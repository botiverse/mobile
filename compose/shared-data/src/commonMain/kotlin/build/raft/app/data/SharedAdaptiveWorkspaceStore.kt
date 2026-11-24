package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.RaftAdaptiveWorkspaceState
import build.raft.app.WireThreadLaunchPayload
import build.raft.app.raftAdaptiveDefaultLandingDropped
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@RaftCoreInternalApi data class SharedAdaptiveWorkspaceSession(
    val workspace: RaftAdaptiveWorkspaceState? = null,
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
    /**
     * task #36: measured width (dp) of the detail pane on the host currently showing it, after the
     * rail, list, dividers and insets. Null while unmeasured or on a host without a side column.
     */
    val detailPaneWidthDp: Float? = null,
    /**
     * #189 rule 3: whether the row plan folds the reply thread into the conversation (web rowPlan
     * `decideFold`), from the row the detail stands in with every column and the conversation at
     * their kept widths. Null where the host has no pane grid row; [detailPaneWidthDp] decides then.
     */
    val sideThreadFolds: Boolean? = null,
    /**
     * task #243: server id for which wide Home default landing has settled (either installed a
     * conversation or confirmed none was eligible). Kept alongside [workspace] so selection and
     * landing-settled state advance atomically in one emission — preventing a transient frame where
     * landing appears settled before [workspace].selection arrives — and cleared whenever authority
     * changes or narrowing drops a default-landing selection.
     */
    val defaultLandingServerId: String? = null,
)

/** One adoption request the shell accepted and is waiting to confirm before acking. */
@RaftCoreInternalApi data class PendingAdaptiveDetailOpen(
    val requestId: String,
    val hostId: String,
    val identity: build.raft.app.RaftAdaptiveDetailIdentity,
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
@RaftCoreInternalApi class SharedAdaptiveWorkspaceStore {
    private val lock = SynchronizedObject()
    private val _state = MutableStateFlow(SharedAdaptiveWorkspaceSession())
    val state: StateFlow<SharedAdaptiveWorkspaceSession> = _state.asStateFlow()

    fun snapshot(): SharedAdaptiveWorkspaceSession = _state.value

    fun update(
        transform: (SharedAdaptiveWorkspaceSession) -> SharedAdaptiveWorkspaceSession,
    ): SharedAdaptiveWorkspaceSession =
        synchronized(lock) {
            val previous = _state.value
            transform(previous).let { next ->
                val history = next.detailHistory
                val withHistory = if (history != null && history.owner != next.workspace?.selection) {
                    next.copy(detailHistory = null)
                } else next
                // task #36: a side thread belongs to the selection it opened beside.
                val side = withHistory.sideThread
                val withSide = if (side != null && side.owner != withHistory.workspace?.selection) {
                    withHistory.copy(sideThread = null)
                } else withHistory
                // task #243: canonical store-level normalization for defaultLandingServerId and
                // narrow-drop launchPayload clearing via [raftAdaptiveDefaultLandingDropped].
                // SettingsHomePage Effect #1 computes the same predicate only to know whether to
                // release its handoff lease; keeping the invariant here prevents caller drift.
                val landingDropped = raftAdaptiveDefaultLandingDropped(previous.workspace, withSide.workspace)
                val normalized =
                    when {
                        landingDropped ->
                            withSide.copy(
                                launchPayload = null,
                                defaultLandingServerId = null,
                            )
                        withSide.defaultLandingServerId != null &&
                            withSide.workspace?.serverId != withSide.defaultLandingServerId ->
                            withSide.copy(defaultLandingServerId = null)
                        else -> withSide
                    }
                normalized.also { _state.value = it }
            }
        }

    fun clear() {
        synchronized(lock) { _state.value = SharedAdaptiveWorkspaceSession() }
    }
}
