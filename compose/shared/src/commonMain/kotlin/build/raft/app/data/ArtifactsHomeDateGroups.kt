package build.raft.app.data

import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.floorDiv
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull

/**
 * Pure grouping & settled-visibility logic for the Artifacts home page (web `artifactHomeDateGroups.ts`,
 * botiverse/slock #16137/#16182/#16290):
 * - Status filter (`unsettled` hides settled except mentioned/newComments; `all` keeps all with
 *   settled dimmed; `settled` keeps only settled). Forced to `all` when `settledEnabled == false`.
 * - Search mode returns a flat list (`forYou` and `dateGroups` empty) while keeping settled filtering.
 * - Otherwise extracts "For you" (`mentioned || newerVersion || newComments > 0`) without duplication,
 *   sorts remaining items by `openedAt ?? updatedAt` (when `byOpened`) else `updatedAt`, and groups:
 *   - List view: always by calendar day (`Today`, `Yesterday`, weekday + short date `< 7d`, `Earlier`).
 *   - Grid view: Monday-based calendar weeks (`This week`, `Last week`, `Earlier`), adaptively
 *     splitting `This week` or `Last week` into calendar days once that week has >= 8 items.
 */
internal const val ARTIFACTS_HOME_ADAPTIVE_WEEK_SPLIT_THRESHOLD: Int = 8

/** Whether [row] is kept under [status] when the settled feature is active. */
internal fun isArtifactVisibleForStatus(
    row: ArtifactsHomeRow,
    status: ArtifactsHomeStatusFilter,
    settledEnabled: Boolean,
): Boolean {
    if (!settledEnabled || status == ArtifactsHomeStatusFilter.All) return true
    val isSettled = row.summary.settled != null
    if (status == ArtifactsHomeStatusFilter.Settled) return isSettled
    // Critical rule (#362): mentioned or unseen-comment items stay visible under "unsettled".
    return !isSettled || row.news.mentioned || row.news.newComments > 0
}

/** Settled items stay in their date groups dimmed when viewing "all". */
internal fun isArtifactDimmed(
    row: ArtifactsHomeRow,
    status: ArtifactsHomeStatusFilter,
    settledEnabled: Boolean,
): Boolean = settledEnabled && status == ArtifactsHomeStatusFilter.All && row.summary.settled != null

/** The timestamp that determines an item's date group and within-group order. */
internal fun artifactDateTimestamp(row: ArtifactsHomeRow, byOpened: Boolean): String? =
    if (byOpened) row.openedAt ?: row.summary.updatedAt else row.summary.updatedAt

internal sealed interface ArtifactsHomeDateGroupLabel {
    data object Today : ArtifactsHomeDateGroupLabel
    data object Yesterday : ArtifactsHomeDateGroupLabel
    data object ThisWeek : ArtifactsHomeDateGroupLabel
    data object LastWeek : ArtifactsHomeDateGroupLabel
    data object Earlier : ArtifactsHomeDateGroupLabel
    data class WeekdayDate(val epochDay: Long) : ArtifactsHomeDateGroupLabel
}

internal data class ArtifactsHomeDateGroup(
    val key: String,
    val label: ArtifactsHomeDateGroupLabel,
    val rows: List<ArtifactsHomeRow>,
)

internal data class ArtifactsHomeGroupsResult(
    val settledEnabled: Boolean,
    val status: ArtifactsHomeStatusFilter,
    val hiddenSettledCount: Int,
    val dimmedSettledCount: Int,
    val visible: List<ArtifactsHomeRow>,
    val forYou: List<ArtifactsHomeRow>,
    val dateGroups: List<ArtifactsHomeDateGroup>,
    val searching: Boolean,
)

/**
 * Monday-based calendar week index (1970-01-01 = day 0 was Thursday, so +3 shifts
 * Monday 1969-12-29 to day 0). Matches web `mondayWeekIndex`.
 */
private fun mondayWeekIndex(dayIdx: Long): Long = floorDiv(dayIdx + 3L, 7L)

private fun localEpochDay(epochMillis: Long, localOffsetMinutes: Int): Long =
    floorDiv(epochMillis + localOffsetMinutes.toLong() * MillisPerMinute, MillisPerDay)

