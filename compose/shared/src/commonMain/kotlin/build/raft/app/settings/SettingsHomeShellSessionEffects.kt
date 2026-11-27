package build.raft.app.settings

import build.raft.app.resources.*
import build.raft.app.settle
import build.raft.app.thread.RaftVisibleConversationRegistry
import build.raft.app.thread.RaftVisibleConversationSnapshot
import build.raft.app.thread.dispatch
import build.raft.app.thread.toConversationOpenSourceMessageIdentity
import build.raft.app.toConversationOpenSourceMessageIdentity
import build.raft.app.toKuiklyJson
import build.raft.app.toServerLastConversation
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.RaftReadToggleNotice
import build.raft.app.data.foldSideThreadIntoDetail
import build.raft.app.data.sideThreadColumnAvailable
import build.raft.app.data.visibleSideThread
import androidx.compose.ui.input.key.key
import build.raft.app.shell.AppShellTabHistory
import build.raft.app.ConversationOpenResolution
import build.raft.app.NotificationPermissionAfterSignInEffect
import build.raft.app.ProcessAttachmentAuthScopes
import build.raft.app.QaBootstrapShellEffects
import build.raft.app.shell.RAFT_SHELL_INSTANCE_ID
import build.raft.app.RaftAdaptiveDetailIdentity
import build.raft.app.RaftAdaptivePaneLayout
import build.raft.app.RaftAdaptivePaneState
import build.raft.app.RaftAdaptiveWorkspaceState
import build.raft.app.shell.RaftAppShellScope
import build.raft.app.shell.RaftAppShellTopBarState
import build.raft.app.RaftLogLevel
import build.raft.app.RaftPlatformLogHint
import build.raft.app.shell.RaftShellNavigationIntake
import build.raft.app.RaftWhiteScreenStage
import build.raft.app.shell.RaftWideNavEntry
import build.raft.app.shell.RaftWideNavHistories
import build.raft.app.shell.RaftWideNavHistory
import build.raft.app.shell.RaftWideNavRestoreSettleMillis
import build.raft.app.ServerLastConversationRepository
import build.raft.app.shell.SharedForegroundBannerStore
import build.raft.app.TaskPendingDraftsFollowSessionEffect
import build.raft.app.shell.appShellRailInlineProductPageView
import build.raft.app.shell.appShellVisibleForegroundConversationKey
import build.raft.app.appShellWhiteScreenStage
import build.raft.app.data.SharedAdaptiveWorkspaceSession
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.MyMessageAlignmentPreference
import build.raft.app.data.AuthLossDecision
import build.raft.app.data.AuthPendingOpenRequest
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthReadyPhase
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.SharedAuthGateViewModel
import build.raft.app.data.isColdStartCachedReadyEligible
import build.raft.app.data.RaftAuthSessionTerminalSource
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.RaftSystemNotificationContract
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeShellIntent
import build.raft.app.data.SharedHomeShellStore
import build.raft.app.data.RealtimeGuardedDomainEventSinks
import build.raft.app.data.RealtimeCanonicalFinalSinkSurfaceKey
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftAuthSessionEvents
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SharedDirectMessagePreviewPrefetchCoordinator
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.ServerId
import build.raft.app.optString
import build.raft.app.post
import build.raft.app.shell.raftAdaptiveNavRailActive
import build.raft.app.raftLogInfo
import build.raft.app.raftLogWarn
import build.raft.app.raftLogger
import build.raft.app.resolveOpenRequest
import build.raft.app.model.RaftTimeContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import build.raft.app.data.map
import kotlinx.coroutines.launch

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellAuthSessionEffects(
    sharedStores: RaftSharedStoreProvider,
    pager: Pager,
    bridge: build.raft.app.base.BridgeModule,
    authStore: build.raft.app.data.KeyValueAuthSessionStore,
    shellAuthStore: build.raft.app.data.RaftAuthSessionStore,
    authSessionTerminalSource: RaftAuthSessionTerminalSource?,
    appShellCoroutineScope: kotlinx.coroutines.CoroutineScope,
    authSessionEvents: RaftAuthSessionEvents,
    authGateViewModel: SharedAuthGateViewModel,
    authGateMessageState: MutableState<AuthGateMessage?>,
    authSessionResetGenerationState: MutableState<Int>,
) {
    var authGateMessage by authGateMessageState
    var authSessionResetGeneration by authSessionResetGenerationState
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellAppearanceEffects(
    sharedStores: RaftSharedStoreProvider,
    pager: Pager,
    bridge: build.raft.app.base.BridgeModule,
    storeScope: RaftShellStoreScope,
    visibleAppearanceGeneration: Int,
) {
    LaunchedEffect(pager.pagerId, visibleAppearanceGeneration) {
        if (visibleAppearanceGeneration > 0) {
            sharedStores.markActiveAppShellPager(pager.pagerId)
        }
    }
    // The app wears this shell's selected server theme synchronously on composition commit
    // (preempting followProcessServerTheme before Frame 1 on cold start of another server's
    // window), and again whenever it appears (another window's shell may have taken over meanwhile).
    DisposableEffect(sharedStores, storeScope, visibleAppearanceGeneration) {
        sharedStores.followServerTheme(storeScope)
        onDispose {}
    }
    // The account is scheduled for deletion (a 403 `account_deletion_pending` on any request): open
    // the web deletion page, which shows the pending state and its Cancel. Once per session; the
    // signal hands each request to one shell only.
    LaunchedEffect(bridge, storeScope) {
        build.raft.app.data.RaftAccountDeletionPendingSignal.Process.openRequests.collect {
            bridge.logWarn("account_deletion_pending_open_page shell=${storeScope.shellInstanceId}")
            pager.openSettingsAccountDeletionPage(storeScope)
        }
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellForegroundBannerEffects(
    pager: Pager,
    suppressRestoreLanding: Boolean,
    selectedTabState: MutableState<String>,
    childTabShellId: String,
    bridge: build.raft.app.base.BridgeModule,
    appShellServerMembersStore: SharedServerMembersStore,
    authGateStateState: MutableState<SharedAuthGateState>,
    foregroundMessageBannersEnabledState: MutableState<Boolean>,
    readyServerId: String?,
    authoritativeHomeStartAllowed: Boolean,
) {
    var selectedTab by selectedTabState
    var authGateState by authGateStateState
    var foregroundMessageBannersEnabled by foregroundMessageBannersEnabledState
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellTabAndDesktopModeEffects(
    desktopModeOnLargeScreenState: MutableState<Boolean>,
    selectedTabState: MutableState<String>,
    childTabSwitchEpochState: MutableState<Int>,
    appShellTabHistoryState: MutableState<AppShellTabHistory>,
    retainedAppShellTabsState: MutableState<Set<String>>,
    railInlineSecondaryDestinationState: MutableState<String?>,
    railActivityReachedState: MutableState<Boolean>,
    bridge: build.raft.app.base.BridgeModule,
    shellScope: RaftAppShellScope,
    slackNavTabKeys: List<String>,
) {
    var desktopModeOnLargeScreen by desktopModeOnLargeScreenState
    var selectedTab by selectedTabState
    var childTabSwitchEpoch by childTabSwitchEpochState
    var appShellTabHistory by appShellTabHistoryState
    var retainedAppShellTabs by retainedAppShellTabsState
    var railInlineSecondaryDestination by railInlineSecondaryDestinationState
    var railActivityReached by railActivityReachedState
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
        // Back never returns to a tab that is no longer available.
        appShellTabHistory = appShellTabHistory.sanitized(allowedKeys)
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellSignInEffects(
    pageVisible: Boolean,
    bridge: build.raft.app.base.BridgeModule,
    storeScope: RaftShellStoreScope,
    authDataSource: SharedAuthDataSource,
    authGateStateState: MutableState<SharedAuthGateState>,
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    homeShellStore: SharedHomeShellStore,
    homeState: SharedHomeState,
    readyServerId: String?,
) {
    var authGateState by authGateStateState
    QaBootstrapShellEffects(
        authGateState = authGateState,
        homeContent = homeState.content,
        authDataSource = authDataSource,
        onRefreshHome = { homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.Refresh)) },
    )
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
                    RaftProductEvents.setSignedIn(serverId, userId = state.session.user.id)
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
    // artin 10-27: ask once for the notification permission when the signed-in shell shows (Android 13+).
    NotificationPermissionAfterSignInEffect(
        active = pageVisible && authGateState is SharedAuthGateState.Ready && !readyServerId.isNullOrBlank(),
        settingsCapabilities = settingsCapabilities,
    )
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellAuthRestoreEffects(
    initialAuthRestoreTick: Int,
    shellAuthStore: build.raft.app.data.RaftAuthSessionStore,
    authGateViewModel: SharedAuthGateViewModel,
    authGateStateState: MutableState<SharedAuthGateState>,
    cachedRestoreSessionState: MutableState<StoredAuthSession?>,
) {
    var authGateState by authGateStateState
    var cachedRestoreSession by cachedRestoreSessionState
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellWindowNavigationEventEffects(
    bridge: build.raft.app.base.BridgeModule,
    storeScope: RaftShellStoreScope,
    windowNavigationCoordinator: build.raft.app.data.SharedWindowNavigationCoordinator,
    eventBus: build.raft.app.data.RaftEventBusRelay,
    navigationIntake: RaftShellNavigationIntake,
    pendingNotificationActivityServerIdState: MutableState<String?>,
    pendingNotificationAppTargetState: MutableState<AppShellNotificationAppTarget?>,
) {
    var pendingNotificationActivityServerId by pendingNotificationActivityServerIdState
    var pendingNotificationAppTarget by pendingNotificationAppTargetState
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
                val enqueued = navigationIntake.enqueue(intent)
                bridge.log(
                    "window_navigation_received source=app_shortcut enqueued=$enqueued " +
                        "targetServerId=${intent.serverId}"
                )
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
                val notificationJson = eventData?.toKuiklyJson()
                val appTarget = notificationJson?.appShellNotificationAppTarget()
                if (appTarget != null) {
                    bridge.log("notification_open_app source=system_notification targetServerId=${appTarget.serverId} appId=${appTarget.appId}")
                    pendingNotificationAppTarget = appTarget
                    return@observe
                }
                val activityServerId = notificationJson?.appShellNotificationActivityServerId()
                if (activityServerId != null) {
                    bridge.log("notification_open_activity source=system_notification targetServerId=$activityServerId")
                    pendingNotificationActivityServerId = activityServerId
                    return@observe
                }
                val intent = notificationJson.appShellSystemNotificationNavigationIntent()
                if (intent == null) {
                    bridge.logWarn("window_navigation_drop source=system_notification reason=invalid_identity")
                    return@observe
                }
                val enqueued = navigationIntake.enqueue(intent)
                bridge.log(
                    "window_navigation_received source=system_notification enqueued=$enqueued " +
                        "targetServerId=${intent.serverId}"
                )
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellAdaptiveColumnEffects(
    adaptivePane: RaftAdaptivePaneState,
    desktopModeOnLargeScreenState: MutableState<Boolean>,
    selectedTabState: MutableState<String>,
    bridge: build.raft.app.base.BridgeModule,
    adaptiveWorkspaceStore: build.raft.app.data.SharedAdaptiveWorkspaceStore,
    adaptiveWorkspaceSessionState: State<SharedAdaptiveWorkspaceSession>,
    adaptiveWorkspaceState: RaftAdaptiveWorkspaceState?,
    tasksDetailColumnActiveState: MutableState<Boolean>,
    tasksDetailColumnExpandedState: MutableState<Boolean>,
    listPaneResizeDefersFold: Boolean,
) {
    var desktopModeOnLargeScreen by desktopModeOnLargeScreenState
    var selectedTab by selectedTabState
    val adaptiveWorkspaceSession by adaptiveWorkspaceSessionState
    var tasksDetailColumnActive by tasksDetailColumnActiveState
    var tasksDetailColumnExpanded by tasksDetailColumnExpandedState
    val sideThreadNeedsFold =
        !listPaneResizeDefersFold &&
            adaptiveWorkspaceSession.visibleSideThread() != null &&
            !adaptiveWorkspaceSession.sideThreadColumnAvailable(desktopModeOnLargeScreen)
    LaunchedEffect(sideThreadNeedsFold) {
        if (!sideThreadNeedsFold) return@LaunchedEffect
        adaptiveWorkspaceStore.update { current ->
            if (current.visibleSideThread() != null && !current.sideThreadColumnAvailable(desktopModeOnLargeScreen)) {
                bridge.log("adaptive_side_thread_fold width=${current.detailPaneWidthDp} planFolds=${current.sideThreadFolds}")
                current.foldSideThreadIntoDetail()
            } else current
        }
    }
    val tasksDetailColumnKeepsOwnership =
        appShellTasksDetailColumnStaysActive(
            desktopModeOnLargeScreen = desktopModeOnLargeScreen,
            dualPane = adaptivePane.layout == RaftAdaptivePaneLayout.DualPane,
            selectedTab = selectedTab,
            selectionPresent = adaptiveWorkspaceState?.selection != null,
        )
    LaunchedEffect(tasksDetailColumnKeepsOwnership) {
        if (!tasksDetailColumnKeepsOwnership && tasksDetailColumnActive) {
            bridge.log("tasks_detail_column_release reason=surface_or_selection_changed")
            tasksDetailColumnActive = false
            tasksDetailColumnExpanded = false
        }
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellComputerAttentionEffects(
    computerAttentionUserId: String?,
    computerAttentionServerId: String?,
    currentComputerAttention: Set<String>,
    computerAttentionSeenState: MutableState<Set<String>>,
    writeComputerAttentionSeen: (Set<String>) -> Unit,
) {
    var computerAttentionSeen by computerAttentionSeenState
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellChatLayoutEffect(
    windowNavigationCoordinator: build.raft.app.data.SharedWindowNavigationCoordinator,
    myMessageAlignmentState: MutableState<MyMessageAlignmentPreference>,
    notificationWindowNavigationIntent: WindowNavigationIntent?,
    desktopShortcutWindowNavigationIntent: WindowNavigationIntent?,
    navigationIntake: RaftShellNavigationIntake,
    homeState: SharedHomeState,
    readySession: StoredAuthSession?,
    authoritativeHomeStartAllowed: Boolean,
    appShellConversationDirectory: SharedConversationDirectoryState,
    threadTimeContext: RaftTimeContext?,
) {
    var myMessageAlignment by myMessageAlignmentState
    LaunchedEffect(
        notificationWindowNavigationIntent?.key,
        desktopShortcutWindowNavigationIntent?.key,
        readySession?.currentServerId,
        readySession?.servers,
        homeState.content,
        appShellConversationDirectory,
        threadTimeContext,
        myMessageAlignment,
        authoritativeHomeStartAllowed
    ) {
        if (authoritativeHomeStartAllowed) {
            notificationWindowNavigationIntent?.let(windowNavigationCoordinator::enqueue)
            desktopShortcutWindowNavigationIntent?.let(windowNavigationCoordinator::enqueue)
        }
        navigationIntake.wake()
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellTaskDraftsAndBannerEffects(
    sharedStores: RaftSharedStoreProvider,
    pager: Pager,
    selectedTabState: MutableState<String>,
    selectedServerStore: build.raft.app.data.SelectedServerStore,
    authGateStateState: MutableState<SharedAuthGateState>,
    selectedHomeDestination: String?,
    readySession: StoredAuthSession?,
    latestSelectedTabState: State<String>,
    latestSelectedHomeDestinationState: State<String?>,
    latestAppShellConversationDirectoryState: State<SharedConversationDirectoryState>,
    homeConversationDirectoryFlow: kotlinx.coroutines.flow.Flow<SharedConversationDirectoryState>,
    homeShellStore: SharedHomeShellStore,
    latestSelectedHomeConversationIdState: State<String?>,
    foregroundBannerStore: SharedForegroundBannerStore,
) {
    var selectedTab by selectedTabState
    var authGateState by authGateStateState
    val latestSelectedTab by latestSelectedTabState
    val latestSelectedHomeDestination by latestSelectedHomeDestinationState
    val latestAppShellConversationDirectory by latestAppShellConversationDirectoryState
    val latestSelectedHomeConversationId by latestSelectedHomeConversationIdState
    // Keep a plain non-snapshot directory reference synced even while the shell is covered so
    // notification suppression (including FCM background threads) never sees stale channel/DM names
    // without recomposing the shell.
    val liveHomeDirectoryRef = remember { atomic(SharedConversationDirectoryState()) }
    LaunchedEffect(homeConversationDirectoryFlow) {
        homeConversationDirectoryFlow.collect { liveHomeDirectoryRef.value = it }
    }
    TaskPendingDraftsFollowSessionEffect(foregroundBannerStore.taskPendingDrafts, authGateState, readySession)
    // System notifications stay silent for the conversation on screen, same rule as the in-app banner.
    DisposableEffect(sharedStores, pager.pagerId, foregroundBannerStore, homeShellStore) {
        val ownerId = "app-shell:${pager.pagerId}"
        RaftVisibleConversationRegistry.register(ownerId) {
            if (!sharedStores.isActiveAppShellPager(pager.pagerId)) return@register null
            val liveActivity = homeShellStore.state.value.activity
            val activityDirectory =
                liveActivity.snapshot?.toSharedConversationDirectoryState(dmPeerNames = liveActivity.dmPeerNames)
                    ?: SharedConversationDirectoryState()
            val liveDirectory =
                listOf(liveHomeDirectoryRef.value, activityDirectory)
                    .mergedSharedConversationDirectoryState()
                    .takeIf { it.projectionsByCanonicalId.isNotEmpty() }
                    ?: latestAppShellConversationDirectory
            RaftVisibleConversationSnapshot(
                currentServerId = selectedServerStore.selectedServerId.value,
                visibleConversationKey =
                    appShellVisibleForegroundConversationKey(
                        selectedTab = latestSelectedTab,
                        selectedHomeDestination = latestSelectedHomeDestination,
                        selectedConversationId = latestSelectedHomeConversationId
                    ),
                visibleRouteIdentity = foregroundBannerStore.visibleRouteIdentity(),
                conversationDirectory = liveDirectory,
                visibleRouteIdentities = foregroundBannerStore.visibleRouteIdentities(),
            )
        }
        onDispose { RaftVisibleConversationRegistry.unregister(ownerId) }
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellReadyServerEffect(
    sharedStores: RaftSharedStoreProvider,
    conversationNotReadyMessage: String,
    pager: Pager,
    bridge: build.raft.app.base.BridgeModule,
    authGateStateState: MutableState<SharedAuthGateState>,
    myMessageAlignmentState: MutableState<MyMessageAlignmentPreference>,
    compactConsecutiveMessagesState: MutableState<Boolean>,
    readySession: StoredAuthSession?,
    readyServerId: String?,
    authoritativeHomeStartAllowed: Boolean,
    navigationStoreScope: RaftShellStoreScope,
    appShellConversationDirectory: SharedConversationDirectoryState,
    threadTimeContext: RaftTimeContext?,
) {
    var authGateState by authGateStateState
    var myMessageAlignment by myMessageAlignmentState
    var compactConsecutiveMessages by compactConsecutiveMessagesState
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellRailHelpEffects(
    serverMenuState: build.raft.designsystem.components.RaftMobileNavServerPopoverState,
    navRailActive: Boolean,
    railHelpOpenState: MutableState<Boolean>,
) {
    var railHelpOpen by railHelpOpenState
    LaunchedEffect(navRailActive) { railHelpOpen = false }
    LaunchedEffect(serverMenuState.open) { if (serverMenuState.open) railHelpOpen = false }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellHomeStoreEffects(
    bridge: build.raft.app.base.BridgeModule,
    conversationRepository: RealConversationRepository,
    homeStore: build.raft.app.data.SharedHomeStore,
) {
    val dmPreviewPrefetchCoordinator =
        remember(conversationRepository, homeStore) {
            SharedDirectMessagePreviewPrefetchCoordinator(
                conversationRepository = conversationRepository,
                dispatchHome = { intent -> homeStore.dispatch(intent) },
                diagnostics = raftLogger("SlockDmPreviewPrefetch", "dm_preview_prefetch", RaftLogLevel.Debug),
            )
        }
    // botiverse/slock #11792: the sidebar's Mark as Unread found nothing to mark; say why.
    val markUnreadNothingMessage by rememberUpdatedState(stringResource(Res.string.home_action_mark_unread_nothing))
    LaunchedEffect(homeStore) {
        homeStore.readToggleNotices.collect { notice ->
            when (notice) {
                RaftReadToggleNotice.MarkUnreadNothing -> bridge.toast(markUnreadNothingMessage)
            }
        }
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellTopBarEffects(
    railHelpOpenState: MutableState<Boolean>,
    railWithoutTopBar: Boolean,
    appShellTopBar: RaftAppShellTopBarState,
) {
    var railHelpOpen by railHelpOpenState
    SideEffect { appShellTopBar.drawn = !railWithoutTopBar }
    LaunchedEffect(appShellTopBar.bellOpen.value) { if (appShellTopBar.bellOpen.value) railHelpOpen = false }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellRailInlineProductPageEffect(
    pager: Pager,
    railInlineSecondaryDestinationState: MutableState<String?>,
) {
    var railInlineSecondaryDestination by railInlineSecondaryDestinationState
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellHomeShellStoreEffects(
    selectedTabState: MutableState<String>,
    homeShellStore: SharedHomeShellStore,
) {
    var selectedTab by selectedTabState
    DisposableEffect(homeShellStore) {
        onDispose { homeShellStore.clear() }
    }
    LaunchedEffect(homeShellStore, selectedTab) {
        homeShellStore.dispatch(SharedHomeShellIntent.SetSelectedTab(selectedTab))
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellAnnouncementEffects(
    announcementStore: build.raft.app.data.SharedAnnouncementStore,
) {
    DisposableEffect(announcementStore) {
        onDispose { announcementStore.close() }
    }
    LaunchedEffect(announcementStore) { announcementStore.refresh() }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellWideNavRestoreEffect(
    bridge: build.raft.app.base.BridgeModule,
    readyServerId: String?,
    wideNavHistoriesState: MutableState<RaftWideNavHistories>,
    latestWideNavShowingState: State<RaftWideNavEntry?>,
    wideNavHistory: RaftWideNavHistory,
) {
    var wideNavHistories by wideNavHistoriesState
    val latestWideNavShowing by latestWideNavShowingState
    LaunchedEffect(readyServerId, wideNavHistory.pendingRestore) {
        val serverId = readyServerId ?: return@LaunchedEffect
        val pending = wideNavHistory.pendingRestore ?: return@LaunchedEffect
        delay(RaftWideNavRestoreSettleMillis)
        bridge.logWarn("wide_history_restore_settled surface=${pending.surface} detail=${pending.detail}")
        wideNavHistories = wideNavHistories.settle(serverId, latestWideNavShowing)
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellHomePreviewEffect(
    bridge: build.raft.app.base.BridgeModule,
    homeStore: build.raft.app.data.SharedHomeStore,
    homePreviewSession: StoredAuthSession?,
    readyAuthPhase: SharedAuthReadyPhase?,
    homePreviewServerId: String?,
) {
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
}

/**
 * The server's last conversation (ServerLastConversation.kt), which Chat's default landing reads again after a server
 * switch or a restart, is the one Chat showed last: [chatConversation], the conversation the wide layout's Chat shows
 * (RaftRailPlaces.kt). A conversation opened from Activity, Search, Saved or Tasks is that tab's and leaves it alone
 * (web layout-memory.md: only Chat's place is a conversation). Off the wide layout ([navRailActive] false) there is
 * no rail and every selection the workspace keeps is written, as before (desktop mode off parks each open there).
 */
@Composable
@NonRestartableComposable
internal fun AppShellAdaptiveSelectionEffect(
    adaptiveWorkspaceState: RaftAdaptiveWorkspaceState?,
    chatConversation: RaftAdaptiveDetailIdentity?,
    navRailActive: Boolean,
    serverLastConversationRepository: ServerLastConversationRepository,
    serverLastConversationOwnerKey: String?,
) {
    LaunchedEffect(adaptiveWorkspaceState?.selection?.identity, serverLastConversationOwnerKey) {
        if (navRailActive) return@LaunchedEffect
        val identity = adaptiveWorkspaceState?.selection?.identity ?: return@LaunchedEffect
        val conversation = identity.toServerLastConversation() ?: return@LaunchedEffect
        serverLastConversationRepository.write(serverLastConversationOwnerKey, identity.serverId, conversation)
    }
    LaunchedEffect(chatConversation, serverLastConversationOwnerKey) {
        val identity = chatConversation ?: return@LaunchedEffect
        val conversation = identity.toServerLastConversation() ?: return@LaunchedEffect
        serverLastConversationRepository.write(serverLastConversationOwnerKey, identity.serverId, conversation)
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellCanonicalFinalSinkEffect(
    appShellRealtimeDomainEventOwner: build.raft.app.data.SharedRealtimeDomainEventOwner,
    canonicalFinalLifecycleEffects: build.raft.app.data.RealtimeCanonicalFinalSinkLifecycleEffects?,
    selectedServerIdState: State<String?>,
    canonicalFinalSelectedServerId: String?,
    canonicalFinalDomainSlot: Pair<String, String>?,
    canonicalFinalUnreadSlots: List<Pair<String, String>>,
    activeAppShellCanonicalFinalSinksState: MutableState<RealtimeGuardedDomainEventSinks?>,
) {
    val selectedServerId by selectedServerIdState
    var activeAppShellCanonicalFinalSinks by activeAppShellCanonicalFinalSinksState
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellStackRootTabEffect(
    selectedTabState: MutableState<String>,
) {
    var selectedTab by selectedTabState
    val stackRootTab = build.raft.app.nav.LocalRaftStackRootTab.current
    SideEffect {
        stackRootTab?.tab = selectedTab
        RaftScreenshotFeedbackRuntime.updateActiveShellTab(selectedTab)
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellWideNavShowingEffect(
    wideNavHistoriesState: MutableState<RaftWideNavHistories>,
    wideNavShowing: RaftWideNavEntry?,
) {
    var wideNavHistories by wideNavHistoriesState
    LaunchedEffect(wideNavShowing) {
        val entry = wideNavShowing ?: return@LaunchedEffect
        wideNavHistories = wideNavHistories.record(entry)
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellServerMenuDismissEffect(
    serverMenuState: build.raft.designsystem.components.RaftMobileNavServerPopoverState,
    navRailActive: Boolean,
    showAppShellBottomTabs: Boolean,
) {
    LaunchedEffect(showAppShellBottomTabs, navRailActive) { serverMenuState.dismiss() }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellSearchOverlayRailEffect(
    navRailActive: Boolean,
    searchOverlayOpenState: MutableState<Boolean>,
) {
    var searchOverlayOpen by searchOverlayOpenState
    LaunchedEffect(navRailActive) { if (!navRailActive) searchOverlayOpen = false }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellHomeServerSwitchEffect(
    pendingHomeServerSwitchIdState: MutableState<String?>,
    observedHomeServerSwitchIdState: MutableState<String?>,
    bridge: build.raft.app.base.BridgeModule,
    homeState: SharedHomeState,
    renderedHomeServerId: String?,
) {
    var pendingHomeServerSwitchId by pendingHomeServerSwitchIdState
    var observedHomeServerSwitchId by observedHomeServerSwitchIdState
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellMessageLinkWakeEffect(
    navigationIntake: RaftShellNavigationIntake,
    messageLinkSessionReady: Boolean,
) {
    LaunchedEffect(messageLinkSessionReady) { navigationIntake.wake() }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellJointInviteEffect(
    appShellRealtimeDomainEventOwner: build.raft.app.data.SharedRealtimeDomainEventOwner,
    jointInviteStore: build.raft.app.data.SharedJointInviteStore,
) {
    DisposableEffect(appShellRealtimeDomainEventOwner, jointInviteStore) {
        val sink =
            build.raft.app.data.RealtimeGuardedActiveThreadSink { eventServerId, event ->
                if (event is build.raft.app.model.JointInvitesChanged && event.serverId.value == eventServerId) {
                    jointInviteStore.dispatch(build.raft.app.data.SharedJointInviteIntent.Changed(eventServerId))
                }
            }
        appShellRealtimeDomainEventOwner.registerGuardedActiveThreadSink(sink)
        onDispose { appShellRealtimeDomainEventOwner.unregisterGuardedActiveThreadSink(sink) }
    }
}

// `seat-join-blocks:changed` (botiverse/slock #11576): re-read the seat-join-blocks list immediately.
@Composable
@NonRestartableComposable
internal fun AppShellSeatJoinBlocksEffect(
    appShellRealtimeDomainEventOwner: build.raft.app.data.SharedRealtimeDomainEventOwner,
    seatJoinBlocksStore: build.raft.app.data.SharedSeatJoinBlocksStore,
) {
    DisposableEffect(appShellRealtimeDomainEventOwner, seatJoinBlocksStore) {
        val sink =
            build.raft.app.data.RealtimeGuardedActiveThreadSink { eventServerId, event ->
                if (event is build.raft.app.model.SeatJoinBlocksChanged && event.serverId.value == eventServerId) {
                    seatJoinBlocksStore.dispatch(build.raft.app.data.SharedSeatJoinBlocksIntent.Changed(eventServerId))
                }
            }
        appShellRealtimeDomainEventOwner.registerGuardedActiveThreadSink(sink)
        onDispose { appShellRealtimeDomainEventOwner.unregisterGuardedActiveThreadSink(sink) }
    }
}

/**
 * Loads `GET /integrations/permission-updates` for owners & admins on the current server and
 * refreshes on `permission-updates:changed`, foreground/reconnect, and page visibility return
 * (#proj-mobile:#345). Fails closed (no request, no dot) for members and guests.
 */
@Composable
@NonRestartableComposable
internal fun AppShellAppPermissionUpdatesEffect(
    appShellRealtimeDomainEventOwner: build.raft.app.data.SharedRealtimeDomainEventOwner,
    store: build.raft.app.data.SharedAppPermissionUpdatesStore,
    serverId: String?,
    canManageIntegrations: Boolean,
    pageVisibleTick: Int,
) {
    LaunchedEffect(store, serverId, canManageIntegrations) {
        store.load(serverId = serverId, canManageIntegrations = canManageIntegrations)
    }
    LaunchedEffect(store, pageVisibleTick) {
        if (pageVisibleTick > 0) {
            store.refresh()
        }
    }
    LaunchedEffect(store) {
        build.raft.app.data.RaftFreshnessSignal.events.collect { event ->
            when (event.trigger) {
                build.raft.app.data.RaftFreshnessTrigger.Reconnect ->
                    event.serverId?.let(store::refresh)
                build.raft.app.data.RaftFreshnessTrigger.Foreground,
                build.raft.app.data.RaftFreshnessTrigger.NetworkRestored ->
                    store.refresh()
            }
        }
    }
    DisposableEffect(appShellRealtimeDomainEventOwner, store) {
        val sink =
            build.raft.app.data.RealtimeGuardedActiveThreadSink { eventServerId, event ->
                if (event is build.raft.app.model.PermissionUpdatesChanged && event.serverId.value == eventServerId) {
                    store.onChanged(eventServerId)
                }
            }
        appShellRealtimeDomainEventOwner.registerGuardedActiveThreadSink(sink)
        onDispose { appShellRealtimeDomainEventOwner.unregisterGuardedActiveThreadSink(sink) }
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellContentStageEffect(
    selectedTabState: MutableState<String>,
    authGateStateState: MutableState<SharedAuthGateState>,
    appShellContentStageState: MutableState<RaftWhiteScreenStage?>,
    homeState: SharedHomeState,
    cachedHomePreview: Boolean,
) {
    var selectedTab by selectedTabState
    var authGateState by authGateStateState
    var appShellContentStage by appShellContentStageState
    SideEffect {
        appShellContentStage =
            appShellWhiteScreenStage(
                restoring = authGateState == SharedAuthGateState.Restoring,
                cachedHomePreview = cachedHomePreview,
                selectedTab = selectedTab,
                homeContentReady = homeState.content is SharedHomeContentState.Content,
            )
    }
}
