package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.NotificationAccountSettingsChanged
import build.raft.app.settingsFollowedThreadRepliesPatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * "Replies in threads I follow" (`NotificationAccountSettings.followedThreadReplies`, botiverse/slock
 * #14677 at 9b284c6d: packages/shared/src/notifications.ts, packages/server/src/services/notifications/
 * settings.ts, routes/notificationSettings.api.test.ts). Default true; an older server sends no
 * field, which reads as on and hides the row. Bodies are the server test's shapes.
 */
class RaftNotificationFollowedThreadRepliesTest {
    private val productionAccountApi = RaftNotificationAccountSettingsStore.apiProvider
    private val productionPreviewCache = RaftNotificationAccountSettingsStore.previewCache

    @AfterTest
    fun restore() {
        RaftNotificationAccountSettingsStore.apiProvider = productionAccountApi
        RaftNotificationAccountSettingsStore.previewCache = productionPreviewCache
        RaftNotificationAccountSettingsStore.clear()
    }

    private fun body(text: String?): JsonObject = Json.parseToJsonElement(requireNotNull(text)).jsonObject

    private fun scriptedStore(): ScriptedAgentControlsNetworkClient {
        val client = ScriptedAgentControlsNetworkClient()
        RaftNotificationAccountSettingsStore.apiProvider = { RaftNotificationSettingsApi(client) }
        RaftNotificationAccountSettingsStore.previewCache = {}
        return client
    }

    @Test
    fun theFieldParsesWhenSentAndReadsAsOnButUnsupportedWhenAbsent() {
        fun settingsWith(field: String): RaftNotificationAccountSettings =
            requireNotNull(
                parseRaftNotificationSettingsResponse(
                    """{"settings":{"pausedUntil":null,"quietHours":{"enabled":false,"startMinute":1320,"endMinute":480,"timeZone":"UTC"},
                      "previewMode":"full","sound":true,"mobileDelay":"inactive"$field,"version":0},"servers":[]}"""
                )
            ).settings

        val on = settingsWith(""","followedThreadReplies":true""")
        assertEquals(true, on.followedThreadReplies)
        assertTrue(on.followedThreadRepliesEnabled)
        assertTrue(on.followedThreadRepliesSupported)

        val off = settingsWith(""","followedThreadReplies":false""")
        assertFalse(off.followedThreadRepliesEnabled)
        assertTrue(off.followedThreadRepliesSupported)

        // An older server: no field. It behaves as on; the row is hidden.
        val absent = settingsWith("")
        assertNull(absent.followedThreadReplies)
        assertTrue(absent.followedThreadRepliesEnabled)
        assertFalse(absent.followedThreadRepliesSupported)

        // The PATCH answer and the 409 body carry it the same way.
        assertEquals(false, parseRaftNotificationAccountSettingsEnvelope("""{"settings":{"followedThreadReplies":false,"version":2}}""")?.followedThreadReplies)
        assertNull(parseRaftNotificationAccountSettingsEnvelope("""{"settings":{"sound":false,"version":2}}""")?.followedThreadReplies)
    }

    @Test
    fun thePatchBodyCarriesTheBooleanOnlyWhenFlippedAndOtherPatchesKeepIt() {
        val sent = body(buildRaftNotificationAccountSettingsPatchBody(RaftNotificationAccountSettingsPatch(followedThreadReplies = false), version = 7))
        assertEquals(setOf("followedThreadReplies", "version"), sent.keys)
        assertEquals(false, sent.getValue("followedThreadReplies").jsonPrimitive.boolean)
        assertEquals(7, sent.getValue("version").jsonPrimitive.int)

        // Another setting's patch never sends it, and carries the held value along (also an absent one).
        assertFalse("followedThreadReplies" in body(buildRaftNotificationAccountSettingsPatchBody(RaftNotificationAccountSettingsPatch(sound = false), 1)).keys)
        assertEquals(false, RaftNotificationAccountSettingsPatch(sound = false).appliedTo(RaftNotificationAccountSettings(followedThreadReplies = false)).followedThreadReplies)
        assertNull(RaftNotificationAccountSettingsPatch(sound = false).appliedTo(RaftNotificationAccountSettings()).followedThreadReplies)
    }

    @Test
    fun theRowWritesOnlyARealChangeAndNothingOnAnOlderServer() {
        val on = RaftNotificationAccountSettings(followedThreadReplies = true)
        assertEquals(RaftNotificationAccountSettingsPatch(followedThreadReplies = false), settingsFollowedThreadRepliesPatch(on, enabled = false))
        assertNull(settingsFollowedThreadRepliesPatch(on, enabled = true), "already on")
        assertNull(settingsFollowedThreadRepliesPatch(RaftNotificationAccountSettings(followedThreadReplies = null), enabled = false), "no setting on the server")
    }

