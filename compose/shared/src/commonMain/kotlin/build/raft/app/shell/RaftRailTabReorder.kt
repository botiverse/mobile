package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftRailTabIds
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftAppRailDefaults
import build.raft.designsystem.components.RaftReorderDefaults
import build.raft.designsystem.components.RaftReorderState
import build.raft.designsystem.components.raftReorderableItem
import build.raft.designsystem.components.rememberRaftReorderState

// The wide rail's tabs in the person's own order (web SortableRailTabs, botiverse/slock #10279).
// The saved order uses the web's tab ids (RaftRailTabIds); the rail's items use the app's keys.

/** The web's rail tab id for a rail item [key]: Search and Chat differ, the others match. */
internal fun raftRailTabIdForKey(key: String): String? =
    when (key) {
        AppShellRailSearchKey -> "search"
        RaftAppDestination.Chat.id -> "chat"
        else -> key.takeIf { it in RaftRailTabIds && it != "search" && it != "chat" }
    }

/**
 * [items] in the saved [order] (the web's ids, [RaftRailTabIds]); an item with no web id keeps
 * its place after them. Tabs of [order] the rail doesn't draw are skipped.
 */
internal fun List<RaftBottomTabItemPayload>.orderedLikeRailTabOrder(order: List<String>): List<RaftBottomTabItemPayload> =
    sortedBy { item -> raftRailTabIdForKey(item.key)?.let(order::indexOf)?.takeIf { it >= 0 } ?: Int.MAX_VALUE }

/** The rail's reordering: [onOrderChange] gets the drawn items' keys in their new order. */
@Immutable
internal data class RaftRailTabReorder(val onOrderChange: (List<String>) -> Unit)

internal const val RaftRailReorderableTabTagPrefix = "rail-tab-"

/**
 * The rail's destinations, draggable (web SortableRailTabs). A finger or mouse that moves 6dp
 * ([RaftReorderDefaults.DragDistance], dnd-kit's distance) lifts a tab at once, and a 500ms hold
 * lifts it too; it moves up and down only and stays within the rail; letting go saves the order.
 * A tap or click still opens the tab. A screen reader gets Move up / Move down; a keyboard picks
 * the focused tab up with Space, moves it with the arrows, drops it with Space and cancels with
 * Escape (Enter still opens it), with the web's announcements.
 */
@Composable
internal fun RaftRailReorderableTabs(
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
    reorder: RaftRailTabReorder,
) {
    val metrics = RaftAppRailDefaults.metrics
    val state = rememberRaftReorderState(items.map { it.key }, gap = metrics.itemGap, clampToEnds = true)
    val byKey = items.associateBy { it.key }
    val commit by rememberUpdatedState(reorder.onOrderChange)
    val moveUp = stringResource(Res.string.rail_tab_move_up)
    val moveDown = stringResource(Res.string.rail_tab_move_down)
    var announcement by remember { mutableStateOf("") }
    // The order when the keyboard picked a tab up: whose slot it is over.
    val keyboardPickup = remember { arrayOfNulls<List<String>>(1) }
    val nameOf: (String) -> String = { key -> byKey[key]?.label ?: key }
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(metrics.itemGap),
        ) {
            state.order.forEach { itemKey ->
                val item = byKey[itemKey] ?: return@forEach
                key(itemKey) {
                    RaftNavigationRailTabItem(
                        item = item,
                        onSelect = onSelect,
                        modifier =
                            Modifier
                                .testTag(RaftRailReorderableTabTagPrefix + itemKey)
                                .raftReorderableItem(
                                    state = state,
                                    key = itemKey,
                                    onOrderChange = { commit(it) },
                                    moveUpLabel = moveUp,
                                    moveDownLabel = moveDown,
                                    enabled = items.size > 1,
                                    dragDistance = RaftReorderDefaults.DragDistance,
                                )
                                .raftRailTabKeyboardReorder(
                                    state = state,
                                    key = itemKey,
                                    pickup = keyboardPickup,
                                    nameOf = nameOf,
                                    announce = { announcement = it },
                                    commit = { commit(it) },
                                ),
                    )
                }
            }
        }
        // The keyboard reorder's announcements (web dnd-kit's live region): a 1dp node, so it
        // never covers the tabs for touch exploration.
        Box(
            Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    if (announcement.isNotEmpty()) contentDescription = announcement
                },
        )
    }
}

/**
 * Keyboard reordering of the focused tab (web SortableRailTabs' KeyboardSensor): Space picks it
 * up and drops it, the arrows move it a slot, Escape puts it back. Space never clicks the tab
 * (Enter opens it). [announce] gets each step's words for the live region.
 */
private fun Modifier.raftRailTabKeyboardReorder(
    state: RaftReorderState,
    key: String,
    pickup: Array<List<String>?>,
    nameOf: (String) -> String,
    announce: (String) -> Unit,
    commit: (List<String>) -> Unit,
): Modifier =
    onFocusChanged { focus ->
        // Focus left a tab the keyboard holds: it goes back.
        if (!focus.hasFocus && pickup[0] != null && state.liftedKey == key) {
            pickup[0] = null
            state.cancel()
            announce(Res.string.rail_tab_drag_cancelled.format(nameOf(key)).resolve())
        }
    }.onPreviewKeyEvent { event ->
        val pickedUpFrom = pickup[0].orEmpty()
        val lifted = state.liftedKey
        val down = event.type == KeyEventType.KeyDown
        when {
            event.key == Key.Spacebar -> {
                if (down && lifted == null && state.lift(key)) {
                    pickup[0] = state.order
                    announce(Res.string.rail_tab_drag_picked_up.format(nameOf(key)).resolve())
                } else if (down && lifted == key) {
                    val target = pickedUpFrom.getOrNull(state.order.indexOf(key))
                    pickup[0] = null
                    state.drop()?.let(commit)
                    announce(
                        if (target != null && target != key) {
                            Res.string.rail_tab_drag_dropped_at.format(nameOf(key), nameOf(target)).resolve()
                        } else {
                            Res.string.rail_tab_drag_dropped.format(nameOf(key)).resolve()
                        },
                    )
                }
                true
            }
            lifted == key && (event.key == Key.DirectionUp || event.key == Key.DirectionDown) -> {
                if (down) {
                    val by = if (event.key == Key.DirectionUp) -1 else 1
                    if (state.stepLifted(by)) {
                        val target = pickedUpFrom.getOrNull(state.order.indexOf(key)) ?: key
                        announce(Res.string.rail_tab_drag_over.format(nameOf(key), nameOf(target)).resolve())
                    }
                }
                true
            }
            lifted == key && event.key == Key.Escape -> {
                if (down) {
                    pickup[0] = null
                    state.cancel()
                    announce(Res.string.rail_tab_drag_cancelled.format(nameOf(key)).resolve())
                }
                true
            }
            else -> false
        }
    }
