package build.raft.app.thread

import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.canRetainThreadPageFor
import build.raft.app.data.DataResult
import build.raft.app.data.isOlderMessageCardRevision
import build.raft.app.data.isRegressingActionCardState
import build.raft.app.data.EmptyReason
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.MessageReactionSummary
import build.raft.app.data.map
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.keepKnownDelegatedBy
import build.raft.app.messages
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageReaction
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.thread.resolveThreadRouteIdentity
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal fun RaftThreadScreenPayload.withContentSliceFrom(patch: RaftThreadScreenPayload): RaftThreadScreenPayload {
    val acceptsPatchHeader =
        resolveThreadRouteIdentity().canRetainThreadPageFor(patch.resolveThreadRouteIdentity())
    val patchTitle =
        if (acceptsPatchHeader) {
            threadHeaderSafeTitleOrNull(
                patch.title,
                patch.conversationHeaderLookupIds().toSet()
            )?.takeUnless { it.isGenericThreadHeaderFallbackLabel() }
        } else {
            null
        }
    val patchDmPeerName =
        if (acceptsPatchHeader) {
            threadHeaderSafeTitleOrNull(
                patch.dmPeerName,
                patch.conversationHeaderLookupIds().toSet()
            )?.takeUnless { it.isGenericThreadHeaderFallbackLabel() }
        } else {
            null
        }
    return copy(
        // Only a content response for this retained destination may enrich header
        // metadata. Even then, a neutral route fallback (for example "Direct Message")
        // must never erase a valid canonical detail/directory value.
        title = patchTitle ?: title,
        subtitle =
            if (acceptsPatchHeader) {
                patch.subtitle?.takeIf { it.isNotBlank() } ?: subtitle
            } else {
                subtitle
            },
        status = patch.status,
        backgroundRefreshing = patch.backgroundRefreshing,
        // A loaded page says whether the channel is archived (archive / unarchive flip it); a
        // loading or failed patch keeps what the page knew.
        channelArchived = if (acceptsPatchHeader && patch.isLoadedContentFrame()) patch.channelArchived else channelArchived,
        channelCanUnarchive =
            if (acceptsPatchHeader && patch.isLoadedContentFrame()) patch.channelCanUnarchive else channelCanUnarchive,
        // The content slice carries its own provenance: a network patch is never last-known.
        durableLastKnown = patch.durableLastKnown,
        durableReachedOldest = patch.durableReachedOldest,
        durableHasOlderStored = patch.durableHasOlderStored,
        durableHasNewerStored = patch.durableHasNewerStored,
        mainLoadFailed = patch.mainLoadFailed,
        messages = patch.messages,
        originMessage = patch.originMessage,
        originPreview = patch.originPreview,
        originParentMessageId = patch.originParentMessageId,
        originStatus = patch.originStatus,
        originErrorCode = patch.originErrorCode,
        knownMarkdownChannelNames =
            patch.knownMarkdownChannelNames.ifEmpty { knownMarkdownChannelNames },
        knownMarkdownDirectMessagePeerNames =
            patch.knownMarkdownDirectMessagePeerNames.ifEmpty { knownMarkdownDirectMessagePeerNames },
        canLoadMoreBefore = patch.canLoadMoreBefore,
        canLoadMoreAfter = patch.canLoadMoreAfter,
        olderMessagesStatus = patch.olderMessagesStatus,
        newerMessagesStatus = patch.newerMessagesStatus,
        errorCode = patch.errorCode,
        errorServerMessage = patch.errorServerMessage,
        errorTraceId = patch.errorTraceId,
        emptyTitle = patch.emptyTitle,
        emptyDetail = patch.emptyDetail,
        replySummariesAuthority = patch.replySummariesAuthority,
        dmPeerName = patchDmPeerName ?: dmPeerName,
        dmPeerAvatarUrl =
            if (acceptsPatchHeader) {
                patch.dmPeerAvatarUrl?.takeIf { it.isNotBlank() } ?: dmPeerAvatarUrl
            } else {
                dmPeerAvatarUrl
            },
        dmPeerAvatarLabel =
            if (acceptsPatchHeader) {
                patch.dmPeerAvatarLabel?.takeIf { it.isNotBlank() } ?: dmPeerAvatarLabel
            } else {
                dmPeerAvatarLabel
            }
    ).withOriginReplyCountAtLeastLoadedReplies()
}

