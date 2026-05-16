package ai.slock.android.ui.components

import ai.slock.android.R
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.shared.SlockKuiklyActivity
import ai.slock.android.shared.SlockKuiklyPageIsland
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

// Android keeps this API surface while the implementation is rendered by shared Kuikly Home.
data class SlockServerSwitcherItem(
    val id: String,
    val name: String,
    val slug: String,
    val unreadCount: Int = 0
)

data class SlockHomeConversationItem(
    val id: String,
    val name: String,
    val unreadCount: Int = 0,
    val avatarLabel: String = name.take(2).uppercase(),
    val avatarUrl: String? = null,
    val avatar: AvatarReference? = AvatarReference.fromWireValue(avatarUrl),
    val presence: SlockPresence? = null,
    val hasDraft: Boolean = false,
    val joined: Boolean = true
)

data class SlockHomeNotificationItem(
    val id: String,
    val title: String,
    val body: String,
    val actionLabel: String = "View",
    val targetId: String? = null,
    val kind: SlockHomeNotificationKind = SlockHomeNotificationKind.Warning
)

enum class SlockHomeNotificationKind { Warning, Info, Error }

data class SlockHomeShellLabels(
    val search: String = "Search",
    val inbox: String = "Inbox",
    val followedThreads: String = "Followed threads",
    val saved: String = "Saved",
    val channels: String = "CHANNELS",
    val directMessages: String = "DIRECT MESSAGES",
    val addChannel: String = "Add channel",
    val noServersAvailable: String = "No servers available",
    val switchOrCreateServer: String = "Switch or create server"
)

@Composable
fun SlockStructuralHomeShell(
    modifier: Modifier = Modifier,
    currentServerName: String = "Slock Android",
    currentServerId: String = "slock-android",
    servers: List<SlockServerSwitcherItem> = emptyList(),
    channels: List<SlockHomeConversationItem> = emptyList(),
    directMessages: List<SlockHomeConversationItem> = emptyList(),
    selectedChannelId: String? = "android-dev",
    serverUnreadCount: Int = 5,
    inboxUnreadCount: Int = 0,
    savedCount: Int = 0,
    notifications: List<SlockHomeNotificationItem> = emptyList(),
    serverSwitcherExpanded: Boolean = false,
    contentRefreshing: Boolean = false,
    contentRefreshEnabled: Boolean = true,
    selectedTabKey: String = "home",
    showBottomBar: Boolean = true,
    bottomContentPadding: Dp = 24.dp,
    outerPadding: Dp = 0.dp,
    onContentRefresh: () -> Unit = {},
    onServerBadgeClick: () -> Unit = {},
    onServerSelected: (String) -> Unit = {},
    onCreateServerClick: () -> Unit = {},
    onCreateChannelClick: () -> Unit = {},
    onTabSelected: (String) -> Unit = {},
    onInboxClick: () -> Unit = {},
    onFollowedThreadsClick: () -> Unit = {},
    onSavedClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onNotificationView: (SlockHomeNotificationItem) -> Unit = {},
    onNotificationDismiss: (SlockHomeNotificationItem) -> Unit = {},
    onChannelClick: (String) -> Unit = {}
) {
    val notificationById = notifications.associateBy { it.id }
    val labels =
        JSONObject()
            .put("search", stringResource(R.string.home_search))
            .put("inbox", stringResource(R.string.home_inbox))
            .put("followedThreads", stringResource(R.string.home_followed_threads))
            .put("saved", stringResource(R.string.home_saved))
            .put("channels", stringResource(R.string.home_channels).uppercase())
            .put("directMessages", stringResource(R.string.home_direct_messages).uppercase())
            .put("addChannel", stringResource(R.string.home_add_channel))
            .put("noServersAvailable", stringResource(R.string.home_no_servers_available))
            .put("switchOrCreateServer", stringResource(R.string.home_switch_or_create_server))
    SlockKuiklyPageIsland(
        pageName = SlockKuiklyActivity.PAGE_HOME,
        pageData = JSONObject().put(
            "payload",
            JSONObject()
                .put("currentServerName", currentServerName)
                .put("currentServerId", currentServerId)
                .put("servers", servers.toJsonArray { it.toJson() })
                .put("channels", channels.toJsonArray { it.toJson() })
                .put("directMessages", directMessages.toJsonArray { it.toJson() })
                .put("selectedChannelId", selectedChannelId)
                .put("serverUnreadCount", serverUnreadCount)
                .put("inboxUnreadCount", inboxUnreadCount)
                .put("savedCount", savedCount)
                .put("notifications", notifications.toJsonArray { it.toJson() })
                .put("serverSwitcherExpanded", serverSwitcherExpanded)
                .put("contentRefreshing", contentRefreshing)
                .put("contentRefreshEnabled", contentRefreshEnabled)
                .put("bottomContentPaddingDp", bottomContentPadding.value)
                .put("labels", labels)
                .toString()
        ),
        modifier = modifier,
        onEvent = { event ->
            when (event.optString("action")) {
                "toggleServerSwitcher" -> onServerBadgeClick()
                "selectServer" -> event.optString("itemId").takeIf { it.isNotBlank() }?.let(onServerSelected)
                "createServer" -> onCreateServerClick()
                "createChannel" -> onCreateChannelClick()
                "openInbox" -> onInboxClick()
                "openFollowedThreads" -> onOpenFollowedThreadsSafely(onFollowedThreadsClick)
                "openSaved" -> onSavedClick()
                "openSearch" -> onSearchClick()
                "viewNotification" -> event.optString("itemId").let(notificationById::get)?.let(onNotificationView)
                "dismissNotification" -> event.optString("itemId").let(notificationById::get)?.let(onNotificationDismiss)
                "openChannel" -> event.optString("itemId").takeIf { it.isNotBlank() }?.let(onChannelClick)
            }
        }
    )
}

private fun onOpenFollowedThreadsSafely(action: () -> Unit) = action()

private fun SlockServerSwitcherItem.toJson(): JSONObject =
    JSONObject().put("id", id).put("name", name).put("slug", slug).put("unreadCount", unreadCount)

private fun SlockHomeConversationItem.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("name", name)
        .put("unreadCount", unreadCount)
        .put("avatarLabel", avatarLabel)
        .put("avatarUrl", avatarUrl)
        .put("avatarWireValue", avatar?.wireValue ?: avatarUrl)
        .put("presence", presence?.toSharedName())
        .put("hasDraft", hasDraft)
        .put("joined", joined)

private fun SlockHomeNotificationItem.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put("body", body)
        .put("actionLabel", actionLabel)
        .put("targetId", targetId)
        .put("kind", kind.name)

private fun SlockPresence.toSharedName(): String =
    when (this) {
        SlockPresence.Online -> "Online"
        SlockPresence.Offline -> "Offline"
        SlockPresence.Unknown -> "Unknown"
    }

private inline fun <T> List<T>.toJsonArray(mapper: (T) -> JSONObject): JSONArray =
    JSONArray().also { array -> forEach { array.put(mapper(it)) } }
