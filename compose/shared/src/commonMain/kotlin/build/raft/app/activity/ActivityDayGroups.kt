package build.raft.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.InboxItem
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerHour
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.floorDiv
import build.raft.app.model.floorMod
import build.raft.app.model.formatRaftClock
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import org.jetbrains.compose.resources.StringResource

/*
 * Activity's rows by day (web botiverse/slock #13030: activityDayGroups.ts, ThreadsInbox.tsx,
 * ActivityRowTime.tsx; raft-design patterns/lists.md "Rows grouped by day", "Times in list rows").
 */

/** The time an Activity row shows, which is the time it is grouped by (web `inboxItemTime`). */
internal fun InboxItem.activityRowTimeValue(): String? = latestActivityAt ?: lastMessageAt

/** What a day's heading says (web `formatDayLabel`). */
internal sealed interface ActivityDayLabel {
    data object Today : ActivityDayLabel

    data object Yesterday : ActivityDayLabel

    /** The weekday and date ("Tuesday, October 20"), with the year when it isn't today's year. */
    data class Date(val epochDay: Long, val withYear: Boolean) : ActivityDayLabel
}

internal fun activityDayLabel(epochDay: Long, todayEpochDay: Long): ActivityDayLabel =
    when (epochDay) {
        todayEpochDay -> ActivityDayLabel.Today
        todayEpochDay - 1L -> ActivityDayLabel.Yesterday
        else ->
            ActivityDayLabel.Date(
                epochDay = epochDay,
                withYear = civilDateFromEpochDay(epochDay).year != civilDateFromEpochDay(todayEpochDay).year,
            )
    }

/** One day's rows under its heading; [epochDay] and [label] are null for rows before any readable time. */
internal data class ActivityDayGroup(
    val epochDay: Long?,
    val label: ActivityDayLabel?,
    val rows: List<InboxItem>,
) {
    /** By the day, not its heading: stable when Today turns into Yesterday, so its rows stay put. */
    val key: String = activityDayGroupKey(epochDay)
}

internal const val ACTIVITY_DAY_GROUP_KEY_PREFIX = "activity_day:"

internal fun activityDayGroupKey(epochDay: Long?): String = "$ACTIVITY_DAY_GROUP_KEY_PREFIX${epochDay ?: "none"}"

internal fun RaftTimeContext.activityTodayEpochDay(): Long? =
    todayEpochDay ?: nowEpochMillis?.let { activityLocalEpochDay(it, localOffsetMinutes) }

private fun activityLocalEpochDay(epochMillis: Long, localOffsetMinutes: Int): Long =
    floorDiv(epochMillis + localOffsetMinutes * MillisPerMinute, MillisPerDay)

/**
 * The listed rows by day, newest first, one heading per day that has rows: a filter, a Done or an
 * Unfollow that empties a day takes its heading with it. Days count in the person's zone, and a time a
 * little ahead of this device's clock counts as today.
 *
 * Unlike the web, which sorts the rows by their time first, this keeps the order the list presents:
 * the stable order updates a held row in place and reveals the new order later
 * ([activityItemsPreservingPresentedOrder]), and moving it into another day here would reorder the
 * rows under a finger. A row whose day is not older than the group it follows stays in that group,
 * so no heading shows twice; a row with no readable time stays where it is too.
 */
internal fun groupActivityRowsByDay(items: List<InboxItem>, timeContext: RaftTimeContext?): List<ActivityDayGroup> {
    if (items.isEmpty()) return emptyList()
    val today = timeContext?.activityTodayEpochDay() ?: return listOf(ActivityDayGroup(null, null, items))
    val offset = timeContext.localOffsetMinutes
    val groups = mutableListOf<ActivityDayGroup>()
    var currentDay: Long? = null
    var currentRows = mutableListOf<InboxItem>()
    fun close() {
        if (currentRows.isNotEmpty()) {
            groups += ActivityDayGroup(currentDay, currentDay?.let { activityDayLabel(it, today) }, currentRows)
        }
    }
    for (item in items) {
        val day =
            parseRaftTimestampEpochMillisOrNull(item.activityRowTimeValue(), offset)
                ?.let { activityLocalEpochDay(it, offset).coerceAtMost(today) }
        val startsDay = day != null && (currentRows.isEmpty() || currentDay == null || day < currentDay)
        if (startsDay) {
            close()
            currentDay = day
            currentRows = mutableListOf()
        }
        currentRows += item
    }
    close()
    return groups
}

