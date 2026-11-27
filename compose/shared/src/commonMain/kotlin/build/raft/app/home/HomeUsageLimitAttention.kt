package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.data.RaftFreshnessSignal
import build.raft.app.data.RaftFreshnessTrigger
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedUsageLimitAttentionIntent
import build.raft.app.data.SharedUsageLimitAttentionState
import build.raft.app.data.SharedUsageLimitAttentionStore
import build.raft.app.data.USAGE_LIMIT_ATTENTION_POLL_MS
import build.raft.app.data.UsageLimitAttentionNotification
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.serverRoleCanReceiveSystemAttention
import build.raft.app.data.usageLimitHomeNotificationId
import build.raft.app.data.usageLimitProviderDisplayName
import build.raft.app.localized
import build.raft.app.members.localized
import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.app.settings.toHomeNotificationItem
import build.raft.app.thread.dispatch
import build.raft.app.thread.icon
import build.raft.app.toHomeNotificationItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

internal class HomeUsageLimitAttentionUi(
    /** Notification-center rows (web `useSystemNotifications` section "0"). */
    val items: List<RaftHomeNotificationItem>,
    val markSeen: (String) -> Unit,
    val markAllUnseenSeen: () -> Unit,
    val dismiss: (String) -> Unit,
    val refresh: () -> Unit,
)

/**
 * Only server owners and admins fetch and render `/api/servers/:id/system-notifications-v2`
 * (slock #13079 `UsageLimitAttentionService.listForReceiver` — no feature flag).
 */
internal fun homeUsageLimitAttentionAudience(content: SharedHomeContentState): Pair<String?, Boolean> {
    val server = (content as? SharedHomeContentState.Content)?.snapshot?.server
    return server?.id?.value to serverRoleCanReceiveSystemAttention(server?.role)
}

/** Notifications for [currentServerId] only; rows still owned by another server show nothing. */
internal fun SharedUsageLimitAttentionState.homeUsageLimitAttentionRows(
    currentServerId: String?,
): List<UsageLimitAttentionNotification> =
    if (currentServerId != null && serverId == currentServerId) notifications else emptyList()

/**
 * Builds the Home Notification Center row for a usage-limit attention incident (web
 * `useSystemNotifications.tsx` section "0": warning triangle, `"View"` -> Computer page,
 * `"Dismiss"` -> receiver-scoped dismiss receipt, `unread = seenAt == null`).
 */
internal fun UsageLimitAttentionNotification.toHomeNotificationItem(
    title: String,
    body: String,
    viewLabel: String,
): RaftHomeNotificationItem {
    val seenAtValue = seenAt
    return RaftHomeNotificationItem(
        id = usageLimitHomeNotificationId(id),
        title = title,
        body = body,
        actionLabel = viewLabel,
        targetId = machine.id,
        kind = RaftHomeNotificationKind.Warning,
        icon = RaftHomeNotificationIcon.Default,
        unread = seenAtValue == null,
    )
}

/**
 * Resolves the localized relative future reset wording matching web `resetWhen` (`rackResetText.ts`):
 * minutes under an hour, hours under 48 hours, then days, null when unknown, implausible, or past.
 */
@Composable
internal fun usageLimitAttentionResetWhen(
    resetsAt: String?,
    timeContext: RaftTimeContext? = null,
): String? {
    val rawResetsAt = resetsAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val nowMillis = currentRaftTimeContext(timeContext)?.nowEpochMillis ?: defaultNowMillis()
    val reset = raftRackReset(rawResetsAt, nowMillis) ?: return null
    return RaftRelativeTimeLabel.Relative(reset.count, reset.unit).localized()
}

