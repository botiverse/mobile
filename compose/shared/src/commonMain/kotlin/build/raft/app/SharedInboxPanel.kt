package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.components.RaftBrutalBadge
import build.raft.app.components.RaftBrutalTone
import build.raft.app.components.TabPager
import build.raft.app.components.TabPagerGesturePolicy
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED
import build.raft.app.data.ActivityReplyThreadPrewarmKey
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.ActivityUnreadSummaryProjection
import build.raft.app.data.ActivityUpdateTrackingState
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.RaftActivityMarkAction
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeContentState
import androidx.compose.runtime.Immutable
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.activityVisibleSummaryCounts
import build.raft.app.data.hasUnopenableInternalJointParentSource
import build.raft.app.data.isUnfollowedActivityThread
import build.raft.app.data.isFollowedActivityThread
import build.raft.app.data.MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RealInboxRepository
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealReadReceiptRepository
import build.raft.app.data.NetworkRaftThreadApi
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedActivityIntent
import build.raft.app.data.SharedActivitySenderProfile
import build.raft.app.data.SharedActivityPresentationCheckpoint
import build.raft.app.data.SharedActivityPresentationStore
import build.raft.app.data.SharedActivityState
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.data.activityPendingUpdateIncrement
import build.raft.app.data.activityPendingUpdateMarkers
import build.raft.app.data.activityUpdatePresentation
import build.raft.app.data.activityUpdateRevisionMessageId
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import build.raft.app.model.MessageDelegatedBy
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.ThreadConversation
import build.raft.app.model.threadIdentity
import build.raft.app.model.topLevelThreadId
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import build.raft.app.components.RaftPullToRefreshBox
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource
import build.raft.designsystem.components.RaftToast
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.key
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource


@Composable
internal fun SharedHomeActivityScreen(
    bridge: BridgeModule,
    authStore: RaftAuthSessionStore,
    routeVisibleTick: Int = 0,
    active: Boolean = true,
    onOpenMessage: (HomeMessageOpenRequest) -> Unit,
    onClose: (() -> Unit)? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    includeTopSafeArea: Boolean = true,
    timeContext: RaftTimeContext? = null,
    shellScope: RaftAppShellScope = rememberRaftAppShellScope(),
    storeScope: RaftShellStoreScope = RaftShellStoreScope.Legacy,
    activityTaskStatusBadgesEnabled: Boolean = DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED,
    // task #383: secondary-route pages (ComposeHomeActivityPage) never see a
    // Kuikly pageDidAppear on app foreground, so they opt into refreshing on
    // the app-scope appLifecycle resume/show broadcast. Shell surfaces leave
    // this off: the app shell owns foreground reconciliation there.
    refreshOnAppLifecycleResume: Boolean = false,
    // task #45: when the adaptive workspace drives the detail pane, its
    // selection identity (threadId) is the source of truth for the row
    // highlight; a stale checkpoint key must not resurface.
    externalSelectedThreadId: String? = null,
    /**
     * True while the wide list-detail host owns the selection: then a null
     * [externalSelectedThreadId] means nothing is open beside the list, and the row highlight
     * clears with it (the detail closed by Back or ×). On a phone the panel keeps its own.
     */
    externalSelectionOwned: Boolean = false,
    // Activity hosted as an app-shell bottom tab: main-tab header.
    mainTab: Boolean = false,
    // App-shell bottom-tab double tap generation (AppShellTabScrollToTopRequests.activity).
    scrollToTopRequest: Int = 0,
    modifier: Modifier = Modifier
) {
    val pager = LocalActivity.current.getPager() as Pager
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val selectedServerStore = remember(sharedStores, storeScope) { sharedStores.selectedServerStore(storeScope) }
    val repository =
        remember(authStore, networkClient) {
            RealInboxRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("shared_activity $message") },
                warningLogger = { message -> bridge.log("shared_activity_warning $message") }
            )
        }
    val readReceiptRepository =
        remember(authStore, networkClient) {
            RealReadReceiptRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("shared_activity_read $message") },
                warningLogger = { message -> bridge.log("shared_activity_read_warning $message") }
            )
        }
    val conversationRepository =
        remember(authStore, networkClient, selectedServerStore) {
            RealConversationRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                routeServerIdProvider = { selectedServerStore.selectedServerId.value },
                warningLogger = { message -> bridge.log("shared_activity_thread_warning $message") }
            )
        }
    val memberRepository =
        remember(authStore, networkClient) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("shared_activity_member $message") },
                warningLogger = { message -> bridge.log("shared_activity_member_warning $message") }
            )
        }
    // task #399: DM-parent thread rows resolve their peer name through the
    // Home DM list; this repository feeds the activity store's hydrate.
    val channelRepository =
        remember(authStore, networkClient) {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { message -> bridge.log("shared_activity_channel_warning $message") }
            )
        }
    val replyThreadPrewarmDataSource =
        remember(
            authStore,
            selectedServerStore,
            networkClient,
            conversationRepository,
            sharedStores.threadMessageStore,
            sharedStores.threadPayloadStore,
            sharedStores.threadPendingSendJournal,
            sharedStores.apiEnvironmentStore
        ) {
            SharedThreadDataSource(
                networkApi = NetworkRaftThreadApi(networkClient),
                sessionStore = authStore,
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
                threadPageSizeProvider = { serverId ->
                    sharedStores.serverFeatureFlags.snapshotFor(serverId).mobileThreadPageSize()
                },
                diagnosticsLogger = { message -> bridge.log("shared_activity_thread_prewarm $message") }
            )
        }
    // task #436: this call site left the store's diagnostics logger at its no-op
    // default, so the activity feed's load lifecycle was unobservable in shipped
    // builds.
    //
    // The store is shared: SettingsHomePage resolves the same instance for the
    // same shellInstanceId, and updateRuntimeIntegrations overwrites on non-null
    // (last write wins). Injecting a second identity here would silently replace
    // the one already established there and would not be restored when this page
    // closes. Both call sites therefore go through one factory, and it keeps the
    // identity SettingsHomePage already ships so existing consumers see no change.
    val activityDiagnosticsLogger = remember { sharedActivityStoreDiagnosticsLogger() }
    val activityStore =
        remember(
            storeScope,
            authStore,
            repository,
            readReceiptRepository,
            conversationRepository,
            memberRepository,
            channelRepository,
            replyThreadPrewarmDataSource
        ) {
            sharedStores.getOrCreateHomeActivityStore(
                scope = storeScope,
                inboxRepository = repository,
                readReceiptRepository = readReceiptRepository,
                conversationRepository = conversationRepository,
                memberRepository = memberRepository,
                channelRepository = channelRepository,
                prewarmReplyThread = replyThreadPrewarmDataSource::prewarmReplyThread,
                replyThreadContentCached = replyThreadPrewarmDataSource::hasCurrentReplyThreadContent,
                diagnosticsLogger = activityDiagnosticsLogger
            )
        }
    val state by activityStore.state.collectAsState()
    val activitySummaryServerId =
        remember(state.ownerServerId, state.snapshot?.server?.id?.value) {
            ServerId(state.ownerServerId ?: state.snapshot?.server?.id?.value.orEmpty())
        }
    val activitySummaryFlow =
        remember(sharedStores.activityUnreadSummaryStore, activitySummaryServerId) {
            sharedStores.activityUnreadSummaryStore.projectionFlow(activitySummaryServerId)
        }
    val activitySummary by activitySummaryFlow.collectAsState(
        sharedStores.activityUnreadSummaryStore.currentProjection(activitySummaryServerId)
    )
    val projectedState =
        remember(state, activitySummary) {
            projectActivityStateWithProcessSummary(state, activitySummary)
        }
    val safeOpenProjectedState =
        remember(projectedState) {
            projectActivityStateWithoutUnopenableInternalJointRows(projectedState)
        }
    val diagnosticOverrideRevision by
        ProcessDiagnosticFeatureFlagOverrides.registry
            .revisionFor(MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY)
            .collectAsState()
    val stableOrderEnabledAtRouteEntry =
        remember(activityStore, state.ownerUserId, activitySummaryServerId, diagnosticOverrideRevision) {
            val serverEffective =
                sharedStores.serverFeatureFlags.isEnabled(
                    activitySummaryServerId,
                    MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY
                )
            resolveActivityStableOrderEnabled(
                registry = ProcessDiagnosticFeatureFlagOverrides.registry,
                serverEffective = serverEffective,
                environmentId = sharedStores.apiEnvironmentStore.current().apiBaseUrl,
                accountId = state.ownerUserId.orEmpty(),
                serverId = activitySummaryServerId.value,
            )
        }
    val sharedHomeStore = remember(sharedStores, storeScope) {
        sharedStores.sharedHomeStore(storeScope)
    }
    // Selector over the Home content: only the pins, mutes and DM peers of this server reach the list.
    // Null while Home has not produced them yet (see nextActivityConversationListContext): Home facts
    // never apply after the rows have painted.
    val conversationListContextSource =
        remember(sharedHomeStore, activitySummaryServerId) {
            val serverId = activitySummaryServerId.value
            if (serverId.isBlank()) {
                ActivityConversationListContext.Empty to flowOf(ActivityConversationListContext.Empty)
            } else if (sharedHomeStore == null) {
                val unavailable = nextActivityConversationListContext(null, null, serverId)
                unavailable to flowOf(unavailable)
            } else {
                val initial = nextActivityConversationListContext(null, sharedHomeStore.state.value.content, serverId)
                initial to
                    sharedHomeStore.state
                        .map { homeState -> homeState.content }
                        .distinctUntilChanged()
                        .runningFold(initial) { previous, content ->
                            nextActivityConversationListContext(previous, content, serverId)
                        }
                        .distinctUntilChanged()
            }
        }
    val conversationListContext by
        conversationListContextSource.second.collectAsState(conversationListContextSource.first)
    val conversationListPending = conversationListContext == null
    val conversationListAgentStatusResolver =
        remember(sharedStores.serverMembersStore, activitySummaryServerId) {
            raftScopedAgentStatusResolver(sharedStores.serverMembersStore, activitySummaryServerId.value)
        }
    // Activity faces (senders, DM peers) load from this server's member directory, like Home DM rows.
    val activityAvatarSource =
        remember(sharedStores.serverMembersStore, activitySummaryServerId) {
            val serverId = activitySummaryServerId.value.takeIf { it.isNotBlank() }
            ActivityAvatarSource(
                memberAvatarResolver = raftMemberAvatarResolver(sharedStores.serverMembersStore, serverId),
                cacheServerId = serverId,
            )
        }
    // Web InboxContextMenu: a channel row's menu mutes or unmutes that channel's activity, read
    // from and written through the Home sidebar state of this server (the same intent as Home's
    // long-press).
    val activityMuteSource =
        remember(sharedHomeStore, activitySummaryServerId) {
            val serverId = activitySummaryServerId.value
            sharedHomeStore?.state
                ?.map { homeState -> homeState.content.activityMuteByChannelId(serverId) }
                ?.distinctUntilChanged()
                ?: flowOf(emptyMap())
        }
    val activityMuteByChannelId by activityMuteSource.collectAsState(emptyMap())
    val channelMute =
        remember(sharedHomeStore, activityMuteByChannelId) {
            sharedHomeStore?.let { store ->
                ActivityChannelMuteControl(activityMuteByChannelId) { channelId, muted ->
                    store.dispatch(SharedHomeIntent.SetChannelActivityMuted(channelId, muted))
                }
            }
        }
    val conversationList =
        remember(conversationListContext, conversationListAgentStatusResolver) {
            ActivityConversationListPresentation(
                context = conversationListContext ?: ActivityConversationListContext.Empty,
                agentStatusResolver = conversationListAgentStatusResolver
            )
        }
    // t5/#419 A3: user-initiated opens resolve against the real conversation directory
    // (flow-based single source, #240) with the snapshot's real server id — the resolver is
    // the only exit; Pending is an explainable failure, never a guessed channel-shaped nav.
    val conversationDirectoryFlow =
        remember(sharedStores, state.snapshot?.server?.id) {
            state.snapshot?.server?.id
                ?.let { serverId -> sharedStores.conversationIdentityProvider(storeScope)?.observeServer(serverId) }
                ?: flowOf(SharedConversationDirectoryState())
        }
    val conversationDirectory by conversationDirectoryFlow.collectAsState(SharedConversationDirectoryState())
    val activityConversationDirectory = remember(state.snapshot, state.dmPeerNames) {
        state.snapshot?.toSharedConversationDirectoryState(dmPeerNames = state.dmPeerNames)
            ?: SharedConversationDirectoryState()
    }
    val openConversationDirectory = remember(activityConversationDirectory, conversationDirectory) {
        listOf(activityConversationDirectory, conversationDirectory).mergedSharedConversationDirectoryState()
    }
    val activeDraftRecords by sharedStores.composerDraftPersistSlice.activeDraftRecordsState.collectAsState(emptySet())
    val draftProjection =
        remember(activeDraftRecords) {
            activeDraftRecords.toSharedThreadComposerDraftProjection()
        }
    val conversationNotReadyMessage = stringResource(MR.strings.inbox_error_conversation_not_ready)
    LaunchedEffect(activityStore) {
        // task #436: the probe must run from a COMMITTED effect, not from a
        // remember calculation. Composition can be abandoned or retried, so a
        // composition-time side effect can emit for a page that never mounts and
        // never dispatches a load — which would make the control claim "mounted"
        // for a path that did not run. Emitted immediately before the initial
        // dispatch so probe-then-start is a fixed order.
        //
        // Positive evidence only: seeing it proves the channel delivered at this
        // level in this build. A missing line — this one or any other — means
        // "not observed": delivery crosses two bounded queues that drop silently,
        // so it never establishes that the load failed to start or to terminate.
        activityDiagnosticsLogger("store_load domain=activity phase=probe channel=live")
        activityStore.dispatch(SharedActivityIntent.LoadInitial)
    }
    LaunchedEffect(sharedStores.activityUnreadSummaryStore, activitySummaryServerId) {
        if (activitySummaryServerId.value.isNotBlank()) {
            sharedStores.activityUnreadSummaryStore.ensure(
                activitySummaryServerId,
                reason = "activity_route_attach"
            )
        }
    }
    LaunchedEffect(sharedHomeStore, state.snapshot, state.dmPeerNames) {
        state.snapshot?.let { snapshot ->
            sharedHomeStore?.supplementConversationIdentity(snapshot, dmPeerNames = state.dmPeerNames)
        }
    }
    // Task #60: the app-shell Activity tab stays composed (keep-alive) while another tab is
    // selected, and it still receives every shell pageDidAppear tick. Refreshing then cost a
    // Foreground-lane inbox load plus a full Activity/Home identity republish on every return
    // from background for a list nobody sees; the `active` edge below refreshes it on entry.
    val latestActive by rememberUpdatedState(active)
    LaunchedEffect(routeVisibleTick) {
        if (activityPageDidAppearShouldRefresh(routeVisibleTick, active = latestActive)) {
            activityStore.dispatch(SharedActivityIntent.Refresh)
        }
    }
    if (refreshOnAppLifecycleResume) {
        val realtimeEvents = remember(sharedStores) { RaftRealtimeEvents(sharedStores.eventBus) }
        DisposableEffect(realtimeEvents, activityStore) {
            val subscription =
                realtimeEvents.addListener { data ->
                    if (
                        activityAppLifecycleResumeShouldRefresh(
                            action = data.optString(RaftRealtimeNotifyContract.KeyAction),
                            lifecycleEvent = data.optString(RaftRealtimeNotifyContract.KeyLifecycleEvent)
                        )
                    ) {
                        bridge.log("shared_activity_app_lifecycle_resume_refresh")
                        activityStore.dispatch(SharedActivityIntent.Refresh)
                    }
                }
            onDispose { realtimeEvents.removeListener(subscription) }
        }
    }
    // Every entry into the kept-alive Activity tab (tab switch, rail, return from a pushed page)
    // refreshes once; nothing fires while another tab is shown. The shell's SetSelectedTab and
    // the pageDidAppear tick may dispatch in the same frame: the store joins those into one load
    // (ActivityRefreshCoalesceWindowMillis) and supersedes a stale one instead of swallowing it.
    val entryRefreshEdge = remember { ActivityTabEntryRefreshEdge(initiallyActive = active) }
    LaunchedEffect(active) {
        when (entryRefreshEdge.onActiveChanged(active)) {
            ActivityTabActiveEdge.Entered -> {
                bridge.log("shared_activity_entry_refresh source=active_edge")
                activityStore.dispatch(SharedActivityIntent.Refresh)
            }
            ActivityTabActiveEdge.Left -> activityStore.dispatch(SharedActivityIntent.SurfaceHidden)
            ActivityTabActiveEdge.None -> Unit
        }
    }
    val lastOpenedStore = remember { ActivityLastOpenedStore(raftSharedPreferenceStore()) }
    // Every open (a picked row, or the reopen on arrival below) is the one to reopen next time.
    val openActivityItem: (InboxItem) -> Unit = { item ->
        // Every opened row (also the one the wide layout shows beside the list) joins the rows
        // retained for this visit: read in Unread, it stays listed until the user moves on.
        activityStore.dispatch(SharedActivityIntent.RowOpened(item))
        lastOpenedStore.write(activitySummaryServerId.value, item.key)
        state.snapshot?.let { snapshot ->
            sharedHomeStore?.supplementConversationIdentity(snapshot, dmPeerNames = state.dmPeerNames)
        }
        when (
            val resolution =
                item.toHomeMessageOpenRequest(
                    serverId = state.snapshot?.server?.id?.value.orEmpty(),
                    conversationDirectory = openConversationDirectory,
                    diagnosticsLogger = bridge::log
                )
        ) {
            is ConversationOpenResolution.Resolved -> onOpenMessage(resolution.request)
            is ConversationOpenResolution.Pending -> {
                bridge.logWarn(
                    "activity_open_request_pending reason=${resolution.reason} " +
                        "itemKey=${item.key} kind=${item.kind.name}"
                )
                bridge.toast(conversationNotReadyMessage)
            }
        }
    }
    // Wide layout (the list-detail host owns the selection): once per visit, after the list loads,
    // reopen the conversation opened last time on this server when it is still listed and nothing
    // is open beside the list (ActivityArrivalReopen.kt). Not a pick: no MarkRead, no prewarm.
    val arrivalVisit = remember(activitySummaryServerId) { ActivityArrivalVisit() }
    val latestOpenActivityItem by rememberUpdatedState(openActivityItem)
    val arrivalItems = safeOpenProjectedState.snapshot?.items
    val arrivalLoaded = state.loaded && arrivalItems != null && !conversationListPending
    LaunchedEffect(arrivalVisit, active, arrivalLoaded) {
        if (!arrivalVisit.claim(active = active, loaded = arrivalLoaded)) return@LaunchedEffect
        val item =
            activityArrivalReopenTarget(
                wide = externalSelectionOwned,
                somethingOpen = externalSelectedThreadId != null,
                items = arrivalItems.orEmpty(),
                lastOpenedKey = lastOpenedStore.read(activitySummaryServerId.value),
            ) ?: return@LaunchedEffect
        bridge.log("shared_activity_arrival_reopen kind=${item.kind.name} unread=${item.unreadCount}")
        latestOpenActivityItem(item)
    }
    // web: pinning or unpinning while Pinned is shown reloads it (the server resolves the pins).
    val pinnedScopeReloadEdge = remember(activitySummaryServerId) { ActivityPinnedScopeReloadEdge() }
    val latestSelectedFilter by rememberUpdatedState(state.selectedFilter)
    val sidebarPins = conversationListContext?.pinnedRefs
    LaunchedEffect(pinnedScopeReloadEdge, sidebarPins) {
        if (pinnedScopeReloadEdge.onPins(sidebarPins, latestSelectedFilter)) {
            bridge.log("shared_activity_pinned_scope_reload reason=pins_changed")
            activityStore.dispatch(SharedActivityIntent.Refresh)
        }
    }
    CompositionLocalProvider(
        LocalActivityAvatarSource provides activityAvatarSource,
    ) {
    SharedActivityPanelContent(
        state = safeOpenProjectedState,
        onOpenItem = openActivityItem,
        onClose = onClose,
        onIntent = activityStore::dispatch,
        bottomContentPadding = bottomContentPadding,
        topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
        timeContext = timeContext,
        activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
        draftProjection = draftProjection,
        stableOrderEnabled = stableOrderEnabledAtRouteEntry,
        stableOrderDecisionRevision = diagnosticOverrideRevision,
        presentationStore = activityStore.presentationStore,
        foregroundEpoch = activitySummary.lastForegroundEpoch,
        externalSelectedThreadId = externalSelectedThreadId,
        externalSelectionOwned = externalSelectionOwned,
        mainTab = mainTab,
        conversationList = conversationList,
        channelMute = channelMute,
        conversationListPending = conversationListPending,
        scrollToTopRequest = scrollToTopRequest,
        modifier = modifier
    )
    }
}

