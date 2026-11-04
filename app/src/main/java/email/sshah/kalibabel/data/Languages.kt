package email.sshah.kalibabel.data

import java.util.Locale

/**
 * A spoken/written language, identified by a BCP-47 tag (e.g. `en-US`, `pt-BR`, `zh-CN`).
 *
 * Providers disagree on language codes (ISO 639-1, ISO 639-3, BCP-47, DeepL's own codes),
 * so each engine converts from this canonical form at the edge.
 */
data class Language(
    val tag: String,
    val englishName: String,
    val nativeName: String,
) {
    /** Primary subtag, normally ISO 639-1 (two letters) but can be 3 letters (`fil`, `yue`). */
    val code: String get() = tag.substringBefore('-').lowercase(Locale.ROOT)

    val region: String? get() = tag.split('-').getOrNull(1)?.uppercase(Locale.ROOT)

    val locale: Locale get() = Locale.forLanguageTag(tag)

    /** Code understood by Whisper-family models (OpenAI, Groq, Together, Mistral). */
    val whisperCode: String
        get() = when (code) {
            "fil" -> "tl"
            "nb", "nn" -> "no"
            "yue" -> "yue"
            else -> code
        }

    val label: String
        get() = if (nativeName.equals(englishName, ignoreCase = true)) englishName else "$englishName · $nativeName"
}

