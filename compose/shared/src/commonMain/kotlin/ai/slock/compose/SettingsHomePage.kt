package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.data.BridgeAuthApi
import ai.slock.compose.data.BridgeHomeApi
import ai.slock.compose.data.BridgeMembersApi
import ai.slock.compose.data.BridgeSettingsPlatformCapabilities
import ai.slock.compose.data.BridgeTasksApi
import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.KuiklyAuthSessionStore
import ai.slock.compose.data.KuiklyRelationalStore
import ai.slock.compose.data.RelationalComposeLocalDataStore
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthErrorCode
import ai.slock.compose.data.SharedAuthGateIntent
import ai.slock.compose.data.SharedAuthGateState
import ai.slock.compose.data.SharedAuthGateViewModel
import ai.slock.compose.data.SharedAuthResult
import ai.slock.compose.data.SharedTasksDataSource
import ai.slock.compose.data.SharedTasksRouteIntent
import ai.slock.compose.data.SharedTasksViewModel
import ai.slock.compose.data.SharedHomeContentState
import ai.slock.compose.data.SharedHomeDataSource
import ai.slock.compose.data.SharedHomeIntent
import ai.slock.compose.data.SharedHomeState
import ai.slock.compose.data.SharedHomeTopAction
import ai.slock.compose.data.SharedHomeViewModel
import ai.slock.compose.data.SharedSettingsAboutSnapshot
import ai.slock.compose.data.SharedSettingsDataSource
import ai.slock.compose.data.SharedSettingsDestination
import ai.slock.compose.data.SharedSettingsDiagnosticsEntry
import ai.slock.compose.data.SharedSettingsDiagnosticsSnapshot
import ai.slock.compose.data.SharedSettingsEffect
import ai.slock.compose.data.SharedSettingsIntent
import ai.slock.compose.data.SharedSettingsState
import ai.slock.compose.data.SharedSettingsViewModel
import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.parseSlockHome
import ai.slock.compose.data.BridgeThreadApi
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageType
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.Presence
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.ThreadUpdated
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.navigation.NavHost
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.navigation.composable
import com.tencent.kuikly.compose.material3.navigation.rememberNavController
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.BackPressCallback
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
const val BOTTOM_TAB_BAR_VISUAL_PAGE = "compose_bottom_tabbar_visual"
private const val SETTINGS_ROW_VISUAL_PAGE = "compose_settings_row_visual"
private const val SETTINGS_PAGE_VISUAL_PAGE = "compose_settings_page_visual"
const val SLOCK_APP_SHELL_PAGE = "compose_slock_app_shell"

private const val AppShellRouteMain = "main"
private const val AppShellRouteThread = "thread"
private const val HomeDestinationSearch = "search"
private const val HomeDestinationActivity = "activity"
private const val HomeDestinationSaved = "saved"
private const val SettingsDestinationHome = "home"
private const val SettingsDestinationAccount = "account"
private const val SettingsDestinationComputers = "computers"
private const val SettingsDestinationServer = "server"
private const val SettingsDestinationBilling = "billing"
private const val SettingsDestinationMobile = "mobile"
private const val SettingsDestinationStorage = "storage"
private const val SettingsDestinationAbout = "about"
private const val SettingsDestinationDiagnostics = "diagnostics"
private const val SettingsDestinationRouter = "router"
private const val AppShellRealtimeEvent = "slockRealtimeEvent"

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

@Page(BOTTOM_TAB_BAR_VISUAL_PAGE, supportInLocal = true)
internal class ComposeBottomTabBarVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { BottomTabBarVisualContent() }
    }
}

@Page(SETTINGS_ROW_VISUAL_PAGE, supportInLocal = true)
internal class ComposeSettingsRowVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsRowVisualContent() }
    }
}

@Page(SETTINGS_PAGE_VISUAL_PAGE, supportInLocal = true)
internal class ComposeSettingsPageVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsPageVisualContent() }
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
            subtitle = "Connected machines and workspaces",
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

private val settingsVisualItems = listOf(
    SlockTabItemPayload(
        key = "account",
        title = "Account",
        subtitle = "Profile, email, password, connected accounts",
        iconName = "user",
        value = "Verified",
        selected = true
    ),
    SlockTabItemPayload(
        key = "language-region",
        title = "Language & Region",
        subtitle = "Language, timezone, translation",
        iconName = "languages"
    ),
    SlockTabItemPayload(
        key = "appearance",
        title = "Appearance",
        subtitle = "Message font size and reading density",
        iconName = "type"
    ),
    SlockTabItemPayload(
        key = "browser",
        title = "Browser",
        subtitle = "Notifications and local browser state",
        iconName = "app-window"
    ),
    SlockTabItemPayload(
        key = "server",
        title = "Server",
        subtitle = "Workspace, members, invites, permissions",
        iconName = "building-2",
        value = "Owner"
    ),
    SlockTabItemPayload(
        key = "computers",
        title = "Computers",
        subtitle = "Connected machines and workspaces",
        iconName = "monitor"
    ),
    SlockTabItemPayload(
        key = "release-notes",
        title = "Release Notes",
        subtitle = "Product changes and migration notes",
        iconName = "file-text"
    )
)

