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
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground service: keeps BLE advertising + scanning alive in background. */
class MeshForegroundService : Service() {

    companion object {
        private const val TAG = "MeshFgService"
        private const val CHANNEL_ID = "sapulpa_mesh"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.sapulpa.blemesh.STOP"
        val MESH_UUID: UUID = UUID.fromString("0000bf01-0000-1000-8000-00805f9b34fb")
    }

    private val binder = LocalBinder()
    private val running = AtomicBoolean(false)

    val engine = MeshEngine()

    private var adapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    var onStateChanged: ((String) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onNeighborChanged: ((Int) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): MeshForegroundService = this@MeshForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val mgr = getSystemService(BluetoothManager::class.java)
        adapter = mgr?.adapter
        advertiser = adapter?.bluetoothLeAdvertiser
        scanner = adapter?.bluetoothLeScanner
        engine.onLog = { msg -> onLog?.invoke(msg); Log.i(TAG, msg) }
        engine.onNeighborCountChanged = { n -> onNeighborChanged?.invoke(n) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMesh()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Mesh active"))
        startMesh()
        return START_STICKY
    }

    fun startMesh() {
        if (running.getAndSet(true)) return
        if (adapter == null || adapter?.isEnabled != true) {
            emitState("Bluetooth off")
            running.set(false)
            return
        }
        emitState("Scanning + Advertising")
        startScanning()
        advertiseLoop()
    }

    fun stopMesh() {
        running.set(false)
        stopScanning()
        stopAdvertising()
        emitState("Stopped")
    }

    fun sendMessage(text: String) {
        val pkt = engine.injectLocalMessage(text)
        advertisePacket(pkt)
    }

    private fun startScanning() {
        val sc = scanner ?: return
        val filter = ScanFilter.Builder()
            .setManufacturerData(MeshPacket.COMPANY_ID, byteArrayOf(MeshPacket.VERSION))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            sc.startScan(listOf(filter), settings, scanCallback)
            log("Scanner started")
        } catch (e: SecurityException) {
            log("Scan permission error: ${e.message}")
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
            val mfg = record.getManufacturerSpecificData(MeshPacket.COMPANY_ID) ?: return
            val peer = result.device?.address
            val toRelay = engine.onReceive(mfg, peer)
            if (toRelay != null) advertisePacket(toRelay)
            onNeighborChanged?.invoke(engine.neighborCount())
        }
        override fun onScanFailed(errorCode: Int) {
            log("Scan failed: $errorCode")
            emitState("Scan failed ($errorCode)")
        }
    }

    private fun advertiseLoop() {
        Thread {
            while (running.get()) {
                try {
                    val pkt = engine.nextToAdvertise()
                    if (pkt != null) {
                        advertisePacket(pkt)
                    } else {
                        val beacon = MeshPacket(
                            hopCount = 1,
                            payload = "HB:${engine.nodeId}",
                            originNodeId = engine.nodeId
                        )
                        advertisePacket(beacon)
                    }
                    engine.pruneSeen()
                    Thread.sleep(2500)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    log("Adv loop: ${e.message}")
                    try { Thread.sleep(3000) } catch (_: Exception) {}
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun advertisePacket(pkt: MeshPacket) {
        val adv = advertiser ?: run {
            log("No advertiser available")
            return
        }
        val data = pkt.toBytes()
        val clipped = if (data.size > 24) data.copyOf(24) else data
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val advData = AdvertiseData.Builder()
            .addManufacturerData(MeshPacket.COMPANY_ID, clipped)
            .setIncludeDeviceName(false)
            .build()
        try {
            adv.stopAdvertising(advertiseCallback)
            adv.startAdvertising(settings, advData, advertiseCallback)
            emitState("Relaying")
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
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            log("Advertise failed: $errorCode")
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                log("Payload too large for BLE advertisement")
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Sapulpa Mesh", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sapulpa BLE Mesh")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun emitState(s: String) {
        onStateChanged?.invoke(s)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
        Log.i(TAG, msg)
    }

    override fun onDestroy() {
        stopMesh()
        super.onDestroy()
    }
}
