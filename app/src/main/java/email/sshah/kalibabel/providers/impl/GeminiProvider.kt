package email.sshah.kalibabel.providers.impl

import android.util.Base64
import email.sshah.kalibabel.audio.Wav
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.net.ApiException
import email.sshah.kalibabel.net.arr
import email.sshah.kalibabel.net.asString
import email.sshah.kalibabel.net.at
import email.sshah.kalibabel.net.bool
import email.sshah.kalibabel.net.headers
import email.sshah.kalibabel.net.json
import email.sshah.kalibabel.net.jsonBody
import email.sshah.kalibabel.net.jsonData
import email.sshah.kalibabel.net.obj
import email.sshah.kalibabel.net.objects
import email.sshah.kalibabel.net.sse
import email.sshah.kalibabel.net.str
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ModelInfo
import email.sshah.kalibabel.providers.Provider
import email.sshah.kalibabel.providers.ProviderContext
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request

/** Google Gemini API (generativelanguage.googleapis.com) — translation, STT and native TTS. */
class GeminiProvider(ctx: ProviderContext) : Provider(ctx) {

    private fun headers() = mapOf("x-goog-api-key" to ctx.requireKey())

    private fun modelPath(model: String) = model.removePrefix("models/")

    private fun streamUrl(model: String) = "${ctx.baseUrl}/v1beta/models/${modelPath(model)}:streamGenerateContent?alt=sse"

    private fun generateUrl(model: String) = "${ctx.baseUrl}/v1beta/models/${modelPath(model)}:generateContent"

    /** Text parts of a candidate, skipping "thought" parts. */
    private fun textOf(json: JsonElement): String =
        json.arr("candidates").at(0).obj("content").arr("parts").objects()
            .filter { it.bool("thought") != true }
            .joinToString("") { it.str("text").orEmpty() }

    private fun checkBlocked(json: JsonElement) {
        json.obj("promptFeedback").str("blockReason")?.let { throw ApiException(name, 400, "Blocked: $it") }
    }

    /** Disable / minimise thinking for latency. Older models reject the knob; see [adaptiveStream]. */
    private fun thinkingConfig(model: String): JsonObject? {
        val m = model.lowercase()
        return when {
            "tts" in m -> null
            "gemini-3" in m -> buildJsonObject { put("thinkingLevel", "low") }
            "flash" in m -> buildJsonObject { put("thinkingBudget", 0) }
            else -> null
        }
    }

