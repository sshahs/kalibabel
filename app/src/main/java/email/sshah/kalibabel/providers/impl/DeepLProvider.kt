package email.sshah.kalibabel.providers.impl

import email.sshah.kalibabel.data.Formality
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.data.Languages
import email.sshah.kalibabel.net.arr
import email.sshah.kalibabel.net.at
import email.sshah.kalibabel.net.headers
import email.sshah.kalibabel.net.int
import email.sshah.kalibabel.net.json
import email.sshah.kalibabel.net.jsonBody
import email.sshah.kalibabel.net.str
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ModelInfo
import email.sshah.kalibabel.providers.Provider
import email.sshah.kalibabel.providers.ProviderContext
import email.sshah.kalibabel.providers.TranslationDelta
import email.sshah.kalibabel.providers.TranslationEngine
import email.sshah.kalibabel.providers.TranslationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request

/** DeepL API v2. Not an LLM: the system prompt is ignored, but conversation context is used. */
class DeepLProvider(ctx: ProviderContext) : Provider(ctx) {

    private val base: String
        get() = ctx.prefs.baseUrl?.takeIf { it.isNotBlank() }?.trimEnd('/')
            ?: if (ctx.apiKey.orEmpty().trim().endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"

    private fun headers() = mapOf("Authorization" to "DeepL-Auth-Key ${ctx.requireKey()}")

    private data class Result(val text: String, val detected: String?)

    private suspend fun call(request: TranslationRequest, source: Language?, target: Language): Result {
        val body = buildJsonObject {
            put("text", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(request.text)) })
            put("target_lang", targetCode(target))
            source?.let { put("source_lang", sourceCode(it)) }
            when (request.formality) {
                Formality.FORMAL -> put("formality", "prefer_more")
                Formality.INFORMAL -> put("formality", "prefer_less")
                Formality.AUTO -> Unit
            }
            if (request.model.isNotBlank() && request.model != "default") put("model_type", request.model)
            if (request.context.isNotEmpty()) {
                put("context", request.context.joinToString("\n") { it.original })
            }
        }
        val req = Request.Builder().url("$base/v2/translate").headers(headers()).post(jsonBody(body)).build()
        val json = ctx.http.json(req, name)
        val t = json.arr("translations").at(0)
        return Result(t.str("text").orEmpty(), t.str("detected_source_language"))
    }

    override val translation: TranslationEngine = object : TranslationEngine {
        override val isLlm = false

        override fun translate(request: TranslationRequest): Flow<TranslationDelta> = flow {
            if (!request.bidirectional && request.target != null) {
                emit(TranslationDelta.Text(call(request, request.source, request.target).text))
                return@flow
            }
            val toB = call(request, null, request.languageB)
            if (Languages.matches(request.languageB, toB.detected)) {
                val toA = call(request, request.languageB, request.languageA)
                emit(TranslationDelta.Target(request.languageA))
                emit(TranslationDelta.Text(toA.text))
            } else {
                emit(TranslationDelta.Target(request.languageB))
                emit(TranslationDelta.Text(toB.text))
            }
        }
    }

    override suspend fun listModels(capability: Capability): List<ModelInfo> = if (capability == Capability.TRANSLATION) {
        listOf(
            ModelInfo("latency_optimized", "Classic (latency optimised)", "Fastest. Best for live conversation."),
            ModelInfo("prefer_quality_optimized", "Next-gen if available", "DeepL's LLM where the language pair supports it, otherwise classic."),
            ModelInfo("quality_optimized", "Next-gen (quality optimised)", "Highest quality; slower and only for some language pairs."),
        )
    } else {
        emptyList()
    }

    override suspend fun test(): String {
        val req = Request.Builder().url("$base/v2/usage").headers(headers()).get().build()
        val json = ctx.http.json(req, name)
        val used = json.int("character_count")
        val limit = json.int("character_limit")
        return if (used != null && limit != null) "Connected · $used / $limit characters used this period" else "Connected"
    }

    override suspend fun prewarm(models: Map<Capability, String>) {
        runCatching { simpleGet("$base/v2/usage", headers()) }
    }

    companion object {
        fun targetCode(l: Language): String = when (l.code) {
            "en" -> if (l.region == "GB" || l.region == "AU" || l.region == "IN") "EN-GB" else "EN-US"
            "pt" -> if (l.region == "BR") "PT-BR" else "PT-PT"
            "zh" -> if (l.region == "TW" || l.region == "HK") "ZH-HANT" else "ZH-HANS"
            "nb", "no" -> "NB"
            else -> l.code.uppercase()
        }

        fun sourceCode(l: Language): String = when (l.code) {
            "nb", "no" -> "NB"
            else -> l.code.uppercase()
        }
    }
}
