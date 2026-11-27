package build.raft.app.thread

import build.raft.app.resources.*
import build.raft.app.sharedTaskStatusChipMeta
import build.raft.app.sharedTaskStatusChipMetaOrDefault
import build.raft.app.shownAuthorName
import build.raft.app.strictTaskStatusOrNull
import build.raft.app.thread.iconName
import build.raft.app.thread.localizedLabel
import build.raft.app.thread.shownAuthorName
import build.raft.app.withRaftFontWeight
import build.raft.app.markdown.markdownToPreviewText
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.RaftIconSpinner
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.app.components.RaftAnchoredMetaChip
import build.raft.app.components.raftLookOverlaySurface
import build.raft.app.composerPersonOrAgentStructuredMention
import build.raft.app.components.raftCardSurface
import build.raft.app.data.map
import build.raft.app.explicitAvatarWireValue
import build.raft.app.fill
import build.raft.app.iconName
import build.raft.app.localizedLabel
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.canonicalServerMembersMentionHandle
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberServerRef
import build.raft.app.markdown.RaftTaskStatusPixel
import build.raft.app.markdown.raftTaskStatusOutline
import build.raft.app.markdown.raftTaskStatusPixelStyle
import build.raft.app.messages
import build.raft.app.data.isChannelAnnouncementNoticeMetadataKey
import build.raft.app.data.isTaskAssignmentNoticeMetadataKey
import build.raft.app.data.isDelegatedReceiptMetadataKey
import build.raft.app.model.TaskStatus
import build.raft.app.network.sync.ThreadRepliesReadModel
import build.raft.app.origin
import build.raft.app.parsedMemberKindOrNull
import build.raft.app.projectedMemberKindOrNull
import build.raft.app.raftArgbHex
import build.raft.app.raftAvatarKind
import build.raft.app.raftFloatingContentClickTarget
import build.raft.app.raftMemberAvatarCacheSubject
import build.raft.app.rememberRaftJointActor
import build.raft.app.rememberRaftMemberAvatarPending
import build.raft.app.rememberRaftMemberAvatarWireValue
import build.raft.app.model.resolveAvatarSlot
import build.raft.app.model.ServerId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.RaftAgentStatusIndicatorState
import build.raft.app.RaftAnchoredPopover
import build.raft.app.RaftAppAvatar
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppStroke
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftBaseMenuItem
import build.raft.app.RaftColor
import build.raft.app.RaftFloatingOverlayClickTriggeredDismissDelayMillis
import build.raft.app.RaftFloatingOverlayOutsidePointerMode
import build.raft.app.RaftFloatingVerticalPlacement
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftMemberAuthorFacts
import build.raft.app.RaftMemberAvatarResolver
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftPanelAction
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftProfileDescriptionText
import build.raft.app.RaftRelativeTimestampText
import build.raft.app.RaftTimestampDisplayGroup
import build.raft.app.RaftType
import build.raft.app.SharedTaskStatusChipMeta
import build.raft.app.SharedThreadComposerDraftRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import build.raft.designsystem.components.raftClickable

/**
 * raft-ui MessageItem `located`: the row fill is `info-soft` at [locatedStrength] (0..1, from
 * `raftLocatedStrength`), the canvas once it is gone.
 */
internal fun threadMessageLocatedFill(locatedStrength: Float): Color {
    val strength = locatedStrength.coerceIn(0f, 1f)
    if (strength <= 0f) return RaftColor.Canvas
    val infoSoft = RaftColor.InfoSoft
    return infoSoft.copy(alpha = infoSoft.alpha * strength)
}

internal fun shouldShowThreadMessageSavedBadge(
    showSaveAction: Boolean,
    richContentEnabled: Boolean,
    hasLocalSendState: Boolean,
    isSaved: Boolean
): Boolean =
    showSaveAction &&
        richContentEnabled &&
        !hasLocalSendState &&
        isSaved

@Composable
internal fun ThreadMessageSelectionToggle(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // web MessageMultiSelectCheckbox: a 20dp raft-ui Checkbox, `self-start mt-1.5`.
    build.raft.designsystem.components.RaftCheckbox(
        checked = checked,
        onCheckedChange = { onClick() },
        modifier = modifier,
        enabled = true
    )
}

@Composable
internal fun ThreadMessageLocalSendStatus(
    message: RaftThreadMessageItem,
    alignEnd: Boolean,
    retryEnabled: Boolean = true,
    onRetry: () -> Unit,
    onCopyDetails: () -> Unit
) {
    val isFailed = message.deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true)
    val userFacingLabels = threadUserFacingErrorLabels()
    val summary = threadSendFailureSummary(message.pendingSendErrorCode, userFacingLabels)
    val label = if (isFailed) {
        stringResource(Res.string.thread_send_failure_summary, summary)
    } else if (message.pendingSendContext?.asTask == true) {
        "Sending as task..."
    } else {
        "Sending..."
    }
    val detail = remember(message.id, message.pendingSendErrorCode, message.pendingSendErrorMessage) {
        threadFailureDiagnosticDetail(message.pendingSendErrorCode, message.pendingSendErrorMessage)
    }
    var detailsExpanded by remember(message.id, detail) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isFailed) {
                RaftLucideIcon(
                    RaftLucideIconName.TriangleAlert,
                    modifier = Modifier.size(13.dp),
                    color = raftArgbHex(RaftColor.DangerStrong)
                )
            } else {
                RaftIconSpinner(size = 13.dp, color = RaftColor.TextMutedGray)
            }
            Text(
                text = label,
                style =
                    RaftType.Caption.copy(
                        color = if (isFailed) RaftColor.DangerStrong else RaftColor.TextMutedGray
                    ),
                color = if (isFailed) RaftColor.DangerStrong else RaftColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (isFailed && detail != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        if (detailsExpanded) Res.string.thread_send_failure_hide_details
                        else Res.string.thread_send_failure_details
                    ),
                    style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
                    color = RaftColor.TextMutedGray,
                    modifier = Modifier.raftClickable(onClick = { detailsExpanded = !detailsExpanded }).padding(vertical = 2.dp)
                )
                if (detailsExpanded) {
                    Text(
                        text = stringResource(Res.string.thread_send_failure_copy_details),
                        style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
                        color = RaftColor.TextMutedGray,
                        modifier = Modifier.raftClickable(onClick = onCopyDetails).padding(vertical = 2.dp)
                    )
                }
            }
        }
        if (detailsExpanded && detail != null) {
            Text(
                text = detail,
                style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 10.sp, lineHeight = 14.sp),
                color = RaftColor.TextMutedGray,
                modifier = Modifier
                    .fillMaxWidth()
                    .raftCardSurface(fill = RaftColor.FillMuted, radius = RaftAppRadius.Control)
                    .padding(8.dp)
            )
        }
        // A dead attachment id fails every resend: no Retry, the line says to upload it again.
        if (isFailed && retryEnabled && !threadSendFailureNeedsAttachmentReupload(message.pendingSendErrorCode)) {
            RaftPanelAction(
                onClick = { if (message.canRetryLocalSend()) onRetry() },
                enabled = message.canRetryLocalSend(),
                modifier =
                    Modifier.heightIn(min = 28.dp)
                        .background(LocalThreadMessageGeometryTints.current?.retryTint ?: Color.Transparent)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    RaftLucideIcon(
                        RaftLucideIconName.RefreshCw,
                        modifier = Modifier.size(12.dp),
                        color = raftArgbHex(RaftColor.DangerStrong)
                    )
                    Text(
                        text = stringResource(Res.string.thread_send_failure_retry),
                        style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
                        color = RaftColor.DangerStrong
                    )
                }
            }
        }
    }
}

