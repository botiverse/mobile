package build.raft.app

import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalCard
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalInputField
import build.raft.app.components.SlockBrutalListRow
import build.raft.app.components.SlockAnchoredToolbarChip
import build.raft.app.components.SlockBrutalPanelShell
import build.raft.app.components.SlockBrutalReadOnlyField
import build.raft.app.components.SlockBrutalSearchField
import build.raft.app.components.SlockBrutalSectionLabel
import build.raft.app.components.SlockBrutalSelectField
import build.raft.app.components.SlockBrutalSelectOption
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.components.SlockRaftUiSelectField
import build.raft.app.components.SlockRaftUiSelectionOption
import build.raft.app.components.SharedHomeSearchInput
import build.raft.app.components.SlockLinkButton
import build.raft.app.components.SlockSwitchButton
import build.raft.app.components.SlockToolbarChip
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.data.NetworkSlockHomeApi
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.ComposeLocalWriteThrough
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.KeyValueRecompositionProfilerPreferenceStore
import build.raft.app.data.KuiklyHostNetworkEvents
import build.raft.app.data.KuiklyAppUpdateEvents
import build.raft.app.data.KuiklyNotificationPreferenceEvents
import build.raft.app.data.KuiklyRealtimeEvents
import build.raft.app.data.isAppUpdateHomeNotificationId
import build.raft.app.data.MOBILE_ACTION_LANGUAGE
import build.raft.app.data.MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES
import build.raft.app.data.MOBILE_ACTION_COMPACT_MESSAGES
import build.raft.app.data.MOBILE_ACTION_IMAGE_ANNOTATION
import build.raft.app.data.MOBILE_ACTION_MESSAGE_ALIGNMENT
import build.raft.app.data.MOBILE_ACTION_THREAD_ROW_AFFORDANCES
import build.raft.app.data.MOBILE_ACTION_FOREGROUND_KEEP_ALIVE
import build.raft.app.data.MOBILE_ACTION_NOTIFICATION_PREFERENCE
import build.raft.app.data.MOBILE_ACTION_PERMISSION
import build.raft.app.data.MOBILE_ACTION_SERVER_MUTE
import build.raft.app.data.MOBILE_ACTION_SETTINGS
import build.raft.app.data.MOBILE_ACTION_TEST_NOTIFICATION
import build.raft.app.data.APPEARANCE_ACTION_MESSAGE_FONT_SIZE
import build.raft.app.data.AppLocale
import build.raft.app.data.DEVELOPER_ACTION_API_ENVIRONMENT
import build.raft.app.data.DEVELOPER_ACTION_QUIVER_UPDATE_CHECK
import build.raft.app.data.DEVELOPER_ACTION_SHARED_SOCKET_IO_TRANSPORT
import build.raft.app.data.LANGUAGE_REGION_ACTION_AUTO_TRANSLATION
import build.raft.app.data.LANGUAGE_REGION_ACTION_LANGUAGE
import build.raft.app.data.LANGUAGE_REGION_ACTION_SERVER_TRANSLATION
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIME_FORMAT
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIMEZONE
import build.raft.app.data.LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY
import build.raft.app.data.MyMessageAlignmentPreference
import build.raft.app.data.ThreadRowAffordancePreference
import build.raft.app.data.CONNECTED_APPS_ACTION_DELETE
import build.raft.app.data.CONNECTED_APPS_ACTION_INSTALL
import build.raft.app.data.CONNECTED_APPS_ACTION_LOGO_CLEAR
import build.raft.app.data.CONNECTED_APPS_ACTION_LOGO_UPLOAD
import build.raft.app.data.CONNECTED_APPS_ACTION_PUBLISH
import build.raft.app.data.CONNECTED_APPS_ACTION_REGISTER
import build.raft.app.data.CONNECTED_APPS_ACTION_UNINSTALL
import build.raft.app.data.CONNECTED_APPS_ACTION_UPDATE
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.NetworkSlockAnnouncementsRemindersApi
import build.raft.app.data.NetworkSlockTasksApi
import build.raft.app.data.SERVER_ACTION_INVITE_CREATE
import build.raft.app.data.SERVER_ACTION_INVITE_REVOKE
import build.raft.app.data.SERVER_ACTION_JOIN_LINK_CREATE
import build.raft.app.data.SERVER_ACTION_JOIN_LINK_REVOKE
import build.raft.app.data.SERVER_ACTION_CONFIRM
import build.raft.app.data.SERVER_ACTION_DELETE
import build.raft.app.data.SERVER_ACTION_LEAVE
import build.raft.app.data.SERVER_ACTION_AGREEMENT_SAVE
import build.raft.app.data.SERVER_ACTION_MEMBER_PERMISSIONS_SAVE
import build.raft.app.data.SERVER_ACTION_MEMBER_REMOVE
import build.raft.app.data.SERVER_ACTION_MEMBER_ROLE
import build.raft.app.data.SERVER_ACTION_ONBOARDING_SAVE
import build.raft.app.data.SERVER_ACTION_ONBOARDING_TOGGLE
import build.raft.app.data.SERVER_ACTION_PROFILE_SAVE
import build.raft.app.data.SERVER_ACTION_SYSTEM_CHANNEL_HIDE
import build.raft.app.data.SERVER_ACTION_SIDEBAR_ORDER
import build.raft.app.data.SERVER_ACTION_TRANSLATION_TOGGLE
import build.raft.app.data.NotificationPreferenceSnapshot
import build.raft.app.data.PersistentChatLayoutPreferenceRepository
import build.raft.app.data.PersistentLocaleRepository
import build.raft.app.data.PersistentRecompositionProfilerPreferenceRepository
import build.raft.app.data.AuthApiResult
import build.raft.app.data.AuthLoginResponse
import build.raft.app.data.AuthMobileOAuthStartResponse
import build.raft.app.data.AuthRefreshResponse
import build.raft.app.data.AuthSocialCompleteResponse
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthErrorCode
import build.raft.app.data.SharedAuthGateIntent
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.SharedAuthGateViewModel
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.SharedTasksViewModel
import build.raft.app.data.hasCompletedTasksSnapshot
import build.raft.app.data.realtime.RealtimeCursorPolicy
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeDataSource
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.HomeConversationRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeTopAction
import build.raft.app.data.SharedHomeViewModel
import build.raft.app.data.toConversationSummary
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.data.SharedHomeNotification
import build.raft.app.data.SharedHomeNotificationIcon
import build.raft.app.data.SharedHomeNotificationKind
import build.raft.app.data.SharedHomeShellDestinationActivity
import build.raft.app.data.SharedHomeShellDestinationSaved
import build.raft.app.data.SharedHomeShellDestinationSearch
import build.raft.app.data.SharedHomeShellIntent
import build.raft.app.data.SharedHomeShellStore
import build.raft.app.data.RealtimeDomainEventSinks
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsApiEnvironmentOption
import build.raft.app.data.SharedSettingsApiEnvironmentRequest
import build.raft.app.data.SharedSettingsDataSource
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.data.SharedSettingsDiagnosticsEntry
import build.raft.app.data.SharedSettingsDiagnosticsSnapshot
import build.raft.app.data.SharedSettingsEffect
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsRecompositionProfilerSnapshot
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SharedSettingsStorageRow
import build.raft.app.data.SharedSettingsStorageSnapshot
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.SlockAuthApi
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.toSharedHomeAppUpdateNotification
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_EXPORT
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_ENABLED
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_REPORT
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_RESET
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_START
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_STOP
import build.raft.app.data.isAuthSessionError
import build.raft.app.data.KuiklyAuthSessionEvents
import build.raft.app.data.isRefreshing
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.serverConfirmationItemId
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.authSessionClearFingerprint
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.parseSlockHome
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.MemberRepository
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealInboxRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealReadReceiptRepository
import build.raft.app.data.RealSavedRepository
import build.raft.app.data.RealSearchRepository
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.SettingsRepository
import build.raft.app.data.SettingsServerManagementRepository
import build.raft.app.data.SharedMembersDataSource
import build.raft.app.data.SearchIsoBounds
import build.raft.app.data.SearchSort
import build.raft.app.data.SearchSenderFilterItem
import build.raft.app.data.SearchTimeRange
import build.raft.app.data.SlockAgentsApiClient
import build.raft.app.data.SlockAgentsNetworkApi
import build.raft.app.data.SlockComputersApiClient
import build.raft.app.data.SlockComputersNetworkApi
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.SharedCreateAgentCoordinator
import build.raft.app.data.SharedActivityIntent
import build.raft.app.data.SharedAgentDetailActions
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.SharedAgentPanelTabOrderStore
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.resolveHomeConversationSummary
import build.raft.app.data.SharedHomeSavedPanelState
import build.raft.app.data.SharedHomeSavedStateCache
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SharedHomeSearchSavedIntent
import build.raft.app.data.SharedHomeSearchSavedViewModel
import build.raft.app.data.AccountProfileUpdateRequest
import build.raft.app.data.AccountPasswordChangeRequest
import build.raft.app.data.AccountProviderLinkStartRequest
import build.raft.app.data.AccountSettingsSnapshot
import build.raft.app.data.ACCOUNT_ACTION_AVATAR_UPLOAD
import build.raft.app.data.ACCOUNT_ACTION_PASSWORD_CHANGE
import build.raft.app.data.ACCOUNT_ACTION_PROFILE_SAVE
import build.raft.app.data.ACCOUNT_ACTION_PROVIDER_CONNECT
import build.raft.app.data.AgentAvatarUploadRequest
import build.raft.app.data.AgentMachineAssignmentRequest
import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.AgentResetMode
import build.raft.app.data.AgentResetRequest
import build.raft.app.data.PlatformFile
import build.raft.app.data.AttachmentUploadRequest
import build.raft.app.data.EmptyReason
import build.raft.app.data.parseSharedThreadPickedPlatformFiles
import build.raft.app.data.ScreenError
import build.raft.app.data.ConnectedAccountStatus
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.kuiklyNotificationPreferenceRepository
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.data.savedMessageIdOrNull
import build.raft.app.data.SettingsAppearanceSnapshot
import build.raft.app.data.SettingsAppearanceUpdateRequest
import build.raft.app.data.SettingsBillingActionSnapshot
import build.raft.app.data.SettingsBillingCurrentPlanSnapshot
import build.raft.app.data.SettingsBillingPlanSnapshot
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SettingsConnectedAppBuiltInSnapshot
import build.raft.app.data.SettingsConnectedAppClientSnapshot
import build.raft.app.data.SettingsConnectedAppInstalledSnapshot
import build.raft.app.data.SettingsConnectedAppMarketplaceSnapshot
import build.raft.app.data.SettingsConnectedAppPublishRequest
import build.raft.app.data.SettingsConnectedAppRegisterRequest
import build.raft.app.data.SettingsConnectedAppSecretSnapshot
import build.raft.app.data.SettingsConnectedAppUpdateRequest
import build.raft.app.data.SettingsConnectedAppsSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.SettingsDiagnosticsFeedbackDraft
import build.raft.app.data.SettingsMobileSelectionSnapshot
import build.raft.app.data.SettingsMobileSelectionOptionSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsPlanUsageSnapshot
import build.raft.app.data.SettingsLanguageRegionPreferenceUpdateRequest
import build.raft.app.data.SettingsLanguageRegionSnapshot
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.SettingsServerAdminSnapshot
import build.raft.app.data.SettingsServerAgreementSnapshot
import build.raft.app.data.SettingsServerAgreementUpdateRequest
import build.raft.app.data.SettingsServerDeleteRequest
import build.raft.app.data.SettingsServerInviteCreateRequest
import build.raft.app.data.SettingsServerInviteSnapshot
import build.raft.app.data.SettingsServerJoinLinkCreateRequest
import build.raft.app.data.SettingsServerJoinLinkSnapshot
import build.raft.app.data.SettingsServerMemberRoleUpdateRequest
import build.raft.app.data.SettingsServerOnboardingAgentSnapshot
import build.raft.app.data.SettingsServerOnboardingSnapshot
import build.raft.app.data.SettingsServerOnboardingUpdateRequest
import build.raft.app.data.SettingsServerProfileUpdateRequest
import build.raft.app.data.SettingsServerSectionSnapshot
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSidebarOrderUpdateRequest
import build.raft.app.data.SettingsServerSnapshot
import build.raft.app.data.SettingsServerTranslationSnapshot
import build.raft.app.data.SettingsServerSystemChannelSnapshot
import build.raft.app.data.SettingsServerTranslationUpdateRequest
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.MemberSnapshot
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.AgentCreated
import build.raft.app.model.Heartbeat
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerDeleted
import build.raft.app.model.ServerPlanUpdated
import build.raft.app.model.ServerId
import build.raft.app.model.ServerMembershipRemoved
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.model.SlockTimeFormat24Hour
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.slockEpochMillisUtcIso
import build.raft.app.model.slockStartOfDayUtcIso
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
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
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.pullToRefreshItem
import com.tencent.kuikly.compose.material3.rememberPullToRefreshState
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.graphicsLayer
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
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
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

const val SETTINGS_HOME_PAGE = "compose_settings_home"
const val SETTINGS_TAB_BAR_SMOKE_PAGE = "compose_settings_tabbar_smoke"
const val BOTTOM_TAB_BAR_SMOKE_PAGE = "compose_bottom_tabbar_smoke"
const val HOME_SEARCH_PAGE = "compose_home_search"
private const val HOME_SAVED_PAGE = "compose_home_saved"
private const val HOME_ACTIVITY_PAGE = "compose_home_activity"
const val HOME_SEARCH_SELECTED_CHANNEL_ID = "selectedChannelId"
const val HOME_SEARCH_INITIAL_OPEN_FILTER_ID = "initialOpenFilterId"
const val HOME_SEARCH_FOCUS_INPUT = "focusSearchInput"
const val AUTH_CHOOSE_SERVER_PAGE = "compose_auth_choose_server"
const val SLOCK_APP_SHELL_PAGE = "compose_slock_app_shell"
private const val SLOCK_APP_SHELL_CHILD_TAB_PAGE = "compose_app_shell_child_tab"
private const val AUTH_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
private const val AUTH_CHOOSE_SERVER_FROM_SHELL = "slockChooseServerFromShell"
private const val AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD = "slockAuthChooseServerSkipInitialLoad"
private const val APP_SHELL_SUPPRESS_RESTORE_LANDING = "slockSuppressRestoreLanding"
private const val APP_SHELL_AUTH_GATE_INSTANCE_ID = "slockAuthGateInstanceId"

private const val HomeSurfaceVisibleResumeDelayMs = 48L
private val EmptyComposerDraftRecordsFlow = MutableStateFlow(emptySet<SharedThreadComposerDraftRecord>())
private val EmptyConversationDirectoryProjectionFlow =
    MutableStateFlow(emptyMap<String, SharedConversationDirectoryProjection>())
private const val AppShellLifecycleAction = "appLifecycle"
private const val AppShellLifecycleResume = "resume"
private const val AppShellLifecycleShow = "show"
private const val HomeDestinationSearch = SharedHomeShellDestinationSearch
private const val HomeDestinationActivity = SharedHomeShellDestinationActivity
private const val HomeDestinationSaved = SharedHomeShellDestinationSaved
private val homeSecondaryDestinationKeys =
    listOf(HomeDestinationSearch, HomeDestinationActivity, HomeDestinationSaved)
private const val SettingsDestinationHome = "home"
private const val SettingsDestinationAccount = "account"
private const val SettingsDestinationComputers = "computers"
private const val SettingsDestinationServer = "server"
private const val SettingsDestinationAdministration = "administration"
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
private const val SettingsDestinationDeveloper = "developer"
private const val SettingsDestinationApiEnvironment = "api-environment"
private const val SettingsDestinationRouter = "router"
private const val SettingsDeveloperUnlockTapCount = 7
private const val AppShellInitialTabKey = "slockInitialTab"
private const val AppShellInitialSettingsDestinationKey = "slockInitialSettingsDestination"
private const val DiagnosticsDeepLinkIdKey = "slockDiagnosticsDeepLinkId"
private const val DiagnosticsDeepLinkEnableProfilerKey = "slockDiagnosticsDeepLinkEnableProfiler"
private const val DiagnosticsDeepLinkResetSceneKey = "slockDiagnosticsDeepLinkResetScene"
private const val DiagnosticsDeepLinkExportReportKey = "slockDiagnosticsDeepLinkExportReport"
private const val DiagnosticsDeepLinkAutoCloseMsKey = "slockDiagnosticsDeepLinkAutoCloseMs"
private const val SharedSettingsDefaultWebBaseUrl = "https://app.raft.build"
private const val AppUpdateNotificationRefreshIntervalMs = 30L * 60L * 1000L
private const val PreJoinAgreementBodyMaxLength = 5_000
private const val AppShellRealtimeAuthRefreshAction = "authRefreshRequired"
private const val AppShellNotificationThreadPayloadKey = "slockNotificationThreadLaunchPayload"
private const val AppShellNotificationServerIdKey = "slockNotificationServerId"
private const val AppShellNotificationThreadIdKey = "slockNotificationThreadId"
private const val AppShellNotificationChannelIdKey = "slockNotificationChannelId"
private const val AppShellNotificationMessageIdKey = "slockNotificationMessageId"
private const val AppShellNotificationParentChannelIdKey = "slockNotificationParentChannelId"
private const val AppShellNotificationParentMessageIdKey = "slockNotificationParentMessageId"
private const val AppShellNotificationCategoryKey = "slockNotificationCategory"
private const val AppShellChildPageGlassStyleLiquid = "liquid"
private val AppShellNotificationLaunchJson = Json { ignoreUnknownKeys = true }
private val SearchControlsHorizontalPadding = 16.dp
private val SearchMessagesHorizontalPadding = 16.dp
private val SearchControlShadowOffset = 2.dp
private val SavedSelectedRowShadowOffset = 2.dp
private val SearchPopoverBorderColor = SlockColor.BorderBlack
private val SearchPopoverShadowColor = SlockColor.ShadowBlack
private val SearchLightRowBorderColor = SlockColor.TextBlack.copy(alpha = 0.3f)
private val SearchMutedTextColor = SlockColor.TextBlack.copy(alpha = 0.62f)

private val AppShellChildPageTabKeys = setOf("home", "tasks", "members")

private typealias AppShellChildTabRenderer = @Composable (tab: String, active: Boolean) -> Unit

private class AppShellChildTabEntry(
    val debugId: Int,
    initialSelectedTab: String = "home",
    initialRenderer: AppShellChildTabRenderer = { _, _ ->
        SlockCenteredLoading()
    }
) {
    var selectedTab by mutableStateOf(initialSelectedTab)
    var renderer: AppShellChildTabRenderer by mutableStateOf(initialRenderer)
}

private data class AppShellChildTabRegistration(
    val entry: AppShellChildTabEntry,
    val replacedEntryId: Int
)

private object AppShellChildTabRegistry {
    private val entries = mutableMapOf<String, AppShellChildTabEntry>()
    private var nextDebugId = 0

    fun createEntryId(): Int {
        nextDebugId += 1
        return nextDebugId
    }

    fun register(shellId: String, entry: AppShellChildTabEntry): AppShellChildTabEntry? =
        entries.put(shellId, entry)

    fun unregister(shellId: String, entry: AppShellChildTabEntry): Boolean {
        return if (entries[shellId] === entry) {
            entries.remove(shellId)
            true
        } else {
            false
        }
    }

    fun entry(shellId: String): AppShellChildTabEntry? = entries[shellId]
}

internal fun sharedSettingsResolvedWebBaseUrl(webUrl: String?): String =
    webUrl?.trim()?.trimEnd('/').orEmpty().ifBlank { SharedSettingsDefaultWebBaseUrl }

internal fun sharedSettingsWebUrl(webUrl: String?, path: String): String {
    val baseUrl = sharedSettingsResolvedWebBaseUrl(webUrl)
    val cleanPath = path.trim().trimStart('/')
    return if (cleanPath.isBlank()) baseUrl else "$baseUrl/$cleanPath"
}

internal fun ScreenLoadState<SharedSettingsDeveloperSnapshot>.configuredWebBaseUrlOrNull(): String? =
    (this as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data?.webUrl

@Page(SETTINGS_HOME_PAGE, supportInLocal = true)
internal class ComposeSettingsHomePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsHomeScreen() }
    }
}

@Composable
private fun SharedHomeSearchEntityLeadingAvatar(
    result: SearchEntityResult,
    agentStatusResolver: SlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver
) {
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
            run {
                val memberAvatarWireValue =
                    result.memberId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { rememberSlockMemberAvatarWireValue(memberAvatarResolver, it) }
                val status =
                    if (result.showPresence) {
                        result.memberId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { rememberSlockAgentStatusIndicatorState(agentStatusResolver, it) }
                            ?: slockAgentStatusIndicatorState(status = null, presence = Presence.Offline)
                    } else {
                        null
                    }

                SlockAvatarWithStatus(
                    statusColor = status?.color,
                    avatarSize = 32.dp,
                    statusSize = 8.dp,
                    statusPulsing = status?.pulsing == true
                ) {
                    SlockAvatar(
                        avatarWireValue = memberAvatarWireValue ?: result.avatarWireValue ?: "random:${result.key}",
                        label = result.title.removePrefix("@").removePrefix("#")
                    )
                }
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
internal class ComposeSettingsTabBarSmokePage : build.raft.app.base.BasePager() {
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
internal class ComposeBottomTabBarSmokePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { BottomTabBarSmokeContent() }
    }
}

@Page(HOME_SEARCH_PAGE, supportInLocal = true)
internal class ComposeHomeSearchPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            HomeSecondaryRouterContent(
                HomeDestinationSearch,
                slockPageVisibleResumeTick,
                initialSearchChannelId = pageData.params.optString(HOME_SEARCH_SELECTED_CHANNEL_ID).trim().takeIf { it.isNotBlank() },
                initialSearchOpenFilterId = pageData.params.optString(HOME_SEARCH_INITIAL_OPEN_FILTER_ID).trim().takeIf { it.isNotBlank() },
                initialSearchFocusInput = pageData.params.optBoolean(HOME_SEARCH_FOCUS_INPUT, true)
            )
        }
    }
}

@Page(HOME_SAVED_PAGE, supportInLocal = true)
internal class ComposeHomeSavedPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSecondaryRouterContent(HomeDestinationSaved, slockPageVisibleResumeTick) }
    }
}

@Page(HOME_ACTIVITY_PAGE, supportInLocal = true)
internal class ComposeHomeActivityPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSecondaryRouterContent(HomeDestinationActivity, slockPageVisibleResumeTick) }
    }
}

private data class HomeSecondaryRouteEnvironment(
    val pager: Pager,
    val bridge: build.raft.app.base.BridgeModule,
    val authStore: SlockAuthSessionStore,
    val networkClient: SlockNetworkClient,
    val localWriteThrough: ComposeLocalWriteThrough,
    val serverMembersStore: SharedServerMembersStore,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    val settingsCapabilities: BridgeSettingsPlatformCapabilities,
    val homeSavedStateCache: SharedHomeSavedStateCache,
    val timeContext: AppShellThreadTimeContext?,
    val alignOwnMessagesRight: Boolean,
    val compactConsecutiveMessages: Boolean,
    val activityTaskStatusBadgesEnabled: Boolean,
    val threadRowAffordances: Set<ThreadRowAffordancePreference>,
    val shellScope: SlockAppShellScope
)

@Composable
private fun rememberHomeSecondaryRouteEnvironment(): HomeSecondaryRouteEnvironment {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val settingsCapabilities = remember(bridge) { BridgeSettingsPlatformCapabilities(bridge) }
    val authStore = sharedStores.authStore
    val networkClient = sharedStores.networkClient
    val localWriteThrough =
        remember(sharedStores.localDataStore, bridge) {
            ComposeLocalWriteThrough(
                sharedStores.localDataStore,
                logger = { message -> bridge.logDebug("home_secondary_cache $message") }
            )
        }
    val sharedPreferenceStore = remember { slockSharedPreferenceStore() }
    val chatLayoutPreferenceRepository =
        remember(sharedPreferenceStore) {
            PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(sharedPreferenceStore))
    }
    var myMessageAlignment by remember { mutableStateOf(MyMessageAlignmentPreference.Default) }
    var compactConsecutiveMessages by remember { mutableStateOf(false) }
    var activityTaskStatusBadgesEnabled by remember { mutableStateOf(false) }
    var threadRowAffordances by remember { mutableStateOf(ThreadRowAffordancePreference.Default) }
    LaunchedEffect(chatLayoutPreferenceRepository) {
        val chatLayout = chatLayoutPreferenceRepository.getChatLayoutPreference()
        myMessageAlignment = chatLayout.myMessageAlignment
        compactConsecutiveMessages = chatLayout.compactConsecutiveMessages
        activityTaskStatusBadgesEnabled = chatLayout.activityTaskStatusBadgesEnabled
        threadRowAffordances = chatLayout.threadRowAffordances
    }
    val timeContext =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.threadTimeContext()
        }
    val shellScope = rememberSlockAppShellScope()
    return remember(
        pager,
        bridge,
        authStore,
        networkClient,
        localWriteThrough,
        settingsCapabilities,
        sharedStores.serverMembersStore,
        sharedStores.composerDraftPersistSlice,
        sharedStores.homeSavedStateCache,
        timeContext,
        myMessageAlignment,
        compactConsecutiveMessages,
        activityTaskStatusBadgesEnabled,
        threadRowAffordances,
        shellScope
    ) {
        HomeSecondaryRouteEnvironment(
            pager = pager,
            bridge = bridge,
            authStore = authStore,
            networkClient = networkClient,
            localWriteThrough = localWriteThrough,
            serverMembersStore = sharedStores.serverMembersStore,
            composerDraftPersistSlice = sharedStores.composerDraftPersistSlice,
            settingsCapabilities = settingsCapabilities,
            homeSavedStateCache = sharedStores.homeSavedStateCache,
            timeContext = timeContext,
            alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
            compactConsecutiveMessages = compactConsecutiveMessages,
            activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
            threadRowAffordances = threadRowAffordances,
            shellScope = shellScope
        )
    }
}

@Composable
private fun HomeSecondaryRouterContent(
    destination: String,
    routeVisibleTick: Int = 0,
    initialSearchChannelId: String? = null,
    initialSearchOpenFilterId: String? = null,
    initialSearchFocusInput: Boolean = false
) {
    val environment = rememberHomeSecondaryRouteEnvironment()
    val retainedDestination = retainedHomeSecondaryDestination(destination)
    PerformanceSceneEffect(
        bridge = environment.bridge,
        sceneName = retainedDestination?.let(::homeSecondaryPerformanceRoute) ?: "kuikly_home_secondary",
        active = true
    )
    when (retainedDestination) {
        HomeDestinationActivity -> HomeActivityRouterContent(environment, routeVisibleTick)
        HomeDestinationSearch ->
            HomeSearchRouterContent(
                environment,
                routeVisibleTick,
                initialChannelId = initialSearchChannelId,
                initialOpenFilterId = initialSearchOpenFilterId,
                initialFocusInput = initialSearchFocusInput
            )
        HomeDestinationSaved -> HomeSavedRouterContent(environment, routeVisibleTick)
        else -> SlockCenteredLoading(message = "Opening...")
    }
}

@Composable
private fun HomeActivityRouterContent(
    environment: HomeSecondaryRouteEnvironment,
    routeVisibleTick: Int = 0
) {
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    SharedHomeActivityScreen(
        bridge = environment.bridge,
        authStore = environment.authStore,
        routeVisibleTick = routeVisibleTick,
        onOpenMessage = { request ->
            environment.pager.openStandaloneHomeMessageRequest(
                bridge = environment.bridge,
                request = request,
                source = "home_activity",
                timeContext = environment.timeContext,
                alignOwnMessagesRight = environment.alignOwnMessagesRight,
                compactConsecutiveMessages = environment.compactConsecutiveMessages,
                threadRowAffordances = environment.threadRowAffordances
            )
        },
        onClose = { environment.pager.closeHomeSecondaryPage() },
        bottomContentPadding = environment.shellScope.bottomInset(includeBottomTabBar = false),
        timeContext = environment.timeContext,
        shellScope = environment.shellScope,
        listState = listState,
        activityTaskStatusBadgesEnabled = environment.activityTaskStatusBadgesEnabled,
        onTitleClick = { scrollScope.launch { listState.animateScrollToTopExpressive() } }
    )
}

@Composable
private fun HomeSearchRouterContent(
    environment: HomeSecondaryRouteEnvironment,
    routeVisibleTick: Int = 0,
    initialChannelId: String? = null,
    initialOpenFilterId: String? = null,
    initialFocusInput: Boolean = false
) {
    val homeDataSource =
        remember(environment.authStore, environment.networkClient) {
            SharedHomeDataSource(
                sessionStore = environment.authStore,
                api = NetworkSlockHomeApi(environment.networkClient),
                announcementsApi = NetworkSlockAnnouncementsRemindersApi(environment.networkClient),
                computersApi = SlockComputersNetworkApi(environment.networkClient),
                parseHome = { payload, session ->
                    parseSlockHome(payload, session) { message -> environment.bridge.log(message) }
                },
                diagnosticsLogger = { message -> environment.bridge.log("home_secondary_home $message") }
            )
        }
    val homeViewModel: SharedHomeViewModel =
        viewModel(key = "home-secondary-home") {
            SharedHomeViewModel(
                authStore = environment.authStore,
                dataSource = homeDataSource,
                diagnosticsLogger = { message -> environment.bridge.log("home_secondary_home $message") }
            )
        }
    val homeState by homeViewModel.state.collectAsState()
    LaunchedEffect(homeViewModel) {
        homeViewModel.dispatch(SharedHomeIntent.Start)
    }
    val searchSavedViewModel = rememberHomeSearchSavedViewModel(environment, key = "home-secondary-search")
    val searchSavedState by searchSavedViewModel.state.collectAsState()
    val normalizedInitialChannelId =
        remember(initialChannelId) { initialChannelId?.trim()?.takeIf { it.isNotBlank() } }
    var skipInitialChannelRefresh by remember(normalizedInitialChannelId) {
        mutableStateOf(normalizedInitialChannelId != null)
    }
    LaunchedEffect(initialChannelId) {
        val channelId = normalizedInitialChannelId ?: return@LaunchedEffect
        searchSavedViewModel.dispatch(SharedHomeSearchSavedIntent.PreselectSearchChannel(channelId))
    }
    LaunchedEffect(routeVisibleTick, normalizedInitialChannelId) {
        if (activityPageDidAppearShouldRefresh(routeVisibleTick)) {
            if (
                skipInitialChannelRefresh &&
                !searchSavedState.search.searched &&
                searchSavedState.search.query.isBlank()
            ) {
                skipInitialChannelRefresh = false
            } else {
                skipInitialChannelRefresh = false
                searchSavedViewModel.dispatch(SharedHomeSearchSavedIntent.RefreshSearch)
            }
        }
    }
    val searchServerId =
        (homeState.content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.server
            ?.id
            ?.value
    val agentStatusResolver =
        remember(environment.serverMembersStore, searchServerId) {
            slockAgentStatusResolver(environment.serverMembersStore, searchServerId)
        }
    val memberAvatarResolver =
        remember(environment.serverMembersStore, searchServerId) {
            slockMemberAvatarResolver(environment.serverMembersStore, searchServerId)
        }
    LaunchedEffect(environment.serverMembersStore, searchServerId) {
        val serverId = searchServerId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        environment.serverMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Background)
    }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val activeDraftRecords by environment.composerDraftPersistSlice.activeDraftRecordsState.collectAsState(emptySet())
    val draftProjection =
        remember(activeDraftRecords) {
            activeDraftRecords.toSharedThreadComposerDraftProjection()
        }
    SharedHomeSearchPanel(
        state = searchSavedState.search,
        homeState = homeState,
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        draftProjection = draftProjection,
        onIntent = searchSavedViewModel::dispatch,
        onClose = { environment.pager.closeHomeSecondaryPage() },
        onOpenItem = { item ->
            environment.pager.openStandaloneHomeMessageRequest(
                bridge = environment.bridge,
                request = item.toHomeMessageOpenRequest(),
                source = "home_search",
                timeContext = environment.timeContext,
                alignOwnMessagesRight = environment.alignOwnMessagesRight,
                compactConsecutiveMessages = environment.compactConsecutiveMessages,
                threadRowAffordances = environment.threadRowAffordances
            )
        },
        onOpenEntity = { entity ->
            environment.pager.openHomeSearchEntity(
                bridge = environment.bridge,
                entity = entity,
                timeContext = environment.timeContext,
                alignOwnMessagesRight = environment.alignOwnMessagesRight,
                compactConsecutiveMessages = environment.compactConsecutiveMessages,
                threadRowAffordances = environment.threadRowAffordances
            )
        },
        initialOpenFilterId = initialOpenFilterId,
        initialFocusInput = initialFocusInput,
        bottomContentPadding = environment.shellScope.bottomInset(includeBottomTabBar = false),
        timeContext = environment.timeContext,
        shellScope = environment.shellScope,
        listState = listState,
        onTitleClick = { scrollScope.launch { listState.animateScrollToTopExpressive() } }
    )
}

