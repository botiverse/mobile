package build.raft.app.thread

import build.raft.app.data.map
import build.raft.app.data.SharedConversationIdentityProvider
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SharedReferenceResolver
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTaskReferenceResolution
import build.raft.app.data.SharedTaskReferenceResolutionSource
import build.raft.app.data.resolveSharedTaskReference
import build.raft.app.markdown.defaultRaftMarkdownTagValidity
import build.raft.app.markdown.formatRaftDirectMessagePeerRef
import build.raft.app.markdown.RaftLoungeRefAgent
import build.raft.app.markdown.resolveRaftLoungeRefAgent
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.markdown.preparedRaftMarkdownFullRendererContentOrNull
import build.raft.app.markdown.threadMarkdownTagDisplayLabel
import build.raft.app.mentionVisibleLabel
import build.raft.app.messages
import build.raft.app.model.ChannelId
import build.raft.app.model.ExternalMentionSpan
import build.raft.app.model.TaskSummary
import build.raft.app.nonBlank
import build.raft.app.origin
import build.raft.app.parseConversationKindOrNull
import build.raft.app.resolveTaskOpenConversationChannelId
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.taskOpenSurface
import build.raft.app.taskOpenTarget
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import androidx.compose.runtime.key
import build.raft.app.RaftTaskConversationChannelKind
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.TaskOpenTarget
import build.raft.app.settings.buildStandaloneTaskThreadLaunchPayload
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel

internal fun String.normalizedThreadMentionLookupKey(): String =
    trim().removePrefix("@").trim().lowercase()

/**
 * Mention lookup key -> "@Display Name" for every member the page already knows: channel members
 * first, then the server directory (non-joined members are still mentionable and must not render
 * as a raw handle).
 */
internal fun threadMarkdownMentionDisplayLabels(
    members: List<RaftThreadMemberItem>,
    memberCandidates: List<RaftThreadMemberItem>
): ThreadMarkdownMentionDisplayLabels =
    ThreadMarkdownMentionDisplayLabels(
        (members + memberCandidates).mapNotNull { member ->
            val name = member.displayName.trim().removePrefix("@").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ThreadMarkdownMentionDisplayLabel(
                id = member.id,
                handle = member.handle,
                displayName = member.displayName,
                label = mentionVisibleLabel(member.handle, name)
            )
        }
    )

internal data class ThreadMarkdownMentionDisplayLabel(
    val id: String,
    val handle: String,
    val displayName: String,
    val label: String
)

/** Equality-comparable, so a same-content member refresh does not invalidate markdown token plans. */
internal data class ThreadMarkdownMentionDisplayLabels(
    private val entries: List<ThreadMarkdownMentionDisplayLabel>
) {
    fun labelFor(tag: ThreadMarkdownTag): String? {
        val key = tag.label.normalizedThreadMentionLookupKey()
        if (key.isBlank()) return null
        return entries.firstOrNull { entry ->
            entry.id.equals(key, ignoreCase = true) ||
                entry.handle.removePrefix("@").equals(key, ignoreCase = true) ||
                entry.displayName.equals(key, ignoreCase = true)
        }?.label
    }
}

internal fun resolveThreadMarkdownTagDisplayLabel(
    tag: ThreadMarkdownTag,
    labels: ThreadMarkdownMentionDisplayLabels
): String =
    when (tag.kind) {
        ThreadMarkdownTagKind.UserMention -> labels.labelFor(tag) ?: threadMarkdownTagDisplayLabel(tag.label)
        ThreadMarkdownTagKind.ExternalMention -> tag.label
        else -> threadMarkdownTagDisplayLabel(tag.label)
    }

internal fun List<String>.toKnownMarkdownChannelNameSet(): Set<String> =
    mapNotNull { it.trim().takeIf { name -> name.isNotBlank() }?.lowercase() }
        .toSet()

/**
 * [timelineMessages]: the rows on screen. A cached timeline shows before the payload carries its
 * messages, and their authors are known names from that first frame on.
 */
