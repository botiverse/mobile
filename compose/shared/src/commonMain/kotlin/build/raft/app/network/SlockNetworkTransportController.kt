package build.raft.app.network

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

internal data class SlockNetworkTransportPlatformPolicy(
    val platformLabel: String,
    val defaultHttp3Enabled: Boolean,
    /** OHOS has no Ktor delegate; off means curl with H3 not requested. */
    val curlRequired: Boolean,
    val appOwnedTrustRequired: Boolean,
    val readyDetail: String
)

internal const val SLOCK_HTTP1_ONLY_METADATA_KEY = "com.raft.mobile.transport.http1_only"
internal const val SLOCK_HTTP1_ONLY_METADATA_VALUE = "true"

internal fun NetworkRequest.flagHttp1Only(warningLogger: (String) -> Unit) {
    val metadata = metadata
    if (metadata is MutableMap<String, String>) {
        metadata[SLOCK_HTTP1_ONLY_METADATA_KEY] = SLOCK_HTTP1_ONLY_METADATA_VALUE
    } else {
        warningLogger(
            "slock_networkkmm_http1_only_flag_failed metadata=${metadata::class.simpleName}"
        )
    }
}

internal fun NetworkRequest.isHttp1Only(): Boolean =
    metadata[SLOCK_HTTP1_ONLY_METADATA_KEY] == SLOCK_HTTP1_ONLY_METADATA_VALUE

internal expect fun slockNetworkTransportPlatformPolicy(): SlockNetworkTransportPlatformPolicy

/** Mirrors the process-global remote policy into non-NetworkClient platform consumers. */
internal expect fun slockApplyPlatformHttp3Preference(
    enabled: Boolean,
    generation: Long
): Boolean

internal enum class SlockRemoteTransportVariant(val wireValue: String) {
    Ktor("ktor"),
    CurlH2("curl_h2"),
    CurlH3("curl_h3");

    companion object {
        fun fromWire(value: String?): SlockRemoteTransportVariant? =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }
    }
}

/**
 * Process-owned transport policy. Remote variants are reconciled across every active server selection;
 * each new request reads the committed snapshot without reconstructing NetworkClient instances.
 */