@Composable
private fun HomeSavedRouterContent(
    environment: HomeSecondaryRouteEnvironment,
    routeVisibleTick: Int = 0
) {
    val searchSavedViewModel = rememberHomeSearchSavedViewModel(environment, key = "home-secondary-saved")
    val searchSavedState by searchSavedViewModel.state.collectAsState()
    LaunchedEffect(routeVisibleTick) {
        if (activityPageDidAppearShouldRefresh(routeVisibleTick)) {
            searchSavedViewModel.dispatch(SharedHomeSearchSavedIntent.RefreshSaved)
        }
    }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val copyScope = rememberCoroutineScope()
    val activeDraftRecords by environment.composerDraftPersistSlice.activeDraftRecordsState.collectAsState(emptySet())
    val draftProjection =
        remember(activeDraftRecords) {
            activeDraftRecords.toSharedThreadComposerDraftProjection()
        }
    var savedServerId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(environment.authStore, environment.serverMembersStore) {
        val serverId =
            environment.authStore
                .readSession()
                ?.currentServerId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        savedServerId = serverId
        if (serverId != null) {
            environment.serverMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Background)
        }
    }
    val memberAvatarResolver =
        remember(environment.serverMembersStore, savedServerId) {
            slockMemberAvatarResolver(environment.serverMembersStore, savedServerId)
        }
    SharedHomeSavedPanel(
        state = searchSavedState.saved,
        memberAvatarResolver = memberAvatarResolver,
        draftProjection = draftProjection,
        onIntent = searchSavedViewModel::dispatch,
        onClose = { environment.pager.closeHomeSecondaryPage() },
        onOpenItem = { item ->
            environment.pager.openStandaloneHomeMessageRequest(
                bridge = environment.bridge,
                request = item.toHomeMessageOpenRequest(),
                source = "home_saved",
                timeContext = environment.timeContext,
                alignOwnMessagesRight = environment.alignOwnMessagesRight,
                compactConsecutiveMessages = environment.compactConsecutiveMessages,
                threadRowAffordances = environment.threadRowAffordances
            )
        },
        bottomContentPadding = environment.shellScope.bottomInset(includeBottomTabBar = false),
        timeContext = environment.timeContext,
        shellScope = environment.shellScope,
        listState = listState,
        onTitleClick = { scrollScope.launch { listState.animateScrollToTopExpressive() } },
        onCopyLink = { item ->
            copyScope.launch {
                val serverSlug = environment.authStore.readSession()?.currentServerSlug()
                val webBaseUrl =
                    when (val developer = environment.settingsCapabilities.loadDeveloper()) {
                        is DataResult.Content -> developer.data.webUrl
                        else -> null
                    }
                val permalink = serverSlug?.let { sharedHomeSavedPermalink(item, it, webBaseUrl) }
                if (permalink != null) {
                    environment.bridge.copyToPasteboard(permalink)
                    environment.bridge.toast("Copied")
                } else {
                    environment.bridge.toast("Unable to copy link")
                }
            }
        },
        onCopyMarkdown = { item ->
            environment.bridge.copyToPasteboard(item.sharedHomeSavedMarkdownContent())
            environment.bridge.toast("Copied")
        }
    )
}

@Composable
private fun rememberHomeSearchSavedViewModel(
    environment: HomeSecondaryRouteEnvironment,
    key: String
): SharedHomeSearchSavedViewModel {
    val savedRepository =
        remember(environment.authStore, environment.networkClient) {
            RealSavedRepository(
                sessionStore = environment.authStore,
                networkClient = environment.networkClient,
                warningLogger = { message -> environment.bridge.log("home_secondary_saved $message") }
            )
        }
    val searchRepository =
        remember(environment.authStore, environment.networkClient) {
            RealSearchRepository(
                sessionStore = environment.authStore,
                networkClient = environment.networkClient,
                warningLogger = { message -> environment.bridge.log("home_secondary_search $message") }
            )
        }
    val memberRepository =
        remember(environment.authStore, environment.networkClient) {
            RealMemberRepository(
                sessionStore = environment.authStore,
                networkClient = environment.networkClient,
                logger = { message -> environment.bridge.log("home_secondary_member $message") },
                warningLogger = { message -> environment.bridge.log("home_secondary_member_warning $message") }
            )
        }
    return viewModel(key = key) {
        SharedHomeSearchSavedViewModel(
            authStore = environment.authStore,
            savedRepository = savedRepository,
            searchRepository = searchRepository,
            memberRepository = memberRepository,
            serverMembersStore = environment.serverMembersStore,
            savedStateCache = environment.homeSavedStateCache,
            timeRangeBoundsProvider = { range -> environment.timeContext.toSearchIsoBounds(range) }
        )
    }
}

@Page(AUTH_CHOOSE_SERVER_PAGE, supportInLocal = true)
internal class ComposeAuthChooseServerPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthChooseServerPageContent() }
    }
}

@Page(SLOCK_APP_SHELL_PAGE, supportInLocal = true)
internal class ComposeSlockAppShellPage : build.raft.app.base.BasePager() {
    private var initialAuthRestoreTick by mutableStateOf(0)

    override fun willInit() {
        super.willInit()
        setContent { SlockAppShellContent(slockPageVisibleResumeTick, initialAuthRestoreTick) }
    }

    override fun onSlockPageDidAppear(isInitialAppearance: Boolean) {
        if (isInitialAppearance) {
            initialAuthRestoreTick += 1
        }
    }
}

@Page(SLOCK_APP_SHELL_CHILD_TAB_PAGE, supportInLocal = true)
internal class ComposeSlockAppShellChildTabPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockAppShellChildTabPageContent() }
    }
}

@Composable
private fun SlockAppShellChildTabPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val shellId = pager.pageData.params.optString("shellId")
    val tab = retainedAppShellSelectedTab(pager.pageData.params.optString("tab"))
    val entry = AppShellChildTabRegistry.entry(shellId)
    if (entry == null) {
        LaunchedEffect(shellId, tab) {
            bridge.log("app_shell_child_tab_registry_entry shellId=$shellId pageDataTab=$tab result=missing")
        }
        PageErrorContent(
            title = "App Shell",
            subtitle = "Child tab host",
            iconName = SlockLucideIconName.House,
            message = "Could not attach child tab",
            code = "child_tab_registry_missing"
        )
        return
    }
    val active = entry.selectedTab == tab
    LaunchedEffect(shellId, tab, entry.debugId) {
        bridge.log(
            "app_shell_child_tab_registry_entry shellId=$shellId pageDataTab=$tab " +
                "result=hit entryId=${entry.debugId} selectedTab=${entry.selectedTab}"
        )
    }
    LaunchedEffect(tab, active, entry.selectedTab) {
        if (active) {
            bridge.log(
                "app_shell_child_tab_render shellId=$shellId pageDataTab=$tab " +
                    "pageTab=$tab selectedTab=${entry.selectedTab} active=$active entryId=${entry.debugId}"
            )
        }
    }
    entry.renderer(tab, active)
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
    val enabled: Boolean = false,
    val toggleValue: Boolean? = null
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
            iconName = SlockLucideIconName.BadgeInfo
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
        title = "Device",
        subtitle = "Notifications and local device state",
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
                KuiklySharedSettingsRow("logout", SlockLucideIconName.LogOut, "Log out", "Sign out of this device.", status = "Action")
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

internal data class AppShellLaunchSelection(
    val selectedTab: String = "home",
    val selectedSettingsDestination: String = SettingsDestinationHome
)

internal data class DiagnosticsDeepLinkRequest(
    val id: String,
    val enableProfiler: Boolean = false,
    val resetScene: Boolean = false,
    val exportReport: Boolean = false,
    val autoCloseMs: Long = 0L
)

internal fun JSONObject.appShellLaunchSelection(): AppShellLaunchSelection {
    val selectedTab = retainedAppShellSelectedTab(optString(AppShellInitialTabKey))
    return AppShellLaunchSelection(
        selectedTab = selectedTab,
        selectedSettingsDestination =
            retainedAppShellInitialSettingsDestination(
                selectedTab = selectedTab,
                destination = optString(AppShellInitialSettingsDestinationKey)
            )
    )
}

internal fun retainedAppShellInitialSettingsDestination(selectedTab: String, destination: String): String {
    if (retainedAppShellSelectedTab(selectedTab) != "settings") return SettingsDestinationHome
    return destination.takeIf { it.toSharedSettingsDestination() != null } ?: SettingsDestinationHome
}

internal fun JSONObject.diagnosticsDeepLinkRequest(): DiagnosticsDeepLinkRequest? {
    val id = optString(DiagnosticsDeepLinkIdKey).takeIf { it.isNotBlank() } ?: return null
    return DiagnosticsDeepLinkRequest(
        id = id,
        enableProfiler = optBoolean(DiagnosticsDeepLinkEnableProfilerKey, false),
        resetScene = optBoolean(DiagnosticsDeepLinkResetSceneKey, false),
        exportReport = optBoolean(DiagnosticsDeepLinkExportReportKey, false),
        autoCloseMs = optLong(DiagnosticsDeepLinkAutoCloseMsKey, 0L).coerceAtLeast(0L)
    )
}

internal fun retainedAppShellTabOrder(selectedTab: String): List<String> {
    val activeTab = retainedAppShellSelectedTab(selectedTab)
    return retainedAppShellTabKeys.filter { it != activeTab } + activeTab
}

internal fun retainedAppShellComposedTabs(selectedTab: String, mountedTabs: Set<String>): List<String> {
    val activeTab = retainedAppShellSelectedTab(selectedTab)
    return retainedAppShellTabOrder(activeTab).filter { it == activeTab || it in mountedTabs }
}

internal fun activeOnlyAppShellComposedTabs(selectedTab: String): List<String> =
    listOf(retainedAppShellSelectedTab(selectedTab))

internal fun homeWarmAppShellComposedTabs(selectedTab: String, mountedTabs: Set<String>): List<String> {
    val activeTab = retainedAppShellSelectedTab(selectedTab)
    return retainedAppShellTabOrder(activeTab).filter { tab ->
        tab == activeTab || (tab == "home" && "home" in mountedTabs)
    }
}

internal fun warmPrimaryAppShellComposedTabs(selectedTab: String, mountedTabs: Set<String>): List<String> {
    val activeTab = retainedAppShellSelectedTab(selectedTab)
    return retainedAppShellTabOrder(activeTab).filter { tab ->
        tab == activeTab ||
            (tab == "home" && "home" in mountedTabs) ||
            (tab == "tasks" && "tasks" in mountedTabs)
    }
}

internal fun childHostAppShellComposedTabs(selectedTab: String): List<String> =
    activeOnlyAppShellComposedTabs(selectedTab)

private fun isRetainedAppShellTab(tab: String): Boolean =
    tab in retainedAppShellTabKeys

internal fun retainedHomeSecondaryDestination(destination: String?): String? =
    destination?.takeIf { it in homeSecondaryDestinationKeys }

internal fun homeDestinationRefreshesOnLifecycleResume(destination: String?): Boolean =
    when (retainedHomeSecondaryDestination(destination)) {
        null, HomeDestinationActivity, HomeDestinationSaved, HomeDestinationSearch -> true
        else -> false
    }

internal fun appShellPageDidAppearShouldRefresh(pageVisibleTick: Int): Boolean =
    pageVisibleTick > 0

private fun String.toHomeSecondaryOpenIntent(): SharedHomeShellIntent =
    when (retainedHomeSecondaryDestination(this)) {
        HomeDestinationActivity -> SharedHomeShellIntent.OpenActivity
        HomeDestinationSaved -> SharedHomeShellIntent.OpenSaved
        HomeDestinationSearch -> SharedHomeShellIntent.OpenSearch
        else -> SharedHomeShellIntent.CloseSecondaryDestination
    }

internal fun homeSecondaryPerformanceRoute(destination: String): String =
    when (retainedHomeSecondaryDestination(destination)) {
        HomeDestinationActivity -> "kuikly_home_activity"
        HomeDestinationSearch -> "kuikly_home_search"
        HomeDestinationSaved -> "kuikly_home_saved"
        else -> "kuikly_home_secondary"
    }

internal fun appShellBottomTabPerformanceRoute(tab: String): String =
    when (retainedAppShellSelectedTab(tab)) {
        "tasks" -> "kuikly_tasks"
        "members" -> "kuikly_members"
        "settings" -> "kuikly_settings"
        else -> "kuikly_home"
    }

internal fun appShellBottomTabPerformancePhase(tab: String): String =
    "bottom_tab_${retainedAppShellSelectedTab(tab)}"

internal fun appShellBottomTabsVisible(
    selectedTab: String,
    selectedHomeDestination: String?,
    selectedSettingsDestination: String
): Boolean =
    when (retainedAppShellSelectedTab(selectedTab)) {
        "home" -> selectedHomeDestination == null
        "settings" -> selectedSettingsDestination == SettingsDestinationHome
        else -> true
    }

internal fun sharedHomeShouldStartOnActive(active: Boolean, sessionReady: Boolean, state: SharedHomeState): Boolean =
    active && sessionReady && (state.content !is SharedHomeContentState.Content || state.homeRealtimeDirty)

internal fun appShellParentShouldStartHome(
    selectedTab: String,
    selectedHomeDestination: String?,
    readyServerId: String?,
    state: SharedHomeState
): Boolean =
    retainedAppShellSelectedTab(selectedTab) == "home" &&
        selectedHomeDestination == null &&
        !readyServerId.isNullOrBlank() &&
        sharedHomeShouldStartOnActive(active = true, sessionReady = true, state = state)

private fun sharedHomeContentRenderSummary(content: SharedHomeContentState): String =
    when (content) {
        is SharedHomeContentState.Loading ->
            "Loading(session=${content.session?.currentServerId ?: "_"})"
        is SharedHomeContentState.Empty ->
            "Empty(session=${content.session.currentServerId ?: "_"})"
        is SharedHomeContentState.Error ->
            "Error(${content.code})"
        is SharedHomeContentState.Content -> {
            val snapshot = content.snapshot
            "Content(server=${snapshot.server.id.value},channels=${snapshot.channels.size}," +
                "dms=${snapshot.directMessages.size},unread=${snapshot.unreadTotal}:${snapshot.unreadSource}," +
                "refreshing=${content.contentRefreshing})"
        }
    }

private fun sharedHomeRenderBranch(content: SharedHomeContentState): String =
    when (content) {
        is SharedHomeContentState.Loading ->
            if (content.session != null) "loading_session_shell" else "loading_centered"
        is SharedHomeContentState.Empty -> "empty_session_shell"
        is SharedHomeContentState.Error -> "error_${content.code}"
        is SharedHomeContentState.Content -> "content"
    }

internal fun sharedHomeHeaderRefreshing(state: SharedHomeState): Boolean =
    (state.content as? SharedHomeContentState.Content)?.contentRefreshing == true

internal fun homeSurfaceVisibilityDispatchDelayMs(visible: Boolean): Long =
    if (visible) HomeSurfaceVisibleResumeDelayMs else 0L

@Composable
private fun AppShellTabs(
    selectedTab: String,
    childHostShellId: String,
    content: @Composable (tab: String, active: Boolean) -> Unit
) {
    val activeTab = retainedAppShellSelectedTab(selectedTab)
    Box(modifier = Modifier.fillMaxSize()) {
        childHostAppShellComposedTabs(activeTab).forEach { tab ->
            key("app-shell-$tab") {
                if (tab in AppShellChildPageTabKeys) {
                    SlockAppShellChildTabHost(
                        shellId = childHostShellId,
                        tab = tab,
                        active = tab == activeTab
                    )
                } else {
                    content(tab, tab == activeTab)
                }
            }
        }
    }
}

@Composable
private fun SlockAppShellChildTabHost(
    shellId: String,
    tab: String,
    active: Boolean
) {
    val pageData =
        remember(shellId, tab) {
            JSONObject().apply {
                put("shellId", shellId)
                put("tab", tab)
            }
        }
    key("app-shell-child-page-$tab") {
        SlockKuiklyChildPage(
            pageName = SLOCK_APP_SHELL_CHILD_TAB_PAGE,
            pageData = pageData,
            modifier = Modifier.fillMaxSize(),
            active = active,
            glassStyle = AppShellChildPageGlassStyleLiquid
        )
    }
}

