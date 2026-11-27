package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Disconnect preview for Settings › Account › Change password (`botiverse/slock` #17069 / #17081,
 * web `AccountSection.tsx` + `useDisconnectPreview.ts`):
 * - Reads `GET /api/me/sessions/disconnect-preview?action=password_change` each time the password
 *   form opens (when `passwordConfigured == true`), and again after a password change succeeds.
 * - Discards any cached preview when the form closes so reopening never shows a stale list.
 * - A failed read leaves `preview = null`, so the caller keeps its general info notice.
 */
@RaftCoreInternalApi data class SharedPasswordDisconnectPreviewState(
    val preview: SecurityRevokePreview? = null,
)

@RaftCoreInternalApi class SharedPasswordDisconnectPreviewStore(
    private val fetchPreview: suspend () -> SecurityRevokePreview?,
    private val scope: CoroutineScope,
) {
    constructor(
        networkClient: RaftNetworkClient,
        scope: CoroutineScope,
    ) : this(
        fetchPreview = { networkClient.fetchSharedSignedInDisconnectPreview(SignedInDisconnectPreviewTarget.PasswordChange) },
        scope = scope,
    )

    private val mutableState = MutableStateFlow(SharedPasswordDisconnectPreviewState())
    val state: StateFlow<SharedPasswordDisconnectPreviewState> = mutableState.asStateFlow()

    private var activeKey: String? = null
    private var fetchJob: Job? = null
    private var generation: Long = 0

    /**
     * Synchronize the preview read with the password form's open state (`key != null` reads once
     * per distinct [key]; `key == null` cancels any in-flight read and clears the cached preview).
     */
    fun sync(key: String?) {
        if (key == null) {
            if (activeKey != null || fetchJob != null || mutableState.value.preview != null) {
                activeKey = null
                generation++
                fetchJob?.cancel()
                fetchJob = null
                mutableState.value = SharedPasswordDisconnectPreviewState(preview = null)
            }
            return
        }
        if (key == activeKey) return
        activeKey = key
        val requestGeneration = ++generation
        fetchJob?.cancel()
        mutableState.value = SharedPasswordDisconnectPreviewState(preview = null)
        fetchJob =
            scope.launch {
                val result = fetchPreview()
                if (generation == requestGeneration && activeKey == key) {
                    mutableState.value = SharedPasswordDisconnectPreviewState(preview = result)
                }
            }
    }
}
