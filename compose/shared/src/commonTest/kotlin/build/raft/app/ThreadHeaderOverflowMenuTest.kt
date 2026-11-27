package build.raft.app

import build.raft.app.thread.DELETE_TASK_MENU_ITEM_ID
import build.raft.app.thread.FOLLOW_THREAD_MENU_ITEM_ID
import build.raft.app.thread.MARK_THREAD_UNREAD_MENU_ITEM_ID
import build.raft.app.thread.NOTIFICATION_LEVEL_MENU_ITEM_ID
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SEARCH_IN_TASK_MENU_ITEM_ID
import build.raft.app.thread.THREAD_TIMELINE_MENU_ITEM_ID
import build.raft.app.thread.ThreadHeaderOverflowMenuLabels
import build.raft.app.thread.ThreadHeaderOwnActionSpec
import build.raft.app.thread.ThreadHeaderSideColumnLabels
import build.raft.app.thread.VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID
import build.raft.app.thread.icon
import build.raft.app.thread.threadHeaderOverflowMenuItems
import build.raft.app.thread.threadHeaderSideColumnActionsForPayload
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Thread / task `⋯` header menu order v4 (botiverse/slock #16225 / `563672da5`,
 * `ThreadOverflowMenu.tsx`).
 */
class ThreadHeaderOverflowMenuTest {
    private val labels =
        ThreadHeaderOverflowMenuLabels(
            followLabel = "Unfollow thread",
            markUnreadLabel = "Mark as unread",
            searchLabel = "Search in thread",
            timelineLabel = "Timeline",
            viewInChannelLabel = "View in channel",
            notificationLevelLabel = "Notifications: Default",
            deleteTaskLabel = "Delete task",
        )

    private val folded =
        listOf(
            ThreadHeaderOwnActionSpec(
                id = "thread-side-card-files",
                label = "Open files",
                icon = RaftLucideIconName.Folder,
                onClick = {},
            )
        )

    @Test
    fun replyThreadMenuGroupsFollowAndMarkUnreadBeforeNavigationAndNotifications() {
        val items =
            threadHeaderOverflowMenuItems(
                labels = labels,
                foldedActions = folded,
                followed = true,
                hasToggleFollow = true,
                hasMarkUnread = true,
                hasSearch = true,
                hasTimeline = true,
                hasViewInChannel = true,
                taskFollowLabels = false,
                hasDeleteTask = false,
            )
        assertEquals(
            listOf(
                FOLLOW_THREAD_MENU_ITEM_ID to false,
                MARK_THREAD_UNREAD_MENU_ITEM_ID to false,
                SEARCH_IN_TASK_MENU_ITEM_ID to true,
                THREAD_TIMELINE_MENU_ITEM_ID to false,
                VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID to false,
                "thread-side-card-files" to false,
                NOTIFICATION_LEVEL_MENU_ITEM_ID to true,
            ),
            items.map { it.id to it.dividerBefore },
        )
    }

    @Test
    fun taskMenuSharesOneGroupForFollowAndNavigationAndSeparatesNotificationsAndDelete() {
        // Web ThreadOverflowMenu.tsx:177: a task has no Mark as Unread, so Follow task shares the
        // conversation group rather than standing alone above a divider.
        val items =
            threadHeaderOverflowMenuItems(
                labels = labels.copy(followLabel = "Unfollow task", searchLabel = "Search in task"),
                foldedActions = folded,
                followed = true,
                hasToggleFollow = true,
                hasMarkUnread = false,
                hasSearch = true,
                hasTimeline = false,
                hasViewInChannel = true,
                taskFollowLabels = true,
                hasDeleteTask = true,
            )
        assertEquals(
            listOf(
                FOLLOW_THREAD_MENU_ITEM_ID to false,
                SEARCH_IN_TASK_MENU_ITEM_ID to false,
                VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID to false,
                "thread-side-card-files" to false,
                NOTIFICATION_LEVEL_MENU_ITEM_ID to true,
                DELETE_TASK_MENU_ITEM_ID to true,
            ),
            items.map { it.id to it.dividerBefore },
        )
    }

