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

    private class Fixture {
        val created = mutableListOf<FakeRuntime>()
        val disconnected = mutableListOf<String>()
        val scheduler = ManualScheduler()
        val registry =
            AndroidRealtimeSessionRegistry(
                createRuntime = { serverId -> FakeRuntime(serverId).also(created::add) },
                disconnectRuntime = { serverId, _, reason -> disconnected += "${serverId.value}:$reason" },
                releaseScheduler = scheduler,
                replaceGraceMillis = 1_000L
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
            Pending(block).also(pending::add)

        fun runAll() {
            pending.toList().also { pending.clear() }.forEach { it.run() }
        }

        private class Pending(private val block: () -> Unit) : AndroidRealtimePendingRelease {
            private var canceled = false

            override fun cancel() {
                canceled = true
            }

            fun run() {
                if (!canceled) block()
            }
        }
    }
}
