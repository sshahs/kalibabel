package email.sshah.kalibabel.data

import email.sshah.kalibabel.providers.impl.DeepLProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {
    @Test
    fun looksUpByTagAndPrimarySubtag() {
        assertEquals("pt-BR", Languages.byTag("pt-br").tag)
        assertEquals("de", Languages.byTag("de").code)
        assertEquals("ja-JP", Languages.byTag("ja").tag)
    }

    @Test
    fun matchesProviderCodes() {
        val en = Languages.byTag("en-US")
        assertTrue(Languages.matches(en, "en"))
        assertTrue(Languages.matches(en, "en_GB"))
        assertFalse(Languages.matches(en, "es"))
        assertFalse(Languages.matches(en, null))
        assertTrue(Languages.matches(Languages.byTag("fil-PH"), "tl"))
    }

    @Test
    fun whisperCodes() {
        assertEquals("tl", Languages.byTag("fil-PH").whisperCode)
        assertEquals("no", Languages.byTag("nb-NO").whisperCode)
        assertEquals("fr", Languages.byTag("fr-CA").whisperCode)
    }

    @Test
    fun deeplCodes() {
        assertEquals("EN-GB", DeepLProvider.targetCode(Languages.byTag("en-GB")))
        assertEquals("EN-US", DeepLProvider.targetCode(Languages.byTag("en-US")))
        assertEquals("PT-BR", DeepLProvider.targetCode(Languages.byTag("pt-BR")))
        assertEquals("ZH-HANT", DeepLProvider.targetCode(Languages.byTag("zh-TW")))
        assertEquals("DE", DeepLProvider.targetCode(Languages.byTag("de-DE")))
    }

    @Test
    fun glossaryParsing() {
        val s = AppSettings(glossary = "KaliBabel\nticket = billete\n# comment\nGleis -> platform\n\n")
        assertEquals(listOf("KaliBabel" to "KaliBabel", "ticket" to "billete", "Gleis" to "platform"), s.glossaryEntries)
    }
}
