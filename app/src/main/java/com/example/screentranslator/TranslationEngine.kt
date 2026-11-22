package com.example.screentranslator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class TranslationEngine {
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun translate(
        text: String,
        targetLang: String = "iw",
        sourceLang: String = "auto"
    ): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""

        val cacheKey = "$sourceLang->$targetLang:$clean"
        cache[cacheKey]?.let { return it }

        val translated = withContext(Dispatchers.IO) {
            fetchTranslation(clean, sourceLang, targetLang)
        }
        cache[cacheKey] = translated
        return translated
    }

    private fun fetchTranslation(text: String, sourceLang: String, targetLang: String): String {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urlStr =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encoded"
            val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONArray(body)
                val sentences = root.optJSONArray(0) ?: return text
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val part = sentences.optJSONArray(i)
                    val chunk = part?.optString(0)
                    if (!chunk.isNullOrEmpty() && chunk != "null") {
                        sb.append(chunk)
                    }
                }
                val result = sb.toString().trim()
                if (result.isNotEmpty()) result else text
            } else {
                text
            }
        } catch (_: Exception) {
            text
        }
    }

    fun clearCache() {
        cache.clear()
    }
}
