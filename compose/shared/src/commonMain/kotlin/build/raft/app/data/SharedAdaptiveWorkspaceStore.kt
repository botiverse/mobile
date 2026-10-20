package build.raft.app.data

import build.raft.app.SlockAdaptiveWorkspaceState
import build.raft.app.WireThreadLaunchPayload
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SharedAdaptiveWorkspaceSession(
    val workspace: SlockAdaptiveWorkspaceState? = null,
    val launchPayload: WireThreadLaunchPayload? = null,
    val handoffRoomLeaseOwner: RealtimeRoomLeaseOwner? = null,
)

/**
 * Shell-scoped adaptive workspace authority.
 *
 * The store outlives AppShell composition/configuration recreation but is removed with the shell
 * scope. The launch payload remains an identity/intent envelope; Thread product state and drafts
 * continue to hydrate through their canonical stores.
 */
internal class SharedAdaptiveWorkspaceStore {
    private val lock = SynchronizedObject()
    private val _state = MutableStateFlow(SharedAdaptiveWorkspaceSession())
    val state: StateFlow<SharedAdaptiveWorkspaceSession> = _state.asStateFlow()

    fun snapshot(): SharedAdaptiveWorkspaceSession = _state.value

    fun update(
        transform: (SharedAdaptiveWorkspaceSession) -> SharedAdaptiveWorkspaceSession,
    ): SharedAdaptiveWorkspaceSession =
        synchronized(lock) {
            transform(_state.value).also { _state.value = it }
        }

    fun clear() {
        synchronized(lock) { _state.value = SharedAdaptiveWorkspaceSession() }
    }
}
