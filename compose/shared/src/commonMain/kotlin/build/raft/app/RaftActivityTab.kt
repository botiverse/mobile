package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import build.raft.app.data.isAppUpdateHomeNotificationId
import build.raft.app.data.isMachineHomeNotificationId

/**
 * Activity as an app-shell bottom tab (artin 2026-10-20): 首页 · 动态 · 任务 · 成员 · 设置.
 *
 * The tab hosts the existing Activity page content ([SharedHomeActivityScreen]) and adds two
 * things around it, both owned by the shell rather than by the Activity list:
 * - the main-tab header (icon sticker + title + stats line, no back arrow);
 * - a 4th segment, 通知 / Notifications, that renders the former Home notification center
 *   (the Bell popover that left the Home head).
 *
 * The 通知 segment is shell state, not an [build.raft.app.data.InboxFilter]: the inbox store
 * keeps exactly its three server-backed filters, and the notifications come from the Home
 * store's notification list (the same data the Bell showed).
 */
internal class RaftActivityTabChrome(
    /** True while the 通知 segment is the selected Activity page. */
    val notificationsSelected: Boolean,
    /** Count shown on the 通知 segment (visible, non app-update notifications). */
    val notificationCount: Int,
    val onNotificationsSelectedChange: (Boolean) -> Unit,
    val notificationsContent: @Composable (active: Boolean) -> Unit,
)

/** pageData key: which Activity segment a launch into the Activity tab should show. */
internal const val AppShellInitialActivitySegmentKey = "slockInitialActivitySegment"

/** Segment value for the notification center inside the Activity tab. */
internal const val RaftActivityNotificationsSegment = "notifications"

/**
 * `slockInitialTab` values that name the notification center. There was never a standalone
 * notification-center page (it was a Home popover), but a launch that asks for it lands on the
 * Activity tab's 通知 segment.
 */
private val RaftActivityNotificationsTabAliases = setOf("notifications", "notification_center", "notificationCenter")

/** `slockInitialTab` values that name the old Home 动态 row / Activity page. */
private val RaftActivityTabAliases = setOf("activity", "inbox", "home_activity", HOME_ACTIVITY_PAGE)

internal data class RaftAppShellLaunchTab(
    val tab: String,
    val activityNotificationsSegment: Boolean = false,
)

/**
 * Resolves the launch tab from `slockInitialTab` / `slockInitialActivitySegment`. Activity and
 * notification-center aliases land on the Activity tab; everything else keeps the retained-tab
 * clamp ([retainedAppShellSelectedTab]).
 */
internal fun raftAppShellLaunchTab(rawTab: String?, rawActivitySegment: String?): RaftAppShellLaunchTab {
    val tab = rawTab?.trim().orEmpty()
    return when {
        tab in RaftActivityNotificationsTabAliases ->
            RaftAppShellLaunchTab(tab = "activity", activityNotificationsSegment = true)
        tab in RaftActivityTabAliases ->
            RaftAppShellLaunchTab(
                tab = "activity",
                activityNotificationsSegment = rawActivitySegment?.trim() == RaftActivityNotificationsSegment
            )
        else -> RaftAppShellLaunchTab(tab = retainedAppShellSelectedTab(tab))
    }
}

/** The Activity tab badge: Activity unread plus notification-center items needing attention. */
internal fun raftActivityTabBadgeCount(activityUnreadCount: Int, notificationCount: Int): Int =
    activityUnreadCount.coerceAtLeast(0) + notificationCount.coerceAtLeast(0)

/** Notification-center items the 通知 segment lists (the app update keeps its own Home banner). */
internal fun List<RaftHomeNotificationItem>.raftActivityNotificationItems(): List<RaftHomeNotificationItem> =
    nonAppUpdateHomeNotifications()

/**
 * The primary action of a notification-center item. Shared by the Home app-update banner and the
 * Activity tab's 通知 segment so both keep the routing the Bell popover had.
 */
internal fun routeRaftHomeNotificationView(
    notification: RaftHomeNotificationItem,
    onAppUpdateOpen: (() -> Unit)?,
    onOpenComputers: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val target = notification.targetId
    when {
        notification.id.isAppUpdateHomeNotificationId() && onAppUpdateOpen != null -> onAppUpdateOpen()
        notification.id.isMachineHomeNotificationId() -> onOpenComputers()
        target != null && (target.startsWith("http://") || target.startsWith("https://")) -> onOpenUrl(target)
        else -> Unit
    }
}

/** The Activity tab's 通知 segment: the former Home notification center as a full page body. */
@Composable
internal fun RaftActivityNotificationsPane(
    notifications: List<RaftHomeNotificationItem>,
    onView: (RaftHomeNotificationItem) -> Unit,
    onDismiss: (RaftHomeNotificationItem) -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val items = remember(notifications) { notifications.raftActivityNotificationItems() }
    Box(modifier = modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        if (items.isEmpty()) {
            HomeNotificationCenterEmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomContentPadding),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, notification ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
                    }
                    HomeNotificationRow(
                        notification = notification,
                        onView = { onView(notification) },
                        onDismiss = { onDismiss(notification) },
                    )
                }
            }
        }
    }
}
