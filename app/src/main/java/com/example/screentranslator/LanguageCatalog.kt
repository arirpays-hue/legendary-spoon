package com.example.screentranslator

import com.google.mlkit.nl.translate.TranslateLanguage

enum class ScriptType(val isRtl: Boolean) {
    LATIN(false),
    RTL_HEBREW(true),
    RTL_ARABIC(true),
    CYRILLIC(false),
    CJK(false),
    DEVANAGARI(false),
    GREEK(false)
}

data class LanguageOption(
    val code: String,          // ML Kit TranslateLanguage code
    val onlineCode: String,    // Code for online fallback endpoint
    val nameHe: String,
    val nativeName: String,
    val script: ScriptType,
    val supportsLatinOcr: Boolean,
    val samplePhrase: String
) {
    val isRtl: Boolean get() = script.isRtl
}

enum class ModelKind {
    MLKIT_NEURAL_OFFLINE,
    SEGMENTED_FAST_PACK
}

data class ModelOption(
    val id: String,
    val titleHe: String,
    val subtitleHe: String,
    val sourceCode: String,
    val targetCode: String,
    val kind: ModelKind,
    val approxSizeLabel: String,
    val supports60PartSplit: Boolean,
    val defaultDirectUrl: String? = null
)

object LanguageCatalog {

    val allLanguages: List<LanguageOption> = listOf(
        LanguageOption(
            code = TranslateLanguage.ENGLISH,
            onlineCode = "en",
            nameHe = "אנגלית",
            nativeName = "English",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "Settings will be saved automatically when you exit."
        ),
        LanguageOption(
            code = TranslateLanguage.HEBREW,
            onlineCode = "iw",
            nameHe = "עברית",
            nativeName = "עברית",
            script = ScriptType.RTL_HEBREW,
            supportsLatinOcr = false,
            samplePhrase = "ההגדרות יישמרו אוטומטית ביציאה מהאפליקציה."
        ),
        LanguageOption(
            code = TranslateLanguage.SPANISH,
            onlineCode = "es",
            nameHe = "ספרדית",
            nativeName = "Español",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "La configuración se guardará automáticamente al salir."
        ),
        LanguageOption(
            code = TranslateLanguage.FRENCH,
            onlineCode = "fr",
            nameHe = "צרפתית",
            nativeName = "Français",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "Les paramètres seront enregistrés automatiquement à la sortie."
        ),
        LanguageOption(
            code = TranslateLanguage.GERMAN,
            onlineCode = "de",
            nameHe = "גרמנית",
            nativeName = "Deutsch",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "Die Einstellungen werden beim Beenden automatisch gespeichert."
        ),
        LanguageOption(
            code = TranslateLanguage.ITALIAN,
            onlineCode = "it",
            nameHe = "איטלקית",
            nativeName = "Italiano",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "Le impostazioni verranno salvate automaticamente all'uscita."
        ),
        LanguageOption(
            code = TranslateLanguage.PORTUGUESE,
            onlineCode = "pt",
            nameHe = "פורטוגזית",
            nativeName = "Português",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "As configurações serão salvas automaticamente ao sair."
        ),
        LanguageOption(
            code = TranslateLanguage.TURKISH,
            onlineCode = "tr",
            nameHe = "טורקית",
            nativeName = "Türkçe",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "Ayarlar çıkışta otomatik olarak kaydedilecektir."
        ),
        LanguageOption(
            code = TranslateLanguage.POLISH,
            onlineCode = "pl",
            nameHe = "פולנית",
            nativeName = "Polski",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "Ustawienia zostaną automatycznie zapisane przy wyjściu."
        ),
        LanguageOption(
            code = TranslateLanguage.DUTCH,
            onlineCode = "nl",
            nameHe = "הולנדית",
            nativeName = "Nederlands",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "Instellingen worden automatisch opgeslagen bij het afsluiten."
        ),
        LanguageOption(
            code = TranslateLanguage.SWEDISH,
            onlineCode = "sv",
            nameHe = "שוודית",
            nativeName = "Svenska",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "Inställningarna sparas automatiskt när du avslutar."
        ),
        LanguageOption(
            code = TranslateLanguage.CZECH,
            onlineCode = "cs",
            nameHe = "צ'כית",
            nativeName = "Čeština",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "Nastavení se při ukončení automaticky uloží."
        ),
        LanguageOption(
            code = TranslateLanguage.ROMANIAN,
            onlineCode = "ro",
            nameHe = "רומנית",
            nativeName = "Română",
            script = ScriptType.LATIN,
            supportsLatinOcr = true,
            samplePhrase = "Setările vor fi salvate automat la ieșire."
        ),
        LanguageOption(
            code = TranslateLanguage.ARABIC,
            onlineCode = "ar",
            nameHe = "ערבית",
            nativeName = "العربية",
            script = ScriptType.RTL_ARABIC,
            supportsLatinOcr = false,
            samplePhrase = "سيتم حفظ الإعدادات تلقائياً عند الخروج."
        ),
        LanguageOption(
            code = TranslateLanguage.RUSSIAN,
            onlineCode = "ru",
            nameHe = "רוסית",
            nativeName = "Русский",
            script = ScriptType.CYRILLIC,
            supportsLatinOcr = false,
            samplePhrase = "Настройки будут автоматически сохранены при выходе."
        ),
        LanguageOption(
            code = TranslateLanguage.UKRAINIAN,
            onlineCode = "uk",
            nameHe = "אוקראינית",
            nativeName = "Українська",
            script = ScriptType.CYRILLIC,
            supportsLatinOcr = false,
            samplePhrase = "Налаштування будуть автоматично збережені під час виходу."
        ),
        LanguageOption(
            code = TranslateLanguage.GREEK,
            onlineCode = "el",
            nameHe = "יוונית",
            nativeName = "Ελληνικά",
            script = ScriptType.GREEK,
            supportsLatinOcr = false,
            samplePhrase = "Οι ρυθμίσεις θα αποθηκευτούν αυτόματα κατά την έξοδο."
        ),
        LanguageOption(
            code = TranslateLanguage.JAPANESE,
            onlineCode = "ja",
            nameHe = "יפנית",
            nativeName = "日本語",
            script = ScriptType.CJK,
            supportsLatinOcr = false,
            samplePhrase = "終了時に設定が自動的に保存されます。"
        ),
        LanguageOption(
            code = TranslateLanguage.KOREAN,
            onlineCode = "ko",
            nameHe = "קוריאנית",
            nativeName = "한국어",
            script = ScriptType.CJK,
            supportsLatinOcr = false,
            samplePhrase = "종료 시 설정이 자동으로 저장됩니다."
        ),
        LanguageOption(
            code = TranslateLanguage.CHINESE,
            onlineCode = "zh-CN",
            nameHe = "סינית",
            nativeName = "中文",
            script = ScriptType.CJK,
            supportsLatinOcr = false,
            samplePhrase = "退出时将自动保存设置。"
        ),
        LanguageOption(
            code = TranslateLanguage.HINDI,
            onlineCode = "hi",
            nameHe = "הינדית",
            nativeName = "हिन्दी",
            script = ScriptType.DEVANAGARI,
            supportsLatinOcr = false,
            samplePhrase = "बाहर निकलने पर सेटिंग्स अपने आप सहेजी जाएंगी।"
        )
    )

