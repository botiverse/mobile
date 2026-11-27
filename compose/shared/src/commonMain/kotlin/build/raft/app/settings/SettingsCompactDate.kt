package build.raft.app.settings

import androidx.compose.runtime.Composable
import build.raft.app.currentRaftDateTimeContext
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.formatRaftLocalIsoDate
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.raftLocalizedShortMonth
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

/** [settingsCompactDate] as words; the raw value when it can't be read. */
@Composable
internal fun settingsCompactDateLabel(value: String, timeContext: RaftTimeContext? = null): String =
    when (val date = settingsCompactDate(value, currentRaftDateTimeContext(timeContext))) {
        is SettingsCompactDate.Clock -> date.text
        is SettingsCompactDate.MonthDay ->
            stringResource(Res.string.settings_compact_month_day, raftLocalizedShortMonth(date.month), date.day)
        is SettingsCompactDate.IsoDate -> date.text
        null -> value
    }
