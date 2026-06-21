package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.components.SlockBrutalInputField
import ai.slock.compose.components.SlockBrutalListRow
import ai.slock.compose.components.SlockAnchoredToolbarChip
import ai.slock.compose.components.SlockBrutalPanelShell
import ai.slock.compose.components.SlockBrutalSearchField
import ai.slock.compose.components.SlockBrutalSectionLabel
import ai.slock.compose.components.SlockBrutalSelectField
import ai.slock.compose.components.SlockBrutalSelectOption
import ai.slock.compose.components.SlockBrutalTextField
import ai.slock.compose.components.SlockLinkButton
import ai.slock.compose.components.SlockToolbarChip
import ai.slock.compose.components.SegmentedControl
import ai.slock.compose.components.SegmentedControlOption
import ai.slock.compose.data.NetworkSlockHomeApi
import ai.slock.compose.data.BridgeSettingsPlatformCapabilities
import ai.slock.compose.data.buildSystemUnreadNotificationRequest
import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.KeyValueChatLayoutPreferenceStore
import ai.slock.compose.data.KeyValueLocalePreferenceStore
import ai.slock.compose.data.KuiklyNotificationPreferenceEvents
import ai.slock.compose.data.MOBILE_ACTION_LANGUAGE
import ai.slock.compose.data.MOBILE_ACTION_MESSAGE_ALIGNMENT
import ai.slock.compose.data.MOBILE_ACTION_NOTIFICATION_PREFERENCE
import ai.slock.compose.data.MOBILE_ACTION_PERMISSION
import ai.slock.compose.data.MOBILE_ACTION_SERVER_MUTE
import ai.slock.compose.data.MOBILE_ACTION_SETTINGS
import ai.slock.compose.data.MOBILE_ACTION_TEST_NOTIFICATION
import ai.slock.compose.data.APPEARANCE_ACTION_MESSAGE_FONT_SIZE
import ai.slock.compose.data.LANGUAGE_REGION_ACTION_AUTO_TRANSLATION
import ai.slock.compose.data.LANGUAGE_REGION_ACTION_LANGUAGE
import ai.slock.compose.data.LANGUAGE_REGION_ACTION_SERVER_TRANSLATION
import ai.slock.compose.data.LANGUAGE_REGION_ACTION_TIME_FORMAT
import ai.slock.compose.data.LANGUAGE_REGION_ACTION_TIMEZONE
import ai.slock.compose.data.MyMessageAlignmentPreference
import ai.slock.compose.data.CONNECTED_APPS_ACTION_DELETE
import ai.slock.compose.data.CONNECTED_APPS_ACTION_INSTALL
import ai.slock.compose.data.CONNECTED_APPS_ACTION_LOGO_CLEAR
import ai.slock.compose.data.CONNECTED_APPS_ACTION_LOGO_UPLOAD
import ai.slock.compose.data.CONNECTED_APPS_ACTION_PUBLISH
import ai.slock.compose.data.CONNECTED_APPS_ACTION_REGISTER
import ai.slock.compose.data.CONNECTED_APPS_ACTION_UNINSTALL
import ai.slock.compose.data.CONNECTED_APPS_ACTION_UPDATE
import ai.slock.compose.data.SettingsComputerRenameRequest
import ai.slock.compose.data.SharedSettingsActionState
import ai.slock.compose.data.NetworkSlockAnnouncementsRemindersApi
import ai.slock.compose.data.NetworkSlockTasksApi
import ai.slock.compose.data.SERVER_ACTION_INVITE_CREATE
import ai.slock.compose.data.SERVER_ACTION_INVITE_REVOKE
import ai.slock.compose.data.SERVER_ACTION_JOIN_LINK_CREATE
import ai.slock.compose.data.SERVER_ACTION_JOIN_LINK_REVOKE
import ai.slock.compose.data.SERVER_ACTION_CONFIRM
import ai.slock.compose.data.SERVER_ACTION_DELETE
import ai.slock.compose.data.SERVER_ACTION_LEAVE
import ai.slock.compose.data.SERVER_ACTION_AGREEMENT_SAVE
import ai.slock.compose.data.SERVER_ACTION_MEMBER_PERMISSIONS_SAVE
import ai.slock.compose.data.SERVER_ACTION_MEMBER_REMOVE
import ai.slock.compose.data.SERVER_ACTION_MEMBER_ROLE
import ai.slock.compose.data.SERVER_ACTION_ONBOARDING_SAVE
import ai.slock.compose.data.SERVER_ACTION_ONBOARDING_TOGGLE
import ai.slock.compose.data.SERVER_ACTION_PROFILE_SAVE
import ai.slock.compose.data.SERVER_ACTION_SIDEBAR_ORDER
import ai.slock.compose.data.SERVER_ACTION_TRANSLATION_TOGGLE
import ai.slock.compose.data.NotificationPreferenceSnapshot
import ai.slock.compose.data.PersistentChatLayoutPreferenceRepository
import ai.slock.compose.data.PersistentLocaleRepository
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthErrorCode
import ai.slock.compose.data.SharedAuthGateIntent
import ai.slock.compose.data.SharedAuthGateState
import ai.slock.compose.data.SharedAuthGateViewModel
import ai.slock.compose.data.SharedAuthResult
import ai.slock.compose.data.SharedComputersStore
import ai.slock.compose.data.SharedTasksDataSource
import ai.slock.compose.data.SharedTasksRouteIntent
import ai.slock.compose.data.SharedTasksViewModel
import ai.slock.compose.data.realtime.RealtimeCursorPolicy
import ai.slock.compose.data.realtime.RealtimeRoutePolicy
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
import ai.slock.compose.data.SharedSettingsRecompositionProfilerSnapshot
import ai.slock.compose.data.SharedSettingsState
import ai.slock.compose.data.SharedSettingsStorageRow
import ai.slock.compose.data.SharedSettingsStorageSnapshot
import ai.slock.compose.data.SharedSettingsViewModel
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.SlockSharedStoreRegistry
import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.DIAGNOSTICS_ACTION_PROFILER_EXPORT
import ai.slock.compose.data.DIAGNOSTICS_ACTION_PROFILER_REPORT
import ai.slock.compose.data.DIAGNOSTICS_ACTION_PROFILER_RESET
import ai.slock.compose.data.DIAGNOSTICS_ACTION_PROFILER_START
import ai.slock.compose.data.DIAGNOSTICS_ACTION_PROFILER_STOP
import ai.slock.compose.data.isRefreshing
import ai.slock.compose.data.runCatchingPreservingCancellation
import ai.slock.compose.data.serverConfirmationItemId
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthUser
import ai.slock.compose.data.realtime.RealtimeEventMapper
import ai.slock.compose.data.parseSlockHome
import ai.slock.compose.data.CreateChannelRequest
import ai.slock.compose.data.DataRequestPriority
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.isAuthSessionBlocker
import ai.slock.compose.data.KuiklySlockAuthTransport
import ai.slock.compose.data.KuiklySlockNetworkClient
import ai.slock.compose.data.MemberRepository
import ai.slock.compose.data.NetworkSlockThreadApi
import ai.slock.compose.data.RealChannelRepository
import ai.slock.compose.data.RealConversationRepository
import ai.slock.compose.data.RealMemberRepository
import ai.slock.compose.data.RealSavedRepository
import ai.slock.compose.data.RealSearchRepository
import ai.slock.compose.data.RealSettingsRepository
import ai.slock.compose.data.SearchIsoBounds
import ai.slock.compose.data.SearchSort
import ai.slock.compose.data.SearchSenderFilterItem
import ai.slock.compose.data.SearchTimeRange
import ai.slock.compose.data.SlockAgentsApiClient
import ai.slock.compose.data.SlockAgentsNetworkApi
import ai.slock.compose.data.SlockComputersApiClient
import ai.slock.compose.data.SlockComputersNetworkApi
import ai.slock.compose.data.SlockIntegrationsActionsPushApiClient
import ai.slock.compose.data.SlockIntegrationsActionsPushNetworkApi
import ai.slock.compose.data.SharedCreateAgentCoordinator
import ai.slock.compose.data.SharedMembersStore
import ai.slock.compose.data.SharedHomeSavedPanelState
import ai.slock.compose.data.SharedHomeSearchPanelState
import ai.slock.compose.data.SharedHomeSearchSavedIntent
import ai.slock.compose.data.SharedHomeSearchSavedViewModel
import ai.slock.compose.data.AccountProfileUpdateRequest
import ai.slock.compose.data.AccountPasswordChangeRequest
import ai.slock.compose.data.AccountProviderLinkStartRequest
import ai.slock.compose.data.AccountSettingsSnapshot
import ai.slock.compose.data.ACCOUNT_ACTION_AVATAR_UPLOAD
import ai.slock.compose.data.ACCOUNT_ACTION_PASSWORD_CHANGE
import ai.slock.compose.data.ACCOUNT_ACTION_PROFILE_SAVE
import ai.slock.compose.data.ACCOUNT_ACTION_PROVIDER_CONNECT
import ai.slock.compose.data.AgentAvatarUploadRequest
import ai.slock.compose.data.AgentMachineAssignmentRequest
import ai.slock.compose.data.AgentResetMode
import ai.slock.compose.data.AgentResetRequest
import ai.slock.compose.data.AttachmentUploadFile
import ai.slock.compose.data.EmptyReason
import ai.slock.compose.data.parseSharedThreadPickedAttachmentFiles
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.ConnectedAccountStatus
import ai.slock.compose.data.InboxItem
import ai.slock.compose.data.InboxSnapshot
import ai.slock.compose.data.kuiklyNotificationPreferenceRepository
import ai.slock.compose.data.kuiklySharedPreferenceStore
import ai.slock.compose.data.savedMessageIdOrNull
import ai.slock.compose.data.SettingsComputerAgentSnapshot
import ai.slock.compose.data.SettingsAppearanceSnapshot
import ai.slock.compose.data.SettingsAppearanceUpdateRequest
import ai.slock.compose.data.SettingsBillingActionSnapshot
import ai.slock.compose.data.SettingsBillingCurrentPlanSnapshot
import ai.slock.compose.data.SettingsBillingNoticeSnapshot
import ai.slock.compose.data.SettingsBillingPlanSnapshot
import ai.slock.compose.data.SettingsBillingSnapshot
import ai.slock.compose.data.SettingsConnectedAppBuiltInSnapshot
import ai.slock.compose.data.SettingsConnectedAppClientSnapshot
import ai.slock.compose.data.SettingsConnectedAppInstalledSnapshot
import ai.slock.compose.data.SettingsConnectedAppMarketplaceSnapshot
import ai.slock.compose.data.SettingsConnectedAppPublishRequest
import ai.slock.compose.data.SettingsConnectedAppRegisterRequest
import ai.slock.compose.data.SettingsConnectedAppSecretSnapshot
import ai.slock.compose.data.SettingsConnectedAppUpdateRequest
import ai.slock.compose.data.SettingsConnectedAppsSnapshot
import ai.slock.compose.data.SettingsComputerDetailSnapshot
import ai.slock.compose.data.SettingsComputerSnapshot
import ai.slock.compose.data.SettingsComputerWorkspaceSnapshot
import ai.slock.compose.data.SettingsComputersSnapshot
import ai.slock.compose.data.SettingsDiagnosticsFeedbackDraft
import ai.slock.compose.data.SettingsMobileSelectionSnapshot
import ai.slock.compose.data.SettingsMobileSelectionOptionSnapshot
import ai.slock.compose.data.SettingsMobileSnapshot
import ai.slock.compose.data.SettingsPlanUsageSnapshot
import ai.slock.compose.data.SettingsLanguageRegionPreferenceUpdateRequest
import ai.slock.compose.data.SettingsLanguageRegionSnapshot
import ai.slock.compose.data.PermissionBlocker
import ai.slock.compose.data.SettingsServerAdminSnapshot
import ai.slock.compose.data.SettingsServerAgreementSnapshot
import ai.slock.compose.data.SettingsServerAgreementUpdateRequest
import ai.slock.compose.data.SettingsServerDeleteRequest
import ai.slock.compose.data.SettingsServerInviteCreateRequest
import ai.slock.compose.data.SettingsServerInviteSnapshot
import ai.slock.compose.data.SettingsServerJoinLinkCreateRequest
import ai.slock.compose.data.SettingsServerJoinLinkSnapshot
import ai.slock.compose.data.SettingsServerMemberRoleUpdateRequest
import ai.slock.compose.data.SettingsServerOnboardingAgentSnapshot
import ai.slock.compose.data.SettingsServerOnboardingSnapshot
import ai.slock.compose.data.SettingsServerOnboardingUpdateRequest
import ai.slock.compose.data.SettingsServerProfileUpdateRequest
import ai.slock.compose.data.SettingsServerSectionSnapshot
import ai.slock.compose.data.SettingsServerSidebarOrderSnapshot
import ai.slock.compose.data.SettingsServerSidebarOrderUpdateRequest
import ai.slock.compose.data.SettingsServerSnapshot
import ai.slock.compose.data.SettingsServerTranslationSnapshot
import ai.slock.compose.data.SettingsServerTranslationUpdateRequest
import ai.slock.compose.markdown.SlockMarkdownBody
import ai.slock.compose.network.SlockNetworkModule
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.Presence
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.AgentCreated
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
import ai.slock.compose.model.ServerDeleted
import ai.slock.compose.model.ServerPlanUpdated
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerMembershipRemoved
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.TaskCreated
import ai.slock.compose.model.TaskDeleted
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.ThreadUpdated
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.avatarWireValueForAvatarSlot
import ai.slock.compose.model.parseSlockTimestampEpochMillisOrNull
import ai.slock.compose.model.SlockTimeFormat24Hour
import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.slockEpochMillisUtcIso
import ai.slock.compose.model.slockStartOfDayUtcIso
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.RowScope
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
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.text.input.ImeAction
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
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
private const val HOME_SAVED_VISUAL_PAGE = "compose_home_saved_visual"
private const val HOME_ACTIVITY_VISUAL_PAGE = "compose_home_activity_visual"
private const val HOME_CREATE_CHANNEL_DIALOG_VISUAL_PAGE = "compose_home_create_channel_dialog_visual"
const val SLOCK_APP_SHELL_PAGE = "compose_slock_app_shell"

private const val AppShellRouteMain = "main"
private const val AppShellRouteThread = "thread"
private const val RetainedThreadRouteEntryLimit = 4
private const val HomeDestinationSearch = "search"
private const val HomeDestinationActivity = "activity"
private const val HomeDestinationSaved = "saved"
private const val SettingsDestinationHome = "home"
private const val SettingsDestinationAccount = "account"
private const val SettingsDestinationComputers = "computers"
private const val SettingsDestinationComputerDetail = "computerDetail"
private const val SettingsDestinationServer = "server"
private const val SettingsDestinationConnectedApps = "connected-apps"
private const val SettingsDestinationBilling = "billing"
private const val SettingsDestinationLanguageRegion = "language-region"
private const val SettingsDestinationAppearance = "appearance"
private const val SettingsDestinationNotifications = "notifications"
private const val SettingsDestinationMobile = "mobile"
private const val SettingsDestinationStorage = "storage"
private const val SettingsDestinationAbout = "about"
private const val SettingsDestinationDiagnostics = "diagnostics"
private const val SettingsDestinationDiagnosticsFeedbackChannel = "diagnostics-feedback-channel"
private const val SettingsDestinationRouter = "router"
private const val PreJoinAgreementBodyMaxLength = 5_000
private const val AppShellRealtimeEvent = "slockRealtimeEvent"
private const val AppShellRealtimeAuthRefreshAction = "authRefreshRequired"
private val SearchControlsHorizontalPadding = 16.dp
private val SearchMessagesHorizontalPadding = 16.dp
private val SearchControlShadowOffset = 2.dp
private val SearchPopoverBorderColor = SlockColor.BorderBlack
private val SearchPopoverShadowColor = SlockColor.ShadowBlack
private val SearchLightRowBorderColor = SlockColor.TextBlack.copy(alpha = 0.3f)
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
                SlockLucideIcon(SlockLucideIconName.Hash, color = "#FF000000", modifier = Modifier.size(15.dp))
            }

        else ->
            SlockAvatarWithStatus(
                statusColor = if (result.showPresence) result.presence.searchStatusColor() else null,
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
    avatarWireValue: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockAvatar(
            avatarWireValue = avatarWireValue ?: "random:${sender.searchAvatarSeed()}",
            label = sender,
            avatarSize = if (compact) 12.dp else 16.dp
        )
        Text(
            text = sender,
            style =
                SlockType.Caption
                    .withSlockFontWeight(FontWeight.Medium)
                    .copy(fontSize = if (compact) 10.sp else 11.sp, lineHeight = if (compact) 12.sp else 13.sp),
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

@Page(HOME_SAVED_VISUAL_PAGE, supportInLocal = true)
internal class ComposeHomeSavedVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSavedVisualContent() }
    }
}

@Page(HOME_ACTIVITY_VISUAL_PAGE, supportInLocal = true)
internal class ComposeHomeActivityVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeActivityVisualContent() }
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
    val heroIconName: SlockLucideIconName = SlockLucideIconName.Settings,
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
    val iconName: SlockLucideIconName = SlockLucideIconName.Settings,
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
            iconName = SlockLucideIconName.User,
            value = "Verified",
            selected = true
        ),
        SlockTabItemPayload(
            key = "computers",
            title = "Computers",
            subtitle = "Connected machines and workspaces",
            iconName = SlockLucideIconName.Monitor
        ),
        SlockTabItemPayload(
            key = "server",
            title = "Server",
            subtitle = "Workspace name, members, invites, permissions",
            iconName = SlockLucideIconName.Users,
            value = "Owner"
        ),
        SlockTabItemPayload(
            key = "billing",
            title = "Billing",
            subtitle = "Plan, usage, invoices",
            iconName = SlockLucideIconName.ShieldCheck
        ),
        SlockTabItemPayload(
            key = "language-region",
            title = "Language & Region",
            subtitle = "Language, timezone, translation",
            iconName = SlockLucideIconName.Languages
        ),
        SlockTabItemPayload(
            key = "mobile",
            title = "Mobile",
            subtitle = "Push notifications, language, layout",
            iconName = SlockLucideIconName.Smartphone
        ),
        SlockTabItemPayload(
            key = "storage",
            title = "Storage",
            subtitle = "Local cache and diagnostics",
            iconName = SlockLucideIconName.Archive
        ),
        SlockTabItemPayload(
            key = "about",
            title = "About",
            subtitle = "Version, diagnostics, feedback",
            iconName = SlockLucideIconName.Bug
        ),
        SlockTabItemPayload(
            key = "router",
            title = "Smoke previews",
            subtitle = "Open Kuikly router and shared test pages",
            iconName = SlockLucideIconName.Route
        )
    )
)

private val settingsVisualItems = listOf(
    SlockTabItemPayload(
        key = "account",
        title = "Account",
        subtitle = "Profile, email, password, connected accounts",
        iconName = SlockLucideIconName.User,
        value = "Verified",
        selected = true
    ),
    SlockTabItemPayload(
        key = "language-region",
        title = "Language & Region",
        subtitle = "Language, timezone, translation",
        iconName = SlockLucideIconName.Languages
    ),
    SlockTabItemPayload(
        key = "appearance",
        title = "Appearance",
        subtitle = "Message font size and reading density",
        iconName = SlockLucideIconName.Type
    ),
    SlockTabItemPayload(
        key = "browser",
        title = "Browser",
        subtitle = "Notifications and local browser state",
        iconName = SlockLucideIconName.AppWindow
    ),
    SlockTabItemPayload(
        key = "server",
        title = "Server",
        subtitle = "Workspace, members, invites, permissions",
        iconName = SlockLucideIconName.Building2,
        value = "Owner"
    ),
    SlockTabItemPayload(
        key = "computers",
        title = "Computers",
        subtitle = "Connected machines and workspaces",
        iconName = SlockLucideIconName.Monitor
    ),
    SlockTabItemPayload(
        key = "release-notes",
        title = "Release Notes",
        subtitle = "Product changes and migration notes",
        iconName = SlockLucideIconName.FileText
    )
)

private val settingsPageVisualPayload = KuiklySharedSettingsPayload(
    instanceId = "visual-settings-page",
    title = "Account",
    detail = "Profile, email, password, connected accounts",
    heroIconName = SlockLucideIconName.User,
    heroLabel = "Artin",
    sections = listOf(
        KuiklySharedSettingsSection(
            title = "Profile",
            rows = listOf(
                KuiklySharedSettingsRow("displayName", SlockLucideIconName.User, "Display name", "Shown across messages, tasks, and mentions.", status = "Artin"),
                KuiklySharedSettingsRow("email", SlockLucideIconName.Mail, "Email", "artin@slock.test", status = "Verified"),
                KuiklySharedSettingsRow("avatar", SlockLucideIconName.Upload, "Avatar", "Upload a custom human avatar.", status = "Ready")
            )
        ),
        KuiklySharedSettingsSection(
            title = "Session",
            rows = listOf(
                KuiklySharedSettingsRow("password", SlockLucideIconName.Shield, "Password", "Change password and connected credentials.", status = "Enabled"),
                KuiklySharedSettingsRow("logout", SlockLucideIconName.LogOut, "Log out", "Sign out of this browser.", status = "Action")
            )
        )
    )
)

private val bottomTabBarSmokeItems = listOf(
    SlockBottomTabItemPayload("home", "HOME", SlockLucideIconName.House),
    SlockBottomTabItemPayload("tasks", "TASKS", SlockLucideIconName.SquareCheckBig, selected = true),
    SlockBottomTabItemPayload("members", "MEMBERS", SlockLucideIconName.Users),
    SlockBottomTabItemPayload("settings", "SETTINGS", SlockLucideIconName.Settings)
)

private fun appShellBottomTabs(selectedKey: String): List<SlockBottomTabItemPayload> =
    listOf(
        SlockBottomTabItemPayload("home", "HOME", SlockLucideIconName.House, selected = selectedKey == "home"),
        SlockBottomTabItemPayload("tasks", "TASKS", SlockLucideIconName.SquareCheckBig, selected = selectedKey == "tasks"),
        SlockBottomTabItemPayload("members", "MEMBERS", SlockLucideIconName.Users, selected = selectedKey == "members"),
        SlockBottomTabItemPayload("settings", "SETTINGS", SlockLucideIconName.Settings, selected = selectedKey == "settings")
    )

private val retainedAppShellTabKeys = listOf("home", "tasks", "members", "settings")

internal fun retainedAppShellSelectedTab(selectedTab: String): String =
    selectedTab.takeIf { it in retainedAppShellTabKeys } ?: "home"

internal fun retainedAppShellTabOrder(selectedTab: String): List<String> {
    val activeTab = retainedAppShellSelectedTab(selectedTab)
    return retainedAppShellTabKeys.filter { it != activeTab } + activeTab
}

private fun isRetainedAppShellTab(tab: String): Boolean =
    tab in retainedAppShellTabKeys

private data class RetainedThreadRouteEntry(
    val key: String,
    val payload: KuiklyThreadScreenPayload
)

internal fun retainedThreadRouteEntryKey(payload: KuiklyThreadScreenPayload): String =
    listOf(
        payload.surfaceKind,
        payload.sourceThreadId.orEmpty(),
        payload.streamChannelId.orEmpty(),
        payload.parentChannelId.orEmpty(),
        payload.parentMessageId.orEmpty(),
        payload.originParentMessageId.orEmpty(),
        payload.focusedMessageId.orEmpty(),
        payload.instanceId
    ).joinToString("|")

private fun upsertRetainedThreadRouteEntry(
    entries: List<RetainedThreadRouteEntry>,
    entry: RetainedThreadRouteEntry,
    limit: Int = RetainedThreadRouteEntryLimit
): List<RetainedThreadRouteEntry> =
    (entries.filterNot { it.key == entry.key } + entry).takeLast(limit)

@Composable
private fun RetainedAppShellTabs(
    selectedTab: String,
    mountedTabs: Set<String>,
    content: @Composable (tab: String, active: Boolean) -> Unit
) {
    val activeTab = retainedAppShellSelectedTab(selectedTab)
    Box(modifier = Modifier.fillMaxSize()) {
        retainedAppShellTabOrder(activeTab).forEach { tab ->
            if (tab == activeTab || tab in mountedTabs) {
                key("retained-$tab") {
                    val active = activeTab == tab
                    RetainedAppShellTabPane(active = active) {
                        content(tab, active)
                    }
                }
            }
        }
    }
}

@Composable
private fun RetainedAppShellTabPane(
    active: Boolean,
    content: @Composable () -> Unit
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .alpha(if (active) 1f else 0f)
    ) {
        content()
    }
}

@Composable
private fun RetainedAppShellRoutePane(
    active: Boolean,
    content: @Composable () -> Unit
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .alpha(if (active) 1f else 0f)
    ) {
        content()
    }
}

@Composable
private fun SlockAppShellContent() {
    // #85 follow-up: seed the moko KBA `StringDesc.localeType`
    // global from the persisted `AppLocale` as early as possible
    // in the app shell lifecycle, so any `stringResource(MR.strings.<key>)`
    // call site (in the home tab, thread page, settings, etc.)
    // resolves against the user's last selection rather than
    // the OS default.
    RememberMokoLocaleSeedFromLocalActivity()
    var selectedTab by remember { mutableStateOf("home") }
    var retainedAppShellTabs by remember { mutableStateOf(setOf("home")) }
    var selectedSettingsDestination by remember { mutableStateOf(SettingsDestinationHome) }
    var selectedSettingsComputerId by remember { mutableStateOf<String?>(null) }
    var selectedHomeDestination by remember { mutableStateOf<String?>(null) }
    var selectedHomeConversationId by remember { mutableStateOf<String?>(null) }
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val authStore = sharedStores.authStore
    val localWriteThrough =
        remember {
            ComposeLocalWriteThrough(
                sharedStores.localDataStore,
                logger = { message -> bridge.logDebug("shared_home_cache $message") }
            )
        }
    val authDataSource =
        remember {
            SharedAuthDataSource(
                transport =
                    KuiklySlockAuthTransport(
                        networkModule = pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME)
                    ),
                sessionStore = authStore,
                diagnosticsLogger = { message -> bridge.log("shared_auth $message") }
            )
        }
    val appShellNetworkClient =
        remember {
            KuiklySlockNetworkClient(
                sessionStore = authStore,
                networkModule = pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME),
                warningLogger = { message -> bridge.log("kuikly_network $message") }
            )
        }
    val homeDataSource =
        remember {
            SharedHomeDataSource(
                sessionStore = authStore,
                api = NetworkSlockHomeApi(appShellNetworkClient),
                announcementsApi = NetworkSlockAnnouncementsRemindersApi(appShellNetworkClient),
                localWriteThrough = localWriteThrough,
                parseHome = { payload, session ->
                    parseSlockHome(payload, session) { message -> bridge.log(message) }
                },
                diagnosticsLogger = { message -> bridge.log("shared_home $message") }
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
    val channelRepository =
        remember {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                warningLogger = { message -> bridge.log("shared_channel_repository $message") }
            )
        }
    val conversationRepository =
        remember {
            RealConversationRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                warningLogger = { message -> bridge.log("shared_conversation_repository $message") }
            )
        }
    val agentsApiClient =
        remember(appShellNetworkClient) {
            SlockAgentsApiClient(SlockAgentsNetworkApi(appShellNetworkClient))
        }
    val computersApiClient =
        remember(appShellNetworkClient) {
            SlockComputersApiClient(SlockComputersNetworkApi(appShellNetworkClient))
        }
    val createAgentCoordinator =
        remember(agentsApiClient, computersApiClient) {
            SharedCreateAgentCoordinator(
                agentsApiClient = agentsApiClient,
                computersApiClient = computersApiClient,
                logger = { message -> bridge.log("app_shell_thread_create_agent $message") }
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
    val threadDataSource =
        remember {
            SharedThreadDataSource(
                networkApi = NetworkSlockThreadApi(appShellNetworkClient),
                sessionStore = authStore,
                localWriteThrough = localWriteThrough,
                memberRepository = memberRepository,
                conversationRepository = conversationRepository,
                channelRepository = channelRepository,
                agentsApiClient = agentsApiClient,
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(
                    SlockIntegrationsActionsPushNetworkApi(appShellNetworkClient)
                ),
                savedStatusRepository = savedRepository,
                createAgentCoordinator = createAgentCoordinator,
                diagnosticsLogger = { message -> bridge.log("shared_thread_api $message") }
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
                api = NetworkSlockTasksApi(appShellNetworkClient),
                diagnosticsLogger = { message -> bridge.log("shared_tasks $message") }
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
    val appShellCoroutineScope = rememberCoroutineScope()
    val threadMembersStore =
        remember(authStore, memberRepository, channelRepository, appShellCoroutineScope) {
            SharedMembersStore(
                authStore = authStore,
                memberRepository = memberRepository,
                channelRepository = channelRepository,
                scope = appShellCoroutineScope,
                logger = { message -> bridge.log("app_shell_$message") }
            )
        }
    val threadComputersStore =
        remember(computersApiClient, appShellCoroutineScope) {
            SharedComputersStore(
                computersApiClient = computersApiClient,
                scope = appShellCoroutineScope,
                logger = { message -> bridge.log("app_shell_$message") }
            )
        }
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
                platformCapabilities = settingsCapabilities,
                diagnosticsLogger = { message -> bridge.log("shared_settings $message") }
            )
        }
    var activeThreadPayload by remember { mutableStateOf<KuiklyThreadScreenPayload?>(null) }
    var activeThreadResolvedAuxiliaryThreadId by remember { mutableStateOf<String?>(null) }
    var threadBackStack by remember { mutableStateOf<List<KuiklyThreadScreenPayload>>(emptyList()) }
    var appShellRoute by remember { mutableStateOf(AppShellRouteMain) }
    var activeThreadRouteKey by remember { mutableStateOf<String?>(null) }
    var retainedThreadRouteEntries by remember { mutableStateOf<List<RetainedThreadRouteEntry>>(emptyList()) }
    var pendingAuthReturnThreadPayload by remember { mutableStateOf<KuiklyThreadScreenPayload?>(null) }
    var authGateMessage by remember { mutableStateOf<String?>(null) }
    var myMessageAlignment by remember { mutableStateOf(MyMessageAlignmentPreference.Default) }
    var createChannelDialogOpen by remember { mutableStateOf(false) }
    var createChannelSubmitting by remember { mutableStateOf(false) }
    var createChannelError by remember { mutableStateOf<String?>(null) }
    var createChannelMembersLoading by remember { mutableStateOf(false) }
    var createChannelMembers by remember { mutableStateOf<List<MemberProfile>>(emptyList()) }
    var pendingDiagnosticsFeedback by remember { mutableStateOf<SettingsDiagnosticsFeedbackDraft?>(null) }
    var pendingDiagnosticsFeedbackReturnDestination by remember { mutableStateOf(SettingsDestinationDiagnostics) }
    val authGateViewModel: SharedAuthGateViewModel =
        viewModel(key = "shared-auth-gate") {
            SharedAuthGateViewModel(authDataSource)
        }
    val authGateState by authGateViewModel.state.collectAsState()
    val homeSidebarOrderRepository =
        remember {
            RealSettingsRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                logger = { message -> bridge.log("shared_home_sidebar_order $message") },
                warningLogger = { message -> bridge.log("shared_home_sidebar_order_warning $message") }
            )
        }
    val homeViewModel: SharedHomeViewModel =
        viewModel(key = "shared-home") {
            SharedHomeViewModel(
                authStore = authStore,
                dataSource = homeDataSource,
                diagnosticsLogger = { message -> bridge.log("shared_home $message") },
                loadSidebarOrder = { homeSidebarOrderRepository.getServerSidebarOrder() },
                updateSidebarOrder = { request -> homeSidebarOrderRepository.updateServerSidebarOrder(request) },
                pinUpdateFailureCallback = { message -> bridge.toast(message) }
            )
        }
    val homeState by homeViewModel.state.collectAsState()
    // #81: forward the typed selected-server badge projection to the host
    // bridge. The host platform MUST use `count` for the system badge
    // and `source` only for diagnostics / logging. See
    // [ai.slock.compose.base.BridgeModule.appBadge] and
    // [ai.slock.compose.data.AppBadgeCount] for the contract.
    LaunchedEffect(homeState.appBadgeCount) {
        val badge = homeState.appBadgeCount
        bridge.appBadge(
            count = badge.count,
            source = badge.source.wire,
            isStale = badge.isStale
        )
    }
    val hostThreadTimeContext = remember(pager.pageData.params.toString()) {
        pager.pageData.params.threadTimeContext()
    }
    val homeSearchSavedViewModel: SharedHomeSearchSavedViewModel =
        viewModel(key = "shared-home-search-saved") {
            SharedHomeSearchSavedViewModel(
                authStore = authStore,
                savedRepository = savedRepository,
                searchRepository = searchRepository,
                memberRepository = memberRepository,
                timeRangeBoundsProvider = { range -> hostThreadTimeContext.toSearchIsoBounds(range) }
            )
        }
    val homeSearchSavedState by homeSearchSavedViewModel.state.collectAsState()
    val tasksViewModel: SharedTasksViewModel =
        viewModel(key = "shared-tasks") {
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = tasksDataSource,
                diagnosticsLogger = { message -> bridge.log("shared_tasks $message") }
            )
        }
    val tasksState by tasksViewModel.state.collectAsState()
    var tasksFirstRenderLoggedKey by remember { mutableStateOf<String?>(null) }
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "shared-settings") {
            SharedSettingsViewModel(settingsDataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    val readySession = (authGateState as? SharedAuthGateState.Ready)?.session
    val latestHomeState by rememberUpdatedState(homeState)
    val latestNotificationPreferenceSnapshot by rememberUpdatedState(notificationPreferenceSnapshot)
    val latestReadySession by rememberUpdatedState(readySession)
    val activeRouteThreadPayload =
        activeThreadPayload.takeIf { appShellRoute == AppShellRouteThread }
    val latestActiveThreadPayload by rememberUpdatedState(activeRouteThreadPayload)
    val homeSurfaceVisible = selectedTab == "home" && appShellRoute == AppShellRouteMain
    LaunchedEffect(homeSurfaceVisible) {
        homeViewModel.dispatch(SharedHomeIntent.SetSurfaceVisible(homeSurfaceVisible))
    }
    val loadedSettingsTimeFormat =
        (settingsState.languageRegion as? ScreenLoadState.Content)?.data?.timeFormat?.selectedKey
    val sessionTimeFormat = readySession?.user?.preferredTimeFormat ?: readySession?.user?.timeFormat
    val threadTimeContext =
        remember(hostThreadTimeContext, loadedSettingsTimeFormat, sessionTimeFormat) {
            hostThreadTimeContext.withTimeFormat(loadedSettingsTimeFormat ?: sessionTimeFormat)
        }
    val shellScope = rememberSlockAppShellScope()
    val realtimeEventMapper = remember { RealtimeEventMapper() }
    var realtimeLastSeq by remember { mutableStateOf(0L) }
    var threadRealtimeEventSequence by remember { mutableStateOf(0L) }
    var threadRealtimeEventSignal by remember { mutableStateOf<SharedThreadRealtimeEventSignal?>(null) }
    var realtimeSessionKey by remember { mutableStateOf<String?>(null) }
    var skipRealtimeDisposeSessionKey by remember { mutableStateOf<String?>(null) }
    var membersAgentDetailRefreshKey by remember { mutableStateOf(0) }

    fun setActiveThreadPayload(payload: KuiklyThreadScreenPayload?) {
        activeThreadResolvedAuxiliaryThreadId = null
        activeThreadPayload = payload
        activeThreadRouteKey =
            payload?.let { nextPayload ->
                val key = retainedThreadRouteEntryKey(nextPayload)
                retainedThreadRouteEntries =
                    upsertRetainedThreadRouteEntry(
                        retainedThreadRouteEntries,
                        RetainedThreadRouteEntry(key, nextPayload)
                    )
                key
            }
    }

    fun resetAppShellSessionState(clearReturnThread: Boolean = false) {
        realtimeLastSeq = 0L
        realtimeSessionKey = null
        membersAgentDetailRefreshKey = 0
        selectedTab = "home"
        retainedAppShellTabs = setOf("home")
        selectedSettingsDestination = SettingsDestinationHome
        selectedSettingsComputerId = null
        selectedHomeDestination = null
        selectedHomeConversationId = null
        setActiveThreadPayload(null)
        threadBackStack = emptyList()
        appShellRoute = AppShellRouteMain
        retainedThreadRouteEntries = emptyList()
        pendingDiagnosticsFeedback = null
        pendingDiagnosticsFeedbackReturnDestination = SettingsDestinationDiagnostics
        if (clearReturnThread) {
            pendingAuthReturnThreadPayload = null
        }
    }

    fun requireSessionReauth(returnPayload: KuiklyThreadScreenPayload? = activeRouteThreadPayload) {
        // Guard: if the auth gate already has a valid session, the 401 that triggered
        // this callback is stale (from before login/re-auth). Don't override the valid state.
        val currentGate = authGateViewModel.state.value
        if (currentGate is SharedAuthGateState.Ready || currentGate is SharedAuthGateState.ServerRequired) {
            return
        }
        pendingAuthReturnThreadPayload =
            returnPayload
                ?.copy(status = "loading", errorCode = null)
                ?: pendingAuthReturnThreadPayload
        authGateMessage = "Session expired. Sign in again to continue."
        authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
        resetAppShellSessionState(clearReturnThread = false)
        bridge.toast("Session expired")
        appShellCoroutineScope.launch {
            authStore.clearSession()
        }
    }

    LaunchedEffect(Unit) {
        authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
    }
    LaunchedEffect(notificationPreferenceRepository) {
        notificationPreferenceSnapshot =
            notificationPreferenceRepository.getNotificationPreference()
    }
    LaunchedEffect(chatLayoutPreferenceRepository) {
        myMessageAlignment =
            chatLayoutPreferenceRepository.getChatLayoutPreference().myMessageAlignment
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
            resetAppShellSessionState(clearReturnThread = false)
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
                message = authGateMessage,
                onSignedIn = { session ->
                    authGateMessage = null
                    authGateViewModel.dispatch(SharedAuthGateIntent.SignedIn(session))
                },
                onOpenDiagnostics = { pager.openAppShellAuthDiagnostics() }
            )
            return
        }
        is SharedAuthGateState.ServerRequired -> {
            AppShellServerGateContent(
                session = gate.session,
                authDataSource = authDataSource,
                onReady = { session ->
                    authGateMessage = null
                    authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(session))
                    resetAppShellSessionState(clearReturnThread = false)
                },
                onSignOut = {
                    authGateMessage = null
                    authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
                    resetAppShellSessionState(clearReturnThread = true)
                }
            )
            return
        }
        is SharedAuthGateState.Error -> {
            AppShellAuthErrorContent(
                message = gate.message,
                onRetry = { authGateViewModel.dispatch(SharedAuthGateIntent.Retry) },
                onSignIn = { authGateViewModel.dispatch(SharedAuthGateIntent.ShowSignIn) },
                onOpenDiagnostics = { pager.openAppShellAuthDiagnostics() }
            )
            return
        }
        is SharedAuthGateState.Ready -> Unit
    }
    DisposableEffect(readySession?.currentServerId, readySession?.accessToken) {
        val serverId = readySession?.currentServerId
        val accessToken = readySession?.accessToken
        val nextSessionKey =
            if (!serverId.isNullOrBlank() && !accessToken.isNullOrBlank()) {
                "$accessToken|$serverId"
            } else {
                null
            }
        if (!serverId.isNullOrBlank() && !accessToken.isNullOrBlank()) {
            val resumeSeq =
                if (realtimeSessionKey != null && realtimeSessionKey != nextSessionKey) {
                    realtimeLastSeq = 0L
                    0L
                } else {
                    realtimeLastSeq
                }
            realtimeSessionKey = nextSessionKey
            bridge.realtimeConnect(
                serverId = serverId,
                accessToken = accessToken,
                lastSeq = resumeSeq
            )
        }
        onDispose {
            if (!serverId.isNullOrBlank()) {
                if (skipRealtimeDisposeSessionKey == nextSessionKey) {
                    skipRealtimeDisposeSessionKey = null
                } else {
                    bridge.realtimeDisconnect("session_changed")
                }
            }
        }
    }

    fun popThreadRoute(): Boolean {
        val routeBefore = appShellRoute
        val canNavigateBackBefore = appShellRoute == AppShellRouteThread
        val threadBackStackSizeBefore = threadBackStack.size
        val hadActiveThread = activeRouteThreadPayload != null
        if (threadBackStack.isNotEmpty()) {
            setActiveThreadPayload(threadBackStack.last())
            threadBackStack = threadBackStack.dropLast(1)
            appShellRoute = AppShellRouteThread
            bridge.log(
                "app_back_pop_thread result=thread_stack routeBefore=$routeBefore " +
                    "canNavigateBack=$canNavigateBackBefore stackBefore=$threadBackStackSizeBefore " +
                    "activeThreadBefore=$hadActiveThread"
            )
            return true
        }
        if (appShellRoute != AppShellRouteThread) {
            bridge.log(
                "app_back_pop_thread result=no_thread_route routeBefore=$routeBefore " +
                    "canNavigateBack=$canNavigateBackBefore stackBefore=$threadBackStackSizeBefore " +
                    "activeThreadBefore=$hadActiveThread"
            )
            return false
        }
        appShellRoute = AppShellRouteMain
        threadBackStack = emptyList()
        bridge.log(
            "app_back_pop_thread result=route_pop popped=true routeBefore=$routeBefore " +
                "canNavigateBack=$canNavigateBackBefore stackBefore=$threadBackStackSizeBefore " +
                "activeThreadBefore=$hadActiveThread"
        )
        return true
    }

    fun openThreadPayload(payload: KuiklyThreadScreenPayload, pushCurrent: Boolean = true) {
        if (pushCurrent && appShellRoute == AppShellRouteThread) {
            activeThreadPayload?.let { currentPayload ->
                threadBackStack = threadBackStack + currentPayload
            }
        }
        setActiveThreadPayload(payload)
        appShellRoute = AppShellRouteThread
    }

    LaunchedEffect(
        readySession?.accessToken,
        readySession?.currentServerId,
        pendingAuthReturnThreadPayload?.instanceId
    ) {
        val returnPayload = pendingAuthReturnThreadPayload ?: return@LaunchedEffect
        if (readySession == null) return@LaunchedEffect
        pendingAuthReturnThreadPayload = null
        openThreadPayload(returnPayload, pushCurrent = false)
    }

    fun appShellThreadPayload(conversation: ConversationSummary, anchorMessageId: String? = null): KuiklyThreadScreenPayload =
        buildAppShellThreadPayload(
            conversation = conversation,
            timeContext = threadTimeContext,
            alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
            anchorMessageId = anchorMessageId
        )

    fun openDiagnosticsFeedbackChannel(
        draft: SettingsDiagnosticsFeedbackDraft,
        conversation: ConversationSummary,
        source: String,
        pushCurrent: Boolean
    ) {
        val channelId = conversation.channelId?.value?.takeIf { it.isNotBlank() } ?: conversation.threadId.value
        if (conversation.kind != ConversationKind.Channel || channelId.isBlank()) {
            bridge.toast("Choose a channel for diagnostics")
            return
        }
        if (!conversation.joined || !conversation.permissions.canSend || !conversation.permissions.canAttach) {
            bridge.toast("You cannot attach diagnostics in this channel")
            return
        }
        bridge.log("diagnostics_feedback_channel_selected channelId=$channelId source=$source")
        selectedHomeConversationId = conversation.threadId.value
        val basePayload = appShellThreadPayload(conversation)
        val uploadingPayload =
            basePayload.withDiagnosticsFeedbackDraft(
                draft = draft,
                attachments =
                    listOf(
                        KuiklyPendingAttachmentItem(
                            id = "diagnostics-uploading",
                            fileName = draft.fileName,
                            sizeLabel = draft.sizeLabel(),
                            mimeType = draft.mimeType,
                            status = "uploading"
                        )
                    ),
                syncBump = 1,
                statusLabel = "Uploading diagnostics...",
                composerMode = "uploading"
            )
        openThreadPayload(uploadingPayload, pushCurrent = pushCurrent)
        appShellCoroutineScope.launch {
            when (
                val uploadResult =
                    threadDataSource.uploadAttachments(
                        channelId = channelId,
                        files = listOf(draft.toAttachmentUploadFile())
                    )
            ) {
                is DataResult.Content -> {
                    val attachments =
                        uploadResult.data.attachments.map { attachment ->
                            KuiklyPendingAttachmentItem(
                                id = attachment.id.value,
                                fileName = attachment.name,
                                sizeLabel = attachment.sizeLabel,
                                mimeType = attachment.mimeType.takeIf { it.isNotBlank() },
                                status = "ready"
                            )
                        }
                    if (attachments.isEmpty()) {
                        bridge.toast("Diagnostics upload returned no attachment")
                        openThreadPayload(
                            uploadingPayload.copy(
                                errorCode = "diagnostics_upload_empty",
                                composer =
                                    uploadingPayload.composer.copy(
                                        composerMode = "failed",
                                        statusLabel = "Diagnostics upload failed"
                                    )
                            ),
                            pushCurrent = false
                        )
                        return@launch
                    }
                    selectedSettingsDestination = pendingDiagnosticsFeedbackReturnDestination
                    pendingDiagnosticsFeedback = null
                    pendingDiagnosticsFeedbackReturnDestination = SettingsDestinationDiagnostics
                    openThreadPayload(
                        basePayload.withDiagnosticsFeedbackDraft(
                            draft = draft,
                            attachments = attachments,
                            syncBump = 2,
                            statusLabel = "Diagnostics attached"
                        ),
                        pushCurrent = false
                    )
                    bridge.toast("Diagnostics attached. Review and send.")
                }
                is DataResult.Error -> {
                    bridge.toast("Diagnostics upload failed: ${uploadResult.error.code}")
                    openThreadPayload(
                        uploadingPayload.copy(
                            errorCode = uploadResult.error.code,
                            composer =
                                uploadingPayload.composer.copy(
                                    composerMode = "failed",
                                    statusLabel = "Diagnostics upload failed"
                                )
                        ),
                        pushCurrent = false
                    )
                }
                is DataResult.Empty -> {
                    bridge.toast("Diagnostics upload failed: ${uploadResult.reason.name}")
                    openThreadPayload(
                        uploadingPayload.copy(
                            errorCode = uploadResult.reason.name,
                            composer =
                                uploadingPayload.composer.copy(
                                    composerMode = "failed",
                                    statusLabel = "Diagnostics upload failed"
                                )
                        ),
                        pushCurrent = false
                    )
                }
                is DataResult.NoPermission -> {
                    bridge.toast("Diagnostics upload failed: no_permission")
                    openThreadPayload(
                        uploadingPayload.copy(
                            errorCode = uploadResult.blocker.code,
                            composer =
                                uploadingPayload.composer.copy(
                                    composerMode = "no_permission",
                                    statusLabel = "Diagnostics upload denied"
                                )
                        ),
                        pushCurrent = false
                    )
                }
            }
        }
    }

    fun openHomeConversation(conversation: ConversationSummary, source: String, anchorMessageId: String? = null, pushCurrent: Boolean = true) {
        bridge.log(
            "home_open_conversation threadId=${conversation.threadId.value} " +
                "kind=${conversation.kind.name} source=$source anchorMessageId=${anchorMessageId.orEmpty()}"
        )
        bridge.performanceMarker(
            phase = "home_open",
            routeName = "kuikly_home",
            event = "instant",
            threadId = conversation.threadId.value,
            metadata =
                mapOf(
                    "kind" to conversation.kind.name,
                    "source" to source,
                    "anchor" to (anchorMessageId != null).toString()
                )
        )
        selectedHomeConversationId = conversation.threadId.value
        val payload = appShellThreadPayload(conversation, anchorMessageId)
        bridge.log(
            "home_open_thread_payload source=$source threadId=${conversation.threadId.value} " +
                "sourceThreadId=${payload.sourceThreadId.orEmpty()} surface=${payload.surfaceKind} " +
                "parentChannelId=${payload.parentChannelId.orEmpty()} parentMessageId=${payload.parentMessageId.orEmpty()} " +
                "anchorMessageId=${anchorMessageId.orEmpty()} originParentMessageId=${payload.originParentMessageId.orEmpty()} " +
                "originStatus=${payload.originStatus} hasOriginMessage=${payload.originMessage != null} " +
                "hasOriginPreview=${!payload.originPreview.isNullOrBlank()}"
        )
        if (pushCurrent && appShellRoute == AppShellRouteThread) {
            activeThreadPayload?.let { threadBackStack = threadBackStack + it }
        }
        setActiveThreadPayload(payload)
        appShellRoute = AppShellRouteThread
    }

    fun openHomeMessage(request: HomeMessageOpenRequest, source: String, pushCurrent: Boolean = true) {
        openHomeConversation(
            conversation = request.conversation,
            source = source,
            anchorMessageId = request.anchorMessageId,
            pushCurrent = pushCurrent
        )
    }

    fun appShellParentMessageContextLoadingPayload(messageId: String): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-parent-message-$messageId",
            title = "Channel",
            status = "loading",
            selectedTab = "chat",
            navigationLabel = "Back",
            showModeTabs = true,
            showReplyActions = true,
            alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
            localOffsetMinutes = threadTimeContext?.localOffsetMinutes ?: 0,
            todayEpochDay = threadTimeContext?.todayEpochDay,
            nowEpochMillis = threadTimeContext?.nowEpochMillis,
            timeFormat = threadTimeContext?.timeFormat ?: SlockTimeFormat24Hour,
            emptyTitle = "Opening channel",
            emptyDetail = "Locating the original message."
        )

    fun popMainRoute(): Boolean {
        if (createChannelDialogOpen) {
            createChannelDialogOpen = false
            createChannelError = null
            return true
        }
        if (pendingDiagnosticsFeedback != null) {
            selectedSettingsDestination = pendingDiagnosticsFeedbackReturnDestination
            pendingDiagnosticsFeedback = null
            pendingDiagnosticsFeedbackReturnDestination = SettingsDestinationDiagnostics
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

    val handleAppShellBack by rememberUpdatedState {
        val routeBefore = appShellRoute
        val canNavigateBackBefore = appShellRoute == AppShellRouteThread
        val threadBackStackSizeBefore = threadBackStack.size
        val hadActiveThread = activeRouteThreadPayload != null
        if (popThreadRoute()) {
            bridge.log(
                "app_back_root result=thread routeBefore=$routeBefore " +
                    "canNavigateBack=$canNavigateBackBefore stackBefore=$threadBackStackSizeBefore " +
                    "activeThreadBefore=$hadActiveThread"
            )
        } else if (popMainRoute()) {
            bridge.log(
                "app_back_root result=main routeBefore=$routeBefore " +
                    "canNavigateBack=$canNavigateBackBefore stackBefore=$threadBackStackSizeBefore " +
                    "activeThreadBefore=$hadActiveThread"
            )
        } else {
            bridge.logWarn(
                "app_back_root result=close_page routeBefore=$routeBefore " +
                    "canNavigateBack=$canNavigateBackBefore stackBefore=$threadBackStackSizeBefore " +
                    "activeThreadBefore=$hadActiveThread"
            )
            bridge.closePage()
        }
    }

    SlockAppShellRootBackHandler(
        onLifecycleLog = { event ->
            bridge.log(
                "app_back_root_handler $event route=$appShellRoute " +
                    "canNavigateBack=${appShellRoute == AppShellRouteThread} stack=${threadBackStack.size} " +
                    "activeThread=${activeRouteThreadPayload != null}"
            )
        }
    ) { handleAppShellBack() }

    LaunchedEffect(selectedTab) {
        if (selectedTab == "tasks") {
            val hasSnapshot = tasksState.loadState is ScreenLoadState.Content
            bridge.performanceMarker(
                phase = "tasks_screen_enter",
                routeName = "kuikly_tasks",
                event = "instant",
                metadata =
                    mapOf(
                        "hasSnapshot" to hasSnapshot.toString(),
                        "refreshing" to tasksState.refreshing.toString(),
                        "filter" to tasksState.selectedFilter?.name.orEmpty()
                    )
            )
            tasksViewModel.dispatch(SharedTasksRouteIntent.Start)
        }
    }
    LaunchedEffect(selectedTab, tasksState.loadState, tasksState.refreshing) {
        if (selectedTab != "tasks") {
            tasksFirstRenderLoggedKey = null
            return@LaunchedEffect
        }
        val (result, taskCount) =
            when (val loadState = tasksState.loadState) {
                is ScreenLoadState.Content -> "content" to loadState.data.tasks.size
                is ScreenLoadState.Empty -> "empty:${loadState.reason.name}" to 0
                is ScreenLoadState.Error -> "error:${loadState.error.code}" to 0
                is ScreenLoadState.NoPermission -> "no_permission:${loadState.blocker.code}" to 0
                ScreenLoadState.Loading -> "loading" to 0
            }
        val key = "$result:$taskCount:${tasksState.refreshing}"
        if (tasksFirstRenderLoggedKey == key) {
            return@LaunchedEffect
        }
        tasksFirstRenderLoggedKey = key
        bridge.performanceMarker(
            phase = "tasks_first_render",
            routeName = "kuikly_tasks",
            event = "instant",
            metadata =
                mapOf(
                    "result" to result,
                    "tasks" to taskCount.toString(),
                    "refreshing" to tasksState.refreshing.toString()
                )
        )
        bridge.log("tasks_first_render result=$result tasks=$taskCount refreshing=${tasksState.refreshing}")
    }

    fun loadCreateChannelMembers() {
        createChannelMembersLoading = true
        appShellCoroutineScope.launch {
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
        visibility: String,
        agentMemberIds: List<String>,
        userMemberIds: List<String>
    ) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || createChannelSubmitting) {
            return
        }
        createChannelSubmitting = true
        createChannelError = null
        appShellCoroutineScope.launch {
            try {
                when (
                    val result =
                        channelRepository.createChannel(
                            CreateChannelRequest(
                                name = trimmedName,
                                description = description?.trim()?.takeIf { it.isNotBlank() },
                                visibility = visibility.trim().ifBlank { "public" },
                                agentMemberIds = agentMemberIds,
                                userMemberIds = userMemberIds
                            )
                        )
                ) {
                    is DataResult.Content -> {
                        val channel = result.data.channel
                        selectedTab = "home"
                        retainedAppShellTabs = retainedAppShellTabs + "home"
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

    LaunchedEffect(activeThreadResolvedAuxiliaryThreadId) {
        activeThreadResolvedAuxiliaryThreadId
            ?.takeIf { it.isNotBlank() }
            ?.let { bridge.realtimeJoinChannel(it) }
    }

    fun handleRealtimeAuthRefreshRequest(eventData: JSONObject) {
        val requestedServerId = eventData.optString("serverId").takeIf { it.isNotBlank() } ?: return
        val reason = eventData.optString("reason").takeIf { it.isNotBlank() } ?: "reconnect"
        val currentSession =
            (authGateViewModel.state.value as? SharedAuthGateState.Ready)?.session
                ?: readySession
                ?: run {
                    bridge.realtimeAuthRefreshFinished(reason, success = false)
                    return
                }
        val currentServerId =
            currentSession.currentServerId
                ?: run {
                    bridge.realtimeAuthRefreshFinished(reason, success = false)
                    return
                }
        if (currentServerId != requestedServerId) {
            bridge.log("shared_realtime_auth_refresh_skip reason=$reason requestedServerId=$requestedServerId currentServerId=$currentServerId")
            bridge.realtimeAuthRefreshFinished(reason, success = false)
            return
        }
        appShellCoroutineScope.launch {
            when (val result = authDataSource.refreshRealtimeAuth(reason)) {
                is SharedAuthResult.Success -> {
                    val refreshed = result.data
                    val refreshedServerId = refreshed.currentServerId
                    if (refreshedServerId != requestedServerId || refreshed.accessToken.isBlank()) {
                        bridge.log("shared_realtime_auth_refresh_skip reason=$reason result=server_mismatch")
                        bridge.realtimeAuthRefreshFinished(reason, success = false)
                        return@launch
                    }
                    skipRealtimeDisposeSessionKey = realtimeSessionKey
                    authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(refreshed))
                    realtimeSessionKey = "${refreshed.accessToken}|$refreshedServerId"
                    bridge.realtimeConnect(
                        serverId = refreshedServerId,
                        accessToken = refreshed.accessToken,
                        lastSeq = realtimeLastSeq,
                        forceReconnect = true
                    )
                    bridge.realtimeAuthRefreshFinished(reason, success = true)
                    bridge.log("shared_realtime_auth_refresh_success reason=$reason serverId=$refreshedServerId")
                }
                SharedAuthResult.Unauthenticated -> {
                    bridge.realtimeAuthRefreshFinished(reason, success = false)
                    bridge.log("shared_realtime_auth_refresh_failed reason=$reason result=unauthenticated")
                }
                is SharedAuthResult.Failure -> {
                    bridge.realtimeAuthRefreshFinished(reason, success = false)
                    bridge.log("shared_realtime_auth_refresh_failed reason=$reason code=${result.code}")
                }
            }
        }
    }
    val latestRealtimeAuthRefreshHandler by rememberUpdatedState<(JSONObject) -> Unit>(
        { eventData -> handleRealtimeAuthRefreshRequest(eventData) }
    )
    val latestResetAppShellSessionState by rememberUpdatedState<(Boolean) -> Unit>(
        { clearReturnThread -> resetAppShellSessionState(clearReturnThread = clearReturnThread) }
    )

    DisposableEffect(
        pager,
        homeViewModel,
        realtimeEventMapper,
        settingsViewModel
    ) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != AppShellRealtimeEvent) return
                    if (eventData.optString("action") == AppShellRealtimeAuthRefreshAction) {
                        latestRealtimeAuthRefreshHandler(eventData)
                        return
                    }
                    val serverId = eventData.optString("serverId").takeIf { it.isNotBlank() } ?: return
                    val event = decodeSharedShellRealtimeEvent(eventData, realtimeEventMapper, bridge::log) ?: return
                    val activeSession = latestReadySession
                    val activeThread = latestActiveThreadPayload
                    if (
                        event is ServerMembershipRemoved &&
                        event.serverId.value == activeSession?.currentServerId
                    ) {
                        bridge.log("shared_realtime_server_membership_removed serverId=${event.serverId.value}")
                        bridge.realtimeDisconnect("server_membership_removed")
                        latestResetAppShellSessionState(true)
                        appShellCoroutineScope.launch {
                            authStore.updateSelectedServer(serverId = null, serverSlug = null)
                            authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
                        }
                        return
                    }
                    if (
                        event is ServerDeleted &&
                        event.serverId.value == activeSession?.currentServerId
                    ) {
                        bridge.log("shared_realtime_server_deleted serverId=${event.serverId.value}")
                        bridge.realtimeDisconnect("server_deleted")
                        latestResetAppShellSessionState(true)
                        appShellCoroutineScope.launch {
                            authStore.updateSelectedServer(serverId = null, serverSlug = null)
                            authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
                        }
                        return
                    }
                    realtimeLastSeq = realtimeLastSeq.updateFrom(event)
                    if (RealtimeRoutePolicy.shouldForwardToActiveThread(event)) {
                        threadRealtimeEventSequence += 1
                        threadRealtimeEventSignal =
                            SharedThreadRealtimeEventSignal(
                                sequence = threadRealtimeEventSequence,
                                event = event
                            )
                    }
                    if (event is DirectMessageCreated) {
                        bridge.realtimeJoinChannel(event.channelId.value)
                    }
                    if (event is ServerPlanUpdated) {
                        bridge.log("shared_realtime_server_plan_updated serverId=${event.serverId.value} plan=${event.plan}")
                        membersAgentDetailRefreshKey += 1
                    }
                    val shouldRefreshMemberDetail =
                        when (event) {
                            is MemberPresenceUpdated -> event.kind == MemberKind.Agent && activeThread == null
                            is AgentCreated -> true
                            is RealtimeRefreshRequired ->
                                (
                                    event.scope == RealtimeRefreshScope.ServerMembers ||
                                        event.scope == RealtimeRefreshScope.Agents ||
                                        event.scope == RealtimeRefreshScope.Machines
                                )
                            else -> false
                        }
                    if (shouldRefreshMemberDetail) {
                        membersAgentDetailRefreshKey += 1
                    }
                    if (event is MessageNew && latestNotificationPreferenceSnapshot.enabled) {
                        val conversation = latestHomeState.systemNotificationConversation(event.message)
                        val request =
                            buildSystemUnreadNotificationRequest(
                                serverId = serverId,
                                message = event.message,
                                currentMemberId = activeSession?.user?.id,
                                isVisibleThread = event.message.isVisibleInThread(activeThread),
                                conversationName = conversation?.name,
                                conversationKind = conversation?.kind,
                                currentMemberHandle = activeSession?.user?.email
                            )
                        if (request != null) {
                            appShellCoroutineScope.launch {
                                val result = settingsCapabilities.postUnreadNotification(request)
                                if (!result.ok) {
                                    bridge.log("system_unread_notification_failed code=${result.code} message=${result.message}")
                                }
                            }
                        }
                    }
                    if (RealtimeRoutePolicy.shouldEmitRouteMarker(event, activeThread = activeThread != null)) {
                        bridge.performanceMarker(
                            phase = "realtime_batch_apply",
                            routeName = if (activeThread == null) "kuikly_home" else "kuikly_thread",
                            event = "instant",
                            threadId = activeThread?.sourceThreadId ?: activeThread?.instanceId,
                            metadata =
                                mapOf(
                                    "type" to event.performanceMarkerName(),
                                    "activeThread" to (activeThread != null).toString()
                                )
                        )
                    }
                    homeViewModel.dispatch(SharedHomeIntent.ApplyRealtimeEvent(serverId, event))
                    // #79: forward machine-scope realtime events to the
                    // settings store so the ComputerDetail page refreshes
                    // status / daemonVersion / runtimes / lastHeartbeat
                    // without a full app reload. The settings store
                    // itself filters to RealtimeRefreshScope.Machines and
                    // the active route.
                    settingsViewModel.dispatch(SharedSettingsIntent.ApplyRealtimeEvent(serverId, event))
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }

    val retainedThreadRouteEntriesForRender =
        activeThreadRouteKey
            ?.let { activeKey ->
                retainedThreadRouteEntries.filterNot { it.key == activeKey } +
                    retainedThreadRouteEntries.filter { it.key == activeKey }
            }
            ?: retainedThreadRouteEntries

    val mainRouteContent: @Composable () -> Unit = {
        RetainedAppShellRoutePane(active = appShellRoute == AppShellRouteMain) {
            SlockAppShellSurface(
                shellScope = shellScope,
                bottomTabs = appShellBottomTabs(selectedTab),
                onBottomTabSelected = {
                    val nextTab = retainedAppShellSelectedTab(it)
                    selectedTab = nextTab
                    if (isRetainedAppShellTab(nextTab)) {
                        retainedAppShellTabs = retainedAppShellTabs + nextTab
                    }
                    if (nextTab == "home") {
                        selectedHomeDestination = null
                    }
                    if (nextTab != "settings") {
                        selectedSettingsDestination = SettingsDestinationHome
                        selectedSettingsComputerId = null
                        pendingDiagnosticsFeedback = null
                        pendingDiagnosticsFeedbackReturnDestination = SettingsDestinationDiagnostics
                    }
                }
            ) {
                    val tasksTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        PerformanceSceneEffect(bridge, "kuikly_tasks", active = active)
                        SharedTasksScreen(
                            state = tasksState,
                            onIntent = tasksViewModel::dispatch,
                            onOpenTask = { task ->
                                appShellCoroutineScope.launch {
                                    openAppShellTaskThread(
                                        task = task,
                                        dataSource = threadDataSource,
                                        timeContext = threadTimeContext,
                                        alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                                        onOpenThreadPayload = { payload -> openThreadPayload(payload) },
                                        onReplaceThreadPayload = { payload -> openThreadPayload(payload, pushCurrent = false) },
                                        onAuthRequired = { payload -> requireSessionReauth(payload) },
                                        onFailure = { bridge.toast("Unable to open task") }
                                    )
                                }
                            }
                        )
                    }
                    val homeTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        PerformanceSceneEffect(bridge, "kuikly_home", active = active)
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
                                    openHomeConversation(conversation, "home")
                                },
                                bottomContentPadding = shellScope.bottomTabBarContentInset
                            )
                        } else if (selectedHomeDestination == HomeDestinationActivity) {
                            SharedHomeActivityScreen(
                                bridge = bridge,
                                authStore = authStore,
                                onClose = { selectedHomeDestination = null },
                                onOpenMessage = { request ->
                                    openHomeMessage(request, "home_activity")
                                },
                                bottomContentPadding = shellScope.bottomTabBarContentInset,
                                timeContext = threadTimeContext,
                                shellScope = shellScope
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
                                    openHomeMessage(item.toHomeMessageOpenRequest(), "home_search", pushCurrent = true)
                                },
                                onOpenEntity = { entity ->
                                    selectedHomeDestination = null
                                    val threadId = entity.threadId
                                    if (threadId != null) {
                                        val conversation = entity.conversation
                                        if (conversation != null) {
                                            openHomeConversation(conversation, "home_search_entity")
                                        } else {
                                            selectedHomeConversationId = threadId
                                        }
                                    } else {
                                        selectedTab = "members"
                                        retainedAppShellTabs = retainedAppShellTabs + "members"
                                        selectedHomeConversationId = null
                                    }
                                },
                                bottomContentPadding = shellScope.bottomTabBarContentInset,
                                timeContext = threadTimeContext,
                                shellScope = shellScope
                            )
                        } else if (selectedHomeDestination == HomeDestinationSaved) {
                            SharedHomeSavedPanel(
                                state = homeSearchSavedState.saved,
                                onIntent = homeSearchSavedViewModel::dispatch,
                                onClose = { selectedHomeDestination = null },
                                onOpenItem = { item ->
                                    openHomeMessage(item.toHomeMessageOpenRequest(), "home_saved", pushCurrent = true)
                                },
                                bottomContentPadding = shellScope.bottomTabBarContentInset,
                                timeContext = threadTimeContext,
                                shellScope = shellScope
                            )
                        } else {
                            SlockCenteredLoading(message = "Opening...")
                        }
                    }
                    val membersTabContent: @Composable (active: Boolean) -> Unit = {
                        SharedMembersScreen(
                            bridge = bridge,
                            authStore = authStore,
                            memberRepository = memberRepository,
                            createAgentCoordinator = createAgentCoordinator,
                            settingsCapabilities = settingsCapabilities,
                            agentDetailRefreshKey = membersAgentDetailRefreshKey,
                            timeContext = threadTimeContext
                        )
                    }
                    val settingsTabContent: @Composable (active: Boolean) -> Unit = {
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
                            onMessageAlignmentSelected = {
                                myMessageAlignment = it
                            },
                            onSettingsAction = { action, itemId ->
                                bridge.settingsEvent("shared-shell-settings", action, itemId)
                            },
                            onDiagnosticsFeedbackPrepared = { draft ->
                                pendingDiagnosticsFeedbackReturnDestination =
                                    selectedSettingsDestination
                                        .takeIf { it != SettingsDestinationDiagnosticsFeedbackChannel }
                                        ?: SettingsDestinationDiagnostics
                                pendingDiagnosticsFeedback = draft
                                selectedSettingsDestination = SettingsDestinationDiagnosticsFeedbackChannel
                                if (homeState.content !is SharedHomeContentState.Content) {
                                    homeViewModel.dispatch(SharedHomeIntent.Start)
                                }
                                bridge.toast("请选择 channel")
                            },
                            diagnosticsFeedbackDraft = pendingDiagnosticsFeedback,
                            diagnosticsFeedbackHomeState = homeState,
                            onDiagnosticsFeedbackBack = {
                                selectedSettingsDestination = pendingDiagnosticsFeedbackReturnDestination
                                pendingDiagnosticsFeedback = null
                                pendingDiagnosticsFeedbackReturnDestination = SettingsDestinationDiagnostics
                            },
                            onDiagnosticsFeedbackRefresh = {
                                homeViewModel.dispatch(SharedHomeIntent.Refresh)
                            },
                            onDiagnosticsFeedbackChannelSelected = { conversation ->
                                pendingDiagnosticsFeedback?.let { draft ->
                                    openDiagnosticsFeedbackChannel(
                                        draft = draft,
                                        conversation = conversation,
                                        source = "diagnostics_channel_page",
                                        pushCurrent = true
                                    )
                                    homeViewModel.dispatch(SharedHomeIntent.SelectConversation(conversation.threadId.value))
                                }
                            },
                            bottomContentPadding = shellScope.bottomTabBarContentInset,
                            timeContext = threadTimeContext,
                            onLogout = {
                                authGateMessage = null
                                authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
                                resetAppShellSessionState(clearReturnThread = true)
                            }
                        )
                    }
                    RetainedAppShellTabs(
                        selectedTab = selectedTab,
                        mountedTabs = retainedAppShellTabs
                    ) { tab, active ->
                        when (tab) {
                            "tasks" -> tasksTabContent(active)
                            "members" -> membersTabContent(active)
                            "settings" -> settingsTabContent(active)
                            else -> homeTabContent(active)
                        }
                    }
            }
            Box(modifier = Modifier.fillMaxSize()) {
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
                        onSubmit = { name, description, visibility, agentMemberIds, userMemberIds ->
                            submitCreateChannel(name, description, visibility, agentMemberIds, userMemberIds)
                        }
                    )
                }
            }
        }
    }

    val threadRouteContent: @Composable () -> Unit = {
        val hasActiveThreadRouteEntry =
            activeThreadRouteKey?.let { activeKey ->
                retainedThreadRouteEntriesForRender.any { it.key == activeKey }
            } == true
        retainedThreadRouteEntriesForRender.forEach { entry ->
            key("route-thread-${entry.key}") {
                val threadActive = appShellRoute == AppShellRouteThread && entry.key == activeThreadRouteKey
                RetainedAppShellRoutePane(active = threadActive) {
                    SlockBackHandler(
                        enabled = threadActive,
                        label = "app_shell_thread",
                        onLifecycleLog = { event ->
                            bridge.log(
                                "app_back_thread_handler $event route=$appShellRoute " +
                                    "canNavigateBack=${appShellRoute == AppShellRouteThread} stack=${threadBackStack.size} " +
                                    "activeThread=${activeRouteThreadPayload != null}"
                            )
                        }
                    ) {
                        val handled = popThreadRoute()
                        bridge.log(
                            "app_back_thread_handler result=$handled route=$appShellRoute " +
                                "canNavigateBack=${appShellRoute == AppShellRouteThread} stack=${threadBackStack.size} " +
                                "activeThread=${activeRouteThreadPayload != null}"
                        )
                    }
                    SharedThreadRouteContent(
                        initialPayload = entry.payload,
                        bridge = bridge,
                        dataSource = threadDataSource,
                        authStore = authStore,
                        tasksDataSource = tasksDataSource,
                        computersStore = threadComputersStore,
                        membersStore = threadMembersStore,
                        onBack = { popThreadRoute() },
                        onOpenThreadPayload = { payload -> openThreadPayload(payload) },
                        onReplaceThreadPayload = { payload -> openThreadPayload(payload, pushCurrent = false) },
                        onAuthRequired = { requireSessionReauth(entry.payload) },
                        onOpenParentThread = {
                            if (threadBackStack.isNotEmpty()) {
                                setActiveThreadPayload(threadBackStack.last())
                                threadBackStack = threadBackStack.dropLast(1)
                                appShellRoute = AppShellRouteThread
                                true
                            } else {
                                val messageId = activeThreadPayload?.originParentMessageId?.takeIf { it.isNotBlank() }
                                if (messageId == null) {
                                    false
                                } else {
                                    val loadingPayload = appShellParentMessageContextLoadingPayload(messageId)
                                    openThreadPayload(loadingPayload)
                                    appShellCoroutineScope.launch {
                                        when (val result = threadDataSource.openMessageContext(messageId, loadingPayload)) {
                                            is DataResult.Content -> openThreadPayload(result.data, pushCurrent = false)
                                            is DataResult.Empty,
                                            is DataResult.Error -> {
                                                openThreadPayload(
                                                    loadingPayload.copy(status = "error", errorCode = "thread_parent_context_failed"),
                                                    pushCurrent = false
                                                )
                                                bridge.toast("Unable to open channel")
                                            }
                                            is DataResult.NoPermission -> {
                                                if (result.blocker.isAuthSessionBlocker()) {
                                                    requireSessionReauth(entry.payload)
                                                    return@launch
                                                }
                                                openThreadPayload(
                                                    loadingPayload.copy(status = "error", errorCode = "thread_parent_context_failed"),
                                                    pushCurrent = false
                                                )
                                                bridge.toast("Unable to open channel")
                                            }
                                        }
                                    }
                                    true
                                }
                            }
                        },
                        realtimeEventSignal = if (threadActive) threadRealtimeEventSignal else null,
                        active = threadActive,
                        onResolvedAuxiliaryThreadIdChanged = { resolvedThreadId ->
                            if (threadActive) {
                                activeThreadResolvedAuxiliaryThreadId = resolvedThreadId
                            }
                        }
                    )
                }
            }
        }
        if (appShellRoute == AppShellRouteThread && !hasActiveThreadRouteEntry) {
            SlockCenteredLoading(message = "Opening thread...")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (appShellRoute == AppShellRouteMain) {
            key("route-threads") { threadRouteContent() }
            key("route-main") { mainRouteContent() }
        } else {
            key("route-main") { mainRouteContent() }
            key("route-threads") { threadRouteContent() }
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
    selectedDestination: String,
    selectedComputerId: String?,
    onDestinationChange: (String) -> Unit,
    onOpenComputer: (String) -> Unit,
    onOpenRouter: () -> Unit,
    onMessageAlignmentSelected: (MyMessageAlignmentPreference) -> Unit,
    onSettingsAction: (String, String?) -> Unit,
    onDiagnosticsFeedbackPrepared: (SettingsDiagnosticsFeedbackDraft) -> Unit,
    diagnosticsFeedbackDraft: SettingsDiagnosticsFeedbackDraft?,
    diagnosticsFeedbackHomeState: SharedHomeState,
    onDiagnosticsFeedbackBack: () -> Unit,
    onDiagnosticsFeedbackRefresh: () -> Unit,
    onDiagnosticsFeedbackChannelSelected: (ConversationSummary) -> Unit,
    bottomContentPadding: Dp,
    timeContext: SlockTimeContext?,
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
                runCatchingPreservingCancellation { authDataSource.logout() }
                onLogout()
            }
            is SharedSettingsEffect.OpenUrl -> {
                settingsCapabilities.openUrl(effect.url)
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
                val draft = settingsCapabilities.prepareDiagnosticsFeedback()
                if (draft != null) {
                    onDiagnosticsFeedbackPrepared(draft)
                } else if (settingsCapabilities.sendFeedback()) {
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

    var serverInviteEmail by remember { mutableStateOf("") }
    var serverProfileName by remember { mutableStateOf("") }
    var serverDeleteConfirmation by remember { mutableStateOf("") }
    var serverJoinLinkMaxUses by remember { mutableStateOf("") }
    var serverJoinLinkExpiresAt by remember { mutableStateOf("") }
    var serverAgreementEnabled by remember { mutableStateOf(false) }
    var serverAgreementTitle by remember { mutableStateOf("") }
    var serverAgreementBody by remember { mutableStateOf("") }
    var serverHideHumansFromMembers by remember { mutableStateOf(false) }
    var serverOnboardingAgentId by remember { mutableStateOf("") }
    var serverOnboardingGreetingEnabled by remember { mutableStateOf(true) }
    var connectedAppsTab by remember { mutableStateOf("marketplace") }
    var connectedAppEditingId by remember { mutableStateOf("") }
    var connectedAppName by remember { mutableStateOf("") }
    var connectedAppClientId by remember { mutableStateOf("") }
    var connectedAppDescription by remember { mutableStateOf("") }
    var connectedAppHomepageUrl by remember { mutableStateOf("") }
    var connectedAppReturnUrl by remember { mutableStateOf("") }
    var connectedAppManifestUrl by remember { mutableStateOf("") }
    var connectedAppPublishCategory by remember { mutableStateOf("productivity") }
    var connectedAppPublishDataAccess by remember { mutableStateOf("") }
    var connectedAppsMarketplaceSearch by remember { mutableStateOf("") }
    var connectedAppsMarketplaceCategory by remember { mutableStateOf(SETTINGS_CONNECTED_APPS_CATEGORY_ALL) }
    var connectedAppsSelectedMarketplaceId by remember { mutableStateOf<String?>(null) }
    var connectedAppsSelectedBuiltInId by remember { mutableStateOf<String?>(null) }
    var connectedAppsConfirmTarget by remember { mutableStateOf<SettingsConnectedAppsConfirmTarget?>(null) }
    var languageRegionLanguageDraft by remember { mutableStateOf("") }
    var languageRegionDisplayDraft by remember { mutableStateOf("translated") }
    var languageRegionTimezoneDraft by remember { mutableStateOf("") }
    var languageRegionTimeFormatDraft by remember { mutableStateOf("") }
    var appearanceMessageFontSizeDraft by remember { mutableStateOf("md") }
    val currentServerSnapshot = settingsState.server.currentContent()
    val currentConnectedAppsSnapshot = settingsState.connectedApps.currentContent()
    val currentLanguageRegionSnapshot = settingsState.languageRegion.currentContent()
    val currentAppearanceSnapshot = settingsState.appearance.currentContent()
    val currentServerProfile = settingsState.server.currentContent()?.profile
    val currentServerName = currentServerProfile?.name ?: session?.currentServerSummary()?.name.orEmpty()
    val currentServerSlug = currentServerProfile?.slug ?: session?.currentServerSummary()?.slug.orEmpty()
    val currentAgreement = currentServerSnapshot?.agreement
    val currentOnboarding = currentServerSnapshot?.onboarding
    LaunchedEffect(currentServerName) {
        serverProfileName = currentServerName
    }
    LaunchedEffect(currentServerProfile?.hideHumansFromMembers) {
        serverHideHumansFromMembers = currentServerProfile?.hideHumansFromMembers == true
    }
    LaunchedEffect(currentAgreement?.enabled, currentAgreement?.title, currentAgreement?.bodyMarkdown) {
        serverAgreementEnabled = currentAgreement?.enabled == true
        serverAgreementTitle = currentAgreement?.title.orEmpty()
        serverAgreementBody = currentAgreement?.bodyMarkdown.orEmpty()
    }
    LaunchedEffect(currentOnboarding?.onboardingAgentId, currentOnboarding?.agentAllChannelGreetingEnabled) {
        serverOnboardingAgentId = currentOnboarding?.onboardingAgentId.orEmpty()
        serverOnboardingGreetingEnabled = currentOnboarding?.agentAllChannelGreetingEnabled ?: true
    }
    LaunchedEffect(
        currentLanguageRegionSnapshot?.browserLanguage?.selectedKey,
        currentLanguageRegionSnapshot?.autoTranslation?.enabled,
        currentLanguageRegionSnapshot?.timezone?.selectedKey,
        currentLanguageRegionSnapshot?.timeFormat?.selectedKey
    ) {
        currentLanguageRegionSnapshot?.let { snapshot ->
            languageRegionLanguageDraft = snapshot.browserLanguage.selectedKey
            languageRegionDisplayDraft = if (snapshot.autoTranslation.enabled) "translated" else "original"
            languageRegionTimezoneDraft = snapshot.timezone.selectedKey
            languageRegionTimeFormatDraft = snapshot.timeFormat.selectedKey
        }
    }
    LaunchedEffect(currentAppearanceSnapshot?.messageBodyFontSize?.selectedKey) {
        currentAppearanceSnapshot?.let { snapshot ->
            appearanceMessageFontSizeDraft = snapshot.messageBodyFontSize.selectedKey
        }
    }
    LaunchedEffect(currentServerSlug) {
        serverDeleteConfirmation = ""
        serverJoinLinkMaxUses = ""
        serverJoinLinkExpiresAt = ""
    }
    LaunchedEffect(currentConnectedAppsSnapshot?.registered?.map { it.id }) {
        if (connectedAppEditingId.isNotBlank() && currentConnectedAppsSnapshot?.registered?.none { it.id == connectedAppEditingId } == true) {
            connectedAppEditingId = ""
            connectedAppName = ""
            connectedAppClientId = ""
            connectedAppDescription = ""
            connectedAppHomepageUrl = ""
            connectedAppReturnUrl = ""
            connectedAppManifestUrl = ""
            connectedAppPublishCategory = "productivity"
            connectedAppPublishDataAccess = ""
        }
    }
    LaunchedEffect(
        currentConnectedAppsSnapshot?.marketplace?.map { it.id },
        currentConnectedAppsSnapshot?.builtIn?.map { it.id },
        currentConnectedAppsSnapshot?.installed?.map { it.id },
        currentConnectedAppsSnapshot?.registered?.map { it.id }
    ) {
        val snapshot = currentConnectedAppsSnapshot ?: return@LaunchedEffect
        if (connectedAppsMarketplaceCategory != SETTINGS_CONNECTED_APPS_CATEGORY_ALL &&
            snapshot.marketplace.none { it.category == connectedAppsMarketplaceCategory }
        ) {
            connectedAppsMarketplaceCategory = SETTINGS_CONNECTED_APPS_CATEGORY_ALL
        }
        connectedAppsSelectedMarketplaceId?.let { id ->
            if (snapshot.marketplace.none { it.id == id }) connectedAppsSelectedMarketplaceId = null
        }
        connectedAppsSelectedBuiltInId?.let { id ->
            if (snapshot.builtIn.none { it.id == id }) connectedAppsSelectedBuiltInId = null
        }
        connectedAppsConfirmTarget?.let { target ->
            val exists =
                when (target.kind) {
                    SettingsConnectedAppsConfirmKind.DeleteClient -> snapshot.registered.any { it.id == target.id }
                    SettingsConnectedAppsConfirmKind.UninstallMarketplace ->
                        snapshot.marketplace.any { it.id == target.id } || snapshot.installed.any { it.id == target.id }
                    else -> false
                }
            if (!exists) connectedAppsConfirmTarget = null
        }
    }

    if (selectedDestination == SettingsDestinationHome) {
        SettingsHomeContent(
            payload = appShellSettingsHomePayload(session, selectedDestination),
            bottomContentPadding = bottomContentPadding,
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
            if (selectedDestination == SettingsDestinationDiagnosticsFeedbackChannel) {
                onDiagnosticsFeedbackBack()
            } else if (selectedDestination == SettingsDestinationComputerDetail) {
                onDestinationChange(SettingsDestinationComputers)
            } else {
                onDestinationChange(SettingsDestinationHome)
            }
        }
    ) {
        when (selectedDestination) {
            SettingsDestinationAccount ->
                SettingsAccountContent(
                    settingsState.account.toAccountPayload(session, settingsState.actionState),
                    applyTopSafeArea = false,
                    bottomContentPadding = bottomContentPadding,
                    onPasswordChange = { currentPassword, newPassword ->
                        onSettingsIntent(
                            SharedSettingsIntent.ChangeAccountPassword(
                                AccountPasswordChangeRequest(
                                    currentPassword = currentPassword,
                                    newPassword = newPassword
                                )
                            )
                        )
                    }
                ) { action, value ->
                    when (action) {
                        "logout" -> onSettingsIntent(SharedSettingsIntent.Logout)
                        "uploadAvatar" -> onSettingsIntent(SharedSettingsIntent.RequestAccountAvatarUpload)
                        "connectProvider" ->
                            value?.takeIf { it.isNotBlank() }?.let { providerId ->
                                onSettingsIntent(
                                    SharedSettingsIntent.ConnectAccountProvider(
                                        AccountProviderLinkStartRequest(providerId = providerId)
                                    )
                                )
                            }
                        "saveProfile" -> {
                            val account = (settingsState.account as? ScreenLoadState.Content<AccountSettingsSnapshot>)?.data
                            val displayName = value.orEmpty().trim()
                            if (account != null) {
                                onSettingsIntent(
                                    SharedSettingsIntent.UpdateProfile(
                                        AccountProfileUpdateRequest(
                                            displayName = displayName.ifBlank { account.profile.displayName },
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
                SettingsComputersContent(
                    settingsState.computers.toComputersPayload(
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Computers),
                        timeContext = timeContext
                    ),
                    bottomContentPadding = bottomContentPadding
                ) { action, itemId ->
                    when (action) {
                        "openComputer" -> itemId?.takeIf { it.isNotBlank() }?.let(onOpenComputer)
                        "addComputer" -> onSettingsAction(action, itemId)
                        else -> onSettingsAction(action, itemId)
                    }
                }
            SettingsDestinationComputerDetail ->
                SettingsComputerDetailContent(
                    payload = settingsState.computerDetail.toComputerDetailPayload(selectedComputerId, timeContext),
                    actionState = settingsState.actionState?.toComputerActionState(),
                    bottomContentPadding = bottomContentPadding
                ) { action, itemId ->
                    when (action) {
                        "retryWorkspaces", "retryAgents" ->
                            selectedComputerId?.takeIf { it.isNotBlank() }?.let { onSettingsIntent(SharedSettingsIntent.OpenComputer(it)) }
                        "renameComputer" -> itemId?.takeIf { it.isNotBlank() }?.let { machineId ->
                            onSettingsIntent(SharedSettingsIntent.RenameComputer(SettingsComputerRenameRequest(machineId = machineId, name = "")))
                        }
                        "deleteComputer" -> itemId?.takeIf { it.isNotBlank() }?.let { machineId ->
                            onSettingsIntent(SharedSettingsIntent.DeleteComputer(machineId))
                        }
                        "rotateComputerKey" -> itemId?.takeIf { it.isNotBlank() }?.let { machineId ->
                            onSettingsIntent(SharedSettingsIntent.RotateComputerKey(machineId))
                        }
                        "retryComputerAction" -> {
                            val lastAction = settingsState.actionState?.action
                            val lastItemId = settingsState.actionState?.itemId
                            when (lastAction) {
                                "renameComputer" -> lastItemId?.let { id ->
                                    onSettingsIntent(SharedSettingsIntent.RenameComputer(SettingsComputerRenameRequest(machineId = id, name = "")))
                                }
                                "deleteComputer" -> lastItemId?.let { id ->
                                    onSettingsIntent(SharedSettingsIntent.DeleteComputer(id))
                                }
                                "rotateComputerKey" -> lastItemId?.let { id ->
                                    onSettingsIntent(SharedSettingsIntent.RotateComputerKey(id))
                                }
                                else -> Unit
                            }
                        }
                        "openAgent" -> onSettingsAction(action, itemId)
                        else -> onSettingsAction(action, itemId)
                    }
                }
            SettingsDestinationStorage ->
                SettingsStorageContent(
                    settingsState.storage.toStoragePayload(
                        actionState = settingsState.actionState,
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Storage)
                    ),
                    bottomContentPadding = bottomContentPadding
                ) { action, target ->
                    when (action) {
                        "refreshStorage" -> onSettingsIntent(SharedSettingsIntent.RefreshStorage)
                        "clearStorage" -> target?.takeIf { it.isNotBlank() }?.let { onSettingsIntent(SharedSettingsIntent.ClearStorage(it)) }
                        else -> onSettingsAction(action, target)
                    }
                }
            SettingsDestinationBilling ->
                SettingsBillingContent(
                    settingsState.billing.toBillingPayload(session, timeContext),
                    bottomContentPadding = bottomContentPadding
                ) { _, _ -> }
            SettingsDestinationLanguageRegion ->
                SettingsLanguageRegionContent(
                    state = settingsState.languageRegion,
                    actionState = settingsState.actionState,
                    refreshing = settingsState.isRefreshing(SharedSettingsDestination.LanguageRegion),
                    languageDraft = languageRegionLanguageDraft,
                    displayDraft = languageRegionDisplayDraft,
                    timezoneDraft = languageRegionTimezoneDraft,
                    timeFormatDraft = languageRegionTimeFormatDraft,
                    bottomContentPadding = bottomContentPadding,
                    onLanguageDraftChange = { languageRegionLanguageDraft = it },
                    onDisplayDraftChange = { languageRegionDisplayDraft = it },
                    onTimezoneDraftChange = { languageRegionTimezoneDraft = it },
                    onTimeFormatDraftChange = { languageRegionTimeFormatDraft = it },
                    onServerTranslationChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetLanguageRegionServerTranslation(enabled))
                    },
                    onSaveLanguage = {
                        val selected = languageRegionLanguageDraft.takeIf { it.isNotBlank() }
                        onSettingsIntent(
                            SharedSettingsIntent.UpdateLanguageRegionPreference(
                                if (selected == null || selected == "other") {
                                    SettingsLanguageRegionPreferenceUpdateRequest(
                                        clearPreferredLanguage = true,
                                        autoTranslationEnabled = languageRegionDisplayDraft == "translated"
                                    )
                                } else {
                                    SettingsLanguageRegionPreferenceUpdateRequest(
                                        preferredLanguage = selected,
                                        autoTranslationEnabled = languageRegionDisplayDraft == "translated"
                                    )
                                }
                            )
                        )
                    },
                    onSaveTime = {
                        val timezone = languageRegionTimezoneDraft.takeIf { it.isNotBlank() }
                        val timeFormat = languageRegionTimeFormatDraft.takeIf { it.isNotBlank() }
                        if (timezone != null || timeFormat != null) {
                            onSettingsIntent(
                                SharedSettingsIntent.UpdateLanguageRegionPreference(
                                    SettingsLanguageRegionPreferenceUpdateRequest(
                                        preferredTimezone = timezone,
                                        preferredTimeFormat = timeFormat
                                    )
                                )
                            )
                        }
                    },
                    onRetry = { onSettingsIntent(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.LanguageRegion)) }
                )
            SettingsDestinationAppearance ->
                SettingsAppearanceContent(
                    state = settingsState.appearance,
                    actionState = settingsState.actionState,
                    refreshing = settingsState.isRefreshing(SharedSettingsDestination.Appearance),
                    fontSizeDraft = appearanceMessageFontSizeDraft,
                    bottomContentPadding = bottomContentPadding,
                    onFontSizeDraftChange = { appearanceMessageFontSizeDraft = it },
                    onSave = {
                        onSettingsIntent(
                            SharedSettingsIntent.UpdateAppearance(
                                SettingsAppearanceUpdateRequest(appearanceMessageFontSizeDraft)
                            )
                        )
                    },
                    onRetry = { onSettingsIntent(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Appearance)) }
                )
            SettingsDestinationServer ->
                SharedSettingsServerContent(
                    payload = settingsState.server.toServerPayload(
                        session = session,
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Server),
                        actionState = settingsState.actionState,
                        timeContext = timeContext
                    ),
                    inviteEmail = serverInviteEmail,
                    onInviteEmailChange = { serverInviteEmail = it },
                    joinLinkMaxUses = serverJoinLinkMaxUses,
                    onJoinLinkMaxUsesChange = { serverJoinLinkMaxUses = it },
                    joinLinkExpiresAt = serverJoinLinkExpiresAt,
                    onJoinLinkExpiresAtChange = { serverJoinLinkExpiresAt = it },
                    profileName = serverProfileName,
                    onProfileNameChange = { serverProfileName = it },
                    agreementEnabled = serverAgreementEnabled,
                    onAgreementEnabledChange = { serverAgreementEnabled = it },
                    agreementTitle = serverAgreementTitle,
                    onAgreementTitleChange = { serverAgreementTitle = it },
                    agreementBody = serverAgreementBody,
                    onAgreementBodyChange = { serverAgreementBody = it },
                    hideHumansFromMembers = serverHideHumansFromMembers,
                    onHideHumansFromMembersChange = { serverHideHumansFromMembers = it },
                    onboardingAgentId = serverOnboardingAgentId,
                    onOnboardingAgentIdChange = { serverOnboardingAgentId = it },
                    onboardingGreetingEnabled = serverOnboardingGreetingEnabled,
                    onOnboardingGreetingEnabledChange = { serverOnboardingGreetingEnabled = it },
                    serverSnapshot = currentServerSnapshot,
                    timeContext = timeContext,
                    serverRole = session?.currentServerSummary()?.role.orEmpty(),
                    expectedServerSlug = currentServerSlug,
                    deleteConfirmationName = serverDeleteConfirmation,
                    onDeleteConfirmationNameChange = { serverDeleteConfirmation = it },
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onCreateJoinLink = { request ->
                        onSettingsIntent(SharedSettingsIntent.CreateServerJoinLink(request))
                    },
                ) { action, itemId ->
                    when (action) {
                        SERVER_ACTION_PROFILE_SAVE ->
                            itemId?.trim()?.takeIf { it.isNotBlank() }?.let {
                                onSettingsIntent(SharedSettingsIntent.UpdateServerProfile(SettingsServerProfileUpdateRequest(name = it)))
                            }
                        SERVER_ACTION_AGREEMENT_SAVE ->
                            onSettingsIntent(
                                SharedSettingsIntent.UpdateServerAgreement(
                                    SettingsServerAgreementUpdateRequest(
                                        enabled = serverAgreementEnabled,
                                        title = serverAgreementTitle.trim(),
                                        bodyMarkdown = serverAgreementBody
                                    )
                                )
                            )
                        SERVER_ACTION_MEMBER_PERMISSIONS_SAVE ->
                            onSettingsIntent(
                                SharedSettingsIntent.UpdateServerProfile(
                                    SettingsServerProfileUpdateRequest(hideHumansFromMembers = serverHideHumansFromMembers)
                                )
                            )
                        SERVER_ACTION_INVITE_CREATE ->
                            itemId?.takeIf { it.isNotBlank() }?.let {
                                onSettingsIntent(SharedSettingsIntent.CreateServerInvite(SettingsServerInviteCreateRequest(it)))
                            }
                        SERVER_ACTION_INVITE_REVOKE ->
                            itemId?.takeIf { it.isNotBlank() }?.let {
                                if (settingsState.actionState.isServerConfirmation(SERVER_ACTION_INVITE_REVOKE, it)) {
                                    onSettingsIntent(SharedSettingsIntent.RevokeServerInvite(it))
                                } else {
                                    onSettingsIntent(SharedSettingsIntent.RequestServerConfirmation(SERVER_ACTION_INVITE_REVOKE, it))
                                }
                            }
                        SERVER_ACTION_JOIN_LINK_CREATE ->
                            onSettingsIntent(SharedSettingsIntent.CreateServerJoinLink(SettingsServerJoinLinkCreateRequest()))
                        SERVER_ACTION_JOIN_LINK_REVOKE ->
                            itemId?.takeIf { it.isNotBlank() }?.let {
                                if (settingsState.actionState.isServerConfirmation(SERVER_ACTION_JOIN_LINK_REVOKE, it)) {
                                    onSettingsIntent(SharedSettingsIntent.RevokeServerJoinLink(it))
                                } else {
                                    onSettingsIntent(SharedSettingsIntent.RequestServerConfirmation(SERVER_ACTION_JOIN_LINK_REVOKE, it))
                                }
                            }
                        SERVER_ACTION_ONBOARDING_SAVE ->
                            onSettingsIntent(
                                SharedSettingsIntent.UpdateServerOnboarding(
                                    SettingsServerOnboardingUpdateRequest(
                                        onboardingAgentId = serverOnboardingAgentId.trim().takeIf { it.isNotBlank() },
                                        clearOnboardingAgent = serverOnboardingAgentId.trim().isBlank(),
                                        agentAllChannelGreetingEnabled = serverOnboardingGreetingEnabled
                                    )
                                )
                            )
                        SERVER_ACTION_ONBOARDING_TOGGLE ->
                            itemId?.toBooleanStrictOrNull()?.let {
                                onSettingsIntent(SharedSettingsIntent.UpdateServerOnboarding(SettingsServerOnboardingUpdateRequest(setupModalReminderOptOut = it)))
                            }
                        SERVER_ACTION_TRANSLATION_TOGGLE ->
                            itemId?.toBooleanStrictOrNull()?.let {
                                onSettingsIntent(SharedSettingsIntent.UpdateServerTranslation(SettingsServerTranslationUpdateRequest(it)))
                            }
                        SERVER_ACTION_SIDEBAR_ORDER ->
                            itemId?.takeIf { it.isNotBlank() }?.let {
                                onSettingsIntent(SharedSettingsIntent.UpdateServerSidebarOrder(SettingsServerSidebarOrderUpdateRequest(channelSortMode = it)))
                            }
                        SERVER_ACTION_MEMBER_ROLE ->
                            itemId?.split(":", limit = 2)?.takeIf { it.size == 2 }?.let {
                                onSettingsIntent(SharedSettingsIntent.UpdateServerMemberRole(SettingsServerMemberRoleUpdateRequest(it[0], it[1])))
                            }
                        SERVER_ACTION_MEMBER_REMOVE ->
                            itemId?.takeIf { it.isNotBlank() }?.let {
                                if (settingsState.actionState.isServerConfirmation(SERVER_ACTION_MEMBER_REMOVE, it)) {
                                    onSettingsIntent(SharedSettingsIntent.RemoveServerMember(it))
                                } else {
                                    onSettingsIntent(SharedSettingsIntent.RequestServerConfirmation(SERVER_ACTION_MEMBER_REMOVE, it))
                                }
                            }
                        SERVER_ACTION_LEAVE ->
                            if (settingsState.actionState.isServerConfirmation(SERVER_ACTION_LEAVE)) {
                                onSettingsIntent(SharedSettingsIntent.LeaveServer)
                            } else {
                                onSettingsIntent(SharedSettingsIntent.RequestServerConfirmation(SERVER_ACTION_LEAVE))
                            }
                        SERVER_ACTION_DELETE ->
                            itemId?.trim()?.takeIf { it == currentServerSlug && it.isNotBlank() }?.let {
                                onSettingsIntent(SharedSettingsIntent.DeleteServer(SettingsServerDeleteRequest(it)))
                            }
                        else -> onSettingsAction(action, itemId)
                    }
                }
            SettingsDestinationConnectedApps ->
                SettingsConnectedAppsContent(
                    state = settingsState.connectedApps,
                    selectedTab = connectedAppsTab,
                    onSelectedTabChange = { connectedAppsTab = it },
                    editingId = connectedAppEditingId,
                    name = connectedAppName,
                    clientId = connectedAppClientId,
                    description = connectedAppDescription,
                    homepageUrl = connectedAppHomepageUrl,
                    returnUrl = connectedAppReturnUrl,
                    manifestUrl = connectedAppManifestUrl,
                    publishCategory = connectedAppPublishCategory,
                    publishDataAccess = connectedAppPublishDataAccess,
                    marketplaceSearch = connectedAppsMarketplaceSearch,
                    marketplaceCategory = connectedAppsMarketplaceCategory,
                    selectedMarketplaceId = connectedAppsSelectedMarketplaceId,
                    selectedBuiltInId = connectedAppsSelectedBuiltInId,
                    confirmTarget = connectedAppsConfirmTarget,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onNameChange = { connectedAppName = it },
                    onClientIdChange = { connectedAppClientId = it },
                    onDescriptionChange = { connectedAppDescription = it },
                    onHomepageUrlChange = { connectedAppHomepageUrl = it },
                    onReturnUrlChange = { connectedAppReturnUrl = it },
                    onManifestUrlChange = { connectedAppManifestUrl = it },
                    onPublishCategoryChange = { connectedAppPublishCategory = it },
                    onPublishDataAccessChange = { connectedAppPublishDataAccess = it },
                    onMarketplaceSearchChange = { connectedAppsMarketplaceSearch = it },
                    onMarketplaceCategoryChange = { connectedAppsMarketplaceCategory = it },
                    onOpenMarketplaceDetail = { connectedAppsSelectedMarketplaceId = it },
                    onDismissMarketplaceDetail = { connectedAppsSelectedMarketplaceId = null },
                    onOpenBuiltInDetail = { connectedAppsSelectedBuiltInId = it },
                    onDismissBuiltInDetail = { connectedAppsSelectedBuiltInId = null },
                    onStartEdit = { app ->
                        connectedAppEditingId = app.id
                        connectedAppName = app.name
                        connectedAppClientId = app.clientId
                        connectedAppDescription = app.description.orEmpty()
                        connectedAppHomepageUrl = app.homepageUrl.orEmpty()
                        connectedAppReturnUrl = app.returnUrl.orEmpty()
                        connectedAppManifestUrl = app.agentManifestUrl.orEmpty()
                        connectedAppPublishCategory = app.category.orEmpty().ifBlank { "productivity" }
                        connectedAppPublishDataAccess = app.dataAccessSummary.orEmpty()
                    },
                    onCancelEdit = {
                        connectedAppEditingId = ""
                        connectedAppName = ""
                        connectedAppClientId = ""
                        connectedAppDescription = ""
                        connectedAppHomepageUrl = ""
                        connectedAppReturnUrl = ""
                        connectedAppManifestUrl = ""
                        connectedAppPublishCategory = "productivity"
                        connectedAppPublishDataAccess = ""
                    },
                    onRegister = { request ->
                        onSettingsIntent(SharedSettingsIntent.RegisterConnectedApp(request))
                    },
                    onUpdate = { request ->
                        onSettingsIntent(SharedSettingsIntent.UpdateConnectedApp(request))
                    },
                    onUploadLogo = { id ->
                        onSettingsIntent(SharedSettingsIntent.RequestConnectedAppLogoUpload(id))
                    },
                    onClearLogo = { id ->
                        onSettingsIntent(SharedSettingsIntent.ClearConnectedAppLogo(id))
                    },
                    onRequestDelete = { id ->
                        connectedAppsConfirmTarget =
                            SettingsConnectedAppsConfirmTarget(
                                SettingsConnectedAppsConfirmKind.DeleteClient,
                                id
                            )
                    },
                    onConfirmDelete = { id ->
                        connectedAppsConfirmTarget = null
                        onSettingsIntent(SharedSettingsIntent.DeleteConnectedApp(id))
                    },
                    onPublish = { request ->
                        onSettingsIntent(SharedSettingsIntent.RequestConnectedAppPublish(request))
                    },
                    onInstall = { id ->
                        onSettingsIntent(SharedSettingsIntent.InstallMarketplaceApp(id))
                    },
                    onRequestUninstall = { id ->
                        connectedAppsConfirmTarget =
                            SettingsConnectedAppsConfirmTarget(
                                SettingsConnectedAppsConfirmKind.UninstallMarketplace,
                                id
                            )
                    },
                    onConfirmUninstall = { id ->
                        connectedAppsConfirmTarget = null
                        onSettingsIntent(SharedSettingsIntent.UninstallMarketplaceApp(id))
                    },
                    onDismissConfirm = { connectedAppsConfirmTarget = null },
                    onCopySecret = { secret ->
                        settingsCapabilities.bridgeCopyToClipboard(secret)
                    },
                    onOpenUrl = { url ->
                        settingsCapabilities.openUrl(url)
                    },
                    onRefresh = {
                        onSettingsIntent(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.ConnectedApps))
                    }
                )
            SettingsDestinationNotifications ->
                SettingsNotificationsContent(
                    state = settingsState.mobile,
                    actionState = settingsState.actionState,
                    refreshing = settingsState.isRefreshing(SharedSettingsDestination.Notifications),
                    bottomContentPadding = bottomContentPadding,
                    onNotificationPreferenceChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetNotificationPreference(enabled))
                    },
                    onServerMuteChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetServerMute(enabled))
                    },
                    onPermission = { onSettingsIntent(SharedSettingsIntent.RequestNotificationPermission) },
                    onSettings = { onSettingsIntent(SharedSettingsIntent.OpenNotificationSettings) },
                    onTest = { onSettingsIntent(SharedSettingsIntent.SendTestNotification) },
                    onRetry = { onSettingsIntent(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Notifications)) }
                )
            SettingsDestinationMobile ->
                SharedSettingsSectionContent(
                    settingsState.mobile.toMobilePayload(
                        actionState = settingsState.actionState,
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Mobile)
                    ),
                    bottomContentPadding = bottomContentPadding
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
                                onMessageAlignmentSelected(MyMessageAlignmentPreference.fromStorageKey(it))
                                onSettingsIntent(SharedSettingsIntent.SetMessageAlignment(it))
                            }
                        else -> onSettingsAction(action, itemId)
                    }
                }
            SettingsDestinationAbout ->
                SharedSettingsAboutContent(
                    state = settingsState.about,
                    fallback = appShellAboutFallback(session),
                    bottomContentPadding = bottomContentPadding,
                    onOpenDiagnostics = { onDestinationChange(SettingsDestinationDiagnostics) },
                    onCopyBuild = { summary ->
                        settingsCapabilities.bridgeCopyToClipboard(summary)
                    },
                    onOpenSlock = { settingsCapabilities.openUrl("https://app.raft.build") },
                    onSendFeedback = { onSettingsIntent(SharedSettingsIntent.SendFeedback) }
                )
            SettingsDestinationDiagnostics ->
                SharedSettingsDiagnosticsContent(
                    state = settingsState.diagnostics,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onCopyLogs = { onSettingsIntent(SharedSettingsIntent.CopyDiagnostics) },
                    onShareLogs = { onSettingsIntent(SharedSettingsIntent.ShareDiagnostics) },
                    onClearLogs = { onSettingsIntent(SharedSettingsIntent.ClearDiagnostics) },
                    onStartProfiler = { onSettingsIntent(SharedSettingsIntent.StartRecompositionProfiler) },
                    onStopProfiler = { onSettingsIntent(SharedSettingsIntent.StopRecompositionProfiler) },
                    onResetProfiler = { onSettingsIntent(SharedSettingsIntent.ResetRecompositionProfiler) },
                    onRefreshProfilerReport = { onSettingsIntent(SharedSettingsIntent.RefreshRecompositionProfilerReport) },
                    onExportProfilerReport = { onSettingsIntent(SharedSettingsIntent.ExportRecompositionProfilerReport) },
                    onSendFeedback = { onSettingsIntent(SharedSettingsIntent.SendFeedback) }
                )
            SettingsDestinationDiagnosticsFeedbackChannel ->
                if (diagnosticsFeedbackDraft == null) {
        SettingsSharedPlaceholder(
            title = "请选择 channel",
            detail = "Diagnostics feedback is no longer pending.",
            iconName = SlockLucideIconName.Hash,
            bottomContentPadding = bottomContentPadding
        )
                } else {
                    SharedDiagnosticsFeedbackChannelPage(
                        state = diagnosticsFeedbackHomeState,
                        bottomContentPadding = bottomContentPadding,
                        onRefresh = onDiagnosticsFeedbackRefresh,
                        onSelectChannel = onDiagnosticsFeedbackChannelSelected
                    )
                }
            else ->
        SettingsSharedPlaceholder(
            title = "Settings",
            detail = "This Settings section is not available on mobile yet.",
            iconName = SlockLucideIconName.Settings,
            bottomContentPadding = bottomContentPadding
        )
        }
    }
}

@Composable
private fun SharedSettingsSectionContent(
    payload: KuiklySharedSettingsPayload,
    bottomContentPadding: Dp = SlockSpacing.Lg,
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
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SharedSettingsServerContent(
    payload: KuiklySharedSettingsPayload,
    inviteEmail: String,
    onInviteEmailChange: (String) -> Unit,
    joinLinkMaxUses: String,
    onJoinLinkMaxUsesChange: (String) -> Unit,
    joinLinkExpiresAt: String,
    onJoinLinkExpiresAtChange: (String) -> Unit,
    profileName: String,
    onProfileNameChange: (String) -> Unit,
    agreementEnabled: Boolean,
    onAgreementEnabledChange: (Boolean) -> Unit,
    agreementTitle: String,
    onAgreementTitleChange: (String) -> Unit,
    agreementBody: String,
    onAgreementBodyChange: (String) -> Unit,
    hideHumansFromMembers: Boolean,
    onHideHumansFromMembersChange: (Boolean) -> Unit,
    onboardingAgentId: String,
    onOnboardingAgentIdChange: (String) -> Unit,
    onboardingGreetingEnabled: Boolean,
    onOnboardingGreetingEnabledChange: (Boolean) -> Unit,
    serverSnapshot: SettingsServerSnapshot?,
    timeContext: SlockTimeContext?,
    serverRole: String,
    expectedServerSlug: String,
    deleteConfirmationName: String,
    onDeleteConfirmationNameChange: (String) -> Unit,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onCreateJoinLink: (SettingsServerJoinLinkCreateRequest) -> Unit,
    onAction: (String, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SharedSettingsHero(payload) }
        item {
            SettingsServerProfileFormCard(
                profile = serverSnapshot?.profile,
                draftName = profileName,
                fallbackName = payload.heroLabel,
                fallbackSlug = expectedServerSlug,
                role = serverRole,
                actionState = actionState,
                onNameChange = onProfileNameChange,
                onSubmit = { onAction(SERVER_ACTION_PROFILE_SAVE, profileName.trim()) }
            )
        }
        payload.sections.forEach { section ->
            when (section.title) {
                "Profile", "Danger Zone" -> Unit
                "Admins" ->
                    item {
                        SettingsServerAdminsCard(
                            members = serverSnapshot?.admins.orEmpty(),
                            section = serverSnapshot?.sections?.firstOrNull { it.key == "admins" },
                            actorRole = serverRole,
                            actionState = actionState,
                            onAction = onAction
                        )
                    }
                "Pending Invites" ->
                    item {
                        SettingsServerInvitesCard(
                            invites = serverSnapshot?.pendingInvites.orEmpty(),
                            section = serverSnapshot?.sections?.firstOrNull { it.key == "pending_invites" },
                            email = inviteEmail,
                            actionState = actionState,
                            timeContext = timeContext,
                            onEmailChange = onInviteEmailChange,
                            onSubmit = { onAction(SERVER_ACTION_INVITE_CREATE, inviteEmail.trim()) },
                            onAction = onAction
                        )
                    }
                "Join Links" ->
                    item {
                        SettingsServerJoinLinksCard(
                            links = serverSnapshot?.joinLinks.orEmpty(),
                            section = serverSnapshot?.sections?.firstOrNull { it.key == "join_links" },
                            maxUses = joinLinkMaxUses,
                            expiresAt = joinLinkExpiresAt,
                            actionState = actionState,
                            timeContext = timeContext,
                            onMaxUsesChange = onJoinLinkMaxUsesChange,
                            onExpiresAtChange = onJoinLinkExpiresAtChange,
                            onCreate = onCreateJoinLink,
                            onAction = onAction
                        )
                    }
                "Pre-join Agreement" ->
                    item {
                        SettingsServerAgreementCard(
                            agreement = serverSnapshot?.agreement,
                            section = serverSnapshot?.sections?.firstOrNull { it.key == "pre_join_agreement" },
                            enabled = agreementEnabled,
                            title = agreementTitle,
                            bodyMarkdown = agreementBody,
                            actionState = actionState,
                            onEnabledChange = onAgreementEnabledChange,
                            onTitleChange = onAgreementTitleChange,
                            onBodyChange = onAgreementBodyChange,
                            onSubmit = { onAction(SERVER_ACTION_AGREEMENT_SAVE, null) }
                        )
                    }
                "Onboarding" ->
                    item {
                        SettingsServerOnboardingCard(
                            onboarding = serverSnapshot?.onboarding,
                            section = serverSnapshot?.sections?.firstOrNull { it.key == "onboarding_agent" },
                            selectedAgentId = onboardingAgentId,
                            greetingEnabled = onboardingGreetingEnabled,
                            actionState = actionState,
                            onAgentSelected = onOnboardingAgentIdChange,
                            onGreetingEnabledChange = onOnboardingGreetingEnabledChange,
                            onSubmit = { onAction(SERVER_ACTION_ONBOARDING_SAVE, null) }
                        )
                    }
                "Member Permissions" ->
                    item {
                        SettingsServerMemberPermissionsCard(
                            profile = serverSnapshot?.profile,
                            section = serverSnapshot?.sections?.firstOrNull { it.key == "member_permissions" },
                            hideHumansFromMembers = hideHumansFromMembers,
                            actionState = actionState,
                            onHideHumansFromMembersChange = onHideHumansFromMembersChange,
                            onSubmit = { onAction(SERVER_ACTION_MEMBER_PERMISSIONS_SAVE, null) }
                        )
                    }
                else -> item { SharedSettingsSectionCard(section, onAction) }
            }
        }
        item {
            SettingsServerDangerZoneCard(
                serverName = serverSnapshot?.profile?.name ?: payload.heroLabel,
                expectedServerSlug = expectedServerSlug,
                role = serverRole,
                dangerSection = serverSnapshot?.sections?.firstOrNull { it.key == "danger" },
                confirmationSlug = deleteConfirmationName,
                actionState = actionState,
                onConfirmationSlugChange = onDeleteConfirmationNameChange,
                onLeave = { onAction(SERVER_ACTION_LEAVE, null) },
                onDelete = { onAction(SERVER_ACTION_DELETE, deleteConfirmationName.trim()) }
            )
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsConnectedAppsContent(
    state: ScreenLoadState<SettingsConnectedAppsSnapshot>,
    selectedTab: String,
    onSelectedTabChange: (String) -> Unit,
    editingId: String,
    name: String,
    clientId: String,
    description: String,
    homepageUrl: String,
    returnUrl: String,
    manifestUrl: String,
    publishCategory: String,
    publishDataAccess: String,
    marketplaceSearch: String,
    marketplaceCategory: String,
    selectedMarketplaceId: String?,
    selectedBuiltInId: String?,
    confirmTarget: SettingsConnectedAppsConfirmTarget?,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp,
    onNameChange: (String) -> Unit,
    onClientIdChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onHomepageUrlChange: (String) -> Unit,
    onReturnUrlChange: (String) -> Unit,
    onManifestUrlChange: (String) -> Unit,
    onPublishCategoryChange: (String) -> Unit,
    onPublishDataAccessChange: (String) -> Unit,
    onMarketplaceSearchChange: (String) -> Unit,
    onMarketplaceCategoryChange: (String) -> Unit,
    onOpenMarketplaceDetail: (String) -> Unit,
    onDismissMarketplaceDetail: () -> Unit,
    onOpenBuiltInDetail: (String) -> Unit,
    onDismissBuiltInDetail: () -> Unit,
    onStartEdit: (SettingsConnectedAppClientSnapshot) -> Unit,
    onCancelEdit: () -> Unit,
    onRegister: (SettingsConnectedAppRegisterRequest) -> Unit,
    onUpdate: (SettingsConnectedAppUpdateRequest) -> Unit,
    onUploadLogo: (String) -> Unit,
    onClearLogo: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onConfirmDelete: (String) -> Unit,
    onPublish: (SettingsConnectedAppPublishRequest) -> Unit,
    onInstall: (String) -> Unit,
    onRequestUninstall: (String) -> Unit,
    onConfirmUninstall: (String) -> Unit,
    onDismissConfirm: () -> Unit,
    onCopySecret: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val snapshot = state.currentContent()
    val selectedMarketplace = snapshot?.marketplace?.firstOrNull { it.id == selectedMarketplaceId }
    val selectedBuiltIn = snapshot?.builtIn?.firstOrNull { it.id == selectedBuiltInId }
    selectedMarketplace?.let { app ->
        SettingsConnectedAppMarketplaceDetailDialog(
            app = app,
            actionState = actionState,
            onDismiss = onDismissMarketplaceDetail,
            onInstall = onInstall,
            onRequestUninstall = onRequestUninstall,
            onOpenUrl = onOpenUrl
        )
    }
    selectedBuiltIn?.let { app ->
        SettingsConnectedAppBuiltInDetailDialog(
            app = app,
            onDismiss = onDismissBuiltInDetail,
            onOpenUrl = onOpenUrl
        )
    }
    confirmTarget?.let { target ->
        SettingsConnectedAppsConfirmDialog(
            target = target,
            snapshot = snapshot,
            actionState = actionState,
            onDismiss = onDismissConfirm,
            onConfirmDelete = onConfirmDelete,
            onConfirmUninstall = onConfirmUninstall
        )
    }
    val payload =
        KuiklySharedSettingsPayload(
            title = "Connected Apps",
            detail = "Install marketplace apps and manage server-local OAuth clients.",
            heroIconName = SlockLucideIconName.Blocks,
            heroLabel = "Connected Apps"
        )
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SharedSettingsHero(payload) }
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsFormSectionCard(iconName = SlockLucideIconName.Loader, title = "Loading") {
                        SettingsFormDetailText("Loading integrations, marketplace apps, and OAuth clients.")
                    }
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsFormSectionCard(iconName = SlockLucideIconName.Blocks, title = "No Connected Apps") {
                        SettingsFormEmptyText("Connected Apps are unavailable for this workspace.")
                        SlockBrutalActionButton(
                            label = "Retry",
                            onClick = onRefresh,
                            leadingIcon = SlockLucideIconName.RefreshCw,
                            backgroundColor = SlockColor.SurfaceWhite
                        )
                    }
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsFormSectionCard(iconName = SlockLucideIconName.TriangleAlert, title = "Connected Apps Error") {
                        SettingsFormErrorText(state.error.code)
                        SlockBrutalActionButton(
                            label = "Retry",
                            onClick = onRefresh,
                            leadingIcon = SlockLucideIconName.RefreshCw,
                            backgroundColor = SlockColor.SurfaceWhite
                        )
                    }
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsFormSectionCard(iconName = SlockLucideIconName.Lock, title = "Permission Required") {
                        SettingsFormErrorText(settingsConnectedAppsPermissionCopy(state.blocker))
                    }
                }
            is ScreenLoadState.Content -> {
                item {
                    SegmentedControl(
                        selectedValue = selectedTab,
                        options =
                            listOf(
                                SegmentedControlOption("marketplace", "Marketplace", SlockLucideIconName.Store),
                                SegmentedControlOption("installed", "Installed", SlockLucideIconName.CircleCheck),
                                SegmentedControlOption("myapps", "My Apps", SlockLucideIconName.KeyRound)
                            ),
                        onValueSelected = onSelectedTabChange
                    )
                }
                snapshot?.oneTimeClientSecret?.let { secret ->
                    item {
                        SettingsConnectedAppSecretCard(
                            secret = secret,
                            onCopySecret = onCopySecret
                        )
                    }
                }
                when (selectedTab) {
                    "installed" ->
                        item {
                            SettingsConnectedAppsInstalledCard(
                                installed = snapshot?.installed.orEmpty(),
                                clients = snapshot?.registered.orEmpty(),
                                actionState = actionState,
                                onStartEdit = onStartEdit,
                                onRequestUninstall = onRequestUninstall
                            )
                        }
                    "myapps" -> {
                        item {
                            SettingsConnectedAppEditorCard(
                                snapshot = snapshot,
                                editingId = editingId,
                                name = name,
                                clientId = clientId,
                                description = description,
                                homepageUrl = homepageUrl,
                                returnUrl = returnUrl,
                                manifestUrl = manifestUrl,
                                publishCategory = publishCategory,
                                publishDataAccess = publishDataAccess,
                                actionState = actionState,
                                onNameChange = onNameChange,
                                onClientIdChange = onClientIdChange,
                                onDescriptionChange = onDescriptionChange,
                                onHomepageUrlChange = onHomepageUrlChange,
                                onReturnUrlChange = onReturnUrlChange,
                                onManifestUrlChange = onManifestUrlChange,
                                onPublishCategoryChange = onPublishCategoryChange,
                                onPublishDataAccessChange = onPublishDataAccessChange,
                                onCancelEdit = onCancelEdit,
                                onRegister = onRegister,
                                onUpdate = onUpdate,
                                onUploadLogo = onUploadLogo,
                                onClearLogo = onClearLogo
                            )
                        }
                        item {
                            SettingsConnectedAppClientsCard(
                                clients = snapshot?.registered.orEmpty(),
                                actionState = actionState,
                                onStartEdit = onStartEdit,
                                onRequestDelete = onRequestDelete,
                                onPublish = onPublish,
                                publishCategory = publishCategory,
                                publishDataAccess = publishDataAccess
                            )
                        }
                    }
                    else -> {
                        item {
                            SettingsConnectedAppsBuiltInCard(
                                apps = snapshot?.builtIn.orEmpty(),
                                onOpenDetail = onOpenBuiltInDetail
                            )
                        }
                        item {
                            SettingsConnectedAppsMarketplaceCard(
                                marketplace = snapshot?.marketplace.orEmpty(),
                                search = marketplaceSearch,
                                category = marketplaceCategory,
                                actionState = actionState,
                                onSearchChange = onMarketplaceSearchChange,
                                onCategoryChange = onMarketplaceCategoryChange,
                                onOpenDetail = onOpenMarketplaceDetail
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsLanguageRegionContent(
    state: ScreenLoadState<SettingsLanguageRegionSnapshot>,
    actionState: SharedSettingsActionState?,
    refreshing: Boolean,
    languageDraft: String,
    displayDraft: String,
    timezoneDraft: String,
    timeFormatDraft: String,
    bottomContentPadding: Dp,
    onLanguageDraftChange: (String) -> Unit,
    onDisplayDraftChange: (String) -> Unit,
    onTimezoneDraftChange: (String) -> Unit,
    onTimeFormatDraftChange: (String) -> Unit,
    onServerTranslationChange: (Boolean) -> Unit,
    onSaveLanguage: () -> Unit,
    onSaveTime: () -> Unit,
    onRetry: () -> Unit
) {
    val payload =
        KuiklySharedSettingsPayload(
            title = "Language & Region",
            detail =
                if (refreshing) {
                    "Refreshing language and region settings..."
                } else {
                    "Manage language, message display, timezone, and timestamp format."
                },
            heroIconName = SlockLucideIconName.Languages,
            heroLabel = "Language & Region"
        )
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SharedSettingsHero(payload) }
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.Loader, "Loading", "Loading language and region settings.", null, onRetry)
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.Languages, "Unavailable", state.reason.name, "Retry", onRetry)
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.TriangleAlert, "Language Error", state.error.code, "Retry", onRetry)
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.Lock, "Permission Required", state.blocker.code, null, onRetry)
                }
            is ScreenLoadState.Content -> {
                val snapshot = state.data
                item {
                    SettingsLanguagePreferenceCard(
                        snapshot = snapshot,
                        languageDraft = languageDraft,
                        displayDraft = displayDraft,
                        actionState = actionState,
                        onLanguageDraftChange = onLanguageDraftChange,
                        onDisplayDraftChange = onDisplayDraftChange,
                        onSubmit = onSaveLanguage
                    )
                }
                item {
                    SettingsDateTimePreferenceCard(
                        snapshot = snapshot,
                        timezoneDraft = timezoneDraft,
                        timeFormatDraft = timeFormatDraft,
                        actionState = actionState,
                        onTimezoneDraftChange = onTimezoneDraftChange,
                        onTimeFormatDraftChange = onTimeFormatDraftChange,
                        onSubmit = onSaveTime
                    )
                }
                item {
                    SettingsServerTranslationPreferenceCard(
                        snapshot = snapshot,
                        actionState = actionState,
                        onServerTranslationChange = onServerTranslationChange
                    )
                }
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsLanguagePreferenceCard(
    snapshot: SettingsLanguageRegionSnapshot,
    languageDraft: String,
    displayDraft: String,
    actionState: SharedSettingsActionState?,
    onLanguageDraftChange: (String) -> Unit,
    onDisplayDraftChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val languageBusy = actionState?.action == LANGUAGE_REGION_ACTION_LANGUAGE && actionState.inProgress
    val displayBusy = actionState?.action == LANGUAGE_REGION_ACTION_AUTO_TRANSLATION && actionState.inProgress
    val busy = languageBusy || displayBusy
    val currentDisplay = if (snapshot.autoTranslation.enabled) "translated" else "original"
    val dirty = languageDraft != snapshot.browserLanguage.selectedKey || displayDraft != currentDisplay
    SettingsFormSectionCard(iconName = SlockLucideIconName.Languages, title = "Language") {
        SettingsFormDetailText("Choose your preferred language and whether messages display translated or original text.")
        SlockBrutalSectionLabel(label = "Preferred language")
        SlockBrutalSelectField(
            options = snapshot.browserLanguage.toSelectOptions(),
            selectedId = languageDraft.ifBlank { snapshot.browserLanguage.selectedKey },
            selectedLabel = snapshot.browserLanguage.labelFor(languageDraft),
            onSelect = { onLanguageDraftChange(it.id) },
            enabled = snapshot.browserLanguage.available && !busy
        )
        SlockBrutalSectionLabel(label = "Message display")
        SegmentedControl(
            selectedValue = displayDraft.ifBlank { currentDisplay },
            options =
                listOf(
                    SegmentedControlOption("translated", "Translated", SlockLucideIconName.Languages),
                    SegmentedControlOption("original", "Original", SlockLucideIconName.FileText)
                ),
            onValueSelected = onDisplayDraftChange,
            enabled = snapshot.autoTranslation.available && !busy
        )
        if (!snapshot.autoTranslation.available) {
            SettingsFormDetailText(snapshot.autoTranslation.detail)
        }
        if (actionState?.let { it.action in setOf(LANGUAGE_REGION_ACTION_LANGUAGE, LANGUAGE_REGION_ACTION_AUTO_TRANSLATION) && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
        SlockBrutalActionButton(
            label = actionState.statusFor(LANGUAGE_REGION_ACTION_LANGUAGE, "Save language"),
            onClick = onSubmit,
            enabled = dirty && !busy,
            leadingIcon = SlockLucideIconName.Save,
            backgroundColor = SlockColor.BrandYellow
        )
    }
}

@Composable
private fun SettingsDateTimePreferenceCard(
    snapshot: SettingsLanguageRegionSnapshot,
    timezoneDraft: String,
    timeFormatDraft: String,
    actionState: SharedSettingsActionState?,
    onTimezoneDraftChange: (String) -> Unit,
    onTimeFormatDraftChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val timezoneBusy = actionState?.action == LANGUAGE_REGION_ACTION_TIMEZONE && actionState.inProgress
    val timeFormatBusy = actionState?.action == LANGUAGE_REGION_ACTION_TIME_FORMAT && actionState.inProgress
    val busy = timezoneBusy || timeFormatBusy
    val dirty = timezoneDraft != snapshot.timezone.selectedKey || timeFormatDraft != snapshot.timeFormat.selectedKey
    SettingsFormSectionCard(iconName = SlockLucideIconName.Clock, title = "Date & Time") {
        SettingsFormDetailText("Timezone and time format affect timestamps across channels, threads, and Settings.")
        SlockBrutalSectionLabel(label = "Timezone")
        SlockBrutalSelectField(
            options = snapshot.timezone.toSelectOptions(),
            selectedId = timezoneDraft.ifBlank { snapshot.timezone.selectedKey },
            selectedLabel = snapshot.timezone.labelFor(timezoneDraft),
            onSelect = { onTimezoneDraftChange(it.id) },
            enabled = snapshot.timezone.available && !busy
        )
        SlockBrutalSectionLabel(label = "Time format")
        SegmentedControl(
            selectedValue = timeFormatDraft.ifBlank { snapshot.timeFormat.selectedKey },
            options =
                listOf(
                    SegmentedControlOption("12h", "12-hour", SlockLucideIconName.Clock3),
                    SegmentedControlOption("24h", "24-hour", SlockLucideIconName.Clock)
                ),
            onValueSelected = onTimeFormatDraftChange,
            enabled = snapshot.timeFormat.available && !busy
        )
        if (actionState?.let { it.action in setOf(LANGUAGE_REGION_ACTION_TIMEZONE, LANGUAGE_REGION_ACTION_TIME_FORMAT) && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
        SlockBrutalActionButton(
            label = actionState.statusFor(LANGUAGE_REGION_ACTION_TIMEZONE, "Save time"),
            onClick = onSubmit,
            enabled = dirty && !busy,
            leadingIcon = SlockLucideIconName.Save,
            backgroundColor = SlockColor.BrandYellow
        )
    }
}

@Composable
private fun SettingsServerTranslationPreferenceCard(
    snapshot: SettingsLanguageRegionSnapshot,
    actionState: SharedSettingsActionState?,
    onServerTranslationChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == LANGUAGE_REGION_ACTION_SERVER_TRANSLATION && actionState.inProgress
    SettingsFormSectionCard(iconName = SlockLucideIconName.Wand, title = "Workspace translation") {
        SettingsFormDetailText(snapshot.serverTranslation.detail)
        SegmentedControl(
            selectedValue = if (snapshot.serverTranslation.enabled) "on" else "off",
            options =
                listOf(
                    SegmentedControlOption("on", "On", SlockLucideIconName.CircleCheck),
                    SegmentedControlOption("off", "Off", SlockLucideIconName.X)
                ),
            onValueSelected = { onServerTranslationChange(it == "on") },
            enabled = snapshot.serverTranslation.available && !busy
        )
        if (actionState?.action == LANGUAGE_REGION_ACTION_SERVER_TRANSLATION && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
    }
}

@Composable
private fun SettingsAppearanceContent(
    state: ScreenLoadState<SettingsAppearanceSnapshot>,
    actionState: SharedSettingsActionState?,
    refreshing: Boolean,
    fontSizeDraft: String,
    bottomContentPadding: Dp,
    onFontSizeDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit
) {
    val payload =
        KuiklySharedSettingsPayload(
            title = "Appearance",
            detail =
                if (refreshing) {
                    "Refreshing appearance settings..."
                } else {
                    "Adjust message body text without changing chrome, buttons, labels, or code blocks."
                },
            heroIconName = SlockLucideIconName.Type,
            heroLabel = "Appearance"
        )
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SharedSettingsHero(payload) }
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.Loader, "Loading", "Loading appearance settings.", null, onRetry)
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.Type, "Unavailable", state.reason.name, "Retry", onRetry)
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.TriangleAlert, "Appearance Error", state.error.code, "Retry", onRetry)
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.Lock, "Permission Required", state.blocker.code, null, onRetry)
                }
            is ScreenLoadState.Content ->
                item {
                    SettingsAppearanceFontSizeCard(
                        snapshot = state.data,
                        fontSizeDraft = fontSizeDraft,
                        actionState = actionState,
                        onFontSizeDraftChange = onFontSizeDraftChange,
                        onSubmit = onSave
                    )
                }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsAppearanceFontSizeCard(
    snapshot: SettingsAppearanceSnapshot,
    fontSizeDraft: String,
    actionState: SharedSettingsActionState?,
    onFontSizeDraftChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val busy = actionState?.action == APPEARANCE_ACTION_MESSAGE_FONT_SIZE && actionState.inProgress
    val selected = fontSizeDraft.ifBlank { snapshot.messageBodyFontSize.selectedKey }
    val dirty = selected != snapshot.messageBodyFontSize.selectedKey
    SettingsFormSectionCard(iconName = SlockLucideIconName.Type, title = "Message font size") {
        SettingsFormDetailText(snapshot.messageBodyFontSize.detail)
        SegmentedControl(
            selectedValue = selected,
            options =
                snapshot.messageBodyFontSize.options.map {
                    SegmentedControlOption(it.key, it.label, SlockLucideIconName.Type)
                },
            onValueSelected = onFontSizeDraftChange,
            enabled = snapshot.messageBodyFontSize.available && !busy
        )
        if (actionState?.action == APPEARANCE_ACTION_MESSAGE_FONT_SIZE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
        SlockBrutalActionButton(
            label = actionState.statusFor(APPEARANCE_ACTION_MESSAGE_FONT_SIZE, "Save"),
            onClick = onSubmit,
            enabled = dirty && !busy,
            leadingIcon = SlockLucideIconName.Save,
            backgroundColor = SlockColor.AccentPink
        )
        SettingsFormDivider()
        SettingsAppearancePreview(fontSizeKey = selected)
    }
}

@Composable
private fun SettingsAppearancePreview(fontSizeKey: String) {
    val bodyStyle =
        when (fontSizeKey) {
            "sm" -> SlockType.Body.copy(fontSize = 12.sp, lineHeight = 18.sp, color = SlockColor.TextBlack)
            "lg" -> SlockType.Body.copy(fontSize = 16.sp, lineHeight = 22.sp, color = SlockColor.TextBlack)
            else -> SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack)
        }
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Preview",
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            SlockAvatar(avatarWireValue = "letter:AI", label = "AI", avatarSize = 28.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Onboarding Assistant", style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
                Text(
                    "@Joy #proj-uiux love the new design — doing a git pull now to try it locally.",
                    style = bodyStyle,
                    color = SlockColor.TextBlack
                )
            }
        }
    }
}

@Composable
private fun SettingsNotificationsContent(
    state: ScreenLoadState<SettingsMobileSnapshot>,
    actionState: SharedSettingsActionState?,
    refreshing: Boolean,
    bottomContentPadding: Dp,
    onNotificationPreferenceChange: (Boolean) -> Unit,
    onServerMuteChange: (Boolean) -> Unit,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onTest: () -> Unit,
    onRetry: () -> Unit
) {
    val payload =
        KuiklySharedSettingsPayload(
            title = "Notifications",
            detail =
                if (refreshing) {
                    "Refreshing notification settings..."
                } else {
                    "Manage push status, test notifications, and workspace mute."
                },
            heroIconName = SlockLucideIconName.Bell,
            heroLabel = "Notifications"
        )
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SharedSettingsHero(payload) }
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.Loader, "Loading", "Loading notification settings.", null, onRetry)
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.Bell, "Unavailable", state.reason.name, "Retry", onRetry)
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.TriangleAlert, "Notification Error", state.error.code, "Retry", onRetry)
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.Lock, "Permission Required", state.blocker.code, null, onRetry)
                }
            is ScreenLoadState.Content -> {
                val snapshot = state.data
                item { SettingsPwaInstallCard() }
                item {
                    SettingsPushStatusCard(
                        snapshot = snapshot,
                        actionState = actionState,
                        onNotificationPreferenceChange = onNotificationPreferenceChange,
                        onPermission = onPermission,
                        onSettings = onSettings,
                        onTest = onTest
                    )
                }
                item {
                    SettingsServerMuteCard(
                        snapshot = snapshot,
                        actionState = actionState,
                        onServerMuteChange = onServerMuteChange
                    )
                }
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsPwaInstallCard() {
    SettingsFormSectionCard(iconName = SlockLucideIconName.AppWindow, title = "Install app") {
        SettingsFormDetailText("Install prompts are managed by browser PWA APIs on web. Native Android and OHOS builds use their platform app install flow.")
        SettingsFormEmptyText("No install prompt is available inside this native Settings surface.")
    }
}

@Composable
private fun SettingsPushStatusCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onNotificationPreferenceChange: (Boolean) -> Unit,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onTest: () -> Unit
) {
    val preferenceBusy = actionState?.action == MOBILE_ACTION_NOTIFICATION_PREFERENCE && actionState.inProgress
    SettingsFormSectionCard(iconName = SlockLucideIconName.BellRing, title = "Push status") {
        SettingsFormDetailText(snapshot.pushNotifications.detail)
        SegmentedControl(
            selectedValue = if (snapshot.pushNotifications.enabled) "enabled" else "disabled",
            options =
                listOf(
                    SegmentedControlOption("enabled", "Enabled", SlockLucideIconName.BellRing),
                    SegmentedControlOption("disabled", "Disabled", SlockLucideIconName.Bell)
                ),
            onValueSelected = { onNotificationPreferenceChange(it == "enabled") },
            enabled = snapshot.pushNotifications.available && !preferenceBusy
        )
        SettingsFormDetailText("Current status: ${actionState.statusFor(MOBILE_ACTION_NOTIFICATION_PREFERENCE, snapshot.pushNotifications.statusLabel)}")
        SettingsFormDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlockBrutalActionButton(
                label = actionState.statusFor(MOBILE_ACTION_PERMISSION, "Enable"),
                onClick = onPermission,
                enabled = snapshot.notificationPermission.available && actionState?.inProgress != true,
                leadingIcon = SlockLucideIconName.Bell,
                backgroundColor = SlockColor.BrandYellow,
                minWidth = 0.dp
            )
            SlockBrutalActionButton(
                label = actionState.statusFor(MOBILE_ACTION_SETTINGS, "Settings"),
                onClick = onSettings,
                enabled = snapshot.notificationSettings.available && actionState?.inProgress != true,
                leadingIcon = SlockLucideIconName.Settings,
                backgroundColor = SlockColor.SurfaceWhite,
                minWidth = 0.dp
            )
            SlockBrutalActionButton(
                label = actionState.statusFor(MOBILE_ACTION_TEST_NOTIFICATION, "Test"),
                onClick = onTest,
                enabled = snapshot.testNotification.available && actionState?.inProgress != true,
                leadingIcon = SlockLucideIconName.Send,
                backgroundColor = SlockColor.AccentPink,
                minWidth = 0.dp
            )
        }
        if (actionState?.let { it.action in setOf(MOBILE_ACTION_NOTIFICATION_PREFERENCE, MOBILE_ACTION_PERMISSION, MOBILE_ACTION_SETTINGS, MOBILE_ACTION_TEST_NOTIFICATION) && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
    }
}

@Composable
private fun SettingsServerMuteCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onServerMuteChange: (Boolean) -> Unit
) {
    var muteDraft by remember(snapshot.muteServer.enabled) { mutableStateOf(snapshot.muteServer.enabled) }
    val busy = actionState?.action == MOBILE_ACTION_SERVER_MUTE && actionState.inProgress
    val dirty = muteDraft != snapshot.muteServer.enabled
    SettingsFormSectionCard(iconName = SlockLucideIconName.VolumeX, title = "Workspace mute") {
        SettingsFormDetailText(snapshot.muteServer.detail)
        SegmentedControl(
            selectedValue = if (muteDraft) "muted" else "unmuted",
            options =
                listOf(
                    SegmentedControlOption("unmuted", "Unmuted", SlockLucideIconName.BellRing),
                    SegmentedControlOption("muted", "Muted", SlockLucideIconName.VolumeX)
                ),
            onValueSelected = { muteDraft = it == "muted" },
            enabled = snapshot.muteServer.available && !busy
        )
        if (actionState?.action == MOBILE_ACTION_SERVER_MUTE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
        SlockBrutalActionButton(
            label = actionState.statusFor(MOBILE_ACTION_SERVER_MUTE, "Save mute"),
            onClick = { onServerMuteChange(muteDraft) },
            enabled = snapshot.muteServer.available && dirty && !busy,
            leadingIcon = SlockLucideIconName.Save,
            backgroundColor = SlockColor.BrandYellow
        )
    }
}

@Composable
private fun SettingsLoadStateCard(
    iconName: SlockLucideIconName,
    title: String,
    detail: String,
    retryLabel: String?,
    onRetry: () -> Unit
) {
    SettingsFormSectionCard(iconName = iconName, title = title) {
        SettingsFormDetailText(detail)
        retryLabel?.let {
            SlockBrutalActionButton(
                label = it,
                onClick = onRetry,
                leadingIcon = SlockLucideIconName.RefreshCw,
                backgroundColor = SlockColor.SurfaceWhite
            )
        }
    }
}

private fun SettingsMobileSelectionSnapshot.toSelectOptions(): List<SlockBrutalSelectOption> =
    options.map { SlockBrutalSelectOption(it.key, it.label) }

private fun SettingsMobileSelectionSnapshot.labelFor(key: String): String =
    options.firstOrNull { it.key == key }?.label ?: selectedLabel

private const val SETTINGS_CONNECTED_APPS_CATEGORY_ALL = "__all__"

private object SettingsConnectedAppsConfirmKind {
    const val DeleteClient = "delete_client"
    const val UninstallMarketplace = "uninstall_marketplace"
}

private data class SettingsConnectedAppsConfirmTarget(
    val kind: String,
    val id: String
)

@Composable
private fun SettingsConnectedAppSecretCard(
    secret: SettingsConnectedAppSecretSnapshot,
    onCopySecret: (String) -> Unit
) {
    SettingsFormSectionCard(iconName = SlockLucideIconName.KeyRound, title = "Client Secret") {
        SettingsFormDetailText("Copy this secret now. It is shown once and is not persisted in diagnostics.")
        SettingsReadonlyField(label = "Client ID", value = secret.clientId)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            SlockBrutalSectionLabel(label = "Client Secret")
            Text(
                secret.clientSecret,
                style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceCream)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .padding(horizontal = 10.dp, vertical = 10.dp)
            )
        }
        SlockBrutalActionButton(
            label = "Copy Secret",
            onClick = { onCopySecret(secret.clientSecret) },
            leadingIcon = SlockLucideIconName.Copy,
            backgroundColor = SlockColor.BrandYellow
        )
    }
}

@Composable
private fun SettingsConnectedAppMarketplaceDetailDialog(
    app: SettingsConnectedAppMarketplaceSnapshot,
    actionState: SharedSettingsActionState?,
    onDismiss: () -> Unit,
    onInstall: (String) -> Unit,
    onRequestUninstall: (String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val action = if (app.installed) CONNECTED_APPS_ACTION_UNINSTALL else CONNECTED_APPS_ACTION_INSTALL
    val busy = actionState.connectedAppsBusyFor(action, app.id)
    SlockUnifiedDialog(
        title = app.name,
        onDismiss = onDismiss,
        dismissEnabled = !busy,
        maxWidth = 456.dp
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsConnectedAppLogoBox(
                name = app.name,
                logoUrl = app.logoUrl,
                seed = app.clientId,
                size = 56.dp
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsFormDetailText(app.description ?: "No description provided.")
                SettingsFormDetailText(if (app.installed) "Installed from marketplace." else "Available from marketplace.")
            }
        }
        SettingsConnectedAppDetailField("Publisher", app.developer)
        SettingsConnectedAppDetailField("Category", app.category.settingsConnectedAppCategoryLabel())
        SettingsConnectedAppDetailField("Homepage", app.homepageDomain)
        SettingsConnectedAppDetailField("Callback", app.callbackDomain)
        SettingsConnectedAppDetailField("Data Access", app.dataAccessSummary)
        actionState.connectedAppsErrorFor(action, app.id)?.let { SettingsFormErrorText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            app.homepageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                SlockBrutalActionButton(
                    label = "Open",
                    onClick = { onOpenUrl(url) },
                    leadingIcon = SlockLucideIconName.ExternalLink,
                    backgroundColor = SlockColor.SurfaceWhite
                )
            }
            SlockBrutalActionButton(
                label =
                    actionState.connectedAppsStatusFor(
                        action,
                        if (app.installed) "Uninstall" else "Install",
                        app.id
                    ),
                onClick = {
                    if (app.installed) {
                        onRequestUninstall(app.id)
                    } else {
                        onInstall(app.id)
                    }
                },
                enabled = !busy,
                leadingIcon = if (app.installed) SlockLucideIconName.Trash2 else SlockLucideIconName.Download,
                backgroundColor = if (app.installed) SlockColor.SurfaceWhite else SlockColor.BrandYellow
            )
        }
    }
}

@Composable
private fun SettingsConnectedAppBuiltInDetailDialog(
    app: SettingsConnectedAppBuiltInSnapshot,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    SlockUnifiedDialog(
        title = app.name,
        onDismiss = onDismiss,
        maxWidth = 456.dp
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsConnectedAppLogoBox(
                name = app.name,
                logoUrl = null,
                seed = app.clientId,
                size = 56.dp
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsFormDetailText(app.description ?: "No description provided.")
                SettingsFormDetailText("Built in by Raft.")
            }
        }
        SettingsConnectedAppDetailField("Publisher", "Raft")
        SettingsConnectedAppDetailField("Client ID", app.clientId)
        SettingsConnectedAppDetailField("Homepage", app.homepageDomain)
        SettingsConnectedAppDetailField("Agent Manifest", app.agentManifestUrl)
        app.homepageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            SlockBrutalActionButton(
                label = "Open",
                onClick = { onOpenUrl(url) },
                leadingIcon = SlockLucideIconName.ExternalLink,
                backgroundColor = SlockColor.SurfaceWhite
            )
        }
    }
}

@Composable
private fun SettingsConnectedAppsConfirmDialog(
    target: SettingsConnectedAppsConfirmTarget,
    snapshot: SettingsConnectedAppsSnapshot?,
    actionState: SharedSettingsActionState?,
    onDismiss: () -> Unit,
    onConfirmDelete: (String) -> Unit,
    onConfirmUninstall: (String) -> Unit
) {
    val client = snapshot?.registered?.firstOrNull { it.id == target.id }
    val marketplaceApp = snapshot?.marketplace?.firstOrNull { it.id == target.id }
    val installedApp = snapshot?.installed?.firstOrNull { it.id == target.id }
    val appName = client?.name ?: marketplaceApp?.name ?: installedApp?.name ?: "this app"
    val deleting = target.kind == SettingsConnectedAppsConfirmKind.DeleteClient
    val action = if (deleting) CONNECTED_APPS_ACTION_DELETE else CONNECTED_APPS_ACTION_UNINSTALL
    val busy = actionState.connectedAppsBusyFor(action, target.id)
    SlockUnifiedDialog(
        title = if (deleting) "Delete OAuth Client" else "Uninstall App",
        onDismiss = onDismiss,
        dismissEnabled = !busy,
        maxWidth = 424.dp
    ) {
        SettingsFormDetailText(
            if (deleting) {
                "Delete $appName and revoke its client secret, grants, and marketplace linkage. This cannot be undone."
            } else {
                "Uninstall $appName from this workspace and revoke its access."
            }
        )
        SettingsConnectedAppDetailField(
            label = "Client ID",
            value = client?.clientId ?: marketplaceApp?.clientId ?: installedApp?.clientId
        )
        actionState.connectedAppsErrorFor(action, target.id)?.let { SettingsFormErrorText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SlockBrutalActionButton(
                label = "Cancel",
                onClick = onDismiss,
                enabled = !busy,
                leadingIcon = SlockLucideIconName.X,
                backgroundColor = SlockColor.SurfaceWhite
            )
            SlockBrutalActionButton(
                label = actionState.connectedAppsStatusFor(action, if (deleting) "Delete" else "Uninstall", target.id),
                onClick = {
                    if (deleting) {
                        onConfirmDelete(target.id)
                    } else {
                        onConfirmUninstall(target.id)
                    }
                },
                enabled = !busy,
                leadingIcon = SlockLucideIconName.Trash2,
                backgroundColor = SlockColor.AccentRed
            )
        }
    }
}

@Composable
private fun SettingsConnectedAppDetailField(label: String, value: String?) {
    value?.takeIf { it.isNotBlank() }?.let {
        SettingsReadonlyField(label = label, value = it)
    }
}

@Composable
private fun SettingsConnectedAppsBuiltInCard(
    apps: List<SettingsConnectedAppBuiltInSnapshot>,
    onOpenDetail: (String) -> Unit
) {
    SettingsFormSectionCard(iconName = SlockLucideIconName.PackageCheck, title = "Built-in Apps") {
        if (apps.isEmpty()) {
            SettingsFormEmptyText("No built-in apps are available.")
        } else {
            apps.forEach { app ->
                SettingsConnectedAppActionRow(
                    title = app.name,
                    detail = listOfNotNull(app.description, app.homepageDomain).joinToString(" · "),
                    buttonLabel = "Details",
                    buttonIcon = SlockLucideIconName.Eye,
                    buttonColor = SlockColor.SurfaceWhite,
                    enabled = true,
                    onClick = { onOpenDetail(app.id) }
                )
            }
        }
    }
}

@Composable
private fun SettingsConnectedAppsMarketplaceCard(
    marketplace: List<SettingsConnectedAppMarketplaceSnapshot>,
    search: String,
    category: String,
    actionState: SharedSettingsActionState?,
    onSearchChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val categoryOptions = marketplace.settingsConnectedAppCategoryOptions()
    val resolvedCategory =
        category.takeIf { selected -> categoryOptions.any { it.id == selected } } ?: SETTINGS_CONNECTED_APPS_CATEGORY_ALL
    val filteredMarketplace = marketplace.settingsConnectedAppFilteredMarketplace(search, resolvedCategory)
    SettingsFormSectionCard(iconName = SlockLucideIconName.Store, title = "Marketplace") {
        SlockBrutalSearchField(
            value = search,
            placeholder = "Search apps",
            onValueChange = onSearchChange
        )
        SlockBrutalSelectField(
            options = categoryOptions,
            selectedId = resolvedCategory,
            onSelect = { onCategoryChange(it.id) },
            placeholder = "All categories"
        )
        if (marketplace.isEmpty()) {
            SettingsFormEmptyText("No marketplace apps are available.")
        } else if (filteredMarketplace.isEmpty()) {
            SettingsFormEmptyText("No marketplace apps match this search.")
        } else {
            filteredMarketplace.forEach { app ->
                SettingsConnectedAppActionRow(
                    title = app.name,
                    detail =
                        listOfNotNull(
                            app.category.settingsConnectedAppCategoryLabel(),
                            app.developer,
                            app.dataAccessSummary,
                            if (app.installed) "Installed" else null
                        ).joinToString(" · "),
                    buttonLabel = "Details",
                    buttonIcon = SlockLucideIconName.Eye,
                    buttonColor = SlockColor.SurfaceWhite,
                    enabled = true,
                    onClick = { onOpenDetail(app.id) }
                )
                actionState.connectedAppsErrorFor(CONNECTED_APPS_ACTION_INSTALL, app.id)?.let { SettingsFormErrorText(it) }
                actionState.connectedAppsErrorFor(CONNECTED_APPS_ACTION_UNINSTALL, app.id)?.let { SettingsFormErrorText(it) }
            }
        }
    }
}

@Composable
private fun SettingsConnectedAppsInstalledCard(
    installed: List<SettingsConnectedAppInstalledSnapshot>,
    clients: List<SettingsConnectedAppClientSnapshot>,
    actionState: SharedSettingsActionState?,
    onStartEdit: (SettingsConnectedAppClientSnapshot) -> Unit,
    onRequestUninstall: (String) -> Unit
) {
    SettingsFormSectionCard(iconName = SlockLucideIconName.CircleCheck, title = "Installed Apps") {
        if (installed.isEmpty()) {
            SettingsFormEmptyText("No marketplace apps are installed for this workspace.")
        } else {
            installed.forEach { app ->
                val client = clients.firstOrNull { it.id == app.id }
                val marketplaceApp = app.origin == "marketplace"
                val editableClient = app.editable && client != null
                val busy = actionState.connectedAppsBusyFor(CONNECTED_APPS_ACTION_UNINSTALL, app.id)
                SettingsConnectedAppActionRow(
                    title = app.name,
                    detail = listOfNotNull(app.developer, app.description, app.origin).joinToString(" · "),
                    buttonLabel =
                        if (editableClient) {
                            "Edit"
                        } else {
                            actionState.connectedAppsStatusFor(CONNECTED_APPS_ACTION_UNINSTALL, "Uninstall", app.id)
                        },
                    buttonIcon = if (editableClient) SlockLucideIconName.Pencil else SlockLucideIconName.Trash2,
                    buttonColor = SlockColor.SurfaceWhite,
                    enabled = editableClient || (marketplaceApp && !busy),
                    onClick = {
                        if (editableClient) {
                            onStartEdit(client)
                        } else if (marketplaceApp) {
                            onRequestUninstall(app.id)
                        }
                    }
                )
                actionState.connectedAppsErrorFor(CONNECTED_APPS_ACTION_UNINSTALL, app.id)?.let { SettingsFormErrorText(it) }
            }
        }
    }
}

@Composable
private fun SettingsConnectedAppLogoEditor(
    editingClient: SettingsConnectedAppClientSnapshot?,
    fallbackName: String,
    fallbackClientId: String,
    enabled: Boolean,
    busy: Boolean,
    actionState: SharedSettingsActionState?,
    onUploadLogo: () -> Unit,
    onClearLogo: () -> Unit
) {
    val editing = editingClient != null
    val displayName = fallbackName.ifBlank { editingClient?.name.orEmpty().ifBlank { "App" } }
    val seed = fallbackClientId.ifBlank { editingClient?.clientId.orEmpty().ifBlank { displayName } }
    SlockBrutalSectionLabel(label = "Logo")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsConnectedAppLogoBox(
            name = displayName,
            logoUrl = editingClient?.logoUrl,
            seed = seed,
            size = 64.dp
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            SettingsFormDetailText(
                if (editing) {
                    "Upload a square logo, or reset to the deterministic monogram."
                } else {
                    "Register the app first, then upload a logo from Edit."
                }
            )
            if (editingClient != null) {
                val client = editingClient
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlockBrutalActionButton(
                        label = actionState.connectedAppsStatusFor(CONNECTED_APPS_ACTION_LOGO_UPLOAD, if (client.logoUrl.isNullOrBlank()) "Upload" else "Change", client.id),
                        onClick = onUploadLogo,
                        enabled = enabled && !busy,
                        leadingIcon = SlockLucideIconName.Upload,
                        backgroundColor = SlockColor.SurfaceWhite,
                        minWidth = 0.dp
                    )
                    if (!client.logoUrl.isNullOrBlank()) {
                        SlockBrutalActionButton(
                            label = actionState.connectedAppsStatusFor(CONNECTED_APPS_ACTION_LOGO_CLEAR, "Reset", client.id),
                            onClick = onClearLogo,
                            enabled = enabled && !busy,
                            leadingIcon = SlockLucideIconName.RotateCcw,
                            backgroundColor = SlockColor.SurfaceWhite,
                            minWidth = 0.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsConnectedAppLogoBox(
    name: String,
    logoUrl: String?,
    seed: String,
    size: Dp
) {
    val hasLogo = !logoUrl.isNullOrBlank()
    Box(
        modifier =
            Modifier
                .size(size)
                .background(if (hasLogo) SlockColor.SurfaceWhite else settingsConnectedAppLogoColor(seed))
                .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        if (hasLogo) {
            SlockCachedImage(
                source = logoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                settingsConnectedAppInitials(name),
                style =
                    SlockType.PageTitle.copy(
                        fontSize = (size.value * 0.26f).sp,
                        lineHeight = (size.value * 0.30f).sp,
                        color = SlockColor.TextBlack
                    ),
                color = SlockColor.TextBlack,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun settingsConnectedAppLogoColor(seed: String): Color {
    val colors =
        listOf(
            SlockColor.AccentCyan,
            SlockColor.AccentLime,
            SlockColor.AccentLavender,
            SlockColor.BrandYellow,
            SlockColor.AccentPink
        )
    val value = seed.ifBlank { "app" }.sumOf { it.code }
    return colors[value % colors.size]
}

private fun settingsConnectedAppInitials(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotBlank() }
    return parts.take(2).joinToString("") { it.take(1) }.uppercase().ifBlank { "APP" }
}

private fun SettingsConnectedAppClientSnapshot.canRequestMarketplacePublish(): Boolean =
    publishStatus.trim().lowercase() in setOf("private", "rejected")

private fun SettingsConnectedAppClientSnapshot.publishStatusCopy(): String =
    publishStatus.trim()
        .takeIf { it.isNotBlank() }
        ?.replace("_", " ")
        ?.replaceFirstChar { it.uppercase() }
        ?: "Private"

@Composable
private fun SettingsConnectedAppEditorCard(
    snapshot: SettingsConnectedAppsSnapshot?,
    editingId: String,
    name: String,
    clientId: String,
    description: String,
    homepageUrl: String,
    returnUrl: String,
    manifestUrl: String,
    publishCategory: String,
    publishDataAccess: String,
    actionState: SharedSettingsActionState?,
    onNameChange: (String) -> Unit,
    onClientIdChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onHomepageUrlChange: (String) -> Unit,
    onReturnUrlChange: (String) -> Unit,
    onManifestUrlChange: (String) -> Unit,
    onPublishCategoryChange: (String) -> Unit,
    onPublishDataAccessChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onRegister: (SettingsConnectedAppRegisterRequest) -> Unit,
    onUpdate: (SettingsConnectedAppUpdateRequest) -> Unit,
    onUploadLogo: (String) -> Unit,
    onClearLogo: (String) -> Unit
) {
    val editing = editingId.isNotBlank()
    val busy =
        actionState?.inProgress == true &&
            actionState.action in setOf(CONNECTED_APPS_ACTION_REGISTER, CONNECTED_APPS_ACTION_UPDATE)
    val trimmedName = name.trim()
    val canManage = snapshot?.canManage == true
    val enabled = canManage && !busy
    val editingClient = snapshot?.registered?.firstOrNull { it.id == editingId }
    val canRequestPublish = editingClient?.canRequestMarketplacePublish() == true
    val logoBusy =
        actionState?.inProgress == true &&
            actionState.action in setOf(CONNECTED_APPS_ACTION_LOGO_UPLOAD, CONNECTED_APPS_ACTION_LOGO_CLEAR) &&
            actionState.itemId == editingId
    SettingsFormSectionCard(iconName = SlockLucideIconName.KeyRound, title = if (editing) "Edit OAuth Client" else "Register OAuth Client") {
        SettingsFormDetailText("Create server-local OAuth clients for trusted integrations. The client secret is shown once after creation.")
        SlockBrutalTextField(
            label = "App Name",
            value = name,
            placeholder = "My integration",
            onValueChange = onNameChange,
            required = true,
            enabled = enabled
        )
        SlockBrutalTextField(
            label = "Client ID",
            value = clientId,
            placeholder = "Optional custom client id",
            onValueChange = onClientIdChange,
            optional = true,
            enabled = enabled && !editing
        )
        SlockBrutalTextField(
            label = "Description",
            value = description,
            placeholder = "What this app does",
            onValueChange = onDescriptionChange,
            optional = true,
            enabled = enabled,
            maxLines = 3,
            fieldMinHeight = 78.dp
        )
        SlockBrutalTextField(
            label = "Homepage URL",
            value = homepageUrl,
            placeholder = "https://example.com",
            onValueChange = onHomepageUrlChange,
            optional = true,
            enabled = enabled
        )
        SlockBrutalTextField(
            label = "Return URL",
            value = returnUrl,
            placeholder = "https://example.com/oauth/callback",
            onValueChange = onReturnUrlChange,
            optional = true,
            enabled = enabled
        )
        SlockBrutalTextField(
            label = "Agent Manifest URL",
            value = manifestUrl,
            placeholder = "https://example.com/.well-known/slock-agent.json",
            onValueChange = onManifestUrlChange,
            optional = true,
            enabled = enabled
        )
        SettingsConnectedAppLogoEditor(
            editingClient = editingClient,
            fallbackName = name,
            fallbackClientId = clientId,
            enabled = enabled && !busy,
            busy = logoBusy,
            actionState = actionState,
            onUploadLogo = { editingClient?.id?.let(onUploadLogo) },
            onClearLogo = { editingClient?.id?.let(onClearLogo) }
        )
        if (editing && canRequestPublish) {
            SlockBrutalTextField(
                label = "Publish Category",
                value = publishCategory,
                placeholder = "productivity",
                onValueChange = onPublishCategoryChange,
                optional = true,
                enabled = enabled
            )
            SlockBrutalTextField(
                label = "Data Access Summary",
                value = publishDataAccess,
                placeholder = "Profile and workspace metadata",
                onValueChange = onPublishDataAccessChange,
                optional = true,
                enabled = enabled,
                maxLines = 3,
                fieldMinHeight = 78.dp
            )
        } else if (editingClient != null) {
            SettingsFormDetailText(
                listOfNotNull(
                    "Publish status: ${editingClient.publishStatusCopy()}",
                    editingClient.publishRejectionReason?.takeIf { it.isNotBlank() }?.let { "Rejection: $it" }
                ).joinToString(" ")
            )
        }
        if (!canManage) {
            SettingsFormErrorText("Only server owners/admins can manage OAuth clients.")
        }
        if (actionState?.let { it.action in setOf(CONNECTED_APPS_ACTION_REGISTER, CONNECTED_APPS_ACTION_UPDATE) && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
        if (actionState?.let { it.action in setOf(CONNECTED_APPS_ACTION_LOGO_UPLOAD, CONNECTED_APPS_ACTION_LOGO_CLEAR) && it.itemId == editingId && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SlockBrutalActionButton(
                label =
                    actionState.connectedAppsStatusFor(
                        if (editing) CONNECTED_APPS_ACTION_UPDATE else CONNECTED_APPS_ACTION_REGISTER,
                        if (editing) "Save Client" else "Register Client",
                        editingId.takeIf { editing }
                    ),
                onClick = {
                    if (editing) {
                        onUpdate(
                            SettingsConnectedAppUpdateRequest(
                                id = editingId,
                                name = trimmedName,
                                description = description.trim().ifBlank { null },
                                homepageUrl = homepageUrl.trim().ifBlank { null },
                                returnUrl = returnUrl.trim().ifBlank { null },
                                agentManifestUrl = manifestUrl.trim().ifBlank { null }
                            )
                        )
                    } else {
                        onRegister(
                            SettingsConnectedAppRegisterRequest(
                                name = trimmedName,
                                clientId = clientId.trim().ifBlank { null },
                                description = description.trim().ifBlank { null },
                                homepageUrl = homepageUrl.trim().ifBlank { null },
                                returnUrl = returnUrl.trim().ifBlank { null },
                                agentManifestUrl = manifestUrl.trim().ifBlank { null }
                            )
                        )
                    }
                },
                enabled = enabled && trimmedName.isNotBlank(),
                leadingIcon = if (editing) SlockLucideIconName.Save else SlockLucideIconName.Plus,
                backgroundColor = SlockColor.BrandYellow
            )
            if (editing) {
                SlockBrutalActionButton(
                    label = "Cancel",
                    onClick = onCancelEdit,
                    enabled = !busy,
                    leadingIcon = SlockLucideIconName.X,
                    backgroundColor = SlockColor.SurfaceWhite
                )
            }
        }
    }
}

@Composable
private fun SettingsConnectedAppClientsCard(
    clients: List<SettingsConnectedAppClientSnapshot>,
    actionState: SharedSettingsActionState?,
    onStartEdit: (SettingsConnectedAppClientSnapshot) -> Unit,
    onRequestDelete: (String) -> Unit,
    onPublish: (SettingsConnectedAppPublishRequest) -> Unit,
    publishCategory: String,
    publishDataAccess: String
) {
    SettingsFormSectionCard(iconName = SlockLucideIconName.KeyRound, title = "My Apps") {
        if (clients.isEmpty()) {
            SettingsFormEmptyText("No server-local OAuth clients yet.")
        } else {
            clients.forEach { app ->
                val canRequestPublish = app.canRequestMarketplacePublish()
                val publishBusy = actionState.connectedAppsBusyFor(CONNECTED_APPS_ACTION_PUBLISH, app.id)
                val deleteBusy = actionState.connectedAppsBusyFor(CONNECTED_APPS_ACTION_DELETE, app.id)
                SettingsConnectedAppActionRow(
                    title = app.name,
                    detail = listOfNotNull(app.clientId, app.publishStatus, app.description).joinToString(" · "),
                    buttonLabel = "Edit",
                    buttonIcon = SlockLucideIconName.Pencil,
                    buttonColor = SlockColor.SurfaceWhite,
                    enabled = !publishBusy && !deleteBusy,
                    onClick = { onStartEdit(app) }
                )
                if (canRequestPublish) {
                    SettingsConnectedAppActionRow(
                        title = "Publish ${app.name}",
                        detail = app.publishRejectionReason ?: "Request marketplace review for this OAuth client.",
                        buttonLabel = actionState.connectedAppsStatusFor(CONNECTED_APPS_ACTION_PUBLISH, "Request Publish", app.id),
                        buttonIcon = SlockLucideIconName.Send,
                        buttonColor = SlockColor.AccentCyan,
                        enabled = !publishBusy,
                        onClick = {
                            onPublish(
                                SettingsConnectedAppPublishRequest(
                                    id = app.id,
                                    category = publishCategory.trim().ifBlank { app.category.ifBlank { "productivity" } },
                                    dataAccessSummary = publishDataAccess.trim().ifBlank { app.dataAccessSummary }
                                )
                            )
                        }
                    )
                    actionState.connectedAppsErrorFor(CONNECTED_APPS_ACTION_PUBLISH, app.id)?.let { SettingsFormErrorText(it) }
                } else {
                    SettingsFormDetailText(
                        listOfNotNull(
                            "Publish status: ${app.publishStatusCopy()}",
                            app.publishRejectionReason?.takeIf { it.isNotBlank() }?.let { "Rejection: $it" }
                        ).joinToString(" ")
                    )
                }
                SettingsConnectedAppActionRow(
                    title = "Delete ${app.name}",
                    detail = "Remove this server-local OAuth client and its credentials.",
                    buttonLabel = actionState.connectedAppsStatusFor(CONNECTED_APPS_ACTION_DELETE, "Delete", app.id),
                    buttonIcon = SlockLucideIconName.Trash2,
                    buttonColor = SlockColor.AccentRed,
                    enabled = !deleteBusy,
                    onClick = { onRequestDelete(app.id) }
                )
                actionState.connectedAppsErrorFor(CONNECTED_APPS_ACTION_DELETE, app.id)?.let { SettingsFormErrorText(it) }
                SettingsFormDivider()
            }
        }
    }
}

@Composable
private fun SettingsConnectedAppActionRow(
    title: String,
    detail: String,
    buttonLabel: String,
    buttonIcon: SlockLucideIconName,
    buttonColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SettingsServerActionRow(
        title = title,
        detail = detail.ifBlank { "No description provided." },
        buttonLabel = buttonLabel,
        buttonIcon = buttonIcon,
        buttonColor = buttonColor,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun SettingsConnectedAppInfoRow(
    title: String,
    detail: String,
    status: String,
    iconName: SlockLucideIconName
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(16.dp), color = "#FF000000")
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = SlockType.Button.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
            Text(detail.ifBlank { "No description provided." }, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        }
        Text(status, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
    }
}

@Composable
private fun SettingsServerProfileFormCard(
    profile: ai.slock.compose.data.SettingsServerProfileSnapshot?,
    draftName: String,
    fallbackName: String,
    fallbackSlug: String,
    role: String,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val currentName = profile?.name ?: fallbackName
    val currentSlug = profile?.slug?.takeIf { it.isNotBlank() } ?: fallbackSlug
    val canSave = profile?.canSave == true
    val busy = actionState?.action == SERVER_ACTION_PROFILE_SAVE && actionState.inProgress
    val trimmedName = draftName.trim()
    val dirty = trimmedName.isNotBlank() && trimmedName != currentName
    val enabled = canSave && dirty && !busy
    val status = actionState.serverStatusFor(SERVER_ACTION_PROFILE_SAVE, if (canSave) "Save Profile" else "Read only")
    SettingsFormSectionCard(iconName = SlockLucideIconName.Building2, title = "Profile") {
        SlockBrutalTextField(
            label = "Name",
            value = if (canSave) draftName else currentName,
            placeholder = currentName.ifBlank { "Workspace name" },
            onValueChange = onNameChange,
            required = true,
            enabled = canSave && !busy
        )
        SettingsReadonlyField(
            label = "Slug",
            value = currentSlug.ifBlank { "Not provided" }
        )
        SettingsReadonlyField(
            label = "Your role",
            value = role.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Member"
        )
        if (actionState?.action == SERVER_ACTION_PROFILE_SAVE && actionState.errorCode != null) {
            Text(
                actionState.errorCode,
                style = SlockType.Caption.copy(color = SlockColor.AccentRed),
                color = SlockColor.AccentRed
            )
        }
        if (canSave) {
            SlockBrutalActionButton(
                label = status,
                onClick = onSubmit,
                enabled = enabled,
                leadingIcon = SlockLucideIconName.Save,
                backgroundColor = SlockColor.BrandYellow
            )
        }
    }
}

@Composable
private fun SettingsReadonlyField(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        SlockBrutalSectionLabel(label = label)
        Text(
            value,
            style = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(horizontal = 10.dp, vertical = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsServerSectionHeader(
    iconName: SlockLucideIconName,
    title: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(16.dp), color = "#FF000000")
        Text(title, style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
    }
}

@Composable
private fun SettingsServerDangerZoneCard(
    serverName: String,
    expectedServerSlug: String,
    role: String,
    dangerSection: SettingsServerSectionSnapshot?,
    confirmationSlug: String,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    onConfirmationSlugChange: (String) -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit
) {
    val normalizedRole = role.lowercase()
    val canLeave = normalizedRole == "admin" || normalizedRole == "member"
    val canDelete = normalizedRole == "owner" || dangerSection?.state?.name == "Available"
    if (!canLeave && !canDelete) {
        return
    }
    val leaveConfirmed = actionState.isServerConfirmation(SERVER_ACTION_LEAVE)
    val deleteBusy = actionState?.action == SERVER_ACTION_DELETE && actionState.inProgress
    val leaveBusy = actionState?.action == SERVER_ACTION_LEAVE && actionState.inProgress
    val trimmedSlug = confirmationSlug.trim()
    val deleteEnabled = canDelete && !deleteBusy && expectedServerSlug.isNotBlank() && trimmedSlug == expectedServerSlug
    SettingsFormSectionCard(iconName = SlockLucideIconName.TriangleAlert, title = "Danger Zone") {
        if (canLeave) {
            SettingsServerActionRow(
                title = "Leave Server",
                detail = "You will lose access to ${serverName.ifBlank { "this server" }} and all of its channels. You can be re-invited later.",
                buttonLabel = actionState.serverStatusFor(SERVER_ACTION_LEAVE, "Leave Server"),
                buttonIcon = SlockLucideIconName.LogOut,
                buttonColor = SlockColor.AccentOrange,
                enabled = !leaveBusy,
                onClick = onLeave
            )
            if (leaveConfirmed) {
                Text(
                    "Tap Leave Server again to confirm.",
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
        }
        if (canDelete) {
            SettingsServerActionRow(
                title = "Delete Server",
                detail = "Permanently remove ${serverName.ifBlank { "this server" }} and all of its data. This cannot be undone.",
                buttonLabel = actionState.serverStatusFor(SERVER_ACTION_DELETE, "Delete Server"),
                buttonIcon = SlockLucideIconName.Trash2,
                buttonColor = SlockColor.AccentRed,
                enabled = false,
                onClick = {}
            )
            Text(
                "Type ${expectedServerSlug.ifBlank { "the server slug" }} to confirm:",
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
            SlockBrutalTextField(
                label = "Confirm server slug",
                value = confirmationSlug,
                placeholder = expectedServerSlug.ifBlank { "server-slug" },
                onValueChange = onConfirmationSlugChange,
                required = true,
                enabled = !deleteBusy
            )
            if (actionState?.action == SERVER_ACTION_DELETE && actionState.errorCode != null) {
                Text(
                    actionState.errorCode,
                    style = SlockType.Caption.copy(color = SlockColor.AccentRed),
                    color = SlockColor.AccentRed
                )
            }
            SlockBrutalActionButton(
                label = actionState.serverStatusFor(SERVER_ACTION_DELETE, "Delete Server"),
                onClick = onDelete,
                enabled = deleteEnabled,
                leadingIcon = SlockLucideIconName.Trash2,
                backgroundColor = SlockColor.AccentRed
            )
        }
    }
}

@Composable
private fun SettingsFormSectionCard(
    iconName: SlockLucideIconName,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsServerSectionHeader(iconName = iconName, title = title)
        content()
    }
}

@Composable
private fun SettingsServerActionRow(
    title: String,
    detail: String,
    buttonLabel: String,
    buttonIcon: SlockLucideIconName,
    buttonColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = SlockType.Button.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
            Text(detail, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        }
        SlockBrutalActionButton(
            label = buttonLabel,
            onClick = onClick,
            enabled = enabled,
            leadingIcon = buttonIcon,
            backgroundColor = buttonColor,
            minWidth = 108.dp
        )
    }
}

@Composable
private fun SettingsServerAdminsCard(
    members: List<SettingsServerAdminSnapshot>,
    section: SettingsServerSectionSnapshot?,
    actorRole: String,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    onAction: (String, String?) -> Unit
) {
    val normalizedActorRole = actorRole.normalizedServerRole()
    val admins =
        members
            .filter { it.isPrivilegedServerMember() }
            .sortedWith(compareBy<SettingsServerAdminSnapshot> { if (it.normalizedRole() == "owner") 0 else 1 }.thenBy { it.name })
    val candidates = members.filter { !it.isPrivilegedServerMember() }.sortedBy { it.name }
    val ownerCount = admins.count { it.normalizedRole() == "owner" }
    SettingsFormSectionCard(iconName = SlockLucideIconName.Shield, title = "Owners & Admins") {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        if (candidates.isNotEmpty()) {
            Text(
                "Add owner/admin",
                style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
            candidates.forEach { candidate ->
                val targetRole = candidate.normalizedRole().ifBlank { "member" }
                val canMakeOwner = canChangeServerMemberRole(normalizedActorRole, targetRole, "owner")
                val canMakeAdmin = canChangeServerMemberRole(normalizedActorRole, targetRole, "admin")
                if (canMakeOwner) {
                    SettingsServerActionRow(
                        title = candidate.name,
                        detail = candidate.detail.orEmpty().ifBlank { "Member" },
                        buttonLabel = actionState.serverStatusFor(SERVER_ACTION_MEMBER_ROLE, "Make Owner", candidate.id),
                        buttonIcon = SlockLucideIconName.ShieldCheck,
                        buttonColor = SlockColor.AccentPink,
                        enabled = actionState?.inProgress != true,
                        onClick = { onAction(SERVER_ACTION_MEMBER_ROLE, "${candidate.id}:owner") }
                    )
                }
                if (canMakeAdmin) {
                    SettingsServerActionRow(
                        title = candidate.name,
                        detail = candidate.detail.orEmpty().ifBlank { "Member" },
                        buttonLabel = actionState.serverStatusFor(SERVER_ACTION_MEMBER_ROLE, "Make Admin", candidate.id),
                        buttonIcon = SlockLucideIconName.ShieldCheck,
                        buttonColor = SlockColor.BrandYellow,
                        enabled = actionState?.inProgress != true,
                        onClick = { onAction(SERVER_ACTION_MEMBER_ROLE, "${candidate.id}:admin") }
                    )
                }
            }
            SettingsFormDivider()
        }
        if (admins.isEmpty()) {
            SettingsFormEmptyText("No owners or admins yet.")
        } else {
            admins.forEach { admin ->
                val targetRole = admin.normalizedRole()
                val canDemote = canChangeServerMemberRole(normalizedActorRole, targetRole, "member") && !(targetRole == "owner" && ownerCount <= 1)
                val fallback = if (targetRole == "owner") "Remove Owner" else "Remove Admin"
                val disabledLabel =
                    if (targetRole == "owner" && ownerCount <= 1) {
                        "Last owner"
                    } else {
                        "Owner only"
                    }
                SettingsServerActionRow(
                    title = admin.name,
                    detail = listOfNotNull(admin.detail, admin.role).joinToString(" · ").ifBlank { admin.role ?: "Admin" },
                    buttonLabel = if (canDemote) actionState.serverStatusFor(SERVER_ACTION_MEMBER_ROLE, fallback, admin.id) else disabledLabel,
                    buttonIcon = SlockLucideIconName.UserMinus,
                    buttonColor = SlockColor.SurfaceWhite,
                    enabled = canDemote && actionState?.inProgress != true,
                    onClick = { onAction(SERVER_ACTION_MEMBER_ROLE, "${admin.id}:member") }
                )
            }
        }
        if (actionState?.action == SERVER_ACTION_MEMBER_ROLE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
    }
}

@Composable
private fun SettingsServerInvitesCard(
    invites: List<SettingsServerInviteSnapshot>,
    section: SettingsServerSectionSnapshot?,
    email: String,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    timeContext: SlockTimeContext?,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onAction: (String, String?) -> Unit
) {
    val trimmedEmail = email.trim()
    val busy = actionState?.action == SERVER_ACTION_INVITE_CREATE && actionState.inProgress
    val enabled = !busy && trimmedEmail.contains("@") && trimmedEmail.contains(".")
    SettingsFormSectionCard(iconName = SlockLucideIconName.MailPlus, title = "Pending Invites") {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        SlockBrutalTextField(
            label = "Email",
            value = email,
            placeholder = "teammate@example.com",
            onValueChange = onEmailChange,
            required = true,
            enabled = !busy
        )
        SlockBrutalActionButton(
            label = actionState.serverStatusFor(SERVER_ACTION_INVITE_CREATE, "Send invite"),
            onClick = onSubmit,
            enabled = enabled,
            leadingIcon = SlockLucideIconName.MailPlus,
            backgroundColor = SlockColor.BrandYellow
        )
        SettingsFormDivider()
        if (invites.isEmpty()) {
            SettingsFormEmptyText("No pending invites.")
        } else {
            invites.forEach { invite ->
                SettingsServerActionRow(
                    title = invite.email,
                    detail =
                        listOfNotNull(
                            invite.inviterName?.let { "Invited by $it" },
                            formatSettingsTimestampLabel(invite.createdAtLabel, timeContext)
                        ).joinToString(" · "),
                    buttonLabel = actionState.serverStatusFor(SERVER_ACTION_INVITE_REVOKE, "Revoke", invite.id),
                    buttonIcon = SlockLucideIconName.X,
                    buttonColor = SlockColor.SurfaceWhite,
                    enabled = actionState?.inProgress != true,
                    onClick = { onAction(SERVER_ACTION_INVITE_REVOKE, invite.id) }
                )
            }
        }
        if (actionState?.action == SERVER_ACTION_INVITE_REVOKE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
    }
}

@Composable
private fun SettingsServerJoinLinksCard(
    links: List<SettingsServerJoinLinkSnapshot>,
    section: SettingsServerSectionSnapshot?,
    maxUses: String,
    expiresAt: String,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    timeContext: SlockTimeContext?,
    onMaxUsesChange: (String) -> Unit,
    onExpiresAtChange: (String) -> Unit,
    onCreate: (SettingsServerJoinLinkCreateRequest) -> Unit,
    onAction: (String, String?) -> Unit
) {
    val trimmedMaxUses = maxUses.trim()
    val parsedMaxUses = trimmedMaxUses.takeIf { it.isNotBlank() }?.toIntOrNull()
    val maxUsesInvalid = trimmedMaxUses.isNotBlank() && (parsedMaxUses == null || parsedMaxUses <= 0)
    val createBusy = actionState?.action == SERVER_ACTION_JOIN_LINK_CREATE && actionState.inProgress
    val createEnabled = !createBusy && !maxUsesInvalid
    SettingsFormSectionCard(iconName = SlockLucideIconName.Link2, title = "Join Links") {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        SlockBrutalTextField(
            label = "Max Uses",
            value = maxUses,
            placeholder = "Unlimited",
            onValueChange = onMaxUsesChange,
            optional = true,
            enabled = !createBusy
        )
        SlockBrutalTextField(
            label = "Expires At",
            value = expiresAt,
            placeholder = "2026-06-01T09:00:00Z",
            onValueChange = onExpiresAtChange,
            optional = true,
            enabled = !createBusy
        )
        if (maxUsesInvalid) {
            SettingsFormErrorText("Max Uses must be a positive number.")
        }
        SlockBrutalActionButton(
            label = actionState.serverStatusFor(SERVER_ACTION_JOIN_LINK_CREATE, "Create Join Link"),
            onClick = {
                onCreate(
                    SettingsServerJoinLinkCreateRequest(
                        expiresAt = expiresAt.trim().ifBlank { null },
                        maxUses = parsedMaxUses?.takeIf { it > 0 }
                    )
                )
            },
            enabled = createEnabled,
            leadingIcon = SlockLucideIconName.Plus,
            backgroundColor = SlockColor.AccentPink
        )
        SettingsFormDivider()
        if (links.isEmpty()) {
            SettingsFormEmptyText("No active join links.")
        } else {
            links.forEach { link ->
                SettingsServerActionRow(
                    title = link.url ?: link.label,
                    detail =
                        listOfNotNull(
                            link.maxUsesLabel,
                            link.usageLabel,
                            formatSettingsTimestampLabel(link.expiresAtLabel, timeContext)
                        ).joinToString(" · "),
                    buttonLabel = actionState.serverStatusFor(SERVER_ACTION_JOIN_LINK_REVOKE, "Revoke", link.id),
                    buttonIcon = SlockLucideIconName.Unlink,
                    buttonColor = SlockColor.SurfaceWhite,
                    enabled = actionState?.inProgress != true,
                    onClick = { onAction(SERVER_ACTION_JOIN_LINK_REVOKE, link.id) }
                )
            }
        }
        if (actionState?.action == SERVER_ACTION_JOIN_LINK_REVOKE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
    }
}

@Composable
private fun SettingsServerAgreementCard(
    agreement: SettingsServerAgreementSnapshot?,
    section: SettingsServerSectionSnapshot?,
    enabled: Boolean,
    title: String,
    bodyMarkdown: String,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    onEnabledChange: (Boolean) -> Unit,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val canSave = agreement?.canSave == true
    val busy = actionState?.action == SERVER_ACTION_AGREEMENT_SAVE && actionState.inProgress
    val bodyTooLong = bodyMarkdown.length > PreJoinAgreementBodyMaxLength
    val titleTooLong = title.length > 160
    val dirty =
        agreement != null &&
            (enabled != agreement.enabled || title.trim() != agreement.title || bodyMarkdown != agreement.bodyMarkdown)
    val saveEnabled = canSave && dirty && !busy && !bodyTooLong && !titleTooLong
    SettingsFormSectionCard(iconName = SlockLucideIconName.FileCheck, title = "Pre-join Agreement") {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        SegmentedControl(
            selectedValue = if (enabled) "enabled" else "disabled",
            options = listOf(
                SegmentedControlOption("enabled", "Required", SlockLucideIconName.CircleCheck),
                SegmentedControlOption("disabled", "Disabled", SlockLucideIconName.X)
            ),
            onValueSelected = { onEnabledChange(it == "enabled") },
            enabled = canSave && !busy
        )
        SlockBrutalTextField(
            label = "Title",
            value = title,
            placeholder = "Community agreement",
            onValueChange = { onTitleChange(it.take(180)) },
            required = enabled,
            enabled = canSave && !busy
        )
        SlockBrutalTextField(
            label = "Body Markdown",
            value = bodyMarkdown,
            placeholder = "Write the agreement new members must accept.",
            onValueChange = onBodyChange,
            required = enabled,
            enabled = canSave && !busy,
            maxLines = 7,
            fieldMinHeight = 142.dp
        )
        SettingsFormDetailText("${bodyMarkdown.length} / $PreJoinAgreementBodyMaxLength characters")
        if (titleTooLong) {
            SettingsFormErrorText("Agreement title must be 160 characters or fewer.")
        }
        if (bodyTooLong) {
            SettingsFormErrorText("Agreement body must be 5,000 characters or fewer.")
        }
        SettingsAgreementPreview(bodyMarkdown)
        if (actionState?.action == SERVER_ACTION_AGREEMENT_SAVE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
        if (canSave) {
            SlockBrutalActionButton(
                label = actionState.serverStatusFor(SERVER_ACTION_AGREEMENT_SAVE, "Save Agreement"),
                onClick = onSubmit,
                enabled = saveEnabled,
                leadingIcon = SlockLucideIconName.Save,
                backgroundColor = SlockColor.BrandYellow
            )
        }
    }
}

@Composable
private fun SettingsAgreementPreview(bodyMarkdown: String) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Preview",
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        if (bodyMarkdown.isBlank()) {
            SettingsFormEmptyText("Agreement preview will appear here.")
        } else {
            SlockMarkdownBody(
                body = bodyMarkdown,
                textStyle = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextBlack)
            )
        }
    }
}

@Composable
private fun SettingsServerOnboardingCard(
    onboarding: SettingsServerOnboardingSnapshot?,
    section: SettingsServerSectionSnapshot?,
    selectedAgentId: String,
    greetingEnabled: Boolean,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    onAgentSelected: (String) -> Unit,
    onGreetingEnabledChange: (Boolean) -> Unit,
    onSubmit: () -> Unit
) {
    val canSave = onboarding?.canSave == true
    val busy = actionState?.action == SERVER_ACTION_ONBOARDING_SAVE && actionState.inProgress
    val currentAgentId = onboarding?.onboardingAgentId.orEmpty()
    val currentGreeting = onboarding?.agentAllChannelGreetingEnabled ?: true
    val dirty = onboarding != null && (selectedAgentId != currentAgentId || greetingEnabled != currentGreeting)
    val agents = onboarding?.agents.orEmpty()
    SettingsFormSectionCard(iconName = SlockLucideIconName.Bot, title = "Onboarding") {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        Text(
            "Human Onboarding Agent",
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        SettingsServerSelectRow(
            title = "Disabled (no automatic onboarding)",
            detail = "Do not assign an agent to greet new humans automatically.",
            selected = selectedAgentId.isBlank(),
            enabled = canSave && !busy,
            onClick = { onAgentSelected("") }
        )
        if (agents.isEmpty()) {
            SettingsFormEmptyText("No active agents are available for onboarding.")
        } else {
            agents.forEach { agent ->
                SettingsServerSelectRow(
                    title = agent.onboardingAgentLabel(),
                    detail = agent.name.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty(),
                    selected = selectedAgentId == agent.id,
                    enabled = canSave && !busy,
                    onClick = { onAgentSelected(agent.id) }
                )
            }
        }
        SettingsFormDivider()
        Text(
            "New Agent Greeting",
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        SegmentedControl(
            selectedValue = if (greetingEnabled) "yes" else "no",
            options = listOf(
                SegmentedControlOption("yes", "Yes", SlockLucideIconName.MessageCircle),
                SegmentedControlOption("no", "No", SlockLucideIconName.MessageCircleOff)
            ),
            onValueSelected = { onGreetingEnabledChange(it == "yes") },
            enabled = canSave && !busy
        )
        if (actionState?.action == SERVER_ACTION_ONBOARDING_SAVE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
        if (canSave) {
            SlockBrutalActionButton(
                label = actionState.serverStatusFor(SERVER_ACTION_ONBOARDING_SAVE, "Save Onboarding"),
                onClick = onSubmit,
                enabled = dirty && !busy,
                leadingIcon = SlockLucideIconName.Save,
                backgroundColor = SlockColor.BrandYellow
            )
        }
    }
}

@Composable
private fun SettingsServerMemberPermissionsCard(
    profile: ai.slock.compose.data.SettingsServerProfileSnapshot?,
    section: SettingsServerSectionSnapshot?,
    hideHumansFromMembers: Boolean,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    onHideHumansFromMembersChange: (Boolean) -> Unit,
    onSubmit: () -> Unit
) {
    val canSave = profile?.canSave == true
    val busy = actionState?.action == SERVER_ACTION_MEMBER_PERMISSIONS_SAVE && actionState.inProgress
    val dirty = profile != null && hideHumansFromMembers != profile.hideHumansFromMembers
    SettingsFormSectionCard(iconName = SlockLucideIconName.UsersRound, title = "Member Permissions") {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        SegmentedControl(
            selectedValue = if (hideHumansFromMembers) "hidden" else "visible",
            options = listOf(
                SegmentedControlOption("hidden", "Hide Humans", SlockLucideIconName.EyeOff),
                SegmentedControlOption("visible", "Visible", SlockLucideIconName.Eye)
            ),
            onValueSelected = { onHideHumansFromMembersChange(it == "hidden") },
            enabled = canSave && !busy
        )
        SettingsFormDetailText("Member role users cannot see server-wide human lists, graph surfaces, or human counts when hidden.")
        if (actionState?.action == SERVER_ACTION_MEMBER_PERMISSIONS_SAVE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
        if (canSave) {
            SlockBrutalActionButton(
                label = actionState.serverStatusFor(SERVER_ACTION_MEMBER_PERMISSIONS_SAVE, "Save Permissions"),
                onClick = onSubmit,
                enabled = dirty && !busy,
                leadingIcon = SlockLucideIconName.Save,
                backgroundColor = SlockColor.BrandYellow
            )
        }
    }
}

@Composable
private fun SettingsServerSelectRow(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite
    val shadowOffset = if (selected) 3.dp else 0.dp
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = shadowOffset, end = shadowOffset)) {
        if (shadowOffset > 0.dp) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = shadowOffset, y = shadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(background)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockLucideIcon(
                if (selected) SlockLucideIconName.CircleCheck else SlockLucideIconName.Circle,
                modifier = Modifier.size(16.dp),
                color = "#FF000000",
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = SlockType.Button.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
                detail.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
                }
            }
        }
    }
}

private fun SettingsServerOnboardingAgentSnapshot.onboardingAgentLabel(): String =
    displayName?.takeIf { it.isNotBlank() } ?: name.ifBlank { id }

@Composable
private fun SettingsFormDetailText(text: String) {
    Text(text, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
}

@Composable
private fun SettingsFormEmptyText(text: String) {
    Text(text, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
}

@Composable
private fun SettingsFormErrorText(text: String) {
    Text(text, style = SlockType.Caption.copy(color = SlockColor.AccentRed), color = SlockColor.AccentRed)
}

@Composable
private fun SettingsFormDivider() {
    Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
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
            SlockLucideIcon(
                payload.heroIconName,
                modifier = Modifier.size(26.dp)
            )
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
                SlockLucideIcon(
                    row.iconName,
                    modifier = Modifier.size(18.dp)
                )
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
    bottomContentPadding: Dp = SlockSpacing.Lg,
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
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding) { action, value ->
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
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onStartProfiler: () -> Unit,
    onStopProfiler: () -> Unit,
    onResetProfiler: () -> Unit,
    onRefreshProfilerReport: () -> Unit,
    onExportProfilerReport: () -> Unit,
    onSendFeedback: () -> Unit
) {
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>)?.data
    val payload = (snapshot?.entries ?: emptyList()).toDiagnosticsPayload(state, actionState)
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding) { action, _ ->
        when (action) {
            "copyLogs" -> onCopyLogs()
            "shareLogs" -> onShareLogs()
            "clearLogs" -> onClearLogs()
            "startProfiler" -> onStartProfiler()
            "stopProfiler" -> onStopProfiler()
            "resetProfiler" -> onResetProfiler()
            "refreshProfilerReport" -> onRefreshProfilerReport()
            "exportProfilerReport" -> onExportProfilerReport()
            "feedback" -> onSendFeedback()
        }
    }
}

@Composable
private fun SettingsDetailShell(
    title: String,
    subtitle: String,
    iconName: SlockLucideIconName,
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
            SlockLucideIcon(SlockLucideIconName.ArrowLeft, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SettingsSharedPlaceholder(
    title: String,
    detail: String,
    iconName: SlockLucideIconName,
    bottomContentPadding: Dp = SlockSpacing.Lg
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).padding(bottom = bottomContentPadding),
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
                    iconName = SlockLucideIconName.User,
                    value = session?.user?.bestDisplayName,
                    selected = selectedDestination == SettingsDestinationAccount
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationComputers,
                    title = "Computers",
                    subtitle = "Connected machines and workspaces",
                    iconName = SlockLucideIconName.Monitor,
                    selected = selectedDestination == SettingsDestinationComputers
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationServer,
                    title = "Server",
                    subtitle = "Workspace profile, admins, invites, onboarding",
                    iconName = SlockLucideIconName.Users,
                    value = session?.currentServerSummary()?.role?.replaceFirstChar { it.uppercase() },
                    selected = selectedDestination == SettingsDestinationServer
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationConnectedApps,
                    title = "Connected Apps",
                    subtitle = "Marketplace, installed apps, OAuth clients",
                    iconName = SlockLucideIconName.Blocks,
                    selected = selectedDestination == SettingsDestinationConnectedApps
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationBilling,
                    title = "Billing",
                    subtitle = "Plan and usage",
                    iconName = SlockLucideIconName.ShieldCheck,
                    value = session?.currentServerSummary()?.plan?.replaceFirstChar { it.uppercase() },
                    selected = selectedDestination == SettingsDestinationBilling
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationLanguageRegion,
                    title = "Language & Region",
                    subtitle = "Language, timezone, translation",
                    iconName = SlockLucideIconName.Languages,
                    selected = selectedDestination == SettingsDestinationLanguageRegion
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationAppearance,
                    title = "Appearance",
                    subtitle = "Message font size and reading density",
                    iconName = SlockLucideIconName.Type,
                    selected = selectedDestination == SettingsDestinationAppearance
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationNotifications,
                    title = "Notifications",
                    subtitle = "Push status and workspace mute",
                    iconName = SlockLucideIconName.Bell,
                    selected = selectedDestination == SettingsDestinationNotifications
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationMobile,
                    title = "Mobile",
                    subtitle = "Device language and message alignment",
                    iconName = SlockLucideIconName.Smartphone,
                    selected = selectedDestination == SettingsDestinationMobile
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationStorage,
                    title = "Storage",
                    subtitle = "Local cache and temporary files",
                    iconName = SlockLucideIconName.Archive,
                    selected = selectedDestination == SettingsDestinationStorage
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationAbout,
                    title = "About",
                    subtitle = "Version, diagnostics, feedback",
                    iconName = SlockLucideIconName.Bug,
                    selected = selectedDestination == SettingsDestinationAbout
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationRouter,
                    title = "Developer Router",
                    subtitle = "Open Kuikly route launcher and icon compare",
                    iconName = SlockLucideIconName.LayoutList,
                    selected = selectedDestination == SettingsDestinationRouter
                )
            )
    )

private fun ScreenLoadState<AccountSettingsSnapshot>.toAccountPayload(
    session: StoredAuthSession?,
    actionState: ai.slock.compose.data.SharedSettingsActionState? = null
): KuiklySettingsAccountPayload {
    val account = (this as? ScreenLoadState.Content<AccountSettingsSnapshot>)?.data
    val providerActionId =
        actionState
            ?.itemId
            ?.takeIf { actionState.action == ACCOUNT_ACTION_PROVIDER_CONNECT }
    return (account?.toAccountPayload() ?: appShellAccountPayload(session))
        .copy(
            saveInProgress = actionState?.action == ACCOUNT_ACTION_PROFILE_SAVE && actionState.inProgress,
            saveErrorCode = actionState?.errorCode.takeIf { actionState?.action == ACCOUNT_ACTION_PROFILE_SAVE },
            avatarInProgress = actionState?.action == ACCOUNT_ACTION_AVATAR_UPLOAD && actionState.inProgress,
            avatarErrorCode = actionState?.errorCode.takeIf { actionState?.action == ACCOUNT_ACTION_AVATAR_UPLOAD },
            passwordInProgress = actionState?.action == ACCOUNT_ACTION_PASSWORD_CHANGE && actionState.inProgress,
            passwordErrorCode = actionState?.errorCode.takeIf { actionState?.action == ACCOUNT_ACTION_PASSWORD_CHANGE },
            providerConnectingId = providerActionId.takeIf { actionState?.inProgress == true },
            providerErrorId = providerActionId.takeIf { actionState?.errorCode != null },
            providerErrorCode = actionState?.errorCode.takeIf { providerActionId != null }
        )
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
                    providerId = it.providerId,
                    provider = it.provider,
                    identifier = it.identifier,
                    status =
                        when (it.status) {
                            ConnectedAccountStatus.Connected -> "connected"
                            ConnectedAccountStatus.NotConnected -> "notConnected"
                            ConnectedAccountStatus.Loading -> "loading"
                            ConnectedAccountStatus.Failed -> "failed"
                        },
                    canConnect = it.canConnect
                )
            },
        canEditProfile = canEditProfile,
        canUploadAvatar = canUploadAvatar,
        canChangePassword = canChangePassword
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
        connectedAccounts =
            user
                ?.email
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    listOf(
                        KuiklySettingsConnectedAccount(
                            providerId = "password",
                            provider = "Email password",
                            identifier = it,
                            status = "connected",
                            canConnect = false
                        )
                    )
                }
                .orEmpty()
    )
}

private fun ScreenLoadState<SettingsComputersSnapshot>.toComputersPayload(
    refreshing: Boolean,
    timeContext: SlockTimeContext?
): KuiklySettingsComputersPayload =
    when (this) {
        is ScreenLoadState.Content ->
            data.toComputersPayload(refreshing, timeContext)
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

private fun SettingsComputersSnapshot.toComputersPayload(
    refreshing: Boolean,
    timeContext: SlockTimeContext?
): KuiklySettingsComputersPayload =
    KuiklySettingsComputersPayload(
        instanceId = "shared-shell-settings-computers",
        status = if (refreshing) "refreshing" else "content",
        computers = computers.map { it.toComputerItem(timeContext) },
        canAddComputer = canAddComputer,
        addComputerUnavailableReason = addComputerUnavailableReason,
        latestDaemonVersion = latestDaemonVersion
    )

private fun ScreenLoadState<SettingsComputerDetailSnapshot>.toComputerDetailPayload(
    selectedComputerId: String?,
    timeContext: SlockTimeContext?
): KuiklySettingsComputerDetailPayload =
    when (this) {
        is ScreenLoadState.Content ->
            data.toComputerDetailPayload(timeContext)
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

private fun SettingsComputerDetailSnapshot.toComputerDetailPayload(
    timeContext: SlockTimeContext?
): KuiklySettingsComputerDetailPayload =
    KuiklySettingsComputerDetailPayload(
        instanceId = "shared-shell-settings-computer-${computer.id}",
        computer = computer.toComputerItem(timeContext),
        workspaces = workspaces.map { it.toWorkspaceItem(timeContext) },
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

private fun SettingsComputerSnapshot.toComputerItem(
    timeContext: SlockTimeContext?
): KuiklySettingsComputerItem =
    KuiklySettingsComputerItem(
        id = id,
        name = name,
        daemonVersion = daemonVersion,
        online = online,
        hostname = hostname,
        platform = platform,
        lastSeenLabel = formatSettingsTimestampLabel(lastSeenLabel, timeContext),
        runtimes = runtimes,
        createdAt = formatSettingsTimestampLabel(createdAt, timeContext),
        apiKeyPrefix = apiKeyPrefix,
        statusVersion = statusVersion
    )

private fun SharedSettingsActionState.toComputerActionState(): KuiklySettingsComputerActionState =
    KuiklySettingsComputerActionState(
        action = action,
        itemId = itemId,
        inProgress = inProgress,
        errorCode = errorCode
    )

private fun SettingsComputerWorkspaceSnapshot.toWorkspaceItem(
    timeContext: SlockTimeContext?
): KuiklySettingsWorkspaceItem =
    KuiklySettingsWorkspaceItem(
        id = id,
        name = name,
        path = path,
        statusLabel = statusLabel,
        lastActiveLabel = formatSettingsTimestampLabel(lastActiveLabel, timeContext)
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

private fun storageTargetIconName(target: String): SlockLucideIconName =
    when (target) {
        "database" -> SlockLucideIconName.Archive
        "imageCache" -> SlockLucideIconName.Image
        "temporaryAndLogs" -> SlockLucideIconName.FileText
        else -> SlockLucideIconName.Archive
    }

private fun ScreenLoadState<SettingsBillingSnapshot>.toBillingPayload(
    session: StoredAuthSession?,
    timeContext: SlockTimeContext?
): KuiklySettingsBillingPayload {
    val billing = (this as? ScreenLoadState.Content<SettingsBillingSnapshot>)?.data
    val fallbackPlan = session?.currentServerSummary()?.plan?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Hobby"
    return KuiklySettingsBillingPayload(
        instanceId = "shared-shell-settings-billing",
        status =
            when (this) {
                ScreenLoadState.Loading -> "loading"
                is ScreenLoadState.NoPermission -> "noPermission"
                is ScreenLoadState.Error,
                is ScreenLoadState.Empty -> "error"
                is ScreenLoadState.Content -> "content"
            },
        currentPlan =
            KuiklySettingsCurrentPlan(
                planName = billing?.currentPlan?.planName ?: fallbackPlan,
                usageRows =
                    billing?.currentPlan?.usageRows
                        ?.map { row ->
                            KuiklySettingsPlanUsageRow(
                                label = row.label,
                                value = row.value.formatSettingsBillingUsageValue(timeContext)
                            )
                        }
                        .orEmpty(),
                trialActive = billing?.currentPlan?.trialActive ?: false,
                description = billing?.currentPlan?.description.orEmpty(),
                includedFeatures = billing?.currentPlan?.includedFeatures.orEmpty(),
                excludedFeatures = billing?.currentPlan?.excludedFeatures.orEmpty()
            ),
        notices =
            billing?.notices?.map { notice ->
                KuiklySettingsBillingNotice(
                    key = notice.key,
                    title = notice.title,
                    detail = notice.detail.formatSettingsBillingUsageValue(timeContext),
                    tone = notice.tone,
                    rows =
                        notice.rows.map { row ->
                            KuiklySettingsPlanUsageRow(
                                label = row.label,
                                value = row.value.formatSettingsBillingUsageValue(timeContext)
                            )
                        }
                )
            }.orEmpty(),
        actions =
            billing?.actions?.map { action ->
                KuiklySettingsBillingAction(
                    key = action.key,
                    label = action.label,
                    detail = action.detail,
                    enabled = action.enabled,
                    style = action.style
                )
            }.orEmpty(),
        comparePlansUrl = billing?.comparePlansUrl.orEmpty().ifBlank { "https://raft.build/#pricing" },
        plans =
            billing?.plans?.map { plan ->
                KuiklySettingsBillingPlan(
                    id = plan.name.lowercase().replace(" ", "-"),
                    name = plan.name,
                    price = plan.price,
                    features = plan.features,
                    current = plan.current,
                    comingSoon = plan.comingSoon,
                    badgeLabel = plan.badgeLabel
                )
            } ?: listOf(
                KuiklySettingsBillingPlan(id = fallbackPlan.lowercase(), name = fallbackPlan, price = "Loading billing snapshot", current = true)
            )
    )
}

private fun ScreenLoadState<SettingsServerSnapshot>.toServerPayload(
    session: StoredAuthSession?,
    refreshing: Boolean,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    timeContext: SlockTimeContext?
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
            is ScreenLoadState.Content -> "Manage this workspace's profile, members, invites, and permissions."
        }
    val sectionsByKey = snapshot?.sections.orEmpty().associateBy { it.key }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-server",
        title = "Server",
        heroIconName = SlockLucideIconName.Users,
        heroLabel = snapshot?.profile?.name ?: server?.name ?: "Workspace settings",
        detail = detail,
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Profile",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("serverName", SlockLucideIconName.BadgeInfo, "Workspace name", "Current selected workspace profile.", status = snapshot?.profile?.name ?: server?.name ?: "Unknown"),
                            KuiklySharedSettingsRow(
                                SERVER_ACTION_PROFILE_SAVE,
                                SlockLucideIconName.Save,
                                "Save workspace profile",
                                "Save changes to the workspace name and profile.",
                                status = actionState.serverStatusFor(SERVER_ACTION_PROFILE_SAVE, if (snapshot?.profile?.canSave == true) "Ready" else "Read only"),
                                enabled = snapshot?.profile?.canSave == true && actionState?.inProgress != true
                            ),
                            KuiklySharedSettingsRow("serverSlug", SlockLucideIconName.Link, "Slug", snapshot?.profile?.slug?.let { "/$it" } ?: server?.slug?.let { "/$it" } ?: "Not provided", status = if (snapshot?.profile?.canSave == true) "Editable" else "Read only"),
                            KuiklySharedSettingsRow("serverRole", SlockLucideIconName.ShieldCheck, "Your role", "Permission scope for workspace settings.", status = role)
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Admins",
                    rows = snapshot?.admins.toAdminRows(sectionsByKey["admins"], actionState)
                ),
                KuiklySharedSettingsSection(
                    title = "Pending Invites",
                    rows = snapshot?.pendingInvites.toInviteRows(sectionsByKey["pending_invites"], actionState, timeContext)
                ),
                KuiklySharedSettingsSection(
                    title = "Join Links",
                    rows = snapshot?.joinLinks.toJoinLinkRows(sectionsByKey["join_links"], actionState, timeContext)
                ),
                KuiklySharedSettingsSection(
                    title = "Pre-join Agreement",
                    rows = snapshot?.agreement.toAgreementRows(sectionsByKey["pre_join_agreement"], actionState)
                ),
                KuiklySharedSettingsSection(
                    title = "Onboarding",
                    rows = snapshot?.onboarding.toOnboardingRows(sectionsByKey["onboarding_agent"], actionState)
                ),
                KuiklySharedSettingsSection(
                    title = "Member Permissions",
                    rows = snapshot?.profile.toMemberPermissionsRows(sectionsByKey["member_permissions"], actionState)
                ),
                KuiklySharedSettingsSection(
                    title = "Translation",
                    rows = snapshot?.translation.toTranslationRows(sectionsByKey["translation"], actionState)
                ),
                KuiklySharedSettingsSection(
                    title = "Sidebar Order",
                    rows = snapshot?.sidebarOrder.toSidebarOrderRows(sectionsByKey["sidebar_order"], actionState)
                ),
                KuiklySharedSettingsSection(
                    title = "Plan",
                    rows =
                        snapshot.toPlanRows(sectionsByKey["billing"], plan)
                )
            )
    )
}

private fun List<SettingsServerAdminSnapshot>?.toAdminRows(
    section: SettingsServerSectionSnapshot?,
    actionState: ai.slock.compose.data.SharedSettingsActionState?
): List<KuiklySharedSettingsRow> {
    val admins = this.orEmpty()
    if (admins.isEmpty()) {
        return listOf(section.toPlaceholderRow("admins", SlockLucideIconName.Shield, "Admins", "No workspace admins are available."))
    }
    return admins.flatMap { admin ->
        val nextRole = if (admin.role?.lowercase() == "admin") "member" else "admin"
        listOf(
            KuiklySharedSettingsRow(
                key = "admin:${admin.id}",
                iconName = SlockLucideIconName.Shield,
                title = admin.name,
                detail = admin.detail.orEmpty().ifBlank { "Workspace administrator" },
                status = admin.role?.replaceFirstChar { it.uppercase() }.orEmpty(),
                enabled = false
            ),
            KuiklySharedSettingsRow(
                key = SERVER_ACTION_MEMBER_ROLE,
                iconName = SlockLucideIconName.ShieldCheck,
                title = "Change ${admin.name}'s role",
                detail = "Switch this member between admin and member access.",
                value = "${admin.id}:$nextRole",
                status = actionState.serverStatusFor(SERVER_ACTION_MEMBER_ROLE, "Set ${nextRole.replaceFirstChar { it.uppercase() }}", admin.id),
                enabled = actionState?.inProgress != true
            ),
            KuiklySharedSettingsRow(
                key = SERVER_ACTION_MEMBER_REMOVE,
                iconName = SlockLucideIconName.UserMinus,
                title = "Remove ${admin.name}",
                detail = "Remove this member from the workspace after confirmation.",
                value = admin.id,
                status = actionState.serverStatusFor(SERVER_ACTION_MEMBER_REMOVE, "Remove", admin.id),
                enabled = actionState?.inProgress != true
            )
        )
    }
}

private fun List<SettingsServerInviteSnapshot>?.toInviteRows(
    section: SettingsServerSectionSnapshot?,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    timeContext: SlockTimeContext?
): List<KuiklySharedSettingsRow> {
    val invites = this.orEmpty()
    if (invites.isEmpty()) {
        return listOf(
            section.toPlaceholderRow("pendingInvites", SlockLucideIconName.MailPlus, "Pending invites", "No pending invites are available.")
        )
    }
    return invites.flatMap { invite ->
        listOf(
            KuiklySharedSettingsRow(
                key = "invite:${invite.id}",
                iconName = SlockLucideIconName.MailPlus,
                title = invite.email,
                detail =
                    listOfNotNull(
                        invite.inviterName?.let { "Invited by $it" },
                        formatSettingsTimestampLabel(invite.createdAtLabel, timeContext)
                    ).joinToString(" · "),
                status = "Pending",
                enabled = false
            ),
            KuiklySharedSettingsRow(
                key = SERVER_ACTION_INVITE_REVOKE,
                iconName = SlockLucideIconName.Trash2,
                title = "Revoke invite",
                detail = "Cancel ${invite.email}'s pending invite.",
                value = invite.id,
                status = actionState.serverStatusFor(SERVER_ACTION_INVITE_REVOKE, "Revoke", invite.id),
                enabled = actionState?.inProgress != true
            )
        )
    }
}

private fun List<SettingsServerJoinLinkSnapshot>?.toJoinLinkRows(
    section: SettingsServerSectionSnapshot?,
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    timeContext: SlockTimeContext?
): List<KuiklySharedSettingsRow> {
    val links = this.orEmpty()
    val createRow =
        KuiklySharedSettingsRow(
            key = SERVER_ACTION_JOIN_LINK_CREATE,
            iconName = SlockLucideIconName.Plus,
            title = "Create join link",
            detail = "Create a reusable invite link for this workspace.",
            status = actionState.serverStatusFor(SERVER_ACTION_JOIN_LINK_CREATE, "Create"),
            enabled = actionState?.inProgress != true
        )
    if (links.isEmpty()) {
        return listOf(section.toPlaceholderRow("joinLinks", SlockLucideIconName.Link2, "Join links", "No join links are available."), createRow)
    }
    return links.flatMap { link ->
        listOf(
            KuiklySharedSettingsRow(
                key = "joinLink:${link.id}",
                iconName = SlockLucideIconName.Link2,
                title = link.label,
                detail =
                    listOfNotNull(
                        link.url,
                        link.maxUsesLabel,
                        formatSettingsTimestampLabel(link.expiresAtLabel, timeContext)
                    ).joinToString(" · "),
                status = link.usageLabel.orEmpty(),
                enabled = false
            ),
            KuiklySharedSettingsRow(
                key = SERVER_ACTION_JOIN_LINK_REVOKE,
                iconName = SlockLucideIconName.Unlink,
                title = "Revoke join link",
                detail = link.url ?: "Disable this join link.",
                value = link.id,
                status = actionState.serverStatusFor(SERVER_ACTION_JOIN_LINK_REVOKE, "Revoke", link.id),
                enabled = actionState?.inProgress != true
            )
        )
    } + createRow
}

private fun SettingsServerAgreementSnapshot?.toAgreementRows(
    section: SettingsServerSectionSnapshot?,
    actionState: ai.slock.compose.data.SharedSettingsActionState?
): List<KuiklySharedSettingsRow> =
    listOf(
        this?.let { agreement ->
            KuiklySharedSettingsRow(
                key = SERVER_ACTION_AGREEMENT_SAVE,
                iconName = SlockLucideIconName.FileCheck,
                title = "Pre-join Agreement",
                detail = agreement.detail,
                value = agreement.enabled.toString(),
                status = actionState.serverStatusFor(SERVER_ACTION_AGREEMENT_SAVE, agreement.statusLabel),
                enabled = agreement.canSave && actionState?.inProgress != true
            )
        } ?: section.toPlaceholderRow("preJoinAgreement", SlockLucideIconName.FileCheck, "Pre-join Agreement", "No pre-join agreement settings are available.")
    )

private fun SettingsServerOnboardingSnapshot?.toOnboardingRows(
    section: SettingsServerSectionSnapshot?,
    actionState: ai.slock.compose.data.SharedSettingsActionState?
): List<KuiklySharedSettingsRow> =
    listOf(
        this?.let { onboarding ->
            KuiklySharedSettingsRow(
                key = SERVER_ACTION_ONBOARDING_SAVE,
                iconName = SlockLucideIconName.Bot,
                title = "Onboarding",
                detail = onboarding.detail,
                value = onboarding.onboardingAgentId.orEmpty(),
                status = actionState.serverStatusFor(SERVER_ACTION_ONBOARDING_SAVE, onboarding.statusLabel),
                enabled = onboarding.canSave && actionState?.inProgress != true
            )
        } ?: section.toPlaceholderRow("onboarding", SlockLucideIconName.Bot, "Onboarding Agent", "No onboarding settings are available.")
    )

private fun ai.slock.compose.data.SettingsServerProfileSnapshot?.toMemberPermissionsRows(
    section: SettingsServerSectionSnapshot?,
    actionState: ai.slock.compose.data.SharedSettingsActionState?
): List<KuiklySharedSettingsRow> =
    listOf(
        this?.let { profile ->
            KuiklySharedSettingsRow(
                key = SERVER_ACTION_MEMBER_PERMISSIONS_SAVE,
                iconName = SlockLucideIconName.UsersRound,
                title = "Hide human directory and counts from members",
                detail = "Member role users cannot see server-wide human lists, graph surfaces, or human counts.",
                value = profile.hideHumansFromMembers.toString(),
                status = actionState.serverStatusFor(
                    SERVER_ACTION_MEMBER_PERMISSIONS_SAVE,
                    if (profile.hideHumansFromMembers) "Hidden" else "Visible"
                ),
                enabled = profile.canSave && actionState?.inProgress != true
            )
        } ?: section.toPlaceholderRow("memberPermissions", SlockLucideIconName.UsersRound, "Member Permissions", "No member permission settings are available.")
    )

private fun SettingsServerTranslationSnapshot?.toTranslationRows(
    section: SettingsServerSectionSnapshot?,
    actionState: ai.slock.compose.data.SharedSettingsActionState?
): List<KuiklySharedSettingsRow> =
    listOf(
        this?.let { translation ->
            KuiklySharedSettingsRow(
                key = SERVER_ACTION_TRANSLATION_TOGGLE,
                iconName = SlockLucideIconName.Languages,
                title = "Server translation",
                detail = translation.detail,
                value = (!translation.translationEnabled).toString(),
                status = actionState.serverStatusFor(SERVER_ACTION_TRANSLATION_TOGGLE, translation.statusLabel),
                enabled = translation.translationAvailable && translation.canManageTranslation && actionState?.inProgress != true
            )
        } ?: section.toPlaceholderRow("translation", SlockLucideIconName.Languages, "Translation", "No translation settings are available.")
    )

private fun SettingsServerSidebarOrderSnapshot?.toSidebarOrderRows(
    section: SettingsServerSectionSnapshot?,
    actionState: ai.slock.compose.data.SharedSettingsActionState?
): List<KuiklySharedSettingsRow> =
    listOf(
        this?.let { order ->
            val nextMode = if (order.channelSortMode == "manual") "recent" else "manual"
            KuiklySharedSettingsRow(
                key = SERVER_ACTION_SIDEBAR_ORDER,
                iconName = SlockLucideIconName.ListTree,
                title = "Channel sidebar sort",
                detail = "Choose how channels are ordered in the sidebar.",
                value = nextMode,
                status = actionState.serverStatusFor(SERVER_ACTION_SIDEBAR_ORDER, order.channelSortMode.ifBlank { "manual" }),
                enabled = actionState?.inProgress != true
            )
        } ?: section.toPlaceholderRow("sidebarOrder", SlockLucideIconName.ListTree, "Sidebar Order", "No sidebar order settings are available.")
    )

private fun SettingsServerSnapshot?.toPlanRows(
    billingSection: SettingsServerSectionSnapshot?,
    fallbackPlan: String
): List<KuiklySharedSettingsRow> =
    listOf(
        KuiklySharedSettingsRow(
            key = "billingSummary",
            iconName = SlockLucideIconName.ShieldCheck,
            title = "Plan",
            detail = billingSection?.detail ?: "Current workspace billing and usage summary.",
            status = this?.usageRows?.firstOrNull()?.value ?: fallbackPlan,
            enabled = false
        )
    )

private fun SettingsServerSectionSnapshot?.toPlaceholderRow(
    key: String,
    iconName: SlockLucideIconName,
    title: String,
    fallbackDetail: String
): KuiklySharedSettingsRow =
    KuiklySharedSettingsRow(
        key = key,
        iconName = iconName,
        title = this?.title ?: title,
        detail = this?.detail ?: fallbackDetail,
        status = this?.state?.name ?: "Unavailable",
        enabled = false
    )

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
        heroIconName = SlockLucideIconName.Smartphone,
        heroLabel = "Mobile preferences",
        detail =
            if (refreshing) {
                "Refreshing mobile settings..."
            } else {
                "Manage notifications and local preferences for this device."
            },
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Notifications",
                    rows =
                        listOfNotNull(
                            KuiklySharedSettingsRow(
                                key = MOBILE_ACTION_NOTIFICATION_PREFERENCE,
                                iconName = SlockLucideIconName.BellRing,
                                title = "Notification preference",
                                detail = mobile.pushNotifications.detail,
                                value = (!mobile.pushNotifications.enabled).toString(),
                                status = actionState.statusFor(MOBILE_ACTION_NOTIFICATION_PREFERENCE, mobile.pushNotifications.statusLabel),
                                enabled = mobile.pushNotifications.available && actionState?.inProgress != true
                            ),
                            KuiklySharedSettingsRow(
                                key = MOBILE_ACTION_PERMISSION,
                                iconName = SlockLucideIconName.Bell,
                                title = "Push permission",
                                detail = mobile.notificationPermission.detail,
                                status = actionState.statusFor(MOBILE_ACTION_PERMISSION, mobile.notificationPermission.statusLabel),
                                enabled = mobile.notificationPermission.available && actionState?.inProgress != true
                            ),
                            KuiklySharedSettingsRow(
                                key = MOBILE_ACTION_SETTINGS,
                                iconName = SlockLucideIconName.Settings,
                                title = "System notification settings",
                                detail = mobile.notificationSettings.detail,
                                status = actionState.statusFor(MOBILE_ACTION_SETTINGS, mobile.notificationSettings.statusLabel),
                                enabled = mobile.notificationSettings.available && actionState?.inProgress != true
                            ),
                            KuiklySharedSettingsRow(
                                key = MOBILE_ACTION_TEST_NOTIFICATION,
                                iconName = SlockLucideIconName.Send,
                                title = "Send test notification",
                                detail = mobile.testNotification.detail,
                                status = actionState.statusFor(MOBILE_ACTION_TEST_NOTIFICATION, mobile.testNotification.statusLabel),
                                enabled = mobile.testNotification.available && actionState?.inProgress != true
                            ),
                            mobile.muteServer.takeIf { it.available }?.let {
                                KuiklySharedSettingsRow(
                                    key = MOBILE_ACTION_SERVER_MUTE,
                                    iconName = SlockLucideIconName.VolumeX,
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
                                iconName = SlockLucideIconName.Languages,
                                title = "Language",
                                fallbackStatus = "Unsupported",
                                actionState = actionState
                            ),
                            mobile.messageAlignmentPreference.toMobileSelectionRow(
                                key = MOBILE_ACTION_MESSAGE_ALIGNMENT,
                                iconName = SlockLucideIconName.AlignHorizontalSpaceAround,
                                title = "My message alignment",
                                fallbackStatus = "Unsupported",
                                actionState = actionState
                            )
                        )
                )
            )
    )
}

private fun ScreenLoadState<SettingsLanguageRegionSnapshot>.toLanguageRegionPayload(
    actionState: ai.slock.compose.data.SharedSettingsActionState?,
    refreshing: Boolean
): KuiklySharedSettingsPayload {
    val languageRegion =
        (this as? ScreenLoadState.Content<SettingsLanguageRegionSnapshot>)?.data
            ?: return appShellLanguageRegionPlaceholderPayload(this, refreshing)
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-language-region",
        title = "Language & Region",
        heroIconName = SlockLucideIconName.Languages,
        heroLabel = "Language & Region",
        detail =
            if (refreshing) {
                "Refreshing language and region settings..."
            } else {
                "Manage language, timezone, and translation preferences."
            },
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Language",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow(
                                key = LANGUAGE_REGION_ACTION_SERVER_TRANSLATION,
                                iconName = SlockLucideIconName.Languages,
                                title = "Enable message translation for this server",
                                detail = languageRegion.serverTranslation.detail,
                                value = (!languageRegion.serverTranslation.enabled).toString(),
                                status =
                                    actionState.statusFor(
                                        LANGUAGE_REGION_ACTION_SERVER_TRANSLATION,
                                        languageRegion.serverTranslation.statusLabel
                                    ),
                                enabled = languageRegion.serverTranslation.available && actionState?.inProgress != true
                            ),
                            KuiklySharedSettingsRow(
                                key = LANGUAGE_REGION_ACTION_AUTO_TRANSLATION,
                                iconName = SlockLucideIconName.Wand,
                                title = "Auto-translate messages",
                                detail = languageRegion.autoTranslation.detail,
                                value = (!languageRegion.autoTranslation.enabled).toString(),
                                status =
                                    actionState.statusFor(
                                        LANGUAGE_REGION_ACTION_AUTO_TRANSLATION,
                                        languageRegion.autoTranslation.statusLabel
                                    ),
                                enabled = languageRegion.autoTranslation.available && actionState?.inProgress != true
                            ),
                            languageRegion.browserLanguage.toMobileSelectionRow(
                                key = LANGUAGE_REGION_ACTION_LANGUAGE,
                                iconName = SlockLucideIconName.Globe,
                                title = "Browser language",
                                fallbackStatus = "Others",
                                actionState = actionState
                            )
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Date & Time",
                    rows =
                        listOf(
                            languageRegion.timezone.toMobileSelectionRow(
                                key = LANGUAGE_REGION_ACTION_TIMEZONE,
                                iconName = SlockLucideIconName.Clock,
                                title = "Timezone",
                                fallbackStatus = "UTC",
                                actionState = actionState
                            ),
                            languageRegion.timeFormat.toMobileSelectionRow(
                                key = LANGUAGE_REGION_ACTION_TIME_FORMAT,
                                iconName = SlockLucideIconName.Clock3,
                                title = "Time format",
                                fallbackStatus = "24-hour",
                                actionState = actionState
                            )
                        )
                )
            )
    )
}

private fun appShellLanguageRegionPlaceholderPayload(
    state: ScreenLoadState<SettingsLanguageRegionSnapshot>,
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
            else -> "Loading language and region settings."
        }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-language-region-placeholder",
        title = "Language & Region",
        heroIconName = SlockLucideIconName.Languages,
        heroLabel = "Language & Region",
        detail = detail,
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Status",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow(
                                key = "languageRegionStatus",
                                iconName = SlockLucideIconName.Languages,
                                title = "Language & Region",
                                detail = detail,
                                status = status,
                                enabled = false
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
            else -> "Loading mobile settings."
        }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-mobile-placeholder",
        title = "Mobile",
        heroIconName = SlockLucideIconName.Smartphone,
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
                                iconName = SlockLucideIconName.Smartphone,
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
    iconName: SlockLucideIconName,
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

private fun SettingsServerAdminSnapshot.normalizedRole(): String =
    role.normalizedServerRole()

private fun SettingsServerAdminSnapshot.isPrivilegedServerMember(): Boolean {
    val normalizedRole = normalizedRole()
    return normalizedRole == "owner" || normalizedRole == "admin"
}

private fun String?.normalizedServerRole(): String =
    this?.trim()?.lowercase().orEmpty()

private fun canChangeServerMemberRole(
    actorRole: String,
    targetRole: String,
    nextRole: String
): Boolean =
    when (actorRole) {
        "owner" -> targetRole.isNotBlank() && nextRole in setOf("owner", "admin", "member")
        "admin" -> targetRole == "member" && nextRole == "admin"
        else -> false
    }

private fun ai.slock.compose.data.SharedSettingsActionState?.statusFor(action: String, fallback: String): String =
    when {
        this == null || this.action != action -> fallback
        inProgress -> "Loading"
        errorCode != null -> "Error"
        else -> "Saved"
    }

private fun ai.slock.compose.data.SharedSettingsActionState?.serverStatusFor(
    action: String,
    fallback: String,
    itemId: String? = null
): String =
    when {
        isServerConfirmation(action, itemId) -> "Confirm"
        this == null || this.action != action -> fallback
        itemId != null && this.itemId != itemId -> fallback
        inProgress -> "Loading"
        errorCode != null -> errorCode
        else -> "Saved"
    }

private fun ai.slock.compose.data.SharedSettingsActionState?.connectedAppsStatusFor(
    action: String,
    fallback: String,
    itemId: String? = null
): String =
    when {
        this == null || this.action != action -> fallback
        itemId != null && this.itemId != itemId -> fallback
        inProgress -> "Loading"
        errorCode != null -> errorCode
        else -> "Saved"
    }

private fun ai.slock.compose.data.SharedSettingsActionState?.connectedAppsBusyFor(
    action: String,
    itemId: String? = null
): Boolean =
    this?.action == action &&
        (itemId == null || this.itemId == itemId) &&
        this.inProgress

private fun ai.slock.compose.data.SharedSettingsActionState?.connectedAppsErrorFor(
    action: String,
    itemId: String? = null
): String? =
    this?.takeIf {
        it.action == action &&
            (itemId == null || it.itemId == itemId) &&
            it.errorCode != null
    }?.errorCode

private fun List<SettingsConnectedAppMarketplaceSnapshot>.settingsConnectedAppCategoryOptions(): List<SlockBrutalSelectOption> =
    listOf(SlockBrutalSelectOption(SETTINGS_CONNECTED_APPS_CATEGORY_ALL, "All categories")) +
        map { it.category.trim().ifBlank { "Other" } }
            .distinct()
            .sorted()
            .map { SlockBrutalSelectOption(it, it.settingsConnectedAppCategoryLabel()) }

private fun List<SettingsConnectedAppMarketplaceSnapshot>.settingsConnectedAppFilteredMarketplace(
    search: String,
    category: String
): List<SettingsConnectedAppMarketplaceSnapshot> =
    filter { app ->
        val categoryMatches = category == SETTINGS_CONNECTED_APPS_CATEGORY_ALL || app.category == category
        categoryMatches && app.settingsConnectedAppMatchesSearch(search)
    }

private fun SettingsConnectedAppMarketplaceSnapshot.settingsConnectedAppMatchesSearch(search: String): Boolean {
    val query = search.trim().lowercase()
    if (query.isBlank()) return true
    return listOfNotNull(name, category, developer, description, homepageDomain, callbackDomain, dataAccessSummary)
        .any { it.lowercase().contains(query) }
}

private fun String.settingsConnectedAppCategoryLabel(): String =
    trim()
        .ifBlank { "Other" }
        .replace("-", " ")
        .replace("_", " ")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

private fun settingsConnectedAppsPermissionCopy(blocker: PermissionBlocker): String =
    if (blocker.code == "server_admin_required" || blocker.requiredCapability == "manage_integrations") {
        "Only server owners and admins can manage connected apps."
    } else {
        blocker.code
    }

private fun ai.slock.compose.data.SharedSettingsActionState?.isServerConfirmation(
    action: String,
    itemId: String? = null
): Boolean =
    this?.action == SERVER_ACTION_CONFIRM &&
        this.itemId == serverConfirmationItemId(action, itemId)

private fun ai.slock.compose.data.SharedSettingsActionState?.isDiagnosticsActionLoading(action: String): Boolean =
    this?.action == action && this.inProgress

private fun <T> ScreenLoadState<T>.currentContent(): T? =
    (this as? ScreenLoadState.Content<T>)?.data

private fun Boolean.onOffLabel(): String =
    if (this) {
        "on"
    } else {
        "off"
    }

private val profilerActionKeys =
    setOf(
        DIAGNOSTICS_ACTION_PROFILER_START,
        DIAGNOSTICS_ACTION_PROFILER_STOP,
        DIAGNOSTICS_ACTION_PROFILER_RESET,
        DIAGNOSTICS_ACTION_PROFILER_REPORT,
        DIAGNOSTICS_ACTION_PROFILER_EXPORT
    )

private fun appShellAboutFallback(session: StoredAuthSession?): SharedSettingsAboutSnapshot =
    SharedSettingsAboutSnapshot(
        appName = "Raft",
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
        heroIconName = SlockLucideIconName.Bug,
        heroLabel = appName.ifBlank { "Raft" },
        detail = "Version, diagnostics, feedback",
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Application",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("appName", SlockLucideIconName.Sparkles, "Application", appName.ifBlank { "Raft" }, status = "Shared shell"),
                            KuiklySharedSettingsRow("version", SlockLucideIconName.BadgeInfo, "Version", versionLabel.ifBlank { "Unknown" }, status = buildLabel.ifBlank { "Build unknown" }),
                            KuiklySharedSettingsRow("package", SlockLucideIconName.Package, "Package", packageName.ifBlank { "Unknown" }, status = "Read only"),
                            KuiklySharedSettingsRow("device", SlockLucideIconName.Smartphone, "Device", deviceLabel.ifBlank { "Unknown" }, status = platformLabel.ifBlank { "Platform" })
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Runtime",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("signedInUser", SlockLucideIconName.User, "Signed in as", signedInUserLabel.ifBlank { "Unknown" }, status = sessionStatusLabel),
                            KuiklySharedSettingsRow("selectedServer", SlockLucideIconName.Server, "Selected server", selectedServerLabel.ifBlank { "None" }, status = socketStatusLabel),
                            KuiklySharedSettingsRow("notifications", SlockLucideIconName.Bell, "Notifications", notificationStatusLabel.ifBlank { "Unknown" }, status = "Host")
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Actions",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("diagnostics", SlockLucideIconName.ClipboardList, "Diagnostics", "Open logs, profiler, and diagnostic feedback tools.", status = "Open", enabled = true),
                            KuiklySharedSettingsRow("copyBuild", SlockLucideIconName.Copy, "Copy version/build", buildSummary(), status = "Copy", enabled = true),
                            KuiklySharedSettingsRow("openSlock", SlockLucideIconName.ExternalLink, "Open Raft", "Open app.raft.build in the system browser.", status = "Open", enabled = true),
                            KuiklySharedSettingsRow("feedback", SlockLucideIconName.MessageSquarePlus, "Send diagnostics", "Share sanitized logs with support.", status = "Send", enabled = true)
                        )
                )
            )
    )

private fun SharedSettingsAboutSnapshot.buildSummary(): String =
    buildString {
        append(appName.ifBlank { "Raft" })
        versionLabel.takeIf { it.isNotBlank() }?.let { append(" $it") }
        buildLabel.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
        packageName.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
    }

internal fun List<SharedSettingsDiagnosticsEntry>.toDiagnosticsPayload(
    state: ScreenLoadState<SharedSettingsDiagnosticsSnapshot>,
    actionState: ai.slock.compose.data.SharedSettingsActionState?
): KuiklySharedSettingsPayload {
    val logsAvailable = isNotEmpty()
    val diagnosticsExportAvailable = state is ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>
    val profiler = (state as? ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>)?.data?.profiler
        ?: SharedSettingsRecompositionProfilerSnapshot()
    val profilerRunning = profiler.statusLabel == "Running"
    val profilerBusy = actionState?.action in profilerActionKeys && actionState?.inProgress == true
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
                    iconName = SlockLucideIconName.Terminal,
                    title = entry.tag.ifBlank { "Log" },
                    detail = "${entry.timestampLabel} ${entry.message}".trim(),
                    status = ""
                )
            }
        } else {
            listOf(
                KuiklySharedSettingsRow(
                    key = "emptyLogs",
                    iconName = SlockLucideIconName.CircleDashed,
                    title = "No diagnostics yet",
                    detail = "Runtime diagnostics will appear here after app activity.",
                    status = stateLabel
                )
            )
        }
    val reportSummary =
        when {
            profiler.report.reportAvailable ->
                buildString {
                    append(profiler.report.durationLabel.ifBlank { "Ready" })
                    append(" | ${profiler.report.totalFrames} frames")
                    append(" | ${profiler.report.totalRecompositions} recompositions")
                    if (profiler.report.hotspotCount > 0) {
                        append(" | ${profiler.report.hotspotCount} hotspots")
                    }
                }
            else -> "No report exported for this session yet."
        }
    val profilerHotspotRows =
        if (profiler.report.topHotspots.isNotEmpty()) {
            profiler.report.topHotspots.mapIndexed { index, hotspot ->
                KuiklySharedSettingsRow(
                    key = "profilerHotspot-$index",
                    iconName = SlockLucideIconName.Flame,
                    title = hotspot.name,
                    detail =
                        listOfNotNull(
                            hotspot.sourceLocation,
                            "${hotspot.recompositionCount}x recompositions",
                            hotspot.avgDurationLabel.takeIf { it.isNotBlank() }
                        ).joinToString(" | "),
                    status = ""
                )
            }
        } else {
            listOf(
                KuiklySharedSettingsRow(
                    key = "profilerHotspotEmpty",
                    iconName = SlockLucideIconName.CircleDashed,
                    title = "No hotspots yet",
                    detail = "Start a focused session, reproduce the jank, then refresh or export the report.",
                    status = profiler.statusLabel
                )
            )
        }
    val profilerSections =
        if (profiler.supported) {
            listOf(
                KuiklySharedSettingsSection(
                    title = "Recomposition profiler",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow(
                                "profilerStatus",
                                SlockLucideIconName.Activity,
                                "Shared profiler status",
                                profiler.detail,
                                status = profiler.statusLabel
                            ),
                            KuiklySharedSettingsRow(
                                "startProfiler",
                                SlockLucideIconName.Play,
                                "Start capture",
                                "Apply the shared default debug profile with overlay enabled and begin collecting recompositions for the current Kuikly session.",
                                status =
                                    when {
                                        actionState.isDiagnosticsActionLoading(DIAGNOSTICS_ACTION_PROFILER_START) -> "Loading"
                                        profilerRunning -> "Running"
                                        else -> "Start"
                                    },
                                enabled = !profilerRunning && !profilerBusy
                            ),
                            KuiklySharedSettingsRow(
                                "stopProfiler",
                                SlockLucideIconName.Square,
                                "Stop capture",
                                "Stop collection. Per Kuikly docs, stop() writes profiler_report.json automatically and keeps profiler_frames.jsonl for analyzer input.",
                                status =
                                    when {
                                        actionState.isDiagnosticsActionLoading(DIAGNOSTICS_ACTION_PROFILER_STOP) -> "Loading"
                                        profilerRunning -> "Stop"
                                        else -> "Idle"
                                    },
                                enabled = profilerRunning && !profilerBusy
                            ),
                            KuiklySharedSettingsRow(
                                "resetProfiler",
                                SlockLucideIconName.RotateCcw,
                                "Reset scene window",
                                "Clear the current in-memory counters right before opening the target page or replaying the target interaction.",
                                status =
                                    when {
                                        actionState.isDiagnosticsActionLoading(DIAGNOSTICS_ACTION_PROFILER_RESET) -> "Loading"
                                        profilerRunning -> "Reset"
                                        else -> "Wait"
                                    },
                                enabled = profilerRunning && !profilerBusy
                            ),
                            KuiklySharedSettingsRow(
                                "refreshProfilerReport",
                                SlockLucideIconName.RefreshCw,
                                "Refresh report",
                                "Call getReport(saveToFile=false) to refresh the current summary without rewriting files.",
                                status =
                                    when {
                                        actionState.isDiagnosticsActionLoading(DIAGNOSTICS_ACTION_PROFILER_REPORT) -> "Loading"
                                        profiler.report.reportAvailable -> "Refresh"
                                        else -> "Pull"
                                    },
                                enabled = !profilerBusy
                            ),
                            KuiklySharedSettingsRow(
                                "exportProfilerReport",
                                SlockLucideIconName.FileCode,
                                "Export report",
                                "Call getReport(saveToFile=true) and keep profiler_frames.jsonl beside profiler_report.json for analyzer input.",
                                status =
                                    when {
                                        actionState.isDiagnosticsActionLoading(DIAGNOSTICS_ACTION_PROFILER_EXPORT) -> "Loading"
                                        profiler.report.reportAvailable -> "Export"
                                        else -> "Write"
                                    },
                                enabled = !profilerBusy
                            )
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Profiler defaults",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow(
                                "profilerDefaults",
                                SlockLucideIconName.SlidersHorizontal,
                                "Default policy",
                                "File ${profiler.defaults.enableFile.onOffLabel()}, log ${profiler.defaults.enableLog.onOffLabel()}, overlay ${profiler.defaults.enableOverlay.onOffLabel()}, sample ${profiler.defaults.sampleRateLabel}, hotspot ${profiler.defaults.hotspotThreshold}/s, buffer ${profiler.defaults.maxEventBufferSize}.",
                                status = profiler.gateLabel
                            ),
                            KuiklySharedSettingsRow(
                                "profilerOverlayGuide",
                                SlockLucideIconName.PanelBottomOpen,
                                "Overlay behavior",
                                profiler.defaults.overlayGuide,
                                status = "Doc"
                            ),
                            KuiklySharedSettingsRow(
                                "profilerFilterStrategy",
                                SlockLucideIconName.Funnel,
                                "Recommended filtering",
                                profiler.defaults.filterStrategy,
                                status = "Hint"
                            ),
                            KuiklySharedSettingsRow(
                                "profilerResetStrategy",
                                SlockLucideIconName.Route,
                                "Recommended reset timing",
                                profiler.defaults.resetStrategy,
                                status = "Hint"
                            ),
                            KuiklySharedSettingsRow(
                                "profilerExtensionNote",
                                SlockLucideIconName.Waypoints,
                                "Implementation",
                                profiler.extensionNote,
                                status = "Shared"
                            )
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Profiler report",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow(
                                "profilerReportSummary",
                                SlockLucideIconName.ChartBar,
                                "Current summary",
                                reportSummary,
                                status = profiler.report.generatedLabel.ifBlank { profiler.statusLabel }
                            ),
                            KuiklySharedSettingsRow(
                                "profilerSession",
                                SlockLucideIconName.Hash,
                                "Session and file names",
                                listOfNotNull(
                                    profiler.report.sessionId.takeIf { it.isNotBlank() }?.let { "session $it" },
                                    profiler.files.reportFileName.takeIf { it.isNotBlank() },
                                    profiler.files.framesFileName.takeIf { it.isNotBlank() }
                                ).joinToString(" | ").ifBlank { "Use Kuikly default file names after the first export or stop." },
                                status = if (profiler.report.reportAvailable) "Ready" else "Pending"
                            ),
                            KuiklySharedSettingsRow(
                                "profilerLocationHint",
                                SlockLucideIconName.FolderOpen,
                                "Expected location",
                                profiler.files.locationHint,
                                status = "Hint"
                            )
                        ) + profilerHotspotRows
                ),
                KuiklySharedSettingsSection(
                    title = "Analyzer",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow(
                                "profilerAnalyzerHint",
                                SlockLucideIconName.Sparkles,
                                "Next step",
                                profiler.analyzerHint,
                                status = "AI"
                            )
                        )
                )
            )
        } else {
            emptyList()
        }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-diagnostics",
        title = "Diagnostics",
        detail =
            profiler.actionFeedback
                ?: if (profiler.supported) {
                    "Sanitized app diagnostics and Kuikly recomposition profiler"
                } else {
                    "Sanitized app diagnostics"
                },
        heroIconName = SlockLucideIconName.ClipboardList,
        heroLabel = "Diagnostics",
        sections =
            profilerSections + listOf(
                KuiklySharedSettingsSection(
                    title = "Actions",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("copyLogs", SlockLucideIconName.Copy, "Copy logs", "Copy sanitized diagnostics to the clipboard.", status = if (diagnosticsExportAvailable) "Copy" else "Disabled", enabled = diagnosticsExportAvailable),
                            KuiklySharedSettingsRow("shareLogs", SlockLucideIconName.Share, "Share logs", "Open the native share sheet with sanitized diagnostics.", status = if (diagnosticsExportAvailable) "Share" else "Disabled", enabled = diagnosticsExportAvailable),
                            KuiklySharedSettingsRow("feedback", SlockLucideIconName.MessageSquarePlus, "Send diagnostics", "Share sanitized logs with support.", status = "Send", enabled = true),
                            KuiklySharedSettingsRow("clearLogs", SlockLucideIconName.Trash2, "Clear logs", "Clear the sanitized local diagnostics buffer.", status = if (logsAvailable) "Clear" else "Disabled", enabled = logsAvailable)
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
        SettingsDestinationConnectedApps -> "Connected Apps"
        SettingsDestinationBilling -> "Billing"
        SettingsDestinationLanguageRegion -> "Language & Region"
        SettingsDestinationAppearance -> "Appearance"
        SettingsDestinationNotifications -> "Notifications"
        SettingsDestinationMobile -> "Mobile"
        SettingsDestinationStorage -> "Storage"
        SettingsDestinationAbout -> "About"
        SettingsDestinationDiagnostics -> "Diagnostics"
        SettingsDestinationDiagnosticsFeedbackChannel -> "请选择 channel"
        else -> "Settings"
    }

private fun settingsDestinationSubtitle(destination: String): String =
    when (destination) {
        SettingsDestinationAccount -> "Profile and sign out"
        SettingsDestinationComputers -> "Connected machines"
        SettingsDestinationComputerDetail -> "Machine details"
        SettingsDestinationServer -> "Workspace controls"
        SettingsDestinationConnectedApps -> "Marketplace, installed apps, OAuth clients"
        SettingsDestinationBilling -> "Plan and usage"
        SettingsDestinationLanguageRegion -> "Language, timezone, translation"
        SettingsDestinationAppearance -> "Message font size and preview"
        SettingsDestinationNotifications -> "Push status and workspace mute"
        SettingsDestinationMobile -> "Device language and message alignment"
        SettingsDestinationStorage -> "Local cache"
        SettingsDestinationAbout -> "Diagnostics"
        SettingsDestinationDiagnostics -> "Sanitized logs and diagnostics"
        SettingsDestinationDiagnosticsFeedbackChannel -> "最近用过的 channel 放前面"
        else -> ""
    }

private fun settingsDestinationIcon(destination: String): SlockLucideIconName =
    when (destination) {
        SettingsDestinationAccount -> SlockLucideIconName.User
        SettingsDestinationComputers -> SlockLucideIconName.Monitor
        SettingsDestinationComputerDetail -> SlockLucideIconName.Monitor
        SettingsDestinationServer -> SlockLucideIconName.Users
        SettingsDestinationConnectedApps -> SlockLucideIconName.Blocks
        SettingsDestinationBilling -> SlockLucideIconName.ShieldCheck
        SettingsDestinationLanguageRegion -> SlockLucideIconName.Languages
        SettingsDestinationAppearance -> SlockLucideIconName.Type
        SettingsDestinationNotifications -> SlockLucideIconName.Bell
        SettingsDestinationMobile -> SlockLucideIconName.Smartphone
        SettingsDestinationStorage -> SlockLucideIconName.Archive
        SettingsDestinationAbout -> SlockLucideIconName.Bug
        SettingsDestinationDiagnostics -> SlockLucideIconName.Clipboard
        SettingsDestinationDiagnosticsFeedbackChannel -> SlockLucideIconName.Hash
        else -> SlockLucideIconName.Settings
    }

private fun String.toSharedSettingsDestination(): SharedSettingsDestination? =
    when (this) {
        SettingsDestinationAccount -> SharedSettingsDestination.Account
        SettingsDestinationComputers -> SharedSettingsDestination.Computers
        SettingsDestinationComputerDetail -> SharedSettingsDestination.ComputerDetail
        SettingsDestinationServer -> SharedSettingsDestination.Server
        SettingsDestinationConnectedApps -> SharedSettingsDestination.ConnectedApps
        SettingsDestinationBilling -> SharedSettingsDestination.Billing
        SettingsDestinationLanguageRegion -> SharedSettingsDestination.LanguageRegion
        SettingsDestinationAppearance -> SharedSettingsDestination.Appearance
        SettingsDestinationNotifications -> SharedSettingsDestination.Notifications
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
    message: String? = null,
    onSignedIn: (StoredAuthSession) -> Unit,
    onOpenDiagnostics: () -> Unit
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
            runCatchingPreservingCancellation { authDataSource.login(effect.email, effect.password) }
                .getOrElse { SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "Sign in could not finish setup. Try again.") }
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
        if (
            isLoginSubmitInFlight(
                submitting = state.submitting,
                pendingLogin = pendingLogin != null,
                socialSubmitting = socialSubmittingProvider != null
            )
        ) {
            return
        }
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
                errorMessage = "Create account is not available here yet.",
                onNameChange = {},
                onEmailChange = {},
                onPasswordChange = {},
                onSubmit = {},
                onBackToSignIn = { mode = "login" },
                onOpenDiagnostics = onOpenDiagnostics
            )
        "forgot" ->
            ForgotPasswordContent(
                email = "",
                sending = false,
                sent = false,
                errorMessage = "Password reset is not available here yet.",
                onEmailChange = {},
                onSubmit = {},
                onBackToSignIn = { mode = "login" },
                onOpenDiagnostics = onOpenDiagnostics
            )
        else ->
            LoginContent(
                email = state.email,
                password = state.password,
                submitting = state.submitting,
                errorMessage = state.errorMessage ?: message,
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
                onCreateAccount = { mode = "register" },
                onOpenDiagnostics = onOpenDiagnostics
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

private fun Pager.openAppShellAuthDiagnostics() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            AUTH_DIAGNOSTICS_PAGE,
            JSONObject().put("pageName", AUTH_DIAGNOSTICS_PAGE)
        )
}

@Composable
private fun AppShellServerGateContent(
    session: StoredAuthSession,
    authDataSource: SharedAuthDataSource,
    onReady: (StoredAuthSession) -> Unit,
    onSignOut: () -> Unit
) {
    var latestSession by remember(session) { mutableStateOf(session) }
    var mode by remember(session.servers) { mutableStateOf(if (session.servers.isEmpty()) "empty" else "choose") }
    var createName by remember { mutableStateOf("") }
    var createSlug by remember { mutableStateOf("") }
    var pendingServerId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        when (val result = runCatchingPreservingCancellation { authDataSource.loadServers() }.getOrNull()) {
            is SharedAuthResult.Success -> latestSession = result.data
            is SharedAuthResult.Failure -> errorMessage = result.message
            SharedAuthResult.Unauthenticated -> onSignOut()
            null -> errorMessage = "Could not load servers."
        }
    }
    LaunchedEffect(latestSession.servers.size) {
        if (mode != "create") {
            mode = if (latestSession.servers.isEmpty()) "empty" else "choose"
        }
    }

    AuthScaffold(contentTopPadding = 72.dp) {
        AuthCenterMark()
        Text(
            text =
                when (mode) {
                    "create" -> "Create server"
                    "empty" -> "Create your first server"
                    else -> "Choose server"
                },
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Text(
            text = "Signed in as ${latestSession.user.bestDisplayName}",
            style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthFormColumn {
            AuthError(errorMessage)
            if (mode == "create") {
                Text(
                    text = "Create the workspace you want to land in. You can rename it later in settings.",
                    style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
                AuthTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    label = "Server name",
                    required = true,
                    enabled = !loading
                )
                AuthTextField(
                    value = createSlug,
                    onValueChange = { createSlug = it.trim().lowercase() },
                    label = "URL slug",
                    placeholder = "my-team",
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
                    text = if (latestSession.servers.isEmpty()) "Back" else "Back to servers",
                    onClick = { mode = if (latestSession.servers.isEmpty()) "empty" else "choose" },
                    tone = AuthButtonTone.White,
                    enabled = !loading
                )
            } else if (mode == "empty") {
                AppShellServerEmptyState(
                    onCreateServer = { mode = "create" }
                )
            } else {
                latestSession.servers.forEach { server ->
                    SlockBrutalListRow(
                        title = server.name,
                        subtitle =
                            if (pendingServerId == server.id) {
                                "Opening..."
                            } else {
                                "/${server.slug.orEmpty().ifBlank { server.id }}"
                            },
                        enabled = !loading,
                        onClick = {
                            loading = true
                            errorMessage = null
                            pendingServerId = server.id
                        }
                    )
                }
                AuthActionButton(
                    text = "+ Create new server",
                    onClick = { mode = "create" },
                    tone = AuthButtonTone.Yellow,
                    enabled = !loading
                )
            }
            SlockLinkButton(
                text = "Sign out",
                enabled = !loading,
                onClick = {
                    pendingServerId = "__logout__"
                    loading = true
                }
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
                authDataSource.createServer(createName, slug = createSlug)
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
private fun AppShellServerEmptyState(
    onCreateServer: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AuthCenterMark()
        Text(
            text = "You don't have a server yet. Create one to unlock channels, agents, and activity in the app.",
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthActionButton(
            text = "Create new server",
            onClick = onCreateServer,
            tone = AuthButtonTone.Pink
        )
    }
}


@Composable
private fun AppShellAuthErrorContent(
    message: String,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    AuthScaffold(onDiagnosticsShortcut = onOpenDiagnostics) {
        AuthLogoBadge("RAFT")
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
private fun SharedMembersScreen(
    bridge: ai.slock.compose.base.BridgeModule,
    authStore: SlockAuthSessionStore,
    memberRepository: MemberRepository,
    createAgentCoordinator: SharedCreateAgentCoordinator,
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    agentDetailRefreshKey: Int,
    timeContext: AppShellTimeContext?
) {
    var state by remember { mutableStateOf<SharedMembersUiState>(SharedMembersUiState.Loading) }
    var localAgentDetailRefreshKey by remember { mutableStateOf(0) }
    var createAgentMachines by remember { mutableStateOf<List<SharedCreateAgentMachineOption>>(emptyList()) }
    var createAgentMachinesLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                    is DataResult.Content ->
                        SharedMembersUiState.Content(response.data.server, response.data.members, session.user.id)
                    is DataResult.Empty ->
                        SharedMembersUiState.Content(server, emptyList(), session.user.id)
                    is DataResult.NoPermission ->
                        SharedMembersUiState.Error(response.blocker.code)
                    is DataResult.Error ->
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
                    iconName = SlockLucideIconName.Users
                )
                SlockCenteredLoading()
        }
        is SharedMembersUiState.Error ->
            PageErrorContent(
                title = "Members",
                subtitle = "Workspace members",
                iconName = SlockLucideIconName.Users,
                message = "Could not load members",
                code = current.code
            )
        is SharedMembersUiState.Content -> {
            val membersPayload = buildHostMembersPayload(current.server, current.members, timeContext)
            val serverId = current.server.id.value
            val loadMachines: () -> Unit = {
                if (serverId.isNotBlank() && createAgentMachines.isEmpty() && !createAgentMachinesLoading) {
                    createAgentMachinesLoading = true
                    scope.launch {
                        val result = createAgentCoordinator.loadMachines(serverId)
                        createAgentMachinesLoading = false
                        if (result is DataResult.Content) {
                            createAgentMachines = result.data
                        }
                    }
                }
            }
            val createAgentSubmit: suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit> = { submit ->
                val result = createAgentCoordinator.createAgent(submit.toCreateAgentRequest())
                if (result is DataResult.Content) {
                    val profile = MemberProfile(
                        id = MemberId(result.data.id),
                        displayName = result.data.displayName?.takeIf { it.isNotBlank() } ?: result.data.name,
                        handle = result.data.name,
                        kind = MemberKind.Agent,
                        description = result.data.activityDetail,
                        roleDescription = result.data.model,
                        presence = Presence.Offline,
                        agentStatus = AgentStatus.Online
                    )
                    state =
                        when (val latest = state) {
                            is SharedMembersUiState.Content ->
                                latest.copy(members = latest.members + profile)
                            else -> latest
                        }
                }
                when (result) {
                    is DataResult.Content -> DataResult.Content(Unit)
                    is DataResult.Empty -> DataResult.Empty(result.reason)
                    is DataResult.Error -> result
                    is DataResult.NoPermission -> result
                }
            }
            MembersRouteContent(
                payload = membersPayload,
                onAddHuman = { bridge.toast("Add member is not available yet") },
                onOpenDirectMessage = { bridge.toast("Direct message is not available yet") },
                onCopyMemberId = {
                    bridge.copyToPasteboard(it.id.value)
                    bridge.toast("Copied member id")
                },
                onOpenUrl = { bridge.openUrl(it) },
                agentDetailLoader = { agentId ->
                    memberRepository.getAgentDetail(DataRequestPriority.Visible, agentId)
                },
                agentDetailRefreshKey = "$agentDetailRefreshKey:$localAgentDetailRefreshKey",
                onUpdateAgentProfile = { request ->
                    val result = memberRepository.updateAgentProfile(DataRequestPriority.Foreground, request)
                    if (result is DataResult.Content) {
                        state =
                            when (val latest = state) {
                                is SharedMembersUiState.Content ->
                                    latest.copy(
                                        members =
                                            latest.members.map { member ->
                                                if (member.id == result.data.id) result.data else member
                                            }
                                    )
                                else -> latest
                            }
                    }
                    result
                },
                onPickAgentAvatarFile = {
                    val response = bridge.threadAttachmentUpload(channelId = "agent-avatar-picker", imageOnly = true)
                    val code = response?.optInt("code", 0) ?: 0
                    if (code == 204) {
                        DataResult.Empty(EmptyReason.NoMembers)
                    } else if (code !in 200..299) {
                        val errorCode = response?.optString("errorCode").orEmpty().ifBlank { "agent_avatar_picker_failed" }
                        DataResult.Error(ScreenError(code = errorCode, retryable = true))
                    } else {
                        val file = parseSharedThreadPickedAttachmentFiles(response).firstOrNull()
                        if (file != null) DataResult.Content(file)
                        else DataResult.Error(ScreenError(code = "agent_avatar_picker_empty", retryable = true))
                    }
                },
                onUploadAgentAvatar = { agentId, file ->
                    val result = memberRepository.uploadAgentAvatar(
                        priority = DataRequestPriority.Foreground,
                        request = AgentAvatarUploadRequest(agentId = agentId, file = file)
                    )
                    if (result is DataResult.Content) {
                        state =
                            when (val latest = state) {
                                is SharedMembersUiState.Content ->
                                    latest.copy(
                                        members =
                                            latest.members.map { member ->
                                                if (member.id == result.data.id) result.data else member
                                            }
                                    )
                                else -> latest
                            }
                    }
                    result
                },
                onAgentLifecycleAction = { agentId, action ->
                    val result =
                        when (action) {
                            MembersAgentLifecycleAction.Start ->
                                memberRepository.startAgent(DataRequestPriority.Foreground, agentId)
                            MembersAgentLifecycleAction.Stop ->
                                memberRepository.stopAgent(DataRequestPriority.Foreground, agentId)
                            MembersAgentLifecycleAction.Restart,
                            MembersAgentLifecycleAction.ResetSession,
                            MembersAgentLifecycleAction.ResetFull ->
                                memberRepository.resetAgent(
                                    DataRequestPriority.Foreground,
                                    AgentResetRequest(agentId, action.resetMode ?: AgentResetMode.Restart)
                                )
                            MembersAgentLifecycleAction.Delete ->
                                memberRepository.deleteAgent(DataRequestPriority.Foreground, agentId)
                            MembersAgentLifecycleAction.UnassignMachine ->
                                memberRepository.assignAgentMachine(
                                    DataRequestPriority.Foreground,
                                    AgentMachineAssignmentRequest(agentId, machineId = null)
                                )
                        }
                    if (result is DataResult.Content && result.data.success) {
                        if (
                            action == MembersAgentLifecycleAction.ResetSession ||
                            action == MembersAgentLifecycleAction.ResetFull
                        ) {
                            memberRepository.clearAgentActivityCache(agentId)
                        }
                        state = state.withMembersLifecycleResult(action, agentId)
                        localAgentDetailRefreshKey += 1
                    }
                    result
                },
                agentWorkspaceTreeLoader = { agentId ->
                    memberRepository.getAgentWorkspaceTree(DataRequestPriority.Visible, agentId)
                },
                agentWorkspaceChildrenLoader = { agentId, dirPath ->
                    memberRepository.getAgentWorkspaceChildren(DataRequestPriority.Visible, agentId, dirPath)
                },
                agentWorkspaceFileLoader = { agentId, path ->
                    memberRepository.readAgentWorkspaceFile(DataRequestPriority.Visible, agentId, path)
                },
                onCopyAgentDiagnostics = { diagnostics ->
                    bridge.copyToPasteboard(diagnostics)
                    bridge.toast("Copied agent diagnostics")
                },
                onReportAgentIssue = { diagnostics ->
                    bridge.log("agent_diagnostics_report ${diagnostics.toSingleLineDiagnostics()}")
                    val ok = settingsCapabilities.sendFeedback()
                    bridge.toast(if (ok) "Agent issue report sent" else "Unable to report agent issue")
                    ok
                },
                canViewPrivateAgentSurfaces = { _, detail ->
                    current.server.permissions.canManageSettings ||
                        detail?.summary?.creatorId == current.currentUserId
                },
                canManageAgent = { _, detail ->
                    current.server.permissions.canManageSettings ||
                        detail?.summary?.creatorId == current.currentUserId
                },
                onUnavailableAction = { bridge.toast(it) },
                canCreateAgents = membersPayload.canCreateAgents,
                createAgentSubmit = createAgentSubmit,
                createAgentMachines = createAgentMachines,
                createAgentMachinesLoading = createAgentMachinesLoading,
                createAgentCurrentAgentCount = current.members.count { it.kind == MemberKind.Agent },
                createAgentLoadMachines = loadMachines
            )
        }
    }
}

internal fun buildHostMembersPayload(
    server: ServerSummary,
    members: List<MemberProfile>,
    timeContext: SlockTimeContext?
): KuiklyMembersPayload =
    KuiklyMembersPayload(
        instanceId = server.id.value,
        title = "Members",
        members = members,
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: SlockTimeFormat24Hour,
        // Product mapping for now: create-agent follows server admin/settings permission
        // until the API exposes a dedicated capability.
        canCreateAgents = server.permissions.canManageSettings
    )

private sealed interface SharedMembersUiState {
    data object Loading : SharedMembersUiState
    data class Error(val code: String) : SharedMembersUiState
    data class Content(
        val server: ServerSummary,
        val members: List<MemberProfile>,
        val currentUserId: String?
    ) : SharedMembersUiState
}

private fun SharedMembersUiState.withMembersLifecycleResult(
    action: MembersAgentLifecycleAction,
    agentId: MemberId
): SharedMembersUiState {
    val content = this as? SharedMembersUiState.Content ?: return this
    return when (action) {
        MembersAgentLifecycleAction.Delete ->
            content.copy(members = content.members.filterNot { it.id == agentId })
        MembersAgentLifecycleAction.Start,
        MembersAgentLifecycleAction.Restart ->
            content.copy(
                members =
                    content.members.map { member ->
                        if (member.id == agentId) {
                            member.copy(agentStatus = AgentStatus.Working, presence = Presence.Active)
                        } else {
                            member
                        }
                    }
            )
        MembersAgentLifecycleAction.Stop ->
            content.copy(
                members =
                    content.members.map { member ->
                        if (member.id == agentId) {
                            member.copy(agentStatus = AgentStatus.Offline, presence = Presence.Offline)
                        } else {
                            member
                        }
                    }
            )
        MembersAgentLifecycleAction.UnassignMachine ->
            content.copy(
                members =
                    content.members.map { member ->
                        if (member.id == agentId) {
                            member.copy(
                                permissionBadges =
                                    member.permissionBadges.filterNot {
                                        it.startsWith("computer:") || it.startsWith("computer-id:")
                                    }
                            )
                        } else {
                            member
                        }
                    }
            )
        MembersAgentLifecycleAction.ResetSession,
        MembersAgentLifecycleAction.ResetFull ->
            content
    }
}

private fun String.toSingleLineDiagnostics(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" | ")
        .take(1200)

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
        ?: (if (profile.boolean("isAgent") == true || boolean("isAgent") == true || firstObject("agent") != null) MemberKind.Agent else null)
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
        is SharedHomeContentState.Loading -> {
            val session = current.session
            if (session != null) {
                SharedHomeSessionShell(
                    session = session,
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
            } else {
                SlockCenteredLoading()
            }
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
            PageErrorContent(
                title = "Home",
                subtitle = "Workspace",
                iconName = SlockLucideIconName.House,
                message = "Could not load home",
                code = current.code
            )
        is SharedHomeContentState.Content -> {
            val snapshot = current.snapshot
            val servers =
                remember(current.session.servers, snapshot.server) {
                    current.session.servers.map { server ->
                        server.toServerSummary().let { summary ->
                            if (summary.id == snapshot.server.id) {
                                snapshot.server
                            } else {
                                summary
                            }
                        }.toSharedSwitcherItem()
                    }.ifEmpty { listOf(snapshot.server.toSharedSwitcherItem()) }
                }
            val channelItems =
                remember(snapshot.channels) {
                    snapshot.channels.map { it.toSharedHomeConversationItem() }
                }
            val directMessageItems =
                remember(snapshot.directMessages) {
                    snapshot.directMessages.map { it.toSharedHomeConversationItem() }
                }
            val notificationItems =
                remember(state.notifications) {
                    state.notifications.map { it.toHomeNotificationItem() }
                }
            val conversationsByThreadId =
                remember(snapshot.channels, snapshot.directMessages) {
                    (snapshot.channels + snapshot.directMessages).associateBy { it.threadId.value }
                }
            SlockStructuralHomeShell(
                currentServerName = snapshot.server.name,
                currentServerId = snapshot.server.id.value,
                servers = servers,
                channels = channelItems,
                directMessages = directMessageItems,
                selectedChannelId = selectedConversationId,
                serverUnreadCount = snapshot.server.unreadCount,
                inboxUnreadCount = snapshot.unreadTotal,
                serverSwitcherExpanded = serverSwitcherExpanded,
                pendingServerId = serverSwitchState.pendingServerId,
                activitySelected = state.selectedTopAction == SharedHomeTopAction.Activity,
                searchSelected = state.selectedTopAction == SharedHomeTopAction.Search,
                savedSelected = state.selectedTopAction == SharedHomeTopAction.Saved,
                notifications = notificationItems,
                pinnedChannelIds = snapshot.sidebarOrder?.pinnedChannelIds.orEmpty(),
                pinnedOrder = snapshot.sidebarOrder?.pinnedOrder.orEmpty(),
                pinnedSortMode = snapshot.sidebarOrder?.pinnedSortMode ?: "manual",
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
                },
                onChannelLongPress = { _, _ -> },
                onPinnedSortModeChange = { mode ->
                    onIntent(SharedHomeIntent.SetPinnedSortMode(mode))
                },
                onPinToggle = { conversationId ->
                    onIntent(SharedHomeIntent.TogglePinned(conversationId))
                }
            )
        }
    }
}

@Composable
private fun SharedDiagnosticsFeedbackChannelPage(
    state: SharedHomeState,
    bottomContentPadding: Dp,
    onRefresh: () -> Unit,
    onSelectChannel: (ConversationSummary) -> Unit
) {
    val channels = state.diagnosticsFeedbackChannelChoices()
    val isLoading = state.content is SharedHomeContentState.Loading
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .padding(bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "最近用过的 channel 会排在前面。选中后会把诊断日志作为附件放进输入框。",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
            SlockBrutalActionButton(
                label = "Refresh",
                backgroundColor = SlockColor.BrandYellow,
                onClick = onRefresh
            )
        }
        when {
            isLoading -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(SlockColor.SurfaceCream)
                            .border(SlockStroke.Thin, SlockColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Loading channels...",
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray
                    )
                }
            }
            channels.isEmpty() -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(SlockColor.SurfaceCream)
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "No sendable channels found.",
                        style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                        color = SlockColor.TextBlack
                    )
                    Text(
                        text = "You need a joined channel with message and attachment permission.",
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray
                    )
                }
            }
            else -> {
                SlockBrutalSectionLabel(label = "最近使用")
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    channels.forEach { channel ->
                        item(key = channel.channelId?.value ?: channel.threadId.value) {
                            DiagnosticsFeedbackChannelRow(
                                channel = channel,
                                onClick = { onSelectChannel(channel) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsFeedbackChannelRow(
    channel: ConversationSummary,
    onClick: () -> Unit
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .background(SlockColor.BrandYellow)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(SlockLucideIconName.Hash, color = "#FF000000", modifier = Modifier.size(15.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = channel.name.removePrefix("#"),
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = channel.diagnosticsFeedbackChannelMeta(),
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SlockLucideIcon(SlockLucideIconName.ChevronRight, color = "#FF111111", modifier = Modifier.size(18.dp))
        }
    }
}

private fun SharedHomeState.diagnosticsFeedbackChannelChoices(): List<ConversationSummary> {
    val snapshot = (content as? SharedHomeContentState.Content)?.snapshot ?: return emptyList()
    return snapshot.channels
        .asSequence()
        .filter { channel ->
            channel.kind == ConversationKind.Channel &&
                !channel.archived &&
                channel.joined &&
                channel.permissions.canSend &&
                channel.permissions.canAttach
        }
        .mapIndexed { index, channel -> index to channel }
        .sortedWith(
            compareByDescending<Pair<Int, ConversationSummary>> {
                parseSlockTimestampEpochMillisOrNull(it.second.lastMessageAt) ?: Long.MIN_VALUE
            }.thenBy { it.first }
        )
        .map { it.second }
        .toList()
}

private fun ConversationSummary.diagnosticsFeedbackChannelMeta(): String {
    val latest = lastMessageAt?.takeIf { it.isNotBlank() }
    val unread = unreadCount.takeIf { it > 0 }?.let { "$it unread" }
    val sender = lastMessageSenderName?.takeIf { it.isNotBlank() }
    return listOfNotNull(latest, sender, unread)
        .joinToString(" · ")
        .ifBlank { "Channel" }
}

@Composable
private fun SharedCreateChannelDialog(
    submitting: Boolean,
    membersLoading: Boolean,
    members: List<MemberProfile>,
    errorMessage: String?,
    initialName: String = "",
    initialDescription: String = "",
    initialVisibility: SharedCreateChannelVisibility = SharedCreateChannelVisibility.Public,
    initialSelectedMemberKeys: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSubmit: (String, String?, String, List<String>, List<String>) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var visibility by remember(initialVisibility) { mutableStateOf(initialVisibility) }
    var memberSearch by remember { mutableStateOf("") }
    var selectedMemberKeys by remember(initialSelectedMemberKeys) {
        mutableStateOf(initialSelectedMemberKeys)
    }
    val canSubmit = name.isNotBlank() && !submitting
    val selectableMembers = members.filter { it.kind == MemberKind.Human || it.kind == MemberKind.Agent }
    val normalizedMemberSearch = memberSearch.trim().lowercase()
    val filteredMembers =
        selectableMembers.filter { member ->
            if (normalizedMemberSearch.isBlank()) {
                true
            } else {
                val searchableText =
                    buildString {
                        append(member.displayName)
                        append(' ')
                        append(member.handle)
                        member.roleDescription?.let {
                            append(' ')
                            append(it)
                        }
                    }.lowercase()
                normalizedMemberSearch in searchableText
            }
        }
    val filteredAgentMembers = filteredMembers.filter { it.kind == MemberKind.Agent }
    val filteredHumanMembers = filteredMembers.filter { it.kind == MemberKind.Human }
    val hasFilteredMembers = filteredMembers.isNotEmpty()
    SlockUnifiedDialog(
        title = "CREATE CHANNEL",
        dismissEnabled = !submitting,
        onDismiss = onDismiss
    ) {
        SlockBrutalTextField(
            label = "Name",
            required = true,
            value = name,
            onValueChange = { name = it },
            placeholder = "e.g. ai-research",
            enabled = !submitting,
            autoFocus = true
        )
        SlockBrutalTextField(
            label = "Description",
            optional = true,
            value = description,
            onValueChange = { description = it },
            placeholder = "What is this channel about?",
            enabled = !submitting,
            maxLines = 2,
            autoFocus = false
        )
        SharedCreateChannelVisibilityControl(
            selected = visibility,
            enabled = !submitting,
            onSelected = { visibility = it }
        )
        SharedCreateChannelMemberPicker(
            loading = membersLoading,
            hasMembers = selectableMembers.isNotEmpty(),
            query = memberSearch,
            onQueryChange = { memberSearch = it },
            filteredAgentMembers = filteredAgentMembers,
            filteredHumanMembers = filteredHumanMembers,
            hasFilteredMembers = hasFilteredMembers,
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            SlockBrutalActionButton(
                label = "Cancel",
                enabled = !submitting,
                backgroundColor = SlockColor.SurfaceWhite,
                onClick = onDismiss
            )
            SlockBrutalActionButton(
                label = if (submitting) "Creating..." else "Create Channel",
                enabled = canSubmit,
                minWidth = 126.dp,
                backgroundColor = SlockColor.AccentPink,
                onClick = {
                    val selectedMembers =
                        selectableMembers.filter { it.memberSelectionKey in selectedMemberKeys }
                    onSubmit(
                        name,
                        description,
                        visibility.wireValue,
                        selectedMembers.filter { it.kind == MemberKind.Agent }.map { it.id.value },
                        selectedMembers.filter { it.kind == MemberKind.Human }.map { it.id.value }
                    )
                }
            )
        }
    }
}

private enum class SharedCreateChannelVisibility(
    val wireValue: String,
    val label: String,
    val iconName: SlockLucideIconName
) {
    Public("public", "Public", SlockLucideIconName.Hash),
    Private("private", "Private", SlockLucideIconName.Lock);

    companion object {
        fun fromWireValue(value: String?): SharedCreateChannelVisibility =
            values().firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: Public
    }
}

@Composable
private fun SharedCreateChannelVisibilityControl(
    selected: SharedCreateChannelVisibility,
    enabled: Boolean,
    onSelected: (SharedCreateChannelVisibility) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SlockBrutalSectionLabel(label = "Visibility")
        SegmentedControl(
            selectedValue = selected.name,
            options =
                SharedCreateChannelVisibility.values().map { option ->
                    SegmentedControlOption(
                        value = option.name,
                        label = option.label,
                        iconName = option.iconName
                    )
                },
            enabled = enabled,
            onValueSelected = { value ->
                SharedCreateChannelVisibility.values()
                    .firstOrNull { it.name == value }
                    ?.let(onSelected)
            }
        )
    }
}

@Composable
private fun SharedCreateChannelMemberPicker(
    loading: Boolean,
    hasMembers: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    filteredAgentMembers: List<MemberProfile>,
    filteredHumanMembers: List<MemberProfile>,
    hasFilteredMembers: Boolean,
    selectedKeys: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SlockBrutalSectionLabel(label = "Members", optional = true)
        when {
            loading ->
                Text(
                    text = "Loading members...",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            !hasMembers ->
                Text(
                    text = "No members available",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            else -> {
                SlockBrutalSearchField(
                    value = query,
                    enabled = enabled,
                    placeholder = "Search members by name",
                    onValueChange = onQueryChange
                )
                SlockBrutalPanelShell(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(192.dp)
                ) {
                    if (!hasFilteredMembers) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No matches for \"${query.trim()}\"",
                                style = SlockType.Caption,
                                color = SlockColor.TextMutedGray
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            if (filteredAgentMembers.isNotEmpty()) {
                                item(key = "agents-header") {
                                    SharedCreateChannelMemberSectionLabel("Agents")
                                }
                                filteredAgentMembers.forEach { member ->
                                    item(key = member.memberSelectionKey) {
                                        SharedCreateChannelMemberRow(
                                            member = member,
                                            selected = member.memberSelectionKey in selectedKeys,
                                            enabled = enabled,
                                            onClick = { onToggle(member.memberSelectionKey) }
                                        )
                                    }
                                }
                            }
                            if (filteredHumanMembers.isNotEmpty()) {
                                item(key = "humans-header") {
                                    SharedCreateChannelMemberSectionLabel("Humans")
                                }
                                filteredHumanMembers.forEach { member ->
                                    item(key = member.memberSelectionKey) {
                                        SharedCreateChannelMemberRow(
                                            member = member,
                                            selected = member.memberSelectionKey in selectedKeys,
                                            enabled = enabled,
                                            onClick = { onToggle(member.memberSelectionKey) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedCreateChannelMemberSectionLabel(label: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = SlockType.SectionLabel.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1
        )
    }
}

@Composable
private fun SharedCreateChannelMemberRow(
    member: MemberProfile,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val avatarWireValue = member.avatarWireValueForAvatarSlot()
    val statusColor =
        when (member.presence) {
            Presence.Active -> SlockColor.AccentLime
            Presence.Away -> SlockColor.BrandYellow
            Presence.Offline -> SlockColor.EmptyStateGray
        }
    val subtitle =
        member.roleDescription
            ?.takeIf { it.isNotBlank() }
            ?: "${member.kind.name.lowercase()} · @${member.handle}"
    val background = if (selected) SlockColor.AccentPink.copy(alpha = 0.2f) else SlockColor.SurfaceWhite
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockAvatarWithStatus(statusColor = statusColor, avatarSize = 28.dp, statusSize = 7.dp) {
            SlockAvatar(
                avatarWireValue = avatarWireValue,
                label = member.avatarLabel.ifBlank { member.displayName },
                avatarSize = 28.dp
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = member.displayName,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Medium).copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(14.dp), color = "#FF000000")
        }
    }
}

private val MemberProfile.memberSelectionKey: String
    get() = "${kind.name}:${id.value}"

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
    timeContext: AppShellThreadTimeContext? = null,
    shellScope: SlockAppShellScope = rememberSlockAppShellScope(),
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
    var sortAnchor by remember { mutableStateOf(Offset.Zero) }
    var senderSearch by remember { mutableStateOf("") }
    var channelSearch by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        onIntent(SharedHomeSearchSavedIntent.OpenSearch)
    }
    SlockBackHandler(enabled = openFilterId != null) {
        openFilterId = null
    }
    Box(
        modifier =
            modifier
                .background(SlockColor.SurfaceWhite)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SharedHomeSearchToolbar(
                query = state.query,
                loading = state.loading,
                onQueryChange = { onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged(it)) },
                onSubmit = { onIntent(SharedHomeSearchSavedIntent.SubmitSearch) },
                onClear = { onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged("")) },
                onClose = onClose,
                interactive = interactiveSearchField,
                topSafeAreaPadding = shellScope.topInset(includeTopSafeArea)
            )
            SharedHomeSearchDivider()
            SharedHomeSearchFilters(
                state = state,
                channelFilters = channelFilters,
                senderAnchorChanged = { senderAnchor = it },
                channelAnchorChanged = { channelAnchor = it },
                timeAnchorChanged = { timeAnchor = it },
                sortAnchorChanged = { sortAnchor = it },
                onOpenFilter = { openFilterId = it },
                onIntent = onIntent,
                showSenderFilter = showSenderFilter
            )
            SharedHomeSearchDivider()
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    if (state.loading || state.errorCode != null || state.noPermissionCode != null) {
                        SharedHomePanelStatus(
                            loading = state.loading,
                            loadingText = "Searching…",
                            errorCode = state.errorCode,
                            noPermissionCode = state.noPermissionCode,
                            empty = false,
                            emptyTitle = "",
                            emptyDetail = "",
                            onRetry = { onIntent(SharedHomeSearchSavedIntent.RefreshSearch) }
                        )
                    } else if (!state.searched && state.items.isEmpty() && entityResults.isEmpty()) {
                        SharedHomeSearchEmptyState(
                            title = "Search everything",
                            detail = "Search channels, DMs, people, agents, and message history."
                        )
                    } else if (state.empty || (state.searched && state.items.isEmpty() && entityResults.isEmpty())) {
                        val trimmedQuery = state.query.trim()
                        SharedHomeSearchEmptyState(
                            title = if (trimmedQuery.isNotBlank()) "No results for \"$trimmedQuery\"" else "No matching messages",
                            detail = if (trimmedQuery.isNotBlank()) "Try different keywords or a shorter phrase." else "Try a different filter set."
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
                                    timeContext = timeContext,
                                    onOpen = { onOpenItem(group.item) }
                                )
                            }
                        }
                        is HomeSearchResultGroup.Thread -> {
                            item(key = group.key) {
                                SharedHomeThreadResultGroup(
                                    group = group,
                                    timeContext = timeContext,
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
                    selectedRowColor = SlockColor.SurfaceWhite,
                    showClearWhenSelected = true,
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
                    surfaceShadowColor = SearchPopoverShadowColor,
                    selectedRowColor = SlockColor.SurfaceWhite,
                    showClearWhenSelected = true
                )
            "time" ->
                SlockFilterPopover(
                    anchor = timeAnchor,
                    title = "Time",
                    searchPlaceholder = "",
                    searchText = "",
                    onSearchChange = {},
                    options = SearchTimeRange.entries.map { SlockFilterOption(it.key, it.searchLabel) },
                    selectedIds = setOf(state.timeRange.key),
                    onToggle = { key ->
                        SearchTimeRange.entries.firstOrNull { it.key == key }?.let {
                            onIntent(SharedHomeSearchSavedIntent.SetSearchTimeRange(it))
                        }
                    },
                    onClear = {},
                    onDismiss = { openFilterId = null },
                    widthDp = 168f,
                    multiSelect = false,
                    searchable = false,
                    surfaceBorderColor = SearchPopoverBorderColor,
                    surfaceShadowColor = SearchPopoverShadowColor,
                    selectedRowColor = SlockColor.SurfaceWhite
                )
            "sort" ->
                SlockFilterPopover(
                    anchor = sortAnchor,
                    title = "Sort",
                    searchPlaceholder = "",
                    searchText = "",
                    onSearchChange = {},
                    options =
                        listOf(
                            SlockFilterOption("relevance", "Relevant"),
                            SlockFilterOption("recent", "Recent")
                        ),
                    selectedIds = setOf(state.sort.searchKey),
                    onToggle = { key ->
                        when (key) {
                            "recent" -> onIntent(SharedHomeSearchSavedIntent.SetSearchSort(SearchSort.Recent))
                            else -> onIntent(SharedHomeSearchSavedIntent.SetSearchSort(SearchSort.Relevance))
                        }
                    },
                    onClear = {},
                    onDismiss = { openFilterId = null },
                    widthDp = 148f,
                    multiSelect = false,
                    searchable = false,
                    surfaceBorderColor = SearchPopoverBorderColor,
                    surfaceShadowColor = SearchPopoverShadowColor,
                    selectedRowColor = SlockColor.SurfaceWhite
                )
            else -> Unit
        }
    }
}

@Composable
private fun SharedHomeSearchDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SlockStroke.Default)
                .background(SlockColor.BorderBlack)
    )
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
    topSafeAreaPadding: Dp = 0.dp
) {
    SharedHomeSearchControlsRow(topSafeAreaPadding = topSafeAreaPadding) {
        if (onClose != null) {
            SharedHomeSearchIconButton(
                iconName = SlockLucideIconName.ArrowLeft,
                size = 36.dp,
                iconSize = 16.dp,
                background = SlockColor.SurfaceWhite,
                onClick = onClose
            )
        }
        SharedHomeSearchIconButton(
            iconName = SlockLucideIconName.Search,
            size = 38.dp,
            iconSize = 18.dp,
            background = SlockColor.BrandYellow,
            onClick = null
        )
        SharedHomeSearchInput(
            query = query,
            loading = loading,
            interactive = interactive,
            onQueryChange = onQueryChange,
            onSubmit = onSubmit,
            onClear = onClear,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SharedHomeSearchControlsRow(
    topSafeAreaPadding: Dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(
                    start = SearchControlsHorizontalPadding,
                    top = topSafeAreaPadding + 12.dp,
                    end = SearchControlsHorizontalPadding,
                    bottom = 12.dp
                ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun SharedHomeSearchIconButton(
    iconName: SlockLucideIconName,
    size: Dp,
    iconSize: Dp,
    background: Color,
    onClick: (() -> Unit)?
) {
    SharedHomeSearchShadowSurface(
        modifier = Modifier.size(size),
        background = background,
        borderWidth = SlockStroke.Default,
        onClick = onClick
    ) {
        SlockLucideIcon(iconName, color = "#FF000000", modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun SharedHomeSearchInput(
    query: String,
    loading: Boolean,
    interactive: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (interactive) {
        SlockBrutalInputField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search channels, DMs, messages…",
            maxLines = 1,
            textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
            fieldMinHeight = 38.dp,
            horizontalPadding = 11.dp,
            verticalPadding = 5.dp,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (query.isNotBlank() && !loading) onSubmit() }),
            showClearWhenNotEmpty = true,
            onClear = onClear,
            modifier = modifier
        )
    } else {
        SharedHomeSearchShadowSurface(
            modifier = modifier.heightIn(min = 38.dp),
            background = SlockColor.SurfaceWhite,
            borderWidth = SlockStroke.Default,
            contentPaddingHorizontal = 0.dp,
            contentPaddingVertical = 0.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 11.dp, top = 7.dp, end = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = query.ifBlank { "Search channels, DMs, messages…" },
                    style = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = if (query.isBlank()) SlockColor.TextMutedGray else SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SharedHomeSavedPanel(
    state: SharedHomeSavedPanelState,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    onClose: (() -> Unit)? = null,
    onOpenItem: (InboxItem) -> Unit,
    bottomContentPadding: Dp,
    includeTopSafeArea: Boolean = true,
    timeContext: AppShellThreadTimeContext? = null,
    shellScope: SlockAppShellScope = rememberSlockAppShellScope()
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
            SharedHomeSavedHeader(
                count = state.totalCount.takeIf { it > 0 } ?: state.items.size,
                onClose = onClose,
                topSafeAreaPadding = shellScope.topInset(includeTopSafeArea)
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
            SharedHomeSavedResultCard(
                item = item,
                onUnsave = { onIntent(SharedHomeSearchSavedIntent.Unsave(item)) },
                onOpen = { onOpenItem(item) },
                timeContext = timeContext
            )
        }
        if (state.hasMore) {
            item {
                SharedHomeLoadMoreRow(
                    loading = state.loadingMore,
                    label = "Load More",
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
private fun SharedHomeSavedHeader(
    count: Int,
    onClose: (() -> Unit)?,
    topSafeAreaPadding: Dp = 0.dp
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = topSafeAreaPadding + 12.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onClose != null) {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = onClose,
                    size = 34.dp,
                    shadowOffset = 3.dp
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Saved",
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$count saved",
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 0.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        SharedHomeSearchDivider()
    }
}

@Composable
private fun SharedHomePanelHeader(
    title: String,
    subtitle: String,
    iconName: SlockLucideIconName,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    includeTopSafeArea: Boolean = true
) {
    val topPadding = if (includeTopSafeArea) slockTopSafeAreaPadding() else 0.dp
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = topPadding + 18.dp, end = 18.dp, bottom = 8.dp),
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
        SlockBrutalInputField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search messages",
            autoFocus = true,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (query.isNotBlank() && !loading) onSubmit() }),
            textStyle = SlockType.Input.copy(color = SlockColor.TextBlack),
            fieldMinHeight = 42.dp,
            showShadow = false,
            showClearWhenNotEmpty = true,
            modifier = Modifier.weight(1f)
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
    sortAnchorChanged: (Offset) -> Unit,
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
    SharedHomeSearchControlsBlock {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SearchControlsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showSenderFilter) {
                item {
                    SlockAnchoredToolbarChip(
                        label = selectedSender?.let { "From: ${if (it.isSelf) "Me" else it.displayLabel}" } ?: "From",
                        active = state.selectedSenderId != null,
                        enabled = true,
                        uppercase = selectedSender == null,
                        iconName = SlockLucideIconName.CircleUser,
                        activeBackgroundColor = SlockColor.BrandYellow,
                        borderColor = SlockColor.BorderBlack,
                        activeShadowOffset = SearchControlShadowOffset,
                        reserveActiveShadowSpace = true,
                        onClick = { anchor ->
                            senderAnchorChanged(anchor)
                            onOpenFilter("sender")
                        }
                    )
                }
                item {
                    SlockAnchoredToolbarChip(
                        label = selectedChannel?.label ?: "Channel",
                        active = state.selectedChannelId != null,
                        enabled = true,
                        uppercase = selectedChannel == null,
                        iconName = selectedChannel?.iconName ?: SlockLucideIconName.Hash,
                        activeBackgroundColor = SlockColor.BrandYellow,
                        borderColor = SlockColor.BorderBlack,
                        activeShadowOffset = SearchControlShadowOffset,
                        reserveActiveShadowSpace = true,
                        onClick = { anchor ->
                            channelAnchorChanged(anchor)
                            onOpenFilter("channel")
                        }
                    )
                }
                item {
                    SlockAnchoredToolbarChip(
                        label = state.timeRange.searchLabel,
                        active = state.timeRange != SearchTimeRange.Any,
                        enabled = true,
                        uppercase = false,
                        iconName = SlockLucideIconName.Calendar,
                        activeBackgroundColor = SlockColor.BrandYellow,
                        borderColor = SlockColor.BorderBlack,
                        activeShadowOffset = SearchControlShadowOffset,
                        reserveActiveShadowSpace = true,
                        onClick = { anchor ->
                            timeAnchorChanged(anchor)
                            onOpenFilter("time")
                        }
                    )
                }
            } else {
                item {
                    SlockAnchoredToolbarChip(
                        label = selectedChannel?.label ?: "Channel",
                        active = state.selectedChannelId != null,
                        enabled = true,
                        uppercase = selectedChannel == null,
                        iconName = selectedChannel?.iconName ?: SlockLucideIconName.Hash,
                        activeBackgroundColor = SlockColor.BrandYellow,
                        borderColor = SlockColor.BorderBlack,
                        activeShadowOffset = SearchControlShadowOffset,
                        reserveActiveShadowSpace = true,
                        onClick = { anchor ->
                            channelAnchorChanged(anchor)
                            onOpenFilter("channel")
                        }
                    )
                }
            }
            if (!showSenderFilter) {
                item {
                    SlockAnchoredToolbarChip(
                        label = state.timeRange.searchLabel,
                        active = state.timeRange != SearchTimeRange.Any,
                        enabled = true,
                        uppercase = false,
                        iconName = SlockLucideIconName.Calendar,
                        activeBackgroundColor = SlockColor.BrandYellow,
                        borderColor = SlockColor.BorderBlack,
                        activeShadowOffset = SearchControlShadowOffset,
                        reserveActiveShadowSpace = true,
                        onClick = { anchor ->
                            timeAnchorChanged(anchor)
                            onOpenFilter("time")
                        }
                    )
                }
            }
            item {
                SlockAnchoredToolbarChip(
                    label = state.sort.searchLabel,
                    active = state.sort != SearchSort.Relevance,
                    enabled = true,
                    uppercase = false,
                    iconName = SlockLucideIconName.ArrowUpDown,
                    activeBackgroundColor = SlockColor.BrandYellow,
                    borderColor = SlockColor.BorderBlack,
                    activeShadowOffset = SearchControlShadowOffset,
                    reserveActiveShadowSpace = true,
                    onClick = { anchor ->
                        sortAnchorChanged(anchor)
                        onOpenFilter("sort")
                    }
                )
            }
            if (hasActiveFilters) {
                item {
                    SlockToolbarChip(
                        label = "Clear All",
                        active = false,
                        enabled = true,
                        uppercase = false,
                        borderColor = SlockColor.BorderBlack,
                        activeShadowOffset = SearchControlShadowOffset,
                        reserveActiveShadowSpace = true,
                        onClick = { onIntent(SharedHomeSearchSavedIntent.ClearSearchFilters) }
                    )
                }
            }
            if (!showSenderFilter) {
                item {
                    Spacer(Modifier.width(0.dp))
                }
            }
        }
    }
}

@Composable
private fun SharedHomeSearchControlsBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
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
                detail = "Loading the latest results.",
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
private fun SharedHomeSavedResultCard(
    item: InboxItem,
    onUnsave: () -> Unit,
    onOpen: () -> Unit,
    timeContext: AppShellThreadTimeContext? = null
) {
    val preview = item.latestActivityPreview ?: item.lastMessagePreview ?: item.parentMessagePreview ?: "No preview"
    val sender = item.latestActivitySenderName ?: item.lastMessageSenderName
    val time = item.latestActivityAt ?: item.lastMessageAt
    val source =
        when {
            item.kind == InboxItemKind.DirectMessage -> item.channelName.takeIf { it.isNotBlank() }?.let { "@$it" }
            item.kind == InboxItemKind.Thread -> item.parentChannelName?.takeIf { it.isNotBlank() }?.let { "#$it" }
            else -> item.channelName.takeIf { it.isNotBlank() }?.let { "#$it" }
        }
    Row(
        modifier =
            Modifier
                .padding(horizontal = SearchMessagesHorizontalPadding, vertical = 4.dp)
                .fillMaxWidth()
                .heightIn(min = 122.dp)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SearchLightRowBorderColor)
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                source?.let {
                    Text(
                        text = it,
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.5f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 82.dp)
                    )
                }
                if (item.kind == InboxItemKind.Thread) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SlockLucideIcon(SlockLucideIconName.MessageSquare, color = "#66000000", modifier = Modifier.size(10.dp))
                        Text(
                            text = "thread",
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.sp),
                            color = SlockColor.TextBlack.copy(alpha = 0.4f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                sender?.let {
                    SharedHomeSavedSenderMeta(
                        senderName = it,
                        avatarWireValue = item.senderAvatarWireValueForPreview(),
                        modifier = Modifier.weight(1f)
                    )
                }
                time?.let { t ->
                    Text(
                        text = t.formatSavedTimestamp(timeContext),
                        style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.sp),
                        color = SlockColor.TextBlack.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = preview,
                style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
                color = SlockColor.TextBlack,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        SlockBrutalIconButton(
            iconName = SlockLucideIconName.Bookmark,
            onClick = onUnsave,
            size = 31.dp,
            shadowOffset = 3.dp
        )
    }
}

@Composable
private fun SharedHomeSavedSenderMeta(
    senderName: String,
    avatarWireValue: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockAvatar(
            avatarWireValue = avatarWireValue ?: savedSenderAvatarFallback(senderName),
            label = senderName,
            avatarSize = 12.dp
        )
        Text(
            text = senderName,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.sp),
            color = SlockColor.TextBlack,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun InboxItem.senderAvatarWireValueForPreview(): String? {
    val latestKind = latestActivitySenderKind
    val latestId = latestActivitySenderId
    if (latestKind != null && latestId != null) {
        return avatarWireValueForAvatarSlot(
            kind = latestKind,
            id = latestId,
            avatarWireValue = latestActivitySenderAvatarWireValue
        )
    }
    val lastKind = lastMessageSenderKind
    val lastId = lastMessageSenderId
    if (lastKind != null && lastId != null) {
        return avatarWireValueForAvatarSlot(
            kind = lastKind,
            id = lastId,
            avatarWireValue = lastMessageSenderAvatarWireValue
        )
    }
    return latestActivitySenderAvatarWireValue ?: lastMessageSenderAvatarWireValue
}

private fun savedSenderAvatarFallback(senderName: String): String? =
    if (senderName.contains("developer", ignoreCase = true) || senderName.contains("agent", ignoreCase = true)) {
        "pixel:robot"
    } else {
        null
    }

@Composable
private fun SharedHomeSearchEmptyState(
    title: String,
    detail: String
) {
    SlockEmptyState(
        title = title,
        detail = detail,
        iconName = SlockLucideIconName.Search,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(300.dp)
    )
}

@Composable
private fun SharedHomeSearchResultsCount(totalResults: Int) {
    Text(
        text = "$totalResults results",
        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.6.sp),
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
        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.8.sp),
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
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 17.sp),
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
        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 9.sp, lineHeight = 10.sp, letterSpacing = 0.sp),
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
    timeContext: AppShellThreadTimeContext? = null,
    onOpenItem: (InboxItem) -> Unit
) {
    val firstItem = group.items.firstOrNull()
    val sourceLabel = searchResultSourceLabel(firstItem ?: return)
    val latestTime = firstItem.latestActivityAt ?: firstItem.lastMessageAt
    Column(
        modifier =
            Modifier
                .padding(horizontal = SearchMessagesHorizontalPadding, vertical = 4.dp)
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SearchLightRowBorderColor)
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
                SharedHomeMiniMetaBadge("Thread", SlockLucideIconName.MessageSquare, compact = true)
                Text(
                    text = sourceLabel,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${group.items.size} hits",
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                if (!latestTime.isNullOrBlank()) {
                    Text(
                        text = latestTime.formatSavedTimestamp(timeContext),
                        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.sp),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = group.title,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        group.items.forEach { item ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(SlockStroke.Thin)
                        .background(SearchLightRowBorderColor)
            )
            SharedHomeSearchMessageResultRow(
                item = item,
                nested = true,
                timeContext = timeContext,
                onOpen = { onOpenItem(item) }
            )
        }
    }
}

@Composable
private fun SharedHomeSearchMessageResultRow(
    item: InboxItem,
    nested: Boolean = false,
    timeContext: AppShellThreadTimeContext? = null,
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
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (item.kind == InboxItemKind.Thread && !nested) {
                SharedHomeMiniMetaBadge("thread", SlockLucideIconName.MessageSquare)
            }
            if (!sender.isNullOrBlank()) {
                SharedHomeSearchSenderMeta(
                    sender = sender,
                    avatarWireValue = item.senderAvatarWireValueForPreview(),
                    modifier = Modifier.weight(1f),
                    compact = compactMeta
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = time?.formatSavedTimestamp(timeContext).orEmpty(),
                style = SlockType.Caption.copy(fontSize = if (compactMeta) 9.sp else 10.sp, lineHeight = if (compactMeta) 11.sp else 12.sp, letterSpacing = 0.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1
            )
        }
        Text(
            text = preview,
            style = SlockType.Body.copy(fontSize = if (nested) 12.sp else 14.sp, lineHeight = if (nested) 17.sp else 19.sp, letterSpacing = 0.sp),
            color = SlockColor.TextBlack.copy(alpha = if (nested) 0.7f else 0.74f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SharedHomeMiniMetaBadge(
    label: String,
    iconName: SlockLucideIconName,
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
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = if (compact) 9.sp else 10.sp, lineHeight = if (compact) 11.sp else 12.sp, letterSpacing = 0.sp),
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
                .padding(top = 4.dp, bottom = 8.dp)
                .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        SlockBrutalActionButton(
            label = if (loading) "Loading..." else label,
            enabled = !loading,
            minWidth = 0.dp,
            minHeight = 30.dp,
            shadowOffset = 3.dp,
            textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.sp),
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
        modifier = modifier.heightIn(min = 28.dp),
        background = if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite,
        borderColor = SlockColor.BorderBlack,
        shadowColor = if (selected) SlockColor.ShadowBlack else Color.Transparent
    ) {
        Text(
            text = label,
            style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.sp),
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
    SharedHomeSearchShadowSurface(
        modifier = modifier,
        background = background,
        borderColor = borderColor,
        shadowColor = shadowColor,
        enabled = enabled,
        onClick = onClick,
        contentPaddingHorizontal = 11.dp,
        contentPaddingVertical = 8.dp,
        content = content
    )
}

@Composable
private fun SharedHomeSearchShadowSurface(
    modifier: Modifier = Modifier,
    background: Color,
    borderColor: Color = SlockColor.BorderBlack,
    borderWidth: Dp = SlockStroke.Default,
    shadowColor: Color = SlockColor.ShadowBlack,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentPaddingHorizontal: Dp = 0.dp,
    contentPaddingVertical: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier =
            modifier.padding(
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
                        .background(if (enabled) shadowColor else shadowColor.copy(alpha = 0.18f))
            )
        }
        val surfaceModifier =
            Modifier
                .fillMaxSize()
                .background(background)
                .border(borderWidth, borderColor)
                .padding(horizontal = contentPaddingHorizontal, vertical = contentPaddingVertical)
        Box(
            modifier =
                if (onClick != null) {
                    surfaceModifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    surfaceModifier
                },
            contentAlignment = Alignment.Center
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
    val icon: SlockLucideIconName
)

private val SearchChannelFilterItem.iconName: SlockLucideIconName
    get() = icon

private fun homeSearchChannelFilters(state: SharedHomeState): List<SearchChannelFilterItem> {
    val snapshot = (state.content as? SharedHomeContentState.Content)?.snapshot ?: return emptyList()
    val channelFilters =
        snapshot.channels
            .mapNotNull { conversation ->
                val channelId = conversation.channelId?.value ?: return@mapNotNull null
                SearchChannelFilterItem(
                    id = channelId,
                    label = "#${conversation.name}",
                    icon = SlockLucideIconName.Hash
                )
            }
    val directMessageFilters =
        snapshot.directMessages
            .map { conversation ->
                SearchChannelFilterItem(
                    id = conversation.channelId?.value ?: conversation.threadId.value,
                    label = "@${conversation.name.removePrefix("@")}",
                    icon = if (conversation.isAgentDirectMessage()) SlockLucideIconName.Bot else SlockLucideIconName.CircleUser
                )
            }
    return (channelFilters + directMessageFilters)
        .distinctBy { it.id }
}

private fun SharedHomeState.systemNotificationConversation(message: Message): ConversationSummary? {
    val snapshot = (content as? SharedHomeContentState.Content)?.snapshot ?: return null
    val ids =
        setOfNotNull(
            message.threadId.value,
            message.channelId?.value,
            message.threadChannelId?.value
        )
    return (snapshot.channels + snapshot.directMessages).firstOrNull { conversation ->
        conversation.threadId.value in ids ||
            conversation.channelId?.value in ids ||
            conversation.thread?.streamChannelId?.value in ids ||
            conversation.thread?.parentChannelId?.value in ids
    }
}

private fun Message.isVisibleInThread(payload: KuiklyThreadScreenPayload?): Boolean {
    val sourceThreadId = payload?.sourceThreadId?.takeIf { it.isNotBlank() } ?: return false
    return sourceThreadId == threadId.value ||
        sourceThreadId == channelId?.value ||
        sourceThreadId == threadChannelId?.value
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
    val presence: SlockPresence? = null,
    val showPresence: Boolean = false
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
                icon = if (sender.kind == MemberKind.Agent) SlockLucideIconName.Bot else SlockLucideIconName.CircleUser
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
                subtitle = conversation.subtitle?.takeIf { it.isNotBlank() } ?: if (type == SearchEntityType.Channel) "Channel" else "DM",
                threadId = conversation.threadId.value,
                conversation = conversation,
                avatarWireValue = conversation.avatar?.wireValue ?: conversation.avatarUrl,
                presence = conversation.presence.toSharedPresence(),
                showPresence = conversation.isAgentDirectMessage()
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
                presence = (conversation?.presence ?: sender.agentStatus?.presence ?: sender.presence).toSharedPresence(),
                showPresence = sender.kind == MemberKind.Agent
            )
        }
    return (conversationResults + memberResults)
        .distinctBy { it.key }
        .take(5)
}

private fun ConversationSummary.isAgentDirectMessage(): Boolean =
    kind == ConversationKind.DirectMessage &&
        (directMessagePeerType.equals("agent", ignoreCase = true) || agentStatus != null)

private val SearchTimeRange.searchLabel: String
    get() =
        when (this) {
            SearchTimeRange.Any -> "Any time"
            SearchTimeRange.Today -> "Today"
            SearchTimeRange.Last7Days -> "Last 7 days"
            SearchTimeRange.Last30Days -> "Last 30 days"
        }

private val SearchSort.searchLabel: String
    get() =
        when (this) {
            SearchSort.Relevance -> "Relevant"
            SearchSort.Recent -> "Recent"
        }

private val SearchSort.searchKey: String
    get() =
        when (this) {
            SearchSort.Relevance -> "relevance"
            SearchSort.Recent -> "recent"
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
        kind = kind.name.lowercase(),
        unreadCount = unreadCount,
        lastMessageAt = lastMessageAt,
        description = subtitle?.takeIf { it.isNotBlank() },
        avatarUrl = avatarUrl,
        avatarWireValue = avatar?.wireValue ?: avatarUrl,
        gravatarUrl = gravatarUrl,
        peerType = directMessagePeerType?.takeIf { it.isNotBlank() },
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

internal typealias AppShellTimeContext = SlockTimeContext
internal typealias AppShellThreadTimeContext = SlockTimeContext

private fun String.formatSavedTimestamp(timeContext: AppShellThreadTimeContext?): String =
    timeContext?.formatTimestampLabel(this) ?: this

internal fun formatSettingsTimestampLabel(
    value: String?,
    timeContext: SlockTimeContext?
): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return timeContext?.formatTimestampLabel(trimmed)?.takeIf { it.isNotBlank() } ?: trimmed
}

private fun String.formatSettingsBillingUsageValue(timeContext: SlockTimeContext?): String =
    formatSettingsTimestampLabel(this, timeContext) ?: this

private fun AppShellTimeContext?.withTimeFormat(timeFormat: String?): AppShellTimeContext? =
    this?.withTimeFormat(timeFormat)

private fun com.tencent.kuikly.core.nvi.serialization.json.JSONObject.threadTimeContext(): AppShellThreadTimeContext? {
    val offset = optString("localOffsetMinutes").toIntOrNull() ?: return null
    val today = optString("todayEpochDay").toLongOrNull()
    val now = optString("nowEpochMillis").toLongOrNull()
    return AppShellTimeContext(offset, today, now).withTimeFormat(optString("timeFormat").takeIf { it.isNotBlank() })
}

private fun AppShellThreadTimeContext?.toSearchIsoBounds(range: SearchTimeRange): SearchIsoBounds {
    val context = this ?: return SearchIsoBounds()
    val today = context.todayEpochDay ?: return SearchIsoBounds()
    val afterDay =
        when (range) {
            SearchTimeRange.Any -> null
            SearchTimeRange.Today -> today
            SearchTimeRange.Last7Days -> today - 6L
            SearchTimeRange.Last30Days -> today - 29L
        }
    return SearchIsoBounds(
        afterIso = afterDay?.let { slockStartOfDayUtcIso(it, context.localOffsetMinutes) },
        beforeIso = context.nowEpochMillis?.let(::slockEpochMillisUtcIso)
    )
}

internal fun buildAppShellThreadPayload(
    conversation: ConversationSummary,
    timeContext: AppShellThreadTimeContext? = null,
    alignOwnMessagesRight: Boolean = false,
    anchorMessageId: String? = null
): KuiklyThreadScreenPayload {
    val surfaceKind = conversation.threadSurfaceKind()
    val streamChannelId = conversation.threadStreamChannelId()
    val parentChannelId = conversation.threadParentChannelId()
    val parentMessageId = conversation.threadParentMessageId()
    val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
    val originParentMessageId =
        if (isReplyThread) {
            parentMessageId
        } else {
            null
        }
    val canSend = conversation.joined && conversation.permissions.canSend
    val supportsChannelTasks =
        conversation.permissions.canCreateTask &&
            !conversation.channelType.equals("joint", ignoreCase = true)
    val composer =
        KuiklyThreadComposerPayload(
            surfaceKind = surfaceKind,
            enabled = canSend,
            showAsTaskToggle = supportsChannelTasks,
            mentionChannelId = parentChannelId,
            parentMessageId = parentMessageId,
            streamChannelId = streamChannelId
        )
    return KuiklyThreadScreenPayload(
        instanceId = "shared-shell-thread-${conversation.threadId.value}",
        title = SharedThreadHeaderContextStore.launchContext(surfaceKind, originParentMessageId).title,
        subtitle = null,
        status = "loading",
        surfaceKind = surfaceKind,
        sourceThreadId = streamChannelId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        memberScopedMentions = conversation.usesMemberScopedMentions(),
        originParentMessageId = originParentMessageId,
        focusedMessageId = anchorMessageId?.takeIf { it.isNotBlank() },
        selectedTab = "chat",
        navigationLabel = "Back",
        showModeTabs = !isReplyThread,
        showReplyActions = !isReplyThread,
        alignOwnMessagesRight = alignOwnMessagesRight,
        showOpenInChannelAction = isReplyThread && parentMessageId != null,
        joined = conversation.joined,
        canSend = canSend,
        membersCount = conversation.memberCount ?: 0,
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: SlockTimeFormat24Hour,
        showMembersAction = !isReplyThread && surfaceKind != KuiklyThreadSurfaceKind.DirectMessage,
        showSettingsAction = !isReplyThread && surfaceKind != KuiklyThreadSurfaceKind.DirectMessage,
        showTaskOpenActions = supportsChannelTasks,
        showTaskStatusActions = supportsChannelTasks,
        settingsActions = if (isReplyThread) emptyList() else defaultThreadSettingsChannelActionCapabilities(),
        emptyTitle = "Loading conversation",
        emptyDetail = "Loading messages from this conversation.",
        composer = composer
    ).withLaunchHeaderContext()
}

private fun KuiklyThreadScreenPayload.withDiagnosticsFeedbackDraft(
    draft: SettingsDiagnosticsFeedbackDraft,
    attachments: List<KuiklyPendingAttachmentItem>,
    syncBump: Int,
    statusLabel: String,
    composerMode: String = "idle"
): KuiklyThreadScreenPayload =
    copy(
        composer =
            composer.copy(
                seedText = draft.message,
                syncNonce = composer.syncNonce + syncBump,
                focusRequestNonce = composer.focusRequestNonce + syncBump,
                composerMode = composerMode,
                statusLabel = statusLabel,
                pendingAttachments = attachments
            )
    )

private fun SettingsDiagnosticsFeedbackDraft.toAttachmentUploadFile(): AttachmentUploadFile =
    AttachmentUploadFile(
        id = "diagnostics-${fileName.hashCode()}",
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes.takeIf { it > 0L },
        bytes = bytes
    )

private fun SettingsDiagnosticsFeedbackDraft.sizeLabel(): String =
    sizeBytes.takeIf { it > 0L }?.let { bytes ->
        when {
            bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
            bytes >= 1024L -> "${bytes / 1024L} KB"
            else -> "$bytes B"
        }
    }.orEmpty()

private suspend fun openAppShellTaskThread(
    task: TaskSummary,
    dataSource: SharedThreadDataSource,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    onOpenThreadPayload: (KuiklyThreadScreenPayload) -> Unit,
    onReplaceThreadPayload: (KuiklyThreadScreenPayload) -> Unit,
    onAuthRequired: (KuiklyThreadScreenPayload) -> Unit = {},
    onFailure: () -> Unit
) {
    val loadingPayload = buildAppShellTaskThreadLoadingPayload(task, timeContext, alignOwnMessagesRight)
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
                timeContext,
                alignOwnMessagesRight
            )
        when (val result = dataSource.createOrOpenReplyThread(channelPayload, parentMessage)) {
            is ai.slock.compose.data.DataResult.Content -> {
                onReplaceThreadPayload(result.data)
                return
            }
            is ai.slock.compose.data.DataResult.Empty,
            is ai.slock.compose.data.DataResult.Error,
            is ai.slock.compose.data.DataResult.NoPermission -> {
                if (result is ai.slock.compose.data.DataResult.NoPermission && result.blocker.isAuthSessionBlocker()) {
                    onAuthRequired(loadingPayload)
                    return
                }
                onReplaceThreadPayload(loadingPayload.copy(status = "error", errorCode = "thread_task_open_failed"))
                onFailure()
                return
            }
        }
    }

    onReplaceThreadPayload(loadingPayload.copy(status = "error", errorCode = "thread_task_open_failed"))
    onFailure()
}

internal fun buildAppShellTaskContainingThreadPayload(
    task: TaskSummary,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean = false
): KuiklyThreadScreenPayload? {
    return null
}

internal fun buildAppShellTaskThreadLoadingPayload(
    task: TaskSummary,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean = false
): KuiklyThreadScreenPayload {
    val parentMessage = task.toAppShellTaskParentMessage()
    val composer =
        KuiklyThreadComposerPayload(
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            enabled = true,
            mentionChannelId = task.channelId?.value,
            parentMessageId = parentMessage?.id,
            streamChannelId = null
        )
    return KuiklyThreadScreenPayload(
        instanceId = "shared-shell-task-thread-${task.id.value}",
        title = SharedThreadHeaderContextStore.launchContext(KuiklyThreadSurfaceKind.ReplyThread, parentMessage?.id).title,
        subtitle = null,
        status = "loading",
        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
        sourceThreadId = null,
        streamChannelId = null,
        parentChannelId = task.channelId?.value,
        parentMessageId = parentMessage?.id,
        selectedTab = "chat",
        navigationLabel = "Back",
        showModeTabs = false,
        showReplyActions = false,
        alignOwnMessagesRight = alignOwnMessagesRight,
        showOpenInChannelAction = true,
        originParentMessageId = parentMessage?.id,
        focusedMessageId = parentMessage?.id,
        originStatus = if (parentMessage != null) "loading" else "none",
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: SlockTimeFormat24Hour,
        showMembersAction = false,
        showSettingsAction = false,
        emptyTitle = "Opening task",
        emptyDetail = "Loading the task conversation.",
        composer = composer
    ).withLaunchHeaderContext()
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

private fun RealtimeEvent.performanceMarkerName(): String =
    when (this) {
        is MessageNew -> "message_new"
        is MessageUpdated -> "message_updated"
        is DirectMessageCreated -> "dm_new"
        is ThreadUpdated -> "thread_updated"
        is TaskCreated -> "task_created"
        is TaskUpdated -> "task_updated"
        is TaskDeleted -> "task_deleted"
        is MemberPresenceUpdated -> "presence_updated"
        is RealtimeRefreshRequired -> "refresh_${scope.name}"
        is ServerMembershipRemoved -> "server_membership_removed"
        is ServerDeleted -> "server_deleted"
        is ServerPlanUpdated -> "server_plan_updated"
        is AgentCreated -> "agent_created"
        is SyncResumeResponse -> "sync_resume"
        is Heartbeat -> "heartbeat"
    }

private fun decodeSharedShellRealtimeEvent(
    eventData: JSONObject,
    mapper: RealtimeEventMapper,
    log: (String) -> Unit
): RealtimeEvent? {
    // Raw path: eventName + rawPayload -> common mapper (canonical for Android & OHOS)
    val eventName = eventData.optString("eventName").takeIf { it.isNotBlank() }
    val rawPayload = eventData.optString("rawPayload").takeIf { it.isNotBlank() }
    if (eventName != null && rawPayload != null) {
        val event = mapper.map(eventName, rawPayload)
        if (event == null) {
            log("shared_realtime_raw_decode_failed eventName=$eventName")
        }
        return event
    }
    // Legacy payload path: deprecated, Android no longer sends this format
    val typedPayload = eventData.optString("payload").takeIf { it.isNotBlank() }
    if (typedPayload != null) {
        log("shared_realtime_legacy_payload_deprecated")
        return null
    }
    return null
}

private fun Long.updateFrom(event: RealtimeEvent): Long =
    RealtimeCursorPolicy.updateAppliedSeq(this, event)

@Composable
private fun AppShellRestoreSplashContent() {
    val restoreSpacing = 40.dp
    Column(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = com.tencent.kuikly.compose.ui.Alignment.CenterHorizontally
    ) {
        AppShellRestoreLogoBadge("RAFT")
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
private fun PageErrorContent(
    title: String,
    subtitle: String,
    iconName: SlockLucideIconName,
    message: String,
    code: String
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SlockTopPageHeader(title = title, subtitle = subtitle, iconName = iconName)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(SlockSpacing.Md)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(SlockSpacing.Md)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                Text(text = message, style = SlockType.ButtonBold, color = SlockColor.TextBlack)
                Text(text = code, style = SlockType.Caption, color = SlockColor.AccentRed)
            }
        }
    }
}

@Composable
private fun BottomTabBarSmokeContent() {
    val shellScope = rememberSlockAppShellScope()
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
            bottomSafeArea = shellScope.bottomSafeArea
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
    val pager = LocalActivity.current.getPager() as Pager
    val variant =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.optString("variant", "account").ifBlank { "account" }
        }
    val spec = settingsVisualSpec(variant)
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(top = 48.dp)
    ) {
        SettingsDetailShell(
            title = spec.title,
            subtitle = spec.subtitle,
            iconName = spec.iconName,
            onBack = {}
        ) {
            SettingsVisualPageBody(spec)
        }
    }
}

private data class SettingsVisualSpec(
    val variant: String,
    val title: String,
    val subtitle: String,
    val iconName: SlockLucideIconName
)

private fun settingsVisualSpec(variant: String): SettingsVisualSpec =
    when (variant) {
        "server-profile" ->
            SettingsVisualSpec(variant, "Server Profile", "Workspace profile, role, and slug", SlockLucideIconName.Building2)
        "server-danger" ->
            SettingsVisualSpec(variant, "Server Danger", "Leave or delete this workspace", SlockLucideIconName.TriangleAlert)
        "administration-admins" ->
            SettingsVisualSpec(variant, "Administration", "Owners and admins", SlockLucideIconName.Shield)
        "administration-invites" ->
            SettingsVisualSpec(variant, "Administration", "Pending human invites", SlockLucideIconName.MailPlus)
        "administration-join-links" ->
            SettingsVisualSpec(variant, "Administration", "Reusable join links", SlockLucideIconName.Link2)
        "administration-pre-join-agreement" ->
            SettingsVisualSpec(variant, "Administration", "Pre-join agreement", SlockLucideIconName.FileCheck)
        "administration-onboarding" ->
            SettingsVisualSpec(variant, "Administration", "Onboarding agent and greeting", SlockLucideIconName.Bot)
        "administration-member-permissions" ->
            SettingsVisualSpec(variant, "Administration", "Member directory permissions", SlockLucideIconName.UsersRound)
        "administration-translation" ->
            SettingsVisualSpec(variant, "Administration", "Server translation controls", SlockLucideIconName.Languages)
        "billing",
        "billing-loading",
        "billing-no-permission",
        "billing-trial",
        "billing-founder",
        "billing-downgrade" ->
            SettingsVisualSpec(variant, "Plan & Billing", "Plan and workspace usage", SlockLucideIconName.CreditCard)
        "language-region" ->
            SettingsVisualSpec(variant, "Language & Region", "Language, timezone, and translation", SlockLucideIconName.Languages)
        "appearance" ->
            SettingsVisualSpec(variant, "Appearance", "Message font size and reading density", SlockLucideIconName.Type)
        "notifications" ->
            SettingsVisualSpec(variant, "Notifications", "Push notification preferences", SlockLucideIconName.Bell)
        "connected-apps" ->
            SettingsVisualSpec(variant, "Connected Apps", "Marketplace, installed apps, and OAuth clients", SlockLucideIconName.Blocks)
        "account-provider-linked",
        "account-password-expanded",
        "account-error" ->
            SettingsVisualSpec(variant, "Account", "Profile, email, password, connected accounts", SlockLucideIconName.User)
        else ->
            SettingsVisualSpec("account", "Account", "Profile, email, password, connected accounts", SlockLucideIconName.User)
    }

@Composable
private fun SettingsVisualPageBody(spec: SettingsVisualSpec) {
    when (spec.variant) {
        "server-profile",
        "server-danger",
        "administration-admins",
        "administration-invites",
        "administration-join-links",
        "administration-pre-join-agreement",
        "administration-onboarding",
        "administration-member-permissions",
        "administration-translation" -> SettingsVisualServerFocusedContent(spec.variant)
        "billing",
        "billing-loading",
        "billing-no-permission",
        "billing-trial",
        "billing-founder",
        "billing-downgrade" -> SettingsVisualBillingContent(spec.variant)
        "language-region" -> SettingsVisualLanguageRegionContent()
        "appearance" -> SettingsVisualAppearanceContent()
        "notifications" -> SettingsVisualNotificationsContent()
        "connected-apps" -> SettingsVisualConnectedAppsContent()
        else -> SettingsVisualAccountContent(spec.variant)
    }
}

@Composable
private fun SettingsVisualAccountContent(variant: String = "account") {
    val connectedAccounts =
        when (variant) {
            "account-provider-linked" ->
                listOf(
                    KuiklySettingsConnectedAccount("password", "Email password", "artin@slock.test", "connected", canConnect = false),
                    KuiklySettingsConnectedAccount("google", "Google", "artin@slock.test", "connected", canConnect = false),
                    KuiklySettingsConnectedAccount("github", "GitHub", "Not connected", "notConnected", canConnect = true)
                )
            else ->
                listOf(
                    KuiklySettingsConnectedAccount("password", "Email password", "artin@slock.test", "connected", canConnect = false),
                    KuiklySettingsConnectedAccount("google", "Google", "Not connected", "notConnected", canConnect = true),
                    KuiklySettingsConnectedAccount("github", "GitHub", "Not connected", "notConnected", canConnect = true)
                )
        }
    SettingsAccountContent(
        payload =
            settingsAccountSmokePayload.copy(
                instanceId = "visual-settings-account-$variant",
                profile =
                    settingsAccountSmokePayload.profile.copy(
                        displayName = "Artin",
                        handle = "artin",
                        email = "artin@slock.test",
                        verified = true,
                        roleLabel = "Owner"
                    ),
                connectedAccounts = connectedAccounts,
                passwordExpanded = variant == "account-password-expanded" || variant == "account-error",
                saveErrorCode = "server_unavailable".takeIf { variant == "account-error" },
                avatarErrorCode = "upload_failed".takeIf { variant == "account-error" },
                passwordErrorCode = "invalid_current_password".takeIf { variant == "account-error" },
                providerErrorId = "github".takeIf { variant == "account-error" },
                providerErrorCode = "oauth_provider_unavailable".takeIf { variant == "account-error" }
            ),
        applyTopSafeArea = false,
        onPasswordChange = { _, _ -> },
        onEvent = { _, _ -> }
    )
}

@Composable
private fun SettingsVisualServerFocusedContent(variant: String) {
    val snapshot = remember { settingsVisualServerSnapshot() }
    val sectionsByKey = snapshot.sections.associateBy { it.key }
    var profileName by remember { mutableStateOf(snapshot.profile.name) }
    var inviteEmail by remember { mutableStateOf("new.member@slock.test") }
    var joinLinkMaxUses by remember { mutableStateOf("25") }
    var joinLinkExpiresAt by remember { mutableStateOf("2026-07-01T09:00:00Z") }
    var agreementEnabled by remember { mutableStateOf(snapshot.agreement?.enabled ?: true) }
    var agreementTitle by remember { mutableStateOf(snapshot.agreement?.title.orEmpty()) }
    var agreementBody by remember { mutableStateOf(snapshot.agreement?.bodyMarkdown.orEmpty()) }
    var onboardingAgentId by remember { mutableStateOf(snapshot.onboarding?.onboardingAgentId.orEmpty()) }
    var onboardingGreetingEnabled by remember { mutableStateOf(snapshot.onboarding?.agentAllChannelGreetingEnabled ?: true) }
    var hideHumansFromMembers by remember { mutableStateOf(snapshot.profile.hideHumansFromMembers) }
    var deleteConfirmationSlug by remember { mutableStateOf("") }
    val serverPayload =
        ScreenLoadState.Content(snapshot).toServerPayload(
            session = homeSearchVisualSession,
            refreshing = false,
            actionState = null,
            timeContext = null
        )
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SharedSettingsHero(serverPayload.copy(sections = emptyList())) }
        when (variant) {
            "server-profile" ->
                item {
                    SettingsServerProfileFormCard(
                        profile = snapshot.profile,
                        draftName = profileName,
                        fallbackName = snapshot.profile.name,
                        fallbackSlug = snapshot.profile.slug,
                        role = "owner",
                        actionState = null,
                        onNameChange = { profileName = it },
                        onSubmit = {}
                    )
                }
            "server-danger" ->
                item {
                    SettingsServerDangerZoneCard(
                        serverName = snapshot.profile.name,
                        expectedServerSlug = snapshot.profile.slug,
                        role = "owner",
                        dangerSection = sectionsByKey["danger"],
                        confirmationSlug = deleteConfirmationSlug,
                        actionState = null,
                        onConfirmationSlugChange = { deleteConfirmationSlug = it },
                        onLeave = {},
                        onDelete = {}
                    )
                }
            "administration-admins" ->
                item {
                    SettingsServerAdminsCard(
                        members = snapshot.admins,
                        section = sectionsByKey["admins"],
                        actorRole = "owner",
                        actionState = null,
                        onAction = { _, _ -> }
                    )
                }
            "administration-invites" ->
                item {
                    SettingsServerInvitesCard(
                        invites = snapshot.pendingInvites,
                        section = sectionsByKey["pending_invites"],
                        email = inviteEmail,
                        actionState = null,
                        timeContext = null,
                        onEmailChange = { inviteEmail = it },
                        onSubmit = {},
                        onAction = { _, _ -> }
                    )
                }
            "administration-join-links" ->
                item {
                    SettingsServerJoinLinksCard(
                        links = snapshot.joinLinks,
                        section = sectionsByKey["join_links"],
                        maxUses = joinLinkMaxUses,
                        expiresAt = joinLinkExpiresAt,
                        actionState = null,
                        timeContext = null,
                        onMaxUsesChange = { joinLinkMaxUses = it },
                        onExpiresAtChange = { joinLinkExpiresAt = it },
                        onCreate = {},
                        onAction = { _, _ -> }
                    )
                }
            "administration-pre-join-agreement" ->
                item {
                    SettingsServerAgreementCard(
                        agreement = snapshot.agreement,
                        section = sectionsByKey["pre_join_agreement"],
                        enabled = agreementEnabled,
                        title = agreementTitle,
                        bodyMarkdown = agreementBody,
                        actionState = null,
                        onEnabledChange = { agreementEnabled = it },
                        onTitleChange = { agreementTitle = it },
                        onBodyChange = { agreementBody = it },
                        onSubmit = {}
                    )
                }
            "administration-onboarding" ->
                item {
                    SettingsServerOnboardingCard(
                        onboarding = snapshot.onboarding,
                        section = sectionsByKey["onboarding_agent"],
                        selectedAgentId = onboardingAgentId,
                        greetingEnabled = onboardingGreetingEnabled,
                        actionState = null,
                        onAgentSelected = { onboardingAgentId = it },
                        onGreetingEnabledChange = { onboardingGreetingEnabled = it },
                        onSubmit = {}
                    )
                }
            "administration-member-permissions" ->
                item {
                    SettingsServerMemberPermissionsCard(
                        profile = snapshot.profile,
                        section = sectionsByKey["member_permissions"],
                        hideHumansFromMembers = hideHumansFromMembers,
                        actionState = null,
                        onHideHumansFromMembersChange = { hideHumansFromMembers = it },
                        onSubmit = {}
                    )
                }
            else ->
                item {
                    SharedSettingsSectionCard(
                        section =
                            KuiklySharedSettingsSection(
                                title = "Translation",
                                rows = snapshot.translation.toTranslationRows(sectionsByKey["translation"], null)
                            ),
                        onAction = { _, _ -> }
                    )
                }
        }
        item { Spacer(Modifier.height(SlockSpacing.Lg)) }
    }
}

@Composable
private fun SettingsVisualBillingContent(variant: String = "billing") {
    val state: ScreenLoadState<SettingsBillingSnapshot> =
        when (variant) {
            "billing-loading" -> ScreenLoadState.Loading
            "billing-no-permission" ->
                ScreenLoadState.NoPermission(
                    PermissionBlocker(code = "billing_owner_required", requiredCapability = "manage_billing")
                )
            else -> ScreenLoadState.Content(settingsVisualBillingSnapshot(variant))
        }
    SettingsBillingContent(
        payload = state.toBillingPayload(homeSearchVisualSession, timeContext = null),
        bottomContentPadding = SlockSpacing.Lg,
        onEvent = { _, _ -> }
    )
}

@Composable
private fun SettingsVisualLanguageRegionContent() {
    val snapshot = settingsVisualLanguageRegionSnapshot()
    SettingsLanguageRegionContent(
        state = ScreenLoadState.Content(snapshot),
        actionState = null,
        refreshing = false,
        languageDraft = snapshot.browserLanguage.selectedKey,
        displayDraft = if (snapshot.autoTranslation.enabled) "translated" else "original",
        timezoneDraft = snapshot.timezone.selectedKey,
        timeFormatDraft = snapshot.timeFormat.selectedKey,
        bottomContentPadding = SlockSpacing.Lg,
        onLanguageDraftChange = {},
        onDisplayDraftChange = {},
        onTimezoneDraftChange = {},
        onTimeFormatDraftChange = {},
        onServerTranslationChange = {},
        onSaveLanguage = {},
        onSaveTime = {},
        onRetry = {}
    )
}

@Composable
private fun SettingsVisualAppearanceContent() {
    val snapshot = settingsVisualAppearanceSnapshot()
    SettingsAppearanceContent(
        state = ScreenLoadState.Content(snapshot),
        actionState = null,
        refreshing = false,
        fontSizeDraft = snapshot.messageBodyFontSize.selectedKey,
        bottomContentPadding = SlockSpacing.Lg,
        onFontSizeDraftChange = {},
        onSave = {},
        onRetry = {}
    )
}

@Composable
private fun SettingsVisualNotificationsContent() {
    SettingsNotificationsContent(
        state = ScreenLoadState.Content(settingsVisualMobileSnapshot()),
        actionState = null,
        refreshing = false,
        bottomContentPadding = SlockSpacing.Lg,
        onNotificationPreferenceChange = {},
        onServerMuteChange = {},
        onPermission = {},
        onSettings = {},
        onTest = {},
        onRetry = {}
    )
}

@Composable
private fun SettingsVisualConnectedAppsContent() {
    SettingsConnectedAppsContent(
        state = ScreenLoadState.Content(settingsVisualConnectedAppsSnapshot()),
        selectedTab = "marketplace",
        onSelectedTabChange = {},
        editingId = "",
        name = "Slack Bridge",
        clientId = "client_slack_bridge",
        description = "Post server events to an external tool.",
        homepageUrl = "https://example.com",
        returnUrl = "https://example.com/oauth/callback",
        manifestUrl = "https://example.com/manifest.json",
        publishCategory = "productivity",
        publishDataAccess = "Messages and channel metadata",
        marketplaceSearch = "",
        marketplaceCategory = SETTINGS_CONNECTED_APPS_CATEGORY_ALL,
        selectedMarketplaceId = null,
        selectedBuiltInId = null,
        confirmTarget = null,
        actionState = null,
        bottomContentPadding = SlockSpacing.Lg,
        onNameChange = {},
        onClientIdChange = {},
        onDescriptionChange = {},
        onHomepageUrlChange = {},
        onReturnUrlChange = {},
        onManifestUrlChange = {},
        onPublishCategoryChange = {},
        onPublishDataAccessChange = {},
        onMarketplaceSearchChange = {},
        onMarketplaceCategoryChange = {},
        onOpenMarketplaceDetail = {},
        onDismissMarketplaceDetail = {},
        onOpenBuiltInDetail = {},
        onDismissBuiltInDetail = {},
        onStartEdit = {},
        onCancelEdit = {},
        onRegister = {},
        onUpdate = {},
        onUploadLogo = {},
        onClearLogo = {},
        onRequestDelete = {},
        onConfirmDelete = {},
        onPublish = {},
        onInstall = {},
        onRequestUninstall = {},
        onConfirmUninstall = {},
        onDismissConfirm = {},
        onCopySecret = {},
        onOpenUrl = {},
        onRefresh = {}
    )
}

private fun settingsVisualServerSnapshot(): SettingsServerSnapshot =
    SettingsServerSnapshot(
        profile =
            ai.slock.compose.data.SettingsServerProfileSnapshot(
                name = "Raft Design",
                slug = "raft-design",
                hideHumansFromMembers = true,
                canSave = true
            ),
        sections =
            listOf(
                SettingsServerSectionSnapshot("admins", "Owners & Admins", "Control who can manage this workspace."),
                SettingsServerSectionSnapshot("pending_invites", "Pending Invites", "Invite humans by email and revoke pending invites."),
                SettingsServerSectionSnapshot("join_links", "Join Links", "Reusable links for onboarding larger teams."),
                SettingsServerSectionSnapshot("pre_join_agreement", "Pre-join Agreement", "Require humans to accept a workspace agreement before joining."),
                SettingsServerSectionSnapshot("onboarding_agent", "Onboarding", "Choose which agent greets new humans."),
                SettingsServerSectionSnapshot("member_permissions", "Member Permissions", "Limit what member-role users can see."),
                SettingsServerSectionSnapshot("translation", "Translation", "Enable server-level message translation."),
                SettingsServerSectionSnapshot("danger", "Danger Zone", "Leave or delete this workspace.")
            ),
        admins =
            listOf(
                SettingsServerAdminSnapshot("user_artin", "Artin", "Owner · artin@slock.test", "owner"),
                SettingsServerAdminSnapshot("user_cindy", "Cindy", "Admin · onboarding", "admin"),
                SettingsServerAdminSnapshot("user_qa", "QA Tester", "Member · validation", "member")
            ),
        usageRows =
            listOf(
                SettingsPlanUsageSnapshot("Agents", "8 / 20"),
                SettingsPlanUsageSnapshot("Computers", "4 / 10")
            ),
        pendingInvites =
            listOf(
                SettingsServerInviteSnapshot("invite_1", "designer@slock.test", "Artin", "2026-06-18T09:30:00Z"),
                SettingsServerInviteSnapshot("invite_2", "qa@slock.test", "Cindy", "2026-06-19T02:15:00Z")
            ),
        joinLinks =
            listOf(
                SettingsServerJoinLinkSnapshot(
                    id = "link_1",
                    label = "Design review link",
                    url = "https://app.slock.ai/join/design",
                    maxUsesLabel = "25 max",
                    usageLabel = "8 used",
                    expiresAtLabel = "2026-07-01T09:00:00Z"
                )
            ),
        agreement =
            SettingsServerAgreementSnapshot(
                enabled = true,
                title = "Raft Design workspace agreement",
                bodyMarkdown = "Please keep feedback actionable and avoid sharing credentials in public channels.",
                statusLabel = "Required",
                detail = "New humans must accept this agreement before joining.",
                canSave = true
            ),
        onboarding =
            SettingsServerOnboardingSnapshot(
                setupModalReminderOptOut = false,
                onboardingAgentId = "agent_cindy",
                agentAllChannelGreetingEnabled = true,
                agents =
                    listOf(
                        SettingsServerOnboardingAgentSnapshot("agent_cindy", "cindy", "Cindy"),
                        SettingsServerOnboardingAgentSnapshot("agent_api", "api-integration", "API Integration")
                    ),
                statusLabel = "Cindy",
                detail = "Cindy greets new humans and explains the server.",
                canSave = true
            ),
        translation =
            SettingsServerTranslationSnapshot(
                translationEnabled = true,
                translationAvailable = true,
                canManageTranslation = true,
                statusLabel = "Enabled",
                detail = "Server messages can be translated on demand."
            )
    )

private fun settingsVisualBillingSnapshot(variant: String = "billing"): SettingsBillingSnapshot {
    val isTrial = variant == "billing-trial"
    val isFounder = variant == "billing-founder"
    val isDowngrade = variant == "billing-downgrade"
    val planName =
        when {
            isFounder -> "Founder"
            isTrial || isDowngrade -> "Hobby"
            else -> "Pro x 2"
        }
    return SettingsBillingSnapshot(
        currentPlan =
            SettingsBillingCurrentPlanSnapshot(
                planName = planName,
                usageRows =
                    when {
                        isFounder ->
                            listOf(
                                SettingsPlanUsageSnapshot("Human Seat", "12 / Unlimited"),
                                SettingsPlanUsageSnapshot("Agent Seat", "41 / Unlimited"),
                                SettingsPlanUsageSnapshot("Message History", "Unlimited")
                            )
                        isTrial || isDowngrade ->
                            listOf(
                                SettingsPlanUsageSnapshot("Computers", "4 / 2"),
                                SettingsPlanUsageSnapshot("Agents", "12 / 5"),
                                SettingsPlanUsageSnapshot("Channels", "8 / 5"),
                                SettingsPlanUsageSnapshot("History", "30 days")
                            )
                        else ->
                            listOf(
                                SettingsPlanUsageSnapshot("Human Seat", "8 / 10"),
                                SettingsPlanUsageSnapshot("Agent Seat", "18 / 20"),
                                SettingsPlanUsageSnapshot("Universal seats", "26 / 30"),
                                SettingsPlanUsageSnapshot("Message History", "Unlimited")
                            )
                    },
                trialActive = isTrial,
                description =
                    when {
                        isFounder -> "Grandfathered unlimited access."
                        isTrial || isDowngrade -> "Get started in minutes."
                        else -> "2 Pro Seat Packs purchased."
                    },
                includedFeatures =
                    if (isFounder) {
                        listOf("Everything without any limitations")
                    } else if (isTrial || isDowngrade) {
                        listOf(
                            "Channels",
                            "Tasks",
                            "Unlimited agents on your own computers",
                            "30 days of message history",
                            "100 MB file uploads/month"
                        )
                    } else {
                        listOf(
                            "Unlimited message history",
                            "Higher file upload limits",
                            "Joint Channels",
                            "More professional features coming soon"
                        )
                    },
                excludedFeatures =
                    if (isTrial || isDowngrade) {
                        listOf(
                            "Higher file upload limits",
                            "Unlimited message history",
                            "Joint Channels"
                        )
                    } else {
                        emptyList()
                    }
            ),
        notices =
            when {
                isTrial ->
                    listOf(
                        SettingsBillingNoticeSnapshot(
                            key = "trial",
                            title = "Final Trial Period",
                            detail = "Full-featured free trial is active. Paid plans begin after the trial date.",
                            tone = "trial"
                        )
                    )
                isFounder ->
                    listOf(
                        SettingsBillingNoticeSnapshot(
                            key = "founder",
                            title = "Founder Plan",
                            detail = "This workspace keeps grandfathered unlimited access.",
                            tone = "success"
                        )
                    )
                isDowngrade ->
                    listOf(
                        SettingsBillingNoticeSnapshot(
                            key = "downgrade",
                            title = "Plan Downgraded",
                            detail = "3 days remaining in grace period. Excess agents will be stopped automatically.",
                            tone = "warning",
                            rows =
                                listOf(
                                    SettingsPlanUsageSnapshot("Agents over limit", "7 (12/5)"),
                                    SettingsPlanUsageSnapshot("Computers over limit", "2 (4/2)"),
                                    SettingsPlanUsageSnapshot("Channels over limit", "3 (8/5)")
                                )
                        )
                    )
                else -> emptyList()
            },
        actions =
            listOf(
                SettingsBillingActionSnapshot(
                    key = "portal",
                    label = "Billing portal",
                    detail = if (isTrial || isDowngrade) "Paid plans are not available yet." else "Open Stripe billing portal.",
                    enabled = !(isTrial || isDowngrade || isFounder)
                ),
                SettingsBillingActionSnapshot(
                    key = "cancel",
                    label = "Cancel subscription",
                    detail = "Cancel the whole Pro subscription at period end.",
                    enabled = !(isTrial || isDowngrade || isFounder),
                    style = "danger"
                )
            ),
        plans =
            listOf(
                SettingsBillingPlanSnapshot("Hobby", "$0 /mo", listOf("2 computers", "5 agents", "5 channels", "30 days history"), current = isTrial || isDowngrade, badgeLabel = if (isTrial || isDowngrade) "Current" else null),
                SettingsBillingPlanSnapshot("Team", "$20 /mo", listOf("8 computers", "40 agents", "20 channels", "Unlimited history"), current = !isTrial && !isFounder && !isDowngrade, comingSoon = isTrial || isDowngrade, badgeLabel = if (isTrial || isDowngrade) "Coming soon" else "Current"),
                SettingsBillingPlanSnapshot("Business", "$200 /mo", listOf("40 computers", "200 agents", "Unlimited channels"), comingSoon = true, badgeLabel = "Coming soon")
            )
    )
}

private fun settingsVisualLanguageRegionSnapshot(): SettingsLanguageRegionSnapshot =
    SettingsLanguageRegionSnapshot(
        serverTranslation =
            ai.slock.compose.data.SettingsMobileToggleSnapshot(
                enabled = true,
                available = true,
                statusLabel = "Enabled",
                detail = "Allow translation on messages in this server."
            ),
        autoTranslation =
            ai.slock.compose.data.SettingsMobileToggleSnapshot(
                enabled = false,
                available = true,
                statusLabel = "Manual",
                detail = "Only translate when requested."
            ),
        browserLanguage =
            settingsVisualSelection(
                selectedKey = "zh-CN",
                selectedLabel = "Chinese (Simplified)",
                statusLabel = "Chinese",
                detail = "Stored on this account."
            ),
        timezone =
            settingsVisualSelection(
                selectedKey = "Asia/Shanghai",
                selectedLabel = "Asia/Shanghai",
                statusLabel = "GMT+8",
                detail = "Used when formatting timestamps."
            ),
        timeFormat =
            settingsVisualSelection(
                selectedKey = "24h",
                selectedLabel = "24-hour",
                statusLabel = "24h",
                detail = "Use 14:30 instead of 2:30 PM."
        )
    )

private fun settingsVisualAppearanceSnapshot(): SettingsAppearanceSnapshot =
    SettingsAppearanceSnapshot(
        messageBodyFontSize =
            settingsVisualSelection(
                selectedKey = "md",
                selectedLabel = "Medium",
                statusLabel = "Medium",
                detail = "Adjusts message body text only. Buttons, labels, sidebars, and code blocks keep fixed UI sizes.",
                options =
                    listOf(
                        "sm" to "Small",
                        "md" to "Medium",
                        "lg" to "Large"
                    )
            )
    )

private fun settingsVisualMobileSnapshot(): SettingsMobileSnapshot =
    SettingsMobileSnapshot(
        pushNotifications =
            ai.slock.compose.data.SettingsMobileToggleSnapshot(
                enabled = true,
                available = true,
                statusLabel = "On",
                detail = "Receive push notifications for mentions and assigned tasks."
            ),
        muteServer =
            ai.slock.compose.data.SettingsMobileToggleSnapshot(
                enabled = false,
                available = true,
                statusLabel = "Unmuted",
                detail = "This server can send push notifications."
            ),
        sendTestEnabled = true,
        notificationPermission =
            ai.slock.compose.data.SettingsMobileActionSnapshot(
                available = true,
                statusLabel = "Allowed",
                detail = "System notification permission is granted."
            ),
        notificationSettings =
            ai.slock.compose.data.SettingsMobileActionSnapshot(
                available = true,
                statusLabel = "Open",
                detail = "Open OS notification settings."
            ),
        testNotification =
            ai.slock.compose.data.SettingsMobileActionSnapshot(
                available = true,
                statusLabel = "Ready",
                detail = "Send a test push notification."
            )
    )

private fun settingsVisualConnectedAppsSnapshot(): SettingsConnectedAppsSnapshot =
    SettingsConnectedAppsSnapshot(
        marketplace =
            listOf(
                SettingsConnectedAppMarketplaceSnapshot(
                    id = "market_slack",
                    clientId = "client_slack_bridge",
                    name = "Slack Bridge",
                    category = "Productivity",
                    developer = "Raft Labs",
                    description = "Mirror Slock activity into Slack channels.",
                    homepageUrl = "https://example.com/slack",
                    homepageDomain = "example.com",
                    installed = false,
                    dataAccessSummary = "Messages and channel metadata"
                ),
                SettingsConnectedAppMarketplaceSnapshot(
                    id = "market_github",
                    clientId = "client_github_issues",
                    name = "GitHub Issues",
                    category = "Development",
                    developer = "Raft Labs",
                    description = "Create issues from tasks and feedback.",
                    installed = true,
                    dataAccessSummary = "Tasks and feedback"
                )
            ),
        builtIn =
            listOf(
                SettingsConnectedAppBuiltInSnapshot(
                    id = "builtin_github",
                    clientId = "github",
                    name = "GitHub",
                    description = "Built-in source control integration.",
                    homepageUrl = "https://github.com",
                    homepageDomain = "github.com"
                )
            ),
        installed =
            listOf(
                SettingsConnectedAppInstalledSnapshot(
                    id = "install_github",
                    clientId = "github",
                    name = "GitHub Issues",
                    developer = "Raft Labs",
                    description = "Installed for Raft Design.",
                    homepageDomain = "github.com",
                    callbackDomain = "github.com",
                    origin = "marketplace",
                    editable = false
                )
            ),
        registered =
            listOf(
                SettingsConnectedAppClientSnapshot(
                    id = "client_1",
                    serverId = "srv_design",
                    clientId = "client_slack_bridge",
                    appType = "oauth",
                    publishStatus = "draft",
                    category = "productivity",
                    dataAccessSummary = "Messages and channel metadata",
                    name = "Slack Bridge",
                    description = "Server-local OAuth client.",
                    homepageUrl = "https://example.com",
                    homepageDomain = "example.com",
                    returnUrl = "https://example.com/oauth/callback",
                    callbackDomain = "example.com",
                    agentManifestUrl = "https://example.com/manifest.json"
                )
            ),
        oneTimeClientSecret = SettingsConnectedAppSecretSnapshot("client_slack_bridge", "client_secret_visible_once")
    )

private fun settingsVisualSelection(
    selectedKey: String,
    selectedLabel: String,
    statusLabel: String,
    detail: String,
    options: List<Pair<String, String>>? = null
): SettingsMobileSelectionSnapshot =
    SettingsMobileSelectionSnapshot(
        selectedKey = selectedKey,
        selectedLabel = selectedLabel,
        statusLabel = statusLabel,
        detail = detail,
        options =
            options?.map { SettingsMobileSelectionOptionSnapshot(it.first, it.second) }
                ?: listOf(
                    SettingsMobileSelectionOptionSnapshot(selectedKey, selectedLabel),
                    SettingsMobileSelectionOptionSnapshot("system", "System default")
                )
    )

private val homeSearchVisualServer = ServerSummary(
    id = ServerId("srv_design"),
    name = "Raft Design",
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
                name = "Raft Design",
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
        sort = SearchSort.Recent,
        timeRange = SearchTimeRange.Last7Days,
    myMessagesOnly = false,
    selectedSenderId = "user_artin",
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
    selectedChannelId = "ch_design",
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

private val homeSavedVisualState = SharedHomeSavedPanelState(
    items =
        listOf(
            InboxItem(
                key = "saved-channel-report",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("ch_android"),
                channelName = "android-artifacts",
                lastMessageId = MessageId("msg_saved_report"),
                lastMessagePreview = "Uploaded Android visual report with Search and Saved cases. The latest run still needs Product UX review before we mark it done.",
                lastMessageAt = "13:58",
                lastMessageSenderName = "Android Developer",
                latestActivityMessageId = MessageId("msg_saved_report"),
                latestActivityPreview = "Uploaded Android visual report with Search and Saved cases. The latest run still needs Product UX review before we mark it done.",
                latestActivityAt = "2 hours ago",
                latestActivitySenderName = "Android Developer"
            ),
            InboxItem(
                key = "saved-thread-reply",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread_msg_agent_reply"),
                channelName = "thread",
                lastMessageId = MessageId("msg_saved_thread_reply"),
                lastMessagePreview = "Search visual parity should keep the real React SavedPanel as the source, not a hand-built preview shell.",
                lastMessageAt = "14:31",
                lastMessageSenderName = "artin",
                threadChannelId = ChannelId("thread_msg_agent_reply"),
                parentChannelId = ChannelId("ch_design"),
                parentChannelName = "design",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("msg_agent_reply"),
                parentMessagePreview = "收到，统一 MessageItem / ThreadMessageItem 的正文、回复数和任务状态。",
                latestActivityMessageId = MessageId("msg_saved_thread_reply"),
                latestActivityPreview = "Search visual parity should keep the real React SavedPanel as the source, not a hand-built preview shell.",
                latestActivityAt = "1 hour ago",
                latestActivitySenderName = "artin",
                replyCount = 5,
                participantIds = listOf(MemberId("user_artin"), MemberId("agent_android"))
            ),
            InboxItem(
                key = "saved-dm-checklist",
                kind = InboxItemKind.DirectMessage,
                channelId = ChannelId("dm_android"),
                channelName = "Android Developer",
                lastMessageId = MessageId("msg_saved_dm"),
                lastMessagePreview = "Please keep this saved checklist around for the next connected visual-testing run.",
                lastMessageAt = "14:44",
                lastMessageSenderName = "Android Developer",
                latestActivityMessageId = MessageId("msg_saved_dm"),
                latestActivityPreview = "Please keep this saved checklist around for the next connected visual-testing run.",
                latestActivityAt = "1 hour ago",
                latestActivitySenderName = "Android Developer"
            )
        ),
    totalCount = 3,
    loaded = true,
    hasMore = true,
    nextOffset = 3
)

private val homeActivityVisualSnapshot = InboxSnapshot(
    server = homeSearchVisualServer,
    items =
        listOf(
            InboxItem(
                key = "activity-thread-visual-parity",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread_msg_agent_reply"),
                channelName = "thread",
                unreadCount = 2,
                hasMention = true,
                firstUnreadMessageId = MessageId("msg_activity_reply_1"),
                lastMessageId = MessageId("msg_agent_reply"),
                lastMessagePreview = "收到，统一 MessageItem / ThreadMessageItem 的正文、回复数和任务状态。",
                lastMessageAt = "30 minutes ago",
                lastMessageSenderName = "Android Developer",
                threadChannelId = ChannelId("thread_msg_agent_reply"),
                parentChannelId = ChannelId("ch_design"),
                parentChannelName = "design",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("msg_agent_reply"),
                parentMessagePreview = "收到，统一 MessageItem / ThreadMessageItem 的正文、回复数和任务状态。",
                latestActivityMessageId = MessageId("msg_activity_reply_1"),
                latestActivityPreview = "Android Activity needs to reuse the real ThreadsInbox row primitives.",
                latestActivityAt = "30 minutes ago",
                latestActivitySenderName = "Artin",
                replyCount = 5,
                participantIds = listOf(MemberId("user_artin"), MemberId("agent_android")),
                taskNumber = 177,
                taskStatus = TaskStatus.InProgress,
                taskClaimedByName = "Product-UX-Designer"
            ),
            InboxItem(
                key = "activity-channel-artifacts",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("ch_android"),
                channelName = "android-artifacts",
                lastMessageId = MessageId("msg_activity_channel_1"),
                lastMessagePreview = "Uploaded Android visual report with Search and Saved cases.",
                lastMessageAt = "1 hour ago",
                lastMessageSenderName = "Android Developer",
                latestActivityMessageId = MessageId("msg_activity_channel_1"),
                latestActivityPreview = "Uploaded Android visual report with Search and Saved cases.",
                latestActivityAt = "1 hour ago",
                latestActivitySenderName = "Android Developer"
            ),
            InboxItem(
                key = "activity-dm-android",
                kind = InboxItemKind.DirectMessage,
                channelId = ChannelId("dm_android"),
                channelName = "Android Developer",
                unreadCount = 1,
                firstUnreadMessageId = MessageId("msg_activity_dm_1"),
                lastMessageId = MessageId("msg_activity_dm_1"),
                lastMessagePreview = "Please keep this saved checklist around for the next connected visual-testing run.",
                lastMessageAt = "1 hour ago",
                lastMessageSenderName = "Android Developer",
                latestActivityMessageId = MessageId("msg_activity_dm_1"),
                latestActivityPreview = "Please keep this saved checklist around for the next connected visual-testing run.",
                latestActivityAt = "1 hour ago",
                latestActivitySenderName = "Android Developer"
            )
        ),
    totalCount = 3,
    totalUnreadCount = 3,
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
            showSenderFilter = true,
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
                selectedIds = setOf("ch_design"),
                onToggle = {},
                onClear = {},
                onDismiss = {},
                widthDp = 220f,
                multiSelect = false,
                surfaceBorderColor = SearchPopoverBorderColor,
                surfaceShadowColor = SearchPopoverShadowColor,
                selectedRowColor = SlockColor.SurfaceWhite,
                showClearWhenSelected = true
            )
        }
    }
}

@Composable
private fun HomeSavedVisualContent() {
    Box(modifier = Modifier.width(342.dp).height(620.dp)) {
        SharedHomeSavedPanel(
            state = homeSavedVisualState,
            onIntent = {},
            onClose = {},
            onOpenItem = {},
            bottomContentPadding = 12.dp,
            includeTopSafeArea = false
        )
    }
}

@Composable
private fun HomeActivityVisualContent() {
    Box(modifier = Modifier.width(342.dp).height(620.dp)) {
        SharedHomeActivityPanel(
            snapshot = homeActivityVisualSnapshot,
            onOpenMessage = {},
            onClose = {},
            modifier = Modifier.fillMaxSize()
        )
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
            initialVisibility = SharedCreateChannelVisibility.Public,
            initialSelectedMemberKeys = setOf(
                homeCreateChannelVisualMembers[0].memberSelectionKey,
                homeCreateChannelVisualMembers[2].memberSelectionKey
            ),
            onDismiss = {},
            onSubmit = { _, _, _, _, _ -> }
        )
    }
}

@Composable
private fun SettingsHomeScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload = rememberSettingsHomePayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.toString(),
        pager.pageData.params.optString("instanceId")
    )
    LaunchedEffect(payload.instanceId, payload.items.size) {
        bridge.log("settings_home_render instanceId=${payload.instanceId} items=${payload.items.size}")
    }
    SettingsHomeContent(payload) { key ->
        bridge.settingsEvent(payload.instanceId, "openSettingsDestination", key)
    }
}

@Composable
private fun rememberSettingsHomePayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsHomePayload =
    androidx.compose.runtime.remember(payload, fallbackPageData, instanceId) {
        parseSettingsHomePayload(
            payload = payload,
            fallbackPageData = fallbackPageData,
            instanceId = instanceId
        )
    }

@Composable
internal fun SettingsHomeContent(
    payload: KuiklySettingsHomePayload,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = payload.title,
            subtitle = payload.subtitle.takeIf { it.isNotBlank() } ?: "Preferences",
            iconName = SlockLucideIconName.Settings
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
            item {
                Spacer(Modifier.height(bottomContentPadding))
            }
        }
    }
}
