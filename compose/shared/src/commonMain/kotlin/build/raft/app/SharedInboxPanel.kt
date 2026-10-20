package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalBadge
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.components.RaftBrutalTone
import build.raft.app.components.TabPager
import build.raft.app.components.TabPagerGesturePolicy
import build.raft.app.data.DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED
import build.raft.app.data.ActivityReplyThreadPrewarmKey
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.ActivityUnreadSummaryProjection
import build.raft.app.data.ActivityUpdateTrackingState
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

private val ActivitySelectedRowShadowOffset = 2.dp

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
    activitySenderAvatarsEnabled: Boolean = false,
    // task #383: secondary-route pages (ComposeHomeActivityPage) never see a
    // Kuikly pageDidAppear on app foreground, so they opt into refreshing on
    // the app-scope appLifecycle resume/show broadcast. Shell surfaces leave
    // this off: the app shell owns foreground reconciliation there.
    refreshOnAppLifecycleResume: Boolean = false,
    // task #45: when the adaptive workspace drives the detail pane, its
    // selection identity (threadId) is the source of truth for the row
    // highlight; a stale checkpoint key must not resurface.
    externalSelectedThreadId: String? = null,
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
    LaunchedEffect(routeVisibleTick) {
        if (activityPageDidAppearShouldRefresh(routeVisibleTick)) {
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
    var wasActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !wasActive) {
            activityStore.dispatch(SharedActivityIntent.Refresh)
        }
        wasActive = active
    }
    SharedActivityPanelContent(
        state = safeOpenProjectedState,
        onOpenItem = { item ->
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
        },
        onClose = onClose,
        onIntent = activityStore::dispatch,
        bottomContentPadding = bottomContentPadding,
        topSafeAreaPadding = shellScope.topInset(includeTopSafeArea),
        timeContext = timeContext,
        activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
        activitySenderAvatarsEnabled = activitySenderAvatarsEnabled,
        draftProjection = draftProjection,
        stableOrderEnabled = stableOrderEnabledAtRouteEntry,
        stableOrderDecisionRevision = diagnosticOverrideRevision,
        presentationStore = activityStore.presentationStore,
        foregroundEpoch = activitySummary.lastForegroundEpoch,
        externalSelectedThreadId = externalSelectedThreadId,
        modifier = modifier
    )
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