internal fun RaftThreadScreenPayload.toKnownMarkdownMentionNameSet(
    timelineMessages: List<RaftThreadMessageItem> = emptyList()
): Set<String> =
    if (knownMarkdownMentionNames.isNotEmpty()) {
        buildSet {
            knownMarkdownMentionNames.forEach { name -> addKnownThreadMentionValue(name) }
        }
    } else {
        buildSet {
            members.forEach { member -> addKnownThreadMentionValues(member) }
            memberCandidates.forEach { member -> addKnownThreadMentionValues(member) }
            originMessage?.let { message -> addKnownThreadMentionValues(message) }
            messages.forEach { message -> addKnownThreadMentionValues(message) }
            timelineMessages.forEach { message -> addKnownThreadMentionValues(message) }
        }
    }

internal fun RaftThreadScreenPayload.toKnownMarkdownDirectMessagePeerNameSet(): Set<String> {
    val homeNames = buildSet { knownMarkdownDirectMessagePeerNames.forEach { name -> addKnownThreadMentionValue(name) } }
    val names =
        buildSet {
            addAll(homeNames)
            dmPeerName?.let { addKnownThreadMentionValue(it) }
            dmPeerIdentity?.let { identity ->
                addKnownThreadMentionValue(identity.memberId.value)
                dmPeerName?.let { name -> addKnownThreadMentionValue(formatRaftDirectMessagePeerRef(name, identity.kind)) }
            }
            if (surfaceKind == RaftThreadSurfaceKind.DirectMessage ||
                parentSurfaceKind == RaftThreadSurfaceKind.DirectMessage ||
                channelKind == "dm" ||
                parentChannelKind == "dm"
            ) {
                members.forEach { member -> addKnownThreadMentionValues(member) }
                memberCandidates.forEach { member -> addKnownThreadMentionValues(member) }
            }
        }
    return names
}

/**
 * The agents a `lounge:@agent…` ref in this page may name (slock #16279): the viewed server's agents
 * the page knows, the channel's members and the server directory, by their current name (handle).
 * Another server's agent (a joint channel's) is none of them: the server resolves a Lounge ref in its
 * own server only.
 */
internal fun RaftThreadScreenPayload.toMarkdownLoungeRefAgents(): List<RaftLoungeRefAgent> =
    (members + memberCandidates)
        .filter { member -> member.kind.equals("agent", ignoreCase = true) && member.originServers.isEmpty() }
        .mapNotNull { member ->
            val name = member.handle.trim().removePrefix("@").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            RaftLoungeRefAgent(id = member.id, name = name, displayName = member.displayName.trim().takeIf { it.isNotEmpty() })
        }
        .distinctBy { it.id }

internal fun RaftThreadScreenPayload.toKnownMarkdownTaskNumberSetOrNull(): Set<Int>? =
    knownMarkdownTaskNumbers.takeIf { it.isNotEmpty() }
        ?.filterTo(mutableSetOf()) { it > 0 }

internal fun threadMarkdownTagContextSignature(
    highlightedMentions: Set<String>,
    knownChannelNames: Set<String>,
    knownMentionNames: Set<String>,
    knownDirectMessagePeerNames: Set<String>,
    knownTaskNumbers: Set<Int>?,
    loungeAgents: List<RaftLoungeRefAgent> = emptyList()
): String =
    "highlighted=${highlightedMentions.size}:${highlightedMentions.hashCode().toString(16)} " +
        "channels=${knownChannelNames.size}:${knownChannelNames.hashCode().toString(16)} " +
        "mentions=${knownMentionNames.size}:${knownMentionNames.hashCode().toString(16)} " +
        "dmPeers=${knownDirectMessagePeerNames.size}:${knownDirectMessagePeerNames.hashCode().toString(16)} " +
        "tasks=${knownTaskNumbers?.size ?: -1}:${knownTaskNumbers?.hashCode()?.toString(16) ?: "all"}" +
        // Only a page that knows Lounge agents says so: every other signature stays as it was.
        if (loungeAgents.isEmpty()) "" else " lounges=${loungeAgents.size}:${loungeAgents.hashCode().toString(16)}"

private fun MutableSet<String>.addKnownThreadMentionValues(member: RaftThreadMemberItem) {
    addKnownThreadMentionValue(member.id)
    addKnownThreadMentionValue(member.handle)
    addKnownThreadMentionValue(member.displayName)
}

