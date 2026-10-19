package com.yo2.autoclaim

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Yo2 red-envelope auto claimer — Ahmed Bhai's spec (memory/yo2-autoclaim-spec.md).
 *
 * WATCHING -> banner tap -> TIPS_WAIT -> OK -> ROOM_CLASSIFY ->
 *   DIRECT (Open nazar aaye) -> tap Open -> CLAIM_CHECK
 *   COUNTDOWN (timer nazar aaye) -> FAST_TAP (Open aane tak; beech mein DIRECT
 *     banner aaye to PRIORITY preempt) -> tap Open -> CLAIM_CHECK
 * CLAIM_CHECK -> claimed/expired -> EXIT_FLOW (X -> power -> EXIT bubble) -> WATCHING
 */
class AutomationAccessibilityService : AccessibilityService() {

    private enum class State { WATCHING, TIPS_WAIT, ROOM_CLASSIFY, FAST_TAP, CLAIM_CHECK, EXIT_FLOW }

    private var state = State.WATCHING
    private var stateSince = System.currentTimeMillis()
    private var exitStep = 0
    private var timerBox: Rect? = null
    private var wasDirect = false
    private var lastBannerTap = 0L
    private var screenshotWarned = false

    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false

    private val timerRe = Regex("""^(\d{1,2}):([0-5]\d)$""")

    // ---------------- lifecycle ----------------

    override fun onServiceConnected() {
        Prefs.init(this)
        LogStore.add("SVC", "service connected (API ${Build.VERSION.SDK_INT})")
        OverlayManager.init(this)
        startWorker()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { LogStore.add("SVC", "interrupted") }

    override fun onDestroy() {
        running = false
        try { worker?.quitSafely() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startWorker() {
        if (running) return
        running = true
        worker = HandlerThread("yo2-worker").apply { start() }
        handler = Handler(worker!!.looper)
        handler?.post { loop() }
        LogStore.add("SVC", "worker started")
    }

    // ---------------- main loop ----------------

    private fun loop() {
        if (!running) return
        try {
            if (!Prefs.enabled) {
                sleepMs(1000)
            } else {
                when (state) {
                    State.WATCHING -> doWatching()
                    State.TIPS_WAIT -> doTipsWait()
                    State.ROOM_CLASSIFY -> doRoomClassify()
                    State.FAST_TAP -> doFastTap()
                    State.CLAIM_CHECK -> doClaimCheck()
                    State.EXIT_FLOW -> doExitFlow()
                }
            }
        } catch (e: Exception) {
            LogStore.add("ERR", "loop: ${e.message}")
            sleepMs(1000)
        }
        handler?.post { loop() }
    }

    private fun setState(s: State) {
        state = s
        stateSince = System.currentTimeMillis()
        if (s == State.EXIT_FLOW) exitStep = 0
        LogStore.add("STATE", s.name)
    }

    private fun elapsed() = System.currentTimeMillis() - stateSince
    private fun sleepMs(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    // ---------------- primitives ----------------

    /** Screenshot via AccessibilityService API (Android 11+). Null on older versions. */
    private fun shot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (!screenshotWarned) {
                screenshotWarned = true
                LogStore.add("SVC", "screenshot ke liye Android 11+ chahiye")
            }
            return null
        }
        return try {
            val latch = CountDownLatch(1)
            var out: Bitmap? = null
            val exec = Executor { it.run() }
            takeScreenshot(
                Display.DEFAULT_DISPLAY, exec,
                object : AccessibilityService.TakeScreenshotCallback {
    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val hw = result.hardwareBitmap
                            out = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                        } catch (_: Exception) {}
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) { latch.countDown() }
                })
            latch.await(4, TimeUnit.SECONDS)
            out
        } catch (e: Exception) {
            LogStore.add("ERR", "shot: ${e.message}")
            null
        }
    }

