package com.yo2.autoclaim

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** On-device OCR via ML Kit (no internet needed). Blocking call — use off the main thread. */
object OcrHelper {

    data class Line(val text: String, val box: Rect?)

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun recognize(bmp: Bitmap, timeoutMs: Long = 5000): List<Line> {
        return try {
            val latch = CountDownLatch(1)
            var out: List<Line> = emptyList()
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { v ->
                    out = v.textBlocks.flatMap { b ->
                        b.lines.map { ln -> Line(ln.text ?: "", ln.boundingBox) }
                    }
                    latch.countDown()
                }
                .addOnFailureListener { latch.countDown() }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            out
        } catch (e: Exception) {
            LogStore.add("OCR", "error: ${e.message}")
            emptyList()
        }
    }

    /** Crop + OCR, useful for the small timer region (faster). */
    fun recognizeCrop(bmp: Bitmap, crop: Rect, pad: Int = 12, timeoutMs: Long = 4000): List<Line> {
        val w = bmp.width; val h = bmp.height
        val r = Rect(
            (crop.left - pad).coerceAtLeast(0),
            (crop.top - pad).coerceAtLeast(0),
            (crop.right + pad).coerceAtMost(w),
            (crop.bottom + pad).coerceAtMost(h)
        )
        if (r.width() < 8 || r.height() < 8) return emptyList()
        val sub = try {
            Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
        } catch (e: Exception) { return emptyList() }
        val lines = recognize(sub, timeoutMs)
        sub.recycle()
        // shift boxes back to full-bitmap coordinates
        return lines.map { ln ->
            val b = ln.box?.let { Rect(it.left + r.left, it.top + r.top, it.right + r.left, it.bottom + r.top) }
            Line(ln.text, b)
        }
    }
}
