package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.data.BridgeAuthApi
import ai.slock.compose.data.BridgeHomeApi
import ai.slock.compose.data.BridgeMembersApi
import ai.slock.compose.data.DataRequestPriority
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.HomeSnapshot
import ai.slock.compose.data.HomeServerSwitchState
import ai.slock.compose.data.KuiklyAuthSessionStore
import ai.slock.compose.data.KuiklyRelationalStore
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthErrorCode
import ai.slock.compose.data.SharedAuthResult
import ai.slock.compose.data.SharedHomeDataSource
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.parseSlockHome
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.navigation.NavHost
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.navigation.composable
import com.tencent.kuikly.compose.material3.navigation.rememberNavController
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.BackPressCallback
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json

const val SETTINGS_HOME_PAGE = "compose_settings_home"
const val SETTINGS_TAB_BAR_SMOKE_PAGE = "compose_settings_tabbar_smoke"
const val BOTTOM_TAB_BAR_SMOKE_PAGE = "compose_bottom_tabbar_smoke"
const val SLOCK_APP_SHELL_PAGE = "compose_slock_app_shell"

private const val AppShellRouteMain = "main"
private const val AppShellRouteThread = "thread"
private const val SettingsDestinationHome = "home"
private const val SettingsDestinationAccount = "account"
private const val SettingsDestinationComputers = "computers"
private const val SettingsDestinationServer = "server"
private const val SettingsDestinationBilling = "billing"
private const val SettingsDestinationMobile = "mobile"
private const val SettingsDestinationStorage = "storage"
private const val SettingsDestinationAbout = "about"
private const val SettingsDestinationRouter = "router"

@Page(SETTINGS_HOME_PAGE, supportInLocal = true)
internal class ComposeSettingsHomePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsHomeScreen() }
    }
}

@Page(SETTINGS_TAB_BAR_SMOKE_PAGE, supportInLocal = true)
internal class ComposeSettingsTabBarSmokePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            SettingsHomeContent(
                payload = settingsTabBarSmokePayload,
                onSelect = {}
            )
        }
    }
}

@Page(BOTTOM_TAB_BAR_SMOKE_PAGE, supportInLocal = true)
internal class ComposeBottomTabBarSmokePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { BottomTabBarSmokeContent() }
    }
}

@Page(SLOCK_APP_SHELL_PAGE, supportInLocal = true)
internal class ComposeSlockAppShellPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockAppShellContent() }
    }
}

@Serializable
internal data class KuiklySettingsHomePayload(
    val instanceId: String = "",
    val title: String = "Settings",
    val subtitle: String = "",
    val showSelection: Boolean = true,
    val items: List<SlockTabItemPayload> = emptyList()
)

@Serializable
internal data class KuiklySharedSettingsPayload(
    val instanceId: String = "",
    val title: String = "",
    val detail: String = "",
    val heroIconName: String = "settings",
    val heroLabel: String = "",
    val sections: List<KuiklySharedSettingsSection> = emptyList()
)

@Serializable
internal data class KuiklySharedSettingsSection(
    val title: String = "",
    val rows: List<KuiklySharedSettingsRow> = emptyList()
)

@Serializable
internal data class KuiklySharedSettingsRow(
    val key: String = "",
    val iconName: String = "settings",
    val title: String = "",
    val detail: String = "",
    val value: String = "",
    val status: String = "",
    val enabled: Boolean = false
)

private val settingsTabBarSmokePayload = KuiklySettingsHomePayload(
    title = "Settings",
    subtitle = "Harmony smoke",
    showSelection = true,
    items = listOf(
        SlockTabItemPayload(
            key = "account",
            title = "Account",
            subtitle = "Profile, email, password, connected accounts",
            iconName = "user",
            value = "Verified",
            selected = true
        ),
        SlockTabItemPayload(
            key = "computers",
            title = "Computers",
            subtitle = "Connected machines and active sessions",
            iconName = "monitor"
        ),
        SlockTabItemPayload(
            key = "server",
            title = "Server",
            subtitle = "Workspace name, members, invites, permissions",
            iconName = "users",
            value = "Owner"
        ),
        SlockTabItemPayload(
            key = "billing",
            title = "Billing",
            subtitle = "Plan, usage, invoices",
            iconName = "shield-check"
        ),
        SlockTabItemPayload(
            key = "mobile",
            title = "Mobile",
            subtitle = "Push notifications, language, layout",
            iconName = "smartphone"
        ),
        SlockTabItemPayload(
            key = "storage",
            title = "Storage",
            subtitle = "Local cache and diagnostics",
            iconName = "archive"
        ),
        SlockTabItemPayload(
            key = "about",
            title = "About",
            subtitle = "Version, diagnostics, feedback",
            iconName = "bug"
        ),
        SlockTabItemPayload(
            key = "router",
            title = "Smoke previews",
            subtitle = "Open Kuikly router and shared test pages",
            iconName = "route"
        )
    )
)

private val bottomTabBarSmokeItems = listOf(
    SlockBottomTabItemPayload("home", "HOME", "house"),
    SlockBottomTabItemPayload("tasks", "TASKS", "square-check-big", selected = true),
    SlockBottomTabItemPayload("members", "MEMBERS", "users"),
    SlockBottomTabItemPayload("settings", "SETTINGS", "settings")
)

private fun appShellBottomTabs(selectedKey: String): List<SlockBottomTabItemPayload> =
    listOf(
        SlockBottomTabItemPayload("home", "HOME", "house", selected = selectedKey == "home"),
        SlockBottomTabItemPayload("tasks", "TASKS", "square-check-big", selected = selectedKey == "tasks"),
        SlockBottomTabItemPayload("members", "MEMBERS", "users", selected = selectedKey == "members"),
        SlockBottomTabItemPayload("settings", "SETTINGS", "settings", selected = selectedKey == "settings")
    )

