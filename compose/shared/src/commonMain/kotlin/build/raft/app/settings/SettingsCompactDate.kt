package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.formatRaftLocalIsoDate
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

/**
 * [value] in [context]'s zone against its local day (`todayEpochDay` or `nowEpochMillis`).
 * Works with low-frequency `dateContext` (`nowEpochMillis == null`), falls back to a formatted
 * local ISO date if [context] carries no day, and returns null without [context] or a parseable timestamp.
 */
internal fun settingsCompactDate(value: String?, context: RaftTimeContext?): SettingsCompactDate? {
    val resolved = context ?: return null
    val epochMillis = parseRaftTimestampEpochMillisOrNull(value, resolved.localOffsetMinutes) ?: return null
    val day = resolved.localEpochDay(epochMillis)
    val today = resolved.resolvedTodayEpochDay()
    val date = civilDateFromEpochDay(day)
    return when {
        today == null -> SettingsCompactDate.IsoDate(formatRaftLocalIsoDate(epochMillis, resolved.localOffsetMinutes))
        day == today -> SettingsCompactDate.Clock(resolved.formatClock(epochMillis))
        date.year == civilDateFromEpochDay(today).year -> SettingsCompactDate.MonthDay(date.month, date.day)
        else -> SettingsCompactDate.IsoDate(formatRaftLocalIsoDate(epochMillis, resolved.localOffsetMinutes))
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
    when (val date = settingsCompactDate(value, currentRaftDateTimeContext(timeContext))) {
        is SettingsCompactDate.Clock -> date.text
        is SettingsCompactDate.MonthDay ->
            stringResource(Res.string.settings_compact_month_day, stringResource(SettingsCompactMonths[date.month - 1]), date.day)
        is SettingsCompactDate.IsoDate -> date.text
        null -> value
    }
