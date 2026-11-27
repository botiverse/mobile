package build.raft.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import build.raft.app.MEMBER_DETAIL_PAGE
import build.raft.app.MemberDetailIdentity
import build.raft.app.MemberDetailProfileChannelIdKey
import build.raft.app.RAFT_ARTIFACT_VIEWER_PAGE
import build.raft.app.RAFT_BESIDE_VISIBILITY_TIMEOUT_MILLIS
import build.raft.app.RaftAdaptiveDetailCloseRequest
import build.raft.app.RaftAdaptiveDetailIdentity
import build.raft.app.RaftBesideArtifact
import build.raft.app.RaftBesideKind
import build.raft.app.RaftBesideMemoryRepository
import build.raft.app.RaftBesideProfile
import build.raft.app.RaftBesideProfileKind
import build.raft.app.RaftBesideRestorePlan
import build.raft.app.RaftBesideState
import build.raft.app.RaftBesideVisibility
import build.raft.app.RaftBesideVisit
import build.raft.app.RaftDetailConversation
import build.raft.app.RaftMembersPayload
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.RaftWideNavEntry
import build.raft.app.RaftWideSideColumns
import build.raft.app.WireThreadLaunchPayload
import build.raft.app.base.BridgeModule
import build.raft.app.besideMainThread
import build.raft.app.data.AdaptiveSideThreadOpen
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MemberRepository
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.SharedAdaptiveWorkspaceStore
import build.raft.app.data.SharedArtifactSummaryStore
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.answersFor
import build.raft.app.data.artifactIdOrNull
import build.raft.app.data.openSideThread
import build.raft.app.data.sideThreadColumnAvailable
import build.raft.app.data.visibleSideThread
import build.raft.app.detailConversation
import build.raft.app.get
import build.raft.app.isRaftBesideId
import build.raft.app.messages
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.nonBlank
import build.raft.app.normalizedThreadChannelKind
import build.raft.app.parseConversationKindOrNull
import build.raft.app.raftArtifactViewerPageData
import build.raft.app.raftArtifactViewerTargetOrNull
import build.raft.app.raftBesideConversationOf
import build.raft.app.shown
import build.raft.app.thread.ThreadParentNavigationPrevious
import build.raft.app.thread.key
import build.raft.app.toJsonObject
import build.raft.app.toKuiklyJson
import build.raft.app.toMemberDetailPageData
import build.raft.app.toRaftMemberDetailPayload
import build.raft.app.withRaftShellStoreScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * What was open beside a conversation on the wide layout, remembered per conversation (shell/RaftBesideMemory.kt, web
 * docs/frontend/beside-memory.md), wired here and not in SettingsHomePage's RaftAppShellReadyContent, whose method
 * stays under the JVM method-size budget (scripts/ci/check_jvm_method_size.py).
 *
 * - A visit is one person's stay in one conversation ([AppShellBesideVisitKey]): from entering Chat's or the DMs tab's
 *   conversation on the wide layout to leaving it (another conversation, another surface, a page over the shell, the
 *   narrow layout). Within it, the detail changing (a message jumped to, a reply thread replacing the conversation,
 *   folding into it or going back to it) is no new entry: the side columns stay (RaftWideSideColumns.kt).
 * - What shows beside the conversation ([AppShellBesideColumnsHost.shown]: the side thread, or a reply thread shown in
 *   the conversation's place; the Artifact column; the profile at the top of the profile cell with an agent's tab) is
 *   remembered each time it changes, an open as a close; leaving writes nothing.
 * - Entering, every time (a list click, a notification, a link, the rail, Back or Forward through the wide history,
 *   whose entries keep no panes), restores what was remembered and the entry does not name, once it is found still
 *   visible ([AppShellBesideVisibility]); the one in front opens last, so Back closes it first. Gone is forgotten and
 *   never shown; no answer keeps it for the next visit.
 */

/** One person's visit to one conversation ([RaftDetailConversation]): what the memory is kept by while it shows. */
internal data class AppShellBesideVisitKey(val accountId: String, val conversation: RaftDetailConversation)

