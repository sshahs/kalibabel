package email.sshah.kalibabel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LangTagParserTest {
    @Test
    fun stripsTagSplitAcrossDeltas() {
        val p = LangTagParser()
        val out = listOf("[", "es", "-ES] Hola", ", ¿qué tal?").joinToString("") { p.push(it) } + p.flush()
        assertEquals("es-ES", p.tag)
        assertEquals("Hola, ¿qué tal?", out)
    }

    @Test
    fun passesThroughUntaggedText() {
        val p = LangTagParser()
        val out = p.push("Hello") + p.push(" world") + p.flush()
        assertNull(p.tag)
        assertEquals("Hello world", out)
    }

    @Test
    fun givesUpOnLongBracketedText() {
        val p = LangTagParser()
        val out = p.push("[this is not a language tag at all] ok") + p.flush()
        assertNull(p.tag)
        assertEquals("[this is not a language tag at all] ok", out)
    }
}
