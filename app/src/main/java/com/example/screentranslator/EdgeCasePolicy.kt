package com.example.screentranslator

import android.content.Context
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Build
import android.os.StatFs
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * מנוע ומדריך מרכזי לניהול מצבי קיצון (Edge Cases) באפליקציית מתרגם המסך.
 *
 * כל רכיב באפליקציה (לכידה, OCR, תרגום, הורדת מודלים ושכבת Overlay)
 * מתייעץ עם קובץ זה כדי להבטיח יציבות, פרטיות והתאוששות אוטומטית מתקלות.
 */
object EdgeCasePolicy {

    enum class Severity(val labelHe: String) {
        CRITICAL("קריטי"),
        HIGH("גבוה"),
        MEDIUM("בינוני"),
        LOW("נמוך")
    }

    data class EdgeCaseGuide(
        val id: String,
        val titleHe: String,
        val triggerHe: String,
        val resolutionHe: String,
        val severity: Severity
    )

    data class PairValidationResult(
        val isValid: Boolean,
        val warningHe: String? = null,
        val errorHe: String? = null,
        val ocrSupportedOnScreen: Boolean = true
    )

    data class DownloadStrategy(
        val partsCount: Int,
        val maxConcurrentSockets: Int,
        val useSegmentedRange: Boolean,
        val reasonHe: String
    )

    const val TARGET_SEGMENT_COUNT = 60
    const val MIN_BYTES_FOR_60_PARTS = 120_000L // ~120KB minimum so each part is at least 2KB
    const val MAX_RETRIES_PER_SEGMENT = 3
    const val MAX_ONLINE_RESPONSE_BYTES = 65_536
    const val MIN_FREE_STORAGE_MARGIN_BYTES = 60L * 1024L * 1024L // 60MB safety buffer

