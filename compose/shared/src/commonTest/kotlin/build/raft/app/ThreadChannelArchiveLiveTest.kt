package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * artin 11-18 (Android 1.26): archiving the channel he was looking at posted "artin archived this
 * channel" live, but the composer stayed. The archive's `channel:updated` row (slock
 * channelArchiveEffects.ts finishChannelArchive -> publishChannelUpdate) carries `archivedAt`;
 * the open page, and a reply thread in that channel, now follow it both ways.
 */
class ThreadChannelArchiveLiveTest {
    @Test
    fun theArchiveFrameNamesTheChannelAndSaysItIsArchived() {
        val archived = assertIs<RealtimeRefreshRequired>(RealtimeEventMapper().map(RealtimeSocketEvents.ChannelUpdated, channelUpdatedFrame("channel-1", archived = true)))
        assertEquals(RealtimeRefreshScope.Home, archived.scope)
        assertEquals(ChannelId("channel-1"), archived.channelId)
        assertEquals(true, archived.channelArchived)

        val unarchived = assertIs<RealtimeRefreshRequired>(RealtimeEventMapper().map(RealtimeSocketEvents.ChannelUpdated, channelUpdatedFrame("channel-1", archived = false)))
        assertEquals(ChannelId("channel-1"), unarchived.channelId)
        assertEquals(false, unarchived.channelArchived)

        // routes/channels.ts emitExternalRelationChanged: id-only, says nothing of the archive.
        val idOnly = assertIs<RealtimeRefreshRequired>(RealtimeEventMapper().map(RealtimeSocketEvents.ChannelUpdated, """{"channelId":"channel-1"}"""))
        assertEquals(ChannelId("channel-1"), idOnly.channelId)
        assertNull(idOnly.channelArchived)
    }

    @Test
    fun anArchiveArrivingLiveSwapsTheComposerForTheBannerAndAnUnarchiveBringsItBack() = runTest {
        val store = loadedStore(basePayload(), channelId = "channel-1")
        assertFalse(store.state.value.payload.channelArchived)
        assertFalse(threadFooterShowsArchived(store.state.value.payload))

        store.accept(SharedThreadIntent.ApplyRealtimeEvent(channelUpdated("channel-1", archived = true)))
        val archived = store.state.value.payload
        assertTrue(archived.channelArchived)
        assertTrue(threadFooterShowsArchived(archived))
        assertTrue(threadHeaderShowsArchivedBadge(archived, directoryArchived = false))
        assertFalse(archived.channelTaskWritesShown())

        store.accept(SharedThreadIntent.ApplyRealtimeEvent(channelUpdated("channel-1", archived = false)))
        val unarchived = store.state.value.payload
        assertFalse(unarchived.channelArchived)
        assertFalse(threadFooterShowsArchived(unarchived))
    }

    @Test
    fun aReplyThreadFollowsItsParentChannelsArchive() = runTest {
        val store = loadedStore(replyRoute(), channelId = "thread-parent-1")
        assertEquals(RaftThreadSurfaceKind.ReplyThread, store.state.value.payload.surfaceKind)
        assertFalse(store.state.value.payload.channelArchived)

        // The thread's own channel id is not the archived channel: nothing changes.
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(channelUpdated("thread-parent-1", archived = true)))
        assertFalse(store.state.value.payload.channelArchived)

        store.accept(SharedThreadIntent.ApplyRealtimeEvent(channelUpdated("channel-1", archived = true)))
        assertTrue(store.state.value.payload.channelArchived)
        assertTrue(threadFooterShowsArchived(store.state.value.payload))

