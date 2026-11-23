package build.raft.app.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * `Dispatchers.Main` on the AWT event thread for a Compose UI test (`runComposeUiTest`), which
 * composes there. On the app's classpath `Dispatchers.Main` is Nucleus Tao's (decorated-window-tao's
 * main-dispatcher factory outranks kotlinx-coroutines-swing's); a test JVM runs no Tao loop, so it is
 * Nucleus's fallback thread. AndroidX Lifecycle takes `Dispatchers.Main`'s thread as the main thread,
 * so the navigation entries' lifecycles that NavDisplay moves while composing on the AWT thread
 * would fail ("setCurrentState must be called on the main thread"). The app itself composes on the
 * Tao thread, which is its `Dispatchers.Main`.
 *
 * Call [install] before each such test and [reset] after it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object RaftDesktopComposeUiTestMain {
    fun install() = Dispatchers.setMain(Dispatchers.Swing)

    fun reset() = Dispatchers.resetMain()
}
