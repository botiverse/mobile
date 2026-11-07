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

    /** A mouse or trackpad: the desktop's 32dp controls. */
    Fine,
    ;

    /** The control metrics of this input. */
    val controls: RaftControlMetrics
        get() = if (this == Fine) RaftControlMetrics.Desktop else RaftControlMetrics.Mobile
}

/**
 * raft-ui foundation.css control metrics, the ones its fine-pointer query switches. CSS px map 1:1
 * to dp.
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
) {
    companion object {
        /** foundation.css `:root`: a finger's 44. */
        val Mobile = RaftControlMetrics(height = 44.dp, ctaHeight = 52.dp, iconSize = 22.dp, hitInset = 0.dp, embeddedSize = 32.dp)

        /** foundation.css fine-pointer query: Feishu's 32. */
        val Desktop = RaftControlMetrics(height = 32.dp, ctaHeight = 40.dp, iconSize = 18.dp, hitInset = 6.dp, embeddedSize = 20.dp)
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
