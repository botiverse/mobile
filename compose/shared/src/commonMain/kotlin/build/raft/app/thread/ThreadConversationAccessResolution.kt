package build.raft.app.thread

import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.ReplyCacheProvenance
import build.raft.app.ReplyResolutionScope
import build.raft.app.data.ChannelRepository
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeConversationRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.HomeSnapshotLookup
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.TaskConversationChannelRef
import build.raft.app.data.toSharedConversationDirectoryProjection
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.isCurrentOrSuccessorForLocalSend
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.resolve
import kotlinx.coroutines.CancellationException

/**
 * The conversation access paths of [SharedThreadDataSource] (the access candidate a conversation, task
 * or reply thread opens with, the reply-parent fallback, the Home snapshot they read and the names it
 * gives), moved out of it unchanged; the data source forwards to them. Holds no state and takes no lock.
 */
internal class ThreadConversationAccessResolution(
    private val provenanceOwner: ThreadReplyCacheProvenanceOwner,
    private val serverMembersStore: SharedServerMembersStore?,
    private val conversationRepository: ConversationRepository?,
    private val channelRepository: ChannelRepository?,
    private val homeSnapshotProvider: (suspend (ServerId) -> HomeSnapshot?)?,
    private val homeSnapshotLookupProvider: (suspend (ServerId) -> HomeSnapshotLookup?)?,
    private val immediateHomeSnapshotLookupProvider: ((ServerId) -> HomeSnapshotLookup?)?,
    private val diagnosticsLogger: (String) -> Unit,
    private val readSession: suspend () -> StoredAuthSession?,
    private val readReplyResolutionScope: suspend () -> ReplyResolutionScope?,
    private val loadTaskConversationRef: suspend (ChannelId) -> DataResult<TaskConversationChannelRef>,
) {
    private fun isCurrentReplyCacheProvenanceForLocalSend(expected: ReplyCacheProvenance): Boolean =
        provenanceOwner.isCurrentReplyCacheProvenanceForLocalSend(expected)

    suspend fun withConversationAccessFallback(basePayload: RaftThreadScreenPayload): RaftThreadScreenPayload {
        val session = readSession() ?: return basePayload
        return basePayload.withConversationAccessFallback(resolveConversationAccessFallback(basePayload, session))
    }

    suspend fun withKnownMarkdownChannelNames(payload: RaftThreadScreenPayload): RaftThreadScreenPayload {
        val session = readSession() ?: return payload
        return payload.withKnownMarkdownChannelNames(session)
    }

    suspend fun homeConversationContextResolution(
        payload: RaftThreadScreenPayload
    ): ThreadHomeContextResolution {
        val session = readSession() ?: return ThreadHomeContextResolution(payload = payload)
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        if (serverId != null) {
            immediateHomeSnapshotLookupProvider?.invoke(serverId)
                ?.takeIf { lookup ->
                    lookup.snapshot.server.id == serverId &&
                        lookup.snapshot.currentMember.id.value == session.user.id
                }
                ?.let { lookup ->
                    val home = lookup.snapshot
                    val accessFallback = resolveConversationAccessFallback(payload, home)
                    // A non-authoritative in-memory Home snapshot may grant fast positive write
                    // access in 0ms without blocking on an in-flight Home refresh (e.g.
                    // homeRealtimeDirty on slow networks). Negative/archived/missing rows fall
                    // through to the bounded authoritative wait below.
                    if (
                        accessFallback != null &&
                        accessFallback.joined &&
                        accessFallback.permissions.canSend &&
                        !accessFallback.archived
                    ) {
                        return ThreadHomeContextResolution(
                            payload =
                                payload
                                    .withConversationAccessFallback(accessFallback)
                                    .withKnownMarkdownChannelNames(home),
                            accessCandidate = accessFallback
                        )
                    }
                }
        }
        val home = currentHomeSnapshot(session) ?: return ThreadHomeContextResolution(payload = payload)
        val accessFallback = resolveConversationAccessFallback(payload, home)
        return ThreadHomeContextResolution(
            payload =
                payload
                    .withConversationAccessFallback(accessFallback)
                    .withKnownMarkdownChannelNames(home),
            accessCandidate = accessFallback
        )
    }

    /**
     * Reply write authority belongs to the exact parent Channel/DM. Resolve it independently from
     * the full Home aggregate so auxiliary unread enrichment cannot consume the initial-access
     * timeout after the canonical conversation list is already available.
     */
    suspend fun initialConversationAccessResolution(
        payload: RaftThreadScreenPayload
    ): ThreadHomeContextResolution =
        when {
            payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread -> replyParentConversationAccessResolution(payload)
            payload.isTaskConversationSurface() -> taskConversationAccessResolution(payload)
            else -> homeConversationContextResolution(payload)
        }

    /**
     * Task v2: a task conversation has no Home row of its own. Its write authority is its task
     * channel's (`withTaskConversationContext`: the parent's joined + canSend), so the parent's Home
     * row is the same initial candidate a Channel route reads from its own row. Without it the
     * composer waited for messages/context -> channels/:id -> channels/:parent in series (Hands
     * fa151b10: 2.4-4.8 s after the messages showed). Only a joined + canSend parent is used; the
     * canonical load still wins and a later NoPermission still closes access.
     */
    private suspend fun taskConversationAccessResolution(
        payload: RaftThreadScreenPayload
    ): ThreadHomeContextResolution {
        val conversationId =
            listOfNotNull(payload.streamChannelId, payload.sourceThreadId)
                .firstOrNull { it.isNotBlank() }
        val session = readSession()
        val serverId = session?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val immediateHome =
            if (session != null && serverId != null) {
                immediateHomeSnapshotLookupProvider?.invoke(serverId)
                    ?.takeIf { lookup ->
                        lookup.snapshot.server.id == serverId &&
                            lookup.snapshot.currentMember.id.value == session.user.id
                    }
                    ?.snapshot
            } else {
                null
            }
        var loadedRef: TaskConversationChannelRef? = null
        var refAttempted = false
        if (immediateHome != null) {
            val ownCandidate = resolveConversationAccessFallback(payload, immediateHome)
            if (
                ownCandidate != null &&
                ownCandidate.joined &&
                ownCandidate.permissions.canSend &&
                !ownCandidate.archived
            ) {
                return ThreadHomeContextResolution(
                    payload =
                        payload
                            .withConversationAccessFallback(ownCandidate)
                            .withKnownMarkdownChannelNames(immediateHome),
                    accessCandidate = ownCandidate,
                )
            }
            if (conversationId != null) {
                refAttempted = true
                loadedRef =
                    (loadTaskConversationRef(ChannelId(conversationId)) as? DataResult.Content)
                        ?.data
                        ?.takeIf { it.conversationChannelId.value == conversationId }
                val immediateParent =
                    loadedRef?.let { ref ->
                        immediateHome.toSharedConversationDirectoryState()
                            .resolve(ref.parentChannelId.value)
                            ?.toConversationAccessCandidate()
                    }
                if (
                    immediateParent != null &&
                    immediateParent.joined &&
                    immediateParent.permissions.canSend &&
                    !immediateParent.archived
                ) {
                    diagnosticsLogger(
                        "thread_initial_task_access conversationId=$conversationId " +
                            "parentChannelId=${loadedRef?.parentChannelId?.value.orEmpty()} parentRow=true writable=true"
                    )
                    return ThreadHomeContextResolution(
                        payload = payload.withKnownMarkdownChannelNames(immediateHome),
                        accessCandidate =
                            ConversationAccessCandidate(
                                ids = setOf(conversationId),
                                conversationKind = ConversationKind.Task,
                                surfaceKind = payload.surfaceKind,
                                joined = true,
                                archived = false,
                                permissions = immediateParent.permissions.copy(canCreateTask = false),
                            )
                    )
                }
            }
        }
        val base = homeConversationContextResolution(payload)
        if (base.accessCandidate != null) return base
        if (conversationId == null) return base
        val ref =
            if (refAttempted) {
                loadedRef
            } else {
                (loadTaskConversationRef(ChannelId(conversationId)) as? DataResult.Content)
                    ?.data
                    ?.takeIf { it.conversationChannelId.value == conversationId }
            }
        if (ref == null) return base
        val currentSession = session ?: readSession() ?: return base
        val home = currentHomeSnapshot(currentSession) ?: return base
        val parent =
            home.toSharedConversationDirectoryState()
                .resolve(ref.parentChannelId.value)
                ?.toConversationAccessCandidate()
        val writable = parent != null && parent.joined && parent.permissions.canSend && !parent.archived
        diagnosticsLogger(
            "thread_initial_task_access conversationId=$conversationId " +
                "parentChannelId=${ref.parentChannelId.value} parentRow=${parent != null} writable=$writable"
        )
        if (parent == null || !writable) return base
        return base.copy(
            accessCandidate =
                ConversationAccessCandidate(
                    ids = setOf(conversationId),
                    conversationKind = ConversationKind.Task,
                    surfaceKind = payload.surfaceKind,
                    joined = true,
                    archived = false,
                    permissions = parent.permissions.copy(canCreateTask = false),
                )
        )
    }

    private suspend fun replyParentConversationAccessResolution(
        payload: RaftThreadScreenPayload
    ): ThreadHomeContextResolution {
        val (parentId, parentKind) =
            payload.replyParentAccessIdentityOrNull()
                ?: return ThreadHomeContextResolution(
                    payload = payload,
                    requiresBackgroundHomeContextHydration = true,
                )
        val resolutionScope =
            readReplyResolutionScope()
                ?: return ThreadHomeContextResolution(
                    payload = payload,
                    requiresBackgroundHomeContextHydration = true,
                )
        val serverId =
            resolutionScope.provenance.serverId
                .takeIf { it.isNotBlank() }
                ?.let(::ServerId)
                ?: return ThreadHomeContextResolution(
                    payload = payload,
                    requiresBackgroundHomeContextHydration = true,
                )

        // Check the in-memory Home snapshot immediately (0ms, non-suspending) before awaiting any
        // in-flight Home refresh. A positive writable non-archived row (or an authoritative row)
        // resolves right away; a non-authoritative missing/negative/archived row falls through to
        // the bounded authoritative lookup below so a stale cache never locks the thread into Join.
        immediateHomeSnapshotLookupProvider?.invoke(serverId)
            ?.takeIf { lookup ->
                lookup.snapshot.server.id == serverId &&
                    lookup.snapshot.currentMember.id.value == resolutionScope.provenance.principalId
            }
            ?.let { lookup ->
                val home = lookup.snapshot
                val candidate =
                    payload.exactReplyParentAccessCandidate(
                        serverId = serverId,
                        parentId = parentId,
                        parentKind = parentKind,
                        conversations = home.channels + home.directMessages,
                    ) ?: return@let
                val positiveWritable =
                    candidate.joined && candidate.permissions.canSend && !candidate.archived
                if (!positiveWritable && !lookup.authoritative) {
                    return@let
                }
                val ownerCurrent = replyAccessResolutionOwnerStillCurrent(resolutionScope)
                return ThreadHomeContextResolution(
                    payload = payload.withKnownMarkdownChannelNames(home),
                    accessCandidate = candidate,
                    ownerCurrent = ownerCurrent,
                )
            }

        resolveHomeSnapshotLookup(serverId)
            ?.takeIf { lookup ->
                lookup.snapshot.server.id == serverId &&
                    lookup.snapshot.currentMember.id.value == resolutionScope.provenance.principalId
            }
            ?.let { lookup ->
                val home = lookup.snapshot
                val candidate =
                    payload.exactReplyParentAccessCandidate(
                        serverId = serverId,
                        parentId = parentId,
                        parentKind = parentKind,
                        conversations = home.channels + home.directMessages,
                    ) ?: return@let
                // A non-authoritative Home snapshot (durable identity cache or refresh-pending)
                // may grant fast positive write access, but must never short-circuit a negative
                // (unjoined, non-writable, or archived) parent row and lock the thread into Join
                // without checking the network directory.
                if (!lookup.authoritative && (!candidate.joined || !candidate.permissions.canSend || candidate.archived)) {
                    return@let
                }
                val ownerCurrent = replyAccessResolutionOwnerStillCurrent(resolutionScope)
                return ThreadHomeContextResolution(
                    payload = payload.withKnownMarkdownChannelNames(home),
                    accessCandidate = candidate,
                    ownerCurrent = ownerCurrent,
                )
            }

        // Agent Lounge v0: a Lounge is in no Home list and `GET /channels` leaves it out, so the
        // directory can never answer for a thread in one. When this client knows the parent is a
        // Lounge, decide as the web does (useThreadParentChannel `parentJoined`): any member may
        // reply unless it is archived or the open answer said `canPost: false`; no Join step.
        agentLoungeReplyParentAccessCandidate(serverId, parentId, parentKind)?.let { candidate ->
            val ownerCurrent = replyAccessResolutionOwnerStillCurrent(resolutionScope)
            diagnosticsLogger(
                "thread_initial_parent_access source=agent_lounge serverId=${serverId.value} " +
                    "parentId=$parentId canSend=${candidate.permissions.canSend} ownerCurrent=$ownerCurrent"
            )
            return ThreadHomeContextResolution(
                payload = payload,
                accessCandidate = candidate,
                ownerCurrent = ownerCurrent,
                requiresBackgroundHomeContextHydration = true,
            )
        }

        val repository = channelRepository
        val conversations: List<HomeConversationRow> =
            if (repository == null) {
                emptyList()
            } else {
                try {
                    when (parentKind) {
                        ConversationKind.Channel,
                        ConversationKind.Joint ->
                            (repository.getChannels(serverId) as? DataResult.Content)
                                ?.data
                                ?.takeIf { snapshot -> snapshot.server.id == serverId }
                                ?.channels
                                .orEmpty()
                        ConversationKind.DirectMessage ->
                            (repository.getDirectMessages(serverId) as? DataResult.Content)
                                ?.data
                                ?.takeIf { snapshot -> snapshot.server.id == serverId }
                                ?.directMessages
                                .orEmpty()
                        ConversationKind.Thread,
                        ConversationKind.Inbox,
                        ConversationKind.System,
                        ConversationKind.Task -> emptyList()
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    emptyList()
                }
            }
        val ownerCurrent = replyAccessResolutionOwnerStillCurrent(resolutionScope)
        val candidate =
            payload.exactReplyParentAccessCandidate(
                serverId = serverId,
                parentId = parentId,
                parentKind = parentKind,
                conversations = conversations,
            )
        diagnosticsLogger(
            "thread_initial_parent_access source=conversation_list serverId=${serverId.value} " +
                "parentId=$parentId parentKind=${parentKind.name} candidate=${candidate != null} " +
                "ownerCurrent=$ownerCurrent"
        )
        return ThreadHomeContextResolution(
            payload = payload,
            accessCandidate = candidate,
            ownerCurrent = ownerCurrent,
            requiresBackgroundHomeContextHydration = true,
        )
    }

    private fun agentLoungeReplyParentAccessCandidate(
        serverId: ServerId,
        parentId: String,
        parentKind: ConversationKind,
    ): ConversationAccessCandidate? {
        if (parentKind != ConversationKind.Channel) return null
        val lounge = RaftAgentLounges.lounge(serverId.value, parentId) ?: return null
        return agentLoungeParentAccessCandidate(parentId, postable = lounge.postable)
    }

    /**
     * Bounded fallback for a reply thread whose parent write authority is still undetermined
     * after [SharedThreadStore]'s fallback window: read the parent conversation itself
     * (`GET /channels/:id`, web `ensureChannel`) instead of waiting on a directory row that may
     * never come (a Lounge is in no list). A Lounge parent is decided by the Lounge rule (any
     * member may post unless archived / `canPost: false`); any other parent keeps the ordinary
     * joined + canSend check.
     */
    suspend fun replyParentAccessFallback(payload: RaftThreadScreenPayload): ReplyParentAccessFallbackResult {
        val (parentId, parentKind) =
            payload.replyParentAccessIdentityOrNull()
                ?: return ReplyParentAccessFallbackResult.Unavailable("parent_identity_missing")
        val repository =
            conversationRepository
                ?: return ReplyParentAccessFallbackResult.Unavailable("conversation_repository_missing")
        val resolutionScope =
            readReplyResolutionScope()
                ?: return ReplyParentAccessFallbackResult.Unavailable("resolution_scope_missing")
        val result =
            try {
                repository.getConversationDetail(ChannelId(parentId), DataRequestPriority.Foreground)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                return ReplyParentAccessFallbackResult.Failed(
                    "exception_${throwable::class.simpleName.orEmpty()}"
                )
            }
        if (!replyAccessResolutionOwnerStillCurrent(resolutionScope)) {
            return ReplyParentAccessFallbackResult.Unavailable("owner_changed")
        }
        return when (result) {
            is DataResult.Content -> {
                val detail = result.data
                val detailId = detail.channelId?.value ?: detail.threadId.value
                if (detailId != parentId) {
                    return ReplyParentAccessFallbackResult.Failed("parent_id_mismatch")
                }
                if (!detail.loungeAgentId.isNullOrBlank()) {
                    if (parentKind != ConversationKind.Channel) {
                        return ReplyParentAccessFallbackResult.Failed("parent_kind_mismatch")
                    }
                    val serverId = resolutionScope.provenance.serverId
                    val known = RaftAgentLounges.lounge(serverId, parentId)
                    val postable = !detail.archived && known?.canPost != false
                    ReplyParentAccessFallbackResult.Resolved(
                        candidate = agentLoungeParentAccessCandidate(parentId, postable = postable),
                        lounge = true,
                    )
                } else {
                    if (detail.kind != parentKind) {
                        return ReplyParentAccessFallbackResult.Failed("parent_kind_mismatch")
                    }
                    ReplyParentAccessFallbackResult.Resolved(
                        candidate =
                            ConversationAccessCandidate(
                                ids = setOf(parentId),
                                conversationKind = parentKind,
                                surfaceKind = "channel",
                                // Membership only (web useThreadParentChannel `parentJoined =
                                // parentChannel.joined`): an archived parent the viewer is a member of
                                // must not read as "not joined" and offer a Join (fb 93123592);
                                // `archived` still closes sending below.
                                joined = detail.joined,
                                archived = detail.archived,
                                permissions = detail.permissions,
                            ),
                        lounge = false,
                    )
                }
            }
            is DataResult.NoPermission -> ReplyParentAccessFallbackResult.Failed("no_permission")
            is DataResult.Empty -> ReplyParentAccessFallbackResult.Failed("empty_${result.reason.name}")
            is DataResult.Error -> ReplyParentAccessFallbackResult.Failed(result.error.code)
        }
    }

    internal suspend fun replyAccessResolutionOwnerStillCurrent(
        expected: ReplyResolutionScope
    ): Boolean {
        val current = readReplyResolutionScope() ?: return false
        // A transparent token rotation (same binding/server/selection) keeps the owner; any
        // binding, principal, or server change still fails closed (task #2606).
        return current.provenance.isCurrentOrSuccessorForLocalSend(expected.provenance) &&
            isCurrentReplyCacheProvenanceForLocalSend(expected.provenance)
    }

    private fun RaftThreadScreenPayload.exactReplyParentAccessCandidate(
        serverId: ServerId,
        parentId: String,
        parentKind: ConversationKind,
        conversations: List<HomeConversationRow>,
    ): ConversationAccessCandidate? =
        conversations
            .asSequence()
            .mapNotNull { conversation ->
                conversation.toSharedConversationDirectoryProjection(serverId)
            }
            .firstOrNull { projection ->
                projection.canonicalChannelId == parentId &&
                    projection.conversationKind == parentKind
            }
            ?.toConversationAccessCandidate()
            ?.takeIf(::acceptsInitialConversationAccessCandidate)

    suspend fun withHomeConversationContext(payload: RaftThreadScreenPayload): RaftThreadScreenPayload =
        homeConversationContextResolution(payload).payload

    private suspend fun resolveConversationAccessFallback(
        basePayload: RaftThreadScreenPayload,
        session: StoredAuthSession
    ): ConversationAccessCandidate? {
        val home = currentHomeSnapshot(session) ?: return null
        return resolveConversationAccessFallback(basePayload, home)
    }

    private fun resolveConversationAccessFallback(
        basePayload: RaftThreadScreenPayload,
        home: HomeSnapshot
    ): ConversationAccessCandidate? {
        val lookupIds = basePayload.conversationAccessFallbackIds()
        if (lookupIds.isEmpty()) return null
        return home.toSharedConversationDirectoryState()
            .let { directory -> lookupIds.firstNotNullOfOrNull(directory::resolve) }
            ?.toConversationAccessCandidate()
            ?.takeIf { candidate -> basePayload.acceptsInitialConversationAccessCandidate(candidate) }
    }

    private suspend fun RaftThreadScreenPayload.withKnownMarkdownChannelNames(
        session: StoredAuthSession
    ): RaftThreadScreenPayload {
        val home = currentHomeSnapshot(session) ?: return this
        return withKnownMarkdownChannelNames(home)
    }

    private fun RaftThreadScreenPayload.withKnownMarkdownChannelNames(
        home: HomeSnapshot
    ): RaftThreadScreenPayload {
        val channelNames = home.knownMarkdownChannelNames()
        val knownMemberProfilesById = currentKnownMarkdownMemberProfilesById(home)
        val directMessagePeerNames =
            home.knownMarkdownDirectMessagePeerNames(knownMemberProfilesById)
        return if (
            (channelNames.isEmpty() || channelNames == knownMarkdownChannelNames) &&
            directMessagePeerNames == knownMarkdownDirectMessagePeerNames
        ) {
            this
        } else {
            copy(
                knownMarkdownChannelNames = channelNames.ifEmpty { knownMarkdownChannelNames },
                knownMarkdownDirectMessagePeerNames = directMessagePeerNames
            )
        }
    }

    internal fun currentKnownMarkdownMemberProfilesById(home: HomeSnapshot): Map<String, MemberProfile> =
        serverMembersStore?.currentKnownMemberProfilesById(home.server.id).orEmpty()

    internal suspend fun resolveHomeSnapshotLookup(serverId: ServerId): HomeSnapshotLookup? =
        homeSnapshotLookupProvider?.invoke(serverId)
            ?: homeSnapshotProvider?.invoke(serverId)?.let { snapshot ->
                HomeSnapshotLookup(snapshot = snapshot, authoritative = true)
            }

    internal suspend fun currentHomeSnapshot(session: StoredAuthSession): HomeSnapshot? {
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return null
        resolveHomeSnapshotLookup(serverId)?.snapshot?.let { snapshot ->
            diagnosticsLogger("thread_home_context source=shared_home_store serverId=${serverId.value}")
            return snapshot
        }
        val repository = channelRepository ?: return null
        diagnosticsLogger("thread_home_context source=repository serverId=${serverId.value}")
        return (repository.getHome(serverId) as? DataResult.Content)?.data
    }
}
