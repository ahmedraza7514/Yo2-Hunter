package com.yo2.autoclaim

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Pure image analysis (no ML): finds the red envelope banner at the top of the
 * screen and classifies it by its left icon —
 *   DIRECT   = red purse/envelope icon (Image 1)
 *   COUNTDOWN = golden hourglass icon  (Image 2)
 * Also finds the big centered envelope popup (for the X button position).
 */
object Vision {

    enum class BannerType { DIRECT, COUNTDOWN }

    data class Banner(val box: Rect, val type: BannerType)

    private fun isRed(r: Int, g: Int, b: Int) = r > 140 && r > g * 1.6 && r > b * 1.6
    private fun isGold(r: Int, g: Int, b: Int) = r > 170 && g > 120 && b < 130 && r >= g

    private fun rgb(bmp: Bitmap, x: Int, y: Int): IntArray {
        val p = bmp.getPixel(x, y)
        return intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }

    /**
     * Scan the top strip (below status bar) for a horizontal red band = banner.
     * Returns null when nothing found.
     */
    fun findBanner(bmp: Bitmap): Banner? {
        val w = bmp.width; val h = bmp.height
        val y0 = (h * 0.05).toInt()
        val y1 = (h * 0.30).toInt()
        val x0 = (w * 0.04).toInt()
        val x1 = (w * 0.96).toInt()
        val step = 4

        var runStart = -1
        var bestStart = -1
        var bestEnd = -1

        var y = y0
        while (y < y1) {
            var red = 0; var tot = 0
            var x = x0
            while (x < x1) {
                val c = rgb(bmp, x, y); tot++
                if (isRed(c[0], c[1], c[2])) red++
                x += step
            }
            if (red.toDouble() / tot > 0.16) {
                if (runStart < 0) runStart = y
            } else if (runStart >= 0) {
                val rh = y - runStart
                if (rh >= h * 0.035 && rh <= h * 0.20 && rh > bestEnd - bestStart) {
                    bestStart = runStart; bestEnd = y
                }
                runStart = -1
            }
            y += step
        }
        if (runStart >= 0) {
            val rh = y1 - runStart
            if (rh >= h * 0.035 && rh <= h * 0.20 && rh > bestEnd - bestStart) {
                bestStart = runStart; bestEnd = y1
            }
        }
        if (bestStart < 0) return null

        val box = Rect(x0, bestStart, x1, bestEnd)
        return Banner(box, classifyIcon(bmp, box))
    }

    /** Left ~28% of the banner holds the icon: red purse => DIRECT, gold hourglass => COUNTDOWN. */
    private fun classifyIcon(bmp: Bitmap, banner: Rect): BannerType {
        val ix1 = banner.left + (banner.width() * 0.28).toInt()
        var red = 0; var gold = 0; var tot = 0
        val step = 3
        var y = banner.top
        while (y < banner.bottom) {
            var x = banner.left
            while (x < ix1) {
                val c = rgb(bmp, x, y); tot++
                if (isRed(c[0], c[1], c[2])) red++ else if (isGold(c[0], c[1], c[2])) gold++
                x += step
            }
            y += step
        }
        if (tot == 0) return BannerType.DIRECT
        val rf = red.toDouble() / tot
        val gf = gold.toDouble() / tot
        // Ambiguous -> DIRECT (fail towards action; in-room OCR re-classifies anyway).
        return if (rf >= gf) BannerType.DIRECT else BannerType.COUNTDOWN
    }

    /**
     * Find the big centered red envelope popup (for locating the X below it).
     * Looks for a tall red region in the middle of the screen.
     */
    fun findEnvelope(bmp: Bitmap): Rect? {
        val w = bmp.width; val h = bmp.height
        val y0 = (h * 0.20).toInt()
        val y1 = (h * 0.85).toInt()
        val x0 = (w * 0.10).toInt()
        val x1 = (w * 0.90).toInt()
        val step = 6

        var runStart = -1
        var bestStart = -1
        var bestEnd = -1
        var y = y0
        while (y < y1) {
            var red = 0; var tot = 0
            var x = x0
            while (x < x1) {
                val c = rgb(bmp, x, y); tot++
                if (isRed(c[0], c[1], c[2])) red++
                x += step
            }
            if (red.toDouble() / tot > 0.35) {
                if (runStart < 0) runStart = y
            } else if (runStart >= 0) {
                val rh = y - runStart
                if (rh >= h * 0.25 && rh > bestEnd - bestStart) {
                    bestStart = runStart; bestEnd = y
                }
                runStart = -1
            }
            y += step
        }
        if (runStart >= 0) {
            val rh = y1 - runStart
            if (rh >= h * 0.25 && rh > bestEnd - bestStart) {
                bestStart = runStart; bestEnd = y1
            }
        }
        if (bestStart < 0) return null
        return Rect(x0, bestStart, x1, bestEnd)
    }

    /** Position of the X close button: just below the envelope, centered. */
    fun xButtonPos(bmp: Bitmap): Pair<Float, Float> {
        val env = findEnvelope(bmp)
        return if (env != null) {
            Pair(env.centerX().toFloat(), (env.bottom + bmp.height * 0.05).toFloat())
        } else {
            Pair(bmp.width * 0.5f, bmp.height * 0.80f)
        }
    }
}
