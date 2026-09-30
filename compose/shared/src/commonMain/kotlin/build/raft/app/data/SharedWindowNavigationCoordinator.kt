package build.raft.app.data

import build.raft.app.ConversationOpenSourceMessageIdentity
import build.raft.app.HomeMessageOpenRequest
import build.raft.app.resolveOpenRequest
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Identity-only navigation request owned by one AppShell/window scope.
 *
 * Android (or another host) chooses the target window before AppShell starts. Once the page is
 * attached, the request lives here instead of in a composable-local remembered value, so an
 * Activity recreation cannot replay an already dispatched route.
 */
internal data class WindowNavigationIntent(
    val serverId: String,
    val sourceIdentity: ConversationOpenSourceMessageIdentity,
    val source: String,
    /** Unique id per external system event; retries reuse it, new clicks mint one. */
    val activationId: String? = null,
    /** Principal+generation the intent was bound to; null = unbound (pre-login). */
    val authGeneration: String? = null
) {
    val key: String =
        listOf(
            serverId,
            source,
            activationId.orEmpty(),
            sourceIdentity.source.name,
            sourceIdentity.threadChannelId.orEmpty(),
            sourceIdentity.channelId.orEmpty(),
            sourceIdentity.parentChannelId.orEmpty(),
            sourceIdentity.parentMessageId.orEmpty(),
            sourceIdentity.messageId.orEmpty()
        ).joinToString("|")

    init {
        require(serverId.isNotBlank()) { "Window navigation requires a server id" }
        require(source.isNotBlank()) { "Window navigation requires a source" }
    }
}

/** Readiness of the server list itself; Loaded is the only state that may reject. */

/** Canonical, server-qualified readiness supplied by the AppShell integration. */
internal enum class WindowNavigationServerListState { Loading, Failed, Loaded }

internal data class WindowNavigationReadiness(
    val knownServerIds: Set<String> = emptySet(),
    val canonicalServerId: String? = null,
    val homeSnapshotServerId: String? = null,
    val homeSessionServerId: String? = null,
    val conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState(),
    val serverListState: WindowNavigationServerListState,
    /** Current authenticated principal generation; stale bound intents are dropped. */
    val authGeneration: String? = null
)

/**
 * The #599 predicate in coordinator form. A target route is not consumable until both the
 * canonical shell session and Home's own session/snapshot projection identify the same server.
 */
internal fun windowNavigationHomeReady(
    targetServerId: String,
    canonicalServerId: String?,
    homeSnapshotServerId: String?,
    homeSessionServerId: String?
): Boolean =
    targetServerId.isNotBlank() &&
        canonicalServerId == targetServerId &&
        homeSnapshotServerId == targetServerId &&
        homeSessionServerId == targetServerId

internal sealed interface WindowNavigationAction {
    val intent: WindowNavigationIntent

    data class ActivateServer(override val intent: WindowNavigationIntent) : WindowNavigationAction

    data class EnsureHome(override val intent: WindowNavigationIntent) : WindowNavigationAction

    data class OpenConversation(
        override val intent: WindowNavigationIntent,
        val request: HomeMessageOpenRequest
    ) : WindowNavigationAction

    data class Reject(
        override val intent: WindowNavigationIntent,
        val reason: String
    ) : WindowNavigationAction
}

/**
 * Per-window navigation queue.
 *
 * All state is intentionally keyed by [SlockShellStoreScope] by the provider. The coordinator
 * owns exactly-once deduplication and staging; the UI only executes its returned platform-neutral
 * actions. A terminal request remains recorded until its shell scope is released, preventing an
 * Activity recreation or duplicate PendingIntent from opening the route twice.
 */
internal class SharedWindowNavigationCoordinator {
    private val lock = SynchronizedObject()
    private val requests = linkedMapOf<String, RequestState>()
    private var released = false

    val requestCount: Int
        get() = synchronized(lock) { requests.size }