@Composable
private fun SlockAppShellContent(pageVisibleTick: Int = 0, initialAuthRestoreTick: Int = 0) {
    // #85 follow-up: seed the KmmResource `StringDesc.localeType`
    // global from the persisted `AppLocale` as early as possible
    // in the app shell lifecycle, so shared `StringDesc` lookups resolve
    // against the user's last selection rather than the OS default.
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val launchSelection = remember(pager.pageData.params.toString()) {
        pager.pageData.params.appShellLaunchSelection()
    }
    val diagnosticsDeepLinkRequest = remember(pager.pageData.params.toString()) {
        pager.pageData.params.diagnosticsDeepLinkRequest()
    }
    val suppressRestoreLanding = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optBoolean(APP_SHELL_SUPPRESS_RESTORE_LANDING, false)
    }
    val authGateInstanceId = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optString(APP_SHELL_AUTH_GATE_INSTANCE_ID)
    }
    var selectedTab by remember { mutableStateOf(launchSelection.selectedTab) }
    var retainedAppShellTabs by remember {
        mutableStateOf(setOf("home", launchSelection.selectedTab))
    }
    var selectedSettingsDestination by remember {
        mutableStateOf(launchSelection.selectedSettingsDestination)
    }
    var settingsHeaderTapCount by remember { mutableStateOf(0) }
    var homeListPosition by remember { mutableStateOf(AppShellServerScopedLazyListPosition()) }
    var tasksListPosition by remember { mutableStateOf(AppShellLazyListPosition()) }
    var membersListState by remember { mutableStateOf(AppShellMembersListState()) }
    var hostNetworkStatus by remember { mutableStateOf(SlockAppShellNetworkStatus.Online) }
    val childTabShellId = remember(pager.pagerId) { "app-shell-child-tabs-${pager.pagerId}" }
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val authStore = sharedStores.authStore
    val authDataSource = sharedStores.authDataSource
    val appShellNetworkClient = sharedStores.networkClient
    val settingsCapabilities = remember { BridgeSettingsPlatformCapabilities(bridge) }
    val memberRepository =
        remember {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                logger = { message -> bridge.log("app_shell_member_repository $message") }
            )
        }
    val homeDataSource =
        remember(memberRepository, sharedStores.serverMembersStore, settingsCapabilities) {
            SharedHomeDataSource(
                sessionStore = authStore,
                api = NetworkSlockHomeApi(appShellNetworkClient),
                announcementsApi = NetworkSlockAnnouncementsRemindersApi(appShellNetworkClient),
                computersApi = SlockComputersNetworkApi(appShellNetworkClient),
                appUpdateStatusProvider = { settingsCapabilities.checkQuiverUpdateStatus() },
                memberRepository = memberRepository,
                serverMembersStore = sharedStores.serverMembersStore,
                parseHome = { payload, session ->
                    parseSlockHome(payload, session) { message -> bridge.log(message) }
                },
                diagnosticsLogger = { message -> bridge.log("shared_home $message") }
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
    val homeReadReceiptRepository =
        remember {
            RealReadReceiptRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                warningLogger = { message -> bridge.log("shared_home_read_receipt $message") }
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
            SlockAgentsApiClient(
                transport = SlockAgentsNetworkApi(appShellNetworkClient),
                diagnosticsLogger = { message -> bridge.log("app_shell_agents_api $message") }
            )
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
    val sharedPreferenceStore = remember { slockSharedPreferenceStore() }
    val composerDraftPersistSlice = sharedStores.composerDraftPersistSlice
    val activeDraftRecords by composerDraftPersistSlice.activeDraftRecordsState.collectAsState(emptySet())
    val draftProjection =
        remember(activeDraftRecords) {
            activeDraftRecords.toSharedThreadComposerDraftProjection()
        }
    val localePreferenceStore =
        remember(sharedPreferenceStore) {
            KeyValueLocalePreferenceStore(sharedPreferenceStore)
        }
    val localeRepository =
        remember(localePreferenceStore) {
            PersistentLocaleRepository(localePreferenceStore)
        }
    val chatLayoutPreferenceRepository =
        remember(sharedPreferenceStore) {
            PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(sharedPreferenceStore))
        }
    val recompositionProfilerPreferenceRepository =
        remember(sharedPreferenceStore) {
            PersistentRecompositionProfilerPreferenceRepository(
                KeyValueRecompositionProfilerPreferenceStore(sharedPreferenceStore)
            )
        }
    val notificationPreferenceEvents = remember(pager) { KuiklyNotificationPreferenceEvents(pager) }
    val appUpdateEvents = remember(pager) { KuiklyAppUpdateEvents(pager) }
    val realtimeEvents = remember(pager) { KuiklyRealtimeEvents(pager) }
    val hostNetworkEvents = remember(pager) { KuiklyHostNetworkEvents(pager) }
    val authSessionEvents =
        remember(pager.pagerId) {
            KuiklyAuthSessionEvents(
                pager = pager,
                source = "app-shell:${pager.pagerId}"
            )
        }
    val notificationPreferenceRepository =
        remember(pager, notificationPreferenceEvents) {
            kuiklyNotificationPreferenceRepository(pager, notificationPreferenceEvents)
        }
    val notificationPreferenceScope = rememberCoroutineScope()
    val appShellCoroutineScope = rememberCoroutineScope()
    DisposableEffect(authSessionEvents, authStore) {
        val callbackRef =
            authSessionEvents.addSessionClearedListener { signal ->
                if (authSessionEvents.isLocalSource(signal)) {
                    return@addSessionClearedListener
                }
                bridge.log("shared_auth_session auth_session_clear_notify_received source=${signal.source} reason=${signal.reason}")
                appShellCoroutineScope.launch {
                    runCatchingPreservingCancellation {
                        authStore.clearSessionFromSessionClearedNotify(
                            reason = signal.reason,
                            previousSessionFingerprint = signal.previousSessionFingerprint
                        )
                    }.onSuccess { applied ->
                        bridge.log(
                            "shared_auth_session auth_session_clear_notify_result result=success applied=$applied " +
                                "source=${signal.source} reason=${signal.reason}"
                        )
                    }.onFailure { throwable ->
                        bridge.log(
                            "shared_auth_session auth_session_clear_notify_result result=failure source=${signal.source} " +
                                "reason=${signal.reason} error=${throwable.message.orEmpty()}"
                        )
                    }
                }
            }
        onDispose {
            authSessionEvents.removeSessionClearedListener(callbackRef)
        }
    }
    val homeActivityRepository =
        remember {
            RealInboxRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                logger = { message -> bridge.log("shared_home_activity $message") },
                warningLogger = { message -> bridge.log("shared_home_activity_warning $message") }
            )
        }
    val homeSidebarOrderRepository =
        remember {
            RealSettingsRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                logger = { message -> bridge.log("shared_home_sidebar_order $message") },
                warningLogger = { message -> bridge.log("shared_home_sidebar_order_warning $message") }
            )
        }
    val homeStore =
        remember(
            sharedStores,
            homeDataSource,
            homeSidebarOrderRepository,
            homeReadReceiptRepository,
            channelRepository,
            appShellCoroutineScope
        ) {
            sharedStores.getOrCreateHomeStore(
                dataSource = homeDataSource,
                diagnosticsLogger = { message -> bridge.log("shared_home $message") },
                loadSidebarOrder = { homeSidebarOrderRepository.getServerSidebarOrder() },
                updateSidebarOrder = { request -> homeSidebarOrderRepository.updateServerSidebarOrder(request) },
                markChannelReadAll = { channelId -> homeReadReceiptRepository.markChannelReadAll(channelId) },
                markChannelUnread = { channelId -> homeReadReceiptRepository.markChannelUnread(channelId) },
                archiveChannel = { channelId -> channelRepository.archiveChannel(channelId) },
                pinUpdateFailureCallback = { message -> bridge.toast(message) },
            )
        }
    val homeActivityStore =
        remember(
            authStore,
            homeActivityRepository,
            homeReadReceiptRepository,
            conversationRepository,
            memberRepository,
            sharedStores.serverMembersStore,
            homeStore
        ) {
            sharedStores.getOrCreateHomeActivityStore(
                inboxRepository = homeActivityRepository,
                readReceiptRepository = homeReadReceiptRepository,
                conversationRepository = conversationRepository,
                memberRepository = memberRepository,
                onUnreadCountsChanged = { homeStore.dispatch(SharedHomeIntent.ForegroundRefresh) },
                diagnosticsLogger = { message -> bridge.log("shared_home_activity $message") }
            )
        }
    val appShellComputersStore =
        remember(computersApiClient, appShellCoroutineScope) {
            SharedComputersStore(
                computersApiClient = computersApiClient,
                scope = appShellCoroutineScope,
                logger = { message -> bridge.log("app_shell_$message") }
            )
        }
    var notificationPreferenceSnapshot by remember {
        mutableStateOf(NotificationPreferenceSnapshot(enabled = true))
    }
    val settingsRepository =
        remember(appShellNetworkClient) {
            RealSettingsRepository(
                sessionStore = authStore,
                networkClient = appShellNetworkClient,
                logger = { message -> bridge.log("shared_settings_repository $message") },
                warningLogger = { message -> bridge.log("shared_settings_repository_warning $message") },
                channelRepository = channelRepository
            )
        }
    val settingsDataSource =
        remember(appShellComputersStore) {
            SharedSettingsDataSource(
                sessionStore = authStore,
                settingsRepository = settingsRepository,
                computersSource = appShellComputersStore,
                serverMembersStore = sharedStores.serverMembersStore,
                notificationPreferenceRepository = notificationPreferenceRepository,
                localeRepository = localeRepository,
                chatLayoutPreferenceRepository = chatLayoutPreferenceRepository,
                recompositionProfilerPreferenceRepository = recompositionProfilerPreferenceRepository,
                apiEnvironmentStore = sharedStores.apiEnvironmentStore,
                platformCapabilities = settingsCapabilities,
                diagnosticsLogger = { message -> bridge.log("shared_settings $message") }
            )
        }
    var authGateMessage by remember { mutableStateOf<String?>(null) }
    var myMessageAlignment by remember { mutableStateOf(MyMessageAlignmentPreference.Default) }
    var compactConsecutiveMessages by remember { mutableStateOf(false) }
    var activityTaskStatusBadgesEnabled by remember { mutableStateOf(false) }
    var threadRowAffordances by remember { mutableStateOf(ThreadRowAffordancePreference.Default) }
    var createChannelDialogOpen by remember { mutableStateOf(false) }
    var createChannelSubmitting by remember { mutableStateOf(false) }
    var createChannelError by remember { mutableStateOf<String?>(null) }
    var createChannelMembersLoading by remember { mutableStateOf(false) }
    var createChannelMembers by remember { mutableStateOf<List<MemberProfile>>(emptyList()) }
    var pendingDiagnosticsFeedback by remember { mutableStateOf<SettingsDiagnosticsFeedbackDraft?>(null) }
    var pendingDiagnosticsFeedbackReturnDestination by remember { mutableStateOf(SettingsDestinationDiagnostics) }
    var diagnosticsFeedbackUploadChannelId by remember { mutableStateOf<String?>(null) }
    var consumedNotificationLaunchKey by remember { mutableStateOf<String?>(null) }
    var switchingNotificationLaunchKey by remember { mutableStateOf<String?>(null) }
    val authGateViewModel: SharedAuthGateViewModel =
        viewModel(key = appShellAuthGateViewModelKey(pager.pagerId, authGateInstanceId)) {
            SharedAuthGateViewModel(authDataSource)
        }
    var authGateState by remember { mutableStateOf(authGateViewModel.state.value) }
    LaunchedEffect(authGateViewModel) {
        authGateViewModel.state.collect { nextState ->
            authGateState = nextState
        }
    }
    var consumedInitialAuthRestoreTick by remember { mutableStateOf(0) }
    LaunchedEffect(initialAuthRestoreTick) {
        if (
            initialAuthRestoreTick > 0 &&
            consumedInitialAuthRestoreTick != initialAuthRestoreTick
        ) {
            consumedInitialAuthRestoreTick = initialAuthRestoreTick
            authGateViewModel.accept(SharedAuthGateIntent.Restore)
            authGateState = authGateViewModel.state.value
        }
    }
    val hostThreadTimeContext = remember(pager.pageData.params.toString()) {
        pager.pageData.params.threadTimeContext()
    }
    val notificationThreadLaunchRequest =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.appShellNotificationThreadLaunchRequest()
        }
    val notificationLaunchServerId =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.appShellNotificationServerId()
        }
    val homeSearchSavedViewModel: SharedHomeSearchSavedViewModel =
        viewModel(key = "shared-home-search-saved") {
            SharedHomeSearchSavedViewModel(
                authStore = authStore,
                savedRepository = savedRepository,
                searchRepository = searchRepository,
                memberRepository = memberRepository,
                serverMembersStore = sharedStores.serverMembersStore,
                savedStateCache = sharedStores.homeSavedStateCache,
                timeRangeBoundsProvider = { range -> hostThreadTimeContext.toSearchIsoBounds(range) },
                diagnosticsLogger = { message -> bridge.log("shared_home_search_saved $message") }
            )
        }
    val homeShellStore =
        remember(
            homeStore,
            homeActivityStore,
            homeSearchSavedViewModel,
            appShellCoroutineScope
        ) {
            SharedHomeShellStore(
                homeState = homeStore.state,
                activityState = homeActivityStore.state,
                searchSavedState = homeSearchSavedViewModel.state,
                dispatchHome = homeStore::dispatch,
                dispatchActivity = homeActivityStore::dispatch,
                dispatchSearchSaved = homeSearchSavedViewModel::dispatch,
                resetActivity = homeActivityStore::reset,
                diagnosticsLogger = { message -> bridge.log("shared_home_shell $message") },
                scope = appShellCoroutineScope
            )
        }
    DisposableEffect(homeShellStore) {
        onDispose { homeShellStore.clear() }
    }
    val homeShellState by homeShellStore.state.collectAsState()
    val homeState = homeShellState.home
    val homeActivityState = homeShellState.activity
    val homeSearchSavedState = homeShellState.searchSaved
    val selectedHomeDestination = homeShellState.destination
    val selectedHomeConversationId = homeShellState.selectedConversationId
    // #81: forward the typed selected-server badge projection to the host
    // bridge. The host platform MUST use `count` for the system badge
    // and `source` only for diagnostics / logging. See
    // [build.raft.app.base.BridgeModule.appBadge] and
    // [build.raft.app.data.AppBadgeCount] for the contract.
    LaunchedEffect(homeState.appBadgeCount) {
        val badge = homeState.appBadgeCount
        bridge.appBadge(
            count = badge.count,
            source = badge.source.wire,
            isStale = badge.isStale
        )
    }
    val tasksViewModel: SharedTasksViewModel =
        viewModel(key = "shared-tasks") {
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = tasksDataSource,
                sharedTasksDataStore = sharedStores.tasksDataStore,
                diagnosticsLogger = { message -> bridge.log("shared_tasks $message") }
            )
        }
    val tasksState by tasksViewModel.state.collectAsState()
    var tasksFirstRenderLoggedKey by remember { mutableStateOf<String?>(null) }
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "shared-settings") {
            SharedSettingsViewModel(
                settingsDataSource,
                diagnosticsLogger = { message -> bridge.log("shared_settings $message") }
            )
        }
    val settingsState by settingsViewModel.state.collectAsState()
    var consumedDiagnosticsDeepLinkId by remember { mutableStateOf<String?>(null) }
    val readySession = (authGateState as? SharedAuthGateState.Ready)?.session
    val readyServerId = readySession?.currentServerId
    val latestReadySession by rememberUpdatedState(readySession)
    val latestSelectedTab by rememberUpdatedState(selectedTab)
    val latestSelectedHomeDestination by rememberUpdatedState(selectedHomeDestination)
    LaunchedEffect(authGateState, suppressRestoreLanding, selectedTab, readyServerId) {
        bridge.log(
            "app_shell_auth_gate_state state=${authGateState.authGateDiagnosticsName()} " +
                "selectedServer=${!readyServerId.isNullOrBlank()} selectedTab=$selectedTab " +
                "suppressRestoreLanding=$suppressRestoreLanding pagerId=${pager.pagerId} " +
                "childShellId=$childTabShellId"
        )
    }
    val homeSurfaceVisible =
        selectedTab == "home" &&
            selectedHomeDestination == null
    val parentShouldStartHome =
        appShellParentShouldStartHome(
            selectedTab = selectedTab,
            selectedHomeDestination = selectedHomeDestination,
            readyServerId = readyServerId,
            state = homeState
        )
    LaunchedEffect(parentShouldStartHome, readyServerId, homeState.content, homeState.homeRealtimeDirty) {
        bridge.log(
            "app_shell_parent_home_start_check shouldStart=$parentShouldStartHome " +
                "selectedTab=$selectedTab homeDestination=${selectedHomeDestination.orEmpty()} " +
                "readyServerId=${readyServerId.orEmpty()} state=${sharedHomeContentRenderSummary(homeState.content)} " +
                "homeRealtimeDirty=${homeState.homeRealtimeDirty}"
        )
        if (parentShouldStartHome) {
            bridge.log(
                "app_shell_parent_home_start_dispatch readyServerId=${readyServerId.orEmpty()} " +
                    "state=${sharedHomeContentRenderSummary(homeState.content)}"
            )
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Start))
        }
    }
    LaunchedEffect(homeSurfaceVisible, readyServerId) {
        val delayMs = homeSurfaceVisibilityDispatchDelayMs(homeSurfaceVisible)
        if (delayMs > 0L) {
            delay(delayMs)
        }
        homeShellStore.dispatch(SharedHomeShellIntent.SetSurfaceVisible(homeSurfaceVisible && readyServerId != null))
    }
    LaunchedEffect(readyServerId, readySession?.accessToken) {
        if (readyServerId.isNullOrBlank()) return@LaunchedEffect
        while (true) {
            delay(AppUpdateNotificationRefreshIntervalMs)
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.RefreshNotifications))
        }
    }
    DisposableEffect(appUpdateEvents) {
        val callbackRef =
            appUpdateEvents.addStatusListener {
                homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.RefreshNotifications))
            }
        onDispose {
            appUpdateEvents.removeStatusListener(callbackRef)
        }
    }
    val loadedSettingsTimeFormat =
        (settingsState.languageRegion as? ScreenLoadState.Content)?.data?.timeFormat?.selectedKey
    val sessionTimeFormat = readySession?.user?.preferredTimeFormat ?: readySession?.user?.timeFormat
    val threadTimeContext =
        remember(hostThreadTimeContext, loadedSettingsTimeFormat, sessionTimeFormat) {
            hostThreadTimeContext.withTimeFormat(loadedSettingsTimeFormat ?: sessionTimeFormat)
        }
    LaunchedEffect(diagnosticsDeepLinkRequest?.id, readyServerId) {
        val request = diagnosticsDeepLinkRequest ?: return@LaunchedEffect
        if (readyServerId == null || consumedDiagnosticsDeepLinkId == request.id) {
            return@LaunchedEffect
        }
        consumedDiagnosticsDeepLinkId = request.id
        selectedTab = "settings"
        retainedAppShellTabs = retainedAppShellTabs + "settings"
        selectedSettingsDestination = SettingsDestinationDiagnostics
        bridge.log(
            "diagnostics_deeplink_start id=${request.id} " +
                "enableProfiler=${request.enableProfiler} resetScene=${request.resetScene} " +
                "exportReport=${request.exportReport} autoCloseMs=${request.autoCloseMs}"
        )
        settingsViewModel.accept(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Diagnostics))
        if (request.enableProfiler) {
            settingsViewModel.accept(SharedSettingsIntent.SetRecompositionProfilerEnabled(true))
        }
        if (request.resetScene) {
            settingsViewModel.accept(SharedSettingsIntent.ResetRecompositionProfiler)
        }
        if (request.exportReport) {
            settingsViewModel.accept(SharedSettingsIntent.ExportRecompositionProfilerReport)
        }
        if (request.autoCloseMs > 0L) {
            delay(request.autoCloseMs)
            bridge.closePage()
        }
        bridge.log("diagnostics_deeplink_complete id=${request.id}")
    }
    val shellScope = rememberSlockAppShellScope()
    val realtimeEventMapper = remember { RealtimeEventMapper() }
    var realtimeLastSeq by remember { mutableStateOf(0L) }
    var realtimeSessionKey by remember { mutableStateOf<String?>(null) }
    var membersListRefreshKey by remember { mutableStateOf(0) }
    var membersAgentDetailRefreshKey by remember { mutableStateOf(0) }
    var membersListCachedContent by remember { mutableStateOf<SharedMembersUiState.Content?>(null) }

    fun refreshVisibleAppShellRoute(reason: String) {
        if (latestReadySession == null) return
        bridge.log(
            "app_shell_visible_refresh reason=$reason tab=${latestSelectedTab} " +
                "homeDestination=${latestSelectedHomeDestination.orEmpty()}"
        )
        when (retainedAppShellSelectedTab(latestSelectedTab)) {
            "home" ->
                if (homeDestinationRefreshesOnLifecycleResume(latestSelectedHomeDestination)) {
                    when (retainedHomeSecondaryDestination(latestSelectedHomeDestination)) {
                        null, HomeDestinationActivity, HomeDestinationSaved, HomeDestinationSearch ->
                            homeShellStore.dispatch(SharedHomeShellIntent.ForegroundRefresh)
                    }
                }
            "tasks" -> tasksViewModel.dispatch(SharedTasksRouteIntent.Refresh)
            "members" -> {
                membersListRefreshKey += 1
                membersAgentDetailRefreshKey += 1
            }
            "settings" -> settingsViewModel.dispatch(SharedSettingsIntent.Refresh)
        }
    }

    LaunchedEffect(pageVisibleTick) {
        if (appShellPageDidAppearShouldRefresh(pageVisibleTick)) {
            if (latestSelectedHomeDestination != null) {
                homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
            } else {
                refreshVisibleAppShellRoute("page_did_appear")
            }
        }
    }

    fun resetAppShellSessionState() {
        realtimeLastSeq = 0L
        realtimeSessionKey = null
        membersListRefreshKey = 0
        membersAgentDetailRefreshKey = 0
        membersListCachedContent = null
        sharedStores.homeSavedStateCache.clear()
        selectedTab = "home"
        retainedAppShellTabs = setOf("home")
        selectedSettingsDestination = SettingsDestinationHome
        homeListPosition = AppShellServerScopedLazyListPosition()
        tasksListPosition = AppShellLazyListPosition()
        membersListState = AppShellMembersListState()
        homeShellStore.reset()
        pendingDiagnosticsFeedback = null
        pendingDiagnosticsFeedbackReturnDestination = SettingsDestinationDiagnostics
    }

    LaunchedEffect(homeState.content, authGateState) {
        val error = (homeState.content as? SharedHomeContentState.Error)?.code ?: return@LaunchedEffect
        if (!ScreenError(error, retryable = false).isAuthSessionError()) return@LaunchedEffect
        val cachedSession = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
        if (!shouldApplyHomeAuthErrorGate(error, authGateState, cachedSession)) {
            bridge.logWarn(
                "app_shell_home_auth_error_gate_skip code=$error " +
                    "gate=${authGateState.authGateDiagnosticsName()} hasCachedSession=${cachedSession != null}"
            )
            if (authGateState == SharedAuthGateState.AuthRequired && cachedSession != null) {
                authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
            }
            return@LaunchedEffect
        }
        bridge.logWarn("app_shell_home_auth_error_gate code=$error")
        authGateMessage = "Sign in to continue."
        authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
        resetAppShellSessionState()
    }

    LaunchedEffect(notificationPreferenceRepository) {
        notificationPreferenceSnapshot =
            notificationPreferenceRepository.getNotificationPreference()
    }
    LaunchedEffect(chatLayoutPreferenceRepository) {
        val chatLayout = chatLayoutPreferenceRepository.getChatLayoutPreference()
        myMessageAlignment = chatLayout.myMessageAlignment
        compactConsecutiveMessages = chatLayout.compactConsecutiveMessages
        activityTaskStatusBadgesEnabled = chatLayout.activityTaskStatusBadgesEnabled
        threadRowAffordances = chatLayout.threadRowAffordances
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
            bridge.realtimeDisconnect("auth_invalid")
            authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
            resetAppShellSessionState()
        }
    }
    LaunchedEffect(authStore) {
        authStore.sessionChanges.collect { fingerprint ->
            val cachedSession =
                runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
            val currentSession = (authGateViewModel.state.value as? SharedAuthGateState.Ready)?.session
            val identityChanged =
                cachedSession?.user?.id != currentSession?.user?.id ||
                    cachedSession?.currentServerId != currentSession?.currentServerId
            bridge.log(
                "app_shell_session_change_received fingerprint=$fingerprint " +
                    "hasSession=${cachedSession != null} identityChanged=$identityChanged"
            )
            authGateMessage = null
            if (identityChanged) {
                bridge.realtimeDisconnect("auth_changed")
                resetAppShellSessionState()
            }
            authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
        }
    }

    when (val gate = authGateState) {
        SharedAuthGateState.Restoring -> {
            if (suppressRestoreLanding) {
                AppShellSilentRestoreContent()
            } else {
                AppShellRestoreSplashContent()
            }
            return
        }
        SharedAuthGateState.AuthRequired -> {
            LaunchedEffect(authGateMessage) {
                pager.openLoginReplacingCurrentPage(authGateMessage)
            }
            AppShellRestoreSplashContent()
            return
        }
        is SharedAuthGateState.ServerRequired -> {
            AppShellServerGateContent(
                session = gate.session,
                authDataSource = authDataSource,
                onReady = { session ->
                    authGateMessage = null
                    authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(session))
                    resetAppShellSessionState()
                },
                onSignOut = {
                    authGateMessage = null
                    bridge.realtimeDisconnect("logout")
                    authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
                    resetAppShellSessionState()
                }
            )
            return
        }
        is SharedAuthGateState.Error -> {
            AppShellAuthErrorContent(
                message = gate.message,
                onRetry = { authGateViewModel.dispatch(SharedAuthGateIntent.Retry) },
                onSignIn = { authGateViewModel.dispatch(SharedAuthGateIntent.ShowSignIn) },
                onOpenDiagnostics = { pager.openAppShellDeveloper() }
            )
            return
        }
        is SharedAuthGateState.Ready -> Unit
    }

    fun openHomeConversation(conversation: ThreadLaunchConversationIdentity, source: String, anchorMessageId: String? = null, pushCurrent: Boolean = true) {
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
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(conversation.threadId.value))
        val launchPayload =
            buildStandaloneConversationThreadLaunchPayload(
                conversation = conversation,
                timeContext = threadTimeContext,
                alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                compactConsecutiveMessages = compactConsecutiveMessages,
                threadRowAffordances = threadRowAffordances,
                anchorMessageId = anchorMessageId
            )
        bridge.log(
            "home_open_thread_launch source=$source threadId=${conversation.threadId.value} " +
                "sourceThreadId=${launchPayload.sourceThreadId.orEmpty()} surface=${launchPayload.surfaceKind} " +
                "parentChannelId=${launchPayload.parentChannelId.orEmpty()} parentMessageId=${launchPayload.parentMessageId.orEmpty()} " +
                "anchorMessageId=${anchorMessageId.orEmpty()} originParentMessageId=${launchPayload.originParentMessageId.orEmpty()}"
        )
        pager.openStandaloneThreadLaunchPayload(launchPayload)
    }

    LaunchedEffect(
        readySession?.currentServerId,
        notificationLaunchServerId,
        notificationThreadLaunchRequest?.threadId,
        notificationThreadLaunchRequest?.messageId,
        notificationThreadLaunchRequest?.parentChannelId,
        homeState.content,
        threadTimeContext,
        myMessageAlignment
    ) {
        val launchRequest = notificationThreadLaunchRequest ?: return@LaunchedEffect
        val launchKey = "${notificationLaunchServerId.orEmpty()}|${launchRequest.threadId}|${launchRequest.messageId}"
        if (consumedNotificationLaunchKey == launchKey) {
            return@LaunchedEffect
        }
        val session = readySession ?: return@LaunchedEffect
        val targetServerId = notificationLaunchServerId
        if (!targetServerId.isNullOrBlank() && targetServerId != session.currentServerId) {
            if (switchingNotificationLaunchKey == launchKey) {
                return@LaunchedEffect
            }
            if (session.servers.none { server -> server.id == targetServerId }) {
                consumedNotificationLaunchKey = launchKey
                bridge.logWarn(
                    "app_shell_notification_launch_drop reason=server_unavailable " +
                        "targetServerId=$targetServerId currentServerId=${session.currentServerId.orEmpty()}"
                )
                bridge.toast("Notification server unavailable")
                return@LaunchedEffect
            }
            switchingNotificationLaunchKey = launchKey
            bridge.log(
                "app_shell_notification_server_switch_start " +
                    "targetServerId=$targetServerId currentServerId=${session.currentServerId.orEmpty()}"
            )
            when (val result = authDataSource.selectServer(targetServerId)) {
                is SharedAuthResult.Success -> {
                    authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(result.data))
                    resetAppShellSessionState()
                    bridge.log("app_shell_notification_server_switch_success targetServerId=$targetServerId")
                }
                is SharedAuthResult.Failure -> {
                    consumedNotificationLaunchKey = launchKey
                    bridge.logWarn("app_shell_notification_server_switch_failed code=${result.code}")
                    bridge.toast("Notification server unavailable")
                }
                SharedAuthResult.Unauthenticated -> {
                    consumedNotificationLaunchKey = launchKey
                    bridge.logWarn("app_shell_notification_server_switch_failed result=unauthenticated")
                    bridge.toast("Sign in to open this notification")
                }
            }
            switchingNotificationLaunchKey = null
            return@LaunchedEffect
        }
        val content = homeState.content as? SharedHomeContentState.Content
        if (content == null) {
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Start))
            bridge.log(
                "app_shell_notification_thread_defer reason=home_not_loaded " +
                    "threadId=${launchRequest.threadId} messageId=${launchRequest.messageId}"
            )
            return@LaunchedEffect
        }
        val conversation = homeState.notificationThreadConversation(launchRequest)
        if (conversation == null) {
            consumedNotificationLaunchKey = launchKey
            bridge.logWarn(
                "app_shell_notification_launch_drop reason=conversation_not_found " +
                    "threadId=${launchRequest.threadId} channelId=${launchRequest.channelId.orEmpty()} " +
                    "parentChannelId=${launchRequest.parentChannelId.orEmpty()} messageId=${launchRequest.messageId}"
            )
            bridge.toast("Notification conversation unavailable")
            return@LaunchedEffect
        }
        consumedNotificationLaunchKey = launchKey
        val anchorMessageId = conversation.notificationAnchorMessageId(launchRequest)
        selectedTab = "home"
        retainedAppShellTabs = retainedAppShellTabs + "home"
        homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
        bridge.log(
            "app_shell_notification_thread_open " +
                "threadId=${conversation.threadId.value} focusedMessageId=$anchorMessageId"
        )
        openHomeConversation(
            conversation = conversation.toThreadLaunchConversationIdentity(),
            source = "notification",
            anchorMessageId = anchorMessageId
        )
    }
    LaunchedEffect(readySession?.currentServerId, readySession?.accessToken) {
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
            sharedStores.agentDetailStore.dispatch(SharedAgentDetailIntent.ResetAgentActivitySeq)
            bridge.realtimeConnect(
                serverId = serverId,
                accessToken = accessToken,
                lastSeq = resumeSeq
            )
        }
    }

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
        if (diagnosticsFeedbackUploadChannelId != null) {
            bridge.toast("Preparing diagnostics attachment...")
            return
        }
        bridge.log(
            "diagnostics_feedback_channel_selected channelId=$channelId source=$source " +
                "fileName=${draft.fileName} pushCurrent=$pushCurrent"
        )
        diagnosticsFeedbackUploadChannelId = channelId
        bridge.toast("Preparing diagnostics attachment...")
        appShellCoroutineScope.launch {
            val uploadResult =
                withContext(Dispatchers.Default) {
                    conversationRepository.uploadAttachments(
                        AttachmentUploadRequest(
                            channelId = ChannelId(channelId),
                            files = listOf(draft.toDiagnosticsPlatformFile())
                        )
                    )
                }
            diagnosticsFeedbackUploadChannelId = null
            when (uploadResult) {
                is DataResult.Content -> {
                    val uploadedAttachments =
                        uploadResult.data.attachments.map { attachment ->
                            SharedThreadComposerAttachment(
                                id = attachment.id.value,
                                fileName = attachment.name,
                                sizeLabel = attachment.sizeLabel,
                                mimeType = attachment.mimeType.takeIf { it.isNotBlank() },
                                status = "ready"
                            )
                        }
                    if (uploadedAttachments.isEmpty()) {
                        bridge.toast("Diagnostics attachment upload returned empty")
                        return@launch
                    }
                    homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(conversation.threadId.value))
                    selectedSettingsDestination = pendingDiagnosticsFeedbackReturnDestination
                    pendingDiagnosticsFeedback = null
                    pendingDiagnosticsFeedbackReturnDestination = SettingsDestinationDiagnostics
                    val launchPayload =
                        buildStandaloneConversationThreadLaunchPayload(
                            conversation = conversation.toThreadLaunchConversationIdentity(),
                            timeContext = threadTimeContext,
                            alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                            compactConsecutiveMessages = compactConsecutiveMessages,
                            threadRowAffordances = threadRowAffordances
                        ).withDiagnosticsFeedbackDraft(
                            draft = draft
                        )
                    composerDraftPersistSlice.stageUploadedAttachments(
                        draftKey = launchPayload.threadComposerDraftKey(),
                        attachments = uploadedAttachments
                    )
                    pager.openStandaloneThreadLaunchPayload(launchPayload)
                    bridge.toast("Diagnostics draft ready")
                }
                is DataResult.Error -> {
                    val reason = threadFailureReasonLabel(uploadResult.error.code) ?: uploadResult.error.code
                    bridge.toast("Diagnostics attachment upload failed: $reason")
                    bridge.log("diagnostics_feedback_attachment_upload_failed code=${uploadResult.error.code}")
                }
                is DataResult.NoPermission -> {
                    bridge.toast("You cannot attach diagnostics in this channel")
                    bridge.log("diagnostics_feedback_attachment_upload_blocked code=${uploadResult.blocker.code}")
                }
                is DataResult.Empty -> {
                    bridge.toast("Diagnostics attachment upload returned empty")
                    bridge.log("diagnostics_feedback_attachment_upload_empty reason=${uploadResult.reason}")
                }
            }
        }
    }

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
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
            return true
        }
        if (selectedTab == "settings" && selectedSettingsDestination != SettingsDestinationHome) {
            selectedSettingsDestination = SettingsDestinationHome
            return true
        }
        if (selectedTab == "home" && selectedHomeDestination != null) {
            homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
            return true
        }
        if (selectedTab != "home") {
            selectedTab = "home"
            selectedSettingsDestination = SettingsDestinationHome
            homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
            return true
        }
        return false
    }

    fun openHomeSecondaryDestination(destination: String) {
        retainedHomeSecondaryDestination(destination)?.let { retainedDestination ->
            homeShellStore.dispatch(retainedDestination.toHomeSecondaryOpenIntent())
            pager.openHomeSecondaryPage(retainedDestination, threadTimeContext)
        }
    }

    val handleAppShellBack by rememberUpdatedState {
        if (popMainRoute()) {
            bridge.log(
                "app_back_root result=main selectedTab=$selectedTab " +
                    "homeDestination=${selectedHomeDestination.orEmpty()} settingsDestination=$selectedSettingsDestination"
            )
        } else {
            bridge.logWarn(
                "app_back_root result=close_page selectedTab=$selectedTab " +
                    "homeDestination=${selectedHomeDestination.orEmpty()} settingsDestination=$selectedSettingsDestination"
            )
            bridge.closePage()
        }
    }

    SlockAppShellRootBackHandler(
        onLifecycleLog = { event ->
            bridge.log(
                "app_back_root_handler $event selectedTab=$selectedTab " +
                    "homeDestination=${selectedHomeDestination.orEmpty()} settingsDestination=$selectedSettingsDestination"
            )
        }
    ) { handleAppShellBack() }

    LaunchedEffect(selectedTab) {
        if (selectedTab == "tasks") {
            val hasSnapshot = tasksState.hasCompletedTasksSnapshot()
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
                    when (val result = sharedStores.serverMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Visible)) {
                        is DataResult.Content -> sharedStores.serverMembersStore.currentServerMembers(ServerId(serverId))
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
                        homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
                        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(channel.threadId.value))
                        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.ChannelCreated(channel)))
                        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Refresh))
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
                    authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(refreshed))
                    realtimeSessionKey = "${refreshed.accessToken}|$refreshedServerId"
                    sharedStores.agentDetailStore.dispatch(SharedAgentDetailIntent.ResetAgentActivitySeq)
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
    val latestResetAppShellSessionState by rememberUpdatedState<() -> Unit>({ resetAppShellSessionState() })
    DisposableEffect(
        sharedStores.realtimeDomainEventOwner,
        homeShellStore,
        settingsViewModel,
        bridge
    ) {
        val sinks =
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, event ->
                    homeShellStore.dispatch(SharedHomeShellIntent.ApplyRealtimeEvent(serverId, event))
                },
                applySettings = { serverId, event ->
                    settingsViewModel.dispatch(SharedSettingsIntent.ApplyRealtimeEvent(serverId, event))
                },
                applyAgentActivity = { event ->
                    sharedStores.agentDetailStore.dispatch(
                        SharedAgentDetailIntent.ApplyAgentActivityUpdate(event)
                    )
                    sharedStores.serverMembersStore.applyPresence(event)
                },
                requestMemberDetailRefresh = {
                    membersAgentDetailRefreshKey += 1
                },
                joinChannel = { channelId ->
                    realtimeEvents.joinChannel(channelId)
                },
                emitRouteMarker = { event ->
                    if (event.shouldEmitAppShellRouteMarker()) {
                        bridge.performanceMarker(
                            phase = event.performanceMarkerPhase(),
                            routeName = "kuikly_home",
                            event = "instant",
                            metadata =
                                mapOf(
                                    "type" to event.performanceMarkerName(),
                                    "activeThread" to "false"
                                )
                        )
                    }
                },
                log = { message -> bridge.log(message) }
            )
        sharedStores.realtimeDomainEventOwner.registerSinks(sinks)
        onDispose { sharedStores.realtimeDomainEventOwner.unregisterSinks(sinks) }
    }

    DisposableEffect(hostNetworkEvents) {
        val callbackRef =
            hostNetworkEvents.addListener { signal ->
                hostNetworkStatus =
                    if (signal.online) {
                        SlockAppShellNetworkStatus.Online
                    } else {
                        SlockAppShellNetworkStatus.Offline
                    }
            }
        onDispose { hostNetworkEvents.removeListener(callbackRef) }
    }

    DisposableEffect(
        realtimeEvents,
        homeStore,
        realtimeEventMapper,
        settingsViewModel
    ) {
        val callbackRef =
            realtimeEvents.addListener { eventData ->
                if (eventData.optString("action") == AppShellLifecycleAction) {
                    val event = eventData.optString("event")
                    if (
                        eventData.optString("pageName") == SLOCK_APP_SHELL_PAGE &&
                        (event == AppShellLifecycleResume || event == AppShellLifecycleShow) &&
                        latestReadySession != null
                    ) {
                        refreshVisibleAppShellRoute("app_lifecycle_$event")
                    }
                    return@addListener
                }
                if (eventData.optString("action") == AppShellRealtimeAuthRefreshAction) {
                    latestRealtimeAuthRefreshHandler(eventData)
                    return@addListener
                }
                val event = decodeSharedShellRealtimeEvent(eventData, realtimeEventMapper, bridge::log) ?: return@addListener
                val serverId = resolveSharedShellRealtimeServerId(eventData, event, latestReadySession) ?: return@addListener
                val activeSession = latestReadySession
                if (
                    event is ServerMembershipRemoved &&
                    event.serverId.value == activeSession?.currentServerId
                ) {
                    bridge.log("shared_realtime_server_membership_removed serverId=${event.serverId.value}")
                    bridge.realtimeDisconnect("server_membership_removed")
                    latestResetAppShellSessionState()
                    appShellCoroutineScope.launch {
                        authStore.updateSelectedServer(serverId = null, serverSlug = null)
                        authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
                    }
                    return@addListener
                }
                if (
                    event is ServerDeleted &&
                    event.serverId.value == activeSession?.currentServerId
                ) {
                    bridge.log("shared_realtime_server_deleted serverId=${event.serverId.value}")
                    bridge.realtimeDisconnect("server_deleted")
                    latestResetAppShellSessionState()
                    appShellCoroutineScope.launch {
                        authStore.updateSelectedServer(serverId = null, serverSlug = null)
                        authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
                    }
                    return@addListener
                }
                realtimeLastSeq = realtimeLastSeq.updateFrom(event)
                sharedStores.realtimeDomainEventOwner.accept(serverId, event, source = "app_shell")
            }
        onDispose { realtimeEvents.removeListener(callbackRef) }
    }

    val showAppShellBottomTabs =
        appShellBottomTabsVisible(
            selectedTab = selectedTab,
            selectedHomeDestination = selectedHomeDestination,
            selectedSettingsDestination = selectedSettingsDestination
        )
    val appShellBottomTabItems = remember(selectedTab) {
        appShellBottomTabs(selectedTab)
    }
    val appShellBottomContentInset = shellScope.contentBottomInset(showAppShellBottomTabs)

    val mainRouteContent: @Composable () -> Unit = {
        SlockAppShellSurface(
            shellScope = shellScope,
            bottomTabs = appShellBottomTabItems,
            onBottomTabSelected = {
                val nextTab = retainedAppShellSelectedTab(it)
                val visitedBefore = nextTab in retainedAppShellTabs
                bridge.performanceMarker(
                    phase = appShellBottomTabPerformancePhase(nextTab),
                    routeName = appShellBottomTabPerformanceRoute(nextTab),
                    event = "instant",
                    metadata =
                        mapOf(
                            "from" to retainedAppShellSelectedTab(selectedTab),
                            "to" to nextTab,
                            "primaryTab" to isRetainedAppShellTab(nextTab).toString(),
                            "visitedBefore" to visitedBefore.toString()
                        )
                )
                selectedTab = nextTab
                if (isRetainedAppShellTab(nextTab)) {
                    retainedAppShellTabs = retainedAppShellTabs + nextTab
                }
                if (nextTab == "home") {
                    homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
                }
                if (nextTab != "settings") {
                    selectedSettingsDestination = SettingsDestinationHome
                    pendingDiagnosticsFeedback = null
                    pendingDiagnosticsFeedbackReturnDestination = SettingsDestinationDiagnostics
                }
            },
            networkStatus = hostNetworkStatus,
            showBottomTabs = showAppShellBottomTabs
        ) {
                    val tasksTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        PerformanceSceneEffect(bridge, "kuikly_tasks", active = active)
                        SharedTasksScreen(
                            state = tasksState,
                            onIntent = tasksViewModel::dispatch,
                            bottomContentPadding = appShellBottomContentInset,
                            draftProjection = draftProjection,
                            initialListPosition = tasksListPosition,
                            onListPositionSnapshot = { tasksListPosition = it },
                            onOpenTask = { task ->
                                pager.openStandaloneTaskThreadPage(
                                    task = task,
                                    timeContext = threadTimeContext,
                                    alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                                    compactConsecutiveMessages = compactConsecutiveMessages,
                                    threadRowAffordances = threadRowAffordances
                                )
                            }
                        )
                    }
                    val homeTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        val homeListServerId = readyServerId
                        val prefersChineseCommunity =
                            localePreferenceStore.readLocaleSync() == AppLocale.SimplifiedChinese
                        PerformanceSceneEffect(bridge, "kuikly_home", active = active)
                        key("home-list-${homeListServerId.orEmpty()}") {
                            SharedHomeScreen(
                                bridge = bridge,
                                state = homeState,
                                active = active,
                                sessionReady = readyServerId?.isNotBlank() == true,
                                draftPersistSlice = composerDraftPersistSlice,
                                conversationIdentityProvider = sharedStores.conversationIdentityProvider,
                                activityUnreadCount = homeActivityState.totalUnreadCount,
                                activityTotalCount = homeActivityState.totalCount,
                                savedCount = homeSearchSavedState.saved.totalCount,
                                prefersChineseCommunity = prefersChineseCommunity,
                                onIntent = { intent ->
                                    if (intent is SharedHomeIntent.SelectServer) {
                                        readySession
                                            ?.withAppShellSelectedServer(intent.serverId)
                                            ?.let { session ->
                                                authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(session))
                                            }
                                    }
                                    homeShellStore.dispatch(SharedHomeShellIntent.Home(intent))
                                },
                                onNotificationDismiss = { id ->
                                    homeShellStore.dispatch(SharedHomeShellIntent.DismissNotification(id))
                                },
                                onAppUpdateInstall = {
                                    appShellCoroutineScope.launch {
                                        when (val result = settingsCapabilities.installQuiverUpdate()) {
                                            is DataResult.Content -> {
                                                val update = result.data
                                                update.toSharedHomeAppUpdateNotification()?.let { notification ->
                                                    homeShellStore.dispatch(
                                                        SharedHomeShellIntent.Home(
                                                            SharedHomeIntent.UpsertNotification(notification)
                                                        )
                                                    )
                                                }
                                                bridge.toast(update.statusLabel.ifBlank { "Update download started" })
                                                update.detail.takeIf { it.isNotBlank() }?.let(bridge::log)
                                                homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.RefreshNotifications))
                                            }
                                            is DataResult.Empty ->
                                                bridge.toast("App update is unavailable")
                                            is DataResult.Error ->
                                                bridge.toast(result.error.code.ifBlank { "App update failed" })
                                            is DataResult.NoPermission ->
                                                bridge.toast(result.blocker.code.ifBlank { "App update blocked" })
                                        }
                                    }
                                },
                                selectedConversationId = selectedHomeConversationId ?: homeState.selectedConversationId,
                                onSelectedConversationCleared = {
                                    homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
                                },
                                onOpenSearch = {
                                    openHomeSecondaryDestination(HomeDestinationSearch)
                                },
                                onOpenActivity = {
                                    openHomeSecondaryDestination(HomeDestinationActivity)
                                },
                                onOpenSaved = {
                                    openHomeSecondaryDestination(HomeDestinationSaved)
                                },
                                onOpenServerSelection = { pager.openAuthChooseServerPage() },
                                onCreateChannel = { openCreateChannelDialog() },
                                onOpenConversation = { conversation ->
                                    openHomeConversation(conversation.toThreadLaunchConversationIdentity(), "home")
                                },
                                serverMembersStore = sharedStores.serverMembersStore,
                                bottomContentPadding = appShellBottomContentInset,
                                initialListPosition =
                                    appShellLazyListPositionForServer(
                                        currentServerId = homeListServerId,
                                        savedPosition = homeListPosition
                                    ),
                                onListPositionSnapshot = { position ->
                                    homeListPosition =
                                        appShellServerScopedLazyListPosition(homeListServerId, position)
                                }
                            )
                        }
                    }
                    val membersTabContent: @Composable (active: Boolean) -> Unit = {
                        SharedMembersScreen(
                            bridge = bridge,
                            session = readySession,
                            memberRepository = memberRepository,
                            settingsRepository = settingsRepository,
                            createAgentCoordinator = createAgentCoordinator,
                            computersStore = appShellComputersStore,
                            agentDetailStore = sharedStores.agentDetailStore,
                            agentPanelTabOrderStore = sharedStores.agentPanelTabOrderStore,
                            serverMembersStore = sharedStores.serverMembersStore,
                            membersRefreshKey = membersListRefreshKey,
                            initialContent =
                                sharedMembersCachedContentForServer(membersListCachedContent, readyServerId),
                            onContentLoaded = { membersListCachedContent = it },
                            timeContext = threadTimeContext,
                            bottomContentPadding = appShellBottomContentInset,
                            initialListState = membersListState,
                            onListStateSnapshot = { membersListState = it }
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
                            onDestinationChange = {
                                settingsHeaderTapCount = 0
                                selectedSettingsDestination = it
                            },
                            onSettingsHeaderClick = {
                                val nextTapCount = settingsHeaderTapCount + 1
                                if (nextTapCount >= SettingsDeveloperUnlockTapCount) {
                                    settingsHeaderTapCount = 0
                                    selectedSettingsDestination = SettingsDestinationDeveloper
                                } else {
                                    settingsHeaderTapCount = nextTapCount
                                }
                            },
                            onOpenComputer = { computerId ->
                                pager.openSettingsComputerDetailPage(computerId, threadTimeContext)
                            },
                            onOpenRouter = { jumpPage(pager, "router") },
                            onMessageAlignmentSelected = {
                                myMessageAlignment = it
                            },
                            onCompactConsecutiveMessagesSelected = {
                                compactConsecutiveMessages = it
                            },
                            onActivityTaskStatusBadgesSelected = {
                                activityTaskStatusBadgesEnabled = it
                            },
                            onThreadRowAffordanceSelected = { affordance, enabled ->
                                threadRowAffordances =
                                    if (enabled) {
                                        threadRowAffordances + affordance
                                    } else {
                                        threadRowAffordances - affordance
                                    }
                            },
                            onSettingsAction = { action, itemId ->
                                bridge.log("settings_shell_unsupported_action action=$action itemId=${itemId.orEmpty()}")
                            },
                            onDiagnosticsFeedbackPrepared = { draft ->
                                pendingDiagnosticsFeedbackReturnDestination =
                                    selectedSettingsDestination
                                        .takeIf { it != SettingsDestinationDiagnosticsFeedbackChannel }
                                        ?: SettingsDestinationDiagnostics
                                pendingDiagnosticsFeedback = draft
                                selectedSettingsDestination = SettingsDestinationDiagnosticsFeedbackChannel
                                if (homeState.content !is SharedHomeContentState.Content) {
                                    homeShellStore.dispatch(SharedHomeShellIntent.Start)
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
                                homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Refresh))
                            },
                            onDiagnosticsFeedbackChannelSelected = { conversation ->
                                pendingDiagnosticsFeedback?.let { draft ->
                                    openDiagnosticsFeedbackChannel(
                                        draft = draft,
                                        conversation = conversation,
                                        source = "diagnostics_channel_page",
                                        pushCurrent = true
                                    )
                                    homeShellStore.dispatch(
                                        SharedHomeShellIntent.Home(
                                            SharedHomeIntent.SelectConversation(conversation.threadId.value)
                                        )
                                    )
                                }
                            },
                            bottomContentPadding = appShellBottomContentInset,
                            timeContext = threadTimeContext,
                            onSessionCleared = { clearedSession ->
                                authSessionEvents.postSessionCleared(
                                    reason = "local",
                                    previousSessionFingerprint = clearedSession.authSessionClearFingerprint()
                                )
                            },
                            onLogout = {
                                authGateMessage = null
                                bridge.realtimeDisconnect("logout")
                                authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
                                resetAppShellSessionState()
                            }
                        )
                    }
                    val currentChildTab = retainedAppShellSelectedTab(selectedTab)
                    val childTabRenderer: AppShellChildTabRenderer = { tab, active ->
                        val homeSummary = sharedHomeContentRenderSummary(homeState.content)
                        LaunchedEffect(tab, active, currentChildTab, readyServerId, homeSummary, homeState.homeRealtimeDirty) {
                            bridge.log(
                                "app_shell_child_tab_dispatch tab=$tab active=$active selectedTab=$currentChildTab " +
                                    "readyServerId=${readyServerId.orEmpty()} homeState=$homeSummary " +
                                    "homeRealtimeDirty=${homeState.homeRealtimeDirty}"
                            )
                        }
                        when (tab) {
                            "tasks" -> tasksTabContent(active)
                            "members" -> membersTabContent(active)
                            "settings" -> settingsTabContent(active)
                            else -> homeTabContent(active)
                        }
                    }
                    val childTabRegistration =
                        remember(childTabShellId) {
                            val entry = AppShellChildTabEntry(
                                debugId = AppShellChildTabRegistry.createEntryId(),
                                initialSelectedTab = currentChildTab,
                                initialRenderer = childTabRenderer
                            )
                            val replaced = AppShellChildTabRegistry.register(childTabShellId, entry)
                            AppShellChildTabRegistration(entry = entry, replacedEntryId = replaced?.debugId ?: 0)
                        }
                    val childTabEntry = childTabRegistration.entry
                    SideEffect {
                        childTabEntry.selectedTab = currentChildTab
                        childTabEntry.renderer = childTabRenderer
                    }
                    LaunchedEffect(childTabShellId, childTabEntry.debugId) {
                        bridge.log(
                            "app_shell_child_tab_registry_register shellId=$childTabShellId " +
                                "entryId=${childTabEntry.debugId} replacedEntryId=${childTabRegistration.replacedEntryId} " +
                                "selectedTab=${childTabEntry.selectedTab}"
                        )
                    }
                    DisposableEffect(childTabShellId, childTabEntry) {
                        onDispose {
                            val removed = AppShellChildTabRegistry.unregister(childTabShellId, childTabEntry)
                            bridge.log(
                                "app_shell_child_tab_registry_unregister shellId=$childTabShellId " +
                                    "entryId=${childTabEntry.debugId} removed=$removed"
                            )
                        }
                    }
                    AppShellTabs(
                        selectedTab = selectedTab,
                        childHostShellId = childTabShellId
                    ) { tab, active ->
                        when (tab) {
                            "settings" -> settingsTabContent(active)
                            else -> childTabRenderer(tab, active)
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

    Box(modifier = Modifier.fillMaxSize()) {
        key("route-main") { mainRouteContent() }
    }
}

internal fun appShellAuthGateViewModelKey(pagerId: String, instanceId: String = ""): String =
    "shared-auth-gate-${instanceId.ifBlank { pagerId }.ifBlank { "default" }}"

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
    onDestinationChange: (String) -> Unit,
    onSettingsHeaderClick: () -> Unit,
    onOpenComputer: (String) -> Unit,
    onOpenRouter: () -> Unit,
    onMessageAlignmentSelected: (MyMessageAlignmentPreference) -> Unit,
    onCompactConsecutiveMessagesSelected: (Boolean) -> Unit,
    onActivityTaskStatusBadgesSelected: (Boolean) -> Unit,
    onThreadRowAffordanceSelected: (ThreadRowAffordancePreference, Boolean) -> Unit,
    onSettingsAction: (String, String?) -> Unit,
    onDiagnosticsFeedbackPrepared: (SettingsDiagnosticsFeedbackDraft) -> Unit,
    diagnosticsFeedbackDraft: SettingsDiagnosticsFeedbackDraft?,
    diagnosticsFeedbackHomeState: SharedHomeState,
    onDiagnosticsFeedbackBack: () -> Unit,
    onDiagnosticsFeedbackRefresh: () -> Unit,
    onDiagnosticsFeedbackChannelSelected: (ConversationSummary) -> Unit,
    bottomContentPadding: Dp,
    timeContext: SlockTimeContext?,
    onSessionCleared: (StoredAuthSession) -> Unit = {},
    onLogout: () -> Unit
) {
    LaunchedEffect(selectedDestination) {
        when (selectedDestination) {
            SettingsDestinationApiEnvironment ->
                onSettingsIntent(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Developer))
            else ->
                selectedDestination.toSharedSettingsDestination()?.let { destination ->
                    onSettingsIntent(SharedSettingsIntent.OpenDestination(destination))
                }
        }
    }

    LaunchedEffect(settingsState.effect) {
        val effect = settingsState.effect ?: return@LaunchedEffect
        when (effect) {
            SharedSettingsEffect.Logout -> {
                val clearedSession = session
                runCatchingPreservingCancellation { authDataSource.logout() }
                if (clearedSession != null) {
                    onSessionCleared(clearedSession)
                }
                onLogout()
            }
            is SharedSettingsEffect.OpenUrl -> {
                settingsCapabilities.openUrl(effect.url)
            }
            is SharedSettingsEffect.CopyText -> {
                settingsCapabilities.bridgeCopyToClipboard(effect.text)
            }
            is SharedSettingsEffect.OpenComputer -> {
                onOpenComputer(effect.machineId)
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
            languageRegionDisplayDraft = snapshot.translationDisplay.selectedKey
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
            onHeaderClick = onSettingsHeaderClick,
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
        subtitle = "",
        iconName = settingsDestinationIcon(selectedDestination),
        onBack = {
            when (selectedDestination) {
                SettingsDestinationDiagnosticsFeedbackChannel -> onDiagnosticsFeedbackBack()
                SettingsDestinationApiEnvironment -> onDestinationChange(SettingsDestinationDeveloper)
                else -> onDestinationChange(SettingsDestinationHome)
            }
        },
        trailingContent =
            if (selectedDestination == SettingsDestinationComputers && settingsState.computers.canShowAddComputerHeaderAction()) {
                {
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.Plus,
                        onClick = { onSettingsIntent(SharedSettingsIntent.ShowAddComputerDialog) },
                        size = 36.dp,
                        shadowOffset = 3.dp
                    )
                }
            } else {
                null
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
            SettingsDestinationComputers -> {
                SettingsComputersContent(
                    settingsState.computers.toComputersPayload(
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Computers),
                        timeContext = timeContext
                    ),
                    bottomContentPadding = bottomContentPadding,
                ) { action, itemId ->
                    when (action) {
                        "openComputer" -> itemId?.takeIf { it.isNotBlank() }?.let(onOpenComputer)
                        "addComputer" -> onSettingsIntent(SharedSettingsIntent.ShowAddComputerDialog)
                        else -> onSettingsAction(action, itemId)
                    }
                }
                settingsState.addComputerDialog?.let { dialogState ->
                    AddComputerDialog(state = dialogState, onIntent = onSettingsIntent)
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
                SettingsBillingWebViewContent(
                    session = session,
                    webBaseUrl = settingsState.developer.configuredWebBaseUrlOrNull(),
                    bottomContentPadding = bottomContentPadding
                )
            SettingsDestinationLanguageRegion ->
                SettingsLanguageRegionContent(
                    state = settingsState.languageRegion,
                    actionState = settingsState.actionState,
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
                        val request =
                            if (selected == null || selected == "other") {
                                SettingsLanguageRegionPreferenceUpdateRequest(
                                    clearPreferredLanguage = true,
                                    preferredTranslationDisplay = languageRegionDisplayDraft.takeIf { it.isNotBlank() }
                                )
                            } else {
                                SettingsLanguageRegionPreferenceUpdateRequest(
                                    preferredLanguage = selected,
                                    preferredTranslationDisplay = languageRegionDisplayDraft.takeIf { it.isNotBlank() }
                                )
                            }
                        onSettingsIntent(
                            SharedSettingsIntent.UpdateLanguageRegionPreference(request)
                        )
                        // Switch app locale when Chinese is selected.
                        if (selected == "zh") {
                            onSettingsIntent(SharedSettingsIntent.SetLocale("zh-Hans"))
                        } else if (selected == "en") {
                            onSettingsIntent(SharedSettingsIntent.SetLocale("en"))
                        }
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
                    profileName = serverProfileName,
                    onProfileNameChange = { serverProfileName = it },
                    serverSnapshot = currentServerSnapshot,
                    serverRole = session?.currentServerSummary()?.role.orEmpty(),
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                ) { action, itemId ->
                    when (action) {
                        SERVER_ACTION_PROFILE_SAVE ->
                            itemId?.trim()?.takeIf { it.isNotBlank() }?.let {
                                onSettingsIntent(SharedSettingsIntent.UpdateServerProfile(SettingsServerProfileUpdateRequest(name = it)))
                            }
                        "openAdministration" ->
                            onDestinationChange(SettingsDestinationAdministration)
                        else -> onSettingsAction(action, itemId)
                    }
                }
            SettingsDestinationAdministration ->
                SharedSettingsServerContent(
                    payload = settingsState.server.toAdministrationPayload(
                        session = session,
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Administration),
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
                        SERVER_ACTION_SYSTEM_CHANNEL_HIDE -> {
                            val allChannelId = settingsState.server.currentContent()?.systemChannels?.allChannelId
                            if (!allChannelId.isNullOrBlank()) {
                                val currentHidden = settingsState.server.currentContent()?.systemChannels?.allChannelHidden == true
                                onSettingsIntent(SharedSettingsIntent.UpdateSystemChannelVisibility(allChannelId, hide = !currentHidden))
                            }
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
                    bottomContentPadding = bottomContentPadding,
                    notificationPreferenceSnapshot = notificationPreferenceSnapshot,
                    preferenceBusy = settingsState.actionState?.action == MOBILE_ACTION_NOTIFICATION_PREFERENCE && settingsState.actionState.inProgress,
                    foregroundKeepAliveBusy = settingsState.actionState?.action == MOBILE_ACTION_FOREGROUND_KEEP_ALIVE && settingsState.actionState.inProgress,
                    onNotificationPreferenceChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetNotificationPreference(enabled))
                        if (enabled) {
                            onSettingsIntent(SharedSettingsIntent.RequestNotificationPermission)
                        }
                    },
                    onForegroundKeepAlivePreferenceChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetForegroundKeepAlivePreference(enabled))
                        if (enabled) {
                            onSettingsIntent(SharedSettingsIntent.RequestNotificationPermission)
                        }
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
                SettingsMobileContent(
                    state = settingsState.mobile,
                    actionState = settingsState.actionState,
                    refreshing = settingsState.isRefreshing(SharedSettingsDestination.Mobile),
                    bottomContentPadding = bottomContentPadding,
                    onAlignmentChange = { itemId ->
                        onSettingsIntent(SharedSettingsIntent.SetMessageAlignment(itemId ?: "left"))
                        onMessageAlignmentSelected(MyMessageAlignmentPreference.fromStorageKey(itemId))
                    },
                    onCompactConsecutiveMessagesChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetCompactConsecutiveMessages(enabled))
                        onCompactConsecutiveMessagesSelected(enabled)
                    },
                    onImageAnnotationChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetImageAnnotationEnabled(enabled))
                    },
                    onActivityTaskStatusBadgesChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetActivityTaskStatusBadges(enabled))
                        onActivityTaskStatusBadgesSelected(enabled)
                    },
                    onThreadRowAffordanceChange = { affordance, enabled ->
                        onSettingsIntent(
                            SharedSettingsIntent.SetThreadRowAffordance(
                                affordance.storageKey,
                                enabled
                            )
                        )
                        onThreadRowAffordanceSelected(affordance, enabled)
                    }
                )
            SettingsDestinationAbout ->
                SharedSettingsAboutContent(
                    state = settingsState.about,
                    fallback = appShellAboutFallback(session),
                    updateCheck = settingsState.developer.currentContent()?.updateCheck,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onCopyBuild = { summary ->
                        settingsCapabilities.bridgeCopyToClipboard(summary)
                    },
                    webUrl = sharedSettingsResolvedWebBaseUrl(settingsState.developer.configuredWebBaseUrlOrNull()),
                    onOpenSlock = { url -> settingsCapabilities.openUrl(url) },
                    onCheckQuiverUpdate = {
                        onSettingsIntent(SharedSettingsIntent.CheckQuiverUpdate)
                    },
                    onSendFeedback = { onSettingsIntent(SharedSettingsIntent.SendFeedback) }
                )
            SettingsDestinationDeveloper ->
                SharedSettingsDeveloperContent(
                    state = settingsState.developer,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onOpenApiEnvironment = { onDestinationChange(SettingsDestinationApiEnvironment) },
                    onSharedSocketIoTransportChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetSharedSocketIoTransport(enabled))
                    },
                    onOpenDiagnostics = { onDestinationChange(SettingsDestinationDiagnostics) },
                    onOpenRouter = onOpenRouter
                )
            SettingsDestinationApiEnvironment ->
                SharedSettingsApiEnvironmentContent(
                    state = settingsState.developer,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onSaveApiEnvironment = { request ->
                        onSettingsIntent(SharedSettingsIntent.SaveApiEnvironment(request))
                    }
                )
            SettingsDestinationDiagnostics ->
                SharedSettingsDiagnosticsContent(
                    state = settingsState.diagnostics,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onCopyLogs = { onSettingsIntent(SharedSettingsIntent.CopyDiagnostics) },
                    onShareLogs = { onSettingsIntent(SharedSettingsIntent.ShareDiagnostics) },
                    onSetProfilerEnabled = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetRecompositionProfilerEnabled(enabled))
                    },
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
        payload.sections.forEach { section ->
            item { SharedSettingsSectionCard(section, onAction) }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SharedSettingsServerContent(
    payload: KuiklySharedSettingsPayload,
    profileName: String,
    onProfileNameChange: (String) -> Unit,
    serverSnapshot: SettingsServerSnapshot?,
    serverRole: String,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    inviteEmail: String = "",
    onInviteEmailChange: (String) -> Unit = {},
    joinLinkMaxUses: String = "",
    onJoinLinkMaxUsesChange: (String) -> Unit = {},
    joinLinkExpiresAt: String = "",
    onJoinLinkExpiresAtChange: (String) -> Unit = {},
    agreementEnabled: Boolean = false,
    onAgreementEnabledChange: (Boolean) -> Unit = {},
    agreementTitle: String = "",
    onAgreementTitleChange: (String) -> Unit = {},
    agreementBody: String = "",
    onAgreementBodyChange: (String) -> Unit = {},
    hideHumansFromMembers: Boolean = false,
    onHideHumansFromMembersChange: (Boolean) -> Unit = {},
    onboardingAgentId: String = "",
    onOnboardingAgentIdChange: (String) -> Unit = {},
    onboardingGreetingEnabled: Boolean = true,
    onOnboardingGreetingEnabledChange: (Boolean) -> Unit = {},
    timeContext: SlockTimeContext? = null,
    expectedServerSlug: String = "",
    deleteConfirmationName: String = "",
    onDeleteConfirmationNameChange: (String) -> Unit = {},
    onCreateJoinLink: (SettingsServerJoinLinkCreateRequest) -> Unit = {},
    onAction: (String, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsFormSectionCard(iconName = SlockLucideIconName.LoaderCircle, title = "Loading") {
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
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.LoaderCircle, "Loading", "Loading language and region settings.", null, onRetry)
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
    val displayBusy = actionState?.action == LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY && actionState.inProgress
    val busy = languageBusy || displayBusy
    val currentDisplay = snapshot.translationDisplay.selectedKey
    val dirty = languageDraft != snapshot.browserLanguage.selectedKey || displayDraft != currentDisplay
    // Auto-save on draft change with debounce.
    LaunchedEffect(languageDraft, displayDraft) {
        if (!dirty) return@LaunchedEffect
        delay(400)
        onSubmit()
    }
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
        if (actionState?.let { it.action in setOf(LANGUAGE_REGION_ACTION_LANGUAGE, LANGUAGE_REGION_ACTION_AUTO_TRANSLATION, LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY) && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
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
    // Auto-save on draft change with debounce.
    LaunchedEffect(timezoneDraft, timeFormatDraft) {
        if (!dirty) return@LaunchedEffect
        delay(400)
        onSubmit()
    }
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
    fontSizeDraft: String,
    bottomContentPadding: Dp,
    onFontSizeDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.LoaderCircle, "Loading", "Loading appearance settings.", null, onRetry)
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
    // Auto-save immediately on font selection change.
    LaunchedEffect(fontSizeDraft) {
        if (!dirty) return@LaunchedEffect
        onSubmit()
    }
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
private fun SettingsMobileContent(
    state: ScreenLoadState<SettingsMobileSnapshot>,
    actionState: SharedSettingsActionState?,
    refreshing: Boolean,
    bottomContentPadding: Dp,
    onAlignmentChange: (String?) -> Unit,
    onCompactConsecutiveMessagesChange: (Boolean) -> Unit,
    onImageAnnotationChange: (Boolean) -> Unit,
    onActivityTaskStatusBadgesChange: (Boolean) -> Unit,
    onThreadRowAffordanceChange: (ThreadRowAffordancePreference, Boolean) -> Unit
) {
    val snapshot =
        when (state) {
            is ScreenLoadState.Content -> state.data
            else -> null
        }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (snapshot != null) {
            item {
                SettingsMessageAlignmentCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onAlignmentChange = onAlignmentChange
                )
            }
            item {
                SettingsCompactMessagesCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onCompactConsecutiveMessagesChange = onCompactConsecutiveMessagesChange
                )
            }
            item {
                SettingsActivityTaskStatusBadgesCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onActivityTaskStatusBadgesChange = onActivityTaskStatusBadgesChange
                )
            }
            item {
                SettingsThreadRowAffordancesCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onThreadRowAffordanceChange = onThreadRowAffordanceChange
                )
            }
            item {
                SettingsImageAnnotationCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onImageAnnotationChange = onImageAnnotationChange
                )
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsMessageAlignmentCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onAlignmentChange: (String?) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_MESSAGE_ALIGNMENT && actionState.inProgress
    val selectedKey = snapshot.messageAlignmentPreference.selectedKey
    SettingsFormSectionCard(iconName = SlockLucideIconName.Columns3, title = "My message alignment") {
        SettingsFormDetailText(snapshot.messageAlignmentPreference.detail)
        SegmentedControl(
            selectedValue = selectedKey.ifBlank { "left" },
            options =
                listOf(
                    SegmentedControlOption("left", "Left"),
                    SegmentedControlOption("right", "Right")
                ),
            onValueSelected = onAlignmentChange,
            enabled = snapshot.messageAlignmentPreference.available && !busy
        )
        if (actionState?.action == MOBILE_ACTION_MESSAGE_ALIGNMENT && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
    }
}

@Composable
private fun SettingsCompactMessagesCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onCompactConsecutiveMessagesChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_COMPACT_MESSAGES && actionState.inProgress
    val preference = snapshot.compactConsecutiveMessages
    SettingsFormSectionCard(iconName = SlockLucideIconName.MessageSquare, title = "Compact consecutive messages") {
        SettingsFormDetailText(preference.detail)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onCompactConsecutiveMessagesChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preference.statusLabel,
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (preference.enabled) "Repeated avatars hidden" else "Show every message avatar",
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SlockSwitchButton(
                checked = preference.enabled,
                enabled = preference.available && !busy
            )
        }
        if (actionState?.action == MOBILE_ACTION_COMPACT_MESSAGES && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
    }
}

@Composable
private fun SettingsActivityTaskStatusBadgesCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onActivityTaskStatusBadgesChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES && actionState.inProgress
    val preference = snapshot.activityTaskStatusBadges
    SettingsFormSectionCard(iconName = SlockLucideIconName.ClipboardCheck, title = "Activity task status badges") {
        SettingsFormDetailText(preference.detail)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onActivityTaskStatusBadgesChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preference.statusLabel,
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (preference.enabled) "Show task status chips in Activity rows" else "Keep Activity rows visually quiet",
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SlockSwitchButton(
                checked = preference.enabled,
                enabled = preference.available && !busy
            )
        }
        if (actionState?.action == MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
    }
}

