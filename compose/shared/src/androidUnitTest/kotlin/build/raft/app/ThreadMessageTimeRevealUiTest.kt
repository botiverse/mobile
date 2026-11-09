package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drag the message list left to see message times (ThreadMessageTimeReveal.kt; Jasper #triage:#189,
 * artin #triage:530f8c36), on real message rows ([ThreadMessageCard]) in a LazyColumn wired as
 * ThreadMessagesListContent wires it. mdpi: a px is a dp. See AGENTS.md "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-mdpi")
class ThreadMessageTimeRevealUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-time-reveal-ui"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    private class Harness(val reveal: ThreadTimeRevealState, val list: LazyListState, val menus: MutableList<String>)

    private fun ComposeUiTest.show(
        messages: List<RaftThreadMessageItem>,
        compact: Boolean = false,
        reducedMotion: Boolean = false,
    ): Harness {
        var harness: Harness? = null
        setContent {
            val clock = remember { RaftTimeContextProvider(TimeContextForRows) }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalRaftTimeContextProvider provides clock,
            ) {
                RaftDesignSystemTheme {
                    val reveal = rememberThreadTimeRevealState(Unit, reducedMotion = reducedMotion)
                    val list = rememberLazyListState()
                    val menus = remember { mutableListOf<String>() }
                    harness = remember { Harness(reveal, list, menus) }
                    RevealList(messages, reveal, list, compact) { menus += it }
                }
            }
        }
        waitForIdle()
        return assertNotNull(harness)
    }

    private fun ComposeUiTest.revealedLabels(): Int =
        onAllNodesWithTag(ThreadTimeRevealLabelTestTag, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun draggingLeftRevealsTimesAndReleaseSpringsBack() =
        runRobolectricComposeUiTest {
            val h = show(messages(4))
            onNodeWithTag(LIST).performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(-10f, 0f)) }
            }
            waitForIdle()
            assertTrue(h.reveal.active, "a clear leftward drag claims")
            // 100dp of drag since the down: past the 64dp gutter, rubber-banded.
            val offset = h.reveal.offset.value
            assertTrue(offset > 64f && offset < 96f, "offset $offset")
            assertEquals(4, revealedLabels())
            onNodeWithTag(LIST).performTouchInput { up() }
            waitForIdle()
            assertEquals(0f, h.reveal.offset.value)
            assertFalse(h.reveal.active)
            assertEquals(0, revealedLabels())
        }

    @Test
    fun rowsTrackTheFingerFromItsDown() =
        runRobolectricComposeUiTest {
            val h = show(messages(3))
            val body = onNodeWithText("message body 1", useUnmergedTree = true)
            val restX = body.fetchSemanticsNode().positionInRoot.x
            onNodeWithTag(LIST).performTouchInput {
                down(center)
                repeat(4) { moveBy(Offset(-10f, 0f)) }
            }
            waitForIdle()
            assertTrue(h.reveal.active)
            // 40dp of drag moves the rows 40dp (the claim's distance included), not 40dp minus the claim.
            assertEquals(40f, h.reveal.offset.value, 0.5f)
            assertEquals(restX - 40f, body.fetchSemanticsNode().positionInRoot.x, 0.5f)
            onNodeWithTag(LIST).performTouchInput {
                repeat(3) { moveBy(Offset(-8f, 0f)) }
            }
            waitForIdle()
            // 64dp: the whole gutter, no rubber band yet.
            assertEquals(64f, h.reveal.offset.value, 0.5f)
            onNodeWithTag(LIST).performTouchInput { up() }
            waitForIdle()
            assertEquals(0f, h.reveal.offset.value)
        }

    @Test
    fun reducedMotionShowsTimesOnlyInContinuationGutters() =
        runRobolectricComposeUiTest {
            val h = show(messages(3), compact = true, reducedMotion = true)
            val first = onNodeWithText("message body 0", useUnmergedTree = true)
            val second = onNodeWithText("message body 1", useUnmergedTree = true)
            val firstX = first.fetchSemanticsNode().positionInRoot.x
            val secondX = second.fetchSemanticsNode().positionInRoot.x
            onNodeWithTag(LIST).performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(-10f, 0f)) }
            }
            waitForIdle()
            assertTrue(h.reveal.active)
            // Rows don't move.
            assertEquals(firstX, first.fetchSemanticsNode().positionInRoot.x)
            assertEquals(secondX, second.fetchSemanticsNode().positionInRoot.x)
            // Only the two continuation rows (09:01, 09:02); the group's first row has its header time.
            val labels = onAllNodesWithTag(ThreadTimeRevealLabelTestTag, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(
                listOf("09:01", "09:02"),
                labels.map { it.config[SemanticsProperties.ContentDescription].single() },
            )
            // In the start gutter, before the body text: no overlap.
            labels.forEach { assertTrue(it.boundsInRoot.right <= secondX, "label ${it.boundsInRoot} vs body $secondX") }
            onNodeWithTag(LIST).performTouchInput { up() }
            waitForIdle()
            assertFalse(h.reveal.active)
            assertEquals(0, revealedLabels())
        }

    @Test
    fun reducedMotionWithoutCompactRowsShowsNoExtraTimes() =
        runRobolectricComposeUiTest {
            val h = show(messages(3), compact = false, reducedMotion = true)
            onNodeWithTag(LIST).performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(-10f, 0f)) }
            }
            waitForIdle()
            assertTrue(h.reveal.active)
            // Every row is a group's first, its header shows the time.
            assertEquals(0, revealedLabels())
            onNodeWithTag(LIST).performTouchInput { up() }
        }

    @Test
    fun rightwardDragIsNeverClaimed() =
        runRobolectricComposeUiTest {
            val h = show(messages(4))
            onNodeWithTag(LIST).performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(10f, 0f)) }
            }
            waitForIdle()
            assertFalse(h.reveal.active, "toward the end is the host's back swipe")
            onNodeWithTag(LIST).performTouchInput { up() }
        }

    @Test
    fun verticalScrollIsNotHijacked() =
        runRobolectricComposeUiTest {
            val h = show(messages(40))
            val before = h.list.firstVisibleItemIndex
            onNodeWithTag(LIST).performTouchInput {
                down(center)
                // Mostly vertical with some sideways drift: still a scroll.
                repeat(15) { moveBy(Offset(-4f, -20f)) }
            }
            waitForIdle()
            assertFalse(h.reveal.active)
            onNodeWithTag(LIST).performTouchInput { up() }
            waitForIdle()
            assertTrue(h.list.firstVisibleItemIndex > before || h.list.firstVisibleItemScrollOffset > 0, "the list scrolled")
            assertFalse(h.reveal.active)
            assertEquals(0f, h.reveal.offset.value)
        }

    @Test
    fun dragStartingInAWideTableScrollsTheTableNotTheReveal() =
        runRobolectricComposeUiTest {
            val table =
                "| " + (1..12).joinToString(" | ") { "column header $it" } + " |\n" +
                    "| " + (1..12).joinToString(" | ") { "---" } + " |\n" +
                    "| " + (1..12).joinToString(" | ") { "cell value $it" } + " |"
            val h = show(listOf(message(0, body = table)) + messages(2, from = 1))
            val cell = onNodeWithText("cell value 2", substring = true, useUnmergedTree = true)
            val startX = cell.fetchSemanticsNode().positionInRoot.x
            cell.performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(-10f, 0f)) }
            }
            waitForIdle()
            assertFalse(h.reveal.active, "a drag from the table is the table's")
            assertEquals(0f, h.reveal.offset.value)
            assertTrue(cell.fetchSemanticsNode().positionInRoot.x < startX, "the table scrolled")
            cell.performTouchInput { up() }
            waitForIdle()

            // At its end it still keeps the drag: no hand-off.
            repeat(4) {
                cell.performTouchInput {
                    down(center)
                    repeat(20) { moveBy(Offset(-30f, 0f)) }
                    up()
                }
                waitForIdle()
            }
            cell.performTouchInput {
                down(center)
                repeat(10) { moveBy(Offset(-10f, 0f)) }
            }
            waitForIdle()
            assertFalse(h.reveal.active, "no hand-off at the table's end")
            cell.performTouchInput { up() }
        }

    @Test
    fun longPressWithoutMovementOpensTheMenu() =
        runRobolectricComposeUiTest {
            val h = show(messages(3))
            onNodeWithText("message body 1", useUnmergedTree = true).performTouchInput {
                down(center)
                advanceEventTime(1_000)
                up()
            }
            waitForIdle()
            assertEquals(listOf("m-1"), h.menus)
            assertFalse(h.reveal.active)
        }

    @Test
    fun aClaimedDragCancelsTheLongPress() =
        runRobolectricComposeUiTest {
            val h = show(messages(3))
            onNodeWithText("message body 1", useUnmergedTree = true).performTouchInput {
                down(center)
                repeat(6) { moveBy(Offset(-10f, 0f), delayMillis = 16) }
            }
            waitForIdle()
            assertTrue(h.reveal.active)
            onNodeWithText("message body 1", useUnmergedTree = true).performTouchInput {
                advanceEventTime(1_000)
                moveBy(Offset(-2f, 0f))
                up()
            }
            waitForIdle()
            assertEquals(emptyList(), h.menus, "no menu after the drag claimed")
            assertFalse(h.reveal.active)
        }

    @Test
    fun aDragAfterTheLongPressMenuOpenedIsNotClaimed() =
        runRobolectricComposeUiTest {
            val h = show(messages(3))
            onNodeWithText("message body 1", useUnmergedTree = true).performTouchInput {
                down(center)
                advanceEventTime(1_000)
                repeat(6) { moveBy(Offset(-10f, 0f)) }
            }
            waitForIdle()
            assertFalse(h.reveal.active)
            assertEquals(listOf("m-1"), h.menus)
            onNodeWithText("message body 1", useUnmergedTree = true).performTouchInput { up() }
        }

    @Test
    fun mouseDragIsIgnored() =
        runRobolectricComposeUiTest {
            val h = show(messages(4))
            onNodeWithTag(LIST).performMouseInput {
                moveTo(center)
                press()
                repeat(10) { moveBy(Offset(-10f, 0f)) }
            }
            waitForIdle()
            assertFalse(h.reveal.active, "a mouse keeps the hover toolbar; no reveal")
            onNodeWithTag(LIST).performMouseInput { release() }
        }

    @Test
    fun compactConsecutiveRowsCarryTheirFullTimeForScreenReaders() =
        runRobolectricComposeUiTest {
            show(messages(3), compact = true)
            // The rows after the first are compact continuation rows: no visible time, but its
            // semantics say it (the clock today, as the header would show it).
            onNodeWithContentDescription("09:01").assertExists()
            onNodeWithContentDescription("09:02").assertExists()
        }

    private companion object {
        const val LIST = "list"

        fun message(index: Int, body: String = "message body $index") =
            RaftThreadMessageItem(
                id = "m-$index",
                authorId = "user-ada",
                authorName = "Ada",
                authorKind = "human",
                avatarLabel = "A",
                seq = index.toLong() + 1,
                // 09:0x local (01:0x UTC).
                timestampLabel = "2026-10-22T01:0${index % 10}:00Z",
                body = body,
            )

        fun messages(count: Int, from: Int = 0) = (from until from + count).map { message(it) }
    }
}

