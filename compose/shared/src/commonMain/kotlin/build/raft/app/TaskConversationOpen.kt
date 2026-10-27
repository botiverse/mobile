package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskSummary
import build.raft.app.model.topLevelThreadId

/**
 * Task v2: where opening a task lands. Mirrors web `useOpenTask` / `directTaskConversationChannelId`
 * (slock `packages/web/src/components/task/useOpenTask.ts`, `utils/taskConversation.ts`), decided per
 * task (never by the `task_v2_conversations_v0` flag):
 * - a legacy orphan and a pre-v2 task (host message thread) keep the existing thread open;
 * - a v2 task whose canonical conversation is known and is local (not a joint surface) opens it;
 * - otherwise (anchored without a conversation yet, or a joint surface whose LOCAL conversation
 *   differs from the canonical one) `POST /tasks/:id/conversation` answers the local id first.
 */
internal sealed interface TaskOpenTarget {
    data class Conversation(val conversationChannelId: ChannelId) : TaskOpenTarget

    data object ResolveConversation : TaskOpenTarget

    data object HostThread : TaskOpenTarget
}

internal fun TaskSummary.taskOpenTarget(): TaskOpenTarget {
    if (isLegacy || !conversationAnchored) return TaskOpenTarget.HostThread
    val direct =
        conversationChannelId?.takeUnless {
            parseConversationKindOrNull(channelType) == ConversationKind.Joint
        }
    return direct?.let(TaskOpenTarget::Conversation) ?: TaskOpenTarget.ResolveConversation
}

/** The launch identity of a task conversation: a flat, root-less stream keyed by its channel id. */
internal fun taskConversationLaunchIdentity(conversationChannelId: ChannelId): ThreadLaunchConversationIdentity =
    ThreadLaunchConversationIdentity(
        threadId = topLevelThreadId(conversationChannelId),
        channelId = conversationChannelId,
        channelType = RaftTaskConversationChannelKind,
        kind = ConversationKind.Task,
    )

/**
 * The local conversation id a task opens on, resolving it through `POST /tasks/:id/conversation`
 * when the task DTO cannot name it ([TaskOpenTarget.ResolveConversation]). `null` = open the task
 * the pre-v2 way (host thread), or it could not be resolved (the caller reports it).
 */
internal suspend fun resolveTaskOpenConversationChannelId(
    task: TaskSummary,
    serverId: String,
    tasksDataSource: SharedTasksDataSource,
    log: (String) -> Unit = {},
): ChannelId? =
    when (val target = task.taskOpenTarget()) {
        is TaskOpenTarget.Conversation -> target.conversationChannelId
        TaskOpenTarget.HostThread -> null
        TaskOpenTarget.ResolveConversation -> {
            val resolvedServerId = serverId.trim().takeIf { it.isNotBlank() }?.let(::ServerId)
            val result = resolvedServerId?.let { tasksDataSource.openTaskConversation(it, task.id) }
            val opened = (result as? DataResult.Content)?.data?.conversationChannelId
            log(
                "task_conversation_resolve taskId=${task.id.value} " +
                    "result=${if (opened != null) "resolved" else result?.let { it::class.simpleName } ?: "no_server"}"
            )
            opened
        }
    }

/**
 * Builds the launch payload a task opens on. [resolvedConversationChannelId] is the answer of
 * [resolveTaskOpenConversationChannelId] for a task that needed one; without it a task that must
 * be resolved first yields `null`, and a pre-v2 task with a host message falls back to its thread.
 */
internal fun buildTaskOpenLaunchPayload(
    task: TaskSummary,
    resolvedConversationChannelId: ChannelId?,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    hostThreadPayload: () -> WireThreadLaunchPayload?,
): WireThreadLaunchPayload? {
    val conversationId =
        resolvedConversationChannelId
            ?: (task.taskOpenTarget() as? TaskOpenTarget.Conversation)?.conversationChannelId
    if (conversationId != null) {
        return buildStandaloneConversationThreadLaunchPayload(
            conversation = taskConversationLaunchIdentity(conversationId),
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
        )
    }
    // A task resolved to no conversation still opens its pre-v2 host thread when it has one.
    return if (task.taskOpenTarget() == TaskOpenTarget.HostThread || task.hostMessageId != null) {
        hostThreadPayload()
    } else {
        null
    }
}

/**
 * Task v2 push routing: the task-conversation fields of a task reply push (slock #11445, APNs custom
 * payload top level next to `channelId`, and FCM data). The push keeps `kind` / `channelId` /
 * `messageId` (parent channel + anchor) for older apps and adds:
 * - `taskId`: the task;
 * - `taskConversationChannelId`: the conversation the reply was written in (the reader's local one);
 * - `taskMessageId`: the reply's own message id.
 * Without `taskConversationChannelId` the push routes exactly as before.
 */
internal object RaftTaskConversationPushFields {
    const val TaskId = "taskId"
    const val TaskConversationChannelId = "taskConversationChannelId"
    const val TaskMessageId = "taskMessageId"
}

internal data class RaftTaskConversationPushTarget(
    val conversationChannelId: ChannelId,
    val taskId: TaskId?,
    val taskMessageId: MessageId?,
)

/**
 * The single parse point of the task-conversation push fields ([RaftTaskConversationPushFields]),
 * fed by each platform's notification-open payload (Android `RaftCmpActivity`, iOS `iOSApp.swift`).
 * TODO(task-v2): field names follow slock #11445 (not merged yet); re-verify against
 * `packages/server/src/services/pushService.ts` once it lands.
 */
internal fun parseRaftTaskConversationPushTarget(
    taskId: String?,
    taskConversationChannelId: String?,
    taskMessageId: String?,
): RaftTaskConversationPushTarget? {
    val conversation = taskConversationChannelId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return RaftTaskConversationPushTarget(
        conversationChannelId = ChannelId(conversation),
        taskId = taskId?.trim()?.takeIf { it.isNotBlank() }?.let(::TaskId),
        taskMessageId = taskMessageId?.trim()?.takeIf { it.isNotBlank() }?.let(::MessageId),
    )
}
