package com.example.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private val BgObsidian = Color(0xFF0B0E17)
private val BgObsidianTop = Color(0xFF101526)
private val GlassCardBg = Color(0xD9151A28)
private val GlassCardInner = Color(0xFF0F1320)
private val GlassBorder = Color(0x14FFFFFF) // 8% white
private val ElectricBlue = Color(0xFF3B82F6)
private val ElectricBlueLight = Color(0xFF60A5FA)
private val ElectricCyan = Color(0xFF38BDF8)
private val GlowPurple = Color(0xFF7C3AED)
private val EmeraldGreen = Color(0xFF10B981)
private val AccentAmber = Color(0xFFF59E0B)
private val AccentRed = Color(0xFFEF4444)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)

private enum class AppTab(val label: String) {
    HOME("ראשי"),
    MODELS("מודלים"),
    TEST("בדיקה"),
    SETTINGS("הגדרות")
}

class MainActivity : ComponentActivity() {

    private val localTranslator = OfflineEnHeTranslator()

    private var currentTab by mutableStateOf(AppTab.HOME)
    private var hasOverlayPermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(true)
    private var isModelReady by mutableStateOf(false)
    private var isDownloadingModel by mutableStateOf(false)
    private var startInAutoMode by mutableStateOf(true)
    private var offlineOnlyMode by mutableStateOf(true)
    private var skipBrandNames by mutableStateOf(true)
    private var enableScanWave by mutableStateOf(true)
    private var statusMessage by mutableStateOf("מוכן להפעלה")

