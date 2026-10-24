package build.raft.app.shared

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentDirectDownloadOwnerOperationTest {
    @Test
    fun retireDuringStalledTransferCancelsExactAttemptAndUnblocksWaiter() {
        val registry = AndroidAttachmentOwnerRegistry()
        val lease = lease()
        val transferTerminal = CountDownLatch(1)
        val cancelledToken = AtomicReference<String?>()
        registry.bind(lease)
        val operation =
            AndroidAttachmentDirectDownloadOwnerOperation(
                registry = registry,
                owner = lease,
                operationId = "direct-download:download-1",
                cleanupAttempt = { token ->
                    cancelledToken.set(token)
                    transferTerminal.countDown()
                }
            )
        assertTrue(operation.start { "attempt-1" })
        val waiterReleased = CountDownLatch(1)
        val waiter =
            thread {
                transferTerminal.await()
                waiterReleased.countDown()
            }

        registry.retireShell(lease.shellInstanceId)

        assertTrue(waiterReleased.await(2, TimeUnit.SECONDS))
        waiter.join(2_000L)
        assertFalse(waiter.isAlive)
        assertEquals("attempt-1", cancelledToken.get())
        assertNull(operation.finishIfCurrent { "ready" })
    }

    @Test
    fun retireAfterFinalProgressBeforeReadyDeletesStaleTargetAndRejectsOpen() {
        val registry = AndroidAttachmentOwnerRegistry()
        val lease = lease()
        val target = File.createTempFile("direct-owner-terminal", ".apk")
        target.writeText("published bytes")
        registry.bind(lease)
        val operation =
            AndroidAttachmentDirectDownloadOwnerOperation(
                registry = registry,
                owner = lease,
                operationId = "direct-download:download-1",
                cleanupAttempt = { token ->
                    if (token == "attempt-1") target.delete()
                }
            )
        assertTrue(operation.start { "attempt-1" })

        registry.retireShell(lease.shellInstanceId)

        assertNull(operation.finishIfCurrent { "ready" })
        assertFalse(target.exists())
        assertNull(registry.runIfCurrent(lease) { "install" })
    }

    @Test
    fun serverSwitchDuringTransferLetsTheDownloadRunAndComplete() {
        val registry = AndroidAttachmentOwnerRegistry()
        val serverA = lease()
        val serverB = serverA.copy(scopeId = "scope-server-b", principalKey = "b".repeat(64), generation = 2L)
        val cancelledToken = AtomicReference<String?>()
        registry.bind(serverA)
        val operation =
            AndroidAttachmentDirectDownloadOwnerOperation(
                registry = registry,
                owner = serverA,
                operationId = "direct-download:download-1",
                cleanupAttempt = cancelledToken::set
            )
        assertTrue(operation.start { "attempt-1" })

        // The user picks another server of the same account while the bytes are still coming.
        registry.bind(serverB)

        assertNull("the switch must not cancel the transfer", cancelledToken.get())
        assertTrue("progress samples stay admitted", registry.admit(serverA))
        assertEquals("ready", operation.finishIfCurrent { "ready" })
        assertNull(cancelledToken.get())
    }

    private fun lease() =
        AndroidAttachmentOwnerLease(
            shellInstanceId = "shell",
            scopeId = "scope",
            authEpoch = 1L,
            principalKey = "a".repeat(64),
            generation = 1L
        )
}
