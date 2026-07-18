package build.raft.app.network

import build.raft.app.data.NetworkTransportPreferenceAvailability
import build.raft.app.data.NetworkTransportPreferenceMutationResult
import build.raft.app.data.NetworkTransportPreferenceRepository
import build.raft.app.data.NetworkTransportPreferenceSnapshot
import build.raft.app.data.NetworkTransportPreferenceStore
import com.tencent.kmm.network.export.NetworkCurlConfigurationFailureReason
import com.tencent.kmm.network.export.NetworkCurlNativeStatus
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.VBTransportCurl
import com.tencent.kmm.network.service.NetworkClientConfig
import com.tencent.kmm.network.service.NetworkEngineDiagnosticsListener
import com.tencent.kmm.network.service.NetworkEngineExecutionDiagnostics
import com.tencent.kmm.network.service.NetworkEngineSelection
import com.tencent.kmm.network.service.NetworkEngineSelectionDiagnostics
import com.tencent.kmm.network.service.NetworkTransportEngine
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SlockNetworkTransportPlatformPolicy(
    val platformLabel: String,
    val defaultHttp3Enabled: Boolean,
    /** OHOS has no Ktor delegate; off means curl with H3 not requested. */
    val curlRequired: Boolean,
    val appOwnedTrustRequired: Boolean,
    val readyDetail: String
)

internal expect fun slockNetworkTransportPlatformPolicy(): SlockNetworkTransportPlatformPolicy

/** Mirrors the app-global preference into non-NetworkClient platform consumers. */
internal expect fun slockApplyPlatformHttp3Preference(
    enabled: Boolean,
    generation: Long
): Boolean

/**
 * Process-owned transport policy. The durable value initializes a committed
 * in-memory snapshot; each new request reads that snapshot, so Settings can
 * switch transport policy without reconstructing NetworkClient instances.
 */
