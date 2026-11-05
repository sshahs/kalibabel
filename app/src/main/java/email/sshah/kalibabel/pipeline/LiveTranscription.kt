package email.sshah.kalibabel.pipeline

import android.util.Log
import email.sshah.kalibabel.audio.Pcm
import email.sshah.kalibabel.audio.Vad
import email.sshah.kalibabel.data.ErrorLog
import email.sshah.kalibabel.data.ListenMode
import email.sshah.kalibabel.net.ApiException
import email.sshah.kalibabel.providers.MissingKeyException
import email.sshah.kalibabel.providers.SttEngine
import email.sshah.kalibabel.providers.SttEvent
import email.sshah.kalibabel.providers.SttRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Streaming speech recognition that does not lose speech when its connection drops.
 *
 * Microphone [frames] go to the provider as they are captured while the local [vad] drives
 * auto-stop. The local detector also keeps each utterance's audio until the provider has
 * confirmed it with a final transcript. If the live connection then fails (or the provider ends
 * the session early), the unconfirmed utterances, the one in progress and any that follow in
 * interpreter/manual mode are transcribed by upload instead. Engines that can't upload fall back
 * to the last live partial transcript.
 */
internal fun resilientStream(
    engine: SttEngine,
    request: SttRequest,
    frames: Flow<ShortArray>,
    vad: Vad,
    listenMode: ListenMode,
    stop: CompletableDeferred<Unit>,
    providerName: String,
    noSpeechTimeoutMs: Long = 10_000L,
    minUtteranceMs: Long = 250L,
): Flow<SttEvent> = channelFlow {
    val audio = Channel<ShortArray>(Channel.UNLIMITED)
    val pending = UnconfirmedSpeech()
    val uploads = Channel<ShortArray>(Channel.UNLIMITED) // utterances to transcribe once the live stream is gone
    val started = System.currentTimeMillis()

    val recording = launch {
        var spoke = false
        frames.takeWhile { !stop.isCompleted }.collect { frame ->
            val r = vad.process(frame)
            send(SttEvent.Level(r.level))
            when (val e = r.event) {
                Vad.Event.SpeechStart -> {
                    spoke = true
                    send(SttEvent.SpeechStarted)
                }
                is Vad.Event.SpeechEnd -> {
                    send(SttEvent.SpeechEnded)
                    if (pending.ended(e.audio)) uploads.send(e.audio)
                    if (listenMode == ListenMode.AUTO_STOP) stop.complete(Unit)
                }
                Vad.Event.None -> Unit
            }
            if (!spoke && listenMode == ListenMode.AUTO_STOP && System.currentTimeMillis() - started > noSpeechTimeoutMs) {
                stop.complete(Unit)
            }
            audio.trySend(frame)
        }
        vad.flush()?.let { tail ->
            send(SttEvent.SpeechEnded)
            if (pending.ended(tail)) uploads.send(tail)
        }
        audio.close()
    }

    try {
        val failure: Exception? = try {
            engine.stream(request, audio, Pcm.SAMPLE_RATE).collect { ev ->
                when (ev) {
                    is SttEvent.Partial -> pending.partial(ev.text)
                    is SttEvent.Final -> pending.confirmed(speakingNow = vad.isSpeaking)
                    else -> Unit
                }
                send(ev)
            }
            // Completing while we are still recording means the provider hung up on us.
            if (recording.isActive) IOException("$providerName ended the live session early") else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!recoverable(e)) throw e
            e
        }
        if (failure == null) return@channelFlow

        Log.w(TAG, "Live transcription dropped, recovering", failure)
        audio.cancel()
        val unconfirmed = pending.fail()
        val partial = pending.lastPartial
        if (!engine.supportsBatchRecovery(request.model)) {
            if (partial == null) throw failure
            ErrorLog.warning(SOURCE, "$providerName live connection dropped; kept the live transcript — ${failure.userMessage()}", failure)
            send(SttEvent.Notice("Live transcription dropped; kept the words recognised so far."))
            send(SttEvent.Final(partial))
            return@channelFlow
        }
        ErrorLog.warning(SOURCE, "$providerName live connection dropped; transcribing by upload — ${failure.userMessage()}", failure)
        send(SttEvent.Notice("Live transcription dropped; finished with standard transcription."))
        unconfirmed.forEach { uploads.send(it) }
        launch {
            recording.join()
            uploads.close()
        }
        var fallbackText = partial // belongs to the first unconfirmed utterance
        for (pcm in uploads) {
            if (Pcm.durationMs(pcm.size) < minUtteranceMs) continue
            send(SttEvent.Transcribing)
            val result = try {
                engine.transcribe(request, pcm, Pcm.SAMPLE_RATE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = fallbackText ?: throw e
                fallbackText = null
                ErrorLog.warning(SOURCE, "Upload transcription failed too; kept the live transcript — ${e.userMessage()}", e)
                send(SttEvent.Final(text))
                continue
            }
            fallbackText = null
            if (result.text.isNotBlank() && !isLikelyNoise(result.text)) send(SttEvent.Final(result.text, result.language))
        }
    } finally {
        recording.cancel()
    }
}

/** A failure another transcription method could get past (not a missing or rejected key). */
private fun recoverable(e: Exception): Boolean = when (e) {
    is MissingKeyException -> false
    is ApiException -> e.code != 401 && e.code != 403
    else -> true
}

/** Whisper-family hallucinations on silence and other empty transcripts. */
internal fun isLikelyNoise(text: String): Boolean {
    val t = text.trim().lowercase().trim('.', '!', '?', ' ')
    return t.isEmpty() || t in setOf("you", "thank you", "thanks for watching", "thank you for watching", "bye", "[music]", "(music)", "[blank_audio]")
}

private const val TAG = "LiveTranscription"
private const val SOURCE = "Live transcription"

/**
 * Utterances the local speech detector has ended but the provider has not confirmed with a final
 * transcript yet, plus the provider's latest partial. Shared between the recording and the
 * provider-stream coroutines.
 */
internal class UnconfirmedSpeech(private val maxSegments: Int = 10) {
    private val segments = ArrayDeque<ShortArray>()
    private var failed = false

    /** The provider finalised the utterance in progress before the local detector ended it. */
    private var aheadOfDetector = false

    @Volatile
    var lastPartial: String? = null
        private set

    /** An utterance ended. Returns true when it must be uploaded instead (the live stream is gone). */
    @Synchronized
    fun ended(audio: ShortArray): Boolean {
        if (failed) return true
        if (aheadOfDetector) {
            aheadOfDetector = false
            return false
        }
        segments.addLast(audio)
        while (segments.size > maxSegments) segments.removeFirst()
        return false
    }

    @Synchronized
    fun partial(text: String) {
        if (text.isNotBlank()) lastPartial = text
    }

    /**
     * The provider sent a final transcript: it covers the utterances that already ended, or, if
     * none is waiting, the one being spoken now.
     */
    @Synchronized
    fun confirmed(speakingNow: Boolean) {
        if (segments.isNotEmpty()) segments.clear() else if (speakingNow) aheadOfDetector = true
        lastPartial = null
    }

    /** The live stream died: returns the utterances it never confirmed. Later ones go to upload. */
    @Synchronized
    fun fail(): List<ShortArray> {
        failed = true
        return segments.toList().also { segments.clear() }
    }
}
