package build.raft.app.network

import build.raft.app.RaftCoreInternalApi
import build.raft.app.data.NetworkTransportPreferenceAvailability
import build.raft.app.data.NetworkTransportPreferenceSnapshot
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

@RaftCoreInternalApi
data class RaftNetworkTransportPlatformPolicy(
    val platformLabel: String,
    /** Platform has no Ktor delegate; off means curl with H3 not requested. */
    val curlRequired: Boolean,
    val appOwnedTrustRequired: Boolean,
    val readyDetail: String
)

@RaftCoreInternalApi
const val RAFT_HTTP1_ONLY_METADATA_KEY = "com.raft.mobile.transport.http1_only"
@RaftCoreInternalApi
const val RAFT_HTTP1_ONLY_METADATA_VALUE = "true"

@RaftCoreInternalApi
fun NetworkRequest.flagHttp1Only(warningLogger: (String) -> Unit) {
    val metadata = metadata
    if (metadata is MutableMap<String, String>) {
        metadata[RAFT_HTTP1_ONLY_METADATA_KEY] = RAFT_HTTP1_ONLY_METADATA_VALUE
    } else {
        warningLogger(
            "slock_networkkmm_http1_only_flag_failed metadata=${metadata::class.simpleName}"
        )
    }
}

@RaftCoreInternalApi
fun NetworkRequest.isHttp1Only(): Boolean =
    metadata[RAFT_HTTP1_ONLY_METADATA_KEY] == RAFT_HTTP1_ONLY_METADATA_VALUE

@RaftCoreInternalApi
expect fun raftNetworkTransportPlatformPolicy(): RaftNetworkTransportPlatformPolicy

/** Mirrors the process-global transport policy into non-NetworkClient platform consumers. */
@RaftCoreInternalApi
expect fun raftApplyPlatformHttp3Preference(
    enabled: Boolean,
    generation: Long
): Boolean

/**
 * Process-owned transport policy: curl over HTTP/2 for new requests, HTTP/3 only when the user turns it on
 * in Settings. Each new request reads the committed snapshot without reconstructing NetworkClient instances.
 */
