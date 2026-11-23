package build.raft.app.data

import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure KMP grouping tests mirroring web `artifactHomeGroups.behavior.test.tsx` (botiverse/slock
 * #16137, #16290): status filtering, dimmed state, search flat mode, "For you" extraction without
 * duplication, calendar day grouping (list), Monday week grouping (grid), and adaptive >= 8 day
 * splitting for both This week and Last week.
 */
class ArtifactsHomeDateGroupsTest {
    // Anchor: Wednesday 2026-11-18 UTC (todayEpochDay = 2026-11-18).
    // Monday of this week: 2026-11-16; Monday of last week: 2026-11-09.
    private val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 18),
            nowEpochMillis = 1_794_996_000_000L, // 2026-11-18T10:00:00Z
        )

    private val settledInfo =
        ArtifactSettled(
            by = ArtifactsHomeActor("user", "u-1", "remy", "Remy"),
            at = "2026-11-18T09:00:00.000Z",
            version = 2,
        )

    private fun makeRow(
        id: String,
        updatedAt: String = "2026-11-18T08:00:00.000Z",
        openedAt: String? = null,
        settled: ArtifactSettled? = null,
        settledShown: Boolean = true,
        news: ArtifactsHomeNews = ArtifactsHomeNews(),
    ): ArtifactsHomeRow =
        ArtifactsHomeRow(
            summary =
                ArtifactSummary(
                    id = id,
                    title = id,
                    description = null,
                    kind = ArtifactKind.Html,
                    version = 2,
                    pinned = false,
                    latestVersion = 2,
                    home = ArtifactHome.Server,
                    updatedAt = updatedAt,
                    settled = settled,
                    settledShown = settledShown,
                ),
            author = ArtifactsHomeActor("user", "u-1", "kirby", "Kirby"),
            openedAt = openedAt,
            news = news,
        )

    @Test
    fun unsettledHidesPlainSettledButKeepsMentionedAndNewCommentsAndCountsHidden() {
        val working = makeRow("working")
        val plainSettled = makeRow("settled-plain", settled = settledInfo)
        val mentionedSettled = makeRow("settled-mentioned", settled = settledInfo, news = ArtifactsHomeNews(mentioned = true))
        val commentedSettled = makeRow("settled-commented", settled = settledInfo, news = ArtifactsHomeNews(newComments = 2))
        val rows = listOf(working, plainSettled, mentionedSettled, commentedSettled)

        val unsettled =
            buildArtifactsHomeGroups(
                rows = rows,
                view = ArtifactsHomeView.Grid,
                status = ArtifactsHomeStatusFilter.Unsettled,
                searching = false,
                byOpened = false,
                settledEnabled = true,
                timeContext = timeContext,
            )
        assertEquals(listOf("working", "settled-mentioned", "settled-commented"), unsettled.visible.map { it.summary.id })
        assertEquals(1, unsettled.hiddenSettledCount)
        assertEquals(0, unsettled.dimmedSettledCount)
        // Mentioned and commented settled rows go into "For you" and are NOT duplicated in dateGroups.
        assertEquals(listOf("settled-mentioned", "settled-commented"), unsettled.forYou.map { it.summary.id })
        assertEquals(listOf("working"), unsettled.dateGroups.flatMap { it.rows }.map { it.summary.id })

        val all =
            buildArtifactsHomeGroups(
                rows = rows,
                view = ArtifactsHomeView.Grid,
                status = ArtifactsHomeStatusFilter.All,
                searching = false,
                byOpened = false,
                settledEnabled = true,
                timeContext = timeContext,
            )
        assertEquals(4, all.visible.size)
        assertEquals(0, all.hiddenSettledCount)
        assertEquals(3, all.dimmedSettledCount)
        assertTrue(isArtifactDimmed(plainSettled, ArtifactsHomeStatusFilter.All, settledEnabled = true))
        assertFalse(isArtifactDimmed(working, ArtifactsHomeStatusFilter.All, settledEnabled = true))
        assertFalse(isArtifactDimmed(plainSettled, ArtifactsHomeStatusFilter.Settled, settledEnabled = true))

        val onlySettled =
            buildArtifactsHomeGroups(
                rows = rows,
                view = ArtifactsHomeView.Grid,
                status = ArtifactsHomeStatusFilter.Settled,
                searching = false,
                byOpened = false,
                settledEnabled = true,
                timeContext = timeContext,
            )
        assertEquals(listOf("settled-plain", "settled-mentioned", "settled-commented"), onlySettled.visible.map { it.summary.id })
        assertEquals(0, onlySettled.hiddenSettledCount)
        assertEquals(0, onlySettled.dimmedSettledCount)
    }

    @Test
    fun settledDisabledForcesEffectiveStatusToAllAndDisablesDimming() {
        val plainSettled = makeRow("settled", settled = settledInfo, settledShown = false)
        val grouped =
            buildArtifactsHomeGroups(
                rows = listOf(plainSettled),
                view = ArtifactsHomeView.List,
                status = ArtifactsHomeStatusFilter.Unsettled,
                searching = false,
                byOpened = false,
                settledEnabled = false,
                timeContext = timeContext,
            )
        assertEquals(ArtifactsHomeStatusFilter.All, grouped.status)
        assertEquals(listOf("settled"), grouped.visible.map { it.summary.id })
        assertEquals(0, grouped.hiddenSettledCount)
        assertEquals(0, grouped.dimmedSettledCount)
        assertFalse(isArtifactDimmed(plainSettled, ArtifactsHomeStatusFilter.All, settledEnabled = false))
    }

    @Test
    fun searchingFlattensGroupsWhileStillApplyingStatusFilter() {
        val working = makeRow("w", news = ArtifactsHomeNews(mentioned = true))
        val plainSettled = makeRow("s", settled = settledInfo)
        val grouped =
            buildArtifactsHomeGroups(
                rows = listOf(working, plainSettled),
                view = ArtifactsHomeView.Grid,
                status = ArtifactsHomeStatusFilter.Unsettled,
                searching = true,
                byOpened = false,
                settledEnabled = true,
                timeContext = timeContext,
            )
        assertTrue(grouped.searching)
        assertEquals(listOf("w"), grouped.visible.map { it.summary.id })
        assertTrue(grouped.forYou.isEmpty())
        assertTrue(grouped.dateGroups.isEmpty())
        assertEquals(1, grouped.hiddenSettledCount)
    }

    @Test
    fun listViewGroupsByCalendarDayWithWeekdayWithinSevenDaysAndFutureClampedToToday() {
        val future = makeRow("future", updatedAt = "2026-11-19T02:00:00.000Z")
        val today = makeRow("today", updatedAt = "2026-11-18T05:00:00.000Z")
        val yesterday = makeRow("yesterday", updatedAt = "2026-11-17T22:00:00.000Z")
        val monday = makeRow("mon", updatedAt = "2026-11-16T12:00:00.000Z") // 2 days ago -> WeekdayDate
        val sixDaysAgo = makeRow("thu", updatedAt = "2026-11-12T12:00:00.000Z") // 6 days ago -> WeekdayDate
        val sevenDaysAgo = makeRow("old", updatedAt = "2026-11-11T12:00:00.000Z") // 7 days ago -> Earlier

        val grouped =
            buildArtifactsHomeGroups(
                rows = listOf(future, today, yesterday, monday, sixDaysAgo, sevenDaysAgo),
                view = ArtifactsHomeView.List,
                status = ArtifactsHomeStatusFilter.All,
                searching = false,
                byOpened = false,
                settledEnabled = false,
                timeContext = timeContext,
            )
        assertEquals(
            listOf(
                ArtifactsHomeDateGroupLabel.Today to listOf("future", "today"),
                ArtifactsHomeDateGroupLabel.Yesterday to listOf("yesterday"),
                ArtifactsHomeDateGroupLabel.WeekdayDate(raftEpochDayForDate(2026, 11, 16)) to listOf("mon"),
                ArtifactsHomeDateGroupLabel.WeekdayDate(raftEpochDayForDate(2026, 11, 12)) to listOf("thu"),
                ArtifactsHomeDateGroupLabel.Earlier to listOf("old"),
            ),
            grouped.dateGroups.map { it.label to it.rows.map { r -> r.summary.id } },
        )
    }

    @Test
    fun gridViewGroupsByWeekBelowThresholdAndAdaptivelySplitsAtEightOrMore() {
        // Below 8 in this week -> single ThisWeek group.
        val quiet =
            listOf(
                makeRow("w1", updatedAt = "2026-11-18T08:00:00.000Z"),
                makeRow("w2", updatedAt = "2026-11-17T08:00:00.000Z"),
                makeRow("l1", updatedAt = "2026-11-13T08:00:00.000Z"),
                makeRow("e1", updatedAt = "2026-11-01T08:00:00.000Z"),
            )
        val quietGrouped =
            buildArtifactsHomeGroups(
                rows = quiet,
                view = ArtifactsHomeView.Grid,
                status = ArtifactsHomeStatusFilter.All,
                searching = false,
                byOpened = false,
                settledEnabled = false,
                timeContext = timeContext,
            )
        assertEquals(
            listOf(
                ArtifactsHomeDateGroupLabel.ThisWeek to listOf("w1", "w2"),
                ArtifactsHomeDateGroupLabel.LastWeek to listOf("l1"),
                ArtifactsHomeDateGroupLabel.Earlier to listOf("e1"),
            ),
            quietGrouped.dateGroups.map { it.label to it.rows.map { r -> r.summary.id } },
        )

        // 8 items in This week -> splits into Today / Yesterday / WeekdayDate(Mon).
        // 8 items in Last week -> splits into WeekdayDate(Fri 13) / WeekdayDate(Mon 9) (never collapses into Earlier!).
        val busyThisWeek =
            (1..4).map { makeRow("tw-t-$it", updatedAt = "2026-11-18T0$it:00:00.000Z") } +
                (1..2).map { makeRow("tw-y-$it", updatedAt = "2026-11-17T0$it:00:00.000Z") } +
                (1..2).map { makeRow("tw-m-$it", updatedAt = "2026-11-16T0$it:00:00.000Z") }
        val busyLastWeek =
            (1..4).map { makeRow("lw-f-$it", updatedAt = "2026-11-13T0$it:00:00.000Z") } +
                (1..4).map { makeRow("lw-m-$it", updatedAt = "2026-11-09T0$it:00:00.000Z") }

        val busyGrouped =
            buildArtifactsHomeGroups(
                rows = busyThisWeek + busyLastWeek,
                view = ArtifactsHomeView.Grid,
                status = ArtifactsHomeStatusFilter.All,
                searching = false,
                byOpened = false,
                settledEnabled = false,
                timeContext = timeContext,
            )
        assertEquals(
            listOf(
                ArtifactsHomeDateGroupLabel.Today,
                ArtifactsHomeDateGroupLabel.Yesterday,
                ArtifactsHomeDateGroupLabel.WeekdayDate(raftEpochDayForDate(2026, 11, 16)),
                ArtifactsHomeDateGroupLabel.WeekdayDate(raftEpochDayForDate(2026, 11, 13)),
                ArtifactsHomeDateGroupLabel.WeekdayDate(raftEpochDayForDate(2026, 11, 9)),
            ),
            busyGrouped.dateGroups.map { it.label },
        )
    }

    @Test
    fun byOpenedPrefersOpenedAtTimestampAndFallsBackToUpdatedAt() {
        val rowOpenedToday = makeRow("a", updatedAt = "2026-11-01T08:00:00.000Z", openedAt = "2026-11-18T08:00:00.000Z")
        val rowNeverOpened = makeRow("b", updatedAt = "2026-11-01T08:00:00.000Z", openedAt = null)
        val grouped =
            buildArtifactsHomeGroups(
                rows = listOf(rowOpenedToday, rowNeverOpened),
                view = ArtifactsHomeView.List,
                status = ArtifactsHomeStatusFilter.All,
                searching = false,
                byOpened = true,
                settledEnabled = false,
                timeContext = timeContext,
            )
        assertEquals(
            listOf(
                ArtifactsHomeDateGroupLabel.Today to listOf("a"),
                ArtifactsHomeDateGroupLabel.Earlier to listOf("b"),
            ),
            grouped.dateGroups.map { it.label to it.rows.map { r -> r.summary.id } },
        )
    }
}
