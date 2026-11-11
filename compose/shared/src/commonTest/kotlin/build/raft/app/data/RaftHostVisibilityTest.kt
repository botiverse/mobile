package build.raft.app.data

import build.raft.app.releaseRaftShellStoreScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftHostVisibilityTest {
    @AfterTest
    fun tearDown() {
        clearRaftHostVisibilityForTests()
    }

    @Test
    fun unknownShellDefaultsVisible() {
        assertTrue(isRaftHostVisible("shell-a"))
    }

    @Test
    fun hiddenBeforeThreadCompositionIsReplayable() {
        updateRaftHostVisibility("shell-a", visible = false)

        assertFalse(isRaftHostVisible("shell-a"))
    }

    @Test
    fun rapidHiddenVisibleTransitionReturnsCurrentState() {
        updateRaftHostVisibility("shell-a", visible = false)
        updateRaftHostVisibility("shell-a", visible = true)

        assertTrue(isRaftHostVisible("shell-a"))
    }

    @Test
    fun lateOldShellEventDoesNotAffectCurrentShell() {
        updateRaftHostVisibility("shell-current", visible = true)
        updateRaftHostVisibility("shell-old", visible = false)

        assertTrue(isRaftHostVisible("shell-current"))
        assertFalse(isRaftHostVisible("shell-old"))
    }

    @Test
    fun blankShellUsesLegacyScope() {
        updateRaftHostVisibility("", visible = false)

        assertFalse(isRaftHostVisible(RaftShellStoreScope.Legacy.shellInstanceId))
    }

    // Hands a92fbb13: two restored windows, the hidden one reconciled last and kept the only
    // realtime connection on its server; the window in front never heard its own server.
    @Test
    fun windowInFrontOwnsTheProcessRealtimeConnection() {
        updateRaftHostVisibility("shell-a", visible = true)
        updateRaftHostVisibility("shell-a", visible = false)
        updateRaftHostVisibility("shell-b", visible = true)

        assertTrue(raftShellOwnsProcessRealtime("shell-b", multiShellHostEnabled = true))
        assertFalse(raftShellOwnsProcessRealtime("shell-a", multiShellHostEnabled = true))

        val handedToB = raftProcessRealtimeOwnerGeneration()
        updateRaftHostVisibility("shell-a", visible = true)

        assertTrue(raftShellOwnsProcessRealtime("shell-a", multiShellHostEnabled = true))
        assertFalse(raftShellOwnsProcessRealtime("shell-b", multiShellHostEnabled = true))
        assertEquals(handedToB + 1, raftProcessRealtimeOwnerGeneration())
    }

    @Test
    fun sameWindowComingBackKeepsTheOwnerGeneration() {
        updateRaftHostVisibility("shell-a", visible = true)
        val generation = raftProcessRealtimeOwnerGeneration()

        updateRaftHostVisibility("shell-a", visible = false)
        updateRaftHostVisibility("shell-a", visible = true)

        assertEquals(generation, raftProcessRealtimeOwnerGeneration())
        assertTrue(raftShellOwnsProcessRealtime("shell-a", multiShellHostEnabled = true))
    }

    @Test
    fun everyShellReconcilesWithoutSeparateWindowsOrBeforeAnyWindowReports() {
        assertTrue(raftShellOwnsProcessRealtime("shell-a", multiShellHostEnabled = true))

        updateRaftHostVisibility("shell-b", visible = true)

        assertTrue(raftShellOwnsProcessRealtime("shell-a", multiShellHostEnabled = false))
    }

    @Test
    fun processReconcileFollowsTheOwningWindowServerWhileGranted() {
        val granted = setOf("server-a", "server-b")
        assertEquals("server-a", raftProcessRealtimeServerId("server-a", granted))

        noteRaftShellRealtimeServer("server-b")
        assertEquals("server-b", raftProcessRealtimeServerId("server-a", granted))
        assertEquals("server-a", raftProcessRealtimeServerId("server-a", setOf("server-a")))

        noteRaftShellRealtimeServer(null)
        assertEquals("server-a", raftProcessRealtimeServerId("server-a", granted))
    }

    // Phase 3: host visibility and closing a window reach the process realtime demand.
    @Test
    fun visibilityAndWindowCloseFeedTheProcessRealtimeDemand() {
        val granted = setOf("server-a", "server-b")
        raftProcessRealtimeSessionDemand.claim("shell-a", "server-a")
        raftProcessRealtimeSessionDemand.claim("shell-b", "server-b")

        updateRaftHostVisibility("shell-a", visible = false)
        updateRaftHostVisibility("shell-b", visible = true)
        assertEquals(
            listOf("server-b", "server-a"),
            raftProcessRealtimeSessionDemand.desiredServerIds(granted, "server-a", defaultNowMillis(), enabled = true).toList(),
        )

        val revision = raftProcessRealtimeSessionDemand.revision.value
        releaseRaftShellStoreScope("shell-b", "server-b")
        assertTrue(raftProcessRealtimeSessionDemand.revision.value > revision, "closing a window re-reconciles")
        assertEquals(
            setOf("server-a"),
            raftProcessRealtimeSessionDemand.desiredServerIds(granted, "server-a", defaultNowMillis(), enabled = true),
        )
    }
}
