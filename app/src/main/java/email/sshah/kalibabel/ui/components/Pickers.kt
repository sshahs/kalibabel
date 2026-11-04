package email.sshah.kalibabel.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import email.sshah.kalibabel.AppContainer
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.data.Languages
import email.sshah.kalibabel.pipeline.userMessage
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ModelInfo
import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.providers.ProviderSpecs
import email.sshah.kalibabel.providers.VoiceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun LanguagePickerDialog(title: String, selected: String, onDismiss: () -> Unit, onSelect: (Language) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) Languages.all else Languages.all.filter {
            it.englishName.lowercase().contains(q) || it.nativeName.lowercase().contains(q) || it.tag.lowercase().startsWith(q)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column {
                SearchField(query, { query = it }, "Search languages")
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(filtered, key = { it.tag }) { lang ->
                        ListItem(
                            headlineContent = { Text(lang.englishName) },
                            supportingContent = { Text("${lang.nativeName} · ${lang.tag}") },
                            trailingContent = { if (lang.tag == selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { onSelect(lang) },
                        )
                    }
                }
            }
        },
    )
}

/** Picks which provider handles a capability. */
@Composable
fun ProviderPickerDialog(
    container: AppContainer,
    capability: Capability,
    selected: ProviderId,
    onDismiss: () -> Unit,
    onSelect: (ProviderId) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("${capability.label} provider") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(ProviderSpecs.supporting(capability), key = { it.id }) { spec ->
                    val configured = container.registry.isConfigured(spec.id)
                    ListItem(
                        headlineContent = { Text(spec.displayName) },
                        supportingContent = {
                            Text(
                                if (configured) spec.description else "Needs an API key · ${spec.description}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = { RadioButton(selected = spec.id == selected, onClick = { onSelect(spec.id) }) },
                        trailingContent = { if (!configured) Icon(Icons.Default.Key, contentDescription = "API key needed") },
                        modifier = Modifier.clickable { onSelect(spec.id) },
                    )
                }
            }
        },
    )
}

/**
 * Generic searchable catalog picker with refresh and free-text entry, used for models and voices.
 * [load] is called with refresh=false first (cache allowed), then with true on the refresh button.
 */
@Composable
fun <T> CatalogPickerDialog(
    title: String,
    selected: String?,
    fallback: List<T>,
    id: (T) -> String,
    label: (T) -> String,
    detail: (T) -> String?,
    load: suspend (refresh: Boolean) -> List<T>,
    allowCustom: Boolean,
    automaticLabel: String? = null,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var items by remember { mutableStateOf<List<T>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        loading = true
        error = null
        try {
            val result = load(reload > 0)
            items = result.ifEmpty { fallback }.distinctBy(id)
            if (result.isEmpty()) error = "The provider returned no entries; showing suggestions."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            items = fallback.distinctBy(id)
            error = "${e.userMessage()} — showing suggestions."
        } finally {
            loading = false
        }
    }
    val filtered = remember(items, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) items else items.filter { (id(it) + " " + label(it) + " " + (detail(it) ?: "")).lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (allowCustom) {
                TextButton(enabled = custom.isNotBlank(), onClick = { onSelect(custom.trim()) }) { Text("Use custom") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { scope.launch { reload++ } }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchField(query, { query = it }, "Search")
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Box(Modifier.heightIn(max = 360.dp)) {
                    LazyColumn {
                        if (automaticLabel != null) {
                            item {
                                ListItem(
                                    headlineContent = { Text(automaticLabel) },
                                    leadingContent = { RadioButton(selected = selected.isNullOrBlank(), onClick = { onSelect("") }) },
                                    modifier = Modifier.clickable { onSelect("") },
                                )
                            }
                        }
                        items(filtered, key = { id(it) }) { item ->
                            ListItem(
                                headlineContent = { Text(label(item), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    val d = listOfNotNull(id(item).takeIf { it != label(item) }, detail(item)).joinToString(" · ")
                                    if (d.isNotBlank()) Text(d, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                leadingContent = { RadioButton(selected = id(item) == selected, onClick = { onSelect(id(item)) }) },
                                modifier = Modifier.clickable { onSelect(id(item)) },
                            )
                        }
                    }
                }
                if (allowCustom) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        singleLine = true,
                        label = { Text("Or enter an id") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}

@Composable
fun ModelPickerDialog(
    container: AppContainer,
    provider: ProviderId,
    capability: Capability,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val spec = ProviderSpecs[provider]
    CatalogPickerDialog(
        title = "${spec.displayName.substringBefore(" (")} · ${capability.short} model",
        selected = selected,
        fallback = spec.suggestedModels[capability].orEmpty().map { ModelInfo(it) },
        id = { it.id },
        label = { it.label },
        detail = { it.description },
        load = { refresh -> container.registry.models(provider, capability, refresh) },
        allowCustom = provider != ProviderId.ANDROID,
        onDismiss = onDismiss,
        onSelect = onSelect,
    )
}

@Composable
fun VoicePickerDialog(
    container: AppContainer,
    provider: ProviderId,
    model: String,
    language: Language,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val spec = ProviderSpecs[provider]
    CatalogPickerDialog<VoiceInfo>(
        title = "${spec.displayName.substringBefore(" (")} voice · ${language.englishName}",
        selected = selected,
        fallback = emptyList(),
        id = { it.id },
        label = { it.name },
        detail = { v ->
            val langs = if (v.languages.isNotEmpty()) v.languages.take(4).joinToString(", ") else null
            listOfNotNull(v.description, langs).joinToString(" · ").ifBlank { null }
        },
        load = { refresh ->
            val all = container.registry.voices(provider, model.ifBlank { null }, language, refresh)
            // Voices tagged for this language first, then multilingual/untagged ones.
            val (matching, others) = all.partition { v -> v.languages.any { Languages.matches(language, it) } }
            matching + others.filter { it.languages.isEmpty() } + others.filter { it.languages.isNotEmpty() }
        },
        allowCustom = provider != ProviderId.ANDROID,
        automaticLabel = "Automatic (provider default for ${language.englishName})",
        onDismiss = onDismiss,
        onSelect = onSelect,
    )
}