/** What the beside memory reads and opens beside a conversation (the shell's: [AppShellBesideColumnsHost]). */
internal interface AppShellBesideHost {
    /** What shows beside [conversation] now; null while the shell shows another conversation (it is moving to it). */
    fun shown(conversation: RaftDetailConversation): RaftBesideState?

    /** Opens [state] beside [conversation] as opening each by hand does, [front] last (Back closes it first). */
    fun open(conversation: RaftDetailConversation, state: RaftBesideState, front: RaftBesideKind?)
}

/** Whether what a restore would open is still visible to the person, per kind of [state] (web `checkBesideVisibility`). */
internal fun interface AppShellBesideVisibilityCheck {
    suspend fun check(conversation: RaftDetailConversation, state: RaftBesideState): Map<RaftBesideKind, RaftBesideVisibility>
}

/**
 * The memory's visits ([enter], [commit], [restore], [leave]), over the person's stored entries ([repository]), what
 * the shell shows and opens ([host]) and the visibility checks ([visibility]). Plain memory, driven on the main thread
 * by [AppShellBesideMemoryEffect].
 */
internal class AppShellBesideMemory(
    private val repository: RaftBesideMemoryRepository,
    private val host: AppShellBesideHost,
    private val visibility: AppShellBesideVisibilityCheck,
    private val log: (String) -> Unit = {},
) {
    private class Visit(val key: AppShellBesideVisitKey, var state: RaftBesideVisit, var saved: RaftBesideState?) {
        /** What a restore puts in front, for the commit that follows its opens. */
        var pick: RaftBesideKind? = null
    }

    private var visit: Visit? = null

    /** What shows beside [key]'s conversation now ([AppShellBesideHost.shown]). */
    fun shown(key: AppShellBesideVisitKey): RaftBesideState? = host.shown(key.conversation)

    /**
     * Entering [key]'s conversation: what shows is what the entry names, the rest of what was remembered is held. The
     * restore to check and open (null: nothing); [threadTaken]: the entry focuses a message of the conversation.
     */
    fun enter(key: AppShellBesideVisitKey, threadTaken: Boolean): RaftBesideRestorePlan? {
        visit = null
        val named = host.shown(key.conversation) ?: return null
        val conversation = key.conversation
        val remembered = repository.read(key.accountId, conversation.serverId, conversation.conversationId)
        val entered = Visit(key, RaftBesideVisit.enter(remembered, named), saved = remembered)
        visit = entered
        save(entered)
        return entered.state.restorePlan(named, threadTaken).takeUnless { it.restore.isEmpty }
    }

    /** The shell shows [named] beside [key]'s conversation (an open, a close): remembered at once. */
    fun commit(key: AppShellBesideVisitKey, named: RaftBesideState) {
        val current = visit?.takeIf { it.key == key } ?: return
        current.state = current.state.shows(named, current.pick)
        current.pick = null
        save(current)
    }

    /**
     * Checks [plan]'s things, then opens those still visible and not opened by hand meanwhile, unless the visit ended;
     * [threadTaken]: whether the thread's place is taken now.
     */
    suspend fun restore(key: AppShellBesideVisitKey, plan: RaftBesideRestorePlan, threadTaken: () -> Boolean) {
        val answers = visibility.check(key.conversation, plan.restore)
        val current = visit?.takeIf { it.key == key } ?: return
        val now = host.shown(key.conversation) ?: return
        val step = current.state.restoreStep(plan, answers, now, threadTaken())
        current.state = step.visit
        log(
            "beside_memory_restore " +
                plan.restore.kinds.joinToString(" ") { "${it.wireName}=${answers[it]?.name?.lowercase() ?: "unknown"}" } +
                " open=" + step.open.kinds.joinToString(",") { it.wireName }.ifEmpty { "none" },
        )
        if (!step.open.isEmpty) {
            current.pick = step.front
            host.open(key.conversation, step.open, step.front)
        }
        val shown = host.shown(key.conversation)
        if (shown != null) commit(key, shown) else save(current)
    }

    /** The visit ended: nothing is written for it. */
    fun leave() {
        visit = null
    }

    private fun save(current: Visit) {
        val next = current.state.remembered.takeUnless { it.isEmpty }
        if (next == current.saved) return
        val conversation = current.key.conversation
        repository.write(current.key.accountId, conversation.serverId, conversation.conversationId, next ?: RaftBesideState())
        current.saved = next
    }
}

