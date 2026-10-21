package build.raft.app

import build.raft.designsystem.components.RaftTextInputTrace
import build.raft.designsystem.components.RaftTextInputTracing

/**
 * Developer setting "Text input trace" (task #69): while on, every Raft text field logs each IME
 * edit and each value sync between its editor state and its owner to the app logs, so a feedback
 * zip shows the sequence behind an input-method bug (iOS Pinyin marked text being reset). The
 * messages carry lengths, ranges and a coarse ASCII / non-ASCII shape, never the typed text.
 *
 * Off by default and process-scoped (it resets on restart). Off, a field only reads one snapshot
 * state per composition.
 */
internal object RaftTextInputDiagnostics {
    private const val Tag = "RaftTextInput"

    private val logTrace = RaftTextInputTrace { field, message -> raftLogInfo(Tag, "$field $message") }

    val enabled: Boolean
        get() = RaftTextInputTracing.trace != null

    fun setEnabled(enabled: Boolean) {
        if (enabled == this.enabled) return
        RaftTextInputTracing.trace = if (enabled) logTrace else null
        raftLogInfo(Tag, "text_input_trace ${if (enabled) "on" else "off"}")
    }
}