@Composable
private fun SlockAppShellContent() {
    var selectedTab by remember { mutableStateOf("home") }
    var selectedSettingsDestination by remember { mutableStateOf(SettingsDestinationHome) }
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val authStore = remember { KuiklyAuthSessionStore(KuiklyRelationalStore(bridge)) }
    val authDataSource =
        remember {
            SharedAuthDataSource(
                api = BridgeAuthApi(bridge),
                sessionStore = authStore
            )
        }
    val homeDataSource =
        remember {
            SharedHomeDataSource(
                sessionStore = authStore,
                api = BridgeHomeApi(bridge, authStore),
                parseHome = { payload, session ->
                    parseSlockHome(payload, session) { message -> bridge.log(message) }
                }
            )
        }
    val membersApi = remember { BridgeMembersApi(bridge, authStore) }
    var authGateState by remember { mutableStateOf<AppShellAuthGateState>(AppShellAuthGateState.Restoring) }
    var authGateNonce by remember { mutableStateOf(0) }
    var activeThreadPayload by remember { mutableStateOf<KuiklyThreadScreenPayload?>(null) }
    var homeRefreshNonce by remember { mutableStateOf(0) }
    var homeServerSwitchState by remember { mutableStateOf(HomeServerSwitchState()) }
    var homeServerSwitcherExpanded by remember { mutableStateOf(false) }
    var homeState by remember { mutableStateOf<SharedHomeUiState>(SharedHomeUiState.Loading()) }
    val navController = rememberNavController()
    val bottomSafeArea = slockBottomSafeAreaPadding()
    val threadTimeContext = remember(pager.pageData.params.toString()) {
        pager.pageData.params.threadTimeContext()
    }

    LaunchedEffect(authGateNonce) {
        authGateState = AppShellAuthGateState.Restoring
        authGateState =
            when (val result = runCatching { authDataSource.restoreSession() }.getOrElse { SharedAuthResult.Failure(SharedAuthErrorCode.Network, "Network unavailable. Check your connection.") }) {
                is SharedAuthResult.Success ->
                    if (result.data.selectedServerId.isNullOrBlank()) {
                        AppShellAuthGateState.ServerRequired(result.data)
                    } else {
                        AppShellAuthGateState.Ready(result.data)
                    }
                SharedAuthResult.Unauthenticated -> AppShellAuthGateState.AuthRequired
                is SharedAuthResult.Failure -> AppShellAuthGateState.Error(result.message)
            }
    }

    when (val gate = authGateState) {
        AppShellAuthGateState.Restoring -> {
            AppShellPlaceholderContent(
                title = "Slock",
                subtitle = "Restoring session...",
                iconName = "sparkles"
            )
            return
        }
        AppShellAuthGateState.AuthRequired -> {
            AppShellAuthGateContent(
                authDataSource = authDataSource,
                onSignedIn = { session ->
                    authGateState =
                        if (session.selectedServerId.isNullOrBlank()) {
                            AppShellAuthGateState.ServerRequired(session)
                        } else {
                            AppShellAuthGateState.Ready(session)
                        }
                }
            )
            return
        }
        is AppShellAuthGateState.ServerRequired -> {
            AppShellServerGateContent(
                session = gate.session,
                authDataSource = authDataSource,
                onReady = { session ->
                    authGateState = AppShellAuthGateState.Ready(session)
                    selectedTab = "home"
                    activeThreadPayload = null
                },
                onSignOut = {
                    authGateState = AppShellAuthGateState.AuthRequired
                    selectedTab = "home"
                    activeThreadPayload = null
                }
            )
            return
        }
        is AppShellAuthGateState.Error -> {
            AppShellAuthErrorContent(
                message = gate.message,
                onRetry = { authGateNonce += 1 },
                onSignIn = { authGateState = AppShellAuthGateState.AuthRequired }
            )
            return
        }
        is AppShellAuthGateState.Ready -> Unit
    }

    fun popThreadRoute(): Boolean {
        if (navController.currentRoute != AppShellRouteThread && !navController.canNavigateBack) {
            return false
        }
        val popped = navController.popBackStack()
        if (popped) {
            activeThreadPayload = null
        }
        return popped
    }

    fun popMainRoute(): Boolean {
        if (homeServerSwitcherExpanded) {
            homeServerSwitcherExpanded = false
            return true
        }
        if (selectedTab == "settings" && selectedSettingsDestination != SettingsDestinationHome) {
            selectedSettingsDestination = SettingsDestinationHome
            return true
        }
        if (selectedTab != "home") {
            selectedTab = "home"
            selectedSettingsDestination = SettingsDestinationHome
            return true
        }
        return false
    }

    DisposableEffect(pager, navController) {
        val callback =
            object : BackPressCallback() {
                override fun handleOnBackPressed() {
                    if (!popThreadRoute() && !popMainRoute()) {
                        bridge.closePage()
                    }
                }
            }
        pager.getBackPressHandler().addCallback(callback)
        onDispose { pager.getBackPressHandler().removeCallback(callback) }
    }

    NavHost(
        navController = navController,
        startDestination = AppShellRouteMain,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(AppShellRouteThread) {
            val payload = activeThreadPayload
            if (payload == null) {
                AppShellPlaceholderContent(
                    title = "Thread",
                    subtitle = "No conversation selected.",
                    iconName = "message-square"
                )
            } else {
                SharedThreadRouteContent(
                    initialPayload = payload,
                    bridge = bridge,
                    onBack = { popThreadRoute() }
                )
            }
        }
        composable(AppShellRouteMain) {
            Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        "settings" -> {
                            SettingsAppShellContent(
                                session = (authGateState as? AppShellAuthGateState.Ready)?.session,
                                authDataSource = authDataSource,
                                membersApi = membersApi,
                                selectedDestination = selectedSettingsDestination,
                                onDestinationChange = { selectedSettingsDestination = it },
                                onOpenRouter = { jumpPage(pager, "router") },
                                onSettingsAction = { action, itemId ->
                                    bridge.settingsEvent("shared-shell-settings", action, itemId)
                                },
                                onLogout = {
                                    authGateState = AppShellAuthGateState.AuthRequired
                                    selectedTab = "home"
                                    selectedSettingsDestination = SettingsDestinationHome
                                    activeThreadPayload = null
                                }
                            )
                        }
                        "tasks" -> AppShellPlaceholderContent(
                            title = "Tasks",
                            subtitle = "Shared task surface will mount here",
                            iconName = "square-check-big"
                        )
                        "members" -> SharedMembersScreen(bridge)
                        else ->
                            SharedHomeScreen(
                                bridge = bridge,
                                authStore = authStore,
                                dataSource = homeDataSource,
                                refreshNonce = homeRefreshNonce,
                                onRefreshNonceChange = { homeRefreshNonce = it },
                                serverSwitchState = homeServerSwitchState,
                                onServerSwitchStateChange = { homeServerSwitchState = it },
                                serverSwitcherExpanded = homeServerSwitcherExpanded,
                                onServerSwitcherExpandedChange = { homeServerSwitcherExpanded = it },
                                state = homeState,
                                onStateChange = { homeState = it },
                                onOpenConversation = { conversation ->
                                    activeThreadPayload = buildAppShellThreadPayload(conversation, threadTimeContext)
                                    navController.navigate(AppShellRouteThread)
                                }
                            )
                    }
                }
                SlockAppBottomTabBarHost(
                    items = appShellBottomTabs(selectedTab),
                    onSelect = {
                        selectedTab = it
                        if (it != "settings") {
                            selectedSettingsDestination = SettingsDestinationHome
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    bottomSafeArea = bottomSafeArea
                )
            }
        }
    }
}

private sealed interface AppShellAuthGateState {
    data object Restoring : AppShellAuthGateState
    data object AuthRequired : AppShellAuthGateState
    data class ServerRequired(val session: StoredAuthSession) : AppShellAuthGateState
    data class Ready(val session: StoredAuthSession) : AppShellAuthGateState
    data class Error(val message: String) : AppShellAuthGateState
}

@Composable
private fun SettingsAppShellContent(
    session: StoredAuthSession?,
    authDataSource: SharedAuthDataSource,
    membersApi: BridgeMembersApi,
    selectedDestination: String,
    onDestinationChange: (String) -> Unit,
    onOpenRouter: () -> Unit,
    onSettingsAction: (String, String?) -> Unit,
    onLogout: () -> Unit
) {
    var pendingLogout by remember { mutableStateOf(false) }

    LaunchedEffect(pendingLogout) {
        if (!pendingLogout) return@LaunchedEffect
        pendingLogout = false
        runCatching { authDataSource.logout() }
        onLogout()
    }

    if (selectedDestination == SettingsDestinationHome) {
        SettingsHomeContent(
            payload = appShellSettingsHomePayload(session, selectedDestination),
            onSelect = { key ->
                if (key == SettingsDestinationRouter) {
                    onOpenRouter()
                } else {
                    onDestinationChange(key)
                }
            }
        )
        return
    }

    SettingsDetailShell(
        title = settingsDestinationTitle(selectedDestination),
        subtitle = settingsDestinationSubtitle(selectedDestination),
        iconName = settingsDestinationIcon(selectedDestination),
        onBack = { onDestinationChange(SettingsDestinationHome) }
    ) {
        when (selectedDestination) {
            SettingsDestinationAccount ->
                SettingsAccountContent(appShellAccountPayload(session)) { action ->
                    if (action == "logout") {
                        pendingLogout = true
                    }
                }
            SettingsDestinationComputers ->
                SettingsComputersContent(appShellComputersPayload()) { _, _ -> }
            SettingsDestinationStorage ->
                SettingsStorageContent(appShellStoragePayload()) { _, _ -> }
            SettingsDestinationBilling ->
                SettingsBillingContent(appShellBillingPayload(session)) { _, _ -> }
            SettingsDestinationServer ->
                SharedServerSettingsContent(session, membersApi, onSettingsAction)
            SettingsDestinationMobile ->
                SharedSettingsSectionContent(appShellMobilePayload(), onSettingsAction)
            SettingsDestinationAbout ->
                SharedSettingsSectionContent(appShellAboutPayload(session), onSettingsAction)
            else ->
                SettingsSharedPlaceholder(
                    title = "Settings",
                    detail = "This Settings section is not available in shared shell yet.",
                    iconName = "settings"
                )
        }
    }
}

