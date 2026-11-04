package email.sshah.kalibabel.ui.providers

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import email.sshah.kalibabel.AppContainer
import email.sshah.kalibabel.data.AppSettings
import email.sshah.kalibabel.data.ProviderPrefs
import email.sshah.kalibabel.pipeline.userMessage
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.providers.ProviderSpec
import email.sshah.kalibabel.providers.ProviderSpecs
import email.sshah.kalibabel.ui.components.BackTopBar
import email.sshah.kalibabel.ui.components.ClickableSetting
import email.sshah.kalibabel.ui.components.ModelPickerDialog
import email.sshah.kalibabel.ui.components.SectionHeader
import email.sshah.kalibabel.ui.components.SwitchSetting
import email.sshah.kalibabel.ui.components.VoicePickerDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProvidersScreen(container: AppContainer, onBack: () -> Unit, onOpen: (ProviderId) -> Unit) {
    val keysVersion by container.keys.version.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<Capability?>(null) }
    Scaffold(topBar = { BackTopBar("Providers", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                Capability.entries.forEach { cap ->
                    FilterChip(selected = filter == cap, onClick = { filter = if (filter == cap) null else cap }, label = { Text(cap.short) })
                }
            }
            val list = ProviderSpecs.all.filter { filter == null || filter in it.capabilities }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { spec ->
                    keysVersion // recompose on key changes
                    ProviderRow(spec, container.registry.isConfigured(spec.id)) { onOpen(spec.id) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(spec: ProviderSpec, configured: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(spec.displayName) },
        supportingContent = {
            Column {
                Text(spec.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    spec.capabilities.sortedBy { it.ordinal }.joinToString(" · ") { it.short } +
                        if (spec.privacy != null) " · privacy toggle" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailingContent = {
            when {
                !spec.keyRequired -> Text("no key", style = MaterialTheme.typography.labelSmall)
                configured -> Icon(Icons.Default.CheckCircle, contentDescription = "Configured", tint = MaterialTheme.colorScheme.primary)
                else -> Icon(Icons.Default.Key, contentDescription = "Needs key", tint = MaterialTheme.colorScheme.outline)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun ProviderDetailScreen(container: AppContainer, id: ProviderId, onBack: () -> Unit) {
    val spec = ProviderSpecs[id]
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val prefs = s.prefs(id)
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val context = LocalContext.current

    var key by remember { mutableStateOf(container.keys.get(id).orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(prefs.baseUrl.orEmpty()) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var modelDialog by remember { mutableStateOf<Capability?>(null) }
    var voiceDialog by remember { mutableStateOf<String?>(null) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    var regionMenu by remember { mutableStateOf(false) }

    fun updatePrefs(transform: (ProviderPrefs) -> ProviderPrefs) {
        container.settings.update { it.copy(providers = it.providers + (id.name to transform(it.prefs(id)))) }
    }

    Scaffold(topBar = { BackTopBar(spec.displayName, onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text(spec.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.capabilities.sortedBy { it.ordinal }.forEach { SuggestionChip(onClick = {}, label = { Text(it.label) }) }
            }

            if (id != ProviderId.ANDROID) {
                SectionHeader("Connection")
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (spec.keyRequired) "API key" else "API key (optional)") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Show key")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        container.keys.put(id, key)
                        testResult = null
                    }) { Text("Save key") }
                    Spacer(Modifier.padding(4.dp))
                    if (container.keys.has(id)) {
                        TextButton(onClick = {
                            container.keys.put(id, null)
                            key = ""
                        }) { Text("Remove") }
                    }
                    spec.keyUrl?.let { url ->
                        TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("Get a key") }
                    }
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        updatePrefs { p -> p.copy(baseUrl = it.trim().ifBlank { null }) }
                    },
                    label = { Text(if (spec.baseUrlEditable) "Server URL" else "Base URL override (optional)") },
                    placeholder = { Text(spec.defaultBaseUrl) },
                    singleLine = true,
                    supportingText = {
                        val effective = baseUrl.ifBlank { spec.defaultBaseUrl }
                        if (effective.startsWith("http://") && !isPrivateHost(effective)) {
                            Text("Warning: unencrypted HTTP to a public host exposes your key and audio.", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Default: ${spec.defaultBaseUrl}")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )

                if (spec.regions.isNotEmpty()) {
                    val current = spec.regions.firstOrNull { it.id == prefs.region } ?: spec.regions.first()
                    ClickableSetting("Region / data residency", "${current.label} — ${current.baseUrl}", onClick = { regionMenu = true })
                    DropdownMenu(expanded = regionMenu, onDismissRequest = { regionMenu = false }) {
                        spec.regions.forEach { r ->
                            DropdownMenuItem(text = { Text(r.label) }, onClick = {
                                updatePrefs { it.copy(region = r.id) }
                                regionMenu = false
                            })
                        }
                    }
                }
            }

            spec.privacy?.let { toggle ->
                SectionHeader("Privacy")
                SwitchSetting(toggle.title, toggle.description, prefs.privacy) { v -> updatePrefs { it.copy(privacy = v) } }
            }

            SectionHeader("Test")
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !testing, onClick = {
                    if (id != ProviderId.ANDROID) container.keys.put(id, key)
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = try {
                            true to container.registry.get(id).test()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false to e.userMessage()
                        }
                        testing = false
                    }
                }) { Text(if (testing) "Testing…" else "Test connection") }
            }
            testResult?.let { (ok, msg) ->
                Text(
                    msg,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            SectionHeader("Use ${spec.displayName.substringBefore(" (")} for")
            spec.capabilities.sortedBy { it.ordinal }.forEach { cap ->
                val inUse = s.providerFor(cap) == id
                SwitchSetting(cap.label, if (inUse) "In use" else null, inUse) { on ->
                    if (on) {
                        container.settings.update {
                            when (cap) {
                                Capability.STT -> it.copy(sttProvider = id)
                                Capability.TRANSLATION -> it.copy(translationProvider = id)
                                Capability.TTS -> it.copy(ttsProvider = id)
                            }
                        }
                    }
                }
            }

            SectionHeader("Models")
            spec.capabilities.sortedBy { it.ordinal }.forEach { cap ->
                if (id == ProviderId.ANDROID && cap == Capability.TRANSLATION) return@forEach
                ClickableSetting(
                    title = "${cap.label} ${if (id == ProviderId.ANDROID && cap == Capability.TTS) "engine" else "model"}",
                    value = container.registry.model(s, id, cap).ifBlank { "Default" },
                    onClick = { modelDialog = cap },
                )
            }
            if (Capability.TTS in spec.capabilities && spec.hasVoices) {
                ClickableSetting("Voice for ${s.langA.englishName}", container.registry.voice(s, id, "A") ?: "Automatic", onClick = { voiceDialog = "A" })
                ClickableSetting("Voice for ${s.langB.englishName}", container.registry.voice(s, id, "B") ?: "Automatic", onClick = { voiceDialog = "B" })
            }

            if (id == ProviderId.ANDROID) {
                SectionHeader("Offline")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedButton(onClick = {
                        downloadStatus = "Downloading ${s.langA.englishName} ↔ ${s.langB.englishName}…"
                        scope.launch {
                            downloadStatus = try {
                                container.registry.onDeviceTranslator.download(s.langA, s.langB)
                                "Offline translation ready for ${s.langA.englishName} ↔ ${s.langB.englishName}."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                "Download failed: ${e.userMessage()}"
                            }
                        }
                    }) { Text("Download translation packs for current pair") }
                    downloadStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        try {
                            context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                            downloadStatus = "Text-to-speech settings are not available on this device."
                        }
                    }) { Text("System text-to-speech settings") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    modelDialog?.let { cap ->
        ModelPickerDialog(
            container, id, cap, container.registry.model(s, id, cap),
            onDismiss = { modelDialog = null },
            onSelect = { m ->
                container.settings.update { it.copy(models = it.models + (AppSettings.modelKey(id, cap) to m)) }
                modelDialog = null
            },
        )
    }
    voiceDialog?.let { side ->
        VoicePickerDialog(
            container, id, container.registry.model(s, id, Capability.TTS),
            if (side == "A") s.langA else s.langB,
            container.registry.voice(s, id, side),
            onDismiss = { voiceDialog = null },
            onSelect = { v ->
                container.settings.update { it.copy(voices = it.voices + (AppSettings.voiceKey(id, side) to v)) }
                voiceDialog = null
            },
        )
    }
}

private fun isPrivateHost(url: String): Boolean {
    val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
    return host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
        Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) || host.endsWith(".local") || host.endsWith(".lan") ||
        host.startsWith("100.") // Tailscale CGNAT range
}
