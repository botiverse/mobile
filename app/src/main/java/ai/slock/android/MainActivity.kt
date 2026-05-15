@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ai.slock.android

import ai.slock.android.core.data.auth.AuthResult
import ai.slock.android.core.data.auth.AuthServerRepository
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.EncryptedAuthSessionStore
import ai.slock.android.core.data.auth.KtorAuthRepository
import ai.slock.android.core.data.diagnostics.SanitizedDiagnosticsLogBuffer
import ai.slock.android.core.data.diagnostics.NetworkDiagnosticsBridge
import ai.slock.android.core.data.diagnostics.SlockPerformanceDiagnostics
import ai.slock.android.core.data.locale.AppLocale
import ai.slock.android.core.data.locale.LocaleSnapshot
import ai.slock.android.core.data.local.LocalTaskContext
import ai.slock.android.core.data.local.LocalThreadTimeline
import ai.slock.android.core.data.local.SlockLocalWriteThrough
import ai.slock.android.core.data.local.createSlockLocalStore
import ai.slock.android.core.data.local.toThreadUiState
import ai.slock.android.core.data.locale.PersistentLocaleRepository
import ai.slock.android.core.data.locale.SharedPreferencesLocalePreferenceStore
import ai.slock.android.core.data.network.RealChannelRepository
import ai.slock.android.core.data.network.RealConversationRepository
import ai.slock.android.core.data.network.RealInboxRepository
import ai.slock.android.core.data.network.RealMemberRepository
import ai.slock.android.core.data.network.RealReadReceiptRepository
import ai.slock.android.core.data.network.RealSavedRepository
import ai.slock.android.core.data.network.RealSearchRepository
import ai.slock.android.core.data.network.RealServerUnreadRepository
import ai.slock.android.core.data.network.RealSettingsRepository
import ai.slock.android.core.data.network.RealSlockApiFactory
import ai.slock.android.core.data.network.RealTaskRepository
import ai.slock.android.core.data.preferences.ChatLayoutPreferenceSnapshot
import ai.slock.android.core.data.preferences.LargeScreenNavigationPreference
import ai.slock.android.core.data.preferences.MyMessageAlignmentPreference
import ai.slock.android.core.data.preferences.NotificationPreferenceRepository
import ai.slock.android.core.data.preferences.PersistentNotificationPreferenceRepository
import ai.slock.android.core.data.preferences.PersistentChatLayoutPreferenceRepository
import ai.slock.android.core.data.preferences.SharedPreferencesChatLayoutPreferenceStore
import ai.slock.android.core.data.preferences.SharedPreferencesNotificationPreferenceStore
import ai.slock.android.core.data.realtime.SessionRealtimeRepository
import ai.slock.android.core.data.realtime.SocketIoRealtimeClient
import ai.slock.android.core.data.realtime.SocketIoSocketTransport
import ai.slock.android.core.data.repository.AccountProfileSnapshot
import ai.slock.android.core.data.repository.AccountSettingsSnapshot
import ai.slock.android.core.data.repository.AddChannelMemberRequest
import ai.slock.android.core.data.repository.AgentProfileUpdateRequest
import ai.slock.android.core.data.repository.AgentActivitySnapshot
import ai.slock.android.core.data.repository.AgentDmSnapshot
import ai.slock.android.core.data.repository.AgentReminderSnapshot
import ai.slock.android.core.data.repository.AgentSkillsSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceTreeSnapshot
import ai.slock.android.core.data.repository.AttachmentUploadFile
import ai.slock.android.core.data.repository.AttachmentUploadRequest
import ai.slock.android.core.data.repository.ChannelJoinResult
import ai.slock.android.core.data.repository.ChannelMemberSnapshot
import ai.slock.android.core.data.repository.ChannelRepository
import ai.slock.android.core.data.repository.ChannelUnreadSnapshot
import ai.slock.android.core.data.repository.ChannelFilesSnapshot
import ai.slock.android.core.data.repository.ConnectedAccountSnapshot
import ai.slock.android.core.data.repository.ConnectedAccountStatus as RepositoryConnectedAccountStatus
import ai.slock.android.core.data.repository.ConversationRepository
import ai.slock.android.core.data.repository.CreateChannelRequest
import ai.slock.android.core.data.repository.CreateServerRequest
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.InboxSnapshot
import ai.slock.android.core.data.repository.InboxRepository
import ai.slock.android.core.data.repository.MemberRepository
import ai.slock.android.core.data.repository.MemberSnapshot
import ai.slock.android.core.data.repository.OpenDirectMessageResult
import ai.slock.android.core.data.repository.ReadReceiptCoordinator
import ai.slock.android.core.data.repository.ReadReceiptRepository
import ai.slock.android.core.data.repository.ReadReceiptRequest
import ai.slock.android.core.data.repository.ReadReceiptVisibilityGate
import ai.slock.android.core.data.repository.RealtimeRepository
import ai.slock.android.core.data.repository.RealtimeSubscription
import ai.slock.android.core.data.repository.RemoveChannelMemberRequest
import ai.slock.android.core.data.repository.SavedRepository
import ai.slock.android.core.data.repository.SavedSnapshot
import ai.slock.android.core.data.repository.SearchRepository
import ai.slock.android.core.data.repository.SearchSnapshot
import ai.slock.android.core.data.repository.SendMessageResult
import ai.slock.android.core.data.repository.ServerListSnapshot
import ai.slock.android.core.data.repository.ServerUnreadRepository
import ai.slock.android.core.data.repository.ServerUnreadSummarySnapshot
import ai.slock.android.core.data.repository.SettingsBillingSnapshot
import ai.slock.android.core.data.repository.SettingsComputerDetailSnapshot
import ai.slock.android.core.data.repository.SettingsComputersSnapshot
import ai.slock.android.core.data.repository.SettingsMobileSnapshot
import ai.slock.android.core.data.repository.SettingsRepository
import ai.slock.android.core.data.repository.SettingsServerDeleteRequest
import ai.slock.android.core.data.repository.SettingsServerInviteCreateRequest
import ai.slock.android.core.data.repository.SettingsServerProfileUpdateRequest
import ai.slock.android.core.data.repository.SettingsServerSnapshot
import ai.slock.android.core.data.repository.TaskFilter
import ai.slock.android.core.data.repository.TaskRepository
import ai.slock.android.core.data.repository.TaskSnapshot
import ai.slock.android.core.data.repository.ThreadActionResult
import ai.slock.android.core.data.repository.ThreadListSnapshot
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.data.storage.StorageClearTarget
import ai.slock.android.core.data.storage.StorageManagementRepository
import ai.slock.android.core.data.storage.StorageManagementSnapshot
import ai.slock.android.shared.SlockKuiklyActivity
import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationPermissions
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.MessageUpdated
import ai.slock.android.core.model.DirectMessageCreated
import ai.slock.android.core.model.ThreadUpdated
import ai.slock.android.core.model.TaskCreated
import ai.slock.android.core.model.TaskUpdated
import ai.slock.android.core.model.TaskDeleted
import ai.slock.android.core.model.MemberPresenceUpdated
import ai.slock.android.core.model.SyncResumeResponse
import ai.slock.android.core.model.Heartbeat
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ThreadConversation
import ai.slock.compose.model.messageStreamChannelId
import ai.slock.compose.model.parentChannelIdOrNull
import ai.slock.android.core.notification.shouldShowInboxNotification
import ai.slock.compose.model.threadIdentity
import ai.slock.compose.model.toGravatarAvatarReference
import ai.slock.compose.model.withPreviewMembershipFallback
import ai.slock.compose.model.withTitleFallback
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.ScreenError
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.auth.AuthLogoBadge
import ai.slock.android.feature.auth.CreateAccountScreen
import ai.slock.android.feature.auth.CreateAccountStatus
import ai.slock.android.feature.auth.ForgotPasswordScreen
import ai.slock.android.feature.auth.ForgotPasswordStatus
import ai.slock.android.feature.auth.LoginScreen
import ai.slock.android.feature.home.HomeContent
import ai.slock.android.feature.home.HomeUiState
import ai.slock.android.feature.home.withDraftIndicators
import ai.slock.android.feature.inbox.FollowedThreadsScreen
import ai.slock.android.feature.inbox.InboxContent
import ai.slock.android.feature.inbox.InboxFilter
import ai.slock.android.feature.inbox.InboxScreen
import ai.slock.android.feature.inbox.InboxUiState
import ai.slock.android.feature.inbox.SavedScreen
import ai.slock.android.feature.inbox.SearchResultsListScreen
import ai.slock.android.feature.inbox.clearAllUnread
import ai.slock.android.feature.inbox.applyRealtimeEvent
import ai.slock.android.feature.inbox.applySavedRealtimeEvent
import ai.slock.android.feature.inbox.mergeInboxPage
import ai.slock.android.feature.inbox.removeSavedMessage
import ai.slock.android.feature.inbox.savedMessageIdOrNull
import ai.slock.android.feature.inbox.contentOrNull
import ai.slock.android.feature.inbox.toConversationSummary
import ai.slock.android.feature.inbox.toFollowedThreadsUiState
import ai.slock.android.feature.inbox.toInboxUiState
import ai.slock.android.feature.inbox.toRepositoryFilter
import ai.slock.android.feature.inbox.toSavedInboxUiState
import ai.slock.android.feature.inbox.toSearchInboxUiState
import ai.slock.android.feature.inbox.visibleContent
import ai.slock.android.feature.inbox.visibleTotalCount
import ai.slock.android.feature.inbox.visibleUnreadCount
import ai.slock.android.feature.inbox.withBodyLoading
import ai.slock.android.feature.inbox.withFilterLoading
import ai.slock.android.feature.inbox.withSilentRefresh
import ai.slock.android.feature.members.MembersContent
import ai.slock.android.feature.members.MembersRouteScreen
import ai.slock.android.feature.members.MembersUiState
import ai.slock.android.feature.members.AgentProfileSaveState
import ai.slock.android.feature.sample.SlockStateSamples
import ai.slock.android.feature.settings.SettingsAboutScreen
import ai.slock.android.feature.settings.SettingsAboutSnapshot
import ai.slock.android.feature.settings.SettingsDiagnosticsScreen
import ai.slock.android.feature.settings.SettingsAccountProfile
import ai.slock.android.feature.settings.SettingsAccountScreen
import ai.slock.android.feature.settings.SettingsBillingScreen
import ai.slock.android.feature.settings.SettingsComputerDetailKuiklyScreen
import ai.slock.android.feature.settings.SettingsComputersKuiklyScreen
import ai.slock.android.feature.settings.SettingsConnectedAccount
import ai.slock.android.feature.settings.SettingsConnectedAccountStatus
import ai.slock.android.feature.settings.SettingsContent
import ai.slock.android.feature.settings.SettingsCurrentPlanState
import ai.slock.android.feature.settings.SettingsDetailHeader
import ai.slock.android.feature.settings.SettingsHomeItem
import ai.slock.android.feature.settings.SettingsHomeScreen
import ai.slock.android.feature.settings.SettingsMobileScreen
import ai.slock.android.feature.settings.SettingsPlanCardState
import ai.slock.android.feature.settings.SettingsPlanUsageRow
import ai.slock.android.feature.settings.SettingsServerManagementScreen
import ai.slock.android.feature.settings.SettingsStorageScreen
import ai.slock.android.feature.tasks.TasksContent
import ai.slock.android.feature.tasks.TasksPanel
import ai.slock.android.feature.tasks.TasksScreen
import ai.slock.android.feature.tasks.TasksUiState
import ai.slock.android.feature.tasks.withLoading
import ai.slock.android.feature.tasks.withRefreshFailure
import ai.slock.android.feature.tasks.withTaskStatusUpdateFailure
import ai.slock.android.feature.tasks.withTaskStatusUpdateSuccess
import ai.slock.android.feature.tasks.withTaskStatusUpdating
import ai.slock.android.feature.thread.AttachmentOpenStatus
import ai.slock.android.feature.thread.AttachmentOpenUiState
import ai.slock.android.feature.thread.AttachmentPreviewStatus
import ai.slock.android.feature.thread.AttachmentPreviewUiState
import ai.slock.android.feature.thread.ChannelJoinStatus
import ai.slock.android.feature.thread.ComposerState
import ai.slock.android.feature.thread.ComposerStatus
import ai.slock.android.feature.thread.MessagePageStatus
import ai.slock.android.feature.thread.PendingAttachment
import ai.slock.android.feature.thread.PendingAttachmentStatus
import ai.slock.android.feature.thread.ThreadContent
import ai.slock.android.feature.thread.ThreadFilesContent
import ai.slock.android.feature.thread.ThreadFilesUiState
import ai.slock.android.feature.thread.ThreadMemberAddStatus
import ai.slock.android.feature.thread.ThreadMemberRemoveStatus
import ai.slock.android.feature.thread.ThreadMembersPanelContent
import ai.slock.android.feature.thread.ThreadMembersPanelState
import ai.slock.android.feature.thread.ThreadMembersPickerContent
import ai.slock.android.feature.thread.ThreadHeaderNavigationIcon
import ai.slock.android.feature.thread.ThreadParentJumpTarget
import ai.slock.android.feature.thread.ThreadRefreshStatus
import ai.slock.android.feature.thread.ThreadReplyHeaderContext
import ai.slock.android.feature.thread.ThreadScreen
import ai.slock.android.feature.thread.ThreadSettingsModalStatus
import ai.slock.android.feature.thread.ThreadTab
import ai.slock.android.feature.thread.ThreadUiState
import ai.slock.android.feature.thread.applyReplyUnreadCounts
import ai.slock.android.feature.thread.clearReplyUnread
import ai.slock.android.feature.thread.mergeRefreshedThreadSnapshot
import ai.slock.android.feature.thread.sortedForThreadTimeline
import ai.slock.android.feature.thread.mergeRealtimeTaskEvent
import ai.slock.android.feature.thread.toSlockWebLinkTarget
import ai.slock.android.feature.thread.withTaskStatusUpdate
import ai.slock.android.feature.thread.withThreadRefreshStatus
import ai.slock.android.ui.components.SlockBottomTabItem
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.icons.LucideIcons
import ai.slock.android.ui.components.SlockMemberPickerSection
import ai.slock.android.ui.components.SlockMemberPickerMessage
import ai.slock.android.ui.components.SlockMemberPickerItem
import ai.slock.compose.markdown.ThreadMarkdownTag
import ai.slock.compose.markdown.ThreadMarkdownTagKind
import ai.slock.compose.markdown.threadMarkdownTaskReferenceKey
import ai.slock.android.ui.components.SlockModal
import ai.slock.android.ui.components.SlockModalAction
import ai.slock.android.ui.components.SlockModalActionStyle
import ai.slock.android.ui.components.SlockModalTextField
import ai.slock.android.ui.components.SlockPageHeader
import ai.slock.android.ui.components.SlockPresence
import ai.slock.android.ui.components.SlockAppBottomBarHost
import ai.slock.android.ui.components.SlockAdaptiveAppLayoutInfo
import ai.slock.android.ui.components.SlockAdaptiveAppScaffold
import ai.slock.android.ui.components.SlockAdaptiveBottomContentPadding
import ai.slock.android.ui.components.SlockLargeScreenNavigationStyle
import ai.slock.android.ui.components.SlockHomeNotificationItem
import ai.slock.android.ui.components.SlockHomeNotificationKind
import ai.slock.android.ui.components.SlockServerSwitcherItem
import ai.slock.android.ui.components.SlockStructuralHomeShell
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.components.SlockWorkspaceTreeState
import ai.slock.android.ui.components.SlockWindowLayoutMode
import ai.slock.android.ui.components.toSlockMemberPickerItem
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow
import ai.slock.compose.model.SlockWorkspaceFilePreview
import ai.slock.compose.model.SlockWorkspaceNode
import ai.slock.compose.network.SlockRequestPriority
import ai.slock.compose.network.realtime.RealtimeConnectionIntent
import ai.slock.compose.network.withSlockRequestPriority
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import java.io.IOException
import java.net.URL
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private var attachmentPickerResultHandler: ((List<Uri>) -> Unit)? = null
    private val attachmentImagePickerLauncher =
            registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
                attachmentPickerResultHandler?.invoke(uris)
            }
    private val attachmentFilePickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                attachmentPickerResultHandler?.invoke(uris)
            }

    private fun openAttachmentUri(url: String, mimeType: String?): Boolean =
            runCatching {
                val uri = Uri.parse(url)
                val intent =
                        Intent(Intent.ACTION_VIEW).apply {
                            if (mimeType.isNullOrBlank()) {
                                data = uri
                            } else {
                                setDataAndType(uri, mimeType)
                            }
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                startActivity(intent)
                true
            }
                    .getOrElse { error ->
                        if (error is ActivityNotFoundException) {
                            false
                        } else {
                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                    "SlockAttachment",
                                    "open_intent_failed result=error code=${error.javaClass.simpleName}"
                            )
                            false
                        }
                    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Install crash dump handler for debugging
        setupCrashDumpHandler()

        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockBoot",
                "main_activity_on_create buildMarker=auth-unread-diagnostics-v2"
        )
        enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(
                        SlockColor.BrandYellow.toArgb(),
                        SlockColor.BrandYellow.toArgb()
                ),
                navigationBarStyle = SystemBarStyle.light(
                        SlockColor.SurfaceWhite.toArgb(),
                        SlockColor.SurfaceWhite.toArgb()
                )
        )
        setContent {
            SlockTheme {
                SlockTestBuildFrame(show = BuildConfig.SHOW_TEST_BUILD_BADGE) {
                    SlockApp(
                            activityActions =
                                    AppActivityActions(
                                            requestNotificationPermission = { onResult ->
                                                ai.slock.android.core.data.diagnostics
                                                        .SlockDiagnosticsLogger.d(
                                                        "SlockSettingsNotification",
                                                        "request_permission_skipped reason=in_app_permission_request_disabled"
                                                )
                                                onResult(hasNotificationPermission())
                                            },
                                            pickAttachmentImage = { onResult ->
                                                attachmentPickerResultHandler = onResult
                                                attachmentImagePickerLauncher.launch(
                                                        PickVisualMediaRequest(
                                                                ActivityResultContracts
                                                                        .PickVisualMedia
                                                                        .ImageOnly
                                                        )
                                                )
                                            },
                                            pickAttachmentFile = { onResult ->
                                                attachmentPickerResultHandler = onResult
                                                attachmentFilePickerLauncher.launch(arrayOf("*/*"))
                                            },
                                            openAttachmentUri = { url, mimeType ->
                                                openAttachmentUri(url = url, mimeType = mimeType)
                                            }
                                    )
                    )
                }
            }
        }
        SlockPerformanceDiagnostics.install(window)
    }

    private fun setupCrashDumpHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Build crash info
                val crashInfo = buildString {
                    appendLine("Crash at: ${java.util.Date()}")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable.javaClass.name}")
                    appendLine("Message: ${throwable.message}")
                    appendLine()
                    appendLine("Stack trace:")
                    appendLine(android.util.Log.getStackTraceString(throwable))
                }

                // Write to file
                val crashDir = getExternalFilesDir(null)?.resolve("crashes")
                crashDir?.mkdirs()
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                val crashFile = crashDir?.resolve("crash-$timestamp.txt")
                crashFile?.writeText(crashInfo)
                android.util.Log.e("SlockCrashDump", "Crash dump written to: ${crashFile?.absolutePath}")

                // Copy to clipboard
                try {
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Crash Info", crashInfo)
                    clipboard.setPrimaryClip(clip)
                    android.util.Log.e("SlockCrashDump", "Crash info copied to clipboard")

                    // Show toast on main thread
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            this,
                            "Crash info copied to clipboard",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SlockCrashDump", "Failed to copy to clipboard", e)
                }
            } catch (e: Exception) {
                android.util.Log.e("SlockCrashDump", "Failed to write crash dump", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

@Composable
private fun SlockTestBuildFrame(show: Boolean, content: @Composable () -> Unit) {
    if (!show) {
        content()
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        Text(
                text = "TEST",
                modifier =
                        Modifier.align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(top = 4.dp, end = 8.dp)
                                .background(SlockColor.AccentRed, RoundedCornerShape(4.dp))
                                .border(
                                        SlockStroke.Default,
                                        SlockColor.BorderBlack,
                                        RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                style = SlockType.Caption,
                color = SlockColor.TextBlack
        )
    }
}

data class AppActivityActions(
        val requestNotificationPermission: ((Boolean) -> Unit) -> Unit = { it(true) },
        val pickAttachmentImage: ((List<Uri>) -> Unit) -> Unit = { it(emptyList()) },
        val pickAttachmentFile: ((List<Uri>) -> Unit) -> Unit = { it(emptyList()) },
        val openAttachmentUri: (String, String?) -> Boolean = { _, _ -> false }
)

@Composable
fun SlockApp(
        modifier: Modifier = Modifier,
        activityActions: AppActivityActions = AppActivityActions()
) {
    val context = LocalContext.current
    val view = LocalView.current
    val localeStore = remember { SharedPreferencesLocalePreferenceStore(context) }
    val localeRepository = remember { PersistentLocaleRepository(localeStore) }
    val chatLayoutPreferenceStore = remember { SharedPreferencesChatLayoutPreferenceStore(context) }
    val chatLayoutPreferenceRepository = remember {
        PersistentChatLayoutPreferenceRepository(chatLayoutPreferenceStore)
    }
    val notificationPreferenceStore = remember {
        SharedPreferencesNotificationPreferenceStore(context)
    }
    val notificationPreferenceRepository: NotificationPreferenceRepository = remember {
        PersistentNotificationPreferenceRepository(notificationPreferenceStore)
    }
    val localeScope = rememberCoroutineScope()
    var localeSnapshot by remember {
        mutableStateOf(LocaleSnapshot(current = localeStore.readLocaleSync()))
    }
    var chatLayoutPreferenceSnapshot by remember {
        mutableStateOf(
                ChatLayoutPreferenceSnapshot(
                        myMessageAlignment = chatLayoutPreferenceStore.readMyMessageAlignmentSync(),
                        largeScreenNavigation =
                                chatLayoutPreferenceStore.readLargeScreenNavigationSync()
                )
        )
    }
    var notificationsEnabledByUser by remember {
        mutableStateOf(notificationPreferenceStore.readNotificationEnabledSync())
    }

    SlockLocaleProvider(locale = localeSnapshot.current) {
        SlockAppContent(
                localeSnapshot = localeSnapshot,
                onLocaleSelected = { locale ->
                    localeScope.launch { localeSnapshot = localeRepository.selectLocale(locale) }
                },
                chatLayoutPreferenceSnapshot = chatLayoutPreferenceSnapshot,
                onMyMessageAlignmentSelected = { alignment ->
                    localeScope.launch {
                        chatLayoutPreferenceSnapshot =
                                chatLayoutPreferenceRepository.selectMyMessageAlignment(alignment)
                    }
                },
                onLargeScreenNavigationSelected = { navigation ->
                    localeScope.launch {
                        chatLayoutPreferenceSnapshot =
                                chatLayoutPreferenceRepository
                                        .selectLargeScreenNavigation(navigation)
                    }
                },
                notificationsEnabledByUser = notificationsEnabledByUser,
                onNotificationsEnabledByUserChange = { enabled ->
                    localeScope.launch {
                        notificationsEnabledByUser =
                                notificationPreferenceRepository
                                        .setNotificationEnabled(enabled)
                                        .enabled
                    }
                },
                activityActions = activityActions,
                modifier = modifier
        )
    }
}

@Composable
private fun SlockLocaleProvider(locale: AppLocale, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current
    val localizedConfiguration =
            remember(locale, baseConfiguration) {
                Configuration(baseConfiguration).apply {
                    setLocales(LocaleList(locale.toJavaLocale()))
                }
            }
    val localizedContext =
            remember(locale, baseContext, localizedConfiguration) {
                baseContext.createConfigurationContext(localizedConfiguration)
            }

    if (activityResultRegistryOwner != null) {
        CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfiguration,
                LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
                content = content
        )
    } else {
        CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfiguration,
                content = content
        )
    }
}

@Composable
private fun SlockAppContent(
        localeSnapshot: LocaleSnapshot,
        onLocaleSelected: (AppLocale) -> Unit,
        chatLayoutPreferenceSnapshot: ChatLayoutPreferenceSnapshot,
        onMyMessageAlignmentSelected: (MyMessageAlignmentPreference) -> Unit,
        onLargeScreenNavigationSelected: (LargeScreenNavigationPreference) -> Unit,
        notificationsEnabledByUser: Boolean,
        onNotificationsEnabledByUserChange: (Boolean) -> Unit,
        activityActions: AppActivityActions,
        modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val authSessionStore = remember { EncryptedAuthSessionStore(context) }
    val authRepository = remember { KtorAuthRepository(authSessionStore) }
    val authTokenRefresher = remember { AuthTokenRefresher(authSessionStore) }
    val serverRepository = remember { AuthServerRepository(authSessionStore, authRepository) }
    var currentAuthSession by remember { mutableStateOf<AuthSession?>(null) }
    val networkDiagnosticsBridge = remember { NetworkDiagnosticsBridge() }
    val slockApi = remember {
        RealSlockApiFactory.create(store = authSessionStore, tokenRefresher = authTokenRefresher)
    }
    val channelRepository: ChannelRepository = remember {
        RealChannelRepository(authSessionStore, slockApi)
    }
    val inboxRepository: InboxRepository = remember {
        RealInboxRepository(authSessionStore, slockApi)
    }
    val savedRepository: SavedRepository = remember {
        RealSavedRepository(authSessionStore, slockApi)
    }
    val searchRepository: SearchRepository = remember {
        RealSearchRepository(authSessionStore, slockApi)
    }
    val conversationRepository: ConversationRepository = remember {
        RealConversationRepository(authSessionStore, slockApi)
    }
    val localWriteThrough = remember {
        SlockLocalWriteThrough(
                store = createSlockLocalStore(context),
                logger = { message ->
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockLocalStore",
                            message
                    )
                }
        )
    }
    val localTaskWriteMutex = remember { Mutex() }
    suspend fun writeTaskResultQueued(
            result: DataResult<TaskSnapshot>,
            source: String,
            serverId: ServerId?
    ) {
        withContext(Dispatchers.IO) {
            localTaskWriteMutex.withLock {
                localWriteThrough.writeTaskResult(result, source = source, serverId = serverId)
            }
        }
    }
    fun localReadServerId(): ServerId? = currentAuthSession?.currentServerId?.let(::ServerId)

    fun missingThreadServerState(
            threadId: ThreadId,
            source: String,
            current: ThreadUiState? = null
    ): ThreadUiState {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                "SlockServerScope",
                "fail_thread_local_read source=$source reason=missing_server threadId=${threadId.value}"
        )
        return (current ?: ThreadUiState(ScreenLoadState.Loading)).copy(
                loadState =
                        ScreenLoadState.Error(
                                ScreenError(
                                        code = "missing_thread_server_id",
                                        retryable = false
                                )
                        )
        )
    }

    fun localThreadUiState(
            threadId: ThreadId,
            fallbackHeader: ConversationSummary? = null,
            parentMessage: Message? = null,
            serverId: ServerId? = localReadServerId()
    ): ThreadUiState? =
            if (serverId == null) {
                missingThreadServerState(threadId, source = "localThreadUiState")
            } else {
            localWriteThrough.readThreadUiState(
                    threadId,
                    fallbackHeader = fallbackHeader,
                    parentMessage = parentMessage,
                    serverId = serverId
            )
            }

    fun observedLocalThreadUiState(
            threadId: ThreadId,
            current: ThreadUiState,
            fallbackHeader: ConversationSummary? = null,
            parentMessage: Message? = null,
            serverId: ServerId? = current.serverId ?: localReadServerId()
    ): ThreadUiState? =
            if (serverId == null) {
                missingThreadServerState(
                        threadId,
                        source = "observedLocalThreadUiState",
                        current = current
                )
            } else {
            localWriteThrough.readThreadTimeline(threadId, serverId)?.let { timeline ->
                val observed =
                        timeline.toThreadUiState(
                                fallbackHeader = fallbackHeader,
                                parentMessage = parentMessage,
                                serverId = serverId
                        )
                val currentContent = (current.loadState as? ScreenLoadState.Content)?.data
                val observedContent =
                        (observed.loadState as? ScreenLoadState.Content)?.data ?: return@let observed
                observed.copy(
                        loadState =
                                ScreenLoadState.Content(
                                        observedContent.copy(
                                                parentMessage =
                                                        observedContent.parentMessage
                                                                ?: currentContent?.parentMessage,
                                                olderMessagesStatus =
                                                        currentContent?.olderMessagesStatus
                                                                ?: observedContent
                                                                        .olderMessagesStatus,
                                                olderMessagesErrorCode =
                                                        currentContent?.olderMessagesErrorCode
                                                                ?: observedContent
                                                                        .olderMessagesErrorCode,
                                                newerMessagesStatus =
                                                        currentContent?.newerMessagesStatus
                                                                ?: observedContent
                                                                        .newerMessagesStatus,
                                                newerMessagesErrorCode =
                                                        currentContent?.newerMessagesErrorCode
                                                                ?: observedContent
                                                                        .newerMessagesErrorCode,
                                                highlightedMessageId =
                                                        currentContent?.highlightedMessageId
                                                                ?: observedContent
                                                                        .highlightedMessageId,
                                                replyActionErrorCode =
                                                        currentContent?.replyActionErrorCode
                                                                ?: observedContent
                                                                        .replyActionErrorCode,
                                                anchorMessageId =
                                                        currentContent?.anchorMessageId
                                                                ?: observedContent.anchorMessageId,
                                                anchorOffset =
                                                        currentContent?.anchorOffset
                                                                ?: observedContent.anchorOffset,
                                                refreshStatus =
                                                        currentContent?.refreshStatus
                                                                ?: observedContent.refreshStatus,
                                                refreshErrorCode =
                                                        currentContent?.refreshErrorCode
                                                                ?: observedContent.refreshErrorCode
                                        )
                                ),
                        selectedTab = current.selectedTab,
                        tasksState = current.tasksState,
                        filesState = current.filesState,
                        channelJoinStatus = current.channelJoinStatus,
                        channelJoinErrorCode = current.channelJoinErrorCode
                )
            }
            }

    fun LocalThreadTimeline.toObservedThreadUiState(
            current: ThreadUiState,
            fallbackHeader: ConversationSummary? = null,
            parentMessage: Message? = null
    ): ThreadUiState {
        val observed =
                toThreadUiState(
                        fallbackHeader = fallbackHeader,
                        parentMessage = parentMessage,
                        serverId = current.serverId
                )
        val currentContent = (current.loadState as? ScreenLoadState.Content)?.data
        val observedContent = (observed.loadState as? ScreenLoadState.Content)?.data
                ?: return observed
        return observed.copy(
                loadState =
                        ScreenLoadState.Content(
                                observedContent.copy(
                                        parentMessage =
                                                observedContent.parentMessage
                                                        ?: currentContent?.parentMessage,
                                        olderMessagesStatus =
                                                currentContent?.olderMessagesStatus
                                                        ?: observedContent.olderMessagesStatus,
                                        olderMessagesErrorCode =
                                                currentContent?.olderMessagesErrorCode
                                                        ?: observedContent.olderMessagesErrorCode,
                                        newerMessagesStatus =
                                                currentContent?.newerMessagesStatus
                                                        ?: observedContent.newerMessagesStatus,
                                        newerMessagesErrorCode =
                                                currentContent?.newerMessagesErrorCode
                                                        ?: observedContent.newerMessagesErrorCode,
                                        highlightedMessageId =
                                                currentContent?.highlightedMessageId
                                                        ?: observedContent.highlightedMessageId,
                                        replyActionErrorCode =
                                                currentContent?.replyActionErrorCode
                                                        ?: observedContent.replyActionErrorCode,
                                        anchorMessageId =
                                                currentContent?.anchorMessageId
                                                        ?: observedContent.anchorMessageId,
                                        anchorOffset =
                                                currentContent?.anchorOffset
                                                        ?: observedContent.anchorOffset,
                                        refreshStatus =
                                                currentContent?.refreshStatus
                                                        ?: observedContent.refreshStatus,
                                        refreshErrorCode =
                                                currentContent?.refreshErrorCode
                                                        ?: observedContent.refreshErrorCode
                                )
                        ),
                selectedTab = current.selectedTab,
                tasksState = current.tasksState,
                filesState = current.filesState,
                channelJoinStatus = current.channelJoinStatus,
                channelJoinErrorCode = current.channelJoinErrorCode
        )
    }
    val readReceiptRepository: ReadReceiptRepository = remember {
        RealReadReceiptRepository(authSessionStore, slockApi)
    }
    val taskRepository: TaskRepository = remember { RealTaskRepository(authSessionStore, slockApi) }
    val memberRepository: MemberRepository = remember {
        RealMemberRepository(
                store = authSessionStore,
                api = slockApi,
                warningLogger = { message ->
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockMembers",
                            message
                    )
                },
                logger = { message ->
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockMembers",
                            message
                    )
                }
        )
    }
    val serverUnreadRepository: ServerUnreadRepository = remember {
        RealServerUnreadRepository(authSessionStore, slockApi)
    }
    val settingsRepository: SettingsRepository = remember {
        RealSettingsRepository(
                store = authSessionStore,
                api = slockApi,
                logger = { message ->
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockSettingsComputers",
                            message
                    )
                },
                warningLogger = { message ->
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockSettingsComputers",
                            message
                    )
                }
        )
    }
    val storageManagementRepository = remember(context.applicationContext) {
        StorageManagementRepository(context.applicationContext)
    }
    val realtimeRepository: RealtimeRepository = remember {
        SessionRealtimeRepository(
                store = authSessionStore,
                client =
                        SocketIoRealtimeClient(
                                transport = SocketIoSocketTransport(),
                                logger = { message ->
                                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                            "SlockRealtime",
                                            message
                                    )
                                },
                                diagnostics = networkDiagnosticsBridge
                        ),
                tokenRefresher = authTokenRefresher
        )
    }
    val coroutineScope = rememberCoroutineScope()
    var authState by remember { mutableStateOf<AuthUiState>(AuthUiState.Restoring) }
    var restoreSplashVisible by remember { mutableStateOf(true) }
    var restoreCompletedState by remember { mutableStateOf<AuthUiState?>(null) }
    var authRoute by rememberSaveable { mutableStateOf(AuthRoute.Login) }
    var forgotPasswordStatus by remember { mutableStateOf(ForgotPasswordStatus.Default) }
    var createAccountStatus by remember { mutableStateOf(CreateAccountStatus.Default) }
    var destination by rememberSaveable { mutableStateOf(AppDestination.Home) }
    var homeRoute by rememberSaveable { mutableStateOf(HomeRoute.Shell) }
    var previousHomeRoute by rememberSaveable { mutableStateOf<HomeRoute?>(null) }
    var threadReturnDestination by rememberSaveable { mutableStateOf<AppDestination?>(null) }
    var threadReturnHomeRoute by rememberSaveable { mutableStateOf<HomeRoute?>(null) }
    var threadReturnPreviousHomeRoute by rememberSaveable { mutableStateOf<HomeRoute?>(null) }
    var threadReturnTab by rememberSaveable { mutableStateOf<ThreadTab?>(null) }
    var memberDetailReturnDestination by rememberSaveable { mutableStateOf<AppDestination?>(null) }
    var memberDetailReturnHomeRoute by rememberSaveable { mutableStateOf<HomeRoute?>(null) }
    var threadTab by rememberSaveable { mutableStateOf(ThreadTab.Chat) }
    var selectedThreadFilesRefreshSerial by remember { mutableStateOf(0) }
    var selectedThreadFilesRefreshRequest by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun requestSelectedThreadFilesRefresh(reason: String) {
        selectedThreadFilesRefreshSerial += 1
        selectedThreadFilesRefreshRequest = selectedThreadFilesRefreshSerial to reason
    }
    var composerValue by rememberSaveable {
        mutableStateOf(SlockStateSamples.threadContent.composerState.value)
    }
    var composerAsTask by rememberSaveable {
        mutableStateOf(SlockStateSamples.threadContent.composerState.asTask)
    }
    var composerFocusRequestNonce by rememberSaveable { mutableStateOf(0) }
    var localComposerMessages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var pendingAttachmentFiles by remember {
        mutableStateOf<List<AttachmentUploadFile>>(emptyList())
    }
    var failedAttachmentIds by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var composerDrafts by remember { mutableStateOf<Map<String, ComposerDraft>>(emptyMap()) }
    var attachmentOpenStates by remember {
        mutableStateOf<Map<AttachmentId, AttachmentOpenUiState>>(emptyMap())
    }
    var attachmentPreviewState by remember { mutableStateOf<AttachmentPreviewUiState?>(null) }
    var settingsDestination by rememberSaveable { mutableStateOf(SettingsDestination.Home) }
    var membersDetailVisible by rememberSaveable { mutableStateOf(false) }
    var membersDetailSelectedMemberId by rememberSaveable { mutableStateOf<String?>(null) }
    var membersState by remember { mutableStateOf(MembersUiState(ScreenLoadState.Loading)) }
    var serverListState by remember {
        mutableStateOf<ScreenLoadState<ServerListSnapshot>>(ScreenLoadState.Loading)
    }
    var selectedServerOverride by remember { mutableStateOf<ServerId?>(null) }
    var homeState by remember { mutableStateOf<HomeUiState>(HomeUiState(ScreenLoadState.Loading)) }
    var homeRefreshInProgress by remember { mutableStateOf(false) }
    var homeRefreshError by remember { mutableStateOf<String?>(null) }
    var homeRefreshErrorLabel by remember { mutableStateOf<String?>(null) }
    var homeRefreshRetryNonce by rememberSaveable { mutableStateOf(0) }
    var homeRefreshAutoRetryAttempted by rememberSaveable { mutableStateOf(false) }
    var manualHomeRefreshRequested by rememberSaveable { mutableStateOf(false) }
    var selectedServerRefreshRequested by rememberSaveable { mutableStateOf(false) }
    var homeResumeRefreshNonce by remember { mutableStateOf(0) }
    var handledHomeResumeRefreshNonce by remember { mutableStateOf(0) }
    var homeProgressivePrefetchedThreadIdsByServer by remember {
        mutableStateOf<Map<String, Set<String>>>(emptyMap())
    }
    var homeProgressivePrefetchInFlightThreadIdsByServer by remember {
        mutableStateOf<Map<String, Set<String>>>(emptyMap())
    }
    var inboxFilter by rememberSaveable { mutableStateOf(InboxFilter.All) }
    var inboxState by remember {
        mutableStateOf<InboxUiState>(InboxUiState(ScreenLoadState.Loading))
    }
    var inboxSnapshotsByServer by remember {
        mutableStateOf<Map<String, Map<InboxFilter, InboxContent>>>(emptyMap())
    }
    var followedThreadsState by remember {
        mutableStateOf<InboxUiState>(InboxUiState(ScreenLoadState.Loading))
    }
    var followedThreadsSnapshotsByServer by remember {
        mutableStateOf<Map<String, InboxContent>>(emptyMap())
    }
    var selectedThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var threadNavigationGeneration by remember { mutableStateOf(0) }
    var selectedThreadState by remember {
        mutableStateOf<ThreadUiState>(ThreadUiState(ScreenLoadState.Loading))
    }
    var archiveChannelStatus by remember { mutableStateOf(ThreadSettingsModalStatus.Default) }
    var threadMembersPanelState by remember { mutableStateOf(ThreadMembersPanelState()) }
    var selectedThreadNearBottom by remember { mutableStateOf(true) }
    var replyThreadBackStack by remember {
        mutableStateOf<List<ReplyThreadBackStackEntry>>(emptyList())
    }
    var threadReturnSelectedThreadId by remember { mutableStateOf<String?>(null) }
    var threadReturnSelectedThreadState by remember { mutableStateOf<ThreadUiState?>(null) }
    var threadReturnReplyThreadBackStack by remember {
        mutableStateOf<List<ReplyThreadBackStackEntry>?>(null)
    }
    var threadReturnThreadParentJumpTarget by remember { mutableStateOf<String?>(null) }
    var threadReturnLocalComposerMessages by remember { mutableStateOf<List<String>?>(null) }
    var threadReturnRealtimeThreadMessages by remember { mutableStateOf<List<Message>?>(null) }
    var threadReturnAttachmentOpenStates by remember {
        mutableStateOf<Map<AttachmentId, AttachmentOpenUiState>?>(null)
    }
    var threadReturnComposerValue by remember { mutableStateOf<String?>(null) }
    var threadReturnComposerAsTask by remember { mutableStateOf<Boolean?>(null) }
    var threadReturnPendingAttachmentFiles by remember {
        mutableStateOf<List<AttachmentUploadFile>?>(null)
    }
    var threadReturnFailedAttachmentIds by remember { mutableStateOf<Set<String>?>(null) }
    var threadReturnSelectedThreadNearBottom by remember { mutableStateOf<Boolean?>(null) }
    var threadParentJumpTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var optimisticSendMessagesByStream by remember {
        mutableStateOf<Map<String, Message>>(emptyMap())
    }
    var refreshingOriginParentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var originParentRefreshErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var savedState by remember {
        mutableStateOf<InboxUiState>(InboxUiState(ScreenLoadState.Loading))
    }
    var savedSnapshotsByServer by remember {
        mutableStateOf<Map<String, InboxContent>>(emptyMap())
    }
    var tasksFilter by rememberSaveable { mutableStateOf<TaskStatus?>(null) }
    var tasksState by remember {
        mutableStateOf<TasksUiState>(TasksUiState(ScreenLoadState.Loading))
    }
    var nextTasksRefreshSilent by remember { mutableStateOf(false) }
    var taskReferenceCache by remember { mutableStateOf<Map<TaskId, TaskSummary>>(emptyMap()) }
    var taskReferenceCacheVersion by remember { mutableStateOf(0) }
    var tasksRefreshNonce by remember { mutableStateOf(0) }
    var lastTasksRefreshKey by rememberSaveable { mutableStateOf<String?>(null) }
    var taskReferenceChooserLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var taskReferenceChooserIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchState by remember {
        mutableStateOf<InboxUiState>(
                InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
        )
    }
    var searchSnapshotsByServer by remember {
        mutableStateOf<Map<String, Map<String, InboxContent>>>(emptyMap())
    }
    var inboxRefreshInProgress by remember { mutableStateOf(false) }
    var followedThreadsRefreshInProgress by remember { mutableStateOf(false) }
    var savedRefreshInProgress by remember { mutableStateOf(false) }
    var searchRefreshInProgress by remember { mutableStateOf(false) }
    var realtimeSubscription by remember { mutableStateOf<RealtimeSubscription?>(null) }
    var realtimeLastSeq by rememberSaveable { mutableStateOf(0L) }
    var realtimeRetryJob by remember { mutableStateOf<Job?>(null) }
    var realtimeRetryGeneration by remember { mutableStateOf(0) }
    var realtimeThreadMessages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var threadRefreshNonce by remember { mutableStateOf(0) }
    var settingsAccountState by remember {
        mutableStateOf<ScreenLoadState<AccountSettingsSnapshot>>(ScreenLoadState.Loading)
    }
    var settingsComputersState by remember {
        mutableStateOf<ScreenLoadState<SettingsComputersSnapshot>>(ScreenLoadState.Loading)
    }
    var homeComputersState by remember {
        mutableStateOf<ScreenLoadState<SettingsComputersSnapshot>>(ScreenLoadState.Loading)
    }
    var dismissedHomeNotificationIds by rememberSaveable {
        mutableStateOf<List<String>>(emptyList())
    }

    fun selectedServerIdForUi(): ServerId? =
            selectedServerOverride
                    ?: (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.id
                    ?: currentAuthSession?.currentServerId?.let(::ServerId)

    fun isCurrentServer(serverId: ServerId): Boolean =
            authState is AuthUiState.Authenticated && selectedServerIdForUi() == serverId

    fun logStaleServerWrite(source: String, serverId: ServerId) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockServerScope",
                "skip_stale_result source=$source serverId=${serverId.value} currentServer=${selectedServerIdForUi()?.value.orEmpty()}"
        )
    }

    fun inboxSnapshot(
            filter: InboxFilter = inboxFilter,
            serverId: ServerId? = selectedServerIdForUi()
    ): InboxContent? = serverId?.value?.let { inboxSnapshotsByServer[it]?.get(filter) }

    fun followedThreadsSnapshot(serverId: ServerId? = selectedServerIdForUi()): InboxContent? =
            serverId?.value?.let(followedThreadsSnapshotsByServer::get)

    fun savedSnapshot(serverId: ServerId? = selectedServerIdForUi()): InboxContent? =
            serverId?.value?.let(savedSnapshotsByServer::get)

    fun searchSnapshot(
            query: String = searchQuery,
            serverId: ServerId? = selectedServerIdForUi()
    ): InboxContent? =
            query.takeIf(String::isNotBlank)?.let { key ->
                serverId?.value?.let { serverKey -> searchSnapshotsByServer[serverKey]?.get(key) }
            }

    val selectedServerIdForHomeNotifications = selectedServerIdForUi()
    val sessionServerIdForHomeNotifications = currentAuthSession?.currentServerId?.let(::ServerId)

    LaunchedEffect(
            authState,
            selectedServerIdForHomeNotifications?.value,
            sessionServerIdForHomeNotifications?.value,
            settingsRepository
    ) {
        if (authState !is AuthUiState.Authenticated ||
                        selectedServerIdForHomeNotifications == null
        ) {
            homeComputersState = ScreenLoadState.Loading
            return@LaunchedEffect
        }
        if (sessionServerIdForHomeNotifications != selectedServerIdForHomeNotifications) {
            homeComputersState = ScreenLoadState.Loading
            return@LaunchedEffect
        }
        val serverId = selectedServerIdForHomeNotifications
        homeComputersState = ScreenLoadState.Loading
        val result =
                withSlockRequestPriority(
                        priority = SlockRequestPriority.Background,
                        key = "settings:computers:${serverId.value}",
                        reason = "home_notifications_computers"
                ) {
                    settingsRepository.getComputers()
                }.toScreenLoadState()
        if (selectedServerIdForUi() == serverId) {
            homeComputersState = result
        }
    }

    fun clearInboxRouteCachesForServerSwitch() {
        val currentServerId = selectedServerIdForUi()?.value ?: return
        inboxSnapshotsByServer = inboxSnapshotsByServer - currentServerId
        followedThreadsSnapshotsByServer = followedThreadsSnapshotsByServer - currentServerId
        savedSnapshotsByServer = savedSnapshotsByServer - currentServerId
        searchSnapshotsByServer = searchSnapshotsByServer - currentServerId
    }

    fun setInboxState(next: InboxUiState) {
        next.contentOrNull()?.let { content ->
            val serverKey = content.server.id.value
            inboxSnapshotsByServer =
                    inboxSnapshotsByServer + (
                            serverKey to
                                    (
                                            inboxSnapshotsByServer[serverKey].orEmpty() +
                                                    (next.selectedFilter to content)
                                            )
                            )
        }
        val renderServerId = next.contentOrNull()?.server?.id ?: selectedServerIdForUi()
        inboxState =
                next.copy(
                        previousContent =
                                if (next.loadState is ScreenLoadState.Content) null
                                else inboxSnapshot(next.selectedFilter, renderServerId)
                )
    }

    fun setFollowedThreadsState(next: InboxUiState) {
        next.contentOrNull()?.let { content ->
            followedThreadsSnapshotsByServer =
                    followedThreadsSnapshotsByServer + (content.server.id.value to content)
        }
        val renderServerId = next.contentOrNull()?.server?.id ?: selectedServerIdForUi()
        followedThreadsState =
                next.copy(
                        previousContent =
                                if (next.loadState is ScreenLoadState.Content) null
                                else followedThreadsSnapshot(renderServerId)
                )
    }

    fun setSavedState(next: InboxUiState) {
        next.contentOrNull()?.let { content ->
            savedSnapshotsByServer = savedSnapshotsByServer + (content.server.id.value to content)
        }
        val renderServerId = next.contentOrNull()?.server?.id ?: selectedServerIdForUi()
        savedState =
                next.copy(
                        previousContent =
                                if (next.loadState is ScreenLoadState.Content) null
                                else savedSnapshot(renderServerId)
                )
    }

    fun setSearchState(next: InboxUiState, query: String = searchQuery) {
        next.contentOrNull()?.takeIf { query.isNotBlank() }?.let { content ->
            val serverKey = content.server.id.value
            searchSnapshotsByServer =
                    searchSnapshotsByServer + (
                            serverKey to
                                    (searchSnapshotsByServer[serverKey].orEmpty() + (query to content))
                            )
        }
        val renderServerId = next.contentOrNull()?.server?.id ?: selectedServerIdForUi()
        searchState =
                next.copy(
                        previousContent =
                                if (next.loadState is ScreenLoadState.Content || query.isBlank()) null
                                else searchSnapshot(query, renderServerId)
                )
    }

    fun DataResult<InboxSnapshot>.toInboxRouteState(filter: InboxFilter): InboxUiState =
            when (this) {
                is DataResult.Content -> toInboxUiState(filter)
                is DataResult.Empty, is DataResult.Error, is DataResult.NoPermission ->
                        inboxSnapshot(filter)?.toInboxUiState(filter) ?: toInboxUiState(filter)
            }

    fun DataResult<ThreadListSnapshot>.toFollowedThreadsRouteState(): InboxUiState =
            when (this) {
                is DataResult.Content -> toFollowedThreadsUiState()
                is DataResult.Empty, is DataResult.Error, is DataResult.NoPermission ->
                        followedThreadsSnapshot()?.toInboxUiState() ?: toFollowedThreadsUiState()
            }

    fun DataResult<SavedSnapshot>.toSavedRouteState(): InboxUiState =
            when (this) {
                is DataResult.Content -> toSavedInboxUiState()
                is DataResult.Empty, is DataResult.Error, is DataResult.NoPermission ->
                        savedSnapshot()?.toInboxUiState() ?: toSavedInboxUiState()
            }

    fun DataResult<SearchSnapshot>.toSearchRouteState(query: String): InboxUiState =
            when (this) {
                is DataResult.Content -> toSearchInboxUiState()
                is DataResult.Empty, is DataResult.Error, is DataResult.NoPermission ->
                        searchSnapshot(query)?.toInboxUiState() ?: toSearchInboxUiState()
            }

    var settingsComputerDetailState by remember {
        mutableStateOf<ScreenLoadState<SettingsComputerDetailSnapshot>>(ScreenLoadState.Loading)
    }
    var settingsSelectedComputerId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsServerState by remember {
        mutableStateOf<ScreenLoadState<SettingsServerSnapshot>>(ScreenLoadState.Loading)
    }
    var settingsServerActionFeedback by remember { mutableStateOf<String?>(null) }
    var settingsServerActionInProgress by remember { mutableStateOf(false) }
    var settingsBillingState by remember {
        mutableStateOf<ScreenLoadState<SettingsBillingSnapshot>>(ScreenLoadState.Loading)
    }
    var settingsMobileState by remember {
        mutableStateOf<ScreenLoadState<SettingsMobileSnapshot>>(ScreenLoadState.Loading)
    }
    var settingsStorageSnapshot by remember(storageManagementRepository) {
        mutableStateOf(storageManagementRepository.snapshot())
    }
    var settingsStorageActionInProgress by remember {
        mutableStateOf<StorageClearTarget?>(null)
    }
    var settingsStorageFeedback by remember { mutableStateOf<String?>(null) }

    fun refreshStorageSnapshot(feedback: String? = null) {
        coroutineScope.launch {
            settingsStorageSnapshot =
                    withContext(Dispatchers.IO) { storageManagementRepository.snapshot() }
            settingsStorageFeedback = feedback
        }
    }

    fun clearStorageTarget(target: StorageClearTarget) {
        coroutineScope.launch {
            settingsStorageActionInProgress = target
            settingsStorageFeedback = null
            settingsStorageSnapshot =
                    withContext(Dispatchers.IO) { storageManagementRepository.clear(target) }
            if (target == StorageClearTarget.TemporaryAndLogs) {
                SanitizedDiagnosticsLogBuffer.clear()
            }
            settingsStorageFeedback =
                    when (target) {
                        StorageClearTarget.ImageCache ->
                                context.getString(
                                        R.string.settings_storage_feedback_image_cache_cleared
                                )
                        StorageClearTarget.TemporaryAndLogs ->
                                context.getString(
                                        R.string.settings_storage_feedback_temp_logs_cleared
                                )
                    }
            settingsStorageActionInProgress = null
        }
    }
    var lastInboxNotificationMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsDiagnosticsVersion by remember { mutableStateOf(0) }
    var notificationPermissionGranted by remember {
        mutableStateOf(context.hasNotificationPermission())
    }
    var serverSwitcherExpanded by rememberSaveable { mutableStateOf(false) }
    var createServerVisible by rememberSaveable { mutableStateOf(false) }
    var createServerName by rememberSaveable { mutableStateOf("") }
    var createServerSlug by rememberSaveable { mutableStateOf("") }
    var createServerSubmitting by rememberSaveable { mutableStateOf(false) }
    var createServerError by rememberSaveable { mutableStateOf<String?>(null) }
    var serverGateMode by rememberSaveable { mutableStateOf(ServerGateMode.Choose) }
    var serverGateSubmitting by rememberSaveable { mutableStateOf(false) }
    var serverGateError by rememberSaveable { mutableStateOf<String?>(null) }
    var createChannelVisible by rememberSaveable { mutableStateOf(false) }
    var createChannelName by rememberSaveable { mutableStateOf("") }
    var createChannelDescription by rememberSaveable { mutableStateOf("") }
    var createChannelAgentMemberIds by rememberSaveable {
        mutableStateOf<List<String>>(emptyList())
    }
    var createChannelUserMemberIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var createChannelSubmitting by rememberSaveable { mutableStateOf(false) }
    var createChannelError by rememberSaveable { mutableStateOf<String?>(null) }
    var appInForeground by remember { mutableStateOf(true) }
    var networkAvailable by remember { mutableStateOf(context.hasUsableNetwork()) }
    var networkResumeNonce by remember { mutableStateOf(0) }
    val performanceRoute =
            when (destination) {
                AppDestination.Home ->
                        when (homeRoute) {
                            HomeRoute.Shell -> "home"
                            HomeRoute.Thread -> "thread:${selectedThreadId.orEmpty()}"
                            HomeRoute.Inbox -> "inbox:${inboxFilter.name.lowercase()}"
                            HomeRoute.FollowedThreads -> "followed_threads"
                            HomeRoute.Saved -> "saved"
                            HomeRoute.Search -> "search:${searchQuery.isNotBlank()}"
                        }
                AppDestination.Tasks -> "tasks:${tasksFilter?.name?.lowercase() ?: "all"}"
                AppDestination.Members ->
                        if (membersDetailVisible || membersDetailSelectedMemberId != null) {
                            "members:detail"
                        } else {
                            "members"
                        }
                AppDestination.Settings -> "settings:${settingsDestination.name.lowercase()}"
            }
    val performanceOperation =
            when {
                selectedThreadState.refreshStatus() == ThreadRefreshStatus.Loading ->
                        "thread_refresh"
                selectedThreadState.composerState.status == ComposerStatus.Uploading -> "send_upload"
                selectedThreadState.composerState.status == ComposerStatus.Sending -> "send_message"
                homeRefreshInProgress -> "home_refresh"
                inboxRefreshInProgress -> "inbox_refresh"
                followedThreadsRefreshInProgress -> "followed_refresh"
                savedRefreshInProgress -> "saved_refresh"
                destination == AppDestination.Tasks && tasksState.loadState is ScreenLoadState.Loading ->
                        "tasks_refresh"
                else -> "idle"
            }
    SideEffect {
        SlockPerformanceDiagnostics.updateRoute(view, performanceRoute)
        SlockPerformanceDiagnostics.updateOperation(view, performanceOperation)
    }
    val pageStatusBarColor =
            when {
                authState is AuthUiState.Restoring || authState !is AuthUiState.Authenticated ->
                        SlockColor.SurfaceCream
                destination == AppDestination.Home && homeRoute == HomeRoute.Thread ->
                        SlockColor.SurfaceWhite
                destination == AppDestination.Members &&
                        (membersDetailVisible || membersDetailSelectedMemberId != null) ->
                        SlockColor.SurfaceWhite
                destination == AppDestination.Settings &&
                        settingsDestination != SettingsDestination.Home -> SlockColor.SurfaceWhite
                else -> SlockColor.BrandYellow
            }
    val statusBarColor =
            if (authState is AuthUiState.Restoring || authState !is AuthUiState.Authenticated) {
                SlockColor.SurfaceCream
            } else {
                pageStatusBarColor
    }
    DisposableEffect(view, statusBarColor, appInForeground) {
        val activity = view.context.findComponentActivity()
        val previousColor = activity?.window?.statusBarColor
        if (activity != null) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockSystemBar",
                    "apply_status_bar color=${statusBarColor.toStatusBarLogName()} " +
                            "destination=${destination.key} homeRoute=$homeRoute " +
                            "settingsDestination=$settingsDestination membersDetail=${membersDetailVisible || membersDetailSelectedMemberId != null}"
            )
            activity.enableEdgeToEdge(
                    statusBarStyle =
                            SystemBarStyle.light(
                                    statusBarColor.toArgb(),
                                    statusBarColor.toArgb()
                            ),
                    navigationBarStyle =
                            SystemBarStyle.light(
                                    SlockColor.SurfaceWhite.toArgb(),
                                    SlockColor.SurfaceWhite.toArgb()
                            )
            )
            activity.window.statusBarColor = statusBarColor.toArgb()
        }
        onDispose {
            if (activity != null && previousColor != null) {
                activity.enableEdgeToEdge(
                        statusBarStyle =
                                SystemBarStyle.light(previousColor, previousColor),
                        navigationBarStyle =
                                SystemBarStyle.light(
                                        SlockColor.SurfaceWhite.toArgb(),
                                        SlockColor.SurfaceWhite.toArgb()
                                )
                )
                activity.window.statusBarColor = previousColor
            }
        }
    }
    var unreadRefreshNonce by remember { mutableStateOf(0) }
    var selectedThreadRetryAction by remember {
        mutableStateOf<(() -> Unit)?>(null)
    }
    val showBottomBar =
            (destination != AppDestination.Home || homeRoute == HomeRoute.Shell) &&
                    !(destination == AppDestination.Members && membersDetailVisible)
    val showNavigation = showBottomBar

    fun clearThreadReturnTarget() {
        threadReturnDestination = null
        threadReturnHomeRoute = null
        threadReturnPreviousHomeRoute = null
        threadReturnTab = null
        threadReturnSelectedThreadId = null
        threadReturnSelectedThreadState = null
        threadReturnReplyThreadBackStack = null
        threadReturnThreadParentJumpTarget = null
        threadReturnLocalComposerMessages = null
        threadReturnRealtimeThreadMessages = null
        threadReturnAttachmentOpenStates = null
        threadReturnComposerValue = null
        threadReturnComposerAsTask = null
        threadReturnPendingAttachmentFiles = null
        threadReturnFailedAttachmentIds = null
        threadReturnSelectedThreadNearBottom = null
    }

    fun captureThreadReturnTarget() {
        threadReturnDestination = destination
        threadReturnHomeRoute = homeRoute.takeIf { destination == AppDestination.Home }
        threadReturnPreviousHomeRoute = previousHomeRoute.takeIf { destination == AppDestination.Home }
        threadReturnTab = threadTab.takeIf { destination == AppDestination.Home }
        if (destination == AppDestination.Home && homeRoute == HomeRoute.Thread) {
            threadReturnSelectedThreadId = selectedThreadId
            threadReturnSelectedThreadState = selectedThreadState
            threadReturnReplyThreadBackStack = replyThreadBackStack
            threadReturnThreadParentJumpTarget = threadParentJumpTarget
            threadReturnLocalComposerMessages = localComposerMessages
            threadReturnRealtimeThreadMessages = realtimeThreadMessages
            threadReturnAttachmentOpenStates = attachmentOpenStates
            threadReturnComposerValue = composerValue
            threadReturnComposerAsTask = composerAsTask
            threadReturnPendingAttachmentFiles = pendingAttachmentFiles
            threadReturnFailedAttachmentIds = failedAttachmentIds
            threadReturnSelectedThreadNearBottom = selectedThreadNearBottom
        }
    }

    fun currentHomeServerFallback(serverId: ServerId): ServerSummary? =
            (homeState.loadState as? ScreenLoadState.Content)
                    ?.data
                    ?.server
                    ?.takeIf { it.id == serverId }
                    ?: (serverListState as? ScreenLoadState.Content)
                            ?.data
                            ?.selectedServer
                            ?.takeIf { it.id == serverId }
                    ?: currentAuthSession?.servers
                            ?.firstOrNull { it.id == serverId.value }
                            ?.toServerSummary()

    fun currentHomeMemberFallback(): MemberProfile =
            (homeState.loadState as? ScreenLoadState.Content)?.data?.currentMember
                    ?: currentAuthSession?.user?.toMemberProfile()
                    ?: localComposerAuthor

    fun publishHomeFromLocal(
            serverId: ServerId,
            serverFallback: ServerSummary? = null,
            currentMemberFallback: MemberProfile? = null,
            preservedServerUnreadCount: Int? = null
    ): Boolean {
        val server = serverFallback ?: currentHomeServerFallback(serverId) ?: return false
        val member = currentMemberFallback ?: currentHomeMemberFallback()
        val snapshot =
                localWriteThrough.readHomeSnapshot(
                        serverId = serverId,
                        serverFallback = server,
                        currentMemberFallback = member
                ) ?: return false
        homeState =
                DataResult.Content(snapshot).toHomeUiState(
                        selectedServerId = serverId.value,
                        preservedServerUnreadCount =
                                preservedServerUnreadCount
                                        ?: homeState.serverUnreadFor(serverId)
                                        ?: serverListState.serverUnreadFor(serverId)
                )
        return true
    }

    fun reconcileReadLocally(channelId: ChannelId) {
        val serverId =
                selectedServerOverride
                        ?: (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.id
                        ?: currentAuthSession?.currentServerId?.let(::ServerId)
        localWriteThrough.clearConversationUnread(
                channelId,
                source = "read_receipt_optimistic",
                serverId = serverId
        )
        if (serverId == null || !publishHomeFromLocal(serverId)) {
            homeState = homeState.clearUnread(channelId)
        }
        selectedThreadState = selectedThreadState.clearReplyUnread(channelId)
        replyThreadBackStack =
                replyThreadBackStack.map { entry ->
                    entry.copy(
                            selectedThreadState =
                                    entry.selectedThreadState.clearReplyUnread(channelId)
                    )
                }
        setInboxState(inboxState.clearUnread(channelId))
        setFollowedThreadsState(followedThreadsState.clearUnread(channelId))
        setSavedState(savedState.clearUnread(channelId))
        setSearchState(searchState.clearUnread(channelId))
    }

    fun reconcileUnreadCountsFromServer(reason: String, channelId: ChannelId) {
        val serverId =
                selectedServerOverride
                        ?: (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.id
                                ?: currentAuthSession?.currentServerId?.let(::ServerId) ?: return
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockRead",
                    "reconcile_start reason=$reason channelId=${channelId.value} serverId=${serverId.value}"
            )
            when (val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "unread:${serverId.value}",
                            reason = "read_reconcile_$reason"
                    ) {
                        readReceiptRepository.getUnreadCounts(serverId)
                    }) {
                is DataResult.Content -> {
                    localWriteThrough.writeUnreadCounts(result, source = "read_reconcile_$reason")
                    if (!isCurrentServer(serverId)) {
                        logStaleServerWrite("read_reconcile_$reason", serverId)
                        return@launch
                    }
                    serverListState = serverListState.applyUnreadCounts(result.data)
                    val publishedHome =
                            publishHomeFromLocal(
                                    serverId,
                                    preservedServerUnreadCount = result.data.totalUnreadCount
                            )
                    if (!publishedHome) {
                        homeState = homeState.applyUnreadCounts(result.data)
                    }
                    selectedThreadState = selectedThreadState.applyReplyUnreadCounts(result.data)
                    replyThreadBackStack =
                            replyThreadBackStack.map { entry ->
                                entry.copy(
                                        selectedThreadState =
                                                entry.selectedThreadState.applyReplyUnreadCounts(
                                                        result.data
                                                )
                                )
                            }
                    setInboxState(inboxState.applyUnreadCounts(result.data))
                    setFollowedThreadsState(followedThreadsState.applyUnreadCounts(result.data))
                    setSavedState(savedState.applyUnreadCounts(result.data))
                    setSearchState(searchState.applyUnreadCounts(result.data))
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockRead",
                            "reconcile_success reason=$reason channelId=${channelId.value} total=${result.data.totalUnreadCount}"
                    )
                }
                is DataResult.Empty -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockRead",
                            "reconcile_empty reason=$reason channelId=${channelId.value} empty=${result.reason}"
                    )
                }
                is DataResult.Error -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockRead",
                            "reconcile_error reason=$reason channelId=${channelId.value} code=${result.error.code}"
                    )
                }
                is DataResult.NoPermission -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockRead",
                            "reconcile_no_permission reason=$reason channelId=${channelId.value} code=${result.blocker.code}"
                    )
                }
            }
        }
    }

    fun reconcileAllUnreadCountsFromServer(reason: String, serverId: ServerId) {
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockRead",
                    "reconcile_all_start reason=$reason serverId=${serverId.value}"
            )
            when (val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "unread:${serverId.value}",
                            reason = "read_reconcile_all_$reason"
                    ) {
                        readReceiptRepository.getUnreadCounts(serverId)
                    }) {
                is DataResult.Content -> {
                    localWriteThrough.writeUnreadCounts(result, source = "read_reconcile_all_$reason")
                    if (!isCurrentServer(serverId)) {
                        logStaleServerWrite("read_reconcile_all_$reason", serverId)
                        return@launch
                    }
                    serverListState = serverListState.applyUnreadCounts(result.data)
                    val publishedHome =
                            publishHomeFromLocal(
                                    serverId,
                                    preservedServerUnreadCount = result.data.totalUnreadCount
                            )
                    if (!publishedHome) {
                        homeState = homeState.applyUnreadCounts(result.data)
                    }
                    selectedThreadState = selectedThreadState.applyReplyUnreadCounts(result.data)
                    replyThreadBackStack =
                            replyThreadBackStack.map { entry ->
                                entry.copy(
                                        selectedThreadState =
                                                entry.selectedThreadState.applyReplyUnreadCounts(
                                                        result.data
                                                )
                                )
                            }
                    setInboxState(inboxState.applyUnreadCounts(result.data))
                    setFollowedThreadsState(followedThreadsState.applyUnreadCounts(result.data))
                    setSavedState(savedState.applyUnreadCounts(result.data))
                    setSearchState(searchState.applyUnreadCounts(result.data))
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockRead",
                            "reconcile_all_success reason=$reason serverId=${serverId.value} total=${result.data.totalUnreadCount}"
                    )
                }
                is DataResult.Empty -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockRead",
                            "reconcile_all_empty reason=$reason serverId=${serverId.value} empty=${result.reason}"
                    )
                }
                is DataResult.Error -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockRead",
                            "reconcile_all_error reason=$reason serverId=${serverId.value} code=${result.error.code}"
                    )
                }
                is DataResult.NoPermission -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockRead",
                            "reconcile_all_no_permission reason=$reason serverId=${serverId.value} code=${result.blocker.code}"
                    )
                }
            }
        }
    }
    val readReceiptVisibilityGate = remember { ReadReceiptVisibilityGate() }
    val readReceiptCoordinator =
            remember(readReceiptRepository, coroutineScope, readReceiptVisibilityGate) {
                ReadReceiptCoordinator(
                        scope = coroutineScope,
                        repository = readReceiptRepository,
                        requestRunner = { request, block ->
                            withSlockRequestPriority(
                                    priority = SlockRequestPriority.Foreground,
                                    key = "read:${request.channelId.value}",
                                    reason = "mark_read_${request.priorityReasonSuffix()}"
                            ) {
                                block()
                            }
                        },
                        onOptimisticRead = { channelId ->
                            reconcileReadLocally(channelId)
                        },
                        onResult = { request, result ->
                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                    "SlockRead",
                                    "mark_read_result channelId=${request.channelId.value} seq=${request.logSeq()} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
                            )
                        },
                        onFailure = { request, _ ->
                            readReceiptVisibilityGate.clear(request.channelId)
                            reconcileUnreadCountsFromServer(
                                    reason = "mark_read_failed",
                                    channelId = request.channelId
                            )
                        }
                )
            }

    fun resetToSignedOut(message: String? = null) {
        realtimeSubscription?.close()
        realtimeSubscription = null
        realtimeRepository.disconnect()
        composerDrafts = emptyMap()
        pendingAttachmentFiles = emptyList()
        failedAttachmentIds = emptySet()
        currentAuthSession = null
        authState = AuthUiState.SignedOut(message)
        authRoute = AuthRoute.Login
        destination = AppDestination.Home
        homeRoute = HomeRoute.Shell
        previousHomeRoute = null
        settingsDestination = SettingsDestination.Home
        settingsSelectedComputerId = null
        selectedServerOverride = null
        serverListState = ScreenLoadState.Loading
        serverGateMode = ServerGateMode.Choose
        serverGateSubmitting = false
        serverGateError = null
    }

    fun enterSelectedServer(snapshot: ServerListSnapshot) {
        val selected = snapshot.selectedServer ?: snapshot.servers.firstOrNull() ?: return
        selectedServerOverride = selected.id
        serverListState = ScreenLoadState.Content(snapshot.copy(selectedServer = selected))
        destination = AppDestination.Home
        homeRoute = HomeRoute.Shell
        previousHomeRoute = null
        homeRefreshError = null
        homeRefreshErrorLabel = null
        homeRefreshAutoRetryAttempted = false
        manualHomeRefreshRequested = false
        selectedServerRefreshRequested = true
        homeState = HomeUiState(ScreenLoadState.Loading, selectedServerId = selected.id.value)
        clearInboxRouteCachesForServerSwitch()
        setInboxState(inboxState.withBodyLoading(inboxFilter))
        setFollowedThreadsState(followedThreadsState.withBodyLoading())
        setSavedState(savedState.withBodyLoading())
        setSearchState(
                if (searchQuery.isBlank()) {
                    InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
                } else {
                    searchState.withBodyLoading()
                },
                query = searchQuery
        )
        serverGateMode = ServerGateMode.Choose
        serverGateSubmitting = false
        serverGateError = null
        homeResumeRefreshNonce += 1
    }

    fun signOutFromAuthenticated(source: String) {
        coroutineScope.launch {
            when (val result = authRepository.logout()) {
                is AuthResult.Success -> {
                    resetToSignedOut()
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockAuth",
                            "${source}_logout_success"
                    )
                }
                AuthResult.Unauthenticated -> {
                    resetToSignedOut()
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockAuth",
                            "${source}_logout_unauthenticated"
                    )
                }
                is AuthResult.Failure -> {
                    resetToSignedOut(result.message)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockAuth",
                            "${source}_logout_failure message=${result.message}"
                    )
                }
            }
        }
    }

    fun markThreadReadIfLoaded(content: ThreadContent) {
        val channelId = content.header.messageStreamChannelId()
        val maxSeq = content.messages.mapNotNull { it.seq }.maxOrNull()
        if (maxSeq != null && maxSeq > 0L) {
            if (readReceiptVisibilityGate.shouldMarkReadToSeq(channelId, maxSeq)) {
                readReceiptCoordinator.markReadToSeq(channelId, maxSeq)
            }
        } else {
            if (readReceiptVisibilityGate.shouldMarkReadAll(channelId)) {
                readReceiptCoordinator.markReadAll(channelId)
            }
        }
    }

    fun markCurrentThreadReadToSeq(seq: Long?) {
        val currentContent =
                (selectedThreadState.loadState as? ScreenLoadState.Content)?.data
        val channelId =
                currentContent?.header?.messageStreamChannelId()
                        ?: selectedThreadId?.let(::ChannelId)
                        ?: return
        if (seq != null && seq > 0L) {
            if (readReceiptVisibilityGate.shouldMarkReadToSeq(channelId, seq)) {
                readReceiptCoordinator.markReadToSeq(channelId, seq)
            }
        } else {
            currentContent?.let(::markThreadReadIfLoaded)
        }
    }

    fun markInboxItemRead(item: InboxItem) {
        val channelId = item.threadChannelId ?: item.channelId
        if (readReceiptVisibilityGate.shouldMarkReadAll(channelId)) {
            readReceiptCoordinator.markReadAll(channelId)
        }
    }

    fun rememberTaskReferences(tasks: List<TaskSummary>) {
        if (tasks.isEmpty()) return
        val merged = taskReferenceCache.toMutableMap()
        tasks.forEach { task ->
            val current = merged[task.id]
            merged[task.id] =
                    if (current == null) task else current.mergeTaskReferenceCacheUpdate(task)
        }
        val next = merged.toMap()
        if (next != taskReferenceCache) {
            taskReferenceCache = next
            taskReferenceCacheVersion += 1
        }
    }

    fun rememberTaskReference(task: TaskSummary) {
        rememberTaskReferences(listOf(task))
    }

    fun List<TaskSummary>.withCachedTaskReferences(): List<TaskSummary> =
            map { task ->
                taskReferenceCache[task.id]?.let(task::withTaskReferenceFieldsFrom) ?: task
            }

    fun Message.withCachedTaskReferences(): Message =
            taskReferenceCache.findTaskForMessage(this)?.let { cached ->
                copy(task = task?.withTaskReferenceFieldsFrom(cached) ?: cached)
            } ?: this

    fun ThreadContent.withCachedTaskReferences(): ThreadContent =
            copy(
                    messages = messages.map { it.withCachedTaskReferences() },
                    tasks = tasks.withCachedTaskReferences(),
                    parentMessage = parentMessage?.withCachedTaskReferences()
            )

    fun ThreadUiState.withCachedTaskReferences(): ThreadUiState =
            copy(
                    loadState =
                            (loadState as? ScreenLoadState.Content)?.let { contentState ->
                                ScreenLoadState.Content(
                                        contentState.data.withCachedTaskReferences()
                                )
                            } ?: loadState,
                    tasksState = tasksState?.withCachedTaskReferences(taskReferenceCache)
            )

    fun DataResult<ThreadSnapshot>.rememberTaskReferences() {
        (this as? DataResult.Content)?.data?.let { snapshot ->
            rememberTaskReferences(
                    snapshot.tasks +
                            snapshot.messages.mapNotNull { it.task } +
                            listOfNotNull(snapshot.parentMessage?.task)
            )
        }
    }

    fun DataResult<TaskSnapshot>.rememberTaskReferences() {
        (this as? DataResult.Content)?.data?.tasks?.let(::rememberTaskReferences)
    }

    fun RealtimeEvent.rememberTaskReferences() {
        when (this) {
            is TaskCreated -> rememberTaskReferences(tasks)
            is TaskUpdated -> rememberTaskReference(task)
            is MessageNew -> message.task?.let(::rememberTaskReference)
            is MessageUpdated -> message.task?.let(::rememberTaskReference)
            is SyncResumeResponse ->
                    rememberTaskReferences(messages.mapNotNull { it.task })
            else -> Unit
        }
    }

    fun isCurrentMainThread(threadId: ThreadId): Boolean =
            homeRoute == HomeRoute.Thread && selectedThreadId == threadId.value

    fun nextThreadNavigationGeneration(): Int {
        threadNavigationGeneration += 1
        return threadNavigationGeneration
    }

    fun isCurrentThreadNavigationGeneration(generation: Int): Boolean =
            threadNavigationGeneration == generation

    class UiResultOwner(
            private val source: String,
            private val isCurrent: () -> Boolean,
            private val staleDetail: () -> String,
            private val onStale: () -> Unit = {}
    ) {
        fun isCurrentOrSkip(): Boolean {
            if (isCurrent()) return true
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockUiResultOwner",
                    "stale_skip source=$source ${staleDetail()}"
            )
            onStale()
            return false
        }
    }

    fun uiResultOwner(
            source: String,
            isCurrent: () -> Boolean,
            staleDetail: () -> String,
            onStale: () -> Unit = {}
    ): UiResultOwner =
            UiResultOwner(
                    source = source,
                    isCurrent = isCurrent,
                    staleDetail = staleDetail,
                    onStale = onStale
            )

    LaunchedEffect(taskReferenceCacheVersion) {
        if (taskReferenceCacheVersion == 0) return@LaunchedEffect
        selectedThreadState = selectedThreadState.withCachedTaskReferences()
        replyThreadBackStack =
                replyThreadBackStack.map { entry ->
                    entry.copy(
                            selectedThreadState =
                                    entry.selectedThreadState.withCachedTaskReferences()
                    )
                }
        tasksState = tasksState.withCachedTaskReferences(taskReferenceCache)
    }

    LaunchedEffect(selectedThreadId, homeRoute, threadParentJumpTarget, replyThreadBackStack.size) {
        val observedThreadId = selectedThreadId?.let(::ThreadId) ?: return@LaunchedEffect
        if (homeRoute != HomeRoute.Thread) return@LaunchedEffect
        val parentMessage =
                threadParentJumpTarget?.let { parentId ->
                    (selectedThreadState.loadState as? ScreenLoadState.Content)
                            ?.data
                            ?.let { content ->
                                content.parentMessage?.takeIf { it.id.value == parentId }
                                        ?: content.messages.firstOrNull { it.id.value == parentId }
                            }
                }
        val fallbackHeader =
                (selectedThreadState.loadState as? ScreenLoadState.Content)?.data?.header
        localWriteThrough.observeThreadTimeline(observedThreadId, selectedServerIdForUi()).collectLatest { timeline ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockLocalStore",
                    "observe_thread_emit threadId=${observedThreadId.value} timeline_null=${timeline == null} messages=${timeline?.messages?.size ?: -1} selectedThreadId=$selectedThreadId homeRoute=$homeRoute"
            )
            if (selectedThreadId != observedThreadId.value || homeRoute != HomeRoute.Thread) {
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockLocalStore",
                        "observe_thread_skip threadId=${observedThreadId.value} reason=condition_mismatch selectedThreadId=$selectedThreadId observedThreadId=${observedThreadId.value} homeRoute=$homeRoute"
                )
                return@collectLatest
            }
            if (timeline == null) {
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockLocalStore",
                        "observe_thread_skip threadId=${observedThreadId.value} reason=timeline_null"
                )
                return@collectLatest
            }
            selectedThreadState =
                    timeline
                            .toObservedThreadUiState(
                                    current = selectedThreadState,
                                    fallbackHeader = fallbackHeader,
                                    parentMessage = parentMessage
                            )
                            .withCachedTaskReferences()
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockLocalStore",
                    "observe_thread_update threadId=${observedThreadId.value} messages=${timeline.messages.size} tasks=${timeline.tasks.size}"
            )
        }
    }

    val selectedThreadContentForOrigin =
            (selectedThreadState.loadState as? ScreenLoadState.Content)?.data
    val selectedThreadHasOriginParent =
            threadParentJumpTarget?.let { parentIdValue ->
                val parentId = MessageId(parentIdValue)
                selectedThreadContentForOrigin?.let { content ->
                    content.parentMessage?.id == parentId ||
                            content.messages.any { message -> message.id == parentId }
                }
            } == true

    LaunchedEffect(
            homeRoute,
            selectedThreadId,
            threadParentJumpTarget,
            selectedThreadHasOriginParent
    ) {
        val parentIdValue = threadParentJumpTarget ?: return@LaunchedEffect
        if (homeRoute != HomeRoute.Thread) return@LaunchedEffect
        val routeThreadId = selectedThreadId ?: return@LaunchedEffect
        val parentId = MessageId(parentIdValue)
        if (selectedThreadHasOriginParent) {
            refreshingOriginParentIds = refreshingOriginParentIds - parentIdValue
            originParentRefreshErrors = originParentRefreshErrors - parentIdValue
            return@LaunchedEffect
        }
        if (refreshingOriginParentIds.contains(parentIdValue)) return@LaunchedEffect
        refreshingOriginParentIds = refreshingOriginParentIds + parentIdValue
        originParentRefreshErrors = originParentRefreshErrors - parentIdValue
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadOrigin",
                "refresh_missing_origin_start parentMessageId=$parentIdValue threadId=$routeThreadId"
        )
                try {
                        val result =
                                        withTimeoutOrNull(15_000L) {
                                                conversationRepository.getMessageContext(parentId)
                                        }
                        if (selectedThreadId != routeThreadId ||
                                                        homeRoute != HomeRoute.Thread ||
                                                        threadParentJumpTarget != parentIdValue
                        ) {
                                return@LaunchedEffect
                        }
                        if (result == null) {
                                originParentRefreshErrors = originParentRefreshErrors + (parentIdValue to "timeout")
                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                                "SlockThreadOrigin",
                                                "refresh_missing_origin_timeout parentMessageId=$parentIdValue threadId=$routeThreadId"
                                )
                                return@LaunchedEffect
                        }
                        localWriteThrough.writeThreadResult(
                                        result,
                                        source = "refresh_missing_origin_parent",
                                        fallbackThreadId = ThreadId(parentIdValue),
                                        serverId = selectedServerIdForUi()
                        )
                        result.rememberTaskReferences()
                        val refreshedParent = result.findParentMessage(parentId)
                        if (refreshedParent != null) {
                                selectedThreadState =
                                                selectedThreadState
                                                                .withParentMessage(refreshedParent)
                                                                .withCachedTaskReferences()
                                originParentRefreshErrors = originParentRefreshErrors - parentIdValue
                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                                "SlockThreadOrigin",
                                                "refresh_missing_origin_success parentMessageId=$parentIdValue"
                                )
                        } else {
                                val errorCode = result.readDiagnosticCode()
                                originParentRefreshErrors = originParentRefreshErrors + (parentIdValue to errorCode)
                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                                "SlockThreadOrigin",
                                                "refresh_missing_origin_empty parentMessageId=$parentIdValue result=${result.realtimeResultName()} code=$errorCode"
                                )
                        }
                } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (selectedThreadId == routeThreadId &&
                                                        homeRoute == HomeRoute.Thread &&
                                                        threadParentJumpTarget == parentIdValue
                        ) {
                                val errorCode = "exception:${error.javaClass.simpleName.ifBlank { "unknown" }}"
                                originParentRefreshErrors = originParentRefreshErrors + (parentIdValue to errorCode)
                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                                "SlockThreadOrigin",
                                                "refresh_missing_origin_exception parentMessageId=$parentIdValue threadId=$routeThreadId code=$errorCode message=${error.message.orEmpty()}"
                                )
                        }
                } finally {
                        refreshingOriginParentIds = refreshingOriginParentIds - parentIdValue
                }
    }

    fun openThread(conversation: ConversationSummary, fromParentPane: Boolean = false) {
        nextThreadNavigationGeneration()
        val streamThreadId = ThreadId(conversation.messageStreamChannelId().value)
        val threadServerId = selectedServerIdForUi()
        if (!fromParentPane) {
            replyThreadBackStack = emptyList()
        }
        threadParentJumpTarget = null
        if (!fromParentPane) {
            clearThreadReturnTarget()
        }
        selectedThreadRetryAction = { openThread(conversation, fromParentPane) }
        composerDrafts =
                composerDrafts.saveComposerDraft(
                        selectedThreadId,
                        composerValue,
                        composerAsTask,
                        pendingAttachmentFiles,
                        failedAttachmentIds
                )
        selectedThreadId = streamThreadId.value
        attachmentPreviewState = null
        selectedThreadState =
                (localThreadUiState(
                        streamThreadId,
                        fallbackHeader = conversation,
                        serverId = threadServerId
                )
                                ?: ThreadUiState(
                                        ScreenLoadState.Content(
                                                ThreadContent(
                                                        header = conversation,
                                                        messages = emptyList()
                                                )
                                        ),
                                        serverId = threadServerId,
                                        composerState = conversation.toComposerState()
                                ))
                        .withBackgroundThreadRefreshIndicator()
        localComposerMessages = emptyList()
        realtimeThreadMessages = emptyList()
        attachmentOpenStates = emptyMap()
        val draft = composerDrafts[streamThreadId.value]
        composerValue = draft?.value.orEmpty()
        composerAsTask = draft?.asTask ?: false
        pendingAttachmentFiles = draft?.attachments.orEmpty()
        failedAttachmentIds = draft?.failedAttachmentIds.orEmpty()
        coroutineScope.launch {
            val performanceTrace = SlockPerformanceDiagnostics.beginTrace("open_thread")
            try {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "thread:${streamThreadId.value}",
                            reason = "open_thread"
                    ) {
                        conversationRepository.getThread(streamThreadId)
                    }
            cacheOpenedThreadResult(
                    localWriteThrough = localWriteThrough,
                    result = result,
                    source = "open_thread",
                    fallbackThreadId = streamThreadId,
                    fallbackHeader = conversation,
                    serverId = threadServerId
            )
            result.rememberTaskReferences()
            if (!uiResultOwner(
                            source = "open_thread",
                            isCurrent = { isCurrentMainThread(streamThreadId) },
                            staleDetail = {
                                "threadId=${streamThreadId.value} selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute"
                            }
                    ).isCurrentOrSkip()
            ) return@launch
            selectedThreadState =
                    (localThreadUiState(
                            streamThreadId,
                            fallbackHeader = conversation,
                            serverId = threadServerId
                    )
                                    ?: result.toThreadUiState(
                                            fallbackHeader = conversation,
                                            serverId = threadServerId
                                    ))
                            .withCachedTaskReferences()
            if (threadTab == ThreadTab.Tasks) {
                val content =
                        (selectedThreadState.loadState as? ScreenLoadState.Content)?.data
                val channelId =
                        content?.header?.channelId
                                ?: content?.header?.threadId?.value?.let(::ChannelId)
                val selectedServerId =
                        selectedServerOverride
                                ?: (serverListState as? ScreenLoadState.Content)
                                        ?.data
                                        ?.selectedServer
                                        ?.id
                                ?: currentAuthSession?.currentServerId?.let(::ServerId)
                if (content != null && channelId != null && selectedServerId != null) {
                    val fallbackState =
                            content
                                    .tasks
                                    .withCachedTaskReferences()
                                    .toTasksUiStateForThreadTasks()
                    selectedThreadState =
                            selectedThreadState.copy(tasksState = fallbackState.withLoading())
                    coroutineScope.launch {
                        val tasksResult =
                                taskRepository.getChannelTasks(
                                        serverId = selectedServerId,
                                        channelId = channelId,
                                        filter = TaskFilter()
                                )
                        tasksResult.rememberTaskReferences()
                        val currentContent =
                                (selectedThreadState.loadState as? ScreenLoadState.Content)?.data
                        val currentChannelId =
                                currentContent?.header?.channelId
                                        ?: currentContent?.header?.threadId?.value?.let(::ChannelId)
                        if (currentChannelId == channelId) {
                            selectedThreadState =
                                    selectedThreadState.copy(
                                            tasksState =
                                                    selectedThreadState
                                                            .tasksState
                                                            .mergeThreadTasksRefreshResult(
                                                                    tasksResult
                                                            )
                                                            ?.withCachedTaskReferences(
                                                                    taskReferenceCache
                                            )
                            )
                        }
                    }
                }

            } else if (threadTab == ThreadTab.Files) {
                requestSelectedThreadFilesRefresh(reason = "open_thread_selected_tab")
            }
            (result as? DataResult.Content)?.data?.let { snapshot ->
                selectedThreadRetryAction = null
                markThreadReadIfLoaded(
                        ThreadContent(
                                header = snapshot.conversation.withTitleFallback(conversation),
                                messages = snapshot.messages
                        )
                )
            }
            } finally {
                performanceTrace.close()
            }
        }
    }

    val selectedThreadIsLoading = selectedThreadState.loadState is ScreenLoadState.Loading
    val homeThreadRestoreKey =
            (homeState.loadState as? ScreenLoadState.Content)
                    ?.data
                    ?.let { content ->
                        selectedThreadId?.let(content::findConversation)?.let { conversation ->
                            "${conversation.threadId.value}:${conversation.channelId?.value.orEmpty()}"
                        }
                    }
                    .orEmpty()
    LaunchedEffect(
            authState,
            homeRoute,
            homeThreadRestoreKey,
            selectedThreadId,
            selectedThreadIsLoading,
            replyThreadBackStack,
            threadParentJumpTarget
    ) {
        if (authState !is AuthUiState.Authenticated) return@LaunchedEffect
        val conversation =
                conversationToRestoreAfterThreadRecreate(
                        homeState = homeState,
                        selectedThreadId = selectedThreadId,
                        selectedThreadState = selectedThreadState,
                        isThreadRoute = homeRoute == HomeRoute.Thread,
                        hasReplyThreadContext =
                                replyThreadBackStack.isNotEmpty() || threadParentJumpTarget != null
                ) ?: return@LaunchedEffect
        openThread(conversation)
    }

    LaunchedEffect(
            authState,
            homeRoute,
            selectedThreadId,
            selectedThreadIsLoading,
            replyThreadBackStack,
            threadParentJumpTarget,
            conversationRepository
    ) {
        if (authState !is AuthUiState.Authenticated) return@LaunchedEffect
        val threadId =
                threadIdToRestoreDirectlyAfterRecreate(
                        selectedThreadId = selectedThreadId,
                        selectedThreadState = selectedThreadState,
                        isThreadRoute = homeRoute == HomeRoute.Thread,
                        hasReplyThreadContext =
                                replyThreadBackStack.isNotEmpty() || threadParentJumpTarget != null
        ) ?: return@LaunchedEffect
        val restoreThreadId = ThreadId(threadId)
        val restoreServerId = selectedServerIdForUi()
        val cachedState =
                localThreadUiState(restoreThreadId, serverId = restoreServerId)
                        ?.withCachedTaskReferences()
                        ?.withBackgroundThreadRefreshIndicator()
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadLifecycle",
                "restore_start threadId=$threadId localHit=${cachedState != null}"
        )
        if (cachedState != null &&
                        homeRoute == HomeRoute.Thread &&
                        selectedThreadId == threadId &&
                        selectedThreadState.loadState is ScreenLoadState.Loading
        ) {
            selectedThreadState = cachedState
        }
        val result =
                withSlockRequestPriority(
                        priority = SlockRequestPriority.Visible,
                        key = "thread:${restoreThreadId.value}",
                        reason = "restore_thread"
                ) {
                    conversationRepository.getThread(restoreThreadId)
                }
        localWriteThrough.writeThreadResult(
                result,
                source = "restore_thread",
                fallbackThreadId = restoreThreadId,
                serverId = restoreServerId
        )
        result.rememberTaskReferences()
        if (homeRoute == HomeRoute.Thread && selectedThreadId == threadId) {
            val restoredState =
                    when (result) {
                        is DataResult.Content ->
                                localThreadUiState(restoreThreadId, serverId = restoreServerId)
                                        ?: result.toThreadUiState(serverId = restoreServerId)
                        else ->
                                if (selectedThreadState.loadState is ScreenLoadState.Loading) {
                                    result.toThreadUiState(serverId = restoreServerId)
                                } else {
                                    selectedThreadState.withThreadRefreshResult(result)
                                }
                    }.withCachedTaskReferences()
            selectedThreadState = restoredState
            if (threadTab == ThreadTab.Files &&
                            restoredState.filesState?.loadState !is ScreenLoadState.Content
            ) {
                requestSelectedThreadFilesRefresh(reason = "restore_thread_selected_tab")
            }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadLifecycle",
                    "restore_result threadId=$threadId result=${result.realtimeResultName()} code=${result.readDiagnosticCode()} applied=true"
            )
        }
    }

    fun openAgentDirectMessage(member: MemberProfile) {
        if (member.kind != MemberKind.Agent) return
        val navigationGeneration = nextThreadNavigationGeneration()
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockDirectMessage",
                    "open_start source=member_detail agentId=${member.id.value}"
            )
            when (val result = channelRepository.openAgentDirectMessage(member.id)) {
                is DataResult.Content -> {
                    if (!uiResultOwner(
                                    source = "open_agent_dm",
                                    isCurrent = {
                                        isCurrentThreadNavigationGeneration(navigationGeneration)
                                    },
                                    staleDetail = {
                                        "generation=$navigationGeneration currentGeneration=$threadNavigationGeneration selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute destination=$destination"
                                    }
                            ).isCurrentOrSkip()
                    ) return@launch
                    val conversation =
                            result.data.conversation.withAgentDirectMessageIdentity(member)
                    val serverId =
                            selectedServerOverride
                                    ?: (serverListState as? ScreenLoadState.Content)
                                            ?.data
                                            ?.selectedServer
                                            ?.id
                                    ?: currentAuthSession?.currentServerId?.let(::ServerId)
                    if (serverId != null) {
                        localWriteThrough.writeHomeConversations(
                                serverId = serverId,
                                conversations = result.data.directMessages + conversation,
                                source = "open_agent_dm"
                        )
                        localWriteThrough.writeAgentDirectMessagePresence(
                                serverId = serverId,
                                members = listOf(member),
                                source = "open_agent_dm_presence"
                        )
                        publishHomeFromLocal(serverId)
                    } else {
                        homeState = homeState.withOpenDirectMessage(result.data, conversation)
                    }
                    conversation.channelId?.let(realtimeRepository::joinChannel)
                    destination = AppDestination.Home
                    previousHomeRoute = HomeRoute.Shell
                    homeRoute = HomeRoute.Thread
                    openThread(conversation)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockDirectMessage",
                            "open_success source=member_detail agentId=${member.id.value} threadId=${conversation.threadId.value} channelId=${conversation.channelId?.value.orEmpty()} dmCount=${result.data.directMessages.size}"
                    )
                }
                is DataResult.Empty -> {
                    membersState =
                            membersState.copy(
                                    selectedAgentDmsState = ScreenLoadState.Empty(result.reason)
                            )
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockDirectMessage",
                            "open_empty source=member_detail agentId=${member.id.value} reason=${result.reason.name}"
                    )
                }
                is DataResult.Error -> {
                    membersState =
                            membersState.copy(
                                    selectedAgentDmsState = ScreenLoadState.Error(result.error)
                            )
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockDirectMessage",
                            "open_error source=member_detail agentId=${member.id.value} code=${result.error.code}"
                    )
                }
                is DataResult.NoPermission -> {
                    membersState =
                            membersState.copy(
                                    selectedAgentDmsState =
                                            ScreenLoadState.NoPermission(result.blocker)
                            )
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockDirectMessage",
                            "open_no_permission source=member_detail agentId=${member.id.value} code=${result.blocker.code}"
                    )
                }
            }
        }
    }

    fun selectedThreadContent(): ThreadContent? =
            (selectedThreadState.loadState as? ScreenLoadState.Content)?.data

    fun selectedThreadVisibleTasksState(): TasksUiState? =
            selectedThreadState.tasksState
                    ?: selectedThreadContent()
                            ?.tasks
                            ?.withCachedTaskReferences()
                            ?.toTasksUiStateForThreadTasks()

    fun selectedThreadVisibleTasksChannelId(): ChannelId? =
            (selectedThreadVisibleTasksState()?.loadState as? ScreenLoadState.Content)
                    ?.data
                    ?.tasks
                    ?.mapNotNull { it.channelId }
                    ?.distinct()
                    ?.singleOrNull()

    fun selectedThreadChannelId(): ChannelId? =
            selectedThreadContent()?.header?.channelId
                    ?: selectedThreadVisibleTasksChannelId()
                    ?: selectedThreadContent()?.header?.threadId?.value?.let(::ChannelId)
                    ?: selectedThreadId?.let(::ChannelId)

    fun archiveSelectedThreadChannel(targetChannelId: ChannelId? = null) {
        val channelId = targetChannelId ?: selectedThreadChannelId()
        if (channelId == null || archiveChannelStatus == ThreadSettingsModalStatus.Saving) return
        archiveChannelStatus = ThreadSettingsModalStatus.Saving
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "channel:${channelId.value}",
                            reason = "archive_channel"
                    ) {
                        channelRepository.archiveChannel(channelId)
                    }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockChannelArchive",
                    "archive_result channelId=${channelId.value} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
            when (result) {
                is DataResult.Content -> {
                    if (!uiResultOwner(
                                    source = "archive",
                                    isCurrent = { selectedThreadChannelId() == channelId },
                                    staleDetail = {
                                        "channelId=${channelId.value} selectedChannelId=${selectedThreadChannelId()?.value.orEmpty()}"
                                    },
                                    onStale = {
                                        archiveChannelStatus = ThreadSettingsModalStatus.Default
                                    }
                            ).isCurrentOrSkip()
                    ) return@launch
                    homeState = homeState.withArchivedChannel(channelId)
                    selectedThreadId = null
                    selectedThreadState = ThreadUiState(ScreenLoadState.Loading)
                    threadMembersPanelState = ThreadMembersPanelState()
                    replyThreadBackStack = emptyList()
                    threadParentJumpTarget = null
                    localComposerMessages = emptyList()
                    realtimeThreadMessages = emptyList()
                    attachmentPreviewState = null
                    archiveChannelStatus = ThreadSettingsModalStatus.Default
                    homeRoute = HomeRoute.Shell
                    homeRefreshError = null
                    homeRefreshErrorLabel = null
                    val refreshServerId =
                            selectedServerOverride
                                    ?: (serverListState as? ScreenLoadState.Content)
                                            ?.data
                                            ?.selectedServer
                                            ?.id
                                    ?: currentAuthSession?.currentServerId?.let(::ServerId)
                    refreshServerId?.let { serverId ->
                        when (val refreshResult = channelRepository.getHome(serverId)) {
                            is DataResult.Content -> {
                                homeState =
                                        refreshResult.toHomeUiState(
                                                selectedServerId = serverId.value
                                        )
                            }
                            else -> Unit
                        }
                    }
                }
                is DataResult.NoPermission -> {
                    archiveChannelStatus = ThreadSettingsModalStatus.NoPermission
                }
                else -> {
                    archiveChannelStatus = ThreadSettingsModalStatus.Error
                }
            }
        }
    }

    fun deleteSelectedThreadChannel(targetChannelId: ChannelId? = null) {
        val channelId = targetChannelId ?: selectedThreadChannelId()
        if (channelId == null || archiveChannelStatus == ThreadSettingsModalStatus.Deleting) return
        archiveChannelStatus = ThreadSettingsModalStatus.Deleting
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "channel:${channelId.value}",
                            reason = "delete_channel"
                    ) {
                        channelRepository.deleteChannel(channelId)
                    }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockChannelDelete",
                    "delete_result channelId=${channelId.value} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
            when (result) {
                is DataResult.Content -> {
                    if (!uiResultOwner(
                                    source = "delete",
                                    isCurrent = { selectedThreadChannelId() == channelId },
                                    staleDetail = {
                                        "channelId=${channelId.value} selectedChannelId=${selectedThreadChannelId()?.value.orEmpty()}"
                                    },
                                    onStale = {
                                        archiveChannelStatus = ThreadSettingsModalStatus.Default
                                    }
                            ).isCurrentOrSkip()
                    ) return@launch
                    homeState = homeState.withArchivedChannel(channelId)
                    selectedThreadId = null
                    selectedThreadState = ThreadUiState(ScreenLoadState.Loading)
                    threadMembersPanelState = ThreadMembersPanelState()
                    replyThreadBackStack = emptyList()
                    threadParentJumpTarget = null
                    localComposerMessages = emptyList()
                    realtimeThreadMessages = emptyList()
                    attachmentPreviewState = null
                    archiveChannelStatus = ThreadSettingsModalStatus.Default
                    homeRoute = HomeRoute.Shell
                    homeRefreshError = null
                    homeRefreshErrorLabel = null
                    val refreshServerId =
                            selectedServerOverride
                                    ?: (serverListState as? ScreenLoadState.Content)
                                            ?.data
                                            ?.selectedServer
                                            ?.id
                                    ?: currentAuthSession?.currentServerId?.let(::ServerId)
                    refreshServerId?.let { serverId ->
                        when (val refreshResult = channelRepository.getHome(serverId)) {
                            is DataResult.Content -> {
                                homeState =
                                        refreshResult.toHomeUiState(
                                                selectedServerId = serverId.value
                                        )
                            }
                            else -> Unit
                        }
                    }
                }
                is DataResult.NoPermission -> {
                    archiveChannelStatus = ThreadSettingsModalStatus.NoPermission
                }
                else -> {
                    archiveChannelStatus = ThreadSettingsModalStatus.Error
                }
            }
        }
    }

    fun leaveSelectedThreadChannel(targetChannelId: ChannelId? = null) {
        val channelId = targetChannelId ?: selectedThreadChannelId()
        val currentMember = currentAuthSession?.user?.toMemberProfile()
                ?: (homeState.loadState as? ScreenLoadState.Content)?.data?.currentMember
        if (channelId == null ||
                        currentMember == null ||
                        archiveChannelStatus == ThreadSettingsModalStatus.Deleting
        ) {
            return
        }
        archiveChannelStatus = ThreadSettingsModalStatus.Deleting
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "channel:${channelId.value}:leave",
                            reason = "leave_channel"
                    ) {
                        memberRepository.removeChannelMember(
                                priority = SlockRequestPriority.Foreground,
                                RemoveChannelMemberRequest(
                                        channelId = channelId,
                                        memberId = currentMember.id,
                                        kind = currentMember.kind
                                )
                        )
                    }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockChannelLeave",
                    "leave_result channelId=${channelId.value} memberId=${currentMember.id.value} kind=${currentMember.kind} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
            when (result) {
                is DataResult.Content -> {
                    if (!uiResultOwner(
                                    source = "leave",
                                    isCurrent = { selectedThreadChannelId() == channelId },
                                    staleDetail = {
                                        "channelId=${channelId.value} selectedChannelId=${selectedThreadChannelId()?.value.orEmpty()}"
                                    },
                                    onStale = {
                                        archiveChannelStatus = ThreadSettingsModalStatus.Default
                                    }
                            ).isCurrentOrSkip()
                    ) return@launch
                    homeState = homeState.withArchivedChannel(channelId)
                    selectedThreadId = null
                    selectedThreadState = ThreadUiState(ScreenLoadState.Loading)
                    threadMembersPanelState = ThreadMembersPanelState()
                    replyThreadBackStack = emptyList()
                    threadParentJumpTarget = null
                    localComposerMessages = emptyList()
                    realtimeThreadMessages = emptyList()
                    attachmentPreviewState = null
                    archiveChannelStatus = ThreadSettingsModalStatus.Default
                    homeRoute = HomeRoute.Shell
                    homeRefreshError = null
                    homeRefreshErrorLabel = null
                    val refreshServerId =
                            selectedServerOverride
                                    ?: (serverListState as? ScreenLoadState.Content)
                                            ?.data
                                            ?.selectedServer
                                            ?.id
                                    ?: currentAuthSession?.currentServerId?.let(::ServerId)
                    refreshServerId?.let { serverId ->
                        when (val refreshResult = channelRepository.getHome(serverId)) {
                            is DataResult.Content -> {
                                homeState =
                                        refreshResult.toHomeUiState(
                                                selectedServerId = serverId.value
                                        )
                            }
                            else -> Unit
                        }
                    }
                }
                is DataResult.NoPermission -> {
                    archiveChannelStatus = ThreadSettingsModalStatus.NoPermission
                }
                else -> {
                    archiveChannelStatus = ThreadSettingsModalStatus.Error
                }
            }
        }
    }

    fun ThreadUiState.headerForTask(task: TaskSummary): ConversationSummary? {
        val content = (loadState as? ScreenLoadState.Content)?.data ?: return null
        val messages = listOfNotNull(content.parentMessage) + content.messages
        return content.header.takeIf { messages.any { message -> task.matchesMessageReference(message) } }
    }

    fun taskSourceHeader(task: TaskSummary): ConversationSummary? =
            selectedThreadState.headerForTask(task)
                    ?: replyThreadBackStack.asReversed().firstNotNullOfOrNull { entry ->
                        entry.selectedThreadState.headerForTask(task)
                    }

    fun updateSelectedThreadTasksState(transform: (TasksUiState) -> TasksUiState) {
        val current =
                selectedThreadState.tasksState
                        ?: selectedThreadContent()
                                ?.tasks
                                ?.withCachedTaskReferences()
                                ?.toTasksUiStateForThreadTasks()
                        ?: TasksUiState(
                                ScreenLoadState.Loading,
                                selectedFilter = null
                        )
        selectedThreadState = selectedThreadState.copy(tasksState = transform(current))
    }

    fun updateSelectedThreadFilesState(transform: (ThreadFilesUiState) -> ThreadFilesUiState) {
        val current =
                selectedThreadState.filesState
                        ?: ThreadFilesUiState(ScreenLoadState.Loading)
        selectedThreadState = selectedThreadState.copy(filesState = transform(current))
    }

    fun refreshSelectedThreadTasks(reason: String) {
        val channelId = selectedThreadChannelId() ?: return
        val selectedServerId =
                selectedServerOverride
                        ?: (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.id
                        ?: currentAuthSession?.currentServerId?.let(::ServerId)
                        ?: return
        updateSelectedThreadTasksState { state -> state.withLoading() }
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasks",
                    "channel_tasks_refresh_start reason=$reason channelId=${channelId.value}"
            )
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "thread:${channelId.value}:tasks",
                            reason = "thread_tasks_$reason"
                    ) {
                        taskRepository.getChannelTasks(
                                serverId = selectedServerId,
                                channelId = channelId,
                                filter = TaskFilter()
                        )
                    }
            result.rememberTaskReferences()
            if (selectedThreadChannelId() == channelId) {
                selectedThreadState =
                        selectedThreadState.copy(
                                tasksState =
                                        selectedThreadState.tasksState
                                                .mergeThreadTasksRefreshResult(result)
                                                ?.withCachedTaskReferences(taskReferenceCache)
                        )
            }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasks",
                    "channel_tasks_refresh_result reason=$reason channelId=${channelId.value} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
        }
    }

    fun refreshSelectedThreadFiles(reason: String, cursor: String? = null) {
        val channelId = selectedThreadChannelId() ?: return
        val currentFilesState = selectedThreadState.filesState
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadFiles",
                "files_state_before_refresh reason=$reason channelId=${channelId.value} cursor=${cursor.orEmpty()} current=${currentFilesState?.loadState?.threadFilesStatusForLog().orEmpty()} loadingMore=${currentFilesState?.loadingMore == true}"
        )
        selectedThreadState =
                selectedThreadState.copy(
                        filesState =
                                if (cursor != null && currentFilesState != null) {
                                    currentFilesState.copy(loadingMore = true)
                                } else if (currentFilesState?.loadState is ScreenLoadState.Content) {
                                    currentFilesState
                                } else {
                                    ThreadFilesUiState(ScreenLoadState.Loading)
                                }
                )
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadFiles",
                    "channel_files_refresh_start reason=$reason channelId=${channelId.value} cursor=${cursor.orEmpty()}"
            )
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "thread:${channelId.value}:files",
                            reason =
                                    if (cursor == null) {
                                        "thread_files_$reason"
                                    } else {
                                        "thread_files_load_more"
                                    }
                    ) {
                        conversationRepository.getChannelFiles(channelId, cursor = cursor)
                    }
            if (selectedThreadChannelId() == channelId) {
                selectedThreadState =
                        selectedThreadState.copy(
                                filesState =
                                        mergeThreadFilesState(
                                                current = selectedThreadState.filesState,
                                                next = result.toThreadFilesUiState(),
                                                append = cursor != null
                                        )
                        )
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockThreadFiles",
                        "files_state_after_refresh reason=$reason channelId=${channelId.value} cursor=${cursor.orEmpty()} result=${result.realtimeResultName()} next=${selectedThreadState.filesState?.loadState?.threadFilesStatusForLog().orEmpty()} loadingMore=${selectedThreadState.filesState?.loadingMore == true}"
                )
            }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadFiles",
                    "channel_files_refresh_result reason=$reason channelId=${channelId.value} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
        }
    }

    LaunchedEffect(selectedThreadFilesRefreshRequest) {
        val request = selectedThreadFilesRefreshRequest ?: return@LaunchedEffect
        selectedThreadFilesRefreshRequest = null
        refreshSelectedThreadFiles(reason = request.second)
    }

    val refreshSelectedThread: (String) -> Unit = { reason ->
        val currentContent = selectedThreadContent()
        if (currentContent != null && currentContent.refreshStatus != ThreadRefreshStatus.Loading) {
            val streamThreadId = ThreadId(currentContent.header.messageStreamChannelId().value)
            selectedThreadState =
                    selectedThreadState.withThreadRefreshStatus(ThreadRefreshStatus.Loading)
            coroutineScope.launch {
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockThreadLifecycle",
                        "refresh_start reason=$reason threadId=${streamThreadId.value} logicalThreadId=${currentContent.header.threadId.value}"
                )
                when (val result =
                        withSlockRequestPriority(
                                priority = SlockRequestPriority.Visible,
                                key = "thread:${streamThreadId.value}",
                                reason = "refresh_thread_$reason"
                        ) {
                            conversationRepository.getThread(streamThreadId)
                        }) {
                    is DataResult.Content -> {
                        localWriteThrough.writeThreadResult(
                                result,
                                source = "refresh_thread",
                                fallbackThreadId = streamThreadId,
                                serverId = selectedServerIdForUi()
                        )
                        result.rememberTaskReferences()
                        if (selectedThreadId == streamThreadId.value) {
                            selectedThreadState =
                                    (localThreadUiState(
                                                    streamThreadId,
                                                    fallbackHeader = currentContent.header,
                                                    parentMessage = currentContent.parentMessage
                                            )
                                                    ?: selectedThreadState
                                                            .mergeRefreshedThreadSnapshot(
                                                                    result.data
                                                            ))
                                            .withCachedTaskReferences()
                        }
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockThreadLifecycle",
                                "refresh_success reason=$reason threadId=${streamThreadId.value} messageCount=${result.data.messages.size} canLoadMoreBefore=${result.data.canLoadMoreBefore} canLoadMoreAfter=${result.data.canLoadMoreAfter}"
                        )
                    }
                    is DataResult.Empty -> {
                        if (selectedThreadId == streamThreadId.value) {
                            selectedThreadState =
                                    selectedThreadState.withThreadRefreshStatus(
                                            ThreadRefreshStatus.Error,
                                            result.reason.name
                                    )
                        }
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockThreadLifecycle",
                                "refresh_empty reason=$reason threadId=${streamThreadId.value} code=${result.reason.name}"
                        )
                    }
                    is DataResult.Error -> {
                        if (selectedThreadId == streamThreadId.value) {
                            selectedThreadState =
                                    selectedThreadState.withThreadRefreshStatus(
                                            ThreadRefreshStatus.Error,
                                            result.error.code
                                    )
                        }
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockThreadLifecycle",
                                "refresh_error reason=$reason threadId=${streamThreadId.value} code=${result.error.code}"
                        )
                    }
                    is DataResult.NoPermission -> {
                        if (selectedThreadId == streamThreadId.value) {
                            selectedThreadState =
                                    selectedThreadState.withThreadRefreshStatus(
                                            ThreadRefreshStatus.Error,
                                            result.blocker.code
                                    )
                        }
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockThreadLifecycle",
                                "refresh_no_permission reason=$reason threadId=${streamThreadId.value} code=${result.blocker.code}"
                        )
                    }
                }
            }
        }
    }

    fun openMemberProfile(member: MemberProfile) {
        if (destination != AppDestination.Members || !membersDetailVisible) {
            memberDetailReturnDestination = destination
            memberDetailReturnHomeRoute = homeRoute.takeIf { destination == AppDestination.Home }
        }
        val requestedMemberId = member.id
        membersDetailSelectedMemberId = member.id.value
        membersDetailVisible = true
        destination = AppDestination.Members
        membersState = membersState.withMemberProfile(member)
        coroutineScope.launch {
            if (member.kind == MemberKind.Human) {
                when (val profileResult =
                        withSlockRequestPriority(
                                priority = SlockRequestPriority.Foreground,
                                key = "member:${member.id.value}",
                                reason = "open_member_profile"
                        ) {
                            memberRepository.getMemberProfile(SlockRequestPriority.Foreground, member.id)
                        }) {
                    is DataResult.Content -> {
                        if (!uiResultOwner(
                                        source = "profile",
                                        isCurrent = {
                                            membersDetailSelectedMemberId == requestedMemberId.value
                                        },
                                        staleDetail = {
                                            "memberId=${requestedMemberId.value} selectedMemberId=${membersDetailSelectedMemberId.orEmpty()}"
                                        }
                                ).isCurrentOrSkip()
                        ) return@launch
                        membersState = membersState.withMemberProfile(profileResult.data)
                        membersDetailSelectedMemberId = profileResult.data.id.value
                    }
                    else -> Unit
                }
            } else if (member.kind == MemberKind.Agent) {
                membersState =
                        membersState.copy(
                                selectedAgentDmsState = ScreenLoadState.Loading,
                                selectedAgentRemindersState = ScreenLoadState.Loading,
                                selectedAgentActivityState = ScreenLoadState.Loading,
                                selectedAgentWorkspaceState = ScreenLoadState.Loading,
                                selectedAgentWorkspaceTreeState = SlockWorkspaceTreeState.Loading,
                                selectedAgentSkillsState = ScreenLoadState.Loading
                        )
                val (dms, reminders, activity, workspaces, workspaceTree, skills) =
                        coroutineScope {
                            val dmsD = async {
                                withSlockRequestPriority(
                                        priority = SlockRequestPriority.Foreground,
                                        key = "agent:${member.id.value}:dms",
                                        reason = "open_agent_profile"
                                ) {
                                    memberRepository.getAgentDirectMessages(SlockRequestPriority.Foreground, member.id)
                                }
                            }
                            val remindersD = async {
                                withSlockRequestPriority(
                                        priority = SlockRequestPriority.Foreground,
                                        key = "agent:${member.id.value}:reminders",
                                        reason = "open_agent_profile"
                                ) {
                                    memberRepository.getAgentReminders(SlockRequestPriority.Foreground, member.id)
                                }
                            }
                            val activityD = async {
                                withSlockRequestPriority(
                                        priority = SlockRequestPriority.Foreground,
                                        key = "agent:${member.id.value}:activity",
                                        reason = "open_agent_profile"
                                ) {
                                    memberRepository.getAgentActivity(SlockRequestPriority.Foreground, member.id)
                                }
                            }
                            val workspacesD = async {
                                withSlockRequestPriority(
                                        priority = SlockRequestPriority.Foreground,
                                        key = "agent:${member.id.value}:workspaces",
                                        reason = "open_agent_profile"
                                ) {
                                    memberRepository.getAgentWorkspaces(SlockRequestPriority.Foreground, member.id)
                                }
                            }
                            val workspaceTreeD = async {
                                withSlockRequestPriority(
                                        priority = SlockRequestPriority.Foreground,
                                        key = "agent:${member.id.value}:workspace-tree",
                                        reason = "open_agent_profile"
                                ) {
                                    memberRepository.getAgentWorkspaceTree(SlockRequestPriority.Foreground, member.id)
                                }
                            }
                            val skillsD = async {
                                withSlockRequestPriority(
                                        priority = SlockRequestPriority.Foreground,
                                        key = "agent:${member.id.value}:skills",
                                        reason = "open_agent_profile"
                                ) {
                                    memberRepository.getAgentSkills(SlockRequestPriority.Foreground, member.id)
                                }
                            }
                            AgentDetailParts(
                                    dms = dmsD.await(),
                                    reminders = remindersD.await(),
                                    activity = activityD.await(),
                                    workspaces = workspacesD.await(),
                                    workspaceTree = workspaceTreeD.await(),
                                    skills = skillsD.await()
                            )
                        }
                if (!uiResultOwner(
                                source = "agent_detail",
                                isCurrent = {
                                    membersDetailSelectedMemberId == requestedMemberId.value
                                },
                                staleDetail = {
                                    "memberId=${requestedMemberId.value} selectedMemberId=${membersDetailSelectedMemberId.orEmpty()}"
                                }
                        ).isCurrentOrSkip()
                ) return@launch
                val workspacesState = workspaces.toAgentWorkspacesLoadState()
                membersState =
                        membersState.copy(
                                selectedAgentDmsState = dms.toAgentDmsLoadState(),
                                selectedAgentRemindersState = reminders.toAgentRemindersLoadState(),
                                selectedAgentActivityState = activity.toAgentActivityLoadState(),
                                selectedAgentWorkspaceState = workspacesState,
                                selectedAgentWorkspaceTreeState =
                                        workspaceTree.toWorkspaceTreeState(workspacesState),
                                selectedAgentSkillsState = skills.toAgentSkillsLoadState()
                        )
            }
        }
    }

    fun refreshSelectedAgentWorkspaceTree() {
        val agentId = membersDetailSelectedMemberId?.let(::MemberId) ?: return
        membersState = membersState.copy(selectedAgentWorkspaceTreeState = SlockWorkspaceTreeState.Loading)
        coroutineScope.launch {
            val workspacesState = membersState.selectedAgentWorkspaceState
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "agent:${agentId.value}:workspace-tree",
                            reason = "refresh_agent_workspace_tree"
                    ) {
                        memberRepository.getAgentWorkspaceTree(SlockRequestPriority.Foreground, agentId)
                    }
            if (membersDetailSelectedMemberId == agentId.value) {
                membersState =
                        membersState.copy(
                                selectedAgentWorkspaceTreeState =
                                        result.toWorkspaceTreeState(workspacesState)
                        )
            }
        }
    }

    fun toggleAgentWorkspaceFolder(node: SlockWorkspaceNode) {
        val current = membersState.selectedAgentWorkspaceTreeState as? SlockWorkspaceTreeState.Content ?: return
        val agentId = membersDetailSelectedMemberId?.let(::MemberId) ?: return
        val expanded = current.expandedNodeIds.contains(node.id)
        if (expanded) {
            membersState =
                    membersState.copy(
                            selectedAgentWorkspaceTreeState =
                                    current.copy(expandedNodeIds = current.expandedNodeIds - node.id)
                    )
            return
        }
        membersState =
                membersState.copy(
                        selectedAgentWorkspaceTreeState =
                                current.copy(expandedNodeIds = current.expandedNodeIds + node.id)
                )
        if (node.children.isNotEmpty()) return
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "agent:${agentId.value}:workspace:${node.path}",
                            reason = "open_agent_workspace_folder"
                    ) {
                        memberRepository.getAgentWorkspaceChildren(SlockRequestPriority.Foreground, agentId, node.path)
                    }
            if (!uiResultOwner(
                            source = "workspace_tree",
                            isCurrent = { membersDetailSelectedMemberId == agentId.value },
                            staleDetail = {
                                "memberId=${agentId.value} selectedMemberId=${membersDetailSelectedMemberId.orEmpty()}"
                            }
                    ).isCurrentOrSkip()
            ) return@launch
            val latest =
                    membersState.selectedAgentWorkspaceTreeState as? SlockWorkspaceTreeState.Content
                            ?: return@launch
            if (result is DataResult.Content) {
                membersState =
                        membersState.copy(
                                selectedAgentWorkspaceTreeState =
                                        latest.withWorkspaceChildren(node.path, result.data.children)
                        )
            }
        }
    }

    fun selectAgentWorkspaceFile(node: SlockWorkspaceNode) {
        val current = membersState.selectedAgentWorkspaceTreeState as? SlockWorkspaceTreeState.Content ?: return
        val agentId = membersDetailSelectedMemberId?.let(::MemberId) ?: return
        membersState =
                membersState.copy(
                        selectedAgentWorkspaceTreeState =
                                current.withWorkspacePreview(
                                        path = node.path,
                                        preview = SlockWorkspaceFilePreview.Loading()
                                )
                )
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "agent:${agentId.value}:file:${node.path}",
                            reason = "open_agent_workspace_file"
                    ) {
                        memberRepository.readAgentWorkspaceFile(SlockRequestPriority.Foreground, agentId, node.path)
                    }
            if (!uiResultOwner(
                            source = "workspace_children",
                            isCurrent = { membersDetailSelectedMemberId == agentId.value },
                            staleDetail = {
                                "memberId=${agentId.value} selectedMemberId=${membersDetailSelectedMemberId.orEmpty()}"
                            }
                    ).isCurrentOrSkip()
            ) return@launch
            val latest =
                    membersState.selectedAgentWorkspaceTreeState as? SlockWorkspaceTreeState.Content
                            ?: return@launch
            membersState =
                    membersState.copy(
                            selectedAgentWorkspaceTreeState =
                                    latest.withWorkspacePreview(
                                            path = node.path,
                                            preview =
                                                    when (result) {
                                                        is DataResult.Content -> result.data.preview
                                                        is DataResult.Empty ->
                                                                SlockWorkspaceFilePreview.Unsupported(
                                                                        path = node.path,
                                                                        reason = result.reason.name
                                                                )
                                                        is DataResult.Error ->
                                                                SlockWorkspaceFilePreview.Error(
                                                                        reason = result.error.code
                                                                )
                                                        is DataResult.NoPermission ->
                                                                SlockWorkspaceFilePreview.Error(
                                                                        reason = result.blocker.code
                                                                )
                                                    }
                                    )
                    )
        }
    }

    fun backToAgentWorkspaceTree() {
        val current = membersState.selectedAgentWorkspaceTreeState as? SlockWorkspaceTreeState.Content ?: return
        membersState =
                membersState.copy(
                        selectedAgentWorkspaceTreeState = current.copy(showPreview = false)
                )
    }

    fun copyAgentWorkspacePath() {
        val content = membersState.selectedAgentWorkspaceTreeState as? SlockWorkspaceTreeState.Content ?: return
        val selectedPath =
                content.selectedFileId
                        ?.let(content.root::findWorkspaceNodeById)
                        ?.path
                        ?: content.rootPath
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Slock workspace path", selectedPath))
        Toast.makeText(context, "Copied workspace path", Toast.LENGTH_SHORT).show()
    }

    fun returnFromMemberDetail() {
        val returnDestination = memberDetailReturnDestination
        val returnHomeRoute = memberDetailReturnHomeRoute
        memberDetailReturnDestination = null
        memberDetailReturnHomeRoute = null
        when (returnDestination) {
            AppDestination.Home -> {
                destination = AppDestination.Home
                homeRoute = returnHomeRoute ?: HomeRoute.Shell
            }
            AppDestination.Tasks, AppDestination.Settings -> {
                destination = returnDestination
            }
            AppDestination.Members, null -> {
                destination = AppDestination.Members
            }
        }
    }

    fun saveAgentProfile(member: MemberProfile, displayName: String, description: String) {
        membersState = membersState.copy(agentProfileSaveState = AgentProfileSaveState.Saving)
        coroutineScope.launch {
            when (
                    val result =
                            memberRepository.updateAgentProfile(
                                    priority = SlockRequestPriority.Foreground,
                                    AgentProfileUpdateRequest(
                                            agentId = member.id,
                                            displayName = displayName,
                                            description = description
                                    )
                            )
            ) {
                is DataResult.Content -> {
                    val updated =
                            member.copy(
                                    displayName = result.data.displayName,
                                    handle = result.data.handle,
                                    description = result.data.description,
                                    roleDescription = result.data.roleDescription,
                                    avatarUrl = result.data.avatarUrl,
                                    gravatarHash = result.data.gravatarHash,
                                    avatar = result.data.avatar,
                                    avatarLabel = result.data.avatarLabel,
                                    presence = result.data.presence,
                                    agentStatus = result.data.agentStatus
                            )
                    membersState =
                            membersState
                                    .withMemberProfile(updated)
                                    .copy(agentProfileSaveState = AgentProfileSaveState.Saved)
                }
                is DataResult.Error ->
                        membersState =
                                membersState.copy(
                                        agentProfileSaveState =
                                                AgentProfileSaveState.Error(result.error)
                                )
                is DataResult.Empty ->
                        membersState =
                                membersState.copy(agentProfileSaveState = AgentProfileSaveState.Idle)
                is DataResult.NoPermission ->
                        membersState =
                                membersState.copy(
                                        agentProfileSaveState =
                                                AgentProfileSaveState.Error(
                                                        ScreenError(
                                                                code = result.blocker.code,
                                                                retryable = false
                                                        )
                                                )
                                )
            }
        }
    }

    fun openThreadItem(item: InboxItem) {
        nextThreadNavigationGeneration()
        replyThreadBackStack = emptyList()
        threadParentJumpTarget = item.parentMessageId?.value
        threadTab = ThreadTab.Chat
        clearThreadReturnTarget()
        selectedThreadRetryAction = { openThreadItem(item) }
        composerDrafts =
                composerDrafts.saveComposerDraft(
                        selectedThreadId,
                        composerValue,
                        composerAsTask,
                        pendingAttachmentFiles,
                        failedAttachmentIds
                )
        val threadChannelId = item.threadChannelId ?: item.channelId
        val fallbackHeader = item.toConversationSummary()
        val contextMessageId =
                item.latestActivityMessageId
                        ?: item.firstUnreadMessageId ?: item.lastMessageId ?: item.parentMessageId
        selectedThreadId = threadChannelId.value
        attachmentPreviewState = null
        selectedThreadState =
                (localThreadUiState(
                                ThreadId(threadChannelId.value),
                                fallbackHeader = fallbackHeader
                        )
                                ?: ThreadUiState(
                                        ScreenLoadState.Content(
                                                ThreadContent(
                                                        header = fallbackHeader,
                                                        messages = emptyList()
                                                )
                                        ),
                                        composerState = fallbackHeader.toComposerState()
                                ))
                        .withBackgroundThreadRefreshIndicator()
        localComposerMessages = emptyList()
        realtimeThreadMessages = emptyList()
        attachmentOpenStates = emptyMap()
        val draft = composerDrafts[threadChannelId.value]
        composerValue = draft?.value.orEmpty()
        composerAsTask = draft?.asTask ?: false
        pendingAttachmentFiles = draft?.attachments.orEmpty()
        failedAttachmentIds = draft?.failedAttachmentIds.orEmpty()
        coroutineScope.launch {
            val parentMessageId = item.parentMessageId
            val parentContextResult =
                    if (item.kind == InboxItemKind.Thread && parentMessageId != null) {
                        async { conversationRepository.getMessageContext(parentMessageId) }
                    } else {
                        null
                    }
            val result =
                    if (contextMessageId != null) {
                        if (item.kind == InboxItemKind.Thread) {
                            conversationRepository.getThread(ThreadId(threadChannelId.value))
                        } else {
                            conversationRepository.getMessageContext(contextMessageId)
                        }
                    } else {
                        conversationRepository.getThread(ThreadId(threadChannelId.value))
                    }
            val awaitedParentContextResult = parentContextResult?.await()
            awaitedParentContextResult?.let {
                cacheOpenedThreadResult(
                        localWriteThrough = localWriteThrough,
                        result = it,
                        source = "open_inbox_parent_context",
                        fallbackThreadId = item.parentMessageId?.value?.let(::ThreadId),
                        serverId = selectedServerIdForUi()
                )
                it.rememberTaskReferences()
            }
            val parentMessage =
                    awaitedParentContextResult
                            ?.findParentMessage(item.parentMessageId)
            cacheOpenedThreadResult(
                    localWriteThrough = localWriteThrough,
                    result = result,
                    source = "open_inbox_thread",
                    fallbackThreadId = ThreadId(threadChannelId.value),
                    fallbackHeader = fallbackHeader,
                    parentMessage = parentMessage,
                    serverId = selectedServerIdForUi()
            )
            result.rememberTaskReferences()
            if (!uiResultOwner(
                            source = "open_inbox_thread",
                            isCurrent = { isCurrentMainThread(ThreadId(threadChannelId.value)) },
                            staleDetail = {
                                "threadId=${threadChannelId.value} selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute"
                            }
                    ).isCurrentOrSkip()
            ) return@launch
            selectedThreadState =
                    (localThreadUiState(
                                    ThreadId(threadChannelId.value),
                                    fallbackHeader = fallbackHeader,
                                    parentMessage = parentMessage,
                                    serverId = selectedServerIdForUi()
                            )
                                    ?: result.toThreadUiState(
                                            fallbackHeader = fallbackHeader,
                                            parentMessage = parentMessage,
                                            serverId = selectedServerIdForUi()
                                    ))
            (result as? DataResult.Content)?.data?.let { snapshot ->
                selectedThreadRetryAction = null
                markThreadReadIfLoaded(
                        ThreadContent(
                                header = snapshot.conversation.withTitleFallback(fallbackHeader),
                                messages = snapshot.messages
                        )
                )
            }
        }
    }

    fun currentReplyThreadBackStackEntry(parentMessageId: MessageId): ReplyThreadBackStackEntry =
            ReplyThreadBackStackEntry(
                    selectedThreadId = selectedThreadId,
                    selectedThreadState = selectedThreadState,
                    selectedTab = threadTab,
                    previousHomeRoute = previousHomeRoute,
                    parentMessageId = parentMessageId,
                    localComposerMessages = localComposerMessages,
                    realtimeThreadMessages = realtimeThreadMessages,
                    attachmentOpenStates = attachmentOpenStates,
                    composerValue = composerValue,
                    composerAsTask = composerAsTask,
                     pendingAttachmentFiles = pendingAttachmentFiles,
                     failedAttachmentIds = failedAttachmentIds,
                     parentJumpTarget = threadParentJumpTarget
             )

    fun updateReplyThreadBackStackEntry(
            transform: (ReplyThreadBackStackEntry) -> ReplyThreadBackStackEntry
    ): ReplyThreadBackStackEntry? {
        val index = replyThreadBackStack.lastIndex
        if (index < 0) return null
        val updated = transform(replyThreadBackStack[index])
        replyThreadBackStack =
                replyThreadBackStack.toMutableList().also { entries ->
                    entries[index] = updated
                }
        return updated
    }

    fun updateParentPaneThreadState(
            transform: (ThreadUiState) -> ThreadUiState
    ): ReplyThreadBackStackEntry? =
            updateReplyThreadBackStackEntry { entry ->
                entry.copy(selectedThreadState = transform(entry.selectedThreadState))
            }

    fun selectedServerIdForThreadTasks(): ServerId? =
            selectedServerOverride
                    ?: (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.id
                    ?: currentAuthSession?.currentServerId?.let(::ServerId)

    fun refreshParentPaneTasks(reason: String) {
        val entry = replyThreadBackStack.lastOrNull() ?: return
        val channelId = entry.selectedThreadState.threadChannelId() ?: return
        val serverId = selectedServerIdForThreadTasks() ?: return
        val currentTasksState =
                entry.selectedThreadState.tasksState
                        ?: entry.selectedThreadState
                                .selectedThreadContent()
                                ?.tasks
                                ?.withCachedTaskReferences()
                                ?.toTasksUiStateForThreadTasks()
                        ?: TasksUiState(
                                loadState = ScreenLoadState.Loading,
                                selectedFilter = null
                        )
        updateParentPaneThreadState { state ->
            state.copy(tasksState = currentTasksState.withLoading())
        }
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasks",
                    "channel_tasks_refresh_start reason=$reason channelId=${channelId.value} source=parent_pane"
            )
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "thread:${channelId.value}:tasks",
                            reason = "parent_pane_tasks_$reason"
                    ) {
                        taskRepository.getChannelTasks(
                                serverId = serverId,
                                channelId = channelId,
                                filter = TaskFilter()
                        )
                    }
            result.rememberTaskReferences()
            updateReplyThreadBackStackEntry { currentEntry ->
                if (currentEntry.selectedThreadState.threadChannelId() != channelId) {
                    currentEntry
                } else {
                    currentEntry.copy(
                            selectedThreadState =
                                    currentEntry.selectedThreadState.copy(
                                            tasksState =
                                                    currentEntry.selectedThreadState.tasksState
                                                            .mergeThreadTasksRefreshResult(result)
                                                            ?.withCachedTaskReferences(
                                                                    taskReferenceCache
                                                            )
                                    )
                    )
                }
            }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasks",
                    "channel_tasks_refresh_result reason=$reason channelId=${channelId.value} source=parent_pane result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
        }
    }

    fun refreshParentPaneFiles(reason: String, cursor: String? = null) {
        val entry = replyThreadBackStack.lastOrNull() ?: return
        val channelId = entry.selectedThreadState.threadChannelId() ?: return
        val currentFilesState = entry.selectedThreadState.filesState
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadFiles",
                "parent_files_state_before_refresh reason=$reason channelId=${channelId.value} cursor=${cursor.orEmpty()} current=${currentFilesState?.loadState?.threadFilesStatusForLog().orEmpty()} loadingMore=${currentFilesState?.loadingMore == true}"
        )
        updateParentPaneThreadState { state ->
            state.copy(
                    filesState =
                            if (cursor != null && currentFilesState != null) {
                                currentFilesState.copy(loadingMore = true)
                            } else if (currentFilesState?.loadState is ScreenLoadState.Content) {
                                currentFilesState
                            } else {
                                ThreadFilesUiState(ScreenLoadState.Loading)
                            }
            )
        }
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadFiles",
                    "channel_files_refresh_start reason=$reason channelId=${channelId.value} source=parent_pane cursor=${cursor.orEmpty()}"
            )
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "thread:${channelId.value}:files",
                            reason =
                                    if (cursor == null) {
                                        "parent_pane_files_$reason"
                                    } else {
                                        "parent_pane_files_load_more"
                                    }
                    ) {
                        conversationRepository.getChannelFiles(channelId, cursor = cursor)
                    }
            updateReplyThreadBackStackEntry { currentEntry ->
                if (currentEntry.selectedThreadState.threadChannelId() != channelId) return@updateReplyThreadBackStackEntry currentEntry
                currentEntry.copy(
                        selectedThreadState =
                                currentEntry.selectedThreadState.copy(
                                        filesState =
                                                mergeThreadFilesState(
                                                        current = currentEntry.selectedThreadState.filesState,
                                                        next = result.toThreadFilesUiState(),
                                                        append = cursor != null
                                                )
                                )
                )
            }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadFiles",
                    "channel_files_refresh_result reason=$reason channelId=${channelId.value} source=parent_pane result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
        }
    }

    fun refreshParentPaneThread(reason: String) {
        val entry = replyThreadBackStack.lastOrNull() ?: return
        val threadId = entry.selectedThreadId?.let(::ThreadId) ?: return
        val currentContent = entry.selectedThreadState.selectedThreadContent()
        if (currentContent != null) {
            refreshSelectedThread(
                    reason = "${reason}_parent_pane",
                    selectedThreadStateProvider = {
                        replyThreadBackStack.lastOrNull()?.selectedThreadState
                                ?: ThreadUiState(ScreenLoadState.Loading)
                    },
                    selectedThreadIdProvider = { replyThreadBackStack.lastOrNull()?.selectedThreadId },
                    setSelectedThreadState = { state ->
                        updateReplyThreadBackStackEntry { currentEntry ->
                            currentEntry.copy(selectedThreadState = state)
                        }
                    },
                    onThreadSnapshot = { result, refreshedThreadId ->
                        localWriteThrough.writeThreadResult(
                                result,
                                source = "refresh_parent_pane_thread",
                                fallbackThreadId = refreshedThreadId,
                                serverId = selectedServerIdForUi()
                        )
                        result.rememberTaskReferences()
                    },
                    localThreadUiStateProvider = { localThreadId, fallbackHeader, parentMessage ->
                        localThreadUiState(
                                localThreadId,
                                fallbackHeader = fallbackHeader,
                                parentMessage = parentMessage
                        )
                    },
                    transformThreadState = { state -> state.withCachedTaskReferences() },
                    conversationRepository = conversationRepository,
                    coroutineScope = coroutineScope
            )
            return
        }
        updateParentPaneThreadState { it.withBackgroundThreadRefreshIndicator() }
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "thread:${threadId.value}",
                            reason = "retry_parent_pane_thread"
                    ) {
                        conversationRepository.getThread(threadId)
                    }
            localWriteThrough.writeThreadResult(
                    result,
                    source = "retry_parent_pane_thread",
                    fallbackThreadId = threadId,
                    serverId = selectedServerIdForUi()
            )
            result.rememberTaskReferences()
            updateReplyThreadBackStackEntry { currentEntry ->
                if (currentEntry.selectedThreadId != threadId.value) {
                    currentEntry
                } else {
                    currentEntry.copy(
                            selectedThreadState =
                                    (
                                            localThreadUiState(threadId, serverId = selectedServerIdForUi())
                                                    ?: result.toThreadUiState(serverId = selectedServerIdForUi())
                                            ).withCachedTaskReferences()
                    )
                }
            }
        }
    }

    fun loadOlderParentPaneThreadMessages(
            anchorMessageId: MessageId? = null,
            anchorOffset: Int = 0
    ) {
        val entry = replyThreadBackStack.lastOrNull() ?: return
        val threadId = entry.selectedThreadId?.let(::ThreadId) ?: return
        val content = entry.selectedThreadState.selectedThreadContent() ?: return
        val beforeSeq = content.messages.mapNotNull { it.seq }.minOrNull()
        if (beforeSeq == null) {
            updateParentPaneThreadState {
                it.withOlderPageStatus(
                        status = MessagePageStatus.Error,
                        errorCode = "missing_before_seq"
                )
            }
            return
        }
        if (entry.selectedThreadState.isOlderPageLoading()) return
        updateParentPaneThreadState {
            it.withOlderPageStatus(
                    status = MessagePageStatus.Loading,
                    anchorMessageId = anchorMessageId ?: content.messages.firstOrNull()?.id,
                    anchorOffset = anchorOffset
            )
        }
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "thread:${threadId.value}",
                            reason = "page_older_parent_pane"
                    ) {
                        conversationRepository.loadOlderMessages(threadId, beforeSeq)
                    }
            localWriteThrough.writeThreadResult(
                    result,
                    source = "page_older_parent_pane",
                    fallbackThreadId = threadId,
                    serverId = selectedServerIdForUi()
            )
            result.rememberTaskReferences()
            updateReplyThreadBackStackEntry { currentEntry ->
                if (currentEntry.selectedThreadId != threadId.value) {
                    currentEntry
                } else {
                    currentEntry.copy(
                            selectedThreadState =
                                    mergeOlderThreadPage(
                                            current = currentEntry.selectedThreadState,
                                            next = result
                                    ).withCachedTaskReferences()
                    )
                }
            }
        }
    }

    fun loadNewerParentPaneThreadMessages() {
        val entry = replyThreadBackStack.lastOrNull() ?: return
        val threadId = entry.selectedThreadId?.let(::ThreadId) ?: return
        val afterSeq =
                entry.selectedThreadState
                        .selectedThreadContent()
                        ?.messages
                        ?.mapNotNull { it.seq }
                        ?.maxOrNull()
                        ?: return
        if (entry.selectedThreadState.isNewerPageLoading()) return
        updateParentPaneThreadState { it.withNewerPageStatus(MessagePageStatus.Loading) }
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "thread:${threadId.value}",
                            reason = "page_newer_parent_pane"
                    ) {
                        conversationRepository.loadNewerMessages(threadId, afterSeq)
                    }
            localWriteThrough.writeThreadResult(
                    result,
                    source = "page_newer_parent_pane",
                    fallbackThreadId = threadId,
                    serverId = selectedServerIdForUi()
            )
            result.rememberTaskReferences()
            updateReplyThreadBackStackEntry { currentEntry ->
                if (currentEntry.selectedThreadId != threadId.value) {
                    currentEntry
                } else {
                    currentEntry.copy(
                            selectedThreadState =
                                    mergeNewerThreadPage(
                                            current = currentEntry.selectedThreadState,
                                            next = result
                                    ).withCachedTaskReferences()
                    )
                }
            }
        }
    }

    fun updateParentPaneAttachmentDraft(
            transform: (ReplyThreadBackStackEntry) -> ReplyThreadBackStackEntry
    ) {
        updateReplyThreadBackStackEntry(transform)
    }

    fun sendParentPaneMessage() {
        val entry = replyThreadBackStack.lastOrNull() ?: return
        val parentContent = entry.selectedThreadState.selectedThreadContent()
        val selectedHeader = parentContent?.header
        val messageStreamChannelId =
                composerSendChannelId(parentContent, entry.selectedThreadId)
        val sendDraft =
                ComposerSubmissionDraft(
                        value = entry.composerValue,
                        asTask = entry.composerAsTask,
                        attachments = entry.pendingAttachmentFiles,
                        failedAttachmentIds = emptySet()
                )
        if (selectedHeader != null && (!selectedHeader.joined || !selectedHeader.permissions.canSend)) {
            updateParentPaneThreadState { state ->
                state.withComposerStatus(
                        status = ComposerStatus.NoPermission,
                        noPermissionCode =
                                if (!selectedHeader.joined) "join_required" else "read_only"
                )
            }
            return
        }
        if (messageStreamChannelId == null || !sendDraft.hasContent) return
        updateReplyThreadBackStackEntry { currentEntry ->
            currentEntry.copy(
                    selectedThreadState =
                            currentEntry.selectedThreadState.withComposerStatus(
                                    if (sendDraft.attachments.isNotEmpty()) {
                                        ComposerStatus.Uploading
                                    } else {
                                        ComposerStatus.Sending
                                    }
                            ),
                    composerValue = "",
                    composerAsTask = false,
                    pendingAttachmentFiles = emptyList(),
                    failedAttachmentIds = emptySet()
            )
        }
        coroutineScope.launch {
            val attachmentIds =
                    if (sendDraft.attachments.isNotEmpty()) {
                        when (
                                val uploadResult =
                                        withSlockRequestPriority(
                                                priority = SlockRequestPriority.Foreground,
                                                key = "attachments:${messageStreamChannelId.value}",
                                                reason = "send_parent_upload"
                                        ) {
                                            conversationRepository.uploadAttachments(
                                                    AttachmentUploadRequest(
                                                            channelId = messageStreamChannelId,
                                                            files = sendDraft.attachments
                                                    )
                                            )
                                        }
                        ) {
                            is DataResult.Content -> uploadResult.data.attachmentIds
                            is DataResult.Empty -> emptyList()
                            is DataResult.Error -> {
                                val failedDraft = sendDraft.withAllAttachmentsFailed()
                                updateReplyThreadBackStackEntry { currentEntry ->
                                    currentEntry.copy(
                                            selectedThreadState =
                                                    currentEntry.selectedThreadState
                                                            .withComposerStatus(
                                                                    status = ComposerStatus.Failed,
                                                                    errorCode =
                                                                            uploadResult.error.code
                                                            ),
                                            composerValue = failedDraft.value,
                                            composerAsTask = failedDraft.asTask,
                                            pendingAttachmentFiles = failedDraft.attachments,
                                            failedAttachmentIds =
                                                    failedDraft.failedAttachmentIds
                                    )
                                }
                                return@launch
                            }
                            is DataResult.NoPermission -> {
                                val failedDraft = sendDraft.withAllAttachmentsFailed()
                                updateReplyThreadBackStackEntry { currentEntry ->
                                    currentEntry.copy(
                                            selectedThreadState =
                                                    currentEntry.selectedThreadState
                                                            .withComposerStatus(
                                                                    status =
                                                                            ComposerStatus
                                                                                    .NoPermission,
                                                                    noPermissionCode =
                                                                            uploadResult.blocker
                                                                                    .code
                                                            ),
                                            composerValue = failedDraft.value,
                                            composerAsTask = failedDraft.asTask,
                                            pendingAttachmentFiles = failedDraft.attachments,
                                            failedAttachmentIds =
                                                    failedDraft.failedAttachmentIds
                                    )
                                }
                                return@launch
                            }
                        }
                    } else {
                        emptyList()
                    }
            updateParentPaneThreadState { it.withComposerStatus(ComposerStatus.Sending) }
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "send:${messageStreamChannelId.value}",
                            reason = "send_parent_message"
                    ) {
                        conversationRepository.sendMessage(
                                channelId = messageStreamChannelId,
                                body = sendDraft.body,
                                attachmentIds = attachmentIds,
                                asTask = sendDraft.asTask
                        )
                    }
            when (result) {
                is DataResult.Content -> {
                    localWriteThrough.writeSendResult(
                            result,
                            source = "send_parent_reply_split",
                            serverId = selectedServerIdForUi()
                    )
                    updateReplyThreadBackStackEntry { currentEntry ->
                        currentEntry.copy(
                                selectedThreadState =
                                        currentEntry.selectedThreadState
                                                .appendSentMessage(result.data)
                                                .withCachedTaskReferences(),
                                composerValue = "",
                                composerAsTask = false,
                                pendingAttachmentFiles = emptyList(),
                                failedAttachmentIds = emptySet()
                        )
                    }
                }
                is DataResult.Empty -> {
                    updateReplyThreadBackStackEntry { currentEntry ->
                        currentEntry.copy(
                                selectedThreadState =
                                        currentEntry.selectedThreadState
                                                .withComposerStatus(
                                                        status = ComposerStatus.Failed,
                                                        errorCode = result.reason.name
                                                ),
                                composerValue = sendDraft.value,
                                composerAsTask = sendDraft.asTask,
                                pendingAttachmentFiles = sendDraft.attachments,
                                failedAttachmentIds = sendDraft.failedAttachmentIds
                        )
                    }
                }
                is DataResult.Error -> {
                    updateReplyThreadBackStackEntry { currentEntry ->
                        currentEntry.copy(
                                selectedThreadState =
                                        currentEntry.selectedThreadState
                                                .withComposerStatus(
                                                        status = ComposerStatus.Failed,
                                                        errorCode = result.error.code
                                                ),
                                composerValue = sendDraft.value,
                                composerAsTask = sendDraft.asTask,
                                pendingAttachmentFiles = sendDraft.attachments,
                                failedAttachmentIds = sendDraft.failedAttachmentIds
                        )
                    }
                }
                is DataResult.NoPermission -> {
                    updateReplyThreadBackStackEntry { currentEntry ->
                        currentEntry.copy(
                                selectedThreadState =
                                        currentEntry.selectedThreadState
                                                .withComposerStatus(
                                                        status = ComposerStatus.NoPermission,
                                                        noPermissionCode = result.blocker.code
                                                ),
                                composerValue = sendDraft.value,
                                composerAsTask = sendDraft.asTask,
                                pendingAttachmentFiles = sendDraft.attachments,
                                failedAttachmentIds = sendDraft.failedAttachmentIds
                        )
                    }
                }
            }
        }
    }

    fun restoreReplyThreadParent(): Boolean {
        val entry = replyThreadBackStack.lastOrNull() ?: return false
        composerDrafts =
                composerDrafts.saveComposerDraft(
                        selectedThreadId,
                        composerValue,
                        composerAsTask,
                        pendingAttachmentFiles,
                        failedAttachmentIds
                )
        replyThreadBackStack = replyThreadBackStack.dropLast(1)
        threadParentJumpTarget = entry.parentJumpTarget
        selectedThreadId = entry.selectedThreadId
        attachmentPreviewState = null
        selectedThreadState =
                entry.selectedThreadState.withHighlightedMessage(entry.parentMessageId)
        previousHomeRoute = entry.previousHomeRoute
        homeRoute = HomeRoute.Thread
        threadTab = entry.selectedTab
        localComposerMessages = entry.localComposerMessages
        realtimeThreadMessages = entry.realtimeThreadMessages
        attachmentOpenStates = entry.attachmentOpenStates
        composerValue = entry.composerValue
        composerAsTask = entry.composerAsTask
        pendingAttachmentFiles = entry.pendingAttachmentFiles
        failedAttachmentIds = entry.failedAttachmentIds
        selectedThreadNearBottom = false
        return true
    }

    fun returnFromThread(): Boolean {
        val returnDestination = threadReturnDestination ?: return false
        val returnHomeRoute = threadReturnHomeRoute
        val returnPreviousHomeRoute = threadReturnPreviousHomeRoute
        val returnTab = threadReturnTab
        val returnSelectedThreadId = threadReturnSelectedThreadId
        val returnSelectedThreadState = threadReturnSelectedThreadState
        val returnReplyThreadBackStack = threadReturnReplyThreadBackStack
        val returnThreadParentJumpTarget = threadReturnThreadParentJumpTarget
        val returnLocalComposerMessages = threadReturnLocalComposerMessages
        val returnRealtimeThreadMessages = threadReturnRealtimeThreadMessages
        val returnAttachmentOpenStates = threadReturnAttachmentOpenStates
        val returnComposerValue = threadReturnComposerValue
        val returnComposerAsTask = threadReturnComposerAsTask
        val returnPendingAttachmentFiles = threadReturnPendingAttachmentFiles
        val returnFailedAttachmentIds = threadReturnFailedAttachmentIds
        val returnSelectedThreadNearBottom = threadReturnSelectedThreadNearBottom
        clearThreadReturnTarget()
        if (returnDestination == AppDestination.Tasks &&
                        tasksState.loadState is ScreenLoadState.Content
        ) {
            nextTasksRefreshSilent = true
        }
        destination = returnDestination
        if (returnDestination == AppDestination.Home) {
            previousHomeRoute = returnPreviousHomeRoute
            homeRoute = returnHomeRoute ?: HomeRoute.Shell
            returnTab?.let { threadTab = it }
            if (returnHomeRoute == HomeRoute.Thread && returnSelectedThreadState != null) {
                selectedThreadId = returnSelectedThreadId
                selectedThreadState = returnSelectedThreadState
                replyThreadBackStack = returnReplyThreadBackStack.orEmpty()
                threadParentJumpTarget = returnThreadParentJumpTarget
                localComposerMessages = returnLocalComposerMessages.orEmpty()
                realtimeThreadMessages = returnRealtimeThreadMessages.orEmpty()
                attachmentOpenStates = returnAttachmentOpenStates.orEmpty()
                composerValue = returnComposerValue.orEmpty()
                composerAsTask = returnComposerAsTask ?: false
                pendingAttachmentFiles = returnPendingAttachmentFiles.orEmpty()
                failedAttachmentIds = returnFailedAttachmentIds.orEmpty()
                selectedThreadNearBottom = returnSelectedThreadNearBottom ?: false
                attachmentPreviewState = null
            }
        } else {
            previousHomeRoute = null
            homeRoute = HomeRoute.Shell
        }
        return true
    }

    fun addSelectedThreadMember(rawMemberId: String, kind: MemberKind) {
        val channelId = selectedThreadChannelId() ?: return
        val memberIdValue = rawMemberId.trim().removePrefix("@")
        if (memberIdValue.isBlank()) {
            threadMembersPanelState =
                    threadMembersPanelState.copy(
                            addStatus = ThreadMemberAddStatus.Failed,
                            addErrorCode = "member_id_required"
                    )
            return
        }
        threadMembersPanelState =
                threadMembersPanelState.copy(
                        addStatus = ThreadMemberAddStatus.Adding,
                        addErrorCode = null
        )
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "channel:${channelId.value}:add-member:$memberIdValue",
                            reason = "add_channel_member"
                    ) {
                        memberRepository.addChannelMember(
                                priority = SlockRequestPriority.Foreground,
                                AddChannelMemberRequest(
                                        channelId = channelId,
                                        memberId = MemberId(memberIdValue),
                                        kind = kind
                                )
                        )
                    }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadMembers",
                    "add_result channelId=${channelId.value} memberId=$memberIdValue kind=$kind result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
            when (result) {
                is DataResult.Content -> {
                    val membersResult =
                            withSlockRequestPriority(
                                    priority = SlockRequestPriority.Foreground,
                                    key = "channel:${channelId.value}:members",
                                    reason = "add_channel_member_reload"
                            ) {
                                memberRepository.getChannelMembers(SlockRequestPriority.Foreground, channelId)
                            }
                    if (!uiResultOwner(
                                    source = "add_member",
                                    isCurrent = { selectedThreadChannelId() == channelId },
                                    staleDetail = {
                                        "channelId=${channelId.value} selectedChannelId=${selectedThreadChannelId()?.value.orEmpty()}"
                                    }
                            ).isCurrentOrSkip()
                    ) return@launch
                    threadMembersPanelState =
                            threadMembersPanelState.copy(
                                    loadState = membersResult.toThreadMembersPanelLoadState(),
                                    addStatus = ThreadMemberAddStatus.Idle,
                                    addErrorCode = null
                            )
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockThreadMembers",
                            "reload_after_add channelId=${channelId.value} result=${membersResult.realtimeResultName()} code=${membersResult.readDiagnosticCode()}"
                    )
                }
                is DataResult.Empty -> {
                    threadMembersPanelState =
                            threadMembersPanelState.copy(
                                    addStatus = ThreadMemberAddStatus.Failed,
                                    addErrorCode = result.reason.name
                            )
                }
                is DataResult.Error -> {
                    threadMembersPanelState =
                            threadMembersPanelState.copy(
                                    addStatus = ThreadMemberAddStatus.Failed,
                                    addErrorCode = result.error.code
                            )
                }
                is DataResult.NoPermission -> {
                    threadMembersPanelState =
                            threadMembersPanelState.copy(
                                    addStatus = ThreadMemberAddStatus.NoPermission,
                                    addErrorCode = result.blocker.code
                            )
                }
            }
        }
    }

    fun removeSelectedThreadMember(member: MemberProfile) {
        val channelId = selectedThreadChannelId() ?: return
        if (member.kind != MemberKind.Agent) return
        threadMembersPanelState =
                threadMembersPanelState.copy(
                        removeStatus = ThreadMemberRemoveStatus.Removing,
                        removingMemberId = member.id,
                        removeErrorCode = null
        )
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "channel:${channelId.value}:remove-member:${member.id.value}",
                            reason = "remove_channel_member"
                    ) {
                        memberRepository.removeChannelMember(
                                priority = SlockRequestPriority.Foreground,
                                RemoveChannelMemberRequest(
                                        channelId = channelId,
                                        memberId = member.id,
                                        kind = member.kind
                                )
                        )
                    }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadMembers",
                    "remove_result channelId=${channelId.value} memberId=${member.id.value} kind=${member.kind} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
            when (result) {
                is DataResult.Content -> {
                    val membersResult =
                            withSlockRequestPriority(
                                    priority = SlockRequestPriority.Foreground,
                                    key = "channel:${channelId.value}:members",
                                    reason = "remove_channel_member_reload"
                            ) {
                                memberRepository.getChannelMembers(SlockRequestPriority.Foreground, channelId)
                            }
                    if (!uiResultOwner(
                                    source = "remove_member",
                                    isCurrent = { selectedThreadChannelId() == channelId },
                                    staleDetail = {
                                        "channelId=${channelId.value} selectedChannelId=${selectedThreadChannelId()?.value.orEmpty()}"
                                    }
                            ).isCurrentOrSkip()
                    ) return@launch
                    threadMembersPanelState =
                            threadMembersPanelState.copy(
                                    loadState = membersResult.toThreadMembersPanelLoadState(),
                                    removeStatus = ThreadMemberRemoveStatus.Idle,
                                    removingMemberId = null,
                                    removeErrorCode = null
                            )
                    if (membersDetailSelectedMemberId == member.id.value) {
                        membersDetailSelectedMemberId = null
                    }
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockThreadMembers",
                            "reload_after_remove channelId=${channelId.value} result=${membersResult.realtimeResultName()} code=${membersResult.readDiagnosticCode()}"
                    )
                }
                is DataResult.Empty -> {
                    threadMembersPanelState =
                            threadMembersPanelState.copy(
                                    removeStatus = ThreadMemberRemoveStatus.Failed,
                                    removingMemberId = member.id,
                                    removeErrorCode = result.reason.name
                            )
                }
                is DataResult.Error -> {
                    threadMembersPanelState =
                            threadMembersPanelState.copy(
                                    removeStatus = ThreadMemberRemoveStatus.Failed,
                                    removingMemberId = member.id,
                                    removeErrorCode = result.error.code
                            )
                }
                is DataResult.NoPermission -> {
                    threadMembersPanelState =
                            threadMembersPanelState.copy(
                                    removeStatus = ThreadMemberRemoveStatus.NoPermission,
                                    removingMemberId = member.id,
                                    removeErrorCode = result.blocker.code
                            )
                }
            }
        }
    }

    fun joinVisibleChannel(conversation: ConversationSummary) {
        val channelId = conversation.channelId ?: conversation.threadId.parentChannelIdOrNull()
            ?: ChannelId(conversation.threadId.value)
        selectedThreadState = selectedThreadState.withChannelJoinStatus(ChannelJoinStatus.Joining)
        coroutineScope.launch {
            when (val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "channel:${channelId.value}",
                            reason = "join_channel"
                    ) {
                        channelRepository.joinChannel(channelId)
                    }) {
                is DataResult.Content -> {
                    val fallback = selectedThreadContent()?.header ?: conversation
                    val joinedChannel = result.data.toJoinedConversation(fallback)
                    selectedThreadId = joinedChannel.threadId.value
                    val serverId =
                            selectedServerOverride
                                    ?: (serverListState as? ScreenLoadState.Content)
                                            ?.data
                                            ?.selectedServer
                                            ?.id
                                    ?: currentAuthSession?.currentServerId?.let(::ServerId)
                    if (serverId != null) {
                        localWriteThrough.writeHomeConversations(
                                serverId = serverId,
                                conversations = listOf(joinedChannel),
                                source = "join_channel"
                        )
                        publishHomeFromLocal(serverId)
                    } else {
                        homeState = homeState.withJoinedChannel(joinedChannel)
                    }
                    selectedThreadState = selectedThreadState.withJoinedChannel(joinedChannel)
                    realtimeRepository.joinChannel(joinedChannel.channelId ?: channelId)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockChannelJoin",
                            "join_success channelId=${channelId.value} threadId=${joinedChannel.threadId.value}"
                    )
                }
                is DataResult.Empty -> {
                    selectedThreadState =
                            selectedThreadState.withChannelJoinStatus(
                                    ChannelJoinStatus.Failed,
                                    result.reason.name
                            )
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockChannelJoin",
                            "join_empty channelId=${channelId.value} code=${result.reason.name}"
                    )
                }
                is DataResult.Error -> {
                    selectedThreadState =
                            selectedThreadState.withChannelJoinStatus(
                                    ChannelJoinStatus.Failed,
                                    result.error.code
                            )
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockChannelJoin",
                            "join_error channelId=${channelId.value} code=${result.error.code}"
                    )
                }
                is DataResult.NoPermission -> {
                    selectedThreadState =
                            selectedThreadState.withChannelJoinStatus(
                                    ChannelJoinStatus.NoPermission,
                                    result.blocker.code
                            )
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockChannelJoin",
                            "join_no_permission channelId=${channelId.value} code=${result.blocker.code}"
                    )
                }
            }
        }
    }

    fun openMessageThread(message: Message, fromParentPane: Boolean = false) {
        nextThreadNavigationGeneration()
        composerDrafts =
                composerDrafts.saveComposerDraft(
                        selectedThreadId,
                        composerValue,
                        composerAsTask,
                        pendingAttachmentFiles,
                        failedAttachmentIds
                )
        val parentPaneEntry = replyThreadBackStack.lastOrNull()
        val openingReplyFromParentPane = fromParentPane && parentPaneEntry != null
        val parentPaneContent =
                (parentPaneEntry?.selectedThreadState?.loadState as? ScreenLoadState.Content)?.data
        val currentHeader =
                if (openingReplyFromParentPane) {
                    parentPaneContent?.header
                } else {
                    selectedThreadContent()?.header
                }
        val fallbackHeader = message.toConversationSummary(currentHeader)
        val existingThreadChannelId = message.threadChannelId
        selectedThreadRetryAction = { openMessageThread(message, fromParentPane) }
        if (existingThreadChannelId != null) {
            if (!openingReplyFromParentPane) {
                replyThreadBackStack =
                        replyThreadBackStack + currentReplyThreadBackStackEntry(message.id)
            }
            threadParentJumpTarget = message.id.value
            selectedThreadId = existingThreadChannelId.value
            selectedThreadState =
                    (localThreadUiState(
                                    ThreadId(existingThreadChannelId.value),
                                    fallbackHeader = fallbackHeader,
                                    parentMessage = message
                            )
                                    ?: ThreadUiState(
                                            ScreenLoadState.Content(
                                                    ThreadContent(
                                                                    header = fallbackHeader,
                                                                    messages = emptyList()
                                                            )
                                                            .copy(parentMessage = message)
                                            ),
                                            composerState = fallbackHeader.toComposerState()
                                    ))
                            .withBackgroundThreadRefreshIndicator()
            localComposerMessages = emptyList()
            realtimeThreadMessages = emptyList()
            attachmentOpenStates = emptyMap()
            val draft = composerDrafts[existingThreadChannelId.value]
            composerValue = draft?.value.orEmpty()
            composerAsTask = draft?.asTask ?: false
            pendingAttachmentFiles = draft?.attachments.orEmpty()
            failedAttachmentIds = draft?.failedAttachmentIds.orEmpty()
            coroutineScope.launch {
                val result =
                        withSlockRequestPriority(
                                priority = SlockRequestPriority.Foreground,
                                key = "thread:${existingThreadChannelId.value}",
                                reason = "open_existing_reply_thread"
                        ) {
                            conversationRepository.getThread(ThreadId(existingThreadChannelId.value))
                        }
                cacheOpenedThreadResult(
                        localWriteThrough = localWriteThrough,
                        result = result,
                        source = "open_existing_reply_thread",
                        fallbackThreadId = ThreadId(existingThreadChannelId.value),
                        fallbackHeader = fallbackHeader,
                        parentMessage = message,
                        serverId = selectedServerIdForUi()
                )
                result.rememberTaskReferences()
                if (!uiResultOwner(
                                source = "open_existing_reply_thread",
                                isCurrent = {
                                    isCurrentMainThread(ThreadId(existingThreadChannelId.value))
                                },
                                staleDetail = {
                                    "threadId=${existingThreadChannelId.value} selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute"
                                }
                        ).isCurrentOrSkip()
                ) return@launch
                selectedThreadState =
                        localThreadUiState(
                                ThreadId(existingThreadChannelId.value),
                                fallbackHeader = fallbackHeader,
                                parentMessage = message,
                                serverId = selectedServerIdForUi()
                        )
                                ?: result.toThreadUiState(
                                        fallbackHeader = fallbackHeader,
                                        parentMessage = message,
                                        serverId = selectedServerIdForUi()
                                )
                (result as? DataResult.Content)?.data?.let { snapshot ->
                    selectedThreadRetryAction = null
                    markThreadReadIfLoaded(
                            ThreadContent(
                                    header =
                                            snapshot.conversation.withTitleFallback(fallbackHeader),
                                    messages = snapshot.messages
                            )
                    )
                }
            }
            return
        }
        if (openingReplyFromParentPane && parentPaneEntry != null) {
            replyThreadBackStack =
                    replyThreadBackStack.dropLast(1) +
                            parentPaneEntry.copy(
                                    selectedThreadState =
                                            parentPaneEntry.selectedThreadState
                                                    .withReplyActionError(null)
                            )
        } else {
            selectedThreadState = selectedThreadState.withReplyActionError(null)
        }
        val replyOpenSourceThreadId = selectedThreadId
        val replyOpenOwner =
                uiResultOwner(
                        source = "open_message_thread",
                        isCurrent = {
                            homeRoute == HomeRoute.Thread &&
                                    selectedThreadId == replyOpenSourceThreadId
                        },
                        staleDetail = {
                            "messageId=${message.id.value} sourceThreadId=${replyOpenSourceThreadId.orEmpty()} selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute"
                        }
                )
        coroutineScope.launch {
            val parentChannelIdCandidate =
                    message.channelId
                            ?: message.threadId.parentChannelIdOrNull()
                            ?: currentHeader?.channelId
                            ?: currentHeader?.thread?.parentChannelId
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadReplies",
                    "open_message_thread_context_start messageId=${message.id.value} " +
                            "parentChannelIdCandidate=${parentChannelIdCandidate?.value.orEmpty()} " +
                            "messageThreadId=${message.threadId.value}"
            )
            val contextResult =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "message:${message.id.value}",
                            reason = "open_reply_context"
                    ) {
                        conversationRepository.getMessageContext(message.id)
                    }
            cacheOpenedThreadResult(
                    localWriteThrough = localWriteThrough,
                    result = contextResult,
                    source = "open_reply_context",
                    fallbackThreadId = message.threadId,
                    serverId = selectedServerIdForUi()
            )
            contextResult.rememberTaskReferences()
            val contextSnapshot = (contextResult as? DataResult.Content)?.data
            val contextMessage =
                    contextSnapshot?.messages?.firstOrNull { it.id == message.id }
                            ?: contextSnapshot?.parentMessage?.takeIf { it.id == message.id }
            val resolvedParentChannelId =
                    contextMessage?.channelId
                            ?: contextSnapshot?.conversation?.channelId
                            ?: contextSnapshot?.conversation?.thread?.parentChannelId
                            ?: contextSnapshot?.conversation?.threadId?.parentChannelIdOrNull()
                            ?: parentChannelIdCandidate
            if (resolvedParentChannelId == null) {
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                        "SlockThreadReplies",
                        "open_message_thread_missing_parent_channel messageId=${message.id.value} " +
                                "messageThreadId=${message.threadId.value} " +
                                "contextThreadId=${contextSnapshot?.conversation?.threadId?.value.orEmpty()}"
                )
                if (!replyOpenOwner.isCurrentOrSkip()) return@launch
                selectedThreadState =
                        selectedThreadState.withReplyActionError("missing_parent_channel")
                return@launch
            }
            val contextResolvedMessage =
                    contextMessage?.copy(channelId = contextMessage.channelId ?: resolvedParentChannelId)
                            ?: message.copy(channelId = message.channelId ?: resolvedParentChannelId)
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadReplies",
                            "open_message_thread_context_result messageId=${message.id.value} " +
                            "result=${contextResult.realtimeResultName()} " +
                            "resolvedParentChannelId=${resolvedParentChannelId.value} " +
                            "contextThreadId=${contextSnapshot?.conversation?.threadId?.value.orEmpty()} " +
                            "contextMessageCount=${contextSnapshot?.messages?.size ?: 0}"
            )
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadReplies",
                    "open_message_thread_create_start parentMessageId=${message.id.value} " +
                            "parentChannelId=${resolvedParentChannelId.value}"
            )
            val createResult =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "thread-parent:${message.id.value}",
                            reason = "create_or_open_reply_thread"
                    ) {
                        conversationRepository.createOrOpenThread(
                                channelId = resolvedParentChannelId,
                                parentMessageId = message.id
                        )
                    }
            if (createResult !is DataResult.Content) {
                if (!replyOpenOwner.isCurrentOrSkip()) return@launch
                if (openingReplyFromParentPane && parentPaneEntry != null) {
                    replyThreadBackStack =
                            replyThreadBackStack.dropLast(1) +
                                    parentPaneEntry.copy(
                                            selectedThreadState =
                                                    parentPaneEntry.selectedThreadState
                                                            .withReplyActionError(
                                                                    createResult
                                                                            .readDiagnosticCode()
                                                            )
                                    )
                } else {
                    selectedThreadState =
                            selectedThreadState.withReplyActionError(
                                    createResult.readDiagnosticCode()
                            )
                }
                return@launch
            }
            val action = createResult.data
            if (!replyOpenOwner.isCurrentOrSkip()) return@launch
            if (openingReplyFromParentPane && parentPaneEntry != null) {
                replyThreadBackStack =
                        replyThreadBackStack.dropLast(1) +
                                parentPaneEntry.copy(
                                        selectedThreadState =
                                                parentPaneEntry.selectedThreadState
                                                        .mergeThreadActionResult(action)
                                )
            } else {
                selectedThreadState = selectedThreadState.mergeThreadActionResult(action)
                replyThreadBackStack =
                        replyThreadBackStack +
                                currentReplyThreadBackStackEntry(
                                        action.parentMessageId ?: message.id
                                )
            }
            threadParentJumpTarget = (action.parentMessageId ?: message.id).value
            val resolvedMessage =
                    contextResolvedMessage.copy(
                            threadChannelId = action.threadChannelId,
                            replyCount = maxOf(contextResolvedMessage.replyCount, action.replyCount)
                    )
            val resolvedFallbackHeader = resolvedMessage.toConversationSummary(currentHeader)
            selectedThreadId = action.threadChannelId.value
            selectedThreadState =
                    (localThreadUiState(
                                    ThreadId(action.threadChannelId.value),
                                    fallbackHeader = resolvedFallbackHeader,
                                    parentMessage = resolvedMessage
                            )
                                    ?: ThreadUiState(
                                            ScreenLoadState.Content(
                                                    ThreadContent(
                                                                    header = resolvedFallbackHeader,
                                                                    messages = emptyList()
                                                            )
                                                            .copy(parentMessage = resolvedMessage)
                                            ),
                                            composerState = resolvedFallbackHeader.toComposerState()
                                    ))
                            .withBackgroundThreadRefreshIndicator()
            localComposerMessages = emptyList()
            realtimeThreadMessages = emptyList()
            attachmentOpenStates = emptyMap()
            val draft = composerDrafts[action.threadChannelId.value]
            composerValue = draft?.value.orEmpty()
            composerAsTask = draft?.asTask ?: false
            pendingAttachmentFiles = draft?.attachments.orEmpty()
            failedAttachmentIds = draft?.failedAttachmentIds.orEmpty()
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "thread:${action.threadChannelId.value}",
                            reason = "open_created_reply_thread"
                    ) {
                        conversationRepository.getThread(ThreadId(action.threadChannelId.value))
                    }
            localWriteThrough.writeThreadResult(
                    result,
                    source = "open_created_reply_thread",
                    fallbackThreadId = ThreadId(action.threadChannelId.value),
                    serverId = selectedServerIdForUi()
            )
            result.rememberTaskReferences()
            if (!uiResultOwner(
                            source = "open_created_reply_thread",
                            isCurrent = {
                                isCurrentMainThread(ThreadId(action.threadChannelId.value))
                            },
                            staleDetail = {
                                "threadId=${action.threadChannelId.value} selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute"
                            }
                    ).isCurrentOrSkip()
            ) return@launch
            selectedThreadState =
                    (localThreadUiState(
                                    ThreadId(action.threadChannelId.value),
                                    fallbackHeader = resolvedFallbackHeader,
                                    parentMessage = resolvedMessage,
                                    serverId = selectedServerIdForUi()
                            )
                                    ?: result.toThreadUiState(
                                            fallbackHeader = resolvedFallbackHeader,
                                            parentMessage = resolvedMessage,
                                            serverId = selectedServerIdForUi()
                                    ))
                            .withCachedTaskReferences()
            (result as? DataResult.Content)?.data?.let { snapshot ->
                selectedThreadRetryAction = null
                markThreadReadIfLoaded(
                        ThreadContent(
                                header =
                                        snapshot.conversation.withTitleFallback(
                                                resolvedFallbackHeader
                                        ),
                                messages = snapshot.messages
                        )
                )
            }
        }
    }

    fun openTaskContext(task: TaskSummary, fromParentPane: Boolean = false) {
        val localTaskContext = localWriteThrough.taskContext(task, selectedServerIdForUi())
        val resolvedTask = localTaskContext.task
        val parentMessageId = resolvedTask.parentMessageId ?: resolvedTask.messageId
        val messageId = resolvedTask.messageId
        val threadId = resolvedTask.threadId
        if (messageId == null && threadId == null) {
            tasksState = tasksState.copy(navigationErrorCode = "missing_context")
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                    "SlockTasksNav",
                    "open_missing_context taskId=${resolvedTask.id.value} taskNumber=${resolvedTask.taskNumber}"
            )
            return
        }
        val navigationGeneration = nextThreadNavigationGeneration()
        val navigationOwner =
                uiResultOwner(
                        source = "open_task_context",
                        isCurrent = {
                            isCurrentThreadNavigationGeneration(navigationGeneration)
                        },
                        staleDetail = {
                            "generation=$navigationGeneration currentGeneration=$threadNavigationGeneration selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute destination=$destination"
                        }
                )
        tasksState = tasksState.copy(navigationErrorCode = null)
        val optimisticSourceHeader =
                taskSourceHeader(resolvedTask)
                        ?: localTaskContext.sourceConversation
                        ?: localTaskContext.replyConversation
        val optimisticFallbackHeader = resolvedTask.toConversationSummary(optimisticSourceHeader)
        val optimisticOpenedThreadId =
                optimisticFallbackHeader.messageStreamChannelId().value
        val didOpenOptimistically =
                !fromParentPane &&
                        optimisticOpenedThreadId.isNotBlank()
        if (didOpenOptimistically) {
            captureThreadReturnTarget()
            composerDrafts =
                    composerDrafts.saveComposerDraft(
                            selectedThreadId,
                            composerValue,
                            composerAsTask,
                            pendingAttachmentFiles,
                            failedAttachmentIds
                    )
            destination = AppDestination.Home
            replyThreadBackStack = emptyList()
            threadParentJumpTarget =
                    parentMessageId?.value.takeIf {
                        optimisticFallbackHeader.kind == ConversationKind.Thread
                    }
            previousHomeRoute =
                    if (threadReturnHomeRoute == HomeRoute.Thread) null else HomeRoute.Shell
            homeRoute = HomeRoute.Thread
            threadTab = ThreadTab.Chat
            selectedThreadId = optimisticOpenedThreadId
            selectedThreadState =
                    (
                            localThreadUiState(
                                    ThreadId(optimisticOpenedThreadId),
                                    fallbackHeader = optimisticFallbackHeader,
                                    parentMessage = localTaskContext.parentMessage,
                                    serverId = selectedServerIdForUi()
                            )
                                    ?: DataResult.Empty(EmptyReason.NoMessages).toThreadUiState(
                                            fallbackHeader = optimisticFallbackHeader,
                                            parentMessage = localTaskContext.parentMessage,
                                            serverId = selectedServerIdForUi()
                                    )
                            ).withCachedTaskReferences()
            localComposerMessages = emptyList()
            realtimeThreadMessages = emptyList()
            attachmentOpenStates = emptyMap()
            val draft = composerDrafts[optimisticOpenedThreadId]
            composerValue = draft?.value.orEmpty()
            composerAsTask = draft?.asTask ?: false
            pendingAttachmentFiles = draft?.attachments.orEmpty()
            failedAttachmentIds = draft?.failedAttachmentIds.orEmpty()
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasksNav",
                    "open_optimistic taskId=${resolvedTask.id.value} selectedThreadId=$optimisticOpenedThreadId messageId=${messageId?.value} parentMessageId=${parentMessageId?.value}"
            )
        }
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasksNav",
                    "open_start taskId=${resolvedTask.id.value} taskNumber=${resolvedTask.taskNumber} messageId=${messageId?.value} parentMessageId=${parentMessageId?.value} threadId=${threadId?.value} localParent=${localTaskContext.parentMessage?.id?.value.orEmpty()} localSource=${localTaskContext.sourceConversation?.messageStreamChannelId()?.value.orEmpty()} localReply=${localTaskContext.replyConversation?.messageStreamChannelId()?.value.orEmpty()}"
            )
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "task:${resolvedTask.id.value}",
                            reason = "open_task_context"
                    ) {
                        when {
                            resolvedTask.isThreadTask && threadId != null ->
                                    conversationRepository.getThread(threadId)
                            messageId != null -> conversationRepository.getMessageContext(messageId)
                            else -> conversationRepository.getMessageContext(parentMessageId!!)
                        }
                    }
            val parentContextResult =
                    if (parentMessageId != null &&
                                    (resolvedTask.isThreadTask && threadId != null ||
                                            (messageId != null && parentMessageId != messageId))
                    ) {
                        withSlockRequestPriority(
                                priority = SlockRequestPriority.Foreground,
                                key = "task-parent:${resolvedTask.id.value}",
                                reason = "open_task_parent_context"
                        ) {
                            conversationRepository.getMessageContext(parentMessageId)
                        }
                    } else {
                        result
                    }
            val parentMessage =
                    parentContextResult.findParentMessage(parentMessageId)
                            ?: localTaskContext.parentMessage
            cacheOpenedThreadResult(
                    localWriteThrough = localWriteThrough,
                    result = result,
                    source = "open_task_context",
                    fallbackThreadId = threadId,
                    parentMessage = parentMessage,
                    serverId = selectedServerIdForUi()
            )
            result.rememberTaskReferences()
            cacheOpenedThreadResult(
                    localWriteThrough = localWriteThrough,
                    result = parentContextResult,
                    source = "open_task_parent_context",
                    fallbackThreadId = parentMessageId?.value?.let(::ThreadId),
                    serverId = selectedServerIdForUi()
            )
            parentContextResult.rememberTaskReferences()
            when (result) {
                is DataResult.Content -> {
                    val replyThreadChannelId =
                            resolvedTask.replyThreadChannelId(parentMessage, result.data)
                    val shouldOpenReplyThread =
                            resolvedTask.shouldOpenReplyThread(parentMessage, replyThreadChannelId)
                    val displayResult =
                            if (shouldOpenReplyThread && replyThreadChannelId != null) {
                                val replyThreadResult =
                                        withSlockRequestPriority(
                                                priority = SlockRequestPriority.Foreground,
                                                key = "thread:${replyThreadChannelId.value}",
                                                reason = "open_task_reply_thread"
                                        ) {
                                            conversationRepository.getThread(
                                                    ThreadId(replyThreadChannelId.value)
                                            )
                                        }
                                cacheOpenedThreadResult(
                                        localWriteThrough = localWriteThrough,
                                        result = replyThreadResult,
                                        source = "open_task_reply_thread",
                                        fallbackThreadId = ThreadId(replyThreadChannelId.value),
                                        parentMessage = parentMessage,
                                        serverId = selectedServerIdForUi()
                                )
                                replyThreadResult.rememberTaskReferences()
                                replyThreadResult
                            } else {
                                result
                            }
                    if (displayResult !is DataResult.Content) {
                        if (!navigationOwner.isCurrentOrSkip()) return@launch
                        selectedThreadRetryAction = { openTaskContext(resolvedTask, fromParentPane) }
                        tasksState =
                                tasksState.copy(
                                        navigationErrorCode = displayResult.readDiagnosticCode()
                                )
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockTasksNav",
                                "open_reply_thread_error taskId=${resolvedTask.id.value} " +
                                        "replyThreadId=${replyThreadChannelId?.value.orEmpty()} " +
                                        "code=${displayResult.readDiagnosticCode()}"
                        )
                        return@launch
                    }
                    val sourceHeader =
                            taskSourceHeader(resolvedTask)
                                    ?: localTaskContext.sourceConversation
                                    ?: localTaskContext.replyConversation
                    val fallbackHeader =
                            resolvedTask.toConversationSummary(
                                    sourceHeader ?: displayResult.data.conversation
                            )
                    if (!navigationOwner.isCurrentOrSkip()) return@launch
                    if (!didOpenOptimistically) {
                        captureThreadReturnTarget()
                        composerDrafts =
                                composerDrafts.saveComposerDraft(
                                        selectedThreadId,
                                        composerValue,
                                        composerAsTask,
                                        pendingAttachmentFiles,
                                        failedAttachmentIds
                                )
                    }
                    destination = AppDestination.Home
                    if (!fromParentPane) {
                        replyThreadBackStack =
                                if (shouldOpenReplyThread && parentMessage != null) {
                                    listOf(currentReplyThreadBackStackEntry(parentMessage.id))
                                } else {
                                    emptyList()
                                }
                    }
                    threadParentJumpTarget =
                            if (shouldOpenReplyThread) parentMessage?.id?.value ?: parentMessageId?.value
                            else null
                    previousHomeRoute =
                            if (threadReturnHomeRoute == HomeRoute.Thread) null else HomeRoute.Shell
                    homeRoute = HomeRoute.Thread
                    threadTab = ThreadTab.Chat
                    val openedThreadChannelId =
                            displayResult.data.conversation.channelId?.value
                                    ?: displayResult.data.conversation.threadId.value
                    selectedThreadId = openedThreadChannelId
                    selectedThreadState =
                            (
                                    localThreadUiState(
                                            ThreadId(openedThreadChannelId),
                                            fallbackHeader = fallbackHeader,
                                            parentMessage = parentMessage,
                                            serverId = selectedServerIdForUi()
                                    )
                                            ?: displayResult.toThreadUiState(
                                                    fallbackHeader = fallbackHeader,
                                                    parentMessage = parentMessage,
                                                    serverId = selectedServerIdForUi()
                                            )
                                    ).withCachedTaskReferences()
                    localComposerMessages = emptyList()
                    realtimeThreadMessages = emptyList()
                    attachmentOpenStates = emptyMap()
                    val draft = composerDrafts[openedThreadChannelId]
                    composerValue = draft?.value.orEmpty()
                    composerAsTask = draft?.asTask ?: false
                    pendingAttachmentFiles = draft?.attachments.orEmpty()
                    failedAttachmentIds = draft?.failedAttachmentIds.orEmpty()
                    markThreadReadIfLoaded(
                            ThreadContent(
                                    header =
                                            displayResult.data.conversation.withTitleFallback(
                                                    fallbackHeader
                                            ),
                                    messages = displayResult.data.messages
                            )
                    )
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockTasksNav",
                            "open_success taskId=${resolvedTask.id.value} selectedThreadId=$openedThreadChannelId headerThreadId=${displayResult.data.conversation.threadId.value} headerChannelId=${displayResult.data.conversation.channelId?.value.orEmpty()} replyThread=${shouldOpenReplyThread} parentMessageId=${parentMessage?.id?.value.orEmpty()} highlighted=${displayResult.data.highlightedMessageId?.value}"
                    )
                }
                is DataResult.Empty -> {
                    if (!navigationOwner.isCurrentOrSkip()) return@launch
                    tasksState = tasksState.copy(navigationErrorCode = result.reason.name)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockTasksNav",
                            "open_empty taskId=${resolvedTask.id.value} code=${result.reason.name}"
                    )
                }
                is DataResult.Error -> {
                    if (!navigationOwner.isCurrentOrSkip()) return@launch
                    tasksState = tasksState.copy(navigationErrorCode = result.error.code)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockTasksNav",
                            "open_error taskId=${resolvedTask.id.value} code=${result.error.code}"
                    )
                }
                is DataResult.NoPermission -> {
                    if (!navigationOwner.isCurrentOrSkip()) return@launch
                    tasksState = tasksState.copy(navigationErrorCode = result.blocker.code)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockTasksNav",
                            "open_no_permission taskId=${resolvedTask.id.value} code=${result.blocker.code}"
                    )
                }
            }
        }
    }

    fun openThreadParentMessage(target: ThreadParentJumpTarget) {
        val previousThreadState = selectedThreadState
        val navigationGeneration = nextThreadNavigationGeneration()
        val requestedFromThreadId = selectedThreadId
        val navigationOwner =
                uiResultOwner(
                        source = "open_thread_parent_message",
                        isCurrent = {
                            selectedThreadId == requestedFromThreadId &&
                                    homeRoute == HomeRoute.Thread &&
                                    isCurrentThreadNavigationGeneration(navigationGeneration)
                        },
                        staleDetail = {
                            "generation=$navigationGeneration currentGeneration=$threadNavigationGeneration requestedFromThreadId=${requestedFromThreadId.orEmpty()} selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute"
                        }
                )
        composerDrafts =
                composerDrafts.saveComposerDraft(
                        selectedThreadId,
                        composerValue,
                        composerAsTask,
                        pendingAttachmentFiles,
                        failedAttachmentIds
                )
        val parentEntry =
                replyThreadBackStack.lastOrNull {
                    it.parentMessageId == target.parentMessageId
                }
        if (parentEntry != null) {
            replyThreadBackStack = replyThreadBackStack.dropLast(1)
            threadParentJumpTarget = parentEntry.parentJumpTarget
            selectedThreadId = parentEntry.selectedThreadId
            attachmentPreviewState = null
            selectedThreadState = parentEntry.selectedThreadState.withHighlightedMessage(target.parentMessageId)
            previousHomeRoute = parentEntry.previousHomeRoute
            homeRoute = HomeRoute.Thread
            localComposerMessages = parentEntry.localComposerMessages
            realtimeThreadMessages = parentEntry.realtimeThreadMessages
            attachmentOpenStates = parentEntry.attachmentOpenStates
            composerValue = parentEntry.composerValue
            composerAsTask = parentEntry.composerAsTask
            pendingAttachmentFiles = parentEntry.pendingAttachmentFiles
            failedAttachmentIds = parentEntry.failedAttachmentIds
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasksNav",
                    "open_parent_restore parentMessageId=${target.parentMessageId.value} selectedThreadId=${selectedThreadId.orEmpty()}"
            )
            return
        }
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasksNav",
                    "open_parent_start parentMessageId=${target.parentMessageId.value}"
            )
            when (val result = conversationRepository.getMessageContext(target.parentMessageId)) {
                is DataResult.Content -> {
                    if (!navigationOwner.isCurrentOrSkip()) return@launch
                    localWriteThrough.writeThreadResult(
                            result,
                            source = "open_thread_parent_message",
                            fallbackThreadId = target.parentMessageId.value.let(::ThreadId),
                            serverId = selectedServerIdForUi()
                    )
                    result.rememberTaskReferences()
                    replyThreadBackStack = emptyList()
                    threadParentJumpTarget = null
                    selectedThreadId = result.data.conversation.threadId.value
                    selectedThreadState =
                            (localThreadUiState(result.data.conversation.threadId, serverId = selectedServerIdForUi())
                                            ?: result.toThreadUiState(serverId = selectedServerIdForUi()))
                                    .withCachedTaskReferences()
                    localComposerMessages = emptyList()
                    realtimeThreadMessages = emptyList()
                    attachmentOpenStates = emptyMap()
                    val draft = composerDrafts[result.data.conversation.threadId.value]
                    composerValue = draft?.value.orEmpty()
                    composerAsTask = draft?.asTask ?: false
                    pendingAttachmentFiles = draft?.attachments.orEmpty()
                    failedAttachmentIds = draft?.failedAttachmentIds.orEmpty()
                    markThreadReadIfLoaded(
                            ThreadContent(
                                    header = result.data.conversation,
                                    messages = result.data.messages
                            )
                    )
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockTasksNav",
                            "open_parent_success parentMessageId=${target.parentMessageId.value} threadId=${result.data.conversation.threadId.value}"
                    )
                }
                is DataResult.Empty -> {
                    selectedThreadState =
                            previousThreadState.withReplyActionError(result.reason.name)
                }
                is DataResult.Error -> {
                    selectedThreadState =
                            previousThreadState.withReplyActionError(result.error.code)
                }
                is DataResult.NoPermission -> {
                    selectedThreadState =
                            previousThreadState.withReplyActionError(result.blocker.code)
                }
            }
        }
    }

    fun updateTaskStatus(task: TaskSummary, status: TaskStatus) {
        val threadTasksState = selectedThreadVisibleTasksState()
        val updateThreadTasks =
                threadTab == ThreadTab.Tasks &&
                        (threadTasksState?.loadState as? ScreenLoadState.Content)
                                ?.data
                                ?.tasks
                                ?.any { it.id == task.id } == true
        if (
                task.status == status ||
                        tasksState.statusUpdatingTaskId == task.id.value ||
                        (updateThreadTasks && threadTasksState?.statusUpdatingTaskId == task.id.value)
        ) {
            return
        }
        if (updateThreadTasks) {
            updateSelectedThreadTasksState { it.withTaskStatusUpdating(task.id.value, status) }
        } else {
            tasksState = tasksState.withTaskStatusUpdating(task.id.value, status)
        }
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasks",
                    "task_status_update_start displayTaskId=${task.id.value} actionTaskId=${task.id.value} status=${status.name} " +
                            "messageId=${task.messageId?.value.orEmpty()} parentMessageId=${task.parentMessageId?.value.orEmpty()} " +
                            "threadId=${task.threadId?.value.orEmpty()} channelId=${task.channelId?.value.orEmpty()} isThreadTask=${task.isThreadTask}"
            )
            when (val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "task:${task.id.value}",
                            reason = "task_status_update"
                    ) {
                        taskRepository.updateTaskStatus(task.id, status)
                    }) {
                is DataResult.Content -> {
                    val updatedTask = task.mergeTaskStatusAction(result.data.task)
                    writeTaskResultQueued(
                            DataResult.Content(
                                    TaskSnapshot(
                                            tasks = listOf(updatedTask),
                                            summaryCounts =
                                                    TaskStatus.entries.associateWith { taskStatus ->
                                                        if (updatedTask.status == taskStatus) 1 else 0
                                                    }
                                    )
                            ),
                            source = "task_status_update",
                            serverId = selectedServerIdForUi()
                    )
                    rememberTaskReference(updatedTask)
                    tasksState = tasksState.withTaskStatusUpdateSuccess(updatedTask)
                    selectedThreadState = selectedThreadState.withTaskStatusUpdate(updatedTask)
                    replyThreadBackStack =
                            replyThreadBackStack.map { entry ->
                                entry.copy(
                                        selectedThreadState =
                                                entry.selectedThreadState.withTaskStatusUpdate(
                                                        updatedTask
                                                )
                                )
                            }
                    if (updateThreadTasks) {
                        updateSelectedThreadTasksState {
                            it.withTaskStatusUpdateSuccess(updatedTask)
                        }
                    }
                }
                is DataResult.Empty -> {
                    if (updateThreadTasks) {
                        updateSelectedThreadTasksState {
                            it.withTaskStatusUpdateFailure(task, result.reason.name)
                        }
                    } else {
                        tasksState =
                                tasksState.withTaskStatusUpdateFailure(task, result.reason.name)
                    }
                }
                is DataResult.Error -> {
                    if (updateThreadTasks) {
                        updateSelectedThreadTasksState {
                            it.withTaskStatusUpdateFailure(task, result.error.code)
                        }
                    } else {
                        tasksState =
                                tasksState.withTaskStatusUpdateFailure(task, result.error.code)
                    }
                }
                is DataResult.NoPermission -> {
                    if (updateThreadTasks) {
                        updateSelectedThreadTasksState {
                            it.withTaskStatusUpdateFailure(task, result.blocker.code)
                        }
                    } else {
                        tasksState =
                                tasksState.withTaskStatusUpdateFailure(task, result.blocker.code)
                    }
                }
            }
        }
    }

    fun openAttachment(attachment: Attachment) {
        val attachmentThreadId = selectedThreadId?.let(::ThreadId)
        val attachmentOwner =
                uiResultOwner(
                        source = "open_attachment",
                        isCurrent = {
                            attachmentThreadId == null || isCurrentMainThread(attachmentThreadId)
                        },
                        staleDetail = {
                            "attachmentId=${attachment.id.value} threadId=${attachmentThreadId?.value.orEmpty()} selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute"
                        }
                )
        val isImageAttachment = attachment.mimeType.startsWith("image/")
        val isTextAttachment = attachment.isTextPreviewable()
        val immediatePreviewUrl = attachment.thumbnailUrl?.takeIf { it.isNotBlank() }

        fun showImagePreview(url: String) {
            attachmentOpenStates = attachmentOpenStates - attachment.id
            attachmentPreviewState =
                    AttachmentPreviewUiState(
                            status = AttachmentPreviewStatus.Showing,
                            attachmentId = attachment.id,
                            name = attachment.name,
                            url = url,
                            thumbnailUrl = attachment.thumbnailUrl,
                            mimeType = attachment.mimeType
                    )
        }

        fun failAttachmentOpen(errorCode: String) {
            if (isImageAttachment && immediatePreviewUrl != null) {
                attachmentOpenStates = attachmentOpenStates - attachment.id
            } else {
                attachmentOpenStates =
                        attachmentOpenStates +
                                (attachment.id to
                                        AttachmentOpenUiState(
                                                status = AttachmentOpenStatus.Error,
                                                errorCode = errorCode
                                        ))
            }
        }

        fun showTextPreview(targetUrl: String, text: String) {
            attachmentOpenStates = attachmentOpenStates - attachment.id
            attachmentPreviewState =
                    AttachmentPreviewUiState(
                            status = AttachmentPreviewStatus.Showing,
                            attachmentId = attachment.id,
                            name = attachment.name,
                            url = targetUrl,
                            mimeType = attachment.mimeType,
                            textContent = text
                    )
        }

        attachmentOpenStates =
                attachmentOpenStates +
                        (attachment.id to
                                AttachmentOpenUiState(status = AttachmentOpenStatus.Loading))
        if (isImageAttachment && immediatePreviewUrl != null) {
            showImagePreview(immediatePreviewUrl)
        } else {
            attachmentPreviewState = null
        }
        coroutineScope.launch {
            when (val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "attachment:${attachment.id.value}",
                            reason = "open_attachment"
                    ) {
                        conversationRepository.getAttachmentOpenTarget(attachment.id)
                    }) {
                is DataResult.Content -> {
                    val openResultName =
                            if (isImageAttachment) {
                                if (!attachmentOwner.isCurrentOrSkip()) return@launch
                                showImagePreview(result.data.url)
                                "preview"
                            } else if (isTextAttachment) {
                                val textContent =
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                        downloadAttachmentPreviewText(
                                                                result.data.url
                                                        )
                                                    }
                                                    .getOrNull()
                                        }
                                if (textContent == null) {
                                    if (!attachmentOwner.isCurrentOrSkip()) return@launch
                                    failAttachmentOpen("text_preview_failed")
                                    "text_preview_failed"
                                } else {
                                    if (!attachmentOwner.isCurrentOrSkip()) return@launch
                                    showTextPreview(result.data.url, textContent)
                                    "text_preview"
                                }
                            } else {
                                if (!attachmentOwner.isCurrentOrSkip()) return@launch
                                val opened =
                                        activityActions.openAttachmentUri(
                                                result.data.url,
                                                attachment.mimeType
                                        )
                                attachmentOpenStates =
                                        if (opened) {
                                            attachmentOpenStates - attachment.id
                                        } else {
                                            attachmentOpenStates +
                                                    (attachment.id to
                                                            AttachmentOpenUiState(
                                                                    status =
                                                                            AttachmentOpenStatus
                                                                                    .Error,
                                                                    errorCode = "no_handler"
                                                            ))
                                        }
                                if (opened) {
                                    "opened"
                                } else {
                                    "no_handler"
                                }
                            }
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockAttachment",
                            "open_result attachmentId=${attachment.id.value} result=$openResultName"
                    )
                }
                is DataResult.Empty -> {
                    if (!attachmentOwner.isCurrentOrSkip()) return@launch
                    failAttachmentOpen(result.reason.name)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockAttachment",
                            "open_empty attachmentId=${attachment.id.value} code=${result.reason.name}"
                    )
                }
                is DataResult.Error -> {
                    if (!attachmentOwner.isCurrentOrSkip()) return@launch
                    failAttachmentOpen(result.error.code)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockAttachment",
                            "open_error attachmentId=${attachment.id.value} code=${result.error.code}"
                    )
                }
                is DataResult.NoPermission -> {
                    if (!attachmentOwner.isCurrentOrSkip()) return@launch
                    failAttachmentOpen(result.blocker.code)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockAttachment",
                            "open_no_permission attachmentId=${attachment.id.value} code=${result.blocker.code}"
                    )
                }
            }
        }
    }

    fun dismissAttachmentPreview() {
        attachmentPreviewState = null
    }

    fun loadOlderThreadMessages(anchorMessageId: MessageId? = null, anchorOffset: Int = 0) {
        val threadId = selectedThreadId?.let(::ThreadId)
        if (threadId == null) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                    "SlockThreadPaging",
                    "older_skip reason=no_thread"
            )
            return
        }
        val content = selectedThreadContent()
        if (content == null) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                    "SlockThreadPaging",
                    "older_skip threadId=${threadId.value} reason=no_content"
            )
            return
        }
        val beforeSeq = content.messages.mapNotNull { it.seq }.minOrNull()
        if (beforeSeq == null) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                    "SlockThreadPaging",
                    "older_skip threadId=${threadId.value} reason=missing_before_seq messageCount=${content.messages.size}"
            )
            selectedThreadState =
                    selectedThreadState.withOlderPageStatus(
                            status = MessagePageStatus.Error,
                            errorCode = "missing_before_seq"
                    )
            return
        }
        if (selectedThreadState.isOlderPageLoading()) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadPaging",
                    "older_skip threadId=${threadId.value} reason=already_loading beforeSeq=$beforeSeq"
            )
            return
        }
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadPaging",
                "older_start threadId=${threadId.value} beforeSeq=$beforeSeq messageCount=${content.messages.size} canLoadMoreBefore=${content.canLoadMoreBefore}"
        )
        selectedThreadState =
                selectedThreadState.withOlderPageStatus(
                        status = MessagePageStatus.Loading,
                        anchorMessageId = anchorMessageId ?: content.messages.firstOrNull()?.id,
                        anchorOffset = anchorOffset
                )
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "thread:${threadId.value}",
                            reason = "page_older"
                    ) {
                        conversationRepository.loadOlderMessages(threadId, beforeSeq)
                    }
            localWriteThrough.writeThreadResult(
                    result,
                    source = "page_older",
                    fallbackThreadId = threadId,
                    serverId = selectedServerIdForUi()
            )
            result.rememberTaskReferences()
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadPaging",
                    result.toOlderPageLog(threadId.value, beforeSeq)
            )
            if (!uiResultOwner(
                            source = "page_older",
                            isCurrent = { isCurrentMainThread(threadId) },
                            staleDetail = {
                                "threadId=${threadId.value} selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute"
                            }
                    ).isCurrentOrSkip()
            ) return@launch
            selectedThreadState =
                    mergeOlderThreadPage(current = selectedThreadState, next = result)
                            .withCachedTaskReferences()
        }
    }

    fun loadNewerThreadMessages() {
        val threadId = selectedThreadId?.let(::ThreadId) ?: return
        val afterSeq =
                selectedThreadContent()?.messages?.mapNotNull { it.seq }?.maxOrNull() ?: return
        if (selectedThreadState.isNewerPageLoading()) return
        selectedThreadState = selectedThreadState.withNewerPageStatus(MessagePageStatus.Loading)
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "thread:${threadId.value}",
                            reason = "page_newer"
                    ) {
                        conversationRepository.loadNewerMessages(threadId, afterSeq)
                    }
            localWriteThrough.writeThreadResult(
                    result,
                    source = "page_newer",
                    fallbackThreadId = threadId,
                    serverId = selectedServerIdForUi()
            )
            result.rememberTaskReferences()
            if (!uiResultOwner(
                            source = "page_newer",
                            isCurrent = { isCurrentMainThread(threadId) },
                            staleDetail = {
                                "threadId=${threadId.value} selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute"
                            }
                    ).isCurrentOrSkip()
            ) return@launch
            selectedThreadState =
                    mergeNewerThreadPage(
                            current = selectedThreadState,
                            next = result
                    ).withCachedTaskReferences()
        }
    }

    val selectedThreadMembersChannelId = selectedThreadChannelId()
    LaunchedEffect(authState, selectedThreadMembersChannelId, memberRepository) {
        if (authState !is AuthUiState.Authenticated) return@LaunchedEffect
        val channelId = selectedThreadMembersChannelId ?: return@LaunchedEffect
        val serverId = currentAuthSession?.currentServerId?.let(::ServerId)
        threadMembersPanelState =
                ThreadMembersPanelState(
                        loadState = ScreenLoadState.Loading,
                        candidatesLoadState = ScreenLoadState.Loading,
                        fallbackMemberCount =
                                selectedThreadContent()?.header?.memberCount,
                        fallbackMemberCountKnown =
                                selectedThreadContent()?.header?.memberCount != null
                )
        val (result, candidatesResult) =
                coroutineScope {
                    val resultD =
                            async {
                                withSlockRequestPriority(
                                        priority = SlockRequestPriority.Visible,
                                        key = "thread_members:${channelId.value}",
                                        reason = "thread_members_panel"
                                ) {
                                    memberRepository.getChannelMembers(SlockRequestPriority.Visible, channelId)
                                }
                            }
                    val candidatesD =
                            serverId?.let { sid ->
                                async {
                                    withSlockRequestPriority(
                                            priority = SlockRequestPriority.Visible,
                                            key = "thread_members_candidates:${sid.value}",
                                            reason = "thread_members_panel"
                                    ) {
                                        memberRepository.getMembers(SlockRequestPriority.Visible, sid)
                                    }
                                }
                            }
                    Pair(resultD.await(), candidatesD?.await())
                }
        threadMembersPanelState =
                ThreadMembersPanelState(
                        loadState = result.toThreadMembersPanelLoadState(),
                        candidatesLoadState = candidatesResult.toThreadMembersPickerLoadState(),
                        fallbackMemberCount =
                                (result as? DataResult.Content)?.data?.members?.size
                                        ?: selectedThreadContent()?.header?.memberCount,
                        fallbackMemberCountKnown =
                                result is DataResult.Content ||
                                        selectedThreadContent()?.header?.memberCount != null
                )
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadMembers",
                "load_result channelId=${channelId.value} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
        )
    }

    LaunchedEffect(Unit) {
        delay(AUTH_RESTORE_SPLASH_MIN_DURATION_MS)
        restoreSplashVisible = false
        restoreCompletedState?.let { completed ->
            authState = completed
            restoreCompletedState = null
        }
    }

    LaunchedEffect(authRepository) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockAuth",
                "restore_effect_start"
        )
        val restoredState =
                when (val restoreResult = authRepository.restoreSession()) {
                    is AuthResult.Success -> {
                        currentAuthSession = authSessionStore.readSession()
                        authRoute = AuthRoute.Login
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockAuth",
                                "restore_effect_success direct_home=true hasSession=${currentAuthSession != null} serverCount=${currentAuthSession?.servers?.size ?: 0} selectedServer=${currentAuthSession?.currentServerId != null} emailVerified=${currentAuthSession?.user?.emailVerified}"
                        )
                        AuthUiState.Authenticated
                    }
                    AuthResult.Unauthenticated -> {
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockAuth",
                                "restore_effect_unauthenticated"
                        )
                        AuthUiState.SignedOut()
                    }
                    is AuthResult.Failure -> {
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockAuth",
                                "restore_effect_failure message=${restoreResult.message}"
                        )
                        AuthUiState.SignedOut()
                    }
                }
        if (restoreSplashVisible) {
            restoreCompletedState = restoredState
        } else {
            authState = restoredState
        }
    }

    if (restoreSplashVisible || authState is AuthUiState.Restoring) {
        AuthRestoreScreen(modifier = modifier)
        return
    }

    if (authState !is AuthUiState.Authenticated) {
        AuthRouteScreen(
                route = authRoute,
                onRouteChange = { route ->
                    authRoute = route
                    authState = AuthUiState.SignedOut()
                    forgotPasswordStatus = ForgotPasswordStatus.Default
                    createAccountStatus = CreateAccountStatus.Default
                },
                onLogin = { email, password ->
                    coroutineScope.launch {
                        authRoute = AuthRoute.Login
                        forgotPasswordStatus = ForgotPasswordStatus.Default
                        createAccountStatus = CreateAccountStatus.Default
                        authState = AuthUiState.Submitting
                        when (val result = authRepository.login(email, password)) {
                            is AuthResult.Success -> {
                                currentAuthSession = authSessionStore.readSession()
                                authState = AuthUiState.Authenticated
                                authRoute = AuthRoute.Login
                                destination = AppDestination.Home
                                homeRoute = HomeRoute.Shell
                                previousHomeRoute = null
                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                        "SlockAuth",
                                        "login_success direct_home=true hasSession=${currentAuthSession != null} serverCount=${currentAuthSession?.servers?.size ?: 0} selectedServer=${currentAuthSession?.currentServerId != null} emailVerified=${currentAuthSession?.user?.emailVerified}"
                                )
                            }
                            AuthResult.Unauthenticated -> {
                                authState = AuthUiState.SignedOut("Session expired. Sign in again.")
                            }
                            is AuthResult.Failure -> {
                                authState = AuthUiState.SignedOut(result.message)
                            }
                        }
                    }
                },
                onSendResetLink = { email ->
                    coroutineScope.launch {
                        forgotPasswordStatus = ForgotPasswordStatus.Sending
                        when (val result = authRepository.forgotPassword(email)) {
                            is AuthResult.Success -> {
                                authState = AuthUiState.SignedOut()
                                forgotPasswordStatus = ForgotPasswordStatus.Success
                            }
                            AuthResult.Unauthenticated -> {
                                authState = AuthUiState.SignedOut("Session expired. Sign in again.")
                                forgotPasswordStatus = ForgotPasswordStatus.Error
                            }
                            is AuthResult.Failure -> {
                                authState = AuthUiState.SignedOut(result.message)
                                forgotPasswordStatus = ForgotPasswordStatus.Error
                            }
                        }
                    }
                },
                onCreateAccount = { name, email, password ->
                    coroutineScope.launch {
                        authRoute = AuthRoute.CreateAccount
                        createAccountStatus = CreateAccountStatus.Saving
                        when (val result = authRepository.register(name, email, password)) {
                            is AuthResult.Success -> {
                                currentAuthSession = authSessionStore.readSession()
                                authState = AuthUiState.Authenticated
                                authRoute = AuthRoute.Login
                                createAccountStatus = CreateAccountStatus.Default
                                forgotPasswordStatus = ForgotPasswordStatus.Default
                                destination = AppDestination.Home
                                homeRoute = HomeRoute.Shell
                                previousHomeRoute = null
                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                        "SlockAuth",
                                        "register_success direct_home=true hasSession=${currentAuthSession != null} serverCount=${currentAuthSession?.servers?.size ?: 0} selectedServer=${currentAuthSession?.currentServerId != null} emailVerified=${currentAuthSession?.user?.emailVerified}"
                                )
                            }
                            AuthResult.Unauthenticated -> {
                                authState = AuthUiState.SignedOut("Session expired. Sign in again.")
                                createAccountStatus = CreateAccountStatus.Error
                            }
                            is AuthResult.Failure -> {
                                authState = AuthUiState.SignedOut(result.message)
                                createAccountStatus = CreateAccountStatus.Error
                            }
                        }
                    }
                },
                authState = authState,
                forgotPasswordStatus = forgotPasswordStatus,
                createAccountStatus = createAccountStatus,
                onAuthProviderUnavailable = { provider ->
                    authState = AuthUiState.SignedOut("$provider sign-in is not connected yet.")
                },
                modifier = modifier
        )
        return
    }

    LaunchedEffect(authState, serverRepository) {
        if (authState is AuthUiState.Authenticated) {
            serverListState = serverRepository.getServers().toScreenLoadState()
            currentAuthSession = authSessionStore.readSession()
            selectedServerOverride = null
            serverGateMode = ServerGateMode.Choose
            serverGateError = null
        }
    }

    val sessionHasSelectedServer = currentAuthSession?.selectedServerId != null
    val requiresServerSelection =
            !sessionHasSelectedServer &&
                    (serverListState is ScreenLoadState.Content ||
                            serverListState is ScreenLoadState.Empty ||
                            serverListState is ScreenLoadState.Error ||
                            serverListState is ScreenLoadState.NoPermission)
    if (requiresServerSelection) {
        ServerGateScreen(
                state = serverListState,
                currentEmail = currentAuthSession?.user?.email.orEmpty(),
                mode = serverGateMode,
                name = createServerName,
                slug = createServerSlug,
                submitting = serverGateSubmitting,
                errorCode = serverGateError,
                onRetry = {
                    coroutineScope.launch {
                        serverGateError = null
                        serverListState = ScreenLoadState.Loading
                        serverListState = serverRepository.getServers().toScreenLoadState()
                        currentAuthSession = authSessionStore.readSession()
                    }
                },
                onSelectServer = { serverId ->
                    coroutineScope.launch {
                        serverGateSubmitting = true
                        serverGateError = null
                        when (val result = serverRepository.selectServer(serverId)) {
                            is DataResult.Content -> enterSelectedServer(result.data)
                            is DataResult.Empty -> {
                                serverGateSubmitting = false
                                serverGateError = result.reason.name
                            }
                            is DataResult.Error -> {
                                serverGateSubmitting = false
                                serverGateError = result.error.code
                            }
                            is DataResult.NoPermission -> {
                                serverGateSubmitting = false
                                serverGateError = result.blocker.code
                            }
                        }
                    }
                },
                onShowCreate = {
                    serverGateMode = ServerGateMode.Create
                    serverGateError = null
                },
                onCancelCreate = {
                    if (!serverGateSubmitting) {
                        serverGateMode = ServerGateMode.Choose
                        serverGateError = null
                    }
                },
                onNameChange = {
                    createServerName = it
                    serverGateError = null
                },
                onSlugChange = {
                    createServerSlug = it.lowercase().replace(Regex("[^a-z0-9-]"), "-")
                    serverGateError = null
                },
                onCreateServer = {
                    val name = createServerName.trim()
                    val slug = createServerSlug.trim()
                    if (name.isBlank()) {
                        serverGateError = "server_name_required"
                        return@ServerGateScreen
                    }
                    coroutineScope.launch {
                        serverGateSubmitting = true
                        serverGateError = null
                        when (val result =
                                        serverRepository.createServer(
                                                CreateServerRequest(
                                                        name = name,
                                                        slug = slug.ifBlank { null }
                                                )
                                        )
                        ) {
                            is DataResult.Content -> {
                                createServerName = ""
                                createServerSlug = ""
                                enterSelectedServer(result.data)
                            }
                            is DataResult.Empty -> {
                                serverGateSubmitting = false
                                serverGateError = result.reason.name
                            }
                            is DataResult.Error -> {
                                serverGateSubmitting = false
                                serverGateError = result.error.code
                            }
                            is DataResult.NoPermission -> {
                                serverGateSubmitting = false
                                serverGateError = result.blocker.code
                            }
                        }
                    }
                },
                onSignOut = { signOutFromAuthenticated("server_gate") },
                modifier = modifier
        )
        return
    }

    LaunchedEffect(authState, appInForeground, authTokenRefresher) {
        if (authState !is AuthUiState.Authenticated || !appInForeground) return@LaunchedEffect
        while (true) {
            authTokenRefresher.refreshIfExpiringSoon(clearOnUnauthorized = false)
            val latestSession = authSessionStore.readSession()
            if (latestSession == null) {
                authState = AuthUiState.SignedOut("Session expired. Sign in again.")
                currentAuthSession = null
                return@LaunchedEffect
            }
            currentAuthSession = latestSession
            delay(30_000)
        }
    }

    val realtimeServerId =
            if (authState is AuthUiState.Authenticated) {
                selectedServerOverride
                        ?: (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.id
                                ?: currentAuthSession?.currentServerId?.let(::ServerId)
            } else {
                null
            }
    val realtimeAuthTokenVersion =
            if (authState is AuthUiState.Authenticated) {
                currentAuthSession?.accessToken?.hashCode()
            } else {
                null
            }

    fun scheduleRealtimeRetry(serverId: ServerId, delayMillis: Long) {
        if (delayMillis <= 0L) return
        realtimeRetryGeneration += 1
        val generation = realtimeRetryGeneration
        realtimeRetryJob?.cancel()
        realtimeRetryJob =
                coroutineScope.launch {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockRealtime",
                            "retry_scheduled serverId=${serverId.value} delayMs=$delayMillis generation=$generation"
                    )
                    delay(delayMillis)
                    if (generation != realtimeRetryGeneration ||
                                    realtimeServerId != serverId ||
                                    !appInForeground
                    ) {
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockRealtime",
                                "retry_skip serverId=${serverId.value} generation=$generation currentGeneration=$realtimeRetryGeneration foreground=$appInForeground currentServer=${realtimeServerId?.value.orEmpty()}"
                        )
                        return@launch
                    }
                    networkDiagnosticsBridge.onConnectRequested(serverId, realtimeLastSeq)
                    val result = realtimeRepository.connect(serverId, realtimeLastSeq)
                    networkDiagnosticsBridge.onRealtimeConnectResult(serverId, result)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockRealtime",
                            "retry_result serverId=${serverId.value} result=${result.realtimeResultName()} delayMs=$delayMillis generation=$generation"
                    )
                }
    }

    fun drainRealtimeRetryIntents(serverId: ServerId) {
        networkDiagnosticsBridge.drainPendingIntents().forEach { intent ->
            if (intent is RealtimeConnectionIntent.Connect) {
                scheduleRealtimeRetry(serverId, intent.delayMillis)
            }
        }
    }

    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer =
                androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                    "SlockUnread",
                                    "lifecycle_resume"
                            )
                            appInForeground = true
                            threadRefreshNonce += 1
                            homeResumeRefreshNonce += 1
                            notificationPermissionGranted = context.hasNotificationPermission()
                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                    "SlockSettingsNotification",
                                    "permission_state_check source=resume granted=$notificationPermissionGranted"
                            )
                        }
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                    "SlockUnread",
                                    "lifecycle_stop"
                            )
                            appInForeground = false
                        }
                        else -> Unit
                    }
                }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager == null) {
            onDispose {}
        } else {
            var lastAvailable = context.hasUsableNetwork()
            networkAvailable = lastAvailable
            fun applyNetworkState(nextAvailable: Boolean, eventName: String) {
                coroutineScope.launch {
                    val resumed = nextAvailable && !lastAvailable
                    if (resumed) {
                        networkResumeNonce += 1
                        homeResumeRefreshNonce += 1
                        threadRefreshNonce += 1
                    }
                    lastAvailable = nextAvailable
                    networkAvailable = nextAvailable
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockNetworkResume",
                            "$eventName usable=$nextAvailable resumed=$resumed nonce=$networkResumeNonce"
                    )
                }
            }
            val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            applyNetworkState(context.hasUsableNetwork(), "network_available")
                        }

                        override fun onLost(network: Network) {
                            applyNetworkState(context.hasUsableNetwork(), "network_lost")
                        }

                        override fun onCapabilitiesChanged(
                                network: Network,
                                networkCapabilities: NetworkCapabilities
                        ) {
                            applyNetworkState(
                                    context.hasUsableNetwork(),
                                    "network_capabilities"
                            )
                        }
                    }
            connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build(),
                    callback
            )
            onDispose { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }

    fun requestNetworkResume(reason: String) {
        if (!networkAvailable || !appInForeground) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockNetworkResume",
                    "skip reason=$reason foreground=$appInForeground network=$networkAvailable"
            )
            return
        }
        realtimeRetryGeneration += 1
        realtimeRetryJob?.cancel()
        realtimeRetryJob = null
        homeResumeRefreshNonce += 1
        threadRefreshNonce += 1
        unreadRefreshNonce += 1
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockNetworkResume",
                "request reason=$reason nonce=$networkResumeNonce lastSeq=$realtimeLastSeq"
        )
    }

    LaunchedEffect(authState, appInForeground, networkAvailable, networkResumeNonce) {
        if (authState !is AuthUiState.Authenticated || networkResumeNonce == 0) {
            return@LaunchedEffect
        }
        requestNetworkResume(reason = "network_available")
    }

    val selectedServerIdForEffects = selectedServerIdForUi()
    val selectedServerIdValueForEffects = selectedServerIdForEffects?.value
    val serverListLoadedForEffects = serverListState is ScreenLoadState.Content

    LaunchedEffect(
            authState,
            realtimeServerId,
            selectedServerIdValueForEffects,
            serverListLoadedForEffects,
            appInForeground,
            unreadRefreshNonce,
            serverUnreadRepository
    ) {
        val serverId = realtimeServerId
        if (serverId == null) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockUnread",
                    "skip_refresh reason=no_selected_server"
            )
            return@LaunchedEffect
        }
        if (authState !is AuthUiState.Authenticated ||
                        !appInForeground ||
                        serverListState !is ScreenLoadState.Content
        ) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockUnread",
                    "skip_refresh serverId=${serverId.value} authenticated=${authState is AuthUiState.Authenticated} foreground=$appInForeground hasServerList=${serverListState is ScreenLoadState.Content}"
            )
            return@LaunchedEffect
        }

        suspend fun refreshUnreadSummary(reason: String) {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockUnread",
                    "fetch_start reason=$reason serverId=${serverId.value}"
            )
            when (val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Background,
                            key = "unread:${serverId.value}",
                            reason = "unread_summary_$reason"
                    ) {
                        serverUnreadRepository.getUnreadSummary(serverId)
                    }) {
                is DataResult.Content -> {
                    serverListState = serverListState.applyUnreadSummary(result.data)
                    if (!publishHomeFromLocal(serverId)) {
                        homeState = homeState.applyUnreadSummary(result.data)
                    }
                    reconcileUnreadCountsFromServer(
                            reason = "server_unread_${reason}",
                            channelId = ChannelId(serverId.value)
                    )
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockUnread",
                            "fetch_success reason=$reason serverId=${serverId.value} total=${result.data.totalUnreadCount} selectedServerUnread=${result.data.selectedServerUnreadCount}"
                    )
                }
                is DataResult.Empty -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockUnread",
                            "fetch_empty reason=$reason serverId=${serverId.value} empty=${result.reason}"
                    )
                }
                is DataResult.Error -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockUnread",
                            "fetch_error reason=$reason serverId=${serverId.value} code=${result.error.code}"
                    )
                }
                is DataResult.NoPermission -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockUnread",
                            "fetch_no_permission reason=$reason serverId=${serverId.value} code=${result.blocker.code}"
                    )
                }
            }
        }

        while (true) {
            delay(30_000)
            refreshUnreadSummary(reason = "interval_30s")
        }
    }

    LaunchedEffect(authState, appInForeground, threadRefreshNonce, conversationRepository) {
        if (authState !is AuthUiState.Authenticated ||
                        !appInForeground ||
                        homeRoute != HomeRoute.Thread
        ) {
            return@LaunchedEffect
        }
        refreshSelectedThread(
                reason = "resume",
                selectedThreadStateProvider = { selectedThreadState },
                selectedThreadIdProvider = { selectedThreadId },
                setSelectedThreadState = { selectedThreadState = it },
                onThreadSnapshot = { result, refreshedThreadId ->
                    localWriteThrough.writeThreadResult(
                            result,
                            source = "refresh_thread",
                            fallbackThreadId = refreshedThreadId,
                            serverId = selectedServerIdForUi()
                    )
                    result.rememberTaskReferences()
                },
                localThreadUiStateProvider = { threadId, fallbackHeader, parentMessage ->
                    localThreadUiState(
                            threadId,
                            fallbackHeader = fallbackHeader,
                            parentMessage = parentMessage
                    )
                },
                transformThreadState = { state -> state.withCachedTaskReferences() },
                conversationRepository = conversationRepository,
                coroutineScope = coroutineScope
        )
    }

    val homeContentForEffects = (homeState.loadState as? ScreenLoadState.Content)?.data
    val homeConversationIdentityKey =
            homeContentForEffects
                    ?.let { content ->
                        (content.channels + content.directMessages)
                                .asSequence()
                                .flatMap { conversation ->
                                    sequenceOf(
                                            conversation.channelId?.value,
                                            conversation.threadId.value,
                                            conversation.thread?.streamChannelId?.value,
                                            conversation.thread?.parentChannelId?.value
                                    )
                                }
                                .filterNotNull()
                                .distinct()
                                .sorted()
                                .joinToString("|")
                    }
                    .orEmpty()
    val joinedRealtimeChannelIds =
            homeContentForEffects
                    ?.let { content ->
                        (content.channels + content.directMessages)
                                .asSequence()
                                .filter { it.joined }
                                .map {
                                    it.channelId ?: it.threadId.parentChannelIdOrNull()
                                            ?: ChannelId(it.threadId.value)
                                }
                                .distinctBy { it.value }
                                .sortedBy { it.value }
                                .toList()
                    }
                    .orEmpty()
    val homeProgressivePrefetchContent = homeContentForEffects
    val homeProgressivePrefetchTargetIds =
            homeProgressivePrefetchContent?.homeProgressivePrefetchTargets()
                    ?.joinToString("|") { it.messageStreamChannelId().value }
                    .orEmpty()

    LaunchedEffect(
            authState,
            appInForeground,
            destination,
            homeProgressivePrefetchContent?.server?.id,
            homeProgressivePrefetchTargetIds,
            selectedThreadId,
            conversationRepository,
            localWriteThrough
    ) {
        if (authState !is AuthUiState.Authenticated || !appInForeground) return@LaunchedEffect
        if (destination != AppDestination.Home) return@LaunchedEffect
        val content = homeProgressivePrefetchContent ?: return@LaunchedEffect
        val serverId = content.server.id.value
        val alreadyPrefetched = homeProgressivePrefetchedThreadIdsByServer[serverId].orEmpty()
        val alreadyInFlight =
                homeProgressivePrefetchInFlightThreadIdsByServer[serverId].orEmpty()
        val targets =
                content
                        .homeProgressivePrefetchTargets()
                        .filter { conversation ->
                            val streamThreadId = conversation.messageStreamChannelId().value
                            streamThreadId !in alreadyPrefetched &&
                                    streamThreadId !in alreadyInFlight &&
                                    streamThreadId != selectedThreadId &&
                                    conversation.joined &&
                                    conversation.permissions.canRead
                        }
        if (targets.isEmpty()) return@LaunchedEffect
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockHomePrefetch",
                "start serverId=$serverId count=${targets.size}"
        )
        delay(HomeProgressivePrefetchInitialDelayMillis)
        targets.forEachIndexed { index, conversation ->
            delay(HomeProgressivePrefetchDelayMillis)
            val targetServerId = ServerId(serverId)
            if (!isCurrentServer(targetServerId)) {
                logStaleServerWrite("home_progressive_prefetch", targetServerId)
                return@forEachIndexed
            }
            val streamThreadId = ThreadId(conversation.messageStreamChannelId().value)
            homeProgressivePrefetchInFlightThreadIdsByServer =
                    homeProgressivePrefetchInFlightThreadIdsByServer + (
                            serverId to
                                    (
                                            homeProgressivePrefetchInFlightThreadIdsByServer[serverId]
                                                    .orEmpty() + streamThreadId.value
                                    )
                            )
            try {
                val result =
                        withSlockRequestPriority(
                                priority = SlockRequestPriority.Background,
                                key = "thread:${streamThreadId.value}",
                                reason = "home_progressive_prefetch"
                        ) {
                            conversationRepository.getThread(streamThreadId)
                        }
                if (!isCurrentServer(targetServerId)) {
                    logStaleServerWrite("home_progressive_prefetch_result", targetServerId)
                    return@forEachIndexed
                }
                localWriteThrough.writeThreadResult(
                        result,
                        source = "home_progressive_prefetch",
                        fallbackThreadId = streamThreadId,
                        fallbackHeader = conversation,
                        serverId = targetServerId
                )
                if (result is DataResult.Content) {
                    homeProgressivePrefetchedThreadIdsByServer =
                            homeProgressivePrefetchedThreadIdsByServer + (
                                    serverId to
                                            (
                                                    homeProgressivePrefetchedThreadIdsByServer[serverId]
                                                            .orEmpty() + streamThreadId.value
                                            )
                                    )
                }
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockHomePrefetch",
                        "result serverId=$serverId index=${index + 1}/${targets.size} threadId=${streamThreadId.value} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
                )
            } finally {
                homeProgressivePrefetchInFlightThreadIdsByServer =
                        homeProgressivePrefetchInFlightThreadIdsByServer + (
                                serverId to
                                        (
                                                homeProgressivePrefetchInFlightThreadIdsByServer[serverId]
                                                        .orEmpty() - streamThreadId.value
                                        )
                                )
            }
        }
    }

    LaunchedEffect(
            realtimeServerId,
            realtimeAuthTokenVersion,
            appInForeground,
            networkAvailable,
            networkResumeNonce,
            realtimeRepository
    ) {
        val serverId = realtimeServerId
        if (serverId == null || !appInForeground || !networkAvailable) {
            realtimeSubscription?.close()
            realtimeSubscription = null
            realtimeRetryGeneration += 1
            realtimeRetryJob?.cancel()
            realtimeRetryJob = null
            realtimeRepository.disconnect()
            networkDiagnosticsBridge.onAppForegroundChanged(
                    inForeground = appInForeground,
                    serverId = serverId
            )
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockRealtime",
                    "disconnect reason=${when {
                        serverId == null -> "no_server"
                        !networkAvailable -> "network_unavailable"
                        else -> "background"
                    }}"
            )
            return@LaunchedEffect
        }
        realtimeSubscription?.close()
        realtimeSubscription =
                realtimeRepository.observeEvents { event ->
                    coroutineScope.launch {
                        if (!isCurrentServer(serverId)) {
                            logStaleServerWrite("realtime_event_prewrite", serverId)
                            return@launch
                        }
                        val previousSeq = realtimeLastSeq
                        realtimeLastSeq = realtimeLastSeq.updateFrom(event)
                        val activeReplyParentMessageId =
                                replyThreadBackStack.lastOrNull()?.parentMessageId
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockRealtime",
                                "event_received type=${event::class.simpleName} selectedThreadId=$selectedThreadId homeRoute=$homeRoute"
                        )
                        localWriteThrough.writeRealtimeEvent(serverId, event)
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockRealtime",
                                "event_written type=${event::class.simpleName}"
                        )
                        if (!isCurrentServer(serverId)) {
                            logStaleServerWrite("realtime_event_postwrite", serverId)
                            return@launch
                        }
                        event.rememberTaskReferences()
                        if (!publishHomeFromLocal(serverId)) {
                            homeState = homeState.applyRealtimeEvent(event)
                        }
                        setInboxState(inboxState.applyRealtimeEvent(event))
                        setSavedState(savedState.applySavedRealtimeEvent(event))
                        setSearchState(
                                searchState.applyRealtimeEvent(event, searchQuery),
                                query = searchQuery
                        )
                        tasksState = tasksState.applyRealtimeEvent(event)
                        replyThreadBackStack =
                                replyThreadBackStack.map { entry ->
                                    val fallbackHeader =
                                            (entry.selectedThreadState.loadState as?
                                                    ScreenLoadState.Content)?.data?.header
                                    val parentMessage =
                                            (entry.selectedThreadState.loadState as?
                                                    ScreenLoadState.Content)?.data?.parentMessage
                                    val rehydrated =
                                            entry.selectedThreadId
                                                    ?.let(::ThreadId)
                                                    ?.let { threadId ->
                                                        observedLocalThreadUiState(
                                                                threadId = threadId,
                                                                current = entry.selectedThreadState,
                                                                fallbackHeader = fallbackHeader,
                                                                parentMessage = parentMessage
                                                        )
                                                    }
                                    entry.copy(
                                            selectedThreadState =
                                                    (rehydrated
                                                            ?: entry.selectedThreadState
                                                                    .applyRealtimeEvent(
                                                                            event = event,
                                                                            selectedThreadId =
                                                                                    entry.selectedThreadId
                                                                    )).withCachedTaskReferences()
                                    )
                                }
                        val selectedThreadFallbackHeader =
                                (selectedThreadState.loadState as? ScreenLoadState.Content)
                                        ?.data?.header
                        val selectedThreadParentMessage =
                                (selectedThreadState.loadState as? ScreenLoadState.Content)
                                        ?.data?.parentMessage
                        val rehydratedSelectedThreadState =
                                selectedThreadId
                                        ?.let(::ThreadId)
                                        ?.let { threadId ->
                                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                                    "SlockRealtime",
                                                    "rehydrate_start threadId=${threadId.value}"
                                            )
                                            val result = observedLocalThreadUiState(
                                                    threadId = threadId,
                                                    current = selectedThreadState,
                                                    fallbackHeader = selectedThreadFallbackHeader,
                                                    parentMessage = selectedThreadParentMessage
                                            )
                                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                                    "SlockRealtime",
                                                    "rehydrate_done threadId=${threadId.value} messages=${(result?.loadState as? ScreenLoadState.Content)?.data?.messages?.size ?: -1}"
                                            )
                                            result
                                        }
                        selectedThreadState =
                                (rehydratedSelectedThreadState
                                        ?: selectedThreadState.applyRealtimeEvent(
                                                event = event,
                                                selectedThreadId = selectedThreadId,
                                                activeReplyParentMessageId =
                                                        activeReplyParentMessageId
                                        )).withCachedTaskReferences()
                        membersState = membersState.applyRealtimeEvent(event)
                        threadMembersPanelState = threadMembersPanelState.applyRealtimeEvent(event)
                        if (event is MessageNew &&
                                        notificationsEnabledByUser &&
                                        lastInboxNotificationMessageId != event.message.id.value &&
                                        shouldShowInboxNotification(
                                                message = event.message,
                                                currentMemberId =
                                                        currentAuthSession
                                                                ?.user
                                                                ?.id
                                                                ?.let(::MemberId),
                                                isAppInForeground = appInForeground,
                                                isVisibleThread =
                                                        selectedThreadContent()?.let { content ->
                                                            event.message.matchesThread(
                                                                    content.header,
                                                                    selectedThreadId,
                                                                    activeReplyParentMessageId
                                                            )
                                                        } == true
                                        )
                        ) {
                            lastInboxNotificationMessageId = event.message.id.value
                            val result =
                                    context.showInboxNotification(
                                            message = event.message,
                                            diagnosticsTag = "realtime_message_new"
                                    )
                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                    "SlockInboxNotification",
                                    "post_result source=realtime_message_new messageId=${event.message.id.value} ${result.toLogFields()} ${context.notificationDiagnostics().toLogFields()} userEnabled=$notificationsEnabledByUser"
                            )
                        }
                        if (event is MemberPresenceUpdated) {
                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                    "SlockPresence",
                                    "merge_result source=realtime memberId=${event.memberId.value} presence=${event.presence} kind=${event.kind?.name.orEmpty()}"
                            )
                        }
                        if (event is DirectMessageCreated) {
                            realtimeRepository.joinChannel(event.channelId)
                            homeResumeRefreshNonce += 1
                        }
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockRealtime",
                                "event type=${event.realtimeLogName()} serverId=${serverId.value} seq=$realtimeLastSeq"
                        )
                        networkDiagnosticsBridge.onEvent(
                                event = event,
                                serverId = serverId,
                                visibleThreadId = selectedThreadId
                        )
                        drainRealtimeRetryIntents(serverId)
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockRealtime",
                                "merge_result type=${event.realtimeLogName()} serverId=${serverId.value} selectedThreadId=${selectedThreadId.orEmpty()} threadContent=${selectedThreadState.loadState is ScreenLoadState.Content}"
                        )
                        selectedThreadState.logVisibleThreadMerge(
                                event,
                                selectedThreadId,
                                activeReplyParentMessageId
                        )
                        if (event is MessageNew &&
                                        homeRoute == HomeRoute.Thread &&
                                        selectedThreadNearBottom
                        ) {
                            val content = selectedThreadContent()
                            if (content != null &&
                                            event.message.matchesThread(
                                                    content.header,
                                                    selectedThreadId
                                            )
                            ) {
                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                        "SlockRead",
                                        "realtime_mark_read_start threadId=${selectedThreadId.orEmpty()} messageId=${event.message.id.value} seq=${event.message.seq ?: 0L}"
                                )
                                markCurrentThreadReadToSeq(
                                        maxOf(
                                                content.latestMessageSeq(),
                                                event.message.seq ?: 0L
                                        )
                                )
                            }
                        }
                        if (event is Heartbeat && event.seq > previousSeq) {
                            val threadId = selectedThreadId?.let(::ThreadId)
                            val threadContent = selectedThreadContent()
                            val syncAfterSeq =
                                    maxOf(previousSeq, threadContent?.latestMessageSeq() ?: 0L)
                            if (threadId != null && event.seq > syncAfterSeq && syncAfterSeq > 0L) {
                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                        "SlockRealtime",
                                        "gap_sync_start threadId=${threadId.value} afterSeq=$syncAfterSeq realtimePreviousSeq=$previousSeq heartbeatSeq=${event.seq} visibleLastSeq=${threadContent?.latestMessageSeq() ?: 0L}"
                                )
                                val newerResult =
                                        conversationRepository.loadNewerMessages(
                                                threadId,
                                                syncAfterSeq
                                        )
                                if (!isCurrentServer(serverId)) {
                                    logStaleServerWrite("gap_sync_result", serverId)
                                    return@launch
                                }
                                localWriteThrough.writeThreadResult(
                                        newerResult,
                                        source = "gap_sync",
                                        fallbackThreadId = threadId,
                                        serverId = serverId
                                )
                                newerResult.rememberTaskReferences()
                                networkDiagnosticsBridge.onSyncResult(
                                        serverId = serverId,
                                        resultLabel = "gap_sync:${newerResult.realtimeResultName()}"
                                )
                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                        "SlockRealtime",
                                        "gap_sync_result threadId=${threadId.value} result=${newerResult.realtimeResultName()}"
                                )
                                if (!uiResultOwner(
                                                source = "gap_sync",
                                                isCurrent = { isCurrentMainThread(threadId) },
                                                staleDetail = {
                                                    "threadId=${threadId.value} selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute"
                                                }
                                        ).isCurrentOrSkip()
                                ) return@launch
                                selectedThreadState =
                                        mergeNewerThreadPage(
                                                current =
                                                        selectedThreadState.withNewerPageStatus(
                                                                MessagePageStatus.Loading
                                                        ),
                                                next = newerResult
                                        ).withCachedTaskReferences()
                            }
                        }
                    }
                }
        networkDiagnosticsBridge.onAppForegroundChanged(inForeground = true, serverId = serverId)
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockNetworkResume",
                "realtime_resume_connect serverId=${serverId.value} nonce=$networkResumeNonce lastSeq=$realtimeLastSeq"
        )
        val connectResult = realtimeRepository.connect(serverId, realtimeLastSeq)
        networkDiagnosticsBridge.onRealtimeConnectResult(serverId, connectResult)
        drainRealtimeRetryIntents(serverId)
        when (connectResult) {
            is DataResult.Content ->
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockRealtime",
                            "connect_success serverId=${serverId.value} lastSeq=$realtimeLastSeq"
                    )
            is DataResult.Empty ->
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockRealtime",
                            "connect_empty serverId=${serverId.value}"
                    )
            is DataResult.Error ->
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockRealtime",
                            "connect_error serverId=${serverId.value}"
                    )
            is DataResult.NoPermission ->
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockRealtime",
                            "connect_no_permission serverId=${serverId.value}"
                    )
        }
    }

    LaunchedEffect(realtimeServerId, appInForeground, networkAvailable) {
        val serverId = realtimeServerId ?: return@LaunchedEffect
        if (!appInForeground || !networkAvailable) return@LaunchedEffect
        while (true) {
            drainRealtimeRetryIntents(serverId)
            delay(1_000)
        }
    }

    DisposableEffect(realtimeRepository) {
        onDispose {
            realtimeRetryGeneration += 1
            realtimeRetryJob?.cancel()
            realtimeRetryJob = null
            realtimeSubscription?.close()
            realtimeSubscription = null
            realtimeRepository.disconnect()
        }
    }

    LaunchedEffect(
            realtimeServerId,
            joinedRealtimeChannelIds,
            networkResumeNonce,
            appInForeground,
            networkAvailable,
            realtimeRepository
    ) {
        if (!appInForeground || !networkAvailable) return@LaunchedEffect
        joinedRealtimeChannelIds.forEach { channelId ->
            realtimeRepository.joinChannel(channelId)
        }
    }

    val selectedThreadJoined = selectedThreadContent()?.header?.joined ?: true
    LaunchedEffect(
            realtimeServerId,
            selectedThreadId,
            selectedThreadJoined,
            networkResumeNonce,
            appInForeground,
            networkAvailable,
            realtimeRepository
    ) {
        val threadId = selectedThreadId ?: return@LaunchedEffect
        if (!appInForeground || !networkAvailable) return@LaunchedEffect
        if (!selectedThreadJoined) return@LaunchedEffect
        realtimeRepository.joinChannel(ChannelId(threadId))
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockRealtime",
                "join_thread_room serverId=${realtimeServerId?.value.orEmpty()} threadId=$threadId"
        )
    }

    LaunchedEffect(
            authState,
            selectedServerIdValueForEffects,
            currentAuthSession?.user?.id
    ) {
        if (authState !is AuthUiState.Authenticated) return@LaunchedEffect
        val serverId =
                selectedServerOverride
                        ?: (serverListState as? ScreenLoadState.Content)
                                ?.data
                                ?.selectedServer
                                ?.id
                        ?: currentAuthSession?.currentServerId?.let(::ServerId)
                        ?: return@LaunchedEffect
        val server = currentHomeServerFallback(serverId) ?: return@LaunchedEffect
        val currentMember = currentHomeMemberFallback()
        localWriteThrough
                .observeHomeSnapshot(
                        serverId = serverId,
                        serverFallback = server,
                        currentMemberFallback = currentMember
                )
                .collectLatest { snapshot ->
                    if (snapshot != null) {
                        homeState =
                                DataResult.Content(snapshot).toHomeUiState(
                                        selectedServerId = serverId.value,
                                        preservedServerUnreadCount =
                                                serverListState.serverUnreadFor(serverId)
                                )
                    }
                }
    }

    LaunchedEffect(
            authState,
            selectedServerIdValueForEffects,
            channelRepository,
            inboxRepository,
            savedRepository,
            searchRepository,
            conversationRepository,
            memberRepository,
            serverUnreadRepository,
            searchQuery,
            homeRefreshRetryNonce,
            homeResumeRefreshNonce,
            networkResumeNonce,
            appInForeground
    ) {
        if (authState is AuthUiState.Authenticated && appInForeground) {
            val selectedServerId =
                    selectedServerOverride
                            ?: (serverListState as? ScreenLoadState.Content)
                                    ?.data
                                    ?.selectedServer
                                    ?.id
                                    ?: currentAuthSession?.currentServerId?.let(::ServerId)
                                    ?: return@LaunchedEffect

            val hasVisibleHomeContent = homeState.loadState is ScreenLoadState.Content
            val isManualRefresh = manualHomeRefreshRequested
            val isSelectedServerRefresh = selectedServerRefreshRequested
            val isResumeRefresh = homeResumeRefreshNonce != handledHomeResumeRefreshNonce
            if (isResumeRefresh) {
                handledHomeResumeRefreshNonce = homeResumeRefreshNonce
            }
            val selectedServer =
                    (serverListState as? ScreenLoadState.Content)
                            ?.data
                            ?.selectedServer
                            ?.takeIf { it.id == selectedServerId }
                            ?: currentAuthSession?.servers
                                    ?.firstOrNull { it.id == selectedServerId.value }
                                    ?.toServerSummary()
            val initialHomeContent =
                    if (!hasVisibleHomeContent) {
                        selectedServer?.let { server ->
                            val currentMember =
                                    currentAuthSession?.user?.toMemberProfile()
                                            ?: ((SlockStateSamples.homeContent.loadState as?
                                                            ScreenLoadState.Content)
                                                    ?.data
                                                    ?.currentMember
                                                    ?: localComposerAuthor)
                            val cachedHome =
                                    localWriteThrough.readHomeSnapshot(
                                            serverId = selectedServerId,
                                            serverFallback = server,
                                            currentMemberFallback = currentMember
                                    )
                            cachedHome?.let { snapshot ->
                                HomeContent(
                                        server = snapshot.server,
                                        currentMember = snapshot.currentMember,
                                        channels = snapshot.channels,
                                        directMessages = snapshot.directMessages,
                                        unreadTotal = snapshot.unreadTotal,
                                        serverUnreadCount = snapshot.server.unreadCount
                                )
                            }
                                    ?: HomeContent(
                                            server = server,
                                            currentMember = currentMember,
                                            channels = emptyList(),
                                            directMessages = emptyList(),
                                            unreadTotal = 0
                                    )
                        }
                    } else {
                        null
                    }
            val refreshSource =
                    when {
                        !hasVisibleHomeContent -> "initial"
                        isManualRefresh -> "pull_refresh"
                        isSelectedServerRefresh -> "server_switch"
                        isResumeRefresh -> "resume"
                        homeRefreshAutoRetryAttempted -> "auto_retry"
                        else -> "background"
                    }
            if (initialHomeContent != null) {
                homeState =
                        HomeUiState(
                                ScreenLoadState.Content(initialHomeContent),
                                selectedServerId = selectedServerId.value
                        )
            }
                val initialHomeHasCachedConversations =
                        initialHomeContent?.let {
                            it.channels.isNotEmpty() || it.directMessages.isNotEmpty()
                        } == true
                val shouldKeepHomeShell = hasVisibleHomeContent || initialHomeContent != null
                val shouldSurfaceHomeRefreshError =
                        isManualRefresh || isSelectedServerRefresh || !shouldKeepHomeShell
                val showRefreshAffordance =
                        shouldKeepHomeShell &&
                                (initialHomeHasCachedConversations ||
                                        !hasVisibleHomeContent ||
                                    isManualRefresh ||
                                    isSelectedServerRefresh)
            if (showRefreshAffordance) {
                homeRefreshInProgress = true
                homeRefreshError = null
                homeRefreshErrorLabel = null
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockHomeRefresh",
                        "fetch_start source=$refreshSource serverId=${selectedServerId.value} retryAttempted=$homeRefreshAutoRetryAttempted affordance=true"
                )
            } else {
                homeRefreshInProgress = false
                if (!shouldKeepHomeShell) {
                    homeState =
                            HomeUiState(
                                    ScreenLoadState.Loading,
                                    selectedServerId = selectedServerId.value
                            )
                }
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockHomeRefresh",
                        "fetch_start source=$refreshSource serverId=${selectedServerId.value} retryAttempted=$homeRefreshAutoRetryAttempted affordance=false"
                )
            }
            membersState = MembersUiState(ScreenLoadState.Loading)
            val previousInboxState = inboxState
            val previousFollowedThreadsState = followedThreadsState
            val previousSavedState = savedState
            val previousHomeState = homeState
            val previousServerListState = serverListState
            val silentInboxRefresh =
                    !showRefreshAffordance &&
                            inboxState.loadState is ScreenLoadState.Content
            val silentFollowedThreadsRefresh =
                    !showRefreshAffordance &&
                            followedThreadsState.loadState is ScreenLoadState.Content
            val silentSavedRefresh =
                    !showRefreshAffordance &&
                            savedState.loadState is ScreenLoadState.Content
            setInboxState(
                    if (silentInboxRefresh) {
                        inboxState.withSilentRefresh(inboxFilter, inboxSnapshot(inboxFilter))
                    } else {
                        inboxState.withBodyLoading(inboxFilter, inboxSnapshot(inboxFilter))
                    }
            )
            setFollowedThreadsState(
                    if (silentFollowedThreadsRefresh) {
                        followedThreadsState.withSilentRefresh(renderCache = followedThreadsSnapshot())
                    } else {
                        followedThreadsState.withBodyLoading(renderCache = followedThreadsSnapshot())
                    }
            )
            setSavedState(
                    if (silentSavedRefresh) {
                        savedState.withSilentRefresh(renderCache = savedSnapshot())
                    } else {
                        savedState.withBodyLoading(renderCache = savedSnapshot())
                    }
            )
            setSearchState(
                    if (searchQuery.isBlank()) {
                        InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
                    } else {
                        if (!showRefreshAffordance &&
                                        searchState.loadState is ScreenLoadState.Content
                        ) {
                            searchState.withSilentRefresh(renderCache = searchSnapshot())
                        } else {
                            searchState.withBodyLoading(renderCache = searchSnapshot())
                        }
                    },
                    query = searchQuery
            )
            val homeRefreshPriority =
                    if (showRefreshAffordance) SlockRequestPriority.Visible
                    else SlockRequestPriority.Background
            coroutineScope {
                val homeD =
                        async {
                            withSlockRequestPriority(
                                    priority = homeRefreshPriority,
                                    key = "home:${selectedServerId.value}",
                                    reason = "home_refresh_$refreshSource"
                            ) {
                                channelRepository
                                        .getHome(selectedServerId)
                                        .retryInitialChannelsIfMissing(
                                                refreshSource = refreshSource,
                                                serverId = selectedServerId,
                                                channelRepository = channelRepository
                                        )
                            }
                        }
                val membersD =
                        async {
                            withSlockRequestPriority(
                                    priority = homeRefreshPriority,
                                    key = "members:${selectedServerId.value}",
                                    reason = "home_refresh_members_$refreshSource"
                            ) {
                                memberRepository.getMembers(homeRefreshPriority, selectedServerId)
                            }
                        }
                val inboxD =
                        async {
                            withSlockRequestPriority(
                                    priority = homeRefreshPriority,
                                    key = "inbox:${selectedServerId.value}:${inboxFilter.name}",
                                    reason = "home_refresh_inbox_$refreshSource"
                            ) {
                                inboxRepository.loadInitial(
                                        serverId = selectedServerId,
                                        filter = inboxFilter.toRepositoryFilter()
                                )
                            }
                        }
                val followedD =
                        async {
                            withSlockRequestPriority(
                                    priority = homeRefreshPriority,
                                    key = "followed:${selectedServerId.value}",
                                    reason = "home_refresh_followed_$refreshSource"
                            ) {
                                conversationRepository.getFollowedThreads(selectedServerId)
                            }
                        }
                val savedD =
                        async {
                            withSlockRequestPriority(
                                    priority = homeRefreshPriority,
                                    key = "saved:${selectedServerId.value}",
                                    reason = "home_refresh_saved_$refreshSource"
                            ) {
                                savedRepository.loadInitial(selectedServerId)
                            }
                        }
                val unreadD =
                        async {
                            withSlockRequestPriority(
                                    priority = SlockRequestPriority.Background,
                                    key = "unread:${selectedServerId.value}",
                                    reason = "home_refresh_unread_$refreshSource"
                            ) {
                                serverUnreadRepository.getUnreadSummary(selectedServerId)
                            }
                        }
                val searchD =
                        if (searchQuery.isBlank()) {
                            null
                        } else {
                            async {
                                withSlockRequestPriority(
                                        priority = homeRefreshPriority,
                                        key = "search:${selectedServerId.value}",
                                        reason = "home_refresh_search_$refreshSource"
                                ) {
                                    searchRepository.search(selectedServerId, searchQuery)
                                }
                            }
                        }

                val homeResult = homeD.await()
                localWriteThrough.writeHomeResult(homeResult, source = "home_refresh_$refreshSource")
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockHomeRefresh",
                        "fetch_result source=$refreshSource serverId=${selectedServerId.value} result=${homeResult.logName()} code=${homeResult.diagnosticCode()} retryAttempted=$homeRefreshAutoRetryAttempted"
                )
                if (homeResult is DataResult.Content || !shouldKeepHomeShell) {
                    val preservedUnread =
                            previousHomeState.serverUnreadFor(selectedServerId)
                                    ?: previousServerListState.serverUnreadFor(selectedServerId)
                    val cachedHome =
                            (homeResult as? DataResult.Content)?.data?.let { snapshot ->
                                localWriteThrough.readHomeSnapshot(
                                        serverId = selectedServerId,
                                        serverFallback = snapshot.server,
                                        currentMemberFallback = snapshot.currentMember
                                ) ?: snapshot
                            }
                    val effectiveHomeResult: DataResult<HomeSnapshot> =
                            cachedHome?.let { DataResult.Content(it) } ?: homeResult
                    val nextHomeState =
                            effectiveHomeResult.toHomeUiState(
                                    selectedServerId = selectedServerId.value,
                                    preservedServerUnreadCount = preservedUnread
                            )
                    val nextUnread = nextHomeState.serverUnreadFor(selectedServerId)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockUnread",
                            "home_refresh_merge source=$refreshSource serverId=${selectedServerId.value} old=${preservedUnread ?: -1} new=${nextUnread ?: -1} result=${homeResult.logName()}"
                    )
                    homeState = nextHomeState
                }
                if (homeResult is DataResult.Content || !shouldKeepHomeShell) {
                    homeRefreshInProgress = false
                    homeRefreshError = null
                    homeRefreshErrorLabel = null
                    homeRefreshAutoRetryAttempted = false
                    manualHomeRefreshRequested = false
                    selectedServerRefreshRequested = false
                } else if (shouldSurfaceHomeRefreshError && !homeRefreshAutoRetryAttempted) {
                    homeRefreshInProgress = isManualRefresh || isSelectedServerRefresh
                    homeRefreshError = null
                    homeRefreshErrorLabel = null
                    homeRefreshAutoRetryAttempted = true
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockHomeRefresh",
                            "silent_retry source=auto serverId=${selectedServerId.value} reason=${homeResult.diagnosticCode()}"
                    )
                    homeRefreshRetryNonce += 1
                } else {
                    homeRefreshInProgress = false
                    manualHomeRefreshRequested = false
                    selectedServerRefreshRequested = false
                    homeRefreshAutoRetryAttempted = false
                    if (shouldSurfaceHomeRefreshError) {
                        homeRefreshError = homeResult.refreshErrorCode()
                        homeRefreshErrorLabel = homeResult.refreshErrorLabel()
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockHomeRefresh",
                                "ui_error source=auto serverId=${selectedServerId.value} result=${homeResult.logName()} code=${homeResult.diagnosticCode()} retryAttempted=true"
                        )
                    } else {
                        homeRefreshError = null
                        homeRefreshErrorLabel = null
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockHomeRefresh",
                                "silent_error source=$refreshSource serverId=${selectedServerId.value} result=${homeResult.logName()} code=${homeResult.diagnosticCode()} cachedShell=true"
                        )
                    }
                }

                val inboxResult = inboxD.await()
                setInboxState(
                        when (inboxResult) {
                            is DataResult.Content -> inboxResult.toInboxUiState(inboxFilter)
                            is DataResult.Empty, is DataResult.Error, is DataResult.NoPermission ->
                                    if (previousInboxState.loadState is ScreenLoadState.Content &&
                                                    previousInboxState.selectedFilter == inboxFilter
                                    ) {
                                        previousInboxState.copy(loadingMore = false)
                                    } else {
                                        inboxResult.toInboxRouteState(inboxFilter)
                                    }
                        }
                )

                val followedThreadsResult = followedD.await()
                setFollowedThreadsState(
                        when (followedThreadsResult) {
                            is DataResult.Content ->
                                    followedThreadsResult.toFollowedThreadsUiState()
                            is DataResult.Empty, is DataResult.Error, is DataResult.NoPermission ->
                                    if (previousFollowedThreadsState.loadState is
                                                    ScreenLoadState.Content
                                    ) {
                                        previousFollowedThreadsState.copy(loadingMore = false)
                                    } else {
                                        followedThreadsResult.toFollowedThreadsRouteState()
                                    }
                        }
                )

                val savedResult = savedD.await()
                setSavedState(
                        when (savedResult) {
                            is DataResult.Content -> savedResult.toSavedInboxUiState()
                            is DataResult.Empty, is DataResult.Error, is DataResult.NoPermission ->
                                    if (previousSavedState.loadState is ScreenLoadState.Content) {
                                        previousSavedState.copy(loadingMore = false)
                                    } else {
                                        savedResult.toSavedRouteState()
                                    }
                        }
                )

                setSearchState(
                        if (searchD == null) {
                            InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
                        } else {
                            when (val searchResult = searchD.await()) {
                            is DataResult.Content -> searchResult.toSearchInboxUiState()
                            is DataResult.Empty, is DataResult.Error, is DataResult.NoPermission ->
                                    if (searchState.loadState is ScreenLoadState.Content) {
                                        searchState.copy(loadingMore = false)
                                    } else {
                                        searchResult.toSearchRouteState(searchQuery)
                                    }
                            }
                        },
                        query = searchQuery
                )

                when (val unreadResult = unreadD.await()) {
                    is DataResult.Content -> {
                        serverListState = serverListState.applyUnreadSummary(unreadResult.data)
                        if (!publishHomeFromLocal(selectedServerId)) {
                            homeState = homeState.applyUnreadSummary(unreadResult.data)
                        }
                        reconcileUnreadCountsFromServer(
                                reason = "home_refresh_$refreshSource",
                                channelId = ChannelId(selectedServerId.value)
                        )
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockUnread",
                                "fetch_success reason=$refreshSource serverId=${selectedServerId.value} total=${unreadResult.data.totalUnreadCount} selectedServerUnread=${unreadResult.data.selectedServerUnreadCount}"
                        )
                    }
                    is DataResult.Empty -> {
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockUnread",
                                "fetch_empty reason=$refreshSource serverId=${selectedServerId.value} empty=${unreadResult.reason}"
                        )
                    }
                    is DataResult.Error -> {
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockUnread",
                                "fetch_error reason=$refreshSource serverId=${selectedServerId.value} code=${unreadResult.error.code}"
                        )
                    }
                    is DataResult.NoPermission -> {
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockUnread",
                                "fetch_no_permission reason=$refreshSource serverId=${selectedServerId.value} code=${unreadResult.blocker.code}"
                        )
                    }
                }

                val membersResult = membersD.await()
                membersState = membersResult.toMembersUiState()
                (membersResult as? DataResult.Content)?.data?.members?.let { members ->
                    if (localWriteThrough.writeAgentDirectMessagePresence(
                                    serverId = selectedServerId,
                                    members = members,
                                    source = "members_presence_home"
                            )
                    ) {
                        publishHomeFromLocal(selectedServerId)
                    }
                }
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockPresence",
                        "members_load_result source=members_page serverId=${selectedServerId.value} result=${membersResult.realtimeResultName()} code=${membersResult.readDiagnosticCode()}"
                )

            }
        }
    }

    LaunchedEffect(
            authState,
            destination,
            tasksFilter,
            selectedServerIdValueForEffects,
            homeConversationIdentityKey,
            localWriteThrough
    ) {
        if (authState !is AuthUiState.Authenticated) return@LaunchedEffect
        if (destination != AppDestination.Tasks) return@LaunchedEffect
        localWriteThrough.observeTaskSnapshot(selectedServerIdForUi()).collectLatest { snapshot ->
            if (snapshot == null) return@collectLatest
            val selectedServerId =
                    selectedServerOverride
                            ?: (serverListState as? ScreenLoadState.Content)
                                    ?.data
                                    ?.selectedServer
                                    ?.id
                            ?: currentAuthSession?.currentServerId?.let(::ServerId)
                            ?: return@collectLatest
            val serverSnapshot =
                    snapshot
                            .filteredForCurrentServerTasks(homeState, selectedServerId)
                            .filteredForTasksFilter(tasksFilter)
            if (serverSnapshot.tasks.isEmpty()) return@collectLatest
            rememberTaskReferences(serverSnapshot.tasks)
            val localState =
                    DataResult.Content(serverSnapshot)
                            .toTasksUiState(tasksFilter)
                            .withCachedTaskReferences(taskReferenceCache)
            tasksState =
                    localState.copy(
                            refreshing =
                                    (tasksState.loadState as? ScreenLoadState.Content) != null &&
                                            tasksState.refreshing,
                            navigationErrorCode = tasksState.navigationErrorCode,
                            statusUpdatingTaskId = tasksState.statusUpdatingTaskId,
                            statusUpdateErrorCode = tasksState.statusUpdateErrorCode
                    )
        }
    }

    LaunchedEffect(
            authState,
            destination,
            selectedServerIdValueForEffects,
            homeConversationIdentityKey,
            tasksFilter,
            tasksRefreshNonce,
            taskRepository
    ) {
        if (authState !is AuthUiState.Authenticated || destination != AppDestination.Tasks) {
            return@LaunchedEffect
        }
        val selectedServerId =
                selectedServerOverride
                        ?: (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.id
                                ?: currentAuthSession?.currentServerId?.let(::ServerId)
                                ?: return@LaunchedEffect
        val refreshKey = "${selectedServerId.value}:${tasksFilter?.name.orEmpty()}:$tasksRefreshNonce"
        val localSnapshot = localWriteThrough.readTaskSnapshot(selectedServerId)
        if (localSnapshot != null && tasksState.loadState !is ScreenLoadState.Content) {
            val serverSnapshot =
                    localSnapshot
                            .filteredForCurrentServerTasks(homeState, selectedServerId)
                            .filteredForTasksFilter(tasksFilter)
            if (serverSnapshot.tasks.isNotEmpty()) {
                tasksState =
                        DataResult.Content(serverSnapshot)
                                .toTasksUiState(tasksFilter)
                                .withCachedTaskReferences(taskReferenceCache)
            }
        }
        if (lastTasksRefreshKey == refreshKey && tasksState.loadState is ScreenLoadState.Content) {
            return@LaunchedEffect
        }
        lastTasksRefreshKey = refreshKey
        val silentRefresh =
                tasksState.loadState is ScreenLoadState.Content ||
                        (nextTasksRefreshSilent && tasksState.loadState is ScreenLoadState.Content)
        nextTasksRefreshSilent = false
        if (silentRefresh) {
            tasksState =
                    tasksState.copy(
                            selectedFilter = tasksFilter,
                            navigationErrorCode = null,
                            refreshing = false
                    )
        } else {
            tasksState = tasksState.withLoading(tasksFilter)
        }
        val result =
                withSlockRequestPriority(
                        priority =
                                if (silentRefresh) SlockRequestPriority.Background
                                else SlockRequestPriority.Visible,
                        key = "tasks:${selectedServerId.value}",
                        reason = "tasks_route_refresh"
                ) {
                    taskRepository.getTasks(
                            serverId = selectedServerId,
                            filter =
                                    TaskFilter(
                                            statuses = tasksFilter?.let { setOf(it) }.orEmpty()
                                    )
                    )
                }
        writeTaskResultQueued(result, source = "tasks_route_refresh", serverId = selectedServerId)
        result.rememberTaskReferences()
        tasksState =
                tasksState.mergeTasksRefreshResult(result, tasksFilter)
                        .withCachedTaskReferences(taskReferenceCache)
    }

    LaunchedEffect(
            authState,
            destination,
            settingsDestination,
            settingsRepository,
            storageManagementRepository
    ) {
        if (authState is AuthUiState.Authenticated && destination == AppDestination.Settings) {
            when (settingsDestination) {
                SettingsDestination.Account -> {
                    settingsAccountState = ScreenLoadState.Loading
                    settingsAccountState = settingsRepository.getAccount().toScreenLoadState()
                }
                SettingsDestination.Computers -> {
                    settingsComputersState = ScreenLoadState.Loading
                    settingsComputersState = settingsRepository.getComputers().toScreenLoadState()
                }
                SettingsDestination.ComputerDetail -> {
                    val machineId = settingsSelectedComputerId
                    if (machineId == null) {
                        settingsComputerDetailState =
                                ScreenLoadState.Empty(EmptyReason.NoSettingsAvailable)
                    } else {
                        settingsComputerDetailState = ScreenLoadState.Loading
                        settingsComputerDetailState =
                                settingsRepository.getComputerDetail(machineId).toScreenLoadState()
                    }
                }
                SettingsDestination.Server -> {
                    settingsServerState = ScreenLoadState.Loading
                    settingsServerActionFeedback = null
                    settingsServerState = settingsRepository.getServerSettings().toScreenLoadState()
                }
                SettingsDestination.Billing -> {
                    settingsBillingState = ScreenLoadState.Loading
                    settingsBillingState = settingsRepository.getBilling().toScreenLoadState()
                }
                SettingsDestination.Mobile -> {
                    settingsMobileState = ScreenLoadState.Loading
                    settingsMobileState = settingsRepository.getMobileSettings().toScreenLoadState()
                }
                SettingsDestination.Storage -> {
                    settingsStorageSnapshot =
                            withContext(Dispatchers.IO) { storageManagementRepository.snapshot() }
                    settingsStorageFeedback = null
                }
                SettingsDestination.Home -> {
                    settingsAccountState = ScreenLoadState.Loading
                    settingsAccountState = settingsRepository.getAccount().toScreenLoadState()
                }
                SettingsDestination.About,
                SettingsDestination.Diagnostics ->
                        Unit
            }
        }
    }

    fun selectedServerIdForHome(): ServerId? =
            selectedServerIdForUi()

    fun clearAllUnreadLocally() {
        val selectedServerId = selectedServerIdForHome()
        if (selectedServerId != null) {
            localWriteThrough.clearAllHomeUnread(selectedServerId, source = "mark_all_optimistic")
        }
        if (selectedServerId == null || !publishHomeFromLocal(selectedServerId)) {
            homeState = homeState.clearAllUnread()
        }
        setInboxState(inboxState.clearAllUnread())
        setFollowedThreadsState(followedThreadsState.clearAllUnread())
        setSavedState(savedState.clearAllUnread())
        setSearchState(searchState.clearAllUnread())
    }

    fun markAllInboxRead() {
        val selectedServerId = selectedServerIdForHome() ?: return
        clearAllUnreadLocally()
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockRead",
                    "mark_inbox_all_start serverId=${selectedServerId.value}"
            )
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "inbox:${selectedServerId.value}:read-all",
                            reason = "mark_inbox_read_all"
                    ) {
                        readReceiptRepository.markInboxReadAll(selectedServerId)
                    }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockRead",
                    "mark_inbox_all_result serverId=${selectedServerId.value} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
            if (result !is DataResult.Content) {
                reconcileAllUnreadCountsFromServer(
                        reason = "mark_inbox_all_failed",
                        serverId = selectedServerId
                )
            }
        }
    }

    fun refreshInboxRoute() {
        val selectedServerId = selectedServerIdForHome() ?: return
        if (inboxRefreshInProgress) return
        inboxRefreshInProgress = true
        setInboxState(inboxState.withSilentRefresh(inboxFilter, inboxSnapshot(inboxFilter)))
        coroutineScope.launch {
            val filter = inboxFilter
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "inbox:${selectedServerId.value}:${filter.name}",
                            reason = "refresh_inbox"
                    ) {
                        inboxRepository.loadInitial(
                                serverId = selectedServerId,
                                filter = filter.toRepositoryFilter()
                        )
                    }
            val currentSelectedServerId = selectedServerIdForHome()
            if (currentSelectedServerId == selectedServerId && inboxFilter == filter) {
                setInboxState(result.toInboxRouteState(filter))
            }
            inboxRefreshInProgress = false
        }
    }

    fun refreshFollowedThreadsRoute() {
        val selectedServerId = selectedServerIdForHome() ?: return
        if (followedThreadsRefreshInProgress) return
        followedThreadsRefreshInProgress = true
        setFollowedThreadsState(
                followedThreadsState.withSilentRefresh(renderCache = followedThreadsSnapshot())
        )
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "followed:${selectedServerId.value}",
                            reason = "refresh_followed_threads"
                    ) {
                        conversationRepository.getFollowedThreads(selectedServerId)
                    }
            if (selectedServerIdForHome() == selectedServerId) {
                setFollowedThreadsState(result.toFollowedThreadsRouteState())
            }
            followedThreadsRefreshInProgress = false
        }
    }

    fun refreshSavedRoute() {
        val selectedServerId = selectedServerIdForHome() ?: return
        if (savedRefreshInProgress) return
        savedRefreshInProgress = true
        setSavedState(savedState.withSilentRefresh(renderCache = savedSnapshot()))
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "saved:${selectedServerId.value}",
                            reason = "refresh_saved"
                    ) {
                        savedRepository.loadInitial(selectedServerId)
                    }
            if (selectedServerIdForHome() == selectedServerId) {
                setSavedState(result.toSavedRouteState())
            }
            savedRefreshInProgress = false
        }
    }

    fun saveMessage(message: Message) {
        val selectedServerId = selectedServerIdForHome() ?: return
        coroutineScope.launch {
            when (val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "message:${message.id.value}:save",
                            reason = "save_message"
                    ) {
                        savedRepository.saveMessage(selectedServerId, message.id)
                    }) {
                is DataResult.Content<*> -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockSaved",
                            "save_success messageId=${message.id.value}"
                    )
                    refreshSavedRoute()
                }
                is DataResult.Error ->
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockSaved",
                                "save_error messageId=${message.id.value} code=${result.error.code}"
                        )
                is DataResult.NoPermission ->
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockSaved",
                                "save_no_permission messageId=${message.id.value} code=${result.blocker.code}"
                        )
                is DataResult.Empty ->
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockSaved",
                                "save_empty messageId=${message.id.value} reason=${result.reason.name}"
                        )
            }
        }
    }

    fun unsaveMessage(item: InboxItem) {
        val selectedServerId = selectedServerIdForHome() ?: return
        val messageId = item.savedMessageIdOrNull() ?: return
        val previousState = savedState
        setSavedState(savedState.removeSavedMessage(messageId))
        coroutineScope.launch {
            when (val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Foreground,
                            key = "message:${messageId.value}:unsave",
                            reason = "unsave_message"
                    ) {
                        savedRepository.unsaveMessage(selectedServerId, messageId)
                    }) {
                is DataResult.Content<*> -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockSaved",
                            "unsave_success messageId=${messageId.value}"
                    )
                }
                is DataResult.Empty, is DataResult.Error, is DataResult.NoPermission -> {
                    setSavedState(previousState)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockSaved",
                            "unsave_failed messageId=${messageId.value} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
                    )
                }
            }
        }
    }

    fun refreshSearchRoute() {
        val selectedServerId = selectedServerIdForHome() ?: return
        val query = searchQuery
        if (query.isBlank() || searchRefreshInProgress) return
        searchRefreshInProgress = true
        setSearchState(searchState.withSilentRefresh(renderCache = searchSnapshot(query)), query)
        coroutineScope.launch {
            val result =
                    withSlockRequestPriority(
                            priority = SlockRequestPriority.Visible,
                            key = "search:${selectedServerId.value}",
                            reason = "refresh_search"
                    ) {
                        searchRepository.search(selectedServerId, query)
                    }
            if (selectedServerIdForHome() == selectedServerId && searchQuery == query) {
                setSearchState(result.toSearchRouteState(query), query)
            }
            searchRefreshInProgress = false
        }
    }

    fun openSlockWebLink(url: String, fromParentPane: Boolean = false): Boolean {
        val target = url.toSlockWebLinkTarget() ?: return false
        val navigationGeneration = nextThreadNavigationGeneration()
        val channelThreadId = ThreadId(target.channelId.value)
        val homeContent = (homeState.loadState as? ScreenLoadState.Content)?.data
        val fallbackHeader =
                homeContent?.findConversation(target.channelId.value)
                        ?: ConversationSummary(
                                threadId = channelThreadId,
                                channelId = target.channelId,
                                kind = ConversationKind.Channel,
                                name = target.channelId.value
                        )
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockWebLink",
                "open_internal url=$url channelId=${target.channelId.value} messageId=${target.messageId?.value.orEmpty()}"
        )
        if (!fromParentPane) {
            replyThreadBackStack = emptyList()
        }
        threadParentJumpTarget = target.messageId?.value
        selectedThreadRetryAction = { openSlockWebLink(url, fromParentPane) }
        composerDrafts =
                composerDrafts.saveComposerDraft(
                        selectedThreadId,
                        composerValue,
                        composerAsTask,
                        pendingAttachmentFiles,
                        failedAttachmentIds
                )
        destination = AppDestination.Home
        previousHomeRoute = if (homeRoute == HomeRoute.Thread) null else homeRoute
        homeRoute = HomeRoute.Thread
        threadTab = ThreadTab.Chat
        selectedThreadId = channelThreadId.value
        selectedThreadState =
                (localThreadUiState(channelThreadId, fallbackHeader = fallbackHeader)
                                ?: ThreadUiState(
                                        ScreenLoadState.Content(
                                                ThreadContent(
                                                        header = fallbackHeader,
                                                        messages = emptyList(),
                                                        highlightedMessageId = target.messageId
                                                )
                                        ),
                                        composerState = fallbackHeader.toComposerState()
                                ))
                        .let { state ->
                            target.messageId?.let { messageId ->
                                state.withHighlightedMessage(messageId)
                            } ?: state
                        }
                        .withBackgroundThreadRefreshIndicator()
        localComposerMessages = emptyList()
        realtimeThreadMessages = emptyList()
        attachmentOpenStates = emptyMap()
        attachmentPreviewState = null
        val draft = composerDrafts[channelThreadId.value]
        composerValue = draft?.value.orEmpty()
        composerAsTask = draft?.asTask ?: false
        pendingAttachmentFiles = draft?.attachments.orEmpty()
        failedAttachmentIds = draft?.failedAttachmentIds.orEmpty()
        coroutineScope.launch {
            val resolved =
                    conversationRepository.resolveThreadOpenTarget(
                            requestedChannelId = target.channelId,
                            messageId = target.messageId
                    )
            cacheOpenedThreadResult(
                    localWriteThrough = localWriteThrough,
                    result = resolved.contextResult,
                    source = "open_slock_web_link",
                    fallbackThreadId = channelThreadId,
                    fallbackHeader = fallbackHeader,
                    serverId = selectedServerIdForUi()
            )
            resolved.contextResult.rememberTaskReferences()
            if (resolved.displayResult !== resolved.contextResult) {
                cacheOpenedThreadResult(
                        localWriteThrough = localWriteThrough,
                        result = resolved.displayResult,
                        source = "open_slock_web_link_display_thread",
                        fallbackThreadId = resolved.openedThreadId,
                        parentMessage = resolved.replyParentMessage,
                        serverId = selectedServerIdForUi()
                )
                resolved.displayResult.rememberTaskReferences()
            }
            val resolvedFallbackHeader =
                    resolved.parentMessage?.toConversationSummary(
                            resolved.parentMessage.channelId?.value?.let {
                                homeContent?.findConversation(it)
                            }
                                    ?: fallbackHeader
                    )
                            ?: resolved.contextSnapshot?.conversation?.withTitleFallback(fallbackHeader)
                            ?: fallbackHeader
            if (!uiResultOwner(
                            source = "open_slock_web_link",
                            isCurrent = {
                                selectedThreadId == channelThreadId.value &&
                                        homeRoute == HomeRoute.Thread &&
                                        isCurrentThreadNavigationGeneration(navigationGeneration)
                            },
                            staleDetail = {
                                "threadId=${channelThreadId.value} generation=$navigationGeneration currentGeneration=$threadNavigationGeneration selectedThreadId=${selectedThreadId.orEmpty()} homeRoute=$homeRoute destination=$destination"
                            }
                    ).isCurrentOrSkip()
            ) return@launch
            if (!fromParentPane) {
                replyThreadBackStack =
                        if (resolved.replyParentMessage != null) {
                            listOf(currentReplyThreadBackStackEntry(resolved.replyParentMessage.id))
                        } else {
                            emptyList()
                        }
            }
            threadParentJumpTarget =
                    if (resolved.replyParentMessage != null) {
                        resolved.replyParentMessage.id.value
                    } else {
                        target.messageId?.value
                    }
            selectedThreadId = resolved.openedThreadId.value
            val loadedState =
                    (localThreadUiState(
                                    resolved.openedThreadId,
                                    fallbackHeader = resolvedFallbackHeader,
                                    parentMessage = resolved.parentMessage,
                                    serverId = selectedServerIdForUi()
                            )
                                    ?: resolved.displayResult.toThreadUiState(
                                            fallbackHeader = resolvedFallbackHeader,
                                            parentMessage = resolved.parentMessage,
                                            serverId = selectedServerIdForUi()
                                    ))
                            .withCachedTaskReferences()
                            .let { state ->
                                target.messageId?.let { messageId ->
                                    state.withHighlightedMessage(messageId)
                                } ?: state
                            }
            selectedThreadState = loadedState
            (resolved.displayResult as? DataResult.Content)?.data?.let { snapshot ->
                selectedThreadRetryAction = null
                markThreadReadIfLoaded(
                        ThreadContent(
                                header = snapshot.conversation.withTitleFallback(fallbackHeader),
                                messages = snapshot.messages,
                                parentMessage = resolved.parentMessage
                        )
                )
            }
        }
        return true
    }

        fun openMarkdownTag(tag: ThreadMarkdownTag, fromParentPane: Boolean = false) {
        fun openTaskReferenceMatches(matches: List<TaskSummary>, label: String, source: String): Boolean {
            return when (matches.size) {
                0 -> false
                1 -> {
                    val task = matches.single()
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockMarkdownTag",
                            "open_task tag=$label taskId=${task.id.value} source=$source"
                    )
                    openTaskContext(task, fromParentPane = fromParentPane)
                    true
                }
                else -> {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockMarkdownTag",
                            "open_task_chooser tag=$label matches=${matches.size} source=$source"
                    )
                    taskReferenceChooserLabel = label
                    taskReferenceChooserIds = matches.map { it.id.value }
                    true
                }
            }
        }
        fun refreshAndOpenTaskReference(label: String) {
            tasksRefreshNonce += 1
            coroutineScope.launch {
                val selectedServerId =
                        selectedServerOverride
                                ?: (serverListState as? ScreenLoadState.Content)
                                        ?.data
                                        ?.selectedServer
                                        ?.id
                                ?: currentAuthSession?.currentServerId?.let(::ServerId)
                                ?: return@launch
                when (
                        val result =
                                withSlockRequestPriority(
                                        priority = SlockRequestPriority.Foreground,
                                        key = "task-reference:$label",
                                        reason = "open_task_reference"
                                ) {
                                    taskRepository.getTasks(
                                            serverId = selectedServerId,
                                            filter = TaskFilter()
                                    )
                                }
                ) {
                    is DataResult.Content -> {
                        writeTaskResultQueued(
                                result,
                                source = "task_reference_refresh",
                                serverId = selectedServerId
                        )
                        tasksState = result.toTasksUiState(tasksFilter).copy(refreshing = false)
                        val refreshedMatches = result.data.tasks.matchingTaskReference(label)
                        if (!openTaskReferenceMatches(refreshedMatches, label, "refresh")) {
                            tasksState =
                                    tasksState.copy(navigationErrorCode = "missing_task_reference")
                        }
                    }
                    is DataResult.Empty -> {
                        tasksState = tasksState.copy(navigationErrorCode = result.reason.name)
                    }
                    is DataResult.Error -> {
                        tasksState = tasksState.copy(navigationErrorCode = result.error.code)
                    }
                    is DataResult.NoPermission -> {
                        tasksState = tasksState.copy(navigationErrorCode = result.blocker.code)
                    }
                }
            }
        }
        when (tag.kind) {
            ThreadMarkdownTagKind.Channel -> {
                val value = tag.label.removePrefix("#")
                val normalized = value.lowercase()
                val content = (homeState.loadState as? ScreenLoadState.Content)?.data
                val conversation =
                        content?.channels?.firstOrNull { channel ->
                            channel.name.equals(value, ignoreCase = true) ||
                                    channel.channelId?.value?.equals(value, ignoreCase = true) ==
                                            true ||
                                    channel.threadId.value.equals(value, ignoreCase = true)
                        }
                if (conversation != null) {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockMarkdownTag",
                            "open_channel tag=${tag.label} threadId=${conversation.threadId.value}"
                    )
                                        if (!fromParentPane) {
                                                previousHomeRoute = HomeRoute.Thread
                                                homeRoute = HomeRoute.Thread
                                        }
                                        openThread(conversation, fromParentPane = fromParentPane)
                } else {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockMarkdownTag",
                            "open_channel_missing tag=${tag.label} normalized=$normalized"
                    )
                    val content = (tasksState.loadState as? ScreenLoadState.Content)?.data
                    val localMatches = content?.tasks?.matchingTaskReference(tag.label).orEmpty()
                    if (!openTaskReferenceMatches(localMatches, tag.label, "channel_fallback")) {
                        refreshAndOpenTaskReference(tag.label)
                    }
                }
            }
            ThreadMarkdownTagKind.Task -> {
                val taskNumber = tag.label.removePrefix("#").toIntOrNull()
                val content = (tasksState.loadState as? ScreenLoadState.Content)?.data
                val localMatches = content?.tasks?.matchingTaskReference(tag.label).orEmpty()
                if (openTaskReferenceMatches(localMatches, tag.label, "local")) {
                    Unit
                } else {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockMarkdownTag",
                            "open_task_missing tag=${tag.label} taskNumber=$taskNumber"
                    )
                    if (taskNumber != null) {
                        refreshAndOpenTaskReference(tag.label)
                    }
                }
            }
            ThreadMarkdownTagKind.UserMention -> {
                val handle = tag.label.removePrefix("@")
                val membersContent = (membersState.loadState as? ScreenLoadState.Content)?.data
                val member =
                        membersContent?.members?.firstOrNull {
                            it.handle.equals(handle, ignoreCase = true) ||
                                    it.displayName.equals(handle, ignoreCase = true)
                        }
                if (member != null) {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockMarkdownTag",
                            "open_member tag=${tag.label} memberId=${member.id.value}"
                    )
                    openMemberProfile(member)
                } else {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockMarkdownTag",
                            "open_member_missing tag=${tag.label} handle=$handle"
                    )
                }
            }
        }
    }

    val taskReferenceChooserTasks =
            remember(taskReferenceChooserIds, tasksState) {
                val ids = taskReferenceChooserIds.toSet()
                val content = (tasksState.loadState as? ScreenLoadState.Content)?.data
                content?.tasks
                        ?.filter { it.id.value in ids }
                        ?.sortedWith(
                                compareBy<TaskSummary> { it.taskNumber ?: Int.MAX_VALUE }
                                        .thenBy { it.title.lowercase() }
                        )
                        .orEmpty()
            }
    val homeNotifications =
            remember(homeComputersState, dismissedHomeNotificationIds) {
                (homeComputersState as? ScreenLoadState.Content)
                        ?.data
                        ?.let { snapshot ->
                            settingsComputersToHomeNotificationItems(
                                    snapshot,
                                    dismissedHomeNotificationIds.toSet()
                            )
                        }
                        .orEmpty()
            }

    SlockAdaptiveAppScaffold(
            selectedKey = destination.key,
            items = appBottomTabs(),
            onSelected = { key ->
                val nextDestination = AppDestination.entries.first { it.key == key }
                if (nextDestination == AppDestination.Home &&
                                (destination != AppDestination.Home ||
                                        homeRoute != HomeRoute.Shell)
                ) {
                    homeResumeRefreshNonce += 1
                }
                destination = nextDestination
                homeRoute = HomeRoute.Shell
                previousHomeRoute = null
                membersDetailVisible = false
            },
            showNavigation = showNavigation,
            statusBarColor = statusBarColor,
            largeScreenNavigationStyle =
                    chatLayoutPreferenceSnapshot.largeScreenNavigation
                            .toSlockLargeScreenNavigationStyle(),
            modifier =
                    modifier.background(
                            if (destination == AppDestination.Settings) {
                                SlockColor.BrandYellow
                            } else {
                                SlockColor.AppBgLightBlue
                            }
                    ),
            statusBarContent = { layoutInfo ->
                if (destination == AppDestination.Members &&
                                (membersDetailVisible || membersDetailSelectedMemberId != null) &&
                                layoutInfo.mode != SlockWindowLayoutMode.CompactSinglePane
                ) {
                    MembersWideStatusBar()
                } else {
                    Box(
                            modifier =
                                    Modifier
                                            .fillMaxWidth()
                                            .windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility)
                                            .background(statusBarColor)
                    )
                }
            }
    ) { adaptiveLayoutInfo ->
                    when (destination) {
                    AppDestination.Home ->
                            HomeRouteScreen(
                                    route = homeRoute,
                                    previousRoute = previousHomeRoute,
                                    onRouteChange = { route ->
                                        if (route == HomeRoute.Shell && homeRoute != HomeRoute.Shell) {
                                            homeResumeRefreshNonce += 1
                                        }
                                        previousHomeRoute = null
                                        homeRoute = route
                                    },
                                    onOpenThreadFromRoute = { route ->
                                        previousHomeRoute = route
                                        homeRoute = HomeRoute.Thread
                                    },
                                    onOpenConversationThread = { conversation, route ->
                                        previousHomeRoute = route
                                        homeRoute = HomeRoute.Thread
                                        openThread(conversation)
                                    },
                                    onOpenThreadItem = { item -> openThreadItem(item) },
                                    onOpenMessageThread = { message -> openMessageThread(message) },
                                    onOpenParentPaneMessageThread = { message ->
                                        openMessageThread(message, fromParentPane = true)
                                    },
                                    onOpenMemberProfile = { member -> openMemberProfile(member) },
                                    onOpenMarkdownTag = { tag -> openMarkdownTag(tag) },
                                    onOpenSlockWebLink = { url -> openSlockWebLink(url) },
                                                                        onOpenParentPaneMarkdownTag = { tag ->
                                                                                openMarkdownTag(tag, fromParentPane = true)
                                                                        },
                                                                        onOpenParentPaneSlockWebLink = { url ->
                                                                                openSlockWebLink(url, fromParentPane = true)
                                                                        },
                                    onOpenTask = { task -> openTaskContext(task) },
                                                                        onOpenParentPaneTask = { task ->
                                                                                openTaskContext(task, fromParentPane = true)
                                                                        },
                                    onTaskStatusChange = ::updateTaskStatus,
                                    onRetryThreadTasks = {
                                        refreshSelectedThreadTasks(reason = "retry")
                                    },
                                    onRetryThreadFiles = {
                                        refreshSelectedThreadFiles(reason = "retry")
                                    },
                                    onLoadThreadFilesMore = { cursor ->
                                        refreshSelectedThreadFiles(
                                                reason = "load_more",
                                                cursor = cursor
                                        )
                                    },
                                    onThreadBack = {
                                        restoreReplyThreadParent() || returnFromThread()
                                    },
                                    onJoinChannel = { conversation ->
                                        joinVisibleChannel(conversation)
                                    },
                                    onRetryFollowedThreads = {
                                        refreshFollowedThreadsRoute()
                                    },
                                     threadTab = threadTab,
                                     onThreadTabChange = { tab ->
                                         val previousTab = threadTab
                                         threadTab = tab
                                         if (tab != previousTab) {
                                             when (tab) {
                                                 ThreadTab.Tasks ->
                                                         refreshSelectedThreadTasks(
                                                                 reason = "tab_selected"
                                                         )
                                                 ThreadTab.Files ->
                                                         refreshSelectedThreadFiles(
                                                                 reason = "tab_selected"
                                                         )
                                                 ThreadTab.Chat -> Unit
                                             }
                                         }
                                     },
                                    composerValue = composerValue,
                                    onComposerValueChange = {
                                        composerValue = it
                                        if (selectedThreadState.composerState.status ==
                                                        ComposerStatus.Failed ||
                                                        selectedThreadState.composerState.status ==
                                                                ComposerStatus.NoPermission
                                        ) {
                                            selectedThreadState =
                                                    selectedThreadState.withComposerStatus(
                                                            status =
                                                                    ComposerStatus.IdleEmpty
                                                                            .fromComposerValue(
                                                                                    value = it,
                                                                                    hasAttachments =
                                                                                            pendingAttachmentFiles
                                                                                                    .isNotEmpty()
                                                                            )
                                                    )
                                        }
                                    },
                                    composerAsTask = composerAsTask,
                                    onComposerAsTaskChange = { composerAsTask = it },
                                    pendingAttachmentCount = pendingAttachmentFiles.size,
                                    pendingAttachments =
                                            pendingAttachmentFiles.toPendingAttachments(
                                                    status =
                                                            when (selectedThreadState
                                                                            .composerState
                                                                            .status
                                                            ) {
                                                                ComposerStatus.Uploading ->
                                                                        PendingAttachmentStatus
                                                                                .Uploading
                                                                else ->
                                                                        PendingAttachmentStatus
                                                                                .Ready
                                                            },
                                                    failedIds = failedAttachmentIds
                                            ),
                                    onAttachImage = {
                                        activityActions.pickAttachmentImage { uris ->
                                            if (uris.isNotEmpty()) {
                                                val newFiles = uris.toAttachmentUploadFiles(context)
                                                pendingAttachmentFiles =
                                                        (pendingAttachmentFiles + newFiles)
                                                                .distinctBy { it.id }
                                                failedAttachmentIds =
                                                        failedAttachmentIds -
                                                                newFiles.map { it.id }.toSet()
                                                composerFocusRequestNonce += 1
                                            }
                                        }
                                    },
                                    onAttachFile = {
                                        activityActions.pickAttachmentFile { uris ->
                                            if (uris.isNotEmpty()) {
                                                val newFiles = uris.toAttachmentUploadFiles(context)
                                                pendingAttachmentFiles =
                                                        (pendingAttachmentFiles + newFiles)
                                                                .distinctBy { it.id }
                                                failedAttachmentIds =
                                                        failedAttachmentIds -
                                                                newFiles.map { it.id }.toSet()
                                                composerFocusRequestNonce += 1
                                            }
                                        }
                                    },
                                    onRemoveAttachment = { attachmentId ->
                                        pendingAttachmentFiles =
                                                pendingAttachmentFiles.filterNot {
                                                    it.id == attachmentId
                                                }
                                        failedAttachmentIds = failedAttachmentIds - attachmentId
                                        selectedThreadState =
                                                selectedThreadState.withComposerStatus(
                                                        status =
                                                                ComposerStatus.IdleEmpty
                                                                        .fromComposerValue(
                                                                                value =
                                                                                        composerValue,
                                                                                hasAttachments =
                                                                                        pendingAttachmentFiles
                                                                                                .any {
                                                                                                    it.id !=
                                                                                                            attachmentId
                                                                                                }
                                                                        )
                                                )
                                    },
                                    onRetryAttachments = {
                                        failedAttachmentIds = emptySet()
                                        selectedThreadState =
                                                selectedThreadState.withComposerStatus(
                                                        status =
                                                                ComposerStatus.IdleEmpty
                                                                        .fromComposerValue(
                                                                                value =
                                                                                        composerValue,
                                                                                hasAttachments =
                                                                                        pendingAttachmentFiles
                                                                                                .isNotEmpty()
                                                                        )
                                                )
                                    },
                                    localComposerMessages = localComposerMessages,
                                    realtimeThreadMessages = realtimeThreadMessages,
                                    optimisticSendMessagesByStream = optimisticSendMessagesByStream,
                                    serverListState = serverListState,
                                    homeState = homeState,
                                    draftThreadIds = composerDrafts.keys,
                                    inboxState = inboxState,
                                    followedThreadsState = followedThreadsState,
                                    selectedThreadState = selectedThreadState,
                                    selectedThreadId = selectedThreadId,
                                    composerFocusRequestNonce = composerFocusRequestNonce,
                                    replyThreadBackStack = replyThreadBackStack,
                                    replyHeaderContext =
                                            replyThreadBackStack
                                                    .lastOrNull()
                                                    ?.toThreadReplyHeaderContext()
                                                    ?: threadParentJumpTarget?.let { parentId ->
                                                        selectedThreadState
                                                                .toThreadReplyHeaderContext(
                                                                        parentMessageId = MessageId(parentId),
                                                                        refreshing =
                                                                                refreshingOriginParentIds
                                                                                        .contains(parentId),
                                                                        refreshErrorCode =
                                                                                originParentRefreshErrors[
                                                                                        parentId
                                                                                ]
                                                                )
                                                    },
                                    parentJumpTarget =
                                            threadParentJumpTarget?.let {
                                                ThreadParentJumpTarget(MessageId(it))
                                            },
                                    onOpenParentMessage = ::openThreadParentMessage,
                                    onRefreshThread = {
                                        refreshSelectedThread(
                                                reason = "retry",
                                                selectedThreadStateProvider = {
                                                    selectedThreadState
                                                },
                                                selectedThreadIdProvider = { selectedThreadId },
                                                setSelectedThreadState = {
                                                    selectedThreadState = it
                                                },
                                                onThreadSnapshot = { result, refreshedThreadId ->
                                                    localWriteThrough.writeThreadResult(
                                                            result,
                                                            source = "refresh_thread",
                                                            fallbackThreadId = refreshedThreadId,
                                                            serverId = selectedServerIdForUi()
                                                    )
                                                    result.rememberTaskReferences()
                                                },
                                                localThreadUiStateProvider = {
                                                        threadId,
                                                        fallbackHeader,
                                                        parentMessage ->
                                                    localThreadUiState(
                                                            threadId,
                                                            fallbackHeader = fallbackHeader,
                                                            parentMessage = parentMessage
                                                    )
                                                },
                                                transformThreadState = { state ->
                                                    state.withCachedTaskReferences()
                                                },
                                                conversationRepository = conversationRepository,
                                                coroutineScope = coroutineScope
                                        )
                                    },
                                    onRetryThreadLoad = { selectedThreadRetryAction?.invoke() },
                                    threadMembersPanelState = threadMembersPanelState,
                                    archiveChannelStatus = archiveChannelStatus,
                                    onArchiveChannel = { channelId ->
                                        archiveSelectedThreadChannel(channelId)
                                    },
                                    onDeleteChannel = { channelId ->
                                        deleteSelectedThreadChannel(channelId)
                                    },
                                    onLeaveChannel = { channelId ->
                                        leaveSelectedThreadChannel(channelId)
                                    },
                                    onAddChannelMember = ::addSelectedThreadMember,
                                    onRemoveChannelMember = ::removeSelectedThreadMember,
                                    attachmentOpenStates = attachmentOpenStates,
                                    attachmentPreviewState = attachmentPreviewState,
                                    onThreadNearBottomChange = { isNearBottom ->
                                        selectedThreadNearBottom = isNearBottom
                                        if (isNearBottom) {
                                            selectedThreadContent()?.let(::markThreadReadIfLoaded)
                                        }
                                    },
                                    onOpenAttachment = { openAttachment(it) },
                                    onDismissAttachmentPreview = ::dismissAttachmentPreview,
                                    onOpenAttachmentPreviewExternally = { preview ->
                                        val url = preview.url
                                        if (url.isNullOrBlank()) {
                                            attachmentPreviewState =
                                                    preview.copy(
                                                            status = AttachmentPreviewStatus.Error,
                                                            errorCode = "missing_url"
                                                    )
                                        } else {
                                            val opened =
                                                    activityActions.openAttachmentUri(
                                                            url,
                                                            preview.mimeType
                                                    )
                                            attachmentPreviewState =
                                                    if (opened) {
                                                        null
                                                    } else {
                                                        preview.copy(
                                                                status =
                                                                        AttachmentPreviewStatus
                                                                                .Error,
                                                                errorCode = "no_handler"
                                                        )
                                                    }
                                            ai.slock.android.core.data.diagnostics
                                                    .SlockDiagnosticsLogger.d(
                                                    "SlockAttachment",
                                                    "preview_external attachmentId=${preview.attachmentId?.value.orEmpty()} result=${if (opened) "opened" else "no_handler"}"
                                            )
                                        }
                                    },
                                    onSendParentPaneMessage = { messageStreamChannelId, body, asTask, onResult ->
                                        coroutineScope.launch {
                                            val result =
                                                    withSlockRequestPriority(
                                                            priority = SlockRequestPriority.Foreground,
                                                            key = "send:${messageStreamChannelId.value}",
                                                            reason = "send_parent_pane_message"
                                                    ) {
                                                        conversationRepository.sendMessage(
                                                                channelId = messageStreamChannelId,
                                                                body = body,
                                                                asTask = asTask
                                                        )
                                                    }
                                            localWriteThrough.writeSendResult(
                                                    result,
                                                    source = "send_parent_reply_split",
                                                    serverId = selectedServerIdForUi()
                                            )
                                            onResult(result)
                                        }
                                    },
                                     onParentPaneTabChange = { tab ->
                                         val previousTab =
                                                 replyThreadBackStack.lastOrNull()?.selectedTab
                                                         ?: ThreadTab.Chat
                                         updateReplyThreadBackStackEntry { entry ->
                                             entry.copy(selectedTab = tab)
                                         }
                                         if (tab != previousTab) {
                                             when (tab) {
                                                 ThreadTab.Tasks ->
                                                         refreshParentPaneTasks(
                                                                 reason = "tab_selected"
                                                         )
                                                 ThreadTab.Files ->
                                                         refreshParentPaneFiles(
                                                                 reason = "tab_selected"
                                                         )
                                                 ThreadTab.Chat -> Unit
                                             }
                                         }
                                     },
                                    onParentPaneComposerChange = { value ->
                                        updateReplyThreadBackStackEntry { entry ->
                                            entry.copy(composerValue = value)
                                        }
                                    },
                                    onParentPaneAsTaskChange = { asTask ->
                                        updateReplyThreadBackStackEntry { entry ->
                                            entry.copy(composerAsTask = asTask)
                                        }
                                    },
                                    onParentPaneAttachImage = {
                                        activityActions.pickAttachmentImage { uris ->
                                            if (uris.isNotEmpty()) {
                                                val newFiles = uris.toAttachmentUploadFiles(context)
                                                updateReplyThreadBackStackEntry { entry ->
                                                    entry.copy(
                                                            pendingAttachmentFiles =
                                                                    (entry.pendingAttachmentFiles +
                                                                                    newFiles)
                                                                            .distinctBy { it.id },
                                                            failedAttachmentIds =
                                                                    entry.failedAttachmentIds -
                                                                            newFiles
                                                                                    .map { it.id }
                                                                                    .toSet()
                                                    )
                                                }
                                                composerFocusRequestNonce += 1
                                            }
                                        }
                                    },
                                    onParentPaneAttachFile = {
                                        activityActions.pickAttachmentFile { uris ->
                                            if (uris.isNotEmpty()) {
                                                val newFiles = uris.toAttachmentUploadFiles(context)
                                                updateReplyThreadBackStackEntry { entry ->
                                                    entry.copy(
                                                            pendingAttachmentFiles =
                                                                    (entry.pendingAttachmentFiles +
                                                                                    newFiles)
                                                                            .distinctBy { it.id },
                                                            failedAttachmentIds =
                                                                    entry.failedAttachmentIds -
                                                                            newFiles
                                                                                    .map { it.id }
                                                                                    .toSet()
                                                    )
                                                }
                                                composerFocusRequestNonce += 1
                                            }
                                        }
                                    },
                                    onParentPaneRemoveAttachment = { attachmentId ->
                                        updateReplyThreadBackStackEntry { entry ->
                                            val remainingAttachments =
                                                    entry.pendingAttachmentFiles.filterNot {
                                                        it.id == attachmentId
                                                    }
                                            entry.copy(
                                                    selectedThreadState =
                                                            entry.selectedThreadState
                                                                    .withComposerStatus(
                                                                            status =
                                                                                    ComposerStatus
                                                                                            .IdleEmpty
                                                                                            .fromComposerValue(
                                                                                                    value =
                                                                                                            entry.composerValue,
                                                                                                    hasAttachments =
                                                                                                            remainingAttachments
                                                                                                                    .isNotEmpty()
                                                                                            )
                                                                    ),
                                                    pendingAttachmentFiles =
                                                            remainingAttachments,
                                                    failedAttachmentIds =
                                                            entry.failedAttachmentIds -
                                                                    attachmentId
                                            )
                                        }
                                    },
                                    onParentPaneRetryAttachment = {
                                        updateReplyThreadBackStackEntry { entry ->
                                            entry.copy(
                                                    selectedThreadState =
                                                            entry.selectedThreadState
                                                                    .withComposerStatus(
                                                                            status =
                                                                                    ComposerStatus
                                                                                            .IdleEmpty
                                                                                            .fromComposerValue(
                                                                                                    value =
                                                                                                            entry.composerValue,
                                                                                                    hasAttachments =
                                                                                                            entry.pendingAttachmentFiles
                                                                                                                    .isNotEmpty()
                                                                                            )
                                                                    ),
                                                    failedAttachmentIds = emptySet()
                                            )
                                        }
                                    },
                                    onParentPaneSend = ::sendParentPaneMessage,
                                    onParentPaneLoadOlderMessages = ::loadOlderParentPaneThreadMessages,
                                    onParentPaneRetryLoadOlderMessages = {
                                        loadOlderParentPaneThreadMessages()
                                    },
                                    onParentPaneLoadNewerMessages = ::loadNewerParentPaneThreadMessages,
                                    onParentPaneRetryLoadNewerMessages = {
                                        loadNewerParentPaneThreadMessages()
                                    },
                                    onParentPaneRefreshThread = {
                                        refreshParentPaneThread(reason = "retry")
                                    },
                                    onParentPaneNearBottomChange = { isNearBottom ->
                                        if (isNearBottom) {
                                            replyThreadBackStack
                                                    .lastOrNull()
                                                    ?.selectedThreadState
                                                    ?.selectedThreadContent()
                                                    ?.let(::markThreadReadIfLoaded)
                                        }
                                    },
                                    onRetryParentPaneTasks = {
                                        refreshParentPaneTasks(reason = "retry")
                                    },
                                    onRetryParentPaneFiles = {
                                        refreshParentPaneFiles(reason = "retry")
                                    },
                                    onLoadParentPaneFilesMore = { cursor ->
                                        refreshParentPaneFiles(
                                                reason = "load_more",
                                                cursor = cursor
                                        )
                                    },
                                    onRetryParentPaneThreadLoad = {
                                        refreshParentPaneThread(reason = "retry_load")
                                    },
                                    savedState = savedState,
                                    homeNotifications = homeNotifications,
                                    onHomeNotificationView = { notification ->
                                        settingsSelectedComputerId = notification.targetId
                                        settingsDestination =
                                                if (notification.targetId == null) {
                                                    SettingsDestination.Computers
                                                } else {
                                                    SettingsDestination.ComputerDetail
                                                }
                                        destination = AppDestination.Settings
                                        membersDetailVisible = false
                                        membersDetailSelectedMemberId = null
                                    },
                                    onHomeNotificationDismiss = { notification ->
                                        dismissedHomeNotificationIds =
                                                (dismissedHomeNotificationIds + notification.id)
                                                        .distinct()
                                    },
                                    searchState = searchState,
                                    searchQuery = searchQuery,
                                    onSearchQueryChange = { searchQuery = it },
                                    homeRefreshInProgress = homeRefreshInProgress,
                                    homeRefreshError = homeRefreshError,
                                    homeRefreshErrorLabel = homeRefreshErrorLabel,
                                    inboxRefreshInProgress = inboxRefreshInProgress,
                                    followedThreadsRefreshInProgress = followedThreadsRefreshInProgress,
                                    savedRefreshInProgress = savedRefreshInProgress,
                                    searchRefreshInProgress = searchRefreshInProgress,
                                    onHomeRefresh = {
                                        homeRefreshError = null
                                        homeRefreshErrorLabel = null
                                        homeRefreshAutoRetryAttempted = false
                                        manualHomeRefreshRequested = true
                                        selectedServerRefreshRequested = false
                                        if (!homeRefreshInProgress) {
                                            homeRefreshInProgress = true
                                            ai.slock.android.core.data.diagnostics
                                                    .SlockDiagnosticsLogger.d(
                                                    "SlockHomeRefresh",
                                                    "manual_refresh_start serverId=${selectedServerOverride?.value ?: currentAuthSession?.currentServerId.orEmpty()}"
                                            )
                                            homeRefreshRetryNonce += 1
                                        }
                                    },
                                    serverSwitcherExpanded = serverSwitcherExpanded,
                                    onServerSwitcherExpandedChange = {
                                        serverSwitcherExpanded = it
                                    },
                                    onServerSelected = { id ->
                                        serverSwitcherExpanded = false
                                        coroutineScope.launch {
                                            val serverId = ServerId(id)
                                            selectedServerOverride = serverId
                                            serverListState =
                                                    serverListState.selectServerOptimistically(
                                                            serverId
                                                    )
                                            homeRefreshError = null
                                            homeRefreshErrorLabel = null
                                            homeRefreshAutoRetryAttempted = false
                                            manualHomeRefreshRequested = false
                                            selectedServerRefreshRequested = true
                                            ai.slock.android.core.data.diagnostics
                                                    .SlockDiagnosticsLogger.d(
                                                    "SlockHomeRefresh",
                                                    "server_switch_start serverId=${serverId.value}"
                                            )
                                            if (homeState.loadState is ScreenLoadState.Content) {
                                                homeRefreshInProgress = true
                                            } else {
                                                homeState =
                                                        HomeUiState(
                                                                ScreenLoadState.Loading,
                                                                selectedServerId = id
                                                        )
                                            }
                                            currentAuthSession = authSessionStore.readSession()
                                            clearInboxRouteCachesForServerSwitch()
                                            setInboxState(inboxState.withBodyLoading(inboxFilter))
                                            setFollowedThreadsState(
                                                    followedThreadsState.withBodyLoading()
                                            )
                                            setSavedState(savedState.withBodyLoading())
                                            setSearchState(
                                                    if (searchQuery.isBlank()) {
                                                        InboxUiState(
                                                                ScreenLoadState.Empty(
                                                                        EmptyReason.NoSearchResults
                                                                )
                                                        )
                                                    } else {
                                                        searchState.withBodyLoading()
                                                    },
                                                    query = searchQuery
                                            )
                                            val previousServerListState = serverListState
                                            val selectedServerListState =
                                                    serverRepository
                                                            .selectServer(serverId)
                                                            .toScreenLoadState()
                                                            .withPreservedUnreadCounts(
                                                                    previousServerListState
                                                            )
                                            val oldUnread =
                                                    previousServerListState.serverUnreadFor(
                                                            serverId
                                                    )
                                            val newUnread =
                                                    selectedServerListState.serverUnreadFor(
                                                            serverId
                                                    )
                                            serverListState = selectedServerListState
                                            currentAuthSession = authSessionStore.readSession()
                                            ai.slock.android.core.data.diagnostics
                                                    .SlockDiagnosticsLogger.d(
                                                    "SlockHomeRefresh",
                                                    "server_switch_result serverId=${serverId.value} result=${selectedServerListState.logName()} code=${selectedServerListState.diagnosticCode()}"
                                            )
                                            ai.slock.android.core.data.diagnostics
                                                    .SlockDiagnosticsLogger.d(
                                                    "SlockUnread",
                                                    "server_switch_merge source=switch serverId=${serverId.value} old=${oldUnread ?: -1} new=${newUnread ?: -1} result=${selectedServerListState.logName()}"
                                            )
                                            if (selectedServerListState !is ScreenLoadState.Content
                                            ) {
                                                homeRefreshInProgress = false
                                                selectedServerRefreshRequested = false
                                                homeRefreshError =
                                                        selectedServerListState.refreshErrorCode()
                                                homeRefreshErrorLabel =
                                                        selectedServerListState.refreshErrorLabel()
                                                ai.slock.android.core.data.diagnostics
                                                        .SlockDiagnosticsLogger.w(
                                                        "SlockHomeRefresh",
                                                        "ui_error source=server_switch serverId=${serverId.value} result=${selectedServerListState.logName()} code=${selectedServerListState.diagnosticCode()} retryAttempted=false"
                                                )
                                            }
                                        }
                                    },
                                    onCreateServerClick = {
                                        serverSwitcherExpanded = false
                                        createServerVisible = true
                                    },
                                    onCreateChannelClick = {
                                        serverSwitcherExpanded = false
                                        createChannelVisible = true
                                        createChannelError = null
                                        val selectedServerId =
                                                selectedServerOverride
                                                        ?: (serverListState as? ScreenLoadState.Content)
                                                                ?.data
                                                                ?.selectedServer
                                                                ?.id
                                                        ?: currentAuthSession
                                                                ?.currentServerId
                                                                ?.let(::ServerId)
                                        if (selectedServerId != null) {
                                            membersState =
                                                    MembersUiState(ScreenLoadState.Loading)
                                            coroutineScope.launch {
                                                membersState =
                                                        memberRepository
                                                                .getMembers(SlockRequestPriority.Visible, selectedServerId)
                                                                .toMembersUiState()
                                            }
                                        }
                                    },
                                    onSend = {
                                        if (selectedThreadState.composerState.status != ComposerStatus.Sending &&
                                                        selectedThreadState.composerState.status !=
                                                                ComposerStatus.Uploading
                                        ) {
                                            val sendDraft =
                                                    ComposerSubmissionDraft(
                                                            value = composerValue,
                                                            asTask = composerAsTask,
                                                            attachments = pendingAttachmentFiles,
                                                            failedAttachmentIds = emptySet()
                                                    )
                                            val body = sendDraft.body
                                            val selectedHeader = selectedThreadContent()?.header
                                            val messageStreamChannelId =
                                                    composerSendChannelId(
                                                            selectedThreadContent(),
                                                            selectedThreadId
                                                    )
                                            val sendServerId = selectedServerIdForUi()
                                            fun isCurrentComposerTarget(): Boolean =
                                                    messageStreamChannelId != null &&
                                                            selectedServerIdForUi() == sendServerId &&
                                                            composerSendChannelId(
                                                                    selectedThreadContent(),
                                                                    selectedThreadId
                                                            ) == messageStreamChannelId

                                            fun applyComposerStatusIfCurrent(
                                                    status: ComposerStatus,
                                                    errorCode: String? = null,
                                                    noPermissionCode: String? = null
                                            ) {
                                                if (!isCurrentComposerTarget()) return
                                                selectedThreadState =
                                                        selectedThreadState.withComposerStatus(
                                                                status = status,
                                                                errorCode = errorCode,
                                                                noPermissionCode = noPermissionCode
                                                        )
                                            }

                                            fun restoreComposerDraft(restoredDraft: ComposerSubmissionDraft) {
                                                composerDrafts =
                                                        composerDrafts.saveComposerDraft(
                                                                messageStreamChannelId?.value,
                                                                restoredDraft
                                                        )
                                                if (!isCurrentComposerTarget()) return
                                                composerValue = restoredDraft.value
                                                composerAsTask = restoredDraft.asTask
                                                pendingAttachmentFiles = restoredDraft.attachments
                                                failedAttachmentIds = restoredDraft.failedAttachmentIds
                                            }
                                            if (selectedHeader != null &&
                                                            (!selectedHeader.joined ||
                                                                    !selectedHeader.permissions.canSend)
                                            ) {
                                                applyComposerStatusIfCurrent(
                                                        status = ComposerStatus.NoPermission,
                                                        noPermissionCode =
                                                                if (!selectedHeader.joined) "join_required"
                                                                else "read_only"
                                                )
                                            } else if (messageStreamChannelId != null &&
                                                            sendDraft.hasContent
                                            ) {
                                                val optimisticMessage =
                                                        optimisticMessage(
                                                                messageStreamChannelId,
                                                                sendDraft
                                                        )
                                                if (sendDraft.attachments.isNotEmpty()) {
                                                    attachmentOpenStates =
                                                            attachmentOpenStates +
                                                                    sendDraft
                                                                            .optimisticAttachmentOpenStates(
                                                                                    AttachmentOpenStatus
                                                                                            .Loading
                                                                            )
                                                }
                                                optimisticSendMessagesByStream =
                                                        optimisticSendMessagesByStream +
                                                                (messageStreamChannelId.value to
                                                                        optimisticMessage)
                                                failedAttachmentIds = emptySet()
                                                selectedThreadState =
                                                        selectedThreadState
                                                                .upsertOptimisticMessage(
                                                                        optimisticMessage
                                                                )
                                                                .withComposerStatus(
                                                                        if (sendDraft.attachments
                                                                                        .isNotEmpty())
                                                                                ComposerStatus
                                                                                        .Uploading
                                                                        else ComposerStatus.Sending
                                                                )
                                                composerValue = ""
                                                composerAsTask = false
                                                pendingAttachmentFiles = emptyList()
                                                coroutineScope.launch {
                                                    val attachmentIds =
                                                            if (sendDraft.attachments.isNotEmpty()) {
                                                                when (val uploadResult =
                                                                                withSlockRequestPriority(
                                                                                        priority =
                                                                                                SlockRequestPriority
                                                                                                        .Foreground,
                                                                                        key =
                                                                                                "attachments:${messageStreamChannelId.value}",
                                                                                        reason =
                                                                                                "send_message_upload"
                                                                                ) {
                                                                                    conversationRepository
                                                                                            .uploadAttachments(
                                                                                                    AttachmentUploadRequest(
                                                                                                            channelId =
                                                                                                                    messageStreamChannelId,
                                                                                                            files =
                                                                                                                    sendDraft.attachments
                                                                                                    )
                                                                                            )
                                                                                }
                                                                ) {
                                                                    is DataResult.Content ->
                                                                            uploadResult
                                                                                    .data
                                                                                    .attachmentIds
                                                                    is DataResult.Empty -> emptyList()
                                                                    is DataResult.Error -> {
                                                                        val failedDraft =
                                                                                sendDraft.withAllAttachmentsFailed()
                                                                        val failedMessage =
                                                                                optimisticMessage
                                                                                        .copy(
                                                                                                deliveryState =
                                                                                                        MessageDeliveryState
                                                                                                                .Failed
                                                                                        )
                                                                        optimisticSendMessagesByStream =
                                                                                optimisticSendMessagesByStream +
                                                                                        (messageStreamChannelId.value to
                                                                                                failedMessage)
                                                                        attachmentOpenStates =
                                                                                attachmentOpenStates +
                                                                                        failedDraft
                                                                                                .optimisticAttachmentOpenStates(
                                                                                                        AttachmentOpenStatus
                                                                                                                .Error,
                                                                                                        uploadResult
                                                                                                                .error
                                                                                                                .code
                                                                                                )
                                                                        restoreComposerDraft(failedDraft)
                                                                        applyComposerStatusIfCurrent(
                                                                                status = ComposerStatus.Failed,
                                                                                errorCode = uploadResult.error.code
                                                                        )
                                                                        if (isCurrentComposerTarget()) {
                                                                            selectedThreadState =
                                                                                    selectedThreadState
                                                                                            .upsertOptimisticMessage(
                                                                                                    failedMessage
                                                                                            )
                                                                        }
                                                                        return@launch
                                                                    }
                                                                    is DataResult.NoPermission -> {
                                                                        val failedDraft =
                                                                                sendDraft.withAllAttachmentsFailed()
                                                                        val failedMessage =
                                                                                optimisticMessage
                                                                                        .copy(
                                                                                                deliveryState =
                                                                                                        MessageDeliveryState
                                                                                                                .Failed
                                                                                        )
                                                                        optimisticSendMessagesByStream =
                                                                                optimisticSendMessagesByStream +
                                                                                        (messageStreamChannelId.value to
                                                                                                failedMessage)
                                                                        attachmentOpenStates =
                                                                                attachmentOpenStates +
                                                                                        failedDraft
                                                                                                .optimisticAttachmentOpenStates(
                                                                                                        AttachmentOpenStatus
                                                                                                                .Error,
                                                                                                        uploadResult
                                                                                                                .blocker
                                                                                                                .code
                                                                                                )
                                                                        restoreComposerDraft(failedDraft)
                                                                        applyComposerStatusIfCurrent(
                                                                                status = ComposerStatus.NoPermission,
                                                                                noPermissionCode =
                                                                                        uploadResult.blocker.code
                                                                        )
                                                                        if (isCurrentComposerTarget()) {
                                                                            selectedThreadState =
                                                                                    selectedThreadState
                                                                                            .upsertOptimisticMessage(
                                                                                                    failedMessage
                                                                                            )
                                                                        }
                                                                        return@launch
                                                                    }
                                                                }
                                                            } else {
                                                                emptyList()
                                                            }
                                                    applyComposerStatusIfCurrent(ComposerStatus.Sending)
                                                    val result =
                                                            withSlockRequestPriority(
                                                                    priority =
                                                                            SlockRequestPriority
                                                                                    .Foreground,
                                                                    key =
                                                                            "send:${messageStreamChannelId.value}",
                                                                    reason = "send_message"
                                                            ) {
                                                                conversationRepository.sendMessage(
                                                                        channelId = messageStreamChannelId,
                                                                        body = body,
                                                                        attachmentIds = attachmentIds,
                                                                        asTask = sendDraft.asTask
                                                                )
                                                            }
                                                    when (result) {
                                                        is DataResult.Content -> {
                                                            localWriteThrough.writeSendResult(
                                                                    result,
                                                                    source = "send_message",
                                                                    serverId = selectedServerIdForUi()
                                                            )
                                                            if (isCurrentComposerTarget()) {
                                                            selectedThreadState =
                                                                    selectedThreadState
                                                                            .removeMessage(
                                                                                    optimisticMessage
                                                                                            .id
                                                                            )
                                                                            .appendSentMessage(
                                                                                    result.data
                                                                            )
                                                            }
                                                            optimisticSendMessagesByStream =
                                                                    optimisticSendMessagesByStream -
                                                                            messageStreamChannelId
                                                                                    .value
                                                            attachmentOpenStates =
                                                                    attachmentOpenStates -
                                                                            sendDraft
                                                                                    .optimisticAttachmentIds()
                                                            composerDrafts =
                                                                    composerDrafts - messageStreamChannelId.value
                                                            if (isCurrentComposerTarget()) {
                                                                failedAttachmentIds = emptySet()
                                                            }
                                                        }
                                                        is DataResult.Empty -> {
                                                            val failedMessage =
                                                                    optimisticMessage.copy(
                                                                            deliveryState =
                                                                                    MessageDeliveryState
                                                                                            .Failed
                                                                    )
                                                            optimisticSendMessagesByStream =
                                                                    optimisticSendMessagesByStream +
                                                                            (messageStreamChannelId
                                                                                    .value to
                                                                                    failedMessage)
                                                            attachmentOpenStates =
                                                                    attachmentOpenStates +
                                                                            sendDraft
                                                                                    .optimisticAttachmentOpenStates(
                                                                                            AttachmentOpenStatus
                                                                                                    .Error,
                                                                                            result.reason.name
                                                                                    )
                                                            restoreComposerDraft(sendDraft)
                                                            applyComposerStatusIfCurrent(
                                                                    status = ComposerStatus.Failed,
                                                                    errorCode = result.reason.name
                                                            )
                                                            if (isCurrentComposerTarget()) {
                                                                selectedThreadState =
                                                                        selectedThreadState
                                                                                .upsertOptimisticMessage(
                                                                                        failedMessage
                                                                                )
                                                            }
                                                        }
                                                        is DataResult.Error -> {
                                                            val failedMessage =
                                                                    optimisticMessage.copy(
                                                                            deliveryState =
                                                                                    MessageDeliveryState
                                                                                            .Failed
                                                                    )
                                                            optimisticSendMessagesByStream =
                                                                    optimisticSendMessagesByStream +
                                                                            (messageStreamChannelId
                                                                                    .value to
                                                                                    failedMessage)
                                                            attachmentOpenStates =
                                                                    attachmentOpenStates +
                                                                            sendDraft
                                                                                    .optimisticAttachmentOpenStates(
                                                                                            AttachmentOpenStatus
                                                                                                    .Error,
                                                                                            result.error.code
                                                                                    )
                                                            restoreComposerDraft(sendDraft)
                                                            applyComposerStatusIfCurrent(
                                                                    status = ComposerStatus.Failed,
                                                                    errorCode = result.error.code
                                                            )
                                                            if (isCurrentComposerTarget()) {
                                                                selectedThreadState =
                                                                        selectedThreadState
                                                                                .upsertOptimisticMessage(
                                                                                        failedMessage
                                                                                )
                                                            }
                                                        }
                                                        is DataResult.NoPermission -> {
                                                            val failedMessage =
                                                                    optimisticMessage.copy(
                                                                            deliveryState =
                                                                                    MessageDeliveryState
                                                                                            .Failed
                                                                    )
                                                            optimisticSendMessagesByStream =
                                                                    optimisticSendMessagesByStream +
                                                                            (messageStreamChannelId
                                                                                    .value to
                                                                                    failedMessage)
                                                            attachmentOpenStates =
                                                                    attachmentOpenStates +
                                                                            sendDraft
                                                                                    .optimisticAttachmentOpenStates(
                                                                                            AttachmentOpenStatus
                                                                                                    .Error,
                                                                                            result.blocker.code
                                                                                    )
                                                            restoreComposerDraft(sendDraft)
                                                            applyComposerStatusIfCurrent(
                                                                    status = ComposerStatus.NoPermission,
                                                                    noPermissionCode = result.blocker.code
                                                            )
                                                            if (isCurrentComposerTarget()) {
                                                                selectedThreadState =
                                                                        selectedThreadState
                                                                                .upsertOptimisticMessage(
                                                                                        failedMessage
                                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onLoadOlderThreadMessages = ::loadOlderThreadMessages,
                                    onLoadNewerThreadMessages = ::loadNewerThreadMessages,
                                    onMarkInboxItemRead = ::markInboxItemRead,
                                    onSaveMessage = ::saveMessage,
                                    onRemoveSaved = ::unsaveMessage,
                                    onRefreshInbox = { refreshInboxRoute() },
                                    onRefreshFollowedThreads = { refreshFollowedThreadsRoute() },
                                    onRefreshSaved = { refreshSavedRoute() },
                                    onRefreshSearch = { refreshSearchRoute() },
                                    onMarkAllInboxRead = ::markAllInboxRead,
                                    onInboxFilterSelected = { filter ->
                                        if (filter != inboxFilter) {
                                            val selectedServerId = selectedServerIdForUi()
                                            inboxFilter = filter
                                            setInboxState(
                                                    inboxState.withFilterLoading(
                                                            filter,
                                                            inboxSnapshot(filter)
                                                    )
                                            )
                                            if (selectedServerId != null) {
                                                coroutineScope.launch {
                                                    val result =
                                                            inboxRepository.loadInitial(
                                                                    serverId = selectedServerId,
                                                                    filter =
                                                                            filter.toRepositoryFilter()
                                                            )
                                                    if (inboxFilter == filter &&
                                                                    isCurrentServer(selectedServerId)
                                                    ) {
                                                        setInboxState(result.toInboxRouteState(filter))
                                                    } else {
                                                        logStaleServerWrite("inbox_filter_load", selectedServerId)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onLoadInboxMore = { offset, filter ->
                                        val selectedServerId = selectedServerIdForUi()
                                        if (selectedServerId != null && offset != null) {
                                            coroutineScope.launch {
                                                setInboxState(inboxState.copy(loadingMore = true))
                                                val next =
                                                        inboxRepository
                                                                .loadMore(
                                                                        serverId = selectedServerId,
                                                                        filter = filter.toRepositoryFilter(),
                                                                        offset = offset
                                                                )
                                                                .toInboxUiState(filter)
                                                if (isCurrentServer(selectedServerId)) {
                                                    setInboxState(
                                                            mergeInboxPage(
                                                                    current = inboxState,
                                                                    next = next
                                                            )
                                                    )
                                                } else {
                                                    logStaleServerWrite(
                                                            "inbox_load_more",
                                                            selectedServerId
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onLoadSavedMore = { offset ->
                                        val selectedServerId = selectedServerIdForUi()
                                        if (selectedServerId != null && offset != null) {
                                            coroutineScope.launch {
                                                setSavedState(savedState.copy(loadingMore = true))
                                                val next =
                                                        savedRepository
                                                                .loadMore(
                                                                        serverId = selectedServerId,
                                                                        offset = offset
                                                                )
                                                                .toSavedInboxUiState()
                                                if (isCurrentServer(selectedServerId)) {
                                                    setSavedState(
                                                            mergeInboxPage(
                                                                    current = savedState,
                                                                    next = next
                                                            )
                                                    )
                                                } else {
                                                    logStaleServerWrite(
                                                            "saved_load_more",
                                                            selectedServerId
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onLoadSearchMore = { offset ->
                                        val selectedServerId = selectedServerIdForUi()
                                        if (selectedServerId != null &&
                                                        offset != null &&
                                                        searchQuery.isNotBlank()
                                        ) {
                                            val query = searchQuery
                                            coroutineScope.launch {
                                                setSearchState(
                                                        searchState.copy(loadingMore = true),
                                                        query = query
                                                )
                                                val next =
                                                        searchRepository
                                                                .search(
                                                                        serverId = selectedServerId,
                                                                        query = query,
                                                                        offset = offset
                                                                )
                                                                .toSearchInboxUiState()
                                                if (isCurrentServer(selectedServerId) && searchQuery == query) {
                                                    setSearchState(
                                                            mergeInboxPage(
                                                                    current = searchState,
                                                                    next = next
                                                            ),
                                                            query = query
                                                    )
                                                } else {
                                                    logStaleServerWrite(
                                                            "search_load_more",
                                                            selectedServerId
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    currentMemberId =
                                            currentAuthSession?.user?.toMemberProfile()?.id
                                                    ?: ((homeState.loadState as?
                                                                    ScreenLoadState.Content)
                                                            ?.data
                                                            ?.currentMember
                                                            ?.id),
                                    myMessageAlignment =
                                            chatLayoutPreferenceSnapshot.myMessageAlignment,
                                    adaptiveLayoutInfo = adaptiveLayoutInfo,
                                    showNavigation = showNavigation
                            )
                    AppDestination.Tasks ->
                            TasksScreen(
                                    state = tasksState,
                                    onFilterSelected = { filter ->
                                        tasksFilter = filter
                                        tasksState = tasksState.withLoading(filter)
                                        tasksRefreshNonce += 1
                                    },
                                    onTaskClick = ::openTaskContext,
                                    onTaskStatusChange = ::updateTaskStatus,
                                    onRetry = {
                                        tasksState = tasksState.withLoading(tasksFilter)
                                        tasksRefreshNonce += 1
                                    }
                            )
                    AppDestination.Members ->
                            MembersRouteScreen(
                                    state = membersState,
                                    adaptiveLayoutInfo = adaptiveLayoutInfo,
                                    initialSelectedMemberId = membersDetailSelectedMemberId,
                                    onBack = { destination = AppDestination.Home },
                                    onDetailVisibleChange = {
                                        membersDetailVisible = it
                                        if (!it) {
                                            membersDetailSelectedMemberId = null
                                        }
                                    },
                                    onDetailBack = ::returnFromMemberDetail,
                                    onOpenMemberProfile = { member -> openMemberProfile(member) },
                                    onOpenAgentDirectMessage = { member ->
                                        openAgentDirectMessage(member)
                                    },
                                    onToggleAgentWorkspaceFolder = ::toggleAgentWorkspaceFolder,
                                    onSelectAgentWorkspaceFile = ::selectAgentWorkspaceFile,
                                    onBackToAgentWorkspaceTree = ::backToAgentWorkspaceTree,
                                    onCopyAgentWorkspacePath = ::copyAgentWorkspacePath,
                                    onRefreshAgentWorkspaceTree = ::refreshSelectedAgentWorkspaceTree,
                                    onSaveAgentProfile = ::saveAgentProfile,
                                    loadHumanProfile = { memberId ->
                                        (withSlockRequestPriority(
                                                priority = SlockRequestPriority.Foreground,
                                                key = "member:${memberId.value}",
                                                reason = "load_human_profile"
                                        ) {
                                            memberRepository.getMemberProfile(SlockRequestPriority.Foreground, memberId)
                                        } as? DataResult.Content)?.data
                                    }
                            )
                    AppDestination.Settings ->
                            SettingsScreen(
                                    state = SlockStateSamples.settingsContent,
                                    destination = settingsDestination,
                                    onDestinationChange = { settingsDestination = it },
                                    adaptiveLayoutInfo = adaptiveLayoutInfo,
                                    accountState = settingsAccountState,
                                    computersState = settingsComputersState,
                                    computerDetailState = settingsComputerDetailState,
                                    serverState = settingsServerState,
                                    billingState = settingsBillingState,
                                    mobileState = settingsMobileState,
                                    storageSnapshot = settingsStorageSnapshot,
                                    storageActionInProgress = settingsStorageActionInProgress,
                                    storageFeedback = settingsStorageFeedback,
                                    aboutSnapshot =
                                            context.toSettingsAboutSnapshot(
                                                    session = currentAuthSession,
                                                    selectedServerId = selectedServerOverride
                                                                    ?: (serverListState as?
                                                                                    ScreenLoadState.Content)
                                                                            ?.data
                                                                            ?.selectedServer
                                                                            ?.id
                                                                            ?: currentAuthSession
                                                                            ?.currentServerId?.let(
                                                                            ::ServerId
                                                                    ),
                                                    socketConnected = realtimeSubscription != null,
                                                    notificationPermissionGranted =
                                                            notificationPermissionGranted,
                                                    diagnosticsVersion = settingsDiagnosticsVersion
                                    ),
                                    notificationPermissionGranted = notificationPermissionGranted,
                                    notificationPermissionLauncherAvailable = false,
                                    localeSnapshot = localeSnapshot,
                                    onLocaleSelected = onLocaleSelected,
                                    chatLayoutPreferenceSnapshot = chatLayoutPreferenceSnapshot,
                                    onMyMessageAlignmentSelected = onMyMessageAlignmentSelected,
                                    onLargeScreenNavigationSelected =
                                            onLargeScreenNavigationSelected,
                                    notificationsEnabledByUser = notificationsEnabledByUser,
                                    onNotificationsEnabledByUserChange =
                                            onNotificationsEnabledByUserChange,
                                    onOpenComputerDetail = { machineId ->
                                        settingsSelectedComputerId = machineId
                                        settingsDestination = SettingsDestination.ComputerDetail
                                    },
                                    onSaveServerProfile = { name ->
                                        coroutineScope.launch {
                                            settingsServerActionInProgress = true
                                            settingsServerActionFeedback = null
                                            val result =
                                                    withSlockRequestPriority(
                                                            priority =
                                                                    SlockRequestPriority.Foreground,
                                                            key = "settings:server-profile",
                                                            reason = "update_server_profile"
                                                    ) {
                                                        settingsRepository.updateServerProfile(
                                                                SettingsServerProfileUpdateRequest(
                                                                        name
                                                                )
                                                        )
                                                    }
                                            settingsServerActionInProgress = false
                                            settingsServerState = result.toScreenLoadState()
                                            settingsServerActionFeedback =
                                                    result.settingsServerFeedback("Profile saved.")
                                        }
                                    },
                                    onSendServerInvite = { email ->
                                        coroutineScope.launch {
                                            settingsServerActionInProgress = true
                                            settingsServerActionFeedback = null
                                            val result =
                                                    withSlockRequestPriority(
                                                            priority =
                                                                    SlockRequestPriority.Foreground,
                                                            key = "settings:server-invite",
                                                            reason = "create_server_invite"
                                                    ) {
                                                        settingsRepository.createServerInvite(
                                                                SettingsServerInviteCreateRequest(
                                                                        email
                                                                )
                                                        )
                                                    }
                                            settingsServerActionInProgress = false
                                            settingsServerActionFeedback =
                                                    result.settingsServerFeedback(
                                                            context.getString(R.string.settings_server_invite_sent)
                                                    )
                                            if (result is DataResult.Content) {
                                                settingsServerState =
                                                        settingsRepository
                                                                .getServerSettings()
                                                                .toScreenLoadState()
                                            }
                                        }
                                    },
                                    onDeleteServer = { confirmation ->
                                        coroutineScope.launch {
                                            settingsServerActionInProgress = true
                                            settingsServerActionFeedback = null
                                            val result =
                                                    withSlockRequestPriority(
                                                            priority =
                                                                    SlockRequestPriority.Foreground,
                                                            key = "settings:delete-server",
                                                            reason = "delete_server"
                                                    ) {
                                                        settingsRepository.deleteServer(
                                                                SettingsServerDeleteRequest(
                                                                        confirmation
                                                                )
                                                        )
                                                    }
                                            settingsServerActionInProgress = false
                                            settingsServerActionFeedback =
                                                    result.settingsServerFeedback("Server deleted.")
                                            if (result is DataResult.Content) {
                                                serverListState =
                                                        serverRepository.getServers()
                                                                .toScreenLoadState()
                                                settingsDestination = SettingsDestination.Home
                                            }
                                        }
                                    },
                                    serverActionFeedback = settingsServerActionFeedback,
                                    serverActionInProgress = settingsServerActionInProgress,
                                    onLogout = { signOutFromAuthenticated("settings") },
                                    onRetryComputerDetail = {
                                        val machineId = settingsSelectedComputerId
                                        if (machineId != null) {
                                            coroutineScope.launch {
                                                settingsComputerDetailState =
                                                        ScreenLoadState.Loading
                                                settingsComputerDetailState =
                                                        settingsRepository
                                                                .getComputerDetail(machineId)
                                                                .toScreenLoadState()
                                            }
                                        }
                                    },
                                    onRequestNotificationPermission = {
                                        notificationPermissionGranted =
                                                context.hasNotificationPermission()
                                        ai.slock.android.core.data.diagnostics
                                                .SlockDiagnosticsLogger.d(
                                                "SlockSettingsNotification",
                                                "permission_state_check source=enable_click ${context.notificationDiagnostics().toLogFields()} granted=$notificationPermissionGranted"
                                        )
                                        if (!notificationPermissionGranted) {
                                            activityActions.requestNotificationPermission { granted
                                                ->
                                                notificationPermissionGranted =
                                                        granted ||
                                                                context.hasNotificationPermission()
                                                if (notificationPermissionGranted) {
                                                    context
                                                            .ensureSettingsNotificationChannelIfAllowed(
                                                                    "permission_result"
                                                            )
                                                    onNotificationsEnabledByUserChange(true)
                                                }
                                                ai.slock.android.core.data.diagnostics
                                                        .SlockDiagnosticsLogger.d(
                                                        "SlockSettingsNotification",
                                                        "request_permission_result granted=$notificationPermissionGranted ${context.notificationDiagnostics().toLogFields()}"
                                                )
                                            }
                                        } else {
                                            onNotificationsEnabledByUserChange(true)
                                        }
                                    },
                                    onSendTestNotification = {
                                        notificationPermissionGranted =
                                                context.hasNotificationPermission()
                                        val result = context.showSettingsTestNotification()
                                        ai.slock.android.core.data.diagnostics
                                                .SlockDiagnosticsLogger.d(
                                                "SlockSettingsNotification",
                                                "test_post_result ${result.toLogFields()} ${context.notificationDiagnostics().toLogFields()}"
                                        )
                                    },
                                    onOpenNotificationSettings = {
                                        ai.slock.android.core.data.diagnostics
                                                .SlockDiagnosticsLogger.d(
                                                "SlockSettingsNotification",
                                                "open_settings_start ${context.notificationDiagnostics().toLogFields()}"
                                        )
                                        context.openNotificationSettings()
                                    },
                                    onRefreshStorage = {
                                        refreshStorageSnapshot(
                                                context.getString(
                                                        R.string.settings_storage_feedback_refreshed
                                                )
                                        )
                                    },
                                    onClearStorage = ::clearStorageTarget,
                                    onCopyDiagnostics = {
                                        context.copyDiagnosticsToClipboard()
                                        settingsDiagnosticsVersion++
                                    },
                                    onShareDiagnostics = {
                                        context.shareDiagnostics()
                                        settingsDiagnosticsVersion++
                                    },
                                    onClearDiagnostics = {
                                        SanitizedDiagnosticsLogBuffer.clear()
                                        ai.slock.android.core.data.diagnostics
                                                .SlockDiagnosticsLogger.record(
                                                "SlockDiagnostics",
                                                "logs_cleared"
                                        )
                                        settingsDiagnosticsVersion++
                                    },
                                    onSendFeedback = {
                                        coroutineScope.launch {
                                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
                                                    .d(
                                                            "Feedback",
                                                            "send_feedback_requested"
                                                    )

                                            // Get current server ID
                                            val serverId = selectedServerOverride
                                                    ?: (serverListState as? ScreenLoadState.Content)
                                                            ?.data
                                                            ?.selectedServer
                                                            ?.id
                                                    ?: currentAuthSession?.currentServerId?.let(::ServerId)

                                            if (serverId == null) {
                                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
                                                        .d("Feedback", "no_server_selected")
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "未选择服务器，无法发送反馈",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                                return@launch
                                            }

                                            // Find feedback channel
                                            val store = createSlockLocalStore(context)
                                            val conversations = store.getHomeConversations(serverId)
                                            val feedbackChannel = conversations.channels.find { channel ->
                                                channel.name == "反馈" || channel.name == "feedback"
                                            }

                                            if (feedbackChannel == null) {
                                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
                                                        .d("Feedback", "feedback_channel_not_found")
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Feedback channel not found. Create a channel named feedback.",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                                return@launch
                                            }

                                            // Get current about snapshot for device info
                                            val aboutSnapshot = context.toSettingsAboutSnapshot(
                                                    session = currentAuthSession,
                                                    selectedServerId = serverId,
                                                    socketConnected = realtimeSubscription != null,
                                                    notificationPermissionGranted = notificationPermissionGranted,
                                                    diagnosticsVersion = settingsDiagnosticsVersion
                                            )

                                            // Create diagnostic log file
                                            val logFileName = "slock-diagnostics-${System.currentTimeMillis()}.txt"
                                            val logContent = buildString {
                                                appendLine("Slock Android Diagnostic Log")
                                                appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                                                appendLine()
                                                appendLine("=== Device Information ===")
                                                appendLine("App Version: ${aboutSnapshot.appVersion}")
                                                appendLine("Build Number: ${aboutSnapshot.buildNumber}")
                                                appendLine("Device: ${aboutSnapshot.deviceLabel}")
                                                appendLine("Android Version: ${aboutSnapshot.androidVersion}")
                                                appendLine("Session Status: ${aboutSnapshot.sessionStatus}")
                                                appendLine("Server Status: ${aboutSnapshot.serverStatus}")
                                                appendLine("Socket Status: ${aboutSnapshot.socketStatus}")
                                                appendLine()
                                                appendLine("=== Diagnostic Logs ===")
                                                aboutSnapshot.diagnosticLogs.forEach { log ->
                                                    val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                                                            .format(java.util.Date(log.timestampMillis))
                                                    appendLine("$timestamp [${log.tag}] ${log.message}")
                                                }
                                            }

                                            // Create attachment from log content
                                            val logBytes = logContent.toByteArray(Charsets.UTF_8)
                                            val logAttachment = AttachmentUploadFile(
                                                    id = "feedback-log-${System.currentTimeMillis()}",
                                                    fileName = logFileName,
                                                    mimeType = "text/plain",
                                                    sizeBytes = logBytes.size.toLong(),
                                                    previewUri = null,
                                                    bytes = logBytes
                                            )

                                            // Prepare feedback message with device info (without logs in message body)
                                            val feedbackMessage = buildString {
                                                appendLine("## 设备信息")
                                                appendLine()
                                                appendLine("- **应用版本**: ${aboutSnapshot.appVersion}")
                                                appendLine("- **构建号**: ${aboutSnapshot.buildNumber}")
                                                appendLine("- **设备**: ${aboutSnapshot.deviceLabel}")
                                                appendLine("- **Android 版本**: ${aboutSnapshot.androidVersion}")
                                                appendLine("- **会话状态**: ${aboutSnapshot.sessionStatus}")
                                                appendLine("- **服务器状态**: ${aboutSnapshot.serverStatus}")
                                                appendLine("- **Socket 状态**: ${aboutSnapshot.socketStatus}")
                                                appendLine()
                                                appendLine("*诊断日志已作为附件上传*")
                                                appendLine()
                                                appendLine("## 问题描述")
                                                appendLine()
                                                appendLine("请在此描述您遇到的问题...")
                                            }

                                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
                                                    .d("Feedback", "navigating_to_channel channelId=${feedbackChannel.channelId?.value}")

                                            // Navigate to feedback channel
                                            val channelId = feedbackChannel.channelId
                                            if (channelId != null) {
                                                selectedThreadId = channelId.value
                                                homeRoute = HomeRoute.Thread
                                                destination = AppDestination.Home

                                                // Pre-fill composer with feedback message
                                                composerDrafts = composerDrafts.saveComposerDraft(
                                                        threadId = channelId.value,
                                                        value = feedbackMessage,
                                                        asTask = false,
                                                        attachments = listOf(logAttachment),
                                                        failedAttachmentIds = emptySet()
                                                )
                                                composerValue = feedbackMessage
                                                composerAsTask = false
                                                pendingAttachmentFiles = listOf(logAttachment)
                                                failedAttachmentIds = emptySet()
                                                composerFocusRequestNonce += 1
                                                selectedThreadState =
                                                        selectedThreadState.withComposerStatus(
                                                                status = ComposerStatus.Ready
                                                        )

                                                // Show success toast
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "已跳转到反馈频道，日志 txt 已添加，请填写问题描述",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            } else {
                                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
                                                        .d("Feedback", "feedback_channel_no_id")
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "反馈频道 ID 无效",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                            )
                    }
        if (createServerVisible) {
            val trimmedCreateServerName = createServerName.trim()
            val trimmedCreateServerSlug = createServerSlug.trim()
            val createServerSubmitEnabled =
                    trimmedCreateServerName.isNotBlank() && !createServerSubmitting
            SlockModal(
                    title = stringResource(R.string.home_create_server_title),
                    onDismiss = {
                        if (!createServerSubmitting) {
                            createServerVisible = false
                            createServerError = null
                        }
                    },
                    enabled = !createServerSubmitting,
                    dismissOnScrimClick = !createServerSubmitting,
                    errorText =
                            createServerError?.let {
                                stringResource(R.string.home_create_server_error, it)
                            },
                    primaryAction =
                            SlockModalAction(
                                    text = stringResource(R.string.home_create_server_primary),
                                    enabled = createServerSubmitEnabled,
                                    loading = createServerSubmitting,
                                    onClick = {
                                        if (!createServerSubmitEnabled) return@SlockModalAction
                                        coroutineScope.launch {
                                            createServerSubmitting = true
                                            createServerError = null
                                            when (val result =
                                                            serverRepository.createServer(
                                                                    CreateServerRequest(
                                                                            name =
                                                                                    trimmedCreateServerName,
                                                                            slug =
                                                                                    trimmedCreateServerSlug
                                                                                            .ifBlank {
                                                                                                null
                                                                                            }
                                                                    )
                                                            )
                                            ) {
                                                is DataResult.Content -> {
                                                    enterSelectedServer(result.data)
                                                    createServerSubmitting = false
                                                    createServerVisible = false
                                                    createServerName = ""
                                                    createServerSlug = ""
                                                }
                                                is DataResult.Empty -> {
                                                    createServerSubmitting = false
                                                    createServerError = result.reason.name
                                                }
                                                is DataResult.Error -> {
                                                    createServerSubmitting = false
                                                    createServerError = result.error.code
                                                }
                                                is DataResult.NoPermission -> {
                                                    createServerSubmitting = false
                                                    createServerError = result.blocker.code
                                                }
                                            }
                                        }
                                    }
                            ),
                    secondaryAction =
                            SlockModalAction(
                                    text = stringResource(R.string.home_create_server_cancel),
                                    onClick = {
                                        createServerVisible = false
                                        createServerError = null
                                    },
                                    enabled = !createServerSubmitting,
                                    style = SlockModalActionStyle.Outline
                            )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                    Text(
                            text = stringResource(R.string.home_create_server_detail),
                            style = SlockType.Body,
                            color = SlockColor.TextBlack
                    )
                    SlockModalTextField(
                            value = createServerName,
                            onValueChange = {
                                createServerName = it
                                createServerError = null
                            },
                            label = stringResource(R.string.home_create_server_name_label),
                            placeholder =
                                    stringResource(R.string.home_create_server_name_placeholder),
                            required = true,
                            enabled = !createServerSubmitting
                    )
                    SlockModalTextField(
                            value = createServerSlug,
                            onValueChange = {
                                createServerSlug =
                                        it.lowercase().filter { char ->
                                            char.isLetterOrDigit() || char == '-'
                                        }
                                createServerError = null
                            },
                            label = stringResource(R.string.home_create_server_slug_label),
                            placeholder =
                                    stringResource(R.string.home_create_server_slug_placeholder),
                            optional = true,
                            enabled = !createServerSubmitting
                    )
                }
            }
        }
        if (createChannelVisible) {
            val trimmedCreateChannelName = createChannelName.trim()
            val trimmedCreateChannelDescription = createChannelDescription.trim()
            val createChannelSubmitEnabled =
                    trimmedCreateChannelName.isNotBlank() && !createChannelSubmitting
            val createChannelModalBodyMaxHeight =
                    (LocalConfiguration.current.screenHeightDp.dp - 280.dp)
                            .coerceIn(220.dp, 520.dp)
            val createChannelMembersLoadState = membersState.loadState
            val createChannelMembers =
                    (createChannelMembersLoadState as? ScreenLoadState.Content)
                            ?.data
                            ?.members
                            .orEmpty()
            val createChannelAgents = createChannelMembers.filter { it.kind == MemberKind.Agent }
            val createChannelUsers = createChannelMembers.filter { it.kind == MemberKind.Human }
            SlockModal(
                    title = stringResource(R.string.home_create_channel_title),
                    onDismiss = {
                        if (!createChannelSubmitting) {
                            createChannelVisible = false
                            createChannelError = null
                        }
                    },
                    enabled = !createChannelSubmitting,
                    dismissOnScrimClick = !createChannelSubmitting,
                    errorText =
                            createChannelError?.let {
                                stringResource(R.string.home_create_channel_error, it)
                            },
                    primaryAction =
                            SlockModalAction(
                                    text = stringResource(R.string.home_create_channel_primary),
                                    enabled = createChannelSubmitEnabled,
                                    loading = createChannelSubmitting,
                                    onClick = {
                                        if (!createChannelSubmitEnabled) return@SlockModalAction
                                        coroutineScope.launch {
                                            createChannelSubmitting = true
                                            createChannelError = null
                                            when (val result =
                                                            withSlockRequestPriority(
                                                                    priority =
                                                                            SlockRequestPriority
                                                                                    .Foreground,
                                                                    key =
                                                                            "server:${selectedServerIdForUi()?.value.orEmpty()}:create-channel",
                                                                    reason = "create_channel"
                                                            ) {
                                                                channelRepository.createChannel(
                                                                        CreateChannelRequest(
                                                                                name =
                                                                                        trimmedCreateChannelName,
                                                                                description =
                                                                                        trimmedCreateChannelDescription
                                                                                                .ifBlank {
                                                                                                    null
                                                                                                },
                                                                                agentMemberIds =
                                                                                        createChannelAgentMemberIds,
                                                                                userMemberIds =
                                                                                        createChannelUserMemberIds
                                                                        )
                                                                )
                                                            }
                                            ) {
                                                is DataResult.Content -> {
                                                    val channel = result.data.channel
                                                    val serverId =
                                                            selectedServerOverride
                                                                    ?: (serverListState as?
                                                                            ScreenLoadState.Content)
                                                                            ?.data
                                                                            ?.selectedServer
                                                                            ?.id
                                                                    ?: currentAuthSession
                                                                            ?.currentServerId
                                                                            ?.let(::ServerId)
                                                    if (serverId != null) {
                                                        localWriteThrough.writeHomeConversations(
                                                                serverId = serverId,
                                                                conversations = listOf(channel),
                                                                source = "create_channel"
                                                        )
                                                        publishHomeFromLocal(serverId)
                                                    } else {
                                                        homeState =
                                                                homeState.withCreatedChannel(channel)
                                                    }
                                                    realtimeRepository.joinChannel(
                                                            channel.channelId
                                                                    ?: ChannelId(
                                                                            channel.threadId.value
                                                                    )
                                                    )
                                                    createChannelSubmitting = false
                                                    createChannelVisible = false
                                                    createChannelName = ""
                                                    createChannelDescription = ""
                                                    createChannelAgentMemberIds = emptyList()
                                                    createChannelUserMemberIds = emptyList()
                                                    destination = AppDestination.Home
                                                    previousHomeRoute = HomeRoute.Shell
                                                    homeRoute = HomeRoute.Thread
                                                    openThread(channel)
                                                }
                                                is DataResult.Empty -> {
                                                    createChannelSubmitting = false
                                                    createChannelError = result.reason.name
                                                }
                                                is DataResult.Error -> {
                                                    createChannelSubmitting = false
                                                    createChannelError = result.error.code
                                                }
                                                is DataResult.NoPermission -> {
                                                    createChannelSubmitting = false
                                                    createChannelError = result.blocker.code
                                                }
                                            }
                                        }
                                    }
                            ),
                    secondaryAction =
                            SlockModalAction(
                                    text = stringResource(R.string.home_create_channel_cancel),
                                    onClick = {
                                        createChannelVisible = false
                                        createChannelError = null
                                    },
                                    enabled = !createChannelSubmitting,
                                    style = SlockModalActionStyle.Outline
                            )
            ) {
                Column(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .heightIn(max = createChannelModalBodyMaxHeight)
                                        .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                ) {
                    Text(
                            text = stringResource(R.string.home_create_channel_detail),
                            style = SlockType.Body,
                            color = SlockColor.TextBlack
                    )
                    SlockModalTextField(
                            value = createChannelName,
                            onValueChange = {
                                createChannelName = it
                                createChannelError = null
                            },
                            label = stringResource(R.string.home_create_channel_name_label),
                            placeholder =
                                    stringResource(R.string.home_create_channel_name_placeholder),
                            required = true,
                            enabled = !createChannelSubmitting
                    )
                    SlockModalTextField(
                            value = createChannelDescription,
                            onValueChange = {
                                createChannelDescription = it
                                createChannelError = null
                            },
                            label = stringResource(R.string.home_create_channel_description_label),
                            placeholder =
                                    stringResource(
                                            R.string.home_create_channel_description_placeholder
                                    ),
                            optional = true,
                            enabled = !createChannelSubmitting,
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4
                    )
                    CreateChannelMemberSection(
                            title = stringResource(R.string.home_create_channel_agents_label),
                            members = createChannelAgents,
                            selectedIds = createChannelAgentMemberIds,
                            enabled = !createChannelSubmitting,
                            membersLoadState = createChannelMembersLoadState,
                            onSelectionChange = { memberId, selected ->
                                val agentId = memberId.removeCreateChannelKindPrefix()
                                createChannelAgentMemberIds =
                                        createChannelAgentMemberIds.withSelection(
                                                agentId,
                                                selected
                                        )
                                createChannelError = null
                            }
                    )
                    CreateChannelMemberSection(
                            title = stringResource(R.string.home_create_channel_users_label),
                            members = createChannelUsers,
                            selectedIds = createChannelUserMemberIds,
                            enabled = !createChannelSubmitting,
                            membersLoadState = createChannelMembersLoadState,
                            emptyText = stringResource(R.string.home_create_channel_no_members),
                            onSelectionChange = { memberId, selected ->
                                val userId = memberId.removeCreateChannelKindPrefix()
                                createChannelUserMemberIds =
                                        createChannelUserMemberIds.withSelection(userId, selected)
                                createChannelError = null
                            }
                    )
                }
            }
        }
        if (taskReferenceChooserLabel != null && taskReferenceChooserTasks.isNotEmpty()) {
            TaskReferenceChooserModal(
                    label = taskReferenceChooserLabel.orEmpty(),
                    tasks = taskReferenceChooserTasks,
                    onTaskSelected = { task ->
                        taskReferenceChooserLabel = null
                        taskReferenceChooserIds = emptyList()
                        openTaskContext(task)
                    },
                    onDismiss = {
                        taskReferenceChooserLabel = null
                        taskReferenceChooserIds = emptyList()
                    }
            )
        }
    }
}

private enum class AuthRoute {
    Login,
    ForgotPassword,
    CreateAccount
}

private enum class ServerGateMode {
    Choose,
    Create
}

private sealed interface AuthUiState {
    data object Restoring : AuthUiState
    data object Submitting : AuthUiState
    data object Authenticated : AuthUiState
    data class SignedOut(val errorMessage: String? = null) : AuthUiState
}

private const val AUTH_RESTORE_SPLASH_MIN_DURATION_MS = 600L

@Composable
private fun AuthRestoreScreen(modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.screenHeightDp >= configuration.screenWidthDp
    val restoreSpacing = if (isPortrait) 40.dp else 28.dp
    Box(
            modifier = modifier.fillMaxSize().background(SlockColor.SurfaceCream),
            contentAlignment = Alignment.Center
    ) {
        Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(restoreSpacing)
        ) {
            AuthLogoBadge(text = stringResource(R.string.auth_logo))
            Text(
                    text = stringResource(R.string.auth_tagline),
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
private fun AuthRouteScreen(
        route: AuthRoute,
        onRouteChange: (AuthRoute) -> Unit,
        onLogin: (email: String, password: String) -> Unit,
        onSendResetLink: (email: String) -> Unit,
        onCreateAccount: (name: String, email: String, password: String) -> Unit,
        authState: AuthUiState,
        forgotPasswordStatus: ForgotPasswordStatus,
        createAccountStatus: CreateAccountStatus,
        onAuthProviderUnavailable: (String) -> Unit,
        modifier: Modifier = Modifier
) {
    if (route != AuthRoute.Login) {
        BackHandler { onRouteChange(AuthRoute.Login) }
    }
    val context = LocalContext.current
    val openSharedAuth = { SlockKuiklyActivity.start(context, route.sharedAuthPageName) }

    when (route) {
        AuthRoute.Login ->
                LoginScreen(
                        onLogin = onLogin,
                        onForgotPassword = { onRouteChange(AuthRoute.ForgotPassword) },
                        onCreateAccount = { onRouteChange(AuthRoute.CreateAccount) },
                        onOpenSharedAuth = openSharedAuth,
                        onAuthProviderUnavailable = onAuthProviderUnavailable,
                        isSubmitting =
                                authState is AuthUiState.Submitting ||
                                        authState is AuthUiState.Restoring,
                        errorMessage = (authState as? AuthUiState.SignedOut)?.errorMessage,
                        modifier = modifier
                )
        AuthRoute.ForgotPassword ->
                ForgotPasswordScreen(
                        status = forgotPasswordStatus,
                        errorMessage = (authState as? AuthUiState.SignedOut)?.errorMessage,
                        onSendResetLink = onSendResetLink,
                        onBackToSignIn = { onRouteChange(AuthRoute.Login) },
                        onOpenSharedAuth = openSharedAuth,
                        modifier = modifier
                )
        AuthRoute.CreateAccount ->
                CreateAccountScreen(
                        status = createAccountStatus,
                        errorMessage = (authState as? AuthUiState.SignedOut)?.errorMessage,
                        onCreateAccount = onCreateAccount,
                        onBackToSignIn = { onRouteChange(AuthRoute.Login) },
                        onOpenSharedAuth = openSharedAuth,
                        modifier = modifier
                )
    }
}

private val AuthRoute.sharedAuthPageName: String
    get() =
            when (this) {
                AuthRoute.Login -> SlockKuiklyActivity.PAGE_LOGIN
                AuthRoute.ForgotPassword -> SlockKuiklyActivity.PAGE_FORGOT_PASSWORD
                AuthRoute.CreateAccount -> SlockKuiklyActivity.PAGE_REGISTER
            }

@Composable
private fun ServerGateScreen(
        state: ScreenLoadState<ServerListSnapshot>,
        currentEmail: String,
        mode: ServerGateMode,
        name: String,
        slug: String,
        submitting: Boolean,
        errorCode: String?,
        onRetry: () -> Unit,
        onSelectServer: (ServerId) -> Unit,
        onShowCreate: () -> Unit,
        onCancelCreate: () -> Unit,
        onNameChange: (String) -> Unit,
        onSlugChange: (String) -> Unit,
        onCreateServer: () -> Unit,
        onSignOut: () -> Unit,
        modifier: Modifier = Modifier
) {
    Box(
            modifier = modifier.fillMaxSize().background(SlockColor.SurfaceCream).padding(24.dp),
            contentAlignment = Alignment.Center
    ) {
        Column(
                modifier =
                        Modifier.fillMaxWidth()
                                .widthIn(max = 520.dp)
                                .padding(
                                        end = SlockShadow.Default.offsetX,
                                        bottom = SlockShadow.Default.offsetY
                                )
                                .slockHardShadow(SlockShadow.Default)
                                .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
                                .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
                                .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            AuthLogoBadge(text = stringResource(R.string.auth_logo))
            Text(
                    text =
                            if (mode == ServerGateMode.Create) {
                                stringResource(R.string.server_gate_create_title)
                            } else {
                                stringResource(R.string.server_gate_choose_title)
                            },
                    style = SlockType.PageTitle,
                    color = SlockColor.TextBlack
            )
            Text(
                    text =
                            stringResource(
                                    R.string.server_gate_signed_in_as,
                                    currentEmail.ifBlank { "Slock user" }
                            ),
                    style = SlockType.Body,
                    color = SlockColor.TextMutedGray
            )
            if (!errorCode.isNullOrBlank()) {
                Text(
                        text = stringResource(R.string.server_gate_error, errorCode),
                        style = SlockType.Caption,
                        color = SlockColor.AccentRed
                )
            }
            when (mode) {
                ServerGateMode.Choose ->
                        ServerChooseContent(
                                state = state,
                                submitting = submitting,
                                onRetry = onRetry,
                                onSelectServer = onSelectServer,
                                onShowCreate = onShowCreate,
                                onSignOut = onSignOut
                        )
                ServerGateMode.Create ->
                        ServerCreateContent(
                                name = name,
                                slug = slug,
                                submitting = submitting,
                                onNameChange = onNameChange,
                                onSlugChange = onSlugChange,
                                onCreateServer = onCreateServer,
                                onCancelCreate = onCancelCreate,
                                onSignOut = onSignOut
                        )
            }
        }
    }
}

@Composable
private fun ServerChooseContent(
        state: ScreenLoadState<ServerListSnapshot>,
        submitting: Boolean,
        onRetry: () -> Unit,
        onSelectServer: (ServerId) -> Unit,
        onShowCreate: () -> Unit,
        onSignOut: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
        when (state) {
            ScreenLoadState.Loading ->
                    Text(
                            text = stringResource(R.string.server_gate_loading),
                            style = SlockType.Body,
                            color = SlockColor.TextBlack
                    )
            is ScreenLoadState.Content -> {
                if (state.data.servers.isEmpty()) {
                    SlockEmptyState(
                            title = stringResource(R.string.server_gate_empty_title),
                            detail = stringResource(R.string.server_gate_empty_detail)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                        state.data.servers.forEach { server ->
                            ServerChoiceRow(
                                    server = server,
                                    enabled = !submitting,
                                    onClick = { onSelectServer(server.id) }
                            )
                        }
                    }
                }
            }
            is ScreenLoadState.Empty ->
                    SlockEmptyState(
                            title = stringResource(R.string.server_gate_empty_title),
                            detail = stringResource(R.string.server_gate_empty_detail)
                    )
            is ScreenLoadState.Error ->
                    SlockErrorState(
                            title = stringResource(R.string.server_gate_load_failed),
                            detail = state.error.code,
                            onRetry = onRetry
                    )
            is ScreenLoadState.NoPermission ->
                    SlockErrorState(
                            title = stringResource(R.string.server_gate_auth_required),
                            detail = state.blocker.code,
                            onRetry = onSignOut,
                            retryLabel = stringResource(R.string.server_gate_sign_out)
                    )
        }
        SlockButton(
                text = stringResource(R.string.server_gate_create_new),
                onClick = onShowCreate,
                enabled = !submitting,
                variant = SlockButtonVariant.Pink,
                modifier = Modifier.fillMaxWidth()
        )
        SlockButton(
                text = stringResource(R.string.server_gate_sign_out),
                onClick = onSignOut,
                enabled = !submitting,
                variant = SlockButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ServerChoiceRow(
        server: ServerSummary,
        enabled: Boolean,
        onClick: () -> Unit
) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .padding(
                                    end = SlockShadow.Small.offsetX,
                                    bottom = SlockShadow.Small.offsetY
                            )
                            .slockHardShadow(SlockShadow.Small)
                            .background(SlockColor.AppBgLightBlue, RoundedCornerShape(SlockRadius.Xs))
                            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
                            .clickable(enabled = enabled, onClick = onClick)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
                text = server.name,
                style = SlockType.Button,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
        Text(
                text = server.slug?.let { "/$it" } ?: server.id.value,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ServerCreateContent(
        name: String,
        slug: String,
        submitting: Boolean,
        onNameChange: (String) -> Unit,
        onSlugChange: (String) -> Unit,
        onCreateServer: () -> Unit,
        onCancelCreate: () -> Unit,
        onSignOut: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
        SlockTextField(
                value = name,
                onValueChange = onNameChange,
                label = stringResource(R.string.server_gate_name_label),
                placeholder = stringResource(R.string.server_gate_name_placeholder),
                enabled = !submitting,
                singleLine = true,
                reserveErrorSpace = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )
        SlockTextField(
                value = slug,
                onValueChange = onSlugChange,
                label = stringResource(R.string.server_gate_slug_label),
                placeholder = stringResource(R.string.server_gate_slug_placeholder),
                enabled = !submitting,
                singleLine = true,
                reserveErrorSpace = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            SlockButton(
                    text = stringResource(R.string.server_gate_cancel),
                    onClick = onCancelCreate,
                    enabled = !submitting,
                    variant = SlockButtonVariant.Outline,
                    modifier = Modifier.weight(1f)
            )
            SlockButton(
                    text = stringResource(R.string.server_gate_create),
                    onClick = onCreateServer,
                    enabled = name.isNotBlank() && !submitting,
                    loading = submitting,
                    variant = SlockButtonVariant.Pink,
                    modifier = Modifier.weight(1f)
            )
        }
        SlockButton(
                text = stringResource(R.string.server_gate_sign_out),
                onClick = onSignOut,
                enabled = !submitting,
                variant = SlockButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth()
        )
    }
}

private enum class AppDestination(val key: String, val label: String) {
    Home("home", "Home"),
    Tasks("tasks", "Tasks"),
    Members("members", "Members"),
    Settings("settings", "Settings")
}

@Composable
private fun appBottomTabs(): List<SlockBottomTabItem> =
        listOf(
                SlockBottomTabItem(
                        AppDestination.Home.key,
                        stringResource(R.string.bottom_tab_home),
                        LucideIcons.House
                ),
                SlockBottomTabItem(
                        AppDestination.Tasks.key,
                        stringResource(R.string.bottom_tab_tasks),
                        LucideIcons.SquareCheckBig
                ),
                SlockBottomTabItem(
                        AppDestination.Members.key,
                        stringResource(R.string.bottom_tab_members),
                        LucideIcons.Users
                ),
                SlockBottomTabItem(
                        AppDestination.Settings.key,
                        stringResource(R.string.bottom_tab_settings),
                        LucideIcons.Settings
                )
        )

@Composable
private fun HomeRouteScreen(
        route: HomeRoute,
        previousRoute: HomeRoute?,
        onRouteChange: (HomeRoute) -> Unit,
        onOpenThreadFromRoute: (HomeRoute) -> Unit,
        onOpenConversationThread: (ConversationSummary, HomeRoute) -> Unit,
        onOpenThreadItem: (InboxItem) -> Unit,
        onOpenMessageThread: (Message) -> Unit,
        onOpenParentPaneMessageThread: (Message) -> Unit,
        onOpenMemberProfile: (MemberProfile) -> Unit,
        onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
        onOpenParentPaneMarkdownTag: (ThreadMarkdownTag) -> Unit,
        onOpenSlockWebLink: (String) -> Boolean,
        onOpenParentPaneSlockWebLink: (String) -> Boolean,
        onOpenTask: (TaskSummary) -> Unit,
        onOpenParentPaneTask: (TaskSummary) -> Unit,
        onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit,
        onRetryThreadTasks: () -> Unit,
        onRetryThreadFiles: () -> Unit,
        onLoadThreadFilesMore: (String?) -> Unit,
        onThreadBack: () -> Boolean,
        onJoinChannel: (ConversationSummary) -> Unit,
        onRetryFollowedThreads: () -> Unit,
        threadTab: ThreadTab,
        onThreadTabChange: (ThreadTab) -> Unit,
        composerValue: String,
        onComposerValueChange: (String) -> Unit,
        composerAsTask: Boolean,
        onComposerAsTaskChange: (Boolean) -> Unit,
        pendingAttachmentCount: Int,
        pendingAttachments: List<PendingAttachment>,
        onAttachImage: () -> Unit,
        onAttachFile: () -> Unit,
        onRemoveAttachment: (String) -> Unit,
        onRetryAttachments: () -> Unit,
        localComposerMessages: List<String>,
        realtimeThreadMessages: List<Message>,
        optimisticSendMessagesByStream: Map<String, Message>,
        serverListState: ScreenLoadState<ServerListSnapshot>,
        homeState: HomeUiState,
        draftThreadIds: Set<String>,
        inboxState: InboxUiState,
        followedThreadsState: InboxUiState,
        selectedThreadState: ThreadUiState,
        selectedThreadId: String?,
        composerFocusRequestNonce: Int,
        replyThreadBackStack: List<ReplyThreadBackStackEntry>,
        replyHeaderContext: ThreadReplyHeaderContext?,
        parentJumpTarget: ThreadParentJumpTarget?,
        onOpenParentMessage: (ThreadParentJumpTarget) -> Unit,
        threadMembersPanelState: ThreadMembersPanelState,
        archiveChannelStatus: ThreadSettingsModalStatus,
        onArchiveChannel: (ChannelId?) -> Unit,
        onDeleteChannel: (ChannelId?) -> Unit,
        onLeaveChannel: (ChannelId?) -> Unit,
        onAddChannelMember: (String, MemberKind) -> Unit,
        onRemoveChannelMember: (MemberProfile) -> Unit,
        attachmentOpenStates: Map<AttachmentId, AttachmentOpenUiState>,
        attachmentPreviewState: AttachmentPreviewUiState?,
        onThreadNearBottomChange: (Boolean) -> Unit,
        onOpenAttachment: (Attachment) -> Unit,
        onDismissAttachmentPreview: () -> Unit,
        onOpenAttachmentPreviewExternally: (AttachmentPreviewUiState) -> Unit,
        onSendParentPaneMessage: (
                ChannelId,
                String,
                Boolean,
                (DataResult<SendMessageResult>) -> Unit
        ) -> Unit,
        onParentPaneTabChange: (ThreadTab) -> Unit,
        onParentPaneComposerChange: (String) -> Unit,
        onParentPaneAsTaskChange: (Boolean) -> Unit,
        onParentPaneAttachImage: () -> Unit,
        onParentPaneAttachFile: () -> Unit,
        onParentPaneRemoveAttachment: (String) -> Unit,
        onParentPaneRetryAttachment: () -> Unit,
        onParentPaneSend: () -> Unit,
        onParentPaneLoadOlderMessages: (MessageId?, Int) -> Unit,
        onParentPaneRetryLoadOlderMessages: () -> Unit,
        onParentPaneLoadNewerMessages: () -> Unit,
        onParentPaneRetryLoadNewerMessages: () -> Unit,
        onParentPaneRefreshThread: () -> Unit,
        onParentPaneNearBottomChange: (Boolean) -> Unit,
        onRetryParentPaneTasks: () -> Unit,
        onRetryParentPaneFiles: () -> Unit,
        onLoadParentPaneFilesMore: (String?) -> Unit,
        onRetryParentPaneThreadLoad: () -> Unit,
        savedState: InboxUiState,
        searchState: InboxUiState,
        searchQuery: String,
        onSearchQueryChange: (String) -> Unit,
        homeRefreshInProgress: Boolean,
        homeRefreshError: String?,
        homeRefreshErrorLabel: String?,
        inboxRefreshInProgress: Boolean,
        followedThreadsRefreshInProgress: Boolean,
        savedRefreshInProgress: Boolean,
        searchRefreshInProgress: Boolean,
        homeNotifications: List<SlockHomeNotificationItem>,
        onHomeNotificationView: (SlockHomeNotificationItem) -> Unit,
        onHomeNotificationDismiss: (SlockHomeNotificationItem) -> Unit,
        onHomeRefresh: () -> Unit,
        serverSwitcherExpanded: Boolean,
        onServerSwitcherExpandedChange: (Boolean) -> Unit,
        onServerSelected: (String) -> Unit,
        onCreateServerClick: () -> Unit,
        onCreateChannelClick: () -> Unit,
        onSend: () -> Unit,
        onLoadOlderThreadMessages: (MessageId?, Int) -> Unit,
        onLoadNewerThreadMessages: () -> Unit,
        onRefreshThread: () -> Unit,
        onRetryThreadLoad: () -> Unit,
        currentMemberId: MemberId?,
        myMessageAlignment: MyMessageAlignmentPreference,
        onRefreshInbox: () -> Unit,
        onRefreshFollowedThreads: () -> Unit,
        onRefreshSaved: () -> Unit,
        onRefreshSearch: () -> Unit,
        onMarkAllInboxRead: () -> Unit,
        onInboxFilterSelected: (InboxFilter) -> Unit,
        onMarkInboxItemRead: (InboxItem) -> Unit,
        onSaveMessage: (Message) -> Unit,
        onRemoveSaved: (InboxItem) -> Unit,
        onLoadInboxMore: (Int?, InboxFilter) -> Unit,
        onLoadSavedMore: (Int?) -> Unit,
        onLoadSearchMore: (Int?) -> Unit,
        adaptiveLayoutInfo: SlockAdaptiveAppLayoutInfo,
        showNavigation: Boolean
) {
    if (route != HomeRoute.Shell) {
        BackHandler {
            if (route != HomeRoute.Thread || !onThreadBack()) {
                onRouteChange(
                        previousRoute?.takeUnless { it == HomeRoute.Thread } ?: HomeRoute.Shell
                )
            }
        }
    }
    when (route) {
        HomeRoute.Shell ->
                HomeShellScreen(
                        onOpenThread = { conversation ->
                            onOpenConversationThread(conversation, HomeRoute.Shell)
                        },
                        onOpenInbox = { onRouteChange(HomeRoute.Inbox) },
                        onOpenFollowedThreads = { onRouteChange(HomeRoute.FollowedThreads) },
                        onOpenSaved = { onRouteChange(HomeRoute.Saved) },
                        onOpenSearch = { onRouteChange(HomeRoute.Search) },
                        serverListState = serverListState,
                        homeState = homeState,
                        draftThreadIds = draftThreadIds,
                        inboxState = inboxState,
                        savedState = savedState,
                        notifications = homeNotifications,
                        onNotificationView = onHomeNotificationView,
                        onNotificationDismiss = onHomeNotificationDismiss,
                        selectedThreadId = selectedThreadId,
                        homeRefreshInProgress = homeRefreshInProgress,
                        homeRefreshError = homeRefreshError,
                        homeRefreshErrorLabel = homeRefreshErrorLabel,
                        onHomeRefresh = onHomeRefresh,
                        serverSwitcherExpanded = serverSwitcherExpanded,
                        onServerSwitcherExpandedChange = onServerSwitcherExpandedChange,
                        onServerSelected = onServerSelected,
                        onCreateServerClick = onCreateServerClick,
                        onCreateChannelClick = onCreateChannelClick,
                        adaptiveLayoutInfo = adaptiveLayoutInfo,
                        showNavigation = showNavigation
                )
        HomeRoute.Thread -> {
            val baseThreadState =
                    selectedThreadState.takeUnless {
                        it.loadState == ScreenLoadState.Loading && selectedThreadId == null
                    }
                            ?: ThreadUiState(ScreenLoadState.Loading)
            val threadAuthorProfiles = threadMembersPanelState.threadAuthorProfiles()
            val threadLoadState =
                    when (val loadState = baseThreadState.loadState) {
                        is ScreenLoadState.Content ->
                                ScreenLoadState.Content(
                                        loadState.data.copy(
                                                parentMessage =
                                                        loadState.data.parentMessage
                                                                ?.withThreadAuthorProfiles(
                                                                        threadAuthorProfiles
                                                                ),
                                                messages =
                                                        (loadState.data.messages +
                                                                        realtimeThreadMessages +
                                                                        listOfNotNull(
                                                                                optimisticSendMessagesByStream[
                                                                                        loadState
                                                                                                .data
                                                                                                .header
                                                                                                .messageStreamChannelId()
                                                                                                .value
                                                                                ]
                                                                        ) +
                                                                        localComposerMessages
                                                                                .toLocalThreadMessages(
                                                                                        loadState
                                                                                                .data
                                                                                ))
                                                                .distinctBy { it.id }
                                                                .withThreadAuthorProfiles(
                                                                        threadAuthorProfiles
                                                                )
                                                                .sortedForThreadTimeline()
                                        )
                                )
                        else -> loadState
                    }
            val enrichedReplyHeaderContext =
                    replyHeaderContext?.withThreadAuthorProfiles(threadAuthorProfiles)
            val retryLoadOlderThreadMessagesHandler: () -> Unit = {
                onLoadOlderThreadMessages(null, 0)
            }
            val threadScreenState =
                    baseThreadState.copy(
                            loadState = threadLoadState,
                            selectedTab = threadTab,
                            composerState =
                                    baseThreadState.composerState.copy(
                                            value = composerValue,
                                            asTask = composerAsTask,
                                            attachmentIds =
                                                    List(pendingAttachmentCount) { index ->
                                                        AttachmentId("pending-attachment-$index")
                                                    },
                                            pendingAttachments = pendingAttachments,
                                            status =
                                                    baseThreadState.composerState.status
                                                            .fromComposerValue(
                                                                    value = composerValue,
                                                                    hasAttachments =
                                                                            pendingAttachmentCount >
                                                                                    0
                                                            )
                                    )
                    )
            val parentReplyEntry = replyThreadBackStack.lastOrNull()
            val wideReplyPane =
                    adaptiveLayoutInfo.mode != SlockWindowLayoutMode.CompactSinglePane &&
                            parentReplyEntry != null &&
                            threadTab == ThreadTab.Chat
            if (wideReplyPane) {
                val parentThreadContent =
                        (parentReplyEntry.selectedThreadState.loadState as? ScreenLoadState.Content)
                                ?.data
                val parentThreadHeader = parentThreadContent?.header
                val parentPaneComposerState =
                        (parentThreadHeader?.toComposerState() ?: parentReplyEntry.selectedThreadState
                                .composerState)
                                .copy(
                                        value = parentReplyEntry.composerValue,
                                        asTask = parentReplyEntry.composerAsTask,
                                        attachmentIds =
                                                List(parentReplyEntry.pendingAttachmentFiles.size) {
                                                    index ->
                                                    AttachmentId("parent-pane-attachment-$index")
                                                },
                                        pendingAttachments =
                                                parentReplyEntry.pendingAttachmentFiles
                                                        .toPendingAttachments(
                                                                status =
                                                                        when (parentReplyEntry
                                                                                        .selectedThreadState
                                                                                        .composerState
                                                                                        .status) {
                                                                            ComposerStatus.Uploading ->
                                                                                    PendingAttachmentStatus
                                                                                            .Uploading
                                                                            else ->
                                                                                    PendingAttachmentStatus
                                                                                            .Ready
                                                                        },
                                                                failedIds =
                                                                        parentReplyEntry
                                                                                .failedAttachmentIds
                                                        ),
                                        status = parentReplyEntry.selectedThreadState.composerState
                                                .status
                                                .fromComposerValue(
                                                        value = parentReplyEntry.composerValue,
                                                        hasAttachments =
                                                                parentReplyEntry
                                                                        .pendingAttachmentFiles
                                                                        .isNotEmpty()
                                                ),
                                        errorCode =
                                                parentReplyEntry.selectedThreadState.composerState
                                                        .errorCode,
                                        noPermissionCode =
                                                parentReplyEntry.selectedThreadState.composerState
                                                        .noPermissionCode
                                                        ?: parentThreadHeader
                                                                ?.toComposerState()
                                                                ?.noPermissionCode
                                )
                Row(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
                    ThreadScreen(
                            state =
                                    parentReplyEntry.selectedThreadState.copy(
                                            selectedTab = parentReplyEntry.selectedTab,
                                            composerState = parentPaneComposerState
                                    ),
                            modifier =
                                    Modifier.weight(0.46f)
                                            .fillMaxSize()
                                            .widePaneSwipe(
                                                    enabled = true,
                                                    onSwipeLeft = {},
                                                    onSwipeRight = {}
                                            ),
                            onBack = {
                                onRouteChange(
                                        previousRoute?.takeUnless { it == HomeRoute.Thread }
                                                ?: HomeRoute.Shell
                                )
                            },
                            onModeSelected = onParentPaneTabChange,
                            onComposerChange = onParentPaneComposerChange,
                            onAsTaskChange = onParentPaneAsTaskChange,
                            onAttachImage = onParentPaneAttachImage,
                            onAttachFile = onParentPaneAttachFile,
                            onRemoveAttachment = onParentPaneRemoveAttachment,
                            onRetryAttachment = onParentPaneRetryAttachment,
                            onSend = onParentPaneSend,
                            onLoadOlderMessages = onParentPaneLoadOlderMessages,
                            onRetryLoadOlderMessages = onParentPaneRetryLoadOlderMessages,
                            onLoadNewerMessages = onParentPaneLoadNewerMessages,
                            onRetryLoadNewerMessages = onParentPaneRetryLoadNewerMessages,
                            onRefreshThread = onParentPaneRefreshThread,
                            onNearBottomChange = onParentPaneNearBottomChange,
                            onOpenMessageThread = onOpenParentPaneMessageThread,
                            onOpenMemberProfile = onOpenMemberProfile,
                            onOpenMarkdownTag = onOpenParentPaneMarkdownTag,
                            onOpenSlockWebLink = onOpenParentPaneSlockWebLink,
                            onOpenTask = onOpenParentPaneTask,
                            onTaskStatusChange = onTaskStatusChange,
                            onSaveMessage = onSaveMessage,
                            onRetryTasks = onRetryParentPaneTasks,
                            onRetryFiles = onRetryParentPaneFiles,
                            onLoadMoreFiles = {
                                onLoadParentPaneFilesMore(
                                        parentReplyEntry.selectedThreadState.filesState?.nextCursor
                                )
                            },
                            currentMemberId = currentMemberId,
                            myMessageAlignment = myMessageAlignment,
                            parentJumpTarget = null,
                            onOpenParentMessage = onOpenParentMessage,
                            membersPanelState = threadMembersPanelState,
                            onAddChannelMember = onAddChannelMember,
                            onRemoveChannelMember = onRemoveChannelMember,
                            archiveChannelStatus = archiveChannelStatus,
                            onArchiveChannel = {
                                onArchiveChannel(parentReplyEntry.selectedThreadState.threadChannelId())
                            },
                            onDeleteChannel = {
                                onDeleteChannel(parentReplyEntry.selectedThreadState.threadChannelId())
                            },
                            onLeaveChannel = {
                                onLeaveChannel(parentReplyEntry.selectedThreadState.threadChannelId())
                            },
                            onOpenAttachment = onOpenAttachment,
                            onDismissAttachmentPreview = onDismissAttachmentPreview,
                            onOpenAttachmentPreviewExternally =
                                    onOpenAttachmentPreviewExternally,
                            onJoinChannel = onJoinChannel,
                            onRetryThreadLoad = onRetryParentPaneThreadLoad,
                            showComposer = true,
                            showReplyActions = true,
                            fallbackComposerHeader = parentThreadHeader,
                            selectedThreadId =
                                    parentReplyEntry.selectedThreadId?.let(::ThreadId)
                                            ?: parentThreadHeader?.threadId
                    )
                    Box(
                            modifier =
                                    Modifier.fillMaxSize()
                                            .weight(0.54f)
                    ) {
                        ThreadScreen(
                                state = threadScreenState,
                                onBack = {
                                    if (!onThreadBack()) {
                                        onRouteChange(
                                                previousRoute?.takeUnless {
                                                    it == HomeRoute.Thread
                                                } ?: HomeRoute.Shell
                                        )
                                    }
                                },
                                onModeSelected = onThreadTabChange,
                                onComposerChange = onComposerValueChange,
                                onAsTaskChange = onComposerAsTaskChange,
                                onAttachImage = onAttachImage,
                                onAttachFile = onAttachFile,
                                onRemoveAttachment = onRemoveAttachment,
                                onRetryAttachment = onRetryAttachments,
                                onSend = onSend,
                                onLoadOlderMessages = onLoadOlderThreadMessages,
                                onRetryLoadOlderMessages = retryLoadOlderThreadMessagesHandler,
                                onLoadNewerMessages = onLoadNewerThreadMessages,
                                onRetryLoadNewerMessages = onLoadNewerThreadMessages,
                                onRefreshThread = onRefreshThread,
                                onNearBottomChange = onThreadNearBottomChange,
                                onOpenMessageThread = { onOpenMessageThread(it) },
                                onOpenMemberProfile = onOpenMemberProfile,
                                onOpenMarkdownTag = onOpenMarkdownTag,
                                onOpenSlockWebLink = onOpenSlockWebLink,
                                onOpenTask = onOpenTask,
                                onTaskStatusChange = onTaskStatusChange,
                                onSaveMessage = onSaveMessage,
                                onRetryTasks = onRetryThreadTasks,
                                onRetryFiles = onRetryThreadFiles,
                                onLoadMoreFiles = {
                                    onLoadThreadFilesMore(threadScreenState.filesState?.nextCursor)
                                },
                                attachmentOpenStates = attachmentOpenStates,
                                attachmentPreviewState = attachmentPreviewState,
                                onOpenAttachment = onOpenAttachment,
                                onDismissAttachmentPreview = onDismissAttachmentPreview,
                                onOpenAttachmentPreviewExternally =
                                        onOpenAttachmentPreviewExternally,
                                onJoinChannel = onJoinChannel,
                                membersPanelState = threadMembersPanelState,
                                onAddChannelMember = onAddChannelMember,
                                onRemoveChannelMember = onRemoveChannelMember,
                                archiveChannelStatus = archiveChannelStatus,
                                onArchiveChannel = { onArchiveChannel(threadScreenState.threadChannelId()) },
                                onDeleteChannel = { onDeleteChannel(threadScreenState.threadChannelId()) },
                                onLeaveChannel = { onLeaveChannel(threadScreenState.threadChannelId()) },
                                currentMemberId = currentMemberId,
                                myMessageAlignment = myMessageAlignment,
                                replyHeaderContext = enrichedReplyHeaderContext,
                                parentJumpTarget = parentJumpTarget,
                                onOpenParentMessage = onOpenParentMessage,
                                onRetryThreadLoad = onRetryThreadLoad,
                                headerNavigationIcon = ThreadHeaderNavigationIcon.Close,
                                showTopBorder = false,
                                showConversationActions = false,
                                fallbackComposerHeader =
                                        (threadScreenState.loadState as? ScreenLoadState.Content)
                                                ?.data
                                                ?.header,
                                selectedThreadId = selectedThreadId?.let(::ThreadId),
                                composerFocusRequestNonce = composerFocusRequestNonce,
                                channelSuggestions =
                                        (homeState.loadState as? ScreenLoadState.Content)
                                                ?.data?.channels.orEmpty()
                        )
                    }
                }
            } else {
                ThreadScreen(
                    state =
                            threadScreenState,
                    onBack = {
                        if (!onThreadBack()) {
                            onRouteChange(
                                    previousRoute?.takeUnless { it == HomeRoute.Thread }
                                            ?: HomeRoute.Shell
                            )
                        }
                    },
                    onModeSelected = onThreadTabChange,
                    onComposerChange = onComposerValueChange,
                    onAsTaskChange = onComposerAsTaskChange,
                    onAttachImage = onAttachImage,
                    onAttachFile = onAttachFile,
                    onRemoveAttachment = onRemoveAttachment,
                    onRetryAttachment = onRetryAttachments,
                    onSend = onSend,
                    onLoadOlderMessages = onLoadOlderThreadMessages,
                    onRetryLoadOlderMessages = retryLoadOlderThreadMessagesHandler,
                    onLoadNewerMessages = onLoadNewerThreadMessages,
                    onRetryLoadNewerMessages = onLoadNewerThreadMessages,
                    onRefreshThread = onRefreshThread,
                    onNearBottomChange = onThreadNearBottomChange,
                    onOpenMessageThread = { onOpenMessageThread(it) },
                    onOpenMemberProfile = onOpenMemberProfile,
                    onOpenMarkdownTag = onOpenMarkdownTag,
                    onOpenSlockWebLink = onOpenSlockWebLink,
                    onOpenTask = onOpenTask,
                    onTaskStatusChange = onTaskStatusChange,
                    onSaveMessage = onSaveMessage,
                    onRetryTasks = onRetryThreadTasks,
                    onRetryFiles = onRetryThreadFiles,
                    onLoadMoreFiles = {
                        onLoadThreadFilesMore(threadScreenState.filesState?.nextCursor)
                    },
                    attachmentOpenStates = attachmentOpenStates,
                    attachmentPreviewState = attachmentPreviewState,
                    onOpenAttachment = onOpenAttachment,
                    onDismissAttachmentPreview = onDismissAttachmentPreview,
                    onOpenAttachmentPreviewExternally = onOpenAttachmentPreviewExternally,
                    onJoinChannel = onJoinChannel,
                    membersPanelState = threadMembersPanelState,
                    onAddChannelMember = onAddChannelMember,
                    onRemoveChannelMember = onRemoveChannelMember,
                    archiveChannelStatus = archiveChannelStatus,
                    onArchiveChannel = { onArchiveChannel(threadScreenState.threadChannelId()) },
                    onDeleteChannel = { onDeleteChannel(threadScreenState.threadChannelId()) },
                    onLeaveChannel = { onLeaveChannel(threadScreenState.threadChannelId()) },
                    currentMemberId = currentMemberId,
                    myMessageAlignment = myMessageAlignment,
                    replyHeaderContext = enrichedReplyHeaderContext,
                    parentJumpTarget = parentJumpTarget,
                    onOpenParentMessage = onOpenParentMessage,
                    onRetryThreadLoad = onRetryThreadLoad,
                    fallbackComposerHeader =
                            (threadScreenState.loadState as? ScreenLoadState.Content)
                                    ?.data
                                    ?.header,
                    selectedThreadId = selectedThreadId?.let(::ThreadId),
                    composerFocusRequestNonce = composerFocusRequestNonce,
                    channelSuggestions =
                            (homeState.loadState as? ScreenLoadState.Content)
                                    ?.data?.channels.orEmpty()
                )
            }
        }
        HomeRoute.Inbox ->
                InboxScreen(
                        state = inboxState,
                        onBack = { onRouteChange(HomeRoute.Shell) },
                        onOpenThread = {
                            onOpenThreadItem(it)
                            onOpenThreadFromRoute(HomeRoute.Inbox)
                        },
                        onFilterSelected = onInboxFilterSelected,
                        onMarkItemRead = onMarkInboxItemRead,
                        onMarkAllRead = onMarkAllInboxRead,
                        onLoadMore = {
                            onLoadInboxMore(inboxState.nextOffset, inboxState.selectedFilter)
                        },
                        refreshing = inboxRefreshInProgress,
                        onRefresh = onRefreshInbox
                )
        HomeRoute.FollowedThreads ->
                FollowedThreadsScreen(
                        state = followedThreadsState,
                        onBack = { onRouteChange(HomeRoute.Shell) },
                        onOpenThread = {
                            onOpenThreadItem(it)
                            onOpenThreadFromRoute(HomeRoute.FollowedThreads)
                        },
                        refreshing = followedThreadsRefreshInProgress,
                        onRefresh = onRefreshFollowedThreads,
                        onRetry = onRetryFollowedThreads
                )
        HomeRoute.Saved ->
                SavedScreen(
                        state = savedState,
                        onBack = { onRouteChange(HomeRoute.Shell) },
                        onOpenThread = {
                            onOpenThreadItem(it)
                            onOpenThreadFromRoute(HomeRoute.Saved)
                        },
                        onRemoveSaved = onRemoveSaved,
                        onLoadMore = { onLoadSavedMore(savedState.nextOffset) },
                        refreshing = savedRefreshInProgress,
                        onRefresh = onRefreshSaved
                )
        HomeRoute.Search ->
                SearchResultsScreen(
                        state = searchState,
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        onBack = { onRouteChange(HomeRoute.Shell) },
                        onOpenThread = {
                            onOpenThreadItem(it)
                            onOpenThreadFromRoute(HomeRoute.Search)
                        },
                        onLoadMore = { onLoadSearchMore(searchState.nextOffset) },
                        refreshing = searchRefreshInProgress,
                        onRefresh = onRefreshSearch
                )
    }
}

@Composable
private fun SearchResultsScreen(
        state: InboxUiState,
        query: String,
        onQueryChange: (String) -> Unit,
        onBack: () -> Unit,
        onOpenThread: (InboxItem) -> Unit,
        onLoadMore: () -> Unit,
        refreshing: Boolean,
        onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SettingsDetailHeader(
                title = stringResource(R.string.home_search),
                onBack = onBack
        )
        SlockTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.home_search),
                singleLine = true,
                reserveErrorSpace = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier =
                        Modifier.fillMaxWidth()
                                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Md)
        )
        SearchResultsListScreen(
                state = state,
                onOpenThread = onOpenThread,
                onLoadMore = onLoadMore,
                refreshing = refreshing,
                onRefresh = onRefresh
        )
    }
}

private val localComposerAuthor =
        MemberProfile(
                id = MemberId("local-current-user"),
                displayName = "artin",
                handle = "@artin",
                kind = MemberKind.Human,
                roleDescription = "Current user",
                avatarLabel = "A",
                presence = Presence.Active
        )

private fun optimisticMessage(
        streamChannelId: ChannelId,
        draft: ComposerSubmissionDraft,
        deliveryState: MessageDeliveryState = MessageDeliveryState.Sending
): Message =
        Message(
                id = MessageId("local-send-${UUID.randomUUID()}"),
                threadId = ThreadId(streamChannelId.value),
                channelId = streamChannelId,
                author = localComposerAuthor,
                timestampLabel = "Now",
                body = draft.body,
                attachments = draft.optimisticAttachments(),
                deliveryState = deliveryState
        )

private fun ComposerSubmissionDraft.optimisticAttachments(): List<Attachment> =
        attachments.map { file ->
            Attachment(
                    id = file.optimisticAttachmentId(),
                    name = file.fileName,
                    mimeType = file.mimeType.orEmpty(),
                    sizeLabel = file.sizeBytes?.let(::formatFileSize).orEmpty(),
                    thumbnailUrl = file.previewUri
            )
        }

private fun ComposerSubmissionDraft.optimisticAttachmentIds(): Set<AttachmentId> =
        attachments.map { it.optimisticAttachmentId() }.toSet()

private fun ComposerSubmissionDraft.optimisticAttachmentOpenStates(
        status: AttachmentOpenStatus,
        errorCode: String? = null
): Map<AttachmentId, AttachmentOpenUiState> =
        optimisticAttachmentIds().associateWith {
            AttachmentOpenUiState(status = status, errorCode = errorCode)
        }

private fun AttachmentUploadFile.optimisticAttachmentId(): AttachmentId =
        AttachmentId("local-upload-$id")

private fun List<String>.toLocalThreadMessages(content: ThreadContent): List<Message> {
    return mapIndexed { index, body ->
        Message(
                id = MessageId("local-composer-message-$index"),
                threadId = content.header.threadId,
                channelId = content.header.channelId,
                seq = 10_000L + index,
                author = localComposerAuthor,
                timestampLabel = "Now",
                body = body
        )
    }
}

private fun ThreadUiState.withParentMessage(parentMessage: Message): ThreadUiState =
        copy(
                loadState =
                        (loadState as? ScreenLoadState.Content)?.let { contentState ->
                            ScreenLoadState.Content(
                                    contentState.data.copy(parentMessage = parentMessage)
                            )
                        } ?: loadState
        )

private enum class HomeRoute {
    Shell,
    Thread,
    Inbox,
    FollowedThreads,
    Saved,
    Search
}

private fun settingsComputersToHomeNotificationItems(
        snapshot: SettingsComputersSnapshot,
        dismissedIds: Set<String>
): List<SlockHomeNotificationItem> {
    val latestVersion = snapshot.latestDaemonVersion?.takeIf { it.isNotBlank() } ?: return emptyList()
    val outdatedComputers =
            snapshot.computers.filter { computer ->
                computer.online && computer.daemonVersion.isOlderDaemonVersionThan(latestVersion)
            }
    if (outdatedComputers.isEmpty()) return emptyList()
    val machineIds = outdatedComputers.map { it.id }.sorted().joinToString(",")
    val notificationId = "machine-outdated:$latestVersion:$machineIds"
    if (notificationId in dismissedIds) return emptyList()
    val names = outdatedComputers.joinToString(", ") { it.name }
    return listOf(
            SlockHomeNotificationItem(
                    id = notificationId,
                    title =
                            if (outdatedComputers.size == 1) {
                                "$names is running an outdated daemon"
                            } else {
                                "$names are running outdated daemons"
                            },
                    body = "Stop the daemon and reconnect to update.",
                    actionLabel = "View",
                    targetId = outdatedComputers.firstOrNull()?.id,
                    kind = SlockHomeNotificationKind.Warning
            )
    )
}

private fun String.isOlderDaemonVersionThan(latestVersion: String): Boolean {
    val currentParts = semanticVersionParts() ?: return false
    val latestParts = latestVersion.semanticVersionParts() ?: return false
    repeat(3) { index ->
        val current = currentParts.getOrElse(index) { 0 }
        val latest = latestParts.getOrElse(index) { 0 }
        if (current < latest) return true
        if (current > latest) return false
    }
    return false
}

private fun String.semanticVersionParts(): List<Int>? =
        DaemonVersionRegex.find(this)
                ?.value
                ?.split(".")
                ?.mapNotNull(String::toIntOrNull)
                ?.takeIf { it.isNotEmpty() }

private val DaemonVersionRegex = Regex("""\d+(?:\.\d+){0,2}""")

private fun Modifier.widePaneSwipe(
        enabled: Boolean,
        onSwipeLeft: () -> Unit,
        onSwipeRight: () -> Unit
): Modifier =
        if (!enabled) {
            this
        } else {
            pointerInput(onSwipeLeft, onSwipeRight) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        when {
                            overSlop < 0f -> onSwipeLeft()
                            overSlop > 0f -> onSwipeRight()
                        }
                        change.consume()
                    }
                }
            }
        }

private data class ReplyThreadBackStackEntry(
        val selectedThreadId: String?,
        val selectedThreadState: ThreadUiState,
        val selectedTab: ThreadTab,
        val previousHomeRoute: HomeRoute?,
        val parentMessageId: MessageId,
        val localComposerMessages: List<String>,
        val realtimeThreadMessages: List<Message>,
        val attachmentOpenStates: Map<AttachmentId, AttachmentOpenUiState>,
        val composerValue: String,
        val composerAsTask: Boolean,
        val pendingAttachmentFiles: List<AttachmentUploadFile>,
        val failedAttachmentIds: Set<String>,
        val parentJumpTarget: String? = null
)

private fun ReplyThreadBackStackEntry.toThreadReplyHeaderContext(): ThreadReplyHeaderContext? {
    val content = (selectedThreadState.loadState as? ScreenLoadState.Content)?.data ?: return null
    val parentMessage =
            content.messages.firstOrNull { it.id == parentMessageId }
                    ?: content.parentMessage?.takeIf { it.id == parentMessageId }
    return ThreadReplyHeaderContext(
            parentChannelName = content.header.name.takeIf { it.isNotBlank() },
            parentMessagePreview = parentMessage?.task?.title
                            ?: parentMessage?.body?.takeIf { it.isNotBlank() }
                                    ?: content.header.lastMessagePreview,
            parentMessage = parentMessage,
            parentMessageId = parentMessageId
    )
}

private fun ThreadUiState.toThreadReplyHeaderContext(
        parentMessageId: MessageId,
        refreshing: Boolean = false,
        refreshErrorCode: String? = null
): ThreadReplyHeaderContext? {
    val content = (loadState as? ScreenLoadState.Content)?.data ?: return null
    val parentMessage =
            content.messages.firstOrNull { it.id == parentMessageId }
                    ?: content.parentMessage?.takeIf { it.id == parentMessageId }
    return ThreadReplyHeaderContext(
            parentChannelName = content.header.name.takeIf { it.isNotBlank() },
            parentMessagePreview = parentMessage?.task?.title
                            ?: parentMessage?.body?.takeIf { it.isNotBlank() }
                                    ?: content.header.lastMessagePreview,
            parentMessage = parentMessage,
            parentMessageId = parentMessageId,
            parentMessageRefreshing = refreshing,
            parentMessageRefreshErrorCode = refreshErrorCode
    )
}

private fun ThreadReplyHeaderContext.withThreadAuthorProfiles(
        profiles: List<MemberProfile>
): ThreadReplyHeaderContext {
    if (profiles.isEmpty()) return this
    return copy(parentMessage = parentMessage?.withThreadAuthorProfiles(profiles))
}

@Composable
private fun HomeShellScreen(
        onOpenThread: (ConversationSummary) -> Unit,
        onOpenInbox: () -> Unit,
        onOpenFollowedThreads: () -> Unit,
        onOpenSaved: () -> Unit,
        onOpenSearch: () -> Unit,
        serverListState: ScreenLoadState<ServerListSnapshot>,
        homeState: HomeUiState,
        draftThreadIds: Set<String>,
        inboxState: InboxUiState,
        savedState: InboxUiState,
        notifications: List<SlockHomeNotificationItem>,
        onNotificationView: (SlockHomeNotificationItem) -> Unit,
        onNotificationDismiss: (SlockHomeNotificationItem) -> Unit,
        selectedThreadId: String?,
        homeRefreshInProgress: Boolean,
        homeRefreshError: String?,
        homeRefreshErrorLabel: String?,
        onHomeRefresh: () -> Unit,
        serverSwitcherExpanded: Boolean,
        onServerSwitcherExpandedChange: (Boolean) -> Unit,
        onServerSelected: (String) -> Unit,
        onCreateServerClick: () -> Unit,
        onCreateChannelClick: () -> Unit,
        adaptiveLayoutInfo: SlockAdaptiveAppLayoutInfo,
        showNavigation: Boolean
) {
    val currentHomeContent = (homeState.loadState as? ScreenLoadState.Content)?.data
            ?.withDraftIndicators(draftThreadIds)
    val content =
            currentHomeContent
                    ?: fallbackHomeContent(
                            serverListState = serverListState,
                            selectedServerId = homeState.selectedServerId
                    )
    HomeStructuralContent(
            onOpenThread = onOpenThread,
            onOpenInbox = onOpenInbox,
            onOpenFollowedThreads = onOpenFollowedThreads,
            onOpenSaved = onOpenSaved,
            onOpenSearch = onOpenSearch,
            serverListState = serverListState,
            content = content,
            inboxState = inboxState,
            savedState = savedState,
            notifications = notifications,
            onNotificationView = onNotificationView,
            onNotificationDismiss = onNotificationDismiss,
            selectedThreadId = selectedThreadId ?: homeState.selectedThreadId,
            homeRefreshInProgress =
                    homeRefreshInProgress || homeState.loadState == ScreenLoadState.Loading,
            homeRefreshError = homeRefreshError,
            homeRefreshErrorLabel = homeRefreshErrorLabel,
            onHomeRefresh = onHomeRefresh,
            serverSwitcherExpanded = serverSwitcherExpanded,
            onServerSwitcherExpandedChange = onServerSwitcherExpandedChange,
            onServerSelected = onServerSelected,
            onCreateServerClick = onCreateServerClick,
            onCreateChannelClick = onCreateChannelClick,
            adaptiveLayoutInfo = adaptiveLayoutInfo,
            showNavigation = showNavigation
    )
}

@Composable
private fun fallbackHomeContent(
        serverListState: ScreenLoadState<ServerListSnapshot>,
        selectedServerId: String?
): HomeContent {
    val selectedServer =
            (serverListState as? ScreenLoadState.Content)?.data?.let { snapshot ->
                snapshot.selectedServer ?: snapshot.servers.firstOrNull()
            }
    val server =
            selectedServer
                    ?: ServerSummary(
                            id = ServerId(selectedServerId ?: "pending-server"),
                            name =
                                    when (serverListState) {
                                        ScreenLoadState.Loading ->
                                                stringResource(R.string.home_server_loading)
                                        is ScreenLoadState.Empty ->
                                                stringResource(R.string.home_server_empty)
                                        is ScreenLoadState.Error ->
                                                stringResource(R.string.home_server_error)
                                        is ScreenLoadState.NoPermission ->
                                                stringResource(R.string.home_server_auth_required)
                                        is ScreenLoadState.Content ->
                                                stringResource(R.string.home_server_fallback)
                                    }
                    )
    return HomeContent(
            server = server,
            currentMember = localComposerAuthor,
            channels = emptyList(),
            directMessages = emptyList(),
            unreadTotal = 0,
            serverUnreadCount = 0
    )
}

@Composable
private fun HomeStructuralContent(
        onOpenThread: (ConversationSummary) -> Unit,
        onOpenInbox: () -> Unit,
        onOpenFollowedThreads: () -> Unit,
        onOpenSaved: () -> Unit,
        onOpenSearch: () -> Unit,
        serverListState: ScreenLoadState<ServerListSnapshot>,
        content: HomeContent,
        inboxState: InboxUiState,
        savedState: InboxUiState,
        notifications: List<SlockHomeNotificationItem>,
        onNotificationView: (SlockHomeNotificationItem) -> Unit,
        onNotificationDismiss: (SlockHomeNotificationItem) -> Unit,
        selectedThreadId: String?,
        homeRefreshInProgress: Boolean,
        homeRefreshError: String?,
        homeRefreshErrorLabel: String?,
        onHomeRefresh: () -> Unit,
        serverSwitcherExpanded: Boolean,
        onServerSwitcherExpandedChange: (Boolean) -> Unit,
        onServerSelected: (String) -> Unit,
        onCreateServerClick: () -> Unit,
        onCreateChannelClick: () -> Unit,
        adaptiveLayoutInfo: SlockAdaptiveAppLayoutInfo,
        showNavigation: Boolean
) {
    val serverSnapshot = (serverListState as? ScreenLoadState.Content)?.data
    val servers = serverSnapshot?.servers?.map { it.toSwitcherItem() }.orEmpty()
    val selectedServer = serverSnapshot?.selectedServer ?: serverSnapshot?.servers?.firstOrNull()
    val appBottomBarContentPadding =
            SlockAdaptiveBottomContentPadding(
                    layoutInfo = adaptiveLayoutInfo,
                    showNavigation = showNavigation
            )
    val currentServerName =
            selectedServer?.name
                    ?: when (serverListState) {
                        ScreenLoadState.Loading -> stringResource(R.string.home_server_loading)
                        is ScreenLoadState.Empty -> stringResource(R.string.home_server_empty)
                        is ScreenLoadState.Error -> stringResource(R.string.home_server_error)
                        is ScreenLoadState.NoPermission ->
                                stringResource(R.string.home_server_auth_required)
                        is ScreenLoadState.Content -> stringResource(R.string.home_server_fallback)
                    }
    Box(modifier = Modifier.fillMaxSize()) {
        SlockStructuralHomeShell(
                currentServerName = currentServerName,
                currentServerId = selectedServer?.id?.value.orEmpty(),
                servers = servers,
                channels = content.channels.map { it.toHomeConversationItem() },
                directMessages = content.directMessages.map { it.toHomeConversationItem() },
                selectedChannelId = selectedThreadId
                                ?: content.channels.firstOrNull()?.threadId?.value,
                serverUnreadCount = content.serverUnreadCount,
                inboxUnreadCount = content.serverUnreadCount,
                savedCount = savedState.visibleTotalCount() ?: 0,
                notifications = notifications,
                serverSwitcherExpanded = serverSwitcherExpanded,
                contentRefreshing = homeRefreshInProgress,
                contentRefreshEnabled = homeRefreshError == null,
                showBottomBar = false,
                bottomContentPadding = appBottomBarContentPadding,
                outerPadding = 0.dp,
                onContentRefresh = onHomeRefresh,
                onServerBadgeClick = { onServerSwitcherExpandedChange(!serverSwitcherExpanded) },
                onServerSelected = { id -> onServerSelected(id) },
                onCreateServerClick = { onCreateServerClick() },
                onCreateChannelClick = { onCreateChannelClick() },
                onInboxClick = { onOpenInbox() },
                onFollowedThreadsClick = { onOpenFollowedThreads() },
                onSavedClick = { onOpenSaved() },
                onSearchClick = { onOpenSearch() },
                onNotificationView = onNotificationView,
                onNotificationDismiss = onNotificationDismiss,
                onChannelClick = { content.findConversation(it)?.let(onOpenThread) }
        )
        if (homeRefreshError != null) {
            HomeRefreshErrorStrip(
                    errorCode = homeRefreshError,
                    errorLabel = homeRefreshErrorLabel,
                    onRetry = onHomeRefresh,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
            )
        }
    }
}

@Composable
private fun HomeRefreshErrorStrip(
        errorCode: String?,
        errorLabel: String?,
        onRetry: () -> Unit,
        modifier: Modifier = Modifier
) {
    Row(
            modifier =
                    modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite.copy(alpha = 0.96f))
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
                text = stringResource(R.string.home_refresh_failed),
                style = SlockType.SectionLabel,
                color = SlockColor.TextBlack
        )
        Text(
                text =
                        stringResource(
                                R.string.home_refresh_failed_detail,
                                errorLabel ?: errorCode ?: stringResource(R.string.common_error)
                        ),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
        )
        SlockButton(
                text = stringResource(R.string.tasks_retry),
                onClick = onRetry,
                variant = SlockButtonVariant.Outline
        )
    }
}

@Composable
private fun CreateChannelMemberSection(
        title: String,
        members: List<MemberProfile>,
        selectedIds: List<String>,
        enabled: Boolean,
        membersLoadState: ScreenLoadState<MembersContent>,
        modifier: Modifier = Modifier,
        emptyText: String? = null,
        onSelectionChange: (String, Boolean) -> Unit
) {
    val pickerMembers = members.map { it.toSlockMemberPickerItem().withStableCreateChannelId() }
    val selectedIdSet =
            selectedIds.map { memberId ->
                val kind =
                        members.firstOrNull { it.id.value == memberId }?.kind ?: MemberKind.Human
                kind.name + ":" + memberId
            }.toSet()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
        when (membersLoadState) {
            ScreenLoadState.Loading -> {
                Text(text = title, style = SlockType.SectionLabel, color = SlockColor.TextMutedGray)
                SlockMemberPickerMessage(
                        text = stringResource(R.string.home_create_channel_members_loading)
                )
            }
            is ScreenLoadState.Error -> {
                Text(text = title, style = SlockType.SectionLabel, color = SlockColor.TextMutedGray)
                SlockMemberPickerMessage(
                        text =
                                stringResource(
                                        R.string.home_create_channel_members_error,
                                        membersLoadState.error.code
                                )
                )
            }
            is ScreenLoadState.NoPermission -> {
                Text(text = title, style = SlockType.SectionLabel, color = SlockColor.TextMutedGray)
                SlockMemberPickerMessage(
                        text =
                                stringResource(
                                        R.string.home_create_channel_members_error,
                                        membersLoadState.blocker.code
                                )
                )
            }
            is ScreenLoadState.Empty,
            is ScreenLoadState.Content -> {
                SlockMemberPickerSection(
                        label = title,
                        members = pickerMembers,
                        selectedIds = selectedIdSet,
                        enabled = enabled,
                        emptyText = emptyText ?: stringResource(R.string.home_create_channel_no_members),
                        selectedText = stringResource(R.string.home_create_channel_member_selected),
                        disabledText = stringResource(R.string.home_create_channel_member_joined),
                        availableText = stringResource(R.string.home_create_channel_member_add),
                        onMemberSelected = { member ->
                            onSelectionChange(member.id, member.id !in selectedIdSet)
                        }
                )
            }
        }
    }
}

private fun SlockMemberPickerItem.withStableCreateChannelId(): SlockMemberPickerItem =
        copy(
                id =
                        when (kind) {
                            MemberKind.Agent -> MemberKind.Agent.name + ":" + id
                            MemberKind.Human -> MemberKind.Human.name + ":" + id
                            MemberKind.System -> MemberKind.System.name + ":" + id
                        }
        )

private fun String.removeCreateChannelKindPrefix(): String =
        substringAfter(":", this)

@Composable
private fun TaskReferenceChooserModal(
        label: String,
        tasks: List<TaskSummary>,
        onTaskSelected: (TaskSummary) -> Unit,
        onDismiss: () -> Unit
) {
    SlockModal(
            title = "Choose task",
            onDismiss = onDismiss,
            secondaryAction =
                    SlockModalAction(
                            text = "Cancel",
                            onClick = onDismiss,
                            style = SlockModalActionStyle.Outline
                    )
    ) {
        Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                    text = "$label matches ${tasks.size} tasks. Check status, update time, channel, and assignee before choosing.",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
            )
            tasks.forEach { task ->
                TaskReferenceChooserRow(task = task, onClick = { onTaskSelected(task) })
            }
        }
    }
}

@Composable
private fun TaskReferenceChooserRow(task: TaskSummary, onClick: () -> Unit) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
                            .border(
                                    SlockStroke.Default,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .clickable(onClick = onClick)
                            .padding(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        TaskReferenceStatusBadge(status = task.status)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                    text = task.taskReferenceChooserTitle(),
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
            )
            Text(
                    text = task.taskReferenceChooserMetaLine(),
                    style = SlockType.Caption,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            val contextLine = task.taskReferenceChooserContextLine()
            if (contextLine.isNotBlank()) {
                Text(
                        text = contextLine,
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TaskReferenceStatusBadge(status: TaskStatus) {
    Box(
            modifier =
                    Modifier.size(30.dp)
                            .background(status.taskReferenceColor, RoundedCornerShape(SlockRadius.Xs))
                            .border(
                                    SlockStroke.Default,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Xs)
                            ),
            contentAlignment = Alignment.Center
    ) {
        Icon(
                imageVector = status.taskReferenceIcon,
                contentDescription = status.taskReferenceLabel,
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(16.dp)
        )
    }
}

internal fun TaskSummary.taskReferenceChooserTitle(): String =
        listOfNotNull(taskNumber?.let { "#$it" }, title.takeIf { it.isNotBlank() })
                .joinToString(" ")

internal fun TaskSummary.taskReferenceChooserMetaLine(): String =
        listOf(status.taskReferenceLabel, updatedAtLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")

internal fun TaskSummary.taskReferenceChooserContextLine(): String =
        listOfNotNull(
                        channelName?.takeIf { it.isNotBlank() }?.let { "#$it" },
                        assignee?.let { member ->
                            member.handle.takeIf { it.isNotBlank() } ?: member.displayName
                        },
                        messageId?.value?.take(8)?.let { "msg $it" }
                )
                .joinToString(" · ")

private val TaskStatus.taskReferenceLabel: String
    get() =
            when (this) {
                TaskStatus.Todo -> "Todo"
                TaskStatus.InProgress -> "In progress"
                TaskStatus.InReview -> "In review"
                TaskStatus.Done -> "Done"
                TaskStatus.Closed -> "Closed"
            }

private val TaskStatus.taskReferenceColor: Color
    get() =
            when (this) {
                TaskStatus.Todo -> SlockColor.AccentOrange
                TaskStatus.InProgress -> SlockColor.AccentCyan
                TaskStatus.InReview -> SlockColor.AccentLavender
                TaskStatus.Done -> SlockColor.AccentLime
                TaskStatus.Closed -> SlockColor.AccentRed
            }

private val TaskStatus.taskReferenceIcon: androidx.compose.ui.graphics.vector.ImageVector
    get() =
            when (this) {
                TaskStatus.Todo -> LucideIcons.Circle
                TaskStatus.InProgress -> LucideIcons.Play
                TaskStatus.InReview -> LucideIcons.Eye
                TaskStatus.Done -> LucideIcons.CircleCheckBig
                TaskStatus.Closed -> LucideIcons.Ban
            }

private fun List<String>.withSelection(value: String, selected: Boolean): List<String> =
        if (selected) {
            (this + value).distinct()
        } else {
            filterNot { it == value }
        }

private fun ai.slock.android.core.model.ServerSummary.toSwitcherItem(): SlockServerSwitcherItem =
        SlockServerSwitcherItem(
                id = id.value,
                name = name,
                slug = slug ?: description?.removePrefix("/") ?: id.value,
                unreadCount = unreadCount
        )

private fun ScreenLoadState<ServerListSnapshot>.selectServerOptimistically(
        serverId: ServerId
): ScreenLoadState<ServerListSnapshot> =
        when (this) {
            is ScreenLoadState.Content -> {
                val selectedServer =
                        data.servers.firstOrNull { it.id == serverId } ?: data.selectedServer
                ScreenLoadState.Content(data.copy(selectedServer = selectedServer))
            }
            ScreenLoadState.Loading,
            is ScreenLoadState.Empty,
            is ScreenLoadState.Error,
            is ScreenLoadState.NoPermission -> this
        }

private fun ScreenLoadState<ServerListSnapshot>.withPreservedUnreadCounts(
        previous: ScreenLoadState<ServerListSnapshot>
): ScreenLoadState<ServerListSnapshot> =
        when (this) {
            is ScreenLoadState.Content -> {
                val previousServers = previous.serverUnreadMap()
                ScreenLoadState.Content(
                        data.copy(
                                servers =
                                        data.servers.map { server ->
                                            previousServers[server.id]?.let {
                                                server.copy(unreadCount = it)
                                            }
                                                    ?: server
                                        },
                                selectedServer =
                                        data.selectedServer?.let { selected ->
                                            previousServers[selected.id]?.let {
                                                selected.copy(unreadCount = it)
                                            }
                                                    ?: selected
                                        }
                        )
                )
            }
            ScreenLoadState.Loading,
            is ScreenLoadState.Empty,
            is ScreenLoadState.Error,
            is ScreenLoadState.NoPermission -> this
        }

private fun ScreenLoadState<ServerListSnapshot>.serverUnreadMap(): Map<ServerId, Int> =
        (this as? ScreenLoadState.Content)?.data?.servers.orEmpty().associate {
            it.id to it.unreadCount
        }

private fun ScreenLoadState<ServerListSnapshot>.serverUnreadFor(serverId: ServerId): Int? {
    val content = this as? ScreenLoadState.Content ?: return null
    return content.data.servers.firstOrNull { it.id == serverId }?.unreadCount
            ?: content.data.selectedServer?.takeIf { it.id == serverId }?.unreadCount
}

private fun ScreenLoadState<ServerListSnapshot>.refreshErrorCode(): String? =
        when (this) {
            is ScreenLoadState.Empty -> reason.name
            is ScreenLoadState.Error -> error.code
            is ScreenLoadState.NoPermission -> blocker.code
            ScreenLoadState.Loading, is ScreenLoadState.Content -> null
        }

private fun ScreenLoadState<ServerListSnapshot>.refreshErrorLabel(): String? =
        when (this) {
            is ScreenLoadState.Empty -> "No server context"
            is ScreenLoadState.Error -> error.code.toRefreshErrorLabel()
            is ScreenLoadState.NoPermission -> "No permission"
            ScreenLoadState.Loading, is ScreenLoadState.Content -> null
        }

private fun ScreenLoadState<ServerListSnapshot>.logName(): String =
        when (this) {
            ScreenLoadState.Loading -> "loading"
            is ScreenLoadState.Content -> "content"
            is ScreenLoadState.Empty -> "empty"
            is ScreenLoadState.Error -> "error"
            is ScreenLoadState.NoPermission -> "no_permission"
        }

private fun ScreenLoadState<ServerListSnapshot>.diagnosticCode(): String =
        when (this) {
            ScreenLoadState.Loading -> "loading"
            is ScreenLoadState.Content -> "ok"
            is ScreenLoadState.Empty -> reason.name
            is ScreenLoadState.Error -> error.code
            is ScreenLoadState.NoPermission -> blocker.code
        }

private fun ScreenLoadState<ServerListSnapshot>.applyUnreadSummary(
        snapshot: ServerUnreadSummarySnapshot
): ScreenLoadState<ServerListSnapshot> =
        when (this) {
            is ScreenLoadState.Content -> {
                val unreadByServerId = snapshot.summaries.associateBy { it.serverId }
                val updatedServers =
                        data.servers.map { server ->
                            val unread = unreadByServerId[server.id]?.totalUnreadCount
                            if (unread == null) server else server.copy(unreadCount = unread)
                        }
                val updatedSelectedServer =
                        data.selectedServer?.let { selected ->
                            val unread =
                                    unreadByServerId[selected.id]?.totalUnreadCount
                                            ?: snapshot.selectedServerSummary
                                                    ?.takeIf { it.serverId == selected.id }
                                                    ?.totalUnreadCount
                            if (unread == null) selected else selected.copy(unreadCount = unread)
                        }
                if (updatedServers == data.servers && updatedSelectedServer == data.selectedServer) {
                    return this
                }
                ScreenLoadState.Content(
                        data.copy(servers = updatedServers, selectedServer = updatedSelectedServer)
                )
            }
            ScreenLoadState.Loading,
            is ScreenLoadState.Empty,
            is ScreenLoadState.Error,
            is ScreenLoadState.NoPermission -> this
        }

private fun ScreenLoadState<ServerListSnapshot>.applyUnreadCounts(
        snapshot: ChannelUnreadSnapshot
): ScreenLoadState<ServerListSnapshot> =
        this

private fun HomeUiState.applyUnreadSummary(snapshot: ServerUnreadSummarySnapshot): HomeUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val unread =
            snapshot.summaryFor(content.server.id)?.totalUnreadCount ?: snapshot.totalUnreadCount
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    server = content.server.copy(unreadCount = unread),
                                    serverUnreadCount = unread
                            )
                    )
    )
}

private fun HomeUiState.withCreatedChannel(channel: ConversationSummary): HomeUiState {
    val contentState =
            loadState as? ScreenLoadState.Content
                    ?: return copy(selectedThreadId = channel.threadId.value)
    val content = contentState.data
    val channels =
            (content.channels + channel).distinctBy { it.threadId }
                    .sortedWith(compareBy({ it.name.lowercase() }, { it.threadId.value }))
    return copy(
            loadState = ScreenLoadState.Content(content.copy(channels = channels)),
            selectedThreadId = channel.threadId.value
    )
}

private fun HomeUiState.withJoinedChannel(channel: ConversationSummary): HomeUiState {
    val contentState =
            loadState as? ScreenLoadState.Content
                    ?: return copy(selectedThreadId = channel.threadId.value)
    val content = contentState.data
    val channelId = channel.channelId ?: channel.threadId.parentChannelIdOrNull()
            ?: ChannelId(channel.threadId.value)
    var replaced = false
    val channels =
            content.channels
                    .map { existing ->
                        val matches =
                                existing.threadId == channel.threadId ||
                                        existing.channelId == channelId ||
                                        existing.threadId.value == channelId.value
                        if (matches) {
                            replaced = true
                            channel.withTitleFallback(existing).asJoinedChannel()
                        } else {
                            existing
                        }
                    }
                    .let { updated ->
                        if (replaced) updated else updated + channel.asJoinedChannel()
                    }
                    .sortedWith(compareBy({ it.name.lowercase() }, { it.threadId.value }))
    return copy(
            loadState = ScreenLoadState.Content(content.copy(channels = channels)),
            selectedThreadId = channel.threadId.value
    )
}

private fun HomeUiState.withArchivedChannel(channelId: ChannelId): HomeUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return copy(selectedThreadId = null)
    val content = contentState.data
    val channels =
            content.channels.filterNot { channel ->
                channel.channelId == channelId || channel.threadId.value == channelId.value
            }
    return copy(
            loadState = ScreenLoadState.Content(content.copy(channels = channels)),
            selectedThreadId = null
    )
}

private fun ServerUnreadSummarySnapshot.summaryFor(serverId: ServerId) =
        summaries.firstOrNull { it.serverId == serverId }
                ?: selectedServerSummary?.takeIf { it.serverId == serverId }

private fun HomeUiState.clearUnread(channelId: ChannelId): HomeUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    var cleared = 0
    fun ConversationSummary.clearIfMatches(): ConversationSummary {
        val matches = channelId == this.channelId || channelId.value == threadId.value
        if (!matches) return this
        cleared += unreadCount
        return copy(unreadCount = 0, mentionCount = 0, firstUnreadMessageId = null)
    }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    channels = content.channels.map { it.clearIfMatches() },
                                    directMessages =
                                            content.directMessages.map { it.clearIfMatches() },
                                    unreadTotal = (content.unreadTotal - cleared).coerceAtLeast(0)
                            )
                    )
    )
}

private fun HomeUiState.clearAllUnread(): HomeUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    fun ConversationSummary.clearUnread(): ConversationSummary =
            copy(unreadCount = 0, mentionCount = 0, firstUnreadMessageId = null)
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    channels = content.channels.map { it.clearUnread() },
                                    directMessages = content.directMessages.map { it.clearUnread() },
                                    unreadTotal = 0
                            )
                    )
    )
}

private fun HomeUiState.applyUnreadCounts(snapshot: ChannelUnreadSnapshot): HomeUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    fun ConversationSummary.applyCount(): ConversationSummary {
        val id = channelId ?: threadId.parentChannelIdOrNull() ?: ChannelId(threadId.value)
        val count = snapshot.unreadCounts[id] ?: 0
        return copy(
                unreadCount = count,
                mentionCount = 0,
                firstUnreadMessageId = firstUnreadMessageId.takeIf { count > 0 }
        )
    }
    val updatedChannels = content.channels.map { it.applyCount() }
    val updatedDirectMessages = content.directMessages.map { it.applyCount() }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    channels = updatedChannels,
                                    directMessages = updatedDirectMessages,
                                    unreadTotal =
                                            (updatedChannels + updatedDirectMessages).sumOf {
                                                it.unreadCount
                                            }
                            )
                    )
    )
}

private const val HomeProgressivePrefetchInitialDelayMillis = 1_500L
private const val HomeProgressivePrefetchDelayMillis = 750L
private const val HomeProgressivePrefetchTargetLimit = 0

private fun HomeContent.homeProgressivePrefetchTargets(): List<ConversationSummary> =
        channels
                .asSequence()
                .filter { it.kind == ConversationKind.Channel }
                .sortedWith(
                        compareByDescending<ConversationSummary> { it.unreadCount > 0 }
                                .thenByDescending { it.mentionCount > 0 }
                )
                .take(HomeProgressivePrefetchTargetLimit)
                .toList()

private fun InboxUiState.clearUnread(channelId: ChannelId): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    var cleared = 0
    val updatedItems =
            content.items.mapNotNull { item ->
                val matches = item.channelId == channelId || item.threadChannelId == channelId
                if (!matches) return@mapNotNull item
                cleared += item.unreadCount
                val clearedItem = item.copy(unreadCount = 0, firstUnreadMessageId = null)
                if (selectedFilter == InboxFilter.Unread || selectedFilter == InboxFilter.Mentions) {
                    null
                } else {
                    clearedItem
                }
            }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    items = updatedItems,
                                    totalCount =
                                            if (selectedFilter == InboxFilter.Unread || selectedFilter == InboxFilter.Mentions)
                                                    updatedItems.size
                                            else content.totalCount,
                                    totalUnreadCount =
                                            (content.totalUnreadCount - cleared).coerceAtLeast(0)
                            )
                    )
    )
}

private fun InboxUiState.applyUnreadCounts(snapshot: ChannelUnreadSnapshot): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val updatedItems =
            content.items.mapNotNull { item ->
                val id = item.threadChannelId ?: item.channelId
                val count = snapshot.unreadCounts[id] ?: 0
                val updated =
                        item.copy(
                                unreadCount = count,
                                firstUnreadMessageId =
                                        item.firstUnreadMessageId.takeIf { count > 0 }
                        )
                if ((selectedFilter == InboxFilter.Unread || selectedFilter == InboxFilter.Mentions) && count <= 0) {
                    null
                } else {
                    updated
                }
            }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    items = updatedItems,
                                    totalCount =
                                            if (selectedFilter == InboxFilter.Unread || selectedFilter == InboxFilter.Mentions)
                                                    updatedItems.size
                                            else content.totalCount,
                                    totalUnreadCount = updatedItems.sumOf { it.unreadCount }
                            )
                    )
    )
}

private fun DataResult<HomeSnapshot>.refreshErrorCode(): String =
        when (this) {
            is DataResult.Empty -> reason.name
            is DataResult.Error -> error.code
            is DataResult.NoPermission -> blocker.code
            is DataResult.Content -> ""
        }

private fun DataResult<HomeSnapshot>.refreshErrorLabel(): String =
        when (this) {
            is DataResult.Empty -> "No server content"
            is DataResult.Error -> error.code.toRefreshErrorLabel()
            is DataResult.NoPermission -> "No permission"
            is DataResult.Content -> ""
        }

private fun DataResult<HomeSnapshot>.logName(): String =
        when (this) {
            is DataResult.Content -> "content"
            is DataResult.Empty -> "empty"
            is DataResult.Error -> "error"
            is DataResult.NoPermission -> "no_permission"
        }

private fun DataResult<*>.realtimeResultName(): String =
        when (this) {
            is DataResult.Content -> "content"
            is DataResult.Empty -> "empty:${reason.name}"
            is DataResult.Error -> "error:${error.code}"
            is DataResult.NoPermission -> "no_permission:${blocker.code}"
        }

private fun DataResult<*>.readDiagnosticCode(): String =
        when (this) {
            is DataResult.Content -> "ok"
            is DataResult.Empty -> reason.name
            is DataResult.Error -> error.code
            is DataResult.NoPermission -> blocker.code
        }

private fun ReadReceiptRequest.logSeq(): String =
        when (this) {
            is ReadReceiptRequest.ToSeq -> seq.toString()
            is ReadReceiptRequest.All -> "all"
        }

private fun ReadReceiptRequest.priorityReasonSuffix(): String =
        when (this) {
            is ReadReceiptRequest.ToSeq -> "seq"
            is ReadReceiptRequest.All -> "all"
        }

private fun DataResult<HomeSnapshot>.diagnosticCode(): String =
        when (this) {
            is DataResult.Content -> "ok"
            is DataResult.Empty -> reason.name
            is DataResult.Error -> error.code
            is DataResult.NoPermission -> blocker.code
        }

private fun String.toRefreshErrorLabel(): String {
    val normalized = lowercase()
    return when {
        "permission" in normalized ||
                "unauthorized" in normalized ||
                "forbidden" in normalized ||
                "403" in normalized -> "No permission"
        "network" in normalized ||
                "timeout" in normalized ||
                "io" in normalized ||
                "http" in normalized -> "Network failure"
        "server" in normalized || "nochannels" in normalized || "no_server" in normalized ->
                "No server context"
        else -> this
    }
}

private fun ConversationSummary.toHomeConversationItem():
        ai.slock.android.ui.components.SlockHomeConversationItem =
        ai.slock.android.ui.components.SlockHomeConversationItem(
                id = threadId.value,
                name = name,
                unreadCount = unreadCount,
                avatarUrl = avatarUrl,
                avatar = avatar,
                presence = presence?.toSlockPresence(),
                hasDraft = hasDraft,
                joined = joined && permissions.canRead
        )

private fun Presence.toSlockPresence(): SlockPresence =
        when (this) {
            Presence.Active -> SlockPresence.Online
            Presence.Away -> SlockPresence.Unknown
            Presence.Offline -> SlockPresence.Offline
        }

private fun Long.updateFrom(event: RealtimeEvent): Long =
        when (event) {
            is Heartbeat -> maxOf(this, event.seq)
            is MessageNew -> maxOf(this, event.message.seq ?: 0L)
            is MessageUpdated -> maxOf(this, event.message.seq ?: 0L)
            is SyncResumeResponse -> maxOf(this, event.currentSeq)
            else -> this
        }

private fun RealtimeEvent.realtimeLogName(): String =
        when (this) {
            is Heartbeat -> "heartbeat"
            is MessageNew -> "message:new"
            is MessageUpdated -> "message:updated"
            is SyncResumeResponse -> "sync:resume:response"
            is ThreadUpdated -> "thread:updated"
            is DirectMessageCreated -> "direct-message:created"
            is TaskCreated -> "task:created"
            is TaskUpdated -> "task:updated"
            is TaskDeleted -> "task:deleted"
            is MemberPresenceUpdated -> "member:presence-updated"
        }

private fun ThreadContent.latestMessageSeq(): Long =
        messages.maxOfOrNull { it.seq ?: 0L } ?: 0L

private fun HomeUiState.applyRealtimeEvent(event: RealtimeEvent): HomeUiState =
        when (event) {
            is MessageNew ->
                    applyRealtimeMessages(listOf(event.message), incrementUnread = true)
            is MessageUpdated ->
                    applyRealtimeMessages(listOf(event.message), incrementUnread = false)
            is SyncResumeResponse ->
                    applyRealtimeMessages(event.messages, incrementUnread = true)
            else -> this
        }

private fun HomeUiState.withOpenDirectMessage(
        result: OpenDirectMessageResult,
        selectedConversation: ConversationSummary
): HomeUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val directMessages =
            (result.directMessages + selectedConversation).mergeDirectMessageRows()
    return copy(
            loadState =
                    ScreenLoadState.Content(contentState.data.copy(directMessages = directMessages)),
            selectedThreadId = selectedConversation.threadId.value
    )
}

private fun HomeUiState.applyRealtimeMessages(
        messages: List<Message>,
        incrementUnread: Boolean
): HomeUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    var unreadDelta = 0
    fun ConversationSummary.merge(message: Message): ConversationSummary {
        val matches =
                threadId.value == message.threadId.value ||
                        channelId?.value == message.channelId?.value
        if (!matches) return this
        unreadDelta += if (incrementUnread && lastMessageId != message.id) 1 else 0
        return copy(
                unreadCount =
                        unreadCount + if (incrementUnread && lastMessageId != message.id) 1 else 0,
                lastMessageId = message.id,
                lastMessagePreview = message.body,
                lastMessageAt = message.timestampLabel,
                lastMessageSenderName = message.author.displayName
        )
    }
    val content = contentState.data
    val updatedChannels =
            content.channels.map { conversation ->
                messages.fold(conversation) { current, message -> current.merge(message) }
            }
    val updatedDms =
            content.directMessages.map { conversation ->
                messages.fold(conversation) { current, message -> current.merge(message) }
            }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    channels = updatedChannels,
                                    directMessages = updatedDms,
                                    unreadTotal = content.unreadTotal + unreadDelta
                            )
                    )
    )
}

private fun List<ConversationSummary>.mergeDirectMessageRows(): List<ConversationSummary> {
    val merged = LinkedHashMap<String, ConversationSummary>()
    for (conversation in this) {
        val key = conversation.channelId?.value ?: conversation.threadId.value
        val previous = merged[key]
        merged[key] =
                if (previous == null) {
                    conversation
                } else {
                    conversation.withHomeDirectMessageFallback(previous)
                }
    }
    return merged.values.toList()
}

private fun ConversationSummary.withHomeDirectMessageFallback(
        previous: ConversationSummary
): ConversationSummary =
        copy(
                name = name.takeIf { it.isUsefulConversationName(threadId, channelId) }
                        ?: previous.name,
                subtitle = subtitle ?: previous.subtitle,
                avatarUrl = avatarUrl ?: previous.avatarUrl,
                avatar = avatar ?: previous.avatar,
                unreadCount = maxOf(unreadCount, previous.unreadCount),
                mentionCount = maxOf(mentionCount, previous.mentionCount),
                lastMessageId = lastMessageId ?: previous.lastMessageId,
                firstUnreadMessageId = firstUnreadMessageId ?: previous.firstUnreadMessageId,
                lastMessagePreview = lastMessagePreview ?: previous.lastMessagePreview,
                lastMessageAt = lastMessageAt ?: previous.lastMessageAt,
                lastMessageSenderName = lastMessageSenderName ?: previous.lastMessageSenderName,
                joined = joined || previous.joined,
                archived = archived || previous.archived
        )

private fun ConversationSummary.withAgentDirectMessageIdentity(
        member: MemberProfile
): ConversationSummary =
        copy(
                name = name.takeIf { it.isUsefulConversationName(threadId, channelId) }
                        ?: member.displayName,
                avatarUrl = avatarUrl ?: member.avatarUrl,
                avatar = avatar ?: member.avatar
        )

private fun MembersUiState.applyRealtimeEvent(event: RealtimeEvent): MembersUiState =
        when (event) {
            is MemberPresenceUpdated -> applyMemberPresence(event)
            else -> this
        }

private fun MembersUiState.applyMemberPresence(
        event: MemberPresenceUpdated
): MembersUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(members = content.members.map { it.withPresence(event) })
                    )
    )
}

private fun List<Message>.applyRealtimeEvent(event: RealtimeEvent): List<Message> =
        when (event) {
            is MessageNew -> (this + event.message).distinctBy { it.id }
            is MessageUpdated ->
                    map { existing ->
                        if (existing.id == event.message.id) {
                            existing.mergeRealtimeUpdate(event.message)
                        } else {
                            existing
                        }
                    }
            is SyncResumeResponse -> (this + event.messages).distinctBy { it.id }
            else -> this
        }

private fun ThreadUiState.applyRealtimeEvent(
        event: RealtimeEvent,
        selectedThreadId: String?,
        activeReplyParentMessageId: MessageId? = null
): ThreadUiState =
        when (event) {
            is MessageNew ->
                    applyRealtimeThreadMessages(
                            messages = listOf(event.message),
                            selectedThreadId = selectedThreadId,
                            activeReplyParentMessageId = activeReplyParentMessageId
                    )
            is MessageUpdated ->
                    updateRealtimeThreadMessage(
                            message = event.message,
                            selectedThreadId = selectedThreadId,
                            activeReplyParentMessageId = activeReplyParentMessageId
                    )
            is SyncResumeResponse ->
                    applyRealtimeThreadMessages(
                            messages = event.messages,
                            selectedThreadId = selectedThreadId,
                            activeReplyParentMessageId = activeReplyParentMessageId
                    )
            is ThreadUpdated -> applyThreadUpdated(event)
            is TaskCreated, is TaskUpdated -> mergeRealtimeTaskEvent(event)
            else -> this
        }

private fun List<ReplyThreadBackStackEntry>.applyRealtimeToReplyBackStack(
        event: RealtimeEvent
): List<ReplyThreadBackStackEntry> = map { entry ->
    entry.copy(
            selectedThreadState =
                    entry.selectedThreadState.applyRealtimeEvent(
                            event = event,
                            selectedThreadId = entry.selectedThreadId
                    )
    )
}

private fun ThreadMembersPanelState.applyRealtimeEvent(
        event: RealtimeEvent
): ThreadMembersPanelState =
        when (event) {
            is MemberPresenceUpdated -> applyMemberPresence(event)
            else -> this
        }

private fun ThreadMembersPanelState.applyMemberPresence(
        event: MemberPresenceUpdated
): ThreadMembersPanelState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(members = content.members.map { it.withPresence(event) })
                    )
    )
}

private fun MemberProfile.withPresence(event: MemberPresenceUpdated): MemberProfile {
    val kindMatches = event.kind == null || event.kind == kind
    return if (id == event.memberId && kindMatches) {
        copy(
                presence = event.presence,
                agentStatus = event.agentStatus ?: agentStatus
        )
    } else {
        this
    }
}

private fun ThreadUiState.applyRealtimeThreadMessages(
        messages: List<Message>,
        selectedThreadId: String?,
        activeReplyParentMessageId: MessageId? = null
): ThreadUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val relevantMessages =
            messages.filter {
                it.matchesThread(content.header, selectedThreadId, activeReplyParentMessageId)
            }
    if (relevantMessages.isEmpty()) return this
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    messages =
                                            (content.messages + relevantMessages)
                                                    .distinctBy { it.id }
                                                    .sortedForThreadTimeline()
                            )
                    )
    )
}

private fun ThreadUiState.updateRealtimeThreadMessage(
        message: Message,
        selectedThreadId: String?,
        activeReplyParentMessageId: MessageId? = null
): ThreadUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    if (!message.matchesThread(content.header, selectedThreadId, activeReplyParentMessageId))
            return this
    val updatedMessages =
            content.messages.map { existing ->
                if (existing.id == message.id) {
                    existing.mergeRealtimeUpdate(message)
                } else {
                    existing
                }
            }
    val mergedMessages =
            if (updatedMessages.any { it.id == message.id }) {
                updatedMessages
            } else {
                content.messages + message
            }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    messages =
                                            mergedMessages.distinctBy { it.id }.sortedForThreadTimeline()
                            )
                    )
    )
}

internal fun Message.mergeRealtimeUpdate(update: Message): Message =
        copy(
                threadId = update.threadId,
                channelId = update.channelId ?: channelId,
                threadChannelId = update.threadChannelId ?: threadChannelId,
                seq = update.seq ?: seq,
                author = author.mergeRealtimeAuthor(update.author),
                timestampLabel = update.timestampLabel.ifBlank { timestampLabel },
                body = update.body.ifBlank { body },
                messageType = update.messageType,
                systemEventType = update.systemEventType ?: systemEventType,
                metadata = update.metadata.ifEmpty { metadata },
                attachments = update.attachments.ifEmpty { attachments },
                deliveryState = update.deliveryState,
                task = update.task ?: task,
                replyCount = maxOf(replyCount, update.replyCount),
                parentMessageId = update.parentMessageId ?: parentMessageId,
                updatedAtLabel = update.updatedAtLabel ?: updatedAtLabel
        )

private fun MemberProfile.mergeRealtimeAuthor(update: MemberProfile): MemberProfile {
    val mergedAvatarUrl = update.avatarUrl ?: avatarUrl
    val mergedGravatarHash = update.gravatarHash ?: gravatarHash
    return copy(
            id = update.id,
            displayName =
                    update.displayName.takeUnless { it.isBlank() || it == update.id.value }
                            ?: displayName,
            handle = update.handle.takeUnless { it.isBlank() || it == update.id.value } ?: handle,
            kind = update.kind,
            description = update.description ?: description,
            roleDescription = update.roleDescription ?: roleDescription,
            avatarUrl = mergedAvatarUrl,
            gravatarHash = mergedGravatarHash,
            avatar =
                    update.avatar
                            ?: avatar
                            ?: AvatarReference.fromWireValue(mergedAvatarUrl)
                            ?: mergedGravatarHash?.toGravatarAvatarReference(),
            avatarLabel = update.avatarLabel.takeIf { it.isNotBlank() } ?: avatarLabel,
            presence = if (update.presence == Presence.Offline) presence else update.presence,
            permissionBadges =
                    if (update.permissionBadges.isNotEmpty()) update.permissionBadges
                    else permissionBadges
    )
}

private fun ThreadUiState.applyThreadUpdated(event: ThreadUpdated): ThreadUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val updatedMessages =
            content.messages.map { message ->
                if (message.id == event.parentMessageId ||
                                message.threadChannelId == event.threadChannelId
                ) {
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockThreadReplies",
                            "thread_updated parentMessageId=${message.id.value} " +
                                    "channelId=${message.channelId?.value.orEmpty()} " +
                                    "threadChannelId=${event.threadChannelId.value} " +
                                    "replyCount=${maxOf(message.replyCount, event.replyCount)} source=realtime_thread_updated"
                    )
                    message.copy(
                            threadChannelId = event.threadChannelId,
                            replyCount = maxOf(message.replyCount, event.replyCount),
                            updatedAtLabel = event.lastReplyAt ?: message.updatedAtLabel
                    )
                } else {
                    message
                }
            }
    return copy(loadState = ScreenLoadState.Content(content.copy(messages = updatedMessages)))
}

private fun ThreadUiState.mergeThreadActionResult(action: ThreadActionResult): ThreadUiState {
    val parentMessageId = action.parentMessageId ?: return this
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val updatedMessages =
            content.messages.map { message ->
                if (message.id == parentMessageId) {
                    message.copy(
                            threadChannelId = action.threadChannelId,
                            replyCount = maxOf(message.replyCount, action.replyCount)
                    )
                } else {
                    message
                }
            }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(messages = updatedMessages, replyActionErrorCode = null)
                    )
    )
}

private fun ThreadUiState.withReplyActionError(errorCode: String?): ThreadUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            contentState.data.copy(replyActionErrorCode = errorCode)
                    )
    )
}

private fun ThreadUiState.withHighlightedMessage(messageId: MessageId): ThreadUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            contentState.data.copy(highlightedMessageId = messageId)
                    )
    )
}

private fun ThreadUiState.withChannelJoinStatus(
        status: ChannelJoinStatus,
        errorCode: String? = null
): ThreadUiState = copy(channelJoinStatus = status, channelJoinErrorCode = errorCode)

private fun ThreadUiState.withJoinedChannel(channel: ConversationSummary): ThreadUiState {
    val contentState =
            loadState as? ScreenLoadState.Content
                    ?: return withChannelJoinStatus(ChannelJoinStatus.Idle)
    val header = channel.withTitleFallback(contentState.data.header).asJoinedChannel()
    return copy(
            loadState = ScreenLoadState.Content(contentState.data.copy(header = header)),
            composerState = header.toComposerState(),
            channelJoinStatus = ChannelJoinStatus.Idle,
            channelJoinErrorCode = null
    )
}

private fun Message.matchesThread(
        conversation: ConversationSummary,
        selectedThreadId: String? = null,
        activeReplyParentMessageId: MessageId? = null
): Boolean {
    val streamChannelId = conversation.messageStreamChannelId()
    return threadId.value == streamChannelId.value ||
                channelId == streamChannelId ||
                threadChannelId == streamChannelId ||
                selectedThreadId?.let { selectedId ->
                    threadId.value == selectedId ||
                            channelId?.value == selectedId ||
                            threadChannelId?.value == selectedId
                } == true ||
                activeReplyParentMessageId?.let { parentId -> parentMessageId == parentId } == true
}

private fun ThreadUiState.logVisibleThreadMerge(
        event: RealtimeEvent,
        selectedThreadId: String?,
        activeReplyParentMessageId: MessageId? = null
) {
    val content = (loadState as? ScreenLoadState.Content)?.data ?: return
    val messages =
            when (event) {
                is MessageNew -> listOf(event.message)
                is MessageUpdated -> listOf(event.message)
                is SyncResumeResponse -> event.messages
                else -> emptyList()
            }
    if (messages.isEmpty()) return
    val matchedCount =
            messages.count {
                it.matchesThread(content.header, selectedThreadId, activeReplyParentMessageId)
            }
    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
            "SlockRealtime",
            "visible_thread_merge selectedThreadId=${selectedThreadId.orEmpty()} activeReplyParentMessageId=${activeReplyParentMessageId?.value.orEmpty()} headerThreadId=${content.header.threadId.value} headerChannelId=${content.header.channelId?.value.orEmpty()} incoming=${messages.size} matched=$matchedCount visibleMessages=${content.messages.size}"
    )
}

private fun DataResult<HomeSnapshot>.toHomeUiState(
        selectedServerId: String?,
        preservedServerUnreadCount: Int? = null
): HomeUiState =
        HomeUiState(
                loadState =
                        when (this) {
                            is DataResult.Content ->
                                    ScreenLoadState.Content(
                                            run {
                                                val effectiveServerUnreadCount =
                                                        preservedServerUnreadCount
                                                                ?: data.server.unreadCount
                                                val server =
                                                        data.server.copy(
                                                                unreadCount =
                                                                        effectiveServerUnreadCount
                                                        )
                                                 HomeContent(
                                                                 server = server,
                                                                 currentMember = data.currentMember,
                                                                 channels = data.channels,
                                                                 directMessages =
                                                                         data.directMessages,
                                                                 unreadTotal = data.unreadTotal,
                                                                 serverUnreadCount =
                                                                         effectiveServerUnreadCount
                                                         )
                                             }
                                     )
                            is DataResult.Empty -> ScreenLoadState.Empty(EmptyReason.NoInboxItems)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        },
                selectedServerId = selectedServerId
        )

private fun ThreadUiState.threadChannelId(): ChannelId? {
    val content = (loadState as? ScreenLoadState.Content)?.data ?: return null
    return content.header.channelId
            ?: content.header.threadId.parentChannelIdOrNull()
            ?: ChannelId(content.header.threadId.value)
}

private fun ThreadUiState.withBackgroundThreadRefreshIndicator(): ThreadUiState =
        withThreadRefreshStatus(ThreadRefreshStatus.Loading)

private fun ThreadUiState.refreshStatus(): ThreadRefreshStatus =
        (loadState as? ScreenLoadState.Content)?.data?.refreshStatus ?: ThreadRefreshStatus.Idle

private fun ThreadUiState.withThreadRefreshResult(result: DataResult<*>): ThreadUiState =
        when (result) {
            is DataResult.Content -> withThreadRefreshStatus(ThreadRefreshStatus.Idle)
            is DataResult.Empty ->
                    withThreadRefreshStatus(ThreadRefreshStatus.Error, result.reason.name)
            is DataResult.Error ->
                    withThreadRefreshStatus(ThreadRefreshStatus.Error, result.error.code)
            is DataResult.NoPermission ->
                    withThreadRefreshStatus(ThreadRefreshStatus.Error, result.blocker.code)
        }

private suspend fun DataResult<HomeSnapshot>.retryInitialChannelsIfMissing(
        refreshSource: String,
        serverId: ServerId,
        channelRepository: ChannelRepository
): DataResult<HomeSnapshot> {
    if (refreshSource != "initial") return this
    if (this !is DataResult.Content || data.channels.isNotEmpty()) return this
    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
            "SlockHomeRefresh",
            "channels_missing_initial_retry_start serverId=${serverId.value} directMessages=${data.directMessages.size}"
    )
    delay(250)
    val retryResult =
            withSlockRequestPriority(
                    priority = SlockRequestPriority.Visible,
                    key = "home:${serverId.value}",
                    reason = "home_initial_missing_channels_retry"
            ) {
                channelRepository.getHome(serverId)
            }
    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
            "SlockHomeRefresh",
            "channels_missing_initial_retry_result serverId=${serverId.value} result=${retryResult.logName()} code=${retryResult.diagnosticCode()} channels=${(retryResult as? DataResult.Content)?.data?.channels?.size ?: -1}"
    )
    return if (retryResult is DataResult.Content && retryResult.data.channels.isNotEmpty()) {
        retryResult
    } else {
        this
    }
}

private fun HomeUiState.serverUnreadFor(serverId: ServerId): Int? {
    val content = (loadState as? ScreenLoadState.Content)?.data ?: return null
    return if (content.server.id == serverId) content.serverUnreadCount else null
}

private fun DataResult<ThreadSnapshot>.toThreadUiState(
        fallbackHeader: ConversationSummary? = null,
        parentMessage: Message? = null,
        serverId: ServerId? = null
): ThreadUiState {
    val fallbackPreviewHeader = fallbackHeader
    val contentHeader =
            (this as? DataResult.Content)
                    ?.data
                    ?.conversation
                    ?.withTitleFallback(fallbackHeader)
                    ?.withPreviewMembershipFallback(fallbackHeader)
    val previewErrorCode =
            when (this) {
                is DataResult.Empty -> reason.name
                is DataResult.Error -> error.code
                is DataResult.NoPermission -> blocker.code
                is DataResult.Content -> null
            }
    val loadState =
            when (this) {
                is DataResult.Content ->
                        ScreenLoadState.Content(
                                ThreadContent(
                                        header = contentHeader ?: data.conversation,
                                        messages = data.messages,
                                        canLoadMoreBefore = data.canLoadMoreBefore,
                                        canLoadMoreAfter = data.canLoadMoreAfter,
                                        hasGap = data.hasGap,
                                        historyLimited = data.historyLimited,
                                        highlightedMessageId = data.highlightedMessageId,
                                        parentMessage = parentMessage ?: data.parentMessage,
                                        tasks = data.tasks
                                )
                        )
                is DataResult.Empty ->
                        fallbackPreviewHeader?.let { header ->
                            ScreenLoadState.Content(
                                    ThreadContent(
                                            header = header,
                                            messages = emptyList(),
                                            replyActionErrorCode =
                                                    previewErrorCode.takeUnless {
                                                        reason == EmptyReason.NoMessages
                                                    },
                                            parentMessage = parentMessage
                                    )
                            )
                        }
                                ?: ScreenLoadState.Empty(reason)
                is DataResult.Error ->
                        fallbackPreviewHeader?.let { header ->
                            ScreenLoadState.Content(
                                    ThreadContent(
                                            header = header,
                                            messages = emptyList(),
                                            replyActionErrorCode = previewErrorCode,
                                            parentMessage = parentMessage
                                    )
                            )
                        }
                                ?: ScreenLoadState.Error(error)
                is DataResult.NoPermission ->
                        fallbackPreviewHeader?.let { header ->
                            ScreenLoadState.Content(
                                    ThreadContent(
                                            header = header,
                                            messages = emptyList(),
                                            replyActionErrorCode = previewErrorCode,
                                            parentMessage = parentMessage
                                    )
                            )
                        }
                                ?: ScreenLoadState.NoPermission(blocker)
            }
    val header = contentHeader ?: fallbackHeader
    return ThreadUiState(
            loadState = loadState,
            serverId = serverId,
            composerState = header?.toComposerState()
                            ?: ComposerState(status = ComposerStatus.IdleEmpty)
    )
}

private fun DataResult<ChannelFilesSnapshot>.toThreadFilesUiState(): ThreadFilesUiState =
        when (this) {
            is DataResult.Content ->
                    ThreadFilesUiState(
                            loadState =
                                    ScreenLoadState.Content(
                                            ThreadFilesContent(
                                                    items = data.items,
                                                    hasMore = data.hasMore
                                            )
                                    ),
                            nextCursor = data.nextCursor
                    )
            is DataResult.Empty ->
                    ThreadFilesUiState(
                            loadState =
                                    ScreenLoadState.Content(
                                            ThreadFilesContent(items = emptyList(), hasMore = false)
                                    )
                    )
            is DataResult.Error -> ThreadFilesUiState(loadState = ScreenLoadState.Error(error))
            is DataResult.NoPermission ->
                    ThreadFilesUiState(loadState = ScreenLoadState.NoPermission(blocker))
        }

private fun ScreenLoadState<ThreadFilesContent>.threadFilesStatusForLog(): String =
        when (this) {
            ScreenLoadState.Loading -> "loading"
            is ScreenLoadState.Content -> "content:${data.items.size}:hasMore=${data.hasMore}"
            is ScreenLoadState.Empty -> "empty"
            is ScreenLoadState.Error -> "error:${error.code}"
            is ScreenLoadState.NoPermission -> "no_permission:${blocker.code}"
        }

private fun mergeThreadFilesState(
        current: ThreadFilesUiState?,
        next: ThreadFilesUiState,
        append: Boolean
): ThreadFilesUiState =
        if (!append) {
            next.copy(loadingMore = false)
        } else {
            val currentContent = current?.loadState as? ScreenLoadState.Content
            val nextContent = next.loadState as? ScreenLoadState.Content
            if (currentContent == null || nextContent == null) {
                current?.copy(loadingMore = false) ?: next.copy(loadingMore = false)
            } else {
                next.copy(
                        loadState =
                                ScreenLoadState.Content(
                                        ThreadFilesContent(
                                                items =
                                                        (currentContent.data.items + nextContent.data.items)
                                                                .distinctBy {
                                                                    it.attachment.id
                                                                },
                                                hasMore = nextContent.data.hasMore
                                        )
                                ),
                        loadingMore = false
                )
            }
        }

private fun DataResult<ThreadSnapshot>.findParentMessage(parentMessageId: MessageId?): Message? {
    if (parentMessageId == null || this !is DataResult.Content) return null
    return data.parentMessage?.takeIf { it.id == parentMessageId }
            ?: data.messages.firstOrNull { it.id == parentMessageId }
}

private data class ThreadOpenTargetResolution(
        val contextResult: DataResult<ThreadSnapshot>,
        val contextSnapshot: ThreadSnapshot?,
        val displayResult: DataResult<ThreadSnapshot>,
        val openedThreadId: ThreadId,
        val parentMessage: Message?,
        val replyParentMessage: Message?
)

private suspend fun ConversationRepository.resolveThreadOpenTarget(
        requestedChannelId: ChannelId,
        messageId: MessageId?
): ThreadOpenTargetResolution {
    val contextResult =
            messageId?.let { getMessageContext(it) }
                    ?: getThread(ThreadId(requestedChannelId.value))
    val contextSnapshot = (contextResult as? DataResult.Content)?.data
    val targetMessage =
            messageId?.let { targetMessageId ->
                contextSnapshot?.parentMessage?.takeIf { it.id == targetMessageId }
                        ?: contextSnapshot?.messages?.firstOrNull { it.id == targetMessageId }
            }
    val parentMessageId =
            targetMessage?.parentMessageId
                    ?: contextSnapshot?.conversation?.thread?.parentMessageId
                    ?: contextSnapshot?.parentMessage?.id?.takeIf {
                        contextSnapshot.conversation.kind == ConversationKind.Thread ||
                                contextSnapshot.parentMessage.threadChannelId != null
                    }
                    ?: targetMessage?.id?.takeIf { targetMessage.threadChannelId != null }
    val parentChannelId =
            contextSnapshot?.conversation?.thread?.parentChannelId
                    ?: contextSnapshot?.parentMessage?.channelId
                    ?: targetMessage?.threadId?.parentChannelIdOrNull()
    val parentMessage =
            (
                    contextSnapshot?.parentMessage?.takeIf {
                        parentMessageId == null || it.id == parentMessageId
                    }
                            ?: contextSnapshot?.messages?.firstOrNull {
                                parentMessageId != null && it.id == parentMessageId
                            }
                            ?: targetMessage?.takeIf { it.threadChannelId != null }
                    )?.let { message ->
                    message.copy(channelId = message.channelId ?: parentChannelId)
            }
    val replyThreadChannelId =
            parentMessage?.threadChannelId
                    ?: contextSnapshot?.conversation?.thread?.streamChannelId
                    ?: contextSnapshot
                            ?.conversation
                            ?.takeIf { it.kind == ConversationKind.Thread }
                            ?.messageStreamChannelId()
    val shouldOpenReplyThread =
            parentMessage != null &&
                    replyThreadChannelId != null &&
                    replyThreadChannelId != parentMessage.channelId &&
                    (
                            contextSnapshot?.conversation?.kind == ConversationKind.Thread ||
                                    parentMessage.threadChannelId != null ||
                                    requestedChannelId == replyThreadChannelId
                            )
    val replyParentMessage = parentMessage?.takeIf { shouldOpenReplyThread }
    val replyThreadChannelIdForDisplay = replyThreadChannelId?.takeIf { shouldOpenReplyThread }
    val displayResult =
            if (replyThreadChannelIdForDisplay != null) {
                val contextStreamChannelId = contextSnapshot?.conversation?.messageStreamChannelId()
                if (contextStreamChannelId == replyThreadChannelIdForDisplay) {
                    contextResult
                } else {
                    getThread(ThreadId(replyThreadChannelIdForDisplay.value))
                }
            } else {
                contextResult
            }
    val openedThreadId =
            ThreadId(
                    (displayResult as? DataResult.Content)
                            ?.data
                            ?.conversation
                            ?.messageStreamChannelId()
                            ?.value
                            ?: replyThreadChannelIdForDisplay?.value
                            ?: requestedChannelId.value
            )
    return ThreadOpenTargetResolution(
            contextResult = contextResult,
            contextSnapshot = contextSnapshot,
            displayResult = displayResult,
            openedThreadId = openedThreadId,
            parentMessage = parentMessage,
            replyParentMessage = replyParentMessage
    )
}

private fun TaskSummary.replyThreadChannelId(
        parentMessage: Message?,
        snapshot: ThreadSnapshot
): ChannelId? =
        parentMessage?.threadChannelId
                ?: snapshot.parentMessage?.threadChannelId
                ?: snapshot.messages.firstOrNull { message ->
                    parentMessageId?.let { message.id == it } == true ||
                            messageId?.let { message.id == it } == true
                }?.threadChannelId
                ?: threadId?.let { thread ->
                    val parentChannelId = parentMessage?.channelId ?: channelId
                    thread.takeUnless { parentChannelId?.value == it.value }?.let {
                        ChannelId(it.value)
                    }
                }

private fun TaskSummary.shouldOpenReplyThread(
        parentMessage: Message?,
        replyThreadChannelId: ChannelId?
): Boolean =
        parentMessageId != null &&
                replyThreadChannelId != null &&
                replyThreadChannelId != channelId &&
                parentMessage?.channelId != replyThreadChannelId

internal fun composerSendChannelId(
        content: ThreadContent?,
        selectedThreadId: String?
): ChannelId? =
        content?.header?.messageStreamChannelId()
                ?: selectedThreadId?.let(::ChannelId)

internal fun threadUiStateForPreviewResult(
        result: DataResult<ThreadSnapshot>,
        fallbackHeader: ConversationSummary
): ThreadUiState =
        result.toThreadUiState(
                fallbackHeader = fallbackHeader
        )

private fun cacheOpenedThreadResult(
        localWriteThrough: SlockLocalWriteThrough,
        result: DataResult<ThreadSnapshot>,
        source: String,
        fallbackThreadId: ThreadId? = null,
        fallbackHeader: ConversationSummary? = null,
        parentMessage: Message? = null,
        serverId: ServerId? = null
) {
        localWriteThrough.writeThreadResult(
                result = result,
                source = source,
                fallbackThreadId = fallbackThreadId,
                fallbackHeader = fallbackHeader,
                fallbackParentMessage = parentMessage,
                serverId = serverId
        )
}

internal fun conversationToRestoreAfterThreadRecreate(
        homeState: HomeUiState,
        selectedThreadId: String?,
        selectedThreadState: ThreadUiState,
        isThreadRoute: Boolean,
        hasReplyThreadContext: Boolean
): ConversationSummary? {
    if (!isThreadRoute || hasReplyThreadContext) return null
    val threadId = selectedThreadId ?: return null
    if (selectedThreadState.loadState !is ScreenLoadState.Loading) return null
    val homeContent = (homeState.loadState as? ScreenLoadState.Content)?.data ?: return null
    return homeContent.findConversation(threadId)
}

internal fun threadIdToRestoreDirectlyAfterRecreate(
        selectedThreadId: String?,
        selectedThreadState: ThreadUiState,
        isThreadRoute: Boolean,
        hasReplyThreadContext: Boolean
): String? {
    if (!isThreadRoute || hasReplyThreadContext) return null
    val threadId = selectedThreadId?.takeIf { it.isNotBlank() } ?: return null
    if (selectedThreadState.loadState !is ScreenLoadState.Loading) return null
    return threadId
}

private fun HomeContent.findConversation(id: String): ConversationSummary? =
        (channels + directMessages).firstOrNull { conversation ->
            conversation.threadId.value == id || conversation.channelId?.value == id
        }

private fun Message.toConversationSummary(
        currentHeader: ConversationSummary?
): ConversationSummary {
    val parentChannelId = channelId
    val targetThreadId =
            if (threadChannelId != null && parentChannelId != null) {
                threadIdentity(parentChannelId, id)
            } else {
                threadChannelId?.let { ThreadId(it.value) } ?: threadId
            }
    val targetKind =
            if (threadChannelId != null || parentMessageId != null) {
                ConversationKind.Thread
            } else {
                currentHeader?.kind ?: ConversationKind.Channel
            }
    return ConversationSummary(
            threadId = targetThreadId,
            channelId = parentChannelId,
            thread =
                    parentChannelId?.takeIf { targetKind == ConversationKind.Thread }?.let {
                        ThreadConversation(
                                parentChannelId = it,
                                parentMessageId = id,
                                streamChannelId = threadChannelId
                        )
                    },
            kind = targetKind,
            name = currentHeader?.name?.takeIf { it.isNotBlank() }
                            ?: channelId?.value ?: targetThreadId.value,
            subtitle = body.takeIf { it.isNotBlank() },
            lastMessageId = id,
            lastMessagePreview = body,
            lastMessageAt = timestampLabel,
            lastMessageSenderName = author.displayName
    )
}

private fun TaskSummary.toConversationSummary(
        fallback: ConversationSummary? = null
): ConversationSummary {
    val taskChannelId = channelId
    val taskParentMessageId = parentMessageId
    val opensReplyThread =
            isThreadTask || threadId?.let { taskThreadId ->
                taskChannelId == null || taskThreadId.value != taskChannelId.value
            } == true
    val targetThreadId =
            if (opensReplyThread && taskChannelId != null && taskParentMessageId != null) {
                threadIdentity(taskChannelId, taskParentMessageId)
            } else {
                threadId ?: taskChannelId?.let { ThreadId(it.value) } ?: ThreadId(id.value)
            }
    val targetChannelId =
            if (opensReplyThread) taskChannelId ?: fallback?.channelId else taskChannelId ?: fallback?.channelId
    val fallbackName = fallback?.name?.takeIf { it.isUsefulConversationName(targetThreadId, targetChannelId) }
    return ConversationSummary(
            threadId = targetThreadId,
            channelId = targetChannelId,
            thread =
                    if (opensReplyThread && targetChannelId != null && taskParentMessageId != null) {
                        ThreadConversation(
                                parentChannelId = targetChannelId,
                                parentMessageId = taskParentMessageId,
                                streamChannelId =
                                        threadId
                                                ?.takeUnless { it == targetThreadId }
                                                ?.let { ChannelId(it.value) }
                        )
                    } else {
                        null
                    },
            kind = if (opensReplyThread) ConversationKind.Thread else fallback?.kind ?: ConversationKind.Channel,
            name = fallbackName ?: channelName ?: targetThreadId.value,
            subtitle = title,
            lastMessageId = parentMessageId ?: messageId,
            lastMessagePreview = title,
            lastMessageAt = updatedAtLabel,
            lastMessageSenderName = assignee?.displayName
    )
}

private fun String.isUsefulConversationName(
        threadId: ThreadId,
        channelId: ChannelId? = null
): Boolean =
        isNotBlank() && this != threadId.value && this != channelId?.value

private fun ConversationSummary.toComposerState(): ComposerState =
        ComposerState(
                enabled = joined && permissions.canSend,
                status = ComposerStatus.IdleEmpty,
                noPermissionCode =
                        when {
                            !joined -> "join_required"
                            !permissions.canSend -> "read_only"
                            else -> null
                        }
        )

private fun ChannelJoinResult.toJoinedConversation(
        fallback: ConversationSummary
): ConversationSummary =
        (channel ?: fallback.copy(channelId = channelId))
                .withTitleFallback(fallback)
                .copy(channelId = channelId)
                .asJoinedChannel()

private fun ConversationSummary.asJoinedChannel(): ConversationSummary =
        copy(
                joined = true,
                permissions =
                        ConversationPermissions(
                                canRead = true,
                                canSend = true,
                                canAttach = true,
                                canCreateTask = true
                        )
        )

private fun ComposerStatus.fromComposerValue(
        value: String,
        hasAttachments: Boolean = false
): ComposerStatus =
        when (this) {
            ComposerStatus.Sending,
            ComposerStatus.Uploading,
            ComposerStatus.Failed,
            ComposerStatus.NoPermission -> this
            ComposerStatus.IdleEmpty, ComposerStatus.Ready ->
                    if (value.isBlank() && !hasAttachments) ComposerStatus.IdleEmpty
                    else ComposerStatus.Ready
        }

private fun List<Uri>.toAttachmentUploadFiles(context: Context): List<AttachmentUploadFile> =
        mapNotNull { uri ->
            context.toAttachmentUploadFile(uri)
        }

private fun List<AttachmentUploadFile>.toPendingAttachments(
        status: PendingAttachmentStatus,
        failedIds: Set<String>
): List<PendingAttachment> = map { file ->
    PendingAttachment(
            id = file.id,
            fileName = file.fileName,
            mimeType = file.mimeType,
            sizeLabel = file.sizeBytes?.let(::formatFileSize).orEmpty(),
            status = if (file.id in failedIds) PendingAttachmentStatus.Failed else status,
            previewUri = file.previewUri
    )
}

private fun formatFileSize(sizeBytes: Long): String =
        when {
            sizeBytes >= 1024L * 1024L -> "${sizeBytes / (1024L * 1024L)} MB"
            sizeBytes >= 1024L -> "${sizeBytes / 1024L} KB"
            else -> "$sizeBytes B"
        }

private data class ComposerDraft(
        val value: String,
        val asTask: Boolean,
        val attachments: List<AttachmentUploadFile>,
        val failedAttachmentIds: Set<String>
) {
    val isEmpty: Boolean
        get() = value.isBlank() && attachments.isEmpty() && !asTask
}

internal data class ComposerSubmissionDraft(
        val value: String,
        val asTask: Boolean,
        val attachments: List<AttachmentUploadFile>,
        val failedAttachmentIds: Set<String>
) {
    val body: String
        get() = value.trim()

    val hasContent: Boolean
        get() = body.isNotEmpty() || attachments.isNotEmpty()

    fun withAllAttachmentsFailed(): ComposerSubmissionDraft =
            copy(failedAttachmentIds = attachments.map { it.id }.toSet())
}

private fun Map<String, ComposerDraft>.saveComposerDraft(
        threadId: String?,
        value: String,
        asTask: Boolean,
        attachments: List<AttachmentUploadFile>,
        failedAttachmentIds: Set<String>
): Map<String, ComposerDraft> {
    if (threadId == null) return this
    val draft =
            ComposerDraft(
                    value = value,
                    asTask = asTask,
                    attachments = attachments,
                    failedAttachmentIds = failedAttachmentIds
            )
    return if (draft.isEmpty) {
        this - threadId
    } else {
        this + (threadId to draft)
    }
}

private fun Map<String, ComposerDraft>.saveComposerDraft(
        threadId: String?,
        draft: ComposerSubmissionDraft
): Map<String, ComposerDraft> =
        saveComposerDraft(
                threadId = threadId,
                value = draft.value,
                asTask = draft.asTask,
                attachments = draft.attachments,
                failedAttachmentIds = draft.failedAttachmentIds
        )

private fun Context.toAttachmentUploadFile(uri: Uri): AttachmentUploadFile? {
    val resolver = contentResolver
    val metadata =
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    val name = nameIndex.takeIf { it >= 0 }?.let(cursor::getString)
                    val size =
                            sizeIndex.takeIf { it >= 0 }?.let { cursor.getLong(it) }?.takeIf {
                                it >= 0
                            }
                    name to size
                } else {
                    null
                }
            }
    val bytes =
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    ?: return null
    val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    return AttachmentUploadFile(
            id = uri.toString(),
            fileName = metadata?.first?.takeIf { it.isNotBlank() } ?: fallbackName,
            mimeType = resolver.getType(uri),
            sizeBytes = metadata?.second ?: bytes.size.toLong(),
            previewUri = uri.toString(),
            bytes = bytes
    )
}

private fun ThreadUiState.withComposerStatus(
        status: ComposerStatus,
        errorCode: String? = null,
        noPermissionCode: String? = null
): ThreadUiState =
        copy(
                composerState =
                        composerState.copy(
                                status = status,
                                errorCode = errorCode,
                                noPermissionCode = noPermissionCode
                        )
        )

private fun ThreadUiState.appendSentMessage(result: SendMessageResult): ThreadUiState {
    val current =
            loadState as? ScreenLoadState.Content
                    ?: return withComposerStatus(ComposerStatus.IdleEmpty)
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            current.data.copy(
                                    messages =
                                            (current.data.messages +
                                                            result.message.copy(
                                                                    deliveryState =
                                                                            MessageDeliveryState
                                                                                    .Sent
                                                            ))
                                                    .distinctBy { it.id }
                                                    .sortedForThreadTimeline()
                            )
                    ),
            composerState = ComposerState(status = ComposerStatus.IdleEmpty)
    )
}

private fun ThreadUiState.upsertOptimisticMessage(message: Message): ThreadUiState {
    val current = loadState as? ScreenLoadState.Content ?: return this
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            current.data.copy(
                                    messages =
                                            (current.data.messages.filterNot { it.id == message.id } +
                                                            message)
                                                    .sortedForThreadTimeline()
                            )
                    )
    )
}

private fun ThreadUiState.removeMessage(messageId: MessageId): ThreadUiState {
    val current = loadState as? ScreenLoadState.Content ?: return this
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            current.data.copy(
                                    messages =
                                            current.data.messages.filterNot {
                                                it.id == messageId
                                            }
                            )
                    )
    )
}

private fun ThreadUiState.isOlderPageLoading(): Boolean =
        ((loadState as? ScreenLoadState.Content)?.data?.olderMessagesStatus ==
                MessagePageStatus.Loading)

private fun ThreadUiState.isNewerPageLoading(): Boolean =
        ((loadState as? ScreenLoadState.Content)?.data?.newerMessagesStatus ==
                MessagePageStatus.Loading)

private fun ThreadUiState.withOlderPageStatus(
        status: MessagePageStatus,
        errorCode: String? = null,
        anchorMessageId: MessageId? = null,
        anchorOffset: Int? = null
): ThreadUiState {
    val current = loadState as? ScreenLoadState.Content ?: return this
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            current.data.copy(
                                    olderMessagesStatus = status,
                                    olderMessagesErrorCode = errorCode,
                                    anchorMessageId = anchorMessageId
                                                    ?: current.data.anchorMessageId,
                                    anchorOffset = anchorOffset ?: current.data.anchorOffset
                            )
                    )
    )
}

private fun ThreadUiState.withNewerPageStatus(
        status: MessagePageStatus,
        errorCode: String? = null
): ThreadUiState {
    val current = loadState as? ScreenLoadState.Content ?: return this
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            current.data.copy(
                                    newerMessagesStatus = status,
                                    newerMessagesErrorCode = errorCode
                            )
                    )
    )
}

private fun mergeOlderThreadPage(
        current: ThreadUiState,
        next: DataResult<ThreadSnapshot>
): ThreadUiState {
    val currentContent = (current.loadState as? ScreenLoadState.Content)?.data
    return when (next) {
        is DataResult.Content -> {
            val mergedMessages =
                    ((next.data.messages) + currentContent.orEmptyMessages())
                            .distinctBy { it.id }
                            .sortedForThreadTimeline()
            val content =
                    (currentContent ?: next.data.toThreadContent()).copy(
                            messages = mergedMessages,
                            canLoadMoreBefore = next.data.canLoadMoreBefore,
                            hasGap = next.data.hasGap,
                            historyLimited = next.data.historyLimited,
                            highlightedMessageId = next.data.highlightedMessageId
                                            ?: currentContent?.highlightedMessageId,
                            olderMessagesStatus =
                                    if (next.data.canLoadMoreBefore) MessagePageStatus.Idle
                                    else MessagePageStatus.EndReached,
                            olderMessagesErrorCode = null,
                            anchorMessageId = currentContent?.anchorMessageId
                    )
            current.copy(loadState = ScreenLoadState.Content(content))
        }
        is DataResult.Empty -> current.withOlderPageStatus(MessagePageStatus.EndReached)
        is DataResult.Error -> current.withOlderPageStatus(MessagePageStatus.Error, next.error.code)
        is DataResult.NoPermission ->
                current.withOlderPageStatus(MessagePageStatus.Error, next.blocker.code)
    }
}

private fun DataResult<ThreadSnapshot>.toOlderPageLog(threadId: String, beforeSeq: Long): String =
        when (this) {
            is DataResult.Content ->
                    "older_result threadId=$threadId beforeSeq=$beforeSeq result=content messageCount=${data.messages.size} canLoadMoreBefore=${data.canLoadMoreBefore}"
            is DataResult.Empty ->
                    "older_result threadId=$threadId beforeSeq=$beforeSeq result=empty code=${reason.name}"
            is DataResult.Error ->
                    "older_result threadId=$threadId beforeSeq=$beforeSeq result=error code=${error.code}"
            is DataResult.NoPermission ->
                    "older_result threadId=$threadId beforeSeq=$beforeSeq result=no_permission code=${blocker.code}"
        }

private fun mergeNewerThreadPage(
        current: ThreadUiState,
        next: DataResult<ThreadSnapshot>
): ThreadUiState {
    val currentContent = (current.loadState as? ScreenLoadState.Content)?.data
    return when (next) {
        is DataResult.Content -> {
            val mergedMessages =
                    (currentContent.orEmptyMessages() + next.data.messages)
                            .distinctBy { it.id }
                            .sortedForThreadTimeline()
            val content =
                    (currentContent ?: next.data.toThreadContent()).copy(
                            messages = mergedMessages,
                            canLoadMoreAfter = next.data.canLoadMoreAfter,
                            hasGap = next.data.hasGap,
                            historyLimited = next.data.historyLimited,
                            newerMessagesStatus =
                                    if (next.data.canLoadMoreAfter) MessagePageStatus.Idle
                                    else MessagePageStatus.EndReached,
                            newerMessagesErrorCode = null
                    )
            current.copy(loadState = ScreenLoadState.Content(content))
        }
        is DataResult.Empty -> current.withNewerPageStatus(MessagePageStatus.EndReached)
        is DataResult.Error -> current.withNewerPageStatus(MessagePageStatus.Error, next.error.code)
        is DataResult.NoPermission ->
                current.withNewerPageStatus(MessagePageStatus.Error, next.blocker.code)
    }
}

private fun ThreadContent?.orEmptyMessages(): List<Message> = this?.messages.orEmpty()

private fun ThreadSnapshot.toThreadContent(
        fallbackHeader: ConversationSummary? = null
): ThreadContent =
        ThreadContent(
                header = conversation.withTitleFallback(fallbackHeader),
                messages = messages,
                canLoadMoreBefore = canLoadMoreBefore,
                canLoadMoreAfter = canLoadMoreAfter,
                hasGap = hasGap,
                historyLimited = historyLimited,
                highlightedMessageId = highlightedMessageId
        )

private fun DataResult<TaskSnapshot>.toTasksUiState(selectedFilter: TaskStatus?): TasksUiState =
        TasksUiState(
                loadState =
                        when (this) {
                            is DataResult.Content ->
                                    ScreenLoadState.Content(
                                            TasksContent(
                                                    summaryCounts = data.summaryCounts,
                                                    tasks = data.tasks
                                            )
                                    )
                            is DataResult.Empty -> ScreenLoadState.Empty(reason)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        },
                selectedFilter = selectedFilter
        )

private fun TaskSnapshot.filteredForTasksFilter(filter: TaskStatus?): TaskSnapshot {
    val filteredTasks = filter?.let { status -> tasks.filter { it.status == status } } ?: tasks
    return copy(
            tasks = filteredTasks,
            summaryCounts = TaskStatus.entries.associateWith { status ->
                tasks.count { it.status == status }
            }
    )
}

private fun TaskSnapshot.filteredForCurrentServerTasks(
        homeState: HomeUiState,
        serverId: ServerId
): TaskSnapshot {
    val homeContent =
            (homeState.loadState as? ScreenLoadState.Content)
                    ?.data
                    ?.takeIf { it.server.id == serverId }
    val knownConversationIds =
            homeContent
                    ?.let { content ->
                        (content.channels + content.directMessages)
                                .flatMap { conversation ->
                                    listOfNotNull(
                                            conversation.channelId?.value,
                                            conversation.threadId.value,
                                            conversation.thread?.streamChannelId?.value,
                                            conversation.thread?.parentChannelId?.value
                                    )
                                }
                                .toSet()
                    }
                    .orEmpty()
    val filteredTasks =
            tasks.filter { task ->
                if (knownConversationIds.isNotEmpty()) {
                    listOfNotNull(
                                    task.channelId?.value,
                                    task.threadId?.value
                            )
                            .any { it in knownConversationIds }
                } else {
                    task.channelId != null || task.threadId != null
                }
            }
    return copy(
            tasks = filteredTasks,
            summaryCounts =
                    TaskStatus.entries.associateWith { status ->
                        filteredTasks.count { it.status == status }
                    }
    )
}

private fun TasksUiState.mergeTasksRefreshResult(
        result: DataResult<TaskSnapshot>,
        selectedFilter: TaskStatus?
): TasksUiState =
        when (result) {
            is DataResult.Content ->
                    result.toTasksUiState(selectedFilter).copy(refreshing = false)
            is DataResult.Empty ->
                    takeIf { loadState is ScreenLoadState.Content }
                            ?.copy(
                                    selectedFilter = selectedFilter,
                                    refreshing = false,
                                    navigationErrorCode = result.reason.name
                            )
                            ?: result.toTasksUiState(selectedFilter).copy(refreshing = false)
            is DataResult.Error ->
                    takeIf { loadState is ScreenLoadState.Content }
                            ?.copy(
                                    selectedFilter = selectedFilter,
                                    refreshing = false,
                                    navigationErrorCode = result.error.code
                            )
                            ?: result.toTasksUiState(selectedFilter).copy(refreshing = false)
            is DataResult.NoPermission ->
                    takeIf { loadState is ScreenLoadState.Content }
                            ?.copy(
                                    selectedFilter = selectedFilter,
                                    refreshing = false,
                                    navigationErrorCode = result.blocker.code
                            )
                            ?: result.toTasksUiState(selectedFilter).copy(refreshing = false)
        }

private fun TasksUiState?.mergeThreadTasksRefreshResult(
        result: DataResult<TaskSnapshot>
): TasksUiState {
    val existing = this
    return when (result) {
        is DataResult.Content -> result.toTasksUiState(null).copy(refreshing = false)
        is DataResult.Empty ->
                existing
                        ?.takeIf { it.loadState is ScreenLoadState.Content }
                        ?.copy(refreshing = false)
                        ?: result.toTasksUiState(null).copy(refreshing = false)
        is DataResult.Error ->
                existing
                        ?.takeIf { it.loadState is ScreenLoadState.Content }
                        ?.withRefreshFailure(result.error.code)
                        ?: result.toTasksUiState(null).copy(refreshing = false)
        is DataResult.NoPermission ->
                existing
                        ?.takeIf { it.loadState is ScreenLoadState.Content }
                        ?.withRefreshFailure(result.blocker.code)
                        ?: result.toTasksUiState(null).copy(refreshing = false)
    }
}

private fun List<TaskSummary>.toTasksUiStateForThreadTasks(): TasksUiState {
    return TasksUiState(
            loadState =
                    if (isEmpty()) {
                        ScreenLoadState.Empty(EmptyReason.NoTasks)
                    } else {
                        ScreenLoadState.Content(
                                TasksContent(
                                        summaryCounts = summaryCounts(),
                                        tasks = this
                                )
                        )
                    }
    )
}

private fun List<TaskSummary>.matchingTaskReference(label: String): List<TaskSummary> {
    val key = threadMarkdownTaskReferenceKey(label)
    if (key.isBlank()) return emptyList()
    return filter { task ->
        task.taskNumber?.toString() == key ||
                threadMarkdownTaskReferenceKey(task.title) == key
    }
}

private fun TaskSummary.mergeTaskStatusAction(actionTask: TaskSummary): TaskSummary =
        copy(
                title = actionTask.title.ifBlank { title },
                status = actionTask.status,
                taskNumber = actionTask.taskNumber ?: taskNumber,
                messageId = actionTask.messageId ?: messageId,
                parentMessageId = actionTask.parentMessageId ?: parentMessageId,
                channelId = actionTask.channelId ?: channelId,
                threadId = actionTask.threadId ?: threadId,
                isThreadTask = actionTask.isThreadTask || isThreadTask,
                assignee = actionTask.assignee ?: assignee,
                channelName = actionTask.channelName ?: channelName,
                updatedAtLabel = actionTask.updatedAtLabel.ifBlank { updatedAtLabel },
                canClaim = actionTask.canClaim
        )

internal fun TaskSummary.mergeTaskReferenceCacheUpdate(incomingTask: TaskSummary): TaskSummary =
        incomingTask.withTaskReferenceFieldsFrom(this)

internal fun TaskSummary.withTaskReferenceFieldsFrom(referenceTask: TaskSummary): TaskSummary =
        copy(
                title = title.ifBlank { referenceTask.title },
                status = status,
                taskNumber = taskNumber ?: referenceTask.taskNumber,
                messageId = messageId ?: referenceTask.messageId,
                parentMessageId = parentMessageId ?: referenceTask.parentMessageId,
                channelId = channelId ?: referenceTask.channelId,
                threadId = threadId ?: referenceTask.threadId,
                isThreadTask = isThreadTask || referenceTask.isThreadTask,
                assignee = assignee ?: referenceTask.assignee,
                channelName = channelName ?: referenceTask.channelName,
                updatedAtLabel = updatedAtLabel.ifBlank { referenceTask.updatedAtLabel },
                canClaim = canClaim
        )

private fun Map<TaskId, TaskSummary>.findTaskForMessage(message: Message): TaskSummary? =
        values.firstOrNull { task -> task.matchesMessageReference(message) }

private fun TaskSummary.matchesMessageReference(message: Message): Boolean =
        messageId == message.id ||
                parentMessageId == message.id ||
                id == message.task?.id ||
                taskNumber != null && taskNumber == message.task?.taskNumber

private fun TasksUiState.withCachedTaskReferences(
        cache: Map<TaskId, TaskSummary>
): TasksUiState =
        copy(
                loadState =
                        (loadState as? ScreenLoadState.Content)?.let { contentState ->
                            ScreenLoadState.Content(
                                    contentState.data.copy(
                                            tasks =
                                                    contentState.data.tasks.map { task ->
                                                        task.withTaskReferenceFieldsFrom(
                                                                cache[task.id] ?: task
                                                        )
                                                    }
                                    )
                            )
                        } ?: loadState
        )

private fun TasksUiState.applyRealtimeEvent(event: RealtimeEvent): TasksUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val updatedTasks =
            when (event) {
                is TaskCreated ->
                        (contentState.data.tasks + event.tasks).distinctBy { it.id }
                is TaskUpdated -> contentState.data.tasks.replaceOrAppend(event.task)
                is TaskDeleted ->
                        contentState.data.tasks.filterNot { it.id == event.taskId }
                else -> return this
            }.filter { task -> selectedFilter == null || task.status == selectedFilter }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            contentState.data.copy(
                                    tasks = updatedTasks,
                                    summaryCounts = updatedTasks.summaryCounts()
                            )
                    )
    )
}

private fun List<TaskSummary>.replaceOrAppend(task: TaskSummary): List<TaskSummary> =
        if (any { it.id == task.id }) {
            map { current -> if (current.id == task.id) task else current }
        } else {
            this + task
        }

private fun List<TaskSummary>.summaryCounts(): Map<TaskStatus, Int> =
        TaskStatus.entries.associateWith { status -> count { it.status == status } }

private fun <T> DataResult<T>.toScreenLoadState(): ScreenLoadState<T> =
        when (this) {
            is DataResult.Content -> ScreenLoadState.Content(data)
            is DataResult.Empty -> ScreenLoadState.Empty(reason)
            is DataResult.Error -> ScreenLoadState.Error(error)
            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
        }

private fun <T> DataResult<T>.settingsServerFeedback(success: String): String =
        when (this) {
            is DataResult.Content -> success
            is DataResult.Empty -> "No settings data was returned."
            is DataResult.Error -> "Action failed: ${error.code}"
            is DataResult.NoPermission -> "Action failed: ${blocker.code}"
        }

private fun DataResult<MemberSnapshot>.toMembersUiState(): MembersUiState =
        MembersUiState(
                loadState =
                        when (this) {
                            is DataResult.Content -> {
                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                        "SlockMembers",
                                        "ui_state_count source=members_page serverId=${data.server.id.value} " +
                                                "agentsCount=${data.members.count { it.kind == MemberKind.Agent }} humansCount=${data.members.count { it.kind == MemberKind.Human }}"
                                )
                                ScreenLoadState.Content(
                                        MembersContent(server = data.server, members = data.members)
                                )
                            }
                            is DataResult.Empty -> ScreenLoadState.Empty(reason)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        }
        )

private fun MembersUiState.withMemberProfile(member: MemberProfile): MembersUiState =
        when (val loadState = loadState) {
            is ScreenLoadState.Content -> {
                val members = loadState.data.members.replaceMemberPreservingOrder(member)
                copy(loadState = ScreenLoadState.Content(loadState.data.copy(members = members)))
            }
            else -> this
        }

internal fun List<MemberProfile>.replaceMemberPreservingOrder(member: MemberProfile):
        List<MemberProfile> {
    var replaced = false
    val updated =
            map { existing ->
                if (existing.id == member.id) {
                    replaced = true
                    member
                } else {
                    existing
                }
            }
    return if (replaced) updated else updated + member
}

private fun DataResult<AgentDmSnapshot>.toAgentDmsLoadState(): ScreenLoadState<AgentDmSnapshot> =
        when (this) {
            is DataResult.Content -> ScreenLoadState.Content(data)
            is DataResult.Empty -> ScreenLoadState.Empty(reason)
            is DataResult.Error -> ScreenLoadState.Error(error)
            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
        }

private fun DataResult<AgentReminderSnapshot>.toAgentRemindersLoadState():
        ScreenLoadState<AgentReminderSnapshot> =
        when (this) {
            is DataResult.Content -> ScreenLoadState.Content(data)
            is DataResult.Empty -> ScreenLoadState.Empty(reason)
            is DataResult.Error -> ScreenLoadState.Error(error)
            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
        }

private fun DataResult<AgentActivitySnapshot>.toAgentActivityLoadState():
        ScreenLoadState<AgentActivitySnapshot> =
        when (this) {
            is DataResult.Content -> ScreenLoadState.Content(data)
            is DataResult.Empty -> ScreenLoadState.Empty(reason)
            is DataResult.Error -> ScreenLoadState.Error(error)
            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
        }

private fun DataResult<AgentWorkspaceSnapshot>.toAgentWorkspacesLoadState():
        ScreenLoadState<AgentWorkspaceSnapshot> =
        when (this) {
            is DataResult.Content -> ScreenLoadState.Content(data)
            is DataResult.Empty -> ScreenLoadState.Empty(reason)
            is DataResult.Error -> ScreenLoadState.Error(error)
            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
        }

private fun DataResult<AgentWorkspaceTreeSnapshot>.toWorkspaceTreeState(
        workspacesState: ScreenLoadState<AgentWorkspaceSnapshot>
): SlockWorkspaceTreeState =
        when (this) {
            is DataResult.Content -> {
                val rootPath =
                        (workspacesState as? ScreenLoadState.Content)
                                ?.data
                                ?.workspaces
                                ?.firstOrNull()
                                ?.path
                                ?.takeIf { it.isNotBlank() }
                                ?: data.rootPath
                SlockWorkspaceTreeState.Content(
                        rootPath = rootPath,
                        root = data.root,
                        expandedNodeIds = setOf(data.root.id)
                )
            }
            is DataResult.Empty -> SlockWorkspaceTreeState.Empty
            is DataResult.Error -> SlockWorkspaceTreeState.Error(error.code)
            is DataResult.NoPermission -> SlockWorkspaceTreeState.NoPermission
        }

private fun DataResult<AgentSkillsSnapshot>.toAgentSkillsLoadState():
        ScreenLoadState<AgentSkillsSnapshot> =
        when (this) {
            is DataResult.Content -> ScreenLoadState.Content(data)
            is DataResult.Empty -> ScreenLoadState.Empty(reason)
            is DataResult.Error -> ScreenLoadState.Error(error)
            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
        }

private data class AgentDetailParts(
        val dms: DataResult<AgentDmSnapshot>,
        val reminders: DataResult<AgentReminderSnapshot>,
        val activity: DataResult<AgentActivitySnapshot>,
        val workspaces: DataResult<AgentWorkspaceSnapshot>,
        val workspaceTree: DataResult<AgentWorkspaceTreeSnapshot>,
        val skills: DataResult<AgentSkillsSnapshot>
)

private fun SlockWorkspaceTreeState.Content.withWorkspaceChildren(
        dirPath: String,
        children: List<SlockWorkspaceNode>
): SlockWorkspaceTreeState.Content =
        copy(root = root.replaceWorkspaceNode(dirPath) { it.copy(children = children) })

private fun SlockWorkspaceTreeState.Content.withWorkspacePreview(
        path: String,
        preview: SlockWorkspaceFilePreview
): SlockWorkspaceTreeState.Content =
        copy(
                root = root.replaceWorkspaceNode(path) { it.copy(preview = preview) },
                selectedFileId = path,
                showPreview = true
        )

private fun SlockWorkspaceNode.replaceWorkspaceNode(
        targetPath: String,
        transform: (SlockWorkspaceNode) -> SlockWorkspaceNode
): SlockWorkspaceNode {
    if (path == targetPath || id == targetPath) return transform(this)
    return copy(children = children.map { it.replaceWorkspaceNode(targetPath, transform) })
}

private fun SlockWorkspaceNode.findWorkspaceNodeById(targetId: String): SlockWorkspaceNode? {
    if (id == targetId || path == targetId) return this
    return children.firstNotNullOfOrNull { it.findWorkspaceNodeById(targetId) }
}

private fun DataResult<ChannelMemberSnapshot>.toThreadMembersPanelLoadState():
        ScreenLoadState<ThreadMembersPanelContent> =
        when (this) {
            is DataResult.Content ->
                    ScreenLoadState.Content(
                            ThreadMembersPanelContent(
                                    members = data.members,
                                    canAddMembers = data.canAddMembers
                            )
                    )
            is DataResult.Empty -> ScreenLoadState.Empty(reason)
            is DataResult.Error -> ScreenLoadState.Error(error)
            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
        }

private fun ThreadMembersPanelState.threadAuthorProfiles(): List<MemberProfile> {
    val panelMembers = (loadState as? ScreenLoadState.Content)?.data?.members.orEmpty()
    val candidates = (candidatesLoadState as? ScreenLoadState.Content)?.data?.candidates.orEmpty()
    return panelMembers + candidates
}

private fun List<Message>.withThreadAuthorProfiles(profiles: List<MemberProfile>): List<Message> {
    if (isEmpty() || profiles.isEmpty()) return this
    val directory = profiles.toThreadAuthorDirectory()
    return map { message ->
        message.withThreadAuthorProfiles(directory)
    }
}

private fun Message.withThreadAuthorProfiles(profiles: List<MemberProfile>): Message {
    if (profiles.isEmpty()) return this
    return withThreadAuthorProfiles(profiles.toThreadAuthorDirectory())
}

private fun Message.withThreadAuthorProfiles(
        directory: Map<String, MemberProfile>
): Message {
    val profile = directory.findProfile(author) ?: return this
    val mergedTask =
            task?.let { currentTask ->
                currentTask.assignee?.let { assignee ->
                    directory.findProfile(assignee)?.let { profile ->
                        currentTask.copy(
                                assignee = assignee.withThreadAuthorProfile(profile)
                        )
                    }
                } ?: currentTask
            }
    return copy(
            author = author.withThreadAuthorProfile(profile),
            task = mergedTask
    )
}

private fun List<MemberProfile>.toThreadAuthorDirectory(): Map<String, MemberProfile> {
    val directory = linkedMapOf<String, MemberProfile>()
    forEach { profile ->
        listOf(profile.id.value, profile.handle, profile.displayName)
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .forEach { key ->
                    val existing = directory[key]
                    if (existing == null ||
                                    (!existing.hasAvatarReference && profile.hasAvatarReference)
                    ) {
                        directory[key] = profile
                    }
                }
    }
    return directory
}

private fun Map<String, MemberProfile>.findProfile(author: MemberProfile): MemberProfile? =
        listOf(author.id.value, author.handle, author.displayName)
                .map { it.trim().lowercase() }
                .firstNotNullOfOrNull { this[it] }

private val MemberProfile.hasAvatarReference: Boolean
    get() = avatar != null || !avatarUrl.isNullOrBlank()

private fun MemberProfile.withThreadAuthorProfile(profile: MemberProfile): MemberProfile {
    val mergedAvatarUrl = avatarUrl ?: profile.avatarUrl
    return copy(
            displayName = displayName.takeUnless { it.isBlank() || it == id.value }
                            ?: profile.displayName,
            handle = handle.takeUnless { it.isBlank() || it == id.value } ?: profile.handle,
            kind =
                    if (kind == MemberKind.Human && profile.kind == MemberKind.Agent)
                            MemberKind.Agent
                    else kind,
            description = description ?: profile.description,
            roleDescription = roleDescription ?: profile.roleDescription,
            avatarUrl = mergedAvatarUrl,
            avatar = avatar ?: profile.avatar ?: AvatarReference.fromWireValue(mergedAvatarUrl),
            avatarLabel = avatarLabel.takeIf { it.isNotBlank() } ?: profile.avatarLabel,
            presence = if (presence == Presence.Offline) profile.presence else presence,
            agentStatus = agentStatus ?: profile.agentStatus,
            permissionBadges =
                    if (permissionBadges.isEmpty()) profile.permissionBadges else permissionBadges
    )
}

private fun DataResult<MemberSnapshot>?.toThreadMembersPickerLoadState():
        ScreenLoadState<ThreadMembersPickerContent> =
        when (this) {
            is DataResult.Content ->
                    ScreenLoadState.Content(ThreadMembersPickerContent(candidates = data.members))
            is DataResult.Empty -> ScreenLoadState.Empty(reason)
            is DataResult.Error -> ScreenLoadState.Error(error)
            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
            null -> ScreenLoadState.Empty(EmptyReason.NoMembers)
        }

@Composable
private fun SettingsScreen(
        state: ai.slock.android.feature.settings.SettingsUiState,
        destination: SettingsDestination,
        onDestinationChange: (SettingsDestination) -> Unit,
        adaptiveLayoutInfo: SlockAdaptiveAppLayoutInfo,
        accountState: ScreenLoadState<AccountSettingsSnapshot>,
        computersState: ScreenLoadState<SettingsComputersSnapshot>,
        computerDetailState: ScreenLoadState<SettingsComputerDetailSnapshot>,
        serverState: ScreenLoadState<SettingsServerSnapshot>,
        billingState: ScreenLoadState<SettingsBillingSnapshot>,
        mobileState: ScreenLoadState<SettingsMobileSnapshot>,
        storageSnapshot: StorageManagementSnapshot,
        storageActionInProgress: StorageClearTarget?,
        storageFeedback: String?,
        aboutSnapshot: SettingsAboutSnapshot,
        notificationPermissionGranted: Boolean,
        notificationPermissionLauncherAvailable: Boolean,
        localeSnapshot: LocaleSnapshot,
        onLocaleSelected: (AppLocale) -> Unit,
        chatLayoutPreferenceSnapshot: ChatLayoutPreferenceSnapshot,
        onMyMessageAlignmentSelected: (MyMessageAlignmentPreference) -> Unit,
        onLargeScreenNavigationSelected: (LargeScreenNavigationPreference) -> Unit,
        notificationsEnabledByUser: Boolean,
        onNotificationsEnabledByUserChange: (Boolean) -> Unit,
        onOpenComputerDetail: (String) -> Unit,
        onSaveServerProfile: (String) -> Unit,
        onSendServerInvite: (String) -> Unit,
        onDeleteServer: (String) -> Unit,
        serverActionFeedback: String?,
        serverActionInProgress: Boolean,
        onLogout: () -> Unit,
        onRetryComputerDetail: () -> Unit,
        onRequestNotificationPermission: () -> Unit,
        onSendTestNotification: () -> Unit,
        onOpenNotificationSettings: () -> Unit,
        onRefreshStorage: () -> Unit,
        onClearStorage: (StorageClearTarget) -> Unit,
        onCopyDiagnostics: () -> Unit,
        onShareDiagnostics: () -> Unit,
        onClearDiagnostics: () -> Unit,
        onSendFeedback: () -> Unit
) {
    val wideSettingsMode = adaptiveLayoutInfo.mode != SlockWindowLayoutMode.CompactSinglePane
    val bottomNavigationContentPadding =
            if (adaptiveLayoutInfo.usesNavigationRail) {
                0.dp
            } else {
                SlockAdaptiveBottomContentPadding(
                        layoutInfo = adaptiveLayoutInfo,
                        showNavigation = true
                )
            }
    val effectiveDestination =
            if (wideSettingsMode && destination == SettingsDestination.Home) {
                SettingsDestination.Account
            } else {
                destination
            }

    if (!wideSettingsMode && destination != SettingsDestination.Home) {
        BackHandler {
            onDestinationChange(
                    when (destination) {
                        SettingsDestination.ComputerDetail -> SettingsDestination.Computers
                        SettingsDestination.Diagnostics -> SettingsDestination.About
                        else -> SettingsDestination.Home
                    }
            )
        }
    }

    LoadStateScreen(
            state = state.loadState,
            title = stringResource(R.string.settings_home_title),
            emptyTitle = stringResource(R.string.settings_home_title),
            emptyDetail = stringResource(R.string.common_preparing_local_state),
            contentPadding = 0.dp,
            backgroundColor = SlockColor.BrandYellow
    ) { content ->
        if (wideSettingsMode) {
            SettingsAdaptiveScaffold(
                    content = content,
                    localeSnapshot = localeSnapshot,
                    selectedDestination = effectiveDestination,
                    bottomContentPadding = bottomNavigationContentPadding,
                    onDestinationChange = onDestinationChange
            ) {
                SettingsDestinationContent(
                        destination = effectiveDestination,
                        compact = false,
                        adaptiveLayoutInfo = adaptiveLayoutInfo,
                        bottomContentPadding = bottomNavigationContentPadding,
                        onDestinationChange = onDestinationChange,
                        accountState = accountState,
                        computersState = computersState,
                        computerDetailState = computerDetailState,
                        serverState = serverState,
                        billingState = billingState,
                        mobileState = mobileState,
                        storageSnapshot = storageSnapshot,
                        storageActionInProgress = storageActionInProgress,
                        storageFeedback = storageFeedback,
                        aboutSnapshot = aboutSnapshot,
                        notificationPermissionGranted = notificationPermissionGranted,
                        notificationPermissionLauncherAvailable =
                                notificationPermissionLauncherAvailable,
                        localeSnapshot = localeSnapshot,
                        onLocaleSelected = onLocaleSelected,
                        chatLayoutPreferenceSnapshot = chatLayoutPreferenceSnapshot,
                        onMyMessageAlignmentSelected = onMyMessageAlignmentSelected,
                        onLargeScreenNavigationSelected = onLargeScreenNavigationSelected,
                        notificationsEnabledByUser = notificationsEnabledByUser,
                        onNotificationsEnabledByUserChange =
                                onNotificationsEnabledByUserChange,
                        onOpenComputerDetail = onOpenComputerDetail,
                        onSaveServerProfile = onSaveServerProfile,
                        onSendServerInvite = onSendServerInvite,
                        onDeleteServer = onDeleteServer,
                        serverActionFeedback = serverActionFeedback,
                        serverActionInProgress = serverActionInProgress,
                        onLogout = onLogout,
                        onRetryComputerDetail = onRetryComputerDetail,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onSendTestNotification = onSendTestNotification,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onRefreshStorage = onRefreshStorage,
                        onClearStorage = onClearStorage,
                        onCopyDiagnostics = onCopyDiagnostics,
                        onShareDiagnostics = onShareDiagnostics,
                        onClearDiagnostics = onClearDiagnostics,
                        onSendFeedback = onSendFeedback
                )
            }
        } else {
            when (destination) {
                SettingsDestination.Home ->
                        SettingsHomeContent(
                                content = content,
                                localeSnapshot = localeSnapshot,
                                selectedDestination = destination,
                                bottomContentPadding = bottomNavigationContentPadding,
                                onDestinationChange = onDestinationChange
                        )
                else ->
                        SettingsDestinationContent(
                                destination = destination,
                                compact = true,
                                adaptiveLayoutInfo = adaptiveLayoutInfo,
                                bottomContentPadding = bottomNavigationContentPadding,
                                onDestinationChange = onDestinationChange,
                                accountState = accountState,
                                computersState = computersState,
                                computerDetailState = computerDetailState,
                                serverState = serverState,
                                billingState = billingState,
                                mobileState = mobileState,
                                storageSnapshot = storageSnapshot,
                                storageActionInProgress = storageActionInProgress,
                                storageFeedback = storageFeedback,
                                aboutSnapshot = aboutSnapshot,
                                notificationPermissionGranted = notificationPermissionGranted,
                                notificationPermissionLauncherAvailable =
                                        notificationPermissionLauncherAvailable,
                                localeSnapshot = localeSnapshot,
                                onLocaleSelected = onLocaleSelected,
                                chatLayoutPreferenceSnapshot = chatLayoutPreferenceSnapshot,
                                onMyMessageAlignmentSelected = onMyMessageAlignmentSelected,
                                onLargeScreenNavigationSelected =
                                        onLargeScreenNavigationSelected,
                                notificationsEnabledByUser = notificationsEnabledByUser,
                                onNotificationsEnabledByUserChange =
                                        onNotificationsEnabledByUserChange,
                                onOpenComputerDetail = onOpenComputerDetail,
                                onSaveServerProfile = onSaveServerProfile,
                                onSendServerInvite = onSendServerInvite,
                                onDeleteServer = onDeleteServer,
                                serverActionFeedback = serverActionFeedback,
                                serverActionInProgress = serverActionInProgress,
                                onLogout = onLogout,
                                onRetryComputerDetail = onRetryComputerDetail,
                                onRequestNotificationPermission = onRequestNotificationPermission,
                                onSendTestNotification = onSendTestNotification,
                                onOpenNotificationSettings = onOpenNotificationSettings,
                                onRefreshStorage = onRefreshStorage,
                                onClearStorage = onClearStorage,
                                onCopyDiagnostics = onCopyDiagnostics,
                                onShareDiagnostics = onShareDiagnostics,
                                onClearDiagnostics = onClearDiagnostics,
                                onSendFeedback = onSendFeedback
                        )
            }
        }
    }
}

@Composable
private fun SettingsAdaptiveScaffold(
        content: SettingsContent,
        localeSnapshot: LocaleSnapshot,
        selectedDestination: SettingsDestination,
        bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
        onDestinationChange: (SettingsDestination) -> Unit,
        detail: @Composable () -> Unit
) {
    SettingsPageSurface {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                    modifier =
                            Modifier.width(336.dp)
                                    .fillMaxSize()
                                    .background(SlockColor.SurfaceWhite)
                                    .drawWithContent {
                                        drawContent()
                                        val stroke = SlockStroke.Default.toPx()
                                        drawLine(
                                                color = SlockColor.BorderBlack,
                                                start = Offset(size.width - stroke / 2f, 0f),
                                                end =
                                                        Offset(
                                                                size.width - stroke / 2f,
                                                                size.height
                                                        ),
                                                strokeWidth = stroke
                                        )
                                    }
            ) {
                SettingsHomeScreen(
                        title = stringResource(R.string.settings_home_title),
                        subtitle = content.settings.server.name,
                        items =
                                settingsHomeItems(
                                        content = content,
                                        localeSnapshot = localeSnapshot,
                                        selectedDestination = selectedDestination
                                ),
                        showSelection = true,
                        bottomContentPadding = bottomContentPadding,
                        onItemClick = { key ->
                            onDestinationChange(key.toSettingsDestination())
                        }
                )
            }
            Box(
                    modifier =
                            Modifier.weight(1f)
                                    .fillMaxSize()
                                    .background(SlockColor.SurfaceWhite)
            ) {
                detail()
            }
        }
    }
}

@Composable
private fun SettingsHomeContent(
        content: SettingsContent,
        localeSnapshot: LocaleSnapshot,
        selectedDestination: SettingsDestination,
        bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
        onDestinationChange: (SettingsDestination) -> Unit
) {
    SettingsPageSurface {
        SettingsHomeScreen(
                title = stringResource(R.string.settings_home_title),
                subtitle = content.settings.server.name,
                items =
                        settingsHomeItems(
                                content = content,
                                localeSnapshot = localeSnapshot,
                                selectedDestination = selectedDestination
                        ),
                showSelection = false,
                bottomContentPadding = bottomContentPadding,
                onItemClick = { key ->
                    onDestinationChange(key.toSettingsDestination())
                }
        )
    }
}

@Composable
private fun SettingsDestinationContent(
        destination: SettingsDestination,
        compact: Boolean,
        adaptiveLayoutInfo: SlockAdaptiveAppLayoutInfo,
        bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
        onDestinationChange: (SettingsDestination) -> Unit,
        accountState: ScreenLoadState<AccountSettingsSnapshot>,
        computersState: ScreenLoadState<SettingsComputersSnapshot>,
        computerDetailState: ScreenLoadState<SettingsComputerDetailSnapshot>,
        serverState: ScreenLoadState<SettingsServerSnapshot>,
        billingState: ScreenLoadState<SettingsBillingSnapshot>,
        mobileState: ScreenLoadState<SettingsMobileSnapshot>,
        storageSnapshot: StorageManagementSnapshot,
        storageActionInProgress: StorageClearTarget?,
        storageFeedback: String?,
        aboutSnapshot: SettingsAboutSnapshot,
        notificationPermissionGranted: Boolean,
        notificationPermissionLauncherAvailable: Boolean,
        localeSnapshot: LocaleSnapshot,
        onLocaleSelected: (AppLocale) -> Unit,
        chatLayoutPreferenceSnapshot: ChatLayoutPreferenceSnapshot,
        onMyMessageAlignmentSelected: (MyMessageAlignmentPreference) -> Unit,
        onLargeScreenNavigationSelected: (LargeScreenNavigationPreference) -> Unit,
        notificationsEnabledByUser: Boolean,
        onNotificationsEnabledByUserChange: (Boolean) -> Unit,
        onOpenComputerDetail: (String) -> Unit,
        onSaveServerProfile: (String) -> Unit,
        onSendServerInvite: (String) -> Unit,
        onDeleteServer: (String) -> Unit,
        serverActionFeedback: String?,
        serverActionInProgress: Boolean,
        onLogout: () -> Unit,
        onRetryComputerDetail: () -> Unit,
        onRequestNotificationPermission: () -> Unit,
        onSendTestNotification: () -> Unit,
        onOpenNotificationSettings: () -> Unit,
        onRefreshStorage: () -> Unit,
        onClearStorage: (StorageClearTarget) -> Unit,
        onCopyDiagnostics: () -> Unit,
        onShareDiagnostics: () -> Unit,
        onClearDiagnostics: () -> Unit,
        onSendFeedback: () -> Unit
) {
    when (destination) {
        SettingsDestination.Home,
        SettingsDestination.Account ->
                SettingsSecondaryScreen(
                        title = stringResource(R.string.settings_account_title),
                        showBack = destination.showSettingsBackButton(compact),
                        bottomContentPadding = bottomContentPadding,
                        onBack = { onDestinationChange(SettingsDestination.Home) }
                ) {
                    SettingsDataStateContent(
                            state = accountState,
                            emptyTitle = stringResource(R.string.settings_account_title),
                            emptyDetail = stringResource(R.string.common_preparing_local_state)
                    ) { account ->
                        SettingsAccountScreen(
                                profile = account.profile.toSettingsAccountProfile(),
                                connectedAccounts =
                                        account.connectedAccounts.map {
                                            it.toSettingsConnectedAccount()
                                        },
                                showOuterBorder = false,
                                onLogout = onLogout
                        )
                    }
                }
        SettingsDestination.Computers ->
                SettingsSecondaryScreen(
                        title = stringResource(R.string.settings_computers_title),
                        showBack = destination.showSettingsBackButton(compact),
                        bottomContentPadding = bottomContentPadding,
                        onBack = { onDestinationChange(SettingsDestination.Home) }
                ) {
                    SettingsDataStateContent(
                            state = computersState,
                            emptyTitle = stringResource(R.string.settings_computers_empty_title),
                            emptyDetail = stringResource(R.string.settings_computers_empty_detail)
                    ) { computers ->
                        SettingsComputersKuiklyScreen(
                                snapshot = computers,
                                onOpenComputer = onOpenComputerDetail
                        )
                    }
                }
        SettingsDestination.ComputerDetail ->
                SettingsSecondaryScreen(
                        title = stringResource(R.string.settings_computer_detail_title),
                        showBack = destination.showSettingsBackButton(compact),
                        bottomContentPadding = bottomContentPadding,
                        onBack = { onDestinationChange(SettingsDestination.Computers) }
                ) {
                    SettingsDataStateContent(
                            state = computerDetailState,
                            emptyTitle = stringResource(R.string.settings_computer_detail_title),
                            emptyDetail = stringResource(R.string.settings_computer_detail_empty)
                    ) { detail ->
                        SettingsComputerDetailKuiklyScreen(
                                snapshot = detail,
                                onRetryWorkspaces = onRetryComputerDetail,
                                onRetryAgents = onRetryComputerDetail
                        )
                    }
                }
        SettingsDestination.Server ->
                SettingsSecondaryScreen(
                        title = stringResource(R.string.settings_server_title),
                        showBack = destination.showSettingsBackButton(compact),
                        bottomContentPadding = bottomContentPadding,
                        onBack = { onDestinationChange(SettingsDestination.Home) }
                ) {
                    SettingsDataStateContent(
                            state = serverState,
                            emptyTitle = stringResource(R.string.settings_server_title),
                            emptyDetail = stringResource(R.string.settings_unavailable_detail)
                    ) { server ->
                        SettingsServerManagementScreen(
                                snapshot = server,
                                onSaveProfile = onSaveServerProfile,
                                onSendInvite = onSendServerInvite,
                                onDeleteServer = onDeleteServer,
                                actionFeedback = serverActionFeedback,
                                actionInProgress = serverActionInProgress
                        )
                    }
                }
        SettingsDestination.Billing ->
                SettingsSecondaryScreen(
                        title = stringResource(R.string.settings_billing_title),
                        showBack = destination.showSettingsBackButton(compact),
                        bottomContentPadding = bottomContentPadding,
                        onBack = { onDestinationChange(SettingsDestination.Home) }
                ) {
                    SettingsBillingScreen(
                            currentPlan = billingState.toBillingCurrentPlanState(),
                            plans = billingState.toBillingPlans()
                    )
                }
        SettingsDestination.Mobile ->
                SettingsSecondaryScreen(
                        title = stringResource(R.string.settings_mobile_title),
                        showBack = destination.showSettingsBackButton(compact),
                        bottomContentPadding = bottomContentPadding,
                        onBack = { onDestinationChange(SettingsDestination.Home) }
                ) {
                    SettingsDataStateContent(
                            state = mobileState,
                            emptyTitle = stringResource(R.string.settings_mobile_title),
                            emptyDetail = stringResource(R.string.settings_unavailable_detail)
                    ) { mobile ->
                        SettingsMobileScreen(
                                snapshot = mobile,
                                notificationPermissionGranted = notificationPermissionGranted,
                                notificationPermissionAvailable =
                                        notificationPermissionLauncherAvailable,
                                chatLayoutPreference =
                                        chatLayoutPreferenceSnapshot.myMessageAlignment,
                                largeScreenNavigation =
                                        chatLayoutPreferenceSnapshot.largeScreenNavigation,
                                localeSnapshot = localeSnapshot,
                                onRequestNotificationPermission = onRequestNotificationPermission,
                                pushNotificationsEnabledByUser = notificationsEnabledByUser,
                                onPushNotificationsEnabledChange =
                                        onNotificationsEnabledByUserChange,
                                onChatLayoutPreferenceSelected = onMyMessageAlignmentSelected,
                                onLargeScreenNavigationSelected =
                                        onLargeScreenNavigationSelected,
                                onLocaleSelected = onLocaleSelected,
                                onSendTest = onSendTestNotification,
                                onOpenNotificationSettings = onOpenNotificationSettings
                        )
                    }
                }
        SettingsDestination.Storage ->
                SettingsSecondaryScreen(
                        title = stringResource(R.string.settings_storage_title),
                        showBack = destination.showSettingsBackButton(compact),
                        bottomContentPadding = bottomContentPadding,
                        onBack = { onDestinationChange(SettingsDestination.Home) }
                ) {
                    SettingsStorageScreen(
                            snapshot = storageSnapshot,
                            actionInProgress = storageActionInProgress,
                            feedback = storageFeedback,
                            onRefresh = onRefreshStorage,
                            onClear = onClearStorage
                    )
                }
        SettingsDestination.About ->
                SettingsSecondaryScreen(
                        title = stringResource(R.string.settings_about_title),
                        showBack = destination.showSettingsBackButton(compact),
                        bottomContentPadding = bottomContentPadding,
                        onBack = { onDestinationChange(SettingsDestination.Home) }
                ) {
                    SettingsAboutScreen(
                            snapshot = aboutSnapshot,
                            onOpenDiagnostics = {
                                onDestinationChange(SettingsDestination.Diagnostics)
                            }
                    )
                }
        SettingsDestination.Diagnostics ->
                SettingsSecondaryScreen(
                        title = stringResource(R.string.settings_about_diagnostics_title),
                        showBack = destination.showSettingsBackButton(compact),
                        bottomContentPadding = bottomContentPadding,
                        onBack = { onDestinationChange(SettingsDestination.About) }
                ) {
                    SettingsDiagnosticsScreen(
                            logs = aboutSnapshot.diagnosticLogs,
                            onCopyLogs = onCopyDiagnostics,
                            onShareLogs = onShareDiagnostics,
                            onClearLogs = onClearDiagnostics,
                            onSendFeedback = onSendFeedback
                    )
                }
    }
}

private fun String.toSettingsDestination(): SettingsDestination =
        when (this) {
            SettingsDestination.Account.key -> SettingsDestination.Account
            SettingsDestination.Computers.key -> SettingsDestination.Computers
            SettingsDestination.Server.key -> SettingsDestination.Server
            SettingsDestination.Billing.key -> SettingsDestination.Billing
            SettingsDestination.Mobile.key -> SettingsDestination.Mobile
            SettingsDestination.Storage.key -> SettingsDestination.Storage
            SettingsDestination.About.key -> SettingsDestination.About
            else -> SettingsDestination.Home
        }

private fun SettingsDestination.showSettingsBackButton(compact: Boolean): Boolean =
        compact ||
                this == SettingsDestination.ComputerDetail ||
                this == SettingsDestination.Diagnostics

private fun LargeScreenNavigationPreference.toSlockLargeScreenNavigationStyle():
        SlockLargeScreenNavigationStyle =
        when (this) {
            LargeScreenNavigationPreference.LeftRail ->
                    SlockLargeScreenNavigationStyle.NavigationRail
            LargeScreenNavigationPreference.FloatingDock ->
                    SlockLargeScreenNavigationStyle.FloatingDock
        }

@Composable
private fun <T> SettingsDataStateContent(
        state: ScreenLoadState<T>,
        emptyTitle: String,
        emptyDetail: String,
        content: @Composable (T) -> Unit
) {
    when (state) {
        ScreenLoadState.Loading ->
                SlockEmptyState(
                        title = stringResource(R.string.common_loading),
                        detail = stringResource(R.string.common_preparing_local_state)
                )
        is ScreenLoadState.Content -> content(state.data)
        is ScreenLoadState.Empty -> SlockEmptyState(title = emptyTitle, detail = emptyDetail)
        is ScreenLoadState.Error ->
                SlockErrorState(
                        title = stringResource(R.string.common_failed_to_load, emptyTitle),
                        detail = stringResource(R.string.settings_error_detail, state.error.code)
                )
        is ScreenLoadState.NoPermission ->
                SlockEmptyState(
                        title = stringResource(R.string.common_no_permission),
                        detail = state.blocker.requiredCapability ?: state.blocker.code
                )
    }
}

@Composable
private fun SettingsSecondaryScreen(
        title: String,
        showBack: Boolean = true,
        bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
        onBack: () -> Unit,
        content: @Composable () -> Unit
) {
    SettingsPageSurface {
        Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
            SettingsDetailHeader(title = title, showBack = showBack, onBack = onBack)
            Box(modifier = Modifier.weight(1f).padding(bottom = bottomContentPadding)) { content() }
        }
    }
}

@Composable
private fun SettingsPageSurface(content: @Composable () -> Unit) {
    Box(
            modifier =
                    Modifier.fillMaxSize()
                            .background(SlockColor.BrandYellow)
    ) {
        content()
    }
}

private enum class SettingsDestination(val key: String) {
    Home("home"),
    Account("account"),
    Computers("computers"),
    ComputerDetail("computer_detail"),
    Server("server"),
    Billing("billing"),
    Mobile("mobile"),
    Storage("storage"),
    About("about"),
    Diagnostics("diagnostics")
}

@Composable
private fun <T> LoadStateScreen(
        state: ScreenLoadState<T>,
        title: String,
        emptyTitle: String,
        emptyDetail: String,
        contentPadding: androidx.compose.ui.unit.Dp = SlockSpacing.Md,
        backgroundColor: Color = SlockColor.SurfaceWhite,
        content: @Composable (T) -> Unit
) {
    Column(
            modifier =
                    Modifier.fillMaxSize()
                            .background(backgroundColor)
                            .padding(contentPadding)
    ) {
        when (state) {
            ScreenLoadState.Loading -> {
                SlockPageHeader(
                        title = title,
                        subtitle = stringResource(R.string.common_loading_sample_state)
                )
                Spacer(modifier = Modifier.height(SlockSpacing.Md))
                SlockEmptyState(
                        title = stringResource(R.string.common_loading),
                        detail = stringResource(R.string.common_preparing_local_state)
                )
            }
            is ScreenLoadState.Content -> content(state.data)
            is ScreenLoadState.Empty -> {
                SlockPageHeader(title = title, subtitle = state.reason.name)
                Spacer(modifier = Modifier.height(SlockSpacing.Md))
                SlockEmptyState(title = emptyTitle, detail = emptyDetail)
            }
            is ScreenLoadState.Error -> {
                SlockPageHeader(title = title, subtitle = stringResource(R.string.common_error))
                Spacer(modifier = Modifier.height(SlockSpacing.Md))
                SlockErrorState(
                        title = stringResource(R.string.common_failed_to_load, title),
                        detail = stringResource(R.string.settings_error_detail, state.error.code)
                )
            }
            is ScreenLoadState.NoPermission -> {
                SlockPageHeader(
                        title = title,
                        subtitle = stringResource(R.string.common_no_permission)
                )
                Spacer(modifier = Modifier.height(SlockSpacing.Md))
                SlockEmptyState(
                        title = stringResource(R.string.common_no_permission),
                        detail = state.blocker.requiredCapability ?: state.blocker.code
                )
            }
        }
    }
}

@Composable
private fun ConversationCard(
        summary: ConversationSummary,
        selected: Boolean = false,
        onClick: () -> Unit
) {
    Column(
            modifier =
                    Modifier.fillMaxWidth()
                            .padding(
                                    end = SlockShadow.Small.offsetX,
                                    bottom = SlockShadow.Small.offsetY
                            )
                            .slockHardShadow(SlockShadow.Small)
                            .background(
                                    if (selected) SlockColor.BrandYellow
                                    else SlockColor.SurfaceWhite,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .border(
                                    SlockStroke.Default,
                                    SlockColor.BorderBlack,
                                    RoundedCornerShape(SlockRadius.Xs)
                            )
                            .clickable(onClick = onClick)
                            .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                    text = "#${summary.name}",
                    style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
            )
            if (summary.unreadCount > 0) {
                CountBadge(text = summary.unreadCount.toString())
            }
        }
        summary.subtitle?.let {
            Text(it, style = SlockType.Caption, color = SlockColor.TextMutedGray)
        }
        summary.lastMessagePreview?.let {
            Text(it, style = SlockType.Caption, color = SlockColor.TextBlack, maxLines = 2)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text.uppercase(), style = SlockType.SectionLabel, color = SlockColor.TextBlack)
}

@Composable
private fun CountBadge(text: String) {
    Text(
            text = text,
            style = SlockType.Caption,
            color = SlockColor.SurfaceWhite,
            modifier =
                    Modifier.background(SlockColor.AccentPink)
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .padding(horizontal = SlockSpacing.Sm, vertical = 2.dp)
    )
}

@Composable
private fun settingsHomeItems(
        @Suppress("UNUSED_PARAMETER") content: SettingsContent,
        @Suppress("UNUSED_PARAMETER") localeSnapshot: LocaleSnapshot,
        selectedDestination: SettingsDestination = SettingsDestination.Account
): List<SettingsHomeItem> {
    return listOf(
            SettingsHomeItem(
                    key = SettingsDestination.Account.key,
                    title = stringResource(R.string.settings_account_title),
                    subtitle = stringResource(R.string.settings_account_subtitle),
                    icon = LucideIcons.User,
                    selected =
                            selectedDestination == SettingsDestination.Account ||
                                    selectedDestination == SettingsDestination.Home
            ),
            SettingsHomeItem(
                    key = SettingsDestination.Computers.key,
                    title = stringResource(R.string.settings_computers_title),
                    subtitle = stringResource(R.string.settings_computers_subtitle),
                    icon = LucideIcons.Monitor,
                    selected =
                            selectedDestination == SettingsDestination.Computers ||
                                    selectedDestination == SettingsDestination.ComputerDetail
            ),
            SettingsHomeItem(
                    key = SettingsDestination.Server.key,
                    title = stringResource(R.string.settings_server_title),
                    subtitle = stringResource(R.string.settings_server_subtitle),
                    icon = LucideIcons.Users,
                    selected = selectedDestination == SettingsDestination.Server
            ),
            SettingsHomeItem(
                    key = SettingsDestination.Billing.key,
                    title = stringResource(R.string.settings_billing_title),
                    subtitle = stringResource(R.string.settings_billing_subtitle),
                    icon = LucideIcons.ShieldCheck,
                    selected = selectedDestination == SettingsDestination.Billing
            ),
            SettingsHomeItem(
                    key = SettingsDestination.Mobile.key,
                    title = stringResource(R.string.settings_mobile_title),
                    subtitle = stringResource(R.string.settings_mobile_subtitle),
                    icon = LucideIcons.Smartphone,
                    selected = selectedDestination == SettingsDestination.Mobile
            ),
            SettingsHomeItem(
                    key = SettingsDestination.Storage.key,
                    title = stringResource(R.string.settings_storage_title),
                    subtitle = stringResource(R.string.settings_storage_subtitle),
                    icon = LucideIcons.Archive,
                    selected = selectedDestination == SettingsDestination.Storage
            ),
            SettingsHomeItem(
                    key = SettingsDestination.About.key,
                    title = stringResource(R.string.settings_about_title),
                    subtitle = stringResource(R.string.settings_about_home_subtitle),
                    icon = LucideIcons.Bug,
                    selected =
                            selectedDestination == SettingsDestination.About ||
                                    selectedDestination == SettingsDestination.Diagnostics
            )
    )
}

@Composable
private fun AccountProfileSnapshot.toSettingsAccountProfile() =
        SettingsAccountProfile(
                displayName = displayName,
                handle = handle,
                email = email,
                avatarLabel = avatarLabel,
                verified = verified,
                roleLabel = roleLabel.localizedRoleLabel()
        )

@Composable
private fun ConnectedAccountSnapshot.toSettingsConnectedAccount() =
        SettingsConnectedAccount(
                provider = provider.localizedConnectedProvider(),
                identifier = identifier,
                status =
                        when (status) {
                            RepositoryConnectedAccountStatus.Connected ->
                                    SettingsConnectedAccountStatus.Connected
                            RepositoryConnectedAccountStatus.NotConnected ->
                                    SettingsConnectedAccountStatus.NotConnected
                            RepositoryConnectedAccountStatus.Loading ->
                                    SettingsConnectedAccountStatus.Loading
                            RepositoryConnectedAccountStatus.Failed ->
                                    SettingsConnectedAccountStatus.Failed
                        }
        )

@Composable
private fun String?.localizedRoleLabel(): String =
        when (this?.lowercase()) {
            null, "" -> ""
            "owner", "workspace owner" -> stringResource(R.string.settings_account_role_owner)
            "admin", "administrator" -> stringResource(R.string.settings_account_role_admin)
            "member" -> stringResource(R.string.settings_account_role_member)
            else -> this
        }

@Composable
private fun String.localizedConnectedProvider(): String =
        when (lowercase()) {
            "email password", "password", "email" ->
                    stringResource(R.string.settings_account_provider_email_password)
            else -> this
        }

@Composable
private fun ScreenLoadState<SettingsBillingSnapshot>.toBillingCurrentPlanState():
        SettingsCurrentPlanState =
        when (this) {
            ScreenLoadState.Loading ->
                    SettingsCurrentPlanState(
                            planName = stringResource(R.string.settings_billing_loading),
                            usageRows = emptyList(),
                            loading = true
                    )
            is ScreenLoadState.Content ->
                    SettingsCurrentPlanState(
                            planName = data.currentPlan.planName,
                            usageRows =
                                    data.currentPlan.usageRows.map { row ->
                                        SettingsPlanUsageRow(label = row.label, value = row.value)
                                    },
                            trialActive = data.currentPlan.trialActive
                    )
            is ScreenLoadState.Empty ->
                    SettingsCurrentPlanState(
                            planName = stringResource(R.string.settings_billing_title),
                            usageRows = emptyList(),
                            error = true
                    )
            is ScreenLoadState.Error ->
                    SettingsCurrentPlanState(
                            planName = stringResource(R.string.settings_billing_title),
                            usageRows = emptyList(),
                            error = true
                    )
            is ScreenLoadState.NoPermission ->
                    SettingsCurrentPlanState(
                            planName = stringResource(R.string.settings_billing_title),
                            usageRows = emptyList(),
                            noPermission = true
                    )
        }

@Composable
private fun ScreenLoadState<SettingsBillingSnapshot>.toBillingPlans(): List<SettingsPlanCardState> =
        when (this) {
            is ScreenLoadState.Content ->
                    data.plans.map { plan ->
                        SettingsPlanCardState(
                                name = plan.name,
                                price = plan.price,
                                features = plan.features,
                                current = plan.current,
                                comingSoon = plan.comingSoon
                        )
                    }
            else -> emptyList()
        }

private const val SettingsNotificationVisibleTestChannelId = "settings-test-visible-v3"
private const val InboxNotificationChannelId = SettingsNotificationVisibleTestChannelId

private enum class SettingsNotificationPostResult {
    Posted,
    PermissionDenied,
    NotificationsDisabled,
    ChannelBlocked,
    PostedButNotActive,
    SecurityException
}

private data class SettingsNotificationPostOutcome(
        val result: SettingsNotificationPostResult,
        val notificationId: Int = -1,
        val activeAfterPost: Boolean? = null,
        val visibilityReason: String = result.name
) {
    fun toLogFields(): String =
            "result=${result.name} notificationId=$notificationId activeAfterPost=${activeAfterPost ?: false} visibilityReason=$visibilityReason"
}

private data class SettingsNotificationDiagnostics(
        val sdk: Int,
        val permissionGranted: Boolean,
        val notificationsEnabled: Boolean,
        val channelExists: Boolean,
        val channelImportance: Int?
) {
    fun toLogFields(): String =
            "sdk=$sdk permissionGranted=$permissionGranted notificationsEnabled=$notificationsEnabled channelExists=$channelExists channelImportance=${channelImportance ?: -1}"
}

private data class SettingsNotificationChannelDiagnostics(
        val channelExists: Boolean,
        val channelImportance: Int?
)

private fun Context.hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED

private fun Context.showSettingsTestNotification(): SettingsNotificationPostOutcome {
    if (!hasNotificationPermission())
            return SettingsNotificationPostOutcome(
                    SettingsNotificationPostResult.PermissionDenied,
                    visibilityReason = "permission_denied"
            )
    if (!ensureSettingsNotificationChannelIfAllowed("settings_test")) {
        return SettingsNotificationPostOutcome(
                SettingsNotificationPostResult.PermissionDenied,
                visibilityReason = "permission_denied"
        )
    }
    val diagnostics = notificationDiagnostics()
    if (!diagnostics.notificationsEnabled)
            return SettingsNotificationPostOutcome(
                    SettingsNotificationPostResult.NotificationsDisabled,
                    visibilityReason = "notifications_disabled"
            )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    diagnostics.channelImportance == NotificationManager.IMPORTANCE_NONE
    ) {
        return SettingsNotificationPostOutcome(
                SettingsNotificationPostResult.ChannelBlocked,
                visibilityReason = "channel_blocked"
        )
    }
    val notificationId = ((System.currentTimeMillis() % Int.MAX_VALUE).toInt()).coerceAtLeast(1)
    try {
        val contentIntent =
                packageManager
                        .getLaunchIntentForPackage(packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        ?.let { intent ->
                            PendingIntent.getActivity(
                                    this,
                                    notificationId,
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or
                                            PendingIntent.FLAG_IMMUTABLE
                            )
                        }
        NotificationManagerCompat.from(this)
                .notify(
                        notificationId,
                        NotificationCompat.Builder(this, SettingsNotificationVisibleTestChannelId)
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentTitle(
                                        getString(R.string.settings_notification_test_title)
                                )
                                .setContentText(getString(R.string.settings_notification_test_text))
                                .setStyle(
                                        NotificationCompat.BigTextStyle()
                                                .bigText(
                                                        getString(
                                                                R.string
                                                                        .settings_notification_test_text
                                                        )
                                                )
                                )
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setCategory(NotificationCompat.CATEGORY_STATUS)
                                .setDefaults(0)
                                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                                .setWhen(System.currentTimeMillis())
                                .setShowWhen(true)
                                .setContentIntent(contentIntent)
                                .setAutoCancel(true)
                                .build()
                )
        val activeAfterPost = isNotificationActive(notificationId)
        return if (activeAfterPost) {
            SettingsNotificationPostOutcome(
                    SettingsNotificationPostResult.Posted,
                    notificationId = notificationId,
                    activeAfterPost = true,
                    visibilityReason = "active_notification_posted"
            )
        } else {
            SettingsNotificationPostOutcome(
                    SettingsNotificationPostResult.PostedButNotActive,
                    notificationId = notificationId,
                    activeAfterPost = false,
                    visibilityReason = "post_returned_but_not_active"
            )
        }
    } catch (error: SecurityException) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                "SlockSettingsNotification",
                "test_post_security_exception code=${error.javaClass.simpleName}"
        )
        return SettingsNotificationPostOutcome(
                SettingsNotificationPostResult.SecurityException,
                notificationId = notificationId,
                visibilityReason = "security_exception"
        )
    }
}

private fun Context.showInboxNotification(
        message: Message,
        diagnosticsTag: String
): SettingsNotificationPostOutcome {
    if (!hasNotificationPermission())
            return SettingsNotificationPostOutcome(
                    SettingsNotificationPostResult.PermissionDenied,
                    visibilityReason = "permission_denied"
            )
    if (!ensureSettingsNotificationChannelIfAllowed("inbox_$diagnosticsTag")) {
        return SettingsNotificationPostOutcome(
                SettingsNotificationPostResult.PermissionDenied,
                visibilityReason = "permission_denied"
        )
    }
    val diagnostics = notificationDiagnostics()
    if (!diagnostics.notificationsEnabled)
            return SettingsNotificationPostOutcome(
                    SettingsNotificationPostResult.NotificationsDisabled,
                    visibilityReason = "notifications_disabled"
            )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    diagnostics.channelImportance == NotificationManager.IMPORTANCE_NONE
    ) {
        return SettingsNotificationPostOutcome(
                SettingsNotificationPostResult.ChannelBlocked,
                visibilityReason = "channel_blocked"
        )
    }
    val notificationId = message.id.value.hashCode().let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }
            .coerceAtLeast(1)
    val title = getString(R.string.inbox_notification_title, message.notificationChannelName())
    val text = message.notificationBody()
    try {
        val contentIntent =
                packageManager
                        .getLaunchIntentForPackage(packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        ?.let { intent ->
                            PendingIntent.getActivity(
                                    this,
                                    notificationId,
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or
                                            PendingIntent.FLAG_IMMUTABLE
                            )
                        }
        NotificationManagerCompat.from(this)
                .notify(
                        notificationId,
                        NotificationCompat.Builder(this, InboxNotificationChannelId)
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentTitle(title)
                                .setContentText(text)
                                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                                .setDefaults(0)
                                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                                .setWhen(System.currentTimeMillis())
                                .setShowWhen(true)
                                .setContentIntent(contentIntent)
                                .setAutoCancel(true)
                                .build()
                )
        val activeAfterPost = isNotificationActive(notificationId)
        return if (activeAfterPost) {
            SettingsNotificationPostOutcome(
                    SettingsNotificationPostResult.Posted,
                    notificationId = notificationId,
                    activeAfterPost = true,
                    visibilityReason = "${diagnosticsTag}_active_notification_posted"
            )
        } else {
            SettingsNotificationPostOutcome(
                    SettingsNotificationPostResult.PostedButNotActive,
                    notificationId = notificationId,
                    activeAfterPost = false,
                    visibilityReason = "${diagnosticsTag}_post_returned_but_not_active"
            )
        }
    } catch (error: SecurityException) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                "SlockInboxNotification",
                "post_security_exception source=$diagnosticsTag messageId=${message.id.value} code=${error.javaClass.simpleName}"
        )
        return SettingsNotificationPostOutcome(
                SettingsNotificationPostResult.SecurityException,
                notificationId = notificationId,
                visibilityReason = "security_exception"
        )
    }
}

private fun Message.notificationChannelName(): String =
        channelId?.value ?: threadChannelId?.value ?: threadId.value

private fun Message.notificationBody(): String =
        listOf(author.displayName, body)
                .filter { it.isNotBlank() }
                .joinToString(": ")
                .ifBlank { author.displayName }

private fun refreshSelectedThread(
        reason: String,
        selectedThreadStateProvider: () -> ThreadUiState,
        selectedThreadIdProvider: () -> String?,
        setSelectedThreadState: (ThreadUiState) -> Unit,
        onThreadSnapshot: (DataResult<ThreadSnapshot>, ThreadId) -> Unit = { _, _ -> },
        localThreadUiStateProvider: (ThreadId, ConversationSummary?, Message?) -> ThreadUiState? = {
                _,
                _,
                _ ->
            null
        },
        transformThreadState: (ThreadUiState) -> ThreadUiState = { it },
        conversationRepository: ConversationRepository,
        coroutineScope: CoroutineScope
) {
    val currentContent = selectedThreadStateProvider().selectedThreadContent() ?: return
    if (currentContent.refreshStatus == ThreadRefreshStatus.Loading) return
        val streamThreadId = ThreadId(currentContent.header.messageStreamChannelId().value)
    setSelectedThreadState(
            selectedThreadStateProvider().withThreadRefreshStatus(ThreadRefreshStatus.Loading)
    )
    coroutineScope.launch {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadLifecycle",
                "refresh_start reason=$reason threadId=${streamThreadId.value} logicalThreadId=${currentContent.header.threadId.value}"
        )
        val result =
                withSlockRequestPriority(
                        priority = SlockRequestPriority.Visible,
                        key = "thread:${streamThreadId.value}",
                        reason = reason
                ) {
                    conversationRepository.getThread(streamThreadId)
                }
        onThreadSnapshot(result, streamThreadId)
        when (result) {
            is DataResult.Content -> {
                if (selectedThreadIdProvider() == streamThreadId.value) {
                    setSelectedThreadState(
                            transformThreadState(
                                    localThreadUiStateProvider(
                                            streamThreadId,
                                            currentContent.header,
                                            currentContent.parentMessage
                                    )
                                            ?: selectedThreadStateProvider()
                                                    .mergeRefreshedThreadSnapshot(result.data)
                            )
                    )
                }
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockThreadLifecycle",
                        "refresh_success reason=$reason threadId=${streamThreadId.value} messageCount=${result.data.messages.size} canLoadMoreBefore=${result.data.canLoadMoreBefore} canLoadMoreAfter=${result.data.canLoadMoreAfter}"
                )
            }
            is DataResult.Empty -> {
                if (selectedThreadIdProvider() == streamThreadId.value) {
                    setSelectedThreadState(
                            selectedThreadStateProvider()
                                    .withThreadRefreshStatus(
                                            ThreadRefreshStatus.Error,
                                            result.reason.name
                                    )
                    )
                }
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                        "SlockThreadLifecycle",
                        "refresh_empty reason=$reason threadId=${streamThreadId.value} code=${result.reason.name}"
                )
            }
            is DataResult.Error -> {
                if (selectedThreadIdProvider() == streamThreadId.value) {
                    setSelectedThreadState(
                            selectedThreadStateProvider()
                                    .withThreadRefreshStatus(
                                            ThreadRefreshStatus.Error,
                                            result.error.code
                                    )
                    )
                }
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                        "SlockThreadLifecycle",
                        "refresh_error reason=$reason threadId=${streamThreadId.value} code=${result.error.code}"
                )
            }
            is DataResult.NoPermission -> {
                if (selectedThreadIdProvider() == streamThreadId.value) {
                    setSelectedThreadState(
                            selectedThreadStateProvider()
                                    .withThreadRefreshStatus(
                                            ThreadRefreshStatus.Error,
                                            result.blocker.code
                                    )
                    )
                }
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                        "SlockThreadLifecycle",
                        "refresh_no_permission reason=$reason threadId=${streamThreadId.value} code=${result.blocker.code}"
                )
            }
        }
    }
}

private fun ThreadUiState.selectedThreadContent(): ThreadContent? =
        (loadState as? ScreenLoadState.Content)?.data

private fun Context.ensureSettingsNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockSettingsNotification",
                "channel_ensure_skipped sdk=${Build.VERSION.SDK_INT}"
        )
        return
    }
    ensureSettingsNotificationChannelApi26()
}

private fun Context.ensureSettingsNotificationChannelIfAllowed(source: String): Boolean {
    if (!hasNotificationPermission()) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockSettingsNotification",
                "channel_ensure_skipped source=$source reason=permission_not_granted"
        )
        return false
    }
    ensureSettingsNotificationChannel()
    return true
}

@RequiresApi(Build.VERSION_CODES.O)
private fun Context.ensureSettingsNotificationChannelApi26() {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel =
            NotificationChannel(
                            SettingsNotificationVisibleTestChannelId,
                            getString(R.string.settings_notification_channel_name),
                            NotificationManager.IMPORTANCE_DEFAULT
                    )
                    .apply {
                        description = getString(R.string.settings_notification_channel_description)
                        enableVibration(false)
                    }
    manager.createNotificationChannel(channel)
    val created = manager.getNotificationChannel(SettingsNotificationVisibleTestChannelId)
    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
            "SlockSettingsNotification",
            "channel_ensure_result channelId=$SettingsNotificationVisibleTestChannelId exists=${created != null} importance=${created?.importance ?: -1}"
    )
}

private fun Context.notificationDiagnostics(): SettingsNotificationDiagnostics {
    val channelDiagnostics =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationChannelDiagnosticsApi26()
            } else {
                SettingsNotificationChannelDiagnostics(
                        channelExists = true,
                        channelImportance = null
                )
            }
    return SettingsNotificationDiagnostics(
            sdk = Build.VERSION.SDK_INT,
            permissionGranted = hasNotificationPermission(),
            notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled(),
            channelExists = channelDiagnostics.channelExists,
            channelImportance = channelDiagnostics.channelImportance
    )
}

@RequiresApi(Build.VERSION_CODES.O)
private fun Context.notificationChannelDiagnosticsApi26(): SettingsNotificationChannelDiagnostics {
    val channel =
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .getNotificationChannel(SettingsNotificationVisibleTestChannelId)
    return SettingsNotificationChannelDiagnostics(
            channelExists = channel != null,
            channelImportance = channel?.importance
    )
}

private fun Context.isNotificationActive(notificationId: Int): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return manager.activeNotifications.any { notification -> notification.id == notificationId }
}

private fun Context.openNotificationSettings() {
    val notificationSettingsIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                null
            }
    runCatching {
                if (notificationSettingsIntent != null) {
                    startActivity(notificationSettingsIntent)
                } else {
                    throw ActivityNotFoundException("Notification settings unavailable")
                }
            }
            .onFailure {
        startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
        )
    }
}

private fun Context.toSettingsAboutSnapshot(
        session: AuthSession?,
        selectedServerId: ServerId?,
        socketConnected: Boolean,
        notificationPermissionGranted: Boolean,
        diagnosticsVersion: Int
): SettingsAboutSnapshot {
    val notificationDiagnostics = notificationDiagnostics()
    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    val serverStatus =
            selectedServerId?.value?.let { serverId ->
                getString(R.string.settings_about_status_selected_server, serverId.takeLast(8))
            }
                    ?: getString(R.string.settings_about_status_no_server)
    return SettingsAboutSnapshot(
            appVersion = packageInfo.versionName ?: "unknown",
            buildNumber =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toString()
                    } else {
                        @Suppress("DEPRECATION") packageInfo.versionCode.toString()
                    },
            packageName = packageName,
            deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidVersion = "Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}",
            sessionStatus =
                    if (session != null) {
                        getString(R.string.settings_about_status_signed_in)
                    } else {
                        getString(R.string.settings_about_status_signed_out)
                    },
            serverStatus = serverStatus,
            socketStatus =
                    if (socketConnected) {
                        getString(R.string.settings_about_status_connected)
                    } else {
                        getString(R.string.settings_about_status_disconnected)
                    },
            notificationStatus =
                    getString(
                            R.string.settings_about_notification_status,
                            notificationPermissionGranted,
                            notificationDiagnostics.notificationsEnabled,
                            notificationDiagnostics.channelExists,
                            notificationDiagnostics.channelImportance ?: -1
                    ),
            diagnosticLogs = SanitizedDiagnosticsLogBuffer.recent()
    )
}

private fun Context.copyDiagnosticsToClipboard() {
    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.record(
            "SlockDiagnostics",
            "copy_logs"
    )
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
            ClipData.newPlainText(
                    getString(R.string.settings_about_logs_clip_label),
                    SanitizedDiagnosticsLogBuffer.exportText()
            )
    )
}

private fun Context.shareDiagnostics() {
    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.record(
            "SlockDiagnostics",
            "share_logs"
    )
    val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_about_share_subject))
                putExtra(Intent.EXTRA_TEXT, SanitizedDiagnosticsLogBuffer.exportText())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    try {
        startActivity(
                Intent.createChooser(intent, getString(R.string.settings_about_share_logs)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
        )
    } catch (_: ActivityNotFoundException) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                "SlockDiagnostics",
                "share_unavailable"
        )
    }
}

private fun Color.toStatusBarLogName(): String =
        when (this) {
            SlockColor.SurfaceWhite -> "surface_white"
            SlockColor.SurfaceCream -> "surface_cream"
            SlockColor.BrandYellow -> "brand_yellow"
            else -> "0x${toArgb().toUInt().toString(16)}"
        }

@Composable
private fun MembersWideStatusBar() {
    Row(
            modifier =
                    Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility)
    ) {
        Box(
                modifier =
                        Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                                .background(SlockColor.BrandYellow)
        )
        Box(
                modifier =
                        Modifier
                                .width(SlockStroke.Default)
                                .fillMaxHeight()
                                .background(SlockColor.BorderBlack)
        )
        Box(
                modifier =
                        Modifier
                                .weight(0.58f)
                                .fillMaxHeight()
                                .background(SlockColor.SurfaceWhite)
        )
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? =
        when (this) {
            is ComponentActivity -> this
            is ContextWrapper -> baseContext.findComponentActivity()
            else -> null
        }

private fun Context.hasUsableNetwork(): Boolean {
    val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasInternetCapability()
}

private fun NetworkCapabilities.hasInternetCapability(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

private fun Attachment.isTextPreviewable(): Boolean {
    val type = mimeType.substringBefore(';').trim().lowercase()
    val nameLower = name.substringAfterLast('/').lowercase()
    return type.startsWith("text/") ||
            type in
                    setOf(
                            "application/json",
                            "application/xml",
                            "application/javascript",
                            "application/x-javascript",
                            "application/x-sh",
                            "application/yaml",
                            "application/x-yaml"
                    ) ||
            nameLower.endsWith(".json") ||
            nameLower.endsWith(".md") ||
            nameLower.endsWith(".markdown") ||
            nameLower.endsWith(".txt") ||
            nameLower.endsWith(".log") ||
            nameLower.endsWith(".csv") ||
            nameLower.endsWith(".xml") ||
            nameLower.endsWith(".yaml") ||
            nameLower.endsWith(".yml")
}

private fun downloadAttachmentPreviewText(url: String): String {
    val connection = URL(url).openConnection()
    connection.connectTimeout = ATTACHMENT_TEXT_PREVIEW_TIMEOUT_MS
    connection.readTimeout = ATTACHMENT_TEXT_PREVIEW_TIMEOUT_MS
    val bytes =
            connection.getInputStream().use { input ->
                input.readBytes(ATTACHMENT_TEXT_PREVIEW_MAX_BYTES + 1)
            }
    if (bytes.isEmpty()) return ""
    val clipped = bytes.size > ATTACHMENT_TEXT_PREVIEW_MAX_BYTES
    val previewBytes = bytes.copyOf(minOf(bytes.size, ATTACHMENT_TEXT_PREVIEW_MAX_BYTES))
    val text =
            try {
                previewBytes.toString(Charsets.UTF_8)
            } catch (error: Throwable) {
                throw IOException("Attachment text preview is not valid UTF-8", error)
            }
    return if (clipped) {
        "$text\n\n[Preview truncated at ${ATTACHMENT_TEXT_PREVIEW_MAX_BYTES / 1024} KB]"
    } else {
        text
    }
}

private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = java.io.ByteArrayOutputStream()
    var remaining = limit
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read == -1) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}

private const val ATTACHMENT_TEXT_PREVIEW_MAX_BYTES = 128 * 1024
private const val ATTACHMENT_TEXT_PREVIEW_TIMEOUT_MS = 15_000

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SlockAppPreview() {
    SlockTheme { SlockApp() }
}