/**
 * artin 10-26: ends a message whose body is a cut Activity preview while the full message loads
 * ([RaftThreadMessageItem.activityPreviewTruncated]): the raft-ui Spinner at its smallest size in the
 * muted ink, named for screen readers.
 */
@Composable
internal fun ThreadMessageFullBodyLoadingIndicator(modifier: Modifier = Modifier) {
    RaftSpinner(
        modifier = modifier.padding(top = RaftAppSpacing.Xs),
        size = RaftSpinnerSize.Xs,
        color = RaftColor.TextMutedGray,
        contentDescription = stringResource(Res.string.thread_message_loading_full),
    )
}

/**
 * Whether a message body renders as Markdown (links, mentions, tags, app link chips). A cut Activity
 * preview ([hasActivityCutBody]) never does: it is plain text under its loading or failed note.
 */
internal fun threadMessageRendersRichBody(message: RaftThreadMessageItem, richContentEnabled: Boolean): Boolean =
    richContentEnabled && !message.hasActivityCutBody()

/**
 * Ends a cut Activity preview whose full message failed to load
 * ([RaftThreadMessageItem.activityPreviewLoadFailed]): a muted note, so the cut text never reads as
 * the whole message.
 */
@Composable
internal fun ThreadMessageFullBodyLoadFailedNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.thread_message_load_full_failed),
        style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
        modifier = modifier.padding(top = RaftAppSpacing.Xs),
    )
}

@Composable
internal fun ThreadMessageSendingIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(24.dp)
                .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        // Hidden: the 24dp slot stays, so the delayed spinner never moves the bubble.
        if (visible) {
            RaftIconSpinner(size = 14.dp, color = RaftColor.TextMutedGray)
        }
    }
}

internal data class ThreadAuthorDescriptionPopoverRequest(
    val description: String,
    val anchor: RaftPopoverAnchor
)

internal const val ThreadAuthorDescriptionPopoverMaxWidthDp = 280f
internal const val ThreadAuthorDescriptionPopoverMinWidthDp = 120f
internal const val ThreadAuthorDescriptionPopoverHorizontalPaddingDp = 10f
internal const val ThreadAuthorDescriptionPopoverVerticalPaddingDp = 8f
internal const val ThreadAuthorDescriptionPopoverApproxCharWidthDp = 7.5f
internal const val ThreadAuthorDescriptionPopoverLineHeightDp = 18f // RaftType.Footnote line height

internal data class ThreadAuthorDescriptionPopoverDimensions(
    val width: Dp,
    val height: Dp
)

internal fun threadAuthorDescriptionPopoverDimensions(
    description: String,
    maxWidth: Dp = ThreadAuthorDescriptionPopoverMaxWidthDp.dp
): ThreadAuthorDescriptionPopoverDimensions {
    val clean = description.trim()
    if (clean.isEmpty()) {
        return ThreadAuthorDescriptionPopoverDimensions(
            width = ThreadAuthorDescriptionPopoverMinWidthDp.dp,
            height = 36.dp
        )
    }
    val strokeExtra = RaftAppStroke.Default * 2
    val horizontalPadding = (ThreadAuthorDescriptionPopoverHorizontalPaddingDp * 2).dp
    val verticalPadding = (ThreadAuthorDescriptionPopoverVerticalPaddingDp * 2).dp
    val availableTextWidth = (maxWidth - horizontalPadding - strokeExtra).value.coerceAtLeast(60f)
    val approxCharsPerLine = (availableTextWidth / ThreadAuthorDescriptionPopoverApproxCharWidthDp).toInt().coerceAtLeast(1)
    val estimatedLines = ((clean.length + approxCharsPerLine - 1) / approxCharsPerLine).coerceAtLeast(1)

    val estimatedWidth = if (estimatedLines == 1) {
        val singleLineWidth = (clean.length * ThreadAuthorDescriptionPopoverApproxCharWidthDp).dp + horizontalPadding + strokeExtra
        singleLineWidth.coerceIn(ThreadAuthorDescriptionPopoverMinWidthDp.dp, maxWidth)
    } else {
        maxWidth
    }
    val estimatedHeight = (estimatedLines * ThreadAuthorDescriptionPopoverLineHeightDp).dp + verticalPadding + strokeExtra
    return ThreadAuthorDescriptionPopoverDimensions(
        width = estimatedWidth,
        height = estimatedHeight.coerceIn(36.dp, 320.dp)
    )
}

@Composable
internal fun ThreadAuthorDescriptionPopover(
    request: ThreadAuthorDescriptionPopoverRequest,
    onDismiss: () -> Unit
) {
    RaftAnchoredPopover(
        anchor = request.anchor,
        contentSize = null,
        onDismiss = onDismiss,
        horizontalAutoFlip = true,
        verticalPlacement = RaftFloatingVerticalPlacement.Auto,
        dismissActivationDelayMillis = RaftFloatingOverlayClickTriggeredDismissDelayMillis,
        outsidePointerMode = RaftFloatingOverlayOutsidePointerMode.Dismiss
    ) {
        Box {
            Box(
                modifier =
                    Modifier
                        .widthIn(min = ThreadAuthorDescriptionPopoverMinWidthDp.dp, max = ThreadAuthorDescriptionPopoverMaxWidthDp.dp)
                        .raftLookOverlaySurface()
                        .raftFloatingContentClickTarget()
                        .padding(
                            horizontal = ThreadAuthorDescriptionPopoverHorizontalPaddingDp.dp,
                            vertical = ThreadAuthorDescriptionPopoverVerticalPaddingDp.dp
                        )
            ) {
                RaftProfileDescriptionText(
                    text = request.description,
                    // Web sender subtitle Tooltip: sans text-footnote font-medium (13/18 500).
                    style = RaftType.Footnote.withRaftFontWeight(FontWeight.Medium).copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    source = "author_subtitle_popover",
                    onBeforeOpenArtifact = onDismiss,
                    enableHoverCards = true,
                )
            }
        }
    }
}