internal fun resolveActivityStableOrderEnabled(
    registry: DiagnosticFeatureFlagOverrideRegistry,
    serverEffective: Boolean,
    environmentId: String,
    accountId: String,
    serverId: String,
): Boolean =
    registry.decide(
        key = MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY,
        serverEffective = serverEffective,
        scope =
            DiagnosticFeatureFlagScope(
                environmentId = environmentId.trim().trimEnd('/'),
                accountId = accountId,
                serverId = serverId,
            ),
    ).finalEffective

internal fun activityPageDidAppearShouldRefresh(routeVisibleTick: Int, active: Boolean = true): Boolean =
    routeVisibleTick > 0 && active

/**
 * The Activity panel's inactive -> active edge. The first composition is not an entry (its
 * mount-time LoadInitial loads), so a panel composed while active does not refresh twice, and one
 * composed hidden (kept alive, prewarmed, rebuilt by a server switch) refreshes on its first
 * entry. Plain state, not snapshot state: the edge is read and written only by the effect.
 */
internal class ActivityTabEntryRefreshEdge(initiallyActive: Boolean) {
    private var wasActive = initiallyActive

    /** Records [active]: [ActivityTabActiveEdge.Entered] exactly on inactive -> active. */
    fun onActiveChanged(active: Boolean): ActivityTabActiveEdge {
        val edge =
            when {
                active && !wasActive -> ActivityTabActiveEdge.Entered
                !active && wasActive -> ActivityTabActiveEdge.Left
                else -> ActivityTabActiveEdge.None
            }
        wasActive = active
        return edge
    }
}

internal enum class ActivityTabActiveEdge { Entered, Left, None }

/**
 * task #383: app-foreground resume arrives as the app-scope appLifecycle
 * broadcast ("resume" on Android, "show" on iOS) rather than as a Kuikly
 * pageDidAppear, so secondary-route surfaces match on those two wire values.
 */
internal fun activityAppLifecycleResumeShouldRefresh(action: String, lifecycleEvent: String): Boolean =
    action == RaftRealtimeNotifyContract.ActionAppLifecycle &&
        (
            lifecycleEvent == RaftRealtimeNotifyContract.LifecycleVisibleResume ||
                lifecycleEvent == RaftRealtimeNotifyContract.LifecycleVisibleShow
            )

internal data class ActivityRefreshPresentation(
    val headerRefreshing: Boolean,
    val pullRefreshing: Boolean
)

/**
 * Single diagnostics identity for the shared activity store (task #436).
 *
 * `SharedInboxPanel` and `SettingsHomePage` resolve the same store instance for
 * the same shellInstanceId, and `updateRuntimeIntegrations` overwrites the
 * logger on non-null. Two identities would mean whichever composed last wins,
 * with no restore on close. Every call site must use this factory.
 *
 * The tag/prefix are the ones `SettingsHomePage` already shipped, so wiring the
 * previously-unlogged call site does not change what existing consumers grep.
 */
internal fun sharedActivityStoreDiagnosticsLogger(): (String) -> Unit {
    val delegate = raftLogger("SlockHomeActivity", "shared_home_activity")
    return { message -> delegate(stampSharedActivityDiagnosticsSequence(message)) }
}

/**
 * Stamping is separated from delivery so the uniqueness contract can be tested
 * deterministically. Exercised through the logger it cannot be: the queue drops
 * under admission contention, so a duplicated number could be hidden by one of
 * the pair being dropped and the test would pass on a broken counter.
 */
internal fun stampSharedActivityDiagnosticsSequence(message: String): String =
    "seq=${sharedActivityDiagnosticsSequence.incrementAndGet()} $message"

/**
 * Allocation-order sequence stamped on every line this logger emits (task #436).
 *
 * Without it these diagnostics can only *falsify* a stuck load, never confirm
 * one: delivery crosses two bounded queues, so a missing terminal line has two
 * explanations — never emitted, or emitted and dropped — and the reader cannot
 * separate them. That leaves the instrument unable to answer the question it
 * was built for.
 *
 * READ THE SET, NOT THE ARRIVAL ORDER. The number is allocated here, *before*
 * the queues, which is what makes a drop visible at all — a dropped line still
 * consumed its number, so its value is absent from the set. But allocation and
 * enqueue are not one atomic step, and these lines are emitted from several
 * coroutines, so a thread can take a number and be suspended while a later
 * number is enqueued ahead of it. Logcat may therefore show 2 before 1.
 *
 * Interpretation, over the sorted set of observed values:
 *
 *   gap in the set   -> something was dropped; absence is unattributable
 *   set contiguous   -> nothing dropped in that window, so a missing line
 *                       really was never emitted
 *
 * Reordering cannot hide a drop: numbers are unique, so a missing value is
 * missing whatever order the survivors arrive in. It can only cause a *false*
 * gap — a number still in flight when observation stopped — which fails safe
 * (the round is discarded, never wrongly confirmed). Hence a quiescence period
 * before judging a gap.
 *
 * This still does not close the tail: a line emitted after the last observed
 * one and then dropped leaves a contiguous set. Only a subsequent record
 * closes that, which is an operator-side seal, not a property of this counter.
 *
 * Deliberately module-scoped rather than per-logger: both call sites build a
 * logger, and two independent counters would make the sets uncomparable.
 */
private val sharedActivityDiagnosticsSequence = atomic(0L)

@Suppress("UNUSED_PARAMETER")
internal fun activityRefreshPresentation(
    refreshing: Boolean,
    pullRefreshing: Boolean
): ActivityRefreshPresentation =
    ActivityRefreshPresentation(
        // Background/re-entry baselines preserve loaded content and must not
        // present as a continuously refreshing page. Only the user's explicit
        // pull gesture owns visible refresh chrome, and that chrome is the pull
        // indicator alone: the header spinner would show it twice (artin task #22).
        headerRefreshing = false,
        pullRefreshing = pullRefreshing
    )

internal fun projectActivityStateWithProcessSummary(
    state: SharedActivityState,
    activitySummary: ActivityUnreadSummaryProjection
): SharedActivityState {
    val snapshot = state.snapshot
    if (
        snapshot == null ||
        state.selectedFilter != InboxFilter.All ||
        !activitySummary.hasValue ||
        snapshot.items.any { item -> item.hasUnopenableInternalJointParentSource() }
    ) {
        return state
    }
    return state.copy(
        snapshot =
            snapshot.copy(
                totalUnreadCount = activitySummary.totalUnreadCount,
                totalCount = activitySummary.totalCount
            )
    )
}

// Presentation-only stop-loss. Canonical/persisted Activity state remains
// untouched; a typed joint reply row whose own source parent is an internal
// storage identity is hidden because the Activity resolver refuses that same
// source tuple. Counts are derived from the identical filtered set, and a
// later member-visible local tuple naturally reappears in its original order.
internal fun projectActivityStateWithoutUnopenableInternalJointRows(
    state: SharedActivityState
): SharedActivityState {
    val snapshot = state.snapshot ?: return state
    val suppressed = snapshot.items.filter { item -> item.hasUnopenableInternalJointParentSource() }
    if (suppressed.isEmpty()) return state
    val visibleItems = snapshot.items.filterNot { item -> item.hasUnopenableInternalJointParentSource() }
    val (visibleUnreadCount, visibleTotalCount) = snapshot.activityVisibleSummaryCounts()
    return state.copy(
        snapshot =
            snapshot.copy(
                items = visibleItems,
                totalCount = visibleTotalCount,
                totalUnreadCount = visibleUnreadCount
            )
    )
}

