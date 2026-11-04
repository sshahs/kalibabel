package email.sshah.kalibabel.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Decides, frame by frame, whether a frame contains speech. Implementations keep their own noise model. */
interface FrameClassifier {
    /**
     * [speaking] is the detector's current state so classifiers can apply hysteresis:
     * staying in speech is easier than entering it, which avoids chattering at word boundaries.
     */
    fun isSpeech(frame: ShortArray, speaking: Boolean): Boolean
}

/**
 * Voice activity detector: turns a stream of 20 ms frames into utterances.
 *
 * It is used to (1) end an utterance as soon as the speaker pauses, which is the single biggest
 * latency win for batch STT and for auto-stop with streaming STT, (2) split continuous speech into
 * sentences for interpreter mode and (3) drive the input level meter. A short pre-roll keeps the
 * first syllable that triggered it. The per-frame decision comes from a [FrameClassifier]:
 * [SpectralClassifier] (noise-robust, default) or [EnergyClassifier] (basic).
 */
class Vad(
    private val sampleRate: Int = Pcm.SAMPLE_RATE,
    private val frameMs: Int = 20,
    silenceMs: Int = 700,
    private val minSpeechMs: Int = 120,
    private val preRollMs: Int = 300,
    private val maxUtteranceMs: Int = 30_000,
    private val classifier: FrameClassifier = EnergyClassifier(),
) {
    sealed interface Event {
        data object None : Event

        data object SpeechStart : Event

        /** [audio] is the whole utterance including pre-roll. */
        class SpeechEnd(val audio: ShortArray) : Event
    }

    data class Result(val event: Event, val level: Float, val speaking: Boolean)

    var silenceMs: Int = silenceMs
        set(value) {
            field = value.coerceIn(200, 5000)
        }

    private var speaking = false
    private var aboveFrames = 0
    private var silenceFrames = 0
    private var speechRun = 0
    private val preRoll = ArrayDeque<ShortArray>()
    private val utterance = ArrayList<ShortArray>()
    private var utteranceSamples = 0

    val isSpeaking: Boolean get() = speaking

    fun process(frame: ShortArray): Result {
        val level = levelOf(rms(frame))
        val speech = classifier.isSpeech(frame, speaking)

        if (!speaking) {
            preRoll.addLast(frame)
            val maxPreRoll = max(1, preRollMs / frameMs)
            while (preRoll.size > maxPreRoll) preRoll.removeFirst()

            aboveFrames = if (speech) aboveFrames + 1 else 0
            if (aboveFrames * frameMs >= minSpeechMs) {
                speaking = true
                silenceFrames = 0
                speechRun = aboveFrames
                utterance.clear()
                utterance.addAll(preRoll)
                utteranceSamples = utterance.sumOf { it.size }
                preRoll.clear()
                return Result(Event.SpeechStart, level, true)
            }
            return Result(Event.None, level, false)
        }

        utterance.add(frame)
        utteranceSamples += frame.size
        // A single speech-like frame in a pause is usually a noise blip: only two consecutive
        // speech frames reset the end-of-speech countdown, so pauses are recognised on time.
        if (speech) {
            speechRun++
            if (speechRun >= 2) silenceFrames = 0
        } else {
            speechRun = 0
            silenceFrames++
        }

        val tooLong = Pcm.durationMs(utteranceSamples, sampleRate) >= maxUtteranceMs
        if (silenceFrames * frameMs >= silenceMs || tooLong) {
            // Keep ~150 ms of trailing silence; recognizers like a little context after the last word.
            val keepTail = 150 / frameMs
            val drop = max(0, silenceFrames - keepTail)
            val frames = utterance.subList(0, max(0, utterance.size - drop))
            val audio = Pcm.concat(frames)
            reset()
            return Result(Event.SpeechEnd(audio), level, false)
        }
        return Result(Event.None, level, true)
    }

    /** Ends the current utterance immediately (user tapped stop). Returns null if nobody spoke. */
    fun flush(): ShortArray? {
        if (!speaking || utterance.isEmpty()) {
            reset()
            return null
        }
        val audio = Pcm.concat(utterance)
        reset()
        return audio
    }

    private fun reset() {
        speaking = false
        aboveFrames = 0
        silenceFrames = 0
        speechRun = 0
        utterance.clear()
        utteranceSamples = 0
    }

    companion object {
        fun rms(frame: ShortArray): Double {
            if (frame.isEmpty()) return 0.0
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            return sqrt(sum / frame.size)
        }

        /** Maps RMS to a perceptual 0..1 meter value (≈ -60 dBFS … 0 dBFS). */
        fun levelOf(rms: Double): Float {
            if (rms <= 1.0) return 0f
            val db = 20 * ln(rms / 32768.0) / ln(10.0)
            return min(1.0, max(0.0, (db + 60.0) / 60.0)).toFloat()
        }
    }
}

