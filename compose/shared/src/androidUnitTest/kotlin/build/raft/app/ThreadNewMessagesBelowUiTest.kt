package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "N new messages" count on the Back to Bottom pill, wired as ThreadMessagesListContent wires
 * it: the pill's viewport signals ([rememberThreadBackToBottomViewportSignals]), its visibility
 * rule ([shouldShowThreadBackToBottomButton]) and [ThreadNewMessagesBelowEffect], over a real
 * LazyColumn whose last row is the bottom padding row (ThreadPage's `latestItemIndex`).
 *
 * In the composition where a message arrives, the layout has not measured the new row yet, so the
 * pill reads hidden for that frame. Without the `latestItemLaidOut` guard that frame cleared the
 * count (the control case below), and the pill came back as plain "Back to Bottom".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadNewMessagesBelowUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Harness {
        val messages = mutableStateListOf<String>().apply { repeat(InitialMessages) { add("m$it") } }
        val state = mutableStateOf(ThreadNewMessagesBelow())
        var away = false
        lateinit var raw: LazyListState
        lateinit var scope: CoroutineScope
    }

    private fun ComposeUiTest.host(guardStaleLayout: Boolean): Harness {
        val harness = Harness()
        setContent {
            harness.raw = rememberLazyListState()
            harness.scope = rememberCoroutineScope()
            val viewport = remember(harness.raw) { ThreadListViewport(harness.raw, reverse = false) }
            // Messages, then the bottom padding row: latestItemIndex is that padding row. The count
            // is read here in composition, as ThreadPage's `lazyItemCount` is, so the list sees a
            // new message only after this scope recomposes (a live read in the LazyColumn content
            // would let the lazy layout measure it first).
            val messageCount = harness.messages.size
            val latestItemIndex = messageCount
            Box(Modifier.height(300.dp)) {
                LazyColumn(state = harness.raw) {
                    items(messageCount + 1) { Box(Modifier.fillMaxWidth().height(100.dp)) }
                }
            }
            val signals by rememberThreadBackToBottomViewportSignals(viewport, latestItemIndex)
            val away =
                shouldShowThreadBackToBottomButton(
                    ThreadBackToBottomButtonState(
                        initialBottomAnchorReady = true,
                        pendingKeyboardBottomAnchor = false,
                        canScrollTowardBottom = signals.canScrollTowardBottom,
                        latestItemOffBottom = signals.latestItemOffBottom,
                    )
                )
            harness.away = away
            ThreadNewMessagesBelowEffect(
                instanceKey = "thread",
                tailSignature = harness.messages.size to harness.messages.lastOrNull(),
                awayFromBottom = away,
                canLoadMoreAfter = false,
                viewportMeasured = if (guardStaleLayout) signals.latestItemLaidOut else true,
                current = { harness.state.value },
                onChange = { harness.state.value = it },
                arrivals = { harness.messages.map { ThreadJumpArrival(messageId = it, countable = true) } },
            )
        }
        waitForIdle()
        return harness
    }

    private fun ComposeUiTest.arrive(harness: Harness, vararg ids: String) {
        ids.forEach { id ->
            runOnIdle { harness.messages.add(id) }
            waitForIdle()
        }
    }

    @Test
    fun arrivalsWhileScrolledUpCountAndReachingTheBottomClears() =
        runRobolectricComposeUiTest {
            val harness = host(guardStaleLayout = true)
            // At the top of 40 rows: scrolled up, nothing new yet ("Back to Bottom").
            assertEquals(true, harness.away)
            assertEquals(0, harness.state.value.count)

            arrive(harness, "n1")
            assertEquals(1, harness.state.value.count)
            arrive(harness, "n2", "n3")
            assertEquals(3, harness.state.value.count)
            assertEquals(3, threadBottomJumpCount(canLoadMoreAfter = false, conversationUnreadCount = 0, newMessagesBelow = harness.state.value.count))

            runOnIdle { harness.scope.launch { harness.raw.scrollToItem(harness.messages.size) } }
            waitForIdle()
            assertEquals(false, harness.away)
            assertEquals(0, harness.state.value.count)
        }

    @Test
    fun controlWithoutTheGuardTheArrivalFrameClearsTheCount() =
        runRobolectricComposeUiTest {
            val harness = host(guardStaleLayout = false)
            assertEquals(true, harness.away)

            arrive(harness, "n1", "n2")
            assertEquals(true, harness.away)
            assertEquals(0, harness.state.value.count, "the stale arrival frame should have eaten the count without the guard")
        }

    private companion object {
        const val InitialMessages = 40
    }
}
