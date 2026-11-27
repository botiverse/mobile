package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import build.raft.app.data.RaftRailPlace
import build.raft.app.data.mergeRaftMobilePlacesOrder
import build.raft.app.thread.key
import kotlin.math.sqrt

/** Which section of the in-popover Edit navigation bar surface a place belongs to. */
internal enum class MobilePlacesEditSection {
    TabBar,
    More,
}

/** Coordinates key for a single movable place slot inside Edit navigation bar mode (never Settings). */
internal data class MobilePlacesSlotKey(
    val section: MobilePlacesEditSection,
    val index: Int,
    val placeId: String,
)

/** Resolved drop destination for a dragged tile across Tab bar and More. */
internal data class MobilePlacesDropTarget(
    val section: MobilePlacesEditSection,
    val index: Int,
)

/**
 * Pure-Kotlin state & operations for the phone Server Tab Popover's in-place
 * "Edit navigation bar" mode (Kirby #415 v8 §7; server contract slock #16964).
 *
 * Server contract invariant:
 * `mobilePlaces` on `GET/PATCH /api/servers/:id/sidebar-order` is a single flat ordered list of
 * all places (no separate `tabCount` wire field). The first `min(maxTabBarCount, visible.size)`
 * visible built-in places populate the phone bottom tab bar (before the fixed Server tab), and the
 * remaining visible built-ins populate the More grid in the exact same order (followed by static
 * `Settings`, which is never in `mobilePlaces`).
 */