/**
 * The shell's side of the memory: the side thread of the shared workspace ([workspace], or a reply thread shown in the
 * conversation's place), and the Artifact and profile columns ([columns]). Opens as the pages do: the side thread
 * through [openSideThread] (only where its column has room, never in the conversation's place), the columns' pages
 * with the shell's store scope ([storeScope]).
 */
internal class AppShellBesideColumnsHost(
    private val columns: RaftWideSideColumns,
    private val workspace: SharedAdaptiveWorkspaceStore,
    private val desktopMode: () -> Boolean,
    private val storeScope: () -> RaftShellStoreScope,
    private val log: (String) -> Unit = {},
) : AppShellBesideHost {
    override fun shown(conversation: RaftDetailConversation): RaftBesideState? {
        if (columns.conversation != conversation) return null
        val session = workspace.snapshot()
        val main = session.workspace?.selection?.identity ?: return null
        if (main.detailConversation() != conversation) return null
        val profileEntry = columns.profile.profile.entries.lastOrNull()?.takeIf { it.pageName == MEMBER_DETAIL_PAGE }
        val profilePage = profileEntry?.pageData?.toKuiklyJson()?.toRaftMemberDetailPayload()
        val profile = profilePage?.identity?.toBesideProfile(conversation.serverId)
        val agentTab =
            if (profile?.kind == RaftBesideProfileKind.Agent) {
                columns.profileAgentTab?.takeIf { it.entryId == profileEntry?.id }?.tab ?: profilePage?.initialAgentTab
            } else {
                null
            }
        val artifact = columns.artifact.entry?.pageData?.toKuiklyJson()?.raftArtifactViewerTargetOrNull()
        return RaftBesideState(
            thread = session.visibleSideThread()?.payload?.parentMessageId ?: main.besideMainThread(),
            artifact = artifact?.let { RaftBesideArtifact(it.id, it.version) },
            profile = profile,
            agentTab = agentTab,
        )
    }

    override fun open(conversation: RaftDetailConversation, state: RaftBesideState, front: RaftBesideKind?) {
        // The one in front last: Back closes the one opened last first (RaftArtifactColumnPane.isOnTopOf).
        for (kind in state.kinds.sortedBy { it == front }) {
            when (kind) {
                RaftBesideKind.Thread -> state.thread?.let { openThread(conversation, it) }
                RaftBesideKind.Artifact -> state.artifact?.let { openArtifact(conversation, it) }
                RaftBesideKind.Profile -> state.profile?.let { openProfile(conversation, it, state.agentTab) }
            }
        }
    }

    private fun openThread(conversation: RaftDetailConversation, parentMessageId: String) {
        val desktop = desktopMode()
        val after =
            workspace.update { session ->
                val current = session.workspace ?: return@update session
                val main = current.selection ?: return@update session
                val mainPayload = session.launchPayload ?: return@update session
                if (main.generation <= 0L || main.identity.detailConversation() != conversation) return@update session
                val target = raftBesideThreadLaunchPayload(mainPayload, main.identity, parentMessageId) ?: return@update session
                val source = RaftAdaptiveDetailCloseRequest(current.shellInstanceId, main.identity.key, main.generation)
                val outcome = session.openSideThread(source, target, conversation.serverId, session.sideThreadColumnAvailable(desktop))
                (outcome as? AdaptiveSideThreadOpen.Opened)?.session ?: session
            }
        // No room for its column (or the detail is itself a thread): it stays remembered, for the next visit.
        if (after.visibleSideThread()?.payload?.parentMessageId != parentMessageId) log("beside_memory_thread_not_opened reason=no_column")
    }

    private fun openArtifact(conversation: RaftDetailConversation, artifact: RaftBesideArtifact) {
        val id = artifactIdOrNull(artifact.id) ?: return
        val pageData =
            raftArtifactViewerPageData(ArtifactLinkTarget(id, artifact.version), conversation.conversationId)
                .withRaftShellStoreScope(storeScope())
                .toJsonObject()
        columns.artifact = columns.artifact.open(RAFT_ARTIFACT_VIEWER_PAGE, pageData, columns.profile)
    }

    private fun openProfile(conversation: RaftDetailConversation, profile: RaftBesideProfile, agentTab: String?) {
        val kind = if (profile.kind == RaftBesideProfileKind.Agent) MemberKind.Agent else MemberKind.Human
        val identity = MemberDetailIdentity(MemberId(profile.id), kind, ServerId(conversation.serverId))
        val pageData =
            RaftMembersPayload(instanceId = "beside-${conversation.conversationId}", initialAgentTab = agentTab)
                .toMemberDetailPageData(identity) ?: return
        // A person's profile names their servers in the channel it is opened beside (none beside a DM).
        val mainSurface = workspace.snapshot().workspace?.selection?.identity?.surfaceKind
        if (kind == MemberKind.Human && mainSurface == RaftThreadSurfaceKind.Channel) {
            pageData.put(MemberDetailProfileChannelIdKey, conversation.conversationId)
        }
        columns.profile = columns.profile.open(MEMBER_DETAIL_PAGE, pageData.withRaftShellStoreScope(storeScope()).toJsonObject())
    }
}

