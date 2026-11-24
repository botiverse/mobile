package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class RaftAppUpdateCoordinatorTest {
    private val v129 = 12_900_000L
    private val v130 = 13_000_000L
    private val v131 = 13_100_000L

    private fun snapshot(
        latestCode: Long = v130,
        latestLabel: String = "1.30.0",
        forceUpdate: Boolean = false,
        transactionState: SharedSettingsUpdateTransactionState = SharedSettingsUpdateTransactionState.Idle,
    ): SharedSettingsUpdateCheckSnapshot =
        SharedSettingsUpdateCheckSnapshot(
            supported = true,
            updateAvailable = true,
            currentVersionLabel = "1.29.0",
            currentVersionCode = v129,
            latestVersionLabel = latestLabel,
            latestVersionCode = latestCode,
            transactionState = transactionState,
            forceUpdate = forceUpdate,
        )

    @Test
    fun refreshCachesWithinMinRefreshIntervalAndRespectsForce() = runTest {
        var clock = 1_000_000L
        val store = RaftAppUpdatePromptStore(InMemoryRaftKeyValueStore())
        val coordinator =
            RaftAppUpdateCoordinator(
                store = store,
                now = { clock },
                minRefreshIntervalMs = 60_000L,
            )
        coordinator.recordAppLaunch(v129, "1.29.0")

        var fetchCount = 0
        val first =
            coordinator.refresh(force = false) {
                fetchCount += 1
                DataResult.Content(snapshot())
            }
        assertTrue(first is DataResult.Content)
        assertEquals(1, fetchCount)
        assertEquals(1_000_000L, coordinator.state.value.lastCheckedAtMs)
        assertFalse(coordinator.state.value.loading)
        assertTrue(coordinator.state.value.decision.showAmbientPrompt)

        // Within minRefreshIntervalMs -> cached, fetch not invoked.
        clock += 30_000L
        val cached =
            coordinator.refresh(force = false) {
                fetchCount += 1
                DataResult.Content(snapshot(latestCode = v131, latestLabel = "1.31.0"))
            }
        assertTrue(cached is DataResult.Content)
        assertEquals(1, fetchCount)
        assertEquals(v130, cached.data.latestVersionCode)

        // Force bypasses cache even within minRefreshIntervalMs.
        val forced =
            coordinator.refresh(force = true) {
                fetchCount += 1
                DataResult.Content(snapshot(latestCode = v131, latestLabel = "1.31.0"))
            }
        assertTrue(forced is DataResult.Content)
        assertEquals(2, fetchCount)
        assertEquals(v131, forced.data.latestVersionCode)
        assertEquals(v131, coordinator.state.value.snapshot?.latestVersionCode)

        // After minRefreshIntervalMs elapses, non-force refresh fetches again.
        clock += 60_000L
        val expired =
            coordinator.refresh(force = false) {
                fetchCount += 1
                DataResult.Content(snapshot(latestCode = v131, latestLabel = "1.31.0", forceUpdate = true))
            }
        assertTrue(expired is DataResult.Content)
        assertEquals(3, fetchCount)
        assertTrue(coordinator.state.value.decision.showStrongPromptCandidate)
    }

    @Test
    fun concurrentRefreshCallsCoalesceIntoOneInFlightFetch() = runTest {
        var clock = 2_000_000L
        val coordinator =
            RaftAppUpdateCoordinator(
                store = RaftAppUpdatePromptStore(InMemoryRaftKeyValueStore()),
                now = { clock },
                minRefreshIntervalMs = 60_000L,
            )
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<DataResult<SharedSettingsUpdateCheckSnapshot>>()
        var calls = 0

        val first =
            async {
                coordinator.refresh(force = true) {
                    calls += 1
                    started.complete(Unit)
                    gate.await()
                }
            }
        started.await()
        assertTrue(coordinator.state.value.loading)

        val second =
            async {
                coordinator.refresh(force = false) {
                    calls += 1
                    DataResult.Content(snapshot(latestCode = v131))
                }
            }

        gate.complete(DataResult.Content(snapshot(latestCode = v130)))
        val r1 = first.await()
        val r2 = second.await()

        assertEquals(1, calls)
        assertTrue(r1 is DataResult.Content && r1.data.latestVersionCode == v130)
        assertTrue(r2 is DataResult.Content && r2.data.latestVersionCode == v130)
        assertFalse(coordinator.state.value.loading)
    }

    @Test
    fun cancellingFirstConcurrentRefreshAllowsWaitingCallerToRecover() = runTest {
        val coordinator =
            RaftAppUpdateCoordinator(
                store = RaftAppUpdatePromptStore(InMemoryRaftKeyValueStore()),
                now = { 3_000_000L },
                minRefreshIntervalMs = 60_000L,
            )
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<DataResult<SharedSettingsUpdateCheckSnapshot>>()

        val first =
            async {
                coordinator.refresh(force = true) {
                    started.complete(Unit)
                    never.await()
                }
            }
        started.await()

        val second =
            async {
                coordinator.refresh(force = false) {
                    DataResult.Content(snapshot(latestCode = v130))
                }
            }

        first.cancel()
        val recovered = second.await()
        assertTrue(recovered is DataResult.Content)
        assertEquals(v130, recovered.data.latestVersionCode)
        assertFalse(coordinator.state.value.loading)
    }

    @Test
    fun refreshErrorPreservesPreviousSnapshotAndRecordsErrorCode() = runTest {
        var clock = 4_000_000L
        val coordinator =
            RaftAppUpdateCoordinator(
                store = RaftAppUpdatePromptStore(InMemoryRaftKeyValueStore()),
                now = { clock },
            )
        coordinator.publishSnapshot(snapshot(latestCode = v130))

        clock += 120_000L
        val result =
            coordinator.refresh(force = true) {
                DataResult.Error(ScreenError("hands_network_error", retryable = true))
            }
        assertTrue(result is DataResult.Error)
        assertEquals(v130, coordinator.state.value.snapshot?.latestVersionCode)
        assertEquals("hands_network_error", coordinator.state.value.errorCode)
        assertFalse(coordinator.state.value.loading)
    }

    @Test
    fun snoozeStrongPromptMarkAndPostUpdateDismissUpdateStateImmediately() {
        var clock = 5_000_000L
        val store = RaftAppUpdatePromptStore(InMemoryRaftKeyValueStore())
        val coordinator =
            RaftAppUpdateCoordinator(
                store = store,
                now = { clock },
            )

        // Simulate prior install on v129 then upgrade to v130.
        store.recordAppLaunch(v129)
        coordinator.recordAppLaunch(v130, "1.30.0")
        assertEquals(RaftPostUpdateNoticeState(v130, "1.30.0"), coordinator.state.value.decision.postUpdateNotice)

        coordinator.dismissPostUpdateNotice()
        assertNull(coordinator.state.value.decision.postUpdateNotice)

        // Publish a force-update offer for v131.
        coordinator.publishSnapshot(snapshot(latestCode = v131, latestLabel = "1.31.0", forceUpdate = true))
        assertTrue(coordinator.state.value.decision.showStrongPromptCandidate)
        assertEquals(RaftAppUpdateStrongPromptReason.ForceUpdate, coordinator.state.value.decision.strongPromptReason)
        assertEquals(v131, store.readState().firstSeenVersionCode)
        assertEquals(clock, store.readState().firstSeenAtMs)

        // Marking strong prompt shown suppresses strong prompt for cooldown while keeping ambient prompt.
        coordinator.markStrongPromptShown()
        assertFalse(coordinator.state.value.decision.showStrongPromptCandidate)
        assertTrue(coordinator.state.value.decision.showAmbientPrompt)

        // Snoozing suppresses ambient prompt as well.
        coordinator.snoozeCurrentUpdate()
        assertTrue(coordinator.state.value.decision.isSnoozed)
        assertFalse(coordinator.state.value.decision.showAmbientPrompt)
        assertFalse(coordinator.state.value.decision.showStrongPromptCandidate)

        // Once 24h passes, publishing/refreshing restores both prompts.
        clock += RAFT_APP_UPDATE_SNOOZE_DURATION_MS
        coordinator.publishSnapshot(snapshot(latestCode = v131, latestLabel = "1.31.0", forceUpdate = true))
        assertFalse(coordinator.state.value.decision.isSnoozed)
        assertTrue(coordinator.state.value.decision.showAmbientPrompt)
        assertTrue(coordinator.state.value.decision.showStrongPromptCandidate)
    }

    @Test
    fun minBuildGateDecisionChangesImmediatelySuppressAndRestorePrompts() {
        var gate: RaftMinBuildGateDecision = RaftMinBuildGateDecision.None
        val store = RaftAppUpdatePromptStore(InMemoryRaftKeyValueStore())
        store.recordAppLaunch(v129)
        val coordinator =
            RaftAppUpdateCoordinator(
                store = store,
                now = { 6_000_000L },
                minBuildGateDecision = { gate },
            )
        coordinator.recordAppLaunch(v130, "1.30.0")
        coordinator.publishSnapshot(snapshot(latestCode = v131, latestLabel = "1.31.0", forceUpdate = false))
        assertTrue(coordinator.state.value.decision.showAmbientPrompt)
        assertNotNull(coordinator.state.value.decision.postUpdateNotice)

        gate =
            RaftMinBuildGateDecision.Block(
                threshold = v131,
                latestBuild = v131,
                updateUrl = "https://example.test/raft.apk",
            )
        coordinator.onMinBuildGateDecisionChanged(gate)
        assertFalse(coordinator.state.value.decision.showAmbientPrompt)
        assertFalse(coordinator.state.value.decision.showStrongPromptCandidate)
        assertNull(coordinator.state.value.decision.postUpdateNotice)

        gate = RaftMinBuildGateDecision.None
        coordinator.onMinBuildGateDecisionChanged(gate)
        assertTrue(coordinator.state.value.decision.showAmbientPrompt)
        assertNotNull(coordinator.state.value.decision.postUpdateNotice)
    }
}