    private var sampleInput by mutableStateOf("Settings will be saved automatically when you exit.")
    private var sampleOutput by mutableStateOf("")
    private var isTestingTranslation by mutableStateOf(false)
    private var translationRevealKey by mutableIntStateOf(0)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStates()
            launchScreenCapturePrompt()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayService.EXTRA_DATA_INTENT, result.data)
                    putExtra(OverlayService.EXTRA_AUTO_MODE, startInAutoMode)
                    putExtra(OverlayService.EXTRA_OFFLINE_ONLY, offlineOnlyMode)
                    putExtra(OverlayService.EXTRA_SKIP_BRANDS, skipBrandNames)
                    putExtra(OverlayService.EXTRA_SCAN_WAVE, enableScanWave)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                statusMessage = "המתרגם הצף פעיל"
            } else {
                statusMessage = "הרשאת צילום המסך בוטלה"
            }
        }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0B0E17.toInt()
        window.navigationBarColor = 0xFF0B0E17.toInt()

        refreshStates()
        checkOfflineModel()

        setContent {
            val isServiceActive by OverlayService.serviceRunningState.collectAsState()

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BgObsidian,
                    surface = GlassCardBg,
                    primary = ElectricBlue
                )
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ScreenTranslatorAppShell(
                        currentTab = currentTab,
                        onTabSelected = { currentTab = it },
                        hasOverlayPermission = hasOverlayPermission,
                        hasNotificationPermission = hasNotificationPermission,
                        isModelReady = isModelReady,
                        isDownloadingModel = isDownloadingModel,
                        isServiceActive = isServiceActive,
                        startInAutoMode = startInAutoMode,
                        offlineOnlyMode = offlineOnlyMode,
                        skipBrandNames = skipBrandNames,
                        enableScanWave = enableScanWave,
                        statusMessage = statusMessage,
                        sampleInput = sampleInput,
                        sampleOutput = sampleOutput,
                        isTestingTranslation = isTestingTranslation,
                        translationRevealKey = translationRevealKey,
                        onSampleInputChange = { sampleInput = it },
                        onToggleAutoMode = { startInAutoMode = it },
                        onToggleOfflineOnly = { offlineOnlyMode = it },
                        onToggleSkipBrands = { skipBrandNames = it },
                        onToggleScanWave = { enableScanWave = it },
                        onDownloadModelClick = { downloadOfflineModel() },
                        onClearCacheClick = {
                            localTranslator.clearCache()
                            statusMessage = "המטמון הזמני נוקה"
                        },
                        onTestTranslationClick = { testTranslation() },
                        onGrantOverlayClick = { openOverlaySettings() },
                        onPowerButtonClick = {
                            if (isServiceActive) {
                                stopOverlayService()
                            } else {
                                startOverlayFlow()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    override fun onDestroy() {
        localTranslator.close()
        super.onDestroy()
    }

    private fun refreshStates() {
        hasOverlayPermission = Settings.canDrawOverlays(this)
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkOfflineModel() {
        lifecycleScope.launch {
            isModelReady = localTranslator.isModelDownloaded()
            if (isModelReady) {
                statusMessage = "מוכן"
            }
        }
    }

    private fun downloadOfflineModel() {
        if (isDownloadingModel) return
        isDownloadingModel = true
        statusMessage = "מוריד מודל לא-מקוון..."
        lifecycleScope.launch {
            val result = localTranslator.prepareModel()
            isDownloadingModel = false
            if (result.isSuccess) {
                isModelReady = true
                statusMessage = "המודל מאומת ומוכן"
            } else {
                isModelReady = false
                val err = result.exceptionOrNull()?.localizedMessage ?: "שגיאת רשת"
                statusMessage = "ההורדה נכשלה: $err"
            }
        }
    }

    private fun testTranslation() {
        if (isTestingTranslation) return
        isTestingTranslation = true
        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            if (offlineOnlyMode && !isModelReady) {
                val res = localTranslator.prepareModel()
                if (res.isFailure) {
                    sampleOutput = "יש להוריד קודם את המודל הלא-מקוון"
                    isTestingTranslation = false
                    translationRevealKey++
                    return@launch
                }
                isModelReady = true
            }
            val translated = localTranslator.translateEnglishToHebrew(
                text = sampleInput,
                offlineOnly = offlineOnlyMode
            )
            // Allow at least 350ms shimmer sweep as specified for crisp visual feedback
            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed < 350L) {
                delay(350L - elapsed)
            }
            sampleOutput = if (translated.isNotEmpty()) translated else "לא נמצא טקסט באנגלית לתרגום"
            isTestingTranslation = false
            translationRevealKey++
        }
    }

    private fun openOverlaySettings() {
        val packageIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching {
            startActivity(packageIntent)
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }
    }

    private fun startOverlayFlow() {
        if (!Settings.canDrawOverlays(this)) {
            statusMessage = "נדרש אישור תצוגה מעל אפליקציות"
            openOverlaySettings()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        launchScreenCapturePrompt()
    }

    private fun launchScreenCapturePrompt() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        statusMessage = "המתרגם הופסק"
    }
}

@Composable
private fun ScreenTranslatorAppShell(
    currentTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    hasOverlayPermission: Boolean,
    hasNotificationPermission: Boolean,
    isModelReady: Boolean,
    isDownloadingModel: Boolean,
    isServiceActive: Boolean,
    startInAutoMode: Boolean,
    offlineOnlyMode: Boolean,
    skipBrandNames: Boolean,
    enableScanWave: Boolean,
    statusMessage: String,
    sampleInput: String,
    sampleOutput: String,
    isTestingTranslation: Boolean,
    translationRevealKey: Int,
    onSampleInputChange: (String) -> Unit,
    onToggleAutoMode: (Boolean) -> Unit,
    onToggleOfflineOnly: (Boolean) -> Unit,
    onToggleSkipBrands: (Boolean) -> Unit,
    onToggleScanWave: (Boolean) -> Unit,
    onDownloadModelClick: () -> Unit,
    onClearCacheClick: () -> Unit,
    onTestTranslationClick: () -> Unit,
    onGrantOverlayClick: () -> Unit,
    onPowerButtonClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(BgObsidianTop, BgObsidian, BgObsidian)
                )
            )
    ) {
        // Subtle ambient top glow
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ElectricBlue.copy(alpha = 0.11f),
                        GlowPurple.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.20f),
                    radius = size.width * 0.78f
                ),
                center = Offset(size.width * 0.5f, size.height * 0.20f),
                radius = size.width * 0.78f
            )
        }

        // Main tab content
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(animationSpec = tween(160))
            },
            label = "TabContent",
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) { tab ->
            when (tab) {
                AppTab.HOME -> HomeTabScreen(
                    hasOverlayPermission = hasOverlayPermission,
                    isModelReady = isModelReady,
                    isServiceActive = isServiceActive,
                    startInAutoMode = startInAutoMode,
                    offlineOnlyMode = offlineOnlyMode,
                    skipBrandNames = skipBrandNames,
                    statusMessage = statusMessage,
                    onToggleAutoMode = onToggleAutoMode,
                    onToggleOfflineOnly = onToggleOfflineOnly,
                    onToggleSkipBrands = onToggleSkipBrands,
                    onPowerButtonClick = onPowerButtonClick,
                    onGrantOverlayClick = onGrantOverlayClick
                )

                AppTab.MODELS -> ModelsTabScreen(
                    isModelReady = isModelReady,
                    isDownloadingModel = isDownloadingModel,
                    statusMessage = statusMessage,
                    onDownloadModelClick = onDownloadModelClick,
                    onClearCacheClick = onClearCacheClick
                )

                AppTab.TEST -> TestTabScreen(
                    isModelReady = isModelReady,
                    isDownloadingModel = isDownloadingModel,
                    offlineOnlyMode = offlineOnlyMode,
                    sampleInput = sampleInput,
                    sampleOutput = sampleOutput,
                    isTestingTranslation = isTestingTranslation,
                    translationRevealKey = translationRevealKey,
                    onSampleInputChange = onSampleInputChange,
                    onDownloadModelClick = onDownloadModelClick,
                    onTestTranslationClick = onTestTranslationClick
                )

                AppTab.SETTINGS -> SettingsTabScreen(
                    hasOverlayPermission = hasOverlayPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    enableScanWave = enableScanWave,
                    skipBrandNames = skipBrandNames,
                    offlineOnlyMode = offlineOnlyMode,
                    onGrantOverlayClick = onGrantOverlayClick,
                    onToggleScanWave = onToggleScanWave,
                    onToggleSkipBrands = onToggleSkipBrands,
                    onToggleOfflineOnly = onToggleOfflineOnly
                )
            }
        }

        // Floating Glassmorphic Bottom Bar
        FloatingGlassBottomBar(
            selectedTab = currentTab,
            onTabSelected = onTabSelected,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun HomeTabScreen(
    hasOverlayPermission: Boolean,
    isModelReady: Boolean,
    isServiceActive: Boolean,
    startInAutoMode: Boolean,
    offlineOnlyMode: Boolean,
    skipBrandNames: Boolean,
    statusMessage: String,
    onToggleAutoMode: (Boolean) -> Unit,
    onToggleOfflineOnly: (Boolean) -> Unit,
    onToggleSkipBrands: (Boolean) -> Unit,
    onPowerButtonClick: () -> Unit,
    onGrantOverlayClick: () -> Unit
) {
    var autoCardFlash by remember { mutableIntStateOf(0) }
    var privacyCardFlash by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with compact status badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "מתרגם מסך",
                    color = TextPrimary,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "אנגלית ← עברית • פרטיות מלאה",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                StatusPillBadge(
                    label = when {
                        isServiceActive -> "פעיל"
                        isModelReady -> "מוכן"
                        else -> statusMessage
                    },
                    color = when {
                        isServiceActive -> EmeraldGreen
                        isModelReady -> EmeraldGreen
                        else -> AccentAmber
                    }
                )
            }

            StatusPillBadge(
                label = if (hasOverlayPermission) "מאושר" else "נדרש אישור",
                color = if (hasOverlayPermission) EmeraldGreen else AccentRed,
                modifier = Modifier.clickable {
                    if (!hasOverlayPermission) onGrantOverlayClick()
                }
            )
        }

        // Central Hero Activation Button with Breathing Rings
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            HeroPowerButton(
                isServiceActive = isServiceActive,
                onClick = onPowerButtonClick
            )
        }

        // Quick Toggle Card 1: Auto Change Detection
        GlassCard(flashTrigger = autoCardFlash) {
            GlassToggleRow(
                title = "זיהוי שינויים אוטומטי",
                subtitle = "מתרגם אוטומטית רק אזורים שהשתנו ומנקה במעבר מסך",
                checked = startInAutoMode,
                onCheckedChange = {
                    autoCardFlash++
                    onToggleAutoMode(it)
                }
            )
        }

        // Quick Toggle Card 2: Brands & Offline Privacy
        GlassCard(flashTrigger = privacyCardFlash) {
            GlassToggleRow(
                title = "דלג על שמות אפליקציות ומותגים",
                subtitle = "מונע תרגומים מילוליים כמו Gemini, Waze ו-Spotify",
                checked = skipBrandNames,
                onCheckedChange = {
                    privacyCardFlash++
                    onToggleSkipBrands(it)
                }
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                thickness = 1.dp,
                color = GlassBorder
            )
            GlassToggleRow(
                title = "מצב לא-מקוון בלבד (פרטיות מרבית)",
                subtitle = "פעיל = כל התרגום מתבצע מקומית במכשיר בלי שליחת תוכן מסך לרשת",
                checked = offlineOnlyMode,
                onCheckedChange = {
                    privacyCardFlash++
                    onToggleOfflineOnly(it)
                }
            )
        }
    }
}

