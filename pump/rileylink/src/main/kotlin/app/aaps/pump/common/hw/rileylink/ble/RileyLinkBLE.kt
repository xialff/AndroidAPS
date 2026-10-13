package app.aaps.pump.common.hw.rileylink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.pump.ByteUtil
import app.aaps.core.utils.pump.ThreadUtil
import app.aaps.pump.common.hw.rileylink.RileyLinkConst
import app.aaps.pump.common.hw.rileylink.RileyLinkUtil
import app.aaps.pump.common.hw.rileylink.ble.data.GattAttributes
import app.aaps.pump.common.hw.rileylink.ble.device.OrangeLinkImpl
import app.aaps.pump.common.hw.rileylink.ble.operations.BLECommOperation
import app.aaps.pump.common.hw.rileylink.ble.operations.BLECommOperationResult
import app.aaps.pump.common.hw.rileylink.ble.operations.CharacteristicReadOperation
import app.aaps.pump.common.hw.rileylink.ble.operations.CharacteristicWriteOperation
import app.aaps.pump.common.hw.rileylink.ble.operations.DescriptorWriteOperation
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkError
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkServiceState
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringKey
import app.aaps.pump.common.hw.rileylink.keys.RileylinkBooleanPreferenceKey
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import org.apache.commons.lang3.StringUtils
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by geoff on 5/26/16.
 * Added: State handling, configuration of RF for different configuration ranges, connection handling
 */
