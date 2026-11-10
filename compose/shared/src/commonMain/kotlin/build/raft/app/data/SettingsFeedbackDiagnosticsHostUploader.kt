package build.raft.app.data

import build.raft.app.FeedbackDiagnosticsBinding
import build.raft.app.FeedbackDiagnosticsBridge
import build.raft.app.FeedbackDiagnosticsReference
import build.raft.app.host.HostSettingsCapability
import build.raft.app.optBoolean
import build.raft.app.optJsonObject
import build.raft.app.optLong
import build.raft.app.optString
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Web standalone diagnostics `timeoutMs`: no word from the upload for this long skips the archive. */
internal const val SETTINGS_FEEDBACK_DIAGNOSTICS_STALL_MILLIS: Long = 120_000L

/**
 * The diagnostics archive for the native Settings form, through the host actions
 * `feedbackDiagnosticsStart` / `feedbackDiagnosticsCancel` (Android RaftCmpActivity, iOS
 * HRBridgeModule; the Compose desktop app has none and answers "not implemented"). The host writes the ZIP, presigns it with Hands and PUTs
 * it, streaming `progress` events and one terminal `done` (with the reference) or `failed`.
 *
 * The binding's account is the signed-in person (the host cancels the upload when the account
 * changes); the generation pair names this native form, a new (random) one per upload, so a late
 * event of an earlier request never matches.
 */
internal class HostSettingsFeedbackDiagnosticsUploader(
    private val host: HostSettingsCapability,
    private val logger: (String) -> Unit = {},
    private val stallMillis: Long = SETTINGS_FEEDBACK_DIAGNOSTICS_STALL_MILLIS,
    private val newRequestId: () -> String = ::newDiagnosticsRequestId,
) : SettingsFeedbackDiagnosticsUploader {
    override suspend fun upload(accountId: String): FeedbackDiagnosticsReference? {
        val binding =
            FeedbackDiagnosticsBinding(
                accountId = accountId.trim(),
                sessionGeneration = NATIVE_FORM_SESSION_GENERATION,
                // Below 2^53 so it survives any bridge that carries numbers as doubles.
                webViewGeneration = Random.nextLong(1L, 1L shl 52),
            )
        val requestId = newRequestId()
        if (!binding.isValid() || !FeedbackDiagnosticsBridge.validRequestId(requestId)) return null
        val events = Channel<JsonObject>(Channel.UNLIMITED)
        val dispose =
            host.settingsCapabilityStream(command("feedbackDiagnosticsStart", requestId, binding)) { event ->
                if (event != null) events.trySend(event)
            }
        var finished = false
        try {
            while (true) {
                val event =
                    withTimeoutOrNull(stallMillis) { events.receive() }
                        ?: run {
                            logger("settings_feedback_diagnostics_skipped reason=stalled")
                            return null
                        }
                if (event.isHostUnsupportedAnswer()) {
                    // A host without the diagnostics action (the Compose desktop app answers "not
                    // implemented" at once): file the feedback without an archive, without waiting.
                    finished = true
                    logger("settings_feedback_diagnostics_skipped reason=unsupported")
                    return null
                }
                if (!event.matches(requestId, binding)) continue
                when (event.optString(FeedbackDiagnosticsBridge.TYPE).trim()) {
                    "progress" -> continue
                    "done" -> {
                        finished = true
                        return event.optJsonObject("ref")?.toDiagnosticsReference()
                            .also { ref -> logger("settings_feedback_diagnostics_done valid=${ref != null}") }
                    }
                    "failed" -> {
                        finished = true
                        logger("settings_feedback_diagnostics_skipped reason=${event.optString("reason").ifBlank { "failed" }}")
                        return null
                    }
                    else ->
                        if (event.optBoolean("terminal", false)) {
                            finished = true
                            return null
                        }
                }
            }
        } finally {
            dispose()
            events.close()
            if (!finished) {
                // Stalled or the form went away: stop the host's upload (best effort).
                val removeCancelCallback =
                    host.settingsCapabilityStream(command("feedbackDiagnosticsCancel", requestId, binding)) { }
                removeCancelCallback()
            }
        }
    }

    private fun command(action: String, requestId: String, binding: FeedbackDiagnosticsBinding): JsonObject =
        buildJsonObject {
            put("action", action)
            put(FeedbackDiagnosticsBridge.REQUEST_ID, requestId)
            put("accountId", binding.accountId)
            put("sessionGeneration", binding.sessionGeneration)
            put("webViewGeneration", binding.webViewGeneration)
        }

    /** The host's own error answer (`{code, message}`, no request id): it has no diagnostics action. */
    private fun JsonObject.isHostUnsupportedAnswer(): Boolean =
        optString(FeedbackDiagnosticsBridge.REQUEST_ID).isBlank() && containsKey("code") && optLong("code", 0L) !in 200L..299L

    private fun JsonObject.matches(requestId: String, binding: FeedbackDiagnosticsBinding): Boolean {
        if (optString(FeedbackDiagnosticsBridge.REQUEST_ID).trim() != requestId) return false
        val eventBinding = optJsonObject(FeedbackDiagnosticsBridge.BINDING) ?: return false
        return eventBinding.optString("accountId").trim() == binding.accountId &&
            eventBinding.optLong("sessionGeneration", -1L) == binding.sessionGeneration &&
            eventBinding.optLong("webViewGeneration", -1L) == binding.webViewGeneration
    }

    private fun JsonObject.toDiagnosticsReference(): FeedbackDiagnosticsReference? =
        FeedbackDiagnosticsReference(
            r2Key = optString("r2_key"),
            filename = optString("filename"),
            contentType = optString("content_type"),
            size = optLong("size", -1L),
        ).takeIf { it.isValid() }

    private companion object {
        /** The WebView's generations count up from 1 per session; the native form uses session 0. */
        const val NATIVE_FORM_SESSION_GENERATION = 0L
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newDiagnosticsRequestId(): String = "settings-feedback-" + Uuid.random().toString()
