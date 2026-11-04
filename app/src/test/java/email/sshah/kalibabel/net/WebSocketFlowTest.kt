package email.sshah.kalibabel.net

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Fake-server side: drops the TCP connection without a close frame, like a server hanging up
 * after its last reply. Call it from a MockWebServer listener callback: it waits until everything
 * queued with send() went out, then fails the callback, which makes OkHttp close the streams.
 * (Server-side cancel() can't be used: it discards queued frames and needs a client Call.)
 */
internal fun WebSocket.hangUpOnceSent(): Nothing {
    while (queueSize() > 0) Thread.sleep(5)
    throw HangUp()
}

internal class HangUp : RuntimeException("fake server hung up")

class WebSocketFlowTest {
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

    private fun request() = Request.Builder().url(server.url("/ws")).build()

    /** Sends two messages, then hangs up without a close frame once the client asks to close. */
    private fun rudeServer(hangUpAfterMessages: Boolean = false) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("one")
            webSocket.send("two")
            if (hangUpAfterMessages) webSocket.hangUpOnceSent()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.hangUpOnceSent() // instead of answering the close
        }
    })

    @Test
    fun hangUpAfterOurNormalCloseCompletesNormally() = runBlocking {
        server.enqueue(rudeServer())
        var socket: WebSocket? = null
        val texts = withTimeout(10_000) {
            http.webSocket(request(), "Test") { socket = it }
                .filterIsInstance<WsMessage.Text>()
                .map { msg -> msg.text.also { if (it == "two") socket?.close(1000, null) } }
                .toList()
        }
        assertEquals(listOf("one", "two"), texts)
    }

    @Test
    fun unexpectedHangUpIsAReadableError() = runBlocking {
        server.enqueue(rudeServer(hangUpAfterMessages = true))
        val texts = mutableListOf<String>()
        try {
            withTimeout(10_000) {
                http.webSocket(request(), "Test") { }
                    .filterIsInstance<WsMessage.Text>()
                    .collect { texts += it.text }
            }
            fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.startsWith("Test: "))
        }
        assertEquals("messages before the hang-up are all delivered", listOf("one", "two"), texts)
    }
}
