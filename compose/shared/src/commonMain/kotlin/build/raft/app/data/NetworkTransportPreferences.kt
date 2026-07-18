package build.raft.app.data

/** Device-local user intent for the NetworkKMM curl/HTTP/3 lane. */
interface NetworkTransportPreferenceStore {
    fun readHttp3EnabledSync(defaultValue: Boolean): Boolean

    fun writeHttp3EnabledSync(enabled: Boolean)
}

class KeyValueNetworkTransportPreferenceStore(
    private val store: SharedPreferenceStore
) : NetworkTransportPreferenceStore {
    override fun readHttp3EnabledSync(defaultValue: Boolean): Boolean =
        store.getBoolean(KEY_HTTP3_ENABLED, defaultValue)

    override fun writeHttp3EnabledSync(enabled: Boolean) {
        store.putBoolean(KEY_HTTP3_ENABLED, enabled)
    }

    internal companion object {
        const val KEY_HTTP3_ENABLED = "network:http3_enabled"
    }
}

enum class NetworkTransportPreferenceAvailability {
    Ready,
    NativeUnavailable,
    Http3Unsupported,
    TrustUnavailable,
    ProxyUnavailable,
    RuntimeUnavailable
}

/**
 * Separates the persisted preference from the effective request policy. A
 * checked switch never proves that an individual request negotiated HTTP/3.
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

sealed interface NetworkTransportPreferenceMutationResult {
    val snapshot: NetworkTransportPreferenceSnapshot

    data class Applied(
        override val snapshot: NetworkTransportPreferenceSnapshot
    ) : NetworkTransportPreferenceMutationResult

    data class Rejected(
        val errorCode: String,
        override val snapshot: NetworkTransportPreferenceSnapshot
    ) : NetworkTransportPreferenceMutationResult
}

interface NetworkTransportPreferenceRepository {
    fun getPreferenceSync(): NetworkTransportPreferenceSnapshot

    suspend fun getPreference(): NetworkTransportPreferenceSnapshot = getPreferenceSync()

    suspend fun setHttp3Enabled(enabled: Boolean): NetworkTransportPreferenceMutationResult
}