internal class SlockNetworkTransportController(
    private val platformPolicy: SlockNetworkTransportPlatformPolicy = slockNetworkTransportPlatformPolicy(),
    private val nativeStatusProvider: () -> NetworkCurlNativeStatus = { VBTransportCurl.nativeStatus },
    private val curlConfiguredProvider: () -> Boolean = { VBTransportCurl.configured },
    private val curlConfigurationFailureProvider: () -> Pair<NetworkCurlConfigurationFailureReason, String?> = {
        VBTransportCurl.configurationStatus.let { it.failureReason to it.detail }
    },
    private val platformPreferencePublisher: (Boolean, Long) -> Boolean =
        ::slockApplyPlatformHttp3Preference
) {
    private val platformPolicyPublicationLock = SynchronizedObject()
    private val policyGeneration = atomic(0L)
    private val publishedPlatformPolicy = atomic<PublishedPlatformPolicy?>(null)
    private var remoteState = RemoteTransportState()

    fun getPreferenceSync(): NetworkTransportPreferenceSnapshot =
        synchronized(platformPolicyPublicationLock) {
            committedSnapshotLocked().also { publishPlatformPolicyLocked(it) }
        }

    fun updateRemoteEnvironment(environmentId: String): String? =
        mutateRemoteState("environment") { state ->
            val normalized = environmentId.trim().trimEnd('/')
            if (state.currentEnvironmentId == normalized) state else state.copy(currentEnvironmentId = normalized)
        }

    fun updateRemoteActiveSelection(scopeId: String, serverId: String?): String? =
        mutateRemoteState("selection") { state ->
            // A shell scope id is required (SlockShellStoreScope rejects blank ids). Two anonymous
            // callers must not share one "legacy" slot and overwrite each other's active server.
            val normalizedScope = scopeId.trim()
            if (normalizedScope.isBlank()) return@mutateRemoteState state
            val normalizedServer = serverId?.trim()?.takeIf(String::isNotBlank)
            val next = state.activeServerByScope.toMutableMap()
            if (normalizedServer == null) next.remove(normalizedScope) else next[normalizedScope] = normalizedServer
            if (next == state.activeServerByScope) state else state.copy(activeServerByScope = next.toMap())
        }

    fun applyRemoteEvaluation(
        environmentId: String,
        evaluationGeneration: Long,
        serverId: String,
        enabled: Boolean,
        variant: String?
    ): String? = mutateRemoteState("evaluation") { state ->
        val normalizedEnvironment = environmentId.trim().trimEnd('/')
        val normalizedServer = serverId.trim()
        if (normalizedEnvironment.isBlank() || normalizedServer.isBlank()) return@mutateRemoteState state
        val key = RemoteEvaluationKey(normalizedEnvironment, normalizedServer)
        val current = state.evaluations[key]
        if (current != null && evaluationGeneration <= current.generation) return@mutateRemoteState state
        state.copy(
            evaluations = state.evaluations +
                (key to RemoteTransportEvaluation(evaluationGeneration, enabled, variant?.trim()?.lowercase()))
        )
    }

    fun selectionFor(request: NetworkRequest): NetworkEngineSelection {
        val snapshot = getPreferenceSync()
        val http1Only = request.isHttp1Only()
        // Request-owned and copied by NetworkKMM. A later Settings change does
        // not mutate this call, including auth/retry attempts owned by it.
        // Presigned direct-upload PUTs are pinned to HTTP/1.1: their R2 S3
        // endpoints must not attempt an HTTP/3 upgrade even when the remote
        // transport policy defaults to curl_h3.
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
        return snapshot(transportIntent(remotePolicyLocked()), policyGeneration.value)
    }

    private fun mutateRemoteState(
        source: String,
        transform: (RemoteTransportState) -> RemoteTransportState
    ): String? = synchronized(platformPolicyPublicationLock) {
        val currentState = remoteState
        val targetState = transform(currentState)
        if (targetState == currentState) return@synchronized null
        val currentGeneration = policyGeneration.value
        val currentSnapshot = snapshot(transportIntent(remotePolicyLocked(currentState)), currentGeneration)
        publishPlatformPolicyLocked(currentSnapshot)
        val nextGeneration = currentGeneration + 1L
        val targetPolicy = remotePolicyLocked(targetState)
        val targetSnapshot = snapshot(transportIntent(targetPolicy), nextGeneration)
        if (!publishPlatformPolicyLocked(targetSnapshot)) {
            if (!publishPlatformPolicyLocked(currentSnapshot, force = true)) {
                publishedPlatformPolicy.value = null
            }
            return@synchronized "network_transport_remote source=$source applied=false"
        }
        remoteState = targetState
        policyGeneration.value = nextGeneration
        "network_transport_remote source=$source applied=true variant=${targetPolicy.variant.wireValue} " +
            "resolved=${targetPolicy.resolved} conflict=${targetPolicy.conflicting} generation=$nextGeneration"
    }

    private fun remotePolicyLocked(state: RemoteTransportState = remoteState): RemoteTransportPolicy {
        val environmentId = state.currentEnvironmentId
        val activeServers = state.activeServerByScope.values.toSet()
        if (environmentId.isBlank() || activeServers.isEmpty()) return RemoteTransportPolicy.Default
        val evaluations = activeServers.map { serverId ->
            state.evaluations[RemoteEvaluationKey(environmentId, serverId)] ?: return RemoteTransportPolicy.Default
        }
        val variants = evaluations.map { evaluation ->
            if (!evaluation.enabled) {
                SlockRemoteTransportVariant.CurlH3
            } else {
                SlockRemoteTransportVariant.fromWire(evaluation.variant) ?: SlockRemoteTransportVariant.CurlH3
            }
        }.toSet()
        return if (variants.size == 1) {
            RemoteTransportPolicy(resolved = true, variant = variants.single(), conflicting = false)
        } else {
            RemoteTransportPolicy(resolved = true, variant = SlockRemoteTransportVariant.Ktor, conflicting = true)
        }
    }

    private fun transportIntent(policy: RemoteTransportPolicy): TransportIntent =
        when (policy.variant) {
            SlockRemoteTransportVariant.Ktor ->
                TransportIntent(curlRequested = platformPolicy.curlRequired, http3Requested = false)
            SlockRemoteTransportVariant.CurlH2 ->
                TransportIntent(curlRequested = true, http3Requested = false)
            SlockRemoteTransportVariant.CurlH3 ->
                TransportIntent(curlRequested = true, http3Requested = true)
        }

    private fun publishPlatformPolicyLocked(
        snapshot: NetworkTransportPreferenceSnapshot,
        force: Boolean = false
    ): Boolean {
        val current = publishedPlatformPolicy.value
        if (!force && current != null && snapshot.generation < current.generation) {
            // A request that read the old remote generation while a mutation was committing must not
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

private data class RemoteEvaluationKey(
    val environmentId: String,
    val serverId: String
)

private data class RemoteTransportEvaluation(
    val generation: Long,
    val enabled: Boolean,
    val variant: String?
)

private data class RemoteTransportState(
    val currentEnvironmentId: String = "",
    val activeServerByScope: Map<String, String> = emptyMap(),
    val evaluations: Map<RemoteEvaluationKey, RemoteTransportEvaluation> = emptyMap()
)

private data class RemoteTransportPolicy(
    val resolved: Boolean,
    val variant: SlockRemoteTransportVariant,
    val conflicting: Boolean
) {
    companion object {
        val Default = RemoteTransportPolicy(
            resolved = false,
            variant = SlockRemoteTransportVariant.CurlH3,
            conflicting = false
        )
    }
}

private data class TransportIntent(
    val curlRequested: Boolean,
    val http3Requested: Boolean
)

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
                        if (policy.curlRequired || policy.defaultHttp3Enabled) {
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
