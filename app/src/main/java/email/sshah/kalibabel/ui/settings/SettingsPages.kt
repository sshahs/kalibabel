package email.sshah.kalibabel.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import email.sshah.kalibabel.AppContainer
import email.sshah.kalibabel.data.Formality
import email.sshah.kalibabel.data.ListenMode
import email.sshah.kalibabel.data.MicSource
import email.sshah.kalibabel.data.SpeechDetector
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.ui.components.SectionHeader
import email.sshah.kalibabel.ui.components.SegmentedSetting
import email.sshah.kalibabel.ui.components.SliderSetting
import email.sshah.kalibabel.ui.components.SwitchSetting
import kotlin.math.roundToInt

@Composable
fun ListeningSettings(container: AppContainer, onBack: () -> Unit) {
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val update = container.settings::update
    SettingsPage("Listening", onBack) {
        SectionHeader("Speech recognition")
        ProviderRows(container, Capability.STT)

        SectionHeader("When to translate")
        SegmentedSetting("Mode", ListenMode.entries, s.listenMode, { it.label }, description = s.listenMode.description) { v ->
            update { it.copy(listenMode = v) }
        }
        SliderSetting(
            "Pause that ends a sentence",
            s.silenceMs.toFloat(), 300f..2000f, 16,
            format = { "${it.roundToInt()} ms" },
        ) { v -> update { it.copy(silenceMs = (v / 50).roundToInt() * 50) } }

        AdvancedSection("Live transcription, speech detector, microphone, noise") {
            SwitchSetting(
                "Live transcription",
                "Shows words while you speak (ElevenLabs Scribe Realtime, Deepgram, AssemblyAI).",
                s.preferStreamingStt,
            ) { v -> update { it.copy(preferStreamingStt = v) } }
            SegmentedSetting("Speech detector", SpeechDetector.entries, s.speechDetector, { it.label }, description = s.speechDetector.description) { v ->
                update { it.copy(speechDetector = v) }
            }
            SegmentedSetting("Microphone", MicSource.entries, s.micSource, { it.label.substringBefore(" (") }, description = s.micSource.label) { v ->
                update { it.copy(micSource = v) }
            }
            SwitchSetting("Noise suppression", "Use the phone's noise suppressor when available.", s.noiseSuppression) { v ->
                update { it.copy(noiseSuppression = v) }
            }
            SwitchSetting(
                "Mute mic while speaking",
                "Stops the app hearing its own voice. Tap a mic to interrupt.",
                s.pauseMicWhileSpeaking,
            ) { v -> update { it.copy(pauseMicWhileSpeaking = v) } }
        }
    }
}

@Composable
fun TranslationSettings(container: AppContainer, onBack: () -> Unit) {
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val update = container.settings::update
    SettingsPage("Translation", onBack) {
        SectionHeader("Translator")
        ProviderRows(container, Capability.TRANSLATION)

        SectionHeader("Style")
        SegmentedSetting("Register", Formality.entries, s.formality, { it.label }) { v -> update { it.copy(formality = v) } }
        TextSetting(
            title = "Glossary",
            hint = "One per line: term = translation. A name on its own line is kept as-is. Also helps speech recognition.",
            value = s.glossary,
        ) { v -> update { it.copy(glossary = v) } }
        TextSetting(
            title = "Extra instructions",
            hint = "e.g. \"Medical context; keep units metric\"",
            value = s.customInstructions,
        ) { v -> update { it.copy(customInstructions = v) } }

        AdvancedSection("Context, early translation, offline fallback") {
            SliderSetting(
                "Earlier turns used as context",
                s.contextTurns.toFloat(), 0f..10f, 9,
                format = { if (it.roundToInt() == 0) "off" else "${it.roundToInt()}" },
            ) { v -> update { it.copy(contextTurns = v.roundToInt()) } }
            SwitchSetting(
                "Translate before you finish",
                "Starts as soon as your words stop changing. Faster; costs a few extra tokens.",
                s.speculativeTranslation,
            ) { v -> update { it.copy(speculativeTranslation = v) } }
            SwitchSetting(
                "Fall back to on-device",
                "If a provider fails, translate with ML Kit and speak with the system voice.",
                s.fallbackToOnDevice,
            ) { v -> update { it.copy(fallbackToOnDevice = v) } }
        }
    }
}

@Composable
fun VoiceSettings(container: AppContainer, onBack: () -> Unit) {
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val update = container.settings::update
    SettingsPage("Voice", onBack) {
        SwitchSetting("Speak translations aloud", "Off: translations are only shown.", s.autoSpeak) { v -> update { it.copy(autoSpeak = v) } }

        SectionHeader("Voice")
        ProviderRows(container, Capability.TTS)
        SliderSetting("Speed", s.speechRate, 0.7f..1.5f, 15, format = { "×" + "%.2f".format(it) }) { v ->
            update { it.copy(speechRate = v) }
        }

        AdvancedSection("Word streaming, early start") {
            SwitchSetting(
                "Stream words into the voice",
                "ElevenLabs: speech starts before the sentence is finished.",
                s.streamingTts,
            ) { v -> update { it.copy(streamingTts = v) } }
            SwitchSetting(
                "Start speaking early",
                "Begin at the first clause instead of the full sentence.",
                s.earlySpeech,
            ) { v -> update { it.copy(earlySpeech = v) } }
        }
    }
}

@Composable
fun DisplaySettings(container: AppContainer, onBack: () -> Unit) {
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val update = container.settings::update
    SettingsPage("Display", onBack) {
        SwitchSetting("Face-to-face layout", "Split screen; the top half faces the other person.", s.faceToFace) { v ->
            update { it.copy(faceToFace = v) }
        }
        SwitchSetting("Show original text", "Under each translation.", s.showOriginal) { v -> update { it.copy(showOriginal = v) } }
        SwitchSetting("Show timings", "How long each step took, on every message.", s.showLatency) { v ->
            update { it.copy(showLatency = v) }
        }
        SwitchSetting("Keep screen on", "While listening or speaking.", s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
    }
}