internal fun RaftThreadScreenPayload.withThreadContentProjectionFrom(
    patch: RaftThreadScreenPayload
): RaftThreadScreenPayload =
    withResolvedReplyThreadIdentityFrom(patch)
        .withContentSliceFrom(patch)
        .withTaskSliceFrom(patch)

/** The opposite of `applyChannelJoined`: [channelId]'s page shows the composer's Join. */
internal fun RaftThreadScreenPayload.withChannelLeft(channelId: String): RaftThreadScreenPayload =
    if (joinChannelTargetId()?.value != channelId) {
        this
    } else {
        copy(
            joined = false,
            canSend = false,
            composer = composer.copy(enabled = false)
        )
    }

/**
 * `channel:updated` names the channel this page shows: the channel itself, or a reply thread's
 * parent channel (archiving a channel archives its threads; the server refuses their replies too).
 */
internal fun RaftThreadScreenPayload.followsChannelUpdate(event: RealtimeRefreshRequired): Boolean {
    if (event.reason != RealtimeSocketEvents.ChannelUpdated) return false
    val channelId = event.channelId?.value?.takeIf { it.isNotBlank() } ?: return false
    if (surfaceKind == RaftThreadSurfaceKind.DirectMessage) return false
    return joinChannelTargetId()?.value == channelId
}

/**
 * [channelId] was archived or unarchived while its page (or a reply thread in it) is open: the
 * composer gives way to the archived banner, or comes back (web ChatPanel `isArchived` follows
 * the channel store's live row).
 */
internal fun RaftThreadScreenPayload.withChannelArchived(channelId: String, archived: Boolean): RaftThreadScreenPayload =
    if (channelArchived == archived || surfaceKind == RaftThreadSurfaceKind.DirectMessage ||
        joinChannelTargetId()?.value != channelId
    ) {
        this
    } else {
        copy(channelArchived = archived)
    }

/** The server's refusal of a write into an archived channel (slock routes/messages.ts). */
internal const val ThreadSendChannelArchivedErrorCode = "channel_archived"

/**
 * A send the server refused because the channel is archived (archived elsewhere before this page
 * heard of it): the page now knows, so the archived banner replaces the composer. The failed
 * message stays on the timeline.
 */
internal fun RaftThreadScreenPayload.withSendRefusedAsArchived(errorCode: String?): RaftThreadScreenPayload =
    if (errorCode?.trim() != ThreadSendChannelArchivedErrorCode || channelArchived ||
        surfaceKind == RaftThreadSurfaceKind.DirectMessage
    ) {
        this
    } else {
        copy(channelArchived = true)
    }

/** A live `channel:updated` row's archive state, applied where it names this page's channel. */
internal fun RaftThreadScreenPayload.withChannelUpdateArchiveState(event: RealtimeRefreshRequired): RaftThreadScreenPayload {
    val archived = event.channelArchived ?: return this
    if (!followsChannelUpdate(event)) return this
    return withChannelArchived(event.channelId?.value ?: return this, archived)
}

internal fun RaftThreadScreenPayload.withCanonicalConversationAccessFrom(
    patch: RaftThreadScreenPayload
): RaftThreadScreenPayload =
    copy(
        joined = patch.joined,
        canSend = patch.canSend,
        composer = composer.copy(enabled = patch.composer.enabled)
    )

