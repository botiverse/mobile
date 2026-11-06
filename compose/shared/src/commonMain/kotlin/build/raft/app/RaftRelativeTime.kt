package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.RaftRelativeTimeUnit
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.formatRaftTimestampEpochMillis
import build.raft.app.model.formatRaftTimestampLabel
import build.raft.app.data.ProcessRaftDatetimeFoundationOwner
import build.raft.app.data.RaftDatetimeFoundationOwner
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.resolveRaftRelativeTimeLabel
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.compose.resources.StringResource
import build.raft.designsystem.components.raftClickable

internal enum class RaftTimestampDisplayMode {
    Relative,
    Absolute
}

internal enum class RaftTimestampDisplayGroup {
    Aggregate,
    Conversation
}

internal class RaftTimestampDisplayState(
    initialAggregateMode: RaftTimestampDisplayMode = RaftTimestampDisplayMode.Relative,
    initialConversationMode: RaftTimestampDisplayMode = RaftTimestampDisplayMode.Absolute
) {
    private var aggregateMode by mutableStateOf(initialAggregateMode)
    private var conversationMode by mutableStateOf(initialConversationMode)

    fun displayMode(group: RaftTimestampDisplayGroup): RaftTimestampDisplayMode =
        when (group) {
            RaftTimestampDisplayGroup.Aggregate -> aggregateMode
            RaftTimestampDisplayGroup.Conversation -> conversationMode
        }

    private fun updateDisplayMode(
        group: RaftTimestampDisplayGroup,
        mode: RaftTimestampDisplayMode
    ) {
        when (group) {
            RaftTimestampDisplayGroup.Aggregate -> aggregateMode = mode
            RaftTimestampDisplayGroup.Conversation -> conversationMode = mode
        }
    }

    fun toggle(
        group: RaftTimestampDisplayGroup,
        value: String?,
        timeContext: RaftTimeContext?
    ): RaftTimestampDisplayMode {
        val nextMode = nextRaftTimestampDisplayMode(displayMode(group), value, timeContext)
        if (raftTimestampToggleAvailable(value, timeContext)) {
            updateDisplayMode(group, nextMode)
        }
        return nextMode
    }
}

/**
 * Retains one logical window's temporary timestamp modes across host configuration recreation.
 *
 * Kuikly's ViewModel store survives Android Activity configuration changes but is released with
 * the Pager window. It deliberately has no saved-state or persistence binding, so a real new
 * window and a process restart both restore the product defaults.
 */
internal class RaftTimestampDisplayViewModel : ViewModel() {
    val state = RaftTimestampDisplayState()
}

internal fun raftTimestampDisplayViewModelKey(pagerId: String): String =
    "timestamp-display-${pagerId.ifBlank { "default" }}"

@Composable
internal fun retainedRaftTimestampDisplayState(pagerId: String): RaftTimestampDisplayState =
    viewModel(key = raftTimestampDisplayViewModelKey(pagerId)) {
        RaftTimestampDisplayViewModel()
    }.state

internal val LocalRaftTimestampDisplayState =
    compositionLocalOf<RaftTimestampDisplayState?> { null }

@Composable
internal fun RaftTimestampDisplayScope(
    state: RaftTimestampDisplayState = remember { RaftTimestampDisplayState() },
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalRaftTimestampDisplayState provides state, content = content)
}

@Composable
internal fun raftRelativeTimestampLabel(
    value: String?,
    timeContext: RaftTimeContext? = null,
    clampFuture: Boolean = false,
): String {
    val effectiveContext = currentRaftTimeContext(timeContext)
    val resolved = resolveRaftRelativeTimeLabel(value, effectiveContext)
    return when (val label = if (clampFuture) resolved.clampedFutureToNow() else resolved) {
        RaftRelativeTimeLabel.Empty -> ""
        RaftRelativeTimeLabel.Unknown ->
            raftDeviceZoneAbsoluteLabel(value) ?: stringResource(Res.string.relative_time_unknown)
        is RaftRelativeTimeLabel.Literal -> label.value
        is RaftRelativeTimeLabel.Relative -> label.localized()
    }
}

