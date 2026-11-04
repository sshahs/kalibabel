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
import email.sshah.kalibabel.ui.settings.SettingsScreen

/** Tiny back-stack navigator: routes are "conversation", "settings", "providers", "provider/<ID>". */
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
        )
        route == "settings" -> SettingsScreen(container, onBack = ::pop, onOpenProviders = { push("providers") })
        route == "providers" -> ProvidersScreen(container, onBack = ::pop, onOpen = { push("provider/${it.name}") })
        route.startsWith("provider/") -> {
            val id = runCatching { ProviderId.valueOf(route.removePrefix("provider/")) }.getOrNull()
            if (id == null) pop() else ProviderDetailScreen(container, id, onBack = ::pop)
        }
        else -> pop()
    }
}