internal fun RaftThreadScreenPayload.withInitialHomeConversationAccessFallback(
    candidate: ConversationAccessCandidate?
): RaftThreadScreenPayload {
    candidate ?: return this
    if (!acceptsInitialConversationAccessCandidate(candidate)) return this
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread || isTaskConversationSurface()) {
        // Reply capability belongs to the parent Channel/DM, not to the
        // synthetic thread conversation returned with reply content. A task
        // conversation's belongs to its task channel the same way.
        return copy(
            joined = candidate.joined,
            canSend = candidate.permissions.canSend,
            composer =
                composer.copy(
                    enabled = candidate.joined && candidate.permissions.canSend
                )
        )
    }
    if (candidate.joined) return this
    return copy(
        joined = false,
        canSend = candidate.permissions.canSend,
        composer = composer.copy(enabled = false)
    )
}

/**
 * The Home projection is only an initial access candidate. Once a canonical non-reply
 * conversation fetch has resolved access, a stale pre-join Home row must not override it or turn
 * a mounted Composer back into a Join footer. Reply threads still need the Home candidate because
 * their canonical stream response does not attest the parent channel's write authority.
 */
internal fun RaftThreadScreenPayload.withInitialHomeConversationAccessFallbackUnlessCanonical(
    canonicalAccessResolved: Boolean,
    candidate: ConversationAccessCandidate?,
): RaftThreadScreenPayload =
    if (canonicalAccessResolved && surfaceKind != RaftThreadSurfaceKind.ReplyThread) {
        this
    } else {
        withInitialHomeConversationAccessFallback(candidate)
    }

internal fun ThreadAccessResolution.afterRefreshResult(
    result: DataResult<*>,
    canonicalAccessResolved: Boolean,
    homeLookupComplete: Boolean,
    homeAccessCandidate: ConversationAccessCandidate?,
    requiresParentAccessCandidate: Boolean,
    hasReadableCanonicalReplyContent: Boolean
): ThreadAccessResolution =
    when (result) {
        is DataResult.Content ->
            when {
                this == ThreadAccessResolution.Resolved -> ThreadAccessResolution.Resolved
                !canonicalAccessResolved || !homeLookupComplete -> ThreadAccessResolution.Loading
                requiresParentAccessCandidate &&
                    homeAccessCandidate == null &&
                    !hasReadableCanonicalReplyContent ->
                    ThreadAccessResolution.Error
                else -> ThreadAccessResolution.Resolved
            }
        is DataResult.Empty ->
            when {
                this == ThreadAccessResolution.Resolved &&
                    (!requiresParentAccessCandidate || homeAccessCandidate != null) ->
                    ThreadAccessResolution.Resolved
                result.reason == EmptyReason.NoMessages &&
                    canonicalAccessResolved &&
                    !requiresParentAccessCandidate ->
                    ThreadAccessResolution.Resolved
                result.reason == EmptyReason.NoMessages &&
                    canonicalAccessResolved &&
                    !homeLookupComplete ->
                    ThreadAccessResolution.Loading
                result.reason == EmptyReason.NoMessages &&
                    canonicalAccessResolved &&
                    homeAccessCandidate == null ->
                    ThreadAccessResolution.Error
                homeAccessCandidate != null -> ThreadAccessResolution.Resolved
                homeLookupComplete -> ThreadAccessResolution.Error
                else -> ThreadAccessResolution.Loading
            }
        is DataResult.NoPermission -> ThreadAccessResolution.NoPermission
        is DataResult.Error -> if (this == ThreadAccessResolution.Resolved) this else ThreadAccessResolution.Error
    }

// Canonical reply content proves read access independently of parent/origin
// enrichment. The complete reply identity keeps stale or unresolved route
// candidates from acquiring access; parent context remains auxiliary and may
// degrade inline without hiding the canonical timeline.
internal fun RaftThreadScreenPayload.hasReadableCanonicalReplyContent(): Boolean {
    if (surfaceKind != RaftThreadSurfaceKind.ReplyThread || status != "content" || messages.isEmpty()) return false
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return false
    val parentMessage =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return false
    val routeIdentity = resolveThreadRouteIdentity()
    if (!routeIdentity.hasCompleteReplyIdentity || routeIdentity.parentKey != "$parentChannel:$parentMessage") {
        return false
    }
    return routeIdentity.canRenderTimelineCandidates && routeIdentity.timelineChannelId != null
}

