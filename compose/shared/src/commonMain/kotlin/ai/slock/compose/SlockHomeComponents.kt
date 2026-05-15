package ai.slock.compose

import ai.slock.compose.base.Utils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val HOME_HEADER_PAGE = "compose_home_header"
const val HOME_SERVER_SWITCHER_PAGE = "compose_home_server_switcher"
const val HOME_SECTION_HEADER_PAGE = "compose_home_section_header"
const val HOME_ACTION_ROW_PAGE = "compose_home_action_row"
const val HOME_CONVERSATION_ROW_PAGE = "compose_home_conversation_row"
const val HOME_SHELL_PAGE = "compose_home_shell"
const val HOME_COMPONENTS_SMOKE_PAGE = "compose_home_components_smoke"

@Page(HOME_HEADER_PAGE, supportInLocal = true)
internal class ComposeHomeHeaderPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeHeaderPageContent() }
    }
}

@Page(HOME_SERVER_SWITCHER_PAGE, supportInLocal = true)
internal class ComposeHomeServerSwitcherPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeServerSwitcherPageContent() }
    }
}

@Page(HOME_SECTION_HEADER_PAGE, supportInLocal = true)
internal class ComposeHomeSectionHeaderPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSectionHeaderPageContent() }
    }
}

@Page(HOME_ACTION_ROW_PAGE, supportInLocal = true)
internal class ComposeHomeActionRowPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeActionRowPageContent() }
    }
}

@Page(HOME_CONVERSATION_ROW_PAGE, supportInLocal = true)
internal class ComposeHomeConversationRowPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeConversationRowPageContent() }
    }
}

@Page(HOME_SHELL_PAGE, supportInLocal = true)
internal class ComposeHomeShellPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeShellPageContent() }
    }
}

@Page(HOME_COMPONENTS_SMOKE_PAGE, supportInLocal = true)
internal class ComposeHomeComponentsSmokePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeComponentsSmokeContent() }
    }
}

@Serializable
internal data class KuiklyHomeHeaderPayload(
    val instanceId: String = "",
    val serverName: String = "SLOCK",
    val unreadCount: Int = 0
)

@Serializable
internal data class KuiklyHomeServerSwitcherPayload(
    val instanceId: String = "",
    val currentServerId: String = "",
    val noServersLabel: String = "No servers available",
    val createServerLabel: String = "Switch or create server",
    val servers: List<KuiklyHomeServerItem> = emptyList()
)

@Serializable
internal data class KuiklyHomeServerItem(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val unreadCount: Int = 0
)

@Serializable
internal data class KuiklyHomeSectionHeaderPayload(
    val instanceId: String = "",
    val label: String = "",
    val count: Int = 0,
    val expanded: Boolean = true,
    val showAdd: Boolean = false,
    val addLabel: String = "Add"
)

@Serializable
internal data class KuiklyHomeActionRowPayload(
    val instanceId: String = "",
    val iconName: String = "search",
    val label: String = "",
    val badge: String? = null,
    val trailingText: String? = null
)

@Serializable
internal data class KuiklyHomeConversationRowPayload(
    val instanceId: String = "",
    val id: String = "",
    val name: String = "",
    val symbol: String = "#",
    val avatarLabel: String? = null,
    val presence: String? = null,
    val badge: String? = null,
    val hasDraft: Boolean = false,
    val joined: Boolean = true,
    val selected: Boolean = false
)

@Serializable
internal data class KuiklyHomeShellPayload(
    val instanceId: String = "",
    val serverName: String = "Slock",
    val unreadCount: Int = 0,
    val searchLabel: String = "Search",
    val inboxLabel: String = "Inbox",
    val inboxUnreadCount: Int = 0,
    val savedLabel: String = "Saved",
    val savedCount: Int = 0,
    val channelsLabel: String = "CHANNELS",
    val channelsExpanded: Boolean = true,
    val channels: List<KuiklyHomeConversationRowPayload> = emptyList(),
    val directMessagesLabel: String = "DIRECT MESSAGES",
    val directMessagesExpanded: Boolean = true,
    val directMessages: List<KuiklyHomeConversationRowPayload> = emptyList()
)

@Composable
private fun HomeHeaderPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = rememberHomePayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.optString("instanceId"),
        KuiklyHomeHeaderPayload.serializer()
    ) { KuiklyHomeHeaderPayload(instanceId = it) }
    LaunchedEffect(payload) {
        bridge.log("home_header_render instanceId=${payload.instanceId} server=${payload.serverName}")
    }
    SlockHomeHeader(payload) {
        bridge.homeEvent(payload.instanceId, "openServerSwitcher")
    }
}

