package build.raft.app.cmp.host

import kotlinx.serialization.json.JsonObject

/** What [RaftNativePageOpenGate.open] did with an open. */
enum class RaftNativePageOpenOutcome { Opened, Dropped, Queued }

/**
 * Makes a native page stack's pushes idempotent (task #151 follow-up): the stacks [RaftPageOpenGuard]
 * does not see whole, because their opens also come from outside a page's navigator or outlive its
 * window (Android's Activity path: notification, banner and shortcut opens, a push still starting
 * when the guard's fixed window ends). One gate per native stack (an Android task); the iOS router
 * applies the same rules in `RaftIosPageOpenGate.swift`.
 *
 * - An open of the route already on top ([topRoute]) is dropped: a second tap, or a notification
 *   for the page shown.
 * - While a push this gate let through is still running (until [transitionEnded], which the host
 *   calls when the pushed page's enter transition ends, or when it gives up waiting): an open of
 *   that route, or of the one already queued, is dropped; an open of a different route is queued,
 *   only the latest kept, and opened when the transition ends (checked against the top again).
 * - A replace is not a push: it opens at once.
 *
 * Routes compare by [RaftPageRouteIdentities] ([identityOf]). Plain state, main thread only.
 */
class RaftNativePageOpenGate(
    private val topRoute: () -> Any?,
    private val identityOf: (String, JsonObject) -> Any = RaftPageRouteIdentities::of,
    private val log: (String) -> Unit = RaftPageRouteIdentities::log,
) {
    private class Open(val identity: Any, val pageName: String, val pageData: JsonObject, val dispatch: () -> Unit)

    private var inFlight: Any? = null
    private var pending: Open? = null

    /** True from a push this gate let through until [transitionEnded]. */
    val transitionRunning: Boolean
        get() = inFlight != null

    /** The route identity of a page (the same one [open] compares), for the host's [topRoute]. */
    fun routeOf(pageName: String, pageData: JsonObject): Any = identityOf(pageName, pageData)

    /**
     * Opens [pageName] with [dispatch] (the host's push), or drops / queues it. [replace]: a
     * `slockReplaceCurrentPage` open, always at once. A push starts a transition: the host must
     * call [transitionEnded] when it ends.
     */
    fun open(pageName: String, pageData: JsonObject, replace: Boolean = false, dispatch: () -> Unit): RaftNativePageOpenOutcome {
        if (replace) {
            dispatch()
            return RaftNativePageOpenOutcome.Opened
        }
        return open(Open(identityOf(pageName, pageData), pageName, pageData, dispatch))
    }

    private fun open(open: Open): RaftNativePageOpenOutcome {
        val name = open.pageName
        val running = inFlight
        when {
            open.identity == topRoute() -> log("open_dropped page=$name reason=same_route_on_top")
            running != null && open.identity == running -> log("open_dropped page=$name reason=same_route_in_transition")
            running != null && open.identity == pending?.identity -> log("open_dropped page=$name reason=same_route_queued")
            running != null -> {
                pending?.let { log("open_dropped page=${it.pageName} reason=superseded_in_transition") }
                pending = open
                log("open_queued page=$name")
                return RaftNativePageOpenOutcome.Queued
            }
            else -> {
                inFlight = open.identity
                open.dispatch()
                return RaftNativePageOpenOutcome.Opened
            }
        }
        return RaftNativePageOpenOutcome.Dropped
    }

    /** The running push's transition ended (or the host stopped waiting): opens the queued open, if any. */
    fun transitionEnded() {
        if (inFlight == null) return
        inFlight = null
        val queued = pending ?: return
        pending = null
        open(queued)
    }

    /** Forgets a queued open (its source went away). */
    fun cancelPending() {
        pending?.let { log("open_dropped page=${it.pageName} reason=source_closed") }
        pending = null
    }
}
