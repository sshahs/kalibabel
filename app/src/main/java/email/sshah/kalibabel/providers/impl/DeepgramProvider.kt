package email.sshah.kalibabel.providers.impl

import email.sshah.kalibabel.audio.Pcm
import email.sshah.kalibabel.audio.Wav
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.data.Languages
import email.sshah.kalibabel.net.AppJson
import email.sshah.kalibabel.net.WsMessage
import email.sshah.kalibabel.net.arr
import email.sshah.kalibabel.net.asString
import email.sshah.kalibabel.net.at
import email.sshah.kalibabel.net.bool
import email.sshah.kalibabel.net.bytes
import email.sshah.kalibabel.net.headers
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
import email.sshah.kalibabel.providers.TtsEngine
import email.sshah.kalibabel.providers.TtsOutput
import email.sshah.kalibabel.providers.TtsRequest
import email.sshah.kalibabel.providers.VoiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString

/** Deepgram: Nova realtime/batch STT and Aura TTS. */
class DeepgramProvider(ctx: ProviderContext) : Provider(ctx) {

    private fun headers() = mapOf("Authorization" to "Token ${ctx.requireKey()}")

    private fun url(path: String, vararg query: Pair<String, String>): HttpUrl = ctx.baseUrl.toHttpUrl().newBuilder().apply {
        addPathSegments(path.trimStart('/'))
        query.forEach { (k, v) -> addQueryParameter(k, v) }
        // Only the inference endpoints understand the Model Improvement Program opt-out.
        if (ctx.privacy && (path.contains("listen") || path.contains("speak"))) addQueryParameter("mip_opt_out", "true")
    }.build()

    private fun languageParam(l: Language): String = when (l.code) {
        "zh", "pt", "en", "es", "fr" -> if (l.region != null) l.tag else l.code
        else -> l.code
    }

    private fun HttpUrl.Builder.applyLanguage(request: SttRequest, streaming: Boolean) = apply {
        val lang = request.language
        when {
            lang != null -> addQueryParameter("language", languageParam(lang))
            request.model.startsWith("nova-3") -> addQueryParameter("language", "multi")
            !streaming -> addQueryParameter("detect_language", "true")
        }
        if (request.model.startsWith("nova-3")) {
            request.keyterms.take(50).forEach { addQueryParameter("keyterm", it) }
        } else if (request.model.startsWith("nova-2")) {
            request.keyterms.take(50).forEach { addQueryParameter("keywords", it) }
        }
    }

    // ------------------------------------------------------------------ STT

    override val stt: SttEngine = object : SttEngine {
        override fun supportsStreaming(model: String) = !model.startsWith("whisper")

        override suspend fun transcribe(request: SttRequest, pcm: ShortArray, sampleRate: Int): SttResult {
            val u = url("v1/listen", "model" to request.model, "smart_format" to "true", "punctuate" to "true")
                .newBuilder().applyLanguage(request, streaming = false).build()
            val req = Request.Builder().url(u).headers(headers())
                .post(Wav.encode(pcm, sampleRate).toRequestBody("audio/wav".toMediaType())).build()
            val json = ctx.http.json(req, name)
            val channel = json.obj("results").arr("channels").at(0)
            val text = channel.arr("alternatives").at(0).str("transcript").orEmpty()
            return SttResult(text.trim(), channel.str("detected_language"))
        }

        override fun stream(request: SttRequest, audio: ReceiveChannel<ShortArray>, sampleRate: Int): Flow<SttEvent> = channelFlow {
            val u = url(
                "v1/listen",
                "model" to request.model,
                "encoding" to "linear16",
                "sample_rate" to sampleRate.toString(),
                "channels" to "1",
                "interim_results" to "true",
                "smart_format" to "true",
                "punctuate" to "true",
                "endpointing" to request.silenceMs.coerceIn(10, 5000).toString(),
                "utterance_end_ms" to (request.silenceMs + 300).coerceIn(1000, 5000).toString(),
                "vad_events" to "true",
            ).newBuilder().applyLanguage(request, streaming = true).build()
            val req = Request.Builder().url(u).headers(headers()).build()

            val socket = CompletableDeferred<WebSocket>()
            val closing = CompletableDeferred<Unit>()
            val segment = StringBuilder()
            val lock = Mutex()

            val sender = launch {
                val ws = socket.await()
                val batch = ArrayList<ShortArray>()
                var samples = 0
                for (frame in audio) {
                    batch += frame
                    samples += frame.size
                    if (samples >= sampleRate / 20) { // 50 ms
                        ws.send(Pcm.toBytes(Pcm.concat(batch)).toByteString())
                        batch.clear()
                        samples = 0
                    }
                }
                if (batch.isNotEmpty()) ws.send(Pcm.toBytes(Pcm.concat(batch)).toByteString())
                closing.complete(Unit)
                ws.send("""{"type":"Finalize"}""")
                ws.send("""{"type":"CloseStream"}""")
                delay(3_000)
                ws.close(1000, null)
            }

            suspend fun flushSegment() {
                val text = lock.withLock {
                    val t = segment.toString().trim()
                    segment.setLength(0)
                    t
                }
                if (text.isNotEmpty()) send(SttEvent.Final(text))
            }

            ctx.http.webSocket(req, name) { socket.complete(it) }.collect { msg ->
                if (msg !is WsMessage.Text) return@collect
                val json = runCatching { AppJson.parseToJsonElement(msg.text) }.getOrNull() as? JsonObject ?: return@collect
                when (json.str("type")) {
                    "Results" -> {
                        val transcript = json.obj("channel").arr("alternatives").at(0).str("transcript").orEmpty()
                        val isFinal = json.bool("is_final") == true
                        if (isFinal) {
                            if (transcript.isNotBlank()) lock.withLock { segment.append(' ').append(transcript) }
                            if (json.bool("speech_final") == true || json.bool("from_finalize") == true) flushSegment()
                        } else if (transcript.isNotBlank()) {
                            val prefix = lock.withLock { segment.toString() }
                            send(SttEvent.Partial("$prefix $transcript".trim()))
                        }
                    }
                    "UtteranceEnd" -> flushSegment()
                    "SpeechStarted" -> Unit
                }
            }
            flushSegment()
            sender.cancel()
        }
    }

