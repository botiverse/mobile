package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.base.BridgeModule
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftRailPlace
import build.raft.app.data.RaftRailPlaceApp
import build.raft.app.data.RaftRailPlacesSnapshot
import build.raft.app.data.SharedRailPlacesStore
import build.raft.app.data.canonicalRaftRailPlaceKey
import build.raft.app.data.isRaftRailBuiltInPlaceId
import build.raft.app.data.isRaftRailKeptPlaceId
import build.raft.app.data.raftRailAppsNotOnList
import build.raft.app.data.raftRailBuiltInsNotOnList
import build.raft.app.data.raftRailMovePlace
import build.raft.app.data.raftRailMovePlaceTo
import build.raft.app.data.raftRailPlaceNextTo
import build.raft.app.data.raftRailPlacesForKeys
import build.raft.app.data.raftRailRemovePlace
import build.raft.app.data.raftRailSlots
import build.raft.app.data.raftVisiblePlaceCount
import build.raft.app.data.withRaftRailKeptPlaceKeys
import build.raft.app.data.withRaftRailKeptPlaces
import build.raft.app.resources.*
import build.raft.app.thread.RaftConnectedAppLogo
import build.raft.app.thread.icon
import build.raft.designsystem.components.RaftAppRailDefaults
import build.raft.designsystem.components.RaftAppRailItem
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuLabel
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.components.RaftReorderDefaults
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.haptics.RaftHaptics
import build.raft.designsystem.haptics.rememberRaftHaptics
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftMenuMetrics
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// Desktop per-server rail places drag-and-drop & More overflow menu (botiverse/slock #16773,
// #16808, #16838, #16857, #16864; #wg-mobile-sync:#322):
// - Fixed tabs (Search, when shown on the rail) stay first; dragging over Search is refused.
// - Chat & Activity move like any place on the rail, and can never be removed or dropped into More.
// - One halves rule (`raftRailSlotAt`) decides where a pointer-dragged place stands on the rail,
//   whether dragged along the rail or out of More: upper half -> before, lower half -> after,
//   gap between two places -> between them, above first -> before first, below last -> after last,
//   over its own slot or an adjacent gap -> stays put.
// - Holding a removable rail place over More for 500ms opens More; dropping on the open menu or
//   on More once open removes it (with an Undo toast). Dropping on More before it opens moves it
//   to the rail's last visible spot.
// - Dragging a place out of More pins the menu's Y anchor and keeps its rows settled (dimmed at
//   40% at the origin) until drop.
// - A drag is never a click, even when dropped in place, and Escape cancels only the drag (leaving
//   a user-opened More menu open).

/** The More button's rail item key / test tag suffix (web `MORE_ID`). */
internal const val RaftRailMoreItemKey = "rail:more"

/** How long a rail place is held on More before More opens automatically (web `MORE_DWELL_MS`). */
internal const val RaftRailMoreDwellMs = 500L

/** How long after a drag's release a click is still treated as the drag's own (web `CLICK_AFTER_DROP_MS`). */
internal const val RaftRailClickBlockAfterDropMs = 50L

/** Test tag prefix for place rows rendered inside the desktop rail More menu. */
internal const val RaftRailMorePlaceTagPrefix = "rail-more-place-"

/** Where a desktop rail place pointer drag originated (web `DragSource`). */
internal enum class RaftRailDragSource {
    Rail,
    MoreMenu,
}

/** Refusal hint shown while dragging over a disallowed drop target. */
internal enum class RaftRailDragRefusal {
    SearchStaysFirst,
    KeptStayOnRail,
}

/** A visible movable place standing on the rail in root coordinates (web `StandingPlace`). */
internal data class RaftRailStandingPlace(
    val key: String,
    val top: Float,
    val bottom: Float,
)

/** Insertion slot returned by [raftRailSlotAt]: before (`after = false`) or after (`after = true`) [targetKey]. */
internal data class RaftRailDragSlot(
    val targetKey: String,
    val after: Boolean,
)

/** Live preview state computed on pointer move during a desktop rail drag. */
internal data class RaftRailDragPreviewResult(
    val previewKeys: List<String>?,
    val refusal: RaftRailDragRefusal?,
)

/** Resolved outcome when a desktop rail pointer drag ends (web `RailPlacesNav.tsx` `onDragEnd`). */
internal sealed interface RaftRailDragDropOutcome {
    data class Save(
        val keys: List<String>,
        val added: Boolean,
        val neighborKey: String?,
        val placedAfterNeighbor: Boolean,
    ) : RaftRailDragDropOutcome

    data class Remove(
        val key: String,
        val originalIndex: Int,
    ) : RaftRailDragDropOutcome

    data class MoveToLast(
        val keys: List<String>,
        val targetKey: String,
    ) : RaftRailDragDropOutcome

    data object StayInPlace : RaftRailDragDropOutcome

    data class Refused(
        val reason: RaftRailDragRefusal,
    ) : RaftRailDragDropOutcome

    data object Cancelled : RaftRailDragDropOutcome
}

/**
 * The one rule for where a pointer-dragged place stands on the rail (web `railSlotAt`,
 * botiverse/slock #16857 + #16864):
 * - Over a place: upper half (`pointerY <= (top + bottom) / 2`) is before it (`after = false`),
 *   lower half (`pointerY > midpoint`) is after it (`after = true`).
 * - In the gap before `places[i]`: between `places[i - 1]` and `places[i]` (`after = false` on
 *   `places[i]`), unless either side is [heldKey] (already there -> `null`).
 * - Above the first place: before the first place (unless first is [heldKey] -> `null`).
 * - Below the last place: after the last place (unless last is [heldKey] -> `null`).
 * - Over [heldKey] itself: `null` (it stays put).
 */
internal fun raftRailSlotAt(
    places: List<RaftRailStandingPlace>,
    heldKey: String,
    pointerY: Float,
): RaftRailDragSlot? {
    for (i in places.indices) {
        val place = places[i]
        if (pointerY < place.top) {
            if (place.key == heldKey || places.getOrNull(i - 1)?.key == heldKey) return null
            return RaftRailDragSlot(targetKey = place.key, after = false)
        }
        if (pointerY <= place.bottom) {
            if (place.key == heldKey) return null
            val mid = (place.top + place.bottom) / 2f
            return RaftRailDragSlot(targetKey = place.key, after = pointerY > mid)
        }
    }
    val last = places.lastOrNull() ?: return null
    if (last.key == heldKey) return null
    return RaftRailDragSlot(targetKey = last.key, after = true)
}