private fun MutableSet<String>.addKnownThreadMentionValues(message: RaftThreadMessageItem) {
    addKnownThreadMentionValue(message.authorId)
    addKnownThreadMentionValue(message.authorHandle)
    addKnownThreadMentionValue(message.authorName)
}

private fun MutableSet<String>.addKnownThreadMentionValue(value: String?) {
    val key = value?.normalizedThreadMentionLookupKey().orEmpty()
    if (key.isNotBlank()) add(key)
}

internal fun ThreadMarkdownTag.isKnownThreadMarkdownTag(
    knownChannelNames: Set<String>,
    knownMentionNames: Set<String>,
    knownDirectMessagePeerNames: Set<String>,
    knownTaskNumbers: Set<Int>? = null,
    /**
     * The agents a `lounge:@agent…` ref may name ([toMarkdownLoungeRefAgents]); empty where Lounges are
     * off for the reader (the flag, a Guest), so such a ref stays plain text there.
     */
    loungeAgents: List<RaftLoungeRefAgent> = emptyList()
): Boolean =
    when (kind) {
        // `#proj:#7` / `dm:@peer:#7` / `lounge:@agent:#7` chip when the reader knows the conversation
        // (Web: a channel the reader cannot resolve keeps the ref as plain text); the task itself is
        // resolved on open. A bare `#7` chips only when it is a known task of the conversation being read
        // (Web MessageItem `createRaftBareTaskRefRegex`); any other `#4442` is plain text. No task list
        // ([knownTaskNumbers] null) knows no task.
        ThreadMarkdownTagKind.Task ->
            when {
                loungeAgentName != null -> resolveRaftLoungeRefAgent(loungeAgentName, loungeAgents) != null
                channelName != null -> channelName.trim().lowercase().let { it.isNotBlank() && it in knownChannelNames }
                directMessagePeerName != null ->
                    directMessagePeerName.normalizedThreadMentionLookupKey() in knownDirectMessagePeerNames
                else -> label.removePrefix("#").toIntOrNull()?.let { knownTaskNumbers?.contains(it) } == true
            }
        ThreadMarkdownTagKind.UserMention -> label.normalizedThreadMentionLookupKey() in knownMentionNames
        ThreadMarkdownTagKind.ExternalMention -> true
        // A channel or DM the reader cannot resolve keeps the ref as plain text (Web MessageItem).
        ThreadMarkdownTagKind.Message ->
            if (loungeAgentName != null) {
                resolveRaftLoungeRefAgent(loungeAgentName, loungeAgents) != null
            } else if (directMessagePeerName != null) {
                directMessagePeerName.normalizedThreadMentionLookupKey() in knownDirectMessagePeerNames
            } else {
                channelName.orEmpty().trim().lowercase().let { it.isNotBlank() && it in knownChannelNames }
            }
        ThreadMarkdownTagKind.DirectMessageThread ->
            directMessagePeerName.orEmpty().normalizedThreadMentionLookupKey() in knownDirectMessagePeerNames &&
                !threadShortId.isNullOrBlank()
        ThreadMarkdownTagKind.DirectMessage ->
            directMessagePeerName.orEmpty().normalizedThreadMentionLookupKey() in knownDirectMessagePeerNames
        ThreadMarkdownTagKind.Channel,
        ThreadMarkdownTagKind.ChannelThread -> {
            val key =
                (channelName ?: label.removePrefix("#").substringBefore(":"))
                    .trim()
                    .lowercase()
            key.isNotBlank() && key in knownChannelNames
        }
        // `lounge:@agent[:short]` (slock #16279): a chip only when the name names one of this server's
        // agents by the server's rule; a name that names none (or two) is plain text, never `@agent`.
        ThreadMarkdownTagKind.Lounge,
        ThreadMarkdownTagKind.LoungeThread ->
            loungeAgentName?.let { resolveRaftLoungeRefAgent(it, loungeAgents) } != null
    }

/** No tag context: no tag is known, so a bare `#N` is plain text ([defaultRaftMarkdownTagValidity]). */
internal fun defaultThreadMarkdownTagIsValid(tag: ThreadMarkdownTag): Boolean = defaultRaftMarkdownTagValidity(tag)

