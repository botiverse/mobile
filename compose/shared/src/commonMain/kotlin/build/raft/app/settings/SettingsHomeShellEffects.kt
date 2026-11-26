package build.raft.app

import build.raft.app.data.encodeInviteTokenSegment
import build.raft.app.data.artifactWebUrl
import build.raft.app.data.updatedAgentIdOrNull
import build.raft.app.resources.*
import build.raft.app.data.raftProcessRealtimeOwnerGeneration
import build.raft.app.data.raftShellOwnsProcessRealtime
import build.raft.app.data.noteRaftShellRealtimeServer
import build.raft.app.data.raftProcessRealtimeSessionDemand
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.repeatWhileProcessForeground
import androidx.compose.ui.input.key.key
import build.raft.app.data.selectedConversationWhenShellReturns
import build.raft.app.data.RaftHostNetworkEvents
import build.raft.app.data.RaftAppUpdateEvents
import build.raft.app.data.RaftChatLayoutPreferenceEvents
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.MyMessageAlignmentPreference
import build.raft.app.data.ChatLayoutPreferenceSnapshotRuntimeAuthority
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.WindowNavigationReadiness
import build.raft.app.data.hasCompletedTasksSnapshot
import build.raft.app.data.realtime.realtimeProcessDesiredSession
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedHomeShellIntent
import build.raft.app.data.SharedHomeShellStore
import build.raft.app.data.RealtimeGuardedDomainEventSinks
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.AgentProfileRefreshCoalescer
import build.raft.app.data.isAgentProfileUpdate
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.MemberId
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.data.realtime.GeneratedRealtimeContract
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
import androidx.compose.runtime.setValue
import build.raft.app.compat.ui.withKuiklyIdleContext
import androidx.compose.foundation.layout.size
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

private const val AppUpdateNotificationRefreshIntervalMs = 30L * 60L * 1000L