internal fun RaftThreadScreenPayload.withReadOnlyReplyAccessWhenHomeCandidateUnavailable(
    homeLookupComplete: Boolean,
    homeAccessCandidate: ConversationAccessCandidate?
): RaftThreadScreenPayload {
    if (!isReadOnlyReplyAccessFallback(homeLookupComplete, homeAccessCandidate)) return this
    return copy(
        joined = false,
        canSend = false,
        composer = composer.copy(enabled = false)
    )
}

private fun RaftThreadScreenPayload.isReadOnlyReplyAccessFallback(
    homeLookupComplete: Boolean,
    homeAccessCandidate: ConversationAccessCandidate?
): Boolean =
    homeLookupComplete &&
        homeAccessCandidate == null &&
        surfaceKind == RaftThreadSurfaceKind.ReplyThread

// Write authority is a terminal access-state invariant, not an inference from
// whichever payload fields survived the latest projection. In particular, a
// canonical permission revocation clears readable content, so re-evaluating
// only the read-only reply fallback would otherwise turn writes back on.
internal fun RaftThreadScreenPayload.hasWriteAuthorityForAccess(
    accessResolution: ThreadAccessResolution,
    homeLookupComplete: Boolean,
    homeAccessCandidate: ConversationAccessCandidate?
): Boolean =
    accessResolution == ThreadAccessResolution.Resolved &&
        (
            surfaceKind != RaftThreadSurfaceKind.ReplyThread ||
                (
                    homeLookupComplete &&
                        homeAccessCandidate != null &&
                        acceptsInitialConversationAccessCandidate(homeAccessCandidate)
                )
        ) &&
        !isReadOnlyReplyAccessFallback(homeLookupComplete, homeAccessCandidate)

internal fun RaftThreadScreenPayload.withTerminalAccessWriteAuthorityClosed(
    accessResolution: ThreadAccessResolution
): RaftThreadScreenPayload =
    if (
        accessResolution == ThreadAccessResolution.NoPermission ||
        accessResolution == ThreadAccessResolution.Error
    ) {
        copy(
            joined = false,
            canSend = false,
            composer = composer.copy(enabled = false)
        )
    } else {
        this
    }

internal fun DataResult<*>.diagnosticsResultReason(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:${reason.name}"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }

internal fun DataResult<*>.resolvesCanonicalConversationAccess(): Boolean =
    this is DataResult.Content || (this is DataResult.Empty && reason == EmptyReason.NoMessages)

internal fun RaftThreadScreenPayload.withTaskSliceFrom(patch: RaftThreadScreenPayload): RaftThreadScreenPayload =
    copy(tasks = patch.tasks)

internal fun RaftThreadScreenPayload.withResolvedReplyThreadIdentityFrom(
    patch: RaftThreadScreenPayload
): RaftThreadScreenPayload =
    if (patch.hasResolvedReplyThreadStreamFrom(this)) {
        copy(
            instanceId = patch.instanceId,
            title = patch.title,
            subtitle = patch.subtitle,
            sourceThreadId = patch.sourceThreadId,
            streamChannelId = patch.streamChannelId,
            parentChannelId = patch.parentChannelId,
            parentMessageId = patch.parentMessageId,
            composer =
                composer.copy(
                    streamChannelId =
                        patch.composer.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: patch.streamChannelId?.takeIf { it.isNotBlank() }
                            ?: patch.sourceThreadId?.takeIf { it.isNotBlank() }
                )
        )
    } else {
        this
    }

internal fun RaftThreadScreenPayload.withMembersPatchFrom(patch: RaftThreadScreenPayload): RaftThreadScreenPayload =
    copy(
        membersCount = patch.membersCount,
        members = patch.members,
        memberCandidates = patch.memberCandidates,
        canAddMembers = patch.canAddMembers,
        addMemberStatus = patch.addMemberStatus,
        addMemberErrorCode = patch.addMemberErrorCode,
        removeMemberStatus = patch.removeMemberStatus,
        removingMemberId = patch.removingMemberId,
        removeMemberErrorCode = patch.removeMemberErrorCode
    )

internal fun RaftThreadScreenPayload.withMessageReaction(
    messageId: String,
    emoji: String,
    selected: Boolean
): RaftThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    val cleanEmoji = emoji.trim()
    if (cleanMessageId.isBlank() || cleanEmoji.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.withReactionSelection(cleanEmoji, selected) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.withReactionSelection(cleanEmoji, selected) else message
        }
    )
}

