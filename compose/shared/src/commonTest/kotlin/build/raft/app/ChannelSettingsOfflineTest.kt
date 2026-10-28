package build.raft.app

import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.ChannelActivityMuteSettings
import build.raft.app.data.ChannelGuestPolicyUpdate
import build.raft.app.data.ChannelProfileUpdateResult
import build.raft.app.data.ChannelSlackBridgeState
import build.raft.app.data.ConversationDetailResourceSpec
import build.raft.app.data.DataResult
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.InMemoryRaftOfflineResourceRows
import build.raft.app.data.RaftOfflineResourceCache
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedConversationIdentityAuthority
import build.raft.app.data.SharedConversationIdentityHub
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.StoredConversationDetailOwner
import build.raft.app.data.StoredConversationDetails
import build.raft.app.data.toCanonicalConversationDirectoryProjection
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Channel settings and the conversation header's channel detail as offline resources (artin: everything
 * fetched is stored locally): a cold / offline open shows the stored copy, the network replaces it, a failure
 * keeps it, and only confirmed changes write through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSettingsOfflineTest {
    private class Device(scope: CoroutineScope) {
        val rows = InMemoryRaftOfflineResourceRows()
        val offline = RaftOfflineResourceCache(rows, environmentId = { Environment }, scope = scope)
        val details = StoredConversationDetails(offline.resource(ConversationDetailResourceSpec))

        /** A new process over the same disk: fresh memory, same rows. */
        fun cache(): ChannelSettingsSnapshotCache =
            ChannelSettingsSnapshotCache(stored = offline.resource(ChannelSettingsResourceSpec), storedDetails = details)

        suspend fun storedRecord(): ChannelSettingsRecord? {
            val resource = offline.resource(ChannelSettingsResourceSpec)
            return resource.read(resource.key("user-1", "server-1", "channel-1")!!)
        }
    }

    @Test
    fun coldOpenShowsStoredSettingsReadOnlyThenTheNetworkReplacesThem() = runTest {
        val device = Device(backgroundScope)
        loadOnce(device, snapshot("#stored"))

        val network = CompletableDeferred<DataResult<ChannelSettingsSnapshot>>()
        val store = store(device.cache(), OfflineFakeChannelSettingsDataSource(loadGate = network))
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        // Knife: without the stored read this is the Loading blank.
        val standIn = store.state.value
        assertEquals(ChannelSettingsLoadStatus.Content, standIn.status)
        assertEquals("#stored", standIn.channel?.name)
        assertTrue(standIn.refreshing)
        assertFalse(standIn.writeActionsEnabled)
        assertTrue(standIn.isPinned("channel-1"))

        network.complete(DataResult.Content(snapshot("#fresh")))
        runCurrent()

        assertEquals("#fresh", store.state.value.channel?.name)
        assertTrue(store.state.value.writeActionsEnabled)
        assertFalse(store.state.value.refreshing)
        assertEquals("#fresh", device.storedRecord()?.channel?.name)
        store.clear()
    }

    @Test
    fun offlineRefreshKeepsTheStoredSettingsInsteadOfAnErrorBlank() = runTest {
        val device = Device(backgroundScope)
        loadOnce(device, snapshot("#stored"))

        val offline = DataResult.Error(ScreenError(code = "network_offline", retryable = true))
        val store = store(device.cache(), OfflineFakeChannelSettingsDataSource(loadResult = offline))
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        val state = store.state.value
        assertEquals(ChannelSettingsLoadStatus.Content, state.status)
        assertEquals("#stored", state.channel?.name)
        assertEquals("network_offline", state.errorCode)
        assertFalse(state.refreshing)
        assertFalse(state.writeActionsEnabled)
        assertEquals("#stored", device.storedRecord()?.channel?.name)
        store.clear()
    }

    @Test
    fun goneChannelDropsTheStoredSettings() = runTest {
        val device = Device(backgroundScope)
        loadOnce(device, snapshot("#stored"))

        val store =
            store(
                device.cache(),
                OfflineFakeChannelSettingsDataSource(loadResult = DataResult.Empty(build.raft.app.data.EmptyReason.NoMessages)),
            )
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        assertEquals(ChannelSettingsLoadStatus.Error, store.state.value.status)
        assertNull(device.storedRecord())
        store.clear()
    }

    @Test
    fun confirmedChangesWriteThroughAndFailedOnesDoNot() = runTest {
        val device = Device(backgroundScope)
        device.details.record(detailOwner(), "channel-1", detail("#stored"))
        val dataSource = OfflineFakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot("#loaded")))
        val store = store(device.cache(), dataSource)
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()

        store.dispatch(ChannelSettingsIntent.UpdateProfile(name = "renamed", description = "new topic"))
        runCurrent()
        assertEquals("renamed", device.storedRecord()?.channel?.name)
        assertEquals("renamed", device.details.read(detailOwner(), "channel-1")?.name)
        assertEquals("new topic", device.details.read(detailOwner(), "channel-1")?.subtitle)

        store.dispatch(ChannelSettingsIntent.ToggleMute)
        runCurrent()
        assertEquals(true, device.storedRecord()?.channel?.activityNotificationPreference?.activityMuted)
        assertEquals(true, device.details.read(detailOwner(), "channel-1")?.activityNotificationPreference?.activityMuted)

        dataSource.profileResult = DataResult.Error(ScreenError(code = "save_failed", retryable = true))
        store.dispatch(ChannelSettingsIntent.UpdateProfile(name = "never", description = "saved"))
        runCurrent()
        assertEquals("renamed", device.storedRecord()?.channel?.name)
        assertEquals("renamed", device.details.read(detailOwner(), "channel-1")?.name)
        store.clear()
    }

    @Test
    fun leavingDropsTheStoredSettingsAndHeaderDetail() = runTest {
        val device = Device(backgroundScope)
        device.details.record(detailOwner(), "channel-1", detail("#stored"))
        val store = store(device.cache(), OfflineFakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot("#loaded"))))
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()
        assertEquals("#loaded", device.storedRecord()?.channel?.name)

        store.dispatch(ChannelSettingsIntent.Leave)
        runCurrent()

        assertNull(device.storedRecord())
        assertNull(device.details.read(detailOwner(), "channel-1"))
        store.clear()
    }

    @Test
    fun storedHeaderDetailStandsInUntilTheNetworksDetailAndNeverReplacesIt() = runTest {
        val device = Device(backgroundScope)
        device.details.record(detailOwner(), "channel-1", detail("#stored"))

        // Cold start: the hub is empty; the stored detail becomes the header's row.
        val hub = SharedConversationIdentityHub()
        val stored = device.details.read(detailOwner(), "channel-1")!!
        assertTrue(device.details.restore(detailOwner(), "channel-1", hub, stored))
        assertEquals("stored", hub.resolveProjection(ServerId("server-1"), "channel-1")?.title?.removePrefix("#"))

        // The network's detail replaces it ...
        val fresh = detail("#fresh").toCanonicalConversationDirectoryProjection(ServerId("server-1"))!!
        assertTrue(hub.publishCanonical(authority(), fresh))
        assertEquals("fresh", hub.resolveProjection(ServerId("server-1"), "channel-1")?.title?.removePrefix("#"))
        // ... and a late stored read never puts the old one back.
        assertFalse(device.details.restore(detailOwner(), "channel-1", hub, stored))
        assertEquals("fresh", hub.resolveProjection(ServerId("server-1"), "channel-1")?.title?.removePrefix("#"))
    }

    @Test
    fun storedHeaderDetailNeverOutranksALiveHomeRow() = runTest {
        val device = Device(backgroundScope)
        // Stored while previewing as a non-member; Home now knows the user joined.
        device.details.record(detailOwner(), "channel-1", detail("#stored").copy(joined = false))
        val hub = SharedConversationIdentityHub()
        val home = detail("#home").toCanonicalConversationDirectoryProjection(ServerId("server-1"))!!
        assertTrue(hub.publishCandidate(authority(), revision = 1, next = listOf(home).toSharedConversationDirectoryState()))

        val stored = device.details.read(detailOwner(), "channel-1")!!
        assertFalse(device.details.restore(detailOwner(), "channel-1", hub, stored))
        val shown = hub.resolveProjection(ServerId("server-1"), "channel-1")
        assertEquals("home", shown?.title?.removePrefix("#"))
        assertEquals(true, shown?.joined)
    }

    @Test
    fun storedHeaderDetailIsPerAccountAndEnvironment() = runTest {
        val device = Device(backgroundScope)
        device.details.record(detailOwner(), "channel-1", detail("#mine"))

        assertNull(device.details.read(detailOwner(accountId = "user-2"), "channel-1"))
        assertNull(device.details.read(detailOwner(environmentId = "https://staging.raft.build/api"), "channel-1"))
        assertEquals("#mine", device.details.read(detailOwner(), "channel-1")?.name)
    }

    private suspend fun TestScope.loadOnce(device: Device, snapshot: ChannelSettingsSnapshot) {
        val store = store(device.cache(), OfflineFakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot)))
        store.dispatch(ChannelSettingsIntent.Load)
        runCurrent()
        assertEquals(snapshot.channel.name, device.storedRecord()?.channel?.name)
        store.clear()
    }

    private fun CoroutineScope.store(
        cache: ChannelSettingsSnapshotCache,
        dataSource: ChannelSettingsDataSource,
    ): ChannelSettingsStore =
        ChannelSettingsStore(
            route = ChannelSettingsRoute(ServerId("server-1"), ChannelId("channel-1")),
            owner = AuthenticatedRouteOwner(Environment, "user-1", "server-1"),
            dataSource = dataSource,
            snapshotCache = cache,
            initialSharedHandoff = ChannelSettingsSharedHandoff.Unavailable,
            initialAuthorityAccepted = true,
            scope = this,
        )

    private fun detailOwner(
        environmentId: String = Environment,
        accountId: String = "user-1",
    ): StoredConversationDetailOwner = StoredConversationDetailOwner(environmentId, accountId, "server-1")

    private fun authority(): SharedConversationIdentityAuthority =
        SharedConversationIdentityAuthority(
            environmentId = Environment,
            accountId = "user-1",
            serverId = "server-1",
            authGeneration = 0L,
        )

    private fun detail(name: String): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId("channel-1"),
            channelId = ChannelId("channel-1"),
            channelType = "channel",
            kind = ConversationKind.Channel,
            name = name,
        )

    private fun snapshot(name: String): ChannelSettingsSnapshot =
        ChannelSettingsSnapshot(
            channel =
                HomeChannelRow(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    channelType = "channel",
                    name = name,
                    activityNotificationPreference = ChannelActivityNotificationPreference(activityMuteSupported = true),
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

    private companion object {
        const val Environment = "https://api.raft.build/api"
    }
}

private class OfflineFakeChannelSettingsDataSource(
    private val loadResult: DataResult<ChannelSettingsSnapshot>? = null,
    private val loadGate: CompletableDeferred<DataResult<ChannelSettingsSnapshot>>? = null,
) : ChannelSettingsDataSource {
    var profileResult: DataResult<ChannelProfileUpdateResult>? = null

    override suspend fun load(): DataResult<ChannelSettingsSnapshot> = loadGate?.await() ?: requireNotNull(loadResult)

    override suspend fun setMuted(muted: Boolean): DataResult<ChannelActivityMuteSettings> =
        DataResult.Content(
            ChannelActivityMuteSettings(
                channelId = ChannelId("channel-1"),
                activityNotificationPreference =
                    ChannelActivityNotificationPreference(activityMuted = muted, activityMuteSupported = true),
            )
        )

    override suspend fun setPinned(pinned: Boolean): DataResult<SettingsServerSidebarOrderSnapshot> =
        DataResult.Content(SettingsServerSidebarOrderSnapshot())

    override suspend fun updateProfile(name: String, description: String): DataResult<ChannelProfileUpdateResult> =
        profileResult ?: DataResult.Content(ChannelProfileUpdateResult(ChannelId("channel-1"), name, description))

    override suspend fun applySlackBridge(channel: HomeChannelRow, slackChannelId: String?): DataResult<ChannelSlackBridgeState?> =
        DataResult.Content(null)

    override suspend fun makePrivate(): DataResult<Unit> = DataResult.Content(Unit)

    override suspend fun makePublic(): DataResult<Unit> = DataResult.Content(Unit)

    override suspend fun leave(): DataResult<Unit> = DataResult.Content(Unit)

    override suspend fun unarchive(): DataResult<HomeChannelRow> =
        DataResult.Content(HomeChannelRow(threadId = ThreadId("channel-1"), channelId = ChannelId("channel-1"), name = "#x"))

    override suspend fun updateGuestPolicy(update: ChannelGuestPolicyUpdate): DataResult<HomeChannelRow> =
        DataResult.Content(HomeChannelRow(threadId = ThreadId("channel-1"), channelId = ChannelId("channel-1"), name = "#x"))

    override suspend fun archive(): DataResult<Unit> = DataResult.Content(Unit)

    override suspend fun delete(): DataResult<Unit> = DataResult.Content(Unit)

    override suspend fun disconnect(): DataResult<Unit> = DataResult.Content(Unit)
}
