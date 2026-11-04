package email.sshah.kalibabel.pipeline

import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.providers.TranslationDelta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A translation started from a *stable partial* transcript, before the recognizer committed the
 * final one. Its output is buffered in [deltas] and only used if the final transcript matches.
 */
class Speculation(
    val text: String,
    val source: Language?,
    val target: Language?,
    val provider: ProviderId,
    val model: String,
    val isLlm: Boolean,
    val deltas: ReceiveChannel<TranslationDelta>,
    private val job: Job,
) {
    val key: String = SpeculativeTranslator.normalize(text)

    fun cancel() {
        job.cancel()
        deltas.cancel()
    }
}

/**
 * Starts translating when the live partial transcript has stopped changing for [stableMs] — the
 * speaker has paused, but the recognizer has not yet confirmed the end of the utterance (that takes
 * the configured end-of-speech silence). When the final transcript arrives and matches, the
 * translation has a head start of several hundred milliseconds; otherwise it is discarded.
 *
 * Spend is bounded: at most [maxAttempts] speculative requests per utterance, and only for partials
 * of at least [minChars] characters.
 */
class SpeculativeTranslator(
    private val scope: CoroutineScope,
    private val stableMs: Long,
    private val maxAttempts: Int = 3,
    private val minChars: Int = 6,
    private val start: suspend (text: String) -> Speculation?,
) {
    private var current: Speculation? = null
    private var pending: Job? = null
    private var attempts = 0

    /** Number of speculative requests started for the current utterance (for tests/diagnostics). */
    val attemptsThisUtterance: Int get() = attempts

    fun onPartial(text: String) {
        pending?.cancel()
        val key = normalize(text)
        current?.let {
            if (it.key == key) return // already translating exactly this
            it.cancel() // the speaker kept going: that guess is stale
            current = null
        }
        if (key.length < minChars || attempts >= maxAttempts) return
        pending = scope.launch {
            delay(stableMs)
            attempts++
            current = start(text)
        }
    }

    /**
     * Hands over the speculation if it was made for exactly [finalText] (the caller then owns it),
     * otherwise cancels it. Resets the per-utterance budget either way.
     */
    fun take(finalText: String): Speculation? {
        pending?.cancel()
        pending = null
        val spec = current
        current = null
        attempts = 0
        if (spec != null && spec.key == normalize(finalText)) return spec
        spec?.cancel()
        return null
    }

    fun cancelAll() {
        pending?.cancel()
        pending = null
        current?.cancel()
        current = null
        attempts = 0
    }

    companion object {
        private val MARKS = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)

        /**
         * Case-, punctuation- and spacing-insensitive form: finals often add punctuation/casing.
         * Combining marks (Gujarati/Devanagari vowel signs, viramas, accents) are kept — they change
         * the word, so "કેમ" and "કૈમ" must not compare equal.
         */
        fun normalize(text: String): String = text.lowercase()
            .map { if (it.isLetterOrDigit() || it.isWhitespace() || it.category in MARKS) it else ' ' }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }
}
