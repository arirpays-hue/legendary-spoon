package com.example.screentranslator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
    private lateinit var controlBar: LinearLayout
    private lateinit var autoButton: TextView
    private lateinit var translateButton: TextView
    private lateinit var controlParams: WindowManager.LayoutParams

    private var captureHelper: ScreenCaptureHelper? = null
    private val recognizerHelper = TextRecognizerHelper()
    private val offlineTranslator = OfflineEnHeTranslator()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoLoopJob: Job? = null
    private var isAutoMode = false
    private var isBusy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
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
        val startInAuto = intent?.getBooleanExtra(EXTRA_AUTO_MODE, false) ?: false

        if (resultCode == 0 || dataIntent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)

        if (!::overlayCanvasView.isInitialized) {
            setupOverlayViews()
        }

        val (width, height, densityDpi) = getScreenMetrics()
        captureHelper?.stop()
        val helper = ScreenCaptureHelper(
            context = this,
            resultCode = resultCode,
            dataIntent = dataIntent,
            width = width,
            height = height,
            densityDpi = densityDpi
        )
        if (!helper.start()) {
            Toast.makeText(this, "שגיאה בהפעלת לכידת מסך", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }
        captureHelper = helper

        serviceScope.launch {
            val ready = offlineTranslator.prepareModel()
            if (ready.isFailure) {
                Toast.makeText(
                    this@OverlayService,
                    "לא ניתן להוריד מודל תרגום — בדוק חיבור רשת בהפעלה ראשונה",
                    Toast.LENGTH_LONG
                ).show()
            } else if (startInAuto) {
                toggleAutoMode(true)
            } else {
                Toast.makeText(
                    this@OverlayService,
                    "מתרגם המסך מוכן — לחץ על 'תרגם' בכל מסך",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        return START_STICKY
    }

    private fun getScreenMetrics(): Triple<Int, Int, Int> {
        val densityDpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), densityDpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(overlayCanvasView, canvasParams)

        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE161920"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }

        val dragHandle = createPillButton("הזז", "#262B36", "#B0B8C5")
        translateButton = createPillButton("תרגם", "#3B82F6", "#FFFFFF").apply {
            setOnClickListener {
                serviceScope.launch {
                    runSingleTranslation(showFeedback = true)
                }
            }
        }
        autoButton = createPillButton("אוטו", "#262B36", "#E5E7EB").apply {
            setOnClickListener {
                toggleAutoMode(!isAutoMode)
            }
        }
        val clearButton = createPillButton("נקה", "#262B36", "#E5E7EB").apply {
            setOnClickListener {
                toggleAutoMode(false)
                overlayCanvasView.updateBlocks(emptyList())
            }
        }
        val closeButton = createPillButton("סגור", "#3A2024", "#FCA5A5").apply {
            setOnClickListener {
                stopSelf()
            }
        }

        controlBar.addView(dragHandle)
        controlBar.addView(translateButton)
        controlBar.addView(autoButton)
        controlBar.addView(clearButton)
        controlBar.addView(closeButton)

        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(90)
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = controlParams.x
                    initialY = controlParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    controlParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    controlParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(controlBar, controlParams)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(controlBar, controlParams)
    }

    private fun createPillButton(label: String, bgHex: String, textHex: String): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor(textHex))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(18).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
    }

    private fun toggleAutoMode(enable: Boolean) {
        isAutoMode = enable
        autoLoopJob?.cancel()

        val bg = autoButton.background as? GradientDrawable
        if (isAutoMode) {
            autoButton.text = "אוטו פעיל"
            bg?.setColor(Color.parseColor("#1D6F42"))
            autoLoopJob = serviceScope.launch {
                while (isActive && isAutoMode) {
                    runSingleTranslation(showFeedback = false)
                    delay(2200L)
                }
            }
        } else {
            autoButton.text = "אוטו"
            bg?.setColor(Color.parseColor("#262B36"))
        }
    }

    private suspend fun runSingleTranslation(showFeedback: Boolean) {
        if (isBusy) return
        isBusy = true
        translateButton.text = "סורק..."

        try {
            val hadBlocks = overlayCanvasView.hasBlocks()
            if (hadBlocks) {
                overlayCanvasView.visibility = View.INVISIBLE
                delay(160L)
            }

            val frame = captureHelper?.captureFrame()
            if (hadBlocks) {
                overlayCanvasView.visibility = View.VISIBLE
            }

            if (frame == null) {
                if (showFeedback) {
                    Toast.makeText(this, "לא הצלחתי ללכוד את המסך, נסה שוב", Toast.LENGTH_SHORT).show()
                }
                return
            }

            try {
                val blocks = recognizerHelper.recognize(frame)
                if (blocks.isEmpty()) {
                    overlayCanvasView.updateBlocks(emptyList())
                    if (showFeedback) {
                        Toast.makeText(this, "לא נמצא טקסט באנגלית על המסך", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val translatedBlocks = blocks.mapNotNull { block ->
                    val translated = offlineTranslator.translateEnglishToHebrew(block.text)
                    if (translated.isNotEmpty()) {
                        block.copy(translatedText = translated)
                    } else {
                        null
                    }
                }
                overlayCanvasView.updateBlocks(translatedBlocks)
            } finally {
                frame.recycle()
            }
        } catch (_: Exception) {
            if (showFeedback) {
                Toast.makeText(this, "אירעה שגיאה בתרגום המסך", Toast.LENGTH_SHORT).show()
            }
        } finally {
            translateButton.text = "תרגם"
            isBusy = false
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
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
            .setContentText("תרגום מסך לא-מקוון (אנגלית -> עברית) פעיל")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        toggleAutoMode(false)
        captureHelper?.stop()
        offlineTranslator.close()
        serviceScope.cancel()
        if (::overlayCanvasView.isInitialized) {
            runCatching { windowManager.removeView(overlayCanvasView) }
        }
        if (::controlBar.isInitialized) {
            runCatching { windowManager.removeView(controlBar) }
        }
        super.onDestroy()
    }

    private class TranslationCanvasView(context: Context) : View(context) {
        private var blocks: List<RecognizedBlock> = emptyList()

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EB14171F")
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F9FAFB")
            textSize = 38f
        }

        fun hasBlocks(): Boolean = blocks.isNotEmpty()

        fun updateBlocks(newBlocks: List<RecognizedBlock>) {
            blocks = newBlocks
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = 12f
            for (block in blocks) {
                val text = block.translatedText
                if (text.isEmpty()) continue

                val left = (block.bounds.left - pad).coerceAtLeast(8f)
                val top = (block.bounds.top - pad).coerceAtLeast(8f)
                val right = (block.bounds.right + pad).coerceAtMost(width - 8f)
                val bottom = (block.bounds.bottom + pad).coerceAtMost(height - 8f)
                val availableWidth = (right - left - pad * 1.5f).toInt().coerceAtLeast(120)
                val boxHeight = (bottom - top).coerceAtLeast(48f)

                var fontSize = (boxHeight * 0.55f).coerceIn(28f, 44f)
                var layout = buildLayout(text, availableWidth, fontSize)
                while (layout.height > boxHeight && fontSize > 24f) {
                    fontSize -= 3f
                    layout = buildLayout(text, availableWidth, fontSize)
                }

                val finalBottom = (top + layout.height + pad * 1.5f).coerceAtLeast(bottom)
                val finalRect = RectF(left, top, (left + availableWidth + pad * 1.5f).coerceAtMost(width - 8f), finalBottom)

                canvas.drawRoundRect(finalRect, 14f, 14f, boxPaint)
                canvas.drawRoundRect(finalRect, 14f, 14f, borderPaint)

                canvas.save()
                val textTop = finalRect.top + (finalRect.height() - layout.height) / 2f
                canvas.translate(finalRect.left + pad * 0.75f, textTop)
                layout.draw(canvas)
                canvas.restore()
            }
        }

        private fun buildLayout(text: String, widthPx: Int, fontSize: Float): StaticLayout {
            textPaint.textSize = fontSize
            return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .build()
        }
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false

        const val CHANNEL_ID = "screen_translator_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.screentranslator.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
        const val EXTRA_AUTO_MODE = "extra_auto_mode"
    }
}
