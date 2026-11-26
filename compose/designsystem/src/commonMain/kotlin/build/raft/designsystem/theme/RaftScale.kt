package build.raft.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftUiComponentTokens

/**
 * Which raft-ui type scale [RaftTheme] provides: the type and information density. It follows
 * the window width only (artin 2026-11-02), so a tablet and the desktop app at the same width
 * look the same. raft-ui 0.54 switches its "Desktop type" block (styles.css) under
 * `(width >= 48rem) and (pointer: fine)`; the app keeps that block's sizes but drops the pointer
 * half, and starts it at the app's own wide breakpoint, [WideMinWidth]. The control metrics are
 * the other axis and follow the input ([RaftPointerInput]).
 */
enum class RaftScale {
    /** The default scale: a window narrower than [WideMinWidth] (a phone, a split-screen half). */
    Compact,

    /** raft-ui's desktop type: a window at least [WideMinWidth] wide, touch or mouse alike. */
    Wide,
    ;

    companion object {
        /**
         * Where the app lays out wide (two panes, the rail) and takes the wide type: 744, the iPad
         * mini's portrait width. The app's `DualPaneMinimumWidthDp` reads this, so the layout and
         * the type always switch together.
         */
        val WideMinWidth: Dp = 744.dp

        /** The scale for a window [windowWidth] wide. */
        fun of(windowWidth: Dp): RaftScale = if (windowWidth >= WideMinWidth) Wide else Compact
    }
}

/**
 * The input in use. With the window's [RaftScale] it picks the component sizes
 * ([raftComponentTokens]): raft-ui's desktop sizes (34dp controls, 44dp heads) need a wide window
 * and a mouse or trackpad, as foundation.css's `(width >= 48rem) and (pointer: fine)`; a mouse in
 * a narrow window keeps the touch sizes but for the few tokens that follow the input alone
 * (`list-row.min-height`). Hover and the other interactions follow the input at any width.
 */
enum class RaftPointerInput {
    /** A finger (or nothing seen yet on a touch platform). */
    Touch,

    /** A mouse or trackpad: the desktop's 34dp controls on a wide window (sizing v12). */
    Fine,
}

/**
 * raft-ui's component tokens for a window of [scale] used by [pointerInput]: [RaftUiComponentTokens.Desktop]
 * on a wide window with a mouse or trackpad, [RaftUiComponentTokens.Fine] with a mouse in a narrow
 * one, [RaftUiComponentTokens.WideTouch] on a wide window by touch, else [RaftUiComponentTokens.Touch]. The app's wide layout starts at [RaftScale.WideMinWidth]
 * (744), the Web's desktop at 768.
 */
fun raftComponentTokens(scale: RaftScale, pointerInput: RaftPointerInput): RaftUiComponentTokens.Values =
    RaftUiComponentTokens.of(wide = scale == RaftScale.Wide, fine = pointerInput == RaftPointerInput.Fine)

/**
 * The control metrics for a window of [scale] used by [pointerInput] ([raftComponentTokens]). With a finger, a wide
 * window ([RaftScale.Wide]: a tablet, an unfolded Duo, a phone held sideways at 744 and up) takes the short head
 * ([RaftControlMetrics.MobileShortHead], 48): the screen has room for more of the content, not for taller heads
 * (artin, #wg-mobile-sync:#265). Its bar buttons are 36 with a 20 glyph (not the phone's 44 and 22), so they
 * sit in the 48 head with 6 above and below: raft-ui's `wideTouch` context (raft-ui 0.103.4, raft-design
 * #1416: `header-height` 48, `bar-button-size` 36, `bar-icon-size` 20).
 */
fun raftControlMetrics(scale: RaftScale, pointerInput: RaftPointerInput): RaftControlMetrics =
    when {
        raftComponentTokens(scale, pointerInput) == RaftUiComponentTokens.Desktop -> RaftControlMetrics.Desktop
        scale == RaftScale.Wide -> RaftControlMetrics.MobileShortHead
        else -> RaftControlMetrics.Mobile
    }

/**
 * The control metrics: raft-ui's component tokens ([RaftUiComponentTokens], generated from its
 * `tokens.resolved.json`) as the components read them, and the density that goes with them
 * (sizing v12, raft-ui 0.65 desktop density): on a wide window with a mouse or trackpad the heads,
 * bars, rows and switches tighten too. CSS px map 1:1 to dp. [Mobile] is the touch context,
 * [Desktop] the desktop one; [of] builds them, so a token change reaches every component. The few
 * values with no token (the bar glyph, the settings cards) are the app's own and say so.
 */