internal fun RaftThreadMessageItem.resolvedThreadAvatarWireValue(): String? {
    if (threadAuthorBadgeLabel() != null) return null
    // Avatar slot styling consumes the already-normalized Thread projection. It must not infer a
    // human author from unknown presentation data or from the legacy agent-id heuristic.
    val authorMemberKind = authorKind.projectedMemberKindOrNull() ?: return null
    val resolution = resolveAvatarSlot(
        kind = authorMemberKind,
        id = authorId.ifBlank { authorName },
        avatarWireValue = avatarWireValue
    )
    return resolution.effectiveAvatarWireValue
}

private fun String.isLikelyThreadAgentId(): Boolean =
    startsWith("agent", ignoreCase = true) ||
        startsWith("ai-", ignoreCase = true) ||
        contains("_agent", ignoreCase = true) ||
        contains("-agent", ignoreCase = true)

internal fun RaftThreadMessageItem.threadAuthorBadgeLabel(): String? =
    when {
        isDeletedThreadAuthor() -> "DELETED"
        // web MessageItem `getHumanDepartureLabel`: "Removed", as written (not translated).
        isRemovedThreadHuman() -> "Removed"
        else -> null
    }

internal fun RaftThreadMessageItem.canOpenThreadAuthorProfile(): Boolean =
    threadAuthorBadgeLabel() == null

/**
 * The author row's description (web MessageItem `senderDescriptionText`, botiverse/slock #14629):
 * the directory's agent or member (or joint roster person) first, else the message's own
 * `senderDescription`; whitespace counts as none. An external (bridged) sender keeps its provider.
 * [showDescription] off (Settings > Appearance "Show member descriptions", web #14653): none, for
 * people and agents alike; the provider of a bridged sender is not a description and stays.
 */
/**
 * The servers the author row's corner tile names (web MessageItem `useSenderOrigin`): a directory
 * sender from another server ([RaftMemberAuthorFacts.originServers]); never a bridged (external)
 * sender, whose provider is not a server, nor a system row.
 */
internal fun threadAuthorRowOriginServers(message: RaftThreadMessageItem, facts: RaftMemberAuthorFacts?): List<MemberServerRef> {
    if (message.externalAuthor != null || message.authorKind.equals("system", ignoreCase = true)) return emptyList()
    return facts?.originServers.orEmpty()
}

internal fun threadAuthorRowDescription(
    message: RaftThreadMessageItem,
    facts: RaftMemberAuthorFacts?,
    showDescription: Boolean = true,
): String? {
    val own = message.authorDescription?.trim()?.takeIf { it.isNotEmpty() }
    if (message.externalAuthor != null) return own
    if (!showDescription) return null
    return facts?.description?.trim()?.takeIf { it.isNotEmpty() } ?: own
}

/** Web `isViewerServerGuest` on the author row: a person who is this server's Guest. */
internal fun threadAuthorRowShowsGuest(message: RaftThreadMessageItem, facts: RaftMemberAuthorFacts?): Boolean =
    facts?.viewerServerGuest == true &&
        message.externalAuthor == null &&
        message.authorKind.equals("human", ignoreCase = true)

internal fun RaftThreadMessageItem.canOpenThreadAuthorProfile(shareSelectionActive: Boolean): Boolean =
    !shareSelectionActive && canOpenThreadAuthorProfile()

internal fun resolveThreadAgentStatusIndicatorState(
    message: RaftThreadMessageItem,
    canonicalState: RaftAgentStatusIndicatorState?
): RaftAgentStatusIndicatorState? =
    canonicalState.takeIf { message.showsAgentStatusIndicator() }

internal fun RaftThreadMessageItem.threadMentionHandle(
    serverMembersState: SharedServerMembersState = SharedServerMembersState(),
    serverId: ServerId? = null
): String? {
    if (threadAuthorBadgeLabel() != null) return null
    val cleanAuthorId = authorId.trim().takeIf { it.isNotBlank() }
    return threadMentionHandleFromCanonicalStore(
        serverMembersState.threadMentionAuthorHandle(serverId, cleanAuthorId)
    )
}

internal fun RaftThreadMessageItem.threadMentionHandle(
    serverMembersEntry: SharedServerMembersEntry,
    serverId: ServerId? = null
): String? {
    if (threadAuthorBadgeLabel() != null) return null
    val cleanAuthorId = authorId.trim().takeIf { it.isNotBlank() }
    val rosterHandle =
        serverMembersEntry
            .takeIf { entry -> entry.serverId == serverId?.value?.trim() }
            ?.members
            ?.firstOrNull { member -> member.id.value.trim() == cleanAuthorId }
            ?.let { member -> member.handle.canonicalServerMembersMentionHandle(member.id.value) }
    return threadMentionHandleFromCanonicalStore(rosterHandle)
}

/**
 * "Mention" on a message by a person or agent: the author's `{ type: user | agent, id, name }`, as web
 * `senderMentionInsert` hands the composer. Null for any other author (system, Slack, unknown kind).
 */
internal fun RaftThreadMessageItem.threadAuthorStructuredMention(handle: String): build.raft.app.data.ThreadSendMessageMention? =
    composerPersonOrAgentStructuredMention(
        kind =
            when (authorKind.trim().lowercase()) {
                "human", "user" -> MemberKind.Human
                "agent" -> MemberKind.Agent
                else -> return null
            },
        id = authorId,
        handle = handle,
    )

internal fun RaftThreadMessageItem.threadMentionHandle(canonicalMemberHandle: String?): String? =
    if (threadAuthorBadgeLabel() != null) {
        null
    } else {
        threadMentionHandleFromCanonicalStore(
            canonicalMemberHandle.canonicalServerMembersMentionHandle(authorId)
        )
    }

private fun RaftThreadMessageItem.threadMentionHandleFromCanonicalStore(canonicalHandle: String?): String? {
    canonicalHandle?.let { return it }
    val cleanAuthorId = authorId.trim().takeIf { it.isNotBlank() }
    val explicitAuthorHandle = authorHandle.threadMentionExplicitAuthorHandle(authorName, cleanAuthorId)
    if (explicitAuthorHandle != null) return explicitAuthorHandle
    return null
}

