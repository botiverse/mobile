package build.raft.app

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.app.base.Utils
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.RaftNotificationPreferenceEvents
import build.raft.app.data.MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS
import build.raft.app.data.MOBILE_ACTION_FOREGROUND_KEEP_ALIVE
import build.raft.app.data.MOBILE_ACTION_OFFLINE_COMPUTER_ALERTS
import build.raft.app.data.MOBILE_ACTION_NOTIFICATION_PREFERENCE
import build.raft.app.data.MOBILE_ACTION_PERMISSION
import build.raft.app.data.MOBILE_ACTION_SERVER_MUTE
import build.raft.app.data.MOBILE_ACTION_SETTINGS
import build.raft.app.data.MOBILE_ACTION_TEST_NOTIFICATION
import build.raft.app.data.MyMessageAlignmentPreference
import build.raft.app.data.LinkOpenMode
import build.raft.app.data.ServerPublicVisibilityRepository
import build.raft.app.data.ServerAppearanceRepository
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.SERVER_ACTION_PROFILE_SAVE
import build.raft.app.data.NotificationPreferenceSnapshot
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsDiagnosticsSnapshot
import build.raft.app.data.SharedSettingsEffect
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SharedSettingsStorageSnapshot
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.isRefreshing
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataResult
import build.raft.app.data.SettingsAppearanceUpdateRequest
import build.raft.app.data.SettingsServerProfileUpdateRequest
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.SharedRealtimeDomainEventOwner
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerNotificationPreferencesChanged
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
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.Pager
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SettingsNativeDetailScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val route = remember(pager.pageData.params.toString()) {
        settingsNativeDetailRoute(pager.pageData.params)
    }
    val closePage = remember(pager) {
        { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    }
    if (route == null) {
        SettingsDetailShell(
            title = stringResource(Res.string.settings_title),
            subtitle = "",
            iconName = RaftLucideIconName.Settings,
            onBack = closePage,
        ) {
            SettingsSharedPlaceholder(
                title = stringResource(Res.string.settings_title),
                detail = stringResource(Res.string.empty_no_settings_available),
                iconName = RaftLucideIconName.Settings,
            )
        }
        return
    }

    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-native-${pager.pagerId}") {
            SharedSettingsViewModel(
                environment.dataSource,
                diagnosticsLogger = { message -> bridge.log("settings_native_detail $message") }
            )
        }
    val settingsState by settingsViewModel.state.collectAsState()
    var session by remember { mutableStateOf<StoredAuthSession?>(null) }
    var notificationPreferenceSnapshot by remember {
        mutableStateOf(NotificationPreferenceSnapshot(enabled = true))
    }
    var activitySenderAvatarsEnabled by remember { mutableStateOf(false) }
    val pageScope = rememberCoroutineScope()
    val notificationEvents = remember(sharedStores.eventBus) {
        RaftNotificationPreferenceEvents(sharedStores.eventBus)
    }

    LaunchedEffect(environment.authStore) {
        session = environment.authStore.readSession()
    }
    LaunchedEffect(environment.notificationPreferenceRepository) {
        notificationPreferenceSnapshot =
            environment.notificationPreferenceRepository.getNotificationPreference()
    }
    DisposableEffect(notificationEvents) {
        val subscription =
            notificationEvents.addPreferenceListener { snapshot ->
                notificationPreferenceSnapshot = snapshot
                sharedStores
                    .sharedHomeStore(environment.storeScope)
                    ?.dispatch(SharedHomeIntent.RefreshNotifications)
            }
        onDispose { subscription.close() }
    }
    SettingsServerNotificationRealtimeEffect(
        owner = remember(sharedStores, environment.storeScope) {
            sharedStores.realtimeDomainEventOwner(environment.storeScope)
        },
        serverId =
            session?.currentServerId
                ?.trim()
                ?.takeIf { it.isNotBlank() && route.page == SettingsNativeDetailPage.Notifications },
        dispatch = settingsViewModel::dispatch,
    )
    LaunchedEffect(environment.chatLayoutPreferenceRepository) {
        activitySenderAvatarsEnabled =
            environment.chatLayoutPreferenceRepository
                .getChatLayoutPreference()
                .activitySenderAvatarsEnabled
    }
    LaunchedEffect(route.page, route.itemId, route.launchIntent) {
        runSettingsNativeDetailLaunchSequence(
            route = route,
            loadPageContent = {
                settingsViewModel.accept(SharedSettingsIntent.LoadPageContent(route.page.storeDestination))
            },
            waitForAutoClose = { delay(it) },
            closePage = closePage,
        )
    }

    fun openNativePage(page: SettingsNativeDetailPage, itemId: String? = null) {
        if (!pager.openSettingsNativeDetailPage(page, environment.storeScope, itemId)) {
            bridge.logWarn("settings_native_detail_rejected page=${page.key}")
        }
    }

    SettingsNativeDetailPageContent(
        session = session,
        authDataSource = environment.authDataSource,
        settingsCapabilities = environment.settingsCapabilities,
        clearStorage = environment.dataSource::clearStorage,
        settingsState = settingsState,
        notificationPreferenceSnapshot = notificationPreferenceSnapshot,
        onSettingsIntent = settingsViewModel::dispatch,
        route = route,
        onBack = closePage,
        onOpenRouterPage = { jumpPage(pager, "router") },
        onOpenAdministrationPage = { pager.openSettingsAdministrationPage(environment.storeScope) },
        onOpenHandsFeedbackPage = { pager.openSettingsHandsFeedbackPage(environment.storeScope) },
        onOpenSourceLicensesPage = { openNativePage(SettingsNativeDetailPage.OpenSourceLicenses) },
        onOpenApiEnvironmentPage = { openNativePage(SettingsNativeDetailPage.ApiEnvironment) },
        onOpenDiagnosticsPage = { openNativePage(SettingsNativeDetailPage.Diagnostics) },
        onOpenLocalFeatureOverridesPage = { openNativePage(SettingsNativeDetailPage.LocalFeatureOverrides) },
        onOpenLocalFeatureOverridePage = { key ->
            openNativePage(SettingsNativeDetailPage.LocalFeatureOverride, key)
        },
        onMessageAlignmentSelected = {},
        onCompactConsecutiveMessagesSelected = {},
        onForegroundMessageBannersSelected = {},
        onActivityTaskStatusBadgesSelected = {},
        onConversationForwardGestureSelected = {},
        activitySenderAvatarsEnabled = activitySenderAvatarsEnabled,
        onActivitySenderAvatarsSelected = { enabled ->
            activitySenderAvatarsEnabled = enabled
            pageScope.launch {
                environment.chatLayoutPreferenceRepository.setActivitySenderAvatarsEnabled(enabled)
            }
        },
        onServerDrawerSelected = {},
        onHomeServerAvatarSelected = {},
        bottomContentPadding = raftBottomSafeAreaPadding() + RaftAppSpacing.Lg,
        timeContext = null,
        diagnosticsLogger = { message -> bridge.log("settings_native_detail $message") },
        serverPublicVisibilityRepository = environment.serverPublicVisibilityRepository,
        serverAppearanceRepository = environment.serverAppearanceRepository,
        authStore = environment.authStore,
        onOpenBillingPage = { pager.openSettingsBillingPage(environment.storeScope) },
        webBaseUrl = environment.webBaseUrl,
        onLogout = { pager.openLoginReplacingCurrentPage() },
    )
}

