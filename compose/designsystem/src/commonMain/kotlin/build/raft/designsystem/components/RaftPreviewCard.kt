package build.raft.designsystem.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** raft-ui PreviewCard metrics (preview-card.tsx, and the web's MentionLink.tsx). */
object RaftPreviewCardDefaults {
    /** `size="profile"`, `w-[300px]`: a person's or an agent's card, and a card that asks for one step (connect an account). */
    val Width: Dp = 300.dp

    /**
     * `size="item"`, `w-[400px]` (raft-ui 0.58, raft-design #939): a card that shows an app's item
     * (its title, with a state or details) and its buttons. Choose by what the card shows, not by
     * its parts. Where the window is narrower, the card takes the room there is.
     */
    val ItemWidth: Dp = 400.dp

    /** `PreviewCardTitle` in `PreviewCardBody`: an item's title wraps to at most this many lines, then ends in an ellipsis. */
    const val ItemTitleMaxLines: Int = 2

    /**
     * An item card's buttons on its one row ([RaftPreviewCardItemActions]): the app's first two
     * actions, or its first link; everything else is in the More menu.
     */
    const val ItemRowButtons: Int = 2

    /** `sideOffset` 6: the trigger's bottom edge to the card's top edge. */
    val SideOffset: Dp = 6.dp

    /** `collisionPadding` 6: how far the card stays inside the window's safe area. */
    val CollisionPadding: Dp = 6.dp

    /** `delay={200}` on the web's mention trigger: how long the pointer rests before it opens. */
    const val OpenDelayMillis: Long = 200L

    /**
     * `closeDelay={120}` on the web's mention trigger: how long it waits after the pointer
     * leaves, so the pointer can cross the 6dp gap from the trigger onto the card.
     */
    const val CloseDelayMillis: Long = 120L

    /**
     * The web's runtime usage card (`RuntimeUsageHoverCard`, `w-[min(390px,…)]`): wider than a
     * profile card, for its meters and Refresh row.
     */
    val WideWidth: Dp = 390.dp
}

/**
 * Opens every [RaftPreviewCard] / [RaftPreviewCardPopup] in the subtree, whatever the pointer
 * does (screenshot fixtures only: an offscreen frame has no pointer to rest on a trigger).
 */
internal val LocalRaftPreviewCardPreview = staticCompositionLocalOf { false }

/**
 * Whether a preview card wants to be open: it is pinned, or the mouse or trackpad pointer is on
 * its trigger or on the card and the card was not just dismissed (a click, Escape, a scroll) with
 * the pointer still on the trigger.
 */
internal fun raftPreviewCardWantsOpen(overTrigger: Boolean, overCard: Boolean, suppressed: Boolean, pinned: Boolean = false): Boolean =
    pinned || (!suppressed && (overTrigger || overCard))

/**
 * Whether a press and its release on a `pinOnClick` trigger are a click that pins the card: the
 * pointer let go where it went down (within [touchSlop]) before [longPressMillis] (a long press
 * is the trigger's own, such as copying).
 */
internal fun raftPreviewCardIsPinClick(distance: Float, heldMillis: Long, touchSlop: Float, longPressMillis: Long): Boolean =
    distance <= touchSlop && heldMillis < longPressMillis

/**
 * How long to wait before an [open] card follows [wantsOpen]: null when it already does,
 * [openDelayMillis] to open, [closeDelayMillis] to close. A change of [wantsOpen] while
 * waiting starts over (the pending switch is dropped), so a pointer that crosses from the
 * trigger onto the card within [closeDelayMillis] keeps it open.
 */
internal fun raftPreviewCardSwitchDelay(open: Boolean, wantsOpen: Boolean, openDelayMillis: Long, closeDelayMillis: Long): Long? =
    when {
        open == wantsOpen -> null
        wantsOpen -> openDelayMillis
        else -> closeDelayMillis
    }

/**
 * The open state of one [RaftPreviewCard] (Base UI PreviewCard.Root): where the pointer is and
 * whether the card shows. [RaftPreviewCard] feeds it from its trigger; a trigger that is part
 * of a text (an @mention span) feeds it with [setTriggerHovered] and shows the card with
 * [RaftPreviewCardPopup].
 */
@Stable
class RaftPreviewCardState(
    val openDelayMillis: Long = RaftPreviewCardDefaults.OpenDelayMillis,
    val closeDelayMillis: Long = RaftPreviewCardDefaults.CloseDelayMillis,
) {
    /** The card shows. */
    var isOpen: Boolean by mutableStateOf(false)
        internal set

    /** A mouse or trackpad pointer is on the trigger (never a finger or a pen). */
    val isTriggerHovered: Boolean get() = hoverOnTrigger

    // Backing state under other names: a property isX or x gets the JVM setter setX, which
    // would clash with setTriggerHovered / setCardHovered.
    private var hoverOnTrigger by mutableStateOf(false)

    internal val isCardHovered: Boolean get() = hoverOnCard

    private var hoverOnCard by mutableStateOf(false)

    /** Dismissed while the pointer was on the trigger: stays shut until it leaves. */
    private var suppressed by mutableStateOf(false)

    /**
     * Pinned open by a click on the trigger ([pin]): it stays when the pointer leaves, until a
     * click outside, Escape or [dismiss] (the card's close button).
     */
    val isPinned: Boolean get() = pinnedOpen

    private var pinnedOpen by mutableStateOf(false)

    /**
     * A pinned card was just closed by a press outside it, in this frame: when that press is on
     * the trigger (a finger: the popup sees it before the trigger does), it must not pin again.
     */
    internal var closedByOutsidePress = false

    /** A pointer is down on the trigger (any pointer): a press there is never "outside". */
    internal var pressOnTrigger = false

    /** The trigger's focus, which a pinned card takes so Escape reaches it. */
    internal val triggerFocus = FocusRequester()

    internal val wantsOpen: Boolean get() = raftPreviewCardWantsOpen(isTriggerHovered, isCardHovered, suppressed, pinnedOpen)

    /**
     * Reports a mouse or trackpad pointer entering ([hovered] true) or leaving the trigger. For a
     * trigger [RaftPreviewCard] does not wrap: report only pointers that hover (not touch, not a
     * pen), as [raftPreviewCardTrigger] does.
     */
    fun setTriggerHovered(hovered: Boolean) {
        hoverOnTrigger = hovered
        if (!hovered) suppressed = false
    }

    internal fun setCardHovered(hovered: Boolean) {
        hoverOnCard = hovered
    }

    /**
     * Opens the card now and keeps it open whatever the pointer does (web `RuntimeUsageHoverCard`
     * pins on click): a click on a `pinOnClick` trigger, by any pointer, a finger too.
     */
    fun pin() {
        pinnedOpen = true
        suppressed = false
        isOpen = true
    }

    /**
     * Closes the card now, without the close delay: a click on the trigger or on a button of
     * the card, Escape, a scroll. It opens again once the pointer has left the trigger and comes
     * back.
     */
    fun dismiss() {
        pinnedOpen = false
        isOpen = false
        hoverOnCard = false
        if (isTriggerHovered) suppressed = true
    }
}

@Composable
fun rememberRaftPreviewCardState(
    openDelayMillis: Long = RaftPreviewCardDefaults.OpenDelayMillis,
    closeDelayMillis: Long = RaftPreviewCardDefaults.CloseDelayMillis,
): RaftPreviewCardState = remember(openDelayMillis, closeDelayMillis) { RaftPreviewCardState(openDelayMillis, closeDelayMillis) }

/**
 * raft-ui PreviewCard (Base UI PreviewCard; `PreviewCardTrigger delay={200} closeDelay={120}`,
 * `PreviewCardContent sideOffset={6} collisionPadding={6}` as the web's @mention uses it): the
 * read-only card that shows who someone is when a mouse or trackpad pointer rests on [trigger]
 * (a mention, a name, an avatar), without leaving the conversation.
 *
 * - Look ([RaftPreviewCardSurface]): 300dp wide, `layer-popover`, `radius-container` (10dp), a
 *   1dp `line-seam` ring, `shadow-float`, content clipped. Fill it with [RaftPreviewCardHeader],
 *   [RaftPreviewCardSeparator], [RaftPreviewCardDescription] and [RaftPreviewCardActions].
 * - Opens after the pointer rests on [trigger] for 200ms; stays open while the pointer is on
 *   [trigger] or on the card; closes 120ms after it has left both (the time to cross the 6dp gap
 *   onto the card). A click on [trigger] (its own action still runs), a click outside, Escape
 *   (where the platform routes it to popups, or while focus is in [trigger]) and a scroll that
 *   moves [trigger] close it at once; it opens again when the pointer comes back.
 * - Only a mouse or trackpad opens it: a finger or a pen never does, and [trigger]'s own taps,
 *   long presses and hover are untouched (nothing is consumed). The card is supplementary, so
 *   whatever it shows must be reachable another way: make [trigger] a link to the profile.
 * - Below [trigger], start-aligned, 6dp away; above it when there is no room below and more
 *   above; kept 6dp inside the window's safe area. Not focusable, no scrim: it never takes focus
 *   and clicks elsewhere go through.
 *
 * [state] holds the open state: hold it to [RaftPreviewCardState.dismiss] the card from one of
 * its buttons. [enabled] false shows [trigger] alone.
 *
 * [pinOnClick] (web `RuntimeUsageHoverCard`, a card with buttons of its own): a click on
 * [trigger] by any pointer, a finger too, opens the card at once and pins it
 * ([RaftPreviewCardState.pin]) instead of closing it; a pinned card stays when the pointer
 * leaves and closes on a click outside, Escape or its own close button. [width] is the card's
 * ([RaftPreviewCardDefaults.WideWidth] for the usage card).
 */
@Composable
fun RaftPreviewCard(
    card: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    state: RaftPreviewCardState = rememberRaftPreviewCardState(),
    enabled: Boolean = true,
    pinOnClick: Boolean = false,
    width: Dp = RaftPreviewCardDefaults.Width,
    trigger: @Composable () -> Unit,
) {
    Box(modifier.raftPreviewCardTrigger(state, enabled, pinOnClick), propagateMinConstraints = true) {
        trigger()
        if (enabled) RaftPreviewCardPopup(state = state, width = width, card = card)
    }
}

/**
 * Makes the element a [RaftPreviewCard] trigger for [state]: a mouse or trackpad pointer
 * entering and leaving it is reported ([RaftPreviewCardState.setTriggerHovered]); a click or a
 * wheel scroll on it dismisses the card, and so does Escape while focus is in it; with
 * [pinOnClick], a click by any pointer pins it instead ([RaftPreviewCardState.pin]). Consumes
 * nothing. [RaftPreviewCard] attaches it; use it with [RaftPreviewCardPopup] for a trigger laid
 * out elsewhere.
 */
fun Modifier.raftPreviewCardTrigger(state: RaftPreviewCardState, enabled: Boolean = true, pinOnClick: Boolean = false): Modifier =
    if (!enabled) {
        this
    } else {
        this
            .onPreviewKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    state.isOpen && event.key == Key.Escape -> {
                        state.dismiss()
                        true
                    }
                    // A focused trigger opens its card from the keyboard, as its click does.
                    pinOnClick && !state.isPinned && (event.key == Key.Enter || event.key == Key.Spacebar) -> {
                        state.pin()
                        true
                    }
                    else -> false
                }
            }
            // After the key handler, so it sees keys while this node has focus (a pinned card).
            .then(if (pinOnClick) Modifier.focusRequester(state.triggerFocus).focusable() else Modifier)
            .pointerInput(state, pinOnClick) {
                awaitPointerEventScope {
                    var downAt: Offset? = null
                    var downMillis = 0L
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (pinOnClick) {
                            when (event.type) {
                                PointerEventType.Press -> {
                                    state.pressOnTrigger = true
                                    // The popup's "outside" press and this one come from two windows
                                    // in either order (Android): if the card was closed by this press
                                    // first, it stays pinned, as when this press came first.
                                    if (state.closedByOutsidePress) state.pin()
                                    // One pointer, and the primary button for a mouse (a right click
                                    // is the trigger's menu).
                                    downAt =
                                        change.position.takeIf {
                                            event.changes.size == 1 &&
                                                (change.type != PointerType.Mouse || event.buttons.isPrimaryPressed)
                                        }
                                    downMillis = change.uptimeMillis
                                }
                                PointerEventType.Release -> {
                                    state.pressOnTrigger = false
                                    val start = downAt
                                    downAt = null
                                    if (
                                        start != null &&
                                        raftPreviewCardIsPinClick(
                                            distance = (change.position - start).getDistance(),
                                            heldMillis = change.uptimeMillis - downMillis,
                                            touchSlop = viewConfiguration.touchSlop,
                                            longPressMillis = viewConfiguration.longPressTimeoutMillis,
                                        )
                                    ) {
                                        state.pin()
                                        // Focus comes to the trigger, so Escape closes the pinned card
                                        // wherever the pointer is (web: a document listener while
                                        // pinned). Only for a mouse: a finger's tap must not take focus
                                        // from a field being typed in (its keyboard would close).
                                        if (change.type == PointerType.Mouse) runCatching { state.triggerFocus.requestFocus() }
                                        // The click pins the card and goes no further: the trigger's
                                        // own click (a sheet, a page) would show the same thing twice.
                                        change.consume()
                                    }
                                }
                            }
                        }
                        if (change.type != PointerType.Mouse) continue
                        when (event.type) {
                            PointerEventType.Enter -> state.setTriggerHovered(true)
                            PointerEventType.Exit -> state.setTriggerHovered(false)
                            PointerEventType.Press -> if (!pinOnClick && (state.isOpen || state.wantsOpen)) state.dismiss()
                            PointerEventType.Scroll -> if (!state.isPinned && (state.isOpen || state.wantsOpen)) state.dismiss()
                        }
                    }
                }
            }
    }

