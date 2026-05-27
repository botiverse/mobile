package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.data.BridgeAnnouncementsRemindersApi
import ai.slock.compose.data.BridgeAuthApi
import ai.slock.compose.data.BridgeHomeApi
import ai.slock.compose.data.BridgeMembersApi
import ai.slock.compose.data.BridgeSettingsPlatformCapabilities
import ai.slock.compose.data.BridgeTasksApi
import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.KeyValueChatLayoutPreferenceStore
import ai.slock.compose.data.KeyValueLocalePreferenceStore
import ai.slock.compose.data.KuiklyAuthSessionStore
import ai.slock.compose.data.KuiklyNotificationPreferenceEvents
import ai.slock.compose.data.KuiklyRelationalStore
import ai.slock.compose.data.MOBILE_ACTION_LANGUAGE
import ai.slock.compose.data.MOBILE_ACTION_MESSAGE_ALIGNMENT
import ai.slock.compose.data.MOBILE_ACTION_NOTIFICATION_PREFERENCE
import ai.slock.compose.data.MOBILE_ACTION_PERMISSION
import ai.slock.compose.data.MOBILE_ACTION_SERVER_MUTE
import ai.slock.compose.data.MOBILE_ACTION_SETTINGS
import ai.slock.compose.data.MOBILE_ACTION_TEST_NOTIFICATION
import ai.slock.compose.data.NotificationPreferenceSnapshot
import ai.slock.compose.data.PersistentChatLayoutPreferenceRepository
import ai.slock.compose.data.PersistentLocaleRepository
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
import ai.slock.compose.data.HomeSnapshot
import ai.slock.compose.data.SharedHomeIntent
import ai.slock.compose.data.SharedHomeState
import ai.slock.compose.data.SharedHomeTopAction
import ai.slock.compose.data.SharedHomeViewModel
import ai.slock.compose.data.SharedHomeNotification
import ai.slock.compose.data.SharedHomeNotificationKind
import ai.slock.compose.data.SharedSettingsAboutSnapshot
import ai.slock.compose.data.SharedSettingsDataSource
import ai.slock.compose.data.SharedSettingsDestination
import ai.slock.compose.data.SharedSettingsDiagnosticsEntry
import ai.slock.compose.data.SharedSettingsDiagnosticsSnapshot
import ai.slock.compose.data.SharedSettingsEffect
import ai.slock.compose.data.SharedSettingsIntent
import ai.slock.compose.data.SharedSettingsState
import ai.slock.compose.data.SharedSettingsStorageRow
import ai.slock.compose.data.SharedSettingsStorageSnapshot
import ai.slock.compose.data.SharedSettingsViewModel
import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.isRefreshing
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthUser
import ai.slock.compose.data.realtime.RealtimeEventMapper
import ai.slock.compose.data.parseSlockHome
import ai.slock.compose.data.BridgeThreadApi
import ai.slock.compose.data.CreateChannelRequest
import ai.slock.compose.data.DataRequestPriority
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.KuiklySlockNetworkClient
import ai.slock.compose.data.RealChannelRepository
import ai.slock.compose.data.RealMemberRepository
import ai.slock.compose.data.RealSavedRepository
import ai.slock.compose.data.RealSearchRepository
import ai.slock.compose.data.RealSettingsRepository
import ai.slock.compose.data.SearchIsoBounds
import ai.slock.compose.data.SearchSort
import ai.slock.compose.data.SearchSenderFilterItem
import ai.slock.compose.data.SearchTimeRange
import ai.slock.compose.data.SharedHomeSavedPanelState
import ai.slock.compose.data.SharedHomeSearchPanelState
import ai.slock.compose.data.SharedHomeSearchSavedIntent
import ai.slock.compose.data.SharedHomeSearchSavedViewModel
import ai.slock.compose.data.AccountProfileUpdateRequest
import ai.slock.compose.data.AccountSettingsSnapshot
import ai.slock.compose.data.ConnectedAccountStatus
import ai.slock.compose.data.InboxItem
import ai.slock.compose.data.kuiklyNotificationPreferenceRepository
import ai.slock.compose.data.kuiklySharedPreferenceStore
import ai.slock.compose.data.savedMessageIdOrNull
import ai.slock.compose.data.SettingsComputerAgentSnapshot
import ai.slock.compose.data.SettingsBillingSnapshot
import ai.slock.compose.data.SettingsComputerDetailSnapshot
import ai.slock.compose.data.SettingsComputerSnapshot
import ai.slock.compose.data.SettingsComputerWorkspaceSnapshot
import ai.slock.compose.data.SettingsComputersSnapshot
import ai.slock.compose.data.SettingsMobileSelectionSnapshot
import ai.slock.compose.data.SettingsMobileSnapshot
import ai.slock.compose.data.SettingsServerSnapshot
import ai.slock.compose.network.SlockNetworkModule
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.InboxItemKind
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
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.ThreadUpdated
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.slockEpochMillisUtcIso
import ai.slock.compose.model.slockStartOfDayUtcIso
import ai.slock.compose.model.threadIdentity
import ai.slock.compose.model.topLevelThreadId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.navigation.NavHost
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.navigation.composable
import com.tencent.kuikly.compose.material3.navigation.rememberNavController
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.text.input.ImeAction
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.BackPressCallback
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
private const val HOME_SEARCH_VISUAL_PAGE = "compose_home_search_visual"
private const val HOME_SEARCH_CHANNEL_DROPDOWN_VISUAL_PAGE = "compose_home_search_channel_dropdown_visual"
private const val HOME_CREATE_CHANNEL_DIALOG_VISUAL_PAGE = "compose_home_create_channel_dialog_visual"
const val SLOCK_APP_SHELL_PAGE = "compose_slock_app_shell"

private const val AppShellRouteMain = "main"
private const val AppShellRouteThread = "thread"
private const val HomeDestinationSearch = "search"
private const val HomeDestinationActivity = "activity"
private const val HomeDestinationSaved = "saved"
private const val SettingsDestinationHome = "home"
private const val SettingsDestinationAccount = "account"
private const val SettingsDestinationComputers = "computers"
private const val SettingsDestinationComputerDetail = "computerDetail"
private const val SettingsDestinationServer = "server"
private const val SettingsDestinationBilling = "billing"
private const val SettingsDestinationMobile = "mobile"
private const val SettingsDestinationStorage = "storage"
private const val SettingsDestinationAbout = "about"
private const val SettingsDestinationDiagnostics = "diagnostics"
private const val SettingsDestinationRouter = "router"
private const val AppShellRealtimeEvent = "slockRealtimeEvent"
private val SearchControlsHorizontalPadding = 16.dp
private val SearchMessagesHorizontalPadding = 16.dp
private val SearchControlShadowOffset = 2.dp
private val SearchPopoverBorderColor = SlockColor.EmptyStateGray
private val SearchPopoverShadowColor = Color.Transparent
private val SearchMutedBorderColor = SlockColor.TextBlack.copy(alpha = 0.28f)
private val SearchLightRowBorderColor = SlockColor.TextBlack.copy(alpha = 0.24f)
private val SearchMutedTextColor = SlockColor.TextBlack.copy(alpha = 0.62f)

@Page(SETTINGS_HOME_PAGE, supportInLocal = true)
internal class ComposeSettingsHomePage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsHomeScreen() }
    }
}

@Composable
private fun SharedHomeSearchEntityLeadingAvatar(result: SearchEntityResult) {
    when (result.type) {
        SearchEntityType.Channel ->
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .background(SlockColor.BrandYellow)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon("hash", color = "#FF000000", modifier = Modifier.size(15.dp))
            }

        else ->
            SlockAvatarWithStatus(
                statusColor = result.presence.searchStatusColor(),
                avatarSize = 32.dp,
                statusSize = 8.dp
            ) {
                SlockAvatar(
                    avatarWireValue = result.avatarWireValue ?: "random:${result.key}",
                    label = result.title.removePrefix("@").removePrefix("#")
                )
            }
    }
}

@Composable
private fun SharedHomeSearchSenderMeta(
    sender: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockAvatar(
            avatarWireValue = "random:${sender.searchAvatarSeed()}",
            label = sender,
            avatarSize = if (compact) 12.dp else 16.dp
        )
        Text(
            text = sender,
            style = SlockType.Caption.copy(fontSize = if (compact) 10.sp else 11.sp, lineHeight = if (compact) 12.sp else 13.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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

@Page(HOME_SEARCH_VISUAL_PAGE, supportInLocal = true)
internal class ComposeHomeSearchVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchVisualContent() }
    }
}

@Page(HOME_SEARCH_CHANNEL_DROPDOWN_VISUAL_PAGE, supportInLocal = true)
internal class ComposeHomeSearchChannelDropdownVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchVisualContent(initialOpenFilterId = "channel") }
    }
}