/**
 * Pure preview & refusal step on pointer move (web `RailPlacesNav.tsx` `onDragOver` + `followHand`,
 * botiverse/slock #16808 + #16857 + #16864).
 */
internal fun raftRailComputeDragPreview(
    fullKeys: List<String>,
    heldKey: String,
    source: RaftRailDragSource,
    pointerY: Float,
    overRail: Boolean,
    overFixed: Boolean,
    overMoreOrMenu: Boolean,
    standingPlaces: List<RaftRailStandingPlace>,
    currentPreview: List<String>?,
    fitsOnRail: (candidateKeys: List<String>, key: String) -> Boolean,
): RaftRailDragPreviewResult {
    val refusal =
        when {
            overFixed -> RaftRailDragRefusal.SearchStaysFirst
            source == RaftRailDragSource.Rail && overMoreOrMenu && isRaftRailKeptPlaceId(heldKey) ->
                RaftRailDragRefusal.KeptStayOnRail
            else -> null
        }
    if (overMoreOrMenu || !overRail || overFixed) {
        return RaftRailDragPreviewResult(previewKeys = null, refusal = refusal)
    }
    val slot = raftRailSlotAt(standingPlaces, heldKey, pointerY)
        ?: return RaftRailDragPreviewResult(previewKeys = currentPreview, refusal = refusal)
    var next = raftRailPlaceNextTo(fullKeys, heldKey, slot.targetKey, slot.after)
    // On a full rail, dropping after the last place shown would fold into More: stand before it instead.
    if (next != null && slot.after && !fitsOnRail(next, heldKey)) {
        next = raftRailPlaceNextTo(fullKeys, heldKey, slot.targetKey, after = false)
    }
    if (next == null) {
        return RaftRailDragPreviewResult(previewKeys = currentPreview, refusal = refusal)
    }
    // A place from the rail back in its own slot is where it was: clear preview, nothing to save.
    if (source == RaftRailDragSource.Rail && next == fullKeys) {
        return RaftRailDragPreviewResult(previewKeys = null, refusal = refusal)
    }
    return RaftRailDragPreviewResult(previewKeys = next, refusal = refusal)
}

/**
 * Pure drop resolution when a desktop rail pointer drag finishes (web `RailPlacesNav.tsx`
 * `onDragEnd`, botiverse/slock #16808 + #16857 + #16864).
 */
internal fun raftRailResolveDragDrop(
    fullKeys: List<String>,
    shownKeys: List<String>,
    heldKey: String,
    source: RaftRailDragSource,
    overRail: Boolean,
    overFixed: Boolean,
    overMoreOrMenu: Boolean,
    moreMenuOpen: Boolean,
    stagedPreview: List<String>?,
): RaftRailDragDropOutcome {
    if (source == RaftRailDragSource.Rail && overMoreOrMenu) {
        if (isRaftRailKeptPlaceId(heldKey)) {
            return RaftRailDragDropOutcome.Refused(RaftRailDragRefusal.KeptStayOnRail)
        }
        if (moreMenuOpen) {
            return RaftRailDragDropOutcome.Remove(
                key = heldKey,
                originalIndex = fullKeys.indexOf(heldKey).coerceAtLeast(0),
            )
        }
        val last = shownKeys.lastOrNull()
        val next = if (last != null && last != heldKey) raftRailMovePlaceTo(fullKeys, heldKey, last) else null
        return if (next != null && last != null) {
            RaftRailDragDropOutcome.MoveToLast(keys = next, targetKey = last)
        } else {
            RaftRailDragDropOutcome.StayInPlace
        }
    }
    if (overFixed || overMoreOrMenu || !overRail) {
        return RaftRailDragDropOutcome.Cancelled
    }
    if (stagedPreview == null || heldKey !in shownKeys) {
        return if (source == RaftRailDragSource.MoreMenu) {
            RaftRailDragDropOutcome.Cancelled
        } else {
            RaftRailDragDropOutcome.StayInPlace
        }
    }
    val at = shownKeys.indexOf(heldKey)
    val above = if (at > 0) shownKeys[at - 1] else null
    val below = if (at >= 0) shownKeys.getOrNull(at + 1) else null
    val neighbor = above ?: below
    return RaftRailDragDropOutcome.Save(
        keys = stagedPreview,
        added = heldKey !in fullKeys,
        neighborKey = neighbor,
        placedAfterNeighbor = above != null,
    )
}

/** Re-inserts a removed place [key] at [atIndex] for Undo (web `railPlaceActions.ts` Undo). */
internal fun raftRailUndoInsertKey(
    currentKeys: List<String>,
    key: String,
    atIndex: Int,
): List<String> {
    val canonical = canonicalRaftRailPlaceKey(key) ?: return currentKeys
    if (canonical in currentKeys) return currentKeys
    val index = atIndex.coerceIn(0, currentKeys.size)
    return currentKeys.toMutableList().apply { add(index, canonical) }
}

internal data class RaftRailActivePointerDrag(
    val placeKey: String,
    val source: RaftRailDragSource,
    val pointerRoot: Offset,
)

/**
 * Stateful coordinator shared between the desktop navigation rail ([RaftDesktopRailPlacesNav])
 * and its shell-level overlays ([Overlays]: More overflow menu + floating drag lift tile & refusal
 * hint). Kept in one object so `SettingsHomePage.kt` adds only a single hook call.
 */
