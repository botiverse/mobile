package build.raft.app.desktop

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The lifecycle the desktop pages see (artin: the wide detail pane went blank whenever the window
 * lost focus). Compose Desktop's window lifecycle: focused RESUMED, unfocused STARTED, minimized
 * CREATED. The host passes it through (focus loss stays STARTED, which pages treat as shown) and
 * adds macOS Hide (Cmd-H) as CREATED, the same background as minimizing.
 */
class RaftDesktopHostLifecycleTest {
    private class Window : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

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

    @Test
    fun hostOwnerFollowsWindowFocusMinimizeAndHide() {
        val window = Window()
        window.registry.currentState = Lifecycle.State.RESUMED
        val host = RaftDesktopHostLifecycleOwner(window.lifecycle)
        val seen = mutableListOf<Lifecycle.Event>()
        host.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event -> seen += event })
        assertEquals(Lifecycle.State.RESUMED, host.lifecycle.currentState)
        seen.clear()

        // Another app takes focus: pause only, never stop.
        window.registry.currentState = Lifecycle.State.STARTED
        assertEquals(Lifecycle.State.STARTED, host.lifecycle.currentState)
        assertEquals(listOf(Lifecycle.Event.ON_PAUSE), seen)
        window.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_RESUME), seen)
        seen.clear()

        // Minimize, then restore.
        window.registry.currentState = Lifecycle.State.CREATED
        assertEquals(Lifecycle.State.CREATED, host.lifecycle.currentState)
        window.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(Lifecycle.State.RESUMED, host.lifecycle.currentState)

        // Cmd-H while focused: the window stays RESUMED to AWT, the pages go to the background.
        host.setAppHidden(true)
        assertEquals(Lifecycle.State.CREATED, host.lifecycle.currentState)
        window.registry.currentState = Lifecycle.State.STARTED
        assertEquals(Lifecycle.State.CREATED, host.lifecycle.currentState)
        host.setAppHidden(false)
        assertEquals(Lifecycle.State.STARTED, host.lifecycle.currentState)

        host.dispose()
        assertEquals(Lifecycle.State.DESTROYED, host.lifecycle.currentState)
        window.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(Lifecycle.State.DESTROYED, host.lifecycle.currentState)
    }
}
