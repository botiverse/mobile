package build.raft.app

import build.raft.app.data.SettingsServerSidebarPinnedItem
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
    fun wireUserSelfDmClickTargetsCachedConversationWithoutMemberOpen() {
        val target = pinnedSelfDmRows(pinnedMemberProfileResolver = { null }).single().clickTarget()

        val conversation = assertIs<RaftHomePinnedClickTarget.Conversation>(target)
        assertEquals("dm-self", conversation.conversationId)
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
