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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
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

private enum class PickerMode {
    NONE,
    SOURCE,
    TARGET
}

class MainActivity : ComponentActivity() {

    private lateinit var translationEngine: ScreenTranslationEngine
    private lateinit var segmentedDownloader: SegmentedModelDownloader
    private val prefs by lazy { getSharedPreferences("screen_translator_prefs", Context.MODE_PRIVATE) }

    private var currentTab by mutableStateOf(AppTab.HOME)
    private var pickerMode by mutableStateOf(PickerMode.NONE)

    private var sourceLang by mutableStateOf(LanguageCatalog.defaultSource)
    private var targetLang by mutableStateOf(LanguageCatalog.defaultTarget)
    private var downloadedLangCodes by mutableStateOf<Set<String>>(emptySet())

    private var hasOverlayPermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(true)
    private var isPairModelReady by mutableStateOf(false)
    private var isDownloadingMlKit by mutableStateOf(false)
    private var mlKitDownloadProgress by mutableStateOf(0f)
    private var mlKitDownloadMessage by mutableStateOf("")

    private var fastPackUrlInput by mutableStateOf("")
    private var fastPackEntryCount by mutableIntStateOf(0)

    private var startInAutoMode by mutableStateOf(true)
    private var offlineOnlyMode by mutableStateOf(true)
    private var skipBrandNames by mutableStateOf(true)
    private var enableScanWave by mutableStateOf(true)
    private var statusMessage by mutableStateOf("מוכן להפעלה")

