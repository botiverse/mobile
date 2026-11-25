package build.raft.app.desktop

import build.raft.app.RaftDesktopFileLog
import build.raft.app.data.RaftDesktopPaths
import build.raft.app.data.isAllowedAppLoginUrl
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMutationResult
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideSnapshot
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.network.sync.ProcessReadStateV1DiagnosticOverride
import build.raft.app.network.sync.ReadStateV1DiagnosticExactScope
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideController
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideMode
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideSnapshot
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
 * read and reveal the log file ([RaftDesktopFileLog]) and expose process-local diagnostic feature
 * flag overrides (`setDiagnosticFeatureFlagOverride`) strictly when entitled via
 * [DESKTOP_DEBUG_PROPERTY] or [DESKTOP_DIAGNOSTIC_OVERRIDES_PROPERTY]. TODO(desktop): every other
 * action (about, developer environment, notifications, account linking) answers "not implemented"
 * and the shared pages fall back as for an unsupported host action.
 */
object RaftDesktopSettingsCapability {
    /**
     * One of the server's allowed app-login return URIs (exact match), the release apps' `raft`
     * (see [RaftDesktopLoginCallbacks] for Raft Desktop also claiming it).
     */
    const val APP_LOGIN_RETURN_URI = "${RaftDesktopLoginCallbacks.SCHEME}://login/callback"

    internal const val DESKTOP_DEBUG_PROPERTY = "raft.desktop.debug"
    internal const val DESKTOP_DIAGNOSTIC_OVERRIDES_PROPERTY = "raft.desktop.diagnosticOverrides"

    private val random = SecureRandom()

    internal fun isDebugBuild(
        readProperty: (String) -> String? = System::getProperty,
    ): Boolean = readProperty(DESKTOP_DEBUG_PROPERTY)?.trim().equals("true", ignoreCase = true)

    internal fun isDiagnosticFeatureFlagOverridesEntitled(
        readProperty: (String) -> String? = System::getProperty,
    ): Boolean =
        isDebugBuild(readProperty) ||
            readProperty(DESKTOP_DIAGNOSTIC_OVERRIDES_PROPERTY)?.trim().equals("true", ignoreCase = true)

