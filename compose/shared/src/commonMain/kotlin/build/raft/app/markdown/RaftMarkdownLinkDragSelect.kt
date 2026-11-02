package build.raft.app.markdown

import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import kotlin.math.abs

/*
 * A mouse press that starts on a link and drags selects text from the press point, as in a
 * browser; a click (no drag past the touch slop) opens the link as before (artin, 2026-11-02).
 *
 * Links stay real LinkAnnotations (semantics, focus, keyboard, hover). Compose runs each one
 * through a clickable box over its words (foundation TextLinkScope); that box consumes the press,
 * and the selection gesture only starts on an unconsumed press, so a drag from a link selected
 * nothing. This watches the text's pointer in the Initial pass (before the link box): a primary
 * mouse press on a link is left alone; once it moves past the touch slop, the gesture is taken
 * (its moves are consumed, which cancels the link's click) and the selection is driven through the
 * body's public [SelectionState], from the press point to the pointer. A finger is never touched.
 */

/** One text of the body: its layout and where it is, so a pointer can be mapped to a character. */
internal class RaftMarkdownLinkDragText {
    var layout: TextLayoutResult? = null
    var coordinates: LayoutCoordinates? = null
}

/** One per selectable message body; null elsewhere (links behave as framework links only). */
@Stable
internal class RaftMarkdownLinkDragSelection(
    private val selectionState: SelectionState,
    private val onMouseSelectionStart: () -> Unit,
) {
    private val texts = mutableListOf<RaftMarkdownLinkDragText>()

    fun register(text: RaftMarkdownLinkDragText) {
        if (texts.none { it === text }) texts += text
    }

    fun unregister(text: RaftMarkdownLinkDragText) {
        texts.removeAll { it === text }
    }

    /**
     * The character offset under [position] (local px of [source]) in the global space of
     * [SelectionState.select]: the selectable texts in their layout order, end to end.
     */
    fun globalOffsetAt(source: RaftMarkdownLinkDragText, position: Offset): Int? {
        val sourceCoordinates = source.coordinates?.takeIf { it.isAttached } ?: return null
        val selectables = selectionState.getSelectableTexts()
        val candidates =
            texts.mapNotNull { text ->
                val layout = text.layout ?: return@mapNotNull null
                val coordinates = text.coordinates?.takeIf { it.isAttached } ?: return@mapNotNull null
                val index = raftMarkdownSelectableIndexOf(selectables, layout.layoutInput.text) ?: return@mapNotNull null
                Triple(index, layout, coordinates.localPositionOf(sourceCoordinates, position))
            }
        val nearest =
            raftMarkdownLinkDragNearest(
                candidates.map { (_, layout, local) -> RaftMarkdownTextExtent(height = layout.size.height.toFloat(), pointerY = local.y) }
            ) ?: return null
        val (index, layout, local) = candidates[nearest]
        return raftMarkdownSelectableStart(selectables.map { it.length }, index) + layout.getOffsetForPosition(local)
    }

    /** Some text of the body is selected (a drag in progress included). */
    val holdsSelection: Boolean
        get() = selectionState.selectedTexts.any { it.isNotEmpty() }

    fun select(anchor: Int, focus: Int) {
        selectionState.select(TextRange(anchor, focus))
    }

    fun onDragStart() = onMouseSelectionStart()
}

internal val LocalRaftMarkdownLinkDragSelection = compositionLocalOf<RaftMarkdownLinkDragSelection?> { null }

/** A text's height and the pointer's y in the text's local px. */
internal data class RaftMarkdownTextExtent(val height: Float, val pointerY: Float)

/**
 * The index of the text the pointer is over, else of the one vertically nearest (between
 * paragraphs, past the first or last); null when there is none.
 */
internal fun raftMarkdownLinkDragNearest(extents: List<RaftMarkdownTextExtent>): Int? =
    extents.indices.minByOrNull { i ->
        val extent = extents[i]
        when {
            extent.pointerY < 0f -> -extent.pointerY
            extent.pointerY > extent.height -> extent.pointerY - extent.height
            else -> 0f
        }
    }

/** Where the [index]th selectable starts in the global space: the lengths before it, summed. */
internal fun raftMarkdownSelectableStart(lengths: List<Int>, index: Int): Int = lengths.take(index).sum()

