package build.raft.app

import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A query carried from a conversation-scoped search page to the app shell's Search. */
internal data class RaftSearchTabQueryRequest(val query: String, val nonce: Long)

/** How a page takes part in a pending "Search all" handoff when it reappears. */
internal enum class RaftSearchTabHandoffRole {
    /** A page stacked over the shell: it closes so the stack pops back to the shell. */
    PopToShell,
    /** The app shell: it takes the request and opens Search with the query. */
    Shell,
    /** Not part of the page stack (an embedded child page): never acts. */
    Ignore,
}

/** What a page does with the pending request when it reappears. */
internal enum class RaftSearchTabHandoffAction { None, ClosePage, Consume }

/**
 * Nav spec §7.2 / §10.5: a channel or DM's search is a pushed page scoped to that conversation;
 * its "Search all" action switches to Search with the same query instead of turning the pushed
 * page into global search.
 *
 * Each pushed page is its own host, so the request is process-wide, but it is deterministic:
 * - it is only made while an app shell is alive ([request] returns false otherwise);
 * - every page stacked over the shell that *reappears* while it is pending closes itself, so the
 *   stack pops all the way back to the shell (a page's first appearance never acts);
 * - the shell consumes it exactly once when it reappears;
 * - a request older than [maxAgeMillis] is dropped, so nothing acts on it later.
 */
internal class RaftSearchTabHandoffState(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val maxAgeMillis: Long = 5_000L,
) {
    private var pending: RaftSearchTabQueryRequest? = null
    private var requestedAt: TimeMark? = null
    private var nextNonce = 0L

    fun request(query: String, shellAlive: Boolean): Boolean {
        if (!shellAlive) {
            pending = null
            return false
        }
        nextNonce += 1
        pending = RaftSearchTabQueryRequest(query.trim(), nextNonce)
        requestedAt = timeSource.markNow()
        return true
    }

    /** Called by a page each time it appears; returns what that page must do. */
    fun onPageAppeared(role: RaftSearchTabHandoffRole, isInitialAppearance: Boolean): RaftSearchTabHandoffAction {
        if (isInitialAppearance || role == RaftSearchTabHandoffRole.Ignore) return RaftSearchTabHandoffAction.None
        fresh() ?: return RaftSearchTabHandoffAction.None
        return when (role) {
            RaftSearchTabHandoffRole.PopToShell -> RaftSearchTabHandoffAction.ClosePage
            RaftSearchTabHandoffRole.Shell -> RaftSearchTabHandoffAction.Consume
            RaftSearchTabHandoffRole.Ignore -> RaftSearchTabHandoffAction.None
        }
    }

    /** The shell takes the request (exactly once). */
    fun consume(): RaftSearchTabQueryRequest? {
        val request = fresh()
        clear()
        return request
    }

    val hasPending: Boolean get() = fresh() != null

    private fun fresh(): RaftSearchTabQueryRequest? {
        val request = pending ?: return null
        val age = requestedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
        if (age > maxAgeMillis) {
            clear()
            return null
        }
        return request
    }

    private fun clear() {
        pending = null
        requestedAt = null
    }
}

/** The process-wide handoff shared by every page host. */
internal val RaftSearchTabHandoff = RaftSearchTabHandoffState()
