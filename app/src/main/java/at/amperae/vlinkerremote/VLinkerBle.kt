package at.amperae.vlinkerremote

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BLE transport for vLinker MC-IOS ELM327/STN-compatible adapter.
 *
 * Two buses are used:
 *  - HS-CAN (protocol 6, 500 kbps) for OBD-II diagnostic polls (SOC, battery temp, …)
 *  - SWCAN  (protocol STP 61, 33.3 kbps) for vehicle remote commands (lock, unlock, preheat)
 *
 * ELM327 responses arrive as BLE notification fragments; this class accumulates
 * bytes until the '>' prompt appears, then delivers the complete response to the
 * queued command callback.
 */
@SuppressLint("MissingPermission")
class VLinkerBle(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onLog(message: String)
        fun onConnectionStateChanged(connected: Boolean)
        fun onSocUpdate(socPercent: Float)
        fun onBatteryInfo(tempC: Int?, voltageV: Float?, currentA: Float?, capacityKwh: Float?)
        fun onCommandDone(command: String, success: Boolean)
    }

    companion object {
        const val DEVICE_NAME = "vLinker MC-IOS"

        val SERVICE_UUID: UUID  = UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID  = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID  = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS  = 15_000L
        private const val CMD_TIMEOUT_MS   = 4_000L
        private const val STEP_DELAY_MS    = 120L
        private const val WAKE_DELAY_MS    = 600L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    val isConnected: Boolean get() = gatt != null && txChar != null

    // ── response accumulation ──

    private val rxBuffer = StringBuilder()
    private var pendingCallback: ((String) -> Unit)? = null
    private var timeoutRunnable: Runnable? = null

    private val cmdQueue = ConcurrentLinkedQueue<Pair<String, (String) -> Unit>>()
    @Volatile private var cmdBusy = false
    @Volatile private var sequenceBusy = false

    // ── polling ──

    private var pollingActive = false
    private var pollRunnable: Runnable? = null
    private val POLL_INTERVAL_MS = 5_000L

    // ────────────────────────────── BLE scan ──────────────────────────────

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
            listener.onLog("Found ${device.name ?: device.address}")
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

    // ────────────────────────────── GATT ──────────────────────────────

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
                listener.onLog("Service discovery failed")
                disconnect(); return
            }
            val svc = g.getService(SERVICE_UUID)
            if (svc == null) {
                listener.onLog("vLinker service not found")
                disconnect(); return
            }
            txChar = svc.getCharacteristic(TX_CHAR_UUID)
            rxChar = svc.getCharacteristic(RX_CHAR_UUID)
            if (txChar == null || rxChar == null) {
                listener.onLog("TX/RX characteristics missing")
                disconnect(); return
            }
            gatt = g

            g.setCharacteristicNotification(rxChar, true)
            val desc = rxChar!!.getDescriptor(CCC_DESCRIPTOR)
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                g.writeDescriptor(desc)
            }
            handler.post {
                listener.onLog("BLE ready")
                listener.onConnectionStateChanged(true)
                initAdapter()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            val bytes = characteristic.value ?: return
            val fragment = String(bytes, Charsets.US_ASCII)
            handler.post { onFragment(fragment) }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post {
                    listener.onLog("BLE write failed: $status")
                    completeCurrentCommand("WRITE_ERROR")
                }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post { listener.onLog("Descriptor write failed") }
            }
        }
    }

    // ────────────────────── response accumulation ──────────────────────

    private fun onFragment(fragment: String) {
        rxBuffer.append(fragment)
        val buf = rxBuffer.toString()
        if (buf.contains('>')) {
            val response = buf.substringBefore('>').trim()
            rxBuffer.clear()
            completeCurrentCommand(response)
        }
    }

    private fun completeCurrentCommand(response: String) {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        val cb = pendingCallback
        pendingCallback = null
        cmdBusy = false
        cb?.invoke(response)
        drainQueue()
    }

    // ──────────────────────── command queue ────────────────────────

    private fun enqueue(cmd: String, callback: (String) -> Unit) {
        cmdQueue.add(cmd to callback)
        drainQueue()
    }

    private fun drainQueue() {
        if (cmdBusy) return
        val pair = cmdQueue.poll() ?: return
        cmdBusy = true
        val (cmd, cb) = pair
        pendingCallback = cb
        rxBuffer.clear()
        sendRaw(cmd)

        val timeout = Runnable {
            listener.onLog("⏱ Timeout: $cmd")
            completeCurrentCommand("TIMEOUT")
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, CMD_TIMEOUT_MS)
    }

    private fun sendRaw(cmd: String) {
        val tx = txChar; val g = gatt
        if (tx == null || g == null) return
        tx.value = "$cmd\r".toByteArray(Charsets.US_ASCII)
        tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        g.writeCharacteristic(tx)
    }

    /** Send one AT/data command, wait for complete response, then call [onDone]. */
    private fun send(cmd: String, onDone: (String) -> Unit) {
        listener.onLog("→ $cmd")
        enqueue(cmd, onDone)
    }

    /** Fire-and-forget for AT commands that just return OK. */
    private fun sendOk(cmd: String, then: () -> Unit) {
        send(cmd) { resp ->
            if (!resp.contains("OK") && !resp.isBlank() && resp != "TIMEOUT") {
                listener.onLog("⚠ $cmd → $resp")
            }
            handler.postDelayed(then, STEP_DELAY_MS)
        }
    }

    // ──────────────── run a sequence of commands ────────────────

    private fun runSteps(steps: List<String>, delayMs: Long = STEP_DELAY_MS, done: (Boolean) -> Unit) {
        if (steps.isEmpty()) { done(true); return }
        val first = steps.first()
        val rest = steps.drop(1)
        send(first) { resp ->
            val line = resp.lines().lastOrNull()?.trim() ?: resp.trim()
            listener.onLog("← $line")
            if (line == "?" || line.contains("ERROR")) {
                done(false)
            } else {
                handler.postDelayed({ runSteps(rest, delayMs, done) }, delayMs)
            }
        }
    }

    // ────────────────────── adapter initialisation ──────────────────────

    private fun initAdapter() {
        listener.onLog("Initialising adapter …")
        val initCmds = listOf("ATZ", "ATE0", "ATL0", "ATS1")
        runSteps(initCmds, 300L) { ok ->
            if (ok) listener.onLog("Adapter ready")
            else    listener.onLog("⚠ Init incomplete")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LIVE SOC  –  via HS-CAN (protocol 6, 500 kbps)
    // ═══════════════════════════════════════════════════════════════

    fun startSocPolling() {
        if (pollingActive) return
        pollingActive = true
        listener.onLog("Starting SOC polling …")
        setupHsCan {
            schedulePoll()
        }
    }

    fun stopSocPolling() {
        pollingActive = false
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        listener.onLog("SOC polling stopped")
    }

    private fun setupHsCan(then: () -> Unit) {
        val setup = listOf(
            "ATSP6",       // ISO 15765-4 CAN 500 kbps 11-bit
            "ATST96",      // timeout ~600 ms
            "ATAL",        // allow long messages
            "ATH0",        // headers off for cleaner parsing
            "ATS1"         // spaces on
        )
        runSteps(setup, 200L) { ok ->
            if (!ok) listener.onLog("⚠ HS-CAN setup issue")
            then()
        }
    }

    private fun schedulePoll() {
        if (!pollingActive || !isConnected) return
        pollSoc()
    }

    private fun pollSoc() {
        sendOk("ATSH7E4") {
            send("228334") { resp ->
                parseSocResponse(resp)
                if (pollingActive) pollBatteryInfo()
            }
        }
    }

    private fun parseSocResponse(resp: String) {
        // Expected: "62 83 34 XX" where XX is SOC byte
        val cleaned = resp.replace("\r", " ").replace("\n", " ").trim()
        val parts = cleaned.split("\\s+".toRegex())
        val idx = parts.indexOfFirst { it.equals("62", true) }
        if (idx >= 0 && idx + 3 < parts.size
            && parts[idx + 1].equals("83", true)
            && parts[idx + 2].equals("34", true)
        ) {
            val socByte = parts[idx + 3].toIntOrNull(16)
            if (socByte != null) {
                val soc = socByte * 100f / 255f
                listener.onSocUpdate(soc)
                return
            }
        }
        if (!cleaned.contains("NO DATA", true) && cleaned.isNotBlank()) {
            listener.onLog("SOC parse: $cleaned")
        }
    }

    private fun pollBatteryInfo() {
        send("22434F") { tempResp ->
            val tempC = parseSingleByte(tempResp, "43", "4F")?.let { it - 40 }

            send("22436B") { vResp ->
                val voltage = parseTwoBytes(vResp, "43", "6B")?.let { it / 2f }

                send("22436C") { cResp ->
                    val current = parseTwoBytes(cResp, "43", "6C")?.let { it / 20f }

                    sendOk("ATSH7E4") {
                        send("2245F9") { capResp ->
                            val cap = parseTwoBytes(capResp, "45", "F9")?.let { it * 0.0032f }

                            listener.onBatteryInfo(tempC, voltage, current, cap)

                            if (pollingActive) {
                                val r = Runnable { schedulePoll() }
                                pollRunnable = r
                                handler.postDelayed(r, POLL_INTERVAL_MS)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parseSingleByte(resp: String, pidHi: String, pidLo: String): Int? {
        val parts = resp.replace("\r", " ").replace("\n", " ").trim().split("\\s+".toRegex())
        val idx = parts.indexOfFirst { it.equals("62", true) }
        if (idx >= 0 && idx + 3 < parts.size
            && parts[idx + 1].equals(pidHi, true)
            && parts[idx + 2].equals(pidLo, true)
        ) {
            return parts[idx + 3].toIntOrNull(16)
        }
        return null
    }

    private fun parseTwoBytes(resp: String, pidHi: String, pidLo: String): Float? {
        val parts = resp.replace("\r", " ").replace("\n", " ").trim().split("\\s+".toRegex())
        val idx = parts.indexOfFirst { it.equals("62", true) }
        if (idx >= 0 && idx + 4 < parts.size
            && parts[idx + 1].equals(pidHi, true)
            && parts[idx + 2].equals(pidLo, true)
        ) {
            val hi = parts[idx + 3].toIntOrNull(16) ?: return null
            val lo = parts[idx + 4].toIntOrNull(16) ?: return null
            return (hi * 256 + lo).toFloat()
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    //  REMOTE COMMANDS  –  via SWCAN (STP 61, 33.3 kbps)
    // ═══════════════════════════════════════════════════════════════

    fun lock()       = executeRemote("Lock",       "00 01 FF", "0C 00 FF", "00 00 FF")
    fun unlock()     = executeRemote("Unlock all", "00 03 FF", "0C 00 FF", "00 00 FF")
    fun unlockDrv()  = executeRemote("Unlock drv", "00 02 FF", "0C 00 FF", "00 00 FF")
    fun preheatOn()  = executeRemote("Preheat ON", "00 01 FF", "80 01 FF", "00 00 FF")
    fun preheatOff() = executeRemote("Preheat OFF","00 01 FF", "40 01 FF", "00 00 FF")
    fun trunk()      = executeRemote("Trunk",      "02 00 FF")

    private fun executeRemote(label: String, vararg payloads: String) {
        if (sequenceBusy) {
            listener.onLog("Busy – wait for current command")
            return
        }
        if (!isConnected) {
            listener.onCommandDone(label, false)
            return
        }
        sequenceBusy = true
        val wasPoll = pollingActive
        if (wasPoll) stopSocPolling()

        listener.onLog("── $label ──")
        switchToSwcan {
            wakeSwcanBus {
                sendVehicleCommand(payloads.toList()) { ok ->
                    listener.onLog(if (ok) "✓ $label done" else "✗ $label failed")
                    listener.onCommandDone(label, ok)
                    sequenceBusy = false
                    if (wasPoll) {
                        handler.postDelayed({
                            setupHsCan { startSocPolling() }
                        }, 1000L)
                    }
                }
            }
        }
    }

    /** Configure the adapter for SWCAN (33.3 kbps single-wire CAN). */
    private fun switchToSwcan(then: () -> Unit) {
        val setup = listOf(
            "ATZ",
            "ATE0",
            "ATH1",
            "ATAL",
            "ATS1",
            "STP 61",
            "ATCAF0"
        )
        runSteps(setup, 200L) { ok ->
            if (!ok) listener.onLog("⚠ SWCAN setup issue")
            then()
        }
    }

    /**
     * Full SWCAN high-voltage wakeup: switch transceiver to HV wakeup mode,
     * send wake frames to all known ECU node addresses, then return to normal mode.
     * Reproduces the OnStar/MyVoltStar wake sequence.
     */
    private fun wakeSwcanBus(then: () -> Unit) {
        listener.onLog("Waking SWCAN bus …")
        val wake = mutableListOf<String>()

        wake += "STCSWM 2"                       // HV wakeup mode

        wake += "ATSH 100"
        wake += "00 00 00 00 00 00 00 00"         // initial wake pulse

        for (node in listOf("620", "621", "622")) {
            wake += "ATSH $node"
            wake += "00 FF FF FF FF FF 00 00"
            wake += "01 FF FF FF FF FF 00 00"
        }

        wake += "ATSH 100"
        wake += "00 00 00 00 00 00 00 00"         // mid pulse

        for (node in listOf("627", "62C", "62D")) {
            wake += "ATSH $node"
            wake += "00 FF FF FF FF FF 00 00"
            wake += "01 FF FF FF FF FF 00 00"
        }

        wake += "ATSH 100"
        wake += "00 00 00 00 00 00 00 00"         // final pulse

        wake += "STCSWM 3"                       // back to normal mode

        runSteps(wake, 80L) { _ ->
            listener.onLog("Wake sequence sent")
            handler.postDelayed(then, WAKE_DELAY_MS)
        }
    }

    /**
     * Send vehicle command payloads as extended SWCAN frames (29-bit CAN ID 0x1024E097).
     * ATCP sets the priority nibble (10), ATSH sets the remaining 24 bits (24E097).
     */
    private fun sendVehicleCommand(payloads: List<String>, done: (Boolean) -> Unit) {
        val cmds = mutableListOf<String>()
        cmds += "ATCP 10"
        cmds += "ATSH 24 E0 97"

        for ((i, payload) in payloads.withIndex()) {
            cmds += payload
            if (i < payloads.size - 1) {
                // inter-frame gap handled by runSteps delay
            }
        }

        runSteps(cmds, 500L, done)
    }

    // ────────────────────── lifecycle ──────────────────────

    fun disconnect() {
        stopSocPolling()
        cmdQueue.clear()
        cmdBusy = false
        sequenceBusy = false
        pendingCallback = null
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        try { gatt?.disconnect() } catch (_: Exception) {}
        cleanup()
    }

    private fun cleanup() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        txChar = null
        rxChar = null
        rxBuffer.clear()
    }
}
