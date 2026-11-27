package build.raft.app.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.EmptyRaftMemberAvatarResolver
import build.raft.app.EmptyRaftMemberModelLabelResolver
import build.raft.app.LocalRaftJointActorSurface
import build.raft.app.LocalRaftMemberPreviewCardSource
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftColor
import build.raft.app.RaftMemberAvatarIdentity
import build.raft.app.RaftMemberAvatarResolver
import build.raft.app.RaftMemberModelLabelResolver
import build.raft.app.RaftMemberPreviewCardContent
import build.raft.app.RaftType
import build.raft.app.RaftUserContentLocaleList
import build.raft.app.data.ResolvedActor
import build.raft.app.data.TaskAssignmentNotice
import build.raft.app.data.TaskAssignmentNoticeParty
import build.raft.app.data.TaskAssignmentPartyType
import build.raft.app.data.TaskAssignmentTimelineLine
import build.raft.app.data.TaskHandoffTimeline
import build.raft.app.data.asTaskHandoff
import build.raft.app.data.sameParty
import build.raft.app.data.taskAssignmentNoticeOf
import build.raft.app.data.taskHandoffTimeline
import build.raft.app.explicitAvatarWireValue
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.markdown.RaftRichTextChromeKind
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.markdown.raftInlineDecorations
import build.raft.app.markdown.raftInlineGapInlineContent
import build.raft.app.markdown.raftMarkdownInlineBoxStyle
import build.raft.app.markdown.raftMarkdownInlineContentIds
import build.raft.app.markdown.raftMarkdownReadableSemantics
import build.raft.app.markdown.raftMarkdownRichTextTagFontSizeStyle
import build.raft.app.markdown.raftMarkdownTagSpanStyle
import build.raft.app.markdown.withRaftInlineBox
import build.raft.app.markdown.withRaftInlineChipGaps
import build.raft.app.memberDetailIdentity
import build.raft.app.messages
import build.raft.app.model.MemberKind
import build.raft.app.model.appAvatarWireValueOrNull
import build.raft.app.raftAvatarKind
import build.raft.app.raftMemberAvatarCacheSubject
import build.raft.app.raftMemberAvatarWireValue
import build.raft.app.rememberRaftJointActor
import build.raft.app.rememberRaftMemberAuthorFacts
import build.raft.app.rememberRaftMemberAvatarPending
import build.raft.app.rememberRaftMemberAvatarResolver
import build.raft.app.rememberRaftMemberAvatarWireValue
import build.raft.app.renderedAvatarWireValue
import build.raft.app.resources.*
import build.raft.app.thread.memberDetailIdentity
import build.raft.app.withRaftFontWeight
import build.raft.app.withRaftUserContentLocale
import build.raft.designsystem.components.RaftMessageActionCardDefaults
import build.raft.designsystem.components.RaftPreviewCardState
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/*
 * A task handed to someone (by someone else than its new assignee, with or without a reason), in
 * the task's conversation (web `MessageItem.tsx` `TaskHandoffMessageRow` + `TaskHandoffCard.tsx` +
 * `useTaskPartyIdentity.ts`, botiverse/slock #15631):
 *
 * The hand-off is the message of whoever made it (their avatar in the gutter, their display name
 * and time in the header, never grouped under adjacent messages), and its body is the card:
 *
 *   Assigned to xxchan                      (Reassigned from Bob to xxchan when someone else moved
 *   #51 Ship it                              the task; Assigned back to xxchan when it goes back to
 *   <the reason, when there is one>          whoever handed it to the actor)
 *
 * People are named by display name, not @handle; the viewer, when the task went to them, wears the
 * yellow "you" sticker a self @mention wears. Claims and unassigns stay one-line system notices.
 */

/**
 * Title of each task in the conversation's task context, by task number (web MessageItem
 * `taskByChannelNumber`, botiverse/slock #15631). Empty when unknown; the card shows `#N` alone.
 */
internal val LocalThreadTaskTitlesByNumber = compositionLocalOf<Map<Int, String>> { emptyMap() }

/** The hand-off a system line's projected metadata carries (web `readTaskHandoffNotice`); null for any other line. */
internal fun RaftThreadMessageItem.taskHandoffNotice(): TaskAssignmentNotice? =
    if (isSystemMessage()) taskAssignmentNoticeOf(metadata).asTaskHandoff() else null

