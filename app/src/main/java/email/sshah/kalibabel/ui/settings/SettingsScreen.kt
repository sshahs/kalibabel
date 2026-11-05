package email.sshah.kalibabel.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import email.sshah.kalibabel.AppContainer
import email.sshah.kalibabel.BuildConfig
import email.sshah.kalibabel.data.AppSettings
import email.sshah.kalibabel.data.ErrorLog
import email.sshah.kalibabel.data.LogLevel
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.providers.ProviderSpecs
import email.sshah.kalibabel.ui.components.BackTopBar

/** Settings pages reachable from the hub (navigation routes). */
object SettingsRoutes {
    const val PROVIDERS = "providers"
    const val LISTENING = "settings/listening"
    const val TRANSLATION = "settings/translation"
    const val VOICE = "settings/voice"
    const val DISPLAY = "settings/display"
    const val DIAGNOSTICS = "settings/diagnostics"
}

/** Top-level settings: one row per area with a summary of what is set there. */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val keysVersion by container.keys.version.collectAsStateWithLifecycle()
    val log by ErrorLog.entries.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val registry = container.registry

    fun missingKey(capability: Capability): Boolean = !registry.isConfigured(s.providerFor(capability)).also { keysVersion }

    Scaffold(topBar = { BackTopBar("Settings", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val keys = ProviderSpecs.all.count { it.keyRequired && registry.isConfigured(it.id) }.also { keysVersion }
            HubRow(
                icon = Icons.Default.Key,
                title = "Providers & API keys",
                summary = (if (keys == 0) "No keys saved yet" else "$keys ${if (keys == 1) "key" else "keys"} saved") +
                    " · ${ProviderSpecs.all.size} providers",
                onClick = { onOpen(SettingsRoutes.PROVIDERS) },
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            HubRow(
                icon = Icons.Default.Mic,
                title = "Listening",
                summary = pipelineSummary(container, s, Capability.STT, s.listenMode.label),
                warning = missingKey(Capability.STT),
                onClick = { onOpen(SettingsRoutes.LISTENING) },
            )
            HubRow(
                icon = Icons.Default.Translate,
                title = "Translation",
                summary = pipelineSummary(container, s, Capability.TRANSLATION, if (s.glossaryEntries.isNotEmpty()) "glossary on" else null),
                warning = missingKey(Capability.TRANSLATION),
                onClick = { onOpen(SettingsRoutes.TRANSLATION) },
            )
            HubRow(
                icon = Icons.Default.RecordVoiceOver,
                title = "Voice",
                summary = pipelineSummary(container, s, Capability.TTS, if (s.autoSpeak) null else "not spoken automatically"),
                warning = missingKey(Capability.TTS),
                onClick = { onOpen(SettingsRoutes.VOICE) },
            )
            HubRow(
                icon = Icons.Default.Visibility,
                title = "Display",
                summary = listOfNotNull(
                    if (s.faceToFace) "Face-to-face" else "Chat layout",
                    "original text".takeIf { s.showOriginal },
                    "timings".takeIf { s.showLatency },
                ).joinToString(" · "),
                onClick = { onOpen(SettingsRoutes.DISPLAY) },
            )
            HubRow(
                icon = Icons.Default.BugReport,
                title = "Diagnostics",
                summary = when {
                    log.isEmpty() -> "No problems recorded"
                    else -> {
                        val crashes = log.count { it.level == LogLevel.CRASH }
                        "${log.size} ${if (log.size == 1) "entry" else "entries"}" + if (crashes > 0) " · $crashes ${if (crashes == 1) "crash" else "crashes"}" else ""
                    }
                },
                onClick = { onOpen(SettingsRoutes.DIAGNOSTICS) },
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            HubRow(
                icon = Icons.Default.Info,
                title = "Releases & updates",
                summary = "KaliBabel ${BuildConfig.VERSION_NAME} · ${BuildConfig.RELEASES_URL.substringAfter("://")}",
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
}

/** "ElevenLabs · scribe_v2_realtime · Auto-stop", or why it can't be used yet. */
private fun pipelineSummary(container: AppContainer, s: AppSettings, capability: Capability, extra: String?): String {
    val id = s.providerFor(capability)
    val name = ProviderSpecs[id].displayName
    if (!container.registry.isConfigured(id)) return "$name · API key needed"
    val model = container.registry.model(s, id, capability).takeUnless { id == ProviderId.ANDROID || it.isBlank() }
    return listOfNotNull(name, model, extra).joinToString(" · ")
}
