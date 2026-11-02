package build.raft.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
    /** `w-[300px]`. */
    val Width: Dp = 300.dp

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
                if (state.isOpen && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    state.dismiss()
                    true
                } else {
                    false
                }
            }.pointerInput(state, pinOnClick) {
                awaitPointerEventScope {
                    var downAt: Offset? = null
                    var downMillis = 0L
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (pinOnClick) {
                            when (event.type) {
                                PointerEventType.Press -> {
                                    // One pointer, the primary button for a mouse (a right click is the
                                    // trigger's menu), and not the press that just closed the pinned card.
                                    downAt =
                                        change.position.takeIf {
                                            event.changes.size == 1 &&
                                                (change.type != PointerType.Mouse || event.buttons.isPrimaryPressed) &&
                                                !state.closedByOutsidePress
                                        }
                                    downMillis = change.uptimeMillis
                                }
                                PointerEventType.Release -> {
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
    val scope = rememberCoroutineScope()
    val pinned = state.isPinned
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = {
            // A mouse press on the trigger of a pinned card is not "outside": the trigger's click
            // keeps it pinned (web), so it does not close and open again.
            if (!(pinned && state.isTriggerHovered)) {
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
        // A pinned card takes focus, so Escape closes it wherever focus was (web: a document
        // listener while pinned); a hover card never does.
        properties = PopupProperties(focusable = pinned, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Box(
            if (room == null) {
                Modifier
            } else {
                Modifier.padding(start = room.Horizontal, end = room.Horizontal, top = room.Top, bottom = room.Bottom)
            },
        ) {
            RaftPreviewCardSurface(
                width = width,
                modifier = Modifier.pointerInput(state) {
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
                },
                content = card,
            )
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
 * raft-ui `PreviewCardActions` (`flex flex-wrap gap-2 px-4 pb-3`): at most two small buttons
 * ([RaftButton] [RaftButtonSize.Md]), 8dp apart, in Title Case (Message, View Profile); never
 * pink. A button that leaves the card should [RaftPreviewCardState.dismiss] it.
 */
@Composable
fun RaftPreviewCardActions(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