    val defaultSource: LanguageOption = findByCode(TranslateLanguage.ENGLISH)
    val defaultTarget: LanguageOption = findByCode(TranslateLanguage.HEBREW)

    fun findByCode(code: String?): LanguageOption {
        return allLanguages.firstOrNull { it.code == code } ?: allLanguages.first()
    }

    /**
     * מחזיר רשימת מודלים וחבילות תומכות עבור זוג השפות שנבחר.
     */
    fun getSupportedModels(
        source: LanguageOption,
        target: LanguageOption
    ): List<ModelOption> {
        if (source.code == target.code) return emptyList()

        val pairTag = "${source.code.uppercase()}_${target.code.uppercase()}"
        val pairDisplay = "${source.nameHe} ← ${target.nameHe}"

        return listOf(
            ModelOption(
                id = "mlkit_${source.code}_${target.code}",
                titleHe = "מודל עצבי לא-מקוון (ML Kit Neural)",
                subtitleHe = "תרגום משפטים מלא במכשיר ($pairDisplay) ללא אינטרנט. ההורדה מנוהלת ע\"י ML Kit.",
                sourceCode = source.code,
                targetCode = target.code,
                kind = ModelKind.MLKIT_NEURAL_OFFLINE,
                approxSizeLabel = "~30 MB",
                supports60PartSplit = false
            ),
            ModelOption(
                id = "fastpack_${source.code}_${target.code}",
                titleHe = "חבילת מילון ומונחים מהירה ($pairTag)",
                subtitleHe = "תומך בפיצול הורדה ל-60 חלקים מקבילים מקישור HTTPS ישיר (JSON/TSV) להאצת תרגום מונחי ממשק ומשחקים.",
                sourceCode = source.code,
                targetCode = target.code,
                kind = ModelKind.SEGMENTED_FAST_PACK,
                approxSizeLabel = "מותאם לקובץ",
                supports60PartSplit = true
            )
        )
    }
}
