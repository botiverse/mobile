package build.raft.app.desktop

import androidx.lifecycle.Lifecycle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The lifecycle the desktop pages see (artin: the wide detail pane went blank whenever the window
 * lost focus). Compose Desktop's window lifecycle: focused RESUMED, unfocused STARTED, minimized
 * CREATED. The host passes it through (focus loss stays STARTED, which pages treat as shown) and
 * adds macOS Hide (Cmd-H) as CREATED, the same background as minimizing.
 */
class RaftDesktopHostLifecycleTest {
    @Test
    fun focusLossIsNotBackground() {
        assertEquals(Lifecycle.State.STARTED, raftDesktopHostLifecycleState(Lifecycle.State.STARTED, appHidden = false))
        assertEquals(Lifecycle.State.RESUMED, raftDesktopHostLifecycleState(Lifecycle.State.RESUMED, appHidden = false))
    }

    @Test
    fun minimizedOrHiddenIsBackground() {
        assertEquals(Lifecycle.State.CREATED, raftDesktopHostLifecycleState(Lifecycle.State.CREATED, appHidden = false))
        assertEquals(Lifecycle.State.CREATED, raftDesktopHostLifecycleState(Lifecycle.State.RESUMED, appHidden = true))
        assertEquals(Lifecycle.State.CREATED, raftDesktopHostLifecycleState(Lifecycle.State.STARTED, appHidden = true))
        assertEquals(Lifecycle.State.DESTROYED, raftDesktopHostLifecycleState(Lifecycle.State.DESTROYED, appHidden = true))
    }
}