internal fun activityPageDidAppearShouldRefresh(routeVisibleTick: Int): Boolean =
    routeVisibleTick > 0

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
        // pull gesture owns visible refresh chrome.
        headerRefreshing = pullRefreshing,
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
    activitySenderAvatarsEnabled: Boolean = false,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    stableOrderEnabled: Boolean = true,
    stableOrderDecisionRevision: Long = 0L,
    presentationStore: SharedActivityPresentationStore = remember { SharedActivityPresentationStore() },
    foregroundEpoch: Long,
    modifier: Modifier = Modifier,
    externalSelectedThreadId: String? = null
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
        activitySenderAvatarsEnabled = activitySenderAvatarsEnabled,
        draftProjection = draftProjection,
        stableOrderEnabled = stableOrderEnabled,
        stableOrderDecisionRevision = stableOrderDecisionRevision,
        presentationStore = presentationStore,
        foregroundEpoch = 0L,
        externalSelectedThreadId = externalSelectedThreadId,
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
    activitySenderAvatarsEnabled: Boolean = false,
    draftProjection: SharedThreadComposerDraftProjection = SharedThreadComposerDraftProjection.Empty,
    stableOrderEnabled: Boolean = true,
    stableOrderDecisionRevision: Long = 0L,
    presentationStore: SharedActivityPresentationStore = remember { SharedActivityPresentationStore() },
    foregroundEpoch: Long,
    modifier: Modifier = Modifier,
    externalSelectedThreadId: String? = null
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
            activitySenderAvatarsEnabled = activitySenderAvatarsEnabled,
            draftProjection = draftProjection,
            stableOrderEnabled = stableOrderEnabled,
            stableOrderDecisionRevision = stableOrderDecisionRevision,
            presentationStore = presentationStore,
            foregroundEpoch = foregroundEpoch,
            externalSelectedThreadId = externalSelectedThreadId,
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
    activitySenderAvatarsEnabled: Boolean,
    draftProjection: SharedThreadComposerDraftProjection,
    stableOrderEnabled: Boolean,
    stableOrderDecisionRevision: Long,
    presentationStore: SharedActivityPresentationStore,
    foregroundEpoch: Long,
    modifier: Modifier = Modifier,
    externalSelectedThreadId: String? = null
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
    // set is a closed enum of three, and this list is index-aligned with
    // `filters` (All/Unread/Mentions entry order).
    val pageListStates = listOf(
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
    )
    val scrollToTopScope = rememberCoroutineScope()
    Column(
        modifier = modifier.fillMaxSize().background(RaftColor.SurfaceWhite)
    ) {
        SharedActivityHeader(
            totalCount = state.totalCount,
            totalUnreadCount = state.totalUnreadCount,
            onClose = onClose,
            topSafeAreaPadding = topSafeAreaPadding,
            refreshing = refreshPresentation.headerRefreshing,
            onTitleClick = {
                scrollToTopScope.launch {
                    activityScrollToTopTarget(state.selectedFilter, pageListStates)
                        .animateScrollToTopExpressive()
                }
            }
        )
        RaftAppShellBodyTopBanner()
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
                    onMarkAllRead = { onIntent(SharedActivityIntent.MarkAllRead) }
                )
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page, active ->
            val pageFilter = filters[page]
            val pageState = projectActivityStateForFilterPage(state, pageFilter)
            if (pageState.snapshot != null) {
                SharedActivityBodyContent(
                    active = active,
                    state = pageState,
                    onOpenItem = onOpenItem,
                    onIntent = onIntent,
                    bottomContentPadding = bottomContentPadding,
                    timeContext = timeContext,
                    listState = pageListStates[page],
                    activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                    activitySenderAvatarsEnabled = activitySenderAvatarsEnabled,
                    draftProjection = draftProjection,
                    presentationAccountId = state.ownerUserId.orEmpty(),
                    stableOrderEnabled = stableOrderEnabled,
                    stableOrderDecisionRevision = stableOrderDecisionRevision,
                    presentationStore = presentationStore,
                    foregroundEpoch = foregroundEpoch,
                    externalSelectedThreadId = externalSelectedThreadId,
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
    activitySenderAvatarsEnabled: Boolean,
    draftProjection: SharedThreadComposerDraftProjection,
    presentationAccountId: String,
    stableOrderEnabled: Boolean,
    stableOrderDecisionRevision: Long,
    presentationStore: SharedActivityPresentationStore,
    foregroundEpoch: Long,
    externalSelectedThreadId: String? = null,
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
                    activitySenderAvatarsEnabled = activitySenderAvatarsEnabled,
                    presentationAccountId = presentationAccountId,
                    stableOrderEnabled = stableOrderEnabled,
                    stableOrderDecisionRevision = stableOrderDecisionRevision,
                    presentationStore = presentationStore,
                    foregroundEpoch = foregroundEpoch,
                    externalSelectedThreadId = externalSelectedThreadId
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
    onTitleClick: (() -> Unit)? = null
) {
    RaftTopPageHeader(
        title = stringResource(MR.strings.home_nav_activity),
        subtitle =
            if (totalUnreadCount > 0) {
                stringResource(MR.strings.activity_header_active_unread_count, totalCount, totalUnreadCount)
            } else {
                stringResource(MR.strings.activity_header_active_count, totalCount)
            },
        backgroundColor = RaftColor.SurfaceWhite,
        topSafeAreaOverride = topSafeAreaPadding,
        refreshing = refreshing,
        leadingContent =
            onClose?.let { close ->
                {
                    RaftTopPageHeaderAction(icon = RaftLucideIconName.ArrowLeft, onClick = close)
                }
            },
        onTitleClick = onTitleClick,
        showBodyTopBanner = false,
    )
}

@Composable
private fun SharedActivityFilterBar(
    selectedFilter: InboxFilter,
    totalUnreadCount: Int,
    onFilterSelected: (InboxFilter) -> Unit,
    onMarkAllRead: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().height(54.dp).background(RaftColor.SurfaceWhite)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // raft-ui 0.8.0 SegmentedControl hugs its items; the tray never stretches.
            // Weighted without fill, it only gives way when Mark All Read needs the room.
            SegmentedControl(
                selectedValue = selectedFilter.wireValue,
                options =
                    InboxFilter.entries.map { filter ->
                        SegmentedControlOption(value = filter.wireValue, label = filter.localizedLabel())
                    },
                onValueSelected = { value ->
                    InboxFilter.entries.firstOrNull { it.wireValue == value }?.let(onFilterSelected)
                },
                modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp)
            )
            if (totalUnreadCount > 0) {
                RaftBrutalActionButton(
                    label = stringResource(MR.strings.inbox_action_mark_all_read),
                    minHeight = 28.dp,
                    minWidth = 0.dp,
                    shadowOffset = 2.dp,
                    backgroundColor = RaftColor.SurfaceWhite,
                    textStyle = RaftType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    onClick = onMarkAllRead
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Default).background(RaftColor.BorderBlack))
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
    activitySenderAvatarsEnabled: Boolean,
    presentationAccountId: String,
    stableOrderEnabled: Boolean,
    stableOrderDecisionRevision: Long,
    presentationStore: SharedActivityPresentationStore,
    foregroundEpoch: Long,
    externalSelectedThreadId: String? = null
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
    LaunchedEffect(active, foregroundEpoch) {
        if (active) viewportRecoveryArmed = true
    }
    LaunchedEffect(listState, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) viewportRecoveryArmed = false }
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
    LaunchedEffect(presentedItems, externalSelectedThreadId, active) {
        if (!active || externalSelectedThreadId == null) return@LaunchedEffect
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
        if (!stableOrderEnabled) {
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
            pullRefreshInFlight = false
            return@LaunchedEffect
        }
        if (pullRefreshing) {
            pullRefreshInFlight = true
        }
        val pullRefreshCompleted = pullRefreshInFlight && !pullRefreshing
        var nextTracking =
            updateTracking
                .recordSnapshot(selectedFilter, snapshot.items)
                .reconcileCanonicalSnapshot(
                    nextFilter = selectedFilter,
                    currentItems = snapshot.items
                )
        val nextPresentedItems =
            activityItemsAfterCanonicalSnapshot(
                stableOrderEnabled = true,
                pendingCount = nextTracking.pendingCount,
                pullRefreshCompleted = pullRefreshCompleted,
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
        if (pullRefreshCompleted) {
            nextTracking = nextTracking.consumePending()
            pullRefreshInFlight = false
        }
        updateTracking = nextTracking
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
    // The pointerInput coroutine deliberately keeps the callback from its DOWN generation. Read
    // the backing MutableState objects at commit time so the old callback sees an order change
    // immediately, even before the next recomposition updates or cancels its pointerInput node.
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
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
                        .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = renderedItems, key = { item -> item.key }) { item ->
                    SharedActivityRow(
                        item = item,
                        senderProfiles = senderProfiles,
                        dmPeerNames = dmPeerNames,
                        selected = selectedItemKey == item.key,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        timeContext = timeContext,
                        activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled,
                        activitySenderAvatarsEnabled = activitySenderAvatarsEnabled,
                        captureMenuAnchor = !listState.isScrollInProgress,
                        presentationGeneration = renderedPresentationGeneration,
                        presentationOwnerIdentity = renderedPresentationOwnerIdentity,
                        onOpen = open@{
                            val currentItem =
                                activityItemForCommittedGesture(
                                    renderedItemKey = item.key,
                                    renderedPresentationOwnerIdentity = renderedPresentationOwnerIdentity,
                                    renderedPresentationGeneration = renderedPresentationGeneration,
                                    currentItems = presentedItemsState.value,
                                    currentPresentationOwnerIdentity = currentPresentationOwnerIdentityState.value,
                                    currentPresentationGeneration = presentationGenerationState.value,
                                ) ?: return@open
                            selectedItemKey = currentItem.key
                            onIntent(
                                SharedActivityIntent.PrewarmActivityTail(
                                    activityReplyThreadPrewarmNextAfterClick(
                                        items = renderedItems,
                                        clickedKey = currentItem.key,
                                        serverId = snapshot.server.id.value
                                    )
                                )
                            )
                            currentItem.activityOpenReadIntent()?.let(onIntent)
                            onOpenItem(currentItem)
                        },
                        onDone = done@{
                            val currentItem =
                                activityItemForCommittedGesture(
                                    renderedItemKey = item.key,
                                    renderedPresentationOwnerIdentity = renderedPresentationOwnerIdentity,
                                    renderedPresentationGeneration = renderedPresentationGeneration,
                                    currentItems = presentedItemsState.value,
                                    currentPresentationOwnerIdentity = currentPresentationOwnerIdentityState.value,
                                    currentPresentationGeneration = presentationGenerationState.value,
                                ) ?: return@done
                            onIntent(SharedActivityIntent.MarkDone(currentItem))
                        },
                        onOpenMenu = menu@{ anchor ->
                            val currentItem =
                                activityItemForCommittedGesture(
                                    renderedItemKey = item.key,
                                    renderedPresentationOwnerIdentity = renderedPresentationOwnerIdentity,
                                    renderedPresentationGeneration = renderedPresentationGeneration,
                                    currentItems = presentedItemsState.value,
                                    currentPresentationOwnerIdentity = currentPresentationOwnerIdentityState.value,
                                    currentPresentationGeneration = presentationGenerationState.value,
                                ) ?: return@menu
                            activeMenuItem = currentItem
                            menuAnchor = anchor
                        }
                    )
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
            RaftBrutalActionButton(
                label = stringResource(
                    if (updateTracking.pendingCount == 1) MR.strings.activity_updates_one else MR.strings.activity_updates_many,
                    updateTracking.pendingCount
                ),
                onClick = {
                    // Retire exactly the snapshot represented by this button
                    // before scrolling. A later realtime arrival gets its own
                    // marker and remains actionable; animation completion never
                    // clears work that arrived after this click.
                    updateTracking = updateTracking.consumePending()
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
                minHeight = 30.dp,
                minWidth = 0.dp,
                shadowOffset = 2.dp,
                textStyle = RaftType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp)
            )
        }
        activeMenuItem?.let { item ->
            val resolvedMenuAnchor = menuAnchor ?: return@let
            RaftBaseMenuOverlay(
                anchor = resolvedMenuAnchor,
                items = activityContextMenuActionIds(item).map { actionId ->
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
                                label = stringResource(MR.strings.common_done),
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
                        else -> error("unknown Activity menu action: $actionId")
                    }
                },
                selectedId = "",
                uppercaseLabels = false,
                onDismiss = { activeMenuItem = null },
                onSelect = { id ->
                    when (id) {
                        "toggle-read" ->
                            if (item.unreadCount > 0) {
                                onIntent(SharedActivityIntent.MarkRead(item))
                            } else {
                                onIntent(SharedActivityIntent.MarkUnread(item))
                            }
                        "done" -> onIntent(SharedActivityIntent.MarkDone(item))
                        "follow-thread" -> onIntent(SharedActivityIntent.FollowThread(item))
                        "unfollow-thread" -> onIntent(SharedActivityIntent.UnfollowThread(item))
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

internal fun InboxItem.activityOpenReadIntent(): SharedActivityIntent? {
    // The destination store acknowledges the stable channel/thread identity after it is loaded
    // and visible. Activity rows can reorder while navigation is in flight, so the list must not
    // infer or write read state from its transient presentation snapshot.
    return null
}

/**
 * Menu membership is a pure projection of the row's server-owned follow
 * state. Keeping this list as the production input to the Composable makes
 * the follow/unfollow and attention gates directly executable in tests.
 */
internal fun activityContextMenuActionIds(item: InboxItem): List<String> =
    buildList {
        if (item.isUnfollowedActivityThread) {
            // An unfollowed terminal row is historical context. It has no
            // read/unread or Done affordance; the only valid action is an
            // explicit Follow, which clears the server tombstone.
            if (item.kind == InboxItemKind.Thread) add("follow-thread")
            return@buildList
        }
        add("toggle-read")
        add("done")
        if (item.isFollowedActivityThread) add("unfollow-thread")
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

@Composable
private fun SharedActivitySelectedRowSurface(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    // raft-ui cards take the container radius; the selected sticker shadow follows it.
    val shape = RoundedCornerShape(RaftAppRadius.Container)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(end = ActivitySelectedRowShadowOffset, bottom = ActivitySelectedRowShadowOffset)
    ) {
        if (selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .offset(x = ActivitySelectedRowShadowOffset, y = ActivitySelectedRowShadowOffset)
                    .background(RaftColor.ShadowBlack, shape)
            )
        }
        content(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(RaftColor.SurfaceWhite)
                .border(
                    RaftAppStroke.Default,
                    if (selected) RaftColor.BorderBlack else RaftColor.TextBlack.copy(alpha = 0.3f),
                    shape
                )
        )
    }
}

@Composable
private fun SharedActivityRow(
    item: InboxItem,
    senderProfiles: Map<String, SharedActivitySenderProfile>,
    dmPeerNames: Map<String, String> = emptyMap(),
    selected: Boolean,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext?,
    activityTaskStatusBadgesEnabled: Boolean,
    activitySenderAvatarsEnabled: Boolean,
    captureMenuAnchor: Boolean = true,
    presentationGeneration: Int = 0,
    presentationOwnerIdentity: String = "",
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onOpenMenu: (RaftPopoverAnchor) -> Unit
) {
    val openGestureKey =
        remember(item, presentationGeneration, presentationOwnerIdentity) {
            item.activityOpenGestureKey(
                presentationGeneration = presentationGeneration,
                presentationOwnerIdentity = presentationOwnerIdentity,
            )
        }
    val mutedPrimaryContent = item.activityPrimaryContentMuted()
    val threadFallback = stringResource(MR.strings.activity_fallback_thread_update)
    val actionFallback = stringResource(MR.strings.activity_fallback_action_required)
    val conversationFallback = stringResource(MR.strings.activity_fallback_conversation_update)
    val avatarPresentation = activitySenderAvatarPresentation(activitySenderAvatarsEnabled)
    SharedActivitySelectedRowSurface(selected = selected, modifier = modifier) { surfaceModifier ->
        Column(modifier = surfaceModifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Optional latest-activity sender avatar: 32dp frame, 2dp
                // black border, 12dp gap to the content column, and a 2dp top
                // nudge. Pure presentation —
                // it shares the row open/long-press gesture and never changes
                // the Done button's independent click. The frame is a fixed
                // 32dp regardless of sender kind / missing avatar URL so the
                // row height never drifts.
                if (avatarPresentation.visible) {
                    SharedActivitySenderAvatar(
                        item = item,
                        senderProfiles = senderProfiles,
                        modifier =
                            Modifier
                                .activityRowGestures(
                                    openGestureKey,
                                    captureMenuAnchor,
                                    onOpen,
                                    onOpenMenu
                                )
                                .padding(start = 12.dp, top = 12.dp)
                                .offset(y = 2.dp)
                    )
                }
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .activityRowGestures(
                                openGestureKey,
                                captureMenuAnchor,
                                onOpen,
                                onOpenMenu
                            )
                            .padding(start = avatarPresentation.contentStartPadding, top = 12.dp)
                ) {
                // Tag + timestamp share the top row — artin's ruling on #1039
                // (#wg-nightly-visual-fix:4670869d, 2026-07-17): react's
                // timestamp-beside-title placement steals width from the real
                // message content; the android layout is the desired product
                // behavior and react should adopt it (declared react-adopts-later).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Channel tag fills the row so the timestamp is pushed to the
                    // right edge (artin #1171, 2026-07-17): timestamp right-aligned
                    // on the tag row; the title and the rows below do not move.
                    Text(
                        text = item.channelLabel(dmPeerNames),
                        style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = RaftColor.TextBlack.copy(alpha = 0.52f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 10.dp)
                    )
                    RaftRelativeTimestampText(
                        value = item.latestActivityAt ?: item.lastMessageAt,
                        timeContext = timeContext,
                        style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = RaftMonoFamily),
                        color = RaftColor.TextBlack.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (item.kind == InboxItemKind.Thread) {
                        RaftLucideIcon(
                            RaftLucideIconName.MessageSquare,
                            modifier = Modifier.size(13.dp),
                            // react title icon is text-black/45 in EVERY state
                            // (#1039); only the title text dims when read.
                            color = "#73000000",
                            verticalOffsetDp = 2f
                        )
                    }
                    val primaryPreview = item.primaryActivityPreviewText(
                        senderProfiles = senderProfiles,
                        dmPeerNames = dmPeerNames,
                        threadFallback = threadFallback,
                        actionFallback = actionFallback,
                        conversationFallback = conversationFallback
                    )
                    Text(
                        text = primaryPreview.toAnnotatedString(),
                        style =
                            if (item.unreadCount > 0) {
                                RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp)
                            } else {
                                RaftType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp)
                            },
                        color = if (mutedPrimaryContent) RaftColor.TextBlack.copy(alpha = 0.5f) else RaftColor.TextBlack,
                        maxLines = if (item.kind == InboxItemKind.Thread) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                item.secondaryActivityPreviewText(
                    senderProfiles = senderProfiles,
                    threadFallback = threadFallback,
                    actionFallback = actionFallback,
                    conversationFallback = conversationFallback
                )?.let { secondary ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = secondary.toAnnotatedString(),
                        style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = if (item.unreadCount > 0) RaftColor.TextBlack else RaftColor.TextBlack.copy(alpha = 0.45f),
                        // react body is line-clamp-2 (#1039): the preview gets
                        // two lines before ellipsis, not one.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SharedActivityFooter(
                    item = item,
                    activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled
                )
                }
                if (item.activityShowsDoneAction()) {
                    Row(
                        modifier = Modifier.padding(top = 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SharedActivitySmallIconButton(
                            iconName = RaftLucideIconName.Check,
                            clickIdentity =
                                item.activityDoneClickIdentity(
                                    presentationGeneration = presentationGeneration,
                                    presentationOwnerIdentity = presentationOwnerIdentity,
                                ),
                            onClick = onDone
                        )
                    }
                }
            }
            // This padding is visually part of the card, so keep it in the
            // row's open/long-press target instead of leaving a dead strip
            // below the content. The Done button above remains independent.
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .activityRowGestures(
                        openGestureKey,
                        captureMenuAnchor,
                        onOpen,
                        onOpenMenu
                    )
            )
        }
    }
}

