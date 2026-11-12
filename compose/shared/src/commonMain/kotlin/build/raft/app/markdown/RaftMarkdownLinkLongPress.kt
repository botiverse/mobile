package build.raft.app.markdown

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult

/*
 * A finger's long press on a link in a message opens that message's menu, with the link on top
 * (artin, 1.24). It did nothing: Compose runs each link through a clickable box over its words
 * (foundation TextLinkScope), and that box consumes the press, so the message row's long-press
 * detector never started. This watches the text's pointer in the Initial pass (before the link
 * box): a finger held on a link past the long-press timeout without moving past the touch slop is
 * handed to [LocalRaftMarkdownLinkLongPress] (the message row), and the rest of that press is
 * consumed so letting go does not also open the link. A tap, a drag, a scroll, a second finger
 * and a mouse are left alone (a mouse has its right-click menu and its drag-select).
 */

/**
 * What a long press landed on: a web link ([url]) or a link with no address the menu can offer
 * (an @mention, #channel or task chip: [url] null, the message's menu opens without a link
 * section).
 */
internal data class RaftMarkdownPressedLink(val url: String?)

/** Where a message body sends a finger's long press on one of its links. */
internal fun interface RaftMarkdownLinkLongPressHandler {
    /**
     * [position] is in [source]'s local px (the text that was pressed). True when the press was
     * taken (a menu opened): the rest of the press is then consumed and the link does not open.
     */
    fun onLinkLongPress(link: RaftMarkdownPressedLink, source: LayoutCoordinates, position: Offset): Boolean
}

/** The message row's handler; null outside a message row (a link keeps only its own click). */
internal val LocalRaftMarkdownLinkLongPress = staticCompositionLocalOf<RaftMarkdownLinkLongPressHandler?> { null }

/** A string annotation carrying the address of a link drawn as a chip whose tap opens something else. */
internal const val RaftMarkdownLinkUrlTag = "raft.build.markdown.linkUrl"

/** One text of a body: its layout and where it is, so a press can be mapped to a link. */
@Stable
internal class RaftMarkdownLinkLongPressText {
    var layout: TextLayoutResult? = null
    var coordinates: LayoutCoordinates? = null
}

/** A finger (or a stylus) press: the ones a long press opens the message menu for. */
internal fun raftMarkdownLinkLongPressPointer(type: PointerType): Boolean =
    type == PointerType.Touch || type == PointerType.Stylus || type == PointerType.Unknown

/**
 * The link under [position] (local px of the text) in [layout]: the glyph under the pointer
 * carries a link annotation, as the framework link box (clipped to the link's path) would be hit.
 * A web link gives its address; a chip whose tap opens something else gives the address it carries
 * ([RaftMarkdownLinkUrlTag], an App link card chip's [RaftAppLinkHoverTag]) or none (an @mention,
 * #channel or task chip). A body-wide tap target is not a link: null.
 */
internal fun raftMarkdownPressedLinkAt(layout: TextLayoutResult, position: Offset): RaftMarkdownPressedLink? {
    val text = layout.layoutInput.text
    if (text.isEmpty()) return null
    val caret = layout.getOffsetForPosition(position)
    // The caret is the nearest gap: the character under the pointer is the one after or before it.
    for (offset in intArrayOf(caret, caret - 1)) {
        if (offset !in 0 until text.length) continue
        val link = text.getLinkAnnotations(offset, offset + 1).firstOrNull()?.item ?: continue
        if (!layout.getBoundingBox(offset).contains(position)) continue
        return when (link) {
            is LinkAnnotation.Url -> RaftMarkdownPressedLink(url = link.url)
            is LinkAnnotation.Clickable -> {
                if (link.tag == RaftMarkdownBodyClickTag) return null
                val url =
                    text.getStringAnnotations(RaftMarkdownLinkUrlTag, offset, offset + 1).firstOrNull()?.item
                        ?: text.getStringAnnotations(RaftAppLinkHoverTag, offset, offset + 1).firstOrNull()?.item
                RaftMarkdownPressedLink(url = url?.takeIf { it.isNotBlank() })
            }
            // LinkAnnotation is not sealed: any other kind is still a link, with no address we know.
            else -> RaftMarkdownPressedLink(url = null)
        }
    }
    return null
}

/**
 * Keeps [text]'s coordinates and hands a finger's long press on one of its links to [handler].
 * Never consumes a tap, a drag or a press off the links: the link opens, the list scrolls and the
 * row's own gestures run as before.
 */
internal fun Modifier.raftMarkdownLinkLongPress(
    handler: RaftMarkdownLinkLongPressHandler,
    text: RaftMarkdownLinkLongPressText,
): Modifier =
    onGloballyPositioned { text.coordinates = it }
        .pointerInput(handler, text) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!raftMarkdownLinkLongPressPointer(down.type)) return@awaitEachGesture
                val link = text.layout?.let { raftMarkdownPressedLinkAt(it, down.position) } ?: return@awaitEachGesture
                val touchSlop = viewConfiguration.touchSlop
                // Non-null: the press became something else in time (a tap, a drag, a second finger).
                val endedEarly =
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var ended = false
                        while (!ended) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            ended =
                                event.changes.size > 1 ||
                                    change == null ||
                                    !change.pressed ||
                                    (change.position - down.position).getDistance() > touchSlop
                        }
                        true
                    }
                if (endedEarly != null) return@awaitEachGesture
                val source = text.coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                if (!handler.onLinkLongPress(link, source, down.position)) return@awaitEachGesture
                // The press is the menu's now: consumed, the link's click cancels and letting go opens nothing.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