    private var sampleInput by mutableStateOf(LanguageCatalog.defaultSource.samplePhrase)
    private var sampleOutput by mutableStateOf("")
    private var isTestingTranslation by mutableStateOf(false)
    private var translationRevealKey by mutableIntStateOf(0)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionStates()
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
                    putExtra(OverlayService.EXTRA_SOURCE_LANG, sourceLang.code)
                    putExtra(OverlayService.EXTRA_TARGET_LANG, targetLang.code)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                statusMessage = "פעיל (${sourceLang.nameHe} ← ${targetLang.nameHe})"
            } else {
                statusMessage = "הרשאת צילום המסך בוטלה"
            }
        }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0B0E17.toInt()
        window.navigationBarColor = 0xFF0B0E17.toInt()

        translationEngine = ScreenTranslationEngine(applicationContext)
        segmentedDownloader = SegmentedModelDownloader(applicationContext)

        loadPreferences()
        refreshPermissionStates()
        refreshModelStates()

        setContent {
            val isServiceActive by OverlayService.serviceRunningState.collectAsState()
            val downloadStates by segmentedDownloader.progressMap.collectAsState()
            val pairValidation = EdgeCasePolicy.validateLanguagePair(sourceLang, targetLang)
            val supportedModels = remember(sourceLang, targetLang) {
                LanguageCatalog.getSupportedModels(sourceLang, targetLang)
            }

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
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        pairValidation = pairValidation,
                        supportedModels = supportedModels,
                        downloadedLangCodes = downloadedLangCodes,
                        downloadStates = downloadStates,
                        hasOverlayPermission = hasOverlayPermission,
                        hasNotificationPermission = hasNotificationPermission,
                        isPairModelReady = isPairModelReady,
                        isDownloadingMlKit = isDownloadingMlKit,
                        mlKitDownloadProgress = mlKitDownloadProgress,
                        mlKitDownloadMessage = mlKitDownloadMessage,
                        fastPackUrlInput = fastPackUrlInput,
                        fastPackEntryCount = fastPackEntryCount,
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
                        onOpenSourcePicker = { pickerMode = PickerMode.SOURCE },
                        onOpenTargetPicker = { pickerMode = PickerMode.TARGET },
                        onSwapLanguages = { swapLanguages() },
                        onFastPackUrlChange = { fastPackUrlInput = it },
                        onDownloadMlKitClick = { downloadCurrentMlKitModel() },
                        onDeleteMlKitTargetClick = { deleteTargetMlKitModel() },
                        onDownloadFastPackClick = { modelId -> start60PartDownload(modelId) },
                        onCancelFastPackClick = { modelId -> segmentedDownloader.cancel(modelId) },
                        onDeleteFastPackClick = { modelId -> deleteFastPack(modelId) },
                        onSampleInputChange = { sampleInput = it },
                        onResetSamplePhrase = {
                            sampleInput = sourceLang.samplePhrase
                            sampleOutput = ""
                        },
                        onToggleAutoMode = {
                            startInAutoMode = it
                            savePreferences()
                        },
                        onToggleOfflineOnly = {
                            offlineOnlyMode = it
                            savePreferences()
                        },
                        onToggleSkipBrands = {
                            skipBrandNames = it
                            savePreferences()
                        },
                        onToggleScanWave = {
                            enableScanWave = it
                            savePreferences()
                        },
                        onClearCacheClick = {
                            translationEngine.clearCache()
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

                    if (pickerMode != PickerMode.NONE) {
                        LanguagePickerDialog(
                            titleHe = if (pickerMode == PickerMode.SOURCE) {
                                "בחר שפת מקור"
                            } else {
                                "בחר שפת יעד"
                            },
                            selectedCode = if (pickerMode == PickerMode.SOURCE) {
                                sourceLang.code
                            } else {
                                targetLang.code
                            },
                            downloadedCodes = downloadedLangCodes,
                            isSourceSelection = pickerMode == PickerMode.SOURCE,
                            onDismiss = { pickerMode = PickerMode.NONE },
                            onSelect = { chosen ->
                                if (pickerMode == PickerMode.SOURCE) {
                                    applyLanguagePair(
                                        newSource = chosen,
                                        newTarget = if (chosen.code == targetLang.code) sourceLang else targetLang
                                    )
                                } else {
                                    applyLanguagePair(
                                        newSource = if (chosen.code == sourceLang.code) targetLang else sourceLang,
                                        newTarget = chosen
                                    )
                                }
                                pickerMode = PickerMode.NONE
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    override fun onDestroy() {
        if (::translationEngine.isInitialized) {
            translationEngine.close()
        }
        super.onDestroy()
    }

    private fun loadPreferences() {
        sourceLang = LanguageCatalog.findByCode(
            prefs.getString("source_lang", LanguageCatalog.defaultSource.code)
        )
        targetLang = LanguageCatalog.findByCode(
            prefs.getString("target_lang", LanguageCatalog.defaultTarget.code)
        )
        if (sourceLang.code == targetLang.code) {
            sourceLang = LanguageCatalog.defaultSource
            targetLang = LanguageCatalog.defaultTarget
        }
        startInAutoMode = prefs.getBoolean("auto_mode", true)
        offlineOnlyMode = prefs.getBoolean("offline_only", true)
        skipBrandNames = prefs.getBoolean("skip_brands", true)
        enableScanWave = prefs.getBoolean("scan_wave", true)
        sampleInput = sourceLang.samplePhrase
    }

    private fun savePreferences() {
        prefs.edit()
            .putString("source_lang", sourceLang.code)
            .putString("target_lang", targetLang.code)
            .putBoolean("auto_mode", startInAutoMode)
            .putBoolean("offline_only", offlineOnlyMode)
            .putBoolean("skip_brands", skipBrandNames)
            .putBoolean("scan_wave", enableScanWave)
            .apply()
    }

    private fun applyLanguagePair(newSource: LanguageOption, newTarget: LanguageOption) {
        if (newSource.code == newTarget.code) return
        sourceLang = newSource
        targetLang = newTarget
        sampleInput = newSource.samplePhrase
        sampleOutput = ""
        mlKitDownloadProgress = 0f
        mlKitDownloadMessage = ""
        savePreferences()
        refreshModelStates()
    }

    private fun swapLanguages() {
        applyLanguagePair(newSource = targetLang, newTarget = sourceLang)
    }

    private fun refreshPermissionStates() {
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

    private fun refreshModelStates() {
        val currentSrc = sourceLang
        val currentTgt = targetLang
        lifecycleScope.launch {
            downloadedLangCodes = translationEngine.getDownloadedLanguageCodes()
            val ready = translationEngine.isPairModelDownloaded(currentSrc, currentTgt)
            if (currentSrc.code == sourceLang.code && currentTgt.code == targetLang.code) {
                isPairModelReady = ready
                if (ready) {
                    mlKitDownloadProgress = 1f
                    statusMessage = "מוכן"
                } else {
                    mlKitDownloadProgress = 0f
                    statusMessage = "מודל לא הורד"
                }
                fastPackEntryCount = translationEngine.getOrLoadFastPack(currentSrc, currentTgt).size
            }
        }
    }

    private fun downloadCurrentMlKitModel() {
        if (isDownloadingMlKit) return
        val currentSrc = sourceLang
        val currentTgt = targetLang
        isDownloadingMlKit = true
        mlKitDownloadProgress = 0.05f
        mlKitDownloadMessage = "מתחיל הורדת ${currentSrc.nameHe} ← ${currentTgt.nameHe}..."
        statusMessage = "מוריד מודל..."

        lifecycleScope.launch {
            val result = translationEngine.prepareModel(
                source = currentSrc,
                target = currentTgt,
                onProgress = { progress, msg ->
                    runOnUiThread {
                        mlKitDownloadProgress = progress
                        mlKitDownloadMessage = msg
                    }
                }
            )
            isDownloadingMlKit = false
            downloadedLangCodes = translationEngine.getDownloadedLanguageCodes()
            if (result.isSuccess) {
                isPairModelReady = true
                mlKitDownloadProgress = 1f
                mlKitDownloadMessage = "המודל מאומת ומוכן ללא אינטרנט"
                statusMessage = "מוכן"
            } else {
                isPairModelReady = false
                mlKitDownloadProgress = 0f
                val err = result.exceptionOrNull()?.localizedMessage ?: "שגיאת רשת"
                mlKitDownloadMessage = "שגיאה: $err"
                statusMessage = "ההורדה נכשלה"
            }
        }
    }

    private fun deleteTargetMlKitModel() {
        if (isDownloadingMlKit) return
        val targetToDelete = targetLang
        lifecycleScope.launch {
            val res = translationEngine.deleteMlKitLanguage(targetToDelete.code)
            if (res.isSuccess) {
                statusMessage = "מודל ${targetToDelete.nameHe} הוסר מהמכשיר"
            }
            refreshModelStates()
        }
    }

    private fun start60PartDownload(modelId: String) {
        val url = fastPackUrlInput.trim()
        if (url.isEmpty()) {
            segmentedDownloader.setExternalState(
                modelId,
                DownloadProgressState(
                    modelId = modelId,
                    status = DownloadStatus.FAILED,
                    messageHe = "הזן קישור HTTPS ישיר לקובץ החבילה (JSON/TSV)"
                )
            )
            return
        }
        val src = sourceLang
        val tgt = targetLang
        lifecycleScope.launch {
            val result = segmentedDownloader.downloadSegmented(
                modelId = modelId,
                urlString = url,
                requestedParts = EdgeCasePolicy.TARGET_SEGMENT_COUNT
            )
            if (result.isSuccess) {
                val loaded = translationEngine.getOrLoadFastPack(src, tgt)
                fastPackEntryCount = loaded.size
                statusMessage = if (loaded.isNotEmpty()) {
                    "נטענו ${loaded.size} מונחים לחבילה המהירה"
                } else {
                    "הקובץ הורד ב-60 חלקים בהצלחה"
                }
            }
        }
    }

    private fun deleteFastPack(modelId: String) {
        segmentedDownloader.deletePack(modelId)
        fastPackEntryCount = 0
        translationEngine.clearCache()
        statusMessage = "החבילה המהירה הוסרה"
    }

    private fun testTranslation() {
        if (isTestingTranslation) return
        val validation = EdgeCasePolicy.validateLanguagePair(sourceLang, targetLang)
        if (!validation.isValid) {
            sampleOutput = validation.errorHe ?: "זוג שפות לא תקין"
            translationRevealKey++
            return
        }

        isTestingTranslation = true
        val currentSrc = sourceLang
        val currentTgt = targetLang
        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            if (offlineOnlyMode && !isPairModelReady) {
                val res = translationEngine.prepareModel(currentSrc, currentTgt)
                if (res.isFailure) {
                    sampleOutput = "יש להוריד קודם את המודל (${currentSrc.nameHe} ← ${currentTgt.nameHe})"
                    isTestingTranslation = false
                    translationRevealKey++
                    return@launch
                }
                isPairModelReady = true
                downloadedLangCodes = translationEngine.getDownloadedLanguageCodes()
            }
            val translated = translationEngine.translateText(
                text = sampleInput,
                source = currentSrc,
                target = currentTgt,
                offlineOnly = offlineOnlyMode
            )
            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed < 350L) {
                delay(350L - elapsed)
            }
            sampleOutput = if (translated.isNotEmpty()) {
                translated
            } else {
                "לא התקבל תרגום — ודא שהטקסט ב${currentSrc.nameHe}"
            }
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
        val validation = EdgeCasePolicy.validateLanguagePair(sourceLang, targetLang)
        if (!validation.isValid) {
            statusMessage = validation.errorHe ?: "בחר שתי שפות שונות"
            return
        }

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
    sourceLang: LanguageOption,
    targetLang: LanguageOption,
    pairValidation: EdgeCasePolicy.PairValidationResult,
    supportedModels: List<ModelOption>,
    downloadedLangCodes: Set<String>,
    downloadStates: Map<String, DownloadProgressState>,
    hasOverlayPermission: Boolean,
    hasNotificationPermission: Boolean,
    isPairModelReady: Boolean,
    isDownloadingMlKit: Boolean,
    mlKitDownloadProgress: Float,
    mlKitDownloadMessage: String,
    fastPackUrlInput: String,
    fastPackEntryCount: Int,
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
    onOpenSourcePicker: () -> Unit,
    onOpenTargetPicker: () -> Unit,
    onSwapLanguages: () -> Unit,
    onFastPackUrlChange: (String) -> Unit,
    onDownloadMlKitClick: () -> Unit,
    onDeleteMlKitTargetClick: () -> Unit,
    onDownloadFastPackClick: (String) -> Unit,
    onCancelFastPackClick: (String) -> Unit,
    onDeleteFastPackClick: (String) -> Unit,
    onSampleInputChange: (String) -> Unit,
    onResetSamplePhrase: () -> Unit,
    onToggleAutoMode: (Boolean) -> Unit,
    onToggleOfflineOnly: (Boolean) -> Unit,
    onToggleSkipBrands: (Boolean) -> Unit,
    onToggleScanWave: (Boolean) -> Unit,
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
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    pairValidation = pairValidation,
                    hasOverlayPermission = hasOverlayPermission,
                    isPairModelReady = isPairModelReady,
                    isServiceActive = isServiceActive,
                    startInAutoMode = startInAutoMode,
                    offlineOnlyMode = offlineOnlyMode,
                    skipBrandNames = skipBrandNames,
                    statusMessage = statusMessage,
                    onOpenModelsTab = { onTabSelected(AppTab.MODELS) },
                    onSwapLanguages = onSwapLanguages,
                    onToggleAutoMode = onToggleAutoMode,
                    onToggleOfflineOnly = onToggleOfflineOnly,
                    onToggleSkipBrands = onToggleSkipBrands,
                    onPowerButtonClick = onPowerButtonClick,
                    onGrantOverlayClick = onGrantOverlayClick
                )

                AppTab.MODELS -> ModelsTabScreen(
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    pairValidation = pairValidation,
                    supportedModels = supportedModels,
                    downloadedLangCodes = downloadedLangCodes,
                    downloadStates = downloadStates,
                    isPairModelReady = isPairModelReady,
                    isDownloadingMlKit = isDownloadingMlKit,
                    mlKitDownloadProgress = mlKitDownloadProgress,
                    mlKitDownloadMessage = mlKitDownloadMessage,
                    fastPackUrlInput = fastPackUrlInput,
                    fastPackEntryCount = fastPackEntryCount,
                    statusMessage = statusMessage,
                    onOpenSourcePicker = onOpenSourcePicker,
                    onOpenTargetPicker = onOpenTargetPicker,
                    onSwapLanguages = onSwapLanguages,
                    onFastPackUrlChange = onFastPackUrlChange,
                    onDownloadMlKitClick = onDownloadMlKitClick,
                    onDeleteMlKitTargetClick = onDeleteMlKitTargetClick,
                    onDownloadFastPackClick = onDownloadFastPackClick,
                    onCancelFastPackClick = onCancelFastPackClick,
                    onDeleteFastPackClick = onDeleteFastPackClick,
                    onClearCacheClick = onClearCacheClick
                )

                AppTab.TEST -> TestTabScreen(
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    isPairModelReady = isPairModelReady,
                    isDownloadingMlKit = isDownloadingMlKit,
                    mlKitDownloadProgress = mlKitDownloadProgress,
                    mlKitDownloadMessage = mlKitDownloadMessage,
                    fastPackEntryCount = fastPackEntryCount,
                    offlineOnlyMode = offlineOnlyMode,
                    sampleInput = sampleInput,
                    sampleOutput = sampleOutput,
                    isTestingTranslation = isTestingTranslation,
                    translationRevealKey = translationRevealKey,
                    onOpenSourcePicker = onOpenSourcePicker,
                    onOpenTargetPicker = onOpenTargetPicker,
                    onSwapLanguages = onSwapLanguages,
                    onSampleInputChange = onSampleInputChange,
                    onResetSamplePhrase = onResetSamplePhrase,
                    onDownloadMlKitClick = onDownloadMlKitClick,
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
    sourceLang: LanguageOption,
    targetLang: LanguageOption,
    pairValidation: EdgeCasePolicy.PairValidationResult,
    hasOverlayPermission: Boolean,
    isPairModelReady: Boolean,
    isServiceActive: Boolean,
    startInAutoMode: Boolean,
    offlineOnlyMode: Boolean,
    skipBrandNames: Boolean,
    statusMessage: String,
    onOpenModelsTab: () -> Unit,
    onSwapLanguages: () -> Unit,
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
                    text = "${sourceLang.nameHe} ← ${targetLang.nameHe} • ${if (offlineOnlyMode) "פרטיות מלאה" else "היברידי"}",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPillBadge(
                        label = when {
                            isServiceActive -> "פעיל"
                            isPairModelReady -> "מוכן"
                            else -> statusMessage
                        },
                        color = when {
                            isServiceActive -> EmeraldGreen
                            isPairModelReady -> EmeraldGreen
                            else -> AccentAmber
                        }
                    )
                    StatusPillBadge(
                        label = "שנה שפות",
                        color = ElectricBlueLight,
                        modifier = Modifier.clickable { onOpenModelsTab() }
                    )
                }
            }

            StatusPillBadge(
                label = if (hasOverlayPermission) "מאושר" else "נדרש אישור",
                color = if (hasOverlayPermission) EmeraldGreen else AccentRed,
                modifier = Modifier.clickable {
                    if (!hasOverlayPermission) onGrantOverlayClick()
                }
            )
        }

        // Edge case warning banner (e.g. non-Latin source OCR note)
        pairValidation.warningHe?.let { warning ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AccentAmber.copy(alpha = 0.12f))
                    .border(1.dp, AccentAmber.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = warning,
                    color = Color(0xFFFDE68A),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "החלף ⇄",
                    color = ElectricCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onSwapLanguages() }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            HeroPowerButton(
                isServiceActive = isServiceActive,
                onClick = onPowerButtonClick
            )
        }

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
    sourceLang: LanguageOption,
    targetLang: LanguageOption,
    pairValidation: EdgeCasePolicy.PairValidationResult,
    supportedModels: List<ModelOption>,
    downloadedLangCodes: Set<String>,
    downloadStates: Map<String, DownloadProgressState>,
    isPairModelReady: Boolean,
    isDownloadingMlKit: Boolean,
    mlKitDownloadProgress: Float,
    mlKitDownloadMessage: String,
    fastPackUrlInput: String,
    fastPackEntryCount: Int,
    statusMessage: String,
    onOpenSourcePicker: () -> Unit,
    onOpenTargetPicker: () -> Unit,
    onSwapLanguages: () -> Unit,
    onFastPackUrlChange: (String) -> Unit,
    onDownloadMlKitClick: () -> Unit,
    onDeleteMlKitTargetClick: () -> Unit,
    onDownloadFastPackClick: (String) -> Unit,
    onCancelFastPackClick: (String) -> Unit,
    onDeleteFastPackClick: (String) -> Unit,
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
                text = "שפות ומודלים",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "בחר שפת מקור ויעד להורדה מהירה של מודלים תומכים",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        // Source & Target Language Selector Card
        GlassCard {
            Text(
                text = "בחירת שפות תרגום",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            LanguagePairSelectorRow(
                sourceLang = sourceLang,
                targetLang = targetLang,
                onOpenSourcePicker = onOpenSourcePicker,
                onOpenTargetPicker = onOpenTargetPicker,
                onSwapLanguages = onSwapLanguages
            )
            pairValidation.warningHe?.let { warn ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = warn,
                    color = Color(0xFFFDE68A),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        // Dynamic Supported Models for selected language pair
        Text(
            text = "מודלים תומכים עבור ${sourceLang.nameHe} ← ${targetLang.nameHe}",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )

        supportedModels.forEach { model ->
            when (model.kind) {
                ModelKind.MLKIT_NEURAL_OFFLINE -> {
                    GlassCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = model.titleHe,
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = model.approxSizeLabel,
                                    color = ElectricCyan,
                                    fontSize = 12.sp
                                )
                            }
                            StatusPillBadge(
                                label = when {
                                    isDownloadingMlKit -> "מוריד..."
                                    isPairModelReady -> "מותקן"
                                    else -> "זמין להורדה"
                                },
                                color = when {
                                    isDownloadingMlKit -> ElectricCyan
                                    isPairModelReady -> EmeraldGreen
                                    else -> AccentAmber
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = model.subtitleHe,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )

                        if (isDownloadingMlKit || mlKitDownloadProgress > 0f || mlKitDownloadMessage.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            ObsidianDownloadProgressBar(
                                progress = if (isPairModelReady && !isDownloadingMlKit) 1f else mlKitDownloadProgress,
                                isDownloading = isDownloadingMlKit,
                                isSegmented = false,
                                segmentProgress = emptyList(),
                                completedParts = if (isPairModelReady) 1 else 0,
                                totalParts = 1,
                                speedBytesPerSec = 0L,
                                labelHe = mlKitDownloadMessage.ifEmpty {
                                    if (isPairModelReady) "מודל לא-מקוון מוכן לשימוש" else "ממתין להורדה"
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onDownloadMlKitClick,
                                enabled = !isDownloadingMlKit,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPairModelReady) Color(0xFF232B3E) else ElectricBlue
                                )
                            ) {
                                Text(
                                    text = when {
                                        isDownloadingMlKit -> "מוריד..."
                                        isPairModelReady -> "אמת מודל"
                                        else -> "הורד מודל לא-מקוון"
                                    },
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (isPairModelReady && !isDownloadingMlKit) {
                                OutlinedButton(
                                    onClick = onDeleteMlKitTargetClick,
                                    modifier = Modifier.height(44.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.45f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = AccentRed.copy(alpha = 0.10f)
                                    )
                                ) {
                                    Text("מחק", color = Color(0xFFFCA5A5), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                ModelKind.SEGMENTED_FAST_PACK -> {
                    val dlState = downloadStates[model.id] ?: DownloadProgressState(modelId = model.id)
                    val isBusyDl = dlState.status == DownloadStatus.PROBING ||
                        dlState.status == DownloadStatus.DOWNLOADING ||
                        dlState.status == DownloadStatus.VERIFYING
                    val isPackInstalled = dlState.status == DownloadStatus.COMPLETED || fastPackEntryCount > 0

                    GlassCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = model.titleHe,
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "האצת הורדה • 60 חלקים מקבילים (HTTP Range)",
                                    color = ElectricCyan,
                                    fontSize = 12.sp
                                )
                            }
                            StatusPillBadge(
                                label = when {
                                    isBusyDl -> "${(dlState.progress * 100).roundToInt()}%"
                                    fastPackEntryCount > 0 -> "$fastPackEntryCount מונחים"
                                    isPackInstalled -> "מותקן"
                                    else -> "60 חלקים"
                                },
                                color = when {
                                    isBusyDl -> ElectricCyan
                                    isPackInstalled -> EmeraldGreen
                                    else -> GlowPurple
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = model.subtitleHe,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            OutlinedTextField(
                                value = fastPackUrlInput,
                                onValueChange = onFastPackUrlChange,
                                enabled = !isBusyDl,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = {
                                    Text(
                                        "https://example.com/pack_${sourceLang.code}_${targetLang.code}.json",
                                        color = TextSecondary.copy(alpha = 0.65f),
                                        fontSize = 12.sp
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = GlassCardInner,
                                    unfocusedContainerColor = GlassCardInner,
                                    focusedBorderColor = ElectricCyan.copy(alpha = 0.7f),
                                    unfocusedBorderColor = GlassBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    cursorColor = ElectricCyan
                                )
                            )
                        }

                        if (dlState.status != DownloadStatus.IDLE) {
                            Spacer(modifier = Modifier.height(12.dp))
                            ObsidianDownloadProgressBar(
                                progress = dlState.progress,
                                isDownloading = isBusyDl,
                                isSegmented = dlState.isSegmented || isBusyDl,
                                segmentProgress = dlState.segmentProgress,
                                completedParts = dlState.completedParts,
                                totalParts = dlState.totalParts,
                                speedBytesPerSec = dlState.speedBytesPerSec,
                                labelHe = dlState.messageHe.ifEmpty { "מוכן" },
                                isError = dlState.status == DownloadStatus.FAILED
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isBusyDl) {
                                OutlinedButton(
                                    onClick = { onCancelFastPackClick(model.id) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.5f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = AccentAmber.copy(alpha = 0.12f)
                                    )
                                ) {
                                    Text("בטל הורדה", color = Color(0xFFFDE68A), fontSize = 13.sp)
                                }
                            } else {
                                Button(
                                    onClick = { onDownloadFastPackClick(model.id) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                                ) {
                                    Text(
                                        text = "הורד ב-60 חלקים",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                if (isPackInstalled) {
                                    OutlinedButton(
                                        onClick = { onDeleteFastPackClick(model.id) },
                                        modifier = Modifier.height(44.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.45f)),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = AccentRed.copy(alpha = 0.10f)
                                        )
                                    ) {
                                        Text("מחק", color = Color(0xFFFCA5A5), fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Downloaded languages summary & RAM cache control
        GlassCard {
            Text(
                text = "שפות מותקנות במכשיר (${downloadedLangCodes.size})",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            val installedNames = if (downloadedLangCodes.isEmpty()) {
                "עדיין לא הורדו שפות לא-מקוונות."
            } else {
                downloadedLangCodes.map { LanguageCatalog.findByCode(it).nameHe }.distinct().joinToString(" • ")
            }
            Text(
                text = installedNames,
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
                onClick = onClearCacheClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, GlassBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White.copy(alpha = 0.03f)
                )
            ) {
                Text("נקה מטמון זיכרון זמני", color = TextPrimary, fontSize = 13.sp)
            }
            if (statusMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = statusMessage, color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TestTabScreen(
    sourceLang: LanguageOption,
    targetLang: LanguageOption,
    isPairModelReady: Boolean,
    isDownloadingMlKit: Boolean,
    mlKitDownloadProgress: Float,
    mlKitDownloadMessage: String,
    fastPackEntryCount: Int,
    offlineOnlyMode: Boolean,
    sampleInput: String,
    sampleOutput: String,
    isTestingTranslation: Boolean,
    translationRevealKey: Int,
    onOpenSourcePicker: () -> Unit,
    onOpenTargetPicker: () -> Unit,
    onSwapLanguages: () -> Unit,
    onSampleInputChange: (String) -> Unit,
    onResetSamplePhrase: () -> Unit,
    onDownloadMlKitClick: () -> Unit,
    onTestTranslationClick: () -> Unit
) {
    val inputDir = if (sourceLang.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val outputDir = if (targetLang.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

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
                text = "${sourceLang.nameHe} ← ${targetLang.nameHe} • בדיקה והורדה",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        GlassCard {
            LanguagePairSelectorRow(
                sourceLang = sourceLang,
                targetLang = targetLang,
                onOpenSourcePicker = onOpenSourcePicker,
                onOpenTargetPicker = onOpenTargetPicker,
                onSwapLanguages = onSwapLanguages
            )
        }

        ModelGaugeCard(
            sourceLang = sourceLang,
            targetLang = targetLang,
            isModelReady = isPairModelReady,
            isDownloading = isDownloadingMlKit,
            progress = mlKitDownloadProgress,
            statusNote = mlKitDownloadMessage,
            fastPackEntryCount = fastPackEntryCount,
            onDownloadModelClick = onDownloadMlKitClick
        )

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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPillBadge(
                        label = "דוגמה",
                        color = ElectricBlueLight,
                        modifier = Modifier.clickable { onResetSamplePhrase() }
                    )
                    StatusPillBadge(
                        label = if (offlineOnlyMode) "מקומי" else "היברידי",
                        color = ElectricCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            CompositionLocalProvider(LocalLayoutDirection provides inputDir) {
                OutlinedTextField(
                    value = sampleInput,
                    onValueChange = onSampleInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("הזן טקסט ב${sourceLang.nameHe}...", color = TextSecondary)
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                GlowingDownArrow(active = isTestingTranslation || sampleOutput.isNotEmpty())
            }

            CompositionLocalProvider(LocalLayoutDirection provides outputDir) {
                ShimmerTranslationResultBox(
                    isLoading = isTestingTranslation,
                    translatedText = sampleOutput,
                    placeholderText = "התרגום ל${targetLang.nameHe} יופיע כאן בזמן אמת",
                    revealKey = translationRevealKey
                )
            }

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
                        text = "בדוק תרגום ל${targetLang.nameHe}",
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
    var showEdgeCasesPlaybook by remember { mutableStateOf(false) }

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
                text = "הרשאות, אפקטים ומדריך מצבי קיצון",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

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
                border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.45f)),
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

        // EdgeCasePolicy Playbook Viewer
        GlassCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showEdgeCasesPlaybook = !showEdgeCasesPlaybook },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "מדריך מצבי קיצון והגנות (${EdgeCasePolicy.playbook.size})",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "לחץ לצפייה בכללי האפליקציה לטיפול בתקלות, רשת, זיכרון ופרטיות",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                StatusPillBadge(
                    label = if (showEdgeCasesPlaybook) "סגור" else "הצג",
                    color = ElectricCyan
                )
            }

            AnimatedVisibility(visible = showEdgeCasesPlaybook) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    EdgeCasePolicy.playbook.forEach { item ->
                        val sevColor = when (item.severity) {
                            EdgeCasePolicy.Severity.CRITICAL -> AccentRed
                            EdgeCasePolicy.Severity.HIGH -> AccentAmber
                            EdgeCasePolicy.Severity.MEDIUM -> ElectricCyan
                            EdgeCasePolicy.Severity.LOW -> EmeraldGreen
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(GlassCardInner)
                                .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${item.id} • ${item.titleHe}",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                StatusPillBadge(label = item.severity.labelHe, color = sevColor)
                            }
                            Text(
                                text = "טריגר: ${item.triggerHe}",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                            Text(
                                text = "טיפול: ${item.resolutionHe}",
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguagePairSelectorRow(
    sourceLang: LanguageOption,
    targetLang: LanguageOption,
    onOpenSourcePicker: () -> Unit,
    onOpenTargetPicker: () -> Unit,
    onSwapLanguages: () -> Unit
) {
    val view = LocalView.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LanguageChipButton(
            roleLabel = "שפת מקור",
            lang = sourceLang,
            onClick = onOpenSourcePicker,
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(ElectricBlue.copy(alpha = 0.16f))
                .border(1.dp, ElectricBlueLight.copy(alpha = 0.35f), CircleShape)
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSwapLanguages()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⇄",
                color = ElectricBlueLight,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        LanguageChipButton(
            roleLabel = "שפת יעד",
            lang = targetLang,
            onClick = onOpenTargetPicker,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LanguageChipButton(
    roleLabel: String,
    lang: LanguageOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(GlassCardInner)
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = roleLabel,
            color = TextSecondary,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = lang.nameHe,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = lang.nativeName,
            color = ElectricCyan.copy(alpha = 0.85f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    titleHe: String,
    selectedCode: String,
    downloadedCodes: Set<String>,
    isSourceSelection: Boolean,
    onDismiss: () -> Unit,
    onSelect: (LanguageOption) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            LanguageCatalog.allLanguages
        } else {
            LanguageCatalog.allLanguages.filter {
                it.nameHe.lowercase(Locale.ROOT).contains(q) ||
                    it.nativeName.lowercase(Locale.ROOT).contains(q) ||
                    it.code.lowercase(Locale.ROOT).contains(q)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF5121726))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(24.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = titleHe,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "סגור",
                    color = ElectricBlueLight,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("חפש שפה...", color = TextSecondary, fontSize = 13.sp) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = GlassCardInner,
                    unfocusedContainerColor = GlassCardInner,
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = GlassBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.code }) { lang ->
                    val isSelected = lang.code == selectedCode
                    val isDownloaded = lang.code in downloadedCodes

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) ElectricBlue.copy(alpha = 0.22f) else GlassCardInner
                            )
                            .border(
                                1.dp,
                                if (isSelected) ElectricBlueLight.copy(alpha = 0.6f) else GlassBorder,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelect(lang) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.nameHe,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = buildString {
                                    append(lang.nativeName)
                                    if (isSourceSelection && !lang.supportsLatinOcr) {
                                        append(" • בדיקה בלבד (לא לטיני)")
                                    }
                                },
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        if (isDownloaded) {
                            StatusPillBadge(label = "מותקן", color = EmeraldGreen)
                        }
                    }
                }
            }
        }
    }
}

/**
 * פס התקדמות מותאם לסגנון Dark Obsidian & Glass, הכולל מילוי זוהר,
 * חיווי אחוזים ומהירות, ותצוגה חיה של 60 החלקים המקבילים במודלים תומכים.
 */
@Composable
private fun ObsidianDownloadProgressBar(
    progress: Float,
    isDownloading: Boolean,
    isSegmented: Boolean,
    segmentProgress: List<Float>,
    completedParts: Int,
    totalParts: Int,
    speedBytesPerSec: Long,
    labelHe: String,
    isError: Boolean = false
) {
    val clamped by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "ObsidianProgress"
    )

    val infinite = rememberInfiniteTransition(label = "ProgressShimmer")
    val shimmerOffset by infinite.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "BarShimmer"
    )

    val pct = (clamped * 100f).roundToInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassCardInner)
            .border(
                width = 1.dp,
                color = when {
                    isError -> AccentRed.copy(alpha = 0.45f)
                    clamped >= 0.999f && !isDownloading -> EmeraldGreen.copy(alpha = 0.45f)
                    isDownloading -> ElectricCyan.copy(alpha = 0.45f)
                    else -> GlassBorder
                },
                shape = RoundedCornerShape(14.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = labelHe,
                color = if (isError) Color(0xFFFCA5A5) else TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Text(
                text = if (isError) "שגיאה" else "$pct%",
                color = when {
                    isError -> AccentRed
                    clamped >= 0.999f && !isDownloading -> EmeraldGreen
                    else -> ElectricCyan
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Main glossy obsidian progress bar
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
        ) {
            // Track
            drawRoundRect(
                color = Color.White.copy(alpha = 0.08f),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
            )

            val fillWidth = (size.width * clamped).coerceIn(0f, size.width)
            if (fillWidth > 1f) {
                val brush = when {
                    isError -> Brush.horizontalGradient(listOf(AccentRed, Color(0xFFF87171)))
                    clamped >= 0.999f && !isDownloading -> Brush.horizontalGradient(
                        listOf(EmeraldGreen, Color(0xFF34D399))
                    )
                    else -> Brush.horizontalGradient(
                        listOf(GlowPurple, ElectricBlue, ElectricCyan)
                    )
                }
                drawRoundRect(
                    brush = brush,
                    size = Size(fillWidth, size.height),
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                )

                if (isDownloading) {
                    val glossX = shimmerOffset * fillWidth
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.45f),
                                Color.Transparent
                            ),
                            startX = (glossX - 60f).coerceAtLeast(0f),
                            endX = (glossX + 60f).coerceAtMost(fillWidth)
                        ),
                        size = Size(fillWidth, size.height),
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                    )
                }
            }
        }

        // 60-segment micro visualizer when segmented downloading is active
        if (isSegmented && totalParts > 1) {
            val segments = if (segmentProgress.size == totalParts) {
                segmentProgress
            } else {
                List(totalParts) { idx -> if (idx < completedParts) 1f else 0f }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            ) {
                val count = segments.size.coerceAtLeast(1)
                val gap = 1.5.dp.toPx()
                val segW = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
                for (i in 0 until count) {
                    val p = segments[i].coerceIn(0f, 1f)
                    val x = i * (segW + gap)
                    val segColor = when {
                        p >= 0.99f -> EmeraldGreen
                        p > 0.05f -> ElectricCyan
                        else -> Color.White.copy(alpha = 0.10f)
                    }
                    drawRoundRect(
                        color = segColor,
                        topLeft = Offset(x, 0f),
                        size = Size(segW, size.height),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "חלקים: $completedParts / $totalParts",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Text(
                    text = if (speedBytesPerSec > 0L) formatSpeed(speedBytesPerSec) else "",
                    color = ElectricCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
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
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.34f

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

            val r1Scale = 1.0f + (ring1Progress * 0.36f)
            val r1Alpha = (1f - ring1Progress) * 0.40f
            drawCircle(
                color = glowColor.copy(alpha = r1Alpha),
                radius = baseRadius * r1Scale,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            val r2Scale = 1.0f + (ring2Progress * 0.36f)
            val r2Alpha = (1f - ring2Progress) * 0.28f
            drawCircle(
                color = ElectricCyan.copy(alpha = r2Alpha),
                radius = baseRadius * r2Scale,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

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
            val sqSize = w * 0.30f
            drawRoundRect(
                color = tint,
                topLeft = Offset((w - sqSize) / 2f, h * 0.52f),
                size = Size(sqSize, sqSize),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            )
        } else {
            val tileW = w * 0.34f
            val tileH = h * 0.34f
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.17f, h * 0.44f),
                size = Size(tileW, tileH),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = stroke
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.49f, h * 0.56f),
                size = Size(tileW, tileH),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = stroke
            )
            val aPath = Path().apply {
                moveTo(w * 0.58f, h * 0.82f)
                lineTo(w * 0.66f, h * 0.64f)
                lineTo(w * 0.74f, h * 0.82f)
                moveTo(w * 0.61f, h * 0.76f)
                lineTo(w * 0.71f, h * 0.76f)
            }
            drawPath(aPath, color = tint, style = stroke)

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
    sourceLang: LanguageOption,
    targetLang: LanguageOption,
    isModelReady: Boolean,
    isDownloading: Boolean,
    progress: Float,
    statusNote: String,
    fastPackEntryCount: Int,
    onDownloadModelClick: () -> Unit
) {
    GlassCard {
        Text(
            text = "מודל לא-מקוון (${sourceLang.code.uppercase()} ← ${targetLang.code.uppercase()})",
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
                isDownloading = isDownloading,
                progress = progress
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = when {
                        isDownloading -> statusNote.ifEmpty { "מוריד את מודל השפה למכשיר..." }
                        isModelReady -> "עובד מקומית במהירות ללא אינטרנט עבור ${sourceLang.nameHe} ← ${targetLang.nameHe}"
                        else -> "הורדה חד-פעמית (כ-30MB) להפעלת תרגום ללא רשת"
                    },
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                if (fastPackEntryCount > 0) {
                    StatusPillBadge(
                        label = "חבילה מהירה פעילה ($fastPackEntryCount מונחים)",
                        color = ElectricCyan
                    )
                }
            }
        }

        if (isDownloading) {
            Spacer(modifier = Modifier.height(12.dp))
            ObsidianDownloadProgressBar(
                progress = progress,
                isDownloading = true,
                isSegmented = false,
                segmentProgress = emptyList(),
                completedParts = 0,
                totalParts = 1,
                speedBytesPerSec = 0L,
                labelHe = statusNote.ifEmpty { "מוריד מודל..." }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = onDownloadModelClick,
            enabled = !isDownloading,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(23.dp),
            border = BorderStroke(
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
                    isDownloading -> "מוריד מודל..."
                    isModelReady -> "אמת מודל"
                    else -> "הורד מודל ${sourceLang.nameHe} ← ${targetLang.nameHe}"
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
    isDownloading: Boolean,
    progress: Float
) {
    val targetSweep = when {
        isDownloading -> (progress.coerceIn(0.05f, 0.98f) * 360f)
        isReady -> 360f
        else -> 18f
    }
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
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

            if (isReady || isDownloading) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ringColor.copy(alpha = 0.24f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.52f
                    ),
                    radius = size.minDimension * 0.52f,
                    center = center
                )
            }

            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx)
            )

            drawArc(
                color = ringColor,
                startAngle = if (isDownloading) spinAngle - 90f else -90f,
                sweepAngle = animatedSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    isDownloading -> "${(progress * 100f).roundToInt()}%"
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
    placeholderText: String,
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
                text = placeholderText,
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
                label = "TextReveal"
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

private fun formatSpeed(bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024.0
    return if (kb >= 1024.0) {
        String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f KB/s", kb)
    }
}