/** Web `taskHandoffTimeline` over a conversation's lines, oldest first. */
internal fun threadTaskHandoffTimeline(messages: List<RaftThreadMessageItem>): Map<String, TaskHandoffTimeline> =
    taskHandoffTimeline(
        messages.mapNotNull { message ->
            if (!message.isSystemMessage()) return@mapNotNull null
            val notice = taskAssignmentNoticeOf(message.metadata) ?: return@mapNotNull null
            TaskAssignmentTimelineLine(message.id, notice)
        }
    )

/**
 * How a hand-off's reason draws its refs, @mentions and links: the same handlers as a message body
 * in the conversation list (web `TaskHandoffCard` `reasonMarkdown`, botiverse/slock #15339).
 * Without it (a share image, a thread's origin) the reason stays plain text, as on the web.
 */
internal class ThreadTaskHandoffReasonMarkdown(
    val highlightedMentions: Set<String>,
    val isTagValid: (ThreadMarkdownTag) -> Boolean,
    val resolveTagLabel: ((ThreadMarkdownTag) -> String)?,
    val onOpenTag: (ThreadMarkdownTag, String) -> Unit,
    val onOpenUrl: (String) -> Unit,
)

/** Who a party of an assignment line is, as the timeline shows people (web `useTaskPartyIdentity`). */
private data class TaskHandoffPartyIdentity(
    val name: String,
    val memberKind: MemberKind,
    val avatarResolver: RaftMemberAvatarResolver,
    val jointActor: ResolvedActor?,
)

@Composable
private fun rememberTaskHandoffPartyIdentity(
    party: TaskAssignmentNoticeParty,
    memberAvatarResolver: RaftMemberAvatarResolver,
): TaskHandoffPartyIdentity {
    val serverId = LocalThreadViewerServerId.current
    val resolvedAvatarResolver =
        if (memberAvatarResolver === EmptyRaftMemberAvatarResolver) {
            rememberRaftMemberAvatarResolver(serverId)
        } else {
            memberAvatarResolver
        }
    val memberKind = if (party.type == TaskAssignmentPartyType.Agent) MemberKind.Agent else MemberKind.Human
    val jointSurface = resolvedAvatarResolver.jointSurface || LocalRaftJointActorSurface.current
    val jointActor =
        rememberRaftJointActor(
            memberAvatarResolver = resolvedAvatarResolver,
            kind = memberKind,
            memberId = party.id,
            enabled = jointSurface,
        )
    val names = LocalThreadActionCardNames.current
    val entityKind =
        if (party.type == TaskAssignmentPartyType.Agent) {
            ThreadActionCardEntityKind.Agent
        } else {
            ThreadActionCardEntityKind.Human
        }
    // Display name first (local roster, then joint actor on a joint surface, then the line's own
    // names; never prefixed with @).
    val name =
        names.name(entityKind, party.id)?.takeIf { it.isNotBlank() }
            ?: jointActor?.displayName?.takeIf { it.isNotBlank() }
            ?: jointActor?.name?.takeIf { it.isNotBlank() }
            ?: party.displayName?.takeIf { it.isNotBlank() }
            ?: party.name?.takeIf { it.isNotBlank() }
            ?: ""
    return TaskHandoffPartyIdentity(
        name = name,
        memberKind = memberKind,
        avatarResolver = resolvedAvatarResolver,
        jointActor = jointActor,
    )
}

/**
 * A system line that is a hand-off to someone else, drawn as its actor's message row with the
 * hand-off card as its body (web `MessageItem` `TaskHandoffMessageRow`, #15631); returns false
 * for any other line (claims, unassigns, status lines) so the caller draws the one-line notice.
 */
