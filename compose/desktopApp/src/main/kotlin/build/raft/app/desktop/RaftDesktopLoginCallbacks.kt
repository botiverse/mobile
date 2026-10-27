package build.raft.app.desktop

import build.raft.app.data.RaftAppLoginBridge
import build.raft.app.data.RaftDesktopPaths
import build.raft.app.data.RaftDesktopPrivateFiles
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Browser sign-in callbacks (`raft-mac://login/callback?...`).
 *
 * - macOS: the packaged app declares the `raft-mac` URL scheme (Info.plist, see build.gradle.kts);
 *   macOS delivers the URL to the running app's open-URI handler. Not `raft`: the Electron
 *   "Raft Desktop" app registers that scheme (for its sign-in and `raft://v1/...` notification
 *   links), and macOS gives a scheme to only one app.
 * - Elsewhere (and development runs) a URL handler starts the app again with the URL as an
 *   argument: a second instance forwards its `raft-mac://` arguments to the running one over a
 *   loopback socket and exits, so the waiting sign-in completes. The running instance writes its
 *   port and a random 32-byte secret to an owner-only (0600) file in the data dir; a forward must
 *   present the secret (compared in constant time), so another local user cannot inject callbacks.
 */
object RaftDesktopLoginCallbacks {
    /** The URL scheme this app registers (Info.plist `CFBundleURLSchemes`, build.gradle.kts). */
    const val SCHEME = "raft-mac"
    private const val PREFIX = "$SCHEME://"
    private const val MAX_LINES = 8
    private const val MAX_LINE_CHARS = 4_096
    private const val SOCKET_TIMEOUT_MILLIS = 2_000

    private val instanceFile: File get() = File(RaftDesktopPaths.dataDir, "instance")

    /** Call first in main: may forward [args] to a running instance and exit this process. */
    fun install(args: Array<String>) {
        val callbacks = callbackArguments(args.asList())
        if (forwardToRunningInstance(callbacks)) {
            println("RaftDesktop: forwarded ${callbacks.size} callback(s) to the running instance")
            exitProcess(0)
        }
        listenForForwardedCallbacks()
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
                Desktop.getDesktop().setOpenURIHandler { event -> accept(event.uri.toString()) }
            }
        }.onFailure { log("login callbacks: no open-URI handler (${it::class.simpleName})") }
        callbacks.forEach(::accept)
    }

    /** The arguments / forwarded lines that are this app's callbacks (`raft-mac://`, not `raft://`). */
    internal fun callbackArguments(values: List<String>): List<String> = values.filter { it.startsWith(PREFIX) }

    fun accept(uri: String) {
        val accepted = RaftAppLoginBridge.completeCallbackUri(uri)
        log("login callback accepted=$accepted")
    }

    private fun forwardToRunningInstance(callbacks: List<String>): Boolean {
        val lines = runCatching { instanceFile.readLines() }.getOrNull() ?: return false
        val port = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return false
        val secret = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return runCatching {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                socket.getOutputStream().bufferedWriter().apply {
                    write(secret + "\n")
                    callbacks.take(MAX_LINES - 1).forEach { write(it.take(MAX_LINE_CHARS) + "\n") }
                    flush()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun listenForForwardedCallbacks() {
        val server = runCatching { ServerSocket(0, 8, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return
        val secret = HexFormat.of().formatHex(ByteArray(32).also(SecureRandom()::nextBytes))
        try {
            RaftDesktopPrivateFiles.writeAtomically(instanceFile, "${server.localPort}\n$secret\n")
        } catch (e: IOException) {
            // Without the file no second launch can reach this one; sign-in still works on macOS.
            log("login callbacks: instance file not written (${e::class.simpleName})")
            runCatching { server.close() }
            return
        }
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { instanceFile.delete() } })
        val expected = secret.toByteArray(Charsets.US_ASCII)
        thread(isDaemon = true, name = "raft-instance-forwarding") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: continue
                runCatching {
                    socket.use {
                        it.soTimeout = SOCKET_TIMEOUT_MILLIS
                        val lines = readBoundedLines(it.getInputStream())
                        val presented = lines.firstOrNull()?.toByteArray(Charsets.US_ASCII) ?: return@use
                        if (!MessageDigest.isEqual(presented, expected)) {
                            log("login callbacks: forward rejected")
                            return@use
                        }
                        callbackArguments(lines.drop(1)).forEach(::accept)
                    }
                }
            }
        }
    }

    /** At most [MAX_LINES] lines of at most [MAX_LINE_CHARS] characters; the rest is dropped. */
    private fun readBoundedLines(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var overflow = false
        while (lines.size < MAX_LINES) {
            val byte = input.read()
            if (byte == -1) break
            if (byte == '\n'.code) {
                if (!overflow) lines += current.toString()
                current.clear()
                overflow = false
            } else if (current.length < MAX_LINE_CHARS) {
                current.append(byte.toChar())
            } else {
                overflow = true
            }
        }
        if (current.isNotEmpty() && !overflow && lines.size < MAX_LINES) lines += current.toString()
        return lines
    }
}