    /**
     * מדריך מובנה של כל מצבי הקיצון ודרכי ההתמודדות שהאפליקציה מיישמת בפועל.
     */
    val playbook: List<EdgeCaseGuide> = listOf(
        EdgeCaseGuide(
            id = "EC-01",
            titleHe = "שפת מקור ויעד זהות",
            triggerHe = "המשתמש בחר את אותה שפה במקור וביעד (למשל עברית -> עברית).",
            resolutionHe = "חסימת פעולת התרגום והחלפה אוטומטית לשפת הנגד או הצגת התראה ברורה.",
            severity = Severity.MEDIUM
        ),
        EdgeCaseGuide(
            id = "EC-02",
            titleHe = "שפת מקור בכתב לא-לטיני בזיהוי מסך",
            triggerHe = "נבחרה שפת מקור שאינה באותיות לטיניות (כגון יפנית, רוסית, ערבית או עברית) כשהמנוע המובנה ל-OCR הוא לטיני.",
            resolutionHe = "הצגת אזהרה ממוקדת: בדיקת טקסט ותרגום בארגז החול פועלים מלא, בעוד זיהוי מסך (OCR) מותאם לשפות בכתב לטיני.",
            severity = Severity.HIGH
        ),
        EdgeCaseGuide(
            id = "EC-03",
            titleHe = "שרת שלא תומך בפיצול ל-60 חלקים (HTTP Range)",
            triggerHe = "השרת לא מחזיר Accept-Ranges: bytes או חוסם חיבורים מקבילים עם קוד 429/503.",
            resolutionHe = "בדיקת HEAD מקדימה. אם אין תמיכה ב-Range או שהקובץ קטן מדי — מעבר שקוף לזרם יחיד. בעומס 429/503 — השהיה מדורגת וניסיון חוזר.",
            severity = Severity.HIGH
        ),
        EdgeCaseGuide(
            id = "EC-04",
            titleHe = "ניתוק רשת או השחתת קובץ באמצע הורדת מודל",
            triggerHe = "איבוד קליטה סלולרית או סגירת האפליקציה באמצע הורדה מרובת חלקים.",
            resolutionHe = "כתיבה לקובץ זמני (.part) ואימות גודל מלא בסיום. במקרה כשל — ניקוי הקובץ החלקי ומניעת טעינת מודל פגום.",
            severity = Severity.CRITICAL
        ),
        EdgeCaseGuide(
            id = "EC-05",
            titleHe = "אחסון פנוי נמוך במכשיר",
            triggerHe = "פחות מ-60MB פנויים + גודל המודל המבוקש באחסון הפנימי.",
            resolutionHe = "עצירה לפני תחילת ההורדה והצגת הודעה על חוסר מקום פנוי במקום קריסת IOException.",
            severity = Severity.HIGH
        ),
        EdgeCaseGuide(
            id = "EC-06",
            titleHe = "חסימת מגע (קיפאון מסך) באנדרואיד 12+",
            triggerHe = "שכבת Overlay באטימות גבוהה מסף המערכת (0.80) גורמת לאנדרואיד לחסום לחיצות וגלילה.",
            resolutionHe = "אכיפת אטימות מתחת ל-InputManager.maximumObscuringOpacityForTouch והסתרה מוחלטת (GONE) כשאין תרגומים.",
            severity = Severity.CRITICAL
        ),
        EdgeCaseGuide(
            id = "EC-07",
            titleHe = "ביטול הרשאת הקלטת מסך (MediaProjection) או סיבוב מסך",
            triggerHe = "המערכת מבטלת הקלטה ברקע או שהמכשיר מסתובב בין אנכי לאופקי.",
            resolutionHe = "בביטול הרשאה — עצירת שירות נקייה ללא קריסה. בסיבוב מסך — התאמת VirtualDisplay (resize) וניקוי בועות ישנות.",
            severity = Severity.HIGH
        ),
        EdgeCaseGuide(
            id = "EC-08",
            titleHe = "דליפת מידע רגיש בתרגום מקוון",
            triggerHe = "טקסט במסך מכיל כתובת מייל, מספר כרטיס אשראי או קוד אימות חד-פעמי (OTP).",
            resolutionHe = "ניתוב כפוי למנוע הלא-מקוון בלבד ואי-שמירה במטמון, גם אם מצב היברידי דלוק.",
            severity = Severity.CRITICAL
        ),
        EdgeCaseGuide(
            id = "EC-09",
            titleHe = "בועות ישנות שצצות אחרי מעבר מסך או לחיצה על נקה",
            triggerHe = "סריקה איטית מסתיימת אחרי שהמשתמש כבר עבר אפליקציה או לחץ על 'נקה תרגום'.",
            resolutionHe = "שימוש במונה דורות אטומי (overlayGeneration) שפוסל אוטומטית תוצאות ישנות.",
            severity = Severity.MEDIUM
        ),
        EdgeCaseGuide(
            id = "EC-10",
            titleHe = "שמות מותגים, קוד או טקסט שכבר בשפת היעד",
            triggerHe = "זיהוי שמות כמו Spotify/Waze, נתיבי קבצים או טקסט שכבר כתוב בשפת היעד.",
            resolutionHe = "סינון חכם לפני תרגום ובדיקת תקינות התוצאה מול כתב שפת היעד (RTL/LTR).",
            severity = Severity.MEDIUM
        ),
        EdgeCaseGuide(
            id = "EC-11",
            titleHe = "קישור הורדה לא מאובטח (HTTP / כתובת פנימית)",
            triggerHe = "ניסיון הורדת חבילה מקישור לא מוצפן או מכתובת מקומית.",
            resolutionHe = "אכיפת HTTPS בלבד וחסימת כתובות localhost / IP פנימי למניעת חשיפה ו-MITM.",
            severity = Severity.CRITICAL
        ),
        EdgeCaseGuide(
            id = "EC-12",
            titleHe = "שחרור משאבי זיכרון (Bitmap & ML Kit JNI)",
            triggerHe = "הפעלות חוזרות של סריקת מסך ומעבר בין מודלים שונים עלולים לגרום ל-OutOfMemory.",
            resolutionHe = "לכידה בחצי רזולוציה עם שימוש חוזר ב-Buffer, סגירת לקוחות ML Kit ישנים ומיחזור Bitmap מובטח ב-finally.",
            severity = Severity.HIGH
        )
    )

    private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val cardNumberRegex = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
    private val sensitiveKeywordWithCodeRegex = Regex(
        "(?i)\\b(otp|pin|cvv|password|passcode|token|secret)\\b.*\\b\\d{4,8}\\b|\\b\\d{4,8}\\b.*\\b(otp|pin|cvv|code)\\b"
    )
    private val urlOrCodeFileRegex = Regex(
        ".*(://|www\\.|\\.(kt|java|xml|yml|yaml|json|gradle|apk|png|jpg|mp4)\\b|[A-Za-z0-9_]+/[A-Za-z0-9_]+/[A-Za-z0-9_]+).*",
        RegexOption.IGNORE_CASE
    )
    private val latinWordRegex = Regex("[\\p{IsLatin}]{2,}")

