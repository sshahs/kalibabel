package email.sshah.kalibabel.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VadTest {
    private val frame = 320 // 20 ms @ 16 kHz

    private fun silence() = ShortArray(frame) { ((it % 7) - 3).toShort() }

    private fun tone(amplitude: Int = 8000) = ShortArray(frame) { (amplitude * sin(2 * PI * 220 * it / 16000.0)).toInt().toShort() }

    @Test
    fun detectsUtteranceAndEndsAfterSilence() {
        val vad = Vad(silenceMs = 400)
        val events = mutableListOf<Vad.Event>()
        repeat(25) { events += vad.process(silence()).event }
        repeat(30) { events += vad.process(tone()).event }
        repeat(30) { events += vad.process(silence()).event }

        assertEquals(1, events.count { it is Vad.Event.SpeechStart })
        val end = events.filterIsInstance<Vad.Event.SpeechEnd>()
        assertEquals(1, end.size)
        // ~600 ms speech + pre-roll + a short tail, but not the full 600 ms of trailing silence.
        val ms = Pcm.durationMs(end.single().audio.size)
        assertTrue("utterance was $ms ms", ms in 600..1200)
    }

    @Test
    fun ignoresShortClicks() {
        val vad = Vad(silenceMs = 400)
        repeat(20) { vad.process(silence()) }
        repeat(2) { vad.process(tone()) } // 40 ms click < minSpeechMs
        repeat(20) { assertTrue(vad.process(silence()).event is Vad.Event.None) }
        assertNull(vad.flush())
    }

    @Test
    fun flushReturnsOngoingSpeech() {
        val vad = Vad(silenceMs = 800)
        repeat(10) { vad.process(silence()) }
        repeat(20) { vad.process(tone()) }
        assertTrue(vad.isSpeaking)
        assertNotNull(vad.flush())
    }

    @Test
    fun levelIsNormalised() {
        assertEquals(0f, Vad.levelOf(0.0))
        assertTrue(Vad.levelOf(20000.0) > 0.9f)
    }
}
