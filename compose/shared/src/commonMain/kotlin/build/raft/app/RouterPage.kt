package build.raft.app

import build.raft.app.base.BasePager
import build.raft.app.base.Utils
import build.raft.app.base.bridgeModule
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalInputField
import build.raft.app.components.SlockBrutalListRow
import build.raft.app.components.SlockBrutalPanelShell
import build.raft.app.data.KeyValueRouterPreferenceRepository
import build.raft.app.data.slockSharedPreferenceStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.utils.urlParams
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ROUTER_PAGE = "router"
private const val ROUTE_PAYLOAD_PREVIEW = "with smoke payload"
private val routerPreviewJson = Json { encodeDefaults = true }

@Page(ROUTER_PAGE, supportInLocal = true)
internal class ComposeRoutePager : BasePager() {

    override fun willInit() {
        super.willInit()
        setContent {
            ComposeRouteImpl()
        }
    }

    companion object {
        const val PLACEHOLDER = "compose_thread&channelId=... 或 pageName=compose_thread&channelId=..."
        const val JUMP_TEXT = "Open"
        const val TITLE = "Developer Router"
        const val AAR_MODE_TIP = "输入 pageName 后可追加 &key=value 参数；下方列表枚举当前 shared @Page 页面。"
    }
}

private data class DeveloperRouteItem(
    val title: String,
    val pageName: String,
    val detail: String,
    val payloadProvider: (() -> String)? = null
) {
    val subtitle: String
        get() = if (payloadProvider == null) {
            pageName
        } else {
            "$pageName · $ROUTE_PAYLOAD_PREVIEW"
        }
}

private data class DeveloperRouteGroup(
    val title: String,
    val routes: List<DeveloperRouteItem>
)

private val DeveloperRouteGroups =
    listOf(
        DeveloperRouteGroup(
            title = "Core",
            routes =
                listOf(
                    DeveloperRouteItem("Router", ROUTER_PAGE, "Developer route launcher"),
                    DeveloperRouteItem("App Shell", SLOCK_APP_SHELL_PAGE, "Raft shell with real navigation chrome"),
                    DeveloperRouteItem("Home", SLOCK_HOME_PAGE, "Main workspace/channel shell"),
                    DeveloperRouteItem("Thread", THREAD_PAGE, "Conversation route"),
                    DeveloperRouteItem("Members", MEMBERS_PAGE, "Server members route"),
                    DeveloperRouteItem("File Preview", SLOCK_FILE_PREVIEW_PAGE, "Standalone workspace/thread file preview")
                )
        ),
        DeveloperRouteGroup(
            title = "Auth",
            routes =
                listOf(
                    DeveloperRouteItem("Login", LOGIN_PAGE, "Sign-in page"),
                    DeveloperRouteItem("Register", REGISTER_PAGE, "Registration page"),
                    DeveloperRouteItem("Forgot Password", FORGOT_PASSWORD_PAGE, "Password recovery page"),
                    DeveloperRouteItem("About", AUTH_ABOUT_PAGE, "Shared About, environment, and diagnostics page"),
                    DeveloperRouteItem("Choose Server", AUTH_CHOOSE_SERVER_PAGE, "Server picker page")
                )
        ),
        DeveloperRouteGroup(
            title = "Settings",
            routes =
                listOf(
                    DeveloperRouteItem("Settings Home", SETTINGS_HOME_PAGE, "Settings root page"),
                    DeveloperRouteItem(
                        title = "Settings Account Preview",
                        pageName = SETTINGS_ACCOUNT_PAGE,
                        detail = "Account settings fixture",
                        payloadProvider = { routerPreviewJson.encodeToString(settingsAccountSmokePayload) }
                    ),
                    DeveloperRouteItem("Settings Storage", SETTINGS_STORAGE_PAGE, "Storage settings page"),
                    DeveloperRouteItem(
                        title = "Settings Billing Preview",
                        pageName = SETTINGS_BILLING_PAGE,
                        detail = "Billing settings fixture",
                        payloadProvider = { routerPreviewJson.encodeToString(settingsBillingSmokePayload) }
                    ),
                    DeveloperRouteItem("Settings Administration", SETTINGS_ADMINISTRATION_PAGE, "Administration webview page")
                )
        ),
        DeveloperRouteGroup(
            title = "Component Visuals",
            routes =
                listOf(
                    DeveloperRouteItem("Lucide Icon Gallery", SLOCK_LUCIDE_ICON_GALLERY_PAGE, "Icon asset parity page"),
                    DeveloperRouteItem("Typography Probe", SLOCK_TYPOGRAPHY_PROBE_PAGE, "React/KMP text-weight proof page"),
                    DeveloperRouteItem("Settings TabBar Smoke", SETTINGS_TAB_BAR_SMOKE_PAGE, "Settings tab fixture"),
                    DeveloperRouteItem("Bottom TabBar Smoke", BOTTOM_TAB_BAR_SMOKE_PAGE, "Bottom tab fixture"),
                    DeveloperRouteItem("Members Smoke", MEMBERS_SMOKE_PAGE, "Members smoke fixture"),
                    DeveloperRouteItem("Child Page Host Smoke", SLOCK_CHILD_PAGE_HOST_SMOKE_PAGE, "Nested Kuikly child-page host proof")
                )
        )
    )

