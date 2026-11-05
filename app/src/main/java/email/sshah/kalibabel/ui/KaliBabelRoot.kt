package email.sshah.kalibabel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import email.sshah.kalibabel.AppContainer
import email.sshah.kalibabel.providers.ProviderId
import email.sshah.kalibabel.ui.conversation.ConversationScreen
import email.sshah.kalibabel.ui.providers.ProviderDetailScreen
import email.sshah.kalibabel.ui.providers.ProvidersScreen
import email.sshah.kalibabel.ui.settings.DiagnosticsScreen
import email.sshah.kalibabel.ui.settings.DisplaySettings
import email.sshah.kalibabel.ui.settings.ListeningSettings
import email.sshah.kalibabel.ui.settings.SettingsRoutes
import email.sshah.kalibabel.ui.settings.SettingsScreen
import email.sshah.kalibabel.ui.settings.TranslationSettings
import email.sshah.kalibabel.ui.settings.VoiceSettings

/**
 * Tiny back-stack navigator: routes are "conversation", "settings", the settings pages in
 * [SettingsRoutes], and "provider/<ID>".
 */
@Composable
fun KaliBabelRoot(container: AppContainer, incomingText: String?, onIncomingConsumed: () -> Unit) {
    var stack by rememberSaveable { mutableStateOf(listOf("conversation")) }
    fun push(route: String) {
        stack = stack + route
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    val route = stack.last()
    when {
        route == "conversation" -> ConversationScreen(
            container = container,
            incomingText = incomingText,
            onIncomingConsumed = onIncomingConsumed,
            onOpenSettings = { push("settings") },
            onOpenDiagnostics = { push(SettingsRoutes.DIAGNOSTICS) },
        )
        route == "settings" -> SettingsScreen(container, onBack = ::pop, onOpen = ::push)
        route == SettingsRoutes.LISTENING -> ListeningSettings(container, onBack = ::pop)
        route == SettingsRoutes.TRANSLATION -> TranslationSettings(container, onBack = ::pop)
        route == SettingsRoutes.VOICE -> VoiceSettings(container, onBack = ::pop)
        route == SettingsRoutes.DISPLAY -> DisplaySettings(container, onBack = ::pop)
        route == SettingsRoutes.DIAGNOSTICS -> DiagnosticsScreen(onBack = ::pop)
        route == SettingsRoutes.PROVIDERS -> ProvidersScreen(container, onBack = ::pop, onOpen = { push("provider/${it.name}") })
        route.startsWith("provider/") -> {
            val id = runCatching { ProviderId.valueOf(route.removePrefix("provider/")) }.getOrNull()
            if (id == null) pop() else ProviderDetailScreen(container, id, onBack = ::pop)
        }
        else -> pop()
    }
}
