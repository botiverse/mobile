package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/**
 * Fences route terminalization to the latest auth mutation and the persisted session SSOT.
 * Auth events are wake signals only: a late clear must never evict a newer authenticated session.
 */
@RaftCoreInternalApi class AuthSessionTerminalRouteGate {
    private var reconciledSubscription = false
    private var observedGeneration = 0L

    fun reconcile(snapshot: AuthSessionTerminalSnapshot): Boolean {
        if (reconciledSubscription && snapshot.generation <= observedGeneration) return false
        reconciledSubscription = true
        observedGeneration = snapshot.generation
        return snapshot.session == null
    }

    fun admit(event: AuthSessionEvent): AuthSessionClearReason? {
        if (event.generation <= observedGeneration) return null
        observedGeneration = event.generation
        if (event !is AuthSessionEvent.Cleared) return null
        return event.reason
    }
}