private fun SharedServerMembersState.threadMentionAuthorHandle(
    serverId: ServerId?,
    authorId: String?
): String? {
    val cleanAuthorId = authorId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val cleanServerId = serverId?.value?.trim()?.takeIf { it.isNotBlank() }
    val channelHandle =
        channelMemberSlices.values
            .asSequence()
            .filter { slice -> cleanServerId == null || slice.serverId?.trim() == cleanServerId }
            .flatMap { slice -> slice.members.asSequence() }
            .filter { member -> member.id.value.trim() == cleanAuthorId }
            .mapNotNull { member -> member.handle.canonicalServerMembersMentionHandle(member.id.value) }
            .firstOrNull()
    if (channelHandle != null) return channelHandle
    val entries =
        if (cleanServerId != null) {
            listOfNotNull(entriesByServerId[cleanServerId])
        } else {
            entriesByServerId.values.toList()
        }
    return entries
        .asSequence()
        .flatMap { entry -> entry.members.asSequence() }
        .filter { member -> member.id.value.trim() == cleanAuthorId }
        .mapNotNull { member -> member.handle.canonicalServerMembersMentionHandle(member.id.value) }
        .firstOrNull()
}

private fun String?.threadMentionHandleValue(): String? =
    this
        ?.trim()
        ?.removePrefix("@")
        ?.takeIf { it.isNotBlank() }

private fun String.threadMentionExplicitAuthorHandle(authorName: String, authorId: String?): String? {
    val handle = threadMentionHandleValue() ?: return null
    val presentationName = authorName.threadMentionHandleValue()
    if (presentationName != null && handle == presentationName) return null
    if (handle.any { it.isWhitespace() }) return null
    return handle.canonicalServerMembersMentionHandle(authorId)
}

internal fun RaftThreadMessageItem.isDeletedThreadAuthor(): Boolean =
    !authorDeletedAt.isNullOrBlank() || senderMembershipStatus.equals("deleted", ignoreCase = true)

private fun RaftThreadMessageItem.isRemovedThreadHuman(): Boolean =
    !isThreadAgentAuthor() && senderMembershipStatus.equals("removed", ignoreCase = true)

internal fun RaftThreadMessageItem.isThreadAgentAuthor(): Boolean =
    authorKind.equals("agent", ignoreCase = true) || authorId.isLikelyThreadAgentId()

/** The author's name for the avatar's initials; never the author id (no name shows the icon). */
internal fun RaftThreadMessageItem.threadAvatarName(): String =
    shownAuthorName().trim().takeUnless { it.isEmpty() || it == authorId } ?: avatarLabel

internal fun shouldSuppressCompactThreadAuthor(
    previous: RaftThreadMessageItem?,
    next: RaftThreadMessageItem,
    localOffsetMinutes: Int
): Boolean =
    previous?.isCompactContinuationBefore(next, localOffsetMinutes) == true

/**
 * Whether a row drops its author header (avatar, name, time, model badge) while messages are picked
 * for the share image. Web messageGrouping.ts `shouldShowGroupedMessageHeader`: a selected
 * continuation whose timeline predecessor is not selected cannot lean on that predecessor's header,
 * so it shows its own; a selected continuation right after a selected row of its run stays compact.
 * Two selected messages with an unselected one between them therefore never group. The share image
 * captures the live rows, so this is what puts the author on the image's first message.
 */
internal fun threadShareSelectionSuppressesAuthor(
    timelineSuppressesAuthor: Boolean,
    selectionActive: Boolean,
    selected: Boolean,
    previousSelected: Boolean
): Boolean = timelineSuppressesAuthor && !(selectionActive && selected && !previousSelected)

internal data class ThreadInlineReplyPresentation(
    val replies: List<build.raft.app.network.sync.ReplyPreview>,
    val replyCount: Int,
    val unreadCount: Int,
    val hasDraft: Boolean,
)

/**
 * Header label for the inline-replies surface: ALWAYS the authoritative total, never
 * total-minus-visible previews. The retired "View N earlier replies" branch subtracted the
 * visible rows, which read as if only the undisplayed remainder existed (Web #5486 / cindyz
 * ruling: always show `n replies`, n = total reply count).
 */
internal data class ThreadInlineReplyCountLabel(
    val kind: Kind,
    val count: Int,
) {
    enum class Kind { One, Many }
}

internal fun ThreadInlineReplyPresentation.countLabel(): ThreadInlineReplyCountLabel =
    if (replyCount == 1) {
        ThreadInlineReplyCountLabel(ThreadInlineReplyCountLabel.Kind.One, 1)
    } else {
        ThreadInlineReplyCountLabel(ThreadInlineReplyCountLabel.Kind.Many, replyCount)
    }

internal fun threadInlineReplyPresentation(
    model: ThreadRepliesReadModel?,
    replyMetadata: ThreadReplyMetadata,
    hasDraft: Boolean,
): ThreadInlineReplyPresentation? {
    if (replyMetadata.replyCount <= 0) return null
    val replies =
        model
            ?.replies
            .orEmpty()
            .filterNot { it.senderType.equals("system", ignoreCase = true) }
            .takeLast(build.raft.app.network.sync.InlineThreadReplyPreviewLimit)
    // PR #5157: geometry belongs to actual conversation rows. Total replyCount includes
    // system history only for the label and must never reserve an empty/system-only footprint.
    if (replies.isEmpty()) return null
    val authoritativeReplyCount = maxOf(replyMetadata.replyCount, model?.replyCount ?: 0)
    return ThreadInlineReplyPresentation(
        replies = replies,
        replyCount = authoritativeReplyCount,
        unreadCount = replyMetadata.unreadReplyCount.coerceAtLeast(0),
        hasDraft = hasDraft,
    )
}

internal fun threadInlineReplyPresentationForMessage(
    model: ThreadRepliesReadModel?,
    replyMetadata: ThreadReplyMetadata,
    hasDraft: Boolean,
    showReplyAction: Boolean,
): ThreadInlineReplyPresentation? =
    if (showReplyAction) {
        threadInlineReplyPresentation(model, replyMetadata, hasDraft)
    } else {
        null
    }


internal val LocalThreadInlineReplyTimestampReporter =
    compositionLocalOf<((String, Offset, IntSize) -> Unit)?> { null }


