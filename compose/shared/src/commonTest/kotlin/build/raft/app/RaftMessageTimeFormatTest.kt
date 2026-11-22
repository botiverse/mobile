package build.raft.app

import build.raft.app.format.RaftFormatLocale
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A message row's time in every UI language, as the web's `formatMessageTime` prints it
 * (`packages/web/src/utils/timeFormatting.ts`, botiverse/slock d7f7f7a2; the expected texts are
 * Chrome 157's output for the same instants, zone and language): the clock today, "Yesterday" and
 * the clock, this year's 2-digit month and day in the language's order and separators, the year
 * before that.
 */
class RaftMessageTimeFormatTest {
    // 2026-06-22 in Shanghai (UTC+8); every time below is read there.
    private val context = RaftTimeContext(localOffsetMinutes = 8 * 60, todayEpochDay = raftEpochDayForDate(2026, 6, 22))

    private val instants =
        listOf(
            "2026-06-22T02:30:00Z", // today, 10:30
            "2026-06-21T01:07:00Z", // yesterday, 09:07
            "2026-03-04T10:05:00Z", // this year, March 4, 18:05
            "2025-11-09T01:07:00Z", // another year, November 9 2025, 09:07
        )

    /** Each UI language, the web catalog's `message.dateDivider.yesterday`, and the four rows (24-hour clock). */
    private val table =
        listOf(
            Triple(RaftFormatLocale.English, "Yesterday", listOf("10:30", "Yesterday 09:07", "03/04 18:05", "11/09/2025 09:07")),
            Triple(RaftFormatLocale.SimplifiedChinese, "昨天", listOf("10:30", "昨天 09:07", "03/04 18:05", "2025/11/09 09:07")),
            Triple(RaftFormatLocale.Japanese, "昨日", listOf("10:30", "昨日 09:07", "03/04 18:05", "2025/11/09 09:07")),
            Triple(RaftFormatLocale.TraditionalChinese, "昨天", listOf("10:30", "昨天 09:07", "03/04 18:05", "2025/11/09 09:07")),
            Triple(RaftFormatLocale.Korean, "어제", listOf("10:30", "어제 09:07", "03. 04. 18:05", "2025. 11. 09. 09:07")),
            Triple(RaftFormatLocale.Spanish, "Ayer", listOf("10:30", "Ayer 09:07", "4/3 18:05", "09/11/2025 09:07")),
            Triple(RaftFormatLocale.BrazilianPortuguese, "Ontem", listOf("10:30", "Ontem 09:07", "04/03 18:05", "09/11/2025 09:07")),
            Triple(RaftFormatLocale.Russian, "Вчера", listOf("10:30", "Вчера 09:07", "04.03 18:05", "09.11.2025 09:07")),
        )

    @Test
    fun everyUiLanguageWritesTheDayAsTheWebDoes() {
        assertEquals(RaftFormatLocale.entries.toSet(), table.map { it.first }.toSet(), "a row per UI language")
        for ((locale, yesterday, expected) in table) {
            val format = RaftMessageTimeFormat(locale, yesterday)
            assertEquals(expected, instants.map { format.format(it, context) }, locale.languageTag)
        }
    }

    @Test
    fun theClockFollowsTheTwelveHourChoiceAndTheDateDoesNot() {
        val twelveHour = context.copy(timeFormat = RaftTimeFormat12Hour)
        assertEquals(
            listOf("10:30 AM", "Yesterday 09:07 AM", "03/04 06:05 PM", "11/09/2025 09:07 AM"),
            instants.map { RaftEnglishMessageTime.format(it, twelveHour) },
        )
    }

    @Test
    fun theDayIsThePersonsOwnNotUtcs() {
        // 2026-06-21T16:30Z is already 00:30 on June 22 in Shanghai; 15:59Z is still the 21st there.
        assertEquals("00:30", RaftEnglishMessageTime.format("2026-06-21T16:30:00Z", context))
        assertEquals("Yesterday 23:59", RaftEnglishMessageTime.format("2026-06-21T15:59:00Z", context))
        // Yesterday wins over the year: on January 1, December 31 is "Yesterday", the 30th has its year.
        val newYearsDay = context.copy(todayEpochDay = raftEpochDayForDate(2026, 1, 1))
        assertEquals("Yesterday 23:59", RaftEnglishMessageTime.format("2025-12-31T15:59:00Z", newYearsDay))
        assertEquals("12/30/2025 23:59", RaftEnglishMessageTime.format("2025-12-30T15:59:00Z", newYearsDay))
    }

    @Test
    fun noTimeWithoutATimestampOrAToday() {
        assertNull(RaftEnglishMessageTime.format("12:51", context))
        assertNull(RaftEnglishMessageTime.format(null, context))
        assertNull(RaftEnglishMessageTime.format("2026-06-22T02:30:00Z", RaftTimeContext(localOffsetMinutes = 8 * 60)))
    }
}
