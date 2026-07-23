package build.raft.app

import build.raft.app.model.SlockTimeContext
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.model.slockEpochDayForDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlockLiveTimeContextTest {
    @Test
    fun activeLoopCrossesMidnightAndResumeRestartRecalculatesDelay() {
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

        assertFailsWith<StopRefreshLoop> {
            runBlocking {
                runSlockTimeContextRefreshLoop(
                    seed = seed,
                    nowEpochMillis = { listOf(beforeMidnight, afterMidnight)[activeClockIndex] },
                    wait = { delayMillis ->
                        activeDelays += delayMillis
                        if (activeClockIndex == 1) throw StopRefreshLoop()
                        activeClockIndex += 1
                    },
                    onRefresh = activeRefreshes::add
                )
            }
        }

        assertEquals(
            listOf(
                slockEpochDayForDate(2026, 7, 22),
                slockEpochDayForDate(2026, 7, 23)
            ),
            activeRefreshes.map { it?.todayEpochDay }
        )
        assertEquals(listOf(30_000L, 55_000L), activeDelays)

        val resumedRefreshes = mutableListOf<SlockTimeContext?>()
        val resumedDelays = mutableListOf<Long>()
        assertFailsWith<StopRefreshLoop> {
            runBlocking {
                runSlockTimeContextRefreshLoop(
                    seed = seed,
                    nowEpochMillis = { afterMidnight },
                    wait = { delayMillis ->
                        resumedDelays += delayMillis
                        throw StopRefreshLoop()
                    },
                    onRefresh = resumedRefreshes::add
                )
            }
        }

        assertEquals(slockEpochDayForDate(2026, 7, 23), resumedRefreshes.single()?.todayEpochDay)
        assertEquals(listOf(55_000L), resumedDelays)
    }

    private fun epochMillis(value: String): Long =
        parseSlockTimestampEpochMillisOrNull(value)!!

    private class StopRefreshLoop : CancellationException()
}
