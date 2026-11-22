package com.example.screentranslator

import android.util.LruCache
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class OfflineEnHeTranslator {

    private val cacheLock = Any()
    private val cache = LruCache<String, String>(2000)
    private val modelMutex = Mutex()

    @Volatile
    private var translator: Translator? = null

    @Volatile
    private var isModelReady = false

    private val englishWordRegex = Regex("[A-Za-z]{2,}")
    private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val cardNumberRegex = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
    private val sensitiveKeywordWithCodeRegex = Regex(
        "(?i)\\b(otp|pin|cvv|password|passcode|token|secret)\\b.*\\b\\d{4,8}\\b|\\b\\d{4,8}\\b.*\\b(otp|pin|cvv|code)\\b"
    )
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

    private fun getCached(key: String): String? = synchronized(cacheLock) { cache.get(key) }

    private fun putCached(key: String, value: String) {
        synchronized(cacheLock) {
            cache.put(key, value)
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

    suspend fun prepareModel(): Result<Unit> = modelMutex.withLock {
        if (isModelReady) return@withLock Result.success(Unit)
        return@withLock try {
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

    private fun containsSensitiveData(text: String): Boolean {
        return emailRegex.containsMatchIn(text) ||
            cardNumberRegex.containsMatchIn(text) ||
            sensitiveKeywordWithCodeRegex.containsMatchIn(text)
    }

    suspend fun translateBatch(
        texts: List<String>,
        offlineOnly: Boolean = true
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val uniqueInputs = texts
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (uniqueInputs.isEmpty()) return@withContext emptyMap()

        val results = ConcurrentHashMap<String, String>()
        val uncachedOnline = ArrayList<String>()
        val uncachedOfflineOnly = ArrayList<String>()

        for (item in uniqueInputs) {
            val cached = getCached(item)
            if (cached != null) {
                results[item] = cached
            } else if (offlineOnly || containsSensitiveData(item)) {
                uncachedOfflineOnly.add(item)
            } else {
                uncachedOnline.add(item)
            }
        }

        if (uncachedOnline.isNotEmpty()) {
            val onlineResolved = translateBatchOnline(uncachedOnline)
            for (item in uncachedOnline) {
                val tr = onlineResolved[item]
                if (!tr.isNullOrBlank()) {
                    results[item] = tr
                    putCached(item, tr)
                } else {
                    uncachedOfflineOnly.add(item)
                }
            }
        }

        if (uncachedOfflineOnly.isNotEmpty()) {
            val offlineResolved = translateBatchOffline(uncachedOfflineOnly)
            for ((src, tr) in offlineResolved) {
                if (tr.isNotBlank()) {
                    results[src] = tr
                    if (!containsSensitiveData(src)) {
                        putCached(src, tr)
                    }
                }
            }
        }

        results
    }

    suspend fun translateEnglishToHebrew(
        text: String,
        offlineOnly: Boolean = true
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
                instanceFollowRedirects = false
                connectTimeout = 3000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val body = readLimitedResponse(conn, maxBytes = 65_536) ?: return null
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

    private fun readLimitedResponse(conn: HttpURLConnection, maxBytes: Int): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        conn.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buffer, 0, read)
            }
        }
        return out.toString(Charsets.UTF_8.name())
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
        synchronized(cacheLock) {
            cache.evictAll()
        }
    }

    fun close() {
        clearCache()
        runCatching { translator?.close() }
        translator = null
        isModelReady = false
    }
}
