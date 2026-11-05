package email.sshah.kalibabel

import android.app.Application
import email.sshah.kalibabel.data.ErrorLog
import email.sshah.kalibabel.providers.ProviderId

class KaliBabelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash while the app starts up is recorded too. Stored keys are scrubbed from the log.
        ErrorLog.install(this) { ProviderId.entries.mapNotNull { container.keys.get(it) } }
        container = AppContainer(this)
    }
}