internal fun RaftThreadScreenPayload.resolveTaskAssigneeDisplayName(
    value: String?,
    id: String? = null
): String? {
    val normalizedId = id?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
    val normalizedValue = value?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
    val member =
        listOfNotNull(normalizedId, normalizedValue)
            .firstNotNullOfOrNull { candidate ->
                members.firstOrNull { member ->
                    member.id.equals(candidate, ignoreCase = true) ||
                        member.handle.removePrefix("@").equals(candidate, ignoreCase = true) ||
                        member.displayName.equals(candidate, ignoreCase = true)
                }
            }
    return member?.displayName?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
        ?: normalizedValue?.takeIf { it.isDisplaySafeTaskAssigneeName(normalizedId) }
}

internal sealed interface MarkdownTaskNumberOpenResult {
    data class Found(
        val task: TaskSummary,
        val payload: RaftThreadScreenPayload,
        val source: SharedTaskReferenceResolutionSource
    ) : MarkdownTaskNumberOpenResult

    /** The task opens without a page of its own: its empty conversation or its legacy panel. */
    data class OpensInPlace(val task: TaskSummary) : MarkdownTaskNumberOpenResult

    data object NotFound : MarkdownTaskNumberOpenResult
    data object AuthRequired : MarkdownTaskNumberOpenResult
    data object Failed : MarkdownTaskNumberOpenResult
}

internal class MarkdownTaskNumberOpenRequestGate {
    private var generation: Long = 0L

    fun nextRequest(): Long {
        generation += 1L
        return generation
    }

    fun <T> accept(requestGeneration: Long, value: T): T? =
        value.takeIf { requestGeneration == generation }
}

internal suspend fun openMarkdownTaskNumberPayload(
    serverId: ServerId,
    taskNumber: Int,
    currentPayload: RaftThreadScreenPayload,
    currentTaskChannelId: ChannelId?,
    tasksDataSource: SharedTasksDataSource,
    tasksDataStore: SharedTasksDataStore?,
    conversationIdentityProvider: SharedConversationIdentityProvider? = null,
    /** Its network lookup runs in this resolver's shared fetch phase (the chip spinner). */
    referenceResolver: SharedReferenceResolver? = null,
    actionId: String? = null
): MarkdownTaskNumberOpenResult {
    return when (
        val resolution =
            resolveSharedTaskReference(
                serverId = serverId,
                taskNumber = taskNumber,
                currentChannelId = currentTaskChannelId,
                tasksDataSource = tasksDataSource,
                tasksDataStore = tasksDataStore,
                referenceResolver = referenceResolver,
                actionId = actionId
            )
    ) {
        is SharedTaskReferenceResolution.Found -> {
            if (resolution.task.taskOpenSurface() != null) {
                return MarkdownTaskNumberOpenResult.OpensInPlace(resolution.task)
            }
            val resolveConversation: suspend () -> ChannelId? = {
                resolveTaskOpenConversationChannelId(
                    task = resolution.task,
                    serverId = serverId.value,
                    tasksDataSource = tasksDataSource,
                )
            }
            // A joint task's local conversation is a `POST /tasks/:id/conversation`: part of the
            // open's fetch, so the shared spinner covers it; every other target is local.
            val resolvedConversationChannelId =
                if (
                    referenceResolver != null &&
                    resolution.task.taskOpenTarget() == TaskOpenTarget.ResolveConversation
                ) {
                    referenceResolver.fetchPhase(actionId) { resolveConversation() }
                } else {
                    resolveConversation()
                }
            val payload =
                currentPayload.taskThreadLaunchPayload(
                    task = resolution.task,
                    serverId = serverId,
                    conversationIdentityProvider = conversationIdentityProvider,
                    resolvedConversationChannelId = resolvedConversationChannelId,
                ) ?: return MarkdownTaskNumberOpenResult.Failed
            MarkdownTaskNumberOpenResult.Found(
                task = resolution.task,
                payload = payload,
                source = resolution.source
            )
        }
        SharedTaskReferenceResolution.NotFound -> MarkdownTaskNumberOpenResult.NotFound
        SharedTaskReferenceResolution.AuthRequired -> MarkdownTaskNumberOpenResult.AuthRequired
        SharedTaskReferenceResolution.Failed -> MarkdownTaskNumberOpenResult.Failed
    }
}

