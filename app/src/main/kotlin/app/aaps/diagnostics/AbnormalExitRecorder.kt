package app.aaps.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 异常退出原因记录器（"黑匣子"）。
 *
 * 背景：AAPS 自身的日志（logback → AndroidAPS.log）在进程被系统杀死时会"断流"，
 * 只留下最后一行，无法回答"为什么退出"。本记录器把**退出原因**写入独立文件
 * `abnormal_exit.log`，并在每次进程启动时把**上一次进程**的退出原因补记下来。
 *
 * 记录内容：
 *  1. 启动时读取 ActivityManager.getHistoricalProcessExitReasons()：
 *     REASON_CRASH / REASON_CRASH_NATIVE / REASON_ANR / REASON_LOW_MEMORY /
 *     REASON_SIGNALED / REASON_EXCESSIVE_RESOURCE_USAGE / REASON_USER_REQUESTED /
 *     REASON_OTHER 等，附带退出时的 PSS/RSS、退出前 importance、进程存活时长。
 *  2. 未捕获的 Java 异常（线程名 + 完整堆栈），另存 crash_<time>.txt。
 *  3. 运行期心跳（默认 5 分钟）：PSS、Java 堆、线程数、亮屏/省电/充电/电量。
 *     心跳之间的"空洞"可反推出进程被杀死的准确时刻。
 *  4. ANR trace / native tombstone（traceInputStream）落盘。
 *
 * 依赖：纯 Android SDK，不依赖 AAPS 任何模块，便于整体删除或移植。
 * minSdk = 31 ⇒ ApplicationExitInfo 全量可用，无需反射。
 */
object AbnormalExitRecorder {

    private const val TAG = "AbnormalExit"

    /** 单次启动最多回溯多少条历史退出记录（系统上限 64） */
    private const val MAX_HISTORY = 16

    /** 每次启动最多打印多少条历史退出记录（全部打印太长，但保留足够上下文） */
    private const val MAX_PRINT = 6

    /** 心跳间隔（分钟） */
    private const val HEARTBEAT_MINUTES = 5L

    /** 首次心跳延迟（秒） */
    private const val HEARTBEAT_INITIAL_DELAY_SECONDS = 30L

    /** tombstone / ANR trace 最多保存多少字节 */
    private const val MAX_TRACE_BYTES = 256L * 1024L