@Composable
private fun SharedServerSettingsContent(
    session: StoredAuthSession?,
    membersApi: BridgeMembersApi,
    onAction: (String, String?) -> Unit
) {
    var state by remember(session?.currentServerId) {
        mutableStateOf<SharedServerSettingsState>(SharedServerSettingsState.Loading)
    }

    LaunchedEffect(session?.currentServerId) {
        val server = session?.currentServerSummary()
        val serverId = session?.currentServerId
        if (server == null || serverId.isNullOrBlank()) {
            state = SharedServerSettingsState.Error("server_required")
            return@LaunchedEffect
        }
        state = SharedServerSettingsState.Loading
        try {
            val response = membersApi.serverMembers(serverId)
            state =
                if (response.isSuccessful) {
                    SharedServerSettingsState.Content(
                        members = response.bodyText?.let(::parseSharedMembers).orEmpty(),
                        memberErrorCode = null
                    )
                } else {
                    SharedServerSettingsState.Content(
                        members = emptyList(),
                        memberErrorCode = response.errorBodyText?.takeIf { it.isNotBlank() } ?: "members_network"
                    )
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            state = SharedServerSettingsState.Content(emptyList(), "members_network")
        }
    }

    SharedSettingsSectionContent(
        payload = appShellServerPayload(session, state),
        onAction = onAction
    )
}

private sealed interface SharedServerSettingsState {
    data object Loading : SharedServerSettingsState
    data class Content(val members: List<MemberProfile>, val memberErrorCode: String?) : SharedServerSettingsState
    data class Error(val code: String) : SharedServerSettingsState
}

@Composable
private fun SharedSettingsSectionContent(
    payload: KuiklySharedSettingsPayload,
    onAction: (String, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SharedSettingsHero(payload) }
        payload.sections.forEach { section ->
            item { SharedSettingsSectionCard(section, onAction) }
        }
    }
}

