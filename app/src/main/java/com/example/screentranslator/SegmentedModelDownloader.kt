package com.example.screentranslator

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

typealias DownloadProgressMap = Map<String, DownloadProgressState>

enum class DownloadStatus {
    IDLE,
    PROBING,
    DOWNLOADING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadProgressState(
    val modelId: String = "",
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val totalParts: Int = EdgeCasePolicy.TARGET_SEGMENT_COUNT,
    val completedParts: Int = 0,
    val activeParts: Int = 0,
    val segmentProgress: List<Float> = List(EdgeCasePolicy.TARGET_SEGMENT_COUNT) { 0f },
    val isSegmented: Boolean = false,
    val messageHe: String = ""
)

class SegmentedModelDownloader(context: Context) {

    private val modelsDir = File(context.applicationContext.filesDir, "offline_packs").apply {
        if (!exists()) mkdirs()
    }

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val _progressMap: MutableStateFlow<DownloadProgressMap> = MutableStateFlow(emptyMap())
    val progressMap: StateFlow<DownloadProgressMap> = _progressMap.asStateFlow()

    fun getPackFile(modelId: String): File {
        val safeName = modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(modelsDir, "$safeName.pack")
    }

    fun isPackDownloaded(modelId: String): Boolean {
        val f = getPackFile(modelId)
        return f.exists() && f.length() > 0L
    }

    fun deletePack(modelId: String): Boolean {
        cancel(modelId)
        val file = getPackFile(modelId)
        val temp = File(modelsDir, "${file.name}.part")
        temp.delete()
        val deleted = file.delete()
        updateState(modelId, DownloadProgressState(modelId = modelId, status = DownloadStatus.IDLE))
        return deleted
    }

    fun cancel(modelId: String) {
        activeJobs.remove(modelId)?.cancel()
    }

    fun setExternalState(modelId: String, state: DownloadProgressState) {
        updateState(modelId, state)
    }

    private fun updateState(modelId: String, state: DownloadProgressState) {
        val updated = HashMap<String, DownloadProgressState>(_progressMap.value)
        updated[modelId] = state
        _progressMap.value = updated
    }

    /**
     * הורדת קובץ מודל/חבילה מקישור HTTPS ישיר עם פיצול אוטומטי ל-60 חלקים מקבילים
     * כאשר השרת תומך ב-HTTP Range.
     */
    suspend fun downloadSegmented(
        modelId: String,
        urlString: String,
        requestedParts: Int = EdgeCasePolicy.TARGET_SEGMENT_COUNT
    ): Result<File> = withContext(Dispatchers.IO) {
        val validated = EdgeCasePolicy.validateDownloadUrl(urlString)
        if (validated.isFailure) {
            val err = validated.exceptionOrNull() ?: IllegalArgumentException("קישור לא תקין")
            updateState(
                modelId,
                DownloadProgressState(
                    modelId = modelId,
                    status = DownloadStatus.FAILED,
                    messageHe = err.message ?: "כתובת ההורדה אינה תקינה"
                )
            )
            return@withContext Result.failure(err)
        }

        val safeUrl = validated.getOrThrow()
        val finalFile = getPackFile(modelId)
        val tempFile = File(modelsDir, "${finalFile.name}.part")
        val currentJob = coroutineContext[Job]
        if (currentJob != null) {
            activeJobs[modelId]?.cancel()
            activeJobs[modelId] = currentJob
        }

        updateState(
            modelId,
            DownloadProgressState(
                modelId = modelId,
                status = DownloadStatus.PROBING,
                totalParts = requestedParts,
                messageHe = "בודק תמיכת שרת בפיצול ל-$requestedParts חלקים..."
            )
        )

        try {
            val probe = probeRemoteFile(safeUrl)
            if (!EdgeCasePolicy.hasEnoughStorage(modelsDir, probe.contentLength)) {
                val err = IllegalStateException("אין מספיק שטח אחסון פנוי במכשיר")
                updateState(
                    modelId,
                    DownloadProgressState(
                        modelId = modelId,
                        status = DownloadStatus.FAILED,
                        messageHe = err.message ?: "אין מקום פנוי"
                    )
                )
                return@withContext Result.failure(err)
            }

            val strategy = EdgeCasePolicy.decideDownloadStrategy(
                contentLength = probe.contentLength,
                acceptsRanges = probe.acceptsRanges,
                requestedParts = requestedParts
            )

            if (tempFile.exists()) tempFile.delete()

            if (strategy.useSegmentedRange && probe.contentLength > 0L) {
                execute60PartDownload(
                    modelId = modelId,
                    urlString = safeUrl,
                    totalBytes = probe.contentLength,
                    partsCount = strategy.partsCount,
                    maxSockets = strategy.maxConcurrentSockets,
                    tempFile = tempFile,
                    strategyMessage = strategy.reasonHe
                )
            } else {
                executeSingleStreamDownload(
                    modelId = modelId,
                    urlString = safeUrl,
                    knownTotalBytes = probe.contentLength,
                    tempFile = tempFile,
                    strategyMessage = strategy.reasonHe
                )
            }

            val currentSnapshot = _progressMap.value[modelId] ?: DownloadProgressState(modelId = modelId)
            updateState(
                modelId,
                currentSnapshot.copy(
                    status = DownloadStatus.VERIFYING,
                    progress = 0.99f,
                    messageHe = "מאמת שלמות קובץ..."
                )
            )

            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IllegalStateException("הקובץ שהורד ריק או פגום")
            }
            if (probe.contentLength > 0L && tempFile.length() != probe.contentLength) {
                throw IllegalStateException("גודל הקובץ לא תואם — ההורדה נקטעה")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            val doneParts = if (strategy.useSegmentedRange) strategy.partsCount else 1
            updateState(
                modelId,
                DownloadProgressState(
                    modelId = modelId,
                    status = DownloadStatus.COMPLETED,
                    progress = 1f,
                    downloadedBytes = finalFile.length(),
                    totalBytes = finalFile.length(),
                    speedBytesPerSec = 0L,
                    totalParts = doneParts,
                    completedParts = doneParts,
                    activeParts = 0,
                    segmentProgress = List(doneParts) { 1f },
                    isSegmented = strategy.useSegmentedRange,
                    messageHe = "ההורדה הושלמה בהצלחה"
                )
            )
            Result.success(finalFile)
        } catch (ce: CancellationException) {
            tempFile.delete()
            updateState(
                modelId,
                DownloadProgressState(
                    modelId = modelId,
                    status = DownloadStatus.CANCELLED,
                    messageHe = "ההורדה בוטלה"
                )
            )
            throw ce
        } catch (e: Exception) {
            tempFile.delete()
            updateState(
                modelId,
                DownloadProgressState(
                    modelId = modelId,
                    status = DownloadStatus.FAILED,
                    messageHe = e.localizedMessage ?: "שגיאה בהורדת המודל"
                )
            )
            Result.failure(e)
        } finally {
            activeJobs.remove(modelId)
        }
    }

