package build.raft.app.model

import build.raft.app.RaftCoreInternalApi

@RaftCoreInternalApi
const val MillisPerSecond = 1_000L
@RaftCoreInternalApi
const val MillisPerMinute = 60_000L
@RaftCoreInternalApi
const val MillisPerHour = 3_600_000L
@RaftCoreInternalApi
const val MillisPerDay = 86_400_000L

@RaftCoreInternalApi
enum class RaftRelativeTimeUnit {
    Minute,
    Hour,
    Day
}

@RaftCoreInternalApi
sealed interface RaftRelativeTimeLabel {
    data object Empty : RaftRelativeTimeLabel

    data object Unknown : RaftRelativeTimeLabel

    data class Literal(val value: String) : RaftRelativeTimeLabel

    data class Relative(
        val value: Int,
        val unit: RaftRelativeTimeUnit
    ) : RaftRelativeTimeLabel
}

const val RaftTimeFormat12Hour = "12h"
const val RaftTimeFormat24Hour = "24h"

data class RaftTimeContext(
    val localOffsetMinutes: Int,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = RaftTimeFormat24Hour
) {
    fun withTimeFormat(value: String?): RaftTimeContext =
        copy(timeFormat = normalizeRaftTimeFormatPreference(value ?: timeFormat))

    fun formatTimestampLabel(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val today = todayEpochDay ?: nowEpochMillis?.toLocalEpochDay(localOffsetMinutes) ?: return trimmed
        return formatRaftTimestampLabel(trimmed, localOffsetMinutes, today, timeFormat)
    }

    fun formatTimestampClockWithSeconds(value: String?): String =
        formatRaftTimestampClockWithSeconds(value, localOffsetMinutes, timeFormat)

    fun refreshedAt(nowEpochMillis: Long): RaftTimeContext =
        copy(
            todayEpochDay = nowEpochMillis.toLocalEpochDay(localOffsetMinutes),
            nowEpochMillis = nowEpochMillis
        )
}

fun raftTimeContextRefreshDelayMillis(nowEpochMillis: Long): Long {
    val millisIntoMinute = floorMod(nowEpochMillis, MillisPerMinute)
    return MillisPerMinute - millisIntoMinute
}

@RaftCoreInternalApi
fun resolveRaftRelativeTimeLabel(
    value: String?,
    context: RaftTimeContext?
): RaftRelativeTimeLabel {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return RaftRelativeTimeLabel.Empty
    if (trimmed.equals("Now", ignoreCase = true)) {
        return RaftRelativeTimeLabel.Relative(0, RaftRelativeTimeUnit.Minute)
    }
    if (RaftDisplayClockRegex.matches(trimmed)) {
        return RaftRelativeTimeLabel.Literal(trimmed)
    }
    val timestamp =
        parseRaftTimestampEpochMillisOrNull(trimmed, context?.localOffsetMinutes ?: 0)
            ?: return if (context?.nowEpochMillis != null) {
                RaftRelativeTimeLabel.Unknown
            } else {
                RaftRelativeTimeLabel.Literal(trimmed)
            }
    val timeContext = context ?: return RaftRelativeTimeLabel.Unknown
    val now = timeContext.nowEpochMillis ?: return RaftRelativeTimeLabel.Unknown
    val diffMillis = timestamp.toDouble() - now.toDouble()
    val absoluteMillis = kotlin.math.abs(diffMillis)
    val unit =
        when {
            absoluteMillis < MillisPerHour -> RaftRelativeTimeUnit.Minute
            absoluteMillis < MillisPerDay -> RaftRelativeTimeUnit.Hour
            else -> RaftRelativeTimeUnit.Day
        }
    val divisor =
        when (unit) {
            RaftRelativeTimeUnit.Minute -> MillisPerMinute
            RaftRelativeTimeUnit.Hour -> MillisPerHour
            RaftRelativeTimeUnit.Day -> MillisPerDay
        }
    // JavaScript Math.round is floor(x + 0.5), including negative half values.
    // Match Web exactly instead of Kotlin's ties-to-even round().
    val rounded = kotlin.math.floor(diffMillis / divisor.toDouble() + 0.5)
    return RaftRelativeTimeLabel.Relative(
        value = rounded.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt(),
        unit = unit
    )
}

fun normalizeRaftTimeFormatPreference(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RaftTimeFormat12Hour -> RaftTimeFormat12Hour
        else -> RaftTimeFormat24Hour
    }

fun formatRaftTimestampLabel(
    value: String?,
    context: RaftTimeContext?
): String =
    context?.formatTimestampLabel(value) ?: value?.trim().orEmpty()

fun formatRaftTimestampClockWithSeconds(
    value: String?,
    context: RaftTimeContext?
): String =
    context?.formatTimestampClockWithSeconds(value)
        ?: formatRaftTimestampClockWithSeconds(value, localOffsetMinutes = 0)