    // ------------------------------------------------------------------ TTS

    private var cachedVoices: List<VoiceInfo>? = null

    private suspend fun voiceFor(language: Language, model: String): String {
        val voices = cachedVoices ?: runCatching { listVoices(model, null) }.getOrDefault(emptyList()).also { cachedVoices = it }
        voices.firstOrNull { v -> v.id.startsWith(model) && v.languages.any { Languages.matches(language, it) } }?.let { return it.id }
        return when (language.code) {
            "es" -> "aura-2-celeste-es"
            "de" -> "aura-2-julius-de"
            "fr" -> "aura-2-agathe-fr"
            "it" -> "aura-2-livia-it"
            "nl" -> "aura-2-rhea-nl"
            "ja" -> "aura-2-fujin-ja"
            else -> "aura-2-thalia-en"
        }
    }

    override val tts: TtsEngine = object : TtsEngine {
        override suspend fun synthesize(request: TtsRequest): TtsOutput {
            val family = request.model.ifBlank { "aura-2" }
            val voice = request.voice?.takeIf { it.isNotBlank() } ?: voiceFor(request.language, family)
            val u = url("v1/speak", "model" to voice, "encoding" to "linear16", "sample_rate" to "24000", "container" to "none")
            val body = buildJsonObject { put("text", request.text) }
            val req = Request.Builder().url(u).headers(headers()).post(jsonBody(body)).build()
            return TtsOutput.Pcm(24_000, ctx.http.bytes(req, name))
        }
    }

    // ------------------------------------------------------------------ catalogs

    private suspend fun modelsJson() = ctx.http.json(Request.Builder().url(url("v1/models")).headers(headers()).get().build(), name)

    override suspend fun listModels(capability: Capability): List<ModelInfo> = when (capability) {
        Capability.STT -> modelsJson().arr("stt").objects()
            .filter { it.bool("streaming") != false || it.bool("batch") == true }
            .groupBy { it.str("canonical_name") ?: it.str("name").orEmpty() }
            .filterKeys { it.isNotBlank() }
            .map { (id, entries) ->
                val langs = entries.flatMap { e -> e.arr("languages")?.mapNotNull { it.asString }.orEmpty() }.distinct()
                ModelInfo(id, id, "${langs.size} languages" + if (entries.any { it.bool("streaming") == true }) " · streaming" else "")
            }
            .sortedWith(compareBy({ !it.id.startsWith("nova-3") }, { !it.id.startsWith("nova-2") }, { it.id }))
        Capability.TTS -> modelsJson().arr("tts").objects()
            .mapNotNull { it.str("architecture") }
            .distinct()
            .map { ModelInfo(it, it, "Voice family — pick the voice per language below") }
        Capability.TRANSLATION -> emptyList()
    }

    override suspend fun listVoices(model: String?, language: Language?): List<VoiceInfo> =
        modelsJson().arr("tts").objects().mapNotNull { v ->
            val id = v.str("canonical_name") ?: return@mapNotNull null
            if (model != null && v.str("architecture") != null && v.str("architecture") != model) return@mapNotNull null
            val langs = v.arr("languages")?.mapNotNull { it.asString }.orEmpty()
            val meta = v.obj("metadata")
            val tags = meta.arr("tags")?.mapNotNull { it.asString }.orEmpty().take(4)
            VoiceInfo(
                id = id,
                name = v.str("name")?.replaceFirstChar { it.uppercase() } ?: id,
                description = (listOfNotNull(meta.str("accent")) + tags).joinToString(" · ").ifBlank { null },
                languages = langs,
                previewUrl = meta.str("sample"),
            )
        }.distinctBy { it.id }

    override suspend fun test(): String {
        val json = ctx.http.json(Request.Builder().url(url("v1/projects")).headers(headers()).get().build(), name)
        val project = json.arr("projects").at(0).str("name")
        return "Connected" + (project?.let { " · project $it" } ?: "")
    }
}
