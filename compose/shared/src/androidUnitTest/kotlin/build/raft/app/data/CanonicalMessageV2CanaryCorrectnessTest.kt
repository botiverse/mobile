package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.realtime.MessageShadowObserver
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2CommitResult
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoadResult
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoader
import build.raft.app.network.sync.CanonicalMessageV2Store
import build.raft.app.network.sync.CanonicalMessageV2StoreWriteResult
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.ProductionFace
import build.raft.app.network.sync.RestoredCanonicalMessageV2Scope
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.decodeCanonicalMessageWire
import build.raft.app.network.sync.toCanonicalMessageV2Envelope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task #168 correctness fixes of the single-scope durable canary, on the real SQLite store:
 * #1 a failed durable write still displays; #2 a late `New` never overwrites a stored fact; #3 a frame
 * older than the window is never written; #4 the per-scope cap moves the oldest edge; #5 one realtime
 * frame is written once however many entries deliver it.
 */
class CanonicalMessageV2CanaryCorrectnessTest {
    private val grant = RealtimeGrantRecord(
        slotKey = RealtimeSlotKey(scopeLeaseId = "scope-1", serverId = "server-1"),
        principalId = "principal-1",
        environmentId = "env-test",
        authGeneration = 1L,
        connectionFamilyId = "family-1",
        connectionEpochId = "epoch-1",
    )
    private val partition = grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
    private val scope = SyncScopeKey(partition, "channel-1", "channel")

    // ---- #1 Held never hides the frame -------------------------------------------------------------

    @Test
    fun aSnapshotThatIsUnavailableStillDisplaysTheFrame() = withFreshDatabase { database ->
        val rig = Rig(database)
        rig.snapshotUnavailable = true
        val (owner, projected) = rig.displayingOwner()
        val event = rig.messageNew("m1", 1L)

        assertTrue(runBlocking { owner.accept(grant, event, source = "test") })

        assertEquals(listOf<RealtimeEvent>(event), projected, "the durable write failed; display must not")
        assertEquals(0, rig.store.snapshotWrites)
    }

    @Test
    fun aCommittedSnapshotDisplaysTheFrameExactlyAsBefore() = withFreshDatabase { database ->
        val rig = Rig(database)
        val (owner, projected) = rig.displayingOwner()
        val event = rig.messageNew("m1", 1L)

        assertTrue(runBlocking { owner.accept(grant, event, source = "test") })

        assertEquals(listOf<RealtimeEvent>(event), projected)
        assertEquals(1, rig.store.snapshotWrites)
    }

    // ---- #2 a late New is insert-only ---------------------------------------------------------------

    @Test
    fun aLateNewNeverOverwritesTheEditedFactAlreadyOnDisk() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            // The window comes from a snapshot that already has m1's edit.
            rig.baseline(rig.input("m1", 1L, content = "edited", kind = CanonicalMessageFoldKind.Updated))

            rig.accept(rig.input("m1", 1L, content = "original"))

