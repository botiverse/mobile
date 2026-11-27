package build.raft.app

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.data.RaftTimeZoneRuntime
import build.raft.app.members.RaftMemberDetailPayload
import build.raft.app.members.membersAgentNowTurnTime
import build.raft.app.members.membersReminderRelativeTime
import build.raft.app.members.membersReminderShortDateTime
import build.raft.app.members.rememberMemberDetailTimeContextProvider
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.resources.StringDesc
import build.raft.app.thread.toRaftTimeContext
import build.raft.app.members.toRaftTimeContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
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

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "members-reminder-datetime-locale"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest
    fun setUp() {
        RaftTimeFormatRuntime.resetForTest()
        RaftTimeZoneRuntime.resetForTest()
        PagerManager.register(livePager)
    }

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
        RaftTimeFormatRuntime.resetForTest()
        RaftTimeZoneRuntime.resetForTest()
        PagerManager.unregister(livePager)
    }

    @Test
    fun shortReminderDateTimeLocalizesAcrossAllEightLocalesAndRespects12hFormat() =
        runRobolectricComposeUiTest {
            val utc24 = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = 20_397L, timeFormat = RaftTimeFormat24Hour)
            val utc12 = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = 20_397L, timeFormat = RaftTimeFormat12Hour)
            val tokyo24 = RaftTimeContext(localOffsetMinutes = 540, todayEpochDay = 20_397L, timeFormat = RaftTimeFormat24Hour)
            val iso = "2025-11-05T14:30:00Z"

            setContent {
                BasicText(text = iso.membersReminderShortDateTime(utc24), modifier = Modifier.testTag("dt24"))
                BasicText(text = iso.membersReminderShortDateTime(utc12), modifier = Modifier.testTag("dt12"))
                BasicText(text = iso.membersReminderShortDateTime(tokyo24), modifier = Modifier.testTag("dt-tokyo"))
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
            onNodeWithTag("dt-tokyo").assertTextEquals("Nov 5, 23:30")
        }

    @Test
    fun memberDetailDayOnlyPayloadStillInheritsLiveProviderSoRemindersStayRelative() =
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
            val dayOnlyLivePayload =
                RaftMemberDetailPayload(
                    instanceId = "detail-1",
                    todayEpochDay = 20_397L,
                    nowEpochMillis = null,
                )
            val pinnedClockPayload =
                RaftMemberDetailPayload(
                    instanceId = "detail-2",
                    localOffsetMinutes = 0,
                    todayEpochDay = 20_397L,
                    nowEpochMillis = initialNow + 3_600_000L, // +2h until fireAt
                )

            setContent {
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(livePager),
                    LocalRaftTimeContextProvider provides liveProvider,
                ) {
                    val inherited = rememberMemberDetailTimeContextProvider(dayOnlyLivePayload, pageVisibleTick = 0, pageVisible = true)
                    val pinned = rememberMemberDetailTimeContextProvider(pinnedClockPayload, pageVisibleTick = 0, pageVisible = true)
                    CompositionLocalProvider(LocalRaftTimeContextProvider provides inherited) {
                        BasicText(
                            text = fireAtIso.membersReminderRelativeTime(inherited.dateContext ?: dayOnlyLivePayload.toRaftTimeContext()),
                            modifier = Modifier.testTag("day-only-live"),
                        )
                    }
                    CompositionLocalProvider(LocalRaftTimeContextProvider provides pinned) {
                        BasicText(
                            text = fireAtIso.membersReminderRelativeTime(pinned.current ?: pinnedClockPayload.toRaftTimeContext()),
                            modifier = Modifier.testTag("pinned-clock"),
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("day-only-live").assertTextEquals("in 3 hours")
            onNodeWithTag("pinned-clock").assertTextEquals("in 2 hours")
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

    @Test
    fun memberDetailTimeContextProviderHonorsAccountPreferredTimezoneOverDeviceOffset() =
        runRobolectricComposeUiTest {
            StringDesc.localeType = StringDesc.LocaleType.Custom("en")
            val nov21Utc = 1_795_278_180_000L // 2026-11-21T16:23:00Z (10:23 CST / 11:23 EST)
            val deviceUtcMinus6 =
                RaftTimeContextProvider(
                    RaftTimeContext(
                        localOffsetMinutes = -6 * 60,
                        todayEpochDay = 20_780L, // 2026-11-23
                        nowEpochMillis = nov21Utc + 2 * 86_400_000L,
                        timeFormat = RaftTimeFormat24Hour,
                        timeZoneId = "America/Chicago",
                    )
                )
            val livePayload =
                RaftMemberDetailPayload(
                    instanceId = "detail-tz-1",
                    localOffsetMinutes = -6 * 60,
                    todayEpochDay = 20_780L,
                    nowEpochMillis = null,
                )

            setContent {
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(livePager),
                    LocalRaftTimeContextProvider provides deviceUtcMinus6,
                ) {
                    val resolved = rememberMemberDetailTimeContextProvider(livePayload, pageVisibleTick = 0, pageVisible = true)
                    val ctx = resolved.dateContext ?: livePayload.toRaftTimeContext()
                    BasicText(
                        text = membersAgentNowTurnTime(nov21Utc, ctx),
                        modifier = Modifier.testTag("turn-time"),
                    )
                }
            }
            waitForIdle()
            onNodeWithTag("turn-time").assertTextEquals("Nov 21, 10:23")

            RaftTimeZoneRuntime.update("America/New_York")
            waitForIdle()
            onNodeWithTag("turn-time").assertTextEquals("Nov 21, 11:23")
        }
}
