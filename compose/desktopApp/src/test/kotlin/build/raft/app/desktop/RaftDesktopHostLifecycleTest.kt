package build.raft.app.desktop

import androidx.lifecycle.Lifecycle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The lifecycle the desktop pages see (artin: the wide detail pane went blank whenever the window
 * lost focus). The window's lifecycle, derived from the Tao window's state as Compose Desktop's AWT
 * window drove it: focused RESUMED, unfocused STARTED, minimized CREATED. The host passes it through
 * (focus loss stays STARTED, which pages treat as shown) and adds macOS Hide as CREATED, the same
 * background as minimizing.
 */
class RaftDesktopHostLifecycleTest {
    @Test
    fun focusLossIsNotBackground() {
        assertEquals(Lifecycle.State.RESUMED, raftDesktopWindowLifecycleState(focused = true, minimized = false))
        assertEquals(Lifecycle.State.STARTED, raftDesktopWindowLifecycleState(focused = false, minimized = false))
        assertEquals(Lifecycle.State.STARTED, raftDesktopHostLifecycleState(Lifecycle.State.STARTED, appHidden = false))
        assertEquals(Lifecycle.State.RESUMED, raftDesktopHostLifecycleState(Lifecycle.State.RESUMED, appHidden = false))
    }

    @Test
    fun minimizedOrHiddenIsBackground() {
        assertEquals(Lifecycle.State.CREATED, raftDesktopWindowLifecycleState(focused = false, minimized = true))
        assertEquals(Lifecycle.State.CREATED, raftDesktopWindowLifecycleState(focused = true, minimized = true))
        assertEquals(Lifecycle.State.CREATED, raftDesktopHostLifecycleState(Lifecycle.State.CREATED, appHidden = false))
        assertEquals(Lifecycle.State.CREATED, raftDesktopHostLifecycleState(Lifecycle.State.RESUMED, appHidden = true))
        assertEquals(Lifecycle.State.CREATED, raftDesktopHostLifecycleState(Lifecycle.State.STARTED, appHidden = true))
        assertEquals(Lifecycle.State.DESTROYED, raftDesktopHostLifecycleState(Lifecycle.State.DESTROYED, appHidden = true))
    }
}
