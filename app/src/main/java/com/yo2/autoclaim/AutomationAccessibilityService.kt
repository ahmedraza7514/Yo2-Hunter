package com.yo2.autoclaim

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Yo2 Hunter v2 — automation engine.
 *
 * Detection: accessibility text nodes (old app's proven approach, ~350 ms scan)
 * with a screenshot colour fallback. Flow per YO2-HUNTER spec:
 * banner → Tips OK → room popup → DIRECT tap Open / COUNTDOWN fast-tap
 * (a direct banner always preempts countdown tapping) → verify → exit flow.
 */
class AutomationAccessibilityService : AccessibilityService() {

    private lateinit var prefs: AppPreferences
    private var overlayManager: OverlayManager? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var flowJob: Job? = null

    @Volatile
    private var flowRunning = false

    private var currentPackage: String = ""
    private var cycleCount = 0
    private var lastScreenshotMs = 0L
    private var lastBannerTapMs = 0L
    private var lastVisualMs = 0L

    @Volatile
    private var visualBusy = false

    // Last screen luminance measured by the exit flow's bubble check —
    // logged when the power tap seems to miss, so the log shows whether
    // the bubbles never appeared or appeared but read too bright.
    private var lastBubbleLum = -1f

    private val stateListener: () -> Unit = {
        overlayManager?.refresh()
        if (AutomationStateMachine.isRunning()) {
            if (loopJob?.isActive != true) startScanLoop()
            val om = overlayManager
            if (om != null && prefs.showFloatingOverlay) {
                mainHandlerPost { om.show(); om.refresh() }
            }
        } else {
            stopScanLoop()
            mainHandlerPost { overlayManager?.dismiss() }
        }
    }

    private fun mainHandlerPost(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        overlayManager = OverlayManager(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        AutomationStateMachine.addLog("Accessibility Service Connected & Ready", "SUCCESS")
        AutomationStateMachine.addListener(stateListener)
        if (AutomationStateMachine.isRunning()) {
            startScanLoop()
            if (prefs.showFloatingOverlay) overlayManager?.show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.lowercase() ?: return
        if (pkg == packageName.lowercase()) return
        currentPackage = pkg
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Transient system/search windows must not pause the automation —
        // they come and go while the user is still inside Yo2.
        if (IGNORE_PACKAGES.any { pkg.contains(it) }) return

        val isYo2Active = prefs.getTargetPackagesList().any { pkg.contains(it) }
        if (isYo2Active) {
            if (AutomationStateMachine.currentState == AutomationState.PAUSED && prefs.autoResume) {
                AutomationStateMachine.resume()
            }
        } else {
            if (AutomationStateMachine.isRunning() &&
                AutomationStateMachine.currentState != AutomationState.PAUSED
            ) {
                AutomationStateMachine.pause("Switched to $pkg - Paused")
            }
        }
    }

    override fun onInterrupt() {
        AutomationStateMachine.addLog("Accessibility service interrupted", "WARN")
    }

    override fun onDestroy() {
        isServiceConnected = false
        AutomationStateMachine.removeListener(stateListener)
        stopScanLoop()
        overlayManager?.dismiss()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ loop

    private fun startScanLoop() {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            while (isActive && AutomationStateMachine.isRunning()) {
                try {
                    if (AutomationStateMachine.currentState != AutomationState.PAUSED && !flowRunning) {
                        processScreenCycle()
                    }
                } catch (e: Exception) {
                    // a bad cycle must never kill the loop
                }
                delay(prefs.scanIntervalMs.coerceIn(120L, 2000L))
            }
        }
    }

    private fun stopScanLoop() {
        loopJob?.cancel()
        loopJob = null
        flowJob?.cancel()
        flowJob = null
        flowRunning = false
    }

    private suspend fun processScreenCycle() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()?.lowercase() ?: return
        if (pkg == packageName.lowercase()) return
        if (!prefs.getTargetPackagesList().any { pkg.contains(it) }) return

        val targets = TargetDetection.scanScreen(
            root,
            prefs.getEnvelopeKeywordsList(),
            prefs.getOpenKeywordsList(),
            prefs.getSuccessKeywordsList()
        )
        val banner = targets.firstOrNull { it.targetType == TargetDetection.TargetType.FLOATING_ENVELOPE }
        val now = System.currentTimeMillis()
        if (banner != null && now - lastBannerTapMs > BANNER_THROTTLE_MS) {
            lastBannerTapMs = now
            AutomationStateMachine.onEnvelopeDetected(banner.textSnippet.take(40))
            startRoomFlow(banner, null, null)
            return
        }

        // Periodic scan summary — shows exactly what the app can see.
        cycleCount++
        if (cycleCount % 30 == 0) {
            val texts = TargetDetection.collectTexts(root)
            val sample = texts.take(3).joinToString(" | ") { it.take(22) }
            AutomationStateMachine.addLog("SCAN texts=${texts.size} [$sample]", "INFO")
        }

        // Visual fallback for the top banner — runs in its own coroutine so a
        // slow screenshot never blocks the fast node scans.
        if (now - lastVisualMs > 800 && now - lastBannerTapMs > BANNER_THROTTLE_MS && !visualBusy) {
            lastVisualMs = now
            visualBusy = true
            serviceScope.launch {
                try {
                    val bmp = captureScreen()
                    if (bmp != null) {
                        try {
                            val tb = VisualDetection.analyzeTopBanner(bmp)
                            if (tb.redRatio >= 0.03f || tb.warmRatio >= 0.05f || tb.present) {
                                AutomationStateMachine.addLog(
                                    "VIS red=${"%.2f".format(tb.redRatio)} warm=${"%.2f".format(tb.warmRatio)} present=${tb.present}",
                                    "INFO"
                                )
                            }
                            if (tb.present && !flowRunning) {
                                lastBannerTapMs = System.currentTimeMillis()
                                AutomationStateMachine.onEnvelopeDetected(
                                    "visual banner red=${"%.2f".format(tb.redRatio)}"
                                )
                                startRoomFlow(null, tb.tapX, tb.tapY)
                            }
                        } finally {
                            bmp.recycle()
                        }
                    }
                } catch (e: Exception) {
                    // visual check is best-effort
                } finally {
                    visualBusy = false
                }
            }
        }
    }

    // ------------------------------------------------------------------ flow

    private fun startRoomFlow(
        banner: TargetDetection.IdentifiedTarget?,
        visualTapX: Int?,
        visualTapY: Int?
    ) {
        if (flowRunning) return
        flowJob = serviceScope.launch {
            flowRunning = true
            try {
                runRoomFlow(banner, visualTapX, visualTapY)
            } catch (e: Exception) {
                AutomationStateMachine.addLog("Flow error: ${e.message}", "WARN")
            } finally {
                flowRunning = false
                AutomationStateMachine.onWaitingNext()
            }
        }
    }

    private suspend fun runRoomFlow(
        banner: TargetDetection.IdentifiedTarget?,
        visualTapX: Int?,
        visualTapY: Int?
    ) {
        // 1) Tap the banner.
        if (banner != null) {
            tapTarget(banner)
        } else if (visualTapX != null && visualTapY != null) {
            tapXY(visualTapX, visualTapY)
        }
        delay(900)

        // 2) Tips dialog → OK.
        handleTipsDialog()

        // 3) Classify the room popup: DIRECT vs COUNTDOWN.
        var enteredRoom = false
        var openBtn: TargetDetection.IdentifiedTarget? = null
        var countdown: TargetDetection.IdentifiedTarget? = null
        var visualReady: VisualDetection.Result? = null
        val classifyDeadline = System.currentTimeMillis() + 12_000
        var cycle = 0
        while (System.currentTimeMillis() < classifyDeadline) {
            val root = rootInActiveWindow
            val targets = TargetDetection.scanScreen(
                root,
                prefs.getEnvelopeKeywordsList(),
                prefs.getOpenKeywordsList(),
                prefs.getSuccessKeywordsList()
            )
            openBtn = targets.firstOrNull { it.targetType == TargetDetection.TargetType.OPEN_BUTTON }
            countdown = targets.firstOrNull { it.targetType == TargetDetection.TargetType.COUNTDOWN_LABEL }
            if (openBtn != null || countdown != null) {
                enteredRoom = true
                break
            }
            if (cycle % 2 == 0) {
                val bmp = captureScreen()
                if (bmp != null) {
                    try {
                        val vr = VisualDetection.analyzeBanner(bmp)
                        if (vr.envelopeReady) {
                            visualReady = vr
                            enteredRoom = true
                            break
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            cycle++
            delay(250)
        }

        if (!enteredRoom) {
            AutomationStateMachine.onClaimFailed("room popup not found")
            return
        }

        // 4a) DIRECT — tap Open now.
        if (openBtn != null || visualReady != null) {
            AutomationStateMachine.addLog("Direct envelope — tapping Open", "FLOW")
            if (openBtn != null) tapTarget(openBtn) else tapPoint(OPEN_X, OPEN_Y)
            verifyClaim(enteredRoom = true)
            return
        }

        // 4b) COUNTDOWN — fast-tap until Open appears.
        val cdSeconds = countdown?.countdownSeconds
        AutomationStateMachine.onCountdownRunning(cdSeconds)
        val tapDeadline = System.currentTimeMillis() +
            minOf(((cdSeconds ?: 180) + 45) * 1000L, 7 * 60 * 1000L)
        var lastPreemptTapMs = 0L
        var lastVisualPreemptMs = 0L
        var lastStayLogMs = 0L
        var popupMissingSince = 0L
        var lastPopupSeenMs = System.currentTimeMillis()
        while (System.currentTimeMillis() < tapDeadline) {
            val root = rootInActiveWindow
            val targets = TargetDetection.scanScreen(
                root,
                prefs.getEnvelopeKeywordsList(),
                prefs.getOpenKeywordsList(),
                prefs.getSuccessKeywordsList()
            )
            // Open appeared — claim it.
            val nowOpen = targets.firstOrNull { it.targetType == TargetDetection.TargetType.OPEN_BUTTON }
            if (nowOpen != null) {
                tapTarget(nowOpen)
                verifyClaim(enteredRoom = true)
                return
            }
            // Confirmation already visible (someone/something claimed it).
            if (targets.any { isRealConfirmation(it) }) {
                verifyClaim(enteredRoom = true)
                return
            }
            // PRIORITY RULE: a fresh direct banner preempts countdown tapping.
            val now = System.currentTimeMillis()
            val freshBanner = targets.firstOrNull { it.targetType == TargetDetection.TargetType.FLOATING_ENVELOPE }
            if (freshBanner != null && now - lastPreemptTapMs > 2500) {
                lastPreemptTapMs = now
                AutomationStateMachine.addLog("Direct banner preempted countdown", "FLOW")
                tapTarget(freshBanner)
                delay(900)
                handleTipsDialog()
                // After the detour, the new room may be direct-open:
                val root2 = rootInActiveWindow
                val targets2 = TargetDetection.scanScreen(
                    root2,
                    prefs.getEnvelopeKeywordsList(),
                    prefs.getOpenKeywordsList(),
                    prefs.getSuccessKeywordsList()
                )
                val directNow = targets2.firstOrNull { it.targetType == TargetDetection.TargetType.OPEN_BUTTON }
                if (directNow != null) {
                    tapTarget(directNow)
                    verifyClaim(enteredRoom = true)
                    return
                }
            }
            // PRIORITY RULE (visual): the banner is canvas-drawn, so it NEVER
            // appears in the node tree — the node check above can never see a
            // banner crossing during a countdown. Only a screenshot can.
            // Check the top strip about once a second; a fresh banner preempts
            // the countdown immediately (direct envelopes are first priority).
            if (now - lastVisualPreemptMs > 900) {
                lastVisualPreemptMs = now
                val bmp = captureScreen()
                if (bmp != null) {
                    try {
                        val tb = VisualDetection.analyzeTopBanner(bmp)
                        if (tb.present) {
                            // Only a DIRECT banner may preempt: switching to
                            // another countdown would throw away the countdown
                            // already running in this room (his rule, 2026-10-21).
                            val kind = VisualDetection.classifyBanner(bmp)
                            if (kind != VisualDetection.BannerKind.DIRECT) {
                                if (now - lastStayLogMs > 4000) {
                                    lastStayLogMs = now
                                    AutomationStateMachine.addLog(
                                        "Banner during countdown is $kind — staying on countdown",
                                        "FLOW"
                                    )
                                }
                            } else {
                            // Confirm on a second screenshot ~450ms later — a
                            // single reading can misjudge a moving banner, and
                            // a wrong switch throws away this room's countdown.
                            delay(450)
                            var kind2 = VisualDetection.BannerKind.UNKNOWN
                            val bmpB = captureScreen()
                            if (bmpB != null) {
                                try {
                                    if (VisualDetection.analyzeTopBanner(bmpB).present) {
                                        kind2 = VisualDetection.classifyBanner(bmpB)
                                    }
                                } finally {
                                    bmpB.recycle()
                                }
                            }
                            if (kind2 != VisualDetection.BannerKind.DIRECT) {
                                if (now - lastStayLogMs > 4000) {
                                    lastStayLogMs = now
                                    AutomationStateMachine.addLog(
                                        "Priority check not confirmed ($kind2) — staying on countdown",
                                        "FLOW"
                                    )
                                }
                            } else {
                            AutomationStateMachine.addLog(
                                "Priority banner during countdown — switching",
                                "FLOW"
                            )
                            tapXY(tb.tapX, tb.tapY)
                            delay(900)
                            handleTipsDialog()
                            // Poll for the new popup — it can render late.
                            // Direct → claim it. A countdown label → we are
                            // simply on that countdown now; keep tapping it.
                            // Nothing at all → the switch led nowhere; leave
                            // cleanly instead of tapping seats.
                            val pollEnd = System.currentTimeMillis() + 5000
                            var settled = false
                            while (System.currentTimeMillis() < pollEnd) {
                                val root3 = rootInActiveWindow
                                val targets3 = TargetDetection.scanScreen(
                                    root3,
                                    prefs.getEnvelopeKeywordsList(),
                                    prefs.getOpenKeywordsList(),
                                    prefs.getSuccessKeywordsList()
                                )
                                val directNow2 = targets3.firstOrNull {
                                    it.targetType == TargetDetection.TargetType.OPEN_BUTTON
                                }
                                if (directNow2 != null) {
                                    tapTarget(directNow2)
                                    verifyClaim(enteredRoom = true)
                                    return
                                }
                                if (targets3.any {
                                        it.targetType == TargetDetection.TargetType.COUNTDOWN_LABEL
                                    }
                                ) {
                                    lastPopupSeenMs = System.currentTimeMillis()
                                    popupMissingSince = 0L
                                    settled = true
                                    break
                                }
                                delay(300)
                            }
                            if (!settled) {
                                AutomationStateMachine.addLog(
                                    "Priority switch found no popup — leaving room",
                                    "FLOW"
                                )
                                exitFlow()
                                return
                            }
                            }
                            }
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            // Fast-tap the countdown label (fresh bounds each cycle).
            val cdNow = targets.firstOrNull { it.targetType == TargetDetection.TargetType.COUNTDOWN_LABEL }
            if (cdNow != null) {
                popupMissingSince = 0L
                lastPopupSeenMs = now
                tapXY(cdNow.bounds.centerX(), cdNow.bounds.centerY())
            } else {
                // Label node missing. NEVER blind-tap the oval point on a
                // stale sighting: if the popup itself is gone (claimed by
                // others / expired / closed — or we just switched rooms and
                // its popup has not rendered yet), that point is a room
                // SEAT and the taps open people's profiles (seen live
                // 2026-10-21). Tap only while the popup was seen recently;
                // after 2.5s of absence verify visually — the envelope
                // popup is a big red card on screen.
                if (popupMissingSince == 0L) popupMissingSince = now
                if (now - lastPopupSeenMs <= 2500) {
                    tapPoint(OPEN_X, OPEN_Y)
                } else if (now - popupMissingSince > 2500) {
                    val bmp2 = captureScreen()
                    var cardUp = true // uncertain → treat as up, re-check later
                    if (bmp2 != null) {
                        try {
                            cardUp = VisualDetection.resultScreenRed(bmp2) >= 0.30f
                        } finally {
                            bmp2.recycle()
                        }
                    }
                    if (!cardUp) {
                        AutomationStateMachine.addLog(
                            "Countdown popup gone — leaving room",
                            "FLOW"
                        )
                        exitFlow()
                        return
                    }
                    lastPopupSeenMs = now
                    popupMissingSince = now
                    tapPoint(OPEN_X, OPEN_Y)
                }
                // else: popup unseen and the visual check is not due yet —
                // tap nothing this cycle.
            }
            delay(280)
        }
        AutomationStateMachine.onClaimFailed("countdown timeout")
        exitFlow()
    }

    private suspend fun handleTipsDialog() {
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow ?: return
            val texts = TargetDetection.collectTexts(root)
            val hasTips = texts.any { it.contains("Tips", ignoreCase = true) }
            val okNode = TargetDetection.findNodeByText(root, listOf("OK"))
            if (okNode != null && (hasTips || texts.size < 40)) {
                tapTarget(okNode)
                AutomationStateMachine.addLog("Tips dialog — OK tapped", "FLOW")
                delay(900)
                return
            }
            if (hasTips) {
                tapPoint(TIPS_OK_X, TIPS_OK_Y)
                AutomationStateMachine.addLog("Tips dialog — OK tapped (point)", "FLOW")
                delay(900)
                return
            }
            delay(300)
        }
    }

    private suspend fun verifyClaim(enteredRoom: Boolean) {
        AutomationStateMachine.onClaimPending()
        // 15s: the result panel can arrive late (measured on a real capture —
        // Open tapped 10:47:21, panel only appeared after the old 10s window).
        val deadline = System.currentTimeMillis() + 15_000
        var openRetries = 0
        var lastRetryMs = 0L
        var lastVisualMs = 0L
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            val targets = TargetDetection.scanScreen(
                root,
                prefs.getEnvelopeKeywordsList(),
                prefs.getOpenKeywordsList(),
                prefs.getSuccessKeywordsList()
            )
            // Visual confirmation — the result screen is canvas-drawn, so its
            // text never reaches the node tree; its big red card does show in
            // a screenshot (measured red 0.49 vs 0.00 for a plain room).
            val vNow = System.currentTimeMillis()
            if (vNow - lastVisualMs > 700) {
                lastVisualMs = vNow
                val bmp = captureScreen()
                if (bmp != null) {
                    try {
                        val cardRed = VisualDetection.resultScreenRed(bmp)
                        if (cardRed >= 0.30f) {
                            AutomationStateMachine.onClaimConfirmed("result screen (visual)")
                            NotificationHelper.triggerClaimSuccessFeedback(
                                this, prefs.enableSound, prefs.enableVibration
                            )
                            delay(700)
                            if (enteredRoom) exitFlow()
                            return
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            val confirmation = targets.firstOrNull { isRealConfirmation(it) }
            if (confirmation != null) {
                val coins = extractCoins(root)
                AutomationStateMachine.onClaimConfirmed(
                    if (coins != null) "+$coins coins" else confirmation.textSnippet.take(40)
                )
                NotificationHelper.triggerClaimSuccessFeedback(
                    this, prefs.enableSound, prefs.enableVibration
                )
                delay(700)
                if (enteredRoom) exitFlow()
                return
            }
            if (targets.any { it.targetType == TargetDetection.TargetType.CLAIM_FAILED_LABEL }) {
                AutomationStateMachine.onClaimFailed("envelope finished")
                if (enteredRoom) exitFlow()
                return
            }
            // Popup still open — re-tap Open a few times.
            val now = System.currentTimeMillis()
            val openBtn = targets.firstOrNull { it.targetType == TargetDetection.TargetType.OPEN_BUTTON }
            if (openBtn != null && openRetries < prefs.maxRetries && now - lastRetryMs > 2000) {
                openRetries++
                lastRetryMs = now
                tapTarget(openBtn)
            }
            delay(400)
        }
        AutomationStateMachine.onClaimFailed("no confirmation")
        if (enteredRoom) exitFlow()
    }

    private fun isRealConfirmation(target: TargetDetection.IdentifiedTarget): Boolean {
        if (target.targetType != TargetDetection.TargetType.CLAIM_CONFIRMATION) return false
        val s = target.textSnippet.lowercase()
        return s.contains("received completely") || s.contains("lucky money") ||
            s.contains("coins received") || s.contains("congratulations") ||
            s.contains("deposited into your account")
    }

    /** The success screen shows a big coin number — grab the largest plausible one for the log. */
    private fun extractCoins(root: AccessibilityNodeInfo?): Int? {
        val texts = TargetDetection.collectTexts(root)
        var best: Int? = null
        for (t in texts) {
            val n = t.trim().replace(",", "").toIntOrNull()
            if (n != null && n in 1..10_000_000) {
                if (best == null || n > best!!) best = n
            }
        }
        return best
    }

    /**
     * Exit flow — X under the envelope → power → Exit bubble. Every step is
     * VERIFIED and retried: blind taps kept missing the small power icon
     * (v2.4/v2.5 live tests), so now the flow checks its own work —
     * the red card must be gone, the Keep/Exit bubbles screen must be up
     * (it dims the room almost to black: measured mean luminance ~16 vs
     * ~110 in a normal room), and the profile screen must be back.
     */
    private suspend fun exitFlow() {
        // 1) Close the result panel / popup; re-tap the X if the card stays.
        tapPoint(CLOSE_X, CLOSE_Y)
        delay(900)
        if (redCardVisible()) {
            tapPoint(CLOSE_X, 0.75f)
            delay(900)
        }
        // 2) Power → bubbles → Exit, up to 3 attempts at nearby spots.
        // Live evidence (2026-10-21): the (0.92, 0.082) spot lands — the
        // (0.93, 0.080) first try missed twice before the retry saved the
        // exit — so the proven spot goes first now.
        val powerSpots = listOf(
            0.92f to 0.082f,
            POWER_X to POWER_Y,
            0.94f to 0.076f
        )
        var exited = false
        for ((px, py) in powerSpots) {
            tapPoint(px, py)
            delay(1100)
            if (!bubblesVisible()) {
                AutomationStateMachine.addLog(
                    "Exit: power missed, retrying (lum=${"%.0f".format(lastBubbleLum)})",
                    "FLOW"
                )
                continue
            }
            tapPoint(EXIT_X, EXIT_Y)
            delay(1300)
            if (outOfRoom()) {
                exited = true
                break
            }
            if (bubblesVisible()) {
                // Bubbles still up — the Exit tap missed the bubble itself.
                tapPoint(EXIT_X, 0.63f)
                delay(1300)
                if (outOfRoom()) {
                    exited = true
                    break
                }
            } else {
                // Bubbles gone after the Exit tap — the tap landed.
                exited = true
                break
            }
        }
        val tail = rootInActiveWindow?.let {
            TargetDetection.collectTexts(it).take(3).joinToString(" | ") { t -> t.take(22) }
        } ?: ""
        AutomationStateMachine.addLog(
            if (exited) "Exit flow done — out of the room [$tail]"
            else "Exit flow done — exit unconfirmed [$tail]",
            "FLOW"
        )
        delay(prefs.cooldownMs)
    }

    /** Is the big red result card / envelope popup still on screen? */
    private suspend fun redCardVisible(): Boolean {
        val bmp = captureScreen() ?: return false
        return try {
            VisualDetection.resultScreenRed(bmp) >= 0.30f
        } finally {
            bmp.recycle()
        }
    }

    /** Are the Keep/Exit bubbles up? Their overlay dims the room to near-black. */
    private suspend fun bubblesVisible(): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            val texts = TargetDetection.collectTexts(root)
            val hasExit = texts.any { it.trim().equals("exit", ignoreCase = true) }
            val hasKeep = texts.any { it.trim().equals("keep", ignoreCase = true) }
            if (hasExit && hasKeep) return true
        }
        val bmp = captureScreen() ?: return false
        return try {
            lastBubbleLum = meanLuminance(bmp)
            lastBubbleLum < 45f
        } finally {
            bmp.recycle()
        }
    }

    private fun meanLuminance(bmp: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bmp, 48, 96, false)
        var sum = 0L
        var n = 0
        for (x in 0 until small.width) {
            for (y in 0 until small.height) {
                val c = small.getPixel(x, y)
                sum += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                n++
            }
        }
        small.recycle()
        return if (n == 0) 255f else sum.toFloat() / n
    }

    /** Back on the profile page? Its Recharge / Balance labels are the marker. */
    private fun outOfRoom(): Boolean {
        val root = rootInActiveWindow ?: return false
        val texts = TargetDetection.collectTexts(root)
        return texts.any {
            it.contains("Recharge", ignoreCase = true) ||
                it.contains("Balance", ignoreCase = true)
        }
    }

    // ------------------------------------------------------------------ taps

    private suspend fun tapTarget(target: TargetDetection.IdentifiedTarget) {
        val clickable = TargetDetection.findClickableNode(target.node)
        if (clickable.isClickable) {
            val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) return
        }
        tapXY(target.bounds.centerX(), target.bounds.centerY())
    }

    private suspend fun tapPoint(fx: Float, fy: Float) {
        val metrics = resources.displayMetrics
        tapXY((metrics.widthPixels * fx).toInt(), (metrics.heightPixels * fy).toInt())
    }

    private suspend fun tapXY(x: Int, y: Int): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    // ------------------------------------------------------------- screenshot

    private suspend fun captureScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val now = System.currentTimeMillis()
        if (now - lastScreenshotMs < 350) return null
        lastScreenshotMs = now
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(0, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var bmp: Bitmap? = null
                    try {
                        val buffer = result.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        if (wrapped != null) {
                            bmp = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped.recycle()
                        }
                        buffer.close()
                    } catch (e: Exception) {
                        bmp = null
                    }
                    if (cont.isActive) cont.resume(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }

    companion object {
        @Volatile
        var isServiceConnected: Boolean = false
            private set

        // Fixed tap points (fractions). OPEN from the proven old app. Exit
        // points measured from the reference screenshots (2026-10-21): the
        // power icon centre is (0.920w, 0.082h) and the Exit bubble centre is
        // (0.500w, 0.607h) — the old spec guesses (0.94, 0.11) and (0.50, 0.66)
        // landed beside the icons, so the taps never registered. CLOSE was
        // re-measured from a real capture (gold X centroid at 0.493w, 0.764h).
        private const val OPEN_X = 0.5f
        private const val OPEN_Y = 0.62f
        private const val CLOSE_X = 0.5f
        private const val CLOSE_Y = 0.765f
        private const val POWER_X = 0.93f
        private const val POWER_Y = 0.08f
        private const val EXIT_X = 0.5f
        private const val EXIT_Y = 0.607f

        // Tips dialog OK (YO2-HUNTER spec).
        private const val TIPS_OK_X = 0.75f
        private const val TIPS_OK_Y = 0.60f

        private const val BANNER_THROTTLE_MS = 6000L

        // Windows that appear briefly over other apps and must never pause us.
        private val IGNORE_PACKAGES = listOf(
            "systemui", "inputmethod", "launcher",
            "googlequicksearchbox", "permissioncontroller", "android.gms"
        )
    }
}
