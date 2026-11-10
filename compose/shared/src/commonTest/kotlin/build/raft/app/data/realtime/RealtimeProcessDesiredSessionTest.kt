package build.raft.app.data.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeProcessDesiredSessionTest {
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
