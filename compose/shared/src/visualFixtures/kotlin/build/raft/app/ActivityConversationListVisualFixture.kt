package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.TaskStatus

/*
 * Activity conversation-list visual fixture: Activity rendered as the web ThreadsInbox list layout.
 * One screen covers every row branch: a pinned channel and a pinned agent DM (Pinned section), an
 * unread thread with a task badge, @ marker, draft pencil, the sender's face and a body long enough to
 * wrap to two lines, a muted channel with unread (bell-off, no count), a DM whose last message came from
 * the peer (preview alone), a person's DM last written by the viewer (localized "You" sender), and a
 * mention action (localized "System" sender).
 */

private val activityConversationListVisualItems: List<InboxItem> =
    listOf(
        InboxItem(
            key = "conv-list-thread-unread",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("thread-conv-list"),
            channelName = "thread",
            unreadCount = 3,
            hasMention = true,
            hasDraft = true,
            firstUnreadMessageId = MessageId("msg-conv-list-reply"),
            threadChannelId = ChannelId("thread-conv-list"),
            parentChannelId = ChannelId("channel-design"),
            parentChannelName = "design",
            parentChannelKind = ConversationKind.Channel,
            parentMessageId = MessageId("msg-conv-list-parent"),
            parentMessagePreview = "Ship the conversation list behind the server flag",
            latestActivityMessageId = MessageId("msg-conv-list-reply"),
            latestActivityPreview = "Pinned rows now float to the top, and the preview wraps to a second line before it ellipsizes.",
            latestActivityAt = "2m",
            latestActivitySenderName = "Mahua",
            latestActivitySenderId = "agent-mahua",
            latestActivitySenderKind = MemberKind.Agent,
            latestActivitySenderAvatarWireValue = "pixel:robot",
            replyCount = 5,
            taskNumber = 5,
            taskStatus = TaskStatus.InProgress,
        ),
        InboxItem(
            key = "conv-list-channel-pinned",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-android"),
            channelName = "android",
            unreadCount = 12,
            firstUnreadMessageId = MessageId("msg-conv-list-android"),
            lastMessageId = MessageId("msg-conv-list-android"),
            lastMessagePreview = "Published the new render artifact to staging.",
            lastMessageAt = "5m",
            lastMessageSenderName = "artin",
            lastMessageSenderId = "user-artin",
            lastMessageSenderKind = MemberKind.Human,
        ),
        InboxItem(
            key = "conv-list-channel-muted",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-random"),
            channelName = "random",
            unreadCount = 4,
            lastMessageId = MessageId("msg-conv-list-random"),
            lastMessagePreview = "Anyone up for lunch?",
            lastMessageAt = "1h",
            lastMessageSenderName = "Cindy",
            lastMessageSenderId = "agent-cindy",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessageSenderAvatarWireValue = "pixel:random:fixture-cindy",
        ),
        InboxItem(
            key = "conv-list-dm-agent-pinned",
            kind = InboxItemKind.DirectMessage,
            channelId = ChannelId("dm-fable"),
            channelName = "dm-fable",
            channelKind = ConversationKind.DirectMessage,
            unreadCount = 1,
            lastMessageId = MessageId("msg-conv-list-fable"),
            lastMessagePreview = "Storyboard for the launch thread is ready to review.",
            lastMessageAt = "3h",
            lastMessageSenderName = "Fable",
            lastMessageSenderId = "agent-fable",
            lastMessageSenderKind = MemberKind.Agent,
        ),
        InboxItem(
            key = "conv-list-dm-human",
            kind = InboxItemKind.DirectMessage,
            channelId = ChannelId("dm-xxchan"),
            channelName = "dm-xxchan",
            channelKind = ConversationKind.DirectMessage,
            lastMessageId = MessageId("msg-conv-list-xxchan"),
            lastMessagePreview = "Sounds good, merging after CI.",
            lastMessageAt = "1d",
            lastMessageSenderName = "you",
            lastMessageSenderId = "user-me",
            lastMessageSenderKind = MemberKind.Human,
        ),
        InboxItem(
            key = "conv-list-mention-action",
            kind = InboxItemKind.MentionAction,
            channelId = ChannelId("channel-general"),
            channelName = "general",
            unreadCount = 1,
            hasMention = true,
            lastMessageId = MessageId("msg-conv-list-mention"),
            lastMessagePreview = "@quill was not notified at send time.",
            lastMessageAt = "2d",
        ),
    )

private val activityConversationListVisualSnapshot =
    InboxSnapshot(
        server = homeSearchVisualServer,
        items = activityConversationListVisualItems,
        totalCount = activityConversationListVisualItems.size,
        totalUnreadCount = activityConversationListVisualItems.sumOf { it.unreadCount },
        hasMore = false,
    )

private fun activityConversationListVisualContext(withPins: Boolean): ActivityConversationListContext =
    ActivityConversationListContext(
        pinnedRefs =
            if (withPins) {
                listOf(
                    SettingsServerSidebarPinnedItem(kind = "channel", id = "channel-android"),
                    SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-fable"),
                )
            } else {
                emptyList()
            },
        mutedChannelIds = setOf("channel-random"),
        currentMemberId = "user-me",
        dmPeersByChannelId =
            mapOf(
                "dm-fable" to
                    ActivityConversationDmPeer(
                        peerId = "agent-fable",
                        peerIsAgent = true,
                        avatarWireValue = "pixel:random:fixture-fable",
                        displayName = "Fable",
                        activityMuted = false,
                    ),
                "dm-xxchan" to
                    ActivityConversationDmPeer(
                        peerId = "user-xxchan",
                        peerIsAgent = false,
                        avatarWireValue = null,
                        displayName = "xxchan",
                        activityMuted = false,
                    ),
            ),
    )

private val activityConversationListVisualDraftProjection =
    setOf(
        SharedThreadComposerDraftRecord(
            key = "reply:msg-conv-list-parent",
            sendChannelId = "thread-conv-list",
            parentMessageId = "msg-conv-list-parent",
        )
    ).toSharedThreadComposerDraftProjection()

@Composable
internal fun HomeActivityConversationListVisualContent(withPins: Boolean) {
    Box(modifier = Modifier.width(342.dp).height(620.dp)) {
        SharedHomeActivityPanel(
            snapshot = activityConversationListVisualSnapshot,
            onOpenItem = {},
            onClose = {},
            activityTaskStatusBadgesEnabled = true,
            draftProjection = activityConversationListVisualDraftProjection,
            foregroundEpoch = 0L,
            conversationList = ActivityConversationListPresentation(context = activityConversationListVisualContext(withPins)),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