/**
 * Canonical task-to-thread opener shared by the Tasks panel and Markdown task references.
 *
 * A task row already carries the typed parent/stream identity required by the same
 * [resolveOpenRequest] contract used by Activity. Keep that identity intact instead of
 * reducing the row to a task id and searching only the currently loaded message window.
 */
internal fun RaftThreadScreenPayload.taskThreadLaunchPayload(
    task: TaskSummary,
    serverId: ServerId,
    conversationIdentityProvider: SharedConversationIdentityProvider? = null,
    /** Task v2: the local conversation `POST /tasks/:id/conversation` answered ([taskOpenTarget]). */
    resolvedConversationChannelId: ChannelId? = null,
): RaftThreadScreenPayload? {
    val taskIds = listOfNotNull(task.threadId?.value, task.channelId?.value).filter { it.isNotBlank() }
    val directory =
        taskIds
            .mapNotNull { id -> conversationIdentityProvider?.resolveProjection(serverId, id) }
            .distinctBy { it.canonicalChannelId }
            .toSharedConversationDirectoryState()
    val currentRoute = resolveThreadRouteIdentity()
    val taskParentId = task.channelId?.value?.takeIf { it.isNotBlank() }
    val currentParentKind =
        if (taskParentId != null && taskParentId == currentRoute.timelineChannelId) {
            parseConversationKindOrNull(currentRoute.routeKind)
        } else if (
            taskParentId != null &&
            currentRoute.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
            parentChannelId == taskParentId
        ) {
            currentRoute.parentConversationKind
        } else {
            null
        }
    val launchPayload =
        buildStandaloneTaskThreadLaunchPayload(
            task = task,
            serverId = serverId.value,
            conversationDirectory = directory,
            timeContext =
                RaftTimeContext(
                    localOffsetMinutes = localOffsetMinutes,
                    todayEpochDay = todayEpochDay,
                    nowEpochMillis = nowEpochMillis,
                    timeFormat = timeFormat
                ),
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            parentConversationKind = currentParentKind,
            resolvedConversationChannelId = resolvedConversationChannelId,
        ) ?: return null
    return launchPayload.toInitialPayloadOrNull(launchPayload.instanceId)
}

/**
 * The page offers task writes (the message menu's task items, a task card's status actions, New
 * task in the Tasks tab): its conversation allows them, and the channel is not archived as the
 * page's own load knows it (archive / unarchive flip [RaftThreadScreenPayload.channelArchived] on a
 * loaded page; the server refuses task writes there with 409). Web canWriteTasksInChannel.
 */
internal fun RaftThreadScreenPayload.channelTaskWritesShown(): Boolean =
    showTaskStatusActions && !channelArchived

internal fun RaftThreadScreenPayload.canConvertMessagesToTasks(): Boolean =
    channelTaskWritesShown() &&
        !surfaceKind.equals(RaftThreadSurfaceKind.ReplyThread, ignoreCase = true) &&
        parentMessageId.isNullOrBlank() &&
        originParentMessageId.isNullOrBlank()

/** Task v2: any sent, non-system message can have tasks created from it (several, too). */
internal fun RaftThreadMessageItem.canCreateTaskFromMessage(): Boolean =
    !isSystemMessage() && !hasLocalSendState()

internal fun RaftThreadMessageItem.canConvertToTask(): Boolean =
    !isSystemMessage() &&
        taskId.isNullOrBlank() &&
        taskNumber == null &&
        !hasLocalSendState()

internal fun shouldStartThreadTasksJoin(
    payload: RaftThreadScreenPayload,
    startedThreadId: String?,
    taskChannelId: ChannelId?
): Boolean =
    shouldStartThreadTasksLoad(payload, taskChannelId) &&
        RaftThreadTab.fromWireValue(payload.selectedTab) == RaftThreadTab.Tasks &&
        startedThreadId != taskChannelId?.value

