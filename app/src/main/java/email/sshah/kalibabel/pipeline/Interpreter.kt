package email.sshah.kalibabel.pipeline

import android.util.Log
import email.sshah.kalibabel.audio.AudioRecorder
import email.sshah.kalibabel.audio.Pcm
import email.sshah.kalibabel.audio.SpeechItem
import email.sshah.kalibabel.audio.SpeechQueue
import email.sshah.kalibabel.audio.Vad
import email.sshah.kalibabel.data.AppSettings
import email.sshah.kalibabel.data.HistoryStore
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.data.Languages
import email.sshah.kalibabel.data.ListenMode
import email.sshah.kalibabel.data.SettingsRepository
import email.sshah.kalibabel.net.ApiException
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ContextLine
import email.sshah.kalibabel.providers.MissingKeyException
import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.providers.ProviderRegistry
import email.sshah.kalibabel.providers.ProviderSpecs
import email.sshah.kalibabel.providers.SttEngine
import email.sshah.kalibabel.providers.SttEvent
import email.sshah.kalibabel.providers.SttRequest
import email.sshah.kalibabel.providers.TranslationDelta
import email.sshah.kalibabel.providers.TranslationEngine
import email.sshah.kalibabel.providers.TranslationRequest
import email.sshah.kalibabel.providers.TtsEngine
import email.sshah.kalibabel.providers.TtsOutput
import email.sshah.kalibabel.providers.TtsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The real-time interpreter: microphone → STT → streaming translation → sentence-chunked TTS.
 *
 * Latency strategy:
 *  - realtime STT where available (partials while you speak, final within ~100–300 ms);
 *  - local VAD ends utterances as soon as you pause (configurable);
 *  - connections to the translation/TTS providers are pre-warmed the moment you start talking;
 *  - the translation streams token by token and is cut into sentences: TTS for the first
 *    sentence starts while the LLM is still writing the second;
 *  - TTS audio is streamed as PCM straight into AudioTrack;
 *  - several turns can be in flight; playback stays in conversation order.
 */
