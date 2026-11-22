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
import kotlinx.coroutines.withTimeoutOrNull
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

    @Volatile
    private var latestBitmap: Bitmap? = null

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
        reader.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
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
                latestBitmap?.recycle()
                latestBitmap = cropped
            } catch (_: Exception) {
                // Ignore transient frame copy errors
            } finally {
                image.close()
            }
        }, mainHandler)
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

    suspend fun captureFrame(): Bitmap? {
        val immediate = latestBitmap
        if (immediate != null && !immediate.isRecycled) {
            return immediate.copy(Bitmap.Config.ARGB_8888, false)
        }

        return withTimeoutOrNull(1500L) {
            suspendCancellableCoroutine { cont ->
                mainHandler.postDelayed(object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        val bmp = latestBitmap
                        if (bmp != null && !bmp.isRecycled) {
                            cont.resume(bmp.copy(Bitmap.Config.ARGB_8888, false))
                        } else {
                            mainHandler.postDelayed(this, 100L)
                        }
                    }
                }, 100L)
            }
        }
    }

    private fun releaseDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        latestBitmap?.recycle()
        latestBitmap = null
    }

    fun stop() {
        releaseDisplay()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }
}
