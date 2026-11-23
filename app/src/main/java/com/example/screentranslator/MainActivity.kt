package com.example.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class PickerMode {
    NONE,
    SOURCE,
    TARGET
}

private data class TranslatedImageEntry(
    val sourceText: String,
    val translatedText: String
)

class MainActivity : ComponentActivity() {

    private lateinit var translationEngine: ScreenTranslationEngine
    private lateinit var segmentedDownloader: SegmentedModelDownloader
    private val imageRecognizer = TextRecognizerHelper()
    private val prefs by lazy { getSharedPreferences("screen_translator_prefs", Context.MODE_PRIVATE) }
    private val uiErrorHandler = CrashGuard.coroutineHandler("MainActivity") {
        statusMessage = "אירעה תקלה זמנית — הפעולה בוטלה בבטחה"
    }

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
    private var enableUiAnimations by mutableStateOf(true)
    private var statusMessage by mutableStateOf("מוכן להפעלה")

    // Sandbox text testing
    private var sampleInput by mutableStateOf(LanguageCatalog.defaultSource.samplePhrase)
    private var sampleOutput by mutableStateOf("")
    private var isTestingTranslation by mutableStateOf(false)
    private var translationRevealKey by mutableIntStateOf(0)

    // Sandbox image translation
    private var selectedImageUri by mutableStateOf<Uri?>(null)
    private var originalPreviewBitmap by mutableStateOf<ImageBitmap?>(null)
    private var translatedPreviewBitmap by mutableStateOf<ImageBitmap?>(null)
    private var showOriginalImage by mutableStateOf(false)
    private var isTranslatingImage by mutableStateOf(false)
    private var imageTranslationStatus by mutableStateOf("")
    private var imageTranslatedEntries by mutableStateOf<List<TranslatedImageEntry>>(emptyList())

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                selectedImageUri = uri
                translateUploadedImage(uri)
            }
        }

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
                val started = CrashGuard.safeCall("MainActivity.startOverlayService", false) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    true
                }
                statusMessage = if (started) {
                    "פעיל (${sourceLang.nameHe} ← ${targetLang.nameHe})"
                } else {
                    "המערכת מנעה הפעלת שירות ברקע — נסה שוב"
                }
            } else {
                statusMessage = "הרשאת צילום המסך בוטלה"
            }
        }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.safeRun("MainActivity.systemBars") {
            window.statusBarColor = 0xFF0B0E17.toInt()
            window.navigationBarColor = 0xFF0B0E17.toInt()
        }

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
                    background = AppPalette.BgObsidian,
                    surface = AppPalette.GlassCardBg,
                    primary = AppPalette.ElectricBlue
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
                        enableUiAnimations = enableUiAnimations,
                        statusMessage = statusMessage,
                        sampleInput = sampleInput,
                        sampleOutput = sampleOutput,
                        isTestingTranslation = isTestingTranslation,
                        translationRevealKey = translationRevealKey,
                        originalPreviewBitmap = originalPreviewBitmap,
                        translatedPreviewBitmap = translatedPreviewBitmap,
                        showOriginalImage = showOriginalImage,
                        isTranslatingImage = isTranslatingImage,
                        imageTranslationStatus = imageTranslationStatus,
                        imageTranslatedEntries = imageTranslatedEntries,
                        hasSelectedImage = selectedImageUri != null,
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
                        onPickImageClick = { launchImagePickerSafely() },
                        onRetranslateImageClick = {
                            selectedImageUri?.let { translateUploadedImage(it) }
                        },
                        onToggleShowOriginalImage = { showOriginalImage = it },
                        onClearImageClick = { clearUploadedImage() },
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
                        onToggleUiAnimations = {
                            enableUiAnimations = it
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
                            animationsEnabled = enableUiAnimations,
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
        imageRecognizer.close()
        if (::translationEngine.isInitialized) {
            translationEngine.close()
        }
        super.onDestroy()
    }

    private fun loadPreferences() {
        CrashGuard.safeRun("MainActivity.loadPrefs") {
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
            enableUiAnimations = prefs.getBoolean("ui_animations", true)
            sampleInput = sourceLang.samplePhrase
        }
    }

    private fun savePreferences() {
        CrashGuard.safeRun("MainActivity.savePrefs") {
            prefs.edit()
                .putString("source_lang", sourceLang.code)
                .putString("target_lang", targetLang.code)
                .putBoolean("auto_mode", startInAutoMode)
                .putBoolean("offline_only", offlineOnlyMode)
                .putBoolean("skip_brands", skipBrandNames)
                .putBoolean("scan_wave", enableScanWave)
                .putBoolean("ui_animations", enableUiAnimations)
                .apply()
        }
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
        hasOverlayPermission = CrashGuard.safeCall("MainActivity.canDrawOverlays", false) {
            Settings.canDrawOverlays(this)
        }
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            CrashGuard.safeCall("MainActivity.notifPerm", true) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            true
        }
    }

    private fun refreshModelStates() {
        val currentSrc = sourceLang
        val currentTgt = targetLang
        lifecycleScope.launch(uiErrorHandler) {
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
                fastPackEntryCount = withContext(Dispatchers.IO) {
                    translationEngine.getOrLoadFastPack(currentSrc, currentTgt).size
                }
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

        lifecycleScope.launch(uiErrorHandler) {
            try {
                val result = translationEngine.prepareModel(
                    source = currentSrc,
                    target = currentTgt,
                    onProgress = { progress, msg ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            mlKitDownloadProgress = progress
                            mlKitDownloadMessage = msg
                        }
                    }
                )
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
            } finally {
                isDownloadingMlKit = false
            }
        }
    }

    private fun deleteTargetMlKitModel() {
        if (isDownloadingMlKit) return
        val targetToDelete = targetLang
        lifecycleScope.launch(uiErrorHandler) {
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
        lifecycleScope.launch(uiErrorHandler) {
            val result = segmentedDownloader.downloadSegmented(
                modelId = modelId,
                urlString = url,
                requestedParts = EdgeCasePolicy.TARGET_SEGMENT_COUNT
            )
            if (result.isSuccess) {
                val loadedCount = withContext(Dispatchers.IO) {
                    translationEngine.getOrLoadFastPack(src, tgt).size
                }
                fastPackEntryCount = loadedCount
                statusMessage = if (loadedCount > 0) {
                    "נטענו $loadedCount מונחים לחבילה המהירה"
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

    private fun launchImagePickerSafely() {
        val launched = CrashGuard.safeCall("MainActivity.pickImage", false) {
            imagePickerLauncher.launch("image/*")
            true
        }
        if (!launched) {
            imageTranslationStatus = "לא נמצאה אפליקציית גלריה זמינה במכשיר"
        }
    }

    private fun clearUploadedImage() {
        selectedImageUri = null
        originalPreviewBitmap = null
        translatedPreviewBitmap = null
        showOriginalImage = false
        imageTranslationStatus = ""
        imageTranslatedEntries = emptyList()
    }

    private fun translateUploadedImage(uri: Uri) {
        if (isTranslatingImage) return
        val validation = EdgeCasePolicy.validateLanguagePair(sourceLang, targetLang)
        if (!validation.isValid) {
            imageTranslationStatus = validation.errorHe ?: "זוג שפות לא תקין"
            return
        }

        val currentSrc = sourceLang
        val currentTgt = targetLang
        isTranslatingImage = true
        showOriginalImage = false
        imageTranslationStatus = "סורק טקסט מהתמונה..."
        imageTranslatedEntries = emptyList()

        lifecycleScope.launch(uiErrorHandler) {
            try {
                val decoded = withContext(Dispatchers.IO) {
                    decodeScaledSoftwareBitmap(uri, maxDimension = 1400)
                }
                if (decoded == null) {
                    imageTranslationStatus = "לא ניתן לטעון את התמונה (ייתכן שהיא גדולה מדי או פגומה)"
                    return@launch
                }

                originalPreviewBitmap = decoded.asImageBitmap()
                translatedPreviewBitmap = null

                if (offlineOnlyMode && !isPairModelReady) {
                    imageTranslationStatus = "מכין מודל לא-מקוון (${currentSrc.nameHe} ← ${currentTgt.nameHe})..."
                    val prep = translationEngine.prepareModel(currentSrc, currentTgt)
                    if (prep.isFailure) {
                        imageTranslationStatus = "יש להוריד תחילה את המודל בלשונית מודלים"
                        return@launch
                    }
                    isPairModelReady = true
                    downloadedLangCodes = translationEngine.getDownloadedLanguageCodes()
                }

                imageTranslationStatus = "מזהה אזורי טקסט בתמונה..."
                val detected = imageRecognizer.recognize(
                    bitmap = decoded,
                    targetLang = currentTgt
                )
                val eligible = detected.filterNot {
                    translationEngine.shouldSkipText(
                        rawText = it.text,
                        source = currentSrc,
                        target = currentTgt,
                        skipBrands = skipBrandNames
                    )
                }

                if (eligible.isEmpty()) {
                    imageTranslationStatus = "לא זוהה טקסט מתאים ב${currentSrc.nameHe} בתמונה"
                    return@launch
                }

                imageTranslationStatus = "מתרגם ${eligible.size} אזורי טקסט ל${currentTgt.nameHe}..."
                val translatedMap = translationEngine.translateBatch(
                    texts = eligible.map { it.text },
                    source = currentSrc,
                    target = currentTgt,
                    offlineOnly = offlineOnlyMode
                )

                val translatedBlocks = eligible.mapNotNull { block ->
                    val tr = translatedMap[block.text]?.trim().orEmpty()
                    if (translationEngine.isValidResult(block.text, tr, currentTgt)) {
                        block.copy(translatedText = tr)
                    } else {
                        null
                    }
                }

                if (translatedBlocks.isEmpty()) {
                    imageTranslationStatus = "לא התקבלו תרגומים חדשים לתמונה זו"
                    return@launch
                }

                val rendered = withContext(Dispatchers.Default) {
                    renderTranslatedBitmap(
                        sourceBitmap = decoded,
                        blocks = translatedBlocks,
                        isTargetRtl = currentTgt.isRtl
                    )
                }

                if (rendered != null) {
                    translatedPreviewBitmap = rendered.asImageBitmap()
                    imageTranslatedEntries = translatedBlocks.map {
                        TranslatedImageEntry(sourceText = it.text, translatedText = it.translatedText)
                    }
                    imageTranslationStatus = "תורגמו ${translatedBlocks.size} אזורי טקסט בהצלחה"
                } else {
                    imageTranslationStatus = "אין מספיק זיכרון לציור התמונה המתורגמת"
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                CrashGuard.recordError("MainActivity.translateImage", t)
                imageTranslationStatus = "אירעה שגיאה בעיבוד התמונה"
            } finally {
                isTranslatingImage = false
            }
        }
    }

    private fun decodeScaledSoftwareBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        return CrashGuard.safeCall("MainActivity.decodeBitmap", null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val src = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                    val w = info.size.width
                    val h = info.size.height
                    if (w > maxDimension || h > maxDimension) {
                        val scale = maxDimension.toFloat() / max(w, h).toFloat()
                        decoder.setTargetSize(
                            (w * scale).roundToInt().coerceAtLeast(1),
                            (h * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                }
            } else {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, boundsOpts)
                }
                val rawW = boundsOpts.outWidth
                val rawH = boundsOpts.outHeight
                if (rawW <= 0 || rawH <= 0) return@safeCall null

                var sampleSize = 1
                while ((rawW / sampleSize) > maxDimension || (rawH / sampleSize) > maxDimension) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, decodeOpts)
                }
            }
        }
    }

    private fun renderTranslatedBitmap(
        sourceBitmap: Bitmap,
        blocks: List<RecognizedBlock>,
        isTargetRtl: Boolean
    ): Bitmap? {
        if (sourceBitmap.isRecycled || sourceBitmap.width <= 4 || sourceBitmap.height <= 4) return null
        return CrashGuard.safeCall("MainActivity.renderImage", null) {
            val output = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return@safeCall null
            val canvas = AndroidCanvas(output)
            val canvasW = output.width
            val canvasH = output.height

            val sorted = blocks.sortedWith(compareBy<RecognizedBlock> { it.bounds.top }.thenBy { it.bounds.left })
            val padX = max(6f, canvasW * 0.008f)
            val padY = max(4f, canvasH * 0.005f)
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val textDir = if (isTargetRtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR

            for (i in sorted.indices) {
                val block = sorted[i]
                val text = block.translatedText
                if (text.isEmpty()) continue

                val isRotated = abs(block.angleDegrees) >= 2.5f
                val baseW = if (isRotated) block.orientedWidth else block.bounds.width()
                val baseH = if (isRotated) block.orientedHeight else block.bounds.height()
                val origW = baseW.coerceAtLeast(20)
                val origH = baseH.coerceAtLeast(16)
                val isCompactLabel = block.lineCount == 1 && origW < (canvasW * 0.50f)

                val avgLineH = origH.toFloat() / block.lineCount.coerceAtLeast(1)
                var fontSize = (avgLineH * 0.80f).coerceIn(16f, 56f)

                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = block.textColor
                    textSize = fontSize
                    isFakeBoldText = fontSize <= 26f || block.needsTextShadow
                    if (block.needsTextShadow) {
                        setShadowLayer(2.5f, 0f, 1.5f, AndroidColor.argb(150, 0, 0, 0))
                    }
                }

                var minAllowedLeft = 4f
                var maxAllowedRight = canvasW - 4f
                if (!isRotated) {
                    for (j in sorted.indices) {
                        if (i == j) continue
                        val other = sorted[j].bounds
                        val vOverlap = min(block.bounds.bottom, other.bottom) - max(block.bounds.top, other.top)
                        val minH = min(block.bounds.height(), other.height()).coerceAtLeast(1)
                        if (vOverlap.toFloat() / minH > 0.30f) {
                            if (other.right <= block.bounds.left + 8) {
                                minAllowedLeft = max(minAllowedLeft, (other.right + block.bounds.left) / 2f + 2f)
                            } else if (other.left >= block.bounds.right - 8) {
                                maxAllowedRight = min(maxAllowedRight, (block.bounds.right + other.left) / 2f - 2f)
                            }
                        }
                    }
                }

                if (maxAllowedRight - minAllowedLeft < 36f) {
                    minAllowedLeft = 4f
                    maxAllowedRight = canvasW - 4f
                }

                val targetRect: RectF
                val layout: StaticLayout

                if (isCompactLabel) {
                    val colLimit = (maxAllowedRight - minAllowedLeft - padX * 2f).coerceAtLeast(origW.toFloat())
                    val maxAllowedW = min(colLimit, max(origW * 1.45f, canvasW * 0.30f))
                    var measuredW = ceil(StaticLayout.getDesiredWidth(text, textPaint).toDouble()).toFloat()

                    while (measuredW > maxAllowedW && fontSize > 13f) {
                        fontSize -= 1.2f
                        textPaint.textSize = fontSize
                        measuredW = ceil(StaticLayout.getDesiredWidth(text, textPaint).toDouble()).toFloat()
                    }

                    val layoutW = max(origW.toFloat(), min(measuredW, maxAllowedW)).toInt().coerceAtLeast(24)
                    layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, layoutW)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setTextDirection(textDir)
                        .setIncludePad(false)
                        .build()

                    val boxW = max(origW + padX * 2f, layoutW + padX * 2f)
                    val boxH = max(origH + padY * 2f, layout.height + padY * 2f)
                    val cx = block.bounds.exactCenterX()
                    val cy = block.bounds.exactCenterY()

                    var left = cx - boxW / 2f
                    var right = cx + boxW / 2f
                    if (!isRotated) {
                        if (left < minAllowedLeft) {
                            right += (minAllowedLeft - left)
                            left = minAllowedLeft
                        }
                        if (right > maxAllowedRight) {
                            left -= (right - maxAllowedRight)
                            right = maxAllowedRight
                        }
                        left = left.coerceAtLeast(minAllowedLeft)
                        right = right.coerceAtMost(maxAllowedRight).coerceAtLeast(left + 24f)
                    }
                    val top = (cy - boxH / 2f).coerceIn(2f, (canvasH - 20f).coerceAtLeast(2f))
                    val bottom = (top + boxH).coerceIn(top + 10f, canvasH - 2f)
                    targetRect = RectF(left, top, right, bottom)
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
                    val layoutW = (right - left - padX * 2f).toInt().coerceAtLeast(36)

                    var fitted = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, layoutW)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setTextDirection(textDir)
                        .setIncludePad(false)
                        .build()
                    while (fitted.height > origH + padY && fontSize > 13f) {
                        fontSize -= 1.2f
                        textPaint.textSize = fontSize
                        fitted = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, layoutW)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setTextDirection(textDir)
                            .setIncludePad(false)
                            .build()
                    }
                    layout = fitted

                    val boxH = max(origH + padY * 2f, layout.height + padY * 2f)
                    val top = if (isRotated) {
                        block.bounds.exactCenterY() - boxH / 2f
                    } else {
                        (block.bounds.top - padY).coerceIn(2f, (canvasH - 20f).coerceAtLeast(2f))
                    }
                    val bottom = (top + boxH).coerceIn(top + 10f, canvasH - 2f)
                    targetRect = RectF(left, top, right.coerceAtLeast(left + 24f), bottom)
                }

                val topCol = block.bgTopColor or -0x1000000
                val botCol = block.bgBottomColor or -0x1000000
                if (topCol != botCol && targetRect.bottom > targetRect.top + 1f) {
                    boxPaint.shader = LinearGradient(
                        targetRect.left,
                        targetRect.top,
                        targetRect.left,
                        targetRect.bottom,
                        topCol,
                        botCol,
                        Shader.TileMode.CLAMP
                    )
                } else {
                    boxPaint.shader = null
                    boxPaint.color = topCol
                }

                canvas.save()
                if (isRotated) {
                    canvas.rotate(block.angleDegrees, targetRect.centerX(), targetRect.centerY())
                }
                canvas.drawRoundRect(targetRect, 8f, 8f, boxPaint)

                val textLeft = targetRect.left + (targetRect.width() - layout.width) / 2f
                val textTop = targetRect.top + (targetRect.height() - layout.height) / 2f
                canvas.translate(textLeft, textTop)
                layout.draw(canvas)
                canvas.restore()
            }

            output
        }
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
        lifecycleScope.launch(uiErrorHandler) {
            try {
                val startMs = System.currentTimeMillis()
                if (offlineOnlyMode && !isPairModelReady) {
                    val res = translationEngine.prepareModel(currentSrc, currentTgt)
                    if (res.isFailure) {
                        sampleOutput = "יש להוריד קודם את המודל (${currentSrc.nameHe} ← ${currentTgt.nameHe})"
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
                if (enableUiAnimations && elapsed < 350L) {
                    delay(350L - elapsed)
                }
                sampleOutput = if (translated.isNotEmpty()) {
                    translated
                } else {
                    "לא התקבל תרגום — ודא שהטקסט ב${currentSrc.nameHe}"
                }
                translationRevealKey++
            } finally {
                isTestingTranslation = false
            }
        }
    }

    private fun openOverlaySettings() {
        val packageIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        val opened = CrashGuard.safeCall("MainActivity.openOverlayPkg", false) {
            startActivity(packageIntent)
            true
        }
        if (!opened) {
            val fallbackOpened = CrashGuard.safeCall("MainActivity.openOverlayGeneric", false) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                true
            }
            if (!fallbackOpened) {
                statusMessage = "לא ניתן לפתוח את הגדרות ההרשאות במכשיר זה"
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
                val asked = CrashGuard.safeCall("MainActivity.reqNotif", false) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    true
                }
                if (asked) return
            }
        }

        launchScreenCapturePrompt()
    }

    private fun launchScreenCapturePrompt() {
        val launched = CrashGuard.safeCall("MainActivity.launchProjection", false) {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                    ?: return@safeCall false
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            true
        }
        if (!launched) {
            statusMessage = "לא ניתן להפעיל את חלונית צילום המסך במכשיר זה"
        }
    }

    private fun stopOverlayService() {
        CrashGuard.safeRun("MainActivity.stopService") {
            stopService(Intent(this, OverlayService::class.java))
        }
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
    downloadStates: DownloadProgressMap,
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
    enableUiAnimations: Boolean,
    statusMessage: String,
    sampleInput: String,
    sampleOutput: String,
    isTestingTranslation: Boolean,
    translationRevealKey: Int,
    originalPreviewBitmap: ImageBitmap?,
    translatedPreviewBitmap: ImageBitmap?,
    showOriginalImage: Boolean,
    isTranslatingImage: Boolean,
    imageTranslationStatus: String,
    imageTranslatedEntries: List<TranslatedImageEntry>,
    hasSelectedImage: Boolean,
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
    onPickImageClick: () -> Unit,
    onRetranslateImageClick: () -> Unit,
    onToggleShowOriginalImage: (Boolean) -> Unit,
    onClearImageClick: () -> Unit,
    onToggleAutoMode: (Boolean) -> Unit,
    onToggleOfflineOnly: (Boolean) -> Unit,
    onToggleSkipBrands: (Boolean) -> Unit,
    onToggleScanWave: (Boolean) -> Unit,
    onToggleUiAnimations: (Boolean) -> Unit,
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
                    colors = listOf(AppPalette.BgObsidianTop, AppPalette.BgObsidian, AppPalette.BgObsidian)
                )
            )
    ) {
        ObsidianAmbientBackdrop(
            isServiceActive = isServiceActive,
            animationsEnabled = enableUiAnimations
        )

        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                AppMotion.tabTransition(
                    fromTab = initialState,
                    toTab = targetState,
                    animationsEnabled = enableUiAnimations
                )
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
                    animationsEnabled = enableUiAnimations,
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
                    animationsEnabled = enableUiAnimations,
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
                    animationsEnabled = enableUiAnimations,
                    sampleInput = sampleInput,
                    sampleOutput = sampleOutput,
                    isTestingTranslation = isTestingTranslation,
                    translationRevealKey = translationRevealKey,
                    originalPreviewBitmap = originalPreviewBitmap,
                    translatedPreviewBitmap = translatedPreviewBitmap,
                    showOriginalImage = showOriginalImage,
                    isTranslatingImage = isTranslatingImage,
                    imageTranslationStatus = imageTranslationStatus,
                    imageTranslatedEntries = imageTranslatedEntries,
                    hasSelectedImage = hasSelectedImage,
                    onOpenSourcePicker = onOpenSourcePicker,
                    onOpenTargetPicker = onOpenTargetPicker,
                    onSwapLanguages = onSwapLanguages,
                    onSampleInputChange = onSampleInputChange,
                    onResetSamplePhrase = onResetSamplePhrase,
                    onPickImageClick = onPickImageClick,
                    onRetranslateImageClick = onRetranslateImageClick,
                    onToggleShowOriginalImage = onToggleShowOriginalImage,
                    onClearImageClick = onClearImageClick,
                    onDownloadMlKitClick = onDownloadMlKitClick,
                    onTestTranslationClick = onTestTranslationClick
                )

                AppTab.SETTINGS -> SettingsTabScreen(
                    hasOverlayPermission = hasOverlayPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    enableScanWave = enableScanWave,
                    enableUiAnimations = enableUiAnimations,
                    skipBrandNames = skipBrandNames,
                    offlineOnlyMode = offlineOnlyMode,
                    onGrantOverlayClick = onGrantOverlayClick,
                    onToggleScanWave = onToggleScanWave,
                    onToggleUiAnimations = onToggleUiAnimations,
                    onToggleSkipBrands = onToggleSkipBrands,
                    onToggleOfflineOnly = onToggleOfflineOnly
                )
            }
        }

        FloatingGlassBottomBar(
            selectedTab = currentTab,
            onTabSelected = onTabSelected,
            animationsEnabled = enableUiAnimations,
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
    animationsEnabled: Boolean,
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
            modifier = Modifier
                .fillMaxWidth()
                .staggeredEntrance(index = 0, enabled = animationsEnabled),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "מתרגם מסך",
                    color = AppPalette.TextPrimary,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${sourceLang.nameHe} ← ${targetLang.nameHe} • ${if (offlineOnlyMode) "פרטיות מלאה" else "היברידי"}",
                    color = AppPalette.TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PulsingStatusPill(
                        label = when {
                            isServiceActive -> "פעיל"
                            isPairModelReady -> "מוכן"
                            else -> statusMessage
                        },
                        color = when {
                            isServiceActive -> AppPalette.EmeraldGreen
                            isPairModelReady -> AppPalette.EmeraldGreen
                            else -> AppPalette.AccentAmber
                        },
                        pulsing = isServiceActive,
                        animationsEnabled = animationsEnabled
                    )
                    PulsingStatusPill(
                        label = "שנה שפות",
                        color = AppPalette.ElectricBlueLight,
                        animationsEnabled = animationsEnabled,
                        modifier = Modifier.tactilePress(
                            animationsEnabled = animationsEnabled,
                            onClick = onOpenModelsTab
                        )
                    )
                }
            }

            PulsingStatusPill(
                label = if (hasOverlayPermission) "מאושר" else "נדרש אישור",
                color = if (hasOverlayPermission) AppPalette.EmeraldGreen else AppPalette.AccentRed,
                pulsing = !hasOverlayPermission,
                animationsEnabled = animationsEnabled,
                modifier = Modifier.tactilePress(
                    enabled = !hasOverlayPermission,
                    animationsEnabled = animationsEnabled,
                    onClick = onGrantOverlayClick
                )
            )
        }

        pairValidation.warningHe?.let { warning ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 1, enabled = animationsEnabled)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AppPalette.AccentAmber.copy(alpha = 0.12f))
                    .border(1.dp, AppPalette.AccentAmber.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
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
                    color = AppPalette.ElectricCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.tactilePress(
                        animationsEnabled = animationsEnabled,
                        onClick = onSwapLanguages
                    )
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredEntrance(index = 1, enabled = animationsEnabled)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            HeroPowerButton(
                isServiceActive = isServiceActive,
                animationsEnabled = animationsEnabled,
                onClick = onPowerButtonClick
            )
        }

        AnimatedGlassCard(
            staggerIndex = 2,
            animationsEnabled = animationsEnabled,
            flashTrigger = autoCardFlash
        ) {
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

        AnimatedGlassCard(
            staggerIndex = 3,
            animationsEnabled = animationsEnabled,
            flashTrigger = privacyCardFlash
        ) {
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
                color = AppPalette.GlassBorder
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
    downloadStates: DownloadProgressMap,
    isPairModelReady: Boolean,
    isDownloadingMlKit: Boolean,
    mlKitDownloadProgress: Float,
    mlKitDownloadMessage: String,
    fastPackUrlInput: String,
    fastPackEntryCount: Int,
    animationsEnabled: Boolean,
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
        Column(
            modifier = Modifier.staggeredEntrance(index = 0, enabled = animationsEnabled),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "שפות ומודלים",
                color = AppPalette.TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "בחר שפת מקור ויעד להורדה מהירה של מודלים תומכים",
                color = AppPalette.TextSecondary,
                fontSize = 13.sp
            )
        }

        AnimatedGlassCard(staggerIndex = 1, animationsEnabled = animationsEnabled) {
            Text(
                text = "בחירת שפות תרגום",
                color = AppPalette.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            LanguagePairSelectorRow(
                sourceLang = sourceLang,
                targetLang = targetLang,
                animationsEnabled = animationsEnabled,
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

        Text(
            text = "מודלים תומכים עבור ${sourceLang.nameHe} ← ${targetLang.nameHe}",
            color = AppPalette.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.staggeredEntrance(index = 2, enabled = animationsEnabled)
        )

        supportedModels.forEachIndexed { idx, model ->
            when (model.kind) {
                ModelKind.MLKIT_NEURAL_OFFLINE -> {
                    AnimatedGlassCard(
                        staggerIndex = 2 + idx,
                        animationsEnabled = animationsEnabled
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = model.titleHe,
                                    color = AppPalette.TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = model.approxSizeLabel,
                                    color = AppPalette.ElectricCyan,
                                    fontSize = 12.sp
                                )
                            }
                            PulsingStatusPill(
                                label = when {
                                    isDownloadingMlKit -> "מוריד..."
                                    isPairModelReady -> "מותקן"
                                    else -> "זמין להורדה"
                                },
                                color = when {
                                    isDownloadingMlKit -> AppPalette.ElectricCyan
                                    isPairModelReady -> AppPalette.EmeraldGreen
                                    else -> AppPalette.AccentAmber
                                },
                                pulsing = isDownloadingMlKit,
                                animationsEnabled = animationsEnabled
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = model.subtitleHe,
                            color = AppPalette.TextSecondary,
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
                                },
                                animationsEnabled = animationsEnabled
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
                                    containerColor = if (isPairModelReady) Color(0xFF232B3E) else AppPalette.ElectricBlue
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
                                    border = BorderStroke(1.dp, AppPalette.AccentRed.copy(alpha = 0.45f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = AppPalette.AccentRed.copy(alpha = 0.10f)
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

                    AnimatedGlassCard(
                        staggerIndex = 2 + idx,
                        animationsEnabled = animationsEnabled
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = model.titleHe,
                                    color = AppPalette.TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "האצת הורדה • 60 חלקים מקבילים (HTTP Range)",
                                    color = AppPalette.ElectricCyan,
                                    fontSize = 12.sp
                                )
                            }
                            PulsingStatusPill(
                                label = when {
                                    isBusyDl -> "${(dlState.progress * 100).roundToInt()}%"
                                    fastPackEntryCount > 0 -> "$fastPackEntryCount מונחים"
                                    isPackInstalled -> "מותקן"
                                    else -> "60 חלקים"
                                },
                                color = when {
                                    isBusyDl -> AppPalette.ElectricCyan
                                    isPackInstalled -> AppPalette.EmeraldGreen
                                    else -> AppPalette.GlowPurple
                                },
                                pulsing = isBusyDl,
                                animationsEnabled = animationsEnabled
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = model.subtitleHe,
                            color = AppPalette.TextSecondary,
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
                                        color = AppPalette.TextSecondary.copy(alpha = 0.65f),
                                        fontSize = 12.sp
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = AppPalette.GlassCardInner,
                                    unfocusedContainerColor = AppPalette.GlassCardInner,
                                    focusedBorderColor = AppPalette.ElectricCyan.copy(alpha = 0.7f),
                                    unfocusedBorderColor = AppPalette.GlassBorder,
                                    focusedTextColor = AppPalette.TextPrimary,
                                    unfocusedTextColor = AppPalette.TextPrimary,
                                    cursorColor = AppPalette.ElectricCyan
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
                                animationsEnabled = animationsEnabled,
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
                                    border = BorderStroke(1.dp, AppPalette.AccentAmber.copy(alpha = 0.5f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = AppPalette.AccentAmber.copy(alpha = 0.12f)
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
                                    colors = ButtonDefaults.buttonColors(containerColor = AppPalette.ElectricBlue)
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
                                        border = BorderStroke(1.dp, AppPalette.AccentRed.copy(alpha = 0.45f)),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = AppPalette.AccentRed.copy(alpha = 0.10f)
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

        AnimatedGlassCard(staggerIndex = 4, animationsEnabled = animationsEnabled) {
            Text(
                text = "שפות מותקנות במכשיר (${downloadedLangCodes.size})",
                color = AppPalette.TextPrimary,
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
                color = AppPalette.TextSecondary,
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
                border = BorderStroke(1.dp, AppPalette.GlassBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White.copy(alpha = 0.03f)
                )
            ) {
                Text("נקה מטמון זיכרון זמני", color = AppPalette.TextPrimary, fontSize = 13.sp)
            }
            if (statusMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = statusMessage, color = AppPalette.TextSecondary, fontSize = 12.sp)
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
    animationsEnabled: Boolean,
    sampleInput: String,
    sampleOutput: String,
    isTestingTranslation: Boolean,
    translationRevealKey: Int,
    originalPreviewBitmap: ImageBitmap?,
    translatedPreviewBitmap: ImageBitmap?,
    showOriginalImage: Boolean,
    isTranslatingImage: Boolean,
    imageTranslationStatus: String,
    imageTranslatedEntries: List<TranslatedImageEntry>,
    hasSelectedImage: Boolean,
    onOpenSourcePicker: () -> Unit,
    onOpenTargetPicker: () -> Unit,
    onSwapLanguages: () -> Unit,
    onSampleInputChange: (String) -> Unit,
    onResetSamplePhrase: () -> Unit,
    onPickImageClick: () -> Unit,
    onRetranslateImageClick: () -> Unit,
    onToggleShowOriginalImage: (Boolean) -> Unit,
    onClearImageClick: () -> Unit,
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
        Column(
            modifier = Modifier.staggeredEntrance(index = 0, enabled = animationsEnabled),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "מודל ובדיקת איכות",
                color = AppPalette.TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${sourceLang.nameHe} ← ${targetLang.nameHe} • בדיקת טקסט ותמונות",
                color = AppPalette.TextSecondary,
                fontSize = 13.sp
            )
        }

        AnimatedGlassCard(staggerIndex = 1, animationsEnabled = animationsEnabled) {
            LanguagePairSelectorRow(
                sourceLang = sourceLang,
                targetLang = targetLang,
                animationsEnabled = animationsEnabled,
                onOpenSourcePicker = onOpenSourcePicker,
                onOpenTargetPicker = onOpenTargetPicker,
                onSwapLanguages = onSwapLanguages
            )
        }

        AnimatedGlassCard(staggerIndex = 2, animationsEnabled = animationsEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "תרגום תמונה מהגלריה",
                        color = AppPalette.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "העלה צילום מסך או תמונה ותראה את התרגום מוטמע ישירות עליה",
                        color = AppPalette.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                if (translatedPreviewBitmap != null) {
                    PulsingStatusPill(
                        label = if (showOriginalImage) "מקור" else "מתורגם",
                        color = if (showOriginalImage) AppPalette.AccentAmber else AppPalette.EmeraldGreen,
                        animationsEnabled = animationsEnabled,
                        modifier = Modifier.tactilePress(
                            animationsEnabled = animationsEnabled,
                            onClick = { onToggleShowOriginalImage(!showOriginalImage) }
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val activeBitmap = if (showOriginalImage) {
                originalPreviewBitmap
            } else {
                translatedPreviewBitmap ?: originalPreviewBitmap
            }

            if (activeBitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 380.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(AppPalette.GlassCardInner)
                        .border(1.dp, AppPalette.GlassBorder, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = showOriginalImage to activeBitmap,
                        transitionSpec = {
                            fadeIn(tween(if (animationsEnabled) 260 else 1)) togetherWith
                                fadeOut(tween(if (animationsEnabled) 180 else 1))
                        },
                        label = "ImageCompareCrossfade"
                    ) { (_, bmp) ->
                        Image(
                            bitmap = bmp,
                            contentDescription = "תמונה מתורגמת",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (isTranslatingImage) {
                        Box(modifier = Modifier.matchParentSize()) {
                            ImageScanLaserOverlay(
                                statusText = imageTranslationStatus,
                                animationsEnabled = animationsEnabled
                            )
                        }
                    }
                }
            }

            if (imageTranslationStatus.isNotEmpty() && !isTranslatingImage) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = imageTranslationStatus,
                    color = if (translatedPreviewBitmap != null) AppPalette.EmeraldGreen else AppPalette.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPickImageClick,
                    enabled = !isTranslatingImage,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppPalette.ElectricBlue)
                ) {
                    Text(
                        text = if (hasSelectedImage) "העלה תמונה אחרת" else "העלה תמונה לתרגום",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (hasSelectedImage && !isTranslatingImage) {
                    OutlinedButton(
                        onClick = onRetranslateImageClick,
                        modifier = Modifier.height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AppPalette.ElectricCyan.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = AppPalette.ElectricCyan.copy(alpha = 0.10f)
                        )
                    ) {
                        Text("תרגם שוב", color = AppPalette.ElectricCyan, fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = onClearImageClick,
                        modifier = Modifier.height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AppPalette.GlassBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White.copy(alpha = 0.04f)
                        )
                    ) {
                        Text("נקה", color = AppPalette.TextSecondary, fontSize = 13.sp)
                    }
                }
            }

            if (imageTranslatedEntries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(thickness = 1.dp, color = AppPalette.GlassBorder)
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    imageTranslatedEntries.take(8).forEachIndexed { entryIdx, entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .staggeredEntrance(index = entryIdx, enabled = animationsEnabled)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppPalette.GlassCardInner)
                                .border(1.dp, AppPalette.GlassBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides inputDir) {
                                Text(
                                    text = entry.sourceText,
                                    color = AppPalette.TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            CompositionLocalProvider(LocalLayoutDirection provides outputDir) {
                                Text(
                                    text = entry.translatedText,
                                    color = AppPalette.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        ModelGaugeCard(
            sourceLang = sourceLang,
            targetLang = targetLang,
            isModelReady = isPairModelReady,
            isDownloading = isDownloadingMlKit,
            progress = mlKitDownloadProgress,
            statusNote = mlKitDownloadMessage,
            fastPackEntryCount = fastPackEntryCount,
            animationsEnabled = animationsEnabled,
            staggerIndex = 3,
            onDownloadModelClick = onDownloadMlKitClick
        )

        AnimatedGlassCard(staggerIndex = 4, animationsEnabled = animationsEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "בדיקת תרגום טקסט",
                    color = AppPalette.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PulsingStatusPill(
                        label = "דוגמה",
                        color = AppPalette.ElectricBlueLight,
                        animationsEnabled = animationsEnabled,
                        modifier = Modifier.tactilePress(
                            animationsEnabled = animationsEnabled,
                            onClick = onResetSamplePhrase
                        )
                    )
                    PulsingStatusPill(
                        label = if (offlineOnlyMode) "מקומי" else "היברידי",
                        color = AppPalette.ElectricCyan,
                        animationsEnabled = animationsEnabled
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
                        Text("הזן טקסט ב${sourceLang.nameHe}...", color = AppPalette.TextSecondary)
                    },
                    singleLine = false,
                    minLines = 2,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = AppPalette.GlassCardInner,
                        unfocusedContainerColor = AppPalette.GlassCardInner,
                        focusedBorderColor = AppPalette.ElectricBlue.copy(alpha = 0.7f),
                        unfocusedBorderColor = AppPalette.GlassBorder,
                        focusedTextColor = AppPalette.TextPrimary,
                        unfocusedTextColor = AppPalette.TextPrimary,
                        cursorColor = AppPalette.ElectricBlue
                    )
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                GlowingDownArrow(
                    active = isTestingTranslation || sampleOutput.isNotEmpty(),
                    animationsEnabled = animationsEnabled
                )
            }

            CompositionLocalProvider(LocalLayoutDirection provides outputDir) {
                ShimmerTranslationResultBox(
                    isLoading = isTestingTranslation,
                    translatedText = sampleOutput,
                    placeholderText = "התרגום ל${targetLang.nameHe} יופיע כאן בזמן אמת",
                    revealKey = translationRevealKey,
                    animationsEnabled = animationsEnabled
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
                    containerColor = AppPalette.ElectricBlue,
                    disabledContainerColor = AppPalette.ElectricBlue.copy(alpha = 0.5f)
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
    enableUiAnimations: Boolean,
    skipBrandNames: Boolean,
    offlineOnlyMode: Boolean,
    onGrantOverlayClick: () -> Unit,
    onToggleScanWave: (Boolean) -> Unit,
    onToggleUiAnimations: (Boolean) -> Unit,
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
        Column(
            modifier = Modifier.staggeredEntrance(index = 0, enabled = enableUiAnimations),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "הגדרות",
                color = AppPalette.TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "הרשאות, אפקטים והתאמת שכבת התרגום",
                color = AppPalette.TextSecondary,
                fontSize = 13.sp
            )
        }

        AnimatedGlassCard(staggerIndex = 1, animationsEnabled = enableUiAnimations) {
            Text(
                text = "הרשאות מערכת",
                color = AppPalette.TextPrimary,
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
                    Text("תצוגה מעל אפליקציות", color = AppPalette.TextPrimary, fontSize = 14.sp)
                    Text(
                        "נדרש להצגת התרגום ולשונית הצד מעל מסכים אחרים",
                        color = AppPalette.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                PulsingStatusPill(
                    label = if (hasOverlayPermission) "מאושר" else "חסר",
                    color = if (hasOverlayPermission) AppPalette.EmeraldGreen else AppPalette.AccentRed,
                    pulsing = !hasOverlayPermission,
                    animationsEnabled = enableUiAnimations
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("התראת שירות פעיל", color = AppPalette.TextPrimary, fontSize = 14.sp)
                    Text(
                        "מציגה חיווי קבוע כשהמתרגם רץ ברקע",
                        color = AppPalette.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                PulsingStatusPill(
                    label = if (hasNotificationPermission) "מאושר" else "חסר",
                    color = if (hasNotificationPermission) AppPalette.EmeraldGreen else AppPalette.AccentAmber,
                    animationsEnabled = enableUiAnimations
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
                onClick = onGrantOverlayClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AppPalette.ElectricBlue.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = AppPalette.ElectricBlue.copy(alpha = 0.08f)
                )
            ) {
                Text(
                    text = if (hasOverlayPermission) "פתח הגדרות תצוגה צפה" else "אשר תצוגה מעל אפליקציות",
                    color = AppPalette.ElectricBlueLight,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        AnimatedGlassCard(
            staggerIndex = 2,
            animationsEnabled = enableUiAnimations,
            flashTrigger = visualCardFlash
        ) {
            Text(
                text = "אנימציות ואפקטים",
                color = AppPalette.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            GlassToggleRow(
                title = "אנימציות ואפקטים מלאים בממשק",
                subtitle = "רקע Aurora, חלקיקי אור, כניסה מדורגת לכרטיסיות ולייזר סריקה לתמונות",
                checked = enableUiAnimations,
                onCheckedChange = {
                    visualCardFlash++
                    onToggleUiAnimations(it)
                }
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                thickness = 1.dp,
                color = AppPalette.GlassBorder
            )
            GlassToggleRow(
                title = "גל סריקה כחול-טורקיז במסך",
                subtitle = "פס אור שוטף את המסך וחושף את התרגום ב-Crossfade כשהגל עובר",
                checked = enableScanWave,
                onCheckedChange = {
                    visualCardFlash++
                    onToggleScanWave(it)
                }
            )
        }

        AnimatedGlassCard(
            staggerIndex = 3,
            animationsEnabled = enableUiAnimations,
            flashTrigger = filterCardFlash
        ) {
            Text(
                text = "סינון ופרטיות",
                color = AppPalette.TextPrimary,
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
                color = AppPalette.GlassBorder
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
private fun LanguagePairSelectorRow(
    sourceLang: LanguageOption,
    targetLang: LanguageOption,
    animationsEnabled: Boolean,
    onOpenSourcePicker: () -> Unit,
    onOpenTargetPicker: () -> Unit,
    onSwapLanguages: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LanguageChipButton(
            roleLabel = "שפת מקור",
            lang = sourceLang,
            animationsEnabled = animationsEnabled,
            onClick = onOpenSourcePicker,
            modifier = Modifier.weight(1f)
        )

        AnimatedSwapButton(
            animationsEnabled = animationsEnabled,
            onClick = onSwapLanguages
        )

        LanguageChipButton(
            roleLabel = "שפת יעד",
            lang = targetLang,
            animationsEnabled = animationsEnabled,
            onClick = onOpenTargetPicker,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LanguageChipButton(
    roleLabel: String,
    lang: LanguageOption,
    animationsEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .tactilePress(animationsEnabled = animationsEnabled, onClick = onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(AppPalette.GlassCardInner)
            .border(1.dp, AppPalette.GlassBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = roleLabel,
            color = AppPalette.TextSecondary,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = lang.nameHe,
            color = AppPalette.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = lang.nativeName,
            color = AppPalette.ElectricCyan.copy(alpha = 0.85f),
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
    animationsEnabled: Boolean,
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
                .staggeredEntrance(index = 0, enabled = animationsEnabled)
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
                    color = AppPalette.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "סגור",
                    color = AppPalette.ElectricBlueLight,
                    fontSize = 13.sp,
                    modifier = Modifier.tactilePress(
                        animationsEnabled = animationsEnabled,
                        onClick = onDismiss
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("חפש שפה...", color = AppPalette.TextSecondary, fontSize = 13.sp) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AppPalette.GlassCardInner,
                    unfocusedContainerColor = AppPalette.GlassCardInner,
                    focusedBorderColor = AppPalette.ElectricBlue,
                    unfocusedBorderColor = AppPalette.GlassBorder,
                    focusedTextColor = AppPalette.TextPrimary,
                    unfocusedTextColor = AppPalette.TextPrimary
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
                            .tactilePress(
                                animationsEnabled = animationsEnabled,
                                onClick = { onSelect(lang) }
                            )
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) AppPalette.ElectricBlue.copy(alpha = 0.22f) else AppPalette.GlassCardInner
                            )
                            .border(
                                1.dp,
                                if (isSelected) AppPalette.ElectricBlueLight.copy(alpha = 0.6f) else AppPalette.GlassBorder,
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.nameHe,
                                color = AppPalette.TextPrimary,
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
                                color = AppPalette.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        if (isDownloaded) {
                            PulsingStatusPill(
                                label = "מותקן",
                                color = AppPalette.EmeraldGreen,
                                animationsEnabled = animationsEnabled
                            )
                        }
                    }
                }
            }
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
    animationsEnabled: Boolean,
    staggerIndex: Int,
    onDownloadModelClick: () -> Unit
) {
    AnimatedGlassCard(staggerIndex = staggerIndex, animationsEnabled = animationsEnabled) {
        Text(
            text = "מודל לא-מקוון (${sourceLang.code.uppercase()} ← ${targetLang.code.uppercase()})",
            color = AppPalette.TextPrimary,
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
                progress = progress,
                animationsEnabled = animationsEnabled
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
                    color = AppPalette.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                if (fastPackEntryCount > 0) {
                    PulsingStatusPill(
                        label = "חבילה מהירה פעילה ($fastPackEntryCount מונחים)",
                        color = AppPalette.ElectricCyan,
                        animationsEnabled = animationsEnabled
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
                labelHe = statusNote.ifEmpty { "מוריד מודל..." },
                animationsEnabled = animationsEnabled
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
                color = if (isModelReady) Color.White.copy(alpha = 0.22f) else AppPalette.ElectricBlue
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isModelReady) {
                    Color.White.copy(alpha = 0.04f)
                } else {
                    AppPalette.ElectricBlue.copy(alpha = 0.16f)
                }
            )
        ) {
            Text(
                text = when {
                    isDownloading -> "מוריד מודל..."
                    isModelReady -> "אמת מודל"
                    else -> "הורד מודל ${sourceLang.nameHe} ← ${targetLang.nameHe}"
                },
                color = AppPalette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
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
                CrashGuard.safeRun("ToggleHaptic") {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
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
                color = AppPalette.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = AppPalette.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        ObsidianToggleSwitch(
            checked = checked,
            onCheckedChange = {
                CrashGuard.safeRun("ToggleHaptic") {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                onCheckedChange(it)
            }
        )
    }
}