            assertEquals(mapOf("m1" to "edited"), rig.storedContents())
            assertEquals(1, rig.store.incrementalWrites)
        }
    }

    @Test
    fun aNewForAnAbsentFactIsInserted() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.baseline(rig.input("m1", 1L, content = "edited", kind = CanonicalMessageFoldKind.Updated))

            rig.accept(rig.input("m2", 2L, content = "original"))

            assertEquals(mapOf("m1" to "edited", "m2" to "original"), rig.storedContents())
            assertEquals(1, rig.store.incrementalWrites)
        }
    }

    @Test
    fun aSnapshotStillReplacesAStoredFact() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.baseline(rig.input("m1", 1L, content = "first"))
            rig.accept(rig.input("m1", 1L, content = "edited", kind = CanonicalMessageFoldKind.Updated))

            assertEquals(mapOf("m1" to "edited"), rig.storedContents())
            assertEquals(2, rig.store.snapshotWrites)
        }
    }

    // ---- #3 below-window frames are display-only ----------------------------------------------------

    @Test
    fun aFrameInsideTheWindowIsWritten() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.baseline(rig.input("m20", 20L), oldestLoadedSeq = 10L, newestLoadedSeq = 20L)

            assertEquals(
                CanonicalMessageV2CanaryOutcome.Committed,
                rig.accept(rig.input("m15", 15L)),
            )

            assertEquals(setOf("m15", "m20"), rig.storedContents().keys)
            assertEquals(10L, rig.storedCursor()?.oldestLoadedSeq)
        }
    }

    @Test
    fun aFrameBelowTheWindowIsNeverWrittenAndNeverMovesTheEdge() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.baseline(rig.input("m20", 20L), oldestLoadedSeq = 10L, newestLoadedSeq = 20L)

            assertEquals(
                CanonicalMessageV2CanaryOutcome.NotSelected,
                rig.accept(rig.input("m5", 5L)),
                "display only",
            )

            assertEquals(setOf("m20"), rig.storedContents().keys)
            assertEquals(10L, rig.storedCursor()?.oldestLoadedSeq)
            assertEquals(0, rig.store.incrementalWrites)
            assertTrue(rig.logs.any { it.startsWith("canonical_v2_canary skip=below_window") })
        }
    }

    // ---- #4 the per-scope cap moves the oldest edge -------------------------------------------------

    @Test
    fun aScopeAtTheCapKeepsItsBeginning() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.fillThenAppendOne(storedFacts = 499)

            val cursor = rig.storedCursor()!!
            assertEquals(1L, cursor.oldestLoadedSeq)
            assertEquals(true, cursor.hasLoadedOldest)
            assertEquals(cursor, rig.engine.currentCursor(scope))
            assertEquals(500, rig.storedContents().size)
        }
    }

    @Test
    fun aScopeOverTheCapMovesItsOldestEdgeAndForgetsItsBeginning() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.fillThenAppendOne(storedFacts = 500)

            val cursor = rig.storedCursor()!!
            assertEquals(2L, cursor.oldestLoadedSeq, "seq 1 was pruned")
            assertEquals(false, cursor.hasLoadedOldest, "the window no longer reaches the beginning")
            assertEquals(cursor, rig.engine.currentCursor(scope), "the engine commits what the store wrote")
            assertEquals(500, rig.storedContents().size)
        }
    }

    // ---- #5 one frame, one write --------------------------------------------------------------------

    @Test
    fun oneRealtimeFrameThroughEveryEntryIsWrittenOnce() = withFreshDatabase { database ->
        val rig = Rig(database)
        val processOwner = rig.owner()
        val shellOwner = rig.owner()
        runBlocking {
            // Android: AppShell → process owner, then the shell owner. iOS / OHOS additionally the
            // process ingress coordinator → process owner.
            suspend fun deliverEverywhere(event: RealtimeEvent) {
                processOwner.acceptProcessMutation(grant, event, "process_normal_realtime", presenceTransportEpoch = 3L)
                processOwner.acceptProcessMutation(grant, event, "process_realtime", presenceTransportEpoch = 3L)
                shellOwner.accept(grant, event, source = "app_shell", transportEpoch = 3L)
            }

            deliverEverywhere(rig.messageNew("m1", 1L))
            assertEquals(1, rig.store.snapshotWrites, "the baselining frame snapshots once")

            deliverEverywhere(rig.messageNew("m2", 2L))
            assertEquals(1, rig.store.incrementalWrites, "the next frame is appended once")
            assertEquals(setOf("m1", "m2"), rig.storedContents().keys)
        }
    }

    @Test
    fun twoRealtimeFramesThroughOneEntryAreWrittenTwice() = withFreshDatabase { database ->
        val rig = Rig(database)
        val processOwner = rig.owner()
        runBlocking {
            processOwner.acceptProcessMutation(grant, rig.messageNew("m1", 1L), "process_realtime", presenceTransportEpoch = 3L)
            assertEquals(1, rig.store.snapshotWrites)

            processOwner.acceptProcessMutation(grant, rig.messageNew("m2", 2L), "process_realtime", presenceTransportEpoch = 3L)
            processOwner.acceptProcessMutation(grant, rig.messageNew("m3", 3L), "process_realtime", presenceTransportEpoch = 3L)
            assertEquals(2, rig.store.incrementalWrites)
            assertEquals(setOf("m1", "m2", "m3"), rig.storedContents().keys)
        }
    }

    @Test
    fun twoDistinctEditsOfOneMessageAreBothWritten() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.baseline(rig.input("m1", 1L, content = "first"))
            rig.accept(rig.input("m1", 1L, content = "edit-1", kind = CanonicalMessageFoldKind.Updated))
            rig.accept(rig.input("m1", 1L, content = "edit-2", kind = CanonicalMessageFoldKind.Updated))
            // The same edit through another entry is that frame again.
            rig.accept(rig.input("m1", 1L, content = "edit-2", kind = CanonicalMessageFoldKind.Updated))

            assertEquals(3, rig.store.snapshotWrites)
            assertEquals(mapOf("m1" to "edit-2"), rig.storedContents())
        }
    }

    // ---- rig ------------------------------------------------------------------------------------------

    private inner class Rig(database: SlockSqlDatabase) {
        val store = CountingStore(SqlDelightCanonicalMessageV2Store(database, now = { 100L }))
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val logs = mutableListOf<String>()
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { true },
            generationEngine = engine,
            logger = { logs += it },
        )
        var snapshotUnavailable = false

        /** What the server returns for a snapshot: the latest known version of each message. */
        private val serverMessages = linkedMapOf<String, CanonicalMessageFoldInput>()
        private var serverWindow: Pair<Long, Long>? = null

        private val mapper = RealtimeEventMapper(
            messageShadowObserver = MessageShadowObserver { _, raw, _ ->
                decodeCanonicalMessageWire(raw, ProductionFace.MessageNew)
            },
        )

        val loader = CanonicalMessageV2SnapshotLoader { request ->
            if (snapshotUnavailable) return@CanonicalMessageV2SnapshotLoader CanonicalMessageV2SnapshotLoadResult.Unavailable("offline")
            val envelopes = serverMessages.values.map(::envelope)
            val seqs = envelopes.map { it.fact.seq }
            val (oldest, newest) = serverWindow ?: (seqs.min() to seqs.max())
            CanonicalMessageV2SnapshotLoadResult.Loaded(
                CanonicalMessageV2SnapshotFrame(
                    scope = request.scope,
                    envelopes = envelopes,
                    cursor = PersistedCursor(
                        latestSeq = newest,
                        oldestLoadedSeq = oldest,
                        newestLoadedSeq = newest,
                        hasLoadedOldest = false,
                        hasLoadedNewest = true,
                    ),
                ),
            )
        }

        private val gateway = CanonicalMessageV2OwnerGateway { _, foldInput, transportEpoch ->
            remember(foldInput)
            canary.accept(partition, foldInput, loader, transportEpoch)
        }

        private fun remember(input: CanonicalMessageFoldInput) {
            serverMessages[input.row.id] = input
        }

        suspend fun baseline(
            input: CanonicalMessageFoldInput,
            oldestLoadedSeq: Long? = null,
            newestLoadedSeq: Long? = null,
        ) {
            serverWindow = if (oldestLoadedSeq != null && newestLoadedSeq != null) oldestLoadedSeq to newestLoadedSeq else null
            remember(input)
            assertIs<CanonicalMessageV2CanaryOutcome.Committed>(
                canary.accept(partition, input, loader),
            )
            serverWindow = null
        }

        /** A realtime frame; the server's own copy is not changed by a late `New`. */
        suspend fun accept(input: CanonicalMessageFoldInput): CanonicalMessageV2CanaryOutcome {
            if (input.kind == CanonicalMessageFoldKind.Updated || input.row.id !in serverMessages) remember(input)
            return canary.accept(partition, input, loader)
        }

        suspend fun fillThenAppendOne(storedFacts: Int) {
            val envelopes = (1..storedFacts).map { envelope(input("m$it", it.toLong())) }
            assertEquals(
                CanonicalMessageV2CommitResult.Committed,
                engine.applySnapshot(
                    CanonicalMessageV2SnapshotFrame(
                        scope = scope,
                        envelopes = envelopes,
                        cursor = PersistedCursor(
                            latestSeq = storedFacts.toLong(),
                            oldestLoadedSeq = 1L,
                            newestLoadedSeq = storedFacts.toLong(),
                            hasLoadedOldest = true,
                            hasLoadedNewest = true,
                        ),
                    ),
                ),
            )
            val next = storedFacts + 1L
            assertEquals(
                CanonicalMessageV2CommitResult.Committed,
                engine.applyIncremental(
                    CanonicalMessageV2IncrementalFrame(
                        scope = scope,
                        envelopes = listOf(envelope(input("m$next", next))),
                        cursor = engine.currentCursor(scope)!!.copy(latestSeq = next, newestLoadedSeq = next),
                        insertOnly = true,
                    ),
                ),
            )
        }

        suspend fun storedContents(): Map<String, String> =
            store.restore(scope).envelopes.associate { it.fact.id to it.fact.content }

        suspend fun storedCursor(): PersistedCursor? = store.readCursor(scope)

        fun owner(): SharedRealtimeDomainEventOwner =
            SharedRealtimeDomainEventOwner(
                runIfCurrentGrant = { _, block ->
                    block()
                    true
                },
                runWithCurrentMessageGrant = { _, block ->
                    block(CurrentMessageCommitContext(partition))
                    true
                },
            ).also { it.installCanonicalMessageV2OwnerGateway(gateway) }

        fun displayingOwner(): Pair<SharedRealtimeDomainEventOwner, List<RealtimeEvent>> {
            val projected = mutableListOf<RealtimeEvent>()
            val owner = owner()
            owner.registerGuardedSinks(
                RealtimeGuardedDomainEventSinks(applyHomeShell = { _, event -> projected += event }),
            )
            return owner to projected
        }

        /** Real `message:new` wire JSON (server `projectMessageSocketPayload` + rich aggregates) via the mapper. */
        fun messageNew(id: String, seq: Long): MessageNew {
            val event = mapper.map(
                RealtimeSocketEvents.MessageNew,
                """
                {
                  "id": "$id", "seq": $seq, "channelId": "channel-1",
                  "senderType": "user", "senderId": "user-1", "randomId": null,
                  "messageType": "chat", "content": "body-$id", "actionMetadata": null, "threadId": null,
                  "taskStatus": null, "taskNumber": null, "taskAssigneeType": null, "taskAssigneeId": null,
                  "taskClaimedAt": null, "taskCompletedAt": null,
                  "createdAt": "2026-07-17T00:00:00.000Z", "updatedAt": "2026-07-17T00:00:00.000Z",
                  "senderName": "Alice", "senderDescription": null, "senderMembershipStatus": null,
                  "mentions": [], "attachments": [], "reactions": [],
                  "conversationContext": { "channelType": "channel" }
                }
                """.trimIndent(),
            )
            val messageNew = assertIs<MessageNew>(event)
            requireNotNull(messageNew.decodedCanonical) { "the wire frame must decode canonically" }
            return messageNew
        }

        fun input(
            id: String,
            seq: Long,
            content: String = "body-$id",
            kind: CanonicalMessageFoldKind = CanonicalMessageFoldKind.New,
        ) = CanonicalMessageFoldInput(
            kind = kind,
            row = CanonicalMessageRow(
                id = id,
                channelId = "channel-1",
                seq = seq,
                senderType = "user",
                senderId = "user-1",
                content = content,
                messageType = "chat",
                createdAt = "2026-07-17T00:00:00.000Z",
                senderName = "Alice",
                conversationContext = CanonicalConversationContextWire("channel"),
            ),
            presentAggregates = MessageAggregateField.entries.toList(),
        )

        private fun envelope(input: CanonicalMessageFoldInput): CanonicalMessageEnvelopeV2 =
            requireNotNull(input.toCanonicalMessageV2Envelope(partition, replyCount = 0)) { "fixture must normalize" }
    }

    private class CountingStore(private val delegate: CanonicalMessageV2Store) : CanonicalMessageV2Store {
        var snapshotWrites = 0
        var incrementalWrites = 0

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
            snapshotWrites += 1
            return delegate.applySnapshot(frame)
        }

        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
            incrementalWrites += 1
            return delegate.applyIncremental(frame)
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope = delegate.restore(scope)
        override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> = delegate.clearScope(scope)
        override suspend fun clearPartition(partition: DurablePartitionKey) = delegate.clearPartition(partition)
        override suspend fun knownPartitions(): List<DurablePartitionKey> = delegate.knownPartitions()
        override suspend fun baselinedScopeKinds(partition: DurablePartitionKey, scopeId: SyncScopeId): List<String> =
            delegate.baselinedScopeKinds(partition, scopeId)
        override suspend fun readCursor(scope: SyncScopeKey): PersistedCursor? = delegate.readCursor(scope)
    }

    private fun withFreshDatabase(block: (SlockSqlDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SlockSqlDatabase.Schema.create(driver).value
            block(SlockSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }
}
