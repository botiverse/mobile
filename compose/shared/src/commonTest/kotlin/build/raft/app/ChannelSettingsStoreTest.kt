package build.raft.app

import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteAuthorityReceipt
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.ChannelActivityMuteSettings
import build.raft.app.data.ChannelGuestPolicyUpdate
import build.raft.app.data.ChannelSlackBridgeOption
import build.raft.app.data.ChannelSlackBridgeState
import build.raft.app.data.ChannelProfileUpdateResult
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerChannelMembersHandoff
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.RaftExpectedAuthBinding
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerPermissions
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelSettingsStoreTest {
    @Test
    fun sharedHandoffPublishesExactOwnerFirstFrameWithoutWaitingForSettingsNetwork() = runBlocking {
        val handoff =
            channelSettingsSharedHandoffSnapshot(
                owner = routeOwner(),
                route = route(),
                homeSnapshot = homeSnapshot(),
                membersHandoff = SharedServerChannelMembersHandoff.Accepted(ownerMemberSlice()),
            )
        val loadGate = CompletableDeferred<DataResult<ChannelSettingsSnapshot>>()
        val dataSource = FakeChannelSettingsDataSource(loadGate = loadGate)
        val store = store(dataSource, initialSharedHandoff = handoff)

        assertEquals(ChannelSettingsLoadStatus.Content, store.state.value.status)
        assertEquals("#from-channel", store.state.value.channel?.name)
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.state.value.memberSlice.status)
        assertFalse(store.state.value.writeActionsEnabled)
        assertEquals(0, dataSource.loadCalls)

        store.dispatch(ChannelSettingsIntent.Refresh)
        yield()
        assertEquals(1, dataSource.loadCalls)
        assertTrue(store.state.value.refreshing)
        assertEquals("#from-channel", store.state.value.channel?.name)

        loadGate.complete(DataResult.Content(snapshot("#fresh")))
        yield()
        assertEquals("#fresh", store.state.value.channel?.name)
        assertTrue(store.state.value.writeActionsEnabled)
        store.clear()
    }

    @Test
    fun sharedHandoffRejectsAccountServerChannelAndMemberOwnerDrift() {
        val exactHome = homeSnapshot()
        val exactMembers = ownerMemberSlice()

        assertEquals(
            ChannelSettingsSharedHandoff.Rejected,
            channelSettingsSharedHandoffSnapshot(
                routeOwner(accountId = "user-2"),
                route(),
                exactHome,
                SharedServerChannelMembersHandoff.Accepted(exactMembers),
            ),
        )
        assertEquals(
            ChannelSettingsSharedHandoff.Rejected,
            channelSettingsSharedHandoffSnapshot(
                routeOwner(),
                ChannelSettingsRoute(ServerId("server-2"), ChannelId("channel-1")),
                exactHome,
                SharedServerChannelMembersHandoff.Accepted(exactMembers),
            ),
        )
        assertEquals(
            ChannelSettingsSharedHandoff.Rejected,
            channelSettingsSharedHandoffSnapshot(
                routeOwner(),
                ChannelSettingsRoute(ServerId("server-1"), ChannelId("channel-2")),
                exactHome,
                SharedServerChannelMembersHandoff.Accepted(exactMembers),
            ),
        )
        assertEquals(
            ChannelSettingsSharedHandoff.Rejected,
            channelSettingsSharedHandoffSnapshot(
                routeOwner(),
                route(),
                exactHome,
                SharedServerChannelMembersHandoff.Accepted(exactMembers.copy(accountId = "user-2")),
            ),
        )
    }

    @Test
    fun rejectedSharedHandoffDoesNotFallBackToSettingsCache() = runBlocking {
        val cache = ChannelSettingsSnapshotCache()
        assertTrue(cache.put(routeOwner(), route(), snapshot("#cached")))
        val store =
            store(
                dataSource = FakeChannelSettingsDataSource(),
                cache = cache,
                initialSharedHandoff = ChannelSettingsSharedHandoff.Rejected,
            )

        assertEquals(ChannelSettingsLoadStatus.Loading, store.state.value.status)
        assertNull(store.state.value.channel)
        store.clear()
    }

    @Test
    fun unavailableMemberStoreStillHandsOffExactHomeAsReadOnlyContent() = runBlocking {
        val handoff =
            channelSettingsSharedHandoffSnapshot(
                owner = routeOwner(),
                route = route(),
                homeSnapshot = homeSnapshot(),
                membersHandoff = SharedServerChannelMembersHandoff.Unavailable,
            )
        val store = store(FakeChannelSettingsDataSource(), initialSharedHandoff = handoff)

        assertEquals(ChannelSettingsLoadStatus.Content, store.state.value.status)
        assertEquals("#from-channel", store.state.value.channel?.name)
        assertEquals(SharedServerMembersLoadStatus.InFlight, store.state.value.memberSlice.status)
        assertFalse(store.state.value.writeActionsEnabled)
        store.clear()
    }

    @Test
    fun mismatchedOwnerRejectsLoadAndEveryMutationBeforeNetworkClient() = runBlocking {
        val gateway = RejectingChannelSettingsGateway()
        val dataSource =
            RealChannelSettingsDataSource(
                route = route(),
                authStore = InMemoryRaftAuthSessionStore(),
                owner = routeOwner(),
                gateway = gateway,
            )

        listOf(
            dataSource.load(),
            dataSource.setMuted(true),
            dataSource.setPinned(true),
            dataSource.updateProfile("renamed", "description"),
            dataSource.makePrivate(),
            dataSource.archive(),
            dataSource.delete(),
            dataSource.disconnect(),
        ).forEach { result ->
            assertEquals(
                AUTHENTICATED_ROUTE_AUTHORITY_CHANGED,
                assertIs<DataResult.Error>(result).error.code,
            )
        }
        assertEquals(8, gateway.captureCalls)
        assertEquals(0, gateway.networkClientCalls)
    }

    @Test
    fun coldLoadProducesStandaloneCanonicalContent() = runBlocking {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()))
        val store = store(dataSource)

        store.dispatch(ChannelSettingsIntent.Load)
        yield()

        assertEquals(1, dataSource.loadCalls)
        assertEquals(ChannelSettingsLoadStatus.Content, store.state.value.status)
        assertEquals("#canonical", store.state.value.channel?.name)
        assertTrue(store.state.value.writeActionsEnabled)
        assertTrue(store.state.value.isPinned("channel-1"))
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.state.value.memberSlice.status)
        store.clear()
    }

    @Test
    fun snapshotCacheIsPartitionedByEnvironmentAndAccount() = runBlocking {
        val cache = ChannelSettingsSnapshotCache()
        assertTrue(cache.put(routeOwner(), route(), snapshot("#account-a")))

        val accountA = store(FakeChannelSettingsDataSource(), cache = cache)
        val accountB =
            store(
                FakeChannelSettingsDataSource(),
                cache = cache,
                owner = routeOwner(accountId = "user-2"),
            )
        val staging =
            store(
                FakeChannelSettingsDataSource(),
                cache = cache,
                owner = routeOwner(environmentId = "https://staging.raft.build/api"),
            )
        val staleRouteOwner =
            store(
                FakeChannelSettingsDataSource(),
                cache = cache,
                initialAuthorityAccepted = false,
            )

        assertEquals("#account-a", accountA.state.value.channel?.name)
        assertFalse(accountA.state.value.writeActionsEnabled)
        assertEquals(ChannelSettingsLoadStatus.Loading, accountB.state.value.status)
        assertEquals(null, accountB.state.value.channel)
        assertEquals(ChannelSettingsLoadStatus.Loading, staging.state.value.status)
        assertEquals(null, staging.state.value.channel)
        assertEquals(ChannelSettingsLoadStatus.Loading, staleRouteOwner.state.value.status)
        assertEquals(null, staleRouteOwner.state.value.channel)

        accountA.clear()
        accountB.clear()
        staging.clear()
        staleRouteOwner.clear()
    }

    @Test
    fun mismatchedSnapshotRetiresExistingExactCacheKey() {
        val cache = ChannelSettingsSnapshotCache()
        assertTrue(cache.put(routeOwner(), route(), snapshot("#cached")))
        val source = snapshot("#wrong")
        val mismatched =
            source.copy(channel = source.channel.copy(channelId = ChannelId("channel-2")))

        assertFalse(cache.put(routeOwner(), route(), mismatched))

        assertEquals(null, cache.get(routeOwner(), route()))
    }

    @Test
    fun mismatchedAuthoritativeSnapshotIsNeitherCachedNorPublished() = runBlocking {
        val cache = ChannelSettingsSnapshotCache()
        cache.put(routeOwner(), route(), snapshot("#cached"))
        val source = snapshot("#wrong")
        val mismatched =
            source.copy(channel = source.channel.copy(channelId = ChannelId("channel-2")))
        val store =
            store(
                FakeChannelSettingsDataSource(loadResult = DataResult.Content(mismatched)),
                cache = cache,
            )

        store.dispatch(ChannelSettingsIntent.Refresh)
        yield()

        assertEquals(ChannelSettingsLoadStatus.Error, store.state.value.status)
        assertEquals(null, store.state.value.channel)
        assertEquals(CHANNEL_SETTINGS_IDENTITY_MISMATCH, store.state.value.errorCode)
        assertFalse(store.state.value.writeActionsEnabled)
        assertEquals(null, cache.get(routeOwner(), route()))
        store.clear()
    }

    @Test
    fun cachedContentRendersReadOnlyUntilBackgroundRefreshSucceeds() = runBlocking {
        val cache = ChannelSettingsSnapshotCache()
        cache.put(routeOwner(), route(), snapshot("#cached"))
        val loadGate = CompletableDeferred<DataResult<ChannelSettingsSnapshot>>()
        val dataSource = FakeChannelSettingsDataSource(loadGate = loadGate)
        val store = store(dataSource, cache = cache)

        assertEquals(ChannelSettingsLoadStatus.Content, store.state.value.status)
        assertEquals("#cached", store.state.value.channel?.name)
        assertFalse(store.state.value.refreshing)
        assertFalse(store.state.value.writeActionsEnabled)

        store.dispatch(ChannelSettingsIntent.Refresh)
        yield()
        assertEquals(1, dataSource.loadCalls)
        assertTrue(store.state.value.refreshing)

        store.dispatch(ChannelSettingsIntent.ToggleMute)
        store.dispatch(ChannelSettingsIntent.TogglePin)
        store.dispatch(ChannelSettingsIntent.UpdateProfile("renamed", "description"))
        store.dispatch(ChannelSettingsIntent.MakePrivate)
        store.dispatch(ChannelSettingsIntent.Archive)
        store.dispatch(ChannelSettingsIntent.Delete)
        yield()
        assertEquals(0, dataSource.muteCalls)
        assertEquals(0, dataSource.pinCalls)
        assertEquals(0, dataSource.profileCalls)
        assertEquals(0, dataSource.makePrivateCalls)
        assertEquals(0, dataSource.archiveCalls)
        assertEquals(0, dataSource.deleteCalls)

        loadGate.complete(DataResult.Content(snapshot("#fresh")))
        yield()
        assertEquals("#fresh", store.state.value.channel?.name)
        assertFalse(store.state.value.refreshing)
        assertTrue(store.state.value.writeActionsEnabled)

        store.dispatch(ChannelSettingsIntent.TogglePin)
        yield()
        assertEquals(1, dataSource.pinCalls)

        val reopened = store(FakeChannelSettingsDataSource(), cache = cache)
        assertEquals("#fresh", reopened.state.value.channel?.name)
        assertFalse(reopened.state.value.writeActionsEnabled)
        store.clear()
        reopened.clear()
    }

    @Test
    fun noPermissionClearsCachedContentAndRetiresSnapshot() = runBlocking {
        val cache = ChannelSettingsSnapshotCache()
        cache.put(routeOwner(), route(), snapshot("#cached"))
        val loadGate = CompletableDeferred<DataResult<ChannelSettingsSnapshot>>()
        val dataSource = FakeChannelSettingsDataSource(loadGate = loadGate)
        val store = store(dataSource, cache = cache)

        store.dispatch(ChannelSettingsIntent.Refresh)
        yield()
        loadGate.complete(DataResult.NoPermission(PermissionBlocker(code = "channel_forbidden")))
        yield()

        assertEquals(ChannelSettingsLoadStatus.Error, store.state.value.status)
        assertEquals(null, store.state.value.channel)
        assertEquals("channel_forbidden", store.state.value.errorCode)
        assertEquals(null, cache.get(routeOwner(), route()))
        store.clear()
    }

    @Test
    fun identityMismatchClearsCachedContentAndDoesNotReappearOnReopen() = runBlocking {
        val cache = ChannelSettingsSnapshotCache()
        cache.put(routeOwner(), route(), snapshot("#cached"))
        val dataSource =
            FakeChannelSettingsDataSource(
                loadResult =
                    DataResult.Error(
                        ScreenError(code = CHANNEL_SETTINGS_IDENTITY_MISMATCH, retryable = false)
                    )
            )
        val store = store(dataSource, cache = cache)

        store.dispatch(ChannelSettingsIntent.Refresh)
        yield()

        assertEquals(ChannelSettingsLoadStatus.Error, store.state.value.status)
        assertEquals(null, store.state.value.channel)
        assertEquals(CHANNEL_SETTINGS_IDENTITY_MISMATCH, store.state.value.errorCode)
        assertFalse(store.state.value.writeActionsEnabled)
        assertEquals(null, cache.get(routeOwner(), route()))

        val reopened = store(FakeChannelSettingsDataSource(), cache = cache)
        assertEquals(ChannelSettingsLoadStatus.Loading, reopened.state.value.status)
        assertEquals(null, reopened.state.value.channel)
        assertFalse(reopened.state.value.writeActionsEnabled)
        store.clear()
        reopened.clear()
    }

    @Test
    fun transientRefreshFailureKeepsCachedContentReadOnly() = runBlocking {
        val cache = ChannelSettingsSnapshotCache()
        cache.put(routeOwner(), route(), snapshot("#cached"))
        val dataSource =
            FakeChannelSettingsDataSource(
                loadResult = DataResult.Error(ScreenError(code = "network_unavailable"))
            )
        val store = store(dataSource, cache = cache)

        store.dispatch(ChannelSettingsIntent.Refresh)
        yield()

        assertEquals(ChannelSettingsLoadStatus.Content, store.state.value.status)
        assertEquals("#cached", store.state.value.channel?.name)
        assertEquals("network_unavailable", store.state.value.errorCode)
        assertFalse(store.state.value.writeActionsEnabled)
        assertEquals("#cached", cache.get(routeOwner(), route())?.channel?.name)
        store.clear()
    }

    @Test
    fun refreshCannotObsoleteOrClearMutationInFlight() = runBlocking {
        val pinResult = CompletableDeferred<DataResult<SettingsServerSidebarOrderSnapshot>>()
        val dataSource =
            FakeChannelSettingsDataSource(
                loadResult = DataResult.Content(snapshot()),
                pinResult = pinResult,
            )
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        yield()

        store.dispatch(ChannelSettingsIntent.TogglePin)
        yield()
        store.dispatch(ChannelSettingsIntent.Refresh)

        assertEquals(1, dataSource.loadCalls)
        assertEquals(ChannelSettingsAction.Pin, store.state.value.actionInFlight)
        pinResult.complete(DataResult.Content(SettingsServerSidebarOrderSnapshot()))
        yield()
        assertFalse(store.state.value.isPinned("channel-1"))
        assertEquals(ChannelSettingsEffect.Updated(ChannelSettingsAction.Pin), store.state.value.effect)
        store.clear()
    }

    @Test
    fun aToggleShowsItsNewValueAtOnceAndAFailurePutsTheOldOneBack() = runBlocking {
        val pinResult = CompletableDeferred<DataResult<SettingsServerSidebarOrderSnapshot>>()
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()), pinResult = pinResult)
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        yield()
        // The fixture's channel is pinned.
        assertTrue(store.state.value.displayPinned("channel-1"))

        store.dispatch(ChannelSettingsIntent.TogglePin)
        // artin: the switch flips on the tap, before the server answers.
        assertFalse(store.state.value.displayPinned("channel-1"))
        yield()
        assertEquals(listOf(false), dataSource.pinRequests, "the request asks for the value the switch shows")

        pinResult.complete(DataResult.Error(ScreenError("network")))
        yield()
        assertTrue(store.state.value.displayPinned("channel-1"), "a failure shows the old value again")
        assertEquals(ChannelSettingsAction.Pin, assertIs<ChannelSettingsEffect.Failure>(store.state.value.effect).action)
        store.clear()
    }

    @Test
    fun authorityInvalidationDropsLateLoadResult() = runBlocking {
        val cache = ChannelSettingsSnapshotCache()
        cache.put(routeOwner(), route(), snapshot("#cached"))
        val loadGate = CompletableDeferred<DataResult<ChannelSettingsSnapshot>>()
        val dataSource = FakeChannelSettingsDataSource(loadGate = loadGate)
        val store = store(dataSource, cache = cache)

        store.dispatch(ChannelSettingsIntent.Refresh)
        yield()
        store.invalidateAuthority()
        store.dispatch(ChannelSettingsIntent.TogglePin)
        loadGate.complete(DataResult.Content(snapshot()))
        yield()

        assertEquals(ChannelSettingsLoadStatus.AuthorityRejected, store.state.value.status)
        assertEquals(AUTHENTICATED_ROUTE_AUTHORITY_CHANGED, store.state.value.errorCode)
        assertEquals(null, store.state.value.channel)
        assertEquals(0, dataSource.pinCalls)
        assertEquals(null, cache.get(routeOwner(), route()))
        store.clear()
    }

    @Test
    fun mutationAuthorityFailureClearsVisibleAndCachedContent() = runBlocking {
        val cache = ChannelSettingsSnapshotCache()
        val pinResult = CompletableDeferred<DataResult<SettingsServerSidebarOrderSnapshot>>()
        val dataSource =
            FakeChannelSettingsDataSource(
                loadResult = DataResult.Content(snapshot()),
                pinResult = pinResult,
            )
        val store = store(dataSource, cache = cache)
        store.dispatch(ChannelSettingsIntent.Load)
        yield()

        store.dispatch(ChannelSettingsIntent.TogglePin)
        yield()
        pinResult.complete(DataResult.Error(ScreenError(AUTHENTICATED_ROUTE_AUTHORITY_CHANGED)))
        yield()

        assertEquals(ChannelSettingsLoadStatus.AuthorityRejected, store.state.value.status)
        assertEquals(null, store.state.value.channel)
        assertEquals(null, cache.get(routeOwner(), route()))
        store.clear()
    }

    @Test
    fun visibilitySuccessUpdatesRouteLocalCanonicalState() = runBlocking {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()))
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        yield()

        store.dispatch(ChannelSettingsIntent.MakePrivate)
        yield()

        assertEquals("private", store.state.value.channel?.channelType)
        assertEquals(ChannelSettingsEffect.Updated(ChannelSettingsAction.Visibility), store.state.value.effect)
        assertEquals(null, store.state.value.actionInFlight)
        store.clear()
    }

    @Test
    fun makePublicFlipsAPrivateChannelAndIgnoresAPublicOne() = runBlocking {
        val privateSnapshot = snapshot().let { it.copy(channel = it.channel.copy(channelType = "private")) }
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(privateSnapshot))
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        yield()

        store.dispatch(ChannelSettingsIntent.MakePrivate)
        yield()
        assertEquals(0, dataSource.makePrivateCalls)

        store.dispatch(ChannelSettingsIntent.MakePublic)
        yield()
        assertEquals(1, dataSource.makePublicCalls)
        assertEquals("channel", store.state.value.channel?.channelType)
        assertEquals(ChannelSettingsEffect.Updated(ChannelSettingsAction.Visibility), store.state.value.effect)

        store.dispatch(ChannelSettingsIntent.MakePublic)
        yield()
        assertEquals(1, dataSource.makePublicCalls)
        store.clear()
    }

    @Test
    fun leaveAndDeleteLoseAccessButArchiveKeepsTheChannelReadable() = runTest {
        // An archived channel is still readable, so Activity keeps its rows; leave / delete lose it.
        listOf(
            ChannelSettingsIntent.Leave to true,
            ChannelSettingsIntent.Delete to true,
            ChannelSettingsIntent.Archive to false,
        ).forEach { (intent, accessLost) ->
            val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()))
            val store = store(dataSource)
            store.dispatch(ChannelSettingsIntent.Load)
            runCurrent()

            store.dispatch(intent)
            runCurrent()

            assertEquals(ChannelSettingsEffect.Close, store.state.value.effect, "$intent")
            assertEquals(accessLost, store.state.value.closedWithAccessLost, "$intent")
            store.clear()
        }
    }

    @Test
    fun leaveClosesSettingsAndIsRefusedForAllArchivedOrNotJoined() = runTest {
        val cache = ChannelSettingsSnapshotCache()
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()))
        val store = store(dataSource, cache = cache)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.Leave)
        runCurrent()

        assertEquals(1, dataSource.leaveCalls)
        assertEquals(ChannelSettingsEffect.Close, store.state.value.effect)
        assertNull(cache.get(routeOwner(), route()))

        listOf(
            snapshot("#all"),
            snapshot().let { it.copy(channel = it.channel.copy(archived = true)) },
            snapshot().let { it.copy(channel = it.channel.copy(joined = false)) },
        ).forEach { refused ->
            val refusedSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(refused))
            val refusedStore = store(refusedSource)
            refusedStore.dispatch(ChannelSettingsIntent.Load)
            runCurrent()
            refusedStore.dispatch(ChannelSettingsIntent.Leave)
            runCurrent()
            assertEquals(0, refusedSource.leaveCalls, refused.channel.name)
            refusedStore.clear()
        }
        store.clear()
    }

    @Test
    fun unarchiveNeedsAManagerAndAnArchivedChannel() = runTest {
        val archived = snapshot().let { it.copy(channel = it.channel.copy(archived = true)) }
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(archived))
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.Unarchive)
        runCurrent()
        assertEquals(1, dataSource.unarchiveCalls)
        assertEquals(ChannelSettingsEffect.Close, store.state.value.effect)

        val member = archived.copy(canManage = false)
        val memberSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(member))
        val memberStore = store(memberSource)
        memberStore.dispatch(ChannelSettingsIntent.Load)
        runCurrent()
        memberStore.dispatch(ChannelSettingsIntent.Unarchive)
        runCurrent()
        assertEquals(0, memberSource.unarchiveCalls)

        val live = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()))
        val liveStore = store(live)
        liveStore.dispatch(ChannelSettingsIntent.Load)
        runCurrent()
        liveStore.dispatch(ChannelSettingsIntent.Unarchive)
        runCurrent()
        assertEquals(0, live.unarchiveCalls)
        memberStore.clear()
        liveStore.clear()
        store.clear()
    }

    private fun slackSnapshot(): ChannelSettingsSnapshot =
        snapshot().copy(
            slackBridge =
                ChannelSlackBridgeState(
                    options = listOf(ChannelSlackBridgeOption("C1", "triage", membershipRequired = false, pairedElsewhere = false)),
                    pairedSlackChannelId = null,
                )
        )

    @Test
    fun saveWithOnlyASlackPickSkipsTheProfilePatchAndAppliesTheBridge() = runTest {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(slackSnapshot()))
        dataSource.slackResult =
            DataResult.Content(
                ChannelSlackBridgeState(
                    options = listOf(ChannelSlackBridgeOption("C1", "triage", membershipRequired = false, pairedElsewhere = false)),
                    pairedSlackChannelId = "C1",
                )
            )
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.UpdateProfile("canonical", "", ChannelSlackBridgeSelection("C1")))
        runCurrent()

        assertEquals(0, dataSource.profileCalls)
        assertEquals(listOf<String?>("C1"), dataSource.slackApplies)
        assertEquals("C1", store.state.value.slackBridge?.pairedSlackChannelId)
        assertEquals(ChannelProfileUpdateStatus.Succeeded, store.state.value.profileStatus)
        store.clear()
    }

    @Test
    fun aFailedSlackStepKeepsTheSavedNameAndReportsThePartialFailure() = runTest {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(slackSnapshot()))
        dataSource.slackResult = DataResult.Error(ScreenError(code = "slack_bridge_preflight_failed", retryable = false))
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.UpdateProfile("renamed", "", ChannelSlackBridgeSelection("C1")))
        runCurrent()

        assertEquals(1, dataSource.profileCalls)
        assertEquals("renamed", store.state.value.channel?.name)
        assertEquals(ChannelProfileUpdateStatus.Failed, store.state.value.profileStatus)
        assertEquals(CHANNEL_SETTINGS_SLACK_BRIDGE_PARTIAL_FAILURE, store.state.value.profileErrorCode)
        assertNull(store.state.value.slackBridge?.pairedSlackChannelId)
        store.clear()
    }

    @Test
    fun guestSwitchesSendTheWebPolicyPairsAndApplyTheServerRow() = runTest {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot().copy(guestAccessEnabled = true)))
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.SetGuestJoinable(true))
        runCurrent()
        assertEquals(ChannelGuestPolicyUpdate(guestVisible = true, guestJoinable = true), dataSource.guestUpdates.last())
        assertTrue(store.state.value.channel!!.guestVisible)
        assertTrue(store.state.value.channel!!.guestJoinable)

        store.dispatch(ChannelSettingsIntent.SetGuestVisible(false))
        runCurrent()
        assertEquals(ChannelGuestPolicyUpdate(guestVisible = false, guestJoinable = false), dataSource.guestUpdates.last())
        assertFalse(store.state.value.channel!!.guestVisible)
        assertFalse(store.state.value.channel!!.guestJoinable)

        // No-op when the switch already shows the value.
        store.dispatch(ChannelSettingsIntent.SetGuestVisible(false))
        runCurrent()
        assertEquals(2, dataSource.guestUpdates.size)
        store.clear()
    }

    @Test
    fun guestPolicyLimitFailureKeepsTheChannelAndReportsTheServerCode() = runTest {
        val dataSource =
            FakeChannelSettingsDataSource(
                loadResult = DataResult.Content(snapshot().copy(guestAccessEnabled = true)),
                guestPolicyResult = {
                    DataResult.Error(ScreenError(code = "guest_joinable_channel_limit_reached", retryable = false))
                },
            )
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.SetGuestJoinable(true))
        runCurrent()

        assertFalse(store.state.value.channel!!.guestJoinable)
        assertEquals(
            ChannelSettingsEffect.Failure(ChannelSettingsAction.GuestAccess, "guest_joinable_channel_limit_reached"),
            store.state.value.effect,
        )
        store.clear()
    }

    @Test
    fun jointChannelRejectsUnsupportedVisibilityAndDeleteBeforeDataSource() = runBlocking {
        val jointSnapshot =
            snapshot().copy(
                channel =
                    snapshot().channel.copy(
                        channelType = "joint",
                        kind = ConversationKind.Joint,
                    )
            )
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(jointSnapshot))
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        yield()

        store.dispatch(ChannelSettingsIntent.MakePrivate)
        store.dispatch(ChannelSettingsIntent.Delete)
        yield()

        assertEquals(0, dataSource.makePrivateCalls)
        assertEquals(0, dataSource.deleteCalls)
        assertEquals(null, store.state.value.actionInFlight)
        store.clear()
    }

    @Test
    fun jointDisconnectClosesSettingsAndRetiresSnapshot() = runTest {
        val cache = ChannelSettingsSnapshotCache()
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(jointSnapshot()))
        val store = store(dataSource, cache = cache)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.Disconnect)
        runCurrent()

        assertEquals(1, dataSource.disconnectCalls)
        assertEquals(0, dataSource.deleteCalls)
        assertEquals(ChannelSettingsEffect.Close, store.state.value.effect)
        assertEquals(null, store.state.value.actionInFlight)
        assertEquals(null, cache.get(routeOwner(), route()))
        store.clear()
    }

    @Test
    fun disconnectIsRejectedForRegularArchivedAndNonAdminChannels() = runTest {
        listOf(
            snapshot(),
            jointSnapshot().let { it.copy(channel = it.channel.copy(archived = true)) },
            jointSnapshot().copy(canManage = false),
        ).forEach { loaded ->
            val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(loaded))
            val store = store(dataSource)
            store.dispatch(ChannelSettingsIntent.Load)
            runCurrent()

            store.dispatch(ChannelSettingsIntent.Disconnect)
            runCurrent()

            assertEquals(0, dataSource.disconnectCalls)
            assertEquals(null, store.state.value.effect)
            store.clear()
        }
    }

    @Test
    fun disconnectFailureKeepsContentAndCarriesServerReason() = runTest {
        val dataSource =
            FakeChannelSettingsDataSource(
                loadResult = DataResult.Content(jointSnapshot()),
                disconnectResult =
                    DataResult.NoPermission(
                        PermissionBlocker(code = "joint_forbidden", serverMessage = "Only admins can disconnect joint channels")
                    ),
            )
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.Disconnect)
        runCurrent()

        assertEquals(
            ChannelSettingsEffect.Failure(
                ChannelSettingsAction.Disconnect,
                "joint_forbidden",
                "Only admins can disconnect joint channels",
            ),
            store.state.value.effect,
        )
        assertEquals("#canonical", store.state.value.channel?.name)
        assertTrue(store.state.value.writeActionsEnabled)
        store.clear()
    }

    @Test
    fun realDataSourceDisconnectPostsTheJointRoute() = runTest {
        val network = RecordingChannelSettingsNetworkClient()
        val dataSource =
            RealChannelSettingsDataSource(
                route = route(),
                authStore = authStore(),
                owner = routeOwner(),
                gateway = FixedChannelSettingsGateway(network),
            )

        assertIs<DataResult.Content<Unit>>(dataSource.disconnect())
        assertEquals(listOf("POST" to "channels/channel-1/disconnect"), network.methods.zip(network.paths))
    }

    @Test
    fun realDataSourceColdLoadDoesNotDependOnHomeAndPinRereadsCanonicalOrder() = runBlocking {
        val network = RecordingChannelSettingsNetworkClient()
        val gateway = FixedChannelSettingsGateway(network)
        val dataSource =
            RealChannelSettingsDataSource(
                route = route(),
                authStore = authStore(),
                owner = routeOwner(),
                gateway = gateway,
            )

        val loaded = assertIs<DataResult.Content<ChannelSettingsSnapshot>>(dataSource.load()).data
        assertEquals("canonical", loaded.channel.name)
        // The guest-access and Slack Bridge flags are evaluated for a manager; both read as off here.
        assertEquals(
            listOf(
                "channels/channel-1",
                "servers/server-1/sidebar-order",
                "channels/channel-1/members",
                "channels/channel-1",
                "feature-flags/evaluate",
                "feature-flags/evaluate",
            ),
            network.paths,
        )
        assertFalse(loaded.guestAccessEnabled)
        assertNull(loaded.slackBridge)
        assertTrue(network.paths.none { it == "home" || it.endsWith("/home") })

        network.paths.clear()
        network.methods.clear()
        network.bodies.clear()
        val pinned = assertIs<DataResult.Content<SettingsServerSidebarOrderSnapshot>>(dataSource.setPinned(true)).data
        assertTrue(pinned.pinned.any { it.kind == "channel" && it.id == "channel-1" })
        assertEquals(listOf("servers/server-1/sidebar-order", "servers/server-1/sidebar-order"), network.paths)
        assertEquals(listOf("GET", "PATCH"), network.methods)
        assertTrue(network.bodies.last().orEmpty().contains("channel-1"))
    }

    private fun route(): ChannelSettingsRoute =
        ChannelSettingsRoute(ServerId("server-1"), ChannelId("channel-1"))

    private fun CoroutineScope.store(
        dataSource: ChannelSettingsDataSource,
        cache: ChannelSettingsSnapshotCache = ChannelSettingsSnapshotCache(),
        owner: AuthenticatedRouteOwner = routeOwner(),
        initialSharedHandoff: ChannelSettingsSharedHandoff = ChannelSettingsSharedHandoff.Unavailable,
        initialAuthorityAccepted: Boolean = true,
    ): ChannelSettingsStore =
        ChannelSettingsStore(
            route = route(),
            owner = owner,
            dataSource = dataSource,
            snapshotCache = cache,
            initialSharedHandoff = initialSharedHandoff,
            initialAuthorityAccepted = initialAuthorityAccepted,
            scope = this,
        )

    private fun routeOwner(
        environmentId: String = "https://api.raft.build/api",
        accountId: String = "user-1",
    ): AuthenticatedRouteOwner =
        AuthenticatedRouteOwner(environmentId, accountId, "server-1")

    private fun authStore(): InMemoryRaftAuthSessionStore =
        InMemoryRaftAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-1",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Raft", role = "owner")),
            )
        )

    private fun snapshot(name: String = "#canonical"): ChannelSettingsSnapshot =
        ChannelSettingsSnapshot(
            channel =
                HomeChannelRow(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    name = name,
                ),
            sidebarOrder =
                SettingsServerSidebarOrderSnapshot(
                    pinned = listOf(SettingsServerSidebarPinnedItem("channel", "channel-1"))
                ),
            canManage = true,
            memberSlice =
                SharedServerChannelMembersSlice(
                    serverId = "server-1",
                    channelId = "channel-1",
                    status = SharedServerMembersLoadStatus.Loaded,
                ),
        )

    private fun jointSnapshot(): ChannelSettingsSnapshot =
        snapshot().let { base ->
            base.copy(channel = base.channel.copy(channelType = "joint", kind = ConversationKind.Joint))
        }

    private fun homeSnapshot(): HomeSnapshot =
        HomeSnapshot(
            server =
                ServerSummary(
                    id = ServerId("server-1"),
                    name = "Raft",
                    permissions = ServerPermissions(canManageSettings = true),
                ),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "User One",
                    handle = "user-one",
                    kind = MemberKind.Human,
                ),
            channels =
                listOf(
                    HomeChannelRow(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        name = "#from-channel",
                    )
                ),
            directMessages = emptyList(),
            unreadTotal = 0,
            sidebarOrder =
                SettingsServerSidebarOrderSnapshot(
                    pinned = listOf(SettingsServerSidebarPinnedItem("channel", "channel-1"))
                ),
        )

    private fun ownerMemberSlice(): SharedServerChannelMembersSlice =
        SharedServerChannelMembersSlice(
            environmentId = "https://api.raft.build/api",
            accountId = "user-1",
            serverId = "server-1",
            channelId = "channel-1",
            status = SharedServerMembersLoadStatus.Loaded,
        )
}