/**
 * A readable time with no time context: the absolute local time in the device zone ("14:05",
 * "11-06 12:10"), never "unknown time" for a timestamp that parses (artin 11-05). Every page has
 * a base context now (BasePager), so this is only the last fallback; null when [value] is not a
 * timestamp.
 */
internal fun raftDeviceZoneAbsoluteLabel(
    value: String?,
    nowEpochMillis: Long = defaultNowMillis(),
    foundationOwner: RaftDatetimeFoundationOwner = ProcessRaftDatetimeFoundationOwner,
): String? {
    val offset = foundationOwner.localOffsetMinutesAt(nowEpochMillis, 0)
    val epochMillis = parseRaftTimestampEpochMillisOrNull(value, offset) ?: return null
    val todayEpochDay = (nowEpochMillis + offset * 60_000L).floorDiv(86_400_000L)
    return formatRaftTimestampEpochMillis(epochMillis, offset, todayEpochDay, RaftTimeFormat24Hour)
}

/**
 * Shared interactive timestamp used by product surfaces that lead with relative time.
 *
 * A parseable timestamp with a live local time context can toggle its window-level display group.
 * Aggregate surfaces default to relative time; conversation surfaces default to compact local
 * absolute time. Every timestamp in the same group and active activity/window observes the change.
 * A newly created window restores both defaults, and the modes are not persisted across restarts.
 */
@Composable
internal fun RaftRelativeTimestampText(
    value: String?,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext? = null,
    displayGroup: RaftTimestampDisplayGroup = RaftTimestampDisplayGroup.Aggregate,
    style: TextStyle,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
    toggleEnabled: Boolean = true,
    clampFuture: Boolean = false,
    onToggle: (RaftTimestampDisplayMode) -> Unit = {}
) {
    val effectiveContext = currentRaftTimeContext(timeContext)
    val toggleAvailable = raftTimestampToggleAvailable(value, effectiveContext, toggleEnabled)
    val displayState = LocalRaftTimestampDisplayState.current ?: remember { RaftTimestampDisplayState() }
    val resolvedMode =
        resolvedRaftTimestampDisplayMode(displayState.displayMode(displayGroup), value, effectiveContext)
    val relativeLabel = raftRelativeTimestampLabel(value, effectiveContext, clampFuture)
    val absoluteLabel = raftAbsoluteTimestampLabel(value, effectiveContext)
    val displayLabel =
        if (resolvedMode == RaftTimestampDisplayMode.Absolute) {
            absoluteLabel ?: relativeLabel
        } else {
            relativeLabel
        }
    val onClickLabel =
        if (resolvedMode == RaftTimestampDisplayMode.Relative) {
            stringResource(Res.string.relative_time_toggle_show_absolute)
        } else {
            stringResource(Res.string.relative_time_toggle_show_relative)
        }
    val modeDescription =
        if (resolvedMode == RaftTimestampDisplayMode.Relative) {
            stringResource(Res.string.relative_time_toggle_state_relative)
        } else {
            stringResource(Res.string.relative_time_toggle_state_absolute)
        }
    val interactiveModifier =
        if (toggleAvailable) {
            modifier
                .raftClickable(
                    onClick = {
                        val nextMode = displayState.toggle(displayGroup, value, effectiveContext)
                        onToggle(nextMode)
                    },
                    onClickLabel = onClickLabel,
                )
                .semantics { stateDescription = modeDescription }
        } else {
            modifier
        }
    Text(
        text = displayLabel,
        modifier = interactiveModifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        softWrap = softWrap
    )
}

internal fun raftTimestampToggleAvailable(
    value: String?,
    timeContext: RaftTimeContext?,
    toggleEnabled: Boolean = true,
): Boolean =
    toggleEnabled &&
        timeContext?.nowEpochMillis != null &&
        parseRaftTimestampEpochMillisOrNull(value, timeContext.localOffsetMinutes) != null

internal fun raftAbsoluteTimestampLabel(
    value: String?,
    timeContext: RaftTimeContext?
): String? =
    if (raftTimestampToggleAvailable(value, timeContext)) {
        formatRaftTimestampLabel(value, timeContext).takeIf { it.isNotBlank() }
    } else {
        null
    }

