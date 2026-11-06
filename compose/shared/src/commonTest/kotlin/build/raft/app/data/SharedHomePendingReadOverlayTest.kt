package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A read the server has not acknowledged stays read on a network Home: the first Home after a restart
 * does not bring the badge back, and messages above the read still count. Runs the real
 * [SharedHomeDataSource] and [parseRaftHome] under [SharedHomeStore].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedHomePendingReadOverlayTest {
    private val owner = ReadReceiptOwner("env", "user-1", "server-1")
    private val channel = ChannelId("channel-1")
    private val disk = InMemoryRaftKeyValueStore()
    private val offline: DataResult<ReadReceiptResult> =
        DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))

    @Test
    fun aSavedReadKeepsTheBadgeDownOnTheFirstNetworkHome() = runTest {
        ReadReceiptPendingStore(disk).record(owner, channel, 40L)
        val store = homeStore(HomeApi(unread = 5, latestSeq = "40"))

        store.accept(SharedHomeIntent.Start)

        val snapshot = store.snapshot()
        assertEquals(0, snapshot.row("channel-1").unreadCount)
        assertEquals(0, snapshot.row("channel-1").mentionCount)
        assertEquals(0, snapshot.unreadTotal, "the total the app badge reads")
        assertEquals(0, store.state.value.serverUnreadCounts["server-1"] ?: 0, "and the server's own badge")
    }

    @Test
    fun messagesAboveTheSavedReadStillCount() = runTest {
        ReadReceiptPendingStore(disk).record(owner, channel, 38L)
        val store = homeStore(HomeApi(unread = 5, latestSeq = "40"))

        store.accept(SharedHomeIntent.Start)

        assertEquals(2, store.snapshot().row("channel-1").unreadCount)
        assertEquals(2, store.snapshot().unreadTotal)
    }

    @Test
    fun theServersLowerCountIsNotRaised() = runTest {
        ReadReceiptPendingStore(disk).record(owner, channel, 30L)
        val store = homeStore(HomeApi(unread = 3, latestSeq = "40"))

        store.accept(SharedHomeIntent.Start)

        assertEquals(3, store.snapshot().row("channel-1").unreadCount, "some of the seqs above are not countable")
    }

    @Test
    fun withNoNewestSeqTheServersCountStands() = runTest {
        ReadReceiptPendingStore(disk).record(owner, channel, 40L)
        val store = homeStore(HomeApi(unread = 5, latestSeq = null))

        store.accept(SharedHomeIntent.Start)

        assertEquals(5, store.snapshot().row("channel-1").unreadCount, "a message nobody read is never hidden")
    }

    @Test
    fun anotherAccountsSavedReadDoesNotTouchThisHome() = runTest {
        ReadReceiptPendingStore(disk).record(ReadReceiptOwner("env", "user-2", "server-1"), channel, 40L)
        val store = homeStore(HomeApi(unread = 5, latestSeq = "40"))

        store.accept(SharedHomeIntent.Start)

        assertEquals(5, store.snapshot().row("channel-1").unreadCount)
    }

    @Test
    fun theOverlayEndsWithTheAck() = runTest {
        ReadReceiptPendingStore(disk).record(owner, channel, 40L)
        val api = HomeApi(unread = 5, latestSeq = "40")
        val reads = ReadsApi(answer = DataResult.Content(ReadReceiptResult(channelId = channel, seq = 40L)))
        val coordinator = coordinator(reads)
        val store = homeStore(api, coordinator)
        store.accept(SharedHomeIntent.Start)
        assertEquals(0, store.snapshot().row("channel-1").unreadCount)

        coordinator.replayPendingReads()
        runCurrent()
        assertEquals(listOf("read:channel-1@40"), reads.calls)

        // From here the server's answer is the truth, even one that disagrees.
        store.accept(SharedHomeIntent.Refresh)
        runCurrent()
        assertEquals(5, store.snapshot().row("channel-1").unreadCount)
    }

    @Test
    fun markUnreadFromHomeCancelsTheSavedRead() = runTest {
        ReadReceiptPendingStore(disk).record(owner, channel, 40L)
        val coordinator = coordinator(ReadsApi(answer = offline))
        val calls = mutableListOf<String>()
        val store =
            homeStore(HomeApi(unread = 5, latestSeq = "40"), coordinator) { channelId ->
                calls += "unread:${channelId.value}:saved=${coordinator.pendingReadSeqs(owner)[channelId]}"
                DataResult.Content(ReadReceiptResult(channelId = channelId, unreadCount = 5))
            }
        store.accept(SharedHomeIntent.Start)
        assertEquals(0, store.snapshot().row("channel-1").unreadCount)

        store.accept(SharedHomeIntent.MarkUnread("channel-1"))

        assertEquals(listOf("unread:channel-1:saved=null"), calls, "cancelled before the POST goes out")
        assertEquals(emptyMap(), coordinator.pendingReadSeqs(owner))
    }

    @Test
    fun markingARowReadSavesTheNewestSeqTheSummaryReported() = runTest {
        val reads = ReadsApi(answer = offline)
        val coordinator = coordinator(reads)
        val store = homeStore(HomeApi(unread = 5, latestSeq = "40"), coordinator)
        store.accept(SharedHomeIntent.Start)

        store.accept(SharedHomeIntent.MarkRead("channel-1"))

        assertEquals(mapOf(channel to 40L), coordinator.pendingReadSeqs(owner))
        assertEquals(
            ReadReceiptFrontier(30L, 3L),
            ReadReceiptPendingStore(disk).entry(owner, channel)?.base,
            "the summary's cursor is saved as what this read was based on"
        )
        assertEquals(0, store.snapshot().row("channel-1").unreadCount)
    }

    @Test
    fun theOverlayLeavesTheReadCursorToTheServer() = runTest {
        val store = homeStore(HomeApi(unread = 5, latestSeq = "40"))
        store.accept(SharedHomeIntent.Start)
        val snapshot = store.snapshot()
        val withCursor =
            snapshot.copy(channels = snapshot.channels.map { it.copy(readStateMaxReadSeq = 35L) })

        val overlaid = withCursor.withPendingReadOverlay(mapOf(channel to 40L))

        assertEquals(0, overlaid.row("channel-1").unreadCount)
        assertEquals(35L, overlaid.row("channel-1").readStateMaxReadSeq, "only an ack moves it, with its version")
    }

    @Test
    fun aMentionOnlyRowWithMessagesAboveTheReadKeepsItsMark() = runTest {
        val store = homeStore(HomeApi(unread = 0, latestSeq = "40", mentioned = true))
        store.accept(SharedHomeIntent.Start)
        val snapshot = store.snapshot()
        val mentioned = snapshot.copy(channels = snapshot.channels.map { it.copy(mentionCount = 1) })

        val overlaid = mentioned.withPendingReadOverlay(mapOf(channel to 38L))

        assertEquals(1, overlaid.row("channel-1").mentionCount)
    }

    private fun TestScope.coordinator(reads: ReadsApi): ReadReceiptCoordinator =
        ReadReceiptCoordinator(
            scope = backgroundScope,
            repository = reads,
            pendingStore = ReadReceiptPendingStore(disk),
            replayRetryDelayMillis = { 60_000_000L },
        )

    private fun TestScope.homeStore(
        api: RaftHomeApi,
        coordinator: ReadReceiptCoordinator = coordinator(ReadsApi(answer = offline)),
        markChannelUnread: suspend (ChannelId) -> DataResult<ReadReceiptResult> = { channelId ->
            DataResult.Content(ReadReceiptResult(channelId = channelId))
        },
    ): SharedHomeStore {
        val session = sampleSession("user-1").copy(servers = listOf(StoredAuthServer(id = "server-1", name = "server-1")))
        return SharedHomeStore(
            authStore = InMemoryRaftAuthSessionStore(session),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(session),
                    api = api,
                    parseHome = { payload, s -> parseRaftHome(payload, s) },
                    parseDispatcher = UnconfinedTestDispatcher(testScheduler),
                ),
            scope = backgroundScope,
            readReceiptCoordinator = coordinator,
            readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("env", accountId, serverId) },
            markChannelUnread = markChannelUnread,
        )
    }

    private fun SharedHomeStore.snapshot(): HomeSnapshot =
        (state.value.content as? SharedHomeContentState.Content)?.snapshot
            ?: error("Home has no content: ${state.value.content}")

    private fun HomeSnapshot.row(id: String): HomeChannelRow = channels.single { it.channelId == ChannelId(id) }

    private class HomeApi(
        private val unread: Int,
        private val latestSeq: String?,
        private val mentioned: Boolean = false,
    ) : RaftHomeApi {
        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(200, """[{"id":"channel-1","name":"general","type":"channel","joined":true}]""")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(200, "[]")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse {
            val readState =
                if (latestSeq == null) {
                    """{"kind":"absent"}"""
                } else {
                    """{"kind":"present","readStateVersion":3,"maxReadSeq":"30",""" +
                        """"latestActivity":{"messageId":"message-1","seq":"$latestSeq"}}"""
                }
            return HomeNetworkResponse(
                200,
                """{"channels":{"channel-1":{"unreadCount":$unread,"hasMention":$mentioned,""" +
                    """"hasAnyMention":$mentioned,"readState":$readState}},"threadUnread":{}}"""
            )
        }

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(200, """[{"serverId":"server-1","unreadCount":$unread}]""")
    }

    private class ReadsApi(private val answer: DataResult<ReadReceiptResult>) : ReadReceiptRepository {
        val calls = mutableListOf<String>()

        override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> =
            throw UnsupportedOperationException("not used")

        override suspend fun markChannelReadToSeq(channelId: ChannelId, seq: Long): DataResult<ReadReceiptResult> {
            calls += "read:${channelId.value}@$seq"
            return answer
        }

        override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> {
            calls += "all:${channelId.value}"
            return answer
        }

        override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> =
            throw UnsupportedOperationException("not used")

        override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> =
            throw UnsupportedOperationException("not used")
    }
}