fun parseRaftTimestampEpochMillisOrNull(
    value: String?,
    localOffsetMinutes: Int = 0
): Long? {
    val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    trimmed.toEpochTimestampMillisOrNull()?.let { return it }
    val match = RaftTimestampRegex.matchEntire(trimmed) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
    val minute = match.groupValues[5].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
    val second = match.groupValues[6].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
    val millis = match.groupValues[7].toMillisFraction()
    val offsetMinutes =
        match.groupValues[8].toOffsetMinutesOrNull()
            ?: localOffsetMinutes.takeIf { match.groupValues[8].isBlank() }
            ?: return null
    if (!isValidDateTime(year, month, day, hour, minute, second)) return null
    val epochDay = daysSinceUnixEpoch(year, month, day)
    return epochDay * MillisPerDay +
        hour * MillisPerHour +
        minute * MillisPerMinute +
        second * MillisPerSecond +
        millis -
        offsetMinutes * MillisPerMinute
}

fun formatRaftTimestampLabel(
    value: String?,
    localOffsetMinutes: Int,
    todayEpochDay: Long,
    timeFormat: String = RaftTimeFormat24Hour
): String {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val epochMillis =
        parseRaftTimestampEpochMillisOrNull(trimmed, localOffsetMinutes) ?: return trimmed
    return formatRaftTimestampEpochMillis(epochMillis, localOffsetMinutes, todayEpochDay, timeFormat)
}

fun formatRaftTimestampEpochMillis(
    epochMillis: Long,
    localOffsetMinutes: Int,
    todayEpochDay: Long,
    timeFormat: String = RaftTimeFormat24Hour
): String {
    val localMillis = epochMillis + localOffsetMinutes * MillisPerMinute
    val localEpochDay = floorDiv(localMillis, MillisPerDay)
    val millisOfDay = floorMod(localMillis, MillisPerDay)
    val date = civilDateFromEpochDay(localEpochDay)
    val today = civilDateFromEpochDay(todayEpochDay)
    val hour = (millisOfDay / MillisPerHour).toInt()
    val minute = ((millisOfDay % MillisPerHour) / MillisPerMinute).toInt()
    val time = formatRaftClock(hour, minute, null, timeFormat)
    return when {
        localEpochDay == todayEpochDay -> time
        date.year == today.year -> "${date.month.twoDigits()}-${date.day.twoDigits()} $time"
        else -> "${date.year}-${date.month.twoDigits()}-${date.day.twoDigits()} $time"
    }
}

fun formatRaftTimestampClockWithSeconds(
    value: String?,
    localOffsetMinutes: Int,
    timeFormat: String = RaftTimeFormat24Hour
): String {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val epochMillis =
        parseRaftTimestampEpochMillisOrNull(trimmed, localOffsetMinutes) ?: return trimmed
    return formatRaftTimestampClockWithSeconds(epochMillis, localOffsetMinutes, timeFormat)
}

fun formatRaftTimestampClockWithSeconds(
    epochMillis: Long,
    localOffsetMinutes: Int,
    timeFormat: String = RaftTimeFormat24Hour
): String {
    val localMillis = epochMillis + localOffsetMinutes * MillisPerMinute
    val millisOfDay = floorMod(localMillis, MillisPerDay)
    val hour = (millisOfDay / MillisPerHour).toInt()
    val minute = ((millisOfDay % MillisPerHour) / MillisPerMinute).toInt()
    val second = ((millisOfDay % MillisPerMinute) / MillisPerSecond).toInt()
    return formatRaftClock(hour, minute, second, timeFormat)
}

fun raftEpochDayForDate(year: Int, month: Int, day: Int): Long =
    daysSinceUnixEpoch(year, month, day)

fun raftStartOfDayUtcIso(epochDay: Long, localOffsetMinutes: Int): String {
    val epochMillis = epochDay * MillisPerDay - localOffsetMinutes * MillisPerMinute
    return formatEpochMillisAsUtcIso(epochMillis)
}

fun raftEpochMillisUtcIso(epochMillis: Long): String =
    formatEpochMillisAsUtcIso(epochMillis)

private fun Long.toLocalEpochDay(localOffsetMinutes: Int): Long =
    floorDiv(this + localOffsetMinutes.toLong() * MillisPerMinute, MillisPerDay)

