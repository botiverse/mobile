package build.raft.app.data.realtime

import build.raft.app.data.RaftNotificationRoute
import build.raft.app.data.parseRaftNotificationRoute
import build.raft.app.model.NotificationAccountSettingsChanged
import build.raft.app.model.NotificationLevelChanged
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.NotificationRetracted
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.ServerNotificationPreferencesChanged
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Socket payloads of the unified notification contract (botiverse/slock
 * `docs/contracts/notifications.md` §2.3, §4.1, §5; pinned SHA in
 * `contract-cases/notification-levels/SOURCE_SHA`).
 */
class RealtimeEventMapperNotificationContractTest {
    private val mapper = RealtimeEventMapper()

    @Test
    fun notificationPushCarriesTheEnvelope() {
        val push =
            assertIs<NotificationPushReceived>(
                mapper.map(
                    RealtimeSocketEvents.NotificationPush,
                    """
                    {
                      "serverId": "server-2", "kind": "dm", "channelId": "dm-1", "messageId": "m-9",
                      "title": "Ada", "body": "hello", "tag": "server-2:dm-1",
                      "id": "message:m-9", "category": "dm", "priority": "high", "seq": 41,
                      "groupKey": "server-2:dm-1", "conversationId": "dm-1",
                      "route": {"type": "dm", "serverId": "server-2", "channelId": "dm-1", "messageId": "m-9"},
                      "routeUrl": "/s/acme/dm/dm-1?msg=m-9", "silent": true, "previewMode": "full"
                    }
                    """.trimIndent()
                )
            )
        assertEquals("message:m-9", push.notificationId)
        assertEquals("dm", push.category)
        assertEquals(41L, push.seq)
        assertEquals("server-2:dm-1", push.groupKey)
        assertEquals("dm-1", push.conversationId)
        assertEquals(true, push.silent)
        assertEquals(RaftNotificationRoute.DirectMessage("server-2", "dm-1", "m-9"), parseRaftNotificationRoute(push.routeJson))
    }

    @Test
    fun notificationPushFromAnOlderServerStillMaps() {
        val push =
            assertIs<NotificationPushReceived>(
                mapper.map(
                    RealtimeSocketEvents.NotificationPush,
                    """{"serverId":"s1","kind":"channel","channelId":"c1","messageId":"m1","title":"#a","body":"b","tag":"t"}"""
                )
            )
        assertNull(push.category)
        assertNull(push.seq)
        assertNull(push.routeJson)
    }

    @Test
    fun retractMapsWithNumberOrNullThroughSeq() {
        val partial =
            assertIs<NotificationRetracted>(
                mapper.map(
                    RealtimeSocketEvents.NotificationRetract,
                    """{"serverId":"s1","groupKey":"s1:c1","conversationId":"c1","throughSeq":12}"""
                )
            )
        assertEquals(12L, partial.throughSeq)
        assertEquals("s1:c1", partial.groupKey)
        val all =
            assertIs<NotificationRetracted>(
                mapper.map(
                    RealtimeSocketEvents.NotificationRetract,
                    """{"serverId":"s1","groupKey":"s1:c1","conversationId":"c1","throughSeq":null}"""
                )
            )
        assertNull(all.throughSeq)
        assertNull(mapper.map(RealtimeSocketEvents.NotificationRetract, """{"serverId":"s1","conversationId":"c1"}"""))
    }

    @Test
    fun prefsUpdatedTypesMapToTheirEvents() {
        val channel =
            assertIs<NotificationLevelChanged>(
                mapper.map(
                    RealtimeSocketEvents.NotificationPrefsUpdated,
                    """{"type":"channel","serverId":"s1","scopeId":"c1","level":"none","prefsVersion":2}"""
                )
            )
        assertEquals("channel", channel.scope)
        assertEquals("none", channel.level)
        assertEquals(2L, channel.prefsVersion)
        val thread =
            assertIs<NotificationLevelChanged>(
                mapper.map(
                    RealtimeSocketEvents.NotificationPrefsUpdated,
                    """{"type":"thread","serverId":"s1","scopeId":"t1","level":"default","prefsVersion":1}"""
                )
            )
        assertEquals("default", thread.level)
        val server =
            assertIs<ServerNotificationPreferencesChanged>(
                mapper.map(
                    RealtimeSocketEvents.NotificationPrefsUpdated,
                    """{"type":"server","serverId":"s1","scopeId":"s1","level":"mentions","prefs":{"serverPushMode":"mentions"},"prefsVersion":4}"""
                )
            )
        assertEquals("mentions", server.serverPushMode)
        val account =
            assertIs<NotificationAccountSettingsChanged>(
                mapper.map(
                    RealtimeSocketEvents.NotificationPrefsUpdated,
                    """
                    {"type":"account","settings":{"pausedUntil":null,
                      "quietHours":{"enabled":true,"startMinute":1320,"endMinute":480,"timeZone":"Europe/Berlin"},
                      "previewMode":"sender_only","sound":false,"version":3}}
                    """.trimIndent()
                )
            )
        assertEquals(3, account.settings.version)
        assertEquals(false, account.settings.sound)
        assertTrue(account.settings.quietHours.enabled)
        assertEquals("Europe/Berlin", account.settings.quietHours.timeZone)
    }

    @Test
    fun aChannelMuteKeepsItsOriginalShape() {
        assertIs<PerReceiverNotificationPrefsChanged>(
            mapper.map(
                RealtimeSocketEvents.NotificationPrefsUpdated,
                """{"serverId":"s1","scopeId":"c1","prefs":{"activityMuted":true,"muteFromSeq":5},"prefsVersion":3}"""
            )
        )
        assertNull(
            mapper.map(
                RealtimeSocketEvents.NotificationPrefsUpdated,
                """{"type":"channel","serverId":"s1","scopeId":"c1","level":"loud","prefsVersion":2}"""
            ),
            "an unknown level is rejected"
        )
    }
}
