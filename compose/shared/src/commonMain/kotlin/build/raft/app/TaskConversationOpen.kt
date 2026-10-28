package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.topLevelThreadId

/**
 * Task v2: where opening a task lands. Mirrors web `useOpenTask` / `opensAsTaskConversation` /
 * `directTaskConversationChannelId` (slock `packages/web/src/components/task/useOpenTask.ts`,
 * `utils/taskConversation.ts`):
 * - a legacy orphan (server `isLegacy`: no host message, no conversation, not created to have one)
 *   opens the read-only legacy panel ([LegacyPanel], web `LegacyTaskPanel`);
 * - with `task_v2_conversations_v0` off, a pre-v2 task (not `conversationAnchored`) keeps its host
 *   message's thread; with the flag on every non-legacy task opens as its own conversation;
 * - a task with no conversation yet opens it empty ([PendingConversation]): opening never creates
 *   it; the first send does (web `TaskConversationDialog`, `ensureTaskModalThreadChannel`);
 * - a task whose canonical conversation is known and is local (not a joint surface) opens it;
 * - a joint surface's LOCAL conversation differs from the canonical one: `POST /tasks/:id/conversation`
 *   (idempotent; the conversation already exists, so nothing is created) answers it first.
 */
internal sealed interface TaskOpenTarget {
    data class Conversation(val conversationChannelId: ChannelId) : TaskOpenTarget

    data object ResolveConversation : TaskOpenTarget

    data object HostThread : TaskOpenTarget

    /** No conversation yet: open it empty; the first send creates it ([TaskOpenSurface.Pending]). */
    data object PendingConversation : TaskOpenTarget

    /** A legacy orphan: the read-only panel ([TaskOpenSurface.Legacy]). */
    data object LegacyPanel : TaskOpenTarget
}

internal fun TaskSummary.taskOpenTarget(taskV2Enabled: Boolean): TaskOpenTarget {
    if (isLegacy) return TaskOpenTarget.LegacyPanel
    if (!opensAsTaskConversation(taskV2Enabled)) return TaskOpenTarget.HostThread
    val conversation = conversationChannelId ?: return TaskOpenTarget.PendingConversation
    return if (parseConversationKindOrNull(channelType) == ConversationKind.Joint) {
        TaskOpenTarget.ResolveConversation
    } else {
        TaskOpenTarget.Conversation(conversation)
    }
}

/** Web `opensAsTaskConversation(task, taskV2Enabled)`: never for a legacy orphan; else with the flag on, or per task. */
internal fun TaskSummary.opensAsTaskConversation(taskV2Enabled: Boolean): Boolean =
    !isLegacy && (taskV2Enabled || conversationAnchored)

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
    taskV2Enabled: Boolean,
    log: (String) -> Unit = {},
): ChannelId? =
    when (val target = task.taskOpenTarget(taskV2Enabled)) {
        is TaskOpenTarget.Conversation -> target.conversationChannelId
        // Never POSTed: that would create the conversation (web: opening does not create).
        TaskOpenTarget.HostThread, TaskOpenTarget.PendingConversation, TaskOpenTarget.LegacyPanel -> null
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
            ?: (task.taskOpenTarget(taskV2Enabled = false) as? TaskOpenTarget.Conversation)?.conversationChannelId
    if (conversationId != null) {
        return buildStandaloneConversationThreadLaunchPayload(
            conversation = taskConversationLaunchIdentity(conversationId),
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
        )
    }
    // A task resolved to no conversation still opens its pre-v2 host thread when it has one.
    // A legacy orphan has no thread to open (its panel is [TaskOpenSurface.Legacy]).
    if (task.isLegacy) return null
    return if (task.taskOpenTarget(taskV2Enabled = false) == TaskOpenTarget.HostThread || task.hostMessageId != null) {
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

/**
 * Task v2 (web `TaskConversationBar.viewInChannel`): the message the task hangs under in its
 * channel — its source, or a pre-v2 task's host — and null for a task created without one (the
 * channel itself opens).
 */
internal fun TaskSummary.taskViewInChannelAnchor(): MessageId? = sourceMessageId ?: hostMessageId

/**
 * Where View in Channel and the Source row lead (web `nav.toMessage` / `nav.toDmMessage` /
 * `nav.toChannel`): the task's channel (a DM, a joint or an ordinary channel, as its `channelType`
 * says), anchored on [anchorMessageId] when there is one. Null without a channel.
 */
internal fun TaskSummary.taskChannelOpenRequest(
    serverId: String,
    anchorMessageId: MessageId?,
    conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState(),
): HomeMessageOpenRequest? {
    val channel = channelId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val kind =
        parseConversationKindOrNull(channelType)
            ?.takeIf { it == ConversationKind.Channel || it == ConversationKind.DirectMessage || it == ConversationKind.Joint }
            ?: ConversationKind.Channel
    val identity =
        ConversationOpenSourceMessageIdentity(
            source = ConversationOpenSource.Task,
            messageId = anchorMessageId?.value?.takeIf { it.isNotBlank() },
            channelId = channel,
            conversationKind = kind,
        )
    return (resolveOpenRequest(serverId, identity, conversationDirectory) as? ConversationOpenResolution.Resolved)?.request
}

/**
 * Task v2 (web `TaskEarlierDiscussionProperty`): a pre-v2 task's earlier discussion is the thread
 * of its host message; [threadChannelId] is that thread when the lookup named it. Null for a task
 * without a host message.
 */
internal fun TaskSummary.taskEarlierDiscussionOpenRequest(
    serverId: String,
    threadChannelId: ChannelId?,
    conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState(),
): HomeMessageOpenRequest? {
    val host = hostMessageId ?: return null
    if (channelId == null) return null
    return copy(
        messageId = host,
        parentMessageId = host,
        threadId = threadChannelId?.value?.let(::ThreadId) ?: threadId,
    ).resolveTaskThreadOpenRequest(
        serverId = serverId,
        conversationDirectory = conversationDirectory,
        parentConversationKind = parseConversationKindOrNull(channelType),
    )
}