/**
 * Basic detector: RMS energy against an adaptive noise floor. Cheap and fine in quiet rooms, but
 * loud steady noise (fans, traffic, cafés) reads as "speech" — the utterance then never ends.
 */
class EnergyClassifier : FrameClassifier {
    private var noiseFloor = 120.0

    override fun isSpeech(frame: ShortArray, speaking: Boolean): Boolean {
        val rms = Vad.rms(frame)
        val onThreshold = max(noiseFloor * 3.0, MIN_THRESHOLD)
        if (!speaking) {
            // Track the noise floor only while nobody is talking.
            noiseFloor = if (rms < noiseFloor) noiseFloor * 0.9 + rms * 0.1 else noiseFloor * 0.995 + rms * 0.005
            noiseFloor = noiseFloor.coerceIn(30.0, 4000.0)
            return rms > onThreshold
        }
        return rms >= onThreshold * 0.6
    }

    private companion object {
        const val MIN_THRESHOLD = 350.0
    }
}

/**
 * Noise-robust detector. Each frame is windowed and transformed (512-point FFT); only the speech
 * band (250–3500 Hz) is considered, so low rumble (traffic, engines, wind) and hiss above the voice
 * band are ignored. The noise spectrum is tracked per frequency bin with minimum statistics —
 * the minimum of the smoothed spectrum over the last ~3 s, which follows changing background noise
 * even while someone is talking, because speech always has short gaps. A frame is speech when its
 * band energy is well above that noise estimate *and* the spectrum is peaky (low spectral flatness,
 * as voiced speech has harmonics) — broadband bursts such as clatter are flat and are rejected.
 */
class SpectralClassifier(private val sampleRate: Int = Pcm.SAMPLE_RATE) : FrameClassifier {
    private val fftSize = 512
    private val bins = fftSize / 2 + 1
    private val lo = max(1, 250 * fftSize / sampleRate)
    private val hi = min(bins - 1, 3500 * fftSize / sampleRate)

    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val power = DoubleArray(bins)
    private val smoothed = DoubleArray(bins)
    private val subMin = DoubleArray(bins) { Double.MAX_VALUE }
    private val history = ArrayDeque<DoubleArray>()
    private var framesInSub = 0
    private var frames = 0
    private var window = DoubleArray(0)

    /** Last computed features, exposed for tests and diagnostics. */
    var lastSnrDb = 0.0
        private set
    var lastFlatnessDb = 0.0
        private set
    var lastPeakFraction = 0.0
        private set

    override fun isSpeech(frame: ShortArray, speaking: Boolean): Boolean {
        spectrum(frame)
        updateNoise()
        frames++

        // Work on the whitened spectrum W = P / N: pure noise of any colour has W ≈ 1 everywhere.
        var sumW = 0.0
        var logSumW = 0.0
        var peaks = 0
        for (k in lo..hi) {
            val w = (power[k] + EPS) / (noise(k) + EPS)
            sumW += w
            logSumW += ln(w)
            if (w > PEAK_RATIO) peaks++
        }
        val count = (hi - lo + 1).toDouble()
        val meanW = sumW / count
        lastSnrDb = 10 * log10(meanW)
        lastFlatnessDb = 10 * log10(exp(logSumW / count) / meanW)
        lastPeakFraction = peaks / count

        if (Vad.rms(frame) < DIGITAL_SILENCE || frames <= WARMUP_FRAMES) return false
        return if (speaking) {
            lastSnrDb > SNR_STAY_DB || lastPeakFraction > PEAKS_STAY
        } else {
            (lastSnrDb > SNR_ENTER_DB && lastFlatnessDb < FLATNESS_MAX_DB) ||
                lastPeakFraction > PEAKS_ENTER ||
                lastSnrDb > SNR_CERTAIN_DB
        }
    }

