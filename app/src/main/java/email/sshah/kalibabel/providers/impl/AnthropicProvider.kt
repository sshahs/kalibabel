package email.sshah.kalibabel.providers.impl

import email.sshah.kalibabel.net.headers
import email.sshah.kalibabel.net.json
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
import okhttp3.Request

class AnthropicProvider(ctx: ProviderContext) : Provider(ctx) {

    private fun headers() = mapOf(
        "x-api-key" to ctx.requireKey(),
        "anthropic-version" to "2023-06-01",
    )

    override val translation: TranslationEngine = object : TranslationEngine {
        override val isLlm = true

        override fun translate(request: TranslationRequest): Flow<TranslationDelta> =
            adaptiveStream("${spec.id}/${request.model}", listOf("temperature")) { blocked ->
                LlmStreams.anthropic(
                    ctx.http, name, "${ctx.baseUrl}/v1/messages", headers(),
                    LlmStreams.anthropicBody(request, temperature = "temperature" !in blocked),
                )
            }.map { TranslationDelta.Text(it) }
    }

    override suspend fun listModels(capability: Capability): List<ModelInfo> {
        if (capability != Capability.TRANSLATION) return emptyList()
        val req = Request.Builder().url("${ctx.baseUrl}/v1/models?limit=1000").headers(headers()).get().build()
        val json = ctx.http.json(req, name)
        return LlmStreams.modelIds(json).map { (id, obj) ->
            ModelInfo(id, obj.str("display_name") ?: id, obj.str("created_at")?.take(10))
        }
    }
}
