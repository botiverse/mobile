package build.raft.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.awt.Desktop
import java.awt.desktop.AppHiddenEvent
import java.awt.desktop.AppHiddenListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The lifecycle the pages see. Compose Desktop already drives the window's lifecycle: RESUMED while
 * focused, STARTED while visible but unfocused (the user is in another app), CREATED while
 * minimized. Pages count as shown from STARTED on desktop (`RaftPagerHost`), so only a minimized
 * window goes to the background. macOS Hide (Cmd-H) does not minimize the window, so it is added
 * here: while the app is hidden the pages see CREATED, like a minimized window.
 */
internal fun raftDesktopHostLifecycleState(windowState: Lifecycle.State, appHidden: Boolean): Lifecycle.State =
    if (appHidden && windowState.isAtLeast(Lifecycle.State.CREATED)) Lifecycle.State.CREATED else windowState

/** Follows [window]'s lifecycle, capped at CREATED while the app is hidden ([setAppHidden]). */
internal class RaftDesktopHostLifecycleOwner(private val window: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    private var appHidden = false
    private val observer = LifecycleEventObserver { _, _ -> sync() }

    override val lifecycle: Lifecycle
        get() = registry

    init {
        window.addObserver(observer)
        sync()
    }

    fun setAppHidden(hidden: Boolean) {
        if (appHidden == hidden) return
        appHidden = hidden
        sync()
    }

    fun dispose() {
        window.removeObserver(observer)
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private fun sync() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        val target = raftDesktopHostLifecycleState(window.currentState, appHidden)
        // INITIALIZED cannot go straight to DESTROYED; there is nothing to tear down yet.
        if (target == Lifecycle.State.DESTROYED && registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = target
    }
}

/** Whether macOS has the app hidden (Cmd-H / Hide Raft). Always false where the event is unsupported. */
internal object RaftDesktopAppVisibility {
    private val hiddenState = MutableStateFlow(false)
    val hidden: StateFlow<Boolean> = hiddenState.asStateFlow()

    fun install() {
        runCatching {
            if (!Desktop.isDesktopSupported()) return
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.APP_EVENT_HIDDEN)) return
            desktop.addAppEventListener(
                object : AppHiddenListener {
                    override fun appHidden(e: AppHiddenEvent?) {
                        hiddenState.value = true
                    }

                    override fun appUnhidden(e: AppHiddenEvent?) {
                        hiddenState.value = false
                    }
                }
            )
        }.onFailure { log("app hidden listener unavailable: ${it.message}") }
    }
}

/** Provides [RaftDesktopHostLifecycleOwner] over the window's lifecycle to [content]. */
@Composable
internal fun RaftDesktopHostLifecycle(content: @Composable () -> Unit) {
    val windowOwner = LocalLifecycleOwner.current
    val hostOwner = remember(windowOwner) { RaftDesktopHostLifecycleOwner(windowOwner.lifecycle) }
    DisposableEffect(hostOwner) { onDispose { hostOwner.dispose() } }
    val appHidden by RaftDesktopAppVisibility.hidden.collectAsState()
    SideEffect { hostOwner.setAppHidden(appHidden) }
    CompositionLocalProvider(LocalLifecycleOwner provides hostOwner, content = content)
}
