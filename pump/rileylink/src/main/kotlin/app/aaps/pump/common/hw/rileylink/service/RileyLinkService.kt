package app.aaps.pump.common.hw.rileylink.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.RileyLinkCommunicationManager
import app.aaps.pump.common.hw.rileylink.RileyLinkUtil
import app.aaps.pump.common.hw.rileylink.ble.RFSpy
import app.aaps.pump.common.hw.rileylink.ble.RileyLinkBLE
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkEncodingType
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkError
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkPumpDevice
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkServiceState
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkDoubleKey
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringPreferenceKey
import app.aaps.pump.common.hw.rileylink.service.tasks.InitializePumpManagerTask
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTaskExecutor
import dagger.android.DaggerService
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider

/**
 * Created by andy on 5/6/18.
 * Split from original file and renamed.
 */
abstract class RileyLinkService : DaggerService() {

    companion object {
        /**
         * 连接健康检查周期。
         * 目的：断连后自动恢复，不再需要用户手动重启 AAPS
         * （夜间断连会造成闭环无法调整基础率，必须能自愈）。
         */
        private const val CONNECTION_CHECK_INTERVAL_MS = 60_000L

        /** 距上次状态变化至少这么久才允许强制重连，避免与正常连接流程/泵命令打架 */
        private const val MIN_RECONNECT_INTERVAL_MS = 120_000L

        /** 连续重连失败次数上限，超过后只记录并按周期继续尝试 */
        private const val MAX_CONSECUTIVE_RECONNECTS = 10
    }

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rileyLinkUtil: RileyLinkUtil
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var rileyLinkServiceData: RileyLinkServiceData
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var rileyLinkBLE: RileyLinkBLE     // android-bluetooth management
    @Inject lateinit var rfSpy: RFSpy // interface for RL xxx Mhz radio.
    @Inject lateinit var serviceTaskExecutor: ServiceTaskExecutor
    @Inject lateinit var initializePumpManagerTaskProvider: Provider<InitializePumpManagerTask>

    private val bluetoothAdapter: BluetoothAdapter? get() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private var broadcastReceiver: RileyLinkBroadcastReceiver? = null
    private var bluetoothStateReceiver: RileyLinkBluetoothStateReceiver? = null

    /** 连接健康检查（自动重连）*/
    private val healthCheckHandler = Handler(Looper.getMainLooper())
    private var consecutiveReconnects = 0

    override fun onCreate() {
        super.onCreate()
        rileyLinkUtil.encoding = encoding
        initRileyLinkServiceData()
        broadcastReceiver = RileyLinkBroadcastReceiver()
        broadcastReceiver?.registerBroadcasts(this)
        bluetoothStateReceiver = RileyLinkBluetoothStateReceiver()
        bluetoothStateReceiver?.registerBroadcasts(this)
        startConnectionHealthMonitor()
    }

    /**
     * Get Encoding for RileyLink communication
     */
    abstract val encoding: RileyLinkEncodingType

    /**
     * If you have customized RileyLinkServiceData you need to override this
     */
    abstract fun initRileyLinkServiceData()

    override fun onDestroy() {
        super.onDestroy()

        stopConnectionHealthMonitor()
        rileyLinkBLE.disconnect() // dispose of Gatt (disconnect and close)
        broadcastReceiver?.unregisterBroadcasts(this)
        bluetoothStateReceiver?.unregisterBroadcasts(this)
    }

    abstract val deviceCommunicationManager: RileyLinkCommunicationManager<*>

    // Here is where the wake-lock begins:
    // We've received a service startCommand, we grab the lock.
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int = START_STICKY