// Moved verbatim out of RaftAppShellReadyContent, which hit the JVM 64 KB method size limit.
@Composable
internal fun AppShellNotificationBadgeEffects(
    readySession: StoredAuthSession?,
    readyServerId: String?,
    homeShellStore: SharedHomeShellStore,
    homeState: SharedHomeState,
    activityUnreadSummaryFlow: Flow<build.raft.app.data.ActivityUnreadSummaryProjection>,
    authoritativeHomeStartAllowed: Boolean,
    serverActivityExpectedOwner: ReadReceiptOwner?,
    bridge: build.raft.app.base.BridgeModule,
    sharedStores: RaftSharedStoreProvider,
) {
    // Every server's level (the badge leaves out `none` servers) and the account settings, once per
    // signed-in account; realtime `notification_prefs:updated` keeps them current afterwards.
    val notificationAccountUserId = readySession?.user?.id?.takeIf { it.isNotBlank() }
    LaunchedEffect(notificationAccountUserId) {
        if (notificationAccountUserId == null) return@LaunchedEffect
        if (build.raft.app.data.RaftNotificationAccountSettingsStore.ownerUserId != notificationAccountUserId) {
            build.raft.app.data.RaftNotificationLevelStore.clear()
            build.raft.app.data.RaftNotificationAccountSettingsStore.clear()
            build.raft.app.data.RaftNotificationChannelNames.clear()
            // What another account's Free servers hid is not this account's to show.
            build.raft.app.data.RaftHiddenHistoryStore.clear()
            build.raft.app.data.RaftNotificationAccountSettingsStore.ownerUserId = notificationAccountUserId
        }
        build.raft.app.data.RaftNotificationAccountSettingsStore.load(build.raft.app.data.DataRequestPriority.Background)
    }
    // The selected server's explicit channel / thread levels, for the sidebar and thread menus' labels.
    LaunchedEffect(notificationAccountUserId, readyServerId) {
        val serverId = readyServerId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (notificationAccountUserId == null) return@LaunchedEffect
        build.raft.app.data.RaftNotificationLevelStore.refresh(serverId, build.raft.app.data.DataRequestPriority.Background)
    }
    // Forward the badge to the host directly from store flows so app / macOS Dock badges stay live
    // even while the shell is covered (Android) or hidden/minimized (macOS) with zero shell recompositions.
    // The selected server's live Activity projection plus every other server's Activity unread,
    // without servers set to `none` (slock docs/contracts/notifications.md §1.4).
    LaunchedEffect(
        homeShellStore,
        activityUnreadSummaryFlow,
        authoritativeHomeStartAllowed,
        readySession?.user?.id,
        readyServerId,
        serverActivityExpectedOwner,
    ) {
        val homeBadgeFlow =
            homeShellStore.state
                .map { it.home.appBadgeCount to it.home.serverActivityUnread }
                .distinctUntilChanged()
        combine(
            homeBadgeFlow,
            activityUnreadSummaryFlow,
            build.raft.app.data.RaftNotificationLevelStore.state,
        ) { (fallback, serverActivityUnread), activityUnreadSummary, notificationLevelsByServer ->
            Triple(fallback to serverActivityUnread, activityUnreadSummary, notificationLevelsByServer)
        }.collect { (homeBadge, activityUnreadSummary, notificationLevelsByServer) ->
            val (fallback, serverActivityUnread) = homeBadge
            val useActivityProjection = authoritativeHomeStartAllowed && readySession != null
            val currentServerCount =
                if (useActivityProjection && activityUnreadSummary.hasValue) {
                    activityUnreadSummary.totalUnreadCount
                } else {
                    fallback.count
                }
            bridge.appBadge(
                count =
                    build.raft.app.data.raftAppBadgeAcrossServers(
                        currentServerId = readyServerId,
                        currentServerCount = currentServerCount,
                        otherServerCounts = serverActivityUnread.countsForAccountOf(serverActivityExpectedOwner),
                        serverPushMuted = serverActivityUnread.serverPushMutedForAccountOf(serverActivityExpectedOwner),
                        levels =
                            notificationLevelsByServer
                                .filterValues { it.serverLevelKnown }
                                .mapValues { it.value.server }
                    ),
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
    }
    // Sync top pinned channels / DMs to the host launcher icon dynamic shortcut menu, and recent
    // writable conversations to the OS Share Sheet Direct Share surface. The label of a DM without
    // a name is resolved here, in composition, so a language change syncs the launcher menu again.
    val directMessageShortcutTitle = stringResource(Res.string.thread_direct_message_title)
    LaunchedEffect(homeState.content, readySession, authoritativeHomeStartAllowed, directMessageShortcutTitle) {
        if (!authoritativeHomeStartAllowed || readySession == null) {
            bridge.syncDesktopShortcuts(emptyList())
            return@LaunchedEffect
        }
        val content = homeState.content as? SharedHomeContentState.Content
        if (content != null) {
            val shortcuts =
                build.raft.app.data.extractTopPinnedDesktopShortcuts(
                    content.snapshot,
                    directMessageFallbackTitle = directMessageShortcutTitle,
                    maxCount = 4
                )
            bridge.syncDesktopShortcuts(shortcuts)
            val contentOwnedBySession =
                content.snapshot.currentMember.id.value == readySession.user.id &&
                    content.session.user.id == readySession.user.id &&
                    readySession.servers.any { it.id == content.snapshot.server.id.value }
            if (!content.isDurableIdentityCache && contentOwnedBySession) {
                val shareSuggestions =
                    build.raft.app.data.extractRecentShareSuggestions(snapshot = content.snapshot)
                build.raft.app.data.RaftShareSuggestionsHostProvider.publish(
                    accountId = readySession.user.id,
                    serverId = content.snapshot.server.id.value,
                    suggestions = shareSuggestions
                )
            }
        }
    }
    LaunchedEffect(sharedStores.activityUnreadSummaryStore, readyServerId, authoritativeHomeStartAllowed) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        val serverId = readyServerId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        sharedStores.activityUnreadSummaryStore.ensure(ServerId(serverId), reason = "app_shell_server_ready")
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellExternalLinkNavigationEffects(
    sharedStores: RaftSharedStoreProvider,
    pager: Pager,
    adaptivePane: RaftAdaptivePaneState,
    desktopModeOnLargeScreenState: MutableState<Boolean>,
    bridge: build.raft.app.base.BridgeModule,
    storeScope: RaftShellStoreScope,
    processRealtimeAuthorityState: State<build.raft.app.data.RealtimeScopeAuthority?>,
    appShellAgentDetailStore: SharedAgentDetailStore,
    selectedServerIdState: State<String?>,
    canonicalFinalSelectedServerId: String?,
    linkNavigator: RaftLinkNavigator,
    navigationIntake: RaftShellNavigationIntake,
    readySession: StoredAuthSession?,
    authoritativeHomeStartAllowed: Boolean,
    navigationStoreScope: RaftShellStoreScope,
    realtimeConnectionFamilyIdState: MutableState<String?>,
    pendingRealtimeAuthRefreshReasonState: MutableState<String?>,
    pendingRealtimeAuthRefreshPreviousFamilyIdState: MutableState<String?>,
    realtimeAuthorityConnectTickState: MutableState<Int>,
    realtimeShellForegroundTickState: MutableState<Int>,
    realtimeReconciledOwnerGenerationState: MutableState<Long>,
    foregroundBannerStore: SharedForegroundBannerStore,
    latestNavigationReadinessState: State<() -> WindowNavigationReadiness?>,
    latestNavigationPerformState: State<suspend (WindowNavigationAction) -> Unit>,
    messageLinkSessionReady: Boolean,
    latestMessageLinkSessionReadyState: State<Boolean>,
    latestResolveMessageLinkState: State<suspend (RaftPendingMessageLink) -> WindowNavigationIntent?>,
    sendPromptHostState: AppShellSendPromptHostState,
) {
    var desktopModeOnLargeScreen by desktopModeOnLargeScreenState
    val processRealtimeAuthority by processRealtimeAuthorityState
    val selectedServerId by selectedServerIdState
    var realtimeConnectionFamilyId by realtimeConnectionFamilyIdState
    var pendingRealtimeAuthRefreshReason by pendingRealtimeAuthRefreshReasonState
    var pendingRealtimeAuthRefreshPreviousFamilyId by pendingRealtimeAuthRefreshPreviousFamilyIdState
    var realtimeAuthorityConnectTick by realtimeAuthorityConnectTickState
    var realtimeShellForegroundTick by realtimeShellForegroundTickState
    var realtimeReconciledOwnerGeneration by realtimeReconciledOwnerGenerationState
    val latestNavigationReadiness by latestNavigationReadinessState
    val latestNavigationPerform by latestNavigationPerformState
    val latestMessageLinkSessionReady by latestMessageLinkSessionReadyState
    val latestResolveMessageLink by latestResolveMessageLinkState
    val latestOpenArtifactLink by rememberUpdatedState<(RaftPendingArtifactLink) -> Unit> { taken ->
        RaftArtifactOpenTrace.tap("deep_link", taken.target.id)
        if (!pager.openRaftArtifactViewerPage(taken.target, navigationStoreScope)) {
            RaftArtifactOpenTrace.mark("browser_fallback", "reason=no_page_scope")
            linkNavigator.open(artifactWebUrl(sharedStores.apiEnvironmentStore.current().webUrl, taken.target), RaftLinkOpenIntent.RaftWebPage)
        }
    }
    val latestNavigationAccountId by rememberUpdatedState { readySession?.user?.id?.takeIf(String::isNotBlank) }
    // An external channel invite link: its page, which reads the invite for this account.
    val latestOpenExternalInviteLink by rememberUpdatedState<(RaftPendingExternalInviteLink) -> Unit> { taken ->
        if (!pager.openExternalInvitePage(taken.token, navigationStoreScope)) {
            bridge.log("external_invite_link_open result=browser_fallback reason=no_page_scope")
            linkNavigator.open(
                sharedSettingsResolvedWebBaseUrl(sharedStores.apiEnvironmentStore.current().webUrl) +
                    "/external-invite/" + taken.token.encodeInviteTokenSegment()
            )
        }
    }
    // A server join link (`/join/{token}`), tapped in a message or opened from outside: the join
    // page, which reads the link and joins for this account.
    val latestOpenServerJoinLink by rememberUpdatedState<(RaftPendingExternalInviteLink) -> Unit> { taken ->
        if (!pager.openServerJoinPage(taken.token, navigationStoreScope)) {
            bridge.log("server_join_link_open result=browser_fallback reason=no_page_scope")
            linkNavigator.open(
                sharedSettingsResolvedWebBaseUrl(sharedStores.apiEnvironmentStore.current().webUrl) +
                    "/join/" + taken.token.encodeInviteTokenSegment(),
                RaftLinkOpenIntent.RaftWebPage,
            )
        }
    }
    val latestOpenSendPromptLink by rememberUpdatedState<(RaftPendingSendPromptLink) -> Unit> { taken ->
        val serverId =
            readySession?.currentServerId?.takeIf(String::isNotBlank)
                ?: canonicalFinalSelectedServerId?.takeIf(String::isNotBlank)
        sendPromptHostState.open(serverId = serverId, prompt = taken.link.prompt)
    }
    val latestOpenDataExportLink by rememberUpdatedState<suspend (RaftPendingDataExportLink) -> Unit> { taken ->
        val session = readySession
        val webBaseUrl = sharedStores.apiEnvironmentStore.current().webUrl
        for (serverId in raftDataExportFlagServerIdsToPrefetch(taken.link, session)) {
            val id = ServerId(serverId)
            if (!sharedStores.serverFeatureFlags.snapshotFor(id).resolved) {
                runCatchingPreservingCancellation { sharedStores.serverFeatureFlags.prefetch(id) }
            }
        }
        when (
            val action =
                resolveRaftDataExportLinkAction(
                    link = taken.link,
                    session = session,
                    webBaseUrl = webBaseUrl,
                    isFlagEnabledForServer = { id ->
                        sharedStores.serverFeatureFlags.isDataExportEnabled(ServerId(id))
                    },
                )
        ) {
            is RaftDataExportLinkAction.OpenSettingsWebView -> {
                bridge.log(
                    "data_export_link_open target=webview hasServer=${action.serverId != null} source=${taken.source}"
                )
                pager.openSettingsDataPrivacyPage(
                    storeScope = navigationStoreScope,
                    exportId = action.exportId,
                    serverId = action.serverId,
                )
            }
            is RaftDataExportLinkAction.OpenWebFallback -> {
                bridge.log(
                    "data_export_link_open target=browser reason=flag_off_or_unresolved source=${taken.source}"
                )
                linkNavigator.open(action.url, RaftLinkOpenIntent.RaftWebPageInBrowser)
            }
        }
    }
    val latestOpenDownloadCenter by rememberUpdatedState<(RaftPendingDownloadCenterLaunch) -> Unit> { taken ->
        val topPageName = pager.host?.navigator?.topPage()?.pageName
        if (topPageName == SETTINGS_STORAGE_PAGE) {
            bridge.log("download_center_open_skipped reason=already_on_top source=${taken.source}")
        } else {
            bridge.log("download_center_open source=${taken.source}")
            pager.openSettingsStoragePage(navigationStoreScope)
        }
    }
    // Single-pane layout (compact phones, or large screens with desktop mode off): resolve the
    // conversation page on top of the shell so a notification or link to it is not pushed twice
    // (preferring the host's authoritative navigation stack over Compose visibility, which clears
    // when the app enters the background). Only an active wide dual-pane split skips this check.
    val wideSplitPaneActive =
        raftAdaptiveNavRailActive(
            desktopModeEnabled = desktopModeOnLargeScreen,
            paneState = adaptivePane,
        )
    val latestNavigationTopPageCheck by rememberUpdatedState {
        val hostNavigator = pager.host?.navigator
        val hostTopPage = hostNavigator?.topPage()
        val fallbackRoute = foregroundBannerStore.visibleRouteIdentity()
        val resolved =
            raftResolveNavigationTopPageRoute(
                wideSplitPaneActive = wideSplitPaneActive,
                hostTopPage = hostTopPage,
                fallbackVisibleRoute = { fallbackRoute },
            )
        val hasStringPayload =
            (hostTopPage?.pageData?.get("payload") as? kotlinx.serialization.json.JsonPrimitive)?.isString == true
        val diagnostics =
            "wideSplitPane=$wideSplitPaneActive desktopMode=$desktopModeOnLargeScreen " +
                "paneLayout=${adaptivePane.layout.name} hostNull=${pager.host == null} " +
                "hostTopPage=${hostTopPage?.pageName ?: "null"} " +
                "hostTopHasPayload=$hasStringPayload hostTopKeys=${hostTopPage?.pageData?.size ?: 0} " +
                "fallbackInstance=${fallbackRoute?.instanceKey.orEmpty()}"
        RaftNavigationTopPageCheck(route = resolved, diagnostics = diagnostics)
    }
    val latestFocusTopConversationMessage by rememberUpdatedState<(HomeMessageOpenRequest, String) -> Unit> { request, source ->
        val messageId = request.anchorMessageId?.trim()?.takeIf(String::isNotBlank) ?: return@rememberUpdatedState
        val result =
            foregroundBannerStore.topPageFocus.dispatch(
                targetRoute = request.conversationIdentity.resolveThreadRouteIdentity(),
                messageId = messageId,
                source = source,
            )
        bridge.log(
            "window_navigation_top_page_focus source=$source " +
                "threadId=${request.conversationIdentity.threadId.value} " +
                "focusedMessageId=$messageId " +
                "delivery=${if (result?.deliveredImmediately == true) "immediate" else if (result != null) "pending" else "dropped"} " +
                "nonce=${result?.request?.nonce ?: 0L}"
        )
    }
    LaunchedEffect(navigationIntake) {
        navigationIntake.run(
            object : RaftShellNavigationIntakeHost {
                override fun messageLinkSessionReady(): Boolean = latestMessageLinkSessionReady

                override suspend fun resolveMessageLink(taken: RaftPendingMessageLink): WindowNavigationIntent? =
                    latestResolveMessageLink(taken)

                override fun openArtifactLink(taken: RaftPendingArtifactLink) = latestOpenArtifactLink(taken)

                override fun openExternalInviteLink(taken: RaftPendingExternalInviteLink) = latestOpenExternalInviteLink(taken)

                override fun openServerJoinLink(taken: RaftPendingExternalInviteLink) = latestOpenServerJoinLink(taken)

                override fun openSendPromptLink(taken: RaftPendingSendPromptLink) = latestOpenSendPromptLink(taken)

                override suspend fun openDataExportLink(taken: RaftPendingDataExportLink) = latestOpenDataExportLink(taken)

                override fun openDownloadCenter(taken: RaftPendingDownloadCenterLaunch) = latestOpenDownloadCenter(taken)

                override fun currentAccountId(): String? = latestNavigationAccountId()

                override fun shellInstanceId(): String = navigationStoreScope.shellInstanceId

                override fun readiness(): WindowNavigationReadiness? = latestNavigationReadiness()

                override fun topPageRoute(): ThreadRouteIdentity? = latestNavigationTopPageCheck().route

                override fun checkTopPage(): RaftNavigationTopPageCheck = latestNavigationTopPageCheck()

                override fun focusTopConversationMessage(request: HomeMessageOpenRequest, source: String) =
                    latestFocusTopConversationMessage(request, source)

                override suspend fun closeIncomingNavigationCover(): Boolean = foregroundBannerStore.incomingNavigation.closeAll()

                override suspend fun perform(action: WindowNavigationAction) = latestNavigationPerform(action)

                override fun log(message: String) = bridge.log(message)
            }
        )
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
        realtimeShellForegroundTick,
    ) {
        if (!authoritativeHomeStartAllowed) return@LaunchedEffect
        // Phase 3: this window claims its server; with multi-session realtime on every claimed server
        // keeps its own session, so no window has to take the connection from another one.
        val multiSessionRealtime = raftMultiServerRealtimeEnabled()
        canonicalFinalSelectedServerId?.let { serverId ->
            raftProcessRealtimeSessionDemand.claim(storeScope.shellInstanceId, serverId)
        }
        if (
            !multiSessionRealtime &&
            !raftShellOwnsProcessRealtime(storeScope.shellInstanceId, raftMultiShellHostEnabled())
        ) {
            // A window behind another one never takes the connection away from the window in front.
            bridge.log(
                "app_shell_realtime_reconcile_skip reason=background_window " +
                    "serverId=${canonicalFinalSelectedServerId.orEmpty()}"
            )
            pendingRealtimeAuthRefreshReason?.let { reason ->
                pendingRealtimeAuthRefreshReason = null
                pendingRealtimeAuthRefreshPreviousFamilyId = null
                bridge.realtimeAuthRefreshFinished(reason, success = true)
            }
            return@LaunchedEffect
        }
        realtimeReconciledOwnerGeneration = raftProcessRealtimeOwnerGeneration()
        if (!multiSessionRealtime) {
            noteRaftShellRealtimeServer(canonicalFinalSelectedServerId.takeIf { raftMultiShellHostEnabled() })
        }
        val environment = sharedStores.apiEnvironmentStore.current()
        val desired =
            if (multiSessionRealtime) {
                // The same set the process reconcile sends (ProcessRealtimeIngressCoordinator), so the
                // two never take a server from each other.
                realtimeProcessDesiredSession(
                    authority = processRealtimeAuthority,
                    environmentId = environment.apiBaseUrl,
                    socketUrl = environment.socketBaseUrl,
                    currentServerId = canonicalFinalSelectedServerId,
                    serverIdsFor = { granted ->
                        raftProcessRealtimeSessionDemand.desiredServerIds(
                            grantedServerIds = granted,
                            selectedServerId = canonicalFinalSelectedServerId,
                            nowMillis = defaultNowMillis(),
                            enabled = true,
                        )
                    },
                )
            } else {
                realtimeProcessDesiredSession(
                    authority = processRealtimeAuthority,
                    environmentId = environment.apiBaseUrl,
                    socketUrl = environment.socketBaseUrl,
                    currentServerId = canonicalFinalSelectedServerId,
                )
            }
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
            multiSession = multiSessionRealtime,
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellServerMenuEffects(
    pageVisibleTick: Int,
    selectedTabState: MutableState<String>,
    serverMenuState: build.raft.designsystem.components.RaftMobileNavServerPopoverState,
    serverPageOriginState: MutableState<String?>,
    serverTabFocusPendingState: MutableState<Boolean>,
    serverTabFocusOnPageReturnState: MutableState<Boolean>,
    railInlineSecondaryDestinationState: MutableState<String?>,
    bridge: build.raft.app.base.BridgeModule,
    authDataSource: SharedAuthDataSource,
    serverDrawerProgrammaticCloseFenceState: MutableState<((String) -> Unit)?>,
    homeShellStore: SharedHomeShellStore,
    homeState: SharedHomeState,
    readyServerId: String?,
    wideNavHistoriesState: MutableState<RaftWideNavHistories>,
    serverMenuArtifactsShown: Boolean,
    activePhoneTabKeys: List<String> = AppShellPhoneBottomBarKeys,
) {
    var selectedTab by selectedTabState
    var serverPageOrigin by serverPageOriginState
    var serverTabFocusPending by serverTabFocusPendingState
    var serverTabFocusOnPageReturn by serverTabFocusOnPageReturnState
    var railInlineSecondaryDestination by railInlineSecondaryDestinationState
    var serverDrawerProgrammaticCloseFence by serverDrawerProgrammaticCloseFenceState
    var wideNavHistories by wideNavHistoriesState
    LaunchedEffect(serverMenuArtifactsShown, readyServerId) {
        if (serverMenuArtifactsShown) return@LaunchedEffect
        val serverId = readyServerId ?: return@LaunchedEffect
        wideNavHistories = wideNavHistories.withoutSurface(serverId, AppShellRailArtifactsKey)
        if (railInlineSecondaryDestination == AppShellRailArtifactsKey) railInlineSecondaryDestination = null
    }

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
    // The server panel lists the account's servers as they are now: one joined from a link, on the
    // web or on another device shows without opening the full list first. The stored list shows at
    // once; the reloaded one replaces it when it comes.
    LaunchedEffect(serverMenuState.open) {
        if (!serverMenuState.open) return@LaunchedEffect
        homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SyncSessionServers))
        val reloaded = runCatchingPreservingCancellation { authDataSource.loadServers() }.getOrNull()
        bridge.log("app_shell_server_menu_servers_reload result=${if (reloaded is SharedAuthResult.Success) "success" else "skipped"}")
        if (reloaded is SharedAuthResult.Success) {
            homeShellStore.dispatch(SharedHomeShellIntent.Home(SharedHomeIntent.SyncSessionServers))
        }
    }
    // A server page's origin lives only while a server page shows; a deep link to one finds none
    // and goes back to the bar's primary tab.
    LaunchedEffect(selectedTab, activePhoneTabKeys) {
        if (!appShellIsMoreChildTab(selectedTab, activePhoneTabKeys)) serverPageOrigin = null
    }
    // Computers is a page of its own: when the shell shows again after it, focus the server tab.
    LaunchedEffect(pageVisibleTick) {
        if (serverTabFocusOnPageReturn) {
            serverTabFocusOnPageReturn = false
            serverTabFocusPending = true
        }
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellHomeStartEffects(
    pageVisible: Boolean,
    desktopModeOnLargeScreenState: MutableState<Boolean>,
    selectedTabState: MutableState<String>,
    bridge: build.raft.app.base.BridgeModule,
    adaptiveWorkspaceStore: build.raft.app.data.SharedAdaptiveWorkspaceStore,
    appShellCoroutineScope: kotlinx.coroutines.CoroutineScope,
    appUpdateEvents: RaftAppUpdateEvents,
    homeShellStore: SharedHomeShellStore,
    homeState: SharedHomeState,
    selectedHomeDestination: String?,
    readySession: StoredAuthSession?,
    readyServerId: String?,
    slackNavMode: Boolean,
    homePreviewServerId: String?,
    setupAdmissionAllowsHome: Boolean,
    authoritativeHomeStartAllowed: Boolean,
    cachedHomePreview: Boolean,
    latestAuthoritativeHomeStartAllowedState: State<Boolean>,
    homeSurfaceVisible: Boolean,
) {
    var desktopModeOnLargeScreen by desktopModeOnLargeScreenState
    var selectedTab by selectedTabState
    val latestAuthoritativeHomeStartAllowed by latestAuthoritativeHomeStartAllowedState
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
        // Only while the app is in front: hidden, nothing is read; back in front one read if one came due.
        repeatWhileProcessForeground(intervalMillis = AppUpdateNotificationRefreshIntervalMs) {
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellBackAndTasksEffects(
    selectedTabState: MutableState<String>,
    tasksSurfaceActive: Boolean,
    bridge: build.raft.app.base.BridgeModule,
    appShellServerMembersStore: SharedServerMembersStore,
    selectedHomeDestination: String?,
    tasksFirstRenderLoggedKeyState: MutableState<String?>,
    readyServerId: String?,
    tasksRoute: build.raft.app.data.SharedTasksRouteSession,
    tasksState: build.raft.app.data.TasksUiState,
    authoritativeHomeStartAllowed: Boolean,
    handleAppShellBackState: State<() -> Unit>,
) {
    var selectedTab by selectedTabState
    var tasksFirstRenderLoggedKey by tasksFirstRenderLoggedKeyState
    val handleAppShellBack by handleAppShellBackState
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellChildPagePrewarmEffects(
    pageVisibleTick: Int,
    pager: Pager,
    selectedTabState: MutableState<String>,
    retainedAppShellTabsState: MutableState<Set<String>>,
    prewarmedAppShellTabsState: MutableState<Set<String>>,
    prewarmAdmissionGate: PrewarmAdmissionGate,
    childPagePrewarmPolicy: AppShellChildPagePrewarmPolicy,
    childTabShellId: String,
    bridge: build.raft.app.base.BridgeModule,
    appShellComputersStore: SharedComputersStore,
    homeState: SharedHomeState,
    readySession: StoredAuthSession?,
    readyServerId: String?,
    slackNavTabKeys: List<String>,
    slackNavMode: Boolean,
    authoritativeHomeStartAllowed: Boolean,
) {
    var selectedTab by selectedTabState
    var retainedAppShellTabs by retainedAppShellTabsState
    var prewarmedAppShellTabs by prewarmedAppShellTabsState
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
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellRuntimeAuthorityEffects(
    desktopModeOnLargeScreenState: MutableState<Boolean>,
    chatLayoutPreferenceEvents: RaftChatLayoutPreferenceEvents,
    chatLayoutPreferenceRepository: build.raft.app.data.ChatLayoutPreferenceRepository,
    myMessageAlignmentState: MutableState<MyMessageAlignmentPreference>,
    compactConsecutiveMessagesState: MutableState<Boolean>,
    foregroundMessageBannersEnabledState: MutableState<Boolean>,
    serverDrawerEnabledState: MutableState<Boolean>,
    foregroundBannerStore: SharedForegroundBannerStore,
) {
    var desktopModeOnLargeScreen by desktopModeOnLargeScreenState
    var myMessageAlignment by myMessageAlignmentState
    var compactConsecutiveMessages by compactConsecutiveMessagesState
    var foregroundMessageBannersEnabled by foregroundMessageBannersEnabledState
    var serverDrawerEnabled by serverDrawerEnabledState
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
                serverDrawerEnabled = chatLayout.serverDrawerEnabled
                    }
        }
    DisposableEffect(
        chatLayoutPreferenceEvents,
        chatLayoutPreferenceSnapshotRuntimeAuthority,
    ) {
        val snapshotSubscription =
            chatLayoutPreferenceEvents.addPreferenceListener { chatLayout ->
                chatLayoutPreferenceSnapshotRuntimeAuthority.applyProcessPreferenceChange(chatLayout)
            }
        onDispose {
            snapshotSubscription.close()
        }
    }
    LaunchedEffect(
        chatLayoutPreferenceRepository,
        chatLayoutPreferenceSnapshotRuntimeAuthority,
    ) {
        val chatLayout = chatLayoutPreferenceRepository.getChatLayoutPreference()
        chatLayoutPreferenceSnapshotRuntimeAuthority.seedFromRepository(chatLayout)
    }
}

/**
 * Whether the wide rail shows its Assistant (web LeftRail `useRememberedAssistantShown`): the
 * phone Home row's rule ([build.raft.app.assistant.rememberAssistantEntryShown]), only while the
 * rail shows. When it goes (flag off, a guest, another server without it), its inline page closes
 * and the wide history's arrows never reopen it, as Artifacts. Kept out of
 * RaftAppShellReadyContent (JVM method size).
 */
@Composable
internal fun rememberAppShellRailAssistantShown(
    navRailActive: Boolean,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    session: StoredAuthSession?,
    serverId: String?,
    railInlineSecondaryDestinationState: MutableState<String?>,
    wideNavHistoriesState: MutableState<RaftWideNavHistories>,
): Boolean {
    if (!navRailActive) return false
    val store = remember(sharedStores, storeScope) { sharedStores.myAssistantStore(storeScope) }
    val memory = remember { build.raft.app.data.HomeAssistantEntryMemory(build.raft.app.data.raftSharedPreferenceStore()) }
    val server = serverId?.trim()?.takeIf(String::isNotEmpty)
    val shown =
        build.raft.app.assistant.rememberAssistantEntryShown(
            store = store,
            featureFlags = sharedStores.serverFeatureFlags,
            memory = memory,
            serverId = server,
            userId = session?.user?.id,
            serverRole = session?.servers?.firstOrNull { it.id == server }?.role,
        )
    var railInlineSecondaryDestination by railInlineSecondaryDestinationState
    var wideNavHistories by wideNavHistoriesState
    LaunchedEffect(shown, server) {
        if (shown || server == null) return@LaunchedEffect
        wideNavHistories = wideNavHistories.withoutSurface(server, AppShellRailAssistantKey)
        if (railInlineSecondaryDestination == AppShellRailAssistantKey) railInlineSecondaryDestination = null
    }
    return shown
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellRailTabOrderEffects(
    bridge: build.raft.app.base.BridgeModule,
    readySession: StoredAuthSession?,
    navRailActive: Boolean,
    railTabOrderStore: build.raft.app.data.SharedRailTabOrderStore,
    railTabOrderStateState: State<build.raft.app.data.SharedRailTabOrderState>,
) {
    val railTabOrderState by railTabOrderStateState
    val railTabOrderUser = readySession?.user
    LaunchedEffect(railTabOrderStore, railTabOrderUser?.id, railTabOrderUser?.railTabOrder) {
        if (railTabOrderUser != null) {
            railTabOrderStore.dispatch(
                build.raft.app.data.SharedRailTabOrderIntent.SessionLoaded(railTabOrderUser.id, railTabOrderUser.railTabOrder)
            )
        }
    }
    LaunchedEffect(railTabOrderStore, navRailActive) {
        railTabOrderStore.dispatch(build.raft.app.data.SharedRailTabOrderIntent.RailShown(navRailActive))
    }
    val railTabOrderSaveFailed = stringResource(Res.string.rail_tab_order_save_failed)
    LaunchedEffect(railTabOrderState.effect) {
        if (railTabOrderState.effect == build.raft.app.data.SharedRailTabOrderEffect.SaveFailed) {
            bridge.errorToast(railTabOrderSaveFailed, railTabOrderState.effectTraceId)
            railTabOrderStore.dispatch(build.raft.app.data.SharedRailTabOrderIntent.ConsumeEffect)
        }
    }
}

/**
 * Wires per-server rail places (`GET/PATCH /servers/:id/sidebar-order`, `rail_places:updated`,
 * `permission-updates:changed`, foreground/reconnect freshness, and conflict/error toasts;
 * botiverse/slock #16682 + #16684 + #16715, #wg-mobile-sync:#294). Gated behind `rail_places_v0`
 * ([enabled]): when OFF or unresolved, no per-server rail places are read, written, or updated.
 * Kept outside `RaftAppShellReadyContent` to preserve its JVM method-size budget.
 */
@Composable
@NonRestartableComposable
internal fun AppShellRailPlacesEffects(
    bridge: build.raft.app.base.BridgeModule,
    readySession: StoredAuthSession?,
    readyServerId: String?,
    navRailActive: Boolean,
    enabled: Boolean,
    sidebarOrder: build.raft.app.data.SettingsServerSidebarOrderSnapshot?,
    appShellRealtimeDomainEventOwner: build.raft.app.data.SharedRealtimeDomainEventOwner,
    railPlacesStore: build.raft.app.data.SharedRailPlacesStore,
    railPlacesStateState: State<build.raft.app.data.SharedRailPlacesState>,
) {
    val railPlacesState by railPlacesStateState
    val accountId = readySession?.user?.id
    val railPlacesActive = navRailActive && enabled
    LaunchedEffect(railPlacesStore, accountId) {
        railPlacesStore.setAccount(accountId)
    }
    LaunchedEffect(railPlacesStore, readyServerId, enabled) {
        railPlacesStore.setActiveServer(if (enabled) readyServerId else null)
    }
    LaunchedEffect(railPlacesStore, railPlacesActive) {
        railPlacesStore.setRailShown(railPlacesActive)
    }
    LaunchedEffect(railPlacesStore, readyServerId, sidebarOrder?.railPlaces, enabled) {
        if (!enabled) return@LaunchedEffect
        val serverId = readyServerId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        railPlacesStore.applySidebarOrderSnapshot(serverId, sidebarOrder?.railPlaces)
    }
    LaunchedEffect(railPlacesStore, railPlacesActive) {
        if (!railPlacesActive) return@LaunchedEffect
        build.raft.app.data.RaftFreshnessSignal.events.collect { event ->
            val targetServerId =
                when (event.trigger) {
                    build.raft.app.data.RaftFreshnessTrigger.Reconnect -> event.serverId ?: railPlacesStore.state.value.activeServerId
                    build.raft.app.data.RaftFreshnessTrigger.Foreground,
                    build.raft.app.data.RaftFreshnessTrigger.NetworkRestored -> railPlacesStore.state.value.activeServerId
                }
            if (!targetServerId.isNullOrBlank()) {
                railPlacesStore.reloadAsync(targetServerId)
            }
        }
    }
    DisposableEffect(appShellRealtimeDomainEventOwner, railPlacesStore, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val sink =
            build.raft.app.data.RealtimeGuardedActiveThreadSink { eventServerId, event ->
                when (event) {
                    is build.raft.app.model.RailPlacesUpdated -> {
                        if (event.serverId.value == eventServerId) {
                            railPlacesStore.applyRealtimeUpdated(event)
                        }
                    }
                    is build.raft.app.model.PermissionUpdatesChanged -> {
                        if (event.serverId.value == eventServerId) {
                            railPlacesStore.onPermissionUpdatesChanged(eventServerId)
                        }
                    }
                    else -> Unit
                }
            }
        appShellRealtimeDomainEventOwner.registerGuardedActiveThreadSink(sink)
        onDispose { appShellRealtimeDomainEventOwner.unregisterGuardedActiveThreadSink(sink) }
    }
    val saveFailedText = stringResource(Res.string.rail_places_save_failed)
    val conflictText = stringResource(Res.string.rail_places_conflict)
    LaunchedEffect(railPlacesState.effect, enabled) {
        if (!enabled) return@LaunchedEffect
        when (val effect = railPlacesState.effect) {
            is build.raft.app.data.SharedRailPlacesEffect.SaveFailed -> {
                bridge.errorToast(saveFailedText, effect.traceId)
                railPlacesStore.consumeEffect()
            }
            is build.raft.app.data.SharedRailPlacesEffect.Conflict -> {
                bridge.toast(conflictText)
                railPlacesStore.consumeEffect()
            }
            null -> Unit
        }
    }
}

/**
 * Wires per-server mobile & native tablet/foldable navigation places
 * (`GET/PATCH /servers/:id/sidebar-order`, `mobile_places:updated`, `permission-updates:changed`,
 * foreground/reconnect freshness, conflict/error toasts, and phone default-tab landing;
 * botiverse/slock #16964, `#proj-mobile:#329`). Gated behind `tab_bar_places_v0` ([enabled],
 * always false on Desktop Compose). Kept outside `RaftAppShellReadyContent` to preserve its JVM
 * method-size budget.
 */
@Composable
@NonRestartableComposable
internal fun AppShellMobileRailPlacesEffects(
    bridge: build.raft.app.base.BridgeModule,
    readySession: StoredAuthSession?,
    readyServerId: String?,
    navRailActive: Boolean,
    enabled: Boolean,
    sidebarOrder: build.raft.app.data.SettingsServerSidebarOrderSnapshot?,
    appShellRealtimeDomainEventOwner: build.raft.app.data.SharedRealtimeDomainEventOwner,
    mobileRailPlacesStore: build.raft.app.data.SharedMobileRailPlacesStore,
    mobileRailPlacesStateState: State<build.raft.app.data.SharedMobileRailPlacesState>,
    activePhoneTabKeys: List<String>,
    artifactsEnabled: Boolean,
    isGuest: Boolean,
    hasExplicitInitialTab: Boolean,
    selectedTabState: MutableState<String>,
    retainedAppShellTabsState: MutableState<Set<String>>,
    userPickedTabServerIdState: MutableState<String?>,
    onSelectProgrammaticTab: (String) -> Unit,
) {
    val mobileRailPlacesState by mobileRailPlacesStateState
    val accountId = readySession?.user?.id
    val selectedTab by selectedTabState
    var retainedAppShellTabs by retainedAppShellTabsState
    val latestOnSelectProgrammaticTab by rememberUpdatedState(onSelectProgrammaticTab)
    LaunchedEffect(mobileRailPlacesStore, accountId) {
        mobileRailPlacesStore.setAccount(accountId)
    }
    LaunchedEffect(mobileRailPlacesStore, readyServerId, enabled) {
        mobileRailPlacesStore.setActiveServer(if (enabled) readyServerId else null)
    }
    LaunchedEffect(mobileRailPlacesStore, enabled) {
        mobileRailPlacesStore.setSurfaceActive(enabled)
    }
    LaunchedEffect(mobileRailPlacesStore, readyServerId, sidebarOrder?.mobilePlaces, enabled) {
        if (!enabled) return@LaunchedEffect
        val serverId = readyServerId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        mobileRailPlacesStore.applySidebarOrderSnapshot(serverId, sidebarOrder?.mobilePlaces)
    }
    LaunchedEffect(mobileRailPlacesStore, enabled) {
        if (!enabled) return@LaunchedEffect
        build.raft.app.data.RaftFreshnessSignal.events.collect { event ->
            val targetServerId =
                when (event.trigger) {
                    build.raft.app.data.RaftFreshnessTrigger.Reconnect ->
                        event.serverId ?: mobileRailPlacesStore.state.value.activeServerId
                    build.raft.app.data.RaftFreshnessTrigger.Foreground,
                    build.raft.app.data.RaftFreshnessTrigger.NetworkRestored ->
                        mobileRailPlacesStore.state.value.activeServerId
                }
            if (!targetServerId.isNullOrBlank()) {
                mobileRailPlacesStore.reloadAsync(targetServerId)
            }
        }
    }
    DisposableEffect(appShellRealtimeDomainEventOwner, mobileRailPlacesStore, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val sink =
            build.raft.app.data.RealtimeGuardedActiveThreadSink { eventServerId, event ->
                when (event) {
                    is build.raft.app.model.MobilePlacesUpdated -> {
                        if (event.serverId.value == eventServerId) {
                            mobileRailPlacesStore.applyRealtimeUpdated(event)
                        }
                    }
                    is build.raft.app.model.PermissionUpdatesChanged -> {
                        if (event.serverId.value == eventServerId) {
                            mobileRailPlacesStore.onPermissionUpdatesChanged(eventServerId)
                        }
                    }
                    else -> Unit
                }
            }
        appShellRealtimeDomainEventOwner.registerGuardedActiveThreadSink(sink)
        onDispose { appShellRealtimeDomainEventOwner.unregisterGuardedActiveThreadSink(sink) }
    }
    val saveFailedText = stringResource(Res.string.rail_places_save_failed)
    val conflictText = stringResource(Res.string.rail_places_conflict)
    LaunchedEffect(mobileRailPlacesState.effect, enabled) {
        if (!enabled) return@LaunchedEffect
        when (val effect = mobileRailPlacesState.effect) {
            is build.raft.app.data.SharedRailPlacesEffect.SaveFailed -> {
                bridge.errorToast(saveFailedText, effect.traceId)
                mobileRailPlacesStore.consumeEffect()
            }
            is build.raft.app.data.SharedRailPlacesEffect.Conflict -> {
                bridge.toast(conflictText)
                mobileRailPlacesStore.consumeEffect()
            }
            null -> Unit
        }
    }
    // Kirby §7: when entering a server with `tab_bar_places_v0` ON on a phone, land on the 1st tab
    // of the person's phone bar (not hardcoded "home") unless an explicit launch tab or manual tap
    // already chose a tab on this server. Also clamp unavailable promoted tabs (e.g. Artifacts off
    // or guest role on Members/Computers/Apps) back to the 1st tab.
    var appliedDefaultServerId by remember { mutableStateOf<String?>(null) }
    val activeSnapshotLoaded = mobileRailPlacesState.activeSnapshot != null
    LaunchedEffect(
        enabled,
        navRailActive,
        readyServerId,
        activeSnapshotLoaded,
        activePhoneTabKeys,
        artifactsEnabled,
        isGuest,
        selectedTab,
    ) {
        if (!enabled || navRailActive) return@LaunchedEffect
        val serverId = readyServerId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val primaryTab = appShellPhonePrimaryTabKey(activePhoneTabKeys)
        val retained = retainedAppShellSelectedTab(selectedTab)
        val selectedUnavailable =
            (retained == RaftAppDestination.Artifacts.id && !artifactsEnabled) ||
                (isGuest && (retained == RaftAppDestination.Members.id || retained == RaftAppDestination.Computers.id || retained == RaftAppDestination.Apps.id))
        if (selectedUnavailable && retained != primaryTab) {
            latestOnSelectProgrammaticTab(primaryTab)
            retainedAppShellTabs = retainedAppShellTabs + primaryTab
            return@LaunchedEffect
        }
        if (
            activeSnapshotLoaded &&
            appliedDefaultServerId != serverId &&
            !hasExplicitInitialTab &&
            userPickedTabServerIdState.value != serverId
        ) {
            appliedDefaultServerId = serverId
            if (retained != primaryTab) {
                latestOnSelectProgrammaticTab(primaryTab)
                retainedAppShellTabs = retainedAppShellTabs + primaryTab
            }
        }
    }
}

// Moved verbatim out of RaftAppShellReadyContent (JVM method size; #4802, task #102).
@Composable
@NonRestartableComposable
internal fun AppShellRealtimeSinkAndNetworkEffects(
    sharedStores: RaftSharedStoreProvider,
    bridge: build.raft.app.base.BridgeModule,
    appShellServerMembersStore: SharedServerMembersStore,
    appShellRealtimeDomainEventOwner: build.raft.app.data.SharedRealtimeDomainEventOwner,
    appShellAgentDetailStore: SharedAgentDetailStore,
    activeAppShellCanonicalFinalSinksState: MutableState<RealtimeGuardedDomainEventSinks?>,
    appShellCoroutineScope: kotlinx.coroutines.CoroutineScope,
    networkBannerGate: RaftAppShellNetworkBannerGate,
    realtimeEvents: RaftRealtimeEvents,
    hostNetworkEvents: RaftHostNetworkEvents,
    homeShellStore: SharedHomeShellStore,
    latestReadySessionState: State<StoredAuthSession?>,
    membersAgentDetailRefreshKeyState: MutableState<Int>,
) {
    var activeAppShellCanonicalFinalSinks by activeAppShellCanonicalFinalSinksState
    val latestReadySession by latestReadySessionState
    var membersAgentDetailRefreshKey by membersAgentDetailRefreshKeyState
    val agentProfileRefreshCoalescer = remember(appShellCoroutineScope, appShellServerMembersStore) {
        AgentProfileRefreshCoalescer(appShellCoroutineScope) { serverId ->
            appShellServerMembersStore.refreshAgents(
                serverId = build.raft.app.model.ServerId(serverId),
                reason = "realtime_${build.raft.app.data.realtime.RealtimeSocketEvents.AgentUpdated}"
            )
        }
    }
    DisposableEffect(agentProfileRefreshCoalescer) {
        onDispose { agentProfileRefreshCoalescer.cancelAll() }
    }
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
                    // their own projections (and `machine:status` triggers a coalesced
                    // `/agents` recovery at the process realtime owner level), so only
                    // ServerMembers pays for a full roster refetch — except agent:updated
                    // (a profile edit, e.g. a new avatar), which re-reads only the agents,
                    // as web re-reads /api/agents.
                    if (event.isAgentProfileUpdate()) {
                        agentProfileRefreshCoalescer.request(serverId)
                        // An open agent Profile shows `GET /agents/:id`, not the roster: it re-reads too.
                        event.updatedAgentIdOrNull()?.let { agentId ->
                            sharedStores.agentUpdatedSignals.publish(serverId, agentId)
                        }
                    }
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
}
