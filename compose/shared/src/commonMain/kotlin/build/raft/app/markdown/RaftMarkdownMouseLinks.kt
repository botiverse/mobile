package build.raft.app.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult

/*
 * A mouse press that starts on a link and drags selects text, as in a browser; a click (no drag
 * past the touch slop) still opens the link (artin, 2026-11-02).
 *
 * Compose runs every LinkAnnotation through a clickable box laid over the link's words
 * (foundation TextLinkScope). That box consumes the press before the text's selection gesture sees
 * it, and the selection gesture only starts on an unconsumed press, so a drag from a link selected
 * nothing. Inside a selectable message body, once a mouse has been seen there, a text's styled
 * links are painted as plain spans instead (so no box takes the press) and
 * [raftMarkdownMouseLinks] opens them on a click. A finger never hovers, so a touch-only body keeps
 * the framework links (tap opens, long-press selects) unchanged.
 */

/** One per selectable message body: whether a mouse (or trackpad) has been over it. */
@Stable
internal class RaftMarkdownMouseLinkBody {
    var mouseSeen by mutableStateOf(false)
}

/** Provided by the in-place selectable message body; null elsewhere (links stay framework links). */
internal val LocalRaftMarkdownMouseLinkBody = compositionLocalOf<RaftMarkdownMouseLinkBody?> { null }

/** The pointer state of one text's links under [RaftMarkdownMouseLinkBody]. */
@Stable
internal class RaftMarkdownMouseLinkState(
    val links: List<AnnotatedString.Range<LinkAnnotation>>,
    private val body: RaftMarkdownMouseLinkBody,
) {
    /** The rendered text's layout (same offsets as the links). */
    var layout: TextLayoutResult? = null

    /** True once the composed text carries plain spans in place of [links]. */
    var applied: Boolean = false

    var hovered by mutableStateOf<IntRange?>(null)
    var pressed by mutableStateOf<IntRange?>(null)

    val active: Boolean get() = body.mouseSeen

    fun markMouseSeen() {
        if (!body.mouseSeen) body.mouseSeen = true
    }

    fun linkAt(position: Offset): AnnotatedString.Range<LinkAnnotation>? =
        layout?.let { raftMarkdownMouseLinkAt(it, links, position) }
}

internal val AnnotatedString.Range<LinkAnnotation>.span: IntRange get() = start until end

/** The links [withRaftMarkdownMouseLinks] paints itself: styled and non-empty. */
internal fun AnnotatedString.raftMarkdownMouseLinks(): List<AnnotatedString.Range<LinkAnnotation>> =
    getLinkAnnotations(0, length).filter { it.end > it.start && it.item.styles != null }

/** The mouse-link state of [text]'s links, or null when the body has none or is not selectable in place. */
@Composable
internal fun rememberRaftMarkdownMouseLinks(text: AnnotatedString): RaftMarkdownMouseLinkState? {
    val body = LocalRaftMarkdownMouseLinkBody.current ?: return null
    val links = remember(text) { text.raftMarkdownMouseLinks() }
    if (links.isEmpty()) return null
    return remember(body, links) { RaftMarkdownMouseLinkState(links, body) }
}

/** [text] as composed for [state]: plain spans for its links once a mouse is about. */
@Composable
internal fun raftMarkdownMouseLinkText(text: AnnotatedString, state: RaftMarkdownMouseLinkState?): AnnotatedString {
    val active = state?.active == true
    val result =
        if (state != null && active) {
            val hovered = state.hovered
            val pressed = state.pressed
            remember(text, hovered, pressed) { text.withRaftMarkdownMouseLinks(hovered, pressed) }
        } else {
            text
        }
    if (state != null) SideEffect { state.applied = active }
    return result
}

/**
 * [this] with each styled link replaced by its look, as foundation TextLinkScope paints it: the
 * link's style, merged with its hovered and pressed styles for the [hovered] / [pressed] link. The
 * span takes the link's place in the annotation order, so styles nested inside the link still win.
 * A link without styles stays a link (Material3 Text gives it the default link look).
 */
internal fun AnnotatedString.withRaftMarkdownMouseLinks(hovered: IntRange?, pressed: IntRange?): AnnotatedString =
    flatMapAnnotations { range ->
        val link = range.item as? LinkAnnotation
        val styles = link?.styles
        if (styles == null || range.end <= range.start) {
            listOf(range)
        } else {
            val span = range.start until range.end
            var style = styles.style ?: SpanStyle()
            if (span == hovered) style = style.merge(styles.hoveredStyle)
            if (span == pressed) style = style.merge(styles.pressedStyle)
            listOf(AnnotatedString.Range(style, range.start, range.end))
        }
    }

