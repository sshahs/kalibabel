package email.sshah.kalibabel.providers

import email.sshah.kalibabel.providers.Capability.STT
import email.sshah.kalibabel.providers.Capability.TRANSLATION
import email.sshah.kalibabel.providers.Capability.TTS

enum class ProviderId {
    ANDROID,
    OPENAI,
    ANTHROPIC,
    GEMINI,
    ELEVENLABS,
    COMMAND_CODE,
    OLLAMA_CLOUD,
    OLLAMA,
    DEEPL,
    DEEPGRAM,
    ASSEMBLYAI,
    CARTESIA,
    GROQ,
    CEREBRAS,
    MISTRAL,
    COHERE,
    DEEPSEEK,
    XAI,
    OPENROUTER,
    TOGETHER,
    FIREWORKS,
    SAMBANOVA,
    MOONSHOT,
    QWEN,
    ZAI,
    HUGGINGFACE,
    CUSTOM_OPENAI,
}

/** An optional privacy switch a provider exposes (ZDR, logging opt-out, data-collection opt-out). */
data class PrivacyToggle(val title: String, val description: String, val defaultOn: Boolean = false)

data class Region(val id: String, val label: String, val baseUrl: String)

/**
 * Static description of a provider. Behaviour lives in the classes under `providers.impl`;
 * everything the UI needs to render the provider list comes from here.
 */
data class ProviderSpec(
    val id: ProviderId,
    val displayName: String,
    val description: String,
    val capabilities: Set<Capability>,
    val defaultBaseUrl: String,
    val keyRequired: Boolean = true,
    /** Whether the user may (and should) change the base URL. */
    val baseUrlEditable: Boolean = false,
    val keyUrl: String? = null,
    val defaultModels: Map<Capability, String> = emptyMap(),
    val suggestedModels: Map<Capability, List<String>> = emptyMap(),
    val privacy: PrivacyToggle? = null,
    val regions: List<Region> = emptyList(),
    /** True when the TTS voice picker is meaningful for this provider. */
    val hasVoices: Boolean = TTS in capabilities,
    val streamingStt: Boolean = false,
    val notes: String? = null,
) {
    fun defaultModel(capability: Capability): String =
        defaultModels[capability] ?: suggestedModels[capability]?.firstOrNull() ?: ""

    fun supports(capability: Capability) = capability in capabilities
}

