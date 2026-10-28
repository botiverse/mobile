package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.realtime.MessageShadowObserver
import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.SyncResumeResponse
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2ScopeContinuity
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.ProductionFace
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.decodeCanonicalMessageWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Message storage design 2026-10-27, Phase 3b (P4): `sync:resume:response` messages are stored before the realtime
 * applied seq moves past them. The server's resume rows carry no `conversationContext` and no `randomId`, so the
 * scope comes from the stored header (option (a)). Real SQLite file, the real process owner with its cursor store,
 * the real mapper on resume-shaped wire rows; a restart is a new driver + store on the same file.
 */
class CanonicalMessageV2ResumeIngestRoundTripTest {
    @Test
    fun resumeMessagesOfKnownScopesAreStoredBeforeTheAppliedSeqMoves() = withFile { file ->
        file.rig().session { rig ->
            rig.cursorAt(29L)
            rig.connect(1L)
            // Headers: channel-1 (live message), thread-1 (live reply under channel-1 / root-1).
            assertTrue(rig.deliver(rig.messageNew(channel, "m5", 5L)))
            assertTrue(rig.deliver(rig.replyNew(thread, "r6", 6L)))
            rig.onAppliedSeqSave = { rig.factsOnDisk() }
            val resume = rig.resume(
                currentSeq = 34L,
                rig.resumeRow("m30", 30L, channel.scopeId),
                rig.resumeRow("m31", 31L, "channel-unknown"), // never entered nor seen live: no header
                rig.resumeRow("m32", 32L, channel.scopeId, senderId = "user-1"), // the user's own message
                rig.resumeRow("r33", 33L, thread.scopeId),
                rig.resumeRow("m34", 34L, channel.scopeId, threadId = "thread-9"), // a thread root: reply count unknown
            )
            assertTrue(rig.deliver(resume))
            assertEquals(setOf("m5", "r6", "m30", "r33"), rig.factsAtFirstSave, "facts on disk when the applied seq moved")
            assertEquals(34L, rig.appliedSeq())
            assertTrue(rig.logs.any { "canonical_v2_resume" in it && "written=2" in it && "no_header:1" in it && "own_message:1" in it && "thread_root:1" in it }, rig.logs.toString())
        }
        file.rig().session { restarted ->
            assertEquals(listOf(5L, 30L), restarted.coldSeqs(channel))
            assertEquals(listOf(6L, 33L), restarted.coldReplySeqs(thread))
            assertEquals(emptyList(), restarted.segments(channel), "a resume claims nothing")
        }
    }

    @Test
    fun aKillBetweenTheCommitAndTheAppliedSeqSaveReplaysWithoutDuplicates() = withFile { file ->
        val kv = InMemoryRaftKeyValueStore()
        file.rig(kv).session { rig ->
            rig.cursorAt(29L)
            rig.connect(1L)
            assertTrue(rig.deliver(rig.messageNew(channel, "m5", 5L)))
            rig.killOnAppliedSeqSave = true
            assertFailsWith<IllegalStateException> { rig.deliver(rig.resume(30L, rig.resumeRow("m30", 30L, channel.scopeId))) }
        }
        file.rig(kv).session { restarted ->
            assertEquals(29L, restarted.appliedSeq(), "the kill left the cursor behind")
            assertEquals(listOf(5L, 30L), restarted.coldSeqs(channel), "the fact was committed before the kill")
            // The replay after the restart stores nothing twice and moves the cursor.
            restarted.connect(2L)
            assertTrue(restarted.deliver(restarted.resume(30L, restarted.resumeRow("m30", 30L, channel.scopeId))))
            assertEquals(30L, restarted.appliedSeq())
            assertEquals(listOf(5L, 30L), restarted.coldSeqs(channel))
        }
    }

