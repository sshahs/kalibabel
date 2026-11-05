package email.sshah.kalibabel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechRecoveryTest {
    private val full = "Hola, ¿qué tal? Muy bien, gracias."

    @Test
    fun remainderStartsAfterTheLastVoicedWord() {
        assertEquals("¿qué tal? Muy bien, gracias.", unspokenRemainder(full, "Hola, "))
        assertEquals("Muy bien, gracias.", unspokenRemainder(full, "Hola, ¿qué tal? "))
    }

    @Test
    fun aWordCutInTheMiddleIsRepeated() {
        assertEquals("¿qué tal? Muy bien, gracias.", unspokenRemainder(full, "Hola, ¿qu"))
        assertEquals("bien, gracias.", unspokenRemainder(full, "Hola, ¿qué tal? Muy bi"))
    }

    @Test
    fun punctuationLeftOfAFinishedWordIsNotRepeated() {
        assertEquals("Muy bien, gracias.", unspokenRemainder(full, "Hola, ¿qué tal"))
    }

    @Test
    fun whitespaceAndCaseDifferencesAreIgnored() {
        assertEquals("gracias.", unspokenRemainder(full, "hola,¿qué tal?muy bien,"))
    }

    @Test
    fun everythingVoicedLeavesNothing() {
        assertEquals("", unspokenRemainder(full, full))
        assertEquals("", unspokenRemainder("  $full ", full))
    }

    @Test
    fun scriptsWithoutSpaces() {
        assertEquals("你好吗？", unspokenRemainder("我很好。你好吗？", "我很好。"))
    }

    @Test
    fun notesStackAndCanBeRemoved() {
        val a = withNote(null, "Live transcription dropped")
        val b = withNote(a, "Translated on-device — timeout")
        assertEquals("Live transcription dropped\nTranslated on-device — timeout", b)
        assertEquals("adding the same note twice keeps one", b, withNote(b, "Translated on-device — timeout"))
        assertEquals("Live transcription dropped", withoutNote(b, "Translated on-device — timeout"))
        assertNull(withoutNote("Loading model…", "Loading model…"))
        assertEquals("multi-line messages become one line", "a b", withNote(null, "a\nb"))
    }
}
