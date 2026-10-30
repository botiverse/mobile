package build.raft.app.data

import build.raft.app.model.NotificationLevelChanged
import build.raft.app.model.ServerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Settings API, stores and the test notification of botiverse/slock
 * `docs/contracts/notifications.md` §2 / §6 (SHA in contract-cases/notification-levels/SOURCE_SHA).
 * Response bodies are the contract's examples.
 */
class RaftNotificationSettingsApiTest {
    private val productionLevelApi = RaftNotificationLevelStore.apiProvider
    private val productionAccountApi = RaftNotificationAccountSettingsStore.apiProvider
    private val productionPreviewCache = RaftNotificationAccountSettingsStore.previewCache

    @AfterTest
    fun restore() {
        RaftNotificationLevelStore.apiProvider = productionLevelApi
        RaftNotificationAccountSettingsStore.apiProvider = productionAccountApi
        RaftNotificationAccountSettingsStore.previewCache = productionPreviewCache
        RaftNotificationLevelStore.clear()
        RaftNotificationAccountSettingsStore.clear()
    }

    @Test
    fun accountSettingsResponseParsesSettingsAndServerLevels() {
        val parsed =
            parseRaftNotificationSettingsResponse(
                """
                {"settings":{"pausedUntil":null,"quietHours":{"enabled":false,"startMinute":1320,"endMinute":480,"timeZone":"Europe/Berlin"},
                  "previewMode":"full","sound":true,"version":3},
                 "servers":[{"serverId":"s1","level":"all"},{"serverId":"s2","level":"none"},{"serverId":"s3","level":"loud"}]}
                """.trimIndent()
            )
        requireNotNull(parsed)
        assertEquals(3, parsed.settings.version)
        assertEquals("Europe/Berlin", parsed.settings.quietHours.timeZone)
        assertEquals(mapOf("s1" to RaftNotificationLevel.All, "s2" to RaftNotificationLevel.None), parsed.serverLevels)
        assertEquals(PushPreviewMode.Hidden, RaftNotificationAccountSettings(previewMode = "private").preview, "unknown fails closed")
    }

    @Test
    fun patchBodySendsOnlyTheChangedFieldsAndAnExplicitResume() {
        assertEquals(
            """{"pausedUntil":null,"version":3}""",
            buildRaftNotificationAccountSettingsPatchBody(RaftNotificationAccountSettingsPatch(resume = true), version = 3)
        )
        assertEquals(
            """{"previewMode":"sender_only","sound":false}""",
            buildRaftNotificationAccountSettingsPatchBody(
                RaftNotificationAccountSettingsPatch(previewMode = PushPreviewMode.SenderOnly, sound = false),
                version = null
            )
        )
        assertEquals(
            """{"scope":"thread","id":"t1","level":"default"}""",
            buildRaftNotificationLevelBody(RaftNotificationLevelScope.Thread, "t1", RaftNotificationLevelSetting.Default)
        )
    }

    @Test
    fun serverLevelsListOnlyExplicitValuesForTheAskedServer() {
        val levels =
            requireNotNull(
                parseRaftServerNotificationLevels(
                    """{"serverId":"s1","server":"none","channels":[{"id":"c1","level":"all"}],"threads":[{"id":"t1","level":"mentions"},{"id":"t2","level":"default"}]}""",
                    requestedServerId = "s1"
                )
            )
        assertEquals(RaftNotificationLevel.None, levels.server)
        assertTrue(levels.serverLevelKnown)
        assertEquals(RaftNotificationLevelSetting.All, levels.channelSetting("c1"))
        assertEquals(RaftNotificationLevelSetting.Default, levels.channelSetting("c2"))
        assertEquals(RaftNotificationLevelSetting.Mentions, levels.threadSetting("t1"))
        assertEquals(RaftNotificationLevelSetting.Default, levels.threadSetting("t2"))
        assertNull(parseRaftServerNotificationLevels("""{"serverId":"s2","server":"all"}""", requestedServerId = "s1"))
    }