internal fun RaftThreadScreenPayload.withMessageReactions(
    messageId: String,
    reactions: List<RaftThreadReactionItem>
): RaftThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    if (cleanMessageId.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        }
    )
}

internal fun RaftThreadScreenPayload.withMessageSaved(
    messageId: String,
    saved: Boolean
): RaftThreadScreenPayload {
    val cleanMessageId = messageId.trim()
    if (cleanMessageId.isBlank()) return this
    return copy(
        messages = messages.map { message ->
            if (message.id == cleanMessageId) message.copy(isSaved = saved) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == cleanMessageId) message.copy(isSaved = saved) else message
        }
    )
}

internal fun RaftThreadScreenPayload.withSavedMessageIds(savedIds: Set<String>): RaftThreadScreenPayload =
    copy(
        messages = messages.map { message -> message.copy(isSaved = message.id in savedIds) },
        originMessage = originMessage?.let { message -> message.copy(isSaved = message.id in savedIds) }
    )

// task #569 follow-up: id -> display name over the thread's known members
// (roster + mention candidates) so reaction-tooltip name resolution can backfill
// reactor ids the mutation/realtime payload didn't name. The current user is in
// this roster, so their own just-added reaction resolves without a server round.
internal fun RaftThreadScreenPayload.threadReactorNameById(): Map<String, String> {
    val byId = LinkedHashMap<String, String>()
    (members + memberCandidates).forEach { member ->
        val id = member.id.trim()
        val name = member.displayName.trim()
        if (id.isNotBlank() && name.isNotBlank() && !byId.containsKey(id)) byId[id] = name
    }
    return byId
}

internal fun RaftThreadScreenPayload.withMessageMutationSummary(
    summary: MessageMutationSummary,
    currentUserId: String?,
    currentUserName: String? = null
): RaftThreadScreenPayload {
    val messageId = summary.messageId.trim()
    if (messageId.isBlank()) return this
    val reactions = summary.reactions.toThreadReactionItems(currentUserId, threadReactorNameById(), currentUserName)
    return copy(
        messages = messages.map { message ->
            if (message.id == messageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == messageId) message.copy(reactions = reactions.orderedLike(message.reactions)) else message
        }
    )
}

internal fun RaftThreadScreenPayload.withReplyThreadRead(
    parentMessageId: String,
    threadChannelId: String?
): RaftThreadScreenPayload {
    val cleanParentMessageId = parentMessageId.trim()
    if (cleanParentMessageId.isBlank()) return this
    val cleanThreadChannelId = threadChannelId?.trim()?.takeIf { it.isNotBlank() }
    fun RaftThreadMessageItem.clearIfParent(): RaftThreadMessageItem {
        if (id != cleanParentMessageId) return this
        return copy(
            threadChannelId = cleanThreadChannelId ?: this.threadChannelId,
            unreadReplyCount = 0,
            firstUnreadReplyMessageId = null
        )
    }
    return copy(
        messages = messages.map { it.clearIfParent() },
        originMessage = originMessage?.clearIfParent()
    )
}

