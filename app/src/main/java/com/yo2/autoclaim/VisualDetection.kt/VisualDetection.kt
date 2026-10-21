package com.yo2.autoclaim

import android.graphics.Bitmap

/**
 * Screenshot colour analysis — the old app's visual fallback, plus a
 * top-strip scan for the scrolling red banner (YO2-HUNTER spec).
 *
 * Colour rules (from the old app):
 *  red  : R > 140, G < 80,  B < 90
 *  gold : R > 190, G > 130, B < 120
 */
object VisualDetection {

    data class Result(
        val bannerPresent: Boolean,
        val envelopeReady: Boolean,
        val iconRedRatio: Float,
        val iconGoldRatio: Float,
        val bannerRedRatio: Float,
        val tapX: Int,
        val tapY: Int
    )

    // Room popup regions — X as fractions of WIDTH, Y as fractions of
    // HEIGHT. (Until v2.9 the Y values were wrongly computed from the width;
    // on a tall phone the windows sat at ~0.13h instead of ~0.28h and this
    // check never saw the popup at all — verified on real captures
    // 2026-10-21: old window red 0.00 vs height-based 0.89 on popup frames.)
    private const val ICON_LEFT = 0.18f
    private const val ICON_RIGHT = 0.36f
    private const val ICON_TOP = 0.28f
    private const val ICON_BOTTOM = 0.44f
    private const val BANNER_LEFT = 0.45f
    private const val BANNER_RIGHT = 0.8f
    private const val BANNER_TOP = 0.3f
    private const val BANNER_BOTTOM = 0.42f
    private const val BANNER_RED_MIN = 0.3f
    private const val ENVELOPE_RED_MIN = 0.5f

    /** Analyse the room red-envelope popup area. */
    fun analyzeBanner(bmp: Bitmap): Result {
        val w = bmp.width
        val h = bmp.height
        val iconL = (w * ICON_LEFT).toInt()
        val iconR = (w * ICON_RIGHT).toInt()
        val iconT = (h * ICON_TOP).toInt()
        val iconB = minOf((h * ICON_BOTTOM).toInt(), h - 1)
        val tapX = (iconL + iconR) / 2
        val tapY = (iconT + iconB) / 2
        val innerW = (iconR - iconL) / 4
        val innerH = (iconB - iconT) / 4
        val inner = ratios(bmp, tapX - innerW, tapY - innerH, tapX + innerW, tapY + innerH)
        val banner = ratios(
            bmp,
            (w * BANNER_LEFT).toInt(),
            (h * BANNER_TOP).toInt(),
            (w * BANNER_RIGHT).toInt(),
            minOf((h * BANNER_BOTTOM).toInt(), h - 1)
        )
        val bannerPresent = banner.first >= BANNER_RED_MIN
        val envelopeReady = bannerPresent && inner.first >= ENVELOPE_RED_MIN
        return Result(bannerPresent, envelopeReady, inner.first, inner.second, banner.first, tapX, tapY)
    }

    data class TopBannerResult(
        val present: Boolean,
        val redRatio: Float,
        val warmRatio: Float,
        val tapX: Int,
        val tapY: Int
    )

