package email.sshah.kalibabel.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import email.sshah.kalibabel.KaliBabelApp
import email.sshah.kalibabel.data.ErrorLog
import email.sshah.kalibabel.pipeline.Direction
import email.sshah.kalibabel.pipeline.Turn
import email.sshah.kalibabel.pipeline.TurnMetrics
import email.sshah.kalibabel.pipeline.TurnStage
import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.ui.providers.ProviderDetailScreen
import email.sshah.kalibabel.ui.providers.ProvidersScreen
import email.sshah.kalibabel.ui.settings.DiagnosticsScreen
import email.sshah.kalibabel.ui.settings.DisplaySettings
import email.sshah.kalibabel.ui.settings.ListeningSettings
import email.sshah.kalibabel.ui.settings.SettingsScreen
import email.sshah.kalibabel.ui.settings.TranslationSettings
import email.sshah.kalibabel.ui.settings.VoiceSettings
import email.sshah.kalibabel.ui.theme.KaliBabelTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every screen on the JVM (Robolectric native graphics) to catch layout/runtime crashes
 * without a device, and writes PNGs to build/screenshots for visual review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<KaliBabelApp>()

    private fun snapshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun conversationEmptyState() {
        compose.setContent { KaliBabelTheme { KaliBabelRoot(app.container, null) {} } }
        compose.onNodeWithText("Put the babel fish in your ear.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Speak English (US)").assertIsDisplayed()
        snapshot("01-conversation-empty")
    }

    @Test
    fun conversationWithTurns() {
        seedTurns()
        compose.setContent { KaliBabelTheme { KaliBabelRoot(app.container, null) {} } }
        compose.onNodeWithText("Disculpe, ¿dónde está la estación de tren más cercana?").assertIsDisplayed()
        snapshot("02-conversation-turns")
    }

    @Test
    fun faceToFace() {
        seedTurns()
        app.container.settings.update { it.copy(faceToFace = true) }
        compose.setContent { KaliBabelTheme { KaliBabelRoot(app.container, null) {} } }
        snapshot("03-face-to-face")
        app.container.settings.update { it.copy(faceToFace = false) }
    }

    @Test
    fun settingsScreen() {
        compose.setContent { KaliBabelTheme { SettingsScreen(app.container, onBack = {}, onOpen = {}) } }
        compose.onNodeWithText("Providers & API keys").assertIsDisplayed()
        compose.onNodeWithText("Listening").assertIsDisplayed()
        compose.onNodeWithText("No problems recorded").assertIsDisplayed()
        snapshot("04-settings")
    }

    @Test
    fun listeningSettingsWithAdvancedOpen() {
        compose.setContent { KaliBabelTheme { ListeningSettings(app.container, onBack = {}) } }
        compose.onNodeWithText("Live transcription").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithText("Live transcription").assertExists()
        snapshot("04b-settings-listening")
    }

    @Test
    fun translationSettings() {
        compose.setContent { KaliBabelTheme { TranslationSettings(app.container, onBack = {}) } }
        compose.onNodeWithText("Register").assertIsDisplayed()
        snapshot("04c-settings-translation")
    }

    @Test
    fun voiceSettings() {
        compose.setContent { KaliBabelTheme { VoiceSettings(app.container, onBack = {}) } }
        compose.onNodeWithText("Speak translations aloud").assertIsDisplayed()
        snapshot("04d-settings-voice")
    }

    @Test
    fun displaySettings() {
        compose.setContent { KaliBabelTheme { DisplaySettings(app.container, onBack = {}) } }
        compose.onNodeWithText("Face-to-face layout").assertIsDisplayed()
        snapshot("04e-settings-display")
    }

    @Test
    fun diagnosticsWithEntries() {
        ErrorLog.clear()
        ErrorLog.warning("Live transcription", "ElevenLabs live connection dropped; transcribing by upload — ElevenLabs: connection dropped by the server", java.io.IOException("ElevenLabs: connection dropped by the server"))
        ErrorLog.crash("Thread main", IllegalStateException("Example crash"))
        compose.setContent { KaliBabelTheme { DiagnosticsScreen(onBack = {}) } }
        compose.onNodeWithText("Thread main").assertIsDisplayed()
        compose.onNodeWithText("Live transcription").performClick()
        snapshot("04f-diagnostics")
        ErrorLog.clear()
    }

    @Test
    fun providersScreen() {
        compose.setContent { KaliBabelTheme { ProvidersScreen(app.container, onBack = {}, onOpen = {}) } }
        compose.onNodeWithText("Command Code").assertIsDisplayed()
        snapshot("05-providers")
    }

    @Test
    fun commandCodeDetail() {
        compose.setContent { KaliBabelTheme { ProviderDetailScreen(app.container, ProviderId.COMMAND_CODE, onBack = {}) } }
        compose.onNodeWithText("Zero data retention (ZDR)").assertIsDisplayed()
        snapshot("06-command-code")
    }

    @Test
    fun elevenLabsDetail() {
        compose.setContent { KaliBabelTheme { ProviderDetailScreen(app.container, ProviderId.ELEVENLABS, onBack = {}) } }
        compose.onNodeWithText("Zero retention mode").assertIsDisplayed()
        snapshot("07-elevenlabs")
    }

    private fun seedTurns() {
        val turns = listOf(
            Turn(
                id = 1, direction = Direction.A_TO_B, sourceTag = "en-US", targetTag = "es-ES",
                original = "Excuse me, where is the nearest train station?",
                translation = "Disculpe, ¿dónde está la estación de tren más cercana?",
                stage = TurnStage.DONE,
                metrics = TurnMetrics(sttMs = 180, firstTokenMs = 210, translateMs = 390, firstAudioMs = 320, mouthToEarMs = 500),
            ),
            Turn(
                id = 2, direction = Direction.B_TO_A, sourceTag = "es-ES", targetTag = "en-US",
                original = "Siga recto dos calles y gire a la izquierda.",
                translation = "Go straight for two blocks and turn left.",
                stage = TurnStage.DONE,
                metrics = TurnMetrics(sttMs = 150, firstTokenMs = 190, translateMs = 300, firstAudioMs = 280, mouthToEarMs = 430),
            ),
        )
        val field = app.container.interpreter.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(app.container.interpreter) as kotlinx.coroutines.flow.MutableStateFlow<email.sshah.kalibabel.pipeline.InterpreterState>
        flow.value = flow.value.copy(turns = turns)
    }
}