/**
 * The link whose glyphs are under [position] (local px of the text), or null. The later of
 * overlapping links wins, as its framework box is on top.
 */
internal fun raftMarkdownMouseLinkAt(
    layout: TextLayoutResult,
    links: List<AnnotatedString.Range<LinkAnnotation>>,
    position: Offset,
): AnnotatedString.Range<LinkAnnotation>? {
    val textLength = layout.layoutInput.text.length
    if (textLength == 0 || links.isEmpty()) return null
    val caret = layout.getOffsetForPosition(position)
    // The caret is the nearest gap: the character under the pointer is the one after or before it.
    for (offset in intArrayOf(caret, caret - 1)) {
        if (offset !in 0 until textLength) continue
        val link = links.lastOrNull { offset >= it.start && offset < it.end } ?: continue
        if (layout.getBoundingBox(offset).contains(position)) return link
    }
    return null
}

/**
 * Whether a press released opens its link: it started on a link, never moved past the touch slop
 * (a drag selects instead), was let go on the same link and, for a finger, was not a long press
 * (that selects).
 */
internal fun raftMarkdownMouseLinkClickOpens(
    pressedLink: IntRange?,
    releasedLink: IntRange?,
    movedBeyondSlop: Boolean,
    longPressByFinger: Boolean = false,
): Boolean = pressedLink != null && !movedBeyondSlop && !longPressByFinger && pressedLink == releasedLink

/** foundation TextLinkScope.handleLink: the link's own listener, else a Url opens in the browser. */
internal fun LinkAnnotation.raftOpenAsTextLink(uriHandler: UriHandler) {
    val listener = linkInteractionListener
    when {
        listener != null -> listener.onClick(this)
        this is LinkAnnotation.Url ->
            try {
                uriHandler.openUri(url)
            } catch (_: IllegalArgumentException) {
                // Not a URI the platform can open; the framework link ignores it too.
            }
    }
}

/**
 * Watches the text's pointer without consuming anything (the selection gesture keeps the press):
 * a mouse hover marks the body as mouse-driven, hovering a link shows the hand and its hovered
 * style, and a click on a link (see [raftMarkdownMouseLinkClickOpens]) opens it while [state] has replaced
 * the framework links.
 */
@Composable
internal fun Modifier.raftMarkdownMouseLinks(state: RaftMarkdownMouseLinkState): Modifier {
    val uriHandler by rememberUpdatedState(LocalUriHandler.current)
    val hand =
        if (state.active && state.hovered != null) {
            Modifier.pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true)
        } else {
            Modifier
        }
    return this.then(hand).pointerInput(state) {
        awaitPointerEventScope {
            var pressedLink: AnnotatedString.Range<LinkAnnotation>? = null
            var pressedByMouse = false
            var downPosition = Offset.Zero
            var downTime = 0L
            var dragged = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                val mouse = change.type == PointerType.Mouse
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move -> {
                        if (mouse && !change.pressed) state.markMouseSeen()
                        if (pressedLink != null && (change.position - downPosition).getDistance() > viewConfiguration.touchSlop) {
                            dragged = true
                            state.pressed = null
                        }
                        if (mouse) state.hovered = state.linkAt(change.position)?.span
                    }
                    PointerEventType.Exit -> state.hovered = null
                    PointerEventType.Press -> {
                        // Only while the framework links are gone, or a click would open twice.
                        val ours = state.applied
                        if (mouse) state.markMouseSeen()
                        pressedLink =
                            if (ours && (!mouse || event.buttons.isPrimaryPressed)) state.linkAt(change.position) else null
                        pressedByMouse = mouse
                        downPosition = change.position
                        downTime = change.uptimeMillis
                        dragged = false
                        state.pressed = pressedLink?.span
                    }
                    PointerEventType.Release -> {
                        val link = pressedLink
                        pressedLink = null
                        state.pressed = null
                        if (
                            link != null &&
                                raftMarkdownMouseLinkClickOpens(
                                    pressedLink = link.span,
                                    releasedLink = state.linkAt(change.position)?.span,
                                    movedBeyondSlop = dragged,
                                    longPressByFinger =
                                        !pressedByMouse &&
                                            change.uptimeMillis - downTime >= viewConfiguration.longPressTimeoutMillis,
                                )
                        ) {
                            link.item.raftOpenAsTextLink(uriHandler)
                        }
                    }
                }
            }
        }
    }
}