        store.accept(SharedThreadIntent.ApplyRealtimeEvent(channelUpdated("channel-1", archived = false)))
        assertFalse(store.state.value.payload.channelArchived)
    }

    @Test
    fun anotherChannelsArchiveLeavesThePageAlone() = runTest {
        val store = loadedStore(basePayload(), channelId = "channel-1")
        val before = store.state.value.payload

        store.accept(SharedThreadIntent.ApplyRealtimeEvent(channelUpdated("channel-2", archived = true)))

        assertFalse(store.state.value.payload.channelArchived)
        assertEquals(before.messages, store.state.value.payload.messages)
    }

    @Test
    fun aSendRefusedAsArchivedTurnsThePageArchived() = runTest {
        val (store, dataSource) = loadedStoreAndDataSource(basePayload(), channelId = "channel-1")
        failLocalSend(store, dataSource, id = "${ThreadTransientLocalSendIdPrefix}net-1", errorCode = "thread_send_network")
        assertFalse(store.state.value.payload.channelArchived, "an ordinary send failure keeps the composer")

        val localId = "${ThreadTransientLocalSendIdPrefix}archived-1"
        failLocalSend(store, dataSource, id = localId, errorCode = "channel_archived")

        val payload = store.state.value.payload
        assertTrue(payload.channelArchived)
        assertTrue(threadFooterShowsArchived(payload))
        // The refused message stays, failed, so its text is not lost.
        assertEquals(ThreadMessageDeliveryFailed, payload.messages.first { it.id == localId }.deliveryState)
    }

    @Test
    fun aDirectMessageIgnoresTheArchiveSignals() {
        val dm = basePayload().copy(surfaceKind = RaftThreadSurfaceKind.DirectMessage, status = "content")
        assertFalse(dm.withChannelUpdateArchiveState(channelUpdated("channel-1", archived = true)).channelArchived)
        assertFalse(dm.withSendRefusedAsArchived("channel_archived").channelArchived)
    }

    private fun channelUpdated(channelId: String, archived: Boolean): RealtimeRefreshRequired =
        assertIs<RealtimeRefreshRequired>(RealtimeEventMapper().map(RealtimeSocketEvents.ChannelUpdated, channelUpdatedFrame(channelId, archived)))

    private suspend fun failLocalSend(
        store: SharedThreadStore,
        dataSource: SharedThreadDataSource,
        id: String,
        errorCode: String,
    ) {
        val provenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        val route = store.state.value.payload.resolveThreadRouteIdentity()
        assertTrue(
            store.admitLocalSend(
                message = localSend(id),
                expectedProvenance = provenance,
                expectedRoute = route,
            ),
        )
        store.accept(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = id,
                errorCode = errorCode,
                errorMessage = "This channel is archived",
                expectedProvenance = provenance,
                expectedRoute = route,
            ),
        )
    }

    private fun localSend(id: String): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "u1",
            authorName = "Artin",
            isOwn = true,
            body = "still there?",
            threadChannelId = "channel-1",
            deliveryState = ThreadMessageDeliverySending,
            pendingSendContext =
                RaftThreadPendingSendContext(
                    channelId = "channel-1",
                    body = "still there?",
                    asTask = false,
                    attachmentIds = emptyList(),
                    attachments = emptyList(),
                    mentionChannelId = "channel-1",
                    streamChannelId = "channel-1",
                    memberScopedMentions = false,
                    randomId = "random-$id",
                ),
        )

    private suspend fun TestScope.loadedStore(route: RaftThreadScreenPayload, channelId: String): SharedThreadStore =
        loadedStoreAndDataSource(route, channelId).first

    private suspend fun TestScope.loadedStoreAndDataSource(
        route: RaftThreadScreenPayload,
        channelId: String,
    ): Pair<SharedThreadStore, SharedThreadDataSource> {
        val scope: CoroutineScope = backgroundScope
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(bodyText = messagesResponse("hello", channelId = channelId, historyLimited = false)),
                storeScope = scope,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(route))
        return store to dataSource
    }

    private fun replyRoute(): RaftThreadScreenPayload =
        basePayload().copy(
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = "thread-parent-1",
            streamChannelId = "thread-parent-1",
            parentChannelId = "channel-1",
            parentMessageId = "parent-1",
            originParentMessageId = "parent-1",
            originStatus = "loading",
        )

    private companion object {
        /** slock publishChannelUpdate's `{ channel }`: the channel row as archive / unarchive commit it. */
        fun channelUpdatedFrame(channelId: String, archived: Boolean): String =
            """
            {
              "channel": {
                "id": "$channelId",
                "serverId": "server-1",
                "name": "wg-testbed-app",
                "type": "channel",
                "createdAt": "2026-03-02T08:00:00.000Z",
                "archivedAt": ${if (archived) "\"2026-11-18T09:12:00.000Z\"" else "null"},
                "visitorVisible": false
              }
            }
            """.trimIndent()
    }
}
