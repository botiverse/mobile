package build.raft.app

import androidx.compose.foundation.background
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.data.ChannelConversionAction
import build.raft.app.data.ChannelConversionEffect
import build.raft.app.data.ChannelConversionIntent
import build.raft.app.data.ChannelConversionKey
import build.raft.app.data.ChannelConversionSlice
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.RaftChannelConversionJob
import build.raft.app.data.RaftChannelConversionJobStatus
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftPanelNotice
import build.raft.designsystem.components.RaftPopoverDefaults
import build.raft.designsystem.components.RaftPopoverSurface
import build.raft.designsystem.components.RaftProgressBar
import build.raft.designsystem.components.RaftProgressBarSize
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * A channel being made an external channel, at the top of its conversation (web botiverse/slock
 * 922c8de664 #15525 `components/channel/ChannelConversionStrip.tsx`, its online-move phases from
 * 6ebc85285 #15918; Kirby, design DRI, 2026-11-19). Conversion starts on its own when someone
 * connects outside people or another server, so it is a status of the channel beside its messages:
 *  - running: a strip like the announcement strip (spinner, title, a short bar under a 560dp wide
 *    conversation, the percentage, View); View shows the five steps and Cancel, which asks first.
 *    On a phone the steps are a bottom sheet; on a wide window (a fine pointer, or the dual-pane
 *    width, as the image lightbox decides) a popover hanging from the strip's end, as the web's;
 *  - failed, or its status can't be read: a PanelNotice with Retry or View latest status;
 *  - done: it goes away by itself.
 * The state is SharedChannelConversionStore's.
 */

/** Test tags: the web's `data-testid`s. */
internal object ChannelConversionTestTags {
    const val Strip = "channel-conversion-strip"
    const val StripTitle = "channel-conversion-strip-title"
    const val StripProgress = "channel-conversion-strip-progress"
    const val StripPercent = "channel-conversion-strip-percent"
    const val StripView = "channel-conversion-strip-view"
    const val Steps = "channel-conversion-popover"
    const val Stages = "channel-conversion-stages"
    const val StagePrefix = "channel-conversion-stage-"
    const val Cancel = "channel-conversion-cancel"
    const val CancelConfirm = "channel-conversion-cancel-confirm"
    const val Error = "channel-conversion-error"
    const val Retry = "channel-conversion-retry"
    const val ConnectionWarning = "channel-conversion-connection-warning"
    const val CheckStatus = "channel-conversion-check-status"
}

/**
 * Web `ChannelConversionStrip` `enabled`: who may federate channels (`getServerCapabilities(role)`
 * grants `federateChannels` to a server owner and admin), on any channel but #all. They alone see
 * the strip, and so Retry and Cancel (the server checks `federateChannels` again).
 */
internal fun channelConversionStripEnabled(serverRole: String?, channelName: String): Boolean =
    (serverRole == "owner" || serverRole == "admin") && channelName.trim().removePrefix("#") != "all"

/** Web `CONVERSION_STAGES`: each step as it is named, and as it reads while under way. */
internal enum class ChannelConversionStep(val key: String) {
    Prepare("prepare"),
    Preserve("preserve"),
    Verify("verify"),
    Access("access"),
    Done("done"),
}

/** Web `conversionStageIndex`: which of the five steps [phase] belongs to. */
internal fun channelConversionStepIndex(phase: String): Int =
    when (phase) {
        "done" -> ChannelConversionStep.Done.ordinal
        "verify", "online_moved" -> ChannelConversionStep.Verify.ordinal
        "audience_cutover", "residual_cleanup", "online_switched", "finalize" -> ChannelConversionStep.Access.ordinal
        "prepare_tasks", "move_parent_messages", "prepare_threads", "move_thread_messages", "online_prepare_threads", "online_move",
        "online_cancel" ->
            ChannelConversionStep.Preserve.ordinal
        else -> ChannelConversionStep.Prepare.ordinal
    }

/** Web `conversionProgress`: the percentage shown for [phase]. */
internal fun channelConversionPercent(phase: String): Int =
    when (phase) {
        "prepare" -> 15
        "prepare_tasks" -> 25
        "move_parent_messages", "online_prepare_threads", "online_move", "online_cancel" -> 45
        "prepare_threads" -> 60
        "move_thread_messages" -> 72
        "verify", "online_moved" -> 82
        "audience_cutover" -> 90
        "residual_cleanup", "online_switched" -> 95
        "finalize" -> 98
        "done" -> 100
        else -> 10
    }

/** What the error notice says (web `displayError`). */
internal sealed interface ChannelConversionErrorText {
    /** The server's sentence: the job's error or a refusal. */
    data class Server(val text: String) : ChannelConversionErrorText

    /** Web `channel.edit.convertUploadsInFlight`. */
    data class UploadsInFlight(val count: Int) : ChannelConversionErrorText

    /** A Retry or Cancel that failed without the server's words. */
    data class Local(val action: ChannelConversionAction, val noLongerCancelable: Boolean) : ChannelConversionErrorText
}

/** Web `channel.edit.conversionStatusUnavailable` / `conversionOutcomeUnknown` (once observation settled). */
internal enum class ChannelConversionStatusNotice { Unavailable, OutcomeUnknown }

/** The strip and its notices for one conversion under way (web `ChannelConversionStatus` props). */
internal data class ChannelConversionStripModel(
    /** The one-line strip; false once the job failed (the notice takes its place). */
    val showsStrip: Boolean,
    val stepIndex: Int,
    val percent: Int,
    /** The status can't be read: no step reads as under way (web `paused`). */
    val paused: Boolean,
    val statusNotice: ChannelConversionStatusNotice?,
    /** The failed read's trace id, under the status notice (AGENTS.md "Errors carry the trace ID"). */
    val statusTraceId: String? = null,
    val failed: Boolean,
    val error: ChannelConversionErrorText?,
    val errorTraceId: String?,
    val offersCancel: Boolean,
    val offersRetry: Boolean,
    val busy: ChannelConversionAction?,
)

private const val UploadsInFlightErrorCode = "channel_conversion_uploads_in_flight"
private val UploadsInFlightPatterns =
    listOf(
        Regex("attachment upload(?: records)? .*in flight", RegexOption.IGNORE_CASE),
        Regex("attachment upload(?:s)? .*in progress", RegexOption.IGNORE_CASE),
        Regex("uploads? (?:are|is) still in flight", RegexOption.IGNORE_CASE),
    )
private val UploadCountInError = Regex("because\\s+(\\d+)\\s+attachment uploads?", RegexOption.IGNORE_CASE)

/** Web `conversionErrorMessage`: uploads still under way get their own sentence, with how many. */
private fun channelConversionErrorText(text: String?, job: RaftChannelConversionJob?): ChannelConversionErrorText? {
    val uploads = job?.errorCode == UploadsInFlightErrorCode || (text != null && UploadsInFlightPatterns.any { it.containsMatchIn(text) })
    if (!uploads) return text?.let(ChannelConversionErrorText::Server)
    val fromProgress = job?.uploadCount?.takeIf { it > 0 }
    val fromError = text?.let { UploadCountInError.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it > 0 }
    return ChannelConversionErrorText.UploadsInFlight(if (job?.uploadCount != null && job.uploadCount != 0) fromProgress ?: 1 else fromError ?: 1)
}

/**
 * What shows for [slice] (web `useChannelConversionController` → `ChannelConversionStatus`), or null:
 * nothing is under way (done, canceled, never started, not read yet).
 */
internal fun channelConversionStripModel(slice: ChannelConversionSlice): ChannelConversionStripModel? {
    val conversion = slice.conversion
    val job = slice.shownJob
    if (slice.busy == null && !conversion.commandPending && job == null) return null
    val failed = job?.status == RaftChannelConversionJobStatus.Failed
    // A command the server admitted that waits for the channel: the strip at 0% on its first step.
    val phase = job?.phase ?: if (conversion.commandPending) "prepare" else ""
    val hideError = slice.statusUnavailable || conversion.commandPending
    val actionError = slice.actionError
    val error =
        when {
            hideError -> null
            actionError != null ->
                actionError.serverMessage?.let { channelConversionErrorText(it, job) }
                    ?: channelConversionErrorText(null, job)
                    ?: ChannelConversionErrorText.Local(actionError.action, actionError.noLongerCancelable)
            else -> channelConversionErrorText(job?.error, job)
        }
    return ChannelConversionStripModel(
        showsStrip = !failed,
        stepIndex = channelConversionStepIndex(phase),
        percent = if (job == null && conversion.commandPending) 0 else channelConversionPercent(phase),
        paused = slice.statusUnavailable,
        statusNotice =
            when {
                !slice.statusUnavailable -> null
                slice.outcomeUnknown -> ChannelConversionStatusNotice.OutcomeUnknown
                else -> ChannelConversionStatusNotice.Unavailable
            },
        statusTraceId = slice.statusTraceId.takeIf { slice.statusUnavailable },
        failed = failed,
        error = error,
        errorTraceId = actionError?.traceId.takeIf { error != null },
        offersCancel = slice.offersCancel,
        offersRetry = slice.offersRetry,
        busy = slice.busy,
    )
}

/** Steps in a popover where the app opens popovers (a fine pointer or a dual-pane wide window); a sheet on a phone. */
internal fun channelConversionStepsInPopover(finePointer: Boolean, windowWidthDp: Float): Boolean =
    finePointer || windowWidthDp >= DualPaneMinimumWidthDp

/** Web `@max-[560px]:hidden`: the bar goes under a 560dp wide conversation. */
private val ChannelConversionBarFrom = 560.dp

/** Web `md:` (768px): the strip's wider padding, as the announcement strip reads it from its own width. */
private val ChannelConversionStripWideFrom = 768.dp

@Composable
private fun channelConversionErrorString(error: ChannelConversionErrorText): String =
    when (error) {
        is ChannelConversionErrorText.Server -> error.text
        is ChannelConversionErrorText.UploadsInFlight ->
            if (error.count == 1) {
                stringResource(Res.string.channel_conversion_uploads_in_flight_one)
            } else {
                stringResource(Res.string.channel_conversion_uploads_in_flight_other, error.count)
            }
        is ChannelConversionErrorText.Local ->
            stringResource(
                when {
                    error.noLongerCancelable -> Res.string.channel_conversion_cancel_unavailable
                    error.action == ChannelConversionAction.Retry -> Res.string.channel_conversion_retry_failed
                    else -> Res.string.channel_conversion_cancel_failed
                },
            )
    }

/**
 * The notices and the strip (web `ChannelConversionStatus`): the state in, nothing read here.
 * Cancel asks first; the question stays, its button busy, until the cancel answered.
 */
@Composable
internal fun ChannelConversionStatus(
    model: ChannelConversionStripModel,
    channelName: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onCheckStatus: () -> Unit,
    modifier: Modifier = Modifier,
    stepsInPopover: Boolean =
        channelConversionStepsInPopover(
            raftPlatformHasFinePointer(),
            with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value },
        ),
) {
    var stepsOpen by remember { mutableStateOf(false) }
    var popoverAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var confirmingCancel by remember { mutableStateOf(false) }
    // 0: not sent; 1: confirmed, not seen running yet; 2: running.
    var cancelProgress by remember { mutableStateOf(0) }
    val anchorRef = remember { RaftLiveRootAnchorRef("channel-conversion-strip") }
    val cancelRunning = model.busy == ChannelConversionAction.Cancel
    LaunchedEffect(cancelRunning) {
        if (cancelRunning && cancelProgress == 1) {
            cancelProgress = 2
        } else if (!cancelRunning && cancelProgress == 2) {
            confirmingCancel = false
            cancelProgress = 0
        }
    }
    val openCancelConfirm = {
        stepsOpen = false
        popoverAnchor = null
        confirmingCancel = true
    }
    val title = stringResource(Res.string.channel_conversion_strip_title)
    Column(modifier.fillMaxWidth()) {
        model.statusNotice?.let { notice ->
            RaftPanelNotice(
                message =
                    stringResource(
                        if (notice == ChannelConversionStatusNotice.OutcomeUnknown) Res.string.channel_conversion_outcome_unknown
                        else Res.string.channel_conversion_status_unavailable,
                    ),
                modifier = Modifier.testTag(ChannelConversionTestTags.ConnectionWarning),
                status = RaftBannerStatus.Warning,
                maxLines = 4,
                stackActions = !stepsInPopover,
                actions = {
                    RaftButton(
                        text = stringResource(Res.string.channel_conversion_check_status),
                        onClick = onCheckStatus,
                        modifier = Modifier.testTag(ChannelConversionTestTags.CheckStatus),
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Md,
                    )
                },
            )
            model.statusTraceId?.let { RaftTraceIdNote(it, Modifier.raftSafeAreaHorizontalPadding().padding(horizontal = 16.dp, vertical = 4.dp)) }
        }
        model.error?.let { error ->
            val text = channelConversionErrorString(error)
            RaftPanelNotice(
                message = if (model.failed) stringResource(Res.string.channel_conversion_failed, text) else text,
                modifier = Modifier.testTag(ChannelConversionTestTags.Error),
                status = RaftBannerStatus.Warning,
                maxLines = 6,
                stackActions = !stepsInPopover,
                actions =
                    if (model.failed && (model.offersRetry || model.offersCancel)) {
                        {
                            if (model.offersCancel) {
                                RaftButton(
                                    text = stringResource(Res.string.channel_conversion_cancel),
                                    onClick = openCancelConfirm,
                                    modifier = Modifier.testTag(ChannelConversionTestTags.Cancel),
                                    variant = RaftButtonVariant.Ghost,
                                    size = RaftButtonSize.Md,
                                    enabled = model.busy == null,
                                )
                            }
                            if (model.offersRetry) {
                                RaftButton(
                                    text = stringResource(Res.string.common_retry),
                                    onClick = onRetry,
                                    modifier = Modifier.testTag(ChannelConversionTestTags.Retry),
                                    variant = RaftButtonVariant.Ghost,
                                    size = RaftButtonSize.Md,
                                    enabled = model.busy == null,
                                    loading = model.busy == ChannelConversionAction.Retry,
                                    loadingLabel = stringResource(Res.string.channel_conversion_retrying),
                                )
                            }
                        }
                    } else {
                        null
                    },
            )
            model.errorTraceId?.let { RaftTraceIdNote(it, Modifier.raftSafeAreaHorizontalPadding().padding(horizontal = 16.dp, vertical = 4.dp)) }
        }
        if (model.showsStrip) {
            ChannelConversionStripRow(
                title = title,
                percent = model.percent,
                onView = {
                    if (stepsInPopover) {
                        popoverAnchor = anchorRef.resolver(RaftPopoverLocalPoint.BottomEnd).resolve()
                    } else {
                        stepsOpen = true
                    }
                },
                modifier = Modifier.raftCaptureLiveRootAnchor(anchorRef),
            )
        }
    }
    if (stepsOpen) {
        RaftSheet(
            onDismissRequest = { stepsOpen = false },
            title = title,
            modifier = Modifier.testTag(ChannelConversionTestTags.Steps),
            description = stringResource(Res.string.channel_conversion_strip_description),
            actions =
                if (model.offersCancel) {
                    { ChannelConversionCancelButton(model, openCancelConfirm, Modifier.fillMaxWidth(), RaftButtonSize.Lg) }
                } else {
                    null
                },
        ) {
            ChannelConversionSteps(model.stepIndex, model.paused)
        }
    }
    popoverAnchor?.let { anchor ->
        ChannelConversionStepsPopover(anchor, title, model, onDismiss = { popoverAnchor = null }, onCancel = openCancelConfirm)
    }
    if (confirmingCancel) {
        ChannelConversionCancelDialog(
            channelName = channelName,
            running = cancelRunning,
            onConfirm = {
                cancelProgress = 1
                onCancel()
            },
            onDismiss = {
                confirmingCancel = false
                cancelProgress = 0
            },
        )
    }
}