@Composable
internal fun ThreadInlineRepliesSurface(
    presentation: ThreadInlineReplyPresentation,
    participantsById: Map<String, RaftThreadMemberItem>,
    memberAvatarResolver: RaftMemberAvatarResolver,
    onOpenThread: () -> Unit,
) {
    val replyRowHeight = threadInlineReplyRowHeightDp(LocalDensity.current.fontScale).dp
    val countLabelSpec = presentation.countLabel()
    val countLabel =
        when (countLabelSpec.kind) {
            ThreadInlineReplyCountLabel.Kind.One ->
                stringResource(Res.string.thread_inline_reply_count_one)
            ThreadInlineReplyCountLabel.Kind.Many ->
                stringResource(Res.string.thread_inline_reply_count_many, countLabelSpec.count)
        }
    val unreadLabel =
        presentation.unreadCount.takeIf { it > 0 }?.let { count ->
            stringResource(Res.string.thread_inline_new_replies, count)
        }
    val draftLabel = stringResource(Res.string.thread_inline_draft)
    // raft-ui MessageReplies (gap 7): `fill-muted` block, the pink new-reply sticker, a pencil
    // before Draft and no chevron, as the web's InlineThreadReplies draws it.
    build.raft.designsystem.components.RaftMessageReplies(
        countLabel = countLabel,
        onClick = onOpenThread,
        modifier = Modifier.padding(top = build.raft.designsystem.components.RaftMessageRepliesDefaults.TopSpacing),
        newCount = presentation.unreadCount,
        newCountLabel = unreadLabel,
        draftLabel = draftLabel.takeIf { presentation.hasDraft },
    ) {
        presentation.replies.forEach { reply ->
            key("inline-reply:${reply.messageId}") {
                ThreadInlineReplyRow(
                    reply = reply,
                    participant = participantsById[reply.senderId],
                    memberAvatarResolver = memberAvatarResolver,
                    rowHeight = replyRowHeight,
                )
            }
        }
    }
}

@Composable
private fun ThreadInlineReplyRow(
    reply: build.raft.app.network.sync.ReplyPreview,
    participant: RaftThreadMemberItem?,
    memberAvatarResolver: RaftMemberAvatarResolver,
    rowHeight: Dp,
) {
    val timestampPositionReporter = LocalThreadInlineReplyTimestampReporter.current
    val senderKind =
        participant?.kind?.parsedMemberKindOrNull()
            ?: when (reply.senderType.trim().lowercase()) {
                "user", "human" -> MemberKind.Human
                "agent" -> MemberKind.Agent
                else -> null
            }
    val jointSender =
        rememberRaftJointActor(
            memberAvatarResolver = memberAvatarResolver,
            kind = senderKind,
            memberId = reply.senderId,
            enabled = memberAvatarResolver.jointSurface
        )
    val resolvedAvatar =
        threadInlineReplyAvatarWireValue(
            reply = reply,
            memberAvatarWireValue =
                rememberRaftMemberAvatarWireValue(memberAvatarResolver, reply.senderId)
                    ?: jointSender?.explicitAvatarWireValue(),
        )
    val displayName =
        reply.senderDisplayName?.trim()?.takeIf { it.isNotBlank() }
            ?: participant?.displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: reply.senderName.trim().ifBlank { reply.senderId }
    val avatarWireValue =
        resolveThreadRowAvatarWireValue(resolvedAvatar)
    val replyAvatarPending =
        avatarWireValue.isNullOrBlank() &&
            rememberRaftMemberAvatarPending(
                memberAvatarResolver = memberAvatarResolver,
                kind = senderKind,
                memberId = reply.senderId,
                jointSurface = memberAvatarResolver.jointSurface,
            )
    build.raft.designsystem.components.RaftMessageRepliesItem(
        sender = displayName,
        content = threadInlineReplyPreviewText(reply.content),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(rowHeight),
        avatar = {
            RaftAppAvatar(
                kind = raftAvatarKind(senderKind),
                avatarWireValue = avatarWireValue,
                // The name, never the sender id: no name shows the placeholder icon.
                label = if (displayName == reply.senderId) "" else displayName,
                avatarSize = 20.dp,
                cacheSubject = raftMemberAvatarCacheSubject(senderKind, reply.senderId),
                avatarPending = replyAvatarPending,
            )
        },
        time =
            if (reply.createdAt.isNotBlank()) {
                {
                    RaftRelativeTimestampText(
                        value = reply.createdAt,
                        modifier =
                            if (timestampPositionReporter == null) {
                                Modifier
                            } else {
                                Modifier.onGloballyPositioned { coordinates ->
                                    timestampPositionReporter(
                                        reply.messageId,
                                        coordinates.positionInRoot(),
                                        coordinates.size,
                                    )
                                }
                            },
                        displayGroup = RaftTimestampDisplayGroup.Conversation,
                        style = build.raft.designsystem.components.RaftMessageRepliesDefaults.TimeStyle,
                        color = build.raft.designsystem.components.RaftMessageRepliesDefaults.TimeInk,
                        maxLines = 1,
                        toggleEnabled = false,
                    )
                }
            } else {
                null
            },
    )
}

/**
 * A Slack-bridge reply (`external_projection`) has no member profile: its avatar is the frozen one the
 * reply preview carries (web InlineThreadReplies uses `senderAvatarUrl` the same way). Members keep
 * the resolver's live avatar.
 */
internal fun threadInlineReplyAvatarWireValue(
    reply: build.raft.app.network.sync.ReplyPreview,
    memberAvatarWireValue: String?,
): String? =
    if (reply.senderType.trim().equals("external_projection", ignoreCase = true)) {
        reply.senderAvatarUrl?.trim()?.takeIf { it.isNotBlank() }
    } else {
        memberAvatarWireValue
    }

internal fun threadInlineReplyPreviewText(content: String): String = markdownToPreviewText(content)

/**
 * Keeps the original 20dp row at the default scale while preserving its 4dp breathing room when
 * the user's font scale grows. Kuikly's linear SP conversion is `sp * fontScale` in DP space.
 */
internal fun threadInlineReplyRowHeightDp(fontScale: Float): Float {
    val resolvedFontScale = fontScale.coerceAtLeast(1f)
    return maxOf(
        ThreadInlineReplyRowMinHeightDp,
        ThreadInlineReplyContentLineHeightSp * resolvedFontScale + ThreadInlineReplyRowVerticalAllowanceDp,
    )
}

private const val ThreadInlineReplyContentLineHeightSp = 16f
private const val ThreadInlineReplyRowMinHeightDp = 20f
private const val ThreadInlineReplyRowVerticalAllowanceDp = 4f

