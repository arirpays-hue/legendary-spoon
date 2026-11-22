package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayCanvasView: TranslationCanvasView
    private lateinit var toggleButton: Button
    private var captureHelper: ScreenCaptureHelper? = null

    private val recognizerHelper = TextRecognizerHelper()
    private val offlineTranslator = OfflineEnHeTranslator()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loopJob: Job? = null
    private var isTranslating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupOverlayViews()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode != 0 && dataIntent != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            captureHelper?.stop()
            captureHelper = ScreenCaptureHelper(
                context = this,
                resultCode = resultCode,
                dataIntent = dataIntent,
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi
            ).also { it.start() }

            startTranslationLoop()
        }

        return START_STICKY
    }

    private fun setupOverlayViews() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayCanvasView = TranslationCanvasView(this)
        val canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlayCanvasView, canvasParams)

        toggleButton = Button(this).apply {
            text = "Pause"
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (isTranslating) {
                    stopTranslationLoop()
                } else {
                    startTranslationLoop()
                }
            }
        }

        val buttonParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 140
        }
        windowManager.addView(toggleButton, buttonParams)
    }

    private fun startTranslationLoop() {
        if (isTranslating) return
        isTranslating = true
        toggleButton.text = "Pause"

        loopJob?.cancel()
        loopJob = serviceScope.launch {
            offlineTranslator.prepareModel(requireWifiOnly = false)
            while (isActive && isTranslating) {
                val frame = captureHelper?.captureFrame()
                if (frame != null) {
                    try {
                        val blocks = recognizerHelper.recognize(frame)
                        val translatedBlocks = blocks.map { block ->
                            val translated = offlineTranslator.translateEnglishToHebrew(block.text)
                            block.copy(translatedText = translated)
                        }
                        overlayCanvasView.updateBlocks(translatedBlocks)
                    } catch (_: Exception) {
                        // Ignore transient OCR/translation errors
                    } finally {
                        frame.recycle()
                    }
                }
                delay(1200L)
            }
        }
    }

    private fun stopTranslationLoop() {
        isTranslating = false
        toggleButton.text = "Resume"
        loopJob?.cancel()
        overlayCanvasView.updateBlocks(emptyList())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Offline EN -> HE screen translation active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopTranslationLoop()
        captureHelper?.stop()
        offlineTranslator.close()
        serviceScope.cancel()
        if (::overlayCanvasView.isInitialized) {
            windowManager.removeView(overlayCanvasView)
        }
        if (::toggleButton.isInitialized) {
            windowManager.removeView(toggleButton)
        }
        super.onDestroy()
    }

    private class TranslationCanvasView(context: Context) : View(context) {
        private var blocks: List<RecognizedBlock> = emptyList()

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6121212")
            style = Paint.Style.FILL
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.RIGHT
        }

        fun updateBlocks(newBlocks: List<RecognizedBlock>) {
            blocks = newBlocks
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (block in blocks) {
                if (block.translatedText.isEmpty()) continue
                val rect = RectF(
                    block.bounds.left.toFloat() - 6f,
                    block.bounds.top.toFloat() - 6f,
                    block.bounds.right.toFloat() + 6f,
                    block.bounds.bottom.toFloat() + 6f
                )
                canvas.drawRoundRect(rect, 10f, 10f, boxPaint)
                canvas.drawText(
                    block.translatedText,
                    rect.right - 10f,
                    rect.centerY() + (textPaint.textSize / 3f),
                    textPaint
                )
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "screen_translator_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.screentranslator.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
    }
}