    private val skippedBrands = setOf(
        "whatsapp", "spotify", "waze", "gemini", "firefox", "chrome", "youtube",
        "instagram", "telegram", "tiktok", "facebook", "netflix", "github", "gmail",
        "capcut", "metrolist", "ashell", "aida64", "shizuku", "termux", "zarchiver",
        "moovit", "egged", "canva", "discord", "reddit", "snapchat", "steam", "wolt",
        "uber", "chatgpt", "claude", "1dm", "1dm+", "wi-fi", "wifi", "bluetooth",
        "volte", "4g", "5g", "lte", "usb", "adb", "apk", "pdf", "vpn"
    )

    fun validateLanguagePair(
        source: LanguageOption,
        target: LanguageOption
    ): PairValidationResult {
        if (source.code == target.code) {
            return PairValidationResult(
                isValid = false,
                errorHe = "שפת המקור ושפת היעד זהות — יש לבחור שתי שפות שונות.",
                ocrSupportedOnScreen = false
            )
        }
        if (!source.supportsLatinOcr) {
            return PairValidationResult(
                isValid = true,
                warningHe = "שימו לב: ${source.nameHe} אינה בכתב לטיני. התרגום והמודל יעבדו מלא בארגז הבדיקה, אך זיהוי מסך (OCR) מותאם כרגע לשפות לטיניות.",
                ocrSupportedOnScreen = false
            )
        }
        return PairValidationResult(isValid = true, ocrSupportedOnScreen = true)
    }

