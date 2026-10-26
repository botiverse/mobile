package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import build.raft.designsystem.theme.LocalRaftContentColor
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

// raft-ui MobileNav (raft-design #712, design D1a, #proj-raft-ui task #152): the phone bar's
// last tab is the current server and opens the server popover (the "server panel"), not a
// page. A popover (a dialog), not an ARIA menu. Mirrors the raft-ui parts:
// - MobileNavItem                 -> RaftMobileNavItem
// - MobileNavServerPopover        -> RaftMobileNavServerPopover (+ RaftMobileNavServerPopoverState)
// - MobileNavServerItem           -> RaftMobileNavServerPopoverItem
// - MobileNavServerPopoverContent -> RaftMobileNavServerPopoverContent
// - MobileNavServerPopoverList / Group / Row / Destinations / Destination -> Raft-prefixed, same names
// The parts carry no business logic: the server list, the rows and the dot come from the app.

/** raft-ui MobileNav metrics: the phone's edge-to-edge bottom bar (stdrc 2026-10-21). */
object RaftMobileNavDefaults {
    /** The bar, without the bottom safe area. */
    val BarHeight: Dp = 58.dp

    /** From the bar's top seam to the tab pills. */
    val TopPadding: Dp = 7.dp

    /** The slot every tab holds: the selected tab fills it with the `selected` sticker. */
    val PillWidth: Dp = 56.dp
    val PillHeight: Dp = 32.dp
    val PillRadius: Dp = 9.dp
    val PillBorder: Dp = 2.dp

    /** A tab's icon. */
    val IconSize: Dp = 23.dp

    /** The server tab's avatar: raft-ui draws it at 22px, level with the other tabs' icons. */
    val ServerAvatarSize: Dp = 22.dp

    /** Pill to label. */
    val LabelGap: Dp = 4.dp
    val LabelFontSize = 11.5.sp
    val LabelLineHeight = 14.sp

    /**
     * The attention dot on the pill's top-right corner, from the slot's top-left: raft-ui `Status
     * sm` (8px), the dot on an icon's corner (raft-design #722), centred where the 9px one was.
     */
    val DotOffsetX: Dp = 44.5.dp
    val DotOffsetY: Dp = (-1.5).dp

    /** The wide rail's cell (touch target) and its visual tile. */
    val RailCellSize: Dp = 48.dp
    val RailTileSize: Dp = 40.dp
    val RailServerAvatarSize: Dp = 27.dp

    /** The server tab's hold: raft-ui's long-press menus open after 500ms… */
    const val LongPressMillis: Long = 500L

    /** …and a finger that moves more than 10px first is a scroll, not a hold. */
    val LongPressSlop: Dp = 10.dp

    /** The server popover: 8px from each side of the bar and 8px above it. */
    val ServerPopoverGap: Dp = 8.dp

    /** The server popover is at most this share of the screen tall. */
    const val ServerPopoverMaxHeightFraction: Float = 0.6f

    /** The server list while a switch is pending (web `opacity-60`). */
    const val ServerPopoverPendingAlpha: Float = 0.6f

    /** A server's avatar in a popover row (raft-ui `size-5`). */
    val ServerPopoverAvatarSize: Dp = 20.dp
}

/**
 * raft-ui `MobileNavServerPopover`'s root: whether the server popover is open, and, while a long
 * press that opened it is still held, which row the finger is over. The popover's rows mark
 * themselves with [raftMobileNavServerPopoverTarget]; keys are the app's (a server id, a page).
 */
@Stable
class RaftMobileNavServerPopoverState {
    private val targets = mutableStateMapOf<String, Rect>()

    var open: Boolean by mutableStateOf(false)
        private set

    /** The row under a held long press, or null. */
    var highlightedKey: String? by mutableStateOf(null)
        private set

    /** True from the long press that opened the popover until the finger lifts. */
    var holding: Boolean by mutableStateOf(false)
        private set

    fun show() {
        open = true
    }

    fun dismiss() {
        open = false
        holding = false
        highlightedKey = null
    }

    fun toggle() {
        if (open) dismiss() else show()
    }

    /** A long press opened the popover and is still held. */
    fun beginHold() {
        open = true
        holding = true
        highlightedKey = null
    }