private class FakeChannelSettingsDataSource(
    private val loadResult: DataResult<ChannelSettingsSnapshot>? = null,
    private val loadGate: CompletableDeferred<DataResult<ChannelSettingsSnapshot>>? = null,
    private val pinResult: CompletableDeferred<DataResult<SettingsServerSidebarOrderSnapshot>>? = null,
    private val disconnectResult: DataResult<Unit> = DataResult.Content(Unit),
    private val guestPolicyResult: (ChannelGuestPolicyUpdate) -> DataResult<HomeChannelRow> = { update ->
        DataResult.Content(
            HomeChannelRow(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                name = "#canonical",
                guestVisible = update.guestVisible ?: false,
                guestJoinable = update.guestJoinable ?: false,
            )
        )
    },
) : ChannelSettingsDataSource {
    var makePublicCalls = 0
    val slackApplies = mutableListOf<String?>()
    var slackResult: DataResult<ChannelSlackBridgeState?> = DataResult.Content(null)
    var leaveCalls = 0
    var unarchiveCalls = 0
    val guestUpdates = mutableListOf<ChannelGuestPolicyUpdate>()
    var loadCalls = 0
    var muteCalls = 0
    var pinCalls = 0
    var profileCalls = 0
    var makePrivateCalls = 0
    var archiveCalls = 0
    var deleteCalls = 0
    var disconnectCalls = 0

    override suspend fun load(): DataResult<ChannelSettingsSnapshot> {
        loadCalls += 1
        return loadGate?.await() ?: requireNotNull(loadResult)
    }

    override suspend fun setMuted(muted: Boolean): DataResult<ChannelActivityMuteSettings> {
        muteCalls += 1
        return DataResult.Content(
            ChannelActivityMuteSettings(
                channelId = ChannelId("channel-1"),
                activityNotificationPreference =
                    ChannelActivityNotificationPreference(activityMuteSupported = true),
            )
        )
    }

    val pinRequests = mutableListOf<Boolean>()

    override suspend fun setPinned(pinned: Boolean): DataResult<SettingsServerSidebarOrderSnapshot> {
        pinCalls += 1
        pinRequests += pinned
        return pinResult?.await() ?: DataResult.Content(SettingsServerSidebarOrderSnapshot())
    }

    override suspend fun updateProfile(name: String, description: String): DataResult<ChannelProfileUpdateResult> {
        profileCalls += 1
        return DataResult.Content(ChannelProfileUpdateResult(ChannelId("channel-1"), name, description))
    }

    override suspend fun makePrivate(): DataResult<Unit> {
        makePrivateCalls += 1
        return DataResult.Content(Unit)
    }

    override suspend fun applySlackBridge(channel: HomeChannelRow, slackChannelId: String?): DataResult<ChannelSlackBridgeState?> {
        slackApplies += slackChannelId
        return slackResult
    }

    override suspend fun makePublic(): DataResult<Unit> {
        makePublicCalls += 1
        return DataResult.Content(Unit)
    }

    override suspend fun leave(): DataResult<Unit> {
        leaveCalls += 1
        return DataResult.Content(Unit)
    }

    override suspend fun unarchive(): DataResult<HomeChannelRow> {
        unarchiveCalls += 1
        return DataResult.Content(
            HomeChannelRow(threadId = ThreadId("channel-1"), channelId = ChannelId("channel-1"), name = "#canonical")
        )
    }

    override suspend fun updateGuestPolicy(update: ChannelGuestPolicyUpdate): DataResult<HomeChannelRow> {
        guestUpdates += update
        return guestPolicyResult(update)
    }

    override suspend fun archive(): DataResult<Unit> {
        archiveCalls += 1
        return DataResult.Content(Unit)
    }

    override suspend fun delete(): DataResult<Unit> {
        deleteCalls += 1
        return DataResult.Content(Unit)
    }

    override suspend fun disconnect(): DataResult<Unit> {
        disconnectCalls += 1
        return disconnectResult
    }
}

