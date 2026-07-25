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

/** Hybrid dashboard: Chaquopy Python engine + native BLE. */
class MainActivity : ComponentActivity() {

    private var service: HybridMeshService? = null
    private var bound = false

    private val logLines = mutableStateListOf<String>()
    private var engineState by mutableStateOf("Idle")
    private var statsText by mutableStateOf("—")
    private var messageText by mutableStateOf("")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startAndBind()
        else logLines.add(0, "Permissions denied")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as HybridMeshService.LocalBinder
            service = local.getService()
            bound = true
            service!!.onLog = { msg ->
                runOnUiThread {
                    logLines.add(0, msg)
                    if (logLines.size > 400) logLines.removeLast()
                }
            }
            service!!.onState = { s -> runOnUiThread { engineState = s } }
            service!!.onStats = { n, e, b ->
                runOnUiThread { statsText = "nodes=$n  edges=$e  bridges=$b" }
            }
            service!!.startHybrid()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            engineState = "Disconnected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF66BB6A),
                    surface = Color(0xFF121212),
                    background = Color(0xFF0A0A0A)
                )
            ) {
                Surface(Modifier.fillMaxSize()) {
                    HybridDashboard(
                        engineState = engineState,
                        statsText = statsText,
                        logLines = logLines,
                        messageText = messageText,
                        onMessageChange = { messageText = it },
                        onSend = {
                            val t = messageText.trim()
                            if (t.isNotEmpty()) {
                                service?.inject(t)
                                messageText = ""
                            }
                        },
                        onStart = { requestPermsAndStart() },
                        onStop = { stopHybrid() }
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
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startAndBind()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startAndBind() {
        val intent = Intent(this, HybridMeshService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        engineState = "Starting…"
    }

    private fun stopHybrid() {
        service?.stopHybrid()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        stopService(Intent(this, HybridMeshService::class.java))
        engineState = "Stopped"
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
fun HybridDashboard(
    engineState: String,
    statsText: String,
    logLines: List<String>,
    messageText: String,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Sapulpa Hybrid Mesh", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFFA5D6A7))
        Text("Python engine + native BLE · no computer", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Column(Modifier.padding(12.dp)) {
                Text("Python engine", fontSize = 11.sp, color = Color.Gray)
                Text(engineState, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text("Grid", fontSize = 11.sp, color = Color.Gray)
                Text(statsText, fontWeight = FontWeight.Medium, color = Color(0xFFB0BEC5))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Start Hybrid") }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Inject into Python mesh", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = messageText, onValueChange = onMessageChange, modifier = Modifier.weight(1f), placeholder = { Text("Short message…") }, singleLine = true)
            Button(onClick = onSend) { Text("Send") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Live log", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp)).padding(8.dp)) {
            items(logLines) { line ->
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFB0BEC5), modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}