    /** The held finger is at [rootPosition] (root coordinates, pixels). */
    fun moveHold(rootPosition: Offset) {
        if (!holding) return
        val hit = targets.entries.firstOrNull { (_, bounds) -> bounds.contains(rootPosition) }?.key
        if (hit != highlightedKey) highlightedKey = hit
    }

    /**
     * The held finger lifted: the row it was let go over, or null. Either way the popover stays
     * open (raft-ui `stickIfOpen`); picking the row is the caller's.
     */
    fun endHold(): String? {
        val key = highlightedKey.takeIf { holding }
        holding = false
        highlightedKey = null
        return key
    }

    fun register(key: String, boundsInRoot: Rect) {
        if (targets[key] != boundsInRoot) targets[key] = boundsInRoot
    }

    fun unregister(key: String) {
        targets.remove(key)
        if (highlightedKey == key) highlightedKey = null
    }
}

@Composable
fun rememberRaftMobileNavServerPopoverState(): RaftMobileNavServerPopoverState = remember { RaftMobileNavServerPopoverState() }

/**
 * Marks a row of the server popover as a place a held long press can be let go over, under
 * [key]. Use [RaftMobileNavServerPopoverState.highlightedKey] to draw the row highlighted.
 */
@Composable
fun Modifier.raftMobileNavServerPopoverTarget(state: RaftMobileNavServerPopoverState, key: String): Modifier {
    DisposableEffect(state, key) { onDispose { state.unregister(key) } }
    return onGloballyPositioned { state.register(key, it.boundsInRoot()) }
}

/**
 * raft-ui `MobileNavItem`: one tab of the phone bar, a 56x32 slot holding the [icon] and the
 * label 4dp under it. The selected tab fills the slot with the `selected` sticker (2dp ink
 * border, 9dp radius); a press washes it. [attention] puts the attention pixel on the slot's
 * top-right corner; the selected tab never wears it (nav spec §4). [badge] draws over the slot
 * (a count on its top-right corner, placed by the caller). A disabled tab draws its icon and
 * label in `foreground-disabled` and takes no press. Screen readers read the [label].
 */
@Composable
fun RaftMobileNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (ink: Color) -> Unit,
    modifier: Modifier = Modifier,
    attention: Boolean = false,
    attentionDescription: String? = null,
    enabled: Boolean = true,
    badge: (@Composable BoxScope.() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed = raftIsPressed(source) && enabled
    val colors = RaftTheme.colors
    Column(
        modifier
            .clickable(source, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(RaftMobileNavDefaults.PillWidth, RaftMobileNavDefaults.PillHeight)) {
            RaftMobileNavPill(selected = selected, washed = pressed) {
                icon(
                    when {
                        !enabled -> colors.foregroundDisabled
                        selected -> RaftUiPalette.stickerInk
                        else -> colors.foregroundHint
                    },
                )
            }
            badge?.invoke(this)
            if (attention && !selected) {
                RaftAttentionPixel(
                    attentionDescription,
                    Modifier.align(Alignment.TopStart).offset(RaftMobileNavDefaults.DotOffsetX, RaftMobileNavDefaults.DotOffsetY),
                )
            }
        }
        Spacer(Modifier.height(RaftMobileNavDefaults.LabelGap))
        RaftMobileNavLabel(label, selected, enabled = enabled, readable = true)
    }
}

/**
 * raft-ui `MobileNavServerItem`: the current-server tab. The server's [avatar] (22dp, level
 * with the other tabs' icons) and its [label], truncated. It opens the server popover, not a page, so it
 * is never selected; it shows the `fill-medium` fill while [state] is open. [attention]
 * lights the attention dot on the avatar's top-right corner: only "another server has
 * unread", as on Home's WorkspaceButton (a computer or a feedback reply dots its own
 * destination in the popover instead).
 *
 * - A press toggles the popover.
 * - Holding still for 500ms opens it (a haptic tick); moving more than 10dp first cancels the
 *   hold. Lifting the finger after the hold leaves the popover open, unless it was let go over
 *   a popover row ([raftMobileNavServerPopoverTarget]): then [onHoldRelease] gets that row's key,
 *   so one gesture can switch servers.
 * - [compact]: the wide rail's 48dp cell, the avatar (27dp) on a 40dp tile, no label.
 *
 * Screen readers get one button named [contentDescription] ("Current server: Raft HQ"),
 * "collapsed"/"expanded" by [expandedDescription]/[collapsedDescription], and a long-press
 * action named [longPressLabel]. It is focusable, and Enter or Space from a hardware keyboard
 * toggles the popover like a tap; pass [focusRequester] to put focus back on it (returning
 * from a page the panel opened).
 */
