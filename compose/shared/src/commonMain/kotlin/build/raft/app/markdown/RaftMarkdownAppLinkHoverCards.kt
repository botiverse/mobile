package build.raft.app.markdown

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
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
import build.raft.app.shell.LocalRaftLinkNavigator
import build.raft.app.thread.RaftAppLinkCardView
import build.raft.app.thread.chipCardButtons
import build.raft.app.thread.raftAppLinkCardOpenButton
import build.raft.app.thread.raftAppLinkOpenLinkButton
import build.raft.app.raftLogInfo
import build.raft.app.shell.rememberRaftAppLinkCardLeavesRaft
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
        val hoverHost = host.hoverCardHost
        spans.forEachIndexed { index, span ->
            val state = states[index]
            if (!state.isTriggerHovered && !state.isOpen) return@forEachIndexed
            val card = (host.results[span.url] as? AppLinkResult.Card)?.takeIf { it.hasCardBody } ?: return@forEachIndexed
            key(span) {
                // Register the row-scoped press as soon as hover begins (during the open delay),
                // so App v2 action & Refresh buttons are live on the popup's first frame and
                // confirmations / Undo windows survive if the pointer leaves the hover card.
                LaunchedEffect(hoverHost, card.url) {
                    hoverHost?.ensurePressed(card.url)
                }
                // The item card is a popover card of its own (RaftAppLinkCardView draws its surface).
                // An item card: 400 wide (raft-ui `PreviewCardContent size="item"`, raft-design #939).
                RaftPreviewCardPopup(state = state, anchor = anchors[index], width = RaftPreviewCardDefaults.ItemWidth, surface = false) {
                    val hoverToken = remember { Any() }
                    DisposableEffect(hoverHost, hoverToken) {
                        hoverHost?.setHovered(hoverToken, true)
                        onDispose { hoverHost?.setHovered(hoverToken, false) }
                    }
                    val confirming = hoverHost?.hasConfirmation(card.url) == true
                    LaunchedEffect(confirming) {
                        if (confirming) state.dismiss()
                    }
                    val openAddress: (String) -> Unit = { address ->
                        state.dismiss()
                        if (hoverHost != null) {
                            hoverHost.openAddress(card, address)
                        } else {
                            uriHandler.openUri(address)
                        }
                    }
                    // Also observes installedAppRevisionFlow() and railPlacesStateFlow() while the
                    // hover popup is open, so leavesRaft marks stay current for every button URL.
                    val openLinkLeavesRaft = rememberRaftAppLinkCardLeavesRaft(cardUrl = card.url)
                    val openLink =
                        if (card.connectUrl != null) {
                            raftAppLinkCardOpenButton(card, leavesRaft = openLinkLeavesRaft) { openAddress(it) }
                        } else {
                            raftAppLinkOpenLinkButton(leavesRaft = openLinkLeavesRaft) { openAddress(card.url) }
                        }
                    val linkNavigator = LocalRaftLinkNavigator.current
                    val buttons =
                        if (hoverHost != null) {
                            hoverHost.buttons(card = card, openUri = openAddress, openLink = openLink)
                        } else {
                            linkNavigator?.installedAppRevisionFlow()?.collectAsState()?.value
                            linkNavigator?.railPlacesStateFlow()?.collectAsState()?.value
                            card.chipCardButtons(
                                openUri = openAddress,
                                openLink = openLink,
                                leavesRaftForUrl = { targetUrl -> linkNavigator?.canOpenAppLinkCardInRaft(card.url, targetUrl) != true },
                            )
                        }
                    RaftAppLinkCardView(
                        card = card,
                        onAction = { elementId -> raftLogInfo(RaftMarkdownAppLinkHoverLogTag, "app_link_card_action_ignored element=$elementId") },
                        showFooterIcon = false,
                        buttons = buttons,
                    )
                }
            }
        }
    }
}

private const val RaftMarkdownAppLinkHoverLogTag = "RaftAppLinkCard"

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
