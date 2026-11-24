package build.raft.app

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
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
 * All Wry-specific native operations are injected by [RaftDesktopWryGateway] so this Swing view
 * never calls Wry directly.
 */
class RaftDesktopWryNativeView(
    val content: Component,
    private val onMoveOffScreen: (width: Int, height: Int) -> Unit,
    private val onResyncBounds: () -> Unit,
) : JPanel() {
    /** Set once the page is ready to be seen (the host's readiness check). */
    private var revealed = false
    private var attached = false

    private val onScreen: Boolean get() = revealed && attached

    init {
        isOpaque = false
        background = TransparentAwtColor
        layout = OffscreenUntilShownLayout()
        add(content)
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

    fun reveal() = setRevealed(true)

    fun setRevealed(show: Boolean) {
        if (revealed == show) return
        revealed = show
        updateBackdrop()
        relayout()
    }

    override fun addNotify() {
        super.addNotify()
        attached = true
        updateBackdrop()
        relayout()
    }

    override fun removeNotify() {
        attached = false
        updateBackdrop()
        hideNative()
        super.removeNotify()
    }

    private fun updateBackdrop() {
        val visibleOnScreen = onScreen
        isOpaque = visibleOnScreen
        background = if (visibleOnScreen) java.awt.Color.WHITE else TransparentAwtColor
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
        onResyncBounds()
    }

    private fun hideNative() {
        onMoveOffScreen(content.width.coerceAtLeast(1), content.height.coerceAtLeast(1))
    }

    /** Lays the panel out over this component on screen, or at the same size far left of the window. */
    private inner class OffscreenUntilShownLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) = Unit

        override fun removeLayoutComponent(comp: Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension = parent.size

        override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, 0)

        override fun layoutContainer(parent: Container) {
            val w = parent.width
            val h = parent.height
            val x = if (onScreen) 0 else -(w + OffscreenGap)
            content.setBounds(x, 0, w, h)
            content.doLayout()
        }
    }

    private companion object {
        const val OffscreenGap = 20_000
        val TransparentAwtColor = java.awt.Color(0, 0, 0, 0)
    }
}