@Composable
private fun ModelsTabScreen(
    isModelReady: Boolean,
    isDownloadingModel: Boolean,
    statusMessage: String,
    onDownloadModelClick: () -> Unit,
    onClearCacheClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "מודלי שפה",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "ניהול, הורדה ואימות של מודלים מקומיים",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        ModelGaugeCard(
            isModelReady = isModelReady,
            isDownloadingModel = isDownloadingModel,
            onDownloadModelClick = onDownloadModelClick
        )

        GlassCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "מטמון זיכרון מהיר",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "שומר זמנית מחרוזות מתורגמות בזיכרון הפנימי בלבד למהירות מיידית ומתנקה אוטומטית בסגירת השירות.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
                onClick = onClearCacheClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White.copy(alpha = 0.03f)
                )
            ) {
                Text("נקה מטמון זיכרון", color = TextPrimary, fontSize = 14.sp)
            }
            if (statusMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusMessage,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun TestTabScreen(
    isModelReady: Boolean,
    isDownloadingModel: Boolean,
    offlineOnlyMode: Boolean,
    sampleInput: String,
    sampleOutput: String,
    isTestingTranslation: Boolean,
    translationRevealKey: Int,
    onSampleInputChange: (String) -> Unit,
    onDownloadModelClick: () -> Unit,
    onTestTranslationClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "מודל ובדיקת איכות",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "אנגלית ← עברית • בדיקה והורדה",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        ModelGaugeCard(
            isModelReady = isModelReady,
            isDownloadingModel = isDownloadingModel,
            onDownloadModelClick = onDownloadModelClick
        )

        // Sandbox Quality Test Card
        GlassCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "בדיקת איכות תרגום",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                StatusPillBadge(
                    label = if (offlineOnlyMode) "מקומי" else "היברידי",
                    color = ElectricCyan
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = sampleInput,
                    onValueChange = onSampleInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("Enter English text to test...", color = TextSecondary)
                    },
                    singleLine = false,
                    minLines = 2,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = GlassCardInner,
                        unfocusedContainerColor = GlassCardInner,
                        focusedBorderColor = ElectricBlue.copy(alpha = 0.7f),
                        unfocusedBorderColor = GlassBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = ElectricBlue
                    )
                )
            }

            // Glowing Directional Arrow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                GlowingDownArrow(active = isTestingTranslation || sampleOutput.isNotEmpty())
            }

            // Live Result Box with Shimmer & Staggered RTL Reveal
            ShimmerTranslationResultBox(
                isLoading = isTestingTranslation,
                translatedText = sampleOutput,
                revealKey = translationRevealKey
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onTestTranslationClick,
                enabled = !isTestingTranslation,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricBlue,
                    disabledContainerColor = ElectricBlue.copy(alpha = 0.5f)
                )
            ) {
                if (isTestingTranslation) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("מתרגם...", color = Color.White, fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        text = "בדוק תרגום לעברית",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTabScreen(
    hasOverlayPermission: Boolean,
    hasNotificationPermission: Boolean,
    enableScanWave: Boolean,
    skipBrandNames: Boolean,
    offlineOnlyMode: Boolean,
    onGrantOverlayClick: () -> Unit,
    onToggleScanWave: (Boolean) -> Unit,
    onToggleSkipBrands: (Boolean) -> Unit,
    onToggleOfflineOnly: (Boolean) -> Unit
) {
    var visualCardFlash by remember { mutableIntStateOf(0) }
    var filterCardFlash by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "הגדרות",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "הרשאות, אפקטים והתאמת שכבת התרגום",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        // Permissions Card
        GlassCard {
            Text(
                text = "הרשאות מערכת",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("תצוגה מעל אפליקציות", color = TextPrimary, fontSize = 14.sp)
                    Text(
                        "נדרש להצגת התרגום ולשונית הצד מעל מסכים אחרים",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                StatusPillBadge(
                    label = if (hasOverlayPermission) "מאושר" else "חסר",
                    color = if (hasOverlayPermission) EmeraldGreen else AccentRed
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("התראת שירות פעיל", color = TextPrimary, fontSize = 14.sp)
                    Text(
                        "מציגה חיווי קבוע כשהמתרגם רץ ברקע",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                StatusPillBadge(
                    label = if (hasNotificationPermission) "מאושר" else "חסר",
                    color = if (hasNotificationPermission) EmeraldGreen else AccentAmber
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
                onClick = onGrantOverlayClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = ElectricBlue.copy(alpha = 0.08f)
                )
            ) {
                Text(
                    text = if (hasOverlayPermission) "פתח הגדרות תצוגה צפה" else "אשר תצוגה מעל אפליקציות",
                    color = ElectricBlueLight,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Animations & Visuals Card
        GlassCard(flashTrigger = visualCardFlash) {
            Text(
                text = "אנימציות שכבת התרגום",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            GlassToggleRow(
                title = "גל סריקה כחול-טורקיז",
                subtitle = "פס אור שוטף את המסך וחושף את התרגום ב-Crossfade כשהגל עובר",
                checked = enableScanWave,
                onCheckedChange = {
                    visualCardFlash++
                    onToggleScanWave(it)
                }
            )
        }

        // Engine & Filter Preferences
        GlassCard(flashTrigger = filterCardFlash) {
            Text(
                text = "סינון ופרטיות",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            GlassToggleRow(
                title = "החרגת שמות אפליקציות ומותגים",
                subtitle = "שומר על שמות כמו WhatsApp, Spotify, Waze ו-Gemini ללא תרגום מילולי",
                checked = skipBrandNames,
                onCheckedChange = {
                    filterCardFlash++
                    onToggleSkipBrands(it)
                }
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                thickness = 1.dp,
                color = GlassBorder
            )
            GlassToggleRow(
                title = "מצב לא-מקוון בלבד",
                subtitle = "מתרגם מקומית על המכשיר ללא חיבור לשרת חיצוני",
                checked = offlineOnlyMode,
                onCheckedChange = {
                    filterCardFlash++
                    onToggleOfflineOnly(it)
                }
            )
        }
    }
}

@Composable
private fun HeroPowerButton(
    isServiceActive: Boolean,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "HeroPressScale"
    )

    val rippleProgress = remember { Animatable(1f) }
    var clickPulseCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(clickPulseCount) {
        if (clickPulseCount > 0) {
            rippleProgress.snapTo(0f)
            rippleProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing)
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "HeroBreathing")
    val ring1Progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Ring1"
    )
    val ring2Progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2200,
                delayMillis = 700,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "Ring2"
    )

    val coreStartColor by animateColorAsState(
        targetValue = if (isServiceActive) Color(0xFF10B981) else Color(0xFF60A5FA),
        animationSpec = tween(320),
        label = "CoreStart"
    )
    val coreEndColor by animateColorAsState(
        targetValue = if (isServiceActive) Color(0xFF047857) else Color(0xFF2563EB),
        animationSpec = tween(320),
        label = "CoreEnd"
    )
    val glowColor by animateColorAsState(
        targetValue = if (isServiceActive) EmeraldGreen else ElectricBlue,
        animationSpec = tween(320),
        label = "GlowColor"
    )

    Box(
        modifier = Modifier.size(226.dp),
        contentAlignment = Alignment.Center
    ) {
        // Breathing concentric rings + subtle purple/blue halo + click ripple
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.34f

            // Constant radial halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = if (isServiceActive) 0.42f else 0.34f),
                        GlowPurple.copy(alpha = if (isServiceActive) 0.08f else 0.14f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension * 0.50f
                ),
                center = center,
                radius = size.minDimension * 0.50f
            )

            // Static subtle outer glass rings
            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = baseRadius * 1.18f,
                center = center,
                style = Stroke(width = 1.2.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.04f),
                radius = baseRadius * 1.36f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // Breathing ring 1 (Scale 1.0 -> 1.12+, Alpha 0.4 -> 0)
            val r1Scale = 1.0f + (ring1Progress * 0.36f)
            val r1Alpha = (1f - ring1Progress) * 0.40f
            drawCircle(
                color = glowColor.copy(alpha = r1Alpha),
                radius = baseRadius * r1Scale,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            // Breathing ring 2
            val r2Scale = 1.0f + (ring2Progress * 0.36f)
            val r2Alpha = (1f - ring2Progress) * 0.28f
            drawCircle(
                color = ElectricCyan.copy(alpha = r2Alpha),
                radius = baseRadius * r2Scale,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Expanding press ripple
            if (rippleProgress.value < 0.99f) {
                val rp = rippleProgress.value
                drawCircle(
                    color = Color.White.copy(alpha = (1f - rp) * 0.55f),
                    radius = baseRadius * (1.0f + rp * 0.48f),
                    center = center,
                    style = Stroke(width = (3.5f * (1f - rp) + 1f).dp.toPx())
                )
            }
        }

        // Main circular button core
        Box(
            modifier = Modifier
                .size(152.dp)
                .scale(pressScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(coreStartColor, coreEndColor),
                        center = Offset(140f, 110f),
                        radius = 290f
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.50f),
                            Color.White.copy(alpha = 0.12f)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    clickPulseCount++
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(12.dp)
            ) {
                PowerTranslateGlyph(
                    isActive = isServiceActive,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isServiceActive) "עצור מתרגם מסך" else "הפעל מתרגם מסך",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PowerTranslateGlyph(isActive: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tint = Color(0xFF0A1628).copy(alpha = 0.85f)

        // Small power symbol on top
        val powerCenter = Offset(w * 0.5f, h * 0.22f)
        val powerRadius = w * 0.11f
        drawArc(
            color = tint,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(powerCenter.x - powerRadius, powerCenter.y - powerRadius),
            size = Size(powerRadius * 2f, powerRadius * 2f),
            style = stroke
        )
        drawLine(
            color = tint,
            start = Offset(powerCenter.x, powerCenter.y - powerRadius * 1.25f),
            end = Offset(powerCenter.x, powerCenter.y + powerRadius * 0.1f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )

        if (isActive) {
            // Clean stop square below power icon when active
            val sqSize = w * 0.30f
            drawRoundRect(
                color = tint,
                topLeft = Offset((w - sqSize) / 2f, h * 0.52f),
                size = Size(sqSize, sqSize),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            )
        } else {
            // Dual overlapping translation tiles
            val tileW = w * 0.34f
            val tileH = h * 0.34f
            // Left tile
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.17f, h * 0.44f),
                size = Size(tileW, tileH),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = stroke
            )
            // Right tile
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.49f, h * 0.56f),
                size = Size(tileW, tileH),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = stroke
            )
            // Stylized 'A' in right tile
            val aPath = Path().apply {
                moveTo(w * 0.58f, h * 0.82f)
                lineTo(w * 0.66f, h * 0.64f)
                lineTo(w * 0.74f, h * 0.82f)
                moveTo(w * 0.61f, h * 0.76f)
                lineTo(w * 0.71f, h * 0.76f)
            }
            drawPath(aPath, color = tint, style = stroke)

            // Stylized Hebrew 'א' lines in left tile
            val alefPath = Path().apply {
                moveTo(w * 0.25f, h * 0.52f)
                lineTo(w * 0.42f, h * 0.70f)
                moveTo(w * 0.41f, h * 0.52f)
                lineTo(w * 0.36f, h * 0.61f)
                moveTo(w * 0.26f, h * 0.70f)
                lineTo(w * 0.31f, h * 0.61f)
            }
            drawPath(alefPath, color = tint, style = stroke)
        }
    }
}

@Composable
private fun ModelGaugeCard(
    isModelReady: Boolean,
    isDownloadingModel: Boolean,
    onDownloadModelClick: () -> Unit
) {
    GlassCard {
        Text(
            text = "מודל תרגום לא-מקוון (EN ← HE)",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            CircularModelGauge(
                isReady = isModelReady,
                isDownloading = isDownloadingModel
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = when {
                        isDownloadingModel -> "מוריד את מודל השפה המקומי למכשיר..."
                        isModelReady -> "עובד מקומית במהירות ללא אינטרנט"
                        else -> "הורדה חד-פעמית (כ-30MB) להפעלת תרגום ללא רשת"
                    },
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = onDownloadModelClick,
            enabled = !isDownloadingModel,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(23.dp),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = if (isModelReady) Color.White.copy(alpha = 0.22f) else ElectricBlue
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isModelReady) {
                    Color.White.copy(alpha = 0.04f)
                } else {
                    ElectricBlue.copy(alpha = 0.16f)
                }
            )
        ) {
            Text(
                text = when {
                    isDownloadingModel -> "מוריד מודל..."
                    isModelReady -> "אמת מודל"
                    else -> "הורד מודל לא-מקוון"
                },
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CircularModelGauge(
    isReady: Boolean,
    isDownloading: Boolean
) {
    val targetSweep = if (isReady) 360f else 0f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "GaugeSweep"
    )
    val infinite = rememberInfiniteTransition(label = "GaugeSpin")
    val spinAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SpinAngle"
    )

    val ringColor = when {
        isDownloading -> ElectricCyan
        isReady -> EmeraldGreen
        else -> AccentAmber
    }

    Box(
        modifier = Modifier.size(102.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 7.dp.toPx()
            val inset = strokePx + 4.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)

            // Soft emerald/cyan halo
            if (isReady || isDownloading) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ringColor.copy(alpha = 0.24f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension * 0.52f
                    ),
                    radius = size.minDimension * 0.52f,
                    center = center
                )
            }

            // Track
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx)
            )

            // Active Arc
            if (isDownloading) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            ElectricCyan,
                            ElectricBlue
                        )
                    ),
                    startAngle = spinAngle,
                    sweepAngle = 210f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            } else {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = animatedSweep.coerceAtLeast(if (isReady) 360f else 20f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    isDownloading -> "..."
                    isReady -> "100%"
                    else -> "0%"
                },
                color = if (isReady) EmeraldGreen else TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when {
                    isDownloading -> "מוריד"
                    isReady -> "מוכן"
                    else -> "לא הורד"
                },
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun GlowingDownArrow(active: Boolean) {
    val glowAlpha by animateFloatAsState(
        targetValue = if (active) 0.45f else 0.18f,
        animationSpec = tween(250),
        label = "ArrowGlow"
    )
    Canvas(modifier = Modifier.size(34.dp)) {
        val cx = size.width / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(ElectricCyan.copy(alpha = glowAlpha), Color.Transparent),
                center = Offset(cx, size.height / 2f),
                radius = size.minDimension * 0.55f
            ),
            radius = size.minDimension * 0.55f,
            center = Offset(cx, size.height / 2f)
        )
        val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arrowPath = Path().apply {
            moveTo(cx, size.height * 0.18f)
            lineTo(cx, size.height * 0.78f)
            moveTo(cx - size.width * 0.22f, size.height * 0.56f)
            lineTo(cx, size.height * 0.80f)
            lineTo(cx + size.width * 0.22f, size.height * 0.56f)
        }
        drawPath(arrowPath, color = ElectricBlueLight, style = stroke)
    }
}

