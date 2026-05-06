package ai.slock.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.slock.android.core.data.auth.AuthResult
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthServerRepository
import ai.slock.android.core.data.auth.EncryptedAuthSessionStore
import ai.slock.android.core.data.auth.RetrofitAuthRepository
import ai.slock.android.core.data.locale.AppLocale
import ai.slock.android.core.data.locale.LocaleSnapshot
import ai.slock.android.core.data.locale.PersistentLocaleRepository
import ai.slock.android.core.data.locale.SharedPreferencesLocalePreferenceStore
import ai.slock.android.core.data.network.RealChannelRepository
import ai.slock.android.core.data.network.RealInboxRepository
import ai.slock.android.core.data.network.RealSavedRepository
import ai.slock.android.core.data.network.RealSearchRepository
import ai.slock.android.core.data.network.RealServerUnreadRepository
import ai.slock.android.core.data.network.RealSettingsRepository
import ai.slock.android.core.data.realtime.SessionRealtimeRepository
import ai.slock.android.core.data.realtime.SocketIoRealtimeClient
import ai.slock.android.core.data.realtime.SocketIoSocketTransport
import ai.slock.android.core.data.network.RealSlockApiFactory
import ai.slock.android.core.data.repository.AccountProfileSnapshot
import ai.slock.android.core.data.repository.AccountSettingsSnapshot
import ai.slock.android.core.data.repository.ChannelRepository
import ai.slock.android.core.data.repository.ConnectedAccountSnapshot
import ai.slock.android.core.data.repository.ConnectedAccountStatus as RepositoryConnectedAccountStatus
import ai.slock.android.core.data.repository.CreateServerRequest
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.InboxFilter as RepositoryInboxFilter
import ai.slock.android.core.data.repository.InboxRepository
import ai.slock.android.core.data.repository.RealtimeRepository
import ai.slock.android.core.data.repository.RealtimeSubscription
import ai.slock.android.core.data.repository.InboxSnapshot
import ai.slock.android.core.data.repository.SavedRepository
import ai.slock.android.core.data.repository.SavedSnapshot
import ai.slock.android.core.data.repository.SearchRepository
import ai.slock.android.core.data.repository.SearchSnapshot
import ai.slock.android.core.data.repository.SessionSettingsSnapshot
import ai.slock.android.core.data.repository.ServerListSnapshot
import ai.slock.android.core.data.repository.ServerUnreadRepository
import ai.slock.android.core.data.repository.ServerUnreadSummarySnapshot
import ai.slock.android.core.data.repository.ServerRepository
import ai.slock.android.core.data.repository.SettingsComputersSnapshot
import ai.slock.android.core.data.repository.SettingsMobileSnapshot
import ai.slock.android.core.data.repository.SettingsRepository
import ai.slock.android.core.data.repository.SettingsServerSnapshot
import ai.slock.android.core.data.repository.SettingsSessionSnapshot
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.feature.home.HomeContent
import ai.slock.android.feature.home.HomeUiState
import ai.slock.android.feature.inbox.InboxContent
import ai.slock.android.feature.inbox.InboxFilter
import ai.slock.android.feature.inbox.InboxUiState
import ai.slock.android.feature.auth.LoginScreen
import ai.slock.android.feature.auth.ForgotPasswordScreen
import ai.slock.android.feature.auth.CreateAccountScreen
import ai.slock.android.feature.auth.AuthLogoBadge
import ai.slock.android.feature.inbox.InboxScreen
import ai.slock.android.feature.members.MembersRouteScreen
import ai.slock.android.feature.sample.SlockStateSamples
import ai.slock.android.feature.settings.SettingsAccountProfile
import ai.slock.android.feature.settings.SettingsAccountScreen
import ai.slock.android.feature.settings.SettingsConnectedAccount
import ai.slock.android.feature.settings.SettingsConnectedAccountStatus
import ai.slock.android.feature.settings.SettingsContent
import ai.slock.android.feature.settings.SettingsComputersScreen
import ai.slock.android.feature.settings.SettingsDangerItem
import ai.slock.android.feature.settings.SettingsHomeItem
import ai.slock.android.feature.settings.SettingsHomeScreen
import ai.slock.android.feature.settings.SettingsMobileScreen
import ai.slock.android.feature.settings.SettingsNavRow
import ai.slock.android.feature.settings.SettingsServerManagementScreen
import ai.slock.android.feature.settings.SettingsSessionItem
import ai.slock.android.feature.settings.SettingsSessionsDangerScreen
import ai.slock.android.feature.settings.SettingsDetailHeader
import ai.slock.android.feature.tasks.TasksScreen
import ai.slock.android.feature.thread.ThreadContent
import ai.slock.android.feature.thread.ThreadScreen
import ai.slock.android.feature.thread.ComposerStatus
import ai.slock.android.feature.thread.ThreadTab
import ai.slock.android.ui.components.SlockBottomTabBar
import ai.slock.android.ui.components.SlockBottomTabItem
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockEmptyState
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.components.SlockModal
import ai.slock.android.ui.components.SlockModalAction
import ai.slock.android.ui.components.SlockModalActionStyle
import ai.slock.android.ui.components.SlockModalTextField
import ai.slock.android.ui.components.SlockPageHeader
import ai.slock.android.ui.components.SlockServerSwitcherItem
import ai.slock.android.ui.components.SlockStructuralHomeShell
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("SlockBoot", "main_activity_on_create buildMarker=auth-unread-diagnostics-v2")
        enableEdgeToEdge()
        setContent {
            SlockTheme {
                SlockApp()
            }
        }
    }
}

@Composable
fun SlockApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val localeStore = remember {
        SharedPreferencesLocalePreferenceStore(context)
    }
    val localeRepository = remember {
        PersistentLocaleRepository(localeStore)
    }
    val localeScope = rememberCoroutineScope()
    var localeSnapshot by remember {
        mutableStateOf(LocaleSnapshot(current = localeStore.readLocaleSync()))
    }

    SlockLocaleProvider(locale = localeSnapshot.current) {
        SlockAppContent(
            localeSnapshot = localeSnapshot,
            onLocaleSelected = { locale ->
                localeScope.launch {
                    localeSnapshot = localeRepository.selectLocale(locale)
                }
            },
            modifier = modifier
        )
    }
}

@Composable
private fun SlockLocaleProvider(
    locale: AppLocale,
    content: @Composable () -> Unit
) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val localizedConfiguration = remember(locale, baseConfiguration) {
        Configuration(baseConfiguration).apply {
            setLocales(LocaleList(locale.toJavaLocale()))
        }
    }
    val localizedContext = remember(locale, baseContext, localizedConfiguration) {
        baseContext.createConfigurationContext(localizedConfiguration)
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration,
        content = content
    )
}

