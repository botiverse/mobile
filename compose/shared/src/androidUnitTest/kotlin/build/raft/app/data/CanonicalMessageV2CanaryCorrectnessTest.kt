package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.realtime.MessageShadowObserver
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2CommitResult
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2RetentionPolicy
import build.raft.app.network.sync.CanonicalMessageV2ScopeContinuity
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2ScopePoisoned
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
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.decodeCanonicalMessageWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task #168 step 3 on the real SQLite store, driven by real `message:new` / `message:updated` wire JSON
 * through [RealtimeEventMapper]: realtime messages are written to the offline cache of EVERY channel that has
 * a window committed in this process (the enter snapshot is the only baseline); a scope without one, and a
 * discontinuous one, is display only and never requests the network. Pairs share seqs and epochs and differ
 * only in the signal under test.
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
    private val channelA = SyncScopeKey(partition, "channel-a", "channel")
    private val channelB = SyncScopeKey(partition, "channel-b", "channel")
    private val channelC = SyncScopeKey(partition, "channel-c", "channel")

    // ---- T1 every windowed channel ------------------------------------------------------------------

    // ---- T2 no window: display only ----------------------------------------------------------------

    // ---- T3 hole A: a frame during the enter fetch survives the enter commit -------------------------

    @Test
    fun t3AFrameDuringTheEnterFetchSurvivesTheEnterCommit() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m10" to 10L)
            rig.enter(channelA)

            // The user re-opens the channel: the page is read (m10) and the response is held.
            val hold = rig.server.holdNextPage()
            val entering = async { rig.enter(channelA) }
            hold.read.await()
            val m11 = rig.messageNew(channelA, "m11", 11L)
            assertTrue(rig.deliver(m11))
            assertEquals(m11, rig.projected.last(), "displayed while the page loads")
            hold.release.complete(Unit)
            entering.await()

            assertEquals(setOf("m10", "m11"), rig.storedIds(channelA), "m11 was not wiped by the enter commit")
            assertTrue(rig.deliver(rig.messageNew(channelA, "m12", 12L)))
            assertEquals(setOf("m10", "m11", "m12"), rig.storedIds(channelA), "no hole")
            assertEquals(12L, rig.storedCursor(channelA)?.latestSeq)
        }
    }

    @Test
    fun aFrameBufferedDuringTheFetchThatThePageMissesBelowItsLatestMarksTheWindowDiscontinuous() =
        withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.has(channelA, "m10" to 10L, "m12" to 12L)
                val hold = rig.server.holdNextPage()
                val entering = async { rig.enter(channelA) }
                hold.read.await()
                // seq 11 is inside the page's range, yet not in it.
                rig.deliverWithoutServer(rig.messageNew(channelA, "m11", 11L))
                hold.release.complete(Unit)
                entering.await()

                assertEquals(setOf("m10", "m12"), rig.storedIds(channelA))
                assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(channelA))
                assertTrue(rig.logs.any { "reason=replay_missing_from_snapshot" in it }, rig.logs.toString())
            }
        }

    @Test
    fun aResetDuringTheEnterFetchWritesNothingAndNothingReachesTheNewSession() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m1" to 1L)
            val hold = rig.server.holdNextPage()
            val entering = async { rig.enter(channelA) }
            hold.read.await()
            rig.deliver(rig.messageNew(channelA, "m2", 2L))
            // Logout / account change while the page loads.
            rig.engine.clearPartition(partition)
            hold.release.complete(Unit)
            entering.await()

            assertEquals(0, rig.store.snapshotWrites)
            assertEquals(0, rig.store.incrementalWrites)
            assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, rig.canary.continuityOf(channelA))

            // New session: its own enter; the old buffer (m2) is never replayed into it.
            rig.server.has(channelA, "m1" to 1L)
            rig.server.remove(channelA, "m2")
            rig.enter(channelA)
            assertEquals(setOf("m1"), rig.storedIds(channelA))
            assertEquals(0, rig.store.incrementalWrites)
        }
    }

    // ---- T4 epoch change → discontinuous → display only, until opened ------------------------------

    // ---- gap catch-up of the viewed channel: continuous again only after rooms:joined ---------------

    // ---- T5 room leave -------------------------------------------------------------------------------

    @Test
    fun t5ALeftRoomsNextFrameIsNotWritten() {
        fun run(leaveAllowed: Boolean): Set<String> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.has(channelA, "m1" to 1L)
                rig.enter(channelA)
                val rooms = SharedRealtimeRoomLeaseRegistry(
                    shellInstanceId = "shell-1",
                    leaveAllowed = { _, _ -> leaveAllowed },
                    onLeave = rig.canary::noteRoomLeft,
                )
                val owner = RealtimeRoomLeaseOwner("shell-1", "server-1", "thread-page", generation = 1L)
                rooms.acquire(owner, listOf("channel-a"))
                val transitions = rooms.release(owner)
                assertEquals(leaveAllowed, transitions.any { it.action == RealtimeRoomTransitionAction.Leave })

                rig.deliver(rig.messageNew(channelA, "m2", 2L))
                rig.storedIds(channelA)
            }
        }
        // A non-member room is left on page close: the socket no longer receives it.
        assertEquals(setOf("m1"), run(leaveAllowed = true))
        // Twin: a member room is never left (#2994): the next frame is appended.
        assertEquals(setOf("m1", "m2"), run(leaveAllowed = false))
    }

    // ---- non-member channels: the server joins the room asynchronously on open -----------------------

    // ---- T6 message:updated -------------------------------------------------------------------------

    @Test
    fun t6AFullUpdateOfAWindowFactWithoutAThreadReplacesIt() = updateCase(
        update = { rig -> rig.messageUpdated(channelA, "m6", 6L, content = "edited") },
        expectedContent = "edited",
        expectedContinuity = CanonicalMessageV2ScopeContinuity.Continuous,
        expectedIncrementals = 1,
    )

    /** Task #14a: an update without `reactions` (allowed unknown) is written; the stored roster is kept. */
    @Test
    fun t6AnUpdateWithoutReactionsIsWrittenAndKeepsTheStoredRoster() = updateCase(
        update = { rig -> rig.messageUpdated(channelA, "m6", 6L, content = "edited", partial = true) },
        expectedContent = "edited",
        expectedContinuity = CanonicalMessageV2ScopeContinuity.Continuous,
        expectedIncrementals = 1,
    )

    /** Window m5..m9 (oldest 5); one `message:updated`; then m10 shows whether the window still appends. */
    private fun updateCase(
        update: (Rig) -> MessageUpdated,
        expectedContent: String,
        expectedContinuity: CanonicalMessageV2ScopeContinuity,
        expectedIncrementals: Int = 0,
    ) = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, *(5L..9L).map { "m$it" to it }.toTypedArray())
            rig.enter(channelA)
            val event = update(rig)
            assertTrue(rig.deliver(event))
            assertEquals(event, rig.projected.last(), "displayed in every row")

            assertEquals(expectedContent, rig.storedContents(channelA).getValue("m6"))
            assertFalse("x7" in rig.storedIds(channelA) || "m3" in rig.storedIds(channelA), "never inserted")
            assertEquals(expectedContinuity, rig.canary.continuityOf(channelA))
            assertEquals(expectedIncrementals, rig.store.incrementalWrites)
            assertEquals(5L, rig.storedCursor(channelA)?.oldestLoadedSeq, "the cursor never moves for an update")

            rig.deliver(rig.messageNew(channelA, "m10", 10L))
            assertEquals(expectedContinuity == CanonicalMessageV2ScopeContinuity.Continuous, "m10" in rig.storedIds(channelA))
        }
    }

    // ---- write failure -------------------------------------------------------------------------------

    @Test
    fun aFailedRealtimeWriteStillDisplaysAndMakesTheWindowDiscontinuous() {
        fun run(fail: Boolean): Triple<Set<String>, CanonicalMessageV2ScopeContinuity, Int> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.has(channelA, "m1" to 1L)
                rig.enter(channelA)
                rig.store.failIncrementals = fail
                val m2 = rig.messageNew(channelA, "m2", 2L)
                assertTrue(rig.deliver(m2))
                assertEquals(m2, rig.projected.last(), "a failed write never hides a message")
                rig.store.failIncrementals = false
                rig.deliver(rig.messageNew(channelA, "m3", 3L))
                Triple(rig.storedIds(channelA), rig.canary.continuityOf(channelA), rig.projected.size)
            }
        }
        val failed = run(fail = true)
        assertEquals(setOf("m1"), failed.first, "m3 is never appended across the lost m2")
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, failed.second)
        assertEquals(2, failed.third)
        assertEquals(Triple(setOf("m1", "m2", "m3"), CanonicalMessageV2ScopeContinuity.Continuous, 2), run(fail = false))
    }

    // ---- T7 retention: ranking = last user-driven write ----------------------------------------------

    @Test
    fun t7AViewedQuietChannelSurvivesABusyUnviewedOneAtTheScopeCap() {
        /** Cap 2: B (busy) opened at 10, Q (quiet) at 20, B gets realtime traffic, then C is opened at 40. */
        fun run(reopenBusyAt30: Boolean): Pair<Set<String>, Set<String>> = withFreshDatabase { database ->
            val rig = Rig(database, retention = CanonicalMessageV2RetentionPolicy(maxScopesPerPartition = 2))
            bounded {
                rig.server.has(channelB, "b1" to 1L)
                rig.server.has(channelA, "q1" to 1L)
                rig.server.has(channelC, "c1" to 1L)
                rig.clock = 10L
                rig.enter(channelB)
                rig.clock = 20L
                rig.enter(channelA)
                rig.clock = 30L
                (2L..20L).forEach { seq -> rig.deliver(rig.messageNew(channelB, "b$seq", seq)) }
                if (reopenBusyAt30) rig.enter(channelB)
                rig.clock = 40L
                rig.enter(channelC)
                rig.storedIds(channelA) to rig.storedIds(channelB)
            }
        }
        val (quiet, busy) = run(reopenBusyAt30 = false)
        assertEquals(setOf("q1"), quiet, "the recently viewed quiet channel is kept")
        assertEquals(emptySet(), busy, "the busy channel nobody opened since is evicted")
        // Twin: the same traffic, but the user opened the busy channel again.
        val (quietTwin, busyTwin) = run(reopenBusyAt30 = true)
        assertEquals(emptySet(), quietTwin)
        assertEquals(20, busyTwin.size)
    }

    // ---- T8 one write per frame ------------------------------------------------------------------------

    // ---- T9 cold start --------------------------------------------------------------------------------

    // ---- #3002 rules kept on every window -----------------------------------------------------------

    @Test
    fun aLateNewNeverOverwritesTheEditedFactAlreadyOnDisk() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "m1" to 1L)
            rig.server.edit(channelA, "m1", "edited")
            rig.enter(channelA)
            rig.deliver(rig.messageNew(channelA, "m1", 1L))
            assertEquals(mapOf("m1" to "edited"), rig.storedContents(channelA))

            // Twin: a New for an absent fact is inserted.
            rig.deliver(rig.messageNew(channelA, "m2", 2L))
            assertEquals(mapOf("m1" to "edited", "m2" to "body-m2"), rig.storedContents(channelA))
        }
    }

    // ---- task #14a: realtime replies of an entered reply thread without a join ack are display only -----
    //
    // Without a positive per-thread delivery signal (thread rooms are never joined at connect; task #14c: no
    // `channel:joined` for the thread before the enter), a reply-thread enter commits its page but leaves the
    // window `thread_delivery_unconfirmed`.

    // ---- task #14a: partial facts ("store partial, fill in when data arrives") -------------------------

    /**
     * A partial fact is completed by the next full source of the same id — the enter snapshot, the gap
     * catch-up page, or a full `message:updated` — and its marker is cleared. Twin per source: without that
     * source the fact stays partial.
     */
    @Test
    fun t11APartialFactIsCompletedByEveryFullSource() {
        fun run(complete: suspend (Rig) -> Unit): Triple<Map<String, Set<MessageAggregateField>>, String?, List<String>> =
            withFreshDatabase { database ->
                val rig = Rig(database)
                bounded {
                    rig.server.has(channelA, "a1" to 1L)
                    rig.enter(channelA)
                    rig.deliver(rig.messageNew(channelA, "a2", 2L, omit = PRODUCTION_NEW_OMITS))
                    rig.server.describe("a2", senderDescription = "\"Release bot\"", reactions = ROSTER)
                    complete(rig)
                    val restored = rig.store.restore(channelA)
                    val a2 = restored.envelopes.single { it.fact.id == "a2" }.fact
                    Triple(
                        restored.unknownFieldsByFactId,
                        a2.senderDescription + "|" + a2.reactions.joinToString(",") { "${it.emoji}x${it.count}" },
                        rig.logs.filter { it.startsWith("canonical_v2_partial_completed") },
                    )
                }
            }
        val completedLine = listOf(
            "canonical_v2_partial_completed server=server-1 scope=channel-a scopeKind=channel msg=a2 " +
                "fields=SenderDescription,Reactions",
        )
        assertEquals(Triple(emptyMap(), "Release bot|👀x2", completedLine), run { it.enter(channelA) })
        assertEquals(Triple(emptyMap(), "Release bot|👀x2", completedLine), run { it.catchUp(channelA) })
        assertEquals(
            Triple(emptyMap(), "Release bot|👀x2", completedLine),
            run {
                it.deliver(
                    it.messageUpdated(channelA, "a2", 2L, content = "body-a2", reactions = ROSTER, senderDescription = "\"Release bot\""),
                )
            },
        )
        assertEquals(Triple(mapOf("a2" to BOTH_UNKNOWN), "null|", emptyList()), run { })
    }

    /** A partial frame for a fact already stored in full never erases what is known (insert-only `New` and update). */
    @Test
    fun t11APartialFrameNeverErasesAStoredFullFact() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.server.has(channelA, "a1" to 1L, "a2" to 2L)
            rig.server.describe("a2", senderDescription = "\"Release bot\"", reactions = ROSTER)
            rig.enter(channelA)
            suspend fun a2() = rig.store.restore(channelA).envelopes.single { it.fact.id == "a2" }.fact

            // The same message's production `message:new` arriving late (a second entry / redelivery).
            assertTrue(rig.deliver(rig.messageNew(channelA, "a2", 2L, omit = PRODUCTION_NEW_OMITS)))
            assertEquals("Release bot", a2().senderDescription)
            assertEquals(listOf("👀" to 2), a2().reactions.map { it.emoji to it.count })

            // An edit whose frame omits both keys: content changes, the known aggregates stay.
            assertTrue(
                rig.deliver(rig.messageUpdated(channelA, "a2", 2L, content = "edited", omit = PRODUCTION_NEW_OMITS)),
            )
            assertEquals("edited", a2().content)
            assertEquals("Release bot", a2().senderDescription)
            assertEquals(listOf("👀" to 2), a2().reactions.map { it.emoji to it.count })
            assertEquals(emptyMap(), rig.store.restore(channelA).unknownFieldsByFactId)
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(channelA))
        }
    }

    // ---- task #14c: a public-parent thread whose room join was acked before the enter is continuous ----

    // ---- task #14c: a late ack on the open thread page requests one catch-up that commits continuous ----

    /** A stored marker outside the allowed set (or out of canonical order) poisons the scope; a valid one reads. */
    @Test
    fun t11AnInvalidStoredMarkerPoisonsTheScope() {
        fun restoreWithMarker(marker: String): Result<RestoredCanonicalMessageV2Scope> = withFreshDatabase { database ->
            val rig = Rig(database)
            bounded {
                rig.server.has(channelA, "a1" to 1L)
                rig.enter(channelA)
                database.queries.setCanonicalFactUnknownFields(
                    unknownFields = marker,
                    localRev = 1L,
                    id = "a1",
                    environmentId = channelA.environmentId,
                    serverId = channelA.serverId,
                    principalId = channelA.principalId,
                    consumerId = channelA.consumerId,
                    scopeKind = channelA.scopeKind,
                    channelId = channelA.scopeId,
                )
                runCatching { rig.store.restore(channelA) }
            }
        }
        assertEquals(mapOf("a1" to BOTH_UNKNOWN), restoreWithMarker("SenderDescription,Reactions").getOrThrow().unknownFieldsByFactId)
        assertIs<CanonicalMessageV2ScopePoisoned>(restoreWithMarker("Mentions").exceptionOrNull())
        assertIs<CanonicalMessageV2ScopePoisoned>(restoreWithMarker("Reactions,SenderDescription").exceptionOrNull())
    }

    // ---- rig ------------------------------------------------------------------------------------------

    /** Every hold-based test is bounded: a regression that blocks on a held page fails instead of hanging. */
    private fun <T> bounded(block: suspend CoroutineScope.() -> T): T = runBlocking {
        withTimeout(60_000L) { block() }
    }

    private class PageHold(val failing: Boolean) {
        /** Completed once the server state was read for the page. */
        val read = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /** The server: per-channel messages in the real REST node shape; reads them when a page is requested. */
    private class ServerFake : RaftNetworkClient {
        val paths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        private val nodes = mutableMapOf<String, LinkedHashMap<String, Pair<Long, String>>>()
        @Volatile private var nextHold: PageHold? = null

        /** The page is short (the channel's beginning is in it) when true. */
        @Volatile var reachesBeginning = false

        /** Task #14a: what the REST node of a message carries for `senderDescription` / `reactions` (JSON values). */
        private val details = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

        fun describe(id: String, senderDescription: String, reactions: String) {
            details[id] = senderDescription to reactions
        }

        fun has(scope: SyncScopeKey, vararg messages: Pair<String, Long>) = synchronized(nodes) {
            val channel = nodes.getOrPut(scope.scopeId) { linkedMapOf() }
            messages.forEach { (id, seq) -> if (id !in channel) channel[id] = seq to "body-$id" }
        }

        fun edit(scope: SyncScopeKey, id: String, content: String) = synchronized(nodes) {
            val channel = nodes.getValue(scope.scopeId)
            channel[id] = channel.getValue(id).first to content
        }

        fun remove(scope: SyncScopeKey, id: String) = synchronized(nodes) { nodes[scope.scopeId]?.remove(id) }

        fun holdNextPage(failing: Boolean = false): PageHold = PageHold(failing).also { nextHold = it }

        /** Task #14a: `channels/{thread}` answers a reply thread under this parent. */
        private val threads = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

        fun thread(scope: SyncScopeKey, parentChannelId: String, rootMessageId: String) {
            threads[scope.scopeId] = parentChannelId to rootMessageId
        }

        /** Task #14c: `channels/{id}` answers this `type` for a top-level channel (default the public `channel`). */
        val channelTypes = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun pageLoads(): Int = paths.count { it.startsWith("messages/channel/") }

        /** The channel's latest page (≤ 200 nodes) as the catch-up reads it, now. */
        fun latestPage(scope: SyncScopeKey): kotlinx.serialization.json.JsonArray = synchronized(nodes) {
            val page = nodes[scope.scopeId].orEmpty().entries.sortedByDescending { it.value.first }.take(200)
            kotlinx.serialization.json.Json.parseToJsonElement(
                "[" + page.joinToString(",") { (id, value) -> node(scope.scopeId, id, value.first, value.second) } + "]",
            ) as kotlinx.serialization.json.JsonArray
        }

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            paths += path
            if (path.startsWith("messages/channel/")) {
                val channelId = path.removePrefix("messages/channel/")
                val body = synchronized(nodes) {
                    val channel = nodes[channelId].orEmpty()
                    val page = channel.entries.sortedByDescending { it.value.first }.take(if (reachesBeginning) 500 else 200)
                    """{"messages":[${page.joinToString(",") { (id, value) -> node(channelId, id, value.first, value.second) }}],"historyLimited":false}"""
                }
                val hold = nextHold.also { nextHold = null }
                if (hold != null) {
                    hold.read.complete(Unit)
                    hold.release.await()
                    if (hold.failing) return HomeNetworkResponse(500, null)
                }
                return HomeNetworkResponse(200, body)
            }
            if (path.endsWith("/threads")) return HomeNetworkResponse(200, "{}")
            val channelId = path.removePrefix("channels/")
            threads[channelId]?.let { (parentChannelId, rootMessageId) ->
                return HomeNetworkResponse(
                    200,
                    """{"id":"$channelId","type":"thread","parentChannelId":"$parentChannelId","parentMessageId":"$rootMessageId"}""",
                )
            }
            val type = channelTypes[channelId] ?: "channel"
            return HomeNetworkResponse(200, """{"id":"$channelId","type":"$type"}""")
        }

        private fun node(channelId: String, id: String, seq: Long, content: String): String {
            val (description, reactions) = details[id] ?: ("null" to "[]")
            return """{"id":"$id","seq":$seq,"channelId":"$channelId","senderType":"user","senderId":"user-1",""" +
                """"content":"$content","messageType":"chat","randomId":null,"threadId":null,""" +
                """"createdAt":"2026-07-17T00:00:00.000Z","senderName":"Alice","senderDescription":$description,""" +
                """"senderMembershipStatus":null,"mentions":[],"attachments":[],"reactions":$reactions,"commentRef":null,"actionMetadata":null}"""
        }
    }

    private inner class Rig(
        private val database: RaftSqlDatabase,
        retention: CanonicalMessageV2RetentionPolicy = CanonicalMessageV2RetentionPolicy(),
    ) {
        @Volatile var clock = 100L
        val logs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val store = CountingStore(
            SqlDelightCanonicalMessageV2Store(database, now = { clock }, retention = retention, factLogger = { logs += it }),
        )
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val canary = CanonicalMessageV2ProductionCanary(
            generationEngine = engine,
            logger = { logs += it },
        )
        val server = ServerFake()
        val nonMembers: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
        private val loader = CanonicalMessageV2EnteredScopeLoader(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(server),
            logger = { logs += it },
            isKnownNonMember = { _, channelId -> channelId in nonMembers },
        )
        /** Hands every realtime frame straight to the canary with the connection that delivered it. */
        private val gateway = object : CanonicalMessageV2OwnerGateway {
            override suspend fun accept(
                grant: RealtimeGrantRecord,
                foldInput: CanonicalMessageFoldInput,
                transportEpoch: Long?,
            ): CanonicalMessageV2CanaryOutcome =
                canary.accept(grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID), foldInput, transportEpoch)

            override suspend fun noteTransportEpoch(grant: RealtimeGrantRecord, transportEpoch: Long) {
                canary.noteTransportEpoch(grant.durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID), transportEpoch)
            }
        }
        val projected: MutableList<RealtimeEvent> = java.util.Collections.synchronizedList(mutableListOf())
        private val displayingOwner = owner().also { owner ->
            owner.registerGuardedSinks(RealtimeGuardedDomainEventSinks(applyHomeShell = { _, event -> projected += event }))
        }

        private val mapper = RealtimeEventMapper(
            messageShadowObserver = MessageShadowObserver { eventName, raw, _ ->
                val face = if (eventName == RealtimeSocketEvents.MessageUpdated) ProductionFace.EnrichedUpdated else ProductionFace.MessageNew
                decodeCanonicalMessageWire(raw, face)
            },
        )

        private var connected = false

        /** The socket (re)connected as [epoch]; [roomsJoined]: the server's `rooms:joined` arrived on it. */
        suspend fun connect(epoch: Long, roomsJoined: Boolean = true) {
            canary.noteTransportEpoch(partition, epoch)
            if (roomsJoined) canary.noteRoomsJoined(partition, epoch)
            connected = true
        }

        /**
         * Task #14c: the server answered this socket's `join:channel` for [scope] on [epoch] (`channel:joined`
         * when [joined], else `channel:join_denied`), as the ingress coordinator forwards it.
         */
        suspend fun joinAck(scope: SyncScopeKey, epoch: Long, joined: Boolean = true) {
            canary.noteChannelJoinAck(partition, scope.scopeId, epoch, joined)
        }

        /** The user opens the channel: the real enter snapshot loader against [server]. */
        suspend fun enter(scope: SyncScopeKey) {
            if (!connected) connect(1L)
            loader.loadOnEnter(partition, scope.scopeId) { true }
        }

        /** Task #14a: the user opens reply thread [scope]: the real reply-thread enter loader against [server]. */
        suspend fun enterThread(scope: SyncScopeKey, parent: ThreadDurableReplyParent) {
            if (!connected) connect(1L)
            loader.loadReplyThreadOnEnter(partition, scope.scopeId, parent) { true }
        }

        private val writer = CanonicalMessageV2AdjacentPageWriter(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(server),
            logger = { logs += it },
            isKnownNonMember = { _, channelId -> channelId in nonMembers },
        )

        /**
         * The gap catch-up of the viewed [scope], as SharedThreadStore runs it: the baseline is registered
         * before the latest page is read; [duringFetch] runs after the read, before the commit.
         */
        suspend fun catchUp(scope: SyncScopeKey, duringFetch: suspend () -> Unit = {}) {
            val baseline = writer.beginLatestPageBaseline(partition, scope.scopeId, null)
            val page = server.latestPage(scope)
            duringFetch()
            writer.applyLatestPageAfterBaseline(partition, scope.scopeId, page, 200, null, baseline) { true }
        }

        /**
         * Task #14c: the gap catch-up of the open reply thread [scope] (SharedThreadStore's, through the real latest-page
         * writer): its baseline names [parent] and is registered before the latest page is read.
         */
        suspend fun catchUpThread(scope: SyncScopeKey, parent: ThreadDurableReplyParent) {
            val baseline = writer.beginLatestPageBaseline(partition, scope.scopeId, parent)
            val page = server.latestPage(scope)
            writer.applyLatestPageAfterBaseline(partition, scope.scopeId, page, 200, parent, baseline) { true }
        }

        /** The server persisted the message before pushing it; then one delivery through the displaying owner. */
        suspend fun deliver(event: RealtimeEvent, epoch: Long? = null): Boolean {
            recordOnServer(event)
            return deliverWithoutServer(event, epoch)
        }

        suspend fun deliverWithoutServer(event: RealtimeEvent, epoch: Long? = null): Boolean =
            displayingOwner.accept(grant, event, source = "test", transportEpoch = epoch)

        fun recordOnServer(event: RealtimeEvent) {
            val (message, scope) = when (event) {
                is MessageNew -> event.decodedCanonical!!.row to SyncScopeKey(partition, event.decodedCanonical!!.row.channelId, "channel")
                is MessageUpdated -> event.decodedCanonical!!.row to SyncScopeKey(partition, event.decodedCanonical!!.row.channelId, "channel")
                else -> return
            }
            server.has(scope, message.id to message.seq)
            if (event is MessageUpdated) server.edit(scope, message.id, message.content)
        }

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

        /**
         * Real `message:new` wire JSON (server `projectMessageSocketPayload` + rich aggregates) via the mapper.
         * [omit]: wire keys the frame leaves out — the production send pipeline omits `senderDescription` and
         * `reactions` ([PRODUCTION_NEW_OMITS]).
         */
        fun messageNew(scope: SyncScopeKey, id: String, seq: Long, omit: Set<String> = emptySet()): MessageNew {
            val event = mapper.map(
                RealtimeSocketEvents.MessageNew,
                wire(scope, id, seq, "body-$id", threadId = null, partial = false, omit = omit),
            )
            val messageNew = assertIs<MessageNew>(event)
            requireNotNull(messageNew.decodedCanonical) { "the wire frame must decode canonically" }
            return messageNew
        }

        /**
         * Task #14a: a reply's real `message:new` wire JSON: `channelId` is the thread channel, the context names
         * the thread's parent channel and root message.
         */
        fun replyNew(
            scope: SyncScopeKey,
            id: String,
            seq: Long,
            parentChannelId: String = "channel-a",
            rootMessageId: String = "root-1",
            omit: Set<String> = emptySet(),
            parentChannelType: String = "channel",
        ): MessageNew {
            val context = """{ "channelType": "thread", "parentChannelId": "$parentChannelId", """ +
                """"parentChannelType": "$parentChannelType", "parentMessageId": "$rootMessageId" }"""
            val event = mapper.map(
                RealtimeSocketEvents.MessageNew,
                wire(scope, id, seq, "body-$id", threadId = null, partial = false, conversationContext = context, omit = omit),
            )
            val messageNew = assertIs<MessageNew>(event)
            requireNotNull(messageNew.decodedCanonical) { "the wire frame must decode canonically" }
            return messageNew
        }

        /**
         * Real `message:updated` wire JSON; [partial] drops `reactions` (a content-only update), [omit] drops more
         * keys; [reactions] / [senderDescription] are the JSON values sent when the key is present.
         */
        fun messageUpdated(
            scope: SyncScopeKey,
            id: String,
            seq: Long,
            content: String,
            threadId: String? = null,
            partial: Boolean = false,
            omit: Set<String> = emptySet(),
            reactions: String = "[]",
            senderDescription: String = "null",
        ): MessageUpdated {
            val event = mapper.map(
                RealtimeSocketEvents.MessageUpdated,
                wire(scope, id, seq, content, threadId, partial, omit = omit, reactions = reactions, senderDescription = senderDescription),
            )
            val updated = assertIs<MessageUpdated>(event)
            requireNotNull(updated.decodedCanonical) { "the wire frame must decode canonically" }
            return updated
        }

        private fun wire(
            scope: SyncScopeKey,
            id: String,
            seq: Long,
            content: String,
            threadId: String?,
            partial: Boolean,
            conversationContext: String = """{ "channelType": "channel" }""",
            omit: Set<String> = emptySet(),
            reactions: String = "[]",
            senderDescription: String = "null",
        ): String {
            val thread = threadId?.let { "\"$it\"" } ?: "null"
            val omitted = if (partial) omit + "reactions" else omit
            fun key(name: String, value: String) = if (name in omitted) "" else "\"$name\": $value,"
            return """
                {
                  "id": "$id", "seq": $seq, "channelId": "${scope.scopeId}",
                  "senderType": "user", "senderId": "user-1", "randomId": null,
                  "messageType": "chat", "content": "$content", "actionMetadata": null, "threadId": $thread,
                  "taskStatus": null, "taskNumber": null, "taskAssigneeType": null, "taskAssigneeId": null,
                  "taskClaimedAt": null, "taskCompletedAt": null,
                  "createdAt": "2026-07-17T00:00:00.000Z", "updatedAt": "2026-07-17T00:00:00.000Z",
                  "senderName": "Alice", ${key("senderDescription", senderDescription)} "senderMembershipStatus": null,
                  ${key("mentions", "[]")} "attachments": [], ${key("reactions", reactions)}
                  "conversationContext": $conversationContext
                }
            """.trimIndent()
        }

        suspend fun storedContents(scope: SyncScopeKey): Map<String, String> =
            store.restore(scope).envelopes.associate { it.fact.id to it.fact.content }

        suspend fun storedIds(scope: SyncScopeKey): Set<String> = storedContents(scope).keys

        suspend fun storedCursor(scope: SyncScopeKey): PersistedCursor? = store.readCursor(scope)

        fun storedUpdatedAtMs(scope: SyncScopeKey): Long? =
            database.queries.selectSyncCursor(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                scopeId = scope.scopeId,
            ).executeAsOneOrNull()?.updatedAtMs
    }

    private class CountingStore(private val delegate: CanonicalMessageV2Store) : CanonicalMessageV2Store {
        @Volatile var snapshotWrites = 0
        @Volatile var incrementalWrites = 0

        /** The disk write of a realtime frame throws (SQLite full / IO error): nothing is committed. */
        @Volatile var failIncrementals = false

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
            snapshotWrites += 1
            return delegate.applySnapshot(frame)
        }

        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
            if (failIncrementals) throw IllegalStateException("disk full")
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
        override suspend fun readSegments(scope: SyncScopeKey) = delegate.readSegments(scope)
        override suspend fun maxLocalRev(partition: DurablePartitionKey): Long = delegate.maxLocalRev(partition)
        override suspend fun readFactsBefore(scope: SyncScopeKey, beforeSeq: Long, limit: Int) =
            delegate.readFactsBefore(scope, beforeSeq, limit)
        override suspend fun readFactsAfter(scope: SyncScopeKey, afterSeq: Long, limit: Int) =
            delegate.readFactsAfter(scope, afterSeq, limit)
        override suspend fun hasFactBefore(scope: SyncScopeKey, beforeSeq: Long) =
            delegate.hasFactBefore(scope, beforeSeq)
        override suspend fun hasFactAfter(scope: SyncScopeKey, afterSeq: Long) =
            delegate.hasFactAfter(scope, afterSeq)
        override suspend fun highestFactSeqBefore(scope: SyncScopeKey, beforeSeq: Long) =
            delegate.highestFactSeqBefore(scope, beforeSeq)
        override suspend fun lowestFactSeqAfter(scope: SyncScopeKey, afterSeq: Long) =
            delegate.lowestFactSeqAfter(scope, afterSeq)
        override suspend fun locateFact(partition: DurablePartitionKey, factId: String) =
            delegate.locateFact(partition, factId)
        override suspend fun touchUserAccess(scope: SyncScopeKey) = delegate.touchUserAccess(scope)

        /** Since message storage Phase 2a the enter / catch-up page is a coverage write: counted with the snapshots. */
        override suspend fun applyCoverage(
            scope: SyncScopeKey,
            envelopes: List<build.raft.app.network.sync.CanonicalMessageEnvelopeV2>,
            coverage: build.raft.app.network.sync.CanonicalMessageV2Coverage?,
            revAtRequest: Long,
            userDriven: Boolean,
            threadParent: build.raft.app.network.sync.CanonicalMessageV2ThreadParent?,
            insertOnly: Boolean,
            unknownFieldsByFactId: Map<String, Set<build.raft.app.canonical.message.MessageAggregateField>>,
            runPartitionRetention: Boolean,
            excludedSeqs: Set<Long>,
        ): CanonicalMessageV2StoreWriteResult {
            snapshotWrites += 1
            return delegate.applyCoverage(scope, envelopes, coverage, revAtRequest, userDriven, threadParent, insertOnly, unknownFieldsByFactId, runPartitionRetention, excludedSeqs)
        }
        override suspend fun containsFact(scope: SyncScopeKey, factId: String): Boolean = delegate.containsFact(scope, factId)
    }

    private companion object {
        /** The keys botiverse/slock's ordinary `message:new` leaves out (task #14a). */
        val PRODUCTION_NEW_OMITS = setOf("senderDescription", "reactions")
        val BOTH_UNKNOWN = setOf(MessageAggregateField.SenderDescription, MessageAggregateField.Reactions)
        const val ROSTER = """[{"emoji":"👀","count":2,"reactorIds":["user-1","user-2"],"reactorNames":["Alice","Bob"]}]"""
    }

    private fun <T> withFreshDatabase(block: (RaftSqlDatabase) -> T): T {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            return block(RaftSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }
}
