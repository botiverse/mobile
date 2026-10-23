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
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftPresence
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

/**
 * Ik2: the read mark sits 2dp right of the mention's last stroke, top-aligned with the mention's
 * line, and 10dp are reserved after the mention (the mark's 8dp plus its 2dp gap) before the
 * normal space. Unread draws nothing at all, so no placeholder is appended for it.
 */
private val MentionReadBadgeReserve = 10.dp
private val MentionReadBadgeGap = 2.dp

/** The square's top is 2dp below the text line box's top (not relative to the glyphs). */
private val MentionReadBadgeTop = 2.dp
private val MentionReadCheckSize = 6.5.dp
private const val MentionReadCheckStroke = 3f

/** Appends the read mark placeholder right after a mention (kept on its line by a word joiner); unread appends nothing. */
internal fun AnnotatedString.Builder.appendRaftMarkdownMentionReadBadge(read: Boolean) {
    if (!read) return
    append(RaftMarkdownTaskChipWordJoiner)
    appendInlineContent(MentionReadInlineId, "￼")
}

/** The read mark placeholder; the colours are mode-invariant (online green, ink edge and check). */
internal val RaftMarkdownMentionReadInlineContent: Map<String, InlineTextContent> by lazy {
    mapOf(
        MentionReadInlineId to
            InlineTextContent(
                placeholder =
                    Placeholder(
                        width = MentionReadBadgeReserve.value.sp,
                        height = (RaftStatusSize.Sm.edge + MentionReadBadgeTop).value.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Top,
                    ),
            ) {
                RaftMarkdownMentionReadBadge()
            },
    )
}

/**
 * The read mark: the online status pixel (`sm`, 8dp, 2dp corners, 1.5dp ink edge, online green)
 * with a 6.5dp ink Lucide check (stroke 3 on the 24 grid) centred in it. Labelled "Read".
 */
@Composable
internal fun RaftMarkdownMentionReadBadge() {
    val label = stringResource(MR.strings.thread_mention_agent_read)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Box(Modifier.offset(x = MentionReadBadgeGap, y = MentionReadBadgeTop), contentAlignment = Alignment.Center) {
            RaftStatusDot(size = RaftStatusSize.Sm, presence = RaftPresence.Online, contentDescription = label)
            RaftGlyphIcon(
                glyph = RaftGlyph.Check,
                size = MentionReadCheckSize,
                color = RaftUiPalette.stickerInk,
                strokeWidth = MentionReadCheckStroke,
            )
        }
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
