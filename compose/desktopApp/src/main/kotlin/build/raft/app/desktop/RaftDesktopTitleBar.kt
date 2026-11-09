package build.raft.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFrameInsets
import build.raft.designsystem.components.LocalRaftWindowTitleBar
import build.raft.designsystem.components.RaftFrameInsets
import build.raft.designsystem.components.RaftTopBarDefaults
import build.raft.designsystem.components.RaftWindowTitleBar
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The macOS window's title bar laid over the app's top bar, as the web desktop app's (Electron
 * `titleBarStyle: "hidden"` with the traffic lights at x16 y17, centred in its 48px top bar): no
 * separate "Raft" title bar above the app; the window content runs up under a transparent title
 * bar and the traffic lights sit at the start of the top bar ([RaftWindowTitleBar]).
 *
 * Two ways, picked at start:
 * - JetBrains Runtime (the packaged app's runtime: the Gradle daemon JVM is JBR 21, and Compose
 *   packages the daemon's JDK): its custom title bar ([WindowDecorations.CustomTitleBar]), as tall
 *   as the top bar under a mouse or trackpad ([RaftTopBarDefaults.FineHeight], 44 since sizing
 *   v12; a Mac has no touch screen), so the traffic lights are centred in it. The
 *   system drags the window from it and double-clicks it per the macOS setting (zoom / minimize)
 *   wherever the app lets it: [RaftDesktopTitleBarHitTest] tells it, per mouse event, whether a
 *   control of the app took the event.
 * - Any other JDK: the `apple.awt.*` root pane properties (full window content, transparent and
 *   untitled title bar). The title bar keeps its standard 28pt height, which macOS drags.
 *
 * Fullscreen hides the traffic lights: no band and no inset then. Windows / Linux: unchanged.
 */
internal class RaftDesktopTitleBar private constructor(
    private val customTitleBar: WindowDecorations.CustomTitleBar?,
) {
    /** Hit testing for the custom title bar; null on the `apple.awt` fallback. */
    val hitTest: RaftDesktopTitleBarHitTest? = customTitleBar?.let { RaftDesktopTitleBarHitTest(it::forceHitTest) }

    /** The title bar's band and controls for a window [fullscreen] or not. */
    fun metrics(fullscreen: Boolean): RaftDesktopTitleBarMetrics =
        raftDesktopTitleBarMetrics(
            customTitleBar = customTitleBar != null,
            fullscreen = fullscreen,
            reportedControlsWidth = customTitleBar?.leftInset?.takeIf { it.isFinite() }?.dp ?: 0.dp,
        )

    companion object {
        private const val TAG = "RaftDesktopTitleBar"

        /** Lays the title bar over [window]'s content on macOS; null elsewhere (nothing changes). */
        fun install(window: ComposeWindow): RaftDesktopTitleBar? {
            if (!RaftDesktopHistoryInput.isMac) return null
            val custom = runCatching { installCustomTitleBar(window) }
                .onFailure { RaftDesktopLog.w(TAG, "JBR custom title bar unavailable: $it") }
                .getOrNull()
            if (custom == null) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
            RaftDesktopLog.i(TAG, "installed mode=${if (custom != null) "jbr" else "apple.awt"}")
            return RaftDesktopTitleBar(custom)
        }

        private fun installCustomTitleBar(window: ComposeWindow): WindowDecorations.CustomTitleBar? {
            if (!JBR.isAvailable() || !JBR.isWindowDecorationsSupported()) return null
            val decorations = JBR.getWindowDecorations()
            val titleBar = decorations.createCustomTitleBar()
            titleBar.height = RaftTopBarDefaults.FineHeight.value
            decorations.setCustomTitleBar(window, titleBar)
            // The window keeps its title (Window menu, Mission Control); the title bar draws none.
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            return titleBar
        }
    }
}

/**
 * What the app keeps clear of the title bar: the [band] at the window's top (the pages' top safe
 * area, which the top bar covers itself) and the traffic lights' corner at the bar's start
 * ([controlsStart]).
 */
internal data class RaftDesktopTitleBarMetrics(val band: Dp, val controlsStart: Dp)

/** The standard macOS title bar's height, which the `apple.awt` fallback keeps. */
internal val RaftDesktopStandardTitleBarHeight: Dp = 28.dp

/**
 * The traffic lights' corner when the runtime does not report it: the three ~14pt buttons from
 * x≈8 (the system's place; the web places them at x16 and reserves 76px, its titlebar-area-x)
 * plus the same air before the bar's items (the bar's own 9dp leading padding comes on top).
 */
internal val RaftDesktopDefaultControlsWidth: Dp = 70.dp

/**
 * The band and the corner for a title bar ([customTitleBar]: JBR's, as tall as the top bar;
 * otherwise the standard one). [reportedControlsWidth] is the runtime's measure of the traffic
 * lights' corner (JBR `CustomTitleBar.getLeftInset`), used when it is known. Fullscreen: none.
 */
internal fun raftDesktopTitleBarMetrics(
    customTitleBar: Boolean,
    fullscreen: Boolean,
    reportedControlsWidth: Dp,
): RaftDesktopTitleBarMetrics {
    if (fullscreen) return RaftDesktopTitleBarMetrics(band = 0.dp, controlsStart = 0.dp)
    val band = if (customTitleBar) RaftTopBarDefaults.FineHeight else RaftDesktopStandardTitleBarHeight
    val controls = if (customTitleBar && reportedControlsWidth > 0.dp) reportedControlsWidth else RaftDesktopDefaultControlsWidth
    return RaftDesktopTitleBarMetrics(band = band, controlsStart = controls)
}

/**
 * Tells JBR's custom title bar, for each mouse event, whether the app takes it ([forceHitTest]
 * true: a click on a control) or the title bar does (false: drag the window, double-click to
 * zoom). The app takes an event a control consumed, and every event from a press a control took
 * until its release (Jewel's DecoratedWindow rule): a drag that starts on a button never moves
 * the window. Called in the event's dispatch, before JBR acts on it.
 *
 * Two places see each event: the top bar ([barModifier], after the bar's items), and the window
 * ([windowModifier], last of all) for the band over pages without a top bar. The window does not
 * override what the bar decided.
 */
internal class RaftDesktopTitleBarHitTest(private val forceHitTest: (client: Boolean) -> Unit) {
    private var inAppControl = false
    private var barEvent: Pair<PointerEventType, Long>? = null

    /** Whether the app takes an event of [type]; [consumed]: an app control consumed it. */
    fun decide(type: PointerEventType, consumed: Boolean): Boolean {
        if (!consumed && !inAppControl) return false
        if (type == PointerEventType.Press) inAppControl = true
        if (type == PointerEventType.Release) inAppControl = false
        return true
    }

    /** An event the top bar's items have seen: decide and tell JBR. */
    fun onBarEvent(type: PointerEventType, uptimeMillis: Long, consumed: Boolean) {
        if (!type.reportsHitTest()) return
        barEvent = type to uptimeMillis
        forceHitTest(decide(type, consumed))
    }

    /**
     * An event the whole window has seen: outside the top bar, the title bar takes an unconsumed
     * event [inBand] (the strip a page without a top bar keeps under the traffic lights).
     */
    fun onWindowEvent(type: PointerEventType, uptimeMillis: Long, consumed: Boolean, inBand: Boolean) {
        if (!type.reportsHitTest()) return
        if (barEvent == (type to uptimeMillis)) return
        forceHitTest(decide(type, consumed || !inBand))
    }

    val barModifier: Modifier =
        Modifier.pointerInput(this) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    onBarEvent(event.type, event.uptimeMillis(), event.anyConsumed())
                }
            }
        }

    fun windowModifier(band: () -> Dp): Modifier =
        Modifier.pointerInput(this) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val y = event.changes.firstOrNull()?.position?.y ?: Float.MAX_VALUE
                    onWindowEvent(event.type, event.uptimeMillis(), event.anyConsumed(), inBand = y < band().toPx())
                }
            }
        }

    private fun PointerEvent.anyConsumed(): Boolean = changes.any { it.isConsumed }

    private fun PointerEvent.uptimeMillis(): Long = changes.firstOrNull()?.uptimeMillis ?: -1L

    // JBR: every mouse event but exit and wheel.
    private fun PointerEventType.reportsHitTest(): Boolean = this != PointerEventType.Exit && this != PointerEventType.Scroll
}

/**
 * Provides the title bar to the window's pages: [LocalRaftWindowTitleBar] for the top bar, the
 * band as [LocalRaftFrameInsets]' top (menus keep out of it), and the window-level hit test.
 * Without a title bar (not macOS) [content] is as before.
 */
@Composable
internal fun RaftDesktopTitleBarProvider(
    titleBar: RaftDesktopTitleBar?,
    fullscreen: Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    if (titleBar == null) {
        content(Modifier)
        return
    }
    val metrics = titleBar.metrics(fullscreen)
    val windowTitleBar =
        remember(metrics) {
            RaftWindowTitleBar(
                height = metrics.band,
                startInset = metrics.controlsStart,
                modifier = titleBar.hitTest?.barModifier ?: Modifier,
            )
        }
    val band by rememberUpdatedState(metrics.band)
    val windowModifier = remember(titleBar) { titleBar.hitTest?.windowModifier { band } ?: Modifier }
    CompositionLocalProvider(
        LocalRaftWindowTitleBar provides windowTitleBar,
        LocalRaftFrameInsets provides RaftFrameInsets(top = metrics.band),
    ) {
        content(windowModifier)
    }
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
