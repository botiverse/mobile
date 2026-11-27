package build.raft.app.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.app.RaftLucideIconName
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.StringResource

// web `ArtifactCardMenu.tsx` + `ArtifactHomeMenu.tsx` (`ArtifactMenuContent`) + `artifactMenuKeys.ts`
// (botiverse/slock #16110, menu order v4 #16225, Follow/Unfollow #16357; #proj-workspace:#382 + #395):
// a message's live Artifact card has its own context menu on right-click (non-touch secondary
// pointer) or keyboard Shift+F10 / ContextMenu key. Touch long press, select mode, deleted/preview
// cards, and text selection inside the card stay with the message row's menu. Items on Mobile:
// Open, separator, Copy link, Follow/Unfollow (when the reader can toggle follow on this server;
// Open in new page is dropped in the native App; no Settle).

/** Test tags (web `data-testid`s on `ArtifactMessageCard` / `ArtifactCardMenu`). */
internal object ArtifactCardMenuTags {
    const val Card = "artifact-card"
    const val DeletedCard = "artifact-card-deleted"
    const val Menu = "artifact-card-menu"
    const val Open = "artifact-card-menu-open"
    const val CopyLink = "artifact-card-menu-copy-link"
    const val Follow = "artifact-card-menu-follow"
}

/** One item of a message Artifact card's context menu, in menu order v4 (#16225) + #16357. */
internal enum class ArtifactCardMenuAction {
    Open,
    CopyLink,
    Follow,
}