private fun RaftTimeContext.resolvedTodayEpochDayOrNull(): Long? =
    todayEpochDay ?: nowEpochMillis?.let { localEpochDay(it, localOffsetMinutes) }

private data class PlacedArtifactRow(
    val row: ArtifactsHomeRow,
    val dayIdx: Long,
    val daysAgo: Long,
    val weekDiff: Long,
    val valid: Boolean,
)

private fun placeRows(
    rows: List<ArtifactsHomeRow>,
    byOpened: Boolean,
    todayIdx: Long,
    todayWeekIdx: Long,
    localOffsetMinutes: Int,
): List<PlacedArtifactRow> {
    data class Decorated(val row: ArtifactsHomeRow, val index: Int, val ms: Long?)

    val decorated =
        rows.mapIndexed { index, row ->
            val ms = parseRaftTimestampEpochMillisOrNull(artifactDateTimestamp(row, byOpened), localOffsetMinutes)
            Decorated(row, index, ms)
        }.sortedWith { left, right ->
            val lv = left.ms != null
            val rv = right.ms != null
            when {
                lv != rv -> if (lv) -1 else 1
                left.ms != null && right.ms != null && left.ms != right.ms -> right.ms.compareTo(left.ms)
                else -> left.index.compareTo(right.index)
            }
        }
    return decorated.map { entry ->
        val ms = entry.ms
        if (ms == null) {
            PlacedArtifactRow(entry.row, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, valid = false)
        } else {
            val rawIdx = localEpochDay(ms, localOffsetMinutes)
            // Clamp minor client/server clock skew ahead of "now" to today.
            val dayIdx = if (rawIdx > todayIdx) todayIdx else rawIdx
            PlacedArtifactRow(
                row = entry.row,
                dayIdx = dayIdx,
                daysAgo = todayIdx - dayIdx,
                weekDiff = todayWeekIdx - mondayWeekIndex(dayIdx),
                valid = true,
            )
        }
    }
}

private fun dayBucket(
    placed: PlacedArtifactRow,
    includePastSevenDaysInWeek: Boolean = false,
): Pair<String, ArtifactsHomeDateGroupLabel> =
    when {
        !placed.valid -> "earlier" to ArtifactsHomeDateGroupLabel.Earlier
        placed.daysAgo == 0L -> "day:${placed.dayIdx}" to ArtifactsHomeDateGroupLabel.Today
        placed.daysAgo == 1L -> "day:${placed.dayIdx}" to ArtifactsHomeDateGroupLabel.Yesterday
        placed.daysAgo < 7L || includePastSevenDaysInWeek ->
            "day:${placed.dayIdx}" to ArtifactsHomeDateGroupLabel.WeekdayDate(placed.dayIdx)
        else -> "earlier" to ArtifactsHomeDateGroupLabel.Earlier
    }

private fun groupPlacedByDay(
    placedList: List<PlacedArtifactRow>,
    includePastSevenDaysInWeek: Boolean = false,
): List<ArtifactsHomeDateGroup> {
    val groups = LinkedHashMap<String, Pair<ArtifactsHomeDateGroupLabel, MutableList<ArtifactsHomeRow>>>()
    for (placed in placedList) {
        val (key, label) = dayBucket(placed, includePastSevenDaysInWeek)
        val bucket = groups.getOrPut(key) { label to mutableListOf() }
        bucket.second += placed.row
    }
    return groups.entries.map { (key, value) -> ArtifactsHomeDateGroup(key = key, label = value.first, rows = value.second) }
}

