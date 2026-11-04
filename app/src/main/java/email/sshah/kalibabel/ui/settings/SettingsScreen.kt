package email.sshah.kalibabel.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import email.sshah.kalibabel.AppContainer
import email.sshah.kalibabel.BuildConfig
import email.sshah.kalibabel.data.AppSettings
import email.sshah.kalibabel.data.Formality
import email.sshah.kalibabel.data.ListenMode
import email.sshah.kalibabel.data.MicSource
import email.sshah.kalibabel.data.SpeechDetector
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.providers.ProviderSpecs
import email.sshah.kalibabel.ui.components.BackTopBar
import email.sshah.kalibabel.ui.components.ClickableSetting
import email.sshah.kalibabel.ui.components.ModelPickerDialog
import email.sshah.kalibabel.ui.components.ProviderPickerDialog
import email.sshah.kalibabel.ui.components.SectionHeader
import email.sshah.kalibabel.ui.components.SegmentedSetting
import email.sshah.kalibabel.ui.components.SliderSetting
import email.sshah.kalibabel.ui.components.SwitchSetting
import email.sshah.kalibabel.ui.components.VoicePickerDialog
import kotlin.math.roundToInt

private sealed interface SettingsDialog {
    data class Provider(val capability: Capability) : SettingsDialog

    data class Model(val provider: ProviderId, val capability: Capability) : SettingsDialog