    fun handle(command: JsonObject, callback: ((String?) -> Unit)?) {
        val action = command.string("action")
        if (action == "loadStorage") {
            callback?.invoke(storagePayload(RaftDesktopPaths.databaseDir, RaftDesktopPaths.cacheDir, RaftDesktopPaths.dataDir).toRaftJsonString())
            return
        }
        if (action == "setDiagnosticFeatureFlagOverride") {
            callback?.invoke(setDiagnosticFeatureFlagOverridePayload(command).toRaftJsonString())
            return
        }
        // Settings > About / Developer > Diagnostics: the log file's newest lines plus redacted
        // diagnostic feature-flag snapshots, and Export logs shows the log folder in Finder.
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
    internal fun diagnosticsPayload(
        lines: List<String>,
        registry: DiagnosticFeatureFlagOverrideRegistry = ProcessDiagnosticFeatureFlagOverrides.registry,
        readStateController: ReadStateV1DiagnosticOverrideController = ProcessReadStateV1DiagnosticOverride.controller,
        entitled: Boolean = isDiagnosticFeatureFlagOverridesEntitled(),
    ): JsonObject {
        registry.configureEntitlement(entitled)
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
        return JsonObject(
            mapOf(
                "status" to JsonPrimitive("ok"),
                "entries" to JsonArray(entries),
                "readStateV1DiagnosticJson" to JsonPrimitive(readStateController.snapshot().toRedactedJson().toRaftJsonString()),
                "diagnosticFeatureFlagOverridesJson" to JsonPrimitive(registry.snapshots().toRedactedJson().toRaftJsonString()),
            ),
        )
    }

    internal fun setDiagnosticFeatureFlagOverridePayload(
        command: JsonObject,
        registry: DiagnosticFeatureFlagOverrideRegistry = ProcessDiagnosticFeatureFlagOverrides.registry,
        entitled: Boolean = isDiagnosticFeatureFlagOverridesEntitled(),
    ): JsonObject {
        registry.configureEntitlement(entitled)
        val key = command.string("key").trim()
        val mode =
            when (command.string("mode").trim()) {
                "force_off" -> DiagnosticFeatureFlagOverrideMode.ForceOff
                "force_on" -> DiagnosticFeatureFlagOverrideMode.ForceOn
                "server" -> DiagnosticFeatureFlagOverrideMode.Server
                else ->
                    return JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("error"),
                            "code" to JsonPrimitive("diagnostic_feature_flag_override_mode_unknown"),
                        ),
                    )
            }
        return when (val result = registry.setMode(key, mode)) {
            is DiagnosticFeatureFlagOverrideMutationResult.Applied -> {
                val snapshot = result.snapshot
                log(
                    "diagnostic_override key=${snapshot.key} mode=${snapshot.mode.name} " +
                        "scope=${snapshot.boundScope?.toStableRedactedLabel() ?: "none"}",
                )
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("ok"),
                        "diagnosticFeatureFlagOverridesJson" to JsonPrimitive(registry.snapshots().toRedactedJson().toRaftJsonString()),
                    ),
                )
            }
            is DiagnosticFeatureFlagOverrideMutationResult.Rejected ->
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("error"),
                        "code" to JsonPrimitive(result.code),
                        "diagnosticFeatureFlagOverridesJson" to JsonPrimitive(registry.snapshots().toRedactedJson().toRaftJsonString()),
                    ),
                )
        }
    }

    private fun List<DiagnosticFeatureFlagOverrideSnapshot>.toRedactedJson(): JsonArray =
        JsonArray(map { it.toRedactedJson() })

    private fun DiagnosticFeatureFlagOverrideSnapshot.toRedactedJson(): JsonObject =
        JsonObject(
            mapOf(
                "key" to JsonPrimitive(key),
                "supported" to JsonPrimitive(supported),
                "scopeKind" to JsonPrimitive(scopeKind?.name ?: "unsupported"),
                "entitled" to JsonPrimitive(entitled),
                "clientOverride" to JsonPrimitive(mode.name),
                "expiresAtMillis" to JsonPrimitive(expiresAtMillis ?: 0L),
                "clearReason" to JsonPrimitive(clearReason.orEmpty()),
                "observedScope" to JsonPrimitive(observedScope?.toStableRedactedLabel().orEmpty()),
                "boundScope" to JsonPrimitive(boundScope?.toStableRedactedLabel().orEmpty()),
                "serverEffective" to JsonPrimitive(lastDecision?.serverEffective?.toString().orEmpty()),
                "finalEffective" to JsonPrimitive(diagnosticFinalEffective()?.toString().orEmpty()),
                "precedence" to JsonPrimitive(diagnosticPrecedence()),
                "exactScopeMatched" to JsonPrimitive(lastDecision?.exactScopeMatched ?: false),
                "evidence" to
                    JsonPrimitive(
                        listOfNotNull(
                            evidence.selectedScope?.let { scope -> "selected=${scope.toStableRedactedLabel()}" },
                            evidence.witnessGeneration?.let { "generation=$it" },
                            evidence.durableReadStateVersion?.let { version ->
                                "durable=($version,${evidence.durableMaxReadSeq ?: "--"})"
                            },
                            evidence.latestAdjudication?.takeIf { it.isNotBlank() }?.let { "adjudication=$it" },
                            evidence.destinationPublication?.takeIf { it.isNotBlank() }?.let { "publication=$it" },
                            evidence.barrierState?.takeIf { it.isNotBlank() }?.let { "barrier=$it" },
                            evidence.stalePublicationRejectedCount.takeIf { it > 0L }?.let { "staleRejected=$it" },
                        ).joinToString(" · "),
                    ),
            ),
        )

    private fun ReadStateV1DiagnosticOverrideSnapshot.toRedactedJson(): JsonObject =
        JsonObject(
            mapOf(
                "entitled" to JsonPrimitive(entitled),
                "clientOverride" to JsonPrimitive(mode.name),
                "expiresAtMillis" to JsonPrimitive(expiresAtMillis ?: 0L),
                "clearReason" to JsonPrimitive(clearReason.orEmpty()),
                "observedScope" to JsonPrimitive(observedScope?.toStableRedactedLabel().orEmpty()),
                "boundScope" to JsonPrimitive(boundScope?.toStableRedactedLabel().orEmpty()),
                "serverEffective" to JsonPrimitive(lastDecision?.serverEffective?.toString().orEmpty()),
                "finalEffective" to JsonPrimitive(diagnosticFinalEffective()?.toString().orEmpty()),
                "precedence" to JsonPrimitive(diagnosticPrecedence()),
                "exactScopeMatched" to JsonPrimitive(lastDecision?.exactScopeMatched ?: false),
                "selectedScope" to JsonPrimitive(selectedScope?.toStableRedactedLabel().orEmpty()),
                "witnessGeneration" to JsonPrimitive(witnessGeneration ?: 0L),
                "durableReadStateVersion" to JsonPrimitive(durableReadStateVersion ?: 0L),
                "durableMaxReadSeq" to JsonPrimitive(durableMaxReadSeq ?: -1L),
                "latestAdjudication" to JsonPrimitive(latestAdjudication.orEmpty()),
                "destinationPublication" to JsonPrimitive(destinationPublication.orEmpty()),
                "barrierState" to JsonPrimitive(barrierState.orEmpty()),
                "stalePublicationRejectedCount" to JsonPrimitive(stalePublicationRejectedCount),
            ),
        )

    private fun DiagnosticFeatureFlagOverrideSnapshot.diagnosticFinalEffective(): Boolean? =
        when (mode) {
            DiagnosticFeatureFlagOverrideMode.ForceOff -> false
            DiagnosticFeatureFlagOverrideMode.ForceOn -> true
            DiagnosticFeatureFlagOverrideMode.Server -> lastDecision?.serverEffective
        }

    private fun DiagnosticFeatureFlagOverrideSnapshot.diagnosticPrecedence(): String =
        when (mode) {
            DiagnosticFeatureFlagOverrideMode.ForceOff -> "diagnostic_override_force_off"
            DiagnosticFeatureFlagOverrideMode.ForceOn -> "diagnostic_override_force_on"
            DiagnosticFeatureFlagOverrideMode.Server -> lastDecision?.precedence ?: "not_observed"
        }

    private fun ReadStateV1DiagnosticOverrideSnapshot.diagnosticFinalEffective(): Boolean? =
        when (mode) {
            ReadStateV1DiagnosticOverrideMode.ForceOff -> false
            ReadStateV1DiagnosticOverrideMode.ForceOn -> true
            ReadStateV1DiagnosticOverrideMode.Server -> lastDecision?.serverEffective
        }

    private fun ReadStateV1DiagnosticOverrideSnapshot.diagnosticPrecedence(): String =
        when (mode) {
            ReadStateV1DiagnosticOverrideMode.ForceOff -> "diagnostic_override_force_off"
            ReadStateV1DiagnosticOverrideMode.ForceOn -> "diagnostic_override_force_on"
            ReadStateV1DiagnosticOverrideMode.Server -> lastDecision?.precedence ?: "not_observed"
        }

    private fun ReadStateV1DiagnosticExactScope.toStableRedactedLabel(): String =
        "env=${environmentId.stableDiagnosticId()} account=${accountId.stableDiagnosticId()} " +
            "server=${serverId.stableDiagnosticId()} channel=${channelId.stableDiagnosticId()}"

    private fun DiagnosticFeatureFlagScope.toStableRedactedLabel(): String =
        buildString {
            append("env=${environmentId.stableDiagnosticId()} account=${accountId.stableDiagnosticId()} ")
            append("server=${serverId.stableDiagnosticId()}")
            channelId?.let { append(" channel=${it.stableDiagnosticId()}") }
        }

    private fun String.stableDiagnosticId(): String {
        var hash = 0xcbf29ce484222325UL
        encodeToByteArray().forEach { byte ->
            hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3UL
        }
        return "#${hash.toString(16).takeLast(12).padStart(12, '0')}"
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
