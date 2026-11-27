package build.raft.app

import build.raft.app.data.CHANNEL_ANNOUNCEMENT_STALE_MILLIS
import build.raft.app.data.ChannelAnnouncementIntent
import build.raft.app.data.ChannelAnnouncementKey
import build.raft.app.data.ChannelAnnouncementRepository
import build.raft.app.data.ChannelAnnouncementSlice
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeDirectMessagesWireResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.RaftChannelAnnouncementApi
import build.raft.app.data.SharedChannelAnnouncementStore
import build.raft.app.data.parseDirectMessagesForHomeRefresh
import build.raft.app.data.parseRaftChannelDetail
import build.raft.app.data.toConversationSummary
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.data.withChannelAnnouncementHint
import build.raft.app.data.withDirectMessagesRefreshedForLoungeListing
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelAnnouncementHeightReservationTest {
    private val serverId = ServerId("srv-1")
    private val key = ChannelAnnouncementKey("srv-1", "ch-1")

    private fun sampleSnapshot(
        channelHasAnnouncement: Boolean = false,
        loungeAgentId: String? = null,
        dmHasAnnouncement: Boolean = false,
    ) = HomeSnapshot(
        server = ServerSummary(id = serverId, name = "Raft"),
        currentMember = MemberProfile(id = MemberId("u-1"), kind = MemberKind.Human, handle = "me", displayName = "Me"),
        channels =
            listOf(
                HomeChannelRow(
                    threadId = ThreadId("ch-1"),
                    channelId = ChannelId("ch-1"),
                    channelType = "channel",
                    kind = ConversationKind.Channel,
                    name = "general",
                    hasAnnouncement = channelHasAnnouncement,
                    loungeAgentId = loungeAgentId,
                )
            ),
        directMessages =
            listOf(
                HomeDirectMessageRow(
                    threadId = ThreadId("dm-1"),
                    channelId = ChannelId("dm-1"),
                    channelType = "dm",
                    kind = ConversationKind.DirectMessage,
                    name = "Alex",
                    hasAnnouncement = dmHasAnnouncement,
                )
            ),
        unreadTotal = 0,
    )

    private val announcementResponseBody =
        """{"announcement":{"artifactId":"pt_ann_1","title":"Release Notes","version":1,"excerpt":"v1.32.0 details","note":null,"updatedAt":"2026-11-27T18:00:00Z","updatedBy":{"type":"user","id":"u-1","name":"Lagoon"},"seen":true}}"""

    @Test
    fun wireParsingAndRoundTripsPreserveHasAnnouncementAndExcludeLounges() {
        val channelDetailWithAnnouncement =
            assertNotNull(
                parseRaftChannelDetail(
                    """{"id":"ch-1","name":"general","type":"channel","announcement":{"artifactId":"pt_1","title":"Pinned"}}"""
                )
            )
        assertTrue(channelDetailWithAnnouncement.hasAnnouncement)
        assertTrue(channelDetailWithAnnouncement.toConversationSummary().toHomeChannelRow().hasAnnouncement)

        val channelDetailNullAnnouncement =
            assertNotNull(
                parseRaftChannelDetail(
                    """{"id":"ch-1","name":"general","type":"channel","hasAnnouncement":true,"announcement":null}"""
                )
            )
        assertFalse(channelDetailNullAnnouncement.hasAnnouncement, "explicit null announcement overrides stale hasAnnouncement")

        val channelDetailNullArtifactId =
            assertNotNull(
                parseRaftChannelDetail(
                    """{"id":"ch-1","name":"general","type":"channel","hasAnnouncement":true,"announcementArtifactId":null}"""
                )
            )
        assertFalse(channelDetailNullArtifactId.hasAnnouncement, "explicit null announcementArtifactId overrides stale hasAnnouncement")

        val loungeChannel =
            assertNotNull(
                parseRaftChannelDetail(
                    """{"id":"ch-lounge","name":"lounge","type":"private","hasAnnouncement":true,"loungeAgentId":"agent-1"}"""
                )
            )
        assertFalse(loungeChannel.hasAnnouncement, "Agent Lounge never carries an announcement hint")

        val dmDetail =
            assertNotNull(
                parseRaftChannelDetail(
                    """{"id":"dm-1","name":"dm","type":"dm","peerType":"human","peerId":"u-2","peerName":"alex","hasAnnouncement":true}"""
                )
            )
        assertTrue(dmDetail.hasAnnouncement)
        assertTrue(dmDetail.toConversationSummary().toHomeDirectMessageRow().hasAnnouncement)

        val refreshed =
            parseDirectMessagesForHomeRefresh(
                Json { ignoreUnknownKeys = true }.decodeFromString(
                    HomeDirectMessagesWireResult.serializer(),
                    """[{"id":"dm-1","name":"dm","type":"dm","peerType":"human","peerId":"u-2","peerName":"alex","hasAnnouncement":true}]"""
                )
            )
        val merged = sampleSnapshot(dmHasAnnouncement = false).withDirectMessagesRefreshedForLoungeListing(refreshed, "srv-1", 1L)
        assertTrue(merged.directMessages.single().hasAnnouncement)
    }

    @Test
    fun homeSnapshotRealtimeHintPatchAndReservationRulesMatchWeb() {
        val unpinned = sampleSnapshot(channelHasAnnouncement = false, dmHasAnnouncement = false)
        assertEquals(false, homeSnapshotHasAnnouncementHintOrNull(unpinned, "srv-1", "ch-1", "channel"))
        assertEquals(false, homeSnapshotHasAnnouncementHintOrNull(unpinned, "srv-1", "dm-1", "dm"))
        assertNull(homeSnapshotHasAnnouncementHintOrNull(unpinned, "srv-1", "ch-missing", "channel"))

        val pinned = unpinned.withChannelAnnouncementHint(ChannelId("ch-1"), true)
        assertTrue(homeSnapshotHasAnnouncementHint(pinned, "srv-1", "ch-1", "channel"))
        assertFalse(homeSnapshotHasAnnouncementHint(pinned, "srv-2", "ch-1", "channel"))
        assertFalse(homeSnapshotHasAnnouncementHint(pinned, "srv-1", "ch-1", "thread"))

        val lounge = sampleSnapshot(channelHasAnnouncement = true, loungeAgentId = "agent-1")
        assertFalse(homeSnapshotHasAnnouncementHint(lounge, "srv-1", "ch-1", "private"))

        assertTrue(shouldReserveChannelAnnouncementStrip(key, ChannelAnnouncementSlice.Unread, hasAnnouncementHint = true))
        assertFalse(shouldReserveChannelAnnouncementStrip(null, ChannelAnnouncementSlice.Unread, hasAnnouncementHint = true))
        assertFalse(shouldReserveChannelAnnouncementStrip(key, ChannelAnnouncementSlice.Unread, hasAnnouncementHint = false))
        assertFalse(
            shouldReserveChannelAnnouncementStrip(
                key,
                ChannelAnnouncementSlice(read = true, announcement = null),
                hasAnnouncementHint = true,
            )
        )
        assertFalse(
            shouldReserveChannelAnnouncementStrip(
                key,
                ChannelAnnouncementSlice(read = false, announcement = null, failed = true),
                hasAnnouncementHint = true,
            )
        )
    }

    @Test
    fun storeMarksUnreadFailureAndKeepsRetriesSilentUntilSuccessOrReset() = runTest {
        var readCalls = 0
        var response = HomeNetworkResponse(code = 500, bodyText = """{"error":"server_error"}""")
        val api =
            object : RaftChannelAnnouncementApi {
                override suspend fun read(
                    serverId: ServerId,
                    channelId: ChannelId,
                    priority: DataRequestPriority,
                ): HomeNetworkResponse {
                    readCalls += 1
                    return response
                }

                override suspend fun update(
                    serverId: ServerId,
                    channelId: ChannelId,
                    artifactId: String?,
                ): HomeNetworkResponse = error("unused")
            }
        var now = 1_000L
        val store = SharedChannelAnnouncementStore(ChannelAnnouncementRepository(api), scope = this, nowMillis = { now })

        store.dispatch(ChannelAnnouncementIntent.Open(key))
        advanceUntilIdle()
        assertEquals(1, readCalls)
        val afterFail = store.current(key)
        assertFalse(afterFail.read)
        assertTrue(afterFail.failed, "initial unread failure marks slice failed so placeholder collapses")
        assertFalse(shouldReserveChannelAnnouncementStrip(key, afterFail, hasAnnouncementHint = true))

        // Retry still fails: stays failed without bouncing back to Unread.
        now += CHANNEL_ANNOUNCEMENT_STALE_MILLIS
        store.dispatch(ChannelAnnouncementIntent.Visible(key))
        advanceUntilIdle()
        assertEquals(2, readCalls)
        assertTrue(store.current(key).failed)

        // Retry succeeds: clears failed and exposes loaded announcement.
        response = HomeNetworkResponse(code = 200, bodyText = announcementResponseBody)
        now += CHANNEL_ANNOUNCEMENT_STALE_MILLIS
        store.dispatch(ChannelAnnouncementIntent.Visible(key))
        advanceUntilIdle()
        assertEquals(3, readCalls)
        val loaded = store.current(key)
        assertTrue(loaded.read)
        assertFalse(loaded.failed)
        assertEquals("pt_ann_1", loaded.announcement?.artifactId)

        // Subsequent failure after data is already held must NOT mark failed or drop the announcement.
        response = HomeNetworkResponse(code = 500, bodyText = """{"error":"server_error"}""")
        now += CHANNEL_ANNOUNCEMENT_STALE_MILLIS
        store.dispatch(ChannelAnnouncementIntent.Visible(key))
        advanceUntilIdle()
        assertEquals(4, readCalls)
        assertTrue(store.current(key).read)
        assertFalse(store.current(key).failed)
        assertEquals("pt_ann_1", store.current(key).announcement?.artifactId)

        // Reset clears failedByChannel too.
        store.dispatch(ChannelAnnouncementIntent.Reset)
        assertEquals(ChannelAnnouncementSlice.Unread, store.current(key))
    }
}
