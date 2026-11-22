package com.example.screentranslator

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

class ScreenCaptureHelper(
    private val context: Context,
    private val resultCode: Int,
    private val dataIntent: Intent,
    val fullWidth: Int,
    val fullHeight: Int,
    densityDpi: Int
) {
    private val targetScale = 0.5f
    val captureWidth: Int = max(16, (fullWidth * targetScale).toInt() and 15.inv())
    val captureHeight: Int = max(16, (fullHeight * targetScale).toInt() and 1.inv())
    val scaleX: Float = fullWidth.toFloat() / captureWidth.toFloat()
    val scaleY: Float = fullHeight.toFloat() / captureHeight.toFloat()
    private val captureDpi: Int = max(120, (densityDpi * targetScale).toInt())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val frameLock = Any()
    private var reusableRawBitmap: Bitmap? = null
    private var latestBitmap: Bitmap? = null
    private var latestCanvas: Canvas? = null

    @Volatile
    private var lastFrameTimestamp: Long = 0L
    @Volatile
    private var frameVersion: Long = 0L
    @Volatile
    private var captureRequested: Boolean = true

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseDisplay()
        }
    }

    fun getFrameVersion(): Long = frameVersion

    fun start(): Boolean {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, dataIntent) ?: return false

        val thread = HandlerThread("ScreenCaptureThread").apply { start() }
        val handler = Handler(thread.looper)
        captureThread = thread
        captureHandler = handler

        projection.registerCallback(projectionCallback, handler)
        mediaProjection = projection

        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                val shouldDecode = captureRequested || latestBitmap == null || (now - lastFrameTimestamp >= 180L)
                if (!shouldDecode) return@setOnImageAvailableListener

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                if (pixelStride <= 0) return@setOnImageAvailableListener

                val strideWidth = rowStride / pixelStride
                var raw = reusableRawBitmap
                if (raw == null || raw.isRecycled || raw.width != strideWidth || raw.height != captureHeight) {
                    raw?.recycle()
                    raw = Bitmap.createBitmap(strideWidth, captureHeight, Bitmap.Config.ARGB_8888)
                    reusableRawBitmap = raw
                }

                raw.copyPixelsFromBuffer(buffer)

                synchronized(frameLock) {
                    var dest = latestBitmap
                    if (dest == null || dest.isRecycled || dest.width != captureWidth || dest.height != captureHeight) {
                        dest?.recycle()
                        dest = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
                        latestBitmap = dest
                        latestCanvas = Canvas(dest)
                    }
                    latestCanvas?.drawBitmap(raw, 0f, 0f, null)
                    lastFrameTimestamp = now
                    frameVersion++
                    captureRequested = false
                }
            } catch (_: Exception) {
                // Ignore transient buffer errors on display resize/rotation
            } finally {
                image.close()
            }
        }, handler)
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
        return true
    }

    suspend fun captureFrame(waitForFresh: Boolean = true): Bitmap? = withContext(Dispatchers.Default) {
        val startRequestTime = System.currentTimeMillis()
        if (waitForFresh) {
            captureRequested = true
            repeat(6) {
                synchronized(frameLock) {
                    val bmp = latestBitmap
                    if (bmp != null && !bmp.isRecycled && lastFrameTimestamp >= startRequestTime) {
                        return@withContext bmp.copy(Bitmap.Config.ARGB_8888, false)
                    }
                }
                delay(35L)
            }
        }

        synchronized(frameLock) {
            val bmp = latestBitmap
            if (bmp != null && !bmp.isRecycled) {
                bmp.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                null
            }
        }
    }

    private fun releaseDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        synchronized(frameLock) {
            latestCanvas = null
            latestBitmap?.recycle()
            latestBitmap = null
        }
        reusableRawBitmap?.recycle()
        reusableRawBitmap = null
    }

    fun stop() {
        releaseDisplay()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }
}
