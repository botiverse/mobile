package build.raft.app.data

import build.raft.app.network.RaftHttpTransport

enum class NetworkTransportPreferenceAvailability {
    Ready,
    NativeUnavailable,
    TrustUnavailable,
    ProxyUnavailable,
    RuntimeUnavailable
}

/**
 * What the next app request will use. [requestedTransport] is the policy (Ktor unless the Developer
 * curl override is on); [activeTransport] is what actually carries new requests after the curl
 * readiness check. [curlAvailability] / [detail] describe the curl runtime, which only matters while
 * the override is on.
 */
data class NetworkTransportPreferenceSnapshot(
    val requestedTransport: RaftHttpTransport,
    val activeTransport: RaftHttpTransport,
    val curlAvailability: NetworkTransportPreferenceAvailability,
    val detail: String,
    val generation: Long
) {
    /** Engine policy for the next NetworkKMM request; actual selection is emitted by NetworkKMM diagnostics. */
    val curlRequestedForNewCalls: Boolean
        get() = activeTransport == RaftHttpTransport.Curl
}