@Page(HOME_CREATE_CHANNEL_DIALOG_VISUAL_PAGE, supportInLocal = true)
internal class ComposeHomeCreateChannelDialogVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeCreateChannelDialogVisualContent() }
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
    var selectedSettingsComputerId by remember { mutableStateOf<String?>(null) }
    var selectedHomeDestination by remember { mutableStateOf<String?>(null) }
    var selectedHomeConversationId by remember { mutableStateOf<String?>(null) }
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
                announcementsApi = BridgeAnnouncementsRemindersApi(bridge, authStore),
                localWriteThrough = localWriteThrough,
                parseHome = { payload, session ->
                    parseSlockHome(payload, session) { message -> bridge.log(message) }
                }
            )
        }
    val membersApi = remember { BridgeMembersApi(bridge, authStore) }
    val appShellNetworkClient =
        remember {
            KuiklySlockNetworkClient(
                sessionStore = authStore,
                networkModule = pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME),
                warningLogger = { message -> bridge.log("kuikly_network $message") }
            )
        }
    val memberRepository =
        remember {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                logger = { message -> bridge.log("app_shell_member_repository $message") }
            )
        }
    val threadDataSource =
        remember {
            SharedThreadDataSource(
                networkApi = BridgeThreadApi(bridge, authStore),
                sessionStore = authStore,
                localWriteThrough = localWriteThrough,
                memberRepository = memberRepository
            )
        }
    val channelRepository =
        remember {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                warningLogger = { message -> bridge.log("shared_channel_repository $message") }
            )
        }
    val savedRepository =
        remember {
            RealSavedRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                warningLogger = { message -> bridge.log("shared_saved_repository $message") }
            )
        }
    val searchRepository =
        remember {
            RealSearchRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                warningLogger = { message -> bridge.log("shared_search_repository $message") }
            )
        }
    val tasksDataSource =
        remember {
            SharedTasksDataSource(
                api = BridgeTasksApi(bridge, authStore)
            )
        }
    val settingsCapabilities = remember { BridgeSettingsPlatformCapabilities(bridge) }
    val sharedPreferenceStore = remember(pager) { kuiklySharedPreferenceStore(pager) }
    val localeRepository =
        remember(sharedPreferenceStore) {
            PersistentLocaleRepository(KeyValueLocalePreferenceStore(sharedPreferenceStore))
        }
    val chatLayoutPreferenceRepository =
        remember(sharedPreferenceStore) {
            PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(sharedPreferenceStore))
        }
    val notificationPreferenceEvents = remember(pager) { KuiklyNotificationPreferenceEvents(pager) }
    val notificationPreferenceRepository =
        remember(pager, notificationPreferenceEvents) {
            kuiklyNotificationPreferenceRepository(pager, notificationPreferenceEvents)
        }
    val notificationPreferenceScope = rememberCoroutineScope()
    val appShellScope = rememberCoroutineScope()
    var notificationPreferenceSnapshot by remember {
        mutableStateOf(NotificationPreferenceSnapshot(enabled = false))
    }
    val settingsDataSource =
        remember {
            SharedSettingsDataSource(
                sessionStore = authStore,
                settingsRepository =
                    RealSettingsRepository(
                        sessionStore = authStore,
                        networkClient = appShellNetworkClient,
                        logger = { message -> bridge.log("shared_settings_repository $message") },
                        warningLogger = { message -> bridge.log("shared_settings_repository_warning $message") }
                    ),
                notificationPreferenceRepository = notificationPreferenceRepository,
                localeRepository = localeRepository,
                chatLayoutPreferenceRepository = chatLayoutPreferenceRepository,
                platformCapabilities = settingsCapabilities
            )
        }
    var activeThreadPayload by remember { mutableStateOf<KuiklyThreadScreenPayload?>(null) }
    var threadBackStack by remember { mutableStateOf<List<KuiklyThreadScreenPayload>>(emptyList()) }
    var createChannelDialogOpen by remember { mutableStateOf(false) }
    var createChannelSubmitting by remember { mutableStateOf(false) }
    var createChannelError by remember { mutableStateOf<String?>(null) }
    var createChannelMembersLoading by remember { mutableStateOf(false) }
    var createChannelMembers by remember { mutableStateOf<List<MemberProfile>>(emptyList()) }
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
    val threadTimeContext = remember(pager.pageData.params.toString()) {
        pager.pageData.params.threadTimeContext()
    }
    val homeSearchSavedViewModel: SharedHomeSearchSavedViewModel =
        viewModel(key = "shared-home-search-saved") {
            SharedHomeSearchSavedViewModel(
                authStore = authStore,
                savedRepository = savedRepository,
                searchRepository = searchRepository,
                memberRepository = memberRepository,
                timeRangeBoundsProvider = { range -> pager.pageData.params.threadTimeContext().toSearchIsoBounds(range) }
            )
        }
    val homeSearchSavedState by homeSearchSavedViewModel.state.collectAsState()
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
    val realtimeEventMapper = remember { RealtimeEventMapper() }
    var realtimeLastSeq by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
    }
    LaunchedEffect(notificationPreferenceRepository) {
        notificationPreferenceSnapshot =
            notificationPreferenceRepository.getNotificationPreference()
    }
    DisposableEffect(notificationPreferenceEvents) {
        val callbackRef =
            notificationPreferenceEvents.addPreferenceListener { snapshot ->
                notificationPreferenceSnapshot = snapshot
            }
        onDispose {
            notificationPreferenceEvents.removePreferenceListener(callbackRef)
        }
    }
    LaunchedEffect(authStore) {
        authStore.sessionInvalidations.collect {
            authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
            selectedTab = "home"
            selectedSettingsDestination = SettingsDestinationHome
            selectedSettingsComputerId = null
            selectedHomeConversationId = null
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
                    selectedSettingsDestination = SettingsDestinationHome
                    selectedSettingsComputerId = null
                    selectedHomeDestination = null
                    selectedHomeConversationId = null
                    activeThreadPayload = null
                    threadBackStack = emptyList()
                },
                onSignOut = {
                    authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
                    selectedTab = "home"
                    selectedSettingsDestination = SettingsDestinationHome
                    selectedSettingsComputerId = null
                    selectedHomeDestination = null
                    selectedHomeConversationId = null
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
    val readySession = (authGateState as? SharedAuthGateState.Ready)?.session

    DisposableEffect(readySession?.currentServerId, readySession?.accessToken) {
        val serverId = readySession?.currentServerId
        val accessToken = readySession?.accessToken
        if (!serverId.isNullOrBlank() && !accessToken.isNullOrBlank()) {
            bridge.realtimeConnect(
                serverId = serverId,
                accessToken = accessToken,
                lastSeq = realtimeLastSeq
            )
        }
        onDispose {
            if (!serverId.isNullOrBlank()) {
                bridge.realtimeDisconnect("session_changed")
            }
        }
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

    fun openThreadPayload(payload: KuiklyThreadScreenPayload, pushCurrent: Boolean = true) {
        if (pushCurrent) {
            activeThreadPayload?.let { currentPayload ->
                threadBackStack = threadBackStack + currentPayload
            }
        }
        activeThreadPayload = payload
        if (navController.currentRoute != AppShellRouteThread) {
            navController.navigate(AppShellRouteThread)
        }
    }

    fun popMainRoute(): Boolean {
        if (createChannelDialogOpen) {
            createChannelDialogOpen = false
            createChannelError = null
            return true
        }
        if (homeState.serverSwitcherExpanded) {
            homeViewModel.dispatch(SharedHomeIntent.DismissServerSwitcher)
            return true
        }
        if (selectedTab == "settings" && selectedSettingsDestination != SettingsDestinationHome) {
            if (selectedSettingsDestination == SettingsDestinationComputerDetail) {
                selectedSettingsDestination = SettingsDestinationComputers
            } else {
                selectedSettingsDestination = SettingsDestinationHome
            }
            selectedSettingsComputerId = null
            return true
        }
        if (selectedTab == "home" && selectedHomeDestination != null) {
            selectedHomeDestination = null
            return true
        }
        if (selectedTab != "home") {
            selectedTab = "home"
            selectedSettingsDestination = SettingsDestinationHome
            selectedSettingsComputerId = null
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

    fun loadCreateChannelMembers() {
        createChannelMembersLoading = true
        appShellScope.launch {
            try {
                val session = authStore.readSession()
                val serverId = session?.currentServerId
                if (serverId.isNullOrBlank()) {
                    createChannelMembers = emptyList()
                    createChannelMembersLoading = false
                    return@launch
                }
                createChannelMembers =
                    when (val result = memberRepository.getMembers(DataRequestPriority.Visible, ServerId(serverId))) {
                        is DataResult.Content -> result.data.members
                        is DataResult.Empty,
                        is DataResult.Error,
                        is DataResult.NoPermission -> emptyList()
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                createChannelMembers = emptyList()
            } finally {
                createChannelMembersLoading = false
            }
        }
    }

    fun openCreateChannelDialog() {
        createChannelDialogOpen = true
        createChannelError = null
        createChannelMembers = emptyList()
        loadCreateChannelMembers()
    }

    fun submitCreateChannel(
        name: String,
        description: String?,
        agentMemberIds: List<String>,
        userMemberIds: List<String>
    ) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || createChannelSubmitting) {
            return
        }
        createChannelSubmitting = true
        createChannelError = null
        appShellScope.launch {
            try {
                when (
                    val result =
                        channelRepository.createChannel(
                            CreateChannelRequest(
                                name = trimmedName,
                                description = description?.trim()?.takeIf { it.isNotBlank() },
                                agentMemberIds = agentMemberIds,
                                userMemberIds = userMemberIds
                            )
                        )
                ) {
                    is DataResult.Content -> {
                        val channel = result.data.channel
                        selectedTab = "home"
                        selectedHomeDestination = null
                        selectedHomeConversationId = channel.threadId.value
                        homeViewModel.dispatch(SharedHomeIntent.ChannelCreated(channel))
                        homeViewModel.dispatch(SharedHomeIntent.Refresh)
                        createChannelDialogOpen = false
                        createChannelError = null
                        bridge.toast("Channel created")
                    }
                    is DataResult.Empty -> {
                        createChannelError = "Channel was not created."
                    }
                    is DataResult.Error -> {
                        createChannelError = result.error.code
                    }
                    is DataResult.NoPermission -> {
                        createChannelError = result.blocker.code
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                createChannelError = "channel_create_failed"
            } finally {
                createChannelSubmitting = false
            }
        }
    }

    LaunchedEffect(activeThreadPayload?.sourceThreadId) {
        activeThreadPayload?.sourceThreadId
            ?.takeIf { it.isNotBlank() }
            ?.let { bridge.realtimeJoinChannel(it) }
    }

    DisposableEffect(pager, homeViewModel, realtimeEventMapper) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != AppShellRealtimeEvent) return
                    val serverId = eventData.optString("serverId").takeIf { it.isNotBlank() } ?: return
                    val event = decodeSharedShellRealtimeEvent(eventData, realtimeEventMapper, bridge::log) ?: return
                    realtimeLastSeq = realtimeLastSeq.updateFrom(event)
                    if (event is DirectMessageCreated) {
                        bridge.realtimeJoinChannel(event.channelId.value)
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
                    onReplaceThreadPayload = { payload -> openThreadPayload(payload, pushCurrent = false) },
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
            Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (selectedTab) {
                        "settings" -> {
                            SettingsAppShellContent(
                                session = (authGateState as? SharedAuthGateState.Ready)?.session,
                                authDataSource = authDataSource,
                                settingsCapabilities = settingsCapabilities,
                                settingsState = settingsState,
                                notificationPreferenceSnapshot = notificationPreferenceSnapshot,
                                onToggleNotificationPreference = {
                                    notificationPreferenceScope.launch {
                                        notificationPreferenceSnapshot =
                                            notificationPreferenceRepository.setNotificationEnabled(!notificationPreferenceSnapshot.enabled)
                                    }
                                },
                                onSettingsIntent = settingsViewModel::dispatch,
                                membersApi = membersApi,
                                selectedDestination = selectedSettingsDestination,
                                selectedComputerId = selectedSettingsComputerId,
                                onDestinationChange = {
                                    selectedSettingsDestination = it
                                    if (it != SettingsDestinationComputerDetail) {
                                        selectedSettingsComputerId = null
                                    }
                                },
                                onOpenComputer = { computerId ->
                                    selectedSettingsComputerId = computerId
                                    selectedSettingsDestination = SettingsDestinationComputerDetail
                                },
                                onOpenRouter = { jumpPage(pager, "router") },
                                onSettingsAction = { action, itemId ->
                                    bridge.settingsEvent("shared-shell-settings", action, itemId)
                                },
                                onLogout = {
                                    authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
                                    selectedTab = "home"
                                    selectedSettingsDestination = SettingsDestinationHome
                                    selectedSettingsComputerId = null
                                    selectedHomeDestination = null
                                    selectedHomeConversationId = null
                                    activeThreadPayload = null
                                    threadBackStack = emptyList()
                                }
                            )
                        }
                        "tasks" -> SharedTasksScreen(
                            state = tasksState,
                            onIntent = tasksViewModel::dispatch,
                            onOpenTask = { task ->
                                appShellScope.launch {
                                    openAppShellTaskThread(
                                        task = task,
                                        dataSource = threadDataSource,
                                        timeContext = threadTimeContext,
                                        onOpenThreadPayload = { payload -> openThreadPayload(payload) },
                                        onReplaceThreadPayload = { payload -> openThreadPayload(payload, pushCurrent = false) },
                                        onFailure = { bridge.toast("Unable to open task") }
                                    )
                                }
                            }
                        )
                        "members" -> SharedMembersScreen(bridge)
                        else ->
                            if (selectedHomeDestination == null) {
                                SharedHomeScreen(
                                    bridge = bridge,
                                    state = homeState,
                                    onIntent = homeViewModel::dispatch,
                                    selectedConversationId = selectedHomeConversationId ?: homeState.selectedConversationId,
                                    onSelectedConversationCleared = {
                                        selectedHomeConversationId = null
                                    },
                                    onOpenSearch = {
                                        selectedHomeConversationId = null
                                        selectedHomeDestination = HomeDestinationSearch
                                    },
                                    onOpenActivity = {
                                        selectedHomeConversationId = null
                                        selectedHomeDestination = HomeDestinationActivity
                                    },
                                    onOpenSaved = {
                                        selectedHomeConversationId = null
                                        selectedHomeDestination = HomeDestinationSaved
                                    },
                                    onCreateChannel = { openCreateChannelDialog() },
                                    onOpenConversation = { conversation ->
                                        selectedHomeDestination = null
                                        selectedHomeConversationId = conversation.threadId.value
                                        activeThreadPayload = buildAppShellThreadPayload(conversation, threadTimeContext)
                                        threadBackStack = emptyList()
                                        navController.navigate(AppShellRouteThread)
                                    },
                                    bottomContentPadding = slockAppBottomTabBarContentInset(bottomSafeArea)
                                )
                            } else if (selectedHomeDestination == HomeDestinationActivity) {
                                SharedHomeActivityScreen(
                                    bridge = bridge,
                                    authStore = authStore,
                                    onOpenConversation = { conversation ->
                                        selectedHomeDestination = null
                                        selectedHomeConversationId = conversation.threadId.value
                                        activeThreadPayload = buildAppShellThreadPayload(conversation, threadTimeContext)
                                        threadBackStack = emptyList()
                                        navController.navigate(AppShellRouteThread)
                                    }
                                )
                            } else if (selectedHomeDestination == HomeDestinationSearch) {
                                SharedHomeSearchPanel(
                                    state = homeSearchSavedState.search,
                                    homeState = homeState,
                                    onIntent = homeSearchSavedViewModel::dispatch,
                                    onClose = {
                                        selectedHomeDestination = null
                                    },
                                    onOpenItem = { item ->
                                        selectedHomeDestination = null
                                        selectedHomeConversationId = item.toConversationSummary().threadId.value
                                        activeThreadPayload = buildAppShellThreadPayload(item.toConversationSummary(), threadTimeContext)
                                        threadBackStack = emptyList()
                                        navController.navigate(AppShellRouteThread)
                                    },
                                    onOpenEntity = { entity ->
                                        selectedHomeDestination = null
                                        val threadId = entity.threadId
                                        if (threadId != null) {
                                            selectedHomeConversationId = threadId
                                            entity.conversation?.let {
                                                activeThreadPayload = buildAppShellThreadPayload(it, threadTimeContext)
                                                threadBackStack = emptyList()
                                                navController.navigate(AppShellRouteThread)
                                            }
                                        } else {
                                            selectedTab = "members"
                                            selectedHomeConversationId = null
                                            activeThreadPayload = null
                                            threadBackStack = emptyList()
                                        }
                                    },
                                    bottomContentPadding = slockAppBottomTabBarContentInset(bottomSafeArea)
                                )
                            } else if (selectedHomeDestination == HomeDestinationSaved) {
                                SharedHomeSavedPanel(
                                    state = homeSearchSavedState.saved,
                                    onIntent = homeSearchSavedViewModel::dispatch,
                                    onOpenItem = { item ->
                                        selectedHomeDestination = null
                                        selectedHomeConversationId = item.toConversationSummary().threadId.value
                                        activeThreadPayload = buildAppShellThreadPayload(item.toConversationSummary(), threadTimeContext)
                                        threadBackStack = emptyList()
                                        navController.navigate(AppShellRouteThread)
                                    },
                                    bottomContentPadding = slockAppBottomTabBarContentInset(bottomSafeArea)
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
                            selectedSettingsComputerId = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    bottomSafeArea = bottomSafeArea
                )
                if (createChannelDialogOpen) {
                    SharedCreateChannelDialog(
                        submitting = createChannelSubmitting,
                        membersLoading = createChannelMembersLoading,
                        members = createChannelMembers,
                        errorMessage = createChannelError,
                        onDismiss = {
                            if (!createChannelSubmitting) {
                                createChannelDialogOpen = false
                                createChannelError = null
                            }
                        },
                        onSubmit = { name, description, agentMemberIds, userMemberIds ->
                            submitCreateChannel(name, description, agentMemberIds, userMemberIds)
                        }
                    )
                }
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
    notificationPreferenceSnapshot: NotificationPreferenceSnapshot,
    onToggleNotificationPreference: () -> Unit,
    onSettingsIntent: (SharedSettingsIntent) -> Unit,
    membersApi: BridgeMembersApi,
    selectedDestination: String,
    selectedComputerId: String?,
    onDestinationChange: (String) -> Unit,
    onOpenComputer: (String) -> Unit,
    onOpenRouter: () -> Unit,
    onSettingsAction: (String, String?) -> Unit,
    onLogout: () -> Unit
) {
    LaunchedEffect(selectedDestination, selectedComputerId) {
        if (selectedDestination == SettingsDestinationComputerDetail) {
            selectedComputerId?.takeIf { it.isNotBlank() }?.let { computerId ->
                onSettingsIntent(SharedSettingsIntent.OpenComputer(computerId))
            }
        } else {
            selectedDestination.toSharedSettingsDestination()?.let { destination ->
                onSettingsIntent(SharedSettingsIntent.OpenDestination(destination))
            }
        }
    }

    LaunchedEffect(settingsState.effect) {
        val effect = settingsState.effect ?: return@LaunchedEffect
        when (effect) {
            SharedSettingsEffect.Logout -> {
                runCatching { authDataSource.logout() }
                onLogout()
            }
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
                val ok = settingsCapabilities.sendFeedback()
                if (ok) {
                    onSettingsIntent(SharedSettingsIntent.Refresh)
                }
            }
            is SharedSettingsEffect.RunBuglyCrashMonitorAction -> {
                val ok = settingsCapabilities.buglyCrashMonitorAction(effect.action)
                if (ok) {
                    onSettingsIntent(SharedSettingsIntent.Refresh)
                }
            }
            SharedSettingsEffect.RequestNotificationPermission -> {
                val result = settingsCapabilities.requestNotificationPermission()
                onSettingsIntent(SharedSettingsIntent.CompleteSystemNotificationAction(MOBILE_ACTION_PERMISSION, result))
            }
            SharedSettingsEffect.OpenNotificationSettings -> {
                val result = settingsCapabilities.openNotificationSettings()
                onSettingsIntent(SharedSettingsIntent.CompleteSystemNotificationAction(MOBILE_ACTION_SETTINGS, result))
            }
            SharedSettingsEffect.SendTestNotification -> {
                val result = settingsCapabilities.sendTestNotification()
                onSettingsIntent(SharedSettingsIntent.CompleteSystemNotificationAction(MOBILE_ACTION_TEST_NOTIFICATION, result))
            }
            is SharedSettingsEffect.ClearStorage -> {
                val result = settingsCapabilities.clearStorage(effect.target)
                when (result) {
                    is DataResult.Content -> {
                        onSettingsIntent(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = true,
                                snapshot = result.data
                            )
                        )
                    }
                    is DataResult.Empty -> {
                        onSettingsIntent(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = false,
                                code = result.reason.name
                            )
                        )
                    }
                    is DataResult.Error -> {
                        onSettingsIntent(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = false,
                                code = result.error.code
                            )
                        )
                    }
                    is DataResult.NoPermission -> {
                        onSettingsIntent(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = false,
                                code = result.blocker.code
                            )
                        )
                    }
                }
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
        onBack = {
            if (selectedDestination == SettingsDestinationComputerDetail) {
                onDestinationChange(SettingsDestinationComputers)
            } else {
                onDestinationChange(SettingsDestinationHome)
            }
        }
    ) {
        when (selectedDestination) {
            SettingsDestinationAccount ->
                SettingsAccountContent(settingsState.account.toAccountPayload(session), applyTopSafeArea = false) { action ->
                    when (action) {
                        "logout" -> onSettingsIntent(SharedSettingsIntent.Logout)
                        "saveProfile" -> {
                            val account = (settingsState.account as? ScreenLoadState.Content<AccountSettingsSnapshot>)?.data
                            if (account != null) {
                                onSettingsIntent(
                                    SharedSettingsIntent.UpdateProfile(
                                        AccountProfileUpdateRequest(
                                            displayName = account.profile.displayName,
                                            handle = account.profile.handle,
                                            email = account.profile.email
                                        )
                                    )
                                )
                            }
                        }
                        else -> onSettingsAction(action, null)
                    }
                }
            SettingsDestinationComputers ->
                SettingsComputersContent(settingsState.computers.toComputersPayload(settingsState.isRefreshing(SharedSettingsDestination.Computers))) { action, itemId ->
                    when (action) {
                        "openComputer" -> itemId?.takeIf { it.isNotBlank() }?.let(onOpenComputer)
                        "addComputer" -> onSettingsAction(action, itemId)
                        else -> onSettingsAction(action, itemId)
                    }
                }
            SettingsDestinationComputerDetail ->
                SettingsComputerDetailContent(settingsState.computerDetail.toComputerDetailPayload(selectedComputerId)) { action, itemId ->
                    when (action) {
                        "retryWorkspaces", "retryAgents" ->
                            selectedComputerId?.takeIf { it.isNotBlank() }?.let { onSettingsIntent(SharedSettingsIntent.OpenComputer(it)) }
                        "openAgent" -> onSettingsAction(action, itemId)
                        else -> onSettingsAction(action, itemId)
                    }
                }
            SettingsDestinationStorage ->
                SettingsStorageContent(
                    settingsState.storage.toStoragePayload(
                        actionState = settingsState.actionState,
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Storage)
                    )
                ) { action, target ->
                    when (action) {
                        "refreshStorage" -> onSettingsIntent(SharedSettingsIntent.RefreshStorage)
                        "clearStorage" -> target?.takeIf { it.isNotBlank() }?.let { onSettingsIntent(SharedSettingsIntent.ClearStorage(it)) }
                        else -> onSettingsAction(action, target)
                    }
                }
            SettingsDestinationBilling ->
                SettingsBillingContent(settingsState.billing.toBillingPayload(session)) { _, _ -> }
            SettingsDestinationServer ->
                SharedSettingsSectionContent(
                    settingsState.server.toServerPayload(
                        session = session,
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Server)
                    ),
                    onAction = onSettingsAction
                )
            SettingsDestinationMobile ->
                SharedSettingsSectionContent(
                    settingsState.mobile.toMobilePayload(
                        actionState = settingsState.actionState,
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Mobile)
                    )
                ) { action, itemId ->
                    when (action) {
                        MOBILE_ACTION_NOTIFICATION_PREFERENCE ->
                            onSettingsIntent(SharedSettingsIntent.SetNotificationPreference(itemId == "true"))
                        MOBILE_ACTION_SERVER_MUTE ->
                            onSettingsIntent(SharedSettingsIntent.SetServerMute(itemId == "true"))
                        MOBILE_ACTION_PERMISSION ->
                            onSettingsIntent(SharedSettingsIntent.RequestNotificationPermission)
                        MOBILE_ACTION_SETTINGS ->
                            onSettingsIntent(SharedSettingsIntent.OpenNotificationSettings)
                        MOBILE_ACTION_TEST_NOTIFICATION ->
                            onSettingsIntent(SharedSettingsIntent.SendTestNotification)
                        MOBILE_ACTION_LANGUAGE ->
                            itemId?.takeIf { it.isNotBlank() }?.let { onSettingsIntent(SharedSettingsIntent.SetLocale(it)) }
                        MOBILE_ACTION_MESSAGE_ALIGNMENT ->
                            itemId?.takeIf { it.isNotBlank() }?.let {
                                onSettingsIntent(SharedSettingsIntent.SetMessageAlignment(it))
                            }
                        else -> onSettingsAction(action, itemId)
                    }
                }
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
                    onSendFeedback = { onSettingsIntent(SharedSettingsIntent.SendFeedback) },
                    onBuglyCrashAction = { action -> onSettingsIntent(SharedSettingsIntent.RunBuglyCrashMonitorAction(action)) }
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
    onSendFeedback: () -> Unit,
    onBuglyCrashAction: (String) -> Unit
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
            "buglyPostTestError",
            "buglyTestJsCrash",
            "buglyTestCppCrash",
            "buglyTestFreeze" -> onBuglyCrashAction(action)
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
        SlockTopPageHeader(
            title = title,
            subtitle = subtitle,
            iconName = iconName,
            leadingContent = {
                SettingsDetailBackButton(onBack)
            }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun SettingsDetailBackButton(onBack: () -> Unit) {
    Box(modifier = Modifier.size(30.dp)) {
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .offset(x = 2.dp, y = 2.dp)
                    .background(SlockColor.ShadowBlack)
        )
        Button(
            onClick = onBack,
            modifier =
                Modifier
                    .size(28.dp)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
        ) {
            SlockLucideIcon("arrow-left", modifier = Modifier.size(16.dp))
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
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationRouter,
                    title = "Developer Router",
                    subtitle = "Open Kuikly route launcher and icon compare",
                    iconName = "layout-list",
                    selected = selectedDestination == SettingsDestinationRouter
                )
            )
    )

private fun ScreenLoadState<AccountSettingsSnapshot>.toAccountPayload(
    session: StoredAuthSession?
): KuiklySettingsAccountPayload {
    val account = (this as? ScreenLoadState.Content<AccountSettingsSnapshot>)?.data
    return account?.toAccountPayload() ?: appShellAccountPayload(session)
}

private fun AccountSettingsSnapshot.toAccountPayload(): KuiklySettingsAccountPayload =
    KuiklySettingsAccountPayload(
        instanceId = "shared-shell-settings-account",
        profile =
            KuiklySettingsAccountProfile(
                displayName = profile.displayName,
                handle = profile.handle,
                email = profile.email,
                avatarLabel = profile.avatarLabel,
                verified = profile.verified,
                roleLabel = profile.roleLabel.orEmpty()
            ),
        connectedAccounts =
            connectedAccounts.map {
                KuiklySettingsConnectedAccount(
                    provider = it.provider,
                    identifier = it.identifier,
                    status =
                        when (it.status) {
                            ConnectedAccountStatus.Connected -> "connected"
                            ConnectedAccountStatus.NotConnected -> "notConnected"
                            ConnectedAccountStatus.Loading -> "loading"
                            ConnectedAccountStatus.Failed -> "failed"
                        }
                )
            }
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

private fun ScreenLoadState<SettingsComputersSnapshot>.toComputersPayload(
    refreshing: Boolean
): KuiklySettingsComputersPayload =
    when (this) {
        is ScreenLoadState.Content ->
            data.toComputersPayload(refreshing)
        is ScreenLoadState.Error ->
            KuiklySettingsComputersPayload(
                instanceId = "shared-shell-settings-computers",
                status = "error",
                emptyTitle = "Unable to load computers",
                emptyDetail = error.code
            )
        is ScreenLoadState.NoPermission ->
            KuiklySettingsComputersPayload(
                instanceId = "shared-shell-settings-computers",
                status = "error",
                emptyTitle = "No access to computers",
                emptyDetail = blocker.code
            )
        is ScreenLoadState.Empty ->
            KuiklySettingsComputersPayload(
                instanceId = "shared-shell-settings-computers",
                status = "empty",
                emptyTitle = "No computers yet",
                emptyDetail = "Connect a desktop daemon to see it here."
            )
        ScreenLoadState.Loading ->
            KuiklySettingsComputersPayload(
                instanceId = "shared-shell-settings-computers",
                status = "loading",
                emptyTitle = "Loading computers",
                emptyDetail = "Fetching connected machines from this server."
            )
    }

private fun SettingsComputersSnapshot.toComputersPayload(refreshing: Boolean): KuiklySettingsComputersPayload =
    KuiklySettingsComputersPayload(
        instanceId = "shared-shell-settings-computers",
        status = if (refreshing) "refreshing" else "content",
        computers = computers.map { it.toComputerItem() },
        canAddComputer = canAddComputer,
        addComputerUnavailableReason = addComputerUnavailableReason,
        latestDaemonVersion = latestDaemonVersion
    )

private fun ScreenLoadState<SettingsComputerDetailSnapshot>.toComputerDetailPayload(
    selectedComputerId: String?
): KuiklySettingsComputerDetailPayload =
    when (this) {
        is ScreenLoadState.Content ->
            data.toComputerDetailPayload()
        is ScreenLoadState.Error ->
            loadingComputerDetailPayload(
                selectedComputerId = selectedComputerId,
                name = "Unable to load computer",
                workspacesErrorCode = error.code,
                workspacesRetryable = error.retryable,
                agentsErrorCode = error.code,
                agentsRetryable = error.retryable
            )
        is ScreenLoadState.NoPermission ->
            loadingComputerDetailPayload(
                selectedComputerId = selectedComputerId,
                name = "No access to computer",
                workspacesErrorCode = blocker.code,
                workspacesRetryable = false,
                agentsErrorCode = blocker.code,
                agentsRetryable = false
            )
        is ScreenLoadState.Empty ->
            loadingComputerDetailPayload(
                selectedComputerId = selectedComputerId,
                name = "Computer unavailable"
            )
        ScreenLoadState.Loading ->
            loadingComputerDetailPayload(
                selectedComputerId = selectedComputerId,
                name = "Loading computer"
            )
    }

private fun SettingsComputerDetailSnapshot.toComputerDetailPayload(): KuiklySettingsComputerDetailPayload =
    KuiklySettingsComputerDetailPayload(
        instanceId = "shared-shell-settings-computer-${computer.id}",
        computer = computer.toComputerItem(),
        workspaces = workspaces.map { it.toWorkspaceItem() },
        agents = agents.map { it.toAgentItem() },
        workspacesErrorCode = workspacesError?.code,
        workspacesRetryable = workspacesError?.retryable ?: false,
        agentsErrorCode = agentsError?.code,
        agentsRetryable = agentsError?.retryable ?: false
    )

private fun loadingComputerDetailPayload(
    selectedComputerId: String?,
    name: String,
    workspacesErrorCode: String? = null,
    workspacesRetryable: Boolean = false,
    agentsErrorCode: String? = null,
    agentsRetryable: Boolean = false
): KuiklySettingsComputerDetailPayload =
    KuiklySettingsComputerDetailPayload(
        instanceId = "shared-shell-settings-computer-${selectedComputerId.orEmpty()}",
        computer = KuiklySettingsComputerItem(id = selectedComputerId.orEmpty(), name = name),
        workspacesErrorCode = workspacesErrorCode,
        workspacesRetryable = workspacesRetryable,
        agentsErrorCode = agentsErrorCode,
        agentsRetryable = agentsRetryable
    )

private fun SettingsComputerSnapshot.toComputerItem(): KuiklySettingsComputerItem =
    KuiklySettingsComputerItem(
        id = id,
        name = name,
        daemonVersion = daemonVersion,
        online = online,
        hostname = hostname,
        platform = platform,
        lastSeenLabel = lastSeenLabel
    )

private fun SettingsComputerWorkspaceSnapshot.toWorkspaceItem(): KuiklySettingsWorkspaceItem =
    KuiklySettingsWorkspaceItem(
        id = id,
        name = name,
        path = path,
        statusLabel = statusLabel,
        lastActiveLabel = lastActiveLabel
    )

private fun SettingsComputerAgentSnapshot.toAgentItem(): KuiklySettingsAgentItem {
    val profile = memberProfile
    return KuiklySettingsAgentItem(
        id = id,
        name = name,
        statusLabel = statusLabel,
        ownerLabel = ownerLabel,
        displayName = profile?.displayName,
        handle = profile?.handle,
        description = profile?.description,
        presenceLabel = profile?.presence?.name,
        roleLabel = profile?.roleDescription
    )
}

private fun ScreenLoadState<SharedSettingsStorageSnapshot>.toStoragePayload(
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    refreshing: Boolean
): KuiklySettingsStoragePayload {
    val storage = (this as? ScreenLoadState.Content<SharedSettingsStorageSnapshot>)?.data
    val statusText =
        when (this) {
            ScreenLoadState.Loading -> if (refreshing) "Refreshing storage..." else "Loading storage..."
            is ScreenLoadState.Empty -> "Storage information is not available on this platform."
            is ScreenLoadState.Error -> "Storage unavailable: ${this.error.code}"
            is ScreenLoadState.NoPermission -> "Storage unavailable: ${this.blocker.code}"
            is ScreenLoadState.Content -> storage?.feedback.orEmpty()
        }
    return KuiklySettingsStoragePayload(
        instanceId = "shared-shell-settings-storage",
        totalSize = storage?.totalSizeLabel?.takeIf { it.isNotBlank() } ?: if (refreshing) "Refreshing" else "--",
        feedback = statusText,
        rows =
            storage?.rows?.map { row ->
                row.toStoragePayloadRow(
                    clearInProgress =
                        actionState?.action == "clearStorage" &&
                            actionState.itemId == row.target &&
                            actionState.inProgress
                )
            }.orEmpty()
    )
}

private fun SharedSettingsStorageRow.toStoragePayloadRow(clearInProgress: Boolean): KuiklySettingsStorageRow =
    KuiklySettingsStorageRow(
        target = target,
        iconName = storageTargetIconName(target),
        title = title,
        detail = detail,
        size = sizeLabel,
        clearLabel =
            if (clearEnabled) {
                when (target) {
                    "imageCache" -> "Clear image cache"
                    "temporaryAndLogs" -> "Clear temp files and logs"
                    else -> "Clear"
                }
            } else {
                ""
            },
        clearEnabled = clearEnabled,
        clearInProgress = clearInProgress
    )

private fun storageTargetIconName(target: String): String =
    when (target) {
        "database" -> "archive"
        "imageCache" -> "image"
        "temporaryAndLogs" -> "file-text"
        else -> "archive"
    }

private fun ScreenLoadState<SettingsBillingSnapshot>.toBillingPayload(session: StoredAuthSession?): KuiklySettingsBillingPayload {
    val billing = (this as? ScreenLoadState.Content<SettingsBillingSnapshot>)?.data
    val fallbackPlan = session?.currentServerSummary()?.plan?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Hobby"
    return KuiklySettingsBillingPayload(
        instanceId = "shared-shell-settings-billing",
        currentPlan =
            KuiklySettingsCurrentPlan(
                planName = billing?.currentPlan?.planName ?: fallbackPlan,
                usageRows =
                    billing?.currentPlan?.usageRows
                        ?.map { KuiklySettingsPlanUsageRow(label = it.label, value = it.value) }
                        .orEmpty(),
                trialActive = billing?.currentPlan?.trialActive ?: false
            ),
        plans =
            billing?.plans?.map { plan ->
                KuiklySettingsBillingPlan(
                    id = plan.name.lowercase().replace(" ", "-"),
                    name = plan.name,
                    price = plan.price,
                    features = plan.features,
                    current = plan.current,
                    comingSoon = plan.comingSoon
                )
            } ?: listOf(
                KuiklySettingsBillingPlan(id = fallbackPlan.lowercase(), name = fallbackPlan, price = "Loading billing snapshot", current = true)
            )
    )
}

private fun ScreenLoadState<SettingsServerSnapshot>.toServerPayload(
    session: StoredAuthSession?,
    refreshing: Boolean
): KuiklySharedSettingsPayload {
    val server = session?.currentServerSummary()
    val snapshot = (this as? ScreenLoadState.Content<SettingsServerSnapshot>)?.data
    val role = server?.role?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Member"
    val plan = server?.plan?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Unknown"
    val detail =
        when (this) {
            ScreenLoadState.Loading -> if (refreshing) "Refreshing workspace settings..." else "Loading workspace settings..."
            is ScreenLoadState.Error -> "Workspace settings failed to load: ${error.code}"
            is ScreenLoadState.NoPermission -> "Workspace settings unavailable: ${blocker.code}"
            is ScreenLoadState.Empty -> "Workspace settings unavailable: ${reason.name}"
            is ScreenLoadState.Content -> "Shared workspace settings are backed by typed repository snapshots."
        }
    val administrationRows =
        snapshot?.sections.orEmpty()
            .filter { it.key in setOf("admins", "billing", "pending_invites", "join_links", "onboarding_agent") }
            .map {
                KuiklySharedSettingsRow(
                    key = it.key,
                    iconName =
                        when (it.key) {
                            "admins" -> "shield"
                            "billing" -> "credit-card"
                            "pending_invites" -> "mail-plus"
                            "join_links" -> "link-2"
                            "onboarding_agent" -> "bot"
                            else -> "badge-info"
                        },
                    title = it.title,
                    detail = it.detail,
                    status = it.state.name,
                    enabled = false
                )
            }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-server",
        title = "Server",
        heroIconName = "users",
        heroLabel = snapshot?.profile?.name ?: server?.name ?: "Workspace settings",
        detail = detail,
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Profile",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("serverName", "badge-info", "Workspace name", "Current selected workspace profile.", status = snapshot?.profile?.name ?: server?.name ?: "Unknown"),
                            KuiklySharedSettingsRow("serverSlug", "link", "Slug", snapshot?.profile?.slug?.let { "/$it" } ?: server?.slug?.let { "/$it" } ?: "Not provided", status = if (snapshot?.profile?.canSave == true) "Editable" else "Read only"),
                            KuiklySharedSettingsRow("serverRole", "shield-check", "Your role", "Permission scope for workspace settings.", status = role)
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Administration",
                    rows = administrationRows.ifEmpty {
                        listOf(
                            KuiklySharedSettingsRow("serverUnavailable", "users", "Workspace admin sections", "No server snapshot loaded yet.", status = "Unavailable", enabled = false)
                        )
                    }
                ),
                KuiklySharedSettingsSection(
                    title = "Plan and danger",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("billingSummary", "shield-check", "Plan", "Current workspace billing and usage summary from typed settings snapshot.", status = plan),
                            KuiklySharedSettingsRow("danger", "triangle-alert", "Danger actions", "Delete workspace contract is wired; destructive action remains disabled in shared UI until confirm flow lands.", status = "Disabled")
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

private fun ScreenLoadState<SettingsMobileSnapshot>.toMobilePayload(
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    refreshing: Boolean
): KuiklySharedSettingsPayload {
    val mobile =
        (this as? ScreenLoadState.Content<SettingsMobileSnapshot>)?.data
            ?: return appShellMobilePlaceholderPayload(this, refreshing)
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-mobile",
        title = "Mobile",
        heroIconName = "smartphone",
        heroLabel = "Mobile preferences",
        detail =
            if (refreshing) {
                "Refreshing mobile settings..."
            } else {
                "Notification controls and local preferences are backed by shared Settings MVI."
            },
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Notifications",
                    rows =
                        listOfNotNull(
                            KuiklySharedSettingsRow(
                                key = MOBILE_ACTION_NOTIFICATION_PREFERENCE,
                                iconName = "bell-ring",
                                title = "Notification preference",
                                detail = mobile.pushNotifications.detail,
                                value = (!mobile.pushNotifications.enabled).toString(),
                                status = actionState.statusFor(MOBILE_ACTION_NOTIFICATION_PREFERENCE, mobile.pushNotifications.statusLabel),
                                enabled = mobile.pushNotifications.available && actionState?.inProgress != true
                            ),
                            KuiklySharedSettingsRow(
                                key = MOBILE_ACTION_PERMISSION,
                                iconName = "bell",
                                title = "Push permission",
                                detail = mobile.notificationPermission.detail,
                                status = actionState.statusFor(MOBILE_ACTION_PERMISSION, mobile.notificationPermission.statusLabel),
                                enabled = mobile.notificationPermission.available && actionState?.inProgress != true
                            ),
                            KuiklySharedSettingsRow(
                                key = MOBILE_ACTION_SETTINGS,
                                iconName = "settings",
                                title = "System notification settings",
                                detail = mobile.notificationSettings.detail,
                                status = actionState.statusFor(MOBILE_ACTION_SETTINGS, mobile.notificationSettings.statusLabel),
                                enabled = mobile.notificationSettings.available && actionState?.inProgress != true
                            ),
                            KuiklySharedSettingsRow(
                                key = MOBILE_ACTION_TEST_NOTIFICATION,
                                iconName = "send",
                                title = "Send test notification",
                                detail = mobile.testNotification.detail,
                                status = actionState.statusFor(MOBILE_ACTION_TEST_NOTIFICATION, mobile.testNotification.statusLabel),
                                enabled = mobile.testNotification.available && actionState?.inProgress != true
                            ),
                            mobile.muteServer.takeIf { it.available }?.let {
                                KuiklySharedSettingsRow(
                                    key = MOBILE_ACTION_SERVER_MUTE,
                                    iconName = "volume-x",
                                    title = "Mute workspace",
                                    detail = it.detail,
                                    value = (!it.enabled).toString(),
                                    status = actionState.statusFor(MOBILE_ACTION_SERVER_MUTE, it.statusLabel),
                                    enabled = actionState?.inProgress != true
                                )
                            }
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Local preferences",
                    rows =
                        listOf(
                            mobile.localePreference.toMobileSelectionRow(
                                key = MOBILE_ACTION_LANGUAGE,
                                iconName = "languages",
                                title = "Language",
                                fallbackStatus = "Unsupported",
                                actionState = actionState
                            ),
                            mobile.messageAlignmentPreference.toMobileSelectionRow(
                                key = MOBILE_ACTION_MESSAGE_ALIGNMENT,
                                iconName = "align-horizontal-space-around",
                                title = "My message alignment",
                                fallbackStatus = "Unsupported",
                                actionState = actionState
                            )
                        )
                )
            )
    )
}

private fun appShellMobilePlaceholderPayload(
    state: ScreenLoadState<SettingsMobileSnapshot>,
    refreshing: Boolean
): KuiklySharedSettingsPayload {
    val status =
        when (state) {
            ScreenLoadState.Loading -> if (refreshing) "Refreshing" else "Loading"
            is ScreenLoadState.Empty -> "Unavailable"
            is ScreenLoadState.Error -> "Error"
            is ScreenLoadState.NoPermission -> "No permission"
            is ScreenLoadState.Content -> "Ready"
        }
    val detail =
        when (state) {
            is ScreenLoadState.Error -> state.error.code
            is ScreenLoadState.NoPermission -> state.blocker.code
            is ScreenLoadState.Empty -> state.reason.name
            else -> "Loading shared mobile settings."
        }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-mobile-placeholder",
        title = "Mobile",
        heroIconName = "smartphone",
        heroLabel = "Mobile preferences",
        detail = detail,
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Status",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow(
                                key = "mobileStatus",
                                iconName = "smartphone",
                                title = "Mobile settings",
                                detail = detail,
                                status = status,
                                enabled = false
                            )
                        )
                )
            )
    )
}

