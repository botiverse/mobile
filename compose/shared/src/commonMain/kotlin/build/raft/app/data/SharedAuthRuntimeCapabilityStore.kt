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
 * Legacy provider-probe and local-override values are retained for compatibility.
 * Apple button availability is determined solely by the iOS host capability;
 * these values cannot enable Apple on another platform or hide it on iOS.
 */
data class SharedAuthRuntimeCapabilities(
    /** The host only offers browser web login (HarmonyOS). */
    val webLoginOnly: Boolean = false,
    /**
     * The host implements browser web login (`appLoginPrepare` / `appLoginOpen` and the `login/callback`
     * deep link) and also keeps the email/social login; [MobileWebLoginGate] picks one (Android, iOS).
     */
    val webLoginSupported: Boolean = false,
    val socialProviderIds: Set<String> = emptySet(),
    /** Legacy backend provider snapshot; not an Apple sign-in availability gate. */
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

    fun seedWebLoginOnly(value: Boolean?) = synchronized(lock) {
        if (value != null) _state.value = _state.value.copy(webLoginOnly = value)
    }

    fun seedWebLoginSupported(value: Boolean?) = synchronized(lock) {
        if (value != null) _state.value = _state.value.copy(webLoginSupported = value)
    }

    fun current(): SharedAuthRuntimeCapabilities = synchronized(lock) { _state.value }
}
