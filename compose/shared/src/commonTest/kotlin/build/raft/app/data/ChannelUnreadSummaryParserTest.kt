package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/channels/unread?summary=1` against the canonical upstream shape
 * ([CANONICAL_CHANNEL_UNREAD_SUMMARY_BODY], botiverse/slock staging
 * 259f9fe0b549cfd589ac37bce75e2e1c5ffec6d1: `routes/channels.ts` `/unread`,
 * `services/channelService.ts` `ChannelUnreadSummaryEntry` / `summarizeThreadUnreadByParent`,
 * `packages/shared/src/inboxScopeReadFrontier.ts` for `readState`).
 */
class ChannelUnreadSummaryParserTest {
    private val server = ServerSummary(id = ServerId("server-1"), name = "Slock")

    @Test
    fun parsesCountsMentionsAndFollowedThreadsFromTheCanonicalSummary() {
        val summary = requireNotNull(parseChannelUnreadSummary(CANONICAL_CHANNEL_UNREAD_SUMMARY_BODY))

        assertEquals(
            mapOf(
                ChannelId("channel-general") to 3,
                ChannelId("dm-cindy") to 1,
                ChannelId("channel-news") to 0,
                ChannelId("thread-a") to 2,
                ChannelId("thread-b") to 1,
                ChannelId("thread-read") to 0,
            ),
            summary.unreadCounts
        )
        // `hasMention` only (an unread mention); `hasAnyMention` alone never lights the @.
        assertEquals(setOf(ChannelId("channel-general"), ChannelId("thread-a")), summary.mentionedChannelIds)
        assertEquals(
            mapOf(
                "thread-a" to HomeFollowedThreadUnread(ChannelId("channel-general"), 2, hasMention = true),
                "thread-b" to HomeFollowedThreadUnread(ChannelId("channel-general"), 1, hasMention = false),
            ),
            summary.followedThreadUnread
        )
        assertEquals(
            mapOf(ChannelId("channel-general") to HomeParentThreadUnread(count = 2, hasMention = true)),
            summary.threadUnreadByParent
        )
        // The client fold of the thread rows equals the server's own `threadUnread`.
        assertEquals(summary.threadUnreadByParent, summary.followedThreadUnread.foldThreadUnreadByParent())
    }

    @Test
    fun rejectsTheBareCountMapAndGuessedShapes() {
        for (body in listOf("""{"channel-1":7}""", "{}", """{"channels":{"channel-1":7},"threadUnread":{}}""", "[]")) {
            assertNull(parseChannelUnreadSummary(body), body)
            assertNull(parseChannelUnreadSnapshot(body, server), body)
        }
    }

    @Test
    fun readReceiptSnapshotSumsTheSummaryCounts() {
        val snapshot = requireNotNull(parseChannelUnreadSnapshot(channelUnreadSummaryBody(mapOf("c1" to 7, "dm-1" to 3)), server))

        assertEquals(10, snapshot.totalUnreadCount)
        assertEquals(10, snapshot.server.unreadCount)
        assertEquals(7, snapshot.unreadCounts[ChannelId("c1")])
    }

    @Test
    fun staleOverloadBodyStillParses() {
        val stale = CANONICAL_CHANNEL_UNREAD_SUMMARY_BODY.trimEnd().removeSuffix("}") + """, "stale": true }"""

        assertEquals(parseChannelUnreadSummary(CANONICAL_CHANNEL_UNREAD_SUMMARY_BODY), parseChannelUnreadSummary(stale))
    }

    @Test
    fun homeRowsCarryTheMentionAndTheSnapshotCarriesTheFollowedThreads() {
        val summary = requireNotNull(parseChannelUnreadSummary(CANONICAL_CHANNEL_UNREAD_SUMMARY_BODY))
        val result =
            parseRaftHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels =
                            listOf(
                                HomeChannelWireDto(id = "channel-general", name = "general", type = "channel", joined = true),
                                HomeChannelWireDto(id = "channel-quiet", name = "quiet", type = "channel", joined = true),
                            ),
                        directMessages = listOf(HomeDirectMessageWireDto(
                                    id = "dm-cindy",
                                    name = "cindy",
                                    type = "dm",
                                    peerType = "human",
                                    peerId = "user-2",
                                    peerName = "Cindy",
                                )),
                        unreadCounts = summary.unreadCounts,
                        mentionedChannelIds = summary.mentionedChannelIds,
                        followedThreadUnread = summary.followedThreadUnread,
                        serverUnreadSummary = emptyList(),
                    ),
                session =
                    StoredAuthSession(
                        user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
                        accessToken = "token-a",
                        refreshToken = "token-r",
                        selectedServerId = "server-1",
                        servers = listOf(StoredAuthServer(id = "server-1", name = "Slock")),
                    ),
            )

        assertTrue(result is DataResult.Content)
        val snapshot = result.data
        val general = snapshot.channels.single { it.channelId == ChannelId("channel-general") }
        assertEquals(3, general.unreadCount)
        assertEquals(1, general.mentionCount)
        assertTrue(general.mentionCountKnown)
        // A scope the summary did not report keeps the mention unknown, like its count.
        val quiet = snapshot.channels.single { it.channelId == ChannelId("channel-quiet") }
        assertEquals(0, quiet.mentionCount)
        assertEquals(false, quiet.mentionCountKnown)
        assertEquals(0, snapshot.directMessages.single().mentionCount)
        assertTrue(snapshot.directMessages.single().mentionCountKnown)
        assertEquals(summary.followedThreadUnread, snapshot.followedThreadUnread)
        assertEquals(HomeParentThreadUnread(2, true), snapshot.parentThreadUnread(ChannelId("channel-general")))
    }
}