    @Test
    fun setLevelAppliesAtOnceAndRollsBackWhenRefused() = runTest {
        val client = ScriptedAgentControlsNetworkClient()
        RaftNotificationLevelStore.apiProvider = { RaftNotificationSettingsApi(client) }
        client.respond(
            "PUT",
            "notification-levels",
            200,
            """{"scope":"channel","id":"c1","serverId":"s1","level":"mentions","effective":"mentions","muted":false,"prefsVersion":7}"""
        )
        val saved = RaftNotificationLevelStore.setLevel("s1", RaftNotificationLevelScope.Channel, "c1", RaftNotificationLevelSetting.Mentions)
        assertIs<DataResult.Content<RaftNotificationLevelUpdate>>(saved)
        assertEquals(RaftNotificationLevelSetting.Mentions, RaftNotificationLevelStore.levelsFor("s1").channelSetting("c1"))
        assertEquals("""{"scope":"channel","id":"c1","level":"mentions"}""", client.requests.single().bodyText)

        client.respond("PUT", "notification-levels", 500, """{"error":"boom"}""")
        val refused = RaftNotificationLevelStore.setLevel("s1", RaftNotificationLevelScope.Channel, "c1", RaftNotificationLevelSetting.None)
        assertIs<DataResult.Error>(refused)
        assertEquals(RaftNotificationLevelSetting.Mentions, RaftNotificationLevelStore.levelsFor("s1").channelSetting("c1"), "rolled back")

        assertIs<DataResult.Error>(
            RaftNotificationLevelStore.setLevel("s1", RaftNotificationLevelScope.Server, "s1", RaftNotificationLevelSetting.Default),
            "a server cannot be default"
        )
    }

    @Test
    fun realtimeLevelUpdatesAreDedupedPerTypeAndScope() {
        // Contract §2.3: a channel's level and a thread with the same id keep their own versions.
        RaftNotificationLevelStore.applyRealtime(NotificationLevelChanged(ServerId("s1"), "channel", "x1", "none", prefsVersion = 5))
        RaftNotificationLevelStore.applyRealtime(NotificationLevelChanged(ServerId("s1"), "thread", "x1", "all", prefsVersion = 1))
        val levels = RaftNotificationLevelStore.levelsFor("s1")
        assertEquals(RaftNotificationLevelSetting.None, levels.channelSetting("x1"))
        assertEquals(RaftNotificationLevelSetting.All, levels.threadSetting("x1"))
        // An older or equal version of the same key is dropped; a newer one applies.
        RaftNotificationLevelStore.applyRealtime(NotificationLevelChanged(ServerId("s1"), "channel", "x1", "all", prefsVersion = 5))
        assertEquals(RaftNotificationLevelSetting.None, RaftNotificationLevelStore.levelsFor("s1").channelSetting("x1"))
        RaftNotificationLevelStore.applyRealtime(NotificationLevelChanged(ServerId("s1"), "channel", "x1", "default", prefsVersion = 6))
        assertEquals(RaftNotificationLevelSetting.Default, RaftNotificationLevelStore.levelsFor("s1").channelSetting("x1"))
        assertEquals("server:s1", raftNotificationPrefsVersionKey(RaftNotificationLevelScope.Server, "s1"))
    }

    @Test
    fun accountUpdateRetriesOnceOnAVersionConflict() = runTest {
        val client = ScriptedAgentControlsNetworkClient()
        val cached = mutableListOf<PushPreviewMode>()
        RaftNotificationAccountSettingsStore.apiProvider = { RaftNotificationSettingsApi(client) }
        RaftNotificationAccountSettingsStore.previewCache = { cached += it }
        client.respond(
            "PATCH",
            "me/notification-settings",
            409,
            """{"error":"version_conflict","settings":{"previewMode":"full","sound":true,"version":4}}"""
        )
        client.respond("PATCH", "me/notification-settings", 200, """{"settings":{"previewMode":"hidden","sound":true,"version":5}}""")

        assertTrue(RaftNotificationAccountSettingsStore.update(RaftNotificationAccountSettingsPatch(previewMode = PushPreviewMode.Hidden)))

        val bodies = client.requests.map { it.bodyText }
        assertEquals(listOf("""{"previewMode":"hidden","version":0}""", """{"previewMode":"hidden","version":4}"""), bodies)
        assertEquals(5, RaftNotificationAccountSettingsStore.state.value.settings?.version)
        assertEquals(PushPreviewMode.Hidden, cached.last(), "the device copy of the preview follows the account")
    }