private class FixedChannelSettingsGateway(
    private val client: RaftNetworkClient,
) : AuthenticatedRouteAuthorityGateway {
    private val receipt =
        AuthenticatedRouteAuthorityReceipt(
            owner = AuthenticatedRouteOwner("https://api.raft.build/api", "user-1", "server-1"),
            authGeneration = 1L,
            selectionGeneration = 1L,
            canManageSettings = true,
            expectedAuthBinding =
                RaftExpectedAuthBinding(
                    accountId = "user-1",
                    accessToken = "access",
                    refreshToken = "refresh",
                    serverId = "server-1",
                    environmentId = "https://api.raft.build/api",
                ),
        )

    override suspend fun capture(owner: AuthenticatedRouteOwner): AuthenticatedRouteAuthorityReceipt? =
        receipt.takeIf { it.owner == owner }

    override suspend fun isCurrent(receipt: AuthenticatedRouteAuthorityReceipt): Boolean =
        receipt == this.receipt

    override fun networkClient(receipt: AuthenticatedRouteAuthorityReceipt): RaftNetworkClient = client
}

private class RejectingChannelSettingsGateway : AuthenticatedRouteAuthorityGateway {
    var captureCalls = 0
    var networkClientCalls = 0

    override suspend fun capture(owner: AuthenticatedRouteOwner): AuthenticatedRouteAuthorityReceipt? {
        captureCalls += 1
        return null
    }