/**
 * While the Notifications page of [serverId] is open, routes the viewer's server notification preference
 * push (`notification_prefs:updated` with a server mode, from another device or the web) into the page's
 * settings store, which refetches the preference (single-flight). The last-known copy is refreshed by the
 * process-level refresher whether or not this page is open.
 */
@Composable
private fun SettingsServerNotificationRealtimeEffect(
    owner: SharedRealtimeDomainEventOwner,
    serverId: String?,
    dispatch: (SharedSettingsIntent) -> Unit,
) {
    val latestDispatch by rememberUpdatedState(dispatch)
    DisposableEffect(owner, serverId) {
        if (serverId == null) return@DisposableEffect onDispose {}
        val sink =
            RealtimeGuardedActiveThreadSink { eventServerId, event ->
                if (settingsServerNotificationRealtimeEventApplies(eventServerId, event, serverId)) {
                    latestDispatch(SharedSettingsIntent.ApplyRealtimeEvent(serverId, event))
                }
            }
        owner.registerGuardedActiveThreadSink(sink)
        onDispose { owner.unregisterGuardedActiveThreadSink(sink) }
    }
}

/** Whether a realtime event is the viewer's notification preference change for the open page's [serverId]. */
internal fun settingsServerNotificationRealtimeEventApplies(
    eventServerId: String,
    event: RealtimeEvent,
    serverId: String,
): Boolean =
    event is ServerNotificationPreferencesChanged &&
        eventServerId.trim() == serverId.trim() &&
        event.serverId.value.trim() == serverId.trim()

