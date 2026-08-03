package build.raft.app.data

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

class SharedHomeIdentityPersistPinnedDmTest {
    @Test
    fun prunedIdentitySnapshotKeepsTypedPinnedDmResolvableBeforeRefresh() {
        val peerId = MemberId("human-1")
        val snapshot =
            HomeSnapshot(
                server = ServerSummary(ServerId("server-1"), "Server One"),
                currentMember =
                    MemberProfile(
                        id = MemberId("viewer-1"),
                        displayName = "Viewer",
                        handle = "viewer@example.com",
                        kind = MemberKind.Human
                    ),
                channels =
                    listOf(
                        HomeChannelRow(
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            name = "Channel One"
                        )
                    ),
                directMessages =
                    listOf(
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-1"),
                            channelId = ChannelId("dm-1"),
                            kind = ConversationKind.DirectMessage,
                            name = "Human One",
                            directMessagePeerType = "human",
                            presenceMemberId = peerId,
                            unreadCount = 3,
                            unreadCountKnown = true,
                            lastMessagePreview = "must be pruned"
                        )
                    ),
                unreadTotal = 3,
                sidebarOrder =
                    SettingsServerSidebarOrderSnapshot(
                        pinned =
                            listOf(
                                SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-1"),
                                SettingsServerSidebarPinnedItem(kind = "human", id = peerId.value)
                            ),
                        pinnedOrder = listOf("channel-1", peerId.value),
                        pinnedSortMode = "manual"
                    )
            )

        val pruned = snapshot.pruneForHomeIdentityPersist()
        val pinnedConversationIds =
            pruned.toHomeServerDrawerNormalizedContent()
                .rows
                .filterIsInstance<HomeServerDrawerNormalizedRow.Conversation>()
                .filter { it.section == HomeServerDrawerConversationSection.Pinned }
                .map { it.conversation.threadId.value }

        assertEquals(peerId, pruned.directMessages.single().presenceMemberId)
        assertEquals(0, pruned.directMessages.single().unreadCount)
        assertEquals(null, pruned.directMessages.single().lastMessagePreview)
        assertEquals(listOf("channel-1", "dm-1"), pinnedConversationIds)
    }
}
