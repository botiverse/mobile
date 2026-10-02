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
    val detailHistory: AdaptiveDetailHistory? = null,
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
    /** task #36: reply thread shown in its own column beside the selected channel/DM. */
    val sideThread: AdaptiveSideThread? = null,
    val sideThreadGenerationCursor: Long = 0L,
)

/** One adoption request the shell accepted and is waiting to confirm before acking. */
internal data class PendingAdaptiveDetailOpen(
    val requestId: String,
    val hostId: String,
    val identity: build.raft.app.SlockAdaptiveDetailIdentity,
    val detailGeneration: Long,
    /** Wall-clock capture stamp of the fact that justified this request (freshness fence). */
    val windowFactCapturedAtMillis: Long,
    /**
     * True once the destination reported READY and the shell acked — the request then waits on
     * the native commit receipt; the staged scroll anchor is consumed only on `committed`.
     */
    val acknowledged: Boolean = false,
    /**
     * Native controller identities reported by the source after a successful prepare (via
     * `slockAdaptiveDetailPrepared`). The commit receipt is matched against these, so a stray
     * or cross-instance result cannot settle this request.
     */
    val sourceControllerToken: String? = null,
    val targetControllerToken: String? = null,
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
            transform(_state.value).let { next ->
                val history = next.detailHistory
                val withHistory = if (history != null && history.owner != next.workspace?.selection) {
                    next.copy(detailHistory = null)
                } else next
                // task #36: a side thread belongs to the selection it opened beside.
                val side = withHistory.sideThread
                val normalized = if (side != null && side.owner != withHistory.workspace?.selection) {
                    withHistory.copy(sideThread = null)
                } else withHistory
                normalized.also { _state.value = it }
            }
        }

    fun clear() {
        synchronized(lock) { _state.value = SharedAdaptiveWorkspaceSession() }
    }
}
