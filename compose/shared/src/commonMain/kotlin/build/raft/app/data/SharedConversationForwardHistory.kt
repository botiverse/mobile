package build.raft.app.data

import build.raft.app.ConversationOpenResolution
import build.raft.app.ConversationOpenSource
import build.raft.app.ConversationOpenSourceMessageIdentity
import build.raft.app.HomeMessageOpenRequest
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.ThreadLaunchConversationIdentity
import build.raft.app.model.ConversationKind
import build.raft.app.resolveOpenRequest
import build.raft.app.threadParentChannelId
import build.raft.app.threadParentChannelKind
import build.raft.app.threadParentMessageId
import build.raft.app.threadParentSurfaceKind
import build.raft.app.threadStreamChannelId
import build.raft.app.typedReplyParentConversationKindOrNull
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Identity-only route remembered after a conversation leaves the native navigation stack.
 *
 * This deliberately excludes titles, messages, members, drafts, composer state, scroll state,
 * and controller/pageData snapshots. Forward always resolves a fresh canonical launch identity
 * from the current per-window Home directory before it can reopen anything.
 */
internal data class ConversationForwardEntry(
    val serverId: String,
    val sourceIdentity: ConversationOpenSourceMessageIdentity
) {
    val key: String =
        listOf(
            serverId,
            sourceIdentity.conversationKind?.name.orEmpty(),
            sourceIdentity.threadChannelId.orEmpty(),
            sourceIdentity.channelId.orEmpty(),
            sourceIdentity.parentChannelId.orEmpty(),
            sourceIdentity.parentMessageId.orEmpty(),
            sourceIdentity.parentConversationKind?.name.orEmpty()
        ).joinToString("|")

    init {
        require(serverId.isNotBlank()) { "Conversation Forward history requires a server id" }
        require(sourceIdentity.lookupIds().isNotEmpty()) {
            "Conversation Forward history requires a conversation identity"
        }
    }
}

internal fun ThreadLaunchConversationIdentity.toConversationForwardEntry(
    serverId: String
): ConversationForwardEntry {
    val streamChannelId = threadStreamChannelId()?.takeIf { it.isNotBlank() }
    val parentKind =
        if (kind == ConversationKind.Thread) {
            typedReplyParentConversationKindOrNull(
                threadParentSurfaceKind(),
                threadParentChannelKind()
            )
        } else {
            null
        }
    return ConversationForwardEntry(
        serverId = serverId,
        sourceIdentity =
            ConversationOpenSourceMessageIdentity(
                source = ConversationOpenSource.HistoryForward,
                messageId = null,
                channelId =
                    if (kind == ConversationKind.Thread) {
                        streamChannelId ?: threadId.value.takeIf { it.isNotBlank() }
                    } else {
                        channelId?.value?.takeIf { it.isNotBlank() }
                            ?: streamChannelId
                            ?: threadId.value.takeIf { it.isNotBlank() }
                    },
                threadChannelId = streamChannelId.takeIf { kind == ConversationKind.Thread },
                parentChannelId = threadParentChannelId()?.takeIf { it.isNotBlank() },
                parentMessageId = threadParentMessageId()?.takeIf { it.isNotBlank() },
                conversationKind = kind,
                parentConversationKind = parentKind
            )
    )
}

internal sealed interface ConversationForwardAction {
    data class Open(
        val entry: ConversationForwardEntry,
        val request: HomeMessageOpenRequest
    ) : ConversationForwardAction

    data class Drop(
        val entry: ConversationForwardEntry,
        val reason: String
    ) : ConversationForwardAction
}

/**
 * Per-AppShell/window one-step Forward history.
 *
 * The reducer shape keeps an active entry separate from the single exposed Forward entry. It can
 * be generalized to a bounded deque later without changing the route contract, but product has
 * only approved restoring the most recently left channel/DM. A fresh non-Forward open replaces
 * the active entry and invalidates the obsolete Forward branch.
 */
internal class SharedConversationForwardHistory {
    private val lock = SynchronizedObject()
    private var activeEntry: ConversationForwardEntry? = null
    private var forwardEntry: ConversationForwardEntry? = null
    private var enabled = false
    private var released = false

    val canForward: Boolean
        get() = synchronized(lock) { enabled && !released && forwardEntry != null }

    internal fun enabledForTest(): Boolean = synchronized(lock) { enabled }

    internal fun forwardEntryForTest(): ConversationForwardEntry? =
        synchronized(lock) { forwardEntry }

