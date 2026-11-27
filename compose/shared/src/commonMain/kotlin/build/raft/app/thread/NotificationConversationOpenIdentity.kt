package build.raft.app.thread

import build.raft.app.settings.AppShellNotificationThreadLaunchRequest
import build.raft.app.ConversationOpenSource
import build.raft.app.ConversationOpenSourceMessageIdentity
import build.raft.app.model.ConversationKind
import build.raft.app.thread.toConversationOpenSourceMessageIdentity
import build.raft.app.toConversationOpenSourceMessageIdentity

internal fun AppShellNotificationThreadLaunchRequest.toConversationOpenSourceMessageIdentity(): ConversationOpenSourceMessageIdentity =
    taskConversation?.let { target ->
        // Task v2: a task reply push opens the task conversation (landing on the reply when named);
        // the legacy `channelId` / `messageId` (parent channel + anchor) are for older apps only.
        ConversationOpenSourceMessageIdentity(
            source = ConversationOpenSource.Notification,
            messageId = target.taskMessageId?.value,
            channelId = target.conversationChannelId.value,
            conversationKind = ConversationKind.Task,
        )
    } ?: ConversationOpenSourceMessageIdentity(
        source = ConversationOpenSource.Notification,
        messageId = messageId.takeIf { it.isNotBlank() },
        channelId = channelId?.takeIf { it.isNotBlank() } ?: threadId.takeIf { it.isNotBlank() },
        threadChannelId = threadId.takeIf { it.isNotBlank() },
        parentChannelId = parentChannelId?.takeIf { it.isNotBlank() },
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() },
        conversationKind = conversationKind
    )
