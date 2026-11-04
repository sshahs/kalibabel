package email.sshah.kalibabel.providers

import email.sshah.kalibabel.net.ApiException
import email.sshah.kalibabel.net.Http
import email.sshah.kalibabel.providers.impl.LlmStreams
import email.sshah.kalibabel.providers.impl.adaptiveStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class StreamingClientTest {
    private lateinit var server: MockWebServer
    private val http = Http.createClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(vararg data: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { "data: $it\n\n" })

    @Test
    fun openAiChatStreamYieldsContentDeltas() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"role":"assistant"}}]}""",
                """{"choices":[{"delta":{"content":"Hola"}}]}""",
                """{"choices":[{"delta":{"content":", mundo"}}]}""",
                "[DONE]",
            ),
        )
        val out = LlmStreams.openAiChat(
            http, "Test", server.url("/v1/chat/completions").toString(),
            mapOf("Authorization" to "Bearer k"), buildJsonObject { put("model", "m") },
        ).toList()
        assertEquals(listOf("Hola", ", mundo"), out)
        val req = server.takeRequest()
        assertEquals("Bearer k", req.getHeader("Authorization"))
    }

    @Test
    fun anthropicStreamYieldsTextDeltas() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: message_start\ndata: {\"type\":\"message_start\"}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Bonjour\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            ),
        )
        val out = LlmStreams.anthropic(http, "Test", server.url("/v1/messages").toString(), emptyMap(), buildJsonObject { }).toList()
        assertEquals(listOf("Bonjour"), out)
    }

    @Test
    fun adaptiveStreamDropsRejectedParameterAndRetries() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unsupported parameter: 'temperature'"}}"""))
        server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"}}]}""", "[DONE]"))
        val url = server.url("/v1/chat/completions").toString()
        val out = adaptiveStream("test/model-${System.nanoTime()}", listOf("temperature")) { blocked ->
            val body = buildJsonObject {
                put("model", "m")
                if ("temperature" !in blocked) put("temperature", 0.2)
            }
            LlmStreams.openAiChat(http, "Test", url, emptyMap(), body)
        }.toList()
        assertEquals(listOf("ok"), out)
        assertTrue(server.takeRequest().body.readUtf8().contains("temperature"))
        assertFalse(server.takeRequest().body.readUtf8().contains("temperature"))
    }

    @Test
    fun httpErrorsSurfaceProviderMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":{"message":"x-cmd-zdr: 1 was set but the model has no zero-data-retention upstream.","code":"cmd_zdr_no_providers"}}"""))
        try {
            LlmStreams.openAiChat(http, "Command Code", server.url("/chat/completions").toString(), emptyMap(), buildJsonObject { }).toList()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(422, e.code)
            assertTrue(e.message!!.contains("zero-data-retention"))
        }
    }
}