@Composable
fun RaftMobileNavServerPopoverItem(
    label: String,
    state: RaftMobileNavServerPopoverState,
    avatar: @Composable (size: Dp) -> Unit,
    modifier: Modifier = Modifier,
    attention: Boolean = false,
    attentionDescription: String? = null,
    contentDescription: String = label,
    longPressLabel: String? = null,
    expandedDescription: String? = null,
    collapsedDescription: String? = null,
    onHoldRelease: (key: String) -> Unit = {},
    compact: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val washed = pressed || LocalRaftPressedPreview.current
    val haptics = LocalHapticFeedback.current
    val currentOnHoldRelease by rememberUpdatedState(onHoldRelease)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val clickSource = remember { MutableInteractionSource() }
    val gesture =
        Modifier
            .onGloballyPositioned { coordinates = it }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // The design system's click semantics: focusable, Enter / Space / D-pad centre from a
            // hardware keyboard and the screen reader's click all toggle the popover like a tap
            // (sindresorhus on #3509). Touches are the gesture's below: it runs first (it is the
            // inner pointer input) and consumes every touch it acts on or turns down, so this
            // never clicks a second time.
            .clickable(clickSource, indication = null, role = Role.Button) { state.toggle() }
            .pointerInput(state) {
                val slop = RaftMobileNavDefaults.LongPressSlop.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Wait for the hold: a lift is a press, a move past the slop is not ours.
                    var outcome = HoldOutcome.Cancelled
                    pressed = true
                    // The press ends however the wait does, a cancelled gesture included (the
                    // tab leaving, the state changing): the tab never stays washed.
                    try {
                        withTimeout(RaftMobileNavDefaults.LongPressMillis) {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || change.isConsumed) {
                                    outcome = HoldOutcome.Cancelled
                                    break
                                }
                                if (!change.pressed) {
                                    change.consume()
                                    outcome = HoldOutcome.Tap
                                    break
                                }
                                if ((change.position - down.position).getDistance() > slop) {
                                    // Not a press: consumed so the click below doesn't take it on lift.
                                    change.consume()
                                    outcome = HoldOutcome.Cancelled
                                    break
                                }
                            }
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        // AwaitPointerEventScope's own withTimeout (it shadows kotlinx's here)
                        // throws this, not kotlinx's TimeoutCancellationException.
                        outcome = HoldOutcome.Hold
                    } finally {
                        pressed = false
                    }
                    when (outcome) {
                        HoldOutcome.Tap -> {
                            state.toggle()
                            return@awaitEachGesture
                        }
                        HoldOutcome.Cancelled -> return@awaitEachGesture
                        // A hold while the panel is already open does nothing (Kirby 10-22): its
                        // lift is swallowed so the click below doesn't close the panel.
                        HoldOutcome.Hold ->
                            if (state.open) {
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                                return@awaitEachGesture
                            }
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.beginHold()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        coordinates?.takeIf { it.isAttached }?.localToRoot(change.position)?.let(state::moveHold)
                        if (!change.pressed) {
                            state.endHold()?.let(currentOnHoldRelease)
                            return@awaitEachGesture
                        }
                    }
                    state.endHold()
                }
            }
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                val stateWord = if (state.open) expandedDescription else collapsedDescription
                if (stateWord != null) stateDescription = stateWord
                onLongClick(label = longPressLabel) {
                    if (!state.open) state.show()
                    true
                }
            }
    if (compact) {
        Box(modifier.size(RaftMobileNavDefaults.RailCellSize).then(gesture), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(RaftMobileNavDefaults.RailTileSize)
                    .background(raftMobileNavServerWash(washed, state.open), RoundedCornerShape(RaftRadius.Control)),
                contentAlignment = Alignment.Center,
            ) {
                RaftMobileNavServerAvatar(RaftMobileNavDefaults.RailServerAvatarSize, attention, attentionDescription, RaftTheme.colors.layerFrame, avatar)
            }
        }
        return
    }
    Column(modifier.then(gesture), horizontalAlignment = Alignment.CenterHorizontally) {
        RaftMobileNavPill(selected = false, washed = washed, open = state.open) {
            RaftMobileNavServerAvatar(
                RaftMobileNavDefaults.ServerAvatarSize,
                attention,
                attentionDescription,
                RaftTheme.colors.layerCard,
                avatar,
            )
        }
        Spacer(Modifier.height(RaftMobileNavDefaults.LabelGap))
        RaftMobileNavLabel(label, selected = false)
    }
}