@Stable
internal class RaftDesktopRailPlacesHost(
    private val railPlacesStore: SharedRailPlacesStore,
    private val bridge: BridgeModule,
    private val scope: CoroutineScope,
    private val haptics: RaftHaptics,
    initialServerId: String = "",
    initialSnapshot: RaftRailPlacesSnapshot = RaftRailPlacesSnapshot(emptyList(), 0, emptyList()),
    initialBaseRailItems: List<RaftBottomTabItemPayload> = emptyList(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    internal var serverId: String = initialServerId
    internal var snapshot: RaftRailPlacesSnapshot by mutableStateOf(initialSnapshot)
    internal var baseRailItems: List<RaftBottomTabItemPayload> by mutableStateOf(initialBaseRailItems)
    internal var resolveIconUrl: (String?) -> String? = { it }
    internal var onSelectItem: (String) -> Unit = {}
    internal var onOpenEdit: () -> Unit = {}

    internal var railSlotCapacity: Int by mutableIntStateOf(Int.MAX_VALUE)
    internal var moreOpen: Boolean by mutableStateOf(false)
        private set
    private var openedByDrag: Boolean = false
    internal var pinnedMoreAnchorTopPx: Float? by mutableStateOf(null)
        private set

    internal var activePointerDrag: RaftRailActivePointerDrag? by mutableStateOf(null)
        private set
    internal var keyboardDragKey: String? by mutableStateOf(null)
        private set
    private var keyboardPickupOrder: List<String>? = null
    internal var previewKeys: List<String>? by mutableStateOf(null)
        private set
    internal var refusal: RaftRailDragRefusal? by mutableStateOf(null)
        private set
    internal var liveAnnouncement: String by mutableStateOf("")
        private set

    private var lastDragEndedMark: TimeMark? = null
    private var dwellJob: Job? = null

    private var navBounds: Rect? = null
    private val fixedBoundsByKey = linkedMapOf<String, Rect>()
    private val standingBoundsByKey = linkedMapOf<String, Rect>()
    internal var moreButtonBounds: Rect? by mutableStateOf(null)
        private set
    private var moreMenuBounds: Rect? = null

    internal val isDragging: Boolean
        get() = activePointerDrag != null || keyboardDragKey != null

    internal val isPointerDragging: Boolean
        get() = activePointerDrag != null

    internal fun shouldSuppressClick(): Boolean {
        if (isDragging) return true
        val ended = lastDragEndedMark ?: return false
        return ended.elapsedNow().inWholeMilliseconds < RaftRailClickBlockAfterDropMs
    }

    internal fun fixedItems(): List<RaftBottomTabItemPayload> =
        baseRailItems.filter { it.key in RaftRailPlacesFixedItemKeys }

    internal fun builtInByPlaceId(): Map<String, RaftBottomTabItemPayload> =
        baseRailItems
            .filter { it.key !in RaftRailPlacesFixedItemKeys }
            .mapNotNull { item -> raftRailTabIdForKey(item.key)?.let { placeId -> placeId to item } }
            .toMap()

    internal fun settledPlaces(): List<RaftRailPlace> = withRaftRailKeptPlaces(snapshot.places)

    internal fun settledKeys(): List<String> = settledPlaces().map { it.key }

    internal fun settledDrawable(builtIns: Map<String, RaftBottomTabItemPayload>): List<RaftRailPlace> =
        settledPlaces().filter { it.isApp || it.key in builtIns }

    internal fun activeDrawable(builtIns: Map<String, RaftBottomTabItemPayload>): List<RaftRailPlace> {
        val currentPreview = previewKeys
        val places =
            if (currentPreview != null) {
                raftRailPlacesForKeys(currentPreview, snapshot.places, snapshot.placeableApps)
            } else {
                settledPlaces()
            }
        return places.filter { it.isApp || it.key in builtIns }
    }

    internal fun shownPlaces(builtIns: Map<String, RaftBottomTabItemPayload>): List<RaftRailPlace> {
        val drawable = activeDrawable(builtIns)
        val count = raftVisiblePlaceCount(drawable.size, railSlotCapacity, fixedItems().size)
        return drawable.take(count)
    }

    internal fun settledOverflow(builtIns: Map<String, RaftBottomTabItemPayload>): List<RaftRailPlace> {
        val settled = settledDrawable(builtIns)
        val count = raftVisiblePlaceCount(settled.size, railSlotCapacity, fixedItems().size)
        return settled.drop(count)
    }

    internal fun notOnListBuiltIns(builtIns: Map<String, RaftBottomTabItemPayload>): List<RaftBottomTabItemPayload> =
        raftRailBuiltInsNotOnList(settledKeys()) { it in builtIns }.mapNotNull { builtIns[it] }

    internal fun notOnListApps(): List<RaftRailPlaceApp> =
        raftRailAppsNotOnList(settledKeys(), snapshot.placeableApps)

    internal fun payloadForPlace(
        place: RaftRailPlace,
        builtIns: Map<String, RaftBottomTabItemPayload>,
    ): RaftBottomTabItemPayload? =
        when {
            place.isBuiltIn -> builtIns[place.key]
            place.isApp -> {
                val app = place.app ?: return null
                RaftBottomTabItemPayload(
                    key = place.key,
                    label = app.name,
                    iconName = RaftLucideIconName.Blocks,
                    selected = false,
                    enabled = true,
                    isAppPlace = true,
                    avatarSource = resolveIconUrl(app.iconUrl),
                    avatarFallbackLabel = app.name,
                )
            }
            else -> null
        }

    internal fun placeForKey(key: String): RaftRailPlace? {
        snapshot.places.firstOrNull { it.key == key }?.let { return it }
        if (key.startsWith("app:")) {
            val clientId = key.removePrefix("app:")
            val app = snapshot.placeableApps.firstOrNull { it.clientId.equals(clientId, ignoreCase = true) }
            return app?.let { RaftRailPlace.app(it) }
        }
        return if (isRaftRailBuiltInPlaceId(key)) RaftRailPlace.builtIn(key) else null
    }

    internal fun labelForKey(key: String): String {
        if (key == RaftRailMoreItemKey) return Res.string.rail_places_more.desc().resolve()
        fixedItems().firstOrNull { it.key == key }?.let { return it.label }
        val builtIns = builtInByPlaceId()
        val place = placeForKey(key) ?: return key
        return if (place.isApp) place.app?.name ?: key else builtIns[place.key]?.label ?: key
    }

    internal fun updateNavBounds(bounds: Rect) {
        navBounds = bounds
    }

    internal fun updateFixedBounds(itemKey: String, bounds: Rect) {
        fixedBoundsByKey[itemKey] = bounds
    }

    internal fun updateStandingBounds(placeKey: String, bounds: Rect) {
        standingBoundsByKey[placeKey] = bounds
    }

    internal fun updateMoreButtonBounds(bounds: Rect) {
        moreButtonBounds = bounds
    }

    internal fun updateMoreMenuBounds(bounds: Rect?) {
        moreMenuBounds = bounds
    }

    internal fun toggleMore() {
        if (isDragging) return
        openedByDrag = false
        moreOpen = !moreOpen
        if (!moreOpen) {
            moreMenuBounds = null
        }
    }

    internal fun closeMore() {
        if (isDragging) return
        openedByDrag = false
        moreOpen = false
        moreMenuBounds = null
    }

    internal fun resetForContextChange() {
        stopDwell()
        activePointerDrag = null
        keyboardDragKey = null
        keyboardPickupOrder = null
        previewKeys = null
        refusal = null
        pinnedMoreAnchorTopPx = null
        openedByDrag = false
        moreOpen = false
        moreMenuBounds = null
        standingBoundsByKey.clear()
        fixedBoundsByKey.clear()
    }

    internal fun handlePreviewEscapeKey(event: KeyEvent): Boolean {
        if (event.key != Key.Escape || !isDragging) return false
        if (event.type == KeyEventType.KeyDown) {
            cancelDrag()
        }
        return true
    }

    internal fun startPointerDrag(
        placeKey: String,
        source: RaftRailDragSource,
        pointerRoot: Offset,
    ): Boolean {
        if (isDragging) return false
        if (source == RaftRailDragSource.MoreMenu) {
            pinnedMoreAnchorTopPx = moreButtonBounds?.top
        }
        activePointerDrag = RaftRailActivePointerDrag(placeKey = placeKey, source = source, pointerRoot = pointerRoot)
        previewKeys = null
        refusal = null
        haptics.longPress()
        liveAnnouncement = Res.string.rail_tab_drag_picked_up.format(labelForKey(placeKey)).resolve()
        return true
    }

    internal fun updatePointerDrag(pointerRoot: Offset) {
        val drag = activePointerDrag ?: return
        activePointerDrag = drag.copy(pointerRoot = pointerRoot)
        val builtIns = builtInByPlaceId()
        val fixed = fixedItems()
        val overMoreMenu = moreOpen && moreMenuBounds?.contains(pointerRoot) == true
        val overMoreButton = moreButtonBounds?.contains(pointerRoot) == true
        val overMoreOrMenu = overMoreMenu || overMoreButton
        val overFixed = !overMoreOrMenu && fixed.any { fixedBoundsByKey[it.key]?.contains(pointerRoot) == true }
        val overRail = !overMoreOrMenu && navBounds?.contains(pointerRoot) == true

        // Dwell over More button for 500ms opens More for removable rail places.
        if (drag.source == RaftRailDragSource.Rail && overMoreButton && !isRaftRailKeptPlaceId(drag.placeKey)) {
            if (!moreOpen && dwellJob == null) {
                dwellJob =
                    scope.launch {
                        delay(RaftRailMoreDwellMs)
                        dwellJob = null
                        if (activePointerDrag?.placeKey == drag.placeKey) {
                            openedByDrag = true
                            moreOpen = true
                        }
                    }
            }
        } else {
            stopDwell()
        }

        val currentShown = shownPlaces(builtIns)
        val standing =
            currentShown.mapNotNull { place ->
                standingBoundsByKey[place.key]?.let { rect ->
                    RaftRailStandingPlace(key = place.key, top = rect.top, bottom = rect.bottom)
                }
            }
        val result =
            raftRailComputeDragPreview(
                fullKeys = settledKeys(),
                heldKey = drag.placeKey,
                source = drag.source,
                pointerY = pointerRoot.y,
                overRail = overRail,
                overFixed = overFixed,
                overMoreOrMenu = overMoreOrMenu,
                standingPlaces = standing,
                currentPreview = previewKeys,
                fitsOnRail = { candidateKeys, key -> fitsOnRail(candidateKeys, key, builtIns, fixed.size) },
            )
        if (result.previewKeys != previewKeys && result.previewKeys != null) {
            haptics.tick()
        }
        previewKeys = result.previewKeys
        refusal = result.refusal
    }

    internal fun finishPointerDrag(pointerRoot: Offset) {
        val drag = activePointerDrag ?: return
        val builtIns = builtInByPlaceId()
        val fixed = fixedItems()
        val fullKeys = settledKeys()
        val staged = previewKeys
        val shownKeys = shownPlaces(builtIns).map { it.key }
        val menuWasOpen = moreOpen
        val overMoreMenu = menuWasOpen && moreMenuBounds?.contains(pointerRoot) == true
        val overMoreButton = moreButtonBounds?.contains(pointerRoot) == true
        val overMoreOrMenu = overMoreMenu || overMoreButton
        val overFixed = !overMoreOrMenu && fixed.any { fixedBoundsByKey[it.key]?.contains(pointerRoot) == true }
        val overRail = !overMoreOrMenu && navBounds?.contains(pointerRoot) == true

        val outcome =
            raftRailResolveDragDrop(
                fullKeys = fullKeys,
                shownKeys = shownKeys,
                heldKey = drag.placeKey,
                source = drag.source,
                overRail = overRail,
                overFixed = overFixed,
                overMoreOrMenu = overMoreOrMenu,
                moreMenuOpen = menuWasOpen,
                stagedPreview = staged,
            )
        clearDragState()
        val name = labelForKey(drag.placeKey)
        when (outcome) {
            is RaftRailDragDropOutcome.Save -> {
                saveKeys(outcome.keys)
                liveAnnouncement = formatLandedAnnouncement(name, outcome)
            }
            is RaftRailDragDropOutcome.Remove -> {
                removePlaceWithUndo(outcome.key, name, outcome.originalIndex)
                liveAnnouncement = Res.string.rail_places_removed.format(name).resolve()
            }
            is RaftRailDragDropOutcome.MoveToLast -> {
                saveKeys(outcome.keys)
                liveAnnouncement =
                    Res.string.rail_tab_drag_dropped_at.format(name, labelForKey(outcome.targetKey)).resolve()
            }
            RaftRailDragDropOutcome.StayInPlace -> {
                liveAnnouncement = Res.string.rail_tab_drag_dropped.format(name).resolve()
            }
            is RaftRailDragDropOutcome.Refused,
            RaftRailDragDropOutcome.Cancelled,
            -> {
                liveAnnouncement = Res.string.rail_tab_drag_cancelled.format(name).resolve()
            }
        }
    }

    internal fun cancelDrag() {
        val key = activePointerDrag?.placeKey ?: keyboardDragKey ?: return
        clearDragState()
        liveAnnouncement = Res.string.rail_tab_drag_cancelled.format(labelForKey(key)).resolve()
    }

    internal fun startKeyboardDrag(placeKey: String): Boolean {
        if (isDragging) return false
        val currentKeys = settledKeys()
        keyboardDragKey = placeKey
        keyboardPickupOrder = currentKeys
        previewKeys = currentKeys
        liveAnnouncement = Res.string.rail_tab_drag_picked_up.format(labelForKey(placeKey)).resolve()
        return true
    }

    internal fun stepKeyboardDrag(placeKey: String, direction: Int): Boolean {
        if (keyboardDragKey != placeKey) return false
        val builtIns = builtInByPlaceId()
        val currentKeys = previewKeys ?: settledKeys()
        val visibleKeys = shownPlaces(builtIns).map { it.key }
        val next = raftRailMovePlace(currentKeys, visibleKeys, placeKey, direction) ?: return false
        previewKeys = next
        val movedTo = next.indexOf(placeKey)
        val target = keyboardPickupOrder?.getOrNull(movedTo) ?: placeKey
        liveAnnouncement = Res.string.rail_tab_drag_over.format(labelForKey(placeKey), labelForKey(target)).resolve()
        return true
    }

    internal fun dropKeyboardDrag(placeKey: String) {
        if (keyboardDragKey != placeKey) return
        val staged = previewKeys
        val pickup = keyboardPickupOrder
        val fullKeys = settledKeys()
        clearDragState()
        val name = labelForKey(placeKey)
        if (staged != null && staged != fullKeys) {
            saveKeys(staged)
            val target = pickup?.getOrNull(staged.indexOf(placeKey))
            liveAnnouncement =
                if (target != null && target != placeKey) {
                    Res.string.rail_tab_drag_dropped_at.format(name, labelForKey(target)).resolve()
                } else {
                    Res.string.rail_tab_drag_dropped.format(name).resolve()
                }
        } else {
            liveAnnouncement = Res.string.rail_tab_drag_dropped.format(name).resolve()
        }
    }

    internal fun selectPlace(itemKey: String, fromMore: Boolean) {
        if (shouldSuppressClick()) return
        if (fromMore) {
            openedByDrag = false
            moreOpen = false
            moreMenuBounds = null
        }
        onSelectItem(itemKey)
    }

    private fun fitsOnRail(
        candidateKeys: List<String>,
        key: String,
        builtIns: Map<String, RaftBottomTabItemPayload>,
        fixedCount: Int,
    ): Boolean {
        val drawn =
            raftRailPlacesForKeys(candidateKeys, snapshot.places, snapshot.placeableApps)
                .filter { it.isApp || it.key in builtIns }
        val count = raftVisiblePlaceCount(drawn.size, railSlotCapacity, fixedCount)
        val index = drawn.indexOfFirst { it.key == key }
        return index in 0 until count
    }

    private fun clearDragState() {
        stopDwell()
        activePointerDrag = null
        keyboardDragKey = null
        keyboardPickupOrder = null
        previewKeys = null
        refusal = null
        pinnedMoreAnchorTopPx = null
        lastDragEndedMark = timeSource.markNow()
        if (openedByDrag) {
            openedByDrag = false
            moreOpen = false
            moreMenuBounds = null
        }
    }

    private fun stopDwell() {
        dwellJob?.cancel()
        dwellJob = null
    }

    private fun saveKeys(keys: List<String>) {
        val targetServerId = serverId.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            railPlacesStore.savePlaces(targetServerId, withRaftRailKeptPlaceKeys(keys))
        }
    }

    private fun removePlaceWithUndo(placeKey: String, label: String, originalIndex: Int) {
        val targetServerId = serverId.takeIf { it.isNotBlank() } ?: return
        val next = raftRailRemovePlace(settledKeys(), placeKey)
        if (next == settledKeys()) return
        scope.launch {
            railPlacesStore.savePlaces(targetServerId, next)
        }
        val removedText = Res.string.rail_places_removed.format(label).resolve()
        val undoLabel = Res.string.common_undo.desc().resolve()
        bridge.toastWithAction(
            content = removedText,
            actionLabel = undoLabel,
            onAction = {
                val latestSnapshot = railPlacesStore.state.value.byServer[targetServerId]?.snapshot ?: snapshot
                val currentKeys = withRaftRailKeptPlaceKeys(latestSnapshot.places.map { it.key })
                val restored = raftRailUndoInsertKey(currentKeys, placeKey, originalIndex)
                if (restored != currentKeys) {
                    scope.launch { railPlacesStore.savePlaces(targetServerId, restored) }
                }
            },
        )
    }

    private fun formatLandedAnnouncement(name: String, outcome: RaftRailDragDropOutcome.Save): String {
        val neighbor = outcome.neighborKey
        if (neighbor == null) {
            return if (outcome.added) {
                Res.string.rail_places_drag_added.format(name).resolve()
            } else {
                Res.string.rail_tab_drag_dropped.format(name).resolve()
            }
        }
        val targetName = labelForKey(neighbor)
        return when {
            outcome.added && outcome.placedAfterNeighbor ->
                Res.string.rail_places_drag_added_after.format(name, targetName).resolve()
            outcome.added ->
                Res.string.rail_places_drag_added_before.format(name, targetName).resolve()
            outcome.placedAfterNeighbor ->
                Res.string.rail_places_drag_dropped_after.format(name, targetName).resolve()
            else ->
                Res.string.rail_places_drag_dropped_before.format(name, targetName).resolve()
        }
    }

    @Composable
    internal fun Overlays() {
        RaftRailPlacesMoreOverlay(host = this)
        RaftRailPlacesDragLiftOverlay(host = this)
    }
}

