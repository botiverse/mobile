package build.raft.app.desktop

import build.raft.app.RaftDesktopFileLog
import build.raft.app.data.RaftDesktopPaths
import build.raft.app.data.isAllowedAppLoginUrl
import build.raft.app.toRaftJsonString
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `settingsCapability`: the action-multiplexed settings host. M1 serves the browser sign-in
 * (`appLoginPrepare` / `appLoginOpen`, the Android host's contract); the callback comes back as
 * [APP_LOGIN_RETURN_URI] (see [RaftDesktopLoginCallbacks]); `loadStorage` measures the app's data
 * directory ([storagePayload]; clearing is not offered yet); `loadDiagnostics` / `shareDiagnostics`
 * read and reveal the log file ([RaftDesktopFileLog]). TODO(desktop): every other action
 * (about, developer environment, notifications, account linking) answers
 * "not implemented" and the shared pages fall back as for an unsupported host action.
 */
object RaftDesktopSettingsCapability {
    /**
     * One of the server's allowed app-login return URIs (exact match), the release apps' `raft`
     * (see [RaftDesktopLoginCallbacks] for Raft Desktop also claiming it).
     */
    const val APP_LOGIN_RETURN_URI = "${RaftDesktopLoginCallbacks.SCHEME}://login/callback"

    private val random = SecureRandom()

    fun handle(command: JsonObject, callback: ((String?) -> Unit)?) {
        val action = command.string("action")
        if (action == "loadStorage") {
            callback?.invoke(storagePayload(RaftDesktopPaths.databaseDir, RaftDesktopPaths.cacheDir, RaftDesktopPaths.dataDir).toRaftJsonString())
            return
        }
        // Settings > About > Diagnostics: the log file's newest lines, and Export logs shows the
        // log folder (~/Library/Logs/Raft) in Finder so the files can be attached to a report.
        if (action == "loadDiagnostics" || action == "shareDiagnostics") {
            Thread({
                val payload =
                    if (action == "loadDiagnostics") {
                        diagnosticsPayload(RaftDesktopFileLog.recentLines(DIAGNOSTICS_LINES))
                    } else {
                        revealLogFolder()
                    }
                callback?.invoke(payload.toRaftJsonString())
            }, "RaftDesktopDiagnostics").apply { isDaemon = true }.start()
            return
        }
        val result =
            when (action) {
                "appLoginPrepare" -> prepareAppLogin()
                "appLoginOpen" -> openAppLogin(command.string("authorizationUrl"))
                else -> {
                    log("settingsCapability not implemented: action=$action")
                    callback?.invoke(RaftDesktopBridgeTransport.NOT_IMPLEMENTED)
                    return
                }
            }
        callback?.invoke(JsonObject(result.mapValues { JsonPrimitive(it.value) }).toRaftJsonString())
    }

    /**
     * Settings > Storage: the local database and the cache (images, snapshots, clipboard pastes),
     * plus the whole data directory as the total. Nothing is clearable yet (no row offers Clear),
     * so `clearStorage` stays unimplemented. Titles as the Android host's (host-provided rows).
     */
    internal fun storagePayload(databaseDir: File, cacheDir: File, dataDir: File): JsonObject {
        fun row(target: String, title: String, detail: String, dir: File) =
            JsonObject(
                mapOf(
                    "target" to JsonPrimitive(target),
                    "title" to JsonPrimitive(title),
                    "detail" to JsonPrimitive(detail),
                    "sizeBytes" to JsonPrimitive(directorySize(dir)),
                    "clearEnabled" to JsonPrimitive(false),
                ),
            )
        return JsonObject(
            mapOf(
                "status" to JsonPrimitive("ok"),
                "totalSizeBytes" to JsonPrimitive(directorySize(dataDir)),
                "rows" to
                    JsonArray(
                        listOf(
                            row("database", "Local database", "Messages, task metadata, and server snapshots stored on this computer.", databaseDir),
                            row("cache", "Cache", "Image previews, snapshots and pasted files kept for faster viewing.", cacheDir),
                        ),
                    ),
            ),
        )
    }

    private const val DIAGNOSTICS_LINES = 300

    /** `loadDiagnostics`: log lines (`<time> <level> [thread] <tag>: <message>`) as entries, newest first. */
    internal fun diagnosticsPayload(lines: List<String>): JsonObject {
        val entries =
            lines.asReversed().map { line ->
                val match = LOG_LINE.matchEntire(line)
                JsonObject(
                    mapOf(
                        "timestampLabel" to JsonPrimitive(match?.groupValues?.get(1)?.substringAfter('T')?.take(12).orEmpty()),
                        "tag" to JsonPrimitive(match?.let { "${it.groupValues[2]} ${it.groupValues[3]}" }.orEmpty()),
                        "message" to JsonPrimitive(match?.groupValues?.get(4) ?: line),
                    ),
                )
            }
        return JsonObject(mapOf("status" to JsonPrimitive("ok"), "entries" to JsonArray(entries)))
    }

    private val LOG_LINE = Regex("""(\S+) ([DIWEF]) \[[^\]]*] ([^:]+): (.*)""")

    private fun revealLogFolder(): JsonObject {
        val dir = RaftDesktopFileLog.directory ?: RaftDesktopPaths.logDir
        RaftDesktopFileLog.flush()
        val current = File(dir, "raft.log")
        val shown =
            runCatching {
                val desktop = Desktop.getDesktop()
                if (current.isFile && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                    desktop.browseFileDirectory(current)
                } else {
                    desktop.open(dir)
                }
            }
        log("diagnostics: reveal log folder result=${if (shown.isSuccess) "ok" else shown.exceptionOrNull()?.javaClass?.simpleName}")
        return JsonObject(
            mapOf(
                "status" to JsonPrimitive(if (shown.isSuccess) "ok" else "error"),
                "path" to JsonPrimitive(dir.absolutePath),
            ),
        )
    }

    private fun directorySize(dir: File): Long =
        runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    private fun prepareAppLogin(): Map<String, String> {
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val challenge =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return mapOf(
            "status" to "prepared",
            "generation" to UUID.randomUUID().toString(),
            "codeVerifier" to verifier,
            "codeChallenge" to challenge,
            "returnUri" to APP_LOGIN_RETURN_URI,
        )
    }

    private fun openAppLogin(authorizationUrl: String): Map<String, String> {
        // Same rule as the shared app login: https, or plain http to this machine (local server).
        if (!isAllowedAppLoginUrl(authorizationUrl)) {
            return mapOf("status" to "error", "code" to "settings_mobile_oauth_url_invalid")
        }
        log("app login: opening the browser")
        return runCatching { Desktop.getDesktop().browse(URI(authorizationUrl)) }
            .fold(
                onSuccess = { mapOf("status" to "started") },
                onFailure = {
                    log("app login: no browser available (${it::class.simpleName})")
                    mapOf("status" to "error", "code" to "settings_mobile_oauth_browser_unavailable")
                },
            )
    }
}
