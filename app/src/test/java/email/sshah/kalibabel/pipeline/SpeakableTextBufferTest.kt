package email.sshah.kalibabel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakableTextBufferTest {
    private fun stream(deltas: List<String>): List<String> {
        val b = SpeakableTextBuffer()
        val out = deltas.mapNotNull { b.push(it) }.toMutableList()
        b.flush()?.let { out += it }
        return out
    }

    @Test
    fun neverSplitsWordsAndAlwaysEndsWithASpace() {
        val out = stream(listOf("Ho", "la, ¿qué", " tal? Muy", " bi", "en."))
        assertTrue(out.all { it.endsWith(" ") && !it.endsWith("  ") })
        assertEquals("Hola, ¿qué tal? Muy bien.", out.joinToString("").trim())
        assertEquals("a word is only sent once the following space confirms it is complete", "Hola, ", out.first())
    }

    @Test
    fun releasesUnspacedScriptsAtPunctuationOrLength() {
        val out = stream(listOf("今天天气", "很好，", "我们去公园散步吧"))
        assertEquals("今天天气很好， ", out.first())
        assertEquals("今天天气很好，我们去公园散步吧", out.joinToString("") { it.trim() })
    }

    @Test
    fun blankInputProducesNothing() {
        val b = SpeakableTextBuffer()
        assertNull(b.push("   "))
        assertNull(b.flush())
    }
}