internal class SlockNetworkTransportController(
    private val preferenceStore: NetworkTransportPreferenceStore,
    private val platformPolicy: SlockNetworkTransportPlatformPolicy = slockNetworkTransportPlatformPolicy(),
    private val nativeStatusProvider: () -> NetworkCurlNativeStatus = { VBTransportCurl.nativeStatus },
    private val curlConfiguredProvider: () -> Boolean = { VBTransportCurl.configured },
    private val curlConfigurationFailureProvider: () -> Pair<NetworkCurlConfigurationFailureReason, String?> = {
        VBTransportCurl.configurationStatus.let { it.failureReason to it.detail }
    },
    private val platformPreferencePublisher: (Boolean, Long) -> Boolean =
        ::slockApplyPlatformHttp3Preference
) : NetworkTransportPreferenceRepository {
    private val mutationMutex = Mutex()
    private val platformPolicyPublicationLock = SynchronizedObject()
    private val committedPreference = atomic<CommittedTransportPreference?>(null)
    private val publishedPlatformPolicy = atomic<PublishedPlatformPolicy?>(null)

    override fun getPreferenceSync(): NetworkTransportPreferenceSnapshot =
        synchronized(platformPolicyPublicationLock) {
            committedSnapshotLocked().also { publishPlatformPolicyLocked(it) }
        }

    override suspend fun setHttp3Enabled(
        enabled: Boolean
    ): NetworkTransportPreferenceMutationResult = mutationMutex.withLock {
        synchronized(platformPolicyPublicationLock) {
            val committed = committedPreferenceLocked()
            val current = snapshot(committed.requestedHttp3Enabled, committed.generation)
            publishPlatformPolicyLocked(current)
            if (enabled == current.requestedHttp3Enabled) {
                return@synchronized NetworkTransportPreferenceMutationResult.Applied(current)
            }
            if (enabled && !current.available) {
                return@synchronized NetworkTransportPreferenceMutationResult.Rejected(
                    errorCode = current.availability.settingsErrorCode(),
                    snapshot = current
                )
            }

            val nextGeneration = committed.generation + 1L
            val target = snapshot(enabled, generationValue = nextGeneration)
            val persisted = runCatching { preferenceStore.writeHttp3EnabledSync(enabled) }.isSuccess
            if (!persisted) {
                // No consumer can observe the target: request selection is
                // serialized by this lock and the host policy is not published
                // until the durable write succeeds.
                return@synchronized NetworkTransportPreferenceMutationResult.Rejected(
                    errorCode = "settings_network_preference_write_failed",
                    snapshot = current
                )
            }

            if (!publishPlatformPolicyLocked(target)) {
                val preferenceRolledBack =
                    runCatching {
                        preferenceStore.writeHttp3EnabledSync(current.requestedHttp3Enabled)
                    }.isSuccess
                val platformRolledBack = publishPlatformPolicyLocked(current, force = true)
                if (!platformRolledBack) {
                    // The publisher may have applied the target before
                    // reporting failure. Forget the optimistic publication so
                    // the next committed read retries the old policy instead
                    // of being suppressed by generation/equality checks.
                    publishedPlatformPolicy.value = null
                }
                return@synchronized NetworkTransportPreferenceMutationResult.Rejected(
                    errorCode =
                        if (preferenceRolledBack) {
                            "settings_network_platform_bridge_unavailable"
                        } else {
                            "settings_network_preference_rollback_failed"
                        },
                    snapshot = current
                )
            }

            committedPreference.value =
                CommittedTransportPreference(
                    requestedHttp3Enabled = enabled,
                    generation = nextGeneration
                )
            NetworkTransportPreferenceMutationResult.Applied(target)
        }
    }

    fun selectionFor(request: NetworkRequest): NetworkEngineSelection {
        val snapshot = getPreferenceSync()
        // Request-owned and copied by NetworkKMM. A later Settings change does
        // not mutate this call, including auth/retry attempts owned by it.
        request.setCurlHttp3Enabled(snapshot.effectiveHttp3Enabled)
        return NetworkEngineSelection(
            requestedEngine =
                if (snapshot.curlRequestedForNewCalls) {
                    NetworkTransportEngine.CURL
                } else {
                    NetworkTransportEngine.KTOR
                },
            allowedEngines =
                if (platformPolicy.curlRequired) {
                    setOf(NetworkTransportEngine.CURL)
                } else {
                    NetworkTransportEngine.entries.toSet()
                },
            hostSelectionTag = "settings-generation-${snapshot.generation}"
        )
    }

    private fun snapshot(
        requested: Boolean,
        generationValue: Long
    ): NetworkTransportPreferenceSnapshot {
        val readiness = currentReadiness()
        val effective = requested && readiness.availability == NetworkTransportPreferenceAvailability.Ready
        return NetworkTransportPreferenceSnapshot(
            requestedHttp3Enabled = requested,
            effectiveHttp3Enabled = effective,
            curlRequestedForNewCalls = platformPolicy.curlRequired || effective,
            available = readiness.availability == NetworkTransportPreferenceAvailability.Ready,
            availability = readiness.availability,
            detail = readiness.detail,
            generation = generationValue
        )
    }

    private fun committedSnapshotLocked(): NetworkTransportPreferenceSnapshot {
        val committed = committedPreferenceLocked()
        return snapshot(committed.requestedHttp3Enabled, committed.generation)
    }

    private fun committedPreferenceLocked(): CommittedTransportPreference =
        committedPreference.value
            ?: CommittedTransportPreference(
                requestedHttp3Enabled =
                    preferenceStore.readHttp3EnabledSync(
                        defaultValue = platformPolicy.defaultHttp3Enabled
                    ),
                generation = 0L
            ).also { committedPreference.value = it }

    private fun publishPlatformPolicyLocked(
        snapshot: NetworkTransportPreferenceSnapshot,
        force: Boolean = false
    ): Boolean {
        val current = publishedPlatformPolicy.value
        if (!force && current != null && snapshot.generation < current.generation) {
            // A request that read the old persisted preference while a mutation
            // was committing must not overwrite the newer host policy.
            return true
        }
        if (!force &&
            current?.generation == snapshot.generation &&
            current.enabled == snapshot.effectiveHttp3Enabled
        ) {
            return true
        }
        val published =
            platformPreferencePublisher(
                snapshot.effectiveHttp3Enabled,
                snapshot.generation
            )
        if (published) {
            publishedPlatformPolicy.value =
                PublishedPlatformPolicy(
                    generation = snapshot.generation,
                    enabled = snapshot.effectiveHttp3Enabled
                )
        }
        return published
    }

    private fun currentReadiness(): SlockNetworkTransportReadiness {
        val native = nativeStatusProvider()
        if (!native.linked) {
            return SlockNetworkTransportReadiness(
                NetworkTransportPreferenceAvailability.NativeUnavailable,
                native.detail ?: "${platformPolicy.platformLabel} curl native runtime is not linked."
            )
        }
        if (!native.http3FeatureAvailable) {
            return SlockNetworkTransportReadiness(
                NetworkTransportPreferenceAvailability.Http3Unsupported,
                native.detail ?: "The linked curl runtime does not report HTTP/3 support."
            )
        }
        if (platformPolicy.appOwnedTrustRequired && !curlConfiguredProvider()) {
            val (failureReason, failureDetail) = curlConfigurationFailureProvider()
            return SlockNetworkTransportReadiness(
                NetworkTransportPreferenceAvailability.TrustUnavailable,
                failureDetail
                    ?: if (failureReason == NetworkCurlConfigurationFailureReason.NONE) {
                        "The pinned curl CA bundle has not been staged yet."
                    } else {
                        "The pinned curl CA bundle failed validation ($failureReason)."
                    }
            )
        }
        return SlockNetworkTransportReadiness(
            NetworkTransportPreferenceAvailability.Ready,
            platformPolicy.readyDetail
        )
    }
}

