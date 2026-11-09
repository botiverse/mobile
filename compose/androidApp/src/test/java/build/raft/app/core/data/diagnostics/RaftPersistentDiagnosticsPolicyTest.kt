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

    @Test
    fun skipsRoutineKtorRequestStartAndFinish() {
        assertFalse(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "warn",
                "shared_network slock_http_execute_start path=https://api.raft.build/api/product-events/batch " +
                    "method=POST priority=Background jsonBody=true requestBytes=478 engine=ktor_okhttp " +
                    "lane=background_nonidempotent configTotalTimeoutMillis=30000 configConnectTimeoutMillis=5000 " +
                    "clientRequestId=mobile-653b769f2 headers=none"
            )
        )
        assertFalse(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "info",
                "shared_network slock_http_execute_finish path=https://api.raft.build/api/channels/inbox " +
                    "method=GET priority=Foreground requestBytes=0 responseBytes=11372 errorBytes=0 code=200 " +
                    "durationMs=765 engine=ktor_okhttp status=200 kind=none rawCode=none detail=none totalMs=764"
            )
        )
        assertFalse(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "info",
                "shared_network slock_http_multipart_finish path=https://api.raft.build/api/upload code=201 durationMs=900"
            )
        )
    }

    @Test
    fun keepsKtorFailuresCancellationsErrorsSlowAndAuthRequests() {
        val base = "shared_network slock_http_execute_finish path=https://api.raft.build/api/channels/inbox method=GET "
        assertTrue(RaftPersistentDiagnosticsPolicy.shouldPersist("warn", base + "code=503 durationMs=90 status=503"))
        assertTrue(RaftPersistentDiagnosticsPolicy.shouldPersist("warn", base + "code=200 durationMs=4500 status=200"))
        assertTrue(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "warn",
                base + "responseBytes=0 errorBytes=0 code=0 durationMs=12 status=none kind=CANCELLED rawCode=CANCELED"
            )
        )
        assertTrue(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "info",
                "shared_network slock_http_execute_finish path=https://api.raft.build/api/auth/refresh code=200 durationMs=90"
            )
        )
        assertTrue(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "warn",
                "shared_network slock_http_execute_failed path=https://api.raft.build/api/channels durationMs=10 class=IOException"
            )
        )
        assertTrue(
            RaftPersistentDiagnosticsPolicy.shouldPersist(
                "warn",
                "shared_network slock_http_execute_retry_fresh_session lane=foreground_idempotent"
            )
        )
    }
}
