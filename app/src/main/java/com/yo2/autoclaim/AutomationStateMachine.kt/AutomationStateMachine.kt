package com.yo2.autoclaim

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class AutomationState {
    IDLE,
    WAITING_FOR_ENVELOPE,
    ENVELOPE_DETECTED,
    COUNTDOWN_RUNNING,
    CLAIM_PENDING,
    CLAIM_CONFIRMED,
    WAITING_FOR_NEXT_ENVELOPE,
    PAUSED
}

data class LogEntry(val time: String, val tag: String, val message: String)

data class AutomationStats(
    val detected: Int = 0,
    val claimed: Int = 0,
    val failed: Int = 0
)

/**
 * Shared state between the UI, the foreground service, the accessibility
 * service and the floating overlay — same role as the old app's state machine.
 */
object AutomationStateMachine {

    @Volatile
    var currentState: AutomationState = AutomationState.IDLE
        private set

    @Volatile
    private var running: Boolean = false

    @Volatile
    var sessionStartMs: Long = 0L
        private set

    private var stats = AutomationStats()

    private val logs = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun isRunning(): Boolean = running

    fun getStats(): AutomationStats = stats

    fun getLogs(): List<LogEntry> = logs.toList()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        for (l in listeners) {
            try {
                l()
            } catch (e: Exception) {
                // listener gone — ignore
            }
        }
    }

    private fun setState(state: AutomationState) {
        currentState = state
        notifyChanged()
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        sessionStartMs = System.currentTimeMillis()
        stats = AutomationStats()
        setState(AutomationState.WAITING_FOR_ENVELOPE)
        addLog("Automation started — scanning for envelopes", "START")
    }

    @Synchronized
    fun stop(reason: String) {
        if (!running) return
        running = false
        setState(AutomationState.IDLE)
        addLog("Automation stopped — $reason", "STOP")
    }

    @Synchronized
    fun pause(reason: String) {
        if (!running) return
        setState(AutomationState.PAUSED)
        addLog(reason, "PAUSE")
    }

    @Synchronized
    fun resume() {
        if (!running) return
        setState(AutomationState.WAITING_FOR_ENVELOPE)
        addLog("Back in Yo2 — resumed", "RESUME")
    }

    fun onEnvelopeDetected(details: String) {
        stats = stats.copy(detected = stats.detected + 1)
        setState(AutomationState.ENVELOPE_DETECTED)
        addLog("Envelope detected: $details", "DETECT")
    }

    fun onCountdownRunning(seconds: Int?) {
        setState(AutomationState.COUNTDOWN_RUNNING)
        addLog("Countdown running: ${seconds ?: "?"}s — fast tapping", "COUNT")
    }

    fun onClaimPending() {
        setState(AutomationState.CLAIM_PENDING)
    }

    fun onClaimConfirmed(details: String) {
        stats = stats.copy(claimed = stats.claimed + 1)
        setState(AutomationState.CLAIM_CONFIRMED)
        addLog("Claimed ✔ $details", "CLAIM")
    }

    fun onClaimFailed(details: String) {
        stats = stats.copy(failed = stats.failed + 1)
        setState(AutomationState.WAITING_FOR_NEXT_ENVELOPE)
        addLog("Missed/failed: $details", "FAIL")
    }

    fun onWaitingNext() {
        if (running && currentState != AutomationState.PAUSED) {
            setState(AutomationState.WAITING_FOR_NEXT_ENVELOPE)
        }
    }

    /** Zero the counters and restart the session clock (user's Reset button). */
    @Synchronized
    fun resetStats() {
        stats = AutomationStats()
        sessionStartMs = System.currentTimeMillis()
        addLog("Counters reset", "INFO")
        notifyChanged()
    }

    /** Empty the live activity log (user's Clear button). */
    fun clearLogs() {
        logs.clear()
        addLog("Logs cleared", "INFO")
        notifyChanged()
    }

    fun addLog(message: String, tag: String = "INFO") {
        val entry = LogEntry(timeFmt.format(Date()), tag, message)
        logs.add(0, entry)
        while (logs.size > 200) logs.removeAt(logs.size - 1)
        notifyChanged()
    }

    fun sessionElapsedMs(): Long =
        if (sessionStartMs == 0L) 0L else System.currentTimeMillis() - sessionStartMs
}
