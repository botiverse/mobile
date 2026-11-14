package build.raft.designsystem

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftTaskCompactAssignee
import build.raft.designsystem.components.RaftTaskCompactList
import build.raft.designsystem.components.RaftTaskCompactRow
import build.raft.designsystem.components.RaftTaskStatus
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Crash c41fd092: a task under a message ([RaftTaskCompactRow] in the thread's `LazyColumn`).
 * Reading the right group's (assignee and status) first baseline while measuring the row made
 * Compose 1.12 lay out the avatar of a reused, not yet placed row, and RectManager threw
 * "LayoutNode not found in RectList" (fixed upstream in Compose 1.13, androidx fd550bed79).
 */
@OptIn(ExperimentalTestApi::class)
class RaftTaskCompactRowReuseTest {
    /**
     * The cause, deterministically: a row must lay out its assignee's avatar only when it is
     * placed, never while it is being measured.
     */
    @Test
    fun rowPlacesItsAssigneeOnlyAfterMeasuring() =
        runComposeUiTest {
            var measuring = false
            var placedWhileMeasuring = false
            val probe =
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        if (measuring) placedWhileMeasuring = true
                        placeable.place(0, 0)
                    }
                }
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Layout(
                        content = {
                            RaftTaskCompactList(Modifier.width(480.dp)) {
                                RaftTaskCompactRow(
                                    number = "#12",
                                    title = "Ship the preview build",
                                    onClick = {},
                                    status = RaftTaskStatus.InProgress,
                                    statusText = "In Progress",
                                    assignee = {
                                        RaftTaskCompactAssignee(name = "Lagoon", avatar = {
                                            RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xs, probe, initials = "L", presence = RaftPresence.Busy)
                                        })
                                    },
                                    replyCount = 8,
                                )
                            }
                        },
                    ) { measurables, constraints ->
                        measuring = true
                        val placeable = measurables.single().measure(constraints)
                        measuring = false
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                }
            }
            waitForIdle()

            onNodeWithText("Lagoon").assertExists()
            assertFalse(placedWhileMeasuring, "the assignee's avatar was laid out while the row was measuring")
        }

    /**
     * The shape of the androidx fd550bed79 regression test with task rows in place of its
     * baseline-querying layout: a `LazyColumn` two items tall, scrolled so item 4 reuses item 0's
     * slot. A regression guard (the deterministic proof is the test above).
     */
    @Test
    fun rowsInALazyColumnSurviveItemReuse() =
        runComposeUiTest {
            val itemDp = 80.dp
            val itemPx = with(density) { itemDp.roundToPx() }
            val lazyListState = LazyListState()
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    LazyColumn(state = lazyListState, modifier = Modifier.width(480.dp).height(itemDp * 2)) {
                        items(10) { index ->
                            RaftTaskCompactList(Modifier.height(itemDp)) {
                                RaftTaskCompactRow(
                                    number = "#$index",
                                    title = "Task $index",
                                    onClick = {},
                                    status = if (index % 2 == 0) RaftTaskStatus.InReview else RaftTaskStatus.Todo,
                                    statusText = if (index % 2 == 0) "In Review" else "Todo",
                                    assignee = {
                                        RaftTaskCompactAssignee(name = "Agent $index", avatar = {
                                            RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xs, initials = "A")
                                        })
                                    },
                                    replyCount = index,
                                )
                            }
                        }
                    }
                }
            }

            // Item 0 scrolls off-screen, but is still measured to advance the first visible index.
            runOnIdle { lazyListState.dispatchRawDelta(itemPx.toFloat()) }
            // Item 0 is no longer composed and its slot is moved to the reuse pool.
            runOnIdle { lazyListState.dispatchRawDelta(itemPx.toFloat()) }
            // Item 4 reuses the slot of item 0 and lays out its row.
            runOnIdle { lazyListState.dispatchRawDelta(itemPx.toFloat()) }
            waitForIdle()

            onNodeWithText("Task 4").assertExists()
        }
}