/**
 * The card of [state] in a popup, anchored to the layout it is placed in, or to [anchor] (px, in
 * that layout's coordinates) when given: an @mention's words in a paragraph, whose hover the
 * host reports with [RaftPreviewCardState.setTriggerHovered]. Runs the open and close delays;
 * placement and dismissal as [RaftPreviewCard]. Use one [state] per trigger (key it by the
 * mention), as a moved anchor closes the card.
 */
@Composable
fun RaftPreviewCardPopup(
    state: RaftPreviewCardState,
    anchor: Rect? = null,
    width: Dp = RaftPreviewCardDefaults.Width,
    /**
     * False: [card] draws its own popover surface (an App link's item card, which is a popover
     * card of its own); the popup keeps the placement, delays, hover and dismissal.
     */
    surface: Boolean = true,
    card: @Composable ColumnScope.() -> Unit,
) {
    val preview = LocalRaftPreviewCardPreview.current
    val wantsOpen = state.wantsOpen
    LaunchedEffect(state, wantsOpen) {
        val wait = raftPreviewCardSwitchDelay(state.isOpen, wantsOpen, state.openDelayMillis, state.closeDelayMillis) ?: return@LaunchedEffect
        delay(wait)
        state.isOpen = wantsOpen
    }
    if (!state.isOpen && !preview) return

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val frame = LocalRaftFrameInsets.current
    val area =
        RaftSafeArea(
            left = safe.getLeft(density, layoutDirection),
            top = safe.getTop(density),
            right = safe.getRight(density, layoutDirection),
            bottom = safe.getBottom(density),
        ).clearOf(frame, density)
    // The room round the card for its shadow, only where a popup is a window of its own that
    // clips what it draws outside its bounds (Android). Elsewhere the popup is a layer of the
    // host's window: room there would be a transparent band taking the clicks meant for the
    // messages under it.
    val room = if (RaftPopupClipsDrawing) RaftPopupInsets else null
    val margins =
        with(density) {
            if (room == null) RaftPopupMargins(0, 0, 0) else RaftPopupMargins(room.Horizontal.roundToPx(), room.Top.roundToPx(), room.Bottom.roundToPx())
        }
    val localAnchor = anchor?.roundToIntRect()
    val provider =
        remember(density, area, margins, localAnchor) {
            RaftPreviewCardPositionProvider(
                gap = with(density) { RaftPreviewCardDefaults.SideOffset.roundToPx() },
                windowMargin = with(density) { RaftPreviewCardDefaults.CollisionPadding.roundToPx() },
                insets = margins,
                safeArea = area,
                localAnchor = localAnchor,
                onAnchorMoved = { if (state.isOpen) state.dismiss() },
            )
        }
    DisposableEffect(state) { onDispose { state.setCardHovered(false) } }
    // A page pushed over this one closes the card first (pinned or not).
    RaftDismissOnPageCover { state.dismiss() }
    val scope = rememberCoroutineScope()
    val pinned = state.isPinned
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = {
            // A press on the trigger of a pinned card is not "outside": the trigger's click keeps
            // it pinned (web), so it does not close and open again. A mouse is known by its hover;
            // a finger by its press, when that reached the trigger first.
            if (!(pinned && (state.isTriggerHovered || state.pressOnTrigger))) {
                state.dismiss()
                if (pinned) {
                    state.closedByOutsidePress = true
                    scope.launch {
                        withFrameNanos {}
                        state.closedByOutsidePress = false
                    }
                }
            }
        },
        // Never focusable, pinned or not: a focusable popup takes the clicks and scrolls meant for
        // the page under it (web: neither). Escape reaches a pinned card through its trigger,
        // which takes focus when it pins ([raftPreviewCardTrigger]).
        properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Box(
            if (room == null) {
                Modifier
            } else {
                Modifier.padding(start = room.Horizontal, end = room.Horizontal, top = room.Top, bottom = room.Bottom)
            },
        ) {
            val cardHover = Modifier.pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.firstOrNull()?.type != PointerType.Mouse) continue
                        when (event.type) {
                            PointerEventType.Enter -> state.setCardHovered(true)
                            PointerEventType.Exit -> state.setCardHovered(false)
                            // A pinned card's own content scrolls (web `overflow-y-auto`).
                            PointerEventType.Scroll -> if (!state.isPinned) state.dismiss()
                        }
                    }
                }
            }
            if (surface) {
                RaftPreviewCardSurface(width = width, modifier = cardHover, content = card)
            } else {
                Column(cardHover.width(width), content = card)
            }
        }
    }
}

