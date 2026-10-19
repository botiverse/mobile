package build.raft.app.model

import build.raft.app.SlockCoreInternalApi

@SlockCoreInternalApi
const val MillisPerSecond = 1_000L
@SlockCoreInternalApi
const val MillisPerMinute = 60_000L
@SlockCoreInternalApi
const val MillisPerHour = 3_600_000L
@SlockCoreInternalApi
const val MillisPerDay = 86_400_000L

@SlockCoreInternalApi
enum class SlockRelativeTimeUnit {
    Minute,
    Hour,
    Day
}

@SlockCoreInternalApi
sealed interface SlockRelativeTimeLabel {
    data object Empty : SlockRelativeTimeLabel

    data object Unknown : SlockRelativeTimeLabel

    data class Literal(val value: String) : SlockRelativeTimeLabel

    data class Relative(
        val value: Int,
        val unit: SlockRelativeTimeUnit
    ) : SlockRelativeTimeLabel
}

const val SlockTimeFormat12Hour = "12h"
const val SlockTimeFormat24Hour = "24h"

data class SlockTimeContext(
    val localOffsetMinutes: Int,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour
) {
    fun withTimeFormat(value: String?): SlockTimeContext =
        copy(timeFormat = normalizeSlockTimeFormatPreference(value ?: timeFormat))

    fun formatTimestampLabel(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val today = todayEpochDay ?: nowEpochMillis?.toLocalEpochDay(localOffsetMinutes) ?: return trimmed
        return formatSlockTimestampLabel(trimmed, localOffsetMinutes, today, timeFormat)
    }

    fun formatTimestampClockWithSeconds(value: String?): String =
        formatSlockTimestampClockWithSeconds(value, localOffsetMinutes, timeFormat)

    fun refreshedAt(nowEpochMillis: Long): SlockTimeContext =
        copy(
            todayEpochDay = nowEpochMillis.toLocalEpochDay(localOffsetMinutes),
            nowEpochMillis = nowEpochMillis
        )
}

fun slockTimeContextRefreshDelayMillis(nowEpochMillis: Long): Long {
    val millisIntoMinute = floorMod(nowEpochMillis, MillisPerMinute)
    return MillisPerMinute - millisIntoMinute
}

@SlockCoreInternalApi
fun resolveSlockRelativeTimeLabel(
    value: String?,
    context: SlockTimeContext?
): SlockRelativeTimeLabel {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return SlockRelativeTimeLabel.Empty
    if (trimmed.equals("Now", ignoreCase = true)) {
        return SlockRelativeTimeLabel.Relative(0, SlockRelativeTimeUnit.Minute)
    }
    if (SlockDisplayClockRegex.matches(trimmed)) {
        return SlockRelativeTimeLabel.Literal(trimmed)
    }
    val timestamp =
        parseSlockTimestampEpochMillisOrNull(trimmed, context?.localOffsetMinutes ?: 0)
            ?: return if (context?.nowEpochMillis != null) {
                SlockRelativeTimeLabel.Unknown
            } else {
                SlockRelativeTimeLabel.Literal(trimmed)
            }
    val timeContext = context ?: return SlockRelativeTimeLabel.Unknown
    val now = timeContext.nowEpochMillis ?: return SlockRelativeTimeLabel.Unknown
    val diffMillis = timestamp.toDouble() - now.toDouble()
    val absoluteMillis = kotlin.math.abs(diffMillis)
    val unit =
        when {
            absoluteMillis < MillisPerHour -> SlockRelativeTimeUnit.Minute
            absoluteMillis < MillisPerDay -> SlockRelativeTimeUnit.Hour
            else -> SlockRelativeTimeUnit.Day
        }
    val divisor =
        when (unit) {
            SlockRelativeTimeUnit.Minute -> MillisPerMinute
            SlockRelativeTimeUnit.Hour -> MillisPerHour
            SlockRelativeTimeUnit.Day -> MillisPerDay
        }
    // JavaScript Math.round is floor(x + 0.5), including negative half values.
    // Match Web exactly instead of Kotlin's ties-to-even round().
    val rounded = kotlin.math.floor(diffMillis / divisor.toDouble() + 0.5)
    return SlockRelativeTimeLabel.Relative(
        value = rounded.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt(),
        unit = unit
    )
}

fun normalizeSlockTimeFormatPreference(value: String?): String =
    when (value?.trim()?.lowercase()) {
        SlockTimeFormat12Hour -> SlockTimeFormat12Hour
        else -> SlockTimeFormat24Hour
    }

fun formatSlockTimestampLabel(
    value: String?,
    context: SlockTimeContext?
): String =
    context?.formatTimestampLabel(value) ?: value?.trim().orEmpty()

fun formatSlockTimestampClockWithSeconds(
    value: String?,
    context: SlockTimeContext?
): String =
    context?.formatTimestampClockWithSeconds(value)
        ?: formatSlockTimestampClockWithSeconds(value, localOffsetMinutes = 0)