    override suspend fun isCurrent(receipt: AuthenticatedRouteAuthorityReceipt): Boolean = false

    override fun networkClient(receipt: AuthenticatedRouteAuthorityReceipt): RaftNetworkClient {
        networkClientCalls += 1
        return object : RaftNetworkClient {
            override suspend fun request(
                priority: DataRequestPriority,
                path: String,
                method: String,
                query: List<Pair<String, String>>,
                serverIdOverride: ServerId?,
                bodyText: String?,
            ): HomeNetworkResponse = error("network must not run")
        }
    }
}

private class RecordingChannelSettingsNetworkClient : RaftNetworkClient {
    val paths = mutableListOf<String>()
    val methods = mutableListOf<String>()
    val bodies = mutableListOf<String?>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
    ): HomeNetworkResponse {
        paths += path
        methods += method
        bodies += bodyText
        val response =
            when {
                path == "channels/channel-1" ->
                    """{"id":"channel-1","name":"canonical","type":"channel","memberCount":1}"""
                path == "channels/channel-1/members" ->
                    """{"humans":[{"id":"user-1","name":"artin"}],"agents":[]}"""
                path == "servers/server-1/sidebar-order" && method == "PATCH" ->
                    """{"pinned":[{"kind":"channel","id":"channel-1"}]}"""
                path == "servers/server-1/sidebar-order" -> """{"pinned":[]}"""
                path == "channels/channel-1/disconnect" && method == "POST" -> """{"ok":true}"""
                else -> return HomeNetworkResponse(code = 404, bodyText = null)
            }
        return HomeNetworkResponse(code = 200, bodyText = response)
    }
}
