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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * artin (#proj-mobile #201): saving / unsaving a message jumped the thread for two frames. The
 * Saved indicator comes or goes in the row's footer; the thread list is reversed, so the rows above
 * the saved one moved by its height until a later controller replay put them back. Drives the
 * production [ThreadSavedToggleTopKeepEffect] over a reversed LazyColumn, as ThreadMessagesList
 * does, and reads the very next frame: the row above (and the saved row's top) stay put.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ThreadSavedToggleTopKeepUiTest {
    @get:Rule val activities = RobolectricComposeActivityRule()

    private val ids = (0 until 10).map { "m$it" }
    private val target = "m5"
    private val targetIndex = ids.indexOf(target)

    private class Harness(
        val saved: MutableState<Map<String, Boolean>>,
        val keeper: ThreadSavedToggleTopKeeper,
    ) {
        lateinit var viewport: ThreadListViewport
        var footerPx = 0
        val kept = mutableListOf<Pair<String, Int>>()
    }

    private fun ComposeUiTest.mount(targetSaved: Boolean): Harness {
        val harness = Harness(mutableStateOf(mapOf(target to targetSaved)), ThreadSavedToggleTopKeeper())
        setContent {
            harness.footerPx = with(LocalDensity.current) { 40.dp.roundToPx() }
            // The saved row at the bottom of the viewport, its footer partly below it (the recording).
            val raw =
                rememberLazyListState(
                    initialFirstVisibleItemIndex = reversedThreadListIndex(targetIndex, ids.size),
                    initialFirstVisibleItemScrollOffset = harness.footerPx / 2,
                )
            val viewport = remember(raw) { ThreadListViewport(raw, reverse = true) }
            harness.viewport = viewport
            // Read in composition, as ThreadMessagesList reads its render payload.
            val savedNow = harness.saved.value
            ThreadSavedToggleTopKeepEffect(
                keeper = harness.keeper,
                listState = viewport,
                savedStateOf = { id -> if (id in ids) savedNow[id] ?: false else null },
                listItemIndexOf = { id -> ids.indexOf(id) },
                userScrollGeneration = 0,
                followingLatest = { false },
            ) { id, top -> harness.kept += id to top }
            LazyColumn(state = raw, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                items(ids.asReversed(), key = { it }) { id ->
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().height(180.dp))
                        // The footer's Saved indicator.
                        if (savedNow[id] == true) Box(Modifier.fillMaxWidth().height(40.dp))
                    }
                }
            }
        }
        waitForIdle()
        return harness
    }

    private fun Harness.forwardTop(index: Int): Int =
        assertNotNull(viewport.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }, "row $index not laid out").offset

    /** Toggles the target's saved state (armed like a menu tap when [armed]) and returns the next frame's row tops. */
    private fun ComposeUiTest.toggle(harness: Harness, armed: Boolean): Pair<Int, Int> {
        val next = !(harness.saved.value[target] ?: false)
        mainClock.autoAdvance = false
        runOnIdle {
            if (armed) harness.keeper.arm(target, saved = next, userScrollGeneration = 0)
            harness.saved.value = harness.saved.value + (target to next)
        }
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()
        return runOnIdle { harness.forwardTop(targetIndex - 1) to harness.forwardTop(targetIndex) }
    }

    /** Arms a toggle as the menu / Saved indicator tap does and checks the next frame. */
    private fun assertTogglingKeepsTheRowsAbove(initiallySaved: Boolean) =
        runRobolectricComposeUiTest {
            val harness = mount(targetSaved = initiallySaved)
            val aboveBefore = runOnIdle { harness.forwardTop(targetIndex - 1) }
            val targetBefore = runOnIdle { harness.forwardTop(targetIndex) }

            val (aboveAfter, targetAfter) = toggle(harness, armed = true)

            assertEquals(aboveBefore, aboveAfter, "row above moved")
            assertEquals(targetBefore, targetAfter, "saved row's top moved")
            assertEquals(listOf(target to targetBefore), harness.kept)
        }

    @Test
    fun unsavingKeepsTheRowsAboveInTheNextFrame() = assertTogglingKeepsTheRowsAbove(initiallySaved = true)

    @Test
    fun savingKeepsTheRowsAboveInTheNextFrame() = assertTogglingKeepsTheRowsAbove(initiallySaved = false)

    /** The recording's jump, without the keeper: the reversed list moves the rows above by the footer. */
    @Test
    fun anUnarmedFooterChangeMovesTheRowsAbove() {
        runRobolectricComposeUiTest {
            val harness = mount(targetSaved = true)
            val aboveBefore = runOnIdle { harness.forwardTop(targetIndex - 1) }

            val (aboveAfter, _) = toggle(harness, armed = false)

            assertEquals(aboveBefore + harness.footerPx, aboveAfter)
            assertEquals(emptyList<Pair<String, Int>>(), harness.kept)
        }
    }

    @Test
    fun aUserScrollSinceTheTapRetiresTheToggle() {
        val keeper = ThreadSavedToggleTopKeeper()
        keeper.arm(target, saved = true, userScrollGeneration = 3)
        assertEquals(null, keeper.take({ true }, userScrollGeneration = 4))
        // Retired for good: the same state at the old generation no longer applies.
        assertEquals(null, keeper.take({ true }, userScrollGeneration = 3))

        keeper.arm(target, saved = true, userScrollGeneration = 3)
        assertEquals(null, keeper.take({ false }, userScrollGeneration = 3), "payload not updated yet")
        assertEquals(target, keeper.take({ true }, userScrollGeneration = 3))
        assertEquals(null, keeper.take({ true }, userScrollGeneration = 3), "applied once")
    }
}
