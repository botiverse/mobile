package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.network.raftValidTraceIdOrNull
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.rememberRaftCopyAction
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * Errors carry the trace ID (AGENTS.md): an error the app shows for a failed server request
 * shows that request's `X-Trace-Id` with a way to copy it, as the web's `TraceIdNote` does
 * (botiverse/slock `components/errors/TraceIdNote.tsx`, #proj-o11y:#18). The note shows the
 * first 8 hex digits; the copy takes all 32.
 */
internal const val RaftTraceIdShortLength: Int = 8

/** The shown form of a trace id: its first [RaftTraceIdShortLength] digits and an ellipsis (web `traceId.slice(0, 8)…`). */
internal fun raftShortTraceId(traceId: String): String = traceId.take(RaftTraceIdShortLength) + "…"

/** The error toast's trace id copy: [line] ("Trace ID %s"), the [copyAction] label and the [copied] confirmation. */
internal class RaftTraceIdToastStrings(
    val line: (shortId: String) -> String,
    val copyAction: String,
    val copied: String,
)

/** [RaftTraceIdToastStrings] in the app language, for code outside composition (`BridgeModule.errorToast`). */
internal fun localizedRaftTraceIdToastStrings(): RaftTraceIdToastStrings =
    RaftTraceIdToastStrings(
        line = { shortId -> Res.string.error_trace_id_value.format(shortId).resolve() },
        copyAction = Res.string.common_copy.desc().resolve(),
        copied = Res.string.error_trace_id_copied.desc().resolve(),
    )

/**
 * Inline form of the trace id under an error on a page or card (the web `TraceIdNote` on paper):
 * "Trace ID a1b2c3d4…" in the hint footnote and a ghost copy icon button that copies the whole id
 * through the app's copy action (host pasteboard + "Copied"). Draws nothing without a [traceId].
 */
@Composable
internal fun RaftTraceIdNote(traceId: String?, modifier: Modifier = Modifier) {
    val id = raftValidTraceIdOrNull(traceId) ?: return
    val copy = rememberRaftCopyAction()
    Row(
        modifier.testTag(RaftTraceIdNoteTestTag),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(
            stringResource(Res.string.error_trace_id_label),
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundHint,
        )
        RaftText(
            raftShortTraceId(id),
            style = RaftTheme.typography.monoMeta,
            color = RaftTheme.colors.foregroundHint,
        )
        RaftIconButton(
            onClick = { copy(id) },
            tooltip = stringResource(Res.string.error_trace_id_copy),
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Md,
        ) {
            RaftSlotIcon(RaftLucideIconName.Copy)
        }
    }
}

internal const val RaftTraceIdNoteTestTag: String = "trace-id-note"
