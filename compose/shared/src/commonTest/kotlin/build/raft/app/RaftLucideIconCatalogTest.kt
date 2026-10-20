package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftLucideIconCatalogTest {
    @Test
    fun imageEditorUsesGeneratedHistoryAndMosaicIcons() {
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.Undo2.wireName))
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.Redo2.wireName))
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.Grid3x3.wireName))
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.Blend.wireName))
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.Mosaic.wireName))
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.Crop.wireName))
    }

    @Test
    fun membersAppsIconUsesGeneratedLink2Icon() {
        assertEquals(RaftLucideIconName.Link2, MembersAgentDetailTab.Apps.iconName)
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.Link2.wireName))
        assertFalse(RaftLucideIcons.Names.contains("plug"))
    }

    @Test
    fun threadConvertToTaskIconUsesGeneratedClipboardCheckIcon() {
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.ClipboardCheck.wireName))
    }

    @Test
    fun threadFollowActionsUseDistinctGeneratedMessageCircleIcons() {
        assertEquals(RaftLucideIconName.MessageCirclePlus, threadFollowMenuIcon(isFollowed = false))
        assertEquals(RaftLucideIconName.MessageCircleOff, threadFollowMenuIcon(isFollowed = true))
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.MessageCirclePlus.wireName))
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.MessageCircleOff.wireName))

        val message =
            threadVisualFixture(THREAD_MESSAGE_VISUAL_FIXTURE_DEFAULT, "follow-menu-test")
                .toThreadTimelineSlice()
                .messages
                .first()
        var followed = false
        var unfollowedThreadId: String? = null
        val followActions =
            threadMenuActionsForTest(
                message = message,
                isFollowed = false,
                onFollow = { followed = true },
                onUnfollow = { unfollowedThreadId = it }
            )
        assertTrue(followActions.any { it.icon == RaftLucideIconName.MessageSquare })
        followActions.single { it.icon == RaftLucideIconName.MessageCirclePlus }.onClick()
        assertTrue(followed)
        assertEquals(null, unfollowedThreadId)

        followed = false
        val unfollowActions =
            threadMenuActionsForTest(
                message = message,
                isFollowed = true,
                onFollow = { followed = true },
                onUnfollow = { unfollowedThreadId = it }
            )
        assertTrue(unfollowActions.any { it.icon == RaftLucideIconName.MessageSquare })
        unfollowActions.single { it.icon == RaftLucideIconName.MessageCircleOff }.onClick()
        assertFalse(followed)
        assertEquals("thread-follow-menu", unfollowedThreadId)
    }

    private fun threadMenuActionsForTest(
        message: RaftThreadMessageItem,
        isFollowed: Boolean,
        onFollow: () -> Unit,
        onUnfollow: (String) -> Unit
    ): List<ThreadMessageMenuAction> =
        threadMessageMenuActions(
            message = message,
            replyMetadata =
                ThreadReplyMetadata(
                    parentMessageId = message.id,
                    threadChannelId = "thread-follow-menu",
                    replyCount = 2,
                    unreadReplyCount = 0,
                    firstUnreadReplyMessageId = null,
                    isThreadFollowed = isFollowed
                ),
            showReplyAction = true,
            showSaveAction = false,
            showTaskStatusAction = false,
            showConvertToTaskAction = false,
            showCopyLinkAction = false,
            showShareMessagesAction = false,
            showCancelAction = false,
            showDeleteAction = false,
            onReply = {},
            onCopyLink = {},
            onCopy = {},
            onSelectText = {},
            onShareMessages = {},
            onSave = {},
            onFollowThread = onFollow,
            onUnfollowThread = onUnfollow,
            onTaskStatusChange = {},
            onConvertToTask = {},
            onCancel = {},
            onDelete = {}
        )
}
