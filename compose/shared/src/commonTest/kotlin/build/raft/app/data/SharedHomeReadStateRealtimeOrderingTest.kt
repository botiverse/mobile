package build.raft.app.data

import build.raft.app.data.realtime.PerReceiverReadStateCheckpoint
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.ReadStateV1Fact
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.ReadStateV1ProductionOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SharedHomeReadStateRealtimeOrderingTest {
    @Test
    fun selectedRealtimeCommitsBeforeProjectionWithoutWritingLegacyCheckpoint() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val durable = TestReadStateV1Store(beforeCommit = { fact ->
            if (fact.readStateVersion == 2L && fact.maxReadSeq == 5L) {
                commitStarted.complete(Unit)
                releaseCommit.await()
            }
        })
        val versionStore = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        versionStore.writeReadStateCheckpoint(
            ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1"),
            PerReceiverReadStateCheckpoint(99, 99),
        )
        lateinit var store: SharedHomeStore
        var project = false
        val canary = ReadStateV1ProductionCanary(
            isEnabledForServer = { true },
            store = durable,
            onProjection = { owner, _, fact -> if (project) store.applyReadStateV1Projection(owner, fact) },
            onRebaseline = { _, _, _ -> },
        )
        val api = ReconcileBlockingHomeApi()
        val session = sampleSession()
        store = SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            dataSource = SharedHomeDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(session),
                api = api,
                parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) },
            ),
            scope = scope,
            perReceiverStateVersionStore = versionStore,
            readReceiptOwnerFactory = { serverId, accountId ->
                ReadReceiptOwner("production", accountId, serverId)
            },
            readStateV1Canary = canary,
        )
        store.accept(SharedHomeIntent.Start)
        store.applyReadStateV1Projection(
            ReadStateV1ProductionOwner("staging", "user-1", "server-1"),
            ReadStateV1Fact("channel-1", 50, 50),
        )
        assertEquals(99, store.projectedMaxReadSeq())
        canary.applyHydrate(
            ReadStateV1ProductionOwner("production", "user-1", "server-1"),
            "channel-1",
            1,
            10,
        )
        project = true

        val reconcile = api.blockNextChannels()
        val realtime = async {
            store.accept(
                SharedHomeIntent.ApplyRealtimeEvent(
                    "server-1",
                    PerReceiverReadStateChanged(
                        ServerId("server-1"),
                        listOf(PerReceiverReadStateScope(ChannelId("channel-1"), 5, 2)),
                    ),
                )
            )
        }
        withTimeout(1_000) { commitStarted.await() }
        assertFalse(realtime.isCompleted)
        releaseCommit.complete(Unit)
        realtime.await()
        withTimeout(1_000) { reconcile.started.await() }

        assertEquals(5, store.projectedMaxReadSeq())
        assertEquals(
            PerReceiverReadStateCheckpoint(99, 99),
            versionStore.readReadStateCheckpoint(ServerId("server-1"), MemberId("user-1"), ChannelId("channel-1")),
        )
        scope.cancel()
    }

    private fun SharedHomeStore.projectedMaxReadSeq(): Long? =
        (state.value.content as SharedHomeContentState.Content).snapshot.channels.single().readStateMaxReadSeq

    private fun sampleSession() = StoredAuthSession(
        user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
        accessToken = "token-a",
        refreshToken = "token-r",
        selectedServerId = "server-1",
        servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock")),
    )

    private fun sampleHome(serverId: ServerId) = HomeSnapshot(
        server = ServerSummary(serverId, "Slock"),
        currentMember = MemberProfile(
            id = MemberId("user-1"),
            displayName = "Artin",
            handle = "artin@slock.ai",
            kind = MemberKind.Human,
            presence = Presence.Active,
        ),
        channels = listOf(
            HomeChannelRow(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                name = "general",
            )
        ),
        directMessages = emptyList(),
        unreadTotal = 0,
    )

    private class ReconcileBlockingHomeApi : RaftHomeApi {
        private var nextChannels: CallGate? = null
        fun blockNextChannels() = CallGate().also { nextChannels = it }

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            nextChannels?.also { gate ->
                nextChannels = null
                gate.started.complete(Unit)
                gate.release.await()
            }
            return HomeNetworkResponse(code = 200, bodyText = "[]")
        }

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = "[]")

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = "{}")

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority) =
            HomeNetworkResponse(code = 200, bodyText = "[]")
        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse = error("unused")
    }

    private class CallGate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }
}
