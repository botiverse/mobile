package build.raft.app.shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentExportAuthorityTest {
    @Test
    fun lateCancelFromRetiredOperationCannotRetireSameIdSuccessor() {
        val registry = AndroidAttachmentExportAuthorityRegistry()
        val owner = ownerLease("scope-a", 3L)

        assertTrue(registry.bind(owner, "same-download", "operation-a"))
        assertTrue(registry.retireIfCurrent(owner, "same-download", "operation-a"))
        assertTrue(registry.bind(owner, "same-download", "operation-b"))

        assertFalse(registry.retireIfCurrent(owner, "same-download", "operation-a"))
        assertTrue(registry.isCurrent(owner, "same-download", "operation-b"))
        assertTrue(registry.retireIfCurrent(owner, "same-download", "operation-b"))
        assertFalse(registry.isCurrent(owner, "same-download", "operation-b"))
    }

    @Test
    fun sameOperationIdOnDifferentOwnerCannotCrossCancel() {
        val registry = AndroidAttachmentExportAuthorityRegistry()
        val ownerA = ownerLease("scope-a", 1L)
        val ownerB = ownerLease("scope-b", 1L)

        assertTrue(registry.bind(ownerA, "same-download", "operation"))
        assertTrue(registry.bind(ownerB, "same-download", "operation"))

        assertTrue(registry.retireIfCurrent(ownerA, "same-download", "operation"))
        assertTrue(registry.isCurrent(ownerB, "same-download", "operation"))
    }

    @Test
    fun successorCannotReplaceStillActiveOperationWithoutItsCancel() {
        val registry = AndroidAttachmentExportAuthorityRegistry()
        val owner = ownerLease("scope-a", 1L)

        assertTrue(registry.bind(owner, "same-download", "operation-a"))
        assertFalse(registry.bind(owner, "same-download", "operation-b"))
        assertTrue(registry.isCurrent(owner, "same-download", "operation-a"))
    }

    @Test
    fun successorCannotBindUntilCurrentCleanupFinishes() {
        val registry = AndroidAttachmentExportAuthorityRegistry()
        val owner = ownerLease("scope-a", 1L)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        assertTrue(registry.bind(owner, "same-download", "operation-a"))

        var cancelResult: Boolean? = null
        val cancelThread = thread {
            cancelResult = registry.cancelIfCurrent(owner, "same-download", "operation-a") {
                cleanupEntered.countDown()
                releaseCleanup.await()
            }
        }
        assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))

        val bindEntered = CountDownLatch(1)
        val bindFinished = CountDownLatch(1)
        var bindResult: Boolean? = null
        val bindThread = thread {
            bindEntered.countDown()
            bindResult = registry.bind(owner, "same-download", "operation-b")
            bindFinished.countDown()
        }
        assertTrue(bindEntered.await(2, TimeUnit.SECONDS))
        assertFalse(bindFinished.await(100, TimeUnit.MILLISECONDS))

        releaseCleanup.countDown()
        cancelThread.join(2_000L)
        bindThread.join(2_000L)
        assertFalse(cancelThread.isAlive)
        assertFalse(bindThread.isAlive)
        assertTrue(cancelResult == true)
        assertTrue(bindResult == true)
        assertFalse(registry.cancelIfCurrent(owner, "same-download", "operation-a") {})
        assertTrue(registry.isCurrent(owner, "same-download", "operation-b"))
    }

    @Test
    fun asynchronousCancelOwnsGateBeforeCleanupTaskStarts() {
        val registry = AndroidAttachmentExportAuthorityRegistry()
        val owner = ownerLease("scope-a", 1L)
        val cleanupTask = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>()
        assertTrue(registry.bind(owner, "same-download", "operation-a"))

        assertTrue(
            registry.cancelIfCurrent(
                owner = owner,
                downloadId = "same-download",
                operationId = "operation-a",
                dispatch = cleanupTask::set,
                cleanup = {}
            )
        )
        val bindFinished = CountDownLatch(1)
        var successorBound = false
        val bindThread = thread {
            successorBound = registry.bind(owner, "same-download", "operation-b")
            bindFinished.countDown()
        }
        assertFalse(bindFinished.await(100, TimeUnit.MILLISECONDS))

        cleanupTask.get()?.invoke()
        bindThread.join(2_000L)

        assertFalse(bindThread.isAlive)
        assertTrue(successorBound)
        assertTrue(registry.isCurrent(owner, "same-download", "operation-b"))
    }

    private fun ownerLease(scopeId: String, generation: Long) =
        AndroidAttachmentOwnerLease(
            shellInstanceId = "shell",
            scopeId = scopeId,
            authEpoch = 1L,
            principalKey = "a".repeat(64),
            generation = generation
        )
}