@Composable
private fun ShimmerTranslationResultBox(
    isLoading: Boolean,
    translatedText: String,
    revealKey: Int
) {
    val infinite = rememberInfiniteTransition(label = "ResultShimmer")
    val shimmerPhase by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerPhase"
    )

    val borderBrush = if (isLoading || translatedText.isNotEmpty()) {
        Brush.horizontalGradient(
            colors = listOf(
                ElectricCyan.copy(alpha = 0.55f),
                ElectricBlue.copy(alpha = 0.25f),
                GlowPurple.copy(alpha = 0.45f)
            )
        )
    } else {
        Brush.horizontalGradient(colors = listOf(GlassBorder, GlassBorder))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassCardInner)
            .border(1.dp, borderBrush, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.04f),
                                ElectricCyan.copy(alpha = 0.30f),
                                Color.White.copy(alpha = 0.04f)
                            ),
                            start = Offset(shimmerPhase * 420f, 0f),
                            end = Offset((shimmerPhase + 0.8f) * 420f, 80f)
                        )
                    )
            )
        } else if (translatedText.isEmpty()) {
            Text(
                text = "התרגום לעברית יופיע כאן בזמן אמת",
                color = TextSecondary.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
        } else {
            AnimatedContent(
                targetState = revealKey to translatedText,
                transitionSpec = {
                    (fadeIn(tween(250, easing = FastOutSlowInEasing)) +
                        slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { it / 6 }) togetherWith
                        fadeOut(tween(120))
                },
                label = "RtlReveal"
            ) { (_, text) ->
                Text(
                    text = text,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
private fun FloatingGlassBottomBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = AppTab.entries
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
    val view = LocalView.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xE8131826))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(32.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        val tabWidth = maxWidth / tabs.size
        val spotlightOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex,
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
            label = "SpotlightOffset"
        )

        // Sliding Spotlight Glow under the active tab
        Box(
            modifier = Modifier
                .offset(x = spotlightOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            ElectricBlue.copy(alpha = 0.36f),
                            ElectricBlue.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        radius = 150f
                    )
                )
                .border(
                    width = 1.dp,
                    color = ElectricBlueLight.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(24.dp)
                )
        )

        Row(modifier = Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val isSelected = tab == selectedTab
                val iconJumpY by animateDpAsState(
                    targetValue = if (isSelected) (-2).dp else 0.dp,
                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                    label = "IconJump"
                )
                val itemColor by animateColorAsState(
                    targetValue = if (isSelected) ElectricBlueLight else TextSecondary,
                    animationSpec = tween(220),
                    label = "TabTint"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (!isSelected) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onTabSelected(tab)
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    NavTabIcon(
                        tab = tab,
                        filled = isSelected,
                        tint = itemColor,
                        modifier = Modifier
                            .offset(y = iconJumpY)
                            .size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        color = if (isSelected) TextPrimary else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun NavTabIcon(
    tab: AppTab,
    filled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (tab) {
            AppTab.HOME -> {
                val house = Path().apply {
                    moveTo(w * 0.5f, h * 0.14f)
                    lineTo(w * 0.88f, h * 0.46f)
                    lineTo(w * 0.80f, h * 0.46f)
                    lineTo(w * 0.80f, h * 0.86f)
                    lineTo(w * 0.20f, h * 0.86f)
                    lineTo(w * 0.20f, h * 0.46f)
                    lineTo(w * 0.12f, h * 0.46f)
                    close()
                }
                if (filled) {
                    drawPath(house, color = tint)
                    drawRoundRect(
                        color = BgObsidian,
                        topLeft = Offset(w * 0.42f, h * 0.58f),
                        size = Size(w * 0.16f, h * 0.28f),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                } else {
                    drawPath(house, color = tint, style = stroke)
                }
            }

            AppTab.MODELS -> {
                if (filled) {
                    drawRoundRect(
                        color = tint.copy(alpha = 0.55f),
                        topLeft = Offset(w * 0.16f, h * 0.14f),
                        size = Size(w * 0.48f, h * 0.56f),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(w * 0.36f, h * 0.30f),
                        size = Size(w * 0.48f, h * 0.56f),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                } else {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(w * 0.16f, h * 0.14f),
                        size = Size(w * 0.46f, h * 0.54f),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        style = stroke
                    )
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(w * 0.38f, h * 0.32f),
                        size = Size(w * 0.46f, h * 0.54f),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        style = stroke
                    )
                }
            }

            AppTab.TEST -> {
                val radius = w * 0.38f
                val center = Offset(w / 2f, h / 2f)
                if (filled) {
                    drawCircle(color = tint, radius = radius, center = center)
                } else {
                    drawCircle(color = tint, radius = radius, center = center, style = stroke)
                }
                val check = Path().apply {
                    moveTo(w * 0.34f, h * 0.51f)
                    lineTo(w * 0.46f, h * 0.63f)
                    lineTo(w * 0.68f, h * 0.38f)
                }
                drawPath(
                    check,
                    color = if (filled) BgObsidian else tint,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            AppTab.SETTINGS -> {
                val center = Offset(w / 2f, h / 2f)
                val outerR = w * 0.39f
                val innerR = w * 0.27f
                for (i in 0 until 8) {
                    val angle = Math.toRadians(i * 45.0)
                    val sx = center.x + (innerR * cos(angle)).toFloat()
                    val sy = center.y + (innerR * sin(angle)).toFloat()
                    val ex = center.x + (outerR * cos(angle)).toFloat()
                    val ey = center.y + (outerR * sin(angle)).toFloat()
                    drawLine(
                        color = tint,
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = 2.4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                if (filled) {
                    drawCircle(color = tint, radius = w * 0.27f, center = center)
                    drawCircle(color = BgObsidian, radius = w * 0.11f, center = center)
                } else {
                    drawCircle(color = tint, radius = w * 0.25f, center = center, style = stroke)
                    drawCircle(color = tint, radius = w * 0.09f, center = center, style = stroke)
                }
            }
        }
    }
}

@Composable
private fun GlassCard(
    flashTrigger: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(flashTrigger) {
        if (flashTrigger > 0) {
            flashAlpha.snapTo(0.85f)
            flashAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
            )
        }
    }

    val borderColor = Color(
        red = lerpFloat(1f, ElectricBlueLight.red, flashAlpha.value),
        green = lerpFloat(1f, ElectricBlueLight.green, flashAlpha.value),
        blue = lerpFloat(1f, ElectricBlueLight.blue, flashAlpha.value),
        alpha = lerpFloat(0.08f, 0.72f, flashAlpha.value)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(GlassCardBg)
            .border(1.dp, borderColor, RoundedCornerShape(22.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun GlassToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val view = LocalView.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onCheckedChange(!checked)
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 14.dp)
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        ObsidianToggleSwitch(
            checked = checked,
            onCheckedChange = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onCheckedChange(it)
            }
        )
    }
}

@Composable
private fun ObsidianToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) ElectricBlue else Color(0xFF23293A),
        animationSpec = tween(durationMillis = 200),
        label = "SwitchTrack"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF3B1F9E) else Color(0xFF94A3B8),
        animationSpec = tween(durationMillis = 200),
        label = "SwitchThumb"
    )
    val thumbOffset: Dp by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "SwitchOffset"
    )

    Box(
        modifier = Modifier
            .width(50.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(trackColor)
            .border(
                width = 1.dp,
                color = if (checked) ElectricBlueLight.copy(alpha = 0.55f) else GlassBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(22.dp)
                .clip(CircleShape)
                .background(thumbColor)
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
        )
    }
}

@Composable
private fun StatusPillBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 11.dp, vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}
