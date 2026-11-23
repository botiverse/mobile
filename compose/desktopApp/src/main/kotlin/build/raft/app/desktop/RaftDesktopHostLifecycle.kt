package build.raft.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The window's lifecycle, as Compose Desktop's AWT window drove it: RESUMED while focused, STARTED
 * while visible but unfocused (the user is in another app), CREATED while minimized. The Nucleus
 * Tao window gives Compose no lifecycle of its own (its scenes share one that is always RESUMED),
 * so the host derives it from the window's state.
 */
internal fun raftDesktopWindowLifecycleState(focused: Boolean, minimized: Boolean): Lifecycle.State =
    when {
        minimized -> Lifecycle.State.CREATED
        focused -> Lifecycle.State.RESUMED
        else -> Lifecycle.State.STARTED
    }

/**
 * The lifecycle the pages see: the window's ([raftDesktopWindowLifecycleState]). Pages count as
 * shown from STARTED on desktop (`RaftPagerHost`), so only a minimized window goes to the
 * background. macOS Hide does not minimize the window, so it is added here: while the app is hidden
 * the pages see CREATED, like a minimized window.
 */
internal fun raftDesktopHostLifecycleState(windowState: Lifecycle.State, appHidden: Boolean): Lifecycle.State =
    if (appHidden && windowState.isAtLeast(Lifecycle.State.CREATED)) Lifecycle.State.CREATED else windowState

/** The pages' lifecycle: [update] with the window's state, capped at CREATED while the app is hidden. */
internal class RaftDesktopHostLifecycleOwner(initialWindowState: Lifecycle.State) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    private var windowState = initialWindowState
    private var appHidden = false

    override val lifecycle: Lifecycle
        get() = registry

    init {
        sync()
    }

    fun update(windowState: Lifecycle.State, appHidden: Boolean) {
        if (this.windowState == windowState && this.appHidden == appHidden) return
        this.windowState = windowState
        this.appHidden = appHidden
        sync()
    }

    fun dispose() {
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private fun sync() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        val target = raftDesktopHostLifecycleState(windowState, appHidden)
        // INITIALIZED cannot go straight to DESTROYED; there is nothing to tear down yet.
        if (target == Lifecycle.State.DESTROYED && registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = target
    }
}

/**
 * Whether macOS has the app hidden (Hide Raft). TODO(desktop, Tao): always false for now. The AWT
 * window heard it from `java.awt.Desktop`'s app events, which the Tao window must not start (AWT and
 * Tao would each set up the application object), and Nucleus 2.5.18 reports no app-hide event.
 * While hidden, the pages stay shown (STARTED) like an unfocused window.
 */
internal object RaftDesktopAppVisibility {
    private val hiddenState = MutableStateFlow(false)
    val hidden: StateFlow<Boolean> = hiddenState.asStateFlow()
}

/**
 * Provides [RaftDesktopHostLifecycleOwner] to [content], driven by the window's [focused] and
 * [minimized] state (Nucleus `DecoratedWindowState`).
 */
@Composable
internal fun RaftDesktopHostLifecycle(
    focused: Boolean,
    minimized: Boolean,
    content: @Composable () -> Unit,
) {
    val windowState = raftDesktopWindowLifecycleState(focused, minimized)
    val hostOwner = remember { RaftDesktopHostLifecycleOwner(windowState) }
    DisposableEffect(hostOwner) { onDispose { hostOwner.dispose() } }
    val appHidden by RaftDesktopAppVisibility.hidden.collectAsState()
    SideEffect { hostOwner.update(windowState, appHidden) }
    CompositionLocalProvider(LocalLifecycleOwner provides hostOwner, content = content)
}
