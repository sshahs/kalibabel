@file:OptIn(ExperimentalMaterial3Api::class)

package email.sshah.kalibabel.ui.conversation

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import email.sshah.kalibabel.AppContainer
import email.sshah.kalibabel.data.AppSettings
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.data.Languages
import email.sshah.kalibabel.pipeline.Direction
import email.sshah.kalibabel.pipeline.InterpreterState
import email.sshah.kalibabel.pipeline.Turn
import email.sshah.kalibabel.pipeline.TurnStage
import email.sshah.kalibabel.providers.ProviderSpecs
import email.sshah.kalibabel.ui.components.LanguagePickerDialog
import kotlinx.coroutines.launch

@Composable
fun ConversationScreen(
    container: AppContainer,
    incomingText: String?,
    onIncomingConsumed: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val interpreter = container.interpreter
    val state by interpreter.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var pendingMic by remember { mutableStateOf<Direction?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val dir = pendingMic
        pendingMic = null
        if (granted && dir != null) interpreter.toggle(dir)
    }
    fun mic(direction: Direction) {
        val needsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingMic = direction
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            interpreter.toggle(direction)
        }
    }

    LaunchedEffect(Unit) { interpreter.warmUp() }
    LaunchedEffect(incomingText) {
        if (!incomingText.isNullOrBlank()) {
            interpreter.translateText(incomingText, Direction.AUTO)
            onIncomingConsumed()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            interpreter.dismissError()
        }
    }

    // Keep the screen awake while a conversation is in progress.
    val view = LocalView.current
    val busy = state.listening != null || state.speakingTurn != null || state.turns.lastOrNull()?.active == true
    DisposableEffect(busy, settings.keepScreenOn) {
        view.keepScreenOn = busy && settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    var picking by remember { mutableStateOf<String?>(null) } // "A" or "B"
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KaliBabel") },
                actions = {
                    IconButton(onClick = { container.settings.update { it.copy(faceToFace = !it.faceToFace) } }) {
                        Icon(Icons.Default.People, contentDescription = "Face-to-face mode")
                    }
                    IconButton(onClick = { confirmClear = true }, enabled = state.turns.isNotEmpty()) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear conversation")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (settings.faceToFace) {
                FaceToFace(state, settings, onMic = ::mic, onStopSpeaking = interpreter::stopSpeaking)
            } else {
                LanguageBar(
                    a = settings.langA,
                    b = settings.langB,
                    onPick = { picking = it },
                    onSwap = { container.settings.update { it.copy(languageA = it.languageB, languageB = it.languageA) } },
                )
                RouteLine(settings, onOpenSettings)
                TurnList(
                    state = state,
                    settings = settings,
                    onReplay = interpreter::replay,
                    onDelete = interpreter::deleteTurn,
                    modifier = Modifier.weight(1f),
                )
                StatusLine(state)
                TextInput(settings, onSend = interpreter::translateText)
                MicBar(state, settings, onMic = ::mic, onStopSpeaking = interpreter::stopSpeaking)
            }
        }
    }

    picking?.let { side ->
        LanguagePickerDialog(
            title = if (side == "A") "Your language" else "Their language",
            selected = if (side == "A") settings.languageA else settings.languageB,
            onDismiss = { picking = null },
            onSelect = { lang ->
                container.settings.update { if (side == "A") it.copy(languageA = lang.tag) else it.copy(languageB = lang.tag) }
                picking = null
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear conversation?") },
            text = { Text("This deletes the transcript history stored on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    interpreter.clearHistory()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LanguageBar(a: Language, b: Language, onPick: (String) -> Unit, onSwap: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LanguageButton(a, "You", Modifier.weight(1f)) { onPick("A") }
        IconButton(onClick = onSwap) { Icon(Icons.Default.SwapHoriz, contentDescription = "Swap languages") }
        LanguageButton(b, "Them", Modifier.weight(1f)) { onPick("B") }
    }
}

@Composable
private fun LanguageButton(lang: Language, caption: String, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(lang.englishName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RouteLine(settings: AppSettings, onClick: () -> Unit) {
    fun name(id: email.sshah.kalibabel.providers.ProviderId) = ProviderSpecs[id].displayName.substringBefore(" (")
    val text = "🎙 ${name(settings.sttProvider)}  ·  ⇄ ${name(settings.translationProvider)}" +
        if (settings.autoSpeak) "  ·  🔊 ${name(settings.ttsProvider)}" else "  ·  🔇"
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun TurnList(
    state: InterpreterState,
    settings: AppSettings,
    onReplay: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val last = state.turns.lastOrNull()
    LaunchedEffect(state.turns.size, last?.translation?.length, last?.original?.length) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.lastIndex)
    }
    if (state.turns.isEmpty()) {
        EmptyState(settings, modifier)
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.turns, key = { it.id }) { turn ->
            TurnCard(turn, settings, speaking = state.speakingTurn == turn.id, onReplay = { onReplay(turn.id) }, onDelete = { onDelete(turn.id) })
        }
    }
}

@Composable
private fun EmptyState(settings: AppSettings, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🐟", fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        Text("Put the babel fish in your ear.", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap the left mic and speak ${settings.langA.englishName}, or the right mic for ${settings.langB.englishName}. " +
                "The middle mic detects the language automatically. You can also type below, or select text in any app and choose " +
                "“Translate with KaliBabel”.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.sttProvider == email.sshah.kalibabel.providers.ProviderId.ANDROID &&
            settings.translationProvider == email.sshah.kalibabel.providers.ProviderId.ANDROID
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "You're using the free on-device engines. Add API keys in Settings → Providers for faster, more accurate cloud models.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun TurnCard(turn: Turn, settings: AppSettings, speaking: Boolean, onReplay: () -> Unit, onDelete: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val fromA = turn.sourceTag?.let { tag ->
        tag == settings.languageA || (tag != settings.languageB && Languages.matches(settings.langA, tag))
    } ?: (turn.direction != Direction.B_TO_A)
    val src = turn.sourceTag?.let { Languages.byTag(it).englishName } ?: "Auto"
    val dst = turn.targetTag?.let { Languages.byTag(it).englishName } ?: "…"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (fromA) Alignment.CenterStart else Alignment.CenterEnd) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(0.94f),
            colors = if (fromA) CardDefaults.elevatedCardColors() else CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            ),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$src → $dst", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    StageBadge(turn.stage, speaking)
                    Spacer(Modifier.weight(1f))
                    if (turn.translation.isNotBlank()) {
                        IconButton(onClick = onReplay, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Speak again", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("translation", turn.translation))) } },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy translation", modifier = Modifier.size(18.dp))
                        }
                    }
                    if (!turn.active) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (settings.showOriginal || turn.translation.isBlank()) {
                    Text(
                        turn.original.ifBlank { if (turn.stage == TurnStage.LISTENING) "Listening…" else "" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = if (turn.partial) FontStyle.Italic else FontStyle.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
                if (turn.translation.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(turn.translation, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 10.dp))
                }
                turn.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                turn.note?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (settings.showLatency) LatencyLine(turn)
            }
        }
    }
}

