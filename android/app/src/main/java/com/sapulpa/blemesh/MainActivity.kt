package com.sapulpa.blemesh

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** Standalone Sapulpa BLE Mesh – no computer, no Python, no ADB. */
class MainActivity : ComponentActivity() {

    private var service: MeshForegroundService? = null
    private var bound = false

    private val logLines = mutableStateListOf<String>()
    private var nodeId by mutableStateOf("—")
    private var bleState by mutableStateOf("Idle")
    private var neighborCount by mutableIntStateOf(0)
    private var messageText by mutableStateOf("")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startAndBindService()
        } else {
            logLines.add(0, "Permissions denied – mesh cannot start")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as MeshForegroundService.LocalBinder
            service = local.getService()
            bound = true
            nodeId = service!!.engine.nodeId
            service!!.onLog = { msg ->
                runOnUiThread {
                    logLines.add(0, msg)
                    if (logLines.size > 300) logLines.removeLast()
                }
            }
            service!!.onStateChanged = { s -> runOnUiThread { bleState = s } }
            service!!.onNeighborChanged = { n -> runOnUiThread { neighborCount = n } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            bleState = "Disconnected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF42A5F5),
                    surface = Color(0xFF121212),
                    background = Color(0xFF0A0A0A)
                )
            ) {
                Surface(Modifier.fillMaxSize()) {
                    MeshDashboard(
                        nodeId = nodeId,
                        bleState = bleState,
                        neighborCount = neighborCount,
                        logLines = logLines,
                        messageText = messageText,
                        onMessageChange = { messageText = it },
                        onSend = {
                            val t = messageText.trim()
                            if (t.isNotEmpty()) {
                                service?.sendMessage(t)
                                messageText = ""
                            }
                        },
                        onStart = { requestPermsAndStart() },
                        onStop = { stopMesh() }
                    )
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
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startAndBindService()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startAndBindService() {
        val intent = Intent(this, MeshForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bleState = "Starting…"
    }

    private fun stopMesh() {
        service?.stopMesh()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        stopService(Intent(this, MeshForegroundService::class.java))
        bleState = "Stopped"
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}

@Composable
fun MeshDashboard(
    nodeId: String,
    bleState: String,
    neighborCount: Int,
    logLines: List<String>,
    messageText: String,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Sapulpa BLE Mesh", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF90CAF9))
        Text("Standalone · No internet · No computer", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("Node", nodeId, Modifier.weight(1f))
            StatusChip("Peers", neighborCount.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        StatusChip("BLE", bleState, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Start Mesh") }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Inject message into mesh", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = messageText,
                onValueChange = onMessageChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a short message…") },
                singleLine = true,
                maxLines = 1
            )
            Button(onClick = onSend) { Text("Send") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Live log", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp)).padding(8.dp)
        ) {
            items(logLines) { line ->
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFB0BEC5), modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
fun StatusChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = Color(0xFF1E1E1E)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 11.sp, color = Color.Gray)
            Text(value, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}