/** A profile as the memory keeps it: an agent's or a person's of [serverId] (another server's member is not kept). */
private fun MemberDetailIdentity.toBesideProfile(serverId: String): RaftBesideProfile? {
    val beside =
        when (kind) {
            MemberKind.Agent -> RaftBesideProfileKind.Agent
            MemberKind.Human -> RaftBesideProfileKind.Human
            else -> return null
        }
    val source = sourceServerId?.value
    if (source != null && source != serverId) return null
    return RaftBesideProfile(beside, memberId.value)
}

/**
 * The reply thread under [parentMessageId] of the conversation [main] shows ([mainIdentity], a channel or a DM), as the
 * side column opens it (ThreadRouteSupport `replyThreadLoadingPayload`): by its parent only, the thread page reads the
 * rest; display settings and time as the conversation's. Null for any other detail.
 */
internal fun raftBesideThreadLaunchPayload(
    main: WireThreadLaunchPayload,
    mainIdentity: RaftAdaptiveDetailIdentity,
    parentMessageId: String,
): WireThreadLaunchPayload? {
    val dm = mainIdentity.surfaceKind == RaftThreadSurfaceKind.DirectMessage
    if (!dm && mainIdentity.surfaceKind != RaftThreadSurfaceKind.Channel) return null
    val channelKind =
        main.channelKind.normalizedThreadChannelKind()?.takeIf {
            val kind = parseConversationKindOrNull(it)
            kind == ConversationKind.Channel || kind == ConversationKind.Joint
        }
    return WireThreadLaunchPayload(
        instanceId = "shared-shell-thread-pending-$parentMessageId",
        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
        jointChannelId = if (dm) null else main.jointChannelId.nonBlank(),
        parentChannelId = mainIdentity.channelId ?: mainIdentity.threadId,
        parentMessageId = parentMessageId,
        parentSurfaceKind = if (dm) RaftThreadSurfaceKind.DirectMessage else RaftThreadSurfaceKind.Channel,
        parentChannelKind = if (dm) null else channelKind ?: RaftThreadSurfaceKind.Channel,
        parentNavigationHint = ThreadParentNavigationPrevious,
        originParentMessageId = parentMessageId,
        navigationLabel = "Close",
        alignOwnMessagesRight = main.alignOwnMessagesRight,
        compactConsecutiveMessages = main.compactConsecutiveMessages,
        showReactionActions = main.showReactionActions,
        localOffsetMinutes = main.localOffsetMinutes,
        todayEpochDay = main.todayEpochDay,
        nowEpochMillis = main.nowEpochMillis,
        timeFormat = main.timeFormat,
    )
}

/**
 * The visibility checks before a restore (web besideVisibility.ts), every kind together, each answered from what the
 * app holds where it can be, else by one request given [timeoutMillis]:
 * - the thread: its parent message, read alone (`messages/context`, [ConversationRepository.resolveMessageContext]);
 *   gone when it is deleted, missing, or the read is refused (400, 403, 404);
 * - the Artifact: the summaries kept for the viewer's cards, else asked ([SharedArtifactSummaryStore]); gone when it is
 *   unavailable or deleted;
 * - a profile: in the server's member list, else one read of it (`GET /agents/:id`, the person's profile); gone when
 *   refused.
 * Anything else (offline, a 5xx, signed out, too slow) is no answer.
 */