    private fun noise(k: Int): Double {
        // Warm-up: the running mean of the first frames is a far better noise estimate than the
        // minimum of a handful of raw periodograms (which has near-zero bins and inflates SNR).
        if (frames <= WARMUP_FRAMES) return smoothed[k]
        var m = subMin[k]
        for (h in history) m = min(m, h[k])
        return m * NOISE_BIAS
    }

    private fun updateNoise() {
        if (frames < WARMUP_FRAMES) {
            for (k in 0 until bins) smoothed[k] = (smoothed[k] * frames + power[k]) / (frames + 1)
            if (frames == WARMUP_FRAMES - 1) {
                // Seed minimum statistics with the warm-up mean (already a mean, so undo the bias).
                for (k in 0 until bins) subMin[k] = smoothed[k] / NOISE_BIAS
            }
            return
        }
        for (k in 0 until bins) {
            smoothed[k] = SMOOTHING * smoothed[k] + (1 - SMOOTHING) * power[k]
            subMin[k] = min(subMin[k], smoothed[k])
        }
        framesInSub++
        if (framesInSub >= SUB_WINDOW_FRAMES) {
            history.addLast(subMin.copyOf())
            while (history.size > SUB_WINDOWS) history.removeFirst()
            subMin.fill(Double.MAX_VALUE)
            for (k in 0 until bins) subMin[k] = smoothed[k]
            framesInSub = 0
        }
    }

    private fun spectrum(frame: ShortArray) {
        val n = min(frame.size, fftSize)
        if (window.size != n) window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1).coerceAtLeast(1)) }
        for (i in 0 until fftSize) {
            re[i] = if (i < n) frame[i] / 32768.0 * window[i] else 0.0
            im[i] = 0.0
        }
        fft(re, im)
        for (k in 0 until bins) power[k] = re[k] * re[k] + im[k] * im[k]
    }

    companion object {
        private const val EPS = 1e-12
        private const val SMOOTHING = 0.7
        private const val SUB_WINDOW_FRAMES = 25 // 0.5 s
        private const val SUB_WINDOWS = 6 // ~3 s of minimum-statistics memory
        // The minimum of a smoothed periodogram under-estimates the mean noise power; 3.2 calibrates
        // pure noise to ~0 dB SNR (p99 ≈ 1.7 dB), safely below SNR_STAY_DB.
        private const val NOISE_BIAS = 3.2
        private const val WARMUP_FRAMES = 10 // 200 ms
        private const val DIGITAL_SILENCE = 8.0
        private const val SNR_ENTER_DB = 6.0
        private const val SNR_CERTAIN_DB = 12.0
        private const val SNR_STAY_DB = 4.0
        private const val FLATNESS_MAX_DB = -4.0
        private const val PEAK_RATIO = 4.0 // a bin 6 dB above its noise estimate
        // Noise alone: peak fraction p50 ≈ 0.03, p99 ≈ 0.11. Voice at 0 dB SNR: p50 ≈ 0.18.
        private const val PEAKS_ENTER = 0.13
        private const val PEAKS_STAY = 0.12

        /** In-place iterative radix-2 FFT; [re]/[im] length must be a power of two. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j or bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang)
                val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k + len / 2] = re[i + k] - ar
                        im[i + k + len / 2] = im[i + k] - ai
                        re[i + k] += ar
                        im[i + k] += ai
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
