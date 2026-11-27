package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import build.raft.app.settings.SettingsWebViewWhileShown
import build.raft.app.settings.rememberRaftPagerAppeared
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #4828 review: the refresh-ahead loop of a signed-in WebView runs only while its page is on screen
 * (the pager's appear / disappear, [rememberRaftPagerAppeared]) and the App is in the foreground
 * (lifecycle STARTED), and starts again when both hold. Composes the wiring as
 * [SettingsWebViewAccessTokenSyncEffect] does ([SettingsWebViewWhileShown] fed by the pager's
 * visibility) on a real composition; see AGENTS.md "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsWebViewRefreshWhileShownUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** The loop's runs: how many started, and whether one runs now. */
    private class Loop {
        var starts = 0
        var running = false

        suspend fun run() {
            starts++
            running = true
            try {
                awaitCancellation()
            } finally {
                running = false
            }
        }
    }

    private fun Pager.send(event: String) = onReceivePagerEvent(event, JSONObject())

    @Test
    fun theLoopRunsOnlyWhileThePageIsOnScreenAndTheAppInTheForeground() =
        runRobolectricComposeUiTest {
            val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            val pager = object : Pager() {}
            val loop = Loop()
            setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    SettingsWebViewWhileShown(rememberRaftPagerAppeared(pager), "fence") { loop.run() }
                }
            }
            waitForIdle()
            // The page has not appeared yet: nothing runs.
            assertEquals(0 to false, loop.starts to loop.running)

            runOnIdle { pager.send(Pager.PAGER_EVENT_DID_APPEAR) }
            waitForIdle()
            assertEquals(1 to true, loop.starts to loop.running, "on screen, in the foreground")

            // The App goes to the background: cancelled; back: started again.
            runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            waitForIdle()
            assertEquals(1 to false, loop.starts to loop.running, "in the background")
            runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            waitForIdle()
            assertEquals(2 to true, loop.starts to loop.running, "back in the foreground")

            // Another page covers it: cancelled; uncovered: started again.
            runOnIdle { pager.send(Pager.PAGER_EVENT_DID_DISAPPEAR) }
            waitForIdle()
            assertEquals(2 to false, loop.starts to loop.running, "covered by another page")
            runOnIdle { pager.send(Pager.PAGER_EVENT_DID_APPEAR) }
            waitForIdle()
            assertEquals(3 to true, loop.starts to loop.running, "on screen again")

            // Covered while in the background: nothing starts on return to the foreground.
            runOnIdle {
                pager.send(Pager.PAGER_EVENT_DID_DISAPPEAR)
                owner.registry.currentState = Lifecycle.State.CREATED
            }
            waitForIdle()
            runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            waitForIdle()
            assertEquals(3 to false, loop.starts to loop.running, "still covered")
        }
}