private enum class HoldOutcome { Tap, Hold, Cancelled }

/** The avatar with the attention dot centred on its top-right corner (AvatarBadge `offset={["100%", 0]}`). */
@Composable
private fun RaftMobileNavServerAvatar(
    size: Dp,
    attention: Boolean,
    attentionDescription: String?,
    cutout: Color,
    avatar: @Composable (size: Dp) -> Unit,
) {
    Box(Modifier.size(size)) {
        Box(Modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) { avatar(size) }
        if (attention) {
            val edge = RaftAvatarSize.Sm.badgeEdge
            Box(Modifier.align(Alignment.TopEnd).offset(x = edge / 2, y = -edge / 2)) {
                RaftAvatarAttentionLight(RaftAvatarSize.Sm, cutout = cutout, contentDescription = attentionDescription)
            }
        }
    }
}

/**
 * raft-ui `MobileNavServerPopover`: the layer the server popover opens on, over the page area
 * above the bar. While [state] is open it fills its parent and stands [content] (a
 * [RaftMobileNavServerPopoverContent]) 8dp from each side and 8dp above its bottom edge (the
 * bar's top, plus [bottomInset] when a host bar floats over that area). It is at most 60% of
 * [screenHeight] tall and stops below [topInset] (the top safe area). Dismissing on a tap
 * outside and on Back is the host's (the app's overlay).
 */
@Composable
fun RaftMobileNavServerPopover(
    state: RaftMobileNavServerPopoverState,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    topInset: Dp = 0.dp,
    screenHeight: Dp? = null,
    alignment: Alignment = Alignment.BottomCenter,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!state.open) return
    val gap = RaftMobileNavDefaults.ServerPopoverGap
    BoxWithConstraints(modifier.fillMaxSize()) {
        // raft-ui: max-h-[min(60dvh, available height - safe-area-inset-top)].
        val available = maxHeight - topInset - bottomInset - gap * 2
        val cap = minOf((screenHeight ?: maxHeight) * RaftMobileNavDefaults.ServerPopoverMaxHeightFraction, available)
        Box(
            Modifier.fillMaxSize().padding(start = gap, end = gap, top = topInset + gap, bottom = bottomInset + gap),
            contentAlignment = alignment,
        ) {
            Box(Modifier.heightIn(max = cap.coerceAtLeast(0.dp)), content = content)
        }
    }
}

/**
 * raft-ui `MobileNavServerPopoverContent`: the popover surface (the popover layer:
 * `layer-popover`, container radius, `line-seam` ring, `shadow-float`), as wide as the bar
 * less 8dp each side; a dialog named by [paneTitle] ("Server panel"), not a menu. A column:
 * put a [RaftMobileNavServerPopoverList] first (it takes the room left and scrolls), then,
 * between [RaftMobileNavServerPopoverSeparator]s, a [RaftMobileNavServerPopoverGroup] (the
 * footer actions) and, last and nearest the thumb (stdrc 10-22),
 * [RaftMobileNavServerPopoverDestinations]; those stay in view.
 */
@Composable
fun RaftMobileNavServerPopoverContent(
    modifier: Modifier = Modifier,
    paneTitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    RaftPopoverSurface(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (paneTitle != null) Modifier.semantics { this.paneTitle = paneTitle } else Modifier),
        width = null,
        contentPadding = PaddingValues(0.dp),
        content = content,
    )
}

