package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.model.MillisPerDay
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.formatRaftClock
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*

/**
 * A date in a compact list row (web `formatCompactDate`): today, the clock in the person's 12h/24h
 * choice ("15:00"); earlier this year, the month and day ("Oct 21"); an earlier year, the ISO date
 * ("2025-10-21").
 */
internal sealed interface SettingsCompactDate {
    data class Clock(val text: String) : SettingsCompactDate
    data class MonthDay(val month: Int, val day: Int) : SettingsCompactDate
    data class IsoDate(val text: String) : SettingsCompactDate
}

/** [value] in [context]'s zone against its now; null without a now or when [value] is not a timestamp. */
internal fun settingsCompactDate(value: String?, context: RaftTimeContext?): SettingsCompactDate? {
    val now = context?.nowEpochMillis ?: return null
    val offset = context.localOffsetMinutes
    val epochMillis = parseRaftTimestampEpochMillisOrNull(value, offset) ?: return null
    val local = epochMillis + offset * 60_000L
    val day = local.floorDiv(MillisPerDay)
    val today = (now + offset * 60_000L).floorDiv(MillisPerDay)
    val date = civilDateFromEpochDay(day)
    return when {
        day == today -> {
            val millisOfDay = local.mod(MillisPerDay)
            SettingsCompactDate.Clock(
                formatRaftClock((millisOfDay / 3_600_000L).toInt(), ((millisOfDay / 60_000L) % 60).toInt(), null, context.timeFormat)
            )
        }
        date.year == civilDateFromEpochDay(today).year -> SettingsCompactDate.MonthDay(date.month, date.day)
        else ->
            SettingsCompactDate.IsoDate(
                "${date.year}-${date.month.toString().padStart(2, '0')}-${date.day.toString().padStart(2, '0')}"
            )
    }
}

private val SettingsCompactMonths =
    listOf(
        Res.string.task_head_month_1, Res.string.task_head_month_2, Res.string.task_head_month_3,
        Res.string.task_head_month_4, Res.string.task_head_month_5, Res.string.task_head_month_6,
        Res.string.task_head_month_7, Res.string.task_head_month_8, Res.string.task_head_month_9,
        Res.string.task_head_month_10, Res.string.task_head_month_11, Res.string.task_head_month_12,
    )

/** [settingsCompactDate] as words; the raw value when it can't be read. */
@Composable
internal fun settingsCompactDateLabel(value: String, timeContext: RaftTimeContext? = null): String =
    when (val date = settingsCompactDate(value, currentRaftTimeContext(timeContext))) {
        is SettingsCompactDate.Clock -> date.text
        is SettingsCompactDate.MonthDay ->
            stringResource(Res.string.settings_compact_month_day, stringResource(SettingsCompactMonths[date.month - 1]), date.day)
        is SettingsCompactDate.IsoDate -> date.text
        null -> value
    }
