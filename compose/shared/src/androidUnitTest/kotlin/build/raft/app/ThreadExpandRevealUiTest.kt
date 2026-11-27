package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.thread.ThreadExpandRevealEffect
import build.raft.app.thread.ThreadExpandRevealOutcome
import build.raft.app.thread.ThreadExpandRevealOwner
import build.raft.app.thread.ThreadExpandRevealTopMargin
import build.raft.app.thread.ThreadListViewport
import build.raft.app.thread.ThreadReadingAnchorOwner
import build.raft.app.thread.key
import build.raft.app.thread.threadExpandRevealDelta
import build.raft.app.thread.threadReadingAnchorOwns
import build.raft.app.thread.threadVisibleContentBottom
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * artin dm:@artin:#151: a folded system-message group at the bottom of a thread opened below the
 * composer (nothing visible changed) and closing it jumped the list for a frame. Mounts the
 * thread's reversed LazyColumn with a group whose summary row sits at the bottom of the viewport
 * and drives the production pieces the group toggle uses, wired as ThreadMessagesList wires them:
 * the reading anchor keeper ([ThreadReadingAnchorOwner], #5067; the tapped summary becomes its
 * anchor as `keepReadingAnchorFor` makes it) and the [ThreadExpandRevealEffect] owner.
 * Checks (1) the summary keeps its place in the frame the group opens, (2) the opened rows are
 * then brought into view without pushing the summary off the top, and (3) a drag since the tap
 * cancels the reveal.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ThreadExpandRevealUiTest {
    @get:Rule val activities = RobolectricComposeActivityRule()

    private val ids = (0 until 10).map { "m$it" }
    private val summary = "m7"
    private val members = listOf("m8", "m9")
    private val summaryIndex = ids.indexOf(summary)
    private val lastIndex = ids.indexOf(members.last())

    private class Harness(val owner: ThreadExpandRevealOwner) {
        val open = mutableStateOf(false)
        /** The viewport controller: following the latest row until the tap anchors it on the summary. */
        val followingLatest = mutableStateOf(true)
        val anchoredMessageId = mutableStateOf<String?>(null)
        lateinit var keeper: ThreadReadingAnchorOwner
        var generation = 0
        lateinit var viewport: ThreadListViewport
        var marginPx = 0
        val outcomes = mutableListOf<ThreadExpandRevealOutcome>()
    }

    private fun ComposeUiTest.mount(memberHeight: Dp): Harness {
        val harness = Harness(ThreadExpandRevealOwner())
        setContent {
            harness.marginPx = with(LocalDensity.current) { ThreadExpandRevealTopMargin.roundToPx() }
            // Parked at the newest row, as the thread opens: the closed group's summary is the last
            // thing above the composer.
            val raw = rememberLazyListState()
            val viewport = remember(raw) { ThreadListViewport(raw, reverse = true) }
            harness.viewport = viewport
            val scope = rememberCoroutineScope()
            val keeper = remember(viewport) { ThreadReadingAnchorOwner(viewport, scope) }
            harness.keeper = keeper
            val afterLayout: () -> Unit = {
                keeper.afterLayout(
                    owns = {
                        threadReadingAnchorOwns(
                            reverseLayout = true,
                            followingLatest = harness.followingLatest.value,
                            scrollInProgress = viewport.isScrollInProgress,
                            ownerLive = !viewport.isRetired,
                            placementSettled = true,
                        )
                    },
                    preferredMessageId = { harness.anchoredMessageId.value },
                    messageIdAt = { index -> ids.getOrNull(index) },
                )
            }
            val open = harness.open.value
            ThreadExpandRevealEffect(
                owner = harness.owner,
                listState = viewport,
                instanceKey = Unit,
                listItemIndexOf = { id -> ids.indexOf(id) },
                userScrollGeneration = { harness.generation },
                controllerSettled = { true },
                topMarginPx = harness.marginPx,
            ) { _, outcome -> harness.outcomes += outcome }
            LazyColumn(
                state = raw,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize().onGloballyPositioned { afterLayout() },
            ) {
                items(ids.asReversed(), key = { it }) { id ->
                    Box(Modifier.fillMaxWidth().onGloballyPositioned { afterLayout() }) {
                    when (id) {
                        summary ->
                            Column(Modifier.fillMaxWidth()) {
                                // The summary ("2 system updates") and, opened, its own line.
                                Box(Modifier.fillMaxWidth().height(40.dp))
                                if (open) Box(Modifier.fillMaxWidth().height(40.dp))
                            }
                        in members -> Box(Modifier.fillMaxWidth().height(if (open) memberHeight else 0.dp))
                        else -> Box(Modifier.fillMaxWidth().height(180.dp))
                    }
                    }
                }
            }
        }
        waitForIdle()
        return harness
    }

    private fun Harness.item(index: Int) =
        assertNotNull(viewport.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }, "row $index not laid out")

    /** Opens the group as the summary tap does; returns the summary's top in the very next frame. */
    private fun ComposeUiTest.open(harness: Harness): Int {
        mainClock.autoAdvance = false
        runOnIdle {
            val before = harness.item(summaryIndex)
            // keepTappedRowForToggle: an expand replaces following the latest row with an anchor on
            // the tapped summary, which becomes the reading anchor.
            harness.followingLatest.value = false
            harness.anchoredMessageId.value = summary
            assertTrue(harness.keeper.keeper.anchorOn(summary), "summary not laid out")
            harness.open.value = true
            harness.owner.request(
                firstMessageId = summary,
                lastMessageId = members.last(),
                rowSizeBefore = before.size,
                userScrollGeneration = harness.generation,
            )
        }
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()
        return runOnIdle { harness.item(summaryIndex).offset }
    }

    @Test
    fun openingAGroupAtTheBottomKeepsTheSummaryThenRevealsTheOpenedRows() =
        runRobolectricComposeUiTest {
            val harness = mount(memberHeight = 120.dp)
            val summaryTopBefore = runOnIdle { harness.item(summaryIndex).offset }
            val visibleBottom = runOnIdle { harness.viewport.layoutInfo.threadVisibleContentBottom() }

            // (1) No jump: the opening frame keeps the summary where it was tapped.
            assertEquals(summaryTopBefore, open(harness), "summary moved in the opening frame")
            // ...and the opened rows are below the viewport at that point (the recording).
            assertTrue(runOnIdle { harness.viewport.layoutInfo.visibleItemsInfo.none { it.index == lastIndex } })

            // (2) The reveal brings them in, smoothly, all of them since they fit.
            mainClock.autoAdvance = true
            waitForIdle()
            val last = runOnIdle { harness.item(lastIndex) }
            assertEquals(visibleBottom, last.offset + last.size, "opened rows not brought into view")
            val summaryTopAfter = runOnIdle { harness.item(summaryIndex).offset }
            assertTrue(summaryTopAfter < summaryTopBefore, "list did not move")
            assertTrue(summaryTopAfter >= harness.marginPx, "summary scrolled off the top")
            val outcome = harness.outcomes.single() as ThreadExpandRevealOutcome.Revealed
            assertEquals(summaryTopBefore - summaryTopAfter, outcome.delta)
            assertTrue(outcome.atBottom)
            assertEquals(null, harness.owner.request)
        }

    @Test
    fun aDragSinceTheTapRetiresTheReveal() =
        runRobolectricComposeUiTest {
            val harness = mount(memberHeight = 120.dp)
            val summaryTopBefore = runOnIdle { harness.item(summaryIndex).offset }

            open(harness)
            // The user's drag bumps the list's user-scroll generation before the reveal measures.
            runOnIdle { harness.generation += 1 }
            mainClock.autoAdvance = true
            waitForIdle()

            assertEquals(summaryTopBefore, runOnIdle { harness.item(summaryIndex).offset }, "a retired reveal scrolled")
            assertTrue(harness.outcomes.single() is ThreadExpandRevealOutcome.Skipped)
            assertEquals(null, harness.owner.request)
        }

    @Test
    fun revealDeltaPolicy() {
        // Fits: nothing moves.
        assertEquals(0, threadExpandRevealDelta(controlTop = 300, contentBottom = 700, visibleBottom = 760, topMargin = 8))
        // Hidden part smaller than the room above the control: all of it.
        assertEquals(140, threadExpandRevealDelta(controlTop = 600, contentBottom = 900, visibleBottom = 760, topMargin = 8))
        // Hidden part larger: stop with the control at the top margin.
        assertEquals(292, threadExpandRevealDelta(controlTop = 300, contentBottom = 2000, visibleBottom = 760, topMargin = 8))
        // The last row not laid out (beyond the viewport): as far as the control allows.
        assertEquals(592, threadExpandRevealDelta(controlTop = 600, contentBottom = null, visibleBottom = 760, topMargin = 8))
        // A control already above the margin: never scrolls backward.
        assertEquals(0, threadExpandRevealDelta(controlTop = 4, contentBottom = null, visibleBottom = 760, topMargin = 8))
    }
}