@Composable
private fun SharedSettingsHero(payload: KuiklySharedSettingsPayload) {
    Row(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(14.dp),
        verticalAlignment = com.tencent.kuikly.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(52.dp).background(SlockColor.BrandYellow).border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = com.tencent.kuikly.compose.ui.Alignment.Center
        ) {
            SlockLucideIcon(payload.heroIconName, modifier = Modifier.size(26.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(payload.heroLabel.ifBlank { payload.title }, style = SlockType.PageTitle.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(payload.detail, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        }
    }
}

@Composable
private fun SharedSettingsSectionCard(
    section: KuiklySharedSettingsSection,
    onAction: (String, String?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            section.title.uppercase(),
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
        )
        section.rows.forEachIndexed { index, row ->
            SharedSettingsRow(row, showDivider = index > 0, onAction = onAction)
        }
    }
}

@Composable
private fun SharedSettingsRow(
    row: KuiklySharedSettingsRow,
    showDivider: Boolean,
    onAction: (String, String?) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SlockColor.BorderBlack))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .clickable(enabled = row.enabled) { onAction(row.key, row.value.takeIf { it.isNotBlank() }) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = com.tencent.kuikly.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier.size(34.dp).background(if (row.enabled) SlockColor.AccentCyan else SlockColor.EmptyStateGray).border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = com.tencent.kuikly.compose.ui.Alignment.Center
            ) {
                SlockLucideIcon(row.iconName, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.title, style = SlockType.Button.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.detail.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            row.status.takeIf { it.isNotBlank() }?.let {
                Text(it, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SettingsDetailShell(
    title: String,
    subtitle: String,
    iconName: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(12.dp),
            verticalAlignment = com.tencent.kuikly.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
            ) {
                SlockLucideIcon("arrow-left", modifier = Modifier.size(18.dp))
            }
            SlockLucideIcon(iconName, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = SlockType.PageTitle.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun SettingsSharedPlaceholder(
    title: String,
    detail: String,
    iconName: String
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(52.dp).background(SlockColor.BrandYellow).border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = com.tencent.kuikly.compose.ui.Alignment.Center
        ) {
            SlockLucideIcon(iconName, modifier = Modifier.size(26.dp))
        }
        Text(title, style = SlockType.PageTitle.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        Text(detail, style = SlockType.Body.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
    }
}

private fun appShellSettingsHomePayload(
    session: StoredAuthSession?,
    selectedDestination: String
): KuiklySettingsHomePayload =
    KuiklySettingsHomePayload(
        title = "Settings",
        subtitle = session?.currentServerSummary()?.name ?: "Shared settings",
        showSelection = true,
        items =
            listOf(
                SlockTabItemPayload(
                    key = SettingsDestinationAccount,
                    title = "Account",
                    subtitle = "Profile, email, password, connected accounts",
                    iconName = "user",
                    value = session?.user?.bestDisplayName,
                    selected = selectedDestination == SettingsDestinationAccount
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationComputers,
                    title = "Computers",
                    subtitle = "Connected machines and active sessions",
                    iconName = "monitor",
                    selected = selectedDestination == SettingsDestinationComputers
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationServer,
                    title = "Server",
                    subtitle = "Workspace profile, admins, invites, onboarding",
                    iconName = "users",
                    value = session?.currentServerSummary()?.role?.replaceFirstChar { it.uppercase() },
                    selected = selectedDestination == SettingsDestinationServer
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationBilling,
                    title = "Billing",
                    subtitle = "Plan and usage",
                    iconName = "shield-check",
                    value = session?.currentServerSummary()?.plan?.replaceFirstChar { it.uppercase() },
                    selected = selectedDestination == SettingsDestinationBilling
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationMobile,
                    title = "Mobile",
                    subtitle = "Push notifications, language, layout",
                    iconName = "smartphone",
                    selected = selectedDestination == SettingsDestinationMobile
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationStorage,
                    title = "Storage",
                    subtitle = "Local cache and diagnostics",
                    iconName = "archive",
                    selected = selectedDestination == SettingsDestinationStorage
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationAbout,
                    title = "About",
                    subtitle = "Version, diagnostics, feedback",
                    iconName = "bug",
                    selected = selectedDestination == SettingsDestinationAbout
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationRouter,
                    title = "Smoke previews",
                    subtitle = "Open Kuikly router and shared test pages",
                    iconName = "route",
                    selected = false
                )
            )
    )

private fun appShellAccountPayload(session: StoredAuthSession?): KuiklySettingsAccountPayload {
    val user = session?.user
    val displayName = user?.bestDisplayName.orEmpty()
    return KuiklySettingsAccountPayload(
        instanceId = "shared-shell-settings-account",
        profile =
            KuiklySettingsAccountProfile(
                displayName = displayName,
                handle = user?.email.orEmpty(),
                email = user?.email.orEmpty(),
                avatarLabel = displayName.take(2).uppercase(),
                verified = user?.emailVerified == true,
                roleLabel = session?.currentServerSummary()?.role.orEmpty()
            ),
        connectedAccounts = emptyList()
    )
}

private fun appShellComputersPayload(): KuiklySettingsComputersPayload =
    KuiklySettingsComputersPayload(
        instanceId = "shared-shell-settings-computers",
        computers = emptyList(),
        emptyTitle = "Computers are moving to shared Settings",
        emptyDetail = "The shared Computers API and detail route will be connected in the next Settings migration slice."
    )

private fun appShellStoragePayload(): KuiklySettingsStoragePayload =
    KuiklySettingsStoragePayload(
        instanceId = "shared-shell-settings-storage",
        totalSize = "Managed by Android",
        rows =
            listOf(
                KuiklySettingsStorageRow(
                    target = "database",
                    iconName = "archive",
                    title = "Local database",
                    detail = "Messages, task metadata, and server snapshots stored on this device.",
                    size = "--"
                ),
                KuiklySettingsStorageRow(
                    target = "imageCache",
                    iconName = "image",
                    title = "Image cache",
                    detail = "Downloaded image previews kept for faster viewing.",
                    size = "--"
                )
            )
    )

private fun appShellBillingPayload(session: StoredAuthSession?): KuiklySettingsBillingPayload {
    val plan = session?.currentServerSummary()?.plan?.takeIf { it.isNotBlank() } ?: "Hobby"
    return KuiklySettingsBillingPayload(
        instanceId = "shared-shell-settings-billing",
        currentPlan = KuiklySettingsCurrentPlan(planName = plan.replaceFirstChar { it.uppercase() }),
        plans =
            listOf(
                KuiklySettingsBillingPlan(id = plan, name = plan.replaceFirstChar { it.uppercase() }, price = "Current workspace plan", current = true),
                KuiklySettingsBillingPlan(id = "team", name = "Team", price = "Coming soon", comingSoon = true)
            )
    )
}

private fun appShellServerPayload(
    session: StoredAuthSession?,
    state: SharedServerSettingsState
): KuiklySharedSettingsPayload {
    val server = session?.currentServerSummary()
    val role = server?.role?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Member"
    val plan = server?.plan?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Unknown"
    val members =
        (state as? SharedServerSettingsState.Content)
            ?.members
            .orEmpty()
    val memberErrorCode = (state as? SharedServerSettingsState.Content)?.memberErrorCode
    val membersStatus =
        when (state) {
            SharedServerSettingsState.Loading -> "Loading"
            is SharedServerSettingsState.Error -> state.code
            is SharedServerSettingsState.Content ->
                memberErrorCode?.takeIf { it.isNotBlank() } ?: members.size.toString()
        }
    val adminCount = members.count { it.isWorkspaceAdmin() }
    val adminStatus =
        when (state) {
            SharedServerSettingsState.Loading -> "Loading"
            is SharedServerSettingsState.Error -> state.code
            is SharedServerSettingsState.Content ->
                memberErrorCode?.takeIf { it.isNotBlank() } ?: adminCount.toString()
        }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-server",
        title = "Server",
        heroIconName = "users",
        heroLabel = server?.name ?: "Workspace settings",
        detail = "Shared workspace settings now hydrates server identity and members through the shared bridge. Mutating actions remain host/API follow-ups.",
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Profile",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("serverName", "badge-info", "Workspace name", server?.name ?: "No selected server", status = "Read only"),
                            KuiklySharedSettingsRow("serverSlug", "link", "Slug", server?.slug?.let { "/$it" } ?: "Not provided", status = "Read only"),
                            KuiklySharedSettingsRow("serverRole", "shield-check", "Your role", "Permission scope for workspace settings.", status = role)
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Administration",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("members", "users", "Members", "Loaded from /servers/{serverId}/members through shared bridge.", status = membersStatus),
                            KuiklySharedSettingsRow("admins", "shield", "Admins", "Derived from member roles and permission badges.", status = adminStatus),
                            KuiklySharedSettingsRow("invites", "mail-plus", "Pending invites", "Invite creation/listing will be wired through shared actions.", status = "Pending"),
                            KuiklySharedSettingsRow("joinLinks", "link-2", "Join links", "Join link management is not exposed in shared yet.", status = "Pending"),
                            KuiklySharedSettingsRow("onboarding", "bot", "Onboarding agent", "Current onboarding agent id: ${server?.onboardingAgentId ?: "not set"}.", status = "Pending")
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Plan and danger",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("billing", "shield-check", "Plan", "Billing detail has a shared page; server billing summary still needs API hydrate.", status = plan),
                            KuiklySharedSettingsRow("danger", "triangle-alert", "Danger actions", "Delete workspace remains disabled until shared confirm + host API wiring is in place.", status = "Disabled")
                        )
                )
            )
    )
}

private fun MemberProfile.isWorkspaceAdmin(): Boolean {
    val role = roleDescription.orEmpty().lowercase()
    return "owner" in role ||
        "admin" in role ||
        permissionBadges.any { badge ->
            val normalized = badge.lowercase()
            "owner" in normalized || "admin" in normalized || "manage" in normalized
        }
}

private fun appShellMobilePayload(): KuiklySharedSettingsPayload =
    KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-mobile",
        title = "Mobile",
        heroIconName = "smartphone",
        heroLabel = "Mobile preferences",
        detail = "Shared mobile settings contract for platform capabilities. Host-specific permission and locale adapters still need to attach.",
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Notifications",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("notificationPermission", "bell", "Push permission", "Android/OHOS permission state must be supplied by host.", status = "Host pending"),
                            KuiklySharedSettingsRow("sendTestNotification", "send", "Send test notification", "Requires host notification adapter before it can run.", status = "Disabled"),
                            KuiklySharedSettingsRow("muteServer", "volume-x", "Mute workspace", "Server mute state/action is not in shared yet.", status = "Pending")
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Local preferences",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("language", "languages", "Language", "Locale selection needs shared preference state and host persistence.", status = "Pending"),
                            KuiklySharedSettingsRow("messageAlignment", "align-horizontal-space-around", "My message alignment", "Personal chat layout preference should move into shared state.", status = "Pending")
                        )
                )
            )
    )

private fun appShellAboutPayload(session: StoredAuthSession?): KuiklySharedSettingsPayload =
    KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-about",
        title = "About",
        heroIconName = "bug",
        heroLabel = "Slock diagnostics",
        detail = "About and diagnostics are represented in shared now; runtime version/log buffers still need host data.",
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "App",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("appName", "sparkles", "Application", "Slock", status = "Shared shell"),
                            KuiklySharedSettingsRow("signedInUser", "user", "Signed in as", session?.user?.bestDisplayName ?: "Unknown", status = session?.user?.email.orEmpty()),
                            KuiklySharedSettingsRow("selectedServer", "server", "Selected server", session?.currentServerSummary()?.name ?: "None", status = session?.currentServerId ?: "")
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Diagnostics",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("diagnostics", "clipboard-list", "Diagnostics log", "Needs host sanitized log buffer bridge before display/copy/share.", status = "Pending"),
                            KuiklySharedSettingsRow("feedback", "message-square-plus", "Send feedback", "Feedback should route through the real shared thread composer.", status = "Pending")
                        )
                )
            )
    )

