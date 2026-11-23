package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftShareSuggestionTest {
    @AfterTest
    fun tearDown() {
        RaftShareSuggestionsHostProvider.clearForTests()
    }

    @Test
    fun targetHintCodecRoundTripsAllValidKindsAndRejectsMalformedInput() {
        val channelHint =
            RaftShareSuggestionContract.encodeTargetHint("srv-1", RaftShareSuggestionKind.Channel, "ch_1.a~b")
        assertEquals("raft_share:v1:srv-1:channel:ch_1.a~b", channelHint)
        assertEquals(
            RaftShareSuggestionTargetHint("srv-1", RaftShareSuggestionKind.Channel, "ch_1.a~b"),
            RaftShareSuggestionContract.decodeTargetHint(channelHint)
        )

        val jointHint =
            RaftShareSuggestionContract.encodeTargetHint("srv-2", RaftShareSuggestionKind.Joint, "joint-9")
        assertEquals("raft_share:v1:srv-2:joint:joint-9", jointHint)
        assertEquals(
            RaftShareSuggestionTargetHint("srv-2", RaftShareSuggestionKind.Joint, "joint-9"),
            RaftShareSuggestionContract.decodeTargetHint(jointHint)
        )

        val dmHint =
            RaftShareSuggestionContract.encodeTargetHint("srv-3", RaftShareSuggestionKind.DirectMessage, "dm-42")
        assertEquals("raft_share:v1:srv-3:dm:dm-42", dmHint)
        assertEquals(
            RaftShareSuggestionTargetHint("srv-3", RaftShareSuggestionKind.DirectMessage, "dm-42"),
            RaftShareSuggestionContract.decodeTargetHint(dmHint)
        )

        // Reject invalid segments, wrong prefixes, extra colons, unknown kinds, and oversized strings.
        assertNull(RaftShareSuggestionContract.encodeTargetHint("srv:bad", RaftShareSuggestionKind.Channel, "ch-1"))
        assertNull(RaftShareSuggestionContract.encodeTargetHint("srv-1", RaftShareSuggestionKind.Channel, "ch/1"))
        assertNull(RaftShareSuggestionContract.encodeTargetHint("", RaftShareSuggestionKind.Channel, "ch-1"))
        assertNull(RaftShareSuggestionContract.decodeTargetHint(null))
        assertNull(RaftShareSuggestionContract.decodeTargetHint(""))
        assertNull(RaftShareSuggestionContract.decodeTargetHint("shortcut_srv-1_ch-1"))
        assertNull(RaftShareSuggestionContract.decodeTargetHint("raft_share:v2:srv-1:channel:ch-1"))
        assertNull(RaftShareSuggestionContract.decodeTargetHint("raft_share:v1:srv-1:thread:ch-1"))
        assertNull(RaftShareSuggestionContract.decodeTargetHint("raft_share:v1:srv-1:channel:ch-1:extra"))
        assertNull(RaftShareSuggestionContract.decodeTargetHint("raft_share:v1:srv-1:channel:../escape"))
        assertNull(
            RaftShareSuggestionContract.decodeTargetHint(
                "raft_share:v1:${"a".repeat(130)}:channel:ch-1"
            )
        )
    }

    @Test
    fun extractRecentShareSuggestionsOrdersByActivityAndFiltersIneligibleRows() {
        val nowMillis = 1_763_900_000_000L // 2025-11-23T12:26:40Z
        val snapshot =
            HomeSnapshot(
                server = ServerSummary(id = ServerId("srv-1"), name = "Acme Corp"),
                currentMember =
                    MemberProfile(
                        id = MemberId("me"),
                        displayName = "Me",
                        handle = "me",
                        kind = MemberKind.Human
                    ),
                channels =
                    listOf(
                        HomeChannelRow(
                            threadId = ThreadId("ch-general"),
                            channelId = ChannelId("ch-general"),
                            name = "general",
                            lastMessageAt = "2025-11-23T12:20:00Z"
                        ),
                        HomeChannelRow(
                            threadId = ThreadId("ch-joint"),
                            channelId = ChannelId("ch-joint"),
                            kind = ConversationKind.Joint,
                            channelType = "joint",
                            name = "#partner-sync",
                            lastMessageAt = "2025-11-23T12:25:00Z",
                            externalFreezeAt = "2025-11-24T00:00:00Z" // future freeze -> still eligible
                        ),
                        HomeChannelRow(
                            threadId = ThreadId("ch-frozen"),
                            channelId = ChannelId("ch-frozen"),
                            kind = ConversationKind.Joint,
                            channelType = "joint",
                            name = "expired-joint",
                            lastMessageAt = "2025-11-23T12:26:00Z",
                            externalFreezeAt = "2025-11-23T12:00:00Z" // already frozen -> excluded
                        ),
                        HomeChannelRow(
                            threadId = ThreadId("ch-unjoined"),
                            channelId = ChannelId("ch-unjoined"),
                            name = "unjoined",
                            joined = false,
                            lastMessageAt = "2025-11-23T12:26:00Z"
                        ),
                        HomeChannelRow(
                            threadId = ThreadId("ch-archived"),
                            channelId = ChannelId("ch-archived"),
                            name = "archived",
                            archived = true,
                            lastMessageAt = "2025-11-23T12:26:00Z"
                        ),
                        HomeChannelRow(
                            threadId = ThreadId("ch-readonly"),
                            channelId = ChannelId("ch-readonly"),
                            name = "announcements",
                            permissions = ConversationPermissions(canRead = true, canSend = false),
                            lastMessageAt = "2025-11-23T12:26:00Z"
                        )
                    ),
                directMessages =
                    listOf(
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-agent"),
                            channelId = ChannelId("dm-agent"),
                            name = "AirenaAgent",
                            directMessagePeerType = "agent",
                            directMessagePeerAvatarWireValue = "pixel:cat",
                            lastMessageAt = "2025-11-23T12:26:30Z"
                        ),
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-unnamed"),
                            channelId = ChannelId("dm-unnamed"),
                            name = "   ",
                            directMessagePeerType = "human",
                            lastMessageAt = "2025-11-23T12:10:00Z"
                        ),
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-archived"),
                            channelId = ChannelId("dm-archived"),
                            name = "Old Bot",
                            archived = true,
                            lastMessageAt = "2025-11-23T12:26:35Z"
                        ),
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-readonly"),
                            channelId = ChannelId("dm-readonly"),
                            name = "Muted Peer",
                            permissions = ConversationPermissions(canRead = true, canSend = false),
                            lastMessageAt = "2025-11-23T12:26:36Z"
                        ),
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-frozen"),
                            channelId = ChannelId("dm-frozen"),
                            name = "Frozen Peer",
                            externalFreezeAt = "2025-11-23T12:00:00Z",
                            lastMessageAt = "2025-11-23T12:26:37Z"
                        )
                    ),
                unreadTotal = 0
            )

        val suggestions =
            extractRecentShareSuggestions(
                snapshot = snapshot,
                nowEpochMillis = nowMillis,
                maxCount = 8
            )

        assertEquals(
            listOf(
                "raft_share:v1:srv-1:dm:dm-agent",
                "raft_share:v1:srv-1:joint:ch-joint",
                "raft_share:v1:srv-1:channel:ch-general"
            ),
            suggestions.map { it.shortcutId }
        )
        assertEquals("AirenaAgent", suggestions[0].title)
        assertEquals("pixel:cat", suggestions[0].avatarWireValue)
        assertEquals("agent", suggestions[0].peerType)
        assertEquals("Acme Corp", suggestions[0].subtitle)
        assertEquals("#partner-sync", suggestions[1].title)
        assertEquals("#", suggestions[1].avatarLabel)
        assertEquals("#general", suggestions[2].title)

        // Verify recentForwardMessageTargets (backing RealForwardMessageTargetRepository.recentTargets)
        // enforces the same eligibility rules for BOTH DMs and channels (excluding read-only,
        // archived, unjoined, and expired external-freeze rows).
        val recentTargets =
            recentForwardMessageTargets(
                serverId = ServerId("srv-1"),
                directMessages = snapshot.directMessages,
                channels = snapshot.channels,
                limit = 20,
                nowEpochMillis = nowMillis
            )
        assertEquals(
            listOf("dm-agent", "ch-general", "ch-joint"),
            recentTargets.map { it.id }
        )
    }

    @Test
    fun extractRecentShareSuggestionsCapsAtMaxCountAndBreaksTiesDeterministically() {
        val channels =
            (1..12).map { index ->
                HomeChannelRow(
                    threadId = ThreadId("ch-$index"),
                    channelId = ChannelId("ch-$index"),
                    name = "room-$index",
                    lastMessageAt = "2025-11-23T10:00:00Z"
                )
            }
        val dm =
            HomeDirectMessageRow(
                threadId = ThreadId("dm-1"),
                channelId = ChannelId("dm-1"),
                name = "Zoe",
                lastMessageAt = "2025-11-23T10:00:00Z"
            )
        val snapshot =
            HomeSnapshot(
                server = ServerSummary(id = ServerId("srv-1"), name = "Acme"),
                currentMember = MemberProfile(MemberId("me"), "Me", "me", MemberKind.Human),
                channels = channels,
                directMessages = listOf(dm),
                unreadTotal = 0
            )

        val suggestions =
            extractRecentShareSuggestions(
                snapshot = snapshot,
                nowEpochMillis = 1_763_900_000_000L,
                maxCount = 8
            )

        assertEquals(8, suggestions.size)
        // Same timestamp: DM wins tie-breaker before alphabetical channels.
        assertEquals("raft_share:v1:srv-1:dm:dm-1", suggestions.first().shortcutId)
        assertTrue(suggestions.drop(1).all { it.kind == RaftShareSuggestionKind.Channel })
    }

    @Test
    fun hostProviderForwardsValidPublishAndClearAndIgnoresBlankIdentity() {
        val published = mutableListOf<Triple<String, String, Int>>()
        val cleared = mutableListOf<String>()
        RaftShareSuggestionsHostProvider.installAppScopeHost(
            object : RaftShareSuggestionsHost {
                override fun publish(
                    accountId: String,
                    serverId: String,
                    suggestions: List<RaftShareSuggestion>
                ) {
                    published += Triple(accountId, serverId, suggestions.size)
                }

                override fun clear(reason: String) {
                    cleared += reason
                }
            }
        )

        RaftShareSuggestionsHostProvider.publish(" ", "srv-1", emptyList())
        RaftShareSuggestionsHostProvider.publish("acc-1", "", emptyList())
        assertTrue(published.isEmpty())

        val item =
            RaftShareSuggestion(
                shortcutId = assertNotNull(
                    RaftShareSuggestionContract.encodeTargetHint("srv-1", RaftShareSuggestionKind.Channel, "ch-1")
                ),
                serverId = "srv-1",
                serverName = "Acme",
                channelId = "ch-1",
                kind = RaftShareSuggestionKind.Channel,
                title = "#general"
            )
        RaftShareSuggestionsHostProvider.publish("acc-1", "srv-1", listOf(item))
        RaftShareSuggestionsHostProvider.clear("logout")

        assertEquals(listOf(Triple("acc-1", "srv-1", 1)), published)
        assertEquals(listOf("logout"), cleared)
    }
}
