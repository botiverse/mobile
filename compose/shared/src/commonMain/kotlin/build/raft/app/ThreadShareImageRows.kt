package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import build.raft.app.data.ThreadActionCard
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSlider
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * Whether each of [selected] (in timeline order) shows its author in the share image: as the
 * conversation groups it ([shouldSuppressCompactThreadAuthor] against the message before it in
 * [timeline]), except that a selected message whose predecessor is not selected always shows its
 * author ([threadShareSelectionSuppressesAuthor], web `shouldShowGroupedMessageHeader`), so the
 * image never starts a run headless. True: drawn as a continuation, without avatar and name.
 */
internal fun threadShareImageSuppressesAuthor(
    timeline: List<RaftThreadMessageItem>,
    selected: List<RaftThreadMessageItem>,
    compactConsecutiveMessages: Boolean,
    localOffsetMinutes: Int,
): List<Boolean> {
    if (!compactConsecutiveMessages) return selected.map { false }
    val selectedIds = selected.mapTo(HashSet()) { it.id }
    val indexById = HashMap<String, Int>(timeline.size)
    timeline.forEachIndexed { index, message -> indexById.putIfAbsent(message.id, index) }
    return selected.map { message ->
        val previous = indexById[message.id]?.let { timeline.getOrNull(it - 1) }
        threadShareSelectionSuppressesAuthor(
            timelineSuppressesAuthor = shouldSuppressCompactThreadAuthor(previous, message, localOffsetMinutes),
            selectionActive = true,
            selected = true,
            previousSelected = previous != null && previous.id in selectedIds,
        )
    }
}

/**
 * One message of the share image, laid out off screen ([ThreadShareImageOffscreenRenderer]): the
 * conversation's row ([ThreadMessageCard]) with nothing interactive, no selection checkbox and
 * no viewer-only content (the renderer provides [LocalThreadShareCaptureActive]). The share image
 * shows what the author wrote, so a translation is not drawn.
 */
@Composable
internal fun ThreadShareImageMessageRow(
    message: RaftThreadMessageItem,
    messageBodyTextStyle: TextStyle,
    compactConsecutiveMessages: Boolean,
    suppressRepeatedAuthor: Boolean,
    timestampLabel: String = message.timestampLabel,
    compactTimestampLabel: String = timestampLabel,
    replyMetadata: ThreadReplyMetadata = message.toThreadReplyMetadata(),
    sourceTasks: List<ThreadMessageSourceTaskItem> = emptyList(),
    actionCard: ThreadActionCard? = message.threadActionCard(),
    actionCardExecutedTimeLabel: String? = null,
    resolvedTaskAssigneeName: String? = null,
    highlightedMentions: Set<String> = emptySet(),
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    memberModelLabelResolver: RaftMemberModelLabelResolver = EmptyRaftMemberModelLabelResolver,
    inlineReplyParticipantsById: Map<String, RaftThreadMemberItem> = emptyMap(),
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    markdownTagContextSignature: String = "",
    alignOwnMessageRight: Boolean = false,
    messageAlignmentModeEnabled: Boolean = alignOwnMessageRight,
    currentServerSlug: String? = null,
    richContentEnabled: Boolean = true,
) {
    ThreadMessageCard(
        message = message,
        messageBodyTextStyle = messageBodyTextStyle,
        replyMetadata = replyMetadata,
        sourceTasks = sourceTasks,
        actionCard = actionCard,
        highlightedMentions = highlightedMentions,
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        memberModelLabelResolver = memberModelLabelResolver,
        inlineReplyParticipantsById = inlineReplyParticipantsById,
        alignOwnMessageRight = alignOwnMessageRight,
        messageAlignmentModeEnabled = messageAlignmentModeEnabled,
        compactConsecutiveMessages = compactConsecutiveMessages,
        suppressRepeatedAuthor = suppressRepeatedAuthor,
        showReplyAction = false,
        showSaveAction = false,
        resolvedTaskAssigneeName = resolvedTaskAssigneeName,
        resolveMarkdownTagLabel = resolveMarkdownTagLabel,
        markdownTagContextSignature = markdownTagContextSignature,
        isMarkdownTagValid = isMarkdownTagValid,
        taskOpenEnabled = false,
        taskStatusActionsEnabled = false,
        writeActionsEnabled = false,
        timestampLabel = timestampLabel,
        compactTimestampLabel = compactTimestampLabel,
        actionCardExecutedTimeLabel = actionCardExecutedTimeLabel,
        currentServerSlug = currentServerSlug,
        richContentEnabled = richContentEnabled,
        onShowActions = {},
        onOpenMember = {},
        onOpenThread = {},
        onOpenTask = {},
        onOpenTaskStatusMenu = {},
        onToggleReaction = {},
        onOpenAttachment = {},
        onOpenMarkdownTag = { _, _ -> },
        onOpenMarkdownUrl = {},
    )
}