    private fun tap(x: Float, y: Float) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 70)
            val gd = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gd, null, null)
        } catch (e: Exception) {
            LogStore.add("ERR", "tap: ${e.message}")
        }
    }

    private fun tapRect(r: Rect) = tap(r.centerX().toFloat(), r.centerY().toFloat())

    private fun findNodeByText(text: String): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: return null
            val list = root.findAccessibilityNodeInfosByText(text)
            val node = list.firstOrNull()
            list.filter { it != node }.forEach { try { it.recycle() } catch (_: Exception) {} }
            node
        } catch (_: Exception) { null }
    }

    // ---------------- states ----------------

    /** Top strip scan for the red banner (2-3s window — poll fast). */
    private fun doWatching() {
        val bmp = shot() ?: return sleepMs(700)
        val banner = Vision.findBanner(bmp)
        bmp.recycle()
        val now = System.currentTimeMillis()
        if (banner != null && now - lastBannerTap > 4000) {
            lastBannerTap = now
            Prefs.detected++
            OverlayManager.refresh()
            LogStore.add("FLOW", "Banner! type=${banner.type} — tap kar raha")
            tapRect(banner.box)
            setState(State.TIPS_WAIT)
        } else {
            sleepMs(600)
        }
    }

    /** "Tips" dialog -> OK button. */
    private fun doTipsWait() {
        if (elapsed() > 10_000) {
            LogStore.add("WARN", "Tips timeout — wapas watch")
            setState(State.WATCHING); return
        }
        val okNode = findNodeByText("OK") ?: findNodeByText("Ok")
        if (okNode != null) {
            LogStore.add("FLOW", "Tips -> OK dabaya")
            try { okNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) {}
            try { okNode.recycle() } catch (_: Exception) {}
            sleepMs(1600)
            setState(State.ROOM_CLASSIFY); return
        }
        val bmp = shot()
        if (bmp != null) {
            val lines = OcrHelper.recognize(bmp)
            val ok = lines.firstOrNull { it.text.trim().equals("OK", ignoreCase = true) && it.box != null }
            if (ok?.box != null) {
                LogStore.add("FLOW", "Tips -> OK (OCR) dabaya")
                tapRect(ok.box!!)
                bmp.recycle()
                sleepMs(1600)
                setState(State.ROOM_CLASSIFY); return
            }
            bmp.recycle()
        }
        sleepMs(600)
    }

    /** Room mein: Open (direct) ya timer (countdown)? */
    private fun doRoomClassify() {
        if (elapsed() > 15_000) {
            LogStore.add("WARN", "classify timeout — exit")
            setState(State.EXIT_FLOW); return
        }
        val bmp = shot() ?: return sleepMs(600)
        val lines = OcrHelper.recognize(bmp)
        val w = bmp.width; val h = bmp.height

        if (lines.any { it.text.contains("received completely", ignoreCase = true) }) {
            Prefs.expired++; OverlayManager.refresh()
            LogStore.add("FLOW", "pehle hi khatam (received completely)")
            bmp.recycle(); setState(State.EXIT_FLOW); return
        }
        val open = lines.firstOrNull { it.text.trim().equals("Open", ignoreCase = true) && it.box != null }
        if (open?.box != null) {
            wasDirect = true
            LogStore.add("FLOW", "DIRECT envelope — Open tap!")
            tapRect(open.box!!)
            bmp.recycle(); sleepMs(1200); setState(State.CLAIM_CHECK); return
        }
        val timer = lines.firstOrNull {
            it.box != null && timerRe.matches(it.text.trim()) &&
                    it.box!!.centerX() > w * 0.25 && it.box!!.centerX() < w * 0.75 &&
                    it.box!!.centerY() > h * 0.40 && it.box!!.centerY() < h * 0.80
        }
        if (timer?.box != null) {
            wasDirect = false
            timerBox = timer.box
            LogStore.add("FLOW", "Countdown ${timer.text.trim()} — fast tap shuru")
            bmp.recycle(); setState(State.FAST_TAP); return
        }
        bmp.recycle()
        sleepMs(700)
    }

    /** Timer par fast tap jab tak "Open" na aaye. Beech mein DIRECT banner = PRIORITY preempt. */
    private fun doFastTap() {
        if (elapsed() > 7 * 60_000) {
            LogStore.add("WARN", "countdown timeout (7min) — exit")
            setState(State.EXIT_FLOW); return
        }
        timerBox?.let { tapRect(it) }

        val bmp = shot() ?: return sleepMs(300)
        val lines = OcrHelper.recognize(bmp)
        val w = bmp.width; val h = bmp.height

        val open = lines.firstOrNull { it.text.trim().equals("Open", ignoreCase = true) && it.box != null }
        if (open?.box != null) {
            LogStore.add("FLOW", "OPEN aa gaya! tap!")
            tapRect(open.box!!)
            bmp.recycle(); sleepMs(1200); setState(State.CLAIM_CHECK); return
        }
        if (lines.any { it.text.contains("received completely", ignoreCase = true) }) {
            Prefs.expired++; OverlayManager.refresh()
            LogStore.add("FLOW", "received completely — exit")
            bmp.recycle(); setState(State.EXIT_FLOW); return
        }
        val t = lines.firstOrNull {
            it.box != null && timerRe.matches(it.text.trim()) &&
                    it.box!!.centerX() > w * 0.25 && it.box!!.centerX() < w * 0.75 &&
                    it.box!!.centerY() > h * 0.40 && it.box!!.centerY() < h * 0.80
        }
        if (t?.box != null) timerBox = t.box

        // PRIORITY: oper DIRECT banner aaya?
        val now = System.currentTimeMillis()
        val banner = Vision.findBanner(bmp)
        if (banner != null && banner.type == Vision.BannerType.DIRECT && now - lastBannerTap > 4000) {
            lastBannerTap = now
            Prefs.detected++; OverlayManager.refresh()
            LogStore.add("FLOW", "PRIORITY! direct banner — countdown rok kar us par!")
            tapRect(banner.box)
            bmp.recycle(); setState(State.TIPS_WAIT); return
        }
        bmp.recycle()
        sleepMs(300)
    }

    /** Claim confirm: "Deposited into your account" / "Received" + coins gin lo. */
    private fun doClaimCheck() {
        if (elapsed() > 12_000) {
            LogStore.add("WARN", "claim check timeout — exit")
            setState(State.EXIT_FLOW); return
        }
        val bmp = shot() ?: return sleepMs(600)
        val lines = OcrHelper.recognize(bmp)
        bmp.recycle()
        val all = lines.joinToString(" ") { it.text }

        if (all.contains("received completely", ignoreCase = true)) {
            Prefs.expired++; OverlayManager.refresh()
            LogStore.add("FLOW", "received completely — exit")
            setState(State.EXIT_FLOW); return
        }
        if (all.contains("Deposited into your account", ignoreCase = true) ||
            all.contains("Received", ignoreCase = true)) {
            val coins = Regex("""\d[\d,]*""").findAll(all)
                .map { it.value.replace(",", "").toLongOrNull() ?: 0L }
                .maxOrNull() ?: 0L
            Prefs.coins += coins
            if (wasDirect) Prefs.claimedDirect++ else Prefs.claimedCountdown++
            OverlayManager.refresh()
            LogStore.add("FLOW", "CLAIMED! +$coins coins ${if (wasDirect) "(direct)" else "(countdown)"}")
            setState(State.EXIT_FLOW); return
        }
        val open = lines.firstOrNull { it.text.trim().equals("Open", ignoreCase = true) && it.box != null }
        if (open?.box != null) {
            tapRect(open.box!!); sleepMs(1200); return
        }
        sleepMs(700)
    }

    /** Exit: X -> power (top-right) -> EXIT bubble -> WATCHING. */
    private fun doExitFlow() {
        when (exitStep) {
            0 -> {
                val bmp = shot()
                if (bmp != null) {
                    val (x, y) = Vision.xButtonPos(bmp)
                    bmp.recycle()
                    LogStore.add("FLOW", "X dabaa raha")
                    tap(x, y)
                } else {
                    tap(500f, 1500f)
                }
                sleepMs(900)
                exitStep = 1
            }
            1 -> {
                val bmp = shot()
                if (bmp != null) {
                    val w = bmp.width; val h = bmp.height
                    bmp.recycle()
                    LogStore.add("FLOW", "power button dabaa raha")
                    tap(w * 0.93f, h * 0.105f)
                } else {
                    tap(530f, 135f)
                }
                sleepMs(1000)
                exitStep = 2
            }
            2 -> {
                val bmp = shot()
                var tapped = false
                if (bmp != null) {
                    val lines = OcrHelper.recognize(bmp)
                    val exit = lines.firstOrNull { it.text.trim().equals("EXIT", ignoreCase = true) && it.box != null }
                    if (exit?.box != null) {
                        LogStore.add("FLOW", "EXIT dabaa raha")
                        tapRect(exit.box!!)
                        tapped = true
                    }
                    val w = bmp.width; val h = bmp.height
                    bmp.recycle()
                    if (!tapped) {
                        LogStore.add("FLOW", "EXIT (fallback position)")
                        tap(w * 0.5f, h * 0.66f)
                    }
                } else {
                    tap(288f, 922f)
                }
                sleepMs(1500)
                exitStep = 3
            }
            else -> {
                LogStore.add("FLOW", "room se bahar — agle banner ka wait")
                setState(State.WATCHING)
            }
        }
    }
}