    override val translation: TranslationEngine = object : TranslationEngine {
        override val isLlm = true

        override fun translate(request: TranslationRequest): Flow<TranslationDelta> =
            adaptiveStream("${spec.id}/${request.model}", listOf("thinking", "temperature")) { blocked ->
                val body = buildJsonObject {
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", request.systemPrompt) }) }
                    }
                    putJsonArray("contents") {
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { add(buildJsonObject { put("text", request.text) }) }
                        })
                    }
                    putJsonObject("generationConfig") {
                        if ("temperature" !in blocked) put("temperature", 0.2)
                        if ("thinking" !in blocked) thinkingConfig(request.model)?.let { put("thinkingConfig", it) }
                    }
                }
                val req = Request.Builder().url(streamUrl(request.model)).headers(headers()).post(jsonBody(body)).build()
                ctx.http.sse(req, name).jsonData().transform { (_, json) ->
                    checkBlocked(json)
                    val text = textOf(json)
                    if (text.isNotEmpty()) emit(text)
                }
            }.map { TranslationDelta.Text(it) }
    }

    override val stt: SttEngine = object : SttEngine {
        override suspend fun transcribe(request: SttRequest, pcm: ShortArray, sampleRate: Int): SttResult {
            val audio = Base64.encodeToString(Wav.encode(pcm, sampleRate), Base64.NO_WRAP)
            val langHint = request.language?.let { "The speaker is speaking ${it.englishName}." }
                ?: "The speaker is speaking one of: ${request.candidates.joinToString { it.englishName }}."
            val terms = if (request.keyterms.isNotEmpty()) " Expected names/terms: ${request.keyterms.joinToString(", ")}." else ""
            val body = buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", "audio/wav")
                                    put("data", audio)
                                }
                            })
                            add(buildJsonObject {
                                put(
                                    "text",
                                    "Transcribe this speech verbatim in its original language and script. $langHint$terms " +
                                        "Output only the transcript text, with natural punctuation. If there is no speech, output nothing.",
                                )
                            })
                        }
                    })
                }
                putJsonObject("generationConfig") {
                    put("temperature", 0)
                    thinkingConfig(request.model)?.let { put("thinkingConfig", it) }
                }
            }
            val req = Request.Builder().url(generateUrl(request.model)).headers(headers()).post(jsonBody(body)).build()
            val json = ctx.http.json(req, name)
            checkBlocked(json)
            return SttResult(textOf(json).trim())
        }
    }

    override val tts: TtsEngine = object : TtsEngine {
        override suspend fun synthesize(request: TtsRequest): TtsOutput {
            val voice = request.voice?.takeIf { it.isNotBlank() } ?: "Kore"
            val pace = when {
                request.speed > 1.15f -> " at a brisk pace"
                request.speed < 0.85f -> " slowly"
                else -> ""
            }
            val body = buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", "Say naturally$pace in ${request.language.englishName}: ${request.text}") })
                        }
                    })
                }
                putJsonObject("generationConfig") {
                    put("responseModalities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) })
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) }
                        }
                    }
                }
            }
            val req = Request.Builder().url(streamUrl(request.model)).headers(headers()).post(jsonBody(body)).build()
            val chunks = flow {
                ctx.http.sse(req, name).jsonData().collect { (_, json) ->
                    checkBlocked(json)
                    json.arr("candidates").at(0).obj("content").arr("parts").objects().forEach { part ->
                        part.obj("inlineData").str("data")?.let { emit(Base64.decode(it, Base64.DEFAULT)) }
                    }
                }
            }
            // Gemini TTS returns 16-bit PCM, mono, 24 kHz ("audio/L16;codec=pcm;rate=24000").
            return TtsOutput.Pcm(24_000, chunks)
        }
    }

    override suspend fun listVoices(model: String?, language: Language?): List<VoiceInfo> = listOf(
        "Zephyr" to "Bright", "Puck" to "Upbeat", "Charon" to "Informative", "Kore" to "Firm", "Fenrir" to "Excitable",
        "Leda" to "Youthful", "Orus" to "Firm", "Aoede" to "Breezy", "Callirrhoe" to "Easy-going", "Autonoe" to "Bright",
        "Enceladus" to "Breathy", "Iapetus" to "Clear", "Umbriel" to "Easy-going", "Algieba" to "Smooth", "Despina" to "Smooth",
        "Erinome" to "Clear", "Algenib" to "Gravelly", "Rasalgethi" to "Informative", "Laomedeia" to "Upbeat", "Achernar" to "Soft",
        "Alnilam" to "Firm", "Schedar" to "Even", "Gacrux" to "Mature", "Pulcherrima" to "Forward", "Achird" to "Friendly",
        "Zubenelgenubi" to "Casual", "Vindemiatrix" to "Gentle", "Sadachbia" to "Lively", "Sadaltager" to "Knowledgeable",
        "Sulafat" to "Warm",
    ).map { (id, d) -> VoiceInfo(id, id, d) }

    override suspend fun listModels(capability: Capability): List<ModelInfo> {
        val req = Request.Builder().url("${ctx.baseUrl}/v1beta/models?pageSize=1000").headers(headers()).get().build()
        val json = ctx.http.json(req, name)
        return json.arr("models").objects().mapNotNull { m ->
            val id = m.str("name")?.removePrefix("models/") ?: return@mapNotNull null
            val methods = m.arr("supportedGenerationMethods")?.mapNotNull { it.asString }.orEmpty()
            if ("generateContent" !in methods) return@mapNotNull null
            val l = id.lowercase()
            val isTts = "tts" in l
            val ok = when (capability) {
                Capability.TTS -> isTts
                Capability.STT, Capability.TRANSLATION ->
                    !isTts && listOf("image", "embedding", "aqa", "veo", "imagen", "live", "native-audio", "robotics", "computer-use").none { it in l }
            }
            if (!ok) return@mapNotNull null
            ModelInfo(id, m.str("displayName") ?: id, m.str("description")?.take(140))
        }
    }
}
