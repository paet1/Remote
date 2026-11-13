package at.amperae.vlinkerremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), VLinkerBle.Listener {

    companion object {
        private const val PERM_REQ = 1
    }

    private lateinit var ble: VLinkerBle

    private lateinit var tvSoc: TextView
    private lateinit var tvBattInfo: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnSoc: Button
    private lateinit var btnLock: Button
    private lateinit var btnUnlockDrv: Button
    private lateinit var btnUnlock: Button
    private lateinit var btnHeatOn: Button
    private lateinit var btnHeatOff: Button
    private lateinit var btnTrunk: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    private var socPolling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSoc       = findViewById(R.id.tvSoc)
        tvBattInfo  = findViewById(R.id.tvBattInfo)
        btnConnect  = findViewById(R.id.btnConnect)
        btnSoc      = findViewById(R.id.btnSoc)
        btnLock     = findViewById(R.id.btnLock)
        btnUnlockDrv= findViewById(R.id.btnUnlockDrv)
        btnUnlock   = findViewById(R.id.btnUnlock)
        btnHeatOn   = findViewById(R.id.btnHeatOn)
        btnHeatOff  = findViewById(R.id.btnHeatOff)
        btnTrunk    = findViewById(R.id.btnTrunk)
        tvLog       = findViewById(R.id.tvLog)
        scrollLog   = findViewById(R.id.scrollLog)

        ble = VLinkerBle(this, this)
        setRemoteButtonsEnabled(false)
        btnSoc.isEnabled = false

        btnConnect.setOnClickListener {
            if (ble.isConnected) {
                ble.disconnect()
                onConnectionStateChanged(false)
            } else {
                ensurePermissions { ble.scanAndConnect() }
            }
        }

        btnSoc.setOnClickListener {
            if (socPolling) {
                ble.stopSocPolling()
                socPolling = false
                btnSoc.text = getString(R.string.soc_start)
            } else {
                ble.startSocPolling()
                socPolling = true
                btnSoc.text = getString(R.string.soc_stop)
            }
        }

        btnLock.setOnClickListener      { confirm("Lock vehicle?")       { ble.lock() } }
        btnUnlockDrv.setOnClickListener  { confirm("Unlock driver door?") { ble.unlockDrv() } }
        btnUnlock.setOnClickListener     { confirm("Unlock all doors?")   { ble.unlock() } }
        btnHeatOn.setOnClickListener     { confirm("Preheat ON?")         { ble.preheatOn() } }
        btnHeatOff.setOnClickListener    { confirm("Preheat OFF?")        { ble.preheatOff() } }
        btnTrunk.setOnClickListener      { confirm("Open trunk?")         { ble.trunk() } }
    }

    override fun onDestroy() {
        ble.disconnect()
        super.onDestroy()
    }

    // ── VLinkerBle.Listener ──

    override fun onLog(message: String) {
        runOnUiThread {
            tvLog.append("$message\n")
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            btnConnect.text = if (connected) "Disconnect" else "Connect"
            btnSoc.isEnabled = connected
            setRemoteButtonsEnabled(connected)
            if (!connected) {
                socPolling = false
                btnSoc.text = getString(R.string.soc_start)
                tvSoc.text = getString(R.string.soc_placeholder)
                tvBattInfo.text = getString(R.string.batt_placeholder)
            }
        }
    }

    override fun onSocUpdate(socPercent: Float) {
        runOnUiThread {
            tvSoc.text = String.format(Locale.US, "%.1f %%", socPercent)
        }
    }

    override fun onBatteryInfo(tempC: Int?, voltageV: Float?, currentA: Float?, capacityKwh: Float?) {
        runOnUiThread {
            val parts = mutableListOf<String>()
            tempC?.let       { parts += "${it} °C" }
            voltageV?.let    { parts += String.format(Locale.US, "%.0f V", it) }
            currentA?.let    { parts += String.format(Locale.US, "%.1f A", it) }
            capacityKwh?.let { parts += String.format(Locale.US, "%.1f kWh", it) }
            tvBattInfo.text = if (parts.isNotEmpty()) parts.joinToString("  ·  ") else "Polling …"
        }
    }

    override fun onCommandDone(command: String, success: Boolean) {
        runOnUiThread { setRemoteButtonsEnabled(ble.isConnected) }
    }

    // ── helpers ──

    private fun setRemoteButtonsEnabled(on: Boolean) {
        btnLock.isEnabled = on
        btnUnlockDrv.isEnabled = on
        btnUnlock.isEnabled = on
        btnHeatOn.isEnabled = on
        btnHeatOff.isEnabled = on
        btnTrunk.isEnabled = on
    }

    private fun confirm(title: String, action: () -> Unit) {
        setRemoteButtonsEnabled(false)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Send command to vehicle?")
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("Cancel") { _, _ -> setRemoteButtonsEnabled(ble.isConnected) }
            .setCancelable(false)
            .show()
    }

    // ── permissions ──

    private var pendingAction: (() -> Unit)? = null

    private fun ensurePermissions(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isEmpty()) {
            action()
        } else {
            pendingAction = action
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQ)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pendingAction?.invoke()
        } else {
            onLog("Permissions denied")
        }
        pendingAction = null
    }
}
