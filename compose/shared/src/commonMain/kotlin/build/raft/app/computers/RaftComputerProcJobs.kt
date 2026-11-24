package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.ComputerProcJobSnapshot
import build.raft.app.data.ComputerProcJobState
import build.raft.app.data.ComputerProcJobStopOutcome
import build.raft.app.data.ComputerProcJobStopResult
import build.raft.app.data.ComputerProcJobsRead
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.defaultNowMillis
import build.raft.app.format.RaftFormat
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.network.raftValidTraceIdOrNull
import build.raft.app.resources.*
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.components.rememberRaftCopyAction
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the Computer page re-reads `raft proc` jobs while online and visible (web `MACHINE_PROC_JOBS_POLL_MS`). */
internal const val COMPUTER_PROC_JOBS_POLL_MS: Long = 30_000L

/** How long after a confirmed Stop the page re-reads `raft proc` jobs (web `MACHINE_PROC_JOBS_AFTER_STOP_MS`). */
internal const val COMPUTER_PROC_JOBS_AFTER_STOP_MS: Long = 3_000L

internal const val COMPUTER_PROC_JOBS_SECTION_TEST_TAG = "computer_agent_proc_jobs"
internal const val COMPUTER_PROC_JOBS_STOP_DIALOG_TEST_TAG = "computer_proc_job_stop_dialog"
internal const val COMPUTER_PROC_JOBS_STOP_CONFIRM_TEST_TAG = "computer_proc_job_stop_confirm"

internal fun computerProcJobRowTestTag(name: String): String = "computer_proc_job_row_$name"
internal fun computerProcJobStopButtonTestTag(name: String): String = "computer_proc_job_stop_$name"

/**
 * Web `isValidProcStoppedBy` (`packages/shared/src/procJobs.ts` `PROC_STOPPED_BY_PATTERN`):
 * 1..64 characters of Unicode letters, digits, `_`, `-`, or `.`. Anything else falls back to
 * "Stopped by a person".
 */
internal fun isValidComputerProcStoppedBy(value: String?): Boolean {
    if (value.isNullOrEmpty() || value.length > 64) return false
    return value.all { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' }
}

internal fun computerProcJobKey(job: ComputerProcJobSnapshot): String =
    "${job.agentId}\u0000${job.name}\u0000${job.startedAt}"

/**
 * Groups jobs by `agentId`, sorted running first, then newest `startedAt` first, then by `name`
 * (web `useMachineProcJobs.ts` `byAgent`).
 */
internal fun groupComputerProcJobsByAgent(
    jobs: List<ComputerProcJobSnapshot>
): Map<String, List<ComputerProcJobSnapshot>> {
    if (jobs.isEmpty()) return emptyMap()
    val grouped = LinkedHashMap<String, MutableList<ComputerProcJobSnapshot>>()
    for (job in jobs) {
        grouped.getOrPut(job.agentId) { mutableListOf() }.add(job)
    }
    val sorted = LinkedHashMap<String, List<ComputerProcJobSnapshot>>(grouped.size)
    for ((agentId, list) in grouped) {
        sorted[agentId] =
            list.sortedWith(
                compareBy<ComputerProcJobSnapshot> { if (it.state == ComputerProcJobState.Running) 0 else 1 }
                    .thenByDescending { parseRaftTimestampEpochMillisOrNull(it.startedAt, 0) ?: Long.MIN_VALUE }
                    .thenBy { it.name }
            )
    }
    return sorted
}

/**
 * Overlays confirmed local stops onto still-running entries from a snapshot that predated the
 * Computer's post-stop report (web `useMachineProcJobs.ts`).
 */
internal fun applyConfirmedComputerProcJobStops(
    jobs: List<ComputerProcJobSnapshot>,
    stoppedAtByKey: Map<String, String>
): List<ComputerProcJobSnapshot> {
    if (jobs.isEmpty() || stoppedAtByKey.isEmpty()) return jobs
    return jobs.map { job ->
        val at = if (job.state == ComputerProcJobState.Running) stoppedAtByKey[computerProcJobKey(job)] else null
        if (at != null) {
            job.copy(
                state = ComputerProcJobState.Exited,
                exitedAt = at,
                exitReason = "owner_stop",
                stoppedBy = null,
                exitCode = null,
                exitSignal = null,
                rssBytes = null
            )
        } else {
            job
        }
    }
}

/** Formats UTC epoch millis as ISO-8601 `YYYY-MM-DDTHH:MM:SSZ` without JVM-only APIs. */
internal fun computerProcEpochMillisToIsoUtc(epochMillis: Long): String {
    val totalSeconds = floorDivLong(epochMillis, 1_000L)
    val days = floorDivLong(totalSeconds, 86_400L)
    val secsOfDay = (totalSeconds - days * 86_400L).toInt()
    val hour = secsOfDay / 3_600
    val minute = (secsOfDay % 3_600) / 60
    val second = secsOfDay % 60
    // Howard Hinnant civil-from-days algorithm (UTC proleptic Gregorian calendar).
    val z = days + 719_468L
    val era = (if (z >= 0L) z else z - 146_096L) / 146_097L
    val doe = (z - era * 146_097L).toInt()
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400L
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = mp + (if (mp < 10) 3 else -9)
    val year = y + (if (month <= 2) 1L else 0L)
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}" +
        "T${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:${second.toString().padStart(2, '0')}Z"
}