    /** 单条日志最长长度，避免一次写入过大 */
    private const val MAX_LINE = 4000

    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTsFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AbnormalExitRecorder").apply { isDaemon = true }
    }

    private val installed = AtomicBoolean(false)
    private val heartbeatScheduled = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var prevUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 必须在 MainApp.onCreate() 最前面调用 —— 越早越能捕获启动期崩溃。
     */
    fun install(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!installed.compareAndSet(false, true)) return

        // registerActivityLifecycleCallbacks 是 Application 的方法，Context 上没有
        val app = (ctx as? Application) ?: (context as? Application)

        try {
            prevUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                recordUncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            safeLog("install handler failed: ${t.message}")
        }

        // 读取退出原因放后台线程，避免拖慢冷启动（冷启动过长本身也是被杀的诱因）
        executor.execute {
            runCatching { recordStartup(ctx) }
                .onFailure { safeLog("recordStartup failed: ${it.message}") }
        }

        // 前/后台打点：只用 framework 的 ActivityLifecycleCallbacks，不引入任何新依赖。
        // 与心跳互相印证，可看出"退到后台后多久被杀"。
        if (app != null) runCatching {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private var started = 0
                private var foreground = false

                override fun onActivityStarted(activity: Activity) {
                    if (started == 0 && !foreground) {
                        foreground = true
                        noteForeground()
                    }
                    started++
                }

                override fun onActivityStopped(activity: Activity) {
                    started = (started - 1).coerceAtLeast(0)
                    if (started == 0 && foreground) {
                        foreground = false
                        noteBackground()
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }.onFailure { safeLog("registerActivityLifecycleCallbacks failed: ${it.message}") }
    }

    /** 由 MainApp 在 doInit() 末尾调用，标记 AAPS 已完成基本初始化 */
    fun noteAapsReady() = submit("READY") {
        it.append("doInit completed, uptime=").append(SystemClock.elapsedRealtime() / 1000).append('s')
    }

    fun noteForeground() = submit("FOREGROUND", null)

    fun noteBackground() = submit("BACKGROUND", null)

    /** 干净收尾（onTerminate）——用于把"正常结束"与"被杀"区分开 */
    fun noteCleanShutdown() = submit("STOPPED") { it.append("onTerminate, clean shutdown") }

    // ------------------------------------------------------------------
    // 启动时采集"上一个进程"的退出原因
    // ------------------------------------------------------------------

    private fun recordStartup(ctx: Context) {
        val myPid = Process.myPid()
        val myStartWall = myProcessStartWallClock()
        val myStartUptime = Process.getStartUptimeMillis()

        write(
            "START",
            StringBuilder()
                .append("process started, pid=").append(myPid)
                .append(", app=").append(appVersion(ctx))
                .append(", device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", api=").append(Build.VERSION.SDK_INT)
                .append(", release=").append(Build.VERSION.RELEASE)
                .append(", build=").append(Build.DISPLAY)
                .append(", 本进程启动于=").append(tsFormat.format(Date(myStartWall)))
                .append(" (uptime=").append(myStartUptime / 1000).append("s)")
                .toString()
        )
        write("MEM", "启动初内存: " + memorySummaryOrEmpty(ctx))

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            write("WARN", "ActivityManager 不可用，无法读取历史退出原因")
            scheduleHeartbeat(ctx)
            return
        }

        val reasons = runCatching {
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, MAX_HISTORY)
        }.onFailure { safeLog("getHistoricalProcessExitReasons failed: ${it.message}") }
            .getOrNull()

        if (reasons.isNullOrEmpty()) {
            write("EXIT", "系统没有返回任何历史退出记录（本进程可能是安装后首个进程）")
            scheduleHeartbeat(ctx)
            return
        }

        // 系统返回按时间倒序（最新在前）。全部打印出来，避免"挑错那一条"。
        val sorted = reasons.sortedByDescending { runCatching { it.timestamp }.getOrDefault(0L) }
        write(
            "EXIT-HISTORY",
            "系统返回 ${sorted.size} 条退出记录（可能有脱敏：异常进程的 timestamp/pss/description 会被系统清零）"
        )
        sorted.take(MAX_PRINT).forEachIndexed { idx, info ->
            write("EXIT-HISTORY", describe(info, idx, myStartWall, myPid))
        }
        if (sorted.size > MAX_PRINT) {
            write("EXIT-HISTORY", "... 其余 ${sorted.size - MAX_PRINT} 条已省略（只影响展示，不影响判读）")
        }

        // 结论：找出"本进程之前"最近的一次退出
        val previous = sorted
            .filter { it.pid != myPid }
            .filter { runCatching { it.timestamp }.getOrDefault(0L) in 1..myStartWall }
            .maxByOrNull { it.timestamp }

        if (previous == null) {
            val usable = sorted.count { runCatching { it.timestamp }.getOrDefault(0L) > 0L }
            write(
                "EXIT",
                "本进程之前没有可识别的退出记录（记录总数=${sorted.size}，其中带有效时间戳的=$usable，" +
                    "本进程 pid=$myPid 启动于 ${tsFormat.format(Date(myStartWall))}）"
            )
            scheduleHeartbeat(ctx)
            return
        }

        val normal = previous.reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
            previous.reason == ApplicationExitInfo.REASON_USER_STOPPED

        write(
            if (normal) "EXIT-NORMAL" else "EXIT-ABNORMAL",
            describe(previous, -1, myStartWall, myPid) +
                ", 距本进程启动=" + fmtDuration(myStartWall - previous.timestamp)
        )
        dumpTrace(ctx, previous)
        scheduleHeartbeat(ctx)
    }

    /** idx >= 0 表示历史列表项；idx < 0 表示"上一个进程" */
    private fun describe(info: ApplicationExitInfo, idx: Int, myStartWall: Long, myPid: Int): String {
        val sb = StringBuilder()
        if (idx >= 0) sb.append('#').append(idx + 1).append(' ')
        sb.append("reason=").append(reasonName(info.reason)).append("(code=").append(info.reason).append(')')

        val ts = runCatching { info.timestamp }.getOrDefault(0L)
        sb.append(", importance=").append(importanceName(info.importance))
        sb.append(", status=").append(runCatching { info.status }.getOrDefault(-1))
        sb.append(", pid=").append(info.pid).append(if (info.pid == myPid) "(=当前进程)" else "")
        sb.append(", exitTime=").append(if (ts > 0) tsFormat.format(Date(ts)) else "(系统未提供/已脱敏)")
        sb.append(", pss=").append(kb(info.pss)).append(", rss=").append(kb(info.rss))

        runCatching {
            if (!info.description.isNullOrBlank()) sb.append(", description=").append(info.description)
        }

        if (ts > 0 && myStartWall > ts) {
            sb.append(", 距本次启动=").append(fmtDuration(myStartWall - ts))
        }
        if (info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
        ) {
            sb.append(" [退出时持有前台服务]")
        }
        if (ts <= 0L) {
            sb.append(" [该条记录已被系统脱敏，字段不可信]")
        }
        return sb.toString()
    }

    private fun fmtDuration(ms: Long): String {
        if (ms <= 0) return "0s"
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}分${sec % 60}秒"
            else -> "${sec / 3600}小时${(sec % 3600) / 60}分"
        }
    }

    /**
     * 本进程的启动时刻（wall clock）。
     *
     * 注意：必须用 uptimeMillis 作为时基。Process.getStartUptimeMillis() 返回的是
     * uptimeMillis 基准（不包含深睡），而 SystemClock.elapsedRealtime() 含深睡；
     * 二者混用会让算出的启动时刻偏小，从而把最近几次真实退出记录全部过滤掉
     * —— 这正是旧版本"总是读到最旧那条记录"的原因。
     */
    private fun myProcessStartWallClock(): Long {
        val startUptime = Process.getStartUptimeMillis()
        if (startUptime > 0) {
            return System.currentTimeMillis() - (SystemClock.uptimeMillis() - startUptime)
        }
        // 极少数 ROM 上返回 0，退回"当前时刻"（宁可保守，也不伪造时间）
        return System.currentTimeMillis()
    }

    private fun dumpTrace(ctx: Context, info: ApplicationExitInfo) {
        try {
            val stream = info.traceInputStream ?: return
            stream.use { input ->
                // timestamp 可能为 0（被系统脱敏），此时用当前时间命名避免覆盖
                val ts = runCatching { info.timestamp }.getOrDefault(0L).takeIf { it > 0 }
                    ?: System.currentTimeMillis()
                val target = File(
                    dir(ctx),
                    "exit_trace_${fileTsFormat.format(Date(ts))}_${reasonShort(info.reason)}.txt"
                )
                target.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (total < MAX_TRACE_BYTES) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        val allowed = minOf(read.toLong(), MAX_TRACE_BYTES - total).toInt()
                        output.write(buf, 0, allowed)
                        total += allowed
                    }
                }
                write("TRACE", "退出 trace 已保存: ${target.name} (${target.length()} bytes)")
            }
        } catch (t: Throwable) {
            write("TRACE", "读取退出 trace 失败: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // 未捕获异常
    // ------------------------------------------------------------------

    private fun recordUncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val ctx = appContext
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stack = sw.toString()

            val header = StringBuilder()
                .append("!!! 未捕获异常（Java 崩溃，进程即将终止）!!!")
                .append("\nthread=").append(thread.name).append(" (id=").append(thread.id).append(')')
                .append("\nexception=").append(throwable.javaClass.name).append(": ").append(throwable.message)
                .append('\n').append(memorySummaryOrEmpty(ctx))
                .toString()

            write("CRASH", header)
            write("CRASH-STACK", stack)

            if (ctx != null) {
                val safeName = thread.name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val f = File(
                    dir(ctx),
                    "crash_${fileTsFormat.format(Date(System.currentTimeMillis()))}_$safeName.txt"
                )
                f.writeText("time=${tsFormat.format(Date())}\nversion=${appVersion(ctx)}\n\n$header\n$stack")
                write("CRASH", "崩溃报告已保存: ${f.name}")
            }
        } catch (t: Throwable) {
            safeLog("recordUncaughtException failed: ${t.message}")
        } finally {
            // 交还系统默认处理（触发系统的"应用已停止"并生成 tombstone）
            runCatching { prevUncaughtHandler?.uncaughtException(thread, throwable) }
        }
    }

    // ------------------------------------------------------------------
    // 心跳
    // ------------------------------------------------------------------

    private fun scheduleHeartbeat(ctx: Context) {
        if (!heartbeatScheduled.compareAndSet(false, true)) return
        runCatching {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "AbnormalExitHeartbeat").apply { isDaemon = true }
            }.scheduleWithFixedDelay(
                {
                    runCatching { write("HEARTBEAT", heartbeatSummary(ctx)) }
                        .onFailure { safeLog("heartbeat failed: ${it.message}") }
                },
                HEARTBEAT_INITIAL_DELAY_SECONDS,
                TimeUnit.MINUTES.toSeconds(HEARTBEAT_MINUTES),
                TimeUnit.SECONDS
            )
        }.onFailure { safeLog("scheduleHeartbeat failed: ${it.message}") }
    }

    private fun heartbeatSummary(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("pid=").append(Process.myPid())
        sb.append(", uptime=").append(SystemClock.elapsedRealtime() / 1000).append('s')
        sb.append(", threads=").append(Thread.activeCount())
        sb.append(", ").append(memorySummaryOrEmpty(ctx))

        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            sb.append(", interactive=").append(pm.isInteractive)
            sb.append(", powerSave=").append(pm.isPowerSaveMode)
            sb.append(", deviceIdle=").append(pm.isDeviceIdleMode)
        }
        runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            sb.append(", battery=").append(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).append('%')
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            sb.append(", charging=")
                .append(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun memorySummaryOrEmpty(ctx: Context?): String {
        val sb = StringBuilder()
        runCatching {
            // 注意：Debug.getPss() 在部分厂商 ROM 上返回无意义的极小值，
            // 因此改用 Debug.MemoryInfo（getTotalPss 单位 kB）作为主指标。
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            sb.append("totalPss=").append(mi.totalPss / 1024).append("MB")
            sb.append(", dalvikPss=").append(mi.dalvikPss / 1024).append("MB")
            sb.append(", nativePss=").append(mi.nativePss / 1024).append("MB")
            sb.append(", javaHeapUsed=")
                .append((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024)
                .append("MB")
            sb.append(", javaHeapMax=").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append("MB")
        }
        if (ctx != null) {
            runCatching {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                sb.append(", memoryClass=").append(am.memoryClass).append("MB")
                sb.append(", lowRamDevice=").append(am.isLowRamDevice)
            }
        }
        return sb.toString()
    }

    private fun submit(tag: String, build: ((StringBuilder) -> Unit)?) {
        executor.execute {
            runCatching {
                val sb = StringBuilder()
                build?.invoke(sb)
                write(tag, sb.toString())
            }.onFailure { safeLog("submit($tag) failed: ${it.message}") }
        }
    }

    private fun write(tag: String, message: String) {
        runCatching {
            val ctx = appContext ?: return
            val line = "${tsFormat.format(Date())} | ${tag.padEnd(14)} | ${message.take(MAX_LINE)}"
            File(dir(ctx), LOG_FILE_NAME).appendText(line + "\n")
        }.onFailure { safeLog("write($tag) failed: ${it.message}") }
    }

    private const val LOG_FILE_NAME = "abnormal_exit.log"

    /** 与 AAPS 日志同目录（/sdcard/Android/data/<pkg>/files），会被"发送日志"一并打包 */
    private fun dir(ctx: Context): File {
        val d = ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "logs")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun appVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrDefault("unknown")

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF(自杀/System.exit)"
        ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED(被信号杀死 SIGKILL/SIGSEGV → 系统杀进程或 native 崩溃)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY(内存不足被 LMK 杀死)"
        ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH(Java 未捕获异常)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE(native 崩溃)"
        ApplicationExitInfo.REASON_ANR -> "REASON_ANR(无响应被系统杀死)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE(启动失败)"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE(权限变更)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE(资源占用过高被系统杀死)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED(用户主动结束)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED(用户强制停止)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED(依赖进程死亡)"
        ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER(其他 → 看 trace 文件)"
        ApplicationExitInfo.REASON_FREEZER -> "REASON_FREEZER(被 freezer 冻结/杀死)"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "REASON_PACKAGE_STATE_CHANGE(包状态变更)"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "REASON_PACKAGE_UPDATED(应用被更新)"
        else -> "未知原因($reason)"
    }

    private fun reasonShort(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "java_crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "resource"
        else -> "reason$reason"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY -> "EMPTY"
        else -> "importance$importance"
    }

    private fun kb(bytes: Long): String = "${bytes / 1024}KB"

    private fun safeLog(message: String) {
        runCatching { Log.w(TAG, message) }
    }
}
