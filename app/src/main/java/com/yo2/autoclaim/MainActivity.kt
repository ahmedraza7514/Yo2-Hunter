package com.yo2.autoclaim

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var toggleBtn: Button
    private lateinit var statsTv: TextView
    private lateinit var logTv: TextView
    private lateinit var logScroll: ScrollView

    private val logListener: () -> Unit = {
        runOnUiThread {
            logTv.text = LogStore.text()
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            refreshStats()
            OverlayManager.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        LogStore.add("APP", "Yo2 Auto-Claim khula")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }

        val title = TextView(this).apply {
            text = "Yo2 Auto-Claim"
            textSize = 24f
        }
        root.addView(title)

        toggleBtn = Button(this)
        toggleBtn.setOnClickListener { toggleAutomation() }
        root.addView(toggleBtn)

        statsTv = TextView(this).apply { textSize = 15f }
        root.addView(statsTv)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val overlayBtn = Button(this).apply {
            text = "Overlay Permission"
            setOnClickListener { requestOverlay() }
        }
        val a11yBtn = Button(this).apply {
            text = "Accessibility ON karo"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        row1.addView(overlayBtn); row1.addView(a11yBtn)
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val batteryBtn = Button(this).apply {
            text = "Battery Ignore"
            setOnClickListener { requestBatteryIgnore() }
        }
        val resetBtn = Button(this).apply {
            text = "Counters Reset"
            setOnClickListener {
                Prefs.resetStats()
                refreshStats(); OverlayManager.refresh()
                LogStore.add("APP", "counters reset")
            }
        }
        row2.addView(batteryBtn); row2.addView(resetBtn)
        root.addView(row2)

        val logTitle = TextView(this).apply {
            text = "Log:"
            textSize = 16f
        }
        root.addView(logTitle)

        logTv = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            addView(logTv)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        root.addView(logScroll, lp)

        setContentView(root)
        LogStore.listen(logListener)
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroy() {
        LogStore.unlisten(logListener)
        super.onDestroy()
    }

    private fun toggleAutomation() {
        if (!Prefs.enabled && !OverlayManager.hasPermission(this)) {
            LogStore.add("APP", "pehle Overlay Permission do")
            requestOverlay()
            return
        }
        Prefs.enabled = !Prefs.enabled
        OverlayManager.init(this)
        LogStore.add("APP", if (Prefs.enabled) "AUTOMATION ON" else "AUTOMATION OFF")
        refreshAll()
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            OverlayManager.init(this)
            LogStore.add("APP", "overlay permission OK")
        }
    }

    private fun requestBatteryIgnore() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } else {
                LogStore.add("APP", "battery already ignored")
            }
        } catch (e: Exception) {
            LogStore.add("APP", "battery: ${e.message}")
        }
    }

    private fun refreshStats() {
        statsTv.text =
            "Detected: ${Prefs.detected}\n" +
            "Claimed (direct): ${Prefs.claimedDirect}\n" +
            "Claimed (countdown): ${Prefs.claimedCountdown}\n" +
            "Coins: ${Prefs.coins}\n" +
            "Expired: ${Prefs.expired}"
    }

    private fun refreshAll() {
        toggleBtn.text = if (Prefs.enabled) "STOP Automation" else "START Automation"
        refreshStats()
        logTv.text = LogStore.text()
        OverlayManager.refresh()
    }
}
