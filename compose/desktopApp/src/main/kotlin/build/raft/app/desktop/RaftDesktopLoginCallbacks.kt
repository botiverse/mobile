package build.raft.app.desktop

import build.raft.app.data.RaftAppLoginBridge
import java.awt.Desktop

/**
 * Browser sign-in callbacks (`raft://login/callback?...`). The packaged macOS app declares the
 * `raft` URL scheme (Info.plist, see build.gradle.kts), so macOS delivers the URL through the
 * open-URI handler; a `raft://` command-line argument is accepted too (development runs, Linux).
 */
object RaftDesktopLoginCallbacks {
    fun install(args: Array<String>) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
                Desktop.getDesktop().setOpenURIHandler { event -> accept(event.uri.toString()) }
            }
        }.onFailure { log("login callbacks: no open-URI handler (${it.message})") }
        args.filter { it.startsWith("raft://") }.forEach(::accept)
    }

    fun accept(uri: String) {
        val accepted = RaftAppLoginBridge.completeCallbackUri(uri)
        log("login callback accepted=$accepted")
    }
}
