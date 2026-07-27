package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.model.SlockRelativeTimeLabel
import build.raft.app.model.SlockRelativeTimeUnit
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.resolveSlockRelativeTimeLabel
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource

@Composable
internal fun slockRelativeTimestampLabel(
    value: String?,
    timeContext: SlockTimeContext? = LocalSlockTimeContext.current
): String =
    when (val label = resolveSlockRelativeTimeLabel(value, timeContext)) {
        SlockRelativeTimeLabel.Empty -> ""
        SlockRelativeTimeLabel.Unknown -> stringResource(MR.strings.relative_time_unknown)
        is SlockRelativeTimeLabel.Literal -> label.value
        is SlockRelativeTimeLabel.Relative -> label.localized()
    }

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
