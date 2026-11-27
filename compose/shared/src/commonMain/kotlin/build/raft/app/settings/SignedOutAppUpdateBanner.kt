package build.raft.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.HomeAppUpdateBanner
import build.raft.app.base.BridgeModule
import build.raft.app.compat.pager.Pager
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.DataResult
import build.raft.app.data.RaftAppUpdateEvents
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedHomeNotification
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.toSharedHomeAppUpdateNotification
import build.raft.app.get
import build.raft.app.settings.toHomeNotificationItem
import build.raft.app.toHomeNotificationItem

/**
 * The app-update notice for a signed-out person. The signed-in Home asks the host for the update
 * status as part of its notifications; with no session there is no Home, so someone on an old
 * build who cannot sign in would never hear of the build that fixes it. The Hands check needs no
 * account, so the signed-out pages ask the same host action and build the same notice. A host
 * with no in-app update (iOS, Google Play builds) answers unsupported; a store install then gets
 * the store's answer instead (RaftStoreUpdateChecker), anything else has no notice.
 */
internal suspend fun loadSignedOutAppUpdateNotification(
    skippedVersionCode: Long = skippedAppUpdateVersionCode(),
    checkStatus: suspend () -> DataResult<SharedSettingsUpdateCheckSnapshot>,
): SharedHomeNotification? =
    when (val result = runCatchingPreservingCancellation { checkStatus() }.getOrNull()) {
        is DataResult.Content ->
            result.data
                .withSkippedAppUpdateVersionApplied(skippedVersionCode)
                .toSharedHomeAppUpdateNotification()
        else -> null
    }

/**
 * Home's update banner (same look, same "View"), under the auth top bar. A tap opens the same
 * update page as Home, which downloads and installs; a skip or an install there tells this banner
 * to check again.
 */
@Composable
internal fun SignedOutAppUpdateBanner(pager: Pager, bridge: BridgeModule) {
    val scope = rememberCoroutineScope()
    val capabilities =
        remember(bridge, scope) {
            BridgeSettingsPlatformCapabilities(bridge, "", scope.kuiklyBridgeCallContext())
        }
    val appUpdateEvents = remember(bridge) { RaftAppUpdateEvents(RaftSharedStoreRegistry.get(bridge).eventBus) }
    var latestSnapshot by remember { mutableStateOf<SharedSettingsUpdateCheckSnapshot?>(null) }
    var notification by remember { mutableStateOf<SharedHomeNotification?>(null) }
    var checkGeneration by remember { mutableStateOf(0) }
    LaunchedEffect(capabilities, checkGeneration) {
        latestSnapshot = null
        notification =
            loadSignedOutAppUpdateNotification {
                capabilities.checkHandsUpdateStatus().also { result ->
                    if (result is DataResult.Content) latestSnapshot = result.data
                }
            }
    }
    DisposableEffect(appUpdateEvents) {
        val listener = appUpdateEvents.addStatusListener { checkGeneration += 1 }
        onDispose { appUpdateEvents.removeStatusListener(listener) }
    }
    val update = notification ?: return
    HomeAppUpdateBanner(
        notification = update.toHomeNotificationItem(),
        onClick = {
            triggerAppShellUpdateOpenOrRestart(
                snapshot = latestSnapshot,
                scope = scope,
                settingsCapabilities = capabilities,
                onOpenUpdatePage = { pager.openAppUpdatePage(RaftShellStoreScope.Legacy) },
            )
        }
    )
}