internal fun resolvedRaftTimestampDisplayMode(
    preferred: RaftTimestampDisplayMode,
    value: String?,
    timeContext: RaftTimeContext?
): RaftTimestampDisplayMode =
    if (raftTimestampToggleAvailable(value, timeContext)) {
        preferred
    } else {
        RaftTimestampDisplayMode.Relative
    }

internal fun nextRaftTimestampDisplayMode(
    current: RaftTimestampDisplayMode,
    value: String?,
    timeContext: RaftTimeContext?
): RaftTimestampDisplayMode {
    if (!raftTimestampToggleAvailable(value, timeContext)) {
        return RaftTimestampDisplayMode.Relative
    }
    return when (current) {
        RaftTimestampDisplayMode.Relative -> RaftTimestampDisplayMode.Absolute
        RaftTimestampDisplayMode.Absolute -> RaftTimestampDisplayMode.Relative
    }
}

@Composable
private fun currentRaftTimeContext(timeContext: RaftTimeContext?): RaftTimeContext? =
    timeContext?.takeIf { it.nowEpochMillis != null }
        ?: currentRaftTimeContextProvider?.current

/**
 * For timestamps of things that already happened (an Activity row's last message): a client
 * clock running behind the server makes them look a little in the future ("in 1 minute").
 * Any future delta reads as "now" instead, in every locale.
 */
internal fun RaftRelativeTimeLabel.clampedFutureToNow(): RaftRelativeTimeLabel =
    if (this is RaftRelativeTimeLabel.Relative && value > 0) {
        RaftRelativeTimeLabel.Relative(0, RaftRelativeTimeUnit.Minute)
    } else {
        this
    }

internal data class RaftRelativeTimeStringSelection(
    val resource: StringResource,
    val formatArg: Int? = null
)

/**
 * Pure resource-selection seam for the relative-time label. [localized] renders
 * exactly this selection; the consumer test drives it directly to lock the
 * singular/many and past/future wiring, so a deleted or mis-wired key turns RED.
 */
internal fun RaftRelativeTimeLabel.Relative.selectLocalizedStringResource(): RaftRelativeTimeStringSelection =
    when (unit) {
        RaftRelativeTimeUnit.Minute ->
            when {
                value == 0 -> RaftRelativeTimeStringSelection(Res.string.thread_timestamp_now)
                value == -1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_minute_past_one)
                value < 0 -> RaftRelativeTimeStringSelection(Res.string.relative_time_minute_past_many, absoluteValue())
                value == 1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_minute_future_one)
                else -> RaftRelativeTimeStringSelection(Res.string.relative_time_minute_future_many, value)
            }
        RaftRelativeTimeUnit.Hour ->
            when {
                value == 0 -> RaftRelativeTimeStringSelection(Res.string.relative_time_hour_now)
                value == -1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_hour_past_one)
                value < 0 -> RaftRelativeTimeStringSelection(Res.string.relative_time_hour_past_many, absoluteValue())
                value == 1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_hour_future_one)
                else -> RaftRelativeTimeStringSelection(Res.string.relative_time_hour_future_many, value)
            }
        RaftRelativeTimeUnit.Day ->
            when (value) {
                -2 -> RaftRelativeTimeStringSelection(Res.string.relative_time_day_before_yesterday)
                -1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_day_yesterday)
                0 -> RaftRelativeTimeStringSelection(Res.string.relative_time_day_today)
                1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_day_tomorrow)
                2 -> RaftRelativeTimeStringSelection(Res.string.relative_time_day_after_tomorrow)
                else ->
                    if (value < 0) {
                        RaftRelativeTimeStringSelection(Res.string.relative_time_day_past_many, absoluteValue())
                    } else {
                        RaftRelativeTimeStringSelection(Res.string.relative_time_day_future_many, value)
                    }
            }
    }

@Composable
private fun RaftRelativeTimeLabel.Relative.localized(): String {
    val selection = selectLocalizedStringResource()
    val formatArg = selection.formatArg
    return if (formatArg != null) {
        stringResource(selection.resource, formatArg)
    } else {
        stringResource(selection.resource)
    }
}

private fun RaftRelativeTimeLabel.Relative.absoluteValue(): Int =
    if (value == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(value)
