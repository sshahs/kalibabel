package email.sshah.kalibabel.providers.impl

import email.sshah.kalibabel.audio.Wav
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.net.bytes
import email.sshah.kalibabel.net.headers
import email.sshah.kalibabel.net.json
import email.sshah.kalibabel.net.jsonBody
import email.sshah.kalibabel.net.str
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ModelClassifier
import email.sshah.kalibabel.providers.ModelInfo
import email.sshah.kalibabel.providers.Provider
import email.sshah.kalibabel.providers.ProviderContext
import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.providers.SttEngine
import email.sshah.kalibabel.providers.SttRequest
import email.sshah.kalibabel.providers.SttResult
import email.sshah.kalibabel.providers.TranslationDelta
import email.sshah.kalibabel.providers.TranslationEngine
import email.sshah.kalibabel.providers.TranslationRequest
import email.sshah.kalibabel.providers.TtsEngine
import email.sshah.kalibabel.providers.TtsOutput
import email.sshah.kalibabel.providers.TtsRequest
import email.sshah.kalibabel.providers.VoiceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Provider speaking the OpenAI REST dialect: `/chat/completions`, `/audio/transcriptions`,
 * `/audio/speech` and `/models`. Covers OpenAI itself plus Groq, Mistral, DeepSeek, xAI,
 * OpenRouter, Together, Fireworks, Cerebras, SambaNova, Moonshot, Qwen, Z.ai, Hugging Face and
 * any custom compatible server.
 */
open class OpenAiCompatibleProvider(ctx: ProviderContext) : Provider(ctx) {

    protected open fun authHeaders(): Map<String, String> = buildMap {
        ctx.apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        if (spec.id == ProviderId.OPENROUTER) {
            put("HTTP-Referer", "https://github.com/sshahs/kalibabel")
            put("X-Title", "KaliBabel")
        }
    }

    protected fun checkKey() {
        if (spec.keyRequired) ctx.requireKey()
    }

