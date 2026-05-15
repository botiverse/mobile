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
private fun HomeComponentsSmokeContent() {
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockHomeHeader(KuiklyHomeHeaderPayload(serverName = "markdown专修", unreadCount = 3), onClick = {})
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SlockHomeSectionHeader(KuiklyHomeSectionHeaderPayload(label = "CHANNELS", count = 12, expanded = true, showAdd = true), onAction = {})
            SlockHomeSectionHeader(KuiklyHomeSectionHeaderPayload(label = "DIRECT MESSAGES", count = 4, expanded = false), onAction = {})
        }
        SlockHomeServerSwitcher(
            payload = KuiklyHomeServerSwitcherPayload(
                currentServerId = "home",
                servers = listOf(
                    KuiklyHomeServerItem("home", "首页专修", "home", 2),
                    KuiklyHomeServerItem("markdown", "markdown专修", "markdown")
                )
            ),
            onAction = { _, _ -> }
        )
    }
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
                modifier = Modifier.background(SlockColor.TextBlack).padding(horizontal = 12.dp, vertical = 7.dp),
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
                    else -> value
                }
            }
    }.getOrElse { fallback(instanceId) }
}
