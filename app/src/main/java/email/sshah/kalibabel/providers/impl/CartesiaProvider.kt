package email.sshah.kalibabel.providers.impl

import email.sshah.kalibabel.audio.Wav
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.data.Languages
import email.sshah.kalibabel.net.arr
import email.sshah.kalibabel.net.asString
import email.sshah.kalibabel.net.bool
import email.sshah.kalibabel.net.bytes
import email.sshah.kalibabel.net.headers
import email.sshah.kalibabel.net.json
import email.sshah.kalibabel.net.jsonBody
import email.sshah.kalibabel.net.objects
import email.sshah.kalibabel.net.str
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ModelInfo
import email.sshah.kalibabel.providers.Provider
import email.sshah.kalibabel.providers.ProviderContext
import email.sshah.kalibabel.providers.SttEngine
import email.sshah.kalibabel.providers.SttRequest
import email.sshah.kalibabel.providers.SttResult
import email.sshah.kalibabel.providers.TtsEngine
import email.sshah.kalibabel.providers.TtsOutput
import email.sshah.kalibabel.providers.TtsRequest
import email.sshah.kalibabel.providers.VoiceInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Cartesia: Sonic TTS (raw PCM streaming) and Ink-Whisper STT. */
class CartesiaProvider(ctx: ProviderContext) : Provider(ctx) {

    private fun headers() = mapOf(
        "X-API-Key" to ctx.requireKey(),
        "Cartesia-Version" to API_VERSION,
    )

    private var voiceCache: List<VoiceInfo>? = null

    private suspend fun voiceFor(language: Language): String {
        val voices = voiceCache ?: listVoices(null, null).also { voiceCache = it }
        return (voices.firstOrNull { v -> v.languages.any { Languages.matches(language, it) } } ?: voices.firstOrNull())?.id
            ?: throw IllegalStateException("Cartesia: no voices available — pick one in Settings")
    }

    override val tts: TtsEngine = object : TtsEngine {
        override suspend fun synthesize(request: TtsRequest): TtsOutput {
            val voice = request.voice?.takeIf { it.isNotBlank() } ?: voiceFor(request.language)
            val body = buildJsonObject {
                put("model_id", request.model.ifBlank { "sonic-3" })
                put("transcript", request.text)
                putJsonObject("voice") {
                    put("mode", "id")
                    put("id", voice)
                }
                putJsonObject("output_format") {
                    put("container", "raw")
                    put("encoding", "pcm_s16le")
                    put("sample_rate", 24_000)
                }
                put("language", request.language.code)
                if (request.speed != 1f) {
                    putJsonObject("generation_config") { put("speed", request.speed.coerceIn(0.6f, 1.5f).toDouble()) }
                }
            }
            val req = Request.Builder().url("${ctx.baseUrl}/tts/bytes").headers(headers()).post(jsonBody(body)).build()
            return TtsOutput.Pcm(24_000, ctx.http.bytes(req, name))
        }
    }

    override val stt: SttEngine = object : SttEngine {
        override suspend fun transcribe(request: SttRequest, pcm: ShortArray, sampleRate: Int): SttResult {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", request.model.ifBlank { "ink-whisper" })
                .addFormDataPart("file", "speech.wav", Wav.encode(pcm, sampleRate).toRequestBody("audio/wav".toMediaType()))
            request.language?.let { form.addFormDataPart("language", it.code) }
            val req = Request.Builder().url("${ctx.baseUrl}/stt").headers(headers()).post(form.build()).build()
            val json = ctx.http.json(req, name)
            return SttResult(json.str("text").orEmpty().trim(), json.str("language"))
        }
    }

    override suspend fun listModels(capability: Capability): List<ModelInfo> = when (capability) {
        Capability.TTS -> listOf(
            ModelInfo("sonic-3", "Sonic 3", "Latest, most natural, 40+ languages"),
            ModelInfo("sonic-2", "Sonic 2", "Previous generation"),
            ModelInfo("sonic-turbo", "Sonic Turbo", "Lowest latency"),
        )
        Capability.STT -> listOf(ModelInfo("ink-whisper", "Ink-Whisper", "Fast Whisper variant tuned for conversation"))
        Capability.TRANSLATION -> emptyList()
    }

    override suspend fun listVoices(model: String?, language: Language?): List<VoiceInfo> {
        val voices = mutableListOf<VoiceInfo>()
        var startingAfter: String? = null
        do {
            val url = buildString {
                append("${ctx.baseUrl}/voices?limit=100")
                startingAfter?.let { append("&starting_after=").append(it) }
            }
            val json = ctx.http.json(Request.Builder().url(url).headers(headers()).get().build(), name)
            val list = (json as? JsonArray) ?: json.arr("data") ?: JsonArray(emptyList())
            list.objects().forEach { v -> voices += voice(v) }
            startingAfter = if (json.bool("has_more") == true) voices.lastOrNull()?.id else null
        } while (startingAfter != null && voices.size < 1000)
        return voices
    }

    private fun voice(v: JsonObject): VoiceInfo {
        val lang = v.str("language") ?: (v["language"] as? JsonArray)?.firstOrNull()?.asString
        return VoiceInfo(v.str("id").orEmpty(), v.str("name") ?: "Voice", v.str("description")?.take(140), listOfNotNull(lang))
    }

    override suspend fun test(): String {
        ctx.http.json(Request.Builder().url("${ctx.baseUrl}/voices?limit=1").headers(headers()).get().build(), name)
        return "Connected"
    }

    private companion object {
        const val API_VERSION = "2025-04-16"
    }
}
