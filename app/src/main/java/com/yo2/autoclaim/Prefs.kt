package com.yo2.autoclaim

import android.content.Context
import android.content.SharedPreferences

/** Simple persistent prefs + counters. */
object Prefs {
    private const val NAME = "yo2_prefs"
    private lateinit var sp: SharedPreferences
    private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        ready = true
    }

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var detected: Int
        get() = sp.getInt("detected", 0)
        set(v) = sp.edit().putInt("detected", v).apply()

    var claimedDirect: Int
        get() = sp.getInt("claimed_direct", 0)
        set(v) = sp.edit().putInt("claimed_direct", v).apply()

    var claimedCountdown: Int
        get() = sp.getInt("claimed_countdown", 0)
        set(v) = sp.edit().putInt("claimed_countdown", v).apply()

    var coins: Long
        get() = sp.getLong("coins", 0L)
        set(v) = sp.edit().putLong("coins", v).apply()

    var expired: Int
        get() = sp.getInt("expired", 0)
        set(v) = sp.edit().putInt("expired", v).apply()

    fun resetStats() {
        sp.edit()
            .putInt("detected", 0)
            .putInt("claimed_direct", 0)
            .putInt("claimed_countdown", 0)
            .putLong("coins", 0L)
            .putInt("expired", 0)
            .apply()
    }
}
