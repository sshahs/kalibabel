package email.sshah.kalibabel.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import android.os.Build
import android.util.Log
import email.sshah.kalibabel.BuildConfig
import email.sshah.kalibabel.net.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
enum class LogLevel(val label: String) { CRASH("Crash"), ERROR("Error"), WARNING("Recovered") }

@Serializable
data class LogEntry(
    val time: Long,
    val level: LogLevel,
    /** Where it happened, e.g. "Live transcription" or "Thread main". */
    val source: String,
    val message: String,
    /** Stack trace (obfuscated in release builds: decode with the release's R8 mapping). */
    val details: String = "",
    val version: String = "",
)

/**
 * Crashes and pipeline failures, kept on this device only so they can be viewed and shared from
 * Settings → Diagnostics. Secrets are scrubbed before anything is written: every stored API key
 * verbatim, plus common key/token shapes.
 *
 * A process-wide singleton like [Log]: until [install] runs (e.g. in unit tests) it only logs.
 */
object ErrorLog {
    private const val TAG = "ErrorLog"
    private const val MAX_ENTRIES = 150
    private const val MAX_DETAILS = 6_000
    private const val PREFS = "diagnostics"
    private const val SEEN_KEY = "crash_seen_until"

    private val lock = Any()
    private var file: File? = null
    private var prefs: SharedPreferences? = null
    private var secrets: () -> Collection<String> = { emptyList() }
    private var handlerInstalled = false
    private var linesInFile = 0

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Oldest first. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _unseenCrash = MutableStateFlow(false)

    /** True after a crash until the user has looked at (or dismissed) the log. */
    val unseenCrash: StateFlow<Boolean> = _unseenCrash.asStateFlow()

    /** Loads the log and records crashes from now on. [secrets] returns the stored API keys. */
    fun install(context: Context, secrets: () -> Collection<String>) {
        val app = context.applicationContext
        synchronized(lock) {
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            this.secrets = secrets
            file = File(app.filesDir, "error-log.jsonl")
            _entries.value = read()
            linesInFile = _entries.value.size
            refreshUnseen()
        }
        installCrashHandler()
    }

    fun crash(source: String, error: Throwable) = record(LogLevel.CRASH, source, error.toString(), error)

    fun error(source: String, message: String, error: Throwable? = null) = record(LogLevel.ERROR, source, message, error)

    /** Something failed but the app worked around it (fallback provider, reconnect, …). */
    fun warning(source: String, message: String, error: Throwable? = null) = record(LogLevel.WARNING, source, message, error)

    fun record(level: LogLevel, source: String, message: String, error: Throwable? = null) {
        val entry = LogEntry(
            time = System.currentTimeMillis(),
            level = level,
            source = source,
            message = redact(message),
            details = error?.let { redact(it.stackTraceToString()).take(MAX_DETAILS) }.orEmpty(),
            version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        synchronized(lock) {
            val updated = (_entries.value + entry).takeLast(MAX_ENTRIES)
            _entries.value = updated
            if (level == LogLevel.CRASH) _unseenCrash.value = true
            val f = file ?: return
            runCatching {
                // Append (cheap, and safe inside a crashing process); compact once in a while.
                if (linesInFile >= MAX_ENTRIES + MAX_ENTRIES / 2) {
                    write(f, updated)
                    linesInFile = updated.size
                } else {
                    f.appendText(AppJson.encodeToString(LogEntry.serializer(), entry) + "\n")
                    linesInFile++
                }
            }.onFailure { Log.w(TAG, "Could not write the error log", it) }
        }
    }

    fun clear() = synchronized(lock) {
        _entries.value = emptyList()
        file?.delete()
        linesInFile = 0
        markCrashSeen()
    }

    fun markCrashSeen() = synchronized(lock) {
        val latest = _entries.value.lastOrNull { it.level == LogLevel.CRASH }?.time ?: 0L
        prefs?.edit { putLong(SEEN_KEY, latest) }
        _unseenCrash.value = false
    }

    /** Plain-text report for sharing: device header, then entries newest first. */
    fun export(maxChars: Int = 100_000): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val header = buildString {
            appendLine("KaliBabel ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Release stack traces are obfuscated; decode them with that release's R8 mapping file.")
        }
        val out = StringBuilder(header)
        for (e in _entries.value.asReversed()) {
            val block = buildString {
                appendLine()
                appendLine("${fmt.format(Date(e.time))} · ${e.level.label} · ${e.source} · ${e.version}")
                appendLine(e.message)
                if (e.details.isNotBlank()) appendLine(e.details.trimEnd())
            }
            if (out.length + block.length > maxChars) {
                out.appendLine().append("… older entries omitted")
                break
            }
            out.append(block)
        }
        return out.toString()
    }

    /** Replaces API keys and other credentials with "[redacted]". */
    fun redact(text: String): String {
        var out = text
        runCatching { secrets() }.getOrDefault(emptyList())
            .filter { it.length >= 8 }
            .sortedByDescending { it.length }
            .forEach { out = out.replace(it, REDACTED) }
        SECRET_PATTERNS.forEach { (regex, replacement) -> out = regex.replace(out, replacement) }
        return out
    }

    private const val REDACTED = "[redacted]"

    private val SECRET_PATTERNS = listOf(
        Regex("(?i)\\b(bearer|token|basic)\\s+[A-Za-z0-9._~+/=-]{8,}") to "$1 $REDACTED",
        Regex("(?i)(\\b(?:api[_-]?key|x-api-key|xi-api-key|x-goog-api-key|authorization|access[_-]?token|secret|password|key)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"'&,;}]{8,}") to "$1$REDACTED",
        Regex("\\b(?:sk|pk|rk)-[A-Za-z0-9_-]{12,}") to REDACTED,
    )

    private fun read(): List<LogEntry> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().mapNotNull { line ->
                line.takeIf { it.isNotBlank() }?.let { runCatching { AppJson.decodeFromString(LogEntry.serializer(), it) }.getOrNull() }
            }.takeLast(MAX_ENTRIES)
        }.onFailure { Log.w(TAG, "Could not read the error log", it) }.getOrDefault(emptyList())
    }

    private fun write(f: File, entries: List<LogEntry>) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(entries.joinToString("") { AppJson.encodeToString(LogEntry.serializer(), it) + "\n" })
        tmp.renameTo(f)
    }

    private fun refreshUnseen() {
        val seen = prefs?.getLong(SEEN_KEY, 0L) ?: 0L
        _unseenCrash.value = _entries.value.any { it.level == LogLevel.CRASH && it.time > seen }
    }

    private fun installCrashHandler() {
        synchronized(lock) {
            if (handlerInstalled) return
            handlerInstalled = true
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { crash("Thread ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }
}
