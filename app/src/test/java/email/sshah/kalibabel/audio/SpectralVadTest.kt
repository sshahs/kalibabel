package email.sshah.kalibabel.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic-signal tests for the noise-robust detector. "Speech" is a voiced harmonic signal with
 * vowel-like formants, pitch vibrato and syllable-rate amplitude modulation — a reasonable stand-in
 * for a voice when the question is "speech vs. noise".
 */
class SpectralVadTest {
    private val sr = 16_000
    private val frame = 320

    private fun whiteNoise(ms: Int, rms: Double, seed: Long = 1): DoubleArray {
        val r = Random(seed)
        return DoubleArray(sr * ms / 1000) { r.nextGaussian() * rms }
    }

    private fun rumble(ms: Int, rms: Double, seed: Long = 2): DoubleArray {
        val r = Random(seed)
        var y = 0.0
        val raw = DoubleArray(sr * ms / 1000) { y = 0.995 * y + r.nextGaussian(); y }
        return scale(raw, rms)
    }

    private fun voiced(ms: Int, rms: Double): DoubleArray {
        val n = sr * ms / 1000
        val out = DoubleArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f0 = 140.0 * (1 + 0.03 * sin(2 * PI * 5 * t))
            phase += 2 * PI * f0 / sr
            var v = 0.0
            var h = 1
            while (h * 140.0 < 3800) {
                v += formant(h * 140.0) * sin(h * phase)
                h++
            }
            out[i] = v * (0.55 + 0.45 * sin(2 * PI * 4 * t))
        }
        return scale(out, rms)
    }

    private fun formant(f: Double): Double {
        fun g(c: Double, w: Double) = exp(-((f - c) * (f - c)) / (2 * w * w))
        return 0.15 + g(700.0, 150.0) + 0.7 * g(1200.0, 200.0) + 0.4 * g(2600.0, 300.0)
    }

    private fun scale(x: DoubleArray, rms: Double): DoubleArray {
        val cur = sqrt(x.sumOf { it * it } / x.size)
        return DoubleArray(x.size) { x[it] / cur * rms }
    }

    private fun mix(vararg parts: DoubleArray): DoubleArray {
        val n = parts.maxOf { it.size }
        return DoubleArray(n) { i -> parts.sumOf { p -> if (i < p.size) p[i] else 0.0 } }
    }

    private fun concat(vararg parts: DoubleArray) = parts.reduce { a, b -> a + b }

    private fun frames(x: DoubleArray): List<ShortArray> = (0 until x.size / frame).map { f ->
        ShortArray(frame) { (x[f * frame + it]).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    /** Runs a detector and returns (frameIndex, event) for every non-None event. */
    private fun run(vad: Vad, x: DoubleArray): List<Pair<Int, Vad.Event>> =
        frames(x).mapIndexedNotNull { i, f -> vad.process(f).event.takeIf { it != Vad.Event.None }?.let { i to it } }

    private fun spectral(silenceMs: Int = 500) = Vad(silenceMs = silenceMs, classifier = SpectralClassifier())

    private fun energy(silenceMs: Int = 500) = Vad(silenceMs = silenceMs, classifier = EnergyClassifier())

    @Test
    fun loudSteadyNoiseTrapsTheEnergyDetectorButNotTheSpectralOne() {
        val noise = whiteNoise(4000, 2000.0)
        val energyEvents = run(energy(), noise)
        assertTrue("energy VAD should mistake loud noise for speech", energyEvents.any { it.second == Vad.Event.SpeechStart })
        assertTrue("…and never see the end of it", energyEvents.none { it.second is Vad.Event.SpeechEnd })

        assertEquals("spectral VAD must stay quiet in steady noise", emptyList<Pair<Int, Vad.Event>>(), run(spectral(), noise))
    }

    @Test
    fun lowFrequencyRumbleIsIgnored() {
        assertEquals(emptyList<Pair<Int, Vad.Event>>(), run(spectral(), rumble(4000, 3000.0)))
    }

    @Test
    fun detectsSpeechInNoiseAndEndsPromptlyAfterIt() {
        val noiseRms = 700.0
        val signal = concat(
            whiteNoise(1500, noiseRms, seed = 3),
            mix(voiced(1200, 1400.0), whiteNoise(1200, noiseRms, seed = 4)),
            whiteNoise(2000, noiseRms, seed = 5),
        )
        val events = run(spectral(silenceMs = 500), signal)
        val starts = events.filter { it.second == Vad.Event.SpeechStart }
        val ends = events.filter { it.second is Vad.Event.SpeechEnd }
        assertEquals("exactly one utterance: $events", 1, starts.size)
        assertEquals("exactly one end: $events", 1, ends.size)

        val onsetFrame = 1500 / 20
        val offsetFrame = (1500 + 1200) / 20
        val startDelayMs = (starts.single().first - onsetFrame) * 20
        val endDelayMs = (ends.single().first - offsetFrame) * 20
        assertTrue("speech start detected within 300 ms (was $startDelayMs ms)", startDelayMs in 0..300)
        assertTrue("speech end detected within silenceMs + 400 ms (was $endDelayMs ms)", endDelayMs in 0..900)
    }

    @Test
    fun detectsSpeechAsLoudAsTheBackgroundNoise() {
        // 0 dB overall SNR (busy café). The voice still stands out in its band and spectral shape.
        val noiseRms = 1500.0
        val signal = concat(
            whiteNoise(1500, noiseRms, seed = 11),
            mix(voiced(1500, 1500.0), whiteNoise(1500, noiseRms, seed = 12)),
            whiteNoise(2000, noiseRms, seed = 13),
        )
        val events = run(spectral(silenceMs = 600), signal)
        assertEquals("one utterance: $events", 1, events.count { it.second == Vad.Event.SpeechStart })
        val end = events.single { it.second is Vad.Event.SpeechEnd }.first
        val endDelayMs = (end - 3000 / 20) * 20
        assertTrue("ends within silenceMs + 400 ms (was $endDelayMs ms)", endDelayMs in 0..1000)
    }

    @Test
    fun detectsSpeechAfterQuietStart() {
        val signal = concat(whiteNoise(300, 30.0, seed = 6), voiced(1000, 3000.0), whiteNoise(1500, 30.0, seed = 7))
        val events = run(spectral(silenceMs = 500), signal)
        assertEquals(1, events.count { it.second == Vad.Event.SpeechStart })
        assertEquals(1, events.count { it.second is Vad.Event.SpeechEnd })
    }

    @Test
    fun fftFindsTheRightBin() {
        val n = 512
        val re = DoubleArray(n) { sin(2 * PI * 32 * it / n) }
        val im = DoubleArray(n)
        SpectralClassifier.fft(re, im)
        val mags = (0..n / 2).map { hypot(re[it], im[it]) }
        assertEquals(32, mags.indices.maxBy { mags[it] })
    }
}
