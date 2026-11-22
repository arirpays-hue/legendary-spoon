package com.example.screentranslator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
import android.widget.FrameLayout
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
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var backdropCanvasView: TranslationCanvasView
    private lateinit var overlayCanvasView: TranslationCanvasView

    private lateinit var rootControlContainer: LinearLayout
    private lateinit var edgeHandle: FrameLayout
    private lateinit var edgeIndicator: View
    private lateinit var menuContainer: LinearLayout
    private lateinit var autoMenuButton: TextView
    private lateinit var translateMenuButton: TextView
    private lateinit var controlParams: WindowManager.LayoutParams

    private var captureHelper: ScreenCaptureHelper? = null
    private val recognizerHelper = TextRecognizerHelper()
    private lateinit var translationEngine: OfflineEnHeTranslator
    private val diffTracker = ScreenDiffTracker()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcherJob: Job? = null

    private var isAutoMode = false
    private var offlineOnlyMode = false
    private var skipBrandNames = true
    private var isBusy = false
    private var isDockedRight = true
    private var isMenuExpanded = false
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var statusBarHeight = 0
    private var navBarHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        translationEngine = OfflineEnHeTranslator(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        statusBarHeight = loadSystemBarHeight("status_bar_height", dp(30))
        navBarHeight = loadSystemBarHeight("navigation_bar_height", dp(36))
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
        offlineOnlyMode = intent?.getBooleanExtra(EXTRA_OFFLINE_ONLY, false) ?: false
        skipBrandNames = intent?.getBooleanExtra(EXTRA_SKIP_BRANDS, true) ?: true

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

        val (width, height, densityDpi) = getScreenMetrics()
        screenWidth = width
        screenHeight = height

        if (!::overlayCanvasView.isInitialized) {
            setupOverlayViews()
        }

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

        startScreenWatcher()

        serviceScope.launch {
            if (offlineOnlyMode) {
                val ready = translationEngine.prepareModel()
                if (ready.isFailure) {
                    Toast.makeText(
                        this@OverlayService,
                        "המודל הלא-מקוון לא מותקן — הורד אותו במסך הראשי",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                // Warm up offline fallback silently in background
                launch(Dispatchers.IO) {
                    translationEngine.isModelDownloaded()
                }
            }

            if (startInAuto) {
                toggleAutoMode(true)
            } else {
                Toast.makeText(
                    this@OverlayService,
                    "לשונית התרגום פעילה בצד המסך",
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

    private fun loadSystemBarHeight(resourceName: String, fallbackPx: Int): Int {
        val id = resources.getIdentifier(resourceName, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else fallbackPx
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun setupOverlayViews() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        fun makeCanvasParams(): WindowManager.LayoutParams {
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                alpha = 1.0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        // Stacked opaque backdrop + foreground view eliminates OS-enforced overlay bleed-through
        backdropCanvasView = TranslationCanvasView(this, isBackdropOnly = true)
        overlayCanvasView = TranslationCanvasView(this, isBackdropOnly = false)
        windowManager.addView(backdropCanvasView, makeCanvasParams())
        windowManager.addView(overlayCanvasView, makeCanvasParams())

        rootControlContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        edgeIndicator = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(4), dp(26), Gravity.CENTER)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3B82F6"))
                cornerRadius = dp(4).toFloat()
            }
        }

        edgeHandle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(64))
            addView(edgeIndicator)
        }

        menuContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5141821"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }

        translateMenuButton = createMenuButton("תרגם מסך", "#3B82F6", "#FFFFFF").apply {
            setOnClickListener {
                setMenuExpanded(false)
                serviceScope.launch {
                    runFullTranslation(showFeedback = true, hideOverlayFirst = true)
                }
            }
        }

        autoMenuButton = createMenuButton("תרגום אוטומטי", "#232936", "#F8FAFC").apply {
            setOnClickListener {
                setMenuExpanded(false)
                toggleAutoMode(!isAutoMode)
            }
        }

        val clearButton = createMenuButton("נקה תרגום", "#232936", "#E2E8F0").apply {
            setOnClickListener {
                setMenuExpanded(false)
                toggleAutoMode(false)
                clearOverlayBlocks()
                diffTracker.reset()
            }
        }

        val closeButton = createMenuButton("סגור שירות", "#3A2024", "#FCA5A5").apply {
            setOnClickListener {
                stopSelf()
            }
        }

        menuContainer.addView(translateMenuButton)
        menuContainer.addView(autoMenuButton)
        menuContainer.addView(clearButton)
        menuContainer.addView(closeButton)

        applyEdgeStyleAndOrder()

        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = 0
            y = (screenHeight * 0.32f).toInt()
        }

        var startX = 0
        var startY = 0
        var touchDownX = 0f
        var touchDownY = 0f
        var isDragging = false
        val touchSlop = dp(10)

        edgeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val currentWidth = rootControlContainer.width.coerceAtLeast(dp(22))
                    startX = if (isDockedRight) {
                        (screenWidth - currentWidth).coerceAtLeast(0)
                    } else {
                        controlParams.x
                    }
                    startY = controlParams.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownX).toInt()
                    val dy = (event.rawY - touchDownY).toInt()
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        controlParams.gravity = Gravity.TOP or Gravity.LEFT
                    }
                    if (isDragging) {
                        val minY = statusBarHeight + dp(12)
                        val maxY = (screenHeight - navBarHeight - dp(80)).coerceAtLeast(minY)
                        controlParams.x = (startX + dx).coerceIn(0, (screenWidth - dp(22)).coerceAtLeast(0))
                        controlParams.y = (startY + dy).coerceIn(minY, maxY)
                        safeUpdateControlLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        setMenuExpanded(!isMenuExpanded)
                    } else {
                        val center = controlParams.x + (rootControlContainer.width / 2)
                        val newDockedRight = center >= (screenWidth / 2)
                        val sideChanged = newDockedRight != isDockedRight
                        isDockedRight = newDockedRight
                        rootControlContainer.post {
                            if (sideChanged) {
                                applyEdgeStyleAndOrder()
                            }
                            dockToCurrentEdge()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        rootControlContainer.post { dockToCurrentEdge() }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(rootControlContainer, controlParams)
    }

    private fun safeUpdateControlLayout() {
        if (::rootControlContainer.isInitialized && rootControlContainer.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(rootControlContainer, controlParams) }
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun dockToCurrentEdge() {
        controlParams.gravity = Gravity.TOP or if (isDockedRight) Gravity.RIGHT else Gravity.LEFT
        controlParams.x = 0
        val minY = statusBarHeight + dp(12)
        val maxY = (screenHeight - navBarHeight - dp(80)).coerceAtLeast(minY)
        controlParams.y = controlParams.y.coerceIn(minY, maxY)
        safeUpdateControlLayout()
    }

    private fun applyEdgeStyleAndOrder() {
        val radius = dp(14).toFloat()
        val radii = if (isDockedRight) {
            floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
        } else {
            floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
        }

        edgeHandle.background = GradientDrawable().apply {
            setColor(Color.parseColor("#B8141821"))
            cornerRadii = radii
            setStroke(dp(1), Color.parseColor("#44FFFFFF"))
        }

        val desiredFirst: View = if (isDockedRight) menuContainer else edgeHandle
        if (rootControlContainer.childCount == 2 && rootControlContainer.getChildAt(0) === desiredFirst) {
            return
        }

        rootControlContainer.removeAllViews()
        if (isDockedRight) {
            rootControlContainer.addView(menuContainer)
            rootControlContainer.addView(edgeHandle)
        } else {
            rootControlContainer.addView(edgeHandle)
            rootControlContainer.addView(menuContainer)
        }
    }

    private fun setMenuExpanded(expanded: Boolean) {
        isMenuExpanded = expanded
        menuContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        dockToCurrentEdge()
    }

    private fun updateIndicatorColor(hex: String) {
        val bg = edgeIndicator.background as? GradientDrawable
        bg?.setColor(Color.parseColor(hex))
    }

    private fun createMenuButton(label: String, bgHex: String, textHex: String): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor(textHex))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                dp(124),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
        }
    }

    private fun getControlScreenRect(): Rect {
        val loc = IntArray(2)
        rootControlContainer.getLocationOnScreen(loc)
        val pad = dp(6)
        return Rect(
            (loc[0] - pad).coerceAtLeast(0),
            (loc[1] - pad).coerceAtLeast(0),
            (loc[0] + rootControlContainer.width + pad).coerceAtMost(screenWidth),
            (loc[1] + rootControlContainer.height + pad).coerceAtMost(screenHeight)
        )
    }

    private fun setOverlayVisibility(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        backdropCanvasView.visibility = v
        overlayCanvasView.visibility = v
    }

    private fun updateOverlayBlocks(blocks: List<RecognizedBlock>) {
        val layouts = OverlayLayoutEngine.compute(
            blocks = blocks,
            canvasWidth = overlayCanvasView.width.takeIf { it > 0 } ?: screenWidth,
            canvasHeight = overlayCanvasView.height.takeIf { it > 0 } ?: screenHeight
        )
        backdropCanvasView.updateLayouts(blocks, layouts)
        overlayCanvasView.updateLayouts(blocks, layouts)
    }

    private fun clearOverlayBlocks() {
        backdropCanvasView.updateLayouts(emptyList(), emptyList())
        overlayCanvasView.updateLayouts(emptyList(), emptyList())
    }

    private fun startScreenWatcher() {
        watcherJob?.cancel()
        watcherJob = serviceScope.launch {
            while (isActive) {
                delay(if (isAutoMode) 220L else 350L)
                if (isBusy) continue
                if (!isAutoMode && !overlayCanvasView.hasBlocks()) continue
                checkScreenChanges()
            }
        }
    }

    private fun toggleAutoMode(enable: Boolean) {
        isAutoMode = enable
        val bg = autoMenuButton.background as? GradientDrawable

        if (isAutoMode) {
            autoMenuButton.text = "עצור אוטומטי"
            bg?.setColor(Color.parseColor("#1D6F42"))
            updateIndicatorColor("#22C55E")
            diffTracker.reset()
            serviceScope.launch {
                runFullTranslation(showFeedback = false, hideOverlayFirst = true)
            }
        } else {
            autoMenuButton.text = "תרגום אוטומטי"
            bg?.setColor(Color.parseColor("#232936"))
            updateIndicatorColor("#3B82F6")
        }
    }

    private suspend fun checkScreenChanges() {
        val frame = captureHelper?.captureFrame(waitForFresh = false) ?: return
        try {
            val controlRect = getControlScreenRect()
            val maskedRects = buildList {
                add(controlRect)
                addAll(overlayCanvasView.getRenderedRects())
            }
            val diff = withContext(Dispatchers.Default) {
                diffTracker.analyze(
                    frame = frame,
                    maskedRects = maskedRects,
                    topInset = statusBarHeight,
                    bottomInset = navBarHeight
                )
            }

            when (diff) {
                is DiffResult.Unchanged -> Unit

                is DiffResult.Partial -> {
                    if (isAutoMode) {
                        translateChangedRegion(frame, diff.dirtyRect)
                    }
                }

                is DiffResult.FullChange -> {
                    // Always clear stale bubbles immediately on scroll or app switch
                    clearOverlayBlocks()
                    if (isAutoMode) {
                        waitForScreenToSettle()
                        if (isAutoMode) {
                            runFullTranslation(showFeedback = false, hideOverlayFirst = false)
                        }
                    }
                }
            }
        } finally {
            frame.recycle()
        }
    }

    private suspend fun waitForScreenToSettle() {
        repeat(3) {
            delay(170L)
            val probe = captureHelper?.captureFrame(waitForFresh = true) ?: return
            try {
                val controlRect = getControlScreenRect()
                val res = withContext(Dispatchers.Default) {
                    diffTracker.analyze(
                        frame = probe,
                        maskedRects = listOf(controlRect),
                        topInset = statusBarHeight,
                        bottomInset = navBarHeight
                    )
                }
                if (res is DiffResult.Unchanged) {
                    return
                }
            } finally {
                probe.recycle()
            }
        }
    }

    private suspend fun translateBlocksFiltered(blocks: List<RecognizedBlock>): List<RecognizedBlock> {
        val eligible = blocks.filterNot {
            translationEngine.shouldSkipText(it.text, skipBrands = skipBrandNames)
        }
        if (eligible.isEmpty()) return emptyList()

        val translatedMap = translationEngine.translateBatch(
            texts = eligible.map { it.text },
            offlineOnly = offlineOnlyMode
        )

        return eligible.mapNotNull { block ->
            val translated = translatedMap[block.text]?.trim().orEmpty()
            val hasHebrew = translated.any { it in '\u0590'..'\u05FF' }
            if (hasHebrew && !translated.equals(block.text, ignoreCase = true)) {
                block.copy(translatedText = translated)
            } else {
                null
            }
        }
    }

    private suspend fun translateChangedRegion(currentFrame: Bitmap, rawDirtyRect: Rect) {
        if (isBusy) return
        isBusy = true
        updateIndicatorColor("#F59E0B")

        try {
            val padX = dp(24)
            val padY = dp(16)
            val cropLeft = (rawDirtyRect.left - padX).coerceIn(0, currentFrame.width - 1)
            val cropTop = (rawDirtyRect.top - padY).coerceIn(statusBarHeight, currentFrame.height - 1)
            val cropRight = (rawDirtyRect.right + padX).coerceIn(cropLeft + 1, currentFrame.width)
            val cropBottom = (rawDirtyRect.bottom + padY).coerceIn(cropTop + 1, currentFrame.height - navBarHeight)

            val cropWidth = cropRight - cropLeft
            val cropHeight = cropBottom - cropTop
            if (cropWidth < 32 || cropHeight < 20) return

            val expandedRect = Rect(cropLeft, cropTop, cropRight, cropBottom)
            val existingBlocks = overlayCanvasView.getBlocks()
            val overlapping = existingBlocks.filter { Rect.intersects(it.bounds, expandedRect) }
            val preserved = existingBlocks.filterNot { Rect.intersects(it.bounds, expandedRect) }

            var freshFrame: Bitmap? = null
            val sourceBitmap = if (overlapping.isNotEmpty()) {
                updateOverlayBlocks(preserved)
                delay(90L)
                freshFrame = captureHelper?.captureFrame(waitForFresh = true)
                freshFrame ?: currentFrame
            } else {
                currentFrame
            }

            try {
                val safeW = min(cropWidth, sourceBitmap.width - cropLeft)
                val safeH = min(cropHeight, sourceBitmap.height - cropTop)
                if (safeW < 24 || safeH < 16) return

                val cropped = Bitmap.createBitmap(sourceBitmap, cropLeft, cropTop, safeW, safeH)
                try {
                    val detected = recognizerHelper.recognize(
                        bitmap = cropped,
                        offsetX = cropLeft,
                        offsetY = cropTop,
                        minTopY = statusBarHeight,
                        maxBottomY = screenHeight - navBarHeight,
                        excludeRect = getControlScreenRect()
                    )
                    val translatedNew = translateBlocksFiltered(detected)
                    updateOverlayBlocks(preserved + translatedNew)
                } finally {
                    cropped.recycle()
                }
            } finally {
                freshFrame?.recycle()
            }
        } catch (_: Exception) {
            // Preserve existing blocks on transient partial OCR error
        } finally {
            updateIndicatorColor(if (isAutoMode) "#22C55E" else "#3B82F6")
            isBusy = false
        }
    }

    private suspend fun runFullTranslation(showFeedback: Boolean, hideOverlayFirst: Boolean) {
        if (isBusy) return
        isBusy = true
        translateMenuButton.text = "סורק..."
        updateIndicatorColor("#F59E0B")

        try {
            val hadBlocks = overlayCanvasView.hasBlocks()
            if (hideOverlayFirst && hadBlocks) {
                setOverlayVisibility(false)
                delay(95L)
            }

            val frame = captureHelper?.captureFrame(waitForFresh = true)
            if (hideOverlayFirst && hadBlocks) {
                setOverlayVisibility(true)
            }

            if (frame == null) {
                if (showFeedback) {
                    Toast.makeText(this, "לא הצלחתי ללכוד את המסך, נסה שוב", Toast.LENGTH_SHORT).show()
                }
                return
            }

            try {
                withContext(Dispatchers.Default) {
                    diffTracker.updateBaseline(frame)
                }

                val blocks = recognizerHelper.recognize(
                    bitmap = frame,
                    minTopY = statusBarHeight,
                    maxBottomY = screenHeight - navBarHeight,
                    excludeRect = getControlScreenRect()
                )

                val translatedBlocks = translateBlocksFiltered(blocks)
                updateOverlayBlocks(translatedBlocks)
                if (translatedBlocks.isEmpty() && showFeedback) {
                    Toast.makeText(this, "לא נמצא טקסט באנגלית לתרגום", Toast.LENGTH_SHORT).show()
                }
            } finally {
                frame.recycle()
            }
        } catch (_: Exception) {
            if (showFeedback) {
                Toast.makeText(this, "אירעה שגיאה בתרגום המסך", Toast.LENGTH_SHORT).show()
            }
        } finally {
            translateMenuButton.text = "תרגם מסך"
            updateIndicatorColor(if (isAutoMode) "#22C55E" else "#3B82F6")
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
        val modeLabel = if (offlineOnlyMode) "מצב לא-מקוון" else "מצב היברידי מהיר"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("תרגום מסך ($modeLabel) פעיל")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        watcherJob?.cancel()
        captureHelper?.stop()
        translationEngine.close()
        serviceScope.cancel()
        if (::backdropCanvasView.isInitialized) {
            runCatching { windowManager.removeView(backdropCanvasView) }
        }
        if (::overlayCanvasView.isInitialized) {
            runCatching { windowManager.removeView(overlayCanvasView) }
        }
        if (::rootControlContainer.isInitialized) {
            runCatching { windowManager.removeView(rootControlContainer) }
        }
        super.onDestroy()
    }

    private sealed class DiffResult {
        data object Unchanged : DiffResult()
        data class Partial(val dirtyRect: Rect) : DiffResult()
        data object FullChange : DiffResult()
    }

    private class ScreenDiffTracker {
        private val thumbW = 54
        private val thumbH = 108
        private val tileSize = 9
        private var baselinePixels: IntArray? = null

        fun reset() {
            baselinePixels = null
        }

        fun updateBaseline(frame: Bitmap) {
            baselinePixels = extractThumbPixels(frame)
        }

        fun analyze(
            frame: Bitmap,
            maskedRects: List<Rect>,
            topInset: Int,
            bottomInset: Int
        ): DiffResult {
            val current = extractThumbPixels(frame)
            val prev = baselinePixels
            if (prev == null || prev.size != current.size) {
                baselinePixels = current
                return DiffResult.FullChange
            }

            val scaleX = frame.width.toFloat() / thumbW
            val scaleY = frame.height.toFloat() / thumbH
            val minTy = (topInset / scaleY).toInt().coerceIn(0, thumbH - 1)
            val maxTy = ((frame.height - bottomInset) / scaleY).toInt().coerceIn(minTy + 1, thumbH)

            val cols = thumbW / tileSize
            val rows = thumbH / tileSize
            var totalActiveTiles = 0
            var changedTiles = 0

            var minChangedLeft = Int.MAX_VALUE
            var minChangedTop = Int.MAX_VALUE
            var maxChangedRight = 0
            var maxChangedBottom = 0

            for (row in 0 until rows) {
                val tyStart = row * tileSize
                val tyEnd = min(tyStart + tileSize, thumbH)
                if (tyEnd <= minTy || tyStart >= maxTy) continue

                for (col in 0 until cols) {
                    val txStart = col * tileSize
                    val txEnd = min(txStart + tileSize, thumbW)

                    var checkedPixels = 0
                    var diffPixels = 0

                    for (ty in tyStart until tyEnd) {
                        if (ty < minTy || ty >= maxTy) continue
                        val screenY = (ty * scaleY).toInt()
                        val rowOffset = ty * thumbW
                        for (tx in txStart until txEnd) {
                            val screenX = (tx * scaleX).toInt()
                            if (isMasked(screenX, screenY, maskedRects)) continue

                            checkedPixels++
                            val c1 = prev[rowOffset + tx]
                            val c2 = current[rowOffset + tx]
                            val delta = abs(Color.red(c1) - Color.red(c2)) +
                                abs(Color.green(c1) - Color.green(c2)) +
                                abs(Color.blue(c1) - Color.blue(c2))
                            if (delta > 52) {
                                diffPixels++
                            }
                        }
                    }

                    if (checkedPixels < 8) continue
                    totalActiveTiles++

                    if (diffPixels.toFloat() / checkedPixels > 0.14f) {
                        changedTiles++
                        val sx1 = (txStart * scaleX).toInt()
                        val sy1 = (tyStart * scaleY).toInt()
                        val sx2 = (txEnd * scaleX).toInt()
                        val sy2 = (tyEnd * scaleY).toInt()
                        minChangedLeft = min(minChangedLeft, sx1)
                        minChangedTop = min(minChangedTop, sy1)
                        maxChangedRight = max(maxChangedRight, sx2)
                        maxChangedBottom = max(maxChangedBottom, sy2)
                    }
                }
            }

            baselinePixels = current

            if (changedTiles == 0 || totalActiveTiles == 0) {
                return DiffResult.Unchanged
            }

            val changedRatio = changedTiles.toFloat() / totalActiveTiles
            return if (changedRatio >= 0.20f) {
                DiffResult.FullChange
            } else {
                DiffResult.Partial(
                    Rect(
                        minChangedLeft.coerceIn(0, frame.width),
                        minChangedTop.coerceIn(0, frame.height),
                        maxChangedRight.coerceIn(0, frame.width),
                        maxChangedBottom.coerceIn(0, frame.height)
                    )
                )
            }
        }

        private fun isMasked(x: Int, y: Int, rects: List<Rect>): Boolean {
            for (r in rects) {
                if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
                    return true
                }
            }
            return false
        }

        private fun extractThumbPixels(frame: Bitmap): IntArray {
            val scaled = Bitmap.createScaledBitmap(frame, thumbW, thumbH, false)
            val pixels = IntArray(thumbW * thumbH)
            scaled.getPixels(pixels, 0, thumbW, 0, 0, thumbW, thumbH)
            if (scaled != frame) {
                scaled.recycle()
            }
            return pixels
        }
    }

    private data class RenderedBlockLayout(
        val rect: RectF,
        val angleDegrees: Float,
        val bgTopColor: Int,
        val bgBottomColor: Int,
        val textColor: Int,
        val needsTextShadow: Boolean,
        val layout: StaticLayout,
        val screenBounds: Rect
    )

    private object OverlayLayoutEngine {
        fun compute(
            blocks: List<RecognizedBlock>,
            canvasWidth: Int,
            canvasHeight: Int
        ): List<RenderedBlockLayout> {
            if (blocks.isEmpty() || canvasWidth <= 0 || canvasHeight <= 0) return emptyList()

            val sorted = blocks.filter { it.translatedText.isNotEmpty() }
                .sortedWith(compareBy<RecognizedBlock> { it.bounds.top }.thenBy { it.bounds.left })

            val results = ArrayList<RenderedBlockLayout>(sorted.size)
            val padX = 8f
            val padY = 5f

            for (i in sorted.indices) {
                val block = sorted[i]
                val text = block.translatedText
                val isRotated = abs(block.angleDegrees) >= 2.5f
                val baseW = if (isRotated) block.orientedWidth else block.bounds.width()
                val baseH = if (isRotated) block.orientedHeight else block.bounds.height()
                val origW = baseW.coerceAtLeast(22)
                val origH = baseH.coerceAtLeast(18)
                val isCompactLabel = block.lineCount == 1 && origW < (canvasWidth * 0.50f)

                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = block.textColor
                    if (block.needsTextShadow) {
                        setShadowLayer(2.5f, 0f, 1.5f, Color.argb(145, 0, 0, 0))
                    }
                }

                val avgLineHeight = origH.toFloat() / block.lineCount.coerceAtLeast(1)
                var fontSize = (avgLineHeight * 0.78f).coerceIn(19f, 50f)
                paint.textSize = fontSize

                // Find nearest left/right neighbors on the same row to prevent any box collisions
                var minAllowedLeft = 6f
                var maxAllowedRight = canvasWidth - 6f
                if (!isRotated) {
                    for (j in sorted.indices) {
                        if (i == j) continue
                        val other = sorted[j].bounds
                        val vOverlap = min(block.bounds.bottom, other.bottom) - max(block.bounds.top, other.top)
                        val minH = min(block.bounds.height(), other.height()).coerceAtLeast(1)
                        if (vOverlap.toFloat() / minH > 0.30f) {
                            if (other.right <= block.bounds.left + 8) {
                                val mid = (other.right + block.bounds.left) / 2f + 3f
                                minAllowedLeft = max(minAllowedLeft, mid)
                            } else if (other.left >= block.bounds.right - 8) {
                                val mid = (block.bounds.right + other.left) / 2f - 3f
                                maxAllowedRight = min(maxAllowedRight, mid)
                            }
                        }
                    }
                }

                val targetRect: RectF
                val layout: StaticLayout

                if (isCompactLabel) {
                    val columnLimit = (maxAllowedRight - minAllowedLeft - padX * 2f).coerceAtLeast(origW.toFloat())
                    val maxAllowedWidth = min(columnLimit, max(origW * 1.45f, canvasWidth * 0.28f))
                    var measuredWidth = ceil(StaticLayout.getDesiredWidth(text, paint).toDouble()).toFloat()

                    while (measuredWidth > maxAllowedWidth && fontSize > 15f) {
                        fontSize -= 1.2f
                        paint.textSize = fontSize
                        measuredWidth = ceil(StaticLayout.getDesiredWidth(text, paint).toDouble()).toFloat()
                    }

                    val layoutWidth = max(origW.toFloat(), min(measuredWidth, maxAllowedWidth))
                        .toInt()
                        .coerceAtLeast(26)
                    layout = buildStaticLayout(text, paint, layoutWidth, Layout.Alignment.ALIGN_CENTER)

                    val boxW = max(origW + padX * 2f, layoutWidth + padX * 2f)
                    val boxH = max(origH + padY * 2f, layout.height + padY * 2f)
                    val centerX = block.bounds.exactCenterX()
                    val centerY = block.bounds.exactCenterY()

                    if (isRotated) {
                        targetRect = RectF(
                            centerX - boxW / 2f,
                            centerY - boxH / 2f,
                            centerX + boxW / 2f,
                            centerY + boxH / 2f
                        )
                    } else {
                        var left = centerX - (boxW / 2f)
                        var right = centerX + (boxW / 2f)
                        if (left < minAllowedLeft) {
                            right += (minAllowedLeft - left)
                            left = minAllowedLeft
                        }
                        if (right > maxAllowedRight) {
                            left -= (right - maxAllowedRight)
                            right = maxAllowedRight
                        }
                        left = left.coerceAtLeast(minAllowedLeft)
                        right = right.coerceAtMost(maxAllowedRight).coerceAtLeast(left + 28f)
                        val top = (centerY - boxH / 2f).coerceAtLeast(4f)
                        val bottom = (top + boxH).coerceAtMost(canvasHeight - 4f)
                        targetRect = RectF(left, top, right, bottom)
                    }
                } else {
                    val left = if (isRotated) {
                        block.bounds.exactCenterX() - origW / 2f - padX
                    } else {
                        (block.bounds.left - padX).coerceAtLeast(minAllowedLeft)
                    }
                    val right = if (isRotated) {
                        block.bounds.exactCenterX() + origW / 2f + padX
                    } else {
                        (block.bounds.right + padX).coerceAtMost(maxAllowedRight)
                    }
                    val layoutWidth = (right - left - padX * 2f).toInt().coerceAtLeast(44)

                    var fittedLayout = buildStaticLayout(text, paint, layoutWidth, Layout.Alignment.ALIGN_NORMAL)
                    while (fittedLayout.height > origH + padY && fontSize > 15f) {
                        fontSize -= 1.2f
                        paint.textSize = fontSize
                        fittedLayout = buildStaticLayout(text, paint, layoutWidth, Layout.Alignment.ALIGN_NORMAL)
                    }
                    layout = fittedLayout

                    val boxH = max(origH + padY * 2f, layout.height + padY * 2f)
                    val top = if (isRotated) {
                        block.bounds.exactCenterY() - boxH / 2f
                    } else {
                        (block.bounds.top - padY).coerceAtLeast(4f)
                    }
                    val bottom = if (isRotated) {
                        top + boxH
                    } else {
                        (top + boxH).coerceAtMost(canvasHeight - 4f)
                    }
                    targetRect = RectF(left, top, right, bottom)
                }

                results.add(
                    RenderedBlockLayout(
                        rect = targetRect,
                        angleDegrees = block.angleDegrees,
                        bgTopColor = block.bgTopColor or -0x1000000,
                        bgBottomColor = block.bgBottomColor or -0x1000000,
                        textColor = block.textColor,
                        needsTextShadow = block.needsTextShadow,
                        layout = layout,
                        screenBounds = Rect(
                            min(targetRect.left.toInt(), block.bounds.left),
                            min(targetRect.top.toInt(), block.bounds.top),
                            max(targetRect.right.toInt(), block.bounds.right),
                            max(targetRect.bottom.toInt(), block.bounds.bottom)
                        )
                    )
                )
            }

            return results
        }

        private fun buildStaticLayout(
            text: String,
            paint: TextPaint,
            widthPx: Int,
            alignment: Layout.Alignment
        ): StaticLayout {
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
                .setAlignment(alignment)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .build()
        }
    }

    private class TranslationCanvasView(
        context: Context,
        private val isBackdropOnly: Boolean
    ) : View(context) {

        private var blocks: List<RecognizedBlock> = emptyList()
        private var layouts: List<RenderedBlockLayout> = emptyList()

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        fun hasBlocks(): Boolean = blocks.isNotEmpty()

        fun getBlocks(): List<RecognizedBlock> = blocks

        fun getRenderedRects(): List<Rect> = layouts.map { it.screenBounds }

        fun updateLayouts(newBlocks: List<RecognizedBlock>, newLayouts: List<RenderedBlockLayout>) {
            blocks = newBlocks
            layouts = newLayouts
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (item in layouts) {
                val rect = item.rect
                val isRotated = abs(item.angleDegrees) >= 2.5f

                if (item.bgTopColor != item.bgBottomColor) {
                    boxPaint.shader = LinearGradient(
                        rect.left,
                        rect.top,
                        rect.left,
                        rect.bottom,
                        item.bgTopColor,
                        item.bgBottomColor,
                        Shader.TileMode.CLAMP
                    )
                } else {
                    boxPaint.shader = null
                    boxPaint.color = item.bgTopColor
                }

                canvas.save()
                if (isRotated) {
                    canvas.rotate(item.angleDegrees, rect.centerX(), rect.centerY())
                }

                canvas.drawRoundRect(rect, 10f, 10f, boxPaint)

                if (!isBackdropOnly) {
                    val textLeft = rect.left + (rect.width() - item.layout.width) / 2f
                    val textTop = rect.top + (rect.height() - item.layout.height) / 2f
                    canvas.translate(textLeft, textTop)
                    item.layout.draw(canvas)
                }

                canvas.restore()
            }
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
        const val EXTRA_OFFLINE_ONLY = "extra_offline_only"
        const val EXTRA_SKIP_BRANDS = "extra_skip_brands"
    }
}
