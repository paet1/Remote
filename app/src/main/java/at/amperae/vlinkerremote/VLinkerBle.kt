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
 * BLE transport for vLinker MC-IOS (ELM327/STN-compatible).
 *
 * Command sequences are reproduced byte-for-byte from tested nRF Connect macros
 * that have been proven to work on a real Ampera-e / Bolt EV.
 *
 * Two buses:
 *  - HS-CAN (STP 6 / protocol 6, 500 kbps) → OBD-II diagnostic polls (SOC, temps …)
 *  - SWCAN  (STP 61/62, 33.3 kbps)          → vehicle remote commands
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

        val SERVICE_UUID: UUID   = UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID   = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID   = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val CMD_TIMEOUT_MS  = 4_000L
        private const val POLL_INTERVAL_MS = 5_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    val isConnected: Boolean get() = gatt != null && txChar != null

    private val rxBuffer = StringBuilder()
    private var pendingCallback: ((String) -> Unit)? = null
    private var timeoutRunnable: Runnable? = null

    private val cmdQueue = ConcurrentLinkedQueue<Pair<String, (String) -> Unit>>()
    @Volatile private var cmdBusy = false
    @Volatile private var sequenceBusy = false

    private var pollingActive = false
    private var pollRunnable: Runnable? = null

    // ────────────────────────────── BLE scan ──────────────────────────────

    fun scanAndConnect() {
        val s = adapter?.bluetoothLeScanner
        if (s == null) {
            listener.onLog("BLE scanner not available"); return
        }
        scanner = s
        listener.onLog("Scanning for $DEVICE_NAME …")

        val filters = listOf(ScanFilter.Builder().setDeviceName(DEVICE_NAME).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

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
            listener.onLog("Found ${result.device.name ?: result.device.address}")
            result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
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
                listener.onLog("Service discovery failed"); disconnect(); return
            }
            val svc = g.getService(SERVICE_UUID)
            if (svc == null) {
                listener.onLog("vLinker service not found"); disconnect(); return
            }
            txChar = svc.getCharacteristic(TX_CHAR_UUID)
            rxChar = svc.getCharacteristic(RX_CHAR_UUID)
            if (txChar == null || rxChar == null) {
                listener.onLog("TX/RX characteristics missing"); disconnect(); return
            }
            gatt = g

            g.setCharacteristicNotification(rxChar, true)
            rxChar!!.getDescriptor(CCC_DESCRIPTOR)?.let { desc ->
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
        if (rxBuffer.contains('>')) {
            val response = rxBuffer.toString().substringBefore('>').trim()
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

    private fun send(cmd: String, onDone: (String) -> Unit) {
        listener.onLog("→ $cmd")
        enqueue(cmd, onDone)
    }

    // ───────── run a timed sequence (cmd + delay pairs) ─────────

    data class Step(val cmd: String, val delayAfterMs: Long)

    private fun runTimedSteps(steps: List<Step>, done: (Boolean) -> Unit) {
        if (steps.isEmpty()) { done(true); return }
        val step = steps.first()
        val rest = steps.drop(1)
        send(step.cmd) { resp ->
            val line = resp.lines().lastOrNull()?.trim() ?: resp.trim()
            if (line.isNotBlank()) listener.onLog("← $line")
            if (line == "?") {
                listener.onLog("⚠ Command not supported: ${step.cmd}")
                done(false)
            } else {
                handler.postDelayed({ runTimedSteps(rest, done) }, step.delayAfterMs)
            }
        }
    }

    // ────────────────────── adapter initialisation ──────────────────────

    private fun initAdapter() {
        listener.onLog("Initialising adapter …")
        runTimedSteps(listOf(
            Step("ATE0",  300),
            Step("ATL0",  150),
            Step("ATS1",  150),
            Step("ATH1",  150),
        )) { ok ->
            listener.onLog(if (ok) "Adapter ready" else "⚠ Init incomplete")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LIVE SOC  –  via HS-CAN (protocol 6, 500 kbps)
    // ═══════════════════════════════════════════════════════════════

    fun startSocPolling() {
        if (pollingActive) return
        pollingActive = true
        listener.onLog("Starting SOC polling …")
        setupHsCan { schedulePoll() }
    }

    fun stopSocPolling() {
        pollingActive = false
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        listener.onLog("SOC polling stopped")
    }

    private fun setupHsCan(then: () -> Unit) {
        runTimedSteps(listOf(
            Step("ATSP6",     250),   // ISO 15765-4 CAN 500 kbps 11-bit
            Step("STPO",      200),   // open protocol (needed on STN chips)
            Step("ATST96",    200),   // timeout ~600 ms
            Step("ATAL",      150),   // allow long messages
            Step("ATH1",      150),   // headers on for reliable parsing
            Step("ATS1",      150),   // spaces on
            Step("ATSH7E4",   150),   // set TX header to battery ECU
            Step("ATCRA7EC",  150),   // filter RX to battery ECU response only
            Step("ATFCSH7E4", 150),   // flow control source header
            Step("ATFCSD300000",150), // flow control data
            Step("ATFCSM1",   150),   // flow control mode: user defined
        )) { ok ->
            if (!ok) listener.onLog("⚠ HS-CAN setup incomplete")
            then()
        }
    }

    private fun schedulePoll() {
        if (!pollingActive || !isConnected) return
        pollSoc()
    }

    private fun pollSoc() {
        send("228334") { resp ->
            parseSocResponse(resp)
            if (pollingActive) pollBatteryInfo()
        }
    }

    private fun parseSocResponse(resp: String) {
        val parts = resp.replace("\r", " ").replace("\n", " ").trim()
            .split("\\s+".toRegex())
        // With ATH1, response is like: 7EC 04 62 83 34 A5
        // Without headers:             62 83 34 A5
        // Find the service response byte "62" followed by PID "83 34"
        val idx = parts.indexOfFirst { it.equals("62", true) }
        if (idx >= 0 && idx + 3 < parts.size
            && parts[idx + 1].equals("83", true)
            && parts[idx + 2].equals("34", true)
        ) {
            parts[idx + 3].toIntOrNull(16)?.let { b ->
                val soc = b * 100f / 255f
                listener.onLog("SOC: %.1f%%".format(soc))
                listener.onSocUpdate(soc)
                return
            }
        }
        val cleaned = resp.trim()
        if (cleaned.contains("NO DATA", true)) {
            listener.onLog("SOC: NO DATA – ECU not responding")
        } else if (cleaned.contains("ERROR", true)) {
            listener.onLog("SOC: CAN ERROR")
        } else if (cleaned.isNotBlank()) {
            listener.onLog("SOC raw: $cleaned")
        }
    }

    private fun pollBatteryInfo() {
        send("22434F") { tempResp ->
            val tempC = parseSingleByte(tempResp, "43", "4F")?.let { it - 40 }
            send("22436B") { vResp ->
                val voltage = parseTwoBytes(vResp, "43", "6B")?.let { it / 2f }
                send("22436C") { cResp ->
                    val current = parseTwoBytes(cResp, "43", "6C")?.let { it / 20f }
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

    private fun parseSingleByte(resp: String, pidHi: String, pidLo: String): Int? {
        val parts = resp.replace("\r", " ").replace("\n", " ").trim()
            .split("\\s+".toRegex())
        val idx = parts.indexOfFirst { it.equals("62", true) }
        if (idx >= 0 && idx + 3 < parts.size
            && parts[idx + 1].equals(pidHi, true)
            && parts[idx + 2].equals(pidLo, true)
        ) return parts[idx + 3].toIntOrNull(16)
        return null
    }

    private fun parseTwoBytes(resp: String, pidHi: String, pidLo: String): Float? {
        val parts = resp.replace("\r", " ").replace("\n", " ").trim()
            .split("\\s+".toRegex())
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
    //  REMOTE COMMANDS  –  via SWCAN
    //
    //  Sequences reproduced 1:1 from tested nRF Connect macros:
    //    AmperaE_OVMS_Wake_PREHEAT_ON_nRF.xml
    //    AmperaE_OVMS_Wake_UNLOCK_nRF.xml
    //    AmperaE_PREHEAT_OFF_nRF.xml
    // ═══════════════════════════════════════════════════════════════

    fun lock()       = executeRemote("Lock",        lockSteps())
    fun unlock()     = executeRemote("Unlock all",  unlockSteps())
    fun unlockDrv()  = executeRemote("Unlock drv",  unlockDrvSteps())
    fun preheatOn()  = executeRemote("Preheat ON",  preheatOnSteps())
    fun preheatOff() = executeRemote("Preheat OFF", preheatOffSteps())
    fun trunk()      = executeRemote("Trunk",       trunkSteps())

    private fun executeRemote(label: String, commandSteps: List<Step>) {
        if (sequenceBusy) {
            listener.onLog("Busy – wait for current command"); return
        }
        if (!isConnected) {
            listener.onCommandDone(label, false); return
        }
        sequenceBusy = true
        val wasPoll = pollingActive
        if (wasPoll) stopSocPolling()

        listener.onLog("── $label ──")
        val fullSequence = wakeSteps() + commandSteps
        runTimedSteps(fullSequence) { ok ->
            listener.onLog(if (ok) "✓ $label done" else "✗ $label failed")
            listener.onCommandDone(label, ok)
            sequenceBusy = false
            if (wasPoll) {
                listener.onLog("Restarting SOC polling …")
                handler.postDelayed({
                    pollingActive = false
                    setupHsCan { startSocPolling() }
                }, 1500)
            }
        }
    }

    /**
     * SWCAN HV wake sequence – exact reproduction of the tested nRF macros.
     *
     * 1. ATE0              echo off
     * 2. STP 61            SWCAN 11-bit 33.3 kbps
     * 3. STPO              open protocol
     * 4. STCSWM 2          HV wakeup transceiver mode
     * 5. ATSH100           set header to 0x100
     * 6. ATRTR             send RTR frame (HV pulse on ID 100)
     * 7. STCSWM 3          return transceiver to normal mode
     * 8. ATSH621           set header to 0x621
     * 9. 00FFFFFFFFFF0000  BCM wake frame (8 bytes)
     * 10. STP 62           switch to SWCAN 29-bit 33.3 kbps
     * 11. STPO             open protocol
     * 12. ATSH1024E097     set extended 29-bit header
     */
    private fun wakeSteps(): List<Step> = listOf(
        Step("ATE0",               200),
        Step("STP 61",             150),
        Step("STPO",               150),
        Step("STCSWM 2",           30),
        Step("ATSH100",            50),
        Step("ATRTR",              20),
        Step("STCSWM 3",          200),
        Step("ATSH621",            50),
        Step("00FFFFFFFFFF0000",  720),
        Step("STP 62",            150),
        Step("STPO",              150),
        Step("ATSH1024E097",       50),
    )

    private fun preheatOnSteps(): List<Step> = listOf(
        Step("8001FF",   360),
        Step("0000FF",     0),
    )

    private fun preheatOffSteps(): List<Step> = listOf(
        Step("4001FF",   360),
        Step("0000FF",     0),
    )

    private fun unlockSteps(): List<Step> = listOf(
        Step("0003FF",   180),
        Step("0C00FF",  1250),
        Step("0000FF",     0),
    )

    private fun unlockDrvSteps(): List<Step> = listOf(
        Step("0002FF",   180),
        Step("0C00FF",  1250),
        Step("0000FF",     0),
    )

    private fun lockSteps(): List<Step> = listOf(
        Step("0001FF",   180),
        Step("0C00FF",  1250),
        Step("0000FF",     0),
    )

    private fun trunkSteps(): List<Step> = listOf(
        Step("0200FF",     0),
    )

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
