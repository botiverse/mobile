package ai.slock.android.ui.time

import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.slockEpochDayForDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun androidSlockTimeContext(
    zoneId: ZoneId = ZoneId.systemDefault(),
    timeFormat: String? = null
): SlockTimeContext {
    val now = Instant.now()
    val today = LocalDate.now(zoneId)
    return SlockTimeContext(
        localOffsetMinutes = zoneId.rules.getOffset(now).totalSeconds / 60,
        todayEpochDay = slockEpochDayForDate(today.year, today.monthValue, today.dayOfMonth),
        nowEpochMillis = now.toEpochMilli()
    ).withTimeFormat(timeFormat)
}