@RaftCoreInternalApi
class RaftNetworkTransportController(
    private val platformPolicy: RaftNetworkTransportPlatformPolicy = raftNetworkTransportPlatformPolicy(),
    private val nativeStatusProvider: () -> NetworkCurlNativeStatus = { VBTransportCurl.nativeStatus },
    private val curlConfiguredProvider: () -> Boolean = { VBTransportCurl.configured },
    private val curlConfigurationFailureProvider: () -> Pair<NetworkCurlConfigurationFailureReason, String?> = {
        VBTransportCurl.configurationStatus.let { it.failureReason to it.detail }
    },
    private val platformPreferencePublisher: (Boolean, Long) -> Boolean =
        ::raftApplyPlatformHttp3Preference
) {
    private val platformPolicyPublicationLock = SynchronizedObject()
    private val policyGeneration = atomic(0L)
    private val publishedPlatformPolicy = atomic<PublishedPlatformPolicy?>(null)

    fun getPreferenceSync(): NetworkTransportPreferenceSnapshot =
        synchronized(platformPolicyPublicationLock) {
            committedSnapshotLocked().also { publishPlatformPolicyLocked(it) }
        }

    fun selectionFor(request: NetworkRequest): NetworkEngineSelection {
        val snapshot = getPreferenceSync()
        val http1Only = request.isHttp1Only()
        // Request-owned and copied by NetworkKMM. A later Settings change does
        // not mutate this call, including auth/retry attempts owned by it.
        // Presigned direct-upload PUTs are pinned to HTTP/1.1: their R2 S3
        // endpoints must not attempt an HTTP/3 upgrade even when the user
        // turned HTTP/3 on.
        request.setCurlHttp3Enabled(if (http1Only) false else snapshot.effectiveHttp3Enabled)
        return NetworkEngineSelection(
            requestedEngine =
                if (http1Only || snapshot.curlRequestedForNewCalls) {
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
            hostSelectionTag = "remote-generation-${snapshot.generation}"
        )
    }

    private fun snapshot(intent: TransportIntent, generationValue: Long): NetworkTransportPreferenceSnapshot {
        val readiness = currentReadiness()
        val ready = readiness.availability == NetworkTransportPreferenceAvailability.Ready
        val effective = intent.http3Requested && ready
        return NetworkTransportPreferenceSnapshot(
            requestedHttp3Enabled = intent.http3Requested,
            effectiveHttp3Enabled = effective,
            curlRequestedForNewCalls = platformPolicy.curlRequired || (intent.curlRequested && ready),
            available = ready,
            availability = readiness.availability,
            detail = readiness.detail,
            generation = generationValue
        )
    }

    private fun committedSnapshotLocked(): NetworkTransportPreferenceSnapshot {
        return snapshot(transportIntent(), policyGeneration.value)
    }

    // Task #3: HTTP/3 is off unless the user turns it on in Settings. Curl is always the requested
    // engine; [snapshot] still falls back to Ktor where the platform allows it and curl is not ready.
    private fun transportIntent(): TransportIntent =
        TransportIntent(curlRequested = true, http3Requested = RaftHttp3Policy.resolve())

    /**
     * Task #3: called after the user flips the HTTP/3 switch. The choice is read live through
     * [RaftHttp3Policy], so this only advances the generation and republishes the platform
     * policy.
     */
    fun onHttp3UserChoiceChanged(): String? = synchronized(platformPolicyPublicationLock) {
        val nextGeneration = policyGeneration.value + 1L
        val targetSnapshot = snapshot(transportIntent(), nextGeneration)
        if (!publishPlatformPolicyLocked(targetSnapshot, force = true)) {
            return@synchronized "network_transport_http3_choice applied=false"
        }
        policyGeneration.value = nextGeneration
        "network_transport_http3_choice applied=true requested=${targetSnapshot.requestedHttp3Enabled} " +
            "effective=${targetSnapshot.effectiveHttp3Enabled} generation=$nextGeneration"
    }

    private fun publishPlatformPolicyLocked(
        snapshot: NetworkTransportPreferenceSnapshot,
        force: Boolean = false
    ): Boolean {
        val current = publishedPlatformPolicy.value
        if (!force && current != null && snapshot.generation < current.generation) {
            // A request that read the old generation while a mutation was committing must not
            // overwrite the newer host policy.
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

    private fun currentReadiness(): RaftNetworkTransportReadiness {
        val native = nativeStatusProvider()
        if (!native.linked) {
            return RaftNetworkTransportReadiness(
                NetworkTransportPreferenceAvailability.NativeUnavailable,
                native.detail ?: "${platformPolicy.platformLabel} curl native runtime is not linked."
            )
        }
        if (!native.http3FeatureAvailable) {
            return RaftNetworkTransportReadiness(
                NetworkTransportPreferenceAvailability.Http3Unsupported,
                native.detail ?: "The linked curl runtime does not report HTTP/3 support."
            )
        }
        if (platformPolicy.appOwnedTrustRequired && !curlConfiguredProvider()) {
            val (failureReason, failureDetail) = curlConfigurationFailureProvider()
            return RaftNetworkTransportReadiness(
                NetworkTransportPreferenceAvailability.TrustUnavailable,
                failureDetail
                    ?: if (failureReason == NetworkCurlConfigurationFailureReason.NONE) {
                        "The pinned curl CA bundle has not been staged yet."
                    } else {
                        "The pinned curl CA bundle failed validation ($failureReason)."
                    }
            )
        }
        return RaftNetworkTransportReadiness(
            NetworkTransportPreferenceAvailability.Ready,
            platformPolicy.readyDetail
        )
    }
}

private data class RaftNetworkTransportReadiness(
    val availability: NetworkTransportPreferenceAvailability,
    val detail: String
)

private data class PublishedPlatformPolicy(
    val generation: Long,
    val enabled: Boolean
)

private data class TransportIntent(
    val curlRequested: Boolean,
    val http3Requested: Boolean
)

private object RaftNetworkTransportRegistry {
    private val controller = atomic<RaftNetworkTransportController?>(null)

    fun install(value: RaftNetworkTransportController) {
        controller.value = value
    }

    fun currentOrNull(): RaftNetworkTransportController? = controller.value
}

@RaftCoreInternalApi
fun installRaftNetworkTransportController(
    controller: RaftNetworkTransportController
) {
    RaftNetworkTransportRegistry.install(controller)
}

@RaftCoreInternalApi
fun currentRaftNetworkTransportController(): RaftNetworkTransportController? =
    RaftNetworkTransportRegistry.currentOrNull()

/** Shared by ordinary API calls, binary downloads and attachment streams. */
@RaftCoreInternalApi
fun raftNetworkClientConfig(
    defaultPolicy: com.tencent.kmm.network.export.NetworkRequestPolicy =
        com.tencent.kmm.network.export.NetworkRequestPolicy(),
    diagnosticsLogger: (String) -> Unit = {}
): NetworkClientConfig =
    NetworkClientConfig(
        defaultPolicy = defaultPolicy,
        engineSelector = { request ->
            val controller = currentRaftNetworkTransportController()
            if (controller != null) {
                controller.selectionFor(request)
            } else {
                val policy = raftNetworkTransportPlatformPolicy()
                // Task #3: only an explicit user choice turns HTTP/3 on.
                val http3 = RaftHttp3Policy.resolve()
                request.setCurlHttp3Enabled(http3)
                NetworkEngineSelection(
                    requestedEngine =
                        if (policy.curlRequired || http3) {
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
                            "configGeneration=${diagnostics.hostSelectionTag?.removePrefix("remote-generation-") ?: "none"} " +
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
                            "configGeneration=${diagnostics.selection.hostSelectionTag?.removePrefix("remote-generation-") ?: "none"} " +
                            "unavailableReason=${diagnostics.selection.unavailableReason ?: "none"} " +
                            "unavailableDetail=${diagnostics.selection.unavailableDetail.toSafeNetworkTransportToken()} " +
                            "nativeLinked=${native.linked} " +
                            "http3FeatureAvailable=${native.http3FeatureAvailable} " +
                            "nativeDetail=${native.detail.toSafeNetworkTransportToken()} " +
                            "http3Requested=${diagnostics.http3Requested ?: "none"} " +
                            "negotiatedProtocol=${diagnostics.negotiatedProtocol} " +
                            "status=${diagnostics.statusCode ?: "none"} " +
                            "errorKind=${diagnostics.errorKind ?: "none"} " +
                            "transportId=${diagnostics.timing.transportRequestId ?: "none"} " +
                            // task #372 / #1135: clamped connect budget actually handed to the
                            // engine (0.1.0-raft.38); 0 = route did not report, shown as none.
                            "effectiveConnectTimeoutMillis=${diagnostics.timing.effectiveConnectTimeoutMillis.takeIf { it > 0 }?.toString() ?: "none"}"
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
