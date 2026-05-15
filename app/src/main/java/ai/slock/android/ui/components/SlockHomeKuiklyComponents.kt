package ai.slock.android.ui.components

import ai.slock.android.shared.SlockKuiklyActivity
import ai.slock.android.shared.SlockKuiklyPageIsland
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun SlockHomeActionRowKuikly(
        iconName: String,
        label: String,
        modifier: Modifier = Modifier,
        badge: String? = null,
        trailingText: String? = null,
        onClick: () -> Unit
) {
    Box(modifier = modifier) {
        SlockKuiklyPageIsland(
                pageName = SlockKuiklyActivity.PAGE_HOME_ACTION_ROW,
                pageData =
                        JSONObject()
                                .put(
                                        "payload",
                                        JSONObject()
                                                .put("iconName", iconName)
                                                .put("label", label)
                                                .put("badge", badge)
                                                .put("trailingText", trailingText)
                                                .toString()
                                ),
                modifier = Modifier.fillMaxSize(),
                onEvent = { event ->
                    if (event.optString("action") == "click") {
                        onClick()
                    }
                }
        )
        TransparentHomeAction(
                modifier =
                        Modifier.fillMaxSize()
                                .semantics {
                                    text = AnnotatedString(label)
                                    contentDescription = label
                                    role = Role.Button
                },
                onClick = onClick
        )
        badge?.takeIf { it.isNotBlank() }?.let { TransparentHomeText(it) }
        trailingText?.takeIf { it.isNotBlank() }?.let { TransparentHomeText(it) }
    }
}

@Composable
internal fun SlockHomeShellKuikly(
        currentServerName: String,
        currentServerId: String,
        servers: List<SlockServerSwitcherItem>,
        channels: List<SlockHomeConversationItem>,
        directMessages: List<SlockHomeConversationItem>,
        selectedChannelId: String?,
        serverUnreadCount: Int,
        inboxUnreadCount: Int,
        savedCount: Int,
        serverSwitcherExpanded: Boolean,
        labels: SlockHomeShellLabels,
        modifier: Modifier = Modifier,
        onAction: (String, String?) -> Unit
) {
    SlockKuiklyPageIsland(
            pageName = SlockKuiklyActivity.PAGE_HOME_SHELL,
            pageData =
                    JSONObject()
                            .put(
                                    "payload",
                                    JSONObject()
                                            .put("serverName", currentServerName)
                                            .put("currentServerId", currentServerId)
                                            .put("servers", servers.toJsonArray())
                                            .put("serverSwitcherExpanded", serverSwitcherExpanded)
                                            .put("unreadCount", serverUnreadCount)
                                            .put("searchLabel", labels.search)
                                            .put("inboxLabel", labels.inbox)
                                            .put("inboxUnreadCount", inboxUnreadCount)
                                            .put("savedLabel", labels.saved)
                                            .put("savedCount", savedCount)
                                            .put("channelsLabel", labels.channels)
                                            .put("channels", channels.toHomeJsonArray(selectedChannelId, showAvatar = false))
                                            .put("directMessagesLabel", labels.directMessages)
                                            .put("directMessages", directMessages.toHomeJsonArray(selectedChannelId, showAvatar = true))
                                            .toString()
                            ),
            modifier = modifier,
            onEvent = { event ->
                onAction(
                        event.optString("action").takeIf { it.isNotBlank() }.orEmpty(),
                        event.optString("itemId").takeIf { it.isNotBlank() }
                )
            }
    )
}

@Composable
internal fun SlockHomeConversationRowKuikly(
        id: String,
        name: String,
        avatarLabel: String?,
        presence: SlockPresence?,
        unreadCount: Int,
        hasDraft: Boolean,
        joined: Boolean,
        selected: Boolean,
        modifier: Modifier = Modifier,
        symbol: String = "#",
        onClick: (String) -> Unit
) {
    Box(modifier = modifier) {
        SlockKuiklyPageIsland(
                pageName = SlockKuiklyActivity.PAGE_HOME_CONVERSATION_ROW,
                pageData =
                        JSONObject()
                                .put(
                                        "payload",
                                        JSONObject()
                                                .put("id", id)
                                                .put("name", name)
                                                .put("symbol", symbol)
                                                .put("avatarLabel", avatarLabel)
                                                .put("presence", presence?.name?.lowercase())
                                                .put("badge", unreadCount.takeIf { it > 0 }?.toString())
                                                .put("hasDraft", hasDraft)
                                                .put("joined", joined)
                                                .put("selected", selected)
                                                .toString()
                                ),
                modifier = Modifier.fillMaxSize(),
                onEvent = { event ->
                    if (event.optString("action") == "click") {
                        event.optString("itemId").takeIf { it.isNotBlank() }?.let(onClick)
                    }
                }
        )
        TransparentHomeAction(
                modifier =
                        Modifier.fillMaxSize()
                                .semantics {
                                    text = AnnotatedString(name)
                                    role = Role.Button
                                    if (hasDraft) {
                                        contentDescription = "Unsent draft"
                                    }
                                },
                onClick = { onClick(id) }
        )
        unreadCount.takeIf { it > 0 }?.let { TransparentHomeText(it.toString()) }
    }
}

