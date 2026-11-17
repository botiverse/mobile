package build.raft.app

import androidx.compose.ui.unit.dp

// Select to here (web botiverse/slock #14325, #14340, #14348: useSelectToHere, SelectToHerePill,
// messageRangeSelection.ts `idsToViewportEdge`). In message select mode, once the anchor (the
// message last added, while it stays selected) is scrolled out of view, a "↑ Select to here" /
// "↓ Select to here" pill floats with the jump pills, its arrow toward the anchor. A tap adds
// every selectable message from the anchor to the edge of the view furthest from it (the last
// message in view when the anchor is above, the first when below); that message is the next
// anchor, so the reader can scroll on and tap again. Nothing scrolls.

/** Where the anchor is from the view: the pill's arrow. */
internal enum class ThreadSelectToHereDirection { Up, Down }

/** What a tap on the pill adds: [ids] in list order, and [edgeId], the next anchor. */
internal data class ThreadSelectToHereRange(
    val ids: List<String>,
    val edgeId: String,
    val direction: ThreadSelectToHereDirection,
)

/**
 * The page's select mode as the message list needs it for Select to here: the [anchorId] (null:
 * no pill), what a range takes ([selectableIds], the select-all checkbox's messages, web
 * `selectableIds`), and [onSelectRange], which adds ids (never removes) and makes the second
 * argument the anchor (web selectionStore `selectRange`).
 */
data class ThreadShareSelectToHere(
    val anchorId: String?,
    val selectableIds: Set<String>,
    val onSelectRange: (ids: List<String>, edgeId: String) -> Unit,
)

/** A row counts as in view once this much of it (or all of a shorter one) is (web `VISIBLE_ROW_MIN_PX`). */
internal val ThreadSelectToHereVisibleRowMin = 24.dp

/** One laid-out row of the list, top-down offsets (ThreadListViewport.layoutInfo). */
internal data class ThreadSelectToHereRow(val key: Any, val offset: Int, val size: Int)

/**
 * The messages in view, top to bottom (web `visibleMessageIdsOfList`): rows whose key maps to a
 * message with at least [minVisiblePx] of them (or all of a shorter one) between [viewportStart]
 * and [viewportEnd] (already less what covers the list's bottom).
 */
internal fun threadSelectToHereVisibleMessageIds(
    rows: List<ThreadSelectToHereRow>,
    viewportStart: Int,
    viewportEnd: Int,
    minVisiblePx: Int,
    keyToMessageId: Map<Any, String>,
): List<String> {
    if (viewportEnd <= viewportStart) return emptyList()
    return rows
        .sortedBy { it.offset }
        .mapNotNull { row ->
            val id = keyToMessageId[row.key] ?: return@mapNotNull null
            val bottom = row.offset + row.size
            val shown = minOf(bottom, viewportEnd) - maxOf(row.offset, viewportStart)
            id.takeIf { shown >= minOf(minVisiblePx, row.size) && shown > 0 }
        }
}

/**
 * Which way the anchor is from the view (web `getMessageViewportPosition`): null while it is in
 * view, not loaded, between two rows in view, or nothing is in view.
 */
internal fun threadSelectToHereDirection(
    orderedIds: List<String>,
    anchorId: String?,
    visibleIds: List<String>,
): ThreadSelectToHereDirection? {
    if (anchorId == null || visibleIds.isEmpty() || anchorId in visibleIds) return null
    val anchorIndex = orderedIds.indexOf(anchorId)
    if (anchorIndex < 0) return null
    val visibleIndexes = visibleIds.map { orderedIds.indexOf(it) }.filter { it >= 0 }
    if (visibleIndexes.isEmpty()) return null
    return when {
        anchorIndex < visibleIndexes.min() -> ThreadSelectToHereDirection.Up
        anchorIndex > visibleIndexes.max() -> ThreadSelectToHereDirection.Down
        else -> null
    }
}

/**
 * Every selectable id from the anchor to the target, both included, in list order (web
 * `messageIdsInRange`). Null when the anchor or the target is not in [orderedIds].
 */
internal fun threadMessageIdsInRange(
    orderedIds: List<String>,
    anchorId: String?,
    targetId: String,
    isSelectable: (String) -> Boolean,
): List<String>? {
    if (anchorId == null) return null
    val anchorIndex = orderedIds.indexOf(anchorId)
    val targetIndex = orderedIds.indexOf(targetId)
    if (anchorIndex < 0 || targetIndex < 0) return null
    return orderedIds.subList(minOf(anchorIndex, targetIndex), maxOf(anchorIndex, targetIndex) + 1).filter(isSelectable)
}

/**
 * Select to here's range (web `idsToViewportEdge`): every selectable message from the anchor to
 * the edge of the view furthest from it. The anchor above the view takes down to the last
 * selectable message in view; below it, up to the first. Null when there is nothing to take: no
 * anchor, the anchor not loaded or in view, nothing selectable in view, or the anchor between
 * two rows in view.
 */
internal fun threadIdsToViewportEdge(
    orderedIds: List<String>,
    anchorId: String?,
    visibleIds: List<String>,
    isSelectable: (String) -> Boolean,
): ThreadSelectToHereRange? {
    if (anchorId == null || visibleIds.isEmpty()) return null
    val anchorIndex = orderedIds.indexOf(anchorId)
    if (anchorIndex < 0) return null
    val visible = visibleIds.toSet()
    if (anchorId in visible) return null
    var first = -1
    var last = -1
    orderedIds.forEachIndexed { index, id ->
        if (id !in visible || !isSelectable(id)) return@forEachIndexed
        if (first < 0) first = index
        last = index
    }
    if (first < 0) return null
    val direction =
        when {
            anchorIndex < first -> ThreadSelectToHereDirection.Up
            anchorIndex > last -> ThreadSelectToHereDirection.Down
            else -> return null
        }
    val edgeId = orderedIds[if (direction == ThreadSelectToHereDirection.Up) last else first]
    val ids = threadMessageIdsInRange(orderedIds, anchorId, edgeId, isSelectable) ?: return null
    return ThreadSelectToHereRange(ids = ids, edgeId = edgeId, direction = direction)
}