internal class AppShellBesideVisibility(
    private val artifactSummaries: SharedArtifactSummaryStore,
    private val serverMembers: SharedServerMembersStore,
    private val memberRepository: MemberRepository,
    private val agentsApi: RaftAgentsApiClient,
    private val conversationRepository: ConversationRepository,
    private val timeoutMillis: Long = RAFT_BESIDE_VISIBILITY_TIMEOUT_MILLIS,
) : AppShellBesideVisibilityCheck {
    override suspend fun check(conversation: RaftDetailConversation, state: RaftBesideState): Map<RaftBesideKind, RaftBesideVisibility> =
        coroutineScope {
            state.kinds
                .map { kind -> kind to async { answer(conversation, state, kind) } }
                .associate { (kind, pending) -> kind to pending.await() }
        }

    private suspend fun answer(conversation: RaftDetailConversation, state: RaftBesideState, kind: RaftBesideKind): RaftBesideVisibility =
        try {
            withTimeoutOrNull(timeoutMillis) {
                when (kind) {
                    RaftBesideKind.Thread -> state.thread?.let { thread(conversation, it) }
                    RaftBesideKind.Artifact -> state.artifact?.let { artifact(conversation.serverId, it) }
                    RaftBesideKind.Profile -> state.profile?.let { profile(conversation.serverId, it) }
                }
            } ?: RaftBesideVisibility.Unknown
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            RaftBesideVisibility.Unknown
        }

    private suspend fun thread(conversation: RaftDetailConversation, messageId: String): RaftBesideVisibility =
        when (val result = conversationRepository.resolveMessageContext(MessageId(messageId), ChannelId(conversation.conversationId))) {
            is DataResult.Content -> {
                val context = result.data
                val parent =
                    context.messages.firstOrNull { it.id.value == messageId }
                        ?: context.parentMessage?.takeIf { it.id.value == messageId }
                if (parent != null && parent.deletedAt == null) RaftBesideVisibility.Visible else RaftBesideVisibility.Gone
            }
            is DataResult.Empty -> RaftBesideVisibility.Gone
            else -> result.besideRefusal()
        }

    private suspend fun artifact(serverId: String, artifact: RaftBesideArtifact): RaftBesideVisibility {
        val id = artifactIdOrNull(artifact.id) ?: return RaftBesideVisibility.Gone
        val targets = listOf(ArtifactLinkTarget(id, artifact.version))
        val key = targets.single().key
        val kept = artifactSummaries.state.value.answersFor(serverId, targets)[key]
        if (kept != null && kept != ArtifactSummaryAnswer.Failed) return kept.besideVisibility()
        artifactSummaries.request(serverId, targets)
        val answered = artifactSummaries.answersFlow(serverId, targets).first { it[key] != null }[key]
        return answered?.besideVisibility() ?: RaftBesideVisibility.Unknown
    }

    private suspend fun profile(serverId: String, profile: RaftBesideProfile): RaftBesideVisibility {
        val kind = if (profile.kind == RaftBesideProfileKind.Agent) MemberKind.Agent else MemberKind.Human
        val listed = serverMembers.state.value.entryFor(ServerId(serverId))?.membersById?.get(profile.id)
        if (listed?.kind == kind) return RaftBesideVisibility.Visible
        val result =
            if (kind == MemberKind.Agent) {
                agentsApi.agent(MemberId(profile.id))
            } else {
                memberRepository.getMemberProfile(DataRequestPriority.Visible, MemberId(profile.id), ServerId(serverId))
            }
        return if (result is DataResult.Content) RaftBesideVisibility.Visible else result.besideRefusal()
    }
}

private fun ArtifactSummaryAnswer.besideVisibility(): RaftBesideVisibility =
    when (this) {
        is ArtifactSummaryAnswer.Ok -> RaftBesideVisibility.Visible
        ArtifactSummaryAnswer.Unavailable, is ArtifactSummaryAnswer.Deleted -> RaftBesideVisibility.Gone
        ArtifactSummaryAnswer.Failed -> RaftBesideVisibility.Unknown
    }

