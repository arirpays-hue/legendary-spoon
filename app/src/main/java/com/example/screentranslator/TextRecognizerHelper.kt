package com.example.screentranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val bgTopColor: Int = Color.rgb(20, 23, 31),
    val bgBottomColor: Int = Color.rgb(20, 23, 31),
    val textColor: Int = Color.WHITE,
    val needsTextShadow: Boolean = false,
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
    ): List<RecognizedBlock> {
        val visionText = processVisionText(bitmap)
        return withContext(Dispatchers.Default) {
            extractBlocks(
                bitmap = bitmap,
                visionText = visionText,
                offsetX = offsetX,
                offsetY = offsetY,
                minTopY = minTopY,
                maxBottomY = maxBottomY,
                excludeRect = excludeRect
            )
        }
    }

    private suspend fun processVisionText(bitmap: Bitmap): Text = suspendCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun extractBlocks(
        bitmap: Bitmap,
        visionText: Text,
        offsetX: Int,
        offsetY: Int,
        minTopY: Int,
        maxBottomY: Int,
        excludeRect: Rect?
    ): List<RecognizedBlock> {
        val result = ArrayList<RecognizedBlock>(visionText.textBlocks.size)

        for (block in visionText.textBlocks) {
            val localBox = block.boundingBox ?: continue
            if (localBox.height() < 15 || localBox.width() < 18) continue

            // Filter low-confidence OCR detections (prevents icon & Hebrew-misread hallucinations)
            val confidences = block.lines.mapNotNull { line ->
                runCatching { line.confidence }.getOrNull()?.takeIf { it > 0f }
            }
            if (confidences.isNotEmpty() && confidences.average() < 0.66) {
                continue
            }

            val cleanText = block.text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (cleanText.isEmpty() || !englishWordRegex.containsMatchIn(cleanText)) {
                continue
            }

            val screenBox = Rect(
                localBox.left + offsetX,
                localBox.top + offsetY,
                localBox.right + offsetX,
                localBox.bottom + offsetY
            )

            if (screenBox.bottom <= minTopY || screenBox.top >= maxBottomY) continue
            if (excludeRect != null && Rect.intersects(screenBox, excludeRect)) continue

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

            val palette = sampleExactPalette(bitmap, localBox)
            result.add(
                RecognizedBlock(
                    text = cleanText,
                    bounds = screenBox,
                    lineCount = block.lines.size.coerceAtLeast(1),
                    angleDegrees = angleDegrees,
                    orientedWidth = orientedW.coerceAtLeast(20),
                    orientedHeight = orientedH.coerceAtLeast(18),
                    bgTopColor = palette.bgTopColor,
                    bgBottomColor = palette.bgBottomColor,
                    textColor = palette.textColor,
                    needsTextShadow = palette.needsShadow
                )
            )
        }

        return result
    }

    private data class SampledPalette(
        val bgTopColor: Int,
        val bgBottomColor: Int,
        val textColor: Int,
        val needsShadow: Boolean
    )

    private fun sampleExactPalette(bitmap: Bitmap, box: Rect): SampledPalette {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 6 || h <= 6) {
            return SampledPalette(Color.rgb(20, 23, 31), Color.rgb(20, 23, 31), Color.WHITE, false)
        }

        val padX = 8
        val padY = 8
        val regLeft = (box.left - padX).coerceIn(0, w - 1)
        val regRight = (box.right + padX).coerceIn(regLeft, w - 1)
        val regTop = (box.top - padY).coerceIn(0, h - 1)
        val regBottom = (box.bottom + padY).coerceIn(regTop, h - 1)

        val regW = (regRight - regLeft + 1).coerceAtLeast(1)
        val regH = (regBottom - regTop + 1).coerceAtLeast(1)
        val pixels = IntArray(regW * regH)
        bitmap.getPixels(pixels, 0, regW, regLeft, regTop, regW, regH)

        fun pixelAt(localX: Int, localY: Int): Int {
            val x = localX.coerceIn(0, regW - 1)
            val y = localY.coerceIn(0, regH - 1)
            return pixels[y * regW + x]
        }

        // Sample top and bottom perimeter bands using median RGB (immune to icon edges)
        val topBand = ArrayList<Int>(32)
        val bottomBand = ArrayList<Int>(32)
        val stepX = max(1, regW / 18)

        for (x in 0 until regW step stepX) {
            topBand.add(pixelAt(x, 0))
            if (regH > 2) topBand.add(pixelAt(x, 1))
            bottomBand.add(pixelAt(x, regH - 1))
            if (regH > 2) bottomBand.add(pixelAt(x, regH - 2))
        }

        // Also include left/right midpoints to stabilize uniform backgrounds
        val midY = regH / 2
        topBand.add(pixelAt(0, (midY / 2).coerceAtLeast(0)))
        topBand.add(pixelAt(regW - 1, (midY / 2).coerceAtLeast(0)))
        bottomBand.add(pixelAt(0, ((regH + midY) / 2).coerceAtMost(regH - 1)))
        bottomBand.add(pixelAt(regW - 1, ((regH + midY) / 2).coerceAtMost(regH - 1)))

        val bgTop = medianColor(topBand)
        val bgBottom = medianColor(bottomBand)
        val bgMid = Color.rgb(
            (Color.red(bgTop) + Color.red(bgBottom)) / 2,
            (Color.green(bgTop) + Color.green(bgBottom)) / 2,
            (Color.blue(bgTop) + Color.blue(bgBottom)) / 2
        )

        // Sample interior pixels to recover exact original foreground text color
        val innerLeft = (box.left - regLeft).coerceIn(0, regW - 1)
        val innerRight = (box.right - regLeft).coerceIn(innerLeft, regW - 1)
        val innerTop = (box.top - regTop).coerceIn(0, regH - 1)
        val innerBottom = (box.bottom - regTop).coerceIn(innerTop, regH - 1)

        val innerW = (innerRight - innerLeft + 1).coerceAtLeast(1)
        val innerH = (innerBottom - innerTop + 1).coerceAtLeast(1)
        val sampleStepX = max(1, innerW / 16)
        val sampleStepY = max(1, innerH / 10)

        val bgR = Color.red(bgMid)
        val bgG = Color.green(bgMid)
        val bgB = Color.blue(bgMid)

        val scored = ArrayList<Pair<Int, Int>>(160)
        var y = innerTop
        while (y <= innerBottom) {
            var x = innerLeft
            while (x <= innerRight) {
                val p = pixelAt(x, y)
                val dr = Color.red(p) - bgR
                val dg = Color.green(p) - bgG
                val db = Color.blue(p) - bgB
                val distSq = dr * dr + dg * dg + db * db
                scored.add(distSq to p)
                x += sampleStepX
            }
            y += sampleStepY
        }

        scored.sortByDescending { it.first }
        // Take the top 10% most-contrasting pixels to avoid antialiased edge muddiness
        val takeCount = max(2, (scored.size * 0.10f).toInt())
        val fgSamples = ArrayList<Int>(takeCount)
        for (i in 0 until min(takeCount, scored.size)) {
            fgSamples.add(scored[i].second)
        }
        val sampledFg = if (fgSamples.isNotEmpty()) medianColor(fgSamples) else Color.WHITE

        val bgLum = relativeLuminance(bgMid)
        val fgLum = relativeLuminance(sampledFg)
        val ratio = contrastRatio(bgMid, sampledFg)

        val finalFg = when {
            // Preserve exact sampled text color when clearly readable
            ratio >= 4.2 -> sampledFg
            // Near-white text on launcher/gradient -> snap cleanly to crisp white
            fgLum > bgLum && bgLum < 0.62 -> Color.WHITE
            // Near-black text on light surface -> snap cleanly to crisp dark charcoal
            fgLum <= bgLum && bgLum >= 0.35 -> Color.rgb(17, 24, 39)
            // Fallback by background luminance
            bgLum > 0.45 -> Color.rgb(17, 24, 39)
            else -> Color.WHITE
        }

        val finalFgLum = relativeLuminance(finalFg)
        val needsShadow = finalFgLum > 0.72 && bgLum in 0.14..0.62

        return SampledPalette(
            bgTopColor = bgTop,
            bgBottomColor = bgBottom,
            textColor = finalFg,
            needsShadow = needsShadow
        )
    }

    private fun medianColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.rgb(20, 23, 31)
        val n = colors.size
        val rs = IntArray(n)
        val gs = IntArray(n)
        val bs = IntArray(n)
        for (i in 0 until n) {
            val c = colors[i]
            rs[i] = Color.red(c)
            gs[i] = Color.green(c)
            bs[i] = Color.blue(c)
        }
        rs.sort()
        gs.sort()
        bs.sort()
        val mid = n / 2
        return Color.rgb(rs[mid], gs[mid], bs[mid])
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
