package com.yo2.autoclaim

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating overlay: ON/OFF toggle + live counters.
 * Draggable. Tapping toggles automation on/off.
 */
object OverlayManager {

    private var wm: WindowManager? = null
    private var view: View? = null
    private var statusTv: TextView? = null
    private var countersTv: TextView? = null
    private var appCtx: Context? = null

    fun hasPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true

    @Synchronized
    fun init(ctx: Context) {
        if (view != null) return
        appCtx = ctx.applicationContext
        if (!hasPermission(appCtx!!)) return
        try {
            wm = appCtx!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val layout = LinearLayout(appCtx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(210, 20, 20, 28))
                setPadding(18, 14, 18, 14)
            }
            statusTv = TextView(appCtx).apply {
                textSize = 15f
                setTextColor(Color.WHITE)
            }
            countersTv = TextView(appCtx).apply {
                textSize = 12f
                setTextColor(Color.argb(255, 255, 210, 130))
            }
            layout.addView(statusTv)
            layout.addView(countersTv)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 20; y = 220
            }

            // drag to move; tap to toggle on/off
            var downX = 0f; var downY = 0f; var moved = false
            var startX = 0; var startY = 0
            layout.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (dx * dx + dy * dy > 100) moved = true
                        params.x = startX + dx; params.y = startY + dy
                        try { wm?.updateViewLayout(layout, params) } catch (_: Exception) {}
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) toggleFromOverlay()
                    }
                }
                true
            }

            wm?.addView(layout, params)
            view = layout
            refresh()
        } catch (e: Exception) {
            LogStore.add("OVERLAY", "show failed: ${e.message}")
        }
    }

    private fun toggleFromOverlay() {
        Prefs.enabled = !Prefs.enabled
        LogStore.add("OVERLAY", if (Prefs.enabled) "ON (overlay se)" else "OFF (overlay se)")
        refresh()
    }

    @Synchronized
    fun refresh() {
        val on = Prefs.enabled
        statusTv?.text = if (on) "● Yo2 AC: ON" else "○ Yo2 AC: OFF"
        statusTv?.setTextColor(if (on) Color.argb(255, 120, 255, 140) else Color.WHITE)
        countersTv?.text =
            "D:${Prefs.detected} ✓:${Prefs.claimedDirect + Prefs.claimedCountdown} 🪙:${Prefs.coins} ✗:${Prefs.expired}"
    }

    @Synchronized
    fun hide() {
        try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
        view = null; wm = null; statusTv = null; countersTv = null
    }
}
