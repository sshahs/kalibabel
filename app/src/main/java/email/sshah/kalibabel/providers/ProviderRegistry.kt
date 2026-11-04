package email.sshah.kalibabel.providers

import android.content.Context
import email.sshah.kalibabel.data.AppSettings
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.data.SecureKeyStore
import email.sshah.kalibabel.data.SettingsRepository
import email.sshah.kalibabel.providers.impl.AndroidProvider
import email.sshah.kalibabel.providers.impl.AnthropicProvider
import email.sshah.kalibabel.providers.impl.AssemblyAiProvider
import email.sshah.kalibabel.providers.impl.CartesiaProvider
import email.sshah.kalibabel.providers.impl.CohereProvider
import email.sshah.kalibabel.providers.impl.CommandCodeProvider
import email.sshah.kalibabel.providers.impl.DeepLProvider
import email.sshah.kalibabel.providers.impl.DeepgramProvider
import email.sshah.kalibabel.providers.impl.ElevenLabsProvider
import email.sshah.kalibabel.providers.impl.GeminiProvider
import email.sshah.kalibabel.providers.impl.LanguageDetector
import email.sshah.kalibabel.providers.impl.OllamaProvider
import email.sshah.kalibabel.providers.impl.OnDeviceTranslator
import email.sshah.kalibabel.providers.impl.OpenAiCompatibleProvider
import email.sshah.kalibabel.providers.impl.SystemTts
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** Builds provider instances from settings + keys and caches them until either changes. */
class ProviderRegistry(
    private val app: Context,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val keys: SecureKeyStore,
) {
    val systemTts = SystemTts(app)
    val onDeviceTranslator = OnDeviceTranslator()
    val languageDetector = LanguageDetector()

    private data class CacheKey(val id: ProviderId, val key: String?, val prefs: Any)

    private val cache = ConcurrentHashMap<ProviderId, Pair<CacheKey, Provider>>()

    fun get(id: ProviderId): Provider {
        val spec = ProviderSpecs[id]
        val key = keys.get(id)
        val prefs = settings.current.prefs(id)
        val cacheKey = CacheKey(id, key, prefs)
        cache[id]?.let { (k, p) -> if (k == cacheKey) return p }
        val ctx = ProviderContext(app, http, spec, key, prefs)
        val provider = create(ctx)
        cache[id] = cacheKey to provider
        return provider
    }

    private fun create(ctx: ProviderContext): Provider = when (ctx.spec.id) {
        ProviderId.ANDROID -> AndroidProvider(ctx, systemTts, onDeviceTranslator, languageDetector)
        ProviderId.ANTHROPIC -> AnthropicProvider(ctx)
        ProviderId.GEMINI -> GeminiProvider(ctx)
        ProviderId.ELEVENLABS -> ElevenLabsProvider(ctx)
        ProviderId.COMMAND_CODE -> CommandCodeProvider(ctx)
        ProviderId.OLLAMA, ProviderId.OLLAMA_CLOUD -> OllamaProvider(ctx)
        ProviderId.DEEPL -> DeepLProvider(ctx)
        ProviderId.DEEPGRAM -> DeepgramProvider(ctx)
        ProviderId.ASSEMBLYAI -> AssemblyAiProvider(ctx)
        ProviderId.CARTESIA -> CartesiaProvider(ctx)
        ProviderId.COHERE -> CohereProvider(ctx)
        ProviderId.OPENAI, ProviderId.GROQ, ProviderId.CEREBRAS, ProviderId.MISTRAL, ProviderId.DEEPSEEK,
        ProviderId.XAI, ProviderId.OPENROUTER, ProviderId.TOGETHER, ProviderId.FIREWORKS, ProviderId.SAMBANOVA,
        ProviderId.MOONSHOT, ProviderId.QWEN, ProviderId.ZAI, ProviderId.HUGGINGFACE, ProviderId.CUSTOM_OPENAI,
        -> OpenAiCompatibleProvider(ctx)
    }

    /** True if the provider can be used right now (key present when one is required). */
    fun isConfigured(id: ProviderId): Boolean {
        val spec = ProviderSpecs[id]
        return !spec.keyRequired || keys.has(id)
    }

    fun model(s: AppSettings, id: ProviderId, capability: Capability): String =
        s.models[AppSettings.modelKey(id, capability)] ?: ProviderSpecs[id].defaultModel(capability)

    fun voice(s: AppSettings, id: ProviderId, side: String): String? =
        s.voices[AppSettings.voiceKey(id, side)]?.takeIf { it.isNotBlank() }

    // ---------------------------------------------------------------- catalogs (cached)

    private data class CatalogEntry<T>(val at: Long, val items: List<T>)

    private val modelCache = ConcurrentHashMap<String, CatalogEntry<ModelInfo>>()
    private val voiceCache = ConcurrentHashMap<String, CatalogEntry<VoiceInfo>>()

    fun cachedModels(id: ProviderId, capability: Capability): List<ModelInfo>? = modelCache["$id/$capability"]?.items

    suspend fun models(id: ProviderId, capability: Capability, refresh: Boolean = false): List<ModelInfo> {
        val k = "$id/$capability"
        val cached = modelCache[k]
        if (!refresh && cached != null && System.currentTimeMillis() - cached.at < CATALOG_TTL) return cached.items
        val items = get(id).listModels(capability)
        modelCache[k] = CatalogEntry(System.currentTimeMillis(), items)
        return items
    }

    suspend fun voices(id: ProviderId, model: String?, language: Language?, refresh: Boolean = false): List<VoiceInfo> {
        val k = "$id/$model/${language?.code}"
        val cached = voiceCache[k]
        if (!refresh && cached != null && System.currentTimeMillis() - cached.at < CATALOG_TTL) return cached.items
        val items = get(id).listVoices(model, language)
        voiceCache[k] = CatalogEntry(System.currentTimeMillis(), items)
        return items
    }

    private companion object {
        const val CATALOG_TTL = 30 * 60 * 1000L
    }
}
