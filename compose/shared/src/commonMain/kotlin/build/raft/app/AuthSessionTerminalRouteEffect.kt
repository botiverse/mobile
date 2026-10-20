package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.data.AuthSessionClearReason
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.AuthSessionTerminalRouteGate
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftAuthSessionTerminalSource
import build.raft.app.data.authSessionTerminalSourceOrNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription

internal data class AuthSessionTerminalRouteSignal(
    val generation: Long?,
    val reason: AuthSessionClearReason,
)

/**
 * Reconciles an independently mounted product route with the process auth SSOT.
 * Production routes call this only when they carry an explicit shell identity.
 */
@Composable
internal fun AuthSessionTerminalRouteEffect(
    enabled: Boolean,
    authStore: RaftAuthSessionStore,
    onTerminal: (AuthSessionTerminalRouteSignal) -> Unit,
) {
    if (!enabled) return
    val terminalSource = authStore.authSessionTerminalSourceOrNull()
    val gate = remember(authStore) { AuthSessionTerminalRouteGate() }
    val latestOnTerminal = rememberUpdatedState(onTerminal)

    LaunchedEffect(authStore, terminalSource) {
        if (terminalSource == null) {
            if (authStore.readSession() == null) {
                latestOnTerminal.value(
                    AuthSessionTerminalRouteSignal(
                        generation = null,
                        reason = AuthSessionClearReason.AuthLoss,
                    )
                )
            }
            return@LaunchedEffect
        }
        collectAuthSessionTerminalSignals(terminalSource, gate) { signal ->
            latestOnTerminal.value(signal)
        }
    }
}

internal suspend fun collectAuthSessionTerminalSignals(
    terminalSource: RaftAuthSessionTerminalSource,
    gate: AuthSessionTerminalRouteGate = AuthSessionTerminalRouteGate(),
    onTerminal: (AuthSessionTerminalRouteSignal) -> Unit,
) {
    suspend fun reconcileSubscribedState() {
        val snapshot = terminalSource.readAuthSessionTerminalSnapshot()
        if (!gate.reconcile(snapshot)) return
        terminalSource.runIfAuthSessionTerminalRevision(snapshot.generation) {
            onTerminal(
                AuthSessionTerminalRouteSignal(
                    generation = snapshot.generation,
                    reason = AuthSessionClearReason.AuthLoss,
                )
            )
        }
    }

    terminalSource.authSessionTerminalEvents
        .onSubscription { reconcileSubscribedState() }
        .collect { event ->
            val reason = gate.admit(event) ?: return@collect
            terminalSource.runIfAuthSessionTerminalRevision(event.generation) {
                onTerminal(
                    AuthSessionTerminalRouteSignal(
                        generation = (event as AuthSessionEvent.Cleared).generation,
                        reason = reason,
                    )
                )
            }
        }
}
