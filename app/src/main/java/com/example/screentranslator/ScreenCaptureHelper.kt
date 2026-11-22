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
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseDisplay()
        }
    }

    fun start() {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, dataIntent) ?: return
        projection.registerCallback(projectionCallback, mainHandler)
        mediaProjection = projection

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenTranslatorDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )
    }

    suspend fun captureFrame(): Bitmap? = suspendCancellableCoroutine { cont ->
        val reader = imageReader
        if (reader == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val image = reader.acquireLatestImage()
        if (image == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        try {
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
            cont.resume(cropped)
        } catch (_: Exception) {
            cont.resume(null)
        } finally {
            image.close()
        }
    }

    private fun releaseDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    fun stop() {
        releaseDisplay()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }
}
