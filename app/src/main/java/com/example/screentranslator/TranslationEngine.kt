package com.example.screentranslator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

class OfflineEnHeTranslator {
    private val cache = ConcurrentHashMap<String, String>()
    private var translator: Translator? = null
    private var isModelReady = false

    suspend fun prepareModel(requireWifiOnly: Boolean = false): Boolean {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.HEBREW)
            .build()

        val client = Translation.getClient(options)
        translator = client

        return try {
            val conditionsBuilder = DownloadConditions.Builder()
            if (requireWifiOnly) {
                conditionsBuilder.requireWifi()
            }
            client.downloadModelIfNeeded(conditionsBuilder.build()).await()
            isModelReady = true
            true
        } catch (_: Exception) {
            isModelReady = false
            false
        }
    }

    suspend fun translateEnglishToHebrew(text: String): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""

        cache[clean]?.let { return it }

        val client = translator ?: return clean
        if (!isModelReady) {
            val ready = prepareModel(requireWifiOnly = false)
            if (!ready) return clean
        }

        return try {
            val result = client.translate(clean).await().trim()
            val finalText = if (result.isNotEmpty()) result else clean
            cache[clean] = finalText
            finalText
        } catch (_: Exception) {
            clean
        }
    }

    fun close() {
        translator?.close()
        translator = null
        isModelReady = false
    }
}