/**
 * The strip (raft-design channel.md "The announcement"): on paper with one seam under it, one line
 * and 40dp at every width: a spinner, the title (it truncates first), the bar (from 560dp wide),
 * the percentage in mono figures, and View.
 */
@Composable
private fun ChannelConversionStripRow(title: String, percent: Int, onView: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val seam = colors.lineSeam
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(colors.layerCanvas)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(seam, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .testTag(ChannelConversionTestTags.Strip),
    ) {
        val wide = maxWidth >= ChannelConversionStripWideFrom
        val showsBar = maxWidth >= ChannelConversionBarFrom
        Row(
            Modifier.fillMaxSize().raftSafeAreaHorizontalPadding().padding(start = if (wide) 20.dp else 16.dp, end = if (wide) 12.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative (web `aria-hidden`): the title says what is under way.
            Box(Modifier.size(16.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                RaftSpinner(size = RaftSpinnerSize.Sm, color = colors.foregroundMuted)
            }
            RaftText(
                title,
                Modifier.weight(1f).testTag(ChannelConversionTestTags.StripTitle),
                style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showsBar) {
                RaftProgressBar(
                    value = percent / 100f,
                    modifier = Modifier.width(80.dp).testTag(ChannelConversionTestTags.StripProgress),
                    size = RaftProgressBarSize.Sm,
                    contentDescription = title,
                )
            }
            RaftText(
                "$percent%",
                // Web `min-w-[4ch]`: 100% fits without moving View.
                Modifier.widthIn(min = 29.dp).testTag(ChannelConversionTestTags.StripPercent),
                style = typography.monoMeta,
                color = colors.foregroundMuted,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            RaftButton(
                text = stringResource(Res.string.channel_conversion_strip_view),
                onClick = onView,
                modifier = Modifier.testTag(ChannelConversionTestTags.StripView),
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Md,
            )
        }
    }
}

@Composable
private fun ChannelConversionCancelButton(model: ChannelConversionStripModel, onClick: () -> Unit, modifier: Modifier, size: RaftButtonSize) {
    RaftButton(
        text = stringResource(Res.string.channel_conversion_cancel),
        onClick = onClick,
        modifier = modifier.testTag(ChannelConversionTestTags.Cancel),
        variant = RaftButtonVariant.Default,
        size = size,
        enabled = model.busy == null,
        loading = model.busy == ChannelConversionAction.Cancel,
        loadingLabel = stringResource(Res.string.channel_conversion_canceling),
    )
}

/**
 * The five steps, one icon family at 16dp (Kirby): done a check, the current one the spinner, the
 * ones ahead an empty circle. Only the current step reads as under way ("Verifying history…");
 * the others are what the step is ("Verify history").
 */
@Composable
internal fun ChannelConversionSteps(stepIndex: Int, paused: Boolean, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    Column(modifier.fillMaxWidth().testTag(ChannelConversionTestTags.Stages), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChannelConversionStep.entries.forEach { step ->
            val completed = step.ordinal < stepIndex
            val current = !paused && step.ordinal == stepIndex
            val label = stringResource(if (current) step.currentLabel() else step.label())
            val state =
                stringResource(
                    when {
                        completed -> Res.string.channel_conversion_step_complete
                        current -> Res.string.channel_conversion_step_in_progress
                        else -> Res.string.channel_conversion_step_pending
                    },
                )
            val description = stringResource(Res.string.channel_conversion_step_label, label, state)
            val color = if (completed) colors.foregroundMuted else if (current) colors.foreground else colors.foregroundHint
            val tag = ChannelConversionTestTags.StagePrefix + step.key
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = description
                    testTag = tag
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.width(16.dp).height(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        completed -> RaftLucideIcon(RaftLucideIconName.Check, Modifier.size(16.dp), color = raftArgbHex(color))
                        current -> RaftSpinner(size = RaftSpinnerSize.Sm, color = color)
                        else -> RaftLucideIcon(RaftLucideIconName.Circle, Modifier.size(16.dp), color = raftArgbHex(color))
                    }
                }
                RaftText(label, Modifier.weight(1f), style = RaftTheme.typography.callout, color = color)
            }
        }
    }
}

private fun ChannelConversionStep.label() =
    when (this) {
        ChannelConversionStep.Prepare -> Res.string.channel_conversion_step_prepare
        ChannelConversionStep.Preserve -> Res.string.channel_conversion_step_preserve
        ChannelConversionStep.Verify -> Res.string.channel_conversion_step_verify
        ChannelConversionStep.Access -> Res.string.channel_conversion_step_access
        ChannelConversionStep.Done -> Res.string.channel_conversion_step_done
    }

private fun ChannelConversionStep.currentLabel() =
    when (this) {
        ChannelConversionStep.Prepare -> Res.string.channel_conversion_step_prepare_current
        ChannelConversionStep.Preserve -> Res.string.channel_conversion_step_preserve_current
        ChannelConversionStep.Verify -> Res.string.channel_conversion_step_verify_current
        ChannelConversionStep.Access -> Res.string.channel_conversion_step_access_current
        ChannelConversionStep.Done -> Res.string.channel_conversion_step_done_current
    }

/** Web `PopoverContent anchor={strip} side="bottom" align="end" sideOffset={4}`: 300dp, its end under the strip's. */
@Composable
private fun ChannelConversionStepsPopover(
    anchor: RaftPopoverAnchor,
    title: String,
    model: ChannelConversionStripModel,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val density = LocalDensity.current
    var measured by remember { mutableStateOf(Size.Zero) }
    val width = RaftPopoverDefaults.Width
    val size = if (measured != Size.Zero) measured else with(density) { Size(width.toPx(), 300.dp.toPx()) }
    RaftModalAnchoredPopover(
        anchor = anchor,
        contentSize = size,
        onDismiss = onDismiss,
        horizontalOffset = -size.width,
        verticalOffset = with(density) { 4.dp.toPx() },
        viewportGap = with(density) { 8.dp.toPx() },
        verticalPlacement = RaftFloatingVerticalPlacement.Auto,
        dismissActivationDelayMillis = 0L,
    ) {
        Box(Modifier.onSizeChanged { measured = Size(it.width.toFloat(), it.height.toFloat()) }) {
            RaftPopoverSurface(
                // A tap on the popover is not a tap outside it.
                modifier = Modifier.testTag(ChannelConversionTestTags.Steps).pointerInput(Unit) { detectTapGestures {} },
                width = width,
            ) {
                RaftText(title, style = RaftTheme.typography.headline, color = RaftTheme.colors.foreground)
                RaftText(
                    stringResource(Res.string.channel_conversion_strip_description),
                    Modifier.padding(top = 4.dp),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted,
                )
                ChannelConversionSteps(model.stepIndex, model.paused, Modifier.padding(top = 12.dp))
                if (model.offersCancel) {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                        ChannelConversionCancelButton(model, onCancel, Modifier, RaftButtonSize.Md)
                    }
                }
            }
        }
    }
}

