package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SettingsHomePageRealtimeTest {
    @Test
    fun announcementRealtimeWithoutAnAuthoritativeServerFailsClosed() {
        val eventData =
            buildJsonObject {
                put("eventName", RealtimeSocketEvents.AnnouncementNew)
                put("rawPayload", "{}")
            }

        val event =
            assertIs<RealtimeRefreshRequired>(
                decodeSharedShellRealtimeEvent(eventData, RealtimeEventMapper()) {}
            )

        assertEquals(RealtimeRefreshScope.Announcements, event.scope)
        assertEquals(null, resolveSharedShellRealtimeServerId(eventData, event, sampleSession()))
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
                buildJsonObject { put("serverId", "wrapper-server") },
                event,
                sampleSession(),
            )
        )
        assertEquals(
            "event-server",
            resolveSharedShellRealtimeServerId(JsonObject(emptyMap()), event, sampleSession())
        )
    }

    @Test
    fun realtimeDeliveryRequiresExactShellServerMatch() {
        assertTrue(shouldRouteRealtimeEventToShell("server-a", "server-a"))
        assertFalse(shouldRouteRealtimeEventToShell("server-a", "server-b"))
        assertFalse(shouldRouteRealtimeEventToShell("server-a", null))
        assertFalse(shouldRouteRealtimeEventToShell(null, "server-a"))
    }

    @Test
    fun presenceTransportCursorAcceptsOnlyNewEpochWithinAConnectionFamily() {
        val first =
            nextAppShellPresenceTransportCursor(
                previous = null,
                connectionFamilyId = "family-1",
                transportEpoch = 3L
            )
        assertEquals(AppShellPresenceTransportCursor("family-1", 3L), first)
        assertEquals(
            null,
            nextAppShellPresenceTransportCursor(first, "family-1", transportEpoch = 3L)
        )
        assertEquals(
            null,
            nextAppShellPresenceTransportCursor(first, "family-1", transportEpoch = 2L)
        )
        assertEquals(
            AppShellPresenceTransportCursor("family-2", 1L),
            nextAppShellPresenceTransportCursor(first, "family-2", transportEpoch = 1L)
        )
    }

    @Test
    fun backgroundUnreadProjectionRequiresAnotherServerInTheAuthenticatedSession() {
        val session =
            sampleSession().copy(
                servers =
                    listOf(
                        StoredAuthServer(id = "server-1", name = "Raft"),
                        StoredAuthServer(id = "server-2", name = "Work")
                    )
            )

        assertTrue(shouldProjectBackgroundServerUnread(session, "server-1", "server-2"))
        assertFalse(shouldProjectBackgroundServerUnread(session, "server-1", "server-1"))
        assertFalse(shouldProjectBackgroundServerUnread(session, "server-1", "server-unknown"))
        assertFalse(shouldProjectBackgroundServerUnread(null, "server-1", "server-2"))
    }

    @Test
    fun realtimeAuthRefreshAcceptsAnyAuthenticatedServerNotOnlySelectedServer() {
        val session =
            sampleSession().copy(
                servers =
                    listOf(
                        StoredAuthServer(id = "server-1", name = "Selected"),
                        StoredAuthServer(id = "server-2", name = "Background"),
                    )
            )

        assertTrue(canRefreshRealtimeServer(session, "server-1"))
        assertTrue(canRefreshRealtimeServer(session, "server-2"))
        assertFalse(canRefreshRealtimeServer(session, "server-unknown"))
        assertFalse(canRefreshRealtimeServer(null, "server-1"))
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