@Composable
private fun SettingsThreadRowAffordancesCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onThreadRowAffordanceChange: (ThreadRowAffordancePreference, Boolean) -> Unit
) {
    val preference = snapshot.threadRowAffordances
    SettingsFormSectionCard(iconName = SlockLucideIconName.SlidersHorizontal, title = "Message row actions") {
        SettingsFormDetailText(preference.detail)
        preference.options.forEach { option ->
            val affordance = ThreadRowAffordancePreference.fromStorageKey(option.key) ?: return@forEach
            val enabled = option.key in preference.selectedKeys
            val busy =
                actionState?.action == MOBILE_ACTION_THREAD_ROW_AFFORDANCES &&
                    actionState.itemId == option.key &&
                    actionState.inProgress
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = preference.available && !busy) {
                            onThreadRowAffordanceChange(affordance, !enabled)
                        }
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = option.label,
                        style = SlockType.Button.copy(color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = affordance.settingsDetail(enabled),
                        style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SlockSwitchButton(
                    checked = enabled,
                    enabled = preference.available && !busy
                )
            }
        }
        if (actionState?.action == MOBILE_ACTION_THREAD_ROW_AFFORDANCES && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
    }
}

private fun ThreadRowAffordancePreference.settingsDetail(enabled: Boolean): String =
    when (this) {
        ThreadRowAffordancePreference.Reactions ->
            if (enabled) "Show reaction actions in message menus" else "Hide reaction actions"
        ThreadRowAffordancePreference.Saved ->
            if (enabled) "Show the inline save action on message rows" else "Keep save in the action menu only"
    }

@Composable
private fun SettingsImageAnnotationCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onImageAnnotationChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_IMAGE_ANNOTATION && actionState.inProgress
    val preference = snapshot.imageAnnotation
    SettingsFormSectionCard(iconName = SlockLucideIconName.Image, title = "Support image annotation") {
        SettingsFormDetailText(preference.detail)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onImageAnnotationChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preference.statusLabel,
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (preference.enabled) "Composer images open the annotation transfer page" else "Composer images upload directly",
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SlockSwitchButton(
                checked = preference.enabled,
                enabled = preference.available && !busy
            )
        }
        if (actionState?.action == MOBILE_ACTION_IMAGE_ANNOTATION && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
    }
}

@Composable
private fun SettingsNotificationsContent(
    state: ScreenLoadState<SettingsMobileSnapshot>,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp,
    notificationPreferenceSnapshot: NotificationPreferenceSnapshot,
    preferenceBusy: Boolean,
    foregroundKeepAliveBusy: Boolean,
    onNotificationPreferenceChange: (Boolean) -> Unit,
    onForegroundKeepAlivePreferenceChange: (Boolean) -> Unit,
    onServerMuteChange: (Boolean) -> Unit,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onTest: () -> Unit,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsLoadStateCard(SlockLucideIconName.LoaderCircle, "Loading", "Loading notification settings.", null, onRetry)
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
                item {
                    SettingsPushStatusCard(
                        snapshot = snapshot,
                        actionState = actionState,
                        notificationPreferenceSnapshot = notificationPreferenceSnapshot,
                        preferenceBusy = preferenceBusy,
                        foregroundKeepAliveBusy = foregroundKeepAliveBusy,
                        onNotificationPreferenceChange = onNotificationPreferenceChange,
                        onForegroundKeepAlivePreferenceChange = onForegroundKeepAlivePreferenceChange,
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
        SettingsFormDetailText("Install prompts are managed by web PWA APIs outside native builds. Native Android and OHOS builds use their platform app install flow.")
        SettingsFormEmptyText("No install prompt is available inside this native Settings surface.")
    }
}

@Composable
private fun SettingsPushStatusCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    notificationPreferenceSnapshot: NotificationPreferenceSnapshot,
    preferenceBusy: Boolean,
    foregroundKeepAliveBusy: Boolean,
    onNotificationPreferenceChange: (Boolean) -> Unit,
    onForegroundKeepAlivePreferenceChange: (Boolean) -> Unit,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onTest: () -> Unit
) {
    SettingsFormSectionCard(iconName = SlockLucideIconName.BellRing, title = "Show system notifications") {
        SettingsFormDetailText(
            "Show a system tray notification for each new unread message when the app is in the background. Server-side push is not enabled in this build."
        )
        SegmentedControl(
            selectedValue = if (notificationPreferenceSnapshot.enabled) "enabled" else "disabled",
            options =
                listOf(
                    SegmentedControlOption("enabled", "Enabled", SlockLucideIconName.BellRing),
                    SegmentedControlOption("disabled", "Disabled", SlockLucideIconName.Bell)
                ),
            onValueSelected = { onNotificationPreferenceChange(it == "enabled") },
            enabled = !preferenceBusy
        )
        SettingsFormDivider()
        SettingsFormDetailText(
            "Keep realtime connected while the app is backgrounded without posting a persistent service notification. The OS may still limit long background sessions."
        )
        SegmentedControl(
            selectedValue = if (notificationPreferenceSnapshot.foregroundKeepAliveEnabled) "enabled" else "disabled",
            options =
                listOf(
                    SegmentedControlOption("enabled", "Keep alive", SlockLucideIconName.Activity),
                    SegmentedControlOption("disabled", "Normal", SlockLucideIconName.Bell)
                ),
            onValueSelected = { onForegroundKeepAlivePreferenceChange(it == "enabled") },
            enabled = !foregroundKeepAliveBusy
        )
        SettingsFormDivider()
        SlockBrutalActionButton(
            // task #90: Don't use actionState.statusFor here — that
            // helper would render "Saved" on success completion of the
            // openNotificationSettings action, which is semantically wrong
            // for a "open system settings" button. "Manage notifications"
            // is the persistent label; in-progress is signaled via
            // `enabled` below.
            label = "Manage notifications",
            onClick = onSettings,
            enabled = snapshot.notificationSettings.available && actionState?.inProgress != true,
            leadingIcon = SlockLucideIconName.Settings,
            backgroundColor = SlockColor.SurfaceWhite
        )
        if (actionState?.let {
                it.action in setOf(MOBILE_ACTION_NOTIFICATION_PREFERENCE, MOBILE_ACTION_FOREGROUND_KEEP_ALIVE, MOBILE_ACTION_SETTINGS) &&
                    it.errorCode != null
            } == true
        ) {
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
    val busy = actionState?.action == MOBILE_ACTION_SERVER_MUTE && actionState.inProgress
    SettingsFormSectionCard(iconName = SlockLucideIconName.VolumeX, title = "Workspace mute") {
        SettingsFormDetailText(snapshot.muteServer.detail)
        SegmentedControl(
            selectedValue = if (snapshot.muteServer.enabled) "muted" else "unmuted",
            options =
                listOf(
                    SegmentedControlOption("unmuted", "Unmuted", SlockLucideIconName.BellRing),
                    SegmentedControlOption("muted", "Muted", SlockLucideIconName.VolumeX)
                ),
            onValueSelected = { onServerMuteChange(it == "muted") },
            enabled = snapshot.muteServer.available && !busy
        )
        if (actionState?.action == MOBILE_ACTION_SERVER_MUTE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
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
        SlockBrutalReadOnlyField(label = "Client ID", value = secret.clientId)
        SlockBrutalReadOnlyField(
            label = "Client Secret",
            value = secret.clientSecret,
            maxLines = 2,
            textStyle = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
            textColor = SlockColor.TextBlack
        )
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
        SlockBrutalReadOnlyField(label = label, value = it)
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
        SlockRaftUiSelectField(
            options = categoryOptions,
            selectedId = resolvedCategory,
            onSelect = { onCategoryChange(it.id) },
            title = "Category",
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
    profile: build.raft.app.data.SettingsServerProfileSnapshot?,
    draftName: String,
    fallbackName: String,
    fallbackSlug: String,
    role: String,
    actionState: build.raft.app.data.SharedSettingsActionState?,
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
        SlockBrutalReadOnlyField(
            label = "Slug",
            value = currentSlug.ifBlank { "Not provided" }
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
                backgroundColor = SlockColor.AccentPink,
                disabledBackgroundColor = SlockColor.AccentPink.copy(alpha = 0.5f),
                minWidth = 0.dp,
                minHeight = 34.dp,
                shadowOffset = 3.dp,
                textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextBlack)
            )
        }
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
        SlockLucideIcon(
            iconName,
            modifier = Modifier.size(16.dp),
            color = "#FF000000",
            spinning = iconName == SlockLucideIconName.Loader || iconName == SlockLucideIconName.LoaderCircle
        )
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
    onConfirmationSlugChange: (String) -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit
) {
    val normalizedRole = role.lowercase()
    val canLeave = normalizedRole == "admin" || normalizedRole == "member"
    val canDelete = normalizedRole == "owner" || dangerSection?.state?.name == "Available"
    var showDeleteModal by remember { mutableStateOf(false) }
    var modalConfirmationSlug by remember { mutableStateOf("") }
    if (!canLeave && !canDelete) {
        return
    }
    val leaveConfirmed = actionState.isServerConfirmation(SERVER_ACTION_LEAVE)
    val deleteBusy = actionState?.action == SERVER_ACTION_DELETE && actionState.inProgress
    val leaveBusy = actionState?.action == SERVER_ACTION_LEAVE && actionState.inProgress
    val trimmedModalSlug = modalConfirmationSlug.trim()
    val deleteEnabled = canDelete && !deleteBusy && expectedServerSlug.isNotBlank() && trimmedModalSlug == expectedServerSlug
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
                buttonLabel = "Delete Server",
                buttonIcon = SlockLucideIconName.Trash2,
                buttonColor = SlockColor.AccentRed,
                enabled = !deleteBusy,
                onClick = { showDeleteModal = true }
            )
        }
    }
    if (showDeleteModal) {
        SettingsServerDeleteConfirmationDialog(
            serverName = serverName,
            expectedServerSlug = expectedServerSlug,
            confirmationSlug = modalConfirmationSlug,
            deleteBusy = deleteBusy,
            deleteEnabled = deleteEnabled,
            errorCode = actionState?.errorCode.takeIf { actionState?.action == SERVER_ACTION_DELETE },
            onDismiss = {
                if (!deleteBusy) {
                    showDeleteModal = false
                    modalConfirmationSlug = ""
                }
            },
            onConfirmationSlugChange = { modalConfirmationSlug = it },
            onCancel = {
                if (!deleteBusy) {
                    showDeleteModal = false
                    modalConfirmationSlug = ""
                }
            },
            onDelete = {
                onConfirmationSlugChange(modalConfirmationSlug)
                onDelete()
                if (actionState?.action != SERVER_ACTION_DELETE || actionState.errorCode == null) {
                    showDeleteModal = false
                    modalConfirmationSlug = ""
                }
            }
        )
    }
}

@Composable
private fun SettingsServerDeleteConfirmationDialog(
    serverName: String,
    expectedServerSlug: String,
    confirmationSlug: String,
    deleteBusy: Boolean,
    deleteEnabled: Boolean,
    errorCode: String?,
    onDismiss: () -> Unit,
    onConfirmationSlugChange: (String) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val resolvedName = serverName.ifBlank { "this server" }
    val resolvedSlug = expectedServerSlug.ifBlank { "server-slug" }
    Modal(onWillDismiss = { if (!deleteBusy) onDismiss() }) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SlockColor.ShadowBlack.copy(alpha = 0.58f))
                    .clickable(enabled = !deleteBusy, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 18.dp)
                        .offset(x = (-10).dp, y = (-36).dp)
                        .widthIn(max = 360.dp)
                        .clickable(enabled = false, onClick = {})
            ) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .offset(x = 4.dp, y = 4.dp)
                            .background(SlockColor.ShadowBlack)
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "DELETE SERVER",
                            style =
                                SlockType.PageTitle.copy(
                                    fontSize = 18.sp,
                                    lineHeight = 22.sp,
                                    color = SlockColor.TextBlack
                                ),
                            color = SlockColor.TextBlack,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        SlockBrutalIconButton(
                            enabled = !deleteBusy,
                            iconName = SlockLucideIconName.X,
                            onClick = onDismiss,
                            size = 34.dp,
                            shadowOffset = 3.dp
                        )
                    }
                    SettingsServerDeleteWarningBanner(serverName = resolvedName)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Type ",
                            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
                            color = SlockColor.TextMutedGray
                        )
                        Text(
                            resolvedSlug,
                            style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            " to confirm:",
                            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
                            color = SlockColor.TextMutedGray
                        )
                    }
                    SettingsServerSlugInput(
                        value = confirmationSlug,
                        placeholder = resolvedSlug,
                        enabled = !deleteBusy,
                        onValueChange = onConfirmationSlugChange
                    )
                    errorCode?.takeIf { it.isNotBlank() }?.let {
                        SettingsServerDeleteWarningBanner(serverName = null, text = it)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SlockBrutalActionButton(
                            label = "Cancel",
                            onClick = onCancel,
                            enabled = !deleteBusy,
                            modifier = Modifier.width(72.dp),
                            backgroundColor = SlockColor.SurfaceWhite,
                            minWidth = 0.dp,
                            minHeight = 34.dp,
                            shadowOffset = 3.dp,
                            textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp)
                        )
                        SlockBrutalActionButton(
                            label = if (deleteBusy) "Deleting..." else "Delete Server",
                            onClick = onDelete,
                            enabled = deleteEnabled,
                            modifier = Modifier.width(112.dp),
                            backgroundColor = SlockColor.AccentRed,
                            disabledBackgroundColor = Color(0xFFFDBDB7),
                            minWidth = 0.dp,
                            minHeight = 34.dp,
                            shadowOffset = 3.dp,
                            textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsServerDeleteWarningBanner(
    serverName: String?,
    text: String? = null
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.AccentRed.copy(alpha = 0.14f))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        SlockLucideIcon(
            SlockLucideIconName.TriangleAlert,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
            color = "#FF000000"
        )
        Text(
            text ?: "This will permanently delete ${serverName ?: "this server"} and all its data (agents, channels, messages). This cannot be undone.",
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SettingsServerSlugInput(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    val shadowOffset = 3.dp
    Box(modifier = Modifier.fillMaxWidth().padding(end = shadowOffset, bottom = shadowOffset)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .background(SlockColor.ShadowBlack)
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier =
                    Modifier
                        .height(42.dp)
                        .background(SlockColor.SurfaceCream)
                        .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "/",
                    style = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = SlockColor.TextBlack
                )
            }
            TextField(
                value = value,
                onValueChange = { if (enabled) onValueChange(it) },
                placeholder = placeholder,
                placeholderColor = SlockColor.TextMutedGray,
                maxLines = 1,
                textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
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
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SettingsServerSectionHeader(iconName = iconName, title = title)
        SlockBrutalCard(
            contentPadding = 14.dp,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
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
    profile: build.raft.app.data.SettingsServerProfileSnapshot?,
    section: SettingsServerSectionSnapshot?,
    hideHumansFromMembers: Boolean,
    actionState: build.raft.app.data.SharedSettingsActionState?,
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
            row.toggleValue?.let { enabled ->
                SlockSwitchButton(checked = enabled, enabled = row.enabled)
            }
        }
    }
}

@Composable
private fun SharedSettingsAboutContent(
    state: ScreenLoadState<SharedSettingsAboutSnapshot>,
    fallback: SharedSettingsAboutSnapshot,
    updateCheck: SharedSettingsUpdateCheckSnapshot?,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onCopyBuild: (String) -> Unit,
    webUrl: String = SharedSettingsDefaultWebBaseUrl,
    onOpenSlock: (String) -> Unit,
    onCheckQuiverUpdate: () -> Unit,
    onSendFeedback: () -> Unit
) {
    val snapshot =
        when (state) {
            is ScreenLoadState.Content -> state.data
            else -> fallback
        }
    val payload = snapshot.toAboutPayload(webUrl, updateCheck, actionState)
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding) { action, value ->
        when (action) {
            "copyBuild" -> onCopyBuild(value.orEmpty().ifBlank { snapshot.buildSummary() })
            "openSlock" -> onOpenSlock(value.orEmpty().ifBlank { webUrl })
            DEVELOPER_ACTION_QUIVER_UPDATE_CHECK -> onCheckQuiverUpdate()
            "feedback" -> onSendFeedback()
        }
    }
}

