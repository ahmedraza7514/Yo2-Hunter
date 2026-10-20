package com.yo2.autoclaim

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings for Yo2 Hunter v2 — defaults mirror the proven old app exactly.
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("yo2_autoclaim_prefs", Context.MODE_PRIVATE)

    var targetPackages: String
        get() = prefs.getString(KEY_TARGET_PACKAGES, DEFAULT_TARGET_PACKAGES) ?: DEFAULT_TARGET_PACKAGES
        set(value) = prefs.edit().putString(KEY_TARGET_PACKAGES, value).apply()

    var envelopeKeywords: String
        get() = prefs.getString(KEY_ENVELOPE_KEYWORDS, DEFAULT_ENVELOPE_KEYWORDS) ?: DEFAULT_ENVELOPE_KEYWORDS
        set(value) = prefs.edit().putString(KEY_ENVELOPE_KEYWORDS, value).apply()

    var openKeywords: String
        get() = prefs.getString(KEY_OPEN_KEYWORDS, DEFAULT_OPEN_KEYWORDS) ?: DEFAULT_OPEN_KEYWORDS
        set(value) = prefs.edit().putString(KEY_OPEN_KEYWORDS, value).apply()

    var successKeywords: String
        get() = prefs.getString(KEY_SUCCESS_KEYWORDS, DEFAULT_SUCCESS_KEYWORDS) ?: DEFAULT_SUCCESS_KEYWORDS
        set(value) = prefs.edit().putString(KEY_SUCCESS_KEYWORDS, value).apply()

    var scanIntervalMs: Long
        get() = prefs.getLong(KEY_SCAN_INTERVAL_MS, DEFAULT_SCAN_INTERVAL_MS)
        set(value) = prefs.edit().putLong(KEY_SCAN_INTERVAL_MS, value).apply()

    var cooldownMs: Long
        get() = prefs.getLong(KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS)
        set(value) = prefs.edit().putLong(KEY_COOLDOWN_MS, value).apply()

    var maxRetries: Int
        get() = prefs.getInt(KEY_MAX_RETRIES, DEFAULT_MAX_RETRIES)
        set(value) = prefs.edit().putInt(KEY_MAX_RETRIES, value).apply()

    var enableSound: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_SOUND, value).apply()

    var enableVibration: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_VIBRATION, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_VIBRATION, value).apply()

    var showFloatingOverlay: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FLOATING_OVERLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_FLOATING_OVERLAY, value).apply()

    var autoResume: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESUME, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESUME, value).apply()

    init {
        if (prefs.getInt(KEY_DEFAULTS_VERSION, 0) < DEFAULTS_VERSION) {
            targetPackages = mergeCsv(targetPackages, REQUIRED_PACKAGES)
            envelopeKeywords = mergeCsv(envelopeKeywords, REQUIRED_ENVELOPE_KEYWORDS)
            prefs.edit().putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION).apply()
        }
    }

    fun getTargetPackagesList(): List<String> = csv(targetPackages)
    fun getEnvelopeKeywordsList(): List<String> = csv(envelopeKeywords)
    fun getOpenKeywordsList(): List<String> = csv(openKeywords)
    fun getSuccessKeywordsList(): List<String> = csv(successKeywords)

    private fun csv(raw: String): List<String> =
        raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun mergeCsv(current: String, extra: String): String {
        val items = current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        for (e in extra.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            if (items.none { it.equals(e, ignoreCase = true) }) items.add(e)
        }
        return items.joinToString(",")
    }

    companion object {
        const val DEFAULTS_VERSION = 2
        const val DEFAULT_COOLDOWN_MS = 2500L
        const val DEFAULT_ENVELOPE_KEYWORDS = "Red Packet,Red Envelope,Envelope,红包,搶紅包,Lucky Bag,🧧,send,x9999,x39999,x99999"
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_OPEN_KEYWORDS = "Open,Claim,開,开,Get,Tap to open,领取,搶"
        const val DEFAULT_SCAN_INTERVAL_MS = 350L
        const val DEFAULT_SUCCESS_KEYWORDS = "Coins received,Claimed,Congratulations,Success,恭喜获得,已领取,金币"
        const val DEFAULT_TARGET_PACKAGES = "com.yotwo.sealion,com.yo2.live,com.yo2.chat,com.yo2.app"

        private const val REQUIRED_ENVELOPE_KEYWORDS = "send,x9999,x39999,x99999"
        private const val REQUIRED_PACKAGES = "com.yotwo.sealion"

        private const val KEY_AUTO_RESUME = "auto_resume"
        private const val KEY_COOLDOWN_MS = "cooldown_ms"
        private const val KEY_DEFAULTS_VERSION = "defaults_version"
        private const val KEY_ENABLE_SOUND = "enable_sound"
        private const val KEY_ENABLE_VIBRATION = "enable_vibration"
        private const val KEY_ENVELOPE_KEYWORDS = "envelope_keywords"
        private const val KEY_MAX_RETRIES = "max_retries"
        private const val KEY_OPEN_KEYWORDS = "open_keywords"
        private const val KEY_SCAN_INTERVAL_MS = "scan_interval_ms"
        private const val KEY_SHOW_FLOATING_OVERLAY = "show_floating_overlay"
        private const val KEY_SUCCESS_KEYWORDS = "success_keywords"
        private const val KEY_TARGET_PACKAGES = "target_packages"
    }
}