private fun SettingsMobileSelectionSnapshot.toMobileSelectionRow(
    key: String,
    iconName: String,
    title: String,
    fallbackStatus: String,
    actionState: ai.slock.compose.data.SharedSettingsActionState?
): KuiklySharedSettingsRow {
    val nextOption =
        options.dropWhile { it.key != selectedKey }.drop(1).firstOrNull()
            ?: options.firstOrNull()
    return KuiklySharedSettingsRow(
        key = key,
        iconName = iconName,
        title = title,
        detail =
            if (available && nextOption != null) {
                "$detail Next: ${nextOption.label}."
            } else {
                detail
            },
        value = nextOption?.key.orEmpty(),
        status = actionState.statusFor(key, statusLabel.ifBlank { fallbackStatus }),
        enabled = available && nextOption != null && actionState?.inProgress != true
    )
}

private fun ai.slock.compose.data.SharedSettingsActionState?.statusFor(action: String, fallback: String): String =
    when {
        this == null || this.action != action -> fallback
        inProgress -> "Loading"
        errorCode != null -> "Error"
        else -> "Saved"
    }

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
                            KuiklySharedSettingsRow("feedback", "message-square-plus", "Upload diagnostics", "Send sanitized logs to Bugly for support.", status = "Upload", enabled = true)
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
                            KuiklySharedSettingsRow("feedback", "message-square-plus", "Upload diagnostics", "Send sanitized logs to Bugly for support.", status = "Upload", enabled = true),
                            KuiklySharedSettingsRow("clearLogs", "trash-2", "Clear logs", "Clear the sanitized local diagnostics buffer.", status = if (logsAvailable) "Clear" else "Disabled", enabled = logsAvailable)
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Bugly validation",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("buglyPostTestError", "bug", "Report test error", "Post a non-crashing custom error with diagnostics attached.", status = "Report", enabled = true),
                            KuiklySharedSettingsRow("buglyTestJsCrash", "triangle-alert", "Simulate JS crash", "Crash the app to verify Bugly crash upload after restart.", status = "Crash", enabled = true),
                            KuiklySharedSettingsRow("buglyTestCppCrash", "triangle-alert", "Simulate native crash", "Crash the app to verify native crash upload after restart.", status = "Crash", enabled = true),
                            KuiklySharedSettingsRow("buglyTestFreeze", "timer", "Simulate freeze", "Trigger Bugly AppFreeze validation from the SDK.", status = "Freeze", enabled = true)
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
        SettingsDestinationComputerDetail -> "Computer"
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
        SettingsDestinationComputerDetail -> "Machine details"
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
        SettingsDestinationComputerDetail -> "monitor"
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
        SettingsDestinationComputerDetail -> SharedSettingsDestination.ComputerDetail
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
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    var mode by remember { mutableStateOf("login") }
    val reducer = remember { LoginFormReducer() }
    var state by remember { mutableStateOf(LoginFormState()) }
    var pendingLogin by remember { mutableStateOf<PendingAppShellLogin?>(null) }
    var pendingSocialAuth by remember { mutableStateOf<PendingAppShellSocialAuth?>(null) }
    var socialSubmittingProvider by remember { mutableStateOf<AuthProvider?>(null) }
    val socialProviders = remember(pager.pageData.params.toString()) { pager.appShellSocialAuthProviders() }

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

    LaunchedEffect(pendingSocialAuth) {
        val effect = pendingSocialAuth ?: return@LaunchedEffect
        val response = runCatching { bridge.socialAuth(effect.provider.appShellProviderId()) }.getOrNull()
        pendingSocialAuth = null
        socialSubmittingProvider = null
        val code = response?.optString("code").orEmpty().toIntOrNull()
        if (code == 0 || response?.optString("status") == "error") {
            state = state.copy(
                submitting = false,
                errorMessage =
                    response?.optString("message").takeUnless { it.isNullOrBlank() }
                        ?: "Google sign-in is unavailable. Try email sign-in."
            )
        } else {
            state = state.copy(submitting = false, errorMessage = null)
        }
    }

    fun submitLogin(emailValue: String = state.email, passwordValue: String = state.password) {
        val update = reducer.reduce(state.copy(email = emailValue, password = passwordValue), LoginFormEvent.Submit)
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
                socialProviders = socialProviders,
                socialSubmittingProvider = socialSubmittingProvider,
                onEmailChange = { state = reducer.reduce(state, LoginFormEvent.EmailChanged(it)).state },
                onPasswordChange = { state = reducer.reduce(state, LoginFormEvent.PasswordChanged(it)).state },
                onSubmit = { submitLogin() },
                onSubmitValues = { emailValue, passwordValue -> submitLogin(emailValue, passwordValue) },
                onSocialSignIn = { provider ->
                    state = state.copy(submitting = false, errorMessage = null)
                    socialSubmittingProvider = provider
                    pendingSocialAuth =
                        PendingAppShellSocialAuth(
                            nonce = (pendingSocialAuth?.nonce ?: 0) + 1,
                            provider = provider
                        )
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

private data class PendingAppShellSocialAuth(
    val nonce: Int,
    val provider: AuthProvider
)

private fun Pager.appShellSocialAuthProviders(): Set<AuthProvider> =
    pageData.params.optString("slockSocialAuthProviders")
        .split(',')
        .mapNotNull { raw ->
            when (raw.trim().lowercase()) {
                "google" -> AuthProvider.Google
                "github" -> AuthProvider.GitHub
                else -> null
            }
        }
        .toSet()

private fun AuthProvider.appShellProviderId(): String =
    when (this) {
        AuthProvider.Google -> "google"
        AuthProvider.GitHub -> "github"
    }

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
            enabled = enabled,
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
    val pager = LocalActivity.current.getPager() as Pager
    val memberRepository =
        remember {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient =
                    KuiklySlockNetworkClient(
                        sessionStore = authStore,
                        networkModule = pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME),
                        warningLogger = { message -> bridge.log("kuikly_network $message") }
                    ),
                logger = { message -> bridge.log("members_repository $message") }
            )
        }
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
            val response = memberRepository.getMembers(DataRequestPriority.Visible, ServerId(serverId))
            state =
                when (response) {
                    is ai.slock.compose.data.DataResult.Content ->
                        SharedMembersUiState.Content(response.data.server, response.data.members)
                    is ai.slock.compose.data.DataResult.Empty ->
                        SharedMembersUiState.Content(server, emptyList())
                    is ai.slock.compose.data.DataResult.NoPermission ->
                        SharedMembersUiState.Error(response.blocker.code)
                    is ai.slock.compose.data.DataResult.Error ->
                        SharedMembersUiState.Error(response.error.code)
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            state = SharedMembersUiState.Error("members_network")
        }
    }

    when (val current = state) {
        SharedMembersUiState.Loading ->
            Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
                SlockTopPageHeader(
                    title = "Members",
                    subtitle = "Workspace members",
                    iconName = "users"
                )
                SlockCenteredLoading()
            }
        is SharedMembersUiState.Error ->
            AppShellPlaceholderContent(
                title = "Members",
                subtitle = "Could not load members: ${current.code}",
                iconName = "users"
            )
        is SharedMembersUiState.Content ->
            MembersRouteContent(
                payload = KuiklyMembersPayload(
                    instanceId = current.server.id.value,
                    title = "Members",
                    members = current.members
                ),
                onAddHuman = { bridge.toast("Add member is not available yet") },
                onOpenDirectMessage = { bridge.toast("Direct message is not available yet") },
                onCopyMemberId = {
                    bridge.copyToPasteboard(it.id.value)
                    bridge.toast("Copied member id")
                }
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
    selectedConversationId: String? = null,
    onSelectedConversationCleared: () -> Unit = {},
    onOpenSearch: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSaved: () -> Unit,
    onCreateChannel: () -> Unit,
    onOpenConversation: (ConversationSummary) -> Unit,
    bottomContentPadding: Dp = SlockSpacing.Lg
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
                    bottomContentPadding = bottomContentPadding,
                    onRefresh = { onIntent(SharedHomeIntent.Refresh) },
                    onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                    onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                    onServerSelected = { serverId ->
                        onSelectedConversationCleared()
                        onIntent(SharedHomeIntent.SelectServer(serverId))
                    },
                    onCreateServerClick = {
                        bridge.toast("Create server is not available yet")
                    },
                    onCreateChannelClick = onCreateChannel
                )
            }
        is SharedHomeContentState.Empty ->
            SharedHomeSessionShell(
                session = current.session,
                serverSwitcherExpanded = serverSwitcherExpanded,
                contentRefreshing = false,
                bottomContentPadding = bottomContentPadding,
                onRefresh = { onIntent(SharedHomeIntent.Refresh) },
                onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                onServerSelected = { serverId ->
                    onSelectedConversationCleared()
                    onIntent(SharedHomeIntent.SelectServer(serverId))
                },
                onCreateServerClick = {
                    bridge.toast("Create server is not available yet")
                },
                onCreateChannelClick = onCreateChannel
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
            val conversationsByThreadId =
                remember(snapshot.channels, snapshot.directMessages) {
                    (snapshot.channels + snapshot.directMessages).associateBy { it.threadId.value }
                }
            SlockStructuralHomeShell(
                currentServerName = snapshot.server.name,
                currentServerId = snapshot.server.id.value,
                servers = servers,
                channels = snapshot.channels.map { it.toSharedHomeConversationItem() },
                directMessages = snapshot.directMessages.map { it.toSharedHomeConversationItem() },
                selectedChannelId = selectedConversationId,
                serverUnreadCount = snapshot.server.unreadCount,
                inboxUnreadCount = snapshot.unreadTotal,
                serverSwitcherExpanded = serverSwitcherExpanded,
                pendingServerId = serverSwitchState.pendingServerId,
                activitySelected = state.selectedTopAction == SharedHomeTopAction.Activity,
                searchSelected = state.selectedTopAction == SharedHomeTopAction.Search,
                savedSelected = state.selectedTopAction == SharedHomeTopAction.Saved,
                notifications = state.notifications.map { it.toHomeNotificationItem() },
                showBottomBar = false,
                bottomContentPadding = bottomContentPadding,
                outerPadding = 0.dp,
                contentRefreshing = current.contentRefreshing,
                onContentRefresh = { onIntent(SharedHomeIntent.Refresh) },
                onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                onServerSelected = { serverId ->
                    onSelectedConversationCleared()
                    onIntent(SharedHomeIntent.SelectServer(serverId))
                },
                onCreateServerClick = {
                    bridge.toast("Create server is not available yet")
                },
                onCreateChannelClick = onCreateChannel,
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
                onNotificationView = { notification ->
                    notification.targetId?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { url ->
                        bridge.openUrl(url)
                    } ?: bridge.toast("Notification opened")
                },
                onNotificationDismiss = { notification ->
                    onIntent(SharedHomeIntent.DismissNotification(notification.id))
                },
                onChannelClick = { threadId ->
                    val conversation = conversationsByThreadId[threadId]
                    if (conversation == null) {
                        bridge.toast("Conversation is no longer available")
                    } else {
                        onOpenConversation(conversation)
                        onIntent(SharedHomeIntent.SelectConversation(threadId))
                    }
                }
            )
        }
    }
}

