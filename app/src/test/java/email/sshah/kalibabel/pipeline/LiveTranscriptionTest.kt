package email.sshah.kalibabel.pipeline

import email.sshah.kalibabel.audio.EnergyClassifier
import email.sshah.kalibabel.audio.Vad
import email.sshah.kalibabel.data.ListenMode
import email.sshah.kalibabel.net.ApiException
import email.sshah.kalibabel.providers.SttEngine
import email.sshah.kalibabel.providers.SttEvent
import email.sshah.kalibabel.providers.SttRequest
import email.sshah.kalibabel.providers.SttResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.math.PI
import kotlin.math.sin

class LiveTranscriptionTest {
    private val frameSize = 320 // 20 ms @ 16 kHz

    private fun silence() = ShortArray(frameSize) { ((it % 7) - 3).toShort() }

    private fun tone() = ShortArray(frameSize) { (8000 * sin(2 * PI * 220 * it / 16000.0)).toInt().toShort() }

    /** Real-time-ish microphone: one frame at a time, letting the provider keep up. */
    private fun mic(vararg parts: Pair<Int, Boolean>): Flow<ShortArray> = flow {
        for ((frames, speech) in parts) repeat(frames) {
            emit(if (speech) tone() else silence())
            yield()
        }
    }

    private val request = SttRequest(
        language = null, candidates = emptyList(), model = "live-model", keyterms = emptyList(),
        silenceMs = 400, continuous = false, awaitQuiet = {},
    )

    /**
     * Fake realtime recognizer: reads frames, sends a partial while speech is coming in, and
     * fails after [failAfterFrames]. With [finalAfterFrames] it first finalises the first utterance.
     */
    private open inner class FakeEngine(
        private val failAfterFrames: Int,
        private val finalAfterFrames: Int? = null,
        private val failure: Exception = IOException("Fake: connection dropped by the server"),
        private val batch: Boolean = true,
    ) : SttEngine {
        val uploads = mutableListOf<Int>()

        override fun supportsStreaming(model: String) = true

        override fun supportsBatch(model: String) = false

        override fun supportsBatchRecovery(model: String) = batch

        override fun stream(request: SttRequest, audio: ReceiveChannel<ShortArray>, sampleRate: Int): Flow<SttEvent> = channelFlow {
            var n = 0
            for (frame in audio) {
                n++
                if (n == 40) send(SttEvent.Partial("hello wor"))
                if (n == finalAfterFrames) send(SttEvent.Final("first utterance"))
                if (n == failAfterFrames) throw failure
            }
        }

        override suspend fun transcribe(request: SttRequest, pcm: ShortArray, sampleRate: Int): SttResult {
            uploads += pcm.size
            return SttResult("uploaded ${uploads.size}")
        }
    }

    private fun stream(engine: SttEngine, mode: ListenMode, mic: Flow<ShortArray>, stop: CompletableDeferred<Unit> = CompletableDeferred()) =
        resilientStream(engine, request, mic, Vad(silenceMs = 400, classifier = EnergyClassifier()), mode, stop, "Fake")

    private fun List<SttEvent>.finals() = filterIsInstance<SttEvent.Final>().map { it.text }

    @Test
    fun dropMidUtteranceIsTranscribedByUpload() = runBlocking {
        val engine = FakeEngine(failAfterFrames = 50)
        val events = withTimeout(10_000) { stream(engine, ListenMode.AUTO_STOP, mic(20 to false, 60 to true, 40 to false)).toList() }

        assertEquals(listOf("uploaded 1"), events.finals())
        assertEquals("the whole utterance is uploaded once, after it ended", 1, engine.uploads.size)
        assertTrue("~1.2 s of speech plus pre-roll", engine.uploads.single() >= 60 * frameSize)
        assertTrue(events.any { it is SttEvent.Notice })
        assertTrue(events.any { it is SttEvent.Transcribing })
    }

    @Test
    fun withoutUploadSupportTheLastPartialIsKept() = runBlocking {
        val engine = FakeEngine(failAfterFrames = 50, batch = false)
        val events = withTimeout(10_000) { stream(engine, ListenMode.AUTO_STOP, mic(20 to false, 60 to true, 40 to false)).toList() }
        assertEquals(listOf("hello wor"), events.finals())
        assertTrue(engine.uploads.isEmpty())
    }

    @Test
    fun rejectedKeyIsNotRetried() = runBlocking {
        val engine = FakeEngine(failAfterFrames = 50, failure = ApiException("Fake", 401, "invalid key"))
        try {
            withTimeout(10_000) { stream(engine, ListenMode.AUTO_STOP, mic(20 to false, 60 to true, 40 to false)).toList() }
            fail("expected the 401 to surface")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
        }
        assertTrue(engine.uploads.isEmpty())
    }

    @Test
    fun interpreterModeOnlyUploadsWhatWasNotConfirmed() = runBlocking {
        // Utterance 1 (frames 20–80) is finalised live; the stream dies during utterance 2.
        val stop = CompletableDeferred<Unit>()
        val engine = FakeEngine(failAfterFrames = 170, finalAfterFrames = 110)
        val mic = flow {
            mic(20 to false, 60 to true, 50 to false, 60 to true, 40 to false).collect { emit(it) }
            stop.complete(Unit)
            emit(silence())
        }
        val events = withTimeout(10_000) { stream(engine, ListenMode.CONTINUOUS, mic, stop).toList() }

        assertEquals(listOf("first utterance", "uploaded 1"), events.finals())
        assertEquals("only the second utterance is uploaded", 1, engine.uploads.size)
    }

    @Test
    fun sessionEndedEarlyByTheProviderIsRecovered() = runBlocking {
        val engine = object : FakeEngine(failAfterFrames = Int.MAX_VALUE) {
            override fun stream(request: SttRequest, audio: ReceiveChannel<ShortArray>, sampleRate: Int): Flow<SttEvent> =
                flow { repeat(30) { audio.receive() } } // hangs up "normally" mid-utterance
        }
        val events = withTimeout(10_000) { stream(engine, ListenMode.AUTO_STOP, mic(20 to false, 60 to true, 40 to false)).toList() }
        assertEquals(listOf("uploaded 1"), events.finals())
    }

    @Test
    fun unconfirmedSpeechBookkeeping() {
        val u = UnconfirmedSpeech()
        u.partial("hel")
        assertEquals(false, u.ended(ShortArray(1)))
        u.confirmed(speakingNow = false)
        assertEquals(null, u.lastPartial)
        // Provider finalises the utterance in progress before the local detector ends it.
        u.confirmed(speakingNow = true)
        assertEquals(false, u.ended(ShortArray(2)))
        assertEquals(false, u.ended(ShortArray(3)))
        assertEquals(listOf(3), u.fail().map { it.size })
        assertEquals("after a failure every utterance is uploaded", true, u.ended(ShortArray(4)))
    }
}