internal fun RaftThreadScreenPayload.withMessageThreadFollowed(
    parentMessageId: String,
    threadChannelId: String?,
    followed: Boolean,
    replyCount: Int
): RaftThreadScreenPayload {
    val cleanParentMessageId = parentMessageId.trim()
    if (cleanParentMessageId.isBlank()) return this
    val cleanThreadChannelId = threadChannelId?.trim()?.takeIf { it.isNotBlank() }
    fun RaftThreadMessageItem.updateIfParent(): RaftThreadMessageItem {
        if (id != cleanParentMessageId) return this
        return copy(
            threadChannelId = cleanThreadChannelId ?: this.threadChannelId,
            replyCount = maxOf(this.replyCount, replyCount),
            isThreadFollowed = followed
        )
    }
    return copy(
        messages = messages.map { it.updateIfParent() },
        originMessage = originMessage?.updateIfParent()
    )
}

private fun RaftThreadMessageItem.withReactionSelection(emoji: String, selected: Boolean): RaftThreadMessageItem {
    var matched = false
    val updated =
        reactions.mapNotNull { reaction ->
            if (reaction.emoji != emoji) return@mapNotNull reaction
            matched = true
            val delta =
                when {
                    selected && !reaction.selected -> 1
                    !selected && reaction.selected -> -1
                    else -> 0
                }
            val nextCount = (reaction.count + delta).coerceAtLeast(0)
            if (nextCount > 0) {
                reaction.copy(count = nextCount, selected = selected)
            } else {
                null
            }
        }
    val nextReactions =
        if (!matched && selected) {
            updated + RaftThreadReactionItem(emoji = emoji, count = 1, selected = true)
        } else {
            updated
        }
    return copy(reactions = nextReactions)
}

// task #569 follow-up: the who-reacted tooltip reads reactorNames off the
// RaftThreadReactionItem. The server's initial GET already carries names
// (messageService enrichWithSenderNames), but the addReaction mutation summary
// and any reduced payload can arrive with reactorNames empty while reactorIds
// is populated — so right after you tap a reaction the tooltip degraded to the
// bare "1 person" count. Resolve display names on-device (no server change):
// keep any server-provided names, then resolve remaining reactor ids: the
// current user's own id from the session identity (authoritative, always known
// regardless of roster completeness) and everyone else from the thread member
// directory. So a just-added self-reaction shows the name immediately, and
// other reactors resolve whenever they're in the roster. Degrades to the honest
// count when neither a name nor a resolvable id is available.
internal fun resolveThreadReactorNames(
    reactorIds: List<String>,
    reactorNames: List<String>,
    memberNameById: Map<String, String>,
    currentUserId: String? = null,
    currentUserName: String? = null
): List<String> {
    val cleanSelfId = currentUserId?.trim()?.takeIf { it.isNotBlank() }
    val cleanSelfName = currentUserName?.trim()?.takeIf { it.isNotBlank() }
    // Identity is the source of truth: dedup by reactor id (two distinct people
    // who happen to share a display name each count), then map each id to a name
    // — self from the session, others from the index-aligned server name or the
    // member directory. Unresolvable ids are dropped; the tooltip label's "+K"
    // remainder (max(count, names)) still reflects the true reactor count.
    val resolved = LinkedHashSet<String>()
    if (reactorIds.isEmpty()) {
        // Older/edge payload with names but no ids to anchor to — dedup by name.
        reactorNames.forEach { name -> name.trim().takeIf { it.isNotBlank() }?.let(resolved::add) }
        return resolved.toList()
    }
    val seenIds = HashSet<String>()
    reactorIds.forEachIndexed { index, rawId ->
        val id = rawId.trim()
        if (id.isBlank() || !seenIds.add(id)) return@forEachIndexed
        val name =
            if (id == cleanSelfId && cleanSelfName != null) {
                cleanSelfName
            } else {
                reactorNames.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() }
                    ?: memberNameById[id]?.trim()?.takeIf { it.isNotBlank() }
            }
        name?.let(resolved::add)
    }
    return resolved.toList()
}

