package build.raft.app.shared

import build.raft.app.model.ServerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRealtimeSessionRegistryTest {
    @Test
    fun duplicateAcquireIsIdempotentAndServersStayIndependent() {
        val fixture = Fixture()

        val firstA = fixture.registry.acquire(ServerId("server-a"), "shell-a")
        val secondA = fixture.registry.acquire(ServerId("server-a"), "shell-a")
        val runtimeB = fixture.registry.acquire(ServerId("server-b"), "shell-b")
        firstA.cursor = 11L
        firstA.joinedChannels += "channel-a"
        runtimeB.cursor = 22L
        runtimeB.joinedChannels += "channel-b"

        assertSame(firstA, secondA)
        assertNotSame(firstA, runtimeB)
        assertEquals(2, fixture.created.size)
        assertEquals(11L, firstA.cursor)
        assertEquals(setOf("channel-a"), firstA.joinedChannels)
        assertEquals(22L, runtimeB.cursor)
        assertEquals(setOf("channel-b"), runtimeB.joinedChannels)
        assertEquals(setOf("shell-a"), fixture.snapshot("server-a").owners)
    }

    @Test
    fun serverSwitchDisconnectsOldRuntimeBeforeCreatingNewRuntime() {
        val events = mutableListOf<String>()
        val registry =
            AndroidRealtimeSessionRegistry(
                createRuntime = { serverId ->
                    events += "create:${serverId.value}"
                    FakeRuntime(serverId)
                },
                disconnectRuntime = { serverId, _, _ -> events += "disconnect:${serverId.value}" },
                releaseScheduler = ManualScheduler(),
                replaceGraceMillis = 1_000L
            )

        registry.acquire(ServerId("server-a"), "process")
        registry.acquireCurrent(
            serverId = ServerId("server-b"),
            shellInstanceId = "process",
            disconnectReason = "server_switch"
        )

        assertEquals(
            listOf("create:server-a", "disconnect:server-a", "create:server-b"),
            events
        )
        assertEquals(listOf(ServerId("server-b")), registry.snapshots().map { it.serverId })
    }

    @Test
    fun multiSessionParkedResumeSeqIsReusedOnNextAcquire() {
        val fixture = Fixture()
        fixture.reconcile("server-a", "server-b").desired.first().runtime.cursor = 42L
        fixture.reconcile("server-b")
        fixture.scheduler.runAll()

        assertEquals(42L, fixture.registry.parkedResumeSeq(ServerId("server-a")))
        assertEquals(setOf(ServerId("server-a")), fixture.reconcile("server-b").parked)

        val returned = fixture.reconcile("server-a", "server-b")

        val desiredA = returned.desired.first()
        assertTrue(desiredA.created)
        assertEquals(42L, desiredA.parkedResumeSeq)
        assertEquals(null, returned.desired.last().parkedResumeSeq)
        assertTrue(returned.parked.isEmpty())
        assertEquals(null, fixture.registry.parkedResumeSeq(ServerId("server-a")))
    }

    @Test
    fun processWideDisconnectClearsEveryParkedSeq() {
        val fixture = Fixture()
        val desired = fixture.reconcile("server-a", "server-b", "server-c").desired
        desired[0].runtime.cursor = 11L
        desired[1].runtime.cursor = 22L
        fixture.reconcile("server-c")
        fixture.scheduler.runAll()

        fixture.registry.disconnectAll("process_identity_replaced")

        assertEquals(null, fixture.registry.parkedResumeSeq(ServerId("server-a")))
        assertEquals(null, fixture.registry.parkedResumeSeq(ServerId("server-b")))
        assertTrue(fixture.registry.snapshots().isEmpty())
        val again = fixture.reconcile("server-a")
        assertTrue(again.desired.single().created)
        assertEquals(null, again.desired.single().parkedResumeSeq)
    }

    private class Fixture {
        val created = mutableListOf<FakeRuntime>()
        val disconnected = mutableListOf<String>()
        val scheduler = ManualScheduler()
        val registry =
            AndroidRealtimeSessionRegistry(
                createRuntime = { serverId -> FakeRuntime(serverId).also(created::add) },
                disconnectRuntime = { serverId, _, reason -> disconnected += "${serverId.value}:$reason" },
                releaseScheduler = scheduler,
                replaceGraceMillis = 1_000L,
                resumeSeqOf = FakeRuntime::cursor
            )

        fun reconcile(vararg serverIds: String): AndroidRealtimeDesiredReconcileResult<FakeRuntime> =
            registry.reconcileDesired(
                desired = serverIds.mapTo(linkedSetOf(), ::ServerId),
                ownerId = MultiSessionOwner,
                undesiredGraceMillis = MultiSessionGraceMillis
            )

        fun snapshot(serverId: String): AndroidRealtimeSessionRegistrySnapshot =
            registry.snapshots().single { it.serverId == ServerId(serverId) }
    }

    private data class FakeRuntime(
        val serverId: ServerId,
        var cursor: Long = 0L,
        val joinedChannels: MutableSet<String> = linkedSetOf()
    )

    private class ManualScheduler : AndroidRealtimeReleaseScheduler {
        private val pending = mutableListOf<Pending>()

        override fun schedule(delayMillis: Long, block: () -> Unit): AndroidRealtimePendingRelease =
            Pending(delayMillis, block).also(pending::add)

        fun pendingDelays(): List<Long> = pending.filterNot { it.canceled }.map { it.delayMillis }

        fun runAll() {
            pending.toList().also { pending.clear() }.forEach { it.run() }
        }

        private class Pending(val delayMillis: Long, private val block: () -> Unit) : AndroidRealtimePendingRelease {
            var canceled = false
                private set

            override fun cancel() {
                canceled = true
            }

            fun run() {
                if (!canceled) block()
            }
        }
    }

    private companion object {
        const val MultiSessionOwner = "process-realtime-host-owner"
        const val MultiSessionGraceMillis = 10_000L
    }
}
