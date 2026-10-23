package build.raft.app.data

enum class NetworkTransportPreferenceAvailability {
    Ready,
    NativeUnavailable,
    Http3Unsupported,
    TrustUnavailable,
    ProxyUnavailable,
    RuntimeUnavailable
}

/**
 * Separates the requested policy from the effective request policy. Requesting HTTP/3 never proves
 * that an individual request negotiated HTTP/3.
 */
data class NetworkTransportPreferenceSnapshot(
    val requestedHttp3Enabled: Boolean,
    val effectiveHttp3Enabled: Boolean,
    /** Engine policy for the next request; actual selection is emitted by NetworkKMM diagnostics. */
    val curlRequestedForNewCalls: Boolean,
    val available: Boolean,
    val availability: NetworkTransportPreferenceAvailability,
    val detail: String,
    val generation: Long
)
