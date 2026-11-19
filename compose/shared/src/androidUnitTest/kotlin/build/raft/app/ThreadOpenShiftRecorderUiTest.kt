package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The open-trace shift recorder ([ThreadOpenShiftRecorderEffect]) on a real LazyColumn, wired as
 * ThreadMessagesListContent wires it: a memory-cache stand-in whose first row the network answer
 * replaces with taller content. The rows under it move, and that move is recorded as one shift caused
 * by a row height change, after which the open settles as stable. The recorder never scrolls.
 *
 * The recorder asks for every frame while its open is in progress, so the test drives the clock by
 * hand (an always-pending frame would never let the test go idle).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadOpenShiftRecorderUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun aStandInReplacedByTallerNetworkContentRecordsARowHeightShiftThenSettles() =
        runRobolectricComposeUiTest {
            val firstRowHeight = mutableStateOf(60.dp)
            val refreshing = mutableStateOf(true)
            val recording = mutableStateOf(false)
            val session =
                ThreadOpenTraceSession(
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    openKind = "latest",
                    sampleRate = 1.0,
                    recordCondition = "trace_sample",
                    viewKey = "recorder-ui-test",
                    nowMs = { mainClock.currentTime },
                    nowEpochMs = { 0L },
                )
            var viewport: ThreadListViewport? = null
            setContent {
                val raw = rememberLazyListState()
                val list = remember(raw) { ThreadListViewport(raw, reverse = false) }
                viewport = list
                Box(Modifier.height(400.dp)) {
                    // Fills the box from the first frame, so the viewport never changes size.
                    LazyColumn(state = raw, modifier = Modifier.fillMaxSize()) {
                        item(key = "m0") { Box(Modifier.fillMaxWidth().height(firstRowHeight.value)) }
                        items(9, key = { "m${it + 1}" }) { Box(Modifier.fillMaxWidth().height(60.dp)) }
                    }
                }
                if (recording.value) {
                    ThreadOpenShiftRecorderEffect(session = session, listState = list, typeScaleKey = 0) { inputs ->
                        inputs.status = "content"
                        inputs.messageRows = 10
                        inputs.backgroundRefreshing = refreshing.value
                        inputs.positioned = true
                    }
                }
            }
            waitForIdle()
            mainClock.autoAdvance = false
            runOnUiThread {
                recording.value = true
                Snapshot.sendApplyNotifications()
            }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertEquals("memory", session.cache)
            assertEquals(0, session.shiftCount)

            // The network answer: the first row's real content is 100 dp taller.
            runOnUiThread {
                firstRowHeight.value = 160.dp
                refreshing.value = false
                Snapshot.sendApplyNotifications()
            }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertEquals("applied", session.networkResult)
            assertEquals(1, session.shiftCount, "one shift")
            assertEquals(ThreadOpenShiftCause.ContentRowHeight, session.shiftCauseAt(0))
            assertEquals(100f, session.shiftDeltaDpAt(0), 1f)
            assertNull(session.end)

            mainClock.advanceTimeBy(ThreadOpenStableQuietMs + 100L)
            repeat(2) { mainClock.advanceTimeByFrame() }
            assertEquals(ThreadOpenEnd.Stable, session.end)
            assertEquals(0, viewport?.ownedScrollCount, "observe-only: the recorder issued no scroll")
            mainClock.autoAdvance = true
            waitForIdle()
        }
}
