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
 * The input in use, which picks the control metrics ([controls]): raft-ui foundation.css switches
 * `--control-*` for a fine pointer. A finger keeps 44; a mouse or trackpad that is actually in use
 * takes the tighter desktop metrics, at any width.
 */
enum class RaftPointerInput {
    /** A finger (or nothing seen yet on a touch platform): 44dp controls. */
    Touch,

    /** A mouse or trackpad: the desktop's 34dp controls (sizing v12). */
    Fine,
    ;

    /** The control metrics of this input. */
    val controls: RaftControlMetrics
        get() = if (this == Fine) RaftControlMetrics.Desktop else RaftControlMetrics.Mobile
}

/**
 * raft-ui foundation.css control metrics, the ones its fine-pointer query switches, and the
 * density that goes with them (sizing v12, raft-ui 0.65 desktop density): under a mouse or
 * trackpad the heads, bars, rows and switches tighten too, while a finger keeps today's sizes.
 * CSS px map 1:1 to dp. [Mobile] holds the touch values the components always had, so touch
 * rendering does not change; [Desktop] the pointer ones.
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
     * The one height medium controls and rows share under a fine pointer (sizing v12, raft-ui
     * 0.65 desktop density): a Button and an icon button at `md`, a tab, a segmented item, a menu
     * option and the menu's search row, a sidebar row. It is [height], so a row lines up with the
     * field beside it. Null with a finger: each keeps its own touch height (32, 36, 40, 44).
     */
    val rowHeight: Dp?,
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
     * The corners of a [barButtonSize] button: `radius-control` (8) with a finger; `radius-chip`
     * (6) for the 28 desktop bar button (sizing v12), as raft-ui rounds its small buttons.
     */
    val barButtonRadius: Dp,
    /**
     * From a head's leading edge to its first bar button, so the glyph (not the button) lands on
     * the 16dp content margin: (44 - 22) / 2 = 11 in, 5 out with a finger; (28 - 16) / 2 = 6 in,
     * 10 out under a fine pointer.
     */
    val barEdgeInset: Dp,
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
        /** foundation.css `:root`: a finger's 44. */
        val Mobile =
            RaftControlMetrics(
                height = 44.dp,
                ctaHeight = 52.dp,
                iconSize = 22.dp,
                hitInset = 0.dp,
                embeddedSize = 32.dp,
                rowHeight = null,
                headHeight = 56.dp,
                barButtonSize = 44.dp,
                barIconSize = 22.dp,
                barButtonRadius = 8.dp,
                barEdgeInset = 5.dp,
                topBarHeight = 48.dp,
                topBarItemSize = 36.dp,
                railCompact = false,
                switchWidth = 50.dp,
                switchHeight = 30.dp,
                switchKnobSize = 22.dp,
                settingsCardPadding = 16.dp,
                settingsCardGap = 12.dp,
            )

        /**
         * foundation.css fine-pointer query at sizing v12 (raft-ui 0.65 desktop density): 34dp
         * controls (32 before v12), so the hit inset is (44 - 34) / 2.
         */
        val Desktop =
            RaftControlMetrics(
                height = 34.dp,
                ctaHeight = 40.dp,
                iconSize = 18.dp,
                hitInset = 5.dp,
                embeddedSize = 20.dp,
                rowHeight = 34.dp,
                headHeight = 44.dp,
                barButtonSize = 28.dp,
                barIconSize = 16.dp,
                barButtonRadius = 6.dp,
                barEdgeInset = 10.dp,
                topBarHeight = 44.dp,
                topBarItemSize = 34.dp,
                railCompact = true,
                switchWidth = 38.dp,
                switchHeight = 22.dp,
                switchKnobSize = 14.dp,
                settingsCardPadding = 20.dp,
                settingsCardGap = 16.dp,
            )
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

/** The pointer input of the enclosing [RaftTheme], null outside any; inherited as [LocalRaftScale]. */
internal val LocalRaftPointerInput = staticCompositionLocalOf<RaftPointerInput?> { null }

/** The control metrics of the enclosing [RaftTheme] ([RaftPointerInput.controls]). */
internal val LocalRaftControls = staticCompositionLocalOf { RaftControlMetrics.Mobile }

/** The scale for this window's width ([RaftScale.of]). */
@Composable
@ReadOnlyComposable
internal fun raftAutoScale(): RaftScale {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return RaftScale.of(width)
}