@Composable
internal fun SharedHomeActivityScreen(
    state: SharedActivityState,
    onIntent: (SharedActivityIntent) -> Unit,
    onOpenItem: (InboxItem) -> Unit,
    onClose: (() -> Unit)? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    includeTopSafeArea: Boolean = true,
    timeContext: RaftTimeContext? = null,
    shellScope: RaftAppShellScope = rememberRaftAppShellScope(),
    activityTaskStatusBadgesEnabled: Boolean = DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    stableOrderEnabled: Boolean = true,
    stableOrderDecisionRevision: Long = 0L,
    presentationStore: SharedActivityPresentationStore = remember { SharedActivityPresentationStore() },
    foregroundEpoch: Long,
    modifier: Modifier = Modifier,
    externalSelectedThreadId: String? = null,
    /**
     * True while the wide list-detail host owns the selection: then a null
     * [externalSelectedThreadId] means nothing is open beside the list, and the row highlight
     * clears with it (the detail closed by Back or ×). On a phone the panel keeps its own.
     */
    externalSelectionOwned: Boolean = false,
    mainTab: Boolean = false,
    conversationList: ActivityConversationListPresentation = ActivityConversationListPresentation(),
    channelMute: ActivityChannelMuteControl? = null
) {
    SharedActivityPanelContent(
        state = state,
        onOpenItem = onOpenItem,
        onClose = onClose,
        onIntent = onIntent,
        bottomContentPadding = bottomContentPadding,
        topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
        timeContext = timeContext,
        activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
        draftProjection = draftProjection,
        stableOrderEnabled = stableOrderEnabled,
        stableOrderDecisionRevision = stableOrderDecisionRevision,
        presentationStore = presentationStore,
        foregroundEpoch = 0L,
        externalSelectedThreadId = externalSelectedThreadId,
        externalSelectionOwned = externalSelectionOwned,
        mainTab = mainTab,
        conversationList = conversationList,
        channelMute = channelMute,
        modifier = modifier
    )
}

@Composable
internal fun SharedHomeActivityPanel(
    snapshot: InboxSnapshot,
    selectedFilter: InboxFilter = InboxFilter.All,
    onOpenItem: (InboxItem) -> Unit = {},
    onClose: (() -> Unit)? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    includeTopSafeArea: Boolean = false,
    pullRefreshing: Boolean = false,
    timeContext: RaftTimeContext? = null,
    shellScope: RaftAppShellScope = rememberRaftAppShellScope(),
    activityTaskStatusBadgesEnabled: Boolean = DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    stableOrderEnabled: Boolean = true,
    stableOrderDecisionRevision: Long = 0L,
    presentationStore: SharedActivityPresentationStore = remember { SharedActivityPresentationStore() },
    foregroundEpoch: Long,
    modifier: Modifier = Modifier,
    externalSelectedThreadId: String? = null,
    /**
     * True while the wide list-detail host owns the selection: then a null
     * [externalSelectedThreadId] means nothing is open beside the list, and the row highlight
     * clears with it (the detail closed by Back or ×). On a phone the panel keeps its own.
     */
    externalSelectionOwned: Boolean = false,
    conversationList: ActivityConversationListPresentation = ActivityConversationListPresentation(),
    channelMute: ActivityChannelMuteControl? = null
) {
    // #192 trace scope extension: wrap the Inbox panel render. The Inbox is
    // recomposed on every state snapshot change; this marker tells us how much
    // wall-time the panel render itself costs on the UI thread (excluding
    // inner List measure which we'd add separately if this shows hot).
    traceMarkdownSection(RaftInboxTraceNames.PANEL_RENDER, snapshot.items.size) {
        SharedActivityPanelContent(
            state =
                SharedActivityState(
                    selectedFilter = selectedFilter,
                    snapshot = snapshot,
                    loaded = true,
                    pullRefreshing = pullRefreshing,
                ),
            onOpenItem = onOpenItem,
            onClose = onClose,
            onIntent = {},
            bottomContentPadding = bottomContentPadding,
            topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
            timeContext = timeContext,
            activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
            draftProjection = draftProjection,
            stableOrderEnabled = stableOrderEnabled,
            stableOrderDecisionRevision = stableOrderDecisionRevision,
            presentationStore = presentationStore,
            foregroundEpoch = foregroundEpoch,
            externalSelectedThreadId = externalSelectedThreadId,
            externalSelectionOwned = externalSelectionOwned,
            conversationList = conversationList,
            channelMute = channelMute,
            modifier = modifier
        )
    }
}

internal fun projectActivityStateForFilterPage(
    state: SharedActivityState,
    pageFilter: InboxFilter
): SharedActivityState {
    if (state.selectedFilter == pageFilter) return state
    val cachedSnapshot = state.filterSnapshots[pageFilter]
    return state.copy(
        selectedFilter = pageFilter,
        snapshot = cachedSnapshot,
        loaded = cachedSnapshot != null,
        loading = cachedSnapshot == null
    )
}

