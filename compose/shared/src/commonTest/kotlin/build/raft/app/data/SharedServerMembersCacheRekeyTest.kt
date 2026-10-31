package build.raft.app.data

import build.raft.app.model.AvatarReference
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Data-layer plan step 3 (members): rosters are cached per environment + account + server, seeded
 * from the durable store on a cold start, fenced only by account / environment changes, evicted on
 * lost access and capped (Tenny rules 1-3).
 */
class SharedServerMembersCacheRekeyTest {
    @Test
    fun switchingServersAToBToAPaintsCachedMembersOnFirstFrameAndRefreshesOnceInBackground() = runTest {
        val harness = Harness(this)
        harness.rosters[SERVER_A] = listOf(human("a1", "Ada"), agent("a2", "Atlas", AgentStatus.Working))
        harness.rosters[SERVER_B] = listOf(human("b1", "Bea"))
        val store = harness.store()

        assertIs<DataResult.Content<Unit>>(store.refreshMembers(ServerId(SERVER_A)))
        assertIs<DataResult.Content<Unit>>(store.refreshMembers(ServerId(SERVER_B)))
        assertEquals(1, harness.calls(SERVER_A))

        // Back on A: the first frame reads the store's current value — no empty list, names and
        // the agent status are there before any refresh.
        val firstFrame = store.currentServerMembersEntry(ServerId(SERVER_A))
        assertEquals(listOf("Ada", "Atlas"), firstFrame.members.map { it.displayName })
        assertEquals(SharedServerMembersLoadStatus.Loaded, firstFrame.status)
        assertEquals(listOf("a1", "a2"), store.currentServerMembersEntry(harness.routeOwner(SERVER_A)).members.map { it.id.value })
        assertEquals(AgentStatus.Working, store.currentMemberStatus(ServerId(SERVER_A), "a2")?.status)

        // One background revalidation (the realtime rebaseline forces it); the page's own
        // background refresh joins it, and the roster never flips away from Loaded meanwhile.
        harness.gates[SERVER_A] = CompletableDeferred()
        val rebaseline =
            async {
                store.refreshMembers(
                    ServerId(SERVER_A),
                    priority = DataRequestPriority.Background,
                    force = true,
                    reason = "presence_transport_connected"
                )
            }
        runCurrent()
        val pageMount = async { store.refreshMembers(ServerId(SERVER_A), DataRequestPriority.Background) }
        runCurrent()
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.currentServerMembersEntry(ServerId(SERVER_A)).status)
        assertEquals(2, store.currentServerMembersEntry(ServerId(SERVER_A)).members.size)
        harness.rosters[SERVER_A] = listOf(human("a1", "Ada Lovelace"), agent("a2", "Atlas", AgentStatus.Idle))
        harness.gates.remove(SERVER_A)?.complete(Unit)
        assertIs<DataResult.Content<Unit>>(rebaseline.await())
        assertIs<DataResult.Content<Unit>>(pageMount.await())

