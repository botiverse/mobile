package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ServerId

/**
 * What the client knows about the channel a task lives in, for [canWriteTasksInChannel]. Null
 * fields are not known here.
 */
@RaftCoreInternalApi data class TaskChannelWriteFacts(
    val archived: Boolean = false,
    /** A known posting answer: only a Lounge's `POST /agents/:id/lounge` sends one (`canPost`). */
    val canPost: Boolean? = null,
    /** The reader's membership as the channel row says; null when not hydrated. */
    val joined: Boolean? = null,
    val directMessage: Boolean = false,
    /** An Agent Lounge: nothing to join, the first post joins. */
    val lounge: Boolean = false,
    /** The enabled system `#all` (`type == "channel"`, name `all`): every member posts without a membership row. */
    val enabledAllChannel: Boolean = false,
)

/**
 * Whether the reader may change tasks in a channel: create, status, assignee, title and
 * description, delete (web `canWriteTasksInChannel`, botiverse/slock #15155
 * `components/task/taskChannelWrite.ts`). The server gates every task write on post authority
 * (`routes/tasks.ts` `rejectIfNoTaskWriteAccess` -> 403 "You must join this channel to modify
 * tasks", `channelService.canUserPostToChannel`), then `rejectIfArchived` (409). A public channel
 * can be read without joining it, so its tasks show to someone who cannot change them.
 *
 * Only a known "no" hides an action: a channel that is not loaded ([facts] null), or whose
 * membership is not known, stays writable and the server answers (with its trace id).
 */
@RaftCoreInternalApi fun canWriteTasksInChannel(facts: TaskChannelWriteFacts?): Boolean {
    if (facts == null) return true
    if (facts.archived || facts.canPost == false) return false
    // Nothing to join first in a DM or a Lounge (the first post joins a Lounge).
    if (facts.directMessage || facts.lounge) return true
    // #all: `canUserPostToChannel` lets every human member of the Server post; there is no
    // membership row to read, so a row saying joined=false must not hide its tasks' actions.
    if (facts.enabledAllChannel) return true
    return facts.joined != false
}

/** The system `#all` while it is a public channel (server `isEnabledAllChannel`). */
@RaftCoreInternalApi fun isEnabledAllChannel(channelType: String?, name: String?): Boolean =
    channelType.equals("channel", ignoreCase = true) &&
        name?.trim()?.removePrefix("#").equals("all", ignoreCase = true)

/** The facts of a `GET /channels` / `GET /channels/:id` row as the server sent it. */
@RaftCoreInternalApi fun HomeChannelWireDto.taskChannelWriteFacts(): TaskChannelWriteFacts =
    TaskChannelWriteFacts(
        archived = archivedAt != null,
        joined = joined,
        lounge = isAgentLoungeWire(),
        enabledAllChannel = isEnabledAllChannel(type, name),
    )

/** [loungeCanPost]: the Lounge open answer's `canPost`, when this client has one. */
@RaftCoreInternalApi fun ConversationSummary.taskChannelWriteFacts(loungeCanPost: Boolean? = null): TaskChannelWriteFacts =
    TaskChannelWriteFacts(
        archived = archived,
        canPost = loungeCanPost,
        joined = joined,
        directMessage = kind == ConversationKind.DirectMessage,
        lounge = !loungeAgentId.isNullOrBlank(),
        enabledAllChannel = kind == ConversationKind.Channel && isEnabledAllChannel(channelType ?: "channel", name),
    )

/**
 * A conversation shows its channel's Tasks tab and opens its task cards: wherever its tasks can be
 * written, and in a joined channel that was archived (its tasks stay readable there).
 */
@RaftCoreInternalApi fun ConversationSummary.channelTaskSurfacesShown(): Boolean = permissions.canCreateTask || (joined && archived)

/**
 * A conversation offers task writes (send as task, the message menu's task items) as far as its
 * membership goes: [canWriteTasksInChannel] without the archive, which the page applies itself
 * from its live `channelArchived` (`RaftThreadScreenPayload.channelTaskWritesShown`), so an
 * archive or unarchive on an open page takes effect without reopening it.
 */
@RaftCoreInternalApi fun ConversationSummary.channelTaskWritesAllowed(): Boolean =
    channelTaskSurfacesShown() && canWriteTasksInChannel(taskChannelWriteFacts().copy(archived = false))

@RaftCoreInternalApi fun SharedConversationDirectoryProjection.taskChannelWriteFacts(loungeCanPost: Boolean? = null): TaskChannelWriteFacts =
    TaskChannelWriteFacts(
        archived = archived,
        canPost = loungeCanPost,
        joined = joined,
        directMessage = conversationKind == ConversationKind.DirectMessage,
        lounge = !loungeAgentId.isNullOrBlank(),
        enabledAllChannel = conversationKind == ConversationKind.Channel && isEnabledAllChannel(channelKind, title),
    )

/**
 * [canWriteTasksInChannel] for [channelId] as this server's conversation directory has it (a
 * Lounge's known `canPost` too). Not listed: writable, the server decides.
 */
@RaftCoreInternalApi fun SharedConversationDirectoryState.canWriteTasksInChannel(serverId: ServerId?, channelId: ChannelId?): Boolean {
    val id = channelId?.value?.takeIf { it.isNotBlank() } ?: return true
    val projection = resolve(id)?.takeIf { it.canonicalChannelId == id } ?: return true
    val loungeCanPost = RaftAgentLounges.lounge(serverId?.value, id)?.canPost
    return canWriteTasksInChannel(projection.taskChannelWriteFacts(loungeCanPost))
}

/**
 * The page's answer to "may the reader change tasks in this channel" ([canWriteTasksInChannel]),
 * for task lists that hold tasks of several channels (the Tasks page). The default allows every
 * channel: a host that knows nothing leaves the server to decide.
 */
@RaftCoreInternalApi val LocalTaskChannelWriteCheck = staticCompositionLocalOf<(ChannelId?) -> Boolean> { { true } }
