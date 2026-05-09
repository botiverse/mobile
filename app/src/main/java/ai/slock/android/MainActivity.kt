package ai.slock.android

import ai.slock.android.core.data.auth.AuthResult
import ai.slock.android.core.data.auth.AuthServerRepository
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.EncryptedAuthSessionStore
import ai.slock.android.core.data.auth.RetrofitAuthRepository
import ai.slock.android.core.data.diagnostics.SanitizedDiagnosticsLogBuffer
import ai.slock.android.core.data.locale.AppLocale
import ai.slock.android.core.data.locale.LocaleSnapshot
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
import ai.slock.android.core.data.preferences.MyMessageAlignmentPreference
import ai.slock.android.core.data.preferences.PersistentChatLayoutPreferenceRepository
import ai.slock.android.core.data.preferences.SharedPreferencesChatLayoutPreferenceStore
import ai.slock.android.core.data.realtime.SessionRealtimeRepository
import ai.slock.android.core.data.realtime.SocketIoRealtimeClient
import ai.slock.android.core.data.realtime.SocketIoSocketTransport
import ai.slock.android.core.data.repository.AccountProfileSnapshot
import ai.slock.android.core.data.repository.AccountSettingsSnapshot
import ai.slock.android.core.data.repository.AddChannelMemberRequest
import ai.slock.android.core.data.repository.AgentActivitySnapshot
import ai.slock.android.core.data.repository.AgentDmSnapshot
import ai.slock.android.core.data.repository.AgentReminderSnapshot
import ai.slock.android.core.data.repository.AttachmentUploadFile
import ai.slock.android.core.data.repository.AttachmentUploadRequest
import ai.slock.android.core.data.repository.ChannelJoinResult
import ai.slock.android.core.data.repository.ChannelMemberSnapshot
import ai.slock.android.core.data.repository.ChannelRepository
import ai.slock.android.core.data.repository.ChannelUnreadSnapshot
import ai.slock.android.core.data.repository.ConnectedAccountSnapshot
import ai.slock.android.core.data.repository.ConnectedAccountStatus as RepositoryConnectedAccountStatus
import ai.slock.android.core.data.repository.ConversationRepository
import ai.slock.android.core.data.repository.CreateChannelRequest
import ai.slock.android.core.data.repository.CreateServerRequest
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.InboxFilter as RepositoryInboxFilter
import ai.slock.android.core.data.repository.InboxRepository
import ai.slock.android.core.data.repository.InboxSnapshot
import ai.slock.android.core.data.repository.MemberRepository
import ai.slock.android.core.data.repository.MemberSnapshot
import ai.slock.android.core.data.repository.OpenDirectMessageResult
import ai.slock.android.core.data.repository.ReadReceiptRepository
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
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.toGravatarAvatarReference
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.auth.AuthLogoBadge
import ai.slock.android.feature.auth.CreateAccountScreen
import ai.slock.android.feature.auth.ForgotPasswordScreen
import ai.slock.android.feature.auth.LoginScreen
import ai.slock.android.feature.home.HomeContent
import ai.slock.android.feature.home.HomeUiState
import ai.slock.android.feature.home.resolveHomeShellContent
import ai.slock.android.feature.home.withAgentDirectMessagePresence
import ai.slock.android.feature.home.withDraftIndicators
import ai.slock.android.feature.home.withPreservedConversationUnread
import ai.slock.android.feature.inbox.FollowedThreadsScreen
import ai.slock.android.feature.inbox.InboxContent
import ai.slock.android.feature.inbox.InboxFilter
import ai.slock.android.feature.inbox.InboxScreen
import ai.slock.android.feature.inbox.InboxUiState
import ai.slock.android.feature.inbox.SavedScreen
import ai.slock.android.feature.inbox.withBodyLoading
import ai.slock.android.feature.inbox.withFilterLoading
import ai.slock.android.feature.members.MembersContent
import ai.slock.android.feature.members.MembersRouteScreen
import ai.slock.android.feature.members.MembersUiState
import ai.slock.android.feature.sample.SlockStateSamples
import ai.slock.android.feature.settings.SettingsAboutScreen
import ai.slock.android.feature.settings.SettingsAboutSnapshot
import ai.slock.android.feature.settings.SettingsAccountProfile
import ai.slock.android.feature.settings.SettingsAccountScreen
import ai.slock.android.feature.settings.SettingsBillingScreen
import ai.slock.android.feature.settings.SettingsComputerDetailScreen
import ai.slock.android.feature.settings.SettingsComputersScreen
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
import ai.slock.android.feature.thread.ThreadMarkdownTag
import ai.slock.android.feature.thread.ThreadMarkdownTagKind
import ai.slock.android.feature.thread.ThreadMemberAddStatus
import ai.slock.android.feature.thread.ThreadMemberRemoveStatus
import ai.slock.android.feature.thread.ThreadMembersPanelContent
import ai.slock.android.feature.thread.ThreadMembersPanelState
import ai.slock.android.feature.thread.ThreadMembersPickerContent
import ai.slock.android.feature.thread.ThreadParentJumpTarget
import ai.slock.android.feature.thread.ThreadRefreshStatus
import ai.slock.android.feature.thread.ThreadReplyHeaderContext
import ai.slock.android.feature.thread.ThreadScreen
import ai.slock.android.feature.thread.ThreadTab
import ai.slock.android.feature.thread.ThreadUiState
import ai.slock.android.feature.thread.mergeRefreshedThreadSnapshot
import ai.slock.android.feature.thread.threadMarkdownTaskReferenceKey
import ai.slock.android.feature.thread.mergeRealtimeTaskEvent
import ai.slock.android.feature.thread.withThreadRefreshStatus
import ai.slock.android.ui.components.SlockBottomTabBar
import ai.slock.android.ui.components.SlockBottomTabItem
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockMemberPickerSection
import ai.slock.android.ui.components.SlockModal
import ai.slock.android.ui.components.SlockModalAction
import ai.slock.android.ui.components.SlockModalActionStyle
import ai.slock.android.ui.components.SlockModalTextField
import ai.slock.android.ui.components.SlockPageHeader
import ai.slock.android.ui.components.SlockPresence
import ai.slock.android.ui.components.SlockServerSwitcherItem
import ai.slock.android.ui.components.SlockStructuralHomeShell
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.components.toSlockMemberPickerItem
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var notificationPermissionResultHandler: ((Boolean) -> Unit)? = null
    private var attachmentPickerResultHandler: ((List<Uri>) -> Unit)? = null
    private val notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                notificationPermissionResultHandler?.invoke(granted)
            }
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
        super.onCreate(savedInstanceState)
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockBoot",
                "main_activity_on_create buildMarker=auth-unread-diagnostics-v2"
        )
        enableEdgeToEdge()
        setContent {
            SlockTheme {
                SlockTestBuildFrame(show = BuildConfig.SHOW_TEST_BUILD_BADGE) {
                    SlockApp(
                            activityActions =
                                    AppActivityActions(
                                            requestNotificationPermission = { onResult ->
                                                if (Build.VERSION.SDK_INT >=
                                                                Build.VERSION_CODES.TIRAMISU
                                                ) {
                                                    ai.slock.android.core.data.diagnostics
                                                            .SlockDiagnosticsLogger.d(
                                                            "SlockSettingsNotification",
                                                            "request_permission_start"
                                                    )
                                                    notificationPermissionResultHandler = onResult
                                                    notificationPermissionLauncher.launch(
                                                            Manifest.permission.POST_NOTIFICATIONS
                                                    )
                                                } else {
                                                    onResult(true)
                                                }
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
    val localeStore = remember { SharedPreferencesLocalePreferenceStore(context) }
    val localeRepository = remember { PersistentLocaleRepository(localeStore) }
    val chatLayoutPreferenceStore = remember { SharedPreferencesChatLayoutPreferenceStore(context) }
    val chatLayoutPreferenceRepository = remember {
        PersistentChatLayoutPreferenceRepository(chatLayoutPreferenceStore)
    }
    val localeScope = rememberCoroutineScope()
    var localeSnapshot by remember {
        mutableStateOf(LocaleSnapshot(current = localeStore.readLocaleSync()))
    }
    var chatLayoutPreferenceSnapshot by remember {
        mutableStateOf(
                ChatLayoutPreferenceSnapshot(
                        myMessageAlignment = chatLayoutPreferenceStore.readMyMessageAlignmentSync()
                )
        )
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
        activityActions: AppActivityActions,
        modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authSessionStore = remember { EncryptedAuthSessionStore(context) }
    val authRepository = remember { RetrofitAuthRepository(authSessionStore) }
    val authTokenRefresher = remember { AuthTokenRefresher(authSessionStore) }
    val serverRepository = remember { AuthServerRepository(authSessionStore, authRepository) }
    var currentAuthSession by remember { mutableStateOf<AuthSession?>(null) }
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
                                }
                        ),
                tokenRefresher = authTokenRefresher
        )
    }
    val coroutineScope = rememberCoroutineScope()
    var authState by remember { mutableStateOf<AuthUiState>(AuthUiState.Restoring) }
    var authRoute by rememberSaveable { mutableStateOf(AuthRoute.Login) }
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
    var composerValue by rememberSaveable {
        mutableStateOf(SlockStateSamples.threadContent.composerState.value)
    }
    var composerAsTask by rememberSaveable {
        mutableStateOf(SlockStateSamples.threadContent.composerState.asTask)
    }
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
    var membersState by remember { mutableStateOf(SlockStateSamples.membersContent) }
    var serverListState by remember {
        mutableStateOf<ScreenLoadState<ServerListSnapshot>>(ScreenLoadState.Loading)
    }
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
    var inboxFilter by rememberSaveable { mutableStateOf(InboxFilter.All) }
    var inboxState by remember {
        mutableStateOf<InboxUiState>(InboxUiState(ScreenLoadState.Loading))
    }
    var followedThreadsState by remember {
        mutableStateOf<InboxUiState>(InboxUiState(ScreenLoadState.Loading))
    }
    var selectedThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedThreadState by remember {
        mutableStateOf<ThreadUiState>(ThreadUiState(ScreenLoadState.Loading))
    }
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
    var savedState by remember {
        mutableStateOf<InboxUiState>(InboxUiState(ScreenLoadState.Loading))
    }
    var tasksFilter by rememberSaveable { mutableStateOf<TaskStatus?>(null) }
    var tasksState by remember {
        mutableStateOf<TasksUiState>(TasksUiState(ScreenLoadState.Loading))
    }
    var tasksRefreshNonce by remember { mutableStateOf(0) }
    var taskReferenceChooserLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var taskReferenceChooserIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchState by remember {
        mutableStateOf<InboxUiState>(
                InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
        )
    }
    var realtimeSubscription by remember { mutableStateOf<RealtimeSubscription?>(null) }
    var realtimeLastSeq by rememberSaveable { mutableStateOf(0L) }
    var realtimeThreadMessages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var threadRefreshNonce by remember { mutableStateOf(0) }
    var settingsAccountState by remember {
        mutableStateOf<ScreenLoadState<AccountSettingsSnapshot>>(ScreenLoadState.Loading)
    }
    var settingsComputersState by remember {
        mutableStateOf<ScreenLoadState<SettingsComputersSnapshot>>(ScreenLoadState.Loading)
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
    var settingsDiagnosticsVersion by remember { mutableStateOf(0) }
    var notificationPermissionGranted by remember {
        mutableStateOf(context.hasNotificationPermission())
    }
    var selectedServerOverride by remember { mutableStateOf<ServerId?>(null) }
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
    var unreadRefreshNonce by remember { mutableStateOf(0) }
    var selectedThreadRetryAction by remember {
        mutableStateOf<(() -> Unit)?>(null)
    }
    val showBottomBar =
            (destination != AppDestination.Home || homeRoute == HomeRoute.Shell) &&
                    !(destination == AppDestination.Members && membersDetailVisible)

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

    fun reconcileReadLocally(channelId: ChannelId) {
        homeState = homeState.clearUnread(channelId)
        inboxState = inboxState.clearUnread(channelId)
        followedThreadsState = followedThreadsState.clearUnread(channelId)
        savedState = savedState.clearUnread(channelId)
        searchState = searchState.clearUnread(channelId)
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
            when (val result = readReceiptRepository.getUnreadCounts(serverId)) {
                is DataResult.Content -> {
                    homeState = homeState.applyUnreadCounts(result.data)
                    inboxState = inboxState.applyUnreadCounts(result.data)
                    followedThreadsState = followedThreadsState.applyUnreadCounts(result.data)
                    savedState = savedState.applyUnreadCounts(result.data)
                    searchState = searchState.applyUnreadCounts(result.data)
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
        inboxState = inboxState.withBodyLoading(inboxFilter)
        followedThreadsState = followedThreadsState.withBodyLoading()
        savedState = savedState.withBodyLoading()
        searchState =
                if (searchQuery.isBlank()) {
                    InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
                } else {
                    searchState.withBodyLoading()
                }
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
        val channelId = content.header.channelId ?: ChannelId(content.header.threadId.value)
        val maxSeq = content.messages.mapNotNull { it.seq }.maxOrNull()
        reconcileReadLocally(channelId)
        coroutineScope.launch {
            val result =
                    if (maxSeq != null && maxSeq > 0L) {
                        readReceiptRepository.markChannelReadToSeq(channelId, maxSeq)
                    } else {
                        readReceiptRepository.markChannelReadAll(channelId)
                    }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockRead",
                    "mark_read_result channelId=${channelId.value} seq=${maxSeq ?: "all"} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
            if (result !is DataResult.Content) {
                reconcileUnreadCountsFromServer(reason = "mark_read_failed", channelId = channelId)
            }
        }
    }

    fun openThread(conversation: ConversationSummary) {
        replyThreadBackStack = emptyList()
        threadParentJumpTarget = null
        clearThreadReturnTarget()
        selectedThreadRetryAction = { openThread(conversation) }
        composerDrafts =
                composerDrafts.saveComposerDraft(
                        selectedThreadId,
                        composerValue,
                        composerAsTask,
                        pendingAttachmentFiles,
                        failedAttachmentIds
                )
        selectedThreadId = conversation.threadId.value
        attachmentPreviewState = null
        selectedThreadState =
                ThreadUiState(
                        ScreenLoadState.Content(
                                ThreadContent(header = conversation, messages = emptyList())
                        ),
                        composerState = conversation.toComposerState()
                )
        localComposerMessages = emptyList()
        realtimeThreadMessages = emptyList()
        attachmentOpenStates = emptyMap()
        val draft = composerDrafts[conversation.threadId.value]
        composerValue = draft?.value.orEmpty()
        composerAsTask = draft?.asTask ?: false
        pendingAttachmentFiles = draft?.attachments.orEmpty()
        failedAttachmentIds = draft?.failedAttachmentIds.orEmpty()
        coroutineScope.launch {
            val result = conversationRepository.getThread(conversation.threadId)
            selectedThreadState = result.toThreadUiState(fallbackHeader = conversation)
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
                                    )
                        }
                    }
                }
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
        }
    }

    fun openAgentDirectMessage(member: MemberProfile) {
        if (member.kind != MemberKind.Agent) return
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockDirectMessage",
                    "open_start source=member_detail agentId=${member.id.value}"
            )
            when (val result = channelRepository.openAgentDirectMessage(member.id)) {
                is DataResult.Content -> {
                    val conversation =
                            result.data.conversation.withAgentDirectMessageFallback(member)
                    homeState = homeState.withOpenDirectMessage(result.data, conversation)
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

    fun selectedThreadChannelId(): ChannelId? {
        val header = selectedThreadContent()?.header ?: return selectedThreadId?.let(::ChannelId)
        return header.channelId ?: ChannelId(header.threadId.value)
    }

    fun updateSelectedThreadTasksState(transform: (TasksUiState) -> TasksUiState) {
        val current =
                selectedThreadState.tasksState
                        ?: selectedThreadContent()?.tasks?.toTasksUiStateForThreadTasks()
                        ?: TasksUiState(
                                ScreenLoadState.Loading,
                                selectedFilter = null
                        )
        selectedThreadState = selectedThreadState.copy(tasksState = transform(current))
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
                    taskRepository.getChannelTasks(
                            serverId = selectedServerId,
                            channelId = channelId,
                            filter = TaskFilter()
            )
            if (selectedThreadChannelId() == channelId) {
                selectedThreadState =
                        selectedThreadState.copy(
                                tasksState =
                                        selectedThreadState.tasksState
                                                .mergeThreadTasksRefreshResult(result)
                        )
            }
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasks",
                    "channel_tasks_refresh_result reason=$reason channelId=${channelId.value} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
        }
    }

    val refreshSelectedThread: (String) -> Unit = { reason ->
        val currentContent = selectedThreadContent()
        if (currentContent != null && currentContent.refreshStatus != ThreadRefreshStatus.Loading) {
            val threadId = currentContent.header.threadId
            selectedThreadState =
                    selectedThreadState.withThreadRefreshStatus(ThreadRefreshStatus.Loading)
            coroutineScope.launch {
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockThreadLifecycle",
                        "refresh_start reason=$reason threadId=${threadId.value}"
                )
                when (val result = conversationRepository.getThread(threadId)) {
                    is DataResult.Content -> {
                        if (selectedThreadId == threadId.value) {
                            selectedThreadState =
                                    selectedThreadState.mergeRefreshedThreadSnapshot(result.data)
                        }
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockThreadLifecycle",
                                "refresh_success reason=$reason threadId=${threadId.value} messageCount=${result.data.messages.size} canLoadMoreBefore=${result.data.canLoadMoreBefore} canLoadMoreAfter=${result.data.canLoadMoreAfter}"
                        )
                    }
                    is DataResult.Empty -> {
                        if (selectedThreadId == threadId.value) {
                            selectedThreadState =
                                    selectedThreadState.withThreadRefreshStatus(
                                            ThreadRefreshStatus.Error,
                                            result.reason.name
                                    )
                        }
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockThreadLifecycle",
                                "refresh_empty reason=$reason threadId=${threadId.value} code=${result.reason.name}"
                        )
                    }
                    is DataResult.Error -> {
                        if (selectedThreadId == threadId.value) {
                            selectedThreadState =
                                    selectedThreadState.withThreadRefreshStatus(
                                            ThreadRefreshStatus.Error,
                                            result.error.code
                                    )
                        }
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockThreadLifecycle",
                                "refresh_error reason=$reason threadId=${threadId.value} code=${result.error.code}"
                        )
                    }
                    is DataResult.NoPermission -> {
                        if (selectedThreadId == threadId.value) {
                            selectedThreadState =
                                    selectedThreadState.withThreadRefreshStatus(
                                            ThreadRefreshStatus.Error,
                                            result.blocker.code
                                    )
                        }
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                                "SlockThreadLifecycle",
                                "refresh_no_permission reason=$reason threadId=${threadId.value} code=${result.blocker.code}"
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
        membersDetailSelectedMemberId = member.id.value
        membersDetailVisible = true
        destination = AppDestination.Members
        membersState = membersState.withMemberProfile(member)
        coroutineScope.launch {
            if (member.kind == MemberKind.Human) {
                when (val profileResult = memberRepository.getMemberProfile(member.id)) {
                    is DataResult.Content -> {
                        membersState = membersState.withMemberProfile(profileResult.data)
                        if (membersDetailSelectedMemberId == member.id.value) {
                            membersDetailSelectedMemberId = profileResult.data.id.value
                        }
                    }
                    else -> Unit
                }
            } else if (member.kind == MemberKind.Agent) {
                membersState =
                        membersState.copy(
                                selectedAgentDmsState = ScreenLoadState.Loading,
                                selectedAgentRemindersState = ScreenLoadState.Loading,
                                selectedAgentActivityState = ScreenLoadState.Loading
                        )
                val (dms, reminders, activity) =
                        coroutineScope {
                            val dmsD = async { memberRepository.getAgentDirectMessages(member.id) }
                            val remindersD = async { memberRepository.getAgentReminders(member.id) }
                            val activityD = async { memberRepository.getAgentActivity(member.id) }
                            Triple(dmsD.await(), remindersD.await(), activityD.await())
                        }
                membersState =
                        membersState.copy(
                                selectedAgentDmsState = dms.toAgentDmsLoadState(),
                                selectedAgentRemindersState = reminders.toAgentRemindersLoadState(),
                                selectedAgentActivityState = activity.toAgentActivityLoadState()
                        )
            }
        }
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

    fun openThreadItem(item: InboxItem) {
        replyThreadBackStack = emptyList()
        threadParentJumpTarget = item.parentMessageId?.value
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
                ThreadUiState(
                        ScreenLoadState.Content(
                                ThreadContent(header = fallbackHeader, messages = emptyList())
                        ),
                        composerState = fallbackHeader.toComposerState()
                )
        localComposerMessages = emptyList()
        realtimeThreadMessages = emptyList()
        attachmentOpenStates = emptyMap()
        val draft = composerDrafts[threadChannelId.value]
        composerValue = draft?.value.orEmpty()
        composerAsTask = draft?.asTask ?: false
        pendingAttachmentFiles = draft?.attachments.orEmpty()
        failedAttachmentIds = draft?.failedAttachmentIds.orEmpty()
        coroutineScope.launch {
            val parentContextResult =
                    if (item.kind == InboxItemKind.Thread && item.parentMessageId != null) {
                        async { conversationRepository.getMessageContext(item.parentMessageId) }
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
            val parentMessage =
                    parentContextResult
                            ?.await()
                            ?.findParentMessage(item.parentMessageId)
            selectedThreadState = result.toThreadUiState(
                    fallbackHeader = fallbackHeader,
                    parentMessage = parentMessage
            )
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
        threadTab = ThreadTab.Chat
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
                    memberRepository.addChannelMember(
                            AddChannelMemberRequest(
                                    channelId = channelId,
                                    memberId = MemberId(memberIdValue),
                                    kind = kind
                            )
                    )
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadMembers",
                    "add_result channelId=${channelId.value} memberId=$memberIdValue kind=$kind result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
            when (result) {
                is DataResult.Content -> {
                    val membersResult = memberRepository.getChannelMembers(channelId)
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
                    memberRepository.removeChannelMember(
                            RemoveChannelMemberRequest(
                                    channelId = channelId,
                                    memberId = member.id,
                                    kind = member.kind
                            )
                    )
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadMembers",
                    "remove_result channelId=${channelId.value} memberId=${member.id.value} kind=${member.kind} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
            )
            when (result) {
                is DataResult.Content -> {
                    val membersResult = memberRepository.getChannelMembers(channelId)
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
        val channelId = conversation.channelId ?: ChannelId(conversation.threadId.value)
        selectedThreadState = selectedThreadState.withChannelJoinStatus(ChannelJoinStatus.Joining)
        coroutineScope.launch {
            when (val result = channelRepository.joinChannel(channelId)) {
                is DataResult.Content -> {
                    val fallback = selectedThreadContent()?.header ?: conversation
                    val joinedChannel = result.data.toJoinedConversation(fallback)
                    selectedThreadId = joinedChannel.threadId.value
                    homeState = homeState.withJoinedChannel(joinedChannel)
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

    fun openMessageThread(message: Message) {
        composerDrafts =
                composerDrafts.saveComposerDraft(
                        selectedThreadId,
                        composerValue,
                        composerAsTask,
                        pendingAttachmentFiles,
                        failedAttachmentIds
                )
        val currentHeader = selectedThreadContent()?.header
        val fallbackHeader = message.toConversationSummary(currentHeader)
        val existingThreadChannelId = message.threadChannelId
        selectedThreadRetryAction = { openMessageThread(message) }
        if (existingThreadChannelId != null) {
            replyThreadBackStack =
                    replyThreadBackStack + currentReplyThreadBackStackEntry(message.id)
            threadParentJumpTarget = message.id.value
            selectedThreadId = existingThreadChannelId.value
            selectedThreadState =
                    ThreadUiState(
                            ScreenLoadState.Content(
                                    ThreadContent(header = fallbackHeader, messages = emptyList())
                                            .copy(parentMessage = message)
                            ),
                            composerState = fallbackHeader.toComposerState()
                    )
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
                        conversationRepository.getThread(ThreadId(existingThreadChannelId.value))
                selectedThreadState = result.toThreadUiState(
                        fallbackHeader = fallbackHeader,
                        parentMessage = message
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
        selectedThreadState = selectedThreadState.withReplyActionError(null)
        coroutineScope.launch {
            val fallbackCreateChannelId = message.channelId ?: ChannelId(message.threadId.value)
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadReplies",
                    "open_message_thread_context_start messageId=${message.id.value} " +
                            "fallbackChannelId=${fallbackCreateChannelId.value} " +
                            "messageThreadId=${message.threadId.value}"
            )
            val contextResult = conversationRepository.getMessageContext(message.id)
            val contextSnapshot = (contextResult as? DataResult.Content)?.data
            val contextMessage =
                    contextSnapshot?.messages?.firstOrNull { it.id == message.id }
                            ?: contextSnapshot?.parentMessage?.takeIf { it.id == message.id }
            val createChannelId =
                    contextMessage?.channelId
                            ?: contextSnapshot?.conversation?.channelId
                            ?: contextSnapshot?.conversation?.threadId?.let { ChannelId(it.value) }
                            ?: fallbackCreateChannelId
            val contextResolvedMessage =
                    contextMessage?.copy(channelId = contextMessage.channelId ?: createChannelId)
                            ?: message.copy(channelId = message.channelId ?: createChannelId)
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadReplies",
                    "open_message_thread_context_result messageId=${message.id.value} " +
                            "result=${contextResult.realtimeResultName()} " +
                            "resolvedChannelId=${createChannelId.value} " +
                            "contextConversationId=${contextSnapshot?.conversation?.threadId?.value.orEmpty()} " +
                            "contextMessageCount=${contextSnapshot?.messages?.size ?: 0}"
            )
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadReplies",
                    "open_message_thread_create_start parentMessageId=${message.id.value} " +
                            "channelId=${createChannelId.value}"
            )
            val createResult =
                    conversationRepository.createOrOpenThread(
                            channelId = createChannelId,
                            parentMessageId = message.id
                    )
            if (createResult !is DataResult.Content) {
                selectedThreadState =
                        selectedThreadState.withReplyActionError(createResult.readDiagnosticCode())
                return@launch
            }
            val action = createResult.data
            selectedThreadState = selectedThreadState.mergeThreadActionResult(action)
            replyThreadBackStack =
                    replyThreadBackStack +
                            currentReplyThreadBackStackEntry(action.parentMessageId ?: message.id)
            threadParentJumpTarget = (action.parentMessageId ?: message.id).value
            val resolvedMessage =
                    contextResolvedMessage.copy(
                            threadChannelId = action.threadChannelId,
                            replyCount = maxOf(contextResolvedMessage.replyCount, action.replyCount)
                    )
            val resolvedFallbackHeader = resolvedMessage.toConversationSummary(currentHeader)
            selectedThreadId = action.threadChannelId.value
            selectedThreadState =
                    ThreadUiState(
                            ScreenLoadState.Content(
                                    ThreadContent(
                                            header = resolvedFallbackHeader,
                                            messages = emptyList()
                                    ).copy(parentMessage = resolvedMessage)
                            ),
                            composerState = resolvedFallbackHeader.toComposerState()
                    )
            localComposerMessages = emptyList()
            realtimeThreadMessages = emptyList()
            attachmentOpenStates = emptyMap()
            val draft = composerDrafts[action.threadChannelId.value]
            composerValue = draft?.value.orEmpty()
            composerAsTask = draft?.asTask ?: false
            pendingAttachmentFiles = draft?.attachments.orEmpty()
            failedAttachmentIds = draft?.failedAttachmentIds.orEmpty()
            val result = conversationRepository.getThread(ThreadId(action.threadChannelId.value))
            selectedThreadState = result.toThreadUiState(
                    fallbackHeader = resolvedFallbackHeader,
                    parentMessage = resolvedMessage,
                    keepContentOnEmpty = true
            )
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

    fun openTaskContext(task: TaskSummary) {
        val parentMessageId = task.parentMessageId ?: task.messageId
        val messageId = task.messageId
        val threadId = task.threadId
        if (messageId == null && threadId == null) {
            tasksState = tasksState.copy(navigationErrorCode = "missing_context")
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                    "SlockTasksNav",
                    "open_missing_context taskId=${task.id.value} taskNumber=${task.taskNumber}"
            )
            return
        }
        tasksState = tasksState.copy(navigationErrorCode = null)
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasksNav",
                    "open_start taskId=${task.id.value} taskNumber=${task.taskNumber} messageId=${messageId?.value} parentMessageId=${parentMessageId?.value} threadId=${threadId?.value}"
            )
            val result =
                    when {
                        task.isThreadTask && threadId != null ->
                                conversationRepository.getThread(threadId)
                        messageId != null -> conversationRepository.getMessageContext(messageId)
                        else -> conversationRepository.getMessageContext(parentMessageId!!)
                    }
            when (result) {
                is DataResult.Content -> {
                    val fallbackHeader = task.toConversationSummary(result.data.conversation)
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
                            if (task.isThreadTask && threadId != null) parentMessageId?.value
                            else null
                    previousHomeRoute =
                            if (threadReturnHomeRoute == HomeRoute.Thread) null else HomeRoute.Shell
                    homeRoute = HomeRoute.Thread
                    selectedThreadId = result.data.conversation.threadId.value
                    selectedThreadState = result.toThreadUiState(fallbackHeader)
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
                                    header =
                                            result.data.conversation.withTitleFallback(
                                                    fallbackHeader
                                            ),
                                    messages = result.data.messages
                            )
                    )
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockTasksNav",
                            "open_success taskId=${task.id.value} threadId=${result.data.conversation.threadId.value} highlighted=${result.data.highlightedMessageId?.value}"
                    )
                }
                is DataResult.Empty -> {
                    tasksState = tasksState.copy(navigationErrorCode = result.reason.name)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockTasksNav",
                            "open_empty taskId=${task.id.value} code=${result.reason.name}"
                    )
                }
                is DataResult.Error -> {
                    tasksState = tasksState.copy(navigationErrorCode = result.error.code)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockTasksNav",
                            "open_error taskId=${task.id.value} code=${result.error.code}"
                    )
                }
                is DataResult.NoPermission -> {
                    tasksState = tasksState.copy(navigationErrorCode = result.blocker.code)
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockTasksNav",
                            "open_no_permission taskId=${task.id.value} code=${result.blocker.code}"
                    )
                }
            }
        }
    }

    fun openThreadParentMessage(target: ThreadParentJumpTarget) {
        val previousThreadState = selectedThreadState
        composerDrafts =
                composerDrafts.saveComposerDraft(
                        selectedThreadId,
                        composerValue,
                        composerAsTask,
                        pendingAttachmentFiles,
                        failedAttachmentIds
                )
        coroutineScope.launch {
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockTasksNav",
                    "open_parent_start parentMessageId=${target.parentMessageId.value}"
            )
            when (val result = conversationRepository.getMessageContext(target.parentMessageId)) {
                is DataResult.Content -> {
                    replyThreadBackStack = emptyList()
                    threadParentJumpTarget = null
                    selectedThreadId = result.data.conversation.threadId.value
                    selectedThreadState = result.toThreadUiState()
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
        val threadTasksState = selectedThreadState.tasksState
        val updateThreadTasks =
                threadTasksState != null &&
                        threadTab == ThreadTab.Tasks &&
                        (threadTasksState.loadState as? ScreenLoadState.Content)
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
            when (val result = taskRepository.updateTaskStatus(task.id, status)) {
                is DataResult.Content -> {
                    val updatedTask = task.mergeTaskStatusAction(result.data.task)
                    tasksState = tasksState.withTaskStatusUpdateSuccess(updatedTask)
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
        attachmentOpenStates =
                attachmentOpenStates +
                        (attachment.id to
                                AttachmentOpenUiState(status = AttachmentOpenStatus.Loading))
        attachmentPreviewState = null
        coroutineScope.launch {
            when (val result = conversationRepository.getAttachmentOpenTarget(attachment.id)) {
                is DataResult.Content -> {
                    val openResultName =
                            if (attachment.mimeType.startsWith("image/")) {
                                attachmentOpenStates = attachmentOpenStates - attachment.id
                                attachmentPreviewState =
                                        AttachmentPreviewUiState(
                                                status = AttachmentPreviewStatus.Showing,
                                                attachmentId = attachment.id,
                                                name = attachment.name,
                                                url = result.data.url,
                                                thumbnailUrl = attachment.thumbnailUrl,
                                                mimeType = attachment.mimeType
                                        )
                                "preview"
                            } else {
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
                    attachmentOpenStates =
                            attachmentOpenStates +
                                    (attachment.id to
                                            AttachmentOpenUiState(
                                                    status = AttachmentOpenStatus.Error,
                                                    errorCode = result.reason.name
                                            ))
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockAttachment",
                            "open_empty attachmentId=${attachment.id.value} code=${result.reason.name}"
                    )
                }
                is DataResult.Error -> {
                    attachmentOpenStates =
                            attachmentOpenStates +
                                    (attachment.id to
                                            AttachmentOpenUiState(
                                                    status = AttachmentOpenStatus.Error,
                                                    errorCode = result.error.code
                                            ))
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockAttachment",
                            "open_error attachmentId=${attachment.id.value} code=${result.error.code}"
                    )
                }
                is DataResult.NoPermission -> {
                    attachmentOpenStates =
                            attachmentOpenStates +
                                    (attachment.id to
                                            AttachmentOpenUiState(
                                                    status = AttachmentOpenStatus.Error,
                                                    errorCode = result.blocker.code
                                            ))
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
            val result = conversationRepository.loadOlderMessages(threadId, beforeSeq)
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockThreadPaging",
                    result.toOlderPageLog(threadId.value, beforeSeq)
            )
            selectedThreadState = mergeOlderThreadPage(current = selectedThreadState, next = result)
        }
    }

    fun loadNewerThreadMessages() {
        val threadId = selectedThreadId?.let(::ThreadId) ?: return
        val afterSeq =
                selectedThreadContent()?.messages?.mapNotNull { it.seq }?.maxOrNull() ?: return
        if (selectedThreadState.isNewerPageLoading()) return
        selectedThreadState = selectedThreadState.withNewerPageStatus(MessagePageStatus.Loading)
        coroutineScope.launch {
            selectedThreadState =
                    mergeNewerThreadPage(
                            current = selectedThreadState,
                            next = conversationRepository.loadNewerMessages(threadId, afterSeq)
                    )
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
                        candidatesLoadState = ScreenLoadState.Loading
                )
        val (result, candidatesResult) =
                coroutineScope {
                    val resultD = async { memberRepository.getChannelMembers(channelId) }
                    val candidatesD = serverId?.let { sid -> async { memberRepository.getMembers(sid) } }
                    Pair(resultD.await(), candidatesD?.await())
                }
        threadMembersPanelState =
                ThreadMembersPanelState(
                        loadState = result.toThreadMembersPanelLoadState(),
                        candidatesLoadState = candidatesResult.toThreadMembersPickerLoadState()
                )
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadMembers",
                "load_result channelId=${channelId.value} result=${result.realtimeResultName()} code=${result.readDiagnosticCode()}"
        )
    }

    LaunchedEffect(authRepository) {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockAuth",
                "restore_effect_start"
        )
        authState =
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
    }

    if (authState is AuthUiState.Restoring) {
        AuthRestoreScreen(modifier = modifier)
        return
    }

    if (authState !is AuthUiState.Authenticated) {
        AuthRouteScreen(
                route = authRoute,
                onRouteChange = { authRoute = it },
                onLogin = { email, password ->
                    coroutineScope.launch {
                        authRoute = AuthRoute.Login
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
                authState = authState,
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

    LaunchedEffect(
            authState,
            realtimeServerId,
            serverListState,
            currentAuthSession,
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
            when (val result = serverUnreadRepository.getUnreadSummary(serverId)) {
                is DataResult.Content -> {
                    serverListState = serverListState.applyUnreadSummary(result.data)
                    homeState = homeState.applyUnreadSummary(result.data)
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
                conversationRepository = conversationRepository,
                coroutineScope = coroutineScope
        )
    }

    LaunchedEffect(realtimeServerId, appInForeground, realtimeRepository) {
        val serverId = realtimeServerId
        if (serverId == null || !appInForeground) {
            realtimeSubscription?.close()
            realtimeSubscription = null
            realtimeRepository.disconnect()
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                    "SlockRealtime",
                    "disconnect reason=${if (serverId == null) "no_server" else "background"}"
            )
            return@LaunchedEffect
        }
        realtimeSubscription?.close()
        realtimeSubscription =
                realtimeRepository.observeEvents { event ->
                    coroutineScope.launch {
                        val previousSeq = realtimeLastSeq
                        realtimeLastSeq = realtimeLastSeq.updateFrom(event)
                        val activeReplyParentMessageId =
                                replyThreadBackStack.lastOrNull()?.parentMessageId
                        homeState = homeState.applyRealtimeEvent(event)
                        inboxState = inboxState.applyRealtimeEvent(event)
                        savedState = savedState.applySavedRealtimeEvent(event)
                        searchState = searchState.applyRealtimeEvent(event, searchQuery)
                        tasksState = tasksState.applyRealtimeEvent(event)
                        replyThreadBackStack =
                                replyThreadBackStack.applyRealtimeToReplyBackStack(event)
                        selectedThreadState =
                                selectedThreadState.applyRealtimeEvent(
                                        event = event,
                                        selectedThreadId = selectedThreadId,
                                        activeReplyParentMessageId = activeReplyParentMessageId
                                )
                        membersState = membersState.applyRealtimeEvent(event)
                        threadMembersPanelState = threadMembersPanelState.applyRealtimeEvent(event)
                        if (event is RealtimeEvent.MemberPresenceUpdated) {
                            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                    "SlockPresence",
                                    "merge_result source=realtime memberId=${event.memberId.value} presence=${event.presence} kind=${event.kind?.name.orEmpty()}"
                            )
                        }
                        if (event is RealtimeEvent.DirectMessageCreated) {
                            realtimeRepository.joinChannel(event.channelId)
                            homeResumeRefreshNonce += 1
                        }
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockRealtime",
                                "event type=${event.realtimeLogName()} serverId=${serverId.value} seq=$realtimeLastSeq"
                        )
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockRealtime",
                                "merge_result type=${event.realtimeLogName()} serverId=${serverId.value} selectedThreadId=${selectedThreadId.orEmpty()} threadContent=${selectedThreadState.loadState is ScreenLoadState.Content}"
                        )
                        selectedThreadState.logVisibleThreadMerge(
                                event,
                                selectedThreadId,
                                activeReplyParentMessageId
                        )
                        if (event is RealtimeEvent.MessageNew &&
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
                                markThreadReadIfLoaded(content)
                            }
                        }
                        if (event is RealtimeEvent.Heartbeat && event.seq > previousSeq) {
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
                                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                        "SlockRealtime",
                                        "gap_sync_result threadId=${threadId.value} result=${newerResult.realtimeResultName()}"
                                )
                                selectedThreadState =
                                        mergeNewerThreadPage(
                                                current =
                                                        selectedThreadState.withNewerPageStatus(
                                                                MessagePageStatus.Loading
                                                        ),
                                                next = newerResult
                                        )
                            }
                        }
                    }
                }
        when (realtimeRepository.connect(serverId, realtimeLastSeq)) {
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

    DisposableEffect(realtimeRepository) {
        onDispose {
            realtimeSubscription?.close()
            realtimeSubscription = null
            realtimeRepository.disconnect()
        }
    }

    LaunchedEffect(realtimeServerId, homeState.loadState, realtimeRepository) {
        val content =
                (homeState.loadState as? ScreenLoadState.Content)?.data ?: return@LaunchedEffect
        (content.channels + content.directMessages).filter { it.joined }.forEach { conversation ->
            realtimeRepository.joinChannel(
                    conversation.channelId ?: ChannelId(conversation.threadId.value)
            )
        }
    }

    val selectedThreadJoined = selectedThreadContent()?.header?.joined ?: true
    LaunchedEffect(realtimeServerId, selectedThreadId, selectedThreadJoined, realtimeRepository) {
        val threadId = selectedThreadId ?: return@LaunchedEffect
        if (!selectedThreadJoined) return@LaunchedEffect
        realtimeRepository.joinChannel(ChannelId(threadId))
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockRealtime",
                "join_thread_room serverId=${realtimeServerId?.value.orEmpty()} threadId=$threadId"
        )
    }

    LaunchedEffect(
            authState,
            serverListState,
            selectedServerOverride,
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
            val initialHomeContent =
                    if (!hasVisibleHomeContent) {
                        (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.let {
                                server ->
                            HomeContent(
                                    server = server,
                                    currentMember = currentAuthSession?.user?.toMemberProfile()
                                                    ?: ((SlockStateSamples.homeContent.loadState as?
                                                                    ScreenLoadState.Content)
                                                            ?.data
                                                            ?.currentMember
                                                            ?: localComposerAuthor),
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
            val shouldKeepHomeShell = hasVisibleHomeContent || initialHomeContent != null
            val showRefreshAffordance =
                    shouldKeepHomeShell &&
                            (!hasVisibleHomeContent || isManualRefresh || isSelectedServerRefresh)
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
            inboxState = inboxState.withBodyLoading(inboxFilter)
            followedThreadsState = followedThreadsState.withBodyLoading()
            savedState = savedState.withBodyLoading()
            searchState =
                    if (searchQuery.isBlank()) {
                        InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
                    } else {
                        searchState.withBodyLoading()
                    }
            // Snapshot fallback content before launching parallel requests
            val inboxPreviousContent = inboxState.previousContent
            val followedPreviousContent = followedThreadsState.previousContent
            val savedPreviousContent = savedState.previousContent
            val searchPreviousContent = searchState.previousContent
            coroutineScope {
                val homeD =
                        async {
                            channelRepository
                                    .getHome(selectedServerId)
                                    .retryInitialChannelsIfMissing(
                                            refreshSource = refreshSource,
                                            serverId = selectedServerId,
                                            channelRepository = channelRepository
                                    )
                        }
                val membersD = async { memberRepository.getMembers(selectedServerId) }
                val inboxD =
                        async {
                            inboxRepository.loadInitial(
                                    serverId = selectedServerId,
                                    filter = inboxFilter.toRepositoryFilter()
                            )
                        }
                val followedD =
                        async { conversationRepository.getFollowedThreads(selectedServerId) }
                val savedD = async { savedRepository.loadInitial(selectedServerId) }
                val unreadD = async { serverUnreadRepository.getUnreadSummary(selectedServerId) }
                val searchD =
                        if (searchQuery.isBlank()) {
                            null
                        } else {
                            async { searchRepository.search(selectedServerId, searchQuery) }
                        }

                val homeResult = homeD.await()
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockHomeRefresh",
                        "fetch_result source=$refreshSource serverId=${selectedServerId.value} result=${homeResult.logName()} code=${homeResult.diagnosticCode()} retryAttempted=$homeRefreshAutoRetryAttempted"
                )
                if (homeResult is DataResult.Content || !shouldKeepHomeShell) {
                    val preservedUnread =
                            previousHomeState.serverUnreadFor(selectedServerId)
                                    ?: previousServerListState.serverUnreadFor(selectedServerId)
                    val nextHomeState =
                            homeResult.toHomeUiState(
                                    selectedServerId = selectedServerId.value,
                                    preservedServerUnreadCount = preservedUnread,
                                    previousContent =
                                            (previousHomeState.loadState as? ScreenLoadState.Content)
                                                    ?.data
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
                } else if (!homeRefreshAutoRetryAttempted) {
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
                    homeRefreshError = homeResult.refreshErrorCode()
                    homeRefreshErrorLabel = homeResult.refreshErrorLabel()
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                            "SlockHomeRefresh",
                            "ui_error source=auto serverId=${selectedServerId.value} result=${homeResult.logName()} code=${homeResult.diagnosticCode()} retryAttempted=true"
                    )
                }

                when (val unreadResult = unreadD.await()) {
                    is DataResult.Content -> {
                        serverListState = serverListState.applyUnreadSummary(unreadResult.data)
                        homeState = homeState.applyUnreadSummary(unreadResult.data)
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
                    homeState = homeState.applyAgentDirectMessagePresence(members)
                }
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockPresence",
                        "members_load_result source=members_page serverId=${selectedServerId.value} result=${membersResult.realtimeResultName()} code=${membersResult.readDiagnosticCode()}"
                )

                val inboxResult = inboxD.await()
                inboxState =
                        when (inboxResult) {
                            is DataResult.Content -> inboxResult.toInboxUiState(inboxFilter)
                            is DataResult.Empty, is DataResult.Error,
                            is DataResult.NoPermission ->
                                    if (previousInboxState.loadState is ScreenLoadState.Content &&
                                                    previousInboxState.selectedFilter == inboxFilter
                                    ) {
                                        previousInboxState.copy(loadingMore = false)
                                    } else {
                                        inboxResult.toInboxUiState(inboxFilter, inboxPreviousContent)
                                    }
                        }

                val followedThreadsResult = followedD.await()
                followedThreadsState =
                        when (followedThreadsResult) {
                            is DataResult.Content ->
                                    followedThreadsResult.toFollowedThreadsUiState()
                            is DataResult.Empty, is DataResult.Error,
                            is DataResult.NoPermission ->
                                    if (previousFollowedThreadsState.loadState is
                                                    ScreenLoadState.Content
                                    ) {
                                        previousFollowedThreadsState.copy(loadingMore = false)
                                    } else {
                                        followedThreadsResult.toFollowedThreadsUiState(
                                                followedPreviousContent
                                        )
                                    }
                        }

                val savedResult = savedD.await()
                savedState =
                        when (savedResult) {
                            is DataResult.Content -> savedResult.toSavedInboxUiState()
                            is DataResult.Empty, is DataResult.Error,
                            is DataResult.NoPermission ->
                                    if (previousSavedState.loadState is ScreenLoadState.Content) {
                                        previousSavedState.copy(loadingMore = false)
                                    } else {
                                        savedResult.toSavedInboxUiState(savedPreviousContent)
                                    }
                        }

                searchState =
                        if (searchD == null) {
                            InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
                        } else {
                            when (val searchResult = searchD.await()) {
                            is DataResult.Content -> searchResult.toSearchInboxUiState()
                            is DataResult.Empty, is DataResult.Error, is DataResult.NoPermission ->
                                    if (searchState.loadState is ScreenLoadState.Content) {
                                        searchState.copy(loadingMore = false)
                                    } else {
                                        searchResult.toSearchInboxUiState(searchPreviousContent)
                                    }
                            }
                        }
            }
        }
    }

    LaunchedEffect(
            authState,
            destination,
            serverListState,
            selectedServerOverride,
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
        tasksState = tasksState.withLoading(tasksFilter)
        tasksState =
                taskRepository
                        .getTasks(
                                serverId = selectedServerId,
                                filter =
                                        TaskFilter(
                                                statuses = tasksFilter?.let { setOf(it) }.orEmpty()
                                        )
                        )
                        .toTasksUiState(tasksFilter)
                        .copy(refreshing = false)
    }

    LaunchedEffect(authState, destination, settingsDestination, settingsRepository) {
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
                SettingsDestination.Home, SettingsDestination.About ->
                        Unit
            }
        }
    }

    fun openMarkdownTag(tag: ThreadMarkdownTag) {
        fun openTaskReferenceMatches(matches: List<TaskSummary>, label: String, source: String): Boolean {
            return when (matches.size) {
                0 -> false
                1 -> {
                    val task = matches.single()
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockMarkdownTag",
                            "open_task tag=$label taskId=${task.id.value} source=$source"
                    )
                    openTaskContext(task)
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
                                taskRepository.getTasks(
                                        serverId = selectedServerId,
                                        filter = TaskFilter()
                                )
                ) {
                    is DataResult.Content -> {
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
                    previousHomeRoute = HomeRoute.Thread
                    homeRoute = HomeRoute.Thread
                    openThread(conversation)
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

    Box(
            modifier =
                    modifier.fillMaxSize()
                            .background(
                                    if (destination == AppDestination.Settings) {
                                        SlockColor.BrandYellow
                                    } else {
                                        SlockColor.AppBgLightBlue
                                    }
                            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .windowInsetsTopHeight(WindowInsets.statusBars)
                                    .background(
                                            if (destination == AppDestination.Home &&
                                                            homeRoute == HomeRoute.Thread
                                            ) {
                                                SlockColor.SurfaceWhite
                                            } else if (
                                                    destination == AppDestination.Settings &&
                                                            settingsDestination != SettingsDestination.Home
                                            ) {
                                                SlockColor.SurfaceWhite
                                            } else {
                                                SlockColor.BrandYellow
                                            }
                                    )
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                                    onOpenMemberProfile = { member -> openMemberProfile(member) },
                                    onOpenMarkdownTag = { tag -> openMarkdownTag(tag) },
                                    onOpenTask = { task -> openTaskContext(task) },
                                    onTaskStatusChange = ::updateTaskStatus,
                                    onRetryThreadTasks = {
                                        refreshSelectedThreadTasks(reason = "retry")
                                    },
                                    onThreadBack = {
                                        restoreReplyThreadParent() || returnFromThread()
                                    },
                                    onJoinChannel = { conversation ->
                                        joinVisibleChannel(conversation)
                                    },
                                    onRetryFollowedThreads = {
                                        val selectedServerId =
                                                selectedServerOverride
                                                        ?: (serverListState as?
                                                                        ScreenLoadState.Content)
                                                                ?.data
                                                                ?.selectedServer
                                                                ?.id
                                                                ?: currentAuthSession
                                                                ?.currentServerId?.let(::ServerId)
                                        if (selectedServerId != null) {
                                            coroutineScope.launch {
                                                followedThreadsState =
                                                        InboxUiState(ScreenLoadState.Loading)
                                                followedThreadsState =
                                                        conversationRepository
                                                                .getFollowedThreads(
                                                                        selectedServerId
                                                                )
                                                                .toFollowedThreadsUiState()
                                            }
                                        }
                                    },
                                    threadTab = threadTab,
                                    onThreadTabChange = { tab ->
                                        threadTab = tab
                                        if (tab == ThreadTab.Tasks) {
                                            refreshSelectedThreadTasks(reason = "tab_selected")
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
                                    serverListState = serverListState,
                                    homeState = homeState,
                                    draftThreadIds = composerDrafts.keys,
                                    inboxState = inboxState,
                                    followedThreadsState = followedThreadsState,
                                    selectedThreadState = selectedThreadState,
                                    selectedThreadId = selectedThreadId,
                                    replyHeaderContext =
                                            replyThreadBackStack
                                                    .lastOrNull()
                                                    ?.toThreadReplyHeaderContext()
                                                    ?: threadParentJumpTarget?.let { parentId ->
                                                        selectedThreadState
                                                                .toThreadReplyHeaderContext(
                                                                        MessageId(parentId)
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
                                                conversationRepository = conversationRepository,
                                                coroutineScope = coroutineScope
                                        )
                                    },
                                    onRetryThreadLoad = { selectedThreadRetryAction?.invoke() },
                                    threadMembersPanelState = threadMembersPanelState,
                                    onAddChannelMember = ::addSelectedThreadMember,
                                    onRemoveChannelMember = ::removeSelectedThreadMember,
                                    attachmentOpenStates = attachmentOpenStates,
                                    attachmentPreviewState = attachmentPreviewState,
                                    onThreadNearBottomChange = { selectedThreadNearBottom = it },
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
                                    savedState = savedState,
                                    searchState = searchState,
                                    searchQuery = searchQuery,
                                    onSearchQueryChange = { searchQuery = it },
                                    homeRefreshInProgress = homeRefreshInProgress,
                                    homeRefreshError = homeRefreshError,
                                    homeRefreshErrorLabel = homeRefreshErrorLabel,
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
                                            inboxState = inboxState.withBodyLoading(inboxFilter)
                                            followedThreadsState =
                                                    followedThreadsState.withBodyLoading()
                                            savedState = savedState.withBodyLoading()
                                            searchState =
                                                    if (searchQuery.isBlank()) {
                                                        InboxUiState(
                                                                ScreenLoadState.Empty(
                                                                        EmptyReason.NoSearchResults
                                                                )
                                                        )
                                                    } else {
                                                        searchState.withBodyLoading()
                                                    }
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
                                    },
                                    onSend = {
                                        val body = composerValue.trim()
                                        val threadId = selectedThreadId?.let(::ThreadId)
                                        val channelId = selectedThreadId?.let(::ChannelId)
                                        val selectedHeader = selectedThreadContent()?.header
                                        if (selectedHeader != null &&
                                                        (!selectedHeader.joined ||
                                                                !selectedHeader.permissions.canSend)
                                        ) {
                                            selectedThreadState =
                                                    selectedThreadState.withComposerStatus(
                                                            status = ComposerStatus.NoPermission,
                                                            noPermissionCode =
                                                                    if (!selectedHeader.joined)
                                                                            "join_required"
                                                                    else "read_only"
                                                    )
                                        } else if (threadId != null &&
                                                        (body.isNotEmpty() ||
                                                                pendingAttachmentFiles.isNotEmpty())
                                        ) {
                                            failedAttachmentIds = emptySet()
                                            selectedThreadState =
                                                    selectedThreadState.withComposerStatus(
                                                            if (pendingAttachmentFiles.isNotEmpty())
                                                                    ComposerStatus.Uploading
                                                            else ComposerStatus.Sending
                                                    )
                                            coroutineScope.launch {
                                                val attachmentIds =
                                                        if (channelId != null &&
                                                                        pendingAttachmentFiles
                                                                                .isNotEmpty()
                                                        ) {
                                                            when (val uploadResult =
                                                                            conversationRepository
                                                                                    .uploadAttachments(
                                                                                            AttachmentUploadRequest(
                                                                                                    channelId =
                                                                                                            channelId,
                                                                                                    files =
                                                                                                            pendingAttachmentFiles
                                                                                            )
                                                                                    )
                                                            ) {
                                                                is DataResult.Content ->
                                                                        uploadResult
                                                                                .data
                                                                                .attachmentIds
                                                                is DataResult.Empty -> emptyList()
                                                                is DataResult.Error -> {
                                                                    failedAttachmentIds =
                                                                            pendingAttachmentFiles
                                                                                    .map { it.id }
                                                                                    .toSet()
                                                                    composerDrafts =
                                                                            composerDrafts
                                                                                    .saveComposerDraft(
                                                                                            threadId.value,
                                                                                            composerValue,
                                                                                            composerAsTask,
                                                                                            pendingAttachmentFiles,
                                                                                            failedAttachmentIds
                                                                                    )
                                                                    selectedThreadState =
                                                                            selectedThreadState
                                                                                    .withComposerStatus(
                                                                                            status =
                                                                                                    ComposerStatus
                                                                                                            .Failed,
                                                                                            errorCode =
                                                                                                    uploadResult
                                                                                                            .error
                                                                                                            .code
                                                                                    )
                                                                    return@launch
                                                                }
                                                                is DataResult.NoPermission -> {
                                                                    failedAttachmentIds =
                                                                            pendingAttachmentFiles
                                                                                    .map { it.id }
                                                                                    .toSet()
                                                                    composerDrafts =
                                                                            composerDrafts
                                                                                    .saveComposerDraft(
                                                                                            threadId.value,
                                                                                            composerValue,
                                                                                            composerAsTask,
                                                                                            pendingAttachmentFiles,
                                                                                            failedAttachmentIds
                                                                                    )
                                                                    selectedThreadState =
                                                                            selectedThreadState
                                                                                    .withComposerStatus(
                                                                                            status =
                                                                                                    ComposerStatus
                                                                                                            .NoPermission,
                                                                                            noPermissionCode =
                                                                                                    uploadResult
                                                                                                            .blocker
                                                                                                            .code
                                                                                    )
                                                                    return@launch
                                                                }
                                                            }
                                                        } else {
                                                            emptyList()
                                                        }
                                                selectedThreadState =
                                                        selectedThreadState.withComposerStatus(
                                                                ComposerStatus.Sending
                                                        )
                                                val result =
                                                        conversationRepository.sendMessage(
                                                                threadId = threadId,
                                                                body = body,
                                                                attachmentIds = attachmentIds,
                                                                asTask = composerAsTask
                                                        )
                                                when (result) {
                                                    is DataResult.Content -> {
                                                        selectedThreadState =
                                                                selectedThreadState
                                                                        .appendSentMessage(
                                                                                result.data
                                                                        )
                                                        composerDrafts =
                                                                composerDrafts - threadId.value
                                                        composerValue = ""
                                                        composerAsTask = false
                                                        pendingAttachmentFiles = emptyList()
                                                        failedAttachmentIds = emptySet()
                                                    }
                                                    is DataResult.Empty -> {
                                                        composerDrafts =
                                                                composerDrafts.saveComposerDraft(
                                                                        threadId.value,
                                                                        composerValue,
                                                                        composerAsTask,
                                                                        pendingAttachmentFiles,
                                                                        failedAttachmentIds
                                                                )
                                                        selectedThreadState =
                                                                selectedThreadState
                                                                        .withComposerStatus(
                                                                                status =
                                                                                        ComposerStatus
                                                                                                .Failed,
                                                                                errorCode =
                                                                                        result.reason
                                                                                                .name
                                                                        )
                                                    }
                                                    is DataResult.Error -> {
                                                        composerDrafts =
                                                                composerDrafts.saveComposerDraft(
                                                                        threadId.value,
                                                                        composerValue,
                                                                        composerAsTask,
                                                                        pendingAttachmentFiles,
                                                                        failedAttachmentIds
                                                                )
                                                        selectedThreadState =
                                                                selectedThreadState
                                                                        .withComposerStatus(
                                                                                status =
                                                                                        ComposerStatus
                                                                                                .Failed,
                                                                                errorCode =
                                                                                        result.error
                                                                                                .code
                                                                        )
                                                    }
                                                    is DataResult.NoPermission -> {
                                                        composerDrafts =
                                                                composerDrafts.saveComposerDraft(
                                                                        threadId.value,
                                                                        composerValue,
                                                                        composerAsTask,
                                                                        pendingAttachmentFiles,
                                                                        failedAttachmentIds
                                                                )
                                                        selectedThreadState =
                                                                selectedThreadState
                                                                        .withComposerStatus(
                                                                                status =
                                                                                        ComposerStatus
                                                                                                .NoPermission,
                                                                                noPermissionCode =
                                                                                        result.blocker
                                                                                                .code
                                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onLoadOlderThreadMessages = ::loadOlderThreadMessages,
                                    onLoadNewerThreadMessages = ::loadNewerThreadMessages,
                                    onInboxFilterSelected = { filter ->
                                        if (filter != inboxFilter) {
                                            val selectedServerId =
                                                    selectedServerOverride
                                                            ?: (serverListState as?
                                                                            ScreenLoadState.Content)
                                                                    ?.data
                                                                    ?.selectedServer
                                                                    ?.id
                                                                    ?: currentAuthSession
                                                                    ?.currentServerId?.let(
                                                                    ::ServerId
                                                            )
                                            inboxFilter = filter
                                            inboxState = inboxState.withFilterLoading(filter)
                                            if (selectedServerId != null) {
                                                coroutineScope.launch {
                                                    val previousContent = inboxState.previousContent
                                                    val result =
                                                            inboxRepository.loadInitial(
                                                                    serverId = selectedServerId,
                                                                    filter =
                                                                            filter.toRepositoryFilter()
                                                            )
                                                    val currentSelectedServerId =
                                                            selectedServerOverride
                                                                    ?: (serverListState as?
                                                                                    ScreenLoadState.Content)
                                                                            ?.data
                                                                            ?.selectedServer
                                                                            ?.id
                                                                            ?: currentAuthSession
                                                                            ?.currentServerId?.let(
                                                                            ::ServerId
                                                                    )
                                                    if (inboxFilter == filter &&
                                                                    currentSelectedServerId ==
                                                                            selectedServerId
                                                    ) {
                                                        inboxState =
                                                                result.toInboxUiState(
                                                                        filter,
                                                                        previousContent
                                                                )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onLoadInboxMore = { offset, filter ->
                                        val selectedServerId =
                                                selectedServerOverride
                                                        ?: (serverListState as?
                                                                        ScreenLoadState.Content)
                                                                ?.data
                                                                ?.selectedServer
                                                                ?.id
                                                                ?: currentAuthSession
                                                                ?.currentServerId?.let(::ServerId)
                                        if (selectedServerId != null && offset != null) {
                                            coroutineScope.launch {
                                                inboxState = inboxState.copy(loadingMore = true)
                                                inboxState =
                                                        mergeInboxPage(
                                                                current = inboxState,
                                                                next =
                                                                        inboxRepository
                                                                                .loadMore(
                                                                                        serverId =
                                                                                                selectedServerId,
                                                                                        filter =
                                                                                                filter.toRepositoryFilter(),
                                                                                        offset =
                                                                                                offset
                                                                                )
                                                                                .toInboxUiState(
                                                                                        filter
                                                                                )
                                                        )
                                            }
                                        }
                                    },
                                    onLoadSavedMore = { offset ->
                                        val selectedServerId =
                                                selectedServerOverride
                                                        ?: (serverListState as?
                                                                        ScreenLoadState.Content)
                                                                ?.data
                                                                ?.selectedServer
                                                                ?.id
                                                                ?: currentAuthSession
                                                                ?.currentServerId?.let(::ServerId)
                                        if (selectedServerId != null && offset != null) {
                                            coroutineScope.launch {
                                                savedState = savedState.copy(loadingMore = true)
                                                savedState =
                                                        mergeInboxPage(
                                                                current = savedState,
                                                                next =
                                                                        savedRepository
                                                                                .loadMore(
                                                                                        serverId =
                                                                                                selectedServerId,
                                                                                        offset =
                                                                                                offset
                                                                                )
                                                                                .toSavedInboxUiState()
                                                        )
                                            }
                                        }
                                    },
                                    onLoadSearchMore = { offset ->
                                        val selectedServerId =
                                                selectedServerOverride
                                                        ?: (serverListState as?
                                                                        ScreenLoadState.Content)
                                                                ?.data
                                                                ?.selectedServer
                                                                ?.id
                                                                ?: currentAuthSession
                                                                ?.currentServerId?.let(::ServerId)
                                        if (selectedServerId != null &&
                                                        offset != null &&
                                                        searchQuery.isNotBlank()
                                        ) {
                                            coroutineScope.launch {
                                                searchState = searchState.copy(loadingMore = true)
                                                searchState =
                                                        mergeInboxPage(
                                                                current = searchState,
                                                                next =
                                                                        searchRepository
                                                                                .search(
                                                                                        serverId =
                                                                                                selectedServerId,
                                                                                        query =
                                                                                                searchQuery,
                                                                                        offset =
                                                                                                offset
                                                                                )
                                                                                .toSearchInboxUiState()
                                                        )
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
                                            chatLayoutPreferenceSnapshot.myMessageAlignment
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
                                    }
                            )
                    AppDestination.Settings ->
                            SettingsScreen(
                                    state = SlockStateSamples.settingsContent,
                                    destination = settingsDestination,
                                    onDestinationChange = { settingsDestination = it },
                                    accountState = settingsAccountState,
                                    computersState = settingsComputersState,
                                    computerDetailState = settingsComputerDetailState,
                                    serverState = settingsServerState,
                                    billingState = settingsBillingState,
                                    mobileState = settingsMobileState,
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
                                    notificationPermissionLauncherAvailable = true,
                                    localeSnapshot = localeSnapshot,
                                    onLocaleSelected = onLocaleSelected,
                                    chatLayoutPreferenceSnapshot = chatLayoutPreferenceSnapshot,
                                    onMyMessageAlignmentSelected = onMyMessageAlignmentSelected,
                                    onOpenComputerDetail = { machineId ->
                                        settingsSelectedComputerId = machineId
                                        settingsDestination = SettingsDestination.ComputerDetail
                                    },
                                    onSaveServerProfile = { name ->
                                        coroutineScope.launch {
                                            settingsServerActionInProgress = true
                                            settingsServerActionFeedback = null
                                            val result =
                                                    settingsRepository.updateServerProfile(
                                                            SettingsServerProfileUpdateRequest(name)
                                                    )
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
                                                    settingsRepository.createServerInvite(
                                                            SettingsServerInviteCreateRequest(email)
                                                    )
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
                                                    settingsRepository.deleteServer(
                                                            SettingsServerDeleteRequest(confirmation)
                                                    )
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
                                        context.ensureSettingsNotificationChannel()
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
                                                ai.slock.android.core.data.diagnostics
                                                        .SlockDiagnosticsLogger.d(
                                                        "SlockSettingsNotification",
                                                        "request_permission_result granted=$notificationPermissionGranted ${context.notificationDiagnostics().toLogFields()}"
                                                )
                                            }
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
                                        context.ensureSettingsNotificationChannel()
                                        ai.slock.android.core.data.diagnostics
                                                .SlockDiagnosticsLogger.d(
                                                "SlockSettingsNotification",
                                                "open_settings_start ${context.notificationDiagnostics().toLogFields()}"
                                        )
                                        context.openNotificationSettings()
                                    },
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
                                    }
                            )
                }
            }
            if (showBottomBar) {
                Box(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .background(SlockColor.SurfaceWhite)
                                        .navigationBarsPadding()
                                        .padding(bottom = SlockSpacing.Xs)
                ) {
                    SlockBottomTabBar(
                            items = appBottomTabs(),
                            selectedKey = destination.key,
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
                            modifier = Modifier.fillMaxWidth().offset(y = -SlockStroke.Default)
                    )
                }
            }
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
            val createChannelMembers =
                    ((SlockStateSamples.membersContent.loadState as? ScreenLoadState.Content)
                                    ?.data
                                    ?.members)
                            .orEmpty()
            val createChannelAgents = createChannelMembers.filter { it.kind == MemberKind.Agent }
            val createChannelUsers = createChannelMembers.filter { it.kind != MemberKind.Agent }
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
                                            ) {
                                                is DataResult.Content -> {
                                                    val channel = result.data.channel
                                                    homeState =
                                                            homeState.withCreatedChannel(channel)
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
                                Modifier.heightIn(max = 520.dp)
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
                            onSelectionChange = { memberId, selected ->
                                createChannelAgentMemberIds =
                                        createChannelAgentMemberIds.withSelection(
                                                memberId,
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
                            emptyText = stringResource(R.string.home_create_channel_no_members),
                            onSelectionChange = { memberId, selected ->
                                createChannelUserMemberIds =
                                        createChannelUserMemberIds.withSelection(memberId, selected)
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

@Composable
private fun AuthRestoreScreen(modifier: Modifier = Modifier) {
    Box(
            modifier = modifier.fillMaxSize().background(SlockColor.SurfaceCream),
            contentAlignment = Alignment.Center
    ) {
        Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AuthLogoBadge(text = stringResource(R.string.auth_logo))
            Text(
                    text = stringResource(R.string.auth_tagline),
                    style = SlockType.Body,
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
        authState: AuthUiState,
        onAuthProviderUnavailable: (String) -> Unit,
        modifier: Modifier = Modifier
) {
    if (route != AuthRoute.Login) {
        BackHandler { onRouteChange(AuthRoute.Login) }
    }

    when (route) {
        AuthRoute.Login ->
                LoginScreen(
                        onLogin = onLogin,
                        onForgotPassword = { onRouteChange(AuthRoute.ForgotPassword) },
                        onCreateAccount = { onRouteChange(AuthRoute.CreateAccount) },
                        onAuthProviderUnavailable = onAuthProviderUnavailable,
                        isSubmitting =
                                authState is AuthUiState.Submitting ||
                                        authState is AuthUiState.Restoring,
                        errorMessage = (authState as? AuthUiState.SignedOut)?.errorMessage,
                        modifier = modifier
                )
        AuthRoute.ForgotPassword ->
                ForgotPasswordScreen(
                        onBackToSignIn = { onRouteChange(AuthRoute.Login) },
                        modifier = modifier
                )
        AuthRoute.CreateAccount ->
                CreateAccountScreen(
                        onCreateAccount = { _, _, _ -> },
                        onBackToSignIn = { onRouteChange(AuthRoute.Login) },
                        modifier = modifier
                )
    }
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
                        Icons.Outlined.Home
                ),
                SlockBottomTabItem(
                        AppDestination.Tasks.key,
                        stringResource(R.string.bottom_tab_tasks),
                        SlockIcons.ListTodo
                ),
                SlockBottomTabItem(
                        AppDestination.Members.key,
                        stringResource(R.string.bottom_tab_members),
                        Icons.Outlined.Person
                ),
                SlockBottomTabItem(
                        AppDestination.Settings.key,
                        stringResource(R.string.bottom_tab_settings),
                        Icons.Outlined.Settings
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
        onOpenMemberProfile: (MemberProfile) -> Unit,
        onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
        onOpenTask: (TaskSummary) -> Unit,
        onTaskStatusChange: (TaskSummary, TaskStatus) -> Unit,
        onRetryThreadTasks: () -> Unit,
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
        serverListState: ScreenLoadState<ServerListSnapshot>,
        homeState: HomeUiState,
        draftThreadIds: Set<String>,
        inboxState: InboxUiState,
        followedThreadsState: InboxUiState,
        selectedThreadState: ThreadUiState,
        selectedThreadId: String?,
        replyHeaderContext: ThreadReplyHeaderContext?,
        parentJumpTarget: ThreadParentJumpTarget?,
        onOpenParentMessage: (ThreadParentJumpTarget) -> Unit,
        threadMembersPanelState: ThreadMembersPanelState,
        onAddChannelMember: (String, MemberKind) -> Unit,
        onRemoveChannelMember: (MemberProfile) -> Unit,
        attachmentOpenStates: Map<AttachmentId, AttachmentOpenUiState>,
        attachmentPreviewState: AttachmentPreviewUiState?,
        onThreadNearBottomChange: (Boolean) -> Unit,
        onOpenAttachment: (Attachment) -> Unit,
        onDismissAttachmentPreview: () -> Unit,
        onOpenAttachmentPreviewExternally: (AttachmentPreviewUiState) -> Unit,
        savedState: InboxUiState,
        searchState: InboxUiState,
        searchQuery: String,
        onSearchQueryChange: (String) -> Unit,
        homeRefreshInProgress: Boolean,
        homeRefreshError: String?,
        homeRefreshErrorLabel: String?,
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
        onInboxFilterSelected: (InboxFilter) -> Unit,
        onLoadInboxMore: (Int?, InboxFilter) -> Unit,
        onLoadSavedMore: (Int?) -> Unit,
        onLoadSearchMore: (Int?) -> Unit
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
                        selectedThreadId = selectedThreadId,
                        homeRefreshInProgress = homeRefreshInProgress,
                        homeRefreshError = homeRefreshError,
                        homeRefreshErrorLabel = homeRefreshErrorLabel,
                        onHomeRefresh = onHomeRefresh,
                        serverSwitcherExpanded = serverSwitcherExpanded,
                        onServerSwitcherExpandedChange = onServerSwitcherExpandedChange,
                        onServerSelected = onServerSelected,
                        onCreateServerClick = onCreateServerClick,
                        onCreateChannelClick = onCreateChannelClick
                )
        HomeRoute.Thread -> {
            val baseThreadState =
                    selectedThreadState.takeUnless {
                        it.loadState == ScreenLoadState.Loading && selectedThreadId == null
                    }
                            ?: SlockStateSamples.threadContent
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
                                                                        localComposerMessages
                                                                                .toLocalThreadMessages(
                                                                                        loadState
                                                                                                .data
                                                                                ))
                                                                .distinctBy { it.id }
                                                                .withThreadAuthorProfiles(
                                                                        threadAuthorProfiles
                                                                )
                                        )
                                )
                        else -> loadState
                    }
            val enrichedReplyHeaderContext =
                    replyHeaderContext?.withThreadAuthorProfiles(threadAuthorProfiles)
            val retryLoadOlderThreadMessagesHandler: () -> Unit = {
                onLoadOlderThreadMessages(null, 0)
            }
            ThreadScreen(
                    state =
                            baseThreadState.copy(
                                    loadState = threadLoadState,
                                    selectedTab = threadTab,
                                    composerState =
                                            baseThreadState.composerState.copy(
                                                    value = composerValue,
                                                    asTask = composerAsTask,
                                                    attachmentIds =
                                                            List(pendingAttachmentCount) { index ->
                                                                AttachmentId(
                                                                        "pending-attachment-$index"
                                                                )
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
                            ),
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
                    onOpenTask = onOpenTask,
                    onTaskStatusChange = onTaskStatusChange,
                    onRetryTasks = onRetryThreadTasks,
                    attachmentOpenStates = attachmentOpenStates,
                    attachmentPreviewState = attachmentPreviewState,
                    onOpenAttachment = onOpenAttachment,
                    onDismissAttachmentPreview = onDismissAttachmentPreview,
                    onOpenAttachmentPreviewExternally = onOpenAttachmentPreviewExternally,
                    onJoinChannel = onJoinChannel,
                    membersPanelState = threadMembersPanelState,
                    onAddChannelMember = onAddChannelMember,
                    onRemoveChannelMember = onRemoveChannelMember,
                    currentMemberId = currentMemberId,
                    myMessageAlignment = myMessageAlignment,
                    replyHeaderContext = enrichedReplyHeaderContext,
                    parentJumpTarget = parentJumpTarget,
                    onOpenParentMessage = onOpenParentMessage,
                    onRetryThreadLoad = onRetryThreadLoad,
                    channelSuggestions =
                            (homeState.loadState as? ScreenLoadState.Content)
                                    ?.data?.channels.orEmpty()
            )
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
                        onLoadMore = {
                            onLoadInboxMore(inboxState.nextOffset, inboxState.selectedFilter)
                        }
                )
        HomeRoute.FollowedThreads ->
                FollowedThreadsScreen(
                        state = followedThreadsState,
                        onBack = { onRouteChange(HomeRoute.Shell) },
                        onOpenThread = {
                            onOpenThreadItem(it)
                            onOpenThreadFromRoute(HomeRoute.FollowedThreads)
                        },
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
                        onLoadMore = { onLoadSavedMore(savedState.nextOffset) }
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
                        onLoadMore = { onLoadSearchMore(searchState.nextOffset) }
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
        onLoadMore: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockPageHeader(
                title = stringResource(R.string.home_search),
                subtitle = stringResource(R.string.inbox_more_items_available)
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
        InboxScreen(
                state = state,
                onBack = onBack,
                onOpenThread = onOpenThread,
                onLoadMore = onLoadMore
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

private enum class HomeRoute {
    Shell,
    Thread,
    Inbox,
    FollowedThreads,
    Saved,
    Search
}

private data class ReplyThreadBackStackEntry(
        val selectedThreadId: String?,
        val selectedThreadState: ThreadUiState,
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
                                    ?: content.header.subtitle,
            parentMessage = parentMessage
    )
}

private fun ThreadUiState.toThreadReplyHeaderContext(
        parentMessageId: MessageId
): ThreadReplyHeaderContext? {
    val content = (loadState as? ScreenLoadState.Content)?.data ?: return null
    val parentMessage =
            content.messages.firstOrNull { it.id == parentMessageId }
                    ?: content.parentMessage?.takeIf { it.id == parentMessageId }
    return ThreadReplyHeaderContext(
            parentChannelName = content.header.name.takeIf { it.isNotBlank() },
            parentMessagePreview = parentMessage?.task?.title
                            ?: parentMessage?.body?.takeIf { it.isNotBlank() }
                                    ?: content.header.subtitle,
            parentMessage = parentMessage
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
        selectedThreadId: String?,
        homeRefreshInProgress: Boolean,
        homeRefreshError: String?,
        homeRefreshErrorLabel: String?,
        onHomeRefresh: () -> Unit,
        serverSwitcherExpanded: Boolean,
        onServerSwitcherExpandedChange: (Boolean) -> Unit,
        onServerSelected: (String) -> Unit,
        onCreateServerClick: () -> Unit,
        onCreateChannelClick: () -> Unit
) {
    var lastVisibleHomeContent by remember { mutableStateOf<HomeContent?>(null) }
    val currentHomeContent = (homeState.loadState as? ScreenLoadState.Content)?.data
            ?.withDraftIndicators(draftThreadIds)
    LaunchedEffect(currentHomeContent) {
        if (currentHomeContent != null) {
            lastVisibleHomeContent = currentHomeContent
        }
    }
    val content =
            resolveHomeShellContent(
                    currentContent = currentHomeContent,
                    lastVisibleContent = lastVisibleHomeContent,
                    homeLoadState = homeState.loadState,
                    homeRefreshInProgress = homeRefreshInProgress,
                    fallbackContent =
                            fallbackHomeContent(
                                    serverListState = serverListState,
                                    selectedServerId = homeState.selectedServerId
                            )
            )
    HomeStructuralContent(
            onOpenThread = onOpenThread,
            onOpenInbox = onOpenInbox,
            onOpenFollowedThreads = onOpenFollowedThreads,
            onOpenSaved = onOpenSaved,
            onOpenSearch = onOpenSearch,
            serverListState = serverListState,
            content = content,
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
            onCreateChannelClick = onCreateChannelClick
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
        selectedThreadId: String?,
        homeRefreshInProgress: Boolean,
        homeRefreshError: String?,
        homeRefreshErrorLabel: String?,
        onHomeRefresh: () -> Unit,
        serverSwitcherExpanded: Boolean,
        onServerSwitcherExpandedChange: (Boolean) -> Unit,
        onServerSelected: (String) -> Unit,
        onCreateServerClick: () -> Unit,
        onCreateChannelClick: () -> Unit
) {
    val serverSnapshot = (serverListState as? ScreenLoadState.Content)?.data
    val servers = serverSnapshot?.servers?.map { it.toSwitcherItem() }.orEmpty()
    val selectedServer = serverSnapshot?.selectedServer ?: serverSnapshot?.servers?.firstOrNull()
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
                serverSwitcherExpanded = serverSwitcherExpanded,
                contentRefreshing = homeRefreshInProgress,
                contentRefreshEnabled = homeRefreshError == null,
                showBottomBar = false,
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
        modifier: Modifier = Modifier,
        emptyText: String? = null,
        onSelectionChange: (String, Boolean) -> Unit
) {
    val pickerMembers = members.map { it.toSlockMemberPickerItem() }
    val selectedIdSet = selectedIds.toSet()
    SlockMemberPickerSection(
            label = title,
            members = pickerMembers,
            selectedIds = selectedIdSet,
            enabled = enabled,
            modifier = modifier,
            emptyText = emptyText ?: "None",
            selectedText = "Selected",
            disabledText = "Joined",
            availableText = "Add",
            onMemberSelected = { member ->
                onSelectionChange(member.id, member.id !in selectedIdSet)
            }
    )
}

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
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                    text = "$label matches multiple tasks.",
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
        Icon(
                imageVector = SlockIcons.ListTodo,
                contentDescription = null,
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                    text = listOfNotNull(task.taskNumber?.let { "#$it" }, task.title)
                            .joinToString(" "),
                    style = SlockType.Button,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            Text(
                    text =
                            listOfNotNull(
                                            task.channelName?.takeIf { it.isNotBlank() },
                                            task.status.name.lowercase().replace('_', ' ')
                                    )
                                    .joinToString(" · ")
                                    .ifBlank { task.updatedAtLabel },
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }
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
                ScreenLoadState.Content(
                        data.copy(servers = updatedServers, selectedServer = updatedSelectedServer)
                )
            }
            ScreenLoadState.Loading,
            is ScreenLoadState.Empty,
            is ScreenLoadState.Error,
            is ScreenLoadState.NoPermission -> this
        }

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
            (content.channels + channel).distinctBy { it.threadId }.sortedBy { it.name.lowercase() }
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
    val channelId = channel.channelId ?: ChannelId(channel.threadId.value)
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
                    .sortedBy { it.name.lowercase() }
    return copy(
            loadState = ScreenLoadState.Content(content.copy(channels = channels)),
            selectedThreadId = channel.threadId.value
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

private fun HomeUiState.applyUnreadCounts(snapshot: ChannelUnreadSnapshot): HomeUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    fun ConversationSummary.applyCount(): ConversationSummary {
        val id = channelId ?: ChannelId(threadId.value)
        val count = snapshot.unreadCounts[id] ?: 0
        return copy(
                unreadCount = count,
                mentionCount = 0,
                firstUnreadMessageId = firstUnreadMessageId.takeIf { count > 0 }
        )
    }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    channels = content.channels.map { it.applyCount() },
                                    directMessages = content.directMessages.map { it.applyCount() },
                                    unreadTotal = snapshot.totalUnreadCount
                            )
                    )
    )
}

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
                if (selectedFilter == InboxFilter.Unread) null else clearedItem
            }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    items = updatedItems,
                                    totalCount =
                                            if (selectedFilter == InboxFilter.Unread)
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
                if (selectedFilter == InboxFilter.Unread && count <= 0) null else updated
            }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    items = updatedItems,
                                    totalCount =
                                            if (selectedFilter == InboxFilter.Unread)
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
                joined = joined
        )

private fun Presence.toSlockPresence(): SlockPresence =
        when (this) {
            Presence.Active -> SlockPresence.Online
            Presence.Away -> SlockPresence.Unknown
            Presence.Offline -> SlockPresence.Offline
        }

private fun Long.updateFrom(event: RealtimeEvent): Long =
        when (event) {
            is RealtimeEvent.Heartbeat -> maxOf(this, event.seq)
            is RealtimeEvent.MessageNew -> maxOf(this, event.message.seq ?: 0L)
            is RealtimeEvent.MessageUpdated -> maxOf(this, event.message.seq ?: 0L)
            is RealtimeEvent.SyncResumeResponse -> maxOf(this, event.currentSeq)
            else -> this
        }

private fun RealtimeEvent.realtimeLogName(): String =
        when (this) {
            is RealtimeEvent.Heartbeat -> "heartbeat"
            is RealtimeEvent.MessageNew -> "message:new"
            is RealtimeEvent.MessageUpdated -> "message:updated"
            is RealtimeEvent.SyncResumeResponse -> "sync:resume:response"
            is RealtimeEvent.ThreadUpdated -> "thread:updated"
            is RealtimeEvent.DirectMessageCreated -> "direct-message:created"
            is RealtimeEvent.TaskCreated -> "task:created"
            is RealtimeEvent.TaskUpdated -> "task:updated"
            is RealtimeEvent.TaskDeleted -> "task:deleted"
            is RealtimeEvent.MemberPresenceUpdated -> "member:presence-updated"
        }

private fun ThreadContent.latestMessageSeq(): Long =
        messages.maxOfOrNull { it.seq ?: 0L } ?: 0L

private fun HomeUiState.applyRealtimeEvent(event: RealtimeEvent): HomeUiState =
        when (event) {
            is RealtimeEvent.MessageNew ->
                    applyRealtimeMessages(listOf(event.message), incrementUnread = true)
            is RealtimeEvent.MessageUpdated ->
                    applyRealtimeMessages(listOf(event.message), incrementUnread = false)
            is RealtimeEvent.SyncResumeResponse ->
                    applyRealtimeMessages(event.messages, incrementUnread = true)
            is RealtimeEvent.MemberPresenceUpdated -> applyMemberPresence(event)
            else -> this
        }

private fun HomeUiState.applyMemberPresence(
        event: RealtimeEvent.MemberPresenceUpdated
): HomeUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val updatedDms =
            content.directMessages.map { conversation ->
                if (conversation.matchesPresenceMember(event)) {
                    conversation.copy(presence = event.presence)
                } else {
                    conversation
                }
            }
    return copy(loadState = ScreenLoadState.Content(content.copy(directMessages = updatedDms)))
}

private fun HomeUiState.applyAgentDirectMessagePresence(
        members: List<MemberProfile>
): HomeUiState {
    val contentState = loadState as? ScreenLoadState.Content<HomeContent> ?: return this
    val content = contentState.data.withAgentDirectMessagePresence(members)
    return copy(loadState = ScreenLoadState.Content(content))
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
                name = name.takeUnless { it.isBlank() || it == "Direct message" } ?: previous.name,
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
                presence = presence ?: previous.presence,
                presenceMemberId = presenceMemberId ?: previous.presenceMemberId,
                joined = joined || previous.joined,
                archived = archived || previous.archived
        )

private fun ConversationSummary.withAgentDirectMessageFallback(
        member: MemberProfile
): ConversationSummary =
        copy(
                name = name.takeUnless { it.isBlank() || it == "Direct message" }
                        ?: member.displayName,
                avatarUrl = avatarUrl ?: member.avatarUrl,
                avatar = avatar ?: member.avatar,
                presence = presence ?: member.presence,
                presenceMemberId = presenceMemberId ?: member.id
        )

private fun InboxUiState.applyRealtimeEvent(
        event: RealtimeEvent,
        query: String? = null
): InboxUiState =
        when (event) {
            is RealtimeEvent.MessageNew -> applyRealtimeMessages(listOf(event.message), query)
            is RealtimeEvent.MessageUpdated -> applyRealtimeMessages(listOf(event.message), query)
            is RealtimeEvent.SyncResumeResponse -> applyRealtimeMessages(event.messages, query)
            is RealtimeEvent.ThreadUpdated -> applyThreadUpdated(event)
            else -> this
        }

private fun InboxUiState.applySavedRealtimeEvent(event: RealtimeEvent): InboxUiState =
        when (event) {
            is RealtimeEvent.MessageUpdated -> updateExistingMessage(event.message)
            is RealtimeEvent.SyncResumeResponse ->
                    event.messages.fold(this) { state, message ->
                        state.updateExistingMessage(message)
                    }
            else -> this
        }

private fun InboxUiState.applyRealtimeMessages(
        messages: List<Message>,
        query: String?
): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val filteredMessages =
            if (query.isNullOrBlank()) {
                messages
            } else {
                messages.filter { it.body.contains(query, ignoreCase = true) }
            }
    if (filteredMessages.isEmpty()) return this
    val content = contentState.data
    val items = (filteredMessages.map { it.toInboxItem() } + content.items).distinctBy { it.key }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    items = items,
                                    totalCount = content.totalCount.coerceAtLeast(items.size),
                                    totalUnreadCount = items.sumOf { it.unreadCount }
                            )
                    )
    )
}

private fun InboxUiState.updateExistingMessage(message: Message): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val updatedItems =
            content.items.map { item ->
                if (item.lastMessageId == message.id || item.latestActivityMessageId == message.id
                ) {
                    item.copy(
                            lastMessagePreview = message.body,
                            latestActivityPreview = message.body,
                            latestActivityAt = message.timestampLabel,
                            latestActivitySenderName = message.author.displayName
                    )
                } else {
                    item
                }
            }
    return copy(loadState = ScreenLoadState.Content(content.copy(items = updatedItems)))
}

private fun MembersUiState.applyRealtimeEvent(event: RealtimeEvent): MembersUiState =
        when (event) {
            is RealtimeEvent.MemberPresenceUpdated -> applyMemberPresence(event)
            else -> this
        }

private fun MembersUiState.applyMemberPresence(
        event: RealtimeEvent.MemberPresenceUpdated
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

private fun InboxUiState.applyThreadUpdated(event: RealtimeEvent.ThreadUpdated): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val updatedItems =
            content.items.map { item ->
                if (item.parentMessageId == event.parentMessageId ||
                                item.threadChannelId == event.threadChannelId
                ) {
                    item.copy(
                            replyCount = event.replyCount,
                            latestActivityAt = event.lastReplyAt ?: item.latestActivityAt,
                            participantIds = event.participantIds
                    )
                } else {
                    item
                }
            }
    return copy(loadState = ScreenLoadState.Content(content.copy(items = updatedItems)))
}

private fun List<Message>.applyRealtimeEvent(event: RealtimeEvent): List<Message> =
        when (event) {
            is RealtimeEvent.MessageNew -> (this + event.message).distinctBy { it.id }
            is RealtimeEvent.MessageUpdated ->
                    map { existing ->
                        if (existing.id == event.message.id) {
                            existing.mergeRealtimeUpdate(event.message)
                        } else {
                            existing
                        }
                    }
            is RealtimeEvent.SyncResumeResponse -> (this + event.messages).distinctBy { it.id }
            else -> this
        }

private fun ThreadUiState.applyRealtimeEvent(
        event: RealtimeEvent,
        selectedThreadId: String?,
        activeReplyParentMessageId: MessageId? = null
): ThreadUiState =
        when (event) {
            is RealtimeEvent.MessageNew ->
                    applyRealtimeThreadMessages(
                            messages = listOf(event.message),
                            selectedThreadId = selectedThreadId,
                            activeReplyParentMessageId = activeReplyParentMessageId
                    )
            is RealtimeEvent.MessageUpdated ->
                    updateRealtimeThreadMessage(
                            message = event.message,
                            selectedThreadId = selectedThreadId,
                            activeReplyParentMessageId = activeReplyParentMessageId
                    )
            is RealtimeEvent.SyncResumeResponse ->
                    applyRealtimeThreadMessages(
                            messages = event.messages,
                            selectedThreadId = selectedThreadId,
                            activeReplyParentMessageId = activeReplyParentMessageId
                    )
            is RealtimeEvent.ThreadUpdated -> applyThreadUpdated(event)
            is RealtimeEvent.TaskCreated, is RealtimeEvent.TaskUpdated -> mergeRealtimeTaskEvent(event)
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
            is RealtimeEvent.MemberPresenceUpdated -> applyMemberPresence(event)
            else -> this
        }

private fun ThreadMembersPanelState.applyMemberPresence(
        event: RealtimeEvent.MemberPresenceUpdated
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

private fun MemberProfile.withPresence(event: RealtimeEvent.MemberPresenceUpdated): MemberProfile {
    val kindMatches = event.kind == null || event.kind == kind
    return if (id == event.memberId && kindMatches) {
        copy(presence = event.presence)
    } else {
        this
    }
}

private fun ConversationSummary.matchesPresenceMember(
        event: RealtimeEvent.MemberPresenceUpdated
): Boolean =
        kind == ConversationKind.DirectMessage &&
                (event.kind == null ||
                        event.kind == MemberKind.Agent ||
                        event.kind == MemberKind.Human) &&
                (presenceMemberId == event.memberId ||
                        channelId?.value == event.memberId.value ||
                        threadId.value == event.memberId.value)

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
                                                    .sortedBy { it.seq ?: Long.MAX_VALUE }
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
                                            mergedMessages.distinctBy { it.id }.sortedBy {
                                                it.seq ?: Long.MAX_VALUE
                                            }
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

private fun ThreadUiState.applyThreadUpdated(event: RealtimeEvent.ThreadUpdated): ThreadUiState {
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
): Boolean =
        threadId == conversation.threadId ||
                channelId == conversation.channelId ||
                channelId?.value == conversation.threadId.value ||
                threadChannelId == conversation.channelId ||
                threadChannelId?.value == conversation.threadId.value ||
                selectedThreadId?.let { selectedId ->
                    threadId.value == selectedId ||
                            channelId?.value == selectedId ||
                            threadChannelId?.value == selectedId
                } == true ||
                activeReplyParentMessageId?.let { parentId -> parentMessageId == parentId } == true

private fun ThreadUiState.logVisibleThreadMerge(
        event: RealtimeEvent,
        selectedThreadId: String?,
        activeReplyParentMessageId: MessageId? = null
) {
    val content = (loadState as? ScreenLoadState.Content)?.data ?: return
    val messages =
            when (event) {
                is RealtimeEvent.MessageNew -> listOf(event.message)
                is RealtimeEvent.MessageUpdated -> listOf(event.message)
                is RealtimeEvent.SyncResumeResponse -> event.messages
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

private fun Message.toInboxItem(): InboxItem =
        InboxItem(
                key = id.value,
                kind = if (channelId == null) InboxItemKind.Thread else InboxItemKind.Channel,
                channelId = channelId ?: ChannelId(threadId.value),
                channelName = channelId?.value ?: threadId.value,
                unreadCount = 1,
                firstUnreadMessageId = id,
                lastMessageId = id,
                lastMessagePreview = body,
                lastMessageAt = timestampLabel,
                lastMessageSenderName = author.displayName,
                threadChannelId = ChannelId(threadId.value),
                latestActivityMessageId = id,
                latestActivityPreview = body,
                latestActivityAt = timestampLabel,
                latestActivitySenderName = author.displayName,
                replyCount = replyCount
        )

private fun DataResult<HomeSnapshot>.toHomeUiState(
        selectedServerId: String?,
        preservedServerUnreadCount: Int? = null,
        previousContent: HomeContent? = null
): HomeUiState =
        HomeUiState(
                loadState =
                        when (this) {
                            is DataResult.Content ->
                                    ScreenLoadState.Content(
                                            run {
                                                val server =
                                                        if (preservedServerUnreadCount != null) {
                                                            data.server.copy(
                                                                    unreadCount =
                                                                            preservedServerUnreadCount
                                                            )
                                                        } else {
                                                            data.server
                                                        }
                                                HomeContent(
                                                                server = server,
                                                                currentMember = data.currentMember,
                                                                channels = data.channels,
                                                                directMessages =
                                                                        data.directMessages,
                                                                unreadTotal = data.unreadTotal,
                                                                serverUnreadCount =
                                                                        preservedServerUnreadCount
                                                                                ?: data.server
                                                                                        .unreadCount
                                                        )
                                                        .withPreservedConversationUnread(
                                                                previousContent
                                                        )
                                            }
                                    )
                            is DataResult.Empty -> ScreenLoadState.Empty(EmptyReason.NoInboxItems)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        },
                selectedServerId = selectedServerId
        )

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
    val retryResult = channelRepository.getHome(serverId)
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

private fun DataResult<InboxSnapshot>.toInboxUiState(
        selectedFilter: InboxFilter,
        previousContent: InboxContent? = null
): InboxUiState =
        InboxUiState(
                loadState =
                        when (this) {
                            is DataResult.Content ->
                                    ScreenLoadState.Content(
                                            InboxContent(
                                                    server = data.server,
                                                    items = data.items,
                                                    totalCount = data.totalCount,
                                                    totalUnreadCount = data.totalUnreadCount,
                                                    hasMore = data.hasMore
                                            )
                                    )
                            is DataResult.Empty -> ScreenLoadState.Empty(reason)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        },
                selectedFilter = selectedFilter,
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset,
                previousContent = (this as? DataResult.Content)?.let { null } ?: previousContent
        )

private fun DataResult<ThreadListSnapshot>.toFollowedThreadsUiState(
        previousContent: InboxContent? = null
): InboxUiState =
        InboxUiState(
                loadState =
                        when (this) {
                            is DataResult.Content ->
                                    ScreenLoadState.Content(
                                            InboxContent(
                                                    server = data.server,
                                                    items = data.threads,
                                                    totalCount = data.totalCount,
                                                    totalUnreadCount =
                                                            data.threads.sumOf { it.unreadCount },
                                                    hasMore = data.hasMore
                                            )
                                    )
                            is DataResult.Empty -> ScreenLoadState.Empty(reason)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        },
                selectedFilter = InboxFilter.All,
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset,
                previousContent = (this as? DataResult.Content)?.let { null } ?: previousContent
        )

private fun DataResult<ThreadSnapshot>.toThreadUiState(
        fallbackHeader: ConversationSummary? = null,
        parentMessage: Message? = null,
        keepContentOnEmpty: Boolean = false
): ThreadUiState {
    val fallbackPreviewHeader = fallbackHeader?.takeIf { keepContentOnEmpty || !it.joined }
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
                                        parentMessage = parentMessage ?: data.parentMessage
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
                                                        keepContentOnEmpty &&
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
            composerState = header?.toComposerState()
                            ?: ComposerState(status = ComposerStatus.IdleEmpty)
    )
}

private fun DataResult<ThreadSnapshot>.findParentMessage(parentMessageId: MessageId?): Message? {
    if (parentMessageId == null || this !is DataResult.Content) return null
    return data.parentMessage?.takeIf { it.id == parentMessageId }
            ?: data.messages.firstOrNull { it.id == parentMessageId }
}

private fun HomeContent.findConversation(id: String): ConversationSummary? =
        (channels + directMessages).firstOrNull { conversation ->
            conversation.threadId.value == id || conversation.channelId?.value == id
        }

private fun InboxItem.toConversationSummary(): ConversationSummary {
    val threadId = ThreadId((threadChannelId ?: channelId).value)
    val title =
            when (kind) {
                InboxItemKind.Thread -> parentChannelName ?: channelName
                InboxItemKind.DirectMessage, InboxItemKind.Channel -> channelName
            }
    return ConversationSummary(
            threadId = threadId,
            channelId = threadChannelId ?: channelId,
            kind = parentChannelKind
                            ?: when (kind) {
                                InboxItemKind.DirectMessage -> ConversationKind.DirectMessage
                                else -> ConversationKind.Channel
                            },
            name = title,
            subtitle = parentMessagePreview ?: lastMessagePreview ?: latestActivityPreview,
            unreadCount = unreadCount,
            lastMessageId = latestActivityMessageId ?: lastMessageId,
            firstUnreadMessageId = firstUnreadMessageId,
            lastMessagePreview = latestActivityPreview ?: lastMessagePreview,
            lastMessageAt = latestActivityAt ?: lastMessageAt,
            lastMessageSenderName = latestActivitySenderName ?: lastMessageSenderName
    )
}

private fun Message.toConversationSummary(
        currentHeader: ConversationSummary?
): ConversationSummary {
    val targetThreadId = threadChannelId?.let { ThreadId(it.value) } ?: threadId
    return ConversationSummary(
            threadId = targetThreadId,
            channelId = threadChannelId ?: channelId,
            kind = currentHeader?.kind ?: ConversationKind.Channel,
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
    val targetThreadId = threadId ?: channelId?.let { ThreadId(it.value) } ?: ThreadId(id.value)
    return ConversationSummary(
            threadId = targetThreadId,
            channelId = channelId ?: fallback?.channelId,
            kind = fallback?.kind ?: ConversationKind.Channel,
            name = fallback?.name?.takeIf { it.isNotBlank() }
                            ?: channelName ?: targetThreadId.value,
            subtitle = title,
            lastMessageId = parentMessageId ?: messageId,
            lastMessagePreview = title,
            lastMessageAt = updatedAtLabel,
            lastMessageSenderName = assignee?.displayName
    )
}

private fun ConversationSummary.withTitleFallback(
        fallback: ConversationSummary?
): ConversationSummary {
    if (fallback == null) return this
    val genericNames =
            setOf("Messages", "channel", threadId.value, channelId?.value.orEmpty()).filter {
                it.isNotBlank()
            }
    val shouldUseFallback = name.isBlank() || name in genericNames
    val shouldUseFallbackThreadId = threadId.value == "unknown-channel"
    val shouldUseFallbackChannelId = channelId == null || channelId?.value == "unknown-channel"
    return if (shouldUseFallback) {
        copy(
                name = fallback.name,
                subtitle = subtitle ?: fallback.subtitle,
                threadId = if (shouldUseFallbackThreadId) fallback.threadId else threadId,
                channelId = if (shouldUseFallbackChannelId) fallback.channelId else channelId,
                kind = fallback.kind
        )
    } else {
        copy(
                threadId = if (shouldUseFallbackThreadId) fallback.threadId else threadId,
                channelId = if (shouldUseFallbackChannelId) fallback.channelId else channelId
        )
    }
}

private fun ConversationSummary.withPreviewMembershipFallback(
        fallback: ConversationSummary?
): ConversationSummary {
    if (fallback == null || fallback.joined) return this
    val sameChannel =
            threadId == fallback.threadId ||
                    channelId == fallback.channelId ||
                    channelId?.value == fallback.threadId.value ||
                    fallback.channelId?.value == threadId.value
    if (!sameChannel) return this
    return copy(joined = false, permissions = fallback.permissions)
}

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
                                                    .sortedBy { it.seq ?: Long.MAX_VALUE }
                            )
                    ),
            composerState = ComposerState(status = ComposerStatus.IdleEmpty)
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
                            .sortedBy { it.seq ?: Long.MAX_VALUE }
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
                            .sortedBy { it.seq ?: Long.MAX_VALUE }
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

private fun DataResult<SavedSnapshot>.toSavedInboxUiState(
        previousContent: InboxContent? = null
): InboxUiState =
        InboxUiState(
                loadState =
                        when (this) {
                            is DataResult.Content ->
                                    ScreenLoadState.Content(
                                            InboxContent(
                                                    server = data.server,
                                                    items = data.items,
                                                    totalCount = data.totalCount,
                                                    hasMore = data.hasMore
                                            )
                                    )
                            is DataResult.Empty -> ScreenLoadState.Empty(EmptyReason.NoInboxItems)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        },
                selectedFilter = InboxFilter.All,
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset,
                previousContent = (this as? DataResult.Content)?.let { null } ?: previousContent
        )

private fun DataResult<SearchSnapshot>.toSearchInboxUiState(
        previousContent: InboxContent? = null
): InboxUiState =
        InboxUiState(
                loadState =
                        when (this) {
                            is DataResult.Content ->
                                    ScreenLoadState.Content(
                                            InboxContent(
                                                    server = data.server,
                                                    items = data.items,
                                                    totalCount = data.totalCount,
                                                    hasMore = data.hasMore
                                            )
                                    )
                            is DataResult.Empty -> ScreenLoadState.Empty(reason)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        },
                selectedFilter = InboxFilter.All,
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset,
                previousContent = (this as? DataResult.Content)?.let { null } ?: previousContent
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
                existing?.withRefreshFailure(result.error.code)
                        ?: result.toTasksUiState(null).copy(refreshing = false)
        is DataResult.NoPermission ->
                existing?.withRefreshFailure(result.blocker.code)
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

private fun TasksUiState.applyRealtimeEvent(event: RealtimeEvent): TasksUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val updatedTasks =
            when (event) {
                is RealtimeEvent.TaskCreated ->
                        (contentState.data.tasks + event.tasks).distinctBy { it.id }
                is RealtimeEvent.TaskUpdated -> contentState.data.tasks.replaceOrAppend(event.task)
                is RealtimeEvent.TaskDeleted ->
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

private fun mergeInboxPage(current: InboxUiState, next: InboxUiState): InboxUiState {
    val currentContent = current.loadState as? ScreenLoadState.Content
    val nextContent = next.loadState as? ScreenLoadState.Content
    if (currentContent == null || nextContent == null) {
        return next.copy(loadingMore = false)
    }

    val mergedItems = (currentContent.data.items + nextContent.data.items).distinctBy { it.key }

    return next.copy(
            loadState =
                    ScreenLoadState.Content(
                            nextContent.data.copy(
                                    items = mergedItems,
                                    totalCount =
                                            nextContent.data.totalCount.coerceAtLeast(
                                                    mergedItems.size
                                            )
                            )
                    ),
            loadingMore = false
    )
}

private fun InboxFilter.toRepositoryFilter(): RepositoryInboxFilter =
        when (this) {
            InboxFilter.All -> RepositoryInboxFilter.All
            InboxFilter.Unread -> RepositoryInboxFilter.Unread
        }

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
                val members = buildList {
                    addAll(loadState.data.members.filterNot { it.id == member.id })
                    add(member)
                }
                copy(loadState = ScreenLoadState.Content(loadState.data.copy(members = members)))
            }
            else -> this
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
            roleDescription = roleDescription ?: profile.roleDescription,
            avatarUrl = mergedAvatarUrl,
            avatar = avatar ?: profile.avatar ?: AvatarReference.fromWireValue(mergedAvatarUrl),
            avatarLabel = avatarLabel.takeIf { it.isNotBlank() } ?: profile.avatarLabel,
            presence = if (presence == Presence.Offline) profile.presence else presence,
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
        accountState: ScreenLoadState<AccountSettingsSnapshot>,
        computersState: ScreenLoadState<SettingsComputersSnapshot>,
        computerDetailState: ScreenLoadState<SettingsComputerDetailSnapshot>,
        serverState: ScreenLoadState<SettingsServerSnapshot>,
        billingState: ScreenLoadState<SettingsBillingSnapshot>,
        mobileState: ScreenLoadState<SettingsMobileSnapshot>,
        aboutSnapshot: SettingsAboutSnapshot,
        notificationPermissionGranted: Boolean,
        notificationPermissionLauncherAvailable: Boolean,
        localeSnapshot: LocaleSnapshot,
        onLocaleSelected: (AppLocale) -> Unit,
        chatLayoutPreferenceSnapshot: ChatLayoutPreferenceSnapshot,
        onMyMessageAlignmentSelected: (MyMessageAlignmentPreference) -> Unit,
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
        onCopyDiagnostics: () -> Unit,
        onShareDiagnostics: () -> Unit,
        onClearDiagnostics: () -> Unit
) {
    if (destination != SettingsDestination.Home) {
        BackHandler {
            onDestinationChange(
                    if (destination == SettingsDestination.ComputerDetail) {
                        SettingsDestination.Computers
                    } else {
                        SettingsDestination.Home
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
        when (destination) {
            SettingsDestination.Home ->
                    SettingsPageSurface {
                        SettingsHomeScreen(
                                title = stringResource(R.string.settings_home_title),
                                subtitle = content.settings.server.name,
                                items = settingsHomeItems(content, localeSnapshot),
                                onItemClick = { key ->
                                    onDestinationChange(
                                            when (key) {
                                                SettingsDestination.Account.key ->
                                                        SettingsDestination.Account
                                                SettingsDestination.Computers.key ->
                                                        SettingsDestination.Computers
                                                SettingsDestination.Server.key ->
                                                        SettingsDestination.Server
                                                SettingsDestination.Billing.key ->
                                                        SettingsDestination.Billing
                                                SettingsDestination.Mobile.key ->
                                                        SettingsDestination.Mobile
                                                SettingsDestination.About.key ->
                                                        SettingsDestination.About
                                                else -> SettingsDestination.Home
                                            }
                                    )
                                }
                        )
                    }
            SettingsDestination.Account ->
                    SettingsSecondaryScreen(
                            title = stringResource(R.string.settings_account_title),
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
                            onBack = { onDestinationChange(SettingsDestination.Home) }
                    ) {
                        SettingsDataStateContent(
                                state = computersState,
                                emptyTitle =
                                        stringResource(R.string.settings_computers_empty_title),
                                emptyDetail =
                                        stringResource(R.string.settings_computers_empty_detail)
                        ) { computers ->
                            SettingsComputersScreen(
                                    snapshot = computers,
                                    onOpenComputer = onOpenComputerDetail
                            )
                        }
                    }
            SettingsDestination.ComputerDetail ->
                    SettingsSecondaryScreen(
                            title = stringResource(R.string.settings_computer_detail_title),
                            onBack = { onDestinationChange(SettingsDestination.Computers) }
                    ) {
                        SettingsDataStateContent(
                                state = computerDetailState,
                                emptyTitle =
                                        stringResource(R.string.settings_computer_detail_title),
                                emptyDetail =
                                        stringResource(R.string.settings_computer_detail_empty)
                        ) { detail ->
                            SettingsComputerDetailScreen(
                                    snapshot = detail,
                                    onRetryWorkspaces = onRetryComputerDetail,
                                    onRetryAgents = onRetryComputerDetail
                            )
                        }
                    }
            SettingsDestination.Server ->
                    SettingsSecondaryScreen(
                            title = stringResource(R.string.settings_server_title),
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
                                    localeSnapshot = localeSnapshot,
                                    onRequestNotificationPermission =
                                            onRequestNotificationPermission,
                                    onChatLayoutPreferenceSelected = onMyMessageAlignmentSelected,
                                    onLocaleSelected = onLocaleSelected,
                                    onSendTest = onSendTestNotification,
                                    onOpenNotificationSettings = onOpenNotificationSettings
                            )
                        }
                    }
            SettingsDestination.About ->
                    SettingsSecondaryScreen(
                            title = stringResource(R.string.settings_about_title),
                            onBack = { onDestinationChange(SettingsDestination.Home) }
                    ) {
                        SettingsAboutScreen(
                                snapshot = aboutSnapshot,
                                onCopyLogs = onCopyDiagnostics,
                                onShareLogs = onShareDiagnostics,
                                onClearLogs = onClearDiagnostics
                        )
                    }
        }
    }
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
        onBack: () -> Unit,
        content: @Composable () -> Unit
) {
    SettingsPageSurface {
        Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
            SettingsDetailHeader(title = title, onBack = onBack)
            Box(modifier = Modifier.weight(1f)) { content() }
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
    About("about")
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
        content: SettingsContent,
        localeSnapshot: LocaleSnapshot
): List<SettingsHomeItem> {
    val sections = content.settings.manageableSections.joinToString()
    val currentLanguage =
            when (localeSnapshot.current) {
                AppLocale.English -> stringResource(R.string.settings_language_current_english)
                AppLocale.SimplifiedChinese ->
                        stringResource(R.string.settings_language_current_chinese)
            }
    return listOf(
            SettingsHomeItem(
                    key = SettingsDestination.Account.key,
                    title = stringResource(R.string.settings_account_title),
                    subtitle = stringResource(R.string.settings_account_subtitle),
                    icon = SlockIcons.User,
                    value = sections.ifBlank { null },
                    selected = true
            ),
            SettingsHomeItem(
                    key = SettingsDestination.Computers.key,
                    title = stringResource(R.string.settings_computers_title),
                    subtitle = stringResource(R.string.settings_computers_subtitle),
                    icon = SlockIcons.Monitor
            ),
            SettingsHomeItem(
                    key = SettingsDestination.Server.key,
                    title = stringResource(R.string.settings_server_title),
                    subtitle = stringResource(R.string.settings_server_subtitle),
                    icon = SlockIcons.Users
            ),
            SettingsHomeItem(
                    key = SettingsDestination.Billing.key,
                    title = stringResource(R.string.settings_billing_title),
                    subtitle = stringResource(R.string.settings_billing_subtitle),
                    icon = SlockIcons.ShieldCheck
            ),
            SettingsHomeItem(
                    key = SettingsDestination.Mobile.key,
                    title = stringResource(R.string.settings_mobile_title),
                    subtitle = stringResource(R.string.settings_mobile_subtitle),
                    icon = SlockIcons.Smartphone,
                    value = currentLanguage
            ),
            SettingsHomeItem(
                    key = SettingsDestination.About.key,
                    title = stringResource(R.string.settings_about_title),
                    subtitle = stringResource(R.string.settings_about_home_subtitle),
                    icon = SlockIcons.Bug
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

private const val SettingsNotificationVisibleTestChannelId = "settings-test-visible-v2"

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
    ensureSettingsNotificationChannel()
    val diagnostics = notificationDiagnostics()
    if (!diagnostics.permissionGranted)
            return SettingsNotificationPostOutcome(
                    SettingsNotificationPostResult.PermissionDenied,
                    visibilityReason = "permission_denied"
            )
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
                                .setPriority(NotificationCompat.PRIORITY_HIGH)
                                .setCategory(NotificationCompat.CATEGORY_STATUS)
                                .setDefaults(NotificationCompat.DEFAULT_ALL)
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

private fun refreshSelectedThread(
        reason: String,
        selectedThreadStateProvider: () -> ThreadUiState,
        selectedThreadIdProvider: () -> String?,
        setSelectedThreadState: (ThreadUiState) -> Unit,
        conversationRepository: ConversationRepository,
        coroutineScope: CoroutineScope
) {
    val currentContent = selectedThreadStateProvider().selectedThreadContent() ?: return
    if (currentContent.refreshStatus == ThreadRefreshStatus.Loading) return
    val threadId = currentContent.header.threadId
    setSelectedThreadState(
            selectedThreadStateProvider().withThreadRefreshStatus(ThreadRefreshStatus.Loading)
    )
    coroutineScope.launch {
        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                "SlockThreadLifecycle",
                "refresh_start reason=$reason threadId=${threadId.value}"
        )
        when (val result = conversationRepository.getThread(threadId)) {
            is DataResult.Content -> {
                if (selectedThreadIdProvider() == threadId.value) {
                    setSelectedThreadState(
                            selectedThreadStateProvider().mergeRefreshedThreadSnapshot(result.data)
                    )
                }
                ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                        "SlockThreadLifecycle",
                        "refresh_success reason=$reason threadId=${threadId.value} messageCount=${result.data.messages.size} canLoadMoreBefore=${result.data.canLoadMoreBefore} canLoadMoreAfter=${result.data.canLoadMoreAfter}"
                )
            }
            is DataResult.Empty -> {
                if (selectedThreadIdProvider() == threadId.value) {
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
                        "refresh_empty reason=$reason threadId=${threadId.value} code=${result.reason.name}"
                )
            }
            is DataResult.Error -> {
                if (selectedThreadIdProvider() == threadId.value) {
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
                        "refresh_error reason=$reason threadId=${threadId.value} code=${result.error.code}"
                )
            }
            is DataResult.NoPermission -> {
                if (selectedThreadIdProvider() == threadId.value) {
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
                        "refresh_no_permission reason=$reason threadId=${threadId.value} code=${result.blocker.code}"
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

@RequiresApi(Build.VERSION_CODES.O)
private fun Context.ensureSettingsNotificationChannelApi26() {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel =
            NotificationChannel(
                            SettingsNotificationVisibleTestChannelId,
                            getString(R.string.settings_notification_channel_name),
                            NotificationManager.IMPORTANCE_HIGH
                    )
                    .apply {
                        description = getString(R.string.settings_notification_channel_description)
                        enableVibration(true)
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

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SlockAppPreview() {
    SlockTheme { SlockApp() }
}