/**
 * Share preview's width picker (Android, desktop): the preset segments, the width they make,
 * and for Custom a slider from [ThreadShareImageCustomMinWidthDp] to
 * [ThreadShareImageCustomMaxWidthDp]. [onChoiceChange] fires on every slider move; the caller
 * debounces the re-layout.
 */
@Composable
internal fun ThreadShareImageWidthPicker(
    choice: ThreadShareImageWidthChoice,
    onChoiceChange: (ThreadShareImageWidthChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val label = stringResource(Res.string.thread_share_width_label)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The Progress / Slider header row: what it sets, and the width it makes.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RaftText(
                text = label,
                modifier = Modifier.weight(1f),
                style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                maxLines = 1,
            )
            RaftText(text = choice.widthDp.toString(), style = typography.monoMeta, color = colors.foregroundHint, maxLines = 1)
        }
        RaftSegmentedControl(Modifier.fillMaxWidth()) {
            ThreadShareImageWidth.entries.forEach { preset ->
                RaftSegment(
                    text = stringResource(preset.labelRes()),
                    selected = choice.preset == preset,
                    onClick = { onChoiceChange(choice.select(preset)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (choice.preset == ThreadShareImageWidth.Custom) {
            RaftSlider(
                value = choice.widthDp,
                onValueChange = { onChoiceChange(choice.copy(customWidthDp = it)) },
                valueRange = ThreadShareImageCustomMinWidthDp..ThreadShareImageCustomMaxWidthDp,
                step = ThreadShareImageCustomStepDp,
                contentDescription = label,
                valueText = choice.widthDp.toString(),
            )
        }
    }
}

/** The slider moves in 10dp steps: finer than any difference a reader would see in the image. */
internal const val ThreadShareImageCustomStepDp = 10

private fun ThreadShareImageWidth.labelRes() =
    when (this) {
        ThreadShareImageWidth.Phone -> Res.string.thread_share_width_phone
        ThreadShareImageWidth.Tablet -> Res.string.thread_share_width_tablet
        ThreadShareImageWidth.Wide -> Res.string.thread_share_width_wide
        ThreadShareImageWidth.Custom -> Res.string.thread_share_width_custom
    }

/**
 * Share preview's image (Android, desktop): [image] across the preview's width, scrolling when
 * tall, under a spinner while a new layout is [rendering]. Before the first image, a spinner
 * with the "laying out" line.
 */
@Composable
internal fun ThreadShareImagePreview(
    image: ImageBitmap?,
    rendering: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    RaftCard(modifier.fillMaxWidth(), variant = RaftCardVariant.Inset) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (image == null) {
            Column(
                Modifier.fillMaxWidth().height(ThreadShareImagePreviewEmptyHeight),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val text = stringResource(Res.string.thread_share_rendering)
                RaftSpinner(size = RaftSpinnerSize.Sm, contentDescription = text)
                RaftText(text = text, style = RaftTheme.typography.caption, color = colors.foregroundHint)
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = ThreadShareImagePreviewMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(Res.string.thread_share_image_description),
                    modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1)),
                    contentScale = ContentScale.FillWidth,
                    filterQuality = FilterQuality.High,
                )
            }
            if (rendering) {
                RaftSpinner(size = RaftSpinnerSize.Sm, contentDescription = stringResource(Res.string.thread_share_rendering))
            }
        }
    }
    }
}

private val ThreadShareImagePreviewEmptyHeight = 220.dp
private val ThreadShareImagePreviewMaxHeight = 420.dp
