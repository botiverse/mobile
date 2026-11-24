package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * artin (#proj-mobile #201): saving a message made the thread jump. The thread list is reversed, so
 * any visible row that changes height in place (Saved marker, first reaction, a card or image
 * resolving late) moved every row above it. Drives the production [ThreadReadingAnchorOwner] over a
 * reversed LazyColumn wired as ThreadMessagesList wires it (position callbacks of the list and its
 * rows), changes rows in place, and reads the rows' tops right after the frame that lays the change
 * out: the reading anchor (or the tapped row) stays put, while following the latest row the bottom
 * stays pinned as before, and a scroll by another owner is never undone.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ThreadReadingAnchorUiTest {
    @get:Rule val activities = RobolectricComposeActivityRule()

    private val ids = (0 until 12).map { "m$it" }

    private class Harness(
        /** Read in the parent composition, like the render payload (the list's items change). */
        val saved: MutableState<Map<String, Boolean>>,
        val reactions: MutableState<Map<String, Boolean>>,
        /** Read inside the row only, like an image or link card resolving (only the row changes). */
        val lateCardDp: SnapshotStateMap<String, Int>,
        val followingLatest: MutableState<Boolean>,
    ) {
        /** The thread's messages, oldest first; a new message is appended. */
        val messages = mutableStateOf(List(12) { "m$it" })
        /** The list's height; a smaller one is the keyboard opening. Null fills the window. */
        val listHeightDp = mutableStateOf<Int?>(null)
        /** An author popover, closed by the user's own scroll only ([ThreadUserScrollEffect]). */
        val popoverOpen = mutableStateOf(false)
        lateinit var viewport: ThreadListViewport
        lateinit var owner: ThreadReadingAnchorOwner
        lateinit var scope: CoroutineScope
        var markerPx = 0
    }

    private fun ComposeUiTest.mount(): Harness {
        val harness =
            Harness(
                saved = mutableStateOf(emptyMap()),
                reactions = mutableStateOf(emptyMap()),
                lateCardDp = mutableStateMapOf(),
                followingLatest = mutableStateOf(false),
            )
        setContent {
            harness.markerPx = with(LocalDensity.current) { 30.dp.roundToPx() }
            // Scrolled into the history: m8 is the bottom row, its end a little below the viewport.
            val raw =
                rememberLazyListState(
                    initialFirstVisibleItemIndex = reversedThreadListIndex(8, ids.size),
                    initialFirstVisibleItemScrollOffset = 40,
                )
            val viewport = remember(raw) { ThreadListViewport(raw, reverse = true) }
            harness.viewport = viewport
            harness.scope = rememberCoroutineScope()
            val owner = remember(viewport) { ThreadReadingAnchorOwner(viewport, harness.scope) }
            harness.owner = owner
            val afterLayout: () -> Unit = {
                owner.afterLayout(
                    owns = {
                        threadReadingAnchorOwns(
                            reverseLayout = true,
                            followingLatest = harness.followingLatest.value,
                            scrollInProgress = viewport.isScrollInProgress,
                            ownerLive = !viewport.isRetired,
                            placementSettled = true,
                        )
                    },
                    preferredMessageId = { null },
                    messageIdAt = { index -> harness.messages.value.getOrNull(index) },
                )
            }
            ThreadUserScrollEffect(raw) { harness.popoverOpen.value = false }
            val savedNow = harness.saved.value
            val reactionsNow = harness.reactions.value
            val messagesNow = harness.messages.value
            val listHeight = harness.listHeightDp.value
            LazyColumn(
                state = raw,
                reverseLayout = true,
                modifier =
                    (if (listHeight == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(listHeight.dp))
                        .testTag(ListTag)
                        .onGloballyPositioned { afterLayout() },
            ) {
                items(messagesNow.asReversed(), key = { it }) { id ->
                    Column(Modifier.fillMaxWidth().onGloballyPositioned { afterLayout() }) {
                        Box(Modifier.fillMaxWidth().height(140.dp))
                        if (savedNow[id] == true) Box(Modifier.fillMaxWidth().height(30.dp))
                        if (reactionsNow[id] == true) Box(Modifier.fillMaxWidth().height(30.dp))
                        val card = harness.lateCardDp[id] ?: 0
                        if (card > 0) Box(Modifier.fillMaxWidth().height(card.dp))
                    }
                }
            }
        }
        waitForIdle()
        return harness
    }

    /** Forward tops of the laid-out rows, by message id. */
    private fun ComposeUiTest.tops(harness: Harness): Map<String, Int> =
        runOnIdle {
            val messages = harness.messages.value
            harness.viewport.layoutInfo.visibleItemsInfo.associate { messages[it.index] to it.offset }
        }

    /** Applies [change] and lets exactly one frame lay it out. */
    private fun ComposeUiTest.oneFrame(change: () -> Unit) {
        mainClock.autoAdvance = false
        runOnIdle(change)
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    private fun Map<String, Int>.readingAnchor(): String = entries.filter { it.value >= 0 }.minBy { it.value }.key

    private fun ComposeUiTest.assertReadingRowsKept(change: Harness.(changed: String) -> Unit) {
        val harness = mount()
        val before = tops(harness)
        val anchor = before.readingAnchor()
        // A row below the reading anchor, still in view.
        val changed = ids[ids.indexOf(anchor) + 2]
        assertNotNull(before[changed], "$changed not laid out")

        oneFrame { harness.change(changed) }

        val after = tops(harness)
        // Everything from the reading anchor down to the changed row keeps its top.
        ids.subList(ids.indexOf(anchor), ids.indexOf(changed) + 1).forEach { id ->
            assertEquals(before[id], after[id], "$id moved after changing $changed in place")
        }
        assertTrue(harness.owner.keeper.corrections >= 1, "no correction made")
        assertEquals(ThreadViewportOwnerTag.ReadingAnchor, harness.viewport.lastScrollOwnerTag)
        assertTrue(harness.viewport.lastScrollOwnerAttempt >= 1, "reading anchor correction must record a 1-based attempt")
    }

    @Test
    fun savingKeepsTheReadingRow() =
        runRobolectricComposeUiTest {
            assertReadingRowsKept { changed -> saved.value = saved.value + (changed to true) }
        }

    /** The row whose Save / reaction the user tapped is the anchor: its top stays, the change grows downward. */
    @Test
    fun theTappedRowStaysPut() =
        runRobolectricComposeUiTest {
            val harness = mount()
            val before = tops(harness)
            val tapped = ids[ids.indexOf(before.readingAnchor()) + 1]
            runOnIdle { assertEquals(true, harness.owner.keeper.anchorOn(tapped)) }

            oneFrame { harness.saved.value = mapOf(tapped to true) }

            assertEquals(before[tapped], tops(harness)[tapped])
        }

    /** Following the latest row the bottom stays pinned, as before: the rows above move up. */
    @Test
    fun followingTheLatestRowKeepsTheBottomPinned() =
        runRobolectricComposeUiTest {
            val harness = mount()
            harness.followingLatest.value = true
            waitForIdle()
            val before = tops(harness)
            val anchor = before.readingAnchor()
            val changed = ids[ids.indexOf(anchor) + 2]

            oneFrame { harness.saved.value = mapOf(changed to true) }

            assertEquals(before.getValue(anchor) - harness.markerPx, tops(harness)[anchor])
            assertEquals(0, harness.owner.keeper.corrections)
        }

    /** Another owner's scroll (a jump, a restore, a drag) is a new position, never undone. */
    @Test
    fun aScrollIsNeverUndone() =
        runRobolectricComposeUiTest {
            val harness = mount()
            val before = tops(harness)
            val anchor = before.readingAnchor()

            oneFrame { harness.scope.launch { harness.viewport.scrollBy(-200f) } }

            val after = tops(harness)
            assertNotEquals(before[anchor], after[anchor])
            assertEquals(before.getValue(anchor) + 200, after[anchor])
            assertEquals(0, harness.owner.keeper.corrections)
        }

    /**
     * Locked out: a new message arriving below while the reader is in the history. The bottom
     * row keeps its key and offset, the rows on screen do not move, and the keeper makes no
     * correction.
     */
    @Test
    fun aNewMessageWhileReadingHistoryIsLeftAlone() =
        runRobolectricComposeUiTest {
            val harness = mount()
            val before = tops(harness)

            oneFrame { harness.messages.value = harness.messages.value + "m12" }

            assertEquals(before, tops(harness).filterKeys { it in before })
            assertEquals(0, harness.owner.keeper.corrections)
        }

    /**
     * The keeper's correction is a programmatic scroll: an open author popover stays open through
     * a Save on screen, and closes on the user's own drag.
     */
    @Test
    fun aPopoverClosesOnTheUsersScrollOnly() =
        runRobolectricComposeUiTest {
            val harness = mount()
            harness.popoverOpen.value = true
            val changed = ids[ids.indexOf(tops(harness).readingAnchor()) + 2]

            oneFrame { harness.saved.value = mapOf(changed to true) }

            assertTrue(harness.owner.keeper.corrections >= 1, "no correction made")
            assertEquals(true, harness.popoverOpen.value, "the correction closed the popover")

            mainClock.autoAdvance = true
            onNodeWithTag(ListTag).performTouchInput { swipeDown() }
            waitForIdle()
            assertEquals(false, harness.popoverOpen.value, "the user's drag left the popover open")
        }

    private companion object {
        const val ListTag = "thread-reading-anchor-list"
    }
}
