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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class RecognizedBlock(
    val text: String,
    val bounds: Rect,
    val lineCount: Int = 1,
    val angleDegrees: Float = 0f,
    val orientedWidth: Int = bounds.width(),
    val orientedHeight: Int = bounds.height(),
    val bgColor: Int = Color.rgb(20, 23, 31),
    val textColor: Int = Color.WHITE,
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

                    val corners = block.lines.firstOrNull()?.cornerPoints ?: block.cornerPoints
                    var angleDegrees = 0f
                    var orientedW = screenBox.width()
                    var orientedH = screenBox.height()

                    if (corners != null && corners.size == 4) {
                        val p0 = corners[0]
                        val p1 = corners[1]
                        val rawAngle = Math.toDegrees(
                            atan2((p1.y - p0.y).toDouble(), (p1.x - p0.x).toDouble())
                        ).toFloat()

                        if (abs(rawAngle) in 2.5f..85f) {
                            angleDegrees = rawAngle
                            val blockCorners = block.cornerPoints
                            if (blockCorners != null && blockCorners.size == 4) {
                                val topLen = hypot(
                                    (blockCorners[1].x - blockCorners[0].x).toDouble(),
                                    (blockCorners[1].y - blockCorners[0].y).toDouble()
                                ).toInt()
                                val sideLen = hypot(
                                    (blockCorners[3].x - blockCorners[0].x).toDouble(),
                                    (blockCorners[3].y - blockCorners[0].y).toDouble()
                                ).toInt()
                                if (topLen > 16 && sideLen > 12) {
                                    orientedW = topLen
                                    orientedH = sideLen
                                }
                            }
                        }
                    }

                    val (bgColor, textColor) = sampleBlockColors(bitmap, localBox)
                    RecognizedBlock(
                        text = cleanText,
                        bounds = screenBox,
                        lineCount = block.lines.size.coerceAtLeast(1),
                        angleDegrees = angleDegrees,
                        orientedWidth = orientedW.coerceAtLeast(20),
                        orientedHeight = orientedH.coerceAtLeast(18),
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
        if (w <= 4 || h <= 4) {
            return Color.rgb(20, 23, 31) to Color.WHITE
        }

        val left = (box.left - 6).coerceIn(0, w - 1)
        val right = (box.right + 6).coerceIn(0, w - 1)
        val top = (box.top - 6).coerceIn(0, h - 1)
        val bottom = (box.bottom + 6).coerceIn(0, h - 1)
        val q1X = (box.left + box.width() / 4).coerceIn(0, w - 1)
        val midX = ((box.left + box.right) / 2).coerceIn(0, w - 1)
        val q3X = (box.left + (box.width() * 3) / 4).coerceIn(0, w - 1)
        val midY = ((box.top + box.bottom) / 2).coerceIn(0, h - 1)

        val borderPoints = arrayOf(
            left to top,
            q1X to top,
            midX to top,
            q3X to top,
            right to top,
            left to bottom,
            q1X to bottom,
            midX to bottom,
            q3X to bottom,
            right to bottom,
            left to midY,
            right to midY
        )

        var bgSumR = 0
        var bgSumG = 0
        var bgSumB = 0
        for ((px, py) in borderPoints) {
            val pixel = bitmap.getPixel(px, py)
            bgSumR += Color.red(pixel)
            bgSumG += Color.green(pixel)
            bgSumB += Color.blue(pixel)
        }

        var bgR = bgSumR / borderPoints.size
        var bgG = bgSumG / borderPoints.size
        var bgB = bgSumB / borderPoints.size

        // Sample interior pixels to detect the original foreground text color
        val innerLeft = box.left.coerceIn(0, w - 1)
        val innerRight = box.right.coerceIn(innerLeft, w - 1)
        val innerTop = box.top.coerceIn(0, h - 1)
        val innerBottom = box.bottom.coerceIn(innerTop, h - 1)

        val candidates = ArrayList<Pair<Int, Int>>(24)
        val cols = 6
        val rows = 4
        for (r in 1..rows) {
            val py = innerTop + ((innerBottom - innerTop) * r) / (rows + 1)
            for (c in 1..cols) {
                val px = innerLeft + ((innerRight - innerLeft) * c) / (cols + 1)
                val pixel = bitmap.getPixel(px.coerceIn(0, w - 1), py.coerceIn(0, h - 1))
                val dist = abs(Color.red(pixel) - bgR) +
                    abs(Color.green(pixel) - bgG) +
                    abs(Color.blue(pixel) - bgB)
                candidates.add(dist to pixel)
            }
        }

        candidates.sortByDescending { it.first }
        val topCount = max(1, candidates.size / 4)
        var fgSumR = 0
        var fgSumG = 0
        var fgSumB = 0
        for (i in 0 until topCount) {
            val p = candidates[i].second
            fgSumR += Color.red(p)
            fgSumG += Color.green(p)
            fgSumB += Color.blue(p)
        }

        val sampledFg = Color.rgb(fgSumR / topCount, fgSumG / topCount, fgSumB / topCount)
        var finalBg = Color.rgb(bgR, bgG, bgB)
        var bgLum = relativeLuminance(finalBg)

        // If background is in the awkward mid-tone range (like bright lilac/grey), darken slightly for crisp legibility
        if (bgLum in 0.18..0.44) {
            bgR = (bgR * 0.62f).toInt().coerceIn(0, 255)
            bgG = (bgG * 0.62f).toInt().coerceIn(0, 255)
            bgB = (bgB * 0.62f).toInt().coerceIn(0, 255)
            finalBg = Color.rgb(bgR, bgG, bgB)
            bgLum = relativeLuminance(finalBg)
        }

        val sampledContrast = contrastRatio(finalBg, sampledFg)
        val finalFg = if (sampledContrast >= 4.5) {
            sampledFg
        } else if (bgLum > 0.36) {
            Color.rgb(15, 23, 42)
        } else {
            Color.WHITE
        }

        return finalBg to finalFg
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    private fun contrastRatio(c1: Int, c2: Int): Double {
        val l1 = relativeLuminance(c1)
        val l2 = relativeLuminance(c2)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }
}
