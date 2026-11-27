package build.raft.app

import build.raft.app.resources.*
import build.raft.app.settings.observe
import build.raft.app.thread.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.format.RaftFormatLocale
import build.raft.app.format.numericDate
import build.raft.app.localized
import build.raft.app.members.localized
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.RaftRelativeTimeUnit
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.floorDiv
import build.raft.app.model.formatRaftClock
import build.raft.app.model.formatRaftLocalClock
import build.raft.app.model.formatRaftTimestampEpochMillis
import build.raft.app.model.formatRaftTimestampLabel
import build.raft.app.model.normalizeRaftTimeFormatPreference
import build.raft.app.data.ProcessRaftDatetimeFoundationOwner
import build.raft.app.data.RaftDatetimeFoundationOwner
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.data.RaftTimeZoneRuntime
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.offsetMinutesAt
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.resolveRaftRelativeTimeLabel
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.compose.resources.StringResource
import build.raft.designsystem.components.raftClickable

internal enum class RaftTimestampDisplayMode {
    Relative,
    Absolute
}

internal enum class RaftTimestampDisplayGroup {
    Aggregate,
    Conversation
}

internal class RaftTimestampDisplayState(
    initialAggregateMode: RaftTimestampDisplayMode = RaftTimestampDisplayMode.Relative,
    initialConversationMode: RaftTimestampDisplayMode = RaftTimestampDisplayMode.Absolute
) {
    private var aggregateMode by mutableStateOf(initialAggregateMode)
    private var conversationMode by mutableStateOf(initialConversationMode)

    fun displayMode(group: RaftTimestampDisplayGroup): RaftTimestampDisplayMode =
        when (group) {
            RaftTimestampDisplayGroup.Aggregate -> aggregateMode
            RaftTimestampDisplayGroup.Conversation -> conversationMode
        }

    private fun updateDisplayMode(
        group: RaftTimestampDisplayGroup,
        mode: RaftTimestampDisplayMode
    ) {
        when (group) {
            RaftTimestampDisplayGroup.Aggregate -> aggregateMode = mode
            RaftTimestampDisplayGroup.Conversation -> conversationMode = mode
        }
    }

    fun toggle(
        group: RaftTimestampDisplayGroup,
        value: String?,
        timeContext: RaftTimeContext?
    ): RaftTimestampDisplayMode {
        val nextMode = nextRaftTimestampDisplayMode(displayMode(group), value, timeContext)
        if (raftTimestampToggleAvailable(value, timeContext)) {
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
internal class RaftTimestampDisplayViewModel : ViewModel() {
    val state = RaftTimestampDisplayState()
}

internal fun raftTimestampDisplayViewModelKey(pagerId: String): String =
    "timestamp-display-${pagerId.ifBlank { "default" }}"

@Composable
internal fun retainedRaftTimestampDisplayState(pagerId: String): RaftTimestampDisplayState =
    viewModel(key = raftTimestampDisplayViewModelKey(pagerId)) {
        RaftTimestampDisplayViewModel()
    }.state

internal val LocalRaftTimestampDisplayState =
    compositionLocalOf<RaftTimestampDisplayState?> { null }

@Composable
internal fun RaftTimestampDisplayScope(
    state: RaftTimestampDisplayState = remember { RaftTimestampDisplayState() },
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalRaftTimestampDisplayState provides state, content = content)
}

@Composable
internal fun raftRelativeTimestampLabel(
    value: String?,
    timeContext: RaftTimeContext? = null,
    clampFuture: Boolean = false,
): String {
    val effectiveContext = currentRaftTimeContext(timeContext)
    val resolved = resolveRaftRelativeTimeLabel(value, effectiveContext)
    return when (val label = if (clampFuture) resolved.clampedFutureToNow() else resolved) {
        RaftRelativeTimeLabel.Empty -> ""
        RaftRelativeTimeLabel.Unknown -> {
            val fmt = if (effectiveContext != null) effectiveContext.timeFormat else RaftTimeFormatRuntime.observe()
            raftDeviceZoneAbsoluteLabel(value, timeFormat = fmt)
                ?: stringResource(Res.string.relative_time_unknown)
        }
        is RaftRelativeTimeLabel.Literal -> label.value
        is RaftRelativeTimeLabel.Relative -> label.localized()
    }
}

/**
 * Compact relative time (web `formatShortRelativeTime`, e.g. `"now"`, `"5m ago"`, `"2h ago"`,
 * `"9d ago"`), clamped so slight client clock skew behind the server reads as `"now"` rather than
 * a future label.
 */
@Composable
internal fun raftShortRelativeTimestampLabel(
    value: String?,
    timeContext: RaftTimeContext? = null,
): String {
    val effectiveContext = currentRaftTimeContext(timeContext)
    val resolved = resolveRaftRelativeTimeLabel(value, effectiveContext).clampedFutureToNow()
    return when (resolved) {
        RaftRelativeTimeLabel.Empty -> ""
        RaftRelativeTimeLabel.Unknown -> {
            val fmt = if (effectiveContext != null) effectiveContext.timeFormat else RaftTimeFormatRuntime.observe()
            raftDeviceZoneAbsoluteLabel(value, timeFormat = fmt)
                ?: stringResource(Res.string.relative_time_unknown)
        }
        is RaftRelativeTimeLabel.Literal -> resolved.value
        is RaftRelativeTimeLabel.Relative -> resolved.localizedShort()
    }
}

@Composable
private fun RaftRelativeTimeLabel.Relative.localizedShort(): String {
    val count = absoluteValue()
    if (count == 0) return stringResource(Res.string.relative_time_short_now)
    return when (unit) {
        RaftRelativeTimeUnit.Minute -> stringResource(Res.string.relative_time_short_minute_past, count)
        RaftRelativeTimeUnit.Hour -> stringResource(Res.string.relative_time_short_hour_past, count)
        RaftRelativeTimeUnit.Day ->
            if (count == 1) stringResource(Res.string.relative_time_short_day_past_one)
            else stringResource(Res.string.relative_time_short_day_past_many, count)
    }
}

/**
 * A readable time with no time context: the absolute local time in the device zone ("14:05",
 * "11-06 12:10"), never "unknown time" for a timestamp that parses (artin 11-05). Every page has
 * a base context now (BasePager), so this is only the last fallback; null when [value] is not a
 * timestamp.
 */
internal fun raftDeviceZoneAbsoluteLabel(
    value: String?,
    nowEpochMillis: Long = defaultNowMillis(),
    foundationOwner: RaftDatetimeFoundationOwner = ProcessRaftDatetimeFoundationOwner,
    timeFormat: String = RaftTimeFormat24Hour,
): String? {
    val offset = foundationOwner.localOffsetMinutesAt(nowEpochMillis, 0)
    val epochMillis = parseRaftTimestampEpochMillisOrNull(value, offset) ?: return null
    val todayEpochDay = floorDiv(nowEpochMillis + offset.toLong() * MillisPerMinute, MillisPerDay)
    return formatRaftTimestampEpochMillis(epochMillis, offset, todayEpochDay, timeFormat)
}

internal fun raftDeviceZoneAbsoluteLabel(
    value: String?,
    timeContext: RaftTimeContext?,
    nowEpochMillis: Long = timeContext?.nowEpochMillis ?: defaultNowMillis(),
    foundationOwner: RaftDatetimeFoundationOwner = ProcessRaftDatetimeFoundationOwner,
): String? =
    raftDeviceZoneAbsoluteLabel(
        value = value,
        nowEpochMillis = nowEpochMillis,
        foundationOwner = foundationOwner,
        timeFormat = timeContext?.timeFormat ?: RaftTimeFormat24Hour,
    )

/**
 * Shared interactive timestamp used by product surfaces that lead with relative time.
 *
 * A parseable timestamp with a live local time context can toggle its window-level display group.
 * Aggregate surfaces default to relative time; conversation surfaces default to the absolute time a
 * message row shows ([RaftMessageTimeFormat]). Every timestamp in the same group and active
 * activity/window observes the change.
 * A newly created window restores both defaults, and the modes are not persisted across restarts.
 */
@Composable
internal fun RaftRelativeTimestampText(
    value: String?,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext? = null,
    displayGroup: RaftTimestampDisplayGroup = RaftTimestampDisplayGroup.Aggregate,
    style: TextStyle,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
    toggleEnabled: Boolean = true,
    clampFuture: Boolean = false,
    onToggle: (RaftTimestampDisplayMode) -> Unit = {}
) {
    val effectiveContext = currentRaftTimeContext(timeContext)
    val toggleAvailable = raftTimestampToggleAvailable(value, effectiveContext, toggleEnabled)
    val displayState = LocalRaftTimestampDisplayState.current ?: remember { RaftTimestampDisplayState() }
    val resolvedMode =
        resolvedRaftTimestampDisplayMode(displayState.displayMode(displayGroup), value, effectiveContext)
    val relativeLabel = raftRelativeTimestampLabel(value, effectiveContext, clampFuture)
    val absoluteLabel = raftMessageTimeLabel(value, effectiveContext, rememberRaftMessageTimeFormat())
    val displayLabel =
        if (resolvedMode == RaftTimestampDisplayMode.Absolute) {
            absoluteLabel ?: relativeLabel
        } else {
            relativeLabel
        }
    val onClickLabel =
        if (resolvedMode == RaftTimestampDisplayMode.Relative) {
            stringResource(Res.string.relative_time_toggle_show_absolute)
        } else {
            stringResource(Res.string.relative_time_toggle_show_relative)
        }
    val modeDescription =
        if (resolvedMode == RaftTimestampDisplayMode.Relative) {
            stringResource(Res.string.relative_time_toggle_state_relative)
        } else {
            stringResource(Res.string.relative_time_toggle_state_absolute)
        }
    val interactiveModifier =
        if (toggleAvailable) {
            modifier
                .raftClickable(
                    onClick = {
                        val nextMode = displayState.toggle(displayGroup, value, effectiveContext)
                        onToggle(nextMode)
                    },
                    onClickLabel = onClickLabel,
                )
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

internal fun raftTimestampToggleAvailable(
    value: String?,
    timeContext: RaftTimeContext?,
    toggleEnabled: Boolean = true,
): Boolean =
    toggleEnabled &&
        timeContext?.nowEpochMillis != null &&
        parseRaftTimestampEpochMillisOrNull(value, timeContext.localOffsetMinutes) != null

internal fun raftAbsoluteTimestampLabel(
    value: String?,
    timeContext: RaftTimeContext?
): String? =
    if (raftTimestampToggleAvailable(value, timeContext)) {
        formatRaftTimestampLabel(value, timeContext).takeIf { it.isNotBlank() }
    } else {
        null
    }

/**
 * A message's time as its row shows it, the web's `formatMessageTime`
 * (`packages/web/src/utils/timeFormatting.ts`, botiverse/slock d7f7f7a2): the clock today,
 * [yesterdayLabel] and the clock yesterday, else the day in [locale]'s digits ([numericDate]:
 * `06/22` in English, `22/06` in Portuguese), with the year when it is not this year's, then the
 * clock. The clock follows the person's 12h/24h choice and the day their time zone (the context's
 * offset). Get one for the UI language with [rememberRaftMessageTimeFormat].
 */
@Immutable
internal data class RaftMessageTimeFormat(
    val locale: RaftFormatLocale,
    val yesterdayLabel: String,
) {
    /** [value] (a wire timestamp) in [timeContext]; null when it is not a timestamp or there is no today. */
    fun format(value: String?, timeContext: RaftTimeContext?): String? {
        val context = timeContext ?: return null
        val epochMillis = parseRaftTimestampEpochMillisOrNull(value, context.localOffsetMinutes) ?: return null
        return formatEpochMillis(epochMillis, context)
    }

    /** [epochMillis] (an instant in epoch milliseconds) in [timeContext]; null when there is no today. */
    fun formatEpochMillis(epochMillis: Long, timeContext: RaftTimeContext?): String? {
        val context = timeContext ?: return null
        val today = context.resolvedTodayEpochDay() ?: return null
        val day = context.localEpochDay(epochMillis)
        val clock = context.formatClock(epochMillis)
        if (day == today) return clock
        if (day == today - 1) return "$yesterdayLabel $clock"
        val date = civilDateFromEpochDay(day)
        val withYear = date.year != civilDateFromEpochDay(today).year
        return "${locale.numericDate(date.year, date.month, date.day, withYear)} $clock"
    }
}

/** The [RaftMessageTimeFormat] of the UI language; a language switch recomposes the caller with the new one. */
@Composable
internal fun rememberRaftMessageTimeFormat(): RaftMessageTimeFormat {
    val yesterdayLabel = stringResource(Res.string.thread_message_time_yesterday)
    val localeTag = rememberRaftUiLocaleTag()
    return remember(localeTag, yesterdayLabel) {
        RaftMessageTimeFormat(RaftFormatLocale.forLanguageTag(localeTag), yesterdayLabel)
    }
}

/** A timestamp's absolute label in a [RaftRelativeTimestampText]: [messageTime] when the timestamp can toggle, else null. */
internal fun raftMessageTimeLabel(
    value: String?,
    timeContext: RaftTimeContext?,
    messageTime: RaftMessageTimeFormat,
): String? =
    if (raftTimestampToggleAvailable(value, timeContext)) {
        messageTime.format(value, timeContext)?.takeIf { it.isNotBlank() }
    } else {
        null
    }

internal fun resolvedRaftTimestampDisplayMode(
    preferred: RaftTimestampDisplayMode,
    value: String?,
    timeContext: RaftTimeContext?
): RaftTimestampDisplayMode =
    if (raftTimestampToggleAvailable(value, timeContext)) {
        preferred
    } else {
        RaftTimestampDisplayMode.Relative
    }

internal fun nextRaftTimestampDisplayMode(
    current: RaftTimestampDisplayMode,
    value: String?,
    timeContext: RaftTimeContext?
): RaftTimestampDisplayMode {
    if (!raftTimestampToggleAvailable(value, timeContext)) {
        return RaftTimestampDisplayMode.Relative
    }
    return when (current) {
        RaftTimestampDisplayMode.Relative -> RaftTimestampDisplayMode.Absolute
        RaftTimestampDisplayMode.Absolute -> RaftTimestampDisplayMode.Relative
    }
}

@Composable
internal fun currentRaftTimeContext(timeContext: RaftTimeContext?): RaftTimeContext? =
    timeContext?.takeIf { it.nowEpochMillis != null }
        ?: currentRaftTimeContextProvider?.current?.let { live ->
            if (timeContext != null) live.copy(timeFormat = timeContext.timeFormat) else live
        }

/**
 * Resolves a date/clock formatting [RaftTimeContext] from an optional caller-supplied [timeContext]
 * and the enclosing [LocalRaftTimeContextProvider] / [LocalRaftPageBaseTimeContextProvider] DI.
 *
 * Subscribes only to the provider's low-frequency [RaftTimeContextProvider.dateContext] (changes on
 * midnight, timezone/DST shift, or 12h/24h preference change), so absolute timestamps never
 * recompose on 1-minute ticks while keeping `(localOffsetMinutes, todayEpochDay)` consistently paired.
 */
@Composable
internal fun currentRaftDateTimeContext(timeContext: RaftTimeContext?): RaftTimeContext? {
    if (timeContext != null && (timeContext.todayEpochDay != null || timeContext.nowEpochMillis != null)) {
        return timeContext
    }
    val liveDate = currentRaftTimeContextProvider?.dateContext ?: return timeContext
    if (timeContext == null) return liveDate
    return if (liveDate.localOffsetMinutes == timeContext.localOffsetMinutes && liveDate.todayEpochDay != null) {
        timeContext.copy(todayEpochDay = liveDate.todayEpochDay)
    } else {
        liveDate.copy(timeFormat = timeContext.timeFormat)
    }
}

/**
 * Fallback date/clock [RaftTimeContext] in the current device zone when no CompositionLocal time
 * provider is mounted (e.g., isolated Compose UI tests outside `BasePager`).
 */
internal fun raftFallbackDateTimeContext(
    timeFormat: String = RaftTimeFormat24Hour,
    nowEpochMillis: Long = defaultNowMillis(),
    foundationOwner: RaftDatetimeFoundationOwner = ProcessRaftDatetimeFoundationOwner,
): RaftTimeContext {
    val offset = foundationOwner.localOffsetMinutesAt(nowEpochMillis, 0)
    val todayEpochDay = floorDiv(nowEpochMillis + offset.toLong() * MillisPerMinute, MillisPerDay)
    return RaftTimeContext(
        localOffsetMinutes = offset,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = null,
        timeFormat = normalizeRaftTimeFormatPreference(timeFormat),
        timeZoneId = foundationOwner.effectiveTimeZoneIdOrNull(),
    )
}

/**
 * Non-null low-frequency date/clock [RaftTimeContext]: prefers the enclosing DI provider's
 * `dateContext` (never recomposing on 1-minute ticks), falling back to the effective viewer zone
 * with the reactive 12h/24h and timezone preferences when composed outside `BasePager`.
 */
@Composable
internal fun currentRaftEffectiveDateTimeContext(timeContext: RaftTimeContext? = null): RaftTimeContext {
    val resolved = currentRaftDateTimeContext(timeContext)
    if (resolved != null) return resolved
    val runtimeFormat = RaftTimeFormatRuntime.observe()
    val runtimeTimeZone = RaftTimeZoneRuntime.observe()
    return remember(runtimeFormat, runtimeTimeZone) { raftFallbackDateTimeContext(timeFormat = runtimeFormat) }
}

/** Local clock formatter (`HH:mm` or `hh:mm AM/PM`) honoring the enclosing [RaftTimeContext]. */
@Composable
internal fun rememberRaftLocalClockFormatter(timeContext: RaftTimeContext? = null): (Long) -> String {
    val context = currentRaftEffectiveDateTimeContext(timeContext)
    return remember(context) { { epochMillis -> context.formatClock(epochMillis) } }
}

/** ISO timestamp -> local clock formatter (`HH:mm` or `hh:mm AM/PM`), falling back to raw text if unreadable. */
@Composable
internal fun rememberRaftIsoClockFormatter(timeContext: RaftTimeContext? = null): (String) -> String {
    val context = currentRaftEffectiveDateTimeContext(timeContext)
    return remember(context) { { iso -> context.formatClock(iso) ?: iso } }
}

private val RaftShortMonthResources =
    listOf(
        Res.string.task_head_month_1, Res.string.task_head_month_2, Res.string.task_head_month_3,
        Res.string.task_head_month_4, Res.string.task_head_month_5, Res.string.task_head_month_6,
        Res.string.task_head_month_7, Res.string.task_head_month_8, Res.string.task_head_month_9,
        Res.string.task_head_month_10, Res.string.task_head_month_11, Res.string.task_head_month_12,
    )

/** Localized short month label (`1..12`), shared across compact dates, short date-times, and task heads. */
@Composable
internal fun raftLocalizedShortMonth(month: Int): String =
    RaftShortMonthResources.getOrNull(month - 1)?.let { stringResource(it) } ?: month.toString()

/**
 * Localized short month/day + clock (web `formatShortDateTime`, e.g., `"Nov 5, 14:30"` / `"11月5日 14:30"`)
 * in the viewer's [RaftTimeContext]; returns `""` when blank and falls back to the trimmed raw text when unparseable.
 */
@Composable
internal fun raftLocalizedShortDateTime(iso: String?, timeContext: RaftTimeContext? = null): String {
    val trimmed = iso?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val context = currentRaftEffectiveDateTimeContext(timeContext)
    val parts = taskHeadDateTime(trimmed, context) ?: return trimmed
    val monthDay = stringResource(Res.string.settings_compact_month_day, raftLocalizedShortMonth(parts.month), parts.day)
    val clock = formatRaftClock(parts.hour, parts.minute, null, context.timeFormat)
    return stringResource(Res.string.raft_short_date_time, monthDay, clock)
}

/**
 * Localized short month/day + clock for an epoch-millis instant (web `formatShortDateTime`, e.g.,
 * `"Nov 21, 11:23"` / `"11月21日 11:23"`) in the viewer's [RaftTimeContext] (respecting account
 * `preferredTimezone` and per-instant DST offset when `timeZoneId` is set).
 */
@Composable
internal fun raftLocalizedShortDateTime(epochMillis: Long, timeContext: RaftTimeContext? = null): String {
    val context = currentRaftEffectiveDateTimeContext(timeContext)
    val offsetMinutes = context.offsetMinutesAt(epochMillis)
    val localMillis = epochMillis + offsetMinutes.toLong() * MillisPerMinute
    val date = civilDateFromEpochDay(floorDiv(localMillis, MillisPerDay))
    val monthDay = stringResource(Res.string.settings_compact_month_day, raftLocalizedShortMonth(date.month), date.day)
    val clock = formatRaftLocalClock(epochMillis, offsetMinutes, context.timeFormat)
    return stringResource(Res.string.raft_short_date_time, monthDay, clock)
}

/**
 * Localized medium date + short clock (e.g., `"Nov 17, 2026, 09:14"` or `"Nov 17, 2026, 09:14 AM"`)
 * in the viewer's [RaftTimeContext]; falls back to the raw string when unparseable.
 */
@Composable
internal fun raftLocalizedMediumDateTime(iso: String, timeContext: RaftTimeContext? = null): String {
    val context = currentRaftEffectiveDateTimeContext(timeContext)
    return taskHeadDateTime(iso, context)?.localized(context.timeFormat) ?: iso
}

/**
 * For timestamps of things that already happened (an Activity row's last message): a client
 * clock running behind the server makes them look a little in the future ("in 1 minute").
 * Any future delta reads as "now" instead, in every locale.
 */
internal fun RaftRelativeTimeLabel.clampedFutureToNow(): RaftRelativeTimeLabel =
    if (this is RaftRelativeTimeLabel.Relative && value > 0) {
        RaftRelativeTimeLabel.Relative(0, RaftRelativeTimeUnit.Minute)
    } else {
        this
    }

internal data class RaftRelativeTimeStringSelection(
    val resource: StringResource,
    val formatArg: Int? = null
)

/**
 * Pure resource-selection seam for the relative-time label. [localized] renders
 * exactly this selection; the consumer test drives it directly to lock the
 * singular/many and past/future wiring, so a deleted or mis-wired key turns RED.
 */
internal fun RaftRelativeTimeLabel.Relative.selectLocalizedStringResource(): RaftRelativeTimeStringSelection =
    when (unit) {
        RaftRelativeTimeUnit.Minute ->
            when {
                value == 0 -> RaftRelativeTimeStringSelection(Res.string.thread_timestamp_now)
                value == -1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_minute_past_one)
                value < 0 -> RaftRelativeTimeStringSelection(Res.string.relative_time_minute_past_many, absoluteValue())
                value == 1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_minute_future_one)
                else -> RaftRelativeTimeStringSelection(Res.string.relative_time_minute_future_many, value)
            }
        RaftRelativeTimeUnit.Hour ->
            when {
                value == 0 -> RaftRelativeTimeStringSelection(Res.string.relative_time_hour_now)
                value == -1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_hour_past_one)
                value < 0 -> RaftRelativeTimeStringSelection(Res.string.relative_time_hour_past_many, absoluteValue())
                value == 1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_hour_future_one)
                else -> RaftRelativeTimeStringSelection(Res.string.relative_time_hour_future_many, value)
            }
        RaftRelativeTimeUnit.Day ->
            when (value) {
                -2 -> RaftRelativeTimeStringSelection(Res.string.relative_time_day_before_yesterday)
                -1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_day_yesterday)
                0 -> RaftRelativeTimeStringSelection(Res.string.relative_time_day_today)
                1 -> RaftRelativeTimeStringSelection(Res.string.relative_time_day_tomorrow)
                2 -> RaftRelativeTimeStringSelection(Res.string.relative_time_day_after_tomorrow)
                else ->
                    if (value < 0) {
                        RaftRelativeTimeStringSelection(Res.string.relative_time_day_past_many, absoluteValue())
                    } else {
                        RaftRelativeTimeStringSelection(Res.string.relative_time_day_future_many, value)
                    }
            }
    }

@Composable
internal fun RaftRelativeTimeLabel.Relative.localized(): String {
    val selection = selectLocalizedStringResource()
    val formatArg = selection.formatArg
    return if (formatArg != null) {
        stringResource(selection.resource, formatArg)
    } else {
        stringResource(selection.resource)
    }
}

private fun RaftRelativeTimeLabel.Relative.absoluteValue(): Int =
    if (value == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(value)