@Composable
internal fun ComposeRouteImpl() {
    val localPager = LocalActivity.current.getPager() as Pager
    val routePreferenceRepository = remember(localPager) {
        KeyValueRouterPreferenceRepository(slockSharedPreferenceStore())
    }
    var textFieldValue by remember(routePreferenceRepository) {
        mutableStateOf(routePreferenceRepository.readLastRouteInput().orEmpty())
    }
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    val routeCount = remember { DeveloperRouteGroups.fold(0) { count, group -> count + group.routes.size } }
    val allRoutes = remember { DeveloperRouteGroups.flatMap { it.routes } }
    val suggestions = remember(textFieldValue, allRoutes) {
        developerRouteSuggestions(textFieldValue, allRoutes)
    }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceCream)
                .padding(start = 16.dp, top = (statusBarHeight + 14).dp, end = 16.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            DeveloperRouterHeader(routeCount)
        }
        item {
            DeveloperRouterJumpCard(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                suggestions = suggestions,
                onSuggestionSelected = { route ->
                    textFieldValue = textFieldValue.withDeveloperRoutePageName(route.pageName)
                },
                onOpen = {
                    val normalized = textFieldValue.trim()
                    if (normalized.isEmpty()) {
                        Utils.bridgeModule(localPager.pagerId).toast("请输入 pageName")
                    } else {
                        routePreferenceRepository.saveLastRouteInput(normalized)
                        jumpPage(localPager, normalized)
                    }
                }
            )
        }
        DeveloperRouteGroups.forEach { group ->
            item {
                DeveloperRouteGroupHeader(group.title, group.routes.size)
            }
            items(group.routes.size, key = { "${group.title}-${group.routes[it].pageName}-${group.routes[it].title}" }) { index ->
                val route = group.routes[index]
                SlockBrutalListRow(
                    title = route.title,
                    subtitle = route.subtitle,
                    onClick = {
                        routePreferenceRepository.saveLastRouteInput(route.pageName)
                        textFieldValue = route.pageName
                        jumpPage(localPager, route.pageName, route.payloadProvider?.invoke())
                    }
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

@Composable
private fun DeveloperRouterHeader(routeCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = ComposeRoutePager.TITLE,
            style = SlockType.PageTitle.copy(fontSize = 23.sp, lineHeight = 28.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "$routeCount routes · Raft shared/KMP page launcher",
            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DeveloperRouterJumpCard(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<DeveloperRouteItem>,
    onSuggestionSelected: (DeveloperRouteItem) -> Unit,
    onOpen: () -> Unit
) {
    SlockBrutalPanelShell(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = ComposeRoutePager.AAR_MODE_TIP,
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockBrutalInputField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = ComposeRoutePager.PLACEHOLDER,
                    fieldMinHeight = 42.dp,
                    showClearWhenNotEmpty = true,
                    modifier = Modifier.weight(1f)
                )
                SlockBrutalActionButton(
                    label = ComposeRoutePager.JUMP_TEXT,
                    onClick = onOpen,
                    backgroundColor = SlockColor.BrandYellow,
                    minWidth = 72.dp,
                    minHeight = 42.dp
                )
            }
            if (suggestions.isNotEmpty()) {
                DeveloperRouterSuggestionList(
                    suggestions = suggestions,
                    onSuggestionSelected = onSuggestionSelected
                )
            }
        }
    }
}

@Composable
private fun DeveloperRouterSuggestionList(
    suggestions: List<DeveloperRouteItem>,
    onSuggestionSelected: (DeveloperRouteItem) -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(SlockStroke.Thin, SlockColor.BorderBlack),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        suggestions.forEachIndexed { index, route ->
            val isLast = index == suggestions.lastIndex
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceWhite)
                        .clickable(onClick = { onSuggestionSelected(route) })
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = route.pageName,
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = route.title,
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!isLast) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(SlockStroke.Thin)
                            .background(SlockColor.BorderBlack)
                )
            }
        }
    }
}

@Composable
private fun DeveloperRouteGroupHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = SlockType.SectionLabel.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "$count",
            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1
        )
    }
}

private fun developerRouteSuggestions(input: String, routes: List<DeveloperRouteItem>): List<DeveloperRouteItem> {
    val query = input.developerRouteQueryToken().lowercase()
    if (query.isBlank()) {
        return emptyList()
    }
    return routes
        .filter { route ->
            val pageName = route.pageName.lowercase()
            val title = route.title.lowercase()
            pageName.contains(query) || title.contains(query)
        }
        .sortedWith(
            compareBy<DeveloperRouteItem> { !it.pageName.lowercase().startsWith(query) }
                .thenBy { !it.title.lowercase().startsWith(query) }
                .thenBy { it.pageName }
        )
        .take(5)
}

private fun String.developerRouteQueryToken(): String {
    val normalized = trim().removePrefix("?")
    val firstToken = normalized.substringBefore("&")
    return firstToken.removePrefix("pageName=").trim()
}

private fun String.withDeveloperRoutePageName(pageName: String): String {
    val normalized = trim().removePrefix("?")
    if (normalized.isBlank()) {
        return pageName
    }
    val suffix = normalized.substringAfter("&", missingDelimiterValue = "")
    val paramsSuffix = if (suffix.isBlank()) "" else "&$suffix"
    return if (normalized.startsWith("pageName=")) {
        "pageName=$pageName$paramsSuffix"
    } else {
        "$pageName$paramsSuffix"
    }
}

fun jumpPage(pager: Pager, inputText: String, payload: String? = null) {
    val normalized = inputText.trim().removePrefix("?")
    if (normalized.isBlank()) {
        return
    }
    val query = if (normalized.startsWith("pageName=")) normalized else "pageName=$normalized"
    val params = urlParams(query)
    val pageData = JSONObject()
    params.forEach {
        pageData.put(it.key, it.value)
    }
    payload?.takeIf { it.isNotBlank() }?.let { pageData.put("payload", it) }
    val pageName = pageData.optString("pageName")
    pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(pageName, pageData)
}
