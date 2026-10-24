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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftMessageReferenceReadMark
import build.raft.designsystem.components.RaftMessageReferenceReadMarkDefaults
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
 * raft-design #794: the read mark is a bare 10dp check. Its box's left edge sits 2dp right of the
 * mention's last glyph and its top aligns with the top of the name (cap height); 10dp are reserved
 * after the mention, so the box's last 2dp fall into the following space. Unread draws nothing at
 * all, so no placeholder is appended for it. The line height is unchanged.
 */
private val MentionReadBadgeReserve = RaftMessageReferenceReadMarkDefaults.Reserve
private val MentionReadBadgeGap = RaftMessageReferenceReadMarkDefaults.GapAfterName

/** Space Grotesk's OS/2 sCapHeight is 700 / 1000 em: the name's top above the baseline. */
private const val SansCapHeightEm = 0.7f

/** Distance from the baseline up to the mark's top (the name's top) for text of [fontSize] (sp ~ dp at the default scale). */
internal fun raftMarkdownMentionReadMarkRiseSp(fontSize: TextUnit): Float =
    fontSize.value * SansCapHeightEm

/** Appends the read mark placeholder right after a mention (kept on its line by a word joiner); unread appends nothing. */
internal fun AnnotatedString.Builder.appendRaftMarkdownMentionReadBadge(read: Boolean) {
    if (!read) return
    append(RaftMarkdownTaskChipWordJoiner)
    appendInlineContent(MentionReadInlineId, "￼")
}

/** The read mark placeholder; the colours are mode-invariant (online green, ink edge and check). */
internal fun raftMarkdownMentionReadInlineContent(fontSize: TextUnit): Map<String, InlineTextContent> =
    mapOf(
        MentionReadInlineId to
            InlineTextContent(
                placeholder =
                    Placeholder(
                        width = MentionReadBadgeReserve.value.sp,
                        height = raftMarkdownMentionReadMarkRiseSp(fontSize).sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
                    ),
            ) {
                RaftMarkdownMentionReadBadge()
            },
    )

/**
 * The read mark: a bare check ([RaftMessageReferenceReadMark]), hidden from screen readers; the
 * mention carries the text "Read" instead (web: an sr-only span inside the mention).
 */
@Composable
internal fun RaftMarkdownMentionReadBadge() {
    val label = stringResource(MR.strings.thread_mention_agent_read)
    Box(
        Modifier.fillMaxSize().semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.TopStart,
    ) {
        RaftMessageReferenceReadMark(Modifier.offset(x = MentionReadBadgeGap).clearAndSetSemantics {})
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
