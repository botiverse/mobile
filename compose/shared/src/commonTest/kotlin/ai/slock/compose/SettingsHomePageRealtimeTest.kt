package ai.slock.compose

import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
import ai.slock.compose.data.realtime.RealtimeEventMapper
import ai.slock.compose.data.realtime.RealtimeSocketEvents
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
import ai.slock.compose.model.ServerId
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SettingsHomePageRealtimeTest {
    @Test
    fun announcementRealtimeFallsBackToCurrentSessionServer() {
        val eventData =
            JSONObject().apply {
                put("eventName", RealtimeSocketEvents.AnnouncementNew)
                put("rawPayload", "{}")
            }

        val event =
            assertIs<RealtimeRefreshRequired>(
                decodeSharedShellRealtimeEvent(eventData, RealtimeEventMapper()) {}
            )

        assertEquals(RealtimeRefreshScope.Announcements, event.scope)
        assertEquals("server-1", resolveSharedShellRealtimeServerId(eventData, event, sampleSession()))
    }

    @Test
    fun realtimeServerResolutionPrefersBridgeWrapperThenEventPayload() {
        val event =
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.Home,
                reason = "channel:updated",
                serverId = ServerId("event-server")
            )

        assertEquals(
            "wrapper-server",
            resolveSharedShellRealtimeServerId(
                JSONObject().apply { put("serverId", "wrapper-server") },
                event,
                sampleSession()
            )
        )
        assertEquals(
            "event-server",
            resolveSharedShellRealtimeServerId(JSONObject(), event, sampleSession())
        )
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Raft"))
        )
}