internal data class ArtifactCardMenuItemSpec(
    val action: ArtifactCardMenuAction,
    val label: StringResource,
    val icon: RaftLucideIconName,
    val testTag: String,
    val dividerBefore: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * The Artifact card context menu's items (web `ArtifactMenuContent` with `place = "message"`,
 * `home = null`, botiverse/slock #16110 + #16225 + #16357, minus `Open in new page` per
 * #proj-workspace:#395): Open, then the #16225 group separator, Copy link, and Follow/Unfollow
 * (omitted when [showFollow] is false or [following] is unknown). No Settle on message cards.
 */
internal fun artifactCardMenuItems(
    following: Boolean? = null,
    showFollow: Boolean = false,
    followPending: Boolean = false,
): List<ArtifactCardMenuItemSpec> =
    buildList {
        add(
            ArtifactCardMenuItemSpec(
                action = ArtifactCardMenuAction.Open,
                label = Res.string.common_open,
                icon = RaftLucideIconName.PanelRightOpen,
                testTag = ArtifactCardMenuTags.Open,
            ),
        )
        add(
            ArtifactCardMenuItemSpec(
                action = ArtifactCardMenuAction.CopyLink,
                label = Res.string.artifacts_menu_copy_link,
                icon = RaftLucideIconName.Link,
                testTag = ArtifactCardMenuTags.CopyLink,
                dividerBefore = true,
            ),
        )
        if (showFollow && following != null) {
            add(
                ArtifactCardMenuItemSpec(
                    action = ArtifactCardMenuAction.Follow,
                    label = if (following) Res.string.artifacts_menu_unfollow else Res.string.artifacts_menu_follow,
                    icon = if (following) RaftLucideIconName.UnfollowArtifact else RaftLucideIconName.FollowArtifact,
                    testTag = ArtifactCardMenuTags.Follow,
                    dividerBefore = false,
                    enabled = !followPending,
                ),
            )
        }
    }

/**
 * Whether a pointer press on an [ArtifactMessageCard] opens the Artifact's own context menu (web
 * `ArtifactCardMenu.tsx`, botiverse/slock #16110): a non-touch secondary button down (mouse,
 * trackpad, stylus) while the card's menu is enabled (not a preview, not in select mode) and no
 * text is selected inside the card. A finger's press never opens the card's menu.
 */
internal fun artifactCardOpensContextMenuForPointer(
    pointerType: PointerType,
    secondaryButtonPressed: Boolean,
    pressStarted: Boolean,
    menuEnabled: Boolean,
    hasTextSelectionInCard: Boolean = false,
): Boolean =
    menuEnabled &&
        !hasTextSelectionInCard &&
        threadMessageIsContextClick(
            pointerType = pointerType,
            secondaryButtonPressed = secondaryButtonPressed,
            pressStarted = pressStarted,
        )

/**
 * Whether a key event on a focused [ArtifactMessageCard] opens its context menu (web
 * `artifactMenuKeys.ts` `artifactMenuKey`, botiverse/slock #16110): `ContextMenu` (`Key.Menu`) or
 * `Shift+F10` on key down, while the card's menu is enabled.
 */
internal fun artifactCardOpensContextMenuForKey(
    type: KeyEventType,
    key: Key,
    shiftPressed: Boolean,
    menuEnabled: Boolean,
): Boolean =
    menuEnabled &&
        type == KeyEventType.KeyDown &&
        (key == Key.Menu || (key == Key.F10 && shiftPressed))

/** A finger or stylus press: long press on the card forwards to the message row's menu. */
internal fun artifactCardLongPressPointer(pointerType: PointerType): Boolean =
    pointerType == PointerType.Touch || pointerType == PointerType.Stylus || pointerType == PointerType.Unknown

/** Card-local pixel anchor for a pointer-opened Artifact card menu, clamped inside the card. */
internal fun artifactCardPointerMenuAnchorPx(position: Offset, cardSize: IntSize): IntOffset {
    val maxX = (cardSize.width - 1).coerceAtLeast(0)
    val maxY = (cardSize.height - 1).coerceAtLeast(0)
    return IntOffset(
        x = position.x.roundToInt().coerceIn(0, maxX),
        y = position.y.roundToInt().coerceIn(0, maxY),
    )
}

/**
 * Card-local pixel anchor for a keyboard-opened Artifact card menu (web `artifactMenuKeys.ts`
 * `openAt`: `min(24, width / 2)` by `min(24, height / 2)` inside the card).
 */
internal fun artifactCardKeyboardMenuAnchorPx(cardSize: IntSize, insetPx: Int): IntOffset {
    val maxX = (cardSize.width - 1).coerceAtLeast(0)
    val maxY = (cardSize.height - 1).coerceAtLeast(0)
    val x = minOf(insetPx, cardSize.width / 2).coerceIn(0, maxX)
    val y = minOf(insetPx, cardSize.height / 2).coerceIn(0, maxY)
    return IntOffset(x, y)
}

/** web `artifactMenuKeys.ts`: 24px inset from the card's top-left when opened by keyboard. */
internal val ArtifactCardKeyboardMenuInset: Dp = 24.dp

/** web `ArtifactMenuContent` `min-w-48`. */
private val ArtifactCardMenuMinWidth: Dp = 192.dp

/** Non-state holder for the card's placed coordinates, so scroll positioning never recomposes. */
internal class ArtifactCardBoundsHolder {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Child tooltip targets inside an [ArtifactMessageCard] (Settled tag): touch long press inside
 * them keeps its `RaftTooltip` instead of forwarding to the message row's menu.
 */
internal class ArtifactCardTooltipRegions {
    var settledTag: LayoutCoordinates? = null

    fun containsCardOffset(card: LayoutCoordinates, offset: Offset): Boolean =
        contains(card, settledTag, offset)

    private fun contains(card: LayoutCoordinates, child: LayoutCoordinates?, offset: Offset): Boolean {
        val target = child?.takeIf { it.isAttached } ?: return false
        if (!card.isAttached) return false
        val width = target.size.width.toFloat()
        val height = target.size.height.toFloat()
        if (width <= 0f || height <= 0f) return false
        val local = target.localPositionOf(card, offset)
        return local.x in 0f..width && local.y in 0f..height
    }
}

/**
 * Pointer handling on an [ArtifactMessageCard] (Initial pass, ahead of the card's click):
 * - A non-touch right-click opens the Artifact card's own menu at the pointer and consumes the
 *   press so the card does not activate.
 * - A touch/stylus long press outside child tooltip targets forwards to the message row's menu
 *   ([childLongPress]) and consumes the rest of the press so releasing does not open the Artifact.
 */
@Composable
internal fun Modifier.artifactCardGestures(
    menuEnabled: Boolean,
    tooltipRegions: ArtifactCardTooltipRegions,
    cardCoordinates: () -> LayoutCoordinates?,
    childLongPress: ThreadMessageChildLongPressHandler?,
    onOpenContextMenu: (Offset) -> Unit,
): Modifier {
    val currentMenuEnabled by rememberUpdatedState(menuEnabled)
    val currentCardCoordinates by rememberUpdatedState(cardCoordinates)
    val currentChildLongPress by rememberUpdatedState(childLongPress)
    val currentOnOpenContextMenu by rememberUpdatedState(onOpenContextMenu)
    return pointerInput(tooltipRegions) {
        awaitPointerEventScope {
            var pending: PointerEvent? = null
            while (true) {
                val event = pending ?: awaitPointerEvent(PointerEventPass.Initial)
                pending = null
                val press = event.changes.firstOrNull() ?: continue
                if (!event.changes.all { it.changedToDownIgnoreConsumed() }) continue
                if (
                    artifactCardOpensContextMenuForPointer(
                        pointerType = press.type,
                        secondaryButtonPressed = event.buttons.isSecondaryPressed,
                        pressStarted = true,
                        menuEnabled = currentMenuEnabled,
                    )
                ) {
                    event.changes.forEach { it.consume() }
                    currentOnOpenContextMenu(press.position)
                    // Consume moves and release of this right-click; if the popup window took the
                    // release, replay the next fresh down immediately instead of eating it.
                    while (true) {
                        val next = awaitPointerEvent(PointerEventPass.Initial)
                        if (next.changes.any { it.changedToDownIgnoreConsumed() }) {
                            pending = next
                            break
                        }
                        next.changes.forEach { it.consume() }
                        if (next.changes.none { it.pressed }) break
                    }
                    continue
                }
                val longPressHandler = currentChildLongPress ?: continue
                if (event.buttons.isSecondaryPressed || event.changes.size != 1 || !artifactCardLongPressPointer(press.type)) {
                    continue
                }
                val sourceAtDown = currentCardCoordinates()?.takeIf { it.isAttached } ?: continue
                if (tooltipRegions.containsCardOffset(sourceAtDown, press.position)) continue
                val touchSlop = viewConfiguration.touchSlop
                val endedEarly =
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var ended = false
                        while (!ended) {
                            val next = awaitPointerEvent(PointerEventPass.Initial)
                            val change = next.changes.firstOrNull { it.id == press.id }
                            ended =
                                next.changes.size > 1 ||
                                    change == null ||
                                    !change.pressed ||
                                    (change.position - press.position).getDistance() > touchSlop
                        }
                        true
                    }
                if (endedEarly != null) continue
                val source = currentCardCoordinates()?.takeIf { it.isAttached } ?: continue
                if (!longPressHandler.onChildLongPress(source, press.position)) continue
                // Long press opened the message row's menu: eat the release so the card doesn't open.
                while (true) {
                    val next = awaitPointerEvent(PointerEventPass.Initial)
                    if (next.changes.any { it.changedToDownIgnoreConsumed() }) {
                        pending = next
                        break
                    }
                    next.changes.forEach { it.consume() }
                    if (next.changes.none { it.pressed }) break
                }
            }
        }
    }
}

/**
 * The Artifact card context menu, anchored at [anchor] inside the card's outer Box. Stays composed
 * while closed (`anchor == null`) so [RaftDropdownMenu]'s [returnFocusTo] restores keyboard focus
 * to the card when dismissed.
 */
@Composable
internal fun ArtifactCardContextMenuAnchor(
    anchor: IntOffset?,
    returnFocusTo: FocusRequester,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCopyLink: () -> Unit,
    following: Boolean? = null,
    showFollow: Boolean = false,
    followPending: Boolean = false,
    onToggleFollow: ((Boolean) -> Unit)? = null,
) {
    val placedAnchor = anchor ?: IntOffset.Zero
    Box(Modifier.offset { placedAnchor }.size(1.dp)) {
        RaftDropdownMenu(
            expanded = anchor != null,
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag(ArtifactCardMenuTags.Menu),
            minWidth = ArtifactCardMenuMinWidth,
            offset = 0.dp,
            returnFocusTo = returnFocusTo,
        ) {
            artifactCardMenuItems(
                following = following,
                showFollow = showFollow && onToggleFollow != null,
                followPending = followPending,
            ).forEach { item ->
                if (item.dividerBefore) RaftMenuSeparator()
                RaftMenuItem(
                    text = stringResource(item.label),
                    leadingIcon = { RaftSlotIcon(item.icon) },
                    enabled = item.enabled,
                    modifier = Modifier.testTag(item.testTag),
                    onClick = {
                        onDismiss()
                        when (item.action) {
                            ArtifactCardMenuAction.Open -> onOpen()
                            ArtifactCardMenuAction.CopyLink -> onCopyLink()
                            ArtifactCardMenuAction.Follow -> if (following != null) onToggleFollow?.invoke(!following)
                        }
                    },
                )
            }
        }
    }
}
