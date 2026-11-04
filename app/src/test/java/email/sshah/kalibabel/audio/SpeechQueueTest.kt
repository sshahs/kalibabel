package email.sshah.kalibabel.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Ordering and failure isolation of [SpeechQueue]; playback is faked. */
class SpeechQueueTest {
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })

    /** Reads PCM like [AudioPlayer]: a stream that failed mid-way throws out of play(). */
    private class FakeOutput : SpeechOutput {
        val played = CopyOnWriteArrayList<Byte>()

        override suspend fun play(item: SpeechItem, onFirstAudio: () -> Unit) {
            val pcm = item as SpeechItem.Pcm
            for (chunk in pcm.data) {
                onFirstAudio()
                played += chunk.toList()
            }
        }

        override suspend fun drain() = Unit

        override fun stop() = Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun streamDroppingMidReplyDoesNotKillTheQueue() = runBlocking {
        val output = FakeOutput()
        val queue = SpeechQueue(scope, output)
        val finished = CopyOnWriteArrayList<Long>()
        val secondDone = CompletableDeferred<Unit>()

        // Turn 1: audio starts, then the TTS socket drops (what crashed v0.1.4).
        val first = queue.reserve(1, onFinished = { finished += 1L })
        val broken = Channel<ByteArray>(Channel.UNLIMITED)
        broken.send(byteArrayOf(1, 2))
        broken.close(IOException("ElevenLabs: connection dropped by the server"))
        first.items.send(SpeechItem.Pcm(24_000, broken))
        first.close()

        // Turn 2 must still be spoken.
        val second = queue.reserve(2, onFinished = { finished += 2L; secondDone.complete(Unit) })
        val ok = Channel<ByteArray>(Channel.UNLIMITED)
        ok.send(byteArrayOf(3, 4))
        ok.close()
        second.items.send(SpeechItem.Pcm(24_000, ok))
        second.close()

        withTimeout(5_000) { secondDone.await() }
        assertEquals(listOf<Byte>(1, 2, 3, 4), output.played.toList())
        assertEquals(listOf(1L, 2L), finished.toList())
        assertTrue("no uncaught failure: $uncaught", uncaught.isEmpty())
    }
}