internal fun List<MessageReactionSummary>.toThreadReactionItems(
    currentUserId: String?,
    memberNameById: Map<String, String> = emptyMap(),
    currentUserName: String? = null
): List<RaftThreadReactionItem> =
    mapNotNull { reaction ->
        val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val count = reaction.count.takeIf { it > 0 } ?: return@mapNotNull null
        RaftThreadReactionItem(
            emoji = emoji,
            count = count,
            selected = currentUserId != null && currentUserId in reaction.reactorIds,
            reactorIds = reaction.reactorIds,
            reactorNames =
                if (memberNameById.isEmpty() && currentUserName.isNullOrBlank()) {
                    reaction.reactorNames
                } else {
                    resolveThreadReactorNames(
                        reaction.reactorIds,
                        reaction.reactorNames,
                        memberNameById,
                        currentUserId,
                        currentUserName
                    )
                }
        )
    }

internal fun List<RaftThreadReactionItem>.selectedForEmoji(emoji: String): Boolean =
    any { reaction -> reaction.emoji == emoji && reaction.selected && reaction.count > 0 }

internal fun List<MessageReaction>?.toRealtimeThreadReactionItems(
    currentUserId: String?,
    memberNameById: Map<String, String> = emptyMap()
): List<RaftThreadReactionItem>? =
    this?.mapNotNull { reaction ->
        val emoji = reaction.emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val count = reaction.count.takeIf { it > 0 } ?: return@mapNotNull null
        RaftThreadReactionItem(
            emoji = emoji,
            count = count,
            selected = currentUserId != null && currentUserId in reaction.reactorIds,
            reactorIds = reaction.reactorIds,
            reactorNames =
                if (memberNameById.isEmpty()) {
                    reaction.reactorNames
                } else {
                    resolveThreadReactorNames(reaction.reactorIds, reaction.reactorNames, memberNameById)
                }
        )
    }

internal fun Message.matchesSharedThread(
    payload: RaftThreadScreenPayload,
    activeReplyParentMessageId: MessageId?
): Boolean {
    val replyThreadContentChannelId = payload.replyThreadContentChannelId()
    if (replyThreadContentChannelId != null) {
        return belongsToReplyThreadContent(replyThreadContentChannelId)
    }
    val selectedThreadId = payload.cacheThreadId()
    val originParentMessageId = payload.originParentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId)
    return selectedThreadId?.let { selectedId ->
        threadId.value == selectedId ||
            channelId?.value == selectedId ||
            threadChannelId?.value == selectedId
    } == true ||
        originParentMessageId?.let { parentMessageId == it } == true ||
        activeReplyParentMessageId?.let { parentMessageId == it } == true
}