@Composable
internal fun ThreadMessageFooterMeta(
    message: RaftThreadMessageItem,
    taskOpenEnabled: Boolean,
    taskStatusActionsEnabled: Boolean,
    resolvedTaskAssigneeName: String? = null,
    alignEnd: Boolean,
    onOpenTask: (String) -> Unit,
    onShowActions: (RaftPopoverAnchor) -> Unit,
    onOpenTaskStatusMenu: (RaftPopoverAnchor) -> Unit,
    showSavedBadge: Boolean = false,
    onToggleSave: (() -> Unit)? = null,
    repliesBadge: ThreadRepliesBadgeSpec? = null,
    onOpenReplies: (() -> Unit)? = null,
    showReactionAddAction: Boolean = false,
    onToggleReaction: ((String) -> Unit)? = null,
    showReactionReactors: Boolean = false,
    /**
     * The "+" beside the reactions opens the quick reaction bar at the chip (web
     * `handleMobileReactionAddClick` opens its quick strip there); null when the message can't be
     * quick-reacted to, where it falls back to the message menu.
     */
    onShowQuickReactions: ((RaftPopoverAnchor) -> Unit)? = null
) {
    val pendingReactionVisuals = LocalThreadPendingReactionVisuals.current
    val hasTask = message.hasThreadTaskFooterMeta()
    val visibleReactions =
        threadVisibleReactionsWithPending(
            messageId = message.id,
            reactions = message.reactions,
            pendingVisuals = pendingReactionVisuals
        )
    val hasReactions = visibleReactions.isNotEmpty()
    val showReactionAddChip = showReactionAddAction && hasReactions
    if (!hasTask && !showSavedBadge && repliesBadge == null && !hasReactions && !showReactionAddChip) return

    // raft-ui MessageItemFooter `flex flex-wrap items-center gap-1.5`: what doesn't fit wraps to
    // the next line (6dp under it) instead of squeezing the task chip.
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (alignEnd) Arrangement.spacedBy(6.dp, Alignment.End) else Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (hasTask) {
            // raft-ui TaskChip: the number and the assignee are two labels `gap-1.5` apart.
            val untitledTaskLabel = stringResource(Res.string.drafts_row_task_untitled)
            val labelParts = threadMessageTaskMetaLabelParts(message, resolvedTaskAssigneeName, untitledTaskLabel)
            val selectedStatus = message.taskStatus.threadTaskStatusOption()
            val taskChipClick: ((RaftPopoverAnchor) -> Unit)? =
                when {
                    taskStatusActionsEnabled -> onOpenTaskStatusMenu
                    taskOpenEnabled -> { _ -> message.taskId?.let(onOpenTask) }
                    else -> null
                }
            val taskClickIdentity =
                threadTaskStatusChipClickIdentity(
                    message = message,
                    selectedStatus = selectedStatus,
                    taskStatusActionsEnabled = taskStatusActionsEnabled,
                    taskOpenEnabled = taskOpenEnabled
                )
            // raft-ui `TaskChip` (default): 22dp, rounded-chip, no edge, the status
            // soft fill with the status pixel (`TaskStatusIcon`) and a 12/16 label.
            val statusPixel =
                message.taskStatus.strictTaskStatusOrNull("thread_task_chip", diagnostics = {})?.raftTaskStatusPixelStyle(RaftAppThemeState.colors)
            // Backlog's chip is outlined (`line-hairline`, no tint).
            val statusOutline = selectedStatus.status.raftTaskStatusOutline(RaftAppThemeState.colors)
            RaftAnchoredMetaChip(
                label = labelParts?.first ?: untitledTaskLabel,
                secondaryLabel = labelParts?.second,
                iconName = if (statusPixel == null) selectedStatus.icon else null,
                leadingContent = statusPixel?.let { pixel -> { RaftTaskStatusPixel(pixel) } },
                // `gap-1.5` between the pixel and the number.
                contentSpacing = 6.dp,
                iconSize = selectedStatus.iconSize,
                color = selectedStatus.status.threadTaskChipSoftFill(),
                iconColorArgb = raftArgbHex(selectedStatus.status.threadTaskChipStrong()),
                borderWidth = if (statusOutline != null) 1.dp else 0.dp,
                borderColor = statusOutline,
                minHeight = 22.dp,
                horizontalPadding = 7.dp,
                labelFontSize = 12.sp,
                labelLineHeight = 16.sp,
                // raft-ui TaskChip `max-w-full`: no fixed cap; it truncates only when it alone is
                // wider than the footer (the FlowRow gives it at most the footer's width).
                clickIdentity = taskClickIdentity,
                anchorOwnerIdentity = threadMessageMenuOwnerIdentity(message.id),
                onClick = taskChipClick
            )
        }
        if (showSavedBadge) {
            // raft-ui MessageItemSavedIndicator (gap 23): `fill-muted`, an ink bookmark, muted label.
            // A tap removes the message from Saved, as on the web.
            build.raft.designsystem.components.RaftMessageSavedIndicator(
                label = stringResource(Res.string.thread_label_saved),
                onClick = onToggleSave,
                onClickLabel = stringResource(Res.string.thread_action_remove_from_saved),
            )
        }
        repliesBadge?.let { badge -> ThreadRepliesBadge(badge, onClick = onOpenReplies) }
        if (hasReactions) {
            visibleReactions.forEach { reaction ->
                key("reaction:${message.id}:${reaction.emoji}") {
                    ThreadReactionChip(
                        reaction = reaction,
                        interactionIdentity = "thread_reaction:${message.id}:${reaction.emoji}",
                        onClick = onToggleReaction?.let { callback -> { callback(reaction.emoji) } },
                        showReactors = showReactionReactors
                    )
                }
            }
        }
        if (showReactionAddChip) {
            ThreadReactionAddChip(messageId = message.id, onClick = onShowQuickReactions ?: onShowActions)
        }
    }
}

/**
 * web `ThreadRepliesBadge`: "5 replies · 2 new" (soft information while there are new replies,
 * solid default otherwise), with the thread glyph; a draft adds "· Draft", and a draft alone shows
 * the pencil.
 */
@Composable
private fun ThreadRepliesBadge(badge: ThreadRepliesBadgeSpec, onClick: (() -> Unit)?) {
    val parts = buildList {
        if (badge.replyCount > 0) {
            add(
                if (badge.replyCount == 1) {
                    stringResource(Res.string.thread_inline_reply_count_one)
                } else {
                    stringResource(Res.string.thread_inline_reply_count_many, badge.replyCount)
                }
            )
        }
        if (badge.unreadCount > 0) add(stringResource(Res.string.thread_replies_badge_new_count, badge.unreadCount))
        if (badge.hasDraft) add(stringResource(Res.string.thread_inline_draft))
    }
    val hasUnread = badge.unreadCount > 0
    RaftBadge(
        text = parts.joinToString(" · "),
        appearance = if (hasUnread) RaftBadgeAppearance.Soft else RaftBadgeAppearance.Solid,
        variant = if (hasUnread) RaftBadgeVariant.Information else RaftBadgeVariant.Default,
        icon = {
            RaftSlotIcon(
                if (badge.replyCount > 0) RaftLucideIconName.Thread else RaftLucideIconName.Pencil,
                size = 12.dp,
            )
        },
        onClick = onClick,
    )
}

