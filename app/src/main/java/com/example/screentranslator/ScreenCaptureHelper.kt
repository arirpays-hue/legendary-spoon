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
import kotlinx.coroutines.delay

class ScreenCaptureHelper(
    private val context: Context,
    private val resultCode: Int,
    private val dataIntent: Intent,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val frameLock = Any()
    private var latestBitmap: Bitmap? = null
    @Volatile
    private var lastFrameTimestamp: Long = 0L
    @Volatile
    private var requestFreshFrame: Boolean = true

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseDisplay()
        }
    }

    fun start(): Boolean {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, dataIntent) ?: return false

        val thread = HandlerThread("ScreenCaptureThread").apply { start() }
        val handler = Handler(thread.looper)
        captureThread = thread
        captureHandler = handler

        projection.registerCallback(projectionCallback, handler)
        mediaProjection = projection

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                val shouldDecode = requestFreshFrame || latestBitmap == null || (now - lastFrameTimestamp > 700L)
                if (!shouldDecode) return@setOnImageAvailableListener

                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * width

                val rawBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                rawBitmap.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
                if (cropped != rawBitmap) {
                    rawBitmap.recycle()
                }

                synchronized(frameLock) {
                    latestBitmap?.recycle()
                    latestBitmap = cropped
                    lastFrameTimestamp = now
                    requestFreshFrame = false
                }
            } catch (_: Exception) {
                // Ignore transient frame copy errors
            } finally {
                image.close()
            }
        }, handler)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenTranslatorDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        return true
    }

    suspend fun captureFrame(): Bitmap? {
        val startRequestTime = System.currentTimeMillis()
        requestFreshFrame = true

        // Wait briefly for a fresh frame after hiding overlay or UI update
        repeat(8) {
            synchronized(frameLock) {
                val bmp = latestBitmap
                if (bmp != null && !bmp.isRecycled && lastFrameTimestamp >= startRequestTime) {
                    return bmp.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
            delay(50L)
        }

        // Fallback to latest available frame if the screen is completely static
        return synchronized(frameLock) {
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
            latestBitmap?.recycle()
            latestBitmap = null
        }
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