    @Test
    fun anAmbiguousOrMissingHeaderSkipsTheRowAndNoClaimGrowsOverIt() = withFile { file ->
        file.rig().session { rig ->
            rig.cursorAt(9L)
            rig.connect(1L)
            rig.enter(channel, 10L, 20L)
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(channel))
            // A second header for the same channel id (another kind): the id is ambiguous.
            assertTrue(rig.deliver(rig.messageNew(SyncScopeKey(partition, channel.scopeId, "private"), "p21", 21L)))
            assertTrue(rig.deliver(rig.resume(30L, rig.resumeRow("m30", 30L, channel.scopeId))))
            assertTrue(rig.logs.any { "canonical_v2_resume" in it && "ambiguous_header:1" in it }, rig.logs.toString())
            // Same connection: without the resume's hole report, 40 would extend the head over the skipped 30.
            assertTrue(rig.deliver(rig.messageNew(channel, "m40", 40L)))
            assertEquals(listOf(10L to 20L), rig.segments(channel))
            assertEquals(listOf(10L, 20L, 40L), rig.seqsInScope(channel), "30 skipped, 40 stored loose")
        }
    }

    // ---- rig -----------------------------------------------------------------------------------------

    private class TestFile(val path: Path) {
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        private var created = false

        fun rig(kv: InMemoryRaftKeyValueStore = InMemoryRaftKeyValueStore()): Rig {
            val driver = JdbcSqliteDriver(url)
            if (!created) {
                RaftSqlDatabase.Schema.create(driver).value
                created = true
            }
            return Rig(driver, kv)
        }
    }

    private class Rig(private val driver: JdbcSqliteDriver, kv: InMemoryRaftKeyValueStore) : AutoCloseable {
        val database = RaftSqlDatabase(driver)
        val logs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        var onAppliedSeqSave: (() -> Set<String>)? = null
        var factsAtFirstSave: Set<String>? = null
        @Volatile var killOnAppliedSeqSave = false
        private val observedKv = object : RaftKeyValueStore {
            override fun getString(key: String): String? = kv.getString(key)
            override fun putString(key: String, value: String?) {
                if (killOnAppliedSeqSave) throw IllegalStateException("killed before the applied seq was saved")
                if (factsAtFirstSave == null) factsAtFirstSave = onAppliedSeqSave?.invoke()
                kv.putString(key, value)
            }
        }
        private val cursorStore = RealtimeAppliedSeqCursorStore(observedKv)
        private val store = SqlDelightCanonicalMessageV2Store(database, now = { 1_000L })
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine, logger = { logs += it })
        private val realtime = CanonicalMessageV2RealtimeIngest(
            tracker = canary,
            generationEngine = engine,
            isEnabledForServer = { true },
            logger = { logs += it },
            dispatcher = Dispatchers.IO,
        )
        private val resumeIngest = CanonicalMessageV2ResumeIngest(
            tracker = canary,
            generationEngine = engine,
            isEnabledForServer = { true },
            logger = { logs += it },
            dispatcher = Dispatchers.IO,
        )
        private val grant = canonicalV2TestGrant()
        private val owner = SharedRealtimeDomainEventOwner(
            cursorStore = cursorStore,
            runIfCurrentGrant = { _, block ->
                block()
                true
            },
            runWithCurrentMessageGrant = { captured, block ->
                block(CurrentMessageCommitContext(captured.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)))
                true
            },
            canonicalMessageV2CommitTimeoutMs = 10_000L,
        ).also { owner ->
            owner.installCanonicalMessageV2OwnerGateway(RealtimeIngestCanonicalMessageV2OwnerGateway(realtime, canary))
            owner.installCanonicalMessageV2ResumeGateway(RealtimeResumeCanonicalMessageV2Gateway(resumeIngest))
            owner.registerGuardedSinks(RealtimeGuardedDomainEventSinks(log = { logs += it }))
        }
        private val mapper = RealtimeEventMapper(
            messageShadowObserver = MessageShadowObserver { eventName, raw, _ ->
                val face = if (eventName == RealtimeSocketEvents.MessageUpdated) ProductionFace.EnrichedUpdated else ProductionFace.MessageNew
                decodeCanonicalMessageWire(raw, face)
            },
        )

        suspend fun connect(epoch: Long) {
            canary.noteTransportEpoch(partition, epoch)
            canary.noteRoomsJoined(partition, epoch)
        }

        fun cursorAt(seq: Long) = cursorStore.saveAppliedLastSeq(ServerId(partition.serverId), seq)

        fun appliedSeq(): Long = cursorStore.readAppliedLastSeq(ServerId(partition.serverId))

        suspend fun enter(scope: SyncScopeKey, vararg seqs: Long) {
            val page = adjacentPageOf(*seqs.map { adjacentPageNode("m$it", it, channelId = scope.scopeId) }.toTypedArray())
            CanonicalMessageV2EnteredScopeLoader(
                canary = canary,
                generationEngine = engine,
                fetcher = CanonicalMessageV2RestSnapshotFetcher(
                    RecordingNetworkClient("""{"messages":$page,"historyLimited":false}""", channelIds = setOf(scope.scopeId)),
                ),
                isEnabledForServer = { true },
                logger = { logs += it },
            ).loadOnEnter(partition, scope.scopeId) { true }
        }

        suspend fun deliver(event: RealtimeEvent): Boolean =
            owner.acceptProcessMutation(grant, event, source = "test", presenceTransportEpoch = null)

        fun factsOnDisk(): Set<String> =
            database.queries.selectServerBackedMessagesForChannel(
                environmentId = partition.environmentId,
                serverId = partition.serverId,
                principalId = partition.principalId,
                consumerId = partition.consumerId,
                scopeKind = channel.scopeKind,
                channelId = channel.scopeId,
            ).executeAsList().map { it.id }.toSet() +
                database.queries.selectServerBackedMessagesForChannel(
                    environmentId = partition.environmentId,
                    serverId = partition.serverId,
                    principalId = partition.principalId,
                    consumerId = partition.consumerId,
                    scopeKind = thread.scopeKind,
                    channelId = thread.scopeId,
                ).executeAsList().map { it.id }

        fun seqsInScope(scope: SyncScopeKey): List<Long> =
            database.queries.selectServerBackedMessagesForChannel(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            ).executeAsList().map { it.seq }.sorted()

        private fun coldReader() = CanonicalMessageV2DurableLastKnownReader(
            SqlDelightCanonicalMessageV2Store(database, now = { 2_000L }),
            isEnabledForServer = { true },
        )

        suspend fun coldSeqs(scope: SyncScopeKey): List<Long> =
            assertNotNull(coldReader().read(partition, scope.scopeId)).envelopes.map { it.fact.seq }.sorted()

        suspend fun coldReplySeqs(scope: SyncScopeKey): List<Long> =
            assertNotNull(coldReader().readReplyThread(partition, scope.scopeId, ThreadDurableReplyParent("channel-1", "root-1")))
                .envelopes.map { it.fact.seq }.sorted()

        suspend fun segments(scope: SyncScopeKey): List<Pair<Long, Long>> =
            SqlDelightCanonicalMessageV2Store(database, now = { 2_000L }).readSegments(scope).map { it.lowSeq to it.highSeq }

        fun messageNew(scope: SyncScopeKey, id: String, seq: Long): MessageNew =
            assertIs<MessageNew>(
                mapper.map(RealtimeSocketEvents.MessageNew, liveWire(scope.scopeId, id, seq, """{ "channelType": "${scope.scopeKind}" }""")),
            )

        fun replyNew(scope: SyncScopeKey, id: String, seq: Long): MessageNew =
            assertIs<MessageNew>(
                mapper.map(
                    RealtimeSocketEvents.MessageNew,
                    liveWire(
                        scope.scopeId,
                        id,
                        seq,
                        """{ "channelType": "thread", "parentChannelId": "channel-1", "parentChannelType": "channel", "parentMessageId": "root-1" }""",
                    ),
                ),
            )

        /** A live `message:new` as the production send pipeline emits it (no senderDescription / reactions). */
        private fun liveWire(channelId: String, id: String, seq: Long, context: String) = """
            {
              "id": "$id", "seq": $seq, "channelId": "$channelId", "senderType": "user", "senderId": "user-2",
              "randomId": null, "messageType": "chat", "content": "body-$id", "actionMetadata": null, "threadId": null,
              "createdAt": "2026-07-17T00:00:00.000Z", "updatedAt": "2026-07-17T00:00:00.000Z",
              "senderName": "Bob", "senderMembershipStatus": null, "mentions": [], "attachments": [],
              "conversationContext": $context
            }
        """.trimIndent()

        /**
         * One row of a `sync:resume:response` in the server's shape (slock `syncMessagesWithHorizon` +
         * `enrichWithSenderNames`): enriched aggregates, but no `conversationContext` and no `randomId`.
         */
        fun resumeRow(id: String, seq: Long, channelId: String, senderId: String = "user-2", threadId: String? = null): String {
            val thread = threadId?.let { "\"$it\"" } ?: "null"
            return """{"id":"$id","seq":$seq,"channelId":"$channelId","senderType":"user","senderId":"$senderId",""" +
                """"messageType":"chat","content":"body-$id","threadId":$thread,"taskStatus":null,"taskNumber":null,""" +
                """"taskAssigneeType":null,"taskAssigneeId":null,"taskClaimedAt":null,"taskCompletedAt":null,""" +
                """"actionMetadata":null,"createdAt":"2026-07-17T00:00:00.000Z","updatedAt":"2026-07-17T00:00:00.000Z",""" +
                """"delegatedByAgentId":null,"deletedAt":null,"senderName":"Bob","senderDescription":null,""" +
                """"senderMembershipStatus":null,"reactions":[],"mentions":[],"attachments":[]}"""
        }

        fun resume(currentSeq: Long, vararg rows: String): SyncResumeResponse =
            assertIs<SyncResumeResponse>(
                mapper.map(
                    RealtimeSocketEvents.SyncResumeResponse,
                    """{"messages":[${rows.joinToString(",")}],"currentSeq":$currentSeq,"hasMore":false}""",
                ),
            ).also { assertEquals(rows.size, it.decodedCanonical.size, "every resume row must decode") }

        override fun close() {
            realtime.close()
            resumeIngest.close()
            driver.close()
        }
    }

    private fun <T> Rig.session(block: suspend (Rig) -> T): T = try {
        runBlocking { withTimeout(30_000L) { block(this@session) } }
    } finally {
        close()
    }

    private fun <T> withFile(block: (TestFile) -> T): T {
        val path = Files.createTempFile("slock-resume-ingest-", ".db").also { it.deleteIfExists() }
        return try {
            block(TestFile(path))
        } finally {
            path.deleteIfExists()
        }
    }

    private companion object {
        val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val channel = SyncScopeKey(partition, "channel-1", "channel")
        val thread = SyncScopeKey(partition, "thread-1", "thread")
    }
}