/**
 * Places the card's surface [gap] below its anchor, start-aligned, above it without room below
 * (when there is more above), [windowMargin] inside the [safeArea] ([raftMenuPosition]). The
 * anchor is the popup's parent layout, or [localAnchor] inside it. Reports [onAnchorMoved] when
 * the anchor moves after the first placement (the list under it scrolled).
 */
private class RaftPreviewCardPositionProvider(
    private val gap: Int,
    private val windowMargin: Int,
    private val insets: RaftPopupMargins,
    private val safeArea: RaftSafeArea,
    private val localAnchor: IntRect?,
    private val onAnchorMoved: () -> Unit,
) : PopupPositionProvider {
    private var placedAt: IntOffset? = null

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchor = localAnchor?.translate(anchorBounds.topLeft) ?: anchorBounds
        val first = placedAt
        if (first == null) {
            placedAt = anchor.topLeft
        } else if (first != anchor.topLeft) {
            onAnchorMoved()
        }
        return raftMenuPosition(anchor, windowSize, popupContentSize, gap, windowMargin, insets, layoutDirection == LayoutDirection.Rtl, safeArea)
    }
}

/**
 * raft-ui `PreviewCardPopup`, the preview card's surface without positioning: 300dp wide,
 * `layer-popover`, `radius-container`, a 1dp `line-seam` ring, `shadow-float`, content clipped
 * (the popover layer of [RaftPopoverChrome]); provides `foreground` and `layer-popover`.
 *
 * Its parts pad themselves: each keeps 12dp under it (`pb-3`) and the surface adds 4dp at the
 * bottom, so the last part ends 16dp from the edge (`last:pb-4`).
 */
