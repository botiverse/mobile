package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SecondFactorRecovery
import build.raft.app.data.SecondFactorRecoveryDataSource
import build.raft.app.data.SecondFactorRecoveryStatus
import build.raft.app.data.SharedSecondFactorRecoveryEffect
import build.raft.app.data.SharedSecondFactorRecoveryIntent
import build.raft.app.data.SharedSecondFactorRecoveryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pending two-step verification reset in the Home notification center (web slock #12295
 * `useSecondFactorRecoveryNotification`; server view as pinned in SecondFactorRecoveryApiTest,
 * staging c3e62c2ac990765dd26d72cb3c6450feb54f60f8).
 */
class HomeSecondFactorRecoveryTest {
    private val waiting =
        SecondFactorRecovery(
            status = SecondFactorRecoveryStatus.Waiting,
            requestedAt = "2026-11-01T00:10:00.000Z",
            eligibleAt = "2026-11-04T00:10:00.000Z",
            expiresAt = "2026-11-11T00:10:00.000Z",
            setPasswordFirst = false,
        )
    private val copy =
        SecondFactorRecoveryNoticeCopy(
            waitingTitle = "Reset requested",
            waitingBody = "Waiting until T.",
            readyTitle = "Reset ready",
            readyBody = "Ready until T.",
            setPassword = "Set a password first.",
            cancel = "Cancel Reset",
        )

    private class FakeRecoveryDataSource(var pending: DataResult<SecondFactorRecovery?>) : SecondFactorRecoveryDataSource {
        var reads = 0
        var cancels = 0
        var readGate: CompletableDeferred<Unit>? = null
        var cancelResult: DataResult<Boolean> = DataResult.Content(true)

        override suspend fun pendingRecovery(): DataResult<SecondFactorRecovery?> {
            reads += 1
            readGate?.await()
            return pending
        }

        override suspend fun cancelRecovery(): DataResult<Boolean> {
            cancels += 1
            return cancelResult
        }
    }

    @Test
    fun aWaitingResetIsASecurityEntryThatPutsTheShieldOnTheBell() = runTest {
        val dataSource = FakeRecoveryDataSource(DataResult.Content(waiting))
        val store = SharedSecondFactorRecoveryStore(dataSource, backgroundScope)

        store.dispatch(SharedSecondFactorRecoveryIntent.Refresh)
        runCurrent()

        assertEquals(waiting, store.state.value.recovery)
        val item = waiting.toHomeNotificationItem(copy, cancelling = false)
        assertEquals(SECOND_FACTOR_RECOVERY_HOME_NOTIFICATION_ID, item.id)
        assertEquals("Reset requested", item.title)
        assertEquals("Waiting until T.", item.body)
        assertEquals("Cancel Reset", item.actionLabel)
        assertEquals(RaftHomeNotificationKind.Warning, item.kind)
        assertEquals(RaftHomeNotificationIcon.ShieldAlert, item.icon)
        assertTrue(item.security)
        assertTrue(item.actionEnabled)
        val others = listOf(RaftHomeNotificationItem(id = "joint-invite:1", title = "Invitation"))
        assertTrue((listOf(item) + others).visibleHomeNotifications().hasHomeSecurityAlert())
        assertFalse(others.visibleHomeNotifications().hasHomeSecurityAlert())
    }

    @Test
    fun aReadyResetSaysUntilWhenAndTheSetPasswordStep() {
        val ready = waiting.copy(status = SecondFactorRecoveryStatus.Ready, setPasswordFirst = true)

        val item = ready.toHomeNotificationItem(copy, cancelling = true)

        assertEquals("Reset ready", item.title)
        assertEquals("Ready until T. Set a password first.", item.body)
        assertEquals("2026-11-11T00:10:00.000Z", ready.noticeTime)
        assertEquals("2026-11-04T00:10:00.000Z", waiting.noticeTime)
        assertFalse(item.actionEnabled)
    }

    @Test
    fun cancelDeletesAndTheEntryGoes() = runTest {
        val dataSource = FakeRecoveryDataSource(DataResult.Content(waiting))
        val store = SharedSecondFactorRecoveryStore(dataSource, backgroundScope)
        store.dispatch(SharedSecondFactorRecoveryIntent.Refresh)
        runCurrent()

        store.dispatch(SharedSecondFactorRecoveryIntent.Cancel)
        assertTrue(store.state.value.cancelling)
        // A second tap while it runs sends nothing more.
        store.dispatch(SharedSecondFactorRecoveryIntent.Cancel)
        runCurrent()

        assertEquals(1, dataSource.cancels)
        assertNull(store.state.value.recovery)
        assertFalse(store.state.value.cancelling)
        assertEquals(SharedSecondFactorRecoveryEffect.Cancelled, store.state.value.effect)
        store.dispatch(SharedSecondFactorRecoveryIntent.ConsumeEffect)
        assertNull(store.state.value.effect)
    }

    @Test
    fun aFailedCancelKeepsTheEntry() = runTest {
        val dataSource = FakeRecoveryDataSource(DataResult.Content(waiting))
        dataSource.cancelResult = DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        val store = SharedSecondFactorRecoveryStore(dataSource, backgroundScope)
        store.dispatch(SharedSecondFactorRecoveryIntent.Refresh)
        runCurrent()

        store.dispatch(SharedSecondFactorRecoveryIntent.Cancel)
        runCurrent()

        assertEquals(waiting, store.state.value.recovery)
        assertEquals(SharedSecondFactorRecoveryEffect.CancelFailed, store.state.value.effect)
    }

    @Test
    fun noRequestShowsNothingAndAFailedReadKeepsWhatIsShown() = runTest {
        val dataSource = FakeRecoveryDataSource(DataResult.Content(null))
        val store = SharedSecondFactorRecoveryStore(dataSource, backgroundScope)
        store.dispatch(SharedSecondFactorRecoveryIntent.Refresh)
        runCurrent()
        assertNull(store.state.value.recovery)
        // Nothing pending: Cancel has nothing to do.
        store.dispatch(SharedSecondFactorRecoveryIntent.Cancel)
        runCurrent()
        assertEquals(0, dataSource.cancels)

        dataSource.pending = DataResult.Content(waiting)
        store.dispatch(SharedSecondFactorRecoveryIntent.Refresh)
        runCurrent()
        dataSource.pending = DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        store.dispatch(SharedSecondFactorRecoveryIntent.Refresh)
        runCurrent()
        assertEquals(waiting, store.state.value.recovery)
    }

    @Test
    fun readsAreSingleFlightAndACancelOutdatesOneInFlight() = runTest {
        val dataSource = FakeRecoveryDataSource(DataResult.Content(waiting))
        val store = SharedSecondFactorRecoveryStore(dataSource, backgroundScope)
        store.dispatch(SharedSecondFactorRecoveryIntent.Refresh)
        runCurrent()

        val gate = CompletableDeferred<Unit>()
        dataSource.readGate = gate
        // Foreground and opening the center at once: one read.
        store.dispatch(SharedSecondFactorRecoveryIntent.Refresh)
        store.dispatch(SharedSecondFactorRecoveryIntent.Refresh)
        runCurrent()
        assertEquals(2, dataSource.reads)

        store.dispatch(SharedSecondFactorRecoveryIntent.Cancel)
        gate.complete(Unit)
        runCurrent()

        assertNull(store.state.value.recovery, "the read that started before Cancel must not bring the entry back")
        assertEquals(1, dataSource.cancels)
    }
}
