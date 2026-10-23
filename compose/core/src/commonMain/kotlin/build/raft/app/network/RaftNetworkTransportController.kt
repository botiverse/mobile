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

@RaftCoreInternalApi
data class RaftNetworkTransportPlatformPolicy(
    val platformLabel: String,
    /** The engine under Ktor on this platform, shown in Developer settings and diagnostics. */
    val ktorEngineLabel: String,
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

/**
 * Process-owned transport policy (artin 2026-10-23): Ktor carries app HTTP (OkHttp on Android,
 * NSURLSession on iOS). Curl over HTTP/2 is used only while the Developer override asks for it and the
 * curl runtime is ready; otherwise NetworkKMM's own Ktor engine runs its requests. HTTP/3 is never
 * requested. Each new request reads the committed snapshot without rebuilding NetworkClient instances.
 */
@RaftCoreInternalApi
class RaftNetworkTransportController(
    private val platformPolicy: RaftNetworkTransportPlatformPolicy = raftNetworkTransportPlatformPolicy(),
    private val nativeStatusProvider: () -> NetworkCurlNativeStatus = { VBTransportCurl.nativeStatus },
    private val curlConfiguredProvider: () -> Boolean = { VBTransportCurl.configured },
    private val curlConfigurationFailureProvider: () -> Pair<NetworkCurlConfigurationFailureReason, String?> = {
        VBTransportCurl.configurationStatus.let { it.failureReason to it.detail }
    },
    private val requestedTransport: () -> RaftHttpTransport = RaftHttpTransportPolicy::requested
) {
    private val policyGeneration = atomic(0L)

    val platformLabel: String
        get() = platformPolicy.platformLabel

    /** "Ktor · OkHttp" / "Ktor · NSURLSession" / "curl · HTTP/2": the stack new requests use. */
    fun activeTransportLabel(snapshot: NetworkTransportPreferenceSnapshot = getPreferenceSync()): String =
        when (snapshot.activeTransport) {
            RaftHttpTransport.Ktor -> "Ktor · ${platformPolicy.ktorEngineLabel}"
            RaftHttpTransport.Curl -> "curl · HTTP/2"
        }

    fun getPreferenceSync(): NetworkTransportPreferenceSnapshot {
        val requested = runCatching(requestedTransport).getOrDefault(RaftHttpTransport.Ktor)
        val readiness = currentReadiness()
        val curlReady = readiness.availability == NetworkTransportPreferenceAvailability.Ready
        return NetworkTransportPreferenceSnapshot(
            requestedTransport = requested,
            activeTransport =
                if (requested == RaftHttpTransport.Curl && curlReady) RaftHttpTransport.Curl else RaftHttpTransport.Ktor,
            curlAvailability = readiness.availability,
            detail = readiness.detail,
            generation = policyGeneration.value
        )
    }

    /**
     * Engine choice for requests that still go through NetworkKMM: the curl-override API executor,
     * binary downloads and attachment streams. Ktor unless the override is on and curl is ready.
     */
    fun selectionFor(request: NetworkRequest): NetworkEngineSelection {
        val snapshot = getPreferenceSync()
        // Request-owned and copied by NetworkKMM. Curl, when overridden in, always runs over HTTP/2;
        // presigned direct-upload PUTs (http1Only) keep their HTTP/1.1 pin inside curl.
        request.setCurlHttp3Enabled(false)
        return NetworkEngineSelection(
            requestedEngine =
                if (snapshot.curlRequestedForNewCalls) NetworkTransportEngine.CURL else NetworkTransportEngine.KTOR,
            allowedEngines = NetworkTransportEngine.entries.toSet(),
            hostSelectionTag = "remote-generation-${snapshot.generation}"
        )
    }

    /** Called after the Developer override changes. The override is read live; this only advances the generation. */
    fun onTransportOverrideChanged(): String {
        val nextGeneration = policyGeneration.incrementAndGet()
        val snapshot = getPreferenceSync()
        return "network_transport_override requested=${snapshot.requestedTransport.diagnosticsValue} " +
            "active=${snapshot.activeTransport.diagnosticsValue} curlAvailability=${snapshot.curlAvailability} " +
            "generation=$nextGeneration"
    }

    private fun currentReadiness(): RaftNetworkTransportReadiness {
        val native = nativeStatusProvider()
        if (!native.linked) {
            return RaftNetworkTransportReadiness(
                NetworkTransportPreferenceAvailability.NativeUnavailable,
                native.detail ?: "${platformPolicy.platformLabel} curl native runtime is not linked."
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
                // No controller yet (early process work, tests): Ktor, curl only by override.
                request.setCurlHttp3Enabled(false)
                NetworkEngineSelection(
                    requestedEngine =
                        if (RaftHttpTransportPolicy.curlOverrideRequested()) {
                            NetworkTransportEngine.CURL
                        } else {
                            NetworkTransportEngine.KTOR
                        },
                    allowedEngines = NetworkTransportEngine.entries.toSet()
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
