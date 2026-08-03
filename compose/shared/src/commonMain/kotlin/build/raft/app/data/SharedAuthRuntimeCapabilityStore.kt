package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-lifetime host capabilities for the authentication surface.
 *
 * These values are runtime configuration, not authenticated-session state. The platform host
 * seeds them on the first root auth/AppShell page, and every later auth route reads this store.
 * Clearing or replacing [SlockAuthSessionStore] must not clear these capabilities.
 *
 * [SharedAuthRuntimeCapabilities.localAppleSignInOverride] is a signed-out diagnostics toggle:
 * it only changes which provider buttons the iOS login surface renders locally. Platform host
 * capability remains the outer gate, and backend provider availability (the `platform=mobile`
 * rollout gate) still decides whether a start call succeeds.
 */
data class SharedAuthRuntimeCapabilities(
    val socialProviderIds: Set<String> = emptySet(),
    /** Backend `/auth/providers?platform=…` result; null until a probe succeeds (fail-closed). */
    val backendEnabledSocialProviderIds: Set<String>? = null,
    val localAppleSignInOverride: Boolean = false
)

class SharedAuthRuntimeCapabilityStore {
    private val lock = SynchronizedObject()
    private val _state = MutableStateFlow(SharedAuthRuntimeCapabilities())
    val state: StateFlow<SharedAuthRuntimeCapabilities> = _state.asStateFlow()

    fun seedSocialProviders(hostValue: String?): SharedAuthRuntimeCapabilities =
        synchronized(lock) {
            if (hostValue != null) {
                _state.value =
                    _state.value.copy(
                        socialProviderIds =
                            hostValue
                                .split(',')
                                .map { it.trim().lowercase() }
                                .filter { it.isNotBlank() }
                                .toSet()
                    )
            }
            _state.value
        }

    fun setLocalAppleSignInOverride(enabled: Boolean): SharedAuthRuntimeCapabilities =
        synchronized(lock) {
            _state.value = _state.value.copy(localAppleSignInOverride = enabled)
            _state.value
        }

    fun setBackendEnabledSocialProviders(providerIds: Set<String>): SharedAuthRuntimeCapabilities =
        synchronized(lock) {
            _state.value = _state.value.copy(backendEnabledSocialProviderIds = providerIds)
            _state.value
        }

    fun current(): SharedAuthRuntimeCapabilities = synchronized(lock) { _state.value }
}