        assertEquals(2, harness.calls(SERVER_A), "exactly one background fetch after returning to A")
        assertEquals("Ada Lovelace", store.currentMemberProfile(ServerId(SERVER_A), "a1")?.displayName)
    }

    @Test
    fun coldStartSeedsFromDurableStoreThenRefreshesOnceInBackground() = runTest {
        val storage = MapDurableStorage()
        val firstProcess = Harness(this, storage)
        firstProcess.rosters[SERVER_A] =
            listOf(
                human("a1", "Ada", avatarUrl = "https://cdn.example/ada.png").copy(presence = Presence.Active),
                agent("a2", "Atlas", AgentStatus.Working)
            )
        assertIs<DataResult.Content<Unit>>(firstProcess.store().refreshMembers(ServerId(SERVER_A)))
        assertEquals(1, firstProcess.calls(SERVER_A))

        // New process: empty memory, empty process core, same SQLite rows.
        val secondProcess = Harness(this, storage)
        secondProcess.rosters[SERVER_A] = listOf(human("a1", "Ada"), agent("a2", "Atlas", AgentStatus.Idle))
        val store = secondProcess.store()
        assertTrue(store.currentServerMembersEntry(ServerId(SERVER_A)).members.isEmpty())

        secondProcess.gates[SERVER_A] = CompletableDeferred()
        val refresh = async { store.refreshMembers(ServerId(SERVER_A), DataRequestPriority.Background) }
        runCurrent()

        // Painted from disk while the network is still out: names, avatar, agent status.
        val seeded = store.currentServerMembersEntry(ServerId(SERVER_A))
        assertEquals(listOf("Ada", "Atlas"), seeded.members.map { it.displayName })
        assertTrue(seeded.seededFromDurable)
        assertEquals(SharedServerMembersLoadStatus.Loaded, seeded.status)
        assertEquals("https://cdn.example/ada.png", store.currentMemberProfile(ServerId(SERVER_A), "a1")?.avatar?.wireValue)
        assertEquals(Presence.Offline, seeded.members.first().presence, "presence is never persisted")
        assertEquals(AgentStatus.Working, store.currentMemberStatus(ServerId(SERVER_A), "a2")?.status)
        assertEquals(1, secondProcess.calls(SERVER_A), "the durable seed does not satisfy the load")

        secondProcess.gates.remove(SERVER_A)?.complete(Unit)
        assertIs<DataResult.Content<Unit>>(refresh.await())
        val refreshed = store.currentServerMembersEntry(ServerId(SERVER_A))
        assertFalse(refreshed.seededFromDurable)
        assertEquals(AgentStatus.Idle, store.currentMemberStatus(ServerId(SERVER_A), "a2")?.status)

        // A further visible refresh is served from the fresh process snapshot: one fetch in total.
        assertIs<DataResult.Content<Unit>>(store.refreshMembers(ServerId(SERVER_A)))
        assertEquals(1, secondProcess.calls(SERVER_A))
    }

    @Test
    fun bindingTimeSeedPaintsBeforeAnyPageRefreshAndNeverOverridesNetworkData() = runTest {
        val storage = MapDurableStorage()
        val firstProcess = Harness(this, storage)
        firstProcess.rosters[SERVER_A] = listOf(human("a1", "Ada"))
        firstProcess.store().refreshMembers(ServerId(SERVER_A))

        val secondProcess = Harness(this, storage)
        val store = secondProcess.store()
        assertTrue(store.hydrateFromDurable(ServerId(SERVER_A)))
        assertEquals(listOf("Ada"), store.currentServerMembersEntry(ServerId(SERVER_A)).members.map { it.displayName })
        assertFalse(store.hydrateFromDurable(ServerId(SERVER_A)), "memory already holds the roster")

        // Another account never reads account-a's row.
        val otherAccount = Harness(this, storage, accountId = "account-b")
        assertFalse(otherAccount.store().hydrateFromDurable(ServerId(SERVER_A)))
    }

    @Test
    fun lateResponseAfterAccountChangeIsDiscarded() = runTest {
        val harness = Harness(this)
        harness.rosters[SERVER_A] = listOf(human("a1", "Ada"))
        val store = harness.store()
        harness.gates[SERVER_A] = CompletableDeferred()
        val late = async { store.refreshMembers(ServerId(SERVER_A)) }
        runCurrent()

        harness.switchAccount("account-b")
        store.retireAccountData()
        harness.gates.remove(SERVER_A)?.complete(Unit)

        val result = late.await()
        assertIs<DataResult.Error>(result)
        assertEquals("members_owner_stale", result.error.code)
        assertTrue(store.currentServerMembersEntry(ServerId(SERVER_A)).members.isEmpty())
        assertNull(store.currentMemberProfile(ServerId(SERVER_A), "a1"))
        assertNull(harness.durable.read(harness.durableKey(SERVER_A, "account-a")))
        assertNull(harness.durable.read(harness.durableKey(SERVER_A, "account-b")))
    }

    @Test
    fun accountRetireDropsPredecessorRostersButKeepsTheSuccessors() = runTest {
        val harness = Harness(this)
        harness.rosters[SERVER_A] = listOf(human("a1", "Ada"))
        harness.rosters[SERVER_B] = listOf(human("b1", "Bea"))
        val store = harness.store()
        store.refreshMembers(ServerId(SERVER_A))

        harness.switchAccount("account-b")
        store.refreshMembers(ServerId(SERVER_B))
        store.retireAccountData(
            successorEnvironmentId = ENVIRONMENT,
            successorAccountId = "account-b",
            successorWriteGeneration = harness.currentWriteGeneration
        )

        assertTrue(store.currentServerMembersEntry(ServerId(SERVER_A)).members.isEmpty())
        assertEquals(listOf("b1"), store.currentServerMembersEntry(ServerId(SERVER_B)).members.map { it.id.value })
        // Account-a's durable row stays keyed by its owner for account-a's next session.
        assertNotNull(harness.durable.read(harness.durableKey(SERVER_A, "account-a")))
    }

    @Test
    fun durableWriteCapturedBeforeSignOutIsFenced() = runTest {
        val harness = Harness(this)
        val epoch = harness.durable.epoch
        harness.durable.clearAll()
        val written =
            harness.durable.write(
                harness.durableKey(SERVER_A),
                MemberSnapshot(server(SERVER_A), listOf(human("a1", "Ada"))),
                expectedEpoch = epoch
            )
        assertFalse(written)
        assertNull(harness.durable.read(harness.durableKey(SERVER_A)))
    }

    @Test
    fun lateResponseAfterServerSwitchIsWrittenToItsOriginalServer() = runTest {
        val harness = Harness(this)
        harness.rosters[SERVER_A] = listOf(human("a1", "Ada"))
        harness.rosters[SERVER_B] = listOf(human("b1", "Bea"))
        val store = harness.store()
        harness.gates[SERVER_A] = CompletableDeferred()
        val late = async { store.refreshMembers(ServerId(SERVER_A)) }
        runCurrent()

        // The user switched to B (and the token rotated: same account binding) meanwhile.
        assertIs<DataResult.Content<Unit>>(store.refreshMembers(ServerId(SERVER_B)))
        harness.gates.remove(SERVER_A)?.complete(Unit)

        assertIs<DataResult.Content<Unit>>(late.await())
        assertEquals(listOf("a1"), store.currentServerMembersEntry(ServerId(SERVER_A)).members.map { it.id.value })
        assertEquals(listOf("b1"), store.currentServerMembersEntry(ServerId(SERVER_B)).members.map { it.id.value })
        assertEquals(listOf("a1"), harness.durable.read(harness.durableKey(SERVER_A))?.members?.map { it.id.value })
        assertEquals(listOf("b1"), harness.durable.read(harness.durableKey(SERVER_B))?.members?.map { it.id.value })
    }

    @Test
    fun removedFromServerDropsMemoryAndDurableRoster() = runTest {
        val harness = Harness(this)
        harness.rosters[SERVER_A] = listOf(human("a1", "Ada"))
        harness.rosters[SERVER_B] = listOf(human("b1", "Bea"))
        val store = harness.store()
        store.refreshMembers(ServerId(SERVER_A))
        store.refreshMembers(ServerId(SERVER_B))

        store.dropServer(ServerId(SERVER_A))

        assertTrue(store.currentServerMembersEntry(ServerId(SERVER_A)).members.isEmpty())
        assertNull(store.currentMemberProfile(ServerId(SERVER_A), "a1"))
        assertNull(harness.durable.read(harness.durableKey(SERVER_A)))
        assertFalse(store.hydrateFromDurable(ServerId(SERVER_A)))
        assertEquals(listOf("b1"), store.currentServerMembersEntry(ServerId(SERVER_B)).members.map { it.id.value })
        assertNotNull(harness.durable.read(harness.durableKey(SERVER_B)))
    }

    @Test
    fun backgroundValidationThatProvesLostAccessEvictsTheCachedRoster() = runTest {
        val harness = Harness(this)
        harness.rosters[SERVER_A] = listOf(human("a1", "Ada"))
        val store = harness.store()
        store.refreshMembers(ServerId(SERVER_A))
        assertNotNull(harness.durable.read(harness.durableKey(SERVER_A)))

        harness.failures[SERVER_A] = DataResult.Error(ScreenError(code = "http_404", retryable = false))
        store.refreshMembers(ServerId(SERVER_A), DataRequestPriority.Background, force = true)

        assertTrue(store.currentServerMembersEntry(ServerId(SERVER_A)).members.isEmpty())
        assertNull(harness.durable.read(harness.durableKey(SERVER_A)))

        // A transient failure keeps the cached roster.
        harness.failures.remove(SERVER_A)
        store.refreshMembers(ServerId(SERVER_A), force = true)
        harness.failures[SERVER_A] = DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        store.refreshMembers(ServerId(SERVER_A), DataRequestPriority.Background, force = true)
        assertEquals(listOf("a1"), store.currentServerMembersEntry(ServerId(SERVER_A)).members.map { it.id.value })
    }

    @Test
    fun removedMemberLeavesMemoryAndDurableRoster() = runTest {
        val harness = Harness(this)
        harness.rosters[SERVER_A] = listOf(human("a1", "Ada"), human("a2", "Ben"))
        val store = harness.store()
        store.refreshMembers(ServerId(SERVER_A))

        assertTrue(store.removeMember(ServerId(SERVER_A), MemberId("a2")))
        runCurrent()

        assertEquals(listOf("a1"), store.currentServerMembersEntry(ServerId(SERVER_A)).members.map { it.id.value })
        assertEquals(listOf("a1"), harness.durable.read(harness.durableKey(SERVER_A))?.members?.map { it.id.value })
    }

    @Test
    fun memoryCapEvictsLeastRecentlyUsedServerAndRevisitSeedsFromDisk() = runTest {
        val harness = Harness(this)
        listOf("s1", "s2", "s3").forEach { id -> harness.rosters[id] = listOf(human("$id-m", "Member $id")) }
        val store = harness.store(maxCachedServers = 2)
        store.refreshMembers(ServerId("s1"))
        store.refreshMembers(ServerId("s2"))
        store.refreshMembers(ServerId("s3"))

        assertEquals(listOf("s2", "s3"), store.cachedServerIdsForTest())
        assertTrue(store.currentServerMembersEntry(ServerId("s1")).members.isEmpty())

        assertTrue(store.hydrateFromDurable(ServerId("s1")))
        assertEquals(listOf("s1-m"), store.currentServerMembersEntry(ServerId("s1")).members.map { it.id.value })
        assertEquals(listOf("s3", "s1"), store.cachedServerIdsForTest())
    }

    @Test
    fun durableCapsTrimMembersAndServersLeastRecentlyWrittenFirst() = runTest {
        val storage = MapDurableStorage()
        val durable =
            RaftServerMembersDurableStore(
                storage = storage,
                maxServersPerAccount = 2,
                maxServers = 3,
                maxMembersPerServer = 2,
                scope = backgroundScope
            )
        suspend fun put(accountId: String, serverId: String) {
            assertTrue(
                durable.write(
                    RaftServerMembersDurableKey(ENVIRONMENT, accountId, serverId),
                    MemberSnapshot(server(serverId), listOf(human("x", "X"), human("y", "Y"), human("z", "Z"))),
                    durable.epoch
                )
            )
        }
        put("account-a", "s1")
        put("account-a", "s2")
        put("account-a", "s3")
        assertEquals(listOf("s2", "s3"), durable.rowIdsForTest().map { it.substringAfterLast('\u001f') })
        assertNull(durable.read(RaftServerMembersDurableKey(ENVIRONMENT, "account-a", "s1")))
        assertEquals(2, durable.read(RaftServerMembersDurableKey(ENVIRONMENT, "account-a", "s3"))?.members?.size)

        put("account-b", "s1")
        put("account-b", "s2")
        assertEquals(3, durable.rowIdsForTest().size)
        assertNull(durable.read(RaftServerMembersDurableKey(ENVIRONMENT, "account-a", "s2")))
        assertNotNull(durable.read(RaftServerMembersDurableKey(ENVIRONMENT, "account-a", "s3")))
    }

    private class Harness(
        private val testScope: TestScope,
        val storage: MapDurableStorage = MapDurableStorage(),
        accountId: String = "account-a",
    ) {
        val rosters = mutableMapOf<String, List<MemberProfile>>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val failures = mutableMapOf<String, DataResult<MemberSnapshot>>()
        private val callsByServer = mutableMapOf<String, Int>()
        private val account = MutableStateFlow<String?>(accountId)
        private var writeGeneration = 1L
        val currentWriteGeneration: Long
            get() = writeGeneration
        val durable = RaftServerMembersDurableStore(storage = storage, scope = testScope.backgroundScope)

        fun calls(serverId: String): Int = callsByServer[serverId] ?: 0

        fun switchAccount(accountId: String) {
            account.value = accountId
            writeGeneration += 1L
        }

        fun routeOwner(serverId: String): AuthenticatedRouteOwner =
            AuthenticatedRouteOwner(environmentId = ENVIRONMENT, accountId = requireNotNull(account.value), serverId = serverId)

        fun durableKey(serverId: String, accountId: String = requireNotNull(account.value)): RaftServerMembersDurableKey =
            RaftServerMembersDurableKey(ENVIRONMENT, accountId, serverId)

        private fun ownerFor(serverId: ServerId): ProcessServerMembersOwner? =
            account.value?.let { ProcessServerMembersOwner(ENVIRONMENT, it, serverId.value) }

        private suspend fun fetch(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> {
            callsByServer[serverId.value] = calls(serverId.value) + 1
            gates[serverId.value]?.await()
            failures[serverId.value]?.let { return it }
            return DataResult.Content(MemberSnapshot(server(serverId.value), rosters[serverId.value].orEmpty()))
        }

        fun store(maxCachedServers: Int = SharedServerMembersMaxCachedServers): SharedServerMembersStore =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                processCore = ProcessServerMembersCore(fetchMembers = ::fetch, scope = testScope.backgroundScope),
                processOwner = { serverId -> ownerFor(serverId) },
                presenceOwner = { serverId -> ownerFor(serverId) },
                presenceOwnerFlow = { serverId -> account.map { ownerFor(serverId) } },
                presenceWrite = { serverId -> ownerFor(serverId)?.let { ProcessPresenceWrite(it, writeGeneration) } },
                durableStore = durable,
                maxCachedServers = maxCachedServers
            )
    }

    private class MapDurableStorage : RaftServerMembersDurableStorage {
        private val rows = linkedMapOf<String, String>()

        override suspend fun read(id: String): String? = rows[id]

        override suspend fun write(id: String, value: String) {
            rows[id] = value
        }

        override suspend fun delete(id: String) {
            rows.remove(id)
        }

        override suspend fun ids(): List<String> = rows.keys.toList()
    }

    private companion object {
        const val ENVIRONMENT = "https://api.example"
        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"

        fun server(id: String): ServerSummary = ServerSummary(id = ServerId(id), name = "Workspace $id", slug = id)

        fun human(id: String, name: String, avatarUrl: String? = null): MemberProfile =
            MemberProfile(
                id = MemberId(id),
                displayName = name,
                handle = name.lowercase().replace(' ', '-'),
                kind = MemberKind.Human,
                avatar = AvatarReference.fromWireValue(avatarUrl)
            )

        fun agent(id: String, name: String, status: AgentStatus): MemberProfile =
            MemberProfile(
                id = MemberId(id),
                displayName = name,
                handle = name.lowercase(),
                kind = MemberKind.Agent,
                agentStatus = status
            )
    }
}
