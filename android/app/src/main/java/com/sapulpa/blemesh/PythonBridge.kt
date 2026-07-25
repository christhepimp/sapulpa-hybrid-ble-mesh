package com.sapulpa.blemesh

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * In-process bridge between embedded Python mesh_engine and Kotlin BLE.
 */
class PythonBridge(private val context: Context) {

    companion object {
        private const val TAG = "PythonBridge"
    }

    interface Listener {
        fun onLog(msg: String)
        fun onTx(payloadHex: String, metaJson: String)
        fun onStats(nodes: Int, edges: Int, bridges: Int)
    }

    var listener: Listener? = null
    private var module: PyObject? = null

    fun ensureStarted() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        if (module == null) {
            val py = Python.getInstance()
            module = py.getModule("mesh_engine")
            module!!.put("bridge", Proxy(this))
            Log.i(TAG, "Chaquopy mesh_engine loaded")
        }
    }

    fun startEngine(): String {
        ensureStarted()
        return module!!.callAttr("start_engine").toString()
    }

    fun stopEngine(): String {
        ensureStarted()
        return module!!.callAttr("stop_engine").toString()
    }

    fun isRunning(): Boolean {
        ensureStarted()
        return module!!.callAttr("is_running").toBoolean()
    }

    fun injectMessage(text: String): String {
        ensureStarted()
        return module!!.callAttr("inject_message", text).toString()
    }

    fun onBleRx(payloadHex: String, peer: String = ""): String {
        ensureStarted()
        return module!!.callAttr("on_ble_rx", payloadHex, peer).toString()
    }

    fun getStatus(): String {
        ensureStarted()
        return module!!.callAttr("get_status").toString()
    }

    fun getBridgeCoords(): String {
        ensureStarted()
        return module!!.callAttr("get_bridge_coords").toString()
    }

    class Proxy(private val host: PythonBridge) {
        fun on_log(msg: String) {
            Log.i(TAG, msg)
            host.listener?.onLog(msg)
        }

        fun on_tx(payloadHex: String, metaJson: String) {
            Log.i(TAG, "TX request ${payloadHex.length / 2} B")
            host.listener?.onTx(payloadHex, metaJson)
        }

        fun on_stats(nodes: Int, edges: Int, bridges: Int) {
            host.listener?.onStats(nodes, edges, bridges)
        }
    }
}
