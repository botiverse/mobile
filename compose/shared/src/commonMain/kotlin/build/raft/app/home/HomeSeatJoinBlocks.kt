package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.data.PRO_SEAT_ADD_FEATURE_FLAG_KEY
import build.raft.app.data.RaftFreshnessSignal
import build.raft.app.data.RaftFreshnessTrigger
import build.raft.app.data.SEAT_JOIN_BLOCKS_POLL_MS
import build.raft.app.data.SeatJoinBlock
import build.raft.app.data.SeatJoinSeatUsage
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedSeatJoinBlocksIntent
import build.raft.app.data.SharedSeatJoinBlocksState
import build.raft.app.data.SharedSeatJoinBlocksStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.seatJoinBlockedHomeNotificationId
import build.raft.app.data.serverRoleCanReadSeatJoinBlocks
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

internal class HomeSeatJoinBlocksUi(
    /** Notification-center rows (web `useSystemNotifications` section "0b"). */
    val items: List<RaftHomeNotificationItem>,
    val blockById: Map<String, SeatJoinBlock>,
    val usage: SeatJoinSeatUsage?,
    val dismiss: (String) -> Unit,
    val refresh: () -> Unit,
)

internal data class HomeSeatJoinBlocksAudience(
    val serverId: String?,
    val serverName: String,
    val role: String?,
    val isOwner: Boolean,
    val canRead: Boolean,
)

/**
 * Only non-guest members on servers where `pro_seat_add` is enabled fetch and render
 * `GET /api/servers/:id/seat-join-blocks` (`botiverse/slock` `#11576`, web `useSeatJoinBlocks.ts`).
 */
internal fun homeSeatJoinBlocksAudience(
    content: SharedHomeContentState,
    proSeatAddEnabled: Boolean,
): HomeSeatJoinBlocksAudience {
    val server = (content as? SharedHomeContentState.Content)?.snapshot?.server
    val serverId = server?.id?.value?.trim()?.takeIf { it.isNotEmpty() }
    val role = server?.role?.trim()?.takeIf { it.isNotEmpty() }
    val isOwner = role?.lowercase() == "owner"
    val canRead = serverId != null && proSeatAddEnabled && serverRoleCanReadSeatJoinBlocks(role)
    return HomeSeatJoinBlocksAudience(
        serverId = serverId,
        serverName = server?.name.orEmpty(),
        role = role,
        isOwner = isOwner,
        canRead = canRead,
    )
}

/** Visible notices for [currentServerId] only; rows still owned by another server show nothing. */
internal fun SharedSeatJoinBlocksState.homeSeatJoinBlockRows(
    currentServerId: String?,
): List<SeatJoinBlock> =
    if (currentServerId != null && serverId == currentServerId) visibleBlocks else emptyList()

/**
 * Builds the Home Notification Center row for a seat-join-blocked notice (web
 * `useSystemNotifications.tsx` section "0b"):
 * - Android/Desktop (`offersUpgrade = true`): `"Upgrade needed for {name} to join"`, primary action
 *   `"Add seats"` (owner) or `"Ask an owner"` (non-owner).
 * - iOS view-only (`offersUpgrade = false`, App Store Guideline 3.1.1): neutral seat-capacity body
 *   with zero upgrade/purchase/ask-owner wording and no primary action button, keeping Dismiss working.
 */
internal fun SeatJoinBlock.toHomeNotificationItem(
    title: String,
    body: String,
    actionLabel: String?,
): RaftHomeNotificationItem =
    RaftHomeNotificationItem(
        id = seatJoinBlockedHomeNotificationId(id),
        title = title,
        body = body,
        actionLabel = actionLabel.orEmpty(),
        targetId = userId,
        kind = RaftHomeNotificationKind.Warning,
        icon = RaftHomeNotificationIcon.Default,
    )

@Composable
internal fun seatJoinBlockNotificationItem(
    block: SeatJoinBlock,
    serverName: String,
    isOwner: Boolean,
    offersUpgrade: Boolean = settingsBillingOffersUpgrade(),
): RaftHomeNotificationItem {
    val title = stringResource(Res.string.home_notification_seat_join_blocked_title, serverName, block.name)
    val body =
        if (offersUpgrade) {
            stringResource(Res.string.home_notification_seat_join_blocked_body, block.name)
        } else {
            stringResource(Res.string.home_notification_seat_join_blocked_body_view_only, block.name)
        }
    val actionLabel =
        if (!offersUpgrade) {
            null
        } else if (isOwner) {
            stringResource(Res.string.home_notification_seat_join_add_seats)
        } else {
            stringResource(Res.string.home_notification_seat_join_ask_owner)
        }
    return block.toHomeNotificationItem(
        title = title,
        body = body,
        actionLabel = actionLabel,
    )
}