internal fun shouldStartThreadTasksLoad(
    payload: RaftThreadScreenPayload,
    taskChannelId: ChannelId?
): Boolean =
    taskChannelId != null &&
        // A channel with no messages loads as "empty" and still has a Tasks tab to fill.
        (payload.status == "content" || payload.status == "empty") &&
        payload.supportsChannelTasks() &&
        payload.resolvedTaskLookupChannelId()?.let { it == taskChannelId.value } == true

internal fun shouldRefreshThreadTasksProjection(
    payload: RaftThreadScreenPayload,
    taskChannelId: ChannelId?,
    refreshStarted: Boolean
): Boolean =
    shouldStartThreadTasksLoad(payload, taskChannelId) &&
        RaftThreadTab.fromWireValue(payload.selectedTab) == RaftThreadTab.Chat &&
        !refreshStarted

internal fun RaftThreadScreenPayload.resolvedAuxiliaryThreadId(): String? {
    val timelineChannelId = resolveThreadRouteIdentity().timelineChannelId ?: return null
    if (!isReplyThreadWithExplicitParentPair()) return timelineChannelId
    if (status == "loading") return null
    return timelineChannelId
}

internal fun RaftThreadScreenPayload.resolvedTaskLookupChannelId(
    writeActionsEnabled: Boolean = true
): String? {
    if (!writeActionsEnabled) return null
    val timelineChannelId = resolvedAuxiliaryThreadId() ?: return null
    if (!isReplyThreadWithExplicitParentPair()) return timelineChannelId
    return parentChannelId.nonBlank() ?: timelineChannelId
}

/**
 * The channel whose tasks a bare `#N` in this conversation names, for rendering the chip and opening
 * it (Web MessageItem `taskContextChannelId = parentChannelId || message.channelId`). A task
 * conversation's parent is its task's host channel (Web TaskConversationDialog
 * `parentChannelId: task.channelId`), resolved by the conversation header as
 * [taskConversationHostChannelId]; until it resolves there is no context, never the conversation's
 * own channel. Not gated on write access: a read-only reader, or one whose access is still
 * resolving, sees and opens task refs like any reader.
 */
internal fun RaftThreadScreenPayload.markdownTaskContextChannelId(
    taskConversationHostChannelId: String?
): String? =
    if (channelKind == RaftTaskConversationChannelKind) {
        taskConversationHostChannelId.nonBlank()
    } else {
        resolvedTaskLookupChannelId()
    }

/**
 * The conversation an Artifact opened here is opened from (`hostConversation`, botiverse/slock
 * #15103): the channel or DM itself; for a reply thread, its parent channel (the web records an
 * Artifact opened from a thread beside its channel: only a channel's own side card lists them). None
 * for a task conversation: the web's task dialog keeps the plain title and records nothing.
 */
internal fun RaftThreadScreenPayload.artifactOpenConversationId(): String? =
    if (channelKind == RaftTaskConversationChannelKind) {
        null
    } else {
        val route = resolveThreadRouteIdentity()
        if (route.surfaceKind == RaftThreadSurfaceKind.ReplyThread) parentChannelId.nonBlank() else route.timelineChannelId
    }

internal data class ThreadMarkdownPreparsePlan(
    val key: String,
    val priorityCandidates: List<ThreadMarkdownPreparseCandidate>
)

internal data class ThreadMarkdownVisiblePreparsePlan(
    val key: String,
    val candidates: List<ThreadMarkdownPreparseCandidate>,
    val prewarmBeforeItems: Int
)

internal data class ThreadMarkdownPreparseCandidate(
    val id: String,
    val body: String,
    val messageId: String? = id,
    // The scope the message row passes to RaftMarkdownBody, so the rich-text document warmed
    // off-main has the row's exact cache key. Null = parse warm only.
    val inlineContentScope: String? = null,
    val externalMentionSpans: List<ExternalMentionSpan> = emptyList(),
)

private const val ThreadMarkdownPriorityPreparseMessageCount = 4
private const val ThreadMarkdownFocusedPreparseRadius = 25

