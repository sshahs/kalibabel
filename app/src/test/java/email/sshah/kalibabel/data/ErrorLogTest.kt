package email.sshah.kalibabel.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ErrorLogTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val key = "sk-live-0123456789abcdefABCDEF"

    @Before
    fun setUp() {
        ErrorLog.install(context) { listOf(key, "elevenlabs-secret-key-42") }
        ErrorLog.clear()
    }

    @After
    fun tearDown() = ErrorLog.clear()

    @Test
    fun storedKeysAndCredentialShapesAreRedacted() {
        val text = "POST https://api.example.com/v1?key=AIzaSyA1b2C3d4E5f6G7h8 failed for $key, " +
            "header xi-api-key: elevenlabs-secret-key-42 Authorization: Bearer abcdefghijklmnop {\"api_key\":\"zzzzzzzzzzzz\"}"
        val out = ErrorLog.redact(text)
        listOf(key, "elevenlabs-secret-key-42", "AIzaSyA1b2C3d4E5f6G7h8", "abcdefghijklmnop", "zzzzzzzzzzzz").forEach {
            assertFalse("$it leaked: $out", out.contains(it))
        }
        assertTrue(out.contains("https://api.example.com/v1"))
        assertEquals("ordinary words survive", "monkey business: ok", ErrorLog.redact("monkey business: ok"))
    }

    @Test
    fun entriesPersistWithoutSecrets() {
        ErrorLog.error("Turn", "ElevenLabs rejected $key", IOException("boom $key"))
        ErrorLog.warning("Voice", "stream dropped")

        ErrorLog.install(context) { listOf(key) } // like the next app start
        val entries = ErrorLog.entries.value
        assertEquals(listOf("Turn", "Voice"), entries.map { it.source })
        assertEquals(LogLevel.ERROR, entries.first().level)
        assertTrue(entries.first().details.contains("IOException"))
        assertFalse(entries.joinToString { it.message + it.details }.contains(key))

        val report = ErrorLog.export()
        assertTrue(report.startsWith("KaliBabel "))
        assertTrue("newest first", report.indexOf("stream dropped") < report.indexOf("ElevenLabs rejected"))
    }

    @Test
    fun crashStaysUnseenUntilViewed() {
        assertFalse(ErrorLog.unseenCrash.value)
        ErrorLog.crash("Thread main", IllegalStateException("bad"))
        ErrorLog.install(context) { emptyList() }
        assertTrue(ErrorLog.unseenCrash.value)
        ErrorLog.markCrashSeen()
        ErrorLog.install(context) { emptyList() }
        assertFalse(ErrorLog.unseenCrash.value)
    }

    @Test
    fun logIsBounded() {
        repeat(400) { ErrorLog.warning("Test", "entry $it") }
        ErrorLog.install(context) { emptyList() }
        val entries = ErrorLog.entries.value
        assertTrue(entries.size <= 150)
        assertEquals("entry 399", entries.last().message)
    }
}