internal data class MobilePlacesEditState(
    val tabBarIds: List<String>,
    val moreIds: List<String>,
    val maxTabBarCount: Int = AppShellPhoneMaxPlaceTabs,
) {
    /** Unified flat visible order (`tabBarIds + moreIds`) ready to save via `SharedMobileRailPlacesStore.saveOrder`. */
    val orderedVisibleIds: List<String>
        get() = tabBarIds + moreIds

    /**
     * Whether removing a place from [tabBarIds] is allowed:
     * - Must keep at least 1 tab on the bar (`tabBarIds.size > 1`).
     * - Under the flat `mobilePlaces` contract, moving a tab into More promotes `moreIds.first()`
     *   into the vacated tab slot so the removed item stays at index 0 of More after save; when
     *   `moreIds` is empty (e.g. a guest with Artifacts OFF who only has 4 visible built-ins),
     *   every visible place already occupies a tab slot, so `−` is disabled.
     */
    val canRemoveFromTabBar: Boolean
        get() = tabBarIds.size > 1 && moreIds.isNotEmpty()

    fun sectionOf(placeId: String): MobilePlacesEditSection? =
        when {
            placeId in tabBarIds -> MobilePlacesEditSection.TabBar
            placeId in moreIds -> MobilePlacesEditSection.More
            else -> null
        }

    fun canMoveLeft(placeId: String): Boolean = orderedVisibleIds.indexOf(placeId) > 0

    fun canMoveRight(placeId: String): Boolean {
        val index = orderedVisibleIds.indexOf(placeId)
        return index in 0 until orderedVisibleIds.lastIndex
    }

    /**
     * Removes [placeId] from [tabBarIds] and places it at index 0 (front) of [moreIds].
     * Because the server stores a flat ordered list where positions `0 until maxTabBarCount` form
     * the tab bar, `moreIds.first()` slides up into the last tab slot so [placeId] cleanly lands
     * at `moreIds[0]` and survives round-tripping through `mobilePlaces`.
     * No-op when `!canRemoveFromTabBar` or [placeId] is not in [tabBarIds].
     */
    fun removeFromTabBar(placeId: String): MobilePlacesEditState {
        if (!canRemoveFromTabBar) return this
        val fromIndex = tabBarIds.indexOf(placeId)
        if (fromIndex < 0) return this
        val remainingTabs = tabBarIds.toMutableList().apply { removeAt(fromIndex) }
        val promoted = moreIds.first()
        val nextTabs = remainingTabs + promoted
        val nextMore = listOf(placeId) + moreIds.drop(1)
        return copy(tabBarIds = nextTabs, moreIds = nextMore)
    }

    /**
     * Adds [placeId] from [moreIds] to the end of [tabBarIds].
     * - If `tabBarIds.size < maxTabBarCount`: appends [placeId] to [tabBarIds].
     * - If `tabBarIds.size == maxTabBarCount` (full 4 tabs): bumps slot 4 (`tabBarIds.last()`)
     *   to index 0 (front) of [moreIds] and puts [placeId] at slot 4 — never drops a place.
     * No-op when [placeId] is not in [moreIds].
     */
    fun addToTabBar(placeId: String): MobilePlacesEditState {
        val fromMore = moreIds.indexOf(placeId)
        if (fromMore < 0) return this
        val remainingMore = moreIds.toMutableList().apply { removeAt(fromMore) }
        return if (tabBarIds.size < maxTabBarCount) {
            copy(tabBarIds = tabBarIds + placeId, moreIds = remainingMore)
        } else {
            val bumped = tabBarIds.last()
            copy(
                tabBarIds = tabBarIds.dropLast(1) + placeId,
                moreIds = listOf(bumped) + remainingMore,
            )
        }
    }

    /** Reorders an item within [tabBarIds] without disturbing [moreIds]. */
    fun reorderWithinTabBar(fromIndex: Int, toIndex: Int): MobilePlacesEditState {
        if (fromIndex !in tabBarIds.indices || toIndex !in tabBarIds.indices || fromIndex == toIndex) return this
        val nextTabs = tabBarIds.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        return copy(tabBarIds = nextTabs)
    }

    /** Reorders an item within [moreIds] without disturbing [tabBarIds]. */
    fun reorderWithinMore(fromIndex: Int, toIndex: Int): MobilePlacesEditState {
        if (fromIndex !in moreIds.indices || toIndex !in moreIds.indices || fromIndex == toIndex) return this
        val nextMore = moreIds.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        return copy(moreIds = nextMore)
    }

    /**
     * Moves [placeId] to [targetSection] at [targetIndex] (within-section or cross-section drag).
     * - Same section -> delegates to [reorderWithinTabBar] / [reorderWithinMore].
     * - More -> TabBar: inserts [placeId] at [targetIndex] in [tabBarIds]; if [tabBarIds] exceeds
     *   [maxTabBarCount], the spilled last tab moves to index 0 (front) of [moreIds].
     * - TabBar -> More: requires [canRemoveFromTabBar]; promotes `moreIds.first()` to the last tab
     *   slot and inserts [placeId] at `targetIndex.coerceIn(0, remainingMore.size)` in [moreIds].
     */
    fun moveAcrossSections(
        placeId: String,
        targetSection: MobilePlacesEditSection,
        targetIndex: Int,
    ): MobilePlacesEditState {
        val sourceSection = sectionOf(placeId) ?: return this
        return when {
            sourceSection == MobilePlacesEditSection.TabBar && targetSection == MobilePlacesEditSection.TabBar -> {
                val from = tabBarIds.indexOf(placeId)
                val to = targetIndex.coerceIn(0, tabBarIds.lastIndex.coerceAtLeast(0))
                reorderWithinTabBar(from, to)
            }
            sourceSection == MobilePlacesEditSection.More && targetSection == MobilePlacesEditSection.More -> {
                val from = moreIds.indexOf(placeId)
                val to = targetIndex.coerceIn(0, moreIds.lastIndex.coerceAtLeast(0))
                reorderWithinMore(from, to)
            }
            sourceSection == MobilePlacesEditSection.More && targetSection == MobilePlacesEditSection.TabBar -> {
                val fromMore = moreIds.indexOf(placeId)
                if (fromMore < 0) return this
                val remainingMore = moreIds.toMutableList().apply { removeAt(fromMore) }
                if (tabBarIds.size < maxTabBarCount) {
                    val clampedTarget = targetIndex.coerceIn(0, tabBarIds.size)
                    val nextTabs = tabBarIds.toMutableList().apply { add(clampedTarget, placeId) }
                    copy(tabBarIds = nextTabs, moreIds = remainingMore)
                } else {
                    val clampedTarget = targetIndex.coerceIn(0, (maxTabBarCount - 1).coerceAtLeast(0))
                    val expanded = tabBarIds.toMutableList().apply { add(clampedTarget, placeId) }
                    val bumped = expanded.removeAt(expanded.lastIndex)
                    copy(tabBarIds = expanded, moreIds = listOf(bumped) + remainingMore)
                }
            }
            else -> {
                if (!canRemoveFromTabBar) return this
                val fromTab = tabBarIds.indexOf(placeId)
                if (fromTab < 0) return this
                val remainingTabs = tabBarIds.toMutableList().apply { removeAt(fromTab) }
                val promoted = moreIds.first()
                val remainingMore = moreIds.drop(1).toMutableList()
                val clampedTarget = targetIndex.coerceIn(0, remainingMore.size)
                remainingMore.add(clampedTarget, placeId)
                copy(tabBarIds = remainingTabs + promoted, moreIds = remainingMore)
            }
        }
    }

    /** Moves [placeId] one slot earlier in the unified order (accessible "Move left"). */
    fun moveLeft(placeId: String): MobilePlacesEditState = moveByDelta(placeId, -1)

    /** Moves [placeId] one slot later in the unified order (accessible "Move right"). */
    fun moveRight(placeId: String): MobilePlacesEditState = moveByDelta(placeId, +1)

    private fun moveByDelta(placeId: String, delta: Int): MobilePlacesEditState {
        val all = orderedVisibleIds
        val from = all.indexOf(placeId)
        val to = from + delta
        if (from < 0 || to !in all.indices || delta == 0) return this
        val next = all.toMutableList().apply { add(to, removeAt(from)) }
        val split = tabBarIds.size.coerceIn(0, next.size)
        return copy(tabBarIds = next.take(split), moreIds = next.drop(split))
    }

    /**
     * Merges [orderedVisibleIds] back into [fullStoredKeys] while preserving hidden built-ins
     * (e.g. `artifacts` when flag is OFF, `members`/`computers`/`apps` for guests) and `app:` entries.
     */
    fun mergedWithStoredKeys(fullStoredKeys: List<String>): List<String> =
        mergeRaftMobilePlacesOrder(fullStoredKeys, orderedVisibleIds)

    companion object {
        fun fromVisibleIds(
            visibleIds: List<String>,
            maxTabBarCount: Int = AppShellPhoneMaxPlaceTabs,
        ): MobilePlacesEditState {
            val clampedMax = maxTabBarCount.coerceAtLeast(1)
            val distinct = visibleIds.distinct()
            val tabCount = minOf(clampedMax, distinct.size)
            return MobilePlacesEditState(
                tabBarIds = distinct.take(tabCount),
                moreIds = distinct.drop(tabCount),
                maxTabBarCount = clampedMax,
            )
        }

        fun fromMobilePlaces(
            mobilePlaces: List<RaftRailPlace>?,
            artifactsEnabled: Boolean,
            isGuest: Boolean,
            maxTabBarCount: Int = AppShellPhoneMaxPlaceTabs,
        ): MobilePlacesEditState =
            fromVisibleIds(
                visibleIds =
                    appShellVisibleMobileBuiltInPlaceIds(
                        mobilePlaces = mobilePlaces,
                        artifactsEnabled = artifactsEnabled,
                        isGuest = isGuest,
                    ),
                maxTabBarCount = maxTabBarCount,
            )
    }
}

