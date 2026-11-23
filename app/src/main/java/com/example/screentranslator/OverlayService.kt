package com.example.screentranslator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import android.provider.Settings
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
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
    private lateinit var translationEngine: ScreenTranslationEngine
    private val diffTracker = ScreenDiffTracker()

    private val serviceScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main +
            CrashGuard.coroutineHandler("OverlayService.scope")
    )
    private var watcherJob: Job? = null
    private val overlayGeneration = AtomicLong(0L)
    private val isBusy = AtomicBoolean(false)

    @Volatile
    private var isServiceDestroyed = false

    private var sourceLang: LanguageOption = LanguageCatalog.defaultSource
    private var targetLang: LanguageOption = LanguageCatalog.defaultTarget
    private var isAutoMode = false
    private var offlineOnlyMode = true
    private var skipBrandNames = true
    private var enableScanWave = true
    private var isDockedRight = true
    private var isMenuExpanded = false
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var statusBarHeight = 0
    private var navBarHeight = 0
    private var lastCheckedFrameVersion: Long = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceDestroyed = false
        _serviceRunningState.value = true
        translationEngine = ScreenTranslationEngine(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        statusBarHeight = loadSystemBarHeight("status_bar_height", dp(30))
        navBarHeight = loadSystemBarHeight("navigation_bar_height", dp(36))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA_INTENT)
        }
        val startInAuto = intent.getBooleanExtra(EXTRA_AUTO_MODE, false)
        offlineOnlyMode = intent.getBooleanExtra(EXTRA_OFFLINE_ONLY, true)
        skipBrandNames = intent.getBooleanExtra(EXTRA_SKIP_BRANDS, true)
        enableScanWave = intent.getBooleanExtra(EXTRA_SCAN_WAVE, true)

        sourceLang = LanguageCatalog.findByCode(
            intent.getStringExtra(EXTRA_SOURCE_LANG) ?: LanguageCatalog.defaultSource.code
        )
        targetLang = LanguageCatalog.findByCode(
            intent.getStringExtra(EXTRA_TARGET_LANG) ?: LanguageCatalog.defaultTarget.code
        )

        val validation = EdgeCasePolicy.validateLanguagePair(sourceLang, targetLang)
        if (!validation.isValid) {
            safeToast(validation.errorHe ?: "זוג שפות לא תקין", Toast.LENGTH_LONG)
            stopSelf()
            return START_NOT_STICKY
        }

        if (resultCode == 0 || dataIntent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            safeToast("נדרשת הרשאת תצוגה מעל אפליקציות", Toast.LENGTH_LONG)
            stopSelf()
            return START_NOT_STICKY
        }

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        val foregroundStarted = CrashGuard.safeCall("OverlayService.startForeground", false) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)
            true
        }
        if (!foregroundStarted) {
            safeToast("המערכת חסמה את הפעלת שירות לכידת המסך — נסה להפעיל שוב", Toast.LENGTH_LONG)
            stopSelf()
            return START_NOT_STICKY
        }

        val (width, height, densityDpi) = getScreenMetrics()
        screenWidth = width.coerceAtLeast(320)
        screenHeight = height.coerceAtLeast(320)

        if (!::overlayCanvasView.isInitialized) {
            if (!setupOverlayViews()) {
                safeToast("לא ניתן לפתוח את שכבת התרגום", Toast.LENGTH_LONG)
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            clearOverlayBlocks()
            diffTracker.reset()
        }

        captureHelper?.stop()
        val helper = ScreenCaptureHelper(
            context = this,
            resultCode = resultCode,
            dataIntent = dataIntent,
            initialFullWidth = screenWidth,
            initialFullHeight = screenHeight,
            initialDensityDpi = densityDpi,
            onProjectionStopped = {
                serviceScope.launch {
                    if (!isServiceDestroyed) {
                        stopSelf()
                    }
                }
            }
        )
        if (!helper.start()) {
            safeToast("שגיאה בהפעלת לכידת מסך", Toast.LENGTH_SHORT)
            stopSelf()
            return START_NOT_STICKY
        }
        captureHelper = helper
        lastCheckedFrameVersion = -1L

        startScreenWatcher()

        serviceScope.launch {
            if (validation.warningHe != null) {
                safeToast(validation.warningHe, Toast.LENGTH_LONG)
            }

            if (offlineOnlyMode) {
                val ready = translationEngine.prepareModel(sourceLang, targetLang)
                if (ready.isFailure) {
                    safeToast(
                        "המודל ${sourceLang.nameHe} ← ${targetLang.nameHe} לא מותקן — הורד אותו בלשונית מודלים",
                        Toast.LENGTH_LONG
                    )
                }
            } else {
                launch(Dispatchers.IO) {
                    translationEngine.isPairModelDownloaded(sourceLang, targetLang)
                }
            }

            if (isServiceDestroyed) return@launch
            if (startInAuto) {
                toggleAutoMode(true)
            } else {
                safeToast("לשונית התרגום פעילה (${sourceLang.nameHe} ← ${targetLang.nameHe})", Toast.LENGTH_SHORT)
            }
        }

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CrashGuard.safeRun("OverlayService.onConfigChanged") {
            val (newW, newH, newDpi) = getScreenMetrics()
            if (newW <= 0 || newH <= 0 || (newW == screenWidth && newH == screenHeight)) return@safeRun

            screenWidth = newW
            screenHeight = newH
            statusBarHeight = loadSystemBarHeight("status_bar_height", dp(30))
            navBarHeight = loadSystemBarHeight("navigation_bar_height", dp(36))

            clearOverlayBlocks()
            diffTracker.reset()
            captureHelper?.resize(newW, newH, newDpi)
            lastCheckedFrameVersion = -1L

            if (::rootControlContainer.isInitialized) {
                dockToCurrentEdge()
            }
        }
    }

    private fun safeToast(message: String, duration: Int) {
        if (isServiceDestroyed) return
        CrashGuard.safeRun("OverlayService.toast") {
            Toast.makeText(applicationContext, message, duration).show()
        }
    }

    private fun getScreenMetrics(): Triple<Int, Int, Int> {
        val densityDpi = resources.displayMetrics.densityDpi.coerceAtLeast(160)
        return CrashGuard.safeCall("OverlayService.metrics", Triple(1080, 2400, densityDpi)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                Triple(bounds.width().coerceAtLeast(320), bounds.height().coerceAtLeast(320), densityDpi)
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                Triple(
                    metrics.widthPixels.coerceAtLeast(320),
                    metrics.heightPixels.coerceAtLeast(320),
                    metrics.densityDpi.coerceAtLeast(160)
                )
            }
        }
    }

    private fun loadSystemBarHeight(resourceName: String, fallbackPx: Int): Int {
        return CrashGuard.safeCall("OverlayService.barHeight", fallbackPx) {
            val id = resources.getIdentifier(resourceName, "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else fallbackPx
        }
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun setupOverlayViews(): Boolean {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayCanvasView = TranslationCanvasView(this).apply {
            visibility = View.GONE
        }
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
            alpha = EdgeCasePolicy.resolveSafeOverlayAlpha(this@OverlayService)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val addedCanvas = CrashGuard.safeCall("OverlayService.addCanvas", false) {
            windowManager.addView(overlayCanvasView, canvasParams)
            true
        }
        if (!addedCanvas) return false

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
            if (isServiceDestroyed) return@setOnTouchListener false
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
                            if (isServiceDestroyed) return@post
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
                        rootControlContainer.post {
                            if (!isServiceDestroyed) dockToCurrentEdge()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val addedControls = CrashGuard.safeCall("OverlayService.addControls", false) {
            windowManager.addView(rootControlContainer, controlParams)
            true
        }
        if (!addedControls) {
            CrashGuard.safeRun("OverlayService.rollbackCanvas") {
                if (overlayCanvasView.isAttachedToWindow) {
                    windowManager.removeView(overlayCanvasView)
                }
            }
            return false
        }
        return true
    }

    private fun safeUpdateControlLayout() {
        if (!isServiceDestroyed &&
            ::rootControlContainer.isInitialized &&
            rootControlContainer.isAttachedToWindow
        ) {
            CrashGuard.safeRun("OverlayService.updateControlLayout") {
                windowManager.updateViewLayout(rootControlContainer, controlParams)
            }
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun dockToCurrentEdge() {
        if (isServiceDestroyed || !::controlParams.isInitialized) return
        controlParams.gravity = Gravity.TOP or if (isDockedRight) Gravity.RIGHT else Gravity.LEFT
        controlParams.x = 0
        val minY = statusBarHeight + dp(12)
        val maxY = (screenHeight - navBarHeight - dp(80)).coerceAtLeast(minY)
        controlParams.y = controlParams.y.coerceIn(minY, maxY)
        safeUpdateControlLayout()
    }

    private fun applyEdgeStyleAndOrder() {
        if (isServiceDestroyed || !::rootControlContainer.isInitialized) return
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
        if (isServiceDestroyed || !::menuContainer.isInitialized) return
        isMenuExpanded = expanded
        menuContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        dockToCurrentEdge()
    }

    private fun updateIndicatorColor(hex: String) {
        if (isServiceDestroyed || !::edgeIndicator.isInitialized) return
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
        if (isServiceDestroyed ||
            !::rootControlContainer.isInitialized ||
            !rootControlContainer.isAttachedToWindow
        ) {
            return Rect(0, 0, 0, 0)
        }
        return CrashGuard.safeCall("OverlayService.controlRect", Rect(0, 0, 0, 0)) {
            val loc = IntArray(2)
            rootControlContainer.getLocationOnScreen(loc)
            val pad = dp(6)
            Rect(
                (loc[0] - pad).coerceAtLeast(0),
                (loc[1] - pad).coerceAtLeast(0),
                (loc[0] + rootControlContainer.width + pad).coerceAtMost(screenWidth),
                (loc[1] + rootControlContainer.height + pad).coerceAtMost(screenHeight)
            )
        }
    }

    private enum class OverlayAnimType { NONE, BLOCK_FADE, FULL_WAVE }

    private suspend fun updateOverlayBlocksIfCurrent(
        blocks: List<RecognizedBlock>,
        expectedGeneration: Long,
        animType: OverlayAnimType = OverlayAnimType.NONE
    ) {
        if (isServiceDestroyed || !::overlayCanvasView.isInitialized) return
        if (overlayGeneration.get() != expectedGeneration) return
        if (blocks.isEmpty()) {
            clearOverlayBlocks()
            return
        }
        val targetW = overlayCanvasView.width.takeIf { it > 0 } ?: screenWidth
        val targetH = overlayCanvasView.height.takeIf { it > 0 } ?: screenHeight
        val isTargetRtl = targetLang.isRtl
        val layouts = withContext(Dispatchers.Default) {
            CrashGuard.safeCall("OverlayService.computeLayout", emptyList()) {
                OverlayLayoutEngine.compute(
                    blocks = blocks,
                    canvasWidth = targetW,
                    canvasHeight = targetH,
                    isTargetRtl = isTargetRtl
                )
            }
        }
        if (isServiceDestroyed || overlayGeneration.get() != expectedGeneration) return
        overlayCanvasView.visibility = View.VISIBLE
        val effectiveAnim = if (enableScanWave) animType else OverlayAnimType.NONE
        overlayCanvasView.updateLayouts(
            newBlocks = blocks,
            newLayouts = layouts,
            animType = effectiveAnim
        )
    }

    private fun clearOverlayBlocks() {
        overlayGeneration.incrementAndGet()
        if (::overlayCanvasView.isInitialized) {
            overlayCanvasView.stopAnimations()
            overlayCanvasView.updateLayouts(emptyList(), emptyList(), animType = OverlayAnimType.NONE)
            overlayCanvasView.visibility = View.GONE
        }
    }

    private suspend fun refreshBaselineAfterOverlay(extraDelayMs: Long = 120L) {
        delay(extraDelayMs)
        if (isServiceDestroyed) return
        val helper = captureHelper ?: return
        val captured = helper.captureFrame(waitForFresh = false) ?: return
        try {
            withContext(Dispatchers.Default) {
                diffTracker.updateBaseline(captured.bitmap)
            }
            lastCheckedFrameVersion = helper.getFrameVersion()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CrashGuard.recordError("OverlayService.refreshBaseline", t)
        } finally {
            if (!captured.bitmap.isRecycled) captured.bitmap.recycle()
        }
    }

    private fun startScreenWatcher() {
        watcherJob?.cancel()
        watcherJob = serviceScope.launch {
            while (isActive && !isServiceDestroyed) {
                delay(if (isAutoMode) 300L else 420L)
                if (isBusy.get()) continue
                if (!::overlayCanvasView.isInitialized) continue
                if (!isAutoMode && !overlayCanvasView.hasBlocks()) continue

                val helper = captureHelper ?: continue
                if (!helper.isActive()) continue
                val version = helper.getFrameVersion()
                if (version == lastCheckedFrameVersion) continue
                lastCheckedFrameVersion = version

                checkScreenChanges()
            }
        }
    }

    private fun toggleAutoMode(enable: Boolean) {
        if (isServiceDestroyed || !::autoMenuButton.isInitialized) return
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
        val helper = captureHelper ?: return
        val captured = helper.captureFrame(waitForFresh = false) ?: return
        try {
            val controlRect = getControlScreenRect()
            val maskedRects = buildList {
                add(controlRect)
                addAll(overlayCanvasView.getRenderedRects())
            }
            val diff = withContext(Dispatchers.Default) {
                diffTracker.analyze(
                    frame = captured.bitmap,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    maskedRects = maskedRects,
                    topInset = statusBarHeight,
                    bottomInset = navBarHeight
                )
            }

            when (diff) {
                is DiffResult.Unchanged -> Unit

                is DiffResult.Partial -> {
                    if (isAutoMode) {
                        translateChangedRegion(captured, diff.dirtyRect)
                    }
                }

                is DiffResult.FullChange -> {
                    clearOverlayBlocks()
                    if (isAutoMode) {
                        waitForScreenToSettle()
                        if (isAutoMode && !isServiceDestroyed) {
                            runFullTranslation(showFeedback = false, hideOverlayFirst = false)
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CrashGuard.recordError("OverlayService.checkChanges", t)
        } finally {
            if (!captured.bitmap.isRecycled) captured.bitmap.recycle()
        }
    }

    private suspend fun waitForScreenToSettle() {
        val helper = captureHelper ?: return
        repeat(3) {
            delay(180L)
            if (isServiceDestroyed) return
            val probe = helper.captureFrame(waitForFresh = true) ?: return
            try {
                val controlRect = getControlScreenRect()
                val res = withContext(Dispatchers.Default) {
                    diffTracker.analyze(
                        frame = probe.bitmap,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        maskedRects = listOf(controlRect),
                        topInset = statusBarHeight,
                        bottomInset = navBarHeight
                    )
                }
                if (res is DiffResult.Unchanged) {
                    return
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                CrashGuard.recordError("OverlayService.waitSettle", t)
                return
            } finally {
                if (!probe.bitmap.isRecycled) probe.bitmap.recycle()
            }
        }
    }

    private suspend fun translateBlocksFiltered(blocks: List<RecognizedBlock>): List<RecognizedBlock> {
        val eligible = blocks.filterNot {
            translationEngine.shouldSkipText(
                rawText = it.text,
                source = sourceLang,
                target = targetLang,
                skipBrands = skipBrandNames
            )
        }
        if (eligible.isEmpty()) return emptyList()

        val translatedMap = translationEngine.translateBatch(
            texts = eligible.map { it.text },
            source = sourceLang,
            target = targetLang,
            offlineOnly = offlineOnlyMode
        )

        return eligible.mapNotNull { block ->
            val translated = translatedMap[block.text]?.trim().orEmpty()
            if (translationEngine.isValidResult(block.text, translated, targetLang)) {
                block.copy(translatedText = translated)
            } else {
                null
            }
        }
    }

    private suspend fun translateChangedRegion(currentCapture: CapturedFrame, rawDirtyRect: Rect) {
        if (isServiceDestroyed) return
        val helper = captureHelper ?: return
        if (!isBusy.compareAndSet(false, true)) return

        val gen = overlayGeneration.get()
        updateIndicatorColor("#F59E0B")

        try {
            val expandedScreenRect = EdgeCasePolicy.clampCropRect(
                rawRect = rawDirtyRect,
                padX = dp(24),
                padY = dp(16),
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                topInset = statusBarHeight,
                bottomInset = navBarHeight
            )

            val existingBlocks = overlayCanvasView.getBlocks()
            val overlapping = existingBlocks.filter { Rect.intersects(it.bounds, expandedScreenRect) }
            val preserved = existingBlocks.filterNot { Rect.intersects(it.bounds, expandedScreenRect) }

            var freshCapture: CapturedFrame? = null
            val activeCapture = if (overlapping.isNotEmpty()) {
                updateOverlayBlocksIfCurrent(preserved, gen, animType = OverlayAnimType.NONE)
                delay(90L)
                freshCapture = helper.captureFrame(waitForFresh = true)
                freshCapture ?: currentCapture
            } else {
                currentCapture
            }

            try {
                val srcBitmap = activeCapture.bitmap
                if (srcBitmap.isRecycled || srcBitmap.width <= 16 || srcBitmap.height <= 12) return

                val bmpLeft = (expandedScreenRect.left / activeCapture.scaleX).toInt()
                    .coerceIn(0, srcBitmap.width - 1)
                val bmpTop = (expandedScreenRect.top / activeCapture.scaleY).toInt()
                    .coerceIn(0, srcBitmap.height - 1)
                val bmpRight = (expandedScreenRect.right / activeCapture.scaleX).toInt()
                    .coerceIn(bmpLeft + 1, srcBitmap.width)
                val bmpBottom = (expandedScreenRect.bottom / activeCapture.scaleY).toInt()
                    .coerceIn(bmpTop + 1, srcBitmap.height)

                val safeW = bmpRight - bmpLeft
                val safeH = bmpBottom - bmpTop
                if (safeW < 16 || safeH < 12) return

                val cropped = Bitmap.createBitmap(srcBitmap, bmpLeft, bmpTop, safeW, safeH)
                try {
                    val detected = recognizerHelper.recognize(
                        bitmap = cropped,
                        targetLang = targetLang,
                        bitmapOffsetX = bmpLeft,
                        bitmapOffsetY = bmpTop,
                        scaleX = activeCapture.scaleX,
                        scaleY = activeCapture.scaleY,
                        minTopY = statusBarHeight,
                        maxBottomY = screenHeight - navBarHeight,
                        excludeRect = getControlScreenRect()
                    )
                    val translatedNew = translateBlocksFiltered(detected)
                    if (!isServiceDestroyed && overlayGeneration.get() == gen) {
                        val anim = if (translatedNew.isNotEmpty()) OverlayAnimType.BLOCK_FADE else OverlayAnimType.NONE
                        updateOverlayBlocksIfCurrent(preserved + translatedNew, gen, animType = anim)
                        refreshBaselineAfterOverlay(if (anim == OverlayAnimType.BLOCK_FADE && enableScanWave) 300L else 120L)
                    }
                } finally {
                    if (!cropped.isRecycled) cropped.recycle()
                }
            } finally {
                freshCapture?.bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CrashGuard.recordError("OverlayService.translateChangedRegion", t)
        } finally {
            updateIndicatorColor(if (isAutoMode) "#22C55E" else "#3B82F6")
            isBusy.set(false)
        }
    }

    private suspend fun runFullTranslation(showFeedback: Boolean, hideOverlayFirst: Boolean) {
        if (isServiceDestroyed) return
        val helper = captureHelper ?: return
        if (!isBusy.compareAndSet(false, true)) return

        val gen = overlayGeneration.incrementAndGet()
        if (::translateMenuButton.isInitialized) translateMenuButton.text = "סורק..."
        updateIndicatorColor("#F59E0B")

        try {
            val hadBlocks = ::overlayCanvasView.isInitialized && overlayCanvasView.hasBlocks()
            if (hideOverlayFirst && hadBlocks) {
                overlayCanvasView.stopAnimations()
                overlayCanvasView.visibility = View.GONE
                delay(90L)
            }

            val captured = helper.captureFrame(waitForFresh = true)
            if (captured == null) {
                if (hadBlocks && !isServiceDestroyed && overlayGeneration.get() == gen) {
                    overlayCanvasView.visibility = View.VISIBLE
                }
                if (showFeedback) {
                    safeToast("לא הצלחתי ללכוד את המסך, נסה שוב", Toast.LENGTH_SHORT)
                }
                return
            }

            try {
                val blocks = recognizerHelper.recognize(
                    bitmap = captured.bitmap,
                    targetLang = targetLang,
                    scaleX = captured.scaleX,
                    scaleY = captured.scaleY,
                    minTopY = statusBarHeight,
                    maxBottomY = screenHeight - navBarHeight,
                    excludeRect = getControlScreenRect()
                )

                val translatedBlocks = translateBlocksFiltered(blocks)
                if (isServiceDestroyed || overlayGeneration.get() != gen) return

                updateOverlayBlocksIfCurrent(
                    blocks = translatedBlocks,
                    expectedGeneration = gen,
                    animType = if (translatedBlocks.isNotEmpty()) OverlayAnimType.FULL_WAVE else OverlayAnimType.NONE
                )

                if (translatedBlocks.isEmpty()) {
                    withContext(Dispatchers.Default) {
                        diffTracker.updateBaseline(captured.bitmap)
                    }
                    lastCheckedFrameVersion = helper.getFrameVersion()
                    if (showFeedback) {
                        safeToast("לא נמצא טקסט מתאים ב${sourceLang.nameHe} לתרגום", Toast.LENGTH_SHORT)
                    }
                } else {
                    refreshBaselineAfterOverlay(if (enableScanWave) 640L else 120L)
                }
            } finally {
                if (!captured.bitmap.isRecycled) captured.bitmap.recycle()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CrashGuard.recordError("OverlayService.runFullTranslation", t)
            if (showFeedback) {
                safeToast("אירעה שגיאה בתרגום המסך", Toast.LENGTH_SHORT)
            }
        } finally {
            if (!isServiceDestroyed && ::translateMenuButton.isInitialized) {
                translateMenuButton.text = "תרגם מסך"
            }
            updateIndicatorColor(if (isAutoMode) "#22C55E" else "#3B82F6")
            isBusy.set(false)
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
            CrashGuard.safeRun("OverlayService.createChannel") {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.overlay_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val modeLabel = if (offlineOnlyMode) "לא-מקוון" else "היברידי"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("${sourceLang.nameHe} ← ${targetLang.nameHe} ($modeLabel)")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isServiceDestroyed = true
        _serviceRunningState.value = false
        watcherJob?.cancel()
        CrashGuard.safeRun("OverlayService.stopCapture") { captureHelper?.stop() }
        captureHelper = null
        recognizerHelper.close()
        if (::translationEngine.isInitialized) {
            translationEngine.close()
        }
        serviceScope.cancel()
        if (::overlayCanvasView.isInitialized) {
            overlayCanvasView.stopAnimations()
            CrashGuard.safeRun("OverlayService.removeCanvas") {
                if (overlayCanvasView.isAttachedToWindow) {
                    windowManager.removeView(overlayCanvasView)
                }
            }
        }
        if (::rootControlContainer.isInitialized) {
            CrashGuard.safeRun("OverlayService.removeControls") {
                if (rootControlContainer.isAttachedToWindow) {
                    windowManager.removeView(rootControlContainer)
                }
            }
        }
        super.onDestroy()
    }

    private sealed class DiffResult {
        data object Unchanged : DiffResult()
        data class Partial(val dirtyRect: Rect) : DiffResult()
        data object FullChange : DiffResult()
    }

    private class ScreenDiffTracker {
        private val thumbW = 48
        private val thumbH = 96
        private val tileSize = 8
        private var baselinePixels: IntArray? = null
        private val scratchPixels = IntArray(thumbW * thumbH)

        @Synchronized
        fun reset() {
            baselinePixels = null
        }

        @Synchronized
        fun updateBaseline(frame: Bitmap) {
            if (frame.isRecycled || frame.width <= 0 || frame.height <= 0) return
            if (extractInto(frame, scratchPixels)) {
                baselinePixels = scratchPixels.copyOf()
            }
        }

        @Synchronized
        fun analyze(
            frame: Bitmap,
            screenWidth: Int,
            screenHeight: Int,
            maskedRects: List<Rect>,
            topInset: Int,
            bottomInset: Int
        ): DiffResult {
            if (frame.isRecycled || frame.width <= 0 || frame.height <= 0) return DiffResult.Unchanged
            if (!extractInto(frame, scratchPixels)) return DiffResult.Unchanged

            val prev = baselinePixels
            if (prev == null || prev.size != scratchPixels.size) {
                baselinePixels = scratchPixels.copyOf()
                return DiffResult.FullChange
            }

            val scaleX = screenWidth.coerceAtLeast(1).toFloat() / thumbW
            val scaleY = screenHeight.coerceAtLeast(1).toFloat() / thumbH
            val minTy = (topInset / scaleY).toInt().coerceIn(0, thumbH - 1)
            val maxTy = ((screenHeight - bottomInset) / scaleY).toInt().coerceIn(minTy + 1, thumbH)

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
                            val c2 = scratchPixels[rowOffset + tx]
                            val delta = abs(Color.red(c1) - Color.red(c2)) +
                                abs(Color.green(c1) - Color.green(c2)) +
                                abs(Color.blue(c1) - Color.blue(c2))
                            if (delta > 54) {
                                diffPixels++
                            }
                        }
                    }

                    if (checkedPixels < 6) continue
                    totalActiveTiles++

                    if (diffPixels.toFloat() / checkedPixels > 0.16f) {
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

            System.arraycopy(scratchPixels, 0, prev, 0, scratchPixels.size)

            if (changedTiles == 0 || totalActiveTiles == 0) {
                return DiffResult.Unchanged
            }

            val changedRatio = changedTiles.toFloat() / totalActiveTiles
            return if (changedRatio >= 0.22f) {
                DiffResult.FullChange
            } else {
                DiffResult.Partial(
                    Rect(
                        minChangedLeft.coerceIn(0, screenWidth),
                        minChangedTop.coerceIn(0, screenHeight),
                        maxChangedRight.coerceIn(0, screenWidth),
                        maxChangedBottom.coerceIn(0, screenHeight)
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

        private fun extractInto(frame: Bitmap, target: IntArray): Boolean {
            return CrashGuard.safeCall("DiffTracker.extract", false) {
                val scaled = Bitmap.createScaledBitmap(frame, thumbW, thumbH, false)
                try {
                    scaled.getPixels(target, 0, thumbW, 0, 0, thumbW, thumbH)
                    true
                } finally {
                    if (scaled !== frame && !scaled.isRecycled) {
                        scaled.recycle()
                    }
                }
            }
        }
    }

    private data class RenderedBlockLayout(
        val rect: RectF,
        val angleDegrees: Float,
        val bgTopColor: Int,
        val bgBottomColor: Int,
        val layout: StaticLayout,
        val screenBounds: Rect
    )

    private object OverlayLayoutEngine {
        fun compute(
            blocks: List<RecognizedBlock>,
            canvasWidth: Int,
            canvasHeight: Int,
            isTargetRtl: Boolean
        ): List<RenderedBlockLayout> {
            if (blocks.isEmpty() || canvasWidth <= 20 || canvasHeight <= 20) return emptyList()

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

                val avgLineHeight = origH.toFloat() / block.lineCount.coerceAtLeast(1)
                var fontSize = (avgLineHeight * 0.78f).coerceIn(19f, 50f)

                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = block.textColor
                    textSize = fontSize
                    isFakeBoldText = fontSize <= 28f || block.needsTextShadow
                    if (block.needsTextShadow) {
                        setShadowLayer(2.5f, 0f, 1.5f, Color.argb(150, 0, 0, 0))
                    }
                }

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

                if (maxAllowedRight - minAllowedLeft < 40f) {
                    minAllowedLeft = 6f
                    maxAllowedRight = canvasWidth - 6f
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
                    layout = buildStaticLayout(text, paint, layoutWidth, Layout.Alignment.ALIGN_CENTER, isTargetRtl)

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
                        val top = (centerY - boxH / 2f).coerceIn(4f, (canvasHeight - 24f).coerceAtLeast(4f))
                        val bottom = (top + boxH).coerceIn(top + 12f, canvasHeight - 4f)
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

                    var fittedLayout = buildStaticLayout(text, paint, layoutWidth, Layout.Alignment.ALIGN_NORMAL, isTargetRtl)
                    while (fittedLayout.height > origH + padY && fontSize > 15f) {
                        fontSize -= 1.2f
                        paint.textSize = fontSize
                        fittedLayout = buildStaticLayout(text, paint, layoutWidth, Layout.Alignment.ALIGN_NORMAL, isTargetRtl)
                    }
                    layout = fittedLayout

                    val boxH = max(origH + padY * 2f, layout.height + padY * 2f)
                    val top = if (isRotated) {
                        block.bounds.exactCenterY() - boxH / 2f
                    } else {
                        (block.bounds.top - padY).coerceIn(4f, (canvasHeight - 24f).coerceAtLeast(4f))
                    }
                    val bottom = if (isRotated) {
                        top + boxH
                    } else {
                        (top + boxH).coerceIn(top + 12f, canvasHeight - 4f)
                    }
                    targetRect = RectF(left, top, right.coerceAtLeast(left + 28f), bottom)
                }

                results.add(
                    RenderedBlockLayout(
                        rect = targetRect,
                        angleDegrees = block.angleDegrees,
                        bgTopColor = block.bgTopColor or -0x1000000,
                        bgBottomColor = block.bgBottomColor or -0x1000000,
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
            alignment: Layout.Alignment,
            isTargetRtl: Boolean
        ): StaticLayout {
            val direction = if (isTargetRtl) {
                TextDirectionHeuristics.RTL
            } else {
                TextDirectionHeuristics.LTR
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
                .setAlignment(alignment)
                .setTextDirection(direction)
                .setIncludePad(false)
                .build()
        }
    }

    private class TranslationCanvasView(context: Context) : View(context) {

        private var blocks: List<RecognizedBlock> = emptyList()
        private var layouts: List<RenderedBlockLayout> = emptyList()
        private val effectController = OverlayEffectController { invalidate() }

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        fun hasBlocks(): Boolean = blocks.isNotEmpty()

        fun getBlocks(): List<RecognizedBlock> = blocks

        fun getRenderedRects(): List<Rect> = layouts.map { it.screenBounds }

        fun stopAnimations() {
            effectController.stop()
        }

        fun updateLayouts(
            newBlocks: List<RecognizedBlock>,
            newLayouts: List<RenderedBlockLayout>,
            animType: OverlayAnimType
        ) {
            blocks = newBlocks
            layouts = newLayouts
            when {
                newLayouts.isEmpty() || animType == OverlayAnimType.NONE -> {
                    effectController.stop()
                    invalidate()
                }
                animType == OverlayAnimType.FULL_WAVE -> effectController.startFullScanWave()
                animType == OverlayAnimType.BLOCK_FADE -> effectController.startBlockReveal()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 2 || height <= 2) return
            CrashGuard.safeRun("OverlayCanvas.onDraw") {
                val totalHeight = height.toFloat()

                for (item in layouts) {
                    val rect = item.rect
                    if (rect.width() <= 1f || rect.height() <= 1f) continue

                    val reveal = effectController.blockRevealProgress(rect.top, totalHeight)
                    if (reveal <= 0.01f) continue

                    val alphaInt = (255 * reveal).toInt().coerceIn(0, 255)
                    val popScale = effectController.blockScale(reveal)
                    val isRotated = abs(item.angleDegrees) >= 2.5f

                    if (item.bgTopColor != item.bgBottomColor && rect.bottom > rect.top + 1f) {
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
                    boxPaint.alpha = alphaInt
                    item.layout.paint.alpha = alphaInt

                    canvas.save()
                    if (isRotated) {
                        canvas.rotate(item.angleDegrees, rect.centerX(), rect.centerY())
                    }
                    if (popScale < 0.999f) {
                        canvas.scale(popScale, popScale, rect.centerX(), rect.centerY())
                    }

                    canvas.drawRoundRect(rect, 10f, 10f, boxPaint)
                    effectController.drawBlockHighlightIfAnimating(canvas, rect, reveal)

                    val textLeft = rect.left + (rect.width() - item.layout.width) / 2f
                    val textTop = rect.top + (rect.height() - item.layout.height) / 2f
                    canvas.translate(textLeft, textTop)
                    item.layout.draw(canvas)
                    canvas.restore()
                }

                effectController.drawScanWave(canvas, width.toFloat(), totalHeight)
            }
        }
    }

    companion object {
        private val _serviceRunningState = MutableStateFlow(false)
        val serviceRunningState: StateFlow<Boolean> = _serviceRunningState.asStateFlow()

        const val CHANNEL_ID = "screen_translator_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.screentranslator.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
        const val EXTRA_AUTO_MODE = "extra_auto_mode"
        const val EXTRA_OFFLINE_ONLY = "extra_offline_only"
        const val EXTRA_SKIP_BRANDS = "extra_skip_brands"
        const val EXTRA_SCAN_WAVE = "extra_scan_wave"
        const val EXTRA_SOURCE_LANG = "extra_source_lang"
        const val EXTRA_TARGET_LANG = "extra_target_lang"
    }
}