    fun resolveSafeOverlayAlpha(context: Context): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
            (inputManager.maximumObscuringOpacityForTouch - 0.02f).coerceIn(0.65f, 0.78f)
        } else {
            0.95f
        }
    }

    fun validateDownloadUrl(rawUrl: String): Result<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("כתובת ההורדה ריקה"))
        }
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US)
            val host = uri.host?.lowercase(Locale.US).orEmpty()
            if (scheme != "https") {
                return Result.failure(SecurityException("מותר להוריד רק מקישורי HTTPS מאובטחים"))
            }
            if (host.isEmpty() ||
                host == "localhost" ||
                host == "127.0.0.1" ||
                host.startsWith("10.") ||
                host.startsWith("192.168.") ||
                host.startsWith("169.254.")
            ) {
                return Result.failure(SecurityException("כתובת שרת פנימית אינה מורשית"))
            }
            Result.success(trimmed)
        } catch (_: Exception) {
            Result.failure(IllegalArgumentException("כתובת ה-URL אינה תקינה"))
        }
    }

    fun hasEnoughStorage(targetDir: File, requiredBytes: Long): Boolean {
        return try {
            val stat = StatFs(targetDir.absolutePath)
            val available = stat.availableBytes
            val needed = (if (requiredBytes > 0L) requiredBytes else 35L * 1024L * 1024L) +
                MIN_FREE_STORAGE_MARGIN_BYTES
            available >= needed
        } catch (_: Exception) {
            true
        }
    }

    fun decideDownloadStrategy(
        contentLength: Long,
        acceptsRanges: Boolean,
        requestedParts: Int = TARGET_SEGMENT_COUNT
    ): DownloadStrategy {
        if (!acceptsRanges) {
            return DownloadStrategy(
                partsCount = 1,
                maxConcurrentSockets = 1,
                useSegmentedRange = false,
                reasonHe = "השרת אינו תומך ב-HTTP Range — הורדה בזרם ישיר"
            )
        }
        if (contentLength < MIN_BYTES_FOR_60_PARTS) {
            return DownloadStrategy(
                partsCount = 1,
                maxConcurrentSockets = 1,
                useSegmentedRange = false,
                reasonHe = "קובץ קטן — הורדה מהירה בחלק אחד"
            )
        }
        val parts = requestedParts.coerceIn(2, TARGET_SEGMENT_COUNT)
        // Limit concurrent open sockets conservatively to avoid Android socket/carrier throttling
        // while still splitting and tracking all 60 parts in parallel.
        val concurrentSockets = minOf(parts, 24)
        return DownloadStrategy(
            partsCount = parts,
            maxConcurrentSockets = concurrentSockets,
            useSegmentedRange = true,
            reasonHe = "פיצול מהיר ל-$parts חלקים מקבילים"
        )
    }

    fun computeRetryBackoffMs(attempt: Int, httpCode: Int): Long {
        val base = when (httpCode) {
            429, 503 -> 650L
            else -> 250L
        }
        return base * (1L shl attempt.coerceIn(0, 3))
    }

    fun containsSensitiveData(text: String): Boolean {
        return emailRegex.containsMatchIn(text) ||
            cardNumberRegex.containsMatchIn(text) ||
            sensitiveKeywordWithCodeRegex.containsMatchIn(text)
    }

    fun shouldSkipSourceText(
        rawText: String,
        sourceLang: LanguageOption,
        targetLang: LanguageOption,
        skipBrands: Boolean
    ): Boolean {
        val text = rawText.trim()
        if (text.length < 2) return true
        if (urlOrCodeFileRegex.matches(text)) return true

        // Skip text that is already written predominantly in the target non-Latin script
        if (targetLang.script != ScriptType.LATIN && isPredominantlyScript(text, targetLang.script)) {
            return true
        }

        if (sourceLang.supportsLatinOcr) {
            val latinCount = text.count { Character.isLetter(it) && isLatinChar(it) }
            if (latinCount < 2 || !latinWordRegex.containsMatchIn(text)) return true
            val nonSpace = text.count { !it.isWhitespace() }
            if (nonSpace > 0 && latinCount.toFloat() / nonSpace < 0.35f) return true
        }

        if (skipBrands) {
            val lower = text.lowercase(Locale.US).trim('.', ',', ':', '!', '?', '"', '\'', '(', ')')
            if (lower in skippedBrands) return true
        }

        return false
    }

    fun isValidTranslationResult(
        sourceText: String,
        translatedText: String,
        targetLang: LanguageOption
    ): Boolean {
        val clean = translatedText.trim()
        if (clean.isEmpty()) return false
        if (clean.equals(sourceText.trim(), ignoreCase = true)) return false

        return when (targetLang.script) {
            ScriptType.RTL_HEBREW -> clean.any { it in '\u0590'..'\u05FF' }
            ScriptType.RTL_ARABIC -> clean.any { it in '\u0600'..'\u06FF' }
            ScriptType.CYRILLIC -> clean.any { it in '\u0400'..'\u04FF' }
            ScriptType.GREEK -> clean.any { it in '\u0370'..'\u03FF' }
            ScriptType.DEVANAGARI -> clean.any { it in '\u0900'..'\u097F' }
            ScriptType.CJK -> clean.any {
                it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' || it in '\uAC00'..'\uD7AF'
            }
            ScriptType.LATIN -> clean.any { Character.isLetter(it) }
        }
    }

    fun clampCropRect(
        rawRect: Rect,
        padX: Int,
        padY: Int,
        screenWidth: Int,
        screenHeight: Int,
        topInset: Int,
        bottomInset: Int
    ): Rect {
        val left = (rawRect.left - padX).coerceIn(0, (screenWidth - 1).coerceAtLeast(0))
        val top = (rawRect.top - padY).coerceIn(topInset, (screenHeight - 1).coerceAtLeast(topInset))
        val right = (rawRect.right + padX).coerceIn((left + 1).coerceAtMost(screenWidth), screenWidth)
        val maxBottom = (screenHeight - bottomInset).coerceAtLeast(top + 1)
        val bottom = (rawRect.bottom + padY).coerceIn((top + 1).coerceAtMost(maxBottom), maxBottom)
        return Rect(left, top, right, bottom)
    }

    private fun isLatinChar(c: Char): Boolean {
        return c in 'A'..'Z' || c in 'a'..'z' || c in '\u00C0'..'\u024F'
    }

    private fun isPredominantlyScript(text: String, script: ScriptType): Boolean {
        var matching = 0
        var letters = 0
        for (c in text) {
            if (!Character.isLetter(c)) continue
            letters++
            val hit = when (script) {
                ScriptType.RTL_HEBREW -> c in '\u0590'..'\u05FF'
                ScriptType.RTL_ARABIC -> c in '\u0600'..'\u06FF'
                ScriptType.CYRILLIC -> c in '\u0400'..'\u04FF'
                ScriptType.GREEK -> c in '\u0370'..'\u03FF'
                ScriptType.DEVANAGARI -> c in '\u0900'..'\u097F'
                ScriptType.CJK -> c in '\u3040'..'\u30FF' || c in '\u4E00'..'\u9FFF' || c in '\uAC00'..'\uD7AF'
                ScriptType.LATIN -> isLatinChar(c)
            }
            if (hit) matching++
        }
        return letters > 0 && (matching.toFloat() / letters) >= 0.40f
    }
}