/**
 * Pure-Kotlin hit test resolving [pointerRoot] against movable [slotBounds] (which never includes
 * static `Settings`). Prefers a direct containment match; falls back to the nearest slot within
 * [gapTolerancePx] so dragging across inter-tile gutters feels continuous.
 */
internal fun findMobilePlacesDropTarget(
    pointerRoot: Offset,
    slotBounds: Map<MobilePlacesSlotKey, Rect>,
    gapTolerancePx: Float = 24f,
): MobilePlacesDropTarget? {
    if (slotBounds.isEmpty()) return null
    slotBounds.entries.firstOrNull { (_, rect) -> rect.contains(pointerRoot) }?.key?.let { hit ->
        return MobilePlacesDropTarget(section = hit.section, index = hit.index)
    }
    var bestKey: MobilePlacesSlotKey? = null
    var bestDistance = Float.MAX_VALUE
    for ((key, rect) in slotBounds) {
        val dx =
            when {
                pointerRoot.x < rect.left -> rect.left - pointerRoot.x
                pointerRoot.x > rect.right -> pointerRoot.x - rect.right
                else -> 0f
            }
        val dy =
            when {
                pointerRoot.y < rect.top -> rect.top - pointerRoot.y
                pointerRoot.y > rect.bottom -> pointerRoot.y - rect.bottom
                else -> 0f
            }
        val edgeDistance = sqrt(dx * dx + dy * dy)
        if (edgeDistance <= gapTolerancePx && edgeDistance < bestDistance) {
            bestDistance = edgeDistance
            bestKey = key
        }
    }
    return bestKey?.let { MobilePlacesDropTarget(section = it.section, index = it.index) }
}