/**
 * Server-scoped seat-join-blocked notices for the Home notification center
 * (`GET /api/servers/:id/seat-join-blocks`, `slock #11576`, gated by `pro_seat_add`).
 *
 * - Fetches only when `pro_seat_add` is ON and role is non-guest; guests and flag-off servers make
 *   zero requests and clear state immediately.
 * - Polls every 5 minutes while active, refreshes on reconnect/foreground/network restore, and
 *   updates immediately on `seat-join-blocks:changed` realtime pings via `AppShellSeatJoinBlocksEffect`.
 */
@Composable
internal fun rememberHomeSeatJoinBlocks(
    store: SharedSeatJoinBlocksStore?,
    serverFeatureFlags: SharedServerFeatureFlags?,
    content: SharedHomeContentState,
    active: Boolean,
    offersUpgrade: Boolean = settingsBillingOffersUpgrade(),
): HomeSeatJoinBlocksUi {
    val fallbackStateFlow = remember { MutableStateFlow(SharedSeatJoinBlocksState()) }
    val state by (store?.state ?: fallbackStateFlow).collectAsState()
    val fallbackFlagRevFlow = remember { MutableStateFlow(0L) }
    val flagRevision by (serverFeatureFlags?.snapshotRevision ?: fallbackFlagRevFlow).collectAsState()
    val currentServerId = (content as? SharedHomeContentState.Content)?.snapshot?.server?.id?.value?.trim()?.takeIf { it.isNotEmpty() }
    val proSeatAddEnabled =
        remember(serverFeatureFlags, currentServerId, flagRevision) {
            currentServerId != null && serverFeatureFlags?.isEnabled(ServerId(currentServerId), PRO_SEAT_ADD_FEATURE_FLAG_KEY) == true
        }
    val audience = homeSeatJoinBlocksAudience(content, proSeatAddEnabled)
    val serverId = audience.serverId
    val canRead = audience.canRead
    LaunchedEffect(store, serverId, proSeatAddEnabled, audience.role) {
        store?.dispatch(
            SharedSeatJoinBlocksIntent.Load(
                serverId = serverId,
                proSeatAddEnabled = proSeatAddEnabled,
                role = audience.role,
            )
        )
    }
    LaunchedEffect(store, active, serverId, canRead) {
        if (store == null || !active || serverId == null || !canRead) return@LaunchedEffect
        store.dispatch(SharedSeatJoinBlocksIntent.RefreshIfStale)
        while (true) {
            delay(SEAT_JOIN_BLOCKS_POLL_MS)
            store.dispatch(SharedSeatJoinBlocksIntent.Refresh)
        }
    }
    LaunchedEffect(store, serverId, canRead) {
        if (store == null || serverId == null || !canRead) return@LaunchedEffect
        RaftFreshnessSignal.events.collect { event ->
            when (event.trigger) {
                RaftFreshnessTrigger.Reconnect -> {
                    val eventServerId = event.serverId
                    if (eventServerId == null || eventServerId == serverId) {
                        store.dispatch(SharedSeatJoinBlocksIntent.RefreshIfStale)
                    }
                }
                RaftFreshnessTrigger.Foreground,
                RaftFreshnessTrigger.NetworkRestored ->
                    store.dispatch(SharedSeatJoinBlocksIntent.RefreshIfStale)
            }
        }
    }
    val rows = state.homeSeatJoinBlockRows(serverId)
    val items =
        rows.map { block ->
            seatJoinBlockNotificationItem(
                block = block,
                serverName = audience.serverName,
                isOwner = audience.isOwner,
                offersUpgrade = offersUpgrade,
            )
        }
    val blockById = remember(rows) { rows.associateBy { it.id } }
    val activeUsage = if (serverId != null && state.serverId == serverId) state.usage else null
    return HomeSeatJoinBlocksUi(
        items = items,
        blockById = blockById,
        usage = activeUsage,
        dismiss = { blockId -> store?.dispatch(SharedSeatJoinBlocksIntent.Dismiss(blockId)) },
        refresh = { store?.dispatch(SharedSeatJoinBlocksIntent.Refresh) },
    )
}
