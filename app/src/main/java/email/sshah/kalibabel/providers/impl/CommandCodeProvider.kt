package email.sshah.kalibabel.providers.impl

import email.sshah.kalibabel.net.arr
import email.sshah.kalibabel.net.asString
import email.sshah.kalibabel.net.headers
import email.sshah.kalibabel.net.json
import email.sshah.kalibabel.net.str
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ModelClassifier
import email.sshah.kalibabel.providers.ModelInfo
import email.sshah.kalibabel.providers.ProviderContext
import email.sshah.kalibabel.providers.TranslationDelta
import email.sshah.kalibabel.providers.TranslationEngine
import email.sshah.kalibabel.providers.TranslationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Command Code Provider API (https://api.commandcode.ai/provider/v1).
 *
 * - Open / OpenAI-format models are served on `/chat/completions`.
 * - Claude models are served only on `/messages` (Anthropic format); we route automatically
 *   using the `supported_endpoints` field from `/models`, falling back to the model name.
 * - The ZDR toggle sends `x-cmd-zdr: 1`: requests are only routed to zero-data-retention
 *   upstreams and fail with 422 `cmd_zdr_no_providers` rather than silently falling back.
 */
class CommandCodeProvider(ctx: ProviderContext) : OpenAiCompatibleProvider(ctx) {

    /** model id → true if it must use the Anthropic `/messages` endpoint. */
    private val messagesRoute = ConcurrentHashMap<String, Boolean>()

    override fun authHeaders(): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${ctx.requireKey()}")
        if (ctx.privacy) put("x-cmd-zdr", "1")
    }

    private fun usesMessages(model: String): Boolean =
        messagesRoute[model] ?: model.substringAfterLast('/').lowercase().startsWith("claude")

    override val translation: TranslationEngine = object : TranslationEngine {
        override val isLlm = true

        override fun translate(request: TranslationRequest): Flow<TranslationDelta> {
            val key = "${spec.id}/${request.model}"
            val stream = if (usesMessages(request.model)) {
                adaptiveStream(key, listOf("temperature")) { blocked ->
                    LlmStreams.anthropic(
                        ctx.http, name, "${ctx.baseUrl}/messages",
                        authHeaders() + mapOf("anthropic-version" to "2023-06-01"),
                        LlmStreams.anthropicBody(request, temperature = "temperature" !in blocked),
                    )
                }
            } else {
                adaptiveStream(key, listOf("temperature")) { blocked ->
                    val body = buildJsonObject {
                        put("model", request.model)
                        put("stream", true)
                        put("messages", LlmStreams.openAiMessages(request))
                        if ("temperature" !in blocked) put("temperature", 0.2)
                    }
                    LlmStreams.openAiChat(ctx.http, name, "${ctx.baseUrl}/chat/completions", authHeaders(), body)
                }
            }
            return stream.map { TranslationDelta.Text(it) }
        }
    }

    override suspend fun listModels(capability: Capability): List<ModelInfo> {
        if (capability != Capability.TRANSLATION) return emptyList()
        val req = Request.Builder().url("${ctx.baseUrl}/models").headers(authHeaders()).get().build()
        val json = ctx.http.json(req, name)
        return LlmStreams.modelIds(json).mapNotNull { (id, obj) ->
            val endpoints = obj.arr("supported_endpoints")?.mapNotNull { it.asString?.lowercase() }.orEmpty()
            if (endpoints.isNotEmpty()) {
                val chat = endpoints.any { "chat" in it || "responses" in it }
                val messages = endpoints.any { "messages" in it }
                if (!chat && !messages) return@mapNotNull null // e.g. /systemone decision models
                messagesRoute[id] = messages && !chat
            }
            if (!ModelClassifier.matches(id, Capability.TRANSLATION)) return@mapNotNull null
            val route = if (messagesRoute[id] == true) "Anthropic /messages" else "OpenAI /chat/completions"
            ModelInfo(id, obj.str("name") ?: obj.str("display_name") ?: id, route)
        }.sortedBy { it.id.lowercase() }
    }
}