@Composable
private fun SharedActivityPanelContent(
    state: SharedActivityState,
    onOpenItem: (InboxItem) -> Unit,
    onClose: (() -> Unit)?,
    onIntent: (SharedActivityIntent) -> Unit,
    bottomContentPadding: Dp,
    topSafeAreaPadding: Dp,
    timeContext: RaftTimeContext?,
    activityTaskStatusBadgesEnabled: Boolean,
    draftProjection: SharedThreadComposerDraftProjection,
    stableOrderEnabled: Boolean,
    stableOrderDecisionRevision: Long,
    presentationStore: SharedActivityPresentationStore,
    foregroundEpoch: Long,
    modifier: Modifier = Modifier,
    externalSelectedThreadId: String? = null,
    /**
     * True while the wide list-detail host owns the selection: then a null
     * [externalSelectedThreadId] means nothing is open beside the list, and the row highlight
     * clears with it (the detail closed by Back or ×). On a phone the panel keeps its own.
     */
    externalSelectionOwned: Boolean = false,
    mainTab: Boolean = false,
    conversationList: ActivityConversationListPresentation = ActivityConversationListPresentation(),
    channelMute: ActivityChannelMuteControl? = null,
    /** The list layout's flag or Home context is not decided yet: keep the loading state, paint nothing. */
    conversationListPending: Boolean = false,
    scrollToTopRequest: Int = 0
) {
    val refreshPresentation = activityRefreshPresentation(state.refreshing, state.pullRefreshing)
    // task #232 (#2224 review blocker 1): the panel owns per-page scroll
    // states, so it must also own title-tap scroll-to-top — a caller-held
    // LazyListState attaches to no list here and dies silently (the parameter
    // was removed so that wiring cannot come back). The title tap targets the
    // SAME state object the selected filter's page renders with, via
    // [activityScrollToTopTarget] (mapping unit-tested).
    val filters = InboxFilter.entries
    // One retained scroll state per filter page. Explicit calls (not a
    // loop over the experimental LazyListState constructor): the filter
    // set is a closed enum of four, and this list is index-aligned with
    // `filters` (All/Unread/Mentions/Pinned entry order).
    val pageListStates = listOf(
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
    )
    val scrollToTopScope = rememberCoroutineScope()
    // Head tap and bottom-tab re-tap both scroll the selected filter's page back to the top, like the
    // other tabs (the unread walk was removed, artin 2026-10-23).
    suspend fun scrollSelectedPageToTop() {
        activityScrollToTopTarget(state.selectedFilter, pageListStates).animateScrollToTopExpressive()
    }
    // Bottom-tab double tap targets the same list the title tap does (the selected filter's page).
    val scrollToTopRequestFence = remember { AppShellScrollToTopRequestFence(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequestFence.consume(scrollToTopRequest)) {
            scrollSelectedPageToTop()
        }
    }
    Column(
        modifier = modifier.fillMaxSize().background(RaftColor.SurfaceWhite)
    ) {
        SharedActivityHeader(
            totalCount = state.totalCount,
            totalUnreadCount = state.totalUnreadCount,
            onClose = onClose,
            mainTab = mainTab,
            topSafeAreaPadding = topSafeAreaPadding,
            refreshing = refreshPresentation.headerRefreshing,
            onTitleClick = {
                scrollToTopScope.launch { scrollSelectedPageToTop() }
            }
        )
        RaftAppShellBodyTopBanner()
        // Task #164 (stdrc O2): under the head, above the filters; the rows and counts stay.
        if (state.stale) {
            RaftReadWarehouseStaleNotice(stringResource(MR.strings.activity_stale_notice))
        }
        // task #232: filter tabs are a TabPager — the filter bar stays the tap
        // surface (its SetFilter flows back in as selectedIndex and animates
        // the pager); a horizontal swipe settling on a page dispatches the
        // same SetFilter. The store keeps ONE snapshot (the selected
        // filter's), so only the active page renders real content; a
        // neighbor glimpsed mid-drag shows that filter's loading skeleton
        // until the settle re-targets the store. keepItemAlive keeps hidden
        // pages composed, so the page body gates its shared-state effects on
        // the pager's `active` flag.
        TabPager(
            selectedIndex = filters.indexOf(state.selectedFilter).coerceAtLeast(0),
            onSelectedIndexChange = { index -> onIntent(SharedActivityIntent.SetFilter(filters[index])) },
            pageCount = filters.size,
            gesturePolicy = TabPagerGesturePolicy(
                startEdgeInset = raftPlatformTabPagerStartEdgeInsetDp().dp,
            ),
            keepItemAlive = true,
            tabBar = {
                SharedActivityFilterBar(
                    selectedFilter = state.selectedFilter,
                    totalUnreadCount = state.totalUnreadCount,
                    onFilterSelected = { onIntent(SharedActivityIntent.SetFilter(it)) },
                    onMarkAllRead = {
                        RaftProductEvents.activityMark(RaftActivityMarkAction.ReadAll)
                        onIntent(SharedActivityIntent.MarkAllRead)
                    }
                )
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page, active ->
            val pageFilter = filters[page]
            val pageState = projectActivityStateForFilterPage(state, pageFilter)
            if (pageState.snapshot != null && !conversationListPending) {
                SharedActivityBodyContent(
                    active = active,
                    state = pageState,
                    onOpenItem = onOpenItem,
                    onIntent = onIntent,
                    bottomContentPadding = bottomContentPadding,
                    timeContext = timeContext,
                    listState = pageListStates[page],
                    activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                    draftProjection = draftProjection,
                    presentationAccountId = state.ownerUserId.orEmpty(),
                    stableOrderEnabled = stableOrderEnabled,
                    stableOrderDecisionRevision = stableOrderDecisionRevision,
                    presentationStore = presentationStore,
                    foregroundEpoch = foregroundEpoch,
                    externalSelectedThreadId = externalSelectedThreadId,
                    externalSelectionOwned = externalSelectionOwned,
                    conversationList = conversationList,
                    channelMute = channelMute,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                SharedActivityStateBody(
                    title = stringResource(MR.strings.common_loading),
                    detail = "",
                    loading = true,
                    bottomContentPadding = bottomContentPadding
                )
            }
        }
    }
}

/**
 * The list the Activity title tap must scroll: the one the selected filter's
 * page renders with. Index-aligned with [InboxFilter.entries] exactly like the
 * pager's `selectedIndex` and its per-page `pageListStates` list — this seam
 * exists so a unit test ([ActivityScrollToTopTargetTest]) pins the mapping;
 * the tap→scroll wiring itself lives ten lines from the states it targets and
 * cannot be re-cut from a call site (#2224 review blocker 1: the previous
 * design had callers hand in a LazyListState that attached to nothing).
 */
internal fun <T> activityScrollToTopTarget(
    selectedFilter: InboxFilter,
    pageStates: List<T>,
): T = pageStates[InboxFilter.entries.indexOf(selectedFilter).coerceAtLeast(0)]

@Composable
private fun SharedActivityBodyContent(
    active: Boolean,
    state: SharedActivityState,
    onOpenItem: (InboxItem) -> Unit,
    onIntent: (SharedActivityIntent) -> Unit,
    bottomContentPadding: Dp,
    timeContext: RaftTimeContext?,
    listState: LazyListState,
    activityTaskStatusBadgesEnabled: Boolean,
    draftProjection: SharedThreadComposerDraftProjection,
    presentationAccountId: String,
    stableOrderEnabled: Boolean,
    stableOrderDecisionRevision: Long,
    presentationStore: SharedActivityPresentationStore,
    foregroundEpoch: Long,
    externalSelectedThreadId: String? = null,
    /**
     * True while the wide list-detail host owns the selection: then a null
     * [externalSelectedThreadId] means nothing is open beside the list, and the row highlight
     * clears with it (the detail closed by Back or ×). On a phone the panel keeps its own.
     */
    externalSelectionOwned: Boolean = false,
    conversationList: ActivityConversationListPresentation = ActivityConversationListPresentation(),
    channelMute: ActivityChannelMuteControl? = null,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot
    val userFacingErrorLabels = threadUserFacingErrorLabels()
    Box(modifier = modifier) {
        when {
            snapshot == null && state.loading ->
                SharedActivityStateBody(
                    title = stringResource(MR.strings.common_loading),
                    detail = "",
                    loading = true,
                    bottomContentPadding = bottomContentPadding
                )
            snapshot == null && state.errorCode != null ->
                SharedActivityRefreshableStateBody(
                    title = stringResource(MR.strings.inbox_error_load_activity),
                    detail =
                        userFacingErrorLabels.forErrorCode(state.errorCode)
                            ?: userFacingErrorLabels.generic(),
                    actionLabel = if (state.refreshing || state.loading) "…" else stringResource(MR.strings.common_retry),
                    onAction = { onIntent(SharedActivityIntent.Refresh) },
                    refreshing = state.refreshing,
                    pullRefreshing = state.pullRefreshing,
                    onRefresh = { onIntent(SharedActivityIntent.PullRefresh) },
                    bottomContentPadding = bottomContentPadding,
                    listState = listState
                )
            snapshot == null ->
                SharedActivityRefreshableStateBody(
                    title = stringResource(MR.strings.inbox_state_activity_empty),
                    detail = state.selectedFilter.localizedEmptyCopy(),
                    refreshing = state.refreshing,
                    pullRefreshing = state.pullRefreshing,
                    onRefresh = { onIntent(SharedActivityIntent.PullRefresh) },
                    bottomContentPadding = bottomContentPadding,
                    listState = listState
                )
            snapshot.items.isEmpty() ->
                SharedActivityRefreshableStateBody(
                    title =
                        when (state.selectedFilter) {
                            InboxFilter.Mentions -> stringResource(MR.strings.inbox_state_no_mentions)
                            InboxFilter.Unread -> stringResource(MR.strings.inbox_state_no_unread_chats)
                            InboxFilter.All -> stringResource(MR.strings.inbox_state_activity_empty)
                            InboxFilter.Pinned -> stringResource(MR.strings.inbox_state_no_pinned)
                        },
                    detail = state.selectedFilter.localizedEmptyCopy(),
                    refreshing = state.refreshing,
                    pullRefreshing = state.pullRefreshing,
                    onRefresh = { onIntent(SharedActivityIntent.PullRefresh) },
                    bottomContentPadding = bottomContentPadding,
                    listState = listState
                )
            else ->
                SharedActivityList(
                    active = active,
                    snapshot = remember(snapshot, draftProjection) {
                        snapshot.withComposerDraftProjection(draftProjection)
                    },
                    selectedFilter = state.selectedFilter,
                    senderProfiles = state.senderProfiles,
                    dmPeerNames = state.dmPeerNames,
                    refreshing = state.refreshing,
                    pullRefreshing = state.pullRefreshing,
                    loadingMore = state.loadingMore,
                    loadMoreErrorCode = state.loadMoreErrorCode,
                    onOpenItem = onOpenItem,
                    onIntent = onIntent,
                    bottomContentPadding = bottomContentPadding,
                    timeContext = timeContext,
                    listState = listState,
                    activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                    presentationAccountId = presentationAccountId,
                    stableOrderEnabled = stableOrderEnabled,
                    stableOrderDecisionRevision = stableOrderDecisionRevision,
                    presentationStore = presentationStore,
                    foregroundEpoch = foregroundEpoch,
                    externalSelectedThreadId = externalSelectedThreadId,
                    externalSelectionOwned = externalSelectionOwned,
                    conversationList = conversationList,
                    channelMute = channelMute
                )
        }
    }
}

@Composable
private fun SharedActivityHeader(
    totalCount: Int,
    totalUnreadCount: Int,
    onClose: (() -> Unit)?,
    topSafeAreaPadding: Dp,
    refreshing: Boolean,
    onTitleClick: (() -> Unit)? = null,
    mainTab: Boolean = false
) {
    if (mainTab) {
        // App-shell tab head: raft-ui top-level page, the frame layer with the page's name and the
        // stats line, no icon sticker and no back arrow. The title starts on the 16dp phone margin,
        // the edge of the filter tray and of the rows below it.
        RaftTopPageHeader(
            title = stringResource(MR.strings.tab_activity),
            subtitle = activityHeaderSubtitle(totalCount, totalUnreadCount),
            topSafeAreaOverride = topSafeAreaPadding,
            contentStartPadding = 16.dp,
            titleStyle = RaftMainTabHeaderTitleStyle,
            refreshing = refreshing,
            onTitleClick = onTitleClick,
            showBodyTopBanner = false,
        )
        return
    }
    // web ThreadsInbox head: raft-ui PanelHeader with the phone Back.
    RaftPanelBackHeader(
        title = stringResource(MR.strings.home_nav_activity),
        subtitle = activityHeaderSubtitle(totalCount, totalUnreadCount),
        onBack = onClose,
        topSafeArea = topSafeAreaPadding,
        refreshing = refreshing,
        onTitleClick = onTitleClick,
    )
}

/**
 * web Mark all read: a ghost button; when the toolbar is narrower than 30rem (480dp) it keeps
 * only its CheckCheck icon (`@max-[30rem]:size-8`), so the four filters keep their room.
 */
@Composable
private fun SharedActivityMarkAllReadButton(iconOnly: Boolean, onClick: () -> Unit) {
    val label = stringResource(MR.strings.inbox_action_mark_all_read)
    if (iconOnly) {
        RaftIconButton(
            onClick = onClick,
            tooltip = label,
            variant = RaftButtonVariant.Ghost,
        ) {
            RaftContentLucideIcon(RaftLucideIconName.CheckCheck, size = 16.dp)
        }
    } else {
        RaftButton(
            text = label,
            onClick = onClick,
            variant = RaftButtonVariant.Ghost,
        )
    }
}

/** web `@max-[30rem]` on the toolbar's content box: the 54dp row less its 16dp edges. */
internal fun activityMarkAllReadIconOnly(toolbarWidth: Dp): Boolean = toolbarWidth - 32.dp < 480.dp

@Composable
private fun activityHeaderSubtitle(totalCount: Int, totalUnreadCount: Int): String =
    // Web `thread.header.subtitle`: both counts, always.
    stringResource(MR.strings.activity_header_active_unread_count, totalCount, totalUnreadCount)

@Composable
private fun SharedActivityFilterBar(
    selectedFilter: InboxFilter,
    totalUnreadCount: Int,
    onFilterSelected: (InboxFilter) -> Unit,
    onMarkAllRead: () -> Unit
) {
    // web inbox toolbar: 54dp with a 1dp seam inside it, 16dp edges, the 38dp tray 8dp down.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(54.dp).background(RaftColor.SurfaceWhite)) {
        val markAllReadIconOnly = activityMarkAllReadIconOnly(maxWidth)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    .height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
            // raft-ui SegmentedControl: the tray hugs its segments; Mark all read sits at the
            // row's end (xxchan: the tray must not stretch into an empty grey band).
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Design-system RaftSegmentedControl: the picked filter is the yellow sticker. Like
            // web `iconsOnlyBelow="auto"`, whenever the four labels don't fit the room left beside
            // Mark all read, every filter shows only its icon; its label stays the accessible name.
            RaftSegmentedControl(
                // Wrap content, but never push Mark all read off the row; 12dp keeps the gap.
                modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp),
                iconsOnlyWhenLabelsDontFit = true,
            ) {
                InboxFilter.entries.forEach { filter ->
                    RaftSegment(
                        text = filter.localizedLabel(),
                        selected = filter == selectedFilter,
                        onClick = { onFilterSelected(filter) },
                        iconsOnlyIcon = { RaftContentLucideIcon(filter.iconName(), size = 16.dp) },
                    )
                }
            }
            if (totalUnreadCount > 0) {
                SharedActivityMarkAllReadButton(iconOnly = markAllReadIconOnly, onClick = onMarkAllRead)
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftAppStroke.Thin)
                    .background(RaftColor.BorderSeam)
                    .align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun SharedActivityList(
    active: Boolean,
    snapshot: InboxSnapshot,
    selectedFilter: InboxFilter,
    senderProfiles: Map<String, SharedActivitySenderProfile>,
    dmPeerNames: Map<String, String> = emptyMap(),
    refreshing: Boolean,
    pullRefreshing: Boolean,
    loadingMore: Boolean,
    loadMoreErrorCode: String?,
    onOpenItem: (InboxItem) -> Unit,
    onIntent: (SharedActivityIntent) -> Unit,
    bottomContentPadding: Dp,
    timeContext: RaftTimeContext?,
    listState: LazyListState = rememberLazyListState(),
    activityTaskStatusBadgesEnabled: Boolean,
    presentationAccountId: String,
    stableOrderEnabled: Boolean,
    stableOrderDecisionRevision: Long,
    presentationStore: SharedActivityPresentationStore,
    foregroundEpoch: Long,
    externalSelectedThreadId: String? = null,
    /**
     * True while the wide list-detail host owns the selection: then a null
     * [externalSelectedThreadId] means nothing is open beside the list, and the row highlight
     * clears with it (the detail closed by Back or ×). On a phone the panel keeps its own.
     */
    externalSelectionOwned: Boolean = false,
    conversationList: ActivityConversationListPresentation = ActivityConversationListPresentation(),
    channelMute: ActivityChannelMuteControl? = null
) {
    val presentationServerId = snapshot.server.id.value
    val panelFirstRenderMark = remember { TimeSource.Monotonic.markNow() }
    var firstRenderLogged by remember { mutableStateOf(false) }
    val resumeFirstRenderMark = remember(foregroundEpoch) { TimeSource.Monotonic.markNow() }
    var resumeFirstRenderLogged by remember(foregroundEpoch) { mutableStateOf(false) }
    val restoredPresentation =
        remember(presentationStore, presentationAccountId, presentationServerId, selectedFilter) {
            if (stableOrderEnabled) {
                presentationStore.restoreOrCreate(
                    accountId = presentationAccountId,
                    serverId = presentationServerId,
                    filter = selectedFilter,
                    canonicalItems = snapshot.items
                )
            } else {
                presentationStore.clearOwner(presentationAccountId, presentationServerId)
                SharedActivityPresentationCheckpoint(
                    presentedItems = snapshot.items,
                    updateTracking = ActivityUpdateTrackingState(selectedFilter).recordSnapshot(selectedFilter, snapshot.items)
                )
            }
        }
    var activeMenuItem by remember { mutableStateOf<InboxItem?>(null) }
    var menuAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var selectedItemKey by remember(presentationStore, presentationAccountId, presentationServerId, selectedFilter) {
        mutableStateOf(restoredPresentation.selectedItemKey)
    }
    var updateTracking by remember(presentationStore, presentationAccountId, presentationServerId, selectedFilter) {
        mutableStateOf(restoredPresentation.updateTracking)
    }
    var revealRequest by remember(selectedFilter) { mutableStateOf(0) }
    val presentedItemsState =
        remember(presentationStore, presentationAccountId, presentationServerId, selectedFilter) {
            mutableStateOf(restoredPresentation.presentedItems)
        }
    var presentedItems by presentedItemsState
    val presentationGenerationState =
        remember(presentationStore, presentationAccountId, presentationServerId, selectedFilter) {
            mutableStateOf(restoredPresentation.presentationGeneration)
        }
    var presentationGeneration by presentationGenerationState
    var pullRefreshInFlight by remember(presentationStore, presentationAccountId, presentationServerId, selectedFilter) {
        mutableStateOf(restoredPresentation.pullRefreshInFlight)
    }
    var appliedStableOrderDecision by
        remember(presentationStore, presentationAccountId, presentationServerId, selectedFilter) {
            mutableStateOf(ActivityStableOrderDecisionVersion(stableOrderEnabled, stableOrderDecisionRevision))
        }
    fun savePresentation() {
        if (!stableOrderEnabled) return
        presentationStore.save(
            accountId = presentationAccountId,
            serverId = presentationServerId,
            filter = selectedFilter,
            checkpoint =
                SharedActivityPresentationCheckpoint(
                    presentedItems = presentedItems,
                    updateTracking = updateTracking,
                    presentationGeneration = presentationGeneration,
                    pullRefreshInFlight = pullRefreshInFlight,
                    selectedItemKey = selectedItemKey
                )
        )
    }
    val latestOnIntent by rememberUpdatedState(onIntent)
    val refreshPresentation = activityRefreshPresentation(refreshing, pullRefreshing)
    // Viewport recovery (#997) is only armed right after the panel becomes
    // active again (returning from read navigation) or the app returns to the
    // foreground, and disarms as soon as the reader starts scrolling. A data
    // change that arrives mid-scroll must never move the viewport (task #436).
    var viewportRecoveryArmed by remember { mutableStateOf(active) }
    // Task #47: the entry session owns the top edge until the reader moves the
    // list (see ActivityEntryTopAnchorOwner for owner + fence). Declared before
    // the canonical-snapshot effect so activation arms it before that effect runs.
    val entryTopAnchorOwner = remember(listState) { ActivityEntryTopAnchorOwner() }
    // The rows that landed above a scrolled reader; the pill shows only while one of
    // them is laid out above the viewport (activityNewRowsAboveViewport).
    var newActivityAboveKeys by
        remember(presentationStore, presentationAccountId, presentationServerId, selectedFilter) {
            mutableStateOf(emptySet<String>())
        }
    LaunchedEffect(active, foregroundEpoch) {
        if (active) {
            viewportRecoveryArmed = true
            entryTopAnchorOwner.arm()
        } else {
            entryTopAnchorOwner.retire()
        }
    }
    LaunchedEffect(listState, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) viewportRecoveryArmed = false }
    }
    LaunchedEffect(listState, active) {
        if (!active) return@LaunchedEffect
        entryTopAnchorOwner.fenceUserScroll(snapshotFlow { listState.isScrollInProgress })
    }
    // The reader reached the top themselves: nothing new is hidden above any more.
    LaunchedEffect(listState, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            activityListParkedAtTop(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
            .collect { atTop -> if (atTop && newActivityAboveKeys.isNotEmpty()) newActivityAboveKeys = emptySet() }
    }
    fun shouldAnchorTop(pullRefreshOwnsTop: Boolean, genuineTopUpdate: Boolean): Boolean =
        activityShouldAnchorTopOnCanonicalSnapshot(
            entryOwnerArmed = entryTopAnchorOwner.armed,
            pullRefreshOwnsTop = pullRefreshOwnsTop,
            parkedAtTop =
                activityListParkedAtTop(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset),
            scrollInProgress = listState.isScrollInProgress,
            genuineTopUpdate = genuineTopUpdate,
            followParkedTop = !stableOrderEnabled,
        )
    // Same snapshot as the list change: the next measure pass applies both, so
    // LazyList cannot first re-anchor on the old head key and no delayed job exists.
    fun settleTopEdge(previousItems: List<InboxItem>, nextItems: List<InboxItem>, anchorTop: Boolean) {
        if (anchorTop) {
            if (newActivityAboveKeys.isNotEmpty()) newActivityAboveKeys = emptySet()
            listState.requestScrollToItem(0)
        } else {
            val landed = activityNewTopRowKeys(previousItems.map(InboxItem::key), nextItems.map(InboxItem::key))
            if (landed.isNotEmpty()) newActivityAboveKeys = newActivityAboveKeys + landed
        }
    }
    LaunchedEffect(presentedItems, active, viewportRecoveryArmed) {
        if (!active || !viewportRecoveryArmed || listState.isScrollInProgress) return@LaunchedEffect
        val visibleItemKeys =
            listState.layoutInfo.visibleItemsInfo.mapNotNull { itemInfo ->
                itemInfo.key as? String
            }
        if (
            activityListShouldRecoverVisibleContent(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                visibleItemKeys = visibleItemKeys,
                currentItemKeys = presentedItems.map(InboxItem::key)
            )
        ) {
            viewportRecoveryArmed = false
            updateTracking = updateTracking.consumePending()
            savePresentation()
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(presentedItems, active) {
        if (!active) return@LaunchedEffect
        val selected = selectedItemKey
        if (selected != null && presentedItems.none { it.key == selected }) {
            selectedItemKey = null
        }
    }
    // task #45: when the adaptive workspace owns a selection, the row highlight
    // must track THAT conversation (e.g. Search opened a different session while
    // this panel was covered) instead of reviving a stale checkpoint key.
    LaunchedEffect(presentedItems, externalSelectedThreadId, externalSelectionOwned, active) {
        if (!active) return@LaunchedEffect
        if (externalSelectedThreadId == null) {
            if (externalSelectionOwned && selectedItemKey != null) {
                selectedItemKey = null
            }
            return@LaunchedEffect
        }
        val match =
            presentedItems.firstOrNull { item ->
                item.toConversationSummary().threadId.value == externalSelectedThreadId
            }
        val nextKey = match?.key
        if (selectedItemKey != nextKey) {
            selectedItemKey = nextKey
        }
    }
    // task #45: persist the selected row key with the presentation checkpoint so
    // remounting the panel (e.g. an inline rail destination covering it) keeps
    // the highlight instead of resetting to unselected.
    LaunchedEffect(selectedItemKey) {
        savePresentation()
    }
    LaunchedEffect(
        selectedFilter,
        snapshot.items,
        refreshing,
        pullRefreshing,
        stableOrderEnabled,
        stableOrderDecisionRevision,
        active,
    ) {
        if (!active) return@LaunchedEffect
        val nextStableOrderDecision =
            ActivityStableOrderDecisionVersion(stableOrderEnabled, stableOrderDecisionRevision)
        if (appliedStableOrderDecision != nextStableOrderDecision) {
            presentationStore.clearOwner(presentationAccountId, presentationServerId)
            val anchorTop =
                shouldAnchorTop(
                    pullRefreshOwnsTop = pullRefreshing,
                    genuineTopUpdate =
                        activityHasNewTopRow(presentedItems.map(InboxItem::key), snapshot.items.map(InboxItem::key)),
                )
            val previousPresentedItems = presentedItems
            val canonicalCheckpoint =
                activityCanonicalCheckpointAfterStableOrderDecisionChange(
                    selectedFilter = selectedFilter,
                    canonicalItems = snapshot.items,
                    previousPresentedItems = presentedItems,
                    previousPresentationGeneration = presentationGeneration,
                )
            presentedItems = canonicalCheckpoint.presentedItems
            updateTracking = canonicalCheckpoint.updateTracking
            presentationGeneration = canonicalCheckpoint.presentationGeneration
            pullRefreshInFlight = false
            appliedStableOrderDecision = nextStableOrderDecision
            settleTopEdge(previousPresentedItems, canonicalCheckpoint.presentedItems, anchorTop)
            if (stableOrderEnabled) {
                presentationStore.save(
                    accountId = presentationAccountId,
                    serverId = presentationServerId,
                    filter = selectedFilter,
                    checkpoint = canonicalCheckpoint,
                )
            }
            return@LaunchedEffect
        }
        if (pullRefreshing) {
            pullRefreshInFlight = true
        }
        val pullRefreshCompleted = pullRefreshInFlight && !pullRefreshing
        val pullRefreshOwnsTop = pullRefreshing || pullRefreshCompleted
        if (!stableOrderEnabled) {
            val previousPresentedItems = presentedItems
            val nextPresentedItems =
                activityItemsAfterCanonicalSnapshot(
                    stableOrderEnabled = false,
                    pendingCount = 0,
                    pullRefreshCompleted = false,
                    presentedItems = presentedItems,
                    canonicalItems = snapshot.items
                )
            presentationGeneration =
                activityNextPresentationGeneration(
                    currentGeneration = presentationGeneration,
                    presentedItems = presentedItems,
                    nextPresentedItems = nextPresentedItems
                )
            presentedItems = nextPresentedItems
            updateTracking =
                ActivityUpdateTrackingState(selectedFilter)
                    .recordSnapshot(selectedFilter, snapshot.items)
            pullRefreshInFlight = pullRefreshing
            settleTopEdge(
                previousPresentedItems,
                nextPresentedItems,
                anchorTop =
                    shouldAnchorTop(
                        pullRefreshOwnsTop = pullRefreshOwnsTop,
                        genuineTopUpdate =
                            activityHasNewTopRow(
                                previousPresentedItems.map(InboxItem::key),
                                nextPresentedItems.map(InboxItem::key),
                            ),
                    ),
            )
            return@LaunchedEffect
        }
        var nextTracking =
            updateTracking
                .recordSnapshot(selectedFilter, snapshot.items)
                .reconcileCanonicalSnapshot(
                    nextFilter = selectedFilter,
                    currentItems = snapshot.items
                )
        val previousPresentedItems = presentedItems
        // An owned top edge adopts the canonical order now instead of withholding
        // the new head rows behind the "new updates" button.
        val anchorTop =
            shouldAnchorTop(
                pullRefreshOwnsTop = pullRefreshOwnsTop,
                genuineTopUpdate =
                    nextTracking.pendingCount > 0 ||
                        activityHasNewTopRow(presentedItems.map(InboxItem::key), snapshot.items.map(InboxItem::key)),
            )
        val nextPresentedItems =
            if (anchorTop) {
                snapshot.items
            } else {
                activityItemsAfterCanonicalSnapshot(
                    stableOrderEnabled = true,
                    pendingCount = nextTracking.pendingCount,
                    pullRefreshCompleted = pullRefreshCompleted,
                    presentedItems = presentedItems,
                    canonicalItems = snapshot.items
                )
            }
        presentationGeneration =
            activityNextPresentationGeneration(
                currentGeneration = presentationGeneration,
                presentedItems = presentedItems,
                nextPresentedItems = nextPresentedItems
            )
        presentedItems = nextPresentedItems
        if (anchorTop) {
            nextTracking = nextTracking.consumePending()
        }
        if (pullRefreshCompleted) {
            nextTracking = nextTracking.consumePending()
            pullRefreshInFlight = false
        }
        updateTracking = nextTracking
        settleTopEdge(previousPresentedItems, nextPresentedItems, anchorTop)
        savePresentation()
    }
    LaunchedEffect(selectedFilter, revealRequest) {
        if (revealRequest > 0) {
            listState.animateScrollToTopExpressive()
        }
    }
    LaunchedEffect(listState, selectedFilter, snapshot.server.id, presentedItems, active) {
        if (!active) return@LaunchedEffect
        val activityItemsByKey = presentedItems.associateBy(InboxItem::key)
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { itemInfo ->
                    itemInfo.key as? String
                }
                .toSet()
        }
            .distinctUntilChanged()
            .collect { visibleItemKeys ->
                val markers =
                    visibleItemKeys
                        .mapNotNull { key ->
                            activityItemsByKey[key]
                                ?.toReplyThreadPrewarmMarker(snapshot.server.id.value)
                        }
                        .toSet()
                onIntent(SharedActivityIntent.PrewarmVisibleReplyThreads(markers))
            }
    }
    DisposableEffect(listState) {
        onDispose {
            latestOnIntent(SharedActivityIntent.DetachPrewarmViewport)
        }
    }
    val showLoadMoreFooter = snapshot.hasMore || loadingMore || loadMoreErrorCode != null
    val presentationOwnerIdentity =
        activityPresentationOwnerIdentity(
            accountId = presentationAccountId,
            serverId = presentationServerId,
            filter = selectedFilter,
        )
    val currentPresentationOwnerIdentityState = rememberUpdatedState(presentationOwnerIdentity)
    // Freeze the item/key/content triplet for one LazyColumn composition. Reading the mutable
    // presentedItems list independently from the count-key lambda and the row-content lambda can
    // bind a node to the new key while its content/callback still belongs to the old index during
    // an immediate-canonical reorder (Hands e3866a35/acb153fa).
    val renderedItems = presentedItems.filterNot { item -> item.hasUnopenableInternalJointParentSource() }
    val renderedPresentationGeneration = presentationGeneration
    val renderedPresentationOwnerIdentity = presentationOwnerIdentity
    LaunchedEffect(renderedItems.isNotEmpty()) {
        if (!firstRenderLogged && renderedItems.isNotEmpty()) {
            firstRenderLogged = true
            raftLogInfo(
                "SlockForegroundPerf",
                "first_render elapsedMs=${panelFirstRenderMark.elapsedNow().inWholeMilliseconds} serverId=$presentationServerId items=${renderedItems.size} basis=per_cold_start"
            )
        }
    }
    LaunchedEffect(renderedItems.isNotEmpty(), foregroundEpoch) {
        if (foregroundEpoch != 0L && !resumeFirstRenderLogged && renderedItems.isNotEmpty()) {
            resumeFirstRenderLogged = true
            raftLogInfo(
                "SlockForegroundPerf",
                "resume_first_render elapsedMs=${resumeFirstRenderMark.elapsedNow().inWholeMilliseconds} epoch=$foregroundEpoch serverId=$presentationServerId items=${renderedItems.size} basis=per_resume"
            )
        }
    }
    // Rule L swipe-to-Done: the swiped row is hidden while its undo toast is up; the Done itself is
    // committed when the window ends (ActivityConversationSwipe.kt).
    var swipeDoneUndo by remember { mutableStateOf(ActivitySwipeDoneUndoState()) }
    var swipeEndUndo by remember { mutableStateOf(ActivitySwipeEndUndoState()) }
    // raft-design #785: one row's swipe buttons open at a time; a tap elsewhere, a scroll, Back, the
    // long-press menu or opening another row closes it (ActivityConversationSwipe.kt).
    var swipeOpenRow by remember { mutableStateOf(ActivitySwipeOpenRow()) }
    val swipeOpenRowGeometry = remember { ActivitySwipeOpenRowGeometry() }
    val swipeDoneHiddenKey = swipeDoneUndo.hiddenKey
    val conversationListEntries =
        remember(renderedItems, swipeDoneHiddenKey) {
            val listed = if (swipeDoneHiddenKey == null) renderedItems else renderedItems.filterNot { item -> item.key == swipeDoneHiddenKey }
            activityConversationListEntries(listed)
        }
    // LazyColumn key order as laid out, for the pill.
    val lazyItemKeys =
        remember(conversationListEntries) {
            conversationListEntries.map(ActivityConversationListEntry::key)
        }
    val lazyItemKeysState = rememberUpdatedState(lazyItemKeys)
    val newActivityAboveKeysState = rememberUpdatedState(newActivityAboveKeys)
    val newActivityAboveCount by
        remember(listState) {
            derivedStateOf {
                activityNewRowsAboveViewportCount(
                    newRowKeys = newActivityAboveKeysState.value,
                    lazyItemKeys = lazyItemKeysState.value,
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                )
            }
        }
    // A landed row scrolled into view is seen: it leaves the pill's count.
    LaunchedEffect(listState, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNullTo(mutableSetOf()) { it.key as? String } }
            .distinctUntilChanged()
            .collect { visible ->
                val unseen = activityUnseenNewRowKeys(newActivityAboveKeys, visible)
                if (unseen != newActivityAboveKeys) newActivityAboveKeys = unseen
            }
    }
    // Row gestures resolve the committed item at commit time (see activityItemForCommittedGesture).
    fun committedRowItem(renderedItemKey: String): InboxItem? =
        activityItemForCommittedGesture(
            renderedItemKey = renderedItemKey,
            renderedPresentationOwnerIdentity = renderedPresentationOwnerIdentity,
            renderedPresentationGeneration = renderedPresentationGeneration,
            currentItems = presentedItemsState.value,
            currentPresentationOwnerIdentity = currentPresentationOwnerIdentityState.value,
            currentPresentationGeneration = presentationGenerationState.value,
        )
    fun openRow(renderedItemKey: String) {
        val currentItem = committedRowItem(renderedItemKey) ?: return
        RaftProductEvents.activityItemOpen(currentItem.kind.productActivityItemKind())
        selectedItemKey = currentItem.key
        onIntent(
            SharedActivityIntent.PrewarmActivityTail(
                activityReplyThreadPrewarmNextAfterClick(
                    // The next row the person sees.
                    items = activityRowsInDisplayOrder(conversationListEntries),
                    clickedKey = currentItem.key,
                    serverId = snapshot.server.id.value
                )
            )
        )
        currentItem.activityOpenReadIntent()?.let(onIntent)
        onOpenItem(currentItem)
    }
    fun openRowMenu(renderedItemKey: String, anchor: RaftPopoverAnchor) {
        val currentItem = committedRowItem(renderedItemKey) ?: return
        swipeOpenRow = swipeOpenRow.close()
        activeMenuItem = currentItem
        menuAnchor = anchor
    }
    fun commitSwipeDone(swiped: InboxItem) {
        val current = presentedItemsState.value.firstOrNull { it.key == swiped.key }
        activitySwipeDoneCommitItem(swiped, current)?.let { item ->
            RaftProductEvents.activityMark(RaftActivityMarkAction.Done)
            latestOnIntent(SharedActivityIntent.MarkDone(item))
        }
    }
    fun applySwipeDone(transition: ActivitySwipeDoneTransition) {
        swipeDoneUndo = transition.state
        transition.commit?.let(::commitSwipeDone)
    }
    fun swipeRowDone(renderedItemKey: String) {
        val currentItem = committedRowItem(renderedItemKey) ?: return
        // One toast at a time: an end swipe's toast gives way (its action stands).
        swipeEndUndo = swipeEndUndo.dismiss()
        applySwipeDone(swipeDoneUndo.swipe(currentItem))
    }
    fun runSwipeEndCommand(command: ActivitySwipeEndCommand) {
        when (command) {
            is ActivitySwipeEndCommand.Activity -> latestOnIntent(command.intent)
            is ActivitySwipeEndCommand.ChannelMute -> channelMute?.onToggle?.invoke(command.channelId, command.muted)
        }
    }
    // The swipe's Unfollow / Mute Activity button: written on tap like the menu item; the toast's Undo
    // writes the reverse (ActivityConversationSwipe.kt).
    fun swipeRowEnd(renderedItemKey: String) {
        val currentItem = committedRowItem(renderedItemKey) ?: return
        val action = currentItem.activitySwipeEndAction(channelMute?.mutedByChannelId.orEmpty()) ?: return
        // One toast at a time: a waiting Done is committed now rather than lost.
        applySwipeDone(swipeDoneUndo.flush())
        runSwipeEndCommand(activitySwipeEndApplyCommand(currentItem, action))
        swipeEndUndo = swipeEndUndo.swipe(currentItem, action)
    }
    fun undoSwipeEnd(token: Long) {
        val transition = swipeEndUndo.undo(token)
        swipeEndUndo = transition.state
        transition.reverse?.let { swiped -> runSwipeEndCommand(activitySwipeEndUndoCommand(swiped.item, swiped.action)) }
    }
    val swipeDonePendingToken = swipeDoneUndo.pending?.token
    LaunchedEffect(swipeDonePendingToken) {
        val token = swipeDonePendingToken ?: return@LaunchedEffect
        delay(ActivitySwipeDoneUndoWindowMillis)
        applySwipeDone(swipeDoneUndo.expire(token))
    }
    val swipeEndPendingToken = swipeEndUndo.pending?.token
    LaunchedEffect(swipeEndPendingToken) {
        val token = swipeEndPendingToken ?: return@LaunchedEffect
        delay(ActivitySwipeDoneUndoWindowMillis)
        swipeEndUndo = swipeEndUndo.expire(token)
    }
    // A new account, server or filter (or leaving Activity) commits a waiting Done instead of losing it,
    // drops an Unfollow / Mute toast (its write already stands) and closes an open row.
    DisposableEffect(presentationOwnerIdentity) {
        onDispose {
            applySwipeDone(swipeDoneUndo.flush())
            swipeEndUndo = swipeEndUndo.dismiss()
            swipeOpenRow = swipeOpenRow.close()
        }
    }
    // The open row left the list (Done, a filter, a refresh): nothing stays open, nor holds Back.
    LaunchedEffect(lazyItemKeys) {
        val retained = swipeOpenRow.retain(lazyItemKeys)
        if (retained != swipeOpenRow) swipeOpenRow = retained
    }
    // Scrolling the list closes the open row.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && swipeOpenRow.key != null) swipeOpenRow = swipeOpenRow.close()
            }
    }
    // System Back closes an open row first (AGENTS.md Kuikly Back Handling Rule); the row menu, composed
    // later, still wins while it is up.
    RaftBackHandler(enabled = swipeOpenRow.key != null, label = "activity-swipe-row-open") {
        swipeOpenRow = swipeOpenRow.close()
    }
    // The pointerInput coroutine deliberately keeps the callback from its DOWN generation. Read
    // the backing MutableState objects at commit time so the old callback sees an order change
    // immediately, even before the next recomposition updates or cancels its pointerInput node.
    Box(
        modifier =
            swipeOpenRowGeometry
                .trackPanel(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite))
                .activitySwipeCloseOnOutsideDown(
                    geometry = swipeOpenRowGeometry,
                    openRow = { swipeOpenRow },
                    onChange = { swipeOpenRow = it },
                )
    ) {
        RaftPullToRefreshBox(
            isRefreshing = refreshPresentation.pullRefreshing,
            onRefresh = { onIntent(SharedActivityIntent.PullRefresh) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        // web inbox scroller `py-1`; rows sit edge to edge, split by their own hairline.
                        .padding(top = 4.dp)
            ) {
                // One compact row per conversation (web ThreadsInbox `layout="list"`).
                // TODO(dark-mode): these rows paint with RaftTheme.colors (foreground, lineHairline, infoSoft)
                // over the panel's fixed RaftColor.SurfaceWhite background; migrate the panel surface to a
                // theme token with the rest of Activity's dark-mode pass.
                items(items = conversationListEntries, key = { entry -> entry.key }) { entry ->
                    when (entry) {
                        is ActivityConversationListEntry.Row -> {
                            val item = entry.item
                            val model =
                                remember(item, conversationList, selectedFilter, senderProfiles, dmPeerNames, activityTaskStatusBadgesEnabled) {
                                    item.activityConversationRowModel(
                                        context = conversationList.context,
                                        selectedFilter = selectedFilter,
                                        senderProfiles = senderProfiles,
                                        dmPeerNames = dmPeerNames,
                                        activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                                    )
                                }
                            ActivityConversationListRow(
                                item = item,
                                model = model,
                                divider = entry.divider,
                                selected = selectedItemKey == item.key,
                                menuOpen = activeMenuItem?.key == item.key,
                                timeContext = timeContext,
                                agentStatusResolver = conversationList.agentStatusResolver,
                                canMarkDone = item.activityShowsDoneAction(),
                                openGestureKey =
                                    remember(item, renderedPresentationGeneration, renderedPresentationOwnerIdentity) {
                                        item.activityOpenGestureKey(
                                            presentationGeneration = renderedPresentationGeneration,
                                            presentationOwnerIdentity = renderedPresentationOwnerIdentity,
                                        )
                                    },
                                captureMenuAnchor = !listState.isScrollInProgress,
                                onOpen = { openRow(item.key) },
                                onOpenMenu = { anchor -> openRowMenu(item.key, anchor) },
                                onSwipeDone = { swipeRowDone(item.key) },
                                swipeEndAction = item.activitySwipeEndAction(channelMute?.mutedByChannelId.orEmpty()),
                                onSwipeEnd = { swipeRowEnd(item.key) },
                                swipeOpen = swipeOpenRow.isOpen(item.key),
                                onSwipeOpenChange = { open -> swipeOpenRow = swipeOpenRow.change(item.key, open) },
                                onSwipeOpenRowPlaced = swipeOpenRowGeometry::onOpenRowPlaced,
                            )
                        }
                    }
                }
                if (showLoadMoreFooter) {
                    item(key = ACTIVITY_LOAD_MORE_ROW_KEY) {
                        val shouldRequest = activityShouldRequestLoadMore(
                            hasMore = snapshot.hasMore,
                            refreshing = refreshing,
                            loadingMore = loadingMore,
                            loadMoreErrorCode = loadMoreErrorCode
                        )
                        // The footer is the tail sentinel. Entering composition is a stronger and
                        // more portable signal than observing LazyList layoutInfo: the latter did not
                        // emit on the affected Kuikly/Android build, leaving the user at the end of
                        // the first page with neither a request nor failure feedback (task #539).
                        // Keep page appends stable so one composed footer cannot cascade through every
                        // remaining page. A refresh is different: it replaces the appended tail with
                        // the first page while this keyed footer can remain composed. Re-key on the
                        // refresh lifecycle so its terminal transition can request the lost tail again.
                        LaunchedEffect(refreshing) {
                            if (shouldRequest) {
                                latestOnIntent(SharedActivityIntent.LoadMore)
                            }
                        }
                        when (activityLoadMoreFooterMode(loadingMore, loadMoreErrorCode)) {
                            ActivityLoadMoreFooterMode.Sentinel -> Spacer(Modifier.fillMaxWidth().height(1.dp))
                            ActivityLoadMoreFooterMode.Loading -> SharedActivityLoadingMoreRow()
                            ActivityLoadMoreFooterMode.Retry ->
                                SharedActivityLoadMoreRetryRow(onClick = { onIntent(SharedActivityIntent.LoadMore) })
                        }
                    }
                }
                item { Spacer(Modifier.height(bottomContentPadding)) }
            }
        }
        if (stableOrderEnabled && updateTracking.pendingCount > 0) {
            // web `thread.newUpdates`: a 32dp outline button.
            RaftButton(
                text = stringResource(
                    if (updateTracking.pendingCount == 1) MR.strings.activity_updates_one else MR.strings.activity_updates_many,
                    updateTracking.pendingCount
                ),
                onClick = {
                    // Retire exactly the snapshot represented by this button
                    // before scrolling. A later realtime arrival gets its own
                    // marker and remains actionable; animation completion never
                    // clears work that arrived after this click.
                    updateTracking = updateTracking.consumePending()
                    newActivityAboveKeys = emptySet()
                    presentationGeneration =
                        activityNextPresentationGeneration(
                            currentGeneration = presentationGeneration,
                            presentedItems = presentedItems,
                            nextPresentedItems = snapshot.items
                        )
                    presentedItems = snapshot.items
                    revealRequest += 1
                    savePresentation()
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            )
        } else if (newActivityAboveCount > 0) {
            // Task #47: newer rows landed above a reader who had already scrolled;
            // the viewport stays theirs until they tap (explicit viewport replacement).
            // web `thread.newUpdates`: the count of those rows not seen yet.
            RaftButton(
                text = stringResource(
                    if (newActivityAboveCount == 1) MR.strings.activity_updates_one else MR.strings.activity_updates_many,
                    newActivityAboveCount
                ),
                onClick = {
                    newActivityAboveKeys = emptySet()
                    revealRequest += 1
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            )
        }
        swipeDoneUndo.pending?.let { pending ->
            RaftToast(
                title = stringResource(MR.strings.activity_list_marked_done),
                actionLabel = stringResource(MR.strings.common_undo),
                onAction = { swipeDoneUndo = swipeDoneUndo.undo(pending.token) },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = bottomContentPadding + 8.dp),
            )
        }
        swipeEndUndo.pending?.let { pending ->
            // raft-design responsive.md › Touch screens: the toast says what the button did, naming the conversation.
            val conversation = pending.item.activitySwipeConversationLabel()
            RaftToast(
                title =
                    when (pending.action) {
                        ActivitySwipeEndAction.UnfollowThread -> stringResource(MR.strings.activity_swipe_unfollowed_thread, conversation)
                        ActivitySwipeEndAction.MuteActivity -> stringResource(MR.strings.activity_swipe_muted_activity, conversation)
                    },
                actionLabel = stringResource(MR.strings.common_undo),
                onAction = { undoSwipeEnd(pending.token) },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = bottomContentPadding + 8.dp),
            )
        }
        activeMenuItem?.let { item ->
            val resolvedMenuAnchor = menuAnchor ?: return@let
            RaftBaseMenuOverlay(
                anchor = resolvedMenuAnchor,
                items = activityContextMenuActionIds(item, channelMute?.mutedByChannelId.orEmpty()).map { actionId ->
                    when (actionId) {
                        "toggle-read" ->
                            RaftBaseMenuItem(
                                id = actionId,
                                label =
                                    if (item.unreadCount > 0) {
                                        stringResource(MR.strings.home_action_mark_as_read)
                                    } else {
                                        stringResource(MR.strings.home_action_mark_as_unread)
                                    },
                                icon = RaftLucideIconName.Mail
                            )
                        "done" ->
                            RaftBaseMenuItem(
                                id = actionId,
                                label = stringResource(MR.strings.thread_action_mark_done),
                                icon = RaftLucideIconName.Check
                            )
                        "follow-thread" ->
                            RaftBaseMenuItem(
                                id = actionId,
                                label = stringResource(MR.strings.thread_action_follow_thread),
                                icon = RaftLucideIconName.Bell
                            )
                        "unfollow-thread" ->
                            RaftBaseMenuItem(
                                id = actionId,
                                label = stringResource(MR.strings.thread_action_unfollow_thread),
                                icon = RaftLucideIconName.BellOff
                            )
                        "mute-activity" ->
                            RaftBaseMenuItem(
                                id = actionId,
                                label = stringResource(MR.strings.activity_action_mute_activity),
                                icon = RaftLucideIconName.BellOff
                            )
                        "unmute-activity" ->
                            RaftBaseMenuItem(
                                id = actionId,
                                label = stringResource(MR.strings.activity_action_unmute_activity),
                                icon = RaftLucideIconName.Bell
                            )
                        else -> error("unknown Activity menu action: $actionId")
                    }
                },
                selectedId = "",
                onDismiss = { activeMenuItem = null },
                onSelect = { id ->
                    when (id) {
                        "toggle-read" ->
                            if (item.unreadCount > 0) {
                                RaftProductEvents.activityMark(RaftActivityMarkAction.Read)
                                onIntent(SharedActivityIntent.MarkRead(item))
                            } else {
                                RaftProductEvents.activityMark(RaftActivityMarkAction.Unread)
                                onIntent(SharedActivityIntent.MarkUnread(item))
                            }
                        "done" -> {
                            RaftProductEvents.activityMark(RaftActivityMarkAction.Done)
                            onIntent(SharedActivityIntent.MarkDone(item))
                        }
                        "follow-thread" -> onIntent(SharedActivityIntent.FollowThread(item))
                        "unfollow-thread" -> onIntent(SharedActivityIntent.UnfollowThread(item))
                        "mute-activity" -> channelMute?.onToggle?.invoke(item.channelId.value, true)
                        "unmute-activity" -> channelMute?.onToggle?.invoke(item.channelId.value, false)
                    }
                    activeMenuItem = null
                }
            )
        }
    }
}

