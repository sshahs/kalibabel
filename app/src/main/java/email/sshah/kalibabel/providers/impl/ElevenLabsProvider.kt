package email.sshah.kalibabel.providers.impl

import android.util.Log
import email.sshah.kalibabel.audio.Pcm
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.pipeline.SpeakableTextBuffer
import email.sshah.kalibabel.net.ApiException
import email.sshah.kalibabel.net.AppJson
import email.sshah.kalibabel.net.WsMessage
import email.sshah.kalibabel.net.arr
import email.sshah.kalibabel.net.bool
import email.sshah.kalibabel.net.bytes
import email.sshah.kalibabel.net.headers
import email.sshah.kalibabel.net.int
import email.sshah.kalibabel.net.json
import email.sshah.kalibabel.net.jsonBody
import email.sshah.kalibabel.net.obj
import email.sshah.kalibabel.net.objects
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
import email.sshah.kalibabel.providers.StreamingTextTtsEngine
import email.sshah.kalibabel.providers.TtsEngine
import email.sshah.kalibabel.providers.TtsOutput
import email.sshah.kalibabel.providers.TtsRequest
import email.sshah.kalibabel.providers.VoiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import java.util.Base64

/**
 * ElevenLabs: streaming TTS (PCM straight into AudioTrack), Scribe batch STT and Scribe v2
 * Realtime streaming STT over WebSocket, plus live model and voice catalogs.
 */
class ElevenLabsProvider(ctx: ProviderContext) : Provider(ctx) {

    private fun headers() = mapOf("xi-api-key" to ctx.requireKey())

    private fun url(path: String, vararg query: Pair<String, String>) = ctx.baseUrl.toHttpUrl().newBuilder().apply {
        addPathSegments(path.trimStart('/'))
        query.forEach { (k, v) -> addQueryParameter(k, v) }
        if (ctx.privacy) addQueryParameter("enable_logging", "false")
    }.build()

    // ------------------------------------------------------------------ TTS

    private fun supportsLanguageCode(model: String) =
        model.contains("v2_5") || model.contains("v3") || model.contains("flash") || model.contains("turbo")

