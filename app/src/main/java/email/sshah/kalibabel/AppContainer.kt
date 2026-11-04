package email.sshah.kalibabel

import android.content.Context
import android.util.Log
import email.sshah.kalibabel.audio.AudioPlayer
import email.sshah.kalibabel.audio.AudioRecorder
import email.sshah.kalibabel.audio.SpeechQueue
import email.sshah.kalibabel.data.HistoryStore
import email.sshah.kalibabel.data.SecureKeyStore
import email.sshah.kalibabel.data.SettingsRepository
import email.sshah.kalibabel.net.Http
import email.sshah.kalibabel.pipeline.Interpreter
import email.sshah.kalibabel.providers.ProviderRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application-scoped object graph (manual DI keeps startup fast and the build simple). */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    /**
     * Lives as long as the process; the interpreter must survive configuration changes.
     * Last line of defence: a background failure that slipped through is logged rather than
     * crashing the app in the middle of a conversation.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("KaliBabel", "Uncaught background failure", e) },
    )

    val http = Http.createClient()
    val keys = SecureKeyStore(app)
    val settings = SettingsRepository(app)
    val registry = ProviderRegistry(app, http, settings, keys)
    val recorder = AudioRecorder(app)
    val player = AudioPlayer(app)
    val speech = SpeechQueue(scope, player)
    val history = HistoryStore(app, scope)
    val interpreter = Interpreter(scope, settings, registry, recorder, speech, history)
}
