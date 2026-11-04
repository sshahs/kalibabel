package email.sshah.kalibabel.providers.impl

import email.sshah.kalibabel.net.ApiException
import email.sshah.kalibabel.net.arr
import email.sshah.kalibabel.net.asString
import email.sshah.kalibabel.net.at
import email.sshah.kalibabel.net.headers
import email.sshah.kalibabel.net.jsonBody
import email.sshah.kalibabel.net.jsonData
import email.sshah.kalibabel.net.obj
import email.sshah.kalibabel.net.sse
import email.sshah.kalibabel.net.str
import email.sshah.kalibabel.providers.TranslationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers request parameters a given model rejected (e.g. `temperature` on reasoning models,
 * `thinkingConfig` on models that cannot disable thinking) so we only pay for the failed
 * attempt once per app session.
 */
object ParamCompat {
    private val blocked = ConcurrentHashMap<String, MutableSet<String>>()

    fun blocked(key: String): Set<String> = blocked[key].orEmpty()

    fun block(key: String, param: String) {
        blocked.getOrPut(key) { ConcurrentHashMap.newKeySet() }.add(param)
    }

    fun offending(e: ApiException, candidates: Collection<String>): String? {
        if (e.code !in 400..422) return null
        val msg = e.message.orEmpty().lowercase()
        return candidates.firstOrNull { msg.contains(it.lowercase()) }
    }
}

/**
 * Runs a streaming request built by [build]; if it fails *before producing output* because the
 * provider rejected one of [optionalParams], retries without it.
 */
fun <T> adaptiveStream(key: String, optionalParams: List<String>, build: (blocked: Set<String>) -> Flow<T>): Flow<T> = flow {
    var attempts = 0
    while (true) {
        val blocked = ParamCompat.blocked(key)
        var emitted = false
        try {
            build(blocked).collect {
                emitted = true
                emit(it)
            }
            return@flow
        } catch (e: ApiException) {
            val offending = if (!emitted && attempts < optionalParams.size) {
                ParamCompat.offending(e, optionalParams.filterNot { it in blocked })
            } else {
                null
            }
            if (offending == null) throw e
            ParamCompat.block(key, offending)
            attempts++
        }
    }
}

object LlmStreams {
    /** OpenAI Chat Completions streaming (also Groq, DeepSeek, OpenRouter, Command Code, …). */
    fun openAiChat(
        http: OkHttpClient,
        provider: String,
        url: String,
        headers: Map<String, String>,
        body: JsonObject,
    ): Flow<String> {
        val request = Request.Builder().url(url).headers(headers).post(jsonBody(body)).build()
        return http.sse(request, provider).jsonData().transform { (_, json) ->
            json.obj("error")?.let { throw ApiException(provider, 500, it.str("message") ?: it.toString()) }
            val delta = json.arr("choices").at(0).obj("delta")
            val content = delta?.get("content")
            val text = when (content) {
                is JsonPrimitive -> content.asString
                is JsonArray -> content.joinToString("") { it.str("text").orEmpty() }
                else -> null
            }
            if (!text.isNullOrEmpty()) emit(text)
        }
    }

    fun openAiMessages(request: TranslationRequest): JsonArray = buildJsonArray {
        add(buildJsonObject { put("role", "system"); put("content", request.systemPrompt) })
        add(buildJsonObject { put("role", "user"); put("content", request.text) })
    }

    /** Anthropic Messages streaming. */
    fun anthropic(
        http: OkHttpClient,
        provider: String,
        url: String,
        headers: Map<String, String>,
        body: JsonObject,
    ): Flow<String> {
        val request = Request.Builder().url(url).headers(headers).post(jsonBody(body)).build()
        return http.sse(request, provider).jsonData().transform { (event, json) ->
            val type = event ?: json.str("type")
            when (type) {
                "content_block_delta" -> {
                    val delta = json.obj("delta")
                    if (delta.str("type") == "text_delta") delta.str("text")?.let { if (it.isNotEmpty()) emit(it) }
                }
                "error" -> throw ApiException(provider, 500, json.obj("error").str("message") ?: json.toString())
            }
        }
    }

    fun anthropicBody(request: TranslationRequest, temperature: Boolean): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", 2048)
        put("stream", true)
        put("system", request.systemPrompt)
        if (temperature) put("temperature", 0.2)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", request.text) })
        })
    }

    /** Extracts model ids from `{data:[{id}]}`, `{models:[{name}]}` or a bare array. */
    fun modelIds(json: JsonElement): List<Pair<String, JsonObject>> {
        val list = when (json) {
            is JsonArray -> json
            is JsonObject -> (json["data"] as? JsonArray) ?: (json["models"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: o.str("name") ?: o.str("model") ?: return@mapNotNull null
            id to o
        }
    }
}