private val settingsPageVisualPayload = KuiklySharedSettingsPayload(
    instanceId = "visual-settings-page",
    title = "Account",
    detail = "Profile, email, password, connected accounts",
    heroIconName = "user",
    heroLabel = "Artin",
    sections = listOf(
        KuiklySharedSettingsSection(
            title = "Profile",
            rows = listOf(
                KuiklySharedSettingsRow("displayName", "user", "Display name", "Shown across messages, tasks, and mentions.", status = "Artin"),
                KuiklySharedSettingsRow("email", "mail", "Email", "artin@slock.test", status = "Verified"),
                KuiklySharedSettingsRow("avatar", "upload", "Avatar", "Upload a custom human avatar.", status = "Ready")
            )
        ),
        KuiklySharedSettingsSection(
            title = "Session",
            rows = listOf(
                KuiklySharedSettingsRow("password", "shield", "Password", "Change password and connected credentials.", status = "Enabled"),
                KuiklySharedSettingsRow("logout", "log-out", "Log out", "Sign out of this browser.", status = "Action")
            )
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
    var selectedHomeDestination by remember { mutableStateOf<String?>(null) }
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val relationalStore = remember { KuiklyRelationalStore(bridge) }
    val authStore = remember { KuiklyAuthSessionStore(relationalStore) }
    val localWriteThrough =
        remember {
            ComposeLocalWriteThrough(
                RelationalComposeLocalDataStore(relationalStore),
                logger = { message -> bridge.log("shared_home_cache $message") }
            )
        }
    val authDataSource =
        remember {
            SharedAuthDataSource(
                transport = BridgeAuthApi(bridge),
                sessionStore = authStore
            )
        }
    val homeDataSource =
        remember {
            SharedHomeDataSource(
                sessionStore = authStore,
                api = BridgeHomeApi(bridge, authStore),
                localWriteThrough = localWriteThrough,
                parseHome = { payload, session ->
                    parseSlockHome(payload, session) { message -> bridge.log(message) }
                }
            )
        }
    val membersApi = remember { BridgeMembersApi(bridge, authStore) }
    val threadDataSource =
        remember {
            SharedThreadDataSource(
                networkApi = BridgeThreadApi(bridge, authStore),
                sessionStore = authStore,
                localWriteThrough = localWriteThrough
            )
        }
    val tasksDataSource =
        remember {
            SharedTasksDataSource(
                api = BridgeTasksApi(bridge, authStore)
            )
        }
    val settingsCapabilities = remember { BridgeSettingsPlatformCapabilities(bridge) }
    val settingsDataSource =
        remember {
            SharedSettingsDataSource(
                sessionStore = authStore,
                platformCapabilities = settingsCapabilities
            )
        }
    var activeThreadPayload by remember { mutableStateOf<KuiklyThreadScreenPayload?>(null) }
    var threadBackStack by remember { mutableStateOf<List<KuiklyThreadScreenPayload>>(emptyList()) }
    val authGateViewModel: SharedAuthGateViewModel =
        viewModel(key = "shared-auth-gate") {
            SharedAuthGateViewModel(authDataSource)
        }
    val authGateState by authGateViewModel.state.collectAsState()
    val homeViewModel: SharedHomeViewModel =
        viewModel(key = "shared-home") {
            SharedHomeViewModel(
                authStore = authStore,
                dataSource = homeDataSource
            )
        }
    val homeState by homeViewModel.state.collectAsState()
    val tasksViewModel: SharedTasksViewModel =
        viewModel(key = "shared-tasks") {
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = tasksDataSource
            )
        }
    val tasksState by tasksViewModel.state.collectAsState()
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "shared-settings") {
            SharedSettingsViewModel(settingsDataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    val navController = rememberNavController()
    val bottomSafeArea = slockBottomSafeAreaPadding()
    val threadTimeContext = remember(pager.pageData.params.toString()) {
        pager.pageData.params.threadTimeContext()
    }

    LaunchedEffect(Unit) {
        authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
    }
    LaunchedEffect(authStore) {
        authStore.sessionInvalidations.collect {
            authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
            selectedTab = "home"
            selectedSettingsDestination = SettingsDestinationHome
            activeThreadPayload = null
            threadBackStack = emptyList()
        }
    }

    when (val gate = authGateState) {
        SharedAuthGateState.Restoring -> {
            AppShellRestoreSplashContent()
            return
        }
        SharedAuthGateState.AuthRequired -> {
            AppShellAuthGateContent(
                authDataSource = authDataSource,
                onSignedIn = { session ->
                    authGateViewModel.dispatch(SharedAuthGateIntent.SignedIn(session))
                }
            )
            return
        }
        is SharedAuthGateState.ServerRequired -> {
            AppShellServerGateContent(
                session = gate.session,
                authDataSource = authDataSource,
                onReady = { session ->
                    authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(session))
                    selectedTab = "home"
                    selectedHomeDestination = null
                    activeThreadPayload = null
                    threadBackStack = emptyList()
                },
                onSignOut = {
                    authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
                    selectedTab = "home"
                    selectedHomeDestination = null
                    activeThreadPayload = null
                    threadBackStack = emptyList()
                }
            )
            return
        }
        is SharedAuthGateState.Error -> {
            AppShellAuthErrorContent(
                message = gate.message,
                onRetry = { authGateViewModel.dispatch(SharedAuthGateIntent.Retry) },
                onSignIn = { authGateViewModel.dispatch(SharedAuthGateIntent.ShowSignIn) }
            )
            return
        }
        is SharedAuthGateState.Ready -> Unit
    }

    fun popThreadRoute(): Boolean {
        if (threadBackStack.isNotEmpty()) {
            activeThreadPayload = threadBackStack.last()
            threadBackStack = threadBackStack.dropLast(1)
            return true
        }
        if (navController.currentRoute != AppShellRouteThread && !navController.canNavigateBack) {
            return false
        }
        val popped = navController.popBackStack()
        if (popped) {
            activeThreadPayload = null
            threadBackStack = emptyList()
        }
        return popped
    }

    fun openThreadPayload(payload: KuiklyThreadScreenPayload) {
        activeThreadPayload?.let { currentPayload ->
            threadBackStack = threadBackStack + currentPayload
        }
        activeThreadPayload = payload
        if (navController.currentRoute != AppShellRouteThread) {
            navController.navigate(AppShellRouteThread)
        }
    }

    fun popMainRoute(): Boolean {
        if (homeState.serverSwitcherExpanded) {
            homeViewModel.dispatch(SharedHomeIntent.DismissServerSwitcher)
            return true
        }
        if (selectedTab == "settings" && selectedSettingsDestination != SettingsDestinationHome) {
            selectedSettingsDestination = SettingsDestinationHome
            return true
        }
        if (selectedTab == "home" && selectedHomeDestination != null) {
            selectedHomeDestination = null
            return true
        }
        if (selectedTab != "home") {
            selectedTab = "home"
            selectedSettingsDestination = SettingsDestinationHome
            selectedHomeDestination = null
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

    LaunchedEffect(selectedTab) {
        if (selectedTab == "tasks") {
            tasksViewModel.dispatch(SharedTasksRouteIntent.Start)
        }
    }

    DisposableEffect(pager, homeViewModel) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != AppShellRealtimeEvent) return
                    val serverId = eventData.optString("serverId").takeIf { it.isNotBlank() } ?: return
                    val payload = eventData.optString("payload").takeIf { it.isNotBlank() } ?: return
                    val event =
                        parseSharedShellRealtimeEvent(payload).getOrElse { throwable ->
                            bridge.log("shared_realtime_decode_failed ${throwable.message.orEmpty()}")
                            return
                        }
                    homeViewModel.dispatch(SharedHomeIntent.ApplyRealtimeEvent(serverId, event))
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
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
                    dataSource = threadDataSource,
                    onBack = { popThreadRoute() },
                    onOpenThreadPayload = { payload -> openThreadPayload(payload) },
                    onOpenParentThread = {
                        if (threadBackStack.isNotEmpty()) {
                            activeThreadPayload = threadBackStack.last()
                            threadBackStack = threadBackStack.dropLast(1)
                            true
                        } else {
                            false
                        }
                    }
                )
            }
        }
        composable(AppShellRouteMain) {
            Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        "settings" -> {
                            SettingsAppShellContent(
                                session = (authGateState as? SharedAuthGateState.Ready)?.session,
                                authDataSource = authDataSource,
                                settingsCapabilities = settingsCapabilities,
                                settingsState = settingsState,
                                onSettingsIntent = settingsViewModel::dispatch,
                                membersApi = membersApi,
                                selectedDestination = selectedSettingsDestination,
                                onDestinationChange = { selectedSettingsDestination = it },
                                onOpenRouter = { jumpPage(pager, "router") },
                                onSettingsAction = { action, itemId ->
                                    bridge.settingsEvent("shared-shell-settings", action, itemId)
                                },
                                onLogout = {
                                    authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
                                    selectedTab = "home"
                                    selectedSettingsDestination = SettingsDestinationHome
                                    selectedHomeDestination = null
                                    activeThreadPayload = null
                                    threadBackStack = emptyList()
                                }
                            )
                        }
                        "tasks" -> SharedTasksScreen(
                            state = tasksState,
                            onIntent = tasksViewModel::dispatch
                        )
                        "members" -> SharedMembersScreen(bridge)
                        else ->
                            if (selectedHomeDestination == null) {
                                SharedHomeScreen(
                                    bridge = bridge,
                                    state = homeState,
                                    onIntent = homeViewModel::dispatch,
                                    onOpenSearch = { selectedHomeDestination = HomeDestinationSearch },
                                    onOpenActivity = { selectedHomeDestination = HomeDestinationActivity },
                                    onOpenSaved = { selectedHomeDestination = HomeDestinationSaved },
                                    onOpenConversation = { conversation ->
                                        selectedHomeDestination = null
                                        activeThreadPayload = buildAppShellThreadPayload(conversation, threadTimeContext)
                                        threadBackStack = emptyList()
                                        navController.navigate(AppShellRouteThread)
                                    }
                                )
                            } else {
                                SharedHomeDestinationPlaceholder(selectedHomeDestination ?: HomeDestinationActivity)
                            }
                    }
                }
                SlockAppBottomTabBarHost(
                    items = appShellBottomTabs(selectedTab),
                    onSelect = {
                        selectedTab = it
                        if (it == "home") {
                            selectedHomeDestination = null
                        }
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

@Composable
private fun SettingsAppShellContent(
    session: StoredAuthSession?,
    authDataSource: SharedAuthDataSource,
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    settingsState: SharedSettingsState,
    onSettingsIntent: (SharedSettingsIntent) -> Unit,
    membersApi: BridgeMembersApi,
    selectedDestination: String,
    onDestinationChange: (String) -> Unit,
    onOpenRouter: () -> Unit,
    onSettingsAction: (String, String?) -> Unit,
    onLogout: () -> Unit
) {
    var pendingLogout by remember { mutableStateOf(false) }

    LaunchedEffect(selectedDestination) {
        selectedDestination.toSharedSettingsDestination()?.let { destination ->
            onSettingsIntent(SharedSettingsIntent.OpenDestination(destination))
        }
    }

    LaunchedEffect(pendingLogout) {
        if (!pendingLogout) return@LaunchedEffect
        pendingLogout = false
        runCatching { authDataSource.logout() }
        onLogout()
    }

    LaunchedEffect(settingsState.effect) {
        val effect = settingsState.effect ?: return@LaunchedEffect
        when (effect) {
            SharedSettingsEffect.CopyDiagnostics -> {
                val ok = settingsCapabilities.copyDiagnostics()
                if (ok) {
                    onSettingsIntent(SharedSettingsIntent.Refresh)
                }
            }
            SharedSettingsEffect.ShareDiagnostics -> {
                settingsCapabilities.shareDiagnostics()
            }
            SharedSettingsEffect.ClearDiagnostics -> {
                val ok = settingsCapabilities.clearDiagnostics()
                if (ok) {
                    onSettingsIntent(SharedSettingsIntent.Refresh)
                }
            }
            SharedSettingsEffect.SendFeedback -> {
                settingsCapabilities.sendFeedback()
            }
            else -> Unit
        }
        onSettingsIntent(SharedSettingsIntent.ConsumeEffect)
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
                SettingsAccountContent(appShellAccountPayload(session), applyTopSafeArea = false) { action ->
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
                SharedSettingsAboutContent(
                    state = settingsState.about,
                    fallback = appShellAboutFallback(session),
                    onOpenDiagnostics = { onDestinationChange(SettingsDestinationDiagnostics) },
                    onCopyBuild = { summary ->
                        settingsCapabilities.bridgeCopyToClipboard(summary)
                    },
                    onOpenSlock = { settingsCapabilities.openUrl("https://app.slock.ai") },
                    onSendFeedback = { onSettingsIntent(SharedSettingsIntent.SendFeedback) }
                )
            SettingsDestinationDiagnostics ->
                SharedSettingsDiagnosticsContent(
                    state = settingsState.diagnostics,
                    onCopyLogs = { onSettingsIntent(SharedSettingsIntent.CopyDiagnostics) },
                    onShareLogs = { onSettingsIntent(SharedSettingsIntent.ShareDiagnostics) },
                    onClearLogs = { onSettingsIntent(SharedSettingsIntent.ClearDiagnostics) },
                    onSendFeedback = { onSettingsIntent(SharedSettingsIntent.SendFeedback) }
                )
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
private fun SharedSettingsAboutContent(
    state: ScreenLoadState<SharedSettingsAboutSnapshot>,
    fallback: SharedSettingsAboutSnapshot,
    onOpenDiagnostics: () -> Unit,
    onCopyBuild: (String) -> Unit,
    onOpenSlock: () -> Unit,
    onSendFeedback: () -> Unit
) {
    val snapshot =
        when (state) {
            is ScreenLoadState.Content -> state.data
            else -> fallback
        }
    val payload = snapshot.toAboutPayload()
    SharedSettingsSectionContent(payload) { action, value ->
        when (action) {
            "diagnostics" -> onOpenDiagnostics()
            "copyBuild" -> onCopyBuild(value.orEmpty().ifBlank { snapshot.buildSummary() })
            "openSlock" -> onOpenSlock()
            "feedback" -> onSendFeedback()
        }
    }
}

@Composable
private fun SharedSettingsDiagnosticsContent(
    state: ScreenLoadState<SharedSettingsDiagnosticsSnapshot>,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onSendFeedback: () -> Unit
) {
    val entries =
        when (state) {
            is ScreenLoadState.Content -> state.data.entries
            else -> emptyList()
        }
    val payload = entries.toDiagnosticsPayload(state)
    SharedSettingsSectionContent(payload) { action, _ ->
        when (action) {
            "copyLogs" -> onCopyLogs()
            "shareLogs" -> onShareLogs()
            "clearLogs" -> onClearLogs()
            "feedback" -> onSendFeedback()
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
    val topSafeArea = slockTopSafeAreaPadding()
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(start = 20.dp, top = topSafeArea + 16.dp, end = 20.dp, bottom = 14.dp),
            verticalAlignment = com.tencent.kuikly.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .size(28.dp)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
            ) {
                SlockLucideIcon("arrow-left", modifier = Modifier.size(16.dp))
            }
            Text(
                title,
                style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
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
                    subtitle = "Connected machines and workspaces",
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

private fun appShellAboutFallback(session: StoredAuthSession?): SharedSettingsAboutSnapshot =
    SharedSettingsAboutSnapshot(
        appName = "Slock",
        versionLabel = "Loading",
        signedInUserLabel = session?.user?.bestDisplayName ?: "Unknown",
        selectedServerLabel = session?.currentServerSummary()?.name ?: "None",
        sessionStatusLabel = if (session != null) "Signed in" else "Signed out",
        socketStatusLabel = "Checking",
        notificationStatusLabel = "Checking"
    )

private fun SharedSettingsAboutSnapshot.toAboutPayload(): KuiklySharedSettingsPayload =
    KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-about",
        title = "About",
        heroIconName = "bug",
        heroLabel = appName.ifBlank { "Slock" },
        detail = "Version, diagnostics, feedback",
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Application",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("appName", "sparkles", "Application", appName.ifBlank { "Slock" }, status = "Shared shell"),
                            KuiklySharedSettingsRow("version", "badge-info", "Version", versionLabel.ifBlank { "Unknown" }, status = buildLabel.ifBlank { "Build unknown" }),
                            KuiklySharedSettingsRow("package", "package", "Package", packageName.ifBlank { "Unknown" }, status = "Read only"),
                            KuiklySharedSettingsRow("device", "smartphone", "Device", deviceLabel.ifBlank { "Unknown" }, status = platformLabel.ifBlank { "Platform" })
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Runtime",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("signedInUser", "user", "Signed in as", signedInUserLabel.ifBlank { "Unknown" }, status = sessionStatusLabel),
                            KuiklySharedSettingsRow("selectedServer", "server", "Selected server", selectedServerLabel.ifBlank { "None" }, status = socketStatusLabel),
                            KuiklySharedSettingsRow("notifications", "bell", "Notifications", notificationStatusLabel.ifBlank { "Unknown" }, status = "Host")
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Actions",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("diagnostics", "clipboard-list", "Diagnostics log", "Open sanitized runtime logs.", status = "Open", enabled = true),
                            KuiklySharedSettingsRow("copyBuild", "copy", "Copy version/build", buildSummary(), status = "Copy", enabled = true),
                            KuiklySharedSettingsRow("openSlock", "external-link", "Open Slock", "Open app.slock.ai in the system browser.", status = "Open", enabled = true),
                            KuiklySharedSettingsRow("feedback", "message-square-plus", "Send feedback", "Open the host feedback/share flow with diagnostics.", status = "Send", enabled = true)
                        )
                )
            )
    )

private fun SharedSettingsAboutSnapshot.buildSummary(): String =
    buildString {
        append(appName.ifBlank { "Slock" })
        versionLabel.takeIf { it.isNotBlank() }?.let { append(" $it") }
        buildLabel.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
        packageName.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
    }

private fun List<SharedSettingsDiagnosticsEntry>.toDiagnosticsPayload(
    state: ScreenLoadState<SharedSettingsDiagnosticsSnapshot>
): KuiklySharedSettingsPayload {
    val logsAvailable = isNotEmpty()
    val stateLabel =
        when (state) {
            is ScreenLoadState.Content -> if (logsAvailable) "${size} entries" else "Empty"
            is ScreenLoadState.Error -> state.error.code
            is ScreenLoadState.NoPermission -> state.blocker.code
            is ScreenLoadState.Empty -> state.reason.name
            ScreenLoadState.Loading -> "Loading"
        }
    val logRows =
        if (logsAvailable) {
            takeLast(20).mapIndexed { index, entry ->
                KuiklySharedSettingsRow(
                    key = "log-$index",
                    iconName = "terminal",
                    title = entry.tag.ifBlank { "Log" },
                    detail = "${entry.timestampLabel} ${entry.message}".trim(),
                    status = ""
                )
            }
        } else {
            listOf(
                KuiklySharedSettingsRow(
                    key = "emptyLogs",
                    iconName = "circle-dashed",
                    title = "No diagnostics yet",
                    detail = "Runtime diagnostics will appear here after app activity.",
                    status = stateLabel
                )
            )
        }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-diagnostics",
        title = "Diagnostics",
        detail = "Sanitized app diagnostics",
        heroIconName = "clipboard-list",
        heroLabel = "Diagnostics",
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Actions",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("copyLogs", "copy", "Copy logs", "Copy sanitized diagnostics to the clipboard.", status = if (logsAvailable) "Copy" else "Disabled", enabled = logsAvailable),
                            KuiklySharedSettingsRow("shareLogs", "share", "Share logs", "Open the native share sheet with sanitized diagnostics.", status = if (logsAvailable) "Share" else "Disabled", enabled = logsAvailable),
                            KuiklySharedSettingsRow("feedback", "message-square-plus", "Send feedback", "Open the host feedback/share flow with diagnostics.", status = "Send", enabled = true),
                            KuiklySharedSettingsRow("clearLogs", "trash-2", "Clear logs", "Clear the sanitized local diagnostics buffer.", status = if (logsAvailable) "Clear" else "Disabled", enabled = logsAvailable)
                        )
                ),
                KuiklySharedSettingsSection(title = "Log", rows = logRows)
            )
    )
}

