package at.amperae.vlinkerremote

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

@SuppressLint("MissingPermission")
class VLinkerBle(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onLog(message: String)
        fun onConnectionStateChanged(connected: Boolean)
        fun onCommandSequenceFinished(success: Boolean)
    }

    companion object {
        const val DEVICE_NAME = "vLinker MC-IOS"

        val SERVICE_UUID: UUID = UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb")

        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val STEP_DELAY_MS = 300L
        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    private var commandQueue: MutableList<String> = mutableListOf()
    private var busy = false

    val isConnected: Boolean get() = gatt != null && txChar != null

    // ── scan ──

    fun scanAndConnect() {
        val s = adapter?.bluetoothLeScanner
        if (s == null) {
            listener.onLog("BLE scanner not available")
            return
        }
        scanner = s
        listener.onLog("Scanning for $DEVICE_NAME …")

        val filters = listOf(
            ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        s.startScan(filters, settings, scanCallback)
        handler.postDelayed({
            stopScan()
            if (gatt == null) {
                listener.onLog("Scan timeout – device not found")
                listener.onConnectionStateChanged(false)
            }
        }, SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            val device = result.device
            listener.onLog("Found ${device.name ?: device.address}, connecting …")
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onLog("Scan failed: $errorCode")
            listener.onConnectionStateChanged(false)
        }
    }

    private fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanner = null
    }

    // ── GATT ──

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onLog("Connected – discovering services …")
                g.discoverServices()
            } else {
                handler.post {
                    listener.onLog("Disconnected (status=$status)")
                    cleanup()
                    listener.onConnectionStateChanged(false)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("Service discovery failed: $status")
                disconnect()
                return
            }
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                listener.onLog("vLinker service not found")
                disconnect()
                return
            }
            txChar = service.getCharacteristic(TX_CHAR_UUID)
            rxChar = service.getCharacteristic(RX_CHAR_UUID)
            if (txChar == null || rxChar == null) {
                listener.onLog("TX/RX characteristics not found")
                disconnect()
                return
            }
            gatt = g

            g.setCharacteristicNotification(rxChar, true)
            val desc = rxChar!!.getDescriptor(CCC_DESCRIPTOR)
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                g.writeDescriptor(desc)
            }

            handler.post {
                listener.onLog("Ready – indications enabled")
                listener.onConnectionStateChanged(true)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val raw = characteristic.value ?: return
            val text = String(raw).trim()
            handler.post {
                listener.onLog("← $text")
                processNext()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post {
                    listener.onLog("Write failed: $status")
                    busy = false
                    commandQueue.clear()
                    listener.onCommandSequenceFinished(false)
                }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post { listener.onLog("Descriptor write failed: $status") }
            }
        }
    }

    // ── send commands ──

    private fun send(cmd: String) {
        val tx = txChar
        val g = gatt
        if (tx == null || g == null) {
            listener.onLog("Not connected")
            return
        }
        listener.onLog("→ $cmd")
        tx.value = "$cmd\r".toByteArray(Charsets.US_ASCII)
        tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        g.writeCharacteristic(tx)
    }

    private fun processNext() {
        if (commandQueue.isEmpty()) {
            busy = false
            listener.onCommandSequenceFinished(true)
            return
        }
        val next = commandQueue.removeAt(0)
        handler.postDelayed({ send(next) }, STEP_DELAY_MS)
    }

    fun runSequence(commands: List<String>) {
        if (busy) {
            listener.onLog("Busy – please wait")
            return
        }
        if (!isConnected) {
            listener.onLog("Not connected")
            listener.onCommandSequenceFinished(false)
            return
        }
        busy = true
        commandQueue = commands.toMutableList()
        val first = commandQueue.removeAt(0)
        send(first)
    }

    // ── vehicle command sequences (wake + action) ──

    private fun wakePrefix(): List<String> = listOf(
        "ATZ",
        "ATE0",
        "STP 61",
        "STCSWM 2",
        "AT SH 100",
        "AT RTR",
        "STCSWM 3",
        "AT SH 621",
        "STP 62",
        "AT SH 1024E097"
    )

    fun lock() {
        runSequence(wakePrefix() + listOf("00 01 FF", "0C 00 FF", "00 00 FF"))
    }

    fun unlock() {
        runSequence(wakePrefix() + listOf("00 03 FF", "0C 00 FF", "00 00 FF"))
    }

    fun preheatOn() {
        runSequence(wakePrefix() + listOf("80 01 FF", "00 00 FF"))
    }

    fun preheatOff() {
        runSequence(wakePrefix() + listOf("40 01 FF", "00 00 FF"))
    }

    // ── lifecycle ──

    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        cleanup()
    }

    private fun cleanup() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        txChar = null
        rxChar = null
        commandQueue.clear()
        busy = false
    }
}