    private data class RemoteProbe(
        val contentLength: Long,
        val acceptsRanges: Boolean
    )

    private fun probeRemoteFile(urlString: String): RemoteProbe {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("User-Agent", "ScreenTranslator/1.4")
        }
        return try {
            val code = conn.responseCode
            val contentRange = conn.getHeaderField("Content-Range")
            val acceptRanges = conn.getHeaderField("Accept-Ranges")
            val parsedFromRange = contentRange?.substringAfterLast("/")?.toLongOrNull() ?: -1L
            val length = if (parsedFromRange > 0L) parsedFromRange else conn.contentLengthLong
            val supportsRange = (code == HttpURLConnection.HTTP_PARTIAL) ||
                acceptRanges.equals("bytes", ignoreCase = true)
            RemoteProbe(contentLength = length, acceptsRanges = supportsRange && length > 0L)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun execute60PartDownload(
        modelId: String,
        urlString: String,
        totalBytes: Long,
        partsCount: Int,
        maxSockets: Int,
        tempFile: File,
        strategyMessage: String
    ) = coroutineScope {
        val partProgressArray = FloatArray(partsCount)
        val totalDownloaded = AtomicLong(0L)
        val completedParts = AtomicInteger(0)
        val activeParts = AtomicInteger(0)
        val lastEmitMs = AtomicLong(0L)
        val startMs = System.currentTimeMillis()
        val semaphore = Semaphore(maxSockets)

        val executor = Executors.newFixedThreadPool(maxSockets)
        val poolDispatcher = executor.asCoroutineDispatcher()

        fun emitSnapshot(force: Boolean = false) {
            val now = System.currentTimeMillis()
            val prev = lastEmitMs.get()
            if (!force && (now - prev) < 90L) return
            if (!force && !lastEmitMs.compareAndSet(prev, now)) return

            val downloaded = totalDownloaded.get().coerceAtMost(totalBytes)
            val elapsedSec = ((now - startMs).coerceAtLeast(100L)) / 1000.0
            val speed = (downloaded / elapsedSec).toLong()
            val snapshot = synchronized(partProgressArray) { partProgressArray.toList() }

            updateState(
                modelId,
                DownloadProgressState(
                    modelId = modelId,
                    status = DownloadStatus.DOWNLOADING,
                    progress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.98f),
                    downloadedBytes = downloaded,
                    totalBytes = totalBytes,
                    speedBytesPerSec = speed,
                    totalParts = partsCount,
                    completedParts = completedParts.get(),
                    activeParts = activeParts.get(),
                    segmentProgress = snapshot,
                    isSegmented = true,
                    messageHe = "$strategyMessage (${completedParts.get()}/$partsCount)"
                )
            )
        }

        try {
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.setLength(totalBytes)
                val channel = raf.channel
                val chunkSize = totalBytes / partsCount

                val tasks = (0 until partsCount).map { partIndex ->
                    val startByte = partIndex * chunkSize
                    val endByte = if (partIndex == partsCount - 1) {
                        totalBytes - 1L
                    } else {
                        ((partIndex + 1) * chunkSize) - 1L
                    }
                    val partLength = (endByte - startByte + 1L).coerceAtLeast(1L)

                    async(poolDispatcher) {
                        semaphore.withPermit {
                            activeParts.incrementAndGet()
                            try {
                                var partDownloaded = 0L
                                var attempt = 0
                                var success = false
                                var lastError: Exception? = null

                                while (attempt <= EdgeCasePolicy.MAX_RETRIES_PER_SEGMENT && !success) {
                                    if (!isActive) throw CancellationException()
                                    val resumeStart = startByte + partDownloaded
                                    if (resumeStart > endByte) {
                                        success = true
                                        break
                                    }

                                    val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                                        requestMethod = "GET"
                                        instanceFollowRedirects = true
                                        connectTimeout = 10000
                                        readTimeout = 15000
                                        setRequestProperty("Range", "bytes=$resumeStart-$endByte")
                                        setRequestProperty("User-Agent", "ScreenTranslator/1.4")
                                    }

                                    var httpCode = -1
                                    try {
                                        httpCode = conn.responseCode
                                        if (httpCode != HttpURLConnection.HTTP_PARTIAL &&
                                            httpCode != HttpURLConnection.HTTP_OK
                                        ) {
                                            throw IllegalStateException("HTTP $httpCode בחלק $partIndex")
                                        }

                                        val buf = ByteArray(16 * 1024)
                                        var writeOffset = resumeStart
                                        val remainingForPart = endByte - resumeStart + 1L
                                        var readInAttempt = 0L

                                        conn.inputStream.use { input ->
                                            while (readInAttempt < remainingForPart) {
                                                if (!isActive) throw CancellationException()
                                                val maxToRead = minOf(
                                                    buf.size.toLong(),
                                                    remainingForPart - readInAttempt
                                                ).toInt()
                                                val read = input.read(buf, 0, maxToRead)
                                                if (read <= 0) break

                                                val bb = ByteBuffer.wrap(buf, 0, read)
                                                while (bb.hasRemaining()) {
                                                    writeOffset += channel.write(bb, writeOffset)
                                                }

                                                readInAttempt += read
                                                partDownloaded += read
                                                totalDownloaded.addAndGet(read.toLong())

                                                synchronized(partProgressArray) {
                                                    partProgressArray[partIndex] =
                                                        (partDownloaded.toFloat() / partLength.toFloat())
                                                            .coerceIn(0f, 1f)
                                                }
                                                emitSnapshot(false)
                                            }
                                        }

                                        if (partDownloaded >= partLength) {
                                            success = true
                                        } else {
                                            throw IllegalStateException("חלק $partIndex נקטע לפני הסיום")
                                        }
                                    } catch (ce: CancellationException) {
                                        throw ce
                                    } catch (e: Exception) {
                                        lastError = e
                                        attempt++
                                        if (attempt <= EdgeCasePolicy.MAX_RETRIES_PER_SEGMENT) {
                                            delay(EdgeCasePolicy.computeRetryBackoffMs(attempt, httpCode))
                                        }
                                    } finally {
                                        conn.disconnect()
                                    }
                                }

                                if (!success) {
                                    throw lastError ?: IllegalStateException("הורדת חלק $partIndex נכשלה")
                                }

                                synchronized(partProgressArray) {
                                    partProgressArray[partIndex] = 1f
                                }
                                completedParts.incrementAndGet()
                                emitSnapshot(true)
                            } finally {
                                activeParts.decrementAndGet()
                            }
                        }
                    }
                }

                tasks.awaitAll()
                channel.force(true)
            }
        } finally {
            poolDispatcher.close()
            executor.shutdownNow()
        }
    }

    private suspend fun executeSingleStreamDownload(
        modelId: String,
        urlString: String,
        knownTotalBytes: Long,
        tempFile: File,
        strategyMessage: String
    ) = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        var lastEmit = 0L
        var downloaded = 0L

        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("User-Agent", "ScreenTranslator/1.4")
        }

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("שגיאת שרת (HTTP $code)")
            }
            val total = if (knownTotalBytes > 0L) knownTotalBytes else conn.contentLengthLong

            tempFile.outputStream().use { output ->
                val buf = ByteArray(16 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        if (!isActive) throw CancellationException()
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 100L) {
                            lastEmit = now
                            val elapsedSec = ((now - startMs).coerceAtLeast(100L)) / 1000.0
                            val speed = (downloaded / elapsedSec).toLong()
                            val progress = if (total > 0L) {
                                (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 0.98f)
                            } else {
                                0.5f
                            }
                            updateState(
                                modelId,
                                DownloadProgressState(
                                    modelId = modelId,
                                    status = DownloadStatus.DOWNLOADING,
                                    progress = progress,
                                    downloadedBytes = downloaded,
                                    totalBytes = total.coerceAtLeast(0L),
                                    speedBytesPerSec = speed,
                                    totalParts = 1,
                                    completedParts = 0,
                                    activeParts = 1,
                                    segmentProgress = listOf(progress),
                                    isSegmented = false,
                                    messageHe = strategyMessage
                                )
                            )
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