/**
 * Activity prewarm is click-progressive: consuming row N arms only the next
 * eligible reply Thread. Warming the full tail after navigation competes with
 * the opened Thread's foreground work, especially while confirming a send.
 */
internal fun activityReplyThreadPrewarmNextAfterClick(
    items: List<InboxItem>,
    clickedKey: String,
    serverId: String
): List<ActivityReplyThreadPrewarmMarker> {
    val clickedIndex = items.indexOfFirst { item -> item.key == clickedKey }
    if (clickedIndex !in items.indices) return emptyList()
    return listOfNotNull(
        items
        .drop(clickedIndex + 1)
        .firstNotNullOfOrNull { item -> item.toReplyThreadPrewarmMarker(serverId) }
    )
}

/**
 * Commits an Activity row gesture only while the exact rendered order generation still owns the
 * row key. Content-only updates for the same key are allowed and resolve to the newest canonical
 * item; any reorder/removal cancels the stale DOWN instead of retargeting its UP to another row.
 */
internal fun activityItemForCommittedGesture(
    renderedItemKey: String,
    renderedPresentationOwnerIdentity: String,
    renderedPresentationGeneration: Int,
    currentItems: List<InboxItem>,
    currentPresentationOwnerIdentity: String,
    currentPresentationGeneration: Int,
): InboxItem? {
    if (renderedPresentationOwnerIdentity != currentPresentationOwnerIdentity) return null
    if (renderedPresentationGeneration != currentPresentationGeneration) return null
    return currentItems.firstOrNull { item -> item.key == renderedItemKey }
}

