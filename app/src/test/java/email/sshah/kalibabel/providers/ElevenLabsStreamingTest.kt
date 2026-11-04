package email.sshah.kalibabel.providers

import android.content.ContextWrapper
import email.sshah.kalibabel.data.Languages
import email.sshah.kalibabel.data.ProviderPrefs
import email.sshah.kalibabel.net.ApiException
import email.sshah.kalibabel.net.AppJson
import email.sshah.kalibabel.net.Http
import email.sshah.kalibabel.net.arr
import email.sshah.kalibabel.net.obj
import email.sshah.kalibabel.net.str
import email.sshah.kalibabel.providers.impl.ElevenLabsProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class ElevenLabsStreamingTest {
    private lateinit var server: MockWebServer
    private val received = CopyOnWriteArrayList<String>()
    private val chunks = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6), byteArrayOf(7, 8, 9, 10))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(privacy: Boolean = false): ElevenLabsProvider {
        val spec = ProviderSpecs[ProviderId.ELEVENLABS]
        val prefs = ProviderPrefs(baseUrl = server.url("/").toString().trimEnd('/'), privacy = privacy)
        return ElevenLabsProvider(ProviderContext(ContextWrapper(null), Http.createClient(), spec, "test-key", prefs))
    }

    /** Fake ElevenLabs: replies with one audio chunk per text message, then isFinal on close. */
    private fun fakeTts() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        private var next = 0

        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            val t = AppJson.parseToJsonElement(text).str("text")
            when {
                t == "" -> {
                    while (next < chunks.size) sendAudio(webSocket)
                    webSocket.send("""{"isFinal":true}""")
                    webSocket.close(1000, null)
                }
                t != null && t.isNotBlank() && next < chunks.size -> sendAudio(webSocket)
            }
        }

        private fun sendAudio(ws: WebSocket) {
            ws.send("""{"audio":"${Base64.getEncoder().encodeToString(chunks[next++])}","isFinal":null}""")
        }

        override fun onOpen(webSocket: WebSocket, response: Response) = Unit
    })

    @Test
    fun streamsWordCompleteTextAndReturnsPcm() = runBlocking {
        server.enqueue(fakeTts())
        val text = Channel<String>(Channel.UNLIMITED)
        val request = TtsRequest("", Languages.byTag("es-ES"), "eleven_flash_v2_5", "voice123", 1.1f, null)
        val output = provider(privacy = true).tts.synthesizeTextStream(request, text)
        assertEquals(24_000, output.sampleRate)

        val audio = ByteArrayOutputStream()
        withTimeout(10_000) {
            val collector = launch { output.chunks.collect { audio.write(it) } }
            for (d in listOf("Ho", "la, ¿qué", " tal? Muy", " bien.")) text.send(d)
            text.close()
            collector.join()
        }
        assertArrayEquals(chunks.reduce { a, b -> a + b }, audio.toByteArray())

        val upgrade = server.takeRequest()
        val url = upgrade.requestUrl!!
        assertEquals("/v1/text-to-speech/voice123/stream-input", url.encodedPath)
        assertEquals("eleven_flash_v2_5", url.queryParameter("model_id"))
        assertEquals("pcm_24000", url.queryParameter("output_format"))
        assertEquals("es", url.queryParameter("language_code"))
        assertEquals("false", url.queryParameter("enable_logging"))
        assertEquals("test-key", upgrade.getHeader("xi-api-key"))

        val init = AppJson.parseToJsonElement(received.first())
        assertEquals(" ", init.str("text"))
        assertEquals(1.1, init.obj("voice_settings")!!["speed"]!!.jsonPrimitive.double, 1e-6)
        assertEquals(50, init.obj("generation_config").arr("chunk_length_schedule")!!.first().jsonPrimitive.int)

        val pieces = received.drop(1).dropLast(1).map { AppJson.parseToJsonElement(it).str("text")!! }
        assertTrue("word-complete pieces ending in a space: $pieces", pieces.all { it.endsWith(" ") })
        assertEquals("Hola, ¿qué tal? Muy bien.", pieces.joinToString("").trim())
        assertEquals("", AppJson.parseToJsonElement(received.last()).str("text"))
    }

    @Test
    fun serverErrorSurfacesAsApiException() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"message":"Invalid API key","error":"auth_error","code":1008}""")
                webSocket.close(1008, "Invalid API key")
            }
        }))
        val text = Channel<String>(Channel.UNLIMITED)
        val output = provider().tts.synthesizeTextStream(
            TtsRequest("", Languages.byTag("en-GB"), "eleven_flash_v2_5", null, 1f, null), text,
        )
        text.send("Hello there ")
        text.close()
        try {
            withTimeout(10_000) { output.chunks.collect { } }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("Invalid API key"))
        }
    }
}
