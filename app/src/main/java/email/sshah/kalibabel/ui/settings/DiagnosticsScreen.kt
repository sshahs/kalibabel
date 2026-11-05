package email.sshah.kalibabel.ui.settings

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import email.sshah.kalibabel.data.ErrorLog
import email.sshah.kalibabel.data.LogEntry
import email.sshah.kalibabel.data.LogLevel
import email.sshah.kalibabel.ui.components.BackTopBar
import java.text.DateFormat
import java.util.Date

/** The on-device error log: what went wrong (or was worked around), with share and clear. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val entries by ErrorLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ErrorLog.markCrashSeen() }

    fun share() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "KaliBabel error log")
            .putExtra(Intent.EXTRA_TEXT, ErrorLog.export())
        runCatching { context.startActivity(Intent.createChooser(send, "Share error log")) }
    }

    Scaffold(topBar = { BackTopBar("Diagnostics", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Crashes and problems are kept on this phone only, with API keys removed. Share the log when reporting a problem.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::share, enabled = entries.isNotEmpty()) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Share log")
                        }
                        OutlinedButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Clear")
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        "No problems recorded.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            }
            items(entries.asReversed()) { LogCard(it) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the error log?") },
            confirmButton = {
                TextButton(onClick = {
                    ErrorLog.clear()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LogCard(entry: LogEntry) {
    var open by rememberSaveable(entry.time, entry.source) { mutableStateOf(false) }
    val color = when (entry.level) {
        LogLevel.CRASH, LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
    }
    Card(
        onClick = { open = !open },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).animateContentSize(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${entry.level.label} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.time))} · ${entry.version}",
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
            Text(entry.source, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp))
            Text(
                entry.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (open && entry.details.isNotBlank()) {
                Text(
                    entry.details,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (entry.details.isNotBlank()) {
                Text("Tap for details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