/** Web `ConfirmDialog` for Cancel: Keep connecting or Cancel connecting (warning), busy until it answered. */
@Composable
private fun ChannelConversionCancelDialog(channelName: String, running: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    RaftDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = stringResource(Res.string.channel_conversion_cancel_title),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = !running,
        footer = {
            RaftButton(
                text = stringResource(Res.string.channel_conversion_keep_connecting),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                enabled = !running,
            )
            RaftButton(
                text = stringResource(Res.string.channel_conversion_cancel),
                onClick = onConfirm,
                modifier = Modifier.testTag(ChannelConversionTestTags.CancelConfirm),
                variant = RaftButtonVariant.Warning,
                loading = running,
                loadingLabel = stringResource(Res.string.channel_conversion_canceling),
            )
        },
    ) {
        RaftText(
            stringResource(Res.string.channel_conversion_cancel_body, channelName.trim().removePrefix("#")),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foreground,
        )
    }
}

/**
 * [key]'s strip wired to the shared store: read when it shows and whenever it comes back
 * ([active]), the fallback poll held while it shows, the realtime frames, Retry and Cancel, and the
 * toast once a cancel went through.
 */
@Composable
internal fun ThreadChannelConversionStrip(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    bridge: BridgeModule,
    key: ChannelConversionKey,
    channelName: String,
    active: Boolean,
) {
    val store = remember(sharedStores, storeScope) { sharedStores.channelConversionStore(storeScope) }
    DisposableEffect(store, key, active) {
        if (!active) return@DisposableEffect onDispose {}
        store.dispatch(ChannelConversionIntent.Load(key, "open", DataRequestPriority.Visible))
        store.dispatch(ChannelConversionIntent.Watch(key))
        onDispose { store.dispatch(ChannelConversionIntent.Unwatch(key)) }
    }
    val owner = remember(sharedStores, storeScope) { sharedStores.realtimeDomainEventOwner(storeScope) }
    DisposableEffect(owner, store, key) {
        val sink = RealtimeGuardedActiveThreadSink { eventServerId, event -> store.dispatch(ChannelConversionIntent.Realtime(key, eventServerId, event)) }
        owner.registerGuardedActiveThreadSink(sink)
        onDispose { owner.unregisterGuardedActiveThreadSink(sink) }
    }
    val canceledMessage by rememberUpdatedState(stringResource(Res.string.channel_conversion_canceled))
    LaunchedEffect(store, key) {
        store.effects.collect { effect ->
            if (effect is ChannelConversionEffect.Canceled && effect.key == key) bridge.toast(canceledMessage)
        }
    }
    val slice by remember(store, key) { store.slice(key) }.collectAsState(store.current(key))
    val model = channelConversionStripModel(slice) ?: return
    ChannelConversionStatus(
        model = model,
        channelName = channelName,
        onRetry = { store.dispatch(ChannelConversionIntent.Retry(key)) },
        onCancel = { store.dispatch(ChannelConversionIntent.Cancel(key)) },
        onCheckStatus = { store.dispatch(ChannelConversionIntent.CheckStatus(key)) },
    )
}

