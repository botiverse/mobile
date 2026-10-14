package build.raft.app.data

import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.TopLevelScopeRefV2
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task #168 step 3: reply threads in the durable V2 cache. Opening a reply thread online commits
 * its replies as a `thread` scope under the parent the server confirms, with exactly the enter
 * snapshot's admission; paging inside the thread uses the adjacent-page rule. Every positive has a
 * twin whose inputs differ in one fact.
 *
 * Default wire: thread `thread-1` hangs off root message `root-1` in channel `channel-1`; its
 * replies are seq 20, 30, 40.
 */
class CanonicalMessageV2ReplyThreadDurableTest {
    @Test
    fun replyThreadOpenPersistsItsRepliesUnderTheConfirmedParent() = runBlocking {
        val h = harness(flagOn = true)
        h.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }

        assertEquals(listOf("channels/thread-1", "channels/channel-1", "messages/channel/thread-1"), h.network.paths)
        val frame = h.store.snapshots.single()
        assertEquals(threadScope, frame.scope)
        assertEquals(listOf("r20", "r30", "r40"), frame.envelopes.map { it.fact.id })
        frame.envelopes.forEach { envelope ->
            val context = assertIs<CanonicalConversationContextV2.Thread>(envelope.context)
            assertEquals("thread-1", context.replyScope.scopeId)
            assertEquals(TopLevelScopeRefV2.Channel("server-1", "channel-1"), context.parentTopLevelScope)
            assertEquals("root-1", context.rootMessageRef.messageId)
            assertEquals(0, envelope.fact.replyCount)
        }
        assertEquals("channel", frame.cursor.parentScopeKind)
        assertEquals("channel-1", frame.cursor.parentScopeId)
        assertEquals("root-1", frame.cursor.rootMessageId)
        assertEquals(20L, frame.cursor.oldestLoadedSeq)
        assertEquals(40L, frame.cursor.newestLoadedSeq)
        assertTrue(frame.cursor.hasLoadedOldest, "3 < page limit ⇒ the thread's first reply is stored")
        assertTrue(
            h.logs.any { "scope=thread-1 scopeKind=thread outcome=committed facts=3" in it },
            h.logs.toString(),
        )
        assertTrue(h.network.paths.none { it.endsWith("/threads") }, "a reply page never asks for reply counts")
    }

    @Test
    fun flagOffReplyThreadOpenWritesNothingAndNeverTouchesTheNetwork() = runBlocking {
        // Twin of the test above: identical wire and call, flag off.
        val h = harness(flagOn = false)
        h.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
        assertEquals(emptyList(), h.network.paths)
        assertEquals(0, h.store.snapshots.size)
    }

    @Test
    fun serverNamingAnotherRootRefusesTheThreadBeforeItsPage() = runBlocking {
        // Twin of the positive: identical page, but the server says the thread hangs off `root-2`.
        val h = harness(flagOn = true, threadParentMessageId = "root-2")
        h.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
        assertEquals(listOf("channels/thread-1"), h.network.paths)
        assertEquals(0, h.store.snapshots.size)
        assertTrue(h.logs.any { "scopeKind=thread outcome=canonical_v2_thread_parent_mismatch" in it }, h.logs.toString())
    }

    @Test
    fun aNonThreadScopeOrANestedReplyIsRefused() = runBlocking {
        val topLevel = harness(flagOn = true, threadType = "channel")
        topLevel.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
        assertEquals(0, topLevel.store.snapshots.size)
        assertTrue(topLevel.logs.any { "outcome=canonical_v2_scope_not_thread" in it }, topLevel.logs.toString())

        val nested = harness(
            flagOn = true,
            page = replyPage(replyNode(20), adjacentPageNode("r30", 30, channelId = "thread-1", threadId = "thread-9")),
        )
        nested.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
        assertEquals(0, nested.store.snapshots.size)
        assertTrue(nested.logs.any { "outcome=canonical_v2_thread_nested_reply" in it }, nested.logs.toString())
    }

    @Test
    fun identityChangeOrPartitionClearDuringTheThreadFetchWritesNothing() = runBlocking {
        val identity = harness(flagOn = true)
        var current = true
        identity.network.onPageRequested = { current = false }
        identity.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { current }
        assertEquals(0, identity.store.snapshots.size)
        assertTrue(identity.logs.any { "scopeKind=thread outcome=identity_changed" in it }, identity.logs.toString())

        val cleared = harness(flagOn = true)
        cleared.network.onPageRequested = { cleared.engine.clearPartition(partition) }
        cleared.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
        assertEquals(0, cleared.store.snapshots.size)
        assertTrue(cleared.logs.any { "scopeKind=thread outcome=partition_revoked" in it }, cleared.logs.toString())
    }

    @Test
    fun adjacentOlderReplyPageExtendsTheThreadWindow() = runBlocking {
        val h = harness(flagOn = true)
        h.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
        h.extend(anchorSeq = 30, page = replyPageArray(5, 10, 20), replyParent = parent)

        assertEquals(listOf(5L, 10L, 20L, 30L, 40L), h.store.seqs(threadScope))
        val cursor = h.store.cursors.getValue(threadScope)
        assertEquals(5L, cursor.oldestLoadedSeq)
        assertEquals(false, cursor.hasLoadedOldest, "a full page (3 = limit) says nothing about older replies")
        assertEquals(40L, cursor.newestLoadedSeq)
        assertEquals(Triple("channel", "channel-1", "root-1"), Triple(cursor.parentScopeKind, cursor.parentScopeId, cursor.rootMessageId))
        assertTrue(
            h.logs.any { "scope=thread-1 scopeKind=thread direction=older outcome=committed facts=3" in it },
            h.logs.toString(),
        )
    }

    @Test
    fun replyPageWhoseAnchorIsNotOnDiskWritesNothing() = runBlocking {
        // Twin of the test above: identical anchor (30) and page, but reply 30 was never stored
        // although it lies between the stored edges 20 and 40.
        val h = harness(flagOn = true, page = replyPage(replyNode(20), replyNode(40)))
        h.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
        h.extend(anchorSeq = 30, page = replyPageArray(5, 10, 20), replyParent = parent)

        assertEquals(0, h.store.incrementals.size)
        assertEquals(listOf(20L, 40L), h.store.seqs(threadScope))
        assertTrue(h.logs.any { "scopeKind=thread direction=older outcome=anchor_missing" in it }, h.logs.toString())
    }

    @Test
    fun threadWindowIsExtendedOnlyByItsOwnParentAndNeverAsATopLevelWindow() = runBlocking {
        val h = harness(flagOn = true)
        h.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }

        h.extend(anchorSeq = 30, page = replyPageArray(5, 10, 20), replyParent = null)
        assertTrue(h.logs.last().endsWith("direction=older outcome=no_window"), h.logs.toString())
        h.extend(anchorSeq = 30, page = replyPageArray(5, 10, 20), replyParent = ThreadDurableReplyParent("channel-1", "root-2"))
        assertTrue(h.logs.last().endsWith("scopeKind=thread direction=older outcome=parent_mismatch"), h.logs.toString())
        assertEquals(0, h.store.incrementals.size)

        // Positive control: the identical page with the thread's own parent is committed.
        h.extend(anchorSeq = 30, page = replyPageArray(5, 10, 20), replyParent = parent)
        assertEquals(1, h.store.incrementals.size)
    }

    @Test
    fun topLevelWindowIsNeverExtendedAsAReplyThread() = runBlocking {
        // A channel window with an identical page: the top-level caller commits; a reply caller
        // naming the same id finds no thread window.
        val h = harness(flagOn = true)
        h.seedChannelWindow()
        val channelPage = adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10))
        h.writer.extendWithAdjacentPage(partition, "channel-1", ThreadDurablePageDirection.Older, 20, channelPage, 3, parent) { true }
        assertTrue(h.logs.last().endsWith("scopeKind=thread direction=older outcome=no_window"), h.logs.toString())
        h.writer.extendWithAdjacentPage(partition, "channel-1", ThreadDurablePageDirection.Older, 20, channelPage, 3, null) { true }
        assertTrue(h.logs.last().endsWith("direction=older outcome=committed facts=2"), h.logs.toString())
    }

    private inner class Harness(
        val network: PathNetworkClient,
        val store: InMemoryCanonicalMessageV2Store,
        val engine: CanonicalMessageV2GenerationEngine,
        val loader: CanonicalMessageV2EnteredScopeLoader,
        val writer: CanonicalMessageV2AdjacentPageWriter,
        val logs: MutableList<String>,
    ) {
        suspend fun extend(anchorSeq: Long, page: JsonArray, replyParent: ThreadDurableReplyParent?, limit: Int = 3) =
            writer.extendWithAdjacentPage(
                partition,
                "thread-1",
                ThreadDurablePageDirection.Older,
                anchorSeq,
                page,
                limit,
                replyParent,
            ) { true }

        suspend fun seedChannelWindow() {
            val body = """{"messages":${adjacentPageOf(adjacentPageNode("m20", 20), adjacentPageNode("m30", 30))},"historyLimited":false}"""
            network.bodies["channels/channel-1"] = """{"id":"channel-1","type":"channel"}"""
            network.bodies["messages/channel/channel-1"] = body
            loader.loadOnEnter(partition, "channel-1") { true }
        }
    }

    /**
     * The writer runs on a fresh engine over the same store (the cold-start restore path), like the
     * top-level writer tests.
     */
    private fun harness(
        flagOn: Boolean,
        threadType: String = "thread",
        threadParentMessageId: String = "root-1",
        page: String = replyPage(replyNode(20), replyNode(30), replyNode(40)),
    ): Harness {
        val network = PathNetworkClient(
            mutableMapOf(
                "channels/thread-1" to
                    """{"id":"thread-1","type":"$threadType","parentChannelId":"channel-1","parentMessageId":"$threadParentMessageId"}""",
                "channels/channel-1" to """{"id":"channel-1","type":"channel"}""",
                "messages/channel/thread-1" to page,
            ),
        )
        val store = InMemoryCanonicalMessageV2Store()
        fun engine() = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val loaderEngine = engine()
        val writerEngine = engine()
        val logs = mutableListOf<String>()
        val loader = CanonicalMessageV2EnteredScopeLoader(
            canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { flagOn }, generationEngine = loaderEngine),
            generationEngine = loaderEngine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
            isEnabledForServer = { flagOn },
            logger = logs::add,
        )
        val writer = CanonicalMessageV2AdjacentPageWriter(
            canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { flagOn }, generationEngine = writerEngine),
            generationEngine = writerEngine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
            isEnabledForServer = { flagOn },
            logger = logs::add,
        )
        return Harness(network, store, loaderEngine, loader, writer, logs)
    }

    private val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
    private val parent = ThreadDurableReplyParent(parentChannelId = "channel-1", rootMessageId = "root-1")
    private val threadScope = SyncScopeKey(partition, "thread-1", "thread")
}

/** One reply of `thread-1` in the real server shape (a reply's own `threadId` is null). */
internal fun replyNode(seq: Long, threadChannelId: String = "thread-1"): String =
    adjacentPageNode("r$seq", seq, channelId = threadChannelId)

internal fun replyPage(vararg nodes: String): String =
    """{"messages":${adjacentPageOf(*nodes)},"historyLimited":false}"""

internal fun replyPageArray(vararg seqs: Long): JsonArray = adjacentPageOf(*seqs.map { replyNode(it) }.toTypedArray())

/** Answers exactly the paths in [bodies] (200), everything else 404; records every path asked. */
internal class PathNetworkClient(val bodies: MutableMap<String, String>) : SlockNetworkClient {
    val paths = mutableListOf<String>()

    /** Runs right before a `messages/channel/...` answer is produced (identity / partition change mid-fetch). */
    var onPageRequested: (suspend () -> Unit)? = null

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: build.raft.app.model.ServerId?,
        bodyText: String?,
    ): HomeNetworkResponse {
        paths += path
        if (path.startsWith("messages/channel/")) onPageRequested?.invoke()
        return bodies[path]?.let { HomeNetworkResponse(200, it) } ?: HomeNetworkResponse(404, null)
    }
}