@Composable
internal fun SharedSettingsDeveloperContent(
    state: ScreenLoadState<SharedSettingsDeveloperSnapshot>,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onOpenApiEnvironment: () -> Unit,
    onSharedSocketIoTransportChange: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenRouter: () -> Unit
) {
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data
    val payload = snapshot.toDeveloperPayload(state, actionState)
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding) { action, value ->
        when (action) {
            DEVELOPER_ACTION_API_ENVIRONMENT -> onOpenApiEnvironment()
            DEVELOPER_ACTION_SHARED_SOCKET_IO_TRANSPORT -> onSharedSocketIoTransportChange(value?.toBooleanStrictOrNull() ?: true)
            "diagnostics" -> onOpenDiagnostics()
            "router" -> onOpenRouter()
        }
    }
}

@Composable
private fun SharedSettingsApiEnvironmentContent(
    state: ScreenLoadState<SharedSettingsDeveloperSnapshot>,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onSaveApiEnvironment: (SharedSettingsApiEnvironmentRequest) -> Unit
) {
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data
    var selectedPresetKey by remember(snapshot?.environmentKey, snapshot?.options) {
        mutableStateOf(snapshot?.environmentKey?.takeIf { key -> snapshot.options.any { it.key == key } }.orEmpty())
    }
    var label by remember(snapshot?.environmentKey, snapshot?.environmentLabel) {
        mutableStateOf(snapshot?.environmentLabel.orEmpty().ifBlank { "Custom" })
    }
    var apiBaseUrl by remember(snapshot?.environmentKey, snapshot?.apiBaseUrl) {
        mutableStateOf(snapshot?.apiBaseUrl.orEmpty())
    }
    var socketBaseUrl by remember(snapshot?.environmentKey, snapshot?.socketBaseUrl) {
        mutableStateOf(snapshot?.socketBaseUrl.orEmpty())
    }
    var webUrl by remember(snapshot?.environmentKey, snapshot?.webUrl) {
        mutableStateOf(snapshot?.webUrl.orEmpty())
    }
    val busy = actionState?.action == DEVELOPER_ACTION_API_ENVIRONMENT && actionState.inProgress
    val normalizedApi = apiBaseUrl.trim()
    val normalizedSocket = socketBaseUrl.trim()
    val normalizedWeb = webUrl.trim()
    val matchingPreset =
        snapshot?.options?.firstOrNull { option ->
            option.apiBaseUrl == normalizedApi &&
                option.socketBaseUrl == normalizedSocket &&
                option.webUrl == normalizedWeb
        }
    val requestKey = matchingPreset?.key ?: "custom"
    val requestLabel = matchingPreset?.label ?: label.trim().ifBlank { "Custom" }
    val canSave = normalizedApi.startsWith("http") && normalizedSocket.startsWith("http") && !busy

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SettingsFormSectionCard(iconName = SlockLucideIconName.SlidersHorizontal, title = "Presets") {
                SettingsFormDetailText("Choose a preset to fill the environment form, then save to apply it.")
                snapshot?.options.orEmpty().forEach { option ->
                    SettingsApiEnvironmentPresetRow(
                        option = option,
                        selected = selectedPresetKey == option.key,
                        enabled = !busy,
                        onSelect = {
                            selectedPresetKey = option.key
                            label = option.label
                            apiBaseUrl = option.apiBaseUrl
                            socketBaseUrl = option.socketBaseUrl
                            webUrl = option.webUrl
                        }
                    )
                }
                if (snapshot?.options.isNullOrEmpty()) {
                    SettingsFormEmptyText("No presets are available from this host.")
                }
            }
        }
        item {
            SettingsFormSectionCard(iconName = SlockLucideIconName.Server, title = "Environment form") {
                SettingsFormDetailText("Saving an environment change clears the current session and reconnects realtime against the selected API server.")
                SlockBrutalTextField(
                    label = "Label",
                    value = label,
                    placeholder = "Custom",
                    onValueChange = {
                        selectedPresetKey = ""
                        label = it
                    },
                    enabled = !busy
                )
                SlockBrutalTextField(
                    label = "API Base URL",
                    value = apiBaseUrl,
                    placeholder = "https://api.example.com/api/",
                    onValueChange = {
                        selectedPresetKey = ""
                        apiBaseUrl = it
                    },
                    required = true,
                    enabled = !busy
                )
                SlockBrutalTextField(
                    label = "Socket Base URL",
                    value = socketBaseUrl,
                    placeholder = "https://api.example.com",
                    onValueChange = {
                        selectedPresetKey = ""
                        socketBaseUrl = it
                    },
                    required = true,
                    enabled = !busy
                )
                SlockBrutalTextField(
                    label = "Web URL",
                    value = webUrl,
                    placeholder = "https://app.raft.build",
                    onValueChange = {
                        selectedPresetKey = ""
                        webUrl = it
                    },
                    optional = true,
                    enabled = !busy
                )
                SlockBrutalActionButton(
                    label = if (busy) "Saving" else "Save environment",
                    onClick = {
                        onSaveApiEnvironment(
                            SharedSettingsApiEnvironmentRequest(
                                key = requestKey,
                                label = requestLabel,
                                apiBaseUrl = normalizedApi,
                                socketBaseUrl = normalizedSocket,
                                webUrl = normalizedWeb
                            )
                        )
                    },
                    enabled = canSave,
                    leadingIcon = SlockLucideIconName.Save,
                    backgroundColor = SlockColor.BrandYellow,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!canSave && !busy) {
                    SettingsFormErrorText("API Base URL and Socket Base URL must start with http.")
                }
                if (actionState?.action == DEVELOPER_ACTION_API_ENVIRONMENT && actionState.errorCode != null) {
                    SettingsFormErrorText(actionState.errorCode)
                }
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsApiEnvironmentPresetRow(
    option: SharedSettingsApiEnvironmentOption,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) SlockColor.SurfaceCream else SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(
            name = if (selected) SlockLucideIconName.CircleCheckBig else SlockLucideIconName.Server,
            modifier = Modifier.size(18.dp),
            color = SlockColor.TextBlackArgb
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = option.label,
                style = SlockType.Button.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack
            )
            Text(
                text = listOf(option.apiBaseUrl, option.socketBaseUrl).filter { it.isNotBlank() }.joinToString(" | "),
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        Text(
            text = if (selected) "Current" else "Preset",
            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun SharedSettingsDiagnosticsContent(
    state: ScreenLoadState<SharedSettingsDiagnosticsSnapshot>,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onSetProfilerEnabled: (Boolean) -> Unit,
    onStartProfiler: () -> Unit,
    onStopProfiler: () -> Unit,
    onResetProfiler: () -> Unit,
    onRefreshProfilerReport: () -> Unit,
    onExportProfilerReport: () -> Unit,
    onSendFeedback: () -> Unit
) {
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>)?.data
    val payload = (snapshot?.entries ?: emptyList()).toDiagnosticsPayload(state, actionState)
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding) { action, value ->
        when (action) {
            "copyLogs" -> onCopyLogs()
            "shareLogs" -> onShareLogs()
            DIAGNOSTICS_ACTION_PROFILER_ENABLED -> onSetProfilerEnabled(value == "true")
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
    applyTopSafeArea: Boolean = true,
    headerHeight: Dp = SlockSize.PageHeaderHeight,
    onBack: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = title,
            subtitle = subtitle,
            iconName = iconName,
            backgroundColor = SlockColor.SurfaceWhite,
            applyTopSafeArea = applyTopSafeArea,
            headerHeight = headerHeight,
            leadingContent = {
                SettingsDetailBackButton(onBack)
            },
            trailingContent = if (trailingContent != null) {
                { trailingContent() }
            } else {
                null
            }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

private fun ScreenLoadState<SettingsComputersSnapshot>.canShowAddComputerHeaderAction(): Boolean =
    (this as? ScreenLoadState.Content)?.data?.canAddComputer == true

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
        subtitle = "",
        showSelection = true,
        items =
            listOf(
                SlockTabItemPayload(
                    key = SettingsDestinationAccount,
                    title = "Account",
                    subtitle = "Profile, email, password, connected accounts",
                    iconName = SlockLucideIconName.User,
                    value = session?.user?.bestDisplayName,
                    selected = selectedDestination == SettingsDestinationAccount,
                    section = "Personal"
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationLanguageRegion,
                    title = "Language & Region",
                    subtitle = "Language, timezone, translation",
                    iconName = SlockLucideIconName.Languages,
                    selected = selectedDestination == SettingsDestinationLanguageRegion,
                    section = "Personal"
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationAppearance,
                    title = "Appearance",
                    subtitle = "Message font size and reading density",
                    iconName = SlockLucideIconName.Type,
                    selected = selectedDestination == SettingsDestinationAppearance,
                    section = "Personal"
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationNotifications,
                    title = "Notifications",
                    subtitle = "Push status and workspace mute",
                    iconName = SlockLucideIconName.Bell,
                    selected = selectedDestination == SettingsDestinationNotifications,
                    section = "Personal"
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationMobile,
                    title = "Mobile",
                    subtitle = "My message alignment",
                    iconName = SlockLucideIconName.Smartphone,
                    selected = selectedDestination == SettingsDestinationMobile,
                    section = "Personal"
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationStorage,
                    title = "Storage",
                    subtitle = "Local cache and temporary files",
                    iconName = SlockLucideIconName.Archive,
                    selected = selectedDestination == SettingsDestinationStorage,
                    section = "Personal"
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationServer,
                    title = "Server Profile",
                    subtitle = "Workspace name, slug, role",
                    iconName = SlockLucideIconName.Building2,
                    value = session?.currentServerSummary()?.role?.replaceFirstChar { it.uppercase() },
                    selected = selectedDestination == SettingsDestinationServer,
                    section = "Workspace"
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationBilling,
                    title = "Billing",
                    subtitle = "Plan and usage",
                    iconName = SlockLucideIconName.CreditCard,
                    value = session?.currentServerSummary()?.plan?.replaceFirstChar { it.uppercase() },
                    selected = selectedDestination == SettingsDestinationBilling,
                    section = "Workspace"
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationAdministration,
                    title = "Administration",
                    subtitle = "Admins, invites, join links, agreements",
                    iconName = SlockLucideIconName.Shield,
                    selected = selectedDestination == SettingsDestinationAdministration,
                    section = "Workspace"
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationConnectedApps,
                    title = "Connected Apps",
                    subtitle = "Marketplace, installed apps, OAuth clients",
                    iconName = SlockLucideIconName.Link2,
                    selected = selectedDestination == SettingsDestinationConnectedApps,
                    section = "Workspace"
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationComputers,
                    title = "Computers",
                    subtitle = "Connected machines and workspaces",
                    iconName = SlockLucideIconName.Monitor,
                    selected = selectedDestination == SettingsDestinationComputers,
                    section = "Workspace"
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationAbout,
                    title = "About",
                    subtitle = "Version, diagnostics, feedback",
                    iconName = SlockLucideIconName.BadgeInfo,
                    selected = selectedDestination == SettingsDestinationAbout,
                    section = "About"
                )
            )
    )

internal fun ScreenLoadState<AccountSettingsSnapshot>.toAccountPayload(
    session: StoredAuthSession?,
    actionState: build.raft.app.data.SharedSettingsActionState? = null
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
                avatarUrl = profile.avatarUrl,
                gravatarHash = profile.gravatarHash,
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
                avatarUrl = user?.avatarUrl,
                gravatarHash = user?.gravatarHash,
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

internal fun ScreenLoadState<SharedSettingsStorageSnapshot>.toStoragePayload(
    actionState: build.raft.app.data.SharedSettingsActionState?,
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
                    "attachmentDownloads" -> "Clear downloaded files"
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
        "attachmentDownloads" -> SlockLucideIconName.Download
        "temporaryAndLogs" -> SlockLucideIconName.FileText
        else -> SlockLucideIconName.Archive
    }

internal fun ScreenLoadState<SettingsBillingSnapshot>.toBillingPayload(
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
    timeContext: SlockTimeContext?
): KuiklySharedSettingsPayload {
    val server = session?.currentServerSummary()
    val snapshot = (this as? ScreenLoadState.Content<SettingsServerSnapshot>)?.data
    val role = server?.role?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Member"
    val detail =
        when (this) {
            ScreenLoadState.Loading -> if (refreshing) "Refreshing workspace settings..." else "Loading workspace settings..."
            is ScreenLoadState.Error -> "Workspace settings failed to load: ${error.code}"
            is ScreenLoadState.NoPermission -> "Workspace settings unavailable: ${blocker.code}"
            is ScreenLoadState.Empty -> "Workspace settings unavailable: ${reason.name}"
            is ScreenLoadState.Content -> "Manage this workspace's profile. Admin actions live under Administration."
        }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-server",
        title = "Server Profile",
        heroIconName = SlockLucideIconName.Building2,
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
                                "Save changes to the workspace name.",
                                status = actionState.serverStatusFor(SERVER_ACTION_PROFILE_SAVE, if (snapshot?.profile?.canSave == true) "Ready" else "Read only"),
                                enabled = snapshot?.profile?.canSave == true && actionState?.inProgress != true
                            ),
                            KuiklySharedSettingsRow("serverSlug", SlockLucideIconName.Link, "Slug", snapshot?.profile?.slug?.let { "/$it" } ?: server?.slug?.let { "/$it" } ?: "Not provided", status = if (snapshot?.profile?.canSave == true) "Editable" else "Read only"),
                            KuiklySharedSettingsRow("serverRole", SlockLucideIconName.ShieldCheck, "Your role", "Permission scope for workspace settings.", status = role),
                            KuiklySharedSettingsRow(
                                "openAdministration",
                                SlockLucideIconName.Shield,
                                "Administration",
                                "Manage admins, invites, join links, pre-join agreement, onboarding, member permissions, translation, and sidebar order.",
                                status = "Open",
                                enabled = true
                            )
                        )
                )
            )
    )
}

private fun ScreenLoadState<SettingsServerSnapshot>.toAdministrationPayload(
    session: StoredAuthSession?,
    refreshing: Boolean,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    timeContext: SlockTimeContext?
): KuiklySharedSettingsPayload {
    val server = session?.currentServerSummary()
    val snapshot = (this as? ScreenLoadState.Content<SettingsServerSnapshot>)?.data
    val detail =
        when (this) {
            ScreenLoadState.Loading -> if (refreshing) "Refreshing administration..." else "Loading administration..."
            is ScreenLoadState.Error -> "Administration failed to load: ${error.code}"
            is ScreenLoadState.NoPermission -> "Administration unavailable: ${blocker.code}"
            is ScreenLoadState.Empty -> "Administration unavailable: ${reason.name}"
            is ScreenLoadState.Content -> "Manage admins, invites, join links, and pre-join agreements."
        }
    val sectionsByKey = snapshot?.sections.orEmpty().associateBy { it.key }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-administration",
        title = "Administration",
        heroIconName = SlockLucideIconName.Shield,
        heroLabel = snapshot?.profile?.name ?: server?.name ?: "Workspace settings",
        detail = detail,
        sections =
            listOf(
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
                    title = "System Channels",
                    rows = snapshot?.systemChannels.toSystemChannelRows(sectionsByKey["system_channels"], actionState)
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
                )
            )
    )
}

private fun List<SettingsServerAdminSnapshot>?.toAdminRows(
    section: SettingsServerSectionSnapshot?,
    actionState: build.raft.app.data.SharedSettingsActionState?
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
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
    actionState: build.raft.app.data.SharedSettingsActionState?
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
    actionState: build.raft.app.data.SharedSettingsActionState?
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

private fun build.raft.app.data.SettingsServerProfileSnapshot?.toMemberPermissionsRows(
    section: SettingsServerSectionSnapshot?,
    actionState: build.raft.app.data.SharedSettingsActionState?
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
    actionState: build.raft.app.data.SharedSettingsActionState?
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

private fun SettingsServerSystemChannelSnapshot?.toSystemChannelRows(
    section: SettingsServerSectionSnapshot?,
    actionState: build.raft.app.data.SharedSettingsActionState?
): List<KuiklySharedSettingsRow> {
    val system = this
    if (system == null) {
        return listOf(section.toPlaceholderRow("system_channels", SlockLucideIconName.Eye, "System Channels", "System channel controls are unavailable."))
    }
    if (system.allChannelId.isBlank()) {
        return listOf(section.toPlaceholderRow("system_channels", SlockLucideIconName.Eye, "System Channels", "No #all channel was found for this workspace."))
    }
    return listOf(
        KuiklySharedSettingsRow(
            key = SERVER_ACTION_SYSTEM_CHANNEL_HIDE,
            iconName = SlockLucideIconName.Eye,
            title = "Hide #all channel",
            detail = "When enabled, #all disappears from channel lists, search, posting, members, and agent channel surfaces. Existing history is preserved.",
            value = system.allChannelId,
            status = actionState.serverStatusFor(SERVER_ACTION_SYSTEM_CHANNEL_HIDE, if (system.allChannelHidden) "Hidden" else "Visible"),
            enabled = system.canManage && actionState?.inProgress != true
        )
    )
}

private fun SettingsServerSidebarOrderSnapshot?.toSidebarOrderRows(
    section: SettingsServerSectionSnapshot?,
    actionState: build.raft.app.data.SharedSettingsActionState?
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
    refreshing: Boolean
): KuiklySharedSettingsPayload {
    val mobile =
        (this as? ScreenLoadState.Content<SettingsMobileSnapshot>)?.data
            ?: return appShellMobilePlaceholderPayload(this, refreshing)
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-mobile",
        title = "Mobile",
        heroIconName = SlockLucideIconName.Smartphone,
        heroLabel = "Message alignment",
        detail =
            if (refreshing) {
                "Refreshing mobile settings..."
            } else {
                "Choose how your own messages align in conversations."
            },
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Conversation display",
                    rows =
                        listOf(
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
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
                                title = "Device language",
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
        heroLabel = "Message alignment",
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
    actionState: build.raft.app.data.SharedSettingsActionState?
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

private fun build.raft.app.data.SharedSettingsActionState?.statusFor(action: String, fallback: String): String =
    when {
        this == null || this.action != action -> fallback
        inProgress -> "Loading"
        errorCode != null -> "Error"
        else -> "Saved"
    }

private fun build.raft.app.data.SharedSettingsActionState?.serverStatusFor(
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

private fun build.raft.app.data.SharedSettingsActionState?.connectedAppsStatusFor(
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

private fun build.raft.app.data.SharedSettingsActionState?.connectedAppsBusyFor(
    action: String,
    itemId: String? = null
): Boolean =
    this?.action == action &&
        (itemId == null || this.itemId == itemId) &&
        this.inProgress

private fun build.raft.app.data.SharedSettingsActionState?.connectedAppsErrorFor(
    action: String,
    itemId: String? = null
): String? =
    this?.takeIf {
        it.action == action &&
            (itemId == null || it.itemId == itemId) &&
            it.errorCode != null
    }?.errorCode

private fun List<SettingsConnectedAppMarketplaceSnapshot>.settingsConnectedAppCategoryOptions(): List<SlockRaftUiSelectionOption> =
    listOf(SlockRaftUiSelectionOption(SETTINGS_CONNECTED_APPS_CATEGORY_ALL, "All categories")) +
        map { it.category.trim().ifBlank { "Other" } }
            .distinct()
            .sorted()
            .map { SlockRaftUiSelectionOption(it, it.settingsConnectedAppCategoryLabel()) }

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

private fun build.raft.app.data.SharedSettingsActionState?.isServerConfirmation(
    action: String,
    itemId: String? = null
): Boolean =
    this?.action == SERVER_ACTION_CONFIRM &&
        this.itemId == serverConfirmationItemId(action, itemId)

private fun build.raft.app.data.SharedSettingsActionState?.isDiagnosticsActionLoading(action: String): Boolean =
    this?.action == action && this.inProgress

private fun <T> ScreenLoadState<T>.currentContent(): T? =
    (this as? ScreenLoadState.Content<T>)?.data

private val profilerActionKeys =
    setOf(
        DIAGNOSTICS_ACTION_PROFILER_ENABLED,
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

internal fun SharedSettingsAboutSnapshot.toAboutPayload(
    webUrl: String,
    updateCheck: SharedSettingsUpdateCheckSnapshot? = null,
    actionState: SharedSettingsActionState? = null
): KuiklySharedSettingsPayload {
    val resolvedWebUrl = sharedSettingsResolvedWebBaseUrl(webUrl)
    val updateCheckBusy =
        actionState?.action == DEVELOPER_ACTION_QUIVER_UPDATE_CHECK && actionState.inProgress
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-about",
        title = "About",
        heroIconName = SlockLucideIconName.Info,
        heroLabel = appName.ifBlank { "Raft" },
        detail = "Version, build, and source",
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "Application",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("appName", SlockLucideIconName.Sparkles, "Application", appName.ifBlank { "Raft" }),
                            KuiklySharedSettingsRow("version", SlockLucideIconName.BadgeInfo, "Version", versionLabel.ifBlank { "Unknown" }, status = buildLabel.ifBlank { "Build unknown" }),
                            KuiklySharedSettingsRow("buildTime", SlockLucideIconName.Clock, "Build time", buildTime.ifBlank { "Unknown" }),
                            KuiklySharedSettingsRow("gitCommit", SlockLucideIconName.GitBranch, "Git commit", gitCommit.ifBlank { "Unknown" }),
                            KuiklySharedSettingsRow("package", SlockLucideIconName.Package, "Package", packageName.ifBlank { "Unknown" })
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Actions",
                    rows =
                        buildList {
                            if (updateCheck?.supported == true) {
                                add(
                                    KuiklySharedSettingsRow(
                                        key = DEVELOPER_ACTION_QUIVER_UPDATE_CHECK,
                                        iconName = SlockLucideIconName.Download,
                                        title = "Check for update",
                                        detail = updateCheck.detail.ifBlank { "Checks Quiver for a newer Android APK and opens the installer when available." },
                                        status =
                                            when {
                                                updateCheckBusy -> "Checking"
                                                updateCheck.statusLabel.isNotBlank() -> updateCheck.statusLabel
                                                else -> "Check"
                                            },
                                        enabled = !updateCheckBusy
                                    )
                                )
                            }
                            add(KuiklySharedSettingsRow("openSlock", SlockLucideIconName.ExternalLink, "Open Raft", "Open $resolvedWebUrl in the system browser.", value = resolvedWebUrl, status = "Open", enabled = true))
                        }
                )
            )
    )
}

internal fun SharedSettingsDeveloperSnapshot?.toDeveloperPayload(
    state: ScreenLoadState<SharedSettingsDeveloperSnapshot>,
    actionState: SharedSettingsActionState?
): KuiklySharedSettingsPayload {
    val environmentDetail =
        when (state) {
            is ScreenLoadState.Error -> state.error.code
            is ScreenLoadState.Empty -> state.reason.name
            is ScreenLoadState.NoPermission -> state.blocker.code
            ScreenLoadState.Loading -> "Loading developer environment options."
            is ScreenLoadState.Content -> "Choose presets or edit API, socket, and web URLs."
        }
    val environmentStatus =
        when {
            actionState?.action == DEVELOPER_ACTION_API_ENVIRONMENT && actionState.inProgress -> "Saving"
            this?.environmentLabel.orEmpty().isNotBlank() -> this?.environmentLabel.orEmpty()
            state is ScreenLoadState.Loading -> "Loading"
            else -> "Open"
        }
    val sharedTransportBusy =
        actionState?.action == DEVELOPER_ACTION_SHARED_SOCKET_IO_TRANSPORT && actionState.inProgress
    val sharedTransportEnabled = this?.sharedSocketIoTransportEnabled == true
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-developer",
        title = "Developer",
        detail = this?.feedback ?: this?.environmentLabel.orEmpty().ifBlank { "API server and diagnostics" },
        heroIconName = SlockLucideIconName.Terminal,
        heroLabel = "Developer",
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = "API Server",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow(
                                key = "currentApi",
                                iconName = SlockLucideIconName.Globe,
                                title = "Current API",
                                detail = this?.apiBaseUrl.orEmpty().ifBlank { "Unknown" },
                                status = this?.environmentLabel.orEmpty().ifBlank { "Current" }
                            ),
                            KuiklySharedSettingsRow(
                                key = "currentSocket",
                                iconName = SlockLucideIconName.Activity,
                                title = "Current socket",
                                detail = this?.socketBaseUrl.orEmpty().ifBlank { "Unknown" },
                                status = "Realtime"
                            ),
                            KuiklySharedSettingsRow(
                                key = DEVELOPER_ACTION_API_ENVIRONMENT,
                                iconName = SlockLucideIconName.SlidersHorizontal,
                                title = "API Environment",
                                detail = environmentDetail,
                                status = environmentStatus,
                                enabled = state is ScreenLoadState.Content
                            ),
                            KuiklySharedSettingsRow(
                                key = DEVELOPER_ACTION_SHARED_SOCKET_IO_TRANSPORT,
                                iconName = SlockLucideIconName.Activity,
                                title = "Shared Socket.IO transport",
                                detail = "Opt Android, iOS, and OHOS into the shared Ktor websocket-only Socket.IO pilot on the next realtime reconnect.",
                                value = (!sharedTransportEnabled).toString(),
                                status =
                                    when {
                                        sharedTransportBusy -> "Saving"
                                        sharedTransportEnabled -> "Flag on"
                                        else -> "Platform"
                                    },
                                enabled = state is ScreenLoadState.Content && !sharedTransportBusy,
                                toggleValue = sharedTransportEnabled
                            )
                        )
                ),
                KuiklySharedSettingsSection(
                    title = "Diagnostics",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("diagnostics", SlockLucideIconName.ClipboardList, "Diagnostics", "Open auth, sanitized logs, profiler, and feedback tools.", status = "Open", enabled = true),
                            KuiklySharedSettingsRow("router", SlockLucideIconName.Route, "Route launcher", "Open Kuikly route launcher and icon compare.", status = "Open", enabled = true)
                        )
                )
            )
    )
}

private fun SharedSettingsAboutSnapshot.buildSummary(): String =
    buildString {
        append(appName.ifBlank { "Raft" })
        versionLabel.takeIf { it.isNotBlank() }?.let { append(" $it") }
        buildLabel.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
        packageName.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
    }

internal fun List<SharedSettingsDiagnosticsEntry>.toDiagnosticsPayload(
    state: ScreenLoadState<SharedSettingsDiagnosticsSnapshot>,
    actionState: build.raft.app.data.SharedSettingsActionState?
): KuiklySharedSettingsPayload {
    val diagnosticsExportAvailable = state is ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>)?.data
    val profiler = snapshot?.profiler
        ?: SharedSettingsRecompositionProfilerSnapshot()
    val profilerRunning = profiler.statusLabel == "Running"
    val profilerBusy = actionState?.action in profilerActionKeys && actionState?.inProgress == true
    val environmentRows =
        snapshot?.about?.toDiagnosticsEnvironmentRows().orEmpty()
    val loadStatusRows =
        snapshot?.loadErrorMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { errorMessage ->
                listOf(
                    KuiklySharedSettingsRow(
                        key = "diagnosticsLoadStatus",
                        iconName = SlockLucideIconName.TriangleAlert,
                        title = "Partial diagnostics",
                        detail = errorMessage,
                        status = "Partial"
                    )
                )
            }
            .orEmpty()
    val profilerControlSection =
        KuiklySharedSettingsSection(
            title = "Recomposition profiler",
            rows =
                if (profiler.supported) {
                    listOf(
                            KuiklySharedSettingsRow(
                                "profilerStatus",
                                SlockLucideIconName.Activity,
                                "Shared profiler status",
                                profiler.detail,
                                status = profiler.statusLabel
                            ),
                            KuiklySharedSettingsRow(
                                DIAGNOSTICS_ACTION_PROFILER_ENABLED,
                                SlockLucideIconName.Activity,
                                "Recomposition profiler",
                                "Persist this debug switch locally. When enabled, shared settings asks the host bridge to acknowledge profiler mode, applies the default Kuikly profile, and starts collecting recompositions.",
                                value = (!profiler.enabled).toString(),
                                status =
                                    when {
                                        actionState.isDiagnosticsActionLoading(DIAGNOSTICS_ACTION_PROFILER_ENABLED) -> "Loading"
                                        profiler.enabled && profilerRunning -> "On"
                                        profiler.enabled -> "Pending"
                                        else -> "Off"
                                    },
                                enabled = !profilerBusy,
                                toggleValue = profiler.enabled
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
                } else {
                    listOf(
                        KuiklySharedSettingsRow(
                            "profilerStatus",
                            SlockLucideIconName.Activity,
                            "Shared profiler status",
                            profiler.detail,
                            status = profiler.gateLabel
                        ),
                        KuiklySharedSettingsRow(
                            DIAGNOSTICS_ACTION_PROFILER_ENABLED,
                            SlockLucideIconName.Activity,
                            "Recomposition profiler",
                            profiler.detail,
                            status = profiler.gateLabel,
                            enabled = false,
                            toggleValue = false
                        )
                    )
                }
        )
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-diagnostics",
        title = "Diagnostics",
        detail =
            profiler.actionFeedback
                ?: if (profiler.supported) {
                    "Auth, sanitized app diagnostics, and Kuikly recomposition profiler"
                } else if (profiler.detail.isNotBlank()) {
                    "Auth, sanitized app diagnostics, and Kuikly profiler status"
                } else {
                    "Auth and sanitized app diagnostics"
                },
        heroIconName = SlockLucideIconName.ClipboardList,
        heroLabel = "Diagnostics",
        sections =
            listOfNotNull(
                environmentRows.takeIf { it.isNotEmpty() }?.let {
                    KuiklySharedSettingsSection(title = "Environment", rows = it)
                },
                loadStatusRows.takeIf { it.isNotEmpty() }?.let {
                    KuiklySharedSettingsSection(title = "Load status", rows = it)
                }
            ) + listOf(profilerControlSection) + listOf(
                KuiklySharedSettingsSection(
                    title = "Actions",
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("shareLogs", SlockLucideIconName.Share, "Export logs", "Export environment, status, and recent app JSONL logs.", status = if (diagnosticsExportAvailable) "Export" else "Disabled", enabled = diagnosticsExportAvailable),
                            KuiklySharedSettingsRow("feedback", SlockLucideIconName.MessageSquarePlus, "Send diagnostics", "Share sanitized diagnostics with support.", status = "Send", enabled = true)
                        )
                )
            )
    )
}

private fun SharedSettingsAboutSnapshot.toDiagnosticsEnvironmentRows(): List<KuiklySharedSettingsRow> =
    listOf(
        KuiklySharedSettingsRow("diagnosticsApp", SlockLucideIconName.Info, "App", appName.ifBlank { "Raft" }, status = versionLabel.ifBlank { "Version" }),
        KuiklySharedSettingsRow("diagnosticsBuild", SlockLucideIconName.GitBranch, "Build", buildLabel.ifBlank { "Unknown" }, status = sourceLabel.ifBlank { "Source" }),
        KuiklySharedSettingsRow("diagnosticsPackage", SlockLucideIconName.Package, "Package", packageName.ifBlank { "Unknown" }, status = "Host"),
        KuiklySharedSettingsRow("diagnosticsPlatform", SlockLucideIconName.Monitor, "Platform", platformLabel.ifBlank { "Unknown" }, status = deviceLabel.ifBlank { "Device" }),
        KuiklySharedSettingsRow("diagnosticsSession", SlockLucideIconName.User, "Session", signedInUserLabel.ifBlank { "No signed-in user" }, status = sessionStatusLabel.ifBlank { "Unknown" }),
        KuiklySharedSettingsRow("diagnosticsServer", SlockLucideIconName.Server, "Selected server", selectedServerLabel.ifBlank { "Unknown" }, status = socketStatusLabel.ifBlank { "Socket" })
    )

private fun settingsDestinationTitle(destination: String): String =
    when (destination) {
        SettingsDestinationAccount -> "Account"
        SettingsDestinationComputers -> "Computers"
        SettingsDestinationServer -> "Server Profile"
        SettingsDestinationAdministration -> "Administration"
        SettingsDestinationConnectedApps -> "Connected Apps"
        SettingsDestinationBilling -> "Billing"
        SettingsDestinationLanguageRegion -> "Language & Region"
        SettingsDestinationAppearance -> "Appearance"
        SettingsDestinationNotifications -> "Notifications"
        SettingsDestinationMobile -> "Mobile"
        SettingsDestinationStorage -> "Storage"
        SettingsDestinationAbout -> "About"
        SettingsDestinationDeveloper -> "Developer"
        SettingsDestinationApiEnvironment -> "API Environment"
        SettingsDestinationDiagnostics -> "Diagnostics"
        SettingsDestinationDiagnosticsFeedbackChannel -> "请选择 channel"
        else -> "Settings"
    }

private fun settingsDestinationSubtitle(destination: String): String =
    when (destination) {
        SettingsDestinationAccount -> "Profile and sign out"
        SettingsDestinationComputers -> "Connected machines"
        SettingsDestinationServer -> "Workspace profile"
        SettingsDestinationAdministration -> "Admins, invites, join links, agreements"
        SettingsDestinationConnectedApps -> "Marketplace, installed apps, OAuth clients"
        SettingsDestinationBilling -> "Plan and usage"
        SettingsDestinationLanguageRegion -> "Language, timezone, translation"
        SettingsDestinationAppearance -> "Message font size and preview"
        SettingsDestinationNotifications -> "Push status and workspace mute"
        SettingsDestinationMobile -> "My message alignment"
        SettingsDestinationStorage -> "Local cache"
        SettingsDestinationAbout -> "Version and build information"
        SettingsDestinationDeveloper -> "API server, diagnostics, route launcher"
        SettingsDestinationApiEnvironment -> "Presets and custom server URLs"
        SettingsDestinationDiagnostics -> "Sanitized logs and diagnostics"
        SettingsDestinationDiagnosticsFeedbackChannel -> "最近用过的 channel 放前面"
        else -> ""
    }

private fun settingsDestinationIcon(destination: String): SlockLucideIconName =
    when (destination) {
        SettingsDestinationAccount -> SlockLucideIconName.User
        SettingsDestinationComputers -> SlockLucideIconName.Monitor
        SettingsDestinationServer -> SlockLucideIconName.Building2
        SettingsDestinationAdministration -> SlockLucideIconName.Shield
        SettingsDestinationConnectedApps -> SlockLucideIconName.Link2
        SettingsDestinationBilling -> SlockLucideIconName.CreditCard
        SettingsDestinationLanguageRegion -> SlockLucideIconName.Languages
        SettingsDestinationAppearance -> SlockLucideIconName.Type
        SettingsDestinationNotifications -> SlockLucideIconName.Bell
        SettingsDestinationMobile -> SlockLucideIconName.Smartphone
        SettingsDestinationStorage -> SlockLucideIconName.Archive
        SettingsDestinationAbout -> SlockLucideIconName.BadgeInfo
        SettingsDestinationDeveloper -> SlockLucideIconName.Terminal
        SettingsDestinationApiEnvironment -> SlockLucideIconName.SlidersHorizontal
        SettingsDestinationDiagnostics -> SlockLucideIconName.Clipboard
        SettingsDestinationDiagnosticsFeedbackChannel -> SlockLucideIconName.Hash
        else -> SlockLucideIconName.Settings
    }

