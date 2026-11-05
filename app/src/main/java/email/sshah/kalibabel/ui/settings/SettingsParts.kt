package email.sshah.kalibabel.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import email.sshah.kalibabel.AppContainer
import email.sshah.kalibabel.data.AppSettings
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.providers.ProviderSpecs
import email.sshah.kalibabel.ui.components.BackTopBar
import email.sshah.kalibabel.ui.components.ClickableSetting
import email.sshah.kalibabel.ui.components.ModelPickerDialog
import email.sshah.kalibabel.ui.components.ProviderPickerDialog
import email.sshah.kalibabel.ui.components.VoicePickerDialog

/** Scaffold shared by the settings sub-pages. */
@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), content = content)
    }
}

/** A hub entry: icon, title and a one-line summary of the current setup. */
@Composable
internal fun HubRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit, warning: Boolean = false) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                summary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                if (warning) Icons.Default.Warning else icon,
                contentDescription = null,
                tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Tuning options, collapsed by default so the page shows what most people change. */
@Composable
internal fun AdvancedSection(summary: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(top = 12.dp))
    ListItem(
        headlineContent = { Text("Advanced") },
        supportingContent = { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (open) "Hide" else "Show") },
        modifier = Modifier.clickable { open = !open },
    )
    AnimatedVisibility(open) { Column(content = content) }
}

private sealed interface PickerDialog {
    data object Provider : PickerDialog

    data object Model : PickerDialog

    data class Voice(val side: String) : PickerDialog
}

/** Provider, model and (for speech output) per-language voice rows, with their pickers. */
@Composable
internal fun ProviderRows(container: AppContainer, capability: Capability) {
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val keysVersion by container.keys.version.collectAsStateWithLifecycle()
    val update = container.settings::update
    var dialog by remember { mutableStateOf<PickerDialog?>(null) }

    val id = s.providerFor(capability)
    val spec = ProviderSpecs[id]
    val configured = container.registry.isConfigured(id).also { keysVersion }
    ClickableSetting(
        title = "Provider",
        value = spec.displayName + if (configured) "" else " — API key needed (Providers & API keys)",
        onClick = { dialog = PickerDialog.Provider },
        leading = if (configured) null else ({ Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }),
    )
    val model = container.registry.model(s, id, capability)
    if (!(id == ProviderId.ANDROID && capability == Capability.TRANSLATION)) {
        ClickableSetting(
            title = if (id == ProviderId.ANDROID && capability == Capability.TTS) "Engine" else "Model",
            value = model.ifBlank { "Default" },
            onClick = { dialog = PickerDialog.Model },
        )
    }
    if (capability == Capability.TTS && spec.hasVoices) {
        for ((side, language) in listOf("A" to s.langA, "B" to s.langB)) {
            ClickableSetting(
                title = "Voice for ${language.englishName}",
                value = container.registry.voice(s, id, side) ?: "Automatic",
                onClick = { dialog = PickerDialog.Voice(side) },
            )
        }
    }

    when (val d = dialog) {
        PickerDialog.Provider -> ProviderPickerDialog(
            container, capability, id,
            onDismiss = { dialog = null },
            onSelect = { picked ->
                update {
                    when (capability) {
                        Capability.STT -> it.copy(sttProvider = picked)
                        Capability.TRANSLATION -> it.copy(translationProvider = picked)
                        Capability.TTS -> it.copy(ttsProvider = picked)
                    }
                }
                dialog = null
            },
        )
        PickerDialog.Model -> ModelPickerDialog(
            container, id, capability, model,
            onDismiss = { dialog = null },
            onSelect = { picked ->
                update { it.copy(models = it.models + (AppSettings.modelKey(id, capability) to picked)) }
                dialog = null
            },
        )
        is PickerDialog.Voice -> VoicePickerDialog(
            container, id, model,
            if (d.side == "A") s.langA else s.langB,
            container.registry.voice(s, id, d.side),
            onDismiss = { dialog = null },
            onSelect = { voice ->
                update { it.copy(voices = it.voices + (AppSettings.voiceKey(id, d.side) to voice)) }
                dialog = null
            },
        )
        null -> Unit
    }
}

@Composable
internal fun TextSetting(title: String, hint: String, value: String, onChange: (String) -> Unit) {
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
