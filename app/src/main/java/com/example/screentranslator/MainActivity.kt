package com.example.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

private val BgDark = Color(0xFF0E1116)
private val CardDark = Color(0xFF171B24)
private val CardBorder = Color(0xFF262C3A)
private val AccentBlue = Color(0xFF3B82F6)
private val AccentGreen = Color(0xFF22C55E)
private val AccentAmber = Color(0xFFF59E0B)
private val AccentRed = Color(0xFFEF4444)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)

class MainActivity : ComponentActivity() {

    private lateinit var localTranslator: OfflineEnHeTranslator

    private var hasOverlayPermission by mutableStateOf(false)
    private var isModelReady by mutableStateOf(false)
    private var isDownloadingModel by mutableStateOf(false)
    private var isServiceActive by mutableStateOf(false)
    private var startInAutoMode by mutableStateOf(true)
    private var offlineOnlyMode by mutableStateOf(false)
    private var skipBrandNames by mutableStateOf(true)
    private var statusMessage by mutableStateOf("מוכן להפעלה")

    private var sampleInput by mutableStateOf("Settings will be saved automatically when you exit.")
    private var sampleOutput by mutableStateOf("")
    private var isTestingTranslation by mutableStateOf(false)

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayService.EXTRA_DATA_INTENT, result.data)
                    putExtra(OverlayService.EXTRA_AUTO_MODE, startInAutoMode)
                    putExtra(OverlayService.EXTRA_OFFLINE_ONLY, offlineOnlyMode)
                    putExtra(OverlayService.EXTRA_SKIP_BRANDS, skipBrandNames)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                isServiceActive = true
                statusMessage = "המתרגם הצף פעיל — עבור לכל אפליקציה באנגלית"
            } else {
                statusMessage = "הרשאת צילום המסך בוטלה"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localTranslator = OfflineEnHeTranslator(applicationContext)
        refreshStates()
        checkOfflineModel()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BgDark,
                    surface = CardDark,
                    primary = AccentBlue
                )
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MainScreen(
                        hasOverlayPermission = hasOverlayPermission,
                        isModelReady = isModelReady,
                        isDownloadingModel = isDownloadingModel,
                        isServiceActive = isServiceActive,
                        startInAutoMode = startInAutoMode,
                        offlineOnlyMode = offlineOnlyMode,
                        skipBrandNames = skipBrandNames,
                        statusMessage = statusMessage,
                        sampleInput = sampleInput,
                        sampleOutput = sampleOutput,
                        isTestingTranslation = isTestingTranslation,
                        onSampleInputChange = { sampleInput = it },
                        onToggleAutoMode = { startInAutoMode = it },
                        onToggleOfflineOnly = { offlineOnlyMode = it },
                        onToggleSkipBrands = { skipBrandNames = it },
                        onDownloadModelClick = { downloadOfflineModel() },
                        onTestTranslationClick = { testTranslation() },
                        onGrantOverlayClick = { openOverlaySettings() },
                        onStartOverlayClick = { startOverlayFlow() },
                        onStopOverlayClick = { stopOverlayService() }
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
        if (::localTranslator.isInitialized) {
            localTranslator.close()
        }
        super.onDestroy()
    }

    private fun refreshStates() {
        hasOverlayPermission = Settings.canDrawOverlays(this)
        isServiceActive = OverlayService.isRunning
    }

    private fun checkOfflineModel() {
        lifecycleScope.launch {
            isModelReady = localTranslator.isModelDownloaded()
            if (isModelReady) {
                statusMessage = "המנוע ההיברידי + גיבוי לא-מקוון מוכנים לפעולה"
            }
        }
    }

    private fun downloadOfflineModel() {
        if (isDownloadingModel) return
        isDownloadingModel = true
        statusMessage = "מוריד מודל לא-מקוון אנגלית–עברית למכשיר..."
        lifecycleScope.launch {
            val result = localTranslator.prepareModel()
            isDownloadingModel = false
            if (result.isSuccess) {
                isModelReady = true
                statusMessage = "המודל הלא-מקוון מוכן — התרגום יעבוד גם ללא אינטרנט"
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
            if (offlineOnlyMode && !isModelReady) {
                val res = localTranslator.prepareModel()
                if (res.isFailure) {
                    sampleOutput = "יש להוריד קודם את המודל הלא-מקוון"
                    isTestingTranslation = false
                    return@launch
                }
                isModelReady = true
            }
            val translated = localTranslator.translateEnglishToHebrew(
                text = sampleInput,
                offlineOnly = offlineOnlyMode
            )
            sampleOutput = if (translated.isNotEmpty()) translated else "לא נמצא טקסט באנגלית לתרגום"
            isTestingTranslation = false
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun startOverlayFlow() {
        if (!Settings.canDrawOverlays(this)) {
            statusMessage = "יש לאשר תחילה תצוגה מעל אפליקציות אחרות"
            openOverlaySettings()
            return
        }
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        isServiceActive = false
        statusMessage = "המתרגם הצף הופסק"
    }
}

@Composable
private fun MainScreen(
    hasOverlayPermission: Boolean,
    isModelReady: Boolean,
    isDownloadingModel: Boolean,
    isServiceActive: Boolean,
    startInAutoMode: Boolean,
    offlineOnlyMode: Boolean,
    skipBrandNames: Boolean,
    statusMessage: String,
    sampleInput: String,
    sampleOutput: String,
    isTestingTranslation: Boolean,
    onSampleInputChange: (String) -> Unit,
    onToggleAutoMode: (Boolean) -> Unit,
    onToggleOfflineOnly: (Boolean) -> Unit,
    onToggleSkipBrands: (Boolean) -> Unit,
    onDownloadModelClick: () -> Unit,
    onTestTranslationClick: () -> Unit,
    onGrantOverlayClick: () -> Unit,
    onStartOverlayClick: () -> Unit,
    onStopOverlayClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "מתרגם מסך",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "תרגום מהיר ומדויק אנגלית -> עברית עם התאמת צבעים מלאה",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }

        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "סטטוס מערכת",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                StatusDotBadge(
                    label = if (isServiceActive) "פעיל" else "כבוי",
                    color = if (isServiceActive) AccentGreen else TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusMessage,
                color = TextPrimary,
                fontSize = 15.sp
            )
        }

        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "1. הפעלה והגדרות תרגום",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                StatusDotBadge(
                    label = if (hasOverlayPermission) "מאושר" else "נדרש אישור",
                    color = if (hasOverlayPermission) AccentGreen else AccentRed
                )
            }

            if (!hasOverlayPermission) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onGrantOverlayClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("פתח הגדרות ואשר תצוגה צפה", color = TextPrimary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            SettingToggleRow(
                title = "זיהוי שינויים אוטומטי",
                subtitle = "מתרגם אוטומטית רק אזורים שהשתנו ומנקה במעבר מסך",
                checked = startInAutoMode,
                onCheckedChange = onToggleAutoMode
            )
            Spacer(modifier = Modifier.height(10.dp))
            SettingToggleRow(
                title = "דלג על שמות אפליקציות ומותגים",
                subtitle = "מונע תרגומים מילוליים כמו Gemini, Brave, Waze ו-Bit",
                checked = skipBrandNames,
                onCheckedChange = onToggleSkipBrands
            )
            Spacer(modifier = Modifier.height(10.dp))
            SettingToggleRow(
                title = "מצב לא-מקוון בלבד",
                subtitle = "כבוי = תרגום עצבי מדויק ברשת + מעבר אוטומטי לאופליין",
                checked = offlineOnlyMode,
                onCheckedChange = onToggleOfflineOnly
            )

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartOverlayClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Text(
                        text = "הפעל מתרגם מסך",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (isServiceActive) {
                    Button(
                        onClick = onStopOverlayClick,
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2024))
                    ) {
                        Text("עצור", color = Color(0xFFFCA5A5), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "2. גיבוי תרגום לא-מקוון (EN -> HE)",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                StatusDotBadge(
                    label = when {
                        isDownloadingModel -> "מוריד..."
                        isModelReady -> "מוכן"
                        else -> "לא הורד"
                    },
                    color = when {
                        isDownloadingModel -> AccentAmber
                        isModelReady -> AccentGreen
                        else -> AccentAmber
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "מאפשר תרגום גם כשאין אינטרנט או במצב טיסה. תרגומים שכבר בוצעו נשמרים מקומית למהירות מיידית.",
                color = TextSecondary,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onDownloadModelClick,
                enabled = !isDownloadingModel,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isModelReady) Color(0xFF232936) else AccentBlue
                )
            ) {
                if (isDownloadingModel) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("מוריד מודל...", color = Color.White)
                } else {
                    Text(
                        text = if (isModelReady) "אמת מודל לא-מקוון" else "הורד מודל לא-מקוון",
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        SectionCard {
            Text(
                text = "3. בדיקת איכות תרגום",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = sampleInput,
                onValueChange = onSampleInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("טקסט לבדיקה באנגלית", color = TextSecondary) },
                singleLine = false,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = CardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentBlue
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onTestTranslationClick,
                enabled = !isTestingTranslation,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF232936))
            ) {
                Text(
                    text = if (isTestingTranslation) "מתרגם..." else "בדוק תרגום לעברית",
                    color = TextPrimary
                )
            }
            if (sampleOutput.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF10131A))
                        .padding(12.dp)
                ) {
                    Text(
                        text = sampleOutput,
                        color = AccentGreen,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue)
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardDark)
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun StatusDotBadge(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
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