/**
 * raft-ui `MobileNavServerPopoverList`: the server list; takes the room left and scrolls (4dp
 * padding). With a [reorderState] its rows can be held and dragged into a new order (give each
 * server row [raftReorderableItem]); a lifted row near the list's edge scrolls it. While
 * [enabled] is false (a server switch is pending; web ServerSwitcherMenu `hostedSwitchPending`)
 * the list sits at 60% — pass `enabled = false` to its rows too, so they take no tap or drag.
 */
@Composable
fun ColumnScope.RaftMobileNavServerPopoverList(
    modifier: Modifier = Modifier,
    reorderState: RaftReorderState? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .alpha(if (enabled) 1f else RaftMobileNavDefaults.ServerPopoverPendingAlpha)
            .then(if (reorderState != null) Modifier.raftReorderableViewport(reorderState, scroll) else Modifier)
            .verticalScroll(scroll)
            .padding(4.dp),
        content = content,
    )
}

/** raft-ui `MobileNavServerPopoverGroup`: rows that always stay in view (the footer), 4dp padding. */
@Composable
fun RaftMobileNavServerPopoverGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(4.dp), content = content)
}

/** raft-ui `PopoverSeparator` between the popover's parts: a `line-hairline` rule across it. */
@Composable
fun RaftMobileNavServerPopoverSeparator() {
    Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(RaftTheme.colors.lineHairline))
}

/**
 * raft-ui `MobileNavServerPopoverRow`: one line at the option height of every popup list (the
 * token [RaftMenuItem] uses; 36dp today — it follows if the rule for touch options changes):
 * an optional [leading] (a 20dp avatar or a 16dp icon), the [label] (one line, cut), an
 * optional [description] under it (the server's `/slug`, small muted mono; artin / Kirby
 * 10-22; raft-ui `MobileNavServerPopoverRowDescription` — the second line adds its height), then [trailing] (a check, a count). [current] sets the label bold and marks the row selected.
 * `brand-soft` while pressed, or while a held long press from the tab is over it ([key]).
 */