@Composable
internal fun SlockHomeHeaderKuikly(
        serverName: String,
        serverUnreadCount: Int,
        modifier: Modifier = Modifier,
        onServerBadgeClick: () -> Unit
) {
    SlockKuiklyPageIsland(
            pageName = SlockKuiklyActivity.PAGE_HOME_HEADER,
            pageData =
                    JSONObject()
                            .put(
                                    "payload",
                                    JSONObject()
                                            .put("serverName", serverName)
                                            .put("unreadCount", serverUnreadCount)
                                            .toString()
                            ),
            modifier = modifier,
            onEvent = { event ->
                if (event.optString("action") == "openServerSwitcher") {
                    onServerBadgeClick()
                }
            }
    )
}

@Composable
internal fun SlockHomeServerSwitcherKuikly(
        servers: List<SlockServerSwitcherItem>,
        currentServerId: String,
        labels: SlockHomeShellLabels,
        modifier: Modifier = Modifier,
        onServerSelected: (String) -> Unit,
        onCreateServerClick: () -> Unit
) {
    SlockKuiklyPageIsland(
            pageName = SlockKuiklyActivity.PAGE_HOME_SERVER_SWITCHER,
            pageData =
                    JSONObject()
                            .put(
                                    "payload",
                                    JSONObject()
                                            .put("currentServerId", currentServerId)
                                            .put("noServersLabel", labels.noServersAvailable)
                                            .put("createServerLabel", labels.switchOrCreateServer)
                                            .put("servers", servers.toJsonArray())
                                            .toString()
                            ),
            modifier =
                    modifier.height(
                            ((servers.size.coerceAtLeast(1) * 48) + 48)
                                    .coerceAtMost(384)
                                    .dp
                    ),
            onEvent = { event ->
                when (event.optString("action")) {
                    "selectServer" ->
                            event.optString("itemId")
                                    .takeIf { it.isNotBlank() }
                                    ?.let(onServerSelected)
                    "createServer" -> onCreateServerClick()
                }
            }
    )
}

@Composable
internal fun SlockHomeSectionHeaderKuikly(
        label: String,
        count: Int,
        expanded: Boolean,
        showAdd: Boolean,
        addLabel: String,
        modifier: Modifier = Modifier,
        onExpandedChange: (Boolean) -> Unit,
        onSortClick: () -> Unit,
        onAddClick: () -> Unit
) {
    val toggle = { onExpandedChange(!expanded) }
    Box(modifier = modifier) {
        SlockKuiklyPageIsland(
                pageName = SlockKuiklyActivity.PAGE_HOME_SECTION_HEADER,
                pageData =
                        JSONObject()
                                .put(
                                        "payload",
                                        JSONObject()
                                                .put("label", label)
                                                .put("count", count)
                                                .put("expanded", expanded)
                                                .put("showAdd", showAdd)
                                                .put("addLabel", addLabel)
                                                .toString()
                                ),
                modifier = Modifier.fillMaxSize(),
                onEvent = { event ->
                    when (event.optString("action")) {
                        "toggle" -> toggle()
                        "sort" -> onSortClick()
                        "add" -> onAddClick()
                    }
                }
        )
        Row(modifier = Modifier.fillMaxSize()) {
            TransparentHomeAction(
                    modifier =
                            Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .semantics {
                                        text = AnnotatedString(label)
                                        role = Role.Button
                                    },
                    onClick = toggle
            )
            TransparentHomeAction(
                    modifier =
                            Modifier
                                    .size(30.dp)
                                    .semantics {
                                        contentDescription = "Sort $label"
                                        role = Role.Button
                                    },
                    onClick = onSortClick
            )
            if (showAdd) {
                TransparentHomeAction(
                        modifier =
                                Modifier
                                        .size(30.dp)
                                        .semantics {
                                            contentDescription = addLabel
                                            role = Role.Button
                                        },
                        onClick = onAddClick
                )
            }
        }
    }
}

private fun List<SlockServerSwitcherItem>.toJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { server ->
        array.put(
                JSONObject()
                        .put("id", server.id)
                        .put("name", server.name)
                        .put("slug", server.slug)
                        .put("unreadCount", server.unreadCount)
        )
    }
    return array
}

private fun List<SlockHomeConversationItem>.toHomeJsonArray(
        selectedChannelId: String?,
        showAvatar: Boolean
): JSONArray {
    val array = JSONArray()
    forEach { item ->
        array.put(
                JSONObject()
                        .put("id", item.id)
                        .put("name", item.name)
                        .put("symbol", if (showAvatar) "" else "#")
                        .put("avatarLabel", item.avatarLabel.takeIf { showAvatar })
                        .put("presence", item.presence?.name?.lowercase())
                        .put("badge", item.unreadCount.takeIf { it > 0 }?.toString())
                        .put("hasDraft", item.hasDraft)
                        .put("joined", item.joined)
                        .put("selected", item.id == selectedChannelId)
        )
    }
    return array
}

@Composable
private fun TransparentHomeAction(
        modifier: Modifier,
        onClick: () -> Unit
) {
    Box(
            modifier =
                    modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick
                    )
    )
}

@Composable
private fun TransparentHomeText(textValue: String) {
    Box(modifier = Modifier.size(1.dp).semantics { text = AnnotatedString(textValue) })
}
