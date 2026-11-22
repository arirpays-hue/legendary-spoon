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

    private val batchSeparator = "998877"
    private val batchSplitRegex = Regex("\\s*998877\\s*")
    private val hebrewRegex = Regex("[\\u0590-\\u05FF]")
    private val camelCaseIdentifierRegex = Regex("^[a-z]+[A-Z][A-Za-z0-9]*$|^[A-Z][a-z]+[A-Z][A-Za-z0-9]*$")
    private val codeOrPathRegex = Regex(".*([/\\\\]|://|\\.kt\\b|\\.xml\\b|\\.yml\\b|\\.json\\b|\\.gradle\\b|\\.apk\\b|\\.png\\b).*")

    private val skippedBrands = setOf(
        "whatsapp", "spotify", "waze", "gemini", "brave", "bit", "firefox", "chrome",
        "youtube", "instagram", "telegram", "tiktok", "facebook", "netflix", "github",
        "android", "google", "google keep", "google play", "play store", "angry birds",
        "angry birds 2", "angry birds reloaded", "bloons td 6", "capcut", "metrolist",
        "ashell", "aida64", "shizuku", "termux", "zarchiver", "moovit", "egged", "canva",
        "discord", "reddit", "snapchat", "gmail", "google maps", "google drive", "steam",
        "nintendo", "switch", "xbox", "playstation", "aliexpress", "amazon", "ebay",
        "wolt", "uber", "chatgpt", "claude", "copilot", "nova launcher", "mt manager",
        "1dm", "1dm+", "mi remote", "wi-fi", "wifi", "bluetooth", "gps", "nfc", "volte",
        "4g", "5g", "lte", "ok", "usb", "adb", "apk", "pdf", "vpn"
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

    private fun persistCacheAsync() {
        runCatching {
            val snapshot = if (cache.size > 3500) {
                cache.entries.take(3500)
            } else {
                cache.entries
            }
            val json = JSONObject()
            for ((k, v) in snapshot) {
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

        // Already contains Hebrew characters
        if (hebrewRegex.containsMatchIn(text)) return true

        // Code paths, URLs, filenames, or single-token camelCase identifiers
        if (codeOrPathRegex.matches(text)) return true
        if (!text.contains(' ') && camelCaseIdentifierRegex.matches(text)) return true

        // Truncated launcher labels like "Angry Birds Reloa.."
        val wordCount = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        if ((text.endsWith("..") || text.endsWith("…")) && wordCount <= 3) return true

        // Require mostly English letters (filters out "4GVoLTE", "1DM+", "AIDA64", "27%", symbols)
        val nonSpaceCount = text.count { !it.isWhitespace() }
        val englishLetters = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val digits = text.count { it.isDigit() }
        if (englishLetters < 2) return true
        if (nonSpaceCount > 0 && englishLetters.toFloat() / nonSpaceCount < 0.62f) return true
        if (wordCount == 1 && digits > 0) return true

        // Skip single 2-letter fragments except a few real UI words
        val lower = text.lowercase(Locale.US).trim('.', ',', ':', '!', '?', '"', '\'', '(', ')')
        if (lower.length == 2 && lower !in setOf("no", "on", "go", "up", "in", "by", "to", "or", "is", "me", "my")) {
            return true
        }

        if (skipBrands) {
            if (lower in skippedBrands) return true
            if (lower.startsWith("angry birds") || lower.startsWith("google ") && wordCount <= 2) {
                return true
            }
        }

        return false
    }

    suspend fun translateBatch(
        texts: List<String>,
        offlineOnly: Boolean = false
    ): Map<String, String> = withContext(Dispatchers.IO) {
        ensureCacheLoaded()

        val uniqueInputs = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
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

        if (uncached.isEmpty()) {
            return@withContext results
        }

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
            persistCacheAsync()
        }

        results
    }

    suspend fun translateEnglishToHebrew(
        text: String,
        offlineOnly: Boolean = false
    ): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        return translateBatch(listOf(clean), offlineOnly = offlineOnly)[clean] ?: ""
    }

    private suspend fun translateBatchOnline(items: List<String>): Map<String, String> = coroutineScope {
        val out = ConcurrentHashMap<String, String>()

        // Split into safe chunks under ~850 chars so GET stays fast and reliable
        val chunks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var currentLen = 0
        for (item in items) {
            val safeItem = item.replace(batchSeparator, "")
            if (current.isNotEmpty() && (current.size >= 10 || currentLen + safeItem.length > 850)) {
                chunks.add(current)
                current = ArrayList()
                currentLen = 0
            }
            current.add(item)
            currentLen += safeItem.length + 10
        }
        if (current.isNotEmpty()) {
            chunks.add(current)
        }

        val jobs = chunks.map { chunk ->
            async(Dispatchers.IO) {
                if (chunk.size == 1) {
                    val single = fetchOnlineTranslation(chunk[0])
                    if (!single.isNullOrBlank()) {
                        out[chunk[0]] = single
                    }
                } else {
                    val canBatch = chunk.none { it.contains(batchSeparator) }
                    val joined = if (canBatch) chunk.joinToString("\n$batchSeparator\n") else null
                    val batched = joined?.let { fetchOnlineTranslation(it) }
                    val split = batched?.split(batchSplitRegex)?.map { it.trim() }

                    if (split != null && split.size == chunk.size) {
                        for (i in chunk.indices) {
                            if (split[i].isNotEmpty()) {
                                out[chunk[i]] = split[i]
                            }
                        }
                    } else {
                        // Parallel single-item fallback for this chunk
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
        }
        jobs.awaitAll()
        out
    }

    private fun fetchOnlineTranslation(text: String): String? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urlStr =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=iw&dt=t&q=$encoded"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1600
                readTimeout = 2000
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

    fun clearCache() {
        cache.clear()
        runCatching { cacheFile.delete() }
    }

    fun close() {
        translator?.close()
        translator = null
        isModelReady = false
    }
}
