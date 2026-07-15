package build.raft.app

import build.raft.app.data.NetworkSlockThreadApi

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
import build.raft.app.components.SlockDisabledActionBorder
import build.raft.app.components.SlockDisabledActionContent
import build.raft.app.components.SlockDisabledActionContentArgb
import build.raft.app.components.SlockDisabledActionShadow
import build.raft.app.components.SlockRaftUiSelectField
import build.raft.app.components.SlockRaftUiSelectionOption
import build.raft.app.components.SlockSlugInput
import build.raft.app.components.SharedHomeSearchInput
import build.raft.app.components.SlockLinkButton
import build.raft.app.components.SlockSwitchButton
import build.raft.app.components.SlockToolbarChip
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.slockDisabledActionBackgroundFor
import build.raft.app.data.NetworkSlockHomeApi
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.KeyValueRecompositionProfilerPreferenceStore
import build.raft.app.data.KuiklyHostNetworkEvents
import build.raft.app.data.KuiklyAppUpdateEvents
import build.raft.app.data.KuiklyNotificationPreferenceEvents
import build.raft.app.data.KuiklyRealtimeEvents
import build.raft.app.data.isAppUpdateHomeNotificationId
import build.raft.app.data.isMachineHomeNotificationId
import build.raft.app.data.MOBILE_ACTION_LANGUAGE
import build.raft.app.data.MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES
import build.raft.app.data.MOBILE_ACTION_COMPACT_MESSAGES
import build.raft.app.data.MOBILE_ACTION_LONG_MESSAGE_COLLAPSE
import build.raft.app.data.MOBILE_ACTION_COMPOSER_SEND_KEY
import build.raft.app.data.MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS
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
import build.raft.app.data.kmmResourceLocaleTag
import build.raft.app.data.DEVELOPER_ACTION_API_ENVIRONMENT
import build.raft.app.data.DEVELOPER_ACTION_QUIVER_UPDATE_CHECK
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
import build.raft.app.data.AnnouncementItem
import build.raft.app.data.NetworkSlockAnnouncementsRemindersApi
import build.raft.app.data.SlockAnnouncementsRemindersApiClient
import build.raft.app.data.NetworkSlockTasksApi
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
import build.raft.app.data.AuthLossDecision
import build.raft.app.data.AuthMobileOAuthLinkCompleteResponse
import build.raft.app.data.AuthMobileOAuthStartResponse
import build.raft.app.data.AuthPendingOpenRequest
import build.raft.app.data.AuthRefreshResponse
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.AuthSocialCompleteResponse
import build.raft.app.data.ChannelRepository
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
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.WindowNavigationReadiness
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
import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import build.raft.app.data.SharedHomeShellDestinationActivity
import build.raft.app.data.SharedHomeShellDestinationSaved
import build.raft.app.data.SharedHomeShellDestinationSearch
import build.raft.app.data.SharedHomeShellIntent
import build.raft.app.data.SharedHomeShellStore
import build.raft.app.data.RealtimeGuardedDomainEventSinks
import build.raft.app.data.RealtimeShellCallbackGate
import build.raft.app.data.RealtimeConnectionWitness
import build.raft.app.data.RealtimeGrantRecord
import build.raft.app.data.SharedSettingsAboutSnapshot
import build.raft.app.data.SharedSettingsApiEnvironmentOption
import build.raft.app.data.SharedSettingsApiEnvironmentRequest
import build.raft.app.data.SharedSettingsDataSource
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsDeveloperSnapshot
import build.raft.app.data.SharedSettingsDiagnosticsEntry
import build.raft.app.data.SharedSettingsDiagnosticsSnapshot
import build.raft.app.data.SharedSettingsEffect
import build.raft.app.data.SharedSettingsHomeSnapshot
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsNavItem
import build.raft.app.data.SharedSettingsOpenSourceLicenseItem
import build.raft.app.data.SharedSettingsOpenSourceLicensesSnapshot
import build.raft.app.data.SharedSettingsRecompositionProfilerSnapshot
import build.raft.app.data.SharedSettingsRoute
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SharedSettingsStorageRow
import build.raft.app.data.SharedSettingsStorageSnapshot
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.ServerSwitcherOrderPreference
import build.raft.app.data.SlockAuthApi
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.toSharedHomeAppUpdateNotification
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_EXPORT
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_ENABLED
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_REPORT
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_RESET
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_START
import build.raft.app.data.DIAGNOSTICS_ACTION_PROFILER_STOP
import build.raft.app.data.isAuthSessionError
import build.raft.app.data.isValidChannelName
import build.raft.app.data.KuiklyAuthSessionEvents
import build.raft.app.data.isRefreshing
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.serverConfirmationItemId
import build.raft.app.data.userFacingMessage
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
import build.raft.app.data.SearchScope
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
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.SharedConversationIdentitySupplementSource
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SharedHomeSavedPanelState
import build.raft.app.data.SharedHomeSavedStateCache
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.SharedHomeSearchSavedIntent
import build.raft.app.data.SharedHomeSearchSavedViewModel
import build.raft.app.data.AccountProfileUpdateRequest
import build.raft.app.data.AccountPasswordChangeRequest
import build.raft.app.data.AccountProviderLinkStartRequest
import build.raft.app.data.AccountProviderLinkStartResult
import build.raft.app.data.AccountSettingsSnapshot
import build.raft.app.data.AccountAvatarUploadRequest
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
import build.raft.app.data.SettingsQuiverFeedbackSubmission
import build.raft.app.data.SettingsQuiverFeedbackDraftStore
import build.raft.app.data.formatAttachmentSizeLabel
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
import build.raft.app.data.SettingsConnectedAppLogoUploadRequest
import build.raft.app.data.SettingsConnectedAppMarketplaceSnapshot
import build.raft.app.data.SettingsConnectedAppPublishRequest
import build.raft.app.data.SettingsConnectedAppRegisterRequest
import build.raft.app.data.SettingsConnectedAppSecretSnapshot
import build.raft.app.data.SettingsConnectedAppUpdateRequest
import build.raft.app.data.SettingsConnectedAppsSnapshot
import build.raft.app.data.SettingsDiagnosticsFeedbackDraft
import build.raft.app.data.SettingsMobileSelectionSnapshot
import build.raft.app.data.SettingsMobileSelectionOptionSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import build.raft.app.data.SettingsPlanUsageSnapshot
import build.raft.app.data.SettingsLanguageRegionPreferenceUpdateRequest
import build.raft.app.data.SettingsLanguageRegionSnapshot
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.SettingsServerAdminSnapshot
import build.raft.app.data.SettingsServerAgreementSnapshot
import build.raft.app.data.SettingsServerActionResult
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
import build.raft.app.data.SharedServerMembersState
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
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.PerReceiverPinnedChanged
import build.raft.app.model.PerReceiverReadStateChanged
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
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.threadIdentity
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.model.SlockTimeFormat24Hour
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.slockEpochMillisUtcIso
import build.raft.app.model.slockStartOfDayUtcIso
import build.raft.app.resources.MR
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
import com.tencent.kuikly.compose.foundation.layout.BoxWithConstraints
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
import com.tencent.kuikly.compose.text.SelectionContainer
import com.tencent.kuikly.compose.text.rememberSelectionContainerState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.rememberPullToRefreshState
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.rotate
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.graphicsLayer
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalFocusManager
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.text.input.ImeAction
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.zIndex
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.desc.ResourceFormatted
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import com.tencent.tmm.kmmresource.resource.desc.desc
import com.tencent.tmm.kmmresource.resource.format
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
const val SETTINGS_ROUTE_VISUAL_PAGE = "compose_settings_route_visual"
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

internal sealed interface AuthGateMessage {
    data class Dynamic(val value: String) : AuthGateMessage

    data class Static(
        val value: StringDesc,
        val pinSignOut: Boolean = false
    ) : AuthGateMessage
}

@Composable
private fun AuthGateMessage?.localizedMessage(): String? =
    when (this) {
        is AuthGateMessage.Dynamic -> value
        is AuthGateMessage.Static -> value.localized()
        null -> null
    }

private const val HomeSurfaceVisibleResumeDelayMs = 48L
private val EmptyComposerDraftRecordsFlow = MutableStateFlow(emptySet<SharedThreadComposerDraftRecord>())
private val EmptyConversationDirectoryProjectionFlow =
    MutableStateFlow(emptyMap<String, SharedConversationDirectoryProjection>())
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
private const val SettingsDestinationOpenSourceLicenses = "open-source-licenses"
private const val SettingsDestinationReleaseNotes = "releaseNotes"
private const val SettingsDestinationDiagnostics = "diagnostics"
private const val SettingsDestinationDiagnosticsFeedbackChannel = "diagnostics-feedback-channel"
private const val SettingsDestinationQuiverFeedback = "quiver-feedback"
private const val SettingsDestinationDeveloper = "developer"
private const val SettingsDestinationApiEnvironment = "api-environment"
private const val SettingsDestinationRouter = "router"
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
private const val AppShellNotificationKindKey = "slockNotificationKind"
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

private val AppShellChildPageTabKeys = listOf("home", "tasks", "members", "settings")
private val AppShellChildPagePrewarmOrder = listOf("settings", "tasks", "members")
private const val AppShellChildPagePrewarmInitialDelayMs = 600L
private const val AppShellChildPagePrewarmIntervalMs = 500L

internal typealias AppShellChildTabRenderer = @Composable (tab: String, active: Boolean) -> Unit

internal class AppShellChildTabRenderSlot(
    initialRenderer: AppShellChildTabRenderer
) {
    var renderer: AppShellChildTabRenderer by mutableStateOf(initialRenderer)
}

internal class AppShellChildTabEntry(
    val debugId: Int,
    initialSelectedTab: String = "home",
    initialRetainedTabs: Set<String> = setOf(initialSelectedTab),
    initialRenderer: AppShellChildTabRenderer = { _, _ ->
        SlockCenteredLoading()
    },
    initialModalHostState: SlockUnifiedDialogHostState? = null
) {
    var selectedTab by mutableStateOf(initialSelectedTab)
    var retainedTabs by mutableStateOf(initialRetainedTabs)
    private val renderSlots =
        AppShellChildPageTabKeys.associateWith {
            AppShellChildTabRenderSlot(initialRenderer)
        }
    var modalHostState: SlockUnifiedDialogHostState? by mutableStateOf(initialModalHostState)

    fun rendererFor(tab: String): AppShellChildTabRenderer =
        renderSlots.getValue(tab).renderer

    fun updateRenderer(tab: String, renderer: AppShellChildTabRenderer) {
        renderSlots.getValue(tab).renderer = renderer
    }

    fun updateSelection(
        selectedTab: String,
        retainedTabs: Set<String>,
        renderer: AppShellChildTabRenderer
    ) {
        updateRenderer(selectedTab, renderer)
        this.retainedTabs = retainedTabs
        this.selectedTab = selectedTab
    }
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

@Page(SETTINGS_HOME_PAGE, supportInLocal = true)
internal class ComposeSettingsHomePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsHomeScreen() }
    }
}

@Page(SETTINGS_ROUTE_VISUAL_PAGE, supportInLocal = true)
internal class ComposeSettingsRouteVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsRouteVisualScreen() }
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
                        avatarWireValue = memberAvatarWireValue ?: result.avatarWireValue ?: AvatarReference.Random(result.key).wireValue,
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
            avatarWireValue = avatarWireValue ?: AvatarReference.Random(sender.searchAvatarSeed()).wireValue,
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
    val serverMembersStore: SharedServerMembersStore,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    val settingsCapabilities: BridgeSettingsPlatformCapabilities,
    val homeSavedStateCache: SharedHomeSavedStateCache,
    val timeContext: AppShellThreadTimeContext?,
    val alignOwnMessagesRight: Boolean,
    val compactConsecutiveMessages: Boolean,
    val foregroundMessageBannersEnabled: Boolean,
    val activityTaskStatusBadgesEnabled: Boolean,
    val threadRowAffordances: Set<ThreadRowAffordancePreference>,
    val storeScope: SlockShellStoreScope,
    val shellScope: SlockAppShellScope,
    val conversationIdentityProvider: SharedConversationIdentityProvider?,
    val sharedHomeStore: SharedHomeStore?
)

@Composable
private fun rememberHomeSecondaryRouteEnvironment(): HomeSecondaryRouteEnvironment {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val bridgeCallScope = rememberCoroutineScope()
    val settingsCapabilities =
        remember(bridge, bridgeCallScope) {
            BridgeSettingsPlatformCapabilities(bridge, bridgeCallScope.kuiklyBridgeCallContext())
        }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val serverMembersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val sharedPreferenceStore = remember { slockSharedPreferenceStore() }
    val chatLayoutPreferenceRepository =
        remember(sharedPreferenceStore) {
            PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(sharedPreferenceStore))
    }
    var myMessageAlignment by remember { mutableStateOf(MyMessageAlignmentPreference.Default) }
    var compactConsecutiveMessages by remember { mutableStateOf(false) }
    var foregroundMessageBannersEnabled by remember { mutableStateOf(false) }
    var activityTaskStatusBadgesEnabled by remember { mutableStateOf(false) }
    var threadRowAffordances by remember { mutableStateOf(ThreadRowAffordancePreference.Default) }
    LaunchedEffect(chatLayoutPreferenceRepository) {
        val chatLayout = chatLayoutPreferenceRepository.getChatLayoutPreference()
        myMessageAlignment = chatLayout.myMessageAlignment
        compactConsecutiveMessages = chatLayout.compactConsecutiveMessages
        foregroundMessageBannersEnabled = chatLayout.foregroundMessageBannersEnabled
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
        settingsCapabilities,
        serverMembersStore,
        sharedStores.composerDraftPersistSlice,
        sharedStores.homeSavedStateCache,
        timeContext,
        myMessageAlignment,
        compactConsecutiveMessages,
        foregroundMessageBannersEnabled,
        activityTaskStatusBadgesEnabled,
        threadRowAffordances,
        storeScope,
        shellScope
    ) {
        HomeSecondaryRouteEnvironment(
            pager = pager,
            bridge = bridge,
            authStore = authStore,
            networkClient = networkClient,
            serverMembersStore = serverMembersStore,
            composerDraftPersistSlice = sharedStores.composerDraftPersistSlice,
            settingsCapabilities = settingsCapabilities,
            homeSavedStateCache = sharedStores.homeSavedStateCache,
            timeContext = timeContext,
            alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
            compactConsecutiveMessages = compactConsecutiveMessages,
            foregroundMessageBannersEnabled = foregroundMessageBannersEnabled,
            activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
            threadRowAffordances = threadRowAffordances,
            storeScope = storeScope,
            shellScope = shellScope,
            conversationIdentityProvider = sharedStores.conversationIdentityProvider(storeScope),
            sharedHomeStore = sharedStores.sharedHomeStore(storeScope)
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
                threadRowAffordances = environment.threadRowAffordances,
                storeScope = environment.storeScope
            )
        },
        onClose = { environment.pager.closeHomeSecondaryPage() },
        bottomContentPadding = environment.shellScope.bottomInset(includeBottomTabBar = false),
        timeContext = environment.timeContext,
        shellScope = environment.shellScope,
        listState = listState,
        activityTaskStatusBadgesEnabled = environment.activityTaskStatusBadgesEnabled,
        storeScope = environment.storeScope,
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
                    parseSlockHome(payload, session, slockLogger("SlockHomeSecondaryParse"))
                },
                diagnosticsLogger = slockLogger("SlockHomeSecondaryHome", "home_secondary_home")
            )
        }
    val homeViewModel: SharedHomeViewModel =
        viewModel(key = "home-secondary-home") {
            SharedHomeViewModel(
                authStore = environment.authStore,
                dataSource = homeDataSource,
                diagnosticsLogger = slockLogger("SlockHomeSecondaryHome", "home_secondary_home")
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
    val activeDraftRecords by environment.composerDraftPersistSlice.activeDraftRecordsState.collectAsState(emptySet())
    val draftProjection =
        remember(activeDraftRecords) {
            activeDraftRecords.toSharedThreadComposerDraftProjection()
        }
    // t5/#419 A3: user-initiated opens resolve against the real directory + server id.
    val conversationDirectoryFlow =
        remember(environment.conversationIdentityProvider, searchServerId) {
            searchServerId?.takeIf { it.isNotBlank() }
                ?.let { serverId -> environment.conversationIdentityProvider?.observeServer(ServerId(serverId)) }
                ?: flowOf(SharedConversationDirectoryState())
        }
    val conversationDirectory by conversationDirectoryFlow.collectAsState(SharedConversationDirectoryState())
    val searchConversationDirectory =
        remember(searchServerId, searchSavedState.search.items) {
            searchServerId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ServerId)
                ?.let { serverId -> searchSavedState.search.items.toSharedConversationDirectoryState(serverId) }
                ?: SharedConversationDirectoryState()
        }
    val openConversationDirectory =
        remember(searchConversationDirectory, conversationDirectory) {
            listOf(searchConversationDirectory, conversationDirectory)
                .mergedSharedConversationDirectoryState()
        }
    LaunchedEffect(environment.sharedHomeStore, searchServerId, searchSavedState.search.items) {
        val serverId = searchServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return@LaunchedEffect
        environment.sharedHomeStore?.supplementConversationIdentity(
            serverId = serverId,
            source = SharedConversationIdentitySupplementSource.Search,
            items = searchSavedState.search.items
        )
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
            when (
                val resolution =
                    item.toHomeMessageOpenRequest(
                        serverId = searchServerId.orEmpty(),
                        conversationDirectory = openConversationDirectory
                    )
            ) {
                is ConversationOpenResolution.Resolved ->
                    environment.pager.openStandaloneHomeMessageRequest(
                        bridge = environment.bridge,
                        request = resolution.request,
                        source = "home_search",
                        timeContext = environment.timeContext,
                        alignOwnMessagesRight = environment.alignOwnMessagesRight,
                        compactConsecutiveMessages = environment.compactConsecutiveMessages,
                        threadRowAffordances = environment.threadRowAffordances,
                        storeScope = environment.storeScope
                    )
                is ConversationOpenResolution.Pending -> {
                    environment.bridge.logWarn(
                        "search_open_request_pending reason=${resolution.reason} itemKey=${item.key}"
                    )
                    environment.bridge.toast("Conversation isn't ready yet — try again")
                }
            }
        },
        onOpenEntity = { entity ->
            environment.pager.openHomeSearchEntity(
                bridge = environment.bridge,
                entity = entity,
                timeContext = environment.timeContext,
                alignOwnMessagesRight = environment.alignOwnMessagesRight,
                compactConsecutiveMessages = environment.compactConsecutiveMessages,
                threadRowAffordances = environment.threadRowAffordances,
                storeScope = environment.storeScope
            )
        },
        initialOpenFilterId = initialOpenFilterId,
        initialFocusInput = initialFocusInput,
        bottomContentPadding = environment.shellScope.bottomInset(includeBottomTabBar = false),
        timeContext = environment.timeContext,
        shellScope = environment.shellScope,
        listState = listState
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
    // t5/#419 A3: user-initiated opens resolve against the real directory + server id.
    val conversationDirectoryFlow =
        remember(environment.conversationIdentityProvider, savedServerId) {
            savedServerId?.takeIf { it.isNotBlank() }
                ?.let { serverId -> environment.conversationIdentityProvider?.observeServer(ServerId(serverId)) }
                ?: flowOf(SharedConversationDirectoryState())
        }
    val conversationDirectory by conversationDirectoryFlow.collectAsState(SharedConversationDirectoryState())
    val savedConversationDirectory =
        remember(savedServerId, searchSavedState.saved.items) {
            savedServerId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ServerId)
                ?.let { serverId -> searchSavedState.saved.items.toSharedConversationDirectoryState(serverId) }
                ?: SharedConversationDirectoryState()
        }
    val openConversationDirectory =
        remember(savedConversationDirectory, conversationDirectory) {
            listOf(savedConversationDirectory, conversationDirectory)
                .mergedSharedConversationDirectoryState()
        }
    LaunchedEffect(environment.sharedHomeStore, savedServerId, searchSavedState.saved.items) {
        val serverId = savedServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return@LaunchedEffect
        environment.sharedHomeStore?.supplementConversationIdentity(
            serverId = serverId,
            source = SharedConversationIdentitySupplementSource.Saved,
            items = searchSavedState.saved.items
        )
    }
    SharedHomeSavedPanel(
        state = searchSavedState.saved,
        memberAvatarResolver = memberAvatarResolver,
        draftProjection = draftProjection,
        onIntent = searchSavedViewModel::dispatch,
        onClose = { environment.pager.closeHomeSecondaryPage() },
        onOpenItem = { item ->
            when (
                val resolution =
                    item.toHomeMessageOpenRequest(
                        serverId = savedServerId.orEmpty(),
                        conversationDirectory = openConversationDirectory
                    )
            ) {
                is ConversationOpenResolution.Resolved ->
                    environment.pager.openStandaloneHomeMessageRequest(
                        bridge = environment.bridge,
                        request = resolution.request,
                        source = "home_saved",
                        timeContext = environment.timeContext,
                        alignOwnMessagesRight = environment.alignOwnMessagesRight,
                        compactConsecutiveMessages = environment.compactConsecutiveMessages,
                        threadRowAffordances = environment.threadRowAffordances,
                        storeScope = environment.storeScope
                    )
                is ConversationOpenResolution.Pending -> {
                    environment.bridge.logWarn(
                        "saved_open_request_pending reason=${resolution.reason} itemKey=${item.key}"
                    )
                    environment.bridge.toast("Conversation isn't ready yet — try again")
                }
            }
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
                warningLogger = slockLogger("SlockHomeSecondarySaved", "home_secondary_saved", SlockLogLevel.Warn)
            )
        }
    val searchRepository =
        remember(environment.authStore, environment.networkClient) {
            RealSearchRepository(
                sessionStore = environment.authStore,
                networkClient = environment.networkClient,
                warningLogger = slockLogger("SlockHomeSecondarySearch", "home_secondary_search", SlockLogLevel.Warn)
            )
        }
    val memberRepository =
        remember(environment.authStore, environment.networkClient) {
            RealMemberRepository(
                sessionStore = environment.authStore,
                networkClient = environment.networkClient,
                logger = slockLogger("SlockHomeSecondaryMember", "home_secondary_member"),
                warningLogger = slockLogger("SlockHomeSecondaryMember", "home_secondary_member_warning", SlockLogLevel.Warn)
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
    private var adaptivePane by mutableStateOf(SlockAdaptivePaneState())

    override fun willInit() {
        super.willInit()
        setContent {
            SlockAppShellContent(
                pageVisibleTick = slockPageVisibleResumeTick,
                initialAuthRestoreTick = initialAuthRestoreTick,
                adaptivePane = adaptivePane
            )
        }
    }

    override fun onSlockPageDidAppear(isInitialAppearance: Boolean) {
        if (isInitialAppearance) {
            initialAuthRestoreTick += 1
        }
    }

    override fun onSlockAdaptivePaneChanged(eventData: JSONObject) {
        val event = eventData.toSlockAdaptivePaneEventOrNull() ?: return
        adaptivePane = reduceSlockAdaptivePane(adaptivePane, event)
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
    val retained = tab in entry.retainedTabs
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
    if (appShellChildPageRendersContent(active = active, retained = retained)) {
        val renderer = entry.rendererFor(tab)
        val modalHostState = entry.modalHostState
        if (modalHostState != null) {
            SlockUnifiedDialogHostProvider(modalHostState) {
                renderer(tab, active)
            }
        } else {
            renderer(tab, active)
        }
    } else {
        Box(modifier = Modifier.fillMaxSize())
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
    val enabled: Boolean = false,
    val toggleValue: Boolean? = null,
    val supportingDetail: String = ""
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
            subtitle = "Version and diagnostics",
            iconName = SlockLucideIconName.BadgeInfo
        ),
        SlockTabItemPayload(
            key = SettingsDestinationQuiverFeedback,
            title = "Send feedback",
            subtitle = "Report a problem or suggestion",
            iconName = SlockLucideIconName.MessageSquarePlus
        ),
        SlockTabItemPayload(
            key = SettingsDestinationReleaseNotes,
            title = "Release Notes",
            subtitle = "Product changes and migration notes",
            iconName = SlockLucideIconName.FileText
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
        key = SettingsDestinationQuiverFeedback,
        title = "Send feedback",
        subtitle = "Report a problem or suggestion",
        iconName = SlockLucideIconName.MessageSquarePlus
    ),
    SlockTabItemPayload(
        key = "release-notes",
        title = "Release Notes",
        subtitle = "Product changes and migration notes",
        iconName = SlockLucideIconName.FileText
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

private val settingsVisualFixtureValues: SettingsVisualFixtureValues
    get() = SettingsVisualFixtureBridge.values

private val settingsPageVisualPayload: KuiklySharedSettingsPayload
    get() {
        val fixture = settingsVisualFixtureValues
        return KuiklySharedSettingsPayload(
            instanceId = "visual-settings-page",
            title = "Account",
            detail = "Profile, email, password, connected accounts",
            heroIconName = SlockLucideIconName.User,
            heroLabel = fixture.ownerDisplayName,
            sections = listOf(
                KuiklySharedSettingsSection(
                    title = "Profile",
                    rows = listOf(
                        KuiklySharedSettingsRow("displayName", SlockLucideIconName.User, "Display name", "Shown across messages, tasks, and mentions.", status = fixture.ownerDisplayName),
                        KuiklySharedSettingsRow("email", SlockLucideIconName.Mail, "Email", fixture.ownerEmail, status = "Verified"),
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
    }

private val bottomTabBarSmokeItems = listOf(
    SlockBottomTabItemPayload("home", "HOME", SlockLucideIconName.House),
    SlockBottomTabItemPayload("tasks", "TASKS", SlockLucideIconName.SquareCheckBig, selected = true),
    SlockBottomTabItemPayload("members", "MEMBERS", SlockLucideIconName.Users),
    SlockBottomTabItemPayload("settings", "SETTINGS", SlockLucideIconName.Settings)
)

internal fun appShellBottomTabs(
    selectedKey: String,
    homeLabel: String,
    tasksLabel: String,
    membersLabel: String,
    settingsLabel: String
): List<SlockBottomTabItemPayload> =
    listOf(
        SlockBottomTabItemPayload("home", homeLabel, SlockLucideIconName.House, selected = selectedKey == "home"),
        SlockBottomTabItemPayload("tasks", tasksLabel, SlockLucideIconName.SquareCheckBig, selected = selectedKey == "tasks"),
        SlockBottomTabItemPayload("members", membersLabel, SlockLucideIconName.Users, selected = selectedKey == "members"),
        SlockBottomTabItemPayload("settings", settingsLabel, SlockLucideIconName.Settings, selected = selectedKey == "settings")
    )

private val retainedAppShellTabKeys = listOf("home", "tasks", "members", "settings")

internal fun retainedAppShellSelectedTab(selectedTab: String): String =
    selectedTab.takeIf { it in retainedAppShellTabKeys } ?: "home"

internal fun shouldHandleAppShellTabSelection(currentTab: String, nextTab: String): Boolean =
    retainedAppShellSelectedTab(currentTab) != retainedAppShellSelectedTab(nextTab)

internal fun shouldDismissHomeNotificationCenterForAppShellTabSelection(currentTab: String, targetTab: String): Boolean =
    retainedAppShellSelectedTab(currentTab) == "home" &&
        retainedAppShellSelectedTab(targetTab) != "home"

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

internal fun appShellActiveChildPagerTab(selectedTab: String): String? =
    retainedAppShellSelectedTab(selectedTab).takeIf { it in AppShellChildPageTabKeys }

// V2 keepalive stack: the ordered set of child tabs to keep composed (alive) at once.
// Empty when there is no active child tab (non-child surfaces render directly). The
// active tab is always included even if [mountedTabs] is momentarily stale, so it can
// never fail to render.
internal fun appShellRetainedChildTabs(activeChildTab: String?, mountedTabs: Set<String>): List<String> =
    if (activeChildTab == null) {
        emptyList()
    } else {
        (AppShellChildPageTabKeys.filter { it in mountedTabs } + activeChildTab).distinct()
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

internal fun homeDestinationRefreshesOnLifecycleResume(destination: String?): Boolean =
    retainedHomeSecondaryDestination(destination) == null

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

internal fun appShellDefersReadyGraph(authGateState: SharedAuthGateState): Boolean =
    authGateState == SharedAuthGateState.Restoring

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

    fun onLifecycleHidden(appearanceGeneration: Int) {
        pendingLifecycleGeneration =
            maxOf(
                pendingLifecycleGeneration ?: Int.MIN_VALUE,
                appearanceGeneration + 1
            )
    }

    fun shouldRefresh(
        signal: AppShellVisibleRefreshSignal,
        appearanceGeneration: Int,
        target: String
    ): Boolean {
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
            return false
        }
        val seenSignals = seenSignalsByKey[key]
        if (seenSignals != null) {
            seenSignals += signal
            return false
        }
        seenSignalsByKey[key] = mutableSetOf(signal)
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
        // Home secondary destinations are native child pages. Keep the root tab bar mounted
        // behind them so returning does not recreate it after the root is already visible.
        "home" ->
            selectedHomeDestination == null ||
                retainedHomeSecondaryDestination(selectedHomeDestination) != null
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

private fun sharedHomeErrorDisplay(code: String): SlockHomeInlineError =
    SlockHomeInlineError(
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

internal fun sharedHomeSearchErrorDisplay(code: String): SlockHomeInlineError =
    SlockHomeInlineError(
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
                    settingsUserFacingErrorCodeMessage(code)
            },
        code = code.ifBlank { "unknown_error" },
        actionLabel = "Retry"
    )

@Composable
private fun localizedSharedHomeErrorDisplay(code: String): SlockHomeInlineError =
    SlockHomeInlineError(
        title =
            when {
                ScreenError(code, retryable = false).isAuthSessionError() -> stringResource(MR.strings.home_error_session_expired)
                code.contains("permission", ignoreCase = true) -> stringResource(MR.strings.home_error_unavailable)
                code.contains("timeout", ignoreCase = true) -> stringResource(MR.strings.home_error_timeout)
                code.contains("offline", ignoreCase = true) -> stringResource(MR.strings.home_error_offline)
                code.contains("network", ignoreCase = true) -> stringResource(MR.strings.home_error_refresh)
                else -> stringResource(MR.strings.home_error_load)
            },
        detail =
            when {
                ScreenError(code, retryable = false).isAuthSessionError() -> stringResource(MR.strings.home_error_session_detail)
                code.contains("permission", ignoreCase = true) -> stringResource(MR.strings.home_error_unavailable_detail)
                code.contains("timeout", ignoreCase = true) -> stringResource(MR.strings.home_error_timeout_detail)
                code.contains("offline", ignoreCase = true) -> stringResource(MR.strings.home_error_offline_detail)
                code.contains("network", ignoreCase = true) -> stringResource(MR.strings.home_error_network_detail)
                else -> stringResource(MR.strings.home_error_load_detail)
            },
        code = code,
        actionLabel = stringResource(MR.strings.common_retry)
    )

@Composable
private fun localizedSharedHomeSearchErrorDisplay(code: String): SlockHomeInlineError =
    SlockHomeInlineError(
        title =
            when {
                code.contains("permission", ignoreCase = true) -> stringResource(MR.strings.search_error_unavailable)
                code.contains("timeout", ignoreCase = true) -> stringResource(MR.strings.search_error_timeout)
                code.contains("offline", ignoreCase = true) -> stringResource(MR.strings.home_error_offline)
                code.contains("network", ignoreCase = true) -> stringResource(MR.strings.search_error_refresh)
                else -> stringResource(MR.strings.search_error_load)
            },
        detail =
            when {
                code.contains("permission", ignoreCase = true) -> stringResource(MR.strings.search_error_permission_detail)
                code.contains("timeout", ignoreCase = true) -> stringResource(MR.strings.search_error_timeout_detail)
                code.contains("offline", ignoreCase = true) -> stringResource(MR.strings.search_error_offline_detail)
                code.contains("network", ignoreCase = true) -> stringResource(MR.strings.search_error_network_detail)
                else -> settingsUserFacingErrorCodeMessage(code)
            },
        code = code.ifBlank { "unknown_error" },
        actionLabel = stringResource(MR.strings.common_retry)
    )

internal fun sharedHomeHeaderRefreshing(state: SharedHomeState): Boolean =
    (state.content as? SharedHomeContentState.Content)?.contentRefreshing == true

internal fun homeSurfaceVisibilityDispatchDelayMs(visible: Boolean): Long =
    if (visible) HomeSurfaceVisibleResumeDelayMs else 0L

@Composable
private fun AppShellTabs(
    selectedTab: String,
    childHostShellId: String,
    mountedTabs: Set<String>,
    childTabSwitchEpoch: Int,
    log: (String) -> Unit = {},
    content: @Composable (tab: String, active: Boolean) -> Unit
) {
    val activeTab = retainedAppShellSelectedTab(selectedTab)
    val activeChildTab = appShellActiveChildPagerTab(activeTab)
    // Keepalive stack (V2): render every retained child tab, not just the active one,
    // so Home/Tasks/Members/Settings stay alive and switching back is instant with
    // full state. Only the active child is visible; the rest are hard-hidden via the
    // native `active` guard (visibility=INVISIBLE), so an inactive child's late render
    // can never reach the visible layer. Visibility is driven purely by selectedTab —
    // there is no HorizontalPager/scrollToPage, so none of the pager scroll races
    // (overlay / blank / stale native page) can occur. The active tab is always in the
    // list even if mountedTabs is momentarily stale.
    val retainedChildTabs = appShellRetainedChildTabs(activeChildTab, mountedTabs)
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
                    SlockAppShellChildTabHost(
                        shellId = childHostShellId,
                        tab = tab,
                        active = tab == activeChildTab
                    )
                }
            }
        } else {
            key("app-shell-$activeTab") {
                content(activeTab, true)
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
            modifier =
                Modifier
                    .fillMaxSize(),
            active = active,
            glassStyle = AppShellChildPageGlassStyleLiquid
        )
    }
}

@Composable
private fun SlockAppShellContent(
    pageVisibleTick: Int = 0,
    initialAuthRestoreTick: Int = 0,
    adaptivePane: SlockAdaptivePaneState = SlockAdaptivePaneState()
) {
    // #85 follow-up: seed the KmmResource `StringDesc.localeType`
    // global from the persisted `AppLocale` as early as possible
    // in the app shell lifecycle, so shared `StringDesc` lookups resolve
    // against the user's last selection rather than the OS default.
    RememberKmmResourceLocaleSeedFromLocalActivity(pageVisibleTick)
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
    var childTabSwitchEpoch by remember { mutableStateOf(0) }
    var homeFocusLossDismissEpoch by remember { mutableStateOf(0) }
    var retainedAppShellTabs by remember {
        mutableStateOf(setOf("home", launchSelection.selectedTab))
    }
    var prewarmedAppShellTabs by remember { mutableStateOf(emptySet<String>()) }
    var selectedSettingsDestination by remember {
        mutableStateOf(launchSelection.selectedSettingsDestination)
    }
    var settingsHeaderTapCount by remember { mutableStateOf(0) }
    var homeListPositions by remember { mutableStateOf(AppShellServerLazyListPositions()) }
    var pendingHomeServerSwitchId by remember { mutableStateOf<String?>(null) }
    var observedHomeServerSwitchId by remember { mutableStateOf<String?>(null) }
    var tasksListPosition by remember { mutableStateOf(AppShellLazyListPosition()) }
    var membersListState by remember { mutableStateOf(AppShellMembersListState()) }
    var hostNetworkStatus by remember { mutableStateOf(SlockAppShellNetworkStatus.Online) }
    val childTabShellId = remember(pager.pagerId) { "app-shell-child-tabs-${pager.pagerId}" }
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val visibleAppearanceGeneration =
        appShellAppearanceGeneration(pageVisibleTick, initialAuthRestoreTick)
    val windowNavigationCoordinator =
        remember(sharedStores, storeScope) { sharedStores.windowNavigationCoordinator(storeScope) }
    LaunchedEffect(pager.pagerId, visibleAppearanceGeneration) {
        if (visibleAppearanceGeneration > 0) {
            sharedStores.markActiveAppShellPager(pager.pagerId)
        }
    }
    val authStore = sharedStores.authStore
    val shellAuthStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val selectedServerStore = remember(sharedStores, storeScope) { sharedStores.selectedServerStore(storeScope) }
    val appShellServerMembersStore =
        remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val appShellRealtimeDomainEventOwner =
        remember(sharedStores, storeScope) { sharedStores.realtimeDomainEventOwner(storeScope) }
    val realtimeAuthorityFlow =
        remember(sharedStores, storeScope) { sharedStores.realtimeAuthority(storeScope) }
    val realtimeAuthority by realtimeAuthorityFlow.collectAsState()
    val realtimeCallbackGate =
        remember(sharedStores, appShellRealtimeDomainEventOwner) {
            RealtimeShellCallbackGate(
                authorityProvider = sharedStores.realtimeAuthorityProvider,
                domainOwner = appShellRealtimeDomainEventOwner,
            )
        }
    val appShellAgentDetailStore =
        remember(sharedStores, storeScope) { sharedStores.agentDetailStore(storeScope) }
    val selectedServerId by selectedServerStore.selectedServerId.collectAsState()
    val navigationStoreScope = remember(storeScope.shellInstanceId, selectedServerId) {
        SlockShellStoreScope.create(
            shellInstanceId = storeScope.shellInstanceId,
            initialServerId = selectedServerId ?: storeScope.initialServerId
        )
    }
    val authDataSource = remember(sharedStores, storeScope) {
        sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope)
    }
    val appShellCoroutineScope = rememberCoroutineScope()
    val eventBus = sharedStores.eventBus
    val authSessionEvents =
        remember(eventBus, pager.pagerId) {
            KuiklyAuthSessionEvents(
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
                slockLogInfo(
                    "SlockSharedAuth",
                    "shared_auth_session auth_session_clear_notify_received source=${signal.source} reason=${signal.reason}",
                    SlockPlatformLogHint.AuthCritical
                )
                appShellCoroutineScope.launch {
                    runCatchingPreservingCancellation {
                        authStore.clearSessionFromSessionClearedNotify(
                            reason = signal.reason,
                            previousSessionFingerprint = signal.previousSessionFingerprint
                        )
                    }.onSuccess { applied ->
                        slockLogInfo(
                            "SlockSharedAuth",
                            "shared_auth_session auth_session_clear_notify_result result=success applied=$applied " +
                                "source=${signal.source} reason=${signal.reason}",
                            SlockPlatformLogHint.AuthCritical
                        )
                    }.onFailure { throwable ->
                        slockLogWarn(
                            "SlockSharedAuth",
                            "shared_auth_session auth_session_clear_notify_result result=failure source=${signal.source} " +
                                "reason=${signal.reason} error=${throwable.message.orEmpty()}",
                            SlockPlatformLogHint.AuthCritical
                        )
                    }
                }
            }
        onDispose {
            authSessionEvents.removeSessionClearedListener(callbackRef)
        }
    }
    LaunchedEffect(sharedStores.authLossFunnel, pager.pagerId) {
        sharedStores.authLossFunnel.resolutions.collect { resolution ->
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
                    bridge.logWarn(
                        "app_shell_auth_loss_effect decision=auth_required " +
                            "reason=${decision.reason} source=${resolution.signal.source}"
                    )
                    val authDecision =
                        appShellBootstrapAuthDecision(AppShellBootstrapAuthSignal.AuthRequired)
                    authGateMessage = AuthGateMessage.Static(MR.strings.auth_error_session_expired.desc())
                    if (authDecision.resetSessionState) authSessionResetGeneration += 1
                    authGateViewModel.dispatch(authDecision.intent)
                }
            }
        }
    }
    LaunchedEffect(authStore) {
        authStore.sessionInvalidations.collect {
            val authDecision =
                appShellBootstrapAuthDecision(AppShellBootstrapAuthSignal.SessionInvalidated)
            bridge.realtimeDisconnect("auth_invalid")
            if (authDecision.resetSessionState) authSessionResetGeneration += 1
            authGateViewModel.dispatch(authDecision.intent)
        }
    }
    LaunchedEffect(authStore) {
        authStore.sessionChanges.collect { fingerprint ->
            val cachedSession =
                runCatchingPreservingCancellation { shellAuthStore.readSession() }.getOrNull()
            val currentSession = (authGateViewModel.state.value as? SharedAuthGateState.Ready)?.session
            val identityChanged = appShellSessionIdentityChanged(cachedSession, currentSession)
            val authDecision =
                appShellBootstrapAuthDecision(
                    AppShellBootstrapAuthSignal.SessionChanged,
                    identityChanged = identityChanged
                )
            bridge.log(
                "app_shell_session_change_received fingerprint=$fingerprint " +
                    "hasSession=${cachedSession != null} identityChanged=$identityChanged"
            )
            authGateMessage = null
            if (identityChanged) {
                bridge.realtimeDisconnect("auth_changed")
            }
            if (authDecision.resetSessionState) authSessionResetGeneration += 1
            authGateViewModel.dispatch(authDecision.intent)
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
    if (appShellDefersReadyGraph(authGateState)) {
        if (suppressRestoreLanding) {
            AppShellSilentRestoreContent()
        } else {
            AppShellRestoreSplashContent()
        }
        return
    }
    val appShellNetworkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val settingsCapabilities =
        remember(bridge, appShellCoroutineScope) {
            BridgeSettingsPlatformCapabilities(bridge, appShellCoroutineScope.kuiklyBridgeCallContext())
        }
    val memberRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealMemberRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = slockLogger("SlockAppShell", "app_shell_member_repository")
            )
        }
    val homeDataSource =
        remember(memberRepository, appShellServerMembersStore, settingsCapabilities) {
            SharedHomeDataSource(
                sessionStore = shellAuthStore,
                api = NetworkSlockHomeApi(appShellNetworkClient),
                announcementsApi = NetworkSlockAnnouncementsRemindersApi(appShellNetworkClient),
                computersApi = SlockComputersNetworkApi(appShellNetworkClient),
                appUpdateStatusProvider = { settingsCapabilities.checkQuiverUpdateStatus() },
                memberRepository = memberRepository,
                serverMembersStore = appShellServerMembersStore,
                parseHome = { payload, session ->
                    parseSlockHome(payload, session, slockLogger("SlockHomeParse"))
                },
                diagnosticsLogger = slockLogger("SlockSharedHome", "shared_home")
            )
        }
    val channelRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealChannelRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = slockLogger("SlockChannelRepository", "shared_channel_repository", SlockLogLevel.Warn)
            )
        }
    val homeReadReceiptRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealReadReceiptRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = slockLogger("SlockReadReceipt", "shared_home_read_receipt", SlockLogLevel.Warn)
            )
        }
    val conversationRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealConversationRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = slockLogger("SlockConversationRepository", "shared_conversation_repository", SlockLogLevel.Warn)
            )
        }
    val agentsApiClient =
        remember(appShellNetworkClient) {
            SlockAgentsApiClient(
                transport = SlockAgentsNetworkApi(appShellNetworkClient),
                diagnosticsLogger = slockLogger("SlockAgentsApi", "app_shell_agents_api")
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
                logger = slockLogger("SlockCreateAgent", "app_shell_thread_create_agent")
            )
        }
    val savedRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealSavedRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = slockLogger("SlockSavedRepository", "shared_saved_repository", SlockLogLevel.Warn)
            )
        }
    val searchRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealSearchRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                warningLogger = slockLogger("SlockSearchRepository", "shared_search_repository", SlockLogLevel.Warn)
            )
        }
    val tasksDataSource =
        remember {
            SharedTasksDataSource(
                api = NetworkSlockTasksApi(appShellNetworkClient),
                diagnosticsLogger = slockLogger("SlockTasks", "shared_tasks")
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
    val notificationPreferenceEvents = remember(eventBus) { KuiklyNotificationPreferenceEvents(eventBus) }
    val appUpdateEvents = remember(eventBus) { KuiklyAppUpdateEvents(eventBus) }
    val realtimeEvents = remember(eventBus) { KuiklyRealtimeEvents(eventBus) }
    val hostNetworkEvents = remember(eventBus) { KuiklyHostNetworkEvents(eventBus) }
    val notificationPreferenceRepository =
        remember(notificationPreferenceEvents) {
            kuiklyNotificationPreferenceRepository(notificationPreferenceEvents)
        }
    val notificationPreferenceScope = rememberCoroutineScope()
    val homeActivityRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealInboxRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = slockLogger("SlockHomeActivity", "shared_home_activity"),
                warningLogger = slockLogger("SlockHomeActivity", "shared_home_activity_warning", SlockLogLevel.Warn)
            )
        }
    val homeSidebarOrderRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealSettingsRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = slockLogger("SlockHomeSidebarOrder", "shared_home_sidebar_order"),
                warningLogger = slockLogger("SlockHomeSidebarOrder", "shared_home_sidebar_order_warning", SlockLogLevel.Warn)
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
                diagnosticsLogger = slockLogger("SlockSharedHome", "shared_home"),
                loadSidebarOrder = { homeSidebarOrderRepository.getServerSidebarOrder() },
                updateSidebarOrder = { request -> homeSidebarOrderRepository.updateServerSidebarOrder(request) },
                loadServerOrder = { homeSidebarOrderRepository.getServerOrder() },
                updateServerOrder = { request -> homeSidebarOrderRepository.updateServerOrder(request) },
                markChannelReadAll = { channelId -> homeReadReceiptRepository.markChannelReadAll(channelId) },
                markChannelUnread = { channelId -> homeReadReceiptRepository.markChannelUnread(channelId) },
                archiveChannel = { channelId -> channelRepository.archiveChannel(channelId) },
                setChannelActivityMuted = { channelId, muted -> channelRepository.setChannelActivityMuted(channelId, muted) },
                pinUpdateFailureCallback = { message -> bridge.toast(message) },
            )
        }
    val homeActivityReplyThreadPrewarmDataSource =
        remember(
            shellAuthStore,
            appShellNetworkClient,
            conversationRepository,
            sharedStores.threadMessageStore,
            sharedStores.threadPayloadStore
        ) {
            SharedThreadDataSource(
                networkApi = NetworkSlockThreadApi(appShellNetworkClient),
                sessionStore = shellAuthStore,
                selectedServerStore = selectedServerStore,
                conversationRepository = conversationRepository,
                messageStore = sharedStores.threadMessageStore,
                payloadStore = sharedStores.threadPayloadStore,
                diagnosticsLogger = slockLogger("SlockHomeActivity", "shared_home_activity_thread_prewarm")
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
            homeStore,
            homeActivityReplyThreadPrewarmDataSource
        ) {
            sharedStores.getOrCreateHomeActivityStore(
                scope = storeScope,
                inboxRepository = homeActivityRepository,
                readReceiptRepository = homeReadReceiptRepository,
                conversationRepository = conversationRepository,
                memberRepository = memberRepository,
                prewarmReplyThread = homeActivityReplyThreadPrewarmDataSource::prewarmReplyThread,
                onUnreadCountsChanged = { homeStore.dispatch(SharedHomeIntent.ForegroundRefresh) },
                diagnosticsLogger = slockLogger("SlockHomeActivity", "shared_home_activity")
            )
        }
    val appShellComputersStore =
        remember(computersApiClient, appShellCoroutineScope) {
            SharedComputersStore(
                computersApiClient = computersApiClient,
                scope = appShellCoroutineScope,
                logger = slockLogger("SlockComputers", "app_shell")
            )
        }
    var notificationPreferenceSnapshot by remember {
        mutableStateOf(NotificationPreferenceSnapshot(enabled = true))
    }
    val settingsRepository =
        remember(shellAuthStore, appShellNetworkClient) {
            RealSettingsRepository(
                sessionStore = shellAuthStore,
                networkClient = appShellNetworkClient,
                logger = slockLogger("SlockSettingsRepository", "shared_settings_repository"),
                warningLogger = slockLogger("SlockSettingsRepository", "shared_settings_repository_warning", SlockLogLevel.Warn),
                channelRepository = channelRepository
            )
        }
    val settingsDataSource =
        remember(shellAuthStore, appShellComputersStore) {
            SharedSettingsDataSource(
                sessionStore = shellAuthStore,
                settingsRepository = settingsRepository,
                computersSource = appShellComputersStore,
                serverMembersStore = appShellServerMembersStore,
                notificationPreferenceRepository = notificationPreferenceRepository,
                localeRepository = localeRepository,
                chatLayoutPreferenceRepository = chatLayoutPreferenceRepository,
                recompositionProfilerPreferenceRepository = recompositionProfilerPreferenceRepository,
                apiEnvironmentStore = sharedStores.apiEnvironmentStore,
                platformCapabilities = settingsCapabilities,
                diagnosticsLogger = slockLogger("SlockSettings", "shared_settings")
            )
        }
    var myMessageAlignment by remember { mutableStateOf(MyMessageAlignmentPreference.Default) }
    var compactConsecutiveMessages by remember { mutableStateOf(false) }
    var foregroundMessageBannersEnabled by remember { mutableStateOf(false) }
    var activityTaskStatusBadgesEnabled by remember { mutableStateOf(false) }
    var threadRowAffordances by remember { mutableStateOf(ThreadRowAffordancePreference.Default) }
    var createChannelDialogOpen by remember { mutableStateOf(false) }
    var createChannelSubmitting by remember { mutableStateOf(false) }
    // task #226: global announcement modal. The API client already exists (the
    // dismiss is server-persisted per user); the shell fetches the latest active
    // announcement on session-ready and refetches on the announcement:new socket
    // event (RealtimeEventMapper maps it to RealtimeRefreshScope.Announcements,
    // which the home store deliberately ignores — the modal is the sole consumer).
    var activeAnnouncement by remember { mutableStateOf<AnnouncementItem?>(null) }
    val announcementsClient =
        remember(appShellNetworkClient) {
            SlockAnnouncementsRemindersApiClient(NetworkSlockAnnouncementsRemindersApi(appShellNetworkClient))
        }
    var createChannelError by remember { mutableStateOf<String?>(null) }
    var createChannelMembersLoading by remember { mutableStateOf(false) }
    var createChannelMembers by remember { mutableStateOf<List<MemberProfile>>(emptyList()) }
    var pendingDiagnosticsFeedback by remember { mutableStateOf<SettingsDiagnosticsFeedbackDraft?>(null) }
    var pendingDiagnosticsFeedbackReturnDestination by remember { mutableStateOf(SettingsDestinationDiagnostics) }
    var diagnosticsFeedbackUploadChannelId by remember { mutableStateOf<String?>(null) }
    val diagnosticsChooseChannelToast = stringResource(MR.strings.settings_diagnostics_feedback_choose_channel)
    val diagnosticsCannotAttachToast = stringResource(MR.strings.settings_diagnostics_feedback_cannot_attach)
    val diagnosticsPreparingToast = stringResource(MR.strings.settings_diagnostics_feedback_preparing)
    val diagnosticsUploadEmptyToast = stringResource(MR.strings.settings_diagnostics_feedback_upload_empty)
    val diagnosticsDraftReadyToast = stringResource(MR.strings.settings_diagnostics_feedback_draft_ready)
    val diagnosticsUploadFailedPrefix = stringResource(MR.strings.settings_diagnostics_feedback_upload_failed_prefix)
    val diagnosticsComposerSeed = stringResource(MR.strings.settings_diagnostics_feedback_composer_seed)
    val hostThreadTimeContext = remember(pager.pageData.params.toString()) {
        pager.pageData.params.threadTimeContext()
    }
    val notificationWindowNavigationIntent =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.appShellWindowNavigationIntent(
                fallbackServerId =
                    pager.pageData.params
                        .slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
                        .initialServerId
            )
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
                timeRangeBoundsProvider = { range -> hostThreadTimeContext.toSearchIsoBounds(range) },
                diagnosticsLogger = slockLogger("SlockHomeSearchSaved", "shared_home_search_saved")
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
                acceptHome = { intent ->
                    homeStore.accept(intent)
                    homeStore.awaitRealtimeSettled()
                },
                dispatchActivity = homeActivityStore::dispatch,
                acceptActivityRealtime = homeActivityStore::acceptRealtime,
                dispatchSearchSaved = homeSearchSavedViewModel::dispatch,
                resetActivity = homeActivityStore::reset,
                diagnosticsLogger = slockLogger("SlockHomeShell", "shared_home_shell"),
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
                authStore = shellAuthStore,
                dataSource = tasksDataSource,
                sharedTasksDataStore = sharedStores.tasksDataStore,
                diagnosticsLogger = slockLogger("SlockTasks", "shared_tasks")
            )
        }
    val tasksState by tasksViewModel.state.collectAsState()
    var tasksFirstRenderLoggedKey by remember { mutableStateOf<String?>(null) }
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "shared-settings") {
            SharedSettingsViewModel(
                settingsDataSource,
                diagnosticsLogger = slockLogger("SlockSettings", "shared_settings")
            )
        }
    val settingsState by settingsViewModel.state.collectAsState()
    var consumedDiagnosticsDeepLinkId by remember { mutableStateOf<String?>(null) }
    val readySession = (authGateState as? SharedAuthGateState.Ready)?.session
    val readyServerId = selectedServerId
    val renderedHomeServerId =
        (homeState.content as? SharedHomeContentState.Content)?.snapshot?.server?.id?.value
    val latestReadySession by rememberUpdatedState(readySession)
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
    // task #226: fetch the latest active announcement once the session is ready
    // (keyed by server so switching servers re-pulls that server's announcement).
    LaunchedEffect(announcementsClient, readyServerId) {
        if (readySession == null) {
            activeAnnouncement = null
            return@LaunchedEffect
        }
        activeAnnouncement =
            (announcementsClient.activeAnnouncements() as? DataResult.Content)?.data?.items?.firstOrNull()
    }
    val latestSelectedTab by rememberUpdatedState(selectedTab)
    val latestSelectedHomeDestination by rememberUpdatedState(selectedHomeDestination)
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
        remember(homeConversationDirectory, homeActivityState.snapshot) {
            val activityDirectory =
                homeActivityState.snapshot?.toSharedConversationDirectoryState()
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
    LaunchedEffect(appShellServerMembersStore, readyServerId, foregroundMessageBannersEnabled) {
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
                appShellCoroutineScope.launch {
                    homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.RefreshNotifications))
                }
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
    fun selectAppShellTab(nextTab: String, directChildSwitch: Boolean = false) {
        val retainedTab = retainedAppShellSelectedTab(nextTab)
        val nextEpoch = appShellNextChildTabSwitchEpoch(selectedTab, retainedTab, childTabSwitchEpoch)
        val direct = directChildSwitch && retainedTab in AppShellChildPageTabKeys
        bridge.log(
            "app_shell_select_tab next=$nextTab retained=$retainedTab from=$selectedTab " +
                "epoch=$childTabSwitchEpoch->$nextEpoch direct=$direct mode=single_child"
        )
        childTabSwitchEpoch = nextEpoch
        selectedTab = retainedTab
    }
    LaunchedEffect(diagnosticsDeepLinkRequest?.id, readyServerId) {
        val request = diagnosticsDeepLinkRequest ?: return@LaunchedEffect
        if (readyServerId == null || consumedDiagnosticsDeepLinkId == request.id) {
            return@LaunchedEffect
        }
        consumedDiagnosticsDeepLinkId = request.id
        selectAppShellTab("settings")
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
    val shellScope = rememberSlockAppShellScope(adaptivePane)
    val appShellModalHostState = rememberSlockUnifiedDialogHostState()
    val realtimeEventMapper =
        remember(sharedStores) {
            RealtimeEventMapper(
                messageShadowObserver = sharedStores.messageShadowObserver,
            )
        }
    var realtimeLastSeq by remember { mutableStateOf(0L) }
    var realtimeSessionKey by remember { mutableStateOf<String?>(null) }
    var realtimeConnectionFamilyId by remember { mutableStateOf<String?>(null) }
    var realtimeConnectedServerId by remember { mutableStateOf<String?>(null) }
    var pendingRealtimeAuthRefreshReason by remember { mutableStateOf<String?>(null) }
    var pendingRealtimeAuthRefreshPreviousFamilyId by remember { mutableStateOf<String?>(null) }
    var realtimeAuthorityConnectTick by remember { mutableStateOf(0) }
    var membersListRefreshKey by remember { mutableStateOf(0) }
    var membersAgentDetailRefreshKey by remember { mutableStateOf(0) }
    var membersListCachedContent by remember { mutableStateOf<SharedMembersUiState.Content?>(null) }
    var foregroundMessageBanner by remember { mutableStateOf<SlockForegroundMessageBanner?>(null) }
    val visibleRefreshGate = remember { AppShellVisibleRefreshGate() }

    fun refreshVisibleAppShellRoute(
        reason: String,
        signal: AppShellVisibleRefreshSignal,
        appearanceGeneration: Int
    ) {
        if (latestReadySession == null) return
        val refreshTarget =
            "${retainedAppShellSelectedTab(latestSelectedTab)}:${latestSelectedHomeDestination.orEmpty()}"
        if (!visibleRefreshGate.shouldRefresh(signal, appearanceGeneration, refreshTarget)) {
            bridge.log(
                "app_shell_visible_refresh_skip reason=$reason cause=same_appearance tab=${latestSelectedTab} " +
                    "homeDestination=${latestSelectedHomeDestination.orEmpty()}"
            )
            return
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
            "tasks" -> tasksViewModel.dispatch(SharedTasksRouteIntent.Refresh)
            "members" -> {
                membersListRefreshKey += 1
                membersAgentDetailRefreshKey += 1
            }
            "settings" -> settingsViewModel.dispatch(SharedSettingsIntent.Refresh)
        }
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
        realtimeLastSeq = 0L
        realtimeSessionKey = null
        realtimeConnectionFamilyId = null
        realtimeConnectedServerId = null
        pendingRealtimeAuthRefreshReason = null
        pendingRealtimeAuthRefreshPreviousFamilyId = null
        membersListRefreshKey = 0
        membersAgentDetailRefreshKey = 0
        membersListCachedContent = null
        foregroundMessageBanner = null
        sharedStores.homeSavedStateCache.clear()
        selectAppShellTab("home")
        retainedAppShellTabs = setOf("home")
        prewarmedAppShellTabs = emptySet()
        selectedSettingsDestination = SettingsDestinationHome
        homeListPositions = AppShellServerLazyListPositions()
        pendingHomeServerSwitchId = null
        observedHomeServerSwitchId = null
        tasksListPosition = AppShellLazyListPosition()
        membersListState = AppShellMembersListState()
        homeShellStore.reset()
        pendingDiagnosticsFeedback = null
        pendingDiagnosticsFeedbackReturnDestination = SettingsDestinationDiagnostics
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
        authGateMessage = AuthGateMessage.Static(MR.strings.auth_error_sign_in_continue.desc())
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
        foregroundMessageBannersEnabled = chatLayout.foregroundMessageBannersEnabled
        activityTaskStatusBadgesEnabled = chatLayout.activityTaskStatusBadgesEnabled
        threadRowAffordances = chatLayout.threadRowAffordances
    }
    val latestForegroundMessageBannersEnabled by rememberUpdatedState(foregroundMessageBannersEnabled)
    DisposableEffect(notificationPreferenceEvents) {
        val callbackRef =
            notificationPreferenceEvents.addPreferenceListener { snapshot ->
                notificationPreferenceScope.launch {
                    notificationPreferenceSnapshot = snapshot
                }
            }
        onDispose {
            notificationPreferenceEvents.removePreferenceListener(callbackRef)
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
            val localizedAuthGateMessage = authGateMessage.localizedMessage()
            LaunchedEffect(localizedAuthGateMessage) {
                pager.openLoginReplacingCurrentPage(localizedAuthGateMessage)
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

    LaunchedEffect(readyServerId, authGateState) {
        val serverId = readyServerId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        when (val request = sharedStores.authLossFunnel.consumePendingOpenRequest(serverId)) {
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
                            threadRowAffordances = threadRowAffordances,
                            anchorMessageId = it.anchorMessageId,
                            originFallback = it.originFallback,
                            onWarn = bridge::logWarn
                        )
                    }
                if (launchPayload == null) {
                    bridge.logWarn("app_shell_auth_pending_open_rejected reason=identity_unresolved serverId=$serverId")
                    bridge.toast("Conversation isn't ready yet — try again")
                } else {
                    pager.openStandaloneThreadLaunchPayload(launchPayload, navigationStoreScope)
                }
            }
            null -> Unit
        }
    }

    fun openHomeConversation(
        conversation: ThreadLaunchConversationIdentity,
        source: String,
        anchorMessageId: String? = null,
        pushCurrent: Boolean = true,
        originFallback: ThreadLaunchOriginFallback? = null
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
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(conversation.threadId.value))
        val launchPayload =
            buildStandaloneConversationThreadLaunchPayload(
                conversation = conversation,
                timeContext = threadTimeContext,
                alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                compactConsecutiveMessages = compactConsecutiveMessages,
                threadRowAffordances = threadRowAffordances,
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
        pager.openStandaloneThreadLaunchPayload(launchPayload, navigationStoreScope)
    }

    fun openHomeMessageRequest(request: HomeMessageOpenRequest, source: String, fallbackAnchorMessageId: String? = null) {
        openHomeConversation(
            conversation = request.conversationIdentity,
            source = source,
            anchorMessageId = request.anchorMessageId ?: fallbackAnchorMessageId,
            originFallback = request.originFallback
        )
    }

    // External sources only transport this typed identity envelope in pageData. The queue and
    // its consumed/opened markers live with the window's SlockShellStoreScope, so an AppShell
    // recreation or duplicate PendingIntent cannot replay the route.
    LaunchedEffect(
        notificationWindowNavigationIntent?.key,
        readySession?.currentServerId,
        readySession?.servers,
        homeState.content,
        appShellConversationDirectory,
        threadTimeContext,
        myMessageAlignment
    ) {
        notificationWindowNavigationIntent?.let(windowNavigationCoordinator::enqueue)
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
                    }
                    is SharedAuthResult.Failure -> {
                        windowNavigationCoordinator.rejectActivation(action.intent.key, result.code.name)
                            ?.let { rejection ->
                                bridge.logWarn("window_navigation_drop reason=${rejection.reason}")
                                bridge.toast("Notification server unavailable")
                            }
                    }
                    SharedAuthResult.Unauthenticated -> {
                        windowNavigationCoordinator.rejectActivation(action.intent.key, "unauthenticated")
                            ?.let { rejection ->
                                bridge.logWarn("window_navigation_drop reason=${rejection.reason}")
                                bridge.toast("Sign in to open this notification")
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
    LaunchedEffect(
        selectedServerId,
        realtimeAuthority?.witness?.connectionFamilyId,
        realtimeAuthority?.accessToken,
        realtimeAuthorityConnectTick,
    ) {
        val authority = realtimeAuthority
        val serverId = selectedServerId
        val accessToken = authority?.accessToken
        val familyId = authority?.witness?.connectionFamilyId
        val grant = if (authority != null && serverId != null) authority.grant(serverId) else null
        if (serverId.isNullOrBlank() || accessToken.isNullOrBlank() || familyId.isNullOrBlank() || grant == null) {
            realtimeConnectedServerId?.let { connectedServerId ->
                bridge.realtimeDisconnect(
                    reason = GeneratedRealtimeContract.DisconnectReason.ServerRemoved,
                    serverId = connectedServerId,
                    shellInstanceId = storeScope.shellInstanceId,
                    explicitClose = true,
                )
            }
            realtimeConnectionFamilyId = null
            realtimeConnectedServerId = null
            realtimeSessionKey = null
            return@LaunchedEffect
        }
        val nextSessionKey =
            "${authority.witness.principalId}|$serverId"
        val resumeSeq =
            if (realtimeSessionKey != null && realtimeSessionKey != nextSessionKey) {
                realtimeLastSeq = 0L
                0L
            } else {
                realtimeLastSeq
            }
        val forceReconnect =
            realtimeConnectionFamilyId != null && realtimeConnectionFamilyId != familyId
        realtimeSessionKey = nextSessionKey
        realtimeConnectionFamilyId = familyId
        realtimeConnectedServerId = serverId
        appShellAgentDetailStore.dispatch(SharedAgentDetailIntent.ResetAgentActivitySeq)
        bridge.realtimeConnect(
            serverId = serverId,
            accessToken = accessToken,
            shellInstanceId = storeScope.shellInstanceId,
            lastSeq = resumeSeq,
            forceReconnect = forceReconnect,
            authorityScopeLeaseId = authority.witness.scopeLeaseId,
            authorityPrincipalId = authority.witness.principalId,
            authorityAuthGeneration = authority.witness.authGeneration,
            authorityConnectionFamilyId = authority.witness.connectionFamilyId,
        )
        pendingRealtimeAuthRefreshReason?.let { reason ->
            if (
                pendingRealtimeAuthRefreshPreviousFamilyId == null ||
                pendingRealtimeAuthRefreshPreviousFamilyId != familyId
            ) {
                pendingRealtimeAuthRefreshReason = null
                pendingRealtimeAuthRefreshPreviousFamilyId = null
                bridge.realtimeAuthRefreshFinished(reason, success = true)
                bridge.log("shared_realtime_auth_refresh_success reason=$reason serverId=$serverId")
            }
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
            bridge.toast(diagnosticsChooseChannelToast)
            return
        }
        if (!conversation.joined || !conversation.permissions.canSend || !conversation.permissions.canAttach) {
            bridge.toast(diagnosticsCannotAttachToast)
            return
        }
        if (diagnosticsFeedbackUploadChannelId != null) {
            bridge.toast(diagnosticsPreparingToast)
            return
        }
        bridge.log(
            "diagnostics_feedback_channel_selected channelId=$channelId source=$source " +
                "fileName=${draft.fileName} pushCurrent=$pushCurrent"
        )
        diagnosticsFeedbackUploadChannelId = channelId
        bridge.toast(diagnosticsPreparingToast)
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
                                sizeBytes = attachment.sizeBytes,
                                mimeType = attachment.mimeType.takeIf { it.isNotBlank() },
                                status = "ready"
                            )
                        }
                    if (uploadedAttachments.isEmpty()) {
                        bridge.toast(diagnosticsUploadEmptyToast)
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
                            onWarn = { bridge.logWarn(it) },
                            threadRowAffordances = threadRowAffordances
                        )?.withDiagnosticsFeedbackDraft(
                            draft = draft,
                            composerSeedText = diagnosticsComposerSeed
                        ) ?: return@launch
                    composerDraftPersistSlice.stageUploadedAttachments(
                        draftKey = launchPayload.threadComposerDraftKey(),
                        attachments = uploadedAttachments
                    )
                    pager.openStandaloneThreadLaunchPayload(launchPayload, navigationStoreScope)
                    bridge.toast(diagnosticsDraftReadyToast)
                }
                is DataResult.Error -> {
                    val reason = threadFailureReasonLabel(uploadResult.error.code) ?: uploadResult.error.settingsUserFacingMessage()
                    bridge.toast("$diagnosticsUploadFailedPrefix $reason")
                    bridge.log("diagnostics_feedback_attachment_upload_failed code=${uploadResult.error.code}")
                }
                is DataResult.NoPermission -> {
                    bridge.toast(diagnosticsCannotAttachToast)
                    bridge.log("diagnostics_feedback_attachment_upload_blocked code=${uploadResult.blocker.code}")
                }
                is DataResult.Empty -> {
                    bridge.toast(diagnosticsUploadEmptyToast)
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
            selectAppShellTab("home")
            selectedSettingsDestination = SettingsDestinationHome
            homeShellStore.dispatch(SharedHomeShellIntent.CloseSecondaryDestination)
            return true
        }
        return false
    }

    fun openHomeSecondaryDestination(destination: String) {
        retainedHomeSecondaryDestination(destination)?.let { retainedDestination ->
            homeShellStore.dispatch(retainedDestination.toHomeSecondaryOpenIntent())
            pager.openHomeSecondaryPage(retainedDestination, threadTimeContext, navigationStoreScope)
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
        if (!trimmedName.isValidChannelName() || createChannelSubmitting) {
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
        val currentServerId = selectedServerStore.selectedServerId.value
        if (!selectedServerStore.isSelected(requestedServerId)) {
            bridge.log(
                "shared_realtime_auth_refresh_skip reason=$reason requestedServerId=$requestedServerId " +
                    "currentServerId=${currentServerId.orEmpty()}"
            )
            bridge.realtimeAuthRefreshFinished(reason, success = false)
            return
        }
        val previousFamilyId = realtimeConnectionFamilyId
        appShellCoroutineScope.launch {
            when (val result = authDataSource.refreshRealtimeAuth(reason)) {
                is SharedAuthResult.Success -> {
                    val refreshed = result.data
                    val refreshedServerId = selectedServerStore.selectedServerId.value
                    if (!selectedServerStore.isSelected(requestedServerId) || refreshed.accessToken.isBlank()) {
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
                    slockLogInfo(
                        "SlockSharedAuth",
                        "shared_realtime_auth_refresh_failed reason=$reason result=unauthenticated",
                        SlockPlatformLogHint.AuthCritical
                    )
                }
                is SharedAuthResult.Failure -> {
                    pendingRealtimeAuthRefreshReason = null
                    pendingRealtimeAuthRefreshPreviousFamilyId = null
                    bridge.realtimeAuthRefreshFinished(reason, success = false)
                    slockLogInfo(
                        "SlockSharedAuth",
                        "shared_realtime_auth_refresh_failed reason=$reason code=${result.code}",
                        SlockPlatformLogHint.AuthCritical
                    )
                }
            }
        }
    }
    val latestRealtimeAuthRefreshHandler by rememberUpdatedState<(JSONObject) -> Unit>(
        { eventData -> handleRealtimeAuthRefreshRequest(eventData) }
    )
    val latestResetAppShellSessionState by rememberUpdatedState<() -> Unit>({ resetAppShellSessionState() })
    DisposableEffect(
        appShellRealtimeDomainEventOwner,
        homeShellStore,
        settingsViewModel,
        bridge
    ) {
        val sinks =
            RealtimeGuardedDomainEventSinks(
                applyHomeShell = { serverId, event ->
                    homeShellStore.acceptRealtimeEvent(serverId, event)
                },
                applyHomeUnreadProjection = { serverId, event ->
                    homeShellStore.acceptBackgroundServerUnreadEvent(serverId, event)
                },
                applySettings = { serverId, event ->
                    settingsViewModel.accept(SharedSettingsIntent.ApplyRealtimeEvent(serverId, event))
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
                },
                applyAgentActivity = { event ->
                    appShellAgentDetailStore.dispatch(
                        SharedAgentDetailIntent.ApplyAgentActivityUpdate(event)
                    )
                    appShellServerMembersStore.applyPresence(event)
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
                log = slockLogger("SlockRealtimeDomain")
            )
        appShellRealtimeDomainEventOwner.registerGuardedSinks(sinks)
        onDispose { appShellRealtimeDomainEventOwner.unregisterGuardedSinks(sinks) }
    }

    DisposableEffect(hostNetworkEvents) {
        val callbackRef =
            hostNetworkEvents.addListener { signal ->
                appShellCoroutineScope.launch {
                    hostNetworkStatus =
                        if (signal.online) {
                            SlockAppShellNetworkStatus.Online
                        } else {
                            SlockAppShellNetworkStatus.Offline
                        }
                }
            }
        onDispose { hostNetworkEvents.removeListener(callbackRef) }
    }

    DisposableEffect(
        realtimeEvents,
        homeStore,
        realtimeEventMapper,
        settingsViewModel,
        realtimeCallbackGate,
    ) {
        val callbackRef =
            realtimeEvents.addListener { rawEventData ->
                val eventDataSnapshot = rawEventData.toString()
                val appearanceGenerationAtDelivery = visibleAppearanceGeneration
                appShellCoroutineScope.launch {
                    val eventData = JSONObject(eventDataSnapshot)
                    if (
                        eventData.optString(SlockRealtimeNotifyContract.KeyAction) ==
                        SlockRealtimeNotifyContract.ActionAppLifecycle
                    ) {
                        val event = eventData.optString(SlockRealtimeNotifyContract.KeyLifecycleEvent)
                        if (!appLifecycleEventTargetsShell(
                                eventShellInstanceId = eventData.optString(SlockRealtimeNotifyContract.KeyShellInstanceId),
                                routeShellInstanceId = storeScope.shellInstanceId
                            )
                        ) {
                            return@launch
                        }
                        if (eventData.optString(SlockRealtimeNotifyContract.KeyPageName) != SLOCK_APP_SHELL_PAGE) {
                            return@launch
                        }
                        if (
                            event == SlockRealtimeNotifyContract.LifecycleHiddenPause ||
                            event == SlockRealtimeNotifyContract.LifecycleHidden ||
                            event == SlockRealtimeNotifyContract.LifecycleHiddenKeepAlive
                        ) {
                            visibleRefreshGate.onLifecycleHidden(appearanceGenerationAtDelivery)
                            return@launch
                        }
                        if (
                            (
                                event == SlockRealtimeNotifyContract.LifecycleVisibleResume ||
                                    event == SlockRealtimeNotifyContract.LifecycleVisibleShow
                                ) &&
                            latestReadySession != null
                        ) {
                            refreshVisibleAppShellRoute(
                                reason = "app_lifecycle_$event",
                                signal = AppShellVisibleRefreshSignal.Lifecycle,
                                appearanceGeneration = appearanceGenerationAtDelivery
                            )
                            sharedStores.foregroundReconciler.onForeground("app_shell_$event")
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
                            slockLogger("SlockRealtimeDecode")
                        ) ?: return@launch
                    val serverId = resolveSharedShellRealtimeServerId(eventData, event, latestReadySession) ?: return@launch
                    val witness = eventData.realtimeConnectionWitnessOrNull() ?: return@launch
                    val grant = realtimeCallbackGate.resolveExistingGrant(witness, serverId) ?: return@launch
                    val activeSelectedServerId = selectedServerStore.selectedServerId.value
                    if (!shouldRouteRealtimeEventToShell(activeSelectedServerId, serverId)) {
                        if (shouldProjectBackgroundServerUnread(latestReadySession, activeSelectedServerId, serverId)) {
                            appShellRealtimeDomainEventOwner.acceptHomeUnreadProjection(
                                grant,
                                event,
                                source = "app_shell_background_server"
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
                                slockLogWarn(
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
                                slockLogWarn(
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
                    val accepted = appShellRealtimeDomainEventOwner.accept(grant, event, source = "app_shell")
                    if (!accepted) return@launch
                    sharedStores.realtimeAuthorityProvider.runIfCurrentGrant(grant) {
                        realtimeLastSeq = realtimeLastSeq.updateFrom(event)
                        if (event is RealtimeRefreshRequired && event.scope == RealtimeRefreshScope.Announcements) {
                            // task #226: announcement:new — refetch the latest active
                            // announcement so the modal pops live.
                            activeAnnouncement =
                                (announcementsClient.activeAnnouncements() as? DataResult.Content)?.data?.items?.firstOrNull()
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
                                    slockNotificationPushBannerOrNull(
                                    event = event,
                                    currentServerId = activeSelectedServerId,
                                    visibleConversationKey =
                                        appShellVisibleForegroundConversationKey(
                                            selectedTab = latestSelectedTab,
                                            selectedHomeDestination = latestSelectedHomeDestination,
                                            selectedConversationId = latestSelectedHomeConversationId
                                        ),
                                    conversationDirectory = latestAppShellConversationDirectory,
                                    onSuppressed = { reason ->
                                        bridge.log(
                                            "notification_push_banner_suppressed reason=$reason " +
                                                "serverId=${event.serverId.value} messageId=${event.messageId.value} kind=${event.kind.name}"
                                        )
                                    }
                                    )
                                banner?.let {
                                    bridge.log(
                                        "notification_push_banner_shown serverId=${event.serverId.value} " +
                                            "messageId=${event.messageId.value} kind=${event.kind.name}"
                                    )
                                    foregroundMessageBanner = it
                                }
                            } else {
                                foregroundMessageBanner = null
                            }
                        }
                    }
                }
            }
        onDispose { realtimeEvents.removeListener(callbackRef) }
    }

    val showAppShellBottomTabs =
        appShellBottomTabsVisible(
            selectedTab = selectedTab,
            selectedHomeDestination = selectedHomeDestination,
            selectedSettingsDestination = selectedSettingsDestination
        )
    val homeTabLabel = stringResource(MR.strings.tab_home)
    val tasksTabLabel = stringResource(MR.strings.tab_tasks)
    val membersTabLabel = stringResource(MR.strings.tab_members)
    val settingsTabLabel = stringResource(MR.strings.tab_settings)
    val appShellBottomTabItems = remember(
        selectedTab,
        homeTabLabel,
        tasksTabLabel,
        membersTabLabel,
        settingsTabLabel
    ) {
        appShellBottomTabs(
            selectedKey = selectedTab,
            homeLabel = homeTabLabel,
            tasksLabel = tasksTabLabel,
            membersLabel = membersTabLabel,
            settingsLabel = settingsTabLabel
        )
    }
    val appShellBottomContentInset = shellScope.contentBottomInset(showAppShellBottomTabs)
    LaunchedEffect(foregroundMessageBanner?.id) {
        val bannerId = foregroundMessageBanner?.id ?: return@LaunchedEffect
        delay(slockForegroundBannerAutoDismissMs())
        if (foregroundMessageBanner?.id == bannerId) {
            foregroundMessageBanner = null
        }
    }

    val homeReadyForChildPagePrewarm =
        readyServerId != null && homeState.content is SharedHomeContentState.Content
    LaunchedEffect(homeReadyForChildPagePrewarm, childTabShellId) {
        if (!homeReadyForChildPagePrewarm || !slockPlatformPrewarmsAppShellChildPages()) {
            return@LaunchedEffect
        }
        delay(AppShellChildPagePrewarmInitialDelayMs)
        appShellChildPagePrewarmOrder(
            activeChildTab = appShellActiveChildPagerTab(selectedTab),
            retainedTabs = retainedAppShellTabs,
            prewarmedTabs = prewarmedAppShellTabs
        ).forEach { tab ->
            if (
                tab == appShellActiveChildPagerTab(selectedTab) ||
                tab in retainedAppShellTabs ||
                tab in prewarmedAppShellTabs
            ) {
                return@forEach
            }
            prewarmedAppShellTabs = prewarmedAppShellTabs + tab
            bridge.log(
                "app_shell_child_tab_prewarm tab=$tab selectedTab=$selectedTab " +
                    "retained=$retainedAppShellTabs prewarmed=$prewarmedAppShellTabs"
            )
            delay(AppShellChildPagePrewarmIntervalMs)
        }
    }

    val mainRouteContent: @Composable () -> Unit = {
        SlockAppShellSurface(
            shellScope = shellScope,
            bottomTabs = appShellBottomTabItems,
            onBottomTabSelected = onBottomTabSelected@{
                val nextTab = retainedAppShellSelectedTab(it)
                if (!shouldHandleAppShellTabSelection(selectedTab, nextTab)) return@onBottomTabSelected
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
                if (nextTab != "settings") {
                    selectedSettingsDestination = SettingsDestinationHome
                    pendingDiagnosticsFeedback = null
                    pendingDiagnosticsFeedbackReturnDestination = SettingsDestinationDiagnostics
                }
            },
            networkStatus = hostNetworkStatus,
            showBottomTabs = showAppShellBottomTabs,
            modalHostState = appShellModalHostState
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
                            timeContext = threadTimeContext,
                            onOpenTask = { task ->
                                pager.openStandaloneTaskThreadPage(
                                    task = task,
                                    serverId = readyServerId.orEmpty(),
                                    conversationDirectory = appShellConversationDirectory,
                                    timeContext = threadTimeContext,
                                    alignOwnMessagesRight = myMessageAlignment == MyMessageAlignmentPreference.Right,
                                    compactConsecutiveMessages = compactConsecutiveMessages,
                                    threadRowAffordances = threadRowAffordances,
                                    storeScope = navigationStoreScope
                                )
                            }
                        )
                    }
                    val homeTabContent: @Composable (active: Boolean) -> Unit = { active ->
                        val homeListServerId =
                            appShellHomeListServerId(
                                renderedServerId = renderedHomeServerId,
                                readyServerId = readyServerId
                            )
                        val homeListPositionServerId = readyServerId ?: homeListServerId
                        val prefersChineseCommunity =
                            localePreferenceStore.readLocaleSync() == AppLocale.SimplifiedChinese
                        PerformanceSceneEffect(bridge, "kuikly_home", active = active)
                        SharedHomeScreen(
                                bridge = bridge,
                                state = homeState,
                                active = active,
                                sessionReady = readyServerId?.isNotBlank() == true,
                                draftPersistSlice = composerDraftPersistSlice,
                                conversationIdentityProvider = sharedStores.conversationIdentityProvider(storeScope),
                                activityUnreadCount = homeActivityState.totalUnreadCount,
                                activityTotalCount = homeActivityState.totalCount,
                                savedCount = homeSearchSavedState.saved.totalCount,
                                prefersChineseCommunity = prefersChineseCommunity,
                                onIntent = { intent ->
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
                                                update.detail.takeIf { it.isNotBlank() }?.let(bridge::log)
                                                homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.RefreshNotifications))
                                            }
                                            is DataResult.Empty ->
                                                bridge.toast("App update is unavailable")
                                            is DataResult.Error ->
                                                bridge.toast(result.error.settingsUserFacingMessage())
                                            is DataResult.NoPermission ->
                                                bridge.toast(result.blocker.settingsUserFacingMessage())
                                        }
                                    }
                                },
                                externalFocusLossDismissEpoch = homeFocusLossDismissEpoch,
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
                                onOpenComputers = {
                                    selectAppShellTab("settings")
                                    retainedAppShellTabs = retainedAppShellTabs + "settings"
                                    selectedSettingsDestination = SettingsDestinationComputers
                                },
                                onOpenServerSelection = {
                                    pager.openAuthChooseServerPage(
                                        storeScope = navigationStoreScope
                                    )
                                },
                                onCreateChannel = { openCreateChannelDialog() },
                                onOpenConversation = { conversationIdentity ->
                                    openHomeConversation(conversationIdentity, "home")
                                },
                                serverMembersStore = appShellServerMembersStore,
                                channelRepository = channelRepository,
                                bottomContentPadding = appShellBottomContentInset,
                                listPositionServerId = homeListPositionServerId,
                                initialListPosition =
                                    appShellLazyListPositionForServer(
                                        currentServerId = homeListPositionServerId,
                                        savedPositions = homeListPositions
                                    ),
                                onListPositionSnapshot = { serverId, position ->
                                    homeListPositions = homeListPositions.withPosition(serverId, position)
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
                    val membersTabContent: @Composable (active: Boolean) -> Unit = {
                        SharedMembersScreen(
                            bridge = bridge,
                            session = readySession,
                            memberRepository = memberRepository,
                            settingsRepository = settingsRepository,
                            createAgentCoordinator = createAgentCoordinator,
                            computersStore = appShellComputersStore,
                            agentDetailStore = appShellAgentDetailStore,
                            agentPanelTabOrderStore = sharedStores.agentPanelTabOrderStore,
                            serverMembersStore = appShellServerMembersStore,
                            membersRefreshKey = membersListRefreshKey,
                            initialContent =
                                sharedMembersCachedContentForServer(membersListCachedContent, readyServerId),
                            onContentLoaded = { membersListCachedContent = it },
                            onRetry = { membersListRefreshKey += 1 },
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
                            onOpenRouter = { jumpPage(pager, "router") },
                            onOpenReleaseNotes = {
                                val releaseNotesVersionCode =
                                    (settingsState.about as? ScreenLoadState.Content)
                                        ?.data
                                        ?.buildLabel
                                        .orEmpty()
                                pager.openReleaseNotesPage(releaseNotesVersionCode, storeScope)
                            },
                            onOpenBilling = { pager.openSettingsBillingPage(storeScope) },
                            onOpenAdministration = { pager.openSettingsAdministrationPage(storeScope) },
                            onOpenConnectedApps = { pager.openSettingsMarketplacePage(storeScope) },
                            onMessageAlignmentSelected = {
                                myMessageAlignment = it
                            },
                            onCompactConsecutiveMessagesSelected = {
                                compactConsecutiveMessages = it
                            },
                            onForegroundMessageBannersSelected = {
                                foregroundMessageBannersEnabled = it
                                if (!it) {
                                    foregroundMessageBanner = null
                                }
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
                                initialRetainedTabs = retainedAppShellTabs,
                                initialRenderer = childTabRenderer,
                                initialModalHostState = appShellModalHostState
                            )
                            val replaced = AppShellChildTabRegistry.register(childTabShellId, entry)
                            AppShellChildTabRegistration(entry = entry, replacedEntryId = replaced?.debugId ?: 0)
                        }
                    val childTabEntry = childTabRegistration.entry
                    SideEffect {
                        childTabEntry.updateSelection(
                            selectedTab = currentChildTab,
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
                    AppShellTabs(
                        selectedTab = selectedTab,
                        childHostShellId = childTabShellId,
                        mountedTabs = retainedAppShellTabs + prewarmedAppShellTabs,
                        childTabSwitchEpoch = childTabSwitchEpoch,
                        log = { bridge.log(it) }
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
                activeAnnouncement?.let { announcement ->
                    SharedAnnouncementModal(
                        announcement = announcement,
                        onDismiss = {
                            activeAnnouncement = null
                            appShellCoroutineScope.launch {
                                announcementsClient.dismissAnnouncement(announcement.id)
                            }
                        },
                        onOpenUrl = { url -> settingsCapabilities.openUrl(url) }
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SlockForegroundBannerHost(
            banner = foregroundMessageBanner,
            topSafeArea = shellScope.topSafeArea,
            serverMembersStore = appShellServerMembersStore,
            currentServerStore = sharedStores.localDataStore,
            currentServerIdOverride = readySession?.currentServerId?.let(::ServerId),
            onBannerClick = { target ->
                foregroundMessageBanner = null
                openHomeMessageRequest(
                    request = target.openRequest,
                    source = "foreground_banner",
                    fallbackAnchorMessageId = target.focusedMessageId
                )
            },
            onBannerDismiss = {
                foregroundMessageBanner = null
            }
        ) {
            key("route-main") { mainRouteContent() }
        }
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
    onOpenRouter: () -> Unit,
    onOpenReleaseNotes: () -> Unit,
    onOpenBilling: () -> Unit,
    onOpenAdministration: () -> Unit,
    onOpenConnectedApps: () -> Unit,
    onMessageAlignmentSelected: (MyMessageAlignmentPreference) -> Unit,
    onCompactConsecutiveMessagesSelected: (Boolean) -> Unit,
    onForegroundMessageBannersSelected: (Boolean) -> Unit,
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
    val accountLinkScope = rememberCoroutineScope()

    LaunchedEffect(selectedDestination) {
        when (selectedDestination) {
            SettingsDestinationApiEnvironment ->
                onSettingsIntent(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Developer))
            SettingsDestinationReleaseNotes ->
                onSettingsIntent(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.About))
            SettingsDestinationOpenSourceLicenses ->
                onSettingsIntent(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.OpenSourceLicenses))
            SettingsDestinationComputers ->
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
            is SharedSettingsEffect.ApiEnvironmentChanged -> {
                settingsCapabilities.bridgeToast(effect.message)
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
                onDestinationChange(SettingsDestinationQuiverFeedback)
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
        currentLanguageRegionSnapshot?.translationDisplay?.selectedKey,
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
                when (key) {
                    SettingsDestinationRouter -> onOpenRouter()
                    SettingsDestinationReleaseNotes -> onOpenReleaseNotes()
                    SettingsDestinationBilling -> onOpenBilling()
                    // Administration is the WEB settings page in an in-app
                    // WebView (own pager, Billing pattern) — not a native
                    // child destination.
                    SettingsDestinationAdministration -> onOpenAdministration()
                    SettingsDestinationConnectedApps -> onOpenConnectedApps()
                    else -> onDestinationChange(key)
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
                SettingsDestinationQuiverFeedback -> onDestinationChange(SettingsDestinationHome)
                SettingsDestinationApiEnvironment -> onDestinationChange(SettingsDestinationDeveloper)
                SettingsDestinationOpenSourceLicenses -> onDestinationChange(SettingsDestinationAbout)
                else -> onDestinationChange(SettingsDestinationHome)
            }
        },
        trailingContent = null
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
                                accountLinkScope.launch {
                                    when (settingsCapabilities.startMobileOAuthAccountLink(providerId)) {
                                        is DataResult.Content -> Unit
                                        else ->
                                            onSettingsIntent(
                                                SharedSettingsIntent.ConnectAccountProvider(
                                                    AccountProviderLinkStartRequest(providerId = providerId)
                                                )
                                            )
                                    }
                                }
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
                SettingsComputersWebViewContent(
                    session = session,
                    webBaseUrl = settingsState.developer.configuredWebBaseUrlOrNull(),
                    bottomContentPadding = bottomContentPadding
                )
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
                    mobileState = settingsState.mobile,
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
                    onLocaleChange = { localeTag ->
                        onSettingsIntent(SharedSettingsIntent.SetLocale(localeTag))
                    },
                    onSaveLanguage = {
                        currentLanguageRegionSnapshot?.let { snapshot ->
                            val selectedMode = languageRegionDisplayDraft.ifBlank { snapshot.translationDisplay.selectedKey }
                            if (selectedMode != snapshot.translationDisplay.selectedKey) {
                                onSettingsIntent(SharedSettingsIntent.SetTranslationMode(selectedMode))
                            }
                            buildSettingsLanguageRegionPreferenceUpdateRequest(
                                snapshot = snapshot,
                                languageDraft = languageRegionLanguageDraft,
                                displayDraft = languageRegionDisplayDraft
                            )?.let { request ->
                                onSettingsIntent(
                                    SharedSettingsIntent.UpdateLanguageRegionPreference(request)
                                )
                            }
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
                    onRetry = { onSettingsIntent(SharedSettingsIntent.Refresh) }
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
                    onRetry = { onSettingsIntent(SharedSettingsIntent.Refresh) }
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
                            onOpenAdministration()
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
                    showProfileCard = false,
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
                        onSettingsIntent(SharedSettingsIntent.Refresh)
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
                    foregroundMessageBannersBusy = settingsState.actionState?.action == MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS && settingsState.actionState.inProgress,
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
                    onForegroundMessageBannersChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetForegroundMessageBanners(enabled))
                        onForegroundMessageBannersSelected(enabled)
                    },
                    onServerMuteChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetServerMute(enabled))
                    },
                    onPermission = { onSettingsIntent(SharedSettingsIntent.RequestNotificationPermission) },
                    onSettings = { onSettingsIntent(SharedSettingsIntent.OpenNotificationSettings) },
                    onTest = { onSettingsIntent(SharedSettingsIntent.SendTestNotification) },
                    onRetry = { onSettingsIntent(SharedSettingsIntent.Refresh) }
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
                    onLongMessageCollapseChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetLongMessageCollapseEnabled(enabled))
                    },
                    onImageAnnotationChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetImageAnnotationEnabled(enabled))
                    },
                    onActivityTaskStatusBadgesChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetActivityTaskStatusBadges(enabled))
                        onActivityTaskStatusBadgesSelected(enabled)
                    },
                    onComposerSendKeyChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetComposerSendKey(enabled))
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
                    developerState = settingsState.developer,
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
                    onPrefetchReleaseNotes = { url ->
                        settingsCapabilities.prefetchReleaseNotes(url)
                    },
                    onOpenSourceLicenses = { onDestinationChange(SettingsDestinationOpenSourceLicenses) },
                    onCopyEnvironmentInfo = { onSettingsIntent(SharedSettingsIntent.CopyDiagnostics) },
                    onOpenApiEnvironment = { onDestinationChange(SettingsDestinationApiEnvironment) },
                    onOpenDiagnostics = { onDestinationChange(SettingsDestinationDiagnostics) }
                )
            SettingsDestinationOpenSourceLicenses ->
                SharedSettingsOpenSourceLicensesContent(
                    state = settingsState.openSourceLicenses,
                    bottomContentPadding = bottomContentPadding,
                    onOpenUrl = { url -> settingsCapabilities.openUrl(url) }
                )
            SettingsDestinationReleaseNotes -> {
                val aboutSnapshot =
                    when (val about = settingsState.about) {
                        is ScreenLoadState.Content -> about.data
                        else -> appShellAboutFallback(session)
                    }
                SharedSettingsReleaseNotesContent(
                    versionCode = aboutSnapshot.buildLabel,
                    bottomContentPadding = bottomContentPadding
                )
            }
            SettingsDestinationDeveloper ->
                SharedSettingsDeveloperContent(
                    state = settingsState.developer,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onOpenApiEnvironment = { onDestinationChange(SettingsDestinationApiEnvironment) },
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
            SettingsDestinationQuiverFeedback ->
                SharedQuiverFeedbackContent(
                    settingsCapabilities = settingsCapabilities,
                    accountId = session?.user?.id.orEmpty(),
                    bottomContentPadding = bottomContentPadding
                )
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
    showProfileCard: Boolean = true,
    onCreateJoinLink: (SettingsServerJoinLinkCreateRequest) -> Unit = {},
    onAction: (String, String?) -> Unit
) {
    val visualFullHeight = settingsVisualFullHeightEnabled()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showProfileCard) {
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
                            actionState = actionState,
                            timeContext = timeContext,
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
        if (visualFullHeight) {
            item { SettingsVisualFullHeightSentinel() }
        }
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
    val visualFullHeight = settingsVisualFullHeightEnabled()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsFormSectionCard(iconName = SlockLucideIconName.LoaderCircle, title = stringResource(MR.strings.common_loading)) {
                        SettingsFormDetailText(stringResource(MR.strings.settings_connected_apps_loading))
                    }
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsFormSectionCard(iconName = SlockLucideIconName.Blocks, title = stringResource(MR.strings.settings_connected_apps_empty)) {
                        SettingsFormEmptyText(stringResource(MR.strings.settings_connected_apps_unavailable))
                        SlockBrutalActionButton(
                            label = stringResource(MR.strings.common_retry),
                            onClick = onRefresh,
                            leadingIcon = SlockLucideIconName.RefreshCw,
                            backgroundColor = SlockColor.SurfaceWhite
                        )
                    }
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsFormSectionCard(iconName = SlockLucideIconName.TriangleAlert, title = stringResource(MR.strings.settings_connected_apps_error)) {
                        SettingsFormErrorText(state.error.settingsUserFacingMessage())
                        SlockBrutalActionButton(
                            label = stringResource(MR.strings.common_retry),
                            onClick = onRefresh,
                            leadingIcon = SlockLucideIconName.RefreshCw,
                            backgroundColor = SlockColor.SurfaceWhite
                        )
                    }
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsFormSectionCard(iconName = SlockLucideIconName.Lock, title = stringResource(MR.strings.settings_connected_apps_permission_required)) {
                        SettingsFormErrorText(settingsConnectedAppsPermissionCopy(state.blocker, stringResource(MR.strings.settings_connected_apps_permission_admin)))
                    }
                }
            is ScreenLoadState.Content -> {
                item {
                    SettingsConnectedAppsOverviewCard(
                        snapshot = snapshot,
                        loading = false,
                        onRegister = { onSelectedTabChange("myapps") }
                    )
                }
                item {
                    SettingsConnectedAppsTabControl(
                        selectedValue = selectedTab,
                        snapshot = snapshot,
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
                            SettingsConnectedAppsBuiltInBand(
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
        if (visualFullHeight) {
            item { SettingsVisualFullHeightSentinel() }
        }
    }
}

@Composable
private fun SettingsConnectedAppsOverviewCard(
    snapshot: SettingsConnectedAppsSnapshot?,
    loading: Boolean,
    onRegister: () -> Unit
) {
    SettingsFormSectionCard(iconName = SlockLucideIconName.Link2, title = stringResource(MR.strings.settings_connected_apps_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    stringResource(MR.strings.settings_connected_apps_overview_title),
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack
                )
                Text(
                    stringResource(MR.strings.settings_connected_apps_overview_detail),
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsConnectedAppsBadge(stringResource(MR.strings.settings_connected_apps_badge_experimental), SlockColor.BrandYellow)
                    if (loading) SettingsConnectedAppsBadge(stringResource(MR.strings.settings_connected_apps_badge_loading), SlockColor.EmptyStateGray)
                }
            }
            SlockBrutalActionButton(
                label = stringResource(MR.strings.settings_connected_apps_action_register),
                onClick = onRegister,
                leadingIcon = SlockLucideIconName.Plus,
                backgroundColor = SlockColor.AccentPink,
                minWidth = 118.dp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsConnectedAppsMetric(stringResource(MR.strings.settings_connected_apps_metric_marketplace), (snapshot?.builtIn.orEmpty().size + snapshot?.marketplace.orEmpty().size).toString(), Modifier.weight(1f))
            SettingsConnectedAppsMetric(stringResource(MR.strings.settings_connected_apps_metric_installed), snapshot?.installed.orEmpty().size.toString(), Modifier.weight(1f))
            SettingsConnectedAppsMetric(stringResource(MR.strings.settings_connected_apps_metric_my_apps), snapshot?.registered.orEmpty().size.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SettingsConnectedAppsTabControl(
    selectedValue: String,
    snapshot: SettingsConnectedAppsSnapshot?,
    onValueSelected: (String) -> Unit
) {
    SettingsSegmentedControlField(
        selectedValue = selectedValue,
        options =
            listOf(
                SegmentedControlOption(
                    value = "marketplace",
                    label = stringResource(MR.strings.settings_connected_apps_tab_marketplace),
                    iconName = SlockLucideIconName.Store,
                    count = snapshot?.let { it.builtIn.size + it.marketplace.size }
                ),
                SegmentedControlOption(
                    value = "installed",
                    label = stringResource(MR.strings.settings_connected_apps_tab_installed),
                    iconName = SlockLucideIconName.CircleCheck,
                    count = snapshot?.installed?.size
                ),
                SegmentedControlOption(
                    value = "myapps",
                    label = stringResource(MR.strings.settings_connected_apps_tab_my_apps),
                    iconName = SlockLucideIconName.KeyRound,
                    count = snapshot?.registered?.size
                )
            ),
        onValueSelected = onValueSelected
    )
}

@Composable
private fun SettingsConnectedAppsMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .background(SlockColor.SurfaceCream)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(value, style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 20.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        Text(label, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsConnectedAppsBadge(
    label: String,
    backgroundColor: Color
) {
    Box(
        modifier =
            Modifier
                .background(backgroundColor)
                .border(1.dp, SlockColor.BorderBlack)
                .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = SlockType.TabLabel.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsLanguageRegionContent(
    state: ScreenLoadState<SettingsLanguageRegionSnapshot>,
    mobileState: ScreenLoadState<SettingsMobileSnapshot>,
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
    onLocaleChange: (String) -> Unit,
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
                    SettingsLoadStateCard(
                        SlockLucideIconName.LoaderCircle,
                        stringResource(MR.strings.common_loading),
                        stringResource(MR.strings.settings_language_loading),
                        null,
                        onRetry
                    )
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsLoadStateCard(
                        SlockLucideIconName.Languages,
                        stringResource(MR.strings.settings_state_unavailable),
                        state.reason.settingsUserFacingMessage(),
                        stringResource(MR.strings.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsLoadStateCard(
                        SlockLucideIconName.TriangleAlert,
                        stringResource(MR.strings.settings_error_language),
                        state.error.settingsUserFacingMessage(),
                        stringResource(MR.strings.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(
                        SlockLucideIconName.Lock,
                        stringResource(MR.strings.settings_state_permission_required),
                        state.blocker.settingsUserFacingMessage(),
                        null,
                        onRetry
                    )
                }
            is ScreenLoadState.Content -> {
                val snapshot = state.data
                (mobileState as? ScreenLoadState.Content)?.data?.let { mobileSnapshot ->
                    item {
                        SettingsAppLanguageCard(
                            snapshot = mobileSnapshot,
                            actionState = actionState,
                            onLocaleChange = onLocaleChange
                        )
                    }
                }
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
    val selectedDisplay = displayDraft.ifBlank { currentDisplay }
    val languageDirty = selectedDisplay != "original" && languageDraft != snapshot.browserLanguage.selectedKey
    val displayDirty = selectedDisplay != currentDisplay
    val dirty = languageDirty || displayDirty
    val canSave =
        !busy &&
            dirty &&
            ((languageDirty && snapshot.browserLanguage.available) || displayDirty)
    val saveLabel = actionState.languageRegionSaveLabel(LANGUAGE_REGION_LANGUAGE_ACTIONS)
    SettingsFormSectionCard(iconName = SlockLucideIconName.Languages, title = stringResource(MR.strings.settings_language_title)) {
        SettingsSegmentedControlField(
            label = stringResource(MR.strings.settings_translation_mode),
            detail = stringResource(MR.strings.settings_translation_mode_detail),
            selectedValue = selectedDisplay,
            options = snapshot.translationDisplay.toTranslationModeOptions(),
            onValueSelected = onDisplayDraftChange,
            enabled = !busy
        )
        if (!snapshot.translationDisplay.available) {
            SettingsFormDetailText(snapshot.translationDisplay.detail)
        }
        if (selectedDisplay != "original") {
            SlockBrutalSectionLabel(
                label = stringResource(MR.strings.settings_preferred_translation_language_title)
            )
            SettingsFormDetailText(
                stringResource(MR.strings.settings_preferred_translation_language_detail)
            )
            SlockBrutalSelectField(
                options = snapshot.browserLanguage.toSelectOptions(),
                selectedId = languageDraft.ifBlank { snapshot.browserLanguage.selectedKey },
                selectedLabel = snapshot.browserLanguage.labelFor(languageDraft),
                onSelect = { onLanguageDraftChange(it.id) },
                enabled = snapshot.browserLanguage.available && !busy
            )
        }
        if (actionState?.let { it.action in setOf(LANGUAGE_REGION_ACTION_LANGUAGE, LANGUAGE_REGION_ACTION_AUTO_TRANSLATION, LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY) && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
        SettingsLanguageRegionSaveButton(
            label = saveLabel,
            enabled = canSave,
            onClick = onSubmit
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
    val canSave = dirty && !busy && (snapshot.timezone.available || snapshot.timeFormat.available)
    val saveLabel = actionState.languageRegionSaveLabel(LANGUAGE_REGION_TIME_ACTIONS)
    SettingsFormSectionCard(iconName = SlockLucideIconName.Clock, title = stringResource(MR.strings.settings_date_time_title)) {
        SlockBrutalSectionLabel(label = stringResource(MR.strings.settings_timezone))
        SettingsFormDetailText(stringResource(MR.strings.settings_timezone_detail))
        SlockBrutalSelectField(
            options = snapshot.timezone.toSelectOptions(),
            selectedId = timezoneDraft.ifBlank { snapshot.timezone.selectedKey },
            selectedLabel = snapshot.timezone.labelFor(timezoneDraft),
            onSelect = { onTimezoneDraftChange(it.id) },
            enabled = snapshot.timezone.available && !busy
        )
        SettingsSegmentedControlField(
            label = stringResource(MR.strings.settings_time_format),
            detail = stringResource(MR.strings.settings_time_format_detail),
            selectedValue = timeFormatDraft.ifBlank { snapshot.timeFormat.selectedKey },
            options =
                listOf(
                    SegmentedControlOption("12h", stringResource(MR.strings.settings_time_format_12_hour), SlockLucideIconName.Clock3),
                    SegmentedControlOption("24h", stringResource(MR.strings.settings_time_format_24_hour), SlockLucideIconName.Clock)
                ),
            onValueSelected = onTimeFormatDraftChange,
            enabled = snapshot.timeFormat.available && !busy
        )
        if (actionState?.let { it.action in setOf(LANGUAGE_REGION_ACTION_TIMEZONE, LANGUAGE_REGION_ACTION_TIME_FORMAT) && it.errorCode != null } == true) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
        SettingsLanguageRegionSaveButton(
            label = saveLabel,
            enabled = canSave,
            onClick = onSubmit
        )
    }
}

private val LANGUAGE_REGION_LANGUAGE_ACTIONS =
    setOf(LANGUAGE_REGION_ACTION_LANGUAGE, LANGUAGE_REGION_ACTION_TRANSLATION_DISPLAY, LANGUAGE_REGION_ACTION_AUTO_TRANSLATION)

private val LANGUAGE_REGION_TIME_ACTIONS =
    setOf(LANGUAGE_REGION_ACTION_TIMEZONE, LANGUAGE_REGION_ACTION_TIME_FORMAT)

internal fun buildSettingsLanguageRegionPreferenceUpdateRequest(
    snapshot: SettingsLanguageRegionSnapshot,
    languageDraft: String,
    displayDraft: String
): SettingsLanguageRegionPreferenceUpdateRequest? {
    val currentDisplay = snapshot.translationDisplay.selectedKey
    val nextDisplay = displayDraft.ifBlank { currentDisplay }
    val currentLanguage = snapshot.browserLanguage.selectedKey
    val nextLanguage = languageDraft.ifBlank { currentLanguage }
    val languageChanged = nextDisplay != "original" && nextLanguage != currentLanguage
    if (!languageChanged) return null
    return SettingsLanguageRegionPreferenceUpdateRequest(
        preferredLanguage = nextLanguage.takeIf { languageChanged && it != "other" },
        clearPreferredLanguage = languageChanged && nextLanguage == "other"
    )
}

private fun SettingsMobileSelectionSnapshot.toTranslationModeOptions(): List<SegmentedControlOption> =
    options.map { option ->
        SegmentedControlOption(
            value = option.key,
            label = option.label,
            iconName =
                when (option.key) {
                    "automatic" -> SlockLucideIconName.Languages
                    "manual" -> SlockLucideIconName.Pencil
                    else -> SlockLucideIconName.FileText
                }
        )
    }

@Composable
private fun build.raft.app.data.SharedSettingsActionState?.languageRegionSaveLabel(
    actions: Set<String>
): String =
    when {
        this == null || action !in actions -> stringResource(MR.strings.common_save)
        inProgress -> stringResource(MR.strings.settings_state_saving)
        errorCode != null -> stringResource(MR.strings.common_retry)
        else -> stringResource(MR.strings.settings_state_saved)
    }

@Composable
private fun SettingsLanguageRegionSaveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    SlockBrutalActionButton(
        label = label,
        onClick = onClick,
        enabled = enabled,
        leadingIcon = SlockLucideIconName.Save,
        backgroundColor = SlockColor.AccentPink,
        disabledBackgroundColor = slockDisabledActionBackgroundFor(SlockColor.AccentPink),
        disabledBorderColor = SlockDisabledActionBorder,
        disabledShadowColor = SlockDisabledActionShadow,
        disabledContentColor = SlockDisabledActionContent,
        disabledIconColor = SlockDisabledActionContentArgb,
        minWidth = 0.dp,
        minHeight = 34.dp,
        shadowOffset = 3.dp,
        textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextBlack)
    )
}

@Composable
private fun SettingsServerTranslationPreferenceCard(
    snapshot: SettingsLanguageRegionSnapshot,
    actionState: SharedSettingsActionState?,
    onServerTranslationChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == LANGUAGE_REGION_ACTION_SERVER_TRANSLATION && actionState.inProgress
    SettingsFormSectionCard(iconName = SlockLucideIconName.Wand, title = stringResource(MR.strings.settings_workspace_translation)) {
        SettingsSegmentedControlField(
            detail = snapshot.serverTranslation.detail,
            selectedValue = if (snapshot.serverTranslation.enabled) "on" else "off",
            options =
                listOf(
                    SegmentedControlOption("on", stringResource(MR.strings.settings_option_on), SlockLucideIconName.CircleCheck),
                    SegmentedControlOption("off", stringResource(MR.strings.settings_option_off), SlockLucideIconName.X)
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
                    SettingsLoadStateCard(
                        SlockLucideIconName.LoaderCircle,
                        stringResource(MR.strings.common_loading),
                        stringResource(MR.strings.settings_appearance_loading),
                        null,
                        onRetry
                    )
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsLoadStateCard(
                        SlockLucideIconName.Type,
                        stringResource(MR.strings.settings_state_unavailable),
                        state.reason.settingsUserFacingMessage(),
                        stringResource(MR.strings.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsLoadStateCard(
                        SlockLucideIconName.TriangleAlert,
                        stringResource(MR.strings.settings_error_appearance),
                        state.error.settingsUserFacingMessage(),
                        stringResource(MR.strings.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(
                        SlockLucideIconName.Lock,
                        stringResource(MR.strings.settings_state_permission_required),
                        state.blocker.settingsUserFacingMessage(),
                        null,
                        onRetry
                    )
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.Type, title = stringResource(MR.strings.settings_appearance_title)) {
        SettingsSegmentedControlField(
            detail = snapshot.messageBodyFontSize.detail,
            selectedValue = selected,
            options =
                snapshot.messageBodyFontSize.options.map {
                    SegmentedControlOption(it.key, it.label)
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
            stringResource(MR.strings.settings_appearance_preview),
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            SlockAvatar(avatarWireValue = "letter:AI", label = "AI", avatarSize = 28.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(MR.strings.settings_appearance_preview_sender), style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
                Text(
                    stringResource(MR.strings.settings_appearance_preview_message),
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
    onLongMessageCollapseChange: (Boolean) -> Unit,
    onImageAnnotationChange: (Boolean) -> Unit,
    onActivityTaskStatusBadgesChange: (Boolean) -> Unit,
    onComposerSendKeyChange: (Boolean) -> Unit,
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
                SettingsLongMessageCollapseCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onLongMessageCollapseChange = onLongMessageCollapseChange
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
                SettingsComposerSendKeyCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onComposerSendKeyChange = onComposerSendKeyChange
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
private fun SettingsAppLanguageCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onLocaleChange: (String) -> Unit
) {
    val preference = snapshot.localePreference
    val busy = actionState?.action == MOBILE_ACTION_LANGUAGE && actionState.inProgress
    SettingsFormSectionCard(
        iconName = SlockLucideIconName.Languages,
        title = stringResource(MR.strings.settings_app_language_title)
    ) {
        SettingsSegmentedControlField(
            detail = stringResource(MR.strings.settings_app_language_detail),
            selectedValue = preference.selectedKey.ifBlank { AppLocale.System.languageTag },
            options =
                listOf(
                    SegmentedControlOption(
                        AppLocale.System.languageTag,
                        stringResource(MR.strings.settings_app_language_system)
                    ),
                    SegmentedControlOption(
                        AppLocale.English.languageTag,
                        stringResource(MR.strings.settings_app_language_english)
                    ),
                    SegmentedControlOption(
                        AppLocale.SimplifiedChinese.languageTag,
                        stringResource(MR.strings.settings_app_language_simplified_chinese)
                    )
                ),
            onValueSelected = { selected -> selected?.let(onLocaleChange) },
            enabled = preference.available && !busy
        )
        if (actionState?.action == MOBILE_ACTION_LANGUAGE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.Columns3, title = stringResource(MR.strings.settings_message_alignment_title)) {
        SettingsSegmentedControlField(
            detail = stringResource(MR.strings.settings_message_alignment_detail),
            selectedValue = selectedKey.ifBlank { "left" },
            options =
                listOf(
                    SegmentedControlOption("left", stringResource(MR.strings.settings_message_alignment_left)),
                    SegmentedControlOption("right", stringResource(MR.strings.settings_message_alignment_right))
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.MessageSquare, title = stringResource(MR.strings.settings_mobile_compact_messages)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_mobile_compact_detail))
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
                    text = preference.localizedToggleStatus(),
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(MR.strings.settings_mobile_compact_enabled)
                        } else {
                            stringResource(MR.strings.settings_mobile_compact_disabled)
                        },
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
private fun SettingsLongMessageCollapseCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onLongMessageCollapseChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_LONG_MESSAGE_COLLAPSE && actionState.inProgress
    val preference = snapshot.longMessageCollapse
    SettingsFormSectionCard(iconName = SlockLucideIconName.ClipboardList, title = stringResource(MR.strings.settings_mobile_long_message_collapse)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_mobile_long_message_collapse_detail))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onLongMessageCollapseChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preference.localizedToggleStatus(),
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack
                )
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(MR.strings.settings_mobile_long_message_collapse_enabled)
                        } else {
                            stringResource(MR.strings.settings_mobile_long_message_collapse_disabled)
                        },
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
            SlockSwitchButton(
                checked = preference.enabled,
                enabled = preference.available && !busy
            )
        }
        if (actionState?.action == MOBILE_ACTION_LONG_MESSAGE_COLLAPSE && actionState.errorCode != null) {
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.ClipboardCheck, title = stringResource(MR.strings.settings_mobile_activity_task_badges)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_mobile_activity_task_badges_detail))
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
                    text = preference.localizedToggleStatus(),
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(MR.strings.settings_mobile_activity_task_badges_enabled)
                        } else {
                            stringResource(MR.strings.settings_mobile_activity_task_badges_disabled)
                        },
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
private fun SettingsComposerSendKeyCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onComposerSendKeyChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_COMPOSER_SEND_KEY && actionState.inProgress
    val preference = snapshot.composerSendKey
    SettingsFormSectionCard(iconName = SlockLucideIconName.Send, title = stringResource(MR.strings.settings_mobile_composer_key)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_mobile_composer_key_detail))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onComposerSendKeyChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(MR.strings.settings_mobile_composer_key_send)
                        } else {
                            stringResource(MR.strings.settings_mobile_composer_key_new_line)
                        },
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(MR.strings.settings_mobile_composer_key_enabled)
                        } else {
                            stringResource(MR.strings.settings_mobile_composer_key_disabled)
                        },
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
        if (actionState?.action == MOBILE_ACTION_COMPOSER_SEND_KEY && actionState.errorCode != null) {
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.SlidersHorizontal, title = stringResource(MR.strings.settings_mobile_message_row_actions)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_mobile_message_row_actions_detail))
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
                        text = affordance.settingsTitle(),
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

@Composable
private fun SettingsMobileToggleSnapshot.localizedToggleStatus(): String =
    if (enabled) {
        stringResource(MR.strings.settings_mobile_status_enabled)
    } else {
        stringResource(MR.strings.settings_state_off)
    }

@Composable
private fun ThreadRowAffordancePreference.settingsTitle(): String =
    when (this) {
        ThreadRowAffordancePreference.Reactions -> stringResource(MR.strings.settings_mobile_reactions)
        ThreadRowAffordancePreference.Saved -> stringResource(MR.strings.settings_mobile_saved)
    }

@Composable
private fun ThreadRowAffordancePreference.settingsDetail(enabled: Boolean): String =
    when (this) {
        ThreadRowAffordancePreference.Reactions ->
            if (enabled) {
                stringResource(MR.strings.settings_mobile_reactions_enabled)
            } else {
                stringResource(MR.strings.settings_mobile_reactions_disabled)
            }
        ThreadRowAffordancePreference.Saved ->
            if (enabled) {
                stringResource(MR.strings.settings_mobile_saved_enabled)
            } else {
                stringResource(MR.strings.settings_mobile_saved_disabled)
            }
    }

@Composable
private fun SettingsImageAnnotationCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onImageAnnotationChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_IMAGE_ANNOTATION && actionState.inProgress
    val preference = snapshot.imageAnnotation
    SettingsFormSectionCard(iconName = SlockLucideIconName.Image, title = stringResource(MR.strings.settings_mobile_image_annotation)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_mobile_image_annotation_detail))
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
                    text = preference.localizedToggleStatus(),
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(MR.strings.settings_mobile_image_annotation_enabled)
                        } else {
                            stringResource(MR.strings.settings_mobile_image_annotation_disabled)
                        },
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
    foregroundMessageBannersBusy: Boolean,
    onNotificationPreferenceChange: (Boolean) -> Unit,
    onForegroundKeepAlivePreferenceChange: (Boolean) -> Unit,
    onForegroundMessageBannersChange: (Boolean) -> Unit,
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
                    SettingsLoadStateCard(
                        SlockLucideIconName.LoaderCircle,
                        stringResource(MR.strings.common_loading),
                        stringResource(MR.strings.settings_notifications_loading),
                        null,
                        onRetry
                    )
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsLoadStateCard(
                        SlockLucideIconName.Bell,
                        stringResource(MR.strings.settings_state_unavailable),
                        state.reason.settingsUserFacingMessage(),
                        stringResource(MR.strings.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsLoadStateCard(
                        SlockLucideIconName.TriangleAlert,
                        stringResource(MR.strings.settings_error_notification),
                        state.error.settingsUserFacingMessage(),
                        stringResource(MR.strings.common_retry),
                        onRetry
                    )
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(
                        SlockLucideIconName.Lock,
                        stringResource(MR.strings.settings_state_permission_required),
                        state.blocker.settingsUserFacingMessage(),
                        null,
                        onRetry
                    )
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
                        foregroundMessageBannersBusy = foregroundMessageBannersBusy,
                        onNotificationPreferenceChange = onNotificationPreferenceChange,
                        onForegroundKeepAlivePreferenceChange = onForegroundKeepAlivePreferenceChange,
                        onForegroundMessageBannersChange = onForegroundMessageBannersChange,
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
        SettingsFormEmptyText(stringResource(MR.strings.settings_connected_apps_no_install_prompt))
    }
}

@Composable
private fun SettingsPushStatusCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    notificationPreferenceSnapshot: NotificationPreferenceSnapshot,
    preferenceBusy: Boolean,
    foregroundKeepAliveBusy: Boolean,
    foregroundMessageBannersBusy: Boolean,
    onNotificationPreferenceChange: (Boolean) -> Unit,
    onForegroundKeepAlivePreferenceChange: (Boolean) -> Unit,
    onForegroundMessageBannersChange: (Boolean) -> Unit,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onTest: () -> Unit
) {
    val anyActionBusy = actionState?.inProgress == true
    val permissionGranted = settingsNotificationPermissionGranted(snapshot.notificationPermission.statusLabel)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsNotificationSectionLabel(stringResource(MR.strings.settings_notifications_system))
        SettingsNotificationPermissionBanner(
            status = snapshot.notificationPermission.statusLabel,
            detail = snapshot.notificationPermission.detail,
            permissionGranted = permissionGranted,
            permissionAvailable = snapshot.notificationPermission.available,
            settingsAvailable = snapshot.notificationSettings.available,
            busy = anyActionBusy,
            onPermission = onPermission,
            onSettings = onSettings
        )
        if (actionState?.action in setOf(MOBILE_ACTION_PERMISSION, MOBILE_ACTION_SETTINGS) && actionState?.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode.orEmpty())
        }

        SettingsNotificationSectionLabel(stringResource(MR.strings.settings_notifications_on_device))
        SettingsNotificationPreferenceGroup {
            SettingsNotificationToggleRow(
                title = stringResource(MR.strings.settings_notifications_message_title),
                detail = stringResource(MR.strings.settings_notifications_message_detail),
                checked = notificationPreferenceSnapshot.enabled,
                enabled = !preferenceBusy,
                onCheckedChange = onNotificationPreferenceChange
            )
            SettingsNotificationDivider()
            SettingsNotificationToggleRow(
                title = stringResource(MR.strings.settings_notifications_background_realtime_title),
                detail = stringResource(MR.strings.settings_notifications_background_realtime_detail),
                checked = notificationPreferenceSnapshot.foregroundKeepAliveEnabled,
                enabled = !foregroundKeepAliveBusy,
                onCheckedChange = onForegroundKeepAlivePreferenceChange
            )
            SettingsNotificationDivider()
            SettingsNotificationToggleRow(
                title = stringResource(MR.strings.settings_foreground_banners_title),
                detail = snapshot.foregroundMessageBanners.detail,
                checked = snapshot.foregroundMessageBanners.enabled,
                enabled = snapshot.foregroundMessageBanners.available && !foregroundMessageBannersBusy,
                onCheckedChange = onForegroundMessageBannersChange
            )
            SettingsNotificationDivider()
            SettingsNotificationActionRow(
                title = stringResource(MR.strings.settings_notifications_test_title),
                detail = snapshot.testNotification.detail,
                status = snapshot.testNotification.statusLabel.ifBlank { stringResource(MR.strings.settings_state_ready) },
                enabled = snapshot.testNotification.available && snapshot.sendTestEnabled && !anyActionBusy,
                onClick = onTest
            )
            if (actionState?.action in setOf(
                    MOBILE_ACTION_NOTIFICATION_PREFERENCE,
                    MOBILE_ACTION_FOREGROUND_KEEP_ALIVE,
                    MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS
                ) && actionState?.errorCode != null
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    SettingsFormErrorText(actionState.errorCode.orEmpty())
                }
            }
            if (actionState?.action == MOBILE_ACTION_TEST_NOTIFICATION && actionState.errorCode != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    SettingsFormErrorText(actionState.errorCode.orEmpty())
                }
            }
        }
    }
}

internal fun settingsNotificationPermissionGranted(status: String): Boolean =
    status.trim().lowercase() in setOf("allowed", "granted", "enabled", "on")

@Composable
private fun SettingsNotificationSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
        color = SlockColor.TextMutedGray,
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsNotificationPermissionBanner(
    status: String,
    detail: String,
    permissionGranted: Boolean,
    permissionAvailable: Boolean,
    settingsAvailable: Boolean,
    busy: Boolean,
    onPermission: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (permissionGranted) SlockColor.AccentLime else SlockColor.BrandYellow)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = settingsAvailable && !busy, onClick = onSettings),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockLucideIcon(
                if (permissionGranted) SlockLucideIconName.ShieldCheck else SlockLucideIconName.Bell,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text =
                        if (permissionGranted) {
                            stringResource(MR.strings.settings_notifications_permission_allowed)
                        } else {
                            stringResource(MR.strings.settings_notifications_permission_needed)
                        },
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        detail.ifBlank {
                            status.ifBlank {
                                stringResource(MR.strings.settings_notifications_permission_open_settings)
                            }
                        },
                    style = SlockType.Caption.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SlockLucideIcon(SlockLucideIconName.ChevronRight, modifier = Modifier.size(18.dp))
        }
        if (!permissionGranted) {
            SlockBrutalActionButton(
                label = stringResource(MR.strings.settings_notifications_request_permission),
                onClick = onPermission,
                enabled = permissionAvailable && !busy,
                leadingIcon = SlockLucideIconName.Bell,
                backgroundColor = SlockColor.SurfaceWhite,
                minWidth = 0.dp,
                minHeight = 36.dp,
                shadowOffset = 0.dp
            )
        }
    }
}

@Composable
private fun SettingsNotificationPreferenceGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Thin, SlockColor.BorderBlack),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        content = content
    )
}

@Composable
private fun SettingsNotificationDivider() {
    Box(Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SlockColor.BorderBlack))
}

@Composable
private fun SettingsNotificationActionRow(
    title: String,
    detail: String,
    status: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = SlockType.Button.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SettingsFormDetailText(detail)
        }
        Text(
            text = status,
            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        SlockLucideIcon(SlockLucideIconName.Send, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsNotificationToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = SlockType.Button.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SettingsFormDetailText(detail)
        }
        SlockSwitchButton(checked = checked, enabled = enabled)
    }
}

@Composable
private fun SettingsServerMuteCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onServerMuteChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_SERVER_MUTE && actionState.inProgress
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsNotificationSectionLabel(stringResource(MR.strings.settings_notifications_workspace))
        SettingsNotificationPreferenceGroup {
            SettingsNotificationToggleRow(
                title = stringResource(MR.strings.settings_notifications_mute_workspace),
                detail = snapshot.muteServer.detail,
                checked = snapshot.muteServer.enabled,
                enabled = snapshot.muteServer.available && !busy,
                onCheckedChange = onServerMuteChange
            )
            if (actionState?.action == MOBILE_ACTION_SERVER_MUTE && actionState.errorCode != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    SettingsFormErrorText(actionState.errorCode)
                }
            }
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

private fun ScreenError.settingsUserFacingMessage(): String =
    userFacingMessage()

private fun PermissionBlocker.settingsUserFacingMessage(): String =
    userFacingMessage()

private fun EmptyReason.settingsUserFacingMessage(): String =
    userFacingMessage()

private fun settingsUserFacingErrorCodeMessage(code: String): String =
    ScreenError(code).settingsUserFacingMessage()

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
    SettingsFormSectionCard(iconName = SlockLucideIconName.KeyRound, title = stringResource(MR.strings.settings_connected_apps_client_secret_title)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_connected_apps_client_secret_detail))
        SlockBrutalReadOnlyField(label = stringResource(MR.strings.settings_connected_apps_label_client_id), value = secret.clientId)
        SlockBrutalReadOnlyField(
            label = stringResource(MR.strings.settings_connected_apps_label_client_secret),
            value = secret.clientSecret,
            maxLines = 2,
            textStyle = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
            textColor = SlockColor.TextBlack
        )
        SlockBrutalActionButton(
            label = stringResource(MR.strings.settings_connected_apps_action_copy_secret),
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
                SettingsFormDetailText(app.description ?: stringResource(MR.strings.settings_common_no_description))
                SettingsFormDetailText(if (app.installed) stringResource(MR.strings.settings_connected_apps_marketplace_installed_detail) else stringResource(MR.strings.settings_connected_apps_marketplace_available_detail))
            }
        }
        SettingsConnectedAppDetailField(stringResource(MR.strings.settings_connected_apps_label_publisher), app.developer)
        SettingsConnectedAppDetailField(stringResource(MR.strings.settings_connected_apps_label_category), app.category.settingsConnectedAppCategoryLabel(stringResource(MR.strings.settings_connected_apps_category_other)))
        SettingsConnectedAppDetailField(stringResource(MR.strings.settings_connected_apps_label_homepage), app.homepageDomain)
        SettingsConnectedAppDetailField(stringResource(MR.strings.settings_connected_apps_label_callback), app.callbackDomain)
        SettingsConnectedAppDetailField(stringResource(MR.strings.settings_connected_apps_label_data_access), app.dataAccessSummary)
        actionState.connectedAppsErrorFor(action, app.id)?.let { SettingsFormErrorText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            app.homepageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.common_open),
                    onClick = { onOpenUrl(url) },
                    leadingIcon = SlockLucideIconName.ExternalLink,
                    backgroundColor = SlockColor.SurfaceWhite
                )
            }
            SlockBrutalActionButton(
                label =
                    actionState.connectedAppsLocalizedStatusFor(
                        action,
                        if (app.installed) stringResource(MR.strings.settings_connected_apps_action_uninstall) else stringResource(MR.strings.settings_connected_apps_action_install),
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
                SettingsFormDetailText(app.description ?: stringResource(MR.strings.settings_common_no_description))
                SettingsFormDetailText(stringResource(MR.strings.settings_connected_apps_builtin_by_raft))
            }
        }
        SettingsConnectedAppDetailField(stringResource(MR.strings.settings_connected_apps_label_publisher), "Raft")
        SettingsConnectedAppDetailField(stringResource(MR.strings.settings_connected_apps_label_client_id), app.clientId)
        SettingsConnectedAppDetailField(stringResource(MR.strings.settings_connected_apps_label_homepage), app.homepageDomain)
        SettingsConnectedAppDetailField(stringResource(MR.strings.settings_connected_apps_label_agent_manifest), app.agentManifestUrl)
        app.homepageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            SlockBrutalActionButton(
                label = stringResource(MR.strings.common_open),
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
    val appName = client?.name ?: marketplaceApp?.name ?: installedApp?.name ?: stringResource(MR.strings.settings_connected_apps_this_app)
    val deleting = target.kind == SettingsConnectedAppsConfirmKind.DeleteClient
    val action = if (deleting) CONNECTED_APPS_ACTION_DELETE else CONNECTED_APPS_ACTION_UNINSTALL
    val busy = actionState.connectedAppsBusyFor(action, target.id)
    SlockUnifiedDialog(
        title = if (deleting) stringResource(MR.strings.settings_connected_apps_delete_title) else stringResource(MR.strings.settings_connected_apps_uninstall_title),
        onDismiss = onDismiss,
        dismissEnabled = !busy,
        maxWidth = 424.dp
    ) {
        SettingsFormDetailText(
            if (deleting) {
                MR.strings.settings_connected_apps_delete_detail.format(listOf(appName)).localized()
            } else {
                MR.strings.settings_connected_apps_uninstall_detail.format(listOf(appName)).localized()
            }
        )
        SettingsConnectedAppDetailField(
            label = stringResource(MR.strings.settings_connected_apps_label_client_id),
            value = client?.clientId ?: marketplaceApp?.clientId ?: installedApp?.clientId
        )
        actionState.connectedAppsErrorFor(action, target.id)?.let { SettingsFormErrorText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SlockBrutalActionButton(
                label = stringResource(MR.strings.common_cancel),
                onClick = onDismiss,
                enabled = !busy,
                leadingIcon = SlockLucideIconName.X,
                backgroundColor = SlockColor.SurfaceWhite
            )
            SlockBrutalActionButton(
                label = actionState.connectedAppsLocalizedStatusFor(action, if (deleting) stringResource(MR.strings.common_delete) else stringResource(MR.strings.settings_connected_apps_action_uninstall), target.id),
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
private fun SettingsConnectedAppsBuiltInBand(
    apps: List<SettingsConnectedAppBuiltInSnapshot>,
    onOpenDetail: (String) -> Unit
) {
    SettingsFormSectionCard(iconName = SlockLucideIconName.PackageCheck, title = stringResource(MR.strings.settings_connected_apps_builtin_title)) {
        Text(
            stringResource(MR.strings.settings_connected_apps_builtin_detail),
            style = SlockType.Button.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        if (apps.isEmpty()) {
            SettingsFormEmptyText(stringResource(MR.strings.settings_connected_apps_no_built_in))
        } else {
            apps.forEach { app ->
                SettingsConnectedAppActionRow(
                    title = app.name,
                    detail = listOfNotNull(stringResource(MR.strings.settings_connected_apps_builtin_item_detail), app.description, app.homepageDomain).joinToString(" · "),
                    buttonLabel = stringResource(MR.strings.common_open),
                    buttonIcon = SlockLucideIconName.ExternalLink,
                    buttonColor = SlockColor.AccentPink,
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
    val otherCategoryLabel = stringResource(MR.strings.settings_connected_apps_category_other)
    val categoryOptions = marketplace.settingsConnectedAppCategoryOptions(stringResource(MR.strings.settings_connected_apps_category_all), otherCategoryLabel)
    val resolvedCategory =
        category.takeIf { selected -> categoryOptions.any { it.id == selected } } ?: SETTINGS_CONNECTED_APPS_CATEGORY_ALL
    val filteredMarketplace = marketplace.settingsConnectedAppFilteredMarketplace(search, resolvedCategory)
    SettingsFormSectionCard(iconName = SlockLucideIconName.Store, title = stringResource(MR.strings.settings_connected_apps_tab_marketplace)) {
        SlockBrutalSearchField(
            value = search,
            placeholder = stringResource(MR.strings.settings_connected_apps_marketplace_search),
            onValueChange = onSearchChange
        )
        SlockRaftUiSelectField(
            options = categoryOptions,
            selectedId = resolvedCategory,
            onSelect = { onCategoryChange(it.id) },
            title = stringResource(MR.strings.settings_connected_apps_label_category),
            placeholder = stringResource(MR.strings.settings_connected_apps_category_all)
        )
        if (marketplace.isEmpty()) {
            SettingsFormEmptyText(stringResource(MR.strings.settings_connected_apps_no_marketplace))
        } else if (filteredMarketplace.isEmpty()) {
            SettingsFormEmptyText(stringResource(MR.strings.settings_connected_apps_no_marketplace_match))
        } else {
            filteredMarketplace.forEach { app ->
                SettingsConnectedAppActionRow(
                    title = app.name,
                    detail =
                        listOfNotNull(
                            app.category.settingsConnectedAppCategoryLabel(otherCategoryLabel),
                            app.developer,
                            app.dataAccessSummary,
                            if (app.installed) stringResource(MR.strings.settings_connected_apps_state_installed) else null
                        ).joinToString(" · "),
                    buttonLabel = stringResource(MR.strings.settings_connected_apps_action_details),
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.CircleCheck, title = stringResource(MR.strings.settings_connected_apps_installed_title)) {
        if (installed.isEmpty()) {
            SettingsFormEmptyText(stringResource(MR.strings.settings_connected_apps_no_installed_marketplace))
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
                            stringResource(MR.strings.common_edit)
                        } else {
                            actionState.connectedAppsLocalizedStatusFor(CONNECTED_APPS_ACTION_UNINSTALL, stringResource(MR.strings.settings_connected_apps_action_uninstall), app.id)
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
    val displayName = fallbackName.ifBlank { editingClient?.name.orEmpty().ifBlank { stringResource(MR.strings.settings_connected_apps_default_app) } }
    val seed = fallbackClientId.ifBlank { editingClient?.clientId.orEmpty().ifBlank { displayName } }
    SlockBrutalSectionLabel(label = stringResource(MR.strings.settings_connected_apps_label_logo))
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
                    stringResource(MR.strings.settings_connected_apps_logo_detail)
                } else {
                    stringResource(MR.strings.settings_connected_apps_logo_register_first)
                }
            )
            if (editingClient != null) {
                val client = editingClient
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlockBrutalActionButton(
                        label = actionState.connectedAppsLocalizedStatusFor(CONNECTED_APPS_ACTION_LOGO_UPLOAD, if (client.logoUrl.isNullOrBlank()) stringResource(MR.strings.settings_connected_apps_action_upload) else stringResource(MR.strings.settings_connected_apps_action_change), client.id),
                        onClick = onUploadLogo,
                        enabled = enabled && !busy,
                        leadingIcon = SlockLucideIconName.Upload,
                        backgroundColor = SlockColor.SurfaceWhite,
                        minWidth = 0.dp
                    )
                    if (!client.logoUrl.isNullOrBlank()) {
                        SlockBrutalActionButton(
                            label = actionState.connectedAppsLocalizedStatusFor(CONNECTED_APPS_ACTION_LOGO_CLEAR, stringResource(MR.strings.settings_connected_apps_action_reset), client.id),
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

private fun SettingsConnectedAppClientSnapshot.publishStatusCopy(privateLabel: String): String =
    publishStatus.trim()
        .takeIf { it.isNotBlank() }
        ?.replace("_", " ")
        ?.replaceFirstChar { it.uppercase() }
        ?: privateLabel

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
    SettingsFormSectionCard(iconName = SlockLucideIconName.KeyRound, title = if (editing) stringResource(MR.strings.settings_connected_apps_edit_title) else stringResource(MR.strings.settings_connected_apps_register_title)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_connected_apps_register_detail))
        SlockBrutalTextField(
            label = stringResource(MR.strings.settings_connected_apps_label_app_name),
            value = name,
            placeholder = stringResource(MR.strings.settings_connected_apps_placeholder_app_name),
            onValueChange = onNameChange,
            required = true,
            enabled = enabled
        )
        SlockBrutalTextField(
            label = stringResource(MR.strings.settings_connected_apps_label_client_id),
            value = clientId,
            placeholder = stringResource(MR.strings.settings_connected_apps_placeholder_client_id),
            onValueChange = onClientIdChange,
            optional = true,
            enabled = enabled && !editing
        )
        SlockBrutalTextField(
            label = stringResource(MR.strings.agent_create_label_description),
            value = description,
            placeholder = stringResource(MR.strings.settings_connected_apps_placeholder_description),
            onValueChange = onDescriptionChange,
            optional = true,
            enabled = enabled,
            maxLines = 3,
            fieldMinHeight = 78.dp
        )
        SlockBrutalTextField(
            label = stringResource(MR.strings.settings_connected_apps_label_homepage_url),
            value = homepageUrl,
            placeholder = "https://example.com",
            onValueChange = onHomepageUrlChange,
            optional = true,
            enabled = enabled
        )
        SlockBrutalTextField(
            label = stringResource(MR.strings.settings_connected_apps_label_return_url),
            value = returnUrl,
            placeholder = "https://example.com/oauth/callback",
            onValueChange = onReturnUrlChange,
            optional = true,
            enabled = enabled
        )
        SlockBrutalTextField(
            label = stringResource(MR.strings.settings_connected_apps_label_manifest_url),
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
                label = stringResource(MR.strings.settings_connected_apps_label_publish_category),
                value = publishCategory,
                placeholder = "productivity",
                onValueChange = onPublishCategoryChange,
                optional = true,
                enabled = enabled
            )
            SlockBrutalTextField(
                label = stringResource(MR.strings.settings_connected_apps_label_data_access_summary),
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
                    MR.strings.settings_connected_apps_publish_status.format(listOf(editingClient.publishStatusCopy(stringResource(MR.strings.settings_connected_apps_publish_private)))).localized(),
                    editingClient.publishRejectionReason?.takeIf { it.isNotBlank() }?.let { MR.strings.settings_connected_apps_publish_rejection.format(listOf(it)).localized() }
                ).joinToString(" ")
            )
        }
        if (!canManage) {
            SettingsFormErrorText(stringResource(MR.strings.settings_connected_apps_permission_manage))
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
                    actionState.connectedAppsLocalizedStatusFor(
                        if (editing) CONNECTED_APPS_ACTION_UPDATE else CONNECTED_APPS_ACTION_REGISTER,
                        if (editing) stringResource(MR.strings.settings_connected_apps_action_save_client) else stringResource(MR.strings.settings_connected_apps_action_register_client),
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
                    label = stringResource(MR.strings.common_cancel),
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.KeyRound, title = stringResource(MR.strings.settings_connected_apps_my_apps_title)) {
        if (clients.isEmpty()) {
            SettingsFormEmptyText(stringResource(MR.strings.settings_connected_apps_no_oauth_clients))
        } else {
            clients.forEach { app ->
                val canRequestPublish = app.canRequestMarketplacePublish()
                val publishBusy = actionState.connectedAppsBusyFor(CONNECTED_APPS_ACTION_PUBLISH, app.id)
                val deleteBusy = actionState.connectedAppsBusyFor(CONNECTED_APPS_ACTION_DELETE, app.id)
                SettingsConnectedAppActionRow(
                    title = app.name,
                    detail = listOfNotNull(app.clientId, app.publishStatus, app.description).joinToString(" · "),
                    buttonLabel = stringResource(MR.strings.common_edit),
                    buttonIcon = SlockLucideIconName.Pencil,
                    buttonColor = SlockColor.SurfaceWhite,
                    enabled = !publishBusy && !deleteBusy,
                    onClick = { onStartEdit(app) }
                )
                if (canRequestPublish) {
                    SettingsConnectedAppActionRow(
                        title = MR.strings.settings_connected_apps_publish_title.format(listOf(app.name)).localized(),
                        detail = app.publishRejectionReason ?: stringResource(MR.strings.settings_connected_apps_publish_detail),
                        buttonLabel = actionState.connectedAppsLocalizedStatusFor(CONNECTED_APPS_ACTION_PUBLISH, stringResource(MR.strings.settings_connected_apps_action_request_publish), app.id),
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
                            MR.strings.settings_connected_apps_publish_status.format(listOf(app.publishStatusCopy(stringResource(MR.strings.settings_connected_apps_publish_private)))).localized(),
                            app.publishRejectionReason?.takeIf { it.isNotBlank() }?.let { MR.strings.settings_connected_apps_publish_rejection.format(listOf(it)).localized() }
                        ).joinToString(" ")
                    )
                }
                SettingsConnectedAppActionRow(
                    title = MR.strings.settings_connected_apps_delete_app_title.format(listOf(app.name)).localized(),
                    detail = stringResource(MR.strings.settings_connected_apps_delete_app_detail),
                    buttonLabel = actionState.connectedAppsLocalizedStatusFor(CONNECTED_APPS_ACTION_DELETE, stringResource(MR.strings.common_delete), app.id),
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
        detail = detail.ifBlank { stringResource(MR.strings.settings_connected_apps_no_description) },
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
            Text(detail.ifBlank { stringResource(MR.strings.settings_connected_apps_no_description) }, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
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
    val status = actionState.serverLocalizedStatusFor(SERVER_ACTION_PROFILE_SAVE, if (canSave) stringResource(MR.strings.settings_server_action_save_profile) else stringResource(MR.strings.settings_server_state_read_only))
    SettingsFormSectionCard(iconName = SlockLucideIconName.Building2, title = stringResource(MR.strings.settings_profile_title)) {
        SlockBrutalTextField(
            label = stringResource(MR.strings.settings_server_label_name),
            value = if (canSave) draftName else currentName,
            placeholder = currentName.ifBlank { stringResource(MR.strings.settings_server_placeholder_name) },
            onValueChange = onNameChange,
            required = true,
            enabled = canSave && !busy
        )
        SlockBrutalReadOnlyField(
            label = stringResource(MR.strings.settings_server_label_slug),
            value = currentSlug.ifBlank { stringResource(MR.strings.settings_server_state_not_provided) }
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
                disabledBackgroundColor = slockDisabledActionBackgroundFor(SlockColor.AccentPink),
                disabledBorderColor = SlockDisabledActionBorder,
                disabledShadowColor = SlockDisabledActionShadow,
                disabledContentColor = SlockDisabledActionContent,
                disabledIconColor = SlockDisabledActionContentArgb,
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.TriangleAlert, title = stringResource(MR.strings.settings_section_danger_zone_title)) {
        if (canLeave) {
            SettingsServerActionRow(
                title = stringResource(MR.strings.settings_server_action_leave),
                detail = MR.strings.settings_server_leave_detail.format(listOf(serverName.ifBlank { stringResource(MR.strings.settings_server_this_server) })).localized(),
                buttonLabel = actionState.serverLocalizedStatusFor(SERVER_ACTION_LEAVE, stringResource(MR.strings.settings_server_action_leave)),
                buttonIcon = SlockLucideIconName.LogOut,
                buttonColor = SlockColor.AccentOrange,
                enabled = !leaveBusy,
                onClick = onLeave
            )
            if (leaveConfirmed) {
                Text(
                    stringResource(MR.strings.settings_server_leave_confirm),
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
        }
        if (canDelete) {
            SettingsServerActionRow(
                title = stringResource(MR.strings.settings_server_action_delete),
                detail = MR.strings.settings_server_delete_detail.format(listOf(serverName.ifBlank { stringResource(MR.strings.settings_server_this_server) })).localized(),
                buttonLabel = stringResource(MR.strings.settings_server_action_delete),
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
    val resolvedName = serverName.ifBlank { stringResource(MR.strings.settings_server_this_server) }
    val resolvedSlug = expectedServerSlug.ifBlank { "server-slug" }
    Modal(onWillDismiss = { if (!deleteBusy) onDismiss() }) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            SlockModalDismissBarrier(
                overlayAlpha = 0.58f,
                onDismiss = onDismiss,
                dismissEnabled = !deleteBusy
            )
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 18.dp)
                        .offset(x = (-10).dp, y = (-36).dp)
                        .widthIn(max = 360.dp)
                        .slockFloatingContentClickTarget()
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
                            stringResource(MR.strings.settings_delete_server),
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
                            stringResource(MR.strings.settings_server_delete_type_prefix) + " ",
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
                            stringResource(MR.strings.settings_server_delete_confirm_suffix),
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
                            label = stringResource(MR.strings.common_cancel),
                            onClick = onCancel,
                            enabled = !deleteBusy,
                            modifier = Modifier.widthIn(min = 72.dp),
                            backgroundColor = SlockColor.SurfaceWhite,
                            minWidth = 0.dp,
                            minHeight = 34.dp,
                            shadowOffset = 3.dp,
                            textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp)
                        )
                        SlockBrutalActionButton(
                            label = if (deleteBusy) stringResource(MR.strings.settings_server_action_deleting) else stringResource(MR.strings.settings_server_action_delete),
                            onClick = onDelete,
                            enabled = deleteEnabled,
                            modifier = Modifier.widthIn(min = 112.dp),
                            backgroundColor = SlockColor.AccentRed,
                            disabledBackgroundColor = slockDisabledActionBackgroundFor(SlockColor.AccentRed),
                            disabledBorderColor = SlockDisabledActionBorder,
                            disabledShadowColor = SlockDisabledActionShadow,
                            disabledContentColor = SlockDisabledActionContent,
                            disabledIconColor = SlockDisabledActionContentArgb,
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
    val fallbackName = stringResource(MR.strings.settings_server_this_server)
    val prefix = stringResource(MR.strings.settings_server_delete_warning_prefix)
    val suffix = stringResource(MR.strings.settings_server_delete_warning_suffix)
    val message = remember(serverName, text, fallbackName, prefix, suffix) {
        settingsServerDeleteWarningBannerText(
            serverName = serverName,
            text = text,
            fallbackName = fallbackName,
            prefix = prefix,
            suffix = suffix
        )
    }
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
            message,
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.weight(1f)
        )
    }
}

internal fun settingsServerDeleteWarningBannerText(
    serverName: String?,
    text: String? = null,
    fallbackName: String = "this server",
    prefix: String = "This will permanently delete ",
    suffix: String = " and all its data (agents, channels, messages). This cannot be undone."
): AnnotatedString {
    if (text != null) return AnnotatedString(text)
    val resolvedName = serverName ?: fallbackName
    return buildAnnotatedString {
        append(prefix)
        append(resolvedName)
        append(suffix)
        if (serverName != null) {
            addStyle(
                slockSpanStyleForWeight(FontWeight.Bold),
                start = prefix.length,
                end = prefix.length + resolvedName.length
            )
        }
    }
}

@Composable
private fun SettingsServerSlugInput(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    SlockSlugInput(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        enabled = enabled
    )
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
private fun SettingsSegmentedControlField(
    selectedValue: String,
    options: List<SegmentedControlOption>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    detail: String? = null,
    enabled: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.takeIf { it.isNotBlank() }?.let { SlockBrutalSectionLabel(label = it) }
        detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        SegmentedControl(
            selectedValue = selectedValue,
            options = options,
            onValueSelected = onValueSelected,
            enabled = enabled
        )
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
    var selectedCandidateId by remember(candidates, normalizedActorRole) {
        mutableStateOf(candidates.firstOrNull()?.id.orEmpty())
    }
    val selectedCandidate = candidates.firstOrNull { it.id == selectedCandidateId }
    val selectedRoleOptions = selectedCandidate
        ?.let { candidate -> settingsAdminRoleOptions(normalizedActorRole, candidate.normalizedRole().ifBlank { "member" }) }
        .orEmpty()
    var selectedRole by remember(selectedCandidateId, normalizedActorRole, selectedRoleOptions) {
        mutableStateOf(selectedRoleOptions.firstOrNull() ?: "admin")
    }
    LaunchedEffect(candidates, selectedCandidateId) {
        if (selectedCandidateId.isBlank() || candidates.none { it.id == selectedCandidateId }) {
            selectedCandidateId = candidates.firstOrNull()?.id.orEmpty()
        }
    }
    LaunchedEffect(selectedRoleOptions, selectedRole) {
        if (selectedRole !in selectedRoleOptions) {
            selectedRole = selectedRoleOptions.firstOrNull() ?: "admin"
        }
    }
    SettingsFormSectionCard(iconName = SlockLucideIconName.Shield, title = stringResource(MR.strings.settings_server_section_owners_admins)) {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        if (candidates.isNotEmpty()) {
            Text(
                stringResource(MR.strings.settings_server_action_add_admin),
                style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                SlockBrutalSelectField(
                    options =
                        candidates.map { candidate ->
                            SlockBrutalSelectOption(
                                id = candidate.id,
                                label =
                                    listOfNotNull(
                                        candidate.name.takeIf { it.isNotBlank() },
                                        candidate.detail?.takeIf { it.isNotBlank() }
                                    ).joinToString(" · ").ifBlank { stringResource(MR.strings.settings_server_state_unknown_member) }
                            )
                        },
                    selectedId = selectedCandidateId,
                    selectedLabel =
                        selectedCandidate
                            ?.let { candidate ->
                                listOfNotNull(
                                    candidate.name.takeIf { it.isNotBlank() },
                                    candidate.detail?.takeIf { it.isNotBlank() }
                                ).joinToString(" · ")
                            }
                            .orEmpty(),
                    placeholder = stringResource(MR.strings.settings_server_placeholder_select_member),
                    enabled = actionState?.inProgress != true,
                    onSelect = { option -> selectedCandidateId = option.id }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SlockBrutalSelectField(
                        options =
                            listOf(
                                SlockBrutalSelectOption(
                                    id = "owner",
                                    label = stringResource(MR.strings.settings_server_role_owner),
                                    enabled = "owner" in selectedRoleOptions
                                ),
                                SlockBrutalSelectOption(
                                    id = "admin",
                                    label = stringResource(MR.strings.settings_server_role_admin),
                                    enabled = "admin" in selectedRoleOptions
                                )
                            ),
                        selectedId = selectedRole,
                        selectedLabel = selectedRole.replaceFirstChar { it.uppercase() },
                        placeholder = stringResource(MR.strings.settings_server_label_role),
                        enabled = selectedCandidate != null && selectedRoleOptions.isNotEmpty() && actionState?.inProgress != true,
                        modifier = Modifier.weight(1f),
                        onSelect = { option -> selectedRole = option.id }
                    )
                    SlockBrutalActionButton(
                        label =
                            selectedCandidate
                                ?.let { actionState.serverLocalizedStatusFor(SERVER_ACTION_MEMBER_ROLE, stringResource(MR.strings.settings_server_action_update_role), it.id) }
                                ?: stringResource(MR.strings.settings_server_action_update_role),
                        onClick = {
                            val candidateId = selectedCandidate?.id ?: return@SlockBrutalActionButton
                            onAction(SERVER_ACTION_MEMBER_ROLE, "$candidateId:$selectedRole")
                        },
                        enabled =
                            selectedCandidate != null &&
                                selectedRole in selectedRoleOptions &&
                                actionState?.inProgress != true,
                        backgroundColor = SlockColor.AccentPink,
                        minWidth = 112.dp
                    )
                }
            }
            SettingsFormDivider()
        }
        if (admins.isEmpty()) {
            SettingsFormEmptyText(stringResource(MR.strings.settings_admin_no_owners))
        } else {
            admins.forEach { admin ->
                val targetRole = admin.normalizedRole()
                val canDemote = canChangeServerMemberRole(normalizedActorRole, targetRole, "member") && !(targetRole == "owner" && ownerCount <= 1)
                val fallback = if (targetRole == "owner") stringResource(MR.strings.settings_server_action_remove_owner) else stringResource(MR.strings.settings_server_action_remove_admin)
                val disabledLabel =
                    if (targetRole == "owner" && ownerCount <= 1) {
                        stringResource(MR.strings.settings_server_state_last_owner)
                    } else {
                        stringResource(MR.strings.settings_server_state_owner_only)
                    }
                SettingsServerActionRow(
                    title = admin.name,
                    detail = listOfNotNull(admin.detail, admin.role).joinToString(" · ").ifBlank { admin.role ?: stringResource(MR.strings.settings_server_role_admin) },
                    buttonLabel = if (canDemote) actionState.serverLocalizedStatusFor(SERVER_ACTION_MEMBER_ROLE, fallback, admin.id) else disabledLabel,
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
    actionState: build.raft.app.data.SharedSettingsActionState?,
    timeContext: SlockTimeContext?,
    onAction: (String, String?) -> Unit
) {
    SettingsFormSectionCard(iconName = SlockLucideIconName.MailPlus, title = stringResource(MR.strings.settings_section_pending_invites_title)) {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        if (invites.isEmpty()) {
            SettingsFormEmptyText(stringResource(MR.strings.settings_admin_no_pending_invites))
        } else {
            invites.forEach { invite ->
                SettingsServerActionRow(
                    title = invite.email,
                    detail =
                        listOfNotNull(
                            invite.inviterName?.let { MR.strings.settings_server_invited_by.format(listOf(it)).localized() },
                            formatSettingsTimestampLabel(invite.createdAtLabel, timeContext)
                        ).joinToString(" · "),
                    buttonLabel = actionState.serverLocalizedStatusFor(SERVER_ACTION_INVITE_REVOKE, stringResource(MR.strings.settings_server_action_revoke), invite.id),
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.Link2, title = stringResource(MR.strings.settings_section_join_links_title)) {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        SlockBrutalTextField(
            label = stringResource(MR.strings.settings_server_label_max_uses),
            value = maxUses,
            placeholder = stringResource(MR.strings.settings_server_placeholder_unlimited),
            onValueChange = onMaxUsesChange,
            optional = true,
            enabled = !createBusy
        )
        SlockBrutalTextField(
            label = stringResource(MR.strings.settings_server_label_expires_at),
            value = expiresAt,
            placeholder = "2026-06-01T09:00:00Z",
            onValueChange = onExpiresAtChange,
            optional = true,
            enabled = !createBusy
        )
        if (maxUsesInvalid) {
            SettingsFormErrorText(stringResource(MR.strings.settings_server_error_max_uses))
        }
        SlockBrutalActionButton(
            label = actionState.serverLocalizedStatusFor(SERVER_ACTION_JOIN_LINK_CREATE, stringResource(MR.strings.settings_server_action_create_join_link)),
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
            SettingsFormEmptyText(stringResource(MR.strings.settings_admin_no_join_links))
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
                    buttonLabel = actionState.serverLocalizedStatusFor(SERVER_ACTION_JOIN_LINK_REVOKE, stringResource(MR.strings.settings_server_action_revoke), link.id),
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.FileCheck, title = stringResource(MR.strings.settings_section_pre_join_agreement_title)) {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        SettingsSegmentedControlField(
            selectedValue = if (enabled) "enabled" else "disabled",
            options = listOf(
                SegmentedControlOption("enabled", stringResource(MR.strings.settings_server_agreement_required), SlockLucideIconName.CircleCheck),
                SegmentedControlOption("disabled", stringResource(MR.strings.settings_server_agreement_disabled), SlockLucideIconName.X)
            ),
            onValueSelected = { onEnabledChange(it == "enabled") },
            enabled = canSave && !busy
        )
        SlockBrutalTextField(
            label = stringResource(MR.strings.settings_server_label_title),
            value = title,
            placeholder = stringResource(MR.strings.settings_server_placeholder_agreement_title),
            onValueChange = { onTitleChange(it.take(180)) },
            required = enabled,
            enabled = canSave && !busy
        )
        SlockBrutalTextField(
            label = stringResource(MR.strings.settings_server_label_body_markdown),
            value = bodyMarkdown,
            placeholder = stringResource(MR.strings.settings_server_placeholder_agreement_body),
            onValueChange = onBodyChange,
            required = enabled,
            enabled = canSave && !busy,
            maxLines = 7,
            fieldMinHeight = 142.dp
        )
        SettingsFormDetailText("${bodyMarkdown.length} / $PreJoinAgreementBodyMaxLength characters")
        if (titleTooLong) {
            SettingsFormErrorText(stringResource(MR.strings.settings_server_error_agreement_title))
        }
        if (bodyTooLong) {
            SettingsFormErrorText(stringResource(MR.strings.settings_server_error_agreement_body))
        }
        SettingsAgreementPreview(bodyMarkdown)
        if (actionState?.action == SERVER_ACTION_AGREEMENT_SAVE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
        if (canSave) {
            SlockBrutalActionButton(
                label = actionState.serverLocalizedStatusFor(SERVER_ACTION_AGREEMENT_SAVE, stringResource(MR.strings.settings_server_action_save_agreement)),
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
            stringResource(MR.strings.settings_server_label_preview),
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        if (bodyMarkdown.isBlank()) {
            SettingsFormEmptyText(stringResource(MR.strings.settings_server_empty_agreement_preview))
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.Bot, title = stringResource(MR.strings.settings_section_onboarding_title)) {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        Text(
            stringResource(MR.strings.settings_server_label_onboarding_agent),
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        SettingsServerSelectRow(
            title = stringResource(MR.strings.settings_server_onboarding_disabled),
            detail = stringResource(MR.strings.settings_server_onboarding_disabled_detail),
            selected = selectedAgentId.isBlank(),
            enabled = canSave && !busy,
            onClick = { onAgentSelected("") }
        )
        if (agents.isEmpty()) {
            SettingsFormEmptyText(stringResource(MR.strings.settings_admin_no_onboarding_agents))
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
            stringResource(MR.strings.settings_server_label_new_agent_greeting),
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        SettingsSegmentedControlField(
            selectedValue = if (greetingEnabled) "yes" else "no",
            options = listOf(
                SegmentedControlOption("yes", stringResource(MR.strings.common_yes), SlockLucideIconName.MessageCircle),
                SegmentedControlOption("no", stringResource(MR.strings.common_no), SlockLucideIconName.MessageCircleOff)
            ),
            onValueSelected = { onGreetingEnabledChange(it == "yes") },
            enabled = canSave && !busy
        )
        if (actionState?.action == SERVER_ACTION_ONBOARDING_SAVE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
        if (canSave) {
            SlockBrutalActionButton(
                label = actionState.serverLocalizedStatusFor(SERVER_ACTION_ONBOARDING_SAVE, stringResource(MR.strings.settings_server_action_save_onboarding)),
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
    SettingsFormSectionCard(iconName = SlockLucideIconName.UsersRound, title = stringResource(MR.strings.settings_section_member_permissions_title)) {
        section?.detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        SettingsSegmentedControlField(
            selectedValue = if (hideHumansFromMembers) "hidden" else "visible",
            options = listOf(
                SegmentedControlOption("hidden", stringResource(MR.strings.settings_server_option_hide_humans), SlockLucideIconName.EyeOff),
                SegmentedControlOption("visible", stringResource(MR.strings.settings_server_option_visible), SlockLucideIconName.Eye)
            ),
            onValueSelected = { onHideHumansFromMembersChange(it == "hidden") },
            enabled = canSave && !busy
        )
        SettingsFormDetailText(stringResource(MR.strings.settings_server_member_permissions_detail))
        if (actionState?.action == SERVER_ACTION_MEMBER_PERMISSIONS_SAVE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState.errorCode)
        }
        if (canSave) {
            SlockBrutalActionButton(
                label = actionState.serverLocalizedStatusFor(SERVER_ACTION_MEMBER_PERMISSIONS_SAVE, stringResource(MR.strings.settings_server_action_save_permissions)),
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
    Text(settingsUserFacingErrorCodeMessage(text), style = SlockType.Caption.copy(color = SlockColor.AccentRed), color = SlockColor.AccentRed)
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
                row.supportingDetail.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
internal fun SharedSettingsAboutContent(
    state: ScreenLoadState<SharedSettingsAboutSnapshot>,
    fallback: SharedSettingsAboutSnapshot,
    developerState: ScreenLoadState<SharedSettingsDeveloperSnapshot> = ScreenLoadState.Loading,
    updateCheck: SharedSettingsUpdateCheckSnapshot?,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onCopyBuild: (String) -> Unit,
    webUrl: String = SharedSettingsDefaultWebBaseUrl,
    onOpenSlock: (String) -> Unit,
    onCheckQuiverUpdate: () -> Unit,
    onPrefetchReleaseNotes: suspend (String) -> Unit = {},
    onOpenSourceLicenses: () -> Unit,
    onCopyEnvironmentInfo: () -> Unit,
    onOpenApiEnvironment: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val snapshot =
        when (state) {
            is ScreenLoadState.Content -> state.data
            else -> fallback
        }
    val releaseNotesVersionCode = releaseNotesSanitizeVersionCode(snapshot.buildLabel)
    val releaseNotesUrl =
        remember(releaseNotesVersionCode) {
            releaseNotesVersionCode.takeIf { it.isNotBlank() }?.let(::sharedSettingsReleaseNotesUrl).orEmpty()
        }
    LaunchedEffect(releaseNotesUrl) {
        releaseNotesUrl.takeIf { it.isNotBlank() }?.let { onPrefetchReleaseNotes(it) }
    }
    val aboutPayload = snapshot.toAboutPayload(webUrl, updateCheck, actionState, settingsAboutPayloadCopy(sharedSettingsResolvedWebBaseUrl(webUrl)))
    val developer = (developerState as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data
    val environmentStatus =
        when (developerState) {
            ScreenLoadState.Loading -> stringResource(MR.strings.settings_state_loading)
            is ScreenLoadState.Content -> developer?.environmentLabel.orEmpty().ifBlank { stringResource(MR.strings.settings_developer_state_current) }
            else -> stringResource(MR.strings.settings_state_unavailable)
        }
    val environmentDetail =
        developer?.apiBaseUrl.orEmpty().ifBlank {
            stringResource(MR.strings.settings_developer_environment_detail)
        }
    val payload =
        aboutPayload.copy(
            sections =
                aboutPayload.sections +
                    KuiklySharedSettingsSection(
                        title = stringResource(MR.strings.settings_developer_section_api_server),
                        rows =
                            listOf(
                                KuiklySharedSettingsRow(
                                    key = DEVELOPER_ACTION_API_ENVIRONMENT,
                                    iconName = SlockLucideIconName.Server,
                                    title = stringResource(MR.strings.settings_developer_api_environment),
                                    detail = environmentDetail,
                                    status = environmentStatus,
                                    enabled = developerState is ScreenLoadState.Content
                                ),
                                KuiklySharedSettingsRow(
                                    key = "diagnostics",
                                    iconName = SlockLucideIconName.ClipboardList,
                                    title = stringResource(MR.strings.settings_diagnostics_title),
                                    detail = stringResource(MR.strings.settings_developer_diagnostics_detail),
                                    status = stringResource(MR.strings.settings_state_open),
                                    enabled = true
                                )
                            )
                    )
        )
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding) { action, value ->
        when (action) {
            "copyBuild" -> onCopyBuild(value.orEmpty().ifBlank { snapshot.buildSummary() })
            "openSlock" -> onOpenSlock(value.orEmpty().ifBlank { webUrl })
            DEVELOPER_ACTION_QUIVER_UPDATE_CHECK -> onCheckQuiverUpdate()
            "openSourceLicenses" -> onOpenSourceLicenses()
            "copyEnvironmentInfo" -> onCopyEnvironmentInfo()
            DEVELOPER_ACTION_API_ENVIRONMENT -> onOpenApiEnvironment()
            "diagnostics" -> onOpenDiagnostics()
        }
    }
}

@Composable
private fun SharedSettingsOpenSourceLicensesContent(
    state: ScreenLoadState<SharedSettingsOpenSourceLicensesSnapshot>,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onOpenUrl: (String) -> Unit
) {
    val payload = state.toOpenSourceLicensesPayload(settingsOpenSourceLicensesPayloadCopy())
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding) { action, value ->
        when (action) {
            "openLicenseUrl", "openSourceUrl" ->
                value.orEmpty().takeIf { it.isNotBlank() }?.let(onOpenUrl)
        }
    }
}

/**
 * task #90: server-rendered, public release-notes page for the current
 * build, loaded full-screen in an in-app WebView. `allowNavigation`
 * stays on so the page's per-version download links keep working. On a
 * load failure we overlay a retry affordance that recreates the WebView
 * (bumping [reloadKey]) instead of inventing a new navigation mechanism.
 */
@Composable
private fun SharedSettingsReleaseNotesContent(
    versionCode: String,
    bottomContentPadding: Dp = SlockSpacing.Lg
) {
    if (versionCode.isBlank()) {
        Box(
            modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "加载中… / Loading…",
                style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        return
    }
    val url = remember(versionCode) { sharedSettingsReleaseNotesUrl(versionCode) }
    var reloadKey by remember { mutableStateOf(0) }
    var loadError by remember(reloadKey, url) { mutableStateOf<String?>(null) }
    // The native WebView platform view attaches ahead of the Kuikly-drawn page
    // header on the destination's first frame, so the page flashed as: full
    // white → web content → header last (task #227). Hold the WebView out of
    // composition for the first committed frame so frame one is the header
    // plus the white placeholder, and the WebView attaches strictly after.
    var webViewSlotReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { webViewSlotReady = true }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        // The native WebView applies its own bottom safe-area content inset, so
        // it stops short of the screen bottom and the Box's SurfaceWhite shows
        // through as a white strip. Extend the WebView frame below the slot by
        // the bottom safe-area height so that inset region falls off-screen and
        // the page fills edge-to-edge regardless of page content height.
        val webViewHeight = maxHeight + slockBottomSafeAreaPadding()
        if (webViewSlotReady) {
            key(reloadKey, url) {
                SlockWebView(
                    url = url,
                    modifier = Modifier.fillMaxWidth().height(webViewHeight),
                    allowNavigation = true,
                    onLoadSuccess = { loadError = null },
                    onLoadFailure = { reason -> loadError = reason }
                )
            }
        }
        if (loadError != null) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(SlockColor.SurfaceWhite)
                        .padding(16.dp)
                        .padding(bottom = bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(52.dp).background(SlockColor.BrandYellow).border(SlockStroke.Default, SlockColor.BorderBlack),
                    contentAlignment = com.tencent.kuikly.compose.ui.Alignment.Center
                ) {
                    SlockLucideIcon(SlockLucideIconName.TriangleAlert, modifier = Modifier.size(26.dp))
                }
                Text("Couldn't load release notes", style = SlockType.PageTitle.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
                Text("Check your connection and try again.", style = SlockType.Body.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
                SlockBrutalActionButton(
                    label = "重试 / Retry",
                    onClick = { reloadKey += 1 },
                    leadingIcon = SlockLucideIconName.RefreshCw,
                    backgroundColor = SlockColor.SurfaceWhite
                )
            }
        }
    }
}

internal fun sharedSettingsReleaseNotesUrl(versionCode: String): String {
    val localeTag =
        try {
            KeyValueLocalePreferenceStore(slockSharedPreferenceStore()).readLocaleSync().kmmResourceLocaleTag
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AppLocale.Default.kmmResourceLocaleTag
        }
    val baseUrl = "https://quiver.oranix.io/notes/${slockReleaseNotesAppSlug()}?version_code=$versionCode"
    return localeTag?.let { "$baseUrl&lang=$it" } ?: baseUrl
}

@Composable
internal fun SharedSettingsDeveloperContent(
    state: ScreenLoadState<SharedSettingsDeveloperSnapshot>,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onOpenApiEnvironment: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenRouter: () -> Unit
) {
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data
    val payload = snapshot.toDeveloperPayload(state, actionState, settingsDeveloperPayloadCopy())
    SharedSettingsSectionContent(payload, bottomContentPadding = bottomContentPadding) { action, value ->
        when (action) {
            DEVELOPER_ACTION_API_ENVIRONMENT -> onOpenApiEnvironment()
            "diagnostics" -> onOpenDiagnostics()
            "router" -> onOpenRouter()
        }
    }
}

@Composable
internal fun SharedSettingsApiEnvironmentContent(
    state: ScreenLoadState<SharedSettingsDeveloperSnapshot>,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onSaveApiEnvironment: (SharedSettingsApiEnvironmentRequest) -> Unit
) {
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDeveloperSnapshot>)?.data
    val focusManager = LocalFocusManager.current
    var selectedPresetKey by remember(snapshot?.environmentKey, snapshot?.options) {
        mutableStateOf(snapshot?.environmentKey?.takeIf { key -> snapshot.options.any { it.key == key } }.orEmpty())
    }
    var label by remember(snapshot?.environmentKey, snapshot?.environmentLabel) {
        mutableStateOf(snapshot?.environmentLabel.orEmpty())
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
    val customLabel = stringResource(MR.strings.settings_developer_custom)
    val requestLabel = matchingPreset?.label ?: label.trim().ifBlank { customLabel }
    val canSave = normalizedApi.startsWith("http") && normalizedSocket.startsWith("http") && !busy

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SettingsFormSectionCard(iconName = SlockLucideIconName.SlidersHorizontal, title = stringResource(MR.strings.settings_developer_presets)) {
                SettingsFormDetailText(stringResource(MR.strings.settings_developer_presets_detail))
                snapshot?.options.orEmpty().forEach { option ->
                    SettingsApiEnvironmentPresetRow(
                        option = option,
                        selected = selectedPresetKey == option.key,
                        enabled = !busy,
                        onSelect = {
                            focusManager.clearFocus()
                            selectedPresetKey = option.key
                            label = option.label
                            apiBaseUrl = option.apiBaseUrl
                            socketBaseUrl = option.socketBaseUrl
                            webUrl = option.webUrl
                        }
                    )
                }
                if (snapshot?.options.isNullOrEmpty()) {
                    SettingsFormEmptyText(stringResource(MR.strings.settings_developer_presets_empty))
                }
            }
        }
        item {
            SettingsFormSectionCard(iconName = SlockLucideIconName.Server, title = stringResource(MR.strings.settings_developer_environment_form)) {
                SettingsFormDetailText(stringResource(MR.strings.settings_developer_environment_save_detail))
                SlockBrutalTextField(
                    label = stringResource(MR.strings.settings_developer_label),
                    value = label,
                    placeholder = customLabel,
                    onValueChange = {
                        selectedPresetKey = ""
                        label = it
                    },
                    enabled = !busy
                )
                SlockBrutalTextField(
                    label = stringResource(MR.strings.settings_developer_api_base_url),
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
                    label = stringResource(MR.strings.settings_developer_socket_base_url),
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
                    label = stringResource(MR.strings.settings_developer_web_url),
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
                    label = if (busy) stringResource(MR.strings.settings_developer_action_saving) else stringResource(MR.strings.settings_developer_action_save_environment),
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
                    SettingsFormErrorText(stringResource(MR.strings.settings_developer_error_urls))
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
            text = if (selected) stringResource(MR.strings.settings_developer_state_current) else stringResource(MR.strings.settings_developer_state_preset),
            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
internal fun SharedSettingsDiagnosticsContent(
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
    val payload = (snapshot?.entries ?: emptyList()).toDiagnosticsPayload(state, actionState, settingsDiagnosticsPayloadCopy())
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
private fun SharedQuiverFeedbackContent(
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    accountId: String,
    bottomContentPadding: Dp = SlockSpacing.Lg
) {
    val copy = settingsQuiverFeedbackCopy()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val chatLayoutPreferenceStore =
        remember {
            KeyValueChatLayoutPreferenceStore(slockSharedPreferenceStore())
        }
    val feedbackDraftStore =
        remember {
            SettingsQuiverFeedbackDraftStore(slockSharedPreferenceStore())
        }
    val restoredDraft =
        remember(feedbackDraftStore, accountId) {
            feedbackDraftStore.read(accountId)
        }
    val composerSendKeyEnabled = chatLayoutPreferenceStore.readComposerSendKeyEnabledSync()
    var messageFieldValue by remember(accountId) {
        mutableStateOf(
            TextFieldValue(
                text = restoredDraft.text,
                selection = TextRange(restoredDraft.text.length)
            )
        )
    }
    var draftTextForPersistence by remember(accountId) { mutableStateOf(restoredDraft.text) }
    val latestDraftTextForPersistence by rememberUpdatedState(draftTextForPersistence)
    val message = messageFieldValue.text
    var attachments by remember(accountId) { mutableStateOf(restoredDraft.attachments) }
    val latestAttachmentsForPersistence by rememberUpdatedState(attachments)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingRetryRequest by remember { mutableStateOf<SettingsQuiverFeedbackRetryRequest?>(null) }
    var composerSyncNonce by remember { mutableStateOf(0) }
    var nextEntryId by remember { mutableStateOf(1) }
    var entries by remember {
        mutableStateOf(
            listOf(
                SettingsQuiverFeedbackEntry(
                    id = 0,
                    kind = SettingsQuiverFeedbackEntryKind.Quiver,
                    title = copy.title,
                    body = copy.introduction
                )
            )
        )
    }
    // task #92 close-out: server + Hands SDKs support 9 files with presigned
    // large-file upload; the per-file byte cap is platform-dependent (OHOS SDK
    // still caps at 50MB).
    val attachmentLimit = 9
    val attachmentMaxBytes = slockFeedbackAttachmentMaxBytes()
    val attachmentMaxLabel = "${attachmentMaxBytes / (1024L * 1024L)} MB"
    val remainingSlots = attachmentLimit - attachments.size
    val normalizedMessage = message.trim()

    LaunchedEffect(feedbackDraftStore, accountId, draftTextForPersistence, attachments) {
        delay(250L)
        feedbackDraftStore.save(accountId, draftTextForPersistence, attachments)
    }

    DisposableEffect(feedbackDraftStore, accountId) {
        onDispose {
            feedbackDraftStore.save(
                accountId = accountId,
                text = latestDraftTextForPersistence,
                attachments = latestAttachmentsForPersistence
            )
        }
    }

    LaunchedEffect(entries.size, busy) {
        if (entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    fun nextConversationEntryId(): Int {
        val id = nextEntryId
        nextEntryId += 1
        return id
    }

    fun pickAttachment(imageOnly: Boolean) {
        if (busy) return
        if (remainingSlots <= 0) {
            error = copy.attachmentLimitReached(attachmentLimit)
            return
        }
        busy = true
        error = null
        scope.launch {
            when (val result = settingsCapabilities.pickFeedbackAttachment(imageOnly)) {
                is DataResult.Content -> {
                    val accepted = result.data.filter { file -> (file.sizeBytes ?: 0L) <= attachmentMaxBytes }
                    val rejectedCount = result.data.size - accepted.size
                    val merged =
                        (attachments + accepted)
                            .distinctBy { it.fileRef ?: it.id }
                            .take(attachmentLimit)
                    attachments = merged
                    error = when {
                        rejectedCount > 0 -> copy.attachmentTooLarge(attachmentMaxLabel)
                        accepted.size > remainingSlots -> copy.attachmentLimitTrimmed(attachmentLimit)
                        else -> null
                    }
                }
                is DataResult.Empty -> Unit
                is DataResult.Error -> error = copy.attachmentPickerFailed
                is DataResult.NoPermission -> error = copy.attachmentPickerPermissionNeeded
            }
            busy = false
        }
    }

    fun submitFeedback(explicitRetryRequest: SettingsQuiverFeedbackRetryRequest? = null) {
        val currentAttachments = attachments.take(attachmentLimit)
        val retryRequest =
            explicitRetryRequest
                ?: pendingRetryRequest?.takeIf { request ->
                    request.matches(normalizedMessage, currentAttachments)
                }
        val submittedMessage = retryRequest?.message ?: normalizedMessage
        val submittedAttachments = retryRequest?.attachments ?: currentAttachments
        settingsCapabilities.log(
            "settings_quiver_feedback_submit_click busy=$busy " +
                "messageLength=${submittedMessage.length} retry=${retryRequest != null} " +
                "attachmentCount=${submittedAttachments.size}"
        )
        if (busy) {
            settingsCapabilities.log("settings_quiver_feedback_submit_ignored reason=busy")
            return
        }
        if (submittedMessage.isBlank()) {
            settingsCapabilities.log("settings_quiver_feedback_submit_blocked reason=blank_message")
            error = copy.messageRequired
            return
        }
        settingsCapabilities.log("settings_quiver_feedback_submit_enter")
        busy = true
        error = null
        pendingRetryRequest = null
        entries = entries.filterNot { it.retryRequest != null }
        attachments = submittedAttachments
        messageFieldValue = TextFieldValue("")
        composerSyncNonce += 1
        if (retryRequest == null) {
            entries =
                entries +
                    SettingsQuiverFeedbackEntry(
                        id = nextConversationEntryId(),
                        kind = SettingsQuiverFeedbackEntryKind.User,
                        title = copy.you,
                        body = submittedMessage,
                        detail =
                            submittedAttachments
                                .takeIf { it.isNotEmpty() }
                                ?.joinToString { it.fileName.ifBlank { copy.attachment } }
                    )
        }
        val submittedRequest = SettingsQuiverFeedbackRetryRequest(submittedMessage, submittedAttachments)

        fun appendFailure(failure: SettingsQuiverFeedbackFailurePresentation) {
            val nextRetryRequest = submittedRequest.takeIf { failure.retryable }
            pendingRetryRequest = nextRetryRequest
            messageFieldValue = TextFieldValue(submittedMessage, selection = TextRange(submittedMessage.length))
            draftTextForPersistence = submittedMessage
            composerSyncNonce += 1
            attachments = submittedAttachments
            error = failure.composerStatus
            entries =
                entries +
                    SettingsQuiverFeedbackEntry(
                        id = nextConversationEntryId(),
                        kind = SettingsQuiverFeedbackEntryKind.Error,
                        title = failure.title,
                        body = failure.body,
                        retryRequest = nextRetryRequest
                    )
        }

        scope.launch {
            try {
                when (val result = settingsCapabilities.submitQuiverFeedback(submittedMessage, submittedAttachments)) {
                    is DataResult.Content -> {
                        settingsCapabilities.log(
                            "settings_quiver_feedback_submit_result status=ok ticket=${result.data.ticketId.take(8)}"
                        )
                        pendingRetryRequest = null
                        entries =
                            entries +
                                result.data.toQuiverFeedbackEntry(
                                    id = nextConversationEntryId(),
                                    copy = copy
                                )
                        messageFieldValue = TextFieldValue("")
                        draftTextForPersistence = ""
                        feedbackDraftStore.clear(accountId)
                        attachments = emptyList()
                    }
                    is DataResult.Empty -> {
                        settingsCapabilities.log("settings_quiver_feedback_submit_result status=empty")
                        appendFailure(copy.submissionUnavailableFailure())
                    }
                    is DataResult.Error -> {
                        settingsCapabilities.log(
                            "settings_quiver_feedback_submit_result status=error code=${result.error.code}"
                        )
                        appendFailure(copy.submissionFailure(result.error.code, result.error.retryable))
                    }
                    is DataResult.NoPermission -> {
                        settingsCapabilities.log(
                            "settings_quiver_feedback_submit_result status=no_permission code=${result.blocker.code}"
                        )
                        appendFailure(copy.submissionPermissionFailure())
                    }
                }
            } catch (throwable: Throwable) {
                settingsCapabilities.log(
                        "settings_quiver_feedback_submit_exception error=${throwable::class.simpleName} " +
                        "message=${throwable.message.orEmpty()}"
                )
                appendFailure(copy.submissionFailure())
            } finally {
                settingsCapabilities.log("settings_quiver_feedback_submit_finished")
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            entries.forEach { entry ->
                item(key = entry.id) {
                    SettingsQuiverFeedbackConversationRow(
                        entry = entry,
                        copy = copy,
                        onCopy = { shareText ->
                            settingsCapabilities.bridgeCopyToClipboard(shareText)
                        },
                        onRetry = { request -> submitFeedback(request) }
                    )
                }
            }
            if (busy) {
                item {
                    SettingsQuiverFeedbackConversationRow(
                        entry =
                            SettingsQuiverFeedbackEntry(
                                id = -1,
                                kind = SettingsQuiverFeedbackEntryKind.Quiver,
                                title = copy.title,
                                body = copy.submitting
                            ),
                        copy = copy,
                        onCopy = {},
                        onRetry = {}
                    )
                }
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
        ) {
            ThreadComposerContent(
                surface =
                    ThreadComposerSurface(
                        kind = KuiklyThreadSurfaceKind.Channel,
                        channelId = "settings-feedback",
                        showTaskButton = false
                    ),
                state =
                    ThreadComposerUiState(
                        instanceId = "settings-quiver-feedback-composer",
                        seedText = message,
                        syncNonce = composerSyncNonce,
                        placeholder = copy.placeholder,
                        enabled = !busy,
                        attachmentActionsEnabled = remainingSlots > 0,
                        composerMode = if (busy) "sending" else if (!error.isNullOrBlank()) "failed" else "idle",
                        statusLabel = error?.takeIf { it.isNotBlank() },
                        suggestionsEnabled = false,
                        pendingAttachments = attachments.toFeedbackComposerAttachmentItems(copy)
                    ),
                composerSendKeyEnabled = composerSendKeyEnabled,
                onEvent = { action, value, _, attachmentId ->
                    when (action) {
                        "change" -> {
                            messageFieldValue = TextFieldValue(value.orEmpty())
                            draftTextForPersistence = value.orEmpty()
                            error = null
                        }
                        "send" -> {
                            settingsCapabilities.log("settings_quiver_feedback_composer_send")
                            submitFeedback()
                        }
                        "attachImage" -> pickAttachment(imageOnly = true)
                        "attachFile" -> pickAttachment(imageOnly = false)
                        "removeAttachment" ->
                            attachmentId?.let { id ->
                                attachments =
                                    attachments.filterNot { file ->
                                        file.feedbackComposerAttachmentId() == id
                                    }
                            }
                        "previewAttachment", "retryAttachments", "blur", "toggleAsTask" -> Unit
                    }
                },
                contentPaddingTop = 10.dp,
                contentPaddingBottom = 8.dp,
                hostPaddingHorizontal = SlockSpacing.Sm,
                hostPaddingTop = 12.dp,
                keyboardLiftBottomContentPadding = bottomContentPadding
            )
        }
    }
}

private enum class SettingsQuiverFeedbackEntryKind {
    User,
    Quiver,
    Error
}

private data class SettingsQuiverFeedbackEntry(
    val id: Int,
    val kind: SettingsQuiverFeedbackEntryKind,
    val title: String,
    val body: String,
    val detail: String? = null,
    val ticketId: String? = null,
    val shareText: String? = null,
    val retryRequest: SettingsQuiverFeedbackRetryRequest? = null
)

internal data class SettingsQuiverFeedbackCopy(
    val title: String = "Send feedback",
    val introduction: String = "Share a problem or suggestion. Each submission creates a feedback message and copies a shareable reference.",
    val you: String = "You",
    val attachment: String = "Attachment",
    val attachmentLimitReached: (Int) -> String = { limit -> "You can upload up to $limit attachments at a time." },
    val attachmentTooLarge: (String) -> String = { maxSize -> "Attachments larger than $maxSize were skipped." },
    val attachmentLimitTrimmed: (Int) -> String = { limit -> "Only the first $limit attachments were kept." },
    val attachmentPickerFailed: String = "Unable to add the attachment.",
    val attachmentPickerPermissionNeeded: String = "Permission is needed to add an attachment.",
    val messageRequired: String = "A message is required.",
    val submissionUnavailable: String = "Feedback is unavailable right now. Please try again.",
    val submissionFailedTitle: String = "Feedback failed",
    val submissionFailedBody: String = "We could not send your feedback. Please try again.",
    val submissionNetworkBody: String = "Could not reach Hands. Check your connection and try again.",
    val submissionRateLimitedBody: String = "Too many feedback requests were sent. Wait a moment and try again.",
    val submissionAttachmentReadBody: String = "An attachment could not be read. Remove it and attach the file again.",
    val submissionAttachmentTooLargeBody: String = "An attachment is too large. Remove it or choose a smaller file.",
    val submissionAuthBody: String = "Feedback authentication failed. Update the app or contact support.",
    val submissionPermissionTitle: String = "Permission needed",
    val submissionPermissionBody: String = "You do not have permission to send feedback.",
    val submissionSucceeded: (String) -> String = { ticket -> "Submitted $ticket." },
    val referenceCopied: String = "Reference copied automatically.",
    val submitting: String = "Submitting feedback and preparing the copied reference...",
    val placeholder: String = "Share feedback",
    val copyResult: String = "Copy result",
    val retry: String = "Retry"
)

internal data class SettingsQuiverFeedbackFailurePresentation(
    val composerStatus: String,
    val title: String,
    val body: String,
    val retryable: Boolean
)

internal fun SettingsQuiverFeedbackCopy.submissionFailure(
    diagnosticCode: String? = null,
    retryable: Boolean = true
): SettingsQuiverFeedbackFailurePresentation {
    val body =
        when (diagnosticCode) {
            "settings_feedback_network_failed" -> submissionNetworkBody
            "settings_feedback_server_unavailable" -> submissionUnavailable
            "settings_feedback_rate_limited" -> submissionRateLimitedBody
            "settings_feedback_attachment_read_failed" -> submissionAttachmentReadBody
            "settings_feedback_attachment_too_large" -> submissionAttachmentTooLargeBody
            "settings_feedback_auth_failed" -> submissionAuthBody
            else -> submissionFailedBody
        }
    val canRetry =
        retryable &&
            diagnosticCode !in
                setOf(
                    "settings_feedback_attachment_read_failed",
                    "settings_feedback_attachment_too_large",
                    "settings_feedback_auth_failed"
                )
    return SettingsQuiverFeedbackFailurePresentation(
        composerStatus = body,
        title = submissionFailedTitle,
        body = body,
        retryable = canRetry
    )
}

internal fun SettingsQuiverFeedbackCopy.submissionUnavailableFailure(): SettingsQuiverFeedbackFailurePresentation =
    SettingsQuiverFeedbackFailurePresentation(
        composerStatus = submissionUnavailable,
        title = submissionFailedTitle,
        body = submissionUnavailable,
        retryable = true
    )

internal fun SettingsQuiverFeedbackCopy.submissionPermissionFailure(): SettingsQuiverFeedbackFailurePresentation =
    SettingsQuiverFeedbackFailurePresentation(
        composerStatus = submissionPermissionBody,
        title = submissionPermissionTitle,
        body = submissionPermissionBody,
        retryable = false
    )

@Composable
private fun settingsQuiverFeedbackCopy(): SettingsQuiverFeedbackCopy {
    val attachmentLimitReachedTemplate = stringResource(MR.strings.settings_feedback_attachment_limit_reached)
    val attachmentTooLargeTemplate = stringResource(MR.strings.settings_feedback_attachment_too_large)
    val attachmentLimitTrimmedTemplate = stringResource(MR.strings.settings_feedback_attachment_limit_trimmed)
    val submissionSucceededTemplate = stringResource(MR.strings.settings_feedback_submission_succeeded)
    return SettingsQuiverFeedbackCopy(
        title = stringResource(MR.strings.settings_feedback_title),
        introduction = stringResource(MR.strings.settings_feedback_introduction),
        you = stringResource(MR.strings.settings_feedback_you),
        attachment = stringResource(MR.strings.settings_feedback_attachment),
        attachmentLimitReached = { limit -> attachmentLimitReachedTemplate.replaceLocalizedFormatArgs(limit) },
        attachmentTooLarge = { maxSize -> attachmentTooLargeTemplate.replaceLocalizedFormatArgs(maxSize) },
        attachmentLimitTrimmed = { limit -> attachmentLimitTrimmedTemplate.replaceLocalizedFormatArgs(limit) },
        attachmentPickerFailed = stringResource(MR.strings.settings_feedback_attachment_picker_failed),
        attachmentPickerPermissionNeeded = stringResource(MR.strings.settings_feedback_attachment_permission_needed),
        messageRequired = stringResource(MR.strings.settings_feedback_message_required),
        submissionUnavailable = stringResource(MR.strings.settings_feedback_submission_unavailable),
        submissionFailedTitle = stringResource(MR.strings.settings_feedback_submission_failed_title),
        submissionFailedBody = stringResource(MR.strings.settings_feedback_submission_failed_body),
        submissionNetworkBody = stringResource(MR.strings.settings_feedback_submission_network_body),
        submissionRateLimitedBody = stringResource(MR.strings.settings_feedback_submission_rate_limited_body),
        submissionAttachmentReadBody = stringResource(MR.strings.settings_feedback_submission_attachment_read_body),
        submissionAttachmentTooLargeBody = stringResource(MR.strings.settings_feedback_submission_attachment_too_large_body),
        submissionAuthBody = stringResource(MR.strings.settings_feedback_submission_auth_body),
        submissionPermissionTitle = stringResource(MR.strings.settings_feedback_submission_permission_title),
        submissionPermissionBody = stringResource(MR.strings.settings_feedback_submission_permission_body),
        submissionSucceeded = { ticket -> submissionSucceededTemplate.replaceLocalizedFormatArgs(ticket) },
        referenceCopied = stringResource(MR.strings.settings_feedback_reference_copied),
        submitting = stringResource(MR.strings.settings_feedback_submitting),
        placeholder = stringResource(MR.strings.settings_feedback_placeholder),
        copyResult = stringResource(MR.strings.settings_feedback_copy_result),
        retry = stringResource(MR.strings.common_retry)
    )
}

internal data class SettingsQuiverFeedbackRetryRequest(
    val message: String,
    val attachments: List<PlatformFile>
)

internal fun SettingsQuiverFeedbackRetryRequest.matches(
    message: String,
    attachments: List<PlatformFile>
): Boolean =
    this.message == message.trim() &&
        this.attachments.map { it.feedbackComposerAttachmentId() } ==
        attachments.map { it.feedbackComposerAttachmentId() }

internal fun String.replaceLocalizedFormatArgs(vararg args: Any): String =
    args.foldIndexed(this) { index, value, argument ->
        val placeholderIndex = index + 1
        value
            .replace("%${placeholderIndex}${'$'}s", argument.toString())
            .replace("%${placeholderIndex}${'$'}d", argument.toString())
    }

private fun SettingsQuiverFeedbackSubmission.toQuiverFeedbackEntry(
    id: Int,
    copy: SettingsQuiverFeedbackCopy
): SettingsQuiverFeedbackEntry =
    SettingsQuiverFeedbackEntry(
        id = id,
        kind = SettingsQuiverFeedbackEntryKind.Quiver,
        title = copy.title,
        body =
            listOf(
                copy.submissionSucceeded(ticketId.take(8).ifBlank { copy.title }),
                shareText.takeIf { it.isNotBlank() }?.let { copy.referenceCopied }
            ).filterNotNull().joinToString(" "),
        detail = shareText.takeIf { it.isNotBlank() },
        ticketId = ticketId,
        shareText = shareText
    )

private fun List<PlatformFile>.toFeedbackComposerAttachmentItems(
    copy: SettingsQuiverFeedbackCopy
): List<KuiklyPendingAttachmentItem> =
    map { file ->
        KuiklyPendingAttachmentItem(
            id = file.feedbackComposerAttachmentId(),
            fileName = file.fileName.ifBlank { copy.attachment },
            sizeLabel = "",
            sizeBytes = file.sizeBytes ?: 0L,
            mimeType = file.mimeType,
            previewUri = file.previewUri,
            status = "ready"
        )
    }

private fun PlatformFile.feedbackComposerAttachmentId(): String =
    fileRef?.takeIf { it.isNotBlank() } ?: id

@Composable
private fun SettingsQuiverFeedbackConversationRow(
    entry: SettingsQuiverFeedbackEntry,
    copy: SettingsQuiverFeedbackCopy,
    onCopy: (String) -> Unit,
    onRetry: (SettingsQuiverFeedbackRetryRequest) -> Unit
) {
    val isUser = entry.kind == SettingsQuiverFeedbackEntryKind.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 560.dp)
                    .background(
                        when (entry.kind) {
                            SettingsQuiverFeedbackEntryKind.User -> SlockColor.BrandYellow
                            SettingsQuiverFeedbackEntryKind.Quiver -> SlockColor.SurfaceWhite
                            SettingsQuiverFeedbackEntryKind.Error -> SlockColor.AccentRed.copy(alpha = 0.18f)
                        }
                    )
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = entry.title,
                style = SlockType.SectionLabel,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.body,
                style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack
            )
            entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                SelectionContainer(
                    state = rememberSelectionContainerState(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = detail,
                        style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray
                    )
                }
            }
            entry.shareText?.takeIf { it.isNotBlank() }?.let { shareText ->
                SlockBrutalActionButton(
                    label = copy.copyResult,
                    onClick = { onCopy(shareText) },
                    leadingIcon = SlockLucideIconName.Clipboard,
                    modifier = Modifier.fillMaxWidth(),
                    minWidth = 0.dp
                )
            }
            entry.retryRequest?.let { retryRequest ->
                SlockBrutalActionButton(
                    label = copy.retry,
                    onClick = { onRetry(retryRequest) },
                    leadingIcon = SlockLucideIconName.RefreshCw,
                    modifier = Modifier.fillMaxWidth(),
                    minWidth = 0.dp
                )
            }
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

@Composable
private fun appShellSettingsHomePayload(
    session: StoredAuthSession?,
    selectedDestination: String
): KuiklySettingsHomePayload {
    val personalSection = stringResource(MR.strings.settings_navigation_section_personal)
    val workspaceSection = stringResource(MR.strings.settings_navigation_section_workspace)
    val aboutSection = stringResource(MR.strings.settings_navigation_section_about)
    return KuiklySettingsHomePayload(
        title = stringResource(MR.strings.settings_title),
        subtitle = "",
        showSelection = true,
        items =
            listOf(
                SlockTabItemPayload(
                    key = SettingsDestinationAccount,
                    title = stringResource(MR.strings.settings_account_title),
                    subtitle = stringResource(MR.strings.settings_account_subtitle),
                    iconName = SlockLucideIconName.User,
                    value = session?.user?.bestDisplayName,
                    selected = selectedDestination == SettingsDestinationAccount,
                    section = personalSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationLanguageRegion,
                    title = stringResource(MR.strings.settings_language_region_title),
                    subtitle = stringResource(MR.strings.settings_language_region_subtitle),
                    iconName = SlockLucideIconName.Languages,
                    selected = selectedDestination == SettingsDestinationLanguageRegion,
                    section = personalSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationAppearance,
                    title = stringResource(MR.strings.settings_appearance_title),
                    subtitle = stringResource(MR.strings.settings_appearance_subtitle),
                    iconName = SlockLucideIconName.Type,
                    selected = selectedDestination == SettingsDestinationAppearance,
                    section = personalSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationNotifications,
                    title = stringResource(MR.strings.settings_notifications_title),
                    subtitle = stringResource(MR.strings.settings_notifications_subtitle),
                    iconName = SlockLucideIconName.Bell,
                    selected = selectedDestination == SettingsDestinationNotifications,
                    section = personalSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationMobile,
                    title = stringResource(MR.strings.settings_mobile_title),
                    subtitle = stringResource(MR.strings.settings_mobile_subtitle),
                    iconName = SlockLucideIconName.Smartphone,
                    selected = selectedDestination == SettingsDestinationMobile,
                    section = personalSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationStorage,
                    title = stringResource(MR.strings.settings_storage_title),
                    subtitle = stringResource(MR.strings.settings_storage_subtitle),
                    iconName = SlockLucideIconName.Archive,
                    selected = selectedDestination == SettingsDestinationStorage,
                    section = personalSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationServer,
                    title = stringResource(MR.strings.settings_server_profile_title),
                    subtitle = stringResource(MR.strings.settings_server_profile_subtitle),
                    iconName = SlockLucideIconName.Building2,
                    value = session?.currentServerSummary()?.role?.replaceFirstChar { it.uppercase() },
                    selected = selectedDestination == SettingsDestinationServer,
                    section = workspaceSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationBilling,
                    title = stringResource(MR.strings.settings_billing_title),
                    subtitle = stringResource(MR.strings.settings_billing_subtitle),
                    iconName = SlockLucideIconName.CreditCard,
                    value = session?.currentServerSummary()?.plan?.replaceFirstChar { it.uppercase() },
                    selected = selectedDestination == SettingsDestinationBilling,
                    section = workspaceSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationAdministration,
                    title = stringResource(MR.strings.settings_server_administration_title),
                    subtitle = stringResource(MR.strings.settings_server_administration_subtitle),
                    iconName = SlockLucideIconName.Shield,
                    selected = selectedDestination == SettingsDestinationAdministration,
                    section = workspaceSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationConnectedApps,
                    title = stringResource(MR.strings.settings_connected_apps_title),
                    subtitle = stringResource(MR.strings.settings_connected_apps_subtitle),
                    iconName = SlockLucideIconName.Link2,
                    selected = selectedDestination == SettingsDestinationConnectedApps,
                    section = workspaceSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationComputers,
                    title = stringResource(MR.strings.settings_computers_title),
                    subtitle = stringResource(MR.strings.settings_computers_subtitle),
                    iconName = SlockLucideIconName.Monitor,
                    selected = selectedDestination == SettingsDestinationComputers,
                    section = workspaceSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationAbout,
                    title = stringResource(MR.strings.settings_about_title),
                    subtitle = stringResource(MR.strings.settings_about_subtitle),
                    iconName = SlockLucideIconName.BadgeInfo,
                    selected = selectedDestination == SettingsDestinationAbout,
                    section = aboutSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationQuiverFeedback,
                    title = stringResource(MR.strings.settings_feedback_title),
                    subtitle = stringResource(MR.strings.settings_feedback_subtitle),
                    iconName = SlockLucideIconName.MessageSquarePlus,
                    selected = selectedDestination == SettingsDestinationQuiverFeedback,
                    section = aboutSection
                ),
                SlockTabItemPayload(
                    key = SettingsDestinationReleaseNotes,
                    title = stringResource(MR.strings.settings_release_notes_title),
                    subtitle = stringResource(MR.strings.settings_release_notes_subtitle),
                    iconName = SlockLucideIconName.FileText,
                    selected = selectedDestination == SettingsDestinationReleaseNotes,
                    section = aboutSection
                )
            )
    )
}

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

@Composable
internal fun ScreenLoadState<SharedSettingsStorageSnapshot>.toStoragePayload(
    actionState: build.raft.app.data.SharedSettingsActionState?,
    refreshing: Boolean
): KuiklySettingsStoragePayload {
    val storage = (this as? ScreenLoadState.Content<SharedSettingsStorageSnapshot>)?.data
    val statusText =
        when (this) {
            ScreenLoadState.Loading -> if (refreshing) stringResource(MR.strings.settings_storage_refreshing) else stringResource(MR.strings.settings_storage_loading)
            is ScreenLoadState.Empty -> stringResource(MR.strings.settings_storage_unavailable)
            is ScreenLoadState.Error -> MR.strings.settings_storage_unavailable_detail.format(listOf(this.error.settingsUserFacingMessage())).localized()
            is ScreenLoadState.NoPermission -> MR.strings.settings_storage_unavailable_detail.format(listOf(this.blocker.settingsUserFacingMessage())).localized()
            is ScreenLoadState.Content -> storage?.feedback.orEmpty()
        }
    return KuiklySettingsStoragePayload(
        instanceId = "shared-shell-settings-storage",
        totalTitle = stringResource(MR.strings.settings_storage_total_title),
        totalSize = storage?.totalSizeLabel?.takeIf { it.isNotBlank() } ?: if (refreshing) stringResource(MR.strings.settings_state_refreshing) else "--",
        refreshLabel = stringResource(MR.strings.settings_storage_refresh),
        safeClearNote = stringResource(MR.strings.settings_storage_safe_clear_note),
        feedback = localizedSettingsStorageFeedback(statusText),
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
private fun SharedSettingsStorageRow.toStoragePayloadRow(clearInProgress: Boolean): KuiklySettingsStorageRow =
    KuiklySettingsStorageRow(
        target = target,
        iconName = storageTargetIconName(target),
        title = localizedSettingsStorageRowTitle(target, title),
        detail = localizedSettingsStorageRowDetail(detail),
        size = sizeLabel,
        clearLabel =
            if (clearEnabled) {
                when (target) {
                    "imageCache" -> stringResource(MR.strings.settings_storage_clear_image_cache)
                    "attachmentDownloads" -> stringResource(MR.strings.settings_storage_clear_downloaded_files)
                    "temporaryAndLogs" -> stringResource(MR.strings.settings_storage_clear_temp_files)
                    else -> stringResource(MR.strings.common_clear)
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
        "database" -> stringResource(MR.strings.settings_storage_row_database)
        "imageCache" -> stringResource(MR.strings.settings_storage_row_image_cache)
        "attachmentDownloads" -> stringResource(MR.strings.settings_storage_row_downloads)
        "temporaryAndLogs" -> stringResource(MR.strings.settings_storage_row_temporary)
        else -> fallback
    }

@Composable
private fun localizedSettingsStorageRowDetail(detail: String): String =
    when (detail) {
        "Messages, task metadata, and server snapshots stored on this device." ->
            stringResource(MR.strings.settings_storage_row_database_detail)
        "Downloaded image previews kept for faster viewing." ->
            stringResource(MR.strings.settings_storage_row_image_cache_detail)
        "Files downloaded for opening or installing from previews." ->
            stringResource(MR.strings.settings_storage_row_downloads_detail)
        "Private copies kept for opening or installing files. Public exports are not removed." ->
            stringResource(MR.strings.settings_storage_row_downloads_detail)
        "Debug logs and temporary files generated by the app." ->
            stringResource(MR.strings.settings_storage_row_temporary_detail)
        "OHOS storage usage is not exposed through the host capability yet." ->
            stringResource(MR.strings.settings_storage_ohos_database_detail)
        "Image cache clearing is not available on OHOS yet." ->
            stringResource(MR.strings.settings_storage_ohos_image_cache_detail)
        "Use Diagnostics clear logs for the local diagnostics buffer." ->
            stringResource(MR.strings.settings_storage_ohos_temporary_detail)
        else -> detail
    }

@Composable
private fun localizedSettingsStorageFeedback(feedback: String): String =
    when (feedback) {
        "Image cache cleared." -> stringResource(MR.strings.settings_storage_feedback_image_cleared)
        "Downloaded files cleared.",
        "Attachment cache cleared. Public exports were not removed." ->
            stringResource(MR.strings.settings_storage_feedback_downloads_cleared)
        "Temporary files and diagnostics logs cleared." ->
            stringResource(MR.strings.settings_storage_feedback_temporary_cleared)
        else -> feedback
    }

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
    timeContext: SlockTimeContext?,
    actionState: build.raft.app.data.SharedSettingsActionState? = null
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
                action.toBillingActionPayload(actionState)
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
            is ScreenLoadState.Error -> "Workspace settings failed to load: ${error.settingsUserFacingMessage()}"
            is ScreenLoadState.NoPermission -> "Workspace settings unavailable: ${blocker.settingsUserFacingMessage()}"
            is ScreenLoadState.Empty -> "Workspace settings unavailable: ${reason.settingsUserFacingMessage()}"
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

@Composable
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
            is ScreenLoadState.Error -> "Administration failed to load: ${error.settingsUserFacingMessage()}"
            is ScreenLoadState.NoPermission -> "Administration unavailable: ${blocker.settingsUserFacingMessage()}"
            is ScreenLoadState.Empty -> "Administration unavailable: ${reason.settingsUserFacingMessage()}"
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
            settingsAdministrationSectionSpecs().map { spec ->
                val section = sectionsByKey[spec.key]
                val sectionTitle = (section?.title ?: spec.title).localized()
                when (spec.key) {
                    "admins" ->
                        KuiklySharedSettingsSection(
                            title = sectionTitle,
                            rows =
                                settingsAdministrationAdminRows(
                                    members = snapshot?.admins,
                                    section = section,
                                    sectionTitle = sectionTitle,
                                    actorRole = server?.role,
                                    actionState = actionState
                                )
                        )
                    "system_channels" ->
                        KuiklySharedSettingsSection(
                            title = sectionTitle,
                            rows = snapshot?.systemChannels.toSystemChannelRows(section, sectionTitle, actionState)
                        )
                    "pending_invites" ->
                        KuiklySharedSettingsSection(
                            title = sectionTitle,
                            rows = snapshot?.pendingInvites.toInviteRows(section, sectionTitle, actionState, timeContext)
                        )
                    "join_links" ->
                        KuiklySharedSettingsSection(
                            title = sectionTitle,
                            rows = snapshot?.joinLinks.toJoinLinkRows(section, sectionTitle, actionState, timeContext)
                        )
                    "pre_join_agreement" ->
                        KuiklySharedSettingsSection(
                            title = sectionTitle,
                            rows = snapshot?.agreement.toAgreementRows(section, sectionTitle, actionState)
                        )
                    "onboarding_agent" ->
                        KuiklySharedSettingsSection(
                            title = sectionTitle,
                            rows = snapshot?.onboarding.toOnboardingRows(section, sectionTitle, actionState)
                        )
                    "translation" ->
                        KuiklySharedSettingsSection(
                            title = sectionTitle,
                            rows = snapshot?.translation.toTranslationRows(section, sectionTitle, actionState)
                        )
                    "member_permissions" ->
                        KuiklySharedSettingsSection(
                            title = sectionTitle,
                            rows = snapshot?.profile.toMemberPermissionsRows(section, sectionTitle, actionState)
                        )
                    "sidebar_order" ->
                        KuiklySharedSettingsSection(
                            title = sectionTitle,
                            rows = snapshot?.sidebarOrder.toSidebarOrderRows(section, sectionTitle, actionState)
                        )
                    else -> error("Unknown administration section: ${spec.key}")
                }
            }
    )
}

internal data class SettingsAdministrationSectionSpec(
    val key: String,
    val title: StringDesc
)

internal fun settingsAdministrationSectionSpecs(): List<SettingsAdministrationSectionSpec> =
    listOf(
        SettingsAdministrationSectionSpec("admins", MR.strings.settings_section_admins_title.desc()),
        SettingsAdministrationSectionSpec("system_channels", MR.strings.settings_section_system_channels_title.desc()),
        SettingsAdministrationSectionSpec("pending_invites", MR.strings.settings_section_pending_invites_title.desc()),
        SettingsAdministrationSectionSpec("join_links", MR.strings.settings_section_join_links_title.desc()),
        SettingsAdministrationSectionSpec("pre_join_agreement", MR.strings.settings_section_pre_join_agreement_title.desc()),
        SettingsAdministrationSectionSpec("onboarding_agent", MR.strings.settings_section_onboarding_title.desc()),
        SettingsAdministrationSectionSpec("translation", MR.strings.settings_section_translation_title.desc()),
        SettingsAdministrationSectionSpec("member_permissions", MR.strings.settings_section_member_permissions_title.desc()),
        SettingsAdministrationSectionSpec("sidebar_order", MR.strings.settings_section_sidebar_order_title.desc())
    )

internal fun settingsAdministrationAdminRows(
    members: List<SettingsServerAdminSnapshot>?,
    section: SettingsServerSectionSnapshot?,
    sectionTitle: String,
    actorRole: String?,
    actionState: build.raft.app.data.SharedSettingsActionState?
): List<KuiklySharedSettingsRow> =
    members.toAdminRows(section = section, sectionTitle = sectionTitle, actorRole = actorRole, actionState = actionState)

private fun List<SettingsServerAdminSnapshot>?.toAdminRows(
    section: SettingsServerSectionSnapshot?,
    sectionTitle: String,
    actorRole: String?,
    actionState: build.raft.app.data.SharedSettingsActionState?
): List<KuiklySharedSettingsRow> {
    val members = this.orEmpty()
    val admins =
        members
            .filter { it.isPrivilegedServerMember() }
            .sortedWith(compareBy<SettingsServerAdminSnapshot> { if (it.normalizedRole() == "owner") 0 else 1 }.thenBy { it.name })
    if (admins.isEmpty()) {
        return listOf(section.toPlaceholderRow("admins", SlockLucideIconName.Shield, sectionTitle, "No owners or admins are available."))
    }
    val normalizedActorRole = actorRole.normalizedServerRole()
    val ownerCount = admins.count { it.normalizedRole() == "owner" }
    return admins.flatMap { admin ->
        val targetRole = admin.normalizedRole()
        val canDemote = canChangeServerMemberRole(normalizedActorRole, targetRole, "member") && !(targetRole == "owner" && ownerCount <= 1)
        val fallback = if (targetRole == "owner") "Remove Owner" else "Remove Admin"
        val disabledLabel =
            if (targetRole == "owner" && ownerCount <= 1) {
                "Last owner"
            } else {
                "Owner only"
            }
        listOf(
            KuiklySharedSettingsRow(
                key = "admin:${admin.id}",
                iconName = SlockLucideIconName.Shield,
                title = admin.name,
                detail = listOfNotNull(admin.detail, admin.role).joinToString(" · ").ifBlank { admin.role ?: "Admin" },
                status = targetRole.replaceFirstChar { it.uppercase() },
                enabled = false
            ),
            KuiklySharedSettingsRow(
                key = SERVER_ACTION_MEMBER_ROLE,
                iconName = SlockLucideIconName.UserMinus,
                title = "Remove ${admin.name}'s ${targetRole.ifBlank { "admin" }} role",
                detail = "Demote this member back to regular member access.",
                value = "${admin.id}:member",
                status = if (canDemote) actionState.serverStatusFor(SERVER_ACTION_MEMBER_ROLE, fallback, admin.id) else disabledLabel,
                enabled = canDemote && actionState?.inProgress != true
            )
        )
    }
}

private fun List<SettingsServerInviteSnapshot>?.toInviteRows(
    section: SettingsServerSectionSnapshot?,
    sectionTitle: String,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    timeContext: SlockTimeContext?
): List<KuiklySharedSettingsRow> {
    val invites = this.orEmpty()
    if (invites.isEmpty()) {
        return listOf(
            section.toPlaceholderRow("pendingInvites", SlockLucideIconName.MailPlus, sectionTitle, "No pending invites are available.")
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
    sectionTitle: String,
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
        return listOf(section.toPlaceholderRow("joinLinks", SlockLucideIconName.Link2, sectionTitle, "No join links are available."), createRow)
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
    sectionTitle: String,
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
        } ?: section.toPlaceholderRow("preJoinAgreement", SlockLucideIconName.FileCheck, sectionTitle, "No pre-join agreement settings are available.")
    )

private fun SettingsServerOnboardingSnapshot?.toOnboardingRows(
    section: SettingsServerSectionSnapshot?,
    sectionTitle: String,
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
        } ?: section.toPlaceholderRow("onboarding", SlockLucideIconName.Bot, sectionTitle, "No onboarding settings are available.")
    )

private fun build.raft.app.data.SettingsServerProfileSnapshot?.toMemberPermissionsRows(
    section: SettingsServerSectionSnapshot?,
    sectionTitle: String,
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
        } ?: section.toPlaceholderRow("memberPermissions", SlockLucideIconName.UsersRound, sectionTitle, "No member permission settings are available.")
    )

private fun SettingsServerTranslationSnapshot?.toTranslationRows(
    section: SettingsServerSectionSnapshot?,
    sectionTitle: String,
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
        } ?: section.toPlaceholderRow("translation", SlockLucideIconName.Languages, sectionTitle, "No translation settings are available.")
    )

private fun SettingsServerSystemChannelSnapshot?.toSystemChannelRows(
    section: SettingsServerSectionSnapshot?,
    sectionTitle: String,
    actionState: build.raft.app.data.SharedSettingsActionState?
): List<KuiklySharedSettingsRow> {
    val system = this
    if (system == null) {
        return listOf(section.toPlaceholderRow("system_channels", SlockLucideIconName.Eye, sectionTitle, "System channel controls are unavailable."))
    }
    if (system.allChannelId.isBlank()) {
        return listOf(section.toPlaceholderRow("system_channels", SlockLucideIconName.Eye, sectionTitle, "No #all channel was found for this workspace."))
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
    sectionTitle: String,
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
        } ?: section.toPlaceholderRow("sidebarOrder", SlockLucideIconName.ListTree, sectionTitle, "No sidebar order settings are available.")
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
    sectionTitle: String,
    fallbackDetail: String
): KuiklySharedSettingsRow =
    KuiklySharedSettingsRow(
        key = key,
        iconName = iconName,
        title = sectionTitle,
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
                            ),
                            mobile.compactConsecutiveMessages.toMobileToggleRow(
                                key = MOBILE_ACTION_COMPACT_MESSAGES,
                                iconName = SlockLucideIconName.MessageSquare,
                                title = "Compact consecutive messages",
                                fallbackStatus = "Unsupported",
                                actionState = actionState
                            ),
                            mobile.longMessageCollapse.toMobileToggleRow(
                                key = MOBILE_ACTION_LONG_MESSAGE_COLLAPSE,
                                iconName = SlockLucideIconName.ClipboardList,
                                title = "Collapse long messages",
                                fallbackStatus = "Off",
                                actionState = actionState
                            ),
                            mobile.foregroundMessageBanners.toMobileToggleRow(
                                key = MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS,
                                iconName = SlockLucideIconName.MessageCircle,
                                title = "Foreground message banners",
                                fallbackStatus = "Off",
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
                                title = "Preferred translation language",
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
            is ScreenLoadState.Error -> state.error.settingsUserFacingMessage()
            is ScreenLoadState.NoPermission -> state.blocker.settingsUserFacingMessage()
            is ScreenLoadState.Empty -> state.reason.settingsUserFacingMessage()
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
            is ScreenLoadState.Error -> state.error.settingsUserFacingMessage()
            is ScreenLoadState.NoPermission -> state.blocker.settingsUserFacingMessage()
            is ScreenLoadState.Empty -> state.reason.settingsUserFacingMessage()
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

private fun SettingsMobileToggleSnapshot.toMobileToggleRow(
    key: String,
    iconName: SlockLucideIconName,
    title: String,
    fallbackStatus: String,
    actionState: build.raft.app.data.SharedSettingsActionState?
): KuiklySharedSettingsRow =
    KuiklySharedSettingsRow(
        key = key,
        iconName = iconName,
        title = title,
        detail = detail,
        value = (!enabled).toString(),
        status = actionState.statusFor(key, statusLabel.ifBlank { fallbackStatus }),
        enabled = available && actionState?.inProgress != true,
        toggleValue = enabled
    )

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

internal fun settingsAdminRoleOptions(actorRole: String?, targetRole: String?): List<String> =
    listOf("owner", "admin")
        .filter { nextRole ->
            val normalizedTargetRole = targetRole.normalizedServerRole()
            nextRole != normalizedTargetRole &&
                canChangeServerMemberRole(actorRole.normalizedServerRole(), normalizedTargetRole, nextRole)
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

@Composable
private fun build.raft.app.data.SharedSettingsActionState?.serverLocalizedStatusFor(
    action: String,
    fallback: String,
    itemId: String? = null
): String =
    when {
        isServerConfirmation(action, itemId) -> stringResource(MR.strings.settings_state_confirm)
        this == null || this.action != action -> fallback
        itemId != null && this.itemId != itemId -> fallback
        inProgress -> stringResource(MR.strings.settings_state_loading)
        errorCode != null -> errorCode
        else -> stringResource(MR.strings.settings_state_saved)
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

@Composable
private fun build.raft.app.data.SharedSettingsActionState?.connectedAppsLocalizedStatusFor(
    action: String,
    fallback: String,
    itemId: String? = null
): String =
    when {
        this == null || this.action != action -> fallback
        itemId != null && this.itemId != itemId -> fallback
        inProgress -> stringResource(MR.strings.settings_state_loading)
        errorCode != null -> errorCode
        else -> stringResource(MR.strings.settings_state_saved)
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

private fun List<SettingsConnectedAppMarketplaceSnapshot>.settingsConnectedAppCategoryOptions(
    allCategoriesLabel: String,
    otherCategoryLabel: String
): List<SlockRaftUiSelectionOption> =
    listOf(SlockRaftUiSelectionOption(SETTINGS_CONNECTED_APPS_CATEGORY_ALL, allCategoriesLabel)) +
        map { it.category.trim().ifBlank { otherCategoryLabel } }
            .distinct()
            .sorted()
            .map { SlockRaftUiSelectionOption(it, it.settingsConnectedAppCategoryLabel(otherCategoryLabel)) }

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

private fun String.settingsConnectedAppCategoryLabel(otherCategoryLabel: String): String =
    trim()
        .ifBlank { otherCategoryLabel }
        .replace("-", " ")
        .replace("_", " ")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

private fun settingsConnectedAppsPermissionCopy(blocker: PermissionBlocker, adminRequiredCopy: String): String =
    if (blocker.code == "server_admin_required" || blocker.requiredCapability == "manage_integrations") {
        adminRequiredCopy
    } else {
        blocker.settingsUserFacingMessage()
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

internal data class SettingsAboutPayloadCopy(
    val title: String = "About",
    val detail: String = "Version, build, and source",
    val applicationSection: String = "Application",
    val application: String = "Application",
    val version: String = "Version",
    val buildTime: String = "Build time",
    val gitCommit: String = "Git commit",
    val packageLabel: String = "Package",
    val unknown: String = "Unknown",
    val buildUnknown: String = "Build unknown",
    val actions: String = "Actions",
    val checkUpdate: String = "Check for update",
    val checkUpdateDetail: String = "Checks for a newer Android build and opens the installer when available.",
    val checking: String = "Checking",
    val check: String = "Check",
    val licenses: String = "Open Source Licenses",
    val licensesDetail: String = "Review third-party libraries included in the Android app package.",
    val open: String = "Open",
    val copyEnvironment: String = "Copy environment info",
    val copyEnvironmentDetail: String = "Copy the same sanitized environment used for diagnostics and feedback.",
    val copy: String = "Copy",
    val openRaft: String = "Open Raft",
    val openWebDetail: (String) -> String = { "Open $it in the system browser." }
)

@Composable
private fun settingsAboutPayloadCopy(resolvedWebUrl: String): SettingsAboutPayloadCopy {
    val localizedOpenWebDetail = MR.strings.settings_about_open_web_detail.format(listOf(resolvedWebUrl)).localized()
    return SettingsAboutPayloadCopy(
        title = stringResource(MR.strings.settings_about_title),
        detail = stringResource(MR.strings.settings_about_version_build_source),
        applicationSection = stringResource(MR.strings.settings_about_section_application),
        application = stringResource(MR.strings.settings_about_label_application),
        version = stringResource(MR.strings.settings_about_label_version),
        buildTime = stringResource(MR.strings.settings_about_label_build_time),
        gitCommit = stringResource(MR.strings.settings_about_label_git_commit),
        packageLabel = stringResource(MR.strings.settings_about_label_package),
        unknown = stringResource(MR.strings.settings_about_state_unknown),
        buildUnknown = stringResource(MR.strings.settings_about_state_build_unknown),
        actions = stringResource(MR.strings.settings_about_section_actions),
        checkUpdate = stringResource(MR.strings.settings_about_action_check_update),
        checkUpdateDetail = stringResource(MR.strings.settings_about_check_update_detail),
        checking = stringResource(MR.strings.settings_about_state_checking),
        check = stringResource(MR.strings.settings_about_action_check),
        licenses = stringResource(MR.strings.settings_open_source_licenses_title),
        licensesDetail = stringResource(MR.strings.settings_about_licenses_detail),
        open = stringResource(MR.strings.settings_state_open),
        copyEnvironment = stringResource(MR.strings.settings_about_action_copy_environment),
        copyEnvironmentDetail = stringResource(MR.strings.settings_about_copy_environment_detail),
        copy = stringResource(MR.strings.settings_about_action_copy),
        openRaft = stringResource(MR.strings.settings_about_action_open_raft),
        openWebDetail = { localizedOpenWebDetail }
    )
}

internal data class SettingsDeveloperPayloadCopy(
    val title: String = "Developer",
    val detail: String = "API server and diagnostics",
    val apiServer: String = "API Server",
    val currentApi: String = "Current API",
    val currentSocket: String = "Current socket",
    val apiEnvironment: String = "API Environment",
    val environmentLoading: String = "Loading developer environment options.",
    val environmentDetail: String = "Choose presets or edit API, socket, and web URLs.",
    val saving: String = "Saving",
    val loading: String = "Loading",
    val open: String = "Open",
    val unknown: String = "Unknown",
    val current: String = "Current",
    val realtime: String = "Realtime",
    val diagnostics: String = "Diagnostics",
    val diagnosticsDetail: String = "Open auth, sanitized logs, profiler, and feedback tools.",
    val routeLauncher: String = "Route launcher",
    val routeLauncherDetail: String = "Open Kuikly route launcher and icon compare."
)

@Composable
private fun settingsDeveloperPayloadCopy(): SettingsDeveloperPayloadCopy =
    SettingsDeveloperPayloadCopy(
        title = stringResource(MR.strings.settings_developer_title),
        detail = stringResource(MR.strings.settings_developer_detail),
        apiServer = stringResource(MR.strings.settings_developer_section_api_server),
        currentApi = stringResource(MR.strings.settings_developer_current_api),
        currentSocket = stringResource(MR.strings.settings_developer_current_socket),
        apiEnvironment = stringResource(MR.strings.settings_developer_api_environment),
        environmentLoading = stringResource(MR.strings.settings_developer_environment_loading),
        environmentDetail = stringResource(MR.strings.settings_developer_environment_detail),
        saving = stringResource(MR.strings.settings_state_saving),
        loading = stringResource(MR.strings.settings_state_loading),
        open = stringResource(MR.strings.settings_state_open),
        unknown = stringResource(MR.strings.settings_about_state_unknown),
        current = stringResource(MR.strings.settings_developer_state_current),
        realtime = stringResource(MR.strings.settings_developer_state_realtime),
        diagnostics = stringResource(MR.strings.settings_diagnostics_title),
        diagnosticsDetail = stringResource(MR.strings.settings_developer_diagnostics_detail),
        routeLauncher = stringResource(MR.strings.settings_developer_route_launcher),
        routeLauncherDetail = stringResource(MR.strings.settings_developer_route_launcher_detail)
    )

internal data class SettingsDiagnosticsPayloadCopy(
    val title: String = "Diagnostics",
    val detailProfiler: String = "Auth, sanitized app diagnostics, and Kuikly recomposition profiler",
    val detailStatus: String = "Auth, sanitized app diagnostics, and Kuikly profiler status",
    val detailBasic: String = "Auth and sanitized app diagnostics",
    val environment: String = "Environment",
    val loadStatus: String = "Load status",
    val actions: String = "Actions",
    val partialDiagnostics: String = "Partial diagnostics",
    val partial: String = "Partial",
    val profiler: String = "Recomposition profiler",
    val profilerStatus: String = "Shared profiler status",
    val profilerDetail: String = "Persist this debug switch locally. When enabled, shared settings asks the host bridge to acknowledge profiler mode, applies the default Kuikly profile, and starts collecting recompositions.",
    val resetProfiler: String = "Reset scene window",
    val resetProfilerDetail: String = "Clear the current in-memory counters right before opening the target page or replaying the target interaction.",
    val refreshProfiler: String = "Refresh report",
    val refreshProfilerDetail: String = "Call getReport(saveToFile=false) to refresh the current summary without rewriting files.",
    val exportProfiler: String = "Export report",
    val exportProfilerDetail: String = "Call getReport(saveToFile=true) and keep profiler_frames.jsonl beside profiler_report.json for analyzer input.",
    val loading: String = "Loading",
    val on: String = "On",
    val pending: String = "Pending",
    val off: String = "Off",
    val reset: String = "Reset",
    val wait: String = "Wait",
    val refresh: String = "Refresh",
    val pull: String = "Pull",
    val export: String = "Export",
    val write: String = "Write",
    val disabled: String = "Disabled",
    val send: String = "Send",
    val exportLogs: String = "Export logs",
    val exportLogsDetail: String = "Export environment, status, and recent app JSONL logs.",
    val sendDiagnostics: String = "Send diagnostics",
    val sendDiagnosticsDetail: String = "Share sanitized diagnostics with support.",
    val app: String = "App",
    val build: String = "Build",
    val versionCode: String = "Version code",
    val packageLabel: String = "Package",
    val platform: String = "Platform",
    val deviceFacts: String = "Device facts",
    val display: String = "Display",
    val installTime: String = "Install time",
    val session: String = "Session",
    val selectedServer: String = "Selected server",
    val unknown: String = "Unknown",
    val version: String = "Version",
    val source: String = "Source",
    val sdk: String = "SDK",
    val host: String = "Host",
    val device: String = "Device",
    val screen: String = "Screen",
    val density: String = "Density",
    val fontScale: String = "Font scale",
    val noSignedInUser: String = "No signed-in user",
    val socket: String = "Socket"
)

@Composable
private fun settingsDiagnosticsPayloadCopy(): SettingsDiagnosticsPayloadCopy =
    SettingsDiagnosticsPayloadCopy(
        title = stringResource(MR.strings.settings_diagnostics_title),
        detailProfiler = stringResource(MR.strings.settings_diagnostics_detail_profiler),
        detailStatus = stringResource(MR.strings.settings_diagnostics_detail),
        detailBasic = stringResource(MR.strings.settings_diagnostics_detail_basic),
        environment = stringResource(MR.strings.settings_diagnostics_section_environment),
        loadStatus = stringResource(MR.strings.settings_diagnostics_section_load_status),
        actions = stringResource(MR.strings.settings_diagnostics_section_actions),
        partialDiagnostics = stringResource(MR.strings.settings_diagnostics_partial),
        partial = stringResource(MR.strings.settings_diagnostics_state_partial),
        profiler = stringResource(MR.strings.settings_diagnostics_profiler_title),
        profilerStatus = stringResource(MR.strings.settings_diagnostics_profiler_status),
        profilerDetail = stringResource(MR.strings.settings_diagnostics_profiler_detail),
        resetProfiler = stringResource(MR.strings.settings_diagnostics_profiler_reset),
        resetProfilerDetail = stringResource(MR.strings.settings_diagnostics_profiler_reset_detail),
        refreshProfiler = stringResource(MR.strings.settings_diagnostics_profiler_refresh),
        refreshProfilerDetail = stringResource(MR.strings.settings_diagnostics_profiler_refresh_detail),
        exportProfiler = stringResource(MR.strings.settings_diagnostics_profiler_export),
        exportProfilerDetail = stringResource(MR.strings.settings_diagnostics_profiler_export_detail),
        loading = stringResource(MR.strings.settings_state_loading),
        on = stringResource(MR.strings.settings_state_on),
        pending = stringResource(MR.strings.settings_state_pending),
        off = stringResource(MR.strings.settings_state_off),
        reset = stringResource(MR.strings.settings_state_reset),
        wait = stringResource(MR.strings.settings_state_wait),
        refresh = stringResource(MR.strings.settings_state_refresh),
        pull = stringResource(MR.strings.settings_state_pull),
        export = stringResource(MR.strings.settings_state_export),
        write = stringResource(MR.strings.settings_state_write),
        disabled = stringResource(MR.strings.settings_state_disabled),
        send = stringResource(MR.strings.settings_state_send),
        exportLogs = stringResource(MR.strings.settings_diagnostics_action_export_logs),
        exportLogsDetail = stringResource(MR.strings.settings_diagnostics_export_logs_detail),
        sendDiagnostics = stringResource(MR.strings.settings_diagnostics_action_send),
        sendDiagnosticsDetail = stringResource(MR.strings.settings_diagnostics_send_detail),
        app = stringResource(MR.strings.settings_diagnostics_label_app),
        build = stringResource(MR.strings.settings_diagnostics_label_build),
        versionCode = stringResource(MR.strings.settings_diagnostics_label_version_code),
        packageLabel = stringResource(MR.strings.settings_diagnostics_label_package),
        platform = stringResource(MR.strings.settings_diagnostics_label_platform),
        deviceFacts = stringResource(MR.strings.settings_diagnostics_label_device_facts),
        display = stringResource(MR.strings.settings_diagnostics_label_display),
        installTime = stringResource(MR.strings.settings_diagnostics_label_install_time),
        session = stringResource(MR.strings.settings_diagnostics_label_session),
        selectedServer = stringResource(MR.strings.settings_diagnostics_label_selected_server),
        unknown = stringResource(MR.strings.settings_about_state_unknown),
        version = stringResource(MR.strings.settings_diagnostics_state_version),
        source = stringResource(MR.strings.settings_diagnostics_state_source),
        sdk = stringResource(MR.strings.settings_diagnostics_state_sdk),
        host = stringResource(MR.strings.settings_diagnostics_state_host),
        device = stringResource(MR.strings.settings_diagnostics_state_device),
        screen = stringResource(MR.strings.settings_diagnostics_state_screen),
        density = stringResource(MR.strings.settings_diagnostics_state_density),
        fontScale = stringResource(MR.strings.settings_diagnostics_state_font_scale),
        noSignedInUser = stringResource(MR.strings.settings_diagnostics_state_no_user),
        socket = stringResource(MR.strings.settings_diagnostics_state_socket)
    )

@Composable
private fun appShellAboutFallback(session: StoredAuthSession?): SharedSettingsAboutSnapshot =
    SharedSettingsAboutSnapshot(
        appName = "Raft",
        versionLabel = stringResource(MR.strings.settings_state_loading),
        signedInUserLabel = session?.user?.bestDisplayName ?: stringResource(MR.strings.settings_about_state_unknown),
        selectedServerLabel = session?.currentServerSummary()?.name ?: stringResource(MR.strings.settings_about_state_none),
        sessionStatusLabel = if (session != null) stringResource(MR.strings.settings_about_state_signed_in) else stringResource(MR.strings.settings_about_state_signed_out),
        socketStatusLabel = stringResource(MR.strings.settings_about_state_checking),
        notificationStatusLabel = stringResource(MR.strings.settings_about_state_checking)
    )

internal fun SharedSettingsAboutSnapshot.toAboutPayload(
    webUrl: String,
    updateCheck: SharedSettingsUpdateCheckSnapshot? = null,
    actionState: SharedSettingsActionState? = null,
    copy: SettingsAboutPayloadCopy = SettingsAboutPayloadCopy()
): KuiklySharedSettingsPayload {
    val resolvedWebUrl = sharedSettingsResolvedWebBaseUrl(webUrl)
    val raftLabel = "Raft"
    val updateCheckBusy =
        actionState?.action == DEVELOPER_ACTION_QUIVER_UPDATE_CHECK && actionState.inProgress
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-about",
        title = copy.title,
        heroIconName = SlockLucideIconName.Info,
        heroLabel = appName.ifBlank { raftLabel },
        detail = copy.detail,
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = copy.applicationSection,
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("appName", SlockLucideIconName.Sparkles, copy.application, appName.ifBlank { raftLabel }),
                            KuiklySharedSettingsRow("version", SlockLucideIconName.BadgeInfo, copy.version, versionLabel.ifBlank { copy.unknown }, status = buildLabel.ifBlank { copy.buildUnknown }),
                            KuiklySharedSettingsRow("buildTime", SlockLucideIconName.Clock, copy.buildTime, buildTime.ifBlank { copy.unknown }),
                            KuiklySharedSettingsRow("gitCommit", SlockLucideIconName.GitBranch, copy.gitCommit, gitCommit.ifBlank { copy.unknown }),
                            KuiklySharedSettingsRow("package", SlockLucideIconName.Package, copy.packageLabel, packageName.ifBlank { copy.unknown })
                        )
                ),
                KuiklySharedSettingsSection(
                    title = copy.actions,
                    rows =
                        buildList {
                            if (updateCheck?.supported == true) {
                                add(
                                    KuiklySharedSettingsRow(
                                        key = DEVELOPER_ACTION_QUIVER_UPDATE_CHECK,
                                        iconName = SlockLucideIconName.Download,
                                        title = copy.checkUpdate,
                                        detail = updateCheck.detail.ifBlank { copy.checkUpdateDetail },
                                        status =
                                            when {
                                                updateCheckBusy -> copy.checking
                                                updateCheck.statusLabel.isNotBlank() -> updateCheck.statusLabel
                                                else -> copy.check
                                            },
                                        enabled = !updateCheckBusy
                                    )
                                )
                            }
                            add(
                                KuiklySharedSettingsRow(
                                    key = "openSourceLicenses",
                                    iconName = SlockLucideIconName.FileText,
                                    title = copy.licenses,
                                    detail = copy.licensesDetail,
                                    status = copy.open,
                                    enabled = true
                                )
                            )
                            add(
                                KuiklySharedSettingsRow(
                                    key = "copyEnvironmentInfo",
                                    iconName = SlockLucideIconName.Clipboard,
                                    title = copy.copyEnvironment,
                                    detail = copy.copyEnvironmentDetail,
                                    status = copy.copy,
                                    enabled = true
                                )
                            )
                            add(KuiklySharedSettingsRow("openSlock", SlockLucideIconName.ExternalLink, copy.openRaft, copy.openWebDetail(resolvedWebUrl), value = resolvedWebUrl, status = copy.open, enabled = true))
                        }
                )
            )
    )
}

internal fun SharedSettingsDeveloperSnapshot?.toDeveloperPayload(
    state: ScreenLoadState<SharedSettingsDeveloperSnapshot>,
    actionState: SharedSettingsActionState?,
    copy: SettingsDeveloperPayloadCopy = SettingsDeveloperPayloadCopy()
): KuiklySharedSettingsPayload {
    val environmentDetail =
        when (state) {
            is ScreenLoadState.Error -> state.error.settingsUserFacingMessage()
            is ScreenLoadState.Empty -> state.reason.settingsUserFacingMessage()
            is ScreenLoadState.NoPermission -> state.blocker.settingsUserFacingMessage()
            ScreenLoadState.Loading -> copy.environmentLoading
            is ScreenLoadState.Content -> copy.environmentDetail
        }
    val environmentStatus =
        when {
            actionState?.action == DEVELOPER_ACTION_API_ENVIRONMENT && actionState.inProgress -> copy.saving
            this?.environmentLabel.orEmpty().isNotBlank() -> this?.environmentLabel.orEmpty()
            state is ScreenLoadState.Loading -> copy.loading
            else -> copy.open
        }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-developer",
        title = copy.title,
        detail = this?.feedback ?: this?.environmentLabel.orEmpty().ifBlank { copy.detail },
        heroIconName = SlockLucideIconName.Terminal,
        heroLabel = copy.title,
        sections =
            listOf(
                KuiklySharedSettingsSection(
                    title = copy.apiServer,
                    rows =
                        listOf(
                            KuiklySharedSettingsRow(
                                key = "currentApi",
                                iconName = SlockLucideIconName.Globe,
                                title = copy.currentApi,
                                detail = this?.apiBaseUrl.orEmpty().ifBlank { copy.unknown },
                                status = this?.environmentLabel.orEmpty().ifBlank { copy.current }
                            ),
                            KuiklySharedSettingsRow(
                                key = "currentSocket",
                                iconName = SlockLucideIconName.Activity,
                                title = copy.currentSocket,
                                detail = this?.socketBaseUrl.orEmpty().ifBlank { copy.unknown },
                                status = copy.realtime
                            ),
                            KuiklySharedSettingsRow(
                                key = DEVELOPER_ACTION_API_ENVIRONMENT,
                                iconName = SlockLucideIconName.SlidersHorizontal,
                                title = copy.apiEnvironment,
                                detail = environmentDetail,
                                status = environmentStatus,
                                enabled = state is ScreenLoadState.Content
                            )
                        )
                ),
                KuiklySharedSettingsSection(
                    title = copy.diagnostics,
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("diagnostics", SlockLucideIconName.ClipboardList, copy.diagnostics, copy.diagnosticsDetail, status = copy.open, enabled = true),
                            KuiklySharedSettingsRow("router", SlockLucideIconName.Route, copy.routeLauncher, copy.routeLauncherDetail, status = copy.open, enabled = true)
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

internal data class SettingsOpenSourceLicensesPayloadCopy(
    val title: String = "Open Source Licenses",
    val detail: (Int, String) -> String = { count, source -> "$count libraries from $source" },
    val defaultSource: String = "Android release runtime",
    val sectionLicenses: String = "Licenses",
    val loading: String = "Loading",
    val loadingDetail: String = "Loading generated Android package license metadata.",
    val wait: String = "Wait",
    val unavailable: String = "Unavailable",
    val emptyTitle: String = "No licenses found",
    val emptyDetail: String = "The generated Android package license asset did not include any dependencies.",
    val empty: String = "Empty",
    val libraries: String = "Libraries",
    val numberedLibraries: (Int) -> String = { section -> "Libraries $section" },
    val licenseUnknown: String = "License unknown",
    val licenseLink: String = "License link",
    val sourceLink: String = "Source link",
    val noLink: String = "No link",
    val license: String = "License",
    val source: String = "Source"
)

@Composable
private fun settingsOpenSourceLicensesPayloadCopy(): SettingsOpenSourceLicensesPayloadCopy {
    val detailTemplate = stringResource(MR.strings.settings_open_source_licenses_detail_libraries_from)
    val numberedLibrariesTemplate = stringResource(MR.strings.settings_open_source_licenses_section_libraries_numbered)
    return SettingsOpenSourceLicensesPayloadCopy(
        title = stringResource(MR.strings.settings_open_source_licenses_title),
        detail = { count, source -> detailTemplate.replaceLocalizedFormatArgs(count, source) },
        defaultSource = stringResource(MR.strings.settings_open_source_licenses_default_source),
        sectionLicenses = stringResource(MR.strings.settings_licenses_title),
        loading = stringResource(MR.strings.settings_open_source_licenses_state_loading),
        loadingDetail = stringResource(MR.strings.settings_open_source_licenses_loading_detail),
        wait = stringResource(MR.strings.settings_open_source_licenses_state_wait),
        unavailable = stringResource(MR.strings.settings_open_source_licenses_action_unavailable),
        emptyTitle = stringResource(MR.strings.settings_open_source_licenses_empty_title),
        emptyDetail = stringResource(MR.strings.settings_open_source_licenses_empty_detail),
        empty = stringResource(MR.strings.settings_open_source_licenses_state_empty),
        libraries = stringResource(MR.strings.settings_open_source_licenses_section_libraries),
        numberedLibraries = { section -> numberedLibrariesTemplate.replaceLocalizedFormatArgs(section) },
        licenseUnknown = stringResource(MR.strings.settings_open_source_licenses_license_unknown),
        licenseLink = stringResource(MR.strings.settings_open_source_licenses_license_link),
        sourceLink = stringResource(MR.strings.settings_open_source_licenses_source_link),
        noLink = stringResource(MR.strings.settings_open_source_licenses_no_link),
        license = stringResource(MR.strings.settings_open_source_licenses_action_license),
        source = stringResource(MR.strings.settings_open_source_licenses_action_source)
    )
}

internal fun ScreenLoadState<SharedSettingsOpenSourceLicensesSnapshot>.toOpenSourceLicensesPayload(
    copy: SettingsOpenSourceLicensesPayloadCopy = SettingsOpenSourceLicensesPayloadCopy()
): KuiklySharedSettingsPayload {
    val snapshot = (this as? ScreenLoadState.Content<SharedSettingsOpenSourceLicensesSnapshot>)?.data
    val detail =
        when (this) {
            is ScreenLoadState.Content ->
                copy.detail(
                    snapshot?.items.orEmpty().size,
                    snapshot?.sourceConfiguration.orEmpty().ifBlank { copy.defaultSource }
                )
            is ScreenLoadState.Error -> error.settingsUserFacingMessage()
            is ScreenLoadState.Empty -> reason.settingsUserFacingMessage()
            is ScreenLoadState.NoPermission -> blocker.settingsUserFacingMessage()
            ScreenLoadState.Loading -> copy.loadingDetail
        }
    val sections =
        when {
            snapshot == null ->
                listOf(
                    KuiklySharedSettingsSection(
                        title = copy.sectionLicenses,
                        rows =
                            listOf(
                                KuiklySharedSettingsRow(
                                    key = "loading",
                                    iconName = SlockLucideIconName.LoaderCircle,
                                    title = if (this == ScreenLoadState.Loading) copy.loading else copy.unavailable,
                                    detail = detail,
                                    status = if (this == ScreenLoadState.Loading) copy.wait else copy.unavailable
                                )
                            )
                    )
                )
            snapshot.items.isEmpty() ->
                listOf(
                    KuiklySharedSettingsSection(
                        title = copy.sectionLicenses,
                        rows =
                            listOf(
                                KuiklySharedSettingsRow(
                                    key = "empty",
                                    iconName = SlockLucideIconName.FileText,
                                    title = copy.emptyTitle,
                                    detail = copy.emptyDetail,
                                    status = copy.empty
                                )
                            )
                    )
                )
            else ->
                snapshot.items
                    .sortedWith(compareBy({ it.displayName.ifBlank { it.name }.lowercase() }, { it.version }))
                    .chunked(32)
                    .mapIndexed { sectionIndex, items ->
                        KuiklySharedSettingsSection(
                            title = if (sectionIndex == 0) copy.libraries else copy.numberedLibraries(sectionIndex + 1),
                            rows =
                                items.map { item ->
                                    val title = item.displayName.ifBlank { item.name.ifBlank { item.id } }
                                    val link = item.licenseUrl.ifBlank { item.sourceUrl }
                                    KuiklySharedSettingsRow(
                                        key = "openLicenseUrl",
                                        iconName = SlockLucideIconName.Package,
                                        title = title,
                                        detail = item.settingsLicenseCoordinates(),
                                        value = link,
                                        status = item.settingsLicenseActionLabel(copy),
                                        enabled = link.isNotBlank(),
                                        supportingDetail = item.settingsLicenseMetaLine(copy)
                                    )
                                }
                        )
                    }
        }
    return KuiklySharedSettingsPayload(
        instanceId = "shared-shell-settings-open-source-licenses",
        title = copy.title,
        heroIconName = SlockLucideIconName.FileText,
        heroLabel = copy.title,
        detail = detail,
        sections = sections
    )
}

private fun SharedSettingsOpenSourceLicenseItem.settingsLicenseCoordinates(): String =
    listOf(group, name, version)
        .filter { it.isNotBlank() }
        .joinToString(":")
        .ifBlank { id }

private fun SharedSettingsOpenSourceLicenseItem.settingsLicenseMetaLine(copy: SettingsOpenSourceLicensesPayloadCopy): String =
    listOf(
        licenseName.ifBlank { copy.licenseUnknown },
        when {
            licenseUrl.isNotBlank() -> copy.licenseLink
            sourceUrl.isNotBlank() -> copy.sourceLink
            else -> copy.noLink
        }
    ).joinToString(" / ")

private fun SharedSettingsOpenSourceLicenseItem.settingsLicenseActionLabel(copy: SettingsOpenSourceLicensesPayloadCopy): String =
    when {
        licenseUrl.isNotBlank() -> copy.license
        sourceUrl.isNotBlank() -> copy.source
        else -> copy.unavailable
    }

internal fun List<SharedSettingsDiagnosticsEntry>.toDiagnosticsPayload(
    state: ScreenLoadState<SharedSettingsDiagnosticsSnapshot>,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    copy: SettingsDiagnosticsPayloadCopy = SettingsDiagnosticsPayloadCopy()
): KuiklySharedSettingsPayload {
    val diagnosticsExportAvailable = state is ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>
    val snapshot = (state as? ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>)?.data
    val profiler = snapshot?.profiler
        ?: SharedSettingsRecompositionProfilerSnapshot()
    val profilerRunning = profiler.statusLabel == "Running"
    val profilerBusy = actionState?.action in profilerActionKeys && actionState?.inProgress == true
    val environmentRows =
        snapshot?.about?.toDiagnosticsEnvironmentRows(copy).orEmpty()
    val loadStatusRows =
        snapshot?.loadErrorMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { errorMessage ->
                listOf(
                    KuiklySharedSettingsRow(
                        key = "diagnosticsLoadStatus",
                        iconName = SlockLucideIconName.TriangleAlert,
                        title = copy.partialDiagnostics,
                        detail = errorMessage,
                        status = copy.partial
                    )
                )
            }
            .orEmpty()
    val profilerControlSection =
        KuiklySharedSettingsSection(
            title = copy.profiler,
            rows =
                if (profiler.supported) {
                    listOf(
                            KuiklySharedSettingsRow(
                                "profilerStatus",
                                SlockLucideIconName.Activity,
                                copy.profilerStatus,
                                profiler.detail,
                                status = profiler.statusLabel
                            ),
                            KuiklySharedSettingsRow(
                                DIAGNOSTICS_ACTION_PROFILER_ENABLED,
                                SlockLucideIconName.Activity,
                                copy.profiler,
                                copy.profilerDetail,
                                value = (!profiler.enabled).toString(),
                                status =
                                    when {
                                        actionState.isDiagnosticsActionLoading(DIAGNOSTICS_ACTION_PROFILER_ENABLED) -> copy.loading
                                        profiler.enabled && profilerRunning -> copy.on
                                        profiler.enabled -> copy.pending
                                        else -> copy.off
                                    },
                                enabled = !profilerBusy,
                                toggleValue = profiler.enabled
                            ),
                            KuiklySharedSettingsRow(
                                "resetProfiler",
                                SlockLucideIconName.RotateCcw,
                                copy.resetProfiler,
                                copy.resetProfilerDetail,
                                status =
                                    when {
                                        actionState.isDiagnosticsActionLoading(DIAGNOSTICS_ACTION_PROFILER_RESET) -> copy.loading
                                        profilerRunning -> copy.reset
                                        else -> copy.wait
                                    },
                                enabled = profilerRunning && !profilerBusy
                            ),
                            KuiklySharedSettingsRow(
                                "refreshProfilerReport",
                                SlockLucideIconName.RefreshCw,
                                copy.refreshProfiler,
                                copy.refreshProfilerDetail,
                                status =
                                    when {
                                        actionState.isDiagnosticsActionLoading(DIAGNOSTICS_ACTION_PROFILER_REPORT) -> copy.loading
                                        profiler.report.reportAvailable -> copy.refresh
                                        else -> copy.pull
                                    },
                                enabled = !profilerBusy
                            ),
                            KuiklySharedSettingsRow(
                                "exportProfilerReport",
                                SlockLucideIconName.FileCode,
                                copy.exportProfiler,
                                copy.exportProfilerDetail,
                                status =
                                    when {
                                        actionState.isDiagnosticsActionLoading(DIAGNOSTICS_ACTION_PROFILER_EXPORT) -> copy.loading
                                        profiler.report.reportAvailable -> copy.export
                                        else -> copy.write
                                    },
                                enabled = !profilerBusy
                            )
                        )
                } else {
                    listOf(
                        KuiklySharedSettingsRow(
                            "profilerStatus",
                            SlockLucideIconName.Activity,
                            copy.profilerStatus,
                            profiler.detail,
                            status = profiler.gateLabel
                        ),
                        KuiklySharedSettingsRow(
                            DIAGNOSTICS_ACTION_PROFILER_ENABLED,
                            SlockLucideIconName.Activity,
                            copy.profiler,
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
        title = copy.title,
        detail =
            profiler.actionFeedback
                ?: if (profiler.supported) {
                    copy.detailProfiler
                } else if (profiler.detail.isNotBlank()) {
                    copy.detailStatus
                } else {
                    copy.detailBasic
                },
        heroIconName = SlockLucideIconName.ClipboardList,
        heroLabel = copy.title,
        sections =
            listOfNotNull(
                environmentRows.takeIf { it.isNotEmpty() }?.let {
                    KuiklySharedSettingsSection(title = copy.environment, rows = it)
                },
                loadStatusRows.takeIf { it.isNotEmpty() }?.let {
                    KuiklySharedSettingsSection(title = copy.loadStatus, rows = it)
                }
            ) + listOf(profilerControlSection) + listOf(
                KuiklySharedSettingsSection(
                    title = copy.actions,
                    rows =
                        listOf(
                            KuiklySharedSettingsRow("shareLogs", SlockLucideIconName.Share, copy.exportLogs, copy.exportLogsDetail, status = if (diagnosticsExportAvailable) copy.export else copy.disabled, enabled = diagnosticsExportAvailable),
                            KuiklySharedSettingsRow("feedback", SlockLucideIconName.MessageSquarePlus, copy.sendDiagnostics, copy.sendDiagnosticsDetail, status = copy.send, enabled = true)
                        )
                )
            )
    )
}

private fun SharedSettingsAboutSnapshot.toDiagnosticsEnvironmentRows(copy: SettingsDiagnosticsPayloadCopy): List<KuiklySharedSettingsRow> =
    listOf(
        KuiklySharedSettingsRow("diagnosticsApp", SlockLucideIconName.Info, copy.app, appName.ifBlank { "Raft" }, status = versionLabel.ifBlank { copy.version }),
        KuiklySharedSettingsRow("diagnosticsBuild", SlockLucideIconName.GitBranch, copy.build, buildLabel.ifBlank { copy.unknown }, status = sourceLabel.ifBlank { copy.source }),
        KuiklySharedSettingsRow("diagnosticsVersionCode", SlockLucideIconName.BadgeInfo, copy.versionCode, versionCodeLabel.ifBlank { buildLabel.ifBlank { copy.unknown } }, status = sdkLabel.ifBlank { copy.sdk }),
        KuiklySharedSettingsRow("diagnosticsPackage", SlockLucideIconName.Package, copy.packageLabel, packageName.ifBlank { copy.unknown }, status = copy.host),
        KuiklySharedSettingsRow("diagnosticsPlatform", SlockLucideIconName.Monitor, copy.platform, platformLabel.ifBlank { copy.unknown }, status = deviceLabel.ifBlank { copy.device }),
        KuiklySharedSettingsRow("diagnosticsDeviceFacts", SlockLucideIconName.Smartphone, copy.deviceFacts, modelLabel.ifBlank { deviceLabel.ifBlank { copy.unknown } }, status = screenLabel.ifBlank { copy.screen }),
        KuiklySharedSettingsRow("diagnosticsDisplay", SlockLucideIconName.Monitor, copy.display, densityLabel.ifBlank { copy.density }, status = fontScaleLabel.ifBlank { copy.fontScale }),
        KuiklySharedSettingsRow("diagnosticsInstallTime", SlockLucideIconName.Clock, copy.installTime, installTimeLabel.ifBlank { "--" }, status = copy.host),
        KuiklySharedSettingsRow("diagnosticsSession", SlockLucideIconName.User, copy.session, signedInUserLabel.ifBlank { copy.noSignedInUser }, status = sessionStatusLabel.ifBlank { copy.unknown }),
        KuiklySharedSettingsRow("diagnosticsServer", SlockLucideIconName.Server, copy.selectedServer, selectedServerLabel.ifBlank { copy.unknown }, status = socketStatusLabel.ifBlank { copy.socket })
    )

@Composable
private fun settingsDestinationTitle(destination: String): String =
    when (destination) {
        SettingsDestinationAccount -> stringResource(MR.strings.settings_account_title)
        SettingsDestinationComputers -> stringResource(MR.strings.settings_computers_title)
        SettingsDestinationServer -> stringResource(MR.strings.settings_server_profile_title)
        SettingsDestinationAdministration -> stringResource(MR.strings.settings_server_administration_title)
        SettingsDestinationConnectedApps -> stringResource(MR.strings.settings_connected_apps_title)
        SettingsDestinationBilling -> stringResource(MR.strings.settings_billing_title)
        SettingsDestinationLanguageRegion -> stringResource(MR.strings.settings_language_region_title)
        SettingsDestinationAppearance -> stringResource(MR.strings.settings_appearance_title)
        SettingsDestinationNotifications -> stringResource(MR.strings.settings_notifications_title)
        SettingsDestinationMobile -> stringResource(MR.strings.settings_mobile_title)
        SettingsDestinationStorage -> stringResource(MR.strings.settings_storage_title)
        SettingsDestinationAbout -> stringResource(MR.strings.settings_about_title)
        SettingsDestinationOpenSourceLicenses -> stringResource(MR.strings.settings_open_source_licenses_title)
        SettingsDestinationReleaseNotes -> stringResource(MR.strings.settings_release_notes_title)
        SettingsDestinationDeveloper -> stringResource(MR.strings.settings_developer_title)
        SettingsDestinationApiEnvironment -> stringResource(MR.strings.settings_developer_api_environment)
        SettingsDestinationDiagnostics -> stringResource(MR.strings.settings_diagnostics_title)
        SettingsDestinationDiagnosticsFeedbackChannel -> stringResource(MR.strings.settings_diagnostics_feedback_channel_title)
        SettingsDestinationQuiverFeedback -> stringResource(MR.strings.settings_feedback_title)
        else -> stringResource(MR.strings.settings_title)
    }

@Composable
private fun settingsDestinationSubtitle(destination: String): String =
    when (destination) {
        SettingsDestinationAccount -> stringResource(MR.strings.settings_account_destination_subtitle)
        SettingsDestinationComputers -> stringResource(MR.strings.settings_computers_subtitle)
        SettingsDestinationServer -> stringResource(MR.strings.settings_server_profile_subtitle)
        SettingsDestinationAdministration -> stringResource(MR.strings.settings_server_administration_subtitle)
        SettingsDestinationConnectedApps -> stringResource(MR.strings.settings_connected_apps_subtitle)
        SettingsDestinationBilling -> stringResource(MR.strings.settings_billing_subtitle)
        SettingsDestinationLanguageRegion -> stringResource(MR.strings.settings_language_region_subtitle)
        SettingsDestinationAppearance -> stringResource(MR.strings.settings_appearance_subtitle)
        SettingsDestinationNotifications -> stringResource(MR.strings.settings_notifications_subtitle)
        SettingsDestinationMobile -> stringResource(MR.strings.settings_mobile_subtitle)
        SettingsDestinationStorage -> stringResource(MR.strings.settings_storage_subtitle)
        SettingsDestinationAbout -> stringResource(MR.strings.settings_about_detail)
        SettingsDestinationOpenSourceLicenses -> stringResource(MR.strings.settings_about_licenses_detail)
        SettingsDestinationReleaseNotes -> stringResource(MR.strings.settings_release_notes_subtitle)
        SettingsDestinationDeveloper -> stringResource(MR.strings.settings_developer_subtitle)
        SettingsDestinationApiEnvironment -> stringResource(MR.strings.settings_developer_api_environment_subtitle)
        SettingsDestinationDiagnostics -> stringResource(MR.strings.settings_diagnostics_subtitle)
        SettingsDestinationDiagnosticsFeedbackChannel -> stringResource(MR.strings.settings_diagnostics_feedback_channel_subtitle)
        SettingsDestinationQuiverFeedback -> stringResource(MR.strings.settings_feedback_subtitle)
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
        SettingsDestinationOpenSourceLicenses -> SlockLucideIconName.FileText
        SettingsDestinationReleaseNotes -> SlockLucideIconName.FileText
        SettingsDestinationDeveloper -> SlockLucideIconName.Terminal
        SettingsDestinationApiEnvironment -> SlockLucideIconName.SlidersHorizontal
        SettingsDestinationDiagnostics -> SlockLucideIconName.Clipboard
        SettingsDestinationDiagnosticsFeedbackChannel -> SlockLucideIconName.Hash
        SettingsDestinationQuiverFeedback -> SlockLucideIconName.MessageSquarePlus
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
        SettingsDestinationOpenSourceLicenses -> SharedSettingsDestination.OpenSourceLicenses
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
    val sourceStoreScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
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
    var errorPresentation by remember { mutableStateOf<AuthGateMessage?>(null) }
    var restoreAttempt by remember { mutableStateOf(0) }
    val errorMessage = errorPresentation.localizedMessage()

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
                errorPresentation =
                    if (restored == null) {
                        AuthGateMessage.Static(MR.strings.auth_error_choose_server_sign_in_required.desc())
                    } else {
                        null
                    }
            }
            .onFailure {
                session = null
                authDataSource.logDiagnostics(
                    "auth_choose_server_page_session result=failure restoreAttempt=$restoreAttempt"
                )
                errorPresentation = AuthGateMessage.Static(MR.strings.auth_error_restore_session.desc())
            }
    }

    when (val restoredSession = session) {
        null -> {
            if (errorMessage == null) {
                AppShellRestoreSplashContent()
            } else {
                AppShellAuthErrorContent(
                    message =
                        errorMessage
                            ?: stringResource(MR.strings.auth_error_choose_server_sign_in_required),
                    onRetry = {
                        errorPresentation = null
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
                onReady = { readySession ->
                    bridge.realtimeDisconnect("server_changed")
                    pager.openAppShellReplacingCurrentPage(
                        pageData =
                            appShellPageDataForServerSelection(
                                sourceStoreScope = sourceStoreScope,
                                selectedServerId = readySession.selectedServerId,
                                suppressRestoreLanding = true
                            )
                    )
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

@Composable
internal fun AuthEmailVerificationVisualContent() {
    val session =
        remember {
            authChooseServerVisualSession().let { base ->
                base.copy(user = base.user.copy(emailVerified = false))
            }
        }
    val store = remember(session) { InMemorySlockAuthSessionStore(session) }
    val authDataSource =
        remember(store, session) {
            SharedAuthDataSource(
                api = AuthChooseServerVisualApi(session.user, session.servers),
                sessionStore = store
            )
        }
    AppShellEmailVerificationGateContent(
        session = session,
        authDataSource = authDataSource,
        onRefresh = {},
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

    override suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun forgotPassword(email: String): AuthApiResult<Unit> =
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

private fun Pager.openAppShellDeveloper() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            AUTH_ABOUT_PAGE,
            JSONObject().put("pageName", AUTH_ABOUT_PAGE)
        )
}

private fun Pager.openAuthChooseServerPage(
    storeScope: SlockShellStoreScope
) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            AUTH_CHOOSE_SERVER_PAGE,
            authChooseServerPageData(
                replaceCurrent = false,
                fromShell = true,
                storeScope = storeScope
            )
        )
}

private fun Pager.openAppShellReplacingCurrentPage(pageData: JSONObject) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(SLOCK_APP_SHELL_PAGE, pageData)
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
    skipInitialLoad: Boolean = false,
    storeScope: SlockShellStoreScope? = null
): JSONObject =
    slockAuthRoutePageData(AUTH_CHOOSE_SERVER_PAGE, replaceCurrent)
        .apply {
            if (fromShell) {
                put(AUTH_CHOOSE_SERVER_FROM_SHELL, true)
            }
            if (skipInitialLoad) {
                put(AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD, true)
            }
            storeScope?.let { scope ->
                put(SLOCK_SHELL_INSTANCE_ID, scope.shellInstanceId)
                scope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
                put(SLOCK_DOCUMENT_SHELL, false)
            }
        }

internal fun appShellPageDataForServerSelection(
    sourceStoreScope: SlockShellStoreScope,
    selectedServerId: String?,
    suppressRestoreLanding: Boolean = false
): JSONObject {
    val normalizedServerId = selectedServerId?.trim()?.takeIf { it.isNotBlank() }
    return appShellPageData(
        replaceCurrent = true,
        suppressRestoreLanding = suppressRestoreLanding
    ).apply {
        normalizedServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
        put(SLOCK_SHELL_INSTANCE_ID, sourceStoreScope.shellInstanceId)
        put(SLOCK_DOCUMENT_SHELL, false)
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
                errorPresentation = AuthGateMessage.Static(MR.strings.auth_error_load_servers.desc())
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
                    "create" -> stringResource(MR.strings.auth_server_title_create)
                    "empty" ->
                        if (loadingServers) {
                            stringResource(MR.strings.auth_server_title_checking)
                        } else {
                            stringResource(MR.strings.auth_server_title_first)
                        }
                    else -> stringResource(MR.strings.auth_server_title_choose)
                },
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Text(
            text =
                StringDesc.ResourceFormatted(
                    MR.strings.auth_server_signed_in_as,
                    listOf(latestSession.user.bestDisplayName)
                ).localized(),
            style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthFormColumn {
            AuthError(errorMessage)
            if (showPinnedSignOut) {
                AuthActionButton(
                    text = stringResource(MR.strings.auth_action_sign_out),
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
                    text = stringResource(MR.strings.auth_server_loading),
                    style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    textAlign = TextAlign.Center
                )
            } else if (latestSession.servers.isEmpty() && errorMessage != null) {
                AuthActionButton(
                    text = stringResource(MR.strings.auth_action_retry),
                    onClick = {
                        errorPresentation = null
                        loadAttempt += 1
                    },
                    tone = AuthButtonTone.Pink,
                    enabled = !loading
                )
            } else if (mode == "create") {
                Text(
                    text = stringResource(MR.strings.auth_server_create_hint),
                    style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
                AuthTextField(
                    value = createName,
                    onValueChange = {
                        createName = it
                        // Web parity (ServerSelector.tsx): the slug mirrors the
                        // name until the user edits the slug directly.
                        if (!createSlugTouched) {
                            createSlug = authCreateServerSlugFromName(it)
                        }
                    },
                    label = stringResource(MR.strings.auth_label_server_name),
                    required = true,
                    enabled = !loading
                )
                AuthTextField(
                    value = createSlug,
                    onValueChange = {
                        createSlugTouched = true
                        createSlug = authCreateServerSlugFromName(it)
                    },
                    label = stringResource(MR.strings.auth_label_server_slug),
                    placeholder = "my-team",
                    enabled = !loading
                )
                AuthActionButton(
                    text =
                        if (loading) {
                            stringResource(MR.strings.auth_state_creating_server)
                        } else {
                            stringResource(MR.strings.auth_action_create_server)
                        },
                    onClick = {
                        loading = true
                        errorPresentation = null
                        pendingServerId = "__create__"
                    },
                    tone = AuthButtonTone.Pink,
                    enabled = createName.isNotBlank() && !loading,
                    loading = loading
                )
                AuthActionButton(
                    text =
                        if (latestSession.servers.isEmpty()) {
                            stringResource(MR.strings.auth_action_back)
                        } else {
                            stringResource(MR.strings.auth_action_back_to_servers)
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
                    SlockBrutalListRow(
                        title = server.name,
                        subtitle =
                            if (pendingServerId == server.id) {
                                stringResource(MR.strings.auth_state_opening_server)
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
                    text = stringResource(MR.strings.auth_action_create_new_server),
                    onClick = { mode = "create" },
                    tone = AuthButtonTone.Yellow,
                    enabled = !loading
                )
            }
            if (!showPinnedSignOut) {
                SlockLinkButton(
                    text = stringResource(MR.strings.auth_action_sign_out),
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
                errorPresentation = AuthGateMessage.Dynamic(result.message)
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

// Web parity: ServerSelector.tsx `toSlug` — lowercase, then every character
// outside [a-z0-9-] becomes "-" (no collapsing, no trimming). The create form
// mirrors the name into the slug through this until the slug is hand-edited,
// and normalizes direct slug input through it too.
private val AuthCreateServerSlugDisallowed = Regex("[^a-z0-9-]")

internal fun authCreateServerSlugFromName(value: String): String =
    value.lowercase().replace(AuthCreateServerSlugDisallowed, "-")

internal fun serverGateUnauthenticatedMessage(): AuthGateMessage =
    AuthGateMessage.Static(
        MR.strings.auth_error_session_refresh_choose_server.desc(),
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
            text = stringResource(MR.strings.auth_server_empty_hint),
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthActionButton(
            text = stringResource(MR.strings.auth_action_create_new_server),
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
            text = stringResource(MR.strings.auth_title_session_error),
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        AuthError(message)
        AuthActionButton(text = stringResource(MR.strings.auth_action_retry), onClick = onRetry, tone = AuthButtonTone.Pink)
        AuthActionButton(text = stringResource(MR.strings.auth_action_sign_in), onClick = onSignIn, tone = AuthButtonTone.White)
    }
}

@Composable
private fun AppShellEmailVerificationGateContent(
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
                sentPresentation = AuthGateMessage.Static(MR.strings.auth_notice_verification_email_sent.desc())
                authDataSource.logDiagnostics("auth_resend_verification_result result=success")
            }
            is SharedAuthResult.Failure -> {
                sentPresentation = null
                errorPresentation = AuthGateMessage.Dynamic(result.message)
                authDataSource.logDiagnostics("auth_resend_verification_result result=failure code=${result.code}")
            }
            SharedAuthResult.Unauthenticated -> {
                sentPresentation = null
                errorPresentation = AuthGateMessage.Static(MR.strings.auth_error_session_refresh.desc())
                authDataSource.logDiagnostics("auth_resend_verification_result result=unauthenticated")
            }
            null -> {
                sentPresentation = null
                errorPresentation = AuthGateMessage.Static(MR.strings.auth_error_verification_email_send.desc())
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
            text = stringResource(MR.strings.auth_title_verify_email),
            style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Text(
            text =
                if (accountEmail != null) {
                    StringDesc.ResourceFormatted(
                        MR.strings.auth_hint_verify_email_address,
                        listOf(accountEmail)
                    ).localized()
                } else {
                    stringResource(MR.strings.auth_hint_verify_email)
                },
            style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthFormColumn {
            sentMessage?.let {
                AuthSuccessNotice(
                    title = stringResource(MR.strings.auth_title_email_sent),
                    detail = it
                )
            }
            AuthError(errorMessage)
            AuthActionButton(
                text = stringResource(MR.strings.auth_action_resend_verification),
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
                text = stringResource(MR.strings.auth_action_verified_refresh),
                onClick = onRefresh,
                tone = AuthButtonTone.White,
                enabled = !loading
            )
            SlockLinkButton(
                text = stringResource(MR.strings.auth_action_sign_out),
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
    onRetry: () -> Unit,
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
                slockLogWarn("SlockAgentPanelTabOrder", "shared_members_agent_tab_order_load_failed code=${result.error.code}")
                null
            }
            is DataResult.NoPermission -> {
                slockLogWarn("SlockAgentPanelTabOrder", "shared_members_agent_tab_order_load_blocked code=${result.blocker.code}")
                null
            }
        }
    }
    LaunchedEffect(agentPanelTabOrderStore, currentServerId) {
        agentPanelTabOrderStore.bindServer(
            serverId = currentServerId,
            source = "shared_members_bind_server",
            logger = slockLogger("SlockAgentPanelTabOrder")
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
                logger = slockLogger("SlockAgentPanelTabOrder")
            )
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
                code = current.code,
                onRetry = onRetry
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
                mutableStateOf<Map<Pair<String, String>, SharedCreateAgentRuntimeModelCatalog>>(emptyMap())
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
                            bridge.toast("Failed to load join link: ${result.error.settingsUserFacingMessage()}")
                        is DataResult.Empty -> Unit
                    }
                }
            }
            val inviteHumanSubmitHandler: suspend (List<String>) -> DataResult<Unit> =
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
            val inviteHumanSubmit: (suspend (List<String>) -> DataResult<Unit>)? =
                inviteHumanSubmitHandler.takeIf { membersPayload.canInviteHumans }
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
                                            logger = slockLogger("SlockAgentPanelTabOrder")
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
                            bridge.toast("Unable to open profile")
                            bridge.log("members_detail_open_skip reason=missing_member_id source=app_shell")
                        } else {
                            agentDetailStore.dispatch(SharedAgentDetailIntent.SeedDetailFromMember(member))
                            pager.openSlockPage(
                                pageName = MEMBER_DETAIL_PAGE,
                                pageData = pageData,
                                onMissingScope = {
                                    bridge.toast("Unable to open profile")
                                    bridge.log("members_detail_open_skip reason=missing_shell_scope source=app_shell")
                                }
                            )
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
    val agentStatus = AgentStatus.fromWireValue(profile.string("agentStatus", "activity", "status") ?: string("agentStatus", "activity", "status"))
    return MemberProfile(
        id = MemberId(id),
        displayName = displayName,
        handle = handle,
        kind = kind,
        description = profile.string("description") ?: string("description"),
        roleDescription = profile.string("roleDescription", "role") ?: string("roleDescription", "role"),
        avatarUrl = avatarWireValue?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
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
    externalFocusLossDismissEpoch: Int = 0,
    selectedConversationId: String? = null,
    onSelectedConversationCleared: () -> Unit = {},
    onOpenSearch: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenComputers: () -> Unit = {},
    onOpenServerSelection: () -> Unit,
    onCreateChannel: () -> Unit,
    onOpenConversation: (ThreadLaunchConversationIdentity) -> Unit,
    serverMembersStore: SharedServerMembersStore? = null,
    channelRepository: ChannelRepository? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    listPositionServerId: String? = null,
    initialListPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    onListPositionSnapshot: (String?, AppShellLazyListPosition) -> Unit = { _, _ -> },
    onContentComposeCommitted: (String) -> Unit = {}
) {
    val pinnedMemberOpenScope = rememberCoroutineScope()
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
                    loadingSkeletons = true,
                    onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                    onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                    onServerSelected = { serverId ->
                        onSelectedConversationCleared()
                        onIntent(SharedHomeIntent.SelectServer(serverId))
                    },
                    onServerOrderChange = { order ->
                        onIntent(SharedHomeIntent.ReorderServers(order))
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
                onServerOrderChange = { order ->
                    onIntent(SharedHomeIntent.ReorderServers(order))
                },
                onCreateServerClick = onOpenServerSelection,
                onCreateChannelClick = onCreateChannel
            )
        is SharedHomeContentState.Error ->
            if (current.session != null) {
                SharedHomeSessionShell(
                    session = current.session,
                    serverSwitcherExpanded = serverSwitcherExpanded,
                    serverUnreadCounts = state.serverUnreadCounts,
                    prefersChineseCommunity = prefersChineseCommunity,
                    bottomContentPadding = bottomContentPadding,
                    inlineError = localizedSharedHomeErrorDisplay(current.code),
                    onInlineErrorAction = { onIntent(SharedHomeIntent.Refresh) },
                    onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                    onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                    onServerSelected = { serverId ->
                        onSelectedConversationCleared()
                        onIntent(SharedHomeIntent.SelectServer(serverId))
                    },
                    onServerOrderChange = { order ->
                        onIntent(SharedHomeIntent.ReorderServers(order))
                    },
                    onCreateServerClick = onOpenServerSelection,
                    onCreateChannelClick = onCreateChannel
                )
            } else {
                val error = localizedSharedHomeErrorDisplay(current.code)
                PageErrorContent(
                    title = "Home",
                    subtitle = "Workspace",
                    iconName = SlockLucideIconName.House,
                    message = error.title,
                    code = current.code,
                    onRetry = { onIntent(SharedHomeIntent.Refresh) }
                )
            }
        is SharedHomeContentState.Content -> {
            val snapshot = current.snapshot
            SideEffect {
                onContentComposeCommitted(snapshot.server.id.value)
            }
            val agentStatusResolver =
                remember(serverMembersStore, snapshot.server.id.value) {
                    slockAgentStatusResolver(serverMembersStore, snapshot.server.id.value)
                }
            val memberAvatarResolver =
                remember(serverMembersStore, snapshot.server.id.value) {
                    slockMemberAvatarResolver(serverMembersStore, snapshot.server.id.value)
                }
            val serverMembersState by (serverMembersStore?.state ?: flowOf(SharedServerMembersState()))
                .collectAsState(SharedServerMembersState())
            val pinnedMemberProfileResolver: (String) -> SlockHomePinnedMemberItem? =
                remember(serverMembersState, snapshot.server.id) {
                    { memberId ->
                        serverMembersState
                            .memberProfile(snapshot.server.id, memberId)
                            ?.toSlockHomePinnedMemberItem()
                    }
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
                                snapshot.server.copy(
                                    avatarUrl = snapshot.server.avatarUrl ?: summary.avatarUrl
                                )
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
            val notificationItems = state.notifications.map { it.toHomeNotificationItem() }
            val conversationsByThreadId =
                remember(snapshot.channels, snapshot.directMessages) {
                    (snapshot.channels + snapshot.directMessages).associateBy { it.threadId.value }
                }
            val homeActivityUnreadCount = homeActivitySidebarUnreadCount(
                activityUnreadCount = activityUnreadCount
            )
            val homeShellLabels = SlockHomeShellLabels.localized()
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
                channelOrder = snapshot.sidebarOrder?.channelOrder.orEmpty(),
                channelSortMode = snapshot.sidebarOrder?.channelSortMode ?: "manual",
                dmOrder = snapshot.sidebarOrder?.dmOrder.orEmpty(),
                dmSortMode = snapshot.sidebarOrder?.dmSortMode ?: "manual",
                pinnedChannelIds = snapshot.sidebarOrder?.pinnedChannelIds.orEmpty(),
                pinnedAgentIds = snapshot.sidebarOrder?.pinnedAgentIds.orEmpty(),
                pinnedOrder = snapshot.sidebarOrder?.pinnedOrder.orEmpty(),
                pinnedItems = snapshot.sidebarOrder?.pinned.orEmpty(),
                pinnedSortMode = snapshot.sidebarOrder?.pinnedSortMode ?: "manual",
                hiddenDmIds = snapshot.sidebarOrder?.hiddenDmIds.orEmpty(),
                canArchiveChannels = snapshot.server.permissions.canManageSettings,
                showBottomBar = false,
                externalFocusLossDismissEpoch = externalFocusLossDismissEpoch,
                bottomContentPadding = bottomContentPadding,
                outerPadding = 0.dp,
                labels = homeShellLabels,
                onServerBadgeClick = { onIntent(SharedHomeIntent.ToggleServerSwitcher) },
                onServerSwitcherDismiss = { onIntent(SharedHomeIntent.DismissServerSwitcher) },
                onServerSelected = { serverId ->
                    onSelectedConversationCleared()
                    onIntent(SharedHomeIntent.SelectServer(serverId))
                },
                onServerOrderChange = { order ->
                    onIntent(SharedHomeIntent.ReorderServers(order))
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
                        notification.id.isMachineHomeNotificationId() ->
                            onOpenComputers()
                        notification.targetId?.startsWith("http://") == true ||
                            notification.targetId?.startsWith("https://") == true -> {
                            notification.targetId?.let(bridge::openUrl)
                        }
                        else -> Unit
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
                        onOpenConversation(conversation.toThreadLaunchConversationIdentity())
                        onIntent(SharedHomeIntent.SelectConversation(threadId))
                    }
                },
                onPinnedMemberClick = { identity ->
                    val repository = channelRepository
                    if (repository == null) {
                        bridge.toast("Direct message is unavailable")
                    } else {
                        pinnedMemberOpenScope.launch {
                            when (val result = repository.openPinnedMemberDirectMessage(identity)) {
                                is DataResult.Content -> {
                                    val conversation = result.data.conversation
                                    onOpenConversation(conversation.toThreadLaunchConversationIdentity())
                                    onIntent(SharedHomeIntent.SelectConversation(conversation.threadId.value))
                                    onIntent(SharedHomeIntent.Refresh)
                                }
                                is DataResult.Empty -> bridge.toast("Unable to open direct message")
                                is DataResult.Error -> bridge.toast("Failed to open direct message: ${result.error.code}")
                                is DataResult.NoPermission -> bridge.toast("You do not have permission to open this direct message.")
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
                onSidebarOrderChange = { target, order ->
                    onIntent(target.toReorderSidebarIntent(order))
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
                listPositionServerId = listPositionServerId ?: snapshot.server.id.value,
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
                text = stringResource(MR.strings.settings_diagnostics_feedback_channel_guidance),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
            SlockBrutalActionButton(
                label = stringResource(MR.strings.settings_state_refresh),
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
                        text = stringResource(MR.strings.settings_channels_loading),
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
                        text = stringResource(MR.strings.settings_channels_empty),
                        style = SlockType.Body.withSlockFontWeight(FontWeight.Bold),
                        color = SlockColor.TextBlack
                    )
                    Text(
                        text = stringResource(MR.strings.settings_channels_empty_detail),
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray
                    )
                }
            }
            else -> {
                SlockBrutalSectionLabel(label = stringResource(MR.strings.settings_diagnostics_feedback_channel_recent))
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

@Composable
private fun ConversationSummary.diagnosticsFeedbackChannelMeta(): String {
    val latest = lastMessageAt?.takeIf { it.isNotBlank() }
    val unread =
        unreadCount.takeIf { it > 0 }?.let {
            MR.strings.settings_diagnostics_feedback_channel_unread.format(listOf(it)).localized()
        }
    val sender = lastMessageSenderName?.takeIf { it.isNotBlank() }
    return listOfNotNull(latest, sender, unread)
        .joinToString(" · ")
        .ifBlank { stringResource(MR.strings.search_entity_channel) }
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

internal fun SettingsDiagnosticsFeedbackDraft.diagnosticsComposerMessage(
    seedText: String = DiagnosticsFeedbackComposerSeedText
): String = seedText

private fun WireThreadLaunchPayload.withDiagnosticsFeedbackDraft(
    draft: SettingsDiagnosticsFeedbackDraft,
    composerSeedText: String
): WireThreadLaunchPayload =
    copy(
        composerSeedText = draft.diagnosticsComposerMessage(composerSeedText),
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
    val trimmedName = name.trim()
    val nameError =
        if (trimmedName.isNotEmpty() && !trimmedName.isValidChannelName()) {
            stringResource(MR.strings.channel_create_error_name_format)
        } else {
            null
        }
    val canSubmit = trimmedName.isValidChannelName() && !submitting
    val selectableMembers = members.filter { it.kind == MemberKind.Human || it.kind == MemberKind.Agent }
    val filteredMembers =
        filterPeopleSuggestions(
            query = memberSearch,
            suggestions = selectableMembers,
            handle = { member -> member.handle },
            displayName = { member -> member.displayName },
            description = { member -> member.roleDescription },
            sourceServerLabel = { member -> member.serverName ?: member.serverSlug },
            legacyId = { member -> member.id.value }
        )
    val filteredAgentMembers = filteredMembers.filter { it.kind == MemberKind.Agent }
    val filteredHumanMembers = filteredMembers.filter { it.kind == MemberKind.Human }
    val hasFilteredMembers = filteredMembers.isNotEmpty()
    SlockUnifiedDialog(
        title = stringResource(MR.strings.channel_create_title),
        dismissEnabled = !submitting,
        onDismiss = onDismiss
    ) {
        SlockBrutalTextField(
            label = stringResource(MR.strings.channel_create_label_name),
            required = true,
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(MR.strings.channel_create_placeholder_name),
            enabled = !submitting,
            hint = stringResource(MR.strings.channel_create_hint_name_format),
            error = nameError,
            autoFocus = true
        )
        SlockBrutalTextField(
            label = stringResource(MR.strings.channel_create_label_description),
            optional = true,
            value = description,
            onValueChange = { description = it },
            placeholder = stringResource(MR.strings.channel_create_placeholder_description),
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
            val localizedError =
                if (errorMessage == "channel_name_invalid") {
                    stringResource(MR.strings.channel_create_error_name_format)
                } else {
                    errorMessage
                }
            Text(
                text = MR.strings.channel_create_error.format(listOf(localizedError)).localized(),
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
                label = stringResource(MR.strings.common_cancel),
                enabled = !submitting,
                backgroundColor = SlockColor.SurfaceWhite,
                onClick = onDismiss
            )
            SlockBrutalActionButton(
                label =
                    if (submitting) {
                        stringResource(MR.strings.channel_create_action_creating)
                    } else {
                        stringResource(MR.strings.channel_create_action_create)
                    },
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
        SlockBrutalSectionLabel(label = stringResource(MR.strings.channel_create_label_visibility))
        SegmentedControl(
            selectedValue = selected.name,
            options =
                SharedCreateChannelVisibility.values().map { option ->
                    SegmentedControlOption(
                        value = option.name,
                        label =
                            when (option) {
                                SharedCreateChannelVisibility.Public -> stringResource(MR.strings.channel_create_visibility_public)
                                SharedCreateChannelVisibility.Private -> stringResource(MR.strings.channel_create_visibility_private)
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
        SlockBrutalSectionLabel(label = stringResource(MR.strings.channel_create_label_members), optional = true)
        when {
            loading ->
                Text(
                    text = stringResource(MR.strings.settings_members_loading),
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            !hasMembers ->
                Text(
                    text = stringResource(MR.strings.settings_members_empty),
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            else -> {
                SlockBrutalSearchField(
                    value = query,
                    enabled = enabled,
                    placeholder = stringResource(MR.strings.channel_create_placeholder_member_search),
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
                                text = MR.strings.settings_members_no_matches.format(listOf(query.trim())).localized(),
                                style = SlockType.Caption,
                                color = SlockColor.TextMutedGray
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            if (filteredAgentMembers.isNotEmpty()) {
                                item(key = "agents-header") {
                                    SharedCreateChannelMemberSectionLabel(stringResource(MR.strings.thread_members_agents))
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
                                    SharedCreateChannelMemberSectionLabel(stringResource(MR.strings.thread_members_humans))
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
            // Same resolver as every other agent dot: agentStatus wins over
            // raw presence so a Working agent is yellow here too (#198).
            slockAgentStatusIndicatorState(status = member.agentStatus, presence = member.presence).color
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

@Composable
private fun SharedHomeNotification.toHomeNotificationItem(): SlockHomeNotificationItem =
    SlockHomeNotificationItem(
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
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val channelFilters = remember(homeState) { homeSearchChannelFilters(homeState) }
    val channelEntityLabel = stringResource(MR.strings.search_entity_channel)
    val dmEntityLabel = stringResource(MR.strings.search_entity_dm)
    val agentEntityLabel = stringResource(MR.strings.search_entity_agent)
    val personEntityLabel = stringResource(MR.strings.search_entity_person)
    val membersEntityLabel = stringResource(MR.strings.search_entity_members)
    val threadDiscussionLabel = stringResource(MR.strings.search_thread_discussion)
    val entityResults = remember(
        state.query,
        homeState,
        state.senderFilters,
        channelEntityLabel,
        dmEntityLabel,
        agentEntityLabel,
        personEntityLabel,
        membersEntityLabel
    ) {
        homeSearchEntityResults(
            query = state.query,
            homeState = homeState,
            senderFilters = state.senderFilters,
            channelLabel = channelEntityLabel,
            dmLabel = dmEntityLabel,
            agentLabel = agentEntityLabel,
            personLabel = personEntityLabel,
            membersLabel = membersEntityLabel
        )
    }
    val projectedItems =
        remember(state.items, draftProjection) {
            state.items.map { item -> item.withComposerDraftProjection(draftProjection) }
        }
    val groupedResults = remember(projectedItems, threadDiscussionLabel) {
        groupHomeSearchResults(projectedItems, threadDiscussionLabel)
    }
    var openFilterId by remember(initialOpenFilterId) { mutableStateOf<String?>(null) }
    var pendingInitialOpenFilterId by remember(initialOpenFilterId) { mutableStateOf(initialOpenFilterId) }
    var scopeAnchor by remember { mutableStateOf(Offset.Zero) }
    var senderAnchor by remember { mutableStateOf(Offset.Zero) }
    var channelAnchor by remember { mutableStateOf(Offset.Zero) }
    var timeAnchor by remember { mutableStateOf(Offset.Zero) }
    var sortAnchor by remember { mutableStateOf(Offset.Zero) }
    var senderSearch by remember { mutableStateOf("") }
    var channelSearch by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        onIntent(SharedHomeSearchSavedIntent.OpenSearch)
    }
    LaunchedEffect(initialOpenFilterId, scopeAnchor, senderAnchor, channelAnchor, timeAnchor, sortAnchor) {
        val pending = pendingInitialOpenFilterId ?: return@LaunchedEffect
        if (homeSearchFilterAnchorReady(pending, scopeAnchor, senderAnchor, channelAnchor, timeAnchor, sortAnchor)) {
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
                placeholder = stringResource(MR.strings.search_placeholder)
            )
            SharedHomeSearchDivider()
            SlockAppShellBodyTopBanner()
            SharedHomeSearchFilters(
                state = state,
                channelFilters = channelFilters,
                scopeAnchorChanged = { scopeAnchor = it },
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
                        HomeInlineErrorCard(
                            error = localizedSharedHomeSearchErrorDisplay(state.noPermissionCode ?: state.errorCode.orEmpty()),
                            modifier = Modifier.padding(horizontal = SearchMessagesHorizontalPadding),
                            onAction = { onIntent(SharedHomeSearchSavedIntent.RefreshSearch) }
                        )
                    } else if (!state.searched && projectedItems.isEmpty() && entityResults.isEmpty()) {
                        SharedHomeSearchEmptyState(
                            title = stringResource(MR.strings.search_empty_title),
                            detail = stringResource(MR.strings.search_empty_detail)
                        )
                    } else if (state.empty || (state.searched && projectedItems.isEmpty() && entityResults.isEmpty())) {
                        val trimmedQuery = state.query.trim()
                        SharedHomeSearchEmptyState(
                            title =
                                if (trimmedQuery.isNotBlank()) {
                                    MR.strings.search_empty_results_query.format(listOf(trimmedQuery)).localized()
                                } else {
                                    stringResource(MR.strings.search_empty_results)
                                },
                            detail =
                                if (trimmedQuery.isNotBlank()) {
                                    stringResource(MR.strings.search_empty_query_detail)
                                } else {
                                    stringResource(MR.strings.search_empty_filters_detail)
                                }
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
                        SharedHomeSearchSectionLabel(stringResource(MR.strings.search_section_channels_dms))
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
                        SharedHomeSearchSectionLabel(stringResource(MR.strings.search_section_messages))
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
                            label = stringResource(MR.strings.search_action_load_more_results),
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
                SlockFilterPopover(
                    anchor = scopeAnchor,
                    title = stringResource(MR.strings.search_filter_scope),
                    searchPlaceholder = "",
                    searchText = "",
                    onSearchChange = {},
                    options = SearchScope.entries.map { SlockFilterOption(it.key, it.localizedSearchLabel()) },
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
                    selectedRowColor = SlockColor.SurfaceWhite,
                    showClearWhenSelected = true
                )
            "sender" ->
                SlockFilterPopover(
                    anchor = senderAnchor,
                    title = stringResource(MR.strings.search_filter_from),
                    searchPlaceholder = stringResource(MR.strings.search_filter_search_senders),
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
                            Text(stringResource(MR.strings.search_filter_loading_senders), style = SlockType.Caption, color = SlockColor.TextMutedGray)
                        } else if (state.senderFiltersErrorCode != null) {
                            Text(state.senderFiltersErrorCode, style = SlockType.Caption, color = SlockColor.AccentRed)
                        }
                    }
                )
            "channel" ->
                SlockFilterPopover(
                    anchor = channelAnchor,
                    title = stringResource(MR.strings.search_filter_channels),
                    searchPlaceholder = stringResource(MR.strings.search_filter_search),
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
                    title = stringResource(MR.strings.search_filter_time),
                    searchPlaceholder = "",
                    searchText = "",
                    onSearchChange = {},
                    options = SearchTimeRange.entries.map { SlockFilterOption(it.key, it.localizedSearchLabel()) },
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
                    title = stringResource(MR.strings.search_filter_sort),
                    searchPlaceholder = "",
                    searchText = "",
                    onSearchChange = {},
                    options =
                        listOf(
                            SlockFilterOption("relevance", stringResource(MR.strings.search_sort_relevant)),
                            SlockFilterOption("recent", stringResource(MR.strings.search_sort_recent))
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
    scopeAnchor: Offset,
    senderAnchor: Offset,
    channelAnchor: Offset,
    timeAnchor: Offset,
    sortAnchor: Offset
): Boolean =
    when (filterId) {
        "scope" -> scopeAnchor != Offset.Zero
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
    placeholder: String
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
        SharedHomeSearchInput(
            query = query,
            loading = loading,
            interactive = interactive,
            placeholder = placeholder,
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
    val copyLinkLabel = stringResource(MR.strings.saved_action_copy_link)
    val copyMarkdownLabel = stringResource(MR.strings.saved_action_copy_markdown)
    val removeLabel = stringResource(MR.strings.saved_action_remove)
    val contextMenuItems = remember(copyLinkLabel, copyMarkdownLabel, removeLabel) {
        listOf(
            SlockBaseMenuItem(SharedHomeSavedMenuActionCopyLink, copyLinkLabel, SlockLucideIconName.Link),
            SlockBaseMenuItem(SharedHomeSavedMenuActionCopyMarkdown, copyMarkdownLabel, SlockLucideIconName.Copy),
            SlockBaseMenuItem(
                SharedHomeSavedMenuActionRemove,
                removeLabel,
                SlockLucideIconName.Bookmark,
                dividerBefore = true
            )
        )
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
                refreshing = state.refreshing || state.pullRefreshing,
                topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
                onTitleClick = onTitleClick
            )
            SlockAppShellBodyTopBanner()
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
                refreshing = state.refreshing || state.pullRefreshing,
                topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
                onTitleClick = onTitleClick
            )
            SlockAppShellBodyTopBanner()
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
                    slockRaftPullToRefreshItem(
                        state = pullToRefreshState,
                        scrollState = listState,
                        onRefresh = { onIntent(SharedHomeSearchSavedIntent.PullRefreshSaved) }
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
                            label = stringResource(MR.strings.saved_action_load_more),
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
                items = contextMenuItems,
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
        title = stringResource(MR.strings.saved_empty_title),
        detail = stringResource(MR.strings.saved_empty_detail),
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
                    stringResource(MR.strings.saved_title),
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(MR.strings.saved_count, count),
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
    scopeAnchorChanged: (Offset) -> Unit,
    senderAnchorChanged: (Offset) -> Unit,
    channelAnchorChanged: (Offset) -> Unit,
    timeAnchorChanged: (Offset) -> Unit,
    sortAnchorChanged: (Offset) -> Unit,
    onOpenFilter: (String) -> Unit,
    onIntent: (SharedHomeSearchSavedIntent) -> Unit,
    showSenderFilter: Boolean = true
) {
    val hasActiveFilters =
        state.selectedScopes.isNotEmpty() ||
            state.myMessagesOnly ||
            state.selectedSenderIds.isNotEmpty() ||
            state.selectedChannelId != null ||
            state.timeRange != SearchTimeRange.Any
    val selectedChannel = channelFilters.firstOrNull { it.id == state.selectedChannelId }
    val scopeActive = state.selectedScopes.isNotEmpty()
    val senderActive = state.selectedSenderIds.isNotEmpty()
    val channelActive = state.selectedChannelId != null
    val timeActive = state.timeRange != SearchTimeRange.Any
    val sortActive = state.sort != SearchSort.Relevance
    val scopeLabel = stringResource(MR.strings.search_filter_scope)
    val channelLabel = stringResource(MR.strings.search_filter_channel)
    SharedHomeSearchControlsBlock {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SearchControlsHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            homeSearchFilterOrder(showSenderFilter).forEach { filter ->
                item(key = "search-filter-${filter.id}") {
                    when (filter) {
                        HomeSearchFilter.Sender ->
                            SlockAnchoredToolbarChip(
                                label = state.localizedSenderFilterLabel(),
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
                        HomeSearchFilter.Scope ->
                            SlockAnchoredToolbarChip(
                                label = if (scopeActive) "$scopeLabel ${state.selectedScopes.size}" else scopeLabel,
                                active = scopeActive,
                                enabled = true,
                                uppercase = !scopeActive,
                                iconName = SlockLucideIconName.AtSign,
                                activeBackgroundColor = SlockColor.BrandYellow,
                                activeShadowOffset = SearchControlShadowOffset,
                                reserveActiveShadowSpace = true,
                                onAnchorChanged = scopeAnchorChanged,
                                onClick = { anchor ->
                                    scopeAnchorChanged(anchor)
                                    onOpenFilter("scope")
                                }
                            )
                        HomeSearchFilter.Channel ->
                            SlockAnchoredToolbarChip(
                                label = selectedChannel?.label ?: channelLabel,
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
                        HomeSearchFilter.Time ->
                            SlockAnchoredToolbarChip(
                                label = state.timeRange.localizedSearchLabel(),
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
            }
            item {
                SlockAnchoredToolbarChip(
                    label = state.sort.localizedSearchLabel(),
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
                        label = stringResource(MR.strings.search_filter_clear_all),
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
                title = stringResource(MR.strings.panel_error_no_permission),
                detail = noPermissionCode,
                tone = SlockColor.AccentRed,
                actionLabel = stringResource(MR.strings.common_retry),
                onAction = onRetry
            )
        errorCode != null ->
            SharedHomePanelMessage(
                title = stringResource(MR.strings.panel_error_load),
                detail = errorCode,
                tone = SlockColor.AccentRed,
                actionLabel = stringResource(MR.strings.common_retry),
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
    val preview = item.latestActivityPreview ?: item.lastMessagePreview ?: item.parentMessagePreview
        ?: stringResource(MR.strings.saved_state_no_preview)
    val sender = item.latestActivitySenderName ?: item.lastMessageSenderName
    val time = item.latestActivityAt ?: item.lastMessageAt
    val openGestureKey = remember(item) { item.inboxItemOpenGestureKey() }
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnContextMenu by rememberUpdatedState(onContextMenu)
    val source =
        when {
            item.kind == InboxItemKind.DirectMessage -> item.channelName.takeIf { it.isNotBlank() }?.let { "@$it" }
            item.kind == InboxItemKind.Thread -> item.parentChannelName?.takeIf { it.isNotBlank() }?.let { "#$it" }
            else -> item.channelName.takeIf { it.isNotBlank() }?.let { "#$it" }
        }
    val contentTopLeft = remember(openGestureKey) { SlockMutableOffsetRef() }
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
                        .pointerInput(openGestureKey) {
                            detectTapGestures(
                                onTap = { currentOnOpen() },
                                onLongPress = { localOffset ->
                                    val topLeft = contentTopLeft.value
                                    currentOnContextMenu(Offset(topLeft.x + localOffset.x, topLeft.y + localOffset.y))
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
                                text = stringResource(MR.strings.saved_meta_thread),
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
                            style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp),
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
            // React's saved card renders a lucide bookmark-minus (BookmarkMinus) for the
            // unsave affordance; android previously drew a plain Bookmark with a hand-drawn
            // overlay bar. Use the generated BookmarkMinus glyph to match react (#370).
            SlockLucideIcon(SlockLucideIconName.BookmarkMinus, color = SlockColor.TextBlackArgb, modifier = Modifier.size(16.dp))
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
        text =
            if (searching) {
                stringResource(MR.strings.search_state_searching)
            } else {
                stringResource(MR.strings.search_results_count, totalResults)
            },
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

internal fun sharedHomeSearchTimestampTextStyle(compact: Boolean) =
    SlockType.Caption.copy(
        fontFamily = SlockMonoFamily,
        fontSize = if (compact) 9.sp else 10.sp,
        lineHeight = if (compact) 11.sp else 12.sp,
        letterSpacing = 0.sp
    )

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
                SearchEntityType.Channel -> stringResource(MR.strings.search_entity_channel)
                SearchEntityType.DirectMessage -> stringResource(MR.strings.search_entity_dm)
                SearchEntityType.Human -> stringResource(MR.strings.search_entity_dm)
                SearchEntityType.Agent -> stringResource(MR.strings.search_entity_dm)
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
                SharedHomeMiniMetaBadge(stringResource(MR.strings.search_meta_thread), SlockLucideIconName.MessageSquare, compact = true)
                Text(
                    text = sourceLabel,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(MR.strings.search_hits_count, group.items.size),
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
                        style = sharedHomeSearchTimestampTextStyle(compact = false),
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
    val preview = item.latestActivityPreview ?: item.lastMessagePreview ?: item.parentMessagePreview
        ?: stringResource(MR.strings.search_state_no_preview)
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
                    SharedHomeMiniMetaBadge(stringResource(MR.strings.search_meta_thread), SlockLucideIconName.MessageSquare)
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
                    style = sharedHomeSearchTimestampTextStyle(compact = compactMeta),
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
            label = if (loading) stringResource(MR.strings.common_loading) else label,
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
            label = stringResource(MR.strings.search_sort_relevant),
            selected = sort == SearchSort.Relevance,
            onClick = { onSortChange(SearchSort.Relevance) }
        )
        SharedHomeSearchSortSegmentButton(
            label = stringResource(MR.strings.search_sort_recent),
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
    val conversationIdentity: ThreadLaunchConversationIdentity?,
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
                groups += HomeSearchResultGroup.Thread(threadKey, item.parentMessagePreview ?: threadDiscussionLabel, it)
            }
        }
        existing += item
    }
    return groups
}

@Composable
private fun SharedHomeSearchPanelState.senderFilterOptions(): List<SlockFilterOption> {
    val meLabel = stringResource(MR.strings.search_sender_me)
    return senderFilters
        .map { sender ->
            SlockFilterOption(
                id = sender.id,
                label = if (sender.isSelf) meLabel else sender.displayLabel,
                icon = if (sender.kind == MemberKind.Agent) SlockLucideIconName.Bot else SlockLucideIconName.CircleUser
            )
        }
        .distinctBy { it.id }
}

@Composable
private fun SharedHomeSearchPanelState.localizedSenderFilterLabel(): String {
    val selectedIds = selectedSenderIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (selectedIds.isEmpty()) return stringResource(MR.strings.search_filter_from)
    val meLabel = stringResource(MR.strings.search_sender_me)
    val selected = senderFilters.filter { it.id in selectedIds.toSet() }
    return when (selectedIds.size) {
        1 -> stringResource(
            MR.strings.search_filter_from_value,
            selected.singleOrNull()?.let { if (it.isSelf) meLabel else it.displayLabel } ?: selectedIds.first()
        )
        else -> stringResource(MR.strings.search_filter_from_selected, selectedIds.size)
    }
}

private val SearchSenderFilterItem.displayLabel: String
    get() = label.ifBlank { handle.ifBlank { id } }

private fun homeSearchEntityResults(
    query: String,
    homeState: SharedHomeState,
    senderFilters: List<SearchSenderFilterItem>,
    channelLabel: String,
    dmLabel: String,
    agentLabel: String,
    personLabel: String,
    membersLabel: String
): List<SearchEntityResult> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return emptyList()
    val snapshot = (homeState.content as? SharedHomeContentState.Content)?.snapshot ?: return emptyList()
    val conversations = snapshot.channels + snapshot.directMessages
    val byPresenceMemberId =
        snapshot.directMessages
            .mapNotNull { conversation ->
                val memberId = conversation.presenceMemberId?.value ?: return@mapNotNull null
                memberId to conversation.toThreadLaunchConversationIdentity()
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
                subtitle = conversation.subtitle?.takeIf { it.isNotBlank() } ?: if (type == SearchEntityType.Channel) channelLabel else dmLabel,
                threadId = conversation.threadId.value,
                memberId = conversation.presenceMemberId?.value,
                conversationIdentity = conversation.toThreadLaunchConversationIdentity(),
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
                        append(if (sender.kind == MemberKind.Agent) agentLabel else personLabel)
                        if (sender.handle.isNotBlank()) append(" · @${sender.handle}")
                        if (conversation == null) append(" · $membersLabel")
                    },
                threadId = conversation?.threadId?.value,
                memberId = sender.id,
                conversationIdentity = conversation,
                avatarWireValue = sender.avatarWireValue ?: AvatarReference.Random(sender.id).wireValue,
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

private fun HomeConversationRow.isAgentDirectMessage(): Boolean =
    kind == ConversationKind.DirectMessage &&
        directMessagePeerType.equals("agent", ignoreCase = true)

@Composable
private fun SearchScope.localizedSearchLabel(): String =
    stringResource(
        when (this) {
            SearchScope.Mentioned -> MR.strings.search_scope_mentioned
            SearchScope.Humans -> MR.strings.search_scope_humans
            SearchScope.Agents -> MR.strings.search_scope_agents
        }
    )

@Composable
private fun SearchTimeRange.localizedSearchLabel(): String =
    stringResource(
        when (this) {
            SearchTimeRange.Any -> MR.strings.search_time_any
            SearchTimeRange.Today -> MR.strings.search_time_today
            SearchTimeRange.Last7Days -> MR.strings.search_time_last_7_days
            SearchTimeRange.Last30Days -> MR.strings.search_time_last_30_days
        }
    )

@Composable
private fun SearchSort.localizedSearchLabel(): String =
    stringResource(
        when (this) {
            SearchSort.Relevance -> MR.strings.search_sort_relevant
            SearchSort.Recent -> MR.strings.search_sort_recent
        }
    )

private val SearchSort.searchKey: String
    get() =
        when (this) {
            SearchSort.Relevance -> "relevance"
            SearchSort.Recent -> "recent"
        }

internal fun HomeConversationRow.searchMatches(query: String): Boolean =
    listOfNotNull(
        name,
        if (kind == ConversationKind.DirectMessage) null else "#${name.removePrefix("#")}",
        subtitle.orEmpty(),
        channelId?.value.orEmpty(),
        threadId.value
    )
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
    // #372: true only for the Loading-with-session branch — renders
    // skeleton rows under the CHANNELS / DIRECT MESSAGES headers so the
    // post-sign-in transition matches the React app instead of a bare shell.
    loadingSkeletons: Boolean = false,
    inlineError: SlockHomeInlineError? = null,
    onInlineErrorAction: () -> Unit = {},
    onServerBadgeClick: () -> Unit,
    onServerSwitcherDismiss: () -> Unit,
    onServerSelected: (String) -> Unit,
    onServerOrderChange: (List<String>) -> Unit = {},
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
        showLoadingSkeletons = loadingSkeletons,
        inlineError = inlineError,
        serverSwitcherExpanded = serverSwitcherExpanded,
        prefersChineseCommunity = prefersChineseCommunity,
        showBottomBar = false,
        bottomContentPadding = bottomContentPadding,
        outerPadding = 0.dp,
        labels = SlockHomeShellLabels.localized(),
        onInlineErrorAction = onInlineErrorAction,
        onServerBadgeClick = onServerBadgeClick,
        onServerSwitcherDismiss = onServerSwitcherDismiss,
        onServerSelected = onServerSelected,
        onServerOrderChange = onServerOrderChange,
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
        avatarUrl = avatarUrl,
        unreadCount = serverUnreadCounts[id.value] ?: unreadCount
    )

internal data class SharedHomeConversationRowSeed(
    val id: String,
    val kind: ConversationKind,
    val unreadCount: Int = 0,
    val lastMessageAt: String? = null,
    val peerType: String? = null,
    val fallbackPresenceMemberId: String? = null,
    val fallbackName: String = "",
    val fallbackChannelType: String? = null,
    val fallbackDescription: String? = null,
    val fallbackJoined: Boolean = true,
    val fallbackArchived: Boolean = false,
    val fallbackActivityMuted: Boolean = false,
    val fallbackActivityMuteSupported: Boolean = false
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
        kind = kind,
        unreadCount = unreadCount,
        lastMessageAt = lastMessageAt,
        peerType = directMessagePeerType?.takeIf { it.isNotBlank() },
        fallbackPresenceMemberId = presenceMemberId?.value,
        fallbackName = name,
        fallbackChannelType = channelType?.takeIf { it.isNotBlank() },
        fallbackDescription = subtitle?.takeIf { it.isNotBlank() },
        fallbackJoined = joined && permissions.canRead,
        fallbackArchived = archived,
        fallbackActivityMuted = activityMuted,
        fallbackActivityMuteSupported = activityMuteSupported
    )

private fun SharedHomeConversationRowSeed.toSharedHomeConversationItem(
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    directoryProjection: SharedConversationDirectoryProjection? = null
): SlockHomeConversationItem {
    val title = resolvedDirectoryTitle(directoryProjection)
    val projectedChannelType =
        directoryProjection
            ?.takeIf { projection -> projection.conversationKind == kind }
            ?.channelKind
            ?.takeIf { it.isNotBlank() }
    return SlockHomeConversationItem(
        id = id,
        name = title,
        // The row seed is the Home identity source. Directory projection enriches display/state
        // fields, but cannot rewrite the row's conversation kind (§5).
        // task #444: serialize through the LOSSLESS wire kind, not the surface projection —
        // toKuiklySurfaceKind() folds Joint into the channel surface (contract rule: the
        // surface projection is lossy), which made the Joint Channels section filter
        // (kind == "joint") never match and the section vanish. The surface fallback only
        // covers kinds without a wire channel kind (Inbox/System), which render as channel
        // surfaces anyway.
        kind = kind.toWireChannelKindOrNull() ?: kind.toKuiklySurfaceKind(),
        channelType = projectedChannelType ?: fallbackChannelType,
        unreadCount = unreadCount,
        lastMessageAt = lastMessageAt,
        description = directoryProjection?.subtitle ?: fallbackDescription,
        peerType = peerType,
        presenceMemberId = directoryProjection?.peerIdentity?.memberId?.value ?: fallbackPresenceMemberId,
        hasDraft = draftProjection.hasRootDraft(id),
        joined = directoryProjection?.joined ?: fallbackJoined,
        archived = directoryProjection?.archived ?: fallbackArchived,
        activityMuted = directoryProjection?.activityMuted ?: fallbackActivityMuted,
        activityMuteSupported = directoryProjection?.activityMuteSupported ?: fallbackActivityMuteSupported
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
    accessState: ThreadLaunchAccessState = ThreadLaunchAccessState(),
    onWarn: (String) -> Unit = {}
): KuiklyThreadScreenPayload? {
    val routeIdentity = conversation.resolveThreadRouteIdentity()
    val surfaceKind = routeIdentity.surfaceKind
    val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
    if (isReplyThread && !routeIdentity.hasCompleteReplyIdentity) {
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
    val taskChannelKind = if (isReplyThread) parentChannelKind else routeChannelKind
    val supportsChannelTasks =
        accessState.permissions.canCreateTask &&
            !taskChannelKind.equals(JointChannelKindWireValue, ignoreCase = true)
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
            routeIdentity = routeIdentity,
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
    serverId: String,
    conversationDirectory: SharedConversationDirectoryState,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    threadRowAffordances: Set<ThreadRowAffordancePreference>,
    storeScope: SlockShellStoreScope
) {
    val launchPayload =
        buildStandaloneTaskThreadLaunchPayload(
            task = task,
            serverId = serverId,
            conversationDirectory = conversationDirectory,
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            threadRowAffordances = threadRowAffordances
        )
            ?: return
    openStandaloneThreadLaunchPayload(launchPayload, storeScope)
}

private fun Pager.openStandaloneThreadLaunchPayload(
    launchPayload: WireThreadLaunchPayload,
    storeScope: SlockShellStoreScope
) {
    openSlockPage(
        pageName = THREAD_PAGE,
        pageData = threadLaunchPageData(launchPayload),
        storeScopeOverride = storeScope
    )
}

private fun Pager.closeHomeSecondaryPage() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
}

private fun Pager.openHomeSecondaryPage(
    destination: String,
    timeContext: AppShellThreadTimeContext?,
    storeScope: SlockShellStoreScope
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
            put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
            storeScope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
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
    threadRowAffordances: Set<ThreadRowAffordancePreference>,
    storeScope: SlockShellStoreScope
) {
    openStandaloneHomeConversation(
        bridge = bridge,
        conversation = request.conversationIdentity,
        source = source,
        timeContext = timeContext,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        threadRowAffordances = threadRowAffordances,
        storeScope = storeScope,
        anchorMessageId = request.anchorMessageId,
        originFallback = request.originFallback
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
    storeScope: SlockShellStoreScope,
    anchorMessageId: String? = null,
    originFallback: ThreadLaunchOriginFallback? = null
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
            anchorMessageId = anchorMessageId,
            originFallback = originFallback,
            onWarn = { bridge.logWarn(it) }
        ) ?: return
    bridge.log(
        "home_open_thread_launch source=$source threadId=${conversation.threadId.value} " +
            "sourceThreadId=${launchPayload.sourceThreadId.orEmpty()} surface=${launchPayload.surfaceKind} " +
            "parentChannelId=${launchPayload.parentChannelId.orEmpty()} parentMessageId=${launchPayload.parentMessageId.orEmpty()} " +
            "anchorMessageId=${anchorMessageId.orEmpty()} originParentMessageId=${launchPayload.originParentMessageId.orEmpty()}"
    )
    openStandaloneThreadLaunchPayload(launchPayload, storeScope)
}

private fun Pager.openHomeSearchEntity(
    bridge: build.raft.app.base.BridgeModule,
    entity: SearchEntityResult,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    threadRowAffordances: Set<ThreadRowAffordancePreference>,
    storeScope: SlockShellStoreScope
) {
    val conversation = entity.conversationIdentity
    if (conversation != null) {
        openStandaloneHomeConversation(
            bridge = bridge,
            conversation = conversation,
            source = "home_search_entity",
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            threadRowAffordances = threadRowAffordances,
            storeScope = storeScope
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
    openSlockPage(
        pageName = MEMBER_DETAIL_PAGE,
        pageData = pageData,
        storeScopeOverride = storeScope
    )
}

internal fun buildStandaloneConversationThreadLaunchPayload(
    conversation: ThreadLaunchConversationIdentity,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    threadRowAffordances: Set<ThreadRowAffordancePreference> = ThreadRowAffordancePreference.Default,
    anchorMessageId: String? = null,
    originFallback: ThreadLaunchOriginFallback? = null,
    instanceIdOverride: String? = null,
    onWarn: (String) -> Unit = {}
): WireThreadLaunchPayload? {
    val routeIdentity = conversation.resolveThreadRouteIdentity()
    val surfaceKind = routeIdentity.surfaceKind
    val isReplyThread = surfaceKind == KuiklyThreadSurfaceKind.ReplyThread
    if (isReplyThread && !routeIdentity.hasCompleteReplyIdentity) {
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
                KuiklyThreadSurfaceKind.ReplyThread -> conversation.threadParentChannelKind()
                KuiklyThreadSurfaceKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
                else -> routeIdentity.channelKind
            },
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
        parentSurfaceKind =
            if (isReplyThread) {
                // Defensive (see buildAppShellThreadPayload): null parent kind here means a
                // resolver bypass — log loudly, never silently.
                conversation.threadParentSurfaceKind()
            } else {
                null
            },
        parentChannelKind = if (isReplyThread) conversation.threadParentChannelKind() else null,
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
        showReactionActions = threadRowAffordances.showReactionActions(),
        showInlineSaveAction = threadRowAffordances.showInlineSaveAction(),
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: SlockTimeFormat24Hour,
        originFallbackParentChannelId = originFallback?.parentChannelId,
        originFallbackParentMessageId = originFallback?.parentMessageId,
        originFallbackPreview = originFallback?.preview,
        originFallbackSenderId = originFallback?.senderId,
        originFallbackSenderKind = originFallback?.senderKind,
        originFallbackExpected = originFallback?.expected ?: true
    )
}

private fun Set<ThreadRowAffordancePreference>.showReactionActions(): Boolean =
    ThreadRowAffordancePreference.Reactions in this

private fun Set<ThreadRowAffordancePreference>.showInlineSaveAction(): Boolean =
    ThreadRowAffordancePreference.Saved in this

internal fun buildStandaloneTaskThreadLaunchPayload(
    task: TaskSummary,
    serverId: String,
    conversationDirectory: SharedConversationDirectoryState,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    threadRowAffordances: Set<ThreadRowAffordancePreference> = ThreadRowAffordancePreference.Default
): WireThreadLaunchPayload? {
    val request = task.resolveTaskThreadOpenRequest(serverId, conversationDirectory) ?: return null
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = request.conversationIdentity,
        timeContext = timeContext,
        anchorMessageId = request.anchorMessageId,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        threadRowAffordances = threadRowAffordances
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
        is PerReceiverReadStateChanged -> "read_state_updated"
        is PerReceiverNotificationPrefsChanged -> "notification_prefs_updated"
        is PerReceiverPinnedChanged -> "pinned_updated"
        is NotificationPushReceived -> "notification_push"
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
        is PerReceiverReadStateChanged,
        is PerReceiverNotificationPrefsChanged,
        is PerReceiverPinnedChanged -> "realtime_content_apply"
        is NotificationPushReceived -> "realtime_notification_apply"
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

internal fun JSONObject.realtimeConnectionWitnessOrNull(): RealtimeConnectionWitness? {
    val scopeLeaseId = optString(SlockRealtimeNotifyContract.KeyAuthorityScopeLeaseId).trim()
    val principalId = optString(SlockRealtimeNotifyContract.KeyAuthorityPrincipalId).trim()
    val authGeneration = optLong(SlockRealtimeNotifyContract.KeyAuthorityAuthGeneration, 0L)
    val connectionFamilyId = optString(SlockRealtimeNotifyContract.KeyAuthorityConnectionFamilyId).trim()
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
internal fun AppShellRestoreSplashContent() {
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
            text = stringResource(MR.strings.auth_restore_tagline),
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
    code: String,
    retryLabel: String? = "Retry",
    onRetry: (() -> Unit)? = null
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
                Text(text = settingsUserFacingErrorCodeMessage(code), style = SlockType.Caption, color = SlockColor.AccentRed)
                if (retryLabel != null && onRetry != null) {
                    SlockBrutalActionButton(
                        label = retryLabel,
                        onClick = onRetry,
                        leadingIcon = SlockLucideIconName.RefreshCw,
                        backgroundColor = SlockColor.SurfaceWhite
                    )
                }
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
    // React measures the live tab bar element at full page width (390x53dp), so
    // render the bar flush at the window origin at exactly 390dp — explicit width
    // rather than fillMaxWidth because the capture window can be wider than 390dp
    // (task #370).
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockBottomTabBar(
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
    // React baselines render settings pages flush to the viewport top; no host
    // status-bar spacer or safe-area padding may leak into fixture captures.
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceWhite)
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
    val iconName: SlockLucideIconName
)

internal fun settingsVisualHeaderSubtitle(variant: String): String = ""

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
            if (variant.startsWith("administration")) {
                SettingsVisualSpec("administration", "Administration", "Owners, admins, invites, and join links", SlockLucideIconName.Shield)
            } else {
                SettingsVisualSpec("account", "Account", "Profile, email, password, connected accounts", SlockLucideIconName.User)
            }
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
    val fixture = settingsVisualFixtureValues
    val connectedAccounts =
        when (variant) {
            "account-provider-linked" ->
                listOf(
                    KuiklySettingsConnectedAccount("password", "Email password", fixture.ownerEmail, "connected", canConnect = false),
                    KuiklySettingsConnectedAccount("google", "Google", fixture.ownerEmail, "connected", canConnect = false),
                    KuiklySettingsConnectedAccount("github", "GitHub", "Not connected", "notConnected", canConnect = true)
                )
            else ->
                listOf(
                    KuiklySettingsConnectedAccount("password", "Email password", fixture.ownerEmail, "connected", canConnect = false),
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
    var joinLinkMaxUses by remember { mutableStateOf("25") }
    var joinLinkExpiresAt by remember { mutableStateOf(settingsVisualFixtureValues.recentActivityAtIso) }
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
                style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, letterSpacing = 1.3.sp, color = SlockColor.TextMutedGray),
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
        mobileState = ScreenLoadState.Content(settingsVisualMobileSnapshot()),
        actionState = null,
        languageDraft = snapshot.browserLanguage.selectedKey,
        displayDraft = snapshot.translationDisplay.selectedKey,
        timezoneDraft = snapshot.timezone.selectedKey,
        timeFormatDraft = snapshot.timeFormat.selectedKey,
        bottomContentPadding = SlockSpacing.Lg,
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
        foregroundMessageBannersBusy = false,
        onNotificationPreferenceChange = {},
        onForegroundKeepAlivePreferenceChange = {},
        onForegroundMessageBannersChange = {},
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
                selectedKey = "en",
                selectedLabel = "English",
                statusLabel = "English",
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
                ),
                SettingsConnectedAppInstalledSnapshot(
                    id = "client_1",
                    clientId = "client_slack_bridge",
                    name = "Slack Bridge",
                    developer = "This server",
                    description = "Server-local OAuth client.",
                    homepageDomain = "example.com",
                    callbackDomain = "example.com",
                    origin = "private",
                    editable = true
                )
            ),
        registered =
            listOf(
                SettingsConnectedAppClientSnapshot(
                    id = "client_1",
                    serverId = "srv_design",
                    clientId = "client_slack_bridge",
                    appType = "server_local",
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

private val homeSearchVisualServer: ServerSummary
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

private val homeSearchVisualMember: MemberProfile
    get() {
        val fixture = settingsVisualFixtureValues
        return MemberProfile(
            id = MemberId(fixture.ownerMemberId),
            displayName = fixture.ownerDisplayName,
            handle = fixture.ownerHandleBare,
            kind = MemberKind.Human,
            presence = Presence.Active
        )
    }

private val homeSearchVisualSession: StoredAuthSession
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

// Canonical create-channel roster (task #370): mirrors the react render host
// priming in packages/web/visual-testing/VisualTestingCases.tsx
// (primeCreateChannelStores) — agents Cindy (selected) + Visual QA, human
// Designer (selected); the current user (artin) is excluded from the picker.
// Identity/avatar values come from fixtureData.json via VisualFixtureData.
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

private val homeSearchVisualSearchState = SharedHomeSearchPanelState(
    query = "android",
    sort = SearchSort.Recent,
    timeRange = SearchTimeRange.Last7Days,
    selectedScopes = listOf(SearchScope.Mentioned, SearchScope.Humans),
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

// Canonical saved-messages entries (task #370): ported verbatim from
// packages/visual-testing/shared/savedMessagesFixture.json in the slock repo
// (total 5 → "5 saved" header, 3 rendered entries, hasMore → Load More).
// Relative time labels match what react renders for the fixture ISO stamps.
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
                lastMessageAt = "14 days ago",
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
                lastMessageAt = "14 days ago",
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
                lastMessageAt = "15 days ago",
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
// react renders for the fixture ISO stamps.
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
                latestActivityAt = "14 days ago",
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
                lastMessageAt = "16 days ago",
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
                lastMessageAt = "16 days ago",
                lastMessageSenderName = "Cindy",
                lastMessageSenderId = "agent-cindy",
                lastMessageSenderKind = MemberKind.Agent
            )
        ),
    totalCount = 3,
    totalUnreadCount = 3,
    hasMore = false
)

@Composable
internal fun HomeSearchVisualContent(initialOpenFilterId: String? = null) {
    val pager = LocalActivity.current.getPager() as Pager
    val openFilterId =
        initialOpenFilterId ?: pager.pageData.params.optString("openFilterId").takeIf { it.isNotBlank() }
    // Fill the captured viewport (matched to the react fixture, which renders the search
    // panel full-frame) instead of a 342x620 sub-region — the smaller box left the crop's
    // margin painted with the window background (cream), which the react↔android diff
    // flagged as a spurious "empty beige area" drift. White backing keeps any residual gap
    // aligned with the react baseline. (visual case components.home.search.*-dropdown)
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
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
internal fun HomeSearchErrorVisualContent() {
    Box(modifier = Modifier.width(342.dp).height(620.dp)) {
        SharedHomeSearchPanel(
            state =
                homeSearchVisualSearchState.copy(
                    query = "卡片",
                    sort = SearchSort.Relevance,
                    timeRange = SearchTimeRange.Any,
                    myMessagesOnly = false,
                    selectedSenderIds = emptyList(),
                    selectedChannelId = null,
                    items = emptyList(),
                    totalCount = 0,
                    searched = true,
                    loading = false,
                    refreshing = false,
                    querySearchPending = false,
                    empty = false,
                    hasMore = false,
                    errorCode = "server_unavailable",
                    noPermissionCode = null
                ),
            homeState = homeSearchVisualHomeState,
            onIntent = {},
            onClose = {},
            interactiveSearchField = false,
            includeTopSafeArea = false,
            showSenderFilter = true,
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
            onOpenItem = {},
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
private fun SettingsRouteVisualScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    val route =
        remember(pager.pageData.params.toString()) {
            parseSettingsRouteVisualPayload(pager.pageData.params)
        }
    val session = remember(route.fixtureKey) { settingsRouteVisualSession() }
    val state =
        remember(route.destination, route.itemId, route.fixtureKey) {
            settingsRouteVisualState(route)
        }
    val settingsCapabilities =
        remember(bridge) {
            BridgeSettingsPlatformCapabilities(bridge)
        }
    val sessionStore = remember(session) { InMemorySlockAuthSessionStore(session) }
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
    }

    SettingsAppShellContent(
        session = session,
        authDataSource = authDataSource,
        settingsCapabilities = settingsCapabilities,
        settingsState = state,
        notificationPreferenceSnapshot = NotificationPreferenceSnapshot(enabled = true),
        onToggleNotificationPreference = {},
        onSettingsIntent = {},
        selectedDestination = route.destination,
        onDestinationChange = {},
        onSettingsHeaderClick = {},
        onOpenRouter = {},
        onOpenReleaseNotes = {},
        onOpenBilling = {},
        onOpenAdministration = {},
        onOpenConnectedApps = {},
        onMessageAlignmentSelected = {},
        onCompactConsecutiveMessagesSelected = {},
        onForegroundMessageBannersSelected = {},
        onActivityTaskStatusBadgesSelected = {},
        onThreadRowAffordanceSelected = { _, _ -> },
        onSettingsAction = { _, _ -> },
        onDiagnosticsFeedbackPrepared = {},
        diagnosticsFeedbackDraft = null,
        diagnosticsFeedbackHomeState = SharedHomeState(),
        onDiagnosticsFeedbackBack = {},
        onDiagnosticsFeedbackRefresh = {},
        onDiagnosticsFeedbackChannelSelected = {},
        bottomContentPadding = SlockSpacing.Lg,
        timeContext = null,
        onLogout = {}
    )
}

internal fun settingsRouteVisualState(route: SettingsRouteVisualPayload): SharedSettingsState {
    val destination = route.destination.toSharedSettingsDestination() ?: SharedSettingsDestination.Administration
    return SharedSettingsState(
        route = SharedSettingsRoute(destination, route.itemId),
        home = ScreenLoadState.Content(settingsRouteVisualHomeSnapshot(destination)),
        server =
            if (destination == SharedSettingsDestination.Administration || destination == SharedSettingsDestination.Server) {
                ScreenLoadState.Content(settingsVisualServerSnapshot())
            } else {
                ScreenLoadState.Loading
            },
        connectedApps =
            if (destination == SharedSettingsDestination.ConnectedApps) {
                ScreenLoadState.Content(settingsVisualConnectedAppsSnapshot())
            } else {
                ScreenLoadState.Loading
            },
        billing =
            if (destination == SharedSettingsDestination.Billing) {
                ScreenLoadState.Content(settingsVisualBillingSnapshot())
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

private fun settingsRouteVisualHomeSnapshot(selectedDestination: SharedSettingsDestination): SharedSettingsHomeSnapshot {
    val session = settingsRouteVisualSession()
    return SharedSettingsHomeSnapshot(
        items =
            listOf(
                SharedSettingsNavItem(SharedSettingsDestination.Account, "Account", "Profile, email, password, connected accounts", SlockLucideIconName.User, session.user.bestDisplayName, selectedDestination == SharedSettingsDestination.Account),
                SharedSettingsNavItem(SharedSettingsDestination.LanguageRegion, "Language & Region", "Language, timezone, translation", SlockLucideIconName.Languages, null, selectedDestination == SharedSettingsDestination.LanguageRegion),
                SharedSettingsNavItem(SharedSettingsDestination.Appearance, "Appearance", "Message font size and reading density", SlockLucideIconName.Type, null, selectedDestination == SharedSettingsDestination.Appearance),
                SharedSettingsNavItem(SharedSettingsDestination.Notifications, "Notifications", "Push status and workspace mute", SlockLucideIconName.Bell, null, selectedDestination == SharedSettingsDestination.Notifications),
                SharedSettingsNavItem(SharedSettingsDestination.Mobile, "Mobile", "My message alignment", SlockLucideIconName.Smartphone, null, selectedDestination == SharedSettingsDestination.Mobile),
                SharedSettingsNavItem(SharedSettingsDestination.Server, "Server Profile", "Workspace name, slug, role", SlockLucideIconName.Building2, settingsVisualFixtureValues.ownerRole.replaceFirstChar { it.uppercase() }, selectedDestination == SharedSettingsDestination.Server),
                SharedSettingsNavItem(SharedSettingsDestination.Billing, "Billing", "Plan and usage", SlockLucideIconName.CreditCard, settingsVisualFixtureValues.serverPlan.replaceFirstChar { it.uppercase() }, selectedDestination == SharedSettingsDestination.Billing),
                SharedSettingsNavItem(SharedSettingsDestination.Administration, "Administration", "Admins, invites, join links, agreements", SlockLucideIconName.Shield, null, selectedDestination == SharedSettingsDestination.Administration),
                SharedSettingsNavItem(SharedSettingsDestination.ConnectedApps, "Connected Apps", "Marketplace, installed apps, OAuth clients", SlockLucideIconName.Link2, null, selectedDestination == SharedSettingsDestination.ConnectedApps),
                SharedSettingsNavItem(SharedSettingsDestination.About, "About", "Version and diagnostics", SlockLucideIconName.BadgeInfo, null, selectedDestination == SharedSettingsDestination.About)
            )
    )
}

/**
 * Visual-only full-height support (task #388 A). The capture harness grows the
 * emulator display so the whole Settings route fits one frame; it needs (1) the
 * fullHeight capture flag readable at the list sites and (2) the content-bottom
 * pixel to crop the tall frame to. Both read capture-intent pageData params the
 * same way the safe-area overrides do (SlockSafeArea.kt); production pages never
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

private fun parseSettingsRouteVisualPayload(params: com.tencent.kuikly.core.nvi.serialization.json.JSONObject): SettingsRouteVisualPayload {
    val destination =
        params.optString(SettingsRouteVisualDestinationKey)
            .trim()
            .takeIf { it.toSharedSettingsDestination() != null }
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
                    modifier = Modifier.padding(start = SlockSpacing.Md),
                    sectionStartPadding = 0.dp,
                    itemStartPadding = 0.dp
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