@Composable
private fun SharedCreateChannelDialog(
    submitting: Boolean,
    membersLoading: Boolean,
    members: List<MemberProfile>,
    errorMessage: String?,
    initialName: String = "",
    initialDescription: String = "",
    initialSelectedMemberKeys: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSubmit: (String, String?, List<String>, List<String>) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var selectedMemberKeys by remember(initialSelectedMemberKeys) {
        mutableStateOf(initialSelectedMemberKeys)
    }
    val canSubmit = name.isNotBlank() && !submitting
    val selectableMembers = members.filter { it.kind == MemberKind.Human || it.kind == MemberKind.Agent }
    Modal(onWillDismiss = { if (!submitting) onDismiss() }) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SlockColor.ShadowBlack.copy(alpha = 0.36f))
                    .clickable(enabled = !submitting, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(SlockSpacing.Md)
                        .widthIn(min = 280.dp, max = 380.dp)
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .clickable(enabled = false, onClick = {})
                        .padding(SlockSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Text(
                    text = "Create channel",
                    style = SlockType.PageTitle,
                    color = SlockColor.TextBlack
                )
                Text(
                    text = "Create a workspace channel and optionally add people or agents.",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
                SharedCreateChannelField(
                    label = "Channel name",
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "project-updates",
                    enabled = !submitting,
                    autoFocus = true
                )
                SharedCreateChannelField(
                    label = "Description",
                    value = description,
                    onValueChange = { description = it },
                    placeholder = "Optional channel purpose",
                    enabled = !submitting,
                    maxLines = 3,
                    autoFocus = false
                )
                SharedCreateChannelMemberPicker(
                    loading = membersLoading,
                    members = selectableMembers,
                    selectedKeys = selectedMemberKeys,
                    enabled = !submitting,
                    onToggle = { key ->
                        selectedMemberKeys =
                            if (key in selectedMemberKeys) {
                                selectedMemberKeys - key
                            } else {
                                selectedMemberKeys + key
                            }
                    }
                )
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = "Error: $errorMessage",
                        style = SlockType.Caption,
                        color = SlockColor.AccentRed
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.weight(1f))
                    SharedCreateChannelButton(
                        label = "Cancel",
                        enabled = !submitting,
                        primary = false,
                        onClick = onDismiss
                    )
                    SharedCreateChannelButton(
                        label = if (submitting) "Creating..." else "Create",
                        enabled = canSubmit,
                        primary = true,
                        onClick = {
                            val selectedMembers =
                                selectableMembers.filter { it.memberSelectionKey in selectedMemberKeys }
                            onSubmit(
                                name,
                                description,
                                selectedMembers.filter { it.kind == MemberKind.Agent }.map { it.id.value },
                                selectedMembers.filter { it.kind == MemberKind.Human }.map { it.id.value }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedCreateChannelMemberPicker(
    loading: Boolean,
    members: List<MemberProfile>,
    selectedKeys: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Members and agents",
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack
        )
        when {
            loading ->
                Text(
                    text = "Loading members...",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            members.isEmpty() ->
                Text(
                    text = "No members available to add now.",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            else ->
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 156.dp)
                            .background(SlockColor.SurfaceCream.copy(alpha = 0.55f))
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    members.take(12).forEach { member ->
                        item(key = member.memberSelectionKey) {
                            SharedCreateChannelMemberRow(
                                member = member,
                                selected = member.memberSelectionKey in selectedKeys,
                                enabled = enabled,
                                onClick = { onToggle(member.memberSelectionKey) }
                            )
                        }
                    }
                    if (members.size > 12) {
                        item {
                            Text(
                                text = "+${members.size - 12} more available after creation",
                                style = SlockType.Caption,
                                color = SlockColor.TextMutedGray,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun SharedCreateChannelMemberRow(
    member: MemberProfile,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) SlockColor.BrandYellow.copy(alpha = 0.75f) else SlockColor.SurfaceWhite
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (selected) "✓" else "+",
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = member.displayName,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${member.kind.name.lowercase()} · @${member.handle}",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val MemberProfile.memberSelectionKey: String
    get() = "${kind.name}:${id.value}"

@Composable
private fun SharedCreateChannelField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    autoFocus: Boolean,
    maxLines: Int = 1
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack
        )
        TextField(
            value = value,
            onValueChange = { if (enabled) onValueChange(it) },
            placeholder = placeholder,
            placeholderColor = SlockColor.TextMutedGray,
            autoFocus = autoFocus,
            maxLines = maxLines,
            textStyle = SlockType.Input.copy(color = SlockColor.TextBlack),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .background(SlockColor.AppBgLightBlue.copy(alpha = 0.6f))
                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
                    .padding(horizontal = SlockSpacing.Sm, vertical = 8.dp)
        )
    }
}

@Composable
private fun SharedCreateChannelButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit
) {
    val background =
        when {
            !enabled -> SlockColor.TextMutedGray.copy(alpha = 0.22f)
            primary -> SlockColor.BrandYellow
            else -> SlockColor.SurfaceWhite
        }
    Box(
        modifier =
            Modifier
                .background(background)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = SlockSpacing.Md, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = SlockType.SectionLabel,
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray
        )
    }
}

private fun SharedHomeNotification.toHomeNotificationItem(): SlockHomeNotificationItem =
    SlockHomeNotificationItem(
        id = id,
        title = title,
        body = body,
        actionLabel = actionLabel,
        targetId = targetId,
        kind =
            when (kind) {
                SharedHomeNotificationKind.Warning -> SlockHomeNotificationKind.Warning
                SharedHomeNotificationKind.Info -> SlockHomeNotificationKind.Info
                SharedHomeNotificationKind.Error -> SlockHomeNotificationKind.Error
            }
    )

@Composable
private fun SharedHomeSearchPanel(
    state: SharedHomeSearchPanelState,
    homeState: SharedHomeState,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    onClose: (() -> Unit)? = null,
    interactiveSearchField: Boolean = true,
    includeTopSafeArea: Boolean = true,
    showSenderFilter: Boolean = true,
    initialOpenFilterId: String? = null,
    onOpenItem: (InboxItem) -> Unit,
    onOpenEntity: (SearchEntityResult) -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val channelFilters = remember(homeState) { homeSearchChannelFilters(homeState) }
    val entityResults = remember(state.query, homeState, state.senderFilters) {
        homeSearchEntityResults(state.query, homeState, state.senderFilters)
    }
    val groupedResults = remember(state.items) { groupHomeSearchResults(state.items) }
    var openFilterId by remember(initialOpenFilterId) { mutableStateOf(initialOpenFilterId) }
    var senderAnchor by remember { mutableStateOf(Offset.Zero) }
    var channelAnchor by remember { mutableStateOf(Offset.Zero) }
    var timeAnchor by remember { mutableStateOf(Offset.Zero) }
    var senderSearch by remember { mutableStateOf("") }
    var channelSearch by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        onIntent(SharedHomeSearchSavedIntent.OpenSearch)
    }
    Box(
        modifier =
            modifier
                .background(SlockColor.SurfaceWhite)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                SharedHomeSearchToolbar(
                    query = state.query,
                    loading = state.loading,
                    onQueryChange = { onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged(it)) },
                    onSubmit = { onIntent(SharedHomeSearchSavedIntent.SubmitSearch) },
                    onClear = { onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged("")) },
                    onClose = onClose,
                    interactive = interactiveSearchField,
                    includeTopSafeArea = includeTopSafeArea
                )
            }
            item {
                SharedHomeSearchFilters(
                    state = state,
                    channelFilters = channelFilters,
                    senderAnchorChanged = { senderAnchor = it },
                    channelAnchorChanged = { channelAnchor = it },
                    timeAnchorChanged = { timeAnchor = it },
                    onOpenFilter = { openFilterId = it },
                    onIntent = onIntent,
                    showSenderFilter = showSenderFilter
                )
            }
            item {
                if (state.loading || state.errorCode != null || state.noPermissionCode != null || state.empty || (!state.searched && state.items.isEmpty() && entityResults.isEmpty())) {
                    SharedHomePanelStatus(
                        loading = state.loading,
                        loadingText = "Searching messages...",
                        errorCode = state.errorCode,
                        noPermissionCode = state.noPermissionCode,
                        empty = state.empty || (!state.searched && state.items.isEmpty() && entityResults.isEmpty()),
                        emptyTitle = if (state.searched) "No matching results" else "Start with a search query",
                        emptyDetail =
                            if (state.searched) {
                                "Try another query, sender, channel, or time range."
                            } else {
                                "Search results will appear here after you submit a query."
                            },
                        onRetry = { onIntent(SharedHomeSearchSavedIntent.RefreshSearch) }
                    )
                } else {
                    SharedHomeSearchResultsCount(state.totalCount + entityResults.size)
                }
            }
            if (entityResults.isNotEmpty()) {
                item {
                    SharedHomeSearchSectionLabel("Channels & DMs")
                }
                items(entityResults.size, key = { index -> entityResults[index].key }) { index ->
                    SharedHomeSearchEntityRow(
                        result = entityResults[index],
                        onOpen = { onOpenEntity(entityResults[index]) }
                    )
                }
            }
            if (groupedResults.isNotEmpty()) {
                item {
                    SharedHomeSearchSectionLabel("Messages")
                }
            }
            groupedResults.forEach { group ->
                when (group) {
                    is HomeSearchResultGroup.Message -> {
                        item(key = group.item.key) {
                            SharedHomeSearchMessageResultRow(
                                item = group.item,
                                onOpen = { onOpenItem(group.item) }
                            )
                        }
                    }
                    is HomeSearchResultGroup.Thread -> {
                        item(key = group.key) {
                            SharedHomeThreadResultGroup(
                                group = group,
                                onOpenItem = onOpenItem
                            )
                        }
                    }
                }
            }
            if (state.hasMore) {
                item {
                    SharedHomeLoadMoreRow(
                        loading = state.loadingMore,
                        label = "Load more results",
                        onClick = { onIntent(SharedHomeSearchSavedIntent.LoadMoreSearch) }
                    )
                }
            }
            item {
                Spacer(Modifier.height(bottomContentPadding))
            }
        }
        when (openFilterId) {
            "sender" ->
                SlockFilterPopover(
                    anchor = senderAnchor,
                    title = "From",
                    searchPlaceholder = "Search senders",
                    searchText = senderSearch,
                    onSearchChange = { senderSearch = it },
                    options = state.senderFilterOptions(),
                    selectedIds = state.selectedSenderId?.let { setOf(it) } ?: emptySet(),
                    onToggle = {
                        onIntent(SharedHomeSearchSavedIntent.SetSearchSender(it))
                    },
                    onClear = { onIntent(SharedHomeSearchSavedIntent.SetSearchSender(null)) },
                    onDismiss = { openFilterId = null },
                    multiSelect = false,
                    surfaceBorderColor = SearchPopoverBorderColor,
                    surfaceShadowColor = SearchPopoverShadowColor,
                    headerSlot = {
                        if (state.senderFiltersLoading) {
                            Text("Loading senders...", style = SlockType.Caption, color = SlockColor.TextMutedGray)
                        } else if (state.senderFiltersErrorCode != null) {
                            Text(state.senderFiltersErrorCode, style = SlockType.Caption, color = SlockColor.AccentRed)
                        }
                    }
                )
            "channel" ->
                SlockFilterPopover(
                    anchor = channelAnchor,
                    title = "Channels",
                    searchPlaceholder = "Search...",
                    searchText = channelSearch,
                    onSearchChange = { channelSearch = it },
                    options = channelFilters.map { SlockFilterOption(it.id, it.label, it.icon) },
                    selectedIds = state.selectedChannelId?.let { setOf(it) } ?: emptySet(),
                    onToggle = {
                        onIntent(SharedHomeSearchSavedIntent.SetSearchChannel(it))
                    },
                    onClear = { onIntent(SharedHomeSearchSavedIntent.SetSearchChannel(null)) },
                    onDismiss = { openFilterId = null },
                    widthDp = 220f,
                    multiSelect = false,
                    surfaceBorderColor = SearchPopoverBorderColor,
                    surfaceShadowColor = SearchPopoverShadowColor
                )
            "time" ->
                SlockBaseMenuOverlay(
                    anchor = timeAnchor,
                    items = SearchTimeRange.entries.map { SlockBaseMenuItem(it.key, it.label) },
                    selectedId = state.timeRange.key,
                    onDismiss = { openFilterId = null },
                    onSelect = { key ->
                        SearchTimeRange.entries.firstOrNull { it.key == key }?.let {
                            onIntent(SharedHomeSearchSavedIntent.SetSearchTimeRange(it))
                        }
                        openFilterId = null
                    },
                    surfaceBorderColor = SearchPopoverBorderColor,
                    surfaceShadowColor = SearchPopoverShadowColor,
                    selectedRowColor = SlockColor.AccentCyan.copy(alpha = 0.45f)
                )
            else -> Unit
        }
    }
}