private fun settingsDestinationTitle(destination: String): String =
    when (destination) {
        SettingsDestinationAccount -> "Account"
        SettingsDestinationComputers -> "Computers"
        SettingsDestinationServer -> "Server"
        SettingsDestinationBilling -> "Billing"
        SettingsDestinationMobile -> "Mobile"
        SettingsDestinationStorage -> "Storage"
        SettingsDestinationAbout -> "About"
        SettingsDestinationDiagnostics -> "Diagnostics"
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
        SettingsDestinationDiagnostics -> "Sanitized runtime logs"
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
        SettingsDestinationDiagnostics -> "clipboard-list"
        else -> "settings"
    }

private fun String.toSharedSettingsDestination(): SharedSettingsDestination? =
    when (this) {
        SettingsDestinationAccount -> SharedSettingsDestination.Account
        SettingsDestinationComputers -> SharedSettingsDestination.Computers
        SettingsDestinationServer -> SharedSettingsDestination.Server
        SettingsDestinationBilling -> SharedSettingsDestination.Billing
        SettingsDestinationMobile -> SharedSettingsDestination.Mobile
        SettingsDestinationStorage -> SharedSettingsDestination.Storage
        SettingsDestinationAbout -> SharedSettingsDestination.About
        SettingsDestinationDiagnostics -> SharedSettingsDestination.Diagnostics
        SettingsDestinationHome -> SharedSettingsDestination.Home
        else -> null
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
            val (response, agentsResponse) =
                coroutineScope {
                    val membersDeferred = async { membersApi.serverMembers(serverId) }
                    val agentsDeferred = async { membersApi.agents(serverId) }
                    membersDeferred.await() to agentsDeferred.await()
                }
            state =
                if (response.isSuccessful) {
                    val members = response.bodyText?.let(::parseSharedMembers).orEmpty()
                    val agents =
                        if (agentsResponse.isSuccessful) {
                            agentsResponse.bodyText?.let(::parseSharedAgents).orEmpty()
                        } else {
                            emptyList()
                        }
                    SharedMembersUiState.Content(server, mergeSharedMembers(members, agents))
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

internal fun parseSharedMembers(bodyText: String): List<MemberProfile> =
    runCatching { Json.parseToJsonElement(bodyText).collectMemberProfiles() }
        .getOrDefault(emptyList())
        .distinctBy { "${it.kind.name}:${it.id.value}" }

internal fun parseSharedAgents(bodyText: String): List<MemberProfile> =
    runCatching { Json.parseToJsonElement(bodyText).collectMemberProfiles(MemberKind.Agent) }
        .getOrDefault(emptyList())
        .map { it.copy(kind = MemberKind.Agent) }
        .distinctBy { "${it.kind.name}:${it.id.value}" }

internal fun mergeSharedMembers(
    members: List<MemberProfile>,
    agents: List<MemberProfile>
): List<MemberProfile> =
    (members + agents.map { it.copy(kind = MemberKind.Agent) })
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
    state: SharedHomeState,
    onIntent: (SharedHomeIntent) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenConversation: (ConversationSummary) -> Unit
) {
    LaunchedEffect(Unit) {
        onIntent(SharedHomeIntent.Start)
    }

    val serverSwitchState = state.serverSwitchState
    val serverSwitcherExpanded = state.serverSwitcherExpanded
    when (val current = state.content) {
        is SharedHomeContentState.Loading ->
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
                    onRefresh = { onIntent(SharedHomeIntent.Refresh) },
                    onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                    onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                    onServerSelected = { serverId -> onIntent(SharedHomeIntent.SelectServer(serverId)) },
                    onCreateServerClick = {
                        bridge.toast("Create server is not available yet")
                    }
                )
            }
        is SharedHomeContentState.Empty ->
            SharedHomeSessionShell(
                session = current.session,
                serverSwitcherExpanded = serverSwitcherExpanded,
                contentRefreshing = false,
                onRefresh = { onIntent(SharedHomeIntent.Refresh) },
                onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                onServerSelected = { serverId -> onIntent(SharedHomeIntent.SelectServer(serverId)) },
                onCreateServerClick = {
                    bridge.toast("Create server is not available yet")
                }
            )
        is SharedHomeContentState.Error ->
            AppShellPlaceholderContent(
                title = "Home",
                subtitle = "Could not load home: ${current.code}",
                iconName = "house"
            )
        is SharedHomeContentState.Content -> {
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
                selectedChannelId = state.selectedConversationId,
                serverUnreadCount = snapshot.server.unreadCount,
                inboxUnreadCount = snapshot.unreadTotal,
                serverSwitcherExpanded = serverSwitcherExpanded,
                pendingServerId = serverSwitchState.pendingServerId,
                activitySelected = state.selectedTopAction == SharedHomeTopAction.Activity,
                searchSelected = state.selectedTopAction == SharedHomeTopAction.Search,
                savedSelected = state.selectedTopAction == SharedHomeTopAction.Saved,
                showBottomBar = false,
                outerPadding = 0.dp,
                contentRefreshing = current.contentRefreshing,
                onContentRefresh = { onIntent(SharedHomeIntent.Refresh) },
                onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                onServerSelected = { serverId -> onIntent(SharedHomeIntent.SelectServer(serverId)) },
                onCreateServerClick = {
                    bridge.toast("Create server is not available yet")
                },
                onSearchClick = {
                    onIntent(SharedHomeIntent.SelectTopAction(SharedHomeTopAction.Search))
                    onOpenSearch()
                },
                onInboxClick = {
                    onIntent(SharedHomeIntent.SelectTopAction(SharedHomeTopAction.Activity))
                    onOpenActivity()
                },
                onSavedClick = {
                    onIntent(SharedHomeIntent.SelectTopAction(SharedHomeTopAction.Saved))
                    onOpenSaved()
                },
                onChannelClick = { threadId ->
                    val conversation =
                        (snapshot.channels + snapshot.directMessages)
                            .firstOrNull { it.threadId.value == threadId }
                    if (conversation == null) {
                        bridge.toast("Conversation is no longer available")
                    } else {
                        onIntent(SharedHomeIntent.SelectConversation(threadId))
                        onOpenConversation(conversation)
                    }
                }
            )
        }
    }
}

