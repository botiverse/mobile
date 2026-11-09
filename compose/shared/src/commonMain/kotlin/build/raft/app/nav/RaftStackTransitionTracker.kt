package build.raft.app.nav

/** What moved on a page stack: a page pushed, a page popped, or a predictive back gesture's preview. */
enum class RaftStackTransitionKind(val wire: String) {
    Push("push"),
    Pop("pop"),
    PredictiveBack("predictive_back"),
}

/**
 * How a stack transition ended: [Completed] (the motion settled on its target page; a predictive
 * back gesture released past the threshold), [Cancelled] (a predictive back gesture let go back
 * onto the page it started on), [Interrupted] (another stack change started before it settled).
 */
enum class RaftStackTransitionOutcome(val wire: String) {
    Completed("completed"),
    Cancelled("cancelled"),
    Interrupted("interrupted"),
}

/**
 * A host's view of its stack's transitions (Android: the frame probe of each one). One
 * [onTransitionStarted] is followed by exactly one [onTransitionEnded]. Page names only (identity).
 */
interface RaftStackTransitionListener {
    fun onTransitionStarted(kind: RaftStackTransitionKind, fromPage: String, toPage: String)

    fun onTransitionEnded(outcome: RaftStackTransitionOutcome)
}

/**
 * Turns what [RaftMobilePageStackDisplay] sees into transitions for [listener]:
 *
 * - [onStack]: the stack's content keys after a change. A new top page that was not on the stack
 *   is a push, one that was is a pop; a pop to the page a predictive back gesture was previewing
 *   is that gesture completing (its release motion still runs, so the transition stays open).
 * - [onBackPreview]: the top scene started leaving while it is still on top (NavDisplay seeking a
 *   predictive back gesture): a predictive back transition starts.
 * - [onSettled]: the scene of a top page finished its motion (fully shown, nothing running): the
 *   open transition ends there (completed, or cancelled for a gesture that let go on its start page).
 *
 * Plain state, main thread only; content keys are `pageName#id` ([RaftPageKey.contentKey]).
 */
class RaftStackTransitionTracker(var listener: RaftStackTransitionListener? = null) {
    private class Active(val kind: RaftStackTransitionKind, val fromKey: String, val toKey: String) {
        var popped = false
    }

    private var keys: List<String> = emptyList()
    private var active: Active? = null

    fun onStack(contentKeys: List<String>) {
        val previous = keys
        if (contentKeys == previous) return
        keys = contentKeys
        val oldTop = previous.lastOrNull() ?: return
        val newTop = contentKeys.lastOrNull() ?: return
        if (oldTop == newTop) return
        val current = active
        if (current != null && current.kind == RaftStackTransitionKind.PredictiveBack && !current.popped && newTop == current.toKey) {
            current.popped = true
            return
        }
        val kind = if (newTop in previous) RaftStackTransitionKind.Pop else RaftStackTransitionKind.Push
        start(kind, oldTop, newTop)
    }

    fun onBackPreview(topKey: String) {
        if (topKey != keys.lastOrNull()) return
        val current = active
        if (current != null && current.kind == RaftStackTransitionKind.PredictiveBack && current.fromKey == topKey && !current.popped) return
        val below = keys.getOrNull(keys.lastIndex - 1) ?: return
        start(RaftStackTransitionKind.PredictiveBack, topKey, below)
    }

    fun onSettled(topKey: String) {
        val current = active ?: return
        if (topKey != keys.lastOrNull()) return
        val outcome =
            when {
                current.kind != RaftStackTransitionKind.PredictiveBack ->
                    if (topKey == current.toKey) RaftStackTransitionOutcome.Completed else return
                current.popped && topKey == current.toKey -> RaftStackTransitionOutcome.Completed
                !current.popped && topKey == current.fromKey -> RaftStackTransitionOutcome.Cancelled
                else -> return
            }
        end(outcome)
    }

    private fun start(kind: RaftStackTransitionKind, fromKey: String, toKey: String) {
        if (active != null) end(RaftStackTransitionOutcome.Interrupted)
        active = Active(kind, fromKey, toKey)
        listener?.onTransitionStarted(kind, raftStackPageNameOf(fromKey), raftStackPageNameOf(toKey))
    }

    private fun end(outcome: RaftStackTransitionOutcome) {
        active = null
        listener?.onTransitionEnded(outcome)
    }
}

/** The page name of a content key (`pageName#id`). */
internal fun raftStackPageNameOf(contentKey: String): String = contentKey.substringBeforeLast('#')
