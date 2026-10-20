package com.yo2.autoclaim

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small floating overlay: status, counters, STOP button. Draggable.
 * Built with plain views (same job as the old app's overlay).
 */
class OverlayManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var statusText: TextView? = null
    private var countersText: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var shown = false

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        if (shown || !canDraw()) return
        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E6121B2E"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#E11D48"))
            }

            val title = TextView(context).apply {
                text = "Yo2 Auto-Claim"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(24, 10, 24, 0)
            }
            statusText = TextView(context).apply {
                text = "SERVICE IDLE"
                setTextColor(Color.parseColor("#FBBF24"))
                textSize = 11f
                setPadding(24, 2, 24, 0)
            }
            countersText = TextView(context).apply {
                text = "Claimed: 0   Failed: 0"
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 11f
                setPadding(24, 2, 24, 4)
            }
            val stopBtn = TextView(context).apply {
                text = "STOP"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(24, 8, 24, 14)
                setOnClickListener {
                    AutomationStateMachine.addLog("Stopped from floating overlay", "STOP")
                    AutomationStateMachine.stop("overlay stop")
                }
            }

            rootView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = bg
                addView(title)
                addView(statusText)
                addView(countersText)
                addView(stopBtn)
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 140
            }

            attachDrag(rootView!!, params!!)
            windowManager?.addView(rootView, params)
            shown = true
            refresh()
        } catch (e: Exception) {
            shown = false
        }
    }

    fun dismiss() {
        if (!shown) return
        try {
            rootView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
        }
        rootView = null
        shown = false
    }

    fun refresh() {
        if (!shown) return
        val stats = AutomationStateMachine.getStats()
        val state = AutomationStateMachine.currentState
        rootView?.post {
            statusText?.text = when {
                !AutomationStateMachine.isRunning() -> "SERVICE IDLE"
                state == AutomationState.PAUSED -> "PAUSED"
                else -> "SERVICE RUNNING"
            }
            countersText?.text = "Claimed: ${stats.claimed}   Failed: ${stats.failed}"
        }
    }

    private fun attachDrag(view: View, lp: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    try {
                        windowManager?.updateViewLayout(v, lp)
                    } catch (e: Exception) {
                    }
                    false
                }
                else -> false
            }
        }
    }
}
