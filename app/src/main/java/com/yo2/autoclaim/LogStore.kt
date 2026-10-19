package com.yo2.autoclaim

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory ring-buffer log with UI listeners. */
object LogStore {
    private val lines = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()

    @Synchronized
    fun add(tag: String, msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.addLast("[$ts][$tag] $msg")
        while (lines.size > 400) lines.removeFirst()
        val copy = listeners.toList()
        copy.forEach { l -> try { l() } catch (_: Exception) {} }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun listen(l: () -> Unit) { listeners.add(l) }

    @Synchronized
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
}