@Composable
private fun SlockAppContent(
    localeSnapshot: LocaleSnapshot,
    onLocaleSelected: (AppLocale) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val authSessionStore = remember {
        EncryptedAuthSessionStore(context)
    }
    val authRepository = remember {
        RetrofitAuthRepository(authSessionStore)
    }
    val serverRepository = remember {
        AuthServerRepository(authSessionStore, authRepository)
    }
    var currentAuthSession by remember { mutableStateOf<AuthSession?>(null) }
    val slockApi = remember {
        RealSlockApiFactory.create(sessionProvider = { currentAuthSession })
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
    val serverUnreadRepository: ServerUnreadRepository = remember {
        RealServerUnreadRepository(authSessionStore, slockApi)
    }
    val settingsRepository: SettingsRepository = remember {
        RealSettingsRepository(authSessionStore, slockApi)
    }
    val realtimeRepository: RealtimeRepository = remember {
        SessionRealtimeRepository(
            store = authSessionStore,
            client = SocketIoRealtimeClient(SocketIoSocketTransport())
        )
    }
    val coroutineScope = rememberCoroutineScope()
    var authState by remember { mutableStateOf<AuthUiState>(AuthUiState.Restoring) }
    var authRoute by rememberSaveable { mutableStateOf(AuthRoute.Login) }
    var destination by rememberSaveable { mutableStateOf(AppDestination.Home) }
    var homeRoute by rememberSaveable { mutableStateOf(HomeRoute.Shell) }
    var previousHomeRoute by rememberSaveable { mutableStateOf<HomeRoute?>(null) }
    var threadTab by rememberSaveable { mutableStateOf(ThreadTab.Chat) }
    var composerValue by rememberSaveable {
        mutableStateOf(SlockStateSamples.threadContent.composerState.value)
    }
    var composerAsTask by rememberSaveable {
        mutableStateOf(SlockStateSamples.threadContent.composerState.asTask)
    }
    var localComposerMessages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var settingsDestination by rememberSaveable { mutableStateOf(SettingsDestination.Home) }
    var membersDetailVisible by rememberSaveable { mutableStateOf(false) }
    var serverListState by remember { mutableStateOf<ScreenLoadState<ServerListSnapshot>>(ScreenLoadState.Loading) }
    var homeState by remember { mutableStateOf<HomeUiState>(HomeUiState(ScreenLoadState.Loading)) }
    var homeRefreshInProgress by remember { mutableStateOf(false) }
    var homeRefreshError by remember { mutableStateOf<String?>(null) }
    var homeRefreshRetryNonce by rememberSaveable { mutableStateOf(0) }
    var homeRefreshAutoRetryAttempted by rememberSaveable { mutableStateOf(false) }
    var inboxFilter by rememberSaveable { mutableStateOf(InboxFilter.All) }
    var inboxState by remember { mutableStateOf<InboxUiState>(InboxUiState(ScreenLoadState.Loading)) }
    var savedState by remember { mutableStateOf<InboxUiState>(InboxUiState(ScreenLoadState.Loading)) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchState by remember { mutableStateOf<InboxUiState>(InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))) }
    var realtimeSubscription by remember { mutableStateOf<RealtimeSubscription?>(null) }
    var realtimeLastSeq by rememberSaveable { mutableStateOf(0L) }
    var realtimeThreadMessages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var settingsAccountState by remember {
        mutableStateOf<ScreenLoadState<AccountSettingsSnapshot>>(ScreenLoadState.Loading)
    }
    var settingsSessionsState by remember {
        mutableStateOf<ScreenLoadState<SessionSettingsSnapshot>>(ScreenLoadState.Loading)
    }
    var settingsComputersState by remember {
        mutableStateOf<ScreenLoadState<SettingsComputersSnapshot>>(ScreenLoadState.Loading)
    }
    var settingsServerState by remember {
        mutableStateOf<ScreenLoadState<SettingsServerSnapshot>>(ScreenLoadState.Loading)
    }
    var settingsMobileState by remember {
        mutableStateOf<ScreenLoadState<SettingsMobileSnapshot>>(ScreenLoadState.Loading)
    }
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
    var appInForeground by remember { mutableStateOf(true) }
    var unreadRefreshNonce by remember { mutableStateOf(0) }
    val showBottomBar = (destination != AppDestination.Home || homeRoute == HomeRoute.Shell) &&
        !(destination == AppDestination.Members && membersDetailVisible)

    LaunchedEffect(authRepository) {
        Log.d("SlockAuth", "restore_effect_start")
        authState = when (val restoreResult = authRepository.restoreSession()) {
            is AuthResult.Success -> {
                currentAuthSession = authSessionStore.readSession()
                Log.d(
                    "SlockAuth",
                    "restore_effect_success hasSession=${currentAuthSession != null} serverCount=${currentAuthSession?.servers?.size ?: 0} selectedServer=${currentAuthSession?.currentServerId != null}"
                )
                AuthUiState.Authenticated
            }
            AuthResult.Unauthenticated -> {
                Log.w("SlockAuth", "restore_effect_unauthenticated")
                AuthUiState.SignedOut()
            }
            is AuthResult.Failure -> {
                Log.w("SlockAuth", "restore_effect_failure message=${restoreResult.message}")
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
                    authState = AuthUiState.Submitting
                    when (val result = authRepository.login(email, password)) {
                        is AuthResult.Success -> {
                            currentAuthSession = authSessionStore.readSession()
                            authState = AuthUiState.Authenticated
                            authRoute = AuthRoute.Login
                            destination = AppDestination.Home
                            homeRoute = HomeRoute.Shell
                            previousHomeRoute = null
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
        }
    }

    val realtimeServerId = if (authState is AuthUiState.Authenticated) {
        selectedServerOverride
            ?: (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.id
            ?: currentAuthSession?.currentServerId?.let(::ServerId)
    } else {
        null
    }
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    Log.d("SlockUnread", "lifecycle_resume")
                    appInForeground = true
                    unreadRefreshNonce += 1
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    Log.d("SlockUnread", "lifecycle_stop")
                    appInForeground = false
                }
                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
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
            Log.d("SlockUnread", "skip_refresh reason=no_selected_server")
            return@LaunchedEffect
        }
        if (
            authState !is AuthUiState.Authenticated ||
            !appInForeground ||
            serverListState !is ScreenLoadState.Content
        ) {
            Log.d(
                "SlockUnread",
                "skip_refresh serverId=${serverId.value} authenticated=${authState is AuthUiState.Authenticated} foreground=$appInForeground hasServerList=${serverListState is ScreenLoadState.Content}"
            )
            return@LaunchedEffect
        }

        suspend fun refreshUnreadSummary(reason: String) {
            Log.d("SlockUnread", "fetch_start reason=$reason serverId=${serverId.value}")
            when (val result = serverUnreadRepository.getUnreadSummary(serverId)) {
                is DataResult.Content -> {
                    serverListState = serverListState.applyUnreadSummary(result.data)
                    homeState = homeState.applyUnreadSummary(result.data)
                    inboxState = inboxState.applyUnreadSummary(result.data)
                    savedState = savedState.applySavedUnreadSummary(result.data)
                    Log.d(
                        "SlockUnread",
                        "fetch_success reason=$reason serverId=${serverId.value} total=${result.data.totalUnreadCount} inbox=${result.data.inboxUnreadCount} saved=${result.data.savedUnreadCount}"
                    )
                }
                is DataResult.Empty -> {
                    Log.d("SlockUnread", "fetch_empty reason=$reason serverId=${serverId.value} empty=${result.reason}")
                }
                is DataResult.Error -> {
                    Log.w("SlockUnread", "fetch_error reason=$reason serverId=${serverId.value} code=${result.error.code}")
                }
                is DataResult.NoPermission -> {
                    Log.w("SlockUnread", "fetch_no_permission reason=$reason serverId=${serverId.value} code=${result.blocker.code}")
                }
            }
        }

        refreshUnreadSummary(reason = "effect_start")
        while (true) {
            delay(30_000)
            refreshUnreadSummary(reason = "interval_30s")
        }
    }

    DisposableEffect(realtimeServerId, lifecycleOwner, realtimeRepository) {
        var connected = false
        fun connectRealtime() {
            val serverId = realtimeServerId ?: return
            if (connected) return
            realtimeSubscription?.close()
            realtimeSubscription = realtimeRepository.observeEvents { event ->
                coroutineScope.launch {
                    realtimeLastSeq = realtimeLastSeq.updateFrom(event)
                    homeState = homeState.applyRealtimeEvent(event)
                    inboxState = inboxState.applyRealtimeEvent(event)
                    savedState = savedState.applySavedRealtimeEvent(event)
                    searchState = searchState.applyRealtimeEvent(event, searchQuery)
                    realtimeThreadMessages = realtimeThreadMessages.applyRealtimeEvent(event)
                }
            }
            coroutineScope.launch {
                if (realtimeRepository.connect(serverId, realtimeLastSeq) is DataResult.Content) {
                    connected = true
                }
            }
        }
        fun disconnectRealtime() {
            connected = false
            realtimeSubscription?.close()
            realtimeSubscription = null
            realtimeRepository.disconnect()
        }

        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> connectRealtime()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> disconnectRealtime()
                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        connectRealtime()
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            disconnectRealtime()
        }
    }

    LaunchedEffect(realtimeServerId, homeState.loadState, realtimeRepository) {
        val content = (homeState.loadState as? ScreenLoadState.Content)?.data ?: return@LaunchedEffect
        (content.channels + content.directMessages).forEach { conversation ->
            realtimeRepository.joinChannel(conversation.channelId ?: ChannelId(conversation.threadId.value))
        }
    }

    LaunchedEffect(
        authState,
        serverListState,
        selectedServerOverride,
        channelRepository,
        inboxRepository,
        savedRepository,
        searchRepository,
        inboxFilter,
        searchQuery,
        homeRefreshRetryNonce
    ) {
        if (authState is AuthUiState.Authenticated) {
            val selectedServerId = selectedServerOverride
                ?: (serverListState as? ScreenLoadState.Content)
                ?.data
                ?.selectedServer
                ?.id
                ?: currentAuthSession?.currentServerId?.let(::ServerId)
                ?: return@LaunchedEffect

            val hasVisibleHomeContent = homeState.loadState is ScreenLoadState.Content
            val initialHomeContent = if (!hasVisibleHomeContent) {
                (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.let { server ->
                    HomeContent(
                        server = server,
                        currentMember = currentAuthSession?.user?.toMemberProfile()
                            ?: ((SlockStateSamples.homeContent.loadState as? ScreenLoadState.Content)
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
            if (initialHomeContent != null) {
                homeState = HomeUiState(
                    ScreenLoadState.Content(initialHomeContent),
                    selectedServerId = selectedServerId.value
                )
            }
            val shouldKeepHomeShell = hasVisibleHomeContent || initialHomeContent != null
            if (shouldKeepHomeShell) {
                homeRefreshInProgress = true
                homeRefreshError = null
            } else {
                homeState = HomeUiState(ScreenLoadState.Loading, selectedServerId = selectedServerId.value)
            }
            inboxState = InboxUiState(ScreenLoadState.Loading, selectedFilter = inboxFilter)
            savedState = InboxUiState(ScreenLoadState.Loading)
            searchState = if (searchQuery.isBlank()) {
                InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
            } else {
                InboxUiState(ScreenLoadState.Loading)
            }
            val homeResult = channelRepository.getHome(selectedServerId)
            if (homeResult is DataResult.Content || !shouldKeepHomeShell) {
                homeState = homeResult.toHomeUiState(selectedServerId.value)
            }
            if (homeResult is DataResult.Content || !shouldKeepHomeShell) {
                homeRefreshInProgress = false
                homeRefreshError = null
                homeRefreshAutoRetryAttempted = false
            } else if (!homeRefreshAutoRetryAttempted) {
                homeRefreshInProgress = true
                homeRefreshError = null
                homeRefreshAutoRetryAttempted = true
                homeRefreshRetryNonce += 1
            } else {
                homeRefreshInProgress = false
                homeRefreshError = homeResult.refreshErrorCode()
            }
            inboxState = inboxRepository.loadInitial(
                serverId = selectedServerId,
                filter = inboxFilter.toRepositoryFilter()
            ).toInboxUiState(inboxFilter)
            savedState = savedRepository.loadInitial(selectedServerId).toSavedInboxUiState()
            searchState = if (searchQuery.isBlank()) {
                InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
            } else {
                searchRepository.search(selectedServerId, searchQuery).toSearchInboxUiState()
            }
        }
    }

    LaunchedEffect(authState, destination, settingsDestination, settingsRepository) {
        if (authState is AuthUiState.Authenticated && destination == AppDestination.Settings) {
            when (settingsDestination) {
                SettingsDestination.Account -> {
                    settingsAccountState = ScreenLoadState.Loading
                    settingsAccountState = settingsRepository.getAccount().toScreenLoadState()
                }
                SettingsDestination.Sessions -> {
                    settingsSessionsState = ScreenLoadState.Loading
                    settingsSessionsState = settingsRepository.getSessions().toScreenLoadState()
                }
                SettingsDestination.Computers -> {
                    settingsComputersState = ScreenLoadState.Loading
                    settingsComputersState = settingsRepository.getComputers().toScreenLoadState()
                }
                SettingsDestination.Server -> {
                    settingsServerState = ScreenLoadState.Loading
                    settingsServerState = settingsRepository.getServerSettings().toScreenLoadState()
                }
                SettingsDestination.Mobile -> {
                    settingsMobileState = ScreenLoadState.Loading
                    settingsMobileState = settingsRepository.getMobileSettings().toScreenLoadState()
                }
                SettingsDestination.Home,
                SettingsDestination.Language -> Unit
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.AppBgLightBlue)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(SlockColor.BrandYellow)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (destination) {
                    AppDestination.Home -> HomeRouteScreen(
                        route = homeRoute,
                        previousRoute = previousHomeRoute,
                        onRouteChange = { route ->
                            previousHomeRoute = null
                            homeRoute = route
                        },
                        onOpenThreadFromInbox = {
                            previousHomeRoute = HomeRoute.Inbox
                            homeRoute = HomeRoute.Thread
                        },
                        threadTab = threadTab,
                        onThreadTabChange = { threadTab = it },
                        composerValue = composerValue,
                        onComposerValueChange = { composerValue = it },
                        composerAsTask = composerAsTask,
                        onComposerAsTaskChange = { composerAsTask = it },
                        localComposerMessages = localComposerMessages,
                        realtimeThreadMessages = realtimeThreadMessages,
                        serverListState = serverListState,
                        homeState = homeState,
                        inboxState = inboxState,
                        savedState = savedState,
                        searchState = searchState,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        homeRefreshInProgress = homeRefreshInProgress,
                        homeRefreshError = homeRefreshError,
                        onHomeRefresh = {
                            homeRefreshError = null
                            homeRefreshAutoRetryAttempted = false
                            if (!homeRefreshInProgress) {
                                homeRefreshInProgress = true
                                homeRefreshRetryNonce += 1
                            }
                        },
                        serverSwitcherExpanded = serverSwitcherExpanded,
                        onServerSwitcherExpandedChange = { serverSwitcherExpanded = it },
                        onServerSelected = { id ->
                            serverSwitcherExpanded = false
                            coroutineScope.launch {
                                val serverId = ServerId(id)
                                selectedServerOverride = serverId
                                serverListState = serverListState.selectServerOptimistically(serverId)
                                homeRefreshError = null
                                homeRefreshAutoRetryAttempted = false
                                if (homeState.loadState is ScreenLoadState.Content) {
                                    homeRefreshInProgress = true
                                } else {
                                    homeState = HomeUiState(ScreenLoadState.Loading, selectedServerId = id)
                                }
                                currentAuthSession = authSessionStore.readSession()
                                inboxState = InboxUiState(ScreenLoadState.Loading, selectedFilter = inboxFilter)
                                savedState = InboxUiState(ScreenLoadState.Loading)
                                searchState = if (searchQuery.isBlank()) {
                                    InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
                                } else {
                                    InboxUiState(ScreenLoadState.Loading)
                                }
                                val selectedServerListState = serverRepository.selectServer(serverId).toScreenLoadState()
                                serverListState = selectedServerListState
                                currentAuthSession = authSessionStore.readSession()
                                if (selectedServerListState !is ScreenLoadState.Content) {
                                    homeRefreshInProgress = false
                                    homeRefreshError = selectedServerListState.refreshErrorCode()
                                }
                            }
                        },
                        onCreateServerClick = {
                            serverSwitcherExpanded = false
                            createServerVisible = true
                        },
                        onSend = {
                            val body = composerValue.trim()
                            if (body.isNotEmpty()) {
                                localComposerMessages = localComposerMessages + body
                                composerValue = ""
                            }
                        },
                        onInboxFilterSelected = { filter -> inboxFilter = filter },
                        onLoadInboxMore = { offset, filter ->
                            val selectedServerId = selectedServerOverride
                                ?: (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.id
                                ?: currentAuthSession?.currentServerId?.let(::ServerId)
                            if (selectedServerId != null && offset != null) {
                                coroutineScope.launch {
                                    inboxState = inboxState.copy(loadingMore = true)
                                    inboxState = mergeInboxPage(
                                        current = inboxState,
                                        next = inboxRepository.loadMore(
                                            serverId = selectedServerId,
                                            filter = filter.toRepositoryFilter(),
                                            offset = offset
                                        ).toInboxUiState(filter)
                                    )
                                }
                            }
                        },
                        onLoadSavedMore = { offset ->
                            val selectedServerId = selectedServerOverride
                                ?: (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.id
                                ?: currentAuthSession?.currentServerId?.let(::ServerId)
                            if (selectedServerId != null && offset != null) {
                                coroutineScope.launch {
                                    savedState = savedState.copy(loadingMore = true)
                                    savedState = mergeInboxPage(
                                        current = savedState,
                                        next = savedRepository.loadMore(
                                            serverId = selectedServerId,
                                            offset = offset
                                        ).toSavedInboxUiState()
                                    )
                                }
                            }
                        },
                        onLoadSearchMore = { offset ->
                            val selectedServerId = selectedServerOverride
                                ?: (serverListState as? ScreenLoadState.Content)?.data?.selectedServer?.id
                                ?: currentAuthSession?.currentServerId?.let(::ServerId)
                            if (selectedServerId != null && offset != null && searchQuery.isNotBlank()) {
                                coroutineScope.launch {
                                    searchState = searchState.copy(loadingMore = true)
                                    searchState = mergeInboxPage(
                                        current = searchState,
                                        next = searchRepository.search(
                                            serverId = selectedServerId,
                                            query = searchQuery,
                                            offset = offset
                                        ).toSearchInboxUiState()
                                    )
                                }
                            }
                        }
                    )
                    AppDestination.Tasks -> TasksScreen(state = SlockStateSamples.tasksContent)
                    AppDestination.Members -> MembersRouteScreen(
                        state = SlockStateSamples.membersContent,
                        onBack = { destination = AppDestination.Home },
                        onDetailVisibleChange = { membersDetailVisible = it }
                    )
                    AppDestination.Settings -> SettingsScreen(
                        state = SlockStateSamples.settingsContent,
                        destination = settingsDestination,
                        onDestinationChange = { settingsDestination = it },
                        accountState = settingsAccountState,
                        sessionsState = settingsSessionsState,
                        computersState = settingsComputersState,
                        serverState = settingsServerState,
                        mobileState = settingsMobileState,
                        notificationPermissionGranted = notificationPermissionGranted,
                        notificationPermissionLauncherAvailable = context.findActivity() != null,
                        localeSnapshot = localeSnapshot,
                        onLocaleSelected = onLocaleSelected,
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                context.findActivity()?.let { activity ->
                                    ActivityCompat.requestPermissions(
                                        activity,
                                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                        SettingsNotificationPermissionRequestCode
                                    )
                                }
                            } else {
                                notificationPermissionGranted = true
                            }
                        },
                        onSendTestNotification = {
                            notificationPermissionGranted = context.hasNotificationPermission()
                            if (notificationPermissionGranted) {
                                context.showSettingsTestNotification()
                            }
                        },
                        onOpenNotificationSettings = {
                            context.openNotificationSettings()
                        },
                        onRevokeSession = { sessionId ->
                            coroutineScope.launch {
                                settingsSessionsState = ScreenLoadState.Loading
                                when (val result = settingsRepository.revokeSession(sessionId)) {
                                    is DataResult.Content -> {
                                        settingsSessionsState = settingsRepository.getSessions().toScreenLoadState()
                                    }
                                    is DataResult.Empty -> {
                                        settingsSessionsState = ScreenLoadState.Empty(result.reason)
                                    }
                                    is DataResult.Error -> {
                                        settingsSessionsState = ScreenLoadState.Error(result.error)
                                    }
                                    is DataResult.NoPermission -> {
                                        settingsSessionsState = ScreenLoadState.NoPermission(result.blocker)
                                    }
                                }
                            }
                        }
                    )
                }
            }
            if (showBottomBar) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceWhite)
                        .navigationBarsPadding()
                ) {
                    SlockBottomTabBar(
                        items = appBottomTabs(),
                        selectedKey = destination.key,
                        onSelected = { key ->
                            destination = AppDestination.entries.first { it.key == key }
                            homeRoute = HomeRoute.Shell
                            previousHomeRoute = null
                            membersDetailVisible = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = -SlockStroke.Default)
                    )
                }
            }
        }
        if (createServerVisible) {
            val trimmedCreateServerName = createServerName.trim()
            val trimmedCreateServerSlug = createServerSlug.trim()
            val createServerSubmitEnabled = trimmedCreateServerName.isNotBlank() && !createServerSubmitting
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
                errorText = createServerError?.let { stringResource(R.string.home_create_server_error, it) },
                primaryAction = SlockModalAction(
                    text = stringResource(R.string.home_create_server_primary),
                    enabled = createServerSubmitEnabled,
                    loading = createServerSubmitting,
                    onClick = {
                        if (!createServerSubmitEnabled) return@SlockModalAction
                        coroutineScope.launch {
                            createServerSubmitting = true
                            createServerError = null
                            when (val result = serverRepository.createServer(
                                CreateServerRequest(
                                    name = trimmedCreateServerName,
                                    slug = trimmedCreateServerSlug.ifBlank { null }
                                )
                            )) {
                                is DataResult.Content -> {
                                    serverListState = result.toScreenLoadState()
                                    val selectedServerId = result.data.selectedServer?.id
                                    selectedServerOverride = selectedServerId
                                    currentAuthSession = authSessionStore.readSession()
                                    if (selectedServerId != null) {
                                        if (homeState.loadState is ScreenLoadState.Content) {
                                            homeRefreshInProgress = true
                                        } else {
                                            homeState = HomeUiState(
                                                ScreenLoadState.Loading,
                                                selectedServerId = selectedServerId.value
                                            )
                                        }
                                        inboxState = InboxUiState(ScreenLoadState.Loading, selectedFilter = inboxFilter)
                                        savedState = InboxUiState(ScreenLoadState.Loading)
                                        searchState = if (searchQuery.isBlank()) {
                                            InboxUiState(ScreenLoadState.Empty(EmptyReason.NoSearchResults))
                                        } else {
                                            InboxUiState(ScreenLoadState.Loading)
                                        }
                                    }
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
                secondaryAction = SlockModalAction(
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
                        placeholder = stringResource(R.string.home_create_server_name_placeholder),
                        required = true,
                        enabled = !createServerSubmitting
                    )
                    SlockModalTextField(
                        value = createServerSlug,
                        onValueChange = {
                            createServerSlug = it
                                .lowercase()
                                .filter { char -> char.isLetterOrDigit() || char == '-' }
                            createServerError = null
                        },
                        label = stringResource(R.string.home_create_server_slug_label),
                        placeholder = stringResource(R.string.home_create_server_slug_placeholder),
                        optional = true,
                        enabled = !createServerSubmitting
                    )
                }
            }
        }
    }
}

private enum class AuthRoute {
    Login,
    ForgotPassword,
    CreateAccount
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
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceCream),
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
        AuthRoute.Login -> LoginScreen(
            onLogin = onLogin,
            onForgotPassword = { onRouteChange(AuthRoute.ForgotPassword) },
            onCreateAccount = { onRouteChange(AuthRoute.CreateAccount) },
            onAuthProviderUnavailable = onAuthProviderUnavailable,
            isSubmitting = authState is AuthUiState.Submitting || authState is AuthUiState.Restoring,
            errorMessage = (authState as? AuthUiState.SignedOut)?.errorMessage,
            modifier = modifier
        )
        AuthRoute.ForgotPassword -> ForgotPasswordScreen(
            onBackToSignIn = { onRouteChange(AuthRoute.Login) },
            modifier = modifier
        )
        AuthRoute.CreateAccount -> CreateAccountScreen(
            onCreateAccount = { _, _, _ -> },
            onBackToSignIn = { onRouteChange(AuthRoute.Login) },
            modifier = modifier
        )
    }
}

private enum class AppDestination(
    val key: String,
    val label: String
) {
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
    onOpenThreadFromInbox: () -> Unit,
    threadTab: ThreadTab,
    onThreadTabChange: (ThreadTab) -> Unit,
    composerValue: String,
    onComposerValueChange: (String) -> Unit,
    composerAsTask: Boolean,
    onComposerAsTaskChange: (Boolean) -> Unit,
    localComposerMessages: List<String>,
    realtimeThreadMessages: List<Message>,
    serverListState: ScreenLoadState<ServerListSnapshot>,
    homeState: HomeUiState,
    inboxState: InboxUiState,
    savedState: InboxUiState,
    searchState: InboxUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    homeRefreshInProgress: Boolean,
    homeRefreshError: String?,
    onHomeRefresh: () -> Unit,
    serverSwitcherExpanded: Boolean,
    onServerSwitcherExpandedChange: (Boolean) -> Unit,
    onServerSelected: (String) -> Unit,
    onCreateServerClick: () -> Unit,
    onSend: () -> Unit,
    onInboxFilterSelected: (InboxFilter) -> Unit,
    onLoadInboxMore: (Int?, InboxFilter) -> Unit,
    onLoadSavedMore: (Int?) -> Unit,
    onLoadSearchMore: (Int?) -> Unit
) {
    if (route != HomeRoute.Shell) {
        BackHandler { onRouteChange(previousRoute ?: HomeRoute.Shell) }
    }
    when (route) {
        HomeRoute.Shell -> HomeShellScreen(
            onOpenThread = { onRouteChange(HomeRoute.Thread) },
            onOpenInbox = { onRouteChange(HomeRoute.Inbox) },
            onOpenSaved = { onRouteChange(HomeRoute.Saved) },
            onOpenSearch = { onRouteChange(HomeRoute.Search) },
            serverListState = serverListState,
            homeState = homeState,
            homeRefreshInProgress = homeRefreshInProgress,
            homeRefreshError = homeRefreshError,
            onHomeRefresh = onHomeRefresh,
            serverSwitcherExpanded = serverSwitcherExpanded,
            onServerSwitcherExpandedChange = onServerSwitcherExpandedChange,
            onServerSelected = onServerSelected,
            onCreateServerClick = onCreateServerClick
        )
        HomeRoute.Thread -> {
            val baseThreadState = SlockStateSamples.threadContent
            val threadLoadState = when (val loadState = baseThreadState.loadState) {
                is ScreenLoadState.Content -> ScreenLoadState.Content(
                    loadState.data.copy(
                        messages = (
                            loadState.data.messages +
                                realtimeThreadMessages +
                                localComposerMessages.toLocalThreadMessages(loadState.data)
                            ).distinctBy { it.id }
                    )
                )
                else -> loadState
            }
            ThreadScreen(
            state = baseThreadState.copy(
                loadState = threadLoadState,
                selectedTab = threadTab,
                composerState = baseThreadState.composerState.copy(
                    value = composerValue,
                    asTask = composerAsTask,
                    status = if (composerValue.isBlank()) {
                        ComposerStatus.IdleEmpty
                    } else {
                        ComposerStatus.Ready
                    }
                )
            ),
            onBack = { onRouteChange(previousRoute ?: HomeRoute.Shell) },
            onModeSelected = onThreadTabChange,
            onComposerChange = onComposerValueChange,
            onAsTaskChange = onComposerAsTaskChange,
            onSend = onSend
        )
        }
        HomeRoute.Inbox -> InboxScreen(
            state = inboxState,
            onBack = { onRouteChange(HomeRoute.Shell) },
            onOpenThread = { onOpenThreadFromInbox() },
            onFilterSelected = onInboxFilterSelected,
            onLoadMore = {
                onLoadInboxMore(inboxState.nextOffset, inboxState.selectedFilter)
            }
        )
        HomeRoute.Saved -> InboxScreen(
            state = savedState,
            onBack = { onRouteChange(HomeRoute.Shell) },
            onOpenThread = { onOpenThreadFromInbox() },
            onLoadMore = { onLoadSavedMore(savedState.nextOffset) }
        )
        HomeRoute.Search -> SearchResultsScreen(
            state = searchState,
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            onBack = { onRouteChange(HomeRoute.Shell) },
            onOpenThread = { onOpenThreadFromInbox() },
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
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
            modifier = Modifier
                .fillMaxWidth()
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

private val localComposerAuthor = MemberProfile(
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
    Saved,
    Search
}

@Composable
private fun HomeShellScreen(
    onOpenThread: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenSearch: () -> Unit,
    serverListState: ScreenLoadState<ServerListSnapshot>,
    homeState: HomeUiState,
    homeRefreshInProgress: Boolean,
    homeRefreshError: String?,
    onHomeRefresh: () -> Unit,
    serverSwitcherExpanded: Boolean,
    onServerSwitcherExpandedChange: (Boolean) -> Unit,
    onServerSelected: (String) -> Unit,
    onCreateServerClick: () -> Unit
) {
    val content = (homeState.loadState as? ScreenLoadState.Content)?.data
        ?: fallbackHomeContent(
            serverListState = serverListState,
            selectedServerId = homeState.selectedServerId
        )
    HomeStructuralContent(
        onOpenThread = onOpenThread,
        onOpenInbox = onOpenInbox,
        onOpenSaved = onOpenSaved,
        onOpenSearch = onOpenSearch,
        serverListState = serverListState,
        content = content,
        homeRefreshInProgress = homeRefreshInProgress || homeState.loadState == ScreenLoadState.Loading,
        homeRefreshError = homeRefreshError,
        onHomeRefresh = onHomeRefresh,
        serverSwitcherExpanded = serverSwitcherExpanded,
        onServerSwitcherExpandedChange = onServerSwitcherExpandedChange,
        onServerSelected = onServerSelected,
        onCreateServerClick = onCreateServerClick
    )
}

@Composable
private fun fallbackHomeContent(
    serverListState: ScreenLoadState<ServerListSnapshot>,
    selectedServerId: String?
): HomeContent {
    val selectedServer = (serverListState as? ScreenLoadState.Content)?.data?.let { snapshot ->
        snapshot.selectedServer ?: snapshot.servers.firstOrNull()
    }
    val server = selectedServer ?: ServerSummary(
        id = ServerId(selectedServerId ?: "pending-server"),
        name = when (serverListState) {
            ScreenLoadState.Loading -> stringResource(R.string.home_server_loading)
            is ScreenLoadState.Empty -> stringResource(R.string.home_server_empty)
            is ScreenLoadState.Error -> stringResource(R.string.home_server_error)
            is ScreenLoadState.NoPermission -> stringResource(R.string.home_server_auth_required)
            is ScreenLoadState.Content -> stringResource(R.string.home_server_fallback)
        }
    )
    return HomeContent(
        server = server,
        currentMember = localComposerAuthor,
        channels = emptyList(),
        directMessages = emptyList(),
        unreadTotal = 0,
        inboxUnreadCount = 0,
        savedUnreadCount = 0
    )
}

@Composable
private fun HomeStructuralContent(
    onOpenThread: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenSearch: () -> Unit,
    serverListState: ScreenLoadState<ServerListSnapshot>,
    content: HomeContent,
    homeRefreshInProgress: Boolean,
    homeRefreshError: String?,
    onHomeRefresh: () -> Unit,
    serverSwitcherExpanded: Boolean,
    onServerSwitcherExpandedChange: (Boolean) -> Unit,
    onServerSelected: (String) -> Unit,
    onCreateServerClick: () -> Unit
) {
    val serverSnapshot = (serverListState as? ScreenLoadState.Content)?.data
    val servers = serverSnapshot?.servers?.map { it.toSwitcherItem() }.orEmpty()
    val selectedServer = serverSnapshot?.selectedServer ?: serverSnapshot?.servers?.firstOrNull()
    val currentServerName = selectedServer?.name ?: when (serverListState) {
        ScreenLoadState.Loading -> stringResource(R.string.home_server_loading)
        is ScreenLoadState.Empty -> stringResource(R.string.home_server_empty)
        is ScreenLoadState.Error -> stringResource(R.string.home_server_error)
        is ScreenLoadState.NoPermission -> stringResource(R.string.home_server_auth_required)
        is ScreenLoadState.Content -> stringResource(R.string.home_server_fallback)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        SlockStructuralHomeShell(
            currentServerName = currentServerName,
            currentServerId = selectedServer?.id?.value.orEmpty(),
            servers = servers,
            channels = content.channels.map { it.toHomeConversationItem() },
            directMessages = content.directMessages.map { it.toHomeConversationItem() },
            selectedChannelId = content.channels.firstOrNull()?.threadId?.value,
            inboxUnreadCount = content.unreadTotal,
            savedUnreadCount = content.savedUnreadCount,
            serverSwitcherExpanded = serverSwitcherExpanded,
            contentRefreshing = homeRefreshInProgress,
            contentRefreshEnabled = homeRefreshError == null,
            showBottomBar = false,
            outerPadding = 0.dp,
            onContentRefresh = onHomeRefresh,
            onServerBadgeClick = {
                if (!homeRefreshInProgress && homeRefreshError == null) {
                    onServerSwitcherExpandedChange(!serverSwitcherExpanded)
                }
            },
            onServerSelected = { id ->
                if (!homeRefreshInProgress && homeRefreshError == null) {
                    onServerSelected(id)
                }
            },
            onCreateServerClick = {
                if (!homeRefreshInProgress && homeRefreshError == null) {
                    onCreateServerClick()
                }
            },
            onInboxClick = {
                if (!homeRefreshInProgress && homeRefreshError == null) {
                    onOpenInbox()
                }
            },
            onSavedClick = {
                if (!homeRefreshInProgress && homeRefreshError == null) {
                    onOpenSaved()
                }
            },
            onSearchClick = {
                if (!homeRefreshInProgress && homeRefreshError == null) {
                    onOpenSearch()
                }
            },
            onChannelClick = {
                if (!homeRefreshInProgress && homeRefreshError == null) {
                    onOpenThread()
                }
            }
        )
        if (homeRefreshError != null) {
            HomeRefreshErrorStrip(
                errorCode = homeRefreshError,
                onRetry = onHomeRefresh,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
            )
        }
    }
}

@Composable
private fun HomeRefreshErrorStrip(
    errorCode: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
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
            text = stringResource(
                R.string.home_refresh_failed_detail,
                errorCode ?: stringResource(R.string.common_error)
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

private fun ai.slock.android.core.model.ServerSummary.toSwitcherItem(): SlockServerSwitcherItem =
    SlockServerSwitcherItem(
        id = id.value,
        name = name,
        slug = slug ?: description?.removePrefix("/") ?: id.value
    )

private fun ScreenLoadState<ServerListSnapshot>.selectServerOptimistically(
    serverId: ServerId
): ScreenLoadState<ServerListSnapshot> =
    when (this) {
        is ScreenLoadState.Content -> {
            val selectedServer = data.servers.firstOrNull { it.id == serverId } ?: data.selectedServer
            ScreenLoadState.Content(data.copy(selectedServer = selectedServer))
        }
        ScreenLoadState.Loading,
        is ScreenLoadState.Empty,
        is ScreenLoadState.Error,
        is ScreenLoadState.NoPermission -> this
    }

private fun ScreenLoadState<ServerListSnapshot>.refreshErrorCode(): String? =
    when (this) {
        is ScreenLoadState.Empty -> reason.name
        is ScreenLoadState.Error -> error.code
        is ScreenLoadState.NoPermission -> blocker.code
        ScreenLoadState.Loading,
        is ScreenLoadState.Content -> null
    }

private fun ScreenLoadState<ServerListSnapshot>.applyUnreadSummary(
    snapshot: ServerUnreadSummarySnapshot
): ScreenLoadState<ServerListSnapshot> =
    when (this) {
        is ScreenLoadState.Content -> {
            val unreadByServerId = snapshot.summaries.associateBy { it.serverId }
            val updatedServers = data.servers.map { server ->
                val unread = unreadByServerId[server.id]?.totalUnreadCount
                if (unread == null) server else server.copy(unreadCount = unread)
            }
            val updatedSelectedServer = data.selectedServer?.let { selected ->
                val unread = unreadByServerId[selected.id]?.totalUnreadCount
                    ?: snapshot.selectedServerSummary?.takeIf { it.serverId == selected.id }?.totalUnreadCount
                if (unread == null) selected else selected.copy(unreadCount = unread)
            }
            ScreenLoadState.Content(
                data.copy(
                    servers = updatedServers,
                    selectedServer = updatedSelectedServer
                )
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
    val unread = snapshot.summaryFor(content.server.id)?.totalUnreadCount ?: snapshot.totalUnreadCount
    val inboxUnread = snapshot.summaryFor(content.server.id)?.inboxUnreadCount ?: snapshot.inboxUnreadCount
    val savedUnread = snapshot.summaryFor(content.server.id)?.savedUnreadCount ?: snapshot.savedUnreadCount
    return copy(
        loadState = ScreenLoadState.Content(
            content.copy(
                server = content.server.copy(unreadCount = unread),
                unreadTotal = unread,
                inboxUnreadCount = inboxUnread,
                savedUnreadCount = savedUnread
            )
        )
    )
}

private fun InboxUiState.applyUnreadSummary(snapshot: ServerUnreadSummarySnapshot): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val summary = snapshot.summaryFor(content.server.id) ?: snapshot.selectedServerSummary
    val totalUnread = summary?.inboxUnreadCount ?: snapshot.inboxUnreadCount
    val serverUnread = summary?.totalUnreadCount ?: snapshot.totalUnreadCount
    return copy(
        loadState = ScreenLoadState.Content(
            content.copy(
                server = content.server.copy(unreadCount = serverUnread),
                totalUnreadCount = totalUnread
            )
        )
    )
}

private fun InboxUiState.applySavedUnreadSummary(snapshot: ServerUnreadSummarySnapshot): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val summary = snapshot.summaryFor(content.server.id) ?: snapshot.selectedServerSummary
    val savedUnread = summary?.savedUnreadCount ?: snapshot.savedUnreadCount
    val serverUnread = summary?.totalUnreadCount ?: snapshot.totalUnreadCount
    return copy(
        loadState = ScreenLoadState.Content(
            content.copy(
                server = content.server.copy(unreadCount = serverUnread),
                totalUnreadCount = savedUnread
            )
        )
    )
}

private fun ServerUnreadSummarySnapshot.summaryFor(serverId: ServerId) =
    summaries.firstOrNull { it.serverId == serverId }
        ?: selectedServerSummary?.takeIf { it.serverId == serverId }

private fun DataResult<HomeSnapshot>.refreshErrorCode(): String =
    when (this) {
        is DataResult.Empty -> reason.name
        is DataResult.Error -> error.code
        is DataResult.NoPermission -> blocker.code
        is DataResult.Content -> ""
    }

private fun ConversationSummary.toHomeConversationItem(): ai.slock.android.ui.components.SlockHomeConversationItem =
    ai.slock.android.ui.components.SlockHomeConversationItem(
        id = threadId.value,
        name = name,
        unreadCount = unreadCount
    )

private fun Long.updateFrom(event: RealtimeEvent): Long =
    when (event) {
        is RealtimeEvent.Heartbeat -> maxOf(this, event.seq)
        is RealtimeEvent.MessageNew -> maxOf(this, event.message.seq ?: 0L)
        is RealtimeEvent.MessageUpdated -> maxOf(this, event.message.seq ?: 0L)
        is RealtimeEvent.SyncResumeResponse -> maxOf(this, event.currentSeq)
        else -> this
    }

private fun HomeUiState.applyRealtimeEvent(event: RealtimeEvent): HomeUiState =
    when (event) {
        is RealtimeEvent.MessageNew -> applyRealtimeMessages(listOf(event.message), incrementUnread = true)
        is RealtimeEvent.MessageUpdated -> applyRealtimeMessages(listOf(event.message), incrementUnread = false)
        is RealtimeEvent.SyncResumeResponse -> applyRealtimeMessages(event.messages, incrementUnread = true)
        else -> this
    }

private fun HomeUiState.applyRealtimeMessages(messages: List<Message>, incrementUnread: Boolean): HomeUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    var unreadDelta = 0
    fun ConversationSummary.merge(message: Message): ConversationSummary {
        val matches = threadId.value == message.threadId.value || channelId?.value == message.channelId?.value
        if (!matches) return this
        unreadDelta += if (incrementUnread && lastMessageId != message.id) 1 else 0
        return copy(
            unreadCount = unreadCount + if (incrementUnread && lastMessageId != message.id) 1 else 0,
            lastMessageId = message.id,
            lastMessagePreview = message.body,
            lastMessageAt = message.timestampLabel,
            lastMessageSenderName = message.author.displayName
        )
    }
    val content = contentState.data
    val updatedChannels = content.channels.map { conversation ->
        messages.fold(conversation) { current, message -> current.merge(message) }
    }
    val updatedDms = content.directMessages.map { conversation ->
        messages.fold(conversation) { current, message -> current.merge(message) }
    }
    return copy(
        loadState = ScreenLoadState.Content(
            content.copy(
                channels = updatedChannels,
                directMessages = updatedDms,
                unreadTotal = content.unreadTotal + unreadDelta
            )
        )
    )
}

private fun InboxUiState.applyRealtimeEvent(event: RealtimeEvent, query: String? = null): InboxUiState =
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
        is RealtimeEvent.SyncResumeResponse -> event.messages.fold(this) { state, message -> state.updateExistingMessage(message) }
        else -> this
    }

private fun InboxUiState.applyRealtimeMessages(messages: List<Message>, query: String?): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val filteredMessages = if (query.isNullOrBlank()) {
        messages
    } else {
        messages.filter { it.body.contains(query, ignoreCase = true) }
    }
    if (filteredMessages.isEmpty()) return this
    val content = contentState.data
    val items = (filteredMessages.map { it.toInboxItem() } + content.items)
        .distinctBy { it.key }
    return copy(
        loadState = ScreenLoadState.Content(
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
    val updatedItems = content.items.map { item ->
        if (item.lastMessageId == message.id || item.latestActivityMessageId == message.id) {
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

private fun InboxUiState.applyThreadUpdated(event: RealtimeEvent.ThreadUpdated): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val updatedItems = content.items.map { item ->
        if (item.parentMessageId == event.parentMessageId || item.threadChannelId == event.threadChannelId) {
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
        is RealtimeEvent.MessageUpdated -> map { if (it.id == event.message.id) event.message else it }
        is RealtimeEvent.SyncResumeResponse -> (this + event.messages).distinctBy { it.id }
        else -> this
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

private fun DataResult<HomeSnapshot>.toHomeUiState(selectedServerId: String?): HomeUiState =
    HomeUiState(
        loadState = when (this) {
            is DataResult.Content -> ScreenLoadState.Content(
                HomeContent(
                    server = data.server,
                    currentMember = data.currentMember,
                    channels = data.channels,
                    directMessages = data.directMessages,
                    unreadTotal = data.unreadTotal
                )
            )
            is DataResult.Empty -> ScreenLoadState.Empty(reason)
            is DataResult.Error -> ScreenLoadState.Error(error)
            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
        },
        selectedServerId = selectedServerId
    )

private fun DataResult<InboxSnapshot>.toInboxUiState(selectedFilter: InboxFilter): InboxUiState =
    InboxUiState(
        loadState = when (this) {
            is DataResult.Content -> ScreenLoadState.Content(
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
        nextOffset = (this as? DataResult.Content)?.data?.nextOffset
    )

private fun DataResult<SavedSnapshot>.toSavedInboxUiState(): InboxUiState =
    InboxUiState(
        loadState = when (this) {
            is DataResult.Content -> ScreenLoadState.Content(
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
        nextOffset = (this as? DataResult.Content)?.data?.nextOffset
    )

private fun DataResult<SearchSnapshot>.toSearchInboxUiState(): InboxUiState =
    InboxUiState(
        loadState = when (this) {
            is DataResult.Content -> ScreenLoadState.Content(
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
        nextOffset = (this as? DataResult.Content)?.data?.nextOffset
    )

private fun mergeInboxPage(current: InboxUiState, next: InboxUiState): InboxUiState {
    val currentContent = current.loadState as? ScreenLoadState.Content
    val nextContent = next.loadState as? ScreenLoadState.Content
    if (currentContent == null || nextContent == null) {
        return next.copy(loadingMore = false)
    }

    val mergedItems = (currentContent.data.items + nextContent.data.items)
        .distinctBy { it.key }

    return next.copy(
        loadState = ScreenLoadState.Content(
            nextContent.data.copy(
                items = mergedItems,
                totalCount = nextContent.data.totalCount.coerceAtLeast(mergedItems.size)
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

@Composable
private fun SettingsScreen(
    state: ai.slock.android.feature.settings.SettingsUiState,
    destination: SettingsDestination,
    onDestinationChange: (SettingsDestination) -> Unit,
    accountState: ScreenLoadState<AccountSettingsSnapshot>,
    sessionsState: ScreenLoadState<SessionSettingsSnapshot>,
    computersState: ScreenLoadState<SettingsComputersSnapshot>,
    serverState: ScreenLoadState<SettingsServerSnapshot>,
    mobileState: ScreenLoadState<SettingsMobileSnapshot>,
    notificationPermissionGranted: Boolean,
    notificationPermissionLauncherAvailable: Boolean,
    localeSnapshot: LocaleSnapshot,
    onLocaleSelected: (AppLocale) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSendTestNotification: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRevokeSession: (String) -> Unit
) {
    if (destination != SettingsDestination.Home) {
        BackHandler { onDestinationChange(SettingsDestination.Home) }
    }

    LoadStateScreen(
        state = state.loadState,
        title = stringResource(R.string.settings_home_title),
        emptyTitle = stringResource(R.string.settings_home_title),
        emptyDetail = stringResource(R.string.common_preparing_local_state),
        contentPadding = 0.dp
    ) { content ->
        when (destination) {
            SettingsDestination.Home -> SettingsHomeScreen(
                title = stringResource(R.string.settings_home_title),
                subtitle = content.settings.server.name,
                items = settingsHomeItems(content, localeSnapshot),
                onItemClick = { key ->
                    onDestinationChange(
                        when (key) {
                            SettingsDestination.Account.key -> SettingsDestination.Account
                            SettingsDestination.Language.key -> SettingsDestination.Language
                            SettingsDestination.Sessions.key -> SettingsDestination.Sessions
                            SettingsDestination.Computers.key -> SettingsDestination.Computers
                            SettingsDestination.Server.key -> SettingsDestination.Server
                            SettingsDestination.Mobile.key -> SettingsDestination.Mobile
                            else -> SettingsDestination.Home
                        }
                    )
                }
            )
            SettingsDestination.Account -> SettingsSecondaryScreen(
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
                        connectedAccounts = account.connectedAccounts.map { it.toSettingsConnectedAccount() },
                        showOuterBorder = false
                    )
                }
            }
            SettingsDestination.Sessions -> SettingsSecondaryScreen(
                title = stringResource(R.string.settings_sessions_title),
                onBack = { onDestinationChange(SettingsDestination.Home) }
            ) {
                SettingsDataStateContent(
                    state = sessionsState,
                    emptyTitle = stringResource(R.string.settings_sessions_title),
                    emptyDetail = stringResource(R.string.common_preparing_local_state)
                ) { sessions ->
                    SettingsSessionsDangerScreen(
                        sessions = sessions.sessions.map { it.toSettingsSessionItem() },
                        dangerItems = settingsDangerItems(),
                        showOuterBorder = false,
                        onRevokeSession = onRevokeSession
                    )
                }
            }
            SettingsDestination.Computers -> SettingsSecondaryScreen(
                title = stringResource(R.string.settings_computers_title),
                onBack = { onDestinationChange(SettingsDestination.Home) }
            ) {
                SettingsDataStateContent(
                    state = computersState,
                    emptyTitle = stringResource(R.string.settings_computers_title),
                    emptyDetail = stringResource(R.string.settings_unavailable_detail)
                ) { computers ->
                    SettingsComputersScreen(computers)
                }
            }
            SettingsDestination.Server -> SettingsSecondaryScreen(
                title = stringResource(R.string.settings_server_title),
                onBack = { onDestinationChange(SettingsDestination.Home) }
            ) {
                SettingsDataStateContent(
                    state = serverState,
                    emptyTitle = stringResource(R.string.settings_server_title),
                    emptyDetail = stringResource(R.string.settings_unavailable_detail)
                ) { server ->
                    SettingsServerManagementScreen(server)
                }
            }
            SettingsDestination.Mobile -> SettingsSecondaryScreen(
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
                        notificationPermissionAvailable = notificationPermissionLauncherAvailable,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onSendTest = onSendTestNotification,
                        onOpenNotificationSettings = onOpenNotificationSettings
                    )
                }
            }
            SettingsDestination.Language -> LanguageSettingsScreen(
                localeSnapshot = localeSnapshot,
                onLocaleSelected = onLocaleSelected,
                onBack = { onDestinationChange(SettingsDestination.Home) }
            )
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
        ScreenLoadState.Loading -> SlockEmptyState(
            title = stringResource(R.string.common_loading),
            detail = stringResource(R.string.common_preparing_local_state)
        )
        is ScreenLoadState.Content -> content(state.data)
        is ScreenLoadState.Empty -> SlockEmptyState(
            title = emptyTitle,
            detail = emptyDetail
        )
        is ScreenLoadState.Error -> SlockErrorState(
            title = stringResource(R.string.common_failed_to_load, emptyTitle),
            detail = "Error code: ${state.error.code}"
        )
        is ScreenLoadState.NoPermission -> SlockEmptyState(
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        SettingsDetailHeader(title = title, onBack = onBack)
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

private enum class SettingsDestination(val key: String) {
    Home("home"),
    Account("account"),
    Sessions("sessions"),
    Computers("computers"),
    Server("server"),
    Mobile("mobile"),
    Language("language")
}

@Composable
private fun <T> LoadStateScreen(
    state: ScreenLoadState<T>,
    title: String,
    emptyTitle: String,
    emptyDetail: String,
    contentPadding: androidx.compose.ui.unit.Dp = SlockSpacing.Md,
    content: @Composable (T) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
            .padding(contentPadding)
    ) {
        when (state) {
            ScreenLoadState.Loading -> {
                SlockPageHeader(title = title, subtitle = stringResource(R.string.common_loading_sample_state))
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
                    detail = "Error code: ${state.error.code}"
                )
            }
            is ScreenLoadState.NoPermission -> {
                SlockPageHeader(title = title, subtitle = stringResource(R.string.common_no_permission))
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(
                if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite,
                RoundedCornerShape(SlockRadius.Xs)
            )
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
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
    Text(
        text = text.uppercase(),
        style = SlockType.SectionLabel,
        color = SlockColor.TextBlack
    )
}

@Composable
private fun CountBadge(text: String) {
    Text(
        text = text,
        style = SlockType.Caption,
        color = SlockColor.SurfaceWhite,
        modifier = Modifier
            .background(SlockColor.AccentPink)
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
    val currentLanguage = when (localeSnapshot.current) {
        AppLocale.English -> stringResource(R.string.settings_language_current_english)
        AppLocale.SimplifiedChinese -> stringResource(R.string.settings_language_current_chinese)
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
            key = SettingsDestination.Sessions.key,
            title = stringResource(R.string.settings_sessions_title),
            subtitle = stringResource(R.string.settings_sessions_subtitle),
            icon = SlockIcons.Settings,
            value = if (content.settings.notificationEnabled) {
                stringResource(R.string.settings_notifications_on)
            } else {
                stringResource(R.string.settings_notifications_off)
            }
        ),
        SettingsHomeItem(
            key = SettingsDestination.Computers.key,
            title = stringResource(R.string.settings_computers_title),
            subtitle = stringResource(R.string.settings_computers_subtitle),
            icon = SlockIcons.Monitor,
            value = stringResource(R.string.settings_unavailable_value)
        ),
        SettingsHomeItem(
            key = SettingsDestination.Server.key,
            title = stringResource(R.string.settings_server_title),
            subtitle = stringResource(R.string.settings_server_subtitle),
            icon = SlockIcons.Users,
            value = stringResource(R.string.settings_unavailable_value)
        ),
        SettingsHomeItem(
            key = SettingsDestination.Mobile.key,
            title = stringResource(R.string.settings_mobile_title),
            subtitle = stringResource(R.string.settings_mobile_subtitle),
            icon = SlockIcons.MessageSquare,
            value = stringResource(R.string.settings_unavailable_value)
        ),
        SettingsHomeItem(
            key = SettingsDestination.Language.key,
            title = stringResource(R.string.settings_language_title),
            subtitle = stringResource(R.string.settings_language_subtitle),
            icon = SlockIcons.MessageSquare,
            value = currentLanguage
        )
    )
}

@Composable
private fun LanguageSettingsScreen(
    localeSnapshot: LocaleSnapshot,
    onLocaleSelected: (AppLocale) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_language_title),
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                text = stringResource(R.string.settings_language_restart_hint),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
            localeSnapshot.supported.forEach { locale ->
                val selected = locale == localeSnapshot.current
                SettingsNavRow(
                    item = SettingsHomeItem(
                        key = locale.storageKey,
                        title = locale.nativeLabel,
                        subtitle = locale.englishLabel,
                        icon = SlockIcons.MessageSquare,
                        value = if (selected) stringResource(R.string.settings_locale_selected) else null,
                        selected = selected
                    ),
                    onClick = { onLocaleSelected(locale) }
                )
            }
        }
    }
}

private fun AccountProfileSnapshot.toSettingsAccountProfile() = SettingsAccountProfile(
    displayName = displayName,
    handle = handle,
    email = email,
    avatarLabel = avatarLabel,
    verified = verified,
    roleLabel = roleLabel ?: ""
)

private fun ConnectedAccountSnapshot.toSettingsConnectedAccount() = SettingsConnectedAccount(
    provider = provider,
    identifier = identifier,
    status = when (status) {
        RepositoryConnectedAccountStatus.Connected -> SettingsConnectedAccountStatus.Connected
        RepositoryConnectedAccountStatus.NotConnected -> SettingsConnectedAccountStatus.NotConnected
        RepositoryConnectedAccountStatus.Loading -> SettingsConnectedAccountStatus.Loading
        RepositoryConnectedAccountStatus.Failed -> SettingsConnectedAccountStatus.Failed
    }
)

private fun SettingsSessionSnapshot.toSettingsSessionItem() = SettingsSessionItem(
    id = id,
    deviceName = deviceName,
    browserLabel = browserLabel,
    locationLabel = locationLabel,
    lastActiveLabel = lastActiveLabel,
    current = current,
    revokeEnabled = revokeEnabled
)

private fun settingsDangerItems() = listOf(
    SettingsDangerItem(
        key = "leave-server",
        title = "Leave server",
        subtitle = "Disabled in sample integration",
        actionLabel = "Leave",
        enabled = false,
        noPermission = true
    )
)

private const val SettingsNotificationChannelId = "settings-test"
private const val SettingsNotificationPermissionRequestCode = 142

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Context.showSettingsTestNotification() {
    ensureSettingsNotificationChannel()
    try {
        NotificationManagerCompat.from(this).notify(
            142,
            NotificationCompat.Builder(this, SettingsNotificationChannelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Slock notification test")
                .setContentText("Mobile Settings can show local Android notifications.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        )
    } catch (_: SecurityException) {
        // Permission state is surfaced in the Settings route; this keeps the route callback crash-free.
    }
}

private fun Context.ensureSettingsNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(
        SettingsNotificationChannelId,
        "Settings test notifications",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "Local test notifications from Mobile Settings."
    }
    manager.createNotificationChannel(channel)
}

private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SlockAppPreview() {
    SlockTheme {
        SlockApp()
    }
}
