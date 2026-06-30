package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
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