fun parseSlockTimestampEpochMillisOrNull(
    value: String?,
    localOffsetMinutes: Int = 0
): Long? {
    val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    trimmed.toEpochTimestampMillisOrNull()?.let { return it }
    val match = SlockTimestampRegex.matchEntire(trimmed) ?: return null
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

fun formatSlockTimestampLabel(
    value: String?,
    localOffsetMinutes: Int,
    todayEpochDay: Long,
    timeFormat: String = SlockTimeFormat24Hour
): String {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val epochMillis =
        parseSlockTimestampEpochMillisOrNull(trimmed, localOffsetMinutes) ?: return trimmed
    return formatSlockTimestampEpochMillis(epochMillis, localOffsetMinutes, todayEpochDay, timeFormat)
}

fun formatSlockTimestampEpochMillis(
    epochMillis: Long,
    localOffsetMinutes: Int,
    todayEpochDay: Long,
    timeFormat: String = SlockTimeFormat24Hour
): String {
    val localMillis = epochMillis + localOffsetMinutes * MillisPerMinute
    val localEpochDay = floorDiv(localMillis, MillisPerDay)
    val millisOfDay = floorMod(localMillis, MillisPerDay)
    val date = civilDateFromEpochDay(localEpochDay)
    val today = civilDateFromEpochDay(todayEpochDay)
    val hour = (millisOfDay / MillisPerHour).toInt()
    val minute = ((millisOfDay % MillisPerHour) / MillisPerMinute).toInt()
    val time = formatSlockClock(hour, minute, null, timeFormat)
    return when {
        localEpochDay == todayEpochDay -> time
        date.year == today.year -> "${date.month.twoDigits()}-${date.day.twoDigits()} $time"
        else -> "${date.year}-${date.month.twoDigits()}-${date.day.twoDigits()} $time"
    }
}

fun formatSlockTimestampClockWithSeconds(
    value: String?,
    localOffsetMinutes: Int,
    timeFormat: String = SlockTimeFormat24Hour
): String {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val epochMillis =
        parseSlockTimestampEpochMillisOrNull(trimmed, localOffsetMinutes) ?: return trimmed
    return formatSlockTimestampClockWithSeconds(epochMillis, localOffsetMinutes, timeFormat)
}

fun formatSlockTimestampClockWithSeconds(
    epochMillis: Long,
    localOffsetMinutes: Int,
    timeFormat: String = SlockTimeFormat24Hour
): String {
    val localMillis = epochMillis + localOffsetMinutes * MillisPerMinute
    val millisOfDay = floorMod(localMillis, MillisPerDay)
    val hour = (millisOfDay / MillisPerHour).toInt()
    val minute = ((millisOfDay % MillisPerHour) / MillisPerMinute).toInt()
    val second = ((millisOfDay % MillisPerMinute) / MillisPerSecond).toInt()
    return formatSlockClock(hour, minute, second, timeFormat)
}

fun slockEpochDayForDate(year: Int, month: Int, day: Int): Long =
    daysSinceUnixEpoch(year, month, day)

fun slockStartOfDayUtcIso(epochDay: Long, localOffsetMinutes: Int): String {
    val epochMillis = epochDay * MillisPerDay - localOffsetMinutes * MillisPerMinute
    return formatEpochMillisAsUtcIso(epochMillis)
}

fun slockEpochMillisUtcIso(epochMillis: Long): String =
    formatEpochMillisAsUtcIso(epochMillis)

private fun Long.toLocalEpochDay(localOffsetMinutes: Int): Long =
    floorDiv(this + localOffsetMinutes.toLong() * MillisPerMinute, MillisPerDay)

private val SlockTimestampRegex =
    Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T\s](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(?:\s*(Z|[+-]\d{2}(?::?\d{2})?))?)?$""")

private val SlockDisplayClockRegex =
    Regex("""^\d{1,2}:\d{2}(?::\d{2})?(?:\s[AP]M)?$""", RegexOption.IGNORE_CASE)

@SlockCoreInternalApi
data class SlockCivilDate(
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

@SlockCoreInternalApi
fun formatSlockClock(
    hour: Int,
    minute: Int,
    second: Int?,
    timeFormat: String
): String {
    if (normalizeSlockTimeFormatPreference(timeFormat) != SlockTimeFormat12Hour) {
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

@SlockCoreInternalApi
fun civilDateFromEpochDay(epochDay: Long): SlockCivilDate {
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
    return SlockCivilDate(adjustedYear.toInt(), month.toInt(), day.toInt())
}

@SlockCoreInternalApi
fun floorDiv(value: Int, divisor: Int): Int {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0 && (value xor divisor) < 0) quotient - 1 else quotient
}

@SlockCoreInternalApi
fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0L && (value xor divisor) < 0L) quotient - 1 else quotient
}

@SlockCoreInternalApi
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

@SlockCoreInternalApi
fun Int.twoDigits(): String =
    if (this < 10) "0$this" else toString()
