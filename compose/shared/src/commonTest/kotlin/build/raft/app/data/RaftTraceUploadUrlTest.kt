@file:OptIn(ExperimentalCoroutinesApi::class, RaftCoreInternalApi::class)

package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftWireTransferProgress
import build.raft.app.network.raftIsTelemetrySelfApiRoute
import build.raft.app.network.raftMobileApiRouteTemplate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parity with `botiverse/slock` `packages/shared/src/traceUploadUrl.ts` and
 * `packages/daemon/src/traceUploadUrl.test.ts` (`#proj-mobile:#405`).
 */
class RaftTraceUploadUrlTest {
    @Test
    fun stagingServerDefaultsToStagingTraceUploadReceiver() {
        assertEquals("https://api-aws-staging.botiverse.dev/__trace", RAFT_STAGING_TRACE_UPLOAD_URL)
        val cases =
            listOf(
                "https://api-aws-staging.botiverse.dev",
                "https://api-aws-staging.botiverse.dev/",
                "https://api-aws-staging.botiverse.dev/api/",
                "https://api-aws-staging.botiverse.dev/api",
                "HTTPS://API-AWS-STAGING.BOTIVERSE.DEV:443",
                "HTTPS://API-AWS-STAGING.BOTIVERSE.DEV:443/api/",
                "https://api-aws-staging.botiverse.dev:0443/api/",
                "https://api-aws-staging.botiverse.dev:/api/",
                "  https://api-aws-staging.botiverse.dev/api/  ",
            )
        for (serverUrl in cases) {
            assertEquals(RAFT_STAGING_TRACE_UPLOAD_URL, defaultTraceUploadUrlForServer(serverUrl), serverUrl)
        }
    }

    @Test
    fun productionServersIncludingLegacySlockHostDefaultToProductionReceiver() {
        assertEquals("https://slock-trace-upload.botiverse.dev", RAFT_DEFAULT_TRACE_UPLOAD_URL)
        assertEquals(RAFT_DEFAULT_TRACE_UPLOAD_URL, RAFT_AGENT_FEEDBACK_BASE_URL)
        val cases =
            listOf(
                "https://api.raft.build",
                "https://api.raft.build/",
                "https://api.raft.build/api/",
                "HTTPS://API.RAFT.BUILD:443",
                "HTTPS://API.RAFT.BUILD:443/api/",
                "https://api.slock.ai",
                "https://api.slock.ai/",
                "https://api.slock.ai/api/",
                "HTTPS://API.SLOCK.AI:443/api/",
            )
        for (serverUrl in cases) {
            assertEquals(RAFT_DEFAULT_TRACE_UPLOAD_URL, defaultTraceUploadUrlForServer(serverUrl), serverUrl)
        }
    }

    @Test
    fun otherServersNonHttpsNonDefaultPortUserinfoAndMalformedUrlsReturnNull() {
        val rejected =
            listOf(
                null,
                "",
                "   ",
                "not a url",
                "https://raft.example.org",
                "https://raft.example.org/api/",
                "http://localhost:13055",
                "http://localhost:13055/api/",
                "http://127.0.0.1:8080/api/",
                "http://api.raft.build",
                "http://api.raft.build/api/",
                "http://api.slock.ai/api/",
                "https://api.raft.build:8443",
                "https://api.raft.build:8443/api/",
                "https://api.raft.build:0/api/",
                "https://api.raft.build:abc/api/",
                "https://user@api.raft.build",
                "https://user:pass@api.raft.build/api/",
                "https://@api.raft.build/api/",
                "https://api.raft.build.evil.example",
                "https://x.api.raft.build",
                "https://api.raft.build.",
                "https://api.raft.build./api/",
                "https://api.raft.build\\@evil.example",
                "https://api.raft.build\\api",
                "http://api-aws-staging.botiverse.dev",
                "http://api-aws-staging.botiverse.dev/api/",
                "https://api-aws-staging.botiverse.dev:8443",
                "https://api-aws-staging.botiverse.dev:8443/api/",
                "https://user@api-aws-staging.botiverse.dev",
                "https://api-aws-staging.botiverse.dev.evil.example",
                "https://x.api-aws-staging.botiverse.dev",
                "https://api-aws-staging.botiverse.dev.",
                "https://api-aws-staging.botiverse.dev\n/api/",
            )
        for (serverUrl in rejected) {
            assertNull(defaultTraceUploadUrlForServer(serverUrl), serverUrl.toString())
        }
    }

