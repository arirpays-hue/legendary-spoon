package com.example.screentranslator

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class OfflineEnHeTranslator(context: Context) {

    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, "en_he_translation_cache.json")
    private val cache = ConcurrentHashMap<String, String>()

    @Volatile
    private var translator: Translator? = null
    @Volatile
    private var isModelReady = false
    @Volatile
    private var cacheLoaded = false

    private val englishWordRegex = Regex("[A-Za-z]{2,}")
    private val urlOrCodeFileRegex = Regex(
        ".*(://|www\\.|\\.(kt|java|xml|yml|yaml|json|gradle|apk|png|jpg|mp4)\\b|[A-Za-z0-9_]+/[A-Za-z0-9_]+/[A-Za-z0-9_]+).*",
        RegexOption.IGNORE_CASE
    )

    private val skippedBrands = setOf(
        "whatsapp", "spotify", "waze", "gemini", "firefox", "chrome", "youtube",
        "instagram", "telegram", "tiktok", "facebook", "netflix", "github", "gmail",
        "capcut", "metrolist", "ashell", "aida64", "shizuku", "termux", "zarchiver",
        "moovit", "egged", "canva", "discord", "reddit", "snapchat", "steam", "wolt",
        "uber", "chatgpt", "claude", "1dm", "1dm+", "wi-fi", "wifi", "bluetooth",
        "volte", "4g", "5g", "lte", "usb", "adb", "apk", "pdf", "vpn"
    )

    private fun ensureCacheLoaded() {
        if (cacheLoaded) return
        synchronized(this) {
            if (cacheLoaded) return
            runCatching {
                if (cacheFile.exists()) {
                    val json = JSONObject(cacheFile.readText())
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = json.optString(k)
                        if (k.isNotEmpty() && v.isNotEmpty()) {
                            cache[k] = v
                        }
                    }
                }
            }
            cacheLoaded = true
        }
    }

    private fun persistCache() {
        runCatching {
            val entries = if (cache.size > 4000) cache.entries.take(4000) else cache.entries
            val json = JSONObject()
            for ((k, v) in entries) {
                json.put(k, v)
            }
            cacheFile.writeText(json.toString())
        }
    }

    private fun getOrCreateClient(): Translator {
        translator?.let { return it }
        synchronized(this) {
            translator?.let { return it }
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.HEBREW)
                .build()
            return Translation.getClient(options).also { translator = it }
        }
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

    fun shouldSkipText(rawText: String, skipBrands: Boolean): Boolean {
        val text = rawText.trim()
        if (text.length < 2) return true

        val hebrewCount = text.count { it in '\u0590'..'\u05FF' }
        val englishCount = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        if (englishCount < 2 || !englishWordRegex.containsMatchIn(text)) return true

        val totalLetters = hebrewCount + englishCount
        if (hebrewCount > 0 && hebrewCount.toFloat() / totalLetters >= 0.35f) return true

        if (urlOrCodeFileRegex.matches(text)) return true

        val nonSpaceCount = text.count { !it.isWhitespace() }
        if (nonSpaceCount > 0 && englishCount.toFloat() / nonSpaceCount < 0.35f) return true

        if (skipBrands) {
            val lower = text.lowercase(Locale.US).trim('.', ',', ':', '!', '?', '"', '\'', '(', ')')
            if (lower in skippedBrands) return true
        }

        return false
    }

    suspend fun translateBatch(
        texts: List<String>,
        offlineOnly: Boolean = false
    ): Map<String, String> = withContext(Dispatchers.IO) {
        ensureCacheLoaded()

        val uniqueInputs = texts
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (uniqueInputs.isEmpty()) return@withContext emptyMap()

        val results = ConcurrentHashMap<String, String>()
        val uncached = ArrayList<String>()

        for (item in uniqueInputs) {
            val cached = cache[item]
            if (cached != null) {
                results[item] = cached
            } else {
                uncached.add(item)
            }
        }

        if (uncached.isEmpty()) return@withContext results

        val stillMissing = ArrayList<String>()

        if (!offlineOnly) {
            val onlineResolved = translateBatchOnline(uncached)
            for (item in uncached) {
                val tr = onlineResolved[item]
                if (!tr.isNullOrBlank()) {
                    results[item] = tr
                    cache[item] = tr
                } else {
                    stillMissing.add(item)
                }
            }
        } else {
            stillMissing.addAll(uncached)
        }

        if (stillMissing.isNotEmpty()) {
            val offlineResolved = translateBatchOffline(stillMissing)
            for ((src, tr) in offlineResolved) {
                if (tr.isNotBlank()) {
                    results[src] = tr
                    cache[src] = tr
                }
            }
        }

        if (results.isNotEmpty()) {
            persistCache()
        }

        results
    }

    suspend fun translateEnglishToHebrew(
        text: String,
        offlineOnly: Boolean = false
    ): String {
        val clean = text.replace('\n', ' ').trim()
        if (clean.isEmpty()) return ""
        return translateBatch(listOf(clean), offlineOnly = offlineOnly)[clean] ?: ""
    }

    private suspend fun translateBatchOnline(items: List<String>): Map<String, String> = coroutineScope {
        val out = ConcurrentHashMap<String, String>()

        val chunks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var currentLen = 0
        for (item in items) {
            if (current.isNotEmpty() && (current.size >= 12 || currentLen + item.length > 900)) {
                chunks.add(current)
                current = ArrayList()
                currentLen = 0
            }
            current.add(item)
            currentLen += item.length + 2
        }
        if (current.isNotEmpty()) chunks.add(current)

        chunks.map { chunk ->
            async(Dispatchers.IO) {
                if (chunk.size == 1) {
                    val single = fetchOnlineTranslation(chunk[0])
                    if (!single.isNullOrBlank()) {
                        out[chunk[0]] = single
                    }
                } else {
                    val joined = chunk.joinToString("\n")
                    val batched = fetchOnlineTranslation(joined)
                    val lines = batched?.split('\n')?.map { it.trim() }

                    if (lines != null && lines.size == chunk.size) {
                        for (i in chunk.indices) {
                            if (lines[i].isNotEmpty()) {
                                out[chunk[i]] = lines[i]
                            }
                        }
                    } else {
                        coroutineScope {
                            chunk.map { item ->
                                async(Dispatchers.IO) {
                                    val single = fetchOnlineTranslation(item)
                                    if (!single.isNullOrBlank()) {
                                        out[item] = single
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                }
            }
        }.awaitAll()

        out
    }

    private fun fetchOnlineTranslation(text: String): String? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urlStr =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=iw&dt=t&q=$encoded"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 4500
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONArray(body)
                val segments = root.optJSONArray(0) ?: return null
                val sb = StringBuilder()
                for (i in 0 until segments.length()) {
                    val part = segments.optJSONArray(i)
                    val piece = part?.optString(0)
                    if (!piece.isNullOrEmpty() && piece != "null") {
                        sb.append(piece)
                    }
                }
                sb.toString().trim().takeIf { it.isNotEmpty() }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun translateBatchOffline(items: List<String>): Map<String, String> = coroutineScope {
        if (!isModelReady) {
            val ready = prepareModel()
            if (ready.isFailure) return@coroutineScope emptyMap()
        }

        val client = getOrCreateClient()
        val out = ConcurrentHashMap<String, String>()
        items.map { item ->
            async(Dispatchers.Default) {
                try {
                    val translated = client.translate(item).await().trim()
                    if (translated.isNotEmpty()) {
                        out[item] = translated
                    }
                } catch (_: Exception) {
                    // Ignore single-item failure
                }
            }
        }.awaitAll()
        out
    }

    fun close() {
        translator?.close()
        translator = null
        isModelReady = false
    }
}
