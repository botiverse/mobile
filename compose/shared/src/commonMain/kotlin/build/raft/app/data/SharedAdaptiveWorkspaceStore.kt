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
    /**
     * Outstanding standalone→pane adoption requests awaiting the destination's ready report.
     * Keyed by request id; a second request with the same id is deduplicated, and requests whose
     * window fact or authority was superseded are dropped here rather than inside the reducer.
     */
    val pendingDetailOpens: Map<String, PendingAdaptiveDetailOpen> = emptyMap(),
    /**
     * The freshest window fact the shell applied (whether from its own emitter or replayed from a
     * covered-top request). Freshness comparisons live here — not inside the pure workspace
     * reducer — so carried facts can never resurrect a superseded DualPane.
     */
    val lastWindowFactHostId: String? = null,
    val lastWindowFactCapturedAtMillis: Long = 0L,
)

/** One adoption request the shell accepted and is waiting to confirm before acking. */
internal data class PendingAdaptiveDetailOpen(
    val requestId: String,
    val hostId: String,
    val identity: build.raft.app.SlockAdaptiveDetailIdentity,
    val detailGeneration: Long,
    /** Wall-clock capture stamp of the fact that justified this request (freshness fence). */
    val windowFactCapturedAtMillis: Long,
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