private fun settingsDestinationTitle(destination: String): String =
    when (destination) {
        SettingsDestinationAccount -> "Account"
        SettingsDestinationComputers -> "Computers"
        SettingsDestinationServer -> "Server"
        SettingsDestinationBilling -> "Billing"
        SettingsDestinationMobile -> "Mobile"
        SettingsDestinationStorage -> "Storage"
        SettingsDestinationAbout -> "About"
        else -> "Settings"
    }

private fun settingsDestinationSubtitle(destination: String): String =
    when (destination) {
        SettingsDestinationAccount -> "Profile and sign out"
        SettingsDestinationComputers -> "Connected machines"
        SettingsDestinationServer -> "Workspace controls"
        SettingsDestinationBilling -> "Plan and usage"
        SettingsDestinationMobile -> "Phone and language"
        SettingsDestinationStorage -> "Local cache"
        SettingsDestinationAbout -> "Diagnostics"
        else -> ""
    }

private fun settingsDestinationIcon(destination: String): String =
    when (destination) {
        SettingsDestinationAccount -> "user"
        SettingsDestinationComputers -> "monitor"
        SettingsDestinationServer -> "users"
        SettingsDestinationBilling -> "shield-check"
        SettingsDestinationMobile -> "smartphone"
        SettingsDestinationStorage -> "archive"
        SettingsDestinationAbout -> "bug"
        else -> "settings"
    }

@Composable
private fun AppShellAuthGateContent(
    authDataSource: SharedAuthDataSource,
    onSignedIn: (StoredAuthSession) -> Unit
) {
    var mode by remember { mutableStateOf("login") }
    val reducer = remember { LoginFormReducer() }
    var state by remember { mutableStateOf(LoginFormState()) }
    var pendingLogin by remember { mutableStateOf<PendingAppShellLogin?>(null) }

    LaunchedEffect(pendingLogin) {
        val effect = pendingLogin ?: return@LaunchedEffect
        val result =
            runCatching { authDataSource.login(effect.email, effect.password) }
                .getOrElse { SharedAuthResult.Failure(SharedAuthErrorCode.Network, "Network unavailable. Check your connection.") }
        pendingLogin = null
        when (result) {
            is SharedAuthResult.Success -> {
                state = state.copy(submitting = false, errorMessage = null)
                onSignedIn(result.data)
            }
            is SharedAuthResult.Failure ->
                state = state.copy(submitting = false, errorMessage = result.message)
            SharedAuthResult.Unauthenticated ->
                state = state.copy(submitting = false, errorMessage = "Sign in failed. Try again.")
        }
    }

    when (mode) {
        "register" ->
            RegisterContent(
                name = "",
                email = "",
                password = "",
                submitting = false,
                errorMessage = "Create account will move into the shared app shell next.",
                onNameChange = {},
                onEmailChange = {},
                onPasswordChange = {},
                onSubmit = {},
                onBackToSignIn = { mode = "login" }
            )
        "forgot" ->
            ForgotPasswordContent(
                email = "",
                sending = false,
                sent = false,
                errorMessage = "Password reset will move into the shared app shell next.",
                onEmailChange = {},
                onSubmit = {},
                onBackToSignIn = { mode = "login" }
            )
        else ->
            LoginContent(
                email = state.email,
                password = state.password,
                submitting = state.submitting,
                errorMessage = state.errorMessage,
                onEmailChange = { state = reducer.reduce(state, LoginFormEvent.EmailChanged(it)).state },
                onPasswordChange = { state = reducer.reduce(state, LoginFormEvent.PasswordChanged(it)).state },
                onSubmit = {
                    val update = reducer.reduce(state, LoginFormEvent.Submit)
                    state = update.state
                    when (val effect = update.effect) {
                        is LoginFormEffect.SubmitLogin ->
                            pendingLogin =
                                PendingAppShellLogin(
                                    nonce = (pendingLogin?.nonce ?: 0) + 1,
                                    email = effect.email,
                                    password = effect.password
                                )
                        LoginFormEffect.FocusEmail -> Unit
                        null -> Unit
                    }
                },
                onForgotPassword = { mode = "forgot" },
                onCreateAccount = { mode = "register" }
            )
    }
}

private data class PendingAppShellLogin(
    val nonce: Int,
    val email: String,
    val password: String
)

@Composable
private fun AppShellServerGateContent(
    session: StoredAuthSession,
    authDataSource: SharedAuthDataSource,
    onReady: (StoredAuthSession) -> Unit,
    onSignOut: () -> Unit
) {
    var latestSession by remember(session) { mutableStateOf(session) }
    var mode by remember { mutableStateOf("choose") }
    var createName by remember { mutableStateOf("") }
    var pendingServerId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        when (val result = runCatching { authDataSource.loadServers() }.getOrNull()) {
            is SharedAuthResult.Success -> latestSession = result.data
            is SharedAuthResult.Failure -> errorMessage = result.message
            SharedAuthResult.Unauthenticated -> onSignOut()
            null -> errorMessage = "Could not load servers."
        }
    }

    AuthScaffold {
        AuthLogoBadge("SLOCK")
        Text(
            text = if (mode == "create") "Create server" else "Choose server",
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        AuthFormColumn {
            AuthError(errorMessage)
            if (mode == "create") {
                AuthTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    label = "Server name",
                    required = true,
                    enabled = !loading
                )
                AuthActionButton(
                    text = if (loading) "Creating..." else "Create server",
                    onClick = {
                        loading = true
                        errorMessage = null
                        pendingServerId = "__create__"
                    },
                    tone = AuthButtonTone.Pink,
                    enabled = createName.isNotBlank() && !loading,
                    loading = loading
                )
                AuthActionButton(
                    text = "Back to servers",
                    onClick = { mode = "choose" },
                    tone = AuthButtonTone.White,
                    enabled = !loading
                )
            } else {
                latestSession.servers.forEach { server ->
                    AppShellServerRow(
                        server = server,
                        selected = server.id == latestSession.selectedServerId,
                        pending = pendingServerId == server.id,
                        enabled = !loading,
                        onClick = {
                            loading = true
                            errorMessage = null
                            pendingServerId = server.id
                        }
                    )
                }
                AuthActionButton(
                    text = "Create server",
                    onClick = { mode = "create" },
                    tone = AuthButtonTone.Cyan,
                    enabled = !loading
                )
            }
            AuthActionButton(
                text = "Sign out",
                onClick = {
                    pendingServerId = "__logout__"
                    loading = true
                },
                tone = AuthButtonTone.White,
                enabled = !loading
            )
        }
    }

    LaunchedEffect(pendingServerId) {
        val target = pendingServerId ?: return@LaunchedEffect
        if (target == "__logout__") {
            when (val result = authDataSource.logout()) {
                is SharedAuthResult.Success -> onSignOut()
                is SharedAuthResult.Failure -> errorMessage = result.message
                SharedAuthResult.Unauthenticated -> onSignOut()
            }
            pendingServerId = null
            loading = false
            return@LaunchedEffect
        }
        val result =
            if (target == "__create__") {
                authDataSource.createServer(createName)
            } else {
                authDataSource.selectServer(target)
            }
        pendingServerId = null
        loading = false
        when (result) {
            is SharedAuthResult.Success -> onReady(result.data)
            is SharedAuthResult.Failure -> errorMessage = result.message
            SharedAuthResult.Unauthenticated -> onSignOut()
        }
    }
}

