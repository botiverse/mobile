package build.raft.app

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Date/Time Formatting & DI Phase 2B (`dm:@artin:#2`):
 * - Short reminder/app-grant date-time renders localized month/day + clock across all 8 locales
 *   (and respects 12h/24h preference and timezone offset).
 * - Reminder relative time subscribes to the enclosing live [LocalRaftTimeContextProvider] when
 *   passed a low-frequency date-only `timeContext` (`nowEpochMillis == null`), updates when the
 *   provider ticks, and falls back to the localized short date-time when no clock is available.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class MembersReminderDateTimeLocaleUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    @Test
    fun shortReminderDateTimeLocalizesAcrossAllEightLocalesAndRespects12hFormat() =
        runRobolectricComposeUiTest {
            val utc24 = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = 20_397L, timeFormat = RaftTimeFormat24Hour)
            val utc12 = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = 20_397L, timeFormat = RaftTimeFormat12Hour)
            val iso = "2025-11-05T14:30:00Z"

            setContent {
                BasicText(text = iso.membersReminderShortDateTime(utc24), modifier = Modifier.testTag("dt24"))
                BasicText(text = iso.membersReminderShortDateTime(utc12), modifier = Modifier.testTag("dt12"))
            }

            mapOf(
                "en" to "Nov 5, 14:30",
                "zh-CN" to "11月5日 14:30",
                "zh-TW" to "11月5日 14:30",
                "ja" to "11月5日 14:30",
                "ko" to "11월 5일 14:30",
                "es" to "5 nov, 14:30",
                "pt-BR" to "5 de nov, 14:30",
                "ru" to "5 нояб., 14:30",
            ).forEach { (tag, expected24) ->
                StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
                waitForIdle()
                onNodeWithTag("dt24").assertTextEquals(expected24)
            }

            StringDesc.localeType = StringDesc.LocaleType.Custom("en")
            waitForIdle()
            onNodeWithTag("dt12").assertTextEquals("Nov 5, 02:30 PM")
        }

    @Test
    fun reminderRelativeTimeReadsLiveDiProviderWhenDateContextHasNoClockAndFallsBackWithoutProvider() =
        runRobolectricComposeUiTest {
            StringDesc.localeType = StringDesc.LocaleType.Custom("en")
            val fireAtIso = "2025-11-05T17:30:00Z"
            val initialNow = 1_762_353_000_000L // 2025-11-05T14:30:00Z (+3h until fireAt)
            val liveProvider =
                RaftTimeContextProvider(
                    RaftTimeContext(
                        localOffsetMinutes = 0,
                        todayEpochDay = 20_397L,
                        nowEpochMillis = initialNow,
                        timeFormat = RaftTimeFormat24Hour,
                    )
                )
            val dateOnlyContext =
                RaftTimeContext(
                    localOffsetMinutes = 0,
                    todayEpochDay = 20_397L,
                    nowEpochMillis = null,
                    timeFormat = RaftTimeFormat24Hour,
                )

            setContent {
                CompositionLocalProvider(LocalRaftTimeContextProvider provides liveProvider) {
                    BasicText(
                        text = fireAtIso.membersReminderRelativeTime(dateOnlyContext),
                        modifier = Modifier.testTag("live-relative"),
                    )
                }
                BasicText(
                    text = fireAtIso.membersReminderRelativeTime(dateOnlyContext),
                    modifier = Modifier.testTag("no-provider-fallback"),
                )
            }
            waitForIdle()

            onNodeWithTag("live-relative").assertTextEquals("in 3 hours")
            onNodeWithTag("no-provider-fallback").assertTextEquals("Nov 5, 17:30")

            liveProvider.update(
                RaftTimeContext(
                    localOffsetMinutes = 0,
                    todayEpochDay = 20_397L,
                    nowEpochMillis = initialNow + 3_600_000L, // 15:30Z (+2h until fireAt)
                    timeFormat = RaftTimeFormat24Hour,
                )
            )
            waitForIdle()

            onNodeWithTag("live-relative").assertTextEquals("in 2 hours")
        }
}