@Composable
private fun RevealList(
    messages: List<RaftThreadMessageItem>,
    reveal: ThreadTimeRevealState,
    list: LazyListState,
    compact: Boolean,
    onMenu: (String) -> Unit,
) {
    LazyColumn(
        state = list,
        modifier =
            Modifier
                .fillMaxSize()
                .testTag("list")
                .threadTimeRevealGesture(reveal, isListScrolling = { list.isScrollInProgress }, selectionShowing = { false }),
    ) {
        itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
            val continuation = compact && index > 0
            ThreadTimeRevealRow(
                state = reveal,
                timeLabel = formatThreadTimeRevealLabel(message.timestampLabel, TimeContextForRows),
                continuation = continuation,
            ) {
                ThreadMessageCard(
                    message = message,
                    messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                    compactConsecutiveMessages = compact,
                    suppressRepeatedAuthor = continuation,
                    compactTimestampLabel = formatThreadCompactTimestampLabel(message.timestampLabel, TimeContextForRows),
                    onShowActions = { onMenu(message.id) },
                    onOpenMember = {},
                    onOpenThread = {},
                    onOpenTask = {},
                    onOpenTaskStatusMenu = {},
                    onToggleReaction = {},
                    onOpenAttachment = {},
                    onOpenMarkdownTag = { _, _ -> },
                    onOpenMarkdownUrl = {},
                )
            }
        }
    }
}

// 2026-10-22 in UTC+8; the messages are that morning.
private val TimeContextForRows =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 10, 22),
        nowEpochMillis = 1_792_635_000_000L,
    )