private fun TaskStatus.threadTaskChipSoftFill(): Color =
    when (this) {
        TaskStatus.Backlog, TaskStatus.Unknown -> Color.Transparent
        TaskStatus.Todo -> RaftAppThemeState.colors.taskStatusTodoSoft
        TaskStatus.InProgress -> RaftAppThemeState.colors.taskStatusInProgressSoft
        TaskStatus.InReview -> RaftAppThemeState.colors.taskStatusInReviewSoft
        TaskStatus.Done -> RaftAppThemeState.colors.taskStatusDoneSoft
        TaskStatus.Closed -> RaftAppThemeState.colors.taskStatusClosedSoft
    }

private fun TaskStatus.threadTaskChipStrong(): Color =
    when (this) {
        TaskStatus.Backlog, TaskStatus.Unknown -> RaftAppThemeState.colors.foreground
        TaskStatus.Todo -> RaftAppThemeState.colors.taskStatusTodoStrong
        TaskStatus.InProgress -> RaftAppThemeState.colors.taskStatusInProgressStrong
        TaskStatus.InReview -> RaftAppThemeState.colors.taskStatusInReviewStrong
        TaskStatus.Done -> RaftAppThemeState.colors.taskStatusDoneStrong
        TaskStatus.Closed -> RaftAppThemeState.colors.taskStatusClosedStrong
    }

internal data class ThreadReactionPendingKey(
    val messageId: String,
    val emoji: String
)

internal fun threadReactionPendingKey(messageId: String, emoji: String): ThreadReactionPendingKey =
    ThreadReactionPendingKey(messageId = messageId, emoji = emoji)

internal data class ThreadReactionPendingVisual(
    val reaction: RaftThreadReactionItem,
    val originalIndex: Int
)

internal fun List<RaftThreadReactionItem>.toPendingReactionVisual(
    emoji: String,
    selected: Boolean
): ThreadReactionPendingVisual {
    val originalIndex = indexOfFirst { reaction -> reaction.emoji == emoji }
    val original = getOrNull(originalIndex)
    val optimistic =
        if (original == null) {
            RaftThreadReactionItem(
                emoji = emoji,
                count = if (selected) 1 else 0,
                selected = selected
            )
        } else {
            original.copy(
                count =
                    when {
                        selected && !original.selected -> original.count + 1
                        !selected && original.selected -> (original.count - 1).coerceAtLeast(0)
                        else -> original.count
                    },
                selected = selected
            )
        }
    return ThreadReactionPendingVisual(
        reaction = optimistic,
        originalIndex = originalIndex.takeIf { it >= 0 } ?: size
    )
}

internal fun threadVisibleReactionsWithPending(
    messageId: String,
    reactions: List<RaftThreadReactionItem>,
    pendingVisuals: Map<ThreadReactionPendingKey, ThreadReactionPendingVisual>
): List<RaftThreadReactionItem> {
    val visible = reactions.filter { reaction -> reaction.count > 0 }.toMutableList()
    pendingVisuals
        .filterKeys { key -> key.messageId == messageId }
        .toList()
        .sortedBy { (_, visual) -> visual.originalIndex }
        .forEach { (key, visual) ->
            val currentIndex = visible.indexOfFirst { reaction -> reaction.emoji == key.emoji }
            if (visual.reaction.count <= 0) {
                if (currentIndex >= 0) visible.removeAt(currentIndex)
            } else if (currentIndex >= 0) {
                visible[currentIndex] = visual.reaction
            } else {
                visible.add(visual.originalIndex.coerceIn(0, visible.size), visual.reaction)
            }
        }
    return visible
}

internal val LocalThreadPendingReactionVisuals =
    compositionLocalOf<Map<ThreadReactionPendingKey, ThreadReactionPendingVisual>> { emptyMap() }

internal fun RaftThreadMessageItem.hasThreadFooterMeta(
    showSavedBadge: Boolean = false,
    hasPendingReaction: Boolean = false,
    showRepliesBadge: Boolean = false
): Boolean =
    hasThreadTaskFooterMeta() ||
        showSavedBadge ||
        showRepliesBadge ||
        hasPendingReaction ||
        reactions.any { it.count > 0 }

/**
 * web `ThreadRepliesBadge`: the footer badge that stands for a thread when no inline reply preview
 * does (MessageItem `shouldShowThreadRepliesBadge`): replies or a reply draft, thread actions on,
 * and no [ThreadInlineReplyPresentation] for the message.
 */
internal data class ThreadRepliesBadgeSpec(
    val replyCount: Int,
    val unreadCount: Int,
    val hasDraft: Boolean,
)

internal fun threadRepliesBadgeSpec(
    replyMetadata: ThreadReplyMetadata,
    hasDraft: Boolean,
    showReplyAction: Boolean,
    hasInlineReplies: Boolean,
): ThreadRepliesBadgeSpec? {
    val replyCount = replyMetadata.replyCount.coerceAtLeast(0)
    if (!showReplyAction || hasInlineReplies || (replyCount == 0 && !hasDraft)) return null
    return ThreadRepliesBadgeSpec(
        replyCount = replyCount,
        unreadCount = replyMetadata.unreadReplyCount.coerceAtLeast(0),
        hasDraft = hasDraft,
    )
}

internal enum class ThreadMessageTrailingSection {
    SourceTasks,
    MetaChips,
    InlineReplies,
}

/**
 * Below the body, in web MessageItem's order: the task list (task v2), then the footer chips
 * (reactions, saved), then the replies, which stay the message's own discussion.
 */
internal fun threadMessageTrailingSectionOrder(
    hasMetaChips: Boolean,
    hasInlineReplies: Boolean,
    hasSourceTasks: Boolean = false,
): List<ThreadMessageTrailingSection> =
    buildList {
        if (hasSourceTasks) add(ThreadMessageTrailingSection.SourceTasks)
        if (hasMetaChips) add(ThreadMessageTrailingSection.MetaChips)
        if (hasInlineReplies) add(ThreadMessageTrailingSection.InlineReplies)
    }

private fun RaftThreadMessageItem.hasThreadTaskFooterMeta(): Boolean =
    !taskId.isNullOrBlank() ||
        taskNumber != null ||
        !taskStatus.isNullOrBlank() ||
        !taskAssigneeName.isNullOrBlank()

internal fun threadMessageTaskMetaLabel(
    message: RaftThreadMessageItem,
    resolvedTaskAssigneeName: String? = null,
    untitledLabel: String,
): String? =
    threadMessageTaskMetaLabelParts(message, resolvedTaskAssigneeName, untitledLabel)?.let { (number, assignee) ->
        if (assignee == null) number else "$number $assignee"
    }

/**
 * The task chip's two labels: the number ("#10", or [untitledLabel], the localized "Task", when the
 * task has none) and the "@assignee", when shown.
 */