@Composable
private fun HomeServerSwitcherPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = rememberHomePayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.optString("instanceId"),
        KuiklyHomeServerSwitcherPayload.serializer()
    ) { KuiklyHomeServerSwitcherPayload(instanceId = it) }
    SlockHomeServerSwitcher(payload) { action, itemId ->
        bridge.homeEvent(payload.instanceId, action, itemId)
    }
}

@Composable
private fun HomeSectionHeaderPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = rememberHomePayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.optString("instanceId"),
        KuiklyHomeSectionHeaderPayload.serializer()
    ) { KuiklyHomeSectionHeaderPayload(instanceId = it) }
    SlockHomeSectionHeader(payload) { action ->
        bridge.homeEvent(payload.instanceId, action)
    }
}

@Composable
private fun HomeActionRowPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = rememberHomePayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.optString("instanceId"),
        KuiklyHomeActionRowPayload.serializer()
    ) { KuiklyHomeActionRowPayload(instanceId = it) }
    SlockHomeActionRow(payload) {
        bridge.homeEvent(payload.instanceId, "click")
    }
}

@Composable
private fun HomeConversationRowPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = rememberHomePayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.optString("instanceId"),
        KuiklyHomeConversationRowPayload.serializer()
    ) { KuiklyHomeConversationRowPayload(instanceId = it) }
    SlockHomeConversationRow(payload) {
        bridge.homeEvent(payload.instanceId, "click", payload.id)
    }
}

@Composable
private fun HomeShellPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = rememberHomePayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.optString("instanceId"),
        KuiklyHomeShellPayload.serializer()
    ) { KuiklyHomeShellPayload(instanceId = it) }
    SlockHomeShell(payload) { action, itemId ->
        bridge.homeEvent(payload.instanceId, action, itemId)
    }
}

@Composable
internal fun HomeComponentsSmokeContent() {
    SlockHomeShell(homeSmokePayload, onAction = { _, _ -> })
}

private val homeSmokePayload =
    KuiklyHomeShellPayload(
        serverName = "markdown专修",
        unreadCount = 3,
        inboxUnreadCount = 4,
        savedCount = 2,
        channels =
            listOf(
                KuiklyHomeConversationRowPayload(id = "channel-1", name = "对话流专修", selected = true, badge = "2"),
                KuiklyHomeConversationRowPayload(id = "channel-2", name = "首页专修"),
                KuiklyHomeConversationRowPayload(id = "channel-3", name = "markdown专修", hasDraft = true)
            ),
        directMessages =
            listOf(
                KuiklyHomeConversationRowPayload(id = "dm-1", name = "Android-App-Integrator", avatarLabel = "AI", presence = "online"),
                KuiklyHomeConversationRowPayload(id = "dm-2", name = "KMP-专家", avatarLabel = "KM", presence = "offline")
            )
    )