private data class SlockNetworkTransportReadiness(
    val availability: NetworkTransportPreferenceAvailability,
    val detail: String
)

private data class PublishedPlatformPolicy(
    val generation: Long,
    val enabled: Boolean
)

private data class CommittedTransportPreference(
    val requestedHttp3Enabled: Boolean,
    val generation: Long
)

private fun NetworkTransportPreferenceAvailability.settingsErrorCode(): String =
    when (this) {
        NetworkTransportPreferenceAvailability.Ready -> "settings_network_transport_unavailable"
        NetworkTransportPreferenceAvailability.NativeUnavailable -> "settings_network_native_unavailable"
        NetworkTransportPreferenceAvailability.Http3Unsupported -> "settings_network_http3_unsupported"
        NetworkTransportPreferenceAvailability.TrustUnavailable -> "settings_network_trust_unavailable"
        NetworkTransportPreferenceAvailability.ProxyUnavailable -> "settings_network_proxy_unavailable"
        NetworkTransportPreferenceAvailability.RuntimeUnavailable -> "settings_network_transport_unavailable"
    }

private object SlockNetworkTransportRegistry {
    private val controller = atomic<SlockNetworkTransportController?>(null)

    fun install(value: SlockNetworkTransportController) {
        controller.value = value
    }

    fun currentOrNull(): SlockNetworkTransportController? = controller.value
}

internal fun installSlockNetworkTransportController(
    controller: SlockNetworkTransportController
) {
    SlockNetworkTransportRegistry.install(controller)
}

internal fun currentSlockNetworkTransportController(): SlockNetworkTransportController? =
    SlockNetworkTransportRegistry.currentOrNull()

