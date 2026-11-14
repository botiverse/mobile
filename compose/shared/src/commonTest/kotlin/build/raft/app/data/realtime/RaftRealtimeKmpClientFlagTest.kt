package build.raft.app.data.realtime

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY
import build.raft.app.data.REGISTERED_SERVER_FEATURE_FLAG_KEYS
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The common Socket.IO client switch: build default (QA on), explicit server value or developer override, host-gated. */
class RaftRealtimeKmpClientFlagTest {
    private val store = InMemoryRaftKeyValueStore()

    @BeforeTest @AfterTest fun reset() = RaftRealtimeKmpClientFlag.resetForTests()

    @Test fun `the key is evaluated with the other mobile flags`() {
        assertEquals("realtime_kmp_client_v1", REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY)
        assertTrue(REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
    }

    @Test fun `off without a host adapter whatever is stored`() {
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, true)
        RaftRealtimeKmpClientFlag.setLocalOverride(store, true)
        assertFalse(RaftRealtimeKmpClientFlag.enabled(store))
    }

    @Test fun `server value applies until the developer switch is set and the switch wins both ways`() {
        RaftRealtimeKmpClientFlag.hostSupported = true
        assertFalse(RaftRealtimeKmpClientFlag.enabled(store), "default off")
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, "drafts_section_v0", true)
        assertFalse(RaftRealtimeKmpClientFlag.enabled(store), "another key is ignored")
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, true)
        assertTrue(RaftRealtimeKmpClientFlag.enabled(store))
        RaftRealtimeKmpClientFlag.setLocalOverride(store, false)
        assertFalse(RaftRealtimeKmpClientFlag.enabled(store), "local off rolls back even with the server on")
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, false)
        RaftRealtimeKmpClientFlag.setLocalOverride(store, true)
        assertTrue(RaftRealtimeKmpClientFlag.enabled(store), "local on wins over a server off")
    }

    @Test fun `a QA build default applies until the server explicitly decides`() {
        RaftRealtimeKmpClientFlag.hostSupported = true
        RaftRealtimeKmpClientFlag.buildDefaultOn = true
        assertEquals(RaftRealtimeKmpClientFlag.Decision(true, RaftRealtimeKmpClientFlag.Source.BuildDefault), RaftRealtimeKmpClientFlag.decision(store))
        for (reason in listOf("default", "missing_flag", "missing_server_unit")) {
            RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, false, reason)
            assertTrue(RaftRealtimeKmpClientFlag.enabled(store), "a no-rule evaluation ($reason) keeps the build default")
        }
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, false, "kill_switch")
        assertEquals(RaftRealtimeKmpClientFlag.Decision(false, RaftRealtimeKmpClientFlag.Source.Server), RaftRealtimeKmpClientFlag.decision(store))
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, false, "default")
        assertTrue(RaftRealtimeKmpClientFlag.enabled(store), "the rule went away: back to the build default")
        RaftRealtimeKmpClientFlag.setLocalOverride(store, false)
        assertEquals(RaftRealtimeKmpClientFlag.Decision(false, RaftRealtimeKmpClientFlag.Source.Local), RaftRealtimeKmpClientFlag.decision(store))
    }

    @Test fun `a release build stays off on a no-rule evaluation and follows an explicit server on`() {
        RaftRealtimeKmpClientFlag.hostSupported = true
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, false, "default")
        assertFalse(RaftRealtimeKmpClientFlag.enabled(store))
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, true, "client_rule")
        assertTrue(RaftRealtimeKmpClientFlag.enabled(store))
        RaftRealtimeKmpClientFlag.recordServerEvaluation(store, REALTIME_KMP_CLIENT_V1_FEATURE_FLAG_KEY, false, "server_rule")
        assertFalse(RaftRealtimeKmpClientFlag.enabled(store))
    }

    @Test fun `the build default never turns on a host without an adapter`() {
        RaftRealtimeKmpClientFlag.buildDefaultOn = true
        assertEquals(RaftRealtimeKmpClientFlag.Decision(false, RaftRealtimeKmpClientFlag.Source.Unsupported), RaftRealtimeKmpClientFlag.decision(store))
    }
}