@Composable
internal fun ThreadTaskHandoffRow(
    message: RaftThreadMessageItem,
    timeline: List<RaftThreadMessageItem>,
    reasonMarkdown: ThreadTaskHandoffReasonMarkdown? = null,
    handoffTimeline: TaskHandoffTimeline? = null,
    messageBodyTextStyle: TextStyle = RaftTheme.typography.body,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    memberModelLabelResolver: RaftMemberModelLabelResolver = EmptyRaftMemberModelLabelResolver,
    onOpenMember: ((MemberDetailIdentity) -> Unit)? = null,
): Boolean {
    val notice = remember(message.metadata, message.messageType) { message.taskHandoffNotice() } ?: return false
    val resolvedTimeline =
        handoffTimeline
            ?: remember(timeline, message.id) { threadTaskHandoffTimeline(timeline)[message.id] }
    val taskTitle = LocalThreadTaskTitlesByNumber.current[notice.taskNumber]
    val serverId = LocalThreadViewerServerId.current
    val shareCaptureActive = LocalThreadShareCaptureActive.current
    val actor = rememberTaskHandoffPartyIdentity(notice.actor, memberAvatarResolver)
    val resolvedAvatar = rememberRaftMemberAvatarWireValue(actor.avatarResolver, notice.actor.id)
    val jointSurface = actor.avatarResolver.jointSurface || LocalRaftJointActorSurface.current
    val explicitAvatar =
        resolvedAvatar?.appAvatarWireValueOrNull()
            ?: actor.jointActor?.renderedAvatarWireValue()
    val avatarPending =
        explicitAvatar == null &&
            rememberRaftMemberAvatarPending(
                memberAvatarResolver = actor.avatarResolver,
                kind = actor.memberKind,
                memberId = notice.actor.id,
                jointSurface = jointSurface,
            )
    val avatarWireValue =
        if (avatarPending) {
            null
        } else {
            raftMemberAvatarWireValue(
                identity = RaftMemberAvatarIdentity(actor.memberKind, notice.actor.id, serverId),
                resolvedAvatarWireValue = resolvedAvatar,
                jointActorAvatarWireValue = actor.jointActor?.explicitAvatarWireValue(),
            )
        }
    val actorFacts = rememberRaftMemberAuthorFacts(memberModelLabelResolver, notice.actor.id)
    val actorOriginServers = actorFacts?.originServers.orEmpty()
    val actorProfileIdentity =
        remember(notice.actor.id, actor.memberKind, actor.jointActor?.homeServerId) {
            memberDetailIdentity(
                memberId = notice.actor.id,
                kind = actor.memberKind,
                sourceServerId = actor.jointActor?.homeServerId,
            )
        }
    val memberPreviewCardSource = LocalRaftMemberPreviewCardSource.current
    val actorPreviewCard: (@Composable ColumnScope.(RaftPreviewCardState) -> Unit)? =
        if (memberPreviewCardSource != null && actorProfileIdentity != null && !shareCaptureActive) {
            { _ ->
                RaftMemberPreviewCardContent(
                    source = memberPreviewCardSource,
                    identity = actorProfileIdentity,
                    fallbackHandle = notice.actor.name,
                )
            }
        } else {
            null
        }
    val canOpenProfile = onOpenMember != null && !shareCaptureActive && actorProfileIdentity != null
    // Message row chrome mirrors ThreadMessageCard: a turn's first row (never grouped under
    // adjacent messages), 35dp avatar gutter, actor display name and timestamp in the header.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.Canvas)
                .padding(top = if (shareCaptureActive) 0.dp else ThreadMessageTimelineTurn)
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = RaftAppSpacing.Sm, vertical = ThreadMessageTimelineRowInset)
                .testTag(TaskHandoffMessageTag),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.testTag(TaskHandoffActorAvatarTag)) {
                ThreadMessageAvatarSlot(
                    interactionIdentity = "task-handoff:${message.id}:${notice.actor.id}",
                    showAvatar = true,
                    showTimestamp = false,
                    compactTimestampGutter = false,
                    timestampLabel = "",
                    timestampAlignment = Alignment.TopStart,
                    timestampTextAlign = TextAlign.Start,
                    avatarLabel = actor.name,
                    avatarKind = raftAvatarKind(actor.memberKind),
                    avatarWireValue = avatarWireValue,
                    avatarPending = avatarPending,
                    cacheSubject = raftMemberAvatarCacheSubject(actor.memberKind, notice.actor.id),
                    status = null,
                    originServers = actorOriginServers,
                    onClick = {
                        if (canOpenProfile) {
                            onOpenMember.invoke(actorProfileIdentity)
                        }
                    },
                    profileActions = canOpenProfile,
                    previewCard = actorPreviewCard,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ThreadMessageHeaderBodyGap),
            ) {
                ThreadMessageHeaderRow(
                    alignEnd = false,
                    modifier = Modifier.fillMaxWidth(),
                    time = { timeModifier ->
                        ThreadMessageTimestampLabel(
                            timestampLabel = message.timestampLabel,
                            modifier = timeModifier,
                        )
                    },
                ) { inCluster, nameText ->
                    Text(
                        text = actor.name,
                        style =
                            RaftType.Button
                                .withRaftFontWeight(FontWeight.Bold)
                                .copy(color = RaftColor.TextBlack, fontSize = 15.sp, lineHeight = 20.sp)
                                .withRaftUserContentLocale(),
                        color = RaftColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = inCluster.then(nameText).testTag(TaskHandoffActorNameTag),
                    )
                }
                ThreadTaskHandoffCard(
                    notice = notice,
                    hasTimeline = resolvedTimeline != null,
                    previous = resolvedTimeline?.previous,
                    back = resolvedTimeline?.back ?: false,
                    taskTitle = taskTitle,
                    messageBodyTextStyle = messageBodyTextStyle,
                    reasonMarkdown = reasonMarkdown,
                    inlineContentScope = "task-handoff-${message.id}",
                    memberAvatarResolver = actor.avatarResolver,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
    return true
}

@Composable
internal fun ThreadTaskHandoffCard(
    notice: TaskAssignmentNotice,
    hasTimeline: Boolean,
    previous: TaskAssignmentNoticeParty?,
    back: Boolean,
    taskTitle: String? = null,
    modifier: Modifier = Modifier,
    messageBodyTextStyle: TextStyle = RaftTheme.typography.body,
    reasonMarkdown: ThreadTaskHandoffReasonMarkdown? = null,
    inlineContentScope: String? = null,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
) {
    val assignee = notice.assignee ?: return
    val viewerUserId = LocalThreadActionCardSpecHost.current.viewerUserId?.trim()?.takeIf { it.isNotEmpty() }
    val heldBy = if (hasTimeline) previous else notice.previousAssignee
    // Someone else's task was moved: whoever held it was not the one moving it.
    val reassignedBySomeoneElse = heldBy != null && !heldBy.sameParty(notice.actor)
    val actor = rememberTaskHandoffPartyIdentity(notice.actor, memberAvatarResolver)
    val from = if (reassignedBySomeoneElse) rememberTaskHandoffPartyIdentity(heldBy, memberAvatarResolver) else null
    val to = rememberTaskHandoffPartyIdentity(assignee, memberAvatarResolver)
    val label =
        when {
            reassignedBySomeoneElse ->
                stringResource(Res.string.task_handoff_reassigned_label, actor.name, notice.taskNumber, from?.name.orEmpty(), to.name)
            back -> stringResource(Res.string.task_handoff_back_label, actor.name, notice.taskNumber, to.name)
            else -> stringResource(Res.string.task_handoff_label, actor.name, notice.taskNumber, to.name)
        }
    val assigneeIsViewer =
        assignee.type == TaskAssignmentPartyType.User && viewerUserId != null && assignee.id == viewerUserId
    val assigneeToken = if (assigneeIsViewer && to.name.isNotEmpty()) TaskHandoffAssigneeToken else to.name
    val cleanFromName = from?.name.orEmpty().replace(TaskHandoffAssigneeToken, "")
    val titleTemplate =
        when {
            reassignedBySomeoneElse ->
                stringResource(Res.string.task_handoff_reassigned_from_to, cleanFromName, assigneeToken)
            back -> stringResource(Res.string.task_handoff_assigned_back_to, assigneeToken)
            else -> stringResource(Res.string.task_handoff_assigned_to, assigneeToken)
        }
    val titleAnnotated =
        remember(titleTemplate, to.name, assigneeIsViewer) {
            taskHandoffTitleAnnotatedString(
                template = titleTemplate,
                assigneeName = to.name,
                highlightAssigneeAsYou = assigneeIsViewer,
            )
        }
    val cleanTaskTitle = taskTitle?.trim()?.takeIf { it.isNotEmpty() }
    val taskLine =
        if (cleanTaskTitle != null) {
            stringResource(Res.string.task_handoff_task, notice.taskNumber, cleanTaskTitle)
        } else {
            stringResource(Res.string.task_handoff_task_number, notice.taskNumber)
        }
    val reason = notice.reason?.takeIf { it.isNotBlank() }
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val shape = RoundedCornerShape(RaftRadius.Container)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = RaftMessageActionCardDefaults.MaxWidth)
                .clip(shape)
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .semantics { contentDescription = label }
                .testTag(TaskHandoffCardTag),
    ) {
        // Without a reason the card is its header alone, spaced as a folded card's is (pb-3 = 12dp).
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 12.dp,
                        top = 12.dp,
                        end = 12.dp,
                        bottom = if (reason != null) 8.dp else 12.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TaskHandoffTitle(
                text = titleAnnotated,
                style = typography.subhead.copy(fontWeight = FontWeight.Bold, localeList = RaftUserContentLocaleList),
            )
            RaftText(
                text = taskLine,
                modifier = Modifier.fillMaxWidth().testTag(TaskHandoffCardTaskTag),
                style =
                    typography.monoMeta.copy(
                        fontWeight = FontWeight.Normal,
                        fontFeatureSettings = "tnum",
                        localeList = RaftUserContentLocaleList,
                    ),
                color = colors.foregroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (reason != null) {
            val reasonStyle = messageBodyTextStyle.copy(localeList = RaftUserContentLocaleList, color = colors.foreground)
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (reasonMarkdown == null) {
                    RaftText(
                        text = reason,
                        modifier = Modifier.testTag(TaskHandoffCardReasonTag),
                        style = reasonStyle,
                        color = colors.foreground,
                    )
                } else {
                    // Refs (#channel, task and thread refs), @mentions and links tap through like a message body's.
                    RaftMarkdownBody(
                        body = reason,
                        modifier = Modifier.fillMaxWidth().testTag(TaskHandoffCardReasonTag),
                        textStyle = reasonStyle,
                        highlightedMentions = reasonMarkdown.highlightedMentions,
                        isTagValid = reasonMarkdown.isTagValid,
                        resolveTagLabel = reasonMarkdown.resolveTagLabel,
                        onOpenTagAction = reasonMarkdown.onOpenTag,
                        onOpenUrl = reasonMarkdown.onOpenUrl,
                        inlineContentScope = inlineContentScope,
                    )
                }
            }
        }
    }
}