internal fun ThreadTimelineSlice.threadInitialMarkdownPreparsePlan(
    contentExtrasVisible: Boolean
): ThreadMarkdownPreparsePlan {
    if (!contentExtrasVisible || (messages.isEmpty() && originMessage == null)) {
        return emptyThreadMarkdownPreparsePlan()
    }
    val orderedCandidates = threadMarkdownOrderedPreparseCandidates()
    val priorityCandidates = orderedCandidates.threadMarkdownPriorityPreparseCandidates()
    if (priorityCandidates.none { candidate ->
            candidate.body.preparedRaftMarkdownFullRendererContentOrNull() != null
        }
    ) {
        return emptyThreadMarkdownPreparsePlan()
    }
    return ThreadMarkdownPreparsePlan(
        key =
            priorityCandidates.joinToString(separator = "|") { candidate ->
                "${candidate.id}:${candidate.body.length}:${candidate.body.hashCode()}"
            },
        priorityCandidates = priorityCandidates
    )
}

private fun emptyThreadMarkdownPreparsePlan(): ThreadMarkdownPreparsePlan =
    ThreadMarkdownPreparsePlan(
        key = "",
        priorityCandidates = emptyList()
    )

private fun ThreadTimelineSlice.threadMarkdownOrderedPreparseCandidates(): List<ThreadMarkdownPreparseCandidate> {
    val messageCandidates =
        messages.map { message ->
            ThreadMarkdownPreparseCandidate(
                id = message.threadMessageRenderIdentity(),
                body = message.body,
                messageId = message.id
            )
        }
    val originCandidate =
        originMessage?.let { origin ->
            ThreadMarkdownPreparseCandidate(
                id = "origin:${origin.threadMessageRenderIdentity()}",
                body = origin.body,
                messageId = origin.id
            )
        }
    val focusedId = focusedMessageId?.takeIf { it.isNotBlank() }
    val focusedIndex = focusedId?.let { id -> messages.indexOfFirst { message -> message.id == id } } ?: -1
    val focusedMessageOrder =
        if (focusedIndex >= 0) {
            centeredThreadMessageIndexes(focusedIndex, messages.size, ThreadMarkdownFocusedPreparseRadius)
                .map { index -> messageCandidates[index] }
        } else {
            emptyList()
        }
    val latestMessageOrder = messageCandidates.asReversed()
    val ordered =
        buildList {
            if (focusedId != null && originCandidate?.messageId == focusedId) {
                add(originCandidate)
            }
            addAll(focusedMessageOrder)
            addAll(latestMessageOrder)
            if (originCandidate != null) {
                add(originCandidate)
            }
        }
    return ordered.distinctBy { candidate -> candidate.id }
}

private fun List<ThreadMarkdownPreparseCandidate>.threadMarkdownPriorityPreparseCandidates(): List<ThreadMarkdownPreparseCandidate> {
    val originCandidate = firstOrNull { candidate -> candidate.id.startsWith("origin:") }
    val messageCandidates =
        filterNot { candidate -> candidate.id.startsWith("origin:") }
            .take(ThreadMarkdownPriorityPreparseMessageCount)
    return buildList {
        originCandidate?.let(::add)
        addAll(messageCandidates)
    }.distinctBy { candidate -> candidate.id }
}

private fun centeredThreadMessageIndexes(
    anchorIndex: Int,
    size: Int,
    radius: Int
): List<Int> {
    if (anchorIndex !in 0 until size || radius < 0) return emptyList()
    val indexes = mutableListOf(anchorIndex)
    for (offset in 1..radius) {
        val before = anchorIndex - offset
        if (before >= 0) indexes += before
        val after = anchorIndex + offset
        if (after < size) indexes += after
    }
    return indexes
}

internal fun ThreadTimelineSlice.initialMarkdownPreparsePriorityCandidateIds(
    contentExtrasVisible: Boolean
): List<String> =
    threadInitialMarkdownPreparsePlan(contentExtrasVisible).priorityCandidates.map { it.id }

internal fun ThreadTimelineSlice.initialMarkdownPreparseCandidateIds(
    contentExtrasVisible: Boolean
): List<String> =
    threadInitialMarkdownPreparsePlan(contentExtrasVisible).priorityCandidates.map { it.id }

