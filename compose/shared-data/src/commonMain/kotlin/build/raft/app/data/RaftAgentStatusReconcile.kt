package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Web `STATUS_RECONCILE_INTERVAL_MS` (botiverse/slock `packages/web/src/store/socketBridge.ts`): 10 minutes. */
@RaftCoreInternalApi const val AGENT_STATUS_RECONCILE_INTERVAL_MILLIS: Long = 600_000L

/**
 * The periodic agent status read of an app that stays in front (web `planStatusReconcile` +
 * `executeScheduledMachineReconcile`, cache-coherence contract CC-006: the `agent:activity` /
 * `agent:activity_state` push is best effort, so convergence is owed to a pull).
 *
 * Every list (Members, Home, thread avatars) and the agent profile read one presence store
 * ([ProcessMemberPresenceRegistry]); only the profile reads the server again (`GET /agents/:id`)
 * when it opens. A push the socket never got leaves the lists on the old status until something
 * reads `/agents` again: a (re)connect, a foreground return, a server switch. While the app stays in
 * front on one server with its socket up, none of those happens, so a list could show an agent
 * Online for as long as the app stayed open while its profile showed it working (Hands c76f162d,
 * 46b6cebe). The web reads agents and machines again every 10 minutes while the tab is visible and
 * its socket connected; this does the same.
 *
 * While [foreground] is true, every [intervalMillis] each server of [serverIds] (the servers on
 * screen whose socket has connected: a socket that never connected reads on its first connect) is
 * [reconcile]d. Going to the background stops the clock; a return starts it again from zero (the
 * foreground return reads on its own). A read already started finishes in the background.
 */
@RaftCoreInternalApi class RaftAgentStatusReconcile(
    private val scope: CoroutineScope,
    private val foreground: Flow<Boolean>,
    private val serverIds: () -> Collection<String>,
    private val reconcile: suspend (serverId: String) -> Unit,
    private val intervalMillis: Long = AGENT_STATUS_RECONCILE_INTERVAL_MILLIS,
    private val logger: (String) -> Unit = {},
) {
    fun start(): Job =
        scope.launch {
            foreground.collectLatest { inFront ->
                if (!inFront) return@collectLatest
                while (true) {
                    delay(intervalMillis)
                    val due = serverIds().mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
                    if (due.isEmpty()) {
                        logger("agent_status_reconcile_skip cause=no_connected_server")
                        continue
                    }
                    // Its own job: a return to the background cancels the clock, not a read under way.
                    scope.launch { due.forEach { serverId -> reconcileOne(serverId) } }
                }
            }
        }

    private suspend fun reconcileOne(serverId: String) {
        logger("agent_status_reconcile serverId=$serverId intervalMs=$intervalMillis")
        try {
            reconcile(serverId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger("agent_status_reconcile_failed serverId=$serverId type=${error::class.simpleName ?: "unknown"}")
        }
    }
}
