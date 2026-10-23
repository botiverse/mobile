package build.raft.app.network

import build.raft.app.data.NetworkTransportPreferenceAvailability
import com.tencent.kmm.network.export.NetworkCurlConfigurationFailureReason
import com.tencent.kmm.network.export.NetworkCurlNativeStatus
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.service.NetworkTransportEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** artin 2026-10-23: Ktor is the default transport; curl only behind the Developer override. */
class RaftNetworkTransportControllerTest {
    @AfterTest
    fun resetPolicy() {
        RaftHttpTransportPolicy.resetForTest()
    }

    @Test
    fun newRequestsDefaultToKtorWithoutHttp3() {
        val controller = controller(curlReady = true)

        val request = NetworkRequest(url = "https://example.test/default")
        assertEquals(NetworkTransportEngine.KTOR, controller.selectionFor(request).requestedEngine)
        assertEquals(false, request.curlHttp3EnabledOverride)
        val snapshot = controller.getPreferenceSync()
        assertEquals(RaftHttpTransport.Ktor, snapshot.activeTransport)
        assertFalse(snapshot.curlRequestedForNewCalls)
        assertEquals("Ktor · OkHttp", controller.activeTransportLabel(snapshot))
    }

    @Test
    fun developerOverrideSelectsCurlOverHttp2OnlyWhileCurlIsReady() {
        var stored: String? = null
        RaftHttpTransportPolicy.bindOverride(read = { stored }, write = { stored = it })
        val ready = controller(curlReady = true, requested = RaftHttpTransportPolicy::requested)
        val notReady = controller(curlReady = false, requested = RaftHttpTransportPolicy::requested)

        assertTrue(RaftHttpTransportPolicy.setCurlOverride(true))
        assertEquals("curl", stored)
        val curlRequest = NetworkRequest(url = "https://example.test/curl").apply { flagHttp1Only {} }
        assertEquals(NetworkTransportEngine.CURL, ready.selectionFor(curlRequest).requestedEngine)
        assertEquals(false, curlRequest.curlHttp3EnabledOverride)
        assertEquals("curl · HTTP/2", ready.activeTransportLabel())
        // The override cannot strand requests on a curl runtime that is not there.
        assertEquals(NetworkTransportEngine.KTOR, notReady.selectionFor(NetworkRequest(url = "https://example.test/n")).requestedEngine)
        assertEquals(RaftHttpTransport.Curl, notReady.getPreferenceSync().requestedTransport)
        assertEquals(NetworkTransportPreferenceAvailability.NativeUnavailable, notReady.getPreferenceSync().curlAvailability)

        assertTrue(RaftHttpTransportPolicy.setCurlOverride(false))
        assertEquals(null, stored)
        assertEquals(NetworkTransportEngine.KTOR, ready.selectionFor(NetworkRequest(url = "https://example.test/k")).requestedEngine)
    }

    @Test
    fun overrideChangeAdvancesTheGeneration() {
        val controller = controller(curlReady = true)
        assertEquals(0L, controller.getPreferenceSync().generation)

        val diagnostics = controller.onTransportOverrideChanged()

        assertTrue("generation=1" in diagnostics, diagnostics)
        assertEquals(1L, controller.getPreferenceSync().generation)
    }

    @Test
    fun overridePreferenceParsesOnlyCurlAsCurl() {
        listOf(null, "", "ktor", "true", "okhttp").forEach { stored ->
            assertEquals(RaftHttpTransport.Ktor, RaftHttpTransportPolicy.parseOverride(stored), "stored=$stored")
        }
        listOf("curl", " CURL ").forEach { stored ->
            assertEquals(RaftHttpTransport.Curl, RaftHttpTransportPolicy.parseOverride(stored), "stored=$stored")
        }
        // Unbound or failing stores never move the app off Ktor.
        assertFalse(RaftHttpTransportPolicy.curlOverrideRequested())
        assertFalse(RaftHttpTransportPolicy.setCurlOverride(true))
        RaftHttpTransportPolicy.bindOverride(read = { error("broken store") }, write = {})
        assertEquals(RaftHttpTransport.Ktor, RaftHttpTransportPolicy.requested())
    }

    @Test
    fun selectingExecutorRoutesEachRequestAndBuildsCurlOnlyWhenUsed() = kotlinx.coroutines.runBlocking {
        val ktor = TaggedExecutor("ktor")
        var curlBuilt = false
        var useCurl = false
        val executor =
            RaftTransportSelectingHttpExecutor(
                ktor = ktor,
                curl = lazy { curlBuilt = true; TaggedExecutor("curl") },
                useCurl = { useCurl }
            )

        assertEquals("ktor", executor.execute(RaftRequestPriority.Visible, "https://e.test/a", "GET", emptyMap(), null, false).bodyText)
        assertFalse(curlBuilt)
        useCurl = true
        assertEquals("curl", executor.execute(RaftRequestPriority.Visible, "https://e.test/a", "GET", emptyMap(), null, false).bodyText)
        assertEquals("curl", executor.executeBytes(RaftRequestPriority.Background, "https://e.test/p", "PUT", emptyMap(), ByteArray(1)).bodyText)
        useCurl = false
        assertEquals("ktor", executor.executeMultipart(RaftRequestPriority.Foreground, "https://e.test/u", emptyMap(), emptyList(), emptyMap()).bodyText)
    }

    private class TaggedExecutor(private val tag: String) : RaftHttpExecutor {
        private fun response() = RaftHttpExecutorResponse(code = 200, bodyText = tag, errorBodyText = null)

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ) = response()

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((RaftTransferProgress) -> Unit)?
        ) = response()

        override suspend fun executeBytes(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyBytes: ByteArray,
            onUploadProgress: ((RaftTransferProgress) -> Unit)?
        ) = response()
    }

    private fun controller(
        curlReady: Boolean,
        requested: () -> RaftHttpTransport = { RaftHttpTransport.Ktor },
    ): RaftNetworkTransportController =
        RaftNetworkTransportController(
            platformPolicy =
                RaftNetworkTransportPlatformPolicy(
                    platformLabel = "Android",
                    ktorEngineLabel = "OkHttp",
                    appOwnedTrustRequired = true,
                    readyDetail = "ready"
                ),
            nativeStatusProvider = {
                NetworkCurlNativeStatus(linked = curlReady, http3FeatureAvailable = curlReady, detail = null)
            },
            curlConfiguredProvider = { curlReady },
            curlConfigurationFailureProvider = { NetworkCurlConfigurationFailureReason.NONE to null },
            requestedTransport = requested
        )
}
