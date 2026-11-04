package email.sshah.kalibabel.audio

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight energy-based voice activity detector with an adaptive noise floor and hysteresis.
 *
 * It is used to (1) end an utterance as soon as the speaker pauses, which is the single biggest
 * latency win for batch STT, (2) split continuous speech into sentences for interpreter mode and
 * (3) drive the input level meter. A short pre-roll keeps the first syllable that triggered it.
 */
class Vad(
    private val sampleRate: Int = Pcm.SAMPLE_RATE,
    private val frameMs: Int = 20,
    silenceMs: Int = 700,
    private val minSpeechMs: Int = 120,
    private val preRollMs: Int = 300,
    private val maxUtteranceMs: Int = 30_000,
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

    private var noiseFloor = 120.0
    private var speaking = false
    private var aboveFrames = 0
    private var silenceFrames = 0
    private val preRoll = ArrayDeque<ShortArray>()
    private val utterance = ArrayList<ShortArray>()
    private var utteranceSamples = 0

    val isSpeaking: Boolean get() = speaking

    fun process(frame: ShortArray): Result {
        val rms = rms(frame)
        val level = levelOf(rms)
        val onThreshold = max(noiseFloor * 3.0, MIN_THRESHOLD)
        val offThreshold = onThreshold * 0.6

        if (!speaking) {
            // Track the noise floor only while nobody is talking.
            noiseFloor = if (rms < noiseFloor) noiseFloor * 0.9 + rms * 0.1 else noiseFloor * 0.995 + rms * 0.005
            noiseFloor = noiseFloor.coerceIn(30.0, 4000.0)

            preRoll.addLast(frame)
            val maxPreRoll = max(1, preRollMs / frameMs)
            while (preRoll.size > maxPreRoll) preRoll.removeFirst()

            aboveFrames = if (rms > onThreshold) aboveFrames + 1 else 0
            if (aboveFrames * frameMs >= minSpeechMs) {
                speaking = true
                silenceFrames = 0
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
        silenceFrames = if (rms < offThreshold) silenceFrames + 1 else 0

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
        utterance.clear()
        utteranceSamples = 0
    }

    companion object {
        private const val MIN_THRESHOLD = 350.0

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
