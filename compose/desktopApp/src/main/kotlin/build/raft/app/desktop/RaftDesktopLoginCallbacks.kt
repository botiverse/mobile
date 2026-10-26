package build.raft.app.desktop

import build.raft.app.data.RaftAppLoginBridge
import build.raft.app.data.RaftDesktopPaths
import java.awt.Desktop
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Browser sign-in callbacks (`raft://login/callback?...`).
 *
 * - macOS: the packaged app declares the `raft` URL scheme (Info.plist, see build.gradle.kts);
 *   macOS delivers the URL to the running app's open-URI handler.
 * - Elsewhere (and development runs) a URL handler starts the app again with the URL as an
 *   argument: a second instance forwards its `raft://` arguments to the running one over a
 *   loopback socket (port in the data dir) and exits, so the waiting sign-in completes.
 */
object RaftDesktopLoginCallbacks {
    private val portFile: File get() = File(RaftDesktopPaths.dataDir, "instance.port")

    /** Call first in main: may forward [args] to a running instance and exit this process. */
    fun install(args: Array<String>) {
        val callbacks = args.filter { it.startsWith("raft://") }
        if (forwardToRunningInstance(callbacks)) {
            println("RaftDesktop: forwarded ${callbacks.size} callback(s) to the running instance")
            exitProcess(0)
        }
        listenForForwardedCallbacks()
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
                Desktop.getDesktop().setOpenURIHandler { event -> accept(event.uri.toString()) }
            }
        }.onFailure { log("login callbacks: no open-URI handler (${it.message})") }
        callbacks.forEach(::accept)
    }

    fun accept(uri: String) {
        val accepted = RaftAppLoginBridge.completeCallbackUri(uri)
        log("login callback accepted=$accepted")
    }

    private fun forwardToRunningInstance(callbacks: List<String>): Boolean {
        val port = runCatching { portFile.readText().trim().toInt() }.getOrNull() ?: return false
        return runCatching {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().bufferedWriter().apply {
                    write(HELLO + "\n")
                    callbacks.forEach { write(it + "\n") }
                    flush()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun listenForForwardedCallbacks() {
        val server = runCatching { ServerSocket(0, 8, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return
        portFile.writeText(server.localPort.toString())
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { portFile.delete() } })
        thread(isDaemon = true, name = "raft-instance-forwarding") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: continue
                runCatching {
                    socket.use {
                        val lines = it.getInputStream().bufferedReader().readLines()
                        if (lines.firstOrNull() == HELLO) {
                            lines.drop(1).filter { line -> line.startsWith("raft://") }.forEach(::accept)
                        }
                    }
                }
            }
        }
    }

    private const val HELLO = "raft-desktop-instance-v1"
}
