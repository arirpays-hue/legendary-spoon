package com.example.screentranslator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

class OfflineEnHeTranslator {
    private val cache = ConcurrentHashMap<String, String>()
    private var translator: Translator? = null
    private var isModelReady = false

    private fun getOrCreateClient(): Translator {
        translator?.let { return it }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.HEBREW)
            .build()
        return Translation.getClient(options).also { translator = it }
    }

    suspend fun isModelDownloaded(): Boolean {
        return try {
            val hebrewModel = TranslateRemoteModel.Builder(TranslateLanguage.HEBREW).build()
            val downloaded = RemoteModelManager.getInstance()
                .isModelDownloaded(hebrewModel)
                .await()
            if (downloaded) {
                getOrCreateClient()
                isModelReady = true
            }
            downloaded
        } catch (_: Exception) {
            false
        }
    }

    suspend fun prepareModel(): Result<Unit> {
        return try {
            val client = getOrCreateClient()
            val conditions = DownloadConditions.Builder().build()
            client.downloadModelIfNeeded(conditions).await()
            isModelReady = true
            Result.success(Unit)
        } catch (e: Exception) {
            isModelReady = false
            Result.failure(e)
        }
    }

    suspend fun translateEnglishToHebrew(text: String): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        if (!containsEnglishLetters(clean)) return ""

        cache[clean]?.let { return it }

        if (!isModelReady) {
            val ready = prepareModel()
            if (ready.isFailure) return ""
        }

        return try {
            val result = getOrCreateClient().translate(clean).await().trim()
            if (result.isNotEmpty()) {
                cache[clean] = result
                result
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun containsEnglishLetters(text: String): Boolean {
        return text.any { it in 'A'..'Z' || it in 'a'..'z' }
    }

    fun close() {
        translator?.close()
        translator = null
        isModelReady = false
    }
}