private fun floorDivLong(a: Long, b: Long): Long {
    var q = a / b
    if ((a xor b) < 0L && q * b != a) q -= 1L
    return q
}

/**
 * Pure state controller for a Computer page's `raft proc` jobs (web `useMachineProcJobs.ts`).
 *
 * - Reads `GET …/proc-jobs` only while the Computer is online.
 * - Drops stale answers (`readSeq`) so a slow earlier read never overwrites a newer one.
 * - Unknown snapshots or read errors yield empty [jobsByAgent] (never "no jobs").
 * - Confirmed stops update the job immediately locally and schedule a follow-up read 3 s later.
 */
@Stable
internal class RaftComputerProcJobsController(
    private val loadProcJobs: suspend (ServerId, String, DataRequestPriority) -> DataResult<ComputerProcJobsRead>,
    private val stopProcJobApi: suspend (ServerId, String, String, String) -> ComputerProcJobStopResult,
    private val nowMillisProvider: () -> Long = { defaultNowMillis() },
    private val afterStopDelayMs: Long = COMPUTER_PROC_JOBS_AFTER_STOP_MS
) {
    var answer by mutableStateOf<ComputerProcJobsRead?>(null)
        private set

    private var stoppedAtByKey by mutableStateOf<Map<String, String>>(emptyMap())
    private var readSeq: Long = 0L
    private var afterStopJob: Job? = null
    private var currentOnline: Boolean = false

    val jobsByAgent: Map<String, List<ComputerProcJobSnapshot>>
        get() {
            val known = answer as? ComputerProcJobsRead.Known ?: return emptyMap()
            return groupComputerProcJobsByAgent(applyConfirmedComputerProcJobStops(known.jobs, stoppedAtByKey))
        }

    suspend fun refresh(
        serverId: ServerId?,
        machineId: String,
        online: Boolean,
        priority: DataRequestPriority = DataRequestPriority.Visible
    ) {
        currentOnline = online
        val seq = ++readSeq
        if (serverId == null || !online || machineId.isBlank()) {
            cancelPendingRefresh()
            answer = null
            return
        }
        val result =
            try {
                loadProcJobs(serverId, machineId, priority)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
        if (seq != readSeq || !currentOnline) return
        answer = (result as? DataResult.Content)?.data
    }

    fun markStopped(
        job: ComputerProcJobSnapshot,
        scope: CoroutineScope,
        serverId: ServerId?,
        machineId: String
    ) {
        stoppedAtByKey = stoppedAtByKey + (computerProcJobKey(job) to computerProcEpochMillisToIsoUtc(nowMillisProvider()))
        afterStopJob?.cancel()
        afterStopJob =
            scope.launch {
                delay(afterStopDelayMs)
                if (currentOnline) {
                    refresh(serverId, machineId, online = true, priority = DataRequestPriority.Visible)
                }
            }
    }

    /**
     * Runs `POST …/proc-jobs/stop` inside [scope] (the screen scope, so recomposition or clearing
     * the agent's job list never cancels the request or drops the toast), delivers the outcome via
     * [onOutcome] *before* re-reading jobs (matching web `MachineAgentList.tsx`), and then refreshes
     * using live [currentOnline].
     */
    suspend fun stopJob(
        job: ComputerProcJobSnapshot,
        scope: CoroutineScope,
        serverId: ServerId?,
        machineId: String,
        onOutcome: (ComputerProcJobStopResult) -> Unit = {}
    ): ComputerProcJobStopResult {
        val deferred =
            scope.async {
                if (serverId == null || machineId.isBlank()) {
                    val invalid = ComputerProcJobStopResult.Error("proc_job_invalid")
                    onOutcome(invalid)
                    return@async invalid
                }
                val result =
                    try {
                        stopProcJobApi(serverId, machineId, job.agentId, job.name)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        ComputerProcJobStopResult.Error("request_failed")
                    }
                if (result is ComputerProcJobStopResult.Completed && result.outcome == ComputerProcJobStopOutcome.Stopped) {
                    markStopped(job, scope, serverId, machineId)
                }
                onOutcome(result)
                refresh(serverId, machineId, online = currentOnline, priority = DataRequestPriority.Visible)
                result
            }
        return deferred.await()
    }

    fun cancelPendingRefresh() {
        afterStopJob?.cancel()
        afterStopJob = null
    }
}

internal sealed interface ComputerProcJobUptimeFormat {
    data class Seconds(val seconds: Int) : ComputerProcJobUptimeFormat
    data class Minutes(val minutes: Int) : ComputerProcJobUptimeFormat
    data class Hours(val hours: Int, val minutes: Int) : ComputerProcJobUptimeFormat
    data class Days(val days: Int, val hours: Int) : ComputerProcJobUptimeFormat
}

/** Web `formatProcJobUptime`: `<60s` -> s, `<60m` -> m, `<48h` -> h m, else d h. */
internal fun computerProcJobUptimeFormat(elapsedMs: Long): ComputerProcJobUptimeFormat {
    val seconds = (elapsedMs.coerceAtLeast(0L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (seconds < 60) return ComputerProcJobUptimeFormat.Seconds(seconds)
    val minutes = seconds / 60
    if (minutes < 60) return ComputerProcJobUptimeFormat.Minutes(minutes)
    val hours = minutes / 60
    if (hours < 48) return ComputerProcJobUptimeFormat.Hours(hours, minutes % 60)
    return ComputerProcJobUptimeFormat.Days(hours / 24, hours % 24)
}

internal sealed interface ComputerProcJobExitFormat {
    data class OwnerStop(val handle: String) : ComputerProcJobExitFormat
    data object OwnerStopUnknown : ComputerProcJobExitFormat
    data object MemoryLimit : ComputerProcJobExitFormat
    data class Code(val code: Int) : ComputerProcJobExitFormat
    data class Signal(val signal: String) : ComputerProcJobExitFormat
    data object Plain : ComputerProcJobExitFormat
}

/** Web `formatProcJobExit` priority order. */
internal fun computerProcJobExitFormat(job: ComputerProcJobSnapshot): ComputerProcJobExitFormat =
    when {
        job.exitReason == "owner_stop" ->
            if (isValidComputerProcStoppedBy(job.stoppedBy)) {
                ComputerProcJobExitFormat.OwnerStop(requireNotNull(job.stoppedBy))
            } else {
                ComputerProcJobExitFormat.OwnerStopUnknown
            }
        job.exitReason == "memory_limit" -> ComputerProcJobExitFormat.MemoryLimit
        job.exitCode != null -> ComputerProcJobExitFormat.Code(requireNotNull(job.exitCode))
        !job.exitSignal.isNullOrBlank() -> ComputerProcJobExitFormat.Signal(requireNotNull(job.exitSignal))
        else -> ComputerProcJobExitFormat.Plain
    }

internal sealed interface ComputerProcJobMemoryFormat {
    data class UsedOfCap(val used: String, val cap: String) : ComputerProcJobMemoryFormat
    data class UsedOnly(val used: String) : ComputerProcJobMemoryFormat
    data class CapOnly(val cap: String) : ComputerProcJobMemoryFormat
}

/** Web `formatProcJobMemory`: running RSS + cap, running RSS only, cap only, or null. */
internal fun computerProcJobMemoryFormat(job: ComputerProcJobSnapshot): ComputerProcJobMemoryFormat? {
    val cap = job.memoryMaxBytes?.takeIf { it > 0L }?.let { RaftFormat.bytes(it) }
    val used =
        if (job.state == ComputerProcJobState.Running) {
            job.rssBytes?.takeIf { it >= 0L }?.let { RaftFormat.bytes(it) }
        } else {
            null
        }
    return when {
        used != null && cap != null -> ComputerProcJobMemoryFormat.UsedOfCap(used, cap)
        used != null -> ComputerProcJobMemoryFormat.UsedOnly(used)
        cap != null -> ComputerProcJobMemoryFormat.CapOnly(cap)
        else -> null
    }
}

@Composable
internal fun rememberComputerProcJobsEffect(
    controller: RaftComputerProcJobsController,
    serverId: ServerId?,
    machineId: String,
    online: Boolean,
    pageVisible: Boolean,
    refreshTick: Int = 0
) {
    DisposableEffect(controller) {
        onDispose { controller.cancelPendingRefresh() }
    }
    LaunchedEffect(controller, serverId, machineId, online, pageVisible, refreshTick) {
        if (serverId == null || !online || machineId.isBlank()) {
            controller.refresh(serverId, machineId, online = false)
            return@LaunchedEffect
        }
        if (!pageVisible) {
            controller.cancelPendingRefresh()
            return@LaunchedEffect
        }
        controller.refresh(serverId, machineId, online = true, priority = DataRequestPriority.Visible)
        while (true) {
            delay(COMPUTER_PROC_JOBS_POLL_MS)
            controller.refresh(serverId, machineId, online = true, priority = DataRequestPriority.Background)
        }
    }
}

@Composable
private fun computerProcJobUptimeText(elapsedMs: Long): String =
    when (val format = computerProcJobUptimeFormat(elapsedMs)) {
        is ComputerProcJobUptimeFormat.Seconds ->
            stringResource(Res.string.settings_computers_proc_jobs_duration_seconds, format.seconds)
        is ComputerProcJobUptimeFormat.Minutes ->
            stringResource(Res.string.settings_computers_proc_jobs_duration_minutes, format.minutes)
        is ComputerProcJobUptimeFormat.Hours ->
            stringResource(Res.string.settings_computers_proc_jobs_duration_hours, format.hours, format.minutes)
        is ComputerProcJobUptimeFormat.Days ->
            stringResource(Res.string.settings_computers_proc_jobs_duration_days, format.days, format.hours)
    }

@Composable
private fun computerProcJobExitText(job: ComputerProcJobSnapshot): String =
    when (val format = computerProcJobExitFormat(job)) {
        is ComputerProcJobExitFormat.OwnerStop ->
            stringResource(Res.string.settings_computers_proc_jobs_exit_owner_stop, format.handle)
        ComputerProcJobExitFormat.OwnerStopUnknown ->
            stringResource(Res.string.settings_computers_proc_jobs_exit_owner_stop_unknown)
        ComputerProcJobExitFormat.MemoryLimit ->
            stringResource(Res.string.settings_computers_proc_jobs_exit_memory_limit)
        is ComputerProcJobExitFormat.Code ->
            stringResource(Res.string.settings_computers_proc_jobs_exit_code, format.code)
        is ComputerProcJobExitFormat.Signal ->
            stringResource(Res.string.settings_computers_proc_jobs_exit_signal, format.signal)
        ComputerProcJobExitFormat.Plain ->
            stringResource(Res.string.settings_computers_proc_jobs_exit_plain)
    }

@Composable
private fun computerProcJobStateText(
    job: ComputerProcJobSnapshot,
    nowMillis: Long,
    timeContext: RaftTimeContext?
): String {
    if (job.state == ComputerProcJobState.Running) {
        val startedMs = parseRaftTimestampEpochMillisOrNull(job.startedAt, 0)
        val elapsedMs = if (startedMs != null) (nowMillis - startedMs).coerceAtLeast(0L) else 0L
        return stringResource(Res.string.settings_computers_proc_jobs_running, computerProcJobUptimeText(elapsedMs))
    }
    val reason = computerProcJobExitText(job)
    val ago =
        job.exitedAt?.takeIf { parseRaftTimestampEpochMillisOrNull(it, 0) != null }?.let { exitedAt ->
            raftShortRelativeTimestampLabel(exitedAt, timeContext).takeIf { it.isNotBlank() }
        }
    return if (ago != null) {
        stringResource(Res.string.settings_computers_proc_jobs_exited_ago, reason, ago)
    } else {
        reason
    }
}

@Composable
private fun computerProcJobMemoryText(job: ComputerProcJobSnapshot): String? =
    when (val format = computerProcJobMemoryFormat(job)) {
        is ComputerProcJobMemoryFormat.UsedOfCap ->
            stringResource(Res.string.settings_computers_proc_jobs_memory_of_cap, format.used, format.cap)
        is ComputerProcJobMemoryFormat.UsedOnly ->
            stringResource(Res.string.settings_computers_proc_jobs_memory, format.used)
        is ComputerProcJobMemoryFormat.CapOnly ->
            stringResource(Res.string.settings_computers_proc_jobs_memory_cap, format.cap)
        null -> null
    }

/**
 * Displays an agent's `raft proc` jobs directly under its row in the Computer detail Agents card
 * (web `MachineAgentProcJobs`). Display-only block (never inside the clickable agent row); Stop
 * appears only for running jobs when [canStop] is true, gated behind a warning confirmation dialog.
 */
@Composable
internal fun RaftComputerAgentProcJobs(
    agentName: String,
    jobs: List<ComputerProcJobSnapshot>,
    canStop: Boolean,
    onStop: suspend (ComputerProcJobSnapshot, onOutcome: (ComputerProcJobStopResult) -> Unit) -> Unit,
    timeContext: RaftTimeContext? = currentRaftTimeContextProvider?.current,
    nowMillis: Long = timeContext?.nowEpochMillis ?: defaultNowMillis()
) {
    if (jobs.isEmpty()) return
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val scope = rememberCoroutineScope()
    val toastHost = LocalRaftToastHostState.current
    var confirming by remember { mutableStateOf<ComputerProcJobSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(canStop) {
        if (!canStop && !busy) confirming = null
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(COMPUTER_PROC_JOBS_SECTION_TEST_TAG)
                .padding(start = 63.dp, end = 16.dp, top = 0.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RaftText(
            text = stringResource(Res.string.settings_computers_proc_jobs_label),
            style = typography.caption,
            color = colors.foregroundMuted
        )
        jobs.forEach { job ->
            RaftComputerProcJobLine(
                job = job,
                canStop = canStop,
                nowMillis = nowMillis,
                timeContext = timeContext,
                onRequestStop = { confirming = job }
            )
        }
    }

    confirming?.let { targetJob ->
        val alreadyEndedToast = stringResource(Res.string.settings_computers_proc_jobs_toast_already_ended, targetJob.name)
        val failedToast = stringResource(Res.string.settings_computers_proc_jobs_toast_failed, targetJob.name)
        val upgradeRequiredToast = stringResource(Res.string.settings_computers_proc_jobs_toast_upgrade_required)
        val traceIdFormat = stringResource(Res.string.error_trace_id_value, "%s")
        val copyLabel = stringResource(Res.string.common_copy)
        val copiedToast = stringResource(Res.string.error_trace_id_copied)
        val copy = rememberRaftCopyAction()
        val showFailedToast: (String?) -> Unit = { rawTraceId ->
            val id = raftValidTraceIdOrNull(rawTraceId)
            toastHost?.show(
                title = failedToast,
                intent = RaftToastIntent.Warning,
                actionLabel = if (id != null) copyLabel else null,
                onAction = id?.let { validId ->
                    {
                        copy(validId)
                        toastHost.show(copiedToast, durationMillis = 1_500L)
                    }
                },
                footnote = id?.let { traceIdFormat.replace("%s", raftShortTraceId(it)) },
            )
        }
        val showOutcome: (ComputerProcJobStopResult) -> Unit = { result ->
            when (result) {
                is ComputerProcJobStopResult.Completed ->
                    when (result.outcome) {
                        ComputerProcJobStopOutcome.Stopped -> Unit
                        ComputerProcJobStopOutcome.AlreadyExited,
                        ComputerProcJobStopOutcome.NotFound ->
                            toastHost?.show(alreadyEndedToast)
                        ComputerProcJobStopOutcome.Failed -> showFailedToast(result.traceId)
                    }
                ComputerProcJobStopResult.UpgradeRequired ->
                    toastHost?.show(upgradeRequiredToast, intent = RaftToastIntent.Warning)
                is ComputerProcJobStopResult.Error -> showFailedToast(result.traceId)
            }
        }
        RaftDialog(
            onDismissRequest = { if (!busy) confirming = null },
            title = stringResource(Res.string.settings_computers_proc_jobs_stop_title, targetJob.name),
            size = RaftDialogSize.Sm,
            description = stringResource(Res.string.settings_computers_proc_jobs_stop_body, agentName),
            dismissOnClickOutside = !busy,
            showClose = !busy,
            closeLabel = stringResource(Res.string.common_close),
            modifier = Modifier.testTag(COMPUTER_PROC_JOBS_STOP_DIALOG_TEST_TAG),
            footer = {
                RaftButton(
                    text = stringResource(Res.string.settings_computers_proc_jobs_cancel),
                    onClick = { if (!busy) confirming = null },
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    enabled = !busy
                )
                RaftButton(
                    text = stringResource(Res.string.settings_computers_proc_jobs_stop_confirm),
                    onClick = {
                        if (busy) return@RaftButton
                        busy = true
                        scope.launch {
                            try {
                                onStop(targetJob, showOutcome)
                            } finally {
                                busy = false
                                confirming = null
                            }
                        }
                    },
                    variant = RaftButtonVariant.Warning,
                    size = RaftButtonSize.Lg,
                    loading = busy,
                    modifier = Modifier.testTag(COMPUTER_PROC_JOBS_STOP_CONFIRM_TEST_TAG)
                )
            }
        )
    }
}

@Composable
private fun RaftComputerProcJobLine(
    job: ComputerProcJobSnapshot,
    canStop: Boolean,
    nowMillis: Long,
    timeContext: RaftTimeContext?,
    onRequestStop: () -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val stateText = computerProcJobStateText(job, nowMillis, timeContext)
    val memoryText = computerProcJobMemoryText(job)
    val stopAria = stringResource(Res.string.settings_computers_proc_jobs_stop_aria, job.name)
    Row(
        modifier = Modifier.fillMaxWidth().testTag(computerProcJobRowTestTag(job.name)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            RaftText(
                text = job.name,
                style = typography.monoMeta,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            RaftText(
                text = stateText,
                style = typography.caption,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (memoryText != null) {
                RaftText(
                    text = memoryText,
                    style = typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (canStop && job.state == ComputerProcJobState.Running) {
            RaftButton(
                text = stringResource(Res.string.settings_computers_proc_jobs_stop),
                onClick = onRequestStop,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Md,
                modifier =
                    Modifier
                        .testTag(computerProcJobStopButtonTestTag(job.name))
                        .semantics { contentDescription = stopAria }
            )
        }
    }
}
