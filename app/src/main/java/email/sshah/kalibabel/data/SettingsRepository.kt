package email.sshah.kalibabel.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import email.sshah.kalibabel.net.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** Non-secret settings, persisted as one JSON document and exposed as a [StateFlow]. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("kalibabel_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())

    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = _settings.updateAndGet(transform)
        prefs.edit { putString(KEY, AppJson.encodeToString(AppSettings.serializer(), updated)) }
    }

    private fun load(): AppSettings {
        val raw = prefs.getString(KEY, null) ?: return AppSettings()
        return runCatching { AppJson.decodeFromString(AppSettings.serializer(), raw) }
            .onFailure { Log.w("Settings", "Resetting unreadable settings", it) }
            .getOrDefault(AppSettings())
    }

    private companion object {
        const val KEY = "settings_v1"
    }
}
