package build.raft.app

import androidx.compose.runtime.snapshots.Snapshot
import build.raft.app.data.RaftDisplayPreferenceRuntime
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.data.RaftTimeZoneRuntime
import build.raft.app.model.ChannelId
import build.raft.app.resources.StringDesc
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Deterministic red-before-green regression tests for `#proj-mobile:#264` (Hands `25b91174` /
 * PR `#5293` follow-up): process-wide runtime getters must never read Compose snapshot
 * [androidx.compose.runtime.snapshots.StateObject]s, so cold-start off-main-thread `<clinit>` or
 * background-flow reads can never trigger `SnapshotKt.readError`.
 */
class ProcessWideSnapshotStateIsolationTest {
    private fun assertNoSnapshotStateReads(label: String, block: () -> Unit) {
        val snapshotReads = mutableListOf<Any>()
        val snapshot = Snapshot.takeSnapshot(readObserver = { snapshotReads += it })
        try {
            snapshot.enter(block)
        } finally {
            snapshot.dispose()
        }
        assertTrue(
            snapshotReads.isEmpty(),
            "$label must not read Compose snapshot StateObjects (risks cold-start SnapshotKt.readError): $snapshotReads",
        )
    }

    @Test
    fun raftDisplayPreferenceRuntimeGettersNeverReadComposeSnapshotState() {
        assertNoSnapshotStateReads("RaftDisplayPreferenceRuntime") {
            RaftDisplayPreferenceRuntime.showLiveAgentActivity
            RaftDisplayPreferenceRuntime.showAgentModelName
            RaftDisplayPreferenceRuntime.showSenderDescription
            RaftDisplayPreferenceRuntime.hideEmptySidebarSections
        }
    }

    @Test
    fun raftTimeFormatRuntimeGettersNeverReadComposeSnapshotState() {
        assertNoSnapshotStateReads("RaftTimeFormatRuntime") {
            RaftTimeFormatRuntime.current
            RaftTimeFormatRuntime.currentOrNull
        }
    }

    @Test
    fun raftTimeZoneRuntimeGettersNeverReadComposeSnapshotState() {
        assertNoSnapshotStateReads("RaftTimeZoneRuntime") {
            RaftTimeZoneRuntime.currentOrNull
        }
    }

    @Test
    fun stringDescLocaleTypeNeverReadsComposeSnapshotState() {
        assertNoSnapshotStateReads("StringDesc.localeType") {
            StringDesc.localeType
        }
    }

    @Test
    fun raftUpgradeDialogStateNeverReadsComposeSnapshotState() {
        assertNoSnapshotStateReads("RaftUpgradeDialogState.request") {
            RaftUpgradeDialogState.request
        }
    }

    @Test
    fun raftAskSeatsDialogStateNeverReadsComposeSnapshotState() {
        assertNoSnapshotStateReads("RaftAskSeatsDialogState.request") {
            RaftAskSeatsDialogState.request
        }
    }

    @Test
    fun threadChannelJoinsInFlightNeverReadsComposeSnapshotState() {
        assertNoSnapshotStateReads("ThreadChannelJoinsInFlight.isJoining") {
            ThreadChannelJoinsInFlight.isJoining(ChannelId("ch-snapshot-test"))
        }
    }

    @Test
    fun threadComposerImagePasteRequestsNeverReadsComposeSnapshotState() {
        assertNoSnapshotStateReads("ThreadComposerImagePasteRequests.count") {
            ThreadComposerImagePasteRequests.count
        }
    }

    @Test
    fun raftHostWindowInsetsCurrentNeverReadsComposeSnapshotState() {
        val windowId = "win-snapshot-isolation-test"
        RaftHostWindowInsets.publish(windowId, topPx = 24f, bottomPx = 12f, statusBarHidden = false)
        try {
            assertNoSnapshotStateReads("RaftHostWindowInsets.current") {
                RaftHostWindowInsets.current(windowId)
            }
        } finally {
            RaftHostWindowInsets.remove(windowId)
        }
    }
}