internal fun activityPresentationOwnerIdentity(
    accountId: String,
    serverId: String,
    filter: InboxFilter,
): String = "account:$accountId|server:$serverId|filter:${filter.wireValue}"

/**
 * Opening an unread row marks it read immediately, like web `handleOpen`
 * (botiverse/slock 1bb70cbeb, ThreadsInbox.tsx:830-833). The row passed here is
 * the committed gesture's current row (resolved by key against the live list),
 * so a reorder during navigation cannot redirect the read; the destination
 * store's own acknowledgement still follows and is idempotent.
 */
internal fun InboxItem.activityOpenReadIntent(): SharedActivityIntent? =
    if (unreadCount > 0 && !isUnfollowedActivityThread && kind != InboxItemKind.MentionAction) {
        SharedActivityIntent.MarkRead(this)
    } else {
        null
    }

/**
 * Menu membership is a pure projection of the row's server-owned follow
 * state. Keeping this list as the production input to the Composable makes
 * the follow/unfollow and attention gates directly executable in tests.
 */
/** Web InboxContextMenu `canMute`: which joined channels' activity is muted, and how to toggle it. */
@Immutable
internal data class ActivityChannelMuteControl(
    /** Joined channels of this server (Home sidebar), keyed by id: whether their activity is muted. */
    val mutedByChannelId: Map<String, Boolean>,
    val onToggle: (channelId: String, muted: Boolean) -> Unit,
)

/**
 * Web `canToggleActivityMute`: only joined channels whose type has a user-reachable Activity mute
 * (`activityMuteSupported`; DMs are not channels here) are listed, so the menu's Mute / Unmute
 * Activity and the end swipe never offer a write the Home store would refuse.
 */
internal fun SharedHomeContentState?.activityMuteByChannelId(activityServerId: String): Map<String, Boolean> {
    val content = this as? SharedHomeContentState.Content ?: return emptyMap()
    if (activityServerId.isBlank() || content.snapshot.server.id.value != activityServerId) return emptyMap()
    return content.snapshot.channels
        .filter { channel -> channel.joined && channel.activityMuteSupported }
        .associate { channel -> channel.channelId.value to channel.activityMuted }
}