    @Test
    fun aRefusedAccountUpdateRollsBack() = runTest {
        val client = ScriptedAgentControlsNetworkClient()
        RaftNotificationAccountSettingsStore.apiProvider = { RaftNotificationSettingsApi(client) }
        RaftNotificationAccountSettingsStore.previewCache = {}
        client.respond("PATCH", "me/notification-settings", 400, """{"error":"invalid"}""")

        assertFalse(RaftNotificationAccountSettingsStore.update(RaftNotificationAccountSettingsPatch(sound = false)))

        assertEquals(true, RaftNotificationAccountSettingsStore.state.value.settings?.sound)
        assertEquals("notification_settings_save_failed", RaftNotificationAccountSettingsStore.state.value.errorCode)
    }

    @Test
    fun pauseOptionsPointAtTheRightTime() {
        val now = 1_798_000_000_000L // 2026-12-23T04:26:40Z
        assertEquals(kotlin.time.Instant.fromEpochMilliseconds(now + 30 * 60_000L).toString(), RaftNotificationPauseOption.Minutes30.pausedUntil(now, "UTC"))
        assertEquals("2026-12-24T09:00:00Z", RaftNotificationPauseOption.TomorrowMorning.pausedUntil(now, "UTC"))
        assertEquals("2026-12-24T08:00:00Z", RaftNotificationPauseOption.TomorrowMorning.pausedUntil(now, "Europe/Berlin"))
        assertEquals("22:30", raftMinuteOfDayLabel(1350))
        assertEquals("00:00", raftMinuteOfDayLabel(1440))
    }

    @Test
    fun serverTestNotificationWaitsForTheDevicesAck() = runTest {
        val client = ScriptedAgentControlsNetworkClient()
        client.respond("POST", "notifications/test", 200, """{"testId":"t-1","sent":true}""")
        client.respond("GET", "notifications/test/t-1", 200, """{"status":"pending"}""")
        client.respond("GET", "notifications/test/t-1", 200, """{"status":"shown"}""")

        val outcome = raftSendServerTestNotification(networkClient = client, installationId = "install-1")

        assertEquals(RaftNotificationTestOutcome.Shown, outcome)
        assertEquals("""{"device":{"kind":"mobile","installationId":"install-1"}}""", client.requests.first().bodyText)
        assertEquals("sent", outcome.toSystemNotificationActionResult()?.code)
    }

    @Test
    fun serverTestNotificationFallsBackOrReportsWhatWentWrong() = runTest {
        val noDevice = ScriptedAgentControlsNetworkClient().apply {
            respond("POST", "notifications/test", 200, """{"sent":false,"reason":"no_device"}""")
        }
        assertEquals(RaftNotificationTestOutcome.Unavailable, raftSendServerTestNotification(noDevice, "install-1"))
        // An older server without the route: the local test runs instead.
        assertEquals(RaftNotificationTestOutcome.Unavailable, raftSendServerTestNotification(ScriptedAgentControlsNetworkClient(), "install-1"))
        assertEquals(RaftNotificationTestOutcome.Unavailable, raftSendServerTestNotification(noDevice, installationId = null))
        assertNull(RaftNotificationTestOutcome.Unavailable.toSystemNotificationActionResult())

        val providerError = ScriptedAgentControlsNetworkClient().apply {
            respond("POST", "notifications/test", 200, """{"sent":false,"reason":"provider_error"}""")
        }
        assertEquals(RaftNotificationTestOutcome.Failed("provider_error"), raftSendServerTestNotification(providerError, "install-1"))

        val neverAcked = ScriptedAgentControlsNetworkClient().apply {
            respond("POST", "notifications/test", 200, """{"testId":"t-2","sent":true}""")
            respond("GET", "notifications/test/t-2", 200, """{"status":"pending"}""")
        }
        val timedOut = raftSendServerTestNotification(neverAcked, "install-1")
        assertEquals(RaftNotificationTestOutcome.NotShown("timeout"), timedOut)
        assertEquals("test_not_shown", timedOut.toSystemNotificationActionResult()?.code)
    }
}
