package com.example.screentranslator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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
    private val latinWordRegex = Regex("[A-Za-z\\u00C0-\\u024F\\u1E00-\\u1EFF]{2,}")

    @Volatile
    private var isClosed = false

    suspend fun recognize(
        bitmap: Bitmap,
        targetLang: LanguageOption = LanguageCatalog.defaultTarget,
        bitmapOffsetX: Int = 0,
        bitmapOffsetY: Int = 0,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        minTopY: Int = 0,
        maxBottomY: Int = Int.MAX_VALUE,
        excludeRect: Rect? = null
    ): List<RecognizedBlock> {
        if (isClosed || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return emptyList()
        }

        val visionText = try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(inputImage).await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CrashGuard.recordError("TextRecognizer.process", t)
            return emptyList()
        }

        return withContext(Dispatchers.Default) {
            if (isClosed || bitmap.isRecycled) return@withContext emptyList()
            CrashGuard.safeCall("TextRecognizer.extractBlocks", emptyList()) {
                extractBlocks(
                    bitmap = bitmap,
                    visionText = visionText,
                    targetLang = targetLang,
                    bitmapOffsetX = bitmapOffsetX,
                    bitmapOffsetY = bitmapOffsetY,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    minTopY = minTopY,
                    maxBottomY = maxBottomY,
                    excludeRect = excludeRect
                )
            }
        }
    }

    fun close() {
        isClosed = true
        CrashGuard.safeRun("TextRecognizer.close") {
            recognizer.close()
        }
    }

    private fun extractBlocks(
        bitmap: Bitmap,
        visionText: Text,
        targetLang: LanguageOption,
        bitmapOffsetX: Int,
        bitmapOffsetY: Int,
        scaleX: Float,
        scaleY: Float,
        minTopY: Int,
        maxBottomY: Int,
        excludeRect: Rect?
    ): List<RecognizedBlock> {
        val result = ArrayList<RecognizedBlock>(visionText.textBlocks.size * 2)

        for (block in visionText.textBlocks) {
            val lines = block.lines
            if (lines.isEmpty()) continue

            if (shouldSplitIntoLines(lines, targetLang)) {
                for (line in lines) {
                    val box = line.boundingBox ?: continue
                    val conf = runCatching { line.confidence }.getOrNull()
                    if (conf != null && conf > 0f && conf < 0.36f) continue

                    val built = buildRecognizedBlock(
                        rawText = line.text,
                        localBox = box,
                        lineCount = 1,
                        primaryCorners = line.cornerPoints,
                        blockCorners = line.cornerPoints,
                        bitmap = bitmap,
                        bitmapOffsetX = bitmapOffsetX,
                        bitmapOffsetY = bitmapOffsetY,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        minTopY = minTopY,
                        maxBottomY = maxBottomY,
                        excludeRect = excludeRect
                    )
                    if (built != null) result.add(built)
                }
            } else {
                val box = block.boundingBox ?: continue
                val confidences = lines.mapNotNull { line ->
                    runCatching { line.confidence }.getOrNull()?.takeIf { it > 0f }
                }
                if (confidences.isNotEmpty() && confidences.average() < 0.38) continue

                val built = buildRecognizedBlock(
                    rawText = block.text,
                    localBox = box,
                    lineCount = lines.size,
                    primaryCorners = lines.firstOrNull()?.cornerPoints ?: block.cornerPoints,
                    blockCorners = block.cornerPoints,
                    bitmap = bitmap,
                    bitmapOffsetX = bitmapOffsetX,
                    bitmapOffsetY = bitmapOffsetY,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    minTopY = minTopY,
                    maxBottomY = maxBottomY,
                    excludeRect = excludeRect
                )
                if (built != null) result.add(built)
            }
        }

        return result
    }

    private fun containsTargetNonLatinScript(text: String, targetLang: LanguageOption): Boolean {
        return when (targetLang.script) {
            ScriptType.RTL_HEBREW -> text.any { it in '\u0590'..'\u05FF' }
            ScriptType.RTL_ARABIC -> text.any { it in '\u0600'..'\u06FF' }
            ScriptType.CYRILLIC -> text.any { it in '\u0400'..'\u04FF' }
            ScriptType.GREEK -> text.any { it in '\u0370'..'\u03FF' }
            ScriptType.DEVANAGARI -> text.any { it in '\u0900'..'\u097F' }
            ScriptType.CJK -> text.any {
                it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' || it in '\uAC00'..'\uD7AF'
            }
            ScriptType.LATIN -> false
        }
    }

    private fun shouldSplitIntoLines(lines: List<Text.Line>, targetLang: LanguageOption): Boolean {
        if (lines.size <= 1) return false
        if (lines.any { containsTargetNonLatinScript(it.text, targetLang) }) return true

        val boxes = lines.mapNotNull { it.boundingBox }
        if (boxes.size != lines.size) return true

        val avgHeight = boxes.map { it.height() }.average().toFloat().coerceAtLeast(8f)
        for (i in 0 until boxes.size - 1) {
            val a = boxes[i]
            val b = boxes[i + 1]
            val verticalGap = b.top - a.bottom
            if (verticalGap > avgHeight * 0.85f) return true

            val leftShift = abs(b.left - a.left)
            val centerShift = abs(b.centerX() - a.centerX())
            if (leftShift > avgHeight * 2.2f && centerShift > avgHeight * 2.2f) return true
        }
        return false
    }

    private fun buildRecognizedBlock(
        rawText: String,
        localBox: Rect,
        lineCount: Int,
        primaryCorners: Array<Point>?,
        blockCorners: Array<Point>?,
        bitmap: Bitmap,
        bitmapOffsetX: Int,
        bitmapOffsetY: Int,
        scaleX: Float,
        scaleY: Float,
        minTopY: Int,
        maxBottomY: Int,
        excludeRect: Rect?
    ): RecognizedBlock? {
        val cleanText = rawText.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (cleanText.isEmpty() || !latinWordRegex.containsMatchIn(cleanText)) return null

        val screenBox = Rect(
            ((localBox.left + bitmapOffsetX) * scaleX).toInt(),
            ((localBox.top + bitmapOffsetY) * scaleY).toInt(),
            ((localBox.right + bitmapOffsetX) * scaleX).toInt(),
            ((localBox.bottom + bitmapOffsetY) * scaleY).toInt()
        )

        if (screenBox.height() < 14 || screenBox.width() < 16) return null
        if (screenBox.bottom <= minTopY || screenBox.top >= maxBottomY) return null
        if (excludeRect != null && Rect.intersects(screenBox, excludeRect)) return null

        var angleDegrees = 0f
        var orientedW = screenBox.width()
        var orientedH = screenBox.height()

        if (primaryCorners != null && primaryCorners.size == 4) {
            val p0 = primaryCorners[0]
            val p1 = primaryCorners[1]
            val rawAngle = Math.toDegrees(
                atan2((p1.y - p0.y).toDouble(), (p1.x - p0.x).toDouble())
            ).toFloat()

            if (abs(rawAngle) in 2.5f..85f) {
                angleDegrees = rawAngle
                if (blockCorners != null && blockCorners.size == 4) {
                    val topLen = hypot(
                        ((blockCorners[1].x - blockCorners[0].x) * scaleX).toDouble(),
                        ((blockCorners[1].y - blockCorners[0].y) * scaleY).toDouble()
                    ).toInt()
                    val sideLen = hypot(
                        ((blockCorners[3].x - blockCorners[0].x) * scaleX).toDouble(),
                        ((blockCorners[3].y - blockCorners[0].y) * scaleY).toDouble()
                    ).toInt()
                    if (topLen > 16 && sideLen > 12) {
                        orientedW = topLen
                        orientedH = sideLen
                    }
                }
            }
        }

        val palette = sampleExactPaletteSafe(bitmap, localBox)
        return RecognizedBlock(
            text = cleanText,
            bounds = screenBox,
            lineCount = lineCount.coerceAtLeast(1),
            angleDegrees = angleDegrees,
            orientedWidth = orientedW.coerceAtLeast(20),
            orientedHeight = orientedH.coerceAtLeast(18),
            bgTopColor = palette.bgTopColor,
            bgBottomColor = palette.bgBottomColor,
            textColor = palette.textColor,
            needsTextShadow = palette.needsShadow
        )
    }

    private data class SampledPalette(
        val bgTopColor: Int,
        val bgBottomColor: Int,
        val textColor: Int,
        val needsShadow: Boolean
    )

    private fun sampleExactPaletteSafe(bitmap: Bitmap, box: Rect): SampledPalette {
        val defaultPalette = SampledPalette(
            bgTopColor = Color.rgb(20, 23, 31),
            bgBottomColor = Color.rgb(20, 23, 31),
            textColor = Color.WHITE,
            needsShadow = false
        )
        if (bitmap.isRecycled) return defaultPalette
        return CrashGuard.safeCall("TextRecognizer.samplePalette", defaultPalette) {
            sampleExactPalette(bitmap, box, defaultPalette)
        }
    }

    private fun sampleExactPalette(
        bitmap: Bitmap,
        box: Rect,
        fallback: SampledPalette
    ): SampledPalette {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 4 || h <= 4) return fallback

        val padX = 4
        val padY = 4
        val regLeft = (box.left - padX).coerceIn(0, w - 1)
        val regRight = (box.right + padX).coerceIn(regLeft, w - 1)
        val regTop = (box.top - padY).coerceIn(0, h - 1)
        val regBottom = (box.bottom + padY).coerceIn(regTop, h - 1)

        val regW = (regRight - regLeft + 1).coerceAtLeast(1)
        val regH = (regBottom - regTop + 1).coerceAtLeast(1)
        // Guard against oversized single-box pixel allocation on huge images
        if (regW.toLong() * regH.toLong() > 1_500_000L) return fallback

        val pixels = IntArray(regW * regH)
        bitmap.getPixels(pixels, 0, regW, regLeft, regTop, regW, regH)

        fun pixelAt(localX: Int, localY: Int): Int {
            val x = localX.coerceIn(0, regW - 1)
            val y = localY.coerceIn(0, regH - 1)
            return pixels[y * regW + x]
        }

        val topBand = ArrayList<Int>(24)
        val bottomBand = ArrayList<Int>(24)
        val stepX = max(1, regW / 14)

        for (x in 0 until regW step stepX) {
            topBand.add(pixelAt(x, 0))
            bottomBand.add(pixelAt(x, regH - 1))
        }

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

        val innerLeft = (box.left - regLeft).coerceIn(0, regW - 1)
        val innerRight = (box.right - regLeft).coerceIn(innerLeft, regW - 1)
        val innerTop = (box.top - regTop).coerceIn(0, regH - 1)
        val innerBottom = (box.bottom - regTop).coerceIn(innerTop, regH - 1)

        val innerW = (innerRight - innerLeft + 1).coerceAtLeast(1)
        val innerH = (innerBottom - innerTop + 1).coerceAtLeast(1)
        val sampleStepX = max(1, innerW / 12)
        val sampleStepY = max(1, innerH / 8)

        val bgR = Color.red(bgMid)
        val bgG = Color.green(bgMid)
        val bgB = Color.blue(bgMid)

        val scored = ArrayList<Pair<Int, Int>>(110)
        var y = innerTop
        while (y <= innerBottom) {
            var x = innerLeft
            while (x <= innerRight) {
                val p = pixelAt(x, y)
                val dr = Color.red(p) - bgR
                val dg = Color.green(p) - bgG
                val db = Color.blue(p) - bgB
                scored.add((dr * dr + dg * dg + db * db) to p)
                x += sampleStepX
            }
            y += sampleStepY
        }

        scored.sortByDescending { it.first }
        val takeCount = max(2, (scored.size * 0.12f).toInt())
        val fgSamples = ArrayList<Int>(takeCount)
        for (i in 0 until min(takeCount, scored.size)) {
            fgSamples.add(scored[i].second)
        }
        val sampledFg = if (fgSamples.isNotEmpty()) medianColor(fgSamples) else Color.WHITE

        val bgLum = relativeLuminance(bgMid)
        val fgLum = relativeLuminance(sampledFg)
        val ratio = contrastRatio(bgMid, sampledFg)

        val finalFg = when {
            ratio >= 4.2 -> sampledFg
            fgLum > bgLum && bgLum < 0.60 -> Color.WHITE
            fgLum <= bgLum && bgLum >= 0.35 -> Color.rgb(17, 24, 39)
            bgLum > 0.45 -> Color.rgb(17, 24, 39)
            else -> Color.WHITE
        }

        val finalFgLum = relativeLuminance(finalFg)
        val needsShadow = finalFgLum > 0.72 && bgLum in 0.14..0.60

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
