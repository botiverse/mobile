package build.raft.app.cmp.host

import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * What makes two page opens "the same page": the same thread / conversation / message anchor,
 * whatever per-open facts (a clock, a nonce, an animation hint) their pageData also carries.
 * :ui-cmp cannot read :shared's routes, so :shared installs its resolver
 * ([RaftPageRouteIdentities.install]); until then [RaftPageRouteIdentities.default] applies.
 */
fun interface RaftPageRouteIdentity {
    fun identityOf(pageName: String, pageData: JsonObject): Any
}

object RaftPageRouteIdentities {
    /** pageData keys that differ between two opens of the same page (clock, nonces, motion hints). */
    val volatileKeys: Set<String> =
        setOf("nowEpochMillis", "todayEpochDay", "localOffsetMinutes", "slockOpenWithoutAnimation", "requestId", "openedAt")

    /** Page name plus the pageData minus [volatileKeys]. */
    val default: RaftPageRouteIdentity =
        RaftPageRouteIdentity { pageName, pageData -> pageName to JsonObject(pageData - volatileKeys) }

    @Volatile
    private var installed: RaftPageRouteIdentity? = null

    @Volatile
    private var logger: ((String) -> Unit)? = null

    /** Called by :shared at startup (`RaftPageRegistry.install()`); [log] gets the guard's drops. */
    fun install(identity: RaftPageRouteIdentity, log: (String) -> Unit = {}) {
        installed = identity
        logger = log
    }

    /** The installed log line sink ([RaftPageOpenGuard]'s default). */
    fun log(message: String) {
        logger?.invoke(message)
    }

    /** The identity of an open of [pageName] with [pageData]; the default when the resolver throws. */
    fun of(pageName: String, pageData: JsonObject): Any =
        runCatching { (installed ?: default).identityOf(pageName, pageData) }.getOrElse { default.identityOf(pageName, pageData) }
}

/**
 * Makes one page's `openPage` idempotent while the push it started is still running (task #151:
 * two quick taps on a message's replies row opened the thread twice; during the ~300 ms push the
 * page being covered still takes taps, and every host's push appended a page per call).
 *
 * A push opens the window [windowMillis] long (the push motion plus a frame of slack). Inside it:
 * - an open of the same route as the push, or as one already queued, is dropped (a double tap);
 * - an open of a different route is queued and only the latest one is kept; it is opened when the
 *   window ends (its own push then opens a new window), so a fast "this thread, no, that one"
 *   still lands on the last thing tapped, after the first push settled, never mid slide;
 * - a replace (`slockReplaceCurrentPage`) is not a push: it is opened at once (unless it repeats
 *   the push's route).
 * After the window every open goes straight through, so Back then the same row again, or a
 * notification for the thread just left, opens normally. [cancelPending] (the page closing)
 * forgets a queued open. Routes compare by [RaftPageRouteIdentities].
 *
 * Plain state, main thread only. [now] is a monotonic millisecond clock; [schedule] runs a block
 * after a delay on the main thread (the page's composition scope in production).
 */
class RaftPageOpenGuard(
    private val now: () -> Long,
    private val schedule: (delayMillis: Long, block: () -> Unit) -> Unit,
    private val windowMillis: Long = RaftPageOpenTransitionMillis,
    private val identityOf: (String, JsonObject) -> Any = RaftPageRouteIdentities::of,
    private val log: (String) -> Unit = RaftPageRouteIdentities::log,
) {
    private class Open(val identity: Any, val pageName: String, val pageData: JsonObject, val dispatch: (String, JsonObject) -> Unit)

    private var last: Any? = null
    private var lastAt = 0L
    private var pending: Open? = null
    private var flushScheduled = false

    /** True while the last admitted push is still inside its window. */
    val inTransition: Boolean
        get() = last != null && now() - lastAt < windowMillis

    fun open(pageName: String, pageData: JsonObject, dispatch: (String, JsonObject) -> Unit) {
        val open = Open(identityOf(pageName, pageData), pageName, pageData, dispatch)
        if (!inTransition) {
            pending = null
            admit(open)
            return
        }
        when {
            open.identity == last -> log("open_dropped page=$pageName reason=same_route_in_transition")
            open.identity == pending?.identity -> log("open_dropped page=$pageName reason=same_route_queued")
            (pageData[REPLACE_CURRENT_PAGE] as? JsonPrimitive)?.booleanOrNull == true -> admit(open)
            else -> {
                pending?.let { log("open_dropped page=${it.pageName} reason=superseded_in_transition") }
                pending = open
                log("open_queued page=$pageName")
                if (!flushScheduled) {
                    flushScheduled = true
                    schedule((windowMillis - (now() - lastAt)).coerceAtLeast(0L)) { flush() }
                }
            }
        }
    }

    /** Forgets a queued open (the page that asked for it is closing). */
    fun cancelPending() {
        pending?.let { log("open_dropped page=${it.pageName} reason=source_closed") }
        pending = null
    }

    private fun flush() {
        flushScheduled = false
        val open = pending ?: return
        pending = null
        admit(open)
    }

    private fun admit(open: Open) {
        last = open.identity
        lastAt = now()
        open.dispatch(open.pageName, open.pageData)
    }

    private companion object {
        const val REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
    }
}

/** A push's window in [RaftPageOpenGuard]: the 300 ms push slide plus a little slack. */
const val RaftPageOpenTransitionMillis: Long = 450L