/**
 * Remembers and synchronizes a [RaftDesktopRailPlacesHost] when desktop per-server rail places are
 * active (`rail_places_v0` ON on desktop / non-native-mobile rail); returns null otherwise.
 */
@Composable
internal fun rememberAppShellDesktopRailPlacesHost(
    enabled: Boolean,
    serverId: String?,
    snapshot: RaftRailPlacesSnapshot?,
    baseRailItems: List<RaftBottomTabItemPayload>,
    railPlacesStore: SharedRailPlacesStore,
    bridge: BridgeModule,
    scope: CoroutineScope,
    resolveIconUrl: (String?) -> String?,
    onSelectItem: (String) -> Unit,
    onOpenEdit: () -> Unit,
    closeMoreSignal: Boolean = false,
): RaftDesktopRailPlacesHost? {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
    if (!enabled || cleanServerId == null || snapshot == null) return null
    val haptics = rememberRaftHaptics()
    val host =
        remember(railPlacesStore, bridge, scope, haptics) {
            RaftDesktopRailPlacesHost(
                railPlacesStore = railPlacesStore,
                bridge = bridge,
                scope = scope,
                haptics = haptics,
                initialServerId = cleanServerId,
                initialSnapshot = snapshot,
                initialBaseRailItems = baseRailItems,
            )
        }
    host.serverId = cleanServerId
    host.snapshot = snapshot
    host.baseRailItems = baseRailItems
    host.resolveIconUrl = resolveIconUrl
    host.onSelectItem = onSelectItem
    host.onOpenEdit = onOpenEdit
    LaunchedEffect(cleanServerId) {
        host.resetForContextChange()
    }
    LaunchedEffect(closeMoreSignal) {
        if (closeMoreSignal) host.closeMore()
    }
    DisposableEffect(host) {
        onDispose { host.resetForContextChange() }
    }
    return host
}