@Composable
fun RaftPreviewCardSurface(
    modifier: Modifier = Modifier,
    width: Dp = RaftPreviewCardDefaults.Width,
    content: @Composable ColumnScope.() -> Unit,
) {
    RaftPopoverChrome(modifier.width(width)) {
        content()
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * raft-ui `PreviewCardHeader` (`flex items-start gap-3 px-4 pt-4 pb-3`): [leading] (the large
 * [RaftAvatar], [RaftAvatarSize.Lg]) and, 12dp beside it, [content]: the
 * [RaftPreviewCardTitle] and the lines under it.
 */
@Composable
fun RaftPreviewCardHeader(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), content = content)
    }
}

/** raft-ui `PreviewCardTitle`: the name, `text-headline` bold `foreground`, one line, truncated. */
@Composable
fun RaftPreviewCardTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    RaftText(
        text = text,
        modifier = modifier,
        style = RaftTheme.typography.headline,
        color = RaftTheme.colors.foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** raft-ui `PreviewCardSeparator`: the `line-hairline` under the header, edge to edge. */
@Composable
fun RaftPreviewCardSeparator(modifier: Modifier = Modifier) {
    RaftSeparator(modifier)
}

/**
 * raft-ui `PreviewCardDescription` (`px-4 py-3`): a status line or the description, `text-callout`
 * `foreground-muted`, [maxLines] lines (one, truncated, as raft-ui).
 */
@Composable
fun RaftPreviewCardDescription(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    RaftText(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        style = RaftTheme.typography.callout,
        color = RaftTheme.colors.foregroundMuted,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * raft-ui `PreviewCardActions` (`flex flex-wrap items-center justify-end gap-2 px-4 pb-3`): at
 * most two small buttons ([RaftButton] [RaftButtonSize.Md]), 8dp apart, at the card's end as in a
 * Popover (raft-ui 0.58, raft-design #939): frameless first, the confirm (bordered) one last.
 * Title Case (Message, View Profile); never pink. A button that leaves the card should
 * [RaftPreviewCardState.dismiss] it. An app's item card lays its buttons out with
 * [RaftPreviewCardItemActions] instead.
 */
@Composable
fun RaftPreviewCardActions(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ---------------------------------------------------------------------------
// The item card (raft-ui 0.58 `PreviewCardContent size="item"`, raft-design #939; web
// AppLinkChip / LinkCardActions, slock #12361): an app's item, [RaftPreviewCardDefaults.ItemWidth]
// wide. Its parts answer, in order: which item ([RaftPreviewCardEyebrow]), what it is about
// ([RaftPreviewCardItemTitle]), where it stands ([RaftPreviewCardMeta]), what I can do
// ([RaftPreviewCardItemActions]), and who says so (the footer: the app and the host).

/**
 * raft-ui `PreviewCardBody` (0.84.0: `messageCard` `body`, `flex flex-col gap-1.5 p-3`): the id
 * line, the title and the details row, 12dp from the card's edges, 6dp apart, the same parts as
 * a card in a message ([RaftAppLinkCardBody]).
 */
@Composable
fun RaftPreviewCardBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().padding(RaftMessageEmbedDefaults.Inset),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** raft-ui `PreviewCardEyebrow`: the app's id for the item (`botiverse/slock#10449`), `text-mono-meta` `foreground-muted`, one line. */
@Composable
fun RaftPreviewCardEyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    RaftText(
        text = text,
        modifier = modifier,
        style = RaftTheme.typography.monoMeta,
        color = RaftTheme.colors.foregroundMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * raft-ui `PreviewCardTitle` in `PreviewCardBody` (0.84.0: `messageCard` `title`): the app's title, `text-subhead` bold in ink
 * (never `foreground-link`: the card is not a link, its buttons and the chip are), up to
 * [RaftPreviewCardDefaults.ItemTitleMaxLines] lines, then an ellipsis. Only when it is cut off,
 * the whole title is in a [RaftTooltip] (a pointer resting on it; a long press on touch).
 */
@Composable
fun RaftPreviewCardItemTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    var cut by remember(text) { mutableStateOf(false) }
    val title: @Composable (Modifier) -> Unit = { titleModifier ->
        RaftText(
            text = text,
            modifier = titleModifier,
            style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold),
            color = RaftTheme.colors.foreground,
            maxLines = RaftPreviewCardDefaults.ItemTitleMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { cut = it.hasVisualOverflow },
        )
    }
    if (cut) RaftTooltip(text = text, modifier = modifier) { title(Modifier) } else title(modifier)
}

/** How a detail of [RaftPreviewCardMeta] reads. */
enum class RaftPreviewCardDetailTone {
    /** `foreground`. */
    Default,

    /** `foreground-muted`, the details' own colour. */
    Muted,

    /** A line the app marks as a warning ("2 checks failing"): `warning-strong`, Raft's warning colour, not the app's. */
    Warning,
}

/** One part of an item card's details row ([RaftPreviewCardMeta]). */
@Immutable
sealed interface RaftPreviewCardDetail {
    /** The item's state in the app's words (Merged): a neutral soft Badge, never a status colour. */
    data class State(val text: String) : RaftPreviewCardDetail

    /** A detail ("Martin", "+803 −498"). */
    data class Text(
        val text: String,
        val tone: RaftPreviewCardDetailTone = RaftPreviewCardDetailTone.Muted,
    ) : RaftPreviewCardDetail
}

/**
 * Whether a "·" goes before each of [details] (web LinkCardElements): between two details on
 * one line, but never next to the state chip, which its own fill sets apart.
 */
fun raftPreviewCardDetailSeparators(details: List<RaftPreviewCardDetail>): List<Boolean> =
    details.mapIndexed { index, detail ->
        index > 0 && detail !is RaftPreviewCardDetail.State && details[index - 1] !is RaftPreviewCardDetail.State
    }

/**
 * raft-ui `PreviewCardMeta` (`flex flex-wrap items-center gap-x-2 gap-y-1 text-callout
 * text-foreground-muted`): where the item stands. The state chip
 * ([RaftPreviewCardDetail.State], a neutral soft [RaftBadge]: an app's states are its own, so
 * never Raft's status colours or light), then the details on one line separated by "·"
 * ([raftPreviewCardDetailSeparators]). It wraps when it runs out of room; with [wrap] false it
 * stays on one line and the details are cut with an ellipsis.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RaftPreviewCardMeta(
    details: List<RaftPreviewCardDetail>,
    modifier: Modifier = Modifier,
    wrap: Boolean = true,
) {
    if (details.isEmpty()) return
    val separators = raftPreviewCardDetailSeparators(details)
    val parts: @Composable (oneLine: Boolean, cell: Modifier) -> Unit = { oneLine, cell ->
        details.forEachIndexed { index, detail ->
            if (separators[index]) RaftPreviewCardMetaText("·", RaftPreviewCardDetailTone.Muted, oneLine = true)
            when (detail) {
                is RaftPreviewCardDetail.State -> RaftBadge(text = detail.text, appearance = RaftBadgeAppearance.Soft)
                is RaftPreviewCardDetail.Text -> RaftPreviewCardMetaText(detail.text, detail.tone, oneLine, cell)
            }
        }
    }
    if (wrap) {
        FlowRow(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { parts(false, Modifier) }
    } else {
        Row(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { parts(true, Modifier.weight(1f, fill = false)) }
    }
}

@Composable
private fun RaftPreviewCardMetaText(text: String, tone: RaftPreviewCardDetailTone, oneLine: Boolean, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    RaftText(
        text = text,
        modifier = modifier,
        style = RaftTheme.typography.callout,
        color =
            when (tone) {
                RaftPreviewCardDetailTone.Default -> colors.foreground
                RaftPreviewCardDetailTone.Muted -> colors.foregroundMuted
                RaftPreviewCardDetailTone.Warning -> colors.warningStrong
            },
        maxLines = if (oneLine) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
    )
}

/** What an item card's button does (the app's `action.kind`); it decides where the button goes ([raftPreviewCardButtonLayout]). */
enum class RaftPreviewCardButtonKind {
    /** The app's action (Approve, Merge: `execute`): the first two take the row. */
    Execute,

    /** Reads the item again (Refresh: `read`): always in the More menu. */
    Read,

    /** Goes to a page (Open in Stamp: `navigate`): the first one takes the row when there is no action. */
    Link,
}

/**
 * One button an item card offers, in the app's order.
 *
 * - [frameless]: the app's `ghost` button (no frame); otherwise bordered (`default`, ink border,
 *   never pink).
 * - [leavesRaft]: a link out of Raft ends in the leaving mark (↗), in the row and in the menu;
 *   false for a link Raft opens itself (an Artifact's viewer).
 * - [disabledReason]: the app's reason it can't be used now; the button is disabled and says why
 *   (in the row, a caption under the buttons and the tooltip on a long press; in the menu, the
 *   item's second line). A link with a reason is no link: no ↗.
 * - [enabled] false: disabled without a reason of the app's (the card is busy; say so elsewhere).
 * - [loading]: this button's work is running.
 * - [opensItem]: a link to the item the card is about (Stamp's Open in Stamp, Raft's Open Link): a
 *   block card's title already opens it, so it is in the More menu ([raftAppLinkCardButtonLayout]).
 */
@Immutable
data class RaftPreviewCardButton(
    val label: String,
    val kind: RaftPreviewCardButtonKind,
    val onClick: () -> Unit,
    val frameless: Boolean = false,
    val leavesRaft: Boolean = kind == RaftPreviewCardButtonKind.Link,
    val disabledReason: String? = null,
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val opensItem: Boolean = false,
)

/** An item card's buttons as [raftPreviewCardButtonLayout] places them: [row] left to right, then [more], the More menu's, top to bottom. */
@Immutable
data class RaftPreviewCardButtonLayout<T>(val row: List<T>, val more: List<T>)

/**
 * Where an item card's [buttons] (in the app's order) go (raft-design #939, web
 * `linkCardRowLayout`, slock #12361):
 *
 * - The row: the app's actions ([RaftPreviewCardButtonKind.Execute]), the first
 *   [RaftPreviewCardDefaults.ItemRowButtons]; with none, the first link instead.
 * - In the row, the [frameless] ones first and the bordered one last, at the right end; buttons
 *   of one kind keep the app's order.
 * - Everything else (Refresh, the other links, any further action) in the More menu, in the
 *   app's order: never wrapped onto a second line, never dropped.
 */
fun <T> raftPreviewCardButtonLayout(
    buttons: List<T>,
    kind: (T) -> RaftPreviewCardButtonKind,
    frameless: (T) -> Boolean,
    linkTakesEmptyRow: Boolean = true,
): RaftPreviewCardButtonLayout<T> {
    val executes = buttons.indices.filter { kind(buttons[it]) == RaftPreviewCardButtonKind.Execute }
    val picked =
        if (executes.isNotEmpty()) {
            executes.take(RaftPreviewCardDefaults.ItemRowButtons)
        } else if (linkTakesEmptyRow) {
            buttons.indices.filter { kind(buttons[it]) == RaftPreviewCardButtonKind.Link }.take(1)
        } else {
            // A card whose title is the link (RaftAppLinkCard): every link is in the menu.
            emptyList()
        }
    val row = picked.filter { frameless(buttons[it]) } + picked.filterNot { frameless(buttons[it]) }
    val inRow = picked.toSet()
    return RaftPreviewCardButtonLayout(
        row = row.map { buttons[it] },
        more = buttons.indices.filterNot { it in inRow }.map { buttons[it] },
    )
}

/**
 * Where a block card's [buttons] (in the app's order) go (web `linkCardRowLayout` with a block,
 * botiverse/slock `LinkCardActions.tsx`): the row takes the app's first
 * [RaftPreviewCardDefaults.ItemRowButtons] buttons whatever they do (an action, Refresh, a link),
 * but never a link to the item itself ([RaftPreviewCardButton.opensItem]): the card's title opens
 * it. In the row the frameless ones come first and the bordered one last; everything else is in
 * the More menu in the app's order.
 */
fun raftAppLinkCardButtonLayout(buttons: List<RaftPreviewCardButton>): RaftPreviewCardButtonLayout<RaftPreviewCardButton> {
    val picked = buttons.indices.filterNot { buttons[it].opensItem }.take(RaftPreviewCardDefaults.ItemRowButtons)
    val row = picked.filter { buttons[it].frameless } + picked.filterNot { buttons[it].frameless }
    val inRow = picked.toSet()
    return RaftPreviewCardButtonLayout(
        row = row.map { buttons[it] },
        more = buttons.indices.filterNot { it in inRow }.map { buttons[it] },
    )
}

/** [raftPreviewCardButtonLayout] of [RaftPreviewCardButton]s. */
fun raftPreviewCardButtonLayout(
    buttons: List<RaftPreviewCardButton>,
    linkTakesEmptyRow: Boolean = true,
): RaftPreviewCardButtonLayout<RaftPreviewCardButton> =
    raftPreviewCardButtonLayout(buttons, kind = { it.kind }, frameless = { it.frameless }, linkTakesEmptyRow = linkTakesEmptyRow)

/**
 * raft-ui `PreviewCardActions` on an item card (web `LinkCardActions`): [buttons] laid out by
 * [raftPreviewCardButtonLayout], one group on one line at the card's end: the More menu's ⋯
 * (a frameless [RaftIconButton] named [moreLabel]) first, then the row's buttons. Long labels
 * are cut with an ellipsis rather than wrapping; the whole label is in each button's
 * [RaftTooltip]. The menu opens aligned to the ⋯'s end, so it stays over the card.
 *
 * A row button with a `disabledReason` is disabled, and the reason (the first one) is a caption
 * under the row, `text-footnote` `foreground-muted`, at the end under the buttons: touch has no
 * hover to show the web's tooltip, so the reason is always in sight (it is in the long-press
 * tooltip too). Nothing when [buttons] is empty.
 */
@Composable
fun RaftPreviewCardItemActions(
    buttons: List<RaftPreviewCardButton>,
    modifier: Modifier = Modifier,
    moreLabel: String = RaftTheme.strings.moreActions,
    /** False: with no action of the app's the row stays empty and every link is in the menu ([RaftAppLinkCard]). */
    linkTakesEmptyRow: Boolean = true,
) {
    if (buttons.isEmpty()) return
    val layout = remember(buttons, linkTakesEmptyRow) { raftPreviewCardButtonLayout(buttons, linkTakesEmptyRow) }
    RaftPreviewCardLaidOutActions(layout, modifier, moreLabel)
}

/** [RaftPreviewCardItemActions]' drawing of a [layout] already made (a block card's: [RaftAppLinkCardActions]). */
@Composable
internal fun RaftPreviewCardLaidOutActions(
    layout: RaftPreviewCardButtonLayout<RaftPreviewCardButton>,
    modifier: Modifier = Modifier,
    moreLabel: String = RaftTheme.strings.moreActions,
) {
    if (layout.row.isEmpty() && layout.more.isEmpty()) return
    val reason = layout.row.firstNotNullOfOrNull { it.disabledReason }
    // `messageCard` `actions` (raft-ui 0.84.0): `px-3 pb-3`, a card's inset.
    Column(
        modifier.fillMaxWidth().padding(start = RaftMessageEmbedDefaults.Inset, end = RaftMessageEmbedDefaults.Inset, bottom = RaftMessageEmbedDefaults.Inset),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (layout.more.isNotEmpty()) RaftPreviewCardMoreMenu(layout.more, moreLabel)
            layout.row.forEach { button -> RaftPreviewCardRowButton(button, Modifier.weight(1f, fill = false)) }
        }
        if (reason != null) {
            RaftText(
                text = reason,
                modifier = Modifier.fillMaxWidth(),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** A row button: frameless or bordered, ↗ after a link out of Raft, its label (and reason) in a tooltip. */
@Composable
private fun RaftPreviewCardRowButton(button: RaftPreviewCardButton, modifier: Modifier) {
    val reason = button.disabledReason
    RaftTooltip(text = if (reason != null) "${button.label}\n$reason" else button.label, modifier = modifier) {
        RaftButton(
            text = button.label,
            onClick = button.onClick,
            variant = if (button.frameless) RaftButtonVariant.Ghost else RaftButtonVariant.Default,
            enabled = reason == null && button.enabled,
            loading = button.loading,
            trailingIcon = if (button.leavesRaft && reason == null) { { RaftGlyphIcon(RaftGlyph.ArrowUpRight, size = 16.dp) } } else null,
        )
    }
}

/**
 * The More menu (⋯): a frameless icon button, and a [RaftDropdownMenu] aligned to its end with
 * [items] in the app's order. A link out of Raft ends in ↗; an item with a `disabledReason` is
 * disabled with the reason as its second line (an item can't carry a tooltip). Choosing an item
 * closes the menu and runs it.
 */
@Composable
private fun RaftPreviewCardMoreMenu(items: List<RaftPreviewCardButton>, label: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        RaftIconButton(onClick = { open = true }, tooltip = label, variant = RaftButtonVariant.Ghost) {
            RaftGlyphIcon(RaftGlyph.Ellipsis, size = 18.dp)
        }
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, alignEnd = true) {
            items.forEach { item ->
                val reason = item.disabledReason
                RaftMenuItem(
                    text = item.label,
                    onClick = {
                        open = false
                        item.onClick()
                    },
                    enabled = reason == null && item.enabled,
                    description = reason,
                    trailingIcon = if (item.leavesRaft && reason == null) { { RaftGlyphIcon(RaftGlyph.ArrowUpRight, size = 16.dp) } } else null,
                )
            }
        }
    }
}