    fun enqueue(intent: WindowNavigationIntent): Boolean = synchronized(lock) {
        if (released) return false
        if (requests.containsKey(intent.key)) {
            false
        } else {
            requests[intent.key] = RequestState(intent = intent)
            true
        }
    }

    /**
     * Advances at most one request. Returned actions are already marked dispatched while holding
     * the lock, so repeated recompositions cannot issue another activation, Home start, or open.
     */
    fun advance(readiness: WindowNavigationReadiness): WindowNavigationAction? = synchronized(lock) {
        if (released) return null
        val state = requests.values.firstOrNull { !it.stage.terminal } ?: return null
        val intent = state.intent

        if (intent.sourceIdentity.lookupIds().isEmpty()) {
            state.stage = RequestStage.Rejected
            return WindowNavigationAction.Reject(intent, "conversation_identity_missing")
        }

        if (
            readiness.serverListState == WindowNavigationServerListState.Loaded &&
            intent.serverId !in readiness.knownServerIds
        ) {
            state.stage = RequestStage.Rejected
            return WindowNavigationAction.Reject(intent, "server_unavailable")
        }

        // A bound intent whose principal generation moved on — or whose session is
        // gone entirely — is dead: terminate it rather than replaying a previous
        // account's pending route. An Unbound intent (delivered before login,
        // authGeneration == null) binds exactly once to the first authenticated
        // generation the readiness reports; further switches kill it as stale.
        if (intent.authGeneration == null) {
            readiness.authGeneration?.let { bound ->
                state.intent = intent.copy(authGeneration = bound)
            }
        } else if (readiness.authGeneration != intent.authGeneration) {
            state.stage = RequestStage.Rejected
            return WindowNavigationAction.Reject(intent, "auth_generation_stale")
        }

        val canonicalServerId = readiness.canonicalServerId
        if (canonicalServerId == null) {
            return null
        }
        if (canonicalServerId != intent.serverId) {
            if (state.activationRequested) return null
            state.activationRequested = true
            return WindowNavigationAction.ActivateServer(intent)
        }

        if (
            !windowNavigationHomeReady(
                targetServerId = intent.serverId,
                canonicalServerId = canonicalServerId,
                homeSnapshotServerId = readiness.homeSnapshotServerId,
                homeSessionServerId = readiness.homeSessionServerId
            )
        ) {
            if (state.homeStartRequested) return null
            state.homeStartRequested = true
            return WindowNavigationAction.EnsureHome(intent)
        }

        return when (
            val resolution =
                resolveOpenRequest(
                    serverId = intent.serverId,
                    sourceMessageIdentity = intent.sourceIdentity,
                    conversationDirectory = readiness.conversationDirectory
                )
        ) {
            is build.raft.app.ConversationOpenResolution.Resolved -> {
                state.stage = RequestStage.Opened
                WindowNavigationAction.OpenConversation(intent, resolution.request)
            }
            // Directory snapshots can lag a ready Home/server projection. Keep this request
            // pending and let the next directory emission retry rather than irreversibly
            // consuming a route that can become resolvable moments later.
            is build.raft.app.ConversationOpenResolution.Pending -> null
        }
    }

    /** Records a failed host-level select-server operation as terminal exactly once. */
    fun rejectActivation(intentKey: String, reason: String): WindowNavigationAction.Reject? = synchronized(lock) {
        if (released) return null
        val state = requests[intentKey] ?: return null
        if (state.stage.terminal) return null
        state.stage = RequestStage.Rejected
        WindowNavigationAction.Reject(state.intent, reason)
    }

    /**
     * Releases this coordinator permanently. The provider creates a fresh instance for a later
     * shell with the same id, while any late coroutine holding this old instance becomes inert.
     */
    fun clear() = synchronized(lock) {
        released = true
        requests.clear()
    }

    private data class RequestState(
        var intent: WindowNavigationIntent,
        var activationRequested: Boolean = false,
        var homeStartRequested: Boolean = false,
        var stage: RequestStage = RequestStage.Pending
    )

    private enum class RequestStage(val terminal: Boolean) {
        Pending(false),
        Opened(true),
        Rejected(true)
    }
}