/**
 * Desktop per-server rail navigation content (web `RailPlacesNav.tsx`): fixed Search (when shown),
 * visible movable places (Chat & Activity included) in their stored or preview order, and the
 * `More` (`⋯`) overflow trigger that opens [RaftRailPlacesMoreOverlay] and receives drops.
 */
@Composable
internal fun RaftDesktopRailPlacesNav(
    host: RaftDesktopRailPlacesHost,
    availableNavHeightPx: Float,
) {
    val metrics = RaftAppRailDefaults.metrics
    val density = LocalDensity.current
    val itemPx = with(density) { metrics.itemSize.toPx() }
    val gapPx = with(density) { metrics.itemGap.toPx() }
    host.railSlotCapacity = raftRailSlots(availableNavHeightPx, itemPx, gapPx)

    val fixedItems = host.fixedItems()
    val builtIns = host.builtInByPlaceId()
    val shown = host.shownPlaces(builtIns)
    val settledOverflow = host.settledOverflow(builtIns)
    val notOnListBuiltIns = host.notOnListBuiltIns(builtIns)
    val moreBuiltIns =
        settledOverflow.mapNotNull { place -> if (place.isBuiltIn) builtIns[place.key] else null } +
            notOnListBuiltIns
    val moreHasSelected = moreBuiltIns.any { it.selected }
    val moreHasDot = moreBuiltIns.any { (it.attentionDot || it.badgeCount > 0) && !it.selected }
    val dragKey = host.activePointerDrag?.placeKey ?: host.keyboardDragKey
    val showTooltips = !host.isDragging

    Box {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        val bounds = coords.boundsInRoot()
                        val expandedBottom =
                            if (availableNavHeightPx > 0f) {
                                maxOf(bounds.bottom, bounds.top + availableNavHeightPx)
                            } else {
                                bounds.bottom
                            }
                        host.updateNavBounds(Rect(bounds.left, bounds.top, bounds.right, expandedBottom))
                    },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(metrics.itemGap),
        ) {
            fixedItems.forEach { item ->
                key(item.key) {
                    val glyph = raftNavGlyph(RaftNavPresentation.Rail, item.key)
                    RaftAppRailItem(
                        label = item.label,
                        selected = item.selected,
                        onClick = { host.selectPlace(item.key, fromMore = false) },
                        icon = { ink -> RaftNavigationRailIcon(item, glyph, ink) },
                        modifier =
                            Modifier
                                .testTag(RaftRailReorderableTabTagPrefix + item.key)
                                .onGloballyPositioned { host.updateFixedBounds(item.key, it.boundsInRoot()) },
                        enabled = item.enabled,
                        attention = item.attentionDot || item.badgeCount > 0,
                        showTooltip = showTooltips,
                    )
                }
            }
            shown.forEach { place ->
                val item = host.payloadForPlace(place, builtIns) ?: return@forEach
                val isOrigin = dragKey == place.key
                key(place.key) {
                    val glyph = raftNavGlyph(RaftNavPresentation.Rail, item.key)
                    RaftAppRailItem(
                        label = item.label,
                        selected = item.selected,
                        onClick = { host.selectPlace(item.key, fromMore = false) },
                        icon = { ink -> RaftNavigationRailIcon(item, glyph, ink) },
                        modifier =
                            Modifier
                                .testTag(RaftRailReorderableTabTagPrefix + item.key)
                                .alpha(if (isOrigin) 0.4f else 1f)
                                .onGloballyPositioned { host.updateStandingBounds(place.key, it.boundsInRoot()) }
                                .raftRailPlacePointerDrag(
                                    host = host,
                                    placeKey = place.key,
                                    source = RaftRailDragSource.Rail,
                                )
                                .raftDesktopRailKeyboardReorder(host = host, placeKey = place.key),
                        enabled = item.enabled,
                        attention = item.attentionDot || item.badgeCount > 0,
                        showTooltip = showTooltips,
                    )
                }
            }
            RaftAppRailItem(
                label = stringResource(Res.string.rail_places_more),
                selected = host.moreOpen || moreHasSelected,
                onClick = {
                    if (!host.shouldSuppressClick()) {
                        host.toggleMore()
                    }
                },
                icon = { ink ->
                    RaftLucideIcon(
                        name = RaftLucideIconName.Ellipsis,
                        color = raftArgbHex(ink),
                        modifier = Modifier.size(RaftAppRailDefaults.IconSize),
                    )
                },
                modifier =
                    Modifier
                        .testTag(RaftRailReorderableTabTagPrefix + RaftRailMoreItemKey)
                        .onGloballyPositioned { host.updateMoreButtonBounds(it.boundsInRoot()) },
                attention = moreHasDot,
                attentionDescription = stringResource(Res.string.tab_more_attention_dot),
                showTooltip = !host.moreOpen && showTooltips,
            )
        }
        Box(
            Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    if (host.liveAnnouncement.isNotEmpty()) contentDescription = host.liveAnnouncement
                },
        )
    }
}