private const val TaskHandoffAssigneeToken = "\uE001"

/**
 * Builds the card's title ("Assigned to xxchan", "Assigned back to xxchan", "Reassigned from Bob
 * to xxchan"): when [highlightAssigneeAsYou] is true, the assignee's name (at
 * [TaskHandoffAssigneeToken]) gets the yellow "you" sticker (`RaftRichTextChromeKind.SelfMention`,
 * web `MessageReferenceText variant="primary"`).
 */
internal fun taskHandoffTitleAnnotatedString(
    template: String,
    assigneeName: String,
    highlightAssigneeAsYou: Boolean,
): AnnotatedString {
    val tokenIndex = if (highlightAssigneeAsYou && assigneeName.isNotEmpty()) template.indexOf(TaskHandoffAssigneeToken) else -1
    if (tokenIndex < 0) return AnnotatedString(template)
    val boxStyle = raftMarkdownInlineBoxStyle(RaftRichTextChromeKind.SelfMention)
    val tagStyle = raftMarkdownTagSpanStyle(ThreadMarkdownTagKind.UserMention, highlighted = true)
    val fontSizeStyle = raftMarkdownRichTextTagFontSizeStyle(RaftRichTextChromeKind.SelfMention)
    return buildAnnotatedString {
        append(template.substring(0, tokenIndex))
        withRaftInlineChipGaps(boxStyle) {
            withRaftInlineBox(boxStyle) {
                withStyle(tagStyle) {
                    if (fontSizeStyle != null) {
                        withStyle(fontSizeStyle) { append(assigneeName) }
                    } else {
                        append(assigneeName)
                    }
                }
            }
        }
        append(template.substring(tokenIndex + TaskHandoffAssigneeToken.length))
    }
}

@Composable
private fun TaskHandoffTitle(
    text: AnnotatedString,
    style: TextStyle,
) {
    val decorations = remember(text, style.fontSize) { text.raftInlineDecorations(style.fontSize) }
    val inlineContent = remember(text) { raftInlineGapInlineContent(text.raftMarkdownInlineContentIds()) }
    var textLayout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    RaftText(
        text = text,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { heading() }
                .raftMarkdownReadableSemantics(text)
                .testTag(TaskHandoffCardTitleTag)
                .raftInlineDecorations(decorations) { textLayout },
        style = style,
        color = RaftTheme.colors.foreground,
        inlineContent = inlineContent,
        onTextLayout = { result ->
            if (!decorations.isEmpty) textLayout = result
        },
    )
}

internal const val TaskHandoffMessageTag = "task-handoff-message"
internal const val TaskHandoffActorAvatarTag = "task-handoff-actor-avatar"
internal const val TaskHandoffActorNameTag = "task-handoff-actor-name"
internal const val TaskHandoffCardTag = "task-handoff-card"
internal const val TaskHandoffCardTitleTag = "task-handoff-title"
internal const val TaskHandoffCardTaskTag = "task-handoff-task"
internal const val TaskHandoffCardReasonTag = "task-handoff-reason"
