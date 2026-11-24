package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.data.ComputerRuntimeRescanResult
import build.raft.app.data.ServerRuntimeCatalog
import build.raft.app.data.SettingsComputerRuntimeDetection
import build.raft.app.data.SettingsComputerRuntimeDetectionReason
import build.raft.app.data.SettingsComputerRuntimeDetectionStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Web `RESCAN_NO_RESULT_MS` (#587 site 4, slock #16477): if `POST …/runtimes/rescan` succeeds
 * (200) but the Computer reports no new detection state within 10 s, end as `NoResult` with Retry.
 */
internal const val COMPUTER_RUNTIME_RESCAN_NO_RESULT_MILLIS: Long = 10_000L

/** Web `RuntimeRescanError` (`useRuntimeRescan.ts`, slock #16477). */
internal enum class ComputerRuntimeRescanError {
    Forbidden,
    Offline,
    NotSent,
    Failed,
    NoResult,
}

/** Web `RuntimeRescanState` (`useRuntimeRescan.ts`, slock #16477). */
internal sealed interface ComputerRuntimeRescanState {
    data object Idle : ComputerRuntimeRescanState
    data object Running : ComputerRuntimeRescanState
    data class Failed(val error: ComputerRuntimeRescanError) : ComputerRuntimeRescanState
}

/**
 * Web `runtimeDetectionAnswerKey`: what the Computer last reported about detection (status + server
 * report timestamp), never object identity, so a machine-list refetch with unchanged data is not
 * mistaken for a new answer.
 */
internal fun computerRuntimeDetectionAnswerKey(detection: SettingsComputerRuntimeDetection?): String =
    detection?.let { "${it.status.wire}@${it.updatedAtMs ?: ""}" } ?: "none"

/**
 * Web `useRuntimeRescan` (#587 site 4, slock #16477): one `Running` state from pressing Retry until
 * the Computer reports a different [computerRuntimeDetectionAnswerKey], or early failure (403
 * Forbidden, 409 Offline, network NotSent, HTTP Failed, or 10 s NoResult). A newer answer key
 * clears both `Running` and any prior `Failed` state.
 */
internal class ComputerRuntimeRescanController(
    private val scope: CoroutineScope,
    private val request: suspend () -> ComputerRuntimeRescanResult,
    private val noResultTimeoutMillis: Long = COMPUTER_RUNTIME_RESCAN_NO_RESULT_MILLIS,
) {
    private data class Pressed(val state: ComputerRuntimeRescanState, val answerKey: String)

    private var currentAnswerKey by mutableStateOf("none")
    private var pressed by mutableStateOf<Pressed?>(null)
    private var attempt: Int = 0
    private var activeJob: Job? = null

    val state: ComputerRuntimeRescanState
        get() {
            val current = pressed ?: return ComputerRuntimeRescanState.Idle
            return if (current.answerKey == currentAnswerKey) current.state else ComputerRuntimeRescanState.Idle
        }

    fun updateAnswerKey(answerKey: String) {
        if (currentAnswerKey != answerKey) {
            currentAnswerKey = answerKey
            if (pressed != null && pressed?.answerKey != answerKey) {
                activeJob?.cancel()
                activeJob = null
                pressed = null
            }
        }
    }

    fun rescan() {
        val keyAtPress = currentAnswerKey
        val mine = ++attempt
        activeJob?.cancel()
        pressed = Pressed(ComputerRuntimeRescanState.Running, keyAtPress)
        activeJob =
            scope.launch {
                val result = request()
                if (attempt != mine || currentAnswerKey != keyAtPress) return@launch
                when (result) {
                    ComputerRuntimeRescanResult.Accepted -> {
                        delay(noResultTimeoutMillis)
                        if (attempt == mine && currentAnswerKey == keyAtPress) {
                            pressed = Pressed(ComputerRuntimeRescanState.Failed(ComputerRuntimeRescanError.NoResult), keyAtPress)
                        }
                    }
                    ComputerRuntimeRescanResult.Forbidden ->
                        pressed = Pressed(ComputerRuntimeRescanState.Failed(ComputerRuntimeRescanError.Forbidden), keyAtPress)
                    ComputerRuntimeRescanResult.Offline ->
                        pressed = Pressed(ComputerRuntimeRescanState.Failed(ComputerRuntimeRescanError.Offline), keyAtPress)
                    ComputerRuntimeRescanResult.NotSent ->
                        pressed = Pressed(ComputerRuntimeRescanState.Failed(ComputerRuntimeRescanError.NotSent), keyAtPress)
                    ComputerRuntimeRescanResult.Failed ->
                        pressed = Pressed(ComputerRuntimeRescanState.Failed(ComputerRuntimeRescanError.Failed), keyAtPress)
                }
            }
    }

    fun dispose() {
        attempt += 1
        activeJob?.cancel()
        activeJob = null
    }
}

internal const val COMPUTER_RUNTIME_DETECTION_TAG = "machine-runtime-detection"
internal const val COMPUTER_RUNTIME_DETECTION_RETRY_TAG = "machine-runtime-detection-rescan"
internal const val COMPUTER_RUNTIME_DETECTION_ERROR_TAG = "machine-runtime-detection-rescan-error"
internal const val COMPUTER_RUNTIME_DETECTION_HINT_TAG = "machine-runtime-detection-rescan-hint"

/**
 * Web `MachineInfoSection` runtime detection line (#587 site 4, slock #16477): shown while the
 * Computer is online and its last runtime detection is `pending` or `incomplete`. Pressing Retry
 * enters one "Detecting runtimes…" state until the Computer pushes a new detection report or the
 * request/timeout fails.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComputerRuntimeDetectionLine(
    machineId: String,
    online: Boolean,
    detection: SettingsComputerRuntimeDetection?,
    canRescan: Boolean,
    runtimeCatalog: ServerRuntimeCatalog,
    onRescan: (suspend () -> ComputerRuntimeRescanResult)?,
) {
    if (!online || detection == null || detection.status == SettingsComputerRuntimeDetectionStatus.Complete) return
    val scope = rememberCoroutineScope()
    val answerKey = computerRuntimeDetectionAnswerKey(detection)
    val controller =
        remember(machineId, onRescan) {
            if (onRescan == null) null else ComputerRuntimeRescanController(scope = scope, request = onRescan)
        }
    SideEffect { controller?.updateAnswerKey(answerKey) }
    DisposableEffect(controller) { onDispose { controller?.dispose() } }
    val rescanState = controller?.state ?: ComputerRuntimeRescanState.Idle
    val detecting =
        detection.status == SettingsComputerRuntimeDetectionStatus.Pending ||
            rescanState == ComputerRuntimeRescanState.Running
    val failure = if (!detecting) (rescanState as? ComputerRuntimeRescanState.Failed)?.error else null
    val canRetry =
        canRescan &&
            controller != null &&
            failure != ComputerRuntimeRescanError.Forbidden &&
            failure != ComputerRuntimeRescanError.Offline
    val reasonText =
        stringResource(
            when (detection.reason) {
                SettingsComputerRuntimeDetectionReason.Deadline -> Res.string.settings_computers_runtime_detection_reason_deadline
                SettingsComputerRuntimeDetectionReason.Cancelled -> Res.string.settings_computers_runtime_detection_reason_cancelled
                SettingsComputerRuntimeDetectionReason.ChildFailed, null -> Res.string.settings_computers_runtime_detection_reason_child_failed
            }
        )
    val bodyText =
        when {
            detecting -> stringResource(Res.string.settings_computers_runtime_detection_pending)
            detection.unfinishedRuntimes.isNotEmpty() -> {
                val names =
                    detection.unfinishedRuntimes.joinToString(", ") { id ->
                        runtimeCatalog.entry(id)?.displayName?.takeIf { it.isNotBlank() } ?: id
                    }
                stringResource(Res.string.settings_computers_runtime_detection_incomplete, reasonText, names)
            }
            else -> stringResource(Res.string.settings_computers_runtime_detection_incomplete_none, reasonText)
        }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(COMPUTER_RUNTIME_DETECTION_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (detecting) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftSpinner(size = RaftSpinnerSize.Sm)
                RaftText(bodyText, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
            }
        } else {
            RaftText(bodyText, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
        }
        if (failure != null && failure != ComputerRuntimeRescanError.Forbidden) {
            val errorRes =
                when (failure) {
                    ComputerRuntimeRescanError.Offline -> Res.string.settings_computers_runtime_detection_rescan_failed_offline
                    ComputerRuntimeRescanError.NoResult -> Res.string.settings_computers_runtime_detection_rescan_failed_no_result
                    ComputerRuntimeRescanError.NotSent -> Res.string.settings_computers_runtime_detection_rescan_failed_request_failed
                    ComputerRuntimeRescanError.Failed,
                    ComputerRuntimeRescanError.Forbidden -> Res.string.settings_computers_runtime_detection_rescan_failed_generic
                }
            RaftText(
                stringResource(errorRes),
                modifier = Modifier.testTag(COMPUTER_RUNTIME_DETECTION_ERROR_TAG),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.warningStrong,
            )
        }
        if (!detecting && (!canRescan || failure == ComputerRuntimeRescanError.Forbidden)) {
            RaftText(
                stringResource(Res.string.settings_computers_runtime_detection_rescan_not_allowed),
                modifier =
                    Modifier.testTag(
                        if (failure == ComputerRuntimeRescanError.Forbidden) COMPUTER_RUNTIME_DETECTION_ERROR_TAG
                        else COMPUTER_RUNTIME_DETECTION_HINT_TAG
                    ),
                style = RaftTheme.typography.footnote,
                color = if (failure == ComputerRuntimeRescanError.Forbidden) RaftTheme.colors.warningStrong else RaftTheme.colors.foregroundMuted,
            )
        }
        if (!detecting && canRetry) {
            RaftButton(
                text = stringResource(Res.string.status_line_retry),
                onClick = { controller?.rescan() },
                modifier = Modifier.testTag(COMPUTER_RUNTIME_DETECTION_RETRY_TAG),
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Md,
            )
        }
    }
}
