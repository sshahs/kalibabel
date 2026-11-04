package email.sshah.kalibabel.providers

import email.sshah.kalibabel.data.Formality
import email.sshah.kalibabel.data.Language
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow

/** What a provider can do for the interpreter pipeline. */
enum class Capability(val label: String, val short: String) {
    STT("Speech recognition", "STT"),
    TRANSLATION("Translation", "MT"),
    TTS("Speech synthesis", "TTS"),
}

data class ModelInfo(
    val id: String,
    val label: String = id,
    val description: String? = null,
)

data class VoiceInfo(
    val id: String,
    val name: String,
    val description: String? = null,
    /** BCP-47 / ISO code(s) the voice is designed for, if known. Null = multilingual/unknown. */
    val languages: List<String> = emptyList(),
    val previewUrl: String? = null,
)

// ---------------------------------------------------------------------------------------------
// Translation
// ---------------------------------------------------------------------------------------------

/** One line of recent conversation, sent as disambiguation context. */
data class ContextLine(val language: Language, val original: String, val translation: String)

data class TranslationRequest(
    val text: String,
    /** Null when the direction is unknown and the engine must pick ([bidirectional] = true). */
    val source: Language?,
    val target: Language?,
    val languageA: Language,
    val languageB: Language,
    val bidirectional: Boolean,
    val model: String,
    val systemPrompt: String,
    val context: List<ContextLine>,
    val formality: Formality,
)

sealed interface TranslationDelta {
    data class Text(val text: String) : TranslationDelta

    /** Emitted by engines that decide the target language themselves (bidirectional mode). */
    data class Target(val language: Language) : TranslationDelta

    /** Transient progress note shown on the turn (e.g. a one-time language pack download). */
    data class Status(val message: String) : TranslationDelta
}

interface TranslationEngine {
    /** True for LLM engines that follow [TranslationRequest.systemPrompt] (and the `[xx]` tag protocol). */
    val isLlm: Boolean

    /** Streams the translation as text deltas, as early as the provider allows. */
    fun translate(request: TranslationRequest): Flow<TranslationDelta>
}

// ---------------------------------------------------------------------------------------------
// Speech to text
// ---------------------------------------------------------------------------------------------

data class SttRequest(
    /** Expected spoken language, or null to auto-detect. */
    val language: Language?,
    /** Candidate languages for auto-detection (the conversation pair). */
    val candidates: List<Language>,
    val model: String,
    /** Domain terms / names to bias recognition towards (from the glossary). */
    val keyterms: List<String>,
    /** End-of-utterance silence the user configured, in milliseconds. */
    val silenceMs: Int,
    /** Whether the session keeps listening after an utterance (affects self-capturing engines). */
    val continuous: Boolean,
    /**
     * Suspends until the app has finished speaking. Self-capturing engines call it before they
     * re-open the microphone in continuous mode so they do not transcribe our own TTS output.
     */
    val awaitQuiet: (suspend () -> Unit)? = null,
)

data class SttResult(val text: String, val language: String? = null)

sealed interface SttEvent {
    data class Partial(val text: String) : SttEvent

    data class Final(val text: String, val language: String? = null) : SttEvent

    data object SpeechStarted : SttEvent

    data object SpeechEnded : SttEvent

    /** Input level in 0..1 for the UI meter. */
    data class Level(val level: Float) : SttEvent

    /** A batch upload/recognition started for a captured utterance. */
    data object Transcribing : SttEvent
}

interface SttEngine {
    /** Engines that capture the microphone themselves (Android SpeechRecognizer). */
    val selfCapture: Boolean get() = false

    fun supportsBatch(model: String): Boolean = true

    fun supportsStreaming(model: String): Boolean = false

    /** Transcribes one complete utterance of 16-bit mono PCM. */
    suspend fun transcribe(request: SttRequest, pcm: ShortArray, sampleRate: Int): SttResult =
        throw UnsupportedOperationException("Batch transcription is not supported by this engine")

    /**
     * Streams PCM frames to a realtime recognizer. The flow completes after [audio] is closed and
     * the provider delivered its last final transcript.
     */
    fun stream(request: SttRequest, audio: ReceiveChannel<ShortArray>, sampleRate: Int): Flow<SttEvent> =
        throw UnsupportedOperationException("Streaming transcription is not supported by this engine")

    /** Self-capturing recognition. Completing [stop] asks the recognizer to finish the utterance. */
    fun listen(request: SttRequest, stop: CompletableDeferred<Unit>): Flow<SttEvent> =
        throw UnsupportedOperationException("This engine does not capture audio itself")
}

// ---------------------------------------------------------------------------------------------
// Text to speech
// ---------------------------------------------------------------------------------------------

data class TtsRequest(
    val text: String,
    val language: Language,
    val model: String,
    /** Provider voice id, or null to let the engine choose one for [language]. */
    val voice: String?,
    /** 1.0 = normal. */
    val speed: Float,
    /** Text spoken just before this chunk (improves prosody continuity where supported). */
    val previousText: String?,
)

sealed interface TtsOutput {
    /** Raw 16-bit little-endian mono PCM, streamed as it is generated. */
    class Pcm(val sampleRate: Int, val chunks: Flow<ByteArray>) : TtsOutput

    /** A WAV stream; the player parses the header to learn the sample rate. */
    class Wav(val chunks: Flow<ByteArray>) : TtsOutput

    /** Compressed audio (mp3/ogg/...) played with MediaPlayer once fully downloaded. */
    class Encoded(val mimeType: String, val bytes: Flow<ByteArray>) : TtsOutput

    /** The engine plays the audio itself (Android TextToSpeech). Suspends until done. */
    class SelfPlayed(val play: suspend () -> Unit) : TtsOutput
}

interface TtsEngine {
    suspend fun synthesize(request: TtsRequest): TtsOutput
}

/**
 * TTS that accepts text incrementally — word by word as the translation streams in — and returns
 * one continuous audio stream, so speech starts before a sentence is complete.
 */
interface StreamingTextTtsEngine {
    fun supportsTextStreaming(model: String): Boolean

    /**
     * Opens the stream immediately (so the connection is ready before the first word); text sent
     * to [text] is spoken as it arrives, and closing [text] finishes the utterance.
     * [request].text is ignored.
     */
    fun synthesizeTextStream(request: TtsRequest, text: ReceiveChannel<String>): TtsOutput.Pcm
}