internal fun buildArtifactsHomeGroups(
    rows: List<ArtifactsHomeRow>,
    view: ArtifactsHomeView,
    status: ArtifactsHomeStatusFilter,
    searching: Boolean,
    byOpened: Boolean,
    settledEnabled: Boolean,
    timeContext: RaftTimeContext?,
): ArtifactsHomeGroupsResult {
    val effectiveStatus = if (settledEnabled) status else ArtifactsHomeStatusFilter.All
    val visible = rows.filter { isArtifactVisibleForStatus(it, effectiveStatus, settledEnabled) }
    val hiddenSettledCount = if (settledEnabled && effectiveStatus == ArtifactsHomeStatusFilter.Unsettled) rows.size - visible.size else 0
    val dimmedSettledCount =
        if (settledEnabled && effectiveStatus == ArtifactsHomeStatusFilter.All) {
            visible.count { isArtifactDimmed(it, effectiveStatus, settledEnabled) }
        } else {
            0
        }

    if (searching) {
        return ArtifactsHomeGroupsResult(
            settledEnabled = settledEnabled,
            status = effectiveStatus,
            hiddenSettledCount = hiddenSettledCount,
            dimmedSettledCount = dimmedSettledCount,
            visible = visible,
            forYou = emptyList(),
            dateGroups = emptyList(),
            searching = true,
        )
    }

    val forYou = mutableListOf<ArtifactsHomeRow>()
    val rest = mutableListOf<ArtifactsHomeRow>()
    for (row in visible) {
        if (row.news.any) forYou += row else rest += row
    }

    val todayIdx = timeContext?.resolvedTodayEpochDayOrNull()
    if (todayIdx == null) {
        val dateGroups =
            if (rest.isEmpty()) emptyList() else listOf(ArtifactsHomeDateGroup("earlier", ArtifactsHomeDateGroupLabel.Earlier, rest))
        return ArtifactsHomeGroupsResult(
            settledEnabled = settledEnabled,
            status = effectiveStatus,
            hiddenSettledCount = hiddenSettledCount,
            dimmedSettledCount = dimmedSettledCount,
            visible = visible,
            forYou = forYou,
            dateGroups = dateGroups,
            searching = false,
        )
    }

    val todayWeekIdx = mondayWeekIndex(todayIdx)
    val placed = placeRows(rest, byOpened, todayIdx, todayWeekIdx, timeContext.localOffsetMinutes)

    if (view == ArtifactsHomeView.List) {
        return ArtifactsHomeGroupsResult(
            settledEnabled = settledEnabled,
            status = effectiveStatus,
            hiddenSettledCount = hiddenSettledCount,
            dimmedSettledCount = dimmedSettledCount,
            visible = visible,
            forYou = forYou,
            dateGroups = groupPlacedByDay(placed),
            searching = false,
        )
    }

    val thisWeek = mutableListOf<PlacedArtifactRow>()
    val lastWeek = mutableListOf<PlacedArtifactRow>()
    val earlier = mutableListOf<ArtifactsHomeRow>()
    for (entry in placed) {
        when {
            !entry.valid || entry.weekDiff >= 2L -> earlier += entry.row
            entry.weekDiff == 0L -> thisWeek += entry
            else -> lastWeek += entry
        }
    }

    val dateGroups = mutableListOf<ArtifactsHomeDateGroup>()
    when {
        thisWeek.size >= ARTIFACTS_HOME_ADAPTIVE_WEEK_SPLIT_THRESHOLD -> dateGroups += groupPlacedByDay(thisWeek)
        thisWeek.isNotEmpty() ->
            dateGroups += ArtifactsHomeDateGroup("week:this", ArtifactsHomeDateGroupLabel.ThisWeek, thisWeek.map { it.row })
    }
    when {
        lastWeek.size >= ARTIFACTS_HOME_ADAPTIVE_WEEK_SPLIT_THRESHOLD ->
            dateGroups += groupPlacedByDay(lastWeek, includePastSevenDaysInWeek = true)
        lastWeek.isNotEmpty() ->
            dateGroups += ArtifactsHomeDateGroup("week:last", ArtifactsHomeDateGroupLabel.LastWeek, lastWeek.map { it.row })
    }
    if (earlier.isNotEmpty()) {
        dateGroups += ArtifactsHomeDateGroup("earlier", ArtifactsHomeDateGroupLabel.Earlier, earlier)
    }

    return ArtifactsHomeGroupsResult(
        settledEnabled = settledEnabled,
        status = effectiveStatus,
        hiddenSettledCount = hiddenSettledCount,
        dimmedSettledCount = dimmedSettledCount,
        visible = visible,
        forYou = forYou,
        dateGroups = dateGroups,
        searching = false,
    )
}