    /**
     * Applies the app-scoped Beta preference to this window-owned history. Disabling is terminal
     * for every entry recorded in the current enabled epoch; a later enable always starts empty.
     */
    fun setEnabled(nextEnabled: Boolean) = synchronized(lock) {
        if (released || enabled == nextEnabled) return@synchronized
        enabled = nextEnabled
        activeEntry = null
        forwardEntry = null
    }

    /** Records a newly opened route and drops any abandoned Forward branch. */
    fun recordNewOpen(entry: ConversationForwardEntry) = synchronized(lock) {
        if (released || !enabled) return@synchronized
        activeEntry = entry
        forwardEntry = null
    }

    /**
     * Called only when the AppShell page becomes visible again after a native pop. Repeated page
     * appearance signals are idempotent because the active entry is cleared on the first return.
     */
    fun recordHomeVisible(): Boolean = synchronized(lock) {
        if (released || !enabled) return@synchronized false
        activeEntry?.let { entry ->
            forwardEntry = entry
            activeEntry = null
        }
        forwardEntry != null
    }

    /**
     * Consumes at most one gesture request. A successful resolution becomes active immediately,
     * so a duplicate gesture/recomposition cannot push twice. A temporarily unready Home keeps
     * the candidate for a later user gesture; a changed/unavailable identity is dropped closed.
     */
    fun requestForward(readiness: WindowNavigationReadiness): ConversationForwardAction? =
        synchronized(lock) {
            if (released || !enabled) return@synchronized null
            val entry = forwardEntry ?: return@synchronized null

            if (readiness.knownServerIds.isNotEmpty() && entry.serverId !in readiness.knownServerIds) {
                forwardEntry = null
                return@synchronized ConversationForwardAction.Drop(entry, "server_unavailable")
            }
            if (readiness.canonicalServerId != entry.serverId) {
                forwardEntry = null
                return@synchronized ConversationForwardAction.Drop(entry, "server_changed")
            }
            if (
                !windowNavigationHomeReady(
                    targetServerId = entry.serverId,
                    canonicalServerId = readiness.canonicalServerId,
                    homeSnapshotServerId = readiness.homeSnapshotServerId,
                    homeSessionServerId = readiness.homeSessionServerId
                )
            ) {
                return@synchronized null
            }

            val expectedCanonicalId =
                entry.sourceIdentity.threadChannelId
                    ?.takeIf { it.isNotBlank() }
                    ?: entry.sourceIdentity.channelId?.takeIf { it.isNotBlank() }
            val projection = readiness.conversationDirectory.resolve(expectedCanonicalId)
            if (
                projection == null ||
                projection.serverId != entry.serverId ||
                projection.canonicalChannelId != expectedCanonicalId ||
                !projection.joined ||
                projection.archived ||
                !projection.permissions.canRead ||
                !projection.matchesForwardKind(entry.sourceIdentity.conversationKind)
            ) {
                forwardEntry = null
                return@synchronized ConversationForwardAction.Drop(entry, "conversation_unavailable")
            }

            when (
                val resolution =
                    resolveOpenRequest(
                        serverId = entry.serverId,
                        sourceMessageIdentity = entry.sourceIdentity,
                        conversationDirectory = readiness.conversationDirectory
                    )
            ) {
                is ConversationOpenResolution.Pending -> {
                    forwardEntry = null
                    ConversationForwardAction.Drop(entry, resolution.reason)
                }
                is ConversationOpenResolution.Resolved -> {
                    val resolvedEntry =
                        resolution.request.conversationIdentity.toConversationForwardEntry(entry.serverId)
                    if (resolvedEntry.key != entry.key) {
                        forwardEntry = null
                        ConversationForwardAction.Drop(entry, "conversation_identity_changed")
                    } else {
                        forwardEntry = null
                        activeEntry = entry
                        ConversationForwardAction.Open(entry, resolution.request)
                    }
                }
            }
        }

    fun clear() = synchronized(lock) {
        activeEntry = null
        forwardEntry = null
    }

    fun release() = synchronized(lock) {
        released = true
        enabled = false
        activeEntry = null
        forwardEntry = null
    }
}

private fun SharedConversationDirectoryProjection.matchesForwardKind(
    expectedKind: ConversationKind?
): Boolean =
    when (expectedKind) {
        ConversationKind.DirectMessage -> surfaceKind == RaftThreadSurfaceKind.DirectMessage
        ConversationKind.Thread -> surfaceKind == RaftThreadSurfaceKind.ReplyThread
        ConversationKind.Channel,
        ConversationKind.Joint ->
            surfaceKind == RaftThreadSurfaceKind.Channel && conversationKind == expectedKind
        ConversationKind.Inbox -> false
        ConversationKind.System -> conversationKind == ConversationKind.System
        null -> false
    }
