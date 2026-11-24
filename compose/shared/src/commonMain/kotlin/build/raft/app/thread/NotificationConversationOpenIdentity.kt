package build.raft.app

import build.raft.app.model.ConversationKind

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
