package email.sshah.kalibabel.providers.impl

import email.sshah.kalibabel.audio.Pcm
import email.sshah.kalibabel.audio.Wav
import email.sshah.kalibabel.net.ApiException
import email.sshah.kalibabel.net.AppJson
import email.sshah.kalibabel.net.WsMessage
import email.sshah.kalibabel.net.bool
import email.sshah.kalibabel.net.headers
import email.sshah.kalibabel.net.json
import email.sshah.kalibabel.net.jsonBody
import email.sshah.kalibabel.net.str
import email.sshah.kalibabel.net.webSocket
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ModelInfo
import email.sshah.kalibabel.providers.Provider
import email.sshah.kalibabel.providers.ProviderContext
import email.sshah.kalibabel.providers.SttEngine
import email.sshah.kalibabel.providers.SttEvent
import email.sshah.kalibabel.providers.SttRequest
import email.sshah.kalibabel.providers.SttResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString

/** AssemblyAI: Universal-Streaming (v3 WebSocket) and batch Universal models. */
class AssemblyAiProvider(ctx: ProviderContext) : Provider(ctx) {

    private fun headers() = mapOf("Authorization" to ctx.requireKey())

    private val streamingHost: String
        get() = if (ctx.baseUrl.contains(".eu.")) "https://streaming.eu.assemblyai.com" else "https://streaming.assemblyai.com"

    override val stt: SttEngine = object : SttEngine {
        override fun supportsStreaming(model: String) = model.startsWith("universal-streaming")

        override fun supportsBatch(model: String) = true

        override suspend fun transcribe(request: SttRequest, pcm: ShortArray, sampleRate: Int): SttResult {
            val upload = Request.Builder().url("${ctx.baseUrl}/v2/upload").headers(headers())
                .post(Wav.encode(pcm, sampleRate).toRequestBody("application/octet-stream".toMediaType())).build()
            val audioUrl = ctx.http.json(upload, name).str("upload_url") ?: throw ApiException(name, 500, "Upload failed")
            val model = request.model.takeUnless { it.startsWith("universal-streaming") || it.isBlank() } ?: "universal"
            val body = buildJsonObject {
                put("audio_url", audioUrl)
                put("speech_model", model)
                val lang = request.language
                if (lang != null) put("language_code", lang.code) else put("language_detection", true)
                if (request.keyterms.isNotEmpty()) put("keyterms_prompt", JsonArray(request.keyterms.take(100).map { JsonPrimitive(it) }))
            }
            val create = Request.Builder().url("${ctx.baseUrl}/v2/transcript").headers(headers()).post(jsonBody(body)).build()
            val id = ctx.http.json(create, name).str("id") ?: throw ApiException(name, 500, "No transcript id")
            return withTimeout(60_000) {
                while (true) {
                    delay(250)
                    val poll = ctx.http.json(Request.Builder().url("${ctx.baseUrl}/v2/transcript/$id").headers(headers()).get().build(), name)
                    when (poll.str("status")) {
                        "completed" -> return@withTimeout SttResult(poll.str("text").orEmpty().trim(), poll.str("language_code"))
                        "error" -> throw ApiException(name, 500, poll.str("error") ?: "Transcription failed")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                SttResult("")
            }
        }

        override fun stream(request: SttRequest, audio: ReceiveChannel<ShortArray>, sampleRate: Int): Flow<SttEvent> = channelFlow {
            val model = request.model.takeIf { it.startsWith("universal-streaming") } ?: "universal-streaming-multilingual"
            val u = "$streamingHost/v3/ws".toHttpUrl().newBuilder()
                .addQueryParameter("sample_rate", sampleRate.toString())
                .addQueryParameter("encoding", "pcm_s16le")
                .addQueryParameter("format_turns", "true")
                .addQueryParameter("speech_model", model)
                .addQueryParameter("min_end_of_turn_silence_when_confident", request.silenceMs.coerceIn(160, 2000).toString())
                .addQueryParameter("max_turn_silence", (request.silenceMs * 2).coerceIn(800, 5000).toString())
                .apply {
                    if (request.keyterms.isNotEmpty()) {
                        addQueryParameter("keyterms_prompt", JsonArray(request.keyterms.take(100).map { JsonPrimitive(it) }).toString())
                    }
                }
                .build()
            val req = Request.Builder().url(u).headers(headers()).build()
            val socket = CompletableDeferred<WebSocket>()
            var openSocket: WebSocket? = null

            val sender = launch {
                val ws = socket.await()
                val batch = ArrayList<ShortArray>()
                var samples = 0
                for (frame in audio) {
                    batch += frame
                    samples += frame.size
                    if (samples >= sampleRate / 20) { // 50 ms: the minimum chunk AssemblyAI accepts
                        ws.send(Pcm.toBytes(Pcm.concat(batch)).toByteString())
                        batch.clear()
                        samples = 0
                    }
                }
                if (batch.isNotEmpty()) {
                    val pad = Pcm.concat(batch + listOf(ShortArray((sampleRate / 20 - samples).coerceAtLeast(0))))
                    ws.send(Pcm.toBytes(pad).toByteString())
                }
                ws.send("""{"type":"ForceEndpoint"}""")
                delay(150)
                ws.send("""{"type":"Terminate"}""")
                delay(3_000)
                ws.close(1000, null)
            }

            ctx.http.webSocket(req, name) { openSocket = it; socket.complete(it) }.collect { msg ->
                if (msg !is WsMessage.Text) return@collect
                val json = runCatching { AppJson.parseToJsonElement(msg.text) }.getOrNull() ?: return@collect
                when (json.str("type")) {
                    "Turn" -> {
                        val text = json.str("transcript").orEmpty().trim()
                        val endOfTurn = json.bool("end_of_turn") == true
                        val formatted = json.bool("turn_is_formatted") == true
                        when {
                            endOfTurn && formatted -> if (text.isNotEmpty()) send(SttEvent.Final(text, json.str("language_code")))
                            text.isNotEmpty() -> send(SttEvent.Partial(text))
                        }
                    }
                    "Termination" -> openSocket?.close(1000, null)
                    "Error" -> throw ApiException(name, 400, json.str("error") ?: msg.text.take(200))
                }
                json.str("error")?.let { throw ApiException(name, 400, it) }
            }
            sender.cancel()
        }
    }

    override suspend fun listModels(capability: Capability): List<ModelInfo> = if (capability == Capability.STT) {
        listOf(
            ModelInfo("universal-streaming-multilingual", "Universal-Streaming Multilingual", "Realtime · EN, ES, FR, DE, IT, PT"),
            ModelInfo("universal-streaming-english", "Universal-Streaming English", "Realtime · English only, lowest latency"),
            ModelInfo("universal", "Universal (batch)", "Batch · 99 languages"),
            ModelInfo("slam-1", "Slam-1 (batch)", "Batch · English, promptable"),
        )
    } else {
        emptyList()
    }

    override suspend fun test(): String {
        val req = Request.Builder().url("${ctx.baseUrl}/v2/transcript?limit=1").headers(headers()).get().build()
        ctx.http.json(req, name)
        return "Connected"
    }
}
