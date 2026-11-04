package email.sshah.kalibabel.providers

import android.content.Context
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.data.ProviderPrefs
import email.sshah.kalibabel.net.execute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Everything a provider instance needs; instances are rebuilt when keys or prefs change. */
class ProviderContext(
    val app: Context,
    val http: OkHttpClient,
    val spec: ProviderSpec,
    val apiKey: String?,
    val prefs: ProviderPrefs,
) {
    /** Base URL with user override / region applied, without a trailing slash. */
    val baseUrl: String = run {
        val region = spec.regions.firstOrNull { it.id == prefs.region }?.baseUrl
        (prefs.baseUrl?.takeIf { it.isNotBlank() } ?: region ?: spec.defaultBaseUrl).trimEnd('/')
    }

    val privacy: Boolean get() = prefs.privacy

    fun requireKey(): String = apiKey?.takeIf { it.isNotBlank() }
        ?: throw MissingKeyException(spec.displayName)
}

class MissingKeyException(provider: String) : IllegalStateException("$provider: no API key configured")

/**
 * A configured provider. Each implementation exposes only the engines it supports; the
 * interpreter pipeline composes STT, translation and TTS from (possibly) different providers.
 */
abstract class Provider(protected val ctx: ProviderContext) {
    val spec: ProviderSpec get() = ctx.spec
    val name: String get() = ctx.spec.displayName

    open val translation: TranslationEngine? = null
    open val stt: SttEngine? = null
    open val tts: TtsEngine? = null

    /** Lists models for [capability]; implementations hit the provider's catalog endpoint. */
    open suspend fun listModels(capability: Capability): List<ModelInfo> =
        spec.suggestedModels[capability].orEmpty().map { ModelInfo(it) }

    /** Lists TTS voices, optionally narrowed to a model / language. */
    open suspend fun listVoices(model: String?, language: Language?): List<VoiceInfo> = emptyList()

    /**
     * Opens (or refreshes) the TLS connection to the provider so the first real request of a
     * turn does not pay for DNS + TCP + TLS. Called when the user starts speaking.
     */
    open suspend fun prewarm(models: Map<Capability, String>) {
        if (ctx.baseUrl.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                ctx.http.newCall(Request.Builder().url(ctx.baseUrl).head().build()).execute().close()
            }
        }
    }

    /** Validates the key with a cheap authenticated call; returns a short human summary. */
    open suspend fun test(): String {
        val caps = spec.capabilities.toList()
        val counts = caps.map { cap -> "${listModels(cap).size} ${cap.short}" }
        return "Connected · models: ${counts.joinToString(", ")}"
    }

    protected suspend fun simpleGet(url: String, headers: Map<String, String>) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        ctx.http.execute(req, name).close()
    }
}

/** Heuristic capability classification of model ids from generic `/models` endpoints. */
object ModelClassifier {
    private val sttMarkers = listOf("whisper", "transcribe", "voxtral", "scribe", "stt", "asr", "speech-to-text", "parakeet", "canary")
    private val ttsMarkers = listOf("tts", "orpheus", "playai", "sonic", "kokoro", "speech", "voice", "aura")
    private val nonChatMarkers = listOf(
        "embed", "moderation", "dall-e", "dalle", "image", "gpt-image", "sora", "rerank", "guard",
        "realtime", "audio", "search", "computer-use", "ocr", "vision-only", "clip", "flux", "stable-diffusion",
        "sdxl", "veo", "imagen", "lyria", "aqa", "babbage", "davinci", "codex", "tts", "whisper", "transcribe",
        "orpheus", "playai", "kokoro", "-diarize", "nano-banana",
    )

    fun matches(id: String, capability: Capability): Boolean {
        val l = id.lowercase()
        return when (capability) {
            Capability.STT -> sttMarkers.any { it in l }
            Capability.TTS -> ttsMarkers.any { it in l } && "transcribe" !in l
            Capability.TRANSLATION -> nonChatMarkers.none { it in l }
        }
    }
}
