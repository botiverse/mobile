package build.raft.app.thread

import build.raft.app.ReplyCacheProvenance
import build.raft.app.ReplyResolutionScope
import build.raft.app.ReplyThreadResolveKey
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.HomeSnapshotLookup
import build.raft.app.data.SharedReferenceKey
import build.raft.app.data.SharedReferenceKind
import build.raft.app.data.SharedReferenceOutcome
import build.raft.app.data.SharedReferenceResolver
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.authSessionRequiredResult
import build.raft.app.get
import build.raft.app.markdown.RaftMarkdownMessageRef
import build.raft.app.markdown.raftLoungeConversationAgentName
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.messageStreamChannelId
import build.raft.app.resolve
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.staleReplyCacheProvenanceResult
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.threadPayloadOwnerScopeId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * What a Markdown reference opens in [SharedThreadDataSource] (channel, task, thread and message
 * refs, their local lookup and forgetting a refused target), moved out of it unchanged; the data
 * source forwards to it. Holds no state of its own: reads [ThreadReplyThreadPrewarm]'s resolutions
 * under the lock owned by [ThreadReplyCacheProvenanceOwner], at the same point as before.
 */
internal class ThreadMarkdownRefLaunch(
    private val provenanceOwner: ThreadReplyCacheProvenanceOwner,
    private val payloadCache: SharedThreadPayloadStore,
    private val messageStore: SharedThreadMessageStore?,
    private val messageResolver: SharedThreadMessageStore,
    private val replyThreadPrewarm: ThreadReplyThreadPrewarm,
    private val readSession: suspend () -> StoredAuthSession?,
    private val readReplyResolutionScope: suspend () -> ReplyResolutionScope?,
    private val resolveHomeSnapshotLookup: suspend (ServerId) -> HomeSnapshotLookup?,
    private val currentHomeSnapshot: suspend (StoredAuthSession) -> HomeSnapshot?,
    private val currentKnownMarkdownMemberProfilesById: (HomeSnapshot) -> Map<String, MemberProfile>,
    private val fetchThreadSnapshot:
        suspend (
            String,
            ReplyCacheProvenance,
            suspend (ConversationRepository) -> DataResult<ThreadSnapshot>,
        ) -> DataResult<ThreadSnapshot>,
    private val markdownReplyCacheScopeCapturedHook: () -> (suspend () -> Unit)?,
) {
    private val replyCacheProvenanceLock: SynchronizedObject get() = provenanceOwner.replyCacheProvenanceLock
    private val replyThreadResolveCache: Map<ReplyThreadResolveKey, String> get() = replyThreadPrewarm.replyThreadResolveCache
    private val referenceResolver: SharedReferenceResolver get() = payloadCache.referenceResolver
    private val onMarkdownReplyCacheScopeCapturedForTest: (suspend () -> Unit)? get() = markdownReplyCacheScopeCapturedHook()

    private fun isCurrentReplyCacheProvenance(expected: ReplyCacheProvenance): Boolean =
        provenanceOwner.isCurrentReplyCacheProvenance(expected)

    private fun cacheReplyThreadResolution(
        provenance: ReplyCacheProvenance,
        parentChannelId: String,
        parentMessageId: String,
        threadChannelId: String
    ): Boolean =
        replyThreadPrewarm.cacheReplyThreadResolution(provenance, parentChannelId, parentMessageId, threadChannelId)

    private suspend fun fetchThreadSnapshotFromRepository(
        fallbackCode: String,
        expectedProvenance: ReplyCacheProvenance,
        call: suspend (ConversationRepository) -> DataResult<ThreadSnapshot>
    ): DataResult<ThreadSnapshot> = fetchThreadSnapshot(fallbackCode, expectedProvenance, call)

    /**
     * A thread ref's target from what the device already holds: the page's own messages and
     * origin, the in-memory thread cache, the loaded timelines of cached conversations (their
     * messages carry the thread summaries), reply-thread resolutions already made, and the parent
     * messages the message store holds. Candidates from every source are pooled and deduped by
     * full message id: an 8-char prefix is a local hit only when exactly one message matches across
     * all of them. None, or two different messages, is a miss and fetches.
     */
    private fun localMarkdownThreadRefTarget(
        provenance: ReplyCacheProvenance,
        currentPayload: RaftThreadScreenPayload,
        parentChannelId: String,
        threadShortId: String
    ): MarkdownThreadRefTarget? {
        val shortId = threadShortId.trim().lowercase()
        if (parentChannelId.isBlank() || shortId.isBlank()) return null
        val candidates = mutableListOf<MarkdownThreadRefTarget>()
        candidates += currentPayload.markdownThreadRefCandidates(parentChannelId, shortId)
        provenance.threadPayloadOwnerScopeId?.let { ownerScopeId ->
            candidates += cachedMarkdownThreadRefCandidates(provenance.serverId, ownerScopeId, parentChannelId, shortId)
            candidates +=
                payloadCache.values(provenance.serverId, ownerScopeId)
                    .flatMap { payload -> payload.markdownThreadRefCandidates(parentChannelId, shortId) }
        }
        provenance.namespaceId?.takeIf { it.isNotBlank() }?.let { namespaceId ->
            val resolutions =
                synchronized(replyCacheProvenanceLock) {
                    replyThreadResolveCache.entries
                        .filter { (key, _) ->
                            key.namespaceId == namespaceId &&
                                key.parentChannelId == parentChannelId &&
                                key.parentMessageId.lowercase().startsWith(shortId)
                        }
                        .map { (key, threadChannelId) -> key.parentMessageId to threadChannelId }
                } + messageStore?.findReplyThreadStreams(namespaceId, parentChannelId, shortId).orEmpty()
            candidates +=
                resolutions.map { (parentMessageId, threadChannelId) ->
                    MarkdownThreadRefTarget(parentMessageId = parentMessageId, threadChannelId = threadChannelId)
                }
        }
        provenance.messageStoreScopeId?.takeIf { it.isNotBlank() }?.let { scopeId ->
            candidates +=
                messageResolver.findScopedByIdPrefix(scopeId, ChannelId(parentChannelId), shortId).map { message ->
                    MarkdownThreadRefTarget(
                        parentMessageId = message.id.value,
                        threadChannelId = message.threadChannelId?.value?.takeIf { it.isNotBlank() }
                    )
                }
        }
        return candidates.singleMarkdownThreadRefTarget()
    }

    /**
     * A message of [parentChannelId]'s loaded timelines whose id starts with [messageShortId]: the
     * page's own and every cached timeline of that conversation, pooled. One distinct message only.
     */
    private fun localMarkdownTimelineRefTarget(
        provenance: ReplyCacheProvenance,
        currentPayload: RaftThreadScreenPayload,
        parentChannelId: String,
        messageShortId: String
    ): MarkdownThreadRefTarget? {
        val candidates = currentPayload.markdownThreadRefCandidates(parentChannelId, messageShortId).toMutableList()
        provenance.threadPayloadOwnerScopeId?.let { ownerScopeId ->
            candidates +=
                payloadCache.values(provenance.serverId, ownerScopeId)
                    .flatMap { payload -> payload.markdownThreadRefCandidates(parentChannelId, messageShortId) }
        }
        return candidates.singleMarkdownThreadRefTarget()
    }

    private fun cachedMarkdownThreadRefCandidates(
        serverId: String,
        ownerScopeId: String,
        parentChannelId: String,
        threadShortId: String
    ): List<MarkdownThreadRefTarget> {
        val shortId = threadShortId.trim().lowercase()
        if (parentChannelId.isBlank() || shortId.isBlank()) return emptyList()
        return payloadCache.values(serverId, ownerScopeId)
            .mapNotNull { payload ->
                val parentMessageId =
                    payload.parentMessageId?.takeIf { it.isNotBlank() }
                        ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                if (
                    payload.parentChannelId != parentChannelId ||
                    !parentMessageId.lowercase().startsWith(shortId)
                ) {
                    return@mapNotNull null
                }
                MarkdownThreadRefTarget(
                    parentMessageId = parentMessageId,
                    threadChannelId =
                        payload.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: payload.sourceThreadId?.takeIf { it.isNotBlank() }
                )
            }
    }

    /**
     * A reference's target page could not be read (refused or gone): every kept resolution that
     * points at it is forgotten, so the next tap on that reference asks the server again.
     */
    internal fun forgetReferenceTargetsOf(payload: RaftThreadScreenPayload): Int {
        val ids =
            listOfNotNull(
                payload.parentMessageId,
                payload.originParentMessageId,
                payload.streamChannelId,
                payload.sourceThreadId,
                payload.focusedMessageId
            ).filter { it.isNotBlank() }.toSet()
        if (ids.isEmpty()) return 0
        return referenceResolver.forgetTargets { _, target ->
            target is MarkdownThreadRefTarget &&
                (target.parentMessageId in ids || target.threadChannelId in ids || target.focusedMessageId in ids)
        }
    }

    /** The conversation directory a reference resolves names in: local, unless it has to be fetched. */
    private suspend fun referenceHomeSnapshot(session: StoredAuthSession, actionId: String?): HomeSnapshot? {
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return null
        resolveHomeSnapshotLookup(serverId)?.snapshot?.let { return it }
        // No home snapshot on the device: reading it is part of the reference's fetch.
        return referenceResolver.fetchPhase(actionId) { currentHomeSnapshot(session) }
    }

    suspend fun markdownChannelLaunchPayload(
        currentPayload: RaftThreadScreenPayload,
        channelLabel: String,
        requiredKind: ConversationKind? = null
    ): RaftThreadScreenPayload? {
        val session = readSession() ?: return null
        val home = currentHomeSnapshot(session) ?: return null
        return buildMarkdownChannelLaunchPayload(
            currentPayload = currentPayload,
            home = home,
            channelLabel = channelLabel,
            requiredKind = requiredKind,
            knownMemberProfilesById =
                if (requiredKind == ConversationKind.DirectMessage || raftLoungeConversationAgentName(channelLabel) != null) {
                    currentKnownMarkdownMemberProfilesById(home)
                } else {
                    emptyMap()
                }
        )
    }

    /**
     * The local channel a conversation-qualified task ref (`#proj:#7`, `dm:@peer:#7`,
     * `lounge:@agent:#7`) names, the scope its task number resolves in (Web `findTaskRefChannel`).
     * Null: the reader has no such conversation.
     */
    suspend fun markdownTaskRefChannelId(
        currentPayload: RaftThreadScreenPayload,
        conversationLabel: String,
        requiredKind: ConversationKind? = null,
        /** The chip this open started from: a directory fetch is drawn on it. */
        actionId: String? = null
    ): ChannelId? {
        val session = readSession() ?: return null
        val home = referenceHomeSnapshot(session, actionId) ?: return null
        val conversation =
            resolveMarkdownChannelConversation(
                home = home,
                channelLabel = conversationLabel,
                requiredKind = requiredKind,
                knownMemberProfilesById =
                    if (requiredKind == ConversationKind.DirectMessage || raftLoungeConversationAgentName(conversationLabel) != null) {
                        currentKnownMarkdownMemberProfilesById(home)
                    } else {
                        emptyMap()
                    },
                currentPayload = currentPayload
            ) ?: return null
        return conversation.markdownTaskRefChannelId()
    }

    suspend fun markdownThreadRefLaunchPayload(
        provenance: ReplyCacheProvenance,
        currentPayload: RaftThreadScreenPayload,
        channelLabel: String,
        threadShortId: String?,
        requiredParentKind: ConversationKind? = null,
        /** The message to focus in the thread when the caller knows it (a `#channel:short msg=id` ref). */
        focusedMessageId: String? = null,
        /** The chip this open started from: [referenceResolver]'s fetch phase is drawn on it. */
        actionId: String? = null
    ): DataResult<RaftThreadScreenPayload> {
        val shortId =
            threadShortId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        if (resolutionScope.provenance != provenance || !isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        onMarkdownReplyCacheScopeCapturedForTest?.invoke()
        val session = resolutionScope.session
        val home =
            referenceHomeSnapshot(session, actionId)
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val knownMemberProfilesById = currentKnownMarkdownMemberProfilesById(home)
        val channel =
            resolveMarkdownChannelConversation(
                home = home,
                channelLabel = channelLabel,
                requiredKind = requiredParentKind,
                knownMemberProfilesById = knownMemberProfilesById,
                currentPayload = currentPayload,
                threadShortId = shortId
            )
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val parentChannelId =
            channel.resolveThreadRouteIdentity().timelineChannelId
                ?: channel.messageStreamChannelId().value.takeIf { it.isNotBlank() }
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val target =
            when (
                val outcome =
                    referenceResolver.resolve(
                        key = SharedReferenceKey.of(provenance.namespaceId, SharedReferenceKind.Thread, parentChannelId, shortId),
                        actionId = actionId,
                        local = { localMarkdownThreadRefTarget(provenance, currentPayload, parentChannelId, shortId) },
                        // A message without a thread yet must not stop its thread opening later.
                        cacheable = { target -> !target.threadChannelId.isNullOrBlank() },
                        fetch = {
                            when (
                                val result =
                                    fetchThreadSnapshotFromRepository(
                                        fallbackCode = "thread_markdown_ref_context_network",
                                        expectedProvenance = provenance,
                                        // Only the ref's target: the opened page loads its own header detail.
                                        call = { repository ->
                                            repository.resolveMessageContext(MessageId(shortId), ChannelId(parentChannelId))
                                        }
                                    )
                            ) {
                                is DataResult.Content ->
                                    result.data.markdownThreadRefTarget(parentChannelId, shortId)
                                        ?.let { DataResult.Content(it) }
                                        ?: DataResult.Empty(EmptyReason.NoMessages)
                                is DataResult.Empty -> result
                                is DataResult.Error -> result
                                is DataResult.NoPermission -> result
                            }
                        }
                    )
            ) {
                is SharedReferenceOutcome.Open -> outcome.target
                is SharedReferenceOutcome.Failed -> return outcome.result
            }
        if (!isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val payload =
            buildMarkdownThreadRefLaunchPayload(
                currentPayload = currentPayload,
                home = home,
                channelLabel = channelLabel,
                threadShortId = shortId,
                requiredParentKind = requiredParentKind,
                knownMemberProfilesById = knownMemberProfilesById,
                parentMessageId = target.parentMessageId,
                focusedMessageId = focusedMessageId ?: target.focusedMessageId,
                threadChannelId = target.threadChannelId
            )
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        target.threadChannelId
            ?.takeIf { it.isNotBlank() }
            ?.let {
                cacheReplyThreadResolution(
                    provenance,
                    parentChannelId,
                    target.parentMessageId,
                    it
                )
            }
        if (!isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        return DataResult.Content(payload)
    }

    /**
     * Opens what a message ref names (Web `handleOpenMessageRef`): the channel or DM is resolved by
     * name in the current server, and the id, which may be short, by the message's context. A reply
     * opens its thread focused on it; a top-level message opens its conversation anchored on it,
     * unless the ref names a thread, which then opens with the message focused.
     */
    suspend fun markdownMessageRefLaunchPayload(
        provenance: ReplyCacheProvenance,
        currentPayload: RaftThreadScreenPayload,
        ref: RaftMarkdownMessageRef,
        /** The chip this open started from: [referenceResolver]'s fetch phase is drawn on it. */
        actionId: String? = null
    ): DataResult<RaftThreadScreenPayload> {
        val resolutionScope =
            readReplyResolutionScope()
                ?: return authSessionRequiredResult()
        if (resolutionScope.provenance != provenance || !isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        val home =
            referenceHomeSnapshot(resolutionScope.session, actionId)
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val requiredKind = ConversationKind.DirectMessage.takeIf { ref.directMessagePeerName != null }
        val knownMemberProfilesById = currentKnownMarkdownMemberProfilesById(home)
        val channel =
            resolveMarkdownChannelConversation(
                home = home,
                channelLabel = ref.conversationLabel,
                requiredKind = requiredKind,
                knownMemberProfilesById = knownMemberProfilesById,
                currentPayload = currentPayload,
                threadShortId = ref.threadShortId ?: ref.messageId
            )
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val parentChannelId =
            channel.resolveThreadRouteIdentity().timelineChannelId
                ?: channel.messageStreamChannelId().value.takeIf { it.isNotBlank() }
                ?: return DataResult.Empty(EmptyReason.NoChannels)
        val target =
            when (
                val outcome =
                    referenceResolver.resolve(
                        key = SharedReferenceKey.of(provenance.namespaceId, SharedReferenceKind.Message, parentChannelId, ref.messageId),
                        actionId = actionId,
                        // A message in a loaded timeline of its conversation is a top-level message there.
                        local = {
                            localMarkdownTimelineRefTarget(provenance, currentPayload, parentChannelId, ref.messageId)
                                ?.copy(threadChannelId = null)
                        },
                        fetch = {
                            when (
                                val result =
                                    fetchThreadSnapshotFromRepository(
                                        fallbackCode = "thread_markdown_message_ref_context_network",
                                        expectedProvenance = provenance,
                                        call = { repository ->
                                            repository.resolveMessageContext(MessageId(ref.messageId), ChannelId(parentChannelId))
                                        }
                                    )
                            ) {
                                is DataResult.Content ->
                                    result.data.markdownThreadRefTarget(parentChannelId, ref.messageId)
                                        ?.let { DataResult.Content(it) }
                                        ?: DataResult.Empty(EmptyReason.NoMessages)
                                is DataResult.Empty -> result
                                is DataResult.Error -> result
                                is DataResult.NoPermission -> result
                            }
                        }
                    )
            ) {
                is SharedReferenceOutcome.Open -> outcome.target
                is SharedReferenceOutcome.Failed -> return outcome.result
            }
        if (!isCurrentReplyCacheProvenance(provenance)) {
            return staleReplyCacheProvenanceResult()
        }
        if (target.focusedMessageId == null && target.threadChannelId == null) {
            val messageId = target.parentMessageId
            ref.threadShortId?.let { threadShortId ->
                return markdownThreadRefLaunchPayload(
                    provenance = provenance,
                    currentPayload = currentPayload,
                    channelLabel = ref.conversationLabel,
                    threadShortId = threadShortId,
                    requiredParentKind = requiredKind,
                    // The thread's own parent: opening the thread is the jump.
                    focusedMessageId = messageId.takeUnless { it.startsWith(threadShortId, ignoreCase = true) },
                    actionId = actionId
                )
            }
            return buildMarkdownChannelLaunchPayload(
                currentPayload = currentPayload,
                home = home,
                channelLabel = ref.conversationLabel,
                requiredKind = requiredKind,
                knownMemberProfilesById = knownMemberProfilesById,
                anchorMessageId = messageId
            )?.let { DataResult.Content(it) }
                ?: DataResult.Empty(EmptyReason.NoMessages)
        }
        val payload =
            buildMarkdownThreadRefLaunchPayload(
                currentPayload = currentPayload,
                home = home,
                channelLabel = ref.conversationLabel,
                threadShortId = ref.threadShortId ?: ref.messageId,
                requiredParentKind = requiredKind,
                knownMemberProfilesById = knownMemberProfilesById,
                parentMessageId = target.parentMessageId,
                focusedMessageId = target.focusedMessageId,
                threadChannelId = target.threadChannelId
            )
                ?: return DataResult.Empty(EmptyReason.NoMessages)
        target.threadChannelId
            ?.takeIf { it.isNotBlank() }
            ?.let { cacheReplyThreadResolution(provenance, parentChannelId, target.parentMessageId, it) }
        return DataResult.Content(payload)
    }
}
