package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.data.ChannelSlackBridgeOption
import build.raft.app.data.ChannelSlackBridgeState
import build.raft.app.data.RaftProcessForegroundGate
import build.raft.app.data.SLACK_BRIDGE_CHECK_TIMEOUT_MILLIS
import build.raft.app.data.SlackBridgeReadFloor
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftSelectTrigger
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val CHANNEL_SLACK_TRIGGER_TAG = "channel-slack-bridge-trigger"
internal const val CHANNEL_SLACK_READING_TAG = "channel-slack-bridge-reading"
internal const val CHANNEL_SLACK_READ_FAILED_TAG = "channel-slack-bridge-read-failed"
internal const val CHANNEL_SLACK_READ_RETRY_TAG = "channel-slack-bridge-read-retry"

/** Outcome of one Slack channel list read (`#587 site 11`, slock `#16506`). */
internal enum class ChannelSlackBridgeReadOutcome {
    Success,
    /** Skipped because the page is busy saving; leaves any prior failure unchanged. */
    Skipped,
    Failed,
}

/**
 * Web `useSlackChannelListRead` (`#587 site 11`, slock `#16506`): reads the Slack channel list
 * when the menu opens and when the person returns to the app, subject to the shared 1-minute floor
 * ([SlackBridgeReadFloor]). Inside the minute, one read is deferred until the floor expires if the
 * menu is still open or a foreground return is owed. A failed open/Retry read sets [failed]; a
 * failed foreground return stays quiet. Disabling clears [failed].
 */
internal class ChannelSlackBridgeReadController(
    private val scope: CoroutineScope,
    private val read: suspend () -> ChannelSlackBridgeReadOutcome,
    private val waitRemainingMillis: () -> Long = { SlackBridgeReadFloor.waitRemainingMillis() },
    private val isForeground: () -> Boolean = { RaftProcessForegroundGate.foreground.value },
    private val checkTimeoutMillis: Long = SLACK_BRIDGE_CHECK_TIMEOUT_MILLIS,
) {
    var reading by mutableStateOf(false)
        private set
    var menuOpen by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var retrying by mutableStateOf(false)
        private set

    private var enabled: Boolean = true
    private var disposed: Boolean = false
    private var inFlight: CompletableDeferred<Unit>? = null
    private var reportFailure: Boolean = false
    private var returnOwed: Boolean = false
    private var deferredJob: Job? = null
    private var retryJob: Job? = null
    private var activeReadJob: Job? = null

    fun setEnabled(next: Boolean) {
        if (enabled == next) return
        enabled = next
        if (!next) {
            failed = false
            returnOwed = false
            deferredJob?.cancel()
            deferredJob = null
        }
    }

    fun onMenuOpenChange(open: Boolean) {
        menuOpen = open
        if (!open || !enabled || disposed) return
        if (inFlight != null || waitRemainingMillis() <= 0L) {
            scope.launch { readOnce(report = true) }
        } else {
            deferRead(forReturn = false)
        }
    }

    fun onForegroundReturn() {
        if (!enabled || disposed) return
        if (waitRemainingMillis() > 0L) {
            deferRead(forReturn = true)
            return
        }
        scope.launch { readOnce(report = false) }
    }

    fun retry() {
        if (!enabled || disposed || retrying) return
        retrying = true
        retryJob?.cancel()
        retryJob =
            scope.launch {
                try {
                    while (!disposed && enabled) {
                        val wait = waitRemainingMillis()
                        if (wait <= 0L) break
                        delay(wait)
                    }
                    if (!disposed && enabled) {
                        readOnce(report = true)
                    }
                } finally {
                    retrying = false
                }
            }
    }

    private suspend fun readOnce(report: Boolean) {
        if (disposed) return
        reportFailure = reportFailure || report
        inFlight?.let { existing ->
            existing.await()
            return
        }
        deferredJob?.cancel()
        deferredJob = null
        returnOwed = false
        val gate = CompletableDeferred<Unit>()
        inFlight = gate
        reading = true
        val job =
            scope.launch {
                try {
                    val outcome =
                        try {
                            withTimeoutOrNull(checkTimeoutMillis) { read() } ?: ChannelSlackBridgeReadOutcome.Failed
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            ChannelSlackBridgeReadOutcome.Failed
                        }
                    if (!disposed) {
                        when (outcome) {
                            ChannelSlackBridgeReadOutcome.Success -> failed = false
                            ChannelSlackBridgeReadOutcome.Skipped -> Unit
                            ChannelSlackBridgeReadOutcome.Failed -> if (reportFailure) failed = true
                        }
                    }
                } finally {
                    inFlight = null
                    reportFailure = false
                    if (!disposed) reading = false
                    gate.complete(Unit)
                }
            }
        activeReadJob = job
        gate.await()
    }

    private fun deferRead(forReturn: Boolean) {
        if (forReturn) returnOwed = true
        if (deferredJob?.isActive == true) return
        deferredJob =
            scope.launch {
                try {
                    while (!disposed && enabled) {
                        val wait = waitRemainingMillis()
                        if (wait > 0L) {
                            delay(wait)
                            continue
                        }
                        break
                    }
                } finally {
                    deferredJob = null
                }
                if (disposed || !enabled) return@launch
                val owed = returnOwed
                returnOwed = false
                when {
                    menuOpen -> readOnce(report = true)
                    owed && isForeground() -> readOnce(report = false)
                }
            }
    }

    fun dispose() {
        disposed = true
        deferredJob?.cancel()
        deferredJob = null
        retryJob?.cancel()
        retryJob = null
        activeReadJob?.cancel()
        activeReadJob = null
        inFlight?.complete(Unit)
        inFlight = null
    }
}

