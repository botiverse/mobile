package build.raft.app.data.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeProcessDesiredSessionTest {
    @Test
    fun authenticatedServerSetStartsOneRuntimePerServer() {
        val next = desired(serverIds = setOf("server-b", "server-a"))

        val plan = reconcileRealtimeProcessDesiredSession(previous = null, next = next)

        assertFalse(plan.disconnectAll)
        assertEquals(emptySet(), plan.disconnectServerIds)
        assertEquals(setOf("server-a", "server-b"), plan.connectServerIds)
    }

    @Test
    fun removingOneServerKeepsOtherRuntime() {
        val previous = desired(serverIds = setOf("server-a", "server-b"))
        val next = previous.copy(serverIds = setOf("server-a"))

        val plan = reconcileRealtimeProcessDesiredSession(previous, next)

        assertFalse(plan.disconnectAll)
        assertEquals(setOf("server-b"), plan.disconnectServerIds)
        assertEquals(emptySet(), plan.connectServerIds)
    }

    @Test
    fun authGenerationOrEnvironmentReplacementRetiresEveryOldRuntime() {
        val previous = desired(serverIds = setOf("server-a", "server-b"))
        val next =
            desired(
                environmentId = "https://staging.example",
                socketUrl = "https://socket.staging.example",
                authGeneration = previous.authGeneration + 1,
                connectionFamilyId = "family-2",
                serverIds = setOf("server-a", "server-c"),
            )

        val plan = reconcileRealtimeProcessDesiredSession(previous, next)

        assertTrue(plan.disconnectAll)
        assertEquals(setOf("server-a", "server-c"), plan.connectServerIds)
    }

    @Test
    fun logoutDisconnectsAllWithoutStartingReplacement() {
        val plan =
            reconcileRealtimeProcessDesiredSession(
                previous = desired(serverIds = setOf("server-a", "server-b")),
                next = null,
            )

        assertTrue(plan.disconnectAll)
        assertEquals(emptySet(), plan.connectServerIds)
    }

    @Test
    fun explicitReconnectReissuesEveryDesiredServerWithoutRetiringIdentity() {
        val current = desired(serverIds = setOf("server-a", "server-b"))

        val plan = reconcileRealtimeProcessDesiredSession(current, current, forceReconnect = true)

        assertFalse(plan.disconnectAll)
        assertEquals(emptySet(), plan.disconnectServerIds)
        assertEquals(setOf("server-a", "server-b"), plan.connectServerIds)
        assertTrue(plan.forceReconnect)
    }

    private fun desired(
        environmentId: String = "https://api.example",
        socketUrl: String = "https://socket.example",
        principalId: String = "account-1",
        authGeneration: Long = 7L,
        connectionFamilyId: String = "family-1",
        accessToken: String = "token-1",
        serverIds: Set<String>,
    ): RealtimeProcessDesiredSession =
        RealtimeProcessDesiredSession(
            environmentId = environmentId,
            socketUrl = socketUrl,
            principalId = principalId,
            authGeneration = authGeneration,
            connectionFamilyId = connectionFamilyId,
            accessToken = accessToken,
            serverIds = serverIds,
        )
}
