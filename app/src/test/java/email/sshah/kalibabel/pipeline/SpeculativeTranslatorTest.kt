package email.sshah.kalibabel.pipeline

import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.providers.TranslationDelta
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeculativeTranslatorTest {
    private val started = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()

    private fun TestScope.translator(stableMs: Long = 300) = SpeculativeTranslator(backgroundScope, stableMs) { text ->
        started += text
        val job = Job()
        job.invokeOnCompletion { if (it != null) cancelled += text }
        Speculation(text, null, null, ProviderId.OPENAI, "m", true, Channel<TranslationDelta>(Channel.UNLIMITED), job)
    }

    @Test
    fun startsOnlyAfterThePartialIsStable() = runTest {
        val t = translator()
        t.onPartial("where is the")
        advanceTimeBy(200); runCurrent()
        t.onPartial("where is the train station")
        advanceTimeBy(250); runCurrent()
        assertTrue("not stable yet", started.isEmpty())
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("where is the train station"), started)
    }

    @Test
    fun matchingFinalHandsOverTheSpeculation() = runTest {
        val t = translator()
        t.onPartial("where is the train station")
        advanceTimeBy(400); runCurrent()
        val spec = t.take("Where is the train station?")
        assertNotNull("final only adds casing/punctuation", spec)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun differentFinalCancelsTheSpeculation() = runTest {
        val t = translator()
        t.onPartial("where is the train")
        advanceTimeBy(400); runCurrent()
        assertNull(t.take("Where is the train station?"))
        assertEquals(listOf("where is the train"), cancelled)
    }

    @Test
    fun changedPartialCancelsStaleGuessAndBudgetIsCapped() = runTest {
        val t = translator()
        for (p in listOf("one two three", "one two three four", "one two three four five", "one two three four five six")) {
            t.onPartial(p)
            advanceTimeBy(400); runCurrent()
        }
        assertEquals("at most 3 speculative requests per utterance", 3, started.size)
        assertEquals("each superseded guess was cancelled", started.take(2), cancelled.take(2))
        t.take("x")
        assertEquals(0, t.attemptsThisUtterance)
    }

    @Test
    fun ignoresVeryShortPartials() = runTest {
        val t = translator()
        t.onPartial("hi")
        advanceTimeBy(1000); runCurrent()
        assertTrue(started.isEmpty())
    }

    @Test
    fun normalizationKeepsVowelSigns() {
        assertEquals(SpeculativeTranslator.normalize("Where's  the TRAIN?"), SpeculativeTranslator.normalize("where s the train"))
        assertFalse(SpeculativeTranslator.normalize("કેમ છો") == SpeculativeTranslator.normalize("કૈમ છો"))
        assertEquals(SpeculativeTranslator.normalize("કેમ છો?"), SpeculativeTranslator.normalize("કેમ છો"))
    }
}
