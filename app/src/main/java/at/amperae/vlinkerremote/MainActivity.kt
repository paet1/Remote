package at.amperae.vlinkerremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), VLinkerBle.Listener {

    companion object {
        private const val PERM_REQUEST = 1
    }

    private lateinit var ble: VLinkerBle

    private lateinit var btnConnect: Button
    private lateinit var btnLock: Button
    private lateinit var btnUnlock: Button
    private lateinit var btnHeatOn: Button
    private lateinit var btnHeatOff: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.btnConnect)
        btnLock    = findViewById(R.id.btnLock)
        btnUnlock  = findViewById(R.id.btnUnlock)
        btnHeatOn  = findViewById(R.id.btnHeatOn)
        btnHeatOff = findViewById(R.id.btnHeatOff)
        tvLog      = findViewById(R.id.tvLog)
        scrollView = findViewById(R.id.scrollLog)

        tvLog.movementMethod = ScrollingMovementMethod()

        ble = VLinkerBle(this, this)

        setCommandButtonsEnabled(false)

        btnConnect.setOnClickListener {
            if (ble.isConnected) {
                ble.disconnect()
                onConnectionStateChanged(false)
            } else {
                ensurePermissions { ble.scanAndConnect() }
            }
        }

        btnLock.setOnClickListener    { confirmAndRun("Lock vehicle?")    { ble.lock() } }
        btnUnlock.setOnClickListener  { confirmAndRun("Unlock vehicle?")  { ble.unlock() } }
        btnHeatOn.setOnClickListener  { confirmAndRun("Preheat ON?")      { ble.preheatOn() } }
        btnHeatOff.setOnClickListener { confirmAndRun("Preheat OFF?")     { ble.preheatOff() } }
    }

    override fun onDestroy() {
        ble.disconnect()
        super.onDestroy()
    }

    // ── VLinkerBle.Listener ──

    override fun onLog(message: String) {
        runOnUiThread {
            tvLog.append("$message\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            btnConnect.text = if (connected) "Disconnect" else "Connect"
            setCommandButtonsEnabled(connected)
        }
    }

    override fun onCommandSequenceFinished(success: Boolean) {
        runOnUiThread {
            onLog(if (success) "✓ Sequence complete" else "✗ Sequence failed")
            setCommandButtonsEnabled(ble.isConnected)
        }
    }

    // ── helpers ──

    private fun setCommandButtonsEnabled(enabled: Boolean) {
        btnLock.isEnabled = enabled
        btnUnlock.isEnabled = enabled
        btnHeatOn.isEnabled = enabled
        btnHeatOff.isEnabled = enabled
    }

    private fun confirmAndRun(title: String, action: () -> Unit) {
        setCommandButtonsEnabled(false)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Send command to vehicle?")
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("Cancel") { _, _ -> setCommandButtonsEnabled(ble.isConnected) }
            .setCancelable(false)
            .show()
    }

    // ── permissions ──

    private var pendingAction: (() -> Unit)? = null

    private fun ensurePermissions(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isEmpty()) {
            action()
        } else {
            pendingAction = action
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pendingAction?.invoke()
        } else {
            onLog("Permissions denied")
        }
        pendingAction = null
    }
}
