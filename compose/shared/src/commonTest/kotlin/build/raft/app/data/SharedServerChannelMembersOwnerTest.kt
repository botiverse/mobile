package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SharedServerChannelMembersOwnerTest {
    @Test
    fun sameServerAndChannelIdsDoNotReuseAnotherAccountOrEnvironmentCache() = runBlocking {
        val repository = OwnerTestMemberRepository()
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner(environmentId = "env-a", accountId = "account-a"))
        var writeGeneration = 0L
        repository.channelMembers = listOf(member("member-a"))
        val store = store(repository, ownerState) { writeGeneration }

        val first = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        ownerState.value = owner(environmentId = "env-a", accountId = "account-b")
        writeGeneration += 1
        repository.channelMembers = listOf(member("member-b"))
        val second = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        ownerState.value = owner(environmentId = "env-b/", accountId = "account-b")
        writeGeneration += 1
        repository.channelMembers = listOf(member("member-c"))
        val third = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))

        assertIs<DataResult.Content<ChannelMemberSnapshot>>(first)
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(second)
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(third)
        assertEquals(3, repository.getChannelMembersCalls)
        assertEquals(listOf("member-c"), third.data.members.map { it.id.value })
        assertEquals(
            listOf("member-c"),
            store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).map { it.id.value }
        )
        val visibleSlice = store.channelMemberSliceFlow(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).first()
        assertEquals("env-b", visibleSlice.environmentId)
        assertEquals("account-b", visibleSlice.accountId)
        assertEquals(SERVER_ID, visibleSlice.serverId)
        assertEquals(CHANNEL_ID, visibleSlice.channelId)
        assertEquals(null, store.currentMemberProfile(ServerId(SERVER_ID), "member-a"))
        assertEquals(null, store.currentMemberProfile(ServerId(SERVER_ID), "member-b"))
    }

    @Test
    fun sameOwnerUsesCachedChannelSlice() = runBlocking {
        val repository = OwnerTestMemberRepository().apply {
            channelMembers = listOf(member("member-a"))
        }
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val store = store(repository, ownerState) { 7L }

        val first = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        repository.channelMembers = listOf(member("should-not-load"))
        val cached = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))

        assertIs<DataResult.Content<ChannelMemberSnapshot>>(first)
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(cached)
        assertEquals(1, repository.getChannelMembersCalls)
        assertEquals(listOf("member-a"), cached.data.members.map { it.id.value })
    }

    @Test
    fun forcedReloadKeepsTheRosterWhileItRunsAndWhenItFails() = runBlocking {
        // The channel Members page's pull-to-refresh: a forced Visible reload of a loaded roster.
        val cases =
            listOf<Pair<String, suspend (ChannelId) -> List<MemberProfile>>>(
                "content" to { _ -> listOf(member("member-b"), member("member-a")) },
                "failure" to { _ -> error("network down") },
            )
        cases.forEach { (label, answer) ->
            val repository = OwnerTestMemberRepository().apply { channelMembers = listOf(member("member-a")) }
            val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
            val store = store(repository, ownerState) { 1L }
            store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            repository.channelMembersLoader = { channelId ->
                started.complete(Unit)
                release.await()
                answer(channelId)
            }

            val reload = async {
                store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID), DataRequestPriority.Visible, force = true)
            }
            started.await()
            val inFlight = store.channelMemberSliceFlow(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).first()
            assertEquals(SharedServerMembersLoadStatus.InFlight, inFlight.status, label)
            assertEquals(listOf("member-a"), inFlight.members.map { it.id.value }, "$label: roster stays while it reloads")

            release.complete(Unit)
            reload.await()
            val settled = store.channelMemberSliceFlow(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).first()
            val expected = if (label == "content") listOf("member-a", "member-b") else listOf("member-a")
            assertEquals(expected, settled.members.map { it.id.value }.sorted(), label)
            assertEquals(2, repository.getChannelMembersCalls, label)
        }
    }

    @Test
    fun lateOldOwnerResponseCannotPublish() = runBlocking {
        val repository = OwnerTestMemberRepository()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.channelMembersLoader = {
            started.complete(Unit)
            release.await()
            listOf(member("member-a"))
        }
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        var writeGeneration = 0L
        val store = store(repository, ownerState) { writeGeneration }

        val oldLoad = async {
            store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        }
        started.await()
        ownerState.value = owner("env-a", "account-b")
        writeGeneration += 1

        assertEquals(emptyList(), store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)))
        assertEquals(
            emptyList(),
            store.channelMemberSliceFlow(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).first().members
        )
        release.complete(Unit)
        val result = oldLoad.await()

        assertIs<DataResult.Error>(result)
        assertEquals("channel_members_owner_stale", result.error.code)
        assertEquals(emptyList(), store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)))
        assertEquals(null, store.currentMemberProfile(ServerId(SERVER_ID), "member-a"))
    }

    @Test
    fun clearAndSameAccountReloginRetiresPreviousGeneration() = runBlocking {
        val repository = OwnerTestMemberRepository().apply {
            channelMembers = listOf(member("before-clear"))
        }
        val sameOwner = owner("env-a", "account-a")
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(sameOwner)
        var writeGeneration = 3L
        val store = store(repository, ownerState) { writeGeneration }

        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        ownerState.value = null

        assertEquals(emptyList(), store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)))
        assertEquals(
            emptyList(),
            store.channelMemberSliceFlow(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).first().members
        )

        writeGeneration += 1
        ownerState.value = sameOwner
        repository.channelMembers = listOf(member("after-relogin"))
        val reloaded = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))

        assertIs<DataResult.Content<ChannelMemberSnapshot>>(reloaded)
        assertEquals(2, repository.getChannelMembersCalls)
        assertEquals(listOf("after-relogin"), reloaded.data.members.map { it.id.value })
    }

    @Test
    fun routeOwnedLateResponseCannotPublishAfterSameOwnerGenerationRetires() = runBlocking {
        val repository = OwnerTestMemberRepository()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.channelMembersLoader = {
            started.complete(Unit)
            release.await()
            listOf(member("stale-member"))
        }
        val processOwner = owner("env-a", "account-a")
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(processOwner)
        val registry = ProcessMemberPresenceRegistry()
        val store = store(repository, ownerState, registry = registry) { registry.writeGeneration }
        val routeOwner =
            AuthenticatedRouteOwner(
                environmentId = processOwner.environmentId,
                accountId = processOwner.accountId,
                serverId = processOwner.serverId
            )

        val oldLoad = async {
            store.loadChannelMembers(
                owner = routeOwner,
                channelId = ChannelId(CHANNEL_ID),
                force = true
            )
        }
        started.await()
        registry.clearAll(reason = "test_route_same_owner_relogin")
        release.complete(Unit)
        val result = oldLoad.await()

        assertIs<DataResult.Error>(result)
        assertEquals("channel_members_owner_stale", result.error.code)
        assertEquals(
            emptyList(),
            store.currentChannelMemberSlice(routeOwner, ChannelId(CHANNEL_ID)).members
        )
    }

    @Test
    fun mountedFlowObservesGenerationRetirementWhenOwnerTupleIsUnchanged() = runBlocking {
        val repository = OwnerTestMemberRepository().apply {
            channelMembers = listOf(member("before-clear"))
        }
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val registry = ProcessMemberPresenceRegistry()
        val store = store(repository, ownerState, registry = registry) { registry.writeGeneration }

        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        val observed = mutableListOf<SharedServerChannelMembersSlice>()
        val initialObserved = CompletableDeferred<Unit>()
        val collection = launch {
            store.channelMemberSliceFlow(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
                .take(2)
                .collect { slice ->
                    observed += slice
                    if (observed.size == 1) initialObserved.complete(Unit)
                }
        }

        initialObserved.await()
        registry.clearAll(reason = "test_same_owner_relogin")
        collection.join()

        assertEquals(listOf("before-clear"), observed[0].members.map { it.id.value })
        assertEquals(emptyList(), observed[1].members)
        assertEquals(registry.writeGeneration, observed[1].ownerWriteGeneration)

        repository.channelMembers = listOf(member("after-relogin"))
        val reloaded = store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(reloaded)
        assertEquals(2, repository.getChannelMembersCalls)
        assertEquals(listOf("after-relogin"), reloaded.data.members.map { it.id.value })
    }

    @Test
    fun addingAPeerServerHumanMakesTheirJointRosterRowLocal() = runBlocking {
        // Joint channel: the roster holds this human only through the peer server.
        val repository = OwnerTestMemberRepository().apply {
            channelMembers =
                listOf(member("peer-human").copy(serverId = ServerId("server-peer"), serverName = "Peer", serverSlug = "peer"))
        }
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val store = store(repository, ownerState) { 1L }
        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))

        val result =
            store.addChannelMember(
                ServerId(SERVER_ID),
                AddChannelMemberRequest(ChannelId(CHANNEL_ID), MemberId("peer-human"), MemberKind.Human)
            )

        assertIs<DataResult.Content<ChannelMemberActionResult>>(result)
        val row = store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).single()
        assertEquals(ServerId(SERVER_ID), row.serverId)
        assertEquals(null, row.serverName)
    }

    // ---- Offline-first channel members (artin: everything fetched is stored locally) ----

    /** One device: the disk outlives every store ("process") built over it. */
    private class Device(scope: kotlinx.coroutines.CoroutineScope) {
        val rows = InMemoryRaftOfflineResourceRows()
        val offline = RaftOfflineResourceCache(rows, environmentId = { "env-a" }, scope = scope)
        val members = SharedChannelMembersOffline(offline.resource(ChannelMembersResourceSpec), scope = scope, mirrorPeriodMillis = 0L)

        suspend fun storedIds(channelId: String = CHANNEL_ID, accountId: String = "account-a"): List<String>? =
            members.read(SharedServerChannelMembersScope("env-a", accountId, SERVER_ID, channelId))?.members?.map { it.id.value }
    }

    @Test
    fun coldOpenShowsTheStoredMembersReadOnlyThenTheNetworkReplacesThem() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        loadOnce(device, ownerState, listOf(member("member-a"), member("member-b")))

        val repository = OwnerTestMemberRepository().apply {
            channelMembers = listOf(member("member-c"))
            canManage = true
            gate = CompletableDeferred()
        }
        val store = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
        val load = async { store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)) }
        runCurrent()

        // Knife: without the stored read this is the empty loading slice.
        val standIn = store.currentChannelMemberSlice(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        assertEquals(listOf("member-a", "member-b"), standIn.members.map { it.id.value })
        assertEquals(true, standIn.storedStandIn)
        // Permission flags are never taken from the stored copy: no add / remove until the network answers.
        assertEquals(false, standIn.canAddMembers)
        assertEquals(false, standIn.canRemoveMembers)
        assertEquals(false, standIn.canAddMembersKnown)

        repository.gate!!.complete(Unit)
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(load.await())
        runCurrent()
        val fresh = store.currentChannelMemberSlice(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        assertEquals(listOf("member-c"), fresh.members.map { it.id.value })
        assertEquals(false, fresh.storedStandIn)
        assertEquals(true, fresh.canAddMembers)
        assertEquals(true, fresh.canRemoveMembers)
        assertEquals(listOf("member-c"), device.storedIds())
    }

    @Test
    fun offlineRefreshKeepsTheStoredMembersReadOnlyAndLostAccessDropsThem() = kotlinx.coroutines.test.runTest {
        val cases =
            listOf<Triple<String, DataResult<ChannelMemberSnapshot>, List<String>?>>(
                Triple("network", DataResult.Error(ScreenError(code = "network_offline", retryable = true)), listOf("member-a")),
                Triple("not found", DataResult.Error(ScreenError(code = "http_404", retryable = false)), null),
                Triple("forbidden", DataResult.NoPermission(PermissionBlocker(code = "server_forbidden")), null),
            )
        cases.forEach { (label, failure, expected) ->
            val device = Device(backgroundScope)
            val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
            loadOnce(device, ownerState, listOf(member("member-a")))

            val repository = OwnerTestMemberRepository().apply { this.failure = failure }
            val store = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
            store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
            runCurrent()

            val slice = store.currentChannelMemberSlice(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
            assertEquals(expected.orEmpty(), slice.members.map { it.id.value }, label)
            assertEquals(false, slice.canAddMembers, label)
            assertEquals(false, slice.canRemoveMembers, label)
            assertEquals(expected, device.storedIds(), label)
        }
    }

    @Test
    fun confirmedAddAndRealtimeRemovalReachTheStoredMembersWithoutPermissionFlags() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val repository = OwnerTestMemberRepository().apply {
            channelMembers = listOf(member("member-a").copy(canChangeChannelRole = true, presence = build.raft.app.model.Presence.Active))
            canManage = true
        }
        val store = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()

        val stored = device.members.read(SharedServerChannelMembersScope("env-a", "account-a", SERVER_ID, CHANNEL_ID))!!.members.single()
        assertEquals(null, stored.canChangeChannelRole)
        assertEquals(build.raft.app.model.Presence.Offline, stored.presence)

        store.addChannelMember(ServerId(SERVER_ID), AddChannelMemberRequest(ChannelId(CHANNEL_ID), MemberId("member-b"), MemberKind.Human))
        runCurrent()
        assertEquals(setOf("member-a", "member-b"), device.storedIds()?.toSet())

        store.removeMember(ServerId(SERVER_ID), MemberId("member-a"))
        runCurrent()
        assertEquals(listOf("member-b"), device.storedIds())
    }

    @Test
    fun twoStoresOnOneMirrorNeverStoreAnOlderCopyOrALostChannelAgain() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val repositoryA = OwnerTestMemberRepository().apply {
            channelMembersLoader = { channel -> if (channel.value == CHANNEL_ID) listOf(member("old-a"), member("old-b")) else listOf(member("other")) }
        }
        val repositoryB = OwnerTestMemberRepository().apply { channelMembers = listOf(member("new-a")) }
        // The legacy store and a shell store of the same account share the mirror.
        val storeA = store(repositoryA, ownerState, channelMembersOffline = device.members) { 1L }
        val storeB = store(repositoryB, ownerState, channelMembersOffline = device.members) { 1L }
        storeA.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        storeB.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        assertEquals(listOf("new-a"), device.storedIds())

        // Store A changes something else: its older copy of the channel is not written back.
        storeA.loadChannelMembers(ServerId(SERVER_ID), ChannelId("channel-other"))
        runCurrent()
        assertEquals(listOf("new-a"), device.storedIds())

        // Store B learns the channel is gone; store A still holds old rows and patches them.
        repositoryB.failure = DataResult.Error(ScreenError(code = "http_404", retryable = false))
        storeB.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID), force = true)
        runCurrent()
        assertEquals(null, device.storedIds())
        storeA.removeMember(ServerId(SERVER_ID), MemberId("old-a"))
        runCurrent()
        assertEquals(null, device.storedIds())
    }

    @Test
    fun anOlderStoresPatchedCopyNeverReplacesANewerAnswerAfterReopen() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val repositoryA = OwnerTestMemberRepository().apply { channelMembers = listOf(member("old-a"), member("old-b")) }
        val repositoryB = OwnerTestMemberRepository().apply { channelMembers = listOf(member("new-a"), member("old-b")) }
        val storeA = store(repositoryA, ownerState, channelMembersOffline = device.members) { 1L }
        val storeB = store(repositoryB, ownerState, channelMembersOffline = device.members) { 1L }
        storeA.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()

        // Store B: the channel is gone (drop), then comes back with a fresh answer (re-open).
        repositoryB.failure = DataResult.Error(ScreenError(code = "http_404", retryable = false))
        storeB.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        repositoryB.failure = null
        storeB.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID), force = true)
        runCurrent()
        assertEquals(listOf("new-a", "old-b"), device.storedIds())

        // Both stores apply one realtime removal; store A's copy is older than B's answer.
        storeA.removeMember(ServerId(SERVER_ID), MemberId("old-b"))
        storeB.removeMember(ServerId(SERVER_ID), MemberId("old-b"))
        runCurrent()
        assertEquals(listOf("new-a"), device.storedIds())
    }

    @Test
    fun whenTwoStoresApplyOneRealtimeRemovalTheNewerAnswerStaysStored() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val repositoryA = OwnerTestMemberRepository().apply { channelMembers = listOf(member("old-a"), member("gone")) }
        val repositoryB = OwnerTestMemberRepository().apply { channelMembers = listOf(member("new-a"), member("gone")) }
        val storeA = store(repositoryA, ownerState, channelMembersOffline = device.members) { 1L }
        val storeB = store(repositoryB, ownerState, channelMembersOffline = device.members) { 1L }
        storeA.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        storeB.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()

        // B patches first, A (holding the older answer) last: last writer must not win.
        storeB.removeMember(ServerId(SERVER_ID), MemberId("gone"))
        runCurrent()
        storeA.removeMember(ServerId(SERVER_ID), MemberId("gone"))
        runCurrent()

        assertEquals(listOf("new-a"), device.storedIds())
    }

    @Test
    fun aRemovedMemberLeavesStoredListsThatAreNotInMemory() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        loadOnce(device, ownerState, listOf(member("member-a"), member("member-b")))

        // A new process: nothing in memory; a realtime removal arrives.
        val store = store(OwnerTestMemberRepository(), ownerState, channelMembersOffline = device.members) { 1L }
        store.removeMember(ServerId(SERVER_ID), MemberId("member-a"))
        runCurrent()

        assertEquals(listOf("member-b"), device.storedIds())
    }

    @Test
    fun channelMembersUpdatedReloadsALoadedListAndDropsAStoredOnlyOne() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        loadOnce(device, ownerState, listOf(member("member-a")))

        // Held only on disk: the viewer may no longer belong to the channel; the copy goes.
        val coldRepository = OwnerTestMemberRepository()
        val cold = store(coldRepository, ownerState, channelMembersOffline = device.members) { 1L }
        applyChannelMembersUpdated(listOf(cold), ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        assertEquals(null, device.storedIds())
        assertEquals(0, coldRepository.getChannelMembersCalls)

        // Loaded in memory: it reloads (and a viewer removed from it would get the lost-access drop).
        val repository = OwnerTestMemberRepository().apply { channelMembers = listOf(member("member-a")) }
        val loaded = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
        loaded.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        repository.failure = DataResult.NoPermission(PermissionBlocker(code = "server_forbidden"))
        applyChannelMembersUpdated(listOf(loaded), ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        assertEquals(2, repository.getChannelMembersCalls)
        assertEquals(emptyList(), loaded.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)))
        assertEquals(null, device.storedIds())
    }

    @Test
    fun channelMembersUpdatedKeepsTheListOfAStoreThatHoldsItLoadedInEitherOrder() = kotlinx.coroutines.test.runTest {
        listOf(false, true).forEach { loadedFirst ->
            val device = Device(backgroundScope)
            val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
            val repository = OwnerTestMemberRepository().apply { channelMembers = listOf(member("member-a")) }
            val loaded = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
            val other = store(OwnerTestMemberRepository(), ownerState, channelMembersOffline = device.members) { 1L }
            loaded.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
            runCurrent()

            // A member joins elsewhere; the event reaches both stores.
            repository.channelMembers = listOf(member("member-a"), member("member-b"))
            val stores = if (loadedFirst) listOf(loaded, other) else listOf(other, loaded)
            applyChannelMembersUpdated(stores, ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
            runCurrent()

            assertEquals(listOf("member-a", "member-b"), device.storedIds(), "loadedFirst=$loadedFirst")
        }
    }

    @Test
    fun aBurstOfMembersUpdatedEventsEndsWithTheLastChangeInMemoryAndOnDisk() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val repository = OwnerTestMemberRepository().apply { channelMembers = listOf(member("member-a")) }
        val loaded = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
        val other = store(OwnerTestMemberRepository(), ownerState, channelMembersOffline = device.members) { 1L }
        loaded.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()

        // Change #1 arrives; its reload is held at the network. The server's answer differs per request: the
        // reload of change #1 sees [a, b], only a reload after change #2 sees [a, b, c].
        repository.channelMembersLoader = { _ ->
            if (repository.getChannelMembersCalls == 2) {
                listOf(member("member-a"), member("member-b"))
            } else {
                listOf(member("member-a"), member("member-b"), member("member-c"))
            }
        }
        repository.gate = CompletableDeferred()
        launch { applyChannelMembersUpdated(listOf(other, loaded), ServerId(SERVER_ID), ChannelId(CHANNEL_ID)) }
        runCurrent()
        // Change #2 arrives while that reload runs.
        launch { applyChannelMembersUpdated(listOf(other, loaded), ServerId(SERVER_ID), ChannelId(CHANNEL_ID)) }
        runCurrent()
        repository.gate!!.complete(Unit)
        runCurrent()

        assertEquals(3, repository.getChannelMembersCalls)
        assertEquals(
            listOf("member-a", "member-b", "member-c"),
            loaded.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).map { it.id.value }
        )
        assertEquals(listOf("member-a", "member-b", "member-c"), device.storedIds())
    }

    @Test
    fun aMembersUpdatedEventDuringAColdOpenReloadsAfterItInsteadOfDroppingTheStoredCopy() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        loadOnce(device, ownerState, listOf(member("member-a")))

        // Cold open: the stored stand-in shows while the request runs; that request was sent before the change.
        val repository = OwnerTestMemberRepository().apply {
            gate = CompletableDeferred()
            channelMembersLoader = { _ ->
                if (getChannelMembersCalls == 1) listOf(member("member-a")) else listOf(member("member-a"), member("member-b"))
            }
        }
        val store = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
        val load = async { store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)) }
        runCurrent()
        assertEquals(true, store.currentChannelMemberSlice(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).storedStandIn)

        // A member joins while the stand-in loads.
        launch { applyChannelMembersUpdated(listOf(store), ServerId(SERVER_ID), ChannelId(CHANNEL_ID)) }
        runCurrent()
        // Knife: a loading stand-in did not count as held, so the copy was dropped and nobody reloaded.
        assertEquals(listOf("member-a"), device.storedIds())

        repository.gate!!.complete(Unit)
        assertIs<DataResult.Content<ChannelMemberSnapshot>>(load.await())
        runCurrent()

        assertEquals(2, repository.getChannelMembersCalls)
        assertEquals(
            listOf("member-a", "member-b"),
            store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)).map { it.id.value }
        )
        assertEquals(listOf("member-a", "member-b"), device.storedIds())
    }

    @Test
    fun aMembersUpdatedEventOfAPreviousAccountLeavesTheNextAccountsListsAlone() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        // The event arrived for account-a; the account switched to account-b before it was applied.
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-b"))
        val previousAccount = AuthenticatedRouteOwner(environmentId = "env-a", accountId = "account-a", serverId = SERVER_ID)
        val currentAccount = AuthenticatedRouteOwner(environmentId = "env-a", accountId = "account-b", serverId = SERVER_ID)
        val repository = OwnerTestMemberRepository().apply { channelMembers = listOf(member("member-b")) }
        val loaded = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
        loaded.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        assertEquals(listOf("member-b"), device.storedIds(accountId = "account-b"))

        // Held loaded: no reload for account-b.
        applyChannelMembersUpdated(listOf(loaded), ServerId(SERVER_ID), ChannelId(CHANNEL_ID), expectedOwner = previousAccount)
        runCurrent()
        assertEquals(1, repository.getChannelMembersCalls)

        // Stored only: account-b's copy is not dropped (with or without a channel id).
        val cold = store(OwnerTestMemberRepository(), ownerState, channelMembersOffline = device.members) { 1L }
        applyChannelMembersUpdated(listOf(cold), ServerId(SERVER_ID), ChannelId(CHANNEL_ID), expectedOwner = previousAccount)
        applyChannelMembersUpdated(listOf(cold), ServerId(SERVER_ID), null, expectedOwner = previousAccount)
        runCurrent()
        assertEquals(listOf("member-b"), device.storedIds(accountId = "account-b"))

        // The same event for the current account does act.
        applyChannelMembersUpdated(listOf(loaded), ServerId(SERVER_ID), ChannelId(CHANNEL_ID), expectedOwner = currentAccount)
        runCurrent()
        assertEquals(2, repository.getChannelMembersCalls)
    }

    @Test
    fun aQueuedMembersUpdatedReloadIsNotSentOnceTheInFlightOneLostTheChannel() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val repository = OwnerTestMemberRepository().apply { channelMembers = listOf(member("member-a")) }
        val store = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()

        // Change #1: its reload is held at the network, and the viewer was removed from the channel (403).
        repository.gate = CompletableDeferred()
        repository.failure = DataResult.NoPermission(PermissionBlocker(code = "server_forbidden"))
        launch { applyChannelMembersUpdated(listOf(store), ServerId(SERVER_ID), ChannelId(CHANNEL_ID)) }
        runCurrent()
        // Change #2 queues a reload after it.
        launch { applyChannelMembersUpdated(listOf(store), ServerId(SERVER_ID), ChannelId(CHANNEL_ID)) }
        runCurrent()
        assertEquals(2, repository.getChannelMembersCalls)

        repository.gate!!.complete(Unit)
        runCurrent()

        // The in-flight answer dropped the channel: the queued reload is not sent, no slice comes back.
        assertEquals(2, repository.getChannelMembersCalls)
        assertEquals(false, store.holdsLoadedChannel(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)))
        assertEquals(emptyList(), store.currentChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID)))
        assertEquals(null, device.storedIds())

        // A later event starts afresh (nothing held, nothing stored: no request).
        applyChannelMembersUpdated(listOf(store), ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        assertEquals(2, repository.getChannelMembersCalls)
    }

    @Test
    fun anAccountSwitchAfterTheGrantAdmittedAMembersUpdatedEventLeavesTheNextAccountsListAlone() =
        kotlinx.coroutines.test.runTest {
            val device = Device(backgroundScope)
            val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
            val authContext = MutableStateFlow<ProcessPresenceAuthContext?>(bindingFor("account-a", generation = 1L))
            val repository = OwnerTestMemberRepository().apply { channelMembers = listOf(member("member-a")) }
            val store = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
            store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
            runCurrent()
            assertEquals(1, repository.getChannelMembersCalls)

            // Runs between the grant check and the provider's work, once.
            var onAdmitted: (suspend () -> Unit)? = null
            val realtime =
                SharedRealtimeDomainEventOwner(
                    // The provider's wiring: its handler, the current binding, the members stores.
                    onChannelMembersUpdated = { serverId, channelId, admission ->
                        launchChannelMembersUpdated(
                            scope = backgroundScope,
                            context = authContext.value,
                            admission = admission,
                            serverId = serverId,
                            channelId = channelId,
                            stores = { listOf(store) },
                        )
                    },
                    runIfCurrentGrant = { _, block ->
                        onAdmitted?.also { onAdmitted = null }?.invoke()
                        block()
                        true
                    },
                )
            val event =
                RealtimeRefreshRequired(
                    scope = RealtimeRefreshScope.ChannelMembers,
                    reason = "channel:members-updated",
                    channelId = ChannelId(CHANNEL_ID),
                )

            // Admitted under account-a's grant; the account switches to account-b (which opens the channel)
            // before the event's work runs.
            onAdmitted = {
                ownerState.value = owner("env-a", "account-b")
                authContext.value = bindingFor("account-b", generation = 2L)
                store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
            }
            assertEquals(true, realtime.accept(grant("account-a", generation = 1L), event, source = "test"))
            runCurrent()
            // Knife: the owner taken from the binding (account-b) reloaded account-b's list for account-a's event.
            assertEquals(2, repository.getChannelMembersCalls)
            assertEquals(listOf("member-a"), device.storedIds(accountId = "account-b"))

            // Same account, older generation (a retired session of account-b): no-op as well.
            assertEquals(true, realtime.accept(grant("account-b", generation = 1L), event, source = "test"))
            runCurrent()
            assertEquals(2, repository.getChannelMembersCalls)

            // No grant (canonical-final surface delivery): no-op.
            realtime.deliverGuardedAppShellDomainFinal(sinks = RealtimeGuardedDomainEventSinks(), serverId = SERVER_ID, event = event)
            runCurrent()
            assertEquals(2, repository.getChannelMembersCalls)

            // The event under account-b's current grant does act.
            assertEquals(true, realtime.accept(grant("account-b", generation = 2L), event, source = "test"))
            runCurrent()
            assertEquals(3, repository.getChannelMembersCalls)
        }

    private fun bindingFor(accountId: String, generation: Long): ProcessPresenceAuthContext =
        ProcessPresenceAuthContext(
            environmentId = "env-a",
            accountId = accountId,
            authorityGeneration = generation,
            writeGeneration = 1L,
        )

    private fun grant(accountId: String, generation: Long): RealtimeGrantRecord =
        RealtimeGrantRecord(
            slotKey = RealtimeSlotKey(scopeLeaseId = "shell:test", serverId = SERVER_ID),
            principalId = accountId,
            environmentId = "env-a",
            authGeneration = generation,
            connectionFamilyId = "family-1",
            connectionEpochId = "epoch-1",
        )

    @Test
    fun aMembersUpdatedEventWithoutAChannelAppliesToEveryListOfTheServer() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        // An earlier process stored two lists of the server.
        val earlier = store(
            OwnerTestMemberRepository().apply { channelMembers = listOf(member("member-a")) },
            ownerState,
            channelMembersOffline = device.members,
        ) { 1L }
        earlier.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        earlier.loadChannelMembers(ServerId(SERVER_ID), ChannelId(OTHER_CHANNEL_ID))
        runCurrent()
        earlier.dispose()
        assertEquals(listOf("member-a"), device.storedIds(OTHER_CHANNEL_ID))

        // This process holds one of them loaded.
        val repository = OwnerTestMemberRepository().apply { channelMembers = listOf(member("member-a")) }
        val store = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()

        // The event names no channel: every list of the server is concerned.
        repository.channelMembers = listOf(member("member-a"), member("member-b"))
        val event = RealtimeRefreshRequired(scope = RealtimeRefreshScope.ChannelMembers, reason = "channel:members-updated")
        val update = assertNotNull(event.updatedChannelMembersOrNull())
        applyChannelMembersUpdated(listOf(store), ServerId(SERVER_ID), update.channelId?.let(::ChannelId))
        runCurrent()

        // The held list reloads; the list held only on disk is dropped.
        assertEquals(2, repository.getChannelMembersCalls)
        assertEquals(listOf("member-a", "member-b"), device.storedIds())
        assertEquals(null, device.storedIds(OTHER_CHANNEL_ID))
    }

    @Test
    fun membersUpdatedEventsAreReadWithAndWithoutAChannel() {
        fun event(channelId: String?, scope: RealtimeRefreshScope = RealtimeRefreshScope.ChannelMembers) =
            RealtimeRefreshRequired(scope = scope, reason = "channel:members-updated", channelId = channelId?.let(::ChannelId))
        assertEquals(RealtimeChannelMembersUpdate("channel-1"), event(" channel-1 ").updatedChannelMembersOrNull())
        assertEquals(RealtimeChannelMembersUpdate(null), event(null).updatedChannelMembersOrNull())
        assertEquals(RealtimeChannelMembersUpdate(null), event(" ").updatedChannelMembersOrNull())
        assertEquals(null, event("channel-1", RealtimeRefreshScope.ServerMembers).updatedChannelMembersOrNull())
    }

    @Test
    fun theStoredCopyIsDroppedThroughAnyStoreThatHasAnOwner() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        loadOnce(device, ownerState, listOf(member("member-a")))
        val noOwner = MutableStateFlow<ProcessServerMembersOwner?>(null)
        val ownerless = store(OwnerTestMemberRepository(), noOwner, channelMembersOffline = device.members) { 1L }
        val owned = store(OwnerTestMemberRepository(), ownerState, channelMembersOffline = device.members) { 1L }

        applyChannelMembersUpdated(listOf(ownerless, owned), ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()

        assertEquals(null, device.storedIds())
    }

    @Test
    fun aRemovalThatDoesNotConcernAListLeavesItsLaterWritesWelcome() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val repository = OwnerTestMemberRepository().apply { channelMembers = listOf(member("member-a")) }
        val store = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()

        // Someone not in this channel leaves the server; then a confirmed add here.
        store.removeMember(ServerId(SERVER_ID), MemberId("stranger"))
        runCurrent()
        store.addChannelMember(ServerId(SERVER_ID), AddChannelMemberRequest(ChannelId(CHANNEL_ID), MemberId("member-b"), MemberKind.Human))
        runCurrent()

        assertEquals(setOf("member-a", "member-b"), device.storedIds()?.toSet())
    }

    @Test
    fun aRegainedServerStoresItsUnchangedMembersAgain() = kotlinx.coroutines.test.runTest {
        val device = Device(backgroundScope)
        val ownerState = MutableStateFlow<ProcessServerMembersOwner?>(owner("env-a", "account-a"))
        val repository = OwnerTestMemberRepository().apply { channelMembers = listOf(member("member-a")) }
        val store = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        assertEquals(listOf("member-a"), device.storedIds())

        // The provider's order: the members store drops the server, then the offline wipe.
        store.dropServer(ServerId(SERVER_ID))
        device.offline.wipeServer(SERVER_ID, accountId = "account-a")
        runCurrent()
        assertEquals(null, device.storedIds())

        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        assertEquals(listOf("member-a"), device.storedIds())
    }

    private suspend fun kotlinx.coroutines.test.TestScope.loadOnce(
        device: Device,
        ownerState: MutableStateFlow<ProcessServerMembersOwner?>,
        members: List<MemberProfile>,
    ) {
        val repository = OwnerTestMemberRepository().apply { channelMembers = members; canManage = true }
        val store = store(repository, ownerState, channelMembersOffline = device.members) { 1L }
        store.loadChannelMembers(ServerId(SERVER_ID), ChannelId(CHANNEL_ID))
        runCurrent()
        assertEquals(members.map { it.id.value }, device.storedIds())
    }

    private fun store(
        repository: OwnerTestMemberRepository,
        ownerState: MutableStateFlow<ProcessServerMembersOwner?>,
        registry: ProcessMemberPresenceRegistry = ProcessMemberPresenceRegistry(),
        channelMembersOffline: SharedChannelMembersOffline? = null,
        writeGeneration: () -> Long
    ): SharedServerMembersStore =
        SharedServerMembersStore(
            memberRepository = repository,
            channelMembersOffline = channelMembersOffline,
            presenceRegistry = registry,
            presenceOwner = { ownerState.value },
            presenceOwnerFlow = { ownerState },
            presenceWrite = {
                ownerState.value?.let { owner -> ProcessPresenceWrite(owner, writeGeneration()) }
            }
        )

    private class OwnerTestMemberRepository : UnsupportedMemberRepository() {
        var channelMembers: List<MemberProfile> = emptyList()
        var channelMembersLoader: (suspend (ChannelId) -> List<MemberProfile>)? = null
        var getChannelMembersCalls: Int = 0
        var canManage = false
        var gate: CompletableDeferred<Unit>? = null
        /** A failure to answer with instead of the members. */
        var failure: DataResult<ChannelMemberSnapshot>? = null

        override suspend fun addChannelMember(
            priority: DataRequestPriority,
            request: AddChannelMemberRequest
        ): DataResult<ChannelMemberActionResult> =
            DataResult.Content(
                ChannelMemberActionResult(
                    channelId = request.channelId,
                    memberId = request.memberId,
                    member = member(request.memberId.value)
                )
            )

        override suspend fun getChannelMembers(
            priority: DataRequestPriority,
            channelId: ChannelId
        ): DataResult<ChannelMemberSnapshot> {
            getChannelMembersCalls += 1
            gate?.await()
            failure?.let { return it }
            return DataResult.Content(
                ChannelMemberSnapshot(
                    channelId = channelId,
                    members = channelMembersLoader?.invoke(channelId) ?: channelMembers,
                    canAddMembers = canManage,
                    canRemoveMembers = canManage
                )
            )
        }
    }

    private companion object {
        const val SERVER_ID = "server-shared"
        const val CHANNEL_ID = "channel-shared"
        const val OTHER_CHANNEL_ID = "channel-other"

        fun owner(environmentId: String, accountId: String): ProcessServerMembersOwner =
            ProcessServerMembersOwner(
                environmentId = environmentId,
                accountId = accountId,
                serverId = SERVER_ID
            )

        fun member(id: String): MemberProfile =
            MemberProfile(
                id = MemberId(id),
                displayName = id,
                handle = id,
                kind = MemberKind.Human,
                serverId = ServerId(SERVER_ID)
            )
    }
}