@Composable
private fun SharedHomeSessionShell(
    session: StoredAuthSession,
    serverSwitcherExpanded: Boolean,
    contentRefreshing: Boolean,
    onRefresh: () -> Unit,
    onServerBadgeClick: () -> Unit,
    onServerSwitcherDismiss: () -> Unit,
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
        onServerSwitcherDismiss = onServerSwitcherDismiss,
        onServerSelected = onServerSelected,
        onCreateServerClick = onCreateServerClick
    )
}

@Composable
private fun SharedHomeDestinationPlaceholder(destination: String) {
    val title: String
    val subtitle: String
    val iconName: String
    when (destination) {
        HomeDestinationSearch -> {
            title = "Search"
            subtitle = "Search is opening from the shared Home shell."
            iconName = "search"
        }
        HomeDestinationSaved -> {
            title = "Saved"
            subtitle = "Saved messages are opening from the shared Home shell."
            iconName = "bookmark"
        }
        else -> {
            title = "Activity"
            subtitle = "Activity is opening from the shared Home shell."
            iconName = "bell"
        }
    }
    AppShellPlaceholderContent(
        title = title,
        subtitle = subtitle,
        iconName = iconName
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

private fun parseSharedShellRealtimeEvent(payload: String): Result<RealtimeEvent> =
    runCatching {
        val json = JSONObject(payload)
        when (json.optString("type")) {
            "message_new" -> MessageNew(json.optJSONObject("message").toSharedMessage())
            "message_updated" -> MessageUpdated(json.optJSONObject("message").toSharedMessage())
            "sync_resume" ->
                SyncResumeResponse(
                    messages =
                        buildList {
                            val messages = json.optJSONArray("messages")
                            if (messages != null) {
                                for (index in 0 until messages.length()) {
                                    add(messages.optJSONObject(index).toSharedMessage())
                                }
                            }
                        },
                    currentSeq = json.optLong("currentSeq", 0L),
                    hasMore = json.optBoolean("hasMore", false)
                )
            "thread_updated" ->
                ThreadUpdated(
                    parentMessageId = MessageId(json.optString("parentMessageId")),
                    threadChannelId = ChannelId(json.optString("threadChannelId")),
                    replyCount = json.optInt("replyCount", 0),
                    lastReplyAt = json.optString("lastReplyAt").takeIf { it.isNotBlank() },
                    participantIds =
                        buildList {
                            val ids = json.optJSONArray("participantIds")
                            if (ids != null) {
                                for (index in 0 until ids.length()) {
                                    ids.optString(index)?.takeIf { it.isNotBlank() }?.let { add(MemberId(it)) }
                                }
                            }
                        }
                )
            "member_presence" ->
                MemberPresenceUpdated(
                    memberId = MemberId(json.optString("memberId")),
                    presence = json.optString("presence").toRealtimePresence(),
                    kind = json.optString("kind").toSharedMemberKind(),
                    agentStatus = AgentStatus.fromWireValue(json.optString("agentStatus")),
                    statusLabel = json.optString("statusLabel").takeIf { it.isNotBlank() }
                )
            "direct_message_created" -> DirectMessageCreated(ChannelId(json.optString("channelId")))
            "heartbeat" -> Heartbeat(json.optLong("seq", 0L))
            else -> error("unsupported realtime event ${json.optString("type")}")
        }
    }

private fun JSONObject?.toSharedMessage(): Message {
    val json = this ?: JSONObject()
    return Message(
        id = MessageId(json.optString("id")),
        threadId = ThreadId(json.optString("threadId")),
        channelId = json.optString("channelId").takeIf { it.isNotBlank() }?.let(::ChannelId),
        threadChannelId = json.optString("threadChannelId").takeIf { it.isNotBlank() }?.let(::ChannelId),
        seq = json.optLong("seq", 0L).takeIf { it > 0L },
        author = json.optJSONObject("author").toSharedMemberProfile(),
        timestampLabel = json.optString("timestampLabel"),
        body = json.optString("body"),
        messageType =
            when (json.optString("messageType").lowercase()) {
                "system" -> MessageType.System
                else -> MessageType.Chat
            },
        systemEventType = json.optString("systemEventType").takeIf { it.isNotBlank() },
        replyCount = json.optInt("replyCount", 0),
        parentMessageId = json.optString("parentMessageId").takeIf { it.isNotBlank() }?.let(::MessageId)
    )
}

private fun JSONObject?.toSharedMemberProfile(): MemberProfile {
    val json = this ?: JSONObject()
    val displayName = json.optString("displayName").takeIf { it.isNotBlank() } ?: "Unknown"
    return MemberProfile(
        id = MemberId(json.optString("id")),
        displayName = displayName,
        handle = json.optString("handle").takeIf { it.isNotBlank() } ?: displayName,
        kind = json.optString("kind").toSharedMemberKind() ?: MemberKind.Human,
        avatarUrl = json.optString("avatarUrl").takeIf { it.isNotBlank() },
        avatarLabel = json.optString("avatarLabel").takeIf { it.isNotBlank() } ?: displayName.take(1).uppercase(),
        presence = json.optString("presence").toRealtimePresence(),
        agentStatus = AgentStatus.fromWireValue(json.optString("agentStatus"))
    )
}

private fun String.toSharedMemberKind(): MemberKind? =
    when (trim().lowercase()) {
        "agent" -> MemberKind.Agent
        "human", "user" -> MemberKind.Human
        "system" -> MemberKind.System
        else -> null
    }

private fun String.toRealtimePresence(): Presence =
    when (trim().lowercase()) {
        "active", "online" -> Presence.Active
        "away", "idle" -> Presence.Away
        else -> Presence.Offline
    }

@Composable
private fun AppShellRestoreSplashContent() {
    val restoreSpacing = 40.dp
    Column(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = com.tencent.kuikly.compose.ui.Alignment.CenterHorizontally
    ) {
        AppShellRestoreLogoBadge("SLOCK")
        Spacer(Modifier.height(restoreSpacing))
        Text(
            text = "WHERE HUMANS AND AI AGENTS COLLABORATE",
            style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AppShellRestoreLogoBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(214.dp).height(68.dp).padding(end = 4.dp, bottom = 4.dp).rotate(-2f)) {
        Box(Modifier.fillMaxSize().offset(x = 4f, y = 4f).background(SlockColor.ShadowBlack))
        Box(
            modifier = Modifier.fillMaxSize().background(SlockColor.TextBlack).border(SlockStroke.Default, SlockColor.BorderBlack).padding(horizontal = 22.dp, vertical = 10.dp),
            contentAlignment = com.tencent.kuikly.compose.ui.Alignment.Center
        ) {
            Text(
                text = text,
                style = SlockType.PageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp, color = SlockColor.BrandYellow),
                color = SlockColor.BrandYellow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

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
    var selectedTab by remember { mutableStateOf("tasks") }
    val smokeItems = bottomTabBarSmokeItems.map { item -> item.copy(selected = item.key == selectedTab) }
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
                smokeItems.forEach { item ->
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
        SlockBottomTabBar(items = smokeItems, onSelect = { selectedTab = it }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Floating Dock",
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        SlockFloatingDockTabBar(items = smokeItems, onSelect = { selectedTab = it }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Navigation Rail",
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            SlockNavigationRailTabBar(items = smokeItems, onSelect = { selectedTab = it })
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
            items = smokeItems,
            onSelect = { selectedTab = it },
            modifier = Modifier.fillMaxWidth(),
            bottomSafeArea = bottomSafeArea
        )
    }
}

@Composable
private fun BottomTabBarVisualContent() {
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Spacer(modifier = Modifier.height(72.dp))
        SlockBottomTabBar(
            items = appShellBottomTabs("home"),
            onSelect = {},
            modifier = Modifier.width(342.dp)
        )
    }
}

@Composable
private fun SettingsRowVisualContent() {
    Column(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceCream)
                .padding(top = 48.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        SlockVerticalTabBar(
            items = settingsVisualItems,
            showSelection = true,
            onSelect = {}
        )
    }
}

@Composable
private fun SettingsPageVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(top = 48.dp)
    ) {
        SettingsDetailShell(
            title = "Account",
            subtitle = "Profile, email, password, connected accounts",
            iconName = "user",
            onBack = {}
        ) {
            SettingsAccountContent(
                payload =
                    settingsAccountSmokePayload.copy(
                        instanceId = "visual-settings-account-page",
                        profile =
                            settingsAccountSmokePayload.profile.copy(
                                email = "artin@slock.test",
                                verified = true
                            ),
                        connectedAccounts = emptyList()
                    ),
                applyTopSafeArea = false,
                onEvent = {}
            )
        }
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
