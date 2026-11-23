package com.example.screentranslator

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.max

data class CapturedFrame(
    val bitmap: Bitmap,
    val scaleX: Float,
    val scaleY: Float
)

class ScreenCaptureHelper(
    private val context: Context,
    private val resultCode: Int,
    private val dataIntent: Intent,
    initialFullWidth: Int,
    initialFullHeight: Int,
    initialDensityDpi: Int,
    private val onProjectionStopped: () -> Unit = {}
) {
    private val targetScale = 0.5f
    private val captureLock = Any()

    @Volatile
    var captureWidth: Int = computeWidth(initialFullWidth)
        private set

    @Volatile
    var captureHeight: Int = computeHeight(initialFullHeight)
        private set

    @Volatile
    var scaleX: Float = initialFullWidth.coerceAtLeast(16).toFloat() / captureWidth.toFloat()
        private set

    @Volatile
    var scaleY: Float = initialFullHeight.coerceAtLeast(16).toFloat() / captureHeight.toFloat()
        private set

    @Volatile
    private var captureDpi: Int = max(120, (initialDensityDpi * targetScale).toInt())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var tightBytes: ByteArray? = null
    private var tightBuffer: ByteBuffer? = null
    private var latestBitmap: Bitmap? = null

    @Volatile
    private var lastFrameTimestamp: Long = 0L

    @Volatile
    private var frameVersion: Long = 0L

    @Volatile
    private var captureRequested: Boolean = true

    @Volatile
    private var isStopped: Boolean = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (isStopped) return
            isStopped = true
            releaseDisplay()
            CrashGuard.safeRun("MediaProjection.onStop") {
                onProjectionStopped()
            }
        }
    }

    private fun computeWidth(fullW: Int): Int =
        max(16, (fullW.coerceAtLeast(32) * targetScale).toInt() and 15.inv())

    private fun computeHeight(fullH: Int): Int =
        max(16, (fullH.coerceAtLeast(32) * targetScale).toInt() and 1.inv())

    fun getFrameVersion(): Long = frameVersion

    fun isActive(): Boolean = !isStopped && mediaProjection != null

    fun start(): Boolean {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return false

        val projection = CrashGuard.safeCall("ScreenCapture.getMediaProjection", null) {
            manager.getMediaProjection(resultCode, dataIntent)
        } ?: return false

        val thread = HandlerThread("ScreenCaptureThread").apply { start() }
        val handler = Handler(thread.looper)

        synchronized(captureLock) {
            isStopped = false
            captureThread = thread
            captureHandler = handler
            mediaProjection = projection
        }

        return try {
            projection.registerCallback(projectionCallback, handler)
            synchronized(captureLock) {
                val reader = createReaderLocked(captureWidth, captureHeight, handler)
                imageReader = reader
                virtualDisplay = projection.createVirtualDisplay(
                    "ScreenTranslatorDisplay",
                    captureWidth,
                    captureHeight,
                    captureDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    handler
                )
            }
            virtualDisplay != null
        } catch (t: Throwable) {
            CrashGuard.recordError("ScreenCapture.start", t)
            stop()
            false
        }
    }

    fun resize(newFullWidth: Int, newFullHeight: Int, newDensityDpi: Int) {
        if (isStopped || newFullWidth <= 16 || newFullHeight <= 16) return

        val handler = captureHandler ?: return
        val newW = computeWidth(newFullWidth)
        val newH = computeHeight(newFullHeight)
        val newDpi = max(120, (newDensityDpi * targetScale).toInt())

        synchronized(captureLock) {
            if (isStopped) return
            if (newW == captureWidth && newH == captureHeight) return

            captureWidth = newW
            captureHeight = newH
            scaleX = newFullWidth.toFloat() / newW.toFloat()
            scaleY = newFullHeight.toFloat() / newH.toFloat()
            captureDpi = newDpi

            val oldReader = imageReader
            val newReader = CrashGuard.safeCall("ScreenCapture.resizeReader", null) {
                createReaderLocked(newW, newH, handler)
            } ?: return
            imageReader = newReader

            latestBitmap?.let { if (!it.isRecycled) it.recycle() }
            latestBitmap = null
            tightBytes = null
            tightBuffer = null
            captureRequested = true

            CrashGuard.safeRun("ScreenCapture.resizeDisplay") {
                virtualDisplay?.resize(newW, newH, newDpi)
                virtualDisplay?.surface = newReader.surface
            }
            CrashGuard.safeRun("ScreenCapture.closeOldReader") {
                oldReader?.close()
            }
        }
    }

    private fun createReaderLocked(w: Int, h: Int, handler: Handler): ImageReader {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ ir ->
            synchronized(captureLock) {
                if (isStopped || ir !== imageReader) return@setOnImageAvailableListener
                val image = try {
                    ir.acquireLatestImage()
                } catch (_: Throwable) {
                    null
                } ?: return@setOnImageAvailableListener

                try {
                    val now = System.currentTimeMillis()
                    val shouldDecode = captureRequested || latestBitmap == null || (now - lastFrameTimestamp >= 180L)
                    if (!shouldDecode) return@setOnImageAvailableListener

                    val planes = image.planes
                    if (planes.isEmpty()) return@setOnImageAvailableListener
                    val plane = planes[0]
                    val buffer = plane.buffer ?: return@setOnImageAvailableListener
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    if (pixelStride != 4 || rowStride < w * pixelStride) return@setOnImageAvailableListener

                    var dest = latestBitmap
                    if (dest == null || dest.isRecycled || dest.width != w || dest.height != h) {
                        dest?.let { if (!it.isRecycled) it.recycle() }
                        dest = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        latestBitmap = dest
                    }

                    val rowBytes = w * pixelStride
                    val expectedTightSize = rowBytes * h
                    buffer.rewind()

                    if (rowStride == rowBytes && buffer.remaining() >= expectedTightSize) {
                        dest.copyPixelsFromBuffer(buffer)
                    } else {
                        var bytes = tightBytes
                        var wrapped = tightBuffer
                        if (bytes == null || bytes.size != expectedTightSize || wrapped == null) {
                            bytes = ByteArray(expectedTightSize)
                            wrapped = ByteBuffer.wrap(bytes)
                            tightBytes = bytes
                            tightBuffer = wrapped
                        }
                        val limit = buffer.limit()
                        for (row in 0 until h) {
                            val srcOffset = row * rowStride
                            if (srcOffset < 0 || srcOffset + rowBytes > limit) {
                                return@setOnImageAvailableListener
                            }
                            buffer.position(srcOffset)
                            buffer.get(bytes, row * rowBytes, rowBytes)
                        }
                        wrapped.rewind()
                        dest.copyPixelsFromBuffer(wrapped)
                    }

                    lastFrameTimestamp = now
                    frameVersion++
                    captureRequested = false
                } catch (t: Throwable) {
                    CrashGuard.recordError("ScreenCapture.onImageAvailable", t)
                } finally {
                    try {
                        image.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }, handler)
        return reader
    }

    suspend fun captureFrame(waitForFresh: Boolean = true): CapturedFrame? = withContext(Dispatchers.Default) {
        if (isStopped) return@withContext null

        val startRequestTime = System.currentTimeMillis()
        if (waitForFresh) {
            captureRequested = true
            repeat(6) {
                if (isStopped) return@withContext null
                val fresh = tryCopyLatestLocked(requireTimestampAtLeast = startRequestTime)
                if (fresh != null) return@withContext fresh
                delay(35L)
            }
        }

        if (isStopped) null else tryCopyLatestLocked(requireTimestampAtLeast = 0L)
    }

    private fun tryCopyLatestLocked(requireTimestampAtLeast: Long): CapturedFrame? {
        return synchronized(captureLock) {
            if (isStopped) return@synchronized null
            val bmp = latestBitmap ?: return@synchronized null
            if (bmp.isRecycled || lastFrameTimestamp < requireTimestampAtLeast) return@synchronized null
            try {
                val copy = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return@synchronized null
                CapturedFrame(bitmap = copy, scaleX = scaleX, scaleY = scaleY)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                CrashGuard.recordError("ScreenCapture.copyFrame", t)
                null
            }
        }
    }

    private fun releaseDisplay() {
        synchronized(captureLock) {
            CrashGuard.safeRun("ScreenCapture.releaseVD") { virtualDisplay?.release() }
            virtualDisplay = null
            CrashGuard.safeRun("ScreenCapture.closeReader") { imageReader?.close() }
            imageReader = null
            tightBytes = null
            tightBuffer = null
            latestBitmap?.let { if (!it.isRecycled) it.recycle() }
            latestBitmap = null
        }
    }

    fun stop() {
        isStopped = true
        val proj = mediaProjection
        mediaProjection = null
        CrashGuard.safeRun("ScreenCapture.unregisterCallback") {
            proj?.unregisterCallback(projectionCallback)
        }
        CrashGuard.safeRun("ScreenCapture.stopProjection") {
            proj?.stop()
        }
        releaseDisplay()
        CrashGuard.safeRun("ScreenCapture.quitThread") {
            captureThread?.quitSafely()
        }
        captureThread = null
        captureHandler = null
    }
}
