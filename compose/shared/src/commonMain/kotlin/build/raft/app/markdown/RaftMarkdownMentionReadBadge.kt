package build.raft.app.markdown

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import build.raft.app.resources.MR
import build.raft.designsystem.components.appendRaftMessageReferenceReadMark
import build.raft.designsystem.components.raftMessageReferenceReadMarkInlineContent
import build.raft.designsystem.components.raftMessageReferenceSilentReadStateInlineContent
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
 * Appends the read state right after a mention, kept on the mention's line by a word joiner:
 * raft-ui `MessageReferenceReadMark` (raft-design #715), a muted check once the agent has read the
 * message; nothing visible until then, only the words a screen reader says ("Not read yet").
 */
internal fun AnnotatedString.Builder.appendRaftMarkdownMentionReadBadge(read: Boolean) {
    append(RaftMarkdownTaskChipWordJoiner)
    appendRaftMessageReferenceReadMark(if (read) MentionReadInlineId else MentionUnreadInlineId)
}

/** The check (read) and the silent, zero-width state (not read yet). */
internal val RaftMarkdownMentionReadInlineContent: Map<String, InlineTextContent> by lazy {
    mapOf(
        MentionReadInlineId to
            raftMessageReferenceReadMarkInlineContent { mentionReadLabel(read = true) },
        MentionUnreadInlineId to
            raftMessageReferenceSilentReadStateInlineContent { mentionReadLabel(read = false) },
    )
}

/** "Read" / "Not read yet", as the web mention's hover card says it. */
@Composable
private fun mentionReadLabel(read: Boolean): String =
    stringResource(if (read) MR.strings.thread_mention_agent_read else MR.strings.thread_mention_agent_unread)

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