    data class Voice(val provider: ProviderId, val side: String) : SettingsDialog
}

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenProviders: () -> Unit) {
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val keysVersion by container.keys.version.collectAsStateWithLifecycle()
    val update = container.settings::update
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val uriHandler = LocalUriHandler.current

    Scaffold(topBar = { BackTopBar("Settings", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ClickableSetting(
                title = "Providers & API keys",
                value = "${ProviderSpecs.all.count { container.registry.isConfigured(it.id) && it.keyRequired }.also { keysVersion }} keys configured · " +
                    "${ProviderSpecs.all.size} providers",
                onClick = onOpenProviders,
                leading = { Icon(Icons.Default.Key, contentDescription = null) },
            )
            HorizontalDivider()

            PipelineSection(container, s, Capability.STT, "Speech recognition", onDialog = { dialog = it })
            SwitchSetting(
                "Prefer realtime streaming",
                "Streams audio while you speak and shows live partial text (ElevenLabs Scribe Realtime, Deepgram, AssemblyAI).",
                s.preferStreamingStt,
            ) { v -> update { it.copy(preferStreamingStt = v) } }

            PipelineSection(container, s, Capability.TRANSLATION, "Translation", onDialog = { dialog = it })
            SegmentedSetting("Register", Formality.entries, s.formality, { it.label }) { v -> update { it.copy(formality = v) } }
            SliderSetting(
                "Conversation context",
                s.contextTurns.toFloat(), 0f..10f, 9,
                format = { if (it.roundToInt() == 0) "off" else "${it.roundToInt()} turns" },
            ) { v -> update { it.copy(contextTurns = v.roundToInt()) } }
            TextSetting(
                title = "Glossary",
                hint = "One per line: term = translation. Names to keep as-is on their own line. Also biases speech recognition.",
                value = s.glossary,
            ) { v -> update { it.copy(glossary = v) } }
            TextSetting(
                title = "Extra instructions for the translator",
                hint = "e.g. \"Medical context; keep units metric\"",
                value = s.customInstructions,
            ) { v -> update { it.copy(customInstructions = v) } }
            SwitchSetting(
                "Translate before you finish",
                "Starts translating as soon as your words stop changing, while the recogniser is still confirming the end " +
                    "of the sentence. Discarded if the final words differ (costs a few extra tokens).",
                s.speculativeTranslation,
            ) { v -> update { it.copy(speculativeTranslation = v) } }
            SwitchSetting(
                "Fall back to on-device",
                "If a cloud provider fails (no signal, rate limit), translate with ML Kit and speak with the system voice.",
                s.fallbackToOnDevice,
            ) { v -> update { it.copy(fallbackToOnDevice = v) } }

            PipelineSection(container, s, Capability.TTS, "Speech output", onDialog = { dialog = it })
            SwitchSetting("Speak translations automatically", null, s.autoSpeak) { v -> update { it.copy(autoSpeak = v) } }
            SwitchSetting(
                "Stream words into the voice",
                "ElevenLabs: sends the translation word by word over a WebSocket, so speech starts before the sentence " +
                    "is finished. Off = whole sentences.",
                s.streamingTts,
            ) { v -> update { it.copy(streamingTts = v) } }
            SwitchSetting(
                "Start speaking early",
                "Begin speaking at the first clause instead of waiting for a full sentence (lowest latency).",
                s.earlySpeech,
            ) { v -> update { it.copy(earlySpeech = v) } }
            SliderSetting("Speech rate", s.speechRate, 0.7f..1.5f, 15, format = { "×" + "%.2f".format(it) }) { v ->
                update { it.copy(speechRate = v) }
            }

            SectionHeader("Listening")
            SegmentedSetting(
                "Mode",
                ListenMode.entries,
                s.listenMode,
                { it.label },
                description = s.listenMode.description,
            ) { v -> update { it.copy(listenMode = v) } }
            SliderSetting(
                "End-of-speech pause",
                s.silenceMs.toFloat(), 300f..2000f, 16,
                format = { "${it.roundToInt()} ms" },
            ) { v -> update { it.copy(silenceMs = (v / 50).roundToInt() * 50) } }
            SegmentedSetting("Microphone", MicSource.entries, s.micSource, { it.label.substringBefore(" (") }, description = s.micSource.label) { v ->
                update { it.copy(micSource = v) }
            }
            SegmentedSetting(
                "Speech detector",
                SpeechDetector.entries,
                s.speechDetector,
                { it.label },
                description = s.speechDetector.description,
            ) { v -> update { it.copy(speechDetector = v) } }
            SwitchSetting("Noise suppression", "Use the device's noise suppressor when available.", s.noiseSuppression) { v ->
                update { it.copy(noiseSuppression = v) }
            }
            SwitchSetting(
                "Mute mic while speaking",
                "Prevents the app from translating its own voice in interpreter mode. Tapping a mic interrupts playback.",
                s.pauseMicWhileSpeaking,
            ) { v -> update { it.copy(pauseMicWhileSpeaking = v) } }

            SectionHeader("Display")
            SwitchSetting("Show original text", null, s.showOriginal) { v -> update { it.copy(showOriginal = v) } }
            SwitchSetting("Show latency breakdown", "Mouth-to-ear time and per-stage timings on each message.", s.showLatency) { v ->
                update { it.copy(showLatency = v) }
            }
            SwitchSetting("Keep screen on during conversations", null, s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
            SwitchSetting("Face-to-face layout", "Split screen with the top half facing the other person.", s.faceToFace) { v ->
                update { it.copy(faceToFace = v) }
            }

            SectionHeader("About")
            ClickableSetting(
                title = "Releases & updates",
                value = "KaliBabel ${BuildConfig.VERSION_NAME} · ${BuildConfig.RELEASES_URL.substringAfter("://")}",
                onClick = { runCatching { uriHandler.openUri(BuildConfig.RELEASES_URL) } },
            )
            Text(
                "API keys are encrypted with the Android Keystore and only sent to the provider they belong to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    when (val d = dialog) {
        is SettingsDialog.Provider -> ProviderPickerDialog(
            container, d.capability, s.providerFor(d.capability),
            onDismiss = { dialog = null },
            onSelect = { id ->
                update {
                    when (d.capability) {
                        Capability.STT -> it.copy(sttProvider = id)
                        Capability.TRANSLATION -> it.copy(translationProvider = id)
                        Capability.TTS -> it.copy(ttsProvider = id)
                    }
                }
                dialog = null
            },
        )
        is SettingsDialog.Model -> ModelPickerDialog(
            container, d.provider, d.capability, container.registry.model(s, d.provider, d.capability),
            onDismiss = { dialog = null },
            onSelect = { model ->
                update { it.copy(models = it.models + (AppSettings.modelKey(d.provider, d.capability) to model)) }
                dialog = null
            },
        )
        is SettingsDialog.Voice -> VoicePickerDialog(
            container, d.provider, container.registry.model(s, d.provider, Capability.TTS),
            if (d.side == "A") s.langA else s.langB,
            container.registry.voice(s, d.provider, d.side),
            onDismiss = { dialog = null },
            onSelect = { voice ->
                update { it.copy(voices = it.voices + (AppSettings.voiceKey(d.provider, d.side) to voice)) }
                dialog = null
            },
        )
        null -> Unit
    }
}

@Composable
private fun PipelineSection(container: AppContainer, s: AppSettings, capability: Capability, title: String, onDialog: (SettingsDialog) -> Unit) {
    val id = s.providerFor(capability)
    val spec = ProviderSpecs[id]
    val configured = container.registry.isConfigured(id)
    SectionHeader(title)
    ClickableSetting(
        title = "Provider",
        value = spec.displayName + if (configured) "" else " — API key needed",
        onClick = { onDialog(SettingsDialog.Provider(capability)) },
        leading = if (configured) null else ({ Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }),
    )
    val model = container.registry.model(s, id, capability)
    val showModel = !(id == ProviderId.ANDROID && capability == Capability.TRANSLATION)
    if (showModel) {
        ClickableSetting(
            title = if (id == ProviderId.ANDROID && capability == Capability.TTS) "Engine" else "Model",
            value = model.ifBlank { "Default" },
            onClick = { onDialog(SettingsDialog.Model(id, capability)) },
        )
    }
    if (capability == Capability.TTS && spec.hasVoices) {
        ClickableSetting(
            title = "Voice for ${s.langA.englishName}",
            value = container.registry.voice(s, id, "A") ?: "Automatic",
            onClick = { onDialog(SettingsDialog.Voice(id, "A")) },
        )
        ClickableSetting(
            title = "Voice for ${s.langB.englishName}",
            value = container.registry.voice(s, id, "B") ?: "Automatic",
            onClick = { onDialog(SettingsDialog.Voice(id, "B")) },
        )
    }
}

@Composable
private fun TextSetting(title: String, hint: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(title) },
        supportingText = { Text(hint) },
        minLines = 2,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