internal fun InboxItem.activityPrimaryContentMuted(): Boolean =
    kind != InboxItemKind.System && unreadCount == 0

// Activity sender avatar for the Mobile-only appearance experiment. The outer frame is
// 32dp with a 2dp black border; the fill is keyed by sender kind: agent /
// human reuse the canonical RaftAppAvatar wire-value pipeline (pixel avatar,
// uploaded image, Gravatar, then User-icon placeholder), while system /
// unknown render the app "SY" initials placeholder.
internal val ActivitySenderAvatarSize = 32.dp
private val ActivitySenderAvatarBorder = RaftAppStroke.Default
private const val ActivitySenderAppInitials = "SY"

internal data class ActivitySenderAvatarPresentation(
    val visible: Boolean,
    val contentStartPadding: Dp
)

internal fun activitySenderAvatarPresentation(enabled: Boolean): ActivitySenderAvatarPresentation =
    ActivitySenderAvatarPresentation(
        visible = enabled,
        contentStartPadding = if (enabled) 0.dp else 12.dp
    )

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
    val senderId: String? = null
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
        senderId = senderId
    )
}

@Composable
private fun SharedActivitySenderAvatar(
    item: InboxItem,
    senderProfiles: Map<String, SharedActivitySenderProfile>,
    modifier: Modifier = Modifier
) {
    val identity = remember(item, senderProfiles) { item.activitySenderAvatarIdentity(senderProfiles) }
    Box(
        modifier = modifier.size(ActivitySenderAvatarSize),
        contentAlignment = Alignment.Center
    ) {
        when (identity.senderKind) {
            MemberKind.Agent,
            MemberKind.Human,
            // External senders render their wire avatar; the cache subject stays
            // null because external ids are never member-cache identities.
            MemberKind.External ->
                RaftAppAvatar(
                    avatarWireValue = identity.avatarWireValue,
                    label = identity.label,
                    avatarSize = ActivitySenderAvatarSize,
                    borderWidth = ActivitySenderAvatarBorder,
                    cacheSubject = raftMemberAvatarCacheSubject(identity.senderKind, identity.senderId),
                    fallbackFill = raftAvatarFallbackFill(identity.senderKind)
                )
            MemberKind.System,
            null -> SharedActivityAppAvatar()
        }
    }
}