@Composable
fun RaftMobileNavServerPopoverRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    state: RaftMobileNavServerPopoverState? = null,
    key: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    description: String? = null,
    enabled: Boolean = true,
) {
    val colors = RaftTheme.colors
    val source = remember { MutableInteractionSource() }
    val held = enabled && state != null && key != null && state.highlightedKey == key
    val highlighted = raftIsPressed(source) || held
    val style = RaftTheme.typography.callout.copy(fontWeight = if (current) FontWeight.Bold else FontWeight.Medium)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RaftOptionHeight)
            .then(if (state != null && key != null) Modifier.raftMobileNavServerPopoverTarget(state, key) else Modifier)
            .background(if (highlighted) colors.brandSoft else Color.Transparent, RoundedCornerShape(RaftRadius.Chip))
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (current) this.selected = true }
            // raft-ui `py-option-inset`: a second line adds its own height and nothing else.
            .padding(horizontal = 10.dp, vertical = raftOptionInset(style)),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundMuted) {
                Box(Modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) { leading() }
            }
        }
        Column(Modifier.weight(1f)) {
            RaftText(
                label,
                style = style,
                color = colors.foreground,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                RaftText(
                    description,
                    style = RaftTheme.typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * raft-ui `MobileNavServerPopoverDestinations`: the server's own pages (Members, Computers,
 * Settings) side by side in equal cells (4dp gap and padding).
 */
@Composable
fun RaftMobileNavServerPopoverDestinations(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/**
 * raft-ui `MobileNavServerPopoverDestination`: one of the server's pages, a 20dp [icon] over
 * a short `text-footnote` [label] (cut), chip radius, `brand-soft` while pressed or while a
 * held long press is over it. [attention] puts the attention dot at the icon's top-right
 * (what needs the person on that page: a computer, a feedback reply; never on the tab's
 * avatar), read as [attentionDescription].
 */
@Composable
fun RowScope.RaftMobileNavServerPopoverDestination(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    attention: Boolean = false,
    attentionDescription: String? = null,
    state: RaftMobileNavServerPopoverState? = null,
    key: String? = null,
) {
    val colors = RaftTheme.colors
    val source = remember { MutableInteractionSource() }
    val held = state != null && key != null && state.highlightedKey == key
    val highlighted = raftIsPressed(source) || held
    Box(
        modifier
            .weight(1f)
            .then(if (state != null && key != null) Modifier.raftMobileNavServerPopoverTarget(state, key) else Modifier)
            .background(if (highlighted) colors.brandSoft else Color.Transparent, RoundedCornerShape(RaftRadius.Chip))
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = if (attention && attentionDescription != null) "$label, $attentionDescription" else label
            }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundMuted) {
                Box(Modifier.size(20.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.height(4.dp))
            RaftText(
                label,
                Modifier.clearAndSetSemantics { },
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                color = colors.foreground,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (attention) {
            // raft-ui: the Status dot at top-1, left-[calc(50%+6px)].
            RaftAttentionPixel(null, Modifier.align(Alignment.TopCenter).offset(x = 6.dp + RaftStatusSize.Sm.edge / 2, y = (-4).dp))
        }
    }
}

/** The 56x32 tab slot: the `selected` sticker when [selected], the press wash when [washed]. */
@Composable
private fun RaftMobileNavPill(selected: Boolean, washed: Boolean, open: Boolean = false, content: @Composable () -> Unit) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftMobileNavDefaults.PillRadius)
    val edge = colors.stickerEdge
    Box(
        Modifier
            .size(RaftMobileNavDefaults.PillWidth, RaftMobileNavDefaults.PillHeight)
            .then(
                if (selected) {
                    Modifier
                        .drawBehind {
                            // The die-cut ring outside the sticker: transparent in light, paper in dark.
                            if (edge.alpha > 0f) {
                                val w = RaftStroke.Control.toPx()
                                drawRoundRect(
                                    color = edge,
                                    topLeft = Offset(-w / 2f, -w / 2f),
                                    size = Size(size.width + w, size.height + w),
                                    cornerRadius = CornerRadius(RaftMobileNavDefaults.PillRadius.toPx() + w / 2f),
                                    style = Stroke(w),
                                )
                            }
                        }
                        .background(RaftUiPalette.selected, shape)
                        .border(RaftMobileNavDefaults.PillBorder, RaftUiPalette.stickerInk, shape)
                } else {
                    Modifier
                },
            )
            .then(
                when {
                    washed -> Modifier.background(if (selected) RaftUiPalette.stickerPressed else colors.fillPressed, shape)
                    // raft-ui `data-popup-open:before:bg-fill-medium`: the tab keeps a fill while its popover is open.
                    open -> Modifier.background(colors.fillMedium, shape)
                    else -> Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun raftMobileNavServerWash(pressed: Boolean, open: Boolean): Color =
    when {
        pressed -> RaftTheme.colors.fillPressed
        open -> RaftTheme.colors.fillMedium
        else -> Color.Transparent
    }

/** A tab label: `text-label` at 11.5/14, bold when selected, truncated; it keeps its size at any font scale. */
@Composable
private fun RaftMobileNavLabel(text: String, selected: Boolean, enabled: Boolean = true, readable: Boolean = false) {
    // Sized to the bar's fixed 58dp geometry: like a native tab bar it does not grow with the
    // system font size (it would run out of the bar).
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    RaftText(
        text = text,
        // The server tab names itself (its contentDescription); a plain tab is read by its label.
        modifier = (if (readable) Modifier else Modifier.clearAndSetSemantics { }).padding(horizontal = 2.dp),
        style =
            RaftTheme.typography.label.copy(
                fontSize = RaftMobileNavDefaults.LabelFontSize / fontScale,
                lineHeight = RaftMobileNavDefaults.LabelLineHeight / fontScale,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            ),
        color =
            when {
                !enabled -> RaftTheme.colors.foregroundDisabled
                selected -> RaftTheme.colors.foreground
                else -> RaftTheme.colors.foregroundHint
            },
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The attention dot on an icon's corner (raft-ui `Status sm`): an 8dp `attention` square with the ink edge. */
@Composable
private fun RaftAttentionPixel(contentDescription: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(RaftRadius.Xs)
    Box(
        modifier
            .size(RaftStatusSize.Sm.edge)
            .background(RaftUiPalette.attention, shape)
            .border(RaftStroke.Control, RaftUiPalette.stickerInk, shape)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics {
                        this.contentDescription = contentDescription
                        role = Role.Image
                    }
                } else {
                    Modifier
                },
            ),
    )
}
