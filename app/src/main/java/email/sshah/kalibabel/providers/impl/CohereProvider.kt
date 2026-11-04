package email.sshah.kalibabel.providers.impl

import email.sshah.kalibabel.net.ApiException
import email.sshah.kalibabel.net.arr
import email.sshah.kalibabel.net.headers
import email.sshah.kalibabel.net.json
import email.sshah.kalibabel.net.jsonBody
import email.sshah.kalibabel.net.jsonData
import email.sshah.kalibabel.net.obj
import email.sshah.kalibabel.net.objects
import email.sshah.kalibabel.net.sse
import email.sshah.kalibabel.net.str
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ModelInfo
import email.sshah.kalibabel.providers.Provider
import email.sshah.kalibabel.providers.ProviderContext
import email.sshah.kalibabel.providers.TranslationDelta
import email.sshah.kalibabel.providers.TranslationEngine
import email.sshah.kalibabel.providers.TranslationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request

/** Cohere v2 Chat API (incl. Command A Translate). */
class CohereProvider(ctx: ProviderContext) : Provider(ctx) {

    private fun headers() = mapOf("Authorization" to "Bearer ${ctx.requireKey()}")

    override val translation: TranslationEngine = object : TranslationEngine {
        override val isLlm = true

        override fun translate(request: TranslationRequest): Flow<TranslationDelta> =
            adaptiveStream("${spec.id}/${request.model}", listOf("temperature")) { blocked ->
                val body = buildJsonObject {
                    put("model", request.model)
                    put("stream", true)
                    put("messages", LlmStreams.openAiMessages(request))
                    if ("temperature" !in blocked) put("temperature", 0.2)
                }
                val req = Request.Builder().url("${ctx.baseUrl}/v2/chat").headers(headers()).post(jsonBody(body)).build()
                ctx.http.sse(req, name).jsonData().transform { (event, json) ->
                    when (json.str("type") ?: event) {
                        "content-delta" -> json.obj("delta").obj("message").obj("content").str("text")?.let { if (it.isNotEmpty()) emit(it) }
                        "error" -> throw ApiException(name, 500, json.str("message") ?: json.toString())
                    }
                }
            }.map { TranslationDelta.Text(it) }
    }

    override suspend fun listModels(capability: Capability): List<ModelInfo> {
        if (capability != Capability.TRANSLATION) return emptyList()
        val req = Request.Builder().url("${ctx.baseUrl}/v1/models?endpoint=chat&page_size=1000").headers(headers()).get().build()
        val json = ctx.http.json(req, name)
        return json.arr("models").objects().mapNotNull { m ->
            val id = m.str("name") ?: return@mapNotNull null
            ModelInfo(id, id, m.str("context_length")?.let { "context $it" })
        }
    }
}
