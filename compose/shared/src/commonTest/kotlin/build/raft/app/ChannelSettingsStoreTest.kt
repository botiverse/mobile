package build.raft.app

import build.raft.app.data.ARCHIVED_NAME_COLLISION
import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteAuthorityReceipt
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.ChannelActivityMuteSettings
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
import build.raft.app.data.SharedChannelAuthorityStore
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerChannelMembersHandoff
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.RaftExpectedAuthBinding
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelCapabilities
import build.raft.app.model.ChannelId
import build.raft.app.model.ChannelViewerAuthority
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerPermissions
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.thread.ChannelProfileUpdateStatus
import build.raft.app.thread.dispatch
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
            Triple(ChannelSettingsIntent.Leave, true, ChannelSettingsAction.Leave),
            Triple(ChannelSettingsIntent.Delete, true, ChannelSettingsAction.Delete),
            Triple(ChannelSettingsIntent.Archive, false, ChannelSettingsAction.Archive),
        ).forEach { (intent, accessLost, closedBy) ->
            val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()))
            val store = store(dataSource)
            store.dispatch(ChannelSettingsIntent.Load)
            runCurrent()

            store.dispatch(intent)
            runCurrent()

            assertEquals(ChannelSettingsEffect.Close, store.state.value.effect, "$intent")
            assertEquals(accessLost, store.state.value.closedWithAccessLost, "$intent")
            assertEquals(closedBy, store.state.value.closedBy, "$intent")
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

    private fun collisionRefusal(
        type: String = "private",
        canUnarchive: Boolean = true,
        withId: Boolean = true,
    ): DataResult<ChannelProfileUpdateResult> =
        DataResult.Error(
            ScreenError(
                code = ARCHIVED_NAME_COLLISION,
                retryable = false,
                serverMessage = "Channel name \"launch-room\" is held by an archived channel",
                serverFields =
                    buildMap {
                        if (withId) put("archivedChannelId", "channel-old")
                        put("archivedChannelName", "launch-room")
                        put("archivedChannelType", type)
                        put("canUnarchiveArchivedChannel", canUnarchive.toString())
                    },
            )
        )

    @Test
    fun aRenameOntoAnArchivedChannelNameShowsTheWarningAndOpenChannelLeavesForIt() = runTest {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()))
        dataSource.profileResult = collisionRefusal()
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.UpdateProfile("launch-room", ""))
        runCurrent()

        val collision = store.state.value.profileCollision
        assertEquals(ChannelId("channel-old"), collision?.archivedChannelId)
        assertEquals("launch-room", collision?.archivedChannelName)
        assertEquals("private", collision?.archivedChannelType)
        assertTrue(collision?.canUnarchive == true)
        assertEquals(ChannelProfileUpdateStatus.Failed, store.state.value.profileStatus)
        assertEquals(ARCHIVED_NAME_COLLISION, store.state.value.profileErrorCode)
        assertEquals("#canonical", store.state.value.channel?.name)

        // A description edit clears the status but keeps the warning, as the web does.
        store.dispatch(ChannelSettingsIntent.ClearProfileStatus)
        assertEquals(collision, store.state.value.profileCollision)

        store.dispatch(ChannelSettingsIntent.OpenArchivedCollision)
        val effect = assertIs<ChannelSettingsEffect.OpenChannel>(store.state.value.effect)
        assertFalse(effect.unarchived)
        assertEquals(ChannelId("channel-old"), effect.conversation.channelId)
        assertEquals("private", effect.conversation.channelType)
        assertEquals(ConversationKind.Channel, effect.conversation.kind)
        assertNull(store.state.value.profileCollision)
        assertTrue(dataSource.unarchivedChannels.isEmpty())
        store.clear()
    }

    @Test
    fun unarchiveFromTheWarningRestoresTheArchivedChannelAndOpensIt() = runTest {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()))
        dataSource.profileResult = collisionRefusal()
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()
        store.dispatch(ChannelSettingsIntent.UpdateProfile("launch-room", ""))
        runCurrent()

        store.dispatch(ChannelSettingsIntent.UnarchiveArchivedCollision)
        assertTrue(store.state.value.collisionUnarchiving)
        runCurrent()

        assertEquals(listOf(ChannelId("channel-old")), dataSource.unarchivedChannels)
        assertEquals(0, dataSource.unarchiveCalls)
        val effect = assertIs<ChannelSettingsEffect.OpenChannel>(store.state.value.effect)
        assertTrue(effect.unarchived)
        assertEquals(ChannelId("channel-old"), effect.conversation.channelId)
        assertFalse(store.state.value.collisionUnarchiving)
        assertNull(store.state.value.profileCollision)
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
    fun aFailedSlackStepKeepsTheSavedNameAndReportsThePartialFailure() = runTest {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(slackSnapshot()))
        dataSource.slackResult =
            DataResult.Error(
                ScreenError(
                    code = "slack_bridge_preflight_failed",
                    retryable = false,
                    serverMessage = "Slack Bridge preflight failed: missing channel permission.",
                    serverCode = "slack_bridge_preflight_failed",
                    traceId = "trace-slack-1",
                )
            )
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.UpdateProfile("renamed", "", ChannelSlackBridgeSelection("C1")))
        runCurrent()

        assertEquals(1, dataSource.profileCalls)
        assertEquals("renamed", store.state.value.channel?.name)
        assertEquals(ChannelProfileUpdateStatus.Failed, store.state.value.profileStatus)
        assertEquals(CHANNEL_SETTINGS_SLACK_BRIDGE_PARTIAL_FAILURE, store.state.value.profileErrorCode)
        assertEquals("slack_bridge_preflight_failed", store.state.value.profileErrorServerCode)
        assertEquals("Slack Bridge preflight failed: missing channel permission.", store.state.value.profileErrorMessage)
        assertEquals("trace-slack-1", store.state.value.profileErrorTraceId)
        assertNull(store.state.value.slackBridge?.pairedSlackChannelId)
        store.clear()
    }

    @Test
    fun failedSlack5xxAndEdgeBlockedStepsScrubRawBodyAndPreserveTier1Classification() = runTest {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(slackSnapshot()))
        dataSource.slackResult =
            DataResult.Error(
                ScreenError(
                    code = "slack_bridge_http_500",
                    retryable = true,
                    serverMessage = "SQLSTATE crash",
                    serverCode = "internal_error",
                    traceId = "trace-slack-500",
                )
            )
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.UpdateProfile("canonical", "", ChannelSlackBridgeSelection("C1")))
        runCurrent()

        assertEquals(ChannelProfileUpdateStatus.Failed, store.state.value.profileStatus)
        assertNull(store.state.value.profileErrorMessage)
        assertEquals("server_unavailable", store.state.value.profileErrorServerCode)
        assertEquals("trace-slack-500", store.state.value.profileErrorTraceId)

        // KtorRaftHttpExecutor emits edge_blocked with serverCode == null: must stay classified as edge_blocked.
        dataSource.slackResult =
            DataResult.Error(
                ScreenError(
                    code = "http_403",
                    retryable = false,
                    serverMessage = "edge_blocked",
                    serverCode = null,
                    traceId = "trace-slack-edge",
                )
            )
        store.dispatch(ChannelSettingsIntent.UpdateProfile("canonical", "", ChannelSlackBridgeSelection("C1")))
        runCurrent()
        assertNull(store.state.value.profileErrorMessage)
        assertEquals("edge_blocked", store.state.value.profileErrorServerCode)
        assertEquals("trace-slack-edge", store.state.value.profileErrorTraceId)
        store.clear()
    }

    @Test
    fun refreshSlackBridgeUpdatesOptionsSkipsDuringMutationAndReportsFailure() = runTest {
        val initialSlack =
            ChannelSlackBridgeState(
                options = listOf(ChannelSlackBridgeOption("C1", "triage", membershipRequired = false, pairedElsewhere = false)),
                pairedSlackChannelId = null,
            )
        val refreshedSlack =
            ChannelSlackBridgeState(
                options =
                    listOf(
                        ChannelSlackBridgeOption("C1", "triage", membershipRequired = false, pairedElsewhere = false),
                        ChannelSlackBridgeOption("C2", "alerts", membershipRequired = false, pairedElsewhere = false),
                    ),
                pairedSlackChannelId = null,
            )
        val pinGate = CompletableDeferred<DataResult<SettingsServerSidebarOrderSnapshot>>()
        val dataSource =
            FakeChannelSettingsDataSource(
                loadResult = DataResult.Content(snapshot().copy(slackBridge = initialSlack)),
                pinResult = pinGate,
            )
        dataSource.slackRefreshResult = DataResult.Content(refreshedSlack)
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        // Success replaces slackBridge with fresh options.
        assertEquals(ChannelSlackBridgeReadOutcome.Success, store.refreshSlackBridge())
        assertEquals(listOf("C1", "C2"), store.state.value.slackBridge?.options?.map { it.slackChannelId })
        assertEquals(1, dataSource.slackRefreshCalls)

        // Failure leaves the last list intact and returns Failed.
        dataSource.slackRefreshResult = DataResult.Error(ScreenError("slack_bridge_http_502", retryable = true))
        assertEquals(ChannelSlackBridgeReadOutcome.Failed, store.refreshSlackBridge())
        assertEquals(listOf("C1", "C2"), store.state.value.slackBridge?.options?.map { it.slackChannelId })

        // While a channel mutation is in flight, refreshSlackBridge skips cleanly.
        store.dispatch(ChannelSettingsIntent.TogglePin)
        runCurrent()
        assertEquals(ChannelSlackBridgeReadOutcome.Skipped, store.refreshSlackBridge())
        assertEquals(2, dataSource.slackRefreshCalls)
        pinGate.complete(DataResult.Content(SettingsServerSidebarOrderSnapshot()))
        runCurrent()
        store.clear()
    }

    @Test
    fun profileArchiveAndVisitorVisibleRefusalsPreserveServerMessageCodeAndTraceId() = runTest {
        val capabilities = ChannelCapabilities(editChannelMetadata = true, manageVisitorAccess = true, archiveChannels = true)
        val initial = snapshot().let { base -> base.copy(channel = base.channel.copy(channelCapabilities = capabilities)) }
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(initial))
        dataSource.profileResult =
            DataResult.Error(
                ScreenError(
                    code = "channel_name_reserved",
                    retryable = false,
                    serverMessage = "That channel name is reserved.",
                    serverCode = "channel_name_reserved",
                    traceId = "trace-prof-409",
                )
            )
        dataSource.archiveResult =
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "archive_forbidden",
                    serverMessage = "Only workspace owners can archive announcement channels.",
                    serverCode = "archive_forbidden",
                    traceId = "trace-arch-403",
                )
            )
        dataSource.visitorVisibleFailure =
            DataResult.Error(
                ScreenError(
                    code = "visitor_visible_external_conflict",
                    retryable = false,
                    serverMessage = "Channels with external guests cannot be made visible to visitors.",
                    serverCode = "visitor_visible_external_conflict",
                    traceId = "trace-vis-409",
                )
            )
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.UpdateProfile("announcements", ""))
        runCurrent()
        assertEquals("channel_name_reserved", store.state.value.profileErrorCode)
        assertEquals("channel_name_reserved", store.state.value.profileErrorServerCode)
        assertEquals("That channel name is reserved.", store.state.value.profileErrorMessage)
        assertEquals("trace-prof-409", store.state.value.profileErrorTraceId)

        store.dispatch(ChannelSettingsIntent.SetVisitorVisible(true))
        runCurrent()
        assertEquals("visitor_visible_external_conflict", store.state.value.visitorVisibleErrorCode)
        assertEquals("visitor_visible_external_conflict", store.state.value.visitorVisibleErrorServerCode)
        assertEquals("Channels with external guests cannot be made visible to visitors.", store.state.value.visitorVisibleErrorMessage)
        assertEquals("trace-vis-409", store.state.value.visitorVisibleErrorTraceId)

        store.dispatch(ChannelSettingsIntent.Archive)
        runCurrent()
        val archiveFailure = assertIs<ChannelSettingsEffect.Failure>(store.state.value.effect)
        assertEquals(ChannelSettingsAction.Archive, archiveFailure.action)
        assertEquals("archive_forbidden", archiveFailure.code)
        assertEquals("archive_forbidden", archiveFailure.serverCode)
        assertEquals("Only workspace owners can archive announcement channels.", archiveFailure.serverMessage)
        assertEquals("trace-arch-403", archiveFailure.traceId)
        store.clear()
    }

    @Test
    fun unarchiveCollisionFailureScrubs5xxAndPreserves4xxRefusalReason() = runTest {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()))
        dataSource.profileResult = collisionRefusal()
        dataSource.unarchiveChannelResult =
            DataResult.Error(
                ScreenError(
                    code = "http_500",
                    retryable = true,
                    serverMessage = "<html>500 Internal Server Error</html>",
                    serverCode = "internal_error",
                    traceId = "trace-unarch-500",
                )
            )
        val store = store(dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()
        store.dispatch(ChannelSettingsIntent.UpdateProfile("launch-room", ""))
        runCurrent()

        store.dispatch(ChannelSettingsIntent.UnarchiveArchivedCollision)
        runCurrent()
        assertEquals(CHANNEL_SETTINGS_ARCHIVED_UNARCHIVE_FAILED, store.state.value.profileErrorCode)
        assertEquals("server_unavailable", store.state.value.profileErrorServerCode)
        assertNull(store.state.value.profileErrorMessage)
        assertEquals("trace-unarch-500", store.state.value.profileErrorTraceId)

        dataSource.unarchiveChannelResult =
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "server_forbidden",
                    serverMessage = "Only server admins can restore this archived channel.",
                    serverCode = "forbidden",
                    traceId = "trace-unarch-403",
                )
            )
        store.dispatch(ChannelSettingsIntent.UnarchiveArchivedCollision)
        runCurrent()
        assertEquals("Only server admins can restore this archived channel.", store.state.value.profileErrorMessage)
        assertEquals("forbidden", store.state.value.profileErrorServerCode)
        assertEquals("trace-unarch-403", store.state.value.profileErrorTraceId)
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

    private fun route(): ChannelSettingsRoute =
        ChannelSettingsRoute(ServerId("server-1"), ChannelId("channel-1"))

    private fun CoroutineScope.store(
        dataSource: ChannelSettingsDataSource,
        cache: ChannelSettingsSnapshotCache = ChannelSettingsSnapshotCache(),
        owner: AuthenticatedRouteOwner = routeOwner(),
        initialSharedHandoff: ChannelSettingsSharedHandoff = ChannelSettingsSharedHandoff.Unavailable,
        initialAuthorityAccepted: Boolean = true,
        channelAuthorityStore: SharedChannelAuthorityStore? = null,
    ): ChannelSettingsStore =
        ChannelSettingsStore(
            route = route(),
            owner = owner,
            dataSource = dataSource,
            snapshotCache = cache,
            initialSharedHandoff = initialSharedHandoff,
            initialAuthorityAccepted = initialAuthorityAccepted,
            scope = this,
            channelAuthorityStore = channelAuthorityStore,
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

    private fun snapshot(
        name: String = "#canonical",
        capabilities: ChannelCapabilities? =
            ChannelCapabilities(
                editChannelMetadata = true,
                changeChannelVisibility = true,
                archiveChannels = true,
                deleteChannels = true,
                federateChannels = true,
            ),
    ): ChannelSettingsSnapshot =
        ChannelSettingsSnapshot(
            channel =
                HomeChannelRow(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    name = name,
                    channelCapabilities = capabilities,
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

    @Test
    fun setVisitorVisibleUpdatesOptimisticallyConfirmsAndPreservesCapabilities() = runTest {
        val gate = CompletableDeferred<DataResult<HomeChannelRow>>()
        val capabilities = ChannelCapabilities(editChannelMetadata = true, manageVisitorAccess = true)
        val initial = snapshot().let { base ->
            base.copy(channel = base.channel.copy(guestVisible = false, channelCapabilities = capabilities))
        }
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(initial), visitorVisibleResult = gate)
        val store = store(dataSource = dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.SetVisitorVisible(true))
        assertEquals(ChannelSettingsAction.VisitorVisible, store.state.value.actionInFlight)
        assertEquals(true, store.state.value.displayVisitorVisible)
        assertEquals(false, store.state.value.channel?.guestVisible)

        // PATCH /channels/:id does not re-attach channelCapabilities; the store keeps the existing ones.
        gate.complete(
            DataResult.Content(
                HomeChannelRow(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    name = "#canonical",
                    guestVisible = true,
                    channelCapabilities = null,
                )
            )
        )
        runCurrent()

        assertEquals(listOf(true), dataSource.visitorVisibleRequests)
        assertEquals(null, store.state.value.actionInFlight)
        assertEquals(true, store.state.value.displayVisitorVisible)
        assertEquals(true, store.state.value.channel?.guestVisible)
        assertEquals(capabilities, store.state.value.channel?.channelCapabilities)
        assertEquals(null, store.state.value.visitorVisibleErrorCode)
        assertEquals(ChannelSettingsEffect.Updated(ChannelSettingsAction.VisitorVisible), store.state.value.effect)
    }

    @Test
    fun makePrivateClearsVisitorVisibleWhileSettingsStayOpen() = runTest {
        val capabilities = ChannelCapabilities(changeChannelVisibility = true, manageVisitorAccess = true)
        val initial = snapshot().let { base ->
            base.copy(channel = base.channel.copy(channelType = "channel", guestVisible = true, channelCapabilities = capabilities))
        }
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(initial))
        val store = store(dataSource = dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.MakePrivate)
        runCurrent()

        assertEquals("private", store.state.value.channel?.channelType)
        assertEquals(false, store.state.value.channel?.guestVisible)
        assertEquals(false, store.state.value.displayVisitorVisible)
    }

    @Test
    fun disconnectRequiresDeleteChannelsCapabilityNotServerCanManage() = runTest {
        val noDelete =
            jointSnapshot().let { base ->
                base.copy(
                    canManage = true,
                    channel =
                        base.channel.copy(
                            channelCapabilities = ChannelCapabilities(editChannelMetadata = true, deleteChannels = false),
                        ),
                )
            }
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(noDelete))
        val store = store(dataSource = dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.Disconnect)
        runCurrent()

        assertEquals(0, dataSource.disconnectCalls)
        assertNull(store.state.value.effect)
        store.clear()
    }

    @Test
    fun observedChannelAuthorityUpdatesPermissionsAndCanAddMembersLive() = runTest {
        val authorityStore = SharedChannelAuthorityStore()
        val initial =
            snapshot(
                capabilities = ChannelCapabilities(addChannelMembers = false, archiveChannels = false),
            )
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(initial))
        val store = store(dataSource = dataSource, channelAuthorityStore = authorityStore)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        assertFalse(store.state.value.permissions().archive)
        assertFalse(store.state.value.memberSlice.canAddMembers)

        val token = authorityStore.captureRequestToken("server-1")
        authorityStore.writeSingleAnswer(
            token = token,
            channelId = ChannelId("channel-1"),
            authority =
                ChannelViewerAuthority(
                    role = "admin",
                    adminBasis = "channel_admin",
                    capabilities =
                        ChannelCapabilities(
                            addChannelMembers = true,
                            archiveChannels = true,
                            editChannelMetadata = true,
                        ),
                    revision = 2L,
                ),
        )
        runCurrent()

        assertTrue(store.state.value.permissions().archive)
        assertTrue(store.state.value.permissions().editMetadata)
        assertTrue(store.state.value.memberSlice.canAddMembersKnown)
        assertTrue(store.state.value.memberSlice.canAddMembers)
        store.clear()
    }

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
    private val visitorVisibleResult: CompletableDeferred<DataResult<HomeChannelRow>>? = null,
    private val disconnectResult: DataResult<Unit> = DataResult.Content(Unit),
) : ChannelSettingsDataSource {
    var makePublicCalls = 0
    val visitorVisibleRequests = mutableListOf<Boolean>()
    val slackApplies = mutableListOf<String?>()
    var slackResult: DataResult<ChannelSlackBridgeState?> = DataResult.Content(null)
    var leaveCalls = 0
    var unarchiveCalls = 0
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

    var profileResult: DataResult<ChannelProfileUpdateResult>? = null

    override suspend fun updateProfile(name: String, description: String): DataResult<ChannelProfileUpdateResult> {
        profileCalls += 1
        return profileResult ?: DataResult.Content(ChannelProfileUpdateResult(ChannelId("channel-1"), name, description))
    }

    val unarchivedChannels = mutableListOf<ChannelId>()
    var unarchiveChannelResult: DataResult<HomeChannelRow>? = null

    override suspend fun unarchiveChannel(channelId: ChannelId): DataResult<HomeChannelRow> {
        unarchivedChannels += channelId
        return unarchiveChannelResult
            ?: DataResult.Content(
                HomeChannelRow(threadId = ThreadId(channelId.value), channelId = channelId, channelType = "private", name = "#launch-room")
            )
    }

    override suspend fun makePrivate(): DataResult<Unit> {
        makePrivateCalls += 1
        return DataResult.Content(Unit)
    }

    override suspend fun applySlackBridge(channel: HomeChannelRow, slackChannelId: String?): DataResult<ChannelSlackBridgeState?> {
        slackApplies += slackChannelId
        return slackResult
    }

    var slackRefreshCalls = 0
    var slackRefreshResult: DataResult<ChannelSlackBridgeState?>? = null

    override suspend fun refreshSlackBridge(channel: HomeChannelRow): DataResult<ChannelSlackBridgeState?> {
        slackRefreshCalls += 1
        return slackRefreshResult ?: slackResult
    }

    override suspend fun makePublic(): DataResult<Unit> {
        makePublicCalls += 1
        return DataResult.Content(Unit)
    }

    var visitorVisibleFailure: DataResult<HomeChannelRow>? = null

    override suspend fun setVisitorVisible(visible: Boolean): DataResult<HomeChannelRow> {
        visitorVisibleRequests += visible
        return visitorVisibleFailure
            ?: visitorVisibleResult?.await()
            ?: DataResult.Content(
                HomeChannelRow(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    name = "#canonical",
                    guestVisible = visible,
                )
            )
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

    var archiveResult: DataResult<Unit>? = null

    override suspend fun archive(): DataResult<Unit> {
        archiveCalls += 1
        return archiveResult ?: DataResult.Content(Unit)
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