    @Test
    fun clientTraceTransportRoutesPerEnvironmentSkipsCustomAndTracksRuntimeSwitches() = runTest {
        val environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
        val network = RecordingTraceUploadNetworkClient()
        val transport =
            RaftNetworkClientTraceTransport(
                networkClient = network,
                sessionAccount = { "user-1" },
                apiBaseUrlProvider = { environmentStore.current().apiBaseUrl },
            )
        val logged = mutableListOf<String>()
        val sender =
            RaftClientTraceEventSender(
                scope = backgroundScope,
                nowEpochMillis = { 1_794_391_200_000L },
                defaultAttrs = { mapOf("clientPlatform" to "android") },
                privacyMode = MutableStateFlow(RaftPrivacyMode.Off),
                foreground = MutableStateFlow(true),
                log = { logged += it },
            )
        sender.bindTransport(transport)
        sender.setAccount("user-1")

        // 1. Production (api.raft.build) -> production trace receiver.
        sender.emit("server-1", RaftStateTransitionTraceName, buildJsonObject { put("step", "prod") })
        advanceTimeBy(RaftClientTraceFlushDelayMillis + 1L)
        runCurrent()
        assertEquals(listOf("https://slock-trace-upload.botiverse.dev/api/web-traces"), network.rawUrls)
        assertEquals(listOf("att-1"), network.postedAttestations)
        assertEquals(1, network.attestCalls)

        // 2. Runtime switch to staging -> staging `/__trace/api/web-traces` AND fresh staging attestation
        // (never reuses production's cached attestation).
        environmentStore.select("staging")
        sender.emit("server-1", RaftStateTransitionTraceName, buildJsonObject { put("step", "staging") })
        advanceTimeBy(RaftClientTraceFlushDelayMillis + 1L)
        runCurrent()
        assertEquals(
            listOf(
                "https://slock-trace-upload.botiverse.dev/api/web-traces",
                "https://api-aws-staging.botiverse.dev/__trace/api/web-traces",
            ),
            network.rawUrls,
        )
        assertEquals(listOf("att-1", "att-2"), network.postedAttestations)
        assertEquals(2, network.attestCalls)

        // 3. Runtime switch to custom / localhost -> skip cleanly (no scope-attestation, no POST, no retry).
        environmentStore.save(
            SharedSettingsApiEnvironmentRequest(
                key = SharedApiEnvironmentStore.CustomKey,
                label = "Local",
                apiBaseUrl = "http://localhost:13055/api/",
                socketBaseUrl = "http://localhost:13055",
                webUrl = "http://localhost:5173",
            )
        )
        sender.emit("server-1", RaftStateTransitionTraceName, buildJsonObject { put("step", "local") })
        advanceTimeBy(RaftClientTraceFlushDelayMillis + 1L)
        runCurrent()
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(2, network.rawUrls.size, "custom/localhost must not post to any trace receiver")
        assertEquals(2, network.attestCalls, "custom/localhost must not mint scope-attestation")
        assertEquals(0, sender.queuedCount, "skipped batch must not stay queued for retries")
        for (reason in RaftClientTraceDropReason.entries) {
            assertEquals(0, sender.droppedNotYetReported(reason), "skipped batch must not count as a failure drop")
        }
        assertTrue(logged.any { it.startsWith("client_trace_skipped reason=no_receiver") }, "logged=$logged")

        // 4. Runtime switch back to legacy production host (`api.slock.ai`) -> production trace receiver.
        environmentStore.save(
            SharedSettingsApiEnvironmentRequest(
                key = SharedApiEnvironmentStore.CustomKey,
                label = "Legacy Prod",
                apiBaseUrl = "https://api.slock.ai/api/",
                socketBaseUrl = "https://api.slock.ai",
                webUrl = "https://app.slock.ai",
            )
        )
        sender.emit("server-1", RaftStateTransitionTraceName, buildJsonObject { put("step", "legacy-prod") })
        advanceTimeBy(RaftClientTraceFlushDelayMillis + 1L)
        runCurrent()
        assertEquals(
            listOf(
                "https://slock-trace-upload.botiverse.dev/api/web-traces",
                "https://api-aws-staging.botiverse.dev/__trace/api/web-traces",
                "https://slock-trace-upload.botiverse.dev/api/web-traces",
            ),
            network.rawUrls,
        )
        assertEquals(3, network.attestCalls)
    }

