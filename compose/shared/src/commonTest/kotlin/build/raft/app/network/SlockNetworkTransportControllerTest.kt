package build.raft.app.network

import build.raft.app.data.InMemorySlockKeyValueStore
import build.raft.app.data.KeyValueNetworkTransportPreferenceStore
import build.raft.app.data.NetworkTransportPreferenceAvailability
import build.raft.app.data.NetworkTransportPreferenceMutationResult
import build.raft.app.data.NetworkTransportPreferenceStore
import com.tencent.kmm.network.export.NetworkCurlConfigurationFailureReason
import com.tencent.kmm.network.export.NetworkCurlNativeStatus
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.service.NetworkTransportEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SlockNetworkTransportControllerTest {
    @Test
    fun androidToggleIsRequestLatchedWithoutClientRecreation() = runBlocking {
        val controller = readyController(defaultEnabled = false, curlRequired = false)

        val offRequest = NetworkRequest(url = "https://example.test/off")
        assertEquals(NetworkTransportEngine.KTOR, controller.selectionFor(offRequest).requestedEngine)
        assertEquals(false, offRequest.curlHttp3EnabledOverride)
        val initialGeneration = controller.getPreferenceSync().generation
        controller.setHttp3Enabled(false)
        assertEquals(initialGeneration, controller.getPreferenceSync().generation)

        val enabled = controller.setHttp3Enabled(true)
        assertIs<NetworkTransportPreferenceMutationResult.Applied>(enabled)
        val onRequest = NetworkRequest(url = "https://example.test/on")
        assertEquals(NetworkTransportEngine.CURL, controller.selectionFor(onRequest).requestedEngine)
        assertEquals(true, onRequest.curlHttp3EnabledOverride)

        val inFlightCopy = onRequest.copyMutable()
        controller.setHttp3Enabled(false)
        val offAgainRequest = NetworkRequest(url = "https://example.test/off-again")
        assertEquals(NetworkTransportEngine.KTOR, controller.selectionFor(offAgainRequest).requestedEngine)
        assertEquals(false, offAgainRequest.curlHttp3EnabledOverride)
        assertEquals(true, onRequest.curlHttp3EnabledOverride)
        assertEquals(true, inFlightCopy.curlHttp3EnabledOverride)
    }

    @Test
    fun ohosOffKeepsCurlButStopsRequestingHttp3() = runBlocking {
        val controller = readyController(defaultEnabled = true, curlRequired = true)

        val defaultRequest = NetworkRequest(url = "https://example.test/default")
        assertEquals(NetworkTransportEngine.CURL, controller.selectionFor(defaultRequest).requestedEngine)
        assertEquals(true, defaultRequest.curlHttp3EnabledOverride)

        controller.setHttp3Enabled(false)
        val disabledRequest = NetworkRequest(url = "https://example.test/disabled")
        assertEquals(NetworkTransportEngine.CURL, controller.selectionFor(disabledRequest).requestedEngine)
        assertEquals(false, disabledRequest.curlHttp3EnabledOverride)
        assertTrue(controller.getPreferenceSync().curlRequestedForNewCalls)
        assertFalse(controller.getPreferenceSync().effectiveHttp3Enabled)
    }

    @Test
    fun unavailableRuntimeRejectsEnableWithoutPersistingIt() = runBlocking {
        val values = InMemorySlockKeyValueStore()
        val controller =
            SlockNetworkTransportController(
                preferenceStore = KeyValueNetworkTransportPreferenceStore(values),
                platformPolicy = policy(defaultEnabled = false, curlRequired = false),
                nativeStatusProvider = {
                    NetworkCurlNativeStatus(
                        linked = false,
                        http3FeatureAvailable = false,
                        detail = "missing test runtime"
                    )
                },
                curlConfiguredProvider = { false },
                curlConfigurationFailureProvider = {
                    NetworkCurlConfigurationFailureReason.NONE to null
                },
                platformPreferencePublisher = { _, _ -> true }
            )

        val result = controller.setHttp3Enabled(true)

        assertIs<NetworkTransportPreferenceMutationResult.Rejected>(result)
        assertEquals(
            NetworkTransportPreferenceAvailability.NativeUnavailable,
            result.snapshot.availability
        )
        assertFalse(controller.getPreferenceSync().requestedHttp3Enabled)
        assertEquals(null, values.getString(KeyValueNetworkTransportPreferenceStore.KEY_HTTP3_ENABLED))
    }

    @Test
    fun persistenceFailureNeverPublishesTargetAndBlocksConcurrentSelectionUntilRollback() = runBlocking {
        val publications = mutableListOf<Pair<Long, Boolean>>()
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val controller =
            SlockNetworkTransportController(
                preferenceStore =
                    object : NetworkTransportPreferenceStore {
                        override fun readHttp3EnabledSync(defaultValue: Boolean): Boolean = false

                        override fun writeHttp3EnabledSync(enabled: Boolean) {
                            writeStarted.complete(Unit)
                            runBlocking { releaseWrite.await() }
                            error("simulated preference write failure")
                        }
                    },
                platformPolicy = policy(defaultEnabled = false, curlRequired = false),
                nativeStatusProvider = {
                    NetworkCurlNativeStatus(
                        linked = true,
                        http3FeatureAvailable = true,
                        detail = "ready"
                    )
                },
                curlConfiguredProvider = { true },
                curlConfigurationFailureProvider = {
                    NetworkCurlConfigurationFailureReason.NONE to null
                },
                platformPreferencePublisher = { enabled, generation ->
                    publications += generation to enabled
                    true
                }
            )

        val mutation = async(Dispatchers.Default) { controller.setHttp3Enabled(true) }
        writeStarted.await()
        assertEquals(listOf(0L to false), publications)

        val selectionStarted = CompletableDeferred<Unit>()
        val concurrentRequest = NetworkRequest(url = "https://example.test/during-failed-write")
        val concurrentSelection =
            async(Dispatchers.Default) {
                selectionStarted.complete(Unit)
                controller.selectionFor(concurrentRequest)
            }
        selectionStarted.await()
        yield()
        assertFalse(concurrentSelection.isCompleted)
        assertEquals(listOf(0L to false), publications)

        releaseWrite.complete(Unit)
        val result = mutation.await()

        val rejected = assertIs<NetworkTransportPreferenceMutationResult.Rejected>(result)
        assertEquals("settings_network_preference_write_failed", rejected.errorCode)
        assertEquals(NetworkTransportEngine.KTOR, concurrentSelection.await().requestedEngine)
        assertEquals(false, concurrentRequest.curlHttp3EnabledOverride)
        assertEquals(listOf(0L to false), publications)
        assertFalse(controller.getPreferenceSync().requestedHttp3Enabled)
        assertEquals(0L, controller.getPreferenceSync().generation)
    }

    @Test
    fun failedPlatformRollbackIsInvalidatedAndRetriedFromCommittedPolicy() = runBlocking {
        val publications = mutableListOf<Pair<Long, Boolean>>()
        val persistedWrites = mutableListOf<Boolean>()
        var persisted = false
        val controller =
            SlockNetworkTransportController(
                preferenceStore =
                    object : NetworkTransportPreferenceStore {
                        override fun readHttp3EnabledSync(defaultValue: Boolean): Boolean = persisted

                        override fun writeHttp3EnabledSync(enabled: Boolean) {
                            persistedWrites += enabled
                            persisted = enabled
                        }
                    },
                platformPolicy = policy(defaultEnabled = false, curlRequired = true),
                nativeStatusProvider = {
                    NetworkCurlNativeStatus(
                        linked = true,
                        http3FeatureAvailable = true,
                        detail = "ready"
                    )
                },
                curlConfiguredProvider = { true },
                curlConfigurationFailureProvider = {
                    NetworkCurlConfigurationFailureReason.NONE to null
                },
                platformPreferencePublisher = { enabled, generation ->
                    publications += generation to enabled
                    when (publications.size) {
                        1 -> true // Initial committed policy.
                        2 -> false // Target publication failed or was only partially applied.
                        3 -> false // Immediate forced repair also failed.
                        else -> true // A later committed read retries successfully.
                    }
                }
            )

        val result = controller.setHttp3Enabled(true)

        val rejected = assertIs<NetworkTransportPreferenceMutationResult.Rejected>(result)
        assertEquals("settings_network_platform_bridge_unavailable", rejected.errorCode)
        assertEquals(listOf(true, false), persistedWrites)
        assertFalse(persisted)
        assertEquals(
            listOf(0L to false, 1L to true, 0L to false),
            publications
        )

        val repaired = controller.getPreferenceSync()
        assertFalse(repaired.requestedHttp3Enabled)
        assertEquals(0L, repaired.generation)
        assertEquals(
            listOf(0L to false, 1L to true, 0L to false, 0L to false),
            publications
        )
    }

    private fun readyController(
        defaultEnabled: Boolean,
        curlRequired: Boolean
    ): SlockNetworkTransportController =
        SlockNetworkTransportController(
            preferenceStore = KeyValueNetworkTransportPreferenceStore(InMemorySlockKeyValueStore()),
            platformPolicy = policy(defaultEnabled, curlRequired),
            nativeStatusProvider = {
                NetworkCurlNativeStatus(
                    linked = true,
                    http3FeatureAvailable = true,
                    detail = "ready"
                )
            },
            curlConfiguredProvider = { true },
            curlConfigurationFailureProvider = {
                NetworkCurlConfigurationFailureReason.NONE to null
            },
            platformPreferencePublisher = { _, _ -> true }
        )

    private fun policy(
        defaultEnabled: Boolean,
        curlRequired: Boolean
    ): SlockNetworkTransportPlatformPolicy =
        SlockNetworkTransportPlatformPolicy(
            platformLabel = if (curlRequired) "OHOS" else "Android",
            defaultHttp3Enabled = defaultEnabled,
            curlRequired = curlRequired,
            appOwnedTrustRequired = !curlRequired,
            readyDetail = "ready"
        )
}