    /**
     * Scan the banner band for the scrolling red-envelope banner.
     *
     * Measured from a real screen recording (2026-10-21): the banner rides at
     * 0.095–0.175 of screen height (over the Balance row). Its ribbon is TRUE
     * red (strict rule matches 0.14–0.19 of the band), while the look-alike
     * gift/avatar banners are ORANGE (strict red only 0.03–0.04, warm 0.12).
     * So: envelope = strict red >= 0.09. The tap lands on the centroid of the
     * red pixels, which tracks the banner as it scrolls.
     */
    fun analyzeTopBanner(bmp: Bitmap): TopBannerResult {
        val w = bmp.width
        val h = bmp.height
        val top = (h * 0.095f).toInt()
        val bottom = (h * 0.175f).toInt()
        val left = (w * 0.03f).toInt()
        val right = (w * 0.97f).toInt()
        var total = 0
        var red = 0
        var warm = 0
        var redXSum = 0L
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val p = bmp.getPixel(x, y)
                val rr = (p shr 16) and 255
                val gg = (p shr 8) and 255
                val bb = p and 255
                total++
                if (rr > 140 && gg < 80 && bb < 90) {
                    red++
                    redXSum += x
                } else if (rr > 150 && gg in 60..170 && bb < 130 && rr - bb > 70) {
                    warm++
                }
                x += 3
            }
            y += 3
        }
        val redRatio = if (total == 0) 0f else red.toFloat() / total
        val warmRatio = if (total == 0) 0f else warm.toFloat() / total
        val present = redRatio >= 0.09f
        val tapX = if (red > 0) (redXSum / red).toInt() else w / 2
        return TopBannerResult(present, redRatio, warmRatio, tapX, (top + bottom) / 2)
    }

    /** Which kind of envelope a floating banner carries. */
    enum class BannerKind { DIRECT, COUNTDOWN, UNKNOWN }

    /**
     * Classify a floating banner as DIRECT or COUNTDOWN from its icon.
     *
     * The two banners differ only in the medallion at the ribbon's left end
     * (Ahmed Bhai's reference screenshots, 2026-10-21): DIRECT carries one
     * red purse — the medallion centre is solid red; COUNTDOWN carries a
     * golden hourglass flanked by two small purses — the centre is gold.
     *
     * Measured on 8 real video frames: in the medallion-centre window
     * (red-extent left edge + 0.025w .. + 0.10w, shares over warm pixels)
     * every DIRECT banner read red >= 0.68 and gold <= 0.26, while every
     * COUNTDOWN banner failed at least one side of that test (red as low as
     * 0.27, gold as high as 0.62). A banner whose red extent is narrower
     * than 0.45w is not fully on screen yet, so it stays UNKNOWN — the
     * caller re-checks on the next pass while it crosses.
     */
    fun classifyBanner(bmp: Bitmap): BannerKind {
        val w = bmp.width
        val h = bmp.height
        val top = (h * 0.095f).toInt()
        val bottom = (h * 0.185f).toInt()
        // Pass 1: strict-red extent of the ribbon in the banner band.
        var leftEdge = Int.MAX_VALUE
        var rightEdge = Int.MIN_VALUE
        var y = top
        while (y < bottom) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                val rr = (p shr 16) and 255
                val gg = (p shr 8) and 255
                val bb = p and 255
                if (rr > 140 && gg < 80 && bb < 90) {
                    if (x < leftEdge) leftEdge = x
                    if (x > rightEdge) rightEdge = x
                }
                x += 2
            }
            y += 2
        }
        if (rightEdge == Int.MIN_VALUE) return BannerKind.UNKNOWN
        if (rightEdge - leftEdge < (w * 0.45f).toInt()) return BannerKind.UNKNOWN
        // Pass 2: warm-colour shares in the medallion-centre window.
        val x0 = leftEdge + (w * 0.025f).toInt()
        val x1 = leftEdge + (w * 0.10f).toInt()
        var warm = 0
        var red = 0
        var gold = 0
        y = top
        while (y < bottom) {
            var x = x0
            while (x < x1 && x < w) {
                val p = bmp.getPixel(x, y)
                val rr = (p shr 16) and 255
                val gg = (p shr 8) and 255
                val bb = p and 255
                val isRed = rr > 140 && gg < 80 && bb < 90
                val isGold = rr > 170 && gg > 125 && bb < 130 && rr - bb >= 60
                val isCream = rr > 195 && gg > 170 && bb > 125 && (rr - bb) in 30..95
                if (isRed || isGold || isCream) {
                    warm++
                    if (isRed) red++
                    if (isGold) gold++
                }
                x += 2
            }
            y += 2
        }
        if (warm < 30) return BannerKind.UNKNOWN
        val redShare = red.toFloat() / warm
        val goldShare = gold.toFloat() / warm
        return when {
            redShare >= 0.55f && goldShare <= 0.30f -> BannerKind.DIRECT
            goldShare >= 0.40f -> BannerKind.COUNTDOWN
            else -> BannerKind.UNKNOWN
        }
    }

    /**
     * Strict-red ratio over the central card region. The claim RESULT screen
     * ("Deposited into your account / Received x/x") is one big red card —
     * measured 0.49 on a real capture, vs 0.00 for a plain room — so >= 0.30
     * means the claim screen is showing. (Its text is canvas-drawn and never
     * appears in the accessibility tree, so nodes alone can't confirm it.)
     */
    fun resultScreenRed(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        return ratios(
            bmp,
            (w * 0.10f).toInt(), (h * 0.25f).toInt(),
            (w * 0.90f).toInt(), (h * 0.72f).toInt()
        ).first
    }

    /** Returns (redRatio, goldRatio) for a region, sampling every 3rd pixel. */
    private fun ratios(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Pair<Float, Float> {
        val l = left.coerceIn(0, bmp.width - 1)
        val r = right.coerceIn(l + 1, bmp.width)
        val t = top.coerceIn(0, bmp.height - 1)
        val b = bottom.coerceIn(t + 1, bmp.height)
        var total = 0
        var red = 0
        var gold = 0
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val p = bmp.getPixel(x, y)
                val rr = (p shr 16) and 255
                val gg = (p shr 8) and 255
                val bb = p and 255
                total++
                if (rr > 140 && gg < 80 && bb < 90) {
                    red++
                } else if (rr > 190 && gg > 130 && bb < 120) {
                    gold++
                }
                x += 3
            }
            y += 3
        }
        return if (total == 0) Pair(0f, 0f)
        else Pair(red.toFloat() / total, gold.toFloat() / total)
    }
}