/**
 * The Slack channel picker (`#587 site 11`, slock `#16506`): reads on open and on return to the
 * app (when [onRefresh] is provided), shows `"Reading Slack channels…"` at the top of the open
 * menu while reading, and shows a warning banner with Retry under the field if reading fails.
 * Built on public [RaftSelectTrigger] + [RaftDropdownMenu] so `compose/designsystem/` stays untouched.
 */
@Composable
internal fun ChannelSlackBridgePicker(
    state: ChannelSlackBridgeState,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    label: String? = null,
    onRefresh: (suspend () -> ChannelSlackBridgeReadOutcome)? = null,
) {
    val scope = rememberCoroutineScope()
    val currentRefresh by rememberUpdatedState(onRefresh)
    val controller =
        remember(onRefresh != null) {
            if (onRefresh == null) {
                null
            } else {
                ChannelSlackBridgeReadController(
                    scope = scope,
                    read = { currentRefresh?.invoke() ?: ChannelSlackBridgeReadOutcome.Skipped },
                )
            }
        }
    SideEffect { controller?.setEnabled(enabled) }
    DisposableEffect(controller) { onDispose { controller?.dispose() } }
    if (controller != null) {
        LaunchedEffect(controller, enabled) {
            if (!enabled) return@LaunchedEffect
            var wasForeground = RaftProcessForegroundGate.foreground.value
            RaftProcessForegroundGate.foreground.collect { foreground ->
                val returned = !wasForeground && foreground
                wasForeground = foreground
                if (returned) controller.onForegroundReturn()
            }
        }
    }

    val notConnected = stringResource(Res.string.channel_settings_slack_not_connected)
    val options: List<ChannelSlackBridgeOption?> = listOf(null) + state.options
    val labels =
        options.associateWith { option ->
            when {
                option == null -> notConnected
                option.membershipRequired -> stringResource(Res.string.channel_settings_slack_membership_required, option.name)
                option.pairedElsewhere && option.slackChannelId != selected ->
                    stringResource(Res.string.channel_settings_slack_already_paired, option.name)
                else -> stringResource(Res.string.channel_settings_slack_channel, option.name)
            }
        }
    val selectedOption = options.firstOrNull { it?.slackChannelId == selected }
    val selectedLabel = selectedOption?.let { labels[it] } ?: notConnected
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val failed = controller?.failed == true
    val readingOpen = controller?.let { it.reading && it.menuOpen } == true

    fun setExpanded(next: Boolean) {
        if (expanded == next) return
        expanded = next
        controller?.onMenuOpenChange(next)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth()) {
            RaftSelectTrigger(
                value = selectedLabel,
                onClick = { setExpanded(!expanded) },
                modifier =
                    Modifier
                        .testTag(CHANNEL_SLACK_TRIGGER_TAG)
                        .onSizeChanged { triggerWidthPx = it.width },
                enabled = enabled,
                invalid = failed,
                label = label,
                expanded = expanded,
                onCollapse = { setExpanded(false) },
            )
            RaftDropdownMenu(
                expanded = expanded,
                onDismissRequest = { setExpanded(false) },
                width = if (triggerWidthPx > 0) with(density) { triggerWidthPx.toDp() } else null,
            ) {
                if (readingOpen) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 36.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag(CHANNEL_SLACK_READING_TAG),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftSpinner(size = RaftSpinnerSize.Sm)
                        RaftText(
                            stringResource(Res.string.channel_settings_slack_reading_channels),
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foregroundMuted,
                        )
                    }
                    RaftMenuSeparator()
                }
                options.forEach { option ->
                    val optionEnabled =
                        option == null ||
                            (!option.membershipRequired && (!option.pairedElsewhere || option.slackChannelId == selected))
                    RaftMenuItem(
                        text = labels.getValue(option),
                        onClick = {
                            onSelect(option?.slackChannelId)
                            setExpanded(false)
                        },
                        enabled = optionEnabled,
                        checked = option?.slackChannelId == selected,
                    )
                }
            }
        }
        if (failed) {
            RaftBanner(
                description = stringResource(Res.string.channel_settings_slack_channels_read_failed),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                modifier = Modifier.testTag(CHANNEL_SLACK_READ_FAILED_TAG),
                action = {
                    RaftButton(
                        text = stringResource(Res.string.status_line_retry),
                        onClick = { controller?.retry() },
                        modifier = Modifier.testTag(CHANNEL_SLACK_READ_RETRY_TAG),
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Md,
                        enabled = enabled || controller?.retrying == true,
                        loading = controller?.retrying == true,
                        loadingLabel = stringResource(Res.string.status_line_checking),
                    )
                },
            )
        }
    }
}
