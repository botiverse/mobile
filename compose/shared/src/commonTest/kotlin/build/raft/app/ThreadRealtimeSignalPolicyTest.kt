package build.raft.app

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.data.RealtimeConnectionWitness
import build.raft.app.model.Heartbeat
import build.raft.app.model.RealtimeRefreshRequired
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThreadRealtimeSignalPolicyTest {
    @Test
    fun heartbeatIsDeliveredToActiveThreadForGapRecovery() {
        assertTrue(shouldDeliverToActiveThreadRealtimeSignal(Heartbeat(seq = 42)))
    }

    @Test
    fun standaloneThreadOwnsRealtimeIngressWhenAppShellIsAbsent() {
        assertTrue(shouldUseStandaloneThreadRealtimeIngress(hasGuardedAppShellSinks = false))
    }

    @Test
    fun appShellKeepsSoleRealtimeIngressWhenItsSinksAreMounted() {
        assertFalse(shouldUseStandaloneThreadRealtimeIngress(hasGuardedAppShellSinks = true))
    }

    @Test
    fun standaloneThreadDecodesRawEnvelopeAndForwardsWitness() = runBlocking {
        var acceptedServerId: String? = null
        var acceptedEvent: RealtimeRefreshRequired? = null
        var acceptedWitness: RealtimeConnectionWitness? = null
        val envelope =
            JSONObject()
                .put("eventName", RealtimeSocketEvents.ChannelUpdated)
                .put("rawPayload", """{"serverId":"server-1","channelId":"channel-1"}""")
                .put("serverId", "server-1")
                .put(SlockRealtimeNotifyContract.KeyAuthorityScopeLeaseId, "scope-1")
                .put(SlockRealtimeNotifyContract.KeyAuthorityPrincipalId, "principal-1")
                .put(SlockRealtimeNotifyContract.KeyAuthorityAuthGeneration, 1L)
                .put(SlockRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, "family-1")

        val accepted =
            acceptStandaloneThreadRealtimeEvent(
                eventData = envelope,
                session = null,
                mapper = RealtimeEventMapper(),
                hasGuardedAppShellSinks = false,
                log = {},
            ) { witness, serverId, event ->
                acceptedWitness = witness
                acceptedServerId = serverId
                acceptedEvent = assertIs<RealtimeRefreshRequired>(event)
                true
            }

        assertTrue(accepted)
        assertEquals("server-1", acceptedServerId)
        assertEquals("channel-1", acceptedEvent?.channelId?.value)
        assertEquals("scope-1", acceptedWitness?.scopeLeaseId)
        assertEquals("principal-1", acceptedWitness?.principalId)
        assertEquals(1L, acceptedWitness?.authGeneration)
        assertEquals("family-1", acceptedWitness?.connectionFamilyId)
    }

    @Test
    fun appShellPresenceRejectsRawEnvelopeBeforeDecodeOrAccept() = runBlocking {
        var acceptCalls = 0

        val accepted =
            acceptStandaloneThreadRealtimeEvent(
                eventData = JSONObject(),
                session = null,
                mapper = RealtimeEventMapper(),
                hasGuardedAppShellSinks = true,
                log = {},
            ) { _, _, _ ->
                acceptCalls += 1
                true
            }

        assertFalse(accepted)
        assertEquals(0, acceptCalls)
    }
}