internal fun activityContextMenuActionIds(item: InboxItem, mutedByChannelId: Map<String, Boolean> = emptyMap()): List<String> =
    buildList {
        if (item.isUnfollowedActivityThread) {
            // Web InboxContextMenu: an unfollowed row has no read/unread toggle
            // (the server zeroes its attention) but keeps Done, then Follow.
            add("done")
            if (item.kind == InboxItemKind.Thread) add("follow-thread")
            return@buildList
        }
        add("toggle-read")
        add("done")
        if (item.isFollowedActivityThread) add("unfollow-thread")
        // Channel rows only (not DMs, threads or mentions), and only a joined channel.
        if (item.kind == InboxItemKind.Channel) {
            when (mutedByChannelId[item.channelId.value]) {
                true -> add("unmute-activity")
                false -> add("mute-activity")
                null -> Unit
            }
        }
    }

internal fun InboxItem.toReplyThreadPrewarmMarker(serverId: String): ActivityReplyThreadPrewarmMarker? {
    if (kind != InboxItemKind.Thread || unreadCount <= 0) return null
    val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedParentChannelId = parentChannelId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedParentMessageId = parentMessageId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedThreadChannelId = threadChannelId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (normalizedThreadChannelId == normalizedParentChannelId) return null
    return ActivityReplyThreadPrewarmMarker(
        key =
            ActivityReplyThreadPrewarmKey(
                serverId = normalizedServerId,
                parentChannelId = normalizedParentChannelId,
                parentMessageId = normalizedParentMessageId,
                threadChannelId = normalizedThreadChannelId
            )
    )
}

/** RFC 077: the assistant named next to this row's sender; never on system or mention-action rows. */
internal fun InboxItem.previewDelegatedBy(): MessageDelegatedBy? =
    latestDelegatedBy.takeIf { kind != InboxItemKind.MentionAction && !hasActivitySystemSender() }

internal fun InboxItem.activityPrimaryContentMuted(): Boolean =
    kind != InboxItemKind.System && unreadCount == 0

/**
 * Resolved sender identity for an Activity row's leading avatar.
 *
 * A thread surfaces its latest activity sender; a channel/DM surfaces its last message sender; a mention
 * action is treated as a system/app sender. Missing primary fields fall back
 * to the row's other sender fields — never to the thread parent author —
 * so the avatar always matches the byline shown in the row.
 */
internal data class ActivitySenderAvatarIdentity(
    val senderKind: MemberKind?,
    val avatarWireValue: String?,
    val label: String,
    val senderId: String? = null,
    /**
     * The row is a joint channel's (or a thread in one): its sender may live on the other server,
     * in neither of this server's rosters, and may be resolved through `POST /api/actors/resolve`.
     */
    val jointSurface: Boolean = false,
)

internal fun InboxItem.activitySenderAvatarIdentity(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap()
): ActivitySenderAvatarIdentity {
    val senderKind: MemberKind?
    val senderId: String?
    val avatarWireValue: String?
    when (kind) {
        InboxItemKind.Thread -> {
            senderKind = latestActivitySenderKind ?: lastMessageSenderKind
            senderId = latestActivitySenderId ?: lastMessageSenderId
            avatarWireValue = latestActivitySenderAvatarWireValue ?: lastMessageSenderAvatarWireValue
        }
        InboxItemKind.MentionAction -> {
            senderKind = MemberKind.System
            senderId = null
            avatarWireValue = null
        }
        InboxItemKind.Channel,
        InboxItemKind.DirectMessage,
        InboxItemKind.System -> {
            senderKind = lastMessageSenderKind ?: latestActivitySenderKind
            senderId = lastMessageSenderId ?: latestActivitySenderId
            avatarWireValue = lastMessageSenderAvatarWireValue ?: latestActivitySenderAvatarWireValue
        }
    }
    return ActivitySenderAvatarIdentity(
        senderKind = senderKind,
        avatarWireValue = avatarWireValue,
        label = senderDisplayName(senderProfiles).orEmpty(),
        senderId = senderId,
        jointSurface = isJointActivityRow(),
    )
}

/** Web ThreadsInbox `jointRow`: a thread of a joint channel, or a joint channel row. */
internal fun InboxItem.isJointActivityRow(): Boolean =
    when (kind) {
        InboxItemKind.Thread ->
            parentChannelKind == ConversationKind.Joint || parentChannelType.equals("joint", ignoreCase = true)
        InboxItemKind.MentionAction -> false
        else -> channelKind == ConversationKind.Joint || channelType.equals("joint", ignoreCase = true)
    }

/**
 * Web always offers Done, including on an unfollowed thread row (slock
 * 1bb70cbeb ThreadsInbox.tsx:375-392, InboxContextMenu.tsx:115-120): the server
 * keeps such a row in All until it is marked Done, so it must stay clearable.
 */
@Suppress("UnusedReceiverParameter")
internal fun InboxItem.activityShowsDoneAction(): Boolean = true

@Composable
internal fun Modifier.activityRowGestures(
    gestureKey: String,
    captureMenuAnchor: Boolean,
    onClick: () -> Unit,
    onOpenMenu: (RaftPopoverAnchor) -> Unit,
    /** raft-ui 0.20 InboxItem press: true from DOWN until the finger lifts or the gesture ends. */
    onPressedChange: (Boolean) -> Unit = {},
): Modifier {
    val rootAnchor = remember(gestureKey) { RaftLiveRootAnchorRef(identity = gestureKey) }
    val currentOnPressedChange by rememberUpdatedState(onPressedChange)
    val gestureOwner =
        rememberRaftGestureOwner(
            surface = "activity-row",
            semanticIdentity = gestureKey,
            callbacks = ActivityRowGestureCallbacks(onClick, onOpenMenu, rootAnchor)
        )
    val anchored =
        this
            .then(
                if (captureMenuAnchor) {
                    Modifier.raftCaptureLiveRootAnchor(rootAnchor)
                } else {
                    Modifier
                }
            )
    return anchored.raftGesturePointerInput(gestureOwner) { owner ->
        var gesture: RaftGestureSession<ActivityRowGestureCallbacks>? = null
        detectTapGestures(
            // Preserve the authority that owned DOWN for this gesture. The semantic owner token
            // cancels a lazy-slot replacement, while ordinary recomposition does not tear down a
            // 500 ms long press.
            onPress = {
                gesture = owner.beginGesture()
                currentOnPressedChange(true)
                try {
                    tryAwaitRelease()
                } finally {
                    currentOnPressedChange(false)
                }
            },
            onTap = { (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.onClick() },
            onLongPress = { localOffset ->
                (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                    callbacks.rootAnchor
                        .resolve(RaftPopoverLocalPoint.Exact(localOffset))
                        ?.let(callbacks.onOpenMenu)
                }
            }
        )
    }
}

private data class ActivityRowGestureCallbacks(
    val onClick: () -> Unit,
    val onOpenMenu: (RaftPopoverAnchor) -> Unit,
    val rootAnchor: RaftLiveRootAnchorRef
)

internal fun InboxItem.inboxItemOpenGestureKey(): String =
    // A stable remember-key for the row's gesture handlers. Derived from the row's own
    // identity fields — building an open request here would run the resolver per
    // composition for a value that only needs stability, not routing correctness.
    listOf(
        key,
        kind.name,
        channelId.value,
        threadChannelId?.value.orEmpty(),
        parentChannelId?.value.orEmpty(),
        parentMessageId?.value.orEmpty(),
        resolveInboxAnchorMessageId()?.value.orEmpty(),
        unreadCount.toString()
    ).joinToString("|")

internal fun InboxItem.activityOpenGestureKey(
    presentationGeneration: Int,
    presentationOwnerIdentity: String = "",
): String =
    // The Activity pointer owner follows the stable presented slot, not the
    // latest anchor/unread fields. The captured callback resolves newest content
    // through stable current-state holders; only a committed order generation
    // change may cancel a DOWN→UP gesture that started on this slot.
    "activity-open:$key|owner:$presentationOwnerIdentity|presentation:$presentationGeneration"

@Composable
internal fun SharedActivityFooterBadge(
    label: String,
    slot: SharedActivityFooterSlot,
    taskStatus: TaskStatus? = null,
    modifier: Modifier = Modifier
) {
    val visual = slot.badgeVisual(taskStatus)
    RaftBrutalBadge(
        label = label,
        modifier = modifier,
        tone = visual.tone,
        uppercase = false,
        leadingIcon = visual.leadingIcon
    )
}

@Composable
private fun SharedActivityLoadingMoreRow() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(MR.strings.inbox_state_loading_more),
            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray
        )
    }
}

@Composable
private fun SharedActivityLoadMoreRetryRow(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(MR.strings.inbox_error_load_more),
            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            modifier = Modifier.padding(end = 8.dp)
        )
        RaftButton(
            text = stringResource(MR.strings.common_retry),
            onClick = onClick
        )
    }
}

@Composable
private fun SharedActivityRefreshableStateBody(
    title: String,
    detail: String,
    actionLabel: String? = null,
    refreshing: Boolean,
    pullRefreshing: Boolean,
    onRefresh: () -> Unit,
    bottomContentPadding: Dp,
    listState: LazyListState,
    onAction: () -> Unit = {}
) {
    val refreshPresentation = activityRefreshPresentation(refreshing, pullRefreshing)
    RaftPullToRefreshBox(
        isRefreshing = refreshPresentation.pullRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "activity_state") {
                SharedActivityStateBody(
                    title = title,
                    detail = detail,
                    actionLabel = actionLabel,
                    bottomContentPadding = bottomContentPadding,
                    onAction = onAction,
                    modifier = Modifier.fillParentMaxSize()
                )
            }
        }
    }
}

@Composable
private fun SharedActivityStateBody(
    title: String,
    detail: String,
    loading: Boolean = false,
    actionLabel: String? = null,
    bottomContentPadding: Dp,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (loading) {
        RaftCenteredLoading(message = title)
        return
    }
    RaftEmptyState(
        title = title,
        detail = detail,
        iconName = RaftLucideIconName.Activity,
        actionLabel = actionLabel,
        onAction = onAction.takeIf { actionLabel != null },
        modifier = modifier.padding(bottom = bottomContentPadding)
    )
}

@Composable
private fun InboxFilter.localizedLabel(): String =
    stringResource(
        when (this) {
            InboxFilter.All -> MR.strings.activity_filter_all
            InboxFilter.Unread -> MR.strings.activity_filter_unread
            InboxFilter.Mentions -> MR.strings.activity_filter_mentions
            InboxFilter.Pinned -> MR.strings.activity_filter_pinned
        }
    )

/** web inbox filters' icons, shown alone when the four labels don't fit (`in-data-icons-only:block`). */
internal fun InboxFilter.iconName(): RaftLucideIconName =
    when (this) {
        InboxFilter.All -> RaftLucideIconName.Inbox
        InboxFilter.Unread -> RaftLucideIconName.CircleDot
        InboxFilter.Mentions -> RaftLucideIconName.AtSign
        InboxFilter.Pinned -> RaftLucideIconName.Pin
    }

private fun InboxFilter.emptyCopy(): String =
    when (this) {
        InboxFilter.All -> "No unread conversations or mentions yet."
        InboxFilter.Unread -> "No unread conversations."
        InboxFilter.Mentions -> "No mentions yet."
        InboxFilter.Pinned -> "No pinned conversations."
    }

@Composable
private fun InboxFilter.localizedEmptyCopy(): String =
    stringResource(
        when (this) {
            InboxFilter.All -> MR.strings.inbox_empty_all_detail
            InboxFilter.Unread -> MR.strings.inbox_empty_unread_detail
            InboxFilter.Mentions -> MR.strings.inbox_empty_mentions_detail
            InboxFilter.Pinned -> MR.strings.inbox_empty_pinned_detail
        }
    )

internal fun InboxItem.displayTitle(dmPeerNames: Map<String, String> = emptyMap()): String =
    when (kind) {
        InboxItemKind.Thread ->
            if (parentChannelKind == ConversationKind.DirectMessage) {
                dmPeerLabelOrFallback(dmPeerNames)
            } else {
                parentChannelName ?: channelName
            }
        InboxItemKind.DirectMessage, InboxItemKind.Channel, InboxItemKind.MentionAction, InboxItemKind.System -> channelName
    }.ifBlank { channelId.value }

internal fun InboxItem.channelLabel(dmPeerNames: Map<String, String> = emptyMap()): String =
    when (kind) {
        InboxItemKind.DirectMessage -> "@${channelName.ifBlank { channelId.value }}"
        InboxItemKind.Thread ->
            if (parentChannelKind == ConversationKind.DirectMessage) {
                "@${dmPeerLabelOrFallback(dmPeerNames)}"
            } else {
                "#${(parentChannelName ?: channelName).ifBlank { channelId.value }}"
            }
        InboxItemKind.MentionAction ->
            if (channelKind == ConversationKind.DirectMessage) {
                "@${channelName.ifBlank { channelId.value }}"
            } else {
                "#${channelName.ifBlank { channelId.value }}"
            }
        InboxItemKind.Channel, InboxItemKind.System -> "#${channelName.ifBlank { channelId.value }}"
    }

/**
 * task #399: peer name for a DM-parent thread row. `parentChannelName` is the
 * raw channel name and is not a reliable peer projection for DMs, so the Home
 * DM list name wins; the raw name is only a fail-soft fallback.
 */
internal fun InboxItem.dmPeerLabelOrFallback(dmPeerNames: Map<String, String>): String =
    parentChannelId?.let { dmPeerNames[it.value] }?.takeIf { it.isNotBlank() }
        ?: parentChannelName
        ?: channelName.ifBlank { channelId.value }

internal fun InboxItem.senderDisplayName(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap()
): String? =
    if (kind == InboxItemKind.Thread) {
        latestActivitySenderId
            ?.let(senderProfiles::get)
            ?.displayName
            ?: latestActivitySenderName
            ?: lastMessageSenderId
                ?.let(senderProfiles::get)
                ?.displayName
            ?: lastMessageSenderName
    } else {
        lastMessageSenderId
            ?.let(senderProfiles::get)
            ?.displayName
            ?: lastMessageSenderName
            ?: latestActivitySenderId
                ?.let(senderProfiles::get)
                ?.displayName
            ?: latestActivitySenderName
    }

internal fun InboxItem.activityTimeLabel(timeContext: RaftTimeContext?): String =
    timeContext?.formatTimestampLabel(latestActivityAt ?: lastMessageAt)
        ?: latestActivityAt
        ?: lastMessageAt
        ?: ""

private fun InboxItem.previewText(
    threadFallback: String,
    actionFallback: String,
    conversationFallback: String
): String =
    when (kind) {
        InboxItemKind.Thread -> parentMessagePreview ?: latestActivityPreview ?: lastMessagePreview ?: threadFallback
        InboxItemKind.MentionAction -> lastMessagePreview ?: latestActivityPreview ?: actionFallback
        else -> lastMessagePreview ?: latestActivityPreview ?: conversationFallback
    }