/**
 * The conversation page's conversion strip as a slot, or null where none can show (web ChatPanel:
 * not a DM, thread or Lounge; ThreadPageContent leaves out the Lounge): not a channel's own page
 * ([payload] a DM, thread or task conversation), or [channelConversionStripEnabled] says no for the
 * viewer's role in the channel's server ([session]). Everything is worked out here, not in the route
 * composable, which is at its JVM size and capture limits: it passes what it already holds.
 */
@Composable
internal fun rememberThreadChannelConversionStripSlot(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    bridge: BridgeModule,
    serverId: ServerId?,
    channelId: ChannelId?,
    payload: RaftThreadScreenPayload,
    session: StoredAuthSession?,
    active: Boolean,
): (@Composable () -> Unit)? {
    val cleanServerId = serverId?.value?.takeIf { it.isNotBlank() }
    val cleanChannelId = channelId?.value?.takeIf { it.isNotBlank() }
    val channelPage = payload.surfaceKind == RaftThreadSurfaceKind.Channel && !payload.isTaskConversationSurface()
    val serverRole = session?.servers?.firstOrNull { it.id == cleanServerId }?.role
    val channelName = payload.title
    if (cleanServerId == null || cleanChannelId == null || !channelPage || !channelConversionStripEnabled(serverRole, channelName)) return null
    val key = remember(cleanServerId, cleanChannelId) { ChannelConversionKey(cleanServerId, cleanChannelId) }
    val latestActive by rememberUpdatedState(active)
    val latestName by rememberUpdatedState(channelName)
    return remember(sharedStores, storeScope, bridge, key) {
        { ThreadChannelConversionStrip(sharedStores, storeScope, bridge, key, latestName, latestActive) }
    }
}
