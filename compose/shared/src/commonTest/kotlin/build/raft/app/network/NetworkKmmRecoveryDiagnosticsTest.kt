package build.raft.app.network

import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.VBTransportElapseStatistics
import kotlin.test.Test
import kotlin.test.assertContains

class NetworkKmmRecoveryDiagnosticsTest {
    @Test
    fun recoveryOutcomeFieldsAreIncludedInRequestFinishDiagnostics() {
        val response =
            NetworkResponse(
                request = NetworkRequest(),
                statusCode = 200,
                headers = emptyMap(),
                body = NetworkResponseBody(),
                timing =
                    VBTransportElapseStatistics(
                        staleH2Detected = true,
                        connectionOrigin = "https://app.raft.build:443",
                        connectionGeneration = 7,
                        connectionShard = 3,
                        connectionIdentity = "okhttp@abc-123",
                        connectionDraining = true,
                        connectionRolloverRateLimited = false,
                        freshRetry = true,
                        freshRetryResult = "success",
                        noResponseHeadersDurationMs = 7_123.0,
                        staleH2ConcurrentRequestCount = 2
                    )
            )

        val diagnostics = response.networkKmmDiagnostics()

        assertContains(diagnostics, "staleH2Detected=true")
        assertContains(diagnostics, "connectionOrigin=https://app.raft.build:443")
        assertContains(diagnostics, "connectionShard=3")
        assertContains(diagnostics, "connectionGeneration=7")
        assertContains(diagnostics, "connectionIdentity=okhttpabc-123")
        assertContains(diagnostics, "connectionDraining=true")
        assertContains(diagnostics, "connectionRolloverRateLimited=false")
        assertContains(diagnostics, "freshRetry=true")
        assertContains(diagnostics, "freshRetryResult=success")
        assertContains(diagnostics, "noResponseHeadersDurationMs=7123")
        assertContains(diagnostics, "staleH2ConcurrentRequestCount=2")
    }
}
