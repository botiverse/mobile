package build.raft.app

import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RaftHomePinnedWireIdentityTest {
    @Test
    fun wireUserSelfDmResolvesFromCacheBeforeMembersLoad() {
        val pinned = pinnedSelfDmRows(pinnedMemberProfileResolver = { null })

        assertEquals(listOf("dm-self"), pinned.map { it.conversation?.id })
        assertEquals(listOf(null), pinned.map { it.member?.identity?.memberId })
    }

    @Test
    fun wireUserSelfDmPrefersCachedConversationOverLateMemberFallback() {
        val pinned =
            pinnedSelfDmRows {
                RaftHomePinnedMemberItem(
                    identity = MemberDetailIdentity(MemberId("user-self"), MemberKind.Human),
                    name = "Late Artin",
                    avatarLabel = "A"
                )
            }

        assertEquals(listOf("dm-self"), pinned.map { it.conversation?.id })
        assertEquals(listOf(null), pinned.map { it.member?.identity?.memberId })
    }

    @Test
    fun wireUserSelfDmClickTargetsCachedConversationWithoutMemberOpen() {
        val target = pinnedSelfDmRows(pinnedMemberProfileResolver = { null }).single().clickTarget()

        val conversation = assertIs<RaftHomePinnedClickTarget.Conversation>(target)
        assertEquals("dm-self", conversation.conversationId)
    }

    @Test
    fun agentWireDmStillResolvesTypedAgentPin() {
        val pinned =
            pinnedRowsForSidebar(
                channels = emptyList(),
                directMessages = listOf(dm(id = "dm-agent", peerType = "agent", memberId = "agent-1")),
                pinnedIds = emptyList(),
                pinnedOrder = emptyList(),
                typedPinnedItems = listOf(SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1")),
                mode = RaftSidebarSortMode.Manual
            )

        assertEquals(listOf("dm-agent"), pinned.map { it.conversation?.id })
    }

    @Test
    fun projectedHumanDmStillResolvesTypedHumanPin() {
        val pinned =
            pinnedRowsForSidebar(
                channels = emptyList(),
                directMessages = listOf(dm(id = "dm-human", peerType = "human", memberId = "human-1")),
                pinnedIds = emptyList(),
                pinnedOrder = emptyList(),
                typedPinnedItems = listOf(SettingsServerSidebarPinnedItem(kind = "human", id = "human-1")),
                mode = RaftSidebarSortMode.Manual
            )

        assertEquals(listOf("dm-human"), pinned.map { it.conversation?.id })
    }

    @Test
    fun channelPinStillResolvesByConversationId() {
        val pinned =
            pinnedRowsForSidebar(
                channels =
                    listOf(
                        RaftHomeConversationItem(
                            id = "channel-1",
                            name = "general",
                            kind = RaftThreadSurfaceKind.Channel,
                            channelType = "public"
                        )
                    ),
                directMessages = emptyList(),
                pinnedIds = emptyList(),
                pinnedOrder = emptyList(),
                typedPinnedItems = listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-1")),
                mode = RaftSidebarSortMode.Manual
            )

        assertEquals(listOf("channel-1"), pinned.map { it.conversation?.id })
    }

    @Test
    fun typedMemberKindMismatchDoesNotClaimCachedDm() {
        val pinned =
            pinnedRowsForSidebar(
                channels = emptyList(),
                directMessages = listOf(dm(id = "dm-human", peerType = "user", memberId = "member-1")),
                pinnedIds = emptyList(),
                pinnedOrder = emptyList(),
                typedPinnedItems = listOf(SettingsServerSidebarPinnedItem(kind = "agent", id = "member-1")),
                mode = RaftSidebarSortMode.Manual
            )

        assertEquals(emptyList(), pinned)
    }

    @Test
    fun typedMemberWithoutConversationKeepsMemberOpenFallback() {
        val pinned =
            pinnedRowsForSidebar(
                channels = emptyList(),
                directMessages = emptyList(),
                pinnedIds = emptyList(),
                pinnedOrder = emptyList(),
                typedPinnedItems = listOf(SettingsServerSidebarPinnedItem(kind = "human", id = "human-new")),
                pinnedMemberProfileResolver = {
                    RaftHomePinnedMemberItem(
                        identity = MemberDetailIdentity(MemberId("human-new"), MemberKind.Human),
                        name = "New Human",
                        avatarLabel = "N"
                    )
                },
                mode = RaftSidebarSortMode.Manual
            )

        val member = assertIs<RaftHomePinnedClickTarget.Member>(pinned.single().clickTarget())
        assertEquals(MemberId("human-new"), member.identity.memberId)
    }

    private fun pinnedSelfDmRows(
        pinnedMemberProfileResolver: (String) -> RaftHomePinnedMemberItem?
    ): List<RaftHomePinnedRow> =
        pinnedRowsForSidebar(
            channels = emptyList(),
            directMessages =
                listOf(
                    RaftHomeConversationItem(
                        id = "dm-self",
                        name = "Artin",
                        kind = RaftThreadSurfaceKind.DirectMessage,
                        channelType = "dm",
                        // /channels/dm uses the InternalActor wire value `user`; the typed
                        // sidebar pin uses the projected member kind `human`.
                        peerType = "user",
                        presenceMemberId = "user-self"
                    )
                ),
            pinnedIds = emptyList(),
            pinnedOrder = emptyList(),
            typedPinnedItems = listOf(SettingsServerSidebarPinnedItem(kind = "human", id = "user-self")),
            pinnedMemberProfileResolver = pinnedMemberProfileResolver,
            mode = RaftSidebarSortMode.Manual
        )

    private fun dm(
        id: String,
        peerType: String,
        memberId: String
    ): RaftHomeConversationItem =
        RaftHomeConversationItem(
            id = id,
            name = id,
            kind = RaftThreadSurfaceKind.DirectMessage,
            channelType = "dm",
            peerType = peerType,
            presenceMemberId = memberId
        )
}
