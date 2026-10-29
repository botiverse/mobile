package build.raft.app.desktop

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.lang.reflect.Proxy
import javax.swing.JComponent

/**
 * Input diagnostics for the history buttons (artin's Logitech side buttons did nothing on macOS):
 * every raw AWT mouse press and every Cmd / Ctrl / Alt key press (key code only, never the typed
 * character) goes to the log, before Compose sees it. Log-only: navigation stays with Compose's
 * pointer / key handlers, so nothing steps twice.
 */
internal object RaftDesktopInputDiagnostics {
    private const val TAG = "RaftDesktopInput"
    private const val SHORTCUT_MASK = InputEvent.META_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK

    fun installAwtListener() {
        val toolkit = Toolkit.getDefaultToolkit()
        val buttons = runCatching { toolkit.getDesktopProperty("awt.mouse.numButtons") }.getOrNull()
        RaftDesktopLog.i(TAG, "awt input listener buttons=$buttons extraButtons=${toolkit.areExtraMouseButtonsEnabled()}")
        toolkit.addAWTEventListener(
            { event ->
                when {
                    event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED ->
                        RaftDesktopLog.i(
                            TAG,
                            "awt mouse_pressed button=${event.button} modifiersEx=0x${Integer.toHexString(event.modifiersEx)}" +
                                " (${InputEvent.getModifiersExText(event.modifiersEx)}) clickCount=${event.clickCount}",
                        )
                    event is KeyEvent && event.id == KeyEvent.KEY_PRESSED && (event.modifiersEx and SHORTCUT_MASK) != 0 ->
                        RaftDesktopLog.i(
                            TAG,
                            "awt key_pressed keyCode=${event.keyCode} (${KeyEvent.getKeyText(event.keyCode)})" +
                                " modifiersEx=${InputEvent.getModifiersExText(event.modifiersEx)}",
                        )
                }
            },
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK,
        )
    }

    /**
     * macOS swipe events on [root] (the window's root pane): logged, and left / right mapped to a
     * history step ([raftDesktopHistorySwipe]). `com.apple.eawt.event` is a JDK-internal package
     * (not exported from java.desktop), so this goes through reflection and needs
     * `--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED` (desktopApp's jvmArgs).
     * Elsewhere, or without the export, it logs why and does nothing.
     */
    fun installSwipes(root: JComponent, onHistory: (RaftDesktopHistoryDirection) -> Unit) {
        if (!RaftDesktopHistoryInput.isMac) return
        runCatching {
            val swipeListener = Class.forName("com.apple.eawt.event.SwipeListener")
            val gestureListener = Class.forName("com.apple.eawt.event.GestureListener")
            val utilities = Class.forName("com.apple.eawt.event.GestureUtilities")
            val listener =
                Proxy.newProxyInstance(ClassLoader.getSystemClassLoader(), arrayOf(swipeListener)) { proxy, method, args ->
                    when (method.name) {
                        "swipedUp", "swipedDown", "swipedLeft", "swipedRight" -> {
                            val direction = raftDesktopHistorySwipe(method.name)
                            RaftDesktopLog.i(TAG, "swipe ${method.name} history=${direction?.wireName}")
                            direction?.let(onHistory)
                            null
                        }
                        "equals" -> proxy === args?.firstOrNull()
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "RaftDesktopSwipeListener"
                        else -> null
                    }
                }
            utilities.getMethod("addGestureListenerTo", JComponent::class.java, gestureListener).invoke(null, root, listener)
        }.onSuccess {
            RaftDesktopLog.i(TAG, "swipe listener installed")
        }.onFailure {
            RaftDesktopLog.w(TAG, "swipe listener unavailable: ${it::class.simpleName}: ${it.message}")
        }
    }
}