    fun bluetoothInit(): Boolean {
        aapsLogger.debug(LTag.PUMPBTCOMM, "bluetoothInit: attempting to get an adapter")
        rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothInitializing)
        if (bluetoothAdapter == null) {
            aapsLogger.error("Unable to obtain a BluetoothAdapter.")
            rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.NoBluetoothAdapter)
        } else {
            if (bluetoothAdapter?.isEnabled != true) {
                aapsLogger.error("Bluetooth is not enabled.")
                rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.BluetoothDisabled)
            } else {
                rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothReady)
                return true
            }
        }
        return false
    }

    // returns true if our Rileylink configuration changed
    fun reconfigureRileyLink(deviceAddress: String): Boolean {
        rileyLinkServiceData.setServiceState(RileyLinkServiceState.RileyLinkInitializing)
        return if (rileyLinkBLE.isConnected) {
            if (deviceAddress == rileyLinkServiceData.rileyLinkAddress) {
                aapsLogger.info(LTag.PUMPBTCOMM, "No change to RL address.  Not reconnecting.")
                false
            } else {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Disconnecting from old RL (${rileyLinkServiceData.rileyLinkAddress}), reconnecting to new: $deviceAddress")
                rileyLinkBLE.disconnect()
                // need to shut down listening thread too?
                // preferences.put(MedtronicConst.Prefs.RileyLinkAddress, deviceAddress);
                rileyLinkServiceData.rileyLinkAddress = deviceAddress
                rileyLinkBLE.findRileyLink(deviceAddress)
                true
            }
        } else {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Using RL $deviceAddress")
            if (rileyLinkServiceData.rileyLinkServiceState == RileyLinkServiceState.NotStarted) {
                if (!bluetoothInit()) {
                    aapsLogger.error("RileyLink can't get activated, Bluetooth is not functioning correctly. ${rileyLinkServiceData.rileyLinkError?.name ?: "Unknown error (null)"}")
                    return false
                }
            }
            rileyLinkBLE.findRileyLink(deviceAddress)
            true
        }
    }

    // FIXME: This needs to be run in a session so that is incorruptible, has a separate thread, etc.
    fun doTuneUpDevice() {
        rileyLinkServiceData.setServiceState(RileyLinkServiceState.TuneUpDevice)
        setPumpDeviceState(PumpDeviceState.Sleeping)
        val lastGoodFrequency = rileyLinkServiceData.lastGoodFrequency ?: preferences.get(RileyLinkDoubleKey.LastGoodDeviceFrequency)
        val newFrequency = deviceCommunicationManager.tuneForDevice()
        if (newFrequency != 0.0 && newFrequency != lastGoodFrequency) {
            aapsLogger.info(LTag.PUMPBTCOMM, String.format(Locale.ENGLISH, "Saving new pump frequency of %.3f MHz", newFrequency))
            preferences.put(RileyLinkDoubleKey.LastGoodDeviceFrequency, newFrequency)
            rileyLinkServiceData.lastGoodFrequency = newFrequency
            rileyLinkServiceData.tuneUpDone = true
            rileyLinkServiceData.lastTuneUpTime = System.currentTimeMillis()
        }
        if (newFrequency == 0.0) {
            // error tuning pump, pump not present ??
            rileyLinkServiceData.setServiceState(RileyLinkServiceState.PumpConnectorError, RileyLinkError.TuneUpOfDeviceFailed)
        } else {
            deviceCommunicationManager.clearNotConnectedCount()
            rileyLinkServiceData.setServiceState(RileyLinkServiceState.PumpConnectorReady)
        }
    }

    abstract fun setPumpDeviceState(pumpDeviceState: PumpDeviceState)
    fun disconnectRileyLink() {
        if (rileyLinkBLE.isConnected) {
            rileyLinkBLE.disconnect()
            rileyLinkServiceData.rileyLinkAddress = null
            rileyLinkServiceData.rileyLinkName = null
        }
        rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothReady)
    }

    fun changeRileyLinkEncoding(encodingType: RileyLinkEncodingType) {
        rfSpy.setRileyLinkEncoding(encodingType)
    }

    fun verifyConfiguration(): Boolean {
        return verifyConfiguration(false)
    }

    abstract fun verifyConfiguration(forceRileyLinkAddressRenewal: Boolean): Boolean

    // ------------------------------------------------------------------
    // 连接健康检查 / 自动重连
    // ------------------------------------------------------------------

    /**
     * 周期性连接健康检查 + 自动重连。
     *
     * 背景（AAPS 官方 issue #3897 "Connection to RileyLink sometimes can become broken
     * with no automatic recovery till AAPS restart"）：一旦 RileyLink 通信被破坏，
     * 就只能重启 AAPS 才能恢复；夜间断连会导致闭环无法调整基础率。
     *
     * 这里补上周期性兜底：只要服务状态不是"就绪"，就主动走 AAPS 自己的
     * InitializePumpManagerTask 流程重建连接，无需用户重启。
     *
     * 安全性：
     *  - 通过 serviceTaskExecutor（单线程队列）执行，与泵命令天然串行，不会并发打断；
     *  - 泵正在执行命令（isBusy）时跳过本轮；
     *  - 有最小重连间隔（2 分钟）与失败计数，避免频繁重连干扰闭环。
     */
    private fun startConnectionHealthMonitor() {
        healthCheckHandler.removeCallbacksAndMessages(null)
        healthCheckHandler.postDelayed(object : Runnable {
            override fun run() {
                runCatching { checkConnectionHealth() }
                    .onFailure { aapsLogger.error(LTag.PUMPBTCOMM, "Connection health check failed: ${it.message}") }
                healthCheckHandler.postDelayed(this, CONNECTION_CHECK_INTERVAL_MS)
            }
        }, CONNECTION_CHECK_INTERVAL_MS)
        aapsLogger.debug(LTag.PUMPBTCOMM, "Connection health monitor started (every ${CONNECTION_CHECK_INTERVAL_MS / 1000}s)")
    }

    private fun stopConnectionHealthMonitor() {
        healthCheckHandler.removeCallbacksAndMessages(null)
    }

    private fun checkConnectionHealth() {
        if (!isRileyLinkPumpActive()) return

        val state = rileyLinkServiceData.rileyLinkServiceState

        // 已就绪：正常
        if (state == RileyLinkServiceState.PumpConnectorReady) {
            if (consecutiveReconnects != 0) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Connection healthy again, resetting reconnect counter")
                consecutiveReconnects = 0
            }
            return
        }

        // 正在连接/调谐中：给现有流程时间，不抢跑
        if (state == RileyLinkServiceState.RileyLinkInitializing ||
            state == RileyLinkServiceState.BluetoothInitializing ||
            state == RileyLinkServiceState.TuneUpDevice
        ) return

        // 用户主动断开（未配置 RL 地址）后不要自动重连
        if (preferences.get(RileyLinkStringPreferenceKey.MacAddress).isEmpty()) return

        // 泵正在执行命令（例如正在输注）时绝不打断
        if (isPumpBusy()) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Connection unhealthy (state=$state) but pump is busy - postponing reconnect")
            return
        }

        val sinceChange = System.currentTimeMillis() - rileyLinkServiceData.lastServiceStateChange
        if (sinceChange < MIN_RECONNECT_INTERVAL_MS) return

        consecutiveReconnects++
        val over = consecutiveReconnects > MAX_CONSECUTIVE_RECONNECTS
        aapsLogger.error(
            LTag.PUMPBTCOMM,
            "Connection unhealthy (state=$state, no state change for ${sinceChange / 1000}s) - " +
                "auto reconnect attempt $consecutiveReconnects" +
                if (over) " (still retrying every ${CONNECTION_CHECK_INTERVAL_MS / 1000}s)" else ""
        )

        // 先让 BLE 层清掉可能残留的 GATT，再复用 AAPS 自己的初始化流程重建连接
        runCatching { rileyLinkBLE.reconnectIfNeeded() }
            .onFailure { aapsLogger.error(LTag.PUMPBTCOMM, "BLE reconnect failed: ${it.message}") }
        serviceTaskExecutor.startTask(initializePumpManagerTaskProvider.get())
    }

    /** 只对 RileyLink 类泵生效，其它泵驱动完全不受影响 */
    private fun isRileyLinkPumpActive(): Boolean =
        runCatching { activePlugin.activePump is RileyLinkPumpDevice }.getOrDefault(false)

    private fun isPumpBusy(): Boolean =
        runCatching { activePlugin.activePump.isBusy() }.getOrDefault(true)
}