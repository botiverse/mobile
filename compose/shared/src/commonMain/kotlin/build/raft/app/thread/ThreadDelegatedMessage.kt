package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import build.raft.app.model.MessageDelegatedBy
import org.jetbrains.compose.resources.StringResource

// RFC 077 (assistant launch, task #6): the words for a message a person's assistant sent for
// them. Web source of truth: botiverse/slock `packages/web/src/components/message/
// delegatedMessage.ts` + `MessageItem.tsx` `DelegatedByLabel` + `QuoteCardContent.tsx`, catalog
// keys `message.delegated.*`. Message rows put the words after the sender ("sent by Friday")
// or, on a continuation row, at the end of the body ("(sent by Friday)"); quotes use the
// in-body words; one-line previews (Activity, search) put them next to the sender, as text only.

/** Where the "sent by" words sit: the row header, or the end of a continuation row / a quote. */
internal enum class DelegatedLabelPlacement { Header, InBody }

/** The catalog entry for [delegatedBy] at [placement] (web `DelegatedByLabel` `inBody`). */
internal fun delegatedLabelResource(delegatedBy: MessageDelegatedBy, placement: DelegatedLabelPlacement): StringResource =
    when {
        delegatedBy.deleted && placement == DelegatedLabelPlacement.Header -> Res.string.message_delegated_sent_by_deleted
        delegatedBy.deleted -> Res.string.message_delegated_sent_by_deleted_in_body
        placement == DelegatedLabelPlacement.Header -> Res.string.message_delegated_sent_by
        else -> Res.string.message_delegated_sent_by_in_body
    }

/** Web `formatDelegatedSender`: the sender with the assistant, or the sender alone. */
internal fun delegatedPreviewSenderResource(delegatedBy: MessageDelegatedBy?): StringResource? =
    when {
        delegatedBy == null -> null
        delegatedBy.deleted -> Res.string.message_delegated_preview_sender_deleted
        else -> Res.string.message_delegated_preview_sender
    }

/**
 * The words around the assistant's name in a localized "sent by {name}" (zh "由 {name} 代发",
 * ja "{name} が代理送信"), so the name alone can truncate (raft-ui `MessageItemDelegatedBy`:
 * never the words). [formatted] is the template filled with [marker]; no marker leaves all of it
 * in the prefix.
 */
internal data class DelegatedLabelParts(val prefix: String, val suffix: String)

internal fun splitDelegatedLabel(formatted: String, marker: String): DelegatedLabelParts {
    val at = formatted.indexOf(marker)
    if (at < 0) return DelegatedLabelParts(formatted, "")
    return DelegatedLabelParts(formatted.substring(0, at), formatted.substring(at + marker.length))
}

// A private-use character, never in a catalog string (a NUL could cut a native format short).
private const val DelegatedNameMarker = "\uE000"

/** The full "sent by" words for [delegatedBy] at [placement], localized. */
@Composable
internal fun delegatedLabelText(delegatedBy: MessageDelegatedBy, placement: DelegatedLabelPlacement): String {
    val resource = delegatedLabelResource(delegatedBy, placement)
    return if (delegatedBy.deleted) stringResource(resource) else stringResource(resource, delegatedBy.name)
}

/** Web `formatDelegatedSender` for Activity rows and search hits: "Alice (sent by Friday)". */
@Composable
internal fun delegatedPreviewSender(sender: String, delegatedBy: MessageDelegatedBy?): String {
    val resource = delegatedPreviewSenderResource(delegatedBy) ?: return sender
    return if (delegatedBy?.deleted == true) {
        stringResource(resource, sender)
    } else {
        stringResource(resource, sender, delegatedBy?.name.orEmpty())
    }
}

/**
 * raft-ui `MessageItemDelegatedBy` (`text-caption text-foreground-muted`): "sent by Friday"
 * after the sender in a message row's header, or "(sent by Friday)" ending a continuation row.
 * Only the name truncates; a deleted assistant's words have no name and never truncate.
 */
@Composable
internal fun ThreadMessageDelegatedByLabel(
    delegatedBy: MessageDelegatedBy,
    placement: DelegatedLabelPlacement,
    modifier: Modifier = Modifier,
) {
    val style = threadDelegatedLabelStyle()
    if (delegatedBy.deleted) {
        Text(
            text = delegatedLabelText(delegatedBy, placement),
            style = style,
            color = style.color,
            maxLines = 1,
            modifier = modifier,
        )
        return
    }
    val parts =
        splitDelegatedLabel(
            stringResource(delegatedLabelResource(delegatedBy, placement), DelegatedNameMarker),
            DelegatedNameMarker,
        )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (parts.prefix.isNotEmpty()) Text(parts.prefix, style = style, color = style.color, maxLines = 1, softWrap = false)
        Text(
            text = delegatedBy.name,
            style = style.withRaftUserContentLocale(),
            color = style.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (parts.suffix.isNotEmpty()) Text(parts.suffix, style = style, color = style.color, maxLines = 1, softWrap = false)
    }
}

private fun threadDelegatedLabelStyle(): TextStyle =
    RaftType.Caption.copy(fontWeight = FontWeight.Normal, color = RaftColor.ForegroundMuted)

/**
 * RFC 077 PR 2b, web MessageItem `message-retracted-*` (`text-foreground-hint`): a retracted
 * message keeps its place in the timeline; its body is the localized retracted line in the
 * message body type, never the server's English placeholder or the old words. Not selectable.
 */
@Composable
internal fun ThreadMessageRetractedBody(
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(Res.string.message_delegated_retracted),
        style = textStyle,
        color = RaftColor.ForegroundHint,
        modifier = modifier,
    )
}