@Composable
private fun AppShellServerRow(
    server: ai.slock.compose.data.StoredAuthServer,
    selected: Boolean,
    pending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().height(42.dp).padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.fillMaxSize().background(SlockColor.ShadowBlack).padding(0.dp))
        Button(
            onClick = if (enabled) onClick else ({}),
            modifier = Modifier
                .fillMaxSize()
                .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = com.tencent.kuikly.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = server.name,
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (pending) "..." else if (selected) "Selected" else "Open",
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun AppShellAuthErrorContent(
    message: String,
    onRetry: () -> Unit,
    onSignIn: () -> Unit
) {
    AuthScaffold {
        AuthLogoBadge("SLOCK")
        Text(
            text = "Session error",
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        AuthError(message)
        AuthActionButton(text = "Retry", onClick = onRetry, tone = AuthButtonTone.Pink)
        AuthActionButton(text = "Sign in", onClick = onSignIn, tone = AuthButtonTone.White)
    }
}

@Composable
private fun SharedMembersScreen(bridge: ai.slock.compose.base.BridgeModule) {
    val authStore = remember { KuiklyAuthSessionStore(KuiklyRelationalStore(bridge)) }
    val membersApi = remember { BridgeMembersApi(bridge, authStore) }
    var state by remember { mutableStateOf<SharedMembersUiState>(SharedMembersUiState.Loading) }

    LaunchedEffect(Unit) {
        state = SharedMembersUiState.Loading
        try {
            val session = authStore.readSession()
            val serverId = session?.currentServerId
            val server = session?.servers?.firstOrNull { it.id == serverId }?.toServerSummary()
            if (session == null || serverId.isNullOrBlank() || server == null) {
                state = SharedMembersUiState.Error("server_required")
                return@LaunchedEffect
            }
            val response = membersApi.serverMembers(serverId)
            state =
                if (response.isSuccessful) {
                    val members = response.bodyText?.let(::parseSharedMembers).orEmpty()
                    SharedMembersUiState.Content(server, members)
                } else {
                    SharedMembersUiState.Error(response.errorBodyText?.takeIf { it.isNotBlank() } ?: "members_network")
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            state = SharedMembersUiState.Error("members_network")
        }
    }

    when (val current = state) {
        SharedMembersUiState.Loading ->
            AppShellPlaceholderContent(
                title = "Members",
                subtitle = "Loading...",
                iconName = "users"
            )
        is SharedMembersUiState.Error ->
            AppShellPlaceholderContent(
                title = "Members",
                subtitle = "Could not load members: ${current.code}",
                iconName = "users"
            )
        is SharedMembersUiState.Content ->
            MembersListContent(
                payload = KuiklyMembersPayload(
                    instanceId = current.server.id.value,
                    title = "Members",
                    members = current.members
                ),
                onAddHuman = { bridge.toast("Add member is not available yet") },
                onOpenMember = { bridge.toast(it.displayName) }
            )
    }
}

private sealed interface SharedMembersUiState {
    data object Loading : SharedMembersUiState
    data class Error(val code: String) : SharedMembersUiState
    data class Content(val server: ServerSummary, val members: List<MemberProfile>) : SharedMembersUiState
}

private fun parseSharedMembers(bodyText: String): List<MemberProfile> =
    runCatching { Json.parseToJsonElement(bodyText).collectMemberProfiles() }
        .getOrDefault(emptyList())
        .distinctBy { "${it.kind.name}:${it.id.value}" }

private fun JsonElement.collectMemberProfiles(defaultKind: MemberKind? = null): List<MemberProfile> =
    when (this) {
        is JsonArray -> mapNotNull { it.asObjectOrNull()?.toSharedMemberProfile(defaultKind) }
        is JsonObject -> buildList {
            this@collectMemberProfiles.toSharedMemberProfile(defaultKind)?.let(::add)
            addAll(this@collectMemberProfiles.arrayProfiles("members"))
            addAll(this@collectMemberProfiles.arrayProfiles("items"))
            addAll(this@collectMemberProfiles.arrayProfiles("results"))
            addAll(this@collectMemberProfiles.arrayProfiles("humans", MemberKind.Human))
            addAll(this@collectMemberProfiles.arrayProfiles("users", MemberKind.Human))
            addAll(this@collectMemberProfiles.arrayProfiles("agents", MemberKind.Agent))
            val data = this@collectMemberProfiles["data"]
            if (data is JsonArray) addAll(data.collectMemberProfiles(defaultKind))
            if (data is JsonObject) addAll(data.collectMemberProfiles(defaultKind))
        }
        else -> emptyList()
    }

private fun JsonObject.arrayProfiles(name: String, defaultKind: MemberKind? = null): List<MemberProfile> =
    (this[name] as? JsonArray)?.collectMemberProfiles(defaultKind).orEmpty()

private fun JsonObject.toSharedMemberProfile(defaultKind: MemberKind? = null): MemberProfile? {
    val profile = firstObject("profile", "user", "account", "member", "agent") ?: this
    val kind = explicitMemberKind(defaultKind, profile)
    val id =
        profile.string("id", "memberId", "userId", "agentId", "_id")
            ?: string("id", "memberId", "userId", "agentId", "_id")
            ?: return null
    val displayName =
        profile.string("displayName", "name", "username", "email")
            ?: string("displayName", "name", "username", "email")
            ?: id
    val handle =
        profile.string("handle", "username", "email")
            ?: string("handle", "username", "email")
            ?: displayName
    val avatarWireValue =
        profile.avatarWireValue()
            ?: avatarWireValue()
    val gravatarHash =
        profile.string("gravatarHash", "gravatar_hash", "gravatar", "gravatarMd5", "gravatar_md5")
            ?: string("gravatarHash", "gravatar_hash", "gravatar", "gravatarMd5", "gravatar_md5")
    val agentStatus = AgentStatus.fromWireValue(profile.string("agentStatus", "activity", "status") ?: string("agentStatus", "activity", "status"))
    return MemberProfile(
        id = MemberId(id),
        displayName = displayName,
        handle = handle,
        kind = kind,
        description = profile.string("description") ?: string("description"),
        roleDescription = profile.string("roleDescription", "role") ?: string("roleDescription", "role"),
        avatarUrl = avatarWireValue?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        gravatarHash = gravatarHash,
        avatar = AvatarReference.fromWireValue(avatarWireValue),
        avatarLabel = displayName.take(1).uppercase(),
        presence = (profile.string("presence", "availability") ?: string("presence", "availability")).toSharedPresence(),
        agentStatus = agentStatus,
        permissionBadges = profile.stringArray("permissionBadges") ?: stringArray("permissionBadges") ?: emptyList()
    )
}

private fun JsonObject.explicitMemberKind(defaultKind: MemberKind?, profile: JsonObject): MemberKind =
    (profile.string("kind", "memberKind", "type", "memberType") ?: string("kind", "memberKind", "type", "memberType"))
        ?.lowercase()
        ?.let { value ->
            when {
                "agent" in value || "bot" in value -> MemberKind.Agent
                "system" in value -> MemberKind.System
                "human" in value || "user" in value -> MemberKind.Human
                else -> null
            }
        }
        ?: if (profile.boolean("isAgent") == true || boolean("isAgent") == true || firstObject("agent") != null) MemberKind.Agent else null
        ?: defaultKind
        ?: MemberKind.Human

private fun String?.toSharedPresence(): Presence =
    when (this?.trim()?.lowercase()) {
        "active", "online", "available", "connected", "working", "thinking" -> Presence.Active
        "away", "idle", "busy" -> Presence.Away
        else -> Presence.Offline
    }

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.firstObject(vararg names: String): JsonObject? =
    names.firstNotNullOfOrNull { name -> this[name] as? JsonObject }

private fun JsonObject.string(vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } }

private fun JsonObject.boolean(vararg names: String): Boolean? =
    names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.booleanOrNull }

private fun JsonObject.stringArray(name: String): List<String>? =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }

