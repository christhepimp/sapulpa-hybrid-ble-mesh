package com.sapulpa.blemesh

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unrooted companion app for the Sapulpa Hybrid BLE Mesh.
 *
 * - Listens on a TCP port (default 5555) that is reached from the host via
 *   `adb forward tcp:5555 tcp:5555`.
 * - When a mesh packet arrives from Python, broadcasts it with
 *   BluetoothLeAdvertiser (no root, normal user-space BLE API).
 * - Continuously scans with BluetoothLeScanner; any interesting advertisement
 *   is tunnelled back to the Python mesh engine over the same socket.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SapulpaBleMesh"
        private const val LISTEN_PORT = 5555
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000sap1-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)
    private var serverJob: Job? = null

    private val logLines = mutableStateListOf<String>()
    private var bridgeStatus by mutableStateOf("Stopped")
    private var lastTx by mutableStateOf("-")
    private var lastRx by mutableStateOf("-")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBridge()
        } else {
            appendLog("Bluetooth permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mgr = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = mgr?.adapter
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        scanner = bluetoothAdapter?.bluetoothLeScanner

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Sapulpa Hybrid BLE Mesh", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Bridge: $bridgeStatus")
                        Text("Last TX (mesh→RF): $lastTx")
                        Text("Last RX (RF→mesh): $lastRx")
                        Spacer(Modifier.height(12.dp))
                        Row {
                            Button(onClick = { requestPermsAndStart() }) {
                                Text("Start Bridge")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { stopBridge() }) {
                                Text("Stop")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Log", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(Modifier.weight(1f)) {
                            items(logLines) { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestPermsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startBridge()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBridge() {
        if (running.getAndSet(true)) return
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            appendLog("Bluetooth not available / disabled")
            running.set(false)
            return
        }
        bridgeStatus = "Listening :$LISTEN_PORT"
        appendLog("Starting TCP server on port $LISTEN_PORT")
        startScanner()
        serverJob = scope.launch {
            try {
                ServerSocket(LISTEN_PORT).use { server ->
                    appendLog("ADB tunnel ready – waiting for Python engine…")
                    while (running.get()) {
                        val client = server.accept()
                        appendLog("Python engine connected from ${client.inetAddress}")
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                appendLog("Server error: ${e.message}")
                bridgeStatus = "Error"
            }
        }
    }

    private fun stopBridge() {
        running.set(false)
        stopScanner()
        stopAdvertising()
        serverJob?.cancel()
        bridgeStatus = "Stopped"
        appendLog("Bridge stopped")
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = DataInputStream(socket.getInputStream())
            while (running.get() && !socket.isClosed) {
                val len = input.readInt()
                if (len <= 0 || len > 1_000_000) break
                val buf = ByteArray(len)
                input.readFully(buf)
                val json = JSONObject(String(buf, Charsets.UTF_8))
                when (json.optString("type")) {
                    "mesh_to_rf" -> {
                        val hex = json.optString("payload_b64")
                        val payload = hexStringToByteArray(hex)
                        val meta = json.optJSONObject("meta")
                        appendLog("mesh→RF ${payload.size} B  meta=$meta")
                        lastTx = "${payload.size} B"
                        broadcastPayload(payload)
                    }
                    else -> appendLog("Unknown type ${json.optString("type")}")
                }
            }
        } catch (e: Exception) {
            appendLog("Client disconnected: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun broadcastPayload(payload: ByteArray) {
        val adv = advertiser ?: run {
            appendLog("No BLE advertiser")
            return
        }
        val data = payload.take(20).toByteArray()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(3000)
            .build()
        val advData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .addServiceData(ParcelUuid(MESH_SERVICE_UUID), data)
            .setIncludeDeviceName(false)
            .build()
        try {
            adv.startAdvertising(settings, advData, advertiseCallback)
            appendLog("Advertising ${data.size} B for 3 s")
        } catch (e: SecurityException) {
            appendLog("Advertise permission error: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertise started")
        }
        override fun onStartFailure(errorCode: Int) {
            appendLog("Advertise failed code=$errorCode")
        }
    }

    private fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}
    }

    private fun startScanner() {
        val sc = scanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            sc.startScan(null, settings, scanCallback)
            appendLog("BLE scanner started")
        } catch (e: SecurityException) {
            appendLog("Scan permission error: ${e.message}")
        }
    }

    private fun stopScanner() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val record = result.scanRecord ?: return
            val serviceData = record.getServiceData(ParcelUuid(MESH_SERVICE_UUID))
            if (serviceData != null && serviceData.isNotEmpty()) {
                lastRx = "${serviceData.size} B from ${result.device?.address}"
                appendLog("RF→mesh ${serviceData.size} B  rssi=${result.rssi}")
            }
        }
        override fun onScanFailed(errorCode: Int) {
            appendLog("Scan failed code=$errorCode")
        }
    }

    private fun appendLog(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread {
            logLines.add(0, msg)
            if (logLines.size > 200) logLines.removeLast()
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                    + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    override fun onDestroy() {
        stopBridge()
        scope.cancel()
        super.onDestroy()
    }
}