/** Shared by ordinary API calls, binary downloads and attachment streams. */
internal fun slockNetworkClientConfig(
    defaultPolicy: com.tencent.kmm.network.export.NetworkRequestPolicy =
        com.tencent.kmm.network.export.NetworkRequestPolicy(),
    diagnosticsLogger: (String) -> Unit = {}
): NetworkClientConfig =
    NetworkClientConfig(
        defaultPolicy = defaultPolicy,
        engineSelector = { request ->
            val controller = currentSlockNetworkTransportController()
            if (controller != null) {
                controller.selectionFor(request)
            } else {
                val policy = slockNetworkTransportPlatformPolicy()
                request.setCurlHttp3Enabled(policy.defaultHttp3Enabled)
                NetworkEngineSelection(
                    requestedEngine =
                        if (policy.curlRequired) {
                            NetworkTransportEngine.CURL
                        } else {
                            NetworkTransportEngine.KTOR
                        },
                    allowedEngines =
                        if (policy.curlRequired) {
                            setOf(NetworkTransportEngine.CURL)
                        } else {
                            NetworkTransportEngine.entries.toSet()
                        }
                )
            }
        },
        engineDiagnostics =
            object : NetworkEngineDiagnosticsListener {
                override fun onEngineSelected(diagnostics: NetworkEngineSelectionDiagnostics) {
                    val native = VBTransportCurl.nativeStatus
                    diagnosticsLogger(
                        "networkkmm_engine_selected " +
                            "requestedEngine=${diagnostics.requestedEngine?.externalValue ?: "none"} " +
                            "selectedEngine=${diagnostics.selectedEngine.externalValue} " +
                            "platformDefaultEngine=${diagnostics.platformDefaultEngine.externalValue} " +
                            "selectionReason=${diagnostics.reason} " +
                            "configGeneration=${diagnostics.hostSelectionTag?.removePrefix("settings-generation-") ?: "none"} " +
                            "unavailableReason=${diagnostics.unavailableReason ?: "none"} " +
                            "unavailableDetail=${diagnostics.unavailableDetail.toSafeNetworkTransportToken()} " +
                            "nativeLinked=${native.linked} " +
                            "http3FeatureAvailable=${native.http3FeatureAvailable} " +
                            "nativeDetail=${native.detail.toSafeNetworkTransportToken()}"
                    )
                }

                override fun onEngineCompleted(diagnostics: NetworkEngineExecutionDiagnostics) {
                    val native = VBTransportCurl.nativeStatus
                    diagnosticsLogger(
                        "networkkmm_engine_completed " +
                            "requestedEngine=${diagnostics.selection.requestedEngine?.externalValue ?: "none"} " +
                            "selectedEngine=${diagnostics.selection.selectedEngine.externalValue} " +
                            "selectionReason=${diagnostics.selection.reason} " +
                            "configGeneration=${diagnostics.selection.hostSelectionTag?.removePrefix("settings-generation-") ?: "none"} " +
                            "unavailableReason=${diagnostics.selection.unavailableReason ?: "none"} " +
                            "unavailableDetail=${diagnostics.selection.unavailableDetail.toSafeNetworkTransportToken()} " +
                            "nativeLinked=${native.linked} " +
                            "http3FeatureAvailable=${native.http3FeatureAvailable} " +
                            "nativeDetail=${native.detail.toSafeNetworkTransportToken()} " +
                            "http3Requested=${diagnostics.http3Requested ?: "none"} " +
                            "negotiatedProtocol=${diagnostics.negotiatedProtocol} " +
                            "status=${diagnostics.statusCode ?: "none"} " +
                            "errorKind=${diagnostics.errorKind ?: "none"} " +
                            "transportId=${diagnostics.timing.transportRequestId ?: "none"}"
                    )
                }
            }
    )

private fun String?.toSafeNetworkTransportToken(): String =
    this
        ?.trim()
        ?.replace(Regex("\\s+"), "_")
        ?.take(160)
        ?.takeIf { it.isNotEmpty() }
        ?: "none"
