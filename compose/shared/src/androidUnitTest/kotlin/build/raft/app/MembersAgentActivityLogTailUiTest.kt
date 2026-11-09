package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The agent Activity raw log's tail ([MembersAgentActivityLogList]) over a real LazyColumn, as the
 * Activity tab hosts it: it opens at the newest entry, keeps the newest in view while the reader is at
 * the bottom, and keeps a reader who scrolled up exactly where they were, with the jump pill counting
 * the new steps. The log is capped (oldest entries trimmed as new ones arrive), so a trim plus an
 * append must not move the reader either.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MembersAgentActivityLogTailUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Harness {
        val rows = mutableStateListOf<String>().apply { repeat(InitialRows) { add("e$it") } }
        lateinit var list: LazyListState
    }

    private fun ComposeUiTest.host(): Harness {
        val harness = Harness()
        setContent {
            RaftDesignSystemTheme {
                harness.list = rememberLazyListState(initialFirstVisibleItemIndex = membersAgentActivityInitialFirstVisibleIndex(harness.rows.size))
                Box(Modifier.height(300.dp)) {
                    MembersAgentActivityLogList(
                        rows = harness.rows.toList(),
                        rowKey = { it },
                        resetKey = "agent-1",
                        followLatest = true,
                        contentPadding = PaddingValues(0.dp),
                        listState = harness.list,
                    ) { _, _ -> Box(Modifier.fillMaxWidth().height(100.dp)) }
                }
            }
        }
        waitForIdle()
        return harness
    }

    private fun Harness.lastVisible(): Boolean =
        list.layoutInfo.visibleItemsInfo.any { it.index == rows.lastIndex } && !list.canScrollForward

    private fun ComposeUiTest.scrollUp() {
        // A slow drag: a few rows up, no fling to the very top.
        onNodeWithTag(MembersAgentActivityLogListTag).performTouchInput {
            swipeDown(startY = top + 10f, endY = bottom - 10f, durationMillis = 1_000)
        }
        waitForIdle()
    }

    @Test
    fun opensAtTheNewestAndFollowsNewEntriesAtTheBottom() =
        runRobolectricComposeUiTest {
            val harness = host()
            assertTrue(harness.lastVisible(), "the log opens at its newest entry")

            repeat(3) { n ->
                runOnIdle { harness.rows.add("new$n") }
                waitForIdle()
                assertTrue(harness.lastVisible(), "a parked reader keeps the newest entry in view")
            }
            onNodeWithTag(MembersAgentActivityLogJumpTag).assertDoesNotExist()
        }

    @Test
    fun aCappedLogTrimmingAtTheTopStillFollowsAtTheBottom() =
        runRobolectricComposeUiTest {
            val harness = host()
            runOnIdle {
                harness.rows.removeAt(0)
                harness.rows.add("new0")
            }
            waitForIdle()
            assertEquals(InitialRows, harness.rows.size)
            assertTrue(harness.lastVisible(), "the count stays the same, yet the newest entry is followed")
        }

    @Test
    fun scrolledUpKeepsItsPlaceAndThePillCountsNewSteps() =
        runRobolectricComposeUiTest {
            val harness = host()
            scrollUp()
            val index = harness.list.firstVisibleItemIndex
            val offset = harness.list.firstVisibleItemScrollOffset
            assertTrue(harness.list.canScrollForward, "the reader left the bottom")
            onNodeWithText("Back to Bottom").assertExists()

            runOnIdle { harness.rows.add("new0") }
            waitForIdle()
            runOnIdle { harness.rows.add("new1") }
            waitForIdle()
            assertEquals(index, harness.list.firstVisibleItemIndex)
            assertEquals(offset, harness.list.firstVisibleItemScrollOffset)
            onNodeWithText("2 new steps").assertExists()

            onNodeWithTag(MembersAgentActivityLogJumpTag).performClick()
            waitForIdle()
            assertTrue(harness.lastVisible(), "the pill returns to the newest entry")
            onNodeWithTag(MembersAgentActivityLogJumpTag).assertDoesNotExist()
        }

    @Test
    fun scrolledUpKeepsTheSameRowWhenTheCappedLogTrims() =
        runRobolectricComposeUiTest {
            val harness = host()
            scrollUp()
            val firstKey = harness.list.layoutInfo.visibleItemsInfo.first().key
            val offset = harness.list.firstVisibleItemScrollOffset

            runOnIdle {
                harness.rows.removeAt(0)
                harness.rows.add("new0")
            }
            waitForIdle()
            assertEquals(firstKey, harness.list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(offset, harness.list.firstVisibleItemScrollOffset)
            assertFalse(harness.lastVisible())
            onNodeWithText("1 new step").assertExists()
        }

    private companion object {
        const val InitialRows = 40
    }
}
