package build.raft.app

import build.raft.app.resources.*
import build.raft.app.format.RaftFormat
import build.raft.app.markdown.markdownToPreviewText
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.designsystem.components.rememberRaftMobileNavServerPopoverState
import build.raft.designsystem.components.RaftFieldLabel
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.data.RaftNavDestination
import build.raft.app.data.RaftNavSurface
import build.raft.app.data.RaftProductEvents
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.gesture.rememberRaftBackSwipeScroll
import androidx.compose.ui.draw.clip
import build.raft.app.data.SHELL_OVERLAY_MESSAGE_TYPE
import build.raft.app.data.SHELL_OVERLAY_REQUEST_ID
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED
import build.raft.app.data.backFromDetail
import build.raft.app.data.closeFromDetailPage
import build.raft.app.data.normalizeSideThreadFocus
import build.raft.app.data.sideThreadFocusActive
import build.raft.app.data.sideThreadFocusAvailable
import build.raft.app.data.backClosesSideThreadFirst
import build.raft.app.data.foldSideThreadIntoDetail
import build.raft.app.data.sideThreadColumnAvailable
import build.raft.app.data.visibleSideThread
import build.raft.app.data.AdaptiveSideThread
import build.raft.app.data.RAFT_SIDE_THREAD_COLUMN_DP
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.RAFT_SIDE_THREAD_DIVIDER_DP
import build.raft.app.data.closeDetailCompletely
import build.raft.app.data.withOpenedDetail
import build.raft.app.data.SharedAdaptiveWorkspaceSession
import build.raft.app.data.detailListSelectionId
import build.raft.app.data.selectedConversationWhenShellReturns
import build.raft.app.data.hasVisibleDetailBackHistory
import build.raft.app.data.NetworkRaftThreadApi
import build.raft.app.base.Utils
import build.raft.app.network.raftNetworkKmmRecoveryEnvironmentId
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalInputField
import build.raft.app.components.RaftBrutalListRow
import build.raft.app.components.RaftAnchoredPickerTrigger
import build.raft.app.components.RaftBrutalSearchField
import build.raft.app.components.RaftBrutalTextField
import build.raft.app.components.SharedHomeSearchEditorOwner
import build.raft.app.components.SharedHomeSearchInput
import build.raft.app.components.RaftLinkButton
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.raftCardSurface
import build.raft.app.components.raftControlSurface
import build.raft.app.data.NetworkRaftHomeApi
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.BridgeRaftAvatarNativeCache
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.KeyValuePushPreviewModePreferenceStore
import build.raft.app.data.PRODUCT_FEEDBACK_MULTI_APP_FEATURE_FLAG_KEY
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.RaftHostNetworkEvents
import build.raft.app.data.RaftAppUpdateEvents
import build.raft.app.data.RaftChatLayoutPreferenceEvents
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.RealtimeRoomLeaseOwner
import build.raft.app.data.isAppUpdateHomeNotificationId
import build.raft.app.data.isMachineHomeNotificationId
import build.raft.app.data.normalizedAgentRuntimeStatus
import build.raft.app.data.AppLocale
import build.raft.app.data.MyMessageAlignmentPreference
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.NetworkRaftAnnouncementsRemindersApi
import build.raft.app.data.RaftAnnouncementsRemindersApiClient
import build.raft.app.data.NetworkRaftTasksApi
import build.raft.app.data.NotificationPreferenceSnapshot
import build.raft.app.data.KeyValueNotificationPreferenceStore
import build.raft.app.data.PersistentNotificationPreferenceRepository
import build.raft.app.data.PersistentChatLayoutPreferenceRepository
import build.raft.app.data.ChatLayoutPreferenceSnapshotRuntimeAuthority
import build.raft.app.data.DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN
import build.raft.app.data.DEFAULT_HOME_SERVER_AVATAR_ENABLED
import build.raft.app.data.DEFAULT_SERVER_DRAWER_ENABLED
import build.raft.app.data.ConversationForwardGestureRuntimeAuthority
import build.raft.app.data.raftChatLayoutPreferenceRepository
import build.raft.app.data.reconcileConversationForwardGesturePreference
import build.raft.app.data.AuthApiResult
import build.raft.app.data.AuthAuthorityChangedMessage
import build.raft.app.data.AuthLoginResponse
import build.raft.app.data.AuthLossDecision
import build.raft.app.data.AuthLossResolution
import build.raft.app.data.AuthMobileOAuthLinkCompleteResponse
import build.raft.app.data.AuthMobileOAuthStartResponse
import build.raft.app.data.AuthPendingOpenRequest
import build.raft.app.data.AuthRefreshResponse
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.authSessionTerminalSourceOrNull
import build.raft.app.data.AuthSocialCompleteResponse
import build.raft.app.data.ChannelRepository
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthGateIntent
import build.raft.app.data.SharedAuthReadyPhase
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.SharedAuthGateViewModel
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.isColdStartCachedReadyEligible
import build.raft.app.data.RaftAuthSessionTerminalSource
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedComputersState
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.selectComputerServerSlice
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.SharedTasksRouteOwner
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.WindowNavigationReadiness
import build.raft.app.data.ConversationForwardAction
import build.raft.app.data.toConversationForwardEntry
import build.raft.app.data.RaftSystemNotificationContract
import build.raft.app.data.hasCompletedTasksSnapshot
import build.raft.app.data.realtime.RealtimeCursorPolicy
import build.raft.app.data.realtime.realtimeProcessDesiredSession
import build.raft.app.data.HomeRowRestoredHint
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.jointInviteIdFromHomeNotificationIdOrNull
import build.raft.app.data.jointSameAdminContext
import build.raft.app.data.displayRowHints
import build.raft.app.data.SharedHomeDataSource
import build.raft.app.data.HomeConversationRow
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeParentThreadUnread
import build.raft.app.data.SIDEBAR_MARK_ALL_READ_FEATURE_FLAG_KEY
import build.raft.app.data.THREAD_UNREAD_BADGE_FEATURE_FLAG_KEY
import build.raft.app.data.foldThreadUnreadByParent
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeServerDrawerState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.HomeServerActivityUnreadProjection
import build.raft.app.data.SharedHomeTopAction
import build.raft.app.data.SharedHomeNotification
import build.raft.app.data.SharedHomeNotificationIcon
import build.raft.app.data.SharedHomeNotificationKind
import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import build.raft.app.data.SharedHomeShellIntent
import build.raft.app.data.SharedHomeShellState
import build.raft.app.data.SharedHomeShellStore
import build.raft.app.data.RealtimeGuardedDomainEventSinks
import build.raft.app.data.RealtimeShellCallbackGate
import build.raft.app.data.RealtimeConnectionWitness
import build.raft.app.data.RealtimeCanonicalFinalSinkSurfaceKey
import build.raft.app.data.RealtimeGrantRecord
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.data.SharedSettingsHomeSnapshot
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsRoute
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SharedSettingsStorageRow
import build.raft.app.data.SharedSettingsStorageSnapshot
import build.raft.app.data.ServerSwitcherOrderPreference
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.toSettingsMobileSelectionSnapshot
import build.raft.app.data.RaftAuthApi
import build.raft.app.data.RaftDatabaseLaunchAttempt
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RAFT_SHELL_OVERLAY_REQUEST_EVENT
import build.raft.app.data.RAFT_SHELL_OVERLAY_RESULT_EVENT
import build.raft.app.data.SHELL_OVERLAY_TYPE_CANCEL
import build.raft.app.data.RaftShellOpenOverlay
import build.raft.app.data.RaftShellOverlayResult
import build.raft.app.data.raftShellShouldDropOverlayRequest
import build.raft.app.data.shellOverlayRequestIdOrNull
import build.raft.app.data.toRaftShellOverlayRequestOrNull
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.attemptRaftDatabaseLaunch
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.isAuthSessionError
import build.raft.app.data.isEdgeBlockedMarker
import build.raft.app.data.isSubmittableChannelName
import build.raft.app.data.RaftAuthSessionEvents
import build.raft.app.data.isRefreshing
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.messageBodyFontSizeOwnerKey
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.parseRaftHome
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MemberRepository
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealInboxRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealReadReceiptRepository
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.RealSavedRepository
import build.raft.app.data.RealSearchRepository
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.SettingsRepository
import build.raft.app.data.SettingsServerManagementRepository
import build.raft.app.data.SharedMembersDataSource
import build.raft.app.data.SearchIsoBounds
import build.raft.app.data.SearchScope
import build.raft.app.data.SearchSort
import build.raft.app.data.SearchSenderFilterItem
import build.raft.app.data.SearchFrequentEntityLimit
import build.raft.app.data.SearchFrequentEntityRank
import build.raft.app.data.SearchPersonalizationEntityKey
import build.raft.app.data.SearchPersonalizationEntityKind
import build.raft.app.data.SearchTimeRange
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftAgentsNetworkApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftComputersNetworkApi
import build.raft.app.data.SharedCreateAgentCoordinator
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.SharedAgentPanelTabOrderStore
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SharedDirectMessagePreviewPrefetchCoordinator
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.typedPinnedOrLegacy
import build.raft.app.data.SharedHomeSavedPanelState
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SharedHomeSearchSavedIntent
import build.raft.app.data.SharedHomeSearchSavedViewModel
import build.raft.app.data.hasMessageSearchIntent
import build.raft.app.data.AccountSettingsSnapshot
import build.raft.app.data.ACCOUNT_ACTION_AVATAR_UPLOAD
import build.raft.app.data.ACCOUNT_ACTION_PASSWORD_CHANGE
import build.raft.app.data.ACCOUNT_ACTION_PROFILE_SAVE
import build.raft.app.data.ACCOUNT_ACTION_PROVIDER_CONNECT
import build.raft.app.data.ACCOUNT_ACTION_PROVIDER_DISCONNECT
import build.raft.app.data.ACCOUNT_ACTION_SHARE_USAGE_DATA
import build.raft.app.data.SHARE_USAGE_DATA_DEFAULT
import build.raft.app.data.PlatformFile
import build.raft.app.data.externalShareComposerText
import build.raft.app.data.SettingsHandsFeedbackSubmission
import build.raft.app.data.SettingsHandsFeedbackDraftSnapshot
import build.raft.app.data.ScreenError
import build.raft.app.data.ConnectedAccountStatus
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.savedMessageIdOrNull
import build.raft.app.data.SettingsAppearanceSnapshot
import build.raft.app.data.SettingsDiagnosticsFeedbackDraft
import build.raft.app.data.SettingsMobileSelectionSnapshot
import build.raft.app.data.SettingsMobileSelectionOptionSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import build.raft.app.data.SettingsPlanUsageSnapshot
import build.raft.app.data.SettingsLanguageRegionSnapshot
import build.raft.app.data.TranslationTargetLanguages
import build.raft.app.data.SettingsServerAdminSnapshot
import build.raft.app.data.SettingsServerAgreementSnapshot
import build.raft.app.data.SettingsServerInviteSnapshot
import build.raft.app.data.SettingsServerJoinLinkSnapshot
import build.raft.app.data.SettingsServerOnboardingAgentSnapshot
import build.raft.app.data.SettingsServerOnboardingSnapshot
import build.raft.app.data.SettingsServerSectionSnapshot
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSnapshot
import build.raft.app.data.SettingsServerTranslationSnapshot
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.MemberSnapshot
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
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
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.ServerNotificationPreferencesChanged
import build.raft.app.model.PerReceiverPinnedChanged
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.AgentCreated
import build.raft.app.model.Heartbeat
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerDeleted
import build.raft.app.model.ActivityChanged
import build.raft.app.model.ServerFeaturesChanged
import build.raft.app.model.ServerPlanUpdated
import build.raft.app.model.ComputerRestartDone
import build.raft.app.model.ServerId
import build.raft.app.model.ServerMembershipRemoved
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskCreated
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadFollowersUpdated
import build.raft.app.model.AgentTypingUpdated
import build.raft.app.model.ScopeReadUpdated
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.model.raftStartOfDayUtcIso
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import build.raft.app.compat.ui.withKuiklyIdleContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import build.raft.app.compat.ui.NestedScrollMode
import build.raft.app.compat.ui.bouncesEnable
import build.raft.app.compat.ui.nestedScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.collectIsPressedAsState
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.material3.Text
import build.raft.app.components.RaftPullToRefreshBox
import build.raft.app.components.raftHeaderRefreshIndicatorVisible
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.components.RaftMetrics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.ui.KeyboardParams
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.time.TimeSource
import build.raft.app.model.isExternalAgent

const val SETTINGS_HOME_PAGE = "compose_settings_home"
const val SETTINGS_ROUTE_VISUAL_PAGE = "compose_settings_route_visual"
const val SETTINGS_TAB_BAR_SMOKE_PAGE = "compose_settings_tabbar_smoke"
const val BOTTOM_TAB_BAR_SMOKE_PAGE = "compose_bottom_tabbar_smoke"
const val HOME_SEARCH_PAGE = "compose_home_search"
internal const val HOME_SAVED_PAGE = "compose_home_saved"
internal const val HOME_ACTIVITY_PAGE = "compose_home_activity"
const val HOME_SEARCH_SELECTED_CHANNEL_ID = "selectedChannelId"
const val HOME_SEARCH_INITIAL_OPEN_FILTER_ID = "initialOpenFilterId"
const val HOME_SEARCH_FOCUS_INPUT = "focusSearchInput"
const val AUTH_CHOOSE_SERVER_PAGE = "compose_auth_choose_server"
const val RAFT_APP_SHELL_PAGE = "compose_slock_app_shell"
internal const val RAFT_APP_SHELL_CHILD_TAB_PAGE = "compose_app_shell_child_tab"
internal const val RAFT_CONVERSATION_FORWARD_PAGER_EVENT = "slockConversationForwardRequested"
private const val AUTH_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
internal const val AUTH_CHOOSE_SERVER_FROM_SHELL = "slockChooseServerFromShell"
internal const val AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD = "slockAuthChooseServerSkipInitialLoad"
private const val APP_SHELL_SUPPRESS_RESTORE_LANDING = "slockSuppressRestoreLanding"
private const val APP_SHELL_AUTH_GATE_INSTANCE_ID = "slockAuthGateInstanceId"

internal sealed interface AuthGateMessage {
    data class Dynamic(val value: String) : AuthGateMessage

    data class Static(
        val value: StringDesc,
        val pinSignOut: Boolean = false
    ) : AuthGateMessage
}

@Composable
internal fun AuthGateMessage?.localizedMessage(): String? =
    when (this) {
        is AuthGateMessage.Dynamic -> value
        is AuthGateMessage.Static -> value.localized()
        null -> null
    }

private const val HomeSurfaceVisibleResumeDelayMs = 48L
private val EmptyComposerDraftRecordsFlow = MutableStateFlow(emptySet<SharedThreadComposerDraftRecord>())
private val EmptyConversationDirectoryProjectionFlow =
    MutableStateFlow(emptyMap<String, SharedConversationDirectoryProjection>())
private const val SettingsDestinationHome = "home"
internal const val SettingsDestinationAccount = "account"
internal const val SettingsDestinationComputers = "computers"
internal const val SettingsDestinationAdministration = "administration"
internal const val SettingsDestinationConnectedApps = "connected-apps"
internal const val SettingsDestinationBilling = "billing"
internal const val SettingsDestinationStorage = "storage"
internal const val SettingsDestinationReleaseNotes = "releaseNotes"
internal const val SettingsDestinationHandsFeedback = "hands-feedback"

private const val SettingsRouteVisualDestinationKey = "destination"
private const val SettingsRouteVisualFixtureKey = "fixtureKey"
private const val SettingsRouteVisualItemIdKey = "itemId"
private const val SettingsRouteVisualMachineIdKey = "machineId"
internal val SettingsRouteVisualPageDataFields =
    setOf(
        SettingsRouteVisualDestinationKey,
        SettingsRouteVisualFixtureKey,
        SettingsRouteVisualItemIdKey,
        SettingsRouteVisualMachineIdKey,
        "fullHeight",
        "slockVisualFullHeight"
    )
private const val SettingsDeveloperUnlockTapCount = 7
private const val AppShellInitialTabKey = "slockInitialTab"
private const val DiagnosticsDeepLinkIdKey = "slockDiagnosticsDeepLinkId"
private const val DiagnosticsDeepLinkAutoCloseMsKey = "slockDiagnosticsDeepLinkAutoCloseMs"
internal const val SharedSettingsDefaultWebBaseUrl = "https://app.raft.build"
private const val AppUpdateNotificationRefreshIntervalMs = 30L * 60L * 1000L
private const val AppShellRealtimeAuthRefreshAction = "authRefreshRequired"
private const val AppShellNotificationThreadPayloadKey = "slockNotificationThreadLaunchPayload"
private const val AppShellNotificationServerIdKey = "slockNotificationServerId"
private const val AppShellNotificationThreadIdKey = "slockNotificationThreadId"
private const val AppShellNotificationChannelIdKey = "slockNotificationChannelId"
private const val AppShellNotificationMessageIdKey = "slockNotificationMessageId"
private const val AppShellNotificationParentChannelIdKey = "slockNotificationParentChannelId"
private const val AppShellNotificationParentMessageIdKey = "slockNotificationParentMessageId"
private const val AppShellNotificationKindKey = "slockNotificationKind"
private const val AppShellNotificationCategoryKey = "slockNotificationCategory"
/** WindowNavigationIntent.source of an external message link (#proj-mobile #25/#26). */
internal const val MessageLinkNavigationSource = "message_link"
private const val MessageLinkServerNamePlaceholder = "__raft_message_link_server__"
// AppShell child pages fill the native window. A floating bottom tab bar may
// overlay that page, but it does not turn the full-screen page into an inset
// card. Applying the iOS `liquid` host style here clips every screen edge to a
// fixed 28pt radius; the white AppShell surface then leaks between that radius
// and each iPad/window corner. Keep the page full-bleed and let the native
// window own its own corner mask. A future genuinely inset card must opt in at
// that inset host instead of coupling its shape to the tab-bar renderer.
@Suppress("UNUSED_PARAMETER")
internal fun appShellChildPageGlassStyle(bottomTabBarOverlaysContent: Boolean): String? = null

private val AppShellNotificationLaunchJson = Json { ignoreUnknownKeys = true }
private val SearchControlsHorizontalPadding = 16.dp
private val SearchMessagesHorizontalPadding = 16.dp
private val SavedSelectedRowShadowOffset = 2.dp
private val SavedUnsaveHitTargetSize = 44.dp
private val SearchPopoverBorderColor: Color get() = RaftColor.BorderBlack
private val SearchPopoverShadowColor: Color get() = RaftColor.ShadowBlack
private val SearchMutedTextColor: Color get() = RaftColor.TextBlack.copy(alpha = 0.62f)

// xxchan 2026-10-20: the phone bar is 主页 · 动态 · 任务 · 更多 (+ the Search action); Members and
// Settings are pushed from More. They stay shell child pages so the wide rail, deep links and
// old launch values keep selecting them directly.
internal val AppShellChildPageTabKeys = listOf("home", "activity", "tasks", AppShellSearchTabKey, "more", "members", "settings")
private val AppShellChildPagePrewarmOrder = listOf("settings", "tasks", "members")

internal typealias AppShellChildTabRenderer = @Composable (tab: String, active: Boolean) -> Unit

internal class AppShellChildTabRenderSlot(
    initialRenderer: AppShellChildTabRenderer
) {
    var renderer: AppShellChildTabRenderer by mutableStateOf(initialRenderer)
}

internal class AppShellChildTabEntry(
    val debugId: Int,
    val timestampDisplayState: RaftTimestampDisplayState = RaftTimestampDisplayState(),
    initialSelectedTab: String = "home",
    initialParentVisible: Boolean = true,
    initialRetainedTabs: Set<String> = setOf(initialSelectedTab),
    initialRenderer: AppShellChildTabRenderer = { _, _ ->
        RaftCenteredLoading()
    },
    initialTabKeys: List<String> = AppShellChildPageTabKeys,
    initialModalHostState: RaftUnifiedDialogHostState? = null
) {
    var selectedTab by mutableStateOf(initialSelectedTab)
    var parentVisible by mutableStateOf(initialParentVisible)
    var retainedTabs by mutableStateOf(initialRetainedTabs)
    private val renderSlots =
        initialTabKeys.associateWith {
            AppShellChildTabRenderSlot(initialRenderer)
        }
    // task #94: when the Slack-nav gate flips, the entry is re-registered with a different tab
    // key set while retained native child pages may still briefly compose against the previous
    // entry. Render slots therefore fail closed to the shell renderer (which itself falls back
    // to home content for unknown keys) instead of throwing on getValue.
    private val fallbackRenderer: AppShellChildTabRenderer = initialRenderer
    var modalHostState: RaftUnifiedDialogHostState? by mutableStateOf(initialModalHostState)

    fun rendererFor(tab: String): AppShellChildTabRenderer =
        renderSlots[tab]?.renderer ?: fallbackRenderer

    fun updateRenderer(tab: String, renderer: AppShellChildTabRenderer) {
        renderSlots[tab]?.let { slot -> slot.renderer = renderer }
    }

    fun updateSelection(
        selectedTab: String,
        retainedTabs: Set<String>,
        renderer: AppShellChildTabRenderer,
        parentVisible: Boolean = true
    ) {
        updateRenderer(selectedTab, renderer)
        this.retainedTabs = retainedTabs
        this.selectedTab = selectedTab
        this.parentVisible = parentVisible
    }
}

private data class AppShellChildTabRegistration(
    val entry: AppShellChildTabEntry,
    val replacedEntryId: Int
)

internal object AppShellChildTabRegistry {
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

    /** True while an app shell is composed (it registers its child tabs). */
    fun hasEntries(): Boolean = entries.isNotEmpty()
}

internal fun sharedSettingsResolvedWebBaseUrl(webUrl: String?): String =
    webUrl?.trim()?.trimEnd('/').orEmpty().ifBlank { SharedSettingsDefaultWebBaseUrl }

internal fun sharedSettingsWebUrl(webUrl: String?, path: String): String {
    val baseUrl = sharedSettingsResolvedWebBaseUrl(webUrl)
    val cleanPath = path.trim().trimStart('/')
    return if (cleanPath.isBlank()) baseUrl else "$baseUrl/$cleanPath"
}

internal const val SharedSettingsEmbedContract = "raft-settings-v1"
internal const val SharedSettingsEmbedHostShell = "host"

internal fun sharedSettingsHostShellWebUrl(
    webUrl: String?,
    path: String,
    extraQueryParameters: List<Pair<String, String>> = emptyList()
): String {
    val queryParameters =
        listOf(
            "embed" to SharedSettingsEmbedContract,
            "shell" to SharedSettingsEmbedHostShell
        ) + extraQueryParameters
    val query = queryParameters.joinToString(separator = "&") { (key, value) -> "$key=$value" }
    return "${sharedSettingsWebUrl(webUrl, path)}?$query"
}

internal fun ScreenLoadState<SharedSettingsDeveloperSnapshot>.configuredWebBaseUrlOrNull(): String? =
    (this as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data?.webUrl

// raft-ui search-entity leading slot: channels and computers take an IconTile `sm` (27dp
// `fill-muted`, 14dp `foreground-muted` icon); people and agents an avatar `sm` (27dp) with
// the 9dp activity square at the corner.
@Composable
private fun SharedHomeSearchEntityLeadingAvatar(
    result: SearchEntityResult,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver
) {
    when (result.type) {
        SearchEntityType.Channel,
        SearchEntityType.Computer ->
            RaftIconTile(size = RaftIconTileSize.Sm) {
                RaftLucideIcon(
                    result.homeSearchEntityLeadingIconName() ?: RaftLucideIconName.Hash,
                    color = raftArgbHex(RaftColor.TextMuted),
                    modifier = Modifier.size(RaftIconTileSize.Sm.icon)
                )
            }

        else ->
            run {
                val memberAvatarWireValue =
                    result.memberId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { rememberRaftMemberAvatarWireValue(memberAvatarResolver, it) }
                    val status =
                        if (result.showPresence) {
                            result.memberId
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    projectHomeAgentStatusIndicatorState(
                                        showPresence = true,
                                        memberId = it,
                                        canonicalState = rememberRaftAgentStatusIndicatorState(agentStatusResolver, it)
                                    )
                                }
                        } else {
                            null
                        }

                RaftAvatarWithStatus(
                    statusColor = status?.color,
                    avatarSize = RaftSearchMetrics.EntityLeading,
                    statusPulsing = status?.pulsing == true
                ) {
                    RaftAppAvatar(
                        avatarWireValue = memberAvatarWireValue ?: result.avatarWireValue ?: AvatarReference.Random(result.key).wireValue,
                        label = result.title.removePrefix("@").removePrefix("#"),
                        // task #107: must match the RaftAvatarWithStatus wrapper's
                        // avatarSize — the default (ChannelAvatarSize = 20.dp) leaves the
                        // avatar centered in its slot while the status dot anchors to
                        // the slot corner, floating outside the visible avatar.
                        avatarSize = RaftSearchMetrics.EntityLeading,
                        cacheSubject =
                            result.memberIdentity?.let { identity ->
                                raftMemberAvatarCacheSubject(identity.kind, identity.memberId.value)
                            },
                        cacheServerId = result.memberIdentity?.sourceServerId?.value
                    )
                }
            }
    }
}

// search-message `messageSender`: a 14dp avatar and the name in `text-callout` bold ink.
@Composable
private fun SharedHomeSearchSenderMeta(
    sender: String,
    avatarWireValue: String?,
    avatarCacheIdentity: RaftMemberAvatarCacheIdentity? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftAppAvatar(
            avatarWireValue = avatarWireValue ?: AvatarReference.Random(sender.searchAvatarSeed()).wireValue,
            label = sender,
            avatarSize = RaftSearchMetrics.SenderAvatar,
            cacheSubject = avatarCacheIdentity?.subject,
            cacheServerId = avatarCacheIdentity?.serverId
        )
        RaftText(
            text = sender,
            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Bold),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal val settingsTabBarSmokePayload = RaftSettingsHomePayload(
    title = "Settings",
    subtitle = "Tab bar smoke",
    items = listOf(
        SettingsHomePageLinkPayload(
            key = "account",
            title = "Account",
            subtitle = "Profile, email, password, connected accounts",
            iconName = RaftLucideIconName.User,
            value = "Verified",
        ),
        SettingsHomePageLinkPayload(
            key = "computers",
            title = "Computers",
            subtitle = "Connected machines and workspaces",
            iconName = RaftLucideIconName.Monitor
        ),
        SettingsHomePageLinkPayload(
            key = "server",
            title = "Server",
            subtitle = "Workspace name, members, invites, permissions",
            iconName = RaftLucideIconName.Users,
            value = "Owner"
        ),
        SettingsHomePageLinkPayload(
            key = "billing",
            title = "Billing",
            subtitle = "Plan, usage, invoices",
            iconName = RaftLucideIconName.ShieldCheck
        ),
        SettingsHomePageLinkPayload(
            key = "language-region",
            title = "Language & Region",
            subtitle = "Language, timezone, translation",
            iconName = RaftLucideIconName.Languages
        ),
        SettingsHomePageLinkPayload(
            key = "mobile",
            title = "Mobile",
            subtitle = "Push notifications, language, layout",
            iconName = RaftLucideIconName.Smartphone
        ),
        SettingsHomePageLinkPayload(
            key = "storage",
            title = "Storage",
            subtitle = "Local cache and diagnostics",
            iconName = RaftLucideIconName.Archive
        ),
        SettingsHomePageLinkPayload(
            key = "about",
            title = "About",
            subtitle = "Version and build information",
            iconName = RaftLucideIconName.BadgeInfo
        ),
        SettingsHomePageLinkPayload(
            key = SettingsDestinationHandsFeedback,
            title = "Send feedback",
            subtitle = "Report a problem or suggestion",
            iconName = RaftLucideIconName.MessageSquarePlus
        ),
        SettingsHomePageLinkPayload(
            key = SettingsDestinationReleaseNotes,
            title = "Release Notes",
            subtitle = "Product changes and migration notes",
            iconName = RaftLucideIconName.FileText
        ),
        SettingsHomePageLinkPayload(
            key = "router",
            title = "Smoke previews",
            subtitle = "Open Kuikly router and shared test pages",
            iconName = RaftLucideIconName.Route
        )
    )
)

private val settingsVisualItems = listOf(
    RaftTabItemPayload(
        key = "account",
        title = "Account",
        subtitle = "Profile, email, password, connected accounts",
        iconName = RaftLucideIconName.User,
        value = "Verified",
        selected = true
    ),
    RaftTabItemPayload(
        key = "language-region",
        title = "Language & Region",
        subtitle = "Language, timezone, translation",
        iconName = RaftLucideIconName.Languages
    ),
    RaftTabItemPayload(
        key = "appearance",
        title = "Appearance",
        subtitle = "Message font size and reading density",
        iconName = RaftLucideIconName.Type
    ),
    RaftTabItemPayload(
        key = "browser",
        title = "Device",
        subtitle = "Notifications and local device state",
        iconName = RaftLucideIconName.AppWindow
    ),
    RaftTabItemPayload(
        key = "server",
        title = "Server",
        subtitle = "Workspace, members, invites, permissions",
        iconName = RaftLucideIconName.Building2,
        value = "Owner"
    ),
    RaftTabItemPayload(
        key = "computers",
        title = "Computers",
        subtitle = "Connected machines and workspaces",
        iconName = RaftLucideIconName.Monitor
    ),
    RaftTabItemPayload(
        key = SettingsDestinationHandsFeedback,
        title = "Send feedback",
        subtitle = "Report a problem or suggestion",
        iconName = RaftLucideIconName.MessageSquarePlus
    ),
    RaftTabItemPayload(
        key = "release-notes",
        title = "Release Notes",
        subtitle = "Product changes and migration notes",
        iconName = RaftLucideIconName.FileText
    )
)

internal data class SettingsVisualFixtureValues(
    val serverId: String = "visual-server",
    val serverName: String = "Visual Server",
    val serverSlug: String = "visual",
    val serverPlan: String = "free",
    val ownerId: String = "visual-user",
    val ownerMemberId: String = "visual-human-1",
    val ownerName: String = "artin",
    val ownerDisplayName: String = "artin",
    val ownerHandle: String = "@artin",
    val ownerEmail: String = "artin@slock.ai",
    val ownerAvatarLabel: String = "AR",
    val ownerRole: String = "owner",
    val ownerRoleLabel: String = "Owner",
    val ownerDescription: String = "Owner",
    val ownerEmailVerified: Boolean = true,
    val designerEmail: String = "designer@slock.ai",
    val memberJoinedAtIso: String = "2026-06-18T00:00:00.000Z",
    val entityCreatedAtIso: String = "2026-06-18T00:00:00.000Z",
    val recentActivityAtIso: String = "2026-06-19T12:24:00.000Z"
) {
    val ownerHandleBare: String get() = ownerHandle.removePrefix("@")
    val ownerAdminDetail: String get() = "$ownerRoleLabel · $ownerEmail"
}

internal object SettingsVisualFixtureBridge {
    var values: SettingsVisualFixtureValues = SettingsVisualFixtureValues()
        private set

    fun install(values: SettingsVisualFixtureValues) {
        this.values = values
    }
}

internal val settingsVisualFixtureValues: SettingsVisualFixtureValues
    get() = SettingsVisualFixtureBridge.values

private val settingsPageVisualPayload: RaftSharedSettingsPayload
    get() {
        val fixture = settingsVisualFixtureValues
        return RaftSharedSettingsPayload(
            instanceId = "visual-settings-page",
            title = "Account",
            detail = "Profile, email, password, connected accounts",
            heroIconName = RaftLucideIconName.User,
            heroLabel = fixture.ownerDisplayName,
            sections = listOf(
                RaftSharedSettingsSection(
                    title = "Profile",
                    rows = listOf(
                        RaftSharedSettingsRow("displayName", RaftLucideIconName.User, "Display name", "Shown across messages, tasks, and mentions.", status = fixture.ownerDisplayName),
                        RaftSharedSettingsRow("email", RaftLucideIconName.Mail, "Email", fixture.ownerEmail, status = "Verified"),
                        RaftSharedSettingsRow("avatar", RaftLucideIconName.Upload, "Avatar", "Upload a custom human avatar.", status = "Ready")
                    )
                ),
                RaftSharedSettingsSection(
                    title = "Session",
                    rows = listOf(
                        RaftSharedSettingsRow("password", RaftLucideIconName.Shield, "Password", "Change password and connected credentials.", status = "Enabled"),
                        RaftSharedSettingsRow("logout", RaftLucideIconName.LogOut, "Log out", "Sign out of this device.", status = "Action")
                    )
                )
            )
        )
    }

private val bottomTabBarSmokeItems = listOf(
    RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.House, selected = true),
    RaftBottomTabItemPayload("activity", "Activity", RaftLucideIconName.Activity, attentionDot = true),
    RaftBottomTabItemPayload("tasks", "Tasks", RaftLucideIconName.SquareCheckBig),
    RaftBottomTabItemPayload(AppShellSearchTabKey, "Search", RaftLucideIconName.Search),
    RaftBottomTabItemPayload("more", "More", RaftLucideIconName.Ellipsis)
)

/** Rail-only inline destination key (task #13 three-pane): Search renders in the AppShell
 *  middle pane under the wide LeftRail; it is not a persisted tab key. */
internal const val AppShellRailSearchKey = "rail_search"

/** Saved opened from the Home sidebar in desktop mode: an inline list pane, like rail Search. */
internal const val AppShellRailSavedKey = "rail_saved"

/**
 * The phone bottom bar (stdrc 2026-10-21): Home · Activity · Tasks · Search · More for
 * [selectedTab]. Search is a tab left of More; a page pushed from More (Members) and Settings
 * itself keep More selected.
 */
internal fun appShellPhoneBottomTabs(
    selectedTab: String,
    homeLabel: String,
    activityLabel: String,
    searchLabel: String,
    tasksLabel: String,
    moreLabel: String,
    activityUnreadCount: Int = 0,
    moreAttention: Set<RaftMoreAttention> = emptySet(),
    /**
     * D1a: the last tab is the current server — its name as the label (also what the iOS system
     * bar shows), the server glyph, never selected, and the avatar dot
     * ([appShellServerTabShowsAttention]).
     * Null (no server loaded yet) keeps the plain More tab.
     */
    serverTab: RaftServerMenuModel? = null,
    /** The server tab's screen-reader label ("Current server: Raft HQ"). */
    serverTabAccessibilityLabel: String? = null,
): List<RaftBottomTabItemPayload> =
    appShellBottomTabs(
        selectedKey = appShellPhoneBottomBarSelectedKey(selectedTab),
        orderedTabKeys = AppShellPhoneBottomBarKeys,
        activityUnreadCount = activityUnreadCount,
        moreAttention = moreAttention,
        labelFor = { key ->
            when (key) {
                "home" -> homeLabel
                "activity" -> activityLabel
                AppShellSearchTabKey -> searchLabel
                "tasks" -> tasksLabel
                "more" -> moreLabel
                else -> key.uppercase()
            }
        }
    ).map { item ->
        if (serverTab == null || item.key != AppShellServerTabKey) {
            item
        } else {
            item.copy(
                label = serverTab.currentServer.name.ifBlank { moreLabel },
                iconName = RaftLucideIconName.Server,
                // It opens the server panel, not a page: never selected (raft-design #712).
                selected = false,
                attentionDot = appShellServerTabShowsAttention(serverTab),
                // No count on iOS (artin 10-23): the panel's rows show other servers' unread.
                opensPanel = true,
                accessibilityLabel = serverTabAccessibilityLabel,
                avatarSource = serverTab.currentServer.toServerSwitcherAvatarPresentation().avatarWireValue,
                avatarFallbackLabel = serverTab.currentServer.toServerSwitcherAvatarPresentation().fallbackLabel,
            )
        }
    }

/**
 * task #94: builds bottom-tab payloads from the resolved ordered tab keys (legacy or
 * Slack-aligned) with per-key labels and icons. Icons follow the Slack-aligned preview:
 * Home=house, DMs=message circle, Activity=bell, More=ellipsis.
 *
 * This list is the single source of truth for every bottom-bar renderer, including the
 * host-drawn iOS UITabBar (RaftNativeTabBarBridge): a key added to the ordered tab keys
 * appears there with its label and icon. The Search action key is never selected. Activity
 * unread is an attention dot, hidden while Activity is the selected tab
 * ([appShellActivityTabShowsDot]); More wears the same dot when Settings has something new
 * ([appShellMoreTabShowsDot]); tabs carry no numbers.
 */
internal fun appShellBottomTabs(
    selectedKey: String,
    orderedTabKeys: List<String>,
    activityUnreadCount: Int = 0,
    moreAttention: Set<RaftMoreAttention> = emptySet(),
    labelFor: (String) -> String
): List<RaftBottomTabItemPayload> =
    orderedTabKeys.map { key ->
        val selected = selectedKey == key
        RaftBottomTabItemPayload(
            key = key,
            label = labelFor(key),
            iconName = appShellTabIcon(key),
            selected = selected,
            attentionDot =
                when (key) {
                    "activity" -> appShellActivityTabShowsDot(activityUnreadCount, selected)
                    "more" -> appShellMoreTabShowsDot(moreAttention, selected)
                    else -> false
                },
            nativeBadgeCount =
                if (key == "activity" && appShellActivityTabShowsDot(activityUnreadCount, selected)) {
                    activityUnreadCount
                } else {
                    0
                },
        )
    }

// task #94: valid app-shell tab keys = legacy set plus the Slack-aligned set. Slack keys only
// enter live tab state while the Slack-nav gate is on (or a persisted order exists), so the
// union keeps legacy clamping behavior unchanged while making the Slack keys first-class for
// child-page lookup, tap normalization and retained-tab bookkeeping.
private val retainedAppShellTabKeys =
    listOf("home", "tasks", "members", "settings") + listOf("home", "dms", "activity", "more", AppShellSearchTabKey)

internal fun retainedAppShellSelectedTab(selectedTab: String): String =
    selectedTab.takeIf { it in retainedAppShellTabKeys } ?: "home"

/**
 * task #13: the shared adaptive detail slot lives inside adaptiveSplitListDetail, which is
 * hosted by the Home surface (no pushed secondary page), the Activity rail tab, or an inline
 * rail secondary destination (Search). Any other destination — or a backgrounded host —
 * suspends the detail (ParentVisibilityChanged).
 */
internal fun appShellSharedDetailSurfaceVisible(
    pageVisible: Boolean,
    selectedTab: String,
    selectedHomeDestination: String?,
    railInlineSecondaryDestination: String?,
    tasksDetailOverlayOpen: Boolean = false,
): Boolean =
    pageVisible &&
        (
            (retainedAppShellSelectedTab(selectedTab) == "home" && selectedHomeDestination == null) ||
                retainedAppShellSelectedTab(selectedTab) == "activity" ||
                // task #33: the Slack-nav DMs tab hosts the same list + detail split.
                retainedAppShellSelectedTab(selectedTab) == "dms" ||
                // task #34: the Tasks board hosts the detail in a bounded overlay while a task
                // opened from the board is showing.
                (retainedAppShellSelectedTab(selectedTab) == "tasks" && tasksDetailOverlayOpen) ||
                railInlineSecondaryDestination != null
        )

/**
 * task #33: the DMs tab only shows a DM (or a reply thread inside a DM) in its detail pane. A
 * channel selected on Home stays Home's; next to the DMs list it renders the empty placeholder
 * instead of a channel composer.
 */
internal fun appShellDmsTabShowsDetail(surfaceKind: String?, parentSurfaceKind: String?): Boolean =
    surfaceKind == RaftThreadSurfaceKind.DirectMessage ||
        (surfaceKind == RaftThreadSurfaceKind.ReplyThread && parentSurfaceKind == RaftThreadSurfaceKind.DirectMessage)

/**
 * task #13 two-step Back contract: while a rail surface (Activity tab or an inline secondary
 * destination like Search) is showing a shared detail selection, the first Back/close must
 * dismiss the detail; only a detail-free surface exits the destination or falls back to Home.
 */
internal fun appShellRailSurfaceBackDismissesDetailFirst(
    selectedTab: String,
    railInlineSecondaryDestination: String?,
    selectionPresent: Boolean,
    tasksDetailOverlayOpen: Boolean = false,
): Boolean =
    selectionPresent &&
        (
            railInlineSecondaryDestination != null ||
                retainedAppShellSelectedTab(selectedTab) == "activity" ||
                retainedAppShellSelectedTab(selectedTab) == "dms" ||
                (retainedAppShellSelectedTab(selectedTab) == "tasks" && tasksDetailOverlayOpen)
        )

/**
 * task #34: on a wide desktop-mode screen a task opened from the Tasks board shows its thread in
 * a bounded overlay above the board, so the board (filters, column scroll, selection) stays put.
 * [overlayActive] is set by a board open and kept while the user navigates inside the detail
 * (thread → parent channel advances the selection generation); it is cleared when the overlay
 * closes, the selection ends, the user leaves the Tasks tab, or the wide layout goes away — so a
 * conversation selected elsewhere (Home, Activity, Search) never pops up over the board.
 */
internal fun appShellTasksDetailOverlayOpen(
    desktopModeOnLargeScreen: Boolean,
    dualPane: Boolean,
    selectedTab: String,
    selectionPresent: Boolean,
    overlayActive: Boolean,
): Boolean =
    desktopModeOnLargeScreen &&
        dualPane &&
        retainedAppShellSelectedTab(selectedTab) == "tasks" &&
        selectionPresent &&
        overlayActive

/** task #34: the overlay ownership ends once any of these no longer holds. */
internal fun appShellTasksDetailOverlayStaysActive(
    desktopModeOnLargeScreen: Boolean,
    dualPane: Boolean,
    selectedTab: String,
    selectionPresent: Boolean,
): Boolean =
    desktopModeOnLargeScreen &&
        dualPane &&
        retainedAppShellSelectedTab(selectedTab) == "tasks" &&
        selectionPresent

/**
 * task #13 destination preservation: once the wide rail (or an inline rail destination like
 * Search) has let the user reach Activity — a rail-only key absent from the legacy ordered
 * tab set — narrowing back to compact must not silently evict that live selection to Home.
 * The Activity key stays allowed for the rest of the shell session once reachable; unrelated
 * keys still clamp to Home exactly as before.
 */
internal fun appShellAllowedTabKeys(
    tabKeys: List<String>,
    railActive: Boolean,
    railInlineSecondaryDestination: String?,
    activityReachedThisSession: Boolean,
): List<String> =
    if (railActive || railInlineSecondaryDestination != null || activityReachedThisSession) {
        if ("activity" in tabKeys) tabKeys else tabKeys + "activity"
    } else {
        tabKeys
    }

internal fun shouldHandleAppShellTabSelection(currentTab: String, nextTab: String): Boolean =
    retainedAppShellSelectedTab(currentTab) != retainedAppShellSelectedTab(nextTab)

/**
 * task #45: whether the ON→OFF transition should clear the rail-only transient
 * surfaces (inline secondary destination, slack-nav More menu) exactly once.
 *
 * Pure decision for the LaunchedEffect in the shell composition: the cleanup is
 * edge-triggered — it runs only when `desktopModeOnLargeScreen` flips true→false
 * while armed. While OFF the user may reopen those surfaces, so a persistent
 * predicate would wipe them; a cold start that begins OFF must not clear state
 * it never created. Returns true when the caller should reset
 * `railInlineSecondaryDestination` now, and the next
 * armed latch value.
 */
internal fun desktopModeOffCleanupDecision(
    desktopModeOnLargeScreen: Boolean,
    cleanupArmed: Boolean,
): Pair<Boolean, Boolean> =
    if (desktopModeOnLargeScreen) {
        false to true
    } else if (cleanupArmed) {
        true to false
    } else {
        false to false
    }

internal fun shouldDismissHomeNotificationCenterForAppShellTabSelection(currentTab: String, targetTab: String): Boolean =
    retainedAppShellSelectedTab(currentTab) == "home" &&
        retainedAppShellSelectedTab(targetTab) != "home"

internal data class AppShellLaunchSelection(
    val selectedTab: String = "home",
)

internal data class DiagnosticsDeepLinkRequest(
    val id: String,
    val autoCloseMs: Long = 0L
)

internal fun JSONObject.appShellLaunchSelection(): AppShellLaunchSelection {
    return AppShellLaunchSelection(selectedTab = raftAppShellLaunchTab(optString(AppShellInitialTabKey)))
}

internal fun JSONObject.diagnosticsDeepLinkRequest(): DiagnosticsDeepLinkRequest? {
    val id = optString(DiagnosticsDeepLinkIdKey).takeIf { it.isNotBlank() } ?: return null
    return DiagnosticsDeepLinkRequest(
        id = id,
        autoCloseMs = optLong(DiagnosticsDeepLinkAutoCloseMsKey, 0L).coerceAtLeast(0L)
    )
}

internal fun appShellActiveChildPagerTab(
    selectedTab: String,
    childPageTabKeys: List<String> = AppShellChildPageTabKeys
): String? =
    retainedAppShellSelectedTab(selectedTab).takeIf { it in childPageTabKeys }

// V2 keepalive stack: the ordered set of child tabs to keep composed (alive) at once.
// Empty when there is no active child tab (non-child surfaces render directly). The
// active tab is always included even if [mountedTabs] is momentarily stale, so it can
// never fail to render. [childPageTabKeys] is the active ordered key set (legacy while the
// Slack-nav gate is off, Slack-aligned otherwise).
internal fun appShellRetainedChildTabs(
    activeChildTab: String?,
    mountedTabs: Set<String>,
    childPageTabKeys: List<String> = AppShellChildPageTabKeys
): List<String> =
    if (activeChildTab == null) {
        emptyList()
    } else {
        (childPageTabKeys.filter { it in mountedTabs } + activeChildTab).distinct()
    }

internal fun appShellChildPagePrewarmOrder(
    activeChildTab: String?,
    retainedTabs: Set<String>,
    prewarmedTabs: Set<String>
): List<String> =
    AppShellChildPagePrewarmOrder.filter { tab ->
        tab != activeChildTab && tab !in retainedTabs && tab !in prewarmedTabs
    }

internal fun appShellChildPageRendersContent(active: Boolean, retained: Boolean): Boolean =
    active || retained

/**
 * Whether an app-shell child tab's content runs as active. [placedActive] is the tab pager's
 * own placement decision (the child page's `LocalRaftPageActive`), which is synchronous with the
 * selection; the selection mirrored into [AppShellChildTabEntry] trails it by a frame.
 */
internal fun appShellChildTabContentActive(parentVisible: Boolean, placedActive: Boolean): Boolean =
    parentVisible && placedActive

internal fun appShellChildTabIsActive(
    parentVisible: Boolean,
    selectedTab: String,
    tab: String
): Boolean = parentVisible && retainedAppShellSelectedTab(selectedTab) == tab

internal fun appShellNextChildTabSwitchEpoch(currentTab: String, nextTab: String, currentEpoch: Int): Int =
    if (retainedAppShellSelectedTab(currentTab) == retainedAppShellSelectedTab(nextTab)) {
        currentEpoch
    } else {
        currentEpoch + 1
    }

private fun isRetainedAppShellTab(tab: String): Boolean =
    tab in retainedAppShellTabKeys

internal fun retainedHomeSecondaryDestination(destination: String?): String? =
    destination?.takeIf { it in homeSecondaryDestinationKeys }

// task #383: the Activity destination does not self-refresh on app foreground
// (its "own lifecycle effects" only fire on Kuikly push/pop), so the shell
// resume path must route a ForegroundRefresh to it. Saved/Search keep the old
// contract (their surfaces still own their resume refresh).
internal fun homeDestinationRefreshesOnLifecycleResume(destination: String?): Boolean =
    retainedHomeSecondaryDestination(destination).let { it == null || it == HomeDestinationActivity }

/**
 * How the parent detail pane gives up its space when Focus mode owns the row.
 *
 * task #180 P0: the pane used to collapse with `Modifier.weight(0f)`, which is **illegal** in
 * Compose -- it throws `IllegalArgumentException("invalid weight 0.0")` and crashed the app the
 * moment Focus was entered. A zero WIDTH is legal, so Focus collapses the pane by width and the
 * pane takes no weight at all.
 *
 * Exposed as a pure description (not a Modifier) so tests can pin the invariant: when focused the
 * pane is zero-width and carries no weight, and the weight used anywhere else is always > 0.
 */
internal data class RaftParentPaneLayout(
    val widthDp: Float?,
    val weight: Float?,
)

internal fun raftParentPaneLayout(focused: Boolean): RaftParentPaneLayout =
    if (focused) {
        RaftParentPaneLayout(widthDp = 0f, weight = null)
    } else {
        RaftParentPaneLayout(widthDp = null, weight = RAFT_PARENT_PANE_WEIGHT)
    }

/** The only weight the parent pane may ever take. Must be > 0 -- Compose rejects zero. */
internal const val RAFT_PARENT_PANE_WEIGHT: Float = 1f

internal fun appShellAppearanceGeneration(
    pageVisibleTick: Int,
    initialAuthRestoreTick: Int
): Int = pageVisibleTick + initialAuthRestoreTick

internal fun appShellAppearanceShouldRefresh(
    appearanceGeneration: Int,
    pageVisibleTick: Int,
    selectedTab: String,
    selectedHomeDestination: String?,
    hasRetainedHomeContent: Boolean
): Boolean {
    if (appearanceGeneration <= 0) return false
    if (pageVisibleTick > 0) return true
    // A new pager can attach to process-retained Home state after missing the native resume event.
    return retainedAppShellSelectedTab(selectedTab) == "home" &&
        selectedHomeDestination == null &&
        hasRetainedHomeContent
}

internal fun appShellLifecycleCanRefreshVisibleRoute(
    pageVisible: Boolean,
    lifecycleEvent: String
): Boolean =
    pageVisible &&
        (
            lifecycleEvent == RaftRealtimeNotifyContract.LifecycleVisibleResume ||
                lifecycleEvent == RaftRealtimeNotifyContract.LifecycleVisibleShow
            )

internal data class AppShellForegroundResumePlan(
    val refreshVisibleRoute: Boolean,
    val refreshActivityUnreadSummary: Boolean,
    val refreshServerMembers: Boolean
)

internal fun appShellForegroundResumePlan(
    pageVisible: Boolean,
    selectedTab: String,
    selectedHomeDestination: String?
): AppShellForegroundResumePlan? {
    if (!pageVisible) return null
    return when (retainedAppShellSelectedTab(selectedTab)) {
        "home" ->
            when {
                selectedHomeDestination.isNullOrBlank() ->
                    AppShellForegroundResumePlan(
                        refreshVisibleRoute = true,
                        refreshActivityUnreadSummary = true,
                        refreshServerMembers = true
                    )
                // task #383: Activity destination joins the visible-route
                // refresh; unread summary and members stay scoped to Home.
                retainedHomeSecondaryDestination(selectedHomeDestination) == HomeDestinationActivity ->
                    AppShellForegroundResumePlan(
                        refreshVisibleRoute = true,
                        refreshActivityUnreadSummary = false,
                        refreshServerMembers = false
                    )
                else ->
                    null
            }
        else ->
            AppShellForegroundResumePlan(
                refreshVisibleRoute = true,
                refreshActivityUnreadSummary = false,
                refreshServerMembers = false
            )
    }
}

internal enum class AppShellVisibleRefreshSignal {
    Lifecycle,
    PageDidAppear
}

internal data class AppShellVisibleRefreshKey(
    val appearanceGeneration: Int,
    val target: String
)

internal class AppShellVisibleRefreshGate {
    private val seenSignalsByKey =
        mutableMapOf<AppShellVisibleRefreshKey, MutableSet<AppShellVisibleRefreshSignal>>()
    private val keyOrder = mutableListOf<AppShellVisibleRefreshKey>()
    private val retiredThroughGenerationByTarget = mutableMapOf<String, Int>()
    private var pendingLifecycleGeneration: Int? = null
    // Process foreground is the refresh authority. PageDidAppear remains a fallback when a
    // newly observed process epoch reached this mounted shell while its pager was still hidden.
    private var latestProcessForegroundEpoch: Long? = null
    private var refreshedProcessForegroundEpoch: Long? = null

    fun observeLifecycleForegroundEpoch(foregroundEpoch: Long) {
        if (foregroundEpoch <= 0L) return
        latestProcessForegroundEpoch =
            maxOf(latestProcessForegroundEpoch ?: Long.MIN_VALUE, foregroundEpoch)
    }

    fun onLifecycleHidden(
        appearanceGeneration: Int,
        foregroundEpoch: Long? = null
    ) {
        foregroundEpoch?.let(::observeLifecycleForegroundEpoch)
        pendingLifecycleGeneration =
            maxOf(
                pendingLifecycleGeneration ?: Int.MIN_VALUE,
                appearanceGeneration + 1
            )
    }

    fun shouldRefresh(
        signal: AppShellVisibleRefreshSignal,
        appearanceGeneration: Int,
        target: String,
        foregroundEpoch: Long? = null
    ): Boolean {
        foregroundEpoch?.let(::observeLifecycleForegroundEpoch)
        val effectiveForegroundEpoch = latestProcessForegroundEpoch
        if (
            effectiveForegroundEpoch != null &&
            effectiveForegroundEpoch <=
                (refreshedProcessForegroundEpoch ?: Long.MIN_VALUE)
        ) {
            return false
        }
        val currentKey = AppShellVisibleRefreshKey(appearanceGeneration, target)
        val currentSignals = seenSignalsByKey[currentKey]
        val retiredThroughGeneration =
            retiredThroughGenerationByTarget.getOrElse(target) { Int.MIN_VALUE }
        val key =
            when (signal) {
                AppShellVisibleRefreshSignal.PageDidAppear -> {
                    if ((pendingLifecycleGeneration ?: Int.MAX_VALUE) <= appearanceGeneration) {
                        pendingLifecycleGeneration = null
                    }
                    currentKey
                }
                AppShellVisibleRefreshSignal.Lifecycle -> {
                    val pendingGeneration = pendingLifecycleGeneration
                    when {
                        pendingGeneration != null && pendingGeneration > appearanceGeneration -> {
                            pendingLifecycleGeneration = null
                            AppShellVisibleRefreshKey(pendingGeneration, target)
                        }
                        appearanceGeneration <= retiredThroughGeneration -> currentKey
                        currentSignals?.contains(AppShellVisibleRefreshSignal.PageDidAppear) == true &&
                            AppShellVisibleRefreshSignal.Lifecycle !in currentSignals -> currentKey
                        currentSignals?.contains(AppShellVisibleRefreshSignal.Lifecycle) == true -> currentKey
                        else -> AppShellVisibleRefreshKey(appearanceGeneration + 1, target)
                    }
                }
            }
        if (
            key.appearanceGeneration <=
            retiredThroughGenerationByTarget.getOrElse(key.target) { Int.MIN_VALUE }
        ) {
            if (signal == AppShellVisibleRefreshSignal.Lifecycle && effectiveForegroundEpoch != null) {
                refreshedProcessForegroundEpoch = effectiveForegroundEpoch
            }
            return false
        }
        val seenSignals = seenSignalsByKey[key]
        if (seenSignals != null) {
            seenSignals += signal
            if (signal == AppShellVisibleRefreshSignal.Lifecycle && effectiveForegroundEpoch != null) {
                refreshedProcessForegroundEpoch = effectiveForegroundEpoch
            }
            return false
        }
        seenSignalsByKey[key] = mutableSetOf(signal)
        if (effectiveForegroundEpoch != null) {
            refreshedProcessForegroundEpoch = effectiveForegroundEpoch
        }
        keyOrder += key
        while (keyOrder.size > MaxRememberedAppearanceKeys) {
            val retiredKey = keyOrder.removeAt(0)
            seenSignalsByKey.remove(retiredKey)
            retiredThroughGenerationByTarget[retiredKey.target] =
                maxOf(
                    retiredThroughGenerationByTarget.getOrElse(retiredKey.target) { Int.MIN_VALUE },
                    retiredKey.appearanceGeneration
                )
        }
        return true
    }

    private companion object {
        const val MaxRememberedAppearanceKeys = 16
    }
}

internal fun requestAppShellInitialAuthRestore(
    dispatch: (SharedAuthGateIntent) -> Unit
) {
    dispatch(SharedAuthGateIntent.Restore)
}

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
        "dms" -> "kuikly_dms"
        "activity" -> "kuikly_activity"
        "more" -> "kuikly_more"
        AppShellSearchTabKey -> "kuikly_search"
        else -> "kuikly_home"
    }

internal fun appShellBottomTabPerformancePhase(tab: String): String =
    "bottom_tab_${retainedAppShellSelectedTab(tab)}"

/**
 * Navigation rule (stdrc 2026-10-21): a page with Back at the top shows no bottom tab bar; a
 * page without Back is a tab root and shows it. The roots (Home, Activity, Tasks, More = the
 * Settings page) show the bar; an in-shell child page that carries Back hides it — Members
 * pushed from More ([appShellMoreChildBackTarget]). Home secondary destinations and every other
 * Back page (settings groups, Computers, threads, task details) are native pages pushed over the
 * shell, so the bar stays mounted behind them. The wide nav rail never shows the bar (callers
 * also gate on it).
 */
internal fun appShellBottomTabsVisible(
    selectedTab: String,
    selectedHomeDestination: String?,
    navRailActive: Boolean = false,
): Boolean =
    when (retainedAppShellSelectedTab(selectedTab)) {
        // Home secondary destinations are native child pages. Keep the root tab bar mounted
        // behind them so returning does not recreate it after the root is already visible.
        "home" ->
            selectedHomeDestination == null ||
                retainedHomeSecondaryDestination(selectedHomeDestination) != null
        else -> appShellMoreChildBackTarget(selectedTab, navRailActive) == null
    }

/**
 * Navigation rule on a window that shrank below the wide layout (a tablet turned to portrait, a
 * foldable folded, a split-screen resize): the Home / Activity list-detail host keeps the open
 * conversation and shows it alone (DetailOnly) with its own Back, so it is a pushed page and the
 * bottom bar steps aside, as it does for a conversation opened on a phone.
 */
internal fun appShellAdaptiveDetailCoversTabRoot(
    desktopModeOnLargeScreen: Boolean,
    selectedTab: String,
    presentation: RaftAdaptiveWorkspacePresentation?,
): Boolean =
    desktopModeOnLargeScreen &&
        presentation == RaftAdaptiveWorkspacePresentation.DetailOnly &&
        retainedAppShellSelectedTab(selectedTab) in AppShellAdaptiveListDetailTabs

/** The tabs whose root is the shared list-detail host ([appShellAdaptiveDetailCoversTabRoot]). */
internal val AppShellAdaptiveListDetailTabs: Set<String> = setOf("home", "activity")

internal fun sharedHomeShouldStartOnActive(active: Boolean, sessionReady: Boolean, state: SharedHomeState): Boolean =
    active &&
        sessionReady &&
        (
            state.content !is SharedHomeContentState.Content ||
                state.homeRealtimeDirty ||
                (state.content as? SharedHomeContentState.Content)?.needsNetworkRefresh == true
        )

internal fun appShellParentShouldStartHome(
    selectedTab: String,
    selectedHomeDestination: String?,
    readyServerId: String?,
    state: SharedHomeState,
    pageVisible: Boolean = true,
    readyAccountId: String? = null,
    homeLikeTabs: Set<String> = emptySet()
): Boolean =
    pageVisible &&
        retainedAppShellSelectedTab(selectedTab).let { tab ->
            tab == "home" || tab in homeLikeTabs
        } &&
        selectedHomeDestination == null &&
        !readyServerId.isNullOrBlank() &&
        (
            state.content.let { content ->
                content is SharedHomeContentState.Content &&
                    (
                        content.snapshot.server.id.value != readyServerId ||
                            (
                                readyAccountId != null &&
                                    content.snapshot.currentMember.id.value != readyAccountId
                            )
                    )
            } ||
                sharedHomeShouldStartOnActive(active = true, sessionReady = true, state = state)
        )

internal fun appShellAuthoritativeHomeStartAllowed(
    authGateState: SharedAuthGateState,
    setupAdmissionAllowsHome: Boolean
): Boolean {
    val ready = authGateState as? SharedAuthGateState.Ready ?: return false
    return setupAdmissionAllowsHome && ready.phase != SharedAuthReadyPhase.CachedRestorePending
}

/**
 * Hands 79db9fe6 / e486495b (artin): a tap on a channel in the identity-bound cached Home opens it
 * while the session and server admission are still being confirmed in the background; it used to
 * be dropped (`home_open_conversation_drop reason=home_authority_pending`) until the restore ended.
 */
internal fun appShellHomeConversationOpenAllowed(
    authoritativeHomeStartAllowed: Boolean,
    cachedRestoreUsable: Boolean
): Boolean = authoritativeHomeStartAllowed || cachedRestoreUsable

internal fun appShellCanRenderCachedHomePreview(
    readySession: StoredAuthSession?,
    readyPhase: SharedAuthReadyPhase?,
    featureDecision: ServerSetupFeatureDecision,
    setupAdmissionAllowsHome: Boolean,
    setupAdmissionState: ServerSetupAdmissionState? = null,
    readyServerId: String?,
    selectedTab: String = "home",
    selectedHomeDestination: String? = null,
    pageVisible: Boolean = true,
    state: SharedHomeState
): Boolean {
    if (!pageVisible || retainedAppShellSelectedTab(selectedTab) != "home" || selectedHomeDestination != null) {
        return false
    }
    val session = readySession ?: return false
    val phase = readyPhase ?: return false
    if (phase == SharedAuthReadyPhase.Confirmed) return false
    if (
        phase == SharedAuthReadyPhase.CachedRestoreCompleted &&
        featureDecision != ServerSetupFeatureDecision.Checking
    ) {
        return false
    }
    if (setupAdmissionState is ServerSetupAdmissionState.Blocked || setupAdmissionState is ServerSetupAdmissionState.Failed) {
        return false
    }
    if (
        !setupAdmissionAllowsHome &&
        featureDecision != ServerSetupFeatureDecision.Checking &&
        featureDecision != ServerSetupFeatureDecision.Bypassed
    ) {
        return false
    }
    val serverId = readyServerId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val content = state.content as? SharedHomeContentState.Content ?: return false
    return content.isDurableIdentityCache &&
        content.needsNetworkRefresh &&
        content.snapshot.server.id.value == serverId &&
        content.snapshot.currentMember.id.value == session.user.id &&
        content.session.user.id == session.user.id &&
        content.session.currentServerId == serverId &&
        session.servers.any { it.id == serverId }
}

internal fun appShellNavigationServerId(
    selectedServerId: String?,
    renderedHomeServerId: String?,
    scopeInitialServerId: String?
): String? =
    selectedServerId?.trim()?.takeIf { it.isNotBlank() }
        ?: renderedHomeServerId?.trim()?.takeIf { it.isNotBlank() }
        ?: scopeInitialServerId?.trim()?.takeIf { it.isNotBlank() }

private fun sharedHomeContentRenderSummary(content: SharedHomeContentState): String =
    when (content) {
        is SharedHomeContentState.Loading ->
            "Loading(session=${content.session?.currentServerId ?: "_"})"
        is SharedHomeContentState.Empty ->
            "Empty(session=${content.session.currentServerId ?: "_"})"
        is SharedHomeContentState.Error ->
            "Error(${content.code},session=${content.session?.currentServerId ?: "_"})"
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
        is SharedHomeContentState.Error ->
            if (content.session != null) "error_session_shell_${content.code}" else "error_${content.code}"
        is SharedHomeContentState.Content -> "content"
    }

private fun sharedHomeErrorDisplay(code: String): RaftHomeInlineError =
    RaftHomeInlineError(
        title =
            when {
                ScreenError(code, retryable = false).isAuthSessionError() -> "Session expired"
                code.contains("permission", ignoreCase = true) -> "Home is unavailable"
                code.contains("timeout", ignoreCase = true) -> "Connection timed out"
                code.contains("offline", ignoreCase = true) -> "You are offline"
                code.contains("network", ignoreCase = true) -> "Could not refresh Home"
                else -> "Could not load Home"
            },
        detail =
            when {
                ScreenError(code, retryable = false).isAuthSessionError() ->
                    "Sign out and sign in again, or switch to another available server."
                code.contains("permission", ignoreCase = true) ->
                    "You can still switch servers or open account actions while this server recovers."
                code.contains("timeout", ignoreCase = true) ->
                    "The server did not respond in time. Retry, or switch servers if another workspace is available."
                code.contains("offline", ignoreCase = true) ->
                    "Check your connection, then retry. Server switch and account actions remain available."
                code.contains("network", ignoreCase = true) ->
                    "The current server could not be reached. Retry, or switch servers if another workspace is available."
                else ->
                    "Retry Home refresh, or use server switch and account actions to recover."
            },
        code = code,
        actionLabel = "Retry"
    )

internal fun sharedHomeSearchErrorDisplay(
    code: String,
    errorStrings: ErrorPresentationStrings
): RaftHomeInlineError =
    RaftHomeInlineError(
        title =
            when {
                code.contains("permission", ignoreCase = true) -> "Search is unavailable"
                code.contains("timeout", ignoreCase = true) -> "Search timed out"
                code.contains("offline", ignoreCase = true) -> "You are offline"
                code.contains("network", ignoreCase = true) -> "Could not refresh Search"
                else -> "Could not load Search"
            },
        detail =
            when {
                code.contains("permission", ignoreCase = true) ->
                    "You do not have access to search this workspace right now."
                code.contains("timeout", ignoreCase = true) ->
                    "The server did not respond in time. Retry search after a moment."
                code.contains("offline", ignoreCase = true) ->
                    "Check your connection, then retry search."
                code.contains("network", ignoreCase = true) ->
                    "The current server could not be reached. Retry search after it reconnects."
                else ->
                    code.userFacingErrorCodeMessage(errorStrings)
            },
        code = code.ifBlank { "unknown_error" },
        actionLabel = "Retry"
    )

@Composable
private fun localizedSharedHomeErrorDisplay(
    code: String,
    serverMessage: String? = null
): RaftHomeInlineError =
    RaftHomeInlineError(
        title =
            when {
                serverMessage.isEdgeBlockedMarker() -> stringResource(Res.string.home_error_unavailable)
                ScreenError(code, retryable = false).isAuthSessionError() -> stringResource(Res.string.home_error_session_expired)
                code.contains("permission", ignoreCase = true) -> stringResource(Res.string.home_error_unavailable)
                code.contains("timeout", ignoreCase = true) -> stringResource(Res.string.home_error_timeout)
                code.contains("offline", ignoreCase = true) -> stringResource(Res.string.home_error_offline)
                code.contains("network", ignoreCase = true) -> stringResource(Res.string.home_error_refresh)
                else -> stringResource(Res.string.home_error_load)
            },
        detail =
            when {
                serverMessage.isEdgeBlockedMarker() -> stringResource(Res.string.error_edge_blocked)
                ScreenError(code, retryable = false).isAuthSessionError() -> stringResource(Res.string.home_error_session_detail)
                code.contains("permission", ignoreCase = true) -> stringResource(Res.string.home_error_unavailable_detail)
                code.contains("timeout", ignoreCase = true) -> stringResource(Res.string.home_error_timeout_detail)
                code.contains("offline", ignoreCase = true) -> stringResource(Res.string.home_error_offline_detail)
                code.contains("network", ignoreCase = true) -> stringResource(Res.string.home_error_network_detail)
                else -> stringResource(Res.string.home_error_load_detail)
            },
        code = code,
        actionLabel = stringResource(Res.string.common_retry)
    )

@Composable
private fun localizedSharedHomeSearchErrorDisplay(
    code: String,
    action: SharedHomeSearchSavedIntent? = SharedHomeSearchSavedIntent.RefreshSearch
): RaftHomeInlineError =
    RaftHomeInlineError(
        title =
            when {
                code == "QUERY_TOO_BROAD" -> stringResource(Res.string.search_error_too_broad)
                code.contains("permission", ignoreCase = true) -> stringResource(Res.string.search_error_unavailable)
                code.contains("timeout", ignoreCase = true) -> stringResource(Res.string.search_error_timeout)
                code.contains("offline", ignoreCase = true) -> stringResource(Res.string.home_error_offline)
                code.contains("network", ignoreCase = true) -> stringResource(Res.string.search_error_refresh)
                else -> stringResource(Res.string.search_error_load)
            },
        detail =
            when {
                code == "QUERY_TOO_BROAD" -> stringResource(Res.string.search_error_too_broad_detail)
                code.contains("permission", ignoreCase = true) -> stringResource(Res.string.search_error_permission_detail)
                code.contains("timeout", ignoreCase = true) -> stringResource(Res.string.search_error_timeout_detail)
                code.contains("offline", ignoreCase = true) -> stringResource(Res.string.search_error_offline_detail)
                code.contains("network", ignoreCase = true) -> stringResource(Res.string.search_error_network_detail)
                else -> code.userFacingErrorCodeMessage(localizedErrorPresentationStrings())
            },
        code = code.ifBlank { "unknown_error" },
        actionLabel = when (action) {
            is SharedHomeSearchSavedIntent.SetSearchSort -> stringResource(Res.string.search_error_sort_recent)
            null -> ""
            else -> stringResource(Res.string.common_retry)
        }
    )

internal fun sharedHomeHeaderRefreshing(state: SharedHomeState): Boolean =
    (state.content as? SharedHomeContentState.Content)?.contentRefreshing == true

internal fun homeSurfaceVisibilityDispatchDelayMs(visible: Boolean): Long =
    if (visible) HomeSurfaceVisibleResumeDelayMs else 0L

@Composable
private fun AppShellTabPager(
    selectedTab: String,
    pageVisible: Boolean,
    childHostShellId: String,
    mountedTabs: Set<String>,
    childTabSwitchEpoch: Int,
    childPageTabKeys: List<String> = AppShellChildPageTabKeys,
    log: (String) -> Unit = {},
    content: @Composable (tab: String, active: Boolean) -> Unit
) {
    val activeTab = retainedAppShellSelectedTab(selectedTab)
    val activeChildTab = appShellActiveChildPagerTab(activeTab, childPageTabKeys)
    // Keepalive stack (V2): render every retained child tab, not just the active one,
    // so Home/Tasks/Members/Settings stay alive and switching back is instant with
    // full state. Only the selected child is visible; the rest are hard-hidden via the
    // native `active` guard (visibility=INVISIBLE), so an inactive child's late render
    // can never reach the visible layer. Pixel visibility follows the selected tab
    // even while this shell page is covered (`pageVisible` false). Hiding the
    // selected tab on disappear leaves a frame with no active child when a channel
    // page is removed before the shell's appear event, and the clear container
    // flashes white. Lifecycle still follows the pager appear events. There is no
    // HorizontalPager/scrollToPage, so none of the pager scroll races (overlay /
    // blank / stale native page) can occur. The active tab is always in the list
    // even if mountedTabs is momentarily stale.
    val retainedChildTabs =
        appShellRetainedChildTabs(activeChildTab, mountedTabs, childPageTabKeys)
    LaunchedEffect(activeChildTab, childTabSwitchEpoch, retainedChildTabs) {
        log(
            "app_shell_keepalive_stack active=$activeChildTab epoch=$childTabSwitchEpoch " +
                "retained=$retainedChildTabs mountedTabs=$mountedTabs"
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (activeChildTab != null) {
            retainedChildTabs.forEach { tab ->
                key("app-shell-$tab") {
                    RaftAppShellChildTabHost(
                        shellId = childHostShellId,
                        tab = tab,
                        active = tab == activeChildTab
                    )
                }
            }
        } else {
            key("app-shell-$activeTab") {
                content(activeTab, pageVisible)
            }
        }
    }
}

@Composable
private fun RaftAppShellChildTabHost(
    shellId: String,
    tab: String,
    active: Boolean
) {
    val glassStyle = appShellChildPageGlassStyle(raftAppBottomTabBarOverlaysContent())
    val pageData =
        remember(shellId, tab) {
            JSONObject().apply {
                put("shellId", shellId)
                put("tab", tab)
            }
        }
    key("app-shell-child-page-$tab") {
        RaftChildPage(
            pageName = RAFT_APP_SHELL_CHILD_TAB_PAGE,
            pageData = pageData,
            modifier =
                Modifier
                    .fillMaxSize(),
            active = active,
            glassStyle = glassStyle
        )
    }
}

@Composable
internal fun rememberRaftServerDrawerUserHeader(
    readySession: StoredAuthSession?
): RaftServerDrawerUserHeader =
    remember(
        readySession?.user?.id,
        readySession?.user?.displayName,
        readySession?.user?.name,
        readySession?.user?.email,
        readySession?.user?.avatarUrl,
        readySession?.currentServerId
    ) {
        val user = readySession?.user
        val displayName = user?.bestDisplayName ?: "Raft user"
        RaftServerDrawerUserHeader(
            displayName = displayName,
            memberId = user?.id,
            serverId = readySession?.currentServerId,
            avatarWireValue = user?.avatarUrl,
            avatarLabel = displayName.take(2).uppercase().ifBlank { "?" }
        )
    }

@Composable
internal fun RaftAppShellContent(
    pageVisibleTick: Int = 0,
    initialAuthRestoreTick: Int = 0,
    pageVisible: Boolean = true,
    adaptivePane: RaftAdaptivePaneState = RaftAdaptivePaneState(),
    conversationForwardRequestTick: Int = 0,
    searchHandoff: RaftSearchTabQueryRequest? = null,
    onSearchHandoffApplied: () -> Unit = {}
) {
    // #85 follow-up: seed the KmmResource `StringDesc.localeType`
    // global from the persisted `AppLocale` as early as possible
    // in the app shell lifecycle, so shared `StringDesc` lookups resolve
    // against the user's last selection rather than the OS default.
    RememberKmmResourceLocaleSeedFromLocalActivity(pageVisibleTick)
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    var databaseLaunchRetryEpoch by remember { mutableStateOf(0) }
    val databaseLaunchAttempt =
        remember(bridge, databaseLaunchRetryEpoch) {
            attemptRaftDatabaseLaunch { RaftSharedStoreRegistry.get(bridge) }
        }
    when (databaseLaunchAttempt) {
        is RaftDatabaseLaunchAttempt.Ready ->
            RaftAppShellReadyContent(
                pageVisibleTick = pageVisibleTick,
                initialAuthRestoreTick = initialAuthRestoreTick,
                pageVisible = pageVisible,
                adaptivePane = adaptivePane,
                conversationForwardRequestTick = conversationForwardRequestTick,
                searchHandoff = searchHandoff,
                onSearchHandoffApplied = onSearchHandoffApplied,
                sharedStores = databaseLaunchAttempt.value,
            )
        is RaftDatabaseLaunchAttempt.UpgradeRequired -> {
            val mismatch = databaseLaunchAttempt.mismatch
            LaunchedEffect(mismatch) {
                bridge.logWarn(
                    "database_launch_guard result=upgrade_required " +
                        "databaseVersion=${mismatch.databaseVersion} " +
                        "supportedVersion=${mismatch.supportedVersion}",
                )
            }
            RaftDatabaseLaunchRecoveryContent(
                mismatch = mismatch,
                onRetry = {
                    bridge.logWarn(
                        "database_launch_guard action=retry " +
                            "databaseVersion=${mismatch.databaseVersion} " +
                            "supportedVersion=${mismatch.supportedVersion}",
                    )
                    databaseLaunchRetryEpoch += 1
                },
            )
        }
        is RaftDatabaseLaunchAttempt.Fatal -> throw databaseLaunchAttempt.throwable
    }
}

@Composable
private fun RaftAppShellReadyContent(
    pageVisibleTick: Int,
    initialAuthRestoreTick: Int,
    pageVisible: Boolean,
    adaptivePane: RaftAdaptivePaneState,
    conversationForwardRequestTick: Int,
    searchHandoff: RaftSearchTabQueryRequest? = null,
    onSearchHandoffApplied: () -> Unit = {},
    sharedStores: RaftSharedStoreProvider,
) {
    val errorStrings = localizedErrorPresentationStrings()
    val conversationNotReadyMessage = stringResource(Res.string.home_error_conversation_not_ready)
    val notificationServerUnavailableMessage = stringResource(Res.string.home_error_notification_server_unavailable)
    val notificationSignInMessage = stringResource(Res.string.home_error_notification_sign_in)
    val channelCreatedMessage = stringResource(Res.string.thread_action_card_success_channel)
    val channelNotCreatedMessage = stringResource(Res.string.channel_create_error_not_created)
    val conversationUnavailableMessage = stringResource(Res.string.home_error_conversation_unavailable)
    val pager = LocalActivity.current.getPager() as Pager
    val timestampDisplayState =
        LocalRaftTimestampDisplayState.current ?: remember { RaftTimestampDisplayState() }
    val launchSelection = remember(pager.pageData.params.toString()) {
        pager.pageData.params.appShellLaunchSelection()
    }
    val diagnosticsDeepLinkRequest = remember(pager.pageData.params.toString()) {
        pager.pageData.params.diagnosticsDeepLinkRequest()
    }
    val suppressRestoreLanding = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optBoolean(APP_SHELL_SUPPRESS_RESTORE_LANDING, false)
    }
    // task #45: the user-facing "Desktop mode on large screens" toggle (Settings >
    // Mobile, default ON) replaces the two QA-only adaptive params. The persisted
    // value lands via the chat-layout snapshot runtime authority below.
    var desktopModeOnLargeScreen by remember {
        mutableStateOf(DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN)
    }
    val authGateInstanceId = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optString(APP_SHELL_AUTH_GATE_INSTANCE_ID)
    }
    var selectedTab by remember { mutableStateOf(launchSelection.selectedTab) }
    var childTabSwitchEpoch by remember { mutableStateOf(0) }
    var homeFocusLossDismissEpoch by remember { mutableStateOf(0) }
    var retainedAppShellTabs by remember {
        mutableStateOf(setOf("home", launchSelection.selectedTab))
    }
    var prewarmedAppShellTabs by remember { mutableStateOf(emptySet<String>()) }
    val prewarmAdmissionGate = remember { sharedPrewarmAdmissionGate() }
    val childPagePrewarmPolicy =
        remember { appShellChildPagePrewarmPolicy(raftPlatformAppShellChildPagePrewarmMode()) }
    var settingsHeaderTapCount by remember { mutableStateOf(0) }
    var homeListPositions by remember { mutableStateOf(AppShellServerLazyListPositions()) }
    var pendingHomeServerSwitchId by remember { mutableStateOf<String?>(null) }
    var observedHomeServerSwitchId by remember { mutableStateOf<String?>(null) }
    var tasksListPosition by remember { mutableStateOf(AppShellLazyListPosition()) }
    var membersListState by remember { mutableStateOf(AppShellMembersListState()) }
    var adaptiveDefaultLandingServerId by remember { mutableStateOf<String?>(null) }
    var tabScrollToTopRequests by remember { mutableStateOf(AppShellTabScrollToTopRequests()) }
    val tasksSurfaceActive = selectedTab == "tasks"
    // D1a (raft-design #712): the server panel the current-server tab (phone bar) or the rail's
    // foot entry opens; the page that showed when it opened a server page (Back returns there);
    // and focus going back to the server tab after such a page closes.
    val serverMenuState = rememberRaftMobileNavServerPopoverState()
    var serverPageOrigin by remember { mutableStateOf<String?>(null) }
    var serverTabFocusPending by remember { mutableStateOf(false) }
    var serverTabFocusOnPageReturn by remember { mutableStateOf(false) }
    // Server switcher "Invite Human" (web ServerSwitcherMenu): the server whose
    // invite dialog is open, or null.
    var serverSwitcherInviteServerId by remember { mutableStateOf<String?>(null) }
    // task #13 (three-pane contract): while the wide LeftRail is active, Search is a rail
    // destination rendered INLINE in the AppShell middle pane — never a pushed secondary page
    // (the rail must stay). Narrow or flag-off keeps the legacy push path unchanged.
    var railInlineSecondaryDestination by remember { mutableStateOf<String?>(null) }
    // page_viewed for the wide rail's inline list panes (Search, Saved): they are not pages of their own.
    val railInlineProductPageSlot = remember(pager.pagerId) { "${pager.pagerId}#rail-inline" }
    val railInlineProductPageView = appShellRailInlineProductPageView(railInlineSecondaryDestination)
    DisposableEffect(railInlineProductPageSlot, railInlineProductPageView) {
        val pageViews = RaftProductEvents.pageViews
        if (railInlineProductPageView != null) {
            pageViews.onPageRoute(railInlineProductPageSlot, railInlineProductPageView)
            pageViews.onPageShown(railInlineProductPageSlot)
        }
        onDispose { pageViews.onPageClosed(railInlineProductPageSlot) }
    }
    // The Search tab's result list position survives leaving and re-entering the tab.
    val searchTabListState = rememberLazyListState()
    val searchTabReturnHighlight = remember { HomeSearchReturnHighlight() }
    // Re-tapping the selected Search tab focuses its field (one increment per tap).
    var searchTabFocusInputRequest by remember { mutableStateOf(0) }
    // A query a conversation search handed to the Search tab ("Search all"), until applied.
    var searchTabQueryRequest by remember { mutableStateOf<RaftSearchTabQueryRequest?>(null) }
    // task #13: once a rail surface exposes Activity (rail active or an inline destination is
    // covering the middle pane) the live tab must survive width transitions — narrowing must
    // not clamp a user-reached Activity selection back to Home.
    var railActivityReached by remember { mutableStateOf(false) }
    val childTabShellId = remember(pager.pagerId) { "app-shell-child-tabs-${pager.pagerId}" }
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val adaptiveWorkspaceStore =
        remember(sharedStores, storeScope) { sharedStores.adaptiveWorkspaceStore(storeScope) }
    val adaptiveWorkspaceSession by adaptiveWorkspaceStore.state.collectAsState()
    val adaptiveWorkspaceState = adaptiveWorkspaceSession.workspace
    val adaptiveDetailLaunchPayload = adaptiveWorkspaceSession.launchPayload
    // task #34: a task opened from the Tasks board owns the bounded detail overlay.
    var tasksDetailOverlayActive by remember { mutableStateOf(false) }
    rememberAuthRuntimeCapabilities(
        store = sharedStores.authRuntimeCapabilityStore,
        pageData = pager.pageData.params
    )
    val visibleAppearanceGeneration =
        appShellAppearanceGeneration(pageVisibleTick, initialAuthRestoreTick)
    val windowNavigationCoordinator =
        remember(sharedStores, storeScope) { sharedStores.windowNavigationCoordinator(storeScope) }
    val conversationForwardHistory =
        remember(sharedStores, storeScope) { sharedStores.conversationForwardHistory(storeScope) }
    var handledConversationForwardHomeVisibleTick by remember { mutableStateOf(0) }
    var handledConversationForwardRequestTick by remember { mutableStateOf(0) }
    LaunchedEffect(pager.pagerId, visibleAppearanceGeneration) {
        if (visibleAppearanceGeneration > 0) {
            sharedStores.markActiveAppShellPager(pager.pagerId)
        }
    }
    val authStore = sharedStores.authStore
    val shellAuthStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val authSessionTerminalSource =
        remember(shellAuthStore) { shellAuthStore.authSessionTerminalSourceOrNull() }
    val selectedServerStore = remember(sharedStores, storeScope) { sharedStores.selectedServerStore(storeScope) }
    val appShellServerMembersStore =
        remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val appShellRealtimeDomainEventOwner =
        remember(sharedStores, storeScope) { sharedStores.realtimeDomainEventOwner(storeScope) }
    val canonicalFinalLifecycleEffects =
        remember(sharedStores) {
            sharedStores.realtimeAuthorityProvider.canonicalFinalLifecycleEffectsOrNull()
        }
    val realtimeAuthorityFlow =
        remember(sharedStores, storeScope) { sharedStores.realtimeAuthority(storeScope) }
    val realtimeAuthority by realtimeAuthorityFlow.collectAsState()
    val processRealtimeAuthorityFlow =
        remember(sharedStores) { sharedStores.processRealtimeAuthority() }
    val processRealtimeAuthority by processRealtimeAuthorityFlow.collectAsState()
    val realtimeCallbackGate =
        remember(sharedStores, appShellRealtimeDomainEventOwner) {
            RealtimeShellCallbackGate(
                authorityProvider = sharedStores.realtimeAuthorityProvider,
                domainOwner = appShellRealtimeDomainEventOwner,
            )
        }
    val appShellAgentDetailStore =
        remember(sharedStores, storeScope) { sharedStores.agentDetailStore(storeScope) }
    val appShellPresenceTransportCursors =
        remember(sharedStores, storeScope) { mutableMapOf<String, AppShellPresenceTransportCursor>() }
    val selectedServerId by selectedServerStore.selectedServerId.collectAsState()
    val canonicalFinalSelectedServerId =
        selectedServerId?.takeIf { it.isNotBlank() }
            ?: storeScope.initialServerId?.takeIf { it.isNotBlank() }
    val canonicalFinalDomainGrant =
        remember(realtimeAuthority, canonicalFinalSelectedServerId) {
            val serverId = canonicalFinalSelectedServerId
            if (serverId == null) null else realtimeAuthority?.grant(serverId)
        }
    val canonicalFinalUnreadGrants =
        remember(realtimeAuthority, canonicalFinalSelectedServerId) {
            realtimeAuthority?.grantsByServer?.values
                ?.filter { grant -> grant.serverId != canonicalFinalSelectedServerId }
                ?.sortedBy(RealtimeGrantRecord::serverId)
                .orEmpty()
        }
    val canonicalFinalDomainSlot =
        remember(canonicalFinalDomainGrant) {
            canonicalFinalDomainGrant?.let { grant -> grant.scopeLeaseId to grant.serverId }
        }
    val canonicalFinalUnreadSlots =
        remember(canonicalFinalUnreadGrants) {
            canonicalFinalUnreadGrants.map { grant -> grant.scopeLeaseId to grant.serverId }
        }
    var activeAppShellCanonicalFinalSinks by
        remember { mutableStateOf<RealtimeGuardedDomainEventSinks?>(null) }
    DisposableEffect(
        appShellRealtimeDomainEventOwner,
        canonicalFinalLifecycleEffects,
        canonicalFinalDomainSlot,
        canonicalFinalUnreadSlots,
        canonicalFinalSelectedServerId,
        selectedServerId,
    ) {
        val canonicalFinalDomainHandle =
            canonicalFinalDomainSlot?.let { (scopeLeaseId, serverId) ->
                canonicalFinalLifecycleEffects?.mount(
                    scopeLeaseId = scopeLeaseId,
                    serverId = serverId,
                    surfaceKey = RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
                ) { event ->
                    val sinksSnapshot = activeAppShellCanonicalFinalSinks ?: throw IllegalStateException()
                    appShellRealtimeDomainEventOwner.deliverGuardedAppShellDomainFinal(
                        sinks = sinksSnapshot,
                        serverId = serverId,
                        event = event,
                    )
                }
            }
        val canonicalFinalUnreadHandles =
            canonicalFinalUnreadSlots.mapNotNull { (scopeLeaseId, serverId) ->
                canonicalFinalLifecycleEffects?.mount(
                    scopeLeaseId = scopeLeaseId,
                    serverId = serverId,
                    surfaceKey = RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread,
                ) { event ->
                    val sinksSnapshot = activeAppShellCanonicalFinalSinks ?: throw IllegalStateException()
                    appShellRealtimeDomainEventOwner.deliverGuardedAppShellHomeUnreadFinal(
                        sinks = sinksSnapshot,
                        serverId = serverId,
                        event = event,
                    )
                }
            }
        onDispose {
            canonicalFinalUnreadHandles.forEach { handle ->
                canonicalFinalLifecycleEffects?.dispose(handle)
            }
            canonicalFinalLifecycleEffects?.dispose(canonicalFinalDomainHandle)
        }
    }
    val appShellCoroutineScope = rememberCoroutineScope()
    // Hands (artin 2026-10-22): raw host Offline waits out a 3s grace before the banner shows.
    val networkBannerGate =
        remember(appShellCoroutineScope) { RaftAppShellNetworkBannerGate(appShellCoroutineScope) }
    val hostNetworkStatus by networkBannerGate.bannerStatus.collectAsState()
    val avatarNativeCache =
        remember(bridge, appShellCoroutineScope) {
            BridgeRaftAvatarNativeCache(
                bridge = bridge,
                bridgeCallContext = appShellCoroutineScope.kuiklyBridgeCallContext()
            )
        }
    val authDataSource = remember(sharedStores, storeScope, avatarNativeCache) {
        sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope, avatarNativeCache)
    }
    val eventBus = sharedStores.eventBus
    /**
     * task #180: the menu a child page has asked the shell to draw. Null when no page is asking.
     * The shell is the only place that can draw outside a child page's pane, which is why this
     * lives here rather than in the requesting page.
     */
    var shellOverlayMenu by remember { mutableStateOf<RaftShellOpenOverlay?>(null) }
    var shellOverlayContent by remember { mutableStateOf<RaftShellOverlayContentEntry?>(null) }
    DisposableEffect(eventBus) {
        val subscription =
            eventBus.observe(RAFT_SHELL_OVERLAY_REQUEST_EVENT) { eventData ->
                // task #180 diagnostics (Argus): point 2 of 3 -- the shell saw a request (or why not).
                val messageType = eventData?.optString(SHELL_OVERLAY_MESSAGE_TYPE)
                val incomingRequestId = eventData?.shellOverlayRequestIdOrNull()
                if (messageType == SHELL_OVERLAY_TYPE_CANCEL) {
                    val current = shellOverlayMenu
                    if (current != null && current.request.requestId == incomingRequestId) {
                        shellOverlayContent = null
                        shellOverlayMenu = null
                    }
                    return@observe
                }
                val request = eventData.toRaftShellOverlayRequestOrNull()
                raftTraceToPlatformLog(
                    "SlockShellOverlay",
                    "shell_observe parsed=${request != null} requestId=${request?.requestId} " +
                        "pageKey=${request?.pageKey} anchor=${request?.anchor} items=${request?.items?.size} " +
                        "rawRequestId=${eventData?.optString(SHELL_OVERLAY_REQUEST_ID)} " +
                        "type=${eventData?.optString(SHELL_OVERLAY_MESSAGE_TYPE)}"
                )
                if (request == null) return@observe
                // Ignore a request superseded by another page, and honour a cancel from the page
                // that owns the menu currently on screen.
                val current = shellOverlayMenu
                if (!raftShellShouldDropOverlayRequest(current?.request?.requestId, request.requestId)) {
                    shellOverlayContent = RaftShellOverlayContentRegistry.take(request.requestId)
                    shellOverlayMenu = RaftShellOpenOverlay(request)
                }
            }
        onDispose { subscription.close() }
    }
    val authSessionEvents =
        remember(eventBus, pager.pagerId) {
            RaftAuthSessionEvents(
                eventBus = eventBus,
                source = "app-shell:${pager.pagerId}"
            )
        }
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
    var cachedRestoreSession by remember { mutableStateOf<StoredAuthSession?>(null) }
    LaunchedEffect(shellAuthStore, authGateState) {
        if (authGateState == SharedAuthGateState.Restoring) {
            cachedRestoreSession =
                runCatchingPreservingCancellation { shellAuthStore.readSession() }
                    .getOrNull()
                    ?.takeIf { it.isColdStartCachedReadyEligible() }
        } else if (authGateState is SharedAuthGateState.AuthRequired) {
            cachedRestoreSession = null
        }
    }
    var consumedInitialAuthRestoreTick by remember { mutableStateOf(0) }
    LaunchedEffect(initialAuthRestoreTick) {
        if (
            initialAuthRestoreTick > 0 &&
            consumedInitialAuthRestoreTick != initialAuthRestoreTick
        ) {
            consumedInitialAuthRestoreTick = initialAuthRestoreTick
            requestAppShellInitialAuthRestore(authGateViewModel::dispatch)
        }
    }
    var authGateMessage by remember { mutableStateOf<AuthGateMessage?>(null) }
    var authSessionResetGeneration by remember { mutableStateOf(0) }
    var appliedAuthSessionResetGeneration by remember { mutableStateOf(0) }
    DisposableEffect(authSessionEvents, authStore) {
        val callbackRef =
            authSessionEvents.addSessionClearedListener { signal ->
                if (authSessionEvents.isLocalSource(signal)) {
                    return@addSessionClearedListener
                }
                raftLogInfo(
                    "SlockSharedAuth",
                    "shared_auth_session auth_session_clear_notify_received source=${signal.source} reason=${signal.reason}",
                    RaftPlatformLogHint.AuthCritical
                )
                appShellCoroutineScope.launch {
                    runCatchingPreservingCancellation {
                        authStore.clearSessionFromSessionClearedNotify(
                            reason = signal.reason,
                            previousSessionFingerprint = signal.previousSessionFingerprint
                        )
                    }.onSuccess { applied ->
                        raftLogInfo(
                            "SlockSharedAuth",
                            "shared_auth_session auth_session_clear_notify_result result=success applied=$applied " +
                                "source=${signal.source} reason=${signal.reason}",
                            RaftPlatformLogHint.AuthCritical
                        )
                    }.onFailure { throwable ->
                        raftLogWarn(
                            "SlockSharedAuth",
                            "shared_auth_session auth_session_clear_notify_result result=failure source=${signal.source} " +
                                "reason=${signal.reason} error=${throwable.message.orEmpty()}",
                            RaftPlatformLogHint.AuthCritical
                        )
                    }
                }
            }
        onDispose {
            authSessionEvents.removeSessionClearedListener(callbackRef)
        }
    }
    LaunchedEffect(sharedStores.authLossFunnel, authStore, authSessionTerminalSource, pager.pagerId) {
        var acceptedTerminalGeneration: Long? = null
        val sessionInvalidationEffects =
            authSessionTerminalSource
                ?.authSessionTerminalEvents
                ?.mapNotNull { event ->
                    (event as? AuthSessionEvent.Cleared)?.let {
                        AppShellOrderedAuthEffect.SessionInvalidated(it.generation)
                    }
                }
                ?: authStore.sessionInvalidations.map {
                    AppShellOrderedAuthEffect.SessionInvalidated(generation = null)
                }
        merge(
            sharedStores.authLossFunnel.resolutions.map { AppShellOrderedAuthEffect.AuthLoss(it) },
            authStore.sessionChanges.map { AppShellOrderedAuthEffect.SessionChanged(it) },
            sessionInvalidationEffects,
        ).collect { effect ->
            when (effect) {
                is AppShellOrderedAuthEffect.AuthLoss -> {
                    val resolution = effect.resolution
                    if (!sharedStores.isActiveAppShellPager(pager.pagerId)) {
                        bridge.logWarn(
                            "app_shell_auth_loss_effect_skip reason=inactive_pager " +
                                "source=${resolution.signal.source} pagerId=${pager.pagerId}"
                        )
                        return@collect
                    }
                    when (val decision = resolution.decision) {
                        is AuthLossDecision.SessionPreserved ->
                            bridge.logWarn(
                                "app_shell_auth_loss_effect decision=session_preserved " +
                                    "reason=${decision.reason} source=${resolution.signal.source}"
                            )
                        is AuthLossDecision.AuthRequired -> {
                            val terminalSnapshot =
                                authSessionTerminalSource?.let { source ->
                                    runCatchingPreservingCancellation {
                                        source.readAuthSessionTerminalSnapshot()
                                    }.getOrNull()
                                }
                            if (authSessionTerminalSource != null && terminalSnapshot == null) {
                                sharedStores.authLossFunnel.discardPendingOpenRequest(resolution)
                                bridge.logWarn(
                                    "app_shell_auth_loss_effect_skip reason=terminal_snapshot_unavailable " +
                                        "source=${resolution.signal.source}"
                                )
                                return@collect
                            }
                            val currentSession =
                                terminalSnapshot?.session
                                    ?: if (authSessionTerminalSource == null) {
                                        runCatchingPreservingCancellation {
                                            shellAuthStore.readSession()
                                        }.getOrNull()
                                    } else {
                                        null
                                    }
                            if (
                                !appShellAuthLossEffectShouldApply(
                                    resolution.signal.capturedSession,
                                    currentSession,
                                )
                            ) {
                                sharedStores.authLossFunnel.discardPendingOpenRequest(resolution)
                                bridge.logWarn(
                                    "app_shell_auth_loss_effect_skip reason=auth_authority_changed " +
                                        "source=${resolution.signal.source}"
                                )
                                return@collect
                            }
                            val authDecision =
                                appShellBootstrapAuthDecision(AppShellBootstrapAuthSignal.AuthRequired)
                            val applyEffect = {
                                authGateMessage =
                                    AuthGateMessage.Static(Res.string.auth_error_session_expired.desc())
                                if (authDecision.resetSessionState) authSessionResetGeneration += 1
                                authGateViewModel.dispatch(authDecision.intent)
                            }
                            var appliedWithoutTerminalSource = false
                            val committedTerminalGeneration =
                                if (authSessionTerminalSource != null && terminalSnapshot != null) {
                                    if (acceptedTerminalGeneration == terminalSnapshot.generation) {
                                        terminalSnapshot.generation
                                    } else {
                                        appShellRunIfAuthSessionTerminalOrAuthorityRevision(
                                            authSessionTerminalSource,
                                            terminalSnapshot.generation,
                                            resolution.signal.capturedSession,
                                        ) {
                                            applyEffect()
                                        }
                                    }
                                } else {
                                    applyEffect()
                                    appliedWithoutTerminalSource = true
                                    null
                                }
                            val applied =
                                appliedWithoutTerminalSource || committedTerminalGeneration != null
                            if (!applied) {
                                sharedStores.authLossFunnel.discardPendingOpenRequest(resolution)
                                bridge.logWarn(
                                    "app_shell_auth_loss_effect_skip reason=terminal_revision_changed " +
                                        "source=${resolution.signal.source}"
                                )
                                return@collect
                            }
                            committedTerminalGeneration?.let { generation ->
                                acceptedTerminalGeneration = generation
                            }
                            sharedStores.authLossFunnel.activatePendingOpenRequest(resolution)
                            bridge.logWarn(
                                "app_shell_auth_loss_effect decision=auth_required " +
                                    "reason=${decision.reason} source=${resolution.signal.source}"
                            )
                        }
                    }
                }
                is AppShellOrderedAuthEffect.SessionChanged -> {
                    val cachedSession =
                        runCatchingPreservingCancellation { shellAuthStore.readSession() }.getOrNull()
                    if (cachedSession == null) {
                        ProcessAttachmentAuthScopes.retireAccount()
                        bridge.attachmentDownloadOwnerChanged(null)
                    }
                    val currentSession =
                        (authGateViewModel.state.value as? SharedAuthGateState.Ready)?.session
                    val identityChanged = appShellSessionIdentityChanged(cachedSession, currentSession)
                    val authDecision =
                        appShellBootstrapAuthDecision(
                            AppShellBootstrapAuthSignal.SessionChanged,
                            identityChanged = identityChanged
                        )
                    bridge.log(
                        "app_shell_session_change_received fingerprint=${effect.fingerprint} " +
                            "hasSession=${cachedSession != null} identityChanged=$identityChanged"
                    )
                    authGateMessage = null
                    if (identityChanged) {
                        bridge.realtimeDisconnect("auth_changed")
                    }
                    if (authDecision.resetSessionState) authSessionResetGeneration += 1
                    authGateViewModel.dispatch(authDecision.intent)
                }
                is AppShellOrderedAuthEffect.SessionInvalidated -> {
                    val authDecision =
                        appShellBootstrapAuthDecision(AppShellBootstrapAuthSignal.SessionInvalidated)
                    val applyEffect = {
                        bridge.realtimeDisconnect("auth_invalid")
                        if (authDecision.resetSessionState) authSessionResetGeneration += 1
                        authGateViewModel.dispatch(authDecision.intent)
                    }
                    val applied =
                        if (effect.generation != null && authSessionTerminalSource != null) {
                            if (acceptedTerminalGeneration == effect.generation) {
                                true
                            } else {
                                appShellRunIfAuthSessionTerminalRevision(
                                    authSessionTerminalSource,
                                    effect.generation,
                                ) {
                                    applyEffect()
                                    acceptedTerminalGeneration = effect.generation
                                }
                            }
                        } else {
                            val currentSession =
                                runCatchingPreservingCancellation {
                                    shellAuthStore.readSession()
                                }.getOrNull()
                            if (currentSession == null) {
                                applyEffect()
                                true
                            } else {
                                false
                            }
                        }
                    if (!applied) {
                        bridge.logWarn(
                            "app_shell_session_invalidation_skip reason=terminal_revision_changed"
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(authStore) {
        authStore.authSessionEvents.collect { event ->
            if (appShellAuthSessionEventRestoreIntent(event) == null) return@collect
            val authDecision =
                appShellBootstrapAuthDecision(AppShellBootstrapAuthSignal.BindingMetadataChanged)
            bridge.log(
                "app_shell_auth_session_event_received type=binding_metadata_changed " +
                    "generation=${event.generation} action=refresh_silently"
            )
            authGateMessage = null
            authGateViewModel.dispatch(authDecision.intent)
        }
    }
    // Preserve the cheap bootstrap-only path for signed-out/unknown sessions. A valid local
    // session is discovered by the effect above; once present, the Home graph is allowed to
    // materialize so its identity-bound durable snapshot can paint before remote auth settles.
    if (authGateState == SharedAuthGateState.Restoring && cachedRestoreSession == null) {
        if (suppressRestoreLanding) {
            AppShellSilentRestoreContent()
        } else {
            AppShellRestoreSplashContent()
        }
        return
    }
    val shellScope = rememberRaftAppShellScope(adaptivePane)
    val appShellNetworkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val linkNavigator = rememberRaftLinkNavigator(pager, bridge)
    val copiedMessage = stringResource(Res.string.common_copied)
    val settingsCapabilities =
        remember(bridge, appShellCoroutineScope, linkNavigator, copiedMessage) {
            BridgeSettingsPlatformCapabilities(
                bridge = bridge,
                copiedMessage = copiedMessage,
                bridgeCallContext = appShellCoroutineScope.kuiklyBridgeCallContext(),
                openUrlHandler = { url -> linkNavigator.open(url) }
            )
        }
    val sharedPreferenceStore = remember { raftSharedPreferenceStore() }
    val notificationPreferenceRepository =
        remember(sharedPreferenceStore) {
            PersistentNotificationPreferenceRepository(
                KeyValueNotificationPreferenceStore(sharedPreferenceStore)
            )
        }
    val memberRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealMemberRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = raftLogger("SlockAppShell", "app_shell_member_repository")
            )
        }
    val homeDataSource =
        remember(
            memberRepository,
            appShellServerMembersStore,
            settingsCapabilities,
            notificationPreferenceRepository,
        ) {
            SharedHomeDataSource(
                sessionStore = shellAuthStore,
                api = NetworkRaftHomeApi(appShellNetworkClient),
                announcementsApi = NetworkRaftAnnouncementsRemindersApi(appShellNetworkClient),
                computersApi = RaftComputersNetworkApi(appShellNetworkClient),
                appUpdateStatusProvider = {
                    when (val result = settingsCapabilities.checkHandsUpdateStatus()) {
                        is DataResult.Content ->
                            DataResult.Content(result.data.withSkippedAppUpdateVersionApplied())
                        is DataResult.Empty -> result
                        is DataResult.Error -> result
                        is DataResult.NoPermission -> result
                    }
                },
                notificationPreferenceRepository = notificationPreferenceRepository,
                memberRepository = memberRepository,
                serverMembersStore = appShellServerMembersStore,
                parseHome = { payload, session ->
                    parseRaftHome(payload, session, raftLogger("SlockHomeParse"))
                },
                diagnosticsLogger = raftLogger("SlockSharedHome", "shared_home")
            )
        }
    val channelRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealChannelRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = raftLogger("SlockChannelRepository", "shared_channel_repository", RaftLogLevel.Warn)
            )
        }
    // Pending joint-channel invites for the Home notification center (web usePendingJointInvites).
    val jointInviteScope = rememberCoroutineScope()
    val jointInviteStore =
        remember(appShellNetworkClient, jointInviteScope) {
            build.raft.app.data.SharedJointInviteStore(
                dataSource =
                    build.raft.app.data.NetworkJointInviteDataSource(
                        networkClient = appShellNetworkClient,
                        logger = raftLogger("SlockJointInvites", "shared_joint_invites", RaftLogLevel.Warn),
                    ),
                scope = jointInviteScope,
            )
        }
    val homeReadReceiptRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealReadReceiptRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = raftLogger("SlockReadReceipt", "shared_home_read_receipt", RaftLogLevel.Warn)
            )
        }
    val conversationRepository =
        remember(shellAuthStore, appShellNetworkClient, selectedServerStore) {
            RealConversationRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                routeServerIdProvider = { selectedServerStore.selectedServerId.value },
                warningLogger = raftLogger("SlockConversationRepository", "shared_conversation_repository", RaftLogLevel.Warn)
            )
        }
    val agentsApiClient =
        remember(appShellNetworkClient) {
            RaftAgentsApiClient(
                transport = RaftAgentsNetworkApi(appShellNetworkClient),
                diagnosticsLogger = raftLogger("SlockAgentsApi", "app_shell_agents_api")
            )
        }
    val computersApiClient =
        remember(appShellNetworkClient) {
            RaftComputersApiClient(RaftComputersNetworkApi(appShellNetworkClient))
        }
    val createAgentFormStore =
        remember(sharedStores, storeScope) { sharedStores.createAgentFormStore(storeScope) }
    val createAgentCoordinator =
        remember(agentsApiClient, computersApiClient) {
            SharedCreateAgentCoordinator(
                agentsApiClient = agentsApiClient,
                computersApiClient = computersApiClient,
                logger = raftLogger("SlockCreateAgent", "app_shell_thread_create_agent")
            )
        }
    val savedRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealSavedRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = raftLogger("SlockSavedRepository", "shared_saved_repository", RaftLogLevel.Warn)
            )
        }
    val searchRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealSearchRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = raftLogger("SlockSearchRepository", "shared_search_repository", RaftLogLevel.Warn)
            )
        }
    val tasksDataSource =
        remember {
            SharedTasksDataSource(
                api = NetworkRaftTasksApi(appShellNetworkClient),
                diagnosticsLogger = raftLogger("SlockTasks", "shared_tasks")
            )
        }
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
    val chatLayoutPreferenceEvents =
        remember(eventBus) { RaftChatLayoutPreferenceEvents(eventBus) }
    val chatLayoutPreferenceRepository =
        remember(sharedPreferenceStore, chatLayoutPreferenceEvents) {
            raftChatLayoutPreferenceRepository(
                delegate =
                    PersistentChatLayoutPreferenceRepository(
                        KeyValueChatLayoutPreferenceStore(sharedPreferenceStore)
                    ),
                events = chatLayoutPreferenceEvents
            )
        }
    val appUpdateEvents = remember(eventBus) { RaftAppUpdateEvents(eventBus) }
    val realtimeEvents = remember(eventBus) { RaftRealtimeEvents(eventBus) }
    val hostNetworkEvents = remember(eventBus) { RaftHostNetworkEvents(eventBus) }
    val homeActivityRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealInboxRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = raftLogger("SlockHomeActivity", "shared_home_activity"),
                warningLogger = raftLogger("SlockHomeActivity", "shared_home_activity_warning", RaftLogLevel.Warn)
            )
        }
    val homeSidebarOrderRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealSettingsRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = raftLogger("SlockHomeSidebarOrder", "shared_home_sidebar_order"),
                warningLogger = raftLogger("SlockHomeSidebarOrder", "shared_home_sidebar_order_warning", RaftLogLevel.Warn)
            )
        }
    val homeStore =
        remember(
            sharedStores,
            storeScope,
            homeDataSource,
            homeSidebarOrderRepository,
            homeReadReceiptRepository,
            channelRepository,
            appShellCoroutineScope
        ) {
            sharedStores.getOrCreateHomeStore(
                scope = storeScope,
                dataSource = homeDataSource,
                diagnosticsLogger = raftLogger("SlockSharedHome", "shared_home"),
                loadSidebarOrder = { serverId -> homeSidebarOrderRepository.getServerSidebarOrder(serverId) },
                updateSidebarOrder = { request -> homeSidebarOrderRepository.updateServerSidebarOrder(request) },
                loadServerOrder = { homeSidebarOrderRepository.getServerOrder() },
                updateServerOrder = { request -> homeSidebarOrderRepository.updateServerOrder(request) },
                markChannelReadAll = { channelId -> homeReadReceiptRepository.markChannelReadAll(channelId) },
                markChannelUnread = { channelId -> homeReadReceiptRepository.markChannelUnread(channelId) },
                markConversationReadAll = { channelId -> homeReadReceiptRepository.markConversationReadAll(channelId) },
                archiveChannel = { channelId -> channelRepository.archiveChannel(channelId) },
                setChannelActivityMuted = { channelId, muted -> channelRepository.setChannelActivityMuted(channelId, muted) },
                pinUpdateFailureCallback = { message -> bridge.toast(message) },
            )
        }
    val dmPreviewPrefetchCoordinator =
        remember(conversationRepository, homeStore) {
            SharedDirectMessagePreviewPrefetchCoordinator(
                conversationRepository = conversationRepository,
                dispatchHome = { intent -> homeStore.dispatch(intent) },
                diagnostics = raftLogger("SlockDmPreviewPrefetch", "dm_preview_prefetch", RaftLogLevel.Debug),
            )
        }
    val homeActivityReplyThreadPrewarmDataSource =
        remember(
            shellAuthStore,
            appShellNetworkClient,
            conversationRepository,
            sharedStores.threadMessageStore,
            sharedStores.threadPayloadStore,
            sharedStores.threadPendingSendJournal,
            sharedStores.apiEnvironmentStore
        ) {
            SharedThreadDataSource(
                networkApi = NetworkRaftThreadApi(appShellNetworkClient),
                sessionStore = shellAuthStore,
                pendingSendJournal = sharedStores.threadPendingSendJournal,
                selectedServerStore = selectedServerStore,
                conversationRepository = conversationRepository,
                messageStore = sharedStores.threadMessageStore,
                cacheEnvironmentIdProvider = {
                    sharedStores.apiEnvironmentStore.current().apiBaseUrl
                },
                apiEnvironmentIdProvider = {
                    sharedStores.apiEnvironmentStore.current().apiBaseUrl
                },
                replyCacheIdentityAuthority = {
                    sharedStores.captureThreadReplyCacheIdentity(storeScope)
                },
                replyCacheIdentityTransitionWaiter = { transitionAnchor ->
                    sharedStores.awaitThreadReplyCacheIdentityTransition(storeScope, transitionAnchor)
                },
                payloadStore = sharedStores.threadPayloadStore,
                durableLastKnownReader = sharedStores.threadDurableLastKnownReader,
                enteredScopeSnapshotLoader = sharedStores.threadEnteredScopeSnapshotLoader,
                durablePageExtender = sharedStores.threadDurablePageExtender,
                readReceiptCoordinator = sharedStores.processReadReceiptCoordinator(),
                readReceiptOwnerFactory = sharedStores::readReceiptOwner,
                threadPageSizeProvider = { serverId ->
                    sharedStores.serverFeatureFlags.snapshotFor(serverId).mobileThreadPageSize()
                },
                diagnosticsLogger = raftLogger("SlockHomeActivity", "shared_home_activity_thread_prewarm")
            )
        }
    val homeActivityStore =
        remember(
            storeScope,
            shellAuthStore,
            homeActivityRepository,
            homeReadReceiptRepository,
            conversationRepository,
            memberRepository,
            appShellServerMembersStore,
            channelRepository,
            homeStore,
            homeActivityReplyThreadPrewarmDataSource
        ) {
            sharedStores.getOrCreateHomeActivityStore(
                scope = storeScope,
                inboxRepository = homeActivityRepository,
                readReceiptRepository = homeReadReceiptRepository,
                conversationRepository = conversationRepository,
                memberRepository = memberRepository,
                channelRepository = channelRepository,
                prewarmReplyThread = homeActivityReplyThreadPrewarmDataSource::prewarmReplyThread,
                replyThreadContentCached = homeActivityReplyThreadPrewarmDataSource::hasCurrentReplyThreadContent,
                onUnreadCountsChanged = { homeStore.dispatch(SharedHomeIntent.ForegroundRefresh) },
                // task #436: shared store — both call sites must use one identity.
                diagnosticsLogger = sharedActivityStoreDiagnosticsLogger()
            )
        }
    val appShellComputersStore =
        remember(sharedStores, storeScope) { sharedStores.computersStore(storeScope) }
    val settingsRepository =
        remember(sharedStores, shellAuthStore, appShellNetworkClient) {
            RealSettingsRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = raftLogger("SlockSettingsRepository", "shared_settings_repository"),
                warningLogger = raftLogger("SlockSettingsRepository", "shared_settings_repository_warning", RaftLogLevel.Warn),
                channelRepository = channelRepository,
                onAppearanceSnapshot = { ownerId, snapshot ->
                    messageBodyFontSizeOwnerKey(
                        sharedStores.apiEnvironmentStore.current().apiBaseUrl,
                        ownerId,
                    )?.let { ownerKey ->
                        sharedStores.messageBodyFontSizeStore.acceptAuthoritative(ownerKey, snapshot)
                    }
                },
            )
        }
    var myMessageAlignment by remember { mutableStateOf(MyMessageAlignmentPreference.Default) }
    var compactConsecutiveMessages by remember { mutableStateOf(false) }
    var foregroundMessageBannersEnabled by remember { mutableStateOf(false) }
    var activityTaskStatusBadgesEnabled by remember { mutableStateOf(DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED) }
    var serverDrawerEnabled by remember { mutableStateOf(DEFAULT_SERVER_DRAWER_ENABLED) }
    var homeServerAvatarEnabled by remember { mutableStateOf(DEFAULT_HOME_SERVER_AVATAR_ENABLED) }
    var conversationForwardGestureEnabled by remember { mutableStateOf(false) }
    var serverDrawerProgrammaticCloseFence by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var createChannelDialogOpen by remember { mutableStateOf(false) }
    var createJointChannelDialogOpen by remember { mutableStateOf(false) }
    var createChannelSubmitting by remember { mutableStateOf(false) }
    // task #226: global announcement modal. The API client already exists (the
    // dismiss is server-persisted per user); the shell fetches the latest active
    // announcement on session-ready and refetches on the announcement:new socket
    // event (RealtimeEventMapper maps it to RealtimeRefreshScope.Announcements,
    // which the home store deliberately ignores — the modal is the sole consumer).
    val announcementsClient =
        remember(appShellNetworkClient) {
            RaftAnnouncementsRemindersApiClient(NetworkRaftAnnouncementsRemindersApi(appShellNetworkClient))
        }
    var createChannelError by remember { mutableStateOf<String?>(null) }
    var createChannelMembersLoading by remember { mutableStateOf(false) }
    var createChannelMembers by remember { mutableStateOf<List<MemberProfile>>(emptyList()) }
    val hostThreadTimeCapabilities = remember(pager.pageData.params.toString()) {
        pager.pageData.params.threadTimeCapabilities()
    }
    val hostThreadTimeContextProvider =
        rememberLiveRaftTimeContext(
            capabilities = hostThreadTimeCapabilities,
            pageVisibleTick = pageVisibleTick
        )
    val currentHostThreadTimeContextProvider = rememberUpdatedState(hostThreadTimeContextProvider)
    val notificationWindowNavigationIntent =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.appShellWindowNavigationIntent(
                fallbackServerId =
                    pager.pageData.params
                        .raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
                        .initialServerId
            )
        }
    val desktopShortcutWindowNavigationIntent =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.appShellDesktopShortcutNavigationIntent(
                fallbackServerId =
                    pager.pageData.params
                        .raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
                        .initialServerId
            )
        }
    var systemNotificationNavigationTick by remember { mutableStateOf(0) }
    var desktopShortcutNavigationTick by remember { mutableStateOf(0) }
    // #proj-mobile #25/#26: external message links wait in the process inbox (through sign-in)
    // and, once resolved for this account, enter the same window navigation queue as a
    // notification tap.
    var messageLinkNavigationTick by remember { mutableStateOf(0) }
    val pendingMessageLink by RaftMessageLinkInbox.Process.pending.collectAsState()
    val messageLinkSwitchedServerTemplate =
        stringResource(Res.string.message_link_switched_server, MessageLinkServerNamePlaceholder)
    DisposableEffect(eventBus, windowNavigationCoordinator, bridge) {
        val subscription =
            eventBus.observe(build.raft.app.data.RaftDesktopShortcutContract.OpenEventName) { eventData ->
                // task #483: a shell-targeted event applies only to that shell;
                // absent/blank slockShellInstanceId broadcasts to every shell.
                val targetShellId =
                    eventData?.optString(RAFT_SHELL_INSTANCE_ID)?.trim().orEmpty()
                if (targetShellId.isNotEmpty() && targetShellId != storeScope.shellInstanceId) {
                    return@observe
                }
                val intent = eventData?.toKuiklyJson().appShellDesktopShortcutEventNavigationIntent()
                if (intent == null) {
                    bridge.logWarn("window_navigation_drop source=app_shortcut reason=invalid_identity")
                    return@observe
                }
                val enqueued = windowNavigationCoordinator.enqueue(intent)
                bridge.log(
                    "window_navigation_received source=app_shortcut enqueued=$enqueued " +
                        "targetServerId=${intent.serverId}"
                )
                desktopShortcutNavigationTick += 1
            }
        eventBus.post(
            eventName = build.raft.app.data.RaftDesktopShortcutContract.OpenReadyEventName,
            eventData = JSONObject(),
            source = "app_shell_desktop_shortcut_navigation_ready"
        )
        onDispose {
            eventBus.post(
                eventName = build.raft.app.data.RaftDesktopShortcutContract.OpenNotReadyEventName,
                eventData = JSONObject(),
                source = "app_shell_desktop_shortcut_navigation_not_ready"
            )
            subscription.close()
        }
    }
    DisposableEffect(eventBus, windowNavigationCoordinator, bridge) {
        val subscription =
            eventBus.observe(RaftSystemNotificationContract.OpenEventName) { eventData ->
                // task #483: a shell-targeted event applies only to that shell;
                // absent/blank slockShellInstanceId broadcasts to every shell.
                val targetShellId =
                    eventData?.optString(RAFT_SHELL_INSTANCE_ID)?.trim().orEmpty()
                if (targetShellId.isNotEmpty() && targetShellId != storeScope.shellInstanceId) {
                    return@observe
                }
                val intent = eventData?.toKuiklyJson().appShellSystemNotificationNavigationIntent()
                if (intent == null) {
                    bridge.logWarn("window_navigation_drop source=system_notification reason=invalid_identity")
                    return@observe
                }
                val enqueued = windowNavigationCoordinator.enqueue(intent)
                bridge.log(
                    "window_navigation_received source=system_notification enqueued=$enqueued " +
                        "targetServerId=${intent.serverId}"
                )
                systemNotificationNavigationTick += 1
            }
        eventBus.post(
            eventName = RaftSystemNotificationContract.OpenReadyEventName,
            eventData = JSONObject(),
            source = "app_shell_notification_navigation_ready"
        )
        onDispose {
            eventBus.post(
                eventName = RaftSystemNotificationContract.OpenNotReadyEventName,
                eventData = JSONObject(),
                source = "app_shell_notification_navigation_not_ready"
            )
            subscription.close()
        }
    }
    val homeSearchSavedViewModel: SharedHomeSearchSavedViewModel =
        viewModel(key = "shared-home-search-saved") {
            SharedHomeSearchSavedViewModel(
                authStore = shellAuthStore,
                savedRepository = savedRepository,
                searchRepository = searchRepository,
                memberRepository = memberRepository,
                serverMembersStore = appShellServerMembersStore,
                savedStateCache = sharedStores.homeSavedStateCache,
                readReceiptCoordinator = sharedStores.processReadReceiptCoordinator(),
                readReceiptOwnerFactory = sharedStores::readReceiptOwner,
                timeRangeBoundsProvider = { range -> currentHostThreadTimeContextProvider.value.current.toSearchIsoBounds(range) },
                diagnosticsLogger = raftLogger("SlockHomeSearchSaved", "shared_home_search_saved")
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
                dispatchHomeVisibleRefresh = homeStore::dispatchVisibleForegroundRefresh,
                // Live realtime applies run under the realtime authority registry lock: apply the
                // patch and return. A Home/Activity load the event starts (or one already in flight)
                // keeps running in its store scope; waiting for it here held every later realtime
                // event behind Home bootstraps (feedback bc6e392e: up to 17 s).
                acceptHome = homeStore::accept,
                dispatchActivity = homeActivityStore::dispatch,
                acceptActivityRealtime = homeActivityStore::applyLiveRealtime,
                dispatchSearchSaved = homeSearchSavedViewModel::dispatch,
                refreshActivitySummary = { serverId, reason ->
                    serverId
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { sharedStores.activityUnreadSummaryStore.refresh(ServerId(it), reason) }
                },
                resetActivity = homeActivityStore::reset,
                diagnosticsLogger = raftLogger("SlockHomeShell", "shared_home_shell"),
                scope = appShellCoroutineScope
            )
        }
    DisposableEffect(homeShellStore) {
        onDispose { homeShellStore.clear() }
    }
    LaunchedEffect(homeShellStore, selectedTab) {
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedTab(selectedTab))
    }
    val homeShellRenderState =
        remember(homeShellStore, serverDrawerEnabled) {
            if (serverDrawerEnabled) {
                homeShellStore.state
                    .map { state -> state.withoutServerDrawerRuntimeForAppShellRender() }
                    .distinctUntilChanged()
            } else {
                homeShellStore.state
            }
        }
    val initialHomeShellRenderState =
        remember(homeShellStore, serverDrawerEnabled) {
            if (serverDrawerEnabled) {
                homeShellStore.state.value.withoutServerDrawerRuntimeForAppShellRender()
            } else {
                homeShellStore.state.value
            }
        }
    val homeShellState by homeShellRenderState.collectAsState(initialHomeShellRenderState)
    val homeState = homeShellState.home
    val homeActivityState = homeShellState.activity
    val homeSearchSavedState = homeShellState.searchSaved
    val selectedHomeDestination = homeShellState.destination
    val selectedHomeConversationId = homeShellState.selectedConversationId
    var tasksFirstRenderLoggedKey by remember { mutableStateOf<String?>(null) }
    var consumedDiagnosticsDeepLinkId by remember { mutableStateOf<String?>(null) }
    val readyGate = authGateState as? SharedAuthGateState.Ready
    val readySession = readyGate?.session
    val homePreviewSession =
        readySession
            ?: cachedRestoreSession.takeIf { authGateState == SharedAuthGateState.Restoring }
    val readyAuthPhase =
        readyGate?.phase
            ?: cachedRestoreSession
                ?.takeIf { authGateState == SharedAuthGateState.Restoring }
                ?.let { SharedAuthReadyPhase.CachedRestorePending }
    val readyServerId = selectedServerId?.trim()?.takeIf(String::isNotBlank) ?: readySession?.currentServerId
    val tasksOwner = SharedTasksRouteOwner(
        environment = sharedStores.apiEnvironmentStore.current().apiBaseUrl,
        accountId = readySession?.user?.id,
        serverId = readyServerId
    )
    val tasksBinding = rememberSharedTasksRoute(
        owner = tasksOwner,
        authStore = shellAuthStore,
        dataSource = tasksDataSource,
        sharedTasksDataStore = sharedStores.tasksDataStore
    )
    val tasksRoute = tasksBinding.route
    val tasksViewModel = tasksRoute.viewModel
    val tasksState = tasksBinding.state
    // Slack-aligned navigation was retired; persisted/flagged Slack tabs are ignored. The live
    // child-page set is Home, Activity, Tasks, More plus the More pages Members and Settings.
    val slackNavTabKeys = AppShellChildPageTabKeys
    val slackNavMode = false
    // Clamp a deep link / stale selection onto the active ordered key set: with the gate off
    // the Slack keys are not child pages, and with the gate on the legacy hidden keys (tasks/
    // members) are not first-class tabs anymore. Home is the safe anchor in both cases.
    // Mirrors selectAppShellTab but avoids the local-function ordering constraint.
    LaunchedEffect(
        slackNavTabKeys,
        selectedTab,
        desktopModeOnLargeScreen,
        shellScope.adaptivePane,
        railInlineSecondaryDestination,
    ) {
        // task #13: while the wide rail is active, Activity is a first-class rail
        // destination even under the legacy tab order — the clamp must not evict it.
        // navRailActive is declared later in this composable, so inline the same
        // predicate here (rail = flag && LeftRail pref && DualPane).
        val railActive =
            raftAdaptiveNavRailActive(
                desktopModeEnabled = desktopModeOnLargeScreen,
                paneState = shellScope.adaptivePane
            )
        if (
            retainedAppShellSelectedTab(selectedTab) == "activity" &&
            (railActive || railInlineSecondaryDestination != null)
        ) {
            railActivityReached = true
        }
        val allowedKeys =
            appShellAllowedTabKeys(
                tabKeys = slackNavTabKeys,
                railActive = railActive,
                railInlineSecondaryDestination = railInlineSecondaryDestination,
                activityReachedThisSession = railActivityReached,
            )
        // While an inline secondary destination (Search) covers the middle pane, the
        // underlying rail tab is the host the cover returns to — clamping it now would
        // strand the exit path (e.g. Activity + Search -> Home after close).
        if (railInlineSecondaryDestination != null) return@LaunchedEffect
        if (retainedAppShellSelectedTab(selectedTab) !in allowedKeys) {
            bridge.log(
                "app_shell_slack_nav_tab_clamp selectedTab=$selectedTab keys=${slackNavTabKeys.joinToString(",")}"
            )
            childTabSwitchEpoch =
                appShellNextChildTabSwitchEpoch(selectedTab, "home", childTabSwitchEpoch)
            selectedTab = "home"
            retainedAppShellTabs = retainedAppShellTabs + "home"
        }
    }

    // task #45: rail-only transient surfaces must not leak into the single-column
    // mobile shell — an open inline rail destination (Search) or a visible More
    // menu is display state, not user data. This is edge-triggered on the OFF
    // transition only: while OFF the user may still open the More menu or push a
    // destination, and those must not be wiped by a persistent predicate.
    // Destinations, selections and drafts are untouched (the workspace stays
    // parked for the OFF->ON round trip).
    var desktopModeCleanupArmed by remember { mutableStateOf(desktopModeOnLargeScreen) }
    LaunchedEffect(desktopModeOnLargeScreen) {
        val (shouldCleanup, nextArmed) =
            desktopModeOffCleanupDecision(desktopModeOnLargeScreen, desktopModeCleanupArmed)
        desktopModeCleanupArmed = nextArmed
        if (shouldCleanup) {
            bridge.log(
                "desktop_mode_off_cleanup edge inline=${railInlineSecondaryDestination.orEmpty()} " +
                    "selectedTab=$selectedTab"
            )
            if (railInlineSecondaryDestination != null) {
                railInlineSecondaryDestination = null
            }
        }
    }
    val homePreviewServerId =
        selectedServerId?.trim()?.takeIf(String::isNotBlank) ?: homePreviewSession?.currentServerId
    val createChannelAgentStatusResolver =
        remember(appShellServerMembersStore, readyServerId) {
            raftScopedAgentStatusResolver(appShellServerMembersStore, readyServerId)
        }
    val appShellEnvironment = sharedStores.apiEnvironmentStore.current()
    val setupAdmissionHost =
        if (readySession != null && !readyServerId.isNullOrBlank()) {
            rememberServerSetupAdmissionHost(
                session = requireNotNull(readySession),
                serverId = readyServerId,
                environmentId =
                    appShellEnvironment.let { environment ->
                        raftNetworkKmmRecoveryEnvironmentId(
                            environmentKey = environment.key,
                            apiBaseUrl = environment.apiBaseUrl,
                        )
                    },
                webBaseUrl = appShellEnvironment.webUrl,
                networkClient = appShellNetworkClient,
                shellAuthStore = shellAuthStore,
                authEvents = sharedStores.authStore,
                featureFlags = sharedStores.serverFeatureFlags,
                authDataSource = authDataSource,
                bridge = bridge,
                openExternalUrl = linkNavigator::open,
                refreshSignal = pageVisibleTick,
            )
        } else {
            null
        }
    val setupAdmissionAllowsHome = setupAdmissionHost?.allowsHome == true
    val setupAdmissionFeatureDecision =
        setupAdmissionHost?.featureDecision
            ?: homePreviewServerId
                ?.takeIf { it.isNotBlank() }
                ?.let { serverId ->
                    serverSetupFeatureDecision(
                        sharedStores.serverFeatureFlags.snapshotFor(ServerId(serverId))
                    )
                }
            ?: ServerSetupFeatureDecision.Checking
    val authoritativeHomeStartAllowed =
        appShellAuthoritativeHomeStartAllowed(
            authGateState = authGateState,
            setupAdmissionAllowsHome = setupAdmissionAllowsHome
        )
    LaunchedEffect(homeStore, homePreviewSession?.user?.id, homePreviewServerId, readyAuthPhase) {
        val serverId = homePreviewServerId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (
            readyAuthPhase == SharedAuthReadyPhase.CachedRestorePending ||
            readyAuthPhase == SharedAuthReadyPhase.CachedRestoreCompleted
        ) {
            val hydrated = homeStore.hydrateCachedHome(ServerId(serverId))
            bridge.log(
                "app_shell_cached_home_hydrate serverId=$serverId hydrated=$hydrated " +
                    "authPhase=${readyAuthPhase.name}"
            )
        }
    }
    val cachedHomePreview =
        appShellCanRenderCachedHomePreview(
            readySession = homePreviewSession,
            readyPhase = readyAuthPhase,
            featureDecision = setupAdmissionFeatureDecision,
            setupAdmissionAllowsHome = setupAdmissionAllowsHome,
            setupAdmissionState = setupAdmissionHost?.state,
            readyServerId = homePreviewServerId,
            selectedTab = selectedTab,
            selectedHomeDestination = selectedHomeDestination,
            pageVisible = pageVisible,
            state = homeState
        )
    // artin 2026-10-10 (Hands 79db9fe6): once the identity-bound cached Home can be shown, the
    // user may leave Home (open a channel, switch tab) while the session and server setup
    // admission are still being confirmed in the background; the admission screen only takes over
    // again if admission ends Blocked/Failed or the cache no longer matches the account.
    val cachedRestoreUsable =
        appShellCanRenderCachedHomePreview(
            readySession = homePreviewSession,
            readyPhase = readyAuthPhase,
            featureDecision = setupAdmissionFeatureDecision,
            setupAdmissionAllowsHome = setupAdmissionAllowsHome,
            setupAdmissionState = setupAdmissionHost?.state,
            readyServerId = homePreviewServerId,
            state = homeState
        )
    val adaptiveRoomLeaseRegistry =
        remember(sharedStores, storeScope) { sharedStores.realtimeRoomLeaseRegistry(storeScope) }
    fun rejectPendingAdaptiveDetailOpens(
        dropped: Map<String, build.raft.app.data.PendingAdaptiveDetailOpen>,
        reason: String,
    ) {
        val anchorStore = sharedStores.adaptiveScrollAnchorStore(storeScope)
        dropped.forEach { (requestId, pending) ->
            anchorStore.discard(
                shellInstanceId = storeScope.shellInstanceId,
                identity = pending.identity,
                requestId = requestId,
            )
            sharedStores.eventBus.post(
                eventName = RAFT_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
                eventData =
                    RaftAdaptiveDetailOpenAck(
                        shellInstanceId = storeScope.shellInstanceId,
                        requestId = requestId,
                        accepted = false,
                        reason = reason,
                    ).toJson(),
                crossProcess = true,
                source = "adaptive_detail_open_dropped",
            )
        }
    }

    fun releaseAdaptiveHandoffLease(expectedGeneration: Long? = null) {
        val owner = adaptiveWorkspaceStore.snapshot().handoffRoomLeaseOwner ?: return
        if (expectedGeneration != null && owner.generation != expectedGeneration) return
        realtimeEvents.applyRoomTransitions(adaptiveRoomLeaseRegistry.release(owner))
        adaptiveWorkspaceStore.update { current ->
            if (current.handoffRoomLeaseOwner == owner) {
                current.copy(handoffRoomLeaseOwner = null)
            } else {
                current
            }
        }
    }
    fun installAdaptiveHandoffLease(
        selection: RaftAdaptiveDetailSelection,
        launchPayload: WireThreadLaunchPayload,
    ) {
        val nextOwner =
            RealtimeRoomLeaseOwner(
                shellInstanceId = storeScope.shellInstanceId,
                serverId = selection.identity.serverId,
                consumerId = "adaptive-handoff",
                generation = selection.generation,
            )
        val initialPayload = launchPayload.toInitialPayloadOrNull(launchPayload.instanceId)
        val roomIds = initialPayload?.activeRealtimeRoomIds().orEmpty()
        // Task #14c: a reply thread's own room stays joined after the detail closes (its parent's does not).
        initialPayload?.replyThreadRealtimeRoomIds()?.forEach { roomId ->
            adaptiveRoomLeaseRegistry.keepJoinedUntilClear(nextOwner.serverId, roomId)
        }
        realtimeEvents.applyRoomTransitions(adaptiveRoomLeaseRegistry.acquire(nextOwner, roomIds))
        val previousOwner = adaptiveWorkspaceStore.snapshot().handoffRoomLeaseOwner
        adaptiveWorkspaceStore.update { current ->
            current.copy(handoffRoomLeaseOwner = nextOwner)
        }
        if (previousOwner != null && previousOwner != nextOwner) {
            realtimeEvents.applyRoomTransitions(adaptiveRoomLeaseRegistry.release(previousOwner))
        }
    }
    // task #36: when the window (or desktop mode) can no longer hold the side column, fold the
    // side thread into the main detail with the channel in Back history.
    // task #37: the user's list column width (saved per device) and whether its divider is being
    // dragged. A drag that squeezes the detail below the column threshold folds the side thread
    // only when the finger lifts, so dragging across the threshold and back loses nothing.
    val listPanePreferenceStore = remember { KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore()) }
    val adaptiveListPaneWidths = rememberRaftListPaneWidths(listPanePreferenceStore)
    var adaptiveListPaneResizing by remember { mutableStateOf(false) }
    // A drag only defers the fold while it can still be in progress: desktop mode on, dual pane.
    // (The handle also reports the end when it goes inactive or leaves composition.)
    val listPaneResizeDefersFold =
        adaptiveListPaneResizing && desktopModeOnLargeScreen &&
            adaptivePane.layout == RaftAdaptivePaneLayout.DualPane
    val sideThreadNeedsFold =
        !listPaneResizeDefersFold &&
            adaptiveWorkspaceSession.visibleSideThread() != null &&
            !adaptiveWorkspaceSession.sideThreadColumnAvailable(desktopModeOnLargeScreen)
    LaunchedEffect(sideThreadNeedsFold) {
        if (!sideThreadNeedsFold) return@LaunchedEffect
        adaptiveWorkspaceStore.update { current ->
            if (current.visibleSideThread() != null && !current.sideThreadColumnAvailable(desktopModeOnLargeScreen)) {
                bridge.log("adaptive_side_thread_fold width=${current.detailPaneWidthDp}")
                // task #180: drop Focus together with the column. The fold turns the thread into the
                // main detail, so a leftover flag would mean "maximised with no column to maximise".
                current.foldSideThreadIntoDetail().copy(sideThreadFocused = false)
            } else current
        }
    }
    // task #180: also normalise when Focus is somehow set without an available column (e.g. the
    // thread was closed while focused), so the next thread does not open already-maximised.
    val sideThreadFocusStale =
        adaptiveWorkspaceSession.sideThreadFocusActive() &&
            !adaptiveWorkspaceSession.sideThreadFocusAvailable(desktopModeOnLargeScreen)
    LaunchedEffect(sideThreadFocusStale) {
        if (!sideThreadFocusStale) return@LaunchedEffect
        adaptiveWorkspaceStore.update { it.normalizeSideThreadFocus(desktopModeOnLargeScreen) }
    }
    val tasksDetailOverlayKeepsOwnership =
        appShellTasksDetailOverlayStaysActive(
            desktopModeOnLargeScreen = desktopModeOnLargeScreen,
            dualPane = adaptivePane.layout == RaftAdaptivePaneLayout.DualPane,
            selectedTab = selectedTab,
            selectionPresent = adaptiveWorkspaceState?.selection != null,
        )
    LaunchedEffect(tasksDetailOverlayKeepsOwnership) {
        if (!tasksDetailOverlayKeepsOwnership && tasksDetailOverlayActive) {
            bridge.log("tasks_detail_overlay_release reason=surface_or_selection_changed")
            tasksDetailOverlayActive = false
        }
    }
    val tasksDetailOverlayOpen =
        appShellTasksDetailOverlayOpen(
            desktopModeOnLargeScreen = desktopModeOnLargeScreen,
            dualPane = adaptivePane.layout == RaftAdaptivePaneLayout.DualPane,
            selectedTab = selectedTab,
            selectionPresent = adaptiveWorkspaceState?.selection != null,
            overlayActive = tasksDetailOverlayActive,
        )
    LaunchedEffect(
        desktopModeOnLargeScreen,
        storeScope.shellInstanceId,
        readyServerId,
        adaptivePane,
        pageVisible,
        selectedTab,
        selectedHomeDestination,
        authoritativeHomeStartAllowed,
        railInlineSecondaryDestination,
        tasksDetailOverlayOpen,
    ) {
        if (!authoritativeHomeStartAllowed) {
            releaseAdaptiveHandoffLease()
            adaptiveDefaultLandingServerId = null
            return@LaunchedEffect
        }
        val serverId = readyServerId?.trim()?.takeIf(String::isNotBlank)
        if (!desktopModeOnLargeScreen && serverId != null) {
            // task #45: toggling the user setting OFF must not destroy the workspace —
            // destination, selection and drafts must survive a live OFF->ON round trip.
            // The workspace keeps its last state; rendering gates on the flag, and the
            // next ON tick re-drives WindowChanged/ParentVisibilityChanged from current
            // values. (Server loss below still clears it.)
            return@LaunchedEffect
        }
        if (serverId == null) {
            val droppedOpens = adaptiveWorkspaceStore.snapshot().pendingDetailOpens
            adaptiveWorkspaceStore.update { current ->
                current.copy(
                    workspace =
                        current.workspace?.let { state ->
                            reduceRaftAdaptiveWorkspace(state, RaftAdaptiveWorkspaceIntent.TerminalReset)
                        },
                    launchPayload = null,
                    pendingDetailOpens = emptyMap(),
                )
            }
            rejectPendingAdaptiveDetailOpens(droppedOpens, "workspace_reset")
            releaseAdaptiveHandoffLease()
            adaptiveDefaultLandingServerId = null
            return@LaunchedEffect
        }
        // Reduce window/visibility on the store's CURRENT workspace inside the atomic
        // update — seeding from the compose snapshot (`adaptiveWorkspaceState`) here is a
        // lost-update race: a concurrently-written selection (e.g. a rail Search/Activity
        // open landing between snapshot read and update) would be silently overwritten.
        val parentVisible =
            appShellSharedDetailSurfaceVisible(
                pageVisible = pageVisible,
                selectedTab = selectedTab,
                selectedHomeDestination = selectedHomeDestination,
                railInlineSecondaryDestination = railInlineSecondaryDestination,
                tasksDetailOverlayOpen = tasksDetailOverlayOpen,
            )
        // Capture the dropped pending opens BEFORE the update so the reject/nack path
        // can still see them (they are cleared inside the update for the authority change).
        val droppedOpens = adaptiveWorkspaceStore.snapshot().pendingDetailOpens
        var authorityChanged = false
        adaptiveWorkspaceStore.update { current ->
            var next =
                current.workspace
                    ?: RaftAdaptiveWorkspaceState(
                        shellInstanceId = storeScope.shellInstanceId,
                        serverId = serverId,
                    )
            val changed = next.serverId != serverId
            if (changed) {
                next =
                    reduceRaftAdaptiveWorkspace(
                        next,
                        RaftAdaptiveWorkspaceIntent.AuthorityChanged(serverId),
                    )
                authorityChanged = true
            }
            next =
                reduceRaftAdaptiveWorkspace(
                    next,
                    RaftAdaptiveWorkspaceIntent.WindowChanged(adaptivePane),
                )
            next =
                reduceRaftAdaptiveWorkspace(
                    next,
                    RaftAdaptiveWorkspaceIntent.ParentVisibilityChanged(parentVisible),
                )
            current.copy(
                workspace = next,
                launchPayload = if (changed) null else current.launchPayload,
                pendingDetailOpens =
                    if (changed) emptyMap() else current.pendingDetailOpens,
            )
        }
        if (authorityChanged) {
            rejectPendingAdaptiveDetailOpens(droppedOpens, "authority_changed")
            releaseAdaptiveHandoffLease()
            adaptiveDefaultLandingServerId = null
        }
    }
    fun postShellAdaptiveTrace(
        hop: String,
        identityKey: String,
        generation: Long,
        requestId: String? = null,
        extra: JSONObject.() -> Unit = {},
    ) {
        bridge.log(
            formatAdaptiveTraceLine(
                side = "shell",
                hop = hop,
                identityKey = identityKey,
                generation = generation,
                requestId = requestId,
            ),
        )
        sharedStores.eventBus.postAdaptiveTrace(
            side = "shell",
            hop = hop,
            identityKey = identityKey,
            generation = generation,
            requestId = requestId,
            extra = extra,
        )
    }
    DisposableEffect(sharedStores, storeScope.shellInstanceId) {
        val openSubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_DETAIL_OPEN_EVENT,
                crossProcess = true,
            ) { eventData ->
                val request = eventData?.toRaftAdaptiveDetailOpenRequestOrNull()
                    ?: return@observe
                if (request.shellInstanceId != storeScope.shellInstanceId) return@observe
                postShellAdaptiveTrace(
                    hop = "open_received",
                    identityKey = request.identity.key,
                    generation = 0L,
                    requestId = request.requestId,
                )
                val session = adaptiveWorkspaceStore.snapshot()
                val (nextSession, decision) =
                    evaluateRaftAdaptiveDetailOpen(
                        request = request,
                        workspaceEnabled = desktopModeOnLargeScreen,
                        session = session,
                        // Hard off until the native route-token-verified commit action lands
                        // (task #18, Jony follow-up); keeps the merged protocol inert.
                        nativeGuardedCommitAvailable = false,
                    )
                if (nextSession != session) {
                    adaptiveWorkspaceStore.update { nextSession }
                }
                when (decision) {
                    is RaftAdaptiveDetailOpenDecision.Rejected -> {
                        postShellAdaptiveTrace(
                            hop = "open_rejected_ack",
                            identityKey = request.identity.key,
                            generation = 0L,
                            requestId = request.requestId,
                        ) { put("reason", decision.reason) }
                        sharedStores.eventBus.post(
                            eventName = RAFT_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
                            eventData =
                                RaftAdaptiveDetailOpenAck(
                                    shellInstanceId = storeScope.shellInstanceId,
                                    requestId = request.requestId,
                                    accepted = false,
                                    reason = decision.reason,
                                ).toJson(),
                            crossProcess = true,
                            source = "adaptive_detail_open_rejected",
                        )
                        sharedStores
                            .adaptiveScrollAnchorStore(storeScope)
                            .discard(
                                shellInstanceId = storeScope.shellInstanceId,
                                identity = request.identity,
                                requestId = request.requestId,
                            )
                    }
                    is RaftAdaptiveDetailOpenDecision.Accepted -> {
                        // Ack is held until the destination pane child reports READY (mount +
                        // draft/viewport restore), not merely received. See the
                        // RAFT_ADAPTIVE_DETAIL_READY_EVENT subscription below.
                        postShellAdaptiveTrace(
                            hop = "open_accepted",
                            identityKey = request.identity.key,
                            generation = decision.generation,
                            requestId = request.requestId,
                        )
                    }
                }
            }
        val readySubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_DETAIL_READY_EVENT,
                crossProcess = true,
            ) { eventData ->
                val shellId = eventData?.optString(RAFT_ADAPTIVE_DETAIL_SHELL_ID)?.trim()
                    ?: return@observe
                if (shellId != storeScope.shellInstanceId) return@observe
                val identityKey = eventData.optString(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY).trim()
                val generation =
                    eventData.optDouble(RAFT_ADAPTIVE_DETAIL_GENERATION, 0.0).toLong()
                if (identityKey.isBlank() || generation <= 0L) return@observe
                postShellAdaptiveTrace(
                    hop = "ready_received",
                    identityKey = identityKey,
                    generation = generation,
                )
                val session = adaptiveWorkspaceStore.snapshot()
                val (nextSession, pending) =
                    completeRaftAdaptiveDetailOpen(
                        identityKey = identityKey,
                        generation = generation,
                        session = session,
                    )
                if (pending == null) {
                    postShellAdaptiveTrace(
                        hop = "ready_no_pending",
                        identityKey = identityKey,
                        generation = generation,
                    )
                    return@observe
                }
                adaptiveWorkspaceStore.update { nextSession }
                // READY means the destination restored and mounted; ack authorizes the source to
                // attempt the native commit. The staged anchor is NOT consumed here — it is
                // consumed only on the native commit receipt (see the commit-result subscription
                // below), so a rejected or failed commit keeps the recovery record.
                if (pending.acknowledged) return@observe
                postShellAdaptiveTrace(
                    hop = "ack_sent",
                    identityKey = pending.identity.key,
                    generation = generation,
                    requestId = pending.requestId,
                )
                sharedStores.eventBus.post(
                    eventName = RAFT_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
                    eventData =
                        RaftAdaptiveDetailOpenAck(
                            shellInstanceId = storeScope.shellInstanceId,
                            requestId = pending.requestId,
                            accepted = true,
                            generation = generation,
                        ).toJson(),
                    crossProcess = true,
                    source = "adaptive_detail_open_ready",
                )
            }
        // Source-side cancel notice (task #18 hardening): drops exactly the named pending request
        // and its staged anchor so a READY arriving after the source gave up resolves to
        // ready_no_pending instead of a stray accepted ACK. The drop applies to acknowledged
        // pendings too: a cancel event only exists when the source never dispatched commit
        // (dispatchAdaptiveCommit sets commitDispatched before any later cancel can run), so no
        // commit receipt can ever arrive to settle this entry — retaining it would leak the
        // pending while the transaction is already dead on the source side.
        val cancelSubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_DETAIL_CANCEL_EVENT,
                crossProcess = true,
            ) { eventData ->
                val cancel = eventData?.toRaftAdaptiveDetailCancelOrNull()
                    ?: return@observe
                if (cancel.shellInstanceId != storeScope.shellInstanceId) return@observe
                val session = adaptiveWorkspaceStore.snapshot()
                val pending = session.pendingDetailOpens[cancel.requestId] ?: return@observe
                adaptiveWorkspaceStore.update { current ->
                    dropRaftAdaptiveDetailOpen(cancel.requestId, current)
                }
                sharedStores
                    .adaptiveScrollAnchorStore(storeScope)
                    .discard(
                        shellInstanceId = storeScope.shellInstanceId,
                        identity = pending.identity,
                        requestId = cancel.requestId,
                    )
                postShellAdaptiveTrace(
                    hop = "open_cancelled",
                    identityKey = pending.identity.key,
                    generation = cancel.generation,
                    requestId = cancel.requestId,
                ) { put("reason", cancel.reason) }
            }
        // The source reports its prepared controller identities after native prepare; bind them
        // to the pending request so a stray commit receipt cannot settle it.
        val preparedSubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_DETAIL_PREPARED_EVENT,
                crossProcess = true,
            ) { eventData ->
                val prepared = eventData?.toRaftAdaptiveDetailPreparedOrNull()
                    ?: return@observe
                if (prepared.shellInstanceId != storeScope.shellInstanceId) return@observe
                adaptiveWorkspaceStore.update { current ->
                    markRaftAdaptiveDetailOpenPrepared(
                        requestId = prepared.requestId,
                        sourceControllerToken = prepared.sourceControllerToken,
                        targetControllerToken = prepared.targetControllerToken,
                        session = current,
                    )
                }
            }
        // The authoritative end of an adoption is the native commit receipt, not the ack.
        // commit=true → consume the staged anchor exactly once under the request fence and
        // settle the pending entry; failure → discard the anchor, drop pending, and clear the
        // staged detail selection so the destination does not stay mounted while the source
        // page is still alive.
        val commitSubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_NATIVE_COMMIT_RESULT_EVENT,
                crossProcess = true,
            ) { eventData ->
                val result = eventData?.toRaftAdaptiveNativeCommitResultOrNull()
                    ?: return@observe
                val session = adaptiveWorkspaceStore.snapshot()
                val (nextSession, pending) =
                    settleRaftAdaptiveDetailOpen(
                        result = result,
                        session = session,
                    )
                if (pending == null) return@observe
                val anchorStore = sharedStores.adaptiveScrollAnchorStore(storeScope)
                if (result.committed) {
                    adaptiveWorkspaceStore.update { nextSession }
                    anchorStore.consume(
                        shellInstanceId = storeScope.shellInstanceId,
                        identity = pending.identity,
                        requestId = pending.requestId,
                    )
                } else {
                    // Keep the pending entry and the staged anchor: a rejected/timed-out commit
                    // may mean the native pop already happened but the stack readback failed —
                    // discarding the recovery record here would lose the viewport anchor while
                    // both pages are gone (source popped, destination still mounted). Retention
                    // is what keeps an explicit retry or user navigation able to recover; the
                    // TerminalReset/AuthorityChanged paths still drop+nack as before.
                }
            }
        val closeSubscription =
            sharedStores.eventBus.observe(
                eventName = RAFT_ADAPTIVE_DETAIL_CLOSE_EVENT,
                crossProcess = true,
            ) { eventData ->
                val request = eventData?.toRaftAdaptiveDetailCloseRequestOrNull()
                    ?: return@observe
                if (request.shellInstanceId != storeScope.shellInstanceId) return@observe
                val before = adaptiveWorkspaceStore.snapshot()
                val after =
                    // task #36: a side-column page's close only closes the column.
                    adaptiveWorkspaceStore.update { current -> current.closeFromDetailPage(request) }
                if (before.workspace?.selection != after.workspace?.selection) {
                    releaseAdaptiveHandoffLease(request.generation)
                    homeShellStore.dispatch(
                        SharedHomeShellIntent.SetSelectedConversation(after.detailListSelectionId())
                    )
                }
            }
        onDispose {
            openSubscription.close()
            readySubscription.close()
            cancelSubscription.close()
            preparedSubscription.close()
            commitSubscription.close()
            closeSubscription.close()
        }
    }
    val tasksServerMembersEntry by remember(appShellServerMembersStore, readyServerId) {
        val serverId = readyServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        if (serverId == null) {
            MutableStateFlow(SharedServerMembersEntry(serverId = ""))
        } else {
            appShellServerMembersStore.serverMembersEntryFlow(serverId)
        }
    }.collectAsState(
        remember(appShellServerMembersStore, readyServerId) {
            readyServerId?.takeIf { it.isNotBlank() }
                ?.let { appShellServerMembersStore.currentServerMembersEntry(ServerId(it)) }
                ?: SharedServerMembersEntry(serverId = readyServerId.orEmpty())
        }
    )
    LaunchedEffect(readySession?.qaBootstrap?.ticketDigest, homeState.content) {
        if (
            readySession?.qaBootstrap != null &&
            (homeState.content is SharedHomeContentState.Content || homeState.content is SharedHomeContentState.Empty)
        ) {
            raftPlatformCompleteQaBootstrapRouteReady(authDataSource)
        }
    }
    LaunchedEffect(storeScope.shellInstanceId, authGateState, readyServerId) {
        when (val state = authGateState) {
            is SharedAuthGateState.Ready -> {
                val serverId = readyServerId
                if (serverId.isNullOrBlank()) {
                    ProcessAttachmentAuthScopes.retireShell(storeScope.shellInstanceId)
                    bridge.attachmentDownloadScopeRetired(storeScope.shellInstanceId)
                } else {
                    val lease = ProcessAttachmentAuthScopes.bind(storeScope, state.session, serverId)
                    bridge.attachmentDownloadOwnerChanged(lease)
                    // Product analytics: events go to the server the signed-in shell shows.
                    RaftProductEvents.setSignedIn(serverId)
                }
            }
            SharedAuthGateState.AuthRequired,
            is SharedAuthGateState.EmailVerificationRequired,
            is SharedAuthGateState.ProfileSetupRequired,
            is SharedAuthGateState.ServerRequired,
            is SharedAuthGateState.BootstrapRecoveryRequired -> {
                ProcessAttachmentAuthScopes.retireShell(storeScope.shellInstanceId)
                bridge.attachmentDownloadScopeRetired(storeScope.shellInstanceId)
                // Nothing before sign-in (RFC-067 native apps): drop what was recorded so far.
                RaftProductEvents.setSignedOut()
            }
            SharedAuthGateState.Restoring,
            is SharedAuthGateState.Error -> Unit
        }
    }
    val activityUnreadServerId = remember(readyServerId) { ServerId(readyServerId.orEmpty()) }
    val activityUnreadSummaryFlow =
        remember(sharedStores.activityUnreadSummaryStore, activityUnreadServerId) {
            sharedStores.activityUnreadSummaryStore.projectionFlow(activityUnreadServerId)
        }
    val activityUnreadSummary by activityUnreadSummaryFlow.collectAsState(
        sharedStores.activityUnreadSummaryStore.currentProjection(activityUnreadServerId)
    )
    val serverActivityExpectedOwner =
        readySession?.user?.id
            ?.takeIf { it.isNotBlank() }
            ?.let { accountId ->
                readyServerId?.takeIf { it.isNotBlank() }?.let { serverId ->
                    sharedStores.readReceiptOwner(serverId, accountId)
                }
            }
    // Forward the process-owned selected-server Activity projection to the host badge. Source is
    // diagnostics-only; hosts display count and must not branch on provenance.
    LaunchedEffect(homeState.appBadgeCount, activityUnreadSummary, authoritativeHomeStartAllowed) {
        val fallback = homeState.appBadgeCount
        val useActivityProjection = authoritativeHomeStartAllowed && readySession != null
        bridge.appBadge(
            count = if (useActivityProjection && activityUnreadSummary.hasValue) {
                activityUnreadSummary.totalUnreadCount
            } else {
                fallback.count
            },
            source =
                if (useActivityProjection && activityUnreadSummary.hasValue) {
                    build.raft.app.data.AppBadgeSource.ACTIVITY_UNREAD_SUMMARY.wire
                } else {
                    fallback.source.wire
                },
            isStale =
                if (useActivityProjection && activityUnreadSummary.hasValue) {
                    activityUnreadSummary.freshness != build.raft.app.data.ActivityUnreadSummaryFreshness.Fresh
                } else {
                    fallback.isStale
                }
        )
    }
    // Sync top pinned channels / DMs to the host launcher icon dynamic shortcut menu.
    LaunchedEffect(homeState.content, readySession, authoritativeHomeStartAllowed) {
        if (!authoritativeHomeStartAllowed || readySession == null) {
            bridge.syncDesktopShortcuts(emptyList())
            return@LaunchedEffect
        }
        val content = homeState.content as? SharedHomeContentState.Content
        if (content != null) {
            val shortcuts = build.raft.app.data.extractTopPinnedDesktopShortcuts(content.snapshot, maxCount = 4)
            bridge.syncDesktopShortcuts(shortcuts)
        }
    }
    LaunchedEffect(sharedStores.activityUnreadSummaryStore, readyServerId, authoritativeHomeStartAllowed) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        val serverId = readyServerId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        sharedStores.activityUnreadSummaryStore.ensure(ServerId(serverId), reason = "app_shell_server_ready")
    }
    val renderedHomeServerId =
        (homeState.content as? SharedHomeContentState.Content)?.snapshot?.server?.id?.value
    val navigationStoreScope =
        remember(
            storeScope.shellInstanceId,
            storeScope.initialServerId,
            selectedServerId,
            renderedHomeServerId
        ) {
            RaftShellStoreScope.create(
                shellInstanceId = storeScope.shellInstanceId,
                initialServerId =
                    appShellNavigationServerId(
                        selectedServerId = selectedServerId,
                        renderedHomeServerId = renderedHomeServerId,
                        scopeInitialServerId = storeScope.initialServerId
                    )
            )
        }
    // The native shell can dispatch Back directly to popMainRoute, bypassing
    // a child Kuikly page's BackHandler. Keep the same selection in both hosts.
    val membersDetailSelection = remember(navigationStoreScope, readySession?.user?.id, readyServerId) {
        mutableStateOf<RaftMemberDetailPayload?>(null)
    }
    val latestReadySession by rememberUpdatedState(readySession)
    val latestAuthoritativeHomeStartAllowed by rememberUpdatedState(authoritativeHomeStartAllowed)
    val latestCachedRestoreUsable by rememberUpdatedState(cachedRestoreUsable)
    LaunchedEffect(renderedHomeServerId, pendingHomeServerSwitchId) {
        val targetServerId = pendingHomeServerSwitchId ?: return@LaunchedEffect
        if (renderedHomeServerId != targetServerId) return@LaunchedEffect
        bridge.performanceMarker(
            phase = "home_server_switch_state_observed",
            routeName = "kuikly_home",
            event = "instant",
            metadata =
                mapOf(
                    "serverId" to targetServerId,
                    "channels" to
                        ((homeState.content as? SharedHomeContentState.Content)
                            ?.snapshot
                            ?.channels
                            ?.size
                            ?: 0).toString()
                )
        )
        observedHomeServerSwitchId = targetServerId
    }
    val announcementOwner = listOf(
        sharedStores.apiEnvironmentStore.current().apiBaseUrl,
        readySession?.user?.id,
        readyServerId,
        processRealtimeAuthority?.witness?.authGeneration?.toString(),
        authoritativeHomeStartAllowed.toString(),
    )
    val latestAnnouncementOwner by rememberUpdatedState(announcementOwner)
    val announcementStore = remember(announcementsClient, announcementOwner) {
        build.raft.app.data.SharedAnnouncementStore(announcementsClient) {
            announcementOwner == latestAnnouncementOwner &&
                readySession != null && authoritativeHomeStartAllowed
        }
    }
    val activeAnnouncement by announcementStore.announcement.collectAsState()
    val latestAnnouncementStore by rememberUpdatedState(announcementStore)
    DisposableEffect(announcementStore) {
        onDispose { announcementStore.close() }
    }
    LaunchedEffect(announcementStore) { announcementStore.refresh() }
    val latestSelectedTab by rememberUpdatedState(selectedTab)
    val latestSelectedHomeDestination by rememberUpdatedState(selectedHomeDestination)
    val latestVisibleAppearanceGeneration by rememberUpdatedState(visibleAppearanceGeneration)
    val latestPageVisible by rememberUpdatedState(pageVisible)
    val homeConversationDirectoryFlow =
        remember(homeStore, readyServerId) {
            val serverId = readyServerId?.takeIf { it.isNotBlank() }
            if (serverId == null) {
                MutableStateFlow(SharedConversationDirectoryState())
            } else {
                homeStore.observeServer(ServerId(serverId))
            }
        }
    val homeConversationDirectory by homeConversationDirectoryFlow.collectAsState(SharedConversationDirectoryState())
    val appShellConversationDirectory =
        remember(homeConversationDirectory, homeActivityState.snapshot, homeActivityState.dmPeerNames) {
            val activityDirectory =
                homeActivityState.snapshot?.toSharedConversationDirectoryState(
                    dmPeerNames = homeActivityState.dmPeerNames
                )
                    ?: SharedConversationDirectoryState()
            listOf(homeConversationDirectory, activityDirectory)
                .mergedSharedConversationDirectoryState()
        }
    val latestAppShellConversationDirectory by rememberUpdatedState(appShellConversationDirectory)
    val latestSelectedHomeConversationId by rememberUpdatedState(
        appShellForegroundVisibleHomeConversationId(
            homeShellSelectedConversationId = selectedHomeConversationId,
            homeStoreSelectedConversationId = homeState.selectedConversationId
        )
    )
    LaunchedEffect(
        appShellServerMembersStore,
        readyServerId,
        foregroundMessageBannersEnabled,
        authoritativeHomeStartAllowed
    ) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        val serverId = readyServerId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (!foregroundMessageBannersEnabled) return@LaunchedEffect
        appShellServerMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Background)
    }
    LaunchedEffect(authGateState, suppressRestoreLanding, selectedTab, readyServerId) {
        bridge.log(
            "app_shell_auth_gate_state state=${authGateState.authGateDiagnosticsName()} " +
                "selectedServer=${!readyServerId.isNullOrBlank()} selectedTab=$selectedTab " +
                "suppressRestoreLanding=$suppressRestoreLanding pagerId=${pager.pagerId} " +
                "childShellId=$childTabShellId"
        )
    }
    val homeSurfaceVisible =
        pageVisible &&
            selectedTab == "home" &&
            selectedHomeDestination == null
    val parentShouldStartHome =
        authoritativeHomeStartAllowed &&
        appShellParentShouldStartHome(
            pageVisible = pageVisible,
            selectedTab = selectedTab,
            selectedHomeDestination = selectedHomeDestination,
            readyServerId = readyServerId,
            readyAccountId = readySession?.user?.id,
            state = homeState,
            // task #94: the DMs tab renders the same home store; starting the parent home
            // store must not be gated on the literal "home" tab while Slack nav is active.
            homeLikeTabs = if (slackNavMode) setOf("dms") else emptySet()
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
    LaunchedEffect(
        homeSurfaceVisible,
        readyServerId,
        authoritativeHomeStartAllowed,
        setupAdmissionAllowsHome,
        cachedHomePreview
    ) {
        val delayMs = homeSurfaceVisibilityDispatchDelayMs(homeSurfaceVisible)
        val surfaceWaitMark = TimeSource.Monotonic.markNow()
        if (delayMs > 0L) {
            delay(delayMs)
        }
        homeShellStore.dispatch(
            SharedHomeShellIntent.SetSurfaceVisible(
                homeSurfaceVisible &&
                    (readyServerId ?: homePreviewServerId) != null &&
                    (setupAdmissionAllowsHome || cachedHomePreview)
            )
        )
        raftLogInfo(
            "SlockForegroundPerf",
            "surface_visible_wait plannedMs=$delayMs actualMs=${surfaceWaitMark.elapsedNow().inWholeMilliseconds} visible=$homeSurfaceVisible"
        )
    }
    // Home counts no realtime unread for its selected conversation. A conversation opened as a pushed page
    // (phone, or desktop mode off) is closed once this shell page is visible again, so clear the selection
    // then; a detail still shown inside the shell keeps its list selection.
    var appShellPageWasHidden by remember { mutableStateOf(false) }
    LaunchedEffect(pageVisible) {
        if (!pageVisible) {
            appShellPageWasHidden = true
            return@LaunchedEffect
        }
        if (!appShellPageWasHidden) return@LaunchedEffect
        appShellPageWasHidden = false
        val selection =
            adaptiveWorkspaceStore.snapshot().selectedConversationWhenShellReturns(desktopModeOnLargeScreen)
        bridge.log("app_shell_return_selection selected=${selection.orEmpty()}")
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(selection))
    }
    LaunchedEffect(readyServerId, readySession?.accessToken, authoritativeHomeStartAllowed) {
        if (readyServerId.isNullOrBlank() || !authoritativeHomeStartAllowed) return@LaunchedEffect
        while (true) {
            delay(AppUpdateNotificationRefreshIntervalMs)
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.RefreshNotifications))
        }
    }
    DisposableEffect(appUpdateEvents, latestAuthoritativeHomeStartAllowed) {
        val callbackRef =
            appUpdateEvents.addStatusListener {
                if (!latestAuthoritativeHomeStartAllowed) return@addStatusListener
                appShellCoroutineScope.launch {
                    homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.RefreshNotifications))
                }
            }
        onDispose {
            appUpdateEvents.removeStatusListener(callbackRef)
        }
    }
    val sessionTimeFormat = readySession.appShellThreadTimeFormat()
    val threadTimeContext =
        remember(hostThreadTimeCapabilities, sessionTimeFormat) {
            hostThreadTimeCapabilities
                ?.let {
                    RaftTimeContext(
                        localOffsetMinutes = it.localOffsetMinutes,
                        timeFormat = it.timeFormat
                    )
                }
                .withTimeFormat(sessionTimeFormat)
        }
    fun selectAppShellTab(nextTab: String, directChildSwitch: Boolean = false) {
        prewarmAdmissionGate.invalidate("tab_select:$nextTab")
        serverMenuState.dismiss()
        val retainedTab = retainedAppShellSelectedTab(nextTab)
        val nextEpoch = appShellNextChildTabSwitchEpoch(selectedTab, retainedTab, childTabSwitchEpoch)
        val direct = directChildSwitch && retainedTab in slackNavTabKeys
        bridge.log(
            "app_shell_select_tab next=$nextTab retained=$retainedTab from=$selectedTab " +
                "epoch=$childTabSwitchEpoch->$nextEpoch direct=$direct mode=single_child"
        )
        childTabSwitchEpoch = nextEpoch
        selectedTab = retainedTab
    }
    LaunchedEffect(diagnosticsDeepLinkRequest?.id, readyServerId, authoritativeHomeStartAllowed) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        val request = diagnosticsDeepLinkRequest ?: return@LaunchedEffect
        if (readyServerId == null || consumedDiagnosticsDeepLinkId == request.id) {
            return@LaunchedEffect
        }
        consumedDiagnosticsDeepLinkId = request.id
        selectAppShellTab("settings")
        retainedAppShellTabs = retainedAppShellTabs + "settings"
        bridge.log(
            "diagnostics_deeplink_start id=${request.id} autoCloseMs=${request.autoCloseMs}"
        )
        val opened =
            pager.openSettingsNativeDetailPage(
                page = SettingsNativeDetailPage.Diagnostics,
                storeScope = navigationStoreScope,
                launchIntent =
                    SettingsNativeDetailLaunchIntent(
                        autoCloseMs = request.autoCloseMs,
                    ),
            )
        if (!opened) {
            bridge.logWarn("diagnostics_deeplink_rejected id=${request.id}")
        }
        bridge.log("diagnostics_deeplink_complete id=${request.id} opened=$opened")
    }
    val appShellModalHostState = rememberRaftUnifiedDialogHostState()
    val realtimeEventMapper =
        remember(sharedStores) {
            RealtimeEventMapper(
                messageShadowObserver = sharedStores.messageShadowObserver,
            )
        }
    var realtimeConnectionFamilyId by remember { mutableStateOf<String?>(null) }
    var pendingRealtimeAuthRefreshReason by remember { mutableStateOf<String?>(null) }
    var pendingRealtimeAuthRefreshPreviousFamilyId by remember { mutableStateOf<String?>(null) }
    var realtimeAuthorityConnectTick by remember { mutableStateOf(0) }
    var membersListRefreshKey by remember { mutableStateOf(0) }
    var membersAgentDetailRefreshKey by remember { mutableStateOf(0) }
    var membersListCachedContent by remember { mutableStateOf<SharedMembersUiState.Content?>(null) }
    val foregroundBannerStore =
        remember(sharedStores, navigationStoreScope) {
            sharedStores.foregroundBannerStore(navigationStoreScope)
        }
    val foregroundMessageBanner by foregroundBannerStore.banner.collectAsState()
    // System notifications stay silent for the conversation on screen, same rule as the in-app banner.
    DisposableEffect(sharedStores, pager.pagerId, foregroundBannerStore) {
        val ownerId = "app-shell:${pager.pagerId}"
        RaftVisibleConversationRegistry.register(ownerId) {
            if (!sharedStores.isActiveAppShellPager(pager.pagerId)) return@register null
            RaftVisibleConversationSnapshot(
                currentServerId = selectedServerStore.selectedServerId.value,
                visibleConversationKey =
                    appShellVisibleForegroundConversationKey(
                        selectedTab = latestSelectedTab,
                        selectedHomeDestination = latestSelectedHomeDestination,
                        selectedConversationId = latestSelectedHomeConversationId
                    ),
                visibleRouteIdentity = foregroundBannerStore.visibleRouteIdentity(),
                conversationDirectory = latestAppShellConversationDirectory
            )
        }
        onDispose { RaftVisibleConversationRegistry.unregister(ownerId) }
    }
    val visibleRefreshGate = remember { AppShellVisibleRefreshGate() }

    fun refreshVisibleAppShellRoute(
        reason: String,
        signal: AppShellVisibleRefreshSignal,
        appearanceGeneration: Int,
        foregroundEpoch: Long? = null
    ): Boolean {
        if (latestReadySession == null || !latestAuthoritativeHomeStartAllowed) return false
        val refreshTarget =
            "${retainedAppShellSelectedTab(latestSelectedTab)}:${latestSelectedHomeDestination.orEmpty()}"
        if (
            !visibleRefreshGate.shouldRefresh(
                signal = signal,
                appearanceGeneration = appearanceGeneration,
                target = refreshTarget,
                foregroundEpoch = foregroundEpoch
            )
        ) {
            bridge.log(
                "app_shell_visible_refresh_skip reason=$reason " +
                    "cause=same_appearance_or_foreground_epoch tab=${latestSelectedTab} " +
                    "homeDestination=${latestSelectedHomeDestination.orEmpty()}"
            )
            return false
        }
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
            // task #94: DMs renders the same home store; Activity forwards foreground refresh
            // to ActivityStore, and More is the settings surface.
            "dms" -> homeShellStore.dispatch(SharedHomeShellIntent.ForegroundRefresh)
            "activity" -> homeShellStore.dispatch(SharedHomeShellIntent.ForegroundRefresh)
            "more" -> Unit
            "tasks" -> tasksViewModel.dispatch(SharedTasksRouteIntent.Refresh)
            "members" -> {
                membersListRefreshKey += 1
                membersAgentDetailRefreshKey += 1
            }
            "settings" -> Unit
        }
        return true
    }

    LaunchedEffect(visibleAppearanceGeneration) {
        if (
            appShellAppearanceShouldRefresh(
                appearanceGeneration = visibleAppearanceGeneration,
                pageVisibleTick = pageVisibleTick,
                selectedTab = latestSelectedTab,
                selectedHomeDestination = latestSelectedHomeDestination,
                hasRetainedHomeContent = homeState.content is SharedHomeContentState.Content
            )
        ) {
            if (latestSelectedHomeDestination != null) {
                homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
            } else {
                refreshVisibleAppShellRoute(
                    reason =
                        if (pageVisibleTick == 0) {
                            "initial_page_did_appear"
                        } else {
                            "page_did_appear"
                        },
                    signal = AppShellVisibleRefreshSignal.PageDidAppear,
                    appearanceGeneration = visibleAppearanceGeneration
                )
            }
        }
    }

    fun resetAppShellSessionState() {
        adaptiveWorkspaceStore.update { current ->
            current.copy(
                workspace =
                    current.workspace?.let { state ->
                        reduceRaftAdaptiveWorkspace(state, RaftAdaptiveWorkspaceIntent.TerminalReset)
                    },
                launchPayload = null,
            )
        }
        releaseAdaptiveHandoffLease()
        adaptiveDefaultLandingServerId = null
        realtimeConnectionFamilyId = null
        pendingRealtimeAuthRefreshReason = null
        pendingRealtimeAuthRefreshPreviousFamilyId = null
        membersListRefreshKey = 0
        membersAgentDetailRefreshKey = 0
        membersListCachedContent = null
        foregroundBannerStore.clear()
        conversationForwardHistory.clear()
        bridge.setConversationForwardGestureEnabled(false)
        sharedStores.homeSavedStateCache.clear()
        selectAppShellTab("home")
        retainedAppShellTabs = setOf("home")
        prewarmedAppShellTabs = emptySet()
        homeListPositions = AppShellServerLazyListPositions()
        pendingHomeServerSwitchId = null
        observedHomeServerSwitchId = null
        tasksListPosition = AppShellLazyListPosition()
        membersListState = AppShellMembersListState()
        // Retire all in-memory Home rows on an auth/session reset. Durable identity snapshots are
        // intentionally retained and will be rehydrated only under the successor account/server
        // key; keeping the old rows here would create an A-account/B-account preview window.
        homeStore.reset()
        homeShellStore.reset()
    }
    LaunchedEffect(authSessionResetGeneration) {
        if (authSessionResetGeneration > appliedAuthSessionResetGeneration) {
            appliedAuthSessionResetGeneration = authSessionResetGeneration
            resetAppShellSessionState()
        }
    }

    LaunchedEffect(homeState.content, authGateState) {
        val error = (homeState.content as? SharedHomeContentState.Error)?.code ?: return@LaunchedEffect
        if (!ScreenError(error, retryable = false).isAuthSessionError()) return@LaunchedEffect
        val cachedSession = runCatchingPreservingCancellation { shellAuthStore.readSession() }.getOrNull()
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
        authGateMessage = AuthGateMessage.Static(Res.string.auth_error_sign_in_continue.desc())
        authGateViewModel.dispatch(SharedAuthGateIntent.SignedOut)
        resetAppShellSessionState()
    }

    val conversationForwardGestureRuntimeAuthority =
        remember(
            settingsCapabilities.supportsConversationForwardGesture,
            conversationForwardHistory,
            bridge
        ) {
            ConversationForwardGestureRuntimeAuthority(
                supported = settingsCapabilities.supportsConversationForwardGesture
            ) { effectiveEnabled ->
                conversationForwardGestureEnabled = effectiveEnabled
                reconcileConversationForwardGesturePreference(
                    enabled = effectiveEnabled,
                    history = conversationForwardHistory,
                    disableNativeGesture = {
                        bridge.setConversationForwardGestureEnabled(false)
                    }
                )
            }
        }
    val chatLayoutPreferenceSnapshotRuntimeAuthority =
        remember(foregroundBannerStore) {
            ChatLayoutPreferenceSnapshotRuntimeAuthority { chatLayout ->
                myMessageAlignment = chatLayout.myMessageAlignment
                desktopModeOnLargeScreen = chatLayout.desktopModeOnLargeScreen
                compactConsecutiveMessages = chatLayout.compactConsecutiveMessages
                foregroundMessageBannersEnabled = chatLayout.foregroundMessageBannersEnabled
                if (!chatLayout.foregroundMessageBannersEnabled) {
                    foregroundBannerStore.dismiss()
                }
                activityTaskStatusBadgesEnabled = chatLayout.activityTaskStatusBadgesEnabled
                serverDrawerEnabled = chatLayout.serverDrawerEnabled
                homeServerAvatarEnabled = chatLayout.homeServerAvatarEnabled
                    }
        }
    DisposableEffect(
        chatLayoutPreferenceEvents,
        chatLayoutPreferenceSnapshotRuntimeAuthority,
        conversationForwardGestureRuntimeAuthority,
    ) {
        val forwardSubscription =
            chatLayoutPreferenceEvents.addConversationForwardGestureListener { enabled ->
                conversationForwardGestureRuntimeAuthority.applyProcessPreferenceChange(enabled)
            }
        val snapshotSubscription =
            chatLayoutPreferenceEvents.addPreferenceListener { chatLayout ->
                chatLayoutPreferenceSnapshotRuntimeAuthority.applyProcessPreferenceChange(chatLayout)
            }
        onDispose {
            forwardSubscription.close()
            snapshotSubscription.close()
        }
    }
    LaunchedEffect(
        chatLayoutPreferenceRepository,
        chatLayoutPreferenceSnapshotRuntimeAuthority,
        conversationForwardGestureRuntimeAuthority,
    ) {
        val chatLayout = chatLayoutPreferenceRepository.getChatLayoutPreference()
        chatLayoutPreferenceSnapshotRuntimeAuthority.seedFromRepository(chatLayout)
        conversationForwardGestureRuntimeAuthority.seedFromRepository(
            chatLayout.conversationForwardGestureEnabled
        )
    }
    LaunchedEffect(conversationForwardGestureEnabled, authoritativeHomeStartAllowed) {
        conversationForwardHistory.setEnabled(conversationForwardGestureEnabled)
        bridge.setConversationForwardGestureEnabled(
            conversationForwardGestureEnabled &&
                authoritativeHomeStartAllowed &&
                homeSurfaceVisible &&
                homeState.content is SharedHomeContentState.Content &&
                conversationForwardHistory.canForward
        )
    }
    val latestForegroundMessageBannersEnabled by rememberUpdatedState(foregroundMessageBannersEnabled)
    // The in-app banner obeys the device push preview choice (read per event from the single store).
    val pushPreviewModeStore = remember { KeyValuePushPreviewModePreferenceStore(raftSharedPreferenceStore()) }
    val latestPushPreviewBannerStrings by rememberUpdatedState(
        RaftPushPreviewBannerStrings(
            hiddenTitle = stringResource(Res.string.push_preview_hidden_title),
            hiddenMessage = stringResource(Res.string.push_preview_hidden_message),
            hiddenMention = stringResource(Res.string.push_preview_hidden_mention),
            senderOnlyMessageTemplate =
                stringResource(Res.string.push_preview_sender_only_message, RaftPushPreviewBannerStrings.SENDER_PLACEHOLDER),
            senderOnlyMentionTemplate =
                stringResource(Res.string.push_preview_sender_only_mention, RaftPushPreviewBannerStrings.SENDER_PLACEHOLDER)
        )
    )
    when (val gate = authGateState) {
        SharedAuthGateState.Restoring -> {
            if (cachedHomePreview) {
                AppShellCachedHomePreviewContent(
                    bridge = bridge,
                    shellScope = shellScope,
                    state = homeState
                )
            } else {
                if (suppressRestoreLanding) {
                    AppShellSilentRestoreContent()
                } else {
                    AppShellRestoreSplashContent()
                }
                return
            }
            return
        }
        SharedAuthGateState.AuthRequired -> {
            val localizedAuthGateMessage = authGateMessage.localizedMessage()
            LaunchedEffect(localizedAuthGateMessage) {
                pager.openLoginReplacingCurrentPage(localizedAuthGateMessage)
            }
            AppShellRestoreSplashContent()
            return
        }
        is SharedAuthGateState.BootstrapRecoveryRequired -> {
            AppShellSilentRestoreContent()
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
        is SharedAuthGateState.EmailVerificationRequired -> {
            AppShellEmailVerificationGateContent(
                session = gate.session,
                authDataSource = authDataSource,
                onRefresh = {
                    authGateMessage = null
                    authGateViewModel.dispatch(SharedAuthGateIntent.Restore)
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
        is SharedAuthGateState.ProfileSetupRequired -> {
            LaunchedEffect(gate.session.user.id) {
                pager.openAuthProfileSetupReplacingCurrentPage()
            }
            AppShellRestoreSplashContent()
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

    if (setupAdmissionHost != null && !setupAdmissionHost.allowsHome && !cachedRestoreUsable) {
        setupAdmissionHost.content()
        return
    }

    LaunchedEffect(readyServerId, authGateState, authoritativeHomeStartAllowed) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        val serverId = readyServerId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        when (
            val request =
                sharedStores.authLossFunnel.consumePendingOpenRequest(
                    serverId = serverId,
                    currentAccountPrincipalId = readySession?.user?.id.orEmpty(),
                )
        ) {
            is AuthPendingOpenRequest.Thread -> {
                bridge.log(
                    "app_shell_auth_pending_open_consume type=thread serverId=$serverId " +
                        "threadId=${request.sourceThreadId.orEmpty()} parentMessageId=${request.parentMessageId.orEmpty()}"
                )
                val resolution =
                    resolveOpenRequest(
                        serverId = serverId,
                        sourceMessageIdentity = request.toConversationOpenSourceMessageIdentity(),
                        conversationDirectory = appShellConversationDirectory,
                        diagnosticsLogger = bridge::log
                    )
                val openRequest = (resolution as? ConversationOpenResolution.Resolved)?.request
                val launchPayload =
                    openRequest?.let {
                        buildStandaloneConversationThreadLaunchPayload(
                            conversation = it.conversationIdentity,
                            timeContext = threadTimeContext,
                            alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                            compactConsecutiveMessages = compactConsecutiveMessages,
                                            anchorMessageId = it.anchorMessageId,
                            originFallback = it.originFallback,
                            onWarn = bridge::logWarn
                        )
                    }
                if (launchPayload == null) {
                    bridge.logWarn("app_shell_auth_pending_open_rejected reason=identity_unresolved serverId=$serverId")
                    bridge.toast(conversationNotReadyMessage)
                } else {
                    pager.openStandaloneThreadLaunchPayload(launchPayload, navigationStoreScope)
                }
            }
            null -> Unit
        }
    }

    /**
     * Opens [launchPayload] in the shared workspace detail when the wide dual-pane workspace can
     * show it. Returns the new selection, or null when the caller must fall back to a native push.
     *
     * task #33: reduces on the store's CURRENT session inside the atomic update. Seeding from the
     * compose snapshot here was a lost update: a first tap right after switching to the DMs tab
     * could write back parentVisible=false (pre-switch), leaving the new detail inactive while the
     * previous conversation (e.g. Home's #all) stayed on screen.
     */
    fun openLaunchPayloadInAdaptiveDetail(
        launchPayload: WireThreadLaunchPayload,
        defaultLanding: Boolean = false,
    ): RaftAdaptiveDetailSelection? {
        val adaptiveIdentity = readyServerId?.let(launchPayload::toRaftAdaptiveDetailIdentity)
        if (!desktopModeOnLargeScreen || adaptiveIdentity == null) return null
        var before: SharedAdaptiveWorkspaceSession? = null
        val after =
            adaptiveWorkspaceStore.update { current ->
                before = current
                current.withOpenedDetail(adaptiveIdentity, launchPayload, defaultLanding)
            }
        val opened =
            after.workspace?.selection?.takeIf { after !== before && it.identity == adaptiveIdentity }
                ?: return null
        if (opened != before?.workspace?.selection || before?.launchPayload == null) {
            installAdaptiveHandoffLease(opened, launchPayload)
        }
        // Dual-pane detail does not push a native controller, so it must neither become
        // a Forward entry nor leave an older native-push branch reachable.
        conversationForwardHistory.clear()
        bridge.setConversationForwardGestureEnabled(false)
        return opened
    }

    fun openHomeConversation(
        conversation: ThreadLaunchConversationIdentity,
        source: String,
        anchorMessageId: String? = null,
        recordForwardHistory: Boolean = true,
        originFallback: ThreadLaunchOriginFallback? = null,
        // Shell-initiated wide default landing: only ever fills the visible empty pane; it must
        // never fall back to a standalone push or park a compact selection the user did not pick.
        adaptiveDefaultLanding: Boolean = false,
    ) {
        if (
            !appShellHomeConversationOpenAllowed(
                authoritativeHomeStartAllowed = latestAuthoritativeHomeStartAllowed,
                cachedRestoreUsable = latestCachedRestoreUsable
            )
        ) {
            bridge.logWarn(
                "home_open_conversation_drop reason=home_authority_pending " +
                    "threadId=${conversation.threadId.value} source=$source"
            )
            return
        }
        if (!latestAuthoritativeHomeStartAllowed) {
            bridge.log(
                "home_open_conversation_during_cached_restore threadId=${conversation.threadId.value} source=$source"
            )
        }
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
                    onWarn = { bridge.logWarn(it) },
                anchorMessageId = anchorMessageId,
                originFallback = originFallback
            ) ?: return
        bridge.log(
            "home_open_thread_launch source=$source threadId=${conversation.threadId.value} " +
                "sourceThreadId=${launchPayload.sourceThreadId.orEmpty()} surface=${launchPayload.surfaceKind} " +
                "parentChannelId=${launchPayload.parentChannelId.orEmpty()} parentMessageId=${launchPayload.parentMessageId.orEmpty()} " +
                "anchorMessageId=${anchorMessageId.orEmpty()} originParentMessageId=${launchPayload.originParentMessageId.orEmpty()}"
        )
        val adaptiveIdentity = readyServerId?.let(launchPayload::toRaftAdaptiveDetailIdentity)
        if (openLaunchPayloadInAdaptiveDetail(launchPayload, adaptiveDefaultLanding) != null) return
        if (adaptiveDefaultLanding) {
            bridge.log("home_open_conversation_landing_refused threadId=${conversation.threadId.value}")
            return
        }
        // task #45: while desktop mode is OFF the standalone push still owns the screen,
        // but a parked workspace (same server) must track the user's current selection —
        // otherwise the next ON tick would resurrect a stale pre-OFF detail.
        if (!desktopModeOnLargeScreen && adaptiveIdentity != null) {
            adaptiveWorkspaceStore.update { current ->
                val workspace = current.workspace ?: return@update current
                if (workspace.serverId != adaptiveIdentity.serverId) return@update current
                val next =
                    reduceRaftAdaptiveWorkspace(
                        workspace,
                        RaftAdaptiveWorkspaceIntent.ParkSelection(adaptiveIdentity),
                    )
                current.copy(
                    workspace = next,
                    launchPayload =
                        if (next.selection?.identity == adaptiveIdentity) launchPayload
                        else current.launchPayload,
                )
            }
        }
        if (recordForwardHistory) {
            readyServerId
                ?.takeIf { it.isNotBlank() }
                ?.let { serverId ->
                    conversationForwardHistory.recordNewOpen(
                        conversation.toConversationForwardEntry(serverId)
                    )
                }
        }
        bridge.setConversationForwardGestureEnabled(false)
        pager.openStandaloneThreadLaunchPayload(launchPayload, navigationStoreScope)
    }

    fun openHomeMessageRequest(request: HomeMessageOpenRequest, source: String, fallbackAnchorMessageId: String? = null) {
        openHomeConversation(
            conversation = request.conversationIdentity,
            source = source,
            anchorMessageId = request.anchorMessageId ?: fallbackAnchorMessageId,
            // Notification/system window routing is not a user-selected Home branch.
            recordForwardHistory = false,
            originFallback = request.originFallback
        )
    }

    LaunchedEffect(
        desktopModeOnLargeScreen,
        adaptiveWorkspaceState?.pane,
        adaptiveWorkspaceState?.selection,
        adaptiveWorkspaceState?.parentVisible,
        adaptiveDefaultLandingServerId,
        readyServerId,
        selectedHomeConversationId,
        homeState.content,
        authoritativeHomeStartAllowed,
    ) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        val workspace = adaptiveWorkspaceState ?: return@LaunchedEffect
        val serverId = readyServerId?.trim()?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (
            !desktopModeOnLargeScreen ||
            !workspace.acceptsAdaptiveDefaultLanding() ||
            adaptiveDefaultLandingServerId == serverId
        ) {
            return@LaunchedEffect
        }
        val content = homeState.content as? SharedHomeContentState.Content ?: return@LaunchedEffect
        if (content.snapshot.server.id.value != serverId) return@LaunchedEffect
        val conversations: List<HomeConversationRow> =
            content.snapshot.channels + content.snapshot.directMessages
        val landingId =
            resolveRaftAdaptiveHomeLandingId(
                selectedThreadId = selectedHomeConversationId,
                candidates =
                    conversations.map { row ->
                        RaftAdaptiveHomeLandingCandidate(
                            threadId = row.threadId.value,
                            name = row.name,
                        )
                    },
            )
        val target = conversations.firstOrNull { row -> row.threadId.value == landingId }
        adaptiveDefaultLandingServerId = serverId
        target?.let { row ->
            openHomeConversation(
                conversation = row.toThreadLaunchConversationIdentity(),
                source = "adaptive_default_landing",
                recordForwardHistory = false,
                adaptiveDefaultLanding = true,
            )
        }
    }

    LaunchedEffect(pageVisibleTick, authoritativeHomeStartAllowed) {
        if (!authoritativeHomeStartAllowed) {
            bridge.setConversationForwardGestureEnabled(false)
            return@LaunchedEffect
        }
        if (pageVisibleTick > handledConversationForwardHomeVisibleTick) {
            handledConversationForwardHomeVisibleTick = pageVisibleTick
            conversationForwardHistory.recordHomeVisible()
            bridge.log(
                "conversation_forward_home_visible tick=$pageVisibleTick " +
                    "available=${conversationForwardHistory.canForward}"
            )
        }
        bridge.setConversationForwardGestureEnabled(
            conversationForwardGestureEnabled &&
                homeSurfaceVisible &&
                homeState.content is SharedHomeContentState.Content &&
                conversationForwardHistory.canForward
        )
    }

    LaunchedEffect(
        homeSurfaceVisible,
        readyServerId,
        renderedHomeServerId,
        conversationForwardRequestTick,
        authoritativeHomeStartAllowed,
    ) {
        if (!authoritativeHomeStartAllowed) {
            bridge.setConversationForwardGestureEnabled(false)
            return@LaunchedEffect
        }
        if (conversationForwardRequestTick <= handledConversationForwardRequestTick) {
            bridge.setConversationForwardGestureEnabled(
                conversationForwardGestureEnabled &&
                    homeSurfaceVisible &&
                    homeState.content is SharedHomeContentState.Content &&
                    conversationForwardHistory.canForward
            )
            return@LaunchedEffect
        }
        handledConversationForwardRequestTick = conversationForwardRequestTick
        bridge.setConversationForwardGestureEnabled(false)
        if (!homeSurfaceVisible) {
            bridge.log("conversation_forward_drop reason=home_surface_not_visible")
            return@LaunchedEffect
        }
        val content = homeState.content as? SharedHomeContentState.Content
        val readiness =
            WindowNavigationReadiness(
                knownServerIds = readySession?.servers.orEmpty().mapTo(linkedSetOf()) { server -> server.id },
                canonicalServerId = readySession?.currentServerId,
                homeSnapshotServerId = content?.snapshot?.server?.id?.value,
                homeSessionServerId = content?.session?.currentServerId,
                conversationDirectory = appShellConversationDirectory
            )
        when (val action = conversationForwardHistory.requestForward(readiness)) {
            is ConversationForwardAction.Open -> {
                bridge.log(
                    "conversation_forward_open serverId=${action.entry.serverId} " +
                        "threadId=${action.request.conversationIdentity.threadId.value}"
                )
                openHomeConversation(
                    conversation = action.request.conversationIdentity,
                    source = "ios_forward_gesture",
                    anchorMessageId = action.request.anchorMessageId,
                    recordForwardHistory = false,
                    originFallback = action.request.originFallback
                )
            }
            is ConversationForwardAction.Drop -> {
                bridge.logWarn("conversation_forward_drop reason=${action.reason}")
                bridge.toast(conversationUnavailableMessage)
            }
            null -> {
                bridge.log("conversation_forward_defer reason=home_or_directory_not_ready")
                bridge.setConversationForwardGestureEnabled(
                    conversationForwardGestureEnabled &&
                        homeState.content is SharedHomeContentState.Content &&
                        conversationForwardHistory.canForward
                )
            }
        }
    }

    // External sources only transport this typed identity envelope in pageData. The queue and
    // its consumed/opened markers live with the window's RaftShellStoreScope, so an AppShell
    // recreation or duplicate PendingIntent cannot replay the route.
    LaunchedEffect(
        notificationWindowNavigationIntent?.key,
        desktopShortcutWindowNavigationIntent?.key,
        systemNotificationNavigationTick,
        desktopShortcutNavigationTick,
        messageLinkNavigationTick,
        readySession?.currentServerId,
        readySession?.servers,
        homeState.content,
        appShellConversationDirectory,
        threadTimeContext,
        myMessageAlignment,
        authoritativeHomeStartAllowed
    ) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        notificationWindowNavigationIntent?.let(windowNavigationCoordinator::enqueue)
        desktopShortcutWindowNavigationIntent?.let(windowNavigationCoordinator::enqueue)
        val content = homeState.content as? SharedHomeContentState.Content
        val readiness =
            WindowNavigationReadiness(
                knownServerIds = readySession?.servers.orEmpty().mapTo(linkedSetOf()) { server -> server.id },
                canonicalServerId = readySession?.currentServerId,
                homeSnapshotServerId = content?.snapshot?.server?.id?.value,
                homeSessionServerId = content?.session?.currentServerId,
                conversationDirectory = appShellConversationDirectory
            )
        when (val action = windowNavigationCoordinator.advance(readiness)) {
            is WindowNavigationAction.ActivateServer -> {
                bridge.log(
                    "window_navigation_server_activate source=${action.intent.source} " +
                        "targetServerId=${action.intent.serverId} currentServerId=${readySession?.currentServerId.orEmpty()}"
                )
                when (val result = authDataSource.selectServer(action.intent.serverId)) {
                    is SharedAuthResult.Success -> {
                        authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(result.data))
                        resetAppShellSessionState()
                        bridge.log("window_navigation_server_activated targetServerId=${action.intent.serverId}")
                        if (action.intent.source == MessageLinkNavigationSource) {
                            result.data.servers.firstOrNull { it.id == action.intent.serverId }?.name
                                ?.takeIf { it.isNotBlank() }
                                ?.let { name ->
                                    bridge.toast(messageLinkSwitchedServerTemplate.replace(MessageLinkServerNamePlaceholder, name))
                                }
                        }
                    }
                    is SharedAuthResult.Failure -> {
                        windowNavigationCoordinator.rejectActivation(action.intent.key, result.code.name)
                            ?.let { rejection ->
                                bridge.logWarn("window_navigation_drop reason=${rejection.reason}")
                                if (rejection.intent.source == MessageLinkNavigationSource) {
                                    pager.openRaftMessageLinkNotFoundPage(navigationStoreScope)
                                } else {
                                    bridge.toast(notificationServerUnavailableMessage)
                                }
                            }
                    }
                    SharedAuthResult.Unauthenticated -> {
                        windowNavigationCoordinator.rejectActivation(action.intent.key, "unauthenticated")
                            ?.let { rejection ->
                                bridge.logWarn("window_navigation_drop reason=${rejection.reason}")
                                bridge.toast(notificationSignInMessage)
                            }
                    }
                }
            }
            is WindowNavigationAction.EnsureHome -> {
                homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Start))
                bridge.log(
                    "window_navigation_defer reason=home_not_ready_for_server " +
                        "source=${action.intent.source} targetServerId=${action.intent.serverId} " +
                        "contentServerId=${content?.snapshot?.server?.id?.value.orEmpty()}"
                )
            }
            is WindowNavigationAction.OpenConversation -> {
                selectAppShellTab("home")
                retainedAppShellTabs = retainedAppShellTabs + "home"
                homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
                bridge.log(
                    "window_navigation_open source=${action.intent.source} " +
                        "threadId=${action.request.conversationIdentity.threadId.value} " +
                        "focusedMessageId=${action.request.anchorMessageId.orEmpty()}"
                )
                openHomeMessageRequest(action.request, source = action.intent.source)
            }
            is WindowNavigationAction.Reject -> {
                bridge.logWarn(
                    "window_navigation_drop source=${action.intent.source} reason=${action.reason} " +
                        "targetServerId=${action.intent.serverId}"
                )
                if (action.intent.source == MessageLinkNavigationSource) {
                    pager.openRaftMessageLinkNotFoundPage(navigationStoreScope)
                    return@LaunchedEffect
                }
                bridge.toast(
                    if (action.reason == "server_unavailable") {
                        "Notification server unavailable"
                    } else {
                        "Notification conversation unavailable"
                    }
                )
            }
            null -> Unit
        }
    }
    // An external message link (Android App Link / iOS Universal Link). It waits in the process
    // inbox while signed out or restoring; with a ready session this shell takes it once,
    // resolves {slug}/conversation/message for this account, and either queues the same
    // identity-only WindowNavigationIntent a notification tap queues, or shows the one
    // "Message not found" page. The resolve runs in the shell scope so a session/key change
    // after the take cannot drop the link half-way.
    val messageLinkSessionReady = authoritativeHomeStartAllowed && readySession != null
    LaunchedEffect(pendingMessageLink?.id, messageLinkSessionReady) {
        val pending = pendingMessageLink
        if (!raftMessageLinkShouldTake(pending, messageLinkSessionReady)) return@LaunchedEffect
        val taken = RaftMessageLinkInbox.Process.take(pending!!.id)
        if (taken == null) {
            bridge.log("message_link_take_skipped id=${pending.id} reason=taken_replaced_or_expired")
            return@LaunchedEffect
        }
        val servers = readySession?.servers.orEmpty()
        bridge.log("message_link_take id=${taken.id} source=${taken.source} servers=${servers.size}")
        val resolver = RaftMessageLinkResolver(appShellNetworkClient, diagnosticsLogger = bridge::log)
        appShellCoroutineScope.launch {
            when (val resolution = resolver.resolve(taken.link, servers)) {
                is RaftMessageLinkResolution.Open -> {
                    val intent =
                        WindowNavigationIntent(
                            serverId = resolution.serverId,
                            sourceIdentity = resolution.sourceIdentity,
                            source = MessageLinkNavigationSource,
                            launchId = "message-link-${taken.id}"
                        )
                    val enqueued = windowNavigationCoordinator.enqueue(intent)
                    bridge.log(
                        "window_navigation_received source=$MessageLinkNavigationSource enqueued=$enqueued " +
                            "targetServerId=${intent.serverId}"
                    )
                    messageLinkNavigationTick += 1
                }
                is RaftMessageLinkResolution.NotFound ->
                    pager.openRaftMessageLinkNotFoundPage(navigationStoreScope)
                is RaftMessageLinkResolution.PublicPreview ->
                    pager.openRaftMessageLinkPublicPreviewPage(resolution.link, navigationStoreScope)
            }
        }
    }
    LaunchedEffect(
        processRealtimeAuthority?.witness?.connectionFamilyId,
        processRealtimeAuthority?.accessToken,
        realtimeAuthorityConnectTick,
        // Task #366: realtime follows the current server, so a server switch
        // must re-reconcile (disconnect old, connect new). Without this key the
        // effect only re-ran on auth-family/token/tick changes.
        canonicalFinalSelectedServerId,
        authoritativeHomeStartAllowed,
    ) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        val environment = sharedStores.apiEnvironmentStore.current()
        val desired =
            realtimeProcessDesiredSession(
                authority = processRealtimeAuthority,
                environmentId = environment.apiBaseUrl,
                socketUrl = environment.socketBaseUrl,
                currentServerId = canonicalFinalSelectedServerId,
            )
        realtimeConnectionFamilyId = desired?.connectionFamilyId
        appShellAgentDetailStore.dispatch(SharedAgentDetailIntent.ResetAgentActivitySeq)
        bridge.realtimeReconcile(
            serverIds = desired?.serverIds.orEmpty(),
            accessToken = desired?.accessToken.orEmpty(),
            environmentId = desired?.environmentId.orEmpty(),
            socketUrl = desired?.socketUrl ?: environment.socketBaseUrl,
            // Authority generation/family replacement is the reconnect
            // identity. A refresh tick may arrive before the new durable
            // authority publishes, so never force an old-token reconnect.
            forceReconnect = false,
            disconnectReason = GeneratedRealtimeContract.DisconnectReason.AuthInvalid,
            authorityScopeLeaseId = processRealtimeAuthority?.witness?.scopeLeaseId.orEmpty(),
            authorityPrincipalId = processRealtimeAuthority?.witness?.principalId.orEmpty(),
            authorityAuthGeneration = processRealtimeAuthority?.witness?.authGeneration ?: 0L,
            authorityConnectionFamilyId = processRealtimeAuthority?.witness?.connectionFamilyId.orEmpty(),
        )
        val familyId = desired?.connectionFamilyId
        pendingRealtimeAuthRefreshReason?.let { reason ->
            if (
                pendingRealtimeAuthRefreshPreviousFamilyId == null ||
                pendingRealtimeAuthRefreshPreviousFamilyId != familyId
            ) {
                pendingRealtimeAuthRefreshReason = null
                pendingRealtimeAuthRefreshPreviousFamilyId = null
                bridge.realtimeAuthRefreshFinished(reason, success = true)
                bridge.log(
                    "shared_realtime_auth_refresh_success reason=$reason " +
                        "serverCount=${desired?.serverIds?.size ?: 0}"
                )
            }
        }
    }

    fun dismissAdaptiveDetailSelection() {
        val current = adaptiveWorkspaceStore.snapshot().workspace
        bridge.log(
            "adaptive_detail_dismiss selection=${current?.selection?.identity?.key.orEmpty()} " +
                "presentation=${current?.presentation}"
        )
        if (current == null) return
        val selection = current.selection ?: return
        val request = RaftAdaptiveDetailCloseRequest(current.shellInstanceId, selection.identity.key, selection.generation)
        val next = adaptiveWorkspaceStore.update { it.backFromDetail(request) }
        releaseAdaptiveHandoffLease(selection.generation)
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(next.detailListSelectionId()))
    }

    /**
     * task #34: the task overlay's close button / scrim closes the whole detail, including any
     * thread → channel navigation made inside it (Back still unwinds one level at a time).
     */
    fun closeTasksDetailOverlay() {
        val selection = adaptiveWorkspaceStore.snapshot().workspace?.selection
        bridge.log("tasks_detail_overlay_close selection=${selection?.identity?.key.orEmpty()}")
        tasksDetailOverlayActive = false
        if (selection == null) return
        adaptiveWorkspaceStore.update { it.closeDetailCompletely() }
        releaseAdaptiveHandoffLease(selection.generation)
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
    }

    fun popMainRoute(): Boolean {
        if (createChannelDialogOpen) {
            createChannelDialogOpen = false
            createChannelError = null
            return true
        }
        if (homeState.serverSwitcherExpanded) {
            serverDrawerProgrammaticCloseFence?.invoke("back")
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
            return true
        }
        if (selectedTab == "members" && membersDetailSelection.value != null) {
            membersDetailSelection.value = null
            return true
        }
        // task #180 (web parity): Focus mode is one step of its own. Back leaves Focus and restores
        // the two columns; only the next Back closes the thread. Matches the web menu's model where
        // maximise is a reversible view state, not a new screen.
        if (!tasksDetailOverlayOpen && adaptiveWorkspaceStore.snapshot().sideThreadFocusActive()) {
            adaptiveWorkspaceStore.update { it.copy(sideThreadFocused = false) }
            return true
        }
        // task #36: an open side thread closes first; the main detail stays.
        if (!tasksDetailOverlayOpen && adaptiveWorkspaceStore.snapshot().backClosesSideThreadFirst(desktopModeOnLargeScreen)) {
            adaptiveWorkspaceStore.update { it.copy(sideThread = null) }
            return true
        }
        if (desktopModeOnLargeScreen && adaptiveWorkspaceStore.snapshot().hasVisibleDetailBackHistory()) {
            dismissAdaptiveDetailSelection()
            return true
        }
        if (
            desktopModeOnLargeScreen &&
            appShellRailSurfaceBackDismissesDetailFirst(
                selectedTab = selectedTab,
                railInlineSecondaryDestination = railInlineSecondaryDestination,
                selectionPresent =
                    adaptiveWorkspaceStore.snapshot().let { session ->
                        session.workspace?.selection != null &&
                            // The DMs tab does not show (so must not dismiss) a channel selection.
                            (
                                retainedAppShellSelectedTab(selectedTab) != "dms" ||
                                    appShellDmsTabShowsDetail(
                                        surfaceKind = session.launchPayload?.surfaceKind,
                                        parentSurfaceKind = session.launchPayload?.parentSurfaceKind,
                                    )
                            )
                    },
                tasksDetailOverlayOpen = tasksDetailOverlayOpen,
            )
        ) {
            dismissAdaptiveDetailSelection()
            return true
        }
        if (railInlineSecondaryDestination != null) {
            railInlineSecondaryDestination = null
            return true
        }
        if (selectedTab == "home" && selectedHomeDestination != null) {
            homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
            return true
        }
        // A server page (Members, Settings) goes back to the page the server panel opened it
        // from, or Home after a deep link, on the phone layout; focus returns to the server tab.
        appShellMoreChildBackTarget(
            selectedTab = selectedTab,
            navRailActive =
                raftAdaptiveNavRailActive(
                    desktopModeEnabled = desktopModeOnLargeScreen,
                    paneState = shellScope.adaptivePane
                ),
            origin = serverPageOrigin,
        )?.let { target ->
            selectAppShellTab(target, directChildSwitch = true)
            retainedAppShellTabs = retainedAppShellTabs + target
            serverTabFocusPending = true
            return true
        }
        if (selectedTab != "home") {
            selectAppShellTab("home")
            homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
            return true
        }
        if (
            desktopModeOnLargeScreen &&
            adaptiveWorkspaceState?.presentation == RaftAdaptiveWorkspacePresentation.DetailOnly
        ) {
            dismissAdaptiveDetailSelection()
            return true
        }
        return false
    }

    fun openAppShellActivityTab(source: String) {
        bridge.log("app_shell_activity_tab_open source=$source from=$selectedTab")
        railInlineSecondaryDestination = null
        if (shouldDismissHomeNotificationCenterForAppShellTabSelection(selectedTab, "activity")) {
            homeFocusLossDismissEpoch += 1
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
        }
        selectAppShellTab("activity", directChildSwitch = true)
        retainedAppShellTabs = retainedAppShellTabs + "activity"
    }

    fun openHomeSecondaryDestination(destination: String) {
        if (retainedHomeSecondaryDestination(destination) == HomeDestinationActivity) {
            // artin 2026-10-20: Activity is a bottom tab; the old Home 动态 destination (and any
            // caller still asking for it) lands on the kept-alive Activity tab, not a pushed page.
            openAppShellActivityTab(source = "home_secondary_destination")
            return
        }
        retainedHomeSecondaryDestination(destination)?.let { retainedDestination ->
            homeShellStore.dispatch(retainedDestination.toHomeSecondaryOpenIntent())
            pager.openHomeSecondaryPage(retainedDestination, threadTimeContext, navigationStoreScope)
        }
    }

    // stdrc 2026-10-21 (option B): Search is a phone tab. Opening search (the Home head's Search
    // button) selects it like a bar tap; the wide rail keeps the pushed search page.
    fun openSearchTab(source: String) {
        val navRail =
            raftAdaptiveNavRailActive(
                desktopModeEnabled = desktopModeOnLargeScreen,
                paneState = shellScope.adaptivePane
            )
        if (navRail) {
            bridge.log("app_shell_search_open source=$source tab=$selectedTab mode=rail_page")
            openHomeSecondaryDestination(HomeDestinationSearch)
            return
        }
        bridge.log("app_shell_search_open source=$source tab=$selectedTab mode=tab")
        if (shouldDismissHomeNotificationCenterForAppShellTabSelection(selectedTab, AppShellSearchTabKey)) {
            homeFocusLossDismissEpoch += 1
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
        }
        selectAppShellTab(AppShellSearchTabKey, directChildSwitch = true)
        retainedAppShellTabs = retainedAppShellTabs + AppShellSearchTabKey
    }

    // Nav spec §10.5: a conversation search's "Search all" popped the pages above the shell,
    // which took the request once (ComposeRaftAppShellPage). Show Search with that query: the
    // phone Search tab, or the rail's inline Search on the wide layout. The search page clears
    // [searchTabQueryRequest] once it has applied it.
    LaunchedEffect(searchHandoff?.nonce) {
        val request = searchHandoff ?: return@LaunchedEffect
        val navRail =
            raftAdaptiveNavRailActive(
                desktopModeEnabled = desktopModeOnLargeScreen,
                paneState = shellScope.adaptivePane
            )
        val target = appShellSearchHandoffTarget(navRail)
        bridge.log("app_shell_search_handoff nonce=${request.nonce} queryLength=${request.query.length} target=$target")
        searchTabQueryRequest = request
        when (target) {
            AppShellSearchHandoffTarget.SearchTab -> openSearchTab(source = "conversation_search_expand")
            AppShellSearchHandoffTarget.RailInlineSearch -> railInlineSecondaryDestination = AppShellRailSearchKey
        }
        onSearchHandoffApplied()
    }

    val handleAppShellBack by rememberUpdatedState {
        if (popMainRoute()) {
            bridge.log(
                "app_back_root result=main selectedTab=$selectedTab " +
                    "homeDestination=${selectedHomeDestination.orEmpty()}"
            )
        } else {
            bridge.logWarn(
                "app_back_root result=close_page selectedTab=$selectedTab " +
                    "homeDestination=${selectedHomeDestination.orEmpty()}"
            )
            bridge.closePage()
        }
    }

    RaftAppShellRootBackHandler(
        onLifecycleLog = { event ->
            bridge.log(
                "app_back_root_handler $event selectedTab=$selectedTab " +
                    "homeDestination=${selectedHomeDestination.orEmpty()}"
            )
        }
    ) { handleAppShellBack() }

    LaunchedEffect(tasksRoute, selectedTab, tasksSurfaceActive, authoritativeHomeStartAllowed) {
        if (tasksSurfaceActive && authoritativeHomeStartAllowed) {
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
            tasksRoute.start()
        }
    }
    LaunchedEffect(selectedTab, tasksSurfaceActive, readyServerId, appShellServerMembersStore, authoritativeHomeStartAllowed) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        val serverId = readyServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
            ?: return@LaunchedEffect
        if (tasksSurfaceActive) {
            appShellServerMembersStore.refreshMembers(serverId, DataRequestPriority.Visible)
        }
    }
    LaunchedEffect(selectedTab, tasksSurfaceActive, tasksState.loadState, tasksState.refreshing) {
        if (!tasksSurfaceActive) {
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
                val session = shellAuthStore.readSession()
                val serverId = session?.currentServerId
                if (serverId.isNullOrBlank()) {
                    createChannelMembers = emptyList()
                    createChannelMembersLoading = false
                    return@launch
                }
                createChannelMembers =
                    when (val result = appShellServerMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Visible)) {
                        is DataResult.Content -> appShellServerMembersStore.currentServerMembers(ServerId(serverId))
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

    fun openCreateJointChannelDialog() {
        createJointChannelDialogOpen = true
        createChannelMembers = emptyList()
        loadCreateChannelMembers()
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
        if (!trimmedName.isSubmittableChannelName() || createChannelSubmitting) {
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
                        selectAppShellTab("home")
                        retainedAppShellTabs = retainedAppShellTabs + "home"
                        homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
                        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(channel.threadId.value))
                        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.ChannelCreated(channel)))
                        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Refresh))
                        createChannelDialogOpen = false
                        createChannelError = null
                        bridge.toast(channelCreatedMessage)
                    }
                    is DataResult.Empty -> {
                        createChannelError = channelNotCreatedMessage
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

    fun handleRealtimeAuthRefreshRequest(eventData: JsonObject) {
        val requestedServerId = eventData.optString("serverId").takeIf { it.isNotBlank() } ?: return
        val reason = eventData.optString("reason").takeIf { it.isNotBlank() } ?: "reconnect"
        val currentSession =
            (authGateViewModel.state.value as? SharedAuthGateState.Ready)?.session
                ?: readySession
                ?: run {
                    bridge.realtimeAuthRefreshFinished(reason, success = false)
                    return
                }
        val currentServerId = selectedServerStore.selectedServerId.value
        if (!canRefreshRealtimeServer(currentSession, requestedServerId)) {
            bridge.log(
                "shared_realtime_auth_refresh_skip reason=$reason requestedServerId=$requestedServerId " +
                    "currentServerId=${currentServerId.orEmpty()} result=server_not_authenticated"
            )
            bridge.realtimeAuthRefreshFinished(reason, success = false)
            return
        }
        val previousFamilyId = realtimeConnectionFamilyId
        appShellCoroutineScope.launch {
            when (val result = authDataSource.refreshRealtimeAuth(reason)) {
                is SharedAuthResult.Success -> {
                    val refreshed = result.data
                    if (!canRefreshRealtimeServer(refreshed, requestedServerId) || refreshed.accessToken.isBlank()) {
                        bridge.log("shared_realtime_auth_refresh_skip reason=$reason result=server_mismatch")
                        bridge.realtimeAuthRefreshFinished(reason, success = false)
                        return@launch
                    }
                    authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(refreshed))
                    appShellAgentDetailStore.dispatch(SharedAgentDetailIntent.ResetAgentActivitySeq)
                    pendingRealtimeAuthRefreshReason = reason
                    pendingRealtimeAuthRefreshPreviousFamilyId = previousFamilyId
                    realtimeAuthorityConnectTick += 1
                }
                SharedAuthResult.Unauthenticated -> {
                    pendingRealtimeAuthRefreshReason = null
                    pendingRealtimeAuthRefreshPreviousFamilyId = null
                    bridge.realtimeAuthRefreshFinished(reason, success = false)
                    raftLogInfo(
                        "SlockSharedAuth",
                        "shared_realtime_auth_refresh_failed reason=$reason result=unauthenticated",
                        RaftPlatformLogHint.AuthCritical
                    )
                }
                is SharedAuthResult.Failure -> {
                    pendingRealtimeAuthRefreshReason = null
                    pendingRealtimeAuthRefreshPreviousFamilyId = null
                    bridge.realtimeAuthRefreshFinished(reason, success = false)
                    raftLogInfo(
                        "SlockSharedAuth",
                        "shared_realtime_auth_refresh_failed reason=$reason code=${result.code}",
                        RaftPlatformLogHint.AuthCritical
                    )
                }
            }
        }
    }
    val latestRealtimeAuthRefreshHandler by rememberUpdatedState<(JsonObject) -> Unit>(
        { eventData -> handleRealtimeAuthRefreshRequest(eventData) }
    )
    val latestResetAppShellSessionState by rememberUpdatedState<() -> Unit>({ resetAppShellSessionState() })
    DisposableEffect(
        appShellRealtimeDomainEventOwner,
        homeShellStore,
        bridge,
    ) {
        val sinks =
            RealtimeGuardedDomainEventSinks(
                applyHomeShell = { serverId, event ->
                    homeShellStore.acceptRealtimeEvent(serverId, event)
                },
                applyHomeUnreadProjection = { serverId, event ->
                    homeShellStore.acceptBackgroundServerUnreadEvent(serverId, event)
                },
                applyServerMembersRefresh = { serverId, event ->
                    if (event.scope == build.raft.app.model.RealtimeRefreshScope.Agents && event.reason == "agent:deleted") {
                        val agentId = event.subjectId?.takeIf { it.isNotBlank() }
                            ?: event.memberId?.value?.takeIf { it.isNotBlank() }
                        if (agentId != null) {
                            appShellServerMembersStore.markAgentDeleted(
                                serverId = build.raft.app.model.ServerId(serverId),
                                agentId = build.raft.app.model.MemberId(agentId)
                            )
                        }
                    }
                    // Server-membership/profile mutations must invalidate the in-session
                    // roster cache; otherwise updated fields (e.g. avatarUrl) stay stale
                    // until process restart. Agents/Machines scopes already refresh through
                    // their own projections, so only ServerMembers pays for a roster refetch.
                    if (
                        event.scope == build.raft.app.model.RealtimeRefreshScope.ServerMembers &&
                        (
                            event.reason == build.raft.app.data.realtime.RealtimeSocketEvents.ServerMemberLeft ||
                                event.reason == build.raft.app.data.realtime.RealtimeSocketEvents.ServerMemberRemoved
                            )
                    ) {
                        // Data-layer plan step 3 (Tenny rule 2): a member who left / was removed
                        // leaves the cached roster (memory + durable) at once, before the refetch.
                        event.memberId?.value?.takeIf { it.isNotBlank() }?.let { memberId ->
                            appShellServerMembersStore.removeMember(
                                serverId = build.raft.app.model.ServerId(serverId),
                                memberId = build.raft.app.model.MemberId(memberId)
                            )
                        }
                    }
                    if (event.scope == build.raft.app.model.RealtimeRefreshScope.ServerMembers) {
                        appShellCoroutineScope.launch {
                            appShellServerMembersStore.refreshMembers(
                                serverId = build.raft.app.model.ServerId(serverId),
                                priority = DataRequestPriority.Background,
                                reason = "realtime_${event.reason}"
                            )
                        }
                    }
                },
                applyAgentActivity = { event ->
                    // task #136: members-store applyPresence moved to the
                    // provider-installed presence sink (store lifetime, SSOT);
                    // only the page-scoped agent-detail dispatch stays here.
                    appShellAgentDetailStore.dispatch(
                        SharedAgentDetailIntent.ApplyAgentActivityUpdate(event)
                    )
                },
                requestMemberDetailRefresh = {
                    membersAgentDetailRefreshKey += 1
                },
                joinChannel = { channelId ->
                    realtimeEvents.joinChannel(channelId, latestReadySession?.currentServerId)
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
                log = raftLogger("SlockRealtimeDomain")
            )
        activeAppShellCanonicalFinalSinks = sinks
        appShellRealtimeDomainEventOwner.registerGuardedSinks(sinks)
        onDispose {
            if (activeAppShellCanonicalFinalSinks === sinks) {
                activeAppShellCanonicalFinalSinks = null
            }
            appShellRealtimeDomainEventOwner.unregisterGuardedSinks(sinks)
        }
    }

    DisposableEffect(hostNetworkEvents, networkBannerGate) {
        val callbackRef =
            hostNetworkEvents.addListener { signal ->
                appShellCoroutineScope.launch {
                    networkBannerGate.onHostStatus(
                        if (signal.online) {
                            RaftAppShellNetworkStatus.Online
                        } else {
                            RaftAppShellNetworkStatus.Offline
                        }
                    )
                }
            }
        onDispose { hostNetworkEvents.removeListener(callbackRef) }
    }

    DisposableEffect(
        realtimeEvents,
        homeStore,
        realtimeEventMapper,
        realtimeCallbackGate,
        authoritativeHomeStartAllowed,
    ) {
        var suppressedVisibleRefreshCount = 0
        // Events arrive as immutable kotlinx JsonObjects, so they are shared with the launched
        // coroutine as-is: no snapshot copy, no Kuikly JSONObject round trip.
        val onRealtimeEvent: (JsonObject) -> Unit = onRealtimeEvent@{ eventData ->
            if (!latestAuthoritativeHomeStartAllowed) return@onRealtimeEvent
            if (
                eventData.optString(RaftRealtimeNotifyContract.KeyAction) ==
                    RaftRealtimeNotifyContract.ActionPresenceTransportConnected
            ) {
                val serverId =
                    eventData
                        .optString(RaftRealtimeNotifyContract.KeyServerId)
                        .trim()
                        .takeIf { it.isNotBlank() }
                val transportEpoch =
                    eventData
                        .optLong(RaftRealtimeNotifyContract.KeyPresenceTransportEpoch, 0L)
                        .takeIf { it > 0L }
                val witness = eventData.realtimeConnectionWitnessOrNull()
                val authority = processRealtimeAuthorityFlow.value
                val shellServerId =
                    selectedServerStore.selectedServerId.value
                        ?: storeScope.initialServerId
                if (
                    serverId != null &&
                    transportEpoch != null &&
                    witness != null &&
                    witness == authority?.witness &&
                    authority?.grant(serverId) != null &&
                    shouldRouteRealtimeEventToShell(shellServerId, serverId)
                ) {
                    val nextCursor =
                        nextAppShellPresenceTransportCursor(
                            previous = appShellPresenceTransportCursors[serverId],
                            connectionFamilyId = witness.connectionFamilyId,
                            transportEpoch = transportEpoch
                        )
                    if (nextCursor != null) {
                        appShellPresenceTransportCursors[serverId] = nextCursor
                        // Native posts this signal synchronously before any
                        // event from the new socket. Reset here, before the
                        // per-event coroutine launch, so a low post-restart
                        // seq cannot race ahead of the rebaseline. The cursor
                        // rejects duplicate or delayed old-epoch signals.
                        appShellAgentDetailStore.dispatch(
                            SharedAgentDetailIntent.ResetAgentActivitySeq
                        )
                    }
                }
                return@onRealtimeEvent
            }
                val appearanceGenerationAtDelivery = latestVisibleAppearanceGeneration
                appShellCoroutineScope.launch {
                    if (
                        eventData.optString(RaftRealtimeNotifyContract.KeyAction) ==
                        RaftRealtimeNotifyContract.ActionAppLifecycle
                    ) {
                        val event = eventData.optString(RaftRealtimeNotifyContract.KeyLifecycleEvent)
                        val foregroundEpoch =
                            eventData
                                .optLong(RaftRealtimeNotifyContract.KeyForegroundEpoch, 0L)
                                .takeIf { it > 0L }
                        if (!appLifecycleEventTargetsShell(
                                eventShellInstanceId = eventData.optString(RaftRealtimeNotifyContract.KeyShellInstanceId),
                                routeShellInstanceId = storeScope.shellInstanceId
                            )
                        ) {
                            return@launch
                        }
                        if (eventData.optString(RaftRealtimeNotifyContract.KeyPageName) != RAFT_APP_SHELL_PAGE) {
                            return@launch
                        }
                        if (
                            event == RaftRealtimeNotifyContract.LifecycleHiddenPause ||
                            event == RaftRealtimeNotifyContract.LifecycleHidden ||
                            event == RaftRealtimeNotifyContract.LifecycleHiddenKeepAlive
                        ) {
                            visibleRefreshGate.onLifecycleHidden(
                                appearanceGeneration = appearanceGenerationAtDelivery,
                                foregroundEpoch = foregroundEpoch
                            )
                            return@launch
                        }
                        val visibleLifecycleEvent =
                            event == RaftRealtimeNotifyContract.LifecycleVisibleResume ||
                                event == RaftRealtimeNotifyContract.LifecycleVisibleShow
                        if (visibleLifecycleEvent) {
                            foregroundEpoch?.let(visibleRefreshGate::observeLifecycleForegroundEpoch)
                        }
                        if (visibleLifecycleEvent && !latestPageVisible) {
                            bridge.log(
                                "app_shell_visible_refresh_skip reason=app_lifecycle_$event " +
                                    "cause=page_not_visible tab=$latestSelectedTab " +
                                    "homeDestination=${latestSelectedHomeDestination.orEmpty()}"
                            )
                            suppressedVisibleRefreshCount++
                            return@launch
                        }
                        if (
                            appShellLifecycleCanRefreshVisibleRoute(latestPageVisible, event) &&
                            latestReadySession != null
                        ) {
                            val fgResumeMark = TimeSource.Monotonic.markNow()
                            val plan =
                                appShellForegroundResumePlan(
                                    pageVisible = latestPageVisible,
                                    selectedTab = latestSelectedTab,
                                    selectedHomeDestination = latestSelectedHomeDestination
                                )
                            val refreshedVisibleRoute =
                                if (plan?.refreshVisibleRoute == true) {
                                    refreshVisibleAppShellRoute(
                                        reason = "app_lifecycle_$event",
                                        signal = AppShellVisibleRefreshSignal.Lifecycle,
                                        appearanceGeneration = appearanceGenerationAtDelivery,
                                        foregroundEpoch = foregroundEpoch
                                    )
                                } else {
                                    false
                                }
                            if (refreshedVisibleRoute && plan?.refreshActivityUnreadSummary == true) {
                                latestReadySession
                                    ?.currentServerId
                                    ?.trim()
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { serverId ->
                                        sharedStores.activityUnreadSummaryStore.onForeground(
                                            serverId = ServerId(serverId),
                                            epoch = foregroundEpoch ?: 0L,
                                            reason = "app_shell_$event"
                                        )
                                    }
                            }
                            if (refreshedVisibleRoute && plan?.refreshServerMembers == true) {
                                sharedStores.foregroundReconciler.onForeground("app_shell_$event")
                            }
                            raftLogInfo(
                                "SlockForegroundPerf",
                                "fg_resume event=$event serverId=${eventData.optString(RaftRealtimeNotifyContract.KeyServerId)} epoch=${foregroundEpoch ?: 0L} routeRefreshed=$refreshedVisibleRoute suppressedVisibleRefresh=$suppressedVisibleRefreshCount elapsedMs=${fgResumeMark.elapsedNow().inWholeMilliseconds}"
                            )
                            suppressedVisibleRefreshCount = 0
                        }
                        return@launch
                    }
                    if (eventData.optString("action") == AppShellRealtimeAuthRefreshAction) {
                        latestRealtimeAuthRefreshHandler(eventData)
                        return@launch
                    }
                    val event =
                        decodeSharedShellRealtimeEvent(
                            eventData,
                            realtimeEventMapper,
                            raftLogger("SlockRealtimeDecode")
                        ) ?: return@launch
                    val serverId = resolveSharedShellRealtimeServerId(eventData, event, latestReadySession) ?: return@launch
                    val witness = eventData.realtimeConnectionWitnessOrNull() ?: return@launch
                    val grant = realtimeCallbackGate.resolveExistingGrant(witness, serverId) ?: return@launch
                    // Task #168: the connection that delivered this frame, when the host names it. The durable
                    // canary decides reconnects by it, so frame/signal order across coroutines does not matter.
                    val frameTransportEpoch =
                        eventData
                            .optLong(RaftRealtimeNotifyContract.KeyPresenceTransportEpoch, 0L)
                            .takeIf { it > 0L }
                    val activeSelectedServerId = selectedServerStore.selectedServerId.value
                    val processAccepted =
                        sharedStores.processRealtimeDomainEventOwner().acceptProcessMutation(
                            grant,
                            event,
                            source = "process_realtime",
                            presenceTransportEpoch = frameTransportEpoch
                        )
                    if (!processAccepted) return@launch
                    RealtimeCursorPolicy.appliedSeqForEvent(event)
                        .takeIf { appliedSeq -> appliedSeq > 0L }
                        ?.let { appliedSeq ->
                            bridge.realtimeCursorAdvance(
                                serverId = serverId,
                                appliedSeq = appliedSeq,
                                authorityScopeLeaseId = grant.scopeLeaseId,
                                authorityPrincipalId = grant.principalId,
                                authorityAuthGeneration = grant.authGeneration,
                                authorityConnectionFamilyId = grant.connectionFamilyId,
                            )
                        }
                    if (event is ServerMembershipRemoved && !selectedServerStore.isSelected(serverId)) {
                        bridge.realtimeDisconnect(
                            reason = GeneratedRealtimeContract.DisconnectReason.ServerMembershipRemoved,
                            serverId = serverId,
                            shellInstanceId = "process-realtime-host-owner",
                            explicitClose = true,
                        )
                        bridge.log("shared_realtime_server_membership_removed serverId=$serverId selected=false")
                        authGateViewModel.accept(SharedAuthGateIntent.Restore)
                        return@launch
                    }
                    if (event is ServerDeleted && !selectedServerStore.isSelected(serverId)) {
                        bridge.realtimeDisconnect(
                            reason = GeneratedRealtimeContract.DisconnectReason.ServerDeleted,
                            serverId = serverId,
                            shellInstanceId = "process-realtime-host-owner",
                            explicitClose = true,
                        )
                        bridge.log("shared_realtime_server_deleted serverId=$serverId selected=false")
                        authGateViewModel.accept(SharedAuthGateIntent.Restore)
                        return@launch
                    }
                    val shellGrant = realtimeAuthority?.grant(serverId) ?: return@launch
                    if (!shouldRouteRealtimeEventToShell(activeSelectedServerId, serverId)) {
                        if (shouldProjectBackgroundServerUnread(latestReadySession, activeSelectedServerId, serverId)) {
                            appShellRealtimeDomainEventOwner.acceptHomeUnreadProjection(
                                shellGrant,
                                event,
                                source = "app_shell_background_server",
                                transportEpoch = frameTransportEpoch,
                            )
                        } else {
                            bridge.log(
                                "shared_realtime_event_skip reason=server_mismatch " +
                                    "eventServerId=$serverId shellServerId=${activeSelectedServerId.orEmpty()}"
                            )
                        }
                        return@launch
                    }
                    if (event is ServerMembershipRemoved) {
                        applySelectedServerTerminalRealtimeTransition(
                            grant = grant,
                            eventServerId = event.serverId.value,
                            isSelected = { candidate -> selectedServerStore.isSelected(candidate) },
                            runIfCurrentGrant = sharedStores.realtimeAuthorityProvider::runIfCurrentGrant,
                            disconnectAndReset = {
                                raftLogWarn(
                                    "SlockRealtimeDomain",
                                    "shared_realtime_server_membership_removed serverId=${event.serverId.value}"
                                )
                                bridge.realtimeDisconnect(
                                    reason = "server_membership_removed",
                                    serverId = event.serverId.value,
                                    shellInstanceId = storeScope.shellInstanceId,
                                    explicitClose = true
                                )
                                latestResetAppShellSessionState()
                            },
                            clearSelectedServer = {
                                shellAuthStore.updateSelectedServer(serverId = null, serverSlug = null)
                            },
                            restoreAuthGate = {
                                authGateViewModel.accept(SharedAuthGateIntent.Restore)
                            },
                        )
                        return@launch
                    }
                    if (event is ServerDeleted) {
                        applySelectedServerTerminalRealtimeTransition(
                            grant = grant,
                            eventServerId = event.serverId.value,
                            isSelected = { candidate -> selectedServerStore.isSelected(candidate) },
                            runIfCurrentGrant = sharedStores.realtimeAuthorityProvider::runIfCurrentGrant,
                            disconnectAndReset = {
                                raftLogWarn(
                                    "SlockRealtimeDomain",
                                    "shared_realtime_server_deleted serverId=${event.serverId.value}"
                                )
                                bridge.realtimeDisconnect(
                                    reason = "server_deleted",
                                    serverId = event.serverId.value,
                                    shellInstanceId = storeScope.shellInstanceId,
                                    explicitClose = true
                                )
                                latestResetAppShellSessionState()
                            },
                            clearSelectedServer = {
                                shellAuthStore.updateSelectedServer(serverId = null, serverSlug = null)
                            },
                            restoreAuthGate = {
                                authGateViewModel.accept(SharedAuthGateIntent.Restore)
                            },
                        )
                        return@launch
                    }
                    val projected = appShellRealtimeDomainEventOwner.accept(
                        shellGrant,
                        event,
                        source = "app_shell",
                        transportEpoch = frameTransportEpoch,
                    )
                    if (!projected) return@launch
                    sharedStores.realtimeAuthorityProvider.runIfCurrentGrant(grant) {
                        if (event is RealtimeRefreshRequired && event.scope == RealtimeRefreshScope.Announcements) {
                            // task #226: announcement:new — refetch the latest active
                            // announcement so the modal pops live.
                            latestAnnouncementStore.refresh()
                        }
                    // task #459: `message:new` is a data-sync trigger only — the store
                    // accept above is its whole job. Attention comes from the
                    // server-qualified `notification:push` stream (server PR #4233):
                    // identity is typed on the payload, so there is nothing to defer.
                        if (event is NotificationPushReceived) {
                            if (!sharedStores.isActiveAppShellPager(pager.pagerId)) {
                                return@runIfCurrentGrant
                            }
                            if (latestForegroundMessageBannersEnabled) {
                                val banner =
                                    raftNotificationPushBannerOrNull(
                                    event = event,
                                    currentServerId = activeSelectedServerId,
                                    visibleConversationKey =
                                        appShellVisibleForegroundConversationKey(
                                            selectedTab = latestSelectedTab,
                                            selectedHomeDestination = latestSelectedHomeDestination,
                                            selectedConversationId = latestSelectedHomeConversationId
                                        ),
                                    visibleRouteIdentity = foregroundBannerStore.visibleRouteIdentity(),
                                    conversationDirectory = latestAppShellConversationDirectory,
                                    onSuppressed = { reason ->
                                        bridge.log(
                                            "notification_push_banner_suppressed reason=$reason " +
                                                "serverId=${event.serverId.value} messageId=${event.messageId.value} kind=${event.kind.name}"
                                        )
                                    }
                                    )
                                        ?.redactedForPushPreview(
                                            mode =
                                                PushPreviewMode.fromWire(pushPreviewModeStore.readPushPreviewModeSync())
                                                    ?: PushPreviewMode.Hidden,
                                            senderName = event.senderName,
                                            mentioned = event.mentioned == true,
                                            strings = latestPushPreviewBannerStrings
                                        )
                                banner?.let {
                                    bridge.log(
                                        "notification_push_banner_shown serverId=${event.serverId.value} " +
                                            "messageId=${event.messageId.value} kind=${event.kind.name}"
                                    )
                                    foregroundBannerStore.show(it)
                                }
                            } else {
                                foregroundBannerStore.dismiss()
                            }
                        }
                    }
                }
            }
        val callbackRef = realtimeEvents.addListener(onRealtimeEvent)
        // task #354: the canonical AppShell ingest also consumes the Android
        // raw-ingest topic — large raw server events are routed only there so
        // they are decoded exactly once. Other platforms never publish on it,
        // and page surfaces must not subscribe to it.
        val rawIngestRef = realtimeEvents.addRawIngestListener(onRealtimeEvent)
        onDispose {
            realtimeEvents.removeListener(callbackRef)
            realtimeEvents.removeListener(rawIngestRef)
        }
    }

    val navRailActive =
        raftAdaptiveNavRailActive(
            desktopModeEnabled = desktopModeOnLargeScreen,
            paneState = shellScope.adaptivePane
        )
    // stdrc 2026-10-21: Settings is the More tab's root on the phone bar and the pinned rail entry
    // on the wide layout; a selection made for the other layout moves to this layout's root.
    LaunchedEffect(selectedTab, navRailActive) {
        if (appShellSearchTabMovesToRailSearch(selectedTab, navRailActive)) {
            // The rail has no Search tab: a phone Search tab left open when the window widens
            // becomes the rail's inline Search over Home.
            bridge.log("app_shell_search_tab_to_rail from=$selectedTab")
            selectAppShellTab("home", directChildSwitch = true)
            railInlineSecondaryDestination = AppShellRailSearchKey
            return@LaunchedEffect
        }
        val root = appShellSettingsRootTab(selectedTab, navRailActive) ?: return@LaunchedEffect
        bridge.log("app_shell_settings_root from=$selectedTab to=$root navRail=$navRailActive")
        selectAppShellTab(root, directChildSwitch = true)
        retainedAppShellTabs = retainedAppShellTabs + root
    }
    val showAppShellBottomTabs =
        appShellBottomTabsVisible(
            selectedTab = selectedTab,
            selectedHomeDestination = selectedHomeDestination,
            navRailActive = navRailActive,
        ) && !navRailActive &&
            !appShellAdaptiveDetailCoversTabRoot(
                desktopModeOnLargeScreen = desktopModeOnLargeScreen,
                selectedTab = selectedTab,
                presentation = adaptiveWorkspaceState?.presentation,
            )
    val homeTabLabel = stringResource(Res.string.tab_home)
    val tasksTabLabel = stringResource(Res.string.tab_tasks)
    val membersTabLabel = stringResource(Res.string.tab_members)
    val settingsTabLabel = stringResource(Res.string.tab_settings)
    val dmsTabLabel = stringResource(Res.string.tab_dms)
    val activityTabLabel = stringResource(Res.string.tab_activity)
    val searchTabLabel = stringResource(Res.string.home_nav_search)
    val moreTabLabel = stringResource(Res.string.tab_more)
    // Activity tab badge = Activity unread only (the count the old Home 动态 row showed). The
    // notification center keeps its own Bell dot in the Home head (task #18).
    val activityTabUnreadCount = homeActivitySidebarUnreadCount(activityUnreadSummary.totalUnreadCount)
    // Computers attention (raft-design #620 "Attention dots"): the dot marks a (computer, state)
    // you have not seen; opening Computers sees it. Seen pairs persist per user + server.
    val computerAttentionSeenStore = remember {
        runCatching {
            build.raft.app.data.KeyValueRaftComputerAttentionSeenStore(build.raft.app.data.raftSharedPreferenceStore())
        }.getOrNull()
    }
    val computerAttentionUserId = readySession?.user?.id?.trim()?.takeIf(String::isNotEmpty)
    val computerAttentionServerId = readyServerId
    val currentComputerAttention = remember(homeState.notifications) {
        build.raft.app.data.raftComputerAttentionPairs(homeState.notifications.map { it.id })
    }
    var computerAttentionSeen by remember(computerAttentionSeenStore, computerAttentionUserId, computerAttentionServerId) {
        mutableStateOf(
            if (computerAttentionUserId != null && computerAttentionServerId != null) {
                computerAttentionSeenStore?.read(computerAttentionUserId, computerAttentionServerId).orEmpty()
            } else {
                emptySet()
            }
        )
    }
    val writeComputerAttentionSeen: (Set<String>) -> Unit = { next ->
        if (next != computerAttentionSeen) {
            computerAttentionSeen = next
            if (computerAttentionUserId != null && computerAttentionServerId != null) {
                computerAttentionSeenStore?.write(computerAttentionUserId, computerAttentionServerId, next)
            }
        }
    }
    // A computer observed leaving a state forgets it was seen there, so re-entering lights again.
    val previousComputerAttention = remember(computerAttentionUserId, computerAttentionServerId) {
        mutableStateOf<Set<String>?>(null)
    }
    LaunchedEffect(currentComputerAttention, computerAttentionUserId, computerAttentionServerId) {
        writeComputerAttentionSeen(
            build.raft.app.data.raftComputerAttentionObserve(
                seen = computerAttentionSeen,
                previous = previousComputerAttention.value,
                current = currentComputerAttention,
            )
        )
        previousComputerAttention.value = currentComputerAttention
    }
    // Every way into Computers (the More/Settings row, Settings > Computers on the wide rail, a
    // Home notice).
    val openComputersPage: () -> Unit = {
        writeComputerAttentionSeen(
            build.raft.app.data.raftComputerAttentionMarkSeen(computerAttentionSeen, currentComputerAttention)
        )
        pager.openSettingsComputersPage(storeScope)
    }
    // Something new in More / Settings (app update -> About; an offline/outdated machine not yet
    // seen -> Computers); it dots the More tab and the matching Settings rows from this one set,
    // so tab and rows light and clear together.
    val moreAttention = remember(homeState.notifications, computerAttentionSeen) {
        raftMoreAttention(homeState.notifications.map { it.id }, computerAttentionSeen)
    }
    // D1a: the current-server tab and its menu, from the Home switcher's own items.
    val serverMenuContent = homeState.content as? SharedHomeContentState.Content
    val serverMenuModel =
        serverMenuContent?.let { content ->
            remember(
                content.session,
                content.snapshot.server,
                homeState.serverUnreadCounts,
                serverActivityExpectedOwner,
                homeState.serverActivityUnread,
                moreAttention,
            ) {
                raftServerMenuModel(
                    servers =
                        sharedHomeSwitcherItems(
                            session = content.session,
                            selectedServer = content.snapshot.server,
                            serverUnreadCounts = homeState.serverUnreadCounts,
                            activityExpectedOwner = serverActivityExpectedOwner,
                            activityProjection = homeState.serverActivityUnread,
                        ),
                    currentServerId = content.snapshot.server.id.value,
                    currentServerName = content.snapshot.server.name,
                    moreAttention = moreAttention,
                )
            }
        }
    // The menu belongs to the bar or the rail it rose from: it closes when they go (a pushed page
    // hides the bar, the window crosses the rail breakpoint).
    LaunchedEffect(showAppShellBottomTabs, navRailActive) { serverMenuState.dismiss() }
    // artin 10-23: a server switch that ends without the server's Home (dropped load, error,
    // timeout) says so once (web ServerSwitcherMenu `switchFailed`); the list is already back.
    val serverSwitchFailedMessage = stringResource(Res.string.home_server_switch_failed)
    val serverSwitchFailuresShown = remember { longArrayOf(homeState.serverSwitchState.failureCount) }
    LaunchedEffect(homeState.serverSwitchState.failureCount) {
        val failures = homeState.serverSwitchState.failureCount
        if (failures > serverSwitchFailuresShown[0]) bridge.toast(serverSwitchFailedMessage)
        serverSwitchFailuresShown[0] = failures
    }
    // artin 10-23: the Home switcher dropdown and the server panel never show together; the one
    // opened last stays.
    val serverMenusOpenBefore = remember { RaftServerMenusOpen() }
    LaunchedEffect(serverMenuState.open, homeState.serverSwitcherExpanded) {
        val now = RaftServerMenusOpen(panel = serverMenuState.open, homeSwitcher = homeState.serverSwitcherExpanded)
        when (raftServerMenuToClose(before = serverMenusOpenBefore.copy(), now = now)) {
            RaftServerMenuSurface.Panel -> serverMenuState.dismiss()
            RaftServerMenuSurface.HomeSwitcher -> {
                serverDrawerProgrammaticCloseFence?.invoke("server_panel")
                homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
            }
            null -> Unit
        }
        serverMenusOpenBefore.panel = now.panel
        serverMenusOpenBefore.homeSwitcher = now.homeSwitcher
    }
    // A server page's origin lives only while a server page shows; a deep link to one finds none
    // and goes back to Home.
    LaunchedEffect(selectedTab) {
        if (retainedAppShellSelectedTab(selectedTab) !in AppShellMoreChildTabKeys) serverPageOrigin = null
    }
    // Computers is a page of its own: when the shell shows again after it, focus the server tab.
    LaunchedEffect(pageVisibleTick) {
        if (serverTabFocusOnPageReturn) {
            serverTabFocusOnPageReturn = false
            serverTabFocusPending = true
        }
    }
    val serverTabAccessibilityLabel =
        serverMenuModel?.let { stringResource(Res.string.server_tab_a11y_label, it.currentServer.name) }
    val appShellBottomTabItems = remember(
        selectedTab,
        homeTabLabel,
        tasksTabLabel,
        activityTabLabel,
        searchTabLabel,
        moreTabLabel,
        activityTabUnreadCount,
        moreAttention,
        serverMenuModel,
        serverTabAccessibilityLabel
    ) {
        appShellPhoneBottomTabs(
            selectedTab = selectedTab,
            homeLabel = homeTabLabel,
            activityLabel = activityTabLabel,
            searchLabel = searchTabLabel,
            tasksLabel = tasksTabLabel,
            moreLabel = moreTabLabel,
            activityUnreadCount = activityTabUnreadCount,
            moreAttention = moreAttention,
            serverTab = serverMenuModel,
            serverTabAccessibilityLabel = serverTabAccessibilityLabel
        )
    }
    // The wide rail keeps the web LeftRail entries (Members is a rail root there).
    val appShellRailTabItems = remember(
        selectedTab,
        homeTabLabel,
        tasksTabLabel,
        membersTabLabel,
        activityTabLabel,
        activityTabUnreadCount
    ) {
        appShellBottomTabs(
            selectedKey = retainedAppShellSelectedTab(selectedTab),
            orderedTabKeys = AppShellRailTabKeys,
            activityUnreadCount = activityTabUnreadCount,
            labelFor = { key ->
                when (key) {
                    "home" -> homeTabLabel
                    "activity" -> activityTabLabel
                    "tasks" -> tasksTabLabel
                    "members" -> membersTabLabel
                    else -> key.uppercase()
                }
            }
        )
    }
    val appShellBottomContentInset = shellScope.contentBottomInset(showAppShellBottomTabs)
    val homeReadyForChildPagePrewarm =
        authoritativeHomeStartAllowed &&
            readyServerId != null &&
            homeState.content is SharedHomeContentState.Content
    val computersPrewarmServerId =
        appShellComputersPrewarmServerId(
            readyServerId = readyServerId,
            sessionReady = readySession != null && authoritativeHomeStartAllowed,
            homeContentReady = homeState.content is SharedHomeContentState.Content
        )
    LaunchedEffect(
        selectedTab,
        pageVisibleTick,
        readyServerId,
        homeState.content,
        homeState.homeRealtimeDirty
    ) {
        prewarmAdmissionGate.invalidate("foreground_shell_state")
    }
    LaunchedEffect(
        homeReadyForChildPagePrewarm,
        childTabShellId,
        childPagePrewarmPolicy,
        authoritativeHomeStartAllowed,
        slackNavTabKeys,
    ) {
        if (!homeReadyForChildPagePrewarm || childPagePrewarmPolicy.maxControllers <= 0) {
            return@LaunchedEffect
        }
        // task #94: legacy prewarm targets (settings/tasks/members) are not child pages in
        // Slack-nav mode; prewarming them would mount native child hosts for tabs that have
        // no render slot. Slack-mode prewarm ordering is a follow-up.
        if (slackNavMode) {
            return@LaunchedEffect
        }
        while (prewarmedAppShellTabs.size < childPagePrewarmPolicy.maxControllers) {
            val tab =
                appShellChildPagePrewarmOrder(
                    activeChildTab = appShellActiveChildPagerTab(selectedTab, slackNavTabKeys),
                    retainedTabs = retainedAppShellTabs,
                    prewarmedTabs = prewarmedAppShellTabs
                ).firstOrNull() ?: return@LaunchedEffect
            val waitStart = TimeSource.Monotonic.markNow()
            val stableGeneration = awaitStablePrewarmGeneration(prewarmAdmissionGate)
            var committed = false
            withKuiklyIdleContext(pager.pagerId) {
                val lease = prewarmAdmissionGate.tryAcquire(stableGeneration)
                    ?: return@withKuiklyIdleContext
                try {
                    if (
                        tab != appShellActiveChildPagerTab(selectedTab, slackNavTabKeys) &&
                        tab !in retainedAppShellTabs &&
                        tab !in prewarmedAppShellTabs
                    ) {
                        committed = lease.commit {
                            prewarmedAppShellTabs = prewarmedAppShellTabs + tab
                        }
                    }
                } finally {
                    lease.release()
                }
            }
            if (committed) {
                bridge.log(
                    "app_shell_child_tab_idle_prewarm tab=$tab mode=${childPagePrewarmPolicy.mode} " +
                        "waitMs=${waitStart.elapsedNow().inWholeMilliseconds} selectedTab=$selectedTab " +
                        "retained=$retainedAppShellTabs prewarmed=$prewarmedAppShellTabs"
                )
            }
        }
    }
    // MEMBERS_COMPUTERS_IDLE_PREWARM_START
    LaunchedEffect(
        computersPrewarmServerId,
        readySession?.user?.id,
        readySession?.accessToken,
        appShellComputersStore,
        authoritativeHomeStartAllowed,
    ) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        appShellComputersStore.reconcileOwner()
        val serverId = computersPrewarmServerId ?: return@LaunchedEffect
        while (true) {
            val stableGeneration = awaitStablePrewarmGeneration(prewarmAdmissionGate)
            var admitted = false
            withKuiklyIdleContext(pager.pagerId) {
                val lease = prewarmAdmissionGate.tryAcquire(stableGeneration)
                    ?: return@withKuiklyIdleContext
                try {
                    admitted = lease.commit {
                        appShellComputersStore.dispatch(
                            SharedComputersIntent.EnsureComputerPicker(
                                serverId = serverId,
                                priority = DataRequestPriority.Background
                            )
                        )
                    }
                } finally {
                    lease.release()
                }
            }
            if (admitted) {
                bridge.log("app_shell_computers_idle_prewarm serverId=$serverId priority=Background")
                return@LaunchedEffect
            }
        }
    }
    // MEMBERS_COMPUTERS_IDLE_PREWARM_END

    val appShellHomeLabels = RaftHomeShellLabels.localized()
    val serverDrawerUserHeader = rememberRaftServerDrawerUserHeader(readySession)
    fun openServerDrawerHome(serverIdValue: String) {
        val normalizedServerId = serverIdValue.trim().takeIf { it.isNotBlank() } ?: return
        val session = latestReadySession
        if (session == null || session.servers.none { it.id == normalizedServerId }) {
            bridge.logWarn(
                "server_drawer_home_open_rejected serverId=$normalizedServerId reason=stale_projection"
            )
            return
        }
        selectAppShellTab("home")
        retainedAppShellTabs = retainedAppShellTabs + "home"
        homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SelectServer(normalizedServerId)))
    }

    fun openServerDrawerConversation(serverIdValue: String, conversationId: String) {
        val normalizedServerId = serverIdValue.trim().takeIf { it.isNotBlank() } ?: return
        val normalizedConversationId = conversationId.trim().takeIf { it.isNotBlank() } ?: return
        appShellCoroutineScope.launch {
            val serverId = ServerId(normalizedServerId)
            val drawerSection = homeShellStore.state.value.home.serverDrawer.sections[serverId]
            val conversation =
                drawerSection?.content?.snapshot?.let { it.channels + it.directMessages }
                    ?.firstOrNull { it.threadId.value == normalizedConversationId }
            val selectedSession = latestReadySession?.withAppShellSelectedServer(normalizedServerId)
            if (drawerSection == null || conversation == null || selectedSession == null) {
                bridge.logWarn(
                    "server_drawer_open_rejected serverId=$normalizedServerId " +
                        "conversationId=$normalizedConversationId reason=stale_projection"
                )
                bridge.toast(conversationUnavailableMessage)
                return@launch
            }
            if (selectedServerStore.selectedServerId.value != normalizedServerId) {
                shellAuthStore.updateSelectedServer(normalizedServerId, drawerSection.server.slug)
                authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(selectedSession))
                homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SelectServer(normalizedServerId)))
            }
            val targetStoreScope =
                RaftShellStoreScope.create(
                    shellInstanceId = storeScope.shellInstanceId,
                    initialServerId = normalizedServerId
                )
            val identity = conversation.toThreadLaunchConversationIdentity()
            val payload =
                buildStandaloneConversationThreadLaunchPayload(
                    conversation = identity,
                    timeContext = threadTimeContext,
                    alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                    compactConsecutiveMessages = compactConsecutiveMessages,
                            onWarn = bridge::logWarn
                ) ?: return@launch
            homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(identity.threadId.value))
            conversationForwardHistory.recordNewOpen(
                identity.toConversationForwardEntry(normalizedServerId)
            )
            bridge.setConversationForwardGestureEnabled(false)
            pager.openStandaloneThreadLaunchPayload(payload, targetStoreScope)
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
        }
    }

    suspend fun openExternalShareComposer(
        handoff: build.raft.app.data.ExternalShareComposerHandoff
    ): Boolean {
        val target = handoff.target
        val normalizedServerId = target.serverId.value.trim().takeIf { it.isNotBlank() }
        val identity = target.toExternalShareConversationIdentityOrNull()
        if (normalizedServerId == null || identity == null) {
            bridge.logWarn("external_share_composer_open_rejected reason=destination_identity_missing")
            bridge.toast(conversationUnavailableMessage)
            return false
        }
        val session = latestReadySession
        val selectedServer = session?.servers?.firstOrNull { it.id == normalizedServerId }
        val selectedSession = session?.withAppShellSelectedServer(normalizedServerId)
        if (
            session == null ||
            session.user.id != handoff.accountId ||
            selectedServer == null ||
            selectedSession == null ||
            normalizedServerId != handoff.serverId
        ) {
            bridge.logWarn(
                "external_share_composer_open_rejected serverId=$normalizedServerId " +
                    "conversationId=${identity.threadId.value} reason=authority_changed"
            )
            bridge.toast(conversationUnavailableMessage)
            return false
        }
        val payload =
            buildStandaloneConversationThreadLaunchPayload(
                conversation = identity,
                timeContext = threadTimeContext,
                alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                compactConsecutiveMessages = compactConsecutiveMessages,
                    onWarn = bridge::logWarn
            ) ?: run {
                bridge.toast(conversationUnavailableMessage)
                return false
            }
        val attachments =
            handoff.envelope.items.mapIndexed { index, item ->
                SharedThreadComposerAttachment(
                    id = "external-share-${handoff.envelope.token}-$index",
                    fileName = item.displayName,
                    sizeBytes = item.sizeBytes,
                    mimeType = item.mimeType.takeIf(String::isNotBlank),
                    previewUri = item.localRef,
                    status = "queued",
                    fileRef = item.localRef,
                    source = "external_share",
                    externalShareToken = handoff.envelope.token,
                    externalShareAccountId = handoff.accountId,
                    externalShareServerId = handoff.serverId
                )
            }
        val stageResult =
            composerDraftPersistSlice.stageExternalShareDraft(
                draftKey = payload.threadComposerDraftKey(),
                text = handoff.envelope.externalShareComposerText(),
                attachments = attachments,
                source =
                    SharedThreadComposerExternalShareSource(
                        token = handoff.envelope.token,
                        accountId = handoff.accountId,
                        serverId = handoff.serverId
                    )
            )
        if (stageResult !is ExternalShareComposerDraftStageResult.Staged) {
            val code = (stageResult as ExternalShareComposerDraftStageResult.Rejected).code
            bridge.logWarn("external_share_composer_stage_rejected code=$code")
            bridge.toast(conversationUnavailableMessage)
            return false
        }
        if (selectedServerStore.selectedServerId.value != normalizedServerId) {
            shellAuthStore.updateSelectedServer(normalizedServerId, selectedServer.slug)
            authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(selectedSession))
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SelectServer(normalizedServerId)))
        }
        selectAppShellTab("home")
        retainedAppShellTabs = retainedAppShellTabs + "home"
        homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(identity.threadId.value))
        val targetStoreScope =
            RaftShellStoreScope.create(
                shellInstanceId = storeScope.shellInstanceId,
                initialServerId = normalizedServerId
            )
        pager.openStandaloneThreadLaunchPayload(payload, targetStoreScope)
        bridge.log(
            "external_share_composer_opened serverId=$normalizedServerId " +
                "threadId=${identity.threadId.value} kind=${identity.kind.name} " +
                "attachments=${attachments.size} upload=0 send=0"
        )
        return true
    }

    val onAppShellTabSelected: (String) -> Unit = onBottomTabSelected@{
                prewarmAdmissionGate.invalidate("bottom_tab_tap:$it")
                val nextTab = retainedAppShellSelectedTab(it)
                when (appShellBottomTabTapAction(selectedTab = selectedTab, tappedTab = nextTab)) {
                    AppShellBottomTabTapAction.ScrollCurrentToTop -> {
                        tabScrollToTopRequests = tabScrollToTopRequests.request(nextTab)
                        return@onBottomTabSelected
                    }
                    AppShellBottomTabTapAction.FocusSearchField -> {
                        searchTabFocusInputRequest += 1
                        return@onBottomTabSelected
                    }
                    AppShellBottomTabTapAction.Ignore -> return@onBottomTabSelected
                    AppShellBottomTabTapAction.Select -> Unit
                }
                if (!shouldHandleAppShellTabSelection(selectedTab, nextTab)) return@onBottomTabSelected
                // Selecting a real rail tab dismisses an inline secondary destination
                // (Search) — its middle-pane slot goes back to the tab's own content.
                railInlineSecondaryDestination = null
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
                if (shouldDismissHomeNotificationCenterForAppShellTabSelection(selectedTab, nextTab)) {
                    homeFocusLossDismissEpoch += 1
                    serverDrawerProgrammaticCloseFence?.invoke("bottom_tab")
                    homeShellStore.dispatch(
                        SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher)
                    )
                }
                selectAppShellTab(nextTab, directChildSwitch = true)
                if (isRetainedAppShellTab(nextTab)) {
                    retainedAppShellTabs = retainedAppShellTabs + nextTab
                }
                if (nextTab == "home") {
                    homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
                }
            }

    // D1a: picking a server in the server menu switches like the Home switcher does (session
    // projection + SelectServer) and keeps the tab you are on.
    fun switchServerFromServerMenu(serverId: String) {
        serverMenuState.dismiss()
        val current = serverMenuModel?.currentServer?.id.orEmpty()
        val plan = appShellServerMenuSwitchPlan(current, serverId, selectedTab) ?: return
        val homeServerId = appShellHomeListServerId(renderedServerId = renderedHomeServerId, readyServerId = readyServerId)
        pendingHomeServerSwitchId = plan.serverId.takeIf { it != homeServerId }
        observedHomeServerSwitchId = null
        bridge.performanceMarker(
            phase = "home_server_switch_select",
            routeName = "kuikly_home",
            event = "instant",
            metadata = mapOf("from" to current, "to" to plan.serverId, "source" to "server_menu", "tab" to plan.tab)
        )
        readySession
            ?.withAppShellSelectedServer(plan.serverId)
            ?.let { session -> authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(session)) }
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SelectServer(plan.serverId)))
        if (selectedTab != plan.tab) selectAppShellTab(plan.tab, directChildSwitch = true)
        bridge.log("app_shell_server_menu_switch to=${plan.serverId} tab=${plan.tab}")
    }

    // D1a: the server panel's pages open the pages the More rows opened, as pushed pages that
    // belong to no tab; Back returns to the page that showed when the panel opened them.
    fun openServerMenuEntry(entry: RaftServerMenuEntry) {
        serverMenuState.dismiss()
        prewarmAdmissionGate.invalidate("server_menu_open:${entry.key}")
        bridge.log("app_shell_server_menu_open row=${entry.key} from=$selectedTab")
        if (!navRailActive) serverPageOrigin = appShellServerPageOrigin(selectedTab, serverPageOrigin)
        when (entry) {
            RaftServerMenuEntry.Members -> {
                RaftProductEvents.navEntrySelected(
                    destination = RaftNavDestination.Members,
                    surface = RaftNavSurface.MobileMore,
                    wasActive = retainedAppShellSelectedTab(selectedTab) == "members",
                )
                selectAppShellTab("members", directChildSwitch = true)
                retainedAppShellTabs = retainedAppShellTabs + "members"
            }
            RaftServerMenuEntry.Computers -> {
                RaftProductEvents.navEntrySelected(
                    destination = RaftNavDestination.Computers,
                    surface = RaftNavSurface.MobileMore,
                    wasActive = false,
                )
                if (!navRailActive) serverTabFocusOnPageReturn = true
                openComputersPage()
            }
            RaftServerMenuEntry.Settings -> {
                // On the phone Settings is the pushed page under the `more` key; the pinned rail
                // entry when wide.
                val settingsKey = if (navRailActive) "settings" else AppShellServerTabKey
                RaftProductEvents.navEntrySelected(
                    destination = RaftNavDestination.Settings,
                    surface = RaftNavSurface.MobileMore,
                    wasActive = retainedAppShellSelectedTab(selectedTab) == settingsKey,
                )
                selectAppShellTab(settingsKey, directChildSwitch = true)
                retainedAppShellTabs = retainedAppShellTabs + settingsKey
            }
        }
    }

    fun onServerMenuPick(pick: RaftServerMenuPick) {
        when (pick) {
            // A held press let go over a server while a switch is pending: the list takes none.
            is RaftServerMenuPick.Server ->
                if (!homeServerSwitchListPending(homeState.serverSwitchState.pendingServerId)) {
                    switchServerFromServerMenu(pick.serverId)
                }
            is RaftServerMenuPick.Page -> openServerMenuEntry(pick.entry)
        }
    }

    // The server panel: over the content on the phone (above its bar), over the whole shell under
    // the wide top bar's workspace button ([topBar]).
    val serverMenuOverlay: @Composable (topBar: Boolean) -> Unit = { topBar ->
        if (serverMenuState.open && serverMenuModel != null && serverMenuContent != null) {
            RaftAppServerMenuOverlay(
                model = serverMenuModel,
                state = serverMenuState,
                anchor = if (topBar) RaftServerMenuAnchor.TopBar else RaftServerMenuAnchor.BottomBar,
                entries =
                    raftServerMenuEntriesFor(
                        serverMenuModel.entries,
                        wide = navRailActive,
                        guest = serverMenuContent.snapshot.server.role.equals("guest", ignoreCase = true),
                    ),
                onPick = ::onServerMenuPick,
                switchPending = homeServerSwitchListPending(homeState.serverSwitchState.pendingServerId),
                onServerOrderChange = { order ->
                    bridge.log("app_shell_server_menu_reorder count=${order.size}")
                    homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.ReorderServers(order)))
                },
                prefersChineseCommunity = localePreferenceStore.readLocaleSync() == AppLocale.SimplifiedChinese,
                onCreateServerClick = {
                    pager.openAuthChooseServerPage(
                        storeScope = navigationStoreScope,
                        documentShell = pager.pageData.params.optBoolean(RAFT_DOCUMENT_SHELL, false)
                    )
                },
                onInviteHumanClick =
                    homeServerSwitcherInviteClick(
                        serverMenuContent.snapshot.server,
                        { serverId -> serverSwitcherInviteServerId = serverId }
                    ) { serverMenuState.dismiss() },
                topInset = if (topBar) raftAppShellTopBarMenuTopInset(shellScope.topSafeArea) else shellScope.topSafeArea,
                screenHeight =
                    shellScope.adaptivePane
                        .takeIf { it.heightPx > 0 && it.density > 0f }
                        ?.let { (it.heightPx / it.density).dp },
                bottomInset =
                    when {
                        navRailActive -> shellScope.bottomSafeArea
                        // The iOS system bar floats over the content: clear its band.
                        shellScope.bottomTabBarOverlaysContent -> shellScope.bottomTabBarContentInset - RaftAppSpacing.Lg
                        else -> 0.dp
                    },
            )
        }
    }

    // artin task #8: the wide top bar's bell, and the sign to the list panes under it that they
    // have no head of their own.
    val appShellTopBar = remember { RaftAppShellTopBarState() }
    val mainRouteSurface: @Composable () -> Unit = {
        CompositionLocalProvider(LocalRaftAppShellTopBar provides appShellTopBar.takeIf { navRailActive }) {
        RaftAppShellSurface(
            shellScope = shellScope,
            bottomTabs = appShellBottomTabItems,
            bottomServerTab =
                serverMenuModel?.let { model ->
                    { tabModifier ->
                        RaftAppServerTab(
                            model = model,
                            state = serverMenuState,
                            onPick = ::onServerMenuPick,
                            modifier = tabModifier,
                            requestFocus = serverTabFocusPending,
                            onFocusRequested = { serverTabFocusPending = false },
                        )
                    }
                },
            onBottomTabSelected = { key ->
                if (key == AppShellServerTabKey && serverMenuModel != null) {
                    // The server tab opens the server panel, not a page (also the iOS system bar's tap).
                    bridge.log("app_shell_server_menu_toggle open=${!serverMenuState.open}")
                    serverMenuState.toggle()
                    return@RaftAppShellSurface
                }
                serverMenuState.dismiss()
                RaftProductEvents.navEntrySelected(
                    destination = appShellNavDestination(key),
                    surface = RaftNavSurface.MobileTabBar,
                    wasActive = appShellPhoneBottomBarSelectedKey(selectedTab) == key,
                )
                onAppShellTabSelected(key)
            },
            networkStatus = hostNetworkStatus,
            showBottomTabs = showAppShellBottomTabs,
            // artin 2026-10-10 (Hands 79db9fe6): the cached Home is usable at once while the
            // session and server list refresh in the background; a failed restore or an expired
            // session still goes to its prompt / sign-in. No blocking overlay.
            interactionLocked = false,
            modalHostState = appShellModalHostState,
            leadingNavigation =
                if (navRailActive) {
                    // task #31: Web LeftRail parity — icon-only tabs, settings pinned to the bottom.
                    // artin task #8: the top bar holds the server and Search, so the rail has
                    // neither (web LeftRail with the top bar).
                    {
                        val railBottomItems =
                            remember(settingsTabLabel, selectedTab) {
                                listOf(
                                    RaftBottomTabItemPayload(
                                        key = "settings",
                                        label = settingsTabLabel,
                                        iconName = RaftLucideIconName.Settings,
                                        selected = retainedAppShellSelectedTab(selectedTab) == "settings",
                                    )
                                )
                            }
                        val railItems = appShellRailTabItems.orderedLikePhoneBar { it.key }
                        RaftNavigationRailTabBar(
                            items = railItems,
                            onSelect = { key ->
                                RaftProductEvents.navEntrySelected(
                                    destination = appShellNavDestination(key),
                                    surface = RaftNavSurface.Rail,
                                    wasActive =
                                        railInlineSecondaryDestination == null &&
                                            retainedAppShellSelectedTab(selectedTab) == key,
                                )
                                // Tapping a real rail item dismisses the inline secondary destination
                                // even when the underlying tab is already selected (Activity -> Search
                                // -> Activity must land back on Activity).
                                railInlineSecondaryDestination = null
                                onAppShellTabSelected(key)
                            },
                            modifier = Modifier.fillMaxHeight(),
                            // The server tab at the foot is the phone's; the top bar holds the server.
                            bottomItems = railBottomItems,
                            onBottomItemSelect = { key ->
                                RaftProductEvents.navEntrySelected(
                                    destination = appShellNavDestination(key),
                                    surface = RaftNavSurface.Rail,
                                    wasActive = retainedAppShellSelectedTab(selectedTab) == key,
                                )
                                onAppShellTabSelected(key)
                            },
                            // The top bar above the rail clears the status bar.
                            topSafeArea = 0.dp,
                            bottomSafeArea = shellScope.bottomSafeArea
                        )
                    }
                } else {
                    null
                },
            topNavigation =
                if (navRailActive) {
                    {
                        RaftAppShellTopBar(
                            currentServer = serverMenuModel?.currentServer,
                            servers = serverMenuModel?.servers.orEmpty(),
                            serverMenuOpen = serverMenuState.open,
                            onWorkspaceClick = {
                                appShellTopBar.bellOpen.value = false
                                bridge.log("app_shell_server_menu_toggle open=${!serverMenuState.open} from=top_bar")
                                serverMenuState.toggle()
                            },
                            onServerPick = { serverId ->
                                bridge.log("app_shell_top_bar_server_pick to=$serverId")
                                switchServerFromServerMenu(serverId)
                            },
                            searchLabel = searchTabLabel,
                            onSearchClick = {
                                // The rail's old Search entry: the inline Search over the current tab.
                                serverMenuState.dismiss()
                                appShellTopBar.bellOpen.value = false
                                RaftProductEvents.navEntrySelected(
                                    destination = appShellNavDestination(AppShellRailSearchKey),
                                    surface = RaftNavSurface.TopBar,
                                    wasActive = railInlineSecondaryDestination == AppShellRailSearchKey,
                                )
                                railInlineSecondaryDestination = AppShellRailSearchKey
                                bridge.log("app_shell_rail_secondary_open destination=search from=top_bar")
                            },
                            windowWidth =
                                shellScope.adaptivePane
                                    .takeIf { it.widthPx > 0 && it.density > 0f }
                                    ?.let { (it.widthPx / it.density).dp } ?: 0.dp,
                            topSafeArea = shellScope.topSafeArea,
                            bell = appShellTopBar,
                            onBellToggle = { serverMenuState.dismiss() },
                        )
                    }
                } else {
                    null
                },
            // The top bar's server panel opens under its workspace button, and the bell's popover
            // under the bell, over the rail.
            shellOverlay =
                if (navRailActive) {
                    {
                        serverMenuOverlay(true)
                        RaftAppTopBarNotificationOverlay(appShellTopBar, raftAppShellTopBarMenuTopInset(shellScope.topSafeArea))
                    }
                } else {
                    null
                },
        ) {
                    // The wide top bar clears the status bar: the pages under it start at its seam.
                    @Suppress("NAME_SHADOWING")
                    val shellScope = if (navRailActive) shellScope.copy(topSafeArea = 0.dp) else shellScope
                    if (!navRailActive && showAppShellBottomTabs) serverMenuOverlay(false)
                    serverSwitcherInviteServerId?.let { inviteServerId ->
                        val inviteMembersDataSource =
                            remember(memberRepository, settingsRepository, appShellServerMembersStore) {
                                SharedMembersDataSource(
                                    memberRepository = memberRepository,
                                    settingsRepository = settingsRepository,
                                    serverMembersStore = appShellServerMembersStore
                                )
                            }
                        SharedServerInviteHumanDialogHost(
                            serverId = inviteServerId,
                            membersDataSource = inviteMembersDataSource,
                            loadWebBaseUrl = {
                                (settingsCapabilities.loadDeveloper() as? DataResult.Content)?.data?.webUrl
                            },
                            canOfferGuest =
                                sharedStores.serverFeatureFlags.isEnabled(
                                    ServerId(inviteServerId),
                                    InviteHumanGuestFeatureFlagKey
                                ),
                            copyToPasteboard = bridge::copyToPasteboard,
                            onDismiss = { serverSwitcherInviteServerId = null }
                        )
                    }
                    // task #180: SHELL-RENDERED MENUS. A Kuikly @Page child (thread column, detail
                    // pane) is its own composition, so a Modal opened inside it is clipped to that
                    // pane -- device evidence showed nothing drawn outside the window's left 400px
                    // strip could ever appear, whatever coordinates were passed. Pages therefore ASK
                    // the shell to draw, and the shell (this composition, spanning the window)
                    // renders the menu. Same host pattern as the rail server switcher above.
                    shellOverlayMenu?.let { open ->
                        // task #180 diagnostics (Argus): point 3 of 3 -- the host is composing.
                        raftTraceToPlatformLog(
                            "SlockShellOverlay",
                            "shell_host_compose requestId=${open.request.requestId} " +
                                "anchor=${open.request.anchor} items=${open.request.items.size} " +
                                "content=${shellOverlayContent != null}"
                        )
                        val customContent = shellOverlayContent?.takeIf {
                            it.requestId == open.request.requestId
                        }
                        val dismissShellOverlay = {
                            eventBus.post(
                                RAFT_SHELL_OVERLAY_RESULT_EVENT,
                                RaftShellOverlayResult(
                                    requestId = open.request.requestId,
                                    dismissed = true
                                ).toJson()
                            )
                            shellOverlayContent = null
                            shellOverlayMenu = null
                        }
                        if (customContent != null) {
                            // The shell is the full-window composition. Keep the exact child menu
                            // composable (including its reaction atlas and group dividers), but
                            // mount it in this window-level Modal surface rather than the 400dp
                            // child-page surface.
                            RaftModalAnchoredPopover(
                                anchor = RaftPopoverAnchor.forRootOffset(open.request.anchor),
                                contentSize = customContent.contentSize,
                                onDismiss = dismissShellOverlay,
                                viewportGap = customContent.viewportGap,
                                horizontalOffset = customContent.horizontalOffset,
                                verticalOffset = customContent.verticalOffset,
                                horizontalAutoFlip = true,
                                verticalPlacement = RaftFloatingVerticalPlacement.Auto
                            ) {
                                customContent.content { id ->
                                    customContent.onSelected(id)
                                    shellOverlayContent = null
                                    shellOverlayMenu = null
                                }
                            }
                        } else {
                            RaftBrutalDropdownMenu(
                                // The anchor arrives already resolved in WINDOW space: the page owns the
                                // conversion, and re-deriving it here is what produced four rounds of
                                // wrong positions.
                                anchor = RaftPopoverAnchor.forRootOffset(open.request.anchor),
                                items =
                                    open.request.items.map { item ->
                                        RaftBrutalDropdownMenuItem(
                                            id = item.id,
                                            label = item.label,
                                            enabled = item.enabled
                                        )
                                    },
                                onSelect = { id ->
                                    eventBus.post(
                                        RAFT_SHELL_OVERLAY_RESULT_EVENT,
                                        RaftShellOverlayResult(
                                            requestId = open.request.requestId,
                                            selectedId = id
                                        ).toJson()
                                    )
                                    shellOverlayMenu = null
                                },
                                onDismiss = dismissShellOverlay
                            )
                        }
                    }
                    // task #34: the shared workspace detail child, hosted by the list + detail split
                    // (Home, Activity, DMs, Search) and by the Tasks board overlay.
                    // task #36: the main detail plus, when it fits, the reply-thread side column.
                    // [allowSideColumn] is false on hosts that cannot show one (the task overlay);
                    // the active host reports its measured width for the column decision.
                    val adaptiveDetailPane: @Composable (Modifier, Boolean, Boolean, Boolean) -> Unit = { paneModifier, active, detailAccepted, allowSideColumn ->
                        val density = LocalDensity.current
                        val sideThread: AdaptiveSideThread? =
                            adaptiveWorkspaceSession.visibleSideThread()?.takeIf { allowSideColumn && detailAccepted }
                        val showSideColumn =
                            sideThread != null &&
                                (adaptiveWorkspaceSession.sideThreadColumnAvailable(desktopModeOnLargeScreen) ||
                                    listPaneResizeDefersFold)
                        // task #180: web parity (#9172/#9335). Focus mode lets the side thread take the
                        // whole row. It only relaxes layout -- it never creates a column the window
                        // cannot host, so it is gated on the same availability the column uses.
                        val sideThreadFocusActive = adaptiveWorkspaceSession.sideThreadFocusActive()
                        LaunchedEffect(active, allowSideColumn) {
                            if (active && !allowSideColumn) {
                                adaptiveWorkspaceStore.update {
                                    if (it.detailPaneWidthDp == null) it else it.copy(detailPaneWidthDp = null)
                                }
                            }
                        }
                        Row(
                            modifier =
                                paneModifier
                                    .fillMaxSize()
                                    .onSizeChanged { size ->
                                        if (!active || !allowSideColumn) return@onSizeChanged
                                        val widthDp = with(density) { size.width.toDp().value }
                                        adaptiveWorkspaceStore.update {
                                            if (it.detailPaneWidthDp == widthDp) it else it.copy(detailPaneWidthDp = widthDp)
                                        }
                                    }
                        ) {
                        // task #180: in Focus mode the thread owns the row and the parent channel/DM
                        // gets no space. It is collapsed by WIDTH, never by weight -- `weight(0f)` is
                        // illegal in Compose and threw IllegalArgumentException "invalid weight 0.0"
                        // the moment Focus was entered. See raftParentPaneLayout.
                        val paneLayout = raftParentPaneLayout(focused = sideThreadFocusActive)
                        val modifier =
                            when {
                                // Collapsed by width; a focused pane deliberately takes NO weight.
                                paneLayout.widthDp != null ->
                                    Modifier.width(paneLayout.widthDp.dp).fillMaxHeight()
                                paneLayout.weight != null ->
                                    Modifier.weight(paneLayout.weight).fillMaxHeight()
                                else -> Modifier.fillMaxHeight()
                            }
                        val workspace = adaptiveWorkspaceState
                        // task #33: a surface may decline the shared selection (the DMs tab shows only
                        // DMs); a declined selection renders the empty placeholder, never its composer.
                        val selection = workspace?.selection?.takeIf { detailAccepted }
                        val detailPayload = adaptiveDetailLaunchPayload?.takeIf { detailAccepted }
                        val childActive =
                            active && workspace?.presentation != RaftAdaptiveWorkspacePresentation.Inactive
                        val adaptiveEmptyLabel = stringResource(Res.string.home_adaptive_select_conversation)
                        val detailPageData =
                            remember(
                                detailPayload,
                                selection,
                                navigationStoreScope.shellInstanceId,
                                navigationStoreScope.initialServerId,
                            ) {
                                if (detailPayload == null || selection == null) {
                                    null
                                } else {
                                    threadLaunchPageData(detailPayload)
                                        .withRaftShellStoreScope(navigationStoreScope)
                                        .put(RAFT_ADAPTIVE_DETAIL_SHELL_ID, workspace.shellInstanceId)
                                        .put(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY, selection.identity.key)
                                        .put(RAFT_ADAPTIVE_DETAIL_GENERATION, selection.generation)
                                }
                            }
                        // Bug A (11500008): hand-off point from the shell selection to the detail page.
                        // Every detail_mount must be followed by the detail's own effect_enter for the
                        // same identity/generation; a mount with no matching effect_enter means the
                        // detail page kept rendering an older selection.
                        LaunchedEffect(active, selection?.identity?.key, selection?.generation) {
                            val mounted = selection?.takeIf { active && detailPageData != null } ?: return@LaunchedEffect
                            postShellAdaptiveTrace(
                                hop = "detail_mount",
                                identityKey = mounted.identity.key,
                                generation = mounted.generation,
                            )
                        }
                            if (detailPageData == null) {
                                // The wide detail with nothing open: the shared empty state (icon,
                                // muted title) on the pane's surface, not a bare line of text.
                                RaftEmptyState(
                                    title = adaptiveEmptyLabel,
                                    modifier = modifier.fillMaxSize(),
                                    iconName = RaftLucideIconName.MessageSquare,
                                )
                            } else {
                                RaftChildPage(
                                    pageName = THREAD_PAGE,
                                    pageData = detailPageData,
                                    modifier = modifier.fillMaxSize(),
                                    active = childActive,
                                    onLoadSuccess = {
                                        selection?.generation?.let(::releaseAdaptiveHandoffLease)
                                    },
                                    onLoadFailure = {
                                        selection?.generation?.let(::releaseAdaptiveHandoffLease)
                                    },
                                )
                            }
                        if (showSideColumn && sideThread != null && workspace != null) {
                            // task #180: the page data key is deliberately independent of Focus mode.
                            // Toggling re-lays-out the same child page instead of recreating it, which is
                            // what keeps the reader's scroll position across Focus <-> Standard.
                            val sidePageData =
                                remember(sideThread, navigationStoreScope.shellInstanceId, navigationStoreScope.initialServerId) {
                                    threadLaunchPageData(sideThread.payload)
                                        .withRaftShellStoreScope(navigationStoreScope)
                                        .put(RAFT_ADAPTIVE_DETAIL_SHELL_ID, workspace.shellInstanceId)
                                        .put(RAFT_ADAPTIVE_DETAIL_IDENTITY_KEY, sideThread.identity.key)
                                        .put(RAFT_ADAPTIVE_DETAIL_GENERATION, sideThread.generation)
                                }
                            if (!sideThreadFocusActive) {
                                Spacer(
                                    modifier =
                                        Modifier
                                            .width(RAFT_SIDE_THREAD_DIVIDER_DP.dp)
                                            .fillMaxHeight()
                                            .background(RaftColor.BorderSeam), // raft-ui: one line-seam between regions, as the rail's
                                )
                            }
                            RaftChildPage(
                                pageName = THREAD_PAGE,
                                pageData = sidePageData,
                                modifier =
                                    if (sideThreadFocusActive) {
                                        Modifier.weight(1f).fillMaxHeight()
                                    } else {
                                        Modifier.width(RAFT_SIDE_THREAD_COLUMN_DP.dp).fillMaxHeight()
                                    },
                                active = childActive,
                            )
                        }
                        }
                    }
                    val tasksTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        PerformanceSceneEffect(bridge, "kuikly_tasks", active = active)
                        // Foreground is the shell's visible-route refresh; a socket reconnect re-reads missed task pushes.
                        RaftLiveCatchUpEffect(serverId = selectedServerId, visible = active, foreground = false) {
                            tasksViewModel.dispatch(SharedTasksRouteIntent.CatchUp)
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                        key(tasksRoute) {
                        SharedTasksScreen(
                            state = tasksState,
                            onIntent = { intent ->
                                prewarmAdmissionGate.invalidate("tasks_intent:${intent::class.simpleName}")
                                tasksViewModel.dispatch(intent)
                            },
                            scrollToTopRequest = tabScrollToTopRequests.tasks,
                            bottomContentPadding = appShellBottomContentInset,
                            draftProjection = draftProjection,
                            initialListPosition = tasksListPosition,
                            onListPositionSnapshot = {
                                prewarmAdmissionGate.invalidate("tasks_scroll")
                                tasksListPosition = it
                            },
                            identityOptions = tasksServerMembersEntry.members,
                            timeContext = threadTimeContext,
                            onOpenTask = { task ->
                                prewarmAdmissionGate.invalidate("tasks_open_task")
                                // task #34: wide desktop mode keeps the board and opens the task's
                                // thread in a bounded overlay; otherwise the full-page push stays.
                                val taskLaunchPayload =
                                    buildStandaloneTaskThreadLaunchPayload(
                                        task = task,
                                        serverId = readyServerId.orEmpty(),
                                        conversationDirectory = appShellConversationDirectory,
                                        timeContext = threadTimeContext,
                                        alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                                        compactConsecutiveMessages = compactConsecutiveMessages,
                                    )
                                // page_viewed: the thread page this opens counts as `task`.
                                taskLaunchPayload?.instanceId?.let(RaftProductEvents.recorder::markTaskThreadOpening)
                                val overlaySelection = taskLaunchPayload?.let(::openLaunchPayloadInAdaptiveDetail)
                                if (overlaySelection != null) {
                                    tasksDetailOverlayActive = true
                                    bridge.log(
                                        "tasks_detail_overlay_open task=${task.id} " +
                                            "selection=${overlaySelection.identity.key} generation=${overlaySelection.generation}"
                                    )
                                    return@SharedTasksScreen
                                }
                                pager.openStandaloneTaskThreadPage(
                                    task = task,
                                    serverId = readyServerId.orEmpty(),
                                    conversationDirectory = appShellConversationDirectory,
                                    timeContext = threadTimeContext,
                                    alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                                    compactConsecutiveMessages = compactConsecutiveMessages,
                                                            storeScope = navigationStoreScope
                                )
                            }
                        )
                        }
                        if (tasksDetailOverlayOpen) {
                            RaftTasksDetailOverlay(
                                onDismiss = ::closeTasksDetailOverlay,
                                bottomInset = shellScope.embeddedChildBottomInset(showAppShellBottomTabs),
                            ) { modifier ->
                                adaptiveDetailPane(modifier, active, true, false)
                            }
                        }
                        }
                    }
                    // task #13: shared list-detail host — the Activity tab renders the same
                    // split (360dp list + workspace detail) the Home tab uses, driven by the
                    // shell-wide adaptiveWorkspaceStore. The detail host lives in this stable
                    // slot across wide/compact transitions so the native child controller,
                    // draft and scroll state are retained rather than remounted by branch
                    // movement; task #22 lifts only the detail slot above the floating dock.
                    val adaptiveSplitListDetail:
                        @Composable
                        (
                            enabled: Boolean,
                            active: Boolean,
                            listPane: @Composable (Boolean) -> Unit,
                            detailAccepted: Boolean,
                            listKind: RaftListPaneKind,
                            fillListWhenNothingPicked: Boolean,
                        ) -> Unit = { enabled, active, listPane, detailAccepted, listKind, fillListWhenNothingPicked ->
                        val workspace = adaptiveWorkspaceState
                        val selection = workspace?.selection?.takeIf { detailAccepted }
                        val detailPane: @Composable (Modifier) -> Unit = { modifier ->
                            adaptiveDetailPane(modifier, active, detailAccepted, true)
                        }
                        if (enabled) {
                            // task #37 / artin task #20: per-kind list width and the split live in
                            // RaftAdaptiveListDetailSplit.kt.
                            RaftAdaptiveListDetailSplit(
                                kind = listKind,
                                widths = adaptiveListPaneWidths,
                                dualPane = workspace?.pane?.layout == RaftAdaptivePaneLayout.DualPane,
                                active = active,
                                hasSelection = selection != null,
                                detailBottomInset = shellScope.embeddedChildBottomInset(showAppShellBottomTabs),
                                listPane = listPane,
                                detailPane = detailPane,
                                fillListWhenNothingPicked = fillListWhenNothingPicked,
                                onResizingChange = { adaptiveListPaneResizing = it },
                                log = { bridge.log(it) },
                            )
                        } else {
                            listPane(active)
                        }
                    }
                    val homeTabContentBase:
                        @Composable
                        (
                            active: Boolean,
                            kindFilter: ConversationKind?,
                            scrollRequest: Int,
                            positions: AppShellServerLazyListPositions,
                            onPositionsSnapshot: (AppShellServerLazyListPositions) -> Unit
                        ) -> Unit = { active, kindFilter, scrollRequest, positions, onPositionsSnapshot ->
                        val homeListServerId =
                            appShellHomeListServerId(
                                renderedServerId = renderedHomeServerId,
                                readyServerId = readyServerId
                            )
                        val homeListPositionServerId = readyServerId ?: homeListServerId
                        // thread_unread_badge_v0, read per server like the web `useServerFeatureFlag`.
                        val homeFeatureFlagRevision by sharedStores.serverFeatureFlags.snapshotRevision.collectAsState()
                        val homeThreadUnreadBadgeEnabled =
                            remember(homeListServerId, homeFeatureFlagRevision) {
                                val flagServerId = homeListServerId?.takeIf { it.isNotBlank() }
                                flagServerId != null &&
                                    sharedStores.serverFeatureFlags.isEnabled(
                                        ServerId(flagServerId),
                                        THREAD_UNREAD_BADGE_FEATURE_FLAG_KEY
                                    )
                            }
                        // sidebar_mark_all_read_v0, the web SidebarReadToggleMenuItem's second gate.
                        val homeSidebarMarkAllReadEnabled =
                            remember(homeListServerId, homeFeatureFlagRevision) {
                                val flagServerId = homeListServerId?.takeIf { it.isNotBlank() }
                                flagServerId != null &&
                                    sharedStores.serverFeatureFlags.isEnabled(
                                        ServerId(flagServerId),
                                        SIDEBAR_MARK_ALL_READ_FEATURE_FLAG_KEY
                                    )
                            }
                        val prefersChineseCommunity =
                            localePreferenceStore.readLocaleSync() == AppLocale.SimplifiedChinese
                        PerformanceSceneEffect(bridge, "kuikly_home", active = active)
                        val workspace = adaptiveWorkspaceState
                        val selection = workspace?.selection
                        val homeListPane: @Composable (Boolean) -> Unit = { listActive ->
                            SharedHomeScreen(
                                bridge = bridge,
                                state = homeState,
                                active = listActive,
                                scrollToTopRequest = scrollRequest,
                                conversationKindFilter = kindFilter,
                                sessionReady =
                                    authoritativeHomeStartAllowed &&
                                        readyServerId?.isNotBlank() == true,
                                draftPersistSlice = composerDraftPersistSlice,
                                conversationIdentityProvider = sharedStores.conversationIdentityProvider(storeScope),
                                activityUnreadCount = activityUnreadSummary.totalUnreadCount,
                                activityTotalCount = activityUnreadSummary.totalCount,
                                serverActivityExpectedOwner = serverActivityExpectedOwner,
                                savedCount = homeSearchSavedState.saved.totalCount,
                                prefersChineseCommunity = prefersChineseCommunity,
                                serverDrawerEnabled = serverDrawerEnabled,
                                homeServerAvatarEnabled = homeServerAvatarEnabled,
                                // artin task #8: on a wide screen the top bar holds the server.
                                serverSwitcherInTopBar = navRailActive,
                                // The phone bar or the wide rail carries Search and Activity (nav
                                // spec §5): the list keeps only Saved, never a second copy.
                                searchAndActivityOnShellNav = appShellNavCarriesSearchAndActivity(navRailActive),
                                threadUnreadBadgeEnabled = homeThreadUnreadBadgeEnabled,
                                sidebarMarkAllReadEnabled = homeSidebarMarkAllReadEnabled,
                                onIntent = { intent ->
                                    prewarmAdmissionGate.invalidate("home_intent:${intent::class.simpleName}")
                                    if (intent is SharedHomeIntent.SelectServer) {
                                        pendingHomeServerSwitchId =
                                            intent.serverId.takeIf { it != homeListServerId }
                                        observedHomeServerSwitchId = null
                                        bridge.performanceMarker(
                                            phase = "home_server_switch_select",
                                            routeName = "kuikly_home",
                                            event = "instant",
                                            metadata =
                                                mapOf(
                                                    "from" to homeListServerId.orEmpty(),
                                                    "to" to intent.serverId
                                                )
                                        )
                                        readySession
                                            ?.withAppShellSelectedServer(intent.serverId)
                                            ?.let { session ->
                                                authGateViewModel.dispatch(SharedAuthGateIntent.ServerReady(session))
                                                bridge.performanceMarker(
                                                    phase = "home_server_switch_session_projected",
                                                    routeName = "kuikly_home",
                                                    event = "instant",
                                                    metadata = mapOf("serverId" to intent.serverId)
                                                )
                                            }
                                    }
                                    homeShellStore.dispatch(SharedHomeShellIntent.Home(intent))
                                    if (intent is SharedHomeIntent.SelectServer) {
                                        bridge.performanceMarker(
                                            phase = "home_server_switch_store_dispatched",
                                            routeName = "kuikly_home",
                                            event = "instant",
                                            metadata = mapOf("serverId" to intent.serverId)
                                        )
                                    }
                                },
                                onNotificationDismiss = { id ->
                                    homeShellStore.dispatch(SharedHomeShellIntent.DismissNotification(id))
                                },
                                onAppUpdateOpen = {
                                    pager.openAppUpdatePage(storeScope)
                                },
                                externalFocusLossDismissEpoch = homeFocusLossDismissEpoch,
                                selectedConversationId =
                                    if (desktopModeOnLargeScreen) adaptiveWorkspaceSession.detailListSelectionId()
                                    else resolveRaftHomeSelectedConversationId(
                                        adaptiveWorkspaceEnabled = false,
                                        adaptiveSelection = selection,
                                        shellSelectedConversationId = selectedHomeConversationId,
                                        homeSelectedConversationId = homeState.selectedConversationId,
                                    ),
                                onSelectedConversationCleared = {
                                    homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
                                },
                                onOpenSearch = {
                                    openSearchTab(source = "home_head")
                                },
                                onOpenActivity = {
                                    openHomeSecondaryDestination(HomeDestinationActivity)
                                },
                                onOpenSaved = {
                                    RaftProductEvents.navEntrySelected(
                                        destination = RaftNavDestination.Saved,
                                        surface =
                                            if (desktopModeOnLargeScreen) {
                                                RaftNavSurface.Sidebar
                                            } else {
                                                RaftNavSurface.MobileHomeShortcuts
                                            },
                                        wasActive =
                                            desktopModeOnLargeScreen &&
                                                railInlineSecondaryDestination == AppShellRailSavedKey,
                                    )
                                    if (desktopModeOnLargeScreen) {
                                        // Large screens keep the rail and open Saved as the
                                        // list pane with the shared detail beside it.
                                        railInlineSecondaryDestination = AppShellRailSavedKey
                                        bridge.log("app_shell_rail_secondary_open destination=saved")
                                    } else {
                                        openHomeSecondaryDestination(HomeDestinationSaved)
                                    }
                                },
                                onOpenComputers = {
                                    selectAppShellTab("settings")
                                    retainedAppShellTabs = retainedAppShellTabs + "settings"
                                    openComputersPage()
                                },
                                onOpenUrl = settingsCapabilities::openUrl,
                                onOpenServerSelection = {
                                    pager.openAuthChooseServerPage(
                                        storeScope = navigationStoreScope,
                                        documentShell = pager.pageData.params.optBoolean(RAFT_DOCUMENT_SHELL, false)
                                    )
                                },
                                onInviteHuman = { serverId -> serverSwitcherInviteServerId = serverId },
                                onCreateChannel = { openCreateChannelDialog() },
                                onCreateJointChannel = { openCreateJointChannelDialog() },
                                onOpenConversation = { conversationIdentity ->
                                    openHomeConversation(conversationIdentity, "home")
                                },
                                serverMembersStore = appShellServerMembersStore,
                                channelRepository = channelRepository,
                                jointInviteStore = jointInviteStore,
                                topSafeAreaOverride = shellScope.topSafeArea,
                                bottomContentPadding = appShellBottomContentInset,
                                listPositionServerId = homeListPositionServerId,
                                initialListPosition =
                                    appShellLazyListPositionForServer(
                                        currentServerId = homeListPositionServerId,
                                        savedPositions = positions
                                    ),
                                onListPositionSnapshot = { serverId, position ->
                                    prewarmAdmissionGate.invalidate("home_scroll")
                                    onPositionsSnapshot(positions.withPosition(serverId, position))
                                },
                                onContentComposeCommitted = { serverId ->
                                    if (
                                        pendingHomeServerSwitchId == serverId &&
                                        observedHomeServerSwitchId == serverId
                                    ) {
                                        bridge.performanceMarker(
                                            phase = "home_server_switch_compose_committed",
                                            routeName = "kuikly_home",
                                            event = "instant",
                                            metadata = mapOf("serverId" to serverId)
                                        )
                                        pendingHomeServerSwitchId = null
                                        observedHomeServerSwitchId = null
                                    }
                                }
                            )
                        }
                        adaptiveSplitListDetail(
                            desktopModeOnLargeScreen,
                            active,
                            homeListPane,
                            true,
                            RaftListPaneKind.Sidebar,
                            false,
                        )
                    }
                    val homeTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        homeTabContentBase(
                            active,
                            // task #94: in Slack-nav mode the Home tab is channels-only; the DMs
                            // tab owns direct messages. Legacy mode keeps the merged list.
                            if (slackNavMode) ConversationKind.Channel else null,
                            tabScrollToTopRequests.home,
                            homeListPositions,
                            { homeListPositions = it }
                        )
                    }
                    val dmsTabContent: @Composable (active: Boolean) -> Unit = { }
                    // task #94: Activity tab hosts the shared activity inbox surface directly —
                    // the same screen the home secondary Activity destination renders.
                    // task #13: on DualPane the inbox stays as the 360dp left list and the
                    // opened thread renders in the shared workspace detail slot at right —
                    // same canonical-identity OpenDetail path the Home tab uses, so compact
                    // keeps the standalone push behavior unchanged.
                    val activityListPane: @Composable (Boolean) -> Unit = { listActive ->
                        SharedHomeActivityScreen(
                            bridge = bridge,
                            authStore = shellAuthStore,
                            routeVisibleTick = pageVisibleTick,
                            active = listActive,
                            onOpenMessage = { request ->
                                openHomeMessageRequest(
                                    request = request,
                                    source = "activity_tab"
                                )
                            },
                            onClose = null,
                            bottomContentPadding = appShellBottomContentInset,
                            includeTopSafeArea = true,
                            timeContext = threadTimeContext,
                            shellScope = shellScope,
                            storeScope = navigationStoreScope,
                            activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                            externalSelectedThreadId =
                                adaptiveWorkspaceState?.selection?.identity?.threadId,
                            // The two-pane layout owns the highlight: none once the detail closes.
                            externalSelectionOwned = navRailActive,
                            mainTab = true,
                            scrollToTopRequest = tabScrollToTopRequests.activity
                        )
                    }
                    val activityTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        PerformanceSceneEffect(bridge, "kuikly_activity", active = active)
                        adaptiveSplitListDetail(
                            desktopModeOnLargeScreen,
                            active,
                            activityListPane,
                            true,
                            // Web Activity is two columns at every desktop width (slock #10842).
                            RaftListPaneKind.List,
                            false,
                        )
                    }
                    // Members and Settings are server pages pushed from the server panel on the phone
                    // layout (raft-design #712): their header gets a back button to the page the panel
                    // opened them from (Home after a deep link). On the wide rail they are rail roots.
                    val moreChildBack: (() -> Unit)? =
                        appShellMoreChildBackTarget(selectedTab, navRailActive, serverPageOrigin)?.let { target ->
                            {
                                selectAppShellTab(target, directChildSwitch = true)
                                retainedAppShellTabs = retainedAppShellTabs + target
                                serverTabFocusPending = true
                            }
                        }
                    val membersTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        SharedMembersScreen(
                            onBack = moreChildBack,
                            bridge = bridge,
                            storeScope = navigationStoreScope,
                            sharedStores = sharedStores,
                            loadInviteWebBaseUrl = {
                                (settingsCapabilities.loadDeveloper() as? DataResult.Content)?.data?.webUrl
                            },
                            session = readySession,
                            memberRepository = memberRepository,
                            settingsRepository = settingsRepository,
                            createAgentCoordinator = createAgentCoordinator,
                            createAgentFormStore = createAgentFormStore,
                            computersStore = appShellComputersStore,
                            agentDetailStore = appShellAgentDetailStore,
                            agentPanelTabOrderStore = sharedStores.agentPanelTabOrderStore,
                            serverMembersStore = appShellServerMembersStore,
                            active = active,
                            membersRefreshKey = membersListRefreshKey,
                            initialContent =
                                sharedMembersCachedContentForServer(membersListCachedContent, readyServerId),
                            onContentLoaded = { membersListCachedContent = it },
                            onRetry = {
                                prewarmAdmissionGate.invalidate("members_retry")
                                membersListRefreshKey += 1
                            },
                            timeContext = threadTimeContext,
                            bottomContentPadding = appShellBottomContentInset,
                            scrollToTopRequest = tabScrollToTopRequests.members,
                            initialListState = membersListState,
                            onListStateSnapshot = {
                                prewarmAdmissionGate.invalidate("members_scroll")
                                membersListState = it
                            },
                            paneLayout = shellScope.adaptivePane.layout,
                            desktopMembersEnabled = desktopModeOnLargeScreen,
                            detailSelection = membersDetailSelection,
                            onForegroundActivity = { reason ->
                                prewarmAdmissionGate.invalidate("members_$reason")
                            }
                        )
                    }
                    // raft-design #712: on the phone Settings is a pushed page titled Settings, opened
                    // from the server panel, with Back (Members and Computers are on the panel now,
                    // so they no longer lead it); its rows keep their attention dots. The wide rail
                    // keeps its Settings page as before.
                    val settingsTabContent: @Composable (active: Boolean) -> Unit = {
                        SettingsAppShellHomeContent(
                            moreRoot =
                                if (navRailActive) {
                                    null
                                } else {
                                    SettingsMoreRoot(
                                        title = settingsTabLabel,
                                        membersTitle = membersTabLabel,
                                        attentionRowKeys = moreAttention.mapTo(mutableSetOf()) { it.settingsRowKey },
                                        leadsWithMembersAndComputers = false,
                                    )
                                },
                            onBack = moreChildBack,
                            session = (authGateState as? SharedAuthGateState.Ready)?.session,
                            bottomContentPadding = appShellBottomContentInset,
                            scrollToTopRequest = tabScrollToTopRequests.more,
                            onSettingsHeaderClick = {
                                prewarmAdmissionGate.invalidate("settings_header")
                                val nextTapCount = settingsHeaderTapCount + 1
                                if (nextTapCount >= SettingsDeveloperUnlockTapCount) {
                                    settingsHeaderTapCount = 0
                                    pager.openSettingsNativeDetailPage(SettingsNativeDetailPage.Developer, storeScope)
                                } else {
                                    settingsHeaderTapCount = nextTapCount
                                }
                            },
                            onSelect = { key ->
                                prewarmAdmissionGate.invalidate("settings_open_page:$key")
                                settingsHeaderTapCount = 0
                                when (key) {
                                    SettingsMoreMembersRowKey -> {
                                        RaftProductEvents.navEntrySelected(
                                            destination = RaftNavDestination.Members,
                                            surface = RaftNavSurface.MobileMore,
                                            wasActive = false,
                                        )
                                        bridge.log("app_shell_more_open row=$key from=$selectedTab")
                                        selectAppShellTab("members", directChildSwitch = true)
                                        retainedAppShellTabs = retainedAppShellTabs + "members"
                                    }
                                    SettingsDestinationAccount -> pager.openSettingsAccountPage(storeScope)
                                    SettingsDestinationStorage -> pager.openSettingsStoragePage(storeScope)
                                    SettingsDestinationReleaseNotes -> pager.openReleaseNotesPage(storeScope = storeScope)
                                    SettingsDestinationBilling -> pager.openSettingsBillingPage(storeScope)
                                    SettingsDestinationComputers -> {
                                        if (!navRailActive) {
                                            RaftProductEvents.navEntrySelected(
                                                destination = RaftNavDestination.Computers,
                                                surface = RaftNavSurface.MobileMore,
                                                wasActive = false,
                                            )
                                        }
                                        openComputersPage()
                                    }
                                    SettingsDestinationAdministration -> pager.openSettingsAdministrationPage(storeScope)
                                    SettingsDestinationConnectedApps -> pager.openSettingsMarketplacePage(storeScope)
                                    SettingsDestinationHandsFeedback ->
                                        if (sharedStores.serverFeatureFlags.isEnabled(readyServerId?.let(::ServerId), PRODUCT_FEEDBACK_MULTI_APP_FEATURE_FLAG_KEY)) {
                                            pager.openSettingsFeedbackWebViewPage(storeScope)
                                        } else {
                                            pager.openSettingsHandsFeedbackPage(storeScope)
                                        }
                                    else -> {
                                        val page = SettingsNativeDetailPage.fromKey(key)
                                        if (page == null || !pager.openSettingsNativeDetailPage(page, storeScope)) {
                                            bridge.logWarn("settings_native_detail_rejected page=$key")
                                        }
                                    }
                                }
                            }
                        )
                    }
                    val currentChildTab = retainedAppShellSelectedTab(selectedTab)
                    val moreTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        PerformanceSceneEffect(bridge, "kuikly_more", active = active)
                        settingsTabContent(active)
                    }
                    // stdrc 2026-10-21 (option B): the phone Search tab. A tab root (no Back, the bar
                    // stays); a result pushes its page over the shell and Back returns here with the
                    // query, results and scroll kept (the tab stays composed like the others).
                    // The field never autofocuses (nav spec: the first switch shows the tab with the bar);
                    // tapping it or re-tapping the selected Search tab focuses it. While the tab is not
                    // shown its field holds neither focus nor the IME.
                    val searchTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        HomeSecondaryRouterContent(
                            destination = HomeDestinationSearch,
                            routeVisibleTick = pageVisibleTick,
                            initialSearchFocusInput = false,
                            searchInputActive = active,
                            searchFocusInputRequest = searchTabFocusInputRequest,
                            searchQueryRequest = searchTabQueryRequest,
                            onSearchQueryApplied = { searchTabQueryRequest = null },
                            adaptivePane = shellScope.adaptivePane,
                            tabRoot = true,
                            hostPager = pager,
                            searchListState = searchTabListState,
                            searchReturnHighlight = searchTabReturnHighlight,
                            searchBottomContentPadding = appShellBottomContentInset,
                        )
                    }
                    val childTabRenderer: AppShellChildTabRenderer = { tab, active ->
                        val homeSummary = sharedHomeContentRenderSummary(homeState.content)
                        LaunchedEffect(tab, active, currentChildTab, readyServerId, homeSummary, homeState.homeRealtimeDirty) {
                            bridge.log(
                                "app_shell_child_tab_dispatch tab=$tab active=$active selectedTab=$currentChildTab " +
                                    "readyServerId=${readyServerId.orEmpty()} homeState=$homeSummary " +
                                    "homeRealtimeDirty=${homeState.homeRealtimeDirty}"
                            )
                        }
                        CompositionLocalProvider(LocalRaftTimeContextProvider provides hostThreadTimeContextProvider) {
                            when (tab) {
                                "home" -> homeTabContent(active)
                                "tasks" -> tasksTabContent(active)
                                "members" -> membersTabContent(active)
                                "settings" -> settingsTabContent(active)
                                "dms" -> dmsTabContent(active)
                                "activity" -> activityTabContent(active)
                                "more" -> moreTabContent(active)
                                AppShellSearchTabKey -> searchTabContent(active)
                                else -> homeTabContent(active)
                            }
                        }
                    }
                    val childTabRegistration =
                        remember(childTabShellId, slackNavTabKeys) {
                            val entry = AppShellChildTabEntry(
                                debugId = AppShellChildTabRegistry.createEntryId(),
                                timestampDisplayState = timestampDisplayState,
                                initialSelectedTab = currentChildTab,
                                initialParentVisible = pageVisible,
                                initialRetainedTabs = retainedAppShellTabs,
                                initialRenderer = childTabRenderer,
                                // task #94: the render-slot map follows the resolved ordered
                                // keys; a gate flip re-registers the entry with the new set.
                                initialTabKeys = slackNavTabKeys,
                                initialModalHostState = appShellModalHostState
                            )
                            val replaced = AppShellChildTabRegistry.register(childTabShellId, entry)
                            AppShellChildTabRegistration(entry = entry, replacedEntryId = replaced?.debugId ?: 0)
                        }
                    val childTabEntry = childTabRegistration.entry
                    SideEffect {
                        childTabEntry.updateSelection(
                            selectedTab = currentChildTab,
                            parentVisible = pageVisible,
                            retainedTabs = retainedAppShellTabs,
                            renderer = childTabRenderer
                        )
                        childTabEntry.modalHostState = appShellModalHostState
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
                    // task #13 three-pane: a rail secondary destination (Search) replaces the
                    // tab host's middle area with its own list pane; the shared workspace
                    // detail slot to its right keeps working via adaptiveSplitListDetail.
                    // Narrow or flag-off never reaches here — the push path handles those.
                    // The inline Search / Saved lists mark the row whose conversation is open in
                    // the detail beside them, like Home and Activity (one selection per list: their
                    // row keys are not shared).
                    val railInlineSearchSelection =
                        remember { RaftInlineListSelection { adaptiveWorkspaceStore.snapshot().workspace?.selection?.identity?.key } }
                    val railInlineSavedSelection =
                        remember { RaftInlineListSelection { adaptiveWorkspaceStore.snapshot().workspace?.selection?.identity?.key } }
                    val railInlineOpenDetailKey = adaptiveWorkspaceState?.selection?.identity?.key
                    SideEffect {
                        railInlineSearchSelection.openDetailKey = railInlineOpenDetailKey
                        railInlineSavedSelection.openDetailKey = railInlineOpenDetailKey
                    }
                    val railInlineDestination =
                        when (railInlineSecondaryDestination) {
                            AppShellRailSearchKey -> HomeDestinationSearch
                            AppShellRailSavedKey -> HomeDestinationSaved
                            else -> null
                        }
                    if (railInlineDestination != null) {
                        val searchListPane: @Composable (Boolean) -> Unit = { listActive ->
                            HomeSecondaryRouterContent(
                                destination = railInlineDestination,
                                routeVisibleTick = pageVisibleTick,
                                initialSearchFocusInput = false,
                                adaptivePane = shellScope.adaptivePane,
                                searchQueryRequest = searchTabQueryRequest.takeIf { railInlineDestination == HomeDestinationSearch },
                                onSearchQueryApplied = { searchTabQueryRequest = null },
                                inlineListSelection =
                                    if (railInlineDestination == HomeDestinationSearch) {
                                        railInlineSearchSelection
                                    } else {
                                        railInlineSavedSelection
                                    },
                                inlineOpenRequest = { request, source ->
                                    openHomeMessageRequest(request, source)
                                },
                                inlineOnClose = {
                                    // Same ordering as popMainRoute: a mounted detail
                                    // closes before the inline Search destination exits.
                                    // Read the store snapshot, not the compose state: the
                                    // remember-keyed environment may hold this lambda across
                                    // recompositions, so only the store has the live truth.
                                    val liveSelection =
                                        adaptiveWorkspaceStore.snapshot().workspace?.selection
                                    val dismissFirst =
                                        appShellRailSurfaceBackDismissesDetailFirst(
                                            selectedTab = selectedTab,
                                            railInlineSecondaryDestination = railInlineSecondaryDestination,
                                            selectionPresent = liveSelection != null,
                                        )
                                    bridge.log(
                                        "rail_inline_secondary_close destination=$railInlineSecondaryDestination " +
                                            "selectedTab=$selectedTab dismissFirst=$dismissFirst " +
                                            "selection=${liveSelection?.identity?.key.orEmpty()}"
                                    )
                                    if (dismissFirst) {
                                        dismissAdaptiveDetailSelection()
                                    } else {
                                        railInlineSecondaryDestination = null
                                    }
                                },
                                inlineOpenEntity = { entity ->
                                    when (entity.destinationOrNull()) {
                                        is HomeSearchEntityDestination.Conversation -> {
                                            // Conversation entities open in the shared detail
                                            // pane; everything else still needs a full page.
                                            val destination =
                                                entity.destinationOrNull()
                                                    as HomeSearchEntityDestination.Conversation
                                            openHomeConversation(
                                                conversation = destination.identity,
                                                source = "home_search_entity",
                                            )
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            )
                        }
                        adaptiveSplitListDetail(
                            desktopModeOnLargeScreen,
                            pageVisible,
                            searchListPane,
                            true,
                            // Web Search fills the width until a result is picked (searchMasterDetail).
                            RaftListPaneKind.List,
                            true,
                        )
                    } else {
                    AppShellTabPager(
                        selectedTab = selectedTab,
                        pageVisible = pageVisible,
                        childHostShellId = childTabShellId,
                        mountedTabs = retainedAppShellTabs + prewarmedAppShellTabs,
                        childTabSwitchEpoch = childTabSwitchEpoch,
                        childPageTabKeys = slackNavTabKeys,
                        log = { bridge.log(it) }
                    ) { tab, active ->
                        CompositionLocalProvider(LocalRaftTimeContextProvider provides hostThreadTimeContextProvider) {
                            when (tab) {
                                "settings" -> settingsTabContent(active)
                                else -> childTabRenderer(tab, active)
                            }
                        }
                    }
                    }
            Box(modifier = Modifier.fillMaxSize()) {
                if (createJointChannelDialogOpen) {
                    val jointHomeSnapshot = (homeState.content as? SharedHomeContentState.Content)?.snapshot
                    CreateJointChannelDialogHost(
                        networkClient = appShellNetworkClient,
                        // Web `freeAllowance`: the current server's plan is "free".
                        freePlan = (jointHomeSnapshot?.server?.plan ?: "free") == "free",
                        currentServerSlug = jointHomeSnapshot?.server?.slug,
                        currentMemberId = jointHomeSnapshot?.currentMember?.id?.value,
                        members = createChannelMembers,
                        membersLoading = createChannelMembersLoading,
                        logger = raftLogger("SlockCreateJointChannel", "create_joint_channel", RaftLogLevel.Warn),
                        agentStatusResolver = createChannelAgentStatusResolver,
                        // Web `findSameAdminJointTarget` inputs: the account and its server roles.
                        sameAdminContext =
                            remember(readySession, jointHomeSnapshot?.server?.id) {
                                readySession?.jointSameAdminContext(jointHomeSnapshot?.server?.id?.value)
                            },
                        onDismiss = { createJointChannelDialogOpen = false },
                        onOpenChannel = { channel ->
                            createJointChannelDialogOpen = false
                            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Refresh))
                            openHomeConversation(
                                buildThreadLaunchConversationIdentity(
                                    kind = ConversationKind.Joint,
                                    channelId = channel.channelId,
                                    channelType = "joint",
                                ),
                                "create_joint_channel",
                            )
                        },
                        onOpenBilling = {
                            createJointChannelDialogOpen = false
                            pager.openSettingsBillingPage(storeScope)
                        },
                    )
                }
                if (createChannelDialogOpen) {
                    SharedCreateChannelDialog(
                        submitting = createChannelSubmitting,
                        membersLoading = createChannelMembersLoading,
                        members = createChannelMembers,
                        errorMessage = createChannelError,
                        agentStatusResolver = createChannelAgentStatusResolver,
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
                activeAnnouncement?.let { announcement ->
                    val announcementWebBase = sharedStores.apiEnvironmentStore.current().webUrl.trimEnd('/')
                    SharedAnnouncementModal(
                        announcement = announcement,
                        onDismiss = {
                            if (announcementStore.dismiss(announcement.id)) {
                                appShellCoroutineScope.launch {
                                    announcementStore.persistDismissal(announcement.id)
                                }
                            }
                        },
                        onOpenUrl = { url ->
                            if (announcementOwner == latestAnnouncementOwner) {
                                resolveAnnouncementLink(
                                    url,
                                    announcementWebBase,
                                    readySession?.servers?.firstOrNull { it.id == readyServerId }?.slug,
                                )?.let { linkNavigator.open(it) }
                            }
                        }
                    )
                }
            }
        }
        }
    }

    val mainRouteContent: @Composable () -> Unit = {
        if (serverDrawerEnabled) {
            AppShellServerDrawerHost(
                homeShellStore = homeShellStore,
                bridge = bridge,
                labels = appShellHomeLabels,
                userHeader = serverDrawerUserHeader,
                topSafeArea = shellScope.topSafeArea,
                bottomSafeArea = shellScope.bottomSafeArea,
                serverMembersStore = appShellServerMembersStore,
                serverActivityExpectedOwner = serverActivityExpectedOwner,
                selectedTab = selectedTab,
                modalGestureBlocked =
                    appShellModalHostState.entries.isNotEmpty() ||
                        createChannelDialogOpen ||
                        activeAnnouncement != null,
                dismissLabel = stringResource(Res.string.common_dismiss),
                onServerClick = ::openServerDrawerHome,
                onConversationClick = ::openServerDrawerConversation,
                onProgrammaticCloseFenceChanged = { serverDrawerProgrammaticCloseFence = it },
                content = mainRouteSurface
            )
        } else {
            mainRouteSurface()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RaftForegroundBannerHost(
            banner = foregroundMessageBanner,
            topSafeArea = shellScope.topSafeArea,
            serverMembersStore = appShellServerMembersStore,
            currentServerStore = sharedStores.localDataStore,
            currentServerIdOverride = readySession?.currentServerId?.let(::ServerId),
            onBannerClick = { target ->
                foregroundBannerStore.dismiss(target.id)
                openHomeMessageRequest(
                    request = target.openRequest,
                    source = "foreground_banner",
                    fallbackAnchorMessageId = target.focusedMessageId
                )
            },
            onBannerDismiss = { target -> foregroundBannerStore.dismiss(target.id) }
        ) {
            key("route-main") { mainRouteContent() }
        }
        ExternalShareLaunchHost(
            pageData = pager.pageData.params,
            authStore = shellAuthStore,
            networkClient = appShellNetworkClient,
            authority =
                readySession?.let { session ->
                    ExternalShareLaunchAuthority(
                        accountId = session.user.id,
                        serverId = session.currentServerId.orEmpty(),
                        servers =
                            session.servers.map { server ->
                                build.raft.app.data.ExternalShareServerOption(
                                    id = server.id,
                                    title = server.name,
                                    slug = server.slug
                                )
                            }
                    )
                },
            bridge = bridge,
            onOpenComposer = ::openExternalShareComposer
        )
    }
}

internal fun SharedHomeShellState.withoutServerDrawerRuntimeForAppShellRender(): SharedHomeShellState =
    copy(
        home =
            home.copy(
                serverSwitcherExpanded = false,
                serverDrawer = HomeServerDrawerState(),
                serverDrawerCommandIntent = build.raft.app.data.HomeServerDrawerCommandIntent()
            )
    )

private data class AppShellServerDrawerProjection(
    val commandIntent: build.raft.app.data.HomeServerDrawerCommandIntent,
    val drawer: HomeServerDrawerState,
    val unreadCounts: Map<String, Int>,
    val activityUnread: HomeServerActivityUnreadProjection,
)

private data class DrawerSettledObservation(
    val isOpen: Boolean,
    val progress: Float
)

private fun DrawerSettledObservation.isSettled(desiredOpen: Boolean): Boolean =
    if (desiredOpen) isOpen && progress >= 0.99f else !isOpen && progress <= 0.01f

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun AppShellServerDrawerHost(
    homeShellStore: SharedHomeShellStore,
    bridge: build.raft.app.base.BridgeModule,
    labels: RaftHomeShellLabels,
    userHeader: RaftServerDrawerUserHeader,
    topSafeArea: Dp,
    bottomSafeArea: Dp,
    serverMembersStore: SharedServerMembersStore?,
    serverActivityExpectedOwner: ReadReceiptOwner?,
    selectedTab: String,
    modalGestureBlocked: Boolean,
    dismissLabel: String,
    onServerClick: (String) -> Unit,
    onConversationClick: (String, String) -> Unit,
    onProgrammaticCloseFenceChanged: (((String) -> Unit)?) -> Unit,
    content: @Composable () -> Unit
) {
    val projectionFlow =
        remember(homeShellStore) {
            homeShellStore.state
                .map { shellState ->
                    AppShellServerDrawerProjection(
                        commandIntent = shellState.home.serverDrawerCommandIntent,
                        drawer = shellState.home.serverDrawer,
                        unreadCounts = shellState.home.serverUnreadCounts,
                        activityUnread = shellState.home.serverActivityUnread,
                    )
                }
                .distinctUntilChanged()
        }
    val initialProjection =
        remember(homeShellStore) {
            val home = homeShellStore.state.value.home
            AppShellServerDrawerProjection(
                home.serverDrawerCommandIntent,
                home.serverDrawer,
                home.serverUnreadCounts,
                home.serverActivityUnread,
            )
        }
    val projection by projectionFlow.collectAsState(initialProjection)
    val currentIntent by rememberUpdatedState(projection.commandIntent)
    val drawerSections =
        remember(
            projection.drawer,
            projection.unreadCounts,
            projection.activityUnread,
            serverActivityExpectedOwner,
        ) {
            projection.drawer.toRaftHomeServerDrawerHostedSections(
                serverUnreadCounts = projection.unreadCounts,
                activityExpectedOwner = serverActivityExpectedOwner,
                activityProjection = projection.activityUnread,
            )
        }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val drawerWidth = homeServerDrawerWidth(maxWidth = maxWidth, maxHeight = maxHeight)
        val drawerState =
            rememberRaftMoveableDrawerState(
                initiallyOpen = false,
                drawerWidth = drawerWidth
            )
        val scrimWidth = (maxWidth - drawerWidth * drawerState.progress).coerceAtLeast(0.dp)
        // The drawer slides the shell (and its Compose bar) aside; the iOS system tab bar stays
        // put above the page, so it hides while any of the drawer shows.
        RaftNativeTabBarCover(active = drawerState.progress > 0.01f)
        var commandState by remember(drawerState) { mutableStateOf(HomeServerDrawerCommandState()) }
        val pointerPolicy = commandState.pointerPolicy(projection.commandIntent)
        val openGestureAdmission =
            HomeServerDrawerOpenGestureAdmission(
                homeSurfaceActive = selectedTab == "home",
                pointerOwnerActive = pointerPolicy.appEdgeEnabled,
                modalPresented = modalGestureBlocked
            )

        fun requestProgrammaticClose(source: String) {
            if (!currentIntent.desiredOpen) return
            commandState = commandState.beginProgrammaticClose()
            bridge.log(
                "server_drawer_programmatic_close_fenced generation=${currentIntent.generation} source=$source"
            )
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
        }
        val currentCloseRequest by rememberUpdatedState<(String) -> Unit> { source ->
            requestProgrammaticClose(source)
        }
        val programmaticCloseFence = remember {
            { source: String -> currentCloseRequest(source) }
        }
        DisposableEffect(programmaticCloseFence, onProgrammaticCloseFenceChanged) {
            onProgrammaticCloseFenceChanged(programmaticCloseFence)
            onDispose { onProgrammaticCloseFenceChanged(null) }
        }

        LaunchedEffect(drawerState, projection.commandIntent.generation) {
            val intent = projection.commandIntent
            if (intent.generation == 0L && !intent.desiredOpen) return@LaunchedEffect
            val generation = intent.generation
            commandState = commandState.pendingAdmission(intent)
            bridge.log(
                "server_drawer_command_pending_admission generation=$generation desiredOpen=${intent.desiredOpen} " +
                    "selectedTab=$selectedTab progress=${drawerState.progress}"
            )
            if (intent.desiredOpen) drawerState.open() else drawerState.close()
            if (currentIntent.generation != generation || currentIntent.desiredOpen != intent.desiredOpen) {
                return@LaunchedEffect
            }
            commandState = commandState.markSubmitted(generation, intent.desiredOpen)
            bridge.log(
                "server_drawer_command_submitted generation=$generation desiredOpen=${intent.desiredOpen} " +
                    "selectedTab=$selectedTab progress=${drawerState.progress}"
            )
            snapshotFlow { DrawerSettledObservation(drawerState.isOpen, drawerState.progress) }
                .first { observation -> observation.isSettled(intent.desiredOpen) }
            if (currentIntent.generation != generation || currentIntent.desiredOpen != intent.desiredOpen) {
                return@LaunchedEffect
            }
            val previousInteractive = commandState.interactiveGeneration
            commandState = commandState.settle(generation, intent.desiredOpen)
            if (commandState.interactiveGeneration == generation && previousInteractive != generation) {
                bridge.log(
                    "server_drawer_interactive generation=$generation " +
                        "rowKey=${commandState.interactiveRowKey.orEmpty()} selectedTab=$selectedTab"
                )
            }
            bridge.log(
                "server_drawer_command_settled generation=$generation terminal=" +
                    "${if (intent.desiredOpen) "settled_open" else "settled_closed"} " +
                    "isOpen=${drawerState.isOpen} progress=${drawerState.progress}"
            )
        }

        LaunchedEffect(drawerState) {
            snapshotFlow { DrawerSettledObservation(drawerState.isOpen, drawerState.progress) }
                .collect { observation ->
                    if (observation.isSettled(desiredOpen = false)) {
                        val previousDismissCount = commandState.gestureDismissCount
                        val generation = commandState.currentIntent.generation
                        commandState =
                            commandState
                                .establishClosedOwner(currentIntent)
                                .nativeSettledClosedWithoutCommand()
                        if (commandState.gestureDismissCount > previousDismissCount) {
                            bridge.log("server_drawer_gesture_closed generation=$generation")
                            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.DismissServerSwitcher))
                        }
                    }
                }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            RaftMoveableDrawer(
                state = drawerState,
                scrimColor = Color.Transparent,
                userScrollEnabled = pointerPolicy.nativePagerEnabled,
                drawerContent = {
                    HomeServerDrawerHostedPanel(
                        sections = drawerSections,
                        labels = labels,
                        userHeader = userHeader,
                        topSafeArea = topSafeArea,
                        bottomSafeArea = bottomSafeArea,
                        serverMembersStore = serverMembersStore,
                        rowObservationGeneration =
                            commandState.currentIntent.generation.takeIf {
                                commandState.currentIntent.desiredOpen &&
                                    commandState.phase in
                                        setOf(
                                            HomeServerDrawerCommandPhase.SubmittedOpen,
                                            HomeServerDrawerCommandPhase.SettledOpen
                                        )
                            },
                        interactiveGeneration = commandState.interactiveGeneration,
                        authoritativeOpenGeneration =
                            projection.commandIntent.generation.takeIf { projection.commandIntent.desiredOpen },
                        onServerClick = onServerClick,
                        onSectionToggle = { serverId ->
                            homeShellStore.dispatch(
                                SharedHomeShellIntent.Home(
                                    SharedHomeIntent.ToggleServerDrawerSection(ServerId(serverId))
                                )
                            )
                        },
                        onRetry = { serverId ->
                            homeShellStore.dispatch(
                                SharedHomeShellIntent.Home(SharedHomeIntent.RetryServerDrawerSection(ServerId(serverId)))
                            )
                        },
                        onConversationClick = { generation, serverId, conversationId ->
                            if (commandState.acceptsConversationClick(generation, currentIntent)) {
                                commandState = commandState.beginProgrammaticClose()
                                onConversationClick(serverId, conversationId)
                            }
                        },
                        onFirstInteractiveRow = { generation, rowKey ->
                            if (!commandState.acceptsRowObservation(generation, currentIntent)) {
                                return@HomeServerDrawerHostedPanel
                            }
                            val previousInteractive = commandState.interactiveGeneration
                            commandState = commandState.rowLaidOut(generation, rowKey)
                            if (commandState.interactiveGeneration == generation && previousInteractive != generation) {
                                bridge.log(
                                    "server_drawer_interactive generation=$generation rowKey=$rowKey selectedTab=$selectedTab"
                                )
                            }
                        }
                    )
                },
                content = {
                    val contentAccessibilityModifier =
                        if (drawerState.progress > 0.01f) {
                            Modifier.clearAndSetSemantics { invisibleToUser() }
                        } else {
                            Modifier
                        }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .then(contentAccessibilityModifier)
                    ) {
                        CompositionLocalProvider(
                            LocalRaftHomeDrawerOpenGesture provides
                                RaftHomeDrawerOpenGesture(
                                    admission = openGestureAdmission,
                                    onOpen = {
                                        if (
                                            selectedTab == "home" &&
                                            pointerPolicy.appEdgeEnabled &&
                                            !modalGestureBlocked &&
                                            !currentIntent.desiredOpen
                                        ) {
                                            commandState = commandState.beginProgrammaticOpen()
                                            bridge.log(
                                                "server_drawer_input source=app_shell_content selectedTab=$selectedTab"
                                            )
                                            homeShellStore.dispatch(
                                                SharedHomeShellIntent.Home(
                                                    SharedHomeIntent.ToggleServerSwitcher
                                                )
                                            )
                                        }
                                    }
                                )
                        ) {
                            content()
                        }
                    }
                }
            )
            if (drawerState.progress > 0.01f) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(scrimWidth)
                            .background(Color.Black.copy(alpha = 0.28f * drawerState.progress)) // raft-color: drawer dimming scrim, black in both modes
                            .semantics {
                                contentDescription = dismissLabel
                                traversalIndex = 1f
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button
                            ) {
                                requestProgrammaticClose("scrim_compose")
                            }
                )
            }
        }
    }
}

internal fun appShellAuthGateViewModelKey(pagerId: String, instanceId: String = ""): String =
    "shared-auth-gate-${instanceId.ifBlank { pagerId }.ifBlank { "default" }}"

internal fun ScreenLoadState<AccountSettingsSnapshot>.toAccountPayload(
    session: StoredAuthSession?,
    actionState: build.raft.app.data.SharedSettingsActionState? = null,
    errorStrings: ErrorPresentationStrings
): RaftSettingsAccountPayload {
    val account = (this as? ScreenLoadState.Content<AccountSettingsSnapshot>)?.data
    val providerActionId =
        actionState
            ?.itemId
            ?.takeIf { actionState.action == ACCOUNT_ACTION_PROVIDER_CONNECT }
    val providerDisconnectId =
        actionState
            ?.itemId
            ?.takeIf { actionState.action == ACCOUNT_ACTION_PROVIDER_DISCONNECT }
    val actionErrorMessage =
        actionState?.userFacingErrorMessage(errorStrings)
    return (account?.toAccountPayload() ?: appShellAccountPayload(session))
        .copy(
            saveInProgress = actionState?.action == ACCOUNT_ACTION_PROFILE_SAVE && actionState.inProgress,
            saveErrorCode = actionErrorMessage.takeIf { actionState?.action == ACCOUNT_ACTION_PROFILE_SAVE },
            avatarInProgress = actionState?.action == ACCOUNT_ACTION_AVATAR_UPLOAD && actionState.inProgress,
            avatarErrorCode = actionErrorMessage.takeIf { actionState?.action == ACCOUNT_ACTION_AVATAR_UPLOAD },
            passwordInProgress = actionState?.action == ACCOUNT_ACTION_PASSWORD_CHANGE && actionState.inProgress,
            passwordErrorCode = actionErrorMessage.takeIf { actionState?.action == ACCOUNT_ACTION_PASSWORD_CHANGE },
            providerConnectingId = providerActionId.takeIf { actionState?.inProgress == true },
            providerErrorId = providerActionId.takeIf { actionErrorMessage != null },
            providerErrorCode = actionErrorMessage.takeIf { providerActionId != null },
            providerDisconnectingId = providerDisconnectId.takeIf { actionState?.inProgress == true },
            providerDisconnectErrorId = providerDisconnectId.takeIf { actionErrorMessage != null },
            providerDisconnectError = actionErrorMessage.takeIf { providerDisconnectId != null },
            usageDataSaving = actionState?.action == ACCOUNT_ACTION_SHARE_USAGE_DATA && actionState.inProgress
        )
}

internal fun ScreenLoadState<AccountSettingsSnapshot>.settingsAccountAvatarCacheIdentity(
    session: StoredAuthSession?
): RaftMemberAvatarCacheIdentity? {
    val sessionUserId = session?.user?.id?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val account = (this as? ScreenLoadState.Content<AccountSettingsSnapshot>)?.data ?: return null
    val profileUserId = account.profile.userId.trim().takeIf(String::isNotEmpty) ?: return null
    if (profileUserId != sessionUserId) return null
    return raftMemberAvatarCacheIdentity(
        kind = MemberKind.Human,
        memberId = profileUserId,
        serverId = session.currentServerId
    )
}

private fun AccountSettingsSnapshot.toAccountPayload(): RaftSettingsAccountPayload =
    RaftSettingsAccountPayload(
        instanceId = "shared-shell-settings-account",
        profile =
            RaftSettingsAccountProfile(
                displayName = profile.displayName,
                handle = profile.handle,
                email = profile.email,
                avatarLabel = profile.avatarLabel,
                avatarUrl = profile.avatarUrl,
                verified = profile.verified,
                roleLabel = profile.roleLabel.orEmpty()
            ),
        connectedAccounts =
            connectedAccounts.map {
                RaftSettingsConnectedAccount(
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
        canChangePassword = canChangePassword,
        usageDataVisible = usageData != null,
        usageDataSharing = usageData?.sharing ?: SHARE_USAGE_DATA_DEFAULT
    )

private fun appShellAccountPayload(session: StoredAuthSession?): RaftSettingsAccountPayload {
    val user = session?.user
    val displayName = user?.bestDisplayName.orEmpty()
    return RaftSettingsAccountPayload(
        instanceId = "shared-shell-settings-account",
        profile =
            RaftSettingsAccountProfile(
                displayName = displayName,
                // `handle` is the username field; the loaded account fills it from the profile's name.
                handle = user?.name?.trim().orEmpty(),
                email = user?.email.orEmpty(),
                avatarLabel = displayName.take(2).uppercase(),
                avatarUrl = user?.avatarUrl,
                verified = user?.emailVerified == true,
                roleLabel = session?.currentServerSummary()?.role.orEmpty()
            ),
        connectedAccounts =
            user
                ?.email
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    listOf(
                        RaftSettingsConnectedAccount(
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

@Composable
internal fun ScreenLoadState<SharedSettingsStorageSnapshot>.toStoragePayload(
    actionState: build.raft.app.data.SharedSettingsActionState?,
    refreshing: Boolean
): RaftSettingsStoragePayload {
    val storage = (this as? ScreenLoadState.Content<SharedSettingsStorageSnapshot>)?.data
    val statusText =
        when (this) {
            ScreenLoadState.Loading -> if (refreshing) stringResource(Res.string.settings_storage_refreshing) else stringResource(Res.string.settings_storage_loading)
            is ScreenLoadState.Empty -> stringResource(Res.string.settings_storage_unavailable)
            is ScreenLoadState.Error -> Res.string.settings_storage_unavailable_detail.format(listOf(this.error.localizedUserFacingMessage())).localized()
            is ScreenLoadState.NoPermission -> Res.string.settings_storage_unavailable_detail.format(listOf(this.blocker.localizedUserFacingMessage())).localized()
            is ScreenLoadState.Content ->
                storage?.feedback.orEmpty().ifBlank {
                    storage?.feedbackError
                        ?.resolve(localizedErrorPresentationStrings())
                        .orEmpty()
                }
        }
    return RaftSettingsStoragePayload(
        instanceId = "shared-shell-settings-storage",
        totalTitle = stringResource(Res.string.settings_storage_total_title),
        totalSize = storage?.totalSizeBytes?.let { RaftFormat.bytes(it) } ?: storage?.totalSizeLabel?.takeIf { it.isNotBlank() } ?: if (refreshing) stringResource(Res.string.settings_state_refreshing) else "--",
        refreshLabel = stringResource(Res.string.settings_storage_refresh),
        safeClearNote = stringResource(Res.string.settings_storage_safe_clear_note),
        feedback = localizedSettingsStorageFeedback(statusText),
        refreshing = refreshing,
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

@Composable
private fun SharedSettingsStorageRow.toStoragePayloadRow(clearInProgress: Boolean): RaftSettingsStorageRow =
    RaftSettingsStorageRow(
        target = target,
        iconName = storageTargetIconName(target),
        title = localizedSettingsStorageRowTitle(target, title),
        detail = localizedSettingsStorageRowDetail(detail),
        size = sizeBytes?.let { RaftFormat.bytes(it) } ?: sizeLabel,
        clearLabel =
            if (clearEnabled) {
                when (target) {
                    "imageCache" -> stringResource(Res.string.settings_storage_clear_image_cache)
                    "attachmentDownloads" -> stringResource(Res.string.settings_storage_clear_downloaded_files)
                    "temporaryAndLogs" -> stringResource(Res.string.settings_storage_clear_temp_files)
                    else -> stringResource(Res.string.common_clear)
                }
            } else {
                ""
            },
        clearEnabled = clearEnabled,
        clearInProgress = clearInProgress
    )

@Composable
private fun localizedSettingsStorageRowTitle(target: String, fallback: String): String =
    when (target) {
        "database" -> stringResource(Res.string.settings_storage_row_database)
        "imageCache" -> stringResource(Res.string.settings_storage_row_image_cache)
        "attachmentDownloads" -> stringResource(Res.string.settings_storage_row_downloads)
        "temporaryAndLogs" -> stringResource(Res.string.settings_storage_row_temporary)
        else -> fallback
    }

@Composable
private fun localizedSettingsStorageRowDetail(detail: String): String =
    when (detail) {
        "Messages, task metadata, and server snapshots stored on this device." ->
            stringResource(Res.string.settings_storage_row_database_detail)
        "Downloaded image previews kept for faster viewing." ->
            stringResource(Res.string.settings_storage_row_image_cache_detail)
        "Files downloaded for opening or installing from previews.",
        "Private copies kept for opening or installing files. Public exports are not removed.",
        "Temporary data from active or failed downloads. Completed files remain in Downloads." ->
            stringResource(Res.string.settings_storage_row_downloads_detail)
        "Debug logs and temporary files generated by the app." ->
            stringResource(Res.string.settings_storage_row_temporary_detail)
        else -> detail
    }

@Composable
private fun localizedSettingsStorageFeedback(feedback: String): String =
    when (feedback) {
        "Image cache cleared." -> stringResource(Res.string.settings_storage_feedback_image_cleared)
        "Downloaded files cleared.",
        "Attachment cache cleared. Public exports were not removed.",
        "Attachment staging cleared. Completed Downloads were not removed." ->
            stringResource(Res.string.settings_storage_feedback_downloads_cleared)
        "Temporary files and diagnostics logs cleared." ->
            stringResource(Res.string.settings_storage_feedback_temporary_cleared)
        else -> feedback
    }

private fun storageTargetIconName(target: String): RaftLucideIconName =
    when (target) {
        "database" -> RaftLucideIconName.Archive
        "imageCache" -> RaftLucideIconName.Image
        "attachmentDownloads" -> RaftLucideIconName.Download
        "temporaryAndLogs" -> RaftLucideIconName.FileText
        else -> RaftLucideIconName.Archive
    }

/** Route-visual fixture compatibility for surfaces now owned by dedicated Pagers. */
private fun String.toVisualFixtureSharedSettingsDestination(): SharedSettingsDestination? =
    when (this) {
        SettingsDestinationAccount -> SharedSettingsDestination.Account
        SettingsDestinationComputers -> SharedSettingsDestination.Computers
        SettingsDestinationAdministration -> SharedSettingsDestination.Administration
        SettingsDestinationConnectedApps -> SharedSettingsDestination.ConnectedApps
        SettingsDestinationBilling -> SharedSettingsDestination.Billing
        SettingsDestinationStorage -> SharedSettingsDestination.Storage
        SettingsDestinationHome -> SharedSettingsDestination.Home
        else -> null
    }

internal class AuthChooseServerVisualApi(
    private val user: StoredAuthUser,
    private val servers: List<StoredAuthServer>
) : RaftAuthApi {
    override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthApiResult<AuthSocialCompleteResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun fetchSocialAuthProviders(platform: String): AuthApiResult<Set<String>> =
        AuthApiResult.Failure(code = 400)

    override suspend fun startMobileOAuthLink(
        provider: String,
        returnUri: String,
        codeChallenge: String,
        authorization: String
    ): AuthApiResult<AuthMobileOAuthStartResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun completeMobileOAuthLink(
        provider: String,
        code: String,
        codeVerifier: String,
        authorization: String
    ): AuthApiResult<AuthMobileOAuthLinkCompleteResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun resendVerification(authorization: String): AuthApiResult<Unit> =
        AuthApiResult.Failure(code = 400)

    override suspend fun verifyEmail(token: String): AuthApiResult<Unit> =
        AuthApiResult.Failure(code = 400)

    override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
        AuthApiResult.Success(user)

    override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> =
        AuthApiResult.Failure(code = 401)

    override suspend fun logout(refreshToken: String): AuthApiResult<Unit> =
        AuthApiResult.Success(Unit)

    override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
        AuthApiResult.Success(servers)

    override suspend fun serverOrder(authorization: String): AuthApiResult<ServerSwitcherOrderPreference> =
        AuthApiResult.Success(ServerSwitcherOrderPreference(servers.map { it.id }))

    override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
        AuthApiResult.Failure(code = 400)
}

internal fun Pager.openAppShellDeveloper() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            AUTH_ABOUT_PAGE,
            JSONObject().put("pageName", AUTH_ABOUT_PAGE)
        )
}

private fun Pager.openAuthChooseServerPage(
    storeScope: RaftShellStoreScope,
    documentShell: Boolean
) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            AUTH_CHOOSE_SERVER_PAGE,
            authChooseServerPageData(
                replaceCurrent = false,
                fromShell = true,
                storeScope = storeScope,
                documentShell = documentShell
            )
        )
}

internal fun Pager.openAppShellReplacingCurrentPage(pageData: JSONObject) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(RAFT_APP_SHELL_PAGE, pageData)
}

internal fun Pager.openLoginReplacingCurrentPage(message: String? = null) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            LOGIN_PAGE,
            loginPageData(
                replaceCurrent = true,
                message = message
            )
        )
}

internal fun authChooseServerPageData(
    replaceCurrent: Boolean = false,
    fromShell: Boolean = false,
    skipInitialLoad: Boolean = false,
    storeScope: RaftShellStoreScope? = null,
    documentShell: Boolean = false
): JSONObject =
    raftAuthRoutePageData(AUTH_CHOOSE_SERVER_PAGE, replaceCurrent)
        .apply {
            if (fromShell) {
                put(AUTH_CHOOSE_SERVER_FROM_SHELL, true)
            }
            if (skipInitialLoad) {
                put(AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD, true)
            }
            storeScope?.let { scope ->
                put(RAFT_SHELL_INSTANCE_ID, scope.shellInstanceId)
                scope.initialServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
                put(RAFT_DOCUMENT_SHELL, documentShell)
            }
        }

internal fun authChooseServerUsesScopedStore(pageData: JSONObject): Boolean =
    pageData.optBoolean(AUTH_CHOOSE_SERVER_FROM_SHELL, false) ||
        pageData.raftShellStoreScopeOrNull() != null

internal fun appShellPageDataForServerSelection(
    sourceStoreScope: RaftShellStoreScope,
    sourceDocumentShell: Boolean = false,
    selectedServerId: String?,
    multiShellHostEnabled: Boolean = false,
    suppressRestoreLanding: Boolean = false,
    routeOwner: AuthenticatedRouteOwner? = null
): JSONObject {
    val normalizedServerId = selectedServerId?.trim()?.takeIf { it.isNotBlank() }
    val opensDocumentShell =
        isMultiShellCrossServerSelection(
            sourceStoreScope = sourceStoreScope,
            selectedServerId = normalizedServerId,
            multiShellHostEnabled = multiShellHostEnabled
        )
    return appShellPageData(
        replaceCurrent = true,
        suppressRestoreLanding = suppressRestoreLanding
    ).apply {
        normalizedServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
        if (opensDocumentShell && routeOwner != null) {
            put(RAFT_DOCUMENT_SHELL, true)
            withAuthenticatedRouteOwner(routeOwner)
        } else {
            put(RAFT_SHELL_INSTANCE_ID, sourceStoreScope.shellInstanceId)
            put(RAFT_DOCUMENT_SHELL, sourceDocumentShell && multiShellHostEnabled)
        }
    }
}

internal fun isMultiShellCrossServerSelection(
    sourceStoreScope: RaftShellStoreScope,
    selectedServerId: String?,
    multiShellHostEnabled: Boolean
): Boolean =
    multiShellHostEnabled &&
        !selectedServerId.isNullOrBlank() &&
        selectedServerId.trim() != sourceStoreScope.initialServerId

internal fun appShellPageData(
    replaceCurrent: Boolean = false,
    suppressRestoreLanding: Boolean = false
): JSONObject =
    raftAuthRoutePageData(RAFT_APP_SHELL_PAGE, replaceCurrent)
        .apply {
            if (suppressRestoreLanding) {
                put(APP_SHELL_SUPPRESS_RESTORE_LANDING, true)
            }
        }

private fun raftAuthRoutePageData(pageName: String, replaceCurrent: Boolean): JSONObject =
    JSONObject().apply {
        put("pageName", pageName)
        if (replaceCurrent) {
            put(AUTH_REPLACE_CURRENT_PAGE, true)
        }
    }

@Composable
internal fun AppShellServerGateContent(
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
    var createName by remember { mutableStateOf(TextFieldValue("")) }
    var createSlug by remember { mutableStateOf(TextFieldValue("")) }
    var createSlugTouched by remember { mutableStateOf(false) }
    var pendingServerId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingServers by remember(session, skipInitialLoad) {
        mutableStateOf(!authChooseServerShouldSkipInitialLoad(skipInitialLoad, session.servers.size))
    }
    var loadAttempt by remember { mutableStateOf(0) }
    var errorPresentation by remember { mutableStateOf<AuthGateMessage?>(null) }
    val errorMessage = errorPresentation.localizedMessage()
    val showPinnedSignOut = serverGateShouldPinSignOut(errorPresentation)

    LaunchedEffect(loadAttempt) {
        if (authChooseServerShouldSkipInitialLoad(skipInitialLoad, latestSession.servers.size)) {
            authDataSource.logDiagnostics(
                "auth_choose_server_load_result result=skipped reason=cached_servers_present cachedServers=${latestSession.servers.size}"
            )
            loadingServers = false
            return@LaunchedEffect
        }
        loadingServers = true
        when (val result = runCatchingPreservingCancellation { authDataSource.loadServers() }.getOrNull()) {
            is SharedAuthResult.Success -> {
                authDataSource.logDiagnostics(
                    "auth_choose_server_load_result result=success serversCount=${result.data.servers.size} selectedServer=${!result.data.selectedServerId.isNullOrBlank()}"
                )
                latestSession = result.data
                errorPresentation = null
            }
            is SharedAuthResult.Failure -> {
                authDataSource.logDiagnostics("auth_choose_server_load_result result=failure code=${result.code}")
                errorPresentation = AuthGateMessage.Dynamic(result.message)
            }
            SharedAuthResult.Unauthenticated -> {
                authDataSource.logDiagnostics("auth_choose_server_load_result result=unauthenticated action=stay_on_server_picker")
                errorPresentation = serverGateUnauthenticatedMessage()
            }
            null -> {
                authDataSource.logDiagnostics("auth_choose_server_load_result result=failure code=exception")
                errorPresentation = AuthGateMessage.Static(Res.string.auth_error_load_servers.desc())
            }
        }
        loadingServers = false
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
                RaftBackButton(onClick = back, label = stringResource(Res.string.common_back))
            }
        }
    ) {
        AuthCenterMark()
        Text(
            text =
                when (mode) {
                    "create" -> stringResource(Res.string.auth_server_title_create)
                    "empty" ->
                        if (loadingServers) {
                            stringResource(Res.string.auth_server_title_checking)
                        } else {
                            stringResource(Res.string.auth_server_title_first)
                        }
                    else -> stringResource(Res.string.auth_server_title_choose)
                },
            style = RaftType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        Text(
            text =
                StringDesc.ResourceFormatted(
                    Res.string.auth_server_signed_in_as,
                    listOf(latestSession.user.bestDisplayName)
                ).localized(),
            style = RaftType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthFormColumn {
            AuthError(errorMessage)
            if (showPinnedSignOut) {
                AuthActionButton(
                    text = stringResource(Res.string.auth_action_sign_out),
                    onClick = {
                        pendingServerId = "__logout__"
                        loading = true
                    },
                    tone = AuthButtonTone.White,
                    enabled = !loading
                )
            }
            if (loadingServers) {
                Text(
                    text = stringResource(Res.string.auth_server_loading),
                    style = RaftType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    textAlign = TextAlign.Center
                )
            } else if (latestSession.servers.isEmpty() && errorMessage != null) {
                AuthActionButton(
                    text = stringResource(Res.string.auth_action_retry),
                    onClick = {
                        errorPresentation = null
                        loadAttempt += 1
                    },
                    tone = AuthButtonTone.Pink,
                    enabled = !loading
                )
            } else if (mode == "create") {
                Text(
                    text = stringResource(Res.string.auth_server_create_hint),
                    style = RaftType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray
                )
                AuthTextField(
                    value = createName,
                    keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                    onValueChange = { nextValue ->
                        createName = nextValue
                        // Web parity (ServerSelector.tsx): the slug mirrors the
                        // name until the user edits the slug directly.
                        if (!createSlugTouched) {
                            val slug = authCreateServerSlugFromName(nextValue.text)
                            createSlug = TextFieldValue(slug, selection = TextRange(slug.length))
                        }
                    },
                    label = stringResource(Res.string.auth_label_server_name),
                    required = true,
                    enabled = !loading
                )
                AuthTextField(
                    value = createSlug,
                    keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                    onValueChange = { nextValue ->
                        createSlugTouched = true
                        createSlug = authCreateServerSlugFieldValue(nextValue)
                    },
                    label = stringResource(Res.string.auth_label_server_slug),
                    placeholder = "my-team",
                    enabled = !loading
                )
                AuthActionButton(
                    text =
                        if (loading) {
                            stringResource(Res.string.auth_state_creating_server)
                        } else {
                            stringResource(Res.string.auth_action_create_server)
                        },
                    onClick = {
                        loading = true
                        errorPresentation = null
                        pendingServerId = "__create__"
                    },
                    tone = AuthButtonTone.Pink,
                    enabled = createName.text.isNotBlank() && !loading,
                    loading = loading
                )
                AuthActionButton(
                    text =
                        if (latestSession.servers.isEmpty()) {
                            stringResource(Res.string.auth_action_back)
                        } else {
                            stringResource(Res.string.auth_action_back_to_servers)
                        },
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
                    RaftBrutalListRow(
                        title = server.name,
                        subtitle =
                            if (pendingServerId == server.id) {
                                stringResource(Res.string.auth_state_opening_server)
                            } else {
                                "/${server.slug.orEmpty().ifBlank { server.id }}"
                            },
                        enabled = !loading,
                        onClick = {
                            loading = true
                            errorPresentation = null
                            pendingServerId = server.id
                        }
                    )
                }
                AuthActionButton(
                    text = stringResource(Res.string.auth_action_create_new_server),
                    onClick = { mode = "create" },
                    tone = AuthButtonTone.Yellow,
                    enabled = !loading
                )
            }
            if (!showPinnedSignOut) {
                RaftLinkButton(
                    text = stringResource(Res.string.auth_action_sign_out),
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
                is SharedAuthResult.Failure -> errorPresentation = AuthGateMessage.Dynamic(result.message)
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
                authDataSource.createServer(createName.text, slug = createSlug.text)
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
                errorPresentation = serverGateActionFailureMessage(target, result.message)
            }
            SharedAuthResult.Unauthenticated -> {
                authDataSource.logDiagnostics("auth_choose_server_action_result target=$target result=unauthenticated action=stay_on_server_picker")
                errorPresentation = serverGateUnauthenticatedMessage()
            }
        }
    }

}

internal fun authChooseServerShouldSkipInitialLoad(
    skipInitialLoad: Boolean,
    cachedServersCount: Int
): Boolean =
    skipInitialLoad && cachedServersCount > 0

internal sealed interface AppShellOrderedAuthEffect {
    data class AuthLoss(val resolution: AuthLossResolution) : AppShellOrderedAuthEffect
    data class SessionChanged(val fingerprint: String) : AppShellOrderedAuthEffect
    data class SessionInvalidated(val generation: Long?) : AppShellOrderedAuthEffect
}

internal suspend fun appShellRunIfAuthSessionTerminalRevision(
    terminalSource: RaftAuthSessionTerminalSource,
    expectedGeneration: Long,
    action: () -> Unit,
): Boolean =
    terminalSource.runIfAuthSessionTerminalRevision(expectedGeneration, action)

internal suspend fun appShellRunIfAuthSessionTerminalOrAuthorityRevision(
    terminalSource: RaftAuthSessionTerminalSource,
    expectedGeneration: Long,
    expectedSession: StoredAuthSession?,
    action: () -> Unit,
): Long? =
    terminalSource.runIfAuthSessionTerminalOrAuthorityRevision(
        expectedGeneration = expectedGeneration,
        expectedSession = expectedSession,
        action = action,
    )

internal fun appShellAuthLossEffectShouldApply(
    capturedSession: StoredAuthSession?,
    currentSession: StoredAuthSession?,
): Boolean =
    currentSession == null ||
        (
            capturedSession != null &&
            currentSession.user.id == capturedSession.user.id &&
                currentSession.accessToken == capturedSession.accessToken &&
                currentSession.refreshToken == capturedSession.refreshToken
        )

internal fun serverGateActionFailureMessage(target: String, message: String): AuthGateMessage =
    if (target == "__create__" && message == AuthAuthorityChangedMessage) {
        AuthGateMessage.Static(Res.string.auth_error_session_changed_retry_create_server.desc())
    } else {
        AuthGateMessage.Dynamic(message)
    }

// Web parity: ServerSelector.tsx `toSlug` — lowercase, then every character
// outside [a-z0-9-] becomes "-" (no collapsing, no trimming). The create form
// mirrors the name into the slug through this until the slug is hand-edited,
// and normalizes direct slug input through it too.
private val AuthCreateServerSlugDisallowed = Regex("[^a-z0-9-]")

internal fun authCreateServerSlugFromName(value: String): String =
    value.lowercase().replace(AuthCreateServerSlugDisallowed, "-")

/**
 * Normalizes committed slug input without interrupting an active IME composition.
 *
 * Direct slug edits intentionally keep the web `toSlug` behavior, but rebuilding a value while
 * the native input service owns [TextFieldValue.composition] would commit marked text early. Once
 * composition finishes, selection offsets are mapped through the same prefix transformation.
 */
internal fun authCreateServerSlugFieldValue(value: TextFieldValue): TextFieldValue {
    if (value.composition != null) return value
    val normalizedText = authCreateServerSlugFromName(value.text)
    if (normalizedText == value.text) return value
    fun mappedOffset(offset: Int): Int =
        authCreateServerSlugFromName(value.text.substring(0, offset.coerceIn(0, value.text.length))).length

    return TextFieldValue(
        text = normalizedText,
        selection = TextRange(
            mappedOffset(value.selection.start),
            mappedOffset(value.selection.end),
        ),
    )
}

internal fun serverGateUnauthenticatedMessage(): AuthGateMessage =
    AuthGateMessage.Static(
        Res.string.auth_error_session_refresh_choose_server.desc(),
        pinSignOut = true
    )

internal fun serverGateShouldPinSignOut(errorPresentation: AuthGateMessage?): Boolean =
    (errorPresentation as? AuthGateMessage.Static)?.pinSignOut == true

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
            text = stringResource(Res.string.auth_server_empty_hint),
            style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthActionButton(
            text = stringResource(Res.string.auth_action_create_new_server),
            onClick = onCreateServer,
            tone = AuthButtonTone.Pink
        )
    }
}


@Composable
internal fun AppShellAuthErrorContent(
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
                RaftBackButton(onClick = back, label = stringResource(Res.string.common_back))
            }
        }
    ) {
        AuthLogoBadge("RAFT")
        Text(
            text = stringResource(Res.string.auth_title_session_error),
            style = RaftType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        AuthError(message)
        AuthActionButton(text = stringResource(Res.string.auth_action_retry), onClick = onRetry, tone = AuthButtonTone.Pink)
        AuthActionButton(text = stringResource(Res.string.auth_action_sign_in), onClick = onSignIn, tone = AuthButtonTone.White)
    }
}

@Composable
internal fun AppShellEmailVerificationGateContent(
    session: StoredAuthSession,
    authDataSource: SharedAuthDataSource,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit
) {
    var loading by remember { mutableStateOf(false) }
    var errorPresentation by remember { mutableStateOf<AuthGateMessage?>(null) }
    var sentPresentation by remember { mutableStateOf<AuthGateMessage?>(null) }
    var pendingResendNonce by remember { mutableStateOf(0) }
    var pendingSignOut by remember { mutableStateOf(false) }
    val accountEmail = session.user.email.takeIf { it.isNotBlank() }
    val errorMessage = errorPresentation.localizedMessage()
    val sentMessage = sentPresentation.localizedMessage()

    LaunchedEffect(pendingResendNonce) {
        if (pendingResendNonce == 0) return@LaunchedEffect
        val result =
            runCatchingPreservingCancellation { authDataSource.resendVerification() }
                .getOrNull()
        loading = false
        when (result) {
            is SharedAuthResult.Success -> {
                errorPresentation = null
                sentPresentation = AuthGateMessage.Static(Res.string.auth_notice_verification_email_sent.desc())
                authDataSource.logDiagnostics("auth_resend_verification_result result=success")
            }
            is SharedAuthResult.Failure -> {
                sentPresentation = null
                errorPresentation = AuthGateMessage.Dynamic(result.message)
                authDataSource.logDiagnostics("auth_resend_verification_result result=failure code=${result.code}")
            }
            SharedAuthResult.Unauthenticated -> {
                sentPresentation = null
                errorPresentation = AuthGateMessage.Static(Res.string.auth_error_session_refresh.desc())
                authDataSource.logDiagnostics("auth_resend_verification_result result=unauthenticated")
            }
            null -> {
                sentPresentation = null
                errorPresentation = AuthGateMessage.Static(Res.string.auth_error_verification_email_send.desc())
                authDataSource.logDiagnostics("auth_resend_verification_result result=failure code=exception")
            }
        }
    }

    LaunchedEffect(pendingSignOut) {
        if (!pendingSignOut) return@LaunchedEffect
        authDataSource.logout()
        pendingSignOut = false
        loading = false
        onSignOut()
    }

    AuthScaffold {
        AuthLogoBadge("RAFT")
        Text(
            text = stringResource(Res.string.auth_title_verify_email),
            style = RaftType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        Text(
            text =
                if (accountEmail != null) {
                    StringDesc.ResourceFormatted(
                        Res.string.auth_hint_verify_email_address,
                        listOf(accountEmail)
                    ).localized()
                } else {
                    stringResource(Res.string.auth_hint_verify_email)
                },
            style = RaftType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthFormColumn {
            sentMessage?.let {
                AuthSuccessNotice(
                    title = stringResource(Res.string.auth_title_email_sent),
                    detail = it
                )
            }
            AuthError(errorMessage)
            AuthActionButton(
                text = stringResource(Res.string.auth_action_resend_verification),
                onClick = {
                    loading = true
                    errorPresentation = null
                    sentPresentation = null
                    pendingResendNonce += 1
                },
                tone = AuthButtonTone.Pink,
                enabled = !loading,
                loading = loading
            )
            AuthActionButton(
                text = stringResource(Res.string.auth_action_verified_refresh),
                onClick = onRefresh,
                tone = AuthButtonTone.White,
                enabled = !loading
            )
            RaftLinkButton(
                text = stringResource(Res.string.auth_action_sign_out),
                enabled = !loading,
                onClick = {
                    loading = true
                    errorPresentation = null
                    sentPresentation = null
                    pendingSignOut = true
                }
            )
        }
    }
}

@Composable
private fun SharedMembersScreen(
    /** Back to More when Members is a page pushed from it; null on the wide rail. */
    onBack: (() -> Unit)? = null,
    bridge: build.raft.app.base.BridgeModule,
    storeScope: RaftShellStoreScope,
    sharedStores: RaftSharedStoreProvider,
    /** Developer environment web URL the invite link is built from. */
    loadInviteWebBaseUrl: suspend () -> String?,
    session: StoredAuthSession?,
    memberRepository: MemberRepository,
    settingsRepository: SettingsRepository,
    createAgentCoordinator: SharedCreateAgentCoordinator,
    createAgentFormStore: SharedCreateAgentFormStore,
    computersStore: SharedComputersStore,
    agentDetailStore: SharedAgentDetailStore,
    agentPanelTabOrderStore: SharedAgentPanelTabOrderStore,
    serverMembersStore: SharedServerMembersStore,
    active: Boolean,
    membersRefreshKey: Int,
    initialContent: SharedMembersUiState.Content?,
    onContentLoaded: (SharedMembersUiState.Content) -> Unit,
    onRetry: () -> Unit,
    timeContext: AppShellTimeContext?,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    scrollToTopRequest: Int = 0,
    initialListState: AppShellMembersListState = AppShellMembersListState(),
    onListStateSnapshot: (AppShellMembersListState) -> Unit = {},
    paneLayout: RaftAdaptivePaneLayout = RaftAdaptivePaneLayout.Compact,
    desktopMembersEnabled: Boolean = false,
    detailSelection: androidx.compose.runtime.MutableState<RaftMemberDetailPayload?>,
    onForegroundActivity: (String) -> Unit = {}
) {
    val errorStrings = localizedErrorPresentationStrings()
    val membersTitle = stringResource(Res.string.tab_members)
    val membersLoadFailedMessage = stringResource(Res.string.thread_composer_load_members_failed)
    val openProfileFailedMessage = stringResource(Res.string.member_error_open_profile)
    val currentServerId = session?.currentServerId?.trim()?.takeIf { it.isNotBlank() }
    var selectedDetail by detailSelection
    val showMemberSplit = desktopMembersEnabled && paneLayout == RaftAdaptivePaneLayout.DualPane
    // In the split view the detail pane is page state; only the compact detail overlay blocks
    // the full-page back swipe.
    RaftBackHandler(enabled = active && selectedDetail != null, label = "members_detail_pane", blocksBackSwipe = !showMemberSplit) { selectedDetail = null }
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
                raftLogWarn("SlockAgentPanelTabOrder", "shared_members_agent_tab_order_load_failed code=${result.error.code}")
                null
            }
            is DataResult.NoPermission -> {
                raftLogWarn("SlockAgentPanelTabOrder", "shared_members_agent_tab_order_load_blocked code=${result.blocker.code}")
                null
            }
        }
    }
    LaunchedEffect(agentPanelTabOrderStore, currentServerId) {
        agentPanelTabOrderStore.bindServer(
            serverId = currentServerId,
            source = "shared_members_bind_server",
            logger = raftLogger("SlockAgentPanelTabOrder")
        )
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
            agentPanelTabOrderStore.applyLoaded(
                serverId = currentServerId,
                order = order,
                source = "shared_members_sidebar_order_get",
                logger = raftLogger("SlockAgentPanelTabOrder")
            )
        }
    }

    LaunchedEffect(computersStore, currentServerId, active, membersRefreshKey) {
        computersStore.reconcileOwner()
        val serverId = currentServerId?.takeIf { active && it.isNotBlank() }
            ?: return@LaunchedEffect
        computersStore.ensureComputerPickerForServer(
            serverId = serverId,
            force = true,
            priority = DataRequestPriority.Visible
        )
    }

    // Task #150: a Retry tap forces the reload so it can never be satisfied by (or stuck behind)
    // what the failed attempt left in the shared members core.
    var membersRetryForce by remember { mutableStateOf(false) }
    // Keyed locally too: the parent's refresh key reaches this screen through a tab content lambda,
    // so a Retry must not depend on that lambda being recomposed to re-run the load.
    var membersRetryNonce by remember { mutableStateOf(0) }
    LaunchedEffect(membersRefreshKey, currentServerId, membersRetryNonce) {
        val force = membersRetryForce
        membersRetryForce = false
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
                // Was silent: a Retry that ends here never reached the network.
                raftLogger("SlockMembersPage")(
                    "members_page_load_skipped reason=server_required session=${session != null} " +
                        "serverSelected=${!currentServerId.isNullOrBlank()} serverInSession=${server != null}"
                )
                state = previousContent ?: SharedMembersUiState.Error("server_required")
                return@LaunchedEffect
            }
            val serverId = ServerId(currentServerId)
            val response =
                serverMembersStore.refreshMembers(
                    serverId,
                    DataRequestPriority.Visible,
                    force = force,
                    reason = if (force) "members_retry" else "members_page"
                )
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
            Column(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                // No head under the wide top bar, as the loaded list (MembersListContent).
                if (LocalRaftAppShellTopBar.current == null || onBack != null) {
                    MembersPageHeader(title = membersTitle, onBack = onBack)
                }
                RaftCenteredLoading()
        }
        is SharedMembersUiState.Error ->
            MembersPageErrorContent(
                message = membersLoadFailedMessage,
                code = current.code,
                onBack = onBack,
                onRetry = {
                    membersRetryForce = true
                    membersRetryNonce += 1
                    onRetry()
                }
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
                    raftScopedAgentStatusResolver(serverMembersStore, serverId)
                }
            val computersSlice by computersStore
                .computerPickerSliceFlow(serverId)
                .collectAsState(SharedComputersPickerSlice())
            // MEMBERS_COMPUTERS_ERROR_RETRY_WIRING_START
            val loadMachines: () -> Unit = {
                onForegroundActivity("load_machines")
                if (serverId.isNotBlank()) {
                    computersStore.dispatch(
                        SharedComputersIntent.EnsureComputerPicker(
                            serverId = serverId,
                            priority = DataRequestPriority.Visible
                        )
                    )
                }
            }
            val retryMachines: () -> Unit = {
                onForegroundActivity("retry_machines")
                if (serverId.isNotBlank()) {
                    computersStore.dispatch(
                        SharedComputersIntent.RefreshComputerPicker(
                            serverId = serverId,
                            priority = DataRequestPriority.Visible
                        )
                    )
                }
            }
            val createAgentRuntimeModelScope = rememberCoroutineScope()
            var createAgentRuntimeModels by remember(serverId) {
                mutableStateOf<Map<Pair<String, String>, SharedCreateAgentRuntimeModelCatalog>>(emptyMap())
            }
            var createAgentRuntimeModelLoadsInFlight by remember(serverId) {
                mutableStateOf<Set<Pair<String, String>>>(emptySet())
            }
            val loadRuntimeModels: (String, String, Boolean) -> Unit = { machineId, runtime, force ->
                onForegroundActivity("load_runtime_models")
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
            val inviteGuestFlagRevision by sharedStores.serverFeatureFlags.snapshotRevision.collectAsState()
            val inviteCanOfferGuest =
                remember(serverId, inviteGuestFlagRevision) {
                    serverId.isNotBlank() &&
                        sharedStores.serverFeatureFlags.isEnabled(ServerId(serverId), InviteHumanGuestFeatureFlagKey)
                }
            val inviteHumanDialog: (@Composable (() -> Unit) -> Unit)? =
                if (membersPayload.canInviteHumans && serverId.isNotBlank()) {
                    { onDismiss ->
                        SharedServerInviteHumanDialogHost(
                            serverId = serverId,
                            membersDataSource = settingsMembersDataSource,
                            canOfferGuest = inviteCanOfferGuest,
                            loadWebBaseUrl = {
                                onForegroundActivity("load_invite_link")
                                loadInviteWebBaseUrl()
                            },
                            copyToPasteboard = { url ->
                                onForegroundActivity("copy_invite_link")
                                bridge.copyToPasteboard(url)
                            },
                            onDismiss = onDismiss
                        )
                    }
                } else {
                    null
                }
            val createAgentSubmit: suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit> = { submit ->
                onForegroundActivity("create_agent_submit")
                val result =
                    createAgentCoordinator
                        .createAgent(submit.toCreateAgentRequest())
                        .recoverRejectedCreateAgentFormOrRetry(createAgentFormStore, serverId, submit) { retrySubmit ->
                            createAgentCoordinator.createAgent(retrySubmit.toCreateAgentRequest())
                        }
                if (result is DataResult.Content) {
                    val profile = createdAgentMemberProfile(result.data, submit, computersSlice.machines)
                    val nextState =
                        when (val latest = state) {
                            is SharedMembersUiState.Content ->
                                latest.copy(members = latest.members + profile)
                            else -> latest
                        }
                    state = nextState
                    (nextState as? SharedMembersUiState.Content)?.let(onContentLoaded)
                    // The row above is only a stand-in: replace it with the server's record. Re-entering
                    // the page seeds the store from this cached content, so without a forced refresh the
                    // stand-in was served as a cache hit indefinitely (task #148, HuaTuo 23bc896b).
                    val refreshed =
                        serverMembersStore.refreshMembers(
                            ServerId(serverId),
                            DataRequestPriority.Background,
                            force = true,
                            reason = "members_agent_created"
                        )
                    val latest = state as? SharedMembersUiState.Content
                    if (refreshed is DataResult.Content && latest != null) {
                        serverMembersStore.currentServerMembersContent(ServerId(serverId), fallbackServer = latest.server)
                            ?.let { snapshot ->
                                val content = latest.copy(server = snapshot.server, members = snapshot.members)
                                state = content
                                onContentLoaded(content)
                            }
                    }
                }
                when (result) {
                    is DataResult.Content -> DataResult.Content(Unit)
                    is DataResult.Empty -> DataResult.Empty(result.reason)
                    is DataResult.Error -> result
                    is DataResult.NoPermission -> result
                }
            }
            // Task #409: persist per-machine agent-group collapse for the
            // shared members page under the session user id.
            val membersAgentGroupCollapseStore = remember {
                build.raft.app.data.KeyValueMembersAgentMachineGroupCollapseStore(
                    build.raft.app.data.raftSharedPreferenceStore()
                )
            }
            MembersAdaptiveWorkspace(
                split = showMemberSplit,
                selected = selectedDetail != null,
                listPane = {
                    MembersRouteContent(
                        // Back to More on the phone (the loading and error states already had it).
                        onBack = onBack,
                        selectedIdentity = selectedDetail?.identity,
                        selectedLocalServerId = ServerId(serverId),
                        payload = renderMembersPayload,
                        refreshing = refreshing,
                        paneLayout = paneLayout,
                        onAddHuman = { onForegroundActivity("add_human") },
                        agentStatusResolver = agentStatusResolver,
                        onOpenMemberDetail = { member ->
                            onForegroundActivity("open_member")
                            if (showMemberSplit) {
                                // Resolve local fallback now, never from a later selected server.
                                val identity = MemberDetailIdentity(member.id, member.kind, member.serverId ?: ServerId(serverId))
                                selectedDetail = membersPayload.toMemberDetailPageData(identity)?.toRaftMemberDetailPayload()
                            } else routeScope.launch {
                                val identity = MemberDetailIdentity(member.id, member.kind, member.serverId)
                                val accessMode = identity.memberDetailAccessMode(serverId)
                                val launchPayload =
                                    if (
                                        member.kind == MemberKind.Agent &&
                                            accessMode.allowsPrivateAgentSurfaces &&
                                            (agentPanelTabOrderState.serverId != serverId || !agentPanelTabOrderState.loaded)
                                    ) {
                                        loadAgentPanelTabOrderForSharedMembers()
                                            ?.also { order ->
                                                agentPanelTabOrderStore.applyLoaded(
                                                    serverId = serverId,
                                                    order = order,
                                                    source = "shared_members_detail_open_preload",
                                                    logger = raftLogger("SlockAgentPanelTabOrder")
                                                )
                                            }
                                            ?.let { order -> membersPayload.copy(agentPanelTabOrder = order) }
                                            ?: membersPayload
                                    } else {
                                        membersPayload
                                    }
                                val pageData =
                                    launchPayload.toMemberDetailPageData(
                                        identity
                                    )
                                if (pageData == null) {
                                    bridge.toast(openProfileFailedMessage)
                                    bridge.log("members_detail_open_skip reason=missing_member_id source=app_shell")
                                } else {
                                    val seedMember =
                                        member.copy(
                                            serverId =
                                                member.serverId
                                                    ?: serverId.trim().takeIf { it.isNotBlank() }?.let(::ServerId)
                                        )
                                    agentDetailStore.dispatch(SharedAgentDetailIntent.SeedDetailFromMember(seedMember))
                                    pager.openRaftPage(
                                        pageName = MEMBER_DETAIL_PAGE,
                                        pageData = pageData,
                                        storeScopeOverride = storeScope,
                                        onMissingScope = {
                                            bridge.toast(openProfileFailedMessage)
                                            bridge.log("members_detail_open_skip reason=missing_shell_scope source=app_shell")
                                        }
                                    )
                                }
                            }
                        },
                        canCreateAgents = membersPayload.canCreateAgents,
                        createAgent = MembersCreateAgentInputs(
                            createAgentSubmit = createAgentSubmit,
                            createAgentMachines = computersSlice.machines,
                            createAgentMachinesLoading = computersSlice.initialLoadInFlight,
                            createAgentMachinesErrorMessage = computersSlice.initialLoadError?.userFacingMessage(errorStrings),
                            createAgentCurrentAgentCount = current.members.count { it.kind == MemberKind.Agent },
                            createAgentLoadMachines = loadMachines,
                            createAgentRetryMachines = retryMachines,
                            createAgentRuntimeModelsByMachineAndRuntime = createAgentRuntimeModels,
                            createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight,
                            createAgentLoadRuntimeModels = loadRuntimeModels,
                            createAgentFormStore = createAgentFormStore,
                            createAgentServerId = serverId,
                            createAgentTakenAgentNames = current.members.takenAgentNames()
                        ),
                        inviteHuman = MembersInviteHumanInputs(inviteHumanDialog = inviteHumanDialog),
                        bottomContentPadding = bottomContentPadding,
                        scrollToTopRequest = scrollToTopRequest,
                        initialListState = initialListState,
                        onListStateSnapshot = onListStateSnapshot,
                        // Task #409: persist per-machine collapse under the session user id.
                        currentUserId = current.currentUserId,
                        agentGroupCollapseStore = membersAgentGroupCollapseStore,
                        // A pull used to call only onRetry(), which bumps the parent's refresh key; that key
                        // reaches this screen through a tab content lambda that need not recompose, so the
                        // load often never re-ran and `refreshing` never went true -> false (the indicator
                        // hung or never turned). A pull now drives the local forced reload like Retry does
                        // (members) and refreshes the computer list; the load always clears `refreshing`.
                        onRefresh = {
                            onForegroundActivity("refresh")
                            raftLogger("SlockMembersPage")("members_pull_refresh serverId=$serverId")
                            refreshing = true
                            membersRetryForce = true
                            membersRetryNonce += 1
                            retryMachines()
                            onRetry()
                        }
                    )
                },
                detailPane = {
                    val detail = selectedDetail
                    if (detail == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(Res.string.members_select_detail), color = RaftColor.TextMutedGray)
                        }
                    } else if (active) {
                        // Dispose all detail-local effects on identity changes. Canonical
                        // store selectors still enforce server/member/access ownership.
                        key(storeScope, session?.user?.id, currentServerId, detail.identity) {
                            MemberDetailPageContent(
                                pageVisibleTick = 0,
                                embeddedPayload = detail,
                                storeScopeOverride = storeScope,
                                onClose = { selectedDetail = null },
                            )
                        }
                    }
                },
            )
            // MEMBERS_COMPUTERS_ERROR_RETRY_WIRING_END
        }
    }
}

internal fun buildHostMembersPayload(
    server: ServerSummary,
    timeContext: RaftTimeContext?,
    agentPanelTabOrder: List<String> = emptyList()
): RaftMembersPayload =
    RaftMembersPayload(
        instanceId = server.id.value,
        title = "Members",
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: RaftTimeFormat24Hour,
        agentPanelTabOrder = agentPanelTabOrder,
        // Product mapping for now: create-agent follows server admin/settings permission
        // until the API exposes a dedicated capability.
        canCreateAgents = server.permissions.canManageSettings,
        canInviteHumans = server.permissions.canInviteMember
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
    val agentStatus =
        normalizedAgentRuntimeStatus(
            activity = profile.string("agentStatus", "activity") ?: string("agentStatus", "activity"),
            lifecycleStatus = profile.string("status") ?: string("status")
        )
    return MemberProfile(
        id = MemberId(id),
        displayName = displayName,
        handle = handle,
        kind = kind,
        description = profile.string("description") ?: string("description"),
        roleDescription = profile.string("roleDescription", "role") ?: string("roleDescription", "role"),
        avatarUrl = avatarWireValue?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        avatar = AvatarReference.fromWireValue(avatarWireValue),
        avatarLabel = displayName.toRaftAvatarLabel(),
        presence = (profile.string("presence", "availability") ?: string("presence", "availability")).toSharedPresence(),
        agentStatus = agentStatus,
        isExternal =
            isExternalAgent(
                external = profile.boolean("external") ?: boolean("external"),
                runtime = profile.string("runtime") ?: string("runtime")
            ),
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

/**
 * task #337: projects the shared home snapshot's direct messages into [RaftHomeConversationItem]
 * for the Slack-nav DMs tab, reusing the exact projection chain SharedHomeScreen uses
 * (directory identity + draft projections). Null-safe: empty until home content is ready.
 */
@Composable
private fun rememberProjectedDirectMessageItems(
    state: SharedHomeState,
    conversationIdentityProvider: SharedConversationIdentityProvider?,
    draftPersistSlice: SharedThreadComposerDraftPersistSlice?,
): List<RaftHomeConversationItem> {
    val snapshot = (state.content as? SharedHomeContentState.Content)?.snapshot
    val restoredRowHints = (state.content as? SharedHomeContentState.Content)?.displayRowHints().orEmpty()
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
        remember(snapshot?.channels, snapshot?.directMessages) {
            val channels = snapshot?.channels.orEmpty()
            val directMessages = snapshot?.directMessages.orEmpty()
            (channels + directMessages).map { it.threadId.value }
        }
    val conversationDirectoryFlow =
        remember(conversationIdentityProvider, snapshot?.server?.id, conversationDirectoryIds) {
            val serverId = snapshot?.server?.id
            if (conversationIdentityProvider == null || serverId == null) {
                EmptyConversationDirectoryProjectionFlow
            } else {
                conversationIdentityProvider.projectionFlow(serverId, conversationDirectoryIds)
            }
        }
    val conversationDirectoryProjections by conversationDirectoryFlow.collectAsState(emptyMap())
    val conversationRowProjector =
        remember(conversationDirectoryProjections, draftProjection, restoredRowHints) {
            SharedHomeConversationRowProjector(
                directoryProjections = conversationDirectoryProjections,
                draftProjection = draftProjection,
                restoredRowHints = restoredRowHints
            )
        }
    return remember(snapshot?.directMessages, conversationRowProjector) {
        snapshot
            ?.directMessages
            ?.map { conversation -> conversationRowProjector.project(conversation) }
            .orEmpty()
    }
}

@Composable
private fun SharedHomeScreen(
    bridge: build.raft.app.base.BridgeModule,
    state: SharedHomeState,
    active: Boolean,
    scrollToTopRequest: Int = 0,
    sessionReady: Boolean = true,
    conversationKindFilter: ConversationKind? = null,
    draftPersistSlice: SharedThreadComposerDraftPersistSlice? = null,
    conversationIdentityProvider: SharedConversationIdentityProvider? = null,
    activityUnreadCount: Int = 0,
    activityTotalCount: Int = 0,
    serverActivityExpectedOwner: ReadReceiptOwner? = null,
    savedCount: Int = 0,
    prefersChineseCommunity: Boolean = false,
    serverDrawerEnabled: Boolean = false,
    homeServerAvatarEnabled: Boolean = false,
    /** Wide layout: the top bar holds the server switcher and the bell, so the list has no head. */
    serverSwitcherInTopBar: Boolean = false,
    /** The phone bottom bar is on screen: the list drops its Search / Activity rows. */
    searchAndActivityOnShellNav: Boolean = false,
    /** Server flag `thread_unread_badge_v0`: channel rows show followed-thread unread (web parity). */
    threadUnreadBadgeEnabled: Boolean = false,
    /** Server flag `sidebar_mark_all_read_v0`: with the thread badge on, rows offer "Mark All as Read". */
    sidebarMarkAllReadEnabled: Boolean = false,
    onIntent: (SharedHomeIntent) -> Unit,
    onNotificationDismiss: (String) -> Unit = { id -> onIntent(SharedHomeIntent.DismissNotification(id)) },
    onAppUpdateOpen: (() -> Unit)? = null,
    externalFocusLossDismissEpoch: Int = 0,
    selectedConversationId: String? = null,
    onSelectedConversationCleared: () -> Unit = {},
    onOpenSearch: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenComputers: () -> Unit = {},
    onOpenUrl: (String) -> Unit = bridge::openUrl,
    onOpenServerSelection: () -> Unit,
    /** Server switcher "Invite Human" for the given server; null hides the entry. */
    onInviteHuman: ((serverId: String) -> Unit)? = null,
    onCreateChannel: () -> Unit,
    /** Joint Channels section "+"; shown only with federateChannels (owner/admin), as web gates it. */
    onCreateJointChannel: (() -> Unit)? = null,
    onOpenConversation: (ThreadLaunchConversationIdentity) -> Unit,
    serverMembersStore: SharedServerMembersStore? = null,
    channelRepository: ChannelRepository? = null,
    jointInviteStore: build.raft.app.data.SharedJointInviteStore? = null,
    topSafeAreaOverride: Dp? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    listPositionServerId: String? = null,
    initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    onListPositionSnapshot: (String?, AppShellLazyListPosition) -> Unit = { _, _ -> },
    onContentComposeCommitted: (String) -> Unit = {}
) {
    // keepItemAlive keeps hidden pages composed: a DisposableEffect
    // (onDispose) downstream can still fire a stale snapshot while the page is
    // off-screen. Gate through rememberUpdatedState so the check reads the
    // current `active` flag, not the one captured at callback creation.
    val latestActive = rememberUpdatedState(active)
    val conversationUnavailableMessage = stringResource(Res.string.home_error_conversation_unavailable)
    val directMessageUnavailableMessage = stringResource(Res.string.home_error_direct_message_unavailable)
    val openDirectMessageFailedMessage = stringResource(Res.string.home_error_open_direct_message)
    val openDirectMessageFailedPrefix = stringResource(Res.string.home_error_open_direct_message_failed_prefix)
    val openDirectMessagePermissionMessage = stringResource(Res.string.home_error_open_direct_message_permission)
    val pinnedMemberOpenScope = rememberCoroutineScope()
    val homeContentSummary = sharedHomeContentRenderSummary(state.content)
    val shouldStartHome = sharedHomeShouldStartOnActive(active, sessionReady, state)
    val jointInvites =
        rememberHomeJointInvites(
            bridge = bridge,
            store = jointInviteStore,
            content = state.content,
            active = active,
            onIntent = onIntent,
            onOpenConversation = onOpenConversation,
        )
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
    val serverDrawerSections =
        remember(
            state.serverDrawer,
            state.serverUnreadCounts,
            state.serverActivityUnread,
            serverActivityExpectedOwner,
        ) {
            state.serverDrawer.toRaftHomeServerDrawerSections(
                serverUnreadCounts = state.serverUnreadCounts,
                activityExpectedOwner = serverActivityExpectedOwner,
                activityProjection = state.serverActivityUnread,
            )
        }
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
                    searchAndActivityOnShellNav = searchAndActivityOnShellNav,
                    scrollToTopRequest = scrollToTopRequest,
                    serverSwitcherExpanded = serverSwitcherExpanded,
                    serverDrawerEnabled = serverDrawerEnabled,
                    homeServerAvatarEnabled = homeServerAvatarEnabled,
                    serverSwitcherInTopBar = serverSwitcherInTopBar,
                    serverDrawerSections = serverDrawerSections,
                    serverUnreadCounts = state.serverUnreadCounts,
                    serverActivityExpectedOwner = serverActivityExpectedOwner,
                    serverActivityProjection = state.serverActivityUnread,
                    prefersChineseCommunity = prefersChineseCommunity,
                    topSafeAreaOverride = topSafeAreaOverride,
                    bottomContentPadding = bottomContentPadding,
                    loadingSkeletons = true,
                    onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                    onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                    onServerDrawerSectionToggle = { serverId ->
                        onIntent(SharedHomeIntent.ToggleServerDrawerSection(ServerId(serverId)))
                    },
                    onServerDrawerRetry = { serverId ->
                        onIntent(SharedHomeIntent.RetryServerDrawerSection(ServerId(serverId)))
                    },
                    onServerSelected = { serverId ->
                        onSelectedConversationCleared()
                        onIntent(SharedHomeIntent.SelectServer(serverId))
                    },
                    onServerOrderChange = { order ->
                        onIntent(SharedHomeIntent.ReorderServers(order))
                    },
                    onCreateServerClick = onOpenServerSelection,
                    onInviteHuman = onInviteHuman,
                    onCreateChannelClick = onCreateChannel
                )
            } else {
                RaftCenteredLoading()
            }
        }
        is SharedHomeContentState.Empty ->
            SharedHomeSessionShell(
                session = current.session,
                searchAndActivityOnShellNav = searchAndActivityOnShellNav,
                scrollToTopRequest = scrollToTopRequest,
                serverSwitcherExpanded = serverSwitcherExpanded,
                serverDrawerEnabled = serverDrawerEnabled,
                homeServerAvatarEnabled = homeServerAvatarEnabled,
                serverSwitcherInTopBar = serverSwitcherInTopBar,
                serverDrawerSections = serverDrawerSections,
                serverUnreadCounts = state.serverUnreadCounts,
                serverActivityExpectedOwner = serverActivityExpectedOwner,
                serverActivityProjection = state.serverActivityUnread,
                prefersChineseCommunity = prefersChineseCommunity,
                topSafeAreaOverride = topSafeAreaOverride,
                bottomContentPadding = bottomContentPadding,
                onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                onServerDrawerSectionToggle = { serverId ->
                    onIntent(SharedHomeIntent.ToggleServerDrawerSection(ServerId(serverId)))
                },
                onServerDrawerRetry = { serverId ->
                    onIntent(SharedHomeIntent.RetryServerDrawerSection(ServerId(serverId)))
                },
                onServerSelected = { serverId ->
                    onSelectedConversationCleared()
                    onIntent(SharedHomeIntent.SelectServer(serverId))
                },
                onServerOrderChange = { order ->
                    onIntent(SharedHomeIntent.ReorderServers(order))
                },
                onCreateServerClick = onOpenServerSelection,
                onInviteHuman = onInviteHuman,
                onCreateChannelClick = onCreateChannel
            )
        is SharedHomeContentState.Error ->
            if (current.session != null) {
                SharedHomeSessionShell(
                    session = current.session,
                    searchAndActivityOnShellNav = searchAndActivityOnShellNav,
                    scrollToTopRequest = scrollToTopRequest,
                    serverSwitcherExpanded = serverSwitcherExpanded,
                    serverDrawerEnabled = serverDrawerEnabled,
                    homeServerAvatarEnabled = homeServerAvatarEnabled,
                    serverSwitcherInTopBar = serverSwitcherInTopBar,
                    serverDrawerSections = serverDrawerSections,
                    serverUnreadCounts = state.serverUnreadCounts,
                    serverActivityExpectedOwner = serverActivityExpectedOwner,
                    serverActivityProjection = state.serverActivityUnread,
                    prefersChineseCommunity = prefersChineseCommunity,
                    topSafeAreaOverride = topSafeAreaOverride,
                    bottomContentPadding = bottomContentPadding,
                    inlineError =
                        localizedSharedHomeErrorDisplay(current.code, serverMessage = current.serverMessage),
                    onInlineErrorAction = { onIntent(SharedHomeIntent.Refresh) },
                    onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                    onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                    onServerDrawerSectionToggle = { serverId ->
                        onIntent(SharedHomeIntent.ToggleServerDrawerSection(ServerId(serverId)))
                    },
                    onServerDrawerRetry = { serverId ->
                        onIntent(SharedHomeIntent.RetryServerDrawerSection(ServerId(serverId)))
                    },
                    onServerSelected = { serverId ->
                        onSelectedConversationCleared()
                        onIntent(SharedHomeIntent.SelectServer(serverId))
                    },
                    onServerOrderChange = { order ->
                        onIntent(SharedHomeIntent.ReorderServers(order))
                    },
                    onCreateServerClick = onOpenServerSelection,
                    onInviteHuman = onInviteHuman,
                    onCreateChannelClick = onCreateChannel
                )
            } else {
                val error =
                    localizedSharedHomeErrorDisplay(current.code, serverMessage = current.serverMessage)
                PageErrorContent(
                    title = "Home",
                    subtitle = "Workspace",
                    iconName = RaftLucideIconName.House,
                    message = error.title,
                    code = current.code,
                    onRetry = { onIntent(SharedHomeIntent.Refresh) }
                )
            }
        is SharedHomeContentState.Content -> {
            val snapshot = current.snapshot
            SideEffect {
                if (active) {
                    onContentComposeCommitted(snapshot.server.id.value)
                }
            }
            val agentStatusResolver =
                remember(serverMembersStore, snapshot.server.id.value) {
                    raftScopedAgentStatusResolver(serverMembersStore, snapshot.server.id.value)
                }
            val memberAvatarResolver =
                remember(serverMembersStore, snapshot.server.id.value) {
                    raftMemberAvatarResolver(serverMembersStore, snapshot.server.id.value)
                }
            val serverMembersState by (serverMembersStore?.state ?: flowOf(SharedServerMembersState()))
                .collectAsState(SharedServerMembersState())
            val pinnedMemberProfileResolver: (String) -> RaftHomePinnedMemberItem? =
                remember(serverMembersState, snapshot.server.id) {
                    { memberId ->
                        serverMembersState
                            .memberProfile(snapshot.server.id, memberId)
                            ?.toRaftHomePinnedMemberItem()
                    }
                }
            LaunchedEffect(serverMembersStore, snapshot.server.id) {
                bridge.log(
                    "shared_home_avatar_chain serverId=${snapshot.server.id.value} " +
                        "memberStore=${serverMembersStore != null} resolver=member_ssot"
                )
            }
            LaunchedEffect(serverMembersStore, snapshot.server.id, current.contentRefreshing, active) {
                if (active && !current.contentRefreshing) {
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
            val restoredRowHints = current.displayRowHints()
            // Store-owned followed-thread unread, folded per parent channel; gated like the web sidebar.
            val threadUnreadByParent =
                remember(snapshot.followedThreadUnread, threadUnreadBadgeEnabled) {
                    if (threadUnreadBadgeEnabled) {
                        snapshot.followedThreadUnread.foldThreadUnreadByParent()
                    } else {
                        emptyMap<ChannelId, HomeParentThreadUnread>()
                    }
                }
            val conversationRowProjector =
                remember(conversationDirectoryProjections, draftProjection, restoredRowHints, threadUnreadByParent) {
                    SharedHomeConversationRowProjector(
                        directoryProjections = conversationDirectoryProjections,
                        draftProjection = draftProjection,
                        restoredRowHints = restoredRowHints,
                        threadUnreadByParent = threadUnreadByParent
                    )
                }
            val servers =
                remember(
                    current.session.servers,
                    snapshot.server,
                    state.serverUnreadCounts,
                    state.serverActivityUnread,
                    serverActivityExpectedOwner,
                ) {
                    sharedHomeSwitcherItems(
                        session = current.session,
                        selectedServer = snapshot.server,
                        serverUnreadCounts = state.serverUnreadCounts,
                        activityExpectedOwner = serverActivityExpectedOwner,
                        activityProjection = state.serverActivityUnread,
                    )
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
            val notificationItems = jointInvites.items + state.notifications.map { it.toHomeNotificationItem() }
            val conversationsByThreadId =
                remember(snapshot.channels, snapshot.directMessages) {
                    (snapshot.channels + snapshot.directMessages).associateBy { it.threadId.value }
                }
            val homeActivityUnreadCount = homeActivitySidebarUnreadCount(
                activityUnreadCount = activityUnreadCount
            )
            val homeShellLabels = RaftHomeShellLabels.localized()
            // Web "Hide Empty Sections": a device-local display preference.
            val hideEmptySectionsStore = remember {
                runCatching {
                    build.raft.app.data.KeyValueRaftSidebarHideEmptySectionsStore(
                        build.raft.app.data.raftSharedPreferenceStore()
                    )
                }.getOrNull()
            }
            var hideEmptySections by remember(hideEmptySectionsStore) {
                mutableStateOf(
                    hideEmptySectionsStore?.read() ?: build.raft.app.data.RaftSidebarHideEmptySectionsDefault
                )
            }
            RaftStructuralHomeShell(
                currentServerName = snapshot.server.name,
                currentServerId = snapshot.server.id.value,
                servers = servers,
                // task #94: the Slack-aligned Home tab is channels-only and the DMs tab is
                // direct-message-only; null keeps the legacy merged list.
                channels =
                    if (conversationKindFilter == ConversationKind.DirectMessage) {
                        emptyList()
                    } else {
                        channelItems
                    },
                directMessages =
                    if (conversationKindFilter == ConversationKind.Channel) {
                        emptyList()
                    } else {
                        directMessageItems
                    },
                selectedChannelId = selectedConversationId,
                inboxUnreadCount = homeActivityUnreadCount,
                inboxTotalCount = homeActivitySidebarTotalCount(
                    activityUnreadCount = homeActivityUnreadCount,
                    activityTotalCount = activityTotalCount
                ),
                savedCount = savedCount,
                serverSwitcherExpanded = serverSwitcherExpanded,
                serverDrawerEnabled = serverDrawerEnabled,
                homeServerAvatarEnabled = homeServerAvatarEnabled,
                serverSwitcherInTopBar = serverSwitcherInTopBar,
                serverDrawerSections = serverDrawerSections,
                pendingServerId = serverSwitchState.pendingServerId,
                prefersChineseCommunity = prefersChineseCommunity,
                topSafeAreaOverride = topSafeAreaOverride,
                activitySelected = state.selectedTopAction == SharedHomeTopAction.Activity,
                searchSelected = state.selectedTopAction == SharedHomeTopAction.Search,
                savedSelected = state.selectedTopAction == SharedHomeTopAction.Saved,
                notifications = notificationItems,
                contentRefreshing = sharedHomeHeaderRefreshing(state),
                unreadStale = state.unreadStale,
                channelOrder = snapshot.sidebarOrder?.channelOrder.orEmpty(),
                channelSortMode = snapshot.sidebarOrder?.channelSortMode ?: "manual",
                jointChannelSortMode = snapshot.sidebarOrder?.jointChannelSortMode ?: "manual",
                dmOrder = snapshot.sidebarOrder?.dmOrder.orEmpty(),
                dmSortMode = snapshot.sidebarOrder?.dmSortMode ?: "manual",
                pinnedChannelIds = snapshot.sidebarOrder?.pinnedChannelIds.orEmpty(),
                pinnedAgentIds = snapshot.sidebarOrder?.pinnedAgentIds.orEmpty(),
                pinnedOrder = snapshot.sidebarOrder?.pinnedOrder.orEmpty(),
                pinnedItems = snapshot.sidebarOrder?.pinned.orEmpty(),
                pinnedSortMode = snapshot.sidebarOrder?.pinnedSortMode ?: "manual",
                hiddenDmIds = snapshot.sidebarOrder?.hiddenDmIds.orEmpty(),
                customSections = snapshot.sidebarOrder?.customSections.orEmpty(),
                sectionOrder = snapshot.sidebarOrder?.sectionOrder.orEmpty(),
                sectionPlacements = snapshot.sidebarOrder?.sectionPlacements.orEmpty(),
                hideEmptySections = hideEmptySections,
                canArchiveChannels = snapshot.server.permissions.canManageSettings,
                showBottomBar = false,
                searchAndActivityOnShellNav = searchAndActivityOnShellNav,
                externalFocusLossDismissEpoch = externalFocusLossDismissEpoch,
                bottomContentPadding = bottomContentPadding,
                outerPadding = 0.dp,
                labels = homeShellLabels,
                onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                onServerDrawerSectionToggle = { serverId ->
                    onIntent(SharedHomeIntent.ToggleServerDrawerSection(ServerId(serverId)))
                },
                onServerDrawerRetry = { serverId ->
                    onIntent(SharedHomeIntent.RetryServerDrawerSection(ServerId(serverId)))
                },
                onServerSelected = { serverId ->
                    onSelectedConversationCleared()
                    onIntent(SharedHomeIntent.SelectServer(serverId))
                },
                onServerOrderChange = { order ->
                    onIntent(SharedHomeIntent.ReorderServers(order))
                },
                onCreateServerClick = onOpenServerSelection,
                onInviteHumanClick =
                    homeServerSwitcherInviteClick(snapshot.server, onInviteHuman) {
                        onIntent(SharedHomeIntent.DismissServerSwitcher)
                    },
                onCreateChannelClick = onCreateChannel,
                onCreateJointChannelClick = onCreateJointChannel?.takeIf { snapshot.server.permissions.canManageSettings },
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
                    val jointInviteId = notification.id.jointInviteIdFromHomeNotificationIdOrNull()
                    when {
                        jointInviteId != null ->
                            jointInviteStore?.dispatch(build.raft.app.data.SharedJointInviteIntent.Accept(jointInviteId))
                        notification.id.isAppUpdateHomeNotificationId() && onAppUpdateOpen != null ->
                            onAppUpdateOpen()
                        notification.id.isMachineHomeNotificationId() ->
                            onOpenComputers()
                        notification.targetId?.startsWith("http://") == true ||
                            notification.targetId?.startsWith("https://") == true -> {
                            notification.targetId?.let(onOpenUrl)
                        }
                        else -> Unit
                    }
                },
                onNotificationDismiss = { notification ->
                    val jointInviteId = notification.id.jointInviteIdFromHomeNotificationIdOrNull()
                    if (jointInviteId != null) {
                        jointInviteStore?.dispatch(build.raft.app.data.SharedJointInviteIntent.Dismiss(jointInviteId))
                    } else {
                        onNotificationDismiss(notification.id)
                    }
                },
                onRefresh = {
                    onIntent(SharedHomeIntent.Refresh)
                    jointInvites.refresh()
                },
                onChannelClick = { threadId ->
                    val conversation = conversationsByThreadId[threadId]
                    if (conversation == null) {
                        bridge.toast(conversationUnavailableMessage)
                    } else {
                        onOpenConversation(conversation.toThreadLaunchConversationIdentity())
                        onIntent(SharedHomeIntent.SelectConversation(threadId))
                    }
                },
                onPinnedMemberClick = { identity ->
                    val repository = channelRepository
                    if (repository == null) {
                        bridge.toast(directMessageUnavailableMessage)
                    } else {
                        pinnedMemberOpenScope.launch {
                            when (val result = repository.openPinnedMemberDirectMessage(identity)) {
                                is DataResult.Content -> {
                                    val conversation = result.data.conversation
                                    onOpenConversation(conversation.toThreadLaunchConversationIdentity())
                                    onIntent(SharedHomeIntent.SelectConversation(conversation.threadId.value))
                                    onIntent(SharedHomeIntent.Refresh)
                                }
                                is DataResult.Empty -> bridge.toast(openDirectMessageFailedMessage)
                                is DataResult.Error ->
                                    bridge.toast(openDirectMessageFailedPrefix + result.error.code)
                                is DataResult.NoPermission -> bridge.toast(openDirectMessagePermissionMessage)
                            }
                        }
                    }
                },
                onChannelLongPress = { _, _ -> },
                onPinnedSortModeChange = { mode ->
                    onIntent(SharedHomeIntent.SetPinnedSortMode(mode))
                },
                onChannelSortModeChange = { mode ->
                    onIntent(SharedHomeIntent.SetChannelSortMode(mode))
                },
                onJointChannelSortModeChange = { mode ->
                    onIntent(SharedHomeIntent.SetJointChannelSortMode(mode))
                },
                onHideEmptySectionsChange = { hide ->
                    hideEmptySections = hide
                    hideEmptySectionsStore?.write(hide)
                },
                onSidebarOrderChange = { target, order ->
                    onIntent(target.toReorderSidebarIntent(order))
                },
                onSidebarSectionSortModeChange = { sectionId, mode ->
                    onIntent(SharedHomeIntent.SetSidebarSectionSortMode(sectionId, mode))
                },
                onSidebarSectionCreate = { request ->
                    onIntent(
                        SharedHomeIntent.CreateSidebarSection(
                            name = request.name,
                            emoji = request.emoji,
                            moveConversationId = request.moveConversationId,
                            insertBeforeSectionId = request.insertBeforeSectionId
                        )
                    )
                },
                onSidebarSectionRename = { sectionId, name, emoji ->
                    onIntent(SharedHomeIntent.RenameSidebarSection(sectionId, name, emoji))
                },
                onSidebarSectionDelete = { sectionId ->
                    onIntent(SharedHomeIntent.DeleteSidebarSection(sectionId))
                },
                onMoveConversationToSection = { conversationId, sectionId ->
                    onIntent(SharedHomeIntent.MoveConversationToSection(conversationId, sectionId))
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
                onMarkConversationReadAll =
                    if (threadUnreadBadgeEnabled && sidebarMarkAllReadEnabled) {
                        { conversationId -> onIntent(SharedHomeIntent.MarkConversationReadAll(conversationId)) }
                    } else {
                        null
                    },
                onChannelActivityMuteToggle = { conversationId, muted ->
                    onIntent(SharedHomeIntent.SetChannelActivityMuted(conversationId, muted))
                },
                onCloseDirectMessage = { conversationId ->
                    onIntent(SharedHomeIntent.CloseDirectMessage(conversationId))
                },
                onArchiveChannel = { conversationId ->
                    onIntent(SharedHomeIntent.ArchiveChannel(conversationId))
                },
                agentStatusResolver = agentStatusResolver,
                memberAvatarResolver = memberAvatarResolver,
                pinnedMemberProfileResolver = pinnedMemberProfileResolver,
                diagnosticsLogger = bridge::log,
                scrollToTopRequest = scrollToTopRequest,
                listPositionServerId = listPositionServerId ?: snapshot.server.id.value,
                initialListPosition = initialListPosition,
                onListPositionSnapshot = { serverId, position ->
                    if (latestActive.value) {
                        onListPositionSnapshot(serverId, position)
                    }
                }
            )
        }
    }
}

internal const val DiagnosticsFeedbackComposerSeedText: String =
    "Diagnostics zip attached.\n\n<!-- Describe what happened here. -->"

internal fun SettingsDiagnosticsFeedbackDraft.diagnosticsComposerMessage(
    seedText: String = DiagnosticsFeedbackComposerSeedText
): String = seedText

@Composable
private fun SharedCreateChannelDialog(
    submitting: Boolean,
    membersLoading: Boolean,
    members: List<MemberProfile>,
    errorMessage: String?,
    initialName: String = "",
    initialDescription: TextFieldValue = TextFieldValue(""),
    initialVisibility: SharedCreateChannelVisibility = SharedCreateChannelVisibility.Public,
    initialSelectedMemberKeys: Set<RaftMemberPickerKey> = emptySet(),
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    fixtureStatusFallbackEnabled: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (String, String?, String, List<String>, List<String>) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var visibility by remember(initialVisibility) { mutableStateOf(initialVisibility) }
    // Caller-local TextFieldValue owns the member-search editor for this
    // dialog's mount; filtering reads the String projection (task #130 Phase D).
    var memberSearch by remember { mutableStateOf(TextFieldValue()) }
    var selectedMemberKeys by remember(initialSelectedMemberKeys) {
        mutableStateOf(initialSelectedMemberKeys)
    }
    val createChannelListState = rememberLazyListState()
    val createChannelScrollScope = rememberCoroutineScope()
    var createChannelViewport by remember { mutableStateOf<Rect?>(null) }
    val createChannelScrollCoordinator = remember {
        RaftFocusedEditorScrollRequestCoordinator()
    }
    DisposableEffect(createChannelScrollCoordinator) {
        onDispose { createChannelScrollCoordinator.invalidate() }
    }
    var memberSearchFocused by remember { mutableStateOf(false) }

    fun scrollToMemberPicker() {
        val requestGeneration = createChannelScrollCoordinator.begin()
        val job = createChannelScrollScope.launch {
            raftUnifiedDialogScrollToTargetItem(
                keyboardHeight = 1f,
                targetItemIndex = 3,
                targetItemScrollOffset = 0,
                awaitRelayoutFrame = { withFrameNanos { } },
                scrollToItem = { index, scrollOffset ->
                    createChannelListState.scrollToItem(index, scrollOffset)
                },
                isScrollInProgress = { createChannelListState.isScrollInProgress },
                isRequestCurrent = { createChannelScrollCoordinator.isCurrent(requestGeneration) },
                isTargetActive = { memberSearchFocused }
            )
        }
        createChannelScrollCoordinator.attach(job)
    }

    val trimmedName = name.trim()
    val nameError =
        if (trimmedName.isNotEmpty() && !trimmedName.isSubmittableChannelName()) {
            stringResource(Res.string.channel_create_error_name_format)
        } else {
            null
        }
    val canSubmit = trimmedName.isSubmittableChannelName() && !submitting
    val selectableMembers = members.filter { it.kind == MemberKind.Human || it.kind == MemberKind.Agent }
    RaftUnifiedDialog(
        title = stringResource(Res.string.channel_create_title),
        dismissEnabled = !submitting,
        keyboardAvoidanceEnabled = true,
        onDismiss = onDismiss
    ) {
        val focusedEditorBounds = rememberRaftFocusedInputBoundsResolver()
        val dialogKeyboardHeightChange = LocalRaftDialogKeyboardHeightChange.current

        fun keepFocusedCreateChannelEditorVisible(params: KeyboardParams) {
            dialogKeyboardHeightChange(params)
            val requestGeneration = createChannelScrollCoordinator.begin()
            if (params.height <= 0f) return
            val job = createChannelScrollScope.launch {
                if (memberSearchFocused) {
                    raftUnifiedDialogScrollToTargetItem(
                        keyboardHeight = params.height,
                        targetItemIndex = 3,
                        targetItemScrollOffset = 0,
                        awaitRelayoutFrame = { withFrameNanos { } },
                        scrollToItem = { index, scrollOffset ->
                            createChannelListState.scrollToItem(index, scrollOffset)
                        },
                        isScrollInProgress = { createChannelListState.isScrollInProgress },
                        isRequestCurrent = {
                            createChannelScrollCoordinator.isCurrent(requestGeneration)
                        },
                        isTargetActive = { memberSearchFocused }
                    )
                } else {
                    raftUnifiedDialogKeepFocusedEditorVisible(
                        keyboardHeight = params.height,
                        awaitRelayoutFrame = { withFrameNanos { } },
                        focusedBounds = focusedEditorBounds,
                        viewportBounds = { createChannelViewport },
                        currentScrollPosition = {
                            RaftUnifiedDialogListScrollPosition(
                                index = createChannelListState.firstVisibleItemIndex,
                                scrollOffset = createChannelListState.firstVisibleItemScrollOffset
                            )
                        },
                        scrollToItem = { index, scrollOffset ->
                            createChannelListState.scrollToItem(index, scrollOffset)
                        },
                        isScrollInProgress = { createChannelListState.isScrollInProgress },
                        isRequestCurrent = {
                            createChannelScrollCoordinator.isCurrent(requestGeneration)
                        },
                        isTargetActive = { !memberSearchFocused }
                    )
                }
            }
            createChannelScrollCoordinator.attach(job)
        }

        CompositionLocalProvider(
            LocalRaftDialogKeyboardHeightChange provides ::keepFocusedCreateChannelEditorVisible
        ) {
            LazyColumn(
                state = createChannelListState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .onGloballyPositioned { createChannelViewport = it.boundsInRoot() },
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            item {
                RaftBrutalTextField(
                    label = stringResource(Res.string.channel_create_label_name),
                    required = true,
                    value = name,
                    keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                    onValueChange = { name = it },
                    placeholder = stringResource(Res.string.channel_create_placeholder_name),
                    enabled = !submitting,
                    hint = stringResource(Res.string.channel_create_hint_name_format),
                    error = nameError,
                    autoFocus = false,
                    onFocusChanged = { isFocused ->
                        if (isFocused) {
                            memberSearchFocused = false
                            createChannelScrollCoordinator.invalidate()
                        }
                    }
                )
            }
            item {
                RaftBrutalTextField(
                    label = stringResource(Res.string.channel_create_label_description),
                    optional = true,
                    value = description,
                    onValueChange = { description = it },
                    placeholder = stringResource(Res.string.channel_create_placeholder_description),
                    enabled = !submitting,
                    maxLines = 2,
                    autoFocus = false,
                    onFocusChanged = { isFocused ->
                        if (isFocused) {
                            memberSearchFocused = false
                            createChannelScrollCoordinator.invalidate()
                        }
                    }
                )
            }
            item {
                SharedCreateChannelVisibilityControl(
                    selected = visibility,
                    enabled = !submitting,
                    onSelected = { visibility = it }
                )
            }
            item {
                SharedCreateChannelMemberPicker(
                    loading = membersLoading,
                    members = selectableMembers,
                    query = memberSearch,
                    onQueryChange = { memberSearch = it },
                    selectedKeys = selectedMemberKeys,
                    enabled = !submitting,
                    agentStatusResolver = agentStatusResolver,
                    fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled,
                    onToggle = { key ->
                        selectedMemberKeys = raftMemberPickerToggle(selectedMemberKeys, key, RaftMemberPickerMode.Multiple)
                    },
                    onFocus = {
                        memberSearchFocused = true
                        scrollToMemberPicker()
                    },
                    onBlur = {
                        memberSearchFocused = false
                        createChannelScrollCoordinator.invalidate()
                    }
                )
            }
            if (!errorMessage.isNullOrBlank()) {
                item {
                    val localizedError =
                        if (errorMessage == "channel_name_invalid") {
                            stringResource(Res.string.channel_create_error_name_format)
                        } else {
                            errorMessage
                        }
                    Text(
                        text = Res.string.channel_create_error.format(listOf(localizedError)).localized(),
                        style = RaftType.Caption,
                        color = RaftColor.AccentRed
                    )
                }
            }
        }
        }
        RaftDialogFooter {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                enabled = !submitting,
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg
            )
            RaftBrutalActionButton(
                label =
                    if (submitting) {
                        stringResource(Res.string.channel_create_action_creating)
                    } else {
                        stringResource(Res.string.channel_create_action_create)
                    },
                enabled = canSubmit,
                minWidth = 126.dp,
                backgroundColor = RaftColor.AccentPink,
                onClick = {
                    val selectedMembers =
                        selectableMembers.filter { it.memberPickerKey in selectedMemberKeys }
                    onSubmit(
                        name,
                        description.text,
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
    val iconName: RaftLucideIconName
) {
    Public("public", "Public", RaftLucideIconName.Hash),
    Private("private", "Private", RaftLucideIconName.Lock);

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
        RaftFieldLabel(text = stringResource(Res.string.channel_create_label_visibility))
        SegmentedControl(
            selectedValue = selected.name,
            options =
                SharedCreateChannelVisibility.values().map { option ->
                    SegmentedControlOption(
                        value = option.name,
                        label =
                            when (option) {
                                SharedCreateChannelVisibility.Public -> stringResource(Res.string.channel_create_visibility_public)
                                SharedCreateChannelVisibility.Private -> stringResource(Res.string.channel_create_visibility_private)
                            },
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

/** Create channel's Members field: its label over the shared [RaftMemberPicker]. */
@Composable
internal fun SharedCreateChannelMemberPicker(
    loading: Boolean,
    members: List<MemberProfile>,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    selectedKeys: Set<RaftMemberPickerKey>,
    enabled: Boolean,
    agentStatusResolver: RaftAgentStatusResolver,
    fixtureStatusFallbackEnabled: Boolean,
    onToggle: (RaftMemberPickerKey) -> Unit,
    onFocus: () -> Unit = {},
    onBlur: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RaftFieldLabel(text = stringResource(Res.string.channel_create_label_members), optional = true)
        RaftMemberPicker(
            members = members,
            query = query,
            onQueryChange = onQueryChange,
            selectedKeys = selectedKeys,
            onToggle = onToggle,
            loading = loading,
            enabled = enabled,
            agentStatusResolver = agentStatusResolver,
            fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled,
            onSearchFocus = onFocus,
            onSearchBlur = onBlur
        )
    }
}

@Composable
private fun SharedHomeNotification.toHomeNotificationItem(): RaftHomeNotificationItem =
    RaftHomeNotificationItem(
        id = id,
        title = titleDesc?.localized() ?: title,
        body = bodyDesc?.localized() ?: body,
        actionLabel = actionLabelDesc?.localized() ?: actionLabel,
        primaryActionActive =
            id == APP_UPDATE_HOME_NOTIFICATION_ID &&
                actionLabel.trim().lowercase() in setOf("downloading", "install"),
        targetId = targetId,
        kind =
            when (kind) {
                SharedHomeNotificationKind.Warning -> RaftHomeNotificationKind.Warning
                SharedHomeNotificationKind.Info -> RaftHomeNotificationKind.Info
                SharedHomeNotificationKind.Error -> RaftHomeNotificationKind.Error
            },
        icon =
            when (icon) {
                SharedHomeNotificationIcon.Default -> RaftHomeNotificationIcon.Default
                SharedHomeNotificationIcon.WifiOff -> RaftHomeNotificationIcon.WifiOff
                SharedHomeNotificationIcon.CircleArrowUp -> RaftHomeNotificationIcon.CircleArrowUp
                SharedHomeNotificationIcon.Info -> RaftHomeNotificationIcon.Info
            }
    )

@Composable
internal fun SharedHomeSearchPanel(
    state: SharedHomeSearchPanelState,
    homeState: SharedHomeState,
    cacheServerId: String? = null,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    computerDirectory: HomeSearchComputerDirectory = HomeSearchComputerDirectory(),
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    onClose: (() -> Unit)? = null,
    interactiveSearchField: Boolean = true,
    includeTopSafeArea: Boolean = true,
    showSenderFilter: Boolean = true,
    initialOpenFilterId: String? = null,
    initialFocusInput: Boolean = false,
    // False while the page is kept alive but not shown (the phone Search tab when another tab is
    // selected or a page is pushed over the shell): the field must not hold focus or the IME.
    inputActive: Boolean = true,
    // Each increment focuses the field (re-tapping the selected Search tab).
    focusInputRequest: Int = 0,
    // Advances when the hosting page reappears (a pushed result closed).
    visibleTick: Int = 0,
    // The opened-row mark, hoisted like [listState] where the page content is rebuilt on return.
    returnHighlightState: HomeSearchReturnHighlight? = null,
    // The rail's inline Search (two-pane): the row whose conversation is open beside the list is
    // selected while it stays open. Null on the phone, where a result covers the list.
    inlineListSelection: RaftInlineListSelection? = null,
    // The phone Search tab root: the field sits in the tab head (the 56dp frame bar).
    tabHead: Boolean = false,
    // A conversation-scoped pushed search: shows the "In #channel" scope and a "Search all"
    // action that hands the query to the Search tab.
    onExpandToSearchTab: ((String) -> Unit)? = null,
    // A query handed over from a conversation search (Search tab only); applied once.
    externalQueryRequest: RaftSearchTabQueryRequest? = null,
    onExternalQueryApplied: () -> Unit = {},
    initialHistoryEditing: Boolean = false,
    searchHomeVisualTextScale: Float = 1f,
    onOpenItem: (InboxItem) -> Unit,
    onOpenEntity: (SearchEntityResult) -> Unit,
    onRefreshComputers: () -> Unit = {},
    pinnedItems: List<SettingsServerSidebarPinnedItem> = emptyList(),
    onTogglePin: ((SearchEntityPinTarget) -> Unit)? = null,
    bottomContentPadding: Dp,
    timeContext: AppShellThreadTimeContext? = null,
    shellScope: RaftAppShellScope = rememberRaftAppShellScope(),
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val channelFilters = remember(homeState) { homeSearchChannelFilters(homeState) }
    val channelEntityLabel = stringResource(Res.string.search_entity_channel)
    val dmEntityLabel = stringResource(Res.string.search_entity_dm)
    val agentEntityLabel = stringResource(Res.string.search_entity_agent)
    val personEntityLabel = stringResource(Res.string.search_entity_person)
    val membersEntityLabel = stringResource(Res.string.search_entity_members)
    val computerEntityLabel = stringResource(Res.string.search_entity_computer)
    val threadDiscussionLabel = stringResource(Res.string.search_thread_discussion)
    val entityResults = remember(
        state.query,
        homeState,
        state.senderFilters,
        channelEntityLabel,
        dmEntityLabel,
        agentEntityLabel,
        personEntityLabel,
        membersEntityLabel,
        computerEntityLabel,
        computerDirectory
    ) {
        homeSearchEntityResults(
            query = state.query,
            homeState = homeState,
            senderFilters = state.senderFilters,
            channelLabel = channelEntityLabel,
            dmLabel = dmEntityLabel,
            agentLabel = agentEntityLabel,
            personLabel = personEntityLabel,
            membersLabel = membersEntityLabel,
            computerLabel = computerEntityLabel,
            computerDirectory = computerDirectory
        )
    }
    val computerSearchEligible = state.query.trim().firstOrNull() !in setOf('#', '@') && state.query.trim().isNotBlank()
    val frequentEntityResults = remember(
        state.frequentEntityRanks,
        homeState,
        state.senderFilters,
        channelEntityLabel,
        dmEntityLabel,
        agentEntityLabel,
        personEntityLabel,
        membersEntityLabel,
    ) {
        homeSearchFrequentEntityResults(
            homeState = homeState,
            senderFilters = state.senderFilters,
            ranks = state.frequentEntityRanks,
            channelLabel = channelEntityLabel,
            dmLabel = dmEntityLabel,
            agentLabel = agentEntityLabel,
            personLabel = personEntityLabel,
            membersLabel = membersEntityLabel,
        )
    }
    val showSearchHome = !state.hasMessageSearchIntent()
    val hasSearchHomeContent = state.history.isNotEmpty() || frequentEntityResults.isNotEmpty()
    val projectedItems =
        remember(state.items, draftProjection) {
            state.items.map { item -> item.withComposerDraftProjection(draftProjection) }
        }
    val groupedResults = remember(projectedItems, threadDiscussionLabel) {
        groupHomeSearchResults(projectedItems, threadDiscussionLabel)
    }
    var openFilterId by remember(initialOpenFilterId) { mutableStateOf<String?>(null) }
    var pendingInitialOpenFilterId by remember(initialOpenFilterId) { mutableStateOf(initialOpenFilterId) }
    var scopeAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var senderAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var channelAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var timeAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var sortAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var scopeAnchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    var senderAnchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    var channelAnchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    var timeAnchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    var sortAnchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    var activePinMenu by remember { mutableStateOf<SharedHomeSearchPinMenuState?>(null) }
    var historyEditing by remember(initialHistoryEditing) { mutableStateOf(initialHistoryEditing) }
    val searchEditor = remember { SharedHomeSearchEditorOwner(state.query) }
    val searchFocusRequester = remember { FocusRequester() }
    val searchInputFocus =
        rememberSharedHomeSearchInputFocus(
            focusRequester = searchFocusRequester,
            inputActive = inputActive,
            focusInputRequest = focusInputRequest,
            onFocusInputRequest = searchEditor::selectAll
        )
    val returnHighlight = rememberHomeSearchReturnHighlight(pageVisible = inputActive, visibleTick = visibleTick, hoisted = returnHighlightState)
    // The initial autofocus (pushed search pages) runs once, the first time the page shows;
    // coming back from a result never refocuses the field.
    var pendingInitialFocus by remember { mutableStateOf(initialFocusInput) }
    LaunchedEffect(inputActive) {
        if (inputActive && pendingInitialFocus) {
            pendingInitialFocus = false
            delay(100)
            searchFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(externalQueryRequest?.nonce) {
        val request = externalQueryRequest ?: return@LaunchedEffect
        searchEditor.replaceText(request.query) { next ->
            onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged(next))
        }
        if (request.query.isNotBlank()) onIntent(SharedHomeSearchSavedIntent.SubmitSearch)
        onExternalQueryApplied()
    }
    var senderSearch by remember { mutableStateOf("") }
    var channelSearch by remember { mutableStateOf("") }
    val pinLabel = stringResource(Res.string.home_action_pin)
    val unpinLabel = stringResource(Res.string.home_action_unpin)
    LaunchedEffect(entityResults) {
        val activeKey = activePinMenu?.resultKey
        if (activeKey != null && entityResults.none { it.key == activeKey }) {
            activePinMenu = null
        }
    }
    LaunchedEffect(state.history) {
        if (state.history.isEmpty()) historyEditing = false
    }
    LaunchedEffect(showSearchHome) {
        if (!showSearchHome) historyEditing = false
    }
    LaunchedEffect(Unit) {
        onIntent(SharedHomeSearchSavedIntent.OpenSearch)
    }
    LaunchedEffect(
        initialOpenFilterId,
        scopeAnchorResolver,
        senderAnchorResolver,
        channelAnchorResolver,
        timeAnchorResolver,
        sortAnchorResolver
    ) {
        val pending = pendingInitialOpenFilterId ?: return@LaunchedEffect
        val resolved =
            when (pending) {
                "scope" -> scopeAnchorResolver?.resolve()?.also { scopeAnchor = it }
                "sender" -> senderAnchorResolver?.resolve()?.also { senderAnchor = it }
                "channel" -> channelAnchorResolver?.resolve()?.also { channelAnchor = it }
                "time" -> timeAnchorResolver?.resolve()?.also { timeAnchor = it }
                "sort" -> sortAnchorResolver?.resolve()?.also { sortAnchor = it }
                else -> null
            }
        if (resolved != null) {
            openFilterId = pending
            pendingInitialOpenFilterId = null
        }
    }
    RaftBackHandler(enabled = openFilterId != null || activePinMenu != null || historyEditing) {
        when {
            openFilterId != null || activePinMenu != null -> {
                openFilterId = null
                pendingInitialOpenFilterId = null
                activePinMenu = null
            }
            historyEditing -> historyEditing = false
        }
    }
    // raft-ui search panel: designsystem type tokens and badges, on the canvas layer.
    RaftDesignSystemTheme {
        Box(
            modifier =
                modifier
                    .background(RaftColor.Canvas)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SharedHomeSearchToolbar(
                    editor = searchEditor,
                    focusRequester = searchFocusRequester,
                    fieldModifier = searchInputFocus.fieldModifier,
                    query = state.query,
                    loading = state.loading,
                    onQueryChange = { onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged(it)) },
                    onSubmit = { onIntent(SharedHomeSearchSavedIntent.SubmitSearch) },
                    onClear = { onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged("")) },
                    onClose = onClose,
                    interactive = interactiveSearchField,
                    tabHead = tabHead,
                    topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
                    placeholder = stringResource(Res.string.search_placeholder)
                )
                // The tab head draws its own 1dp seam.
                if (!tabHead) SharedHomeSearchDivider()
                RaftAppShellBodyTopBanner()
                if (onExpandToSearchTab != null) {
                    channelFilters.firstOrNull { it.id == state.selectedChannelId }?.let { scope ->
                        SharedHomeSearchScopeRow(
                            scopeLabel = scope.searchScopeLabel(),
                            onExpand = {
                                searchInputFocus.release()
                                onExpandToSearchTab(state.query)
                            }
                        )
                    }
                }
                SharedHomeSearchFilters(
                    state = state,
                    channelFilters = channelFilters,
                    scopeAnchorChanged = { scopeAnchorResolver = it },
                    senderAnchorChanged = { senderAnchorResolver = it },
                    channelAnchorChanged = { channelAnchorResolver = it },
                    timeAnchorChanged = { timeAnchorResolver = it },
                    sortAnchorChanged = { sortAnchorResolver = it },
                    onOpenFilter = { id, anchor ->
                        when (id) {
                            "scope" -> scopeAnchor = anchor
                            "sender" -> senderAnchor = anchor
                            "channel" -> channelAnchor = anchor
                            "time" -> timeAnchor = anchor
                            "sort" -> sortAnchor = anchor
                        }
                        openFilterId = id
                    },
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
                        if (showSearchHome && (!state.personalizationLoaded || !hasSearchHomeContent)) {
                            SharedHomeSearchEmptyState(
                                title = stringResource(Res.string.search_empty_title),
                                detail = stringResource(Res.string.search_empty_detail)
                            )
                        } else if (showSearchHome) {
                            Unit
                        } else if ((state.loading || (computerSearchEligible && computerDirectory.initialLoadInFlight)) && !hasSearchRows) {
                            RaftCenteredLoading(modifier = Modifier.fillParentMaxSize())
                        } else if (
                            state.noPermissionCode != null ||
                                (state.errorCode != null && !hasSearchRows) ||
                                (computerSearchEligible && computerDirectory.initialLoadError != null && !hasSearchRows)
                        ) {
                            val errorCode = state.noPermissionCode ?: state.errorCode ?: computerDirectory.initialLoadError?.code.orEmpty()
                            val recoveryAction = sharedHomeSearchErrorAction(errorCode, state.sort)
                            SharedHomeSearchErrorState(
                                error = localizedSharedHomeSearchErrorDisplay(errorCode, recoveryAction),
                                showAction = recoveryAction != null,
                                onAction = {
                                    recoveryAction?.let(onIntent)
                                    if (recoveryAction == SharedHomeSearchSavedIntent.RefreshSearch) {
                                        onRefreshComputers()
                                    }
                                }
                            )
                        } else if (!state.searched && projectedItems.isEmpty() && entityResults.isEmpty()) {
                            SharedHomeSearchEmptyState(
                                title = stringResource(Res.string.search_empty_title),
                                detail = stringResource(Res.string.search_empty_detail)
                            )
                        } else if (
                            (state.empty || state.searched) &&
                                projectedItems.isEmpty() &&
                                entityResults.isEmpty()
                        ) {
                            val trimmedQuery = state.query.trim()
                            SharedHomeSearchEmptyState(
                                title =
                                    if (trimmedQuery.isNotBlank()) {
                                        Res.string.search_empty_results_query.format(listOf(trimmedQuery)).localized()
                                    } else {
                                        stringResource(Res.string.search_empty_results)
                                    },
                                detail =
                                    if (trimmedQuery.isNotBlank()) {
                                        stringResource(Res.string.search_empty_query_detail)
                                    } else {
                                        stringResource(Res.string.search_empty_filters_detail)
                                    }
                            )
                        } else {
                            SharedHomeSearchResultsCount(
                                totalResults = state.totalCount + entityResults.size,
                                searching = state.querySearchPending || state.loading || state.refreshing
                            )
                        }
                    }
                    if (showSearchHome && state.personalizationLoaded && state.history.isNotEmpty()) {
                        item(key = "search-home-history") {
                            SharedHomeSearchHistorySection(
                                history = state.history,
                                editing = historyEditing,
                                visualTextScale = searchHomeVisualTextScale,
                                onEditingChange = { historyEditing = it },
                                onSelect = { query ->
                                    historyEditing = false
                                    searchEditor.replaceText(query) { next ->
                                        onIntent(SharedHomeSearchSavedIntent.SearchQueryChanged(next))
                                    }
                                    searchFocusRequester.requestFocus()
                                },
                                onRemove = { query ->
                                    onIntent(SharedHomeSearchSavedIntent.RemoveSearchHistoryEntry(query))
                                },
                                onClear = {
                                    historyEditing = false
                                    onIntent(SharedHomeSearchSavedIntent.ClearSearchHistory)
                                },
                            )
                        }
                    }
                    if (showSearchHome && state.personalizationLoaded && hasSearchHomeContent) {
                        item(key = "search-home-frequent-heading") {
                            SharedHomeSearchSectionLabel(
                                label = stringResource(Res.string.search_home_frequent),
                                visualTextScale = searchHomeVisualTextScale,
                            )
                        }
                        if (frequentEntityResults.isEmpty()) {
                            item(key = "search-home-frequent-empty") {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = SearchMessagesHorizontalPadding, vertical = 12.dp),
                                ) {
                                    RaftText(
                                        text = stringResource(Res.string.search_home_frequent_empty),
                                        style = RaftTheme.typography.callout.withHomeSearchVisualTextScale(searchHomeVisualTextScale),
                                        color = RaftColor.ForegroundHint,
                                    )
                                }
                            }
                        } else {
                            items(
                                frequentEntityResults.size,
                                key = { index -> "frequent:${frequentEntityResults[index].key}" },
                            ) { index ->
                                val frequentKey = "frequent:${frequentEntityResults[index].key}"
                                Box(
                                    Modifier
                                        .raftInlineListSelected(inlineListSelection?.selectedRowKey == frequentKey)
                                        .homeSearchReturnHighlight(returnHighlight, frequentKey)
                                ) {
                                SharedHomeSearchEntityRow(
                                    result = frequentEntityResults[index],
                                    query = "",
                                    showSeparator = index > 0,
                                    agentStatusResolver = agentStatusResolver,
                                    memberAvatarResolver = memberAvatarResolver,
                                    isPinned = frequentEntityResults[index].isPinnedBy(pinnedItems),
                                    visualTextScale = searchHomeVisualTextScale,
                                    onOpen = {
                                        searchInputFocus.release()
                                        returnHighlight.markOpened(frequentKey)
                                        inlineListSelection.opening(frequentKey) { onOpenEntity(frequentEntityResults[index]) }
                                    },
                                    onContextMenu = null,
                                )
                                }
                            }
                        }
                    }
                    if (entityResults.isNotEmpty()) {
                        item {
                            SharedHomeSearchSectionLabel(stringResource(Res.string.search_section_entities))
                        }
                        items(entityResults.size, key = { index -> entityResults[index].key }) { index ->
                            Box(
                                Modifier
                                    .raftInlineListSelected(inlineListSelection?.selectedRowKey == entityResults[index].key)
                                    .homeSearchReturnHighlight(returnHighlight, entityResults[index].key)
                            ) {
                            SharedHomeSearchEntityRow(
                                result = entityResults[index],
                                query = state.query,
                                showSeparator = index > 0,
                                agentStatusResolver = agentStatusResolver,
                                memberAvatarResolver = memberAvatarResolver,
                                isPinned = entityResults[index].isPinnedBy(pinnedItems),
                                onOpen = {
                                    searchInputFocus.release()
                                    returnHighlight.markOpened(entityResults[index].key)
                                    inlineListSelection.opening(entityResults[index].key) { onOpenEntity(entityResults[index]) }
                                },
                                onContextMenu =
                                    searchEntityPinTarget(entityResults[index])
                                        ?.takeIf { onTogglePin != null }
                                        ?.let { target ->
                                            { anchor ->
                                                activePinMenu =
                                                    SharedHomeSearchPinMenuState(
                                                        resultKey = entityResults[index].key,
                                                        target = target,
                                                        isPinned = entityResults[index].isPinnedBy(pinnedItems),
                                                        anchor = anchor
                                                    )
                                            }
                                        }
                            )
                            }
                        }
                    }
                    if (groupedResults.isNotEmpty()) {
                        item {
                            SharedHomeSearchSectionLabel(stringResource(Res.string.search_section_messages))
                        }
                    }
                    groupedResults.forEachIndexed { groupIndex, group ->
                        when (group) {
                            is HomeSearchResultGroup.Message -> {
                                item(key = group.item.key) {
                                    Box(
                                        Modifier
                                            .raftInlineListSelected(inlineListSelection?.selectedRowKey == group.item.key)
                                            .homeSearchReturnHighlight(returnHighlight, group.item.key)
                                    ) {
                                    SharedHomeSearchMessageResultRow(
                                        item = group.item,
                                        query = state.query,
                                        showSeparator = groupIndex > 0,
                                        cacheServerId = cacheServerId,
                                        memberAvatarResolver = memberAvatarResolver,
                                        timeContext = timeContext,
                                        onOpen = {
                                            searchInputFocus.release()
                                            returnHighlight.markOpened(group.item.key)
                                            inlineListSelection.opening(group.item.key) { onOpenItem(group.item) }
                                        }
                                    )
                                    }
                                }
                            }
                            is HomeSearchResultGroup.Thread -> {
                                item(key = group.key) {
                                    Box(
                                        Modifier
                                            .raftInlineListSelected(inlineListSelection?.selectedRowKey == group.key)
                                            .homeSearchReturnHighlight(returnHighlight, group.key)
                                    ) {
                                    SharedHomeThreadResultGroup(
                                        group = group,
                                        query = state.query,
                                        showSeparator = groupIndex > 0,
                                        cacheServerId = cacheServerId,
                                        memberAvatarResolver = memberAvatarResolver,
                                        timeContext = timeContext,
                                        onOpenItem = { item ->
                                            searchInputFocus.release()
                                            returnHighlight.markOpened(group.key)
                                            inlineListSelection.opening(group.key) { onOpenItem(item) }
                                        }
                                    )
                                    }
                                }
                            }
                        }
                    }
                    if (state.hasMore) {
                        item {
                            SharedHomeLoadMoreRow(
                                loading = state.loadingMore,
                                label = stringResource(Res.string.search_action_load_more_results),
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
                "scope" ->
                    RaftFilterPopover(
                        anchor = scopeAnchor,
                        title = stringResource(Res.string.search_filter_scope),
                        searchPlaceholder = "",
                        searchText = "",
                        onSearchChange = {},
                        options = SearchScope.entries.map { RaftFilterOption(it.key, it.localizedSearchLabel()) },
                        selectedIds = state.selectedScopes.mapTo(linkedSetOf()) { it.key },
                        onToggle = { key ->
                            SearchScope.entries.firstOrNull { it.key == key }?.let {
                                onIntent(SharedHomeSearchSavedIntent.ToggleSearchScope(it))
                            }
                        },
                        onClear = { onIntent(SharedHomeSearchSavedIntent.ClearSearchScopes) },
                        onDismiss = { openFilterId = null },
                        widthDp = 180f,
                        multiSelect = true,
                        searchable = false,
                        surfaceBorderColor = SearchPopoverBorderColor,
                        surfaceShadowColor = SearchPopoverShadowColor,
                        selectedRowColor = RaftColor.SurfaceWhite,
                        showClearWhenSelected = true
                    )
                "sender" ->
                    RaftFilterSheet(
                        title = stringResource(Res.string.search_filter_from),
                        searchPlaceholder = stringResource(Res.string.search_filter_search_senders),
                        searchText = senderSearch,
                        onSearchChange = { senderSearch = it },
                        options = state.senderFilterOptions(),
                        // One sender, like the web: a pick replaces the current sender.
                        selectedIds = setOfNotNull(state.selectedSenderId),
                        onToggle = {
                            onIntent(SharedHomeSearchSavedIntent.SetSearchSender(it))
                        },
                        onClear = { onIntent(SharedHomeSearchSavedIntent.SetSearchSender(null)) },
                        onDismiss = { openFilterId = null },
                        multiSelect = false,
                        showClear = true,
                        // An empty list while senders load (or fail) says so.
                        status =
                            when {
                                state.senderFiltersLoading -> stringResource(Res.string.search_filter_loading_senders)
                                else -> state.senderFiltersErrorCode
                            }
                    )
                "channel" ->
                    RaftFilterSheet(
                        title = stringResource(Res.string.search_filter_channels),
                        searchPlaceholder = stringResource(Res.string.search_filter_search),
                        searchText = channelSearch,
                        onSearchChange = { channelSearch = it },
                        options = channelFilters.map { RaftFilterOption(it.id, it.label, it.icon) },
                        selectedIds = state.selectedChannelId?.let { setOf(it) } ?: emptySet(),
                        onToggle = {
                            onIntent(SharedHomeSearchSavedIntent.SetSearchChannel(it))
                        },
                        onClear = { onIntent(SharedHomeSearchSavedIntent.SetSearchChannel(null)) },
                        onDismiss = { openFilterId = null },
                        multiSelect = false,
                        showClear = true
                    )
                "time" ->
                    RaftFilterPopover(
                        anchor = timeAnchor,
                        title = stringResource(Res.string.search_filter_time),
                        searchPlaceholder = "",
                        searchText = "",
                        onSearchChange = {},
                        options = SearchTimeRange.entries.map { RaftFilterOption(it.key, it.localizedSearchLabel()) },
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
                        selectedRowColor = RaftColor.SurfaceWhite
                    )
                "sort" ->
                    RaftFilterPopover(
                        anchor = sortAnchor,
                        title = stringResource(Res.string.search_filter_sort),
                        searchPlaceholder = "",
                        searchText = "",
                        onSearchChange = {},
                        options =
                            listOf(
                                RaftFilterOption("relevance", stringResource(Res.string.search_sort_relevant)),
                                RaftFilterOption("recent", stringResource(Res.string.search_sort_recent))
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
                        selectedRowColor = RaftColor.SurfaceWhite
                    )
                else -> Unit
            }
            activePinMenu?.let { menu ->
                RaftBaseMenuOverlay(
                    anchor = menu.anchor,
                    items =
                        listOf(
                            RaftBaseMenuItem(
                                id = SharedHomeSearchPinMenuActionToggle,
                                label = if (menu.isPinned) unpinLabel else pinLabel,
                                icon = if (menu.isPinned) RaftLucideIconName.PinOff else RaftLucideIconName.Pin
                            )
                        ),
                    selectedId = "",
                    uppercaseLabels = false,
                    onDismiss = { activePinMenu = null },
                    onSelect = { actionId ->
                        activePinMenu = null
                        dispatchSearchEntityPinMenuSelection(
                            actionId = actionId,
                            target = menu.target,
                            onTogglePin = onTogglePin
                        )
                    }
                )
            }
        }
    }
}

private const val SharedHomeSearchPinMenuActionToggle = "toggle-pin"

private data class SharedHomeSearchPinMenuState(
    val resultKey: String,
    val target: SearchEntityPinTarget,
    val isPinned: Boolean,
    val anchor: RaftPopoverAnchor
)

internal fun dispatchSearchEntityPinMenuSelection(
    actionId: String,
    target: SearchEntityPinTarget,
    onTogglePin: ((SearchEntityPinTarget) -> Unit)?
) {
    if (actionId == SharedHomeSearchPinMenuActionToggle) {
        onTogglePin?.invoke(target)
    }
}

// raft-ui PanelHeader / search `filtersRow`: a 1dp `border-line-seam` under each bar.
@Composable
private fun SharedHomeSearchDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(RaftAppStroke.Thin)
                .background(RaftColor.BorderSeam)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedHomeSearchHistorySection(
    history: List<String>,
    editing: Boolean,
    visualTextScale: Float = 1f,
    onEditingChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    // The history heading reads as a search `sectionHeading` (footnote bold hint); the
    // entries are raft-ui Combobox chips (`h-7` chip radius, `fill-muted`, footnote semibold).
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = SearchMessagesHorizontalPadding,
                    top = 8.dp,
                    end = SearchMessagesHorizontalPadding,
                    bottom = 8.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftLucideIcon(
                RaftLucideIconName.Clock3,
                color = raftArgbHex(RaftColor.ForegroundHint),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            RaftText(
                text = stringResource(Res.string.search_home_history),
                style =
                    RaftTheme.typography.footnote
                        .copy(fontWeight = FontWeight.Bold)
                        .withHomeSearchVisualTextScale(visualTextScale),
                color = RaftColor.ForegroundHint,
                modifier = Modifier.weight(1f),
            )
            SharedHomeSearchHistoryTextAction(
                label =
                    if (editing) {
                        stringResource(Res.string.search_home_done)
                    } else {
                        stringResource(Res.string.search_home_edit)
                    },
                onClick = { onEditingChange(!editing) },
                visualTextScale = visualTextScale,
            )
            SharedHomeSearchHistoryTextAction(
                label = stringResource(Res.string.search_home_clear),
                onClick = onClear,
                visualTextScale = visualTextScale,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            history.forEach { query ->
                SharedHomeSearchHistoryTag(
                    query = query,
                    editing = editing,
                    visualTextScale = visualTextScale,
                    onSelect = { onSelect(query) },
                    onRemove = { onRemove(query) },
                )
            }
        }
    }
}

@Composable
private fun SharedHomeSearchHistoryTextAction(
    label: String,
    onClick: () -> Unit,
    visualTextScale: Float = 1f,
) {
    Box(
        modifier =
            Modifier
                .height(44.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(
            text = label,
            style =
                RaftTheme.typography.footnote
                    .copy(fontWeight = FontWeight.Bold)
                    .withHomeSearchVisualTextScale(visualTextScale),
            color = RaftColor.TextBlack,
            maxLines = 1,
        )
    }
}

@Composable
private fun SharedHomeSearchHistoryTag(
    query: String,
    editing: Boolean,
    visualTextScale: Float = 1f,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val removeLabel =
        if (editing) {
            Res.string.search_home_remove_history.format(listOf(query)).localized()
        } else {
            null
        }
    Box(
        modifier =
            Modifier
                .height(if (editing) 44.dp else 28.dp)
                .widthIn(max = 260.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .height(28.dp)
                    .background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Chip)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                    Modifier
                        .height(28.dp)
                        .weight(1f, fill = false)
                        .padding(start = 8.dp, end = if (editing) 0.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RaftLucideIcon(
                    RaftLucideIconName.Clock3,
                    color = raftArgbHex(RaftColor.ForegroundHint),
                    modifier = Modifier.size(12.dp),
                )
                RaftText(
                    text = query,
                    style =
                        RaftTheme.typography.footnote
                            .copy(fontWeight = FontWeight.SemiBold)
                            .withHomeSearchVisualTextScale(visualTextScale),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (editing) {
                // `chipRemove`: a 14dp hint X; the slot keeps the 44dp remove target.
                Box(
                    modifier = Modifier.size(width = 44.dp, height = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    RaftLucideIcon(
                        RaftLucideIconName.X,
                        color = raftArgbHex(RaftColor.ForegroundHint),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (editing) {
            Row(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(onClick = onSelect)
                            .semantics { contentDescription = query },
                )
                Box(
                    modifier =
                        Modifier
                            .width(44.dp)
                            .fillMaxHeight()
                            .clickable(onClick = onRemove)
                            .semantics { contentDescription = removeLabel.orEmpty() },
                )
            }
        } else {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .clickable(onClick = onSelect)
                        .semantics { contentDescription = query },
            )
        }
    }
}

// raft-ui SearchHeader (PanelHeader): a 56dp canvas row, `pl-4` and `gap-2` on a phone; an
// ghost icon-lg Back (RaftBackButton), the 27dp brand PanelHeaderIcon with the search glyph, then the
// search field (a filled control, so it ends on the 16dp content edge).
@Composable
private fun SharedHomeSearchToolbar(
    editor: SharedHomeSearchEditorOwner,
    focusRequester: FocusRequester,
    fieldModifier: Modifier = Modifier,
    query: String,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onClose: (() -> Unit)?,
    interactive: Boolean = true,
    autoFocus: Boolean = false,
    tabHead: Boolean = false,
    topSafeAreaPadding: Dp = 0.dp,
    placeholder: String
) {
    if (tabHead) {
        // Nav spec §2/§7.1: the Search tab root wears the same tab head as the other roots, a
        // 56dp frame bar (Source Yellow, the dark frame in dark) with a 1dp seam; the field
        // takes the title's place, 16dp from both edges.
        RaftTopPageHeaderFrame(
            backgroundColor = RaftColor.Frame,
            topSafeArea = topSafeAreaPadding,
            contentStartPadding = SearchControlsHorizontalPadding,
            contentEndPadding = SearchControlsHorizontalPadding,
        ) {
            SharedHomeSearchInput(
                query = editor.value,
                loading = loading,
                interactive = interactive,
                placeholder = placeholder,
                autoFocus = autoFocus,
                focusRequester = focusRequester,
                onQueryChange = { next -> editor.acceptEditorValue(next, onQueryChange) },
                onSubmit = onSubmit,
                onClear = { editor.clear(onClear) },
                raftField = true,
                modifier = Modifier.weight(1f).then(fieldModifier)
            )
        }
        return
    }
    SharedHomeSearchControlsRow(
        topSafeAreaPadding = topSafeAreaPadding,
        // Back sits at the top-bar edge inset, its arrow 14dp in, like every page header.
        startPadding = if (onClose != null) RaftBackButtonDefaults.EdgeInset else SearchControlsHorizontalPadding
    ) {
        if (onClose != null) {
            RaftBackButton(onClick = onClose, label = stringResource(Res.string.common_back))
        }
        // No location tile: the search field already says where you are (web search page,
        // raft-design: a page whose head is a search field carries no location icon).
        SharedHomeSearchInput(
            query = editor.value,
            loading = loading,
            interactive = interactive,
            placeholder = placeholder,
            autoFocus = autoFocus,
            focusRequester = focusRequester,
            onQueryChange = { next -> editor.acceptEditorValue(next, onQueryChange) },
            onSubmit = onSubmit,
            onClear = { editor.clear(onClear) },
            raftField = true,
            modifier = Modifier.weight(1f).then(fieldModifier)
        )
    }
}

/** "#name" for a channel scope, the bare name for a DM. */
private fun SearchChannelFilterItem.searchScopeLabel(): String =
    if (icon == RaftLucideIconName.Hash) "#$label" else label

/**
 * Nav spec §7.2: a conversation search shows where it searches ("In #channel") and offers
 * "Search all", which moves the query to the Search tab (this page stays scoped).
 */
@Composable
private fun SharedHomeSearchScopeRow(scopeLabel: String, onExpand: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.SurfaceWhite)
                .padding(start = SearchControlsHorizontalPadding, end = SearchControlsHorizontalPadding, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftText(
            text = Res.string.search_scope_in_channel.format(listOf(scopeLabel)).localized(),
            style = RaftTheme.typography.callout,
            color = RaftColor.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        RaftButton(
            text = stringResource(Res.string.search_scope_expand_all),
            onClick = onExpand,
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Md,
        )
    }
}

@Composable
private fun SharedHomeSearchControlsRow(
    topSafeAreaPadding: Dp,
    startPadding: Dp = SearchControlsHorizontalPadding,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.Canvas)
                .padding(top = topSafeAreaPadding)
                .height(56.dp)
                .padding(start = startPadding, end = SearchControlsHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
internal fun SharedHomeSavedPanel(
    state: SharedHomeSavedPanelState,
    cacheServerId: String? = null,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    onClose: (() -> Unit)? = null,
    onOpenItem: (InboxItem) -> Unit,
    bottomContentPadding: Dp,
    includeTopSafeArea: Boolean = true,
    timeContext: AppShellThreadTimeContext? = null,
    shellScope: RaftAppShellScope = rememberRaftAppShellScope(),
    listState: LazyListState = rememberLazyListState(),
    onTitleClick: (() -> Unit)? = null,
    onCopyLink: (InboxItem) -> Unit = {},
    onCopyMarkdown: (InboxItem) -> Unit = {},
    // The rail's inline Saved (two-pane): the row whose conversation is open beside the list is
    // selected, and only while it stays open. Null (the pushed phone page) keeps the tapped row.
    inlineListSelection: RaftInlineListSelection? = null,
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
    val projectedItems =
        remember(state.items, draftProjection) {
            state.items.map { item -> item.withComposerDraftProjection(draftProjection) }
        }
    val copyLinkLabel = stringResource(Res.string.saved_action_copy_link)
    val copyMarkdownLabel = stringResource(Res.string.saved_action_copy_markdown)
    val removeLabel = stringResource(Res.string.saved_action_remove)
    val contextMenuItems = remember(copyLinkLabel, copyMarkdownLabel, removeLabel) {
        listOf(
            RaftBaseMenuItem(SharedHomeSavedMenuActionCopyLink, copyLinkLabel, RaftLucideIconName.Link),
            RaftBaseMenuItem(SharedHomeSavedMenuActionCopyMarkdown, copyMarkdownLabel, RaftLucideIconName.Copy),
            RaftBaseMenuItem(
                SharedHomeSavedMenuActionRemove,
                removeLabel,
                RaftLucideIconName.Bookmark,
                dividerBefore = true
            )
        )
    }
    if (state.loading && projectedItems.isEmpty()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(RaftColor.SurfaceWhite)
        ) {
            SharedHomeSavedHeader(
                count = state.totalCount.takeIf { it > 0 } ?: projectedItems.size,
                onClose = onClose,
                refreshing = state.refreshing || state.pullRefreshing,
                topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
                onTitleClick = onTitleClick
            )
            RaftAppShellBodyTopBanner()
            RaftCenteredLoading(modifier = Modifier.fillMaxWidth().weight(1f))
        }
        return
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
    ) {
        Column(Modifier.fillMaxSize()) {
            SharedHomeSavedHeader(
                count = state.totalCount.takeIf { it > 0 } ?: projectedItems.size,
                onClose = onClose,
                // artin task #22: the pull indicator below is the only chrome for a pulled refresh.
                refreshing = raftHeaderRefreshIndicatorVisible(state.refreshing, state.pullRefreshing),
                topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
                onTitleClick = onTitleClick
            )
            RaftAppShellBodyTopBanner()
            RaftPullToRefreshBox(
                isRefreshing = state.pullRefreshing,
                onRefresh = { onIntent(SharedHomeSearchSavedIntent.PullRefreshSaved) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(RaftColor.SurfaceWhite),
                    // web SavedPanel scroller `py-1`: plain rows stacked edge to edge.
                    contentPadding = PaddingValues(top = 4.dp)
                ) {
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
                            selected =
                                (inlineListSelection?.selectedRowKey ?: selectedItemKey) == item.key ||
                                    activeMenu?.item?.key == item.key,
                            cacheServerId = cacheServerId,
                            memberAvatarResolver = memberAvatarResolver,
                            onUnsave = { onIntent(SharedHomeSearchSavedIntent.Unsave(item)) },
                            onOpen = {
                                if (inlineListSelection == null) selectedItemKey = item.key
                                inlineListSelection.opening(item.key) { onOpenItem(item) }
                            },
                            onContextMenu = { anchor -> activeMenu = SharedHomeSavedContextMenuState(item, anchor) },
                            timeContext = timeContext
                        )
                    }
                    if (state.hasMore) {
                        item(key = "saved-load-more-sentinel") {
                            // web SavedPanel: an infinite-scroll sentinel (min 40dp, 12dp padding), not a
                            // button. Composing it means the list end is near, so the next page loads; a
                            // failed page keeps an explicit retry.
                            val latestOnIntent by rememberUpdatedState(onIntent)
                            val canRequest = !state.loadingMore && state.errorCode == null
                            LaunchedEffect(projectedItems.size, canRequest) {
                                if (canRequest) latestOnIntent(SharedHomeSearchSavedIntent.LoadMoreSaved)
                            }
                            if (state.errorCode != null && !state.loadingMore) {
                                SharedHomeLoadMoreRow(
                                    loading = false,
                                    label = stringResource(Res.string.saved_action_load_more),
                                    onClick = { onIntent(SharedHomeSearchSavedIntent.LoadMoreSaved) }
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (state.loadingMore) {
                                        Text(
                                            text = stringResource(Res.string.common_loading),
                                            style = RaftType.Caption,
                                            color = RaftColor.ForegroundHint
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(bottomContentPadding))
                    }
                }
            }
        }
        activeMenu?.let { menu ->
            RaftBaseMenuOverlay(
                anchor = menu.anchor,
                items = contextMenuItems,
                selectedId = "",
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

internal enum class SharedHomeSavedGestureOwner {
    Open,
    Unsave
}

internal fun sharedHomeSavedGestureOwner(
    tapX: Float,
    rowWidthPx: Float,
    trailingActionWidthPx: Float
): SharedHomeSavedGestureOwner {
    if (rowWidthPx <= 0f || trailingActionWidthPx <= 0f) return SharedHomeSavedGestureOwner.Open
    val actionWidth = trailingActionWidthPx.coerceAtMost(rowWidthPx)
    return if (tapX >= rowWidthPx - actionWidth) {
        SharedHomeSavedGestureOwner.Unsave
    } else {
        SharedHomeSavedGestureOwner.Open
    }
}

private data class SharedHomeSavedContextMenuState(
    val item: InboxItem,
    val anchor: RaftPopoverAnchor
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

/** Saved and search rows: the item's message Markdown read as one line; null when nothing is left. */
private fun InboxItem.previewMarkdownText(): String? =
    (latestActivityPreview ?: lastMessagePreview ?: parentMessagePreview)
        ?.let(::markdownToPreviewText)
        ?.takeIf { it.isNotBlank() }

internal fun InboxItem.sharedHomeSavedMarkdownContent(): String =
    latestActivityPreview ?: lastMessagePreview ?: parentMessagePreview ?: ""

@Composable
private fun SharedHomeSavedEmptyState() {
    RaftEmptyState(
        title = stringResource(Res.string.saved_empty_title),
        detail = stringResource(Res.string.saved_empty_detail),
        iconName = RaftLucideIconName.Bookmark,
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
    // web SavedPanel head: raft-ui PanelHeader with the phone Back, "N saved items".
    RaftPanelBackHeader(
        title = stringResource(Res.string.saved_title),
        subtitle = stringResource(if (count == 1) Res.string.saved_count_one else Res.string.saved_count, count),
        onBack = onClose,
        topSafeArea = topSafeAreaPadding,
        refreshing = refreshing,
        onTitleClick = onTitleClick,
    )
}

@Composable
private fun SharedHomePanelHeader(
    title: String,
    subtitle: String,
    iconName: RaftLucideIconName,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    includeTopSafeArea: Boolean = true
) {
    val topPadding = if (includeTopSafeArea) raftTopSafeAreaPadding() else 0.dp
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = topPadding + 18.dp, end = 18.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(name = iconName, color = RaftColor.TextBlackArgb, modifier = Modifier.size(30.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = RaftType.PageTitle.copy(color = RaftColor.TextBlack),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray,
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
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftBrutalInputField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Search messages",
            autoFocus = true,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (query.isNotBlank() && !loading) onSubmit() }),
            textStyle = RaftType.Input.copy(color = RaftColor.TextBlack),
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
    scopeAnchorChanged: (RaftPopoverAnchorResolver) -> Unit,
    senderAnchorChanged: (RaftPopoverAnchorResolver) -> Unit,
    channelAnchorChanged: (RaftPopoverAnchorResolver) -> Unit,
    timeAnchorChanged: (RaftPopoverAnchorResolver) -> Unit,
    sortAnchorChanged: (RaftPopoverAnchorResolver) -> Unit,
    onOpenFilter: (String, RaftPopoverAnchor) -> Unit,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    showSenderFilter: Boolean = true
) {
    val hasActiveFilters =
        state.selectedScopes.isNotEmpty() ||
            state.myMessagesOnly ||
            state.selectedSenderId != null ||
            state.selectedChannelId != null ||
            state.timeRange != SearchTimeRange.Any
    val selectedChannel = channelFilters.firstOrNull { it.id == state.selectedChannelId }
    val scopeActive = state.selectedScopes.isNotEmpty()
    val senderActive = state.selectedSenderId != null
    val channelActive = state.selectedChannelId != null
    val timeActive = state.timeRange != SearchTimeRange.Any
    val sortActive = state.sort != SearchSort.Relevance
    val scopeLabel = stringResource(Res.string.search_filter_scope)
    val channelLabel = stringResource(Res.string.search_filter_channel)
    // raft-ui PickerTriggerButton: an applied filter (web `appliedFilterProps`) is the
    // `info-soft` wash.
    SharedHomeSearchControlsBlock {
        val backSwipeRowState = rememberLazyListState()
        val backSwipeRow = rememberRaftBackSwipeScroll(backSwipeRowState)
        LazyRow(
            state = backSwipeRowState,
            userScrollEnabled = backSwipeRow.userScrollEnabled,
            modifier = Modifier.then(backSwipeRow.modifier).fillMaxWidth().padding(horizontal = SearchControlsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            homeSearchFilterOrder(showSenderFilter).forEach { filter ->
                item(key = "search-filter-${filter.id}") {
                    when (filter) {
                        HomeSearchFilter.Sender ->
                            RaftAnchoredPickerTrigger(
                                label = state.localizedSenderFilterLabel(),
                                anchorIdentity = "search_filter:sender",
                                hasValue = senderActive,
                                iconName = RaftLucideIconName.CircleUser,
                                onAnchorChanged = senderAnchorChanged,
                                onClick = { anchor ->
                                    onOpenFilter("sender", anchor)
                                }
                            )
                        HomeSearchFilter.Scope ->
                            RaftAnchoredPickerTrigger(
                                label = if (scopeActive) "$scopeLabel ${state.selectedScopes.size}" else scopeLabel,
                                anchorIdentity = "search_filter:scope",
                                hasValue = scopeActive,
                                iconName = RaftLucideIconName.AtSign,
                                onAnchorChanged = scopeAnchorChanged,
                                onClick = { anchor ->
                                    onOpenFilter("scope", anchor)
                                }
                            )
                        HomeSearchFilter.Channel ->
                            RaftAnchoredPickerTrigger(
                                label = selectedChannel?.label ?: channelLabel,
                                anchorIdentity = "search_filter:channel",
                                hasValue = channelActive,
                                iconName = selectedChannel?.iconName ?: RaftLucideIconName.Hash,
                                onAnchorChanged = channelAnchorChanged,
                                onClick = { anchor ->
                                    onOpenFilter("channel", anchor)
                                }
                            )
                        HomeSearchFilter.Time ->
                            RaftAnchoredPickerTrigger(
                                label = state.timeRange.localizedSearchLabel(),
                                anchorIdentity = "search_filter:time",
                                hasValue = timeActive,
                                iconName = RaftLucideIconName.Calendar,
                                onAnchorChanged = timeAnchorChanged,
                                onClick = { anchor ->
                                    onOpenFilter("time", anchor)
                                }
                            )
                    }
                }
            }
            item {
                RaftAnchoredPickerTrigger(
                    label = state.sort.localizedSearchLabel(),
                    anchorIdentity = "search_filter:sort",
                    hasValue = sortActive,
                    iconName = RaftLucideIconName.ArrowUpDown,
                    onAnchorChanged = sortAnchorChanged,
                    onClick = { anchor ->
                        onOpenFilter("sort", anchor)
                    }
                )
            }
            if (hasActiveFilters) {
                item {
                    RaftPickerTriggerButton(
                        text = stringResource(Res.string.search_filter_clear_all),
                        showIndicator = false,
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

internal enum class HomeSearchFilter(val id: String) {
    Sender("sender"),
    Scope("scope"),
    Channel("channel"),
    Time("time"),
}

internal fun homeSearchFilterOrder(showSenderFilter: Boolean): List<HomeSearchFilter> =
    if (showSenderFilter) {
        listOf(HomeSearchFilter.Sender, HomeSearchFilter.Scope, HomeSearchFilter.Channel, HomeSearchFilter.Time)
    } else {
        listOf(HomeSearchFilter.Scope, HomeSearchFilter.Channel, HomeSearchFilter.Time)
    }

@Composable
private fun SharedHomeSearchControlsBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.SurfaceWhite)
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
    val userFacingErrorLabels = threadUserFacingErrorLabels()
    when {
        noPermissionCode != null ->
            SharedHomePanelMessage(
                title = stringResource(Res.string.panel_error_no_permission),
                detail =
                    userFacingErrorLabels.forErrorCode(noPermissionCode)
                        ?: userFacingErrorLabels.generic(),
                tone = RaftColor.AccentRed,
                actionLabel = stringResource(Res.string.common_retry),
                onAction = onRetry
            )
        errorCode != null ->
            SharedHomePanelMessage(
                title = stringResource(Res.string.panel_error_load),
                detail =
                    userFacingErrorLabels.forErrorCode(errorCode)
                        ?: userFacingErrorLabels.generic(),
                tone = RaftColor.AccentRed,
                actionLabel = stringResource(Res.string.common_retry),
                onAction = onRetry
            )
        empty ->
            SharedHomePanelMessage(
                title = emptyTitle,
                detail = emptyDetail,
                tone = RaftColor.AppBgLightBlue
            )
    }
}

@Composable
private fun SharedHomePanelMessage(
    title: String,
    detail: String,
    tone: androidx.compose.ui.graphics.Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier =
            Modifier
                .padding(horizontal = SearchMessagesHorizontalPadding)
                .fillMaxWidth()
                .raftCardSurface(fill = tone.copy(alpha = 0.42f))
                .padding(RaftAppSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = RaftType.SectionLabel,
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray,
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
    ) {
        // raft-ui ListRow `plain`: no frame; the open row keeps the fill-pressed wash.
        content(
            Modifier
                .fillMaxWidth()
                .background(if (selected) RaftColor.FillPressed else Color.Transparent)
        )
    }
}

@Composable
private fun SharedHomeSavedResultCard(
    item: InboxItem,
    selected: Boolean,
    cacheServerId: String? = null,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    onUnsave: () -> Unit,
    onOpen: () -> Unit,
    onContextMenu: (RaftPopoverAnchor) -> Unit,
    timeContext: AppShellThreadTimeContext? = null
) {
    val avatarCacheIdentity =
        remember(item, cacheServerId) { item.previewSenderAvatarCacheIdentity(cacheServerId) }
    val preview = item.previewMarkdownText() ?: stringResource(Res.string.saved_state_no_preview)
    val sender = item.latestActivitySenderName ?: item.lastMessageSenderName
    val time = item.latestActivityAt ?: item.lastMessageAt
    val openGestureKey = remember(item) { item.inboxItemOpenGestureKey() }
    val gestureGeneration = remember(item.key) { mutableStateOf(0L) }
    val gestureLogger = remember { raftLogger("SlockHomeSaved", "home_saved_gesture") }
    val savedMessageId = item.savedMessageIdOrNull()?.value.orEmpty()
    val source =
        when {
            item.kind == InboxItemKind.DirectMessage -> item.channelName.takeIf { it.isNotBlank() }?.let { "@$it" }
            item.kind == InboxItemKind.Thread -> item.parentChannelName?.takeIf { it.isNotBlank() }?.let { "#$it" }
            else -> item.channelName.takeIf { it.isNotBlank() }?.let { "#$it" }
        }
    val contentAnchor = remember(openGestureKey) { RaftLiveRootAnchorRef(identity = openGestureKey) }
    fun dispatchSavedGesture(owner: SharedHomeSavedGestureOwner, source: String) {
        val generation = gestureGeneration.value + 1L
        gestureGeneration.value = generation
        gestureLogger(
            "owner=${owner.name.lowercase()} action=${owner.name.lowercase()} source=$source generation=$generation " +
                "itemKey=${item.key.take(80)} messageId=${savedMessageId.take(80)}"
        )
        when (owner) {
            SharedHomeSavedGestureOwner.Open -> onOpen()
            SharedHomeSavedGestureOwner.Unsave -> onUnsave()
        }
    }
    val gestureOwner =
        rememberRaftGestureOwner(
            surface = "home-saved-result",
            semanticIdentity = openGestureKey,
            callbacks =
                SharedHomeSavedResultGestureCallbacks(
                    onTap = { localOffset, rowWidthPx, trailingActionWidthPx ->
                        dispatchSavedGesture(
                            owner =
                                sharedHomeSavedGestureOwner(
                                    tapX = localOffset.x,
                                    rowWidthPx = rowWidthPx,
                                    trailingActionWidthPx = trailingActionWidthPx
                                ),
                            source = "tap"
                        )
                    },
                    onLongPress = { localOffset, rowWidthPx, trailingActionWidthPx ->
                        val owner =
                            sharedHomeSavedGestureOwner(
                                tapX = localOffset.x,
                                rowWidthPx = rowWidthPx,
                                trailingActionWidthPx = trailingActionWidthPx
                            )
                        val generation = gestureGeneration.value + 1L
                        gestureGeneration.value = generation
                        if (owner == SharedHomeSavedGestureOwner.Open) {
                            gestureLogger(
                                "owner=context_menu action=context_menu source=long_press generation=$generation " +
                                    "itemKey=${item.key.take(80)} messageId=${savedMessageId.take(80)}"
                            )
                            contentAnchor
                                .resolve(RaftPopoverLocalPoint.Exact(localOffset))
                                ?.let(onContextMenu)
                        } else {
                            gestureLogger(
                                "owner=ignored action=none source=long_press generation=$generation " +
                                    "reason=unsave_zone_long_press itemKey=${item.key.take(80)} " +
                                    "messageId=${savedMessageId.take(80)}"
                            )
                        }
                    }
                )
        )
    // web SavedPanel row: a plain raft-ui ListRow (16/12dp padding, no card frame); the
    // pressed / open row takes the fill-pressed wash.
    SharedHomeSavedSelectedSurface(
        selected = selected,
        modifier = Modifier.fillMaxWidth()
    ) { surfaceModifier ->
        Row(
            modifier =
                surfaceModifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .raftCaptureLiveRootAnchor(contentAnchor)
                    .raftGesturePointerInput(gestureOwner) { owner ->
                        val trailingActionWidthPx = SavedUnsaveHitTargetSize.toPx()
                        var gesture: RaftGestureSession<SharedHomeSavedResultGestureCallbacks>? = null
                        detectTapGestures(
                            onPress = {
                                gesture = owner.beginGesture()
                                tryAwaitRelease()
                            },
                            onTap = { localOffset ->
                                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onTap(
                                        localOffset,
                                        size.width.toFloat(),
                                        trailingActionWidthPx
                                    )
                            },
                            onLongPress = { localOffset ->
                                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onLongPress(
                                        localOffset,
                                        size.width.toFloat(),
                                        trailingActionWidthPx
                                    )
                            }
                        )
                    },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Meta line: `text-caption`, 8dp gaps: place, Thread, sender (14dp avatar), time.
                Row(
                    // One 16dp caption line (web `items-center text-caption`), 4dp above the preview.
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).height(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    source?.let {
                        Text(
                            text = it,
                            style = RaftType.Caption,
                            color = RaftColor.ForegroundHint,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (item.kind == InboxItemKind.Thread) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RaftLucideIcon(
                                RaftLucideIconName.MessageSquare,
                                color = raftArgbHex(RaftColor.ForegroundHint),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = stringResource(Res.string.saved_meta_thread),
                                style = RaftType.Caption,
                                color = RaftColor.ForegroundHint,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    if (item.hasDraft) {
                        SharedHomeDraftPencilIcon()
                    }
                    sender?.let {
                        SharedHomeSavedSenderMeta(
                            senderName = it,
                            senderKind = item.latestActivitySenderKind ?: item.lastMessageSenderKind,
                            avatarWireValue = item.resolvedSenderAvatarWireValueForPreview(memberAvatarResolver),
                            avatarCacheIdentity = avatarCacheIdentity,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    time?.let { timestamp ->
                        RaftRelativeTimestampText(
                            value = timestamp,
                            timeContext = timeContext,
                            style = RaftType.Caption.copy(fontFamily = RaftMonoFamily),
                            color = RaftColor.ForegroundHint,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                // `text-callout`, three lines.
                Text(
                    text = preview,
                    style = RaftType.Button.withRaftFontWeight(FontWeight.Normal),
                    color = RaftColor.TextBlack,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SharedHomeSavedUnsaveButton(
                onClick = { dispatchSavedGesture(SharedHomeSavedGestureOwner.Unsave, source = "accessibility") }
            )
        }
    }
}

private data class SharedHomeSavedResultGestureCallbacks(
    val onTap: (Offset, Float, Float) -> Unit,
    val onLongPress: (Offset, Float, Float) -> Unit
)

/**
 * web SavedPanel toggle: a pressed PanelToggleAction drawn quiet (no sticker fill) on a phone,
 * 44dp, with the filled 22dp bookmark — every row is saved, so the filled mark is the state.
 */
@Composable
private fun SharedHomeSavedUnsaveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier =
            modifier
                .size(SavedUnsaveHitTargetSize)
                .zIndex(1f)
                .raftButtonAccessibility(enabled = true) { currentOnClick() },
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(
            RaftLucideIconName.Bookmark,
            color = RaftColor.TextBlackArgb,
            fill = RaftColor.TextBlackArgb,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * web SavedPanel sender: the 14dp `preview-mini` avatar, 4dp, the name in semibold ink. A
 * person without a photo shows the placeholder person (web GravatarAvatar), not initials.
 */
@Composable
private fun SharedHomeSavedSenderMeta(
    senderName: String,
    senderKind: MemberKind?,
    avatarWireValue: String?,
    avatarCacheIdentity: RaftMemberAvatarCacheIdentity? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val wireValue = avatarWireValue ?: savedSenderAvatarFallback(senderName)
        RaftAppAvatar(
            avatarWireValue = wireValue,
            label = if (wireValue == null && senderKind == MemberKind.Human) "" else senderName,
            avatarSize = 14.dp,
            cacheSubject = avatarCacheIdentity?.subject,
            cacheServerId = avatarCacheIdentity?.serverId
        )
        Text(
            text = senderName,
            style = RaftType.Caption.withRaftFontWeight(FontWeight.SemiBold),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class InboxPreviewSenderIdentity(
    val kind: MemberKind,
    val memberId: String,
    val avatarWireValue: String?
)

private fun InboxItem.previewSenderIdentityOrNull(): InboxPreviewSenderIdentity? {
    val latestId = latestActivitySenderId?.trim()?.takeIf(String::isNotEmpty)
    if (latestActivitySenderKind != null && latestId != null) {
        return InboxPreviewSenderIdentity(
            kind = latestActivitySenderKind,
            memberId = latestId,
            avatarWireValue = latestActivitySenderAvatarWireValue
        )
    }
    val lastId = lastMessageSenderId?.trim()?.takeIf(String::isNotEmpty)
    if (lastMessageSenderKind != null && lastId != null) {
        return InboxPreviewSenderIdentity(
            kind = lastMessageSenderKind,
            memberId = lastId,
            avatarWireValue = lastMessageSenderAvatarWireValue
        )
    }
    return null
}

private fun InboxItem.senderAvatarWireValueForPreview(): String? {
    val identity = previewSenderIdentityOrNull()
    return if (identity != null) {
        avatarWireValueForAvatarSlot(
            kind = identity.kind,
            id = identity.memberId,
            avatarWireValue = identity.avatarWireValue
        )
    } else {
        latestActivitySenderAvatarWireValue ?: lastMessageSenderAvatarWireValue
    }
}

internal fun InboxItem.previewSenderAvatarCacheIdentity(
    cacheServerId: String?
): RaftMemberAvatarCacheIdentity? {
    val identity = previewSenderIdentityOrNull() ?: return null
    return raftMemberAvatarCacheIdentity(
        kind = identity.kind,
        memberId = identity.memberId,
        serverId = cacheServerId
    )
}

@Composable
private fun InboxItem.resolvedSenderAvatarWireValueForPreview(
    memberAvatarResolver: RaftMemberAvatarResolver
): String? {
    val senderId = previewSenderMemberId()
    val memberAvatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, senderId)
    return memberAvatarWireValue ?: senderAvatarWireValueForPreview()
}

private fun InboxItem.previewSenderMemberId(): String? =
    previewSenderIdentityOrNull()?.memberId

private fun savedSenderAvatarFallback(senderName: String): String? =
    if (senderName.contains("developer", ignoreCase = true) || senderName.contains("agent", ignoreCase = true)) {
        "pixel:robot"
    } else {
        null
    }

// raft-ui SearchEmpty (EmptyState): search icon, headline title, callout description.
@Composable
private fun SharedHomeSearchEmptyState(
    title: String,
    detail: String
) {
    RaftSearchEmptyState(
        title = title,
        detail = detail,
        typography = RaftTheme.typography,
        modifier = Modifier.heightIn(min = 300.dp)
    )
}

// raft-ui SearchError: the EmptyState with the TriangleAlert icon, its copy and the retry
// action under it.
@Composable
private fun SharedHomeSearchErrorState(
    error: RaftHomeInlineError,
    showAction: Boolean,
    onAction: () -> Unit
) {
    val copy = error.toVisibleCopy()
    RaftSearchEmptyState(
        title = copy.title,
        detail = copy.detail,
        typography = RaftTheme.typography,
        iconName = RaftLucideIconName.TriangleAlert,
        actions =
            if (showAction) {
                {
                    RaftBrutalActionButton(
                        label = copy.actionLabel,
                        onClick = onAction,
                        leadingIcon = RaftLucideIconName.RefreshCw,
                        backgroundColor = RaftColor.SurfaceWhite
                    )
                }
            } else {
                null
            }
    )
}

@Composable
private fun SharedHomeSearchResultsCount(
    totalResults: Int,
    searching: Boolean
) {
    RaftSearchSummary(
        text =
            if (searching) {
                stringResource(Res.string.search_state_searching)
            } else {
                stringResource(Res.string.search_results_count, totalResults)
            },
        typography = RaftTheme.typography
    )
}

/**
 * Visual-fixture-only typography stress for the personalized Search home.
 *
 * Kuikly currently serializes TextStyle SP values without consuming an inner
 * LocalDensity fontScale override, so that override produced a byte-identical
 * 1.0x/1.3x screenshot. Scaling the real component styles here keeps the
 * production default byte-for-byte unchanged while exercising the actual
 * shared History/Frequent layout with larger glyph metrics on every renderer.
 */
internal fun TextStyle.withHomeSearchVisualTextScale(scale: Float): TextStyle {
    if (scale == 1f) return this
    val resolved = scale.coerceIn(1f, 2f)
    return copy(
        fontSize = fontSize * resolved,
        lineHeight = lineHeight * resolved,
        letterSpacing = letterSpacing * resolved,
    )
}

@Composable
private fun SharedHomeSearchSectionLabel(
    label: String,
    visualTextScale: Float = 1f,
) {
    RaftSearchSectionHeading(
        label = label,
        style =
            RaftTheme.typography.footnote
                .copy(fontWeight = FontWeight.Bold)
                .withHomeSearchVisualTextScale(visualTextScale)
    )
}

/**
 * search-message `messageHeader` / search-thread `threadMeta`: `font-mono text-mono-meta`
 * (12/16) in `foreground-hint` for the channel, thread and time meta, nested hits included.
 */
internal fun sharedHomeSearchTimestampTextStyle() =
    RaftType.Caption.copy(
        fontFamily = RaftMonoFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )

// raft-ui search-entity: a flat `px-4 py-3` row (min 64dp) on the panel, `gap-3` between a
// 27dp leading avatar / IconTile and the text; `text-body` semibold title with its type
// Badge, `text-callout` `foreground-muted` description; hairline separators from 55dp;
// pressed lays `fill-pressed`. An archived channel mutes its title and description.
@Composable
internal fun SharedHomeSearchEntityRow(
    result: SearchEntityResult,
    query: String,
    agentStatusResolver: RaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    isPinned: Boolean = false,
    showSeparator: Boolean = false,
    visualTextScale: Float = 1f,
    onOpen: () -> Unit,
    onContextMenu: ((RaftPopoverAnchor) -> Unit)? = null
) {
    val gestureKey = remember(result.key, isPinned, onContextMenu != null) { "search-entity:${result.key}:$isPinned" }
    val contentAnchor = remember(gestureKey) { RaftLiveRootAnchorRef(identity = gestureKey) }
    val gestureOwner =
        rememberRaftGestureOwner(
            surface = "home-search-entity",
            semanticIdentity = gestureKey,
            callbacks = SharedHomeSearchEntityGestureCallbacks(onOpen, onContextMenu, contentAnchor)
        )
    // Same delayed press as the message rows' clickable: a touch that becomes a scroll within
    // the tap timeout never flashes the pressed fill.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val mutedArchived = result.archived && result.type == SearchEntityType.Channel
    val typography = RaftTheme.typography
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = RaftSearchMetrics.EntityMinHeight)
                .raftSearchRowSeparator(showSeparator, RaftSearchMetrics.EntitySeparatorInset, RaftColor.BorderHairline)
                .raftSearchRowPressed(pressed, RaftColor.FillPressed)
                .raftCaptureLiveRootAnchor(contentAnchor)
                .raftGesturePointerInput(gestureOwner, interactionSource) { owner ->
                    var gesture: RaftGestureSession<SharedHomeSearchEntityGestureCallbacks>? = null
                    coroutineScope {
                        val pressScope = this
                        detectTapGestures(
                            onPress = { offset ->
                                gesture = owner.beginGesture()
                                raftDelayedRowPress(offset, interactionSource, pressScope) { tryAwaitRelease() }
                            },
                            onTap = {
                                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onOpen()
                            },
                            onLongPress = { localOffset ->
                                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                                    callbacks.onContextMenu?.let { callback ->
                                        callbacks.contentAnchor
                                            .resolve(RaftPopoverLocalPoint.Exact(localOffset))
                                            ?.let(callback)
                                    }
                                }
                            }
                        )
                    }
                }
                .padding(horizontal = RaftSearchMetrics.Inset, vertical = RaftSearchMetrics.RowPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(RaftSearchMetrics.EntityGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SharedHomeSearchEntityLeadingAvatar(result, agentStatusResolver, memberAvatarResolver)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftSearchHitText(
                    text = result.title,
                    query = query,
                    style =
                        typography.body
                            .copy(fontWeight = FontWeight.SemiBold)
                            .withHomeSearchVisualTextScale(visualTextScale),
                    color = if (mutedArchived) RaftColor.ForegroundHint else RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                SharedHomeEntityTypeBadge(result.type, muted = mutedArchived)
                if (result.archived) {
                    RaftBadge(
                        text = stringResource(Res.string.channel_settings_status_archived),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = if (mutedArchived) RaftBadgeVariant.Muted else RaftBadgeVariant.Warning
                    )
                }
            }
            // A channel matched by its description shows it cut like a message snippet (web
            // `searchEntitySubtitleForQuery`): only when the description holds a hit.
            val subtitle =
                if (result.type == SearchEntityType.Channel && result.subtitleDataRange == null) {
                    channelDescriptionSnippet(result.subtitle, query)
                } else {
                    result.subtitle
                }
            RaftSearchHitText(
                text = subtitle,
                query = query,
                highlightRange = result.subtitleDataRange.takeIf { subtitle == result.subtitle },
                style = typography.callout.withHomeSearchVisualTextScale(visualTextScale),
                color = if (mutedArchived) RaftColor.ForegroundHint else RaftColor.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class SharedHomeSearchEntityGestureCallbacks(
    val onOpen: () -> Unit,
    val onContextMenu: ((RaftPopoverAnchor) -> Unit)?,
    val contentAnchor: RaftLiveRootAnchorRef
)

internal data class SearchEntityPinTarget(
    val conversationId: String,
    val kind: String? = null,
    val id: String? = null
)

internal fun searchEntityPinTarget(result: SearchEntityResult): SearchEntityPinTarget? {
    if (result.archived) return null
    val threadId = result.threadId?.trim()?.takeIf { it.isNotBlank() }
    val memberId = result.memberId?.trim()?.takeIf { it.isNotBlank() }
    return when (result.type) {
        SearchEntityType.Channel ->
            threadId?.let { SearchEntityPinTarget(conversationId = it, kind = "channel", id = it) }
        SearchEntityType.DirectMessage ->
            threadId?.let { SearchEntityPinTarget(conversationId = it) }
        SearchEntityType.Human ->
            memberId?.let {
                SearchEntityPinTarget(
                    conversationId = threadId ?: it,
                    kind = "human",
                    id = it
                )
            }
        SearchEntityType.Agent ->
            memberId?.let {
                SearchEntityPinTarget(
                    conversationId = threadId ?: it,
                    kind = "agent",
                    id = it
                )
            }
        SearchEntityType.Computer -> null
    }
}

internal fun SearchEntityResult.isPinnedBy(items: List<SettingsServerSidebarPinnedItem>): Boolean {
    val target = searchEntityPinTarget(this) ?: return false
    return items.any { item ->
        when {
            target.kind != null && target.id != null ->
                item.kind.equals(target.kind, ignoreCase = true) && item.id == target.id ||
                    item.kind.equals("channel", ignoreCase = true) && item.id == target.conversationId
            else ->
                item.id == target.conversationId ||
                    memberId?.let { member ->
                        item.id == member &&
                            (item.kind.equals("agent", ignoreCase = true) ||
                                item.kind.equals("human", ignoreCase = true))
                    } == true
        }
    }
}

internal fun SettingsServerSidebarOrderSnapshot?.pinnedChannelConversationIds(): Set<String> =
    this
        ?.typedPinnedOrLegacy()
        .orEmpty()
        .asSequence()
        .filter { it.kind.trim().equals("channel", ignoreCase = true) }
        .map { it.id.trim() }
        .filter { it.isNotBlank() }
        .toSet()

// raft-ui search-entity Badge: `solid` `default`, or `soft` `muted` on an archived channel.
@Composable
private fun SharedHomeEntityTypeBadge(type: SearchEntityType, muted: Boolean = false) {
    RaftBadge(
        text =
            when (type) {
                SearchEntityType.Channel -> stringResource(Res.string.search_entity_channel)
                SearchEntityType.DirectMessage -> stringResource(Res.string.search_entity_dm)
                SearchEntityType.Human -> stringResource(Res.string.search_entity_dm)
                SearchEntityType.Agent -> stringResource(Res.string.search_entity_dm)
                SearchEntityType.Computer -> stringResource(Res.string.search_entity_computer)
            },
        appearance = if (muted) RaftBadgeAppearance.Soft else RaftBadgeAppearance.Solid,
        variant = if (muted) RaftBadgeVariant.Muted else RaftBadgeVariant.Default
    )
}

// raft-ui search-thread: a flat block (hairline separator from 16dp, no card or wash). The
// `px-4 pt-3 pb-1` header holds a mono-meta `foreground-hint` line (Thread Badge, #channel,
// hit count, time at the end) over the `text-callout` semibold title; the hits follow as
// search-message rows.
@Composable
private fun SharedHomeThreadResultGroup(
    group: HomeSearchResultGroup.Thread,
    query: String,
    showSeparator: Boolean = false,
    cacheServerId: String? = null,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    timeContext: AppShellThreadTimeContext? = null,
    onOpenItem: (InboxItem) -> Unit
) {
    val firstItem = group.items.firstOrNull()
    val sourceLabel = searchResultSourceLabel(firstItem ?: return)
    val latestTime = firstItem.latestActivityAt ?: firstItem.lastMessageAt
    val metaStyle = sharedHomeSearchTimestampTextStyle()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .raftSearchRowSeparator(showSeparator, RaftSearchMetrics.MessageSeparatorInset, RaftColor.BorderHairline)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = RaftSearchMetrics.Inset, top = 12.dp, end = RaftSearchMetrics.Inset, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RaftBadge(
                        text = stringResource(Res.string.search_meta_thread),
                        icon = {
                            RaftLucideIcon(
                                RaftLucideIconName.MessageSquare,
                                color = raftArgbHex(RaftColor.StickerInk),
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    )
                    Text(
                        text = sourceLabel,
                        style = metaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = stringResource(Res.string.search_hits_count, group.items.size),
                        style = metaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1
                    )
                    if (firstItem.hasDraft) {
                        SharedHomeDraftPencilIcon(compact = true)
                    }
                }
                if (!latestTime.isNullOrBlank()) {
                    RaftRelativeTimestampText(
                        value = latestTime,
                        timeContext = timeContext,
                        style = metaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1
                    )
                }
            }
            RaftSearchHitText(
                text = group.title,
                query = query,
                style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                color = RaftColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        group.items.forEachIndexed { index, item ->
            SharedHomeSearchMessageResultRow(
                item = item,
                query = query,
                nested = true,
                showSeparator = index > 0,
                cacheServerId = cacheServerId,
                memberAvatarResolver = memberAvatarResolver,
                timeContext = timeContext,
                onOpen = { onOpenItem(item) }
            )
        }
    }
}

// raft-ui search-message: a flat `px-4 py-3` row (hairline separator from 16dp, pressed
// `fill-pressed`). The header line is mono-meta `foreground-hint` with the sender first
// (14dp avatar, `text-callout` bold `foreground`), then #channel, thread, and the time at
// the end; the body is `text-callout` `foreground-muted`, two lines, 2dp below.
@Composable
internal fun SharedHomeSearchMessageResultRow(
    item: InboxItem,
    query: String,
    nested: Boolean = false,
    showSeparator: Boolean = false,
    cacheServerId: String? = null,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    timeContext: AppShellThreadTimeContext? = null,
    onOpen: () -> Unit
) {
    val avatarCacheIdentity =
        remember(item, cacheServerId) { item.previewSenderAvatarCacheIdentity(cacheServerId) }
    val preview = item.previewMarkdownText() ?: stringResource(Res.string.search_state_no_preview)
    // RFC 077 (web MessageHitRow): "Alice (sent by Friday)", text only.
    val sender = (item.latestActivitySenderName ?: item.lastMessageSenderName)?.let { delegatedPreviewSender(it, item.latestDelegatedBy) }
    val time = item.latestActivityAt ?: item.lastMessageAt
    val metaStyle = sharedHomeSearchTimestampTextStyle()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .raftSearchRowSeparator(showSeparator, RaftSearchMetrics.MessageSeparatorInset, RaftColor.BorderHairline)
                .raftSearchRowPressed(pressed, RaftColor.FillPressed)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onOpen)
                .padding(horizontal = RaftSearchMetrics.Inset, vertical = RaftSearchMetrics.RowPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!sender.isNullOrBlank()) {
                    SharedHomeSearchSenderMeta(
                        sender = sender,
                        avatarWireValue = item.resolvedSenderAvatarWireValueForPreview(memberAvatarResolver),
                        avatarCacheIdentity = avatarCacheIdentity,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                }
                if (!nested) {
                    Text(
                        text = searchResultSourceLabel(item),
                        style = metaStyle,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (item.kind == InboxItemKind.Thread && !nested) {
                    SharedHomeSearchThreadMeta(metaStyle)
                }
                if (item.hasDraft) {
                    SharedHomeDraftPencilIcon(compact = true)
                }
            }
            if (!time.isNullOrBlank()) {
                RaftRelativeTimestampText(
                    value = time,
                    timeContext = timeContext,
                    style = metaStyle,
                    color = RaftColor.ForegroundHint,
                    maxLines = 1
                )
            }
        }
        RaftSearchHitText(
            text = preview,
            query = query,
            style = RaftTheme.typography.callout,
            color = RaftColor.TextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// search-message `messageThreadMeta`: a 12dp message icon and "thread" in the meta type.
@Composable
private fun SharedHomeSearchThreadMeta(style: TextStyle) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(
            RaftLucideIconName.MessageSquare,
            color = raftArgbHex(RaftColor.ForegroundHint),
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = stringResource(Res.string.search_meta_thread),
            style = style,
            color = RaftColor.ForegroundHint,
            maxLines = 1
        )
    }
}

@Composable
private fun SharedHomeDraftPencilIcon(
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    RaftLucideIcon(
        RaftLucideIconName.Pencil,
        modifier = modifier.size(if (compact) 10.dp else 12.dp),
        color = RaftColor.TextMutedGrayArgb
    )
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

/** The raft-ui search hit spans; [RaftSearchHitText] draws the sticker behind them. */
internal fun homeSearchHighlightedAnnotatedString(
    text: String,
    query: String
): AnnotatedString = raftSearchHitAnnotatedString(text, homeSearchHighlightRanges(text, query))

/**
 * The distinct terms of [query] (web `searchHighlightTokens`), in query order. A leading "@" / "#"
 * only says what kind of thing is searched: "@remy" matches "Remy" and "@Remy" alike (the prefix
 * is taken from the text when it is there).
 */
internal fun homeSearchHighlightTokens(query: String): List<String> =
    query
        .trim()
        .split(Regex("\\s+"))
        .map { it.trim().trimStart('@', '#') }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

/**
 * The search hit spans of [query]'s whitespace-separated terms in [text], case-insensitive,
 * longest term first, never overlapping, in text order. Ranges are indices into [text]
 * itself: matching compares char by char (`regionMatches(ignoreCase = true)`) rather than
 * searching `text.lowercase()`, whose length can differ from [text] ("İ" lowercases to two
 * chars). Hits that touch ("foo" + "bar" in "foobar") merge into one span, so they draw as
 * one sticker.
 */
internal fun homeSearchHighlightRanges(
    text: String,
    query: String,
    within: IntRange? = null
): List<IntRange> {
    if (within != null) {
        val from = within.first.coerceIn(0, text.length)
        val to = (within.last + 1).coerceIn(from, text.length)
        return homeSearchHighlightRanges(text.substring(from, to), query).map { (it.first + from)..(it.last + from) }
    }
    if (text.isBlank()) return emptyList()
    val terms = homeSearchHighlightTokens(query).sortedByDescending { it.length }
    if (terms.isEmpty()) return emptyList()

    val occupied = BooleanArray(text.length)
    val ranges = mutableListOf<IntRange>()
    terms.forEach { term ->
        var start = 0
        while (start + term.length <= text.length) {
            if (!text.regionMatches(start, term, 0, term.length, ignoreCase = true)) {
                start++
                continue
            }
            val endExclusive = start + term.length
            // A hit on a handle or channel takes its "@" / "#" ("@Remy" is one unit, as when the
            // query has it).
            val hitStart = if (!isHandleStart(text, start - 1)) start else start - 1
            if ((hitStart until endExclusive).none { occupied[it] }) {
                ranges += hitStart until endExclusive
                for (index in hitStart until endExclusive) {
                    occupied[index] = true
                }
            }
            start = endExclusive
        }
    }
    return mergeAbuttingHighlightRanges(ranges.sortedBy { it.first })
}

private fun Char.isHitPrefix(): Boolean = this == '@' || this == '#'

/** True when [index] is an "@" / "#" that opens a handle or channel: not glued to a preceding word ("a@b.com"). */
private fun isHandleStart(text: String, index: Int): Boolean =
    index >= 0 && text[index].isHitPrefix() && (index == 0 || !text[index - 1].isLetterOrDigit())

/** Joins sorted, non-overlapping ranges that touch (`a.last + 1 == b.first`). */
private fun mergeAbuttingHighlightRanges(sorted: List<IntRange>): List<IntRange> {
    if (sorted.size < 2) return sorted
    val merged = mutableListOf(sorted.first())
    for (range in sorted.drop(1)) {
        val last = merged.last()
        if (range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
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
        RaftBrutalActionButton(
            label = if (loading) stringResource(Res.string.common_loading) else label,
            enabled = !loading,
            minWidth = 0.dp,
            minHeight = RaftMetrics.ControlHeightMd,
            // raft-ui Button `md` (32dp): `text-footnote` bold.
            textStyle = RaftType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp).withRaftFontWeight(FontWeight.Bold),
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
            label = stringResource(Res.string.search_sort_relevant),
            selected = sort == SearchSort.Relevance,
            onClick = { onSortChange(SearchSort.Relevance) }
        )
        SharedHomeSearchSortSegmentButton(
            label = stringResource(Res.string.search_sort_recent),
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
        background = if (selected) RaftColor.BrandYellow else RaftColor.SurfaceWhite,
        borderColor = RaftColor.BorderBlack,
        shadowColor = if (selected) RaftColor.ShadowBlack else Color.Transparent
    ) {
        Text(
            text = label,
            style = RaftType.Button.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.sp),
            color = if (selected) RaftColor.StickerInk else SearchMutedTextColor,
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
    borderColor: Color = RaftColor.BorderBlack,
    shadowColor: Color = RaftColor.ShadowBlack,
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

@Suppress("UNUSED_PARAMETER")
@Composable
private fun SharedHomeSearchShadowSurface(
    modifier: Modifier = Modifier,
    background: Color,
    borderColor: Color = RaftColor.BorderBlack,
    borderWidth: Dp = RaftAppStroke.Default,
    shadowColor: Color = RaftColor.ShadowBlack,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentPaddingHorizontal: Dp = 0.dp,
    contentPaddingVertical: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    // raft-ui: a selected (yellow) control is the brand sticker (1.5dp sticker-ink edge);
    // every other one is a ghost control. Control radius, no ink frame, no offset shadow.
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    val sticker = background == RaftColor.BrandYellow || background == RaftColor.Brand
    Box(modifier = modifier) {
        val surfaceModifier =
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(if (sticker) RaftColor.Brand else Color.Transparent, shape)
                .border(RaftAppStroke.Control, if (sticker) RaftColor.StickerInk else Color.Transparent, shape)
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
            !enabled -> RaftColor.TextMutedGray.copy(alpha = 0.18f)
            selected -> RaftColor.BrandYellow
            else -> RaftColor.SurfaceWhite
        }
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier =
            modifier
                .raftControlSurface(fill = background, border = if (enabled && selected) RaftColor.StickerInk else RaftColor.BorderBlack)
                .padding(horizontal = if (dense) 5.dp else 11.dp, vertical = if (dense) 6.dp else 7.dp)
    ) {
        Text(
            text = label,
            style = RaftType.SectionLabel.copy(fontSize = if (dense) 9.sp else 11.sp, lineHeight = if (dense) 11.sp else 13.sp),
            color = if (!enabled) RaftColor.TextMutedGray else if (selected) RaftColor.StickerInk else RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal data class SearchChannelFilterItem(
    val id: String,
    val label: String,
    val icon: RaftLucideIconName
)

private val SearchChannelFilterItem.iconName: RaftLucideIconName
    get() = icon

internal fun homeSearchChannelFilters(state: SharedHomeState): List<SearchChannelFilterItem> {
    val snapshot = (state.content as? SharedHomeContentState.Content)?.snapshot ?: return emptyList()
    val channelFilters =
        snapshot.channels
            .mapNotNull { conversation ->
                val channelId = conversation.channelId?.value ?: return@mapNotNull null
                SearchChannelFilterItem(
                    id = channelId,
                    label = conversation.name.removePrefix("#"),
                    icon = RaftLucideIconName.Hash
                )
            }
    val directMessageFilters =
        snapshot.directMessages
            .map { conversation ->
                SearchChannelFilterItem(
                    id = conversation.channelId?.value ?: conversation.threadId.value,
                    label = "@${conversation.name.removePrefix("@")}",
                    icon = if (conversation.isAgentDirectMessage()) RaftLucideIconName.Bot else RaftLucideIconName.CircleUser
                )
            }
    return (channelFilters + directMessageFilters)
        .distinctBy { it.id }
}

internal enum class SearchEntityType {
    Channel,
    DirectMessage,
    Human,
    Agent,
    Computer
}

internal data class HomeSearchComputerDirectory(
    val serverId: String? = null,
    val machines: List<ComputerSummary> = emptyList(),
    val status: SharedComputersLoadStatus = SharedComputersLoadStatus.Idle,
    val hasSnapshot: Boolean = false,
    val refreshing: Boolean = false,
    val initialLoadError: ScreenError? = null,
) {
    val initialLoadInFlight: Boolean
        get() = !hasSnapshot && status == SharedComputersLoadStatus.InFlight
}

internal fun SharedComputersState.toHomeSearchComputerDirectory(serverId: String?): HomeSearchComputerDirectory {
    val normalizedServerId = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return HomeSearchComputerDirectory()
    val slice = selectComputerServerSlice(normalizedServerId)
    return HomeSearchComputerDirectory(
        serverId = normalizedServerId,
        machines = slice.machines,
        status = slice.status,
        hasSnapshot = slice.hasSnapshot,
        refreshing = slice.refreshing,
        initialLoadError = slice.error.takeIf { !slice.hasSnapshot && slice.status == SharedComputersLoadStatus.Error },
    )
}

/** The shared snippet of a channel [description] when it holds a hit of [query], else the description as is. */
internal fun channelDescriptionSnippet(description: String, query: String): String =
    if (homeSearchHighlightRanges(description, query).isEmpty()) {
        description
    } else {
        buildSearchSnippet(description, homeSearchHighlightTokens(query))
    }

internal data class SearchEntityResult(
    val key: String,
    val type: SearchEntityType,
    val title: String,
    val subtitle: String,
    val threadId: String?,
    val memberIdentity: MemberDetailIdentity?,
    val conversationIdentity: ThreadLaunchConversationIdentity?,
    val computerIdentity: SettingsComputerRouteIdentity? = null,
    val avatarWireValue: String? = null,
    val showPresence: Boolean = false,
    val archived: Boolean = false,
    /** The part of [subtitle] that is real data (a handle, a description); the rest is a fixed label that is never marked. Null = all of it. */
    val subtitleDataRange: IntRange? = null
) {
    val memberId: String?
        get() = memberIdentity?.memberId?.value
}

internal fun ThreadLaunchConversationIdentity.homeSearchChannelLeadingIconName(): RaftLucideIconName =
    when {
        kind == ConversationKind.Joint -> RaftLucideIconName.GitBranch
        channelType.equals("private", ignoreCase = true) -> RaftLucideIconName.Lock
        else -> RaftLucideIconName.Hash
    }

internal fun SearchEntityResult.homeSearchEntityLeadingIconName(): RaftLucideIconName? =
    when (type) {
        SearchEntityType.Channel -> conversationIdentity?.homeSearchChannelLeadingIconName() ?: RaftLucideIconName.Hash
        SearchEntityType.Computer -> RaftLucideIconName.Monitor
        SearchEntityType.DirectMessage,
        SearchEntityType.Human,
        SearchEntityType.Agent -> null
    }

internal sealed interface HomeSearchEntityDestination {
    data class Conversation(val identity: ThreadLaunchConversationIdentity) : HomeSearchEntityDestination
    data class Member(val identity: MemberDetailIdentity) : HomeSearchEntityDestination
    data class Computer(val identity: SettingsComputerRouteIdentity) : HomeSearchEntityDestination
}

internal fun SearchEntityResult.destinationOrNull(): HomeSearchEntityDestination? {
    computerIdentity?.let { identity ->
        settingsComputerRouteIdentity(identity.serverId, identity.machineId)?.let {
            return HomeSearchEntityDestination.Computer(it)
        }
    }
    conversationIdentity?.let { return HomeSearchEntityDestination.Conversation(it) }
    val identity = memberIdentity ?: return null
    if (identity.memberId.value.isBlank() || identity.sourceServerId?.value.isNullOrBlank()) return null
    if (identity.kind != MemberKind.Human && identity.kind != MemberKind.Agent) return null
    return HomeSearchEntityDestination.Member(identity)
}

private sealed interface HomeSearchResultGroup {
    data class Message(val item: InboxItem) : HomeSearchResultGroup
    data class Thread(
        val key: String,
        val title: String,
        val items: List<InboxItem>
    ) : HomeSearchResultGroup
}

private fun groupHomeSearchResults(
    items: List<InboxItem>,
    threadDiscussionLabel: String
): List<HomeSearchResultGroup> {
    val groups = mutableListOf<HomeSearchResultGroup>()
    val threadGroups = linkedMapOf<String, MutableList<InboxItem>>()
    items.forEach { item ->
        val threadKey = item.threadChannelId?.value?.takeIf { item.kind == InboxItemKind.Thread }
        if (threadKey == null) {
            groups += HomeSearchResultGroup.Message(item)
            return@forEach
        }
        val existing = threadGroups.getOrPut(threadKey) {
            mutableListOf<InboxItem>().also {
                groups += HomeSearchResultGroup.Thread(
                    threadKey,
                    item.parentMessagePreview?.let(::markdownToPreviewText)?.takeIf { it.isNotBlank() } ?: threadDiscussionLabel,
                    it
                )
            }
        }
        existing += item
    }
    return groups
}

@Composable
private fun SharedHomeSearchPanelState.senderFilterOptions(): List<RaftFilterOption> {
    val meLabel = stringResource(Res.string.search_sender_me)
    return senderFilters
        .map { sender ->
            RaftFilterOption(
                id = sender.id,
                label = if (sender.isSelf) meLabel else sender.displayLabel,
                icon = if (sender.kind == MemberKind.Agent) RaftLucideIconName.Bot else RaftLucideIconName.CircleUser
            )
        }
        .distinctBy { it.id }
}

@Composable
private fun SharedHomeSearchPanelState.localizedSenderFilterLabel(): String {
    val selectedId = selectedSenderId?.trim()?.takeIf { it.isNotBlank() }
        ?: return stringResource(Res.string.search_filter_from)
    val meLabel = stringResource(Res.string.search_sender_me)
    val selected = senderFilters.firstOrNull { it.id == selectedId }
    return stringResource(
        Res.string.search_filter_from_value,
        selected?.let { if (it.isSelf) meLabel else it.displayLabel } ?: selectedId
    )
}

private val SearchSenderFilterItem.displayLabel: String
    get() = label.ifBlank { handle.ifBlank { id } }

internal fun homeSearchEntityResults(
    query: String,
    homeState: SharedHomeState,
    senderFilters: List<SearchSenderFilterItem>,
    channelLabel: String,
    dmLabel: String,
    agentLabel: String,
    personLabel: String,
    membersLabel: String,
    computerLabel: String = "Computer",
    computerDirectory: HomeSearchComputerDirectory = HomeSearchComputerDirectory(),
): List<SearchEntityResult> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) return emptyList()
    val candidates =
        homeSearchEntityCandidates(
            homeState = homeState,
            senderFilters = senderFilters,
            channelLabel = channelLabel,
            dmLabel = dmLabel,
            agentLabel = agentLabel,
            personLabel = personLabel,
            membersLabel = membersLabel,
            computerLabel = computerLabel,
            computerDirectory = computerDirectory,
        )
    val entries =
        candidates
            .let { unfiltered ->
                when (trimmedQuery.firstOrNull()) {
                    '#' -> unfiltered.filter { it.result.type == SearchEntityType.Channel }
                    '@' ->
                        unfiltered.filter {
                            it.result.type == SearchEntityType.Human ||
                                it.result.type == SearchEntityType.Agent
                        }
                    else -> unfiltered
                }
            }
            .mapIndexed { index, candidate ->
                ThreadComposerSuggestionSearchEntry(
                    index = index,
                    suggestion = candidate.result,
                    fields =
                        candidate.searchFields.map { field ->
                            ThreadComposerSuggestionSearchField(field.value, priority = field.priority)
                        },
                )
            }
    return rankThreadComposerSuggestionEntries(query = trimmedQuery, entries = entries)
        .distinctBy { it.key }
        .take(5)
}

internal data class HomeSearchEntityCandidate(
    val result: SearchEntityResult,
    val searchFields: List<HomeSearchEntitySearchField>,
)

internal data class HomeSearchEntitySearchField(
    val value: String,
    val priority: Int,
)

internal fun homeSearchEntityCandidates(
    homeState: SharedHomeState,
    senderFilters: List<SearchSenderFilterItem>,
    channelLabel: String,
    dmLabel: String,
    agentLabel: String,
    personLabel: String,
    membersLabel: String,
    computerLabel: String = "Computer",
    computerDirectory: HomeSearchComputerDirectory = HomeSearchComputerDirectory(),
): List<HomeSearchEntityCandidate> {
    val snapshot = (homeState.content as? SharedHomeContentState.Content)?.snapshot ?: return emptyList()
    val conversations = snapshot.channels + snapshot.directMessages
    val homeServerId = snapshot.server.id.value
    // task #238: this map is an identity index, so it needs an owner scope and must reject blank
    // ids. Keyed by a bare memberId, a joint-channel member carried in from another server could
    // collide with a local DM peer; and a blank wire peerId (`MemberId("")`) survived the null
    // check and sat in the map as a key that can only ever join unrelated rows. A missing scope
    // dimension does not cost an extra row - it merges two identities into one.
    val byPresenceMemberId =
        snapshot.directMessages
            .mapNotNull { conversation ->
                val scope =
                    homeSearchMemberScopeKey(homeServerId, conversation.presenceMemberId?.value.orEmpty())
                        ?: return@mapNotNull null
                scope to conversation.toThreadLaunchConversationIdentity()
            }
            .toMap()
    val conversationEntries =
        conversations.map { conversation ->
            val type =
                if (conversation.kind == ConversationKind.DirectMessage) {
                    SearchEntityType.DirectMessage
                } else {
                    SearchEntityType.Channel
                }
            val conversationIdentity = conversation.toThreadLaunchConversationIdentity()
            HomeSearchEntityCandidate(
                result =
                    SearchEntityResult(
                        key = "conversation:${conversation.threadId.value}",
                        type = type,
                        title = conversation.name,
                        subtitle =
                            conversation.subtitle?.takeIf { it.isNotBlank() }
                                ?: if (type == SearchEntityType.Channel) channelLabel else dmLabel,
                        subtitleDataRange = if (conversation.subtitle.isNullOrBlank()) IntRange.EMPTY else null,
                        threadId = conversation.threadId.value,
                        memberIdentity = conversationIdentity.dmPeerIdentity,
                        conversationIdentity = conversationIdentity,
                        avatarWireValue = null,
                        showPresence = conversation.isAgentDirectMessage(),
                        archived = conversation.archived
                    ),
                searchFields =
                    listOf(
                        HomeSearchEntitySearchField(conversation.name, priority = 0),
                        HomeSearchEntitySearchField(conversation.subtitle.orEmpty(), priority = 3),
                        HomeSearchEntitySearchField(conversation.channelId?.value.orEmpty(), priority = 5),
                        HomeSearchEntitySearchField(conversation.threadId.value, priority = 5),
                    )
            )
        }
    val dmCandidatesByThreadId =
        conversationEntries
            .asSequence()
            .filter { candidate -> candidate.result.type == SearchEntityType.DirectMessage }
            .mapNotNull { candidate -> candidate.result.threadId?.let { threadId -> threadId to candidate } }
            .toMap()
    // task #238: a DM row and its member row are the same entity reached through two disjoint key
    // namespaces ("conversation:<threadId>" vs "sender:<server>:<kind>:<memberId>"), so the
    // trailing distinctBy() was structurally unable to collapse them. Merge from the member side:
    // it is the richer row, and its `byPresenceMemberId` hit only needs presenceMemberId, whereas
    // the conversation side's dmPeerIdentity additionally requires a parseable peer kind - i.e.
    // strictly weaker coverage. The member row already carries the DM launch affordance via
    // conversationIdentity, so it also absorbs the DM row's search fields before that row is
    // dropped, keeping DM name/subtitle/threadId queryable.
    val absorbedDmThreadIds = mutableSetOf<String>()
    val memberEntries =
        senderFilters.mapNotNull { sender ->
            val conversation =
                homeSearchMemberScopeKey(sender.sourceServerId.value, sender.id)
                    ?.let { scope -> byPresenceMemberId[scope] }
            val suggestion =
                sender.toHomeSearchEntityResult(
                    conversationIdentity = conversation,
                    agentLabel = agentLabel,
                    personLabel = personLabel,
                    membersLabel = membersLabel,
                ) ?: return@mapNotNull null
            val absorbedDm =
                conversation?.threadId?.value?.let { threadId -> dmCandidatesByThreadId[threadId] }
            absorbedDm?.result?.threadId?.let { threadId -> absorbedDmThreadIds += threadId }
            HomeSearchEntityCandidate(
                result = suggestion,
                searchFields =
                    listOf(
                        HomeSearchEntitySearchField(sender.displayLabel, priority = 0),
                        HomeSearchEntitySearchField(sender.handle, priority = 1),
                        HomeSearchEntitySearchField(sender.id, priority = 5),
                    ) + absorbedDm?.searchFields.orEmpty()
            )
        }
    val computerEntries =
        computerDirectory.machines
            .takeIf { computerDirectory.serverId == snapshot.server.id.value }
            .orEmpty()
            .asSequence()
            .filter { it.isComputer }
            .distinctBy { it.id }
            .mapNotNull { machine ->
                val computerIdentity =
                    settingsComputerRouteIdentity(
                        serverId = computerDirectory.serverId,
                        machineId = machine.id,
                    ) ?: return@mapNotNull null
                val title = machine.name.trim().ifBlank { machine.hostname?.trim().orEmpty().ifBlank { machine.id } }
                HomeSearchEntityCandidate(
                    result =
                        SearchEntityResult(
                            key = "computer:${computerIdentity.serverId}:${computerIdentity.machineId}",
                            type = SearchEntityType.Computer,
                            title = title,
                            subtitle = machine.hostname?.trim().orEmpty().ifBlank { computerLabel },
                            subtitleDataRange = if (machine.hostname?.trim().isNullOrEmpty()) IntRange.EMPTY else null,
                            threadId = null,
                            memberIdentity = null,
                            conversationIdentity = null,
                            computerIdentity = computerIdentity,
                        ),
                    searchFields =
                        listOf(
                            HomeSearchEntitySearchField(machine.name, priority = 0),
                            HomeSearchEntitySearchField(machine.hostname.orEmpty(), priority = 1),
                            HomeSearchEntitySearchField(machine.description.orEmpty(), priority = 3),
                            HomeSearchEntitySearchField(machine.os.orEmpty(), priority = 4),
                        ),
                )
            }
            .toList()
    val visibleConversationEntries =
        conversationEntries.filterNot { candidate ->
            candidate.result.type == SearchEntityType.DirectMessage &&
                candidate.result.threadId?.let { threadId -> threadId in absorbedDmThreadIds } == true
        }
    return (visibleConversationEntries + memberEntries + computerEntries).distinctBy { it.result.key }
}

/**
 * Owner scope for the DM-peer identity index used by [homeSearchEntityCandidates].
 *
 * A member id is only unique within its own server, so an identity key that omits the server can
 * merge two distinct people. Callers pass the server the row was sourced from: DM rows come from
 * the current Home snapshot, member rows carry their own `sourceServerId` (joint channels surface
 * members from other servers).
 *
 * Deliberately a typed pair rather than a "$serverId:$memberId" string: a concatenated key is
 * delimiter-ambiguous, so ("a:b", "c") and ("a", "b:c") would collide into one entry - the same
 * wrong-merge failure this scope exists to prevent. Blank components yield no key at all, so a
 * blank wire peer id can never become a merge key that joins unrelated rows.
 */
private data class HomeSearchMemberScope(val serverId: String, val memberId: String)

private fun homeSearchMemberScopeKey(serverId: String, memberId: String): HomeSearchMemberScope? {
    val scopedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    val scopedMemberId = memberId.trim().takeIf { it.isNotBlank() } ?: return null
    return HomeSearchMemberScope(serverId = scopedServerId, memberId = scopedMemberId)
}

internal fun homeSearchFrequentEntityResults(
    homeState: SharedHomeState,
    senderFilters: List<SearchSenderFilterItem>,
    ranks: Map<SearchPersonalizationEntityKey, SearchFrequentEntityRank>,
    channelLabel: String,
    dmLabel: String,
    agentLabel: String,
    personLabel: String,
    membersLabel: String,
): List<SearchEntityResult> {
    if (ranks.isEmpty()) return emptyList()
    val snapshot = (homeState.content as? SharedHomeContentState.Content)?.snapshot ?: return emptyList()
    val currentMemberId = snapshot.currentMember.id.value
    val hiddenDmIds = snapshot.sidebarOrder?.hiddenDmIds.orEmpty().toSet()
    val eligibleConversationKeys =
        (snapshot.channels + snapshot.directMessages)
            .asSequence()
            .filter { conversation ->
                conversation.joined &&
                    conversation.permissions.canRead &&
                    !conversation.archived &&
                    (
                        conversation.kind != ConversationKind.DirectMessage ||
                            (
                                conversation.threadId.value !in hiddenDmIds &&
                                    conversation.channelId?.value !in hiddenDmIds
                            )
                    )
            }
            .mapNotNull { conversation ->
                val kind =
                    if (conversation.kind == ConversationKind.DirectMessage) {
                        SearchPersonalizationEntityKind.DirectMessage
                    } else {
                        SearchPersonalizationEntityKind.Channel
                    }
                SearchPersonalizationEntityKey.create(
                    kind,
                    conversation.channelId?.value ?: conversation.threadId.value,
                )
            }
            .toSet()
    val candidates =
        homeSearchEntityCandidates(
            homeState = homeState,
            senderFilters = senderFilters,
            channelLabel = channelLabel,
            dmLabel = dmLabel,
            agentLabel = agentLabel,
            personLabel = personLabel,
            membersLabel = membersLabel,
        )
    return candidates
        .mapNotNull { candidate ->
            val result = candidate.result
            val key = result.searchPersonalizationEntityKeyOrNull() ?: return@mapNotNull null
            val rank = ranks[key] ?: return@mapNotNull null
            if (result.archived || result.destinationOrNull() == null) return@mapNotNull null
            if (
                (key.kind == SearchPersonalizationEntityKind.Channel ||
                    key.kind == SearchPersonalizationEntityKind.DirectMessage) &&
                key !in eligibleConversationKeys
            ) {
                return@mapNotNull null
            }
            if (
                (result.type == SearchEntityType.Human || result.type == SearchEntityType.Agent) &&
                result.memberId == currentMemberId
            ) {
                return@mapNotNull null
            }
            Triple(result, key, rank)
        }
        .sortedWith(
            compareByDescending<Triple<SearchEntityResult, SearchPersonalizationEntityKey, SearchFrequentEntityRank>> {
                it.third.score
            }.thenByDescending { it.third.lastOpenedAtMillis }
                .thenBy { it.first.title.lowercase() }
                .thenBy { it.second.storageValue }
        )
        .take(SearchFrequentEntityLimit)
        .map { it.first }
}

internal fun SearchEntityResult.searchPersonalizationEntityKeyOrNull(): SearchPersonalizationEntityKey? =
    when (type) {
        SearchEntityType.Channel ->
            SearchPersonalizationEntityKey.create(
                SearchPersonalizationEntityKind.Channel,
                conversationIdentity?.channelId?.value ?: conversationIdentity?.threadId?.value,
            )
        SearchEntityType.DirectMessage ->
            SearchPersonalizationEntityKey.create(
                SearchPersonalizationEntityKind.DirectMessage,
                conversationIdentity?.channelId?.value ?: conversationIdentity?.threadId?.value,
            )
        SearchEntityType.Human ->
            SearchPersonalizationEntityKey.create(SearchPersonalizationEntityKind.Human, memberId)
        SearchEntityType.Agent ->
            SearchPersonalizationEntityKey.create(SearchPersonalizationEntityKind.Agent, memberId)
        SearchEntityType.Computer -> null
    }

internal fun SearchSenderFilterItem.toHomeSearchEntityResult(
    conversationIdentity: ThreadLaunchConversationIdentity?,
    agentLabel: String,
    personLabel: String,
    membersLabel: String,
): SearchEntityResult? {
    if (kind != MemberKind.Agent && kind != MemberKind.Human) return null
    val identity = memberDetailIdentity(id, kind, sourceServerId.value) ?: return null
    return SearchEntityResult(
        key = "sender:${sourceServerId.value}:$kind:${identity.memberId.value}",
        type = if (kind == MemberKind.Agent) SearchEntityType.Agent else SearchEntityType.Human,
        title = displayLabel,
        subtitle =
            buildString {
                append(if (kind == MemberKind.Agent) agentLabel else personLabel)
                if (handle.isNotBlank()) append(" · @$handle")
                if (conversationIdentity == null) append(" · $membersLabel")
            },
        // Only the "@handle" is data; "Agent" and "Members" are fixed labels.
        subtitleDataRange =
            if (handle.isBlank()) {
                IntRange.EMPTY
            } else {
                val start = (if (kind == MemberKind.Agent) agentLabel else personLabel).length + " · ".length
                start until start + 1 + handle.length
            },
        threadId = conversationIdentity?.threadId?.value,
        memberIdentity = identity,
        conversationIdentity = conversationIdentity,
        avatarWireValue = avatarWireValue ?: AvatarReference.Random(identity.memberId.value).wireValue,
        showPresence = kind == MemberKind.Agent,
    )
}

private fun HomeDirectMessageRow.isAgentDirectMessage(): Boolean =
    kind == ConversationKind.DirectMessage &&
        directMessagePeerType.equals("agent", ignoreCase = true)

private fun HomeConversationRow.isAgentDirectMessage(): Boolean =
    kind == ConversationKind.DirectMessage &&
        directMessagePeerType.equals("agent", ignoreCase = true)

@Composable
private fun SearchScope.localizedSearchLabel(): String =
    stringResource(
        when (this) {
            SearchScope.Mentioned -> Res.string.search_scope_mentioned
            SearchScope.Humans -> Res.string.search_scope_humans
            SearchScope.Agents -> Res.string.search_scope_agents
        }
    )

@Composable
private fun SearchTimeRange.localizedSearchLabel(): String =
    stringResource(
        when (this) {
            SearchTimeRange.Any -> Res.string.search_time_any
            SearchTimeRange.Today -> Res.string.search_time_today
            SearchTimeRange.Last7Days -> Res.string.search_time_last_7_days
            SearchTimeRange.Last30Days -> Res.string.search_time_last_30_days
        }
    )

@Composable
private fun SearchSort.localizedSearchLabel(): String =
    stringResource(
        when (this) {
            SearchSort.Relevance -> Res.string.search_sort_relevant
            SearchSort.Recent -> Res.string.search_sort_recent
        }
    )

private val SearchSort.searchKey: String
    get() =
        when (this) {
            SearchSort.Relevance -> "relevance"
            SearchSort.Recent -> "recent"
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
    serverDrawerEnabled: Boolean = false,
    homeServerAvatarEnabled: Boolean = false,
    serverSwitcherInTopBar: Boolean = false,
    serverDrawerSections: List<RaftHomeServerDrawerSection> = emptyList(),
    scrollToTopRequest: Int = 0,
    serverUnreadCounts: Map<String, Int> = emptyMap(),
    serverActivityExpectedOwner: ReadReceiptOwner? = null,
    serverActivityProjection: HomeServerActivityUnreadProjection = HomeServerActivityUnreadProjection(),
    prefersChineseCommunity: Boolean = false,
    topSafeAreaOverride: Dp? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    // #372: true only for the Loading-with-session branch — renders
    // skeleton rows under the CHANNELS / DIRECT MESSAGES headers so the
    // post-sign-in transition matches the React app instead of a bare shell.
    loadingSkeletons: Boolean = false,
    searchAndActivityOnShellNav: Boolean = false,
    inlineError: RaftHomeInlineError? = null,
    onInlineErrorAction: () -> Unit = {},
    onServerBadgeClick: () -> Unit,
    onServerSwitcherDismiss: () -> Unit,
    onServerDrawerSectionToggle: (String) -> Unit,
    onServerDrawerRetry: (String) -> Unit,
    onServerSelected: (String) -> Unit,
    onServerOrderChange: (List<String>) -> Unit = {},
    onCreateServerClick: () -> Unit,
    onInviteHuman: ((serverId: String) -> Unit)? = null,
    onCreateChannelClick: () -> Unit
) {
    val currentServer = session.currentServerSummary()
    val currentServerId = currentServer?.id?.value ?: session.currentServerId.orEmpty()
    RaftStructuralHomeShell(
        currentServerName = currentServer?.name ?: "No server",
        currentServerId = currentServerId,
        servers =
            currentServer?.let {
                sharedHomeSwitcherItems(
                    session = session,
                    selectedServer = it,
                    serverUnreadCounts = serverUnreadCounts,
                    activityExpectedOwner = serverActivityExpectedOwner,
                    activityProjection = serverActivityProjection,
                )
            }.orEmpty(),
        channels = emptyList(),
        directMessages = emptyList(),
        showLoadingSkeletons = loadingSkeletons,
        inlineError = inlineError,
        serverSwitcherExpanded = serverSwitcherExpanded,
        serverDrawerEnabled = serverDrawerEnabled,
        homeServerAvatarEnabled = homeServerAvatarEnabled,
        serverSwitcherInTopBar = serverSwitcherInTopBar,
        serverDrawerSections = serverDrawerSections,
        prefersChineseCommunity = prefersChineseCommunity,
        topSafeAreaOverride = topSafeAreaOverride,
        showBottomBar = false,
        searchAndActivityOnShellNav = searchAndActivityOnShellNav,
        bottomContentPadding = bottomContentPadding,
        outerPadding = 0.dp,
        labels = RaftHomeShellLabels.localized(),
        onInlineErrorAction = onInlineErrorAction,
        onServerBadgeClick = onServerBadgeClick,
        onServerSwitcherDismiss = onServerSwitcherDismiss,
        onServerDrawerSectionToggle = onServerDrawerSectionToggle,
        onServerDrawerRetry = onServerDrawerRetry,
        onServerSelected = onServerSelected,
        onServerOrderChange = onServerOrderChange,
        onCreateServerClick = onCreateServerClick,
        onInviteHumanClick = homeServerSwitcherInviteClick(currentServer, onInviteHuman, onServerSwitcherDismiss),
        onCreateChannelClick = onCreateChannelClick,
        scrollToTopRequest = scrollToTopRequest,
    )
}

/**
 * The server switcher's "Invite Human" action for [server], or null when the
 * viewer lacks `inviteMembers` (web `ServerSwitcherMenu`). Like web, it closes
 * the switcher before opening the dialog.
 */
internal fun homeServerSwitcherInviteClick(
    server: ServerSummary?,
    onInviteHuman: ((serverId: String) -> Unit)?,
    onDismissSwitcher: () -> Unit
): (() -> Unit)? {
    if (onInviteHuman == null || server == null || !serverSwitcherShowsInviteHuman(server)) return null
    val serverId = server.id.value
    return {
        onDismissSwitcher()
        onInviteHuman(serverId)
    }
}

internal fun StoredAuthSession.currentServerSummary(): ServerSummary? {
    val currentId = currentServerId
    return servers.firstOrNull { it.id == currentId }?.toServerSummary()
        ?: servers.firstOrNull()?.toServerSummary()
}

private fun ServerSummary.toSharedSwitcherItem(
    serverUnreadCounts: Map<String, Int> = emptyMap(),
    activityExpectedOwner: ReadReceiptOwner? = null,
    activityProjection: HomeServerActivityUnreadProjection = HomeServerActivityUnreadProjection(),
): RaftServerSwitcherItem {
    val serverOwner = activityExpectedOwner?.copy(serverId = id.value)
    return RaftServerSwitcherItem(
        id = id.value,
        name = name,
        slug = slug ?: description?.removePrefix("/") ?: id.value,
        avatarUrl = avatarUrl,
        broaderUnreadCount = serverUnreadCounts[id.value] ?: unreadCount,
        activityUnreadCount = activityProjection.countFor(serverOwner),
        serverPushMuted = activityProjection.serverPushMutedFor(serverOwner),
        publicPageLive = publicPageLive,
    )
}

internal fun sharedHomeSwitcherItems(
    session: StoredAuthSession,
    selectedServer: ServerSummary,
    serverUnreadCounts: Map<String, Int>,
    activityExpectedOwner: ReadReceiptOwner?,
    activityProjection: HomeServerActivityUnreadProjection,
): List<RaftServerSwitcherItem> =
    session.servers.map { server ->
        server.toServerSummary().let { summary ->
            if (summary.id == selectedServer.id) {
                selectedServer.copy(
                    avatarUrl = selectedServer.avatarUrl ?: summary.avatarUrl,
                    // publicPageLive only comes from the server list (session), never
                    // from a Home snapshot, so the session row stays authoritative.
                    publicPageLive = summary.publicPageLive
                )
            } else {
                summary
            }
        }.toSharedSwitcherItem(
            serverUnreadCounts = serverUnreadCounts,
            activityExpectedOwner = activityExpectedOwner,
            activityProjection = activityProjection,
        )
    }.ifEmpty {
        listOf(
            selectedServer.toSharedSwitcherItem(
                serverUnreadCounts = serverUnreadCounts,
                activityExpectedOwner = activityExpectedOwner,
                activityProjection = activityProjection,
            )
        )
    }

internal data class SharedHomeConversationRowSeed(
    val id: String,
    val kind: ConversationKind,
    val unreadCount: Int = 0,
    val lastMessageAt: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageSenderName: String? = null,
    val fallbackAvatarWireValue: String? = null,
    val peerType: String? = null,
    val fallbackPresenceMemberId: String? = null,
    val fallbackName: String = "",
    val fallbackChannelType: String? = null,
    val fallbackDescription: String? = null,
    val fallbackJoined: Boolean = true,
    val fallbackArchived: Boolean = false,
    val fallbackActivityMuted: Boolean = false,
    val fallbackActivityMuteSupported: Boolean = false,
    val fallbackBridgeProvider: String? = null,
    val fallbackGuestVisible: Boolean = false,
    /** Unread summary `hasMention` (or a followed thread's), carried by the store. */
    val mentioned: Boolean = false,
    /** Followed threads under this channel with new replies, carried by the store. */
    val followedThreadUnreadCount: Int = 0
)

internal data class SharedHomeConversationRowProjector(
    val directoryProjections: Map<String, SharedConversationDirectoryProjection> = emptyMap(),
    val draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    /** task #166: cold-start hints from [SharedHomeContentState.Content.displayRowHints]. */
    val restoredRowHints: Map<String, HomeRowRestoredHint> = emptyMap(),
    /**
     * [HomeSnapshot.followedThreadUnread] folded per parent channel. Empty when the server gates the
     * thread mark off (web `thread_unread_badge_v0`), which hides both the count and the thread's @.
     */
    val threadUnreadByParent: Map<ChannelId, HomeParentThreadUnread> = emptyMap()
) {
    fun project(conversation: HomeConversationRow): RaftHomeConversationItem {
        // Only channel rows carry the followed-thread segment (web `SidebarChannelItem`).
        val threadUnread =
            if (conversation is HomeChannelRow) threadUnreadByParent[conversation.channelId] else null
        return project(
            conversation.toSharedHomeConversationRowSeed()
                .copy(
                    mentioned = conversation.mentionCount > 0 || threadUnread?.hasMention == true,
                    followedThreadUnreadCount = threadUnread?.count ?: 0
                )
                .withRestoredHint(
                    // A known count (read prediction, realtime) is newer than anything restored.
                    restoredRowHints[conversation.threadId.value]?.takeIf { !conversation.unreadCountKnown }
                )
        )
    }

    fun project(seed: SharedHomeConversationRowSeed): RaftHomeConversationItem =
        seed.toSharedHomeConversationItem(
            directoryProjection = directoryProjections[seed.id],
            draftProjection = draftProjection
        )
}

internal fun HomeServerDrawerState.toRaftHomeServerDrawerSections(
    serverUnreadCounts: Map<String, Int> = emptyMap(),
    activityExpectedOwner: ReadReceiptOwner? = null,
    activityProjection: HomeServerActivityUnreadProjection = HomeServerActivityUnreadProjection(),
): List<RaftHomeServerDrawerSection> {
    val projector = SharedHomeConversationRowProjector()
    return sections.values.map { section ->
        val snapshot = section.content?.snapshot
        val sidebarOrder = snapshot?.sidebarOrder
        RaftHomeServerDrawerSection(
            server =
                section.server.toSharedSwitcherItem(
                    serverUnreadCounts = serverUnreadCounts,
                    activityExpectedOwner = activityExpectedOwner,
                    activityProjection = activityProjection,
                ),
            expanded = section.server.id in expandedServerIds,
            status = section.status,
            requestGeneration = section.requestGeneration,
            errorCode = section.errorCode,
            channels = snapshot?.channels.orEmpty().map(projector::project),
            directMessages = snapshot?.directMessages.orEmpty().map(projector::project),
            channelOrder = sidebarOrder?.channelOrder.orEmpty(),
            channelSortMode = sidebarOrder?.channelSortMode ?: "manual",
            dmOrder = sidebarOrder?.dmOrder.orEmpty(),
            dmSortMode = sidebarOrder?.dmSortMode ?: "manual",
            pinnedChannelIds = sidebarOrder?.pinnedChannelIds.orEmpty(),
            pinnedAgentIds = sidebarOrder?.pinnedAgentIds.orEmpty(),
            pinnedOrder = sidebarOrder?.pinnedOrder.orEmpty(),
            pinnedItems = sidebarOrder?.pinned.orEmpty(),
            pinnedSortMode = sidebarOrder?.pinnedSortMode ?: "manual",
            hiddenDmIds = sidebarOrder?.hiddenDmIds.orEmpty()
        )
    }
}

internal fun HomeServerDrawerState.toRaftHomeServerDrawerHostedSections(
    serverUnreadCounts: Map<String, Int> = emptyMap(),
    activityExpectedOwner: ReadReceiptOwner? = null,
    activityProjection: HomeServerActivityUnreadProjection = HomeServerActivityUnreadProjection(),
): List<RaftHomeServerDrawerHostedSection> =
    sections.values.map { section ->
        RaftHomeServerDrawerHostedSection(
            header =
                RaftHomeServerDrawerSection(
                    server =
                        section.server.toSharedSwitcherItem(
                            serverUnreadCounts = serverUnreadCounts,
                            activityExpectedOwner = activityExpectedOwner,
                            activityProjection = activityProjection,
                        ),
                    expanded = section.server.id in expandedServerIds,
                    status = section.status,
                    requestGeneration = section.requestGeneration,
                    errorCode = section.errorCode
                ),
            normalizedContent = section.normalizedContent,
            hasCachedConversations =
                section.content?.snapshot?.let { it.channels.isNotEmpty() || it.directMessages.isNotEmpty() } == true
        )
    }

internal fun SharedHomeConversationRowSeed.withRestoredHint(hint: HomeRowRestoredHint?): SharedHomeConversationRowSeed {
    if (hint == null) return this
    val usePreview = lastMessagePreview.isNullOrBlank() && !hint.lastMessagePreview.isNullOrBlank()
    return copy(
        unreadCount = if (unreadCount > 0) unreadCount else hint.unreadCount.coerceAtLeast(0),
        lastMessagePreview = if (usePreview) hint.lastMessagePreview else lastMessagePreview,
        lastMessageAt = if (usePreview) hint.lastMessageAt ?: lastMessageAt else lastMessageAt,
        lastMessageSenderName = if (usePreview) hint.lastMessageSenderName else lastMessageSenderName
    )
}

private fun HomeConversationRow.toSharedHomeConversationRowSeed(): SharedHomeConversationRowSeed =
    SharedHomeConversationRowSeed(
        id = threadId.value,
        kind = kind,
        unreadCount = unreadCount,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = lastMessagePreview,
        lastMessageSenderName = lastMessageSenderName,
        fallbackAvatarWireValue = directMessagePeerAvatarWireValue?.takeIf { it.isNotBlank() },
        peerType = directMessagePeerType?.takeIf { it.isNotBlank() },
        fallbackPresenceMemberId = presenceMemberId?.value,
        fallbackName = name,
        fallbackChannelType = channelType?.takeIf { it.isNotBlank() },
        fallbackDescription = subtitle?.takeIf { it.isNotBlank() },
        fallbackJoined = joined && permissions.canRead,
        fallbackArchived = archived,
        fallbackActivityMuted = activityMuted,
        fallbackActivityMuteSupported = activityMuteSupported,
        fallbackBridgeProvider = bridgeProvider,
        fallbackGuestVisible = guestVisible
    )

private fun SharedHomeConversationRowSeed.toSharedHomeConversationItem(
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    directoryProjection: SharedConversationDirectoryProjection? = null
): RaftHomeConversationItem {
    val title = resolvedDirectoryTitle(directoryProjection)
    val projectedChannelType =
        directoryProjection
            ?.takeIf { projection -> projection.conversationKind == kind }
            ?.channelKind
            ?.takeIf { it.isNotBlank() }
    return RaftHomeConversationItem(
        id = id,
        name = title,
        // The row seed is the Home identity source. Directory projection enriches display/state
        // fields, but cannot rewrite the row's conversation kind (§5).
        // task #444: serialize through the LOSSLESS wire kind, not the surface projection —
        // toRaftSurfaceKind() folds Joint into the channel surface (contract rule: the
        // surface projection is lossy), which made the Joint Channels section filter
        // (kind == "joint") never match and the section vanish. The surface fallback only
        // covers kinds without a wire channel kind (Inbox/System), which render as channel
        // surfaces anyway.
        kind = kind.toWireChannelKindOrNull() ?: kind.toRaftSurfaceKind(),
        channelType = projectedChannelType ?: fallbackChannelType,
        unreadCount = unreadCount,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = lastMessagePreview,
        lastMessageSenderName = lastMessageSenderName,
        description = directoryProjection?.subtitle ?: fallbackDescription,
        avatarWireValue = directoryProjection?.avatarWireValue ?: fallbackAvatarWireValue,
        peerType = peerType,
        presenceMemberId = directoryProjection?.peerIdentity?.memberId?.value ?: fallbackPresenceMemberId,
        hasDraft = draftProjection.hasRootDraft(id),
        joined = directoryProjection?.joined ?: fallbackJoined,
        archived = directoryProjection?.archived ?: fallbackArchived,
        activityMuted = directoryProjection?.activityMuted ?: fallbackActivityMuted,
        activityMuteSupported = directoryProjection?.activityMuteSupported ?: fallbackActivityMuteSupported,
        bridgeProvider = directoryProjection?.bridgeProvider ?: fallbackBridgeProvider,
        // The Home row seed is the canonical channel row; a directory projection from a
        // sparse source (inbox) never carries guestVisible, so it may only add, not clear.
        guestVisible = fallbackGuestVisible || directoryProjection?.guestVisible == true,
        mentioned = mentioned,
        followedThreadUnreadCount = followedThreadUnreadCount
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

private fun Presence?.toSharedPresence(): RaftAppPresence? =
    when (this) {
        Presence.Active -> RaftAppPresence.Online
        Presence.Away -> RaftAppPresence.Unknown
        Presence.Offline -> RaftAppPresence.Offline
        null -> null
    }

internal typealias AppShellTimeContext = RaftTimeContext
internal typealias AppShellThreadTimeContext = RaftTimeContext

internal fun StoredAuthSession?.appShellThreadTimeFormat(): String? =
    listOf(this?.user?.preferredTimeFormat, this?.user?.timeFormat)
        .firstOrNull { it == "12h" || it == "24h" }

private fun AppShellTimeContext?.withTimeFormat(timeFormat: String?): AppShellTimeContext? =
    this?.withTimeFormat(timeFormat)

internal fun build.raft.app.compat.json.JSONObject.threadTimeCapabilities(): RaftTimeCapabilities? {
    val offset = optString("localOffsetMinutes").toIntOrNull() ?: return null
    return RaftTimeContext(
        localOffsetMinutes = offset,
        timeFormat = optString("timeFormat").takeIf { it.isNotBlank() } ?: RaftTimeFormat24Hour
    ).toRaftTimeCapabilities()
}

internal data class AppShellNotificationThreadLaunchRequest(
    val threadId: String,
    val messageId: String,
    val channelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val conversationKind: ConversationKind? = null,
    val category: String? = null
)

internal fun notificationLaunchHomeContentReady(
    content: SharedHomeContentState.Content?,
    targetServerId: String?,
    selectedServerId: String?
): Boolean {
    val target = targetServerId?.trim().takeIf { !it.isNullOrBlank() } ?: return false
    val readyContent = content ?: return false
    return readyContent.snapshot.server.id.value == target && selectedServerId == target
}

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
            conversationKind = parseConversationKindOrNull(optString(AppShellNotificationKindKey)),
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
        parentMessageId = payload.parentMessageId?.takeIf { it.isNotBlank() },
        conversationKind = if (payload.parentMessageId.isNullOrBlank()) null else ConversationKind.Thread
    )
}

private fun JSONObject.appShellNotificationServerId(): String? =
    optString(AppShellNotificationServerIdKey).trim().takeIf { it.isNotBlank() }

/**
 * Migration bridge for existing notification pageData. The serialized legacy thread payload is
 * still decoded above, but it enters the AppShell only as a server-qualified typed intent. Once
 * enqueued, the window-scoped coordinator owns retry, readiness, and exactly-once consumption.
 */
internal fun JSONObject.appShellWindowNavigationIntent(fallbackServerId: String?): WindowNavigationIntent? {
    val request = appShellNotificationThreadLaunchRequest() ?: return null
    val serverId = appShellNotificationServerId() ?: fallbackServerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return WindowNavigationIntent(
        serverId = serverId,
        sourceIdentity = request.toConversationOpenSourceMessageIdentity(),
        source = "system_notification"
    )
}

internal fun JSONObject?.appShellSystemNotificationNavigationIntent(): WindowNavigationIntent? =
    this
        ?.takeIf {
            it.optString(AppShellNotificationKindKey).trim() in setOf("channel", "dm", "thread")
        }
        ?.appShellWindowNavigationIntent(fallbackServerId = null)

internal fun JSONObject.appShellDesktopShortcutNavigationIntent(fallbackServerId: String?): WindowNavigationIntent? {
    val targetId = optString(build.raft.app.data.RaftDesktopShortcutContract.KeyTargetId).trim().takeIf { it.isNotBlank() } ?: return null
    val serverId = optString(build.raft.app.data.RaftDesktopShortcutContract.KeyServerId).trim().takeIf { it.isNotBlank() }
        ?: fallbackServerId?.trim()?.takeIf { it.isNotBlank() }
        ?: return null
    val targetKind = optString(build.raft.app.data.RaftDesktopShortcutContract.KeyTargetKind).trim().lowercase()
    val launchId = optString(build.raft.app.data.RaftDesktopShortcutContract.KeyLaunchId).trim().takeIf { it.isNotBlank() }
    val conversationKind = if (targetKind == build.raft.app.data.RaftDesktopShortcutContract.TargetKindChannel) {
        ConversationKind.Channel
    } else {
        ConversationKind.DirectMessage
    }
    return WindowNavigationIntent(
        serverId = serverId,
        sourceIdentity = ConversationOpenSourceMessageIdentity(
            source = ConversationOpenSource.AppShortcut,
            messageId = null,
            channelId = targetId,
            threadChannelId = targetId,
            conversationKind = conversationKind
        ),
        source = "app_shortcut",
        launchId = launchId
    )
}

internal fun JSONObject?.appShellDesktopShortcutEventNavigationIntent(): WindowNavigationIntent? =
    this?.appShellDesktopShortcutNavigationIntent(fallbackServerId = null)

internal fun SharedConversationDirectoryState.notificationThreadLaunchRequest(
    request: AppShellNotificationThreadLaunchRequest,
    serverId: String = ""
): HomeMessageOpenRequest? {
    return when (
        val resolution =
            resolveOpenRequest(
                serverId = serverId,
                sourceMessageIdentity = request.toConversationOpenSourceMessageIdentity(),
                conversationDirectory = this
            )
    ) {
        is ConversationOpenResolution.Resolved -> resolution.request
        is ConversationOpenResolution.Pending -> null
    }
}

internal fun AppShellThreadTimeContext?.toSearchIsoBounds(range: SearchTimeRange): SearchIsoBounds {
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
        afterIso = afterDay?.let { raftStartOfDayUtcIso(it, context.localOffsetMinutes) },
        beforeIso = context.nowEpochMillis?.let(::raftEpochMillisUtcIso)
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

internal fun appShellSessionIdentityChanged(
    cachedSession: StoredAuthSession?,
    currentSession: StoredAuthSession?
): Boolean =
    cachedSession?.user?.id != currentSession?.user?.id

internal fun appShellSessionChangeRestoreIntent(identityChanged: Boolean): SharedAuthGateIntent =
    if (identityChanged) SharedAuthGateIntent.Restore else SharedAuthGateIntent.RefreshSilently

internal enum class AppShellBootstrapAuthSignal {
    AuthRequired,
    SessionInvalidated,
    SessionChanged,
    BindingMetadataChanged
}

internal data class AppShellBootstrapAuthDecision(
    val intent: SharedAuthGateIntent,
    val resetSessionState: Boolean
)

internal fun appShellBootstrapAuthDecision(
    signal: AppShellBootstrapAuthSignal,
    identityChanged: Boolean = false
): AppShellBootstrapAuthDecision =
    when (signal) {
        AppShellBootstrapAuthSignal.AuthRequired,
        AppShellBootstrapAuthSignal.SessionInvalidated ->
            AppShellBootstrapAuthDecision(
                intent = SharedAuthGateIntent.SignedOut,
                resetSessionState = true
            )
        AppShellBootstrapAuthSignal.SessionChanged ->
            AppShellBootstrapAuthDecision(
                intent = appShellSessionChangeRestoreIntent(identityChanged),
                resetSessionState = identityChanged
            )
        AppShellBootstrapAuthSignal.BindingMetadataChanged ->
            AppShellBootstrapAuthDecision(
                intent = SharedAuthGateIntent.RefreshSilently,
                resetSessionState = false
            )
    }

internal fun appShellAuthSessionEventRestoreIntent(event: AuthSessionEvent): SharedAuthGateIntent? =
    if (event is AuthSessionEvent.BindingMetadataChanged) {
        SharedAuthGateIntent.RefreshSilently
    } else {
        null
    }

private fun SharedAuthGateState.authGateDiagnosticsName(): String =
    when (this) {
        SharedAuthGateState.Restoring -> "restoring"
        SharedAuthGateState.AuthRequired -> "auth_required"
        is SharedAuthGateState.EmailVerificationRequired -> "email_verification_required"
        is SharedAuthGateState.ProfileSetupRequired -> "profile_setup_required"
        is SharedAuthGateState.ServerRequired -> "server_required"
        is SharedAuthGateState.Ready -> "ready"
        is SharedAuthGateState.BootstrapRecoveryRequired -> "qa_bootstrap_recovery"
        is SharedAuthGateState.Error -> "error"
    }

internal fun buildAppShellThreadPayload(
    conversation: ThreadLaunchConversationIdentity,
    timeContext: AppShellThreadTimeContext? = null,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    anchorMessageId: String? = null,
    originFallback: ThreadLaunchOriginFallback? = null,
    accessState: ThreadLaunchAccessState = ThreadLaunchAccessState(),
    onWarn: (String) -> Unit = {}
): RaftThreadScreenPayload? {
    val routeIdentity = conversation.resolveThreadRouteIdentity()
    val surfaceKind = routeIdentity.surfaceKind
    val isReplyThread = surfaceKind == RaftThreadSurfaceKind.ReplyThread
    // Trust the source tuple: parent channel+message ids alone are enough to open the
    // reply thread. Only a missing tuple is unrecoverable here; an unresolved parent
    // kind stays observable via parentSurfaceKind/parentChannelKind = null (#304/#307).
    if (isReplyThread && routeIdentity.parentKey == null) {
        onWarn("thread_launch_rejected reason=reply_identity_incomplete source=app_shell threadId=${conversation.threadId.value}")
        return null
    }
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
    val routeChannelKind = routeIdentity.channelKind
    val parentSurfaceKind =
        if (isReplyThread) {
            // Defensive: every live path resolves the parent kind (resolver-only exits,
            // t5/#419). A null here means a caller bypassed the resolver — log loudly; if
            // this ever fires it escalates to a t8 hardening item.
            conversation.threadParentSurfaceKind()
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
    // Joint channels have task surfaces like any channel (server task surface mapping).
    val supportsChannelTasks = accessState.permissions.canCreateTask
    val composer =
        RaftThreadComposerPayload(
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
            routeIdentity = routeIdentity,
            originMessage = null,
            parentMessageId = parentMessageId,
            originFallback = originFallback
        )
    val payload = RaftThreadScreenPayload(
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
        dmPeerIdentity = conversation.dmPeerIdentity.takeIf { surfaceKind == RaftThreadSurfaceKind.DirectMessage },
        memberScopedMentions = conversation.usesMemberScopedMentions(),
        originParentMessageId = originParentMessageId,
        focusedMessageId = anchorMessageId?.takeIf { it.isNotBlank() },
        selectedTab = "chat",
        navigationLabel = "Back",
        showModeTabs = !isReplyThread,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        showOpenInChannelAction = isReplyThread && parentMessageId != null && parentSurfaceKind != null,
        joined = accessState.joined,
        canSend = canSend,
        membersCount = 0,
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: RaftTimeFormat24Hour,
        showMembersAction = !isReplyThread && surfaceKind != RaftThreadSurfaceKind.DirectMessage,
        showSettingsAction = !isReplyThread && surfaceKind != RaftThreadSurfaceKind.DirectMessage,
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
    serverId: String,
    conversationDirectory: SharedConversationDirectoryState,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    storeScope: RaftShellStoreScope
) {
    val launchPayload =
        buildStandaloneTaskThreadLaunchPayload(
            task = task,
            serverId = serverId,
            conversationDirectory = conversationDirectory,
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
        )
            ?: return
    openStandaloneThreadLaunchPayload(launchPayload, storeScope)
}

private fun Pager.openStandaloneThreadLaunchPayload(
    launchPayload: WireThreadLaunchPayload,
    storeScope: RaftShellStoreScope
) {
    openRaftPage(
        pageName = THREAD_PAGE,
        pageData = threadLaunchPageData(launchPayload),
        storeScopeOverride = storeScope
    )
}

internal fun Pager.closeHomeSecondaryPage() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
}

private fun Pager.openHomeSecondaryPage(
    destination: String,
    timeContext: AppShellThreadTimeContext?,
    storeScope: RaftShellStoreScope
) {
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
            put("timeFormat", timeContext?.timeFormat ?: RaftTimeFormat24Hour)
        }
    openRaftPage(
        pageName = pageName,
        pageData = pageData,
        storeScopeOverride = storeScope
    )
}

internal fun Pager.openStandaloneHomeMessageRequest(
    bridge: build.raft.app.base.BridgeModule,
    request: HomeMessageOpenRequest,
    source: String,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    storeScope: RaftShellStoreScope
): Boolean =
    openStandaloneHomeConversation(
        bridge = bridge,
        conversation = request.conversationIdentity,
        source = source,
        timeContext = timeContext,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        storeScope = storeScope,
        anchorMessageId = request.anchorMessageId,
        originFallback = request.originFallback
    )

private fun Pager.openStandaloneHomeConversation(
    bridge: build.raft.app.base.BridgeModule,
    conversation: ThreadLaunchConversationIdentity,
    source: String,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    storeScope: RaftShellStoreScope,
    anchorMessageId: String? = null,
    originFallback: ThreadLaunchOriginFallback? = null
): Boolean {
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
            anchorMessageId = anchorMessageId,
            originFallback = originFallback,
            onWarn = { bridge.logWarn(it) }
        ) ?: return false
    bridge.log(
        "home_open_thread_launch source=$source threadId=${conversation.threadId.value} " +
            "sourceThreadId=${launchPayload.sourceThreadId.orEmpty()} surface=${launchPayload.surfaceKind} " +
            "parentChannelId=${launchPayload.parentChannelId.orEmpty()} parentMessageId=${launchPayload.parentMessageId.orEmpty()} " +
            "anchorMessageId=${anchorMessageId.orEmpty()} originParentMessageId=${launchPayload.originParentMessageId.orEmpty()}"
    )
    openStandaloneThreadLaunchPayload(launchPayload, storeScope)
    return true
}

internal fun Pager.openHomeSearchEntity(
    bridge: build.raft.app.base.BridgeModule,
    entity: SearchEntityResult,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    storeScope: RaftShellStoreScope
): Boolean {
    return when (val destination = entity.destinationOrNull()) {
        is HomeSearchEntityDestination.Conversation -> {
            openStandaloneHomeConversation(
                bridge = bridge,
                conversation = destination.identity,
                source = "home_search_entity",
                timeContext = timeContext,
                alignOwnMessagesRight = alignOwnMessagesRight,
                compactConsecutiveMessages = compactConsecutiveMessages,
                    storeScope = storeScope
            )
        }
        is HomeSearchEntityDestination.Member -> {
            val pageData = homeSearchMemberDetailPageData(destination.identity, timeContext) ?: return false
            openRaftPage(
                pageName = MEMBER_DETAIL_PAGE,
                pageData = pageData,
                storeScopeOverride = storeScope
            )
            true
        }
        is HomeSearchEntityDestination.Computer -> {
            openSettingsComputerPage(
                storeScope = storeScope,
                target = destination.identity,
            )
            true
        }
        null -> false
    }
}

internal fun ThreadLaunchConversationIdentity.searchPersonalizationEntityKeyOrNull(): SearchPersonalizationEntityKey? =
    when (kind) {
        ConversationKind.DirectMessage ->
            SearchPersonalizationEntityKey.create(
                SearchPersonalizationEntityKind.DirectMessage,
                channelId?.value ?: threadId.value,
            )
        ConversationKind.Channel,
        ConversationKind.Joint ->
            SearchPersonalizationEntityKey.create(
                SearchPersonalizationEntityKind.Channel,
                channelId?.value ?: threadId.value,
            )
        ConversationKind.Thread -> {
            val parentKind =
                typedReplyParentConversationKindOrNull(
                    parentSurfaceKind = threadParentSurfaceKind(),
                    parentChannelKind = threadParentChannelKind(),
                )
            val parentId = threadParentChannelId() ?: channelId?.value
            when (parentKind) {
                ConversationKind.DirectMessage ->
                    SearchPersonalizationEntityKey.create(SearchPersonalizationEntityKind.DirectMessage, parentId)
                ConversationKind.Channel,
                ConversationKind.Joint ->
                    SearchPersonalizationEntityKey.create(SearchPersonalizationEntityKind.Channel, parentId)
                else -> null
            }
        }
        ConversationKind.Inbox,
        ConversationKind.System -> null
    }

internal fun homeSearchMemberDetailPageData(
    identity: MemberDetailIdentity,
    timeContext: AppShellThreadTimeContext?,
): JSONObject? {
    val memberId = identity.memberId.value.trim().takeIf { it.isNotBlank() } ?: return null
    val sourceServerId = identity.sourceServerId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val instanceId = "home-search-member-$memberId"
    val payload =
        JSONObject().apply {
            put("instanceId", instanceId)
            put("memberId", memberId)
            put("memberKind", identity.kind.memberKindWireValue())
            put("sourceServerId", sourceServerId)
            put("localOffsetMinutes", timeContext?.localOffsetMinutes ?: 0)
            timeContext?.todayEpochDay?.let { put("todayEpochDay", it) }
            timeContext?.nowEpochMillis?.let { put("nowEpochMillis", it) }
            put("timeFormat", timeContext?.timeFormat ?: RaftTimeFormat24Hour)
        }
    val pageData =
        JSONObject().apply {
            put("pageName", MEMBER_DETAIL_PAGE)
            put("payload", payload.toString())
            put("instanceId", instanceId)
        }
    return pageData
}

internal fun buildStandaloneConversationThreadLaunchPayload(
    conversation: ThreadLaunchConversationIdentity,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    anchorMessageId: String? = null,
    originFallback: ThreadLaunchOriginFallback? = null,
    instanceIdOverride: String? = null,
    onWarn: (String) -> Unit = {}
): WireThreadLaunchPayload? {
    val routeIdentity = conversation.resolveThreadRouteIdentity()
    val surfaceKind = routeIdentity.surfaceKind
    val isReplyThread = surfaceKind == RaftThreadSurfaceKind.ReplyThread
    // Same contract as buildAppShellThreadPayload: source tuple alone is sufficient;
    // only a missing parentKey rejects the launch (#304/#307).
    if (isReplyThread && routeIdentity.parentKey == null) {
        onWarn("thread_launch_rejected reason=reply_identity_incomplete source=standalone threadId=${conversation.threadId.value}")
        return null
    }
    val streamChannelId =
        if (isReplyThread) {
            conversation.threadStreamChannelId()
        } else {
            conversation.channelId?.value ?: conversation.threadId.value
        }
    val parentChannelId = if (isReplyThread) conversation.threadParentChannelId() else null
    val parentMessageId = if (isReplyThread) conversation.threadParentMessageId() else null
    val focusedMessageId = anchorMessageId?.takeIf { it.isNotBlank() }
    return WireThreadLaunchPayload(
        instanceId = instanceIdOverride?.takeIf { it.isNotBlank() } ?: "shared-shell-thread-${conversation.threadId.value}",
        surfaceKind = surfaceKind,
        channelKind =
            when (surfaceKind) {
                RaftThreadSurfaceKind.ReplyThread -> conversation.threadParentChannelKind()
                RaftThreadSurfaceKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
                else -> routeIdentity.channelKind
            },
        threadId =
            when (surfaceKind) {
                RaftThreadSurfaceKind.ReplyThread,
                RaftThreadSurfaceKind.DirectMessage -> streamChannelId
                else -> null
            },
        sourceThreadId = streamChannelId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        parentSurfaceKind =
            if (isReplyThread) {
                // Defensive (see buildAppShellThreadPayload): null parent kind here means a
                // resolver bypass — log loudly, never silently.
                conversation.threadParentSurfaceKind()
            } else {
                null
            },
        parentChannelKind = if (isReplyThread) conversation.threadParentChannelKind() else null,
        dmPeerIdentity = conversation.dmPeerIdentity.takeIf { surfaceKind == RaftThreadSurfaceKind.DirectMessage },
        originParentMessageId = if (isReplyThread) parentMessageId else null,
        focusedMessageId = focusedMessageId,
        focusedMessageRequestNonce =
            if (focusedMessageId != null) {
                nextThreadFocusedMessageRequestNonce()
            } else {
                0
            },
        selectedTab = "chat",
        navigationLabel = "Back",
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: RaftTimeFormat24Hour,
        originFallbackParentChannelId = originFallback?.parentChannelId,
        originFallbackParentMessageId = originFallback?.parentMessageId,
        originFallbackPreview = originFallback?.preview,
        originFallbackSenderId = originFallback?.senderId,
        originFallbackSenderKind = originFallback?.senderKind,
        originFallbackExpected = originFallback?.expected ?: true
    )
}

internal fun buildStandaloneTaskThreadLaunchPayload(
    task: TaskSummary,
    serverId: String,
    conversationDirectory: SharedConversationDirectoryState,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    parentConversationKind: ConversationKind? = null
): WireThreadLaunchPayload? {
    val request =
        task.resolveTaskThreadOpenRequest(
            serverId = serverId,
            conversationDirectory = conversationDirectory,
            parentConversationKind = parentConversationKind
        ) ?: return null
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = request.conversationIdentity,
        timeContext = timeContext,
        anchorMessageId = request.anchorMessageId,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages
    )
}

private fun RealtimeEvent.performanceMarkerName(): String =
    when (this) {
        is MessageNew -> "message_new"
        is MessageUpdated -> "message_updated"
        is DirectMessageCreated -> "dm_new"
        is ThreadFollowersUpdated -> "thread_followers_updated"
        is ScopeReadUpdated -> "scope_read_updated"
        is AgentTypingUpdated -> "agent_typing"
        is ThreadUpdated -> "thread_updated"
        is TaskCreated -> "task_created"
        is TaskUpdated -> "task_updated"
        is TaskDeleted -> "task_deleted"
        is MemberPresenceUpdated -> "presence_updated"
        is RealtimeRefreshRequired -> "refresh_${scope.name}"
        is ServerMembershipRemoved -> "server_membership_removed"
        is ServerDeleted -> "server_deleted"
        is ServerPlanUpdated -> "server_plan_updated"
        is ServerFeaturesChanged -> "server_features_changed"
        is ActivityChanged -> "activity_changed"
        is ComputerRestartDone -> "computer_restart_done"
        is AgentCreated -> "agent_created"
        is SyncResumeResponse -> "sync_resume"
        is Heartbeat -> "heartbeat"
        is PerReceiverReadStateChanged -> "read_state_updated"
        is PerReceiverNotificationPrefsChanged -> "notification_prefs_updated"
        is ServerNotificationPreferencesChanged -> "server_notification_prefs_updated"
        is PerReceiverPinnedChanged -> "pinned_updated"
        is NotificationPushReceived -> "notification_push"
    }

private fun RealtimeEvent.performanceMarkerPhase(): String =
    when (this) {
        is MemberPresenceUpdated -> "realtime_presence_apply"
        is MessageNew,
        is MessageUpdated,
        is ThreadFollowersUpdated,
        is ScopeReadUpdated,
        is AgentTypingUpdated,
        is ThreadUpdated,
        is TaskCreated,
        is TaskUpdated,
        is TaskDeleted,
        is DirectMessageCreated,
        is SyncResumeResponse -> "realtime_content_apply"
        is RealtimeRefreshRequired,
        is ServerFeaturesChanged,
        is ActivityChanged,
        is ComputerRestartDone -> "realtime_refresh_apply"
        is ServerMembershipRemoved,
        is ServerDeleted,
        is ServerPlanUpdated,
        is AgentCreated -> "realtime_membership_apply"
        is Heartbeat -> "realtime_heartbeat"
        is PerReceiverReadStateChanged,
        is PerReceiverNotificationPrefsChanged,
        is ServerNotificationPreferencesChanged,
        is PerReceiverPinnedChanged -> "realtime_content_apply"
        is NotificationPushReceived -> "realtime_notification_apply"
    }

private fun RealtimeEvent.shouldEmitAppShellRouteMarker(): Boolean =
    this !is Heartbeat && this !is MemberPresenceUpdated

internal fun decodeSharedShellRealtimeEvent(
    eventData: JsonObject,
    mapper: RealtimeEventMapper,
    log: (String) -> Unit
): RealtimeEvent? {
    // Raw path: eventName + rawPayload -> common mapper (canonical for Android)
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
    eventData: JsonObject,
    event: RealtimeEvent,
    @Suppress("UNUSED_PARAMETER") session: StoredAuthSession?,
): String? =
    eventData.optString("serverId").takeIf { it.isNotBlank() }
        ?: event.serverIdHint()?.value

internal suspend fun applySelectedServerTerminalRealtimeTransition(
    grant: RealtimeGrantRecord,
    eventServerId: String,
    isSelected: (String) -> Boolean,
    runIfCurrentGrant: suspend (RealtimeGrantRecord, suspend () -> Unit) -> Boolean,
    disconnectAndReset: suspend () -> Unit,
    clearSelectedServer: suspend () -> Unit,
    restoreAuthGate: suspend () -> Unit,
): Boolean {
    var applied = false
    val accepted =
        runIfCurrentGrant(grant) {
            if (isSelected(eventServerId)) {
                disconnectAndReset()
                clearSelectedServer()
                restoreAuthGate()
                applied = true
            }
        }
    return accepted && applied
}

internal fun JsonObject.realtimeConnectionWitnessOrNull(): RealtimeConnectionWitness? {
    val scopeLeaseId = optString(RaftRealtimeNotifyContract.KeyAuthorityScopeLeaseId).trim()
    val principalId = optString(RaftRealtimeNotifyContract.KeyAuthorityPrincipalId).trim()
    val authGeneration = optLong(RaftRealtimeNotifyContract.KeyAuthorityAuthGeneration, 0L)
    val connectionFamilyId = optString(RaftRealtimeNotifyContract.KeyAuthorityConnectionFamilyId).trim()
    if (
        scopeLeaseId.isBlank() ||
        principalId.isBlank() ||
        authGeneration <= 0L ||
        connectionFamilyId.isBlank()
    ) {
        return null
    }
    return RealtimeConnectionWitness(
        scopeLeaseId = scopeLeaseId,
        principalId = principalId,
        authGeneration = authGeneration,
        connectionFamilyId = connectionFamilyId,
    )
}

internal fun shouldRouteRealtimeEventToShell(shellServerId: String?, eventServerId: String?): Boolean {
    val shellServer = shellServerId?.trim().takeUnless { it.isNullOrBlank() } ?: return false
    val eventServer = eventServerId?.trim().takeUnless { it.isNullOrBlank() } ?: return false
    return shellServer == eventServer
}

internal data class AppShellPresenceTransportCursor(
    val connectionFamilyId: String,
    val transportEpoch: Long
)

internal fun nextAppShellPresenceTransportCursor(
    previous: AppShellPresenceTransportCursor?,
    connectionFamilyId: String?,
    transportEpoch: Long
): AppShellPresenceTransportCursor? {
    val family = connectionFamilyId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (transportEpoch <= 0L) return null
    if (
        previous != null &&
        previous.connectionFamilyId == family &&
        transportEpoch <= previous.transportEpoch
    ) {
        return null
    }
    return AppShellPresenceTransportCursor(
        connectionFamilyId = family,
        transportEpoch = transportEpoch
    )
}

internal fun shouldProjectBackgroundServerUnread(
    session: StoredAuthSession?,
    shellServerId: String?,
    eventServerId: String?
): Boolean {
    val shellServer = shellServerId?.trim().takeUnless { it.isNullOrBlank() } ?: return false
    val eventServer = eventServerId?.trim().takeUnless { it.isNullOrBlank() } ?: return false
    if (shellServer == eventServer) return false
    return session?.servers?.any { it.id == eventServer } == true
}

internal fun canRefreshRealtimeServer(
    session: StoredAuthSession?,
    requestedServerId: String?,
): Boolean {
    val target = requestedServerId?.trim().takeIf { !it.isNullOrBlank() } ?: return false
    return session?.servers?.any { server -> server.id.trim() == target } == true
}

private fun RealtimeEvent.serverIdHint(): ServerId? =
    when (this) {
        is RealtimeRefreshRequired -> serverId
        is ServerMembershipRemoved -> serverId
        is ServerDeleted -> serverId
        is ServerPlanUpdated -> serverId
        is ServerFeaturesChanged -> serverId
        is ActivityChanged -> serverId
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
                .background(RaftColor.SurfaceWhite)
    )
}

@Composable
private fun AppShellCachedHomePreviewContent(
    bridge: build.raft.app.base.BridgeModule,
    shellScope: RaftAppShellScope,
    state: SharedHomeState
) {
    RaftAppShellSurface(
        shellScope = shellScope,
        bottomTabs = emptyList(),
        onBottomTabSelected = {},
        showBottomTabs = false,
        // Shown only while the stored session is read (Restoring → Ready(CachedRestorePending) is
        // local); no blocking overlay here either.
        interactionLocked = false
    ) {
        SharedHomeScreen(
            bridge = bridge,
            state = state,
            active = false,
            sessionReady = false,
            serverDrawerEnabled = false,
            homeServerAvatarEnabled = false,
            // Match the list the restored shell will show (desktop mode defaults on, so the wide
            // dual-pane layout uses the rail, which carries Search and Activity like the bar).
            searchAndActivityOnShellNav =
                appShellNavCarriesSearchAndActivity(
                    navRailActive = shellScope.adaptivePane.layout == RaftAdaptivePaneLayout.DualPane
                ),
            onIntent = {},
            onNotificationDismiss = {},
            onOpenSearch = {},
            onOpenActivity = {},
            onOpenSaved = {},
            onOpenComputers = {},
            onOpenUrl = {},
            onOpenServerSelection = {},
            onCreateChannel = {},
            onOpenConversation = {},
            topSafeAreaOverride = shellScope.topSafeArea,
            bottomContentPadding = shellScope.contentBottomInset(includeBottomTabBar = false)
        )
    }
}

/**
 * First in-app frame while the stored session restores. Matches the static iOS launch screen
 * (iosApp Info.plist UILaunchScreen: RaftLaunchLogo on RaftLaunchBackground) and Android's
 * splash hold: the app-icon mark, 108dp, centred on the app canvas (cream in light, layer
 * canvas in dark). No tagline, tilt or border, so the launch -> app hand-off does not jump.
 */
@Composable
internal fun AppShellRestoreSplashContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(if (RaftAppThemeState.isDark) RaftColor.Canvas else RaftColor.SurfaceCream),
        contentAlignment = Alignment.Center
    ) {
        RaftCachedImage(
            source = "assets://raft/raft-launch-logo.png",
            contentDescription = null,
            modifier = Modifier.size(108.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
internal fun PageErrorContent(
    title: String,
    subtitle: String,
    iconName: RaftLucideIconName?,
    headerContentStartPadding: Dp = 20.dp,
    headerTitleStyle: TextStyle = RaftType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
    message: String,
    code: String,
    retryLabel: String? = "Retry",
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RaftTopPageHeader(
            title = title,
            subtitle = subtitle,
            iconName = iconName,
            contentStartPadding = headerContentStartPadding,
            titleStyle = headerTitleStyle
        )
        PageErrorBody(
            message = message,
            code = code,
            retryLabel = retryLabel,
            onRetry = onRetry
        )
    }
}

@Composable
private fun MembersPageErrorContent(
    message: String,
    code: String,
    onRetry: (() -> Unit)?,
    onBack: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MembersPageHeader(title = stringResource(Res.string.tab_members), onBack = onBack)
        PageErrorBody(
            message = message,
            code = code,
            retryLabel = stringResource(Res.string.common_retry),
            onRetry = onRetry
        )
    }
}

@Composable
private fun PageErrorBody(
    message: String,
    code: String,
    retryLabel: String?,
    onRetry: (() -> Unit)?
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(RaftAppSpacing.Md)
                .raftCardSurface()
                .padding(RaftAppSpacing.Md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)) {
            Text(text = message, style = RaftType.ButtonBold, color = RaftColor.TextBlack)
            Text(
                text = code.userFacingErrorCodeMessage(localizedErrorPresentationStrings()),
                style = RaftType.Caption,
                color = RaftColor.AccentRed
            )
            if (retryLabel != null && onRetry != null) {
                RaftBrutalActionButton(
                    label = retryLabel,
                    onClick = onRetry,
                    leadingIcon = RaftLucideIconName.RefreshCw,
                    backgroundColor = RaftColor.SurfaceWhite
                )
            }
        }
    }
}

@Composable
internal fun BottomTabBarSmokeContent() {
    val shellScope = rememberRaftAppShellScope()
    var selectedTab by remember { mutableStateOf("tasks") }
    val smokeItems = bottomTabBarSmokeItems.map { item -> item.copy(selected = item.key == selectedTab) }
    val onSmokeSelect: (String) -> Unit = { selectedTab = it }
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
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
                    style = RaftType.PageTitle.copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Shared tab bar smoke preview",
                    style = RaftType.Input.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    smokeItems.forEach { item ->
                        Text(
                            text = item.label,
                            style = RaftType.Input.copy(color = RaftColor.TextMutedGray),
                            color = RaftColor.TextMutedGray,
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
                style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            RaftBottomTabBar(items = smokeItems, onSelect = onSmokeSelect, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Navigation Rail",
                style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                RaftNavigationRailTabBar(items = smokeItems, onSelect = onSmokeSelect)
                Column(
                    modifier = Modifier.weight(1f).fillMaxSize().background(RaftColor.AppBgLightBlue).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Rail layout",
                        style = RaftType.PageTitle.copy(color = RaftColor.TextBlack),
                        color = RaftColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Shared TabBar variant parity with Android",
                        style = RaftType.Input.copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        RaftAppBottomTabBarHost(
            items = smokeItems,
            onSelect = onSmokeSelect,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            bottomSafeArea = shellScope.bottomSafeArea
        )
    }
}

@Composable
internal fun BottomTabBarVisualContent() {
    // The bar is measured at full page width (390x58dp, approved mockup A), so
    // render the bar flush at the window origin at exactly 390dp — explicit width
    // rather than fillMaxWidth because the capture window can be wider than 390dp
    // (task #370).
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        RaftBottomTabBar(
            items = bottomTabBarSmokeItems,
            onSelect = {},
            modifier = Modifier.width(390.dp)
        )
    }
}

@Composable
internal fun SettingsRowVisualContent() {
    Column(
        modifier =
            Modifier
                .width(342.dp)
                .background(RaftColor.SurfaceCream)
                .padding(top = 48.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        RaftVerticalTabBar(
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
    // React baselines render settings pages flush to the viewport top; no host
    // status-bar spacer or safe-area padding may leak into fixture captures.
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .background(RaftColor.SurfaceWhite)
    ) {
        SettingsDetailShell(
            title = spec.title,
            subtitle = settingsVisualHeaderSubtitle(spec.variant),
            iconName = spec.iconName,
            applyTopSafeArea = false,
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
    val iconName: RaftLucideIconName
)

internal fun settingsVisualHeaderSubtitle(variant: String): String = ""

private fun settingsVisualSpec(variant: String): SettingsVisualSpec =
    when (variant) {
        "server-profile" ->
            SettingsVisualSpec(variant, "Server Profile", "Workspace profile, role, and slug", RaftLucideIconName.Building2)
        "server-danger" ->
            SettingsVisualSpec(variant, "Server Danger", "Leave or delete this workspace", RaftLucideIconName.TriangleAlert)
        "server-danger-modal" ->
            SettingsVisualSpec(variant, "Server Profile", "Workspace profile, role, and slug", RaftLucideIconName.Building2)
        "language-region" ->
            SettingsVisualSpec(variant, "Language & Region", "Language, timezone, and translation", RaftLucideIconName.Languages)
        "appearance" ->
            SettingsVisualSpec(variant, "Appearance", "Message font size and reading density", RaftLucideIconName.Type)
        "notifications" ->
            SettingsVisualSpec(variant, "Notifications", "Push notification preferences", RaftLucideIconName.Bell)
        "account-provider-linked",
        "account-password-expanded",
        "account-error" ->
            SettingsVisualSpec(variant, "Account", "Profile, email, password, connected accounts", RaftLucideIconName.User)
        else ->
            SettingsVisualSpec("account", "Account", "Profile, email, password, connected accounts", RaftLucideIconName.User)
    }

@Composable
private fun SettingsVisualPageBody(spec: SettingsVisualSpec) {
    when (spec.variant) {
        "server-profile",
        "server-danger",
        "server-danger-modal" -> SettingsVisualServerFocusedContent(spec.variant)
        "language-region" -> SettingsVisualLanguageRegionContent()
        "appearance" -> SettingsVisualAppearanceContent()
        "notifications" -> SettingsVisualNotificationsContent()
        else -> SettingsVisualAccountContent(spec.variant)
    }
}

@Composable
private fun SettingsVisualAccountContent(variant: String = "account") {
    val fixture = settingsVisualFixtureValues
    val connectedAccounts =
        when (variant) {
            "account-provider-linked" ->
                listOf(
                    RaftSettingsConnectedAccount("password", "Email password", fixture.ownerEmail, "connected", canConnect = false),
                    RaftSettingsConnectedAccount("google", "Google", fixture.ownerEmail, "connected", canConnect = false),
                    RaftSettingsConnectedAccount("github", "GitHub", "Not connected", "notConnected", canConnect = true)
                )
            else ->
                listOf(
                    RaftSettingsConnectedAccount("password", "Email password", fixture.ownerEmail, "connected", canConnect = false),
                    RaftSettingsConnectedAccount("google", "Google", "Not connected", "notConnected", canConnect = true),
                    RaftSettingsConnectedAccount("github", "GitHub", "Not connected", "notConnected", canConnect = true)
                )
        }
    SettingsAccountContent(
        payload =
            settingsAccountSmokePayload.copy(
                instanceId = "visual-settings-account-$variant",
                profile =
                    settingsAccountSmokePayload.profile.copy(
                        displayName = fixture.ownerDisplayName,
                        handle = fixture.ownerHandleBare,
                        email = fixture.ownerEmail,
                        verified = fixture.ownerEmailVerified,
                        roleLabel = fixture.ownerRoleLabel
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
        profileName = profileName,
        onProfileNameChange = { profileName = it },
        serverSnapshot = snapshot,
        serverRole = "owner",
        expectedServerSlug = snapshot.profile.slug,
        deleteConfirmationName = deleteConfirmationName,
        onDeleteConfirmationNameChange = { deleteConfirmationName = it },
        actionState = null,
        bottomContentPadding = RaftAppSpacing.Lg,
        onAction = { _, _ -> }
    )
}

@Composable
private fun SettingsServerDangerModalVisualContent() {
    val snapshot = remember { settingsVisualServerSnapshot() }
    var modalSlug by remember { mutableStateOf("") }
    val serverSlug = snapshot.profile.slug
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        Column(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(61.dp)
                        .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaftBackButton(onClick = {}, label = stringResource(Res.string.common_back))
                Text(
                    "Server Profile",
                    style = RaftType.PageTitle.copy(fontSize = 17.sp, lineHeight = 22.sp, color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(RaftColor.BorderBlack))
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
            errorMessage = null,
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
                .raftCardSurface(fill = RaftColor.FillMuted, radius = RaftAppRadius.Control)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftLucideIcon(RaftLucideIconName.Building2, modifier = Modifier.size(14.dp), color = RaftColor.TextBlackArgb)
            Text(
                "PROFILE",
                style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, letterSpacing = 1.3.sp, color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray
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
            style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 14.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray
        )
        Box(modifier = Modifier.fillMaxWidth().height(39.dp)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .raftCardSurface()
                        .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    value,
                    style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp, color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


@Composable
private fun SettingsVisualLanguageRegionContent() {
    val snapshot = settingsVisualLanguageRegionSnapshot()
    SettingsLanguageRegionContent(
        state = ScreenLoadState.Content(snapshot),
        mobileState = ScreenLoadState.Content(settingsVisualMobileSnapshot()),
        actionState = null,
        languageDraft = snapshot.browserLanguage.selectedKey,
        displayDraft = snapshot.translationDisplay.selectedKey,
        timezoneDraft = snapshot.timezone.selectedKey,
        timeFormatDraft = snapshot.timeFormat.selectedKey,
        bottomContentPadding = RaftAppSpacing.Lg,
        onLanguageDraftChange = {},
        onDisplayDraftChange = {},
        onTimezoneDraftChange = {},
        onTimeFormatDraftChange = {},
        onServerTranslationChange = {},
        onLocaleChange = {},
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
        activitySenderAvatarsEnabled = false,
        bottomContentPadding = RaftAppSpacing.Lg,
        onFontSizeDraftChange = {},
        onActivitySenderAvatarsChange = {},
        onSave = {},
        onRetry = {}
    )
}

@Composable
private fun SettingsVisualNotificationsContent() {
    SettingsNotificationsContent(
        state = ScreenLoadState.Content(settingsVisualMobileSnapshot()),
        actionState = null,
        bottomContentPadding = RaftAppSpacing.Lg,
        notificationPreferenceSnapshot = NotificationPreferenceSnapshot(enabled = true),
        preferenceBusy = false,
        foregroundKeepAliveBusy = false,
        offlineComputerAlertsBusy = false,
        foregroundMessageBannersBusy = false,
        onNotificationPreferenceChange = {},
        onForegroundKeepAlivePreferenceChange = {},
        onOfflineComputerAlertsChange = {},
        onForegroundMessageBannersChange = {},
        onServerNotificationModeChange = {},
        onPushPreviewModeChange = {},
        onPermission = {},
        onSettings = {},
        onTest = {},
        onRetry = {}
    )
}

private fun settingsVisualServerSnapshot(): SettingsServerSnapshot {
    val fixture = settingsVisualFixtureValues
    return SettingsServerSnapshot(
        profile =
            build.raft.app.data.SettingsServerProfileSnapshot(
                name = fixture.serverName,
                slug = fixture.serverSlug,
                hideHumansFromMembers = true,
                canSave = true
            ),
        sections =
            listOf(
                SettingsServerSectionSnapshot("admins", "Owners & Admins".desc(), "Control who can manage this workspace."),
                SettingsServerSectionSnapshot("pending_invites", "Pending Invites".desc(), "Invite humans by email and revoke pending invites."),
                SettingsServerSectionSnapshot("join_links", "Join Links".desc(), "Reusable links for onboarding larger teams."),
                SettingsServerSectionSnapshot("pre_join_agreement", "Pre-join Agreement".desc(), "Require humans to accept a workspace agreement before joining."),
                SettingsServerSectionSnapshot("onboarding_agent", "Onboarding".desc(), "Choose which agent greets new humans."),
                SettingsServerSectionSnapshot("member_permissions", "Member Permissions".desc(), "Limit what member-role users can see."),
                SettingsServerSectionSnapshot("translation", "Translation".desc(), "Enable server-level message translation."),
                SettingsServerSectionSnapshot("danger", "Danger Zone".desc(), "Leave or delete this workspace.")
            ),
        admins =
            listOf(
                SettingsServerAdminSnapshot(fixture.ownerId, fixture.ownerDisplayName, fixture.ownerAdminDetail, fixture.ownerRole)
            ),
        usageRows =
            listOf(
                SettingsPlanUsageSnapshot("Agents", "8 / 20"),
                SettingsPlanUsageSnapshot("Computers", "4 / 10")
            ),
        pendingInvites =
            listOf(
                SettingsServerInviteSnapshot("invite_1", fixture.designerEmail, fixture.ownerDisplayName, fixture.entityCreatedAtIso),
                SettingsServerInviteSnapshot("invite_2", "qa@slock.ai", "Cindy", fixture.recentActivityAtIso)
            ),
        joinLinks =
            listOf(
                SettingsServerJoinLinkSnapshot(
                    id = "link_1",
                    label = "Design review link",
                    url = "https://app.raft.build/join/design",
                    maxUsesLabel = "25 max",
                    usageLabel = "8 used",
                    expiresAtLabel = fixture.recentActivityAtIso
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
                selectedKey = "en",
                selectedLabel = "English",
                statusLabel = "English",
                detail = "Stored on this account.",
                options = TranslationTargetLanguages.options.map { it.key to it.label }
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
        pushPreviewMode =
            build.raft.app.data.PushPreviewMode.Full
                .toSettingsMobileSelectionSnapshot(available = true),
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

internal val homeSearchVisualServer: ServerSummary
    get() {
        val fixture = settingsVisualFixtureValues
        return ServerSummary(
            id = ServerId(fixture.serverId),
            name = fixture.serverName,
            slug = fixture.serverSlug,
            role = fixture.ownerRole,
            plan = fixture.serverPlan,
            unreadCount = 24
        )
    }

internal val homeSearchVisualSession: StoredAuthSession
    get() {
        val fixture = settingsVisualFixtureValues
        return StoredAuthSession(
            user =
                StoredAuthUser(
                    id = fixture.ownerId,
                    email = fixture.ownerEmail,
                    name = fixture.ownerName,
                    displayName = fixture.ownerDisplayName,
                    emailVerified = fixture.ownerEmailVerified
                ),
            accessToken = "visual-token",
            refreshToken = "visual-refresh",
            selectedServerId = fixture.serverId,
            selectedServerSlug = fixture.serverSlug,
            servers =
                listOf(
                    StoredAuthServer(
                        id = fixture.serverId,
                        name = fixture.serverName,
                        slug = fixture.serverSlug,
                        role = fixture.ownerRole,
                        plan = fixture.serverPlan
                    )
                )
        )
    }

private val homeCreateChannelVisualMembers = listOf(
        MemberProfile(
            id = MemberId("agent-cindy"),
            displayName = "Cindy",
            handle = "Cindy",
            kind = MemberKind.Agent,
            avatarLabel = "CI",
            avatar = AvatarReference.Pixel("robot"),
            presence = Presence.Active,
            agentStatus = AgentStatus.Working
        ),
        MemberProfile(
            id = MemberId("agent-visual-qa"),
            displayName = "Visual QA",
            handle = "visual-qa",
            kind = MemberKind.Agent,
            avatarLabel = "VQ",
            avatar = AvatarReference.Pixel("eye"),
            presence = Presence.Offline,
            agentStatus = AgentStatus.Offline
        ),
        MemberProfile(
            id = MemberId("visual-human-designer"),
            displayName = "Designer",
            handle = "designer",
            kind = MemberKind.Human,
            avatarLabel = "DE"
        )
)

// savedMessagesFixture.json (slock packages/visual-testing/shared): three entries of 5, more to
// load. Times are what react renders for each createdAt at the fixture instant
// (2026-06-19T12:30Z): 2026-06-22 -> "in 3 days", 2026-06-21T09:12Z -> "in 2 days".
private val homeSavedVisualState = SharedHomeSavedPanelState(
    items =
        listOf(
            InboxItem(
                key = "saved-visual-1",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-design"),
                channelName = "design",
                lastMessageId = MessageId("msg-saved-visual-1"),
                lastMessagePreview = "Captured the Android visual artifact for #product:f8e569cb and queued React parity.",
                lastMessageAt = "in 3 days",
                lastMessageSenderName = "Cindy",
                lastMessageSenderId = "agent-cindy",
                lastMessageSenderKind = MemberKind.Agent,
                lastMessageSenderAvatarWireValue = "pixel:robot"
            ),
            InboxItem(
                key = "saved-visual-2",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-visual"),
                channelName = "design",
                lastMessageId = MessageId("msg-saved-visual-2"),
                lastMessagePreview = "Rename old visual cases under components.* before rerunning baseline.",
                lastMessageAt = "in 3 days",
                lastMessageSenderName = "artin",
                lastMessageSenderId = "visual-user",
                lastMessageSenderKind = MemberKind.Human,
                threadChannelId = ChannelId("thread-visual"),
                parentChannelId = ChannelId("channel-design"),
                parentChannelName = "design",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("msg-parent-visual")
            ),
            InboxItem(
                key = "saved-visual-3",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-design"),
                channelName = "design",
                lastMessageId = MessageId("msg-saved-visual-3"),
                lastMessagePreview = "Publish preflight is green — report goes live after the next full run.",
                lastMessageAt = "in 2 days",
                lastMessageSenderName = "Product UX Designer",
                lastMessageSenderId = "agent-product-ux",
                lastMessageSenderKind = MemberKind.Agent,
                lastMessageSenderAvatarWireValue = "pixel:paint"
            )
        ),
    totalCount = 5,
    loaded = true,
    hasMore = true,
    nextOffset = 3
)

// Canonical activity-inbox rows (task #370): ported verbatim from
// packages/visual-testing/shared/activityResultsFixture.json in the slock repo
// (thread card in #design with 5 replies / @you / 2 new, #android-artifacts
// channel card with 1 new, @Cindy dm card). Relative time labels match what
// react renders for the fixture ISO stamps at the fixture instant
// (fixtureData.locale.nowEpochMillis = 2026-06-19T12:30Z): the thread's
// 2026-06-22T02:35Z reply is "in 3 days", the 2026-06-19T12:24Z rows are
// "6 minutes ago". No task and no draft: the React fixture has neither.
private val homeActivityVisualSnapshot = InboxSnapshot(
    server = homeSearchVisualServer,
    items =
        listOf(
            InboxItem(
                key = "activity-thread-visual-parity",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-msg-agent-reply"),
                channelName = "thread",
                unreadCount = 2,
                hasMention = true,
                firstUnreadMessageId = MessageId("msg-visual-activity-reply"),
                threadChannelId = ChannelId("thread-msg-agent-reply"),
                parentChannelId = ChannelId("channel-design"),
                parentChannelName = "design",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("msg-agent-reply"),
                parentMessagePreview = "Captured the Android visual artifact for #product:f8e569cb and queued React parity.",
                parentMessageSenderId = "agent-cindy",
                parentMessageSenderKind = MemberKind.Agent,
                latestActivityMessageId = MessageId("msg-visual-activity-reply"),
                latestActivityPreview = "Review the Android composer crop before release.",
                latestActivityAt = "in 3 days",
                latestActivitySenderName = "artin",
                latestActivitySenderId = "visual-user",
                latestActivitySenderKind = MemberKind.Human,
                replyCount = 5
            ),
            InboxItem(
                key = "activity-channel-artifacts",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("channel-android"),
                channelName = "android-artifacts",
                unreadCount = 1,
                firstUnreadMessageId = MessageId("msg-visual-activity-android"),
                lastMessageId = MessageId("msg-visual-activity-android"),
                lastMessagePreview = "Captured the previous visual baseline before leaving the workspace.",
                lastMessageAt = "6 minutes ago",
                lastMessageSenderName = "Cindy",
                lastMessageSenderId = "agent-cindy",
                lastMessageSenderKind = MemberKind.Agent
            ),
            InboxItem(
                key = "activity-dm-cindy",
                kind = InboxItemKind.DirectMessage,
                channelId = ChannelId("dm-agent-cindy-artin"),
                channelName = "Cindy",
                lastMessageId = MessageId("msg-visual-activity-dm"),
                lastMessagePreview = "201继续推进",
                lastMessageAt = "6 minutes ago",
                lastMessageSenderName = "Cindy",
                lastMessageSenderId = "agent-cindy",
                lastMessageSenderKind = MemberKind.Agent
            )
        ),
    totalCount = 3,
    totalUnreadCount = 3,
    hasMore = false
)

// Activity-tab (Android-only) screens keep the richer thread row: a claimed task and a draft.
private val homeActivityTabVisualSnapshot =
    homeActivityVisualSnapshot.copy(
        items =
            homeActivityVisualSnapshot.items.map { item ->
                if (item.key == "activity-thread-visual-parity") {
                    item.copy(
                        taskNumber = 607,
                        taskStatus = TaskStatus.InProgress,
                        taskClaimedByName = "Mahua",
                        hasDraft = true
                    )
                } else {
                    item
                }
            }
    )

private val homeActivityVisualDraftProjection =
    setOf(
        SharedThreadComposerDraftRecord(
            key = "reply:msg-agent-reply",
            sendChannelId = "thread-msg-agent-reply",
            parentMessageId = "msg-agent-reply"
        )
    ).toSharedThreadComposerDraftProjection()

@Composable
internal fun HomeSavedVisualContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    Box(modifier = Modifier.width(342.dp).height(620.dp)) {
        SharedHomeSavedPanel(
            state = homeSavedVisualState.copy(refreshing = params.optBoolean("task797Refreshing", false)),
            onIntent = {},
            onClose = {},
            onOpenItem = {},
            bottomContentPadding = 12.dp,
            includeTopSafeArea = params.optBoolean("task797IncludeTopSafeArea", false)
        )
    }
}

@Composable
internal fun HomeActivityVisualContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    Box(modifier = Modifier.width(342.dp).height(620.dp)) {
        SharedHomeActivityPanel(
            snapshot = homeActivityVisualSnapshot,
            onOpenItem = {},
            onClose = {},
            includeTopSafeArea = params.optBoolean("task797IncludeTopSafeArea", false),
            pullRefreshing = params.optBoolean("task797Refreshing", false),
            activityTaskStatusBadgesEnabled = true,
            foregroundEpoch = 0L,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Activity as the second app-shell bottom tab (artin 2026-10-20): the main-tab head, the four
 * segments (全部 · 未读 · 提及 · 已置顶) and the 5-tab bar with the Activity badge. pageData
 * `segment` picks all / unread / mentions / pinned.
 */
@Composable
internal fun RaftActivityTabVisualContent(stale: Boolean = false) {
    val pager = LocalActivity.current.getPager() as Pager
    val segment =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.optString("segment").ifBlank { "all" }
        }
    val filter =
        build.raft.app.data.InboxFilter.entries.firstOrNull { (it.scopeWireValue ?: it.wireValue) == segment }
            ?: build.raft.app.data.InboxFilter.All
    Column(modifier = Modifier.width(390.dp).height(844.dp).background(RaftColor.SurfaceWhite)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SharedHomeActivityScreen(
                state =
                    build.raft.app.data.SharedActivityState(
                        selectedFilter = filter,
                        snapshot = homeActivityTabVisualSnapshot,
                        loaded = true,
                        stale = stale,
                    ),
                onIntent = {},
                onOpenItem = {},
                onClose = null,
                includeTopSafeArea = false,
                activityTaskStatusBadgesEnabled = true,
                draftProjection = homeActivityVisualDraftProjection,
                foregroundEpoch = 0L,
                mainTab = true,
                modifier = Modifier.fillMaxSize()
            )
        }
        RaftBottomTabBar(
            items =
                appShellPhoneBottomTabs(
                    selectedTab = "activity",
                    homeLabel = "Home",
                    activityLabel = "Activity",
                    searchLabel = "Search",
                    tasksLabel = "Tasks",
                    moreLabel = "More",
                    activityUnreadCount = homeActivityTabVisualSnapshot.totalUnreadCount
                ),
            onSelect = {},
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun HomeCreateChannelDialogVisualContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
    ) {
        SharedCreateChannelDialog(
            submitting = false,
            membersLoading = false,
            members = homeCreateChannelVisualMembers,
            errorMessage = null,
            initialName = "android-artifacts",
            initialDescription = textFieldValueAtEnd("Build reports, screenshots, release blockers"),
            initialVisibility = SharedCreateChannelVisibility.Public,
            initialSelectedMemberKeys = setOf(
                homeCreateChannelVisualMembers[0].memberPickerKey,
                homeCreateChannelVisualMembers[2].memberPickerKey
            ),
            fixtureStatusFallbackEnabled = true,
            onDismiss = {},
            onSubmit = { _, _, _, _, _ -> }
        )
    }
}

@Composable
internal fun SettingsRouteVisualScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    val storeScope =
        remember(pager.pageData.params.toString(), pager.pagerId) {
            pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
        }
    val route =
        remember(pager.pageData.params.toString()) {
            parseSettingsRouteVisualPayload(pager.pageData.params)
        }
    val session = remember(route.fixtureKey) { settingsRouteVisualSession() }
    val state =
        remember(route.destination, route.itemId, route.fixtureKey) {
            settingsRouteVisualState(route)
        }
    val handsFeedbackVisualFixture =
        remember(route.fixtureKey) {
            settingsHandsFeedbackVisualFixture(route.fixtureKey)
        }
    val copiedMessage = stringResource(Res.string.common_copied)
    val settingsCapabilities =
        remember(bridge, handsFeedbackVisualFixture, copiedMessage) {
            BridgeSettingsPlatformCapabilities(
                bridge = bridge,
                copiedMessage = copiedMessage,
                feedbackVisualSubmission = handsFeedbackVisualFixture?.let {
                    SettingsHandsFeedbackSubmission(
                        ticketId = "visual-feedback-ticket-139",
                        shareText = "Hands feedback visual-139"
                    )
                }
            )
        }
    val eventBus = remember(bridge) { RaftSharedStoreRegistry.get(bridge).eventBus }
    val sessionStore = remember(session) { InMemoryRaftAuthSessionStore(session) }
    val authDataSource =
        remember(sessionStore, session) {
            SharedAuthDataSource(
                api = AuthChooseServerVisualApi(session.user, session.servers),
                sessionStore = sessionStore,
                diagnosticsLogger = { message -> bridge.log("settings_route_visual_auth $message") }
            )
        }
    LaunchedEffect(route.destination, route.itemId, route.fixtureKey, route.fullHeight) {
        bridge.log(
            "settings_route_visual_render destination=${route.destination} " +
                "fixtureKey=${route.fixtureKey} itemId=${route.itemId.orEmpty()} fullHeight=${route.fullHeight}"
        )
        bridge.log(
            "settings_route_visual_ready destination=${route.destination} " +
                "fixtureKey=${route.fixtureKey}"
        )
    }

    if (route.destination == SettingsDestinationHome) {
        SettingsHomeContent(
            payload = appShellSettingsHomePayload(session),
            onSelect = {},
        )
    } else {
        SettingsRouteVisualFixtureDetailContent(
            pager = pager,
            eventBus = eventBus,
            session = session,
            authDataSource = authDataSource,
            settingsCapabilities = settingsCapabilities,
            storeScope = storeScope,
            handsFeedbackVisualFixture = handsFeedbackVisualFixture,
            settingsState = state,
            notificationPreferenceSnapshot = NotificationPreferenceSnapshot(enabled = true),
            onSettingsIntent = {},
            route = route,
            onBack = {},
            bottomContentPadding = RaftAppSpacing.Lg,
            timeContext = null,
            onLogout = {},
        )
    }
}

/**
 * Screenshot-only adapter for the route visual harness. Production navigation never calls this
 * function: native detail pages receive one immutable page route, while dedicated Pager fixtures
 * below render their own content without exposing any destination-changing callback.
 */
@Composable
private fun SettingsRouteVisualFixtureDetailContent(
    pager: Pager,
    eventBus: RaftEventBus,
    session: StoredAuthSession,
    authDataSource: SharedAuthDataSource,
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    storeScope: RaftShellStoreScope,
    handsFeedbackVisualFixture: SettingsHandsFeedbackVisualFixture?,
    settingsState: SharedSettingsState,
    notificationPreferenceSnapshot: NotificationPreferenceSnapshot,
    onSettingsIntent: (SharedSettingsIntent) -> Unit,
    route: SettingsRouteVisualPayload,
    onBack: () -> Unit,
    bottomContentPadding: Dp,
    timeContext: RaftTimeContext?,
    onLogout: () -> Unit,
) {
    val nativePage = SettingsNativeDetailPage.fromKey(route.destination)
    if (nativePage != null) {
        val nativeRoute =
            SettingsNativeDetailRoute.create(
                page = nativePage,
                itemId = route.itemId,
            )
        if (nativeRoute == null) {
            SettingsSharedPlaceholder(
                title = "Settings",
                detail = "This visual fixture route is invalid.",
                iconName = RaftLucideIconName.Settings,
                bottomContentPadding = bottomContentPadding,
            )
            return
        }
        SettingsNativeDetailPageContent(
            session = session,
            authDataSource = authDataSource,
            settingsCapabilities = settingsCapabilities,
            settingsState = settingsState,
            notificationPreferenceSnapshot = notificationPreferenceSnapshot,
            onSettingsIntent = onSettingsIntent,
            route = nativeRoute,
            onBack = onBack,
            onOpenRouterPage = {},
            onOpenAdministrationPage = {},
            onOpenHandsFeedbackPage = {},
            onOpenSourceLicensesPage = {},
            onOpenApiEnvironmentPage = {},
            onOpenDiagnosticsPage = {},
            onOpenLocalFeatureOverridesPage = {},
            onOpenLocalFeatureOverridePage = {},
            onMessageAlignmentSelected = {},
            onCompactConsecutiveMessagesSelected = {},
            onForegroundMessageBannersSelected = {},
            onActivityTaskStatusBadgesSelected = {},
            onConversationForwardGestureSelected = {},
            activitySenderAvatarsEnabled = false,
            onActivitySenderAvatarsSelected = {},
            onServerDrawerSelected = {},
            onHomeServerAvatarSelected = {},
                bottomContentPadding = bottomContentPadding,
            timeContext = timeContext,
            onLogout = onLogout,
        )
        return
    }

    SettingsDedicatedPagerVisualFixtureContent(
        pager = pager,
        eventBus = eventBus,
        session = session,
        settingsCapabilities = settingsCapabilities,
        storeScope = storeScope,
        handsFeedbackVisualFixture = handsFeedbackVisualFixture,
        settingsState = settingsState,
        route = route,
        onBack = onBack,
        bottomContentPadding = bottomContentPadding,
        timeContext = timeContext,
    )
}

@Composable
private fun SettingsDedicatedPagerVisualFixtureContent(
    pager: Pager,
    eventBus: RaftEventBus,
    session: StoredAuthSession,
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    storeScope: RaftShellStoreScope,
    handsFeedbackVisualFixture: SettingsHandsFeedbackVisualFixture?,
    settingsState: SharedSettingsState,
    route: SettingsRouteVisualPayload,
    onBack: () -> Unit,
    bottomContentPadding: Dp,
    timeContext: RaftTimeContext?,
) {
    SettingsDetailShell(
        title = settingsDedicatedPagerVisualFixtureTitle(route.destination),
        subtitle = "",
        iconName = settingsDedicatedPagerVisualFixtureIcon(route.destination),
        onBack = onBack,
        trailingContent = null,
    ) {
        when (route.destination) {
            SettingsDestinationAccount ->
                SettingsAccountContent(
                    settingsState.account.toAccountPayload(
                        session,
                        settingsState.actionState,
                        localizedErrorPresentationStrings(),
                    ),
                    avatarCacheIdentity =
                        settingsState.account.settingsAccountAvatarCacheIdentity(session),
                    applyTopSafeArea = false,
                    bottomContentPadding = bottomContentPadding,
                    onPasswordChange = { _, _ -> },
                    onEvent = { _, _ -> },
                )
            SettingsDestinationStorage ->
                SettingsStorageContent(
                    settingsState.storage.toStoragePayload(
                        actionState = settingsState.actionState,
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Storage),
                    ),
                    bottomContentPadding = bottomContentPadding,
                    onEvent = { _, _ -> },
                )
            SettingsDestinationHandsFeedback ->
                SharedHandsFeedbackContent(
                    pager = pager,
                    eventBus = eventBus,
                    settingsCapabilities = settingsCapabilities,
                    sourceStoreScope = storeScope,
                    accountId = session.user.id,
                    visualFixture = handsFeedbackVisualFixture,
                    bottomContentPadding = bottomContentPadding,
                )
            SettingsDestinationComputers,
            SettingsDestinationBilling,
            SettingsDestinationReleaseNotes -> SettingsComputersLoadingContent()
            else ->
                SettingsSharedPlaceholder(
                    title = "Settings",
                    detail = "This visual fixture route is unavailable.",
                    iconName = RaftLucideIconName.Settings,
                    bottomContentPadding = bottomContentPadding,
                )
        }
    }
}

@Composable
private fun settingsDedicatedPagerVisualFixtureTitle(destination: String): String =
    when (destination) {
        SettingsDestinationAccount -> stringResource(Res.string.settings_account_title)
        SettingsDestinationComputers -> stringResource(Res.string.settings_computers_title)
        SettingsDestinationAdministration -> stringResource(Res.string.settings_server_administration_title)
        SettingsDestinationConnectedApps -> stringResource(Res.string.settings_connected_apps_title)
        SettingsDestinationBilling -> stringResource(Res.string.settings_billing_title)
        SettingsDestinationStorage -> stringResource(Res.string.settings_storage_title)
        SettingsDestinationReleaseNotes -> stringResource(Res.string.settings_release_notes_title)
        SettingsDestinationHandsFeedback -> stringResource(Res.string.settings_feedback_title)
        else -> stringResource(Res.string.settings_title)
    }

private fun settingsDedicatedPagerVisualFixtureIcon(destination: String): RaftLucideIconName =
    when (destination) {
        SettingsDestinationAccount -> RaftLucideIconName.User
        SettingsDestinationComputers -> RaftLucideIconName.Monitor
        SettingsDestinationAdministration -> RaftLucideIconName.Shield
        SettingsDestinationConnectedApps -> RaftLucideIconName.Link2
        SettingsDestinationBilling -> RaftLucideIconName.CreditCard
        SettingsDestinationStorage -> RaftLucideIconName.Archive
        SettingsDestinationReleaseNotes -> RaftLucideIconName.FileText
        SettingsDestinationHandsFeedback -> RaftLucideIconName.MessageSquarePlus
        else -> RaftLucideIconName.Settings
    }

private fun settingsHandsFeedbackVisualFixture(fixtureKey: String): SettingsHandsFeedbackVisualFixture? {
    if (fixtureKey != "feedback-two-attachments" && fixtureKey != "feedback-submit-success") return null
    return SettingsHandsFeedbackVisualFixture(
        draft =
            SettingsHandsFeedbackDraftSnapshot(
                text = "The attachment row should be replaced by a square-corner User feedback row.",
                attachments =
                    listOf(
                        PlatformFile(
                            id = "visual-feedback-attachment-1",
                            fileName = "reuse-before.png",
                            mimeType = "image/png",
                            sizeBytes = 12_340,
                            fileRef = "visual://feedback/reuse-before.png",
                            source = "visual_fixture"
                        ),
                        PlatformFile(
                            id = "visual-feedback-attachment-2",
                            fileName = "reuse-after.log",
                            mimeType = "text/plain",
                            sizeBytes = 4_096,
                            fileRef = "visual://feedback/reuse-after.log",
                            source = "visual_fixture"
                        )
                    )
            ),
        autoSubmit = fixtureKey == "feedback-submit-success"
    )
}

internal fun settingsRouteVisualState(route: SettingsRouteVisualPayload): SharedSettingsState {
    val destination =
        SettingsNativeDetailPage.fromKey(route.destination)?.storeDestination
            ?: route.destination.toVisualFixtureSharedSettingsDestination()
            ?: SharedSettingsDestination.Administration
    return SharedSettingsState(
        route = SharedSettingsRoute(destination, route.itemId),
        home = ScreenLoadState.Content(SharedSettingsHomeSnapshot()),
        server =
            if (destination == SharedSettingsDestination.Administration || destination == SharedSettingsDestination.Server) {
                ScreenLoadState.Content(settingsVisualServerSnapshot())
            } else {
                ScreenLoadState.Loading
            },
        languageRegion =
            if (destination == SharedSettingsDestination.LanguageRegion) {
                ScreenLoadState.Content(settingsVisualLanguageRegionSnapshot())
            } else {
                ScreenLoadState.Loading
            },
        appearance =
            if (destination == SharedSettingsDestination.Appearance) {
                ScreenLoadState.Content(settingsVisualAppearanceSnapshot())
            } else {
                ScreenLoadState.Loading
            },
        mobile =
            if (destination == SharedSettingsDestination.Notifications || destination == SharedSettingsDestination.Mobile) {
                ScreenLoadState.Content(settingsVisualMobileSnapshot())
            } else {
                ScreenLoadState.Loading
            }
    )
}

/**
 * Visual-only full-height support (task #388 A). The capture harness grows the
 * emulator display so the whole Settings route fits one frame; it needs (1) the
 * fullHeight capture flag readable at the list sites and (2) the content-bottom
 * pixel to crop the tall frame to. Both read capture-intent pageData params the
 * same way the safe-area overrides do (RaftAppSafeArea.kt); production pages never
 * set them, so this is inert outside compose_settings_route_visual captures.
 */
@Composable
internal fun settingsVisualFullHeightEnabled(): Boolean {
    val params = (LocalActivity.current.getPager() as Pager).pageData.params
    return params.optBoolean("fullHeight", params.optBoolean("slockVisualFullHeight", false))
}

@Composable
internal fun SettingsVisualFullHeightSentinel() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    var lastLoggedBottom by remember { mutableStateOf(-1) }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val bottom = (coordinates.positionInRoot().y + coordinates.size.height).toInt()
                if (bottom != lastLoggedBottom) {
                    lastLoggedBottom = bottom
                    bridge.log("settings_route_visual_fullheight contentBottomPx=$bottom")
                }
            }
    )
}

internal data class SettingsRouteVisualPayload(
    val destination: String,
    val itemId: String?,
    val fixtureKey: String,
    val fullHeight: Boolean
)

private fun parseSettingsRouteVisualPayload(params: build.raft.app.compat.json.JSONObject): SettingsRouteVisualPayload {
    val destination =
        params.optString(SettingsRouteVisualDestinationKey)
            .trim()
            .takeIf {
                SettingsNativeDetailPage.fromKey(it) != null ||
                    it.toVisualFixtureSharedSettingsDestination() != null ||
                    it == SettingsDestinationHandsFeedback
            }
            ?: SettingsDestinationAdministration
    val itemId =
        params.optString(SettingsRouteVisualItemIdKey)
            .trim()
            .ifBlank { params.optString(SettingsRouteVisualMachineIdKey).trim() }
            .takeIf { it.isNotBlank() }
    val fixtureKey =
        params.optString(SettingsRouteVisualFixtureKey)
            .trim()
            .takeIf { it.isNotBlank() }
            ?: "canonical"
    return SettingsRouteVisualPayload(
        destination = destination,
        itemId = itemId,
        fixtureKey = fixtureKey,
        fullHeight = params.optBoolean("fullHeight", params.optBoolean("slockVisualFullHeight", false))
    )
}

private fun settingsRouteVisualSession(): StoredAuthSession {
    val fixture = settingsVisualFixtureValues
    return StoredAuthSession(
        user =
            StoredAuthUser(
                id = fixture.ownerId,
                email = fixture.ownerEmail,
                name = fixture.ownerName,
                displayName = fixture.ownerDisplayName,
                emailVerified = fixture.ownerEmailVerified,
                preferredTimeFormat = "24h",
                timeFormat = "24h"
            ),
        accessToken = "visual-access-token",
        refreshToken = "visual-refresh-token",
        selectedServerId = fixture.serverId,
        selectedServerSlug = fixture.serverSlug,
        servers =
            listOf(
                StoredAuthServer(
                    id = fixture.serverId,
                    name = fixture.serverName,
                    slug = fixture.serverSlug,
                    role = fixture.ownerRole,
                    plan = fixture.serverPlan
                )
            )
    )
}
