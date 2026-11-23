package build.raft.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFrameInsets
import build.raft.designsystem.components.LocalRaftWindowTitleBar
import build.raft.designsystem.components.RaftFrameInsets
import build.raft.designsystem.components.RaftTopBarDefaults
import build.raft.designsystem.components.RaftWindowTitleBar
import dev.nucleusframework.window.windowDragArea
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The macOS window's title bar laid over the app's top bar, as the web desktop app's (Electron
 * `titleBarStyle: "hidden"` with the traffic lights at x16 y17, centred in its 48px top bar): no
 * separate "Raft" title bar above the app; the window content runs up under the title bar and the
 * traffic lights sit at the start of the top bar ([RaftWindowTitleBar]).
 *
 * The window is Nucleus's Tao window (Main.kt): its WindowScaffold lays [RaftDesktopTitleBarBand]
 * over the window's top. Nucleus centres the traffic lights in the band's height (the top bar's
 * under a mouse or trackpad, [RaftTopBarDefaults.FineHeight]; a Mac has no touch screen) and reports
 * the room they take (LocalWindowChromeInsets), the [RaftDesktopTitleBarMetrics.controlsStart].
 *
 * Fullscreen hides the traffic lights: no band and no inset then. Windows / Linux: Nucleus's own
 * title bar with its window controls, above the content (development runs only; not shipped).
 */

/**
 * The band at the window's top that moves the window: transparent, as tall as the top bar. It
 * shares every press with the app under it (the scaffold's pass-through overlay): a control of the
 * app that takes the press (a top bar button, the search field) keeps it and the window stays; an
 * empty part of the bar, or the strip a page without a top bar keeps under the traffic lights, drags
 * the window and zooms it on a double click ([windowDragArea]).
 */
@Composable
internal fun RaftDesktopTitleBarBand() {
    Box(Modifier.fillMaxWidth().height(RaftTopBarDefaults.FineHeight).windowDragArea())
}

/**
 * What the app keeps clear of the title bar: the [band] at the window's top (the pages' top safe
 * area, which the top bar covers itself) and the traffic lights' corner at the bar's start
 * ([controlsStart]).
 */
internal data class RaftDesktopTitleBarMetrics(val band: Dp, val controlsStart: Dp)

/**
 * The traffic lights' corner when the window reports none: the three ~14pt buttons from x≈8 (the
 * system's place; the web places them at x16 and reserves 76px, its titlebar-area-x) plus the same
 * air before the bar's items (the bar's own 9dp leading padding comes on top).
 */
internal val RaftDesktopDefaultControlsWidth: Dp = 70.dp

/**
 * The band and the corner for the macOS title bar: the band is the top bar's height; the corner is
 * [reportedControlsWidth] (Nucleus's measure of the traffic lights' room for that height) when it is
 * known. Fullscreen: none.
 */
internal fun raftDesktopTitleBarMetrics(fullscreen: Boolean, reportedControlsWidth: Dp): RaftDesktopTitleBarMetrics {
    if (fullscreen) return RaftDesktopTitleBarMetrics(band = 0.dp, controlsStart = 0.dp)
    val controls = if (reportedControlsWidth > 0.dp) reportedControlsWidth else RaftDesktopDefaultControlsWidth
    return RaftDesktopTitleBarMetrics(band = RaftTopBarDefaults.FineHeight, controlsStart = controls)
}

/**
 * Provides the title bar to the window's pages: [LocalRaftWindowTitleBar] for the top bar and the
 * band as [LocalRaftFrameInsets]' top (menus keep out of it). The band itself moves the window
 * ([RaftDesktopTitleBarBand]), so the top bar needs no modifier of its own. Without a title bar
 * over the content ([metrics] null: not macOS) [content] is as before.
 */
@Composable
internal fun RaftDesktopTitleBarProvider(
    metrics: RaftDesktopTitleBarMetrics?,
    content: @Composable () -> Unit,
) {
    if (metrics == null) {
        content()
        return
    }
    val windowTitleBar = remember(metrics) { RaftWindowTitleBar(height = metrics.band, startInset = metrics.controlsStart) }
    CompositionLocalProvider(
        LocalRaftWindowTitleBar provides windowTitleBar,
        LocalRaftFrameInsets provides RaftFrameInsets(top = metrics.band),
        content = content,
    )
}

/**
 * The title bar's band as each page's top safe area (`slockWindowInsetsChanged`, the contract the
 * iOS host feeds with the status bar): a page header starts under the traffic lights, and the top
 * bar covers the band itself. Nothing is sent while there is no band and none was sent before, so a
 * window without a title bar over its content keeps the pages' defaults.
 */
internal class RaftDesktopWindowInsetsPublisher(
    private val sendEvent: (event: String, data: JsonObject) -> Unit,
) {
    private var latestTopPx: Float? = null

    /**
     * Sends a [band] top inset at [density]; false if invalid, or unchanged and not [resend]. The
     * pages' observers of the insets join as their content composes, so the host sends the same
     * value again on each resize ([resend]).
     */
    fun publish(band: Dp, density: Float, resend: Boolean = false): Boolean {
        if (!(density > 0f) || density.isInfinite()) return false
        val topPx = band.value.coerceAtLeast(0f) * density
        if (latestTopPx == null && topPx == 0f) return false
        if (topPx == latestTopPx && !resend) return false
        latestTopPx = topPx
        sendEvent(
            EVENT_NAME,
            JsonObject(
                mapOf(
                    "statusBarHeightPx" to JsonPrimitive(topPx),
                    "navigationBarHeightPx" to JsonPrimitive(0f),
                    "statusBarHidden" to JsonPrimitive(false),
                )
            ),
        )
        return true
    }

    companion object {
        const val EVENT_NAME = "slockWindowInsetsChanged"
    }
}
