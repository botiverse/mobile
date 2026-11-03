package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/**
 * Settings › Notifications as on the web (botiverse/slock packages/web/src/components/settings/
 * notifications): the request bodies of its pause, quiet hours and content controls, and the
 * channel names of its Servers card.
 */
class RaftNotificationSettingsWebPageTest {
    private val productionNamesApi = RaftNotificationChannelNames.apiProvider

    @AfterTest
    fun restore() {
        RaftNotificationChannelNames.apiProvider = productionNamesApi
        RaftNotificationChannelNames.clear()
    }

    @Test
    fun quietHoursEditSendsAllFourFieldsWithThisDeviceZone() {
        val stored = RaftNotificationQuietHours(enabled = false, startMinute = 1320, endMinute = 480, timeZone = "Europe/Berlin")
        assertEquals(
            """{"quietHours":{"enabled":true,"startMinute":1320,"endMinute":480,"timeZone":"Asia/Shanghai"},"version":4}""",
            buildRaftNotificationAccountSettingsPatchBody(raftQuietHoursPatch(stored, "Asia/Shanghai", enabled = true), version = 4)
        )
        assertEquals(
            """{"quietHours":{"enabled":false,"startMinute":1350,"endMinute":480,"timeZone":"Asia/Shanghai"}}""",
            buildRaftNotificationAccountSettingsPatchBody(raftQuietHoursPatch(stored, "Asia/Shanghai", startMinute = 1350), version = null)
        )
        assertEquals(
            """{"quietHours":{"enabled":false,"startMinute":1320,"endMinute":450,"timeZone":"Asia/Shanghai"}}""",
            buildRaftNotificationAccountSettingsPatchBody(raftQuietHoursPatch(stored, "Asia/Shanghai", endMinute = 450), version = null)
        )
    }

    @Test
    fun pauseResumeSoundDelayAndPreviewBodiesMatchTheWeb() {
        assertEquals(
            """{"pausedUntil":"2026-11-03T08:30:00Z","version":2}""",
            buildRaftNotificationAccountSettingsPatchBody(
                RaftNotificationAccountSettingsPatch(pausedUntil = RaftNotificationPauseOption.Minutes30.pausedUntil(NOW, "UTC")),
                version = 2
            )
        )
        assertEquals("""{"pausedUntil":null}""", buildRaftNotificationAccountSettingsPatchBody(RaftNotificationAccountSettingsPatch(resume = true), null))
        assertEquals("""{"sound":false}""", buildRaftNotificationAccountSettingsPatchBody(RaftNotificationAccountSettingsPatch(sound = false), null))
        assertEquals(
            """{"mobileDelay":"always"}""",
            buildRaftNotificationAccountSettingsPatchBody(RaftNotificationAccountSettingsPatch(mobileDelay = RaftNotificationMobileDelay.Always), null)
        )
        assertEquals(
            """{"previewMode":"hidden"}""",
            buildRaftNotificationAccountSettingsPatchBody(RaftNotificationAccountSettingsPatch(previewMode = PushPreviewMode.Hidden), null)
        )
        assertEquals(
            """{"scope":"server","id":"s2","level":"mentions"}""",
            buildRaftNotificationLevelBody(RaftNotificationLevelScope.Server, "s2", RaftNotificationLevelSetting.Mentions)
        )
        assertEquals(
            """{"scope":"channel","id":"c1","level":"default"}""",
            buildRaftNotificationLevelBody(RaftNotificationLevelScope.Channel, "c1", RaftNotificationLevelSetting.Default)
        )
    }

    @Test
    fun tomorrowMorningPauseIsNineInTheGivenZone() {
        // 08:00Z is 16:00 in Shanghai: tomorrow 09:00 there is 01:00Z the next day.
        assertEquals("2026-11-04T01:00:00Z", RaftNotificationPauseOption.TomorrowMorning.pausedUntil(NOW, "Asia/Shanghai"))
    }