@Composable
internal fun SettingsNativeDetailPageContent(
    session: StoredAuthSession?,
    authDataSource: SharedAuthDataSource,
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    clearStorage: suspend (String) -> DataResult<SharedSettingsStorageSnapshot> = settingsCapabilities::clearStorage,
    settingsState: SharedSettingsState,
    notificationPreferenceSnapshot: NotificationPreferenceSnapshot,
    onSettingsIntent: (SharedSettingsIntent) -> Unit,
    route: SettingsNativeDetailRoute,
    onBack: () -> Unit,
    onOpenRouterPage: () -> Unit,
    onOpenAdministrationPage: () -> Unit,
    onOpenHandsFeedbackPage: () -> Unit,
    onOpenSourceLicensesPage: () -> Unit,
    onOpenApiEnvironmentPage: () -> Unit,
    onOpenDiagnosticsPage: () -> Unit,
    onOpenLocalFeatureOverridesPage: () -> Unit,
    onOpenLocalFeatureOverridePage: (String) -> Unit,
    onMessageAlignmentSelected: (MyMessageAlignmentPreference) -> Unit,
    onCompactConsecutiveMessagesSelected: (Boolean) -> Unit,
    onForegroundMessageBannersSelected: (Boolean) -> Unit,
    onActivityTaskStatusBadgesSelected: (Boolean) -> Unit,
    onConversationForwardGestureSelected: (Boolean) -> Unit,
    activitySenderAvatarsEnabled: Boolean,
    onActivitySenderAvatarsSelected: (Boolean) -> Unit,
    onServerDrawerSelected: (Boolean) -> Unit,
    onHomeServerAvatarSelected: (Boolean) -> Unit,
    bottomContentPadding: Dp,
    timeContext: RaftTimeContext?,
    diagnosticsLogger: (String) -> Unit = {},
    onSessionCleared: (StoredAuthSession) -> Unit = {},
    serverPublicVisibilityRepository: ServerPublicVisibilityRepository? = null,
    /** With [authStore], drives Settings > Theme's server themes; null shows light and dark only. */
    serverAppearanceRepository: ServerAppearanceRepository? = null,
    authStore: RaftAuthSessionStore? = null,
    onOpenBillingPage: () -> Unit = {},
    webBaseUrl: String = "",
    onLogout: () -> Unit
) {
    val diagnosticsSnapshot =
        (settingsState.diagnostics as? ScreenLoadState.Content<SharedSettingsDiagnosticsSnapshot>)?.data
    val localFeatureOverridesAvailable = diagnosticsSnapshot.localFeatureOverridesAvailable()
    val selectedLocalFeatureOverrideKey =
        route.itemId.takeIf { route.page == SettingsNativeDetailPage.LocalFeatureOverride }

    val localFeatureOverrideAvailabilityResolved =
        settingsState.diagnostics !is ScreenLoadState.Loading
    LaunchedEffect(
        route.page,
        route.itemId,
        localFeatureOverrideAvailabilityResolved,
        localFeatureOverridesAvailable,
    ) {
        if (
            shouldCloseLocalFeatureOverridePage(
                route = route,
                availabilityResolved = localFeatureOverrideAvailabilityResolved,
                available = localFeatureOverridesAvailable,
            )
        ) {
            onBack()
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
                settingsCapabilities.openExternalUrl(effect.url)
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
            SharedSettingsEffect.SendFeedback -> onOpenHandsFeedbackPage()
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
                val result = clearStorage(effect.target)
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
                                code = result.reason.name,
                                emptyReason = result.reason
                            )
                        )
                    }
                    is DataResult.Error -> {
                        onSettingsIntent(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = false,
                                code = result.error.code,
                                screenError = result.error
                            )
                        )
                    }
                    is DataResult.NoPermission -> {
                        onSettingsIntent(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = false,
                                code = result.blocker.code,
                                permissionBlocker = result.blocker
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
    var languageRegionPick by remember { mutableStateOf<SettingsLanguageRegionPick?>(null) }
    var appearanceMessageFontSizeDraft by remember { mutableStateOf("md") }
    val currentServerSnapshot = settingsState.server.currentContent()
    val currentLanguageRegionSnapshot = settingsState.languageRegion.currentContent()
    val currentAppearanceSnapshot = settingsState.appearance.currentContent()
    val currentServerProfile = settingsState.server.currentContent()?.profile
    val currentServerName = currentServerProfile?.name ?: session?.currentServerSummary()?.name.orEmpty()
    LaunchedEffect(currentServerName) {
        serverProfileName = currentServerName
    }
    LaunchedEffect(currentAppearanceSnapshot?.messageBodyFontSize?.selectedKey) {
        currentAppearanceSnapshot?.let { snapshot ->
            appearanceMessageFontSizeDraft = snapshot.messageBodyFontSize.selectedKey
        }
    }

    val appPreferenceActions =
        SettingsAppPreferenceActions(
            onAlignmentChange = { itemId ->
                onSettingsIntent(SharedSettingsIntent.SetMessageAlignment(itemId ?: "left"))
                onMessageAlignmentSelected(MyMessageAlignmentPreference.fromStorageKey(itemId))
            },
            onLinkOpenModeChange = { itemId ->
                onSettingsIntent(
                    SharedSettingsIntent.SetLinkOpenMode(
                        itemId ?: LinkOpenMode.Default.storageKey
                    )
                )
            },
            onCompactConsecutiveMessagesChange = { enabled ->
                onSettingsIntent(SharedSettingsIntent.SetCompactConsecutiveMessages(enabled))
                onCompactConsecutiveMessagesSelected(enabled)
            },
            onLongMessageCollapseChange = { enabled ->
                onSettingsIntent(SharedSettingsIntent.SetLongMessageCollapseEnabled(enabled))
            },
            onNewMessageMenuChange = { enabled ->
                onSettingsIntent(SharedSettingsIntent.SetNewMessageMenuEnabled(enabled))
            },
            onConversationForwardGestureChange = { enabled ->
                onSettingsIntent(SharedSettingsIntent.SetConversationForwardGestureEnabled(enabled))
                onConversationForwardGestureSelected(enabled)
            },
            onActivityTaskStatusBadgesChange = { enabled ->
                onSettingsIntent(SharedSettingsIntent.SetActivityTaskStatusBadges(enabled))
                onActivityTaskStatusBadgesSelected(enabled)
            },
            onComposerSendKeyChange = { enabled ->
                onSettingsIntent(SharedSettingsIntent.SetComposerSendKey(enabled))
            },
            onMultiShellHostChange = { enabled ->
                onSettingsIntent(SharedSettingsIntent.SetMultiShellHostEnabled(enabled))
            },
            onDesktopModeOnLargeScreenChange = { enabled ->
                onSettingsIntent(SharedSettingsIntent.SetDesktopModeOnLargeScreen(enabled))
            },
            onServerDrawerChange = { enabled ->
                onSettingsIntent(SharedSettingsIntent.SetServerDrawerEnabled(enabled))
                onServerDrawerSelected(enabled)
            },
            onHomeServerAvatarChange = { enabled ->
                onSettingsIntent(SharedSettingsIntent.SetHomeServerAvatarEnabled(enabled))
                onHomeServerAvatarSelected(enabled)
            },
            onThreadReverseLayoutChange = { enabled ->
                onSettingsIntent(SharedSettingsIntent.SetThreadReverseLayoutEnabled(enabled))
            },
            diagnosticsLogger = diagnosticsLogger,
        )
    val appPreferenceVisibility = settingsAppPreferenceVisibility()

    SettingsDetailShell(
        title = settingsNativeDetailPageTitle(route),
        subtitle = "",
        iconName = settingsNativeDetailPageIcon(route.page),
        onBack = onBack,
        trailingContent = null
    ) {
        if (selectedLocalFeatureOverrideKey != null) {
            val selectedOverride =
                diagnosticsSnapshot
                    ?.registeredLocalFeatureOverrides()
                    ?.firstOrNull { it.key == selectedLocalFeatureOverrideKey }
            if (localFeatureOverridesAvailable && selectedOverride != null) {
                SharedSettingsLocalFeatureOverrideDetailContent(
                    flag = selectedOverride,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onSetOverride = { key, mode ->
                        onSettingsIntent(SharedSettingsIntent.SetDiagnosticFeatureFlagOverride(key, mode))
                    },
                    onExportEvidence = { onSettingsIntent(SharedSettingsIntent.ShareDiagnostics) }
                )
            } else {
                SettingsSharedPlaceholder(
                    title = "Local Feature Overrides",
                    detail = "This internal-only feature override is unavailable in this build.",
                    iconName = RaftLucideIconName.SlidersHorizontal,
                    bottomContentPadding = bottomContentPadding
                )
            }
        } else when (route.page) {
            SettingsNativeDetailPage.LanguageRegion ->
                SettingsLanguageRegionContent(
                    state = settingsState.languageRegion,
                    mobileState = settingsState.mobile,
                    actionState = settingsState.actionState,
                    pick = languageRegionPick,
                    bottomContentPadding = bottomContentPadding,
                    onPick = { pick ->
                        currentLanguageRegionSnapshot
                            ?.let { snapshot -> settingsLanguageRegionApplyIntent(snapshot, pick) }
                            ?.let { intent ->
                                languageRegionPick = pick
                                onSettingsIntent(intent)
                            }
                    },
                    onServerTranslationChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetLanguageRegionServerTranslation(enabled))
                    },
                    onLocaleChange = { localeTag ->
                        onSettingsIntent(SharedSettingsIntent.SetLocale(localeTag))
                    },
                    onRetry = { onSettingsIntent(SharedSettingsIntent.Refresh) }
                )
            SettingsNativeDetailPage.Appearance ->
                SettingsAppearanceContent(
                    state = settingsState.appearance,
                    actionState = settingsState.actionState,
                    fontSizeDraft = appearanceMessageFontSizeDraft,
                    activitySenderAvatarsEnabled = activitySenderAvatarsEnabled,
                    bottomContentPadding = bottomContentPadding,
                    onFontSizeDraftChange = { appearanceMessageFontSizeDraft = it },
                    onActivitySenderAvatarsChange = onActivitySenderAvatarsSelected,
                    onSave = {
                        onSettingsIntent(
                            SharedSettingsIntent.UpdateAppearance(
                                SettingsAppearanceUpdateRequest(appearanceMessageFontSizeDraft)
                            )
                        )
                    },
                    onRetry = { onSettingsIntent(SharedSettingsIntent.Refresh) },
                    showModeCard = settingsAppearanceShowsModeCard(session),
                    appPreferenceItems =
                        if (settingsAppPreferencesUnderAppearance()) {
                            {
                                settingsAppPreferenceItems(
                                    settingsState.mobile,
                                    settingsState.actionState,
                                    appPreferenceActions,
                                    appPreferenceVisibility,
                                )
                            }
                        } else {
                            null
                        },
                )
            SettingsNativeDetailPage.Theme -> {
                val serverTheme =
                    if (serverAppearanceRepository != null && authStore != null) {
                        rememberSettingsServerThemeBinding(
                            repository = serverAppearanceRepository,
                            authStore = authStore,
                            onToast = settingsCapabilities::bridgeToast,
                            onOpenBilling = onOpenBillingPage,
                        )
                    } else {
                        null
                    }
                SettingsThemeContent(
                    serverTheme = serverTheme,
                    bottomContentPadding = bottomContentPadding,
                )
            }
            SettingsNativeDetailPage.Server -> {
                val currentServer = session?.currentServerSummary()
                val publicAccess =
                    rememberSettingsServerPublicAccessBinding(
                        repository = serverPublicVisibilityRepository,
                        serverId = currentServer?.id?.value,
                        role = currentServer?.role,
                        authDataSource = authDataSource,
                        webBaseUrl = webBaseUrl,
                        onCopyUrl = { url -> settingsCapabilities.bridgeCopyToClipboard(url) }
                    )
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
                    publicAccess = publicAccess,
                ) { action, itemId ->
                    when (action) {
                        SERVER_ACTION_PROFILE_SAVE ->
                            itemId?.trim()?.takeIf { it.isNotBlank() }?.let {
                                onSettingsIntent(SharedSettingsIntent.UpdateServerProfile(SettingsServerProfileUpdateRequest(name = it)))
                            }
                        "openAdministration" -> onOpenAdministrationPage()
                        else -> Unit
                    }
                }
            }
            SettingsNativeDetailPage.Notifications ->
                SettingsNotificationsContent(
                    state = settingsState.mobile,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    notificationPreferenceSnapshot = notificationPreferenceSnapshot,
                    preferenceBusy =
                        settingsState.actionState?.inProgress == true &&
                            settingsState.actionState.action in
                            setOf(MOBILE_ACTION_NOTIFICATION_PREFERENCE, MOBILE_ACTION_SERVER_MUTE),
                    foregroundKeepAliveBusy =
                        settingsState.actionState?.inProgress == true &&
                            settingsState.actionState.action in
                            setOf(MOBILE_ACTION_FOREGROUND_KEEP_ALIVE, MOBILE_ACTION_SERVER_MUTE),
                    offlineComputerAlertsBusy =
                        settingsState.actionState?.inProgress == true &&
                            settingsState.actionState.action in
                            setOf(MOBILE_ACTION_OFFLINE_COMPUTER_ALERTS, MOBILE_ACTION_SERVER_MUTE),
                    foregroundMessageBannersBusy =
                        settingsState.actionState?.inProgress == true &&
                            settingsState.actionState.action in
                            setOf(MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS, MOBILE_ACTION_SERVER_MUTE),
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
                    onOfflineComputerAlertsChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetOfflineComputerAlertsPreference(enabled))
                    },
                    onForegroundMessageBannersChange = { enabled ->
                        onSettingsIntent(SharedSettingsIntent.SetForegroundMessageBanners(enabled))
                        onForegroundMessageBannersSelected(enabled)
                    },
                    onServerNotificationModeChange = { mode ->
                        onSettingsIntent(SharedSettingsIntent.SetServerNotificationMode(mode))
                    },
                    onPushPreviewModeChange = { mode ->
                        onSettingsIntent(SharedSettingsIntent.SetPushPreviewMode(mode))
                    },
                    onPermission = { onSettingsIntent(SharedSettingsIntent.RequestNotificationPermission) },
                    onSettings = { onSettingsIntent(SharedSettingsIntent.OpenNotificationSettings) },
                    onTest = { onSettingsIntent(SharedSettingsIntent.SendTestNotification) },
                    onRetry = { onSettingsIntent(SharedSettingsIntent.Refresh) }
                )
            SettingsNativeDetailPage.Mobile ->
                SettingsMobileContent(
                    state = settingsState.mobile,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    actions = appPreferenceActions,
                    visibility = appPreferenceVisibility,
                )
            SettingsNativeDetailPage.About ->
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
                    onOpenUrl = { url -> settingsCapabilities.openUrl(url) },
                    onCheckHandsUpdate = {
                        onSettingsIntent(SharedSettingsIntent.CheckHandsUpdate)
                    },
                    onPrefetchReleaseNotes = { url ->
                        settingsCapabilities.prefetchReleaseNotes(url)
                    },
                    onOpenSourceLicenses = onOpenSourceLicensesPage,
                    onCopyEnvironmentInfo = { onSettingsIntent(SharedSettingsIntent.CopyDiagnostics) },
                    onOpenApiEnvironment = onOpenApiEnvironmentPage,
                    onOpenDiagnostics = onOpenDiagnosticsPage,
                )
            SettingsNativeDetailPage.OpenSourceLicenses ->
                SharedSettingsOpenSourceLicensesContent(
                    state = settingsState.openSourceLicenses,
                    bottomContentPadding = bottomContentPadding,
                    onOpenUrl = { url -> settingsCapabilities.openUrl(url) }
                )
            SettingsNativeDetailPage.Developer ->
                SharedSettingsDeveloperContent(
                    state = settingsState.developer,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onOpenApiEnvironment = onOpenApiEnvironmentPage,
                    onOpenDiagnostics = onOpenDiagnosticsPage,
                    onOpenRouter = onOpenRouterPage,
                )
            SettingsNativeDetailPage.ApiEnvironment ->
                SharedSettingsApiEnvironmentContent(
                    state = settingsState.developer,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onSaveApiEnvironment = { request ->
                        onSettingsIntent(SharedSettingsIntent.SaveApiEnvironment(request))
                    }
                )
            SettingsNativeDetailPage.Diagnostics ->
                SharedSettingsDiagnosticsContent(
                    state = settingsState.diagnostics,
                    actionState = settingsState.actionState,
                    bottomContentPadding = bottomContentPadding,
                    onCopyValue = { value -> settingsCapabilities.bridgeCopyToClipboard(value) },
                    onCopyLogs = { onSettingsIntent(SharedSettingsIntent.CopyDiagnostics) },
                    onShareLogs = { onSettingsIntent(SharedSettingsIntent.ShareDiagnostics) },
                    onSetDiagnosticFeatureFlagOverride = { key, mode ->
                        onSettingsIntent(SharedSettingsIntent.SetDiagnosticFeatureFlagOverride(key, mode))
                    },
                    onOpenLocalFeatureOverrides = {
                        if (localFeatureOverridesAvailable) {
                            onOpenLocalFeatureOverridesPage()
                        }
                    },
                    onSendFeedback = { onSettingsIntent(SharedSettingsIntent.SendFeedback) }
                )
            SettingsNativeDetailPage.LocalFeatureOverrides ->
                if (localFeatureOverridesAvailable && diagnosticsSnapshot != null) {
                    SharedSettingsLocalFeatureOverridesContent(
                        snapshot = diagnosticsSnapshot,
                        bottomContentPadding = bottomContentPadding,
                        onOpenOverride = onOpenLocalFeatureOverridePage,
                    )
                } else {
                    SettingsSharedPlaceholder(
                        title = "Local Feature Overrides",
                        detail = "This internal-only feature override page is unavailable in this build.",
                        iconName = RaftLucideIconName.SlidersHorizontal,
                        bottomContentPadding = bottomContentPadding
                    )
                }
            SettingsNativeDetailPage.LocalFeatureOverride -> Unit
        }
    }
}

