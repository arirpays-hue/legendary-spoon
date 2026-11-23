package com.example.screentranslator

import android.content.Context
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ScreenTranslationEngine(context: Context) {

    private val appContext = context.applicationContext
    private val cacheLock = Any()
    private val cache = LruCache<String, String>(2500)
    private val modelMutex = Mutex()
    private val onlineSemaphore = Semaphore(4)

    private val translators = ConcurrentHashMap<String, Translator>()
    private val readyPairs = ConcurrentHashMap<String, Boolean>()

    private val loadedPacks = ConcurrentHashMap<String, Map<String, String>>()
    private val loadedPackTimestamps = ConcurrentHashMap<String, Long>()

    @Volatile
    private var isClosed = false

    private fun pairKey(source: LanguageOption, target: LanguageOption): String =
        "${source.code}->${target.code}"

    private fun cacheKey(source: LanguageOption, target: LanguageOption, text: String): String =
        "${source.code}->${target.code}|$text"

    private fun getCached(key: String): String? = synchronized(cacheLock) { cache.get(key) }

    private fun putCached(key: String, value: String) {
        synchronized(cacheLock) {
            cache.put(key, value)
        }
    }

    private fun getOrCreateClient(source: LanguageOption, target: LanguageOption): Translator? {
        if (isClosed) return null
        val key = pairKey(source, target)
        translators[key]?.let { return it }
        synchronized(translators) {
            if (isClosed) return null
            translators[key]?.let { return it }
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source.code)
                .setTargetLanguage(target.code)
                .build()
            return Translation.getClient(options).also { translators[key] = it }
        }
    }

    suspend fun getDownloadedLanguageCodes(): Set<String> = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext emptySet()
        CrashGuard.safeCall("TranslationEngine.getDownloaded", emptySet()) {
            RemoteModelManager.getInstance()
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .await()
                .map { it.language }
                .toSet()
        }
    }

    suspend fun isPairModelDownloaded(
        source: LanguageOption,
        target: LanguageOption
    ): Boolean = withContext(Dispatchers.IO) {
        if (isClosed || source.code == target.code) return@withContext false
        CrashGuard.safeCall("TranslationEngine.isPairReady", false) {
            val manager = RemoteModelManager.getInstance()
            val srcModel = TranslateRemoteModel.Builder(source.code).build()
            val tgtModel = TranslateRemoteModel.Builder(target.code).build()
            val srcReady = manager.isModelDownloaded(srcModel).await()
            val tgtReady = manager.isModelDownloaded(tgtModel).await()
            val bothReady = srcReady && tgtReady
            if (bothReady && !isClosed) {
                getOrCreateClient(source, target)
                readyPairs[pairKey(source, target)] = true
            }
            bothReady
        }
    }

    suspend fun prepareModel(
        source: LanguageOption,
        target: LanguageOption,
        onProgress: ((progress: Float, messageHe: String) -> Unit)? = null
    ): Result<Unit> = modelMutex.withLock {
        if (isClosed) return@withLock Result.failure(IllegalStateException("המנוע נסגר"))

        val validation = EdgeCasePolicy.validateLanguagePair(source, target)
        if (!validation.isValid) {
            return@withLock Result.failure(
                IllegalArgumentException(validation.errorHe ?: "זוג שפות לא תקין")
            )
        }

        val key = pairKey(source, target)
        if (readyPairs[key] == true && isPairModelDownloaded(source, target)) {
            onProgress?.invoke(1f, "המודל מאומת ומוכן לשימוש לא-מקוון")
            return@withLock Result.success(Unit)
        }

        if (!EdgeCasePolicy.hasEnoughStorage(appContext.filesDir, 35L * 1024L * 1024L)) {
            return@withLock Result.failure(
                IllegalStateException("אין מספיק שטח אחסון פנוי להורדת המודל (נדרשים לפחות 95MB)")
            )
        }

        return@withLock try {
            val client = getOrCreateClient(source, target)
                ?: return@withLock Result.failure(IllegalStateException("המנוע נסגר"))
            val conditions = DownloadConditions.Builder().build()

            coroutineScope {
                val ticker = launch {
                    var p = 0.08f
                    onProgress?.invoke(p, "מתחבר לשרת המודלים (ML Kit)...")
                    while (isActive && p < 0.88f) {
                        delay(260L)
                        p = (p + 0.04f).coerceAtMost(0.88f)
                        val msg = if (p < 0.45f) {
                            "מוריד חבילת ${source.nameHe} ← ${target.nameHe}..."
                        } else {
                            "מתקין ומאמת מודל לא-מקוון..."
                        }
                        onProgress?.invoke(p, msg)
                    }
                }

                try {
                    client.downloadModelIfNeeded(conditions).await()
                } finally {
                    ticker.cancel()
                }
            }

            readyPairs[key] = true
            onProgress?.invoke(1f, "המודל הורד בהצלחה ומוכן ללא אינטרנט")
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            readyPairs[key] = false
            CrashGuard.recordError("TranslationEngine.prepareModel", t)
            Result.failure(t)
        }
    }

    suspend fun deleteMlKitLanguage(langCode: String): Result<Unit> = modelMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val model = TranslateRemoteModel.Builder(langCode).build()
                RemoteModelManager.getInstance().deleteDownloadedModel(model).await()

                val toRemove = translators.keys.filter {
                    it.startsWith("$langCode->") || it.endsWith("->$langCode")
                }
                for (k in toRemove) {
                    readyPairs.remove(k)
                    CrashGuard.safeRun("TranslationEngine.closeRemoved") {
                        translators.remove(k)?.close()
                    }
                }
                Result.success(Unit)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                CrashGuard.recordError("TranslationEngine.deleteModel", t)
                Result.failure(t)
            }
        }
    }

    fun getOrLoadFastPack(source: LanguageOption, target: LanguageOption): Map<String, String> {
        if (isClosed) return emptyMap()
        val modelId = "fastpack_${source.code}_${target.code}"
        val file = SegmentedModelDownloader.packFileFor(appContext, modelId)
        // Strict 5MB limit to guarantee zero OutOfMemoryError when parsing into RAM
        if (!file.exists() || file.length() == 0L || file.length() > 5L * 1024L * 1024L) {
            loadedPacks.remove(modelId)
            loadedPackTimestamps.remove(modelId)
            return emptyMap()
        }

        val lastMod = file.lastModified()
        if (loadedPackTimestamps[modelId] == lastMod) {
            loadedPacks[modelId]?.let { return it }
        }

        val parsed = parseDictionaryPack(file)
        loadedPacks[modelId] = parsed
        loadedPackTimestamps[modelId] = lastMod
        return parsed
    }

    private fun parseDictionaryPack(file: File): Map<String, String> {
        val map = HashMap<String, String>()
        val maxEntries = 30_000
        CrashGuard.safeRun("TranslationEngine.parsePack") {
            val content = file.readText(Charsets.UTF_8).trim()
            if (content.isEmpty()) return@safeRun

            when {
                content.startsWith("{") -> {
                    val obj = JSONObject(content)
                    val keys = obj.keys()
                    while (keys.hasNext() && map.size < maxEntries) {
                        val k = keys.next().trim()
                        val v = obj.optString(k).trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) {
                            map[k.lowercase(Locale.ROOT)] = v
                        }
                    }
                }

                content.startsWith("[") -> {
                    val arr = JSONArray(content)
                    val limit = minOf(arr.length(), maxEntries)
                    for (i in 0 until limit) {
                        val item = arr.optJSONObject(i) ?: continue
                        val k = (item.optString("source").ifEmpty { item.optString("src") }).trim()
                        val v = (item.optString("target").ifEmpty { item.optString("dst") }).trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) {
                            map[k.lowercase(Locale.ROOT)] = v
                        }
                    }
                }

                else -> {
                    file.useLines(Charsets.UTF_8) { lines ->
                        for (line in lines) {
                            if (map.size >= maxEntries) break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                            val sepIdx = trimmed.indexOf('\t').takeIf { it > 0 }
                                ?: trimmed.indexOf('=').takeIf { it > 0 }
                                ?: continue
                            val k = trimmed.substring(0, sepIdx).trim()
                            val v = trimmed.substring(sepIdx + 1).trim()
                            if (k.isNotEmpty() && v.isNotEmpty()) {
                                map[k.lowercase(Locale.ROOT)] = v
                            }
                        }
                    }
                }
            }
        }
        return map
    }

    fun shouldSkipText(
        rawText: String,
        source: LanguageOption,
        target: LanguageOption,
        skipBrands: Boolean
    ): Boolean {
        return EdgeCasePolicy.shouldSkipSourceText(
            rawText = rawText,
            sourceLang = source,
            targetLang = target,
            skipBrands = skipBrands
        )
    }

    fun isValidResult(
        sourceText: String,
        translatedText: String,
        target: LanguageOption
    ): Boolean {
        return EdgeCasePolicy.isValidTranslationResult(sourceText, translatedText, target)
    }

    suspend fun translateBatch(
        texts: List<String>,
        source: LanguageOption = LanguageCatalog.defaultSource,
        target: LanguageOption = LanguageCatalog.defaultTarget,
        offlineOnly: Boolean = true
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (isClosed || source.code == target.code) return@withContext emptyMap()

        val uniqueInputs = texts
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (uniqueInputs.isEmpty()) return@withContext emptyMap()

        val fastPack = getOrLoadFastPack(source, target)
        val results = ConcurrentHashMap<String, String>()
        val uncachedOnline = ArrayList<String>()
        val uncachedOfflineOnly = ArrayList<String>()

        for (item in uniqueInputs) {
            val cKey = cacheKey(source, target, item)
            val cached = getCached(cKey)
            if (cached != null) {
                results[item] = cached
                continue
            }

            val packHit = fastPack[item.lowercase(Locale.ROOT)]
            if (!packHit.isNullOrBlank()) {
                results[item] = packHit
                putCached(cKey, packHit)
                continue
            }

            if (offlineOnly || EdgeCasePolicy.containsSensitiveData(item)) {
                uncachedOfflineOnly.add(item)
            } else {
                uncachedOnline.add(item)
            }
        }

        if (uncachedOnline.isNotEmpty()) {
            val onlineResolved = translateBatchOnline(uncachedOnline, source, target)
            for (item in uncachedOnline) {
                val tr = onlineResolved[item]
                if (!tr.isNullOrBlank()) {
                    results[item] = tr
                    putCached(cacheKey(source, target, item), tr)
                } else {
                    uncachedOfflineOnly.add(item)
                }
            }
        }

        if (uncachedOfflineOnly.isNotEmpty() && !isClosed) {
            val offlineResolved = translateBatchOffline(uncachedOfflineOnly, source, target)
            for ((src, tr) in offlineResolved) {
                if (tr.isNotBlank()) {
                    results[src] = tr
                    if (!EdgeCasePolicy.containsSensitiveData(src)) {
                        putCached(cacheKey(source, target, src), tr)
                    }
                }
            }
        }

        results
    }

    suspend fun translateText(
        text: String,
        source: LanguageOption = LanguageCatalog.defaultSource,
        target: LanguageOption = LanguageCatalog.defaultTarget,
        offlineOnly: Boolean = true
    ): String {
        val clean = text.replace('\n', ' ').trim()
        if (clean.isEmpty()) return ""
        return translateBatch(
            texts = listOf(clean),
            source = source,
            target = target,
            offlineOnly = offlineOnly
        )[clean] ?: ""
    }

    private suspend fun translateBatchOnline(
        items: List<String>,
        source: LanguageOption,
        target: LanguageOption
    ): Map<String, String> = coroutineScope {
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
                    val single = onlineSemaphore.withPermit {
                        fetchOnlineTranslation(chunk[0], source, target)
                    }
                    if (!single.isNullOrBlank()) out[chunk[0]] = single
                } else {
                    val joined = chunk.joinToString("\n")
                    val batched = onlineSemaphore.withPermit {
                        fetchOnlineTranslation(joined, source, target)
                    }
                    val lines = batched?.split('\n')?.map { it.trim() }

                    if (lines != null && lines.size == chunk.size) {
                        for (i in chunk.indices) {
                            if (lines[i].isNotEmpty()) out[chunk[i]] = lines[i]
                        }
                    } else {
                        coroutineScope {
                            chunk.map { item ->
                                async(Dispatchers.IO) {
                                    val single = onlineSemaphore.withPermit {
                                        fetchOnlineTranslation(item, source, target)
                                    }
                                    if (!single.isNullOrBlank()) out[item] = single
                                }
                            }.awaitAll()
                        }
                    }
                }
            }
        }.awaitAll()

        out
    }

    private fun fetchOnlineTranslation(
        text: String,
        source: LanguageOption,
        target: LanguageOption
    ): String? {
        return CrashGuard.safeCall("TranslationEngine.fetchOnline", null) {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val urlStr =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=${source.onlineCode}&tl=${target.onlineCode}&dt=t&q=$encoded"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 3000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@safeCall null
                val body = readLimitedResponse(conn, EdgeCasePolicy.MAX_ONLINE_RESPONSE_BYTES)
                    ?: return@safeCall null
                val root = JSONArray(body)
                val segments = root.optJSONArray(0) ?: return@safeCall null
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

    private suspend fun translateBatchOffline(
        items: List<String>,
        source: LanguageOption,
        target: LanguageOption
    ): Map<String, String> = coroutineScope {
        if (isClosed) return@coroutineScope emptyMap()
        val key = pairKey(source, target)
        if (readyPairs[key] != true) {
            val ready = prepareModel(source, target)
            if (ready.isFailure) return@coroutineScope emptyMap()
        }

        val client = getOrCreateClient(source, target) ?: return@coroutineScope emptyMap()
        val out = ConcurrentHashMap<String, String>()
        items.map { item ->
            async(Dispatchers.Default) {
                if (isClosed) return@async
                try {
                    val translated = client.translate(item).await().trim()
                    if (translated.isNotEmpty()) {
                        out[item] = translated
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    CrashGuard.recordError("TranslationEngine.offlineItem", t)
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
        isClosed = true
        clearCache()
        loadedPacks.clear()
        loadedPackTimestamps.clear()
        readyPairs.clear()
        synchronized(translators) {
            for (client in translators.values) {
                CrashGuard.safeRun("TranslationEngine.closeClient") {
                    client.close()
                }
            }
            translators.clear()
        }
    }
}