internal fun InboxItem.primaryPreviewText(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap()
): String =
    primaryActivityPreviewText(senderProfiles).fullText

internal fun InboxItem.secondaryPreviewText(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap()
): String? =
    secondaryActivityPreviewText(senderProfiles)?.fullText

internal data class ActivityPreviewText(
    val senderName: String?,
    val body: String,
    /** RFC 077: rendered as "Alice (sent by Friday)" by the row ([delegatedPreviewSender]). */
    val delegatedBy: MessageDelegatedBy? = null
) {
    val fullText: String =
        if (senderName.isNullOrBlank()) {
            body
        } else {
            "$senderName: $body"
        }

    fun toAnnotatedString(): AnnotatedString =
        buildAnnotatedString {
            if (!senderName.isNullOrBlank()) {
                withStyle(raftSpanStyleForWeight(FontWeight.Bold)) {
                    append(senderName)
                    append(": ")
                }
            }
            append(body)
        }
}

internal fun InboxItem.primaryActivityPreviewText(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap(),
    dmPeerNames: Map<String, String> = emptyMap(),
    threadFallback: String = "Thread update",
    actionFallback: String = "Action required",
    conversationFallback: String = "Conversation update"
): ActivityPreviewText =
    ActivityPreviewText(
        senderName =
            when {
                // task #399 飞书 shape: a DM-parent thread's first line is
                // "@peer: root preview" — the peer identity prefixes the root
                // preview instead of the latest-activity sender.
                kind == InboxItemKind.Thread &&
                    parentChannelKind == ConversationKind.DirectMessage ->
                    "@${dmPeerLabelOrFallback(dmPeerNames)}"
                kind == InboxItemKind.Thread || kind == InboxItemKind.MentionAction -> null
                else -> senderDisplayName(senderProfiles)
            },
        body = previewText(threadFallback, actionFallback, conversationFallback)
    )

internal fun InboxItem.secondaryActivityPreviewText(
    senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap(),
    threadFallback: String = "Thread update",
    actionFallback: String = "Action required",
    conversationFallback: String = "Conversation update"
): ActivityPreviewText? =
    latestActivityPreview
        ?.takeIf {
            kind == InboxItemKind.Thread && replyCount > 0 &&
                it != previewText(threadFallback, actionFallback, conversationFallback)
        }
        ?.let { preview ->
            ActivityPreviewText(senderName = senderDisplayName(senderProfiles), body = preview)
        }

internal enum class SharedActivityFooterSlot {
    Task,
    Replies,
    Mention,
    New,
    Unfollowed,
    Draft
}

internal data class SharedActivityFooterBadgeVisual(
    val tone: RaftBrutalTone,
    val leadingIcon: RaftLucideIconName? = null
)

internal fun SharedActivityFooterSlot.badgeVisual(taskStatus: TaskStatus? = null): SharedActivityFooterBadgeVisual =
    when (this) {
        SharedActivityFooterSlot.Task -> {
            val status = taskStatus ?: TaskStatus.Todo
            val statusMeta = status.sharedTaskStatusChipMeta()
            val tone =
                when (status) {
                    TaskStatus.Todo -> RaftBrutalTone.Orange
                    TaskStatus.InProgress -> RaftBrutalTone.Cyan
                    TaskStatus.InReview -> RaftBrutalTone.Lavender
                    TaskStatus.Done -> RaftBrutalTone.Lime
                    TaskStatus.Closed -> RaftBrutalTone.Stone
                }
            SharedActivityFooterBadgeVisual(
                tone = tone,
                leadingIcon = statusMeta.icon
            )
        }
        SharedActivityFooterSlot.Replies -> SharedActivityFooterBadgeVisual(RaftBrutalTone.White)
        SharedActivityFooterSlot.Mention -> SharedActivityFooterBadgeVisual(RaftBrutalTone.Yellow, RaftLucideIconName.AtSign)
        SharedActivityFooterSlot.New -> SharedActivityFooterBadgeVisual(RaftBrutalTone.Pink)
        SharedActivityFooterSlot.Unfollowed ->
            SharedActivityFooterBadgeVisual(RaftBrutalTone.Stone, RaftLucideIconName.BellOff)
        SharedActivityFooterSlot.Draft -> SharedActivityFooterBadgeVisual(RaftBrutalTone.White, RaftLucideIconName.Pencil)
    }

internal fun activityShouldRequestLoadMore(
    hasMore: Boolean,
    refreshing: Boolean,
    loadingMore: Boolean,
    loadMoreErrorCode: String?
): Boolean = hasMore && !refreshing && !loadingMore && loadMoreErrorCode == null

internal enum class ActivityLoadMoreFooterMode {
    Sentinel,
    Loading,
    Retry
}

internal fun activityLoadMoreFooterMode(
    loadingMore: Boolean,
    loadMoreErrorCode: String?
): ActivityLoadMoreFooterMode =
    when {
        loadMoreErrorCode != null -> ActivityLoadMoreFooterMode.Retry
        loadingMore -> ActivityLoadMoreFooterMode.Loading
        else -> ActivityLoadMoreFooterMode.Sentinel
    }

internal const val ACTIVITY_LOAD_MORE_ROW_KEY = "activity_load_more"

/**
 * True only when at least one activity row is visible and none of the visible
 * rows still exists. The load-more footer is not an activity row, and an empty
 * visible set is a transient layout state; neither is evidence that the rows
 * the reader was looking at were removed.
 */
internal fun activityListShouldRecoverVisibleContent(
    firstVisibleItemIndex: Int,
    visibleItemKeys: List<String>,
    currentItemKeys: List<String>
): Boolean {
    if (firstVisibleItemIndex <= 0 || currentItemKeys.isEmpty()) return false
    val visibleRowKeys = visibleItemKeys.filter { it != ACTIVITY_LOAD_MORE_ROW_KEY }
    if (visibleRowKeys.isEmpty()) return false
    val currentKeys = currentItemKeys.toSet()
    return visibleRowKeys.none(currentKeys::contains)
}

internal fun activityItemsPreservingPresentedOrder(
    presentedItems: List<InboxItem>,
    canonicalItems: List<InboxItem>
): List<InboxItem> {
    // Stable LazyColumn keys preserve node identity only when the business list
    // also preserves slots. Reordering the backing list between DOWN and UP can
    // otherwise let a new coordinate occupant receive the gesture on affected
    // Kuikly runtimes. Update fields in place and reveal canonical order later.
    if (presentedItems.isEmpty() || canonicalItems.isEmpty()) return presentedItems
    val canonicalByKey = canonicalItems.associateBy(InboxItem::key)
    return presentedItems.map { presented -> canonicalByKey[presented.key] ?: presented }
}

internal fun activityCanonicalCheckpointAfterStableOrderDecisionChange(
    selectedFilter: InboxFilter,
    canonicalItems: List<InboxItem>,
    previousPresentedItems: List<InboxItem>,
    previousPresentationGeneration: Int,
): SharedActivityPresentationCheckpoint =
    SharedActivityPresentationCheckpoint(
        presentedItems = canonicalItems,
        updateTracking =
            ActivityUpdateTrackingState(selectedFilter)
                .recordSnapshot(selectedFilter, canonicalItems),
        presentationGeneration =
            activityNextPresentationGeneration(
                currentGeneration = previousPresentationGeneration,
                presentedItems = previousPresentedItems,
                nextPresentedItems = canonicalItems,
            ),
        pullRefreshInFlight = false,
    )

internal data class ActivityStableOrderDecisionVersion(
    val enabled: Boolean,
    val revision: Long,
)

internal fun activityShouldAdoptCanonicalOrder(
    pendingCount: Int,
    pullRefreshCompleted: Boolean
): Boolean = pendingCount == 0 || pullRefreshCompleted

internal fun activityItemsAfterCanonicalSnapshot(
    stableOrderEnabled: Boolean,
    pendingCount: Int,
    pullRefreshCompleted: Boolean,
    presentedItems: List<InboxItem>,
    canonicalItems: List<InboxItem>
): List<InboxItem> {
    if (!stableOrderEnabled) return canonicalItems
    return if (activityShouldAdoptCanonicalOrder(pendingCount, pullRefreshCompleted)) {
        canonicalItems
    } else {
        // Realtime/background snapshots may advance content for a stable row,
        // and route-return/foreground reconciliation may confirm a self-authored
        // send, but neither owns its visible slot. Keep every presented key in
        // place and withhold new keys until explicit banner/PTR adoption.
        activityItemsPreservingPresentedOrder(presentedItems, canonicalItems)
    }
}

internal fun activityNextPresentationGeneration(
    currentGeneration: Int,
    presentedItems: List<InboxItem>,
    nextPresentedItems: List<InboxItem>
): Int =
    if (presentedItems.map(InboxItem::key) == nextPresentedItems.map(InboxItem::key)) {
        currentGeneration
    } else {
        currentGeneration + 1
    }

internal fun InboxItem.toConversationSummary(
    dmPeerNames: Map<String, String> = emptyMap()
): ConversationSummary {
    val streamChannelId = resolveInboxStreamChannelId()
    val parentId = parentMessageId
    val parentChannel = parentChannelId
    val stableThreadIdentity =
        if (kind == InboxItemKind.Thread && parentChannel != null && parentId != null) {
            threadIdentity(parentChannel, parentId)
        } else {
            null
        }
    return ConversationSummary(
        threadId = stableThreadIdentity ?: topLevelThreadId(channelId),
        channelId = if (kind == InboxItemKind.Thread) parentChannel else channelId,
        thread =
            if (kind == InboxItemKind.Thread && parentChannel != null && parentId != null) {
                ThreadConversation(
                    parentChannelId = parentChannel,
                    parentMessageId = parentId,
                    streamChannelId = streamChannelId,
                    parentSurfaceKind = parentChannelKind.threadParentSurfaceKindOrNull(),
                    parentChannelKind = parentChannelKind.threadChannelKindWireValue()
                )
            } else {
                null
            },
        kind =
            launchConversationKind(),
        name = displayTitle(dmPeerNames),
        subtitle = parentMessagePreview ?: lastMessagePreview ?: latestActivityPreview ?: stableThreadIdentity?.value,
        unreadCount = unreadCount,
        unreadCountKnown = true,
        mentionCount = if (hasMention) 1 else 0,
        mentionCountKnown = hasMention,
        lastMessageId = latestActivityMessageId ?: lastMessageId,
        firstUnreadMessageId = firstUnreadMessageId,
        lastMessagePreview = latestActivityPreview ?: lastMessagePreview,
        lastMessageAt = latestActivityAt ?: lastMessageAt,
        lastMessageSenderName = latestActivitySenderName ?: lastMessageSenderName
    )
}

internal data class ThreadLaunchOriginFallback(
    val parentChannelId: String?,
    val parentMessageId: String,
    val preview: String?,
    val senderId: String?,
    val senderKind: MemberKind?,
    val expected: Boolean = true
)

internal data class HomeMessageOpenRequest(
    val conversationIdentity: ThreadLaunchConversationIdentity,
    val anchorMessageId: String?,
    val originFallback: ThreadLaunchOriginFallback? = null
)

/**
 * Per-kind stream channel id resolver. Replaces the prior silent fallback
 * `if (kind == Thread) threadChannelId else threadChannelId ?: channelId` (反例 1 in #280 audit).
 *
 * - Thread items resolve to their explicit `threadChannelId` (v0.2 contract requires the
 *   thread stream id to be distinct from `channelId`).
 * - Non-thread items resolve to `null`. The downstream `ThreadConversation` field is only
 *   populated for Thread items, so this is the safe default.
 *
 * Future sealed `InboxItem.Thread` (Phase 3 #3 (B)) will encode this in the type system:
 * `threadChannelId` becomes a required non-null constructor parameter on the Thread subclass.
 */
internal fun InboxItem.resolveInboxStreamChannelId(): ChannelId? =
    when (kind) {
        InboxItemKind.Thread -> threadChannelId
        InboxItemKind.Channel, InboxItemKind.DirectMessage, InboxItemKind.MentionAction, InboxItemKind.System -> null
    }

/**
 * Open anchor used by Activity, Search, and Saved rows. This mirrors React's row-open
 * contract: `firstUnreadMessageId` is only an anchor while the row is actually unread.
 * Thread rows focus reply ids (`firstUnreadMessageId` / `latestActivityMessageId`) and
 * must not fall back to `lastMessageId` or `parentMessageId`, both of which can describe
 * the parent/origin rather than a thread reply.
 */
internal fun InboxItem.resolveInboxAnchorMessageId(): MessageId? =
    when (kind) {
        InboxItemKind.Thread ->
            if (unreadCount > 0) {
                firstUnreadMessageId ?: latestActivityMessageId
            } else {
                latestActivityMessageId
            }
        InboxItemKind.MentionAction -> lastMessageId
        InboxItemKind.DirectMessage,
        InboxItemKind.Channel,
        InboxItemKind.System ->
            if (unreadCount > 0) {
                firstUnreadMessageId ?: lastMessageId
            } else {
                lastMessageId
            }
    }

/**
 * User-initiated row open (Activity/Search/Saved). The resolver is the ONLY exit — the
 * caller must supply the real server id and conversation directory, and must handle
 * [ConversationOpenResolution.Pending] as an explainable failure (loud log + toast, no
 * navigation). The previous Pending fallback rebuilt an identity directly, bypassing the
 * resolver's parent-kind qualification and misrouting null-parent-kind threads onto the
 * channel surface (#415, t5/#419 A3/A4); it is intentionally gone.
 */
internal fun InboxItem.toHomeMessageOpenRequest(
    serverId: String,
    conversationDirectory: SharedConversationDirectoryState,
    diagnosticsLogger: (String) -> Unit = {}
): ConversationOpenResolution =
    resolveOpenRequest(
        serverId = serverId,
        sourceMessageIdentity = toConversationOpenSourceMessageIdentity(),
        conversationDirectory = conversationDirectory,
        diagnosticsLogger = diagnosticsLogger
    )

private fun InboxItem.launchConversationKind(): ConversationKind =
    when (kind) {
        InboxItemKind.DirectMessage -> ConversationKind.DirectMessage
        InboxItemKind.Thread -> ConversationKind.Thread
        // Product default: user-initiated MentionAction opens the target as a channel,
        // this is not a parser fallback for unknown route kind strings.
        InboxItemKind.MentionAction -> channelKind ?: ConversationKind.Channel
        InboxItemKind.System -> ConversationKind.System
        InboxItemKind.Channel -> parentChannelKind ?: ConversationKind.Channel
    }
