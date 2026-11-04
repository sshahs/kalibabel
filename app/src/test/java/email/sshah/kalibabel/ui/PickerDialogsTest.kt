package email.sshah.kalibabel.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import email.sshah.kalibabel.providers.ModelInfo
import email.sshah.kalibabel.ui.components.CatalogPickerDialog
import email.sshah.kalibabel.ui.components.LanguagePickerDialog
import email.sshah.kalibabel.ui.theme.KaliBabelTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
/** Interaction tests for the picker dialogs (plain Robolectric graphics; native mode cannot idle dialogs). */
class PickerDialogsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun languageDialog() {
        compose.setContent { KaliBabelTheme { LanguagePickerDialog("t", "en-US", {}, {}) } }
        compose.onNodeWithText("Afrikaans").assertExists()
    }

    @Test
    fun catalogDialog() {
        compose.setContent {
            KaliBabelTheme {
                CatalogPickerDialog(
                    title = "t", selected = "a", fallback = emptyList<ModelInfo>(), id = { it.id }, label = { it.label },
                    detail = { it.description }, load = { listOf(ModelInfo("a"), ModelInfo("b")) }, allowCustom = true,
                    onDismiss = {}, onSelect = {},
                )
            }
        }
        compose.onNodeWithText("b").assertExists()
    }

    @Test
    fun modelDialogWithRegistry() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<email.sshah.kalibabel.KaliBabelApp>()
        compose.setContent {
            KaliBabelTheme {
                email.sshah.kalibabel.ui.components.ModelPickerDialog(
                    app.container, email.sshah.kalibabel.providers.ProviderId.ELEVENLABS,
                    email.sshah.kalibabel.providers.Capability.STT, "scribe_v2", {}, {},
                )
            }
        }
        compose.onNodeWithText("Scribe v2 Realtime").assertExists()
    }

    @Test
    fun detailThenDialog() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<email.sshah.kalibabel.KaliBabelApp>()
        compose.setContent {
            KaliBabelTheme {
                email.sshah.kalibabel.ui.providers.ProviderDetailScreen(app.container, email.sshah.kalibabel.providers.ProviderId.ELEVENLABS, {})
            }
        }
        compose.onNodeWithText("Speech recognition model").performScrollTo().performClick()
        compose.onNodeWithText("Scribe v2 Realtime").assertExists()
    }
}