/** A read that failed (web `isFinalRefusal`): 400, 403 and 404 are final for this reader, anything else is no answer. */
private fun DataResult<*>.besideRefusal(): RaftBesideVisibility =
    when (this) {
        is DataResult.NoPermission -> if (blocker.code == "auth_required") RaftBesideVisibility.Unknown else RaftBesideVisibility.Gone
        is DataResult.Error ->
            if (error.code == "http_400" || error.code == "http_404") RaftBesideVisibility.Gone else RaftBesideVisibility.Unknown
        else -> RaftBesideVisibility.Unknown
    }

/** What the memory reads of the columns, compared by value: a change to any is an open or a close to look at. */
private fun RaftWideSideColumns.besideStamp(): List<Any?> = listOf(conversation, profile, artifact, profileAgentTab)

/**
 * The shell's beside memory: [wideNavShowing] is what the wide layout shows (null off it), [accountId] the person
 * signed in, [columns] the side columns, [adaptiveWorkspaceStore] the shared workspace (the side thread); the rest are
 * the shell's own stores and repositories, for the visibility checks and the pages it opens.
 */
@Composable
internal fun AppShellBesideMemoryEffect(
    wideNavShowing: RaftWideNavEntry?,
    accountId: String?,
    columns: RaftWideSideColumns,
    adaptiveWorkspaceStore: SharedAdaptiveWorkspaceStore,
    desktopModeOnLargeScreen: Boolean,
    navigationStoreScope: RaftShellStoreScope,
    sharedStores: RaftSharedStoreProvider,
    serverMembersStore: SharedServerMembersStore,
    memberRepository: MemberRepository,
    conversationRepository: ConversationRepository,
    agentsApiClient: RaftAgentsApiClient,
    bridge: BridgeModule,
) {
    val conversation = raftBesideConversationOf(wideNavShowing)
    val account = accountId?.takeIf { isRaftBesideId(it) }
    val key = if (conversation != null && account != null) AppShellBesideVisitKey(account, conversation.detail) else null
    val latestConversation by rememberUpdatedState(conversation)
    val latestDesktopMode by rememberUpdatedState(desktopModeOnLargeScreen)
    val latestStoreScope by rememberUpdatedState(navigationStoreScope)
    val latestBridge by rememberUpdatedState(bridge)
    val memory =
        remember(
            columns,
            adaptiveWorkspaceStore,
            sharedStores,
            serverMembersStore,
            memberRepository,
            conversationRepository,
            agentsApiClient,
        ) {
            val log: (String) -> Unit = { latestBridge.log(it) }
            AppShellBesideMemory(
                repository = sharedStores.besideMemoryRepository,
                host = AppShellBesideColumnsHost(columns, adaptiveWorkspaceStore, { latestDesktopMode }, { latestStoreScope }, log),
                visibility =
                    AppShellBesideVisibility(
                        artifactSummaries = sharedStores.artifactSummaryStore,
                        serverMembers = serverMembersStore,
                        memberRepository = memberRepository,
                        agentsApi = agentsApiClient,
                        conversationRepository = conversationRepository,
                    ),
                log = log,
            )
        }
    LaunchedEffect(memory, key) {
        memory.leave()
        val visitKey = key ?: return@LaunchedEffect
        // The columns hold this conversation's pages once the shell has moved them to it (rememberRaftWideSideColumns).
        snapshotFlow { columns.conversation }.first { it == visitKey.conversation }
        val plan = memory.enter(visitKey, threadTaken = latestConversation?.threadTaken == true)
        if (plan != null) launch { memory.restore(visitKey, plan) { latestConversation?.threadTaken == true } }
        // Every open and close beside it, the side thread's too, is remembered when it happens.
        combine(adaptiveWorkspaceStore.state, snapshotFlow { columns.besideStamp() }) { _, _ -> memory.shown(visitKey) }
            .distinctUntilChanged()
            .collect { shown -> if (shown != null) memory.commit(visitKey, shown) }
    }
}
