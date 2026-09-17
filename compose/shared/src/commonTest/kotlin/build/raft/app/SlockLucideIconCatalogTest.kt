package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockLucideIconCatalogTest {
    @Test
    fun imageEditorUsesGeneratedHistoryAndMosaicIcons() {
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.Undo2.wireName))
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.Redo2.wireName))
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.Grid3x3.wireName))
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.Blend.wireName))
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.Mosaic.wireName))
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.Crop.wireName))
    }

    @Test
    fun membersAppsIconUsesGeneratedLink2Icon() {
        assertEquals(SlockLucideIconName.Link2, MembersAgentDetailTab.Apps.iconName)
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.Link2.wireName))
        assertFalse(SlockLucideIcons.Names.contains("plug"))
    }

    @Test
    fun threadConvertToTaskIconUsesGeneratedClipboardCheckIcon() {
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.ClipboardCheck.wireName))
    }

    @Test
    fun threadFollowActionsUseDistinctGeneratedMessageCircleIcons() {
        assertEquals(SlockLucideIconName.MessageCirclePlus, threadFollowMenuIcon(isFollowed = false))
        assertEquals(SlockLucideIconName.MessageCircleOff, threadFollowMenuIcon(isFollowed = true))
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.MessageCirclePlus.wireName))
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.MessageCircleOff.wireName))

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
        assertTrue(followActions.any { it.icon == SlockLucideIconName.MessageSquare })
        followActions.single { it.icon == SlockLucideIconName.MessageCirclePlus }.onClick()
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
        assertTrue(unfollowActions.any { it.icon == SlockLucideIconName.MessageSquare })
        unfollowActions.single { it.icon == SlockLucideIconName.MessageCircleOff }.onClick()
        assertFalse(followed)
        assertEquals("thread-follow-menu", unfollowedThreadId)
    }

    private fun threadMenuActionsForTest(
        message: KuiklyThreadMessageItem,
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
