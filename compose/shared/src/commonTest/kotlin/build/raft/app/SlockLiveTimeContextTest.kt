package build.raft.app

import build.raft.app.model.SlockTimeContext
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.model.slockEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockLiveTimeContextTest {
    @Test
    fun nativeTimerControllerCrossesMidnightAndResumeRestartRecalculatesDelay() {
        val beforeMidnight = epochMillis("2026-07-22T23:59:30+08:00")
        val afterMidnight = epochMillis("2026-07-23T00:00:05+08:00")
        val seed =
            SlockTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 7, 22),
                nowEpochMillis = beforeMidnight
            )
        val activeRefreshes = mutableListOf<SlockTimeContext?>()
        val activeDelays = mutableListOf<Long>()
        var activeClockIndex = 0
        var activeCallback: (() -> Unit)? = null
        val clearedTimeouts = mutableListOf<String>()
        val activeController =
            SlockTimeContextRefreshController(
                seed = seed,
                nowEpochMillis = { listOf(beforeMidnight, afterMidnight)[activeClockIndex] },
                scheduleTimeout = { delayMillis, callback ->
                    activeDelays += delayMillis.toLong()
                    activeCallback = callback
                    "active-${activeDelays.size}"
                },
                clearTimeout = clearedTimeouts::add,
                onRefresh = activeRefreshes::add
            )

        activeController.start()
        activeController.start()
        assertEquals(1, activeRefreshes.size)
        assertEquals(listOf(30_000L), activeDelays)
        activeClockIndex = 1
        activeCallback!!.invoke()

        assertEquals(
            listOf(
                slockEpochDayForDate(2026, 7, 22),
                slockEpochDayForDate(2026, 7, 23)
            ),
            activeRefreshes.map { it?.todayEpochDay }
        )
        assertEquals(listOf(30_000L, 55_000L), activeDelays)
        activeController.stop()
        assertEquals(listOf("active-2"), clearedTimeouts)
        activeCallback!!.invoke()
        assertEquals(2, activeRefreshes.size)
        assertEquals(listOf(30_000L, 55_000L), activeDelays)

        val resumedRefreshes = mutableListOf<SlockTimeContext?>()
        val resumedDelays = mutableListOf<Long>()
        val resumedController =
            SlockTimeContextRefreshController(
                seed = seed,
                nowEpochMillis = { afterMidnight },
                scheduleTimeout = { delayMillis, _ ->
                    resumedDelays += delayMillis.toLong()
                    "resumed-1"
                },
                clearTimeout = {},
                onRefresh = resumedRefreshes::add
            )
        resumedController.start()

        assertEquals(slockEpochDayForDate(2026, 7, 23), resumedRefreshes.single()?.todayEpochDay)
        assertEquals(listOf(55_000L), resumedDelays)
    }

    private fun epochMillis(value: String): Long =
        parseSlockTimestampEpochMillisOrNull(value)!!
}