    @Test
    fun customPauseStartsAnHourAheadOnTheQuarterHourAndMustBeAhead() {
        val initial = RaftNotificationCustomPause.initial(NOW + 7 * 60_000L, "UTC")
        assertEquals(RaftNotificationCustomPause(LocalDate(2026, 11, 3), 9 * 60 + 15), initial)
        assertEquals("2026-11-03T09:15:00Z", initial.pausedUntil("UTC"))
        assertTrue(initial.accepted(NOW, "UTC"))
        assertFalse(RaftNotificationCustomPause(LocalDate(2026, 11, 3), 7 * 60).accepted(NOW, "UTC"), "in the past")
        assertFalse(RaftNotificationCustomPause(LocalDate(2027, 12, 1), 0).accepted(NOW, "UTC"), "more than 366 days ahead")
        assertEquals("2026-11-03T01:15:00Z", RaftNotificationCustomPause(LocalDate(2026, 11, 3), 9 * 60 + 15).pausedUntil("Asia/Shanghai"))
        // Late in the day it rolls over to the next day at midnight.
        assertEquals(
            RaftNotificationCustomPause(LocalDate(2026, 11, 4), 0),
            RaftNotificationCustomPause.initial(NOW + (14 * 60 + 50) * 60_000L, "UTC")
        )
        val days = RaftNotificationCustomPause.days(NOW, "UTC")
        assertEquals(LocalDate(2026, 11, 3), days.first())
        assertEquals(14, days.size)
    }

    @Test
    fun quietHoursTimesAreTheQuarterHoursPlusAnOffGridStoredTime() {
        val grid = raftQuietHoursTimeOptions(1320)
        assertEquals(96, grid.size)
        assertEquals(listOf(0, 15, 30), grid.take(3))
        val withHeld = raftQuietHoursTimeOptions(437)
        assertEquals(97, withHeld.size)
        assertTrue(437 in withHeld)
        assertEquals(withHeld.sorted(), withHeld)
    }

    @Test
    fun channelNamesLabelChannelsWithHashAndDirectMessagesByPeer() {
        assertEquals(
            mapOf("c1" to "#general", "d1" to "Ada", "d2" to "bob", "d3" to "dm-3"),
            parseRaftNotificationChannelNames(
                """[{"id":"c1","name":"general","type":"channel"},
                    {"id":"d1","name":"dm-1","type":"dm","peerDisplayName":"Ada","peerName":"ada"},
                    {"id":"d2","name":"dm-2","type":"dm","peerDisplayName":"","peerName":"bob"},
                    {"id":"d3","name":"dm-3","type":"dm"},
                    {"id":"","name":"nameless","type":"channel"}]"""
            )
        )
        assertNull(parseRaftNotificationChannelNames("""{"channels":[]}"""))
    }

    @Test
    fun channelNamesLoadBothListsOfThatServerOnce() = runTest {
        val asked = mutableListOf<Pair<String, String?>>()
        val client =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?
                ): HomeNetworkResponse {
                    asked += path to serverIdOverride?.value
                    return when (path) {
                        "channels" -> HomeNetworkResponse(200, """[{"id":"c1","name":"general","type":"channel"}]""")
                        "channels/dm" -> HomeNetworkResponse(500, null)
                        else -> HomeNetworkResponse(404, null)
                    }
                }
            }
        RaftNotificationChannelNames.apiProvider = { RaftNotificationSettingsApi(client) }
        RaftNotificationChannelNames.load("s2")
        RaftNotificationChannelNames.load("s2")
        assertEquals<List<Pair<String, String?>>>(listOf("channels" to "s2", "channels/dm" to "s2"), asked)
        assertEquals(mapOf("s2" to mapOf("c1" to "#general")), RaftNotificationChannelNames.state.value)
    }

    private companion object {
        /** 2026-11-03T08:00:00Z. */
        const val NOW = 1_793_692_800_000L
    }
}