    @Test
    fun explicitTraceBaseUrlOverrideWinsOverCustomEnvironmentAndTrimsTrailingSlash() = runTest {
        val network = RecordingTraceUploadNetworkClient()
        val transport =
            RaftNetworkClientTraceTransport(
                networkClient = network,
                sessionAccount = { "user-1" },
                traceBaseUrl = "https://trace.example.test///",
                apiBaseUrlProvider = { "http://localhost:13055/api/" },
            )
        assertEquals("https://trace.example.test", transport.currentTraceUploadBaseUrl())

        val status =
            transport.send(
                RaftWebTraceBatchRequest(
                    attestation = "att-override",
                    events = emptyList(),
                )
            )
        assertEquals(200, status)
        assertEquals(listOf("https://trace.example.test/api/web-traces"), network.rawUrls)
    }

    @Test
    fun directTransportSendSkipsWithoutNetworkCallWhenReceiverIsNull() = runTest {
        val network = RecordingTraceUploadNetworkClient()
        val transport =
            RaftNetworkClientTraceTransport(
                networkClient = network,
                sessionAccount = { "user-1" },
                apiBaseUrlProvider = { "https://selfhosted.example.org/api/" },
            )
        assertNull(transport.currentTraceUploadBaseUrl())
        val status =
            transport.send(
                RaftWebTraceBatchRequest(
                    attestation = "unused",
                    events = emptyList(),
                )
            )
        assertEquals(0, status)
        assertTrue(network.rawUrls.isEmpty())
    }

    @Test
    fun transportReadsLiveApiBaseUrlFromSharedRaftNetworkClientByDefault() = runTest {
        val environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
        val urls = mutableListOf<String>()
        val executor =
            object : RaftHttpExecutor {
                override suspend fun execute(
                    priority: RaftRequestPriority,
                    url: String,
                    method: String,
                    headers: Map<String, String>,
                    bodyText: String?,
                    jsonBody: Boolean,
                ): RaftHttpExecutorResponse {
                    urls += url
                    return RaftHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
                }

                override suspend fun executeMultipart(
                    priority: RaftRequestPriority,
                    url: String,
                    fields: Map<String, String>,
                    files: List<MultipartFile>,
                    headers: Map<String, String>,
                    onUploadProgress: ((RaftWireTransferProgress) -> Unit)?,
                ): RaftHttpExecutorResponse =
                    RaftHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
            }
        val client =
            SharedRaftNetworkClient(
                sessionStore =
                    InMemoryRaftAuthSessionStore(
                        StoredAuthSession(
                            user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                            accessToken = "access",
                            refreshToken = "refresh",
                            selectedServerId = "server-1",
                            servers = listOf(StoredAuthServer(id = "server-1", name = "Raft")),
                        )
                    ),
                baseUrlProvider = { environmentStore.current().apiBaseUrl },
                executor = executor,
            )
        val transport = RaftNetworkClientTraceTransport(networkClient = client, sessionAccount = { "user-1" })

        assertEquals(RAFT_DEFAULT_TRACE_UPLOAD_URL, transport.currentTraceUploadBaseUrl())
        environmentStore.select("staging")
        assertEquals(RAFT_STAGING_TRACE_UPLOAD_URL, transport.currentTraceUploadBaseUrl())
        assertEquals(200, transport.send(RaftWebTraceBatchRequest(attestation = "att", events = emptyList())))
        assertEquals(listOf("https://api-aws-staging.botiverse.dev/__trace/api/web-traces"), urls)
    }

    @Test
    fun stagingTraceUploadUrlIsRecognizedAsSelfTelemetryRoute() {
        val route = raftMobileApiRouteTemplate("https://api-aws-staging.botiverse.dev/__trace/api/web-traces")
        assertEquals("/api/web-traces", route)
        assertTrue(raftIsTelemetrySelfApiRoute(route))
    }

    private class RecordingTraceUploadNetworkClient : RaftNetworkClient {
        var attestCalls = 0
        val rawUrls = mutableListOf<String>()
        val postedAttestations = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            if (path.endsWith("/scope-attestation")) {
                attestCalls += 1
                return HomeNetworkResponse(
                    code = 200,
                    bodyText = """{"attestation":"att-$attestCalls","expiresAt":"2026-11-11T10:10:00Z"}""",
                )
            }
            return HomeNetworkResponse(code = 404, bodyText = null)
        }

        override suspend fun requestRawAbsoluteUrl(
            priority: DataRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
        ): HomeNetworkResponse {
            rawUrls += url
            val marker = "\"attestation\":\""
            val start = bodyText?.indexOf(marker)?.takeIf { it >= 0 }?.plus(marker.length)
            if (start != null) {
                val end = bodyText.indexOf('"', start)
                if (end > start) postedAttestations += bodyText.substring(start, end)
            }
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
        }
    }
}