    /** HTTP streaming for whole sentences, WebSocket `stream-input` for word-by-word text. */
    inner class Tts : TtsEngine, StreamingTextTtsEngine {
        override suspend fun synthesize(request: TtsRequest): TtsOutput {
            val voice = request.voice?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE
            val model = request.model.ifBlank { "eleven_flash_v2_5" }
            val body = buildJsonObject {
                put("text", request.text)
                put("model_id", model)
                if (supportsLanguageCode(model)) put("language_code", request.language.code)
                request.previousText?.takeIf { it.isNotBlank() && !model.contains("v3") }?.let { put("previous_text", it.takeLast(300)) }
                putJsonObject("voice_settings") {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                    put("speed", request.speed.coerceIn(0.7f, 1.2f).toDouble())
                }
            }
            val req = Request.Builder()
                .url(url("v1/text-to-speech/$voice/stream", "output_format" to "pcm_24000"))
                .headers(headers()).post(jsonBody(body)).build()
            return TtsOutput.Pcm(24_000, ctx.http.bytes(req, name))
        }

        /** Eleven v3 is not available on the realtime WebSocket. */
        override fun supportsTextStreaming(model: String) = !model.contains("v3")

        override fun synthesizeTextStream(request: TtsRequest, text: ReceiveChannel<String>, onVoiced: (String) -> Unit): TtsOutput.Pcm {
            val voice = request.voice?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE
            val model = request.model.ifBlank { "eleven_flash_v2_5" }
            val query = buildList {
                add("model_id" to model)
                add("output_format" to "pcm_24000")
                add("inactivity_timeout" to "60")
                add("sync_alignment" to "true") // which characters each audio chunk voices: lets a dropped reply resume
                if (supportsLanguageCode(model)) add("language_code" to request.language.code)
            }
            val req = Request.Builder()
                .url(url("v1/text-to-speech/$voice/stream-input", *query.toTypedArray()))
                .headers(headers()).build()
            val audio = channelFlow {
                val socket = CompletableDeferred<WebSocket>()
                var openSocket: WebSocket? = null
                val sender = launch {
                    val ws = socket.await()
                    ws.send(streamInit(request.speed))
                    val buffer = SpeakableTextBuffer()
                    for (delta in text) buffer.push(delta)?.let { ws.send(streamText(it)) }
                    buffer.flush()?.let { ws.send(streamText(it)) }
                    ws.send(STREAM_CLOSE) // generates whatever is buffered, then isFinal
                }
                // Stop reading at isFinal: all audio is in, and the server may hang up abruptly after it.
                ctx.http.webSocket(req, name) { openSocket = it; socket.complete(it) }.takeWhile { msg ->
                    if (msg !is WsMessage.Text) return@takeWhile true
                    val json = runCatching { AppJson.parseToJsonElement(msg.text) }.getOrNull() ?: return@takeWhile true
                    json.str("audio")?.takeIf { it.isNotEmpty() }?.let { audio ->
                        send(Base64.getDecoder().decode(audio))
                        json.obj("alignment").arr("chars")?.let { chars ->
                            onVoiced(chars.joinToString("") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() })
                        }
                    }
                    val error = json.str("error") ?: json.obj("error").str("message")
                    if (error != null) throw ApiException(name, json.int("code") ?: 400, json.str("message") ?: error)
                    val done = json.bool("isFinal") == true
                    if (done) openSocket?.close(1000, null)
                    !done
                }.collect()
                sender.cancel()
            }.flowOn(Dispatchers.Default) // JSON + Base64 decoding stays off the main thread
            return TtsOutput.Pcm(24_000, audio)
        }
    }

    override val tts: Tts = Tts()

    // ------------------------------------------------------------------ STT

    override val stt: SttEngine = object : SttEngine {
        override fun supportsBatch(model: String) = !model.contains("realtime")

        override fun supportsStreaming(model: String) = model.contains("realtime")

        /** [transcribe] maps a realtime model to Scribe v2. */
        override fun supportsBatchRecovery(model: String) = true

        override suspend fun transcribe(request: SttRequest, pcm: ShortArray, sampleRate: Int): SttResult {
            val model = request.model.takeUnless { it.contains("realtime") || it.isBlank() } ?: "scribe_v2"
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model_id", model)
                .addFormDataPart("file", "speech.pcm", Pcm.toBytes(pcm).toRequestBody("application/octet-stream".toMediaType()))
                .addFormDataPart("file_format", if (sampleRate == 16_000) "pcm_s16le_16" else "other")
                .addFormDataPart("tag_audio_events", "false")
                .addFormDataPart("timestamps_granularity", "none")
            request.language?.let { form.addFormDataPart("language_code", it.code) }
            val req = Request.Builder().url(url("v1/speech-to-text")).headers(headers()).post(form.build()).build()
            val json = ctx.http.json(req, name)
            return SttResult(json.str("text").orEmpty().trim(), json.str("language_code"))
        }

        override fun stream(request: SttRequest, audio: ReceiveChannel<ShortArray>, sampleRate: Int): Flow<SttEvent> = channelFlow {
            // OkHttp opens WebSockets from http(s) URLs (it upgrades them to ws(s) itself).
            val wsUrl = url(
                "v1/speech-to-text/realtime",
                "model_id" to request.model.ifBlank { "scribe_v2_realtime" },
                "audio_format" to "pcm_$sampleRate",
                "commit_strategy" to "vad",
                "vad_silence_threshold_secs" to (request.silenceMs / 1000.0).coerceIn(0.3, 3.0).toString(),
            ).newBuilder().apply {
                request.language?.let { addQueryParameter("language_code", it.code) }
                request.keyterms.take(50).forEach { addQueryParameter("keyterms", it) }
            }.build()
            val req = Request.Builder().url(wsUrl).headers(headers()).build()

            val socket = CompletableDeferred<WebSocket>()
            var openSocket: WebSocket? = null
            val commitSent = CompletableDeferred<Unit>()

            fun chunk(samples: ShortArray, commit: Boolean) = buildJsonObject {
                put("message_type", "input_audio_chunk")
                put("audio_base_64", Base64.getEncoder().encodeToString(Pcm.toBytes(samples)))
                put("commit", commit)
                put("sample_rate", sampleRate)
            }.toString()

            val sender = launch {
                val ws = socket.await()
                val batch = ArrayList<ShortArray>()
                var samples = 0
                for (frame in audio) {
                    batch += frame
                    samples += frame.size
                    if (samples >= sampleRate / 10) { // ~100 ms per message
                        ws.send(chunk(Pcm.concat(batch), commit = false))
                        batch.clear()
                        samples = 0
                    }
                }
                val tail = if (batch.isEmpty()) ShortArray(sampleRate / 50) else Pcm.concat(batch)
                ws.send(chunk(tail, commit = true))
                commitSent.complete(Unit)
                delay(3_000) // grace period for the last committed transcript
                ws.close(1000, null)
            }

            ctx.http.webSocket(req, name) { openSocket = it; socket.complete(it) }.collect { msg ->
                if (msg !is WsMessage.Text) return@collect
                val json = runCatching { AppJson.parseToJsonElement(msg.text) }.getOrNull() ?: return@collect
                when (val type = json.str("message_type")) {
                    "session_started" -> Unit
                    "partial_transcript" -> json.str("text")?.takeIf { it.isNotBlank() }?.let { send(SttEvent.Partial(it)) }
                    "committed_transcript", "committed_transcript_with_timestamps" -> {
                        val text = json.str("text").orEmpty().trim()
                        if (text.isNotEmpty()) send(SttEvent.Final(text, json.str("language_code")))
                        if (commitSent.isCompleted) openSocket?.close(1000, null)
                    }
                    "warning" -> Log.w(TAG, "Scribe realtime warning: ${json.str("warning")}")
                    else -> {
                        val error = json.str("error") ?: json.obj("error").str("message")
                        if (error != null || type?.contains("error") == true) {
                            if (commitSent.isCompleted) {
                                openSocket?.close(1000, null) // e.g. insufficient audio after final commit
                            } else {
                                throw ApiException(name, 400, "${type ?: "error"}: ${error ?: msg.text.take(200)}")
                            }
                        }
                    }
                }
            }
            sender.cancel()
        }
    }

    // ------------------------------------------------------------------ catalogs

    override suspend fun listModels(capability: Capability): List<ModelInfo> = when (capability) {
        Capability.STT -> listOf(
            ModelInfo("scribe_v2_realtime", "Scribe v2 Realtime", "Streaming over WebSocket with live partials (lowest latency)."),
            ModelInfo("scribe_v2", "Scribe v2", "Batch, highest accuracy."),
            ModelInfo("scribe_v1", "Scribe v1", "Batch, previous generation."),
        )
        Capability.TTS -> {
            val req = Request.Builder().url(url("v1/models")).headers(headers()).get().build()
            val json = ctx.http.json(req, name)
            (json as? kotlinx.serialization.json.JsonArray).objects()
                .filter { it.bool("can_do_text_to_speech") == true }
                .mapNotNull { m ->
                    val id = m.str("model_id") ?: return@mapNotNull null
                    val langs = m.arr("languages")?.size ?: 0
                    ModelInfo(id, m.str("name") ?: id, listOfNotNull(m.str("description")?.take(120), if (langs > 0) "$langs languages" else null).joinToString(" · "))
                }
        }
        Capability.TRANSLATION -> emptyList()
    }

    override suspend fun listVoices(model: String?, language: Language?): List<VoiceInfo> {
        val voices = mutableListOf<VoiceInfo>()
        var token: String? = null
        do {
            val u = url("v2/voices", "page_size" to "100").newBuilder().apply {
                token?.let { addQueryParameter("next_page_token", it) }
            }.build()
            val json = ctx.http.json(Request.Builder().url(u).headers(headers()).get().build(), name)
            json.arr("voices").objects().forEach { v ->
                val id = v.str("voice_id") ?: return@forEach
                val labels = v.obj("labels")
                val desc = listOfNotNull(
                    v.str("category"),
                    labels.str("gender"),
                    labels.str("age"),
                    labels.str("accent"),
                    labels.str("descriptive") ?: labels.str("description"),
                    labels.str("use_case"),
                ).joinToString(" · ")
                val langs = v.arr("verified_languages").objects().mapNotNull { it.str("language") }.distinct()
                voices += VoiceInfo(id, v.str("name") ?: id, desc.ifBlank { null }, langs, v.str("preview_url"))
            }
            token = if (json.bool("has_more") == true) json.str("next_page_token") else null
        } while (token != null && voices.size < 500)
        return voices
    }

    override suspend fun test(): String {
        val json = ctx.http.json(Request.Builder().url(url("v1/user/subscription")).headers(headers()).get().build(), name)
        val tier = json.str("tier")
        val used = json.int("character_count")
        val limit = json.int("character_limit")
        return listOfNotNull("Connected", tier?.let { "tier $it" }, if (used != null && limit != null) "$used / $limit credits used" else null)
            .joinToString(" · ")
    }

    internal companion object {
        const val TAG = "ElevenLabs"

        /** Lower first threshold than the default (120) so the first words are voiced sooner. */
        val CHUNK_SCHEDULE = listOf(50, 120, 160, 250)
        const val STREAM_CLOSE = """{"text":""}"""

        fun streamInit(speed: Float): String = buildJsonObject {
            put("text", " ")
            putJsonObject("voice_settings") {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
                put("speed", speed.coerceIn(0.7f, 1.2f).toDouble())
            }
            putJsonObject("generation_config") {
                put("chunk_length_schedule", kotlinx.serialization.json.JsonArray(CHUNK_SCHEDULE.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
        }.toString()

        fun streamText(piece: String): String = buildJsonObject { put("text", piece) }.toString()

        /** "George" — a premade multilingual voice available on every account. */
        const val DEFAULT_VOICE = "JBFqnCBsd6RMkjVDRZzb"
    }
}
