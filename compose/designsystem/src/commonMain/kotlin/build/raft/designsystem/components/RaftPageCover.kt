package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * "This page is about to be covered by another one", one per page.
 *
 * A popup or dialog is a window layer of its own (iOS: a layer in the host's `UIWindow`), not
 * part of the page's view: once a pushed page covers its page, the covered page stops composing,
 * so an overlay that closed on the page's lifecycle (`pageActive`) closed late or never and stayed
 * over the new page, taking every tap. The page root (`RaftCmpRoot`) owns one signal per page,
 * provides it as [LocalRaftPageCoverSignal] and fires it synchronously from the page host's
 * `openPage`, before the push, while the page still composes; on iOS the page's container fires
 * it again as it disappears (a push the native side made itself). Overlays subscribe with
 * [RaftDismissOnPageCover].
 *
 * Main thread only. Firing twice is harmless: listeners only ask their overlay to close.
 */
class RaftPageCoverSignal {
    private val listeners = LinkedHashSet<Listener>()

    private class Listener(val onCover: () -> Unit)

    /** Adds [listener]; it runs on every [fire] until the returned registration is unregistered. */
    fun register(listener: () -> Unit): RaftPageCoverRegistration {
        val entry = Listener(listener)
        listeners.add(entry)
        return RaftPageCoverRegistration { listeners.remove(entry) }
    }

    /** Runs every current listener (a snapshot: a listener may unregister re-entrantly). */
    fun fire() {
        listeners.toList().forEach { it.onCover() }
    }

    /** Listeners currently registered (tests). */
    val listenerCount: Int get() = listeners.size
}

/** Returned by [RaftPageCoverSignal.register]. */
fun interface RaftPageCoverRegistration {
    fun unregister()
}

/** The page's [RaftPageCoverSignal]; null outside a page root (previews, fixtures): nothing fires. */
val LocalRaftPageCoverSignal = staticCompositionLocalOf<RaftPageCoverSignal?> { null }

/**
 * While composed, calls the latest [onDismissRequest] when the page it is on is about to be
 * covered by a pushed page ([RaftPageCoverSignal]). Put it in every window-layer overlay (dialogs,
 * menus, popovers, tooltips) beside [build.raft.designsystem.gesture.RaftBlockBackSwipe] and
 * [build.raft.designsystem.focus.RaftAutofocusGuard.HoldOpenSurface], composed only while the
 * overlay is shown.
 */
@Composable
fun RaftDismissOnPageCover(onDismissRequest: () -> Unit) {
    val signal = LocalRaftPageCoverSignal.current ?: return
    val dismiss by rememberUpdatedState(onDismissRequest)
    DisposableEffect(signal) {
        val registration = signal.register { dismiss() }
        onDispose { registration.unregister() }
    }
}
