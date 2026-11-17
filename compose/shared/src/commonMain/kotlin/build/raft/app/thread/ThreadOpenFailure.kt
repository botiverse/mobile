package build.raft.app

import build.raft.app.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * Why a thread could not be opened without any request behind it (artin 11-03): each local failure
 * of `openStandaloneThreadPayload` tells the person why in plain words and what to do, and logs
 * its own reason code so a feedback log says which one happened. A server failure is not one of
 * these: it shows its trace id instead (AGENTS.md "Errors carry the trace ID").
 */
internal enum class ThreadOpenFailure(val logReason: String, val message: StringResource) {
    /** The payload has no conversation id to route to (no thread, channel or parent message id). */
    MissingIdentity("missing_identity", Res.string.thread_error_open_thread_missing_identity),

    /** The page that asked to open it was replaced meanwhile (an adaptive side/main page retired). */
    StaleSource("stale_source", Res.string.thread_error_open_thread_stale_source),

    /** The shell store scope the thread page needs was gone when the page opened. */
    MissingScope("missing_scope", Res.string.thread_error_open_thread_missing_scope),
}

/**
 * The diagnostics line for [failure]: the reason code and which route ids [payload] had
 * (presence only, plus the opaque instance id), never user text.
 */
internal fun threadOpenFailureLog(failure: ThreadOpenFailure, payload: RaftThreadScreenPayload): String {
    fun has(value: String?) = if (value.isNullOrBlank()) "0" else "1"
    return "thread_open_failed reason=${failure.logReason} instanceId=${payload.instanceId} " +
        "surfaceKind=${payload.surfaceKind} sourceThreadId=${has(payload.sourceThreadId)} " +
        "streamChannelId=${has(payload.streamChannelId)} parentChannelId=${has(payload.parentChannelId)} " +
        "parentMessageId=${has(payload.parentMessageId)}"
}
