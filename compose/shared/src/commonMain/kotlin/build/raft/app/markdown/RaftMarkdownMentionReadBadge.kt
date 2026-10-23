package build.raft.app.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.tokens.RaftUiPalette
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * Read state of the agents @mentioned in ONE message body (read_receipts_v0, web task #693
 * `MentionLink` + `messageReadReceiptScope`). [readStateFor] gets a mention's label and answers
 * true (that agent has read the message), false (not yet) or null (unknown: a human, an agent that
 * is not a peer, a summary-only scope, no hydrate) — null renders nothing, never "unread".
 *
 * The message row decides who sees it (only the message's own human sender); markdown only draws.
 */
@Stable
fun interface RaftMarkdownMentionReadStates {
    fun readStateFor(label: String): Boolean?
}

internal val LocalRaftMarkdownMentionReadStates = compositionLocalOf<RaftMarkdownMentionReadStates?> { null }

/** Provides [states] to the markdown bodies in [content]; null removes any outer provider. */
@Composable
internal fun RaftMarkdownMentionReadStateScope(
    states: RaftMarkdownMentionReadStates?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRaftMarkdownMentionReadStates provides states, content = content)
}

private const val MentionReadInlineId = "raft-mention-read:read"
private const val MentionUnreadInlineId = "raft-mention-read:unread"

/**
 * Web `mr-2`: the badge's own width is reserved after the mention so the next word never collides
 * with it; the badge (`-right-1.5 top-0`) then hangs 2dp back over the mention's top-right corner.
 */
private val MentionReadBadgeReserve = 8.dp
private val MentionReadBadgeOverlap = (-2).dp

/** Appends the badge placeholder right after a mention, kept on the mention's line by a word joiner. */
internal fun AnnotatedString.Builder.appendRaftMarkdownMentionReadBadge(read: Boolean) {
    append(RaftMarkdownTaskChipWordJoiner)
    appendInlineContent(if (read) MentionReadInlineId else MentionUnreadInlineId, "￼")
}

/** Both badge placeholders; the colours are mode-invariant (lime / sticker paper, ink edge). */
internal val RaftMarkdownMentionReadInlineContent: Map<String, InlineTextContent> by lazy {
    mapOf(
        MentionReadInlineId to mentionReadBadgeInlineContent(read = true),
        MentionUnreadInlineId to mentionReadBadgeInlineContent(read = false),
    )
}

private fun mentionReadBadgeInlineContent(read: Boolean): InlineTextContent =
    InlineTextContent(
        placeholder =
            Placeholder(
                width = MentionReadBadgeReserve.value.sp,
                height = RaftStatusSize.Sm.edge.value.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextTop,
            ),
    ) {
        RaftMarkdownMentionReadBadge(read)
    }

/**
 * raft-ui status pixel `sm` (8dp, 2dp corners, ink edge): lime once the agent has read the message,
 * a white sticker until then (web `bg-success` / `bg-sticker-paper`). Labelled "Read" / "Not read
 * yet" like the web tooltip.
 */
@Composable
internal fun RaftMarkdownMentionReadBadge(read: Boolean) {
    val label =
        stringResource(if (read) MR.strings.thread_mention_agent_read else MR.strings.thread_mention_agent_unread)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        RaftStatusDot(
            modifier = Modifier.offset(x = MentionReadBadgeOverlap),
            size = RaftStatusSize.Sm,
            color = if (read) RaftUiPalette.success else RaftUiPalette.stickerPaper,
            contentDescription = label,
        )
    }
}

/**
 * Memo revision entries for the badges of one document: the vendor AnnotatedString memo must
 * rebuild when a mentioned agent's read state changes (see annotationRevision in RaftMarkdownBody).
 */
internal fun raftMarkdownMentionReadRevision(
    actions: List<RaftRichTextAction>,
    states: RaftMarkdownMentionReadStates,
): List<String> =
    actions
        .filter { action -> action.refKind == RaftRichTextRefKind.UserMention }
        .map { action ->
            "mentionRead:${action.label}=" +
                when (states.readStateFor(action.label)) {
                    true -> "read"
                    false -> "unread"
                    null -> "unknown"
                }
        }