internal fun ThreadTimelineSlice.visibleMarkdownPreparseCandidateIds(
    firstVisibleItemIndex: Int,
    lastVisibleItemIndex: Int,
    overscanItems: Int,
    prewarmBeforeItems: Int = 0,
    contentExtrasVisible: Boolean
): List<String> =
    threadVisibleMarkdownPreparsePlan(
        firstVisibleItemIndex = firstVisibleItemIndex,
        lastVisibleItemIndex = lastVisibleItemIndex,
        overscanItems = overscanItems,
        prewarmBeforeItems = prewarmBeforeItems,
        contentExtrasVisible = contentExtrasVisible
    ).candidates.map { it.id }

internal fun ThreadTimelineSlice.visibleMarkdownPreparseCandidateSignature(
    firstVisibleItemIndex: Int,
    lastVisibleItemIndex: Int,
    overscanItems: Int,
    prewarmBeforeItems: Int = 0,
    contentExtrasVisible: Boolean
): String =
    threadVisibleMarkdownPreparsePlan(
        firstVisibleItemIndex = firstVisibleItemIndex,
        lastVisibleItemIndex = lastVisibleItemIndex,
        overscanItems = overscanItems,
        prewarmBeforeItems = prewarmBeforeItems,
        contentExtrasVisible = contentExtrasVisible
    ).key

internal fun ThreadTimelineSlice.threadVisibleMarkdownPreparsePlan(
    firstVisibleItemIndex: Int,
    lastVisibleItemIndex: Int,
    overscanItems: Int,
    prewarmBeforeItems: Int = 0,
    contentExtrasVisible: Boolean
): ThreadMarkdownVisiblePreparsePlan {
    if (!contentExtrasVisible ||
        firstVisibleItemIndex < 0 ||
        lastVisibleItemIndex < firstVisibleItemIndex ||
        (messages.isEmpty() && originMessage == null)
    ) {
        return emptyThreadMarkdownVisiblePreparsePlan()
    }

    val expandedFirst =
        (
            firstVisibleItemIndex -
                overscanItems.coerceAtLeast(0) -
                prewarmBeforeItems.coerceAtLeast(0)
        ).coerceAtLeast(0)
    val expandedLast = lastVisibleItemIndex + overscanItems.coerceAtLeast(0)
    val candidates =
        buildList {
            if (hasThreadOriginItem() &&
                originMessage != null &&
                ThreadOriginListItemIndex in expandedFirst..expandedLast
            ) {
                add(
                    ThreadMarkdownPreparseCandidate(
                        id = "origin:${originMessage.threadMessageRenderIdentity()}",
                        body = originMessage.body,
                        messageId = originMessage.id
                    )
                )
            }
            val firstMessageItemIndex = firstMessageListItemIndex()
            val firstMessageIndex = (expandedFirst - firstMessageItemIndex).coerceAtLeast(0)
            val lastMessageIndex = (expandedLast - firstMessageItemIndex).coerceAtMost(messages.lastIndex)
            if (firstMessageIndex <= lastMessageIndex) {
                for (index in firstMessageIndex..lastMessageIndex) {
                    val message = messages[index]
                    add(
                        ThreadMarkdownPreparseCandidate(
                            id = message.threadMessageRenderIdentity(),
                            body = message.body,
                            messageId = message.id,
                            inlineContentScope = message.threadMarkdownRenderSignature().inlineContentScope,
                            externalMentionSpans = if (message.externalAuthor != null) message.externalMentionSpans else emptyList(),
                        )
                    )
                }
            }
        }
            .distinctBy { candidate -> candidate.id }
    if (candidates.isEmpty()) {
        return emptyThreadMarkdownVisiblePreparsePlan()
    }
    return ThreadMarkdownVisiblePreparsePlan(
        key =
            candidates.joinToString(separator = "|") { candidate ->
                "${candidate.id}:${candidate.body.length}:${candidate.body.hashCode()}"
            },
        candidates = candidates,
        prewarmBeforeItems = prewarmBeforeItems.coerceAtLeast(0)
    )
}

internal fun emptyThreadMarkdownVisiblePreparsePlan(): ThreadMarkdownVisiblePreparsePlan =
    ThreadMarkdownVisiblePreparsePlan(key = "", candidates = emptyList(), prewarmBeforeItems = 0)

internal const val ThreadOriginListItemIndex = 0
