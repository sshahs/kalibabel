package email.sshah.kalibabel.data

import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ProviderId
import kotlinx.serialization.Serializable

@Serializable
enum class Formality(val label: String) { AUTO("Auto"), FORMAL("Formal"), INFORMAL("Informal") }

@Serializable
enum class ListenMode(val label: String, val description: String) {
    AUTO_STOP("Auto-stop", "Stops listening when you pause, then translates."),
    CONTINUOUS("Interpreter", "Keeps listening and translates every sentence as you go."),
    MANUAL("Manual", "Records until you tap stop, then translates everything at once."),
}

@Serializable
enum class MicSource(val label: String) {
    VOICE_RECOGNITION("Voice recognition (clean, no AGC)"),
    VOICE_COMMUNICATION("Voice call (echo cancellation)"),
    MIC("Raw microphone"),
}

/** Per-provider user preferences. API keys are NOT stored here (see [SecureKeyStore]). */
@Serializable
data class ProviderPrefs(
    val baseUrl: String? = null,
    /** Provider-specific privacy switch: Command Code ZDR, OpenRouter ZDR, ElevenLabs zero-retention, Deepgram MIP opt-out. */
    val privacy: Boolean = false,
    val region: String? = null,
)

@Serializable
data class AppSettings(
    val languageA: String = "en-US",
    val languageB: String = "es-ES",

    val sttProvider: ProviderId = ProviderId.ANDROID,
    val translationProvider: ProviderId = ProviderId.ANDROID,
    val ttsProvider: ProviderId = ProviderId.ANDROID,

    /** Selected model per provider and capability, keyed by [modelKey]. */
    val models: Map<String, String> = emptyMap(),
    /** Selected voice per provider and conversation side, keyed by [voiceKey]. */
    val voices: Map<String, String> = emptyMap(),
    val providers: Map<String, ProviderPrefs> = emptyMap(),

    // Listening
    val listenMode: ListenMode = ListenMode.AUTO_STOP,
    val silenceMs: Int = 700,
    val preferStreamingStt: Boolean = true,
    val micSource: MicSource = MicSource.VOICE_RECOGNITION,
    val noiseSuppression: Boolean = true,
    val pauseMicWhileSpeaking: Boolean = true,

    // Translation
    val formality: Formality = Formality.AUTO,
    val contextTurns: Int = 4,
    val glossary: String = "",
    val customInstructions: String = "",
    val fallbackToOnDevice: Boolean = true,

    // Speech output
    val autoSpeak: Boolean = true,
    val speechRate: Float = 1.0f,
    val earlySpeech: Boolean = true,

    // Display
    val showLatency: Boolean = true,
    val keepScreenOn: Boolean = true,
    val faceToFace: Boolean = false,
    val showOriginal: Boolean = true,
) {
    val langA: Language get() = Languages.byTag(languageA)
    val langB: Language get() = Languages.byTag(languageB)

    fun providerFor(capability: Capability): ProviderId = when (capability) {
        Capability.STT -> sttProvider
        Capability.TRANSLATION -> translationProvider
        Capability.TTS -> ttsProvider
    }

    fun prefs(id: ProviderId): ProviderPrefs = providers[id.name] ?: ProviderPrefs()

    /** Glossary lines of the form `term = translation` (also accepts `->`, `=>`, `:`). */
    val glossaryEntries: List<Pair<String, String>>
        get() = glossary.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val sep = listOf("=>", "->", "=", ":").firstOrNull { trimmed.contains(it) }
            if (sep == null) {
                trimmed to trimmed
            } else {
                val (k, v) = trimmed.split(sep, limit = 2).map { it.trim() }
                if (k.isEmpty()) null else k to v.ifEmpty { k }
            }
        }

    companion object {
        fun modelKey(provider: ProviderId, capability: Capability) = "${provider.name}/${capability.name}"

        /** [side] is "A" or "B": the voice used when speaking language A or B. */
        fun voiceKey(provider: ProviderId, side: String) = "${provider.name}/$side"
    }
}
