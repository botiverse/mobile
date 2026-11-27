package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import build.raft.app.nav.LocalRaftStackPageInMotion
import build.raft.app.nav.LocalRaftStackPageLeaving
import build.raft.app.thread.ThreadAfterMotionEffect
import build.raft.app.thread.ThreadMutableBooleanRef
import build.raft.app.thread.ThreadPageLeavingTracker
import build.raft.app.thread.ThreadScrollAheadWindowEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that [ThreadPageLeavingTracker], [ThreadScrollAheadWindowEffect], and
 * [ThreadAfterMotionEffect] isolate [LocalRaftStackPageInMotion] and [LocalRaftStackPageLeaving]
 * inside leaf composables so push/pop transitions never recompose the enclosing list scope, and
 * that `FirstContentReady` waits until the initial push slide settles.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class ThreadMotionIsolationUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun motionAndLeavingTransitionsDoNotRecomposeParentAndGateFirstContentReady() =
        runRobolectricComposeUiTest {
            var inMotion by mutableStateOf(true)
            var leaving by mutableStateOf(false)
            val parentRecompositions = intArrayOf(0)
            val leavingRef = ThreadMutableBooleanRef(false)
            val beyondBoundsRef = ThreadMutableBooleanRef(false)
            val logs = mutableListOf<String>()
            var contentReadyCalls = 0
            val onLog: (String) -> Unit = { logs += it }
            val onContentReady: () -> Unit = { contentReadyCalls += 1 }

            setContent {
                CompositionLocalProvider(
                    LocalRaftStackPageInMotion provides inMotion,
                    LocalRaftStackPageLeaving provides leaving,
                ) {
                    MotionIsolatedHost(
                        parentRecompositions = parentRecompositions,
                        leavingRef = leavingRef,
                        beyondBoundsRef = beyondBoundsRef,
                        onLog = onLog,
                        onContentReady = onContentReady,
                    )
                }
            }

            waitForIdle()
            assertEquals(1, parentRecompositions[0], "initial composition runs once")
            assertFalse(leavingRef.value, "not leaving during push slide")
            assertFalse(beyondBoundsRef.value, "scroll-ahead window stays closed during push slide")
            assertEquals(0, contentReadyCalls, "FirstContentReady waits while page is in motion")
            assertTrue(logs.isEmpty(), "no beyond-bounds log while page is in motion")

            // Push slide settles: leaf effects fire, parent scope must NOT recompose.
            inMotion = false
            waitForIdle()
            assertEquals(1, parentRecompositions[0], "settling push slide must not recompose parent")
            assertTrue(beyondBoundsRef.value, "scroll-ahead window opens after settle")
            assertEquals(1, contentReadyCalls, "FirstContentReady fires once after settle")
            assertEquals(1, logs.size, "beyond-bounds log emitted once")
            assertTrue(logs.single().contains("thread_initial_beyond_bounds result=enabled_after_first_frame items=6"))

            // Subsequent pop / cover motion: parent still does not recompose, leavingRef updates,
            // and FirstContentReady does not replay.
            inMotion = true
            leaving = true
            waitForIdle()
            assertEquals(1, parentRecompositions[0], "pop transition must not recompose parent")
            assertTrue(leavingRef.value, "leavingRef reflects LocalRaftStackPageLeaving immediately")
            assertEquals(1, contentReadyCalls, "FirstContentReady must not replay on later motion")
        }
}

@Composable
private fun MotionIsolatedHost(
    parentRecompositions: IntArray,
    leavingRef: ThreadMutableBooleanRef,
    beyondBoundsRef: ThreadMutableBooleanRef,
    onLog: (String) -> Unit,
    onContentReady: () -> Unit,
) {
    parentRecompositions[0] += 1
    ThreadPageLeavingTracker(leavingRef)
    ThreadScrollAheadWindowEffect(
        threadInstanceKey = "channel:c1",
        initialBottomAnchorReady = true,
        viewportMeasurementReady = true,
        listBeyondBoundsItems = 6,
        enabledRef = beyondBoundsRef,
        diagnosticsLogger = onLog,
    )
    ThreadAfterMotionEffect("channel:c1") {
        onContentReady()
    }
}
