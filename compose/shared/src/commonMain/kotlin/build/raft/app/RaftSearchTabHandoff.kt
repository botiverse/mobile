package build.raft.app

import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A query carried from a conversation-scoped search page to the phone Search tab. */
internal data class RaftSearchTabQueryRequest(val query: String, val nonce: Long)

/**
 * Nav spec §7.2 / §10.5: a channel or DM's search is a pushed page scoped to that conversation;
 * its "Search all" action switches to the Search tab with the same query instead of turning the
 * pushed page into global search. The pushed pages between it and the app shell (the search page
 * itself and the conversation page) close; the shell selects the Search tab and applies the query.
 *
 * Process-wide because each pushed page is its own host. A request older than [maxAgeMillis] is
 * dropped, so a stack without the shell under it never keeps closing pages later.
 */
internal object RaftSearchTabHandoff {
    private const val maxAgeMillis = 5_000L
    private val clock = TimeSource.Monotonic
    private var requestedAt = clock.markNow()
    private var nextNonce = 0L
    private val pendingState = MutableStateFlow<RaftSearchTabQueryRequest?>(null)

    val pending: StateFlow<RaftSearchTabQueryRequest?> = pendingState.asStateFlow()

    fun request(query: String) {
        nextNonce += 1
        requestedAt = clock.markNow()
        pendingState.value = RaftSearchTabQueryRequest(query.trim(), nextNonce)
    }

    /** True while a pushed page reappearing on the way back to the shell should close. */
    fun closesPushedPages(): Boolean = fresh() != null

    /** The shell takes the request once it is visible again. */
    fun consume(): RaftSearchTabQueryRequest? {
        val request = fresh()
        pendingState.value = null
        return request
    }

    private fun fresh(): RaftSearchTabQueryRequest? {
        val request = pendingState.value ?: return null
        if (requestedAt.elapsedNow().inWholeMilliseconds > maxAgeMillis) {
            pendingState.value = null
            return null
        }
        return request
    }

    internal fun resetForTest() {
        pendingState.value = null
    }
}
