package email.sshah.kalibabel.pipeline

/**
 * Splits a streaming translation into speakable chunks so TTS can start on the first clause
 * while the LLM is still generating the rest — the main trick behind mouth-to-ear latency.
 *
 * - Sentence terminators (Latin, CJK, Arabic, Devanagari…) end a chunk once followed by space
 *   or when the terminator is a full-width CJK mark.
 * - With [early] enabled, the *first* chunk may also end at a clause boundary (comma etc.) once
 *   it is long enough, so speech starts even sooner.
 * - Very long runs without punctuation are cut at a word boundary.
 */
class SentenceChunker(
    private val early: Boolean = true,
    private val firstClauseMin: Int = 28,
    private val minChunk: Int = 12,
    private val maxChunk: Int = 220,
) {
    private val buffer = StringBuilder()
    private var emittedAny = false

    fun push(delta: String): List<String> {
        buffer.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut() ?: break
            val chunk = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (chunk.isNotEmpty()) {
                out += chunk
                emittedAny = true
            }
        }
        return out
    }

    /** Returns whatever is left once the stream has finished. */
    fun flush(): String? {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        return rest.ifEmpty { null }
    }

    private fun findCut(): Int? {
        val s = buffer
        var lastSpace = -1
        for (i in s.indices) {
            val c = s[i]
            if (c.isWhitespace()) lastSpace = i
            if (c in FULL_WIDTH_TERMINATORS && i + 1 >= minChunk.coerceAtMost(4)) {
                return closingQuotesEnd(i + 1)
            }
            if (c in TERMINATORS || c == '\n') {
                val next = s.getOrNull(i + 1) ?: return null // wait to see what follows
                val end = closingQuotesEnd(i + 1)
                val follow = s.getOrNull(end) ?: return null
                if ((follow.isWhitespace() || c == '\n') && end >= minChunk && !isAbbreviation(i)) return end
                if (next.isDigit()) continue // 3.14, 1,000
            }
            if (early && !emittedAny && c in CLAUSE_MARKS && i + 1 >= firstClauseMin) {
                val follow = s.getOrNull(i + 1) ?: return null
                if (follow.isWhitespace()) return i + 1
            }
            if (c in FULL_WIDTH_CLAUSE && early && !emittedAny && i + 1 >= firstClauseMin / 2) return i + 1
        }
        if (s.length > maxChunk && lastSpace > minChunk) return lastSpace
        return null
    }

    private fun closingQuotesEnd(from: Int): Int {
        var j = from
        while (j < buffer.length && buffer[j] in CLOSERS) j++
        return j
    }

    private fun isAbbreviation(dotIndex: Int): Boolean {
        if (buffer[dotIndex] != '.') return false
        var start = dotIndex
        while (start > 0 && buffer[start - 1].isLetter()) start--
        val word = buffer.substring(start, dotIndex).lowercase()
        return word in ABBREVIATIONS || (word.length == 1 && word[0].isLetter())
    }

    private companion object {
        val TERMINATORS = setOf('.', '!', '?', '…', '؟', '।', '॥', ';')
        val FULL_WIDTH_TERMINATORS = setOf('。', '！', '？', '｡')
        val CLAUSE_MARKS = setOf(',', ':', '–', '—', '،')
        val FULL_WIDTH_CLAUSE = setOf('，', '、', '：')
        val CLOSERS = setOf('"', '\'', '”', '’', '»', ')', '」', '』', '】')
        val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "sra", "srta", "hr", "fr", "nr", "z.b", "bzw", "usw")
    }
}

/**
 * Groups streaming text deltas into word-complete pieces for text-streaming TTS. A piece always ends
 * at a word boundary (and with a single space, as ElevenLabs expects), so a word is never split
 * across messages; scripts without spaces (CJK, Thai…) are released at punctuation or by length.
 */
class SpeakableTextBuffer(private val maxUnspacedChars: Int = 24) {
    private val buffer = StringBuilder()

    fun push(delta: String): String? {
        buffer.append(delta)
        val s = buffer
        val lastSpace = s.indexOfLast { it.isWhitespace() }
        val lastStop = s.indexOfLast { it in STOPS }
        val cut = when {
            lastSpace > 0 -> lastSpace + 1
            lastStop >= 0 -> lastStop + 1
            s.length >= maxUnspacedChars && s.none { it.isWhitespace() } && s.any { it.code > 0x2E80 } -> s.length
            else -> return null
        }
        val piece = s.substring(0, cut)
        s.delete(0, cut)
        return normalize(piece)
    }

    fun flush(): String? {
        val piece = buffer.toString()
        buffer.setLength(0)
        return if (piece.isBlank()) null else normalize(piece)
    }

    private fun normalize(piece: String): String? {
        val trimmed = piece.trimEnd()
        return if (trimmed.isBlank()) null else "$trimmed "
    }

    private companion object {
        val STOPS = setOf('。', '，', '、', '！', '？', '；', '：', '．', '…')
    }
}

/**
 * In bidirectional mode the LLM starts its reply with `[xx]` naming the language it translated
 * into. This strips that tag from the stream and reports it.
 */
class LangTagParser {
    private val head = StringBuilder()
    private var done = false
    var tag: String? = null
        private set

    /** Returns the text to pass through for [delta] (may be empty while the tag is buffering). */
    fun push(delta: String): String {
        if (done) return delta
        head.append(delta)
        val s = head.toString()
        val trimmed = s.trimStart()
        if (trimmed.isEmpty()) return ""
        if (trimmed[0] != '[') {
            done = true
            return s
        }
        val close = trimmed.indexOf(']')
        if (close < 0) {
            if (trimmed.length > 16) {
                done = true
                return s
            }
            return ""
        }
        done = true
        val candidate = trimmed.substring(1, close).trim()
        if (!TAG_REGEX.matches(candidate)) return s
        tag = candidate
        return trimmed.substring(close + 1).trimStart()
    }

    fun flush(): String = if (done) "" else head.toString().also { done = true }

    private companion object {
        val TAG_REGEX = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    }
}

/**
 * The part of [full] that still has to be spoken after a voice stream broke off, given the
 * characters the provider reported as voiced (its whitespace may differ from ours). A word that
 * was cut in the middle is repeated from its start rather than skipped.
 */
fun unspokenRemainder(full: String, voiced: String): String {
    val said = voiced.filterNot { it.isWhitespace() }
    var i = 0
    var matched = 0
    while (i < full.length && matched < said.length) {
        val c = full[i]
        if (c.isWhitespace()) {
            i++
            continue
        }
        if (!c.equals(said[matched], ignoreCase = true)) break
        matched++
        i++
    }
    if (i in 1 until full.length && !full[i].isWhitespace() && !full[i - 1].isWhitespace()) {
        var end = i
        while (end < full.length && !full[end].isWhitespace()) end++
        if (full.substring(i, end).none { it.isLetterOrDigit() }) {
            i = end // only punctuation of a finished word is left
        } else {
            // Back to the start of the word; scripts without spaces stop at the last punctuation.
            while (i > 0 && !full[i - 1].isWhitespace() && full[i - 1] !in WORD_STOPS) i--
        }
    }
    return full.substring(i).trim()
}

private val WORD_STOPS = setOf('.', ',', ';', ':', '!', '?', '…', '。', '，', '、', '！', '？', '；', '：', '．', '।', '؟', '،')
