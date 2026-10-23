package build.raft.app.network

import com.tencent.kmm.network.export.NetworkCurlConfigurationFailureReason
import com.tencent.kmm.network.export.NetworkCurlNativeStatus
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.service.NetworkTransportEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftNetworkTransportControllerTest {
    @Test
    fun newRequestsDefaultToCurlHttp2() {
        val controller = readyController(curlRequired = false)

        val request = NetworkRequest(url = "https://example.test/default")
        assertEquals(NetworkTransportEngine.CURL, controller.selectionFor(request).requestedEngine)
        assertEquals(false, request.curlHttp3EnabledOverride)
        assertFalse(controller.getPreferenceSync().effectiveHttp3Enabled)
    }

    @Test
    fun presignedUploadHttp1OnlyOverridesDefaultCurlH2() {
        val controller = readyController(curlRequired = false)

        val request = NetworkRequest(url = "https://example.test/presigned").apply { flagHttp1Only {} }

        assertEquals(NetworkTransportEngine.CURL, controller.selectionFor(request).requestedEngine)
        assertEquals(false, request.curlHttp3EnabledOverride)
    }

    @AfterTest
    fun resetHttp3Policy() {
        RaftHttp3Policy.resetForTest()
    }

    // Task #3: the Settings switch turns HTTP/3 on and off; an unset switch means HTTP/3 off.
    @Test
    fun http3UserChoiceDecidesHttp3AndCurlStaysTheEngine() {
        val controller = readyController(curlRequired = false)

        var choice: Boolean? = null
        RaftHttp3Policy.bindUserChoice { choice }

        choice = true
        controller.onHttp3UserChoiceChanged()
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = true)

        choice = false
        controller.onHttp3UserChoiceChanged()
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)

        choice = null
        controller.onHttp3UserChoiceChanged()
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)
    }

    @Test
    fun http3UserChoiceStillYieldsToHttp1OnlyRequests() {
        val controller = readyController(curlRequired = false)
        RaftHttp3Policy.bindUserChoice { true }
        controller.onHttp3UserChoiceChanged()

        val request = NetworkRequest(url = "https://example.test/presigned").apply { flagHttp1Only {} }
        controller.selectionFor(request)
        assertEquals(false, request.curlHttp3EnabledOverride)
    }

    @Test
    fun ohosRequiresCurlHttp2() {
        val controller = readyController(curlRequired = true)

        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)
        assertTrue(controller.getPreferenceSync().curlRequestedForNewCalls)
    }

    @Test
    fun failedPlatformPublicationDoesNotCommitGeneration() {
        var calls = 0
        val controller =
            RaftNetworkTransportController(
                platformPolicy = policy(curlRequired = false),
                nativeStatusProvider = {
                    NetworkCurlNativeStatus(linked = true, http3FeatureAvailable = true, detail = "ready")
                },
                curlConfiguredProvider = { true },
                curlConfigurationFailureProvider = { NetworkCurlConfigurationFailureReason.NONE to null },
                platformPreferencePublisher = { _, _ -> ++calls != 2 }
            )
        assertEquals(0L, controller.getPreferenceSync().generation)
        RaftHttp3Policy.bindUserChoice { true }

        val diagnostics = controller.onHttp3UserChoiceChanged()

        assertTrue(diagnostics.orEmpty().contains("applied=false"))
        assertEquals(2, calls)
        assertEquals(0L, controller.getPreferenceSync().generation)
    }

    @Test
    fun unavailableCurlRuntimeFallsBackToKtorOnAndroid() {
        val controller =
            RaftNetworkTransportController(
                platformPolicy = policy(curlRequired = false),
                nativeStatusProvider = {
                    NetworkCurlNativeStatus(
                        linked = false,
                        http3FeatureAvailable = false,
                        detail = "missing test runtime"
                    )
                },
                curlConfiguredProvider = { false },
                curlConfigurationFailureProvider = { NetworkCurlConfigurationFailureReason.NONE to null },
                platformPreferencePublisher = { _, _ -> true }
            )

        val request = NetworkRequest(url = "https://example.test/fallback")
        assertEquals(NetworkTransportEngine.KTOR, controller.selectionFor(request).requestedEngine)
        assertEquals(false, request.curlHttp3EnabledOverride)
        assertFalse(controller.getPreferenceSync().available)
    }

    private fun assertSelection(
        controller: RaftNetworkTransportController,
        engine: NetworkTransportEngine,
        http3: Boolean
    ) {
        val request = NetworkRequest(url = "https://example.test/${engine.externalValue}-$http3")
        assertEquals(engine, controller.selectionFor(request).requestedEngine)
        assertEquals(http3, request.curlHttp3EnabledOverride)
    }

    private fun readyController(curlRequired: Boolean): RaftNetworkTransportController =
        RaftNetworkTransportController(
            platformPolicy = policy(curlRequired),
            nativeStatusProvider = {
                NetworkCurlNativeStatus(
                    linked = true,
                    http3FeatureAvailable = true,
                    detail = "ready"
                )
            },
            curlConfiguredProvider = { true },
            curlConfigurationFailureProvider = { NetworkCurlConfigurationFailureReason.NONE to null },
            platformPreferencePublisher = { _, _ -> true }
        )

    private fun policy(curlRequired: Boolean): RaftNetworkTransportPlatformPolicy =
        RaftNetworkTransportPlatformPolicy(
            platformLabel = if (curlRequired) "OHOS" else "Android",
            curlRequired = curlRequired,
            appOwnedTrustRequired = !curlRequired,
            readyDetail = "ready"
        )
}