@Composable
private fun SharedActivityAppAvatar(modifier: Modifier = Modifier) {
    // The same raft-ui sticker frame as the member avatars beside it (ink edge, dark-mode
    // sticker-edge ring), on the system's soft-signal fill.
    RaftAppAvatarFrame(
        modifier = modifier,
        avatarSize = ActivitySenderAvatarSize,
        borderWidth = ActivitySenderAvatarBorder,
        fill = RaftColor.SoftSignal
    ) {
        Text(
            text = ActivitySenderAppInitials,
            style =
                RaftType.Caption
                    .withRaftFontWeight(FontWeight.Black)
                    .copy(fontSize = 12.sp, lineHeight = 12.sp),
            color = RaftColor.TextBlack,
            maxLines = 1
        )
    }
}

internal fun InboxItem.activityDoneClickIdentity(
    presentationGeneration: Int = 0,
    presentationOwnerIdentity: String = "",
): String = "activity-done:$key|owner:$presentationOwnerIdentity|presentation:$presentationGeneration"

internal fun InboxItem.activityShowsDoneAction(): Boolean = !isUnfollowedActivityThread

@Composable
private fun Modifier.activityRowGestures(
    gestureKey: String,
    captureMenuAnchor: Boolean,
    onClick: () -> Unit,
    onOpenMenu: (RaftPopoverAnchor) -> Unit
): Modifier {
    val rootAnchor = remember(gestureKey) { RaftLiveRootAnchorRef(identity = gestureKey) }
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
                tryAwaitRelease()
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
@OptIn(ExperimentalLayoutApi::class)
private fun SharedActivityFooter(
    item: InboxItem,
    activityTaskStatusBadgesEnabled: Boolean
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item.activityFooterSlots(activityTaskStatusBadgesEnabled = activityTaskStatusBadgesEnabled).forEach { slot ->
            when (slot) {
                SharedActivityFooterSlot.Replies ->
                    SharedActivityFooterBadge(
                        label = stringResource(
                            if (item.replyCount == 1) MR.strings.activity_replies_one else MR.strings.activity_replies_many,
                            item.replyCount
                        ),
                        slot = slot,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                SharedActivityFooterSlot.Task ->
                    SharedActivityFooterBadge(
                        label = item.activityTaskBadgeText(),
                        slot = slot,
                        taskStatus = item.taskStatus,
                        modifier = Modifier.widthIn(max = 260.dp).align(Alignment.CenterVertically)
                    )
                SharedActivityFooterSlot.Mention ->
                    SharedActivityFooterBadge(
                        label = stringResource(MR.strings.activity_mention_you),
                        slot = slot,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                SharedActivityFooterSlot.New ->
                    SharedActivityFooterBadge(
                        label = stringResource(MR.strings.activity_new_count, item.unreadCount),
                        slot = slot,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                SharedActivityFooterSlot.Unfollowed ->
                    SharedActivityFooterBadge(
                        label = stringResource(MR.strings.thread_row_unfollowed),
                        slot = slot,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                SharedActivityFooterSlot.Draft ->
                    SharedActivityFooterDraftBadge(
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
            }
        }
    }
}

@Composable
private fun SharedActivityFooterDraftBadge(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(20.dp)
                .border(RaftAppStroke.Thin, RaftColor.BorderBlack)
                .background(RaftColor.SurfaceWhite)
                .raftSquareCorners(),
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(
            name = RaftLucideIconName.Pencil,
            modifier = Modifier.size(10.dp),
            color = "#FF000000"
        )
    }
}

@Composable
private fun SharedActivityFooterBadge(
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
        leadingIcon = visual.leadingIcon,
        contentColor = visual.foregroundColor
    )
}

internal fun InboxItem.activityTaskBadgeText(): String =
    "#${taskNumber}" + taskClaimedByName
        ?.takeIf { it.isNotBlank() }
        ?.let { " @$it" }
        .orEmpty()

@Composable
private fun SharedActivitySmallIconButton(
    iconName: RaftLucideIconName,
    modifier: Modifier = Modifier,
    clickIdentity: Any? = null,
    onClick: () -> Unit
) {
    RaftBrutalIconButton(
        iconName = iconName,
        modifier = modifier,
        clickIdentity = clickIdentity,
        onClick = onClick,
        size = 28.dp,
        shadowOffset = 2.dp
    )
}

@Composable
private fun SharedActivitySmallButton(
    label: String,
    iconName: RaftLucideIconName,
    onClick: () -> Unit
) {
    RaftBrutalActionButton(
        label = label,
        leadingIcon = iconName,
        minHeight = 30.dp,
        minWidth = 0.dp,
        shadowOffset = 2.dp,
        backgroundColor = RaftColor.SurfaceWhite,
        textStyle = RaftType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp),
        onClick = onClick
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
        RaftBrutalActionButton(
            label = stringResource(MR.strings.common_retry),
            minHeight = 30.dp,
            minWidth = 0.dp,
            shadowOffset = 2.dp,
            backgroundColor = RaftColor.SurfaceWhite,
            textStyle = RaftType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 14.sp),
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
        }
    )

private fun InboxFilter.emptyCopy(): String =
    when (this) {
        InboxFilter.All -> "No unread conversations or mentions yet."
        InboxFilter.Unread -> "No unread conversations."
        InboxFilter.Mentions -> "No mentions yet."
    }

@Composable
private fun InboxFilter.localizedEmptyCopy(): String =
    stringResource(
        when (this) {
            InboxFilter.All -> MR.strings.inbox_empty_all_detail
            InboxFilter.Unread -> MR.strings.inbox_empty_unread_detail
            InboxFilter.Mentions -> MR.strings.inbox_empty_mentions_detail
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

private fun InboxItem.senderDisplayName(
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
    val body: String
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
    val leadingIcon: RaftLucideIconName? = null,
    val foregroundColor: Color? = null
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
                leadingIcon = statusMeta.icon,
                foregroundColor = statusMeta.foregroundColor
            )
        }
        SharedActivityFooterSlot.Replies -> SharedActivityFooterBadgeVisual(RaftBrutalTone.White)
        SharedActivityFooterSlot.Mention -> SharedActivityFooterBadgeVisual(RaftBrutalTone.Yellow, RaftLucideIconName.AtSign)
        SharedActivityFooterSlot.New -> SharedActivityFooterBadgeVisual(RaftBrutalTone.Pink)
        SharedActivityFooterSlot.Unfollowed ->
            SharedActivityFooterBadgeVisual(RaftBrutalTone.Stone, RaftLucideIconName.BellOff)
        SharedActivityFooterSlot.Draft -> SharedActivityFooterBadgeVisual(RaftBrutalTone.White, RaftLucideIconName.Pencil)
    }

internal fun InboxItem.activityFooterSlots(
    activityTaskStatusBadgesEnabled: Boolean = true
): List<SharedActivityFooterSlot> {
    val slots = mutableListOf<SharedActivityFooterSlot>()
    if (activityTaskStatusBadgesEnabled && taskNumber != null && taskStatus != null) {
        slots += SharedActivityFooterSlot.Task
    }
    if (kind == InboxItemKind.Thread) {
        slots += SharedActivityFooterSlot.Replies
    }
    if (!isUnfollowedActivityThread && hasMention && unreadCount > 0) {
        slots += SharedActivityFooterSlot.Mention
    }
    if (!isUnfollowedActivityThread && unreadCount > 0) {
        slots += SharedActivityFooterSlot.New
    }
    if (isUnfollowedActivityThread) {
        slots += SharedActivityFooterSlot.Unfollowed
    }
    if (hasDraft) {
        slots += SharedActivityFooterSlot.Draft
    }
    return slots
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