@Composable
private fun SharedHomeSearchToolbar(
    query: String,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onClose: (() -> Unit)?,
    interactive: Boolean = true,
    includeTopSafeArea: Boolean = true
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(
                    start = SearchControlsHorizontalPadding,
                    top = (if (includeTopSafeArea) slockTopSafeAreaPadding() else 0.dp) + 12.dp,
                    end = SearchControlsHorizontalPadding,
                    bottom = 12.dp
                ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onClose != null) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .padding(end = SearchControlShadowOffset, bottom = SearchControlShadowOffset)
            ) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .offset(x = SearchControlShadowOffset, y = SearchControlShadowOffset)
                            .background(SlockColor.ShadowBlack)
                )
                Button(
                    onClick = onClose,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                ) {
                    SlockLucideIcon("arrow-left", color = "#FF000000", modifier = Modifier.size(16.dp))
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .size(38.dp)
                    .padding(end = SearchControlShadowOffset, bottom = SearchControlShadowOffset)
        ) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = SearchControlShadowOffset, y = SearchControlShadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(SlockColor.BrandYellow)
                        .border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon("search", color = "#FF000000", modifier = Modifier.size(18.dp))
            }
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
        ) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = SearchControlShadowOffset, y = SearchControlShadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .padding(start = 11.dp, top = 7.dp, end = 7.dp, bottom = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (interactive) {
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = "Search channels, DMs, messages...",
                        placeholderColor = SlockColor.TextMutedGray,
                        autoFocus = false,
                        maxLines = 1,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (query.isNotBlank() && !loading) onSubmit() }),
                        textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        text = query.ifBlank { "Search channels, DMs, messages..." },
                        style = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp),
                        color = if (query.isBlank()) SlockColor.TextMutedGray else SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .padding(
                                end = if (interactive && query.isNotBlank()) SearchControlShadowOffset else 0.dp,
                                bottom = if (interactive && query.isNotBlank()) SearchControlShadowOffset else 0.dp
                            )
                ) {
                    if (interactive && query.isNotBlank()) {
                        Box(
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .offset(x = SearchControlShadowOffset, y = SearchControlShadowOffset)
                                    .background(SlockColor.ShadowBlack)
                        )
                    }
                    Button(
                        enabled = interactive && query.isNotBlank(),
                        onClick = onClear,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    if (interactive && query.isNotBlank()) {
                                        SlockColor.SurfaceWhite
                                    } else {
                                        SlockColor.EmptyStateGray.copy(alpha = 0.35f)
                                    }
                                )
                                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                    ) {
                        SlockLucideIcon("x", color = "#FF000000", modifier = Modifier.size(12.dp))
                    }
                }
                Text(
                    text = "ESC",
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun SharedHomeSavedPanel(
    state: SharedHomeSavedPanelState,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    onOpenItem: (InboxItem) -> Unit,
    bottomContentPadding: Dp
) {
    LaunchedEffect(Unit) {
        onIntent(SharedHomeSearchSavedIntent.OpenSaved)
    }
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        item {
            SharedHomePanelHeader(
                title = "Saved",
                subtitle = "Messages you saved for later.",
                iconName = "bookmark",
                actionLabel = if (state.loading) "Loading..." else "Refresh",
                actionEnabled = !state.loading,
                onAction = { onIntent(SharedHomeSearchSavedIntent.RefreshSaved) }
            )
        }
        item {
            SharedHomePanelStatus(
                loading = state.loading,
                loadingText = "Loading saved messages...",
                errorCode = state.errorCode,
                noPermissionCode = state.noPermissionCode,
                empty = state.empty || (state.loaded && state.items.isEmpty()),
                emptyTitle = "No saved messages",
                emptyDetail = "Saved message threads will appear here.",
                onRetry = { onIntent(SharedHomeSearchSavedIntent.RefreshSaved) }
            )
        }
        items(state.items.size, key = { index -> "saved-${state.items[index].key}" }) { index ->
            val item = state.items[index]
            SharedHomeInboxResultRow(
                item = item,
                actionLabel = "Unsave",
                onAction = { onIntent(SharedHomeSearchSavedIntent.Unsave(item)) },
                onOpen = { onOpenItem(item) }
            )
        }
        if (state.hasMore) {
            item {
                SharedHomeLoadMoreRow(
                    loading = state.loadingMore,
                    label = "Load more saved",
                    onClick = { onIntent(SharedHomeSearchSavedIntent.LoadMoreSaved) }
                )
            }
        }
        item {
            Spacer(Modifier.height(bottomContentPadding))
        }
    }
}

