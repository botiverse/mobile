package build.raft.app

import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.StateObject
import androidx.compose.ui.test.ExperimentalTestApi
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
 * Deterministic red-before-green test for `#proj-mobile:#264` (`EmptyRaftChannelKindsState` in
 * `RaftChannelKind.kt`): the default [State] of [LocalRaftChannelKinds] is a constant and must
 * NOT be a Compose snapshot [StateObject], because top-level helpers in `RaftChannelKind.kt` can
 * be invoked from background data projections during cold start.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalRaftChannelKindsSnapshotIsolationUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun localRaftChannelKindsDefaultIsNotASnapshotStateObject() =
        runRobolectricComposeUiTest {
            var defaultState: State<RaftChannelKinds>? = null
            setContent {
                defaultState = LocalRaftChannelKinds.current
            }
            waitForIdle()
            val resolved = assertNotNull(defaultState)
            assertFalse(
                resolved is StateObject,
                "LocalRaftChannelKinds default State must not be a Compose snapshot StateObject (risks cold-start SnapshotKt.readError): $resolved",
            )
            val snapshotReads = mutableListOf<Any>()
            val snapshot = Snapshot.takeSnapshot(readObserver = { snapshotReads += it })
            try {
                snapshot.enter {
                    assertEquals(RaftChannelKinds.Empty, resolved.value)
                }
            } finally {
                snapshot.dispose()
            }
            assertTrue(
                snapshotReads.isEmpty(),
                "Reading LocalRaftChannelKinds default value must not record snapshot reads: $snapshotReads",
            )
        }
}
