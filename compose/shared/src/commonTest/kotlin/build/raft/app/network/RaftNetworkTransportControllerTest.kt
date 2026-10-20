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
    fun unresolvedRemotePolicyDefaultsToCurlHttp2() {
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

    @Test
    fun presignedUploadHttp1OnlyAlsoOverridesExplicitCurlH3RemoteVariant() {
        val controller = readyController(curlRequired = false)
        controller.updateRemoteEnvironment("production")
        controller.updateRemoteActiveSelection("shell-a", "server-a")
        controller.applyRemoteEvaluation("production", 1L, "server-a", true, "curl_h3")

        val request = NetworkRequest(url = "https://example.test/presigned").apply { flagHttp1Only {} }

        assertEquals(NetworkTransportEngine.CURL, controller.selectionFor(request).requestedEngine)
        assertEquals(false, request.curlHttp3EnabledOverride)
    }

    @Test
    fun androidRemoteVariantsMapToExactEngineAndHttp3Policy() {
        val controller = readyController(curlRequired = false)
        controller.updateRemoteEnvironment("production")
        controller.updateRemoteActiveSelection("shell-a", "server-a")

        controller.applyRemoteEvaluation("production", 1L, "server-a", true, "curl_h2")
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)

        controller.applyRemoteEvaluation("production", 2L, "server-a", true, "ktor")
        assertSelection(controller, NetworkTransportEngine.KTOR, http3 = false)

        controller.applyRemoteEvaluation("production", 3L, "server-a", true, "curl_h3")
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = true)
    }

    @AfterTest
    fun resetHttp3Policy() {
        RaftHttp3Policy.resetForTest()
    }

    // Task #3: the Settings switch overrides the server-sent default in both directions, and an
    // unset switch keeps following whatever the server sends.
    @Test
    fun http3UserChoiceOverridesTheServerVariant() {
        val controller = readyController(curlRequired = false)
        controller.updateRemoteEnvironment("production")
        controller.updateRemoteActiveSelection("shell-a", "server-a")

        var choice: Boolean? = null
        RaftHttp3Policy.bindUserChoice { choice }

        // Server says curl_h2; the user turns HTTP/3 on.
        controller.applyRemoteEvaluation("production", 1L, "server-a", true, "curl_h2")
        choice = true
        controller.onHttp3UserChoiceChanged()
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = true)

        // Server says curl_h3; the user turns HTTP/3 off, and curl stays the engine.
        controller.applyRemoteEvaluation("production", 2L, "server-a", true, "curl_h3")
        choice = false
        controller.onHttp3UserChoiceChanged()
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)

        // Switch back to unset: follows the server again (still curl_h3).
        choice = null
        controller.onHttp3UserChoiceChanged()
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = true)
    }

    @Test
    fun http3UserChoiceOnKtorVariantRequestsCurlBecauseHttp3ImpliesCurl() {
        val controller = readyController(curlRequired = false)
        controller.updateRemoteEnvironment("production")
        controller.updateRemoteActiveSelection("shell-a", "server-a")
        RaftHttp3Policy.bindUserChoice { true }

        controller.applyRemoteEvaluation("production", 1L, "server-a", true, "ktor")
        controller.onHttp3UserChoiceChanged()
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = true)
    }

    @Test
    fun http3UserChoiceStillYieldsToHttp1OnlyRequests() {
        val controller = readyController(curlRequired = false)
        controller.updateRemoteEnvironment("production")
        controller.updateRemoteActiveSelection("shell-a", "server-a")
        RaftHttp3Policy.bindUserChoice { true }
        controller.applyRemoteEvaluation("production", 1L, "server-a", true, "curl_h2")
        controller.onHttp3UserChoiceChanged()

        val request = NetworkRequest(url = "https://example.test/presigned").apply { flagHttp1Only {} }
        controller.selectionFor(request)
        assertEquals(false, request.curlHttp3EnabledOverride)
    }

    @Test
    fun ohosKtorVariantSafelyMapsToCurlH2() {
        val controller = readyController(curlRequired = true)
        controller.updateRemoteEnvironment("production")
        controller.updateRemoteActiveSelection("shell-a", "server-a")
        controller.applyRemoteEvaluation("production", 1L, "server-a", true, "ktor")

        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)
        assertTrue(controller.getPreferenceSync().curlRequestedForNewCalls)
    }

    @Test
    fun staleEvaluationCannotOverwriteNewerVariant() {
        val controller = readyController(curlRequired = false)
        controller.updateRemoteEnvironment("production")
        controller.updateRemoteActiveSelection("shell-a", "server-a")
        controller.applyRemoteEvaluation("production", 20L, "server-a", true, "curl_h2")
        controller.applyRemoteEvaluation("production", 19L, "server-a", true, "ktor")

        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)
    }

    @Test
    fun conflictingActiveServerVariantsFailSafeWithoutOscillation() {
        val controller = readyController(curlRequired = false)
        controller.updateRemoteEnvironment("production")
        controller.updateRemoteActiveSelection("shell-a", "server-a")
        controller.updateRemoteActiveSelection("shell-b", "server-b")
        controller.applyRemoteEvaluation("production", 1L, "server-a", true, "curl_h3")
        controller.applyRemoteEvaluation("production", 2L, "server-b", true, "curl_h2")

        assertSelection(controller, NetworkTransportEngine.KTOR, http3 = false)

        controller.updateRemoteActiveSelection("shell-b", null)
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = true)
    }

    @Test
    fun blankShellScopeSelectionIsIgnoredInsteadOfSharingOneLegacySlot() {
        val controller = readyController(curlRequired = false)
        controller.updateRemoteEnvironment("production")

        // Positive control: a named scope makes server-a the active server, so its variant applies.
        controller.updateRemoteActiveSelection("shell-a", "server-a")
        controller.applyRemoteEvaluation("production", 1L, "server-a", true, "curl_h2")
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)

        // A blank scope is not a caller identity: it cannot register server-b as active
        // (which would fail-safe the conflict to KTOR) and cannot clear shell-a's selection.
        assertEquals(null, controller.updateRemoteActiveSelection("  ", "server-b"))
        controller.applyRemoteEvaluation("production", 2L, "server-b", true, "ktor")
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)
        assertEquals(null, controller.updateRemoteActiveSelection("", null))
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)

        // The same operations under a real scope do mutate state, proving the assertions above bite.
        controller.updateRemoteActiveSelection("shell-b", "server-b")
        assertSelection(controller, NetworkTransportEngine.KTOR, http3 = false)
    }

    @Test
    fun disabledOrInvalidEvaluationKeepsDefaultCurlH2() {
        val controller = readyController(curlRequired = false)
        controller.updateRemoteEnvironment("production")
        controller.updateRemoteActiveSelection("shell-a", "server-a")

        controller.applyRemoteEvaluation("production", 1L, "server-a", false, "ktor")
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)

        controller.applyRemoteEvaluation("production", 2L, "server-a", true, "not-a-transport")
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)
    }

    @Test
    fun failedPlatformPublicationDoesNotCommitRemoteGeneration() {
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

        val diagnostics = controller.updateRemoteEnvironment("production")

        assertTrue(diagnostics.orEmpty().contains("applied=false"))
        assertEquals(0L, controller.getPreferenceSync().generation)
        assertSelection(controller, NetworkTransportEngine.CURL, http3 = false)
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
