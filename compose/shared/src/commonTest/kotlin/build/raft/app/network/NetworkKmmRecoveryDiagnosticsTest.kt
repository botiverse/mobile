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
                        staleH2ConcurrentRequestCount = 2,
                        curlCompletionInfoVersion = 1,
                        curlNameLookupTimingAvailable = true,
                        curlConnectTimingAvailable = true,
                        curlPreTransferTimingAvailable = true,
                        curlStartTransferTimingAvailable = true,
                        curlTotalTimingAvailable = false,
                        curlTlsTimingState = "reused_connection"
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
        assertContains(diagnostics, "curlCompletionInfoVersion=1")
        assertContains(diagnostics, "curlNameLookupTimingAvailable=true")
        assertContains(diagnostics, "curlConnectTimingAvailable=true")
        assertContains(diagnostics, "curlPreTransferTimingAvailable=true")
        assertContains(diagnostics, "curlStartTransferTimingAvailable=true")
        assertContains(diagnostics, "curlTotalTimingAvailable=false")
        assertContains(diagnostics, "curlTlsTimingState=reused_connection")
    }

    @Test
    fun curlCompletionFieldsRenderAsNoneWhenNativeArtifactLacksThem() {
        val response =
            NetworkResponse(
                request = NetworkRequest(),
                statusCode = 200,
                headers = emptyMap(),
                body = NetworkResponseBody(),
                timing = VBTransportElapseStatistics()
            )

        val diagnostics = response.networkKmmDiagnostics()

        assertContains(diagnostics, "curlCompletionInfoVersion=none")
        assertContains(diagnostics, "curlNameLookupTimingAvailable=none")
        assertContains(diagnostics, "curlConnectTimingAvailable=none")
        assertContains(diagnostics, "curlPreTransferTimingAvailable=none")
        assertContains(diagnostics, "curlStartTransferTimingAvailable=none")
        assertContains(diagnostics, "curlTotalTimingAvailable=none")
        assertContains(diagnostics, "curlTlsTimingState=none")
    }

    @Test
    fun effectiveConnectTimeoutMillisIsReportedWhenEngineRouteProvidesIt() {
        val response =
            NetworkResponse(
                request = NetworkRequest(),
                statusCode = 200,
                headers = emptyMap(),
                body = NetworkResponseBody(),
                timing = VBTransportElapseStatistics(effectiveConnectTimeoutMillis = 15_000)
            )

        val diagnostics = response.networkKmmDiagnostics()

        assertContains(diagnostics, "effectiveConnectTimeoutMillis=15000")
    }

    @Test
    fun effectiveConnectTimeoutMillisRendersAsNoneWhenRouteDoesNotReport() {
        val response =
            NetworkResponse(
                request = NetworkRequest(),
                statusCode = 200,
                headers = emptyMap(),
                body = NetworkResponseBody(),
                timing = VBTransportElapseStatistics()
            )

        val diagnostics = response.networkKmmDiagnostics()

        // 0 means "engine route did not report" (e.g. iOS native curl / streaming
        // routes without a timing assembly point) and must not be read as a real 0ms.
        assertContains(diagnostics, "effectiveConnectTimeoutMillis=none")
    }
}
