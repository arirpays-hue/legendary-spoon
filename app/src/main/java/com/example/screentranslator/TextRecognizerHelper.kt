package com.example.screentranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class RecognizedBlock(
    val text: String,
    val bounds: Rect,
    val lineCount: Int = 1,
    val bgColor: Int = Color.parseColor("#F514171F"),
    val textColor: Int = Color.parseColor("#F8FAFC"),
    val translatedText: String = ""
)

class TextRecognizerHelper {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val englishWordRegex = Regex("[A-Za-z]{2,}")

    suspend fun recognize(
        bitmap: Bitmap,
        offsetX: Int = 0,
        offsetY: Int = 0,
        minTopY: Int = 0,
        maxBottomY: Int = Int.MAX_VALUE,
        excludeRect: Rect? = null
    ): List<RecognizedBlock> = suspendCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val result = visionText.textBlocks.mapNotNull { block ->
                    val cleanText = block.text.replace('\n', ' ').trim()
                    val localBox = block.boundingBox ?: return@mapNotNull null

                    if (cleanText.isEmpty() || !englishWordRegex.containsMatchIn(cleanText)) {
                        return@mapNotNull null
                    }

                    val screenBox = Rect(
                        localBox.left + offsetX,
                        localBox.top + offsetY,
                        localBox.right + offsetX,
                        localBox.bottom + offsetY
                    )

                    if (screenBox.bottom <= minTopY || screenBox.top >= maxBottomY) {
                        return@mapNotNull null
                    }
                    if (excludeRect != null && Rect.intersects(screenBox, excludeRect)) {
                        return@mapNotNull null
                    }

                    val (bgColor, textColor) = sampleBlockColors(bitmap, localBox)
                    RecognizedBlock(
                        text = cleanText,
                        bounds = screenBox,
                        lineCount = block.lines.size.coerceAtLeast(1),
                        bgColor = bgColor,
                        textColor = textColor
                    )
                }
                cont.resume(result)
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    private fun sampleBlockColors(bitmap: Bitmap, box: Rect): Pair<Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 2 || h <= 2) {
            return Color.parseColor("#F514171F") to Color.parseColor("#F8FAFC")
        }

        val left = (box.left - 4).coerceIn(0, w - 1)
        val right = (box.right + 4).coerceIn(0, w - 1)
        val top = (box.top - 4).coerceIn(0, h - 1)
        val bottom = (box.bottom + 4).coerceIn(0, h - 1)
        val midX = ((box.left + box.right) / 2).coerceIn(0, w - 1)
        val midY = ((box.top + box.bottom) / 2).coerceIn(0, h - 1)

        val points = arrayOf(
            left to top,
            right to top,
            left to bottom,
            right to bottom,
            midX to top,
            midX to bottom,
            left to midY,
            right to midY
        )

        var sumR = 0
        var sumG = 0
        var sumB = 0
        for ((px, py) in points) {
            val pixel = bitmap.getPixel(px, py)
            sumR += Color.red(pixel)
            sumG += Color.green(pixel)
            sumB += Color.blue(pixel)
        }

        val avgR = sumR / points.size
        val avgG = sumG / points.size
        val avgB = sumB / points.size

        val luminance = (0.299 * avgR) + (0.587 * avgG) + (0.114 * avgB)
        val bgColor = Color.argb(246, avgR, avgG, avgB)
        val textColor = if (luminance > 145.0) {
            Color.parseColor("#111827")
        } else {
            Color.parseColor("#F8FAFC")
        }
        return bgColor to textColor
    }
}
