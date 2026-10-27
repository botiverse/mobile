package build.raft.app.data

import build.raft.app.model.RaftServerThemeLook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Wears one server look flow app-wide: [apply] gets every look of the flow it follows. The
 * provider starts it on the process flow (the session's own current server: a cold start, the
 * login page after a sign-out) and every app shell that appears switches it to its own selected
 * server's flow, so the theme follows the shell on screen. Sign-out, a plan change and
 * `appearance:updated` all reach it through the stored session the flows read.
 *
 * Main thread: [follow] is called from the shell's composition and [scope] dispatches on the
 * main thread, so [apply] (a snapshot state write the hosts' bar icons follow) runs there.
 */
internal class RaftServerThemeFollower(
    private val scope: CoroutineScope,
    private val apply: (RaftServerThemeLook) -> Unit,
) {
    private var followedKey: Any? = null
    private var job: Job? = null

    /** The key of the flow followed now; null before the first [follow]. */
    val currentKey: Any?
        get() = followedKey

    /**
     * Follows [looks] in place of the flow followed before; a no-op when [key] is already
     * followed (a shell recomposing or appearing again).
     */
    fun follow(key: Any, looks: Flow<RaftServerThemeLook>) {
        if (key == followedKey && job?.isActive == true) return
        job?.cancel()
        followedKey = key
        job = scope.launch { looks.collect { look -> apply(look) } }
    }
}