private fun String.toSharedSettingsDestination(): SharedSettingsDestination? =
    when (this) {
        SettingsDestinationAccount -> SharedSettingsDestination.Account
        SettingsDestinationComputers -> SharedSettingsDestination.Computers
        SettingsDestinationServer -> SharedSettingsDestination.Server
        SettingsDestinationAdministration -> SharedSettingsDestination.Administration
        SettingsDestinationConnectedApps -> SharedSettingsDestination.ConnectedApps
        SettingsDestinationBilling -> SharedSettingsDestination.Billing
        SettingsDestinationLanguageRegion -> SharedSettingsDestination.LanguageRegion
        SettingsDestinationAppearance -> SharedSettingsDestination.Appearance
        SettingsDestinationNotifications -> SharedSettingsDestination.Notifications
        SettingsDestinationMobile -> SharedSettingsDestination.Mobile
        SettingsDestinationStorage -> SharedSettingsDestination.Storage
        SettingsDestinationAbout -> SharedSettingsDestination.About
        SettingsDestinationDeveloper -> SharedSettingsDestination.Developer
        SettingsDestinationDiagnostics -> SharedSettingsDestination.Diagnostics
        SettingsDestinationHome -> SharedSettingsDestination.Home
        else -> null
    }

@Composable
private fun AuthChooseServerPageContent() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val fromShell = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optBoolean(AUTH_CHOOSE_SERVER_FROM_SHELL, false)
    }
    val skipInitialLoad = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optBoolean(AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD, false)
    }
    val closeChooser = remember(pager) {
        { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val authDataSource = sharedStores.authDataSource
    var session by remember { mutableStateOf<StoredAuthSession?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var restoreAttempt by remember { mutableStateOf(0) }

    LaunchedEffect(fromShell, skipInitialLoad) {
        authDataSource.logDiagnostics(
            "auth_choose_server_page_enter fromShell=$fromShell skipInitialLoad=$skipInitialLoad"
        )
    }

    LaunchedEffect(sharedStores.authStore, restoreAttempt) {
        runCatchingPreservingCancellation { sharedStores.authStore.readSession() }
            .onSuccess { restored ->
                session = restored
                authDataSource.logDiagnostics(
                    "auth_choose_server_page_session result=${if (restored == null) "missing" else "success"} " +
                        "serversCount=${restored?.servers?.size ?: 0} " +
                        "selectedServer=${!restored?.selectedServerId.isNullOrBlank()} " +
                        "restoreAttempt=$restoreAttempt"
                )
                errorMessage =
                    if (restored == null) {
                        "Sign in before choosing a server."
                    } else {
                        null
                    }
            }
            .onFailure {
                session = null
                authDataSource.logDiagnostics(
                    "auth_choose_server_page_session result=failure restoreAttempt=$restoreAttempt"
                )
                errorMessage = "Could not restore your session."
            }
    }

    when (val restoredSession = session) {
        null -> {
            if (errorMessage == null) {
                AppShellRestoreSplashContent()
            } else {
                AppShellAuthErrorContent(
                    message = errorMessage ?: "Sign in before choosing a server.",
                    onRetry = {
                        errorMessage = null
                        session = null
                        restoreAttempt += 1
                    },
                    onSignIn = { pager.openLoginReplacingCurrentPage() },
                    onOpenDiagnostics = { pager.openAppShellDeveloper() },
                    onBack = closeChooser.takeIf { fromShell }
                )
            }
        }
        else ->
            AppShellServerGateContent(
                session = restoredSession.copy(selectedServerId = null, selectedServerSlug = null),
                authDataSource = authDataSource,
                skipInitialLoad = skipInitialLoad,
                onReady = {
                    bridge.realtimeDisconnect("server_changed")
                    pager.openAppShellReplacingCurrentPage(suppressRestoreLanding = true)
                },
                onSignOut = {
                    bridge.realtimeDisconnect("logout")
                    pager.openLoginReplacingCurrentPage()
                },
                onBack = closeChooser.takeIf { fromShell }
            )
    }
}

@Composable
internal fun AuthChooseServerVisualContent() {
    val session = remember { authChooseServerVisualSession() }
    val store = remember(session) { InMemorySlockAuthSessionStore(session) }
    val authDataSource = remember(store, session) {
        SharedAuthDataSource(
            api = AuthChooseServerVisualApi(session.user, session.servers),
            sessionStore = store
        )
    }
    AppShellServerGateContent(
        session = session,
        authDataSource = authDataSource,
        onReady = {},
        onSignOut = {}
    )
}

private fun authChooseServerVisualSession(): StoredAuthSession =
    StoredAuthSession(
        user =
            StoredAuthUser(
                id = "user_visual",
                email = "artin@raft.build",
                displayName = "Artin"
            ),
        accessToken = "visual-access-token",
        refreshToken = "visual-refresh-token",
        selectedServerId = null,
        selectedServerSlug = null,
        servers =
            listOf(
                StoredAuthServer(
                    id = "srv_design",
                    name = "Slock Design",
                    slug = "slock-design",
                    role = "owner"
                ),
                StoredAuthServer(
                    id = "srv_android",
                    name = "Android Artifacts",
                    slug = "android-artifacts",
                    role = "admin"
                ),
                StoredAuthServer(
                    id = "srv_feedback",
                    name = "Feedback",
                    slug = "feedback",
                    role = "member"
                )
            )
    )

private class AuthChooseServerVisualApi(
    private val user: StoredAuthUser,
    private val servers: List<StoredAuthServer>
) : SlockAuthApi {
    override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthApiResult<AuthSocialCompleteResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun startMobileOAuth(
        provider: String,
        mode: String,
        platform: String,
        appEnv: String,
        returnUri: String,
        codeChallenge: String,
        authorization: String?
    ): AuthApiResult<AuthMobileOAuthStartResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun completeMobileOAuth(
        code: String,
        codeVerifier: String,
        platform: String,
        acceptLegalTerms: Boolean,
        authorization: String?
    ): AuthApiResult<AuthLoginResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun forgotPassword(email: String): AuthApiResult<Unit> =
        AuthApiResult.Failure(code = 400)

    override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
        AuthApiResult.Success(user)

    override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> =
        AuthApiResult.Failure(code = 401)

    override suspend fun logout(refreshToken: String): AuthApiResult<Unit> =
        AuthApiResult.Success(Unit)

    override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
        AuthApiResult.Success(servers)

    override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
        AuthApiResult.Failure(code = 400)
}

private fun Pager.openAppShellDeveloper() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            AUTH_DEVELOPER_PAGE,
            JSONObject().put("pageName", AUTH_DEVELOPER_PAGE)
        )
}

private fun Pager.openAuthChooseServerPage() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(AUTH_CHOOSE_SERVER_PAGE, authChooseServerPageData(replaceCurrent = false, fromShell = true))
}

private fun Pager.openAppShellReplacingCurrentPage(suppressRestoreLanding: Boolean = false) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            SLOCK_APP_SHELL_PAGE,
            appShellPageData(
                replaceCurrent = true,
                suppressRestoreLanding = suppressRestoreLanding
            )
        )
}

private fun Pager.openLoginReplacingCurrentPage(message: String? = null) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            LOGIN_PAGE,
            loginPageData(
                replaceCurrent = true,
                message = message,
                socialProviders = pageData.params.authRouteSocialProviders()
            )
        )
}

internal fun authChooseServerPageData(
    replaceCurrent: Boolean = false,
    fromShell: Boolean = false,
    skipInitialLoad: Boolean = false
): JSONObject =
    slockAuthRoutePageData(AUTH_CHOOSE_SERVER_PAGE, replaceCurrent)
        .apply {
            if (fromShell) {
                put(AUTH_CHOOSE_SERVER_FROM_SHELL, true)
            }
            if (skipInitialLoad) {
                put(AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD, true)
            }
        }

internal fun appShellPageData(
    replaceCurrent: Boolean = false,
    suppressRestoreLanding: Boolean = false
): JSONObject =
    slockAuthRoutePageData(SLOCK_APP_SHELL_PAGE, replaceCurrent)
        .apply {
            if (suppressRestoreLanding) {
                put(APP_SHELL_SUPPRESS_RESTORE_LANDING, true)
            }
        }

private fun slockAuthRoutePageData(pageName: String, replaceCurrent: Boolean): JSONObject =
    JSONObject().apply {
        put("pageName", pageName)
        if (replaceCurrent) {
            put(AUTH_REPLACE_CURRENT_PAGE, true)
        }
    }