@Immutable
data class RaftControlMetrics(
    /** `--control-height`: a field, a select trigger, a Button at `lg`, an icon button at `icon-lg`. */
    val height: Dp,
    /** `--control-height-cta`: the full-width call to action of a sign-in or setup form (Button `xl`). */
    val ctaHeight: Dp,
    /** `--control-icon-size`: the icon in a [height] icon button. */
    val iconSize: Dp,
    /**
     * `--control-hit-inset`: how far a [height] button's hit area reaches past its top and
     * bottom to keep 44. Compose foundation has no layout-free hit-area expansion, so the
     * components do not draw it; it is here for callers that leave room round a control.
     */
    val hitInset: Dp,
    /** `--control-embedded-size`: a chip or a button embedded in a field. */
    val embeddedSize: Dp,
    /**
     * `--control-radius`: the corners of a control at [height] (a field and a textarea, an input
     * group, a select trigger, the combobox frames, a Button at `lg` and an icon button at
     * `icon-lg`), about a quarter of its height: 10 at 44 with a finger, 8 at 34 under a fine
     * pointer (raft-ui foundation.css). Smaller controls (`md`, `xl`, chips) keep `radius-control`
     * ([build.raft.designsystem.tokens.RaftRadius.Control], 8).
     */
    val controlRadius: Dp,
    /**
     * The one height medium controls and rows share under a fine pointer (sizing v12, raft-ui
     * 0.65 desktop density): a Button and an icon button at `md`, a tab, a segmented item, a menu
     * option and the menu's search row, a sidebar row. It is [height], so a row lines up with the
     * field beside it. Null with a finger: each keeps its own touch height (32, 36, 40, 44).
     */
    val rowHeight: Dp?,
    /**
     * A sidebar row in a wide window (raft-ui SidebarItem at `md`: `h-(--row-height)` in
     * `text-subhead` 500): 38 with a finger (`--row-height` on a touch screen), 34 under a fine
     * pointer. Null on a phone, where a row is at least 44 in `text-body` 600 (`max-md:min-h-11`).
     */
    val sidebarRowHeight: Dp?,
    /**
     * A head: a page's top bar, a panel's head, a dialog sheet's head, a folded pane, a drawer's
     * head (raft-ui `--shell-header-height`). 56 with a finger; 44 under a fine pointer (sizing
     * v12, raft-ui 0.65 desktop density), where a 56 head wastes a mouse's precision.
     */
    val headHeight: Dp,
    /**
     * A head's or a bar's icon button (Back, Close, a head's actions): 44 with a finger, the
     * phone's `icon-lg`; 28 under a fine pointer (sizing v12, raft-ui 0.65 desktop density), the
     * desktop bar button, which a mouse hits without the finger's 44.
     */
    val barButtonSize: Dp,
    /** The glyph in a [barButtonSize] button: 22 with a finger, 16 under a fine pointer. */
    val barIconSize: Dp,
    /**
     * `--bar-button-radius`: the corners of a [barButtonSize] button, about a quarter of its
     * height: 10 at 44 with a finger; 6 at the 28 desktop bar button under a fine pointer
     * (raft-ui foundation.css).
     */
    val barButtonRadius: Dp,
    /**
     * From a head's leading edge to its first bar button, so the glyph (not the button) lands on
     * the 16dp content margin: (44 - 22) / 2 = 11 in, 5 out with a finger; (28 - 16) / 2 = 6 in,
     * 10 out under a fine pointer.
     */
    val barEdgeInset: Dp,
    /**
     * A head's leading identity (a DM's or a member's avatar, the location tile's slot): web
     * AvatarSlot `panel-header`, `md` (35) with a finger in the 56 head; `sm` (27) in the 48 short
     * head and under a fine pointer, so it matches the 27 location tile and the workspace avatar.
     */
    val headAvatar: RaftAvatarSize,
    /**
     * The wide layout's top bar ([build.raft.designsystem.components.RaftTopBar]), its seam
     * included: web AppTopBar `h-12` (48) with a finger; 44 under a fine pointer (sizing v12,
     * raft-ui 0.65 desktop density).
     */
    val topBarHeight: Dp,
    /**
     * An item on the top bar (the workspace button, the search trigger, an on-frame `md` button):
     * raft-ui `--frame-item-height`, 36 with a finger and the 34 control height under a fine
     * pointer, so the bar is the item with the header air above and below (34 + 2 x 5 = 44).
     */
    val topBarItemSize: Dp,
    /**
     * Whether the app rail takes its compact size (48 + the seam, 36 items) whatever the window's
     * height: under a fine pointer (sizing v12, raft-ui 0.65 desktop density). With a finger the
     * rail goes compact only in a short window.
     */
    val railCompact: Boolean,
    /** [build.raft.designsystem.components.RaftSwitch]'s track width: 50 with a finger, 38 under a fine pointer. */
    val switchWidth: Dp,
    /** The switch's track height (and twice its radius): 30 with a finger, 22 under a fine pointer. */
    val switchHeight: Dp,
    /** The switch's knob: 22 with a finger, 14 under a fine pointer (sizing v12). */
    val switchKnobSize: Dp,
    /**
     * Inside a settings card (a titled group of settings rows): 16 with a finger; 20 under a fine
     * pointer (sizing v12, raft-ui 0.65 desktop density), where the cards sit in a wide page.
     */
    val settingsCardPadding: Dp,
    /** Between settings cards: 12 with a finger, 16 under a fine pointer (sizing v12). */
    val settingsCardGap: Dp,
) {
    companion object {
        /**
         * The metrics of [tokens]. [Desktop] alone has a shared [rowHeight]; with a finger each
         * medium control keeps its own touch height.
         */
        fun of(tokens: RaftUiComponentTokens.Values): RaftControlMetrics {
            val desktop = tokens == RaftUiComponentTokens.Desktop
            // `bar-icon-size` (raft-ui 0.103.4): 22 on a phone, 20 by touch on a wide window. App-only:
            // the desktop bar button's glyph stays 16 (raft-ui's is 18), and the settings cards.
            val barIconSize = if (desktop) 16.dp else tokens.barIconSize
            return RaftControlMetrics(
                height = tokens.controlHeight,
                ctaHeight = tokens.controlHeightCta,
                iconSize = tokens.controlIconSize,
                hitInset = tokens.controlHitInset,
                embeddedSize = tokens.controlEmbeddedSize,
                controlRadius = tokens.controlRadius,
                rowHeight = if (desktop) tokens.rowHeight else null,
                sidebarRowHeight = if (desktop) tokens.rowHeight else null,
                headHeight = tokens.headerHeight,
                barButtonSize = tokens.barButtonSize,
                barIconSize = barIconSize,
                barButtonRadius = tokens.barButtonRadius,
                // The first bar button's glyph lands on the page inset.
                barEdgeInset = tokens.pageInset - (tokens.barButtonSize - barIconSize) / 2,
                headAvatar = if (desktop) RaftAvatarSize.Sm else RaftAvatarSize.Md,
                // Web AppTopBar: `h-[calc(var(--frame-item-height)+2*var(--header-air))]`.
                topBarHeight = tokens.frameItemHeight + tokens.headerAir * 2,
                topBarItemSize = tokens.frameItemHeight,
                railCompact = tokens.railWidth == tokens.railWidthShort,
                switchWidth = tokens.switchWidth,
                switchHeight = tokens.switchHeight,
                // The knob sits 4dp inside the track all round (raft-ui Switch).
                switchKnobSize = tokens.switchHeight - 8.dp,
                settingsCardPadding = if (desktop) 20.dp else 16.dp,
                settingsCardGap = if (desktop) 16.dp else 12.dp,
            )
        }

        /** The touch context: a phone, and a touch screen at any width (44dp controls). */
        val Mobile = of(RaftUiComponentTokens.Touch)

        /**
         * The touch context in a wide window, raft-ui's `wideTouch` (0.103.4, raft-design #1416):
         * the 48 head, its bar buttons at 36 with a 20 glyph, so a head's buttons sit in it with
         * 6 above and below as the top bar's items do, instead of a 44 button with 2. App-only on
         * top: the bar buttons' corners at `radius-control`, the head's small avatar and the
         * sidebar's touch row height.
         */
        val MobileShortHead: RaftControlMetrics =
            of(RaftUiComponentTokens.WideTouch).copy(
                barButtonRadius = RaftRadius.Control,
                headAvatar = RaftAvatarSize.Sm,
                sidebarRowHeight = RaftUiComponentTokens.WideTouch.rowHeight,
            )

        /** The desktop context: a wide window with a mouse or trackpad (34dp controls). */
        val Desktop = of(RaftUiComponentTokens.Desktop)
    }
}

