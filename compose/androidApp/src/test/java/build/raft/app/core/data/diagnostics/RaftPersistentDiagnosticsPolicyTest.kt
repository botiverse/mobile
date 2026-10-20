package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftPersistentDiagnosticsPolicyTest {
    @Test
    fun skipsNormalHighFrequencyNetworkKmmFlow() {
        assertFalse(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "warn",
                "slock_networkkmm_info tag=[NXNetwork_HmTransportImpl] send GET request id=42"
            )
        )
        assertFalse(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "warn",
                "slock_networkkmm_info tag=[NXNetwork_TaskManager] execute() coroutine totalTimeout: 30000 requestId: 42"
            )
        )
        assertFalse(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "warn",
                "shared_network slock_networkkmm_execute_start path=https://api.raft.build/api/channels"
            )
        )
        assertFalse(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "warn",
                "endpoint category=channels path=channels code=200 durationMs=180 result=success"
            )
        )
    }

    @Test
    fun keepsFailuresSlowRequestsAuthAndTransportTiming() {
        assertTrue(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "warn",
                "slock_networkkmm_info tag=[NXNetwork_HmTransportImpl] response received status:404 elapsedMs:120"
            )
        )
        assertTrue(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "warn",
                "shared_network slock_networkkmm_execute_finish path=/api/channels code=200 durationMs=3200"
            )
        )
        assertTrue(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "warn",
                "endpoint category=raw_absolute_url path=https://api.raft.build/api/auth/me code=200 durationMs=90"
            )
        )
        assertTrue(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "info",
                "slock_networkkmm_info transport_timing [dns]=18 [tls]=42 totalMs=110"
            )
        )
        assertTrue(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "error",
                "slock_networkkmm_info normal-looking content"
            )
        )
    }

    @Test
    fun keepsExplicitRecoveryEvidenceEvenWithoutRelyingOnTheSlowRequestThreshold() {
        listOf(
            "staleH2Detected=true freshRetry=false connectionRolloverRateLimited=false",
            "staleH2Detected=false freshRetry=true connectionRolloverRateLimited=false",
            "staleH2Detected=false freshRetry=false connectionRolloverRateLimited=true"
        ).forEach { recoveryFields ->
            assertTrue(
                RaftPersistentDiagnosticsPolicy.shouldPersist(
                    "warn",
                    "shared_network slock_networkkmm_execute_finish code=200 totalMs=120 $recoveryFields"
                )
            )
        }
    }
}
