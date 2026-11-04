package email.sshah.kalibabel.providers.impl

import email.sshah.kalibabel.net.ApiException
import email.sshah.kalibabel.net.arr
import email.sshah.kalibabel.net.headers
import email.sshah.kalibabel.net.json
import email.sshah.kalibabel.net.jsonBody
import email.sshah.kalibabel.net.ndjson
import email.sshah.kalibabel.net.obj
import email.sshah.kalibabel.net.objects
import email.sshah.kalibabel.net.str
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ModelInfo
import email.sshah.kalibabel.providers.Provider
import email.sshah.kalibabel.providers.ProviderContext
import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.providers.TranslationDelta
import email.sshah.kalibabel.providers.TranslationEngine
import email.sshah.kalibabel.providers.TranslationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request

/**
 * Ollama native API (`/api/chat`, `/api/tags`). Used for both a self-hosted server and Ollama
 * Cloud (https://ollama.com, Bearer API key).
 */
class OllamaProvider(ctx: ProviderContext) : Provider(ctx) {
    private val cloud = spec.id == ProviderId.OLLAMA_CLOUD

    private fun headers(): Map<String, String> = buildMap {
        val key = if (cloud) ctx.requireKey() else ctx.apiKey
        key?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
    }

    override val translation: TranslationEngine = object : TranslationEngine {
        override val isLlm = true

        override fun translate(request: TranslationRequest): Flow<TranslationDelta> =
            adaptiveStream("${spec.id}/${request.model}", listOf("think")) { blocked ->
                val body = buildJsonObject {
                    put("model", request.model)
                    put("stream", true)
                    put("messages", LlmStreams.openAiMessages(request))
                    // Thinking adds seconds of latency and nothing to a translation.
                    if ("think" !in blocked) put("think", false)
                    if (!cloud) put("keep_alive", "30m")
                    putJsonObject("options") { put("temperature", 0.2) }
                }
                val req = Request.Builder().url("${ctx.baseUrl}/api/chat").headers(headers()).post(jsonBody(body)).build()
                ctx.http.ndjson(req, name).transform { json ->
                    json.str("error")?.let { throw ApiException(name, 400, it) }
                    json.obj("message").str("content")?.let { if (it.isNotEmpty()) emit(it) }
                }
            }.map { TranslationDelta.Text(it) }
    }

    override suspend fun listModels(capability: Capability): List<ModelInfo> {
        if (capability != Capability.TRANSLATION) return emptyList()
        val req = Request.Builder().url("${ctx.baseUrl}/api/tags").headers(headers()).get().build()
        val json = ctx.http.json(req, name)
        return json.arr("models").objects().mapNotNull { m ->
            val id = m.str("name") ?: m.str("model") ?: return@mapNotNull null
            val details = m.obj("details")
            val desc = listOfNotNull(details.str("parameter_size"), details.str("quantization_level"), details.str("family"))
                .joinToString(" · ").ifBlank { null }
            ModelInfo(id, id, desc)
        }.filterNot { "embed" in it.id }
    }

    /** Loads the model into (V)RAM ahead of the first request on a self-hosted server. */
    override suspend fun prewarm(models: Map<Capability, String>) {
        val model = models[Capability.TRANSLATION]
        if (cloud || model.isNullOrBlank()) return super.prewarm(models)
        runCatching {
            val body = buildJsonObject {
                put("model", model)
                put("keep_alive", "30m")
            }
            val req = Request.Builder().url("${ctx.baseUrl}/api/generate").headers(headers()).post(jsonBody(body)).build()
            ctx.http.json(req, name)
        }
    }
}