/**
 * The input the app assumes before any pointer event: the desktop app with a display is
 * [RaftPointerInput.Fine]; Android and iOS start [RaftPointerInput.Touch] and switch once a mouse
 * or trackpad is used ([raftTrackPointerInput]). A headless desktop JVM (the screenshot and UI
 * tests, `java.awt.headless`) is Touch too, so tests render 44dp controls unless they pin one.
 */
expect fun raftPlatformInitialPointerInput(): RaftPointerInput

/**
 * The input last used in this app process, [RaftPointerInput.Touch] or [RaftPointerInput.Fine].
 * Snapshot state, read by the outermost [RaftTheme]; written by [raftTrackPointerInput].
 *
 * It changes only when the kind of input changes, never on every event: a mouse or trackpad
 * moving, pressing or scrolling makes it Fine; a finger lifting (the end of a tap or a drag, so
 * a control never resizes under the finger that is pressing it) makes it Touch. A pen neither
 * (it is precise, but the hand holding it also taps).
 */
object RaftPointerInputState {
    var current: RaftPointerInput by mutableStateOf(raftPlatformInitialPointerInput())
        private set

    private var touchScreen = true

    /** Records one pointer event of [eventType] from a pointer of [type]. */
    fun report(type: PointerType, eventType: PointerEventType) {
        val next =
            when {
                type == PointerType.Mouse -> RaftPointerInput.Fine
                type == PointerType.Touch && eventType == PointerEventType.Release && touchScreen -> RaftPointerInput.Touch
                else -> return
            }
        if (current != next) current = next
    }

