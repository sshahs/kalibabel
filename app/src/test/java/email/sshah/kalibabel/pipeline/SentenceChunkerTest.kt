package email.sshah.kalibabel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceChunkerTest {
    private fun chunk(deltas: List<String>, early: Boolean = false): List<String> {
        val c = SentenceChunker(early = early)
        val out = deltas.flatMap { c.push(it) }.toMutableList()
        c.flush()?.let { out += it }
        return out
    }

    private fun charByChar(text: String, early: Boolean = false) = chunk(text.map { it.toString() }, early)

    @Test
    fun splitsOnSentenceBoundariesWhileStreaming() {
        val out = charByChar("Good morning, how are you today? I am looking for the train station.")
        assertEquals(listOf("Good morning, how are you today?", "I am looking for the train station."), out)
    }

    @Test
    fun doesNotSplitDecimalsOrAbbreviations() {
        val out = charByChar("It costs 3.50 euros, Mr. Smith said. That is fine with me.")
        assertEquals(listOf("It costs 3.50 euros, Mr. Smith said.", "That is fine with me."), out)
    }

    @Test
    fun mergesVeryShortSentences() {
        val out = charByChar("Yes. I think that sounds wonderful, thank you.")
        assertEquals(listOf("Yes. I think that sounds wonderful, thank you."), out)
    }

    @Test
    fun earlyModeCutsFirstClause() {
        val out = charByChar("When you arrive at the station, take the second exit on the left.", early = true)
        assertEquals("When you arrive at the station,", out.first())
        assertEquals(2, out.size)
    }

    @Test
    fun handlesCjkTerminators() {
        val out = chunk(listOf("今天天气很好。", "我们去公园散步吧！"))
        assertEquals(listOf("今天天气很好。", "我们去公园散步吧！"), out)
    }

    @Test
    fun cutsVeryLongRunsAtWordBoundary() {
        val long = (1..60).joinToString(" ") { "word$it" }
        val out = charByChar(long)
        assertTrue(out.size >= 2)
        assertTrue(out.all { it.length <= 230 })
        assertEquals(long, out.joinToString(" "))
    }

    @Test
    fun keepsClosingQuotesWithSentence() {
        val out = charByChar("He told me \"wait here.\" Then he walked away quickly.")
        assertEquals("He told me \"wait here.\"", out.first())
    }
}
