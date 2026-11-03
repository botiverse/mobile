package build.raft.app.markdown

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import build.raft.app.RaftAppLinkCardView
import build.raft.app.raftAppLinkOpenLinkButton
import build.raft.app.data.AppLinkResult
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardPopup
import build.raft.designsystem.components.RaftPreviewCardState
import build.raft.designsystem.components.rememberRaftPreviewCardState

/** One App link card chip in rendered text: its characters [start] until [end], and its [url]. */
internal data class RaftMarkdownAppLinkSpan(val start: Int, val end: Int, val url: String)

internal fun AnnotatedString.raftMarkdownAppLinkSpans(): List<RaftMarkdownAppLinkSpan> =
    getStringAnnotations(RaftAppLinkHoverTag, 0, length)
        .filter { it.end > it.start }
        .map { RaftMarkdownAppLinkSpan(it.start, it.end, it.item) }
        .distinct()
        .sortedBy { it.start }

/**
 * The App link card chips of one rendered text and a [RaftPreviewCardState] each (web
 * `AppLinkHoverCard`, artin 2026-11-02): a mouse or trackpad pointer resting on a chip shows its
 * item card beside it, the same card a tap opens. Fed by real hover events only
 * ([raftMarkdownAppLinkHover]); a finger keeps the tap.
 */
@Stable
internal class RaftMarkdownAppLinkHoverCards(
    val spans: List<RaftMarkdownAppLinkSpan>,
    val states: List<RaftPreviewCardState>,
) {
    /** The text's last layout; set from its onTextLayout. */
    var layout: TextLayoutResult? = null

    private val anchors = arrayOfNulls<Rect>(spans.size)

    fun hover(position: Offset?) {
        val layout = layout
        val hit =
            if (position == null || layout == null) {
                null
            } else {
                val caret = layout.getOffsetForPosition(position)
                val textLength = layout.layoutInput.text.length
                intArrayOf(caret, caret - 1)
                    .filter { it in 0 until textLength }
                    .firstNotNullOfOrNull { offset ->
                        spans.indexOfFirst { offset >= it.start && offset < it.end }
                            .takeIf { it >= 0 && layout.getBoundingBox(offset).contains(position) }
                    }
            }
        states.forEachIndexed { index, state ->
            val over = index == hit
            if (over && !state.isTriggerHovered && layout != null) {
                val span = spans[index]
                anchors[index] = layout.getPathForRange(span.start, span.end).getBounds()
            }
            if (state.isTriggerHovered != over) state.setTriggerHovered(over)
        }
    }

    /** A click or a wheel scroll on the text: the hover card under the pointer closes (the click still runs). */
    fun press() {
        states.forEach { if (it.isTriggerHovered) it.dismiss() }
    }

    @Composable
    fun Popups(host: RaftAppLinkChipHost) {
        val uriHandler = LocalUriHandler.current
        spans.forEachIndexed { index, span ->
            val state = states[index]
            if (!state.isTriggerHovered && !state.isOpen) return@forEachIndexed
            val card = (host.results[span.url] as? AppLinkResult.Card)?.takeIf { it.card != null } ?: return@forEachIndexed
            key(span) {
                // The item card is a popover card of its own (RaftAppLinkCardView draws its surface).
                // An item card: 400 wide (raft-ui `PreviewCardContent size="item"`, raft-design #939).
                RaftPreviewCardPopup(state = state, anchor = anchors[index], width = RaftPreviewCardDefaults.ItemWidth, surface = false) {
                    RaftAppLinkCardView(
                        card = card,
                        onAction = {},
                        buttons =
                            listOf(
                                raftAppLinkOpenLinkButton {
                                    state.dismiss()
                                    uriHandler.openUri(card.url)
                                },
                            ),
                    )
                }
            }
        }
    }
}

/** The hover cards of the App link card chips in [text]; null without a chip host or a card chip. */
@Composable
internal fun rememberRaftMarkdownAppLinkHoverCards(text: AnnotatedString): RaftMarkdownAppLinkHoverCards? {
    LocalRaftAppLinkChips.current ?: return null
    val spans = remember(text) { text.raftMarkdownAppLinkSpans() }
    if (spans.isEmpty()) return null
    val states = spans.map { span -> key(span) { rememberRaftPreviewCardState() } }
    return remember(spans, states) { RaftMarkdownAppLinkHoverCards(spans, states) }
}

/** Feeds [cards] from a mouse or trackpad pointer over the text; consumes nothing. */
internal fun Modifier.raftMarkdownAppLinkHover(cards: RaftMarkdownAppLinkHoverCards): Modifier =
    pointerInput(cards) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != androidx.compose.ui.input.pointer.PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move -> cards.hover(change.position)
                    PointerEventType.Exit -> cards.hover(null)
                    PointerEventType.Press, PointerEventType.Scroll -> cards.press()
                }
            }
        }
    }
