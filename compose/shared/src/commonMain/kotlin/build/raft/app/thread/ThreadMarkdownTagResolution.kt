package build.raft.app.thread

import build.raft.app.markdown.LocalRaftMarkdownTaskStatuses
import build.raft.app.markdown.RaftLoungeRefPrefix
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.members.LocalRaftMemberPreviewCardSource
import build.raft.app.messages
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.NonRestartableComposable

/** What ThreadPageContentContent resolves for markdown tags / links in its messages. Rebuilt every composition. */
internal class ThreadMarkdownTagResolution(
    val activeMarkdownTagKey: String?,
    val openMarkdownTag: (ThreadMarkdownTag, String) -> Unit,
    val openMarkdownUrl: (String) -> Unit,
    val isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean,
    val markdownTagContextSignature: String,
    val resolveMarkdownTagLabel: (ThreadMarkdownTag) -> String,
)

/**
 * The markdown tag resolution of ThreadPageContentContent: known channel / mention / DM peer / task
 * names, the tag validity check and context signature, the label resolver and the tag / URL open
 * handlers. [NonRestartableComposable] and called unconditionally at the same place as the code it
 * replaces: its `remember`s keep their keys, order and slots, and it runs whenever the page does.
 */
@Composable
@NonRestartableComposable
internal fun threadMarkdownTagResolution(
    payload: RaftThreadScreenPayload,
    timelineSlice: ThreadTimelineSlice,
    linkedTasksProjection: ThreadLinkedTasksProjection,
    highlightedMentions: Set<String>,
    markdownReferencePendingActionId: String?,
    onThreadEvent: ThreadEventHandler,
    /** Lounges are on for this reader (the server's flag, not a Guest): `lounge:@agent…` refs may chip. */
    loungeRefsEnabled: Boolean = false,
): ThreadMarkdownTagResolution {
    val activeMarkdownTagKey = markdownReferencePendingActionId
    val hasPreviewCardSource = LocalRaftMemberPreviewCardSource.current != null
    val openMarkdownTag: (ThreadMarkdownTag, String) -> Unit = openMarkdownTag@{ tag, actionId ->
        if (
            tag.kind != ThreadMarkdownTagKind.UserMention &&
            tag.kind != ThreadMarkdownTagKind.ExternalMention &&
            markdownReferencePendingActionId != null
        ) {
            return@openMarkdownTag
        }
        when (tag.kind) {
            ThreadMarkdownTagKind.UserMention -> {
                onThreadEvent("openMarkdownTag", tag.label, null, tag.kind.name)
            }
            ThreadMarkdownTagKind.ExternalMention -> {
                // When a preview card source is present, tapping the chip pins its
                // RaftExternalIdentityPreviewCardContent popup directly; otherwise fall back
                // to the external identity hint (never open a Raft member profile).
                if (!hasPreviewCardSource) {
                    onThreadEvent(
                        "openExternalMention",
                        tag.externalProjectionId ?: tag.label,
                        null,
                        tag.label,
                    )
                }
            }
            ThreadMarkdownTagKind.Task -> {
                // `#proj:#7` / `dm:@peer:#7` / `lounge:@agent:#7` name the task's conversation; the
                // loaded timeline's own `#7` may be another conversation's task, so they always resolve
                // by conversation.
                val taskId =
                    if (tag.channelName != null || tag.directMessagePeerName != null || tag.loungeAgentName != null) {
                        null
                    } else {
                        timelineSlice.findMarkdownTaskId(tag, linkedTasksProjection)
                    }
                if (!taskId.isNullOrBlank()) {
                    onThreadEvent("openTask", taskId, markdownActionId = actionId)
                } else {
                    onThreadEvent("openMarkdownTaskNumber", tag.label, markdownActionId = actionId)
                }
            }
            ThreadMarkdownTagKind.Channel -> {
                onThreadEvent(
                    "openMarkdownChannel",
                    tag.channelName ?: tag.label,
                    tab = tag.kind.name,
                    markdownActionId = actionId
                )
            }
            ThreadMarkdownTagKind.ChannelThread -> {
                onThreadEvent(
                    "openMarkdownThreadRef",
                    tag.label,
                    tab = tag.threadShortId,
                    markdownActionId = actionId
                )
            }
            ThreadMarkdownTagKind.Message -> {
                onThreadEvent(
                    "openMarkdownMessageRef",
                    tag.label,
                    tab = tag.kind.name,
                    markdownActionId = actionId
                )
            }
            ThreadMarkdownTagKind.DirectMessage -> {
                onThreadEvent(
                    "openMarkdownDirectMessage",
                    tag.directMessagePeerName ?: tag.label,
                    tab = tag.kind.name,
                    markdownActionId = actionId
                )
            }
            ThreadMarkdownTagKind.DirectMessageThread -> {
                onThreadEvent(
                    "openMarkdownDirectMessageThreadRef",
                    tag.directMessagePeerName ?: tag.label,
                    tab = tag.threadShortId,
                    markdownActionId = actionId
                )
            }
            // `lounge:@agent`: the agent's Lounge; `lounge:@agent:<short>`: a thread in it. The item
            // is the conversation (`lounge:@agent`), as a channel ref's is its name.
            ThreadMarkdownTagKind.Lounge -> {
                onThreadEvent(
                    "openMarkdownLounge",
                    tag.loungeAgentName?.let { "$RaftLoungeRefPrefix$it" } ?: tag.label,
                    tab = tag.kind.name,
                    markdownActionId = actionId
                )
            }
            ThreadMarkdownTagKind.LoungeThread -> {
                onThreadEvent(
                    "openMarkdownLoungeThreadRef",
                    tag.loungeAgentName?.let { "$RaftLoungeRefPrefix$it" } ?: tag.label,
                    tab = tag.threadShortId,
                    markdownActionId = actionId
                )
            }
        }
    }
    val openMarkdownUrl: (String) -> Unit = { url ->
        onThreadEvent("openMarkdownUrl", url, null, null)
    }
    val knownMarkdownChannelNames =
        remember(payload.knownMarkdownChannelNames) {
            payload.knownMarkdownChannelNames.toKnownMarkdownChannelNameSet()
        }
    val knownMarkdownMentionNames =
        remember(
            payload.knownMarkdownMentionNames,
            payload.members,
            payload.memberCandidates,
            payload.originMessage,
            payload.messages,
            timelineSlice.messages
        ) {
            payload.toKnownMarkdownMentionNameSet(timelineMessages = timelineSlice.messages)
        }
    val knownMarkdownDirectMessagePeerNames =
        remember(
            payload.knownMarkdownDirectMessagePeerNames,
            payload.dmPeerName,
            payload.dmPeerIdentity,
            payload.surfaceKind,
            payload.parentSurfaceKind,
            payload.channelKind,
            payload.parentChannelKind,
            payload.members,
            payload.memberCandidates
        ) {
            payload.toKnownMarkdownDirectMessagePeerNameSet()
        }
    // The tasks of the conversation being read (Web `taskByChannelNumber` for `taskContextChannelId`):
    // a bare `#N` chips only for one of them.
    val loadedMarkdownTaskStatuses = LocalRaftMarkdownTaskStatuses.current
    val knownMarkdownTaskNumbers =
        remember(payload.knownMarkdownTaskNumbers, loadedMarkdownTaskStatuses) {
            payload.toKnownMarkdownTaskNumberSetOrNull() ?: loadedMarkdownTaskStatuses?.keys
        }
    // The agents a `lounge:@agent…` ref may name; none while Lounges are off for this reader.
    val knownMarkdownLoungeAgents =
        remember(loungeRefsEnabled, payload.members, payload.memberCandidates) {
            if (loungeRefsEnabled) payload.toMarkdownLoungeRefAgents() else emptyList()
        }
    val isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean =
        remember(
            knownMarkdownChannelNames,
            knownMarkdownMentionNames,
            knownMarkdownDirectMessagePeerNames,
            knownMarkdownTaskNumbers,
            knownMarkdownLoungeAgents
        ) {
            { tag ->
                tag.isKnownThreadMarkdownTag(
                    knownMarkdownChannelNames,
                    knownMarkdownMentionNames,
                    knownMarkdownDirectMessagePeerNames,
                    knownMarkdownTaskNumbers,
                    knownMarkdownLoungeAgents
                )
            }
        }
    val markdownTagContextSignature =
        remember(
            highlightedMentions,
            knownMarkdownChannelNames,
            knownMarkdownMentionNames,
            knownMarkdownDirectMessagePeerNames,
            knownMarkdownTaskNumbers,
            knownMarkdownLoungeAgents
        ) {
            threadMarkdownTagContextSignature(
                highlightedMentions = highlightedMentions,
                knownChannelNames = knownMarkdownChannelNames,
                knownMentionNames = knownMarkdownMentionNames,
                knownDirectMessagePeerNames = knownMarkdownDirectMessagePeerNames,
                knownTaskNumbers = knownMarkdownTaskNumbers,
                loungeAgents = knownMarkdownLoungeAgents
            )
        }
    // Keyed on the resolved label index (equality), not on the member lists' identity: a
    // same-content member refresh keeps the lambda, so token plans are not recomputed.
    val markdownMentionLabels =
        remember(payload.members, payload.memberCandidates) {
            threadMarkdownMentionDisplayLabels(payload.members, payload.memberCandidates)
        }
    val resolveMarkdownTagLabel =
        remember(markdownMentionLabels) {
            { tag: ThreadMarkdownTag -> resolveThreadMarkdownTagDisplayLabel(tag, markdownMentionLabels) }
        }
    return ThreadMarkdownTagResolution(
        activeMarkdownTagKey = activeMarkdownTagKey,
        openMarkdownTag = openMarkdownTag,
        openMarkdownUrl = openMarkdownUrl,
        isMarkdownTagValid = isMarkdownTagValid,
        markdownTagContextSignature = markdownTagContextSignature,
        resolveMarkdownTagLabel = resolveMarkdownTagLabel,
    )
}