internal fun threadMessageTaskMetaLabelParts(
    message: RaftThreadMessageItem,
    resolvedTaskAssigneeName: String? = null,
    untitledLabel: String,
): Pair<String, String?>? {
    val number = message.taskNumber?.let { "#$it" }
    val assignee =
        resolvedTaskAssigneeName
            ?.trim()
            ?.removePrefix("@")
            ?.takeIf { it.isNotBlank() }
    if (number == null && assignee == null && message.taskStatus.isNullOrBlank() && message.taskId.isNullOrBlank()) {
        return null
    }
    return (number ?: untitledLabel) to assignee?.let { "@$it" }
}

internal fun String.isDisplaySafeTaskAssigneeName(assigneeId: String? = null): Boolean {
    val trimmed = trim().removePrefix("@")
    if (trimmed.isBlank()) return false
    if (trimmed.isUuidLike()) return false
    val normalizedId = assigneeId?.trim()?.removePrefix("@")
    return normalizedId == null || !trimmed.equals(normalizedId, ignoreCase = true) || !normalizedId.isUuidLike()
}

private fun String.isUuidLike(): Boolean {
    val trimmed = trim()
    if (trimmed.length != 36) return false
    val dashPositions = setOf(8, 13, 18, 23)
    return trimmed.withIndex().all { (index, char) ->
        if (index in dashPositions) {
            char == '-'
        } else {
            char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        }
    }
}

internal fun Set<SharedThreadComposerDraftRecord>.toReplyComposerDraftParentMessageIds(): Set<String> =
    mapNotNull { record ->
        record.parentMessageId?.takeIf { !record.isRootComposer && it.isNotBlank() }
    }.toSet()

internal fun Set<String>.hasReplyComposerDraft(parentMessageId: String): Boolean =
    parentMessageId in this

internal fun RaftThreadScreenPayload.threadBeginningReplyCountLabel(): String? {
    val replyCount = threadBeginningReplyCount() ?: return null
    return buildString {
        append(replyCount)
        append(if (replyCount == 1) " reply" else " replies")
    }
}

/**
 * The reply count under "Beginning of conversation" (rendered via string resources): the header's
 * count ([threadReplyCountLikeWeb]), so a lagging origin summary (Hands 75dc2b46: an Activity-opened
 * thread said "0 replies" above its loaded replies) never contradicts the replies on screen. Null
 * without an origin (not a reply thread) or while the count is unknown.
 */
internal fun RaftThreadScreenPayload.threadBeginningReplyCount(): Int? {
    val origin = originMessage ?: return null
    return threadReplyCountLikeWeb(
        summaryCount = origin.replyCount,
        loadedCount = messages.count { it.id != origin.id },
        status = status,
        originStatus = originStatus,
        mainLoadFailed = mainLoadFailed
    )
}

/** The task status choices, labelled in the app's language (`tasks_status_*`), keyed by status. */
@Composable
internal fun threadTaskStatusMenuItems(statuses: List<TaskStatus>): List<RaftBaseMenuItem> =
    statuses.map { status ->
        val option = status.sharedTaskStatusChipMeta()
        RaftBaseMenuItem(id = option.key, label = option.status.localizedLabel())
    }

internal fun threadTaskStatusChipClickIdentity(
    message: RaftThreadMessageItem,
    selectedStatus: SharedTaskStatusChipMeta,
    taskStatusActionsEnabled: Boolean,
    taskOpenEnabled: Boolean
): String =
    "thread-task-status:${message.id}:${message.taskId.orEmpty()}:" +
        "${selectedStatus.key}:$taskStatusActionsEnabled:$taskOpenEnabled"

internal fun threadMessageMenuOwnerIdentity(messageId: String): String = "thread_message:$messageId"

internal fun String?.threadTaskStatusOption(): SharedTaskStatusChipMeta =
    sharedTaskStatusChipMetaOrDefault()

internal fun RaftThreadMessageItem.isSystemMessage(): Boolean =
    messageType.equals("System", ignoreCase = true) || messageType.equals("system", ignoreCase = true)

internal data class ThreadReactionToggleTarget(
    val message: RaftThreadMessageItem,
    val nextSelected: Boolean
)

internal fun RaftThreadScreenPayload.threadReactionToggleTarget(
    messageId: String,
    emoji: String
): ThreadReactionToggleTarget? {
    val cleanMessageId = messageId.trim()
    val cleanEmoji = emoji.trim()
    if (cleanMessageId.isBlank() || cleanEmoji.isBlank()) return null
    val candidates =
        (messages + listOfNotNull(originMessage))
            .filter { message -> message.id == cleanMessageId && !message.isSystemMessage() }
    if (candidates.isEmpty()) return null
    val selectedCandidate =
        candidates.firstOrNull { message ->
            message.reactions.any { reaction -> reaction.emoji == cleanEmoji && reaction.selected }
        }
    val message = selectedCandidate ?: candidates.first()
    return ThreadReactionToggleTarget(
        message = message,
        nextSelected = selectedCandidate == null
    )
}

internal fun RaftThreadMessageItem.systemContentSummary(): String =
    body.sanitizeThreadSystemText()

internal fun RaftThreadMessageItem.expandedSystemContent(): String =
    listOf(systemContentSummary(), expandedSystemDetails())
        .filter { it.isNotBlank() }
        .joinToString("\n")

internal fun RaftThreadMessageItem.expandedSystemDetails(): String =
    buildList {
        systemEventType?.takeIf { it.isNotBlank() }?.let { add("Type: ${it.sanitizeThreadSystemText()}") }
        metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank() && !isChannelAnnouncementNoticeMetadataKey(key) && !isTaskAssignmentNoticeMetadataKey(key) && !isDelegatedReceiptMetadataKey(key)) {
                add("${key.sanitizeThreadSystemText()}: ${value.sanitizeThreadSystemText()}")
            }
        }
    }.joinToString("\n")

internal fun String.sanitizeThreadSystemText(): String =
    replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""</p\s*>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .decodeBasicHtmlEntities()
        .collapseThreadInlineWhitespace()
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

private fun String.collapseThreadInlineWhitespace(): String =
    buildString(length) {
        var pendingSpace = false
        for (char in this@collapseThreadInlineWhitespace) {
            if (char.isThreadInlineWhitespace()) {
                pendingSpace = true
            } else {
                if (pendingSpace && isNotEmpty()) {
                    append(' ')
                }
                append(char)
                pendingSpace = false
            }
        }
    }

private fun Char.isThreadInlineWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\u000B' || this == '\u000C' || this == '\r'

private fun String.decodeBasicHtmlEntities(): String =
    replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
