package build.raft.app.shared

import build.raft.app.data.RaftAuthSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Developer settings: the API server changed, so the stored session (minted by the old server)
 * is cleared before anything else reacts to the switch.
 *
 * Suspends on the auth-session lock instead of `runBlocking` on the main thread (#3823: kotlinx
 * `Mutex` hands the lock to the next queued waiter on its own dispatcher; a Main waiter can never
 * run while the main thread is blocked, so the lock is never released). The clear and
 * [afterClear] run [NonCancellable]: the new environment is already persisted, so a caller torn
 * down mid-switch (Activity destroyed) must not leave the old server's session behind. The
 * NonCancellable block also hands its result back, so the switch is still answered.
 */
internal suspend fun clearSessionForApiEnvironmentChange(
    authStore: RaftAuthSessionStore,
    afterClear: () -> Unit,
) {
    withContext(NonCancellable) {
        authStore.clearSession(reason = "api_environment_changed", source = "android_activity")
        afterClear()
    }
}

/**
 * Runs one settings-bridge action that has to suspend and answers [callback] with its result,
 * or with the bridge's generic failure payload when it throws.
 *
 * Starts UNDISPATCHED, so when nothing suspends (the lock is free) the answer is still delivered
 * inline, as the synchronous bridge path did; a contended lock delays the answer instead of
 * parking the calling (main) thread.
 */
internal fun CoroutineScope.launchSettingsBridgeAction(
    callback: RaftBridgeCallback?,
    action: suspend () -> Map<String, Any>,
) {
    launch(start = CoroutineStart.UNDISPATCHED) {
        val result =
            try {
                action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                mapOf(
                    "status" to "error",
                    "code" to "settings_capability_failed",
                    "message" to (throwable.message ?: "Settings capability failed"),
                )
            }
        callback?.invoke(result)
    }
}