/**
 * More overflow menu overlay (web `RailMore.tsx`, botiverse/slock #16808 + #16838):
 * - Positioned beside the rail at More's anchor (pinned to [RaftDesktopRailPlacesHost.pinnedMoreAnchorTopPx]
 *   while dragging out of More so the menu never jumps as the rail makes room).
 * - Renders **settled** overflow places + "More places" (not-on-list built-ins and `placeableApps`)
 *   so menu rows never reflow mid-drag; the dragged row dims to 40% in place.
 * - Pinned "Edit navigation bar" footer stays visible below the scrollable places list.
 * - Uses [RaftFloatingOverlayOutsidePointerMode.PassThrough] while a drag is live so pointer drag
 *   events cross freely between the menu and the rail without dismissing the menu.
 */
@Composable
private fun RaftRailPlacesMoreOverlay(host: RaftDesktopRailPlacesHost) {
    if (!host.moreOpen) return
    val builtIns = host.builtInByPlaceId()
    val overflow = host.settledOverflow(builtIns)
    val notOnListBuiltIns = host.notOnListBuiltIns(builtIns)
    val notOnListApps = host.notOnListApps()
    val hasMorePlaces = notOnListBuiltIns.isNotEmpty() || notOnListApps.isNotEmpty()
    val hasAnyPlaces = overflow.isNotEmpty() || hasMorePlaces
    val heldKey = host.activePointerDrag?.placeKey
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    var overlayOriginY by remember { mutableFloatStateOf(0f) }
    var menuHeightPx by remember { mutableFloatStateOf(0f) }

    val edgePadPx = with(density) { RaftAppRailPopoverGap.toPx() }
    val rawAnchorTop = host.pinnedMoreAnchorTopPx ?: host.moreButtonBounds?.top ?: edgePadPx
    val localAnchorTop = rawAnchorTop - overlayOriginY
    val maxTopPx =
        if (windowHeightPx > 0f && menuHeightPx > 0f) {
            (windowHeightPx - menuHeightPx - edgePadPx).coerceAtLeast(edgePadPx)
        } else {
            localAnchorTop.coerceAtLeast(edgePadPx)
        }
    val clampedTopPx = localAnchorTop.coerceIn(edgePadPx, maxTopPx)
    val maxListHeightDp =
        with(density) {
            val budget = if (windowHeightPx > 0f) (windowHeightPx - edgePadPx * 2f - 64.dp.toPx()) else 360.dp.toPx()
            budget.coerceAtLeast(120.dp.toPx()).toDp()
        }

    DisposableEffect(Unit) {
        onDispose { host.updateMoreMenuBounds(null) }
    }

    RaftFloatingOverlayHost(
        onDismiss = {
            if (host.isDragging) {
                host.cancelDrag()
            } else {
                host.closeMore()
            }
        },
        dismissActivationDelayMillis = RaftFloatingOverlayClickTriggeredDismissDelayMillis,
        outsidePointerMode =
            if (host.isPointerDragging) {
                RaftFloatingOverlayOutsidePointerMode.PassThrough
            } else {
                RaftFloatingOverlayOutsidePointerMode.Dismiss
            },
    ) { layout ->
        overlayOriginY = layout.origin.y
        RaftDesignSystemTheme {
            Box(Modifier.fillMaxSize()) {
                RaftMenuSurface(
                    width = RaftMenuMetrics.MinWidth,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = RaftAppRailOuterWidth + RaftAppRailPopoverGap)
                            .offset { IntOffset(0, clampedTopPx.roundToInt()) }
                            .onGloballyPositioned { coords ->
                                menuHeightPx = coords.size.height.toFloat()
                                host.updateMoreMenuBounds(coords.boundsInRoot())
                            }
                            .raftFloatingContentClickTarget(),
                ) {
                    if (hasAnyPlaces) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = maxListHeightDp)
                                    .verticalScroll(rememberScrollState()),
                        ) {
                            overflow.forEach { place ->
                                val item = host.payloadForPlace(place, builtIns) ?: return@forEach
                                key(place.key) {
                                    MorePlaceMenuItem(
                                        host = host,
                                        placeKey = place.key,
                                        item = item,
                                        app = place.app,
                                        dimmed = heldKey == place.key,
                                    )
                                }
                            }
                            if (overflow.isNotEmpty() && hasMorePlaces) {
                                RaftMenuSeparator()
                            }
                            if (hasMorePlaces) {
                                RaftMenuLabel(stringResource(Res.string.rail_places_more_places))
                                notOnListBuiltIns.forEach { item ->
                                    val placeKey = raftRailPlaceKeyForItemKey(item.key) ?: return@forEach
                                    key(placeKey) {
                                        MorePlaceMenuItem(
                                            host = host,
                                            placeKey = placeKey,
                                            item = item,
                                            app = null,
                                            dimmed = heldKey == placeKey,
                                        )
                                    }
                                }
                                notOnListApps.forEach { app ->
                                    val placeKey = "app:${app.clientId}"
                                    val item =
                                        RaftBottomTabItemPayload(
                                            key = placeKey,
                                            label = app.name,
                                            iconName = RaftLucideIconName.Blocks,
                                            selected = false,
                                            enabled = true,
                                            isAppPlace = true,
                                            avatarSource = host.resolveIconUrl(app.iconUrl),
                                            avatarFallbackLabel = app.name,
                                        )
                                    key(placeKey) {
                                        MorePlaceMenuItem(
                                            host = host,
                                            placeKey = placeKey,
                                            item = item,
                                            app = app,
                                            dimmed = heldKey == placeKey,
                                        )
                                    }
                                }
                            }
                        }
                        RaftMenuSeparator()
                    }
                    RaftMenuItem(
                        text = stringResource(Res.string.rail_places_edit),
                        onClick = {
                            if (!host.shouldSuppressClick()) {
                                host.closeMore()
                                host.onOpenEdit()
                            }
                        },
                        leadingIcon = { RaftContentLucideIcon(RaftLucideIconName.SlidersHorizontal, size = 14.dp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MorePlaceMenuItem(
    host: RaftDesktopRailPlacesHost,
    placeKey: String,
    item: RaftBottomTabItemPayload,
    app: RaftRailPlaceApp?,
    dimmed: Boolean,
) {
    val dotDescription = stringResource(Res.string.more_row_attention_dot)
    val hasDot = (item.attentionDot || item.badgeCount > 0) && !item.selected
    RaftMenuItem(
        text = item.label,
        checked = item.selected,
        onClick = { host.selectPlace(item.key, fromMore = true) },
        modifier =
            Modifier
                .testTag(RaftRailMorePlaceTagPrefix + placeKey)
                .alpha(if (dimmed) 0.4f else 1f)
                .raftRailPlacePointerDrag(
                    host = host,
                    placeKey = placeKey,
                    source = RaftRailDragSource.MoreMenu,
                ),
        leadingIcon = {
            if (app != null) {
                RaftConnectedAppLogo(
                    name = app.name,
                    logoUrl = host.resolveIconUrl(app.iconUrl),
                    size = RaftAvatarSize.Xs,
                )
            } else if (raftNavShowsOttie(RaftNavPresentation.Rail, item.key)) {
                HomeAssistantFace(Modifier.size(16.dp), size = 16.dp)
            } else {
                val glyph = raftNavGlyph(RaftNavPresentation.Rail, item.key)
                if (glyph != null) {
                    RaftConversationKindIcon(glyph, size = 16.dp, color = RaftTheme.colors.foregroundMuted)
                } else {
                    RaftContentLucideIcon(name = item.iconName, size = 16.dp)
                }
            }
        },
        trailingIcon =
            if (hasDot) {
                { RaftStatusDot(variant = RaftStatusVariant.Accent, contentDescription = dotDescription) }
            } else {
                null
            },
    )
}

/**
 * Floating drag lift tile (centred on the pointer) and refusal pill shown above the shell while
 * a pointer drag is live (web `DragLift` + `DragRefusalHint`).
 */
@Composable
private fun RaftRailPlacesDragLiftOverlay(host: RaftDesktopRailPlacesHost) {
    val drag = host.activePointerDrag ?: return
    val place = host.placeForKey(drag.placeKey) ?: return
    val builtIns = host.builtInByPlaceId()
    val item = host.payloadForPlace(place, builtIns) ?: return
    val metrics = raftAppRailWindowMetrics()
    val density = LocalDensity.current
    val tilePx = with(density) { metrics.itemSize.toPx() }
    val gapPx = with(density) { RaftAppRailPopoverGap.toPx() }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val refusalText =
        when (host.refusal) {
            RaftRailDragRefusal.SearchStaysFirst -> stringResource(Res.string.rail_places_search_stays_first)
            RaftRailDragRefusal.KeptStayOnRail -> stringResource(Res.string.rail_places_kept_stay_on_rail)
            null -> null
        }

    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        val tileShape = RoundedCornerShape(RaftRadius.Control)
        val pillShape = RoundedCornerShape(RaftRadius.Chip)
        val tileX = (drag.pointerRoot.x - overlayOrigin.x - tilePx / 2f).roundToInt()
        val tileY = (drag.pointerRoot.y - overlayOrigin.y - tilePx / 2f).roundToInt()
        Box(
            modifier =
                Modifier
                    .zIndex(RaftFloatingOverlayHostZIndex + 1f)
                    .fillMaxSize()
                    .onGloballyPositioned { overlayOrigin = it.positionInRoot() },
        ) {
            Box(
                modifier =
                    Modifier
                        .offset { IntOffset(tileX, tileY) }
                        .size(metrics.itemSize)
                        .background(colors.layerPopover, tileShape)
                        .border(RaftStroke.Hairline, colors.lineSeam, tileShape),
                contentAlignment = Alignment.Center,
            ) {
                val glyph = raftNavGlyph(RaftNavPresentation.Rail, item.key)
                RaftNavigationRailIcon(item, glyph, colors.foreground)
            }
            if (refusalText != null) {
                Box(
                    modifier =
                        Modifier
                            .offset { IntOffset(tileX + tilePx.roundToInt() + gapPx.roundToInt(), tileY) }
                            .background(colors.layerHud, pillShape)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    RaftText(
                        text = refusalText,
                        style = RaftTheme.typography.caption,
                        color = colors.foregroundOnHud,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Pointer drag modifier for a desktop rail place or More menu place row:
 * - Fine pointer (mouse/stylus): moves past [RaftReorderDefaults.DragDistance] (6dp) or holds for
 *   [RaftReorderDefaults.HoldMillis] (500ms) to lift.
 * - Touch pointer: waits [RaftReorderDefaults.HoldMillis] (500ms) while staying within
 *   [RaftReorderDefaults.HoldSlop] so scrolling or tapping stays untouched.
 * - Once lifted, consumes all pointer events in [PointerEventPass.Initial] so releasing never
 *   triggers a click or scrolls underneath.
 */
@Composable
private fun Modifier.raftRailPlacePointerDrag(
    host: RaftDesktopRailPlacesHost,
    placeKey: String,
    source: RaftRailDragSource,
): Modifier {
    val currentHost by rememberUpdatedState(host)
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onGloballyPositioned { coordinates[0] = it }
        .pointerInput(placeKey, source) {
            val liftDistancePx = RaftReorderDefaults.DragDistance.toPx()
            val holdSlopPx = RaftReorderDefaults.HoldSlop.toPx()
            fun toRoot(local: Offset): Offset? = coordinates[0]?.takeIf { it.isAttached }?.localToRoot(local)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (currentHost.isDragging) return@awaitEachGesture
                val isTouch = down.type == PointerType.Touch
                val downRoot = toRoot(down.position) ?: down.position
                var lifted = false
                var latestRoot = downRoot
                try {
                    withTimeout(RaftReorderDefaults.HoldMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val currentRoot = toRoot(change.position) ?: change.position
                            latestRoot = currentRoot
                            val moved = (currentRoot - downRoot).getDistance()
                            if (!isTouch && moved > liftDistancePx) {
                                lifted = true
                                break
                            }
                            if (isTouch && moved > holdSlopPx) break
                        }
                    }
                } catch (_: PointerEventTimeoutCancellationException) {
                    lifted = true
                }
                if (!lifted || !currentHost.startPointerDrag(placeKey, source, latestRoot)) {
                    return@awaitEachGesture
                }
                currentHost.updatePointerDrag(latestRoot)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || event.changes.count { it.pressed } > 1) {
                            currentHost.cancelDrag()
                            break
                        }
                        val currentRoot = toRoot(change.position) ?: latestRoot
                        latestRoot = currentRoot
                        if (!change.pressed) {
                            currentHost.finishPointerDrag(currentRoot)
                            break
                        }
                        currentHost.updatePointerDrag(currentRoot)
                    }
                } finally {
                    if (currentHost.activePointerDrag?.placeKey == placeKey) {
                        currentHost.cancelDrag()
                    }
                }
            }
        }
}

/** Keyboard reordering on desktop rail places: Space picks up / drops, Arrows move, Escape cancels. */
private fun Modifier.raftDesktopRailKeyboardReorder(
    host: RaftDesktopRailPlacesHost,
    placeKey: String,
): Modifier =
    onFocusChanged { focus ->
        if (!focus.hasFocus && host.keyboardDragKey == placeKey) {
            host.cancelDrag()
        }
    }.onPreviewKeyEvent { event ->
        val down = event.type == KeyEventType.KeyDown
        val lifted = host.keyboardDragKey
        when {
            event.key == Key.Spacebar -> {
                if (down && lifted == null) {
                    host.startKeyboardDrag(placeKey)
                } else if (down && lifted == placeKey) {
                    host.dropKeyboardDrag(placeKey)
                }
                true
            }
            lifted == placeKey && (event.key == Key.DirectionUp || event.key == Key.DirectionDown) -> {
                if (down) {
                    host.stepKeyboardDrag(placeKey, if (event.key == Key.DirectionUp) -1 else 1)
                }
                true
            }
            lifted == placeKey && event.key == Key.Escape -> {
                if (down) host.cancelDrag()
                true
            }
            else -> false
        }
    }