@Singleton
class RileyLinkBLE @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val rileyLinkServiceData: RileyLinkServiceData,
    private val rileyLinkUtil: RileyLinkUtil,
    private val preferences: Preferences,
    private val orangeLink: OrangeLinkImpl,
    private val config: Config,
    /**
     * 只用于"安全性查询"：判断泵当前是否正在执行命令（例如正在输注 bolus）。
     * 看门狗在泵忙碌时绝不重建连接 —— 见 scheduleConnectionWatchdog()。
     */
    private val activePlugin: ActivePlugin
) {

    companion object {

        /**
         * 连接看门狗超时（毫秒）。
         *
         * 背景：Android 的 GATT 回调在某些情况下会静默丢失（厂商蓝牙栈问题）。
         * 此时 connectGatt() 已发出但 onConnectionStateChange 永远不来，
         * AAPS 会永远卡在 CONNECTING（实测可卡 26 分钟以上、反复重试 11 次），
         * 只有杀掉进程重启才能恢复——因为只有进程销毁才会回收泄漏的 GATT client。
         *
         * 这里加一个看门狗：超过该时间仍未连上就强制释放并重建连接。
         */
        private const val CONNECTION_WATCHDOG_TIMEOUT_MS = 45_000L

        /** 看门狗最多连续重建几次（避免无限重建耗尽蓝牙栈资源） */
        private const val WATCHDOG_MAX_RESTARTS = 5

        /** 连续重建达到上限后，等这么久再重新开始一轮 */
        private const val CONNECTION_WATCHDOG_RETRY_AFTER_GIVE_UP_MS = 5 * 60_000L
    }

    private val gattDebugEnabled = true

    /** 看门狗用的主线程 Handler */
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRestarts = 0

    /** 是否已排入看门狗（幂等保护，避免重复调度与互相取消） */
    private var watchdogScheduled = false
    private var manualDisconnect = false

    //val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    val bluetoothAdapter: BluetoothAdapter? get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private val bluetoothGattCallback: BluetoothGattCallback
    var rileyLinkDevice: BluetoothDevice? = null
    private var bluetoothConnectionGatt: BluetoothGatt? = null
    private var mCurrentOperation: BLECommOperation? = null
    private val gattOperationSema = Semaphore(1, true)
    private var radioResponseCountNotified: Runnable? = null
    var isConnected = false
        private set

    @Inject fun onInit() {
        //aapsLogger.debug(LTag.PUMPBTCOMM, "BT Adapter: " + this.bluetoothAdapter);
        orangeLink.rileyLinkBLE = this
    }

    private fun isAnyRileyLinkServiceFound(service: BluetoothGattService): Boolean {
        val found = GattAttributes.isRileyLink(service.uuid)
        if (found) return true
        else
            for (serviceI in service.includedServices) {
                if (isAnyRileyLinkServiceFound(serviceI)) return true
                orangeLink.checkIsOrange(serviceI.uuid)
            }
        return false
    }

    fun debugService(service: BluetoothGattService, indentCount: Int, stringBuilder: StringBuilder) {
        val indentString = StringUtils.repeat(' ', indentCount)
        if (gattDebugEnabled) {
            val uuidServiceString = service.uuid.toString()

            stringBuilder.append(indentString)
            stringBuilder.append(GattAttributes.lookup(uuidServiceString, "Unknown service"))
            stringBuilder.append(" ($uuidServiceString)")
            for (character in service.characteristics) {
                val uuidCharacteristicString = character.uuid.toString()
                stringBuilder.append("\n    ")
                stringBuilder.append(indentString)
                stringBuilder.append(" - " + GattAttributes.lookup(uuidCharacteristicString, "Unknown Characteristic"))
                stringBuilder.append(" ($uuidCharacteristicString)")
            }
            stringBuilder.append("\n\n")

            //aapsLogger.warn(LTag.PUMPBTCOMM, stringBuilder.toString());
            for (serviceI in service.includedServices) {
                debugService(serviceI, indentCount + 4, stringBuilder)
            }
        }
    }

    fun registerRadioResponseCountNotification(notifier: Runnable?) {
        radioResponseCountNotified = notifier
    }

    @SuppressLint("MissingPermission")
    fun discoverServices(): Boolean {
        // shouldn't happen, but if it does we exit
        bluetoothConnectionGatt ?: return false

        return if (bluetoothConnectionGatt?.discoverServices() == true) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "Starting to discover GATT Services.")
            true
        } else {
            aapsLogger.error(LTag.PUMPBTCOMM, "Cannot discover GATT Services.")
            false
        }
    }

    fun enableNotifications(): Boolean {
        val result = setNotificationBlocking(UUID.fromString(GattAttributes.SERVICE_RADIO), UUID.fromString(GattAttributes.CHARA_RADIO_RESPONSE_COUNT))
        if (result.resultCode != BLECommOperationResult.RESULT_SUCCESS) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Error setting response count notification")
            return false
        }
        return if (rileyLinkServiceData.isOrange) orangeLink.enableNotifications()
        else true
    }

    fun findRileyLink(rileyLinkAddress: String) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "RileyLink address: $rileyLinkAddress")
        // Must verify that this is a valid MAC, or crash.
        //macAddress = RileyLinkAddress;
        // 无论走哪条路径，都要挂上连接看门狗：
        // 扫描失败（没扫到设备）时原实现只会静默停下，永远不再重试。
        scheduleConnectionWatchdog()
        val useScanning = preferences.get(RileylinkBooleanPreferenceKey.OrangeUseScanning)
        if (useScanning) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Start scan for OrangeLink device.")
            orangeLink.startScan()
        } else {
            rileyLinkDevice = bluetoothAdapter?.getRemoteDevice(rileyLinkAddress)
            // if this succeeds, we get a connection state change callback?
            if (rileyLinkDevice != null) connectGattInternal()
            else aapsLogger.error(LTag.PUMPBTCOMM, "RileyLink device not found with address: $rileyLinkAddress")
        }
    }

    /**
     * 供上层（RileyLinkService 的连接健康检查）调用的主动重连入口。
     * 幂等：已连接或已在调度中时不重复动作。
     */
    @SuppressLint("MissingPermission")
    fun reconnectIfNeeded() {
        if (isConnected) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "reconnectIfNeeded: already connected, nothing to do")
            return
        }
        if (watchdogScheduled) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "reconnectIfNeeded: reconnect already scheduled")
            return
        }
        aapsLogger.warn(LTag.PUMPBTCOMM, "reconnectIfNeeded: forcing GATT release and reconnect")
        manualDisconnect = false
        safeCloseExistingGatt()
        scheduleConnectionWatchdog()
        val useScanning = preferences.get(RileylinkBooleanPreferenceKey.OrangeUseScanning)
        if (useScanning) orangeLink.startScan() else connectGattInternal()
    }

    fun connectGatt() {
        scheduleConnectionWatchdog()
        val useScanning = preferences.get(RileylinkBooleanPreferenceKey.OrangeUseScanning)
        if (useScanning) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Start scan for OrangeLink device.")
            orangeLink.startScan()
        } else {
            connectGattInternal()
        }
    }

    // This function must be run on UI thread.
    @SuppressLint("HardwareIds")
    fun connectGattInternal() {
        // 关键修复：建立新连接前必须先释放旧的 GATT 对象。
        // 原实现直接覆盖 bluetoothConnectionGatt 引用，旧对象只有在收到
        // onConnectionStateChange(STATE_DISCONNECTED) 时才会被 close()。
        // 一旦该回调丢失（厂商蓝牙栈问题），GATT client 资源就永久泄漏，
        // 后续 connectGatt() 会被蓝牙栈静默拒绝且不产生任何回调，
        // 表现为 AAPS 永远卡在 CONNECTING，必须重启进程才能恢复。
        safeCloseExistingGatt()

        if (rileyLinkDevice == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "RileyLink device is null, can't do connectGatt.")
            return
        }
        if (config.PUMPDRIVERS && ContextCompat.checkSelfPermission(context, "android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "no permission")
            return
        } else bluetoothConnectionGatt = rileyLinkDevice?.connectGatt(context, true, bluetoothGattCallback)
        // , BluetoothDevice.TRANSPORT_LE
        if (bluetoothConnectionGatt == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Failed to connect to Bluetooth Low Energy device at " + bluetoothAdapter?.address)
            scheduleConnectionWatchdog()
        } else {
            if (gattDebugEnabled) aapsLogger.debug(LTag.PUMPBTCOMM, "Gatt Connected.")
            bluetoothConnectionGatt?.device?.name?.let { deviceName ->
                // Update stored name upon connecting (also for backwards compatibility for device where a name was not yet stored)
                if (StringUtils.isNotEmpty(deviceName)) preferences.put(RileyLinkStringKey.Name, deviceName)
                else preferences.remove(RileyLinkStringKey.Name)
                rileyLinkServiceData.rileyLinkName = deviceName
                rileyLinkServiceData.rileyLinkAddress = bluetoothConnectionGatt?.device?.address
            }
            // 连接真正建立前挂上看门狗
            scheduleConnectionWatchdog()
        }
    }

    /**
     * 安全释放旧 GATT：先 disconnect 再 close 并置空引用。
     * 不依赖 onConnectionStateChange 回调，避免回调丢失时资源永久泄漏。
     */
    @SuppressLint("MissingPermission")
    private fun safeCloseExistingGatt() {
        val old = bluetoothConnectionGatt ?: return
        aapsLogger.warn(LTag.PUMPBTCOMM, "Releasing previous GATT before reconnecting")
        runCatching {
            old.disconnect()
            old.close()
        }.onFailure { aapsLogger.error(LTag.PUMPBTCOMM, "Failed to release previous GATT: ${it.message}") }
        bluetoothConnectionGatt = null
        isConnected = false
    }

    /**
     * 连接看门狗：若在超时时间内仍未连上，强制释放并重建连接，然后继续守着。
     * 这是"必须手动重启 AAPS 才能恢复"的解药。
     *
     * 设计要点（幂等）：
     *  - 用 watchdogScheduled 标记避免重复调度（多个入口都调用本方法）
     *  - 只有 cancelConnectionWatchdog()（连接真正就绪）或 disconnect()（用户主动断开）才会停
     *  - 每次触发后重新调度，因此会一直守着直到连上，不受"重复调度互相取消"影响
     */
    private fun scheduleConnectionWatchdog() {
        if (watchdogScheduled) return
        // 用户主动断开（配置页移除 RL / 服务销毁）后不得自动重连
        if (manualDisconnect) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Connection watchdog not scheduled: manual disconnect is active")
            return
        }
        watchdogScheduled = true
        watchdogHandler.postDelayed({
            if (manualDisconnect) {
                watchdogScheduled = false
                return@postDelayed
            }
            if (isConnected) {
                watchdogScheduled = false
                watchdogRestarts = 0
                return@postDelayed
            }

            // ★ 输注安全闸 ★
            // 泵正在执行命令（例如正在输注 bolus / 设置临时基础率）时，
            // 绝不允许强制断开重连：那会让 AAPS 无法确认命令是否已下发，
            // 造成"泵已输注但 AAPS 认为失败"的剂量记录错乱。
            // 此时只记录并继续等待，等泵空闲后再重建连接。
            if (isPumpBusy()) {
                aapsLogger.warn(
                    LTag.PUMPBTCOMM,
                    "Connection watchdog: pump is busy executing a command, " +
                        "postponing rebuild (connection must NOT be interrupted during delivery)"
                )
                // 必须复位标记，否则本方法会被开头的 if (watchdogScheduled) 吃掉，
                // 看门狗就永久停了
                watchdogScheduled = false
                scheduleConnectionWatchdog()
                return@postDelayed
            }

            if (watchdogRestarts >= WATCHDOG_MAX_RESTARTS) {
                // 达到重建上限：复位标记后直接排下一次触发。
                // 注意必须复位 watchdogScheduled，否则下面这次调度会被开头的
                // "if (watchdogScheduled) return" 直接吃掉，看门狗永久停止
                // —— 那就等于又回到"必须重启 AAPS"。
                aapsLogger.error(
                    LTag.PUMPBTCOMM,
                    "Connection watchdog: rebuilt $watchdogRestarts times without success, " +
                        "waiting ${CONNECTION_WATCHDOG_RETRY_AFTER_GIVE_UP_MS / 1000}s before next round"
                )
                watchdogRestarts = 0
                watchdogScheduled = false
                watchdogHandler.postDelayed({ scheduleConnectionWatchdog() }, CONNECTION_WATCHDOG_RETRY_AFTER_GIVE_UP_MS)
                return@postDelayed
            }
            watchdogRestarts++
            aapsLogger.error(
                LTag.PUMPBTCOMM,
                "Connection watchdog fired: not connected after ${CONNECTION_WATCHDOG_TIMEOUT_MS / 1000}s, " +
                    "force rebuild (attempt $watchdogRestarts/$WATCHDOG_MAX_RESTARTS)"
            )
            // 清掉可能残留的 GATT，避免泄漏累积（此处泵空闲，不会打断输注）
            safeCloseExistingGatt()
            val useScanning = preferences.get(RileylinkBooleanPreferenceKey.OrangeUseScanning)
            if (useScanning) orangeLink.startScan() else connectGattInternal()
            // 继续守着：先复位标记，再排下一次
            watchdogScheduled = false
            scheduleConnectionWatchdog()
        }, CONNECTION_WATCHDOG_TIMEOUT_MS)
    }

    /**
     * 泵是否正在执行命令（含输注）。
     * 任何异常都按"忙碌"处理（fail-safe）：宁可不重连，也不能打断可能的输注。
     */
    private fun isPumpBusy(): Boolean =
        runCatching { activePlugin.activePump.isBusy() }
            .onFailure { aapsLogger.debug(LTag.PUMPBTCOMM, "isPumpBusy check failed, assuming busy: ${it.message}") }
            .getOrDefault(true)

    /** 连接成功或用户主动断开时取消看门狗并复位计数 */
    private fun cancelConnectionWatchdog() {
        watchdogScheduled = false
        watchdogHandler.removeCallbacksAndMessages(null)
        if (watchdogRestarts != 0) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Connection watchdog reset (was $watchdogRestarts restarts)")
            watchdogRestarts = 0
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        isConnected = false
        cancelConnectionWatchdog()
        aapsLogger.warn(LTag.PUMPBTCOMM, "Closing GATT connection")
        // Close old connection
        if (bluetoothConnectionGatt != null) {
            // Not sure if to disconnect or to close first..
            bluetoothConnectionGatt?.disconnect()
            manualDisconnect = true
            // 关键修复：不再依赖 onConnectionStateChange(STATE_DISCONNECTED) 来触发 close()，
            // 那条回调一旦丢失就会导致 GATT 资源永久占用。
            close()
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        runCatching { bluetoothConnectionGatt?.close() }
            .onFailure { aapsLogger.error(LTag.PUMPBTCOMM, "close() failed: ${it.message}") }
        bluetoothConnectionGatt = null
    }

    @SuppressLint("MissingPermission")
    fun setNotificationBlocking(serviceUUID: UUID?, charaUUID: UUID?): BLECommOperationResult {
        val retValue = BLECommOperationResult()
        if (bluetoothConnectionGatt == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "setNotification_blocking: not configured!")
            retValue.resultCode = BLECommOperationResult.RESULT_NOT_CONFIGURED
            return retValue
        }
        gattOperationSema.acquire()
        SystemClock.sleep(1) // attempting to yield thread, to make sequence of events easier to follow
        if (mCurrentOperation != null) retValue.resultCode = BLECommOperationResult.RESULT_BUSY
        else {
            if (bluetoothConnectionGatt?.getService(serviceUUID) == null) {
                // Catch if the service is not supported by the BLE device
                retValue.resultCode = BLECommOperationResult.RESULT_NONE
                aapsLogger.error(LTag.PUMPBTCOMM, "BT Device not supported")
                // TODO: 11/07/2016 UI update for user
                // xyz rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.NoBluetoothAdapter);
            } else {
                bluetoothConnectionGatt?.let { bluetoothConnectionGatt ->
                    val chara = bluetoothConnectionGatt.getService(serviceUUID)?.getCharacteristic(charaUUID) ?: return retValue.apply { resultCode = BLECommOperationResult.RESULT_NONE }
                    // Tell Android that we want the notifications
                    bluetoothConnectionGatt.setCharacteristicNotification(chara, true)
                    val list = chara.descriptors
                    if (list.isNotEmpty()) {
                        if (gattDebugEnabled) for (i in list.indices) aapsLogger.debug(LTag.PUMPBTCOMM, "Found descriptor: " + list[i].toString())
                        // Tell the remote device to send the notifications
                        mCurrentOperation = DescriptorWriteOperation(aapsLogger, bluetoothConnectionGatt, list[0], BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        mCurrentOperation?.execute(this)
                        when {
                            mCurrentOperation?.timedOut == true    -> retValue.resultCode = BLECommOperationResult.RESULT_TIMEOUT
                            mCurrentOperation?.interrupted == true -> retValue.resultCode = BLECommOperationResult.RESULT_INTERRUPTED
                            else                                   -> retValue.resultCode = BLECommOperationResult.RESULT_SUCCESS
                        }
                    } else return retValue.apply { resultCode = BLECommOperationResult.RESULT_NONE }
                }
            }
            mCurrentOperation = null
            gattOperationSema.release()
        }
        return retValue
    }

    // call from main
    fun writeCharacteristicBlocking(serviceUUID: UUID, charaUUID: UUID, value: ByteArray): BLECommOperationResult {
        val retValue = BLECommOperationResult()
        if (bluetoothConnectionGatt == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "writeCharacteristic_blocking: not configured!")
            retValue.resultCode = BLECommOperationResult.RESULT_NOT_CONFIGURED
            return retValue
        }
        retValue.value = value
        gattOperationSema.acquire()
        SystemClock.sleep(1) // attempting to yield thread, to make sequence of events easier to follow
        if (mCurrentOperation != null) retValue.resultCode = BLECommOperationResult.RESULT_BUSY
        else {
            if (bluetoothConnectionGatt?.getService(serviceUUID) == null) {
                // Catch if the service is not supported by the BLE device
                // GGW: Tue Jul 12 01:14:01 UTC 2016: This can also happen if the
                // app that created the bluetoothConnectionGatt has been destroyed/created,
                // e.g. when the user switches from portrait to landscape.
                retValue.resultCode = BLECommOperationResult.RESULT_NONE
                aapsLogger.error(LTag.PUMPBTCOMM, "BT Device not supported")
                // TODO: 11/07/2016 UI update for user
                // xyz rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.NoBluetoothAdapter);
            } else {
                bluetoothConnectionGatt?.let { bluetoothConnectionGatt ->
                    val chara = bluetoothConnectionGatt.getService(serviceUUID)?.getCharacteristic(charaUUID) ?: return retValue.apply { resultCode = BLECommOperationResult.RESULT_NOT_CONFIGURED }
                    mCurrentOperation = CharacteristicWriteOperation(aapsLogger, bluetoothConnectionGatt, chara, value)
                    mCurrentOperation?.execute(this)
                    when {
                        mCurrentOperation?.timedOut == true    -> retValue.resultCode = BLECommOperationResult.RESULT_TIMEOUT
                        mCurrentOperation?.interrupted == true -> retValue.resultCode = BLECommOperationResult.RESULT_INTERRUPTED
                        else                                   -> retValue.resultCode = BLECommOperationResult.RESULT_SUCCESS
                    }
                }
            }
            mCurrentOperation = null
            gattOperationSema.release()
        }
        return retValue
    }

    fun readCharacteristicBlocking(serviceUUID: UUID?, charaUUID: UUID?): BLECommOperationResult {
        val retValue = BLECommOperationResult()
        if (bluetoothConnectionGatt == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "readCharacteristic_blocking: not configured!")
            retValue.resultCode = BLECommOperationResult.RESULT_NOT_CONFIGURED
            return retValue
        }

        gattOperationSema.acquire()
        SystemClock.sleep(1) // attempting to yield thread, to make sequence of events easier to follow
        if (mCurrentOperation != null) retValue.resultCode = BLECommOperationResult.RESULT_BUSY
        else {
            if (bluetoothConnectionGatt?.getService(serviceUUID) == null) {
                // Catch if the service is not supported by the BLE device
                retValue.resultCode = BLECommOperationResult.RESULT_NONE
                aapsLogger.error(LTag.PUMPBTCOMM, "BT Device not supported")
                // TODO: 11/07/2016 UI update for user
                // xyz rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.NoBluetoothAdapter);
            } else {
                val chara = bluetoothConnectionGatt?.getService(serviceUUID)?.getCharacteristic(charaUUID) ?: return retValue.apply { resultCode = BLECommOperationResult.RESULT_NOT_CONFIGURED }
                mCurrentOperation = CharacteristicReadOperation(aapsLogger, bluetoothConnectionGatt!!, chara)
                mCurrentOperation?.execute(this)
                when {
                    mCurrentOperation?.timedOut == true    -> retValue.resultCode = BLECommOperationResult.RESULT_TIMEOUT
                    mCurrentOperation?.interrupted == true -> retValue.resultCode = BLECommOperationResult.RESULT_INTERRUPTED

                    else                                   -> {
                        retValue.resultCode = BLECommOperationResult.RESULT_SUCCESS
                        retValue.value = mCurrentOperation?.value
                    }
                }
            }
        }
        mCurrentOperation = null
        gattOperationSema.release()

        return retValue
    }

    private fun getGattStatusMessage(status: Int): String =
        when (status) {
            BluetoothGatt.GATT_SUCCESS             -> "SUCCESS"
            BluetoothGatt.GATT_FAILURE             -> "FAILED"
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "NOT PERMITTED"
            133                                    -> "Found the strange 133 bug"
            else                                   -> "UNKNOWN ($status)"
        }

    init {
        //orangeLink.rileyLinkBLE = this;
        bluetoothGattCallback = object : BluetoothGattCallback() {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                super.onCharacteristicChanged(gatt, characteristic)
                if (gattDebugEnabled) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "${ThreadUtil.sig()}onCharacteristicChanged ${GattAttributes.lookup(characteristic.uuid)} ${ByteUtil.getHex(characteristic.value)}")
                    if (characteristic.uuid == UUID.fromString(GattAttributes.CHARA_RADIO_RESPONSE_COUNT))
                        aapsLogger.debug(LTag.PUMPBTCOMM, "Response Count is " + ByteUtil.shortHexString(characteristic.value))
                }
                if (characteristic.uuid == UUID.fromString(GattAttributes.CHARA_RADIO_RESPONSE_COUNT))
                    radioResponseCountNotified?.run()
                orangeLink.onCharacteristicChanged(characteristic, characteristic.value)
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                super.onCharacteristicRead(gatt, characteristic, status)
                val statusMessage = getGattStatusMessage(status)
                if (gattDebugEnabled)
                    aapsLogger.debug(LTag.PUMPBTCOMM, "${ThreadUtil.sig()}onCharacteristicRead (${GattAttributes.lookup(characteristic.uuid)}) $statusMessage:${ByteUtil.getHex(characteristic.value)}")
                mCurrentOperation?.gattOperationCompletionCallback(characteristic.uuid, characteristic.value)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                super.onCharacteristicWrite(gatt, characteristic, status)
                val uuidString = GattAttributes.lookup(characteristic.uuid)
                if (gattDebugEnabled)
                    aapsLogger.debug(LTag.PUMPBTCOMM, "${ThreadUtil.sig()}onCharacteristicWrite ${getGattStatusMessage(status)} $uuidString ${ByteUtil.shortHexString(characteristic.value)}")
                mCurrentOperation?.gattOperationCompletionCallback(characteristic.uuid, characteristic.value)
            }

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)

                // https://github.com/NordicSemiconductor/puck-central-android/blob/master/PuckCentral/app/src/main/java/no/nordicsemi/puckcentral/bluetooth/gatt/GattManager.java#L117
                if (status == 133) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Got the status 133 bug, closing gatt")
                    disconnect()
                    SystemClock.sleep(500)
                    return
                }
                if (gattDebugEnabled) {
                    val stateMessage: String = when (newState) {
                        BluetoothProfile.STATE_CONNECTED     -> "CONNECTED"
                        BluetoothProfile.STATE_CONNECTING    -> "CONNECTING"
                        BluetoothProfile.STATE_DISCONNECTED  -> "DISCONNECTED"
                        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                        else                                 -> "UNKNOWN newState ($newState)"
                    }

                    aapsLogger.warn(LTag.PUMPBTCOMM, "onConnectionStateChange " + getGattStatusMessage(status) + " " + stateMessage)
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (status == BluetoothGatt.GATT_SUCCESS) rileyLinkUtil.sendBroadcastMessage(RileyLinkConst.Intents.BluetoothConnected)
                    else aapsLogger.debug(LTag.PUMPBTCOMM, "BT State connected, GATT status $status (${getGattStatusMessage(status)})")
                } else if (newState == BluetoothProfile.STATE_CONNECTING || newState == BluetoothProfile.STATE_DISCONNECTING) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "We are in ${if (status == BluetoothProfile.STATE_CONNECTING) "Connecting" else "Disconnecting"} state.")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    rileyLinkUtil.sendBroadcastMessage(RileyLinkConst.Intents.RileyLinkDisconnected)
                    if (manualDisconnect) {
                        // 用户主动断开：不自动重连
                        close()
                        manualDisconnect = false
                        cancelConnectionWatchdog()
                        aapsLogger.warn(LTag.PUMPBTCOMM, "RileyLink Disconnected (manual, no auto reconnect).")
                    } else {
                        // 非用户主动断开（链路自己掉了 / 厂商蓝牙栈断开）：
                        // 原实现什么都不做，只能等上层重新发起，很容易永久卡住。
                        // 这里释放可能残留的 GATT 并挂看门狗，由看门狗负责自动重建连接。
                        runCatching { gatt.close() }
                        if (bluetoothConnectionGatt === gatt) bluetoothConnectionGatt = null
                        isConnected = false
                        scheduleConnectionWatchdog()
                        aapsLogger.warn(LTag.PUMPBTCOMM, "RileyLink Disconnected unexpectedly - watchdog will rebuild the connection.")
                    }
                } else {
                    aapsLogger.warn(LTag.PUMPBTCOMM, String.format(Locale.ENGLISH, "Some other state: (status=%d, newState=%d)", status, newState))
                }
            }

            @Suppress("DEPRECATION")
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                super.onDescriptorWrite(gatt, descriptor, status)
                if (gattDebugEnabled)
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onDescriptorWrite ${GattAttributes.lookup(descriptor.uuid)} ${getGattStatusMessage(status)} written: ${ByteUtil.getHex(descriptor.value)}")
                mCurrentOperation?.gattOperationCompletionCallback(descriptor.uuid, descriptor.value)
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                super.onDescriptorRead(gatt, descriptor, status)
                mCurrentOperation?.gattOperationCompletionCallback(descriptor.uuid, descriptor.value)
                if (gattDebugEnabled)
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onDescriptorRead " + getGattStatusMessage(status) + " status " + descriptor)
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                if (gattDebugEnabled)
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onMtuChanged $mtu status $status")
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                super.onReadRemoteRssi(gatt, rssi, status)
                if (gattDebugEnabled)
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onReadRemoteRssi " + getGattStatusMessage(status) + ": " + rssi)
            }

            override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) {
                super.onReliableWriteCompleted(gatt, status)
                if (gattDebugEnabled)
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onReliableWriteCompleted status $status")
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                super.onServicesDiscovered(gatt, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val services = gatt.services
                    var rileyLinkFound = false
                    orangeLink.resetOrangeLinkData()
                    val stringBuilder = StringBuilder("RileyLink Device Debug\n")
                    for (service in services) {
                        val uuidService = service.uuid
                        if (isAnyRileyLinkServiceFound(service)) {
                            rileyLinkFound = true
                        }
                        if (gattDebugEnabled) {
                            debugService(service, 0, stringBuilder)
                        }
                        orangeLink.checkIsOrange(uuidService)
                    }
                    if (gattDebugEnabled) {
                        aapsLogger.warn(LTag.PUMPBTCOMM, stringBuilder.toString())
                        aapsLogger.warn(LTag.PUMPBTCOMM, "onServicesDiscovered " + getGattStatusMessage(status))
                    }
                    aapsLogger.info(LTag.PUMPBTCOMM, "Gatt device is RileyLink device: $rileyLinkFound")
                    if (rileyLinkFound) {
                        isConnected = true
                        // 服务就绪才算真正连上，此时取消看门狗并复位重建计数
                        cancelConnectionWatchdog()
                        rileyLinkUtil.sendBroadcastMessage(RileyLinkConst.Intents.RileyLinkReady)
                    } else {
                        isConnected = false
                        rileyLinkServiceData.setServiceState(
                            RileyLinkServiceState.RileyLinkError,
                            RileyLinkError.DeviceIsNotRileyLink
                        )
                        // 设备不对劲也要能自愈
                        scheduleConnectionWatchdog()
                    }
                } else {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "onServicesDiscovered " + getGattStatusMessage(status))
                    rileyLinkUtil.sendBroadcastMessage(RileyLinkConst.Intents.RileyLinkGattFailed)
                    // GATT 服务发现失败：释放并交给看门狗重试
                    scheduleConnectionWatchdog()
                }
            }
        }
    }
}