    /**
     * The device has no touch screen: the iPad app on a Mac, where a click still arrives as a
     * touch. The input is [RaftPointerInput.Fine] from now on, and a touch release no longer makes
     * it Touch. The host calls this once at startup, before the first page.
     */
    fun assumeNoTouchScreen() {
        touchScreen = false
        current = RaftPointerInput.Fine
    }

    /** Back to the platform's starting input (tests that drove a pointer). */
    fun reset() {
        touchScreen = true
        current = raftPlatformInitialPointerInput()
    }
}

/**
 * Feeds [RaftPointerInputState] from every pointer event over this element, before its children
 * see them; consumes nothing. Put it on the root of each window's content (the app's page host
 * does).
 */
fun Modifier.raftTrackPointerInput(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                RaftPointerInputState.report(change.type, event.type)
            }
        }
    }

/**
 * The scale of the enclosing [RaftTheme], null outside any. A nested [RaftTheme] without a
 * scale of its own inherits it, so a popup, a dialog window or a screen's own theme never
 * re-measures against a smaller window than the one the page is in.
 */
internal val LocalRaftScale = staticCompositionLocalOf<RaftScale?> { null }

/** The type scale of the enclosing [RaftTheme] ([RaftTheme.typeScale]), null outside any. */
internal val LocalRaftTypeScale = staticCompositionLocalOf<RaftScale?> { null }

/** raft-ui's desktop type applies on a wide window with a mouse or trackpad only (raft-design #601). */
fun raftTypeScale(scale: RaftScale, pointerInput: RaftPointerInput): RaftScale =
    if (scale == RaftScale.Wide && pointerInput == RaftPointerInput.Fine) RaftScale.Wide else RaftScale.Compact

/** The pointer input of the enclosing [RaftTheme], null outside any; inherited as [LocalRaftScale]. */
internal val LocalRaftPointerInput = staticCompositionLocalOf<RaftPointerInput?> { null }

/** The component tokens of the enclosing [RaftTheme] ([RaftTheme.sizes]). */
internal val LocalRaftComponentTokens = staticCompositionLocalOf<RaftUiComponentTokens.Values> { RaftUiComponentTokens.Touch }

/** The control metrics of the enclosing [RaftTheme] ([raftControlMetrics]). */
internal val LocalRaftControls = staticCompositionLocalOf { RaftControlMetrics.Mobile }

/**
 * The scale for this window's width ([RaftScale.of]). A window not measured yet (a zero container
 * width: a host view composed before its first layout) takes the platform's guess instead
 * ([raftPlatformUnmeasuredWindowWidth]), so a wide window does not draw its first frame at the
 * phone scale and then switch.
 */
@Composable
@ReadOnlyComposable
internal fun raftAutoScale(): RaftScale {
    val containerWidth = LocalWindowInfo.current.containerSize.width
    val width =
        if (containerWidth > 0) {
            with(LocalDensity.current) { containerWidth.toDp() }
        } else {
            raftPlatformUnmeasuredWindowWidth() ?: 0.dp
        }
    return RaftScale.of(width)
}

/**
 * The window width to assume while the window has no measured size yet: the screen's width
 * (iOS: the screen bounds in points; Android: the configuration's screen width), null where the
 * platform has no better guess (desktop), which keeps [RaftScale.Compact].
 */
internal expect fun raftPlatformUnmeasuredWindowWidth(): Dp?

