package build.raft.app

import build.raft.app.resources.*

/**
 * Errors carry the trace ID (AGENTS.md): an error the app shows for a failed server request
 * shows that request's `X-Trace-Id` with a way to copy it, as the web's `TraceIdNote` does
 * (botiverse/slock `components/errors/TraceIdNote.tsx`, #proj-o11y:#18). The note shows the
 * first 8 hex digits; the copy takes all 32.
 */
@RaftCoreInternalApi const val RaftTraceIdShortLength: Int = 8

/** The shown form of a trace id: its first [RaftTraceIdShortLength] digits and an ellipsis (web `traceId.slice(0, 8)…`). */
@RaftCoreInternalApi fun raftShortTraceId(traceId: String): String = traceId.take(RaftTraceIdShortLength) + "…"

/** The error toast's trace id copy: [line] ("Trace ID %s"), the [copyAction] label and the [copied] confirmation. */
@RaftCoreInternalApi class RaftTraceIdToastStrings(
    val line: (shortId: String) -> String,
    val copyAction: String,
    val copied: String,
)

/** [RaftTraceIdToastStrings] in the app language, for code outside composition (`BridgeModule.errorToast`). */
@RaftCoreInternalApi fun localizedRaftTraceIdToastStrings(): RaftTraceIdToastStrings =
    RaftTraceIdToastStrings(
        line = { shortId -> Res.string.error_trace_id_value.format(shortId).resolve() },
        copyAction = Res.string.common_copy.desc().resolve(),
        copied = Res.string.error_trace_id_copied.desc().resolve(),
    )
