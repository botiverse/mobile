package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
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

        suspend fun storedIds(): List<String>? =
            members.read(SharedServerChannelMembersScope("env-a", "account-a", SERVER_ID, CHANNEL_ID))?.members?.map { it.id.value }
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
