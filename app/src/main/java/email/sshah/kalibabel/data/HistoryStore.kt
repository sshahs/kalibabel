package email.sshah.kalibabel.data

import android.content.Context
import android.util.Log
import email.sshah.kalibabel.net.AppJson
import email.sshah.kalibabel.pipeline.Turn
import email.sshah.kalibabel.pipeline.TurnStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Persists the conversation locally (never uploaded, excluded from backups). */
class HistoryStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "history.json")
    private var pending: Job? = null

    fun load(): List<Turn> = runCatching {
        if (!file.exists()) return emptyList()
        AppJson.decodeFromString(ListSerializer(Turn.serializer()), file.readText())
            .filter { it.original.isNotBlank() }
            .map { if (it.active) it.copy(stage = if (it.translation.isNotBlank()) TurnStage.DONE else TurnStage.CANCELLED, partial = false) else it }
    }.onFailure { Log.w("History", "Could not read history", it) }.getOrDefault(emptyList())

    /** Debounced save of the most recent [MAX] turns. */
    fun save(turns: List<Turn>) {
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) {
            delay(800)
            runCatching {
                val keep = turns.filter { it.original.isNotBlank() }.takeLast(MAX)
                val tmp = File(file.parentFile, "history.json.tmp")
                tmp.writeText(AppJson.encodeToString(ListSerializer(Turn.serializer()), keep))
                tmp.renameTo(file)
            }.onFailure { Log.w("History", "Could not save history", it) }
        }
    }

    fun clear() {
        pending?.cancel()
        file.delete()
    }

    private companion object {
        const val MAX = 300
    }
}