private abstract class UnsupportedMemberRepository : MemberRepository {
    override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> =
        error("unused")

    override suspend fun getMemberProfile(
        priority: DataRequestPriority,
        memberId: MemberId,
        serverId: ServerId?
    ): DataResult<MemberProfile> = error("unused")

    override suspend fun updateAgentProfile(
        priority: DataRequestPriority,
        request: AgentProfileUpdateRequest
    ): DataResult<MemberProfile> = error("unused")

    override suspend fun uploadAgentAvatar(
        priority: DataRequestPriority,
        request: AgentAvatarUploadRequest
    ): DataResult<MemberProfile> = error("unused")

    override suspend fun getAgentDetail(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDetailSnapshot> =
        error("unused")

    override suspend fun getAgentDirectMessages(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDmSnapshot> =
        error("unused")

    override suspend fun getAgentReminders(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentReminderSnapshot> =
        error("unused")

    override suspend fun getAgentActivity(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentActivitySnapshot> =
        error("unused")

    override suspend fun getAgentWorkspaces(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceSnapshot> =
        error("unused")

    override suspend fun getAgentWorkspaceTree(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceTreeSnapshot> =
        error("unused")

    override suspend fun getAgentWorkspaceChildren(
        priority: DataRequestPriority,
        agentId: MemberId,
        dirPath: String
    ): DataResult<AgentWorkspaceChildrenSnapshot> = error("unused")

    override suspend fun readAgentWorkspaceFile(
        priority: DataRequestPriority,
        agentId: MemberId,
        path: String
    ): DataResult<AgentWorkspaceFilePreviewSnapshot> = error("unused")

    override suspend fun getAgentSkills(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentSkillsSnapshot> =
        error("unused")

    override suspend fun startAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
        error("unused")

    override suspend fun stopAgent(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentLifecycleActionResult> =
        error("unused")

    override suspend fun resetAgent(priority: DataRequestPriority, request: AgentResetRequest): DataResult<AgentLifecycleActionResult> =
        error("unused")

    override suspend fun deleteAgent(priority: DataRequestPriority, agentId: MemberId, workspaceChoice: AgentArchiveWorkspaceChoice): DataResult<AgentLifecycleActionResult> =
        error("unused")

    override suspend fun assignAgentMachine(
        priority: DataRequestPriority,
        request: AgentMachineAssignmentRequest
    ): DataResult<AgentLifecycleActionResult> = error("unused")

    override suspend fun addChannelMember(
        priority: DataRequestPriority,
        request: AddChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> =
        DataResult.Content(ChannelMemberActionResult(channelId = request.channelId, memberId = request.memberId))

    override suspend fun removeChannelMember(
        priority: DataRequestPriority,
        request: RemoveChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> = error("unused")
}
