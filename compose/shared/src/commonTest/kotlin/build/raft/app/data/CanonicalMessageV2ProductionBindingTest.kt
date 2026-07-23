package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2Store
import build.raft.app.network.sync.CanonicalMessageV2StoreWriteResult
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.RestoredCanonicalMessageV2Scope
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.TopLevelScopeRefV2
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class CanonicalMessageV2ProductionBindingTest {
    @Test
    fun flagOffDoesNotReadNetworkOrRequireAnAuthSession() = runBlocking {
        val store = RecordingStore()
        val network = RecordingNetworkClient(page(includeReplyCount = true))
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val gateway = ProviderBackedCanonicalMessageV2OwnerGateway(
            canary = CanonicalMessageV2ProductionCanary(
                isEnabledForServer = { false },
                generationEngine = engine,
            ),
            authStore = InMemorySlockAuthSessionStore(null),
            apiEnvironmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore()),
            networkClient = network,
        )

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, gateway.accept(grant(), input()))
        assertEquals(emptyList(), network.paths)
        assertEquals(0, store.snapshots.size)
    }

    @Test
    fun serverPageBecomesFullV2SnapshotBeforeCommit() = runBlocking {
        val store = RecordingStore()
        val network = RecordingNetworkClient(page(includeReplyCount = true))
        val gateway = gateway(store, network)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, gateway.accept(grant(), input()))
        assertEquals(listOf("channels/channel-1", "messages/channel/channel-1"), network.paths)
        assertEquals(1, store.snapshots.size)
        val frame = store.snapshots.single()
        assertEquals(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, frame.scope.consumerId)
        val context = assertIs<CanonicalConversationContextV2.TopLevel>(frame.envelopes.single().context)
        assertIs<TopLevelScopeRefV2.Channel>(context.scope)
        assertEquals(4, frame.envelopes.single().fact.replyCount)
    }

    @Test
    fun pageWithoutAuthoritativeReplyCountCannotEnterSoleApply() = runBlocking {
        val store = RecordingStore()
        val gateway = gateway(store, RecordingNetworkClient(page(includeReplyCount = false)))

        assertIs<CanonicalMessageV2CanaryOutcome.Held>(gateway.accept(grant(), input()))
        assertEquals(0, store.snapshots.size)
    }

    @Test
    fun visitedRawSnapshotRestoresThroughExistingThreadSnapshotProjection() = runBlocking {
        val store = RecordingStore()
        val binding = visitedBinding(store)
        val identity = binding.topLevelIdentity(
            serverId = "server-1",
            principalId = "user-1",
            scopeId = "channel-1",
            scopeKind = "channel",
        )
        val response = HomeNetworkResponse(200, page(includeReplyCount = true))

        assertEquals(response, binding.load(identity) { response })
        val restored = assertNotNull(binding.restore(identity))
        assertEquals(listOf("message-1"), restored.messages.map { it.id.value })
        assertEquals("hello", restored.messages.single().body)
        assertEquals(4, restored.messages.single().replyCount)
        assertEquals("channel-1", restored.conversation.channelId?.value)
    }

    @Test
    fun replyRestoreRequiresExactTypedParentAndRootIdentity() = runBlocking {
        val store = RecordingStore(maxFactsPerScope = 2)
        val binding = visitedBinding(store)
        val reply = binding.threadIdentity(
            serverId = "server-1",
            principalId = "user-1",
            replyScopeId = "reply-stream",
            parentScopeId = "channel-1",
            parentScopeKind = "channel",
            rootMessageId = "root-message",
        )
        val response = HomeNetworkResponse(200, page(includeReplyCount = true, channelId = "reply-stream"))
        val parentIdentity = binding.topLevelIdentity("server-1", "user-1", "channel-1", "channel")
        binding.load(parentIdentity) {
            HomeNetworkResponse(
                200,
                page(
                    includeReplyCount = true,
                    channelId = "channel-1",
                    messages = listOf("message-10" to 10L, "message-11" to 11L),
                ),
            )
        }

        assertEquals(
            response,
            binding.loadReply(
                identity = reply,
                parentBlock = {
                    HomeNetworkResponse(
                        200,
                        page(
                            includeReplyCount = true,
                            channelId = "channel-1",
                            messages = listOf("root-message" to 5L),
                        ),
                    )
                },
                replyBlock = { response },
            ),
        )
        val restored = assertNotNull(binding.restore(reply))
        assertEquals("reply-stream", restored.conversation.thread?.streamChannelId?.value)
        assertEquals("channel-1", restored.conversation.thread?.parentChannelId?.value)
        assertEquals("root-message", restored.conversation.thread?.parentMessageId?.value)
        assertEquals("root-message", restored.parentMessage?.id?.value)
        assertEquals(
            setOf("message-10", "message-11"),
            assertNotNull(binding.restore(parentIdentity)).messages.mapTo(mutableSetOf()) { it.id.value },
        )
        assertEquals(
            setOf("root-message", "message-10", "message-11"),
            store.restore(parentIdentity.scope).envelopes.mapTo(mutableSetOf()) { it.fact.id },
        )

        val wrongRoot = binding.threadIdentity(
            serverId = "server-1",
            principalId = "user-1",
            replyScopeId = "reply-stream",
            parentScopeId = "channel-1",
            parentScopeKind = "channel",
            rootMessageId = "other-root",
        )
        assertEquals(null, binding.restore(wrongRoot))
    }

    @Test
    fun accessRevocationPurgesOnlyTheExactVisitedScope() = runBlocking {
        val store = RecordingStore()
        val binding = visitedBinding(store)
        val revoked = binding.topLevelIdentity("server-1", "user-1", "channel-1", "channel")
        val retained = binding.topLevelIdentity("server-1", "user-1", "channel-2", "channel")
        binding.load(revoked) { HomeNetworkResponse(200, page(true, "channel-1")) }
        binding.load(retained) { HomeNetworkResponse(200, page(true, "channel-2")) }

        binding.load(revoked) { HomeNetworkResponse(403, null) }

        assertEquals(null, binding.restore(revoked))
        assertNotNull(binding.restore(retained))
        Unit
    }

    private fun gateway(
        store: RecordingStore,
        network: RecordingNetworkClient,
    ): ProviderBackedCanonicalMessageV2OwnerGateway {
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        return ProviderBackedCanonicalMessageV2OwnerGateway(
            canary = CanonicalMessageV2ProductionCanary(
                isEnabledForServer = { true },
                generationEngine = engine,
            ),
            authStore = InMemorySlockAuthSessionStore(session()),
            apiEnvironmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore()),
            networkClient = network,
        )
    }

    private fun visitedBinding(store: RecordingStore): CanonicalMessageV2VisitedSnapshotBinding {
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        return CanonicalMessageV2VisitedSnapshotBinding(
            gateway = build.raft.app.network.sync.CanonicalMessageV2VisitedWindowGateway(
                generationEngine = engine,
                isEnabledForServer = { true },
            ),
            authStore = InMemorySlockAuthSessionStore(session()),
            apiEnvironmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore()),
        )
    }

    private fun input() = CanonicalMessageFoldInput(
        kind = CanonicalMessageFoldKind.New,
        row = CanonicalMessageRow(
            id = "message-1",
            channelId = "channel-1",
            seq = 7L,
            senderType = "user",
            senderId = "user-1",
            content = "hello",
            messageType = "chat",
            createdAt = "2026-07-17T00:00:00.000Z",
            senderName = "Alice",
            conversationContext = CanonicalConversationContextWire("channel"),
        ),
        presentAggregates = MessageAggregateField.entries.toList(),
    )

    private fun grant() = RealtimeGrantRecord(
        slotKey = RealtimeSlotKey("scope-1", "server-1"),
        principalId = "user-1",
        authGeneration = 1L,
        connectionFamilyId = "family-1",
        connectionEpochId = "epoch-1",
    )

    private fun session() = StoredAuthSession(
        user = StoredAuthUser(id = "user-1", email = "alice@example.com"),
        accessToken = "access",
        refreshToken = "refresh",
        selectedServerId = "server-1",
        servers = listOf(StoredAuthServer(id = "server-1", name = "Server", slug = "server")),
    )

    private fun page(
        includeReplyCount: Boolean,
        channelId: String = "channel-1",
        messages: List<Pair<String, Long>> = listOf("message-1" to 7L),
    ): String {
        val replyCount = if (includeReplyCount) ",\"replyCount\":4" else ""
        val rows = messages.joinToString(",") { (id, seq) ->
            """{"id":"$id","seq":$seq,"channelId":"$channelId","senderType":"user","senderId":"user-1","content":"hello","messageType":"chat","randomId":null,"threadId":null,"createdAt":"2026-07-17T00:00:00.000Z","senderName":"Alice","senderDescription":null,"senderMembershipStatus":null,"mentions":[],"attachments":[],"reactions":[],"commentRef":null,"actionMetadata":null$replyCount}"""
        }
        return """{"messages":[$rows],"historyLimited":false}"""
    }

    private class RecordingNetworkClient(
        private val pageBody: String,
    ) : SlockNetworkClient {
        val paths = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: build.raft.app.model.ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            paths += path
            return when {
                path == "channels/channel-1" -> HomeNetworkResponse(200, """{"id":"channel-1","type":"channel"}""")
                path == "messages/channel/channel-1" -> HomeNetworkResponse(200, pageBody)
                else -> HomeNetworkResponse(404, null)
            }
        }
    }

    private class RecordingStore(
        private val maxFactsPerScope: Int? = null,
    ) : CanonicalMessageV2Store {
        val snapshots = mutableListOf<CanonicalMessageV2SnapshotFrame>()
        private val states = mutableMapOf<SyncScopeKey, RestoredCanonicalMessageV2Scope>()

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
            snapshots += frame
            states[frame.scope] = RestoredCanonicalMessageV2Scope(frame.envelopes, frame.cursor)
            prune(frame.scope)
            return CanonicalMessageV2StoreWriteResult()
        }

        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
            val previous = states[frame.scope]?.envelopes.orEmpty().associateBy { it.fact.id }
            states[frame.scope] = RestoredCanonicalMessageV2Scope(
                envelopes = (previous + frame.envelopes.associateBy { it.fact.id }).values.toList(),
                cursor = frame.cursor,
            )
            prune(frame.scope)
            return CanonicalMessageV2StoreWriteResult()
        }

        private fun prune(scope: SyncScopeKey) {
            val limit = maxFactsPerScope ?: return
            val state = states[scope] ?: return
            val pinnedRootIds = states.entries.mapNotNullTo(mutableSetOf()) { (candidateScope, candidate) ->
                candidate.cursor
                    ?.takeIf {
                        candidateScope.scopeKind == "thread" &&
                            it.parentScopeKind == scope.scopeKind &&
                            it.parentScopeId == scope.scopeId
                    }
                    ?.rootMessageId
            }
            val retained = state.envelopes
                .filterNot { it.fact.id in pinnedRootIds }
                .sortedByDescending { it.fact.seq }
                .take(limit)
                .mapTo(mutableListOf()) { it }
            retained += state.envelopes.filter { it.fact.id in pinnedRootIds }
            states[scope] = state.copy(envelopes = retained.sortedBy { it.fact.seq })
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope =
            states[scope] ?: RestoredCanonicalMessageV2Scope(emptyList(), null)

        override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> {
            states.remove(scope)
            return setOf(scope)
        }

        override suspend fun clearPartition(partition: DurablePartitionKey) {
            states.keys.removeAll { it.partition == partition }
        }
    }
}
