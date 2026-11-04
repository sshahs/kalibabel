package email.sshah.kalibabel

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import email.sshah.kalibabel.ui.KaliBabelRoot
import email.sshah.kalibabel.ui.theme.KaliBabelTheme

class MainActivity : ComponentActivity() {
    private val incomingText = mutableStateOf<String?>(null)

    private val container: AppContainer get() = (application as KaliBabelApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            KaliBabelTheme {
                KaliBabelRoot(
                    container = container,
                    incomingText = incomingText.value,
                    onIncomingConsumed = { incomingText.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Android blocks background microphone access without a foreground service; stop cleanly.
        if (!isChangingConfigurations) container.interpreter.stopListening()
    }

    /** Text from the selection menu ("Translate with KaliBabel") or the share sheet. */
    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!text.isNullOrBlank()) incomingText.value = text.take(5000)
    }
}
