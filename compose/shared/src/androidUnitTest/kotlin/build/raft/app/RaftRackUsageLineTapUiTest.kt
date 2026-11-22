package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftStorePullToRefreshBox
import build.raft.designsystem.components.RaftPreviewCardState
import build.raft.designsystem.components.raftClickable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * artin (Huawei Android, 2026-11-19): on the Computers page, scrolling up and down fast kept
 * opening a runtime line's usage card. The line is the usage card's button
 * (RaftRackUsagePreviewCard → ComputerRuntimeUsagePreviewCard, the line's `raftClickable`),
 * composed here as the page does, in a LazyColumn under the pull-to-refresh box: a swipe that
 * starts on a line, or a finger that stops a fling over one, opens nothing (no card, no sheet); a
 * plain tap opens the line's usage sheet (the page's `onTap`), never the card.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class RaftRackUsageLineTapUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val states = List(LineCount) { RaftPreviewCardState() }
    private val sheets = mutableListOf<Int>()
    private lateinit var list: LazyListState

    private fun ComposeUiTest.showRack() {
        setContent {
            list = rememberLazyListState()
            RaftDesignSystemTheme {
                RaftStorePullToRefreshBox(refreshing = false, onRefresh = {}, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize().testTag(ListTag), state = list) {
                        items(states.indices.toList()) { index ->
                            ComputerRuntimeUsagePreviewCard(
                                runtimeLabel = "Claude $index",
                                controller = null,
                                runtimeVersion = null,
                                onTap = { sheets += index },
                                state = states[index],
                            ) { onClick ->
                                Box(Modifier.fillMaxWidth().height(56.dp).testTag(lineTag(index)).raftClickable(onClick = { onClick?.invoke() })) {
                                    BasicText("Claude $index")
                                }
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
    }

    private fun openLines(): List<Int> = states.indices.filter { states[it].isOpen || states[it].isPinned }

    @Test
    fun aFastSwipeStartingOnALineOpensNoCard() = runRobolectricComposeUiTest {
        showRack()

        repeat(3) {
            onNodeWithTag(ListTag).performTouchInput {
                // Up then down, fast (a flick, well under the long-press timeout), starting on a line.
                swipe(start = Offset(centerX, height * 0.75f), end = Offset(centerX, height * 0.25f), durationMillis = 120)
            }
            mainClock.advanceTimeBy(2_000)
            onNodeWithTag(ListTag).performTouchInput {
                swipe(start = Offset(centerX, height * 0.3f), end = Offset(centerX, height * 0.6f), durationMillis = 120)
            }
            mainClock.advanceTimeBy(2_000)
        }

        assertTrue(list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0, "the swipes scrolled the rack")
        assertEquals(emptyList(), openLines(), "no line's card opened")
        assertEquals(emptyList(), sheets, "no line's sheet opened")
    }

    @Test
    fun aTapThatStopsAFlingOpensNoCard() = runRobolectricComposeUiTest {
        showRack()
        mainClock.autoAdvance = false

        onNodeWithTag(ListTag).performTouchInput {
            swipe(start = Offset(centerX, height * 0.85f), end = Offset(centerX, height * 0.15f), durationMillis = 80)
        }
        // The list is still flinging: a finger comes down on whichever line is under it to stop it.
        mainClock.advanceTimeBy(48)
        assertTrue(list.isScrollInProgress, "the rack is still flinging")
        onNodeWithTag(ListTag).performTouchInput { click(center) }
        mainClock.autoAdvance = true
        mainClock.advanceTimeBy(2_000)

        assertEquals(emptyList(), openLines(), "the tap that stopped the fling opened no card")
        assertEquals(emptyList(), sheets, "nor a sheet")

        // Once the list is at rest, a tap is a tap again.
        val visible = list.firstVisibleItemIndex + 1
        onNodeWithTag(lineTag(visible)).performTouchInput { click() }
        mainClock.advanceTimeBy(1_000)
        assertEquals(listOf(visible), sheets)
        assertEquals(emptyList(), openLines())
    }

    private companion object {
        const val LineCount = 40
        const val ListTag = "rack-list"

        fun lineTag(index: Int) = "rack-line-$index"
    }
}