/** [text]'s index among [selectables] (a selectable's text is its layout's input), or null. */
internal fun raftMarkdownSelectableIndexOf(selectables: List<AnnotatedString>, text: AnnotatedString): Int? {
    val same = selectables.indexOfFirst { it === text }
    if (same >= 0) return same
    val equal = selectables.indexOfFirst { it == text }
    return equal.takeIf { it >= 0 }
}

/** Whether a press on a link has become a selection drag: a primary mouse press past the touch slop. */
internal fun raftMarkdownLinkPressBecomesSelection(
    pressedOnLinkWithPrimaryMouse: Boolean,
    distanceFromPress: Float,
    touchSlop: Float,
): Boolean = pressedOnLinkWithPrimaryMouse && abs(distanceFromPress) > touchSlop

/**
 * Whether [position] (local px of the text) is on one of [layout]'s links: the glyph under the
 * pointer carries a LinkAnnotation, as the framework link box (clipped to the link's path) would
 * be hit.
 */
internal fun raftMarkdownLinkAt(layout: TextLayoutResult, position: Offset): Boolean {
    val text = layout.layoutInput.text
    if (text.isEmpty()) return false
    val caret = layout.getOffsetForPosition(position)
    // The caret is the nearest gap: the character under the pointer is the one after or before it.
    for (offset in intArrayOf(caret, caret - 1)) {
        if (offset !in 0 until text.length) continue
        if (!text.hasLinkAnnotations(offset, offset + 1)) continue
        if (layout.getBoundingBox(offset).contains(position)) return true
    }
    return false
}

/** Registers a body text for the drag; null outside a selectable body. */
@Composable
internal fun rememberRaftMarkdownLinkDragText(): Pair<RaftMarkdownLinkDragSelection, RaftMarkdownLinkDragText>? {
    val selection = LocalRaftMarkdownLinkDragSelection.current ?: return null
    val text = remember(selection) { RaftMarkdownLinkDragText() }
    DisposableEffect(selection, text) {
        selection.register(text)
        onDispose { selection.unregister(text) }
    }
    return selection to text
}

/**
 * Keeps [text]'s coordinates for the drag and, when [hasLinks], turns a primary mouse press on a
 * link that drags past the touch slop into a selection from the press point. Never consumes a
 * finger, a click or a press off the links, so the link opens and the selection gestures run as
 * before.
 */
internal fun Modifier.raftMarkdownLinkDragSelect(
    selection: RaftMarkdownLinkDragSelection,
    text: RaftMarkdownLinkDragText,
    hasLinks: Boolean,
): Modifier {
    val positioned = onGloballyPositioned { text.coordinates = it }
    if (!hasLinks) return positioned
    return positioned.pointerInput(selection, text) {
        awaitPointerEventScope {
            var pressedOnLink = false
            var downPosition = Offset.Zero
            var anchor: Int? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Press -> {
                        // Another button pressed mid-drag: the drag goes on from where it started.
                        if (anchor != null) continue
                        anchor = null
                        downPosition = change.position
                        pressedOnLink =
                            event.changes.size == 1 &&
                                change.type == PointerType.Mouse &&
                                event.buttons.isPrimaryPressed &&
                                text.layout?.let { raftMarkdownLinkAt(it, change.position) } == true
                    }
                    PointerEventType.Move -> {
                        if (!pressedOnLink) continue
                        val start =
                            anchor
                                ?: run {
                                    if (
                                        !raftMarkdownLinkPressBecomesSelection(
                                            pressedOnLinkWithPrimaryMouse = true,
                                            distanceFromPress = (change.position - downPosition).getDistance(),
                                            touchSlop = viewConfiguration.touchSlop,
                                        )
                                    ) {
                                        return@run null
                                    }
                                    val mapped = selection.globalOffsetAt(text, downPosition)
                                    // No layout to map to: the gesture stays the link's, as before.
                                    if (mapped == null) pressedOnLink = false else selection.onDragStart()
                                    mapped
                                }
                                ?: continue
                        anchor = start
                        // Consumed: the link's clickable cancels its press, so letting go opens nothing.
                        change.consume()
                        val focus = selection.globalOffsetAt(text, change.position) ?: continue
                        selection.select(start, focus)
                    }
                    PointerEventType.Release -> {
                        // Another button let go while the primary is still down: the drag goes on.
                        if (change.pressed) continue
                        if (anchor != null) change.consume()
                        pressedOnLink = false
                        anchor = null
                    }
                }
            }
        }
    }
}
