package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.model.SlockRelativeTimeLabel
import build.raft.app.model.SlockRelativeTimeUnit
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.formatSlockTimestampLabel
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.model.resolveSlockRelativeTimeLabel
import build.raft.app.resources.MR
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.StringResource

internal enum class SlockTimestampDisplayMode {
    Relative,
    Absolute
}

internal enum class SlockTimestampDisplayGroup {
    Aggregate,
    Conversation
}

internal class SlockTimestampDisplayState(
    initialAggregateMode: SlockTimestampDisplayMode = SlockTimestampDisplayMode.Relative,
    initialConversationMode: SlockTimestampDisplayMode = SlockTimestampDisplayMode.Absolute
) {
    private var aggregateMode by mutableStateOf(initialAggregateMode)
    private var conversationMode by mutableStateOf(initialConversationMode)

    fun displayMode(group: SlockTimestampDisplayGroup): SlockTimestampDisplayMode =
        when (group) {
            SlockTimestampDisplayGroup.Aggregate -> aggregateMode
            SlockTimestampDisplayGroup.Conversation -> conversationMode
        }

    private fun updateDisplayMode(
        group: SlockTimestampDisplayGroup,
        mode: SlockTimestampDisplayMode
    ) {
        when (group) {
            SlockTimestampDisplayGroup.Aggregate -> aggregateMode = mode
            SlockTimestampDisplayGroup.Conversation -> conversationMode = mode
        }
    }

    fun toggle(
        group: SlockTimestampDisplayGroup,
        value: String?,
        timeContext: SlockTimeContext?
    ): SlockTimestampDisplayMode {
        val nextMode = nextSlockTimestampDisplayMode(displayMode(group), value, timeContext)
        if (slockTimestampToggleAvailable(value, timeContext)) {
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
internal class SlockTimestampDisplayViewModel : ViewModel() {
    val state = SlockTimestampDisplayState()
}

internal fun slockTimestampDisplayViewModelKey(pagerId: String): String =
    "timestamp-display-${pagerId.ifBlank { "default" }}"

@Composable
internal fun retainedSlockTimestampDisplayState(pagerId: String): SlockTimestampDisplayState =
    viewModel(key = slockTimestampDisplayViewModelKey(pagerId)) {
        SlockTimestampDisplayViewModel()
    }.state

internal val LocalSlockTimestampDisplayState =
    compositionLocalOf<SlockTimestampDisplayState?> { null }

@Composable
internal fun SlockTimestampDisplayScope(
    state: SlockTimestampDisplayState = remember { SlockTimestampDisplayState() },
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalSlockTimestampDisplayState provides state, content = content)
}

@Composable
internal fun slockRelativeTimestampLabel(
    value: String?,
    timeContext: SlockTimeContext? = null
): String {
    val effectiveContext = currentSlockTimeContext(timeContext)
    return when (val label = resolveSlockRelativeTimeLabel(value, effectiveContext)) {
        SlockRelativeTimeLabel.Empty -> ""
        SlockRelativeTimeLabel.Unknown -> stringResource(MR.strings.relative_time_unknown)
        is SlockRelativeTimeLabel.Literal -> label.value
        is SlockRelativeTimeLabel.Relative -> label.localized()
    }
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
internal fun SlockRelativeTimestampText(
    value: String?,
    modifier: Modifier = Modifier,
    timeContext: SlockTimeContext? = null,
    displayGroup: SlockTimestampDisplayGroup = SlockTimestampDisplayGroup.Aggregate,
    style: TextStyle,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
    toggleEnabled: Boolean = true,
    onToggle: (SlockTimestampDisplayMode) -> Unit = {}
) {
    val effectiveContext = currentSlockTimeContext(timeContext)
    val toggleAvailable = slockTimestampToggleAvailable(value, effectiveContext, toggleEnabled)
    val displayState = LocalSlockTimestampDisplayState.current ?: remember { SlockTimestampDisplayState() }
    val resolvedMode =
        resolvedSlockTimestampDisplayMode(displayState.displayMode(displayGroup), value, effectiveContext)
    val relativeLabel = slockRelativeTimestampLabel(value, effectiveContext)
    val absoluteLabel = slockAbsoluteTimestampLabel(value, effectiveContext)
    val displayLabel =
        if (resolvedMode == SlockTimestampDisplayMode.Absolute) {
            absoluteLabel ?: relativeLabel
        } else {
            relativeLabel
        }
    val onClickLabel =
        if (resolvedMode == SlockTimestampDisplayMode.Relative) {
            stringResource(MR.strings.relative_time_toggle_show_absolute)
        } else {
            stringResource(MR.strings.relative_time_toggle_show_relative)
        }
    val modeDescription =
        if (resolvedMode == SlockTimestampDisplayMode.Relative) {
            stringResource(MR.strings.relative_time_toggle_state_relative)
        } else {
            stringResource(MR.strings.relative_time_toggle_state_absolute)
        }
    val interactiveModifier =
        if (toggleAvailable) {
            modifier
                .clickable(
                    onClickLabel = onClickLabel,
                    role = Role.Button
                ) {
                    val nextMode = displayState.toggle(displayGroup, value, effectiveContext)
                    onToggle(nextMode)
                }
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

internal fun slockTimestampToggleAvailable(
    value: String?,
    timeContext: SlockTimeContext?,
    toggleEnabled: Boolean = true,
): Boolean =
    toggleEnabled &&
        timeContext?.nowEpochMillis != null &&
        parseSlockTimestampEpochMillisOrNull(value, timeContext.localOffsetMinutes) != null

internal fun slockAbsoluteTimestampLabel(
    value: String?,
    timeContext: SlockTimeContext?
): String? =
    if (slockTimestampToggleAvailable(value, timeContext)) {
        formatSlockTimestampLabel(value, timeContext).takeIf { it.isNotBlank() }
    } else {
        null
    }

internal fun resolvedSlockTimestampDisplayMode(
    preferred: SlockTimestampDisplayMode,
    value: String?,
    timeContext: SlockTimeContext?
): SlockTimestampDisplayMode =
    if (slockTimestampToggleAvailable(value, timeContext)) {
        preferred
    } else {
        SlockTimestampDisplayMode.Relative
    }

internal fun nextSlockTimestampDisplayMode(
    current: SlockTimestampDisplayMode,
    value: String?,
    timeContext: SlockTimeContext?
): SlockTimestampDisplayMode {
    if (!slockTimestampToggleAvailable(value, timeContext)) {
        return SlockTimestampDisplayMode.Relative
    }
    return when (current) {
        SlockTimestampDisplayMode.Relative -> SlockTimestampDisplayMode.Absolute
        SlockTimestampDisplayMode.Absolute -> SlockTimestampDisplayMode.Relative
    }
}

@Composable
private fun currentSlockTimeContext(timeContext: SlockTimeContext?): SlockTimeContext? =
    timeContext?.takeIf { it.nowEpochMillis != null }
        ?: LocalSlockTimeContextProvider.current?.current

internal data class SlockRelativeTimeStringSelection(
    val resource: StringResource,
    val formatArg: Int? = null
)

/**
 * Pure resource-selection seam for the relative-time label. [localized] renders
 * exactly this selection; the consumer test drives it directly to lock the
 * singular/many and past/future wiring, so a deleted or mis-wired key turns RED.
 */
internal fun SlockRelativeTimeLabel.Relative.selectLocalizedStringResource(): SlockRelativeTimeStringSelection =
    when (unit) {
        SlockRelativeTimeUnit.Minute ->
            when {
                value == 0 -> SlockRelativeTimeStringSelection(MR.strings.thread_timestamp_now)
                value == -1 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_minute_past_one)
                value < 0 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_minute_past_many, absoluteValue())
                value == 1 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_minute_future_one)
                else -> SlockRelativeTimeStringSelection(MR.strings.relative_time_minute_future_many, value)
            }
        SlockRelativeTimeUnit.Hour ->
            when {
                value == 0 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_hour_now)
                value == -1 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_hour_past_one)
                value < 0 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_hour_past_many, absoluteValue())
                value == 1 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_hour_future_one)
                else -> SlockRelativeTimeStringSelection(MR.strings.relative_time_hour_future_many, value)
            }
        SlockRelativeTimeUnit.Day ->
            when (value) {
                -2 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_day_before_yesterday)
                -1 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_day_yesterday)
                0 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_day_today)
                1 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_day_tomorrow)
                2 -> SlockRelativeTimeStringSelection(MR.strings.relative_time_day_after_tomorrow)
                else ->
                    if (value < 0) {
                        SlockRelativeTimeStringSelection(MR.strings.relative_time_day_past_many, absoluteValue())
                    } else {
                        SlockRelativeTimeStringSelection(MR.strings.relative_time_day_future_many, value)
                    }
            }
    }

@Composable
private fun SlockRelativeTimeLabel.Relative.localized(): String {
    val selection = selectLocalizedStringResource()
    val formatArg = selection.formatArg
    return if (formatArg != null) {
        stringResource(selection.resource, formatArg)
    } else {
        stringResource(selection.resource)
    }
}

private fun SlockRelativeTimeLabel.Relative.absoluteValue(): Int =
    if (value == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(value)
