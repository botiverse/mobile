package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.semantics.semantics
import com.tencent.kuikly.compose.ui.semantics.stateDescription
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.tmm.kmmresource.compose.stringResource

internal enum class SlockTimestampDisplayMode {
    Relative,
    Absolute
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
 * The default stays relative. A parseable timestamp with a live local time context can be toggled
 * to the existing compact local absolute formatter and back. State is deliberately remembered by
 * this composition instance only: changing the source value resets it, and no preference is
 * persisted or shared with another timestamp.
 */
@Composable
internal fun SlockRelativeTimestampText(
    value: String?,
    modifier: Modifier = Modifier,
    timeContext: SlockTimeContext? = null,
    style: TextStyle,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
    onToggle: (SlockTimestampDisplayMode) -> Unit = {}
) {
    val effectiveContext = currentSlockTimeContext(timeContext)
    val toggleAvailable = slockTimestampToggleAvailable(value, effectiveContext)
    var displayMode by remember(value?.trim(), toggleAvailable) {
        mutableStateOf(SlockTimestampDisplayMode.Relative)
    }
    val resolvedMode =
        if (toggleAvailable) displayMode else SlockTimestampDisplayMode.Relative
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
                    val nextMode = nextSlockTimestampDisplayMode(displayMode, value, effectiveContext)
                    onToggle(nextMode)
                    displayMode = nextMode
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
    timeContext: SlockTimeContext?
): Boolean =
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

@Composable
private fun SlockRelativeTimeLabel.Relative.localized(): String =
    when (unit) {
        SlockRelativeTimeUnit.Minute ->
            when {
                value == 0 -> stringResource(MR.strings.thread_timestamp_now)
                value == -1 -> stringResource(MR.strings.relative_time_minute_past_one)
                value < 0 -> stringResource(MR.strings.relative_time_minute_past_many, absoluteValue())
                value == 1 -> stringResource(MR.strings.relative_time_minute_future_one)
                else -> stringResource(MR.strings.relative_time_minute_future_many, value)
            }
        SlockRelativeTimeUnit.Hour ->
            when {
                value == 0 -> stringResource(MR.strings.relative_time_hour_now)
                value == -1 -> stringResource(MR.strings.relative_time_hour_past_one)
                value < 0 -> stringResource(MR.strings.relative_time_hour_past_many, absoluteValue())
                value == 1 -> stringResource(MR.strings.relative_time_hour_future_one)
                else -> stringResource(MR.strings.relative_time_hour_future_many, value)
            }
        SlockRelativeTimeUnit.Day ->
            when (value) {
                -2 -> stringResource(MR.strings.relative_time_day_before_yesterday)
                -1 -> stringResource(MR.strings.relative_time_day_yesterday)
                0 -> stringResource(MR.strings.relative_time_day_today)
                1 -> stringResource(MR.strings.relative_time_day_tomorrow)
                2 -> stringResource(MR.strings.relative_time_day_after_tomorrow)
                else ->
                    if (value < 0) {
                        stringResource(MR.strings.relative_time_day_past_many, absoluteValue())
                    } else {
                        stringResource(MR.strings.relative_time_day_future_many, value)
                    }
            }
    }

private fun SlockRelativeTimeLabel.Relative.absoluteValue(): Int =
    if (value == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(value)
