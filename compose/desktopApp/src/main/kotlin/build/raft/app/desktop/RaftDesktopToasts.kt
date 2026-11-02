package build.raft.app.desktop

import build.raft.app.cmp.RaftCmpToasts

/**
 * The bridge `toast`, which pages send only when no window could show it themselves
 * (`BridgeModule.toast` and the wire capabilities post to [RaftCmpToasts] first): the
 * design-system toast of the app's last active window, even one in the background (the desktop
 * has no system toast to fall back to).
 */
object RaftDesktopToasts {
    fun show(text: String) {
        if (text.isBlank()) return
        if (!RaftCmpToasts.post(text, foregroundOnly = false)) log("toast dropped, no window: $text")
    }
}
