package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Process-lifetime host capabilities for the authentication surface.
 *
 * These values are runtime configuration, not authenticated-session state. The platform host
 * seeds them on the first root auth/AppShell page, and every later auth route reads this store.
 * Clearing or replacing [SlockAuthSessionStore] must not clear these capabilities.
 */
internal data class SharedAuthRuntimeCapabilities(
    val socialProviderIds: Set<String> = emptySet()
)

internal class SharedAuthRuntimeCapabilityStore {
    private val lock = SynchronizedObject()
    private var snapshot = SharedAuthRuntimeCapabilities()

    fun seedSocialProviders(hostValue: String?): SharedAuthRuntimeCapabilities =
        synchronized(lock) {
            if (hostValue != null) {
                snapshot =
                    snapshot.copy(
                        socialProviderIds =
                            hostValue
                                .split(',')
                                .map { it.trim().lowercase() }
                                .filter { it.isNotBlank() }
                                .toSet()
                    )
            }
            snapshot
        }

    fun current(): SharedAuthRuntimeCapabilities = synchronized(lock) { snapshot }
}
