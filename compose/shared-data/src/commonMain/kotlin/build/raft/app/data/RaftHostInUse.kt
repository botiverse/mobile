package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How recent the last input must be for a window to count as in use: the approved "in use" rule,
 * the same as the server's push-defer rule (botiverse/slock docs/contracts/notifications.md).
 */
const val RaftHostInUseInputWindowMillis: Long = 3L * 60L * 1000L

/**
 * Whether the person is using the app window now, on top of it being on screen
 * ([isRaftHostVisible]). "Seen = read" holds only while both do.
 *
 * Phones and tablets leave this true: the app in the foreground with the conversation visible
 * counts as in use. Compose desktop sets it from [raftWindowInUse] (the window focused and input
 * within [RaftHostInUseInputWindowMillis]), so a window left open on a desk does not read what
 * arrives in it, and the person's phone still gets the push. When it holds again, the page reads
 * what is in front of the person then.
 */
object RaftHostInUse {
    private val state = MutableStateFlow(true)
    val inUse: StateFlow<Boolean> = state.asStateFlow()
    val isInUse: Boolean
        get() = state.value

    fun update(inUse: Boolean) {
        state.value = inUse
    }
}

/** The approved rule for a desktop window: focused, with input in the last [inputWindowMillis]. */
fun raftWindowInUse(
    focused: Boolean,
    lastInputAtMillis: Long?,
    nowMillis: Long,
    inputWindowMillis: Long = RaftHostInUseInputWindowMillis,
): Boolean {
    if (!focused || lastInputAtMillis == null) return false
    val sinceInput = nowMillis - lastInputAtMillis
    return sinceInput in 0L until inputWindowMillis
}

/**
 * What a shell has on screen counts as seen right now: the host is visible and in use. Home and
 * Activity skip unread only for a selected conversation that is seen; one left open in a hidden
 * or idle window counts like any other, as the server counts it.
 */
fun raftHostShowsContent(shellInstanceId: String): Boolean =
    isRaftHostVisible(shellInstanceId) && RaftHostInUse.isInUse