/** The string resources a heading renders from: tested directly, so a mis-wired key turns red. */
internal data class ActivityDayLabelSelection(
    val resource: StringResource,
    val weekday: StringResource? = null,
    val month: StringResource? = null,
    val day: Int? = null,
    val year: Int? = null,
)

internal fun ActivityDayLabel.selection(): ActivityDayLabelSelection =
    when (this) {
        ActivityDayLabel.Today -> ActivityDayLabelSelection(Res.string.activity_list_day_today)
        ActivityDayLabel.Yesterday -> ActivityDayLabelSelection(Res.string.activity_list_day_yesterday)
        is ActivityDayLabel.Date -> {
            val date = civilDateFromEpochDay(epochDay)
            ActivityDayLabelSelection(
                resource = if (withYear) Res.string.activity_list_day_date_year else Res.string.activity_list_day_date,
                weekday = activityWeekdayResource(epochDay),
                month = ActivityMonthResources[date.month - 1],
                day = date.day,
                year = date.year.takeIf { withYear },
            )
        }
    }

@Composable
internal fun ActivityDayLabel.localized(): String {
    val selection = selection()
    val weekday = selection.weekday ?: return stringResource(selection.resource)
    val month = selection.month ?: return stringResource(selection.resource)
    val day = selection.day ?: 0
    return if (selection.year != null) {
        stringResource(selection.resource, stringResource(weekday), stringResource(month), day, selection.year)
    } else {
        stringResource(selection.resource, stringResource(weekday), stringResource(month), day)
    }
}

private fun activityWeekdayResource(epochDay: Long): StringResource =
    ActivityWeekdayResources[floorMod(epochDay + 4L, 7L).toInt()]

private val ActivityWeekdayResources =
    listOf(
        Res.string.activity_list_day_weekday_sunday,
        Res.string.activity_list_day_weekday_monday,
        Res.string.activity_list_day_weekday_tuesday,
        Res.string.activity_list_day_weekday_wednesday,
        Res.string.activity_list_day_weekday_thursday,
        Res.string.activity_list_day_weekday_friday,
        Res.string.activity_list_day_weekday_saturday,
    )

private val ActivityMonthResources =
    listOf(
        Res.string.activity_list_day_month_january,
        Res.string.activity_list_day_month_february,
        Res.string.activity_list_day_month_march,
        Res.string.activity_list_day_month_april,
        Res.string.activity_list_day_month_may,
        Res.string.activity_list_day_month_june,
        Res.string.activity_list_day_month_july,
        Res.string.activity_list_day_month_august,
        Res.string.activity_list_day_month_september,
        Res.string.activity_list_day_month_october,
        Res.string.activity_list_day_month_november,
        Res.string.activity_list_day_month_december,
    )

/**
 * raft-ui `InboxItemListHeading` (`text-footnote` bold `foreground-hint`, 4dp above and below its
 * words, on the rows' content line), sticky over its day on the list's own surface so no row shows
 * through it. Groups after the first sit 6dp under the rows above (`InboxItemListGroup mt-1.5`).
 */
