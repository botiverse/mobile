package build.raft.app

import androidx.compose.ui.unit.Density
import build.raft.app.compat.ui.RaftHostImeInsets
import build.raft.app.components.RaftPullToRefreshStyle

/*
 * The iPad app on a Mac ("Designed for iPad", [raftPlatformIsiOSAppOnMac]) behaves like the desktop
 * app where the two differ: a Mac has no touch screen, no software keyboard and no status bar. The
 * iOS actuals and the iOS page root read these, keyed on `onMac`, so the mapping is unit-tested
 * here. iPad and iPhone (onMac false) keep their iOS values.
 */

/** Pull-to-refresh: none on a Mac, so a wheel or trackpad over-scroll cannot pull (desktop). */
internal fun raftIosPullToRefreshStyle(onMac: Boolean): RaftPullToRefreshStyle =
    if (onMac) RaftPullToRefreshStyle.None else RaftPullToRefreshStyle.Cupertino

/**
 * The "Enter sends" default: on with a Mac's keyboard, like desktop, and on an iPad or iPhone while
 * a hardware keyboard is connected (RaftIosHardwareKeyboard.kt). A choice saved in Settings wins.
 */
internal fun raftIosComposerEnterSendsByDefault(onMac: Boolean, hardwareKeyboard: Boolean): Boolean =
    onMac || hardwareKeyboard

/**
 * The host's status-bar-hidden flag as the top inset reads it. A Mac has no status bar: a hidden
 * bar's 16dp margin would draw a frame-coloured strip under the title bar, so a Mac counts as
 * shown and uses its live top inset, as the desktop host publishes.
 */
internal fun raftHostStatusBarHidden(reported: Boolean, onMac: Boolean): Boolean = reported && !onMac

/**
 * The keyboard report of an iOS page on a Mac: there is no software keyboard, so the inset is
 * always 0 and a keyboard frame UIKit still reports is ignored (it lifted the composer). Not
 * animating, so [build.raft.app.compat.ui.raftImeBottomPx] takes this 0 over `WindowInsets.ime`.
 */
internal fun raftIosAppOnMacImeInsets(): RaftHostImeInsets =
    RaftHostImeInsets().apply {
        imeBottomPx = 0
        imeAnimationRunning = false
    }

/**
 * How large macOS draws a "Designed for iPad" app: 77%, so 14pt text shows at about 10.8 Mac
 * points. INFERRED, not read from code or an API: Apple documents 77% for the iPad idiom of Mac
 * Catalyst apps, and iPad apps on Apple silicon Macs are assumed to run at the same scale.
 */
internal const val RaftIosAppOnMacScale: Float = 0.77f

/**
 * The density an iOS page lays out with: on a Mac, divided by [RaftIosAppOnMacScale], so a dp is a
 * Mac point again and type and controls match the desktop app. Window widths in dp shrink by the
 * same factor, so [build.raft.designsystem.theme.RaftScale.WideMinWidth] still falls at 744 Mac
 * points, as on desktop.
 */
internal fun raftIosAppOnMacDensity(density: Density, onMac: Boolean): Density =
    if (onMac) Density(density.density / RaftIosAppOnMacScale, density.fontScale) else density