@Composable
internal fun usageLimitAttentionNotificationItem(
    notification: UsageLimitAttentionNotification,
    timeContext: RaftTimeContext? = null,
): RaftHomeNotificationItem {
    val provider = usageLimitProviderDisplayName(notification.provider)
    val count = notification.affectedAgentIds.size
    val computerName = notification.machine.name
    val reset = usageLimitAttentionResetWhen(notification.resetsAt, timeContext)
    val title = stringResource(Res.string.home_notification_usage_limit_title, provider)
    val body =
        if (reset != null) {
            val res =
                if (count == 1) {
                    Res.string.home_notification_usage_limit_body_with_reset_one
                } else {
                    Res.string.home_notification_usage_limit_body_with_reset_many
                }
            stringResource(res, count, computerName, reset)
        } else {
            val res =
                if (count == 1) {
                    Res.string.home_notification_usage_limit_body_one
                } else {
                    Res.string.home_notification_usage_limit_body_many
                }
            stringResource(res, count, computerName)
        }
    return notification.toHomeNotificationItem(
        title = title,
        body = body,
        viewLabel = stringResource(Res.string.home_action_view),
    )
}

/**
 * Owner/admin runtime-account usage-limit attention notifications for the Home notification center
 * (`GET /api/servers/:id/system-notifications-v2`, `slock #13079`).
 *
 * - Fetches only for `owner` and `admin` roles; `member` and `guest` make zero requests.
 * - Polls every 60 s while active, refreshes on reconnect/foreground/network restore, and supports
 *   receiver-scoped `seen` and `dismiss` receipts synced across devices.
 */
@Composable
internal fun rememberHomeUsageLimitAttention(
    store: SharedUsageLimitAttentionStore?,
    content: SharedHomeContentState,
    active: Boolean,
    timeContext: RaftTimeContext? = null,
): HomeUsageLimitAttentionUi {
    val fallbackFlow = remember { MutableStateFlow(SharedUsageLimitAttentionState()) }
    val state by (store?.state ?: fallbackFlow).collectAsState()
    val (serverId, canReceive) = homeUsageLimitAttentionAudience(content)
    LaunchedEffect(store, serverId, canReceive) {
        store?.dispatch(SharedUsageLimitAttentionIntent.Load(serverId = serverId, canReceive = canReceive))
    }
    LaunchedEffect(store, active, serverId, canReceive) {
        if (store == null || !active || serverId == null || !canReceive) return@LaunchedEffect
        store.dispatch(SharedUsageLimitAttentionIntent.RefreshIfStale)
        while (true) {
            delay(USAGE_LIMIT_ATTENTION_POLL_MS)
            store.dispatch(SharedUsageLimitAttentionIntent.Refresh)
        }
    }
    LaunchedEffect(store, serverId, canReceive) {
        if (store == null || serverId == null || !canReceive) return@LaunchedEffect
        RaftFreshnessSignal.events.collect { event ->
            when (event.trigger) {
                RaftFreshnessTrigger.Reconnect -> {
                    val eventServerId = event.serverId
                    if (eventServerId == null || eventServerId == serverId) {
                        store.dispatch(SharedUsageLimitAttentionIntent.RefreshIfStale)
                    }
                }
                RaftFreshnessTrigger.Foreground,
                RaftFreshnessTrigger.NetworkRestored ->
                    store.dispatch(SharedUsageLimitAttentionIntent.RefreshIfStale)
            }
        }
    }
    val rows = state.homeUsageLimitAttentionRows(serverId)
    val items = rows.map { notification -> usageLimitAttentionNotificationItem(notification, timeContext) }
    return HomeUsageLimitAttentionUi(
        items = items,
        markSeen = { incidentId -> store?.dispatch(SharedUsageLimitAttentionIntent.MarkSeen(incidentId)) },
        markAllUnseenSeen = { store?.dispatch(SharedUsageLimitAttentionIntent.MarkAllUnseenSeen) },
        dismiss = { incidentId -> store?.dispatch(SharedUsageLimitAttentionIntent.Dismiss(incidentId)) },
        refresh = { store?.dispatch(SharedUsageLimitAttentionIntent.Refresh) },
    )
}
