package email.sshah.kalibabel.pipeline

import email.sshah.kalibabel.data.Formality
import email.sshah.kalibabel.data.Languages
import email.sshah.kalibabel.providers.ContextLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {
    private val en = Languages.byTag("en-US")
    private val es = Languages.byTag("es-ES")

    @Test
    fun directionalPromptNamesBothLanguagesAndForbidsAnswering() {
        val p = Prompts.system(en, es, en, es, Formality.FORMAL, emptyList(), "", emptyList())
        assertTrue(p.contains("from English (US) (en-US) into Spanish (Spain) (es-ES)"))
        assertTrue(p.contains("Never answer questions"))
        assertTrue(p.contains("formal"))
        assertFalse(p.contains("square brackets"))
    }

    @Test
    fun bidirectionalPromptRequestsLanguageTag() {
        val p = Prompts.system(null, null, en, es, Formality.AUTO, emptyList(), "", emptyList())
        assertTrue(p.contains("[en-US] or [es-ES]"))
    }

    @Test
    fun includesGlossaryInstructionsAndContext() {
        val p = Prompts.system(
            en, es, en, es, Formality.AUTO,
            listOf("KaliBabel" to "KaliBabel", "ticket" to "billete"),
            "Travel context.",
            listOf(ContextLine(en, "Where is the station?", "¿Dónde está la estación?")),
        )
        assertTrue(p.contains("- KaliBabel (keep as is)"))
        assertTrue(p.contains("- ticket → billete"))
        assertTrue(p.contains("Additional instructions: Travel context."))
        assertTrue(p.contains("Where is the station? ⇒ ¿Dónde está la estación?"))
    }
}