object ProviderSpecs {
    val all: List<ProviderSpec> = listOf(
        ProviderSpec(
            id = ProviderId.ANDROID,
            displayName = "On-device (Android)",
            description = "Free and offline-capable: Android speech recognizer, system text-to-speech and " +
                "ML Kit on-device translation. No key, no network once language packs are downloaded.",
            capabilities = setOf(STT, TRANSLATION, TTS),
            defaultBaseUrl = "",
            keyRequired = false,
            defaultModels = mapOf(STT to "default", TRANSLATION to "mlkit", TTS to ""),
            suggestedModels = mapOf(
                STT to listOf("default", "on-device"),
                TRANSLATION to listOf("mlkit"),
            ),
            streamingStt = true,
        ),
        ProviderSpec(
            id = ProviderId.OPENAI,
            displayName = "OpenAI",
            description = "GPT models for translation, gpt-4o-transcribe / Whisper for STT and gpt-4o-mini-tts for natural multilingual speech.",
            capabilities = setOf(STT, TRANSLATION, TTS),
            defaultBaseUrl = "https://api.openai.com/v1",
            keyUrl = "https://platform.openai.com/api-keys",
            defaultModels = mapOf(STT to "gpt-4o-mini-transcribe", TRANSLATION to "gpt-4.1-mini", TTS to "gpt-4o-mini-tts"),
            suggestedModels = mapOf(
                STT to listOf("gpt-4o-mini-transcribe", "gpt-4o-transcribe", "whisper-1"),
                TRANSLATION to listOf("gpt-4.1-mini", "gpt-4.1-nano", "gpt-4.1", "gpt-4o-mini", "gpt-5-mini", "gpt-5"),
                TTS to listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd"),
            ),
        ),
        ProviderSpec(
            id = ProviderId.ANTHROPIC,
            displayName = "Anthropic",
            description = "Claude models. Haiku is the low-latency choice; larger models for nuanced, idiomatic translation.",
            capabilities = setOf(TRANSLATION),
            defaultBaseUrl = "https://api.anthropic.com",
            keyUrl = "https://console.anthropic.com/settings/keys",
            defaultModels = mapOf(TRANSLATION to "claude-haiku-5-5"),
            suggestedModels = mapOf(TRANSLATION to listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-4-5")),
        ),
        ProviderSpec(
            id = ProviderId.GEMINI,
            displayName = "Google Gemini",
            description = "Gemini for translation, audio transcription and native multilingual TTS voices.",
            capabilities = setOf(STT, TRANSLATION, TTS),
            defaultBaseUrl = "https://generativelanguage.googleapis.com",
            keyUrl = "https://aistudio.google.com/apikey",
            defaultModels = mapOf(STT to "gemini-flash-lite-latest", TRANSLATION to "gemini-flash-latest", TTS to "gemini-2.5-flash-preview-tts"),
            suggestedModels = mapOf(
                STT to listOf("gemini-flash-lite-latest", "gemini-flash-latest", "gemini-2.5-flash"),
                TRANSLATION to listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-pro"),
                TTS to listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts"),
            ),
        ),
        ProviderSpec(
            id = ProviderId.ELEVENLABS,
            displayName = "ElevenLabs",
            description = "Best-in-class voices. Flash v2.5 (~75 ms) for live speech, v3 for expressiveness; " +
                "Scribe v2 for batch STT and Scribe v2 Realtime for streaming STT.",
            capabilities = setOf(STT, TTS),
            defaultBaseUrl = "https://api.elevenlabs.io",
            keyUrl = "https://elevenlabs.io/app/settings/api-keys",
            defaultModels = mapOf(STT to "scribe_v2_realtime", TTS to "eleven_flash_v2_5"),
            suggestedModels = mapOf(
                STT to listOf("scribe_v2_realtime", "scribe_v2", "scribe_v1"),
                TTS to listOf("eleven_flash_v2_5", "eleven_turbo_v2_5", "eleven_multilingual_v2", "eleven_v3"),
            ),
            privacy = PrivacyToggle(
                "Zero retention mode",
                "Sends enable_logging=false so ElevenLabs does not store requests or history. Enterprise plans only — other plans reject the request.",
            ),
            regions = listOf(
                Region("default", "Global (default)", "https://api.elevenlabs.io"),
                Region("us", "United States", "https://api.us.elevenlabs.io"),
                Region("eu", "EU data residency", "https://api.eu.residency.elevenlabs.io"),
                Region("in", "India data residency", "https://api.in.residency.elevenlabs.io"),
                Region("sg", "Singapore data residency", "https://api.sg.residency.elevenlabs.io"),
            ),
            streamingStt = true,
        ),
        ProviderSpec(
            id = ProviderId.COMMAND_CODE,
            displayName = "Command Code",
            description = "Command Code Provider API: one key for open, OpenAI-format and Claude models. " +
                "Claude models are routed to /messages automatically. Requires a plan with API access.",
            capabilities = setOf(TRANSLATION),
            defaultBaseUrl = "https://api.commandcode.ai/provider/v1",
            keyUrl = "https://commandcode.ai",
            defaultModels = mapOf(TRANSLATION to "deepseek/deepseek-v4-flash"),
            suggestedModels = mapOf(TRANSLATION to listOf("deepseek/deepseek-v4-flash", "claude-sonnet-4-6", "minimax-m3")),
            privacy = PrivacyToggle(
                "Zero data retention (ZDR)",
                "Sends x-cmd-zdr: 1 so requests are only routed to zero-data-retention upstreams and never used for training. " +
                    "Models without a ZDR upstream fail with HTTP 422 instead of silently falling back.",
            ),
        ),
        ProviderSpec(
            id = ProviderId.OLLAMA_CLOUD,
            displayName = "Ollama Cloud",
            description = "Large open models (gpt-oss, Qwen, DeepSeek, Gemma, Kimi…) hosted on ollama.com with your Ollama API key.",
            capabilities = setOf(TRANSLATION),
            defaultBaseUrl = "https://ollama.com",
            keyUrl = "https://ollama.com/settings/keys",
            defaultModels = mapOf(TRANSLATION to "gemma4:31b"),
            suggestedModels = mapOf(TRANSLATION to listOf("gemma4:31b", "gpt-oss:120b", "gpt-oss:20b", "qwen3-next:80b", "deepseek-v3.1:671b")),
        ),
        ProviderSpec(
            id = ProviderId.OLLAMA,
            displayName = "Ollama (self-hosted)",
            description = "Your own Ollama server on the LAN or via VPN. Models are pre-loaded and kept warm to avoid cold-start latency.",
            capabilities = setOf(TRANSLATION),
            defaultBaseUrl = "http://192.168.1.10:11434",
            keyRequired = false,
            baseUrlEditable = true,
            defaultModels = mapOf(TRANSLATION to "gemma3:4b"),
            suggestedModels = mapOf(TRANSLATION to listOf("gemma3:4b", "gemma3:12b", "qwen3:8b", "llama3.2:3b", "aya-expanse:8b")),
        ),
        ProviderSpec(
            id = ProviderId.DEEPL,
            displayName = "DeepL",
            description = "Dedicated machine translation: very fast and accurate for European and major Asian languages. " +
                "Free keys (ending in :fx) use api-free.deepl.com automatically.",
            capabilities = setOf(TRANSLATION),
            defaultBaseUrl = "https://api.deepl.com",
            keyUrl = "https://www.deepl.com/your-account/keys",
            defaultModels = mapOf(TRANSLATION to "latency_optimized"),
            suggestedModels = mapOf(TRANSLATION to listOf("latency_optimized", "prefer_quality_optimized", "quality_optimized")),
        ),
        ProviderSpec(
            id = ProviderId.DEEPGRAM,
            displayName = "Deepgram",
            description = "Nova-3 realtime streaming STT with endpointing (very low latency) and Aura-2 TTS voices.",
            capabilities = setOf(STT, TTS),
            defaultBaseUrl = "https://api.deepgram.com",
            keyUrl = "https://console.deepgram.com",
            defaultModels = mapOf(STT to "nova-3", TTS to "aura-2"),
            suggestedModels = mapOf(STT to listOf("nova-3", "nova-2", "whisper-large"), TTS to listOf("aura-2", "aura")),
            privacy = PrivacyToggle(
                "Opt out of Model Improvement Program",
                "Sends mip_opt_out=true so Deepgram does not keep your audio to train models (may affect pricing).",
            ),
            streamingStt = true,
        ),
        ProviderSpec(
            id = ProviderId.ASSEMBLYAI,
            displayName = "AssemblyAI",
            description = "Universal-Streaming realtime STT with turn detection, plus batch Universal models.",
            capabilities = setOf(STT),
            defaultBaseUrl = "https://api.assemblyai.com",
            keyUrl = "https://www.assemblyai.com/app/api-keys",
            defaultModels = mapOf(STT to "universal-streaming-multilingual"),
            suggestedModels = mapOf(
                STT to listOf("universal-streaming-multilingual", "universal-streaming-english", "universal", "slam-1"),
            ),
            hasVoices = false,
            regions = listOf(
                Region("default", "United States (default)", "https://api.assemblyai.com"),
                Region("eu", "EU data residency", "https://api.eu.assemblyai.com"),
            ),
            streamingStt = true,
        ),
        ProviderSpec(
            id = ProviderId.CARTESIA,
            displayName = "Cartesia",
            description = "Sonic TTS with ~40–90 ms time-to-first-audio and Ink-Whisper STT.",
            capabilities = setOf(STT, TTS),
            defaultBaseUrl = "https://api.cartesia.ai",
            keyUrl = "https://play.cartesia.ai/keys",
            defaultModels = mapOf(STT to "ink-whisper", TTS to "sonic-3"),
            suggestedModels = mapOf(STT to listOf("ink-whisper"), TTS to listOf("sonic-3", "sonic-2", "sonic-turbo")),
        ),
        ProviderSpec(
            id = ProviderId.GROQ,
            displayName = "Groq",
            description = "LPU inference: extremely fast Whisper large-v3-turbo STT, fast open LLMs and Orpheus TTS.",
            capabilities = setOf(STT, TRANSLATION, TTS),
            defaultBaseUrl = "https://api.groq.com/openai/v1",
            keyUrl = "https://console.groq.com/keys",
            defaultModels = mapOf(STT to "whisper-large-v3-turbo", TRANSLATION to "llama-3.3-70b-versatile", TTS to "canopylabs/orpheus-v1-english"),
            suggestedModels = mapOf(
                STT to listOf("whisper-large-v3-turbo", "whisper-large-v3"),
                TRANSLATION to listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "moonshotai/kimi-k2-instruct", "llama-3.1-8b-instant"),
                TTS to listOf("canopylabs/orpheus-v1-english", "canopylabs/orpheus-arabic-saudi"),
            ),
        ),
        ProviderSpec(
            id = ProviderId.CEREBRAS,
            displayName = "Cerebras",
            description = "Wafer-scale inference with some of the highest tokens/second available — great for instant translations.",
            capabilities = setOf(TRANSLATION),
            defaultBaseUrl = "https://api.cerebras.ai/v1",
            keyUrl = "https://cloud.cerebras.ai",
            defaultModels = mapOf(TRANSLATION to "llama-3.3-70b"),
            suggestedModels = mapOf(TRANSLATION to listOf("llama-3.3-70b", "gpt-oss-120b", "qwen-3-235b-a22b-instruct-2507", "llama3.1-8b")),
        ),
        ProviderSpec(
            id = ProviderId.MISTRAL,
            displayName = "Mistral AI",
            description = "Mistral models for translation and Voxtral for multilingual speech recognition.",
            capabilities = setOf(STT, TRANSLATION),
            defaultBaseUrl = "https://api.mistral.ai/v1",
            keyUrl = "https://console.mistral.ai/api-keys",
            defaultModels = mapOf(STT to "voxtral-mini-latest", TRANSLATION to "mistral-small-latest"),
            suggestedModels = mapOf(
                STT to listOf("voxtral-mini-latest", "voxtral-small-latest"),
                TRANSLATION to listOf("mistral-small-latest", "mistral-medium-latest", "mistral-large-latest", "ministral-8b-latest"),
            ),
        ),
        ProviderSpec(
            id = ProviderId.COHERE,
            displayName = "Cohere",
            description = "Includes Command A Translate, a model purpose-built for translation across 23 languages.",
            capabilities = setOf(TRANSLATION),
            defaultBaseUrl = "https://api.cohere.com",
            keyUrl = "https://dashboard.cohere.com/api-keys",
            defaultModels = mapOf(TRANSLATION to "command-a-translate-08-2025"),
            suggestedModels = mapOf(TRANSLATION to listOf("command-a-translate-08-2025", "command-a-03-2025", "command-r7b-12-2024")),
        ),
        openAiCompatible(
            ProviderId.DEEPSEEK, "DeepSeek", "DeepSeek chat models.", "https://api.deepseek.com/v1",
            "https://platform.deepseek.com/api_keys", listOf("deepseek-chat"),
        ),
        openAiCompatible(
            ProviderId.XAI, "xAI", "Grok models.", "https://api.x.ai/v1",
            "https://console.x.ai", listOf("grok-4-fast-non-reasoning", "grok-3-mini", "grok-4"),
        ),
        ProviderSpec(
            id = ProviderId.OPENROUTER,
            displayName = "OpenRouter",
            description = "Hundreds of models from every major lab behind one key, with automatic failover.",
            capabilities = setOf(TRANSLATION),
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            keyUrl = "https://openrouter.ai/settings/keys",
            defaultModels = mapOf(TRANSLATION to "google/gemini-2.5-flash"),
            suggestedModels = mapOf(
                TRANSLATION to listOf("google/gemini-2.5-flash", "anthropic/claude-haiku-4.5", "openai/gpt-4.1-mini", "meta-llama/llama-3.3-70b-instruct"),
            ),
            privacy = PrivacyToggle(
                "Zero data retention only",
                "Sends provider.zdr=true and data_collection=deny so OpenRouter only routes to endpoints that do not retain or train on prompts.",
            ),
        ),
        ProviderSpec(
            id = ProviderId.TOGETHER,
            displayName = "Together AI",
            description = "Open models for translation, Whisper STT and hosted TTS models.",
            capabilities = setOf(STT, TRANSLATION, TTS),
            defaultBaseUrl = "https://api.together.xyz/v1",
            keyUrl = "https://api.together.ai/settings/api-keys",
            defaultModels = mapOf(STT to "openai/whisper-large-v3", TRANSLATION to "meta-llama/Llama-3.3-70B-Instruct-Turbo", TTS to "cartesia/sonic-2"),
            suggestedModels = mapOf(
                STT to listOf("openai/whisper-large-v3"),
                TRANSLATION to listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Qwen/Qwen2.5-72B-Instruct-Turbo", "deepseek-ai/DeepSeek-V3"),
                TTS to listOf("cartesia/sonic-2", "hexgrad/Kokoro-82M"),
            ),
        ),
        openAiCompatible(
            ProviderId.FIREWORKS, "Fireworks AI", "Fast open-model inference.", "https://api.fireworks.ai/inference/v1",
            "https://fireworks.ai/account/api-keys",
            listOf("accounts/fireworks/models/llama-v3p3-70b-instruct", "accounts/fireworks/models/deepseek-v3"),
        ),
        openAiCompatible(
            ProviderId.SAMBANOVA, "SambaNova", "RDU inference with very high throughput for open models.", "https://api.sambanova.ai/v1",
            "https://cloud.sambanova.ai/apis", listOf("Meta-Llama-3.3-70B-Instruct", "DeepSeek-V3-0324", "Llama-4-Maverick-17B-128E-Instruct"),
        ),
        openAiCompatible(
            ProviderId.MOONSHOT, "Moonshot AI (Kimi)", "Kimi models — strong for Chinese ↔ English.", "https://api.moonshot.ai/v1",
            "https://platform.moonshot.ai/console/api-keys", listOf("kimi-k2-turbo-preview", "kimi-k2-0905-preview", "moonshot-v1-8k"),
        ),
        openAiCompatible(
            ProviderId.QWEN, "Alibaba Qwen (DashScope)", "Qwen models via the international DashScope OpenAI-compatible endpoint, incl. Qwen-MT.",
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            "https://modelstudio.console.alibabacloud.com", listOf("qwen-mt-turbo", "qwen-flash", "qwen-plus", "qwen-max"),
        ),
        openAiCompatible(
            ProviderId.ZAI, "Z.ai (GLM)", "GLM models from Zhipu / Z.ai.", "https://api.z.ai/api/paas/v4",
            "https://z.ai/manage-apikey/apikey-list", listOf("glm-4.5-air", "glm-4.6", "glm-4.5-flash"),
        ),
        openAiCompatible(
            ProviderId.HUGGINGFACE, "Hugging Face", "Inference Providers router: thousands of open models with one HF token.", "https://router.huggingface.co/v1",
            "https://huggingface.co/settings/tokens", listOf("meta-llama/Llama-3.3-70B-Instruct", "Qwen/Qwen3-235B-A22B-Instruct-2507"),
        ),
        ProviderSpec(
            id = ProviderId.CUSTOM_OPENAI,
            displayName = "Custom (OpenAI-compatible)",
            description = "Any OpenAI-compatible server: LM Studio, vLLM, LiteLLM, LocalAI, Speaches, Kokoro-FastAPI… " +
                "Supports chat, /audio/transcriptions and /audio/speech when the server does.",
            capabilities = setOf(STT, TRANSLATION, TTS),
            defaultBaseUrl = "http://192.168.1.10:1234/v1",
            keyRequired = false,
            baseUrlEditable = true,
            defaultModels = mapOf(STT to "whisper-1", TTS to "tts-1"),
            suggestedModels = mapOf(STT to listOf("whisper-1"), TTS to listOf("tts-1", "kokoro")),
        ),
    )

    private val byId = all.associateBy { it.id }

    operator fun get(id: ProviderId): ProviderSpec = byId.getValue(id)

    fun supporting(capability: Capability): List<ProviderSpec> = all.filter { capability in it.capabilities }

    private fun openAiCompatible(
        id: ProviderId,
        name: String,
        description: String,
        baseUrl: String,
        keyUrl: String,
        models: List<String>,
    ) = ProviderSpec(
        id = id,
        displayName = name,
        description = description,
        capabilities = setOf(TRANSLATION),
        defaultBaseUrl = baseUrl,
        keyUrl = keyUrl,
        defaultModels = mapOf(TRANSLATION to models.first()),
        suggestedModels = mapOf(TRANSLATION to models),
    )
}