    @Test
    fun flippingItAppliesAtOnceAndKeepsTheSavedSettings() = runTest {
        val client = scriptedStore()
        client.respond(
            "GET",
            "me/notification-settings",
            200,
            """{"settings":{"previewMode":"full","sound":false,"mobileDelay":"inactive","followedThreadReplies":true,"version":1},"servers":[]}"""
        )
        assertTrue(RaftNotificationAccountSettingsStore.load())
        client.respond(
            "PATCH",
            "me/notification-settings",
            200,
            """{"settings":{"previewMode":"full","sound":false,"mobileDelay":"inactive","followedThreadReplies":false,"version":2}}"""
        )

        assertTrue(RaftNotificationAccountSettingsStore.update(RaftNotificationAccountSettingsPatch(followedThreadReplies = false)))

        val sent = body(client.requests.last().bodyText)
        assertEquals("PATCH", client.requests.last().method)
        assertEquals(false, sent.getValue("followedThreadReplies").jsonPrimitive.boolean)
        assertEquals(1, sent.getValue("version").jsonPrimitive.int)
        val held = requireNotNull(RaftNotificationAccountSettingsStore.state.value.settings)
        assertEquals(false, held.followedThreadReplies)
        assertEquals(false, held.sound, "other settings are kept")
        assertEquals(2, held.version)
        assertNull(RaftNotificationAccountSettingsStore.state.value.errorCode)
    }

    @Test
    fun aVersionConflictAdoptsTheServerSettingsAndSendsTheFlipOnceMore() = runTest {
        val client = scriptedStore()
        client.respond(
            "PATCH",
            "me/notification-settings",
            409,
            """{"error":"version_conflict","settings":{"previewMode":"full","sound":false,"followedThreadReplies":true,"version":6}}"""
        )
        client.respond("PATCH", "me/notification-settings", 200, """{"settings":{"previewMode":"full","sound":false,"followedThreadReplies":false,"version":7}}""")

        assertTrue(RaftNotificationAccountSettingsStore.update(RaftNotificationAccountSettingsPatch(followedThreadReplies = false)))

        val bodies = client.requests.map { body(it.bodyText) }
        assertEquals(listOf(0, 6), bodies.map { it.getValue("version").jsonPrimitive.int })
        assertEquals(listOf(false, false), bodies.map { it.getValue("followedThreadReplies").jsonPrimitive.boolean })
        val held = requireNotNull(RaftNotificationAccountSettingsStore.state.value.settings)
        assertEquals(false, held.followedThreadReplies)
        assertEquals(false, held.sound, "the server's other settings were adopted")
        assertEquals(7, held.version)
    }

    @Test
    fun aRefusedFlipRollsBackAndKeepsTheTraceId() = runTest {
        val traceId = "0123456789abcdef0123456789abcdef"
        val client = scriptedStore()
        client.respond(
            "GET",
            "me/notification-settings",
            200,
            """{"settings":{"previewMode":"full","sound":true,"followedThreadReplies":true,"version":3},"servers":[]}"""
        )
        assertTrue(RaftNotificationAccountSettingsStore.load())
        // Contract: a non-boolean is a 400; any refusal leaves what was held.
        client.respond(
            "PATCH",
            "me/notification-settings",
            400,
            """{"error":"followedThreadReplies must be a boolean"}""",
            headers = mapOf("X-Trace-Id" to listOf(traceId))
        )

        assertFalse(RaftNotificationAccountSettingsStore.update(RaftNotificationAccountSettingsPatch(followedThreadReplies = false)))

        val state = RaftNotificationAccountSettingsStore.state.value
        assertEquals(true, state.settings?.followedThreadReplies, "rolled back")
        assertEquals(3, state.settings?.version)
        assertFalse(state.saving)
        assertEquals("notification_settings_save_failed", state.errorCode)
        assertEquals(traceId, state.errorTraceId)

        // The next save clears the failure and its trace ID.
        client.respond("PATCH", "me/notification-settings", 200, """{"settings":{"previewMode":"full","sound":true,"followedThreadReplies":false,"version":4}}""")
        assertTrue(RaftNotificationAccountSettingsStore.update(RaftNotificationAccountSettingsPatch(followedThreadReplies = false)))
        assertNull(RaftNotificationAccountSettingsStore.state.value.errorCode)
        assertNull(RaftNotificationAccountSettingsStore.state.value.errorTraceId)
    }

    @Test
    fun theAccountSocketUpdateCarriesItToTheStore() {
        val event =
            assertIs<NotificationAccountSettingsChanged>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.NotificationPrefsUpdated,
                    """{"type":"account","settings":{"pausedUntil":null,
                      "quietHours":{"enabled":false,"startMinute":1320,"endMinute":480,"timeZone":"UTC"},
                      "previewMode":"full","sound":false,"mobileDelay":"inactive","followedThreadReplies":false,"version":2}}"""
                )
            )
        assertEquals(false, event.settings.followedThreadReplies)
        RaftNotificationAccountSettingsStore.previewCache = {}
        RaftNotificationAccountSettingsStore.applyRealtime(event.settings)
        assertEquals(false, RaftNotificationAccountSettingsStore.state.value.settings?.followedThreadReplies)
        assertEquals(2, RaftNotificationAccountSettingsStore.state.value.settings?.version)
    }
}