@Composable
private fun StageBadge(stage: TurnStage, speaking: Boolean) {
    val label = when {
        speaking -> "speaking"
        stage == TurnStage.LISTENING -> "listening"
        stage == TurnStage.TRANSCRIBING -> "transcribing"
        stage == TurnStage.TRANSLATING -> "translating"
        stage == TurnStage.CANCELLED -> "cancelled"
        else -> null
    } ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (stage != TurnStage.CANCELLED) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LatencyLine(turn: Turn) {
    val m = turn.metrics
    val parts = listOfNotNull(
        m.mouthToEarMs?.let { "🎙→🔊 ${it} ms" },
        m.sttMs?.let { "STT $it" },
        m.firstTokenMs?.let { "MT 1st $it" },
        m.translateMs?.let { "MT $it" },
        m.firstAudioMs?.let { "TTS 1st $it" },
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  ·  "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun StatusLine(state: InterpreterState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (state.listening != null) {
            val level by animateFloatAsState(state.level, label = "level")
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
        state.status?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TextInput(settings: AppSettings, onSend: (String, Direction) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf(Direction.A_TO_B) }
    val label = when (direction) {
        Direction.A_TO_B -> "${settings.langA.code.uppercase()}→${settings.langB.code.uppercase()}"
        Direction.B_TO_A -> "${settings.langB.code.uppercase()}→${settings.langA.code.uppercase()}"
        Direction.AUTO -> "Auto"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = {
                direction = when (direction) {
                    Direction.A_TO_B -> Direction.B_TO_A
                    Direction.B_TO_A -> Direction.AUTO
                    Direction.AUTO -> Direction.A_TO_B
                }
            },
            label = { Text(label) },
        )
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Type to translate") },
            maxLines = 4,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            enabled = text.isNotBlank(),
            onClick = {
                onSend(text, direction)
                text = ""
            },
        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Translate") }
    }
}

@Composable
private fun MicBar(state: InterpreterState, settings: AppSettings, onMic: (Direction) -> Unit, onStopSpeaking: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MicButton(settings.langA.englishName, state.listening == Direction.A_TO_B, state.level, big = true) { onMic(Direction.A_TO_B) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.speakingTurn != null) {
                FilledTonalIconButton(onClick = onStopSpeaking, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = "Stop speaking")
                }
                Spacer(Modifier.height(4.dp))
            }
            MicButton("Auto", state.listening == Direction.AUTO, state.level, big = false, icon = Icons.Default.Hearing) { onMic(Direction.AUTO) }
        }
        MicButton(settings.langB.englishName, state.listening == Direction.B_TO_A, state.level, big = true) { onMic(Direction.B_TO_A) }
    }
}

