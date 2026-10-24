package build.raft.app.shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentOwnerRegistryTest {
    @Test
    fun retiredScopeMarksStateSynchronouslyBeforeCleanupRuns() {
        val registry = AndroidAttachmentOwnerRegistry()
        val lease = lease("shell", "scope-a", generation = 1L)
        val stale = AtomicBoolean(false)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        registry.bind(lease)
        registry.registerOperation(
            lease,
            "export",
            AndroidAttachmentRetirementHook {
                stale.set(true)
                val cleanup = {
                    cleanupEntered.countDown()
                    releaseCleanup.await()
                }
                cleanup
            }
        ) { Unit }

        val retireThread = thread { registry.retireShell("shell") }
        assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
        assertTrue(stale.get())
        assertFalse(registry.admit(lease))
        releaseCleanup.countDown()
        retireThread.join(2_000L)
        assertFalse(retireThread.isAlive)
    }

    @Test
    fun parkedOldLaunchIsRejectedAndFreshScopeLaunchesOnce() {
        val registry = AndroidAttachmentOwnerRegistry()
        val old = lease("shell", "scope-a", generation = 1L)
        val fresh = lease("shell", "scope-b", generation = 2L)
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val launches = AtomicInteger(0)
        registry.bind(old)
        var oldResult: Boolean? = false
        val oldThread = thread {
            ready.countDown()
            release.await()
            oldResult = registry.runIfLaunchable(old) { launches.incrementAndGet(); true }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        registry.bind(fresh)
        release.countDown()
        oldThread.join(2_000L)

        assertNull(oldResult)
        assertEquals(0, launches.get())
        assertEquals(true, registry.runIfLaunchable(fresh) { launches.incrementAndGet(); true })
        assertEquals(1, launches.get())
    }

    @Test
    fun serverSwitchKeepsTheOldScopesTransfersAliveUntilSignOut() {
        val registry = AndroidAttachmentOwnerRegistry()
        val serverA = lease("shell", "scope-a", generation = 1L)
        val serverB = lease("shell", "scope-b", generation = 2L)
        val invalidated = AtomicInteger(0)
        registry.bind(serverA)
        registry.registerOperation(serverA, "download", AndroidAttachmentRetirementHook { invalidated.incrementAndGet(); null }) { Unit }

        registry.bind(serverB)

        assertEquals("a server switch must not cancel the old server's transfer", 0, invalidated.get())
        assertTrue(registry.admit(serverA))
        assertEquals("published", registry.runIfCurrent(serverA) { "published" })
        assertNull("the old server can no longer launch UI", registry.runIfLaunchable(serverA) { "open" })
        assertTrue(registry.admit(serverB))

        registry.retireShell("shell")

        assertEquals(1, invalidated.get())
        assertFalse(registry.admit(serverA))
        assertFalse(registry.admit(serverB))
    }

    @Test
    fun anotherAccountRetiresTheDetachedScopes() {
        val registry = AndroidAttachmentOwnerRegistry()
        val serverA = lease("shell", "scope-a", authEpoch = 1L, generation = 1L)
        val serverB = lease("shell", "scope-b", authEpoch = 1L, generation = 2L)
        val otherAccount = lease("shell", "scope-c", authEpoch = 2L, generation = 3L)
        registry.bind(serverA)
        registry.bind(serverB)

        registry.bind(otherAccount)

        assertFalse(registry.admit(serverA))
        assertFalse(registry.admit(serverB))
        assertTrue(registry.admit(otherAccount))
    }

    @Test
    fun differentShellScopesRemainIndependent() {
        val registry = AndroidAttachmentOwnerRegistry()
        val a = lease("shell-a", "scope-a", generation = 1L)
        val b = lease("shell-b", "scope-b", generation = 1L)
        registry.bind(a)
        registry.bind(b)
        registry.retireShell("shell-a")

        assertFalse(registry.admit(a))
        assertTrue(registry.admit(b))
    }

    @Test
    fun retiredScopeIdCannotBeReboundWithANewGeneration() {
        val registry = AndroidAttachmentOwnerRegistry()
        val old = lease("shell", "scope-a", generation = 1L)
        registry.bind(old)
        registry.retireShell("shell")

        val attemptedRevival = old.copy(generation = 2L)
        registry.bind(attemptedRevival)

        assertFalse(registry.admit(old))
        assertFalse(registry.admit(attemptedRevival))
    }

    @Test
    fun accountRetirementRejectsLateOldEpochAndAcceptsNextEpoch() {
        val registry = AndroidAttachmentOwnerRegistry()
        val old = lease("shell", "scope-a", authEpoch = 4L, generation = 1L)
        registry.bind(old)
        registry.retireAccount()

        val lateOldEpoch = lease("late-shell", "late-scope", authEpoch = 4L, generation = 1L)
        val nextEpoch = lease("fresh-shell", "fresh-scope", authEpoch = 5L, generation = 1L)
        registry.bind(lateOldEpoch)
        registry.bind(nextEpoch)

        assertFalse(registry.admit(lateOldEpoch))
        assertTrue(registry.admit(nextEpoch))
    }

    private fun lease(shell: String, scope: String, authEpoch: Long = 1L, generation: Long) =
        AndroidAttachmentOwnerLease(shell, scope, authEpoch, "a".repeat(64), generation)
}
