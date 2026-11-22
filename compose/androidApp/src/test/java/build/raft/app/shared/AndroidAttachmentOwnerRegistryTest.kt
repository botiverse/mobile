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

    private fun lease(shell: String, scope: String, authEpoch: Long = 1L, generation: Long) =
        AndroidAttachmentOwnerLease(shell, scope, authEpoch, "a".repeat(64), generation)
}