@Composable
private fun AppShellServerGateContent(
    session: StoredAuthSession,
    authDataSource: SharedAuthDataSource,
    skipInitialLoad: Boolean = false,
    onReady: (StoredAuthSession) -> Unit,
    onSignOut: () -> Unit,
    onSessionCleared: (StoredAuthSession) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    var latestSession by remember(session) { mutableStateOf(session) }
    var mode by remember(session.servers) { mutableStateOf(if (session.servers.isEmpty()) "empty" else "choose") }
    var createName by remember { mutableStateOf("") }
    var createSlug by remember { mutableStateOf("") }
    var pendingServerId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val showPinnedSignOut = serverGateShouldPinSignOut(errorMessage)

    LaunchedEffect(Unit) {
        if (skipInitialLoad) {
            authDataSource.logDiagnostics(
                "auth_choose_server_load_result result=skipped reason=fresh_no_server_session cachedServers=${latestSession.servers.size}"
            )
            return@LaunchedEffect
        }
        when (val result = runCatchingPreservingCancellation { authDataSource.loadServers() }.getOrNull()) {
            is SharedAuthResult.Success -> {
                authDataSource.logDiagnostics(
                    "auth_choose_server_load_result result=success serversCount=${result.data.servers.size} selectedServer=${!result.data.selectedServerId.isNullOrBlank()}"
                )
                latestSession = result.data
            }
            is SharedAuthResult.Failure -> {
                authDataSource.logDiagnostics("auth_choose_server_load_result result=failure code=${result.code}")
                errorMessage = result.message
            }
            SharedAuthResult.Unauthenticated -> {
                authDataSource.logDiagnostics("auth_choose_server_load_result result=unauthenticated action=stay_on_server_picker")
                errorMessage = serverGateUnauthenticatedMessage()
            }
            null -> {
                authDataSource.logDiagnostics("auth_choose_server_load_result result=failure code=exception")
                errorMessage = "Could not load servers."
            }
        }
    }
    LaunchedEffect(latestSession.servers.size) {
        if (mode != "create") {
            mode = if (latestSession.servers.isEmpty()) "empty" else "choose"
        }
    }

    AuthScaffold(
        contentTopPadding = 72.dp,
        contentScrollable = true,
        topBarHorizontalPadding = 8.dp,
        topBarBrandImagePadding = 0.dp,
        topLeadingContent = onBack?.let { back ->
            {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = back,
                    size = 34.dp,
                    shadowOffset = 2.dp
                )
            }
        }
    ) {
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
            if (showPinnedSignOut) {
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
            if (!showPinnedSignOut) {
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
    }

    LaunchedEffect(pendingServerId) {
        val target = pendingServerId ?: return@LaunchedEffect
        if (target == "__logout__") {
            val clearedSession = latestSession
            when (val result = authDataSource.logout()) {
                is SharedAuthResult.Success -> {
                    onSessionCleared(clearedSession)
                    onSignOut()
                }
                is SharedAuthResult.Failure -> errorMessage = result.message
                SharedAuthResult.Unauthenticated -> {
                    onSessionCleared(clearedSession)
                    onSignOut()
                }
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
            is SharedAuthResult.Success -> {
                authDataSource.logDiagnostics(
                    "auth_choose_server_action_result target=$target result=success selectedServer=${!result.data.selectedServerId.isNullOrBlank()}"
                )
                onReady(result.data)
            }
            is SharedAuthResult.Failure -> {
                authDataSource.logDiagnostics("auth_choose_server_action_result target=$target result=failure code=${result.code}")
                errorMessage = result.message
            }
            SharedAuthResult.Unauthenticated -> {
                authDataSource.logDiagnostics("auth_choose_server_action_result target=$target result=unauthenticated action=stay_on_server_picker")
                errorMessage = serverGateUnauthenticatedMessage()
            }
        }
    }

}

private fun serverGateUnauthenticatedMessage(): String =
    "Your session needs to be refreshed before choosing a server. Try again, or sign out and sign in again."

internal fun serverGateShouldPinSignOut(errorMessage: String?): Boolean =
    errorMessage == serverGateUnauthenticatedMessage()

@Composable
private fun AppShellServerEmptyState(
    onCreateServer: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
    onOpenDiagnostics: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    AuthScaffold(
        onDiagnosticsShortcut = onOpenDiagnostics,
        topLeadingContent = onBack?.let { back ->
            {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = back,
                    size = 34.dp,
                    shadowOffset = 2.dp
                )
            }
        }
    ) {
        AuthLogoBadge("RAFT")
        Text(
            text = "Session error",
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        AuthError(message)
        AuthActionButton(text = "Retry", onClick = onRetry, tone = AuthButtonTone.Pink)
        AuthActionButton(text = "Sign In", onClick = onSignIn, tone = AuthButtonTone.White)
    }
}

@Composable
private fun SharedMembersScreen(
    bridge: build.raft.app.base.BridgeModule,
    session: StoredAuthSession?,
    memberRepository: MemberRepository,
    settingsRepository: SettingsRepository,
    createAgentCoordinator: SharedCreateAgentCoordinator,
    computersStore: SharedComputersStore,
    agentDetailStore: SharedAgentDetailStore,
    agentPanelTabOrderStore: SharedAgentPanelTabOrderStore,
    serverMembersStore: SharedServerMembersStore,
    membersRefreshKey: Int,
    initialContent: SharedMembersUiState.Content?,
    onContentLoaded: (SharedMembersUiState.Content) -> Unit,
    timeContext: AppShellTimeContext?,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    initialListState: AppShellMembersListState = AppShellMembersListState(),
    onListStateSnapshot: (AppShellMembersListState) -> Unit = {}
) {
    val currentServerId = session?.currentServerId?.trim()?.takeIf { it.isNotBlank() }
    var state by remember(currentServerId) {
        mutableStateOf<SharedMembersUiState>(initialContent ?: SharedMembersUiState.Loading)
    }
    var refreshing by remember { mutableStateOf(false) }
    val pager = LocalActivity.current.getPager() as Pager
    val routeScope = rememberCoroutineScope()
    val agentPanelTabOrderState by agentPanelTabOrderStore.state.collectAsState()
    val settingsServerManagementRepository = settingsRepository as? SettingsServerManagementRepository
    suspend fun loadAgentPanelTabOrderForSharedMembers(): List<String>? {
        val repository = settingsServerManagementRepository ?: return null
        return when (val result = repository.getServerSidebarOrder()) {
            is DataResult.Content -> result.data.agentPanelTabOrder
            is DataResult.Empty -> null
            is DataResult.Error -> {
                bridge.log("shared_members_agent_tab_order_load_failed code=${result.error.code}")
                null
            }
            is DataResult.NoPermission -> {
                bridge.log("shared_members_agent_tab_order_load_blocked code=${result.blocker.code}")
                null
            }
        }
    }
    LaunchedEffect(agentPanelTabOrderStore, currentServerId) {
        agentPanelTabOrderStore.bindServer(currentServerId)
    }
    LaunchedEffect(
        settingsServerManagementRepository,
        agentPanelTabOrderStore,
        currentServerId,
        agentPanelTabOrderState.serverId,
        agentPanelTabOrderState.loaded
    ) {
        if (currentServerId.isNullOrBlank()) return@LaunchedEffect
        if (agentPanelTabOrderState.serverId == currentServerId && agentPanelTabOrderState.loaded) return@LaunchedEffect
        loadAgentPanelTabOrderForSharedMembers()?.let { order ->
            agentPanelTabOrderStore.applyLoaded(currentServerId, order)
        }
    }

    LaunchedEffect(membersRefreshKey, currentServerId) {
        var previousContent: SharedMembersUiState.Content? = null
        try {
            previousContent =
                sharedMembersCachedContentForServer(state as? SharedMembersUiState.Content, currentServerId)
                    ?: sharedMembersCachedContentForServer(initialContent, currentServerId)
            if (previousContent != null) {
                serverMembersStore.seed(MemberSnapshot(previousContent.server, previousContent.members))
                state = previousContent
                refreshing = true
            } else {
                state = SharedMembersUiState.Loading
            }
            val server = session?.servers?.firstOrNull { it.id == currentServerId }?.toServerSummary()
            if (session == null || currentServerId.isNullOrBlank() || server == null) {
                state = previousContent ?: SharedMembersUiState.Error("server_required")
                return@LaunchedEffect
            }
            val serverId = ServerId(currentServerId)
            val response = serverMembersStore.refreshMembers(serverId, DataRequestPriority.Visible)
            state =
                when (response) {
                    is DataResult.Content -> {
                        val content =
                            serverMembersStore.currentServerMembersContent(serverId, fallbackServer = server)?.let { snapshot ->
                                SharedMembersUiState.Content(snapshot.server, snapshot.members, session.user.id)
                            } ?: SharedMembersUiState.Content(server, emptyList(), session.user.id)
                        onContentLoaded(content)
                        content
                    }
                    is DataResult.Empty -> {
                        val content = SharedMembersUiState.Content(server, emptyList(), session.user.id)
                        onContentLoaded(content)
                        content
                    }
                    is DataResult.NoPermission ->
                        previousContent ?: SharedMembersUiState.Error(response.blocker.code)
                    is DataResult.Error ->
                        previousContent ?: SharedMembersUiState.Error(response.error.code)
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            state = previousContent ?: SharedMembersUiState.Error("members_network")
        } finally {
            refreshing = false
        }
    }

    when (val current = state) {
        SharedMembersUiState.Loading ->
            Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
                SlockTopPageHeader(
                    title = "Members",
                    subtitle = null,
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
            val serverId = current.server.id.value
            val agentPanelTabOrder =
                if (agentPanelTabOrderState.serverId == serverId) {
                    agentPanelTabOrderState.order
                } else {
                    emptyList()
                }
            val membersPayload = buildHostMembersPayload(current.server, timeContext, agentPanelTabOrder)
            val renderMembersPayload = membersPayload.copy(members = current.members)
            val agentStatusResolver =
                remember(serverMembersStore, serverId) {
                    slockAgentStatusResolver(serverMembersStore, serverId)
                }
            val computersSlice by computersStore
                .computerPickerSliceFlow(serverId)
                .collectAsState(SharedComputersPickerSlice())
            val loadMachines: () -> Unit = {
                if (serverId.isNotBlank()) {
                    computersStore.dispatch(SharedComputersIntent.EnsureComputerPicker(serverId))
                }
            }
            val createAgentRuntimeModelScope = rememberCoroutineScope()
            var createAgentRuntimeModels by remember(serverId) {
                mutableStateOf<Map<Pair<String, String>, List<SharedCreateAgentRuntimeModelOption>>>(emptyMap())
            }
            var createAgentRuntimeModelLoadsInFlight by remember(serverId) {
                mutableStateOf<Set<Pair<String, String>>>(emptySet())
            }
            val loadRuntimeModels: (String, String, Boolean) -> Unit = { machineId, runtime, force ->
                val key = machineId.trim() to runtime.trim()
                if (serverId.isBlank() || key.first.isBlank() || key.second.isBlank()) {
                    // no-op: the dialog keeps using the static runtime catalog fallback
                } else if (!force && (key in createAgentRuntimeModels || key in createAgentRuntimeModelLoadsInFlight)) {
                    // no-op: cached or already loading
                } else {
                    createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight + key
                    createAgentRuntimeModelScope.launch {
                        when (val result = createAgentCoordinator.loadRuntimeModels(serverId, key.first, key.second)) {
                            is DataResult.Content -> {
                                createAgentRuntimeModels = createAgentRuntimeModels + (key to result.data)
                            }
                            is DataResult.Empty -> Unit
                            is DataResult.Error ->
                                bridge.log("create_agent_runtime_models_failed source=shared_members code=${result.error.code}")
                            is DataResult.NoPermission ->
                                bridge.log("create_agent_runtime_models_blocked source=shared_members code=${result.blocker.code}")
                        }
                        createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight - key
                    }
                }
            }
            // task #79: wire Settings → Members humans "+" invite flow.
            // Mirror the MembersPage.kt path: build a SharedMembersDataSource
            // on the fly from the host memberRepository + settingsRepository,
            // then expose loadJoinLinks/ensureJoinLink/sendInvite to the
            // dialog via MembersRouteContent invite params.
            val settingsMembersDataSource = remember(memberRepository, settingsRepository, serverMembersStore, serverId) {
                SharedMembersDataSource(
                    memberRepository = memberRepository,
                    settingsRepository = settingsRepository,
                    serverMembersStore = serverMembersStore
                )
            }
            val inviteScope = rememberCoroutineScope()
            var inviteJoinLinkLoading by remember { mutableStateOf(false) }
            var inviteJoinLinkUrl by remember { mutableStateOf<String?>(null) }
            var inviteJoinLinkCopied by remember { mutableStateOf(false) }
            fun loadInviteJoinLink() {
                if (serverId.isBlank() || inviteJoinLinkLoading) return
                inviteJoinLinkLoading = true
                inviteScope.launch {
                    val listed = settingsMembersDataSource.loadJoinLinks(serverId)
                    val existing = (listed as? DataResult.Content)?.data.orEmpty().firstOrNull()
                    val result = if (existing != null) {
                        DataResult.Content(existing)
                    } else {
                        settingsMembersDataSource.ensureJoinLink(serverId)
                    }
                    inviteJoinLinkLoading = false
                    when (result) {
                        is DataResult.Content -> inviteJoinLinkUrl = result.data.url
                        is DataResult.NoPermission ->
                            bridge.toast("You do not have permission to manage invites.")
                        is DataResult.Error ->
                            bridge.toast("Failed to load join link: ${result.error.code}")
                        is DataResult.Empty -> Unit
                    }
                }
            }
            val inviteHumanSubmit: (suspend (List<String>) -> DataResult<Unit>)? =
                remember(settingsMembersDataSource, serverId) {
                    { emails ->
                        if (serverId.isBlank()) {
                            DataResult.Error(ScreenError(code = "server_id_missing", retryable = false))
                        } else {
                            val results = emails.map { email ->
                                settingsMembersDataSource.sendInvite(serverId, email.trim())
                            }
                            when {
                                results.isEmpty() ->
                                    DataResult.Empty(EmptyReason.NoSettingsAvailable)
                                results.all { it is DataResult.Content } ->
                                    DataResult.Content(Unit)
                                else -> {
                                    val firstNoPermission = results.firstNotNullOfOrNull {
                                        (it as? DataResult.NoPermission)?.blocker?.code
                                    }
                                    val firstError = results.firstNotNullOfOrNull {
                                        (it as? DataResult.Error)?.error?.code
                                    }
                                    DataResult.Error(
                                        ScreenError(
                                            code = firstNoPermission ?: firstError ?: "invite_send_failed",
                                            retryable = true
                                        )
                                    )
                                }
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
                        avatarUrl = result.data.avatarUrl,
                        avatar = AvatarReference.fromWireValue(result.data.avatarUrl),
                        presence = Presence.Offline,
                        agentStatus = AgentStatus.Online
                    )
                    val nextState =
                        when (val latest = state) {
                            is SharedMembersUiState.Content ->
                                latest.copy(members = latest.members + profile)
                            else -> latest
                        }
                    state = nextState
                    (nextState as? SharedMembersUiState.Content)?.let(onContentLoaded)
                }
                when (result) {
                    is DataResult.Content -> DataResult.Content(Unit)
                    is DataResult.Empty -> DataResult.Empty(result.reason)
                    is DataResult.Error -> result
                    is DataResult.NoPermission -> result
                }
            }
            MembersRouteContent(
                payload = renderMembersPayload,
                refreshing = refreshing,
                onAddHuman = { loadInviteJoinLink() },
                agentStatusResolver = agentStatusResolver,
                onOpenMemberDetail = { member ->
                    routeScope.launch {
                        val launchPayload =
                            if (
                                member.kind == MemberKind.Agent &&
                                    (agentPanelTabOrderState.serverId != serverId || !agentPanelTabOrderState.loaded)
                            ) {
                                loadAgentPanelTabOrderForSharedMembers()
                                    ?.also { order -> agentPanelTabOrderStore.applyLoaded(serverId, order) }
                                    ?.let { order -> membersPayload.copy(agentPanelTabOrder = order) }
                                    ?: membersPayload
                            } else {
                                membersPayload
                            }
                        val pageData = launchPayload.toMemberDetailPageData(member.id.value, member.kind)
                        if (pageData == null) {
                            bridge.toast("Unable to open profile")
                            bridge.log("members_detail_open_skip reason=missing_member_id source=app_shell")
                        } else {
                            agentDetailStore.dispatch(SharedAgentDetailIntent.SeedDetailFromMember(member))
                            pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                                .openPage(MEMBER_DETAIL_PAGE, pageData)
                        }
                    }
                },
                canCreateAgents = membersPayload.canCreateAgents,
                createAgentSubmit = createAgentSubmit,
                createAgentMachines = computersSlice.machines,
                createAgentMachinesLoading = computersSlice.status == SharedComputersLoadStatus.InFlight,
                createAgentCurrentAgentCount = current.members.count { it.kind == MemberKind.Agent },
                createAgentLoadMachines = loadMachines,
                createAgentRuntimeModelsByMachineAndRuntime = createAgentRuntimeModels,
                createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight,
                createAgentLoadRuntimeModels = loadRuntimeModels,
                inviteHumanSubmit = inviteHumanSubmit,
                inviteHumanJoinLinkLoading = inviteJoinLinkLoading,
                inviteHumanJoinLinkUrl = inviteJoinLinkUrl,
                inviteHumanJoinLinkCopied = inviteJoinLinkCopied,
                inviteHumanJoinLinkCopy = { url ->
                    bridge.copyToPasteboard(url)
                    inviteJoinLinkCopied = true
                },
                inviteHumanLoadJoinLink = { loadInviteJoinLink() },
                bottomContentPadding = bottomContentPadding,
                initialListState = initialListState,
                onListStateSnapshot = onListStateSnapshot
            )
        }
    }
}

internal fun buildHostMembersPayload(
    server: ServerSummary,
    timeContext: SlockTimeContext?,
    agentPanelTabOrder: List<String> = emptyList()
): KuiklyMembersPayload =
    KuiklyMembersPayload(
        instanceId = server.id.value,
        title = "Members",
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: SlockTimeFormat24Hour,
        agentPanelTabOrder = agentPanelTabOrder,
        // Product mapping for now: create-agent follows server admin/settings permission
        // until the API exposes a dedicated capability.
        canCreateAgents = server.permissions.canManageSettings
    )

internal sealed interface SharedMembersUiState {
    data object Loading : SharedMembersUiState
    data class Error(val code: String) : SharedMembersUiState
    data class Content(
        val server: ServerSummary,
        val members: List<MemberProfile>,
        val currentUserId: String?
    ) : SharedMembersUiState
}

internal fun StoredAuthSession.withAppShellSelectedServer(serverId: String): StoredAuthSession? {
    val targetServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    val server = servers.firstOrNull { it.id == targetServerId } ?: return null
    return copy(selectedServerId = targetServerId, selectedServerSlug = server.slug)
}

internal fun sharedMembersCachedContentForServer(
    cachedContent: SharedMembersUiState.Content?,
    serverId: String?
): SharedMembersUiState.Content? =
    cachedContent?.takeIf { content ->
        !serverId.isNullOrBlank() && content.server.id.value == serverId
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
    bridge: build.raft.app.base.BridgeModule,
    state: SharedHomeState,
    active: Boolean,
    sessionReady: Boolean = true,
    draftPersistSlice: SharedThreadComposerDraftPersistSlice? = null,
    conversationIdentityProvider: SharedConversationIdentityProvider? = null,
    activityUnreadCount: Int = 0,
    activityTotalCount: Int = 0,
    savedCount: Int = 0,
    prefersChineseCommunity: Boolean = false,
    onIntent: (SharedHomeIntent) -> Unit,
    onNotificationDismiss: (String) -> Unit = { id -> onIntent(SharedHomeIntent.DismissNotification(id)) },
    onAppUpdateInstall: (() -> Unit)? = null,
    selectedConversationId: String? = null,
    onSelectedConversationCleared: () -> Unit = {},
    onOpenSearch: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenServerSelection: () -> Unit,
    onCreateChannel: () -> Unit,
    onOpenConversation: (ConversationSummary) -> Unit,
    serverMembersStore: SharedServerMembersStore? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    onListPositionSnapshot: (AppShellLazyListPosition) -> Unit = {}
) {
    val homeContentSummary = sharedHomeContentRenderSummary(state.content)
    val shouldStartHome = sharedHomeShouldStartOnActive(active, sessionReady, state)
    LaunchedEffect(active, sessionReady, homeContentSummary, state.homeRealtimeDirty, shouldStartHome) {
        bridge.log(
            "shared_home_screen_enter active=$active sessionReady=$sessionReady " +
                "shouldStart=$shouldStartHome state=$homeContentSummary homeRealtimeDirty=${state.homeRealtimeDirty}"
        )
    }
    LaunchedEffect(active, sessionReady, state.content, state.homeRealtimeDirty) {
        val effectShouldStart = sharedHomeShouldStartOnActive(active, sessionReady, state)
        bridge.log(
            "shared_home_start_effect active=$active sessionReady=$sessionReady " +
                "shouldStart=$effectShouldStart state=${sharedHomeContentRenderSummary(state.content)} " +
                "homeRealtimeDirty=${state.homeRealtimeDirty}"
        )
        if (effectShouldStart) {
            bridge.log(
                "shared_home_start_effect_dispatch active=$active sessionReady=$sessionReady " +
                    "state=${sharedHomeContentRenderSummary(state.content)}"
            )
            onIntent(SharedHomeIntent.Start)
        }
    }

    val serverSwitchState = state.serverSwitchState
    val serverSwitcherExpanded = state.serverSwitcherExpanded
    val renderBranch = sharedHomeRenderBranch(state.content)
    LaunchedEffect(active, renderBranch, homeContentSummary, selectedConversationId, activityUnreadCount, savedCount) {
        bridge.log(
            "shared_home_render_branch active=$active branch=$renderBranch state=$homeContentSummary " +
                "activityUnread=$activityUnreadCount saved=$savedCount selectedConversation=${selectedConversationId.orEmpty()}"
        )
    }
    when (val current = state.content) {
        is SharedHomeContentState.Loading -> {
            val session = current.session
            if (session != null) {
                SharedHomeSessionShell(
                    session = session,
                    serverSwitcherExpanded = serverSwitcherExpanded,
                    serverUnreadCounts = state.serverUnreadCounts,
                    prefersChineseCommunity = prefersChineseCommunity,
                    bottomContentPadding = bottomContentPadding,
                    onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                    onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                    onServerSelected = { serverId ->
                        onSelectedConversationCleared()
                        onIntent(SharedHomeIntent.SelectServer(serverId))
                    },
                    onCreateServerClick = onOpenServerSelection,
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
                serverUnreadCounts = state.serverUnreadCounts,
                prefersChineseCommunity = prefersChineseCommunity,
                bottomContentPadding = bottomContentPadding,
                onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                onServerSelected = { serverId ->
                    onSelectedConversationCleared()
                    onIntent(SharedHomeIntent.SelectServer(serverId))
                },
                onCreateServerClick = onOpenServerSelection,
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
            val agentStatusResolver =
                remember(serverMembersStore, snapshot.server.id.value) {
                    slockAgentStatusResolver(serverMembersStore, snapshot.server.id.value)
                }
            val memberAvatarResolver =
                remember(serverMembersStore, snapshot.server.id.value) {
                    slockMemberAvatarResolver(serverMembersStore, snapshot.server.id.value)
                }
            LaunchedEffect(serverMembersStore, snapshot.server.id) {
                bridge.log(
                    "shared_home_avatar_chain serverId=${snapshot.server.id.value} " +
                        "memberStore=${serverMembersStore != null} resolver=member_ssot"
                )
            }
            LaunchedEffect(serverMembersStore, snapshot.server.id, current.contentRefreshing) {
                if (!current.contentRefreshing) {
                    serverMembersStore?.refreshMembers(snapshot.server.id, DataRequestPriority.Background)
                }
            }
            val activeDraftRecords by (draftPersistSlice?.activeDraftRecordsState ?: EmptyComposerDraftRecordsFlow)
                .collectAsState(emptySet())
            val draftProjection =
                remember(activeDraftRecords, draftPersistSlice) {
                    if (draftPersistSlice != null) {
                        activeDraftRecords.toSharedThreadComposerDraftProjection()
                    } else {
                        SharedThreadComposerDraftProjection.Empty
                    }
                }
            val conversationDirectoryIds =
                remember(snapshot.channels, snapshot.directMessages) {
                    (snapshot.channels + snapshot.directMessages).map { it.threadId.value }
                }
            val conversationDirectoryFlow =
                remember(conversationIdentityProvider, snapshot.server.id, conversationDirectoryIds) {
                    conversationIdentityProvider
                        ?.projectionFlow(snapshot.server.id, conversationDirectoryIds)
                        ?: EmptyConversationDirectoryProjectionFlow
                }
            val conversationDirectoryProjections by conversationDirectoryFlow.collectAsState(emptyMap())
            val conversationRowProjector =
                remember(conversationDirectoryProjections, draftProjection) {
                    SharedHomeConversationRowProjector(
                        directoryProjections = conversationDirectoryProjections,
                        draftProjection = draftProjection
                    )
                }
            val servers =
                remember(current.session.servers, snapshot.server, state.serverUnreadCounts) {
                    current.session.servers.map { server ->
                        server.toServerSummary().let { summary ->
                            if (summary.id == snapshot.server.id) {
                                snapshot.server
                            } else {
                                summary
                            }
                        }.toSharedSwitcherItem(state.serverUnreadCounts)
                    }.ifEmpty { listOf(snapshot.server.toSharedSwitcherItem(state.serverUnreadCounts)) }
                }
            val channelItems =
                remember(snapshot.channels, conversationRowProjector) {
                    snapshot.channels.map { conversation ->
                        conversationRowProjector.project(conversation)
                    }
                }
            val directMessageItems =
                remember(snapshot.directMessages, conversationRowProjector) {
                    snapshot.directMessages.map { conversation ->
                        conversationRowProjector.project(conversation)
                    }
                }
            val channelDraftIds =
                remember(channelItems) {
                    channelItems.filter { it.hasDraft }.map { it.id }
                }
            val directMessageDraftIds =
                remember(directMessageItems) {
                    directMessageItems.filter { it.hasDraft }.map { it.id }
                }
            LaunchedEffect(snapshot.server.id, activeDraftRecords, channelDraftIds, directMessageDraftIds) {
                bridge.log(
                    "shared_home_draft_projection serverId=${snapshot.server.id.value} " +
                        "activeCount=${activeDraftRecords.size} " +
                        "activeRecords=${activeDraftRecords.toThreadComposerDraftDiagnosticsSummary()} " +
                        "channelDraftIds=${channelDraftIds.joinToString(separator = ",")} " +
                        "directMessageDraftIds=${directMessageDraftIds.joinToString(separator = ",")}"
                )
            }
            val notificationItems =
                remember(state.notifications) {
                    state.notifications.map { it.toHomeNotificationItem() }
                }
            val conversationsByThreadId =
                remember(snapshot.channels, snapshot.directMessages) {
                    (snapshot.channels + snapshot.directMessages).associateBy { it.threadId.value }
                }
            val homeActivityUnreadCount = homeActivitySidebarUnreadCount(
                activityUnreadCount = activityUnreadCount
            )
            SlockStructuralHomeShell(
                currentServerName = snapshot.server.name,
                currentServerId = snapshot.server.id.value,
                servers = servers,
                channels = channelItems,
                directMessages = directMessageItems,
                selectedChannelId = selectedConversationId,
                inboxUnreadCount = homeActivityUnreadCount,
                inboxTotalCount = homeActivitySidebarTotalCount(
                    activityUnreadCount = homeActivityUnreadCount,
                    activityTotalCount = activityTotalCount
                ),
                savedCount = savedCount,
                serverSwitcherExpanded = serverSwitcherExpanded,
                pendingServerId = serverSwitchState.pendingServerId,
                prefersChineseCommunity = prefersChineseCommunity,
                activitySelected = state.selectedTopAction == SharedHomeTopAction.Activity,
                searchSelected = state.selectedTopAction == SharedHomeTopAction.Search,
                savedSelected = state.selectedTopAction == SharedHomeTopAction.Saved,
                notifications = notificationItems,
                contentRefreshing = sharedHomeHeaderRefreshing(state),
                dmOrder = snapshot.sidebarOrder?.dmOrder.orEmpty(),
                dmSortMode = snapshot.sidebarOrder?.dmSortMode ?: "manual",
                pinnedChannelIds = snapshot.sidebarOrder?.pinnedChannelIds.orEmpty(),
                pinnedOrder = snapshot.sidebarOrder?.pinnedOrder.orEmpty(),
                pinnedSortMode = snapshot.sidebarOrder?.pinnedSortMode ?: "manual",
                hiddenDmIds = snapshot.sidebarOrder?.hiddenDmIds.orEmpty(),
                canArchiveChannels = snapshot.server.permissions.canManageSettings,
                showBottomBar = false,
                bottomContentPadding = bottomContentPadding,
                outerPadding = 0.dp,
                onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                onServerSelected = { serverId ->
                    onSelectedConversationCleared()
                    onIntent(SharedHomeIntent.SelectServer(serverId))
                },
                onCreateServerClick = onOpenServerSelection,
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
                    when {
                        notification.id.isAppUpdateHomeNotificationId() && onAppUpdateInstall != null ->
                            onAppUpdateInstall()
                        notification.targetId?.startsWith("http://") == true ||
                            notification.targetId?.startsWith("https://") == true -> {
                            notification.targetId?.let(bridge::openUrl)
                        }
                        else -> {
                            bridge.toast("Notification opened")
                        }
                    }
                },
                onNotificationDismiss = { notification ->
                    onNotificationDismiss(notification.id)
                },
                onRefresh = {
                    onIntent(SharedHomeIntent.Refresh)
                },
                onChannelClick = { threadId ->
                    val conversation = conversationsByThreadId[threadId]
                    if (conversation == null) {
                        bridge.toast("Conversation is no longer available")
                    } else {
                        onOpenConversation(conversation.toConversationSummary())
                        onIntent(SharedHomeIntent.SelectConversation(threadId))
                    }
                },
                onChannelLongPress = { _, _ -> },
                onPinnedSortModeChange = { mode ->
                    onIntent(SharedHomeIntent.SetPinnedSortMode(mode))
                },
                onPinToggle = { conversationId ->
                    onIntent(SharedHomeIntent.TogglePinned(conversationId))
                },
                onReadToggle = { conversationId, markUnread ->
                    onIntent(
                        if (markUnread) {
                            SharedHomeIntent.MarkUnread(conversationId)
                        } else {
                            SharedHomeIntent.MarkRead(conversationId)
                        }
                    )
                },
                onCloseDirectMessage = { conversationId ->
                    onIntent(SharedHomeIntent.CloseDirectMessage(conversationId))
                },
                onArchiveChannel = { conversationId ->
                    onIntent(SharedHomeIntent.ArchiveChannel(conversationId))
                },
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                diagnosticsLogger = bridge::log,
                initialListPosition = initialListPosition,
                onListPositionSnapshot = onListPositionSnapshot
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
                        style = SlockType.Body.withSlockFontWeight(FontWeight.Bold),
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
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold),
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
        .map { it.toConversationSummary() }
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

private fun SettingsDiagnosticsFeedbackDraft.toDiagnosticsPlatformFile(): PlatformFile =
    PlatformFile(
        id = "diagnostics-feedback-${fileName.hashCode().toString().replace("-", "n")}-${sizeBytes}",
        fileName = fileName.ifBlank { "slock-feedback.txt" },
        mimeType = mimeType.ifBlank { "text/plain" },
        sizeBytes = sizeBytes.takeIf { it > 0L } ?: bytes.size.toLong(),
        bytes = bytes
    )

internal const val DiagnosticsFeedbackComposerSeedText: String =
    "Diagnostics zip attached.\n\n<!-- Describe what happened here. -->"

internal fun SettingsDiagnosticsFeedbackDraft.diagnosticsComposerMessage(): String =
    DiagnosticsFeedbackComposerSeedText

private fun WireThreadLaunchPayload.withDiagnosticsFeedbackDraft(
    draft: SettingsDiagnosticsFeedbackDraft
): WireThreadLaunchPayload =
    copy(
        composerSeedText = draft.diagnosticsComposerMessage(),
        composerSyncNonce = composerSyncNonce + 1,
        composerFocusRequestNonce = composerFocusRequestNonce + 1
    )

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
        if (member.kind == MemberKind.Agent) {
            when (member.presence) {
                Presence.Active -> SlockColor.AccentLime
                Presence.Away -> SlockColor.BrandYellow
                Presence.Offline -> SlockColor.EmptyStateGray
            }
        } else {
            null
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
            },
        icon =
            when (icon) {
                SharedHomeNotificationIcon.Default -> SlockHomeNotificationIcon.Default
                SharedHomeNotificationIcon.WifiOff -> SlockHomeNotificationIcon.WifiOff
                SharedHomeNotificationIcon.CircleArrowUp -> SlockHomeNotificationIcon.CircleArrowUp
                SharedHomeNotificationIcon.Info -> SlockHomeNotificationIcon.Info
            }
    )

@Composable
private fun SharedHomeSearchPanel(
    state: SharedHomeSearchPanelState,
    homeState: SharedHomeState,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    onClose: (() -> Unit)? = null,
    interactiveSearchField: Boolean = true,
    includeTopSafeArea: Boolean = true,
    showSenderFilter: Boolean = true,
    initialOpenFilterId: String? = null,
    initialFocusInput: Boolean = false,
    onOpenItem: (InboxItem) -> Unit,
    onOpenEntity: (SearchEntityResult) -> Unit,
    bottomContentPadding: Dp,
    timeContext: AppShellThreadTimeContext? = null,
    shellScope: SlockAppShellScope = rememberSlockAppShellScope(),
    listState: LazyListState = rememberLazyListState(),
    onTitleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val channelFilters = remember(homeState) { homeSearchChannelFilters(homeState) }
    val entityResults = remember(state.query, homeState, state.senderFilters) {
        homeSearchEntityResults(state.query, homeState, state.senderFilters)
    }
    val projectedItems =
        remember(state.items, draftProjection) {
            state.items.map { item -> item.withComposerDraftProjection(draftProjection) }
        }
    val groupedResults = remember(projectedItems) { groupHomeSearchResults(projectedItems) }
    var openFilterId by remember(initialOpenFilterId) { mutableStateOf<String?>(null) }
    var pendingInitialOpenFilterId by remember(initialOpenFilterId) { mutableStateOf(initialOpenFilterId) }
    var senderAnchor by remember { mutableStateOf(Offset.Zero) }
    var channelAnchor by remember { mutableStateOf(Offset.Zero) }
    var timeAnchor by remember { mutableStateOf(Offset.Zero) }
    var sortAnchor by remember { mutableStateOf(Offset.Zero) }
    var senderSearch by remember { mutableStateOf("") }
    var channelSearch by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        onIntent(SharedHomeSearchSavedIntent.OpenSearch)
    }
    LaunchedEffect(initialOpenFilterId, senderAnchor, channelAnchor, timeAnchor, sortAnchor) {
        val pending = pendingInitialOpenFilterId ?: return@LaunchedEffect
        if (homeSearchFilterAnchorReady(pending, senderAnchor, channelAnchor, timeAnchor, sortAnchor)) {
            openFilterId = pending
            pendingInitialOpenFilterId = null
        }
    }
    SlockBackHandler(enabled = openFilterId != null) {
        openFilterId = null
        pendingInitialOpenFilterId = null
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
                autoFocus = initialFocusInput,
                topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
                onTitleClick = onTitleClick
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
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    val hasSearchRows = projectedItems.isNotEmpty() || entityResults.isNotEmpty()
                    if (state.loading && !hasSearchRows) {
                        SlockCenteredLoading(modifier = Modifier.fillParentMaxSize())
                    } else if (state.noPermissionCode != null || (state.errorCode != null && !hasSearchRows)) {
                        SharedHomePanelStatus(
                            errorCode = state.errorCode,
                            noPermissionCode = state.noPermissionCode,
                            empty = false,
                            emptyTitle = "",
                            emptyDetail = "",
                            onRetry = { onIntent(SharedHomeSearchSavedIntent.RefreshSearch) }
                        )
                    } else if (!state.searched && projectedItems.isEmpty() && entityResults.isEmpty()) {
                        SharedHomeSearchEmptyState(
                            title = "Search everything",
                            detail = "Search channels, DMs, people, agents, and message history."
                        )
                    } else if (state.empty || (state.searched && projectedItems.isEmpty() && entityResults.isEmpty())) {
                        val trimmedQuery = state.query.trim()
                        SharedHomeSearchEmptyState(
                            title = if (trimmedQuery.isNotBlank()) "No results for \"$trimmedQuery\"" else "No matching messages",
                            detail = if (trimmedQuery.isNotBlank()) "Try different keywords or a shorter phrase." else "Try a different filter set."
                        )
                    } else {
                        SharedHomeSearchResultsCount(
                            totalResults = state.totalCount + entityResults.size,
                            searching = state.querySearchPending || state.loading || state.refreshing
                        )
                    }
                }
                if (entityResults.isNotEmpty()) {
                    item {
                        SharedHomeSearchSectionLabel("Channels & DMs")
                    }
                    items(entityResults.size, key = { index -> entityResults[index].key }) { index ->
                        SharedHomeSearchEntityRow(
                            result = entityResults[index],
                            query = state.query,
                            agentStatusResolver = agentStatusResolver,
                            memberAvatarResolver = memberAvatarResolver,
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
                                    query = state.query,
                                    memberAvatarResolver = memberAvatarResolver,
                                    timeContext = timeContext,
                                    onOpen = { onOpenItem(group.item) }
                                )
                            }
                        }
                        is HomeSearchResultGroup.Thread -> {
                            item(key = group.key) {
                                SharedHomeThreadResultGroup(
                                    group = group,
                                    query = state.query,
                                    memberAvatarResolver = memberAvatarResolver,
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
                    selectedIds = state.selectedSenderIds.toSet(),
                    onToggle = {
                        onIntent(SharedHomeSearchSavedIntent.ToggleSearchSender(it))
                    },
                    onClear = { onIntent(SharedHomeSearchSavedIntent.ClearSearchSenders) },
                    onDismiss = { openFilterId = null },
                    multiSelect = true,
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

private fun homeSearchFilterAnchorReady(
    filterId: String,
    senderAnchor: Offset,
    channelAnchor: Offset,
    timeAnchor: Offset,
    sortAnchor: Offset
): Boolean =
    when (filterId) {
        "sender" -> senderAnchor != Offset.Zero
        "channel" -> channelAnchor != Offset.Zero
        "time" -> timeAnchor != Offset.Zero
        "sort" -> sortAnchor != Offset.Zero
        else -> false
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
    autoFocus: Boolean = false,
    topSafeAreaPadding: Dp = 0.dp,
    onTitleClick: (() -> Unit)? = null
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
            onClick = onTitleClick ?: onSubmit
        )
        SharedHomeSearchInput(
            query = query,
            loading = loading,
            interactive = interactive,
            autoFocus = autoFocus,
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
private fun SharedHomeSavedPanel(
    state: SharedHomeSavedPanelState,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    onClose: (() -> Unit)? = null,
    onOpenItem: (InboxItem) -> Unit,
    bottomContentPadding: Dp,
    includeTopSafeArea: Boolean = true,
    timeContext: AppShellThreadTimeContext? = null,
    shellScope: SlockAppShellScope = rememberSlockAppShellScope(),
    listState: LazyListState = rememberLazyListState(),
    onTitleClick: (() -> Unit)? = null,
    onCopyLink: (InboxItem) -> Unit = {},
    onCopyMarkdown: (InboxItem) -> Unit = {}
) {
    LaunchedEffect(Unit) {
        onIntent(SharedHomeSearchSavedIntent.OpenSaved)
    }
    var selectedItemKey by remember { mutableStateOf<String?>(null) }
    var activeMenu by remember { mutableStateOf<SharedHomeSavedContextMenuState?>(null) }
    LaunchedEffect(state.items) {
        val selected = selectedItemKey
        if (selected != null && state.items.none { it.key == selected }) {
            selectedItemKey = null
        }
        val menuItemKey = activeMenu?.item?.key
        if (menuItemKey != null && state.items.none { it.key == menuItemKey }) {
            activeMenu = null
        }
    }
    val pullToRefreshState = rememberPullToRefreshState(state.pullRefreshing)
    val projectedItems =
        remember(state.items, draftProjection) {
            state.items.map { item -> item.withComposerDraftProjection(draftProjection) }
        }
    if (state.loading && projectedItems.isEmpty()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SlockColor.SurfaceWhite)
        ) {
            SharedHomeSavedHeader(
                count = state.totalCount.takeIf { it > 0 } ?: projectedItems.size,
                onClose = onClose,
                refreshing = state.refreshing && !state.pullRefreshing,
                topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
                onTitleClick = onTitleClick
            )
            SlockCenteredLoading(modifier = Modifier.fillMaxWidth().weight(1f))
        }
        return
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
    ) {
        Column(Modifier.fillMaxSize()) {
            SharedHomeSavedHeader(
                count = state.totalCount.takeIf { it > 0 } ?: projectedItems.size,
                onClose = onClose,
                refreshing = state.refreshing && !state.pullRefreshing,
                topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
                onTitleClick = onTitleClick
            )
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(SlockColor.SurfaceWhite),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                if (slockPlatformSupportsKuiklyPullToRefreshItem()) {
                    pullToRefreshItem(
                        state = pullToRefreshState,
                        scrollState = listState,
                        onRefresh = { onIntent(SharedHomeSearchSavedIntent.PullRefreshSaved) },
                        content = { pullProgress, isRefreshing, _ ->
                            SlockRaftPullRefreshIndicator(
                                pullProgress = pullProgress,
                                isRefreshing = isRefreshing
                            )
                        }
                    )
                }
                val hasSavedRows = projectedItems.isNotEmpty()
                if (state.noPermissionCode != null || (state.errorCode != null && !hasSavedRows)) {
                    item {
                        SharedHomePanelStatus(
                            errorCode = state.errorCode,
                            noPermissionCode = state.noPermissionCode,
                            empty = false,
                            emptyTitle = "",
                            emptyDetail = "",
                            onRetry = { onIntent(SharedHomeSearchSavedIntent.RefreshSaved) }
                        )
                    }
                } else if (state.empty || (state.loaded && projectedItems.isEmpty())) {
                    item {
                        SharedHomeSavedEmptyState()
                    }
                }
                items(projectedItems.size, key = { index -> "saved-${projectedItems[index].key}" }) { index ->
                    val item = projectedItems[index]
                    SharedHomeSavedResultCard(
                        item = item,
                        selected = selectedItemKey == item.key || activeMenu?.item?.key == item.key,
                        memberAvatarResolver = memberAvatarResolver,
                        onUnsave = { onIntent(SharedHomeSearchSavedIntent.Unsave(item)) },
                        onOpen = {
                            selectedItemKey = item.key
                            onOpenItem(item)
                        },
                        onContextMenu = { anchor -> activeMenu = SharedHomeSavedContextMenuState(item, anchor) },
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
        activeMenu?.let { menu ->
            SlockBaseMenuOverlay(
                anchor = menu.anchor,
                items = SharedHomeSavedContextMenuItems,
                selectedId = "",
                uppercaseLabels = false,
                onDismiss = { activeMenu = null },
                onSelect = { actionId ->
                    val item = menu.item
                    activeMenu = null
                    when (actionId) {
                        SharedHomeSavedMenuActionCopyLink -> onCopyLink(item)
                        SharedHomeSavedMenuActionCopyMarkdown -> onCopyMarkdown(item)
                        SharedHomeSavedMenuActionRemove -> onIntent(SharedHomeSearchSavedIntent.Unsave(item))
                    }
                }
            )
        }
    }
}

private const val SharedHomeSavedMenuActionCopyLink = "copy-link"
private const val SharedHomeSavedMenuActionCopyMarkdown = "copy-markdown"
private const val SharedHomeSavedMenuActionRemove = "remove"

private val SharedHomeSavedContextMenuItems =
    listOf(
        SlockBaseMenuItem(SharedHomeSavedMenuActionCopyLink, "Copy Link", SlockLucideIconName.Link),
        SlockBaseMenuItem(SharedHomeSavedMenuActionCopyMarkdown, "Copy Markdown", SlockLucideIconName.Copy),
        SlockBaseMenuItem(SharedHomeSavedMenuActionRemove, "Remove from Saved", SlockLucideIconName.Bookmark, dividerBefore = true)
    )

private data class SharedHomeSavedContextMenuState(
    val item: InboxItem,
    val anchor: Offset
)

internal fun sharedHomeSavedPermalink(
    item: InboxItem,
    serverSlug: String,
    webBaseUrl: String? = null
): String? {
    val messageId = item.savedMessageIdOrNull()?.value?.takeIf { it.isNotBlank() } ?: return null
    val cleanServerSlug = serverSlug.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanWebBaseUrl = sharedSettingsResolvedWebBaseUrl(webBaseUrl)
    val isThread = item.kind == InboxItemKind.Thread
    val channelForLink = if (isThread) item.parentChannelId ?: item.channelId else item.channelId
    val routeKind =
        if (if (isThread) item.parentChannelKind == ConversationKind.DirectMessage else item.kind == InboxItemKind.DirectMessage) {
            "dm"
        } else {
            "channel"
        }
    val params =
        if (isThread) {
            val parentMessageId = item.parentMessageId?.value?.takeIf { it.isNotBlank() }
            if (parentMessageId != null) {
                "msg=$messageId&thread=${channelForLink.value}%3A$parentMessageId"
            } else {
                "msg=$messageId"
            }
        } else {
            "msg=$messageId"
        }
    return "$cleanWebBaseUrl/s/$cleanServerSlug/$routeKind/${channelForLink.value}?$params"
}

private fun InboxItem.sharedHomeSavedMarkdownContent(): String =
    latestActivityPreview ?: lastMessagePreview ?: parentMessagePreview ?: ""

@Composable
private fun SharedHomeSavedEmptyState() {
    SlockEmptyState(
        title = "No saved messages yet",
        detail = "Save messages by clicking the bookmark icon on any message.",
        iconName = SlockLucideIconName.Bookmark,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(300.dp)
    )
}

@Composable
private fun SharedHomeSavedHeader(
    count: Int,
    onClose: (() -> Unit)?,
    refreshing: Boolean,
    topSafeAreaPadding: Dp = 0.dp,
    onTitleClick: (() -> Unit)? = null
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
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(enabled = onTitleClick != null) { onTitleClick?.invoke() },
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "Saved",
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$count saved",
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (refreshing) {
                SlockHeaderRefreshIndicator()
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
            state.selectedSenderIds.isNotEmpty() ||
            state.selectedChannelId != null ||
            state.timeRange != SearchTimeRange.Any
    val selectedChannel = channelFilters.firstOrNull { it.id == state.selectedChannelId }
    val senderActive = state.selectedSenderIds.isNotEmpty()
    val channelActive = state.selectedChannelId != null
    val timeActive = state.timeRange != SearchTimeRange.Any
    val sortActive = state.sort != SearchSort.Relevance
    SharedHomeSearchControlsBlock {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SearchControlsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showSenderFilter) {
                item {
                    SlockAnchoredToolbarChip(
                        label = state.senderFilterLabel(),
                        active = senderActive,
                        enabled = true,
                        uppercase = !senderActive,
                        iconName = SlockLucideIconName.CircleUser,
                        activeBackgroundColor = SlockColor.BrandYellow,
                        activeShadowOffset = SearchControlShadowOffset,
                        reserveActiveShadowSpace = true,
                        onAnchorChanged = senderAnchorChanged,
                        onClick = { anchor ->
                            senderAnchorChanged(anchor)
                            onOpenFilter("sender")
                        }
                    )
                }
                item {
                    SlockAnchoredToolbarChip(
                        label = selectedChannel?.label ?: "Channel",
                        active = channelActive,
                        enabled = true,
                        uppercase = selectedChannel == null,
                        iconName = selectedChannel?.iconName ?: SlockLucideIconName.Hash,
                        activeBackgroundColor = SlockColor.BrandYellow,
                        activeShadowOffset = SearchControlShadowOffset,
                        reserveActiveShadowSpace = true,
                        onAnchorChanged = channelAnchorChanged,
                        onClick = { anchor ->
                            channelAnchorChanged(anchor)
                            onOpenFilter("channel")
                        }
                    )
                }
                item {
                    SlockAnchoredToolbarChip(
                        label = state.timeRange.searchLabel,
                        active = timeActive,
                        enabled = true,
                        uppercase = false,
                        iconName = SlockLucideIconName.Calendar,
                        activeBackgroundColor = SlockColor.BrandYellow,
                        activeShadowOffset = SearchControlShadowOffset,
                        reserveActiveShadowSpace = true,
                        onAnchorChanged = timeAnchorChanged,
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
                        active = channelActive,
                        enabled = true,
                        uppercase = selectedChannel == null,
                        iconName = selectedChannel?.iconName ?: SlockLucideIconName.Hash,
                        activeBackgroundColor = SlockColor.BrandYellow,
                        activeShadowOffset = SearchControlShadowOffset,
                        reserveActiveShadowSpace = true,
                        onAnchorChanged = channelAnchorChanged,
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
                        active = timeActive,
                        enabled = true,
                        uppercase = false,
                        iconName = SlockLucideIconName.Calendar,
                        activeBackgroundColor = SlockColor.BrandYellow,
                        activeShadowOffset = SearchControlShadowOffset,
                        reserveActiveShadowSpace = true,
                        onAnchorChanged = timeAnchorChanged,
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
                    active = sortActive,
                    enabled = true,
                    uppercase = false,
                    iconName = SlockLucideIconName.ArrowUpDown,
                    activeBackgroundColor = SlockColor.BrandYellow,
                    activeShadowOffset = SearchControlShadowOffset,
                    reserveActiveShadowSpace = true,
                    onAnchorChanged = sortAnchorChanged,
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
    errorCode: String?,
    noPermissionCode: String?,
    empty: Boolean,
    emptyTitle: String,
    emptyDetail: String,
    onRetry: () -> Unit
) {
    when {
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
private fun SharedHomeSavedSelectedSurface(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(end = SavedSelectedRowShadowOffset, bottom = SavedSelectedRowShadowOffset)
    ) {
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = SavedSelectedRowShadowOffset, y = SavedSelectedRowShadowOffset)
                        .background(SlockColor.ShadowBlack)
            )
        }
        content(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 122.dp)
                .background(SlockColor.SurfaceWhite)
                .border(
                    SlockStroke.Default,
                    if (selected) SlockColor.BorderBlack else SearchLightRowBorderColor
                )
        )
    }
}

@Composable
private fun SharedHomeSavedResultCard(
    item: InboxItem,
    selected: Boolean,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    onUnsave: () -> Unit,
    onOpen: () -> Unit,
    onContextMenu: (Offset) -> Unit,
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
    val contentTopLeft = remember { SlockMutableOffsetRef() }
    SharedHomeSavedSelectedSurface(
        selected = selected,
        modifier =
            Modifier
                .padding(horizontal = SearchMessagesHorizontalPadding, vertical = 4.dp)
                .fillMaxWidth()
    ) { surfaceModifier ->
        Row(
            modifier =
                surfaceModifier
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .onGloballyPositioned { coordinates -> contentTopLeft.update(coordinates.positionInRoot()) }
                        .pointerInput(onOpen, onContextMenu) {
                            detectTapGestures(
                                onTap = { onOpen() },
                                onLongPress = { localOffset ->
                                    val topLeft = contentTopLeft.value
                                    onContextMenu(Offset(topLeft.x + localOffset.x, topLeft.y + localOffset.y))
                                }
                            )
                        },
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
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp),
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
                    if (item.hasDraft) {
                        SharedHomeDraftPencilIcon()
                    }
                    sender?.let {
                        SharedHomeSavedSenderMeta(
                            senderName = it,
                            avatarWireValue = item.resolvedSenderAvatarWireValueForPreview(memberAvatarResolver),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    time?.let { t ->
                        Text(
                            text = t.formatSavedTimestamp(timeContext),
                            style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp),
                            color = SlockColor.TextBlack.copy(alpha = 0.4f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = preview,
                    style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SharedHomeSavedUnsaveButton(onClick = onUnsave)
        }
    }
}

@Composable
private fun SharedHomeSavedUnsaveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val size = 31.dp
    val shadowOffset = 3.dp
    Box(
        modifier =
            modifier
                .size(size + shadowOffset)
    ) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(SlockColor.ShadowBlack)
        )
        Box(
            modifier =
                Modifier
                    .size(size)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                SlockLucideIcon(SlockLucideIconName.Bookmark, color = SlockColor.TextBlackArgb, modifier = Modifier.size(16.dp))
                Box(
                    modifier =
                        Modifier
                            .width(9.dp)
                            .height(2.dp)
                            .background(SlockColor.TextBlack)
                )
            }
        }
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
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp),
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

@Composable
private fun InboxItem.resolvedSenderAvatarWireValueForPreview(
    memberAvatarResolver: SlockMemberAvatarResolver
): String? {
    val senderId = previewSenderMemberId()
    val memberAvatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, senderId)
    return memberAvatarWireValue ?: senderAvatarWireValueForPreview()
}

private fun InboxItem.previewSenderMemberId(): String? =
    when {
        latestActivitySenderKind != null && latestActivitySenderId != null -> latestActivitySenderId
        lastMessageSenderKind != null && lastMessageSenderId != null -> lastMessageSenderId
        else -> latestActivitySenderId ?: lastMessageSenderId
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
private fun SharedHomeSearchResultsCount(
    totalResults: Int,
    searching: Boolean
) {
    Text(
        text = if (searching) "SEARCHING..." else "$totalResults results",
        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
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
    query: String,
    agentStatusResolver: SlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
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
        SharedHomeSearchEntityLeadingAvatar(result, agentStatusResolver, memberAvatarResolver)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                HomeSearchHighlightedText(
                    text = result.title,
                    query = query,
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SharedHomeEntityTypeBadge(result.type)
            }
            HomeSearchHighlightedText(
                text = result.subtitle,
                query = query,
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
    SharedHomeOutlinedMetaBadge(
        label =
            when (type) {
                SearchEntityType.Channel -> "Channel"
                SearchEntityType.DirectMessage -> "DM"
                SearchEntityType.Human -> "DM"
                SearchEntityType.Agent -> "DM"
            },
        compact = true
    )
}

@Composable
private fun SharedHomeThreadResultGroup(
    group: HomeSearchResultGroup.Thread,
    query: String,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
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
                if (firstItem.hasDraft) {
                    SharedHomeDraftPencilIcon(compact = true)
                }
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
            HomeSearchHighlightedText(
                text = group.title,
                query = query,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 18.sp),
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
                query = query,
                nested = true,
                memberAvatarResolver = memberAvatarResolver,
                timeContext = timeContext,
                onOpen = { onOpenItem(item) }
            )
        }
    }
}

@Composable
private fun SharedHomeSearchMessageResultRow(
    item: InboxItem,
    query: String,
    nested: Boolean = false,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
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
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(if (nested) 6.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!nested) {
                    Text(
                        text = searchResultSourceLabel(item),
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (item.kind == InboxItemKind.Thread && !nested) {
                    SharedHomeMiniMetaBadge("thread", SlockLucideIconName.MessageSquare)
                }
                if (item.hasDraft) {
                    SharedHomeDraftPencilIcon(compact = nested)
                }
                if (!sender.isNullOrBlank()) {
                    SharedHomeSearchSenderMeta(
                        sender = sender,
                        avatarWireValue = item.resolvedSenderAvatarWireValueForPreview(memberAvatarResolver),
                        modifier = Modifier.weight(1f, fill = false),
                        compact = compactMeta
                    )
                } else if (nested) {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (!time.isNullOrBlank()) {
                Text(
                    text = time.formatSavedTimestamp(timeContext),
                    style = SlockType.Caption.copy(fontSize = if (compactMeta) 9.sp else 10.sp, lineHeight = if (compactMeta) 11.sp else 12.sp, letterSpacing = 0.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            }
        }
        HomeSearchHighlightedText(
            text = preview,
            query = query,
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
    SharedHomeOutlinedMetaBadge(label = label, iconName = iconName, compact = compact)
}

@Composable
private fun SharedHomeDraftPencilIcon(
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    SlockLucideIcon(
        SlockLucideIconName.Pencil,
        modifier = modifier.size(if (compact) 10.dp else 12.dp),
        color = SlockColor.TextMutedGrayArgb
    )
}

@Composable
private fun SharedHomeOutlinedMetaBadge(
    label: String,
    iconName: SlockLucideIconName? = null,
    compact: Boolean = false
) {
    Row(
        modifier =
            Modifier
                .height(if (compact) 16.dp else 18.dp)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.EmptyStateGray)
                .padding(horizontal = if (compact) 4.dp else 5.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconName != null) {
            SlockLucideIcon(iconName, color = "#99000000", modifier = Modifier.size(if (compact) 9.dp else 10.dp))
        }
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
private fun HomeSearchHighlightedText(
    text: String,
    query: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val highlightedText = remember(text, query) { homeSearchHighlightedAnnotatedString(text, query) }
    Text(
        text = highlightedText,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}

internal fun homeSearchHighlightedAnnotatedString(
    text: String,
    query: String
): AnnotatedString {
    val ranges = homeSearchHighlightRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val highlightStyle =
            SpanStyle(
                background = SlockColor.BrandYellow,
                color = SlockColor.TextBlack
            )
        ranges.forEach { range ->
            addStyle(
                highlightStyle,
                start = range.first,
                end = range.last + 1
            )
        }
    }
}

internal fun homeSearchHighlightRanges(
    text: String,
    query: String
): List<IntRange> {
    if (text.isBlank()) return emptyList()
    val terms =
        query
            .trim()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedByDescending { it.length }
    if (terms.isEmpty()) return emptyList()

    val lowerText = text.lowercase()
    val occupied = BooleanArray(text.length)
    val ranges = mutableListOf<IntRange>()
    terms.forEach { term ->
        val lowerTerm = term.lowercase()
        var start = lowerText.indexOf(lowerTerm)
        while (start >= 0) {
            val endExclusive = start + lowerTerm.length
            if (start < endExclusive && (start until endExclusive).none { occupied[it] }) {
                ranges += start until endExclusive
                for (index in start until endExclusive) {
                    occupied[index] = true
                }
            }
            start = lowerText.indexOf(lowerTerm, startIndex = start + lowerTerm.length)
        }
    }
    return ranges.sortedBy { it.first }
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
            textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp),
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
    return snapshot.resolveHomeConversationSummary(
        listOfNotNull(
            message.threadId.value,
            message.channelId?.value,
            message.threadChannelId?.value
        )
    )
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
    val memberId: String?,
    val conversation: ConversationSummary?,
    val avatarWireValue: String? = null,
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

private fun SharedHomeSearchPanelState.senderFilterLabel(): String {
    val selectedIds = selectedSenderIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (selectedIds.isEmpty()) return "From"
    val selected = senderFilters.filter { it.id in selectedIds.toSet() }
    return when (selectedIds.size) {
        1 -> "From: ${selected.singleOrNull()?.let { if (it.isSelf) "Me" else it.displayLabel } ?: selectedIds.first()}"
        else -> "From: ${selectedIds.size} selected"
    }
}

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
                memberId to conversation.toConversationSummary()
            }
            .toMap()
    val conversationResults =
        conversations.map { it.toConversationSummary() }.mapNotNull { conversation ->
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
                memberId = conversation.presenceMemberId?.value,
                conversation = conversation,
                avatarWireValue = null,
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
                memberId = sender.id,
                conversation = conversation,
                avatarWireValue = sender.avatarWireValue ?: "random:${sender.id}",
                showPresence = sender.kind == MemberKind.Agent
            )
        }
    return (conversationResults + memberResults)
        .distinctBy { it.key }
        .take(5)
}

private fun HomeDirectMessageRow.isAgentDirectMessage(): Boolean =
    kind == ConversationKind.DirectMessage &&
        directMessagePeerType.equals("agent", ignoreCase = true)

private fun ConversationSummary.isAgentDirectMessage(): Boolean =
    kind == ConversationKind.DirectMessage &&
        directMessagePeerType.equals("agent", ignoreCase = true)

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

private fun String.searchAvatarSeed(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "default" }

@Composable
private fun SharedHomeSessionShell(
    session: StoredAuthSession,
    serverSwitcherExpanded: Boolean,
    serverUnreadCounts: Map<String, Int> = emptyMap(),
    prefersChineseCommunity: Boolean = false,
    bottomContentPadding: Dp = SlockSpacing.Lg,
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
        servers = session.servers.map { it.toServerSummary().toSharedSwitcherItem(serverUnreadCounts) },
        channels = emptyList(),
        directMessages = emptyList(),
        serverSwitcherExpanded = serverSwitcherExpanded,
        prefersChineseCommunity = prefersChineseCommunity,
        showBottomBar = false,
        bottomContentPadding = bottomContentPadding,
        outerPadding = 0.dp,
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

private fun ServerSummary.toSharedSwitcherItem(serverUnreadCounts: Map<String, Int> = emptyMap()): SlockServerSwitcherItem =
    SlockServerSwitcherItem(
        id = id.value,
        name = name,
        slug = slug ?: description?.removePrefix("/") ?: id.value,
        unreadCount = serverUnreadCounts[id.value] ?: unreadCount
    )

internal data class SharedHomeConversationRowSeed(
    val id: String,
    val kind: String,
    val unreadCount: Int = 0,
    val lastMessageAt: String? = null,
    val peerType: String? = null,
    val fallbackPresenceMemberId: String? = null,
    val fallbackName: String = "",
    val fallbackChannelType: String? = null,
    val fallbackDescription: String? = null,
    val fallbackJoined: Boolean = true,
    val fallbackArchived: Boolean = false
)

internal data class SharedHomeConversationRowProjector(
    val directoryProjections: Map<String, SharedConversationDirectoryProjection> = emptyMap(),
    val draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty
) {
    fun project(conversation: HomeConversationRow): SlockHomeConversationItem =
        project(conversation.toSharedHomeConversationRowSeed())

    fun project(seed: SharedHomeConversationRowSeed): SlockHomeConversationItem =
        seed.toSharedHomeConversationItem(
            directoryProjection = directoryProjections[seed.id],
            draftProjection = draftProjection
        )
}

private fun HomeConversationRow.toSharedHomeConversationRowSeed(): SharedHomeConversationRowSeed =
    SharedHomeConversationRowSeed(
        id = threadId.value,
        kind = kind.name.lowercase(),
        unreadCount = unreadCount,
        lastMessageAt = lastMessageAt,
        peerType = directMessagePeerType?.takeIf { it.isNotBlank() },
        fallbackPresenceMemberId = presenceMemberId?.value,
        fallbackName = name,
        fallbackChannelType = channelType?.takeIf { it.isNotBlank() },
        fallbackDescription = subtitle?.takeIf { it.isNotBlank() },
        fallbackJoined = joined && permissions.canRead,
        fallbackArchived = archived
    )

private fun SharedHomeConversationRowSeed.toSharedHomeConversationItem(
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    directoryProjection: SharedConversationDirectoryProjection? = null
): SlockHomeConversationItem {
    val title = resolvedDirectoryTitle(directoryProjection)
    return SlockHomeConversationItem(
        id = id,
        name = title,
        kind = kind,
        channelType = directoryProjection?.channelKind?.takeIf { it.isNotBlank() } ?: fallbackChannelType,
        unreadCount = unreadCount,
        lastMessageAt = lastMessageAt,
        description = directoryProjection?.subtitle ?: fallbackDescription,
        peerType = peerType,
        presenceMemberId = directoryProjection?.peerMemberId ?: fallbackPresenceMemberId,
        hasDraft = draftProjection.hasRootDraft(id),
        joined = directoryProjection?.joined ?: fallbackJoined,
        archived = directoryProjection?.archived ?: fallbackArchived
    )
}

private fun SharedHomeConversationRowSeed.resolvedDirectoryTitle(
    directoryProjection: SharedConversationDirectoryProjection?
): String =
    directoryProjection
        ?.title
        ?.takeUnless { title -> title.isGenericHomeDirectoryTitle(directoryProjection) }
        ?: fallbackName

private fun String.isGenericHomeDirectoryTitle(
    directoryProjection: SharedConversationDirectoryProjection
): Boolean {
    val candidate = threadHeaderCandidateTitle(this) ?: return true
    val identityIds =
        directoryProjection.aliases +
            directoryProjection.canonicalChannelId +
            setOfNotNull(directoryProjection.parentChannelId, directoryProjection.parentMessageId)
    return candidate.isGenericThreadHeaderFallbackLabel() ||
        threadHeaderIdentityLikeTitle(candidate, identityIds)
}

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

private data class AppShellNotificationThreadLaunchRequest(
    val threadId: String,
    val messageId: String,
    val channelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val category: String? = null
)

private fun JSONObject.appShellNotificationThreadLaunchRequest(): AppShellNotificationThreadLaunchRequest? {
    val threadId = optString(AppShellNotificationThreadIdKey).trim().takeIf { it.isNotBlank() }
    val messageId = optString(AppShellNotificationMessageIdKey).trim().takeIf { it.isNotBlank() }
    if (threadId != null && messageId != null) {
        return AppShellNotificationThreadLaunchRequest(
            threadId = threadId,
            messageId = messageId,
            channelId = optString(AppShellNotificationChannelIdKey).trim().takeIf { it.isNotBlank() },
            parentChannelId = optString(AppShellNotificationParentChannelIdKey).trim().takeIf { it.isNotBlank() },
            parentMessageId = optString(AppShellNotificationParentMessageIdKey).trim().takeIf { it.isNotBlank() },
            category = optString(AppShellNotificationCategoryKey).trim().takeIf { it.isNotBlank() }
        )
    }
    return legacyAppShellNotificationThreadLaunchRequest()
}

private fun JSONObject.legacyAppShellNotificationThreadLaunchRequest(): AppShellNotificationThreadLaunchRequest? {
    val source = optString(AppShellNotificationThreadPayloadKey).takeIf { it.isNotBlank() } ?: return null
    val payload = runCatching {
        AppShellNotificationLaunchJson.decodeFromString(WireThreadLaunchPayload.serializer(), source)
    }.getOrNull()?.takeIf { it.hasRouteIdentity() } ?: return null
    val threadId =
        payload.sourceThreadId?.takeIf { it.isNotBlank() }
            ?: payload.streamChannelId?.takeIf { it.isNotBlank() }
            ?: payload.threadId?.takeIf { it.isNotBlank() }
            ?: payload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return null
    val messageId =
        payload.focusedMessageId?.takeIf { it.isNotBlank() }
            ?: payload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: return null
    return AppShellNotificationThreadLaunchRequest(
        threadId = threadId,
        messageId = messageId,
        channelId = payload.threadId?.takeIf { it.isNotBlank() },
        parentChannelId = payload.parentChannelId?.takeIf { it.isNotBlank() },
        parentMessageId = payload.parentMessageId?.takeIf { it.isNotBlank() }
    )
}

private fun JSONObject.appShellNotificationServerId(): String? =
    optString(AppShellNotificationServerIdKey).trim().takeIf { it.isNotBlank() }

private fun SharedHomeState.notificationThreadConversation(
    request: AppShellNotificationThreadLaunchRequest
): ConversationSummary? {
    val snapshot = (content as? SharedHomeContentState.Content)?.snapshot ?: return null
    return snapshot.resolveHomeConversationSummary(
        listOfNotNull(
            request.threadId,
            request.channelId,
            request.parentMessageId,
            request.parentChannelId
        )
    )
}

private fun ConversationSummary.notificationAnchorMessageId(
    request: AppShellNotificationThreadLaunchRequest
): String =
    if (
        kind != ConversationKind.Thread &&
        request.parentChannelId?.isNotBlank() == true &&
        request.parentMessageId?.isNotBlank() == true
    ) {
        request.parentMessageId
    } else {
        request.messageId
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

internal fun shouldApplyHomeAuthErrorGate(
    errorCode: String?,
    authGateState: SharedAuthGateState,
    cachedSession: StoredAuthSession?
): Boolean {
    if (!ScreenError(errorCode.orEmpty(), retryable = false).isAuthSessionError()) return false
    if (authGateState == SharedAuthGateState.Restoring) return false
    if (cachedSession != null) return false
    return true
}

private fun SharedAuthGateState.authGateDiagnosticsName(): String =
    when (this) {
        SharedAuthGateState.Restoring -> "restoring"
        SharedAuthGateState.AuthRequired -> "auth_required"
        is SharedAuthGateState.ServerRequired -> "server_required"
        is SharedAuthGateState.Ready -> "ready"
        is SharedAuthGateState.Error -> "error"
    }

internal fun buildAppShellThreadPayload(
    conversation: ThreadLaunchConversationIdentity,
    timeContext: AppShellThreadTimeContext? = null,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    threadRowAffordances: Set<ThreadRowAffordancePreference> = ThreadRowAffordancePreference.Default,
    anchorMessageId: String? = null,
    originFallback: ThreadLaunchOriginFallback? = null,
    accessState: ThreadLaunchAccessState = ThreadLaunchAccessState()
): KuiklyThreadScreenPayload {
    val surfaceKind = conversation.threadSurfaceKind()
    val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
    val streamChannelId =
        if (isReplyThread) {
            conversation.threadStreamChannelId()
        } else {
            conversation.channelId?.value ?: conversation.threadId.value
        }
    val parentChannelId =
        if (isReplyThread) {
            conversation.threadParentChannelId()
        } else {
            null
        }
    val parentMessageId =
        if (isReplyThread) {
            conversation.threadParentMessageId()
        } else {
            null
        }
    val routeChannelKind = conversation.resolveThreadRouteIdentity().channelKind
    val parentSurfaceKind =
        if (isReplyThread) {
            conversation.threadParentSurfaceKind() ?: KuiklyThreadSurfaceKind.Channel
        } else {
            null
        }
    val parentChannelKind =
        if (isReplyThread) {
            conversation.threadParentChannelKind()
        } else {
            null
        }
    val originParentMessageId =
        if (isReplyThread) {
            parentMessageId
        } else {
            null
        }
    val canSend = accessState.joined && accessState.permissions.canSend
    val taskChannelKind = if (isReplyThread) parentChannelKind else routeChannelKind
    val supportsChannelTasks =
        accessState.permissions.canCreateTask &&
            !taskChannelKind.equals("joint", ignoreCase = true)
    val composer =
        KuiklyThreadComposerPayload(
            surfaceKind = surfaceKind,
            enabled = canSend,
            showAsTaskToggle = supportsChannelTasks,
            mentionChannelId = parentChannelId,
            parentMessageId = parentMessageId,
            streamChannelId = streamChannelId
        )
    val launchHeaderContext =
        SharedThreadHeaderContextStore.launchContext(surfaceKind, originParentMessageId)
    val originHeaderContext =
        SharedThreadHeaderContextStore.launchOriginContext(
            surfaceKind = surfaceKind,
            routeIdentity = conversation.resolveThreadRouteIdentity(),
            originMessage = null,
            parentMessageId = parentMessageId,
            originFallback = originFallback
        )
    val payload = KuiklyThreadScreenPayload(
        instanceId = "shared-shell-thread-${conversation.threadId.value}",
        title = launchHeaderContext.title,
        subtitle = null,
        status = "loading",
        surfaceKind = surfaceKind,
        channelKind = routeChannelKind ?: conversation.channelType?.takeIf { it.isNotBlank() } ?: conversation.kind.name.lowercase(),
        sourceThreadId = streamChannelId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        parentSurfaceKind = parentSurfaceKind,
        parentChannelKind = parentChannelKind,
        memberScopedMentions = conversation.usesMemberScopedMentions(),
        originParentMessageId = originParentMessageId,
        focusedMessageId = anchorMessageId?.takeIf { it.isNotBlank() },
        selectedTab = "chat",
        navigationLabel = "Back",
        showModeTabs = !isReplyThread,
        showReplyActions = !isReplyThread,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        showReactionActions = threadRowAffordances.showReactionActions(),
        showInlineSaveAction = threadRowAffordances.showInlineSaveAction(),
        showOpenInChannelAction = isReplyThread && parentMessageId != null && parentSurfaceKind != null,
        joined = accessState.joined,
        canSend = canSend,
        membersCount = 0,
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
    ).copy(
        originMessage = originHeaderContext.originMessage,
        originPreview = originHeaderContext.originPreview,
        originParentMessageId = originHeaderContext.originParentMessageId,
        originStatus = originHeaderContext.originStatus,
        originErrorCode = originHeaderContext.originErrorCode
    )
    return payload
}

internal data class ThreadLaunchAccessState(
    val joined: Boolean = true,
    val permissions: ConversationPermissions = ConversationPermissions()
)

private fun Pager.openStandaloneTaskThreadPage(
    task: TaskSummary,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    threadRowAffordances: Set<ThreadRowAffordancePreference>
) {
    openStandaloneThreadLaunchPayload(
        buildStandaloneTaskThreadLaunchPayload(
            task = task,
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            threadRowAffordances = threadRowAffordances
        )
    )
}

private fun Pager.openStandaloneThreadLaunchPayload(launchPayload: WireThreadLaunchPayload) {
    val pageData = threadLaunchPageData(launchPayload)
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(THREAD_PAGE, pageData)
}

private fun Pager.closeHomeSecondaryPage() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
}

private fun Pager.openHomeSecondaryPage(destination: String, timeContext: AppShellThreadTimeContext?) {
    val retainedDestination = retainedHomeSecondaryDestination(destination) ?: return
    val pageName =
        when (retainedDestination) {
            HomeDestinationActivity -> HOME_ACTIVITY_PAGE
            HomeDestinationSearch -> HOME_SEARCH_PAGE
            HomeDestinationSaved -> HOME_SAVED_PAGE
            else -> return
        }
    val pageData =
        JSONObject().apply {
            put("pageName", pageName)
            put("destination", retainedDestination)
            if (retainedDestination == HomeDestinationSearch) {
                put(HOME_SEARCH_FOCUS_INPUT, true)
            }
            put("localOffsetMinutes", timeContext?.localOffsetMinutes ?: 0)
            timeContext?.todayEpochDay?.let { put("todayEpochDay", it) }
            timeContext?.nowEpochMillis?.let { put("nowEpochMillis", it) }
            put("timeFormat", timeContext?.timeFormat ?: SlockTimeFormat24Hour)
        }
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(pageName, pageData)
}

private fun Pager.openStandaloneHomeMessageRequest(
    bridge: build.raft.app.base.BridgeModule,
    request: HomeMessageOpenRequest,
    source: String,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    threadRowAffordances: Set<ThreadRowAffordancePreference>
) {
    openStandaloneHomeConversation(
        bridge = bridge,
        conversation = request.conversationIdentity,
        source = source,
        timeContext = timeContext,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        threadRowAffordances = threadRowAffordances,
        anchorMessageId = request.anchorMessageId
    )
}

private fun Pager.openStandaloneHomeConversation(
    bridge: build.raft.app.base.BridgeModule,
    conversation: ThreadLaunchConversationIdentity,
    source: String,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    threadRowAffordances: Set<ThreadRowAffordancePreference>,
    anchorMessageId: String? = null
) {
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
    val launchPayload =
        buildStandaloneConversationThreadLaunchPayload(
            conversation = conversation,
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            threadRowAffordances = threadRowAffordances,
            anchorMessageId = anchorMessageId
        )
    bridge.log(
        "home_open_thread_launch source=$source threadId=${conversation.threadId.value} " +
            "sourceThreadId=${launchPayload.sourceThreadId.orEmpty()} surface=${launchPayload.surfaceKind} " +
            "parentChannelId=${launchPayload.parentChannelId.orEmpty()} parentMessageId=${launchPayload.parentMessageId.orEmpty()} " +
            "anchorMessageId=${anchorMessageId.orEmpty()} originParentMessageId=${launchPayload.originParentMessageId.orEmpty()}"
    )
    openStandaloneThreadLaunchPayload(launchPayload)
}

private fun Pager.openHomeSearchEntity(
    bridge: build.raft.app.base.BridgeModule,
    entity: SearchEntityResult,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    threadRowAffordances: Set<ThreadRowAffordancePreference>
) {
    val conversation = entity.conversation
    if (conversation != null) {
        openStandaloneHomeConversation(
            bridge = bridge,
            conversation = conversation.toThreadLaunchConversationIdentity(),
            source = "home_search_entity",
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            threadRowAffordances = threadRowAffordances
        )
        return
    }
    val memberId = entity.memberId?.takeIf { it.isNotBlank() } ?: return
    val instanceId = "home-search-member-$memberId"
    val payload =
        JSONObject().apply {
            put("instanceId", instanceId)
            put("memberId", memberId)
            put("localOffsetMinutes", timeContext?.localOffsetMinutes ?: 0)
            timeContext?.todayEpochDay?.let { put("todayEpochDay", it) }
            timeContext?.nowEpochMillis?.let { put("nowEpochMillis", it) }
            put("timeFormat", timeContext?.timeFormat ?: SlockTimeFormat24Hour)
        }
    val pageData =
        JSONObject().apply {
            put("pageName", MEMBER_DETAIL_PAGE)
            put("payload", payload.toString())
            put("instanceId", instanceId)
        }
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(MEMBER_DETAIL_PAGE, pageData)
}

internal fun buildStandaloneConversationThreadLaunchPayload(
    conversation: ThreadLaunchConversationIdentity,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    threadRowAffordances: Set<ThreadRowAffordancePreference> = ThreadRowAffordancePreference.Default,
    anchorMessageId: String? = null
): WireThreadLaunchPayload {
    val surfaceKind = conversation.threadSurfaceKind()
    val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
    val streamChannelId =
        if (isReplyThread) {
            conversation.threadStreamChannelId()
        } else {
            conversation.channelId?.value ?: conversation.threadId.value
        }
    val parentChannelId = if (isReplyThread) conversation.threadParentChannelId() else null
    val parentMessageId = if (isReplyThread) conversation.threadParentMessageId() else null
    return WireThreadLaunchPayload(
        instanceId = "shared-shell-thread-${conversation.threadId.value}",
        surfaceKind = surfaceKind,
        channelKind = conversation.resolveThreadRouteIdentity().channelKind,
        threadId =
            when (surfaceKind) {
                KuiklyThreadSurfaceKind.ReplyThread,
                KuiklyThreadSurfaceKind.DirectMessage -> streamChannelId
                else -> null
            },
        sourceThreadId = streamChannelId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        parentSurfaceKind = if (isReplyThread) conversation.threadParentSurfaceKind() ?: KuiklyThreadSurfaceKind.Channel else null,
        parentChannelKind = if (isReplyThread) conversation.threadParentChannelKind() else null,
        originParentMessageId = if (isReplyThread) parentMessageId else null,
        focusedMessageId = anchorMessageId?.takeIf { it.isNotBlank() },
        selectedTab = "chat",
        navigationLabel = "Back",
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        showReactionActions = threadRowAffordances.showReactionActions(),
        showInlineSaveAction = threadRowAffordances.showInlineSaveAction(),
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: SlockTimeFormat24Hour
    )
}

private fun Set<ThreadRowAffordancePreference>.showReactionActions(): Boolean =
    ThreadRowAffordancePreference.Reactions in this

private fun Set<ThreadRowAffordancePreference>.showInlineSaveAction(): Boolean =
    ThreadRowAffordancePreference.Saved in this

internal fun buildStandaloneTaskThreadLaunchPayload(
    task: TaskSummary,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    threadRowAffordances: Set<ThreadRowAffordancePreference> = ThreadRowAffordancePreference.Default
): WireThreadLaunchPayload {
    val streamThreadId = task.threadId?.value?.takeIf { it.isNotBlank() }
    val parentMessageId =
        task.parentMessageId?.value?.takeIf { it.isNotBlank() }
            ?: task.messageId?.value?.takeIf { it.isNotBlank() }
    val focusedMessageId =
        task.messageId
            ?.value
            ?.takeIf { it.isNotBlank() && it != parentMessageId }
    return WireThreadLaunchPayload(
        instanceId = "task-thread-${task.id.value}",
        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
        threadId = streamThreadId,
        sourceThreadId = streamThreadId,
        streamChannelId = streamThreadId,
        parentChannelId = task.channelId?.value?.takeIf { it.isNotBlank() },
        parentMessageId = parentMessageId,
        parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
        originParentMessageId = parentMessageId,
        focusedMessageId = focusedMessageId,
        selectedTab = "chat",
        navigationLabel = "Back",
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        showReactionActions = threadRowAffordances.showReactionActions(),
        showInlineSaveAction = threadRowAffordances.showInlineSaveAction(),
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: SlockTimeFormat24Hour
    )
}

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

private fun RealtimeEvent.performanceMarkerPhase(): String =
    when (this) {
        is MemberPresenceUpdated -> "realtime_presence_apply"
        is MessageNew,
        is MessageUpdated,
        is ThreadUpdated,
        is TaskCreated,
        is TaskUpdated,
        is TaskDeleted,
        is DirectMessageCreated,
        is SyncResumeResponse -> "realtime_content_apply"
        is RealtimeRefreshRequired -> "realtime_refresh_apply"
        is ServerMembershipRemoved,
        is ServerDeleted,
        is ServerPlanUpdated,
        is AgentCreated -> "realtime_membership_apply"
        is Heartbeat -> "realtime_heartbeat"
    }

private fun RealtimeEvent.shouldEmitAppShellRouteMarker(): Boolean =
    this !is Heartbeat && this !is MemberPresenceUpdated

internal fun decodeSharedShellRealtimeEvent(
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

internal fun resolveSharedShellRealtimeServerId(
    eventData: JSONObject,
    event: RealtimeEvent,
    session: StoredAuthSession?
): String? =
    eventData.optString("serverId").takeIf { it.isNotBlank() }
        ?: event.serverIdHint()?.value
        ?: session?.currentServerId

private fun RealtimeEvent.serverIdHint(): ServerId? =
    when (this) {
        is RealtimeRefreshRequired -> serverId
        is ServerMembershipRemoved -> serverId
        is ServerDeleted -> serverId
        is ServerPlanUpdated -> serverId
        else -> null
    }

private fun Long.updateFrom(event: RealtimeEvent): Long =
    RealtimeCursorPolicy.updateAppliedSeq(this, event)

@Composable
private fun AppShellSilentRestoreContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
    )
}

@Composable
private fun AppShellRestoreSplashContent() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceCream)
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppShellRestoreLogoBadge("RAFT")
        Spacer(Modifier.height(34.dp))
        Text(
            text = "WHERE HUMANS AND AI AGENTS\nCOLLABORATE",
            style =
                SlockType.SectionLabel
                    .withSlockFontWeight(FontWeight.Bold)
                    .copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        letterSpacing = 1.2.sp,
                        color = SlockColor.TextMutedGray
                    ),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 360.dp)
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
internal fun BottomTabBarVisualContent() {
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
internal fun SettingsRowVisualContent() {
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
internal fun SettingsPageVisualContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val variant =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.optString("variant", "account").ifBlank { "account" }
        }
    if (variant == "server-danger-modal") {
        SettingsServerDangerModalVisualContent()
        return
    }
    val spec = settingsVisualSpec(variant)
    val alignBillingRouteToReact = spec.variant == "billing"
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(top = if (alignBillingRouteToReact) 0.dp else 48.dp)
    ) {
        SettingsDetailShell(
            title = spec.title,
            subtitle = spec.subtitle,
            iconName = spec.iconName,
            applyTopSafeArea = !alignBillingRouteToReact,
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
        "server-danger-modal" ->
            SettingsVisualSpec(variant, "Server Profile", "Workspace profile, role, and slug", SlockLucideIconName.Building2)
        "administration" ->
            SettingsVisualSpec(variant, "Administration", "Owners, admins, invites, and join links", SlockLucideIconName.Shield)
        "billing" ->
            SettingsVisualSpec(variant, "Plan & Billing", "", SlockLucideIconName.CreditCard)
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
        "server-danger-modal",
        "administration" -> SettingsVisualServerFocusedContent(spec.variant)
        "billing" -> SettingsVisualBillingContent()
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
    var deleteConfirmationName by remember { mutableStateOf("") }
    val serverPayload =
        ScreenLoadState.Content(snapshot).toServerPayload(
            session = homeSearchVisualSession,
            refreshing = false,
            actionState = null,
            timeContext = null
        )
    // Use the real production SharedSettingsServerContent so visual testing
    // always mirrors production — no duplicated card call sites.
    SharedSettingsServerContent(
        payload = serverPayload,
        inviteEmail = inviteEmail,
        onInviteEmailChange = { inviteEmail = it },
        joinLinkMaxUses = joinLinkMaxUses,
        onJoinLinkMaxUsesChange = { joinLinkMaxUses = it },
        joinLinkExpiresAt = joinLinkExpiresAt,
        onJoinLinkExpiresAtChange = { joinLinkExpiresAt = it },
        profileName = profileName,
        onProfileNameChange = { profileName = it },
        agreementEnabled = agreementEnabled,
        onAgreementEnabledChange = { agreementEnabled = it },
        agreementTitle = agreementTitle,
        onAgreementTitleChange = { agreementTitle = it },
        agreementBody = agreementBody,
        onAgreementBodyChange = { agreementBody = it },
        hideHumansFromMembers = hideHumansFromMembers,
        onHideHumansFromMembersChange = { hideHumansFromMembers = it },
        onboardingAgentId = onboardingAgentId,
        onOnboardingAgentIdChange = { onboardingAgentId = it },
        onboardingGreetingEnabled = onboardingGreetingEnabled,
        onOnboardingGreetingEnabledChange = { onboardingGreetingEnabled = it },
        serverSnapshot = snapshot,
        timeContext = null,
        serverRole = "owner",
        expectedServerSlug = snapshot.profile.slug,
        deleteConfirmationName = deleteConfirmationName,
        onDeleteConfirmationNameChange = { deleteConfirmationName = it },
        actionState = null,
        bottomContentPadding = SlockSpacing.Lg,
        onCreateJoinLink = {},
        onAction = { _, _ -> }
    )
}

@Composable
private fun SettingsServerDangerModalVisualContent() {
    val snapshot = remember { settingsVisualServerSnapshot() }
    var modalSlug by remember { mutableStateOf("") }
    val serverSlug = snapshot.profile.slug
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(61.dp)
                        .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsDetailBackButton(onBack = {})
                Text(
                    "Server Profile",
                    style = SlockType.PageTitle.copy(fontSize = 17.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(SlockColor.BorderBlack))
            Box(modifier = Modifier.fillMaxSize().padding(start = 20.dp, top = 43.dp, end = 20.dp, bottom = 20.dp)) {
                SettingsServerProfileBackdropCard(snapshot)
            }
        }
        SettingsServerDeleteConfirmationDialog(
            serverName = snapshot.profile.name,
            expectedServerSlug = serverSlug,
            confirmationSlug = modalSlug,
            deleteBusy = false,
            deleteEnabled = false,
            errorCode = null,
            onDismiss = {},
            onConfirmationSlugChange = { modalSlug = it },
            onCancel = {},
            onDelete = {}
        )
    }
}

@Composable
private fun SettingsServerProfileBackdropCard(snapshot: SettingsServerSnapshot) {
    Column(
        modifier =
            Modifier
                .width(350.dp)
                .background(SlockColor.SurfaceCream)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockLucideIcon(SlockLucideIconName.Building2, modifier = Modifier.size(14.dp), color = "#FF000000")
            Text(
                "PROFILE",
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.3.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        SettingsServerBackdropField(label = "Name", value = snapshot.profile.name)
        SettingsServerBackdropField(label = "Slug", value = snapshot.profile.slug)
    }
}

@Composable
private fun SettingsServerBackdropField(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        Box(modifier = Modifier.fillMaxWidth().height(39.dp)) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .offset(x = 3.dp, y = 3.dp)
                        .background(SlockColor.ShadowBlack)
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    value,
                    style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


@Composable
private fun SettingsVisualBillingContent() {
    val state: ScreenLoadState<SettingsBillingSnapshot> =
        ScreenLoadState.Content(settingsVisualBillingSnapshot())
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
        bottomContentPadding = SlockSpacing.Lg,
        notificationPreferenceSnapshot = NotificationPreferenceSnapshot(enabled = true),
        preferenceBusy = false,
        foregroundKeepAliveBusy = false,
        onNotificationPreferenceChange = {},
        onForegroundKeepAlivePreferenceChange = {},
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
            build.raft.app.data.SettingsServerProfileSnapshot(
                name = "Visual Server",
                slug = "visual",
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
                    url = "https://app.raft.build/join/design",
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

private fun settingsVisualBillingSnapshot(): SettingsBillingSnapshot {
    return SettingsBillingSnapshot(
        currentPlan =
            SettingsBillingCurrentPlanSnapshot(
                planName = "Free",
                usageRows =
                    listOf(
                        SettingsPlanUsageSnapshot("Message History", "30 days"),
                        SettingsPlanUsageSnapshot("File uploads", "128 MB / 1024 MB this month")
                    ),
                trialActive = false,
                description = "Start building with agents.",
                includedFeatures =
                    listOf(
                        "Channels",
                        "Tasks",
                        "Agents on your own computers",
                        "Agent reminders",
                        "Basic observability",
                        "30 days of message history",
                        "100 MB file uploads/month"
                    ),
                excludedFeatures =
                    listOf(
                        "Higher file upload limits",
                        "Unlimited message history",
                        "Joint channels",
                        "More professional features coming soon"
                    )
            ),
        notices = emptyList(),
        actions = emptyList(),
        plans =
            listOf(
                SettingsBillingPlanSnapshot(
                    name = "Pro",
                    price = "$20 / seat / month",
                    features =
                        listOf(
                            "Everything in Free",
                            "Unlimited message history",
                            "Higher file upload limits",
                            "Joint channels"
                        )
                )
            )
    )
}

private fun settingsVisualLanguageRegionSnapshot(): SettingsLanguageRegionSnapshot =
    SettingsLanguageRegionSnapshot(
        serverTranslation =
            build.raft.app.data.SettingsMobileToggleSnapshot(
                enabled = true,
                available = true,
                statusLabel = "Enabled",
                detail = "Allow translation on messages in this server."
            ),
        autoTranslation =
            build.raft.app.data.SettingsMobileToggleSnapshot(
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
            build.raft.app.data.SettingsMobileToggleSnapshot(
                enabled = true,
                available = true,
                statusLabel = "On",
                detail = "Receive push notifications for mentions and assigned tasks."
            ),
        muteServer =
            build.raft.app.data.SettingsMobileToggleSnapshot(
                enabled = false,
                available = true,
                statusLabel = "Unmuted",
                detail = "This server can send push notifications."
            ),
        sendTestEnabled = true,
        notificationPermission =
            build.raft.app.data.SettingsMobileActionSnapshot(
                available = true,
                statusLabel = "Allowed",
                detail = "System notification permission is granted."
            ),
        notificationSettings =
            build.raft.app.data.SettingsMobileActionSnapshot(
                available = true,
                statusLabel = "Open",
                detail = "Open OS notification settings."
            ),
        testNotification =
            build.raft.app.data.SettingsMobileActionSnapshot(
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
    ).toHomeChannelRow(),
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
    ).toHomeChannelRow()
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
        presenceMemberId = MemberId("agent_product")
    ).toHomeDirectMessageRow()
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
    selectedSenderIds = listOf("user_artin"),
    // Visual fixture is static + non-interactive (`onIntent = {}` in HomeSearchVisualContent),
    // so the store never actually runs a search. Mark `searched = true` explicitly so the
    // panel's "items/empty/results-count" branch falls into the "has results" path instead
    // of the "Search everything" empty state. See #首页专修:491a6bbd / #74.
    searched = true,
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
internal fun HomeSearchVisualContent(initialOpenFilterId: String? = null) {
    val pager = LocalActivity.current.getPager() as Pager
    val openFilterId =
        initialOpenFilterId ?: pager.pageData.params.optString("openFilterId").takeIf { it.isNotBlank() }
    Box(modifier = Modifier.width(342.dp).height(620.dp)) {
        SharedHomeSearchPanel(
            state = homeSearchVisualSearchState,
            homeState = homeSearchVisualHomeState,
            onIntent = {},
            onClose = {},
            interactiveSearchField = false,
            includeTopSafeArea = false,
            showSenderFilter = true,
            initialOpenFilterId = openFilterId,
            onOpenItem = {},
            onOpenEntity = {},
            bottomContentPadding = 12.dp,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
internal fun HomeSavedVisualContent() {
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
internal fun HomeActivityVisualContent() {
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
internal fun HomeCreateChannelDialogVisualContent() {
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
        bridge.log("settings_home_unsupported_destination key=$key")
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
    applyTopSafeArea: Boolean = true,
    onHeaderClick: (() -> Unit)? = null,
    onSelect: (String) -> Unit
) {
    val headerModifier =
        if (onHeaderClick != null) {
            Modifier.clickable(role = Role.Button, onClick = onHeaderClick)
        } else {
            Modifier
        }
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = payload.title,
            subtitle = null,
            iconName = SlockLucideIconName.Settings,
            modifier = headerModifier,
            applyTopSafeArea = applyTopSafeArea
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
                    onSelect = onSelect,
                    modifier = Modifier.padding(start = SlockSpacing.Md)
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
