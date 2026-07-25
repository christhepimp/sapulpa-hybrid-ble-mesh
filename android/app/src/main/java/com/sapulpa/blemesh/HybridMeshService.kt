package com.sapulpa.blemesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/** Foreground service: Chaquopy Python engine + BLE advertiser/scanner. */
class HybridMeshService : Service(), PythonBridge.Listener {

    companion object {
        private const val TAG = "HybridMesh"
        private const val CHANNEL_ID = "sapulpa_hybrid"
        private const val NOTIF_ID = 77
        const val ACTION_STOP = "com.sapulpa.blemesh.STOP_HYBRID"
        const val COMPANY_ID = 0xFFFF
    }

    private val binder = LocalBinder()
    private lateinit var bridge: PythonBridge
    private var adapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var onLog: ((String) -> Unit)? = null
    var onState: ((String) -> Unit)? = null
    var onStats: ((Int, Int, Int) -> Unit)? = null

    private var engineRunning = false

    inner class LocalBinder : Binder() {
        fun getService(): HybridMeshService = this@HybridMeshService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        bridge = PythonBridge(this)
        bridge.listener = this
        val mgr = getSystemService(BluetoothManager::class.java)
        adapter = mgr?.adapter
        advertiser = adapter?.bluetoothLeAdvertiser
        scanner = adapter?.bluetoothLeScanner
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopHybrid()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification("Hybrid mesh starting…"))
        return START_STICKY
    }

    fun startHybrid() {
        try {
            val status = bridge.startEngine()
            engineRunning = true
            emitState("Python engine running")
            log(status)
            startScanning()
            advertiseHex("01", continuous = true)
        } catch (e: Exception) {
            log("Engine start failed: ${e.message}")
            emitState("Error")
        }
    }

    fun stopHybrid() {
        engineRunning = false
        try { bridge.stopEngine() } catch (_: Exception) {}
        stopScanning()
        stopAdvertising()
        emitState("Stopped")
    }

    fun inject(text: String) {
        if (!engineRunning) {
            log("Engine not running")
            return
        }
        Thread {
            try {
                log(bridge.injectMessage(text))
            } catch (e: Exception) {
                log("Inject error: ${e.message}")
            }
        }.start()
    }

    override fun onLog(msg: String) {
        mainHandler.post { log(msg) }
    }

    override fun onTx(payloadHex: String, metaJson: String) {
        mainHandler.post {
            log("Python→BLE TX ${payloadHex.length / 2} B")
            advertiseHex(payloadHex, continuous = false)
        }
    }

    override fun onStats(nodes: Int, edges: Int, bridges: Int) {
        mainHandler.post {
            onStats?.invoke(nodes, edges, bridges)
            emitState("nodes=$nodes edges=$edges bridges=$bridges")
        }
    }

    private fun startScanning() {
        val sc = scanner ?: return
        val filter = ScanFilter.Builder()
            .setManufacturerData(COMPANY_ID, byteArrayOf(0x01))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            sc.startScan(listOf(filter), settings, scanCallback)
            log("BLE scanner started")
        } catch (e: SecurityException) {
            log("Scan permission: ${e.message}")
        } catch (e: Exception) {
            log("Scan error: ${e.message}")
        }
    }

    private fun stopScanning() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val record = result.scanRecord ?: return
            val mfg = record.getManufacturerSpecificData(COMPANY_ID) ?: return
            if (mfg.isEmpty()) return
            val hex = mfg.joinToString("") { "%02x".format(it) }
            val peer = result.device?.address ?: ""
            Thread {
                try {
                    val r = bridge.onBleRx(hex, peer)
                    if (r != "duplicate") mainHandler.post { log("BLE→Python: $r from $peer") }
                } catch (e: Exception) {
                    mainHandler.post { log("onBleRx error: ${e.message}") }
                }
            }.start()
        }
        override fun onScanFailed(errorCode: Int) { log("Scan failed: $errorCode") }
    }

    private fun advertiseHex(hex: String, continuous: Boolean) {
        val adv = advertiser ?: run { log("No BLE advertiser"); return }
        val raw = try {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            log("Bad hex"); return
        }
        val clipped = if (raw.size > 24) raw.copyOf(24) else raw
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(if (continuous) 0 else 3000)
            .build()
        val data = AdvertiseData.Builder()
            .addManufacturerData(COMPANY_ID, clipped)
            .setIncludeDeviceName(false)
            .build()
        try {
            adv.stopAdvertising(advertiseCallback)
            adv.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            log("Advertise permission: ${e.message}")
        } catch (e: Exception) {
            log("Advertise error: ${e.message}")
        }
    }

    private fun stopAdvertising() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { Log.d(TAG, "Advertising ok") }
        override fun onStartFailure(errorCode: Int) { log("Advertise failed: $errorCode") }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Sapulpa Hybrid Mesh", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, HybridMeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sapulpa Hybrid Mesh")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun emitState(s: String) {
        onState?.invoke(s)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(s))
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }

    override fun onDestroy() {
        stopHybrid()
        super.onDestroy()
    }
}
