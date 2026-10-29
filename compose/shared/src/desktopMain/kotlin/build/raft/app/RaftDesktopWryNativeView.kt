package build.raft.app

import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.swing.JPanel

/**
 * The Swing component Compose hosts for one desktop WebView: it owns where Wry's native view is.
 *
 * Wry's view is not part of the AWT hierarchy: it is a native child of the window that follows the
 * panel only when the panel lays out, and outlives it by 400 ms on macOS after the panel leaves
 * (Wry's deferred destroy). So, without this, a page that leaves composition (Back, another page
 * on top: Compose removes interop views that are not placed) left the web content on screen over
 * the native shell for a moment, and a move without a resize (a sliding page) left it behind.
 *
 * - Off screen ([onScreen] false) the panel is laid out at full size but far left of the window,
 *   so the WebView keeps its real viewport while it loads and nothing of it shows.
 * - Leaving the hierarchy moves the native view off screen at once ([removeNotify]), before Wry
 *   gets to destroy it; coming back (or [reveal]) puts it where the panel is.
 * - Every move or resize of the panel or an ancestor re-sends the native bounds.
 *
 * The panel's private bounds sync is reached by reflection ([RaftDesktopWryPanelAccess]); the jar is pinned by
 * checksum (desktopApp `verifyWryWebviewChecksum`) and a test checks the members exist.
 */
internal class RaftDesktopWryNativeView(val panel: WryWebViewPanel) : JPanel() {
    /** Set once the page is ready to be seen (the host's readiness check). */
    private var revealed = false
    private var attached = false

    private val onScreen: Boolean get() = revealed && attached

    init {
        background = java.awt.Color.WHITE
        layout = OffscreenUntilShownLayout()
        add(panel)
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) = syncNative()

                override fun componentResized(e: ComponentEvent) = syncNative()

                override fun componentHidden(e: ComponentEvent) = hideNative()

                override fun componentShown(e: ComponentEvent) = syncNative()
            },
        )
        addHierarchyBoundsListener(
            object : HierarchyBoundsAdapter() {
                override fun ancestorMoved(e: HierarchyEvent) = syncNative()

                override fun ancestorResized(e: HierarchyEvent) = syncNative()
            },
        )
    }

    fun reveal() {
        if (revealed) return
        revealed = true
        relayout()
    }

    override fun addNotify() {
        super.addNotify()
        attached = true
        relayout()
    }

    override fun removeNotify() {
        attached = false
        hideNative()
        super.removeNotify()
    }

    private fun relayout() {
        revalidate()
        doLayout()
        syncNative()
    }

    /** Re-sends the panel's bounds to the native view (Wry only does it on a panel resize). */
    private fun syncNative() {
        if (!onScreen) {
            hideNative()
            return
        }
        RaftDesktopWryPanelAccess.resyncBounds(panel)
    }

    private fun hideNative() {
        RaftDesktopWryPanelAccess.moveOffScreen(panel)
    }

    /** Lays the panel out over this component on screen, or at the same size far left of the window. */
    private inner class OffscreenUntilShownLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: java.awt.Component?) = Unit

        override fun removeLayoutComponent(comp: java.awt.Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension = parent.size

        override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, 0)

        override fun layoutContainer(parent: Container) {
            val w = parent.width
            val h = parent.height
            val x = if (onScreen) 0 else -(w + OffscreenGap)
            panel.setBounds(x, 0, w, h)
            panel.doLayout()
        }
    }

    private companion object {
        const val OffscreenGap = 20_000
    }
}

/** Reflection into [WryWebViewPanel]'s bounds sync (pinned artifact; see [RaftDesktopWryNativeView]). */
object RaftDesktopWryPanelAccess {
    private val webviewIdField: Field? = member { WryWebViewPanel::class.java.getDeclaredField("webviewId") }
    private val lastBoundsField: Field? = member { WryWebViewPanel::class.java.getDeclaredField("lastBounds") }
    private val updateBoundsMethod: Method? = member { WryWebViewPanel::class.java.getDeclaredMethod("updateBounds") }

    /** Whether every member this needs is there (a test pins it to the shipped jar). */
    val available: Boolean get() = webviewIdField != null && lastBoundsField != null && updateBoundsMethod != null

    internal fun moveOffScreen(panel: WryWebViewPanel) {
        val id = runCatching { webviewIdField?.get(panel) as? ULong }.getOrNull() ?: return
        runCatching {
            // Same size, far off the window: the page keeps its viewport (a 1px one would relayout it).
            io.github.kdroidfilter.webview.wry.setBounds(
                id,
                -OffscreenOrigin,
                -OffscreenOrigin,
                panel.width.coerceAtLeast(1),
                panel.height.coerceAtLeast(1),
            )
            // The panel's cache of what it last sent is stale now: the next sync must send again.
            lastBoundsField?.set(panel, null)
        }
    }

    internal fun resyncBounds(panel: WryWebViewPanel) {
        runCatching {
            lastBoundsField?.set(panel, null)
            updateBoundsMethod?.invoke(panel)
        }
    }

    private fun <T : java.lang.reflect.AccessibleObject> member(get: () -> T): T? =
        runCatching { get().apply { isAccessible = true } }.getOrNull()

    private const val OffscreenOrigin = 32_000
}