internal fun RaftThreadMessageItem.mergeRealtimeUpdate(
    update: RaftThreadMessageItem,
    reactionUpdate: List<RaftThreadReactionItem>? = null,
    preserveExistingReactions: Boolean = false
): RaftThreadMessageItem {
    // RFC 082 (web messageStore): the live update and the sync log's copy of a card message can
    // cross; an older card revision never replaces the newer one held.
    if (isOlderMessageCardRevision(held = metadata, incoming = update.metadata)) return this
    val currentAuthorId = authorId.resolvedRealtimeThreadAuthorIdOrNull()
    val incomingAuthorId = update.authorId.resolvedRealtimeThreadAuthorIdOrNull()
    // A message author is immutable. Sparse realtime patches have historically
    // carried an event actor id without matching author presentation fields;
    // accepting that id would leave the old name/avatar clickable as another
    // member. Only hydrate an unresolved author or refresh the same identity.
    val acceptsIncomingAuthor =
        incomingAuthorId != null && (currentAuthorId == null || currentAuthorId == incomingAuthorId)
    return copy(
        authorId = if (acceptsIncomingAuthor) update.authorId else authorId,
        authorName =
            if (acceptsIncomingAuthor) {
                update.authorName.takeUnless { it.isBlank() || it == update.authorId } ?: authorName
            } else {
                authorName
            },
        isOwn = if (acceptsIncomingAuthor) update.isOwn else isOwn,
        avatarLabel = if (acceptsIncomingAuthor) update.avatarLabel.ifBlank { avatarLabel } else avatarLabel,
        avatarWireValue = if (acceptsIncomingAuthor) update.avatarWireValue ?: avatarWireValue else avatarWireValue,
        // Creation time is immutable. Realtime message updates may carry a
        // later updatedAt-derived label, but must never rewrite the visible
        // timeline timestamp of an already loaded message.
        timestampLabel = timestampLabel.ifBlank { update.timestampLabel },
        createdAtEpochMillis = createdAtEpochMillis ?: update.createdAtEpochMillis,
        seq = update.seq ?: seq,
        messageType = update.messageType.ifBlank { messageType },
        systemEventType = update.systemEventType ?: systemEventType,
        metadata =
            when {
                update.metadata.isEmpty() -> metadata
                isRegressingActionCardState(held = metadata, incoming = update.metadata) -> metadata
                else -> update.metadata
            },
        body = update.body.ifBlank { body },
        // A realtime update with a body carries the full text: a cut Activity seed is no longer cut.
        activityPreviewTruncated = activityPreviewTruncated && update.body.isBlank(),
        activityPreviewLoadFailed = activityPreviewLoadFailed && update.body.isBlank(),
        threadChannelId = update.threadChannelId ?: threadChannelId,
        replyCount = maxOf(replyCount, update.replyCount),
        unreadReplyCount = maxOf(unreadReplyCount, update.unreadReplyCount),
        firstUnreadReplyMessageId = update.firstUnreadReplyMessageId ?: firstUnreadReplyMessageId,
        isThreadFollowed = isThreadFollowed || update.isThreadFollowed,
        isSaved = isSaved || update.isSaved,
        taskId = update.taskId ?: taskId,
        taskNumber = update.taskNumber ?: taskNumber,
        taskStatus = update.taskStatus ?: taskStatus,
        taskAssigneeKind = update.taskAssigneeKind ?: taskAssigneeKind,
        taskAssigneeId = update.taskAssigneeId ?: taskAssigneeId,
        taskAssigneeName = update.taskAssigneeName ?: taskAssigneeName,
        reactions =
            when {
                preserveExistingReactions -> reactions
                reactionUpdate != null -> reactionUpdate.orderedLike(reactions)
                else -> update.reactions.ifEmpty { reactions }
            },
        attachments = mergeAttachmentCommentMetadata(update.attachments),
        commentRef = update.commentRef ?: commentRef,
        // Every message row carries `delegatedBy`; a later row wins (the assistant may since
        // have been deleted), a sparse patch without it keeps the label.
        delegatedBy = keepKnownDelegatedBy(update.delegatedBy, delegatedBy),
        deletedAt = update.deletedAt ?: deletedAt,
        systemSubtype = update.systemSubtype ?: systemSubtype,
        // A read's quote replies win; a live copy carries none (room broadcasts never do), so the
        // loaded ones stay while the words they were resolved for stay.
        resolvedQuotes = update.resolvedQuotes ?: resolvedQuotes.takeIf { update.body.isBlank() || update.body == body },
    ).let { merged ->
        // RFC 077 PR 2b: a retraction is authoritative. The sparse-patch fallbacks above
        // (`ifBlank`/`ifEmpty` keep the loaded body, attachments, reactions) must not keep
        // what the owner took back: the server's row replaces the body, its attachments
        // are revoked and its reactions deleted.
        if (update.isRetracted) merged.copy(body = update.body).withoutRetractedContent() else merged.withoutRetractedContent()
    }
}

private fun String.resolvedRealtimeThreadAuthorIdOrNull(): String? =
    trim().takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }

private fun RaftThreadMessageItem.mergeAttachmentCommentMetadata(
    updates: List<RaftThreadAttachmentItem>
): List<RaftThreadAttachmentItem> {
    if (updates.isEmpty()) return attachments
    if (attachments.isEmpty()) return updates
    val previousById = attachments.associateBy { it.id }
    return updates.map { update ->
        val previous = previousById[update.id]
        if (previous != null && update.commentCount == 0 && previous.commentCount > 0) {
            update.copy(commentCount = previous.commentCount)
        } else {
            update
        }
    }
}
