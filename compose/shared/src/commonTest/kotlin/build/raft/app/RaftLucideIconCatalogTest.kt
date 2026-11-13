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
    fun artifactIconIsLucideFileCodeWithAWiderPage() {
        // raft-ui ArtifactIcon was Lucide file-code (raft-design #1052, #1054); raft-ui 0.95.1 /
        // ricons 0.1.1 (raft-design #1210) draws its page 2 wider, so it is file-code's shape, not
        // its exact paths: the same parts, with the page's outline changed.
        val paths = { name: RaftLucideIconName -> RaftLucideIconPaths.spec(name).paths.map { it.pathData } }
        val artifact = paths(RaftLucideIconName.Artifact)
        val fileCode = paths(RaftLucideIconName.FileCode)
        assertEquals(fileCode.size, artifact.size, "the same parts as file-code")
        assertFalse(artifact == fileCode, "the page is drawn wider than file-code's")
    }

    @Test
    fun followArtifactIconsAreRaftUiIcons() {
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.FollowArtifact.wireName))
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.UnfollowArtifact.wireName))
    }

    @Test
    fun membersAppsIconUsesTheWebAppWindowIcon() {
        // Web AgentDetailPanel AGENT_PANEL_TABS: the Apps tab is lucide AppWindow.
        assertEquals(RaftLucideIconName.AppWindow, MembersAgentDetailTab.Apps.iconName)
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.AppWindow.wireName))
    }

    @Test
    fun threadMessageMenuUsesWebMessageItemIcons() {
        // web MessageItem's context menu: Lucide forward / list-checks / square-dashed-text /
        // square-check-big / plus, and raft-ui's own ThreadIcon and ReopenTaskIcon.
        listOf(
            RaftLucideIconName.Thread,
            RaftLucideIconName.Forward,
            RaftLucideIconName.ListChecks,
            RaftLucideIconName.SquareDashedText,
            RaftLucideIconName.SquareCheckBig,
            RaftLucideIconName.ReopenTask,
            RaftLucideIconName.Plus
        ).forEach { assertTrue(RaftLucideIcons.Names.contains(it.wireName), it.wireName) }
        assertEquals(RaftLucideIconName.SquareCheckBig, threadTaskStatusMenuIcon(isDone = false))
        assertEquals(RaftLucideIconName.ReopenTask, threadTaskStatusMenuIcon(isDone = true))
    }

    @Test
    fun threadFollowActionsUseRaftUiFollowThreadIcons() {
        assertEquals(RaftLucideIconName.FollowThread, threadFollowMenuIcon(isFollowed = false))
        assertEquals(RaftLucideIconName.UnfollowThread, threadFollowMenuIcon(isFollowed = true))
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.FollowThread.wireName))
        assertTrue(RaftLucideIcons.Names.contains(RaftLucideIconName.UnfollowThread.wireName))

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
        assertTrue(followActions.any { it.icon == RaftLucideIconName.Thread })
        followActions.single { it.icon == RaftLucideIconName.FollowThread }.onClick()
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
        assertTrue(unfollowActions.any { it.icon == RaftLucideIconName.Thread })
        unfollowActions.single { it.icon == RaftLucideIconName.UnfollowThread }.onClick()
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