@Composable
internal fun ActivityDayHeading(label: ActivityDayLabel, first: Boolean, background: Color, modifier: Modifier = Modifier) {
    RaftText(
        text = label.localized(),
        modifier =
            modifier
                .fillMaxWidth()
                .background(background)
                .raftSafeAreaHorizontalPadding()
                .padding(top = if (first) 0.dp else 6.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics { heading() },
        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** [ActivityDayHeading] as the list's sticky header: it stays in view while its day's rows scroll. */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.activityDayHeadingItem(heading: ActivityConversationListEntry.DayHeading, background: Color) {
    stickyHeader(key = heading.key, contentType = "activity_day_heading") {
        ActivityDayHeading(heading.label, first = heading.first, background = background)
    }
}

/** Which day an older row's time names in words (web `formatRowTime`). */
internal sealed interface ActivityRowTimeDay {
    data object Yesterday : ActivityRowTimeDay

    /** Within the last week: the weekday alone. */
    data class Weekday(val epochDay: Long) : ActivityRowTimeDay

    /** Past a week: the weekday and date, as its heading says it. */
    data class Date(val label: ActivityDayLabel.Date) : ActivityRowTimeDay
}

/**
 * A row's time in a list grouped by day (web `formatRowTime`): "now" under a minute, whole minutes
 * under an hour on any day, whole hours while it is still today; an earlier day's row shows only its
 * clock, and only to a hovering pointer, since its heading names the day. Counts whole steps down
 * (1h 59m is 1h). Null without a clock or a readable time.
 */
internal sealed interface ActivityRowTime {
    data object Now : ActivityRowTime

    data class Minutes(val count: Int) : ActivityRowTime

    data class Hours(val count: Int) : ActivityRowTime

    data class Older(val clock: String, val day: ActivityRowTimeDay) : ActivityRowTime
}

internal fun activityRowTime(value: String?, timeContext: RaftTimeContext?): ActivityRowTime? {
    val context = timeContext ?: return null
    val now = context.nowEpochMillis ?: return null
    val offset = context.localOffsetMinutes
    val at = parseRaftTimestampEpochMillisOrNull(value, offset) ?: return null
    val elapsed = (now - at).coerceAtLeast(0L)
    if (elapsed < MillisPerMinute) return ActivityRowTime.Now
    if (elapsed < MillisPerHour) return ActivityRowTime.Minutes((elapsed / MillisPerMinute).toInt())
    val today = activityLocalEpochDay(now, offset)
    val day = activityLocalEpochDay(at, offset)
    if (day == today) return ActivityRowTime.Hours((elapsed / MillisPerHour).toInt())
    val minuteOfDay = floorMod(at + offset * MillisPerMinute, MillisPerDay) / MillisPerMinute
    // web formatRowClock: the person's 12h / 24h choice, no leading zero ("9:05", "5:20 PM").
    val clock = formatRaftClock((minuteOfDay / 60).toInt(), (minuteOfDay % 60).toInt(), null, context.timeFormat).removePrefix("0")
    val rowDay =
        when {
            day == today - 1L -> ActivityRowTimeDay.Yesterday
            today - day < 7L -> ActivityRowTimeDay.Weekday(day)
            else -> ActivityRowTimeDay.Date(activityDayLabel(day, today) as ActivityDayLabel.Date)
        }
    return ActivityRowTime.Older(clock, rowDay)
}

/** What the row shows; empty for an older row, which shows its clock only to a hovering pointer. */
@Composable
internal fun ActivityRowTime.shownLabel(): String =
    when (this) {
        ActivityRowTime.Now -> stringResource(Res.string.activity_list_time_now)
        is ActivityRowTime.Minutes -> stringResource(Res.string.activity_list_time_minutes, count)
        is ActivityRowTime.Hours -> stringResource(Res.string.activity_list_time_hours, count)
        is ActivityRowTime.Older -> clock
    }

/** The time in words, for the row's screen-reader name: "2 hours ago", "yesterday at 17:20". */
@Composable
internal fun ActivityRowTime.words(): String =
    when (this) {
        ActivityRowTime.Now -> stringResource(Res.string.activity_list_time_now)
        is ActivityRowTime.Minutes ->
            if (count == 1) {
                stringResource(Res.string.relative_time_minute_past_one)
            } else {
                stringResource(Res.string.relative_time_minute_past_many, count)
            }
        is ActivityRowTime.Hours ->
            if (count == 1) {
                stringResource(Res.string.relative_time_hour_past_one)
            } else {
                stringResource(Res.string.relative_time_hour_past_many, count)
            }
        is ActivityRowTime.Older ->
            when (day) {
                ActivityRowTimeDay.Yesterday -> stringResource(Res.string.activity_list_time_yesterday_at, clock)
                is ActivityRowTimeDay.Weekday ->
                    stringResource(Res.string.activity_list_time_day_at, stringResource(activityWeekdayResource(day.epochDay)), clock)
                is ActivityRowTimeDay.Date -> stringResource(Res.string.activity_list_time_day_at, day.label.localized(), clock)
            }
    }
