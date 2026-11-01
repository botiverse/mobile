package build.raft.app.nav

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.RobolectricComposeActivityRule
import build.raft.app.compat.ui.BackHandler
import build.raft.app.runRobolectricComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Navigation 3 step 1 on a real composition: Back goes to the top page's own handler first, then
 * pops the stack, then (root page alone) to the root page's handlers and the host's fallback (the
 * Android shell's move-to-background). A covered page is paused: lifecycle CREATED, back handlers
 * off. AGENTS.md "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftMobilePageStackDisplayUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Probe {
        var pageHandlerOn by mutableStateOf(true)
        var shellHandlerOn by mutableStateOf(true)
        var pageBacks = 0
        var shellBacks = 0
        var hostBacks = 0
        var shellLifecycle: Lifecycle? = null
        var pageLifecycle: Lifecycle? = null
        lateinit var dispatcher: OnBackPressedDispatcher
    }

    @Composable
    private fun Host(stack: RaftPageStack, probe: Probe) {
        val activity = LocalContext.current.componentActivity()
        // The host's fallback, registered before the stack (RaftCmpActivity.hostSystemBackCallback).
        remember {
            probe.dispatcher = activity.onBackPressedDispatcher
            activity.onBackPressedDispatcher.addCallback(
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        probe.hostBacks++
                    }
                }
            )
        }
        RaftMobilePageStackDisplay(stack) { page ->
            if (page === stack.pages.first()) {
                probe.shellLifecycle = LocalLifecycleOwner.current.lifecycle
                BackHandler(enabled = probe.shellHandlerOn) { probe.shellBacks++ }
                Box(Modifier.testTag(SHELL))
            } else {
                probe.pageLifecycle = LocalLifecycleOwner.current.lifecycle
                BackHandler(enabled = probe.pageHandlerOn) { probe.pageBacks++ }
                Box(Modifier.testTag(PAGE))
            }
        }
    }

    @Test
    fun backRunsThePageHandlerThenPopsThenReachesTheRootAndHost() =
        runRobolectricComposeUiTest {
            val stack = RaftPageStack()
            val probe = Probe()
            setContent { Host(stack, probe) }
            runOnIdle { stack.open("about", JsonObject(emptyMap()), from = stack.pages.first()) }
            onNodeWithTag(PAGE).assertExists()

            runOnIdle { probe.dispatcher.onBackPressed() }
            waitForIdle()
            assertEquals(1, probe.pageBacks, "the top page's own handler goes first")
            assertEquals(2, stack.pages.size)
            assertEquals(0, probe.shellBacks, "the covered shell's handler took Back")

            runOnIdle { probe.pageHandlerOn = false }
            runOnIdle { probe.dispatcher.onBackPressed() }
            waitForIdle()
            assertEquals(1, stack.pages.size, "with the page handler off Back pops the stack")
            assertEquals(0, probe.shellBacks)
            assertEquals(0, probe.hostBacks)

            runOnIdle { probe.dispatcher.onBackPressed() }
            waitForIdle()
            assertEquals(1, probe.shellBacks, "root page alone: its own handler")

            runOnIdle { probe.shellHandlerOn = false }
            runOnIdle { probe.dispatcher.onBackPressed() }
            waitForIdle()
            assertEquals(1, probe.hostBacks, "root page alone, no handler: the host's fallback")
            assertEquals(1, stack.pages.size, "the root page never closes")
        }

    @Test
    fun aCoveredPageIsStoppedAndResumesOnTopAgain() =
        runRobolectricComposeUiTest {
            val stack = RaftPageStack()
            val probe = Probe()
            setContent { Host(stack, probe) }
            waitForIdle()
            assertTrue(probe.shellLifecycle!!.currentState.isAtLeast(Lifecycle.State.STARTED))

            runOnIdle { stack.open("about", JsonObject(emptyMap()), from = stack.pages.first()) }
            waitForIdle()
            assertFalse(
                probe.shellLifecycle!!.currentState.isAtLeast(Lifecycle.State.STARTED),
                "covered shell still ${probe.shellLifecycle!!.currentState}",
            )
            assertTrue(probe.pageLifecycle!!.currentState.isAtLeast(Lifecycle.State.STARTED))
            onNodeWithTag(SHELL).assertExists() // kept alive under the page

            runOnIdle { stack.back() }
            waitForIdle()
            onNodeWithTag(PAGE).assertDoesNotExist()
            assertTrue(probe.shellLifecycle!!.currentState.isAtLeast(Lifecycle.State.STARTED))
        }

    private fun Context.componentActivity(): ComponentActivity {
        var context: Context = this
        while (context !is ComponentActivity) context = (context as ContextWrapper).baseContext
        return context
    }

    private companion object {
        const val SHELL = "shell"
        const val PAGE = "page"
    }
}
