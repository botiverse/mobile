package build.raft.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.HomeServerSwitcherMenuMaxWidth
import build.raft.app.RaftHomeShellLabels
import build.raft.app.RaftServerSwitcherItem
import build.raft.app.compat.ui.rememberAsyncImageBitmap
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.homeServerSwitchListPending
import build.raft.app.homeServerSwitcherBadgePresentation
import build.raft.app.homeServerSwitcherJoinCommunityActions
import build.raft.app.raftAvatarImageCandidates
import build.raft.app.toServerSwitcherAvatarPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Native in-place Liquid Glass Home server switcher for iOS 26+ (#proj-mobile:#369).
 *
 * The shared Home shell remains the single source of truth for open/closed state, server list,
 * unread counts, selection, reordering, and footer actions. On iOS 26+ the page host registers a
 * [RaftNativeServerSwitcherBridge] in [RaftNativeServerSwitcherRegistry] and puts its host id in
 * pageData under [RaftNativeServerSwitcherHostIdPageDataKey]. When non-null, Home publishes the
 * switcher popover state to the native host (which renders a real UIKit `UIGlassEffect` popover
 * anchored to the top-left server capsule above the Compose Metal canvas) instead of drawing the
 * opaque Compose `ServerSwitcherPanel`.
 *
 * On Android, Desktop, iOS < 26, and visual fixtures, [LocalRaftNativeServerSwitcherBridge] is
 * null, so every other platform keeps the unchanged Compose popover with no toggle or fallback.
 */

/** pageData key carrying the id of the host's registered [RaftNativeServerSwitcherBridge]. */
const val RaftNativeServerSwitcherHostIdPageDataKey = "slockNativeServerSwitcherHostId"

/** Action ids reported back through [RaftNativeServerSwitcherBridge.tapAction]. */
const val RaftNativeServerSwitcherActionCreateServer = "create_server"
const val RaftNativeServerSwitcherActionInviteHuman = "invite_human"
const val RaftNativeServerSwitcherActionJoinCommunityPrefix = "join_community:"

/** One server row rendered in the native iOS 26+ Liquid Glass switcher. Exported to Swift. */
data class RaftNativeServerSwitcherRow(
    val id: String,
    val name: String,
    val slug: String?,
    val selected: Boolean,
    val publicPageLive: Boolean,
    /** Capped unread count ("3", "99+"); null when no unread badge should show. */
    val badgeLabel: String?,
    /** True when the server's push notifications are muted (gray pill instead of accent pink). */
    val badgeMuted: Boolean,
    /** Localized accessibility phrase for a muted count. */
    val mutedAccessibilityLabel: String,
    /** Decoded avatar pixels from the shared Coil cache; null falls back to initial tile. */
    val avatar: RaftNativeTabBarAvatar?,
    /** Fallback initial for the sticker ink tile when [avatar] is null. */
    val avatarFallbackLabel: String,
)

/** One footer action rendered under the separator in the native switcher. Exported to Swift. */
data class RaftNativeServerSwitcherAction(
    val id: String,
    val label: String,
    val imageAssetName: String,
    val systemSymbol: String,
)

/** Complete state published to the iOS 26+ native server switcher host. Exported to Swift. */
data class RaftNativeServerSwitcherState(
    val open: Boolean,
    val rows: List<RaftNativeServerSwitcherRow>,
    val actions: List<RaftNativeServerSwitcherAction>,
    val emptyLabel: String,
    /** Mirrors web `hostedSwitchPending`: dims the list and blocks taps/reorders while switching. */
    val switchPending: Boolean,
    val reorderable: Boolean,
    /** Popover top offset in points from the page's top edge (anchored under the top-left pill). */
    val anchorTopPt: Double,
    /** Popover leading inset in points from the page's leading edge. */
    val anchorStartPt: Double,
    /** Maximum popover width in points (matches [HomeServerSwitcherMenuMaxWidth]). */
    val maxWidthPt: Double,
) {
    companion object {
        val Closed =
            RaftNativeServerSwitcherState(
                open = false,
                rows = emptyList(),
                actions = emptyList(),
                emptyLabel = "",
                switchPending = false,
                reorderable = false,
                anchorTopPt = 0.0,
                anchorStartPt = 8.0,
                maxWidthPt = 480.0,
            )
    }
}

/** Implemented by the iOS 26+ host (`RaftIosServerSwitcherHost`). Called on the main thread. */
interface RaftNativeServerSwitcherListener {
    fun onNativeServerSwitcherStateChanged(state: RaftNativeServerSwitcherState)
}

/**
 * Per-page bridge between the shared Home shell and the iOS 26+ native Liquid Glass server
 * switcher host. Created by `RaftPageControllerFactory` on iOS 26+ only. Exported to Swift.
 */
class RaftNativeServerSwitcherBridge {
    private val mutableState = MutableStateFlow(RaftNativeServerSwitcherState.Closed)
    val state: StateFlow<RaftNativeServerSwitcherState> = mutableState.asStateFlow()

    var listener: RaftNativeServerSwitcherListener? = null
        set(value) {
            field = value
            value?.onNativeServerSwitcherStateChanged(mutableState.value)
        }

    private var publisherToken: Any? = null
    private var onSelectServer: ((String) -> Unit)? = null
    private var onTapAction: ((String) -> Unit)? = null
    private var onReorderServers: ((List<String>) -> Unit)? = null
    private var onDismiss: (() -> Unit)? = null

    /** Host -> shell: user tapped a server row. Returns false when switching is pending or closed. */
    fun selectServer(serverId: String): Boolean {
        val current = mutableState.value
        if (!current.open || current.switchPending) return false
        if (current.rows.none { it.id == serverId }) return false
        val handler = onSelectServer ?: return false
        handler(serverId)
        return true
    }

    /** Host -> shell: user tapped a footer action row (Join Community / Create / Invite Human). */
    fun tapAction(actionId: String): Boolean {
        val current = mutableState.value
        if (!current.open) return false
        if (current.actions.none { it.id == actionId }) return false
        val handler = onTapAction ?: return false
        handler(actionId)
        return true
    }

    /** Host -> shell: user drag-reordered the server rows into [orderedIds]. */
    fun reorderServers(orderedIds: List<String>): Boolean {
        val current = mutableState.value
        if (!current.open || !current.reorderable) return false
        val currentIds = current.rows.map { it.id }
        if (orderedIds.size != currentIds.size || orderedIds.toSet() != currentIds.toSet()) return false
        if (orderedIds == currentIds) return false
        val handler = onReorderServers ?: return false
        handler(orderedIds)
        return true
    }

    /** Host -> shell: user tapped outside or pressed Escape to close the switcher. */
    fun dismiss() {
        onDismiss?.invoke()
    }

    internal fun publish(
        token: Any,
        state: RaftNativeServerSwitcherState,
        onSelectServer: (String) -> Unit,
        onTapAction: (String) -> Unit,
        onReorderServers: (List<String>) -> Unit,
        onDismiss: () -> Unit,
    ) {
        publisherToken = token
        this.onSelectServer = onSelectServer
        this.onTapAction = onTapAction
        this.onReorderServers = onReorderServers
        this.onDismiss = onDismiss
        emit(state)
    }

    internal fun retract(token: Any) {
        if (publisherToken !== token) return
        publisherToken = null
        onSelectServer = null
        onTapAction = null
        onReorderServers = null
        onDismiss = null
        emit(RaftNativeServerSwitcherState.Closed)
    }

    private fun emit(next: RaftNativeServerSwitcherState) {
        if (mutableState.value == next) return
        mutableState.value = next
        listener?.onNativeServerSwitcherStateChanged(next)
    }
}

/** Host-owned bridges by host id. Exported to Swift as `RaftNativeServerSwitcherRegistry.shared`. */
object RaftNativeServerSwitcherRegistry {
    private val bridges = mutableMapOf<String, RaftNativeServerSwitcherBridge>()

    fun register(hostId: String, bridge: RaftNativeServerSwitcherBridge) {
        if (hostId.isBlank()) return
        bridges[hostId] = bridge
    }

    fun unregister(hostId: String, bridge: RaftNativeServerSwitcherBridge) {
        if (bridges[hostId] === bridge) bridges.remove(hostId)
    }

    internal fun bridgeFor(hostId: String?): RaftNativeServerSwitcherBridge? =
        hostId?.takeIf { it.isNotBlank() }?.let { bridges[it] }
}

/**
 * The iOS 26+ native server switcher bridge registered for the current page, if any.
 * Null on Android, Desktop, iOS < 26, and visual fixtures.
 */
internal val LocalRaftNativeServerSwitcherBridge =
    staticCompositionLocalOf<RaftNativeServerSwitcherBridge?> { null }

internal fun raftNativeServerSwitcherState(
    open: Boolean,
    servers: List<RaftServerSwitcherItem>,
    currentServerId: String,
    pendingServerId: String?,
    prefersChineseCommunity: Boolean,
    labels: RaftHomeShellLabels,
    hasInviteHumanAction: Boolean,
    avatarsByServerId: Map<String, RaftNativeTabBarAvatar?>,
    anchorTopPt: Double,
    anchorStartPt: Double,
    maxWidthPt: Double = HomeServerSwitcherMenuMaxWidth.value.toDouble(),
): RaftNativeServerSwitcherState {
    if (!open) return RaftNativeServerSwitcherState.Closed
    val switchPending = homeServerSwitchListPending(pendingServerId)
    val rows =
        servers.map { server ->
            val badge = homeServerSwitcherBadgePresentation(server)
            val avatarPresentation = server.toServerSwitcherAvatarPresentation()
            RaftNativeServerSwitcherRow(
                id = server.id,
                name = server.name,
                slug = server.slug?.trim()?.takeIf { it.isNotEmpty() },
                selected = server.id == currentServerId,
                publicPageLive = server.publicPageLive,
                badgeLabel = badge.label,
                badgeMuted = badge.muted,
                mutedAccessibilityLabel = labels.serverNotificationsMuted,
                avatar = avatarsByServerId[server.id],
                avatarFallbackLabel = avatarPresentation.fallbackLabel,
            )
        }
    val joinActions =
        homeServerSwitcherJoinCommunityActions(
            servers = servers,
            labels = labels,
            includeChineseCommunity = prefersChineseCommunity,
        ).map { action ->
            RaftNativeServerSwitcherAction(
                id = RaftNativeServerSwitcherActionJoinCommunityPrefix + action.slug,
                label = action.label,
                imageAssetName = "lucide-plus",
                systemSymbol = "plus",
            )
        }
    val actions =
        buildList {
            addAll(joinActions)
            add(
                RaftNativeServerSwitcherAction(
                    id = RaftNativeServerSwitcherActionCreateServer,
                    label = labels.switchOrCreateServer,
                    imageAssetName = "lucide-plus",
                    systemSymbol = "plus",
                )
            )
            if (hasInviteHumanAction) {
                add(
                    RaftNativeServerSwitcherAction(
                        id = RaftNativeServerSwitcherActionInviteHuman,
                        label = labels.inviteHuman,
                        imageAssetName = "lucide-user-plus",
                        systemSymbol = "person.badge.plus",
                    )
                )
            }
        }
    return RaftNativeServerSwitcherState(
        open = true,
        rows = rows,
        actions = actions,
        emptyLabel = labels.noServersAvailable,
        switchPending = switchPending,
        reorderable = rows.size > 1 && !switchPending,
        anchorTopPt = anchorTopPt.coerceAtLeast(0.0),
        anchorStartPt = anchorStartPt.coerceAtLeast(0.0),
        maxWidthPt = maxWidthPt.coerceAtLeast(276.0),
    )
}

/**
 * Publishes the Home server switcher to the iOS 26+ native Liquid Glass host while composed.
 * Leaving composition (dismissed or covered) retracts the native overlay.
 */
@Composable
internal fun RaftNativeServerSwitcherPublisher(
    bridge: RaftNativeServerSwitcherBridge,
    servers: List<RaftServerSwitcherItem>,
    currentServerId: String,
    pendingServerId: String?,
    prefersChineseCommunity: Boolean,
    labels: RaftHomeShellLabels,
    anchorTopPt: Double,
    anchorStartPt: Double,
    onServerSelected: (String) -> Unit,
    onServerOrderChange: (List<String>) -> Unit,
    onCreateServerClick: () -> Unit,
    onInviteHumanClick: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val token = remember(bridge) { Any() }
    val currentOnSelect by rememberUpdatedState(onServerSelected)
    val currentOnOrderChange by rememberUpdatedState(onServerOrderChange)
    val currentOnCreate by rememberUpdatedState(onCreateServerClick)
    val currentOnInvite by rememberUpdatedState(onInviteHumanClick)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    val apiBaseUrl =
        RaftSharedStoreRegistry.currentOrNull()?.apiEnvironmentStore?.current()?.apiBaseUrl?.trimEnd('/')
    val avatarsByServerId =
        buildMap<String, RaftNativeTabBarAvatar?> {
            servers.forEach { server ->
                key(server.id) {
                    val presentation = server.toServerSwitcherAvatarPresentation()
                    val model =
                        presentation.avatarWireValue?.let { wire ->
                            raftAvatarImageCandidates(wire, apiBaseUrl).firstOrNull()
                        }
                    val bitmap = rememberAsyncImageBitmap(model, RaftNativeTabBarAvatarDecodePx)
                    put(server.id, remember(bitmap) { bitmap?.let(::raftNativeTabBarAvatar) })
                }
            }
        }
    val state =
        remember(
            servers,
            currentServerId,
            pendingServerId,
            prefersChineseCommunity,
            labels,
            onInviteHumanClick != null,
            avatarsByServerId,
            anchorTopPt,
            anchorStartPt,
        ) {
            raftNativeServerSwitcherState(
                open = true,
                servers = servers,
                currentServerId = currentServerId,
                pendingServerId = pendingServerId,
                prefersChineseCommunity = prefersChineseCommunity,
                labels = labels,
                hasInviteHumanAction = onInviteHumanClick != null,
                avatarsByServerId = avatarsByServerId,
                anchorTopPt = anchorTopPt,
                anchorStartPt = anchorStartPt,
            )
        }
    val selectHandler = remember(bridge) { { id: String -> currentOnSelect(id) } }
    val actionHandler =
        remember(bridge) {
            { actionId: String ->
                when {
                    actionId == RaftNativeServerSwitcherActionInviteHuman -> currentOnInvite?.invoke()
                    actionId == RaftNativeServerSwitcherActionCreateServer ||
                        actionId.startsWith(RaftNativeServerSwitcherActionJoinCommunityPrefix) -> currentOnCreate()
                }
            }
        }
    val reorderHandler = remember(bridge) { { order: List<String> -> currentOnOrderChange(order) } }
    val dismissHandler = remember(bridge) { { currentOnDismiss() } }

    DisposableEffect(bridge, state) {
        bridge.publish(
            token = token,
            state = state,
            onSelectServer = selectHandler,
            onTapAction = actionHandler,
            onReorderServers = reorderHandler,
            onDismiss = dismissHandler,
        )
        onDispose { }
    }
    DisposableEffect(bridge, token) {
        onDispose { bridge.retract(token) }
    }
}