    @Test
    fun emptyGroupsProduceNoLeadingTrailingOrDoubleDividers() {
        val foldedOnly =
            threadHeaderOverflowMenuItems(
                labels = labels.copy(notificationLevelLabel = null),
                foldedActions = folded,
            )
        assertEquals(listOf("thread-side-card-files" to false), foldedOnly.map { it.id to it.dividerBefore })

        val deleteOnly =
            threadHeaderOverflowMenuItems(
                labels = labels.copy(notificationLevelLabel = null),
                taskFollowLabels = true,
                hasDeleteTask = true,
            )
        assertEquals(listOf(DELETE_TASK_MENU_ITEM_ID to false), deleteOnly.map { it.id to it.dividerBefore })

        val taskWithoutNotifications =
            threadHeaderOverflowMenuItems(
                labels = labels.copy(followLabel = "Follow task", searchLabel = "Search in task", notificationLevelLabel = null),
                followed = false,
                hasToggleFollow = true,
                hasSearch = true,
                hasViewInChannel = true,
                taskFollowLabels = true,
                hasDeleteTask = true,
            )
        assertEquals(
            listOf(
                FOLLOW_THREAD_MENU_ITEM_ID to false,
                SEARCH_IN_TASK_MENU_ITEM_ID to false,
                VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID to false,
                DELETE_TASK_MENU_ITEM_ID to true,
            ),
            taskWithoutNotifications.map { it.id to it.dividerBefore },
        )
    }

    @Test
    fun nativeOverflowSideActionsPlaceFollowAndMarkUnreadBeforeTimelineAndDelete() {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "reply-1",
                title = "",
                status = "ready",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "reply-1",
                parentChannelId = "channel-1",
                parentMessageId = "msg-1",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                showModeTabs = false,
                showSettingsAction = false,
                showOpenInChannelAction = true,
            )
        val actions =
            threadHeaderSideColumnActionsForPayload(
                payload = payload,
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                taskConversation = false,
                labels =
                    ThreadHeaderSideColumnLabels(
                        searchLabel = "Search",
                        searchInThreadLabel = "Search in thread",
                        searchInTaskLabel = "Search in task",
                        settingsLabel = "Details",
                        viewInChannelLabel = "View in channel",
                        timelineLabel = "Timeline",
                        markUnreadLabel = "Mark as unread",
                        followLabel = "Unfollow thread",
                        deleteTaskLabel = "Delete task",
                    ),
                threadFollowed = true,
                onToggleThreadFollow = {},
                onMarkThreadUnread = {},
                onOpenTimeline = {},
            )
        val overflowIds =
            raftNativeNavBarSplit(actions.toRaftNativeNavBarActions())
                .filter { it.placement == RaftNativeNavBarPlacement.Overflow }
                .map { it.id }
        assertEquals(
            listOf(
                FOLLOW_THREAD_MENU_ITEM_ID,
                MARK_THREAD_UNREAD_MENU_ITEM_ID,
                THREAD_TIMELINE_MENU_ITEM_ID,
            ),
            overflowIds,
        )

        val taskPayload =
            RaftThreadScreenPayload(
                instanceId = "task-1",
                title = "",
                status = "ready",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = RaftTaskConversationChannelKind,
                sourceThreadId = "task-1",
                streamChannelId = "task-1",
                parentChannelId = "channel-1",
                showModeTabs = false,
                showSettingsAction = false,
            )
        val taskOverflowIds =
            raftNativeNavBarSplit(
                threadHeaderSideColumnActionsForPayload(
                    payload = taskPayload,
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    taskConversation = true,
                    labels =
                        ThreadHeaderSideColumnLabels(
                            searchLabel = "Search",
                            searchInThreadLabel = "Search in thread",
                            searchInTaskLabel = "Search in task",
                            settingsLabel = "Details",
                            viewInChannelLabel = "View in channel",
                            followLabel = "Unfollow task",
                            deleteTaskLabel = "Delete task",
                        ),
                    threadFollowed = true,
                    onToggleThreadFollow = {},
                    onViewTaskInChannel = {},
                    onDeleteTask = {},
                ).toRaftNativeNavBarActions()
            ).filter { it.placement == RaftNativeNavBarPlacement.Overflow }.map { it.id }
        assertEquals(
            listOf(FOLLOW_THREAD_MENU_ITEM_ID, DELETE_TASK_MENU_ITEM_ID),
            taskOverflowIds,
        )
    }
}