@Composable
internal fun SlockHomeShell(
    payload: KuiklyHomeShellPayload,
    onAction: (String, String?) -> Unit
) {
    val topSafeArea = homeTopSafeAreaPadding()
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        if (topSafeArea > 0f) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topSafeArea.dp)
                    .background(SlockColor.BrandYellow)
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            SlockHomeHeader(
                KuiklyHomeHeaderPayload(
                    serverName = payload.serverName,
                    unreadCount = payload.unreadCount
                ),
                onClick = { onAction("openServerSwitcher", null) }
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth().height(38.dp)) {
                    SlockHomeActionRow(KuiklyHomeActionRowPayload(iconName = "search", label = payload.searchLabel), onClick = { onAction("search", null) })
                }
            }
            item {
                Box(modifier = Modifier.fillMaxWidth().height(38.dp)) {
                    SlockHomeActionRow(KuiklyHomeActionRowPayload(iconName = "inbox", label = payload.inboxLabel, badge = payload.inboxUnreadCount.takeIf { it > 0 }?.toString()), onClick = { onAction("inbox", null) })
                }
            }
            item {
                Box(modifier = Modifier.fillMaxWidth().height(38.dp)) {
                    SlockHomeActionRow(KuiklyHomeActionRowPayload(iconName = "bookmark", label = payload.savedLabel, trailingText = payload.savedCount.takeIf { it > 0 }?.toString()), onClick = { onAction("saved", null) })
                }
            }
            item {
                Box(modifier = Modifier.fillMaxWidth().height(30.dp)) {
                    SlockHomeSectionHeader(
                        KuiklyHomeSectionHeaderPayload(
                            label = payload.channelsLabel,
                            count = payload.channels.size,
                            expanded = payload.channelsExpanded,
                            showAdd = true
                        ),
                        onAction = { onAction("channels.$it", null) }
                    )
                }
            }
            if (payload.channelsExpanded) {
                items(payload.channels.size) { index ->
                    Box(modifier = Modifier.fillMaxWidth().height(32.dp)) {
                        val item = payload.channels[index]
                        SlockHomeConversationRow(item) { onAction("openChannel", item.id) }
                    }
                }
            }
            item {
                Box(modifier = Modifier.fillMaxWidth().height(30.dp)) {
                    SlockHomeSectionHeader(
                        KuiklyHomeSectionHeaderPayload(
                            label = payload.directMessagesLabel,
                            count = payload.directMessages.size,
                            expanded = payload.directMessagesExpanded
                        ),
                        onAction = { onAction("directMessages.$it", null) }
                    )
                }
            }
            if (payload.directMessagesExpanded) {
                items(payload.directMessages.size) { index ->
                    Box(modifier = Modifier.fillMaxWidth().height(32.dp)) {
                        val item = payload.directMessages[index]
                        SlockHomeConversationRow(item) { onAction("openDirectMessage", item.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun homeTopSafeAreaPadding(): Float {
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    return pageData.safeAreaInsets.top
        .coerceAtLeast(pageData.statusBarHeight)
        .coerceAtLeast(0f)
}

@Composable
internal fun SlockHomeActionRow(payload: KuiklyHomeActionRowPayload, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxSize().clickable { onClick() }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            SlockLucideIcon(name = payload.iconName, color = "#FF000000", modifier = Modifier.size(14.dp))
        }
        Text(
            text = payload.label,
            style = SlockType.Button.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!payload.badge.isNullOrBlank()) {
            CountBadge(payload.badge)
        } else if (!payload.trailingText.isNullOrBlank()) {
            Text(
                text = payload.trailingText,
                style = SlockType.Caption.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun SlockHomeConversationRow(payload: KuiklyHomeConversationRowPayload, onClick: () -> Unit) {
    val background = when {
        payload.selected -> SlockColor.BrandYellow
        !payload.joined -> SlockColor.EmptyStateGray
        else -> Color.Transparent
    }
    val border = if (payload.selected) SlockColor.BorderBlack else Color.Transparent
    Row(
        modifier = Modifier.fillMaxSize().clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .height(32.dp)
                .background(background)
                .border(SlockStroke.Default, border)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                if (!payload.avatarLabel.isNullOrBlank()) {
                    HomeAvatar(payload.avatarLabel, payload.presence)
                } else {
                    Text(
                        text = payload.symbol,
                        style = SlockType.Body.copy(color = SlockColor.TextBlack, fontSize = 16.sp),
                        color = SlockColor.TextBlack,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = payload.name,
                style = SlockType.Button.copy(color = if (payload.joined) SlockColor.TextBlack else SlockColor.TextMutedGray),
                color = if (payload.joined) SlockColor.TextBlack else SlockColor.TextMutedGray,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (payload.hasDraft) {
                SlockLucideIcon(name = "pencil", color = "#FF737373", modifier = Modifier.size(14.dp))
            }
            if (!payload.badge.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                CountBadge(payload.badge)
            }
        }
    }
}

@Composable
private fun HomeAvatar(label: String, presence: String?) {
    Box(modifier = Modifier.size(20.dp)) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(SlockColor.BrandYellow)
                .border(1.dp, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.take(2).uppercase(),
                style = SlockType.Caption.copy(color = SlockColor.TextBlack, fontSize = 11.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
        if (!presence.isNullOrBlank()) {
            Box(
                modifier = Modifier.size(8.dp)
                    .background(homePresenceColor(presence))
                    .border(1.dp, SlockColor.BorderBlack)
                    .align(Alignment.BottomEnd)
            )
        }
    }
}

private fun homePresenceColor(presence: String): Color =
    when (presence.lowercase()) {
        "online" -> Color(0xFFB9F27D)
        "offline" -> SlockColor.EmptyStateGray
        else -> SlockColor.BrandYellow
    }

@Composable
internal fun SlockHomeHeader(payload: KuiklyHomeHeaderPayload, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxSize()
            .background(SlockColor.BrandYellow)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Row(
                modifier = Modifier.rotate(-1.8f).background(SlockColor.TextBlack).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = payload.serverName.uppercase(),
                    style = SlockType.Button.copy(color = SlockColor.BrandYellow, fontSize = 15.sp),
                    color = SlockColor.BrandYellow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                SlockLucideIcon(name = "chevron-down", color = "#FFFFD440", modifier = Modifier.size(14.dp))
            }
            if (payload.unreadCount > 0) {
                Box(
                    modifier = Modifier.size(9.dp)
                        .background(SlockColor.AccentRed)
                        .border(1.dp, SlockColor.BorderBlack)
                        .align(Alignment.TopEnd)
                )
            }
        }
    }
}

@Composable
internal fun SlockHomeServerSwitcher(
    payload: KuiklyHomeServerSwitcherPayload,
    onAction: (String, String?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
        if (payload.servers.isEmpty()) {
            Text(
                text = payload.noServersLabel,
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(payload.servers.size) { index ->
                    SlockHomeServerRow(
                        server = payload.servers[index],
                        selected = payload.servers[index].id == payload.currentServerId,
                        onClick = { onAction("selectServer", payload.servers[index].id) }
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp).clickable { onAction("createServer", null) }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlockLucideIcon(name = "plus", color = "#FF000000", modifier = Modifier.size(16.dp))
            Text(
                text = payload.createServerLabel,
                style = SlockType.Button.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SlockHomeServerRow(server: KuiklyHomeServerItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (selected) "✓" else "",
            style = SlockType.Button.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.width(22.dp),
            textAlign = TextAlign.Center
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(server.name, style = SlockType.Button.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("/${server.slug}", style = SlockType.Caption.copy(color = SlockColor.TextMutedGray, fontSize = 11.sp), color = SlockColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (server.unreadCount > 0) {
            CountBadge(server.unreadCount.toString())
        }
    }
}

@Composable
internal fun SlockHomeSectionHeader(
    payload: KuiklyHomeSectionHeaderPayload,
    onAction: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize().clickable { onAction("toggle") },
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(
            name = if (payload.expanded) "chevron-down" else "chevron-right",
            color = "#FF000000",
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = payload.label,
            style = SlockType.SectionLabel.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(start = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = payload.count.toString(),
            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.padding(start = 4.dp),
            maxLines = 1
        )
        Spacer(modifier = Modifier.weight(1f))
        HeaderActionIcon("arrow-up-down") { onAction("sort") }
        if (payload.showAdd) {
            HeaderActionIcon("plus") { onAction("add") }
        }
    }
}

@Composable
private fun HeaderActionIcon(name: String, onClick: () -> Unit) {
    Box(modifier = Modifier.size(30.dp).clickable { onClick() }, contentAlignment = Alignment.Center) {
        SlockLucideIcon(name = name, color = "#FF000000", modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun CountBadge(text: String) {
    Box(
        modifier = Modifier.background(SlockColor.AccentPink).border(SlockStroke.Default, SlockColor.BorderBlack).padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = SlockType.Caption.copy(color = SlockColor.TextBlack, fontSize = 11.sp), color = SlockColor.TextBlack, maxLines = 1)
    }
}

@Composable
private fun <T> rememberHomePayload(
    payload: String,
    instanceId: String,
    serializer: kotlinx.serialization.KSerializer<T>,
    fallback: (String) -> T
): T = remember(payload, instanceId) {
    runCatching {
        Json { ignoreUnknownKeys = true }
            .decodeFromString(serializer, payload)
            .let { value ->
                when (value) {
                    is KuiklyHomeHeaderPayload -> if (value.instanceId.isBlank()) value.copy(instanceId = instanceId) as T else value
                    is KuiklyHomeServerSwitcherPayload -> if (value.instanceId.isBlank()) value.copy(instanceId = instanceId) as T else value
                    is KuiklyHomeSectionHeaderPayload -> if (value.instanceId.isBlank()) value.copy(instanceId = instanceId) as T else value
                    is KuiklyHomeActionRowPayload -> if (value.instanceId.isBlank()) value.copy(instanceId = instanceId) as T else value
                    is KuiklyHomeConversationRowPayload -> if (value.instanceId.isBlank()) value.copy(instanceId = instanceId) as T else value
                    is KuiklyHomeShellPayload -> if (value.instanceId.isBlank()) value.copy(instanceId = instanceId) as T else value
                    else -> value
                }
            }
    }.getOrElse { fallback(instanceId) }
}
