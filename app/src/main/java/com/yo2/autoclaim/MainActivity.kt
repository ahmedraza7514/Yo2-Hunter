package com.yo2.autoclaim

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val BgColor = Color(0xFF0B0F19)
private val CardColor = Color(0xFF121A2E)
private val AccentRed = Color(0xFFE11D48)
private val GoldColor = Color(0xFFFBBF24)
private val MutedText = Color(0xFF94A3B8)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AccentRed,
                    background = BgColor,
                    surface = CardColor
                )
            ) {
                MainScreen(this)
            }
        }
    }
}

@Composable
private fun MainScreen(activity: MainActivity) {
    val context = activity
    val prefs = remember { AppPreferences(context) }

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick

    val running = AutomationStateMachine.isRunning()
    val state = AutomationStateMachine.currentState
    val stats = AutomationStateMachine.getStats()
    val logs = AutomationStateMachine.getLogs()
    val sessionMs = AutomationStateMachine.sessionElapsedMs()

    val a11yEnabled = isAccessibilityEnabled(context)
    val overlayEnabled = Settings.canDrawOverlays(context)
    val batteryIgnored = isBatteryIgnored(context)

    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AccentRed),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp)
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text("Yo2 Auto-Claim", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Reliable Red Envelope Assistant", color = MutedText, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { showSettings = !showSettings }) {
                Text("⚙", fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val statusText = when {
            !running -> "SERVICE IDLE"
            state == AutomationState.PAUSED -> "PAUSED"
            else -> "SERVICE RUNNING"
        }
        val statusColor = when {
            !running -> MutedText
            state == AutomationState.PAUSED -> GoldColor
            else -> Color(0xFF4ADE80)
        }
        Text(statusText, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(12.dp))

        if (!running) {
            Button(
                onClick = { ForegroundAutomationService.start(context) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                modifier = Modifier.fillMaxWidth()
            ) { Text("▶  Start Automation", color = Color.White) }
        } else {
            Button(
                onClick = {
                    ForegroundAutomationService.stop(context)
                    AutomationStateMachine.stop("main screen stop")
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                modifier = Modifier.fillMaxWidth()
            ) { Text("■  Stop Automation", color = Color.White) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Claimed", stats.claimed.toString(), Modifier.weight(1f))
            StatCard("Failed / Missed", stats.failed.toString(), Modifier.weight(1f))
            StatCard("Session", formatDuration(sessionMs), Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Detected", stats.detected.toString(), Modifier.weight(1f))
            StatCard(
                "Accessibility",
                if (AutomationAccessibilityService.isServiceConnected) "Connected" else "Off",
                Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Permissions", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        PermissionRow("Accessibility Service", a11yEnabled) {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        PermissionRow("Display Over Other Apps", overlayEnabled) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            activity.startActivity(intent)
        }
        PermissionRow("Ignore Battery Optimization", batteryIgnored) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            activity.startActivity(intent)
        }

        if (showSettings) {
            Spacer(modifier = Modifier.height(16.dp))
            SettingsSection(prefs)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Live Activity Logs", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = CardColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (logs.isEmpty()) {
                    Text("No activity yet.", color = MutedText, fontSize = 12.sp)
                } else {
                    logs.take(40).forEach { entry ->
                        Text(
                            "${entry.time}  ${entry.message}",
                            color = logColor(entry.tag),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(label, color = MutedText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (granted) "✔" else "✖",
            color = if (granted) Color(0xFF4ADE80) else AccentRed,
            fontSize = 14.sp
        )
        Text(
            label, color = Color.White, fontSize = 13.sp,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
        )
        if (!granted) {
            OutlinedButton(onClick = onOpen) { Text("Open", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SettingsSection(prefs: AppPreferences) {
    var targetPackages by remember { mutableStateOf(prefs.targetPackages) }
    var envelopeKeywords by remember { mutableStateOf(prefs.envelopeKeywords) }
    var openKeywords by remember { mutableStateOf(prefs.openKeywords) }
    var successKeywords by remember { mutableStateOf(prefs.successKeywords) }
    var scanInterval by remember { mutableStateOf(prefs.scanIntervalMs.toFloat()) }
    var soundOn by remember { mutableStateOf(prefs.enableSound) }
    var vibrationOn by remember { mutableStateOf(prefs.enableVibration) }
    var overlayOn by remember { mutableStateOf(prefs.showFloatingOverlay) }
    var savedMsg by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Detection & Timing Configuration",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            SettingsField("Target Packages (comma separated)", targetPackages) { targetPackages = it }
            SettingsField("Envelope Match Keywords", envelopeKeywords) { envelopeKeywords = it }
            SettingsField("Open Button Keywords", openKeywords) { openKeywords = it }
            SettingsField("Success Keywords", successKeywords) { successKeywords = it }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Scan Loop Interval: ${scanInterval.toInt()} ms",
                color = Color.White, fontSize = 13.sp
            )
            Slider(
                value = scanInterval,
                onValueChange = { scanInterval = it },
                valueRange = 150f..1000f
            )

            SettingsToggle("Claim Success Sound", soundOn) { soundOn = it }
            SettingsToggle("Claim Success Vibration", vibrationOn) { vibrationOn = it }
            SettingsToggle("Show Floating Overlay", overlayOn) { overlayOn = it }

            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    prefs.targetPackages = targetPackages
                    prefs.envelopeKeywords = envelopeKeywords
                    prefs.openKeywords = openKeywords
                    prefs.successKeywords = successKeywords
                    prefs.scanIntervalMs = scanInterval.toLong()
                    prefs.enableSound = soundOn
                    prefs.enableVibration = vibrationOn
                    prefs.showFloatingOverlay = overlayOn
                    savedMsg = "Settings saved ✔"
                    AutomationStateMachine.addLog("Settings updated", "INFO")
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Settings", color = Color.White) }
            if (savedMsg.isNotEmpty()) {
                Text(savedMsg, color = Color(0xFF4ADE80), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingsField(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, color = MutedText, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White)
    )
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun logColor(tag: String): Color = when (tag) {
    "CLAIM", "SUCCESS", "START" -> Color(0xFF4ADE80)
    "FAIL", "STOP" -> Color(0xFFF87171)
    "DETECT", "COUNT" -> GoldColor
    "WARN", "PAUSE" -> Color(0xFFFBBF24)
    else -> Color(0xFFCBD5E1)
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    return try {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        enabled.contains(context.packageName, ignoreCase = true)
    } catch (e: Exception) {
        false
    }
}

private fun isBatteryIgnored(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }
}

@Suppress("unused")
private fun isAtLeastApi34(): Boolean = Build.VERSION.SDK_INT >= 34