object Languages {
    val all: List<Language> = listOf(
        Language("af-ZA", "Afrikaans", "Afrikaans"),
        Language("sq-AL", "Albanian", "Shqip"),
        Language("am-ET", "Amharic", "አማርኛ"),
        Language("ar-SA", "Arabic", "العربية"),
        Language("ar-EG", "Arabic (Egypt)", "العربية المصرية"),
        Language("hy-AM", "Armenian", "Հայերեն"),
        Language("az-AZ", "Azerbaijani", "Azərbaycanca"),
        Language("eu-ES", "Basque", "Euskara"),
        Language("be-BY", "Belarusian", "Беларуская"),
        Language("bn-BD", "Bengali", "বাংলা"),
        Language("bs-BA", "Bosnian", "Bosanski"),
        Language("bg-BG", "Bulgarian", "Български"),
        Language("my-MM", "Burmese", "မြန်မာ"),
        Language("yue-HK", "Cantonese", "粵語"),
        Language("ca-ES", "Catalan", "Català"),
        Language("zh-CN", "Chinese (Simplified)", "简体中文"),
        Language("zh-TW", "Chinese (Traditional)", "繁體中文"),
        Language("hr-HR", "Croatian", "Hrvatski"),
        Language("cs-CZ", "Czech", "Čeština"),
        Language("da-DK", "Danish", "Dansk"),
        Language("nl-NL", "Dutch", "Nederlands"),
        Language("en-US", "English (US)", "English (US)"),
        Language("en-GB", "English (UK)", "English (UK)"),
        Language("en-AU", "English (Australia)", "English (Australia)"),
        Language("en-IN", "English (India)", "English (India)"),
        Language("et-EE", "Estonian", "Eesti"),
        Language("fil-PH", "Filipino", "Filipino"),
        Language("fi-FI", "Finnish", "Suomi"),
        Language("fr-FR", "French", "Français"),
        Language("fr-CA", "French (Canada)", "Français (Canada)"),
        Language("gl-ES", "Galician", "Galego"),
        Language("ka-GE", "Georgian", "ქართული"),
        Language("de-DE", "German", "Deutsch"),
        Language("el-GR", "Greek", "Ελληνικά"),
        Language("gu-IN", "Gujarati", "ગુજરાતી"),
        Language("ht-HT", "Haitian Creole", "Kreyòl ayisyen"),
        Language("ha-NG", "Hausa", "Hausa"),
        Language("he-IL", "Hebrew", "עברית"),
        Language("hi-IN", "Hindi", "हिन्दी"),
        Language("hu-HU", "Hungarian", "Magyar"),
        Language("is-IS", "Icelandic", "Íslenska"),
        Language("ig-NG", "Igbo", "Igbo"),
        Language("id-ID", "Indonesian", "Bahasa Indonesia"),
        Language("ga-IE", "Irish", "Gaeilge"),
        Language("it-IT", "Italian", "Italiano"),
        Language("ja-JP", "Japanese", "日本語"),
        Language("jv-ID", "Javanese", "Basa Jawa"),
        Language("kn-IN", "Kannada", "ಕನ್ನಡ"),
        Language("kk-KZ", "Kazakh", "Қазақ тілі"),
        Language("km-KH", "Khmer", "ខ្មែរ"),
        Language("ko-KR", "Korean", "한국어"),
        Language("ku-TR", "Kurdish", "Kurdî"),
        Language("ky-KG", "Kyrgyz", "Кыргызча"),
        Language("lo-LA", "Lao", "ລາວ"),
        Language("lv-LV", "Latvian", "Latviešu"),
        Language("lt-LT", "Lithuanian", "Lietuvių"),
        Language("lb-LU", "Luxembourgish", "Lëtzebuergesch"),
        Language("mk-MK", "Macedonian", "Македонски"),
        Language("ms-MY", "Malay", "Bahasa Melayu"),
        Language("ml-IN", "Malayalam", "മലയാളം"),
        Language("mt-MT", "Maltese", "Malti"),
        Language("mi-NZ", "Māori", "Te Reo Māori"),
        Language("mr-IN", "Marathi", "मराठी"),
        Language("mn-MN", "Mongolian", "Монгол"),
        Language("ne-NP", "Nepali", "नेपाली"),
        Language("nb-NO", "Norwegian", "Norsk bokmål"),
        Language("ps-AF", "Pashto", "پښتو"),
        Language("fa-IR", "Persian", "فارسی"),
        Language("pl-PL", "Polish", "Polski"),
        Language("pt-BR", "Portuguese (Brazil)", "Português (Brasil)"),
        Language("pt-PT", "Portuguese (Portugal)", "Português (Portugal)"),
        Language("pa-IN", "Punjabi", "ਪੰਜਾਬੀ"),
        Language("ro-RO", "Romanian", "Română"),
        Language("ru-RU", "Russian", "Русский"),
        Language("sr-RS", "Serbian", "Српски"),
        Language("si-LK", "Sinhala", "සිංහල"),
        Language("sk-SK", "Slovak", "Slovenčina"),
        Language("sl-SI", "Slovenian", "Slovenščina"),
        Language("so-SO", "Somali", "Soomaali"),
        Language("es-ES", "Spanish (Spain)", "Español (España)"),
        Language("es-MX", "Spanish (Latin America)", "Español (Latinoamérica)"),
        Language("sw-KE", "Swahili", "Kiswahili"),
        Language("sv-SE", "Swedish", "Svenska"),
        Language("tg-TJ", "Tajik", "Тоҷикӣ"),
        Language("ta-IN", "Tamil", "தமிழ்"),
        Language("te-IN", "Telugu", "తెలుగు"),
        Language("th-TH", "Thai", "ไทย"),
        Language("tr-TR", "Turkish", "Türkçe"),
        Language("uk-UA", "Ukrainian", "Українська"),
        Language("ur-PK", "Urdu", "اردو"),
        Language("uz-UZ", "Uzbek", "Oʻzbek"),
        Language("vi-VN", "Vietnamese", "Tiếng Việt"),
        Language("cy-GB", "Welsh", "Cymraeg"),
        Language("xh-ZA", "Xhosa", "isiXhosa"),
        Language("yo-NG", "Yoruba", "Yorùbá"),
        Language("zu-ZA", "Zulu", "isiZulu"),
    )

    private val byTagMap = all.associateBy { it.tag.lowercase(Locale.ROOT) }

    /** Looks up a language by BCP-47 tag, falling back to the first language sharing the primary subtag. */
    fun byTag(tag: String): Language {
        val t = tag.trim().replace('_', '-').lowercase(Locale.ROOT)
        byTagMap[t]?.let { return it }
        val primary = t.substringBefore('-')
        return all.firstOrNull { it.code == primary }
            ?: all.firstOrNull { iso3(it) == primary }
            ?: Language(tag, tag, tag)
    }

    /** Matches a code returned by a provider (ISO 639-1/639-3/BCP-47, any case) against a language. */
    fun matches(language: Language, code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        val c = code.trim().replace('_', '-').lowercase(Locale.ROOT)
        val primary = c.substringBefore('-')
        return primary == language.code || primary == iso3(language) ||
            (language.code == "nb" && primary in setOf("no", "nor", "nob")) ||
            (language.code == "fil" && primary in setOf("tl", "tgl"))
    }

    fun iso3(language: Language): String = runCatching { language.locale.isO3Language }.getOrDefault(language.code)
}