@Composable
private fun SharedHomePanelHeader(
    title: String,
    subtitle: String,
    iconName: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = slockTopSafeAreaPadding() + 18.dp, end = 18.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(name = iconName, color = "#FF000000", modifier = Modifier.size(30.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = SlockType.PageTitle.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        SharedHomePanelButton(
            label = actionLabel,
            enabled = actionEnabled,
            selected = true,
            onClick = onAction
        )
    }
}

@Composable
private fun SharedHomePanelSearchField(
    query: String,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .padding(horizontal = SearchControlsHorizontalPadding)
                .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search messages",
            placeholderColor = SlockColor.TextMutedGray,
            autoFocus = true,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (query.isNotBlank() && !loading) onSubmit() }),
            textStyle = SlockType.Input.copy(color = SlockColor.TextBlack),
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp)
                    .background(SlockColor.AppBgLightBlue.copy(alpha = 0.55f))
                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
                    .padding(horizontal = SlockSpacing.Sm, vertical = 9.dp)
        )
        SharedHomePanelButton(
            label = "Go",
            enabled = query.isNotBlank() && !loading,
            selected = true,
            onClick = onSubmit
        )
    }
}

@Composable
private fun SharedHomeSearchFilters(
    state: SharedHomeSearchPanelState,
    channelFilters: List<SearchChannelFilterItem>,
    senderAnchorChanged: (Offset) -> Unit,
    channelAnchorChanged: (Offset) -> Unit,
    timeAnchorChanged: (Offset) -> Unit,
    onOpenFilter: (String) -> Unit,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    showSenderFilter: Boolean = true
) {
    val hasActiveFilters =
        state.myMessagesOnly ||
            state.selectedSenderId != null ||
            state.selectedChannelId != null ||
            state.timeRange != SearchTimeRange.Any
    val selectedSender = state.senderFilters.firstOrNull { it.id == state.selectedSenderId }
    val selectedChannel = channelFilters.firstOrNull { it.id == state.selectedChannelId }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showSenderFilter) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SearchControlsHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    SharedHomeSearchFilterChip(
                        label = "My messages",
                        selected = state.myMessagesOnly,
                        enabled = true,
                        onClick = { onIntent(SharedHomeSearchSavedIntent.SetMyMessagesOnly(!state.myMessagesOnly)) }
                    )
                }
                item {
                    SharedHomeSearchFilterChip(
                        label = selectedSender?.displayLabel ?: "From",
                        selected = state.selectedSenderId != null,
                        enabled = true,
                        uppercase = selectedSender == null,
                        trailingIconName = "chevron-down",
                        modifier = Modifier.onGloballyPositioned { senderAnchorChanged(it.positionInRoot()) },
                        onClick = { onOpenFilter("sender") }
                    )
                }
                item {
                    SharedHomeSearchFilterChip(
                        label = selectedChannel?.label ?: "Channel",
                        selected = state.selectedChannelId != null,
                        enabled = true,
                        uppercase = selectedChannel == null,
                        leadingIconName = "hash",
                        trailingIconName = "chevron-down",
                        modifier = Modifier.onGloballyPositioned { channelAnchorChanged(it.positionInRoot()) },
                        onClick = { onOpenFilter("channel") }
                    )
                }
                item {
                    SharedHomeSearchFilterChip(
                        label = state.timeRange.label,
                        selected = state.timeRange != SearchTimeRange.Any,
                        enabled = true,
                        uppercase = state.timeRange == SearchTimeRange.Any,
                        leadingIconName = "calendar",
                        trailingIconName = "chevron-down",
                        modifier = Modifier.onGloballyPositioned { timeAnchorChanged(it.positionInRoot()) },
                        onClick = { onOpenFilter("time") }
                    )
                }
                if (hasActiveFilters) {
                    item {
                        SharedHomeSearchFilterChip(
                            label = "Clear all",
                            selected = false,
                            enabled = true,
                            onClick = { onIntent(SharedHomeSearchSavedIntent.ClearSearchFilters) }
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SearchControlsHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SharedHomeSearchFilterChip(
                    label = "My messages",
                    selected = state.myMessagesOnly,
                    enabled = true,
                    onClick = { onIntent(SharedHomeSearchSavedIntent.SetMyMessagesOnly(!state.myMessagesOnly)) }
                )
                SharedHomeSearchFilterChip(
                    label = selectedChannel?.label ?: "Channel",
                    selected = state.selectedChannelId != null,
                    enabled = true,
                    uppercase = selectedChannel == null,
                    leadingIconName = "hash",
                    trailingIconName = "chevron-down",
                    modifier = Modifier.onGloballyPositioned { channelAnchorChanged(it.positionInRoot()) },
                    onClick = { onOpenFilter("channel") }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SearchControlsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!showSenderFilter) {
                SharedHomeSearchFilterChip(
                    label = state.timeRange.label,
                    selected = state.timeRange != SearchTimeRange.Any,
                    enabled = true,
                    uppercase = state.timeRange == SearchTimeRange.Any,
                    leadingIconName = "calendar",
                    trailingIconName = "chevron-down",
                    modifier = Modifier.onGloballyPositioned { timeAnchorChanged(it.positionInRoot()) },
                    onClick = { onOpenFilter("time") }
                )
            }
            if (!showSenderFilter && hasActiveFilters) {
                SharedHomeSearchFilterChip(
                    label = "Clear all",
                    selected = false,
                    enabled = true,
                    onClick = { onIntent(SharedHomeSearchSavedIntent.ClearSearchFilters) }
                )
            }
            SharedHomeSearchSortSegmentedControl(
                sort = state.sort,
                onSortChange = { onIntent(SharedHomeSearchSavedIntent.SetSearchSort(it)) }
            )
        }
    }
}

@Composable
private fun SharedHomePanelStatus(
    loading: Boolean,
    loadingText: String,
    errorCode: String?,
    noPermissionCode: String?,
    empty: Boolean,
    emptyTitle: String,
    emptyDetail: String,
    onRetry: () -> Unit
) {
    when {
        loading ->
            SharedHomePanelMessage(
                title = loadingText,
                detail = "This page is using the shared KMP repository.",
                tone = SlockColor.BrandYellow
            )
        noPermissionCode != null ->
            SharedHomePanelMessage(
                title = "No permission",
                detail = noPermissionCode,
                tone = SlockColor.AccentRed,
                actionLabel = "Retry",
                onAction = onRetry
            )
        errorCode != null ->
            SharedHomePanelMessage(
                title = "Could not load",
                detail = errorCode,
                tone = SlockColor.AccentRed,
                actionLabel = "Retry",
                onAction = onRetry
            )
        empty ->
            SharedHomePanelMessage(
                title = emptyTitle,
                detail = emptyDetail,
                tone = SlockColor.AppBgLightBlue
            )
    }
}

@Composable
private fun SharedHomePanelMessage(
    title: String,
    detail: String,
    tone: com.tencent.kuikly.compose.ui.graphics.Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier =
            Modifier
                .padding(horizontal = SearchMessagesHorizontalPadding)
                .fillMaxWidth()
                .background(tone.copy(alpha = 0.42f))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = SlockType.SectionLabel,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (actionLabel != null && onAction != null) {
            SharedHomePanelButton(label = actionLabel, selected = false, onClick = onAction)
        }
    }
}

@Composable
private fun SharedHomeInboxResultRow(
    item: InboxItem,
    actionLabel: String,
    onAction: () -> Unit,
    onOpen: () -> Unit
) {
    val preview = item.latestActivityPreview ?: item.lastMessagePreview ?: item.parentMessagePreview ?: "No preview"
    val sender = item.latestActivitySenderName ?: item.lastMessageSenderName
    val meta =
        listOfNotNull(
            item.channelName.takeIf { it.isNotBlank() }?.let { if (item.kind == InboxItemKind.Channel) "#$it" else it },
            sender,
            item.latestActivityAt ?: item.lastMessageAt
        ).joinToString(" · ")
    Row(
        modifier =
            Modifier
                .padding(horizontal = 18.dp)
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(onClick = onOpen)
                .padding(SlockSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .background(SlockColor.BrandYellow)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            SlockLucideIcon(
                name =
                    when (item.kind) {
                        InboxItemKind.DirectMessage -> "mail"
                        InboxItemKind.Thread -> "message-square"
                        InboxItemKind.System -> "bell-ring"
                        else -> "hash"
                    },
                color = "#FF000000",
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = item.parentChannelName ?: item.channelName,
                style = SlockType.SectionLabel,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = preview,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = meta.ifBlank { "Message" },
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.replyCount > 0) {
                Text(
                    text = "${item.replyCount} replies",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            }
            SharedHomePanelButton(label = actionLabel, selected = false, onClick = onAction)
        }
    }
}

@Composable
private fun SharedHomeSearchResultsCount(totalResults: Int) {
    Text(
        text = "$totalResults results",
        style = SlockType.SectionLabel.copy(fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 1.4.sp),
        color = SlockColor.TextMutedGray,
        maxLines = 1,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SearchMessagesHorizontalPadding, vertical = 12.dp)
    )
}

@Composable
private fun SharedHomeSearchSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = SlockType.Caption.copy(letterSpacing = 1.6.sp),
        color = SlockColor.TextMutedGray,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = SearchMessagesHorizontalPadding, vertical = 8.dp)
    )
}

