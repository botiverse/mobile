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
    fun currentServerSelectionNormalizesOneServerAndClearsOnBlankInput() {
        assertEquals(
            AndroidCurrentRealtimeServerSelection(
                serverId = ServerId("server-a"),
                normalizedServerCount = 1,
                rejectedMultipleServers = false
            ),
            selectAndroidCurrentRealtimeServer(setOf(" server-a ", "server-a"))
        )
        assertEquals(
            AndroidCurrentRealtimeServerSelection(
                serverId = null,
                normalizedServerCount = 0,
                rejectedMultipleServers = false
            ),
            selectAndroidCurrentRealtimeServer(setOf("", "  "))
        )
    }

    @Test
    fun currentServerSelectionRejectsMultipleServersWithoutChoosingOne() {
        assertEquals(
            AndroidCurrentRealtimeServerSelection(
                serverId = null,
                normalizedServerCount = 2,
                rejectedMultipleServers = true
            ),
            selectAndroidCurrentRealtimeServer(setOf("server-b", "server-a"))
        )
    }

    @Test
    fun visibleShellsOnlyBackgroundAfterLastOwnerHides() {
        val owners = AndroidVisibleShellOwners()

        assertTrue(owners.shown("shell-a"))
        assertFalse(owners.shown("shell-a"))
        assertFalse(owners.shown("shell-b"))
        assertEquals(setOf("shell-a", "shell-b"), owners.snapshot())

        assertFalse(owners.hidden("shell-a"))
        assertEquals(setOf("shell-b"), owners.snapshot())
        assertTrue(owners.hidden("shell-b"))
        assertTrue(owners.snapshot().isEmpty())
        assertFalse(owners.hidden("shell-b"))
    }

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
    fun replaceGraceReacquireDoesNotDisconnectOrRecreate() {
        val fixture = Fixture()
        val first = fixture.registry.acquire(ServerId("server-a"), "shell-a")

        assertFalse(
            fixture.registry.release(
                ServerId("server-a"),
                "shell-a",
                AndroidRealtimeOwnerReleaseMode.ReplaceGrace
            )
        )
        assertTrue(fixture.snapshot("server-a").releasePending)

        val reacquired = fixture.registry.acquire(ServerId("server-a"), "shell-a")
        fixture.scheduler.runAll()

        assertSame(first, reacquired)
        assertTrue(fixture.disconnected.isEmpty())
        assertEquals(1, fixture.created.size)
        assertFalse(fixture.snapshot("server-a").releasePending)
    }

    @Test
    fun finalExplicitCloseDisconnectsOnlyItsServer() {
        val fixture = Fixture()
        fixture.registry.acquire(ServerId("server-a"), "shell-a")
        fixture.registry.acquire(ServerId("server-b"), "shell-b")

        assertTrue(
            fixture.registry.release(
                ServerId("server-a"),
                "shell-a",
                AndroidRealtimeOwnerReleaseMode.ExplicitClose
            )
        )

        assertEquals(listOf("server-a:last_owner_explicit_close"), fixture.disconnected)
        assertEquals(null, fixture.registry.runtime(ServerId("server-a")))
        assertTrue(fixture.registry.runtime(ServerId("server-b")) != null)
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
    fun sharedServerDisconnectsOnlyAfterLastDistinctOwner() {
        val fixture = Fixture()
        fixture.registry.acquire(ServerId("server-a"), "shell-a")
        fixture.registry.acquire(ServerId("server-a"), "shell-b")

        assertFalse(
            fixture.registry.release(
                ServerId("server-a"),
                "shell-a",
                AndroidRealtimeOwnerReleaseMode.ExplicitClose
            )
        )
        assertTrue(fixture.disconnected.isEmpty())
        assertEquals(setOf("shell-b"), fixture.snapshot("server-a").owners)

        assertTrue(
            fixture.registry.release(
                ServerId("server-a"),
                "shell-b",
                AndroidRealtimeOwnerReleaseMode.ExplicitClose
            )
        )
        assertEquals(listOf("server-a:last_owner_explicit_close"), fixture.disconnected)
    }

    @Test
    fun multiSessionAddingServerKeepsExistingRuntime() {
        val fixture = Fixture()
        val first = fixture.reconcile("server-a")
        val runtimeA = first.desired.single().runtime

        val second = fixture.reconcile("server-a", "server-b")

        assertEquals(listOf(ServerId("server-a"), ServerId("server-b")), second.desired.map { it.serverId })
        assertSame(runtimeA, second.desired.first().runtime)
        assertFalse(second.desired.first().created)
        assertTrue(second.desired.last().created)
        assertEquals(setOf(ServerId("server-b")), second.added)
        assertTrue(second.released.isEmpty())
        assertEquals(2, fixture.created.size)
        assertTrue(fixture.disconnected.isEmpty())
    }

    @Test
    fun multiSessionDroppedServerDisconnectsOnlyAfterUndesiredGrace() {
        val fixture = Fixture()
        fixture.reconcile("server-a", "server-b")

        val result = fixture.reconcile("server-b")

        assertEquals(setOf(ServerId("server-a")), result.released)
        assertEquals(listOf(MultiSessionGraceMillis), fixture.scheduler.pendingDelays())
        assertTrue(fixture.disconnected.isEmpty())
        assertTrue(fixture.snapshot("server-a").releasePending)
        assertTrue(fixture.snapshot("server-a").owners.isEmpty())

        fixture.scheduler.runAll()

        assertEquals(listOf("server-a:undesired_grace_elapsed"), fixture.disconnected)
        assertEquals(null, fixture.registry.runtime(ServerId("server-a")))
        assertTrue(fixture.registry.runtime(ServerId("server-b")) != null)
    }

    @Test
    fun lastWindowClosedReleasesItsServerOnlyAfterTheGrace() {
        val fixture = Fixture()
        fixture.reconcile("server-a", "server-b").desired.last().runtime.cursor = 11L
        // The last window on server-b closes: shared leaves it out of the set.
        fixture.reconcile("server-a")
        assertTrue("no disconnect before the grace", fixture.disconnected.isEmpty())

        // No window left at all: an empty set releases the remaining server with the same grace, no clear-all.
        val empty = fixture.reconcile()

        assertEquals(setOf(ServerId("server-a")), empty.released)
        assertTrue(empty.desired.isEmpty())
        assertEquals(listOf(MultiSessionGraceMillis, MultiSessionGraceMillis), fixture.scheduler.pendingDelays())
        assertTrue(fixture.disconnected.isEmpty())
        assertTrue(fixture.snapshot("server-a").releasePending)

        fixture.scheduler.runAll()

        assertEquals(
            listOf("server-b:undesired_grace_elapsed", "server-a:undesired_grace_elapsed"),
            fixture.disconnected
        )
        assertEquals(mapOf(ServerId("server-b") to 11L), fixture.registry.parkedResumeSeqs())
    }

    @Test
    fun multiSessionReaddWithinGraceKeepsRuntimeWithoutRecreate() {
        val fixture = Fixture()
        val runtimeA = fixture.reconcile("server-a", "server-b").desired.first().runtime
        fixture.reconcile("server-b")

        val readded = fixture.reconcile("server-a", "server-b")
        fixture.scheduler.runAll()

        assertSame(runtimeA, readded.desired.first().runtime)
        assertFalse(readded.desired.first().created)
        assertTrue(readded.added.isEmpty())
        assertEquals(2, fixture.created.size)
        assertTrue(fixture.disconnected.isEmpty())
        assertFalse(fixture.snapshot("server-a").releasePending)
        assertEquals(setOf(MultiSessionOwner), fixture.snapshot("server-a").owners)
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
    fun multiSessionUndesiredRuntimeWithoutCursorIsNotParked() {
        val fixture = Fixture()
        fixture.reconcile("server-a", "server-b")
        fixture.reconcile("server-b")
        fixture.scheduler.runAll()

        assertEquals(null, fixture.registry.parkedResumeSeq(ServerId("server-a")))
        assertEquals(null, fixture.reconcile("server-a", "server-b").desired.first().parkedResumeSeq)
    }

    @Test
    fun serverTerminalDisconnectClearsThatServersParkedSeq() {
        val fixture = Fixture()
        val desired = fixture.reconcile("server-a", "server-b", "server-c").desired
        desired[0].runtime.cursor = 11L
        desired[1].runtime.cursor = 22L
        fixture.reconcile("server-c")
        fixture.scheduler.runAll()

        assertTrue(fixture.registry.disconnectServer(ServerId("server-c"), "server_removed"))
        assertFalse(fixture.registry.disconnectServer(ServerId("server-a"), "server_removed"))

        assertEquals(null, fixture.registry.parkedResumeSeq(ServerId("server-a")))
        assertEquals(22L, fixture.registry.parkedResumeSeq(ServerId("server-b")))
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

    @Test
    fun multiSessionReleaseLeavesServersHeldByOtherOwners() {
        val fixture = Fixture()
        fixture.reconcile("server-a")
        fixture.registry.acquire(ServerId("server-a"), "shell-a")

        val result = fixture.reconcile("server-b")
        fixture.scheduler.runAll()

        assertTrue(result.released.isEmpty())
        assertTrue(fixture.disconnected.isEmpty())
        assertEquals(setOf("shell-a"), fixture.snapshot("server-a").owners)
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
