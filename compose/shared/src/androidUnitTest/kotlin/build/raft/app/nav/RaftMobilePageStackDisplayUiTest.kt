package build.raft.app.nav

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.navigation3.runtime.NavEntry
import kotlinx.serialization.json.JsonPrimitive
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
import build.raft.app.compat.ui.LocalRaftPageActive
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
 * off. Batch 3 (the chat): the composer's Back before the page's, a covered chat keeps its
 * viewport size and gives up the keyboard. AGENTS.md "Compose UI tests".
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
    private fun Host(stack: RaftPageStack, probe: Probe, pageExtra: @Composable () -> Unit = {}) {
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
                pageExtra()
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

    // Step 2 batch 3: the chat on the stack ----------------------------------------------------------

    @Test
    fun aChatsBackGoesToTheComposerThenThePageThenPops() =
        runRobolectricComposeUiTest {
            val stack = RaftPageStack()
            val probe = Probe()
            var composerOn by mutableStateOf(true)
            var composerBacks = 0
            setContent {
                Host(stack, probe) {
                    // The composer (reply / edit mode, attachment tray) registers inside the page,
                    // after the page's own handler: it goes first.
                    BackHandler(enabled = composerOn) { composerBacks++ }
                }
            }
            runOnIdle { stack.open(CHAT, JsonObject(emptyMap()), from = stack.pages.first()) }
            waitForIdle()

            runOnIdle { probe.dispatcher.onBackPressed() }
            waitForIdle()
            assertEquals(1, composerBacks, "the composer's handler first")
            assertEquals(0, probe.pageBacks)

            runOnIdle { composerOn = false }
            runOnIdle { probe.dispatcher.onBackPressed() }
            waitForIdle()
            assertEquals(1, probe.pageBacks, "then the page's own")
            assertEquals(2, stack.pages.size)

            runOnIdle { probe.pageHandlerOn = false }
            runOnIdle { probe.dispatcher.onBackPressed() }
            waitForIdle()
            assertEquals(1, stack.pages.size, "then the stack pops the chat")
            assertEquals(0, probe.shellBacks)
            assertEquals(0, probe.hostBacks)
        }

    @Test
    fun aCoveredChatKeepsTheWindowSizeAndOtherPagesCollapse() =
        runRobolectricComposeUiTest {
            val stack = RaftPageStack()
            val sizes = mutableMapOf<String, IntSize>()
            setContent {
                RaftMobilePageStackDisplay(stack) { page ->
                    Box(Modifier.fillMaxSize().onSizeChanged { sizes[page.contentKey] = it })
                }
            }
            runOnIdle { stack.open(CHAT, JsonObject(emptyMap()), from = stack.pages.first()) }
            waitForIdle()
            val chat = stack.pages.last()
            val window = sizes.getValue(chat.contentKey)
            assertTrue(window.width > 0 && window.height > 0, "chat on top: $window")
            assertEquals(IntSize.Zero, sizes.getValue(stack.pages.first().contentKey), "covered shell collapses as before")

            runOnIdle { stack.open("compose_member_detail", JsonObject(emptyMap()), from = chat) }
            waitForIdle()
            val profile = stack.pages.last()
            assertEquals(window, sizes.getValue(profile.contentKey))
            assertEquals(window, sizes.getValue(chat.contentKey), "covered chat keeps its viewport size")

            runOnIdle { stack.back() }
            waitForIdle()
            assertEquals(window, sizes.getValue(chat.contentKey))
        }

    @Test
    fun aCoveredPageGivesUpTextFocusAndANewPageKeepsItsOwn() =
        runRobolectricComposeUiTest {
            val stack = RaftPageStack()
            val focused = mutableMapOf<String, Boolean>()
            val requesters = mutableMapOf<String, FocusRequester>()
            setContent {
                RaftMobilePageStackDisplay(stack) { page ->
                    val requester = remember { FocusRequester() }
                    requesters[page.contentKey] = requester
                    var text by remember { mutableStateOf("") }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.focusRequester(requester).onFocusChanged { focused[page.contentKey] = it.isFocused },
                    )
                    // Search focuses its field when it opens.
                    if (page.args.pageName == SEARCH) LaunchedEffect(Unit) { requester.requestFocus() }
                }
            }
            runOnIdle { stack.open(CHAT, JsonObject(emptyMap()), from = stack.pages.first()) }
            waitForIdle()
            val chat = stack.pages.last()
            runOnIdle { requesters.getValue(chat.contentKey).requestFocus() }
            waitForIdle()
            assertEquals(true, focused[chat.contentKey], "the composer has the keyboard")

            runOnIdle { stack.open("compose_member_detail", JsonObject(emptyMap()), from = chat) }
            waitForIdle()
            assertEquals(false, focused[chat.contentKey], "covered, the composer lets go of the keyboard")

            runOnIdle { stack.back() }
            runOnIdle { stack.open(SEARCH, JsonObject(emptyMap()), from = chat) }
            waitForIdle()
            assertEquals(true, focused[stack.pages.last().contentKey], "a page that focuses on open keeps it")
            assertEquals(false, focused[chat.contentKey])
        }

    // nav_transition_frames: what the display reports per transition ------------------------------

    private class Transitions : RaftStackTransitionListener {
        val events = mutableListOf<String>()

        override fun onTransitionStarted(kind: RaftStackTransitionKind, fromPage: String, toPage: String) {
            events += "${kind.wire} $fromPage>$toPage"
        }

        override fun onTransitionEnded(outcome: RaftStackTransitionOutcome) {
            events += outcome.wire
        }
    }

    @Test
    fun pushPopAndPredictiveBackEachReportOneTransition() =
        runRobolectricComposeUiTest {
            val stack = RaftPageStack()
            val transitions = Transitions()
            lateinit var dispatcher: OnBackPressedDispatcher
            setContent {
                dispatcher = LocalContext.current.componentActivity().onBackPressedDispatcher
                RaftMobilePageStackDisplay(stack, transitions) { page -> Box(Modifier.fillMaxSize().testTag(page.contentKey)) }
            }
            waitForIdle()
            runOnIdle { stack.open("about", JsonObject(emptyMap()), from = stack.pages.first()) }
            waitForIdle()
            runOnIdle { stack.back() }
            waitForIdle()
            assertEquals(
                listOf("push $RAFT_SHELL>about", "completed", "pop about>$RAFT_SHELL", "completed"),
                transitions.events,
            )

            runOnIdle { stack.open("about", JsonObject(emptyMap()), from = stack.pages.first()) }
            waitForIdle()
            transitions.events.clear()
            runOnIdle {
                dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 500f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 500f, 0.4f, BackEventCompat.EDGE_LEFT))
            }
            waitForIdle()
            runOnIdle { dispatcher.dispatchOnBackCancelled() }
            waitForIdle()
            assertEquals(2, stack.pages.size, "a cancelled gesture keeps the page")
            assertEquals(listOf("predictive_back about>$RAFT_SHELL", "cancelled"), transitions.events)

            transitions.events.clear()
            runOnIdle {
                dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 500f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 500f, 0.6f, BackEventCompat.EDGE_LEFT))
            }
            waitForIdle()
            runOnIdle { dispatcher.onBackPressed() }
            waitForIdle()
            assertEquals(1, stack.pages.size, "a released gesture pops the page")
            assertEquals(listOf("predictive_back about>$RAFT_SHELL", "completed"), transitions.events)
        }

    @Test
    fun aPushedPageBecomesTheActivePageOnlyOnceItsSlideSettled() =
        runRobolectricComposeUiTest {
            val stack = RaftPageStack()
            val activity = mutableMapOf<String, MutableList<Boolean>>()
            setContent {
                RaftMobilePageStackDisplay(stack) { page ->
                    val active = LocalRaftPageActive.current
                    val seen = activity.getOrPut(page.contentKey) { mutableListOf() }
                    if (seen.lastOrNull() != active) seen += active
                    Box(Modifier.fillMaxSize().testTag(page.contentKey))
                }
            }
            runOnIdle { stack.open(CHAT, JsonObject(emptyMap()), from = stack.pages.first()) }
            waitForIdle()
            val chat = stack.pages.last().contentKey
            onNodeWithTag(chat).assertExists()
            // Built and drawn inactive while it slides (no viewDidAppear work mid slide), then active.
            assertEquals<List<Boolean>?>(listOf(false, true), activity[chat])
            assertEquals<List<Boolean>?>(listOf(true, false), activity[stack.pages.first().contentKey], "the covered shell is inactive")
        }

    @Test
    fun entryMetadataMarksTheChatAndAnOpenWithoutThePush() {
        val stack = RaftPageStack()
        stack.open(CHAT, JsonObject(mapOf("slockOpenWithoutAnimation" to JsonPrimitive(true))), from = stack.pages.first())
        stack.open("compose_settings_native_detail", JsonObject(emptyMap()), from = stack.pages.first())
        val (chat, settings) = stack.pages.drop(1).map { page -> NavEntry(page, page.contentKey, raftMobileStackEntryMetadata(page)) {} }
        assertTrue(chat.raftStackCoveredKeepsSize())
        assertTrue(chat.raftStackOpensWithoutMotion(), "opened without the push: no slide-in")
        assertFalse(settings.raftStackCoveredKeepsSize())
        assertFalse(settings.raftStackOpensWithoutMotion())
    }

    private fun Context.componentActivity(): ComponentActivity {
        var context: Context = this
        while (context !is ComponentActivity) context = (context as ContextWrapper).baseContext
        return context
    }

    private companion object {
        const val SHELL = "shell"
        const val PAGE = "page"
        const val CHAT = "compose_thread"
        const val SEARCH = "compose_home_search"
        const val RAFT_SHELL = "compose_slock_app_shell"
    }
}