@Composable
private fun SharedHomeSearchEntityRow(
    result: SearchEntityResult,
    onOpen: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SearchLightRowBorderColor)
                .clickable(onClick = onOpen)
                .padding(horizontal = 13.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SharedHomeSearchEntityLeadingAvatar(result)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = result.title,
                    style = SlockType.SectionLabel.copy(fontSize = 14.sp, lineHeight = 17.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SharedHomeEntityTypeBadge(result.type)
            }
            Text(
                text = result.subtitle,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SharedHomeEntityTypeBadge(type: SearchEntityType) {
    Text(
        text =
            when (type) {
                SearchEntityType.Channel -> "Channel"
                SearchEntityType.DirectMessage -> "DM"
                SearchEntityType.Human -> "DM"
                SearchEntityType.Agent -> "DM"
            },
        style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 10.sp, letterSpacing = 0.6.sp),
        color = SlockColor.TextMutedGray,
        maxLines = 1,
        modifier =
            Modifier
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
                .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

@Composable
private fun SharedHomeThreadResultGroup(
    group: HomeSearchResultGroup.Thread,
    onOpenItem: (InboxItem) -> Unit
) {
    val firstItem = group.items.firstOrNull()
    val sourceLabel = searchResultSourceLabel(firstItem ?: return)
    val latestTime = firstItem.latestActivityAt ?: firstItem.lastMessageAt
    Box(
        modifier =
            Modifier
                .padding(horizontal = SearchMessagesHorizontalPadding)
                .padding(end = SearchControlShadowOffset, bottom = SearchControlShadowOffset)
                .fillMaxWidth()
    ) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .offset(x = SearchControlShadowOffset, y = SearchControlShadowOffset)
                    .background(SlockColor.ShadowBlack)
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(SlockColor.AccentPink.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SharedHomeMiniMetaBadge("Thread", "message-square", compact = true)
                    Text(
                        text = sourceLabel,
                        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${group.items.size} hits",
                        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    if (!latestTime.isNullOrBlank()) {
                        Text(
                            text = latestTime,
                            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                            color = SlockColor.TextMutedGray,
                            maxLines = 1
                        )
                    }
                }
                Text(
                    text = group.title,
                    style = SlockType.SectionLabel.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(SlockStroke.Thin)
                        .background(SlockColor.EmptyStateGray)
            )
            SharedHomeSearchMessageResultRow(
                item = firstItem,
                nested = true,
                onOpen = { onOpenItem(firstItem) }
            )
        }
    }
}

@Composable
private fun SharedHomeSearchMessageResultRow(
    item: InboxItem,
    nested: Boolean = false,
    onOpen: () -> Unit
) {
    val preview = item.latestActivityPreview ?: item.lastMessagePreview ?: item.parentMessagePreview ?: "No preview"
    val sender = item.latestActivitySenderName ?: item.lastMessageSenderName
    val time = item.latestActivityAt ?: item.lastMessageAt
    val compactMeta = nested
    Column(
        modifier =
            Modifier
                .let {
                    if (nested) {
                        it
                            .fillMaxWidth()
                    } else {
                        it
                            .padding(horizontal = SearchMessagesHorizontalPadding, vertical = 4.dp)
                            .fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SearchLightRowBorderColor)
                    }
                }
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = if (nested) 10.dp else 11.dp),
        verticalArrangement = Arrangement.spacedBy(if (nested) 5.dp else 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (nested) 6.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!nested) {
                Text(
                    text = searchResultSourceLabel(item),
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 13.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (item.kind == InboxItemKind.Thread && !nested) {
                SharedHomeMiniMetaBadge("thread", "message-square")
            }
            if (!sender.isNullOrBlank()) {
                SharedHomeSearchSenderMeta(
                    sender = sender,
                    modifier = Modifier.weight(1f),
                    compact = compactMeta
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = time ?: "",
                style = SlockType.Caption.copy(fontSize = if (compactMeta) 9.sp else 10.sp, lineHeight = if (compactMeta) 11.sp else 12.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1
            )
        }
        Text(
            text = preview,
            style = SlockType.Body.copy(fontSize = if (nested) 12.sp else 13.sp, lineHeight = if (nested) 17.sp else 18.sp),
            color = SlockColor.TextBlack.copy(alpha = if (nested) 0.7f else 0.74f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!nested && (item.replyCount > 0 || item.taskNumber != null)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.replyCount > 0) {
                    SharedHomeMiniMetaBadge("${item.replyCount} replies", "message-square")
                }
                item.taskNumber?.let {
                    SharedHomeMiniMetaBadge("task #$it", "square-check-big")
                }
            }
        }
    }
}

@Composable
private fun SharedHomeMiniMetaBadge(
    label: String,
    iconName: String,
    compact: Boolean = false
) {
    Row(
        modifier =
            Modifier
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
                .padding(horizontal = if (compact) 4.dp else 5.dp, vertical = if (compact) 1.dp else 2.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(iconName, color = "#99000000", modifier = Modifier.size(if (compact) 9.dp else 10.dp))
        Text(
            text = label,
            style = SlockType.Caption.copy(fontSize = if (compact) 9.sp else 10.sp, lineHeight = if (compact) 11.sp else 12.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun searchResultSourceLabel(item: InboxItem): String {
    val name =
        if (item.kind == InboxItemKind.Thread) {
            item.parentChannelName ?: item.channelName
        } else {
            item.channelName
        }
    return if (item.kind == InboxItemKind.DirectMessage) "@$name" else "#$name"
}

@Composable
private fun SharedHomeLoadMoreRow(
    loading: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier =
            Modifier
                .padding(horizontal = SearchMessagesHorizontalPadding)
                .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        SharedHomePanelButton(
            label = if (loading) "Loading..." else label,
            enabled = !loading,
            selected = false,
            onClick = onClick
        )
    }
}

@Composable
private fun SharedHomeFilterChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    onClick: () -> Unit
) {
    SharedHomePanelButton(
        label = label,
        enabled = enabled,
        selected = selected,
        modifier = modifier,
        dense = dense,
        onClick = onClick
    )
}

@Composable
private fun SharedHomeSearchFilterChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    uppercase: Boolean = true,
    leadingIconName: String? = null,
    trailingIconName: String? = null,
    onClick: () -> Unit
) {
    val background =
        when {
            !enabled -> SlockColor.TextMutedGray.copy(alpha = 0.18f)
            selected -> SlockColor.BrandYellow
            else -> SlockColor.SurfaceWhite
        }
    val contentColor =
        when {
            !enabled -> SlockColor.TextMutedGray
            selected -> SlockColor.TextBlack
            else -> SearchMutedTextColor
        }
    SharedHomeSearchControlSurface(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
        background = background,
        borderColor = if (selected) SlockColor.BorderBlack else SearchMutedBorderColor,
        shadowColor = if (selected) SlockColor.ShadowBlack else Color.Transparent
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            leadingIconName?.let {
                SlockLucideIcon(it, modifier = Modifier.size(12.dp), color = if (selected) "#FF000000" else "#73000000")
            }
            Text(
                text = if (uppercase) label.uppercase() else label,
                style =
                    SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        letterSpacing = 1.1.sp
                    ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            trailingIconName?.let {
                SlockLucideIcon(it, modifier = Modifier.size(12.dp), color = if (selected) "#FF000000" else "#73000000")
            }
        }
    }
}

@Composable
private fun SharedHomeSearchSortSegmentedControl(
    sort: SearchSort,
    modifier: Modifier = Modifier,
    onSortChange: (SearchSort) -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SharedHomeSearchSortSegmentButton(
            label = "Relevant",
            selected = sort == SearchSort.Relevance,
            onClick = { onSortChange(SearchSort.Relevance) }
        )
        SharedHomeSearchSortSegmentButton(
            label = "Recent",
            selected = sort == SearchSort.Recent,
            onClick = { onSortChange(SearchSort.Recent) }
        )
    }
}

@Composable
private fun SharedHomeSearchSortSegmentButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SharedHomeSearchControlSurface(
        enabled = true,
        onClick = onClick,
        modifier = modifier.heightIn(min = 30.dp),
        background = if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite,
        borderColor = SlockColor.BorderBlack,
        shadowColor = if (selected) SlockColor.ShadowBlack else Color.Transparent
    ) {
        Text(
            text = label.uppercase(),
            style = SlockType.TabLabel.copy(fontSize = 12.sp, lineHeight = 14.sp),
            color = if (selected) SlockColor.TextBlack else SearchMutedTextColor,
            maxLines = 1
        )
    }
}

@Composable
private fun SharedHomeSearchControlSurface(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color,
    borderColor: Color = SlockColor.BorderBlack,
    shadowColor: Color = SlockColor.ShadowBlack,
    content: @Composable () -> Unit
) {
    Box(
        modifier =
            modifier
                .padding(
                    end = if (shadowColor == Color.Transparent) 0.dp else SearchControlShadowOffset,
                    bottom = if (shadowColor == Color.Transparent) 0.dp else SearchControlShadowOffset
                )
    ) {
        if (shadowColor != Color.Transparent) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = SearchControlShadowOffset, y = SearchControlShadowOffset)
                        .background(
                            if (enabled) {
                                shadowColor
                            } else {
                                shadowColor.copy(alpha = 0.18f)
                            }
                        )
            )
        }
        Button(
            enabled = enabled,
            onClick = onClick,
            modifier =
                Modifier
                    .background(background)
                    .border(SlockStroke.Default, borderColor)
                    .padding(horizontal = 11.dp, vertical = 8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SharedHomePanelButton(
    label: String,
    enabled: Boolean = true,
    selected: Boolean,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    onClick: () -> Unit
) {
    val background =
        when {
            !enabled -> SlockColor.TextMutedGray.copy(alpha = 0.18f)
            selected -> SlockColor.BrandYellow
            else -> SlockColor.SurfaceWhite
        }
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier =
            modifier
                .background(background)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = if (dense) 5.dp else 11.dp, vertical = if (dense) 6.dp else 7.dp)
    ) {
        Text(
            text = label,
            style = SlockType.SectionLabel.copy(fontSize = if (dense) 9.sp else 11.sp, lineHeight = if (dense) 11.sp else 13.sp),
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class SearchChannelFilterItem(
    val id: String,
    val label: String,
    val icon: String
)

private fun homeSearchChannelFilters(state: SharedHomeState): List<SearchChannelFilterItem> {
    val snapshot = (state.content as? SharedHomeContentState.Content)?.snapshot ?: return emptyList()
    return snapshot.channels
        .mapNotNull { conversation ->
            val channelId = conversation.channelId?.value ?: return@mapNotNull null
            SearchChannelFilterItem(
                id = channelId,
                label = "#${conversation.name}",
                icon = "hash"
            )
        }
        .distinctBy { it.id }
}

private enum class SearchEntityType {
    Channel,
    DirectMessage,
    Human,
    Agent
}

private data class SearchEntityResult(
    val key: String,
    val type: SearchEntityType,
    val title: String,
    val subtitle: String,
    val threadId: String?,
    val conversation: ConversationSummary?,
    val avatarWireValue: String? = null,
    val presence: SlockPresence? = null
)

private sealed interface HomeSearchResultGroup {
    data class Message(val item: InboxItem) : HomeSearchResultGroup
    data class Thread(
        val key: String,
        val title: String,
        val items: List<InboxItem>
    ) : HomeSearchResultGroup
}

private fun groupHomeSearchResults(items: List<InboxItem>): List<HomeSearchResultGroup> {
    val groups = mutableListOf<HomeSearchResultGroup>()
    val threadGroups = linkedMapOf<String, MutableList<InboxItem>>()
    items.forEach { item ->
        val threadKey = item.threadChannelId?.value?.takeIf { item.kind == InboxItemKind.Thread }
        if (threadKey == null) {
            groups += HomeSearchResultGroup.Message(item)
            return@forEach
        }
        val existing = threadGroups.getOrPut(threadKey) {
            mutableListOf<InboxItem>().also { groups += HomeSearchResultGroup.Thread(threadKey, item.parentMessagePreview ?: "Thread discussion", it) }
        }
        existing += item
    }
    return groups
}

private fun SharedHomeSearchPanelState.senderFilterOptions(): List<SlockFilterOption> =
    senderFilters
        .map { sender ->
            SlockFilterOption(
                id = sender.id,
                label = if (sender.isSelf) "Me" else sender.displayLabel,
                icon = if (sender.kind == MemberKind.Agent) "bot" else "user-circle"
            )
        }
        .distinctBy { it.id }

private val SearchSenderFilterItem.displayLabel: String
    get() = label.ifBlank { handle.ifBlank { id } }

private fun homeSearchEntityResults(
    query: String,
    homeState: SharedHomeState,
    senderFilters: List<SearchSenderFilterItem>
): List<SearchEntityResult> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return emptyList()
    val snapshot = (homeState.content as? SharedHomeContentState.Content)?.snapshot ?: return emptyList()
    val conversations = snapshot.channels + snapshot.directMessages
    val byPresenceMemberId =
        snapshot.directMessages
            .mapNotNull { conversation ->
                val memberId = conversation.presenceMemberId?.value ?: return@mapNotNull null
                memberId to conversation
            }
            .toMap()
    val conversationResults =
        conversations.mapNotNull { conversation ->
            if (!conversation.searchMatches(normalizedQuery)) return@mapNotNull null
            val type =
                if (conversation.kind == ConversationKind.DirectMessage) {
                    SearchEntityType.DirectMessage
                } else {
                    SearchEntityType.Channel
                }
            SearchEntityResult(
                key = "conversation:${conversation.threadId.value}",
                type = type,
                title = if (type == SearchEntityType.Channel) "#${conversation.name}" else conversation.name,
                subtitle = if (type == SearchEntityType.Channel) "Channel" else "DM",
                threadId = conversation.threadId.value,
                conversation = conversation,
                avatarWireValue = conversation.avatar?.wireValue ?: conversation.avatarUrl,
                presence = conversation.presence.toSharedPresence()
            )
        }
    val memberResults =
        senderFilters.mapNotNull { sender ->
            if (!sender.searchMatches(normalizedQuery)) return@mapNotNull null
            val conversation = byPresenceMemberId[sender.id]
            SearchEntityResult(
                key = "sender:${sender.kind}:${sender.id}",
                type = if (sender.kind == MemberKind.Agent) SearchEntityType.Agent else SearchEntityType.Human,
                title = sender.displayLabel,
                subtitle =
                    buildString {
                        append(if (sender.kind == MemberKind.Agent) "Agent" else "Person")
                        if (sender.handle.isNotBlank()) append(" · @${sender.handle}")
                        if (conversation == null) append(" · Members")
                    },
                threadId = conversation?.threadId?.value,
                conversation = conversation,
                avatarWireValue = conversation?.avatar?.wireValue ?: conversation?.avatarUrl ?: sender.avatarWireValue ?: "random:${sender.id}",
                presence = (conversation?.presence ?: sender.agentStatus?.presence ?: sender.presence).toSharedPresence()
            )
        }
    return (conversationResults + memberResults)
        .distinctBy { it.key }
        .take(5)
}

private fun ConversationSummary.searchMatches(query: String): Boolean =
    listOf(name, subtitle.orEmpty(), channelId?.value.orEmpty(), threadId.value)
        .any { it.lowercase().contains(query) }

private fun SearchSenderFilterItem.searchMatches(query: String): Boolean =
    listOf(label, handle, id).any { it.lowercase().contains(query) }

private fun SlockPresence?.searchStatusColor(): Color? =
    when (this) {
        SlockPresence.Online -> SlockColor.AccentLime
        SlockPresence.Offline -> SlockColor.EmptyStateGray
        SlockPresence.Unknown -> SlockColor.BrandYellow
        null -> null
    }

private fun String.searchAvatarSeed(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "default" }

private fun InboxItem.toConversationSummary(): ConversationSummary {
    val itemParentChannelId = parentChannelId
    val itemParentMessageId = parentMessageId
    val threadId =
        if (kind == InboxItemKind.Thread && itemParentChannelId != null && itemParentMessageId != null) {
            threadIdentity(itemParentChannelId, itemParentMessageId)
        } else {
            topLevelThreadId(threadChannelId ?: channelId)
        }
    return ConversationSummary(
        threadId = threadId,
        channelId = itemParentChannelId ?: channelId,
        thread =
            if (kind == InboxItemKind.Thread && itemParentChannelId != null && itemParentMessageId != null) {
                ThreadConversation(
                    parentChannelId = itemParentChannelId,
                    parentMessageId = itemParentMessageId,
                    streamChannelId = threadChannelId
                )
            } else {
                null
            },
        kind =
            parentChannelKind
                ?: when (kind) {
                    InboxItemKind.DirectMessage -> ConversationKind.DirectMessage
                    InboxItemKind.Thread -> ConversationKind.Thread
                    InboxItemKind.System -> ConversationKind.System
                    else -> ConversationKind.Channel
                },
        name =
            when (kind) {
                InboxItemKind.Thread -> parentChannelName ?: channelName
                else -> channelName
            },
        subtitle = parentMessagePreview ?: lastMessagePreview ?: latestActivityPreview,
        unreadCount = unreadCount,
        lastMessageId = latestActivityMessageId ?: lastMessageId,
        firstUnreadMessageId = firstUnreadMessageId,
        lastMessagePreview = latestActivityPreview ?: lastMessagePreview,
        lastMessageAt = latestActivityAt ?: lastMessageAt,
        lastMessageSenderName = latestActivitySenderName ?: lastMessageSenderName
    )
}

@Composable
private fun SharedHomeSessionShell(
    session: StoredAuthSession,
    serverSwitcherExpanded: Boolean,
    contentRefreshing: Boolean,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onRefresh: () -> Unit,
    onServerBadgeClick: () -> Unit,
    onServerSwitcherDismiss: () -> Unit,
    onServerSelected: (String) -> Unit,
    onCreateServerClick: () -> Unit,
    onCreateChannelClick: () -> Unit
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
        bottomContentPadding = bottomContentPadding,
        outerPadding = 0.dp,
        contentRefreshing = contentRefreshing,
        onContentRefresh = onRefresh,
        onServerBadgeClick = onServerBadgeClick,
        onServerSwitcherDismiss = onServerSwitcherDismiss,
        onServerSelected = onServerSelected,
        onCreateServerClick = onCreateServerClick,
        onCreateChannelClick = onCreateChannelClick
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
        lastMessageAt = lastMessageAt,
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
    val todayEpochDay: Long,
    val nowEpochMillis: Long? = null
)

private fun com.tencent.kuikly.core.nvi.serialization.json.JSONObject.threadTimeContext(): AppShellThreadTimeContext? {
    val offset = optString("localOffsetMinutes").toIntOrNull() ?: return null
    val today = optString("todayEpochDay").toLongOrNull() ?: return null
    val now = optString("nowEpochMillis").toLongOrNull()
    return AppShellThreadTimeContext(offset, today, now)
}

private fun AppShellThreadTimeContext?.toSearchIsoBounds(range: SearchTimeRange): SearchIsoBounds {
    val context = this ?: return SearchIsoBounds()
    val afterDay =
        when (range) {
            SearchTimeRange.Any -> null
            SearchTimeRange.Today -> context.todayEpochDay
            SearchTimeRange.Last7Days -> context.todayEpochDay - 6L
            SearchTimeRange.Last30Days -> context.todayEpochDay - 29L
        }
    return SearchIsoBounds(
        afterIso = afterDay?.let { slockStartOfDayUtcIso(it, context.localOffsetMinutes) },
        beforeIso = context.nowEpochMillis?.let(::slockEpochMillisUtcIso)
    )
}

internal fun buildAppShellThreadPayload(
    conversation: ConversationSummary,
    timeContext: AppShellThreadTimeContext? = null
): KuiklyThreadScreenPayload =
    KuiklyThreadScreenPayload(
        instanceId = "shared-shell-thread-${conversation.threadId.value}",
        title = conversation.name.ifBlank { "Thread" },
        subtitle = conversation.subtitle?.takeIf { it.isNotBlank() },
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

private suspend fun openAppShellTaskThread(
    task: TaskSummary,
    dataSource: SharedThreadDataSource,
    timeContext: AppShellThreadTimeContext?,
    onOpenThreadPayload: (KuiklyThreadScreenPayload) -> Unit,
    onReplaceThreadPayload: (KuiklyThreadScreenPayload) -> Unit,
    onFailure: () -> Unit
) {
    val loadingPayload = buildAppShellTaskThreadLoadingPayload(task, timeContext)
    onOpenThreadPayload(loadingPayload)
    val channelId = task.channelId
    val parentMessage = task.toAppShellTaskParentMessage()
    if (channelId != null && parentMessage != null) {
        val channelPayload =
            buildAppShellThreadPayload(
                ConversationSummary(
                    threadId = ThreadId(channelId.value),
                    channelId = channelId,
                    kind = ConversationKind.Channel,
                    name = task.channelName?.takeIf { it.isNotBlank() } ?: "Channel",
                    joined = true,
                    permissions = ConversationPermissions(canSend = true, canAttach = true, canCreateTask = true)
                ),
                timeContext
            )
        when (val result = dataSource.createOrOpenReplyThread(channelPayload, parentMessage)) {
            is ai.slock.compose.data.DataResult.Content -> {
                onReplaceThreadPayload(result.data)
                return
            }
            is ai.slock.compose.data.DataResult.Empty,
            is ai.slock.compose.data.DataResult.Error,
            is ai.slock.compose.data.DataResult.NoPermission -> {
                onReplaceThreadPayload(loadingPayload.copy(status = "error", errorCode = "thread_task_open_failed"))
                onFailure()
                return
            }
        }
    }

    val threadId = task.threadId?.value?.takeIf { it.isNotBlank() }
    if (threadId != null) {
        onReplaceThreadPayload(loadingPayload.copy(sourceThreadId = threadId))
    } else {
        onReplaceThreadPayload(loadingPayload.copy(status = "error", errorCode = "thread_task_open_failed"))
        onFailure()
    }
}

internal fun buildAppShellTaskThreadLoadingPayload(
    task: TaskSummary,
    timeContext: AppShellThreadTimeContext?
): KuiklyThreadScreenPayload {
    val threadId = task.threadId?.value?.takeIf { it.isNotBlank() }
    val parentMessage = task.toAppShellTaskParentMessage()
    return KuiklyThreadScreenPayload(
        instanceId = "shared-shell-task-thread-${threadId ?: task.id.value}",
        title = task.tasksDisplayTitleForAppShell(),
        subtitle = task.channelName,
        status = "loading",
        sourceThreadId = threadId,
        selectedTab = "chat",
        navigationLabel = "Back",
        showModeTabs = false,
        showReplyActions = false,
        showOpenInChannelAction = true,
        originMessage = parentMessage,
        originParentMessageId = parentMessage?.id,
        originStatus = if (parentMessage != null) "loaded" else "idle",
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        showMembersAction = false,
        showSettingsAction = false,
        emptyTitle = "Opening task",
        emptyDetail = "Loading the task conversation."
    )
}

private fun TaskSummary.toAppShellTaskParentMessage(): KuiklyThreadMessageItem? {
    val messageId = messageId?.value?.takeIf { it.isNotBlank() } ?: id.value.takeIf { it.isNotBlank() } ?: return null
    val author = creator ?: assignee
    val authorName = author?.displayName?.takeIf { it.isNotBlank() } ?: "Task"
    return KuiklyThreadMessageItem(
        id = messageId,
        authorId = author?.id?.value ?: "",
        authorName = authorName,
        authorKind =
            when (author?.kind) {
                MemberKind.Agent -> "agent"
                MemberKind.Human -> "human"
                else -> ""
            },
        avatarLabel = author.avatarLabelOrName(authorName),
        body = title,
        taskId = id.value,
        taskNumber = taskNumber,
        taskStatus = status.toTaskStatusWireValue(),
        taskAssigneeName = assignee?.displayName
    )
}

private fun MemberProfile?.avatarLabelOrName(fallbackName: String): String =
    this?.avatarLabel?.takeIf { it.isNotBlank() }
        ?: fallbackName.take(1).uppercase()

private fun TaskStatus.toTaskStatusWireValue(): String =
    when (this) {
        TaskStatus.Todo -> "todo"
        TaskStatus.InProgress -> "in_progress"
        TaskStatus.InReview -> "in_review"
        TaskStatus.Done -> "done"
        TaskStatus.Closed -> "closed"
    }

private fun TaskSummary.tasksDisplayTitleForAppShell(): String =
    taskNumber?.let { "#$it" } ?: title.ifBlank { "Task" }

private fun decodeSharedShellRealtimeEvent(
    eventData: JSONObject,
    mapper: RealtimeEventMapper,
    log: (String) -> Unit
): RealtimeEvent? {
    val typedPayload = eventData.optString("payload").takeIf { it.isNotBlank() }
    if (typedPayload != null) {
        return parseSharedShellRealtimeEvent(typedPayload).getOrElse { throwable ->
            log("shared_realtime_decode_failed ${throwable.message.orEmpty()}")
            null
        }
    }

    val eventName = eventData.optString("eventName").takeIf { it.isNotBlank() } ?: return null
    val rawPayload = eventData.optString("rawPayload").takeIf { it.isNotBlank() } ?: return null
    val event = mapper.map(eventName, rawPayload)
    if (event == null) {
        log("shared_realtime_raw_decode_failed eventName=$eventName")
    }
    return event
}

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
                        },
                    unreadCount = if (json.has("unreadCount")) json.optInt("unreadCount", 0) else null,
                    firstUnreadMessageId =
                        json.optString("firstUnreadMessageId").takeIf { it.isNotBlank() }?.let(::MessageId)
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

private fun Long.updateFrom(event: RealtimeEvent): Long =
    when (event) {
        is Heartbeat -> maxOf(this, event.seq)
        is SyncResumeResponse -> maxOf(this, event.currentSeq)
        is MessageNew -> maxOf(this, event.message.seq ?: 0L)
        is MessageUpdated -> maxOf(this, event.message.seq ?: 0L)
        else -> this
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
        unreadReplyCount =
            json.optInt("unreadReplyCount", json.optInt("unreadCount", 0)),
        firstUnreadReplyMessageId =
            json.optString("firstUnreadReplyMessageId")
                .ifBlank { json.optString("firstUnreadMessageId") }
                .takeIf { it.isNotBlank() }
                ?.let(::MessageId),
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
    val topSafeArea = slockTopSafeAreaPadding()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    start = 24.dp,
                    top = topSafeArea + 24.dp,
                    end = 24.dp,
                    bottom = 24.dp
                ),
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
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
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
        }
        SlockAppBottomTabBarHost(
            items = smokeItems,
            onSelect = { selectedTab = it },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
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

private val homeSearchVisualServer = ServerSummary(
    id = ServerId("srv_design"),
    name = "Slock Design",
    slug = "slock-design",
    role = "owner",
    plan = "pro",
    unreadCount = 24
)

private val homeSearchVisualMember = MemberProfile(
    id = MemberId("user_artin"),
    displayName = "Artin",
    handle = "artin",
    kind = MemberKind.Human,
    presence = Presence.Active
)

private val homeSearchVisualSession = StoredAuthSession(
    user =
        StoredAuthUser(
            id = "user_artin",
            email = "artin@slock.test",
            name = "artin",
            displayName = "Artin",
            emailVerified = true
        ),
    accessToken = "visual-token",
    refreshToken = "visual-refresh",
    selectedServerId = "srv_design",
    selectedServerSlug = "slock-design",
    servers =
        listOf(
            StoredAuthServer(
                id = "srv_design",
                name = "Slock Design",
                slug = "slock-design",
                role = "owner",
                plan = "pro"
            )
        )
)

private val homeSearchVisualChannels = listOf(
    ConversationSummary(
        threadId = ThreadId("thread_design"),
        channelId = ChannelId("ch_design"),
        kind = ConversationKind.Channel,
        name = "design",
        subtitle = "Product and UI decisions",
        unreadCount = 3,
        unreadCountKnown = true,
        lastMessagePreview = "Search parity now covers sender filters and results.",
        lastMessageAt = "10:38",
        lastMessageSenderName = "Product UX Designer",
        memberCount = 18
    ),
    ConversationSummary(
        threadId = ThreadId("thread_android"),
        channelId = ChannelId("ch_android"),
        kind = ConversationKind.Channel,
        name = "android-artifacts",
        subtitle = "Builds and screenshots",
        lastMessagePreview = "Uploaded Android visual report with Search and Saved cases.",
        lastMessageAt = "09:58",
        lastMessageSenderName = "Android Developer",
        memberCount = 8
    )
)

private val homeSearchVisualDirectMessages = listOf(
    ConversationSummary(
        threadId = ThreadId("dm_product"),
        channelId = ChannelId("dm_product"),
        kind = ConversationKind.DirectMessage,
        name = "Product UX Designer",
        subtitle = "Product review owner",
        lastMessagePreview = "Search visual case needs visual review.",
        lastMessageAt = "09:42",
        lastMessageSenderName = "Product UX Designer",
        avatar = AvatarReference.Random("product-ux-designer"),
        presence = Presence.Active,
        agentStatus = AgentStatus.Idle
    )
)

private val homeSearchVisualHomeState = SharedHomeState(
    content =
        SharedHomeContentState.Content(
            snapshot =
                HomeSnapshot(
                    server = homeSearchVisualServer,
                    currentMember = homeSearchVisualMember,
                    channels = homeSearchVisualChannels,
                    directMessages = homeSearchVisualDirectMessages,
                    unreadTotal = 7
                ),
            session = homeSearchVisualSession
        ),
    selectedTopAction = SharedHomeTopAction.Search
)

private val homeCreateChannelVisualMembers = listOf(
        MemberProfile(
            id = MemberId("user_artin"),
            displayName = "Artin",
            handle = "artin",
            kind = MemberKind.Human,
            avatarLabel = "AR",
            presence = Presence.Active
        ),
        MemberProfile(
            id = MemberId("user_product"),
            displayName = "Product UX Designer",
            handle = "Product-UX-Designer",
            kind = MemberKind.Human,
            avatarLabel = "PU",
            presence = Presence.Active
        ),
        MemberProfile(
            id = MemberId("agent_android"),
            displayName = "Android Developer",
            handle = "Android-Developer",
            kind = MemberKind.Agent,
            avatarLabel = "AD",
            presence = Presence.Active,
            agentStatus = AgentStatus.Working
        ),
        MemberProfile(
            id = MemberId("agent_kmp"),
            displayName = "KMP-专家",
            handle = "KMP-专家",
            kind = MemberKind.Agent,
            avatarLabel = "K",
            presence = Presence.Active,
            agentStatus = AgentStatus.Idle
        )
)

private val homeSearchVisualSearchState = SharedHomeSearchPanelState(
        query = "android",
        sort = SearchSort.Relevance,
        timeRange = SearchTimeRange.Any,
    myMessagesOnly = false,
    selectedSenderId = null,
    senderFilters =
        listOf(
            SearchSenderFilterItem(
                id = "user_artin",
                label = "Artin",
                handle = "artin",
                kind = MemberKind.Human,
                isSelf = true,
                avatarWireValue = AvatarReference.Random("artin").wireValue,
                presence = Presence.Active
            ),
            SearchSenderFilterItem(
                id = "agent_android",
                label = "Android Developer",
                handle = "Android-Developer",
                kind = MemberKind.Agent,
                avatarWireValue = AvatarReference.Random("android-developer").wireValue,
                presence = Presence.Active,
                agentStatus = AgentStatus.Working
            )
        ),
    selectedChannelId = null,
    items =
        listOf(
            InboxItem(
                key = "search-thread-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread_msg_agent_reply"),
                channelName = "thread",
                lastMessageId = MessageId("msg_search_thread_1"),
                lastMessagePreview = "Android search parity now covers sender filters, time ranges, and entity results.",
                lastMessageAt = "10:38",
                lastMessageSenderName = "Android Developer",
                threadChannelId = ChannelId("thread_msg_agent_reply"),
                parentChannelId = ChannelId("ch_design"),
                parentChannelName = "design",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("msg_agent_reply"),
                parentMessagePreview = "收到，统一 MessageItem / ThreadMessageItem 的正文、回复数和任务状态。",
                latestActivityMessageId = MessageId("msg_search_thread_1"),
                latestActivityPreview = "Android search parity now covers sender filters, time ranges, and entity results.",
                latestActivityAt = "10:38",
                latestActivitySenderName = "Android Developer",
                replyCount = 5,
                participantIds = listOf(MemberId("user_artin"), MemberId("agent_android")),
                taskNumber = 154,
                taskStatus = TaskStatus.InReview,
                taskClaimedByName = "KMP-专家"
            ),
            InboxItem(
                key = "search-thread-2",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread_msg_agent_reply"),
                channelName = "thread",
                lastMessageId = MessageId("msg_search_thread_2"),
                lastMessagePreview = "Search needs to match Android and React behavior before visual review.",
                lastMessageAt = "10:31",
                lastMessageSenderName = "artin",
                threadChannelId = ChannelId("thread_msg_agent_reply"),
                parentChannelId = ChannelId("ch_design"),
                parentChannelName = "design",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("msg_agent_reply"),
                parentMessagePreview = "收到，统一 MessageItem / ThreadMessageItem 的正文、回复数和任务状态。",
                latestActivityMessageId = MessageId("msg_search_thread_2"),
                latestActivityPreview = "Search needs to match Android and React behavior before visual review.",
                latestActivityAt = "10:31",
                latestActivitySenderName = "artin",
                replyCount = 5,
                participantIds = listOf(MemberId("user_artin"), MemberId("agent_android")),
                taskNumber = 154,
                taskStatus = TaskStatus.InReview,
                taskClaimedByName = "KMP-专家"
            ),
            InboxItem(
                key = "search-channel-1",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("ch_android"),
                channelName = "android-artifacts",
                lastMessageId = MessageId("msg_search_channel_1"),
                lastMessagePreview = "Uploaded Android visual report with Search and Saved cases.",
                lastMessageAt = "09:58",
                lastMessageSenderName = "Android Developer",
                latestActivityMessageId = MessageId("msg_search_channel_1"),
                latestActivityPreview = "Uploaded Android visual report with Search and Saved cases.",
                latestActivityAt = "09:58",
                latestActivitySenderName = "Android Developer"
            )
        ),
    totalCount = 3,
    searched = true,
    hasMore = true,
    nextOffset = 3
)

@Composable
private fun HomeSearchVisualContent(initialOpenFilterId: String? = null) {
    val pager = LocalActivity.current.getPager() as Pager
    val openFilterId =
        initialOpenFilterId ?: pager.pageData.params.optString("openFilterId").takeIf { it.isNotBlank() }
    val channelFilters = remember(homeSearchVisualHomeState) { homeSearchChannelFilters(homeSearchVisualHomeState) }
    Box(modifier = Modifier.width(342.dp).height(620.dp)) {
        SharedHomeSearchPanel(
            state = homeSearchVisualSearchState,
            homeState = homeSearchVisualHomeState,
            onIntent = {},
            onClose = {},
            interactiveSearchField = false,
            includeTopSafeArea = false,
            showSenderFilter = false,
            initialOpenFilterId = if (openFilterId == "channel") null else openFilterId,
            onOpenItem = {},
            onOpenEntity = {},
            bottomContentPadding = 12.dp,
            modifier = Modifier.fillMaxSize()
        )
        if (openFilterId == "channel") {
            SlockFilterPopover(
                anchor = Offset(132f, 58f),
                title = "Channels",
                searchPlaceholder = "Search...",
                searchText = "",
                onSearchChange = {},
                options = channelFilters.map { SlockFilterOption(it.id, it.label, it.icon) },
                selectedIds = emptySet(),
                onToggle = {},
                onClear = {},
                onDismiss = {},
                widthDp = 220f,
                multiSelect = false
            )
        }
    }
}

@Composable
private fun HomeCreateChannelDialogVisualContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
    ) {
        SharedCreateChannelDialog(
            submitting = false,
            membersLoading = false,
            members = homeCreateChannelVisualMembers,
            errorMessage = null,
            initialName = "android-artifacts",
            initialDescription = "Build reports, screenshots, release blockers",
            initialSelectedMemberKeys = setOf(
                homeCreateChannelVisualMembers[0].memberSelectionKey,
                homeCreateChannelVisualMembers[2].memberSelectionKey
            ),
            onDismiss = {},
            onSubmit = { _, _, _, _ -> }
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
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = payload.title,
            subtitle = payload.subtitle.takeIf { it.isNotBlank() } ?: "Preferences",
            iconName = "settings"
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Spacer(Modifier.height(SlockSpacing.Md))
            }
            item {
                SlockVerticalTabBar(
                    items = payload.items,
                    showSelection = payload.showSelection,
                    onSelect = onSelect
                )
            }
            item {
                Spacer(Modifier.height(SlockSpacing.Md))
            }
        }
    }
}
