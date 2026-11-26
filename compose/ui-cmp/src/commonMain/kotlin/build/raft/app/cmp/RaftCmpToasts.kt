package build.raft.app.cmp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.host.HostCapabilities
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.theme.RaftTheme
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * The toast hosts on screen, one per window ([RaftCmpToastLayer]), for code outside composition:
 * the bridge `toast` (`BridgeModule.toast`, [build.raft.app.cmp.host.BridgeWireHostCapabilities])
 * and the Android host's own notices post here and fall back to the system toast only when
 * [post] returns false. Thread-safe.
 */
object RaftCmpToasts {
    private val windows = atomic(emptyList<RaftCmpToastWindow>())
    private val activations = atomic(0L)

    /**
     * Attaches a window's toast host. [RaftCmpToastLayer] does this for every window; tests and a
     * host that draws its own [state] call it directly and [RaftCmpToastWindow.detach] it.
     */
    fun attach(state: RaftToastHostState): RaftCmpToastWindow {
        val window = RaftCmpToastWindow(state)
        windows.update { it + window }
        return window
    }

    /**
     * Shows [message] as a design-system toast: on the window that renders [origin] (the
     * capabilities of the page's [build.raft.app.cmp.host.RaftCmpHost]) when that window is in
     * the foreground, else on the foreground window activated last. With [foregroundOnly] false
     * a window in the background takes it too (desktop: the app's windows are its only surface).
     *
     * False when no window can show it (the app in the background, before the first frame):
     * the caller then uses the platform toast. A blank [message] shows nothing and is handled.
     * [description], [footnote], [intent] and the action ([actionLabel] + [onAction]) are the
     * toast's ([RaftToastHostState.show]): e.g. a refusal reason in [description], the trace ID
     * line in [footnote], and its Copy action.
     */
    fun post(
        message: String,
        origin: HostCapabilities? = null,
        foregroundOnly: Boolean = true,
        durationMillis: Long? = null,
        description: String? = null,
        intent: RaftToastIntent? = null,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        footnote: String? = null,
    ): Boolean {
        if (message.isBlank()) return true
        val window = target(origin, foregroundOnly) ?: return false
        window.state.show(message, description, intent, actionLabel, onAction, durationMillis, footnote = footnote)
        return true
    }

    /** The window a toast from [origin] goes to; see [post]. */
    internal fun target(origin: HostCapabilities?, foregroundOnly: Boolean = true): RaftCmpToastWindow? {
        val all = windows.value
        if (origin != null) all.firstOrNull { it.foreground && it.renders(origin) }?.let { return it }
        return all.filter { it.foreground }.maxByOrNull { it.activation }
            ?: if (foregroundOnly) null else all.maxByOrNull { it.activation }
    }

    internal fun detach(window: RaftCmpToastWindow) {
        windows.update { it - window }
        handOff(window)
    }

    internal fun nextActivation(): Long = activations.incrementAndGet()

    /**
     * [from] left the screen (its page closed, another Activity covered it): its toasts move to
     * the foreground window, so "Saved." posted just before closing a page stays readable. With
     * no other window in the foreground (the app went to the background) they stay.
     */
    internal fun handOff(from: RaftCmpToastWindow) {
        if (from.state.visible.isEmpty()) return
        val to = windows.value.filter { it !== from && it.foreground }.maxByOrNull { it.activation } ?: return
        from.state.handOffTo(to.state)
    }
}

/** One window's toast host in [RaftCmpToasts]. */
class RaftCmpToastWindow internal constructor(val state: RaftToastHostState) {
    private val origins = atomic(emptyList<HostCapabilities>())
    private val shown = atomic(false)
    private val attached = atomic(true)
    private val activationOrder = atomic(0L)

    /** Shown (lifecycle at least STARTED) and attached. */
    val foreground: Boolean get() = attached.value && shown.value

    internal val activation: Long get() = activationOrder.value

    internal fun renders(origin: HostCapabilities): Boolean = origins.value.any { it === origin }

    /** Pages whose host has [capabilities] render in this window; the returned function undoes it. */
    fun addOrigin(capabilities: HostCapabilities): () -> Unit {
        origins.update { it + capabilities }
        return { origins.update { list -> list.filterNot { it === capabilities } } }
    }

    /**
     * The window's lifecycle: [visible] at least STARTED; [resumed] just reached RESUMED (made
     * the active window: focused, on top). Leaving the screen hands its toasts to the active one.
     */
    fun setLifecycle(visible: Boolean, resumed: Boolean = false) {
        if (resumed) activationOrder.value = RaftCmpToasts.nextActivation()
        val was = shown.getAndSet(visible)
        if (was && !visible) RaftCmpToasts.handOff(this)
    }

    fun detach() {
        if (attached.getAndSet(false)) RaftCmpToasts.detach(this)
    }
}

private val LocalRaftCmpToastWindow = compositionLocalOf<RaftCmpToastWindow?> { null }

/**
 * The window's one toast host ([RaftToastHost]) over [content], provided to it as
 * [LocalRaftToastHostState] and attached to [RaftCmpToasts] for the window's lifecycle
 * (`LocalLifecycleOwner`). Inside another layer — or when the host supplies a window-level
 * [externalWindow] (iOS's scene-level transparent overlay window) — it adds no per-page
 * [RaftToastHost]: [content] shares the outer/external window's state and registers [origin] on it.
 *
 * When [externalWindow] transitions from null to non-null after the first frame (for example on
 * iOS cold start under SwiftUI `WindowGroup`, where the scene's overlay window attaches lazily on
 * `viewWillAppear`), [content] stays at the same structural Compose slot so the page subtree
 * (`RaftPagerHost`, its `Pager`, and its host-delivered adaptive-pane state) is never torn down
 * and recreated (Hands `9e0a70ff`).
 */
@Composable
fun RaftCmpToastLayer(
    origin: HostCapabilities? = null,
    externalWindow: RaftCmpToastWindow? = null,
    content: @Composable () -> Unit,
) {
    val inherited = LocalRaftCmpToastWindow.current
    if (inherited != null && externalWindow == null) {
        if (origin != null) DisposableEffect(inherited, origin) { onDispose(inherited.addOrigin(origin)) }
        content()
        return
    }
    val fallbackWindow =
        if (externalWindow == null) {
            val state = remember { RaftToastHostState() }
            val window = remember(state) { RaftCmpToasts.attach(state) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(window, lifecycleOwner) {
                val observer =
                    LifecycleEventObserver { _, event ->
                        window.setLifecycle(
                            visible = event.targetState.isAtLeast(Lifecycle.State.STARTED),
                            resumed = event == Lifecycle.Event.ON_RESUME,
                        )
                    }
                // Replays the events up to the current state.
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            DisposableEffect(window) { onDispose { window.detach() } }
            window
        } else {
            null
        }
    val activeWindow = externalWindow ?: checkNotNull(fallbackWindow)
    if (origin != null) DisposableEffect(activeWindow, origin) { onDispose(activeWindow.addOrigin(origin)) }
    CompositionLocalProvider(
        LocalRaftCmpToastWindow provides activeWindow,
        LocalRaftToastHostState provides activeWindow.state,
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (fallbackWindow != null) {
                // Over everything the window's pages draw in-tree (dialogs and their scrims too).
                RaftTheme { RaftToastHost(fallbackWindow.state) }
            }
        }
    }
}
