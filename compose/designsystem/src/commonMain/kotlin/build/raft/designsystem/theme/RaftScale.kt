package build.raft.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Which raft-ui type scale and control metrics [RaftTheme] provides. raft-ui 0.54 switches
 * both under one query, `@media (width >= 48rem) and (pointer: fine)` (styles.css "Desktop
 * type", foundation.css "Desktop"): from 768px wide with a mouse or trackpad the page takes
 * Feishu's PC scale; a phone, and a touch screen at any width, keep the default.
 */
enum class RaftScale {
    /** The default scale: phones, tablets and any touch screen. */
    Mobile,

    /** The PC scale: a mouse or trackpad ([raftPlatformPointerIsFine]) and a window at least [MinWidth] wide. */
    Desktop,
    ;

    /** The control metrics of this scale. */
    val controls: RaftControlMetrics
        get() = if (this == Desktop) RaftControlMetrics.Desktop else RaftControlMetrics.Mobile

    companion object {
        /** `48rem`, Tailwind's `md`: where raft-ui's desktop layout and scale begin. */
        val MinWidth: Dp = 768.dp

        /** The web's query: a fine pointer and a window at least [MinWidth] wide. */
        fun of(pointerFine: Boolean, windowWidth: Dp): RaftScale =
            if (pointerFine && windowWidth >= MinWidth) Desktop else Mobile
    }
}

/**
 * raft-ui foundation.css control metrics, the ones its desktop query switches. CSS px map 1:1
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

        /** foundation.css desktop query: Feishu's 32. */
        val Desktop = RaftControlMetrics(height = 32.dp, ctaHeight = 40.dp, iconSize = 18.dp, hitInset = 6.dp, embeddedSize = 20.dp)
    }
}

/**
 * Whether the platform is driven by a mouse or trackpad (CSS `pointer: fine`): the desktop
 * app with a display. Android and iOS are touch, false. A headless desktop JVM (the
 * screenshot and UI tests, `java.awt.headless`) has no pointer at all, false too, so the
 * tests render the default scale unless they pass one.
 */
expect fun raftPlatformPointerIsFine(): Boolean

/**
 * The scale of the enclosing [RaftTheme], null outside any. A nested [RaftTheme] without a
 * scale of its own inherits it, so a popup, a dialog window or a screen's own theme never
 * re-measures against a smaller window than the one the page is in.
 */
internal val LocalRaftScale = staticCompositionLocalOf<RaftScale?> { null }

/** The control metrics of the enclosing [RaftTheme] ([RaftScale.controls]). */
internal val LocalRaftControls = staticCompositionLocalOf { RaftControlMetrics.Mobile }

/** The scale the web's query picks for this window: [raftPlatformPointerIsFine] and its width. */
@Composable
@ReadOnlyComposable
internal fun raftAutoScale(): RaftScale {
    if (!raftPlatformPointerIsFine()) return RaftScale.Mobile
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return RaftScale.of(pointerFine = true, windowWidth = width)
}