class Interpreter(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val registry: ProviderRegistry,
    private val recorder: AudioRecorder,
    private val speech: SpeechQueue,
    private val history: HistoryStore,
) {
    private val _state = MutableStateFlow(InterpreterState(turns = history.load()))
    val state: StateFlow<InterpreterState> = _state.asStateFlow()

    private var listenJob: Job? = null
    private var stopRequest: CompletableDeferred<Unit>? = null
    private val turnJobs = ConcurrentHashMap<Long, Job>()
    private val ids = AtomicLong(System.currentTimeMillis())
    private var lastWarm = 0L

    init {
        scope.launch { speech.speakingTurn.collect { id -> _state.update { it.copy(speakingTurn = id) } } }
    }

    val isListening: Boolean get() = listenJob?.isActive == true

    // ============================================================== public API

    /** Mic button: starts listening for [direction], or stops if that mic is already open. */
    fun toggle(direction: Direction) {
        if (isListening) {
            val current = _state.value.listening
            stopListening()
            if (current == direction) return
            scope.launch {
                listenJob?.join()
                startListening(direction)
            }
            return
        }
        startListening(direction)
    }

    fun startListening(direction: Direction) {
        listenJob?.cancel()
        if (settings.current.pauseMicWhileSpeaking && speech.isSpeaking) speech.stopAll() // barge-in
        val stop = CompletableDeferred<Unit>()
        stopRequest = stop
        _state.update { it.copy(listening = direction, error = null, status = "Listening…", level = 0f) }
        listenJob = scope.launch {
            try {
                runListening(direction, stop)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Listening failed", e)
                showError(e.userMessage())
                markLiveTurnsFailed(e.userMessage())
            } finally {
                _state.update { it.copy(listening = null, level = 0f, status = null) }
                dropEmptyLiveTurns()
            }
        }
    }

    /** Ends the current utterance gracefully (the final transcript is still processed). */
    fun stopListening() {
        stopRequest?.complete(Unit)
    }

    /** Hard stop: microphone, in-flight translations and playback. */
    fun cancelAll() {
        listenJob?.cancel()
        turnJobs.values.forEach { it.cancel() }
        speech.stopAll()
    }

    fun stopSpeaking() = speech.stopAll()

    /** Translates typed or shared text. */
    fun translateText(text: String, direction: Direction) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val s = settings.current
        val id = newTurn(direction, s)
        warmUp(s)
        processTurn(id, clean, null, direction, System.currentTimeMillis(), s)
    }

    /** Speaks a finished turn's translation again. */
    fun replay(turnId: Long) {
        val turn = _state.value.turns.firstOrNull { it.id == turnId } ?: return
        if (turn.translation.isBlank()) return
        val s = settings.current
        val target = turn.targetTag?.let { Languages.byTag(it) } ?: s.langB
        val slot = speech.reserve(turnId)
        scope.launch {
            try {
                val sentences = Channel<String>(Channel.UNLIMITED)
                val chunker = SentenceChunker(early = true)
                chunker.push(turn.translation).forEach { sentences.send(it) }
                chunker.flush()?.let { sentences.send(it) }
                sentences.close()
                speakSentences(turnId, sentences, slot, s) { target }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.userMessage())
            } finally {
                slot.close()
            }
        }
    }

    fun deleteTurn(turnId: Long) {
        turnJobs[turnId]?.cancel()
        _state.update { st -> st.copy(turns = st.turns.filterNot { it.id == turnId }) }
        history.save(_state.value.turns)
    }

    fun clearHistory() {
        cancelAll()
        _state.update { it.copy(turns = emptyList()) }
        history.clear()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Opens TLS connections (and, for self-hosted Ollama, loads the model) for the configured
     * providers so the first request of a turn starts hot.
     */
    fun warmUp(s: AppSettings = settings.current) {
        val now = System.currentTimeMillis()
        if (now - lastWarm < 45_000) return
        lastWarm = now
        val models = Capability.entries.associateWith { cap -> registry.model(s, s.providerFor(cap), cap) }
        listOf(s.sttProvider, s.translationProvider, s.ttsProvider).distinct().forEach { id ->
            scope.launch(Dispatchers.IO) {
                runCatching { if (registry.isConfigured(id)) registry.get(id).prewarm(models) }
            }
        }
    }

    // ============================================================== listening

    private suspend fun runListening(direction: Direction, stop: CompletableDeferred<Unit>) {
        val s = settings.current
        val a = s.langA
        val b = s.langB
        val sttId = s.sttProvider
        val provider = registry.get(sttId)
        val engine = provider.stt ?: throw IllegalStateException("${provider.name} does not provide speech recognition")
        val model = registry.model(s, sttId, Capability.STT)
        val source = when (direction) {
            Direction.A_TO_B -> a
            Direction.B_TO_A -> b
            Direction.AUTO -> null
        }
        val request = SttRequest(
            language = source,
            candidates = listOf(a, b),
            model = model,
            keyterms = s.glossaryEntries.map { it.first }.filter { it.length > 1 }.distinct(),
            silenceMs = s.silenceMs,
            // Self-capturing recognizers restart after each utterance unless we auto-stop.
            continuous = s.listenMode != ListenMode.AUTO_STOP,
            awaitQuiet = {
                speech.speakingTurn.first { it == null }
                delay(250)
            },
        )
        warmUp(s)

        val mode = s.listenMode
        val events: Flow<SttEvent> = when {
            engine.selfCapture -> engine.listen(request, stop)
            engine.supportsStreaming(model) && (s.preferStreamingStt || !engine.supportsBatch(model)) ->
                streamingEvents(engine, request, s, stop)
            else -> batchEvents(engine, request, s, stop)
        }

        var liveId: Long? = null
        var speechEndAt: Long? = null
        val manual = StringBuilder()

        fun live(): Long = liveId ?: newTurn(direction, s, live = true).also { liveId = it }

        events
            .transformWhile { ev ->
                emit(ev)
                !(ev is SttEvent.Final && mode == ListenMode.AUTO_STOP)
            }
            .collect { ev ->
                when (ev) {
                    is SttEvent.Level -> _state.update { it.copy(level = ev.level) }
                    SttEvent.SpeechStarted -> {
                        speechEndAt = null
                        live()
                    }
                    SttEvent.SpeechEnded -> speechEndAt = System.currentTimeMillis()
                    SttEvent.Transcribing -> {
                        val id = live()
                        updateTurn(id) { it.copy(stage = TurnStage.TRANSCRIBING) }
                        _state.update { it.copy(status = "Transcribing…") }
                    }
                    is SttEvent.Partial -> {
                        val id = live()
                        val text = (manual.toString() + " " + ev.text).trim()
                        updateTurn(id) { it.copy(original = text, partial = true) }
                    }
                    is SttEvent.Final -> {
                        val endAt = speechEndAt ?: System.currentTimeMillis()
                        if (mode == ListenMode.MANUAL) {
                            manual.append(' ').append(ev.text)
                            val id = live()
                            updateTurn(id) { it.copy(original = manual.toString().trim(), partial = true) }
                        } else {
                            val id = live()
                            liveId = null
                            speechEndAt = null
                            processTurn(id, ev.text, ev.language, direction, endAt, s)
                            if (mode == ListenMode.CONTINUOUS) _state.update { it.copy(status = "Listening…") }
                        }
                    }
                }
            }

        if (mode == ListenMode.MANUAL && manual.isNotBlank()) {
            val id = liveId ?: newTurn(direction, s, live = true)
            liveId = null
            processTurn(id, manual.toString().trim(), null, direction, speechEndAt ?: System.currentTimeMillis(), s)
        }
    }

    private fun micFrames(s: AppSettings): Flow<ShortArray> =
        recorder.frames(s.micSource, s.noiseSuppression)

    /** Streaming STT: frames go to the provider as they are captured; local VAD drives auto-stop. */
    private fun streamingEvents(engine: SttEngine, request: SttRequest, s: AppSettings, stop: CompletableDeferred<Unit>): Flow<SttEvent> =
        channelFlow {
            val audio = Channel<ShortArray>(capacity = 1000)
            val vad = Vad(silenceMs = s.silenceMs)
            val started = System.currentTimeMillis()
            val recording = launch {
                var spoke = false
                micFrames(s).takeWhile { !stop.isCompleted }.collect { raw ->
                    val frame = if (s.pauseMicWhileSpeaking && speech.isSpeaking) ShortArray(raw.size) else raw
                    val r = vad.process(frame)
                    send(SttEvent.Level(r.level))
                    when (r.event) {
                        Vad.Event.SpeechStart -> {
                            spoke = true
                            send(SttEvent.SpeechStarted)
                        }
                        is Vad.Event.SpeechEnd -> {
                            send(SttEvent.SpeechEnded)
                            if (s.listenMode == ListenMode.AUTO_STOP) stop.complete(Unit)
                        }
                        Vad.Event.None -> Unit
                    }
                    if (!spoke && s.listenMode == ListenMode.AUTO_STOP && System.currentTimeMillis() - started > NO_SPEECH_TIMEOUT_MS) {
                        stop.complete(Unit)
                    }
                    audio.send(frame)
                }
                if (vad.isSpeaking) send(SttEvent.SpeechEnded)
                audio.close()
            }
            try {
                engine.stream(request, audio, Pcm.SAMPLE_RATE).collect { send(it) }
            } finally {
                recording.cancel()
            }
        }

    /** Batch STT: local VAD cuts utterances; each one is uploaded the instant the speaker pauses. */
    private fun batchEvents(engine: SttEngine, request: SttRequest, s: AppSettings, stop: CompletableDeferred<Unit>): Flow<SttEvent> =
        channelFlow {
            val vad = Vad(silenceMs = s.silenceMs)
            val segments = Channel<ShortArray>(Channel.UNLIMITED)
            val mode = s.listenMode
            val transcriber = launch {
                for (pcm in segments) {
                    if (Pcm.durationMs(pcm.size) < MIN_UTTERANCE_MS) continue
                    send(SttEvent.Transcribing)
                    val result = engine.transcribe(request, pcm, Pcm.SAMPLE_RATE)
                    if (result.text.isNotBlank() && !isNoise(result.text)) send(SttEvent.Final(result.text, result.language))
                }
            }
            val manual = ArrayList<ShortArray>()
            val started = System.currentTimeMillis()
            var spoke = false
            micFrames(s).takeWhile { !stop.isCompleted }.collect { raw ->
                val frame = if (s.pauseMicWhileSpeaking && speech.isSpeaking) ShortArray(raw.size) else raw
                val r = vad.process(frame)
                send(SttEvent.Level(r.level))
                when (val e = r.event) {
                    Vad.Event.SpeechStart -> {
                        spoke = true
                        send(SttEvent.SpeechStarted)
                    }
                    is Vad.Event.SpeechEnd -> {
                        send(SttEvent.SpeechEnded)
                        when (mode) {
                            ListenMode.MANUAL -> manual += e.audio
                            ListenMode.CONTINUOUS -> segments.send(e.audio)
                            ListenMode.AUTO_STOP -> {
                                segments.send(e.audio)
                                stop.complete(Unit)
                            }
                        }
                    }
                    Vad.Event.None -> Unit
                }
                if (!spoke && mode == ListenMode.AUTO_STOP && System.currentTimeMillis() - started > NO_SPEECH_TIMEOUT_MS) {
                    stop.complete(Unit)
                }
            }
            vad.flush()?.let { tail ->
                send(SttEvent.SpeechEnded)
                if (mode == ListenMode.MANUAL) manual += tail else segments.send(tail)
            }
            if (mode == ListenMode.MANUAL && manual.isNotEmpty()) segments.send(Pcm.concat(manual))
            segments.close()
            transcriber.join()
        }

    // ============================================================== turns

    private fun newTurn(direction: Direction, s: AppSettings, live: Boolean = false): Long {
        val id = ids.incrementAndGet()
        val (src, dst) = when (direction) {
            Direction.A_TO_B -> s.languageA to s.languageB
            Direction.B_TO_A -> s.languageB to s.languageA
            Direction.AUTO -> null to null
        }
        val turn = Turn(
            id = id,
            direction = direction,
            sourceTag = src,
            targetTag = dst,
            partial = live,
            stage = if (live) TurnStage.LISTENING else TurnStage.TRANSLATING,
            route = route(s),
        )
        _state.update { it.copy(turns = it.turns + turn) }
        return id
    }

    private fun route(s: AppSettings): String {
        fun part(cap: Capability): String {
            val id = s.providerFor(cap)
            val model = registry.model(s, id, cap).substringAfterLast('/')
            val name = ProviderSpecs[id].displayName.substringBefore(" (")
            return if (model.isBlank()) name else "$name $model"
        }
        return listOf(part(Capability.STT), part(Capability.TRANSLATION), if (s.autoSpeak) part(Capability.TTS) else null)
            .filterNotNull().joinToString(" → ")
    }

    private fun processTurn(id: Long, text: String, sttLanguage: String?, direction: Direction, speechEndAt: Long, s: AppSettings) {
        val transcriptAt = System.currentTimeMillis()
        updateTurn(id) {
            it.copy(
                original = text,
                partial = false,
                stage = TurnStage.TRANSLATING,
                metrics = it.metrics.copy(sttMs = (transcriptAt - speechEndAt).coerceAtLeast(0)),
            )
        }
        _state.update { it.copy(status = if (isListening && s.listenMode == ListenMode.CONTINUOUS) "Listening…" else "Translating…") }
        val slot = if (s.autoSpeak) {
            speech.reserve(
                turnId = id,
                onFirstAudio = {
                    val t = System.currentTimeMillis()
                    updateTurn(id) {
                        it.copy(
                            stage = TurnStage.SPEAKING,
                            metrics = it.metrics.copy(firstAudioMs = t - transcriptAt, mouthToEarMs = t - speechEndAt),
                        )
                    }
                },
                onFinished = {
                    updateTurn(id) { if (it.stage == TurnStage.SPEAKING || it.stage == TurnStage.TRANSLATING) it.copy(stage = TurnStage.DONE) else it }
                    persist()
                },
            )
        } else {
            null
        }
        val job = scope.launch {
            try {
                translateAndSpeak(id, text, sttLanguage, direction, s, slot, transcriptAt)
                if (slot == null) updateTurn(id) { it.copy(stage = TurnStage.DONE) }
            } catch (e: CancellationException) {
                updateTurn(id) { if (it.active) it.copy(stage = TurnStage.CANCELLED) else it }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Turn $id failed", e)
                updateTurn(id) { it.copy(stage = TurnStage.ERROR, error = e.userMessage()) }
            } finally {
                slot?.close()
                turnJobs.remove(id)
                if (!isListening) _state.update { it.copy(status = null) }
                persist()
            }
        }
        turnJobs[id] = job
    }

    private suspend fun resolveDirection(text: String, sttLanguage: String?, direction: Direction, a: Language, b: Language): Pair<Language?, Language?> =
        when (direction) {
            Direction.A_TO_B -> a to b
            Direction.B_TO_A -> b to a
            Direction.AUTO -> {
                val detected = when {
                    Languages.matches(a, sttLanguage) && !Languages.matches(b, sttLanguage) -> a
                    Languages.matches(b, sttLanguage) && !Languages.matches(a, sttLanguage) -> b
                    else -> registry.languageDetector.pick(text, a, b)
                }
                detected to detected?.let { if (it == a) b else a }
            }
        }

    private suspend fun translateAndSpeak(
        id: Long,
        text: String,
        sttLanguage: String?,
        direction: Direction,
        s: AppSettings,
        slot: SpeechQueue.Slot?,
        transcriptAt: Long,
    ) = coroutineScope {
        val a = s.langA
        val b = s.langB
        val (source, target) = resolveDirection(text, sttLanguage, direction, a, b)
        updateTurn(id) { it.copy(sourceTag = source?.tag ?: it.sourceTag, targetTag = target?.tag ?: it.targetTag) }

        val trId = s.translationProvider
        val provider = registry.get(trId)
        val engine = provider.translation ?: throw IllegalStateException("${provider.name} does not provide translation")
        val model = registry.model(s, trId, Capability.TRANSLATION)
        val context = recentContext(id, s.contextTurns)

        var spokenTarget: Language? = target
        var statusNote: String? = null
        val sentences = Channel<String>(Channel.UNLIMITED)
        val ttsJob = slot?.let { launch { speakSentences(id, sentences, it, s) { spokenTarget ?: b } } }

        val chunker = SentenceChunker(early = s.earlySpeech)
        val translation = StringBuilder()
        var firstTokenAt: Long? = null

        suspend fun onText(t: String) {
            if (t.isEmpty()) return
            if (firstTokenAt == null) {
                val now = System.currentTimeMillis()
                firstTokenAt = now
                updateTurn(id) { it.copy(metrics = it.metrics.copy(firstTokenMs = now - transcriptAt)) }
            }
            translation.append(t)
            val shown = translation.toString().trimStart()
            updateTurn(id) { it.copy(translation = shown) }
            chunker.push(t).forEach { sentences.send(it) }
        }

        fun setTarget(l: Language) {
            spokenTarget = l
            val src = if (l == a) b else a
            updateTurn(id) { it.copy(targetTag = l.tag, sourceTag = src.tag) }
        }

        suspend fun run(e: TranslationEngine, m: String) {
            val bidirectional = source == null
            val tagParser = if (bidirectional && e.isLlm) LangTagParser() else null
            val req = TranslationRequest(
                text = text,
                source = source,
                target = target,
                languageA = a,
                languageB = b,
                bidirectional = bidirectional,
                model = m,
                systemPrompt = Prompts.system(source, target, a, b, s.formality, s.glossaryEntries, s.customInstructions, context),
                context = context,
                formality = s.formality,
            )
            e.translate(req).collect { d ->
                when (d) {
                    is TranslationDelta.Status -> {
                        statusNote = d.message
                        updateTurn(id) { it.copy(note = d.message) }
                    }
                    is TranslationDelta.Target -> setTarget(if (Languages.matches(a, d.language.tag)) a else if (Languages.matches(b, d.language.tag)) b else d.language)
                    is TranslationDelta.Text -> if (tagParser != null) {
                        val passed = tagParser.push(d.text)
                        val tag = tagParser.tag
                        if (spokenTarget == null && tag != null) {
                            setTarget(if (Languages.matches(a, tag)) a else b)
                        }
                        onText(passed)
                    } else {
                        onText(d.text)
                    }
                }
            }
            tagParser?.flush()?.let { onText(it) }
        }

        try {
            run(engine, model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val canFallback = translation.isEmpty() && s.fallbackToOnDevice && trId != ProviderId.ANDROID &&
                registry.onDeviceTranslator.isSupported(a) && registry.onDeviceTranslator.isSupported(b)
            if (!canFallback) throw e
            Log.w(TAG, "Translation failed, falling back to on-device", e)
            updateTurn(id) { it.copy(note = "Translated on-device — ${e.userMessage()}") }
            run(registry.get(ProviderId.ANDROID).translation!!, "mlkit")
        }
        statusNote?.let { note -> updateTurn(id) { if (it.note == note) it.copy(note = null) else it } }
        if (spokenTarget == null) spokenTarget = target ?: b
        chunker.flush()?.let { sentences.send(it) }
        sentences.close()
        val doneAt = System.currentTimeMillis()
        updateTurn(id) { it.copy(metrics = it.metrics.copy(translateMs = doneAt - transcriptAt)) }
        _state.update { if (isListening) it else it.copy(status = if (slot != null) "Speaking…" else null) }
        ttsJob?.join()
    }

    private suspend fun speakSentences(
        id: Long,
        sentences: ReceiveChannel<String>,
        slot: SpeechQueue.Slot,
        s: AppSettings,
        targetOf: () -> Language,
    ) {
        val ttsId = s.ttsProvider
        val provider = registry.get(ttsId)
        var engine: TtsEngine = provider.tts ?: throw IllegalStateException("${provider.name} does not provide speech synthesis")
        var model = registry.model(s, ttsId, Capability.TTS)
        var voiceProvider = ttsId
        var previous: String? = null
        for (sentence in sentences) {
            val lang = targetOf()
            val side = if (lang == s.langA) "A" else "B"
            val request = TtsRequest(sentence, lang, model, registry.voice(s, voiceProvider, side), s.speechRate, previous)
            val ok = runCatching { enqueue(engine.synthesize(request), slot) }
            ok.exceptionOrNull()?.let { e ->
                if (e is CancellationException) throw e
                val canFallback = s.fallbackToOnDevice && voiceProvider != ProviderId.ANDROID
                if (!canFallback) throw e
                Log.w(TAG, "TTS failed, falling back to system voice", e)
                updateTurn(id) { it.copy(note = "Spoken with the system voice — ${e.userMessage()}") }
                engine = registry.get(ProviderId.ANDROID).tts!!
                voiceProvider = ProviderId.ANDROID
                model = registry.model(s, ProviderId.ANDROID, Capability.TTS)
                enqueue(engine.synthesize(request.copy(model = model, voice = registry.voice(s, ProviderId.ANDROID, side))), slot)
            }
            previous = sentence
        }
    }

    /** Hands synthesized audio to the ordered speech queue while it is still downloading. */
    private suspend fun enqueue(output: TtsOutput, slot: SpeechQueue.Slot) {
        when (output) {
            is TtsOutput.SelfPlayed -> slot.items.send(SpeechItem.SelfPlayed(output.play))
            is TtsOutput.Pcm -> pump(output.chunks) { slot.items.send(SpeechItem.Pcm(output.sampleRate, it)) }
            is TtsOutput.Wav -> pump(output.chunks) { slot.items.send(SpeechItem.Wav(it)) }
            is TtsOutput.Encoded -> pump(output.bytes) { slot.items.send(SpeechItem.Encoded(output.mimeType, it)) }
        }
    }

    /**
     * Starts the download; the item is only queued once the first bytes arrived, so a failing
     * request can still fall back to another voice for the same sentence.
     */
    private suspend fun pump(source: Flow<ByteArray>, register: suspend (ReceiveChannel<ByteArray>) -> Unit) {
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        var registered = false
        try {
            source.collect { chunk ->
                // The queue cancels the channel when playback was stopped: abort the download.
                if (channel.trySend(chunk).isClosed) throw PlaybackDiscarded()
                if (!registered) {
                    registered = true
                    register(channel)
                }
            }
            channel.close()
        } catch (_: PlaybackDiscarded) {
            return
        } catch (e: CancellationException) {
            channel.cancel()
            throw e
        } catch (e: Exception) {
            channel.close(e)
            if (!registered) throw e
            Log.w(TAG, "Audio stream interrupted", e)
        }
    }

    private fun recentContext(beforeId: Long, count: Int): List<ContextLine> {
        if (count <= 0) return emptyList()
        return _state.value.turns
            .filter { it.id < beforeId && it.translation.isNotBlank() && it.original.isNotBlank() && it.stage != TurnStage.ERROR }
            .takeLast(count)
            .map { ContextLine(Languages.byTag(it.sourceTag ?: settings.current.languageA), it.original, it.translation) }
    }

    // ============================================================== state helpers

    private fun updateTurn(id: Long, transform: (Turn) -> Turn) {
        _state.update { st -> st.copy(turns = st.turns.map { if (it.id == id) transform(it) else it }) }
    }

    private fun dropEmptyLiveTurns() {
        _state.update { st ->
            st.copy(turns = st.turns.filterNot { it.stage == TurnStage.LISTENING && it.original.isBlank() })
        }
    }

    private fun markLiveTurnsFailed(message: String) {
        _state.update { st ->
            st.copy(
                turns = st.turns.map {
                    if (it.stage == TurnStage.LISTENING || it.stage == TurnStage.TRANSCRIBING) {
                        if (it.original.isBlank()) it else it.copy(stage = TurnStage.ERROR, error = message, partial = false)
                    } else {
                        it
                    }
                },
            )
        }
    }

    private fun showError(message: String) = _state.update { it.copy(error = message) }

    private fun persist() = history.save(_state.value.turns)

    private fun isNoise(text: String): Boolean {
        val t = text.trim().lowercase().trim('.', '!', '?', ' ')
        // Whisper-family hallucinations on silence.
        return t.isEmpty() || t in setOf("you", "thank you", "thanks for watching", "thank you for watching", "bye", "[music]", "(music)", "[blank_audio]")
    }

    private companion object {
        const val TAG = "Interpreter"
        const val NO_SPEECH_TIMEOUT_MS = 10_000L
        const val MIN_UTTERANCE_MS = 250L
    }
}

private class PlaybackDiscarded : Exception()

/** Short, user-facing description of a failure. */
fun Throwable.userMessage(): String = when (this) {
    is MissingKeyException -> "${message}. Add it under Settings → Providers."
    is ApiException -> when (code) {
        401, 403 -> "$provider rejected the API key or plan (HTTP $code): ${message?.substringAfter("– ")}"
        404 -> "$provider: model or endpoint not found — check the selected model."
        422 -> "$provider: ${message?.substringAfter("– ")}"
        429 -> "$provider is rate-limiting requests (HTTP 429)."
        else -> message ?: "$provider error"
    }
    is UnknownHostException -> "No internet connection (could not resolve ${message ?: "host"})."
    is SocketTimeoutException -> "The provider took too long to respond."
    is SecurityException -> message ?: "Permission denied"
    is IOException -> message ?: "Network error"
    else -> message ?: javaClass.simpleName
}
