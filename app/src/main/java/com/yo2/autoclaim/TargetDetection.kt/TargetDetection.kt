package com.yo2.autoclaim

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

/**
 * Text-node based detection over the accessibility tree — the old app's core
 * approach. Scans node text/contentDescription for envelope keywords, open
 * buttons, countdown timers and claim results.
 */
object TargetDetection {

    enum class TargetType {
        FLOATING_ENVELOPE,
        OPEN_BUTTON,
        COUNTDOWN_LABEL,
        CLAIM_CONFIRMATION,
        CLAIM_FAILED_LABEL
    }

    data class IdentifiedTarget(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val targetType: TargetType,
        val textSnippet: String,
        val countdownSeconds: Int? = null
    )

    private val countdownPatternMinutesSeconds: Pattern = Pattern.compile("(\\d{1,2}):(\\d{2})")
    private val countdownPatternSeconds: Pattern = Pattern.compile("(\\d{1,3})\\s*(?:s|sec|秒)")

    private val failedTexts = listOf(
        "received completely",
        "too late", "already finished", "has been received", "抢光了", "已抢完", "no more"
    )

    fun parseCountdownSeconds(text: String): Int? {
        val minSec = countdownPatternMinutesSeconds.matcher(text)
        if (minSec.find()) {
            val minutes = minSec.group(1)?.toIntOrNull() ?: 0
            val seconds = minSec.group(2)?.toIntOrNull() ?: 0
            return minutes * 60 + seconds
        }
        val sec = countdownPatternSeconds.matcher(text)
        if (sec.find()) {
            return sec.group(1)?.toIntOrNull()
        }
        return null
    }

    fun scanScreen(
        rootNode: AccessibilityNodeInfo?,
        envelopeKeywords: List<String>,
        openKeywords: List<String>,
        successKeywords: List<String>
    ): List<IdentifiedTarget> {
        if (rootNode == null) return emptyList()
        val results = ArrayList<IdentifiedTarget>()
        traverseNodes(rootNode) { node ->
            val text = nodeText(node)
            if (text.isNotEmpty()) {
                val lower = text.lowercase()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                when {
                    lower.contains("received completely") || lower.contains("lucky money has been received") -> {
                        results.add(IdentifiedTarget(node, bounds, TargetType.CLAIM_CONFIRMATION, text))
                    }
                    successKeywords.any { lower.contains(it) } && bounds.top > 0 -> {
                        results.add(IdentifiedTarget(node, bounds, TargetType.CLAIM_CONFIRMATION, text))
                    }
                    failedTexts.any { lower.contains(it) } && !lower.contains("received completely") -> {
                        results.add(IdentifiedTarget(node, bounds, TargetType.CLAIM_FAILED_LABEL, text))
                    }
                    openKeywords.any { lower == it || (lower.length <= 14 && lower.contains(it)) } -> {
                        results.add(IdentifiedTarget(node, bounds, TargetType.OPEN_BUTTON, text))
                    }
                    parseCountdownSeconds(text) != null && text.length <= 12 -> {
                        results.add(
                            IdentifiedTarget(
                                node, bounds, TargetType.COUNTDOWN_LABEL, text,
                                parseCountdownSeconds(text)
                            )
                        )
                    }
                    envelopeKeywords.any { lower.contains(it) } -> {
                        results.add(IdentifiedTarget(node, bounds, TargetType.FLOATING_ENVELOPE, text))
                    }
                }
            }
        }
        return results
    }

    fun collectTexts(rootNode: AccessibilityNodeInfo?): List<String> {
        if (rootNode == null) return emptyList()
        val out = ArrayList<String>()
        traverseNodes(rootNode) { node ->
            val t = nodeText(node)
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    fun screenHasAnyText(rootNode: AccessibilityNodeInfo?, needles: List<String>): Boolean {
        val texts = collectTexts(rootNode)
        return texts.any { t -> needles.any { n -> t.contains(n, ignoreCase = true) } }
    }

    fun findNodeByText(rootNode: AccessibilityNodeInfo?, needles: List<String>): IdentifiedTarget? {
        if (rootNode == null) return null
        var found: IdentifiedTarget? = null
        traverseNodes(rootNode) { node ->
            if (found == null) {
                val t = nodeText(node)
                if (t.isNotEmpty() && needles.any { n -> t.equals(n, ignoreCase = true) || t.contains(n, ignoreCase = true) }) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    found = IdentifiedTarget(node, bounds, TargetType.OPEN_BUTTON, t)
                }
            }
        }
        return found
    }

    fun nodeText(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        return node.contentDescription?.toString()?.trim().orEmpty()
    }

    fun findClickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return node
    }

    private fun traverseNodes(root: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (!queue.isEmpty() && count < 600) {
            val current = queue.removeFirst()
            count++
            try {
                action(current)
                for (i in 0 until current.childCount) {
                    val child = current.getChild(i)
                    if (child != null) queue.add(child)
                }
            } catch (e: Exception) {
                // node recycled mid-scan — ignore
            }
        }
    }
}
