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

    // Task #366 (artin ruling "维护8个，要是用户有20个呢？…先下掉，改成更合理的"):
    // realtime transport is scoped to the CURRENT server only. Authentication
    // grants for every server stay intact (drawer unread rides the home
    // snapshot's serverUnreadSummary, not a persistent socket per server).
    @Test
    fun currentServerScopeKeepsOnlySelectedGrantedServer() {
        assertEquals(
            setOf("server-b"),
            realtimeCurrentServerScopedServerIds(
                currentServerId = "server-b",
                grantedServerIds = setOf("server-a", "server-b", "server-c"),
            ),
        )
    }

    @Test
    fun currentServerScopeTrimsAndMatchesNormalizedIds() {
        assertEquals(
            setOf("server-a"),
            realtimeCurrentServerScopedServerIds(
                currentServerId = "  server-a  ",
                grantedServerIds = setOf(" server-a ", "server-b"),
            ),
        )
    }

    @Test
    fun blankOrNullCurrentServerYieldsNoTransport() {
        // No selected server yet → keep zero persistent sockets (never fan out
        // to every authenticated server); a later re-run reconnects the current.
        assertEquals(
            emptySet(),
            realtimeCurrentServerScopedServerIds(
                currentServerId = "",
                grantedServerIds = setOf("server-a", "server-b"),
            ),
        )
        assertEquals(
            emptySet(),
            realtimeCurrentServerScopedServerIds(
                currentServerId = null,
                grantedServerIds = setOf("server-a", "server-b"),
            ),
        )
    }

    @Test
    fun currentServerNotAmongGrantsYieldsNoTransport() {
        assertEquals(
            emptySet(),
            realtimeCurrentServerScopedServerIds(
                currentServerId = "server-z",
                grantedServerIds = setOf("server-a", "server-b"),
            ),
        )
    }

    @Test
    fun serverSwitchDisconnectsPreviousAndConnectsCurrentOnly() {
        // Desired sets are already current-scoped; switching server-a → server-b
        // must converge cleanly: drop the old socket, open exactly the new one.
        val previous = desired(serverIds = setOf("server-a"))
        val next = previous.copy(serverIds = setOf("server-b"))

        val plan = reconcileRealtimeProcessDesiredSession(previous, next)

        assertFalse(plan.disconnectAll)
        assertEquals(setOf("server-a"), plan.disconnectServerIds)
        assertEquals(setOf("server-b"), plan.connectServerIds)
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
    fun sameBindingTokenRotationReauthsWithoutIdentityReplacement() {
        // task #333: a silent token refresh bumps authGeneration + accessToken on the
        // SAME binding (environmentId / socketUrl / principalId / connectionFamilyId
        // unchanged). It must re-auth the socket (forceReconnect, re-issue desired)
        // but NOT disconnectAll / process_identity_replaced / server_removed — those
        // are reserved for a real binding swap and are what kicks the UI back to the
        // server list.
        val previous = desired(serverIds = setOf("server-a"))
        val next =
            previous.copy(
                authGeneration = previous.authGeneration + 1,
                accessToken = "token-rotated",
            )

        val plan = reconcileRealtimeProcessDesiredSession(previous, next)

        assertFalse(plan.disconnectAll)
        assertEquals(emptySet(), plan.disconnectServerIds)
        assertEquals(setOf("server-a"), plan.connectServerIds)
        assertTrue(plan.forceReconnect)
    }

    @Test
    fun tokenRotationStillDropsServersThatLeftTheDesiredSet() {
        // Same binding credential rotation AND a server leaving the desired set:
        // re-auth the kept server but still disconnect the removed one.
        val previous = desired(serverIds = setOf("server-a", "server-b"))
        val next =
            previous.copy(
                authGeneration = previous.authGeneration + 1,
                accessToken = "token-rotated",
                serverIds = setOf("server-a"),
            )

        val plan = reconcileRealtimeProcessDesiredSession(previous, next)

        assertFalse(plan.disconnectAll)
        assertEquals(setOf("server-b"), plan.disconnectServerIds)
        assertEquals(setOf("server-a"), plan.connectServerIds)
        assertTrue(plan.forceReconnect)
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

    @Test
    fun runtimeCursorAdvanceIsMonotonicServerScopedAndRejectsLateGeneration() {
        val serverA = cursorIdentity(serverId = "server-a", generation = 7L)
        val serverB = cursorIdentity(serverId = "server-b", generation = 7L)
        val runtimeA = RaftRealtimeAppScopeEngine()
        runtimeA.connectDecisionForHost(
            serverIdValue = "server-a",
            accessToken = "token-7",
            requestedLastSeq = 11L,
            forceReconnect = false,
            connectionStateName = RaftRealtimeAppScopeEngine.StateDisconnected,
        )

        val advanceA =
            realtimeRuntimeCursorAdvanceDecision(
                serverA,
                serverA,
                currentSeq = runtimeA.lastSeq,
                appliedSeq = 19L,
            )
        if (advanceA.shouldAdvance) runtimeA.advanceAppliedSeqForHost(advanceA.resumeSeq)
        val lowerA =
            realtimeRuntimeCursorAdvanceDecision(
                serverA,
                serverA,
                currentSeq = runtimeA.lastSeq,
                appliedSeq = 13L,
            )
        if (lowerA.shouldAdvance) runtimeA.advanceAppliedSeqForHost(lowerA.resumeSeq)
        val crossServer =
            realtimeRuntimeCursorAdvanceDecision(
                serverA,
                serverB,
                currentSeq = runtimeA.lastSeq,
                appliedSeq = 31L,
            )
        if (crossServer.shouldAdvance) runtimeA.advanceAppliedSeqForHost(crossServer.resumeSeq)
        val lateGeneration =
            realtimeRuntimeCursorAdvanceDecision(
                currentIdentity = serverA.copy(authGeneration = 8L, connectionFamilyId = "family-8"),
                requestIdentity = serverA,
                currentSeq = runtimeA.lastSeq,
                appliedSeq = 99L,
            )
        if (lateGeneration.shouldAdvance) runtimeA.advanceAppliedSeqForHost(lateGeneration.resumeSeq)

        assertTrue(advanceA.shouldAdvance)
        assertEquals(19L, advanceA.resumeSeq)
        assertTrue(lowerA.shouldAdvance)
        assertEquals(19L, lowerA.resumeSeq)
        assertFalse(crossServer.shouldAdvance)
        assertEquals("stale_witness", crossServer.reason)
        assertEquals(19L, crossServer.resumeSeq)
        assertFalse(lateGeneration.shouldAdvance)
        assertEquals("stale_witness", lateGeneration.reason)
        assertEquals(19L, lateGeneration.resumeSeq)
        assertEquals(19L, runtimeA.lastSeq)
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

    private fun cursorIdentity(
        serverId: String,
        generation: Long,
    ): RealtimeRuntimeCursorIdentity =
        RealtimeRuntimeCursorIdentity(
            serverId = serverId,
            scopeLeaseId = "process-lease",
            principalId = "account-1",
            authGeneration = generation,
            connectionFamilyId = "family-$generation",
        )
}