private fun JsonObject.avatarWireValue(): String? =
    string(
        "avatarWireValue",
        "avatar",
        "avatarUrl",
        "avatar_url",
        "imageUrl",
        "profileImageUrl",
        "profileAvatar",
        "pictureUrl"
    )

@Composable
private fun SharedHomeScreen(
    bridge: ai.slock.compose.base.BridgeModule,
    authStore: KuiklyAuthSessionStore,
    dataSource: SharedHomeDataSource,
    refreshNonce: Int,
    onRefreshNonceChange: (Int) -> Unit,
    serverSwitchState: HomeServerSwitchState,
    onServerSwitchStateChange: (HomeServerSwitchState) -> Unit,
    serverSwitcherExpanded: Boolean,
    onServerSwitcherExpandedChange: (Boolean) -> Unit,
    state: SharedHomeUiState,
    onStateChange: (SharedHomeUiState) -> Unit,
    onOpenConversation: (ConversationSummary) -> Unit
) {
    LaunchedEffect(serverSwitchState.requestGeneration, refreshNonce) {
        val requestGeneration = serverSwitchState.requestGeneration
        val requestedServerId = serverSwitchState.selectedServerId
        onStateChange(state.asRefreshing())
        try {
            val session = authStore.readSession()
            if (session == null) {
                onStateChange(SharedHomeUiState.Error("unauthenticated"))
                return@LaunchedEffect
            }
            onStateChange(state.asRefreshing(session))
            requestedServerId?.let { serverId ->
                val server = session.servers.firstOrNull { it.id == serverId }
                authStore.updateSelectedServer(serverId = serverId, serverSlug = server?.slug)
            }
            val activeSession = authStore.readSession() ?: session
            onStateChange(state.asRefreshing(activeSession))
            val activeServerId = requestedServerId ?: activeSession.currentServerId
            val result =
                when (val result = dataSource.loadHome(serverId = activeServerId?.let(::ServerId), priority = DataRequestPriority.Visible)) {
                    is DataResult.Content -> SharedHomeUiState.Content(result.data, activeSession)
                    is DataResult.Empty -> SharedHomeUiState.Empty(activeSession)
                    is DataResult.Error -> SharedHomeUiState.Error(result.error.code)
                    is DataResult.NoPermission -> SharedHomeUiState.Error(result.blocker.code)
                }
            val resultServerId = (result as? SharedHomeUiState.Content)?.snapshot?.server?.id
                ?: activeServerId?.let(::ServerId)
            if (resultServerId == null || serverSwitchState.isCurrent(requestGeneration, resultServerId)) {
                onStateChange(result)
                if (resultServerId != null) {
                    onServerSwitchStateChange(serverSwitchState.complete(requestGeneration, resultServerId))
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            onStateChange(state.asRefreshFailed("home_network"))
        }
    }

    when (val current = state) {
        is SharedHomeUiState.Loading ->
            if (current.session == null) {
                AppShellPlaceholderContent(
                    title = "Home",
                    subtitle = "Loading workspace...",
                    iconName = "house"
                )
            } else {
                SharedHomeSessionShell(
                    session = current.session,
                    serverSwitcherExpanded = serverSwitcherExpanded,
                    contentRefreshing = true,
                    onRefresh = { onRefreshNonceChange(refreshNonce + 1) },
                    onServerBadgeClick = { onServerSwitcherExpandedChange(!serverSwitcherExpanded) },
                    onServerSelected = { serverId ->
                        onServerSwitcherExpandedChange(false)
                        onServerSwitchStateChange(serverSwitchState.select(serverId))
                    },
                    onCreateServerClick = {
                        bridge.toast("Create server is not available yet")
                    }
                )
            }
        is SharedHomeUiState.Empty ->
            SharedHomeSessionShell(
                session = current.session,
                serverSwitcherExpanded = serverSwitcherExpanded,
                contentRefreshing = false,
                onRefresh = { onRefreshNonceChange(refreshNonce + 1) },
                onServerBadgeClick = { onServerSwitcherExpandedChange(!serverSwitcherExpanded) },
                onServerSelected = { serverId ->
                    onServerSwitcherExpandedChange(false)
                    onServerSwitchStateChange(serverSwitchState.select(serverId))
                },
                onCreateServerClick = {
                    bridge.toast("Create server is not available yet")
                }
            )
        is SharedHomeUiState.Error ->
            AppShellPlaceholderContent(
                title = "Home",
                subtitle = "Could not load home: ${current.code}",
                iconName = "house"
            )
        is SharedHomeUiState.Content -> {
            val snapshot = current.snapshot
            val servers = current.session.servers.map { server ->
                server.toServerSummary().let { summary ->
                    if (summary.id == snapshot.server.id) {
                        snapshot.server
                    } else {
                        summary
                    }
                }.toSharedSwitcherItem()
            }.ifEmpty { listOf(snapshot.server.toSharedSwitcherItem()) }
            SlockStructuralHomeShell(
                currentServerName = snapshot.server.name,
                currentServerId = snapshot.server.id.value,
                servers = servers,
                channels = snapshot.channels.map { it.toSharedHomeConversationItem() },
                directMessages = snapshot.directMessages.map { it.toSharedHomeConversationItem() },
                selectedChannelId = snapshot.channels.firstOrNull()?.threadId?.value
                    ?: snapshot.directMessages.firstOrNull()?.threadId?.value,
                serverUnreadCount = snapshot.server.unreadCount,
                inboxUnreadCount = snapshot.unreadTotal,
                serverSwitcherExpanded = serverSwitcherExpanded,
                pendingServerId = serverSwitchState.pendingServerId,
                showBottomBar = false,
                outerPadding = 0.dp,
                contentRefreshing = current.contentRefreshing,
                onContentRefresh = { onRefreshNonceChange(refreshNonce + 1) },
                onServerBadgeClick = { onServerSwitcherExpandedChange(!serverSwitcherExpanded) },
                onServerSelected = { serverId ->
                    onServerSwitcherExpandedChange(false)
                    if (serverId == snapshot.server.id.value) {
                        onServerSwitchStateChange(serverSwitchState.refresh())
                        onRefreshNonceChange(refreshNonce + 1)
                    } else {
                        onServerSwitchStateChange(serverSwitchState.select(serverId))
                    }
                },
                onCreateServerClick = {
                    bridge.toast("Create server is not available yet")
                },
                onChannelClick = { threadId ->
                    val conversation =
                        (snapshot.channels + snapshot.directMessages)
                            .firstOrNull { it.threadId.value == threadId }
                    if (conversation == null) {
                        bridge.toast("Conversation is no longer available")
                    } else {
                        onOpenConversation(conversation)
                    }
                }
            )
        }
    }
}

private sealed interface SharedHomeUiState {
    data class Loading(val session: StoredAuthSession? = null) : SharedHomeUiState
    data class Empty(val session: StoredAuthSession) : SharedHomeUiState
    data class Error(val code: String) : SharedHomeUiState
    data class Content(
        val snapshot: HomeSnapshot,
        val session: StoredAuthSession,
        val contentRefreshing: Boolean = false
    ) : SharedHomeUiState
}

private fun SharedHomeUiState.asRefreshing(
    session: StoredAuthSession? = (this as? SharedHomeUiState.Content)?.session
): SharedHomeUiState =
    when (this) {
        is SharedHomeUiState.Content -> copy(contentRefreshing = true)
        is SharedHomeUiState.Empty -> SharedHomeUiState.Loading(session ?: this.session)
        is SharedHomeUiState.Loading -> SharedHomeUiState.Loading(session ?: this.session)
        is SharedHomeUiState.Error -> SharedHomeUiState.Loading(session)
    }

private fun SharedHomeUiState.asRefreshFailed(code: String): SharedHomeUiState =
    when (this) {
        is SharedHomeUiState.Content -> copy(contentRefreshing = false)
        else -> SharedHomeUiState.Error(code)
    }

@Composable
private fun SharedHomeSessionShell(
    session: StoredAuthSession,
    serverSwitcherExpanded: Boolean,
    contentRefreshing: Boolean,
    onRefresh: () -> Unit,
    onServerBadgeClick: () -> Unit,
    onServerSelected: (String) -> Unit,
    onCreateServerClick: () -> Unit
) {
    val currentServer = session.currentServerSummary()
    SlockStructuralHomeShell(
        currentServerName = currentServer?.name ?: "No server",
        currentServerId = currentServer?.id?.value ?: session.currentServerId.orEmpty(),
        servers = session.servers.map { it.toServerSummary().toSharedSwitcherItem() },
        channels = emptyList(),
        directMessages = emptyList(),
        serverSwitcherExpanded = serverSwitcherExpanded,
        showBottomBar = false,
        outerPadding = 0.dp,
        contentRefreshing = contentRefreshing,
        onContentRefresh = onRefresh,
        onServerBadgeClick = onServerBadgeClick,
        onServerSelected = onServerSelected,
        onCreateServerClick = onCreateServerClick
    )
}

internal fun StoredAuthSession.currentServerSummary(): ServerSummary? {
    val currentId = currentServerId
    return servers.firstOrNull { it.id == currentId }?.toServerSummary()
        ?: servers.firstOrNull()?.toServerSummary()
}

private fun ServerSummary.toSharedSwitcherItem(): SlockServerSwitcherItem =
    SlockServerSwitcherItem(
        id = id.value,
        name = name,
        slug = slug ?: description?.removePrefix("/") ?: id.value,
        unreadCount = unreadCount
    )

private fun ConversationSummary.toSharedHomeConversationItem(): SlockHomeConversationItem =
    SlockHomeConversationItem(
        id = threadId.value,
        name = name,
        unreadCount = unreadCount,
        avatarUrl = avatarUrl,
        avatarWireValue = avatar?.wireValue ?: avatarUrl,
        presence = presence.toSharedPresence(),
        hasDraft = hasDraft,
        joined = joined && permissions.canRead
    )

private fun Presence?.toSharedPresence(): SlockPresence? =
    when (this) {
        Presence.Active -> SlockPresence.Online
        Presence.Away -> SlockPresence.Unknown
        Presence.Offline -> SlockPresence.Offline
        null -> null
    }

internal data class AppShellThreadTimeContext(
    val localOffsetMinutes: Int,
    val todayEpochDay: Long
)

private fun com.tencent.kuikly.core.nvi.serialization.json.JSONObject.threadTimeContext(): AppShellThreadTimeContext? {
    val offset = optString("localOffsetMinutes").toIntOrNull() ?: return null
    val today = optString("todayEpochDay").toLongOrNull() ?: return null
    return AppShellThreadTimeContext(offset, today)
}

internal fun buildAppShellThreadPayload(
    conversation: ConversationSummary,
    timeContext: AppShellThreadTimeContext? = null
): KuiklyThreadScreenPayload =
    KuiklyThreadScreenPayload(
        instanceId = "shared-shell-thread-${conversation.threadId.value}",
        title = conversation.name.ifBlank { "Thread" },
        subtitle = conversation.subtitle ?: conversation.channelId?.value,
        status = "loading",
        sourceThreadId = conversation.threadId.value,
        selectedTab = "chat",
        navigationLabel = "Back",
        showModeTabs = true,
        showReplyActions = false,
        showOpenInChannelAction = false,
        joined = conversation.joined,
        canSend = conversation.joined && conversation.permissions.canSend,
        membersCount = conversation.memberCount ?: 0,
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        showMembersAction = true,
        showSettingsAction = true,
        emptyTitle = "Loading conversation",
        emptyDetail = "Loading messages from this conversation.",
        composerPayload =
            buildSharedThreadComposerPayloadJson(
                SharedThreadComposerPayloadInput(
                    seedText = "",
                    seedAsTask = false,
                    syncNonce = 0,
                    focusRequestNonce = 0,
                    placeholder = "Message #${conversation.name.ifBlank { conversation.threadId.value }}",
                    enabled = conversation.joined && conversation.permissions.canSend,
                    showAsTaskToggle = true,
                    composerMode = "channel"
                )
            )
    )

@Composable
private fun AppShellPlaceholderContent(
    title: String,
    subtitle: String,
    iconName: String
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SlockLucideIcon(name = iconName, color = "#FF000000", modifier = Modifier.size(36.dp))
        Text(
            title,
            style = SlockType.PageTitle.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitle,
            style = SlockType.Input.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BottomTabBarSmokeContent() {
    val bottomSafeArea = slockBottomSafeAreaPadding()
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Bottom TabBar",
                style = SlockType.PageTitle.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Harmony smoke preview",
                style = SlockType.Input.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                bottomTabBarSmokeItems.forEach { item ->
                    Text(
                        text = item.label,
                        style = SlockType.Input.copy(color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            "Edge",
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        SlockBottomTabBar(items = bottomTabBarSmokeItems, onSelect = {}, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Floating Dock",
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        SlockFloatingDockTabBar(items = bottomTabBarSmokeItems, onSelect = {}, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Navigation Rail",
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            SlockNavigationRailTabBar(items = bottomTabBarSmokeItems, onSelect = {})
            Column(
                modifier = Modifier.weight(1f).fillMaxSize().background(SlockColor.AppBgLightBlue).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Rail layout",
                    style = SlockType.PageTitle.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Shared TabBar variant parity with Android",
                    style = SlockType.Input.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        SlockAppBottomTabBarHost(
            items = bottomTabBarSmokeItems,
            onSelect = {},
            modifier = Modifier.fillMaxWidth(),
            bottomSafeArea = bottomSafeArea
        )
    }
}

@Composable
private fun SettingsHomeScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = rememberSettingsHomePayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.optString("instanceId")
    )
    LaunchedEffect(payload) {
        bridge.log("settings_home_render instanceId=${payload.instanceId} items=${payload.items.size}")
    }
    SettingsHomeContent(payload) { key ->
        bridge.settingsEvent(payload.instanceId, "openSettingsDestination", key)
    }
}

@Composable
private fun rememberSettingsHomePayload(payload: String, instanceId: String): KuiklySettingsHomePayload =
    androidx.compose.runtime.remember(payload, instanceId) {
        runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(KuiklySettingsHomePayload.serializer(), payload)
                .let { if (it.instanceId.isBlank()) it.copy(instanceId = instanceId) else it }
        }.getOrElse { KuiklySettingsHomePayload(instanceId = instanceId) }
    }

@Composable
internal fun SettingsHomeContent(
    payload: KuiklySettingsHomePayload,
    onSelect: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = slockTopSafeAreaPadding()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    payload.title,
                    style = SlockType.PageTitle.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                payload.subtitle.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = SlockType.Input.copy(color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        item {
            SlockVerticalTabBar(
                items = payload.items,
                showSelection = payload.showSelection,
                onSelect = onSelect
            )
        }
    }
}