@Composable
private fun MicButton(
    label: String,
    active: Boolean,
    level: Float,
    big: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Mic,
    onClick: () -> Unit,
) {
    val size = if (big) 76.dp else 56.dp
    val pulse by animateFloatAsState(if (active) 1f + level * 0.25f else 1f, label = "pulse")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (active) {
                Box(
                    Modifier.size(size).scale(pulse + 0.12f)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape),
                )
            }
            FilledIconButton(
                onClick = onClick,
                modifier = Modifier.size(size),
                colors = if (active) {
                    IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    IconButtonDefaults.filledIconButtonColors()
                },
            ) {
                Icon(
                    if (active) Icons.Default.Stop else icon,
                    contentDescription = if (active) "Stop listening" else "Speak $label",
                    modifier = Modifier.size(if (big) 34.dp else 26.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Face-to-face mode: the top half is rotated 180° for the person sitting opposite, each half
 * showing the latest message in that person's language with their own mic button.
 */
@Composable
private fun FaceToFace(state: InterpreterState, settings: AppSettings, onMic: (Direction) -> Unit, onStopSpeaking: () -> Unit) {
    val a = settings.langA
    val b = settings.langB
    fun latestFor(lang: Language): String {
        val turn = state.turns.lastOrNull() ?: return ""
        return when {
            turn.targetTag != null && Languages.matches(lang, turn.targetTag) -> turn.translation.ifBlank { "…" }
            turn.sourceTag != null && Languages.matches(lang, turn.sourceTag) -> turn.original
            else -> ""
        }
    }
    Column(Modifier.fillMaxSize()) {
        PersonPanel(b, latestFor(b), state.listening == Direction.B_TO_A, state.level, Modifier.weight(1f).rotate(180f)) { onMic(Direction.B_TO_A) }
        HorizontalDivider(thickness = 2.dp)
        if (state.speakingTurn != null) {
            TextButton(onClick = onStopSpeaking, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Stop speaking") }
        }
        PersonPanel(a, latestFor(a), state.listening == Direction.A_TO_B, state.level, Modifier.weight(1f)) { onMic(Direction.A_TO_B) }
    }
}

@Composable
private fun PersonPanel(lang: Language, text: String, active: Boolean, level: Float, modifier: Modifier, onMic: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(lang.nativeName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            Text(
                text.ifBlank { "…" },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
        }
        MicButton(lang.englishName, active, level, big = true, onClick = onMic)
    }
}