    /** OpenAI reasoning models only accept the default temperature. */
    protected open fun supportsTemperature(model: String): Boolean {
        if (spec.id != ProviderId.OPENAI && spec.id != ProviderId.OPENROUTER) return true
        val m = model.substringAfterLast('/').lowercase()
        return !(m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") || m.startsWith("gpt-5"))
    }

    protected open fun extraChatParams(model: String): JsonObject = buildJsonObject {
        if (spec.id == ProviderId.OPENROUTER && ctx.privacy) {
            putJsonObject("provider") {
                put("zdr", true)
                put("data_collection", "deny")
            }
        }
    }

    // ------------------------------------------------------------------ translation

    override val translation: TranslationEngine? = if (Capability.TRANSLATION in spec.capabilities) {
        object : TranslationEngine {
            override val isLlm = true

            override fun translate(request: TranslationRequest): Flow<TranslationDelta> {
                checkKey()
                val key = "${spec.id}/${request.model}"
                return adaptiveStream(key, listOf("temperature")) { blocked ->
                    val body = buildJsonObject {
                        put("model", request.model)
                        put("stream", true)
                        put("messages", LlmStreams.openAiMessages(request))
                        if (supportsTemperature(request.model) && "temperature" !in blocked) put("temperature", 0.2)
                        extraChatParams(request.model).forEach { (k, v) -> put(k, v) }
                    }
                    LlmStreams.openAiChat(ctx.http, name, "${ctx.baseUrl}/chat/completions", authHeaders(), body)
                }.map { TranslationDelta.Text(it) }
            }
        }
    } else {
        null
    }

    // ------------------------------------------------------------------ speech to text

    override val stt: SttEngine? = if (Capability.STT in spec.capabilities) {
        object : SttEngine {
            override suspend fun transcribe(request: SttRequest, pcm: ShortArray, sampleRate: Int): SttResult {
                checkKey()
                val wav = Wav.encode(pcm, sampleRate)
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", request.model)
                    .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("response_format", "json")
                    .addFormDataPart("temperature", "0")
                request.language?.let { form.addFormDataPart("language", it.whisperCode) }
                if (request.keyterms.isNotEmpty()) {
                    // Whisper-style prompt biasing: names and terms the speaker is likely to use.
                    form.addFormDataPart("prompt", request.keyterms.joinToString(", ").take(800))
                }
                val req = Request.Builder().url("${ctx.baseUrl}/audio/transcriptions")
                    .headers(authHeaders()).post(form.build()).build()
                val json = ctx.http.json(req, name)
                return SttResult(json.str("text").orEmpty().trim(), json.str("language"))
            }
        }
    } else {
        null
    }

    // ------------------------------------------------------------------ text to speech

    /** OpenAI streams raw 24 kHz PCM; most other servers are safest with WAV. */
    protected open val ttsFormat: String get() = if (spec.id == ProviderId.OPENAI) "pcm" else "wav"

    override val tts: TtsEngine? = if (Capability.TTS in spec.capabilities) {
        object : TtsEngine {
            override suspend fun synthesize(request: TtsRequest): TtsOutput {
                checkKey()
                val voice = request.voice?.takeIf { it.isNotBlank() } ?: defaultVoice(request.model)
                val body = buildJsonObject {
                    put("model", request.model)
                    put("input", request.text)
                    put("voice", voice)
                    put("response_format", ttsFormat)
                    if (request.speed != 1f) put("speed", request.speed.toDouble())
                    if (spec.id == ProviderId.OPENAI && request.model.contains("gpt-4o")) {
                        put(
                            "instructions",
                            "Speak naturally and clearly in ${request.language.englishName}, like a friendly live interpreter.",
                        )
                    }
                }
                val req = Request.Builder().url("${ctx.baseUrl}/audio/speech")
                    .headers(authHeaders()).post(jsonBody(body)).build()
                val stream = ctx.http.bytes(req, name)
                return if (ttsFormat == "pcm") TtsOutput.Pcm(24_000, stream) else TtsOutput.Wav(stream)
            }
        }
    } else {
        null
    }

    protected open fun defaultVoice(model: String): String = when (spec.id) {
        ProviderId.GROQ -> if (model.contains("arabic")) "fahad" else "troy"
        ProviderId.TOGETHER -> if (model.contains("kokoro", true)) "af_alloy" else "helpful woman"
        else -> "alloy"
    }

    override suspend fun listVoices(model: String?, language: Language?): List<VoiceInfo> = when (spec.id) {
        ProviderId.OPENAI, ProviderId.CUSTOM_OPENAI -> listOf(
            "alloy" to "Neutral, balanced", "ash" to "Warm, clear", "ballad" to "Soft, melodic", "coral" to "Bright, friendly",
            "echo" to "Calm, male", "fable" to "Expressive, British", "nova" to "Energetic, female", "onyx" to "Deep, male",
            "sage" to "Measured, wise", "shimmer" to "Light, female", "verse" to "Versatile", "marin" to "Natural (newest)",
            "cedar" to "Natural (newest)",
        ).map { (id, d) -> VoiceInfo(id, id.replaceFirstChar { it.uppercase() }, d) }
        ProviderId.GROQ -> if (model?.contains("arabic") == true) {
            listOf("fahad", "sultan", "lulwa", "noura").map { VoiceInfo(it, it.replaceFirstChar { c -> c.uppercase() }, languages = listOf("ar")) }
        } else {
            listOf("autumn", "diana", "hannah", "austin", "daniel", "troy").map { VoiceInfo(it, it.replaceFirstChar { c -> c.uppercase() }, languages = listOf("en")) }
        }
        ProviderId.TOGETHER -> listOf("helpful woman", "laidback woman", "friendly sidekick", "british reading lady", "af_alloy", "am_adam")
            .map { VoiceInfo(it, it) }
        else -> emptyList()
    }

    // ------------------------------------------------------------------ catalog

    override suspend fun listModels(capability: Capability): List<ModelInfo> {
        checkKey()
        val req = Request.Builder().url("${ctx.baseUrl}/models").headers(authHeaders()).get().build()
        val json = ctx.http.json(req, name)
        val all = LlmStreams.modelIds(json)
        val filtered = all.filter { (id, obj) ->
            // Mistral annotates capabilities explicitly; prefer that over name heuristics.
            val caps = obj["capabilities"] as? JsonObject
            if (caps != null && spec.id == ProviderId.MISTRAL) {
                when (capability) {
                    Capability.TRANSLATION -> caps.str("completion_chat") == "true"
                    Capability.STT -> caps.str("audio_transcription") == "true" || ModelClassifier.matches(id, capability)
                    Capability.TTS -> ModelClassifier.matches(id, capability)
                }
            } else {
                ModelClassifier.matches(id, capability)
            }
        }
        return filtered.map { (id, obj) ->
            ModelInfo(id, obj.str("name")?.takeIf { it != id } ?: id, obj.str("owned_by") ?: obj.str("description")?.take(120))
        }.distinctBy { it.id }.sortedBy { it.id.lowercase() }
    }
}
