package email.sshah.kalibabel.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A non-2xx response from a provider, with the provider's own error message when available. */
class ApiException(val provider: String, val code: Int, message: String) :
    IOException("$provider: HTTP $code – $message")

val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

object Http {
    /**
     * One shared client for every provider: HTTP/2 multiplexing and a warm connection pool mean
     * the TLS handshake is paid once, not per request — a big part of conversational latency.
     */
    fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .build()
}

fun jsonBody(element: JsonElement): RequestBody = element.toString().toRequestBody(JSON_MEDIA)

fun Request.Builder.headers(map: Map<String, String>): Request.Builder = apply {
    map.forEach { (k, v) -> header(k, v) }
}

/** Suspends until the response headers arrive; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** Throws [ApiException] (closing the response) for non-2xx responses. */
suspend fun Response.checked(provider: String): Response {
    if (isSuccessful) return this
    val body = withContext(Dispatchers.IO) { runCatching { body.string() }.getOrDefault("") }
    val code = this.code
    close()
    throw ApiException(provider, code, extractErrorMessage(body) ?: message.ifBlank { "request failed" })
}

suspend fun OkHttpClient.execute(request: Request, provider: String): Response =
    newCall(request).await().checked(provider)

suspend fun OkHttpClient.json(request: Request, provider: String): JsonElement {
    val response = execute(request, provider)
    val text = withContext(Dispatchers.IO) { response.use { it.body.string() } }
    return AppJson.parseToJsonElement(text.ifBlank { "{}" })
}

/**
 * Streams the response body line by line. The HTTP call is cancelled as soon as the collector
 * stops (e.g. the user interrupts), so no tokens are wasted on abandoned translations.
 */
fun OkHttpClient.lines(request: Request, provider: String): Flow<String> = flow {
    val call = newCall(request)
    val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { call.cancel() } }
    try {
        call.await().checked(provider).use { response ->
            val source = response.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                emit(line)
            }
        }
    } finally {
        handle?.dispose()
    }
}.flowOn(Dispatchers.IO)

fun OkHttpClient.sse(request: Request, provider: String): Flow<SseEvent> {
    val parser = SseParser()
    return flow {
        lines(request, provider).collect { line -> parser.feed(line)?.let { emit(it) } }
        parser.finish()?.let { emit(it) }
    }
}

/** Newline-delimited JSON (Ollama). */
fun OkHttpClient.ndjson(request: Request, provider: String): Flow<JsonElement> =
    lines(request, provider)
        .filter { it.isNotBlank() }
        .map { AppJson.parseToJsonElement(it) }

/** Streams a binary body (audio) in chunks as they arrive from the network. */
fun OkHttpClient.bytes(request: Request, provider: String, chunkSize: Int = 4096): Flow<ByteArray> = flow {
    val call = newCall(request)
    val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { call.cancel() } }
    try {
        call.await().checked(provider).use { response ->
            val stream = response.body.byteStream()
            val buf = ByteArray(chunkSize)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n > 0) emit(buf.copyOf(n))
            }
        }
    } finally {
        handle?.dispose()
    }
}.flowOn(Dispatchers.IO)

/** Parses an SSE stream of JSON payloads, skipping keep-alives and the OpenAI `[DONE]` marker. */
fun Flow<SseEvent>.jsonData(): Flow<Pair<String?, JsonElement>> = transform { ev ->
    val data = ev.data.trim()
    if (data.isEmpty() || data == "[DONE]") return@transform
    val el = runCatching { AppJson.parseToJsonElement(data) }.getOrNull() ?: return@transform
    emit(ev.event to el)
}

// ---------------------------------------------------------------------------------------------
// WebSockets
// ---------------------------------------------------------------------------------------------

sealed interface WsMessage {
    data class Text(val text: String) : WsMessage

    data class Binary(val bytes: ByteString) : WsMessage

    data object Open : WsMessage
}

/**
 * Opens a WebSocket and exposes it as a flow of messages. [onOpen] receives the socket so the
 * caller can start sending. The flow completes when the server closes the socket.
 *
 * Once the caller has closed the socket normally (code 1000), a connection that then drops
 * without a close frame still completes the flow normally: some servers just hang up after
 * their final message, and everything the caller asked for has arrived by then.
 */
fun OkHttpClient.webSocket(
    request: Request,
    provider: String,
    onOpen: (WebSocket) -> Unit,
): Flow<WsMessage> = callbackFlow {
    val ws = newWebSocket(request, object : WebSocketListener() {
        @Volatile
        private var socket: CloseTrackingWebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val tracked = CloseTrackingWebSocket(webSocket)
            socket = tracked
            trySend(WsMessage.Open)
            onOpen(tracked)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            trySend(WsMessage.Text(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            trySend(WsMessage.Binary(bytes))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            if (code == 1000 || code == 1001 || code == 1005) {
                close()
            } else {
                close(ApiException(provider, code, reason.ifBlank { "WebSocket closed" }))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (response == null && socket?.closedNormally == true) {
                close() // we were done anyway; the server hung up instead of answering our close
                return
            }
            val body = runCatching { response?.body?.string() }.getOrNull()
            val reason = t.message ?: if (t is EOFException) "connection dropped by the server" else "WebSocket failed"
            val err = if (response != null) {
                ApiException(provider, response.code, body?.let { extractErrorMessage(it) } ?: response.message.ifBlank { reason })
            } else {
                IOException("$provider: $reason", t)
            }
            close(err)
        }
    })
    awaitClose { ws.cancel() }
}.buffer(Channel.UNLIMITED) // never drop audio or control messages when the collector is busy

/** Remembers whether the caller closed the socket normally (see [webSocket]). */
private class CloseTrackingWebSocket(private val delegate: WebSocket) : WebSocket by delegate {
    @Volatile
    var closedNormally = false
        private set

    override fun close(code: Int, reason: String?): Boolean {
        if (code == 1000) closedNormally = true
        return delegate.close(code, reason)
    }
}
