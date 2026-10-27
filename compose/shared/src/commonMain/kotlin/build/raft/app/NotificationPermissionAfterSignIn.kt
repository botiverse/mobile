package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.KeyValueNotificationPermissionPromptPreferenceStore
import build.raft.app.data.NotificationPermissionAutoPrompt
import build.raft.app.data.SharedPreferenceStore
import kotlinx.coroutines.launch

/**
 * Asks once for the system notification permission when the signed-in app shell is showing
 * ([active]: a ready session with a server, page visible). See [NotificationPermissionAutoPrompt].
 */
@Composable
internal fun NotificationPermissionAfterSignInEffect(
    active: Boolean,
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    preferenceStore: SharedPreferenceStore,
) {
    val prompt =
        remember(settingsCapabilities, preferenceStore) {
            NotificationPermissionAutoPrompt(
                preferences = KeyValueNotificationPermissionPromptPreferenceStore(preferenceStore),
                supported = raftPlatformAutoPromptsNotificationPermissionAfterSignIn(),
                loadStatusCode = settingsCapabilities::loadSystemNotificationPermissionCode,
                requestPermission = settingsCapabilities::requestNotificationPermission,
                logger = { raftLogInfo("SlockNotificationPermission", it) },
            )
        }
    LaunchedEffect(prompt, active) {
        if (active) prompt.maybeAsk()
    }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, prompt) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) scope.launch { prompt.onResumed() }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
