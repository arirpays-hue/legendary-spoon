package com.example.screentranslator

import android.graphics.Bitmap
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
    val translatedText: String = ""
)

class TextRecognizerHelper {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): List<RecognizedBlock> = suspendCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val result = visionText.textBlocks.mapNotNull { block ->
                    val cleanText = block.text.replace('\n', ' ').trim()
                    val box = block.boundingBox
                    if (cleanText.isNotEmpty() && box != null) {
                        RecognizedBlock(text = cleanText, bounds = box)
                    } else {
                        null
                    }
                }
                cont.resume(result)
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }
}