@Composable
internal fun SharedSettingsSectionContent(
    payload: RaftSharedSettingsPayload,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onCopyValue: ((String) -> Unit)? = null,
    onAction: (String, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).raftSafeAreaHorizontalPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        payload.sections.forEach { section ->
            item { SharedSettingsSectionCard(section, onCopyValue, onAction) }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

private fun <T> ScreenLoadState<T>.currentContent(): T? =
    (this as? ScreenLoadState.Content<T>)?.data

@Composable
private fun settingsNativeDetailPageTitle(route: SettingsNativeDetailRoute): String =
    when (route.page) {
        SettingsNativeDetailPage.LanguageRegion -> stringResource(Res.string.settings_language_region_title)
        SettingsNativeDetailPage.Appearance -> stringResource(Res.string.settings_appearance_title)
        SettingsNativeDetailPage.Notifications -> stringResource(Res.string.settings_notifications_title)
        SettingsNativeDetailPage.Theme -> stringResource(Res.string.settings_theme_title)
        SettingsNativeDetailPage.Mobile -> stringResource(Res.string.settings_mobile_title)
        SettingsNativeDetailPage.Server -> stringResource(Res.string.settings_server_profile_title)
        SettingsNativeDetailPage.About -> stringResource(Res.string.settings_about_title)
        SettingsNativeDetailPage.Developer -> stringResource(Res.string.settings_developer_title)
        SettingsNativeDetailPage.OpenSourceLicenses -> stringResource(Res.string.settings_open_source_licenses_title)
        SettingsNativeDetailPage.ApiEnvironment -> stringResource(Res.string.settings_developer_api_environment)
        SettingsNativeDetailPage.Diagnostics -> stringResource(Res.string.settings_diagnostics_title)
        SettingsNativeDetailPage.LocalFeatureOverrides -> "Local Feature Overrides"
        SettingsNativeDetailPage.LocalFeatureOverride ->
            route.itemId
                .orEmpty()
                .replace(Regex("_v[0-9]+$"), "")
                .split('_')
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .replaceFirstChar { it.uppercase() }
                .ifBlank { "Local Feature Override" }
    }

private fun settingsNativeDetailPageIcon(page: SettingsNativeDetailPage): RaftLucideIconName =
    when (page) {
        SettingsNativeDetailPage.LanguageRegion -> RaftLucideIconName.Languages
        SettingsNativeDetailPage.Appearance -> RaftLucideIconName.Type
        SettingsNativeDetailPage.Notifications -> RaftLucideIconName.Bell
        SettingsNativeDetailPage.Theme -> RaftLucideIconName.Palette
        SettingsNativeDetailPage.Mobile -> RaftLucideIconName.Smartphone
        SettingsNativeDetailPage.Server -> RaftLucideIconName.Building2
        SettingsNativeDetailPage.About -> RaftLucideIconName.BadgeInfo
        SettingsNativeDetailPage.OpenSourceLicenses -> RaftLucideIconName.FileText
        SettingsNativeDetailPage.Developer -> RaftLucideIconName.Terminal
        SettingsNativeDetailPage.ApiEnvironment,
        SettingsNativeDetailPage.LocalFeatureOverrides,
        SettingsNativeDetailPage.LocalFeatureOverride -> RaftLucideIconName.SlidersHorizontal
        SettingsNativeDetailPage.Diagnostics -> RaftLucideIconName.Clipboard
    }