private val RaftTimestampRegex =
    Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T\s](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(?:\s*(Z|[+-]\d{2}(?::?\d{2})?))?)?$""")

private val RaftDisplayClockRegex =
    Regex("""^\d{1,2}:\d{2}(?::\d{2})?(?:\s[AP]M)?$""", RegexOption.IGNORE_CASE)

@RaftCoreInternalApi
data class RaftCivilDate(
    val year: Int,
    val month: Int,
    val day: Int
)

private fun String.toEpochTimestampMillisOrNull(): Long? {
    if (!all { it.isDigit() }) return null
    val epoch = toLongOrNull() ?: return null
    return when (length) {
        10 -> epoch * MillisPerSecond
        13 -> epoch
        else -> null
    }
}

private fun String.toMillisFraction(): Long {
    if (isBlank()) return 0L
    return take(3).padEnd(3, '0').toLongOrNull() ?: 0L
}

private fun String.toOffsetMinutesOrNull(): Int? {
    if (isBlank()) return null
    if (this == "Z") return 0
    val sign = when (firstOrNull()) {
        '+' -> 1
        '-' -> -1
        else -> return null
    }
    val raw = drop(1).replace(":", "")
    val hours = raw.take(2).toIntOrNull() ?: return null
    val minutes =
        when (raw.length) {
            2 -> 0
            4 -> raw.drop(2).toIntOrNull() ?: return null
            else -> return null
        }
    if (hours !in 0..23 || minutes !in 0..59) return null
    return sign * (hours * 60 + minutes)
}

@RaftCoreInternalApi
fun formatRaftClock(
    hour: Int,
    minute: Int,
    second: Int?,
    timeFormat: String
): String {
    if (normalizeRaftTimeFormatPreference(timeFormat) != RaftTimeFormat12Hour) {
        return buildString {
            append(hour.twoDigits())
            append(':')
            append(minute.twoDigits())
            if (second != null) {
                append(':')
                append(second.twoDigits())
            }
        }
    }
    val displayHour = (hour % 12).let { if (it == 0) 12 else it }
    return buildString {
        append(displayHour.twoDigits())
        append(':')
        append(minute.twoDigits())
        if (second != null) {
            append(':')
            append(second.twoDigits())
        }
        append(if (hour < 12) " AM" else " PM")
    }
}

private fun isValidDateTime(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int
): Boolean =
    month in 1..12 &&
        day in 1..daysInMonth(year, month) &&
        hour in 0..23 &&
        minute in 0..59 &&
        second in 0..59

private fun daysInMonth(year: Int, month: Int): Int =
    when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 0
    }

private fun isLeapYear(year: Int): Boolean =
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private fun daysSinceUnixEpoch(year: Int, month: Int, day: Int): Long {
    val adjustedYear = year - if (month <= 2) 1 else 0
    val era = floorDiv(adjustedYear, 400)
    val yearOfEra = adjustedYear - era * 400
    val adjustedMonth = month + if (month > 2) -3 else 9
    val dayOfYear = (153 * adjustedMonth + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}

@RaftCoreInternalApi
fun civilDateFromEpochDay(epochDay: Long): RaftCivilDate {
    val zeroDay = epochDay + 719_468L
    val era = floorDiv(zeroDay, 146_097L)
    val dayOfEra = zeroDay - era * 146_097L
    val yearOfEra =
        (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    val adjustedYear = year + if (month <= 2L) 1L else 0L
    return RaftCivilDate(adjustedYear.toInt(), month.toInt(), day.toInt())
}

@RaftCoreInternalApi
fun floorDiv(value: Int, divisor: Int): Int {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0 && (value xor divisor) < 0) quotient - 1 else quotient
}

@RaftCoreInternalApi
fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0L && (value xor divisor) < 0L) quotient - 1 else quotient
}

@RaftCoreInternalApi
fun floorMod(value: Long, divisor: Long): Long =
    value - floorDiv(value, divisor) * divisor

private fun formatEpochMillisAsUtcIso(epochMillis: Long): String {
    val epochDay = floorDiv(epochMillis, MillisPerDay)
    val millisOfDay = floorMod(epochMillis, MillisPerDay)
    val date = civilDateFromEpochDay(epochDay)
    val hour = (millisOfDay / MillisPerHour).toInt()
    val minute = ((millisOfDay % MillisPerHour) / MillisPerMinute).toInt()
    val second = ((millisOfDay % MillisPerMinute) / MillisPerSecond).toInt()
    val millis = (millisOfDay % MillisPerSecond).toInt()
    val millisSuffix = if (millis == 0) "" else ".${millis.toString().padStart(3, '0')}"
    return buildString {
        append(date.year)
        append('-')
        append(date.month.twoDigits())
        append('-')
        append(date.day.twoDigits())
        append('T')
        append(hour.twoDigits())
        append(':')
        append(minute.twoDigits())
        append(':')
        append(second.twoDigits())
        append(millisSuffix)
        append('Z')
    }
}

@RaftCoreInternalApi
fun Int.twoDigits(): String =
    if (this < 10) "0$this" else toString()
