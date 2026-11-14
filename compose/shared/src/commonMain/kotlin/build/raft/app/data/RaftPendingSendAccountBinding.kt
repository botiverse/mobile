package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The production account binding transition ([processAccountBindingTransition]) with the
 * pending-send journal's cleanup wired in: what the authority provider runs on every auth
 * revision advance.
 */
internal fun processAccountBindingTransitionWithPendingSends(
    presenceAuthContext: MutableStateFlow<ProcessPresenceAuthContext?>,
    accountBinding: MutableStateFlow<RaftAccountBinding<ProcessPresenceAuthContext>>,
    registry: ProcessMemberPresenceRegistry,
    journal: ThreadPendingSendJournal,
    currentEnvironmentId: () -> String,
    diagnosticsLogger: (String) -> Unit,
): (RealtimeAuthRevision, RaftAuthSessionState) -> Unit =
    processAccountBindingTransition(
        presenceAuthContext = presenceAuthContext,
        accountBinding = accountBinding,
        registry = registry,
        currentEnvironmentId = currentEnvironmentId,
        // Retire only the exact environment/principal/auth revision that
        // previously owned sends.  A late terminal event can never erase a
        // successor login's rows because its authGeneration is different.
        onBindingRetired = { retired ->
            val cleared = journal.clearForAuthRevision(
                environmentId = retired.environmentId,
                principalId = retired.accountId,
                authGeneration = retired.authorityGeneration,
            )
            if (!cleared) {
                diagnosticsLogger(
                    "thread_pending_send_auth_revision_cleanup_failed " +
                        "environmentId=${retired.environmentId} " +
                        "principalId=${retired.accountId} " +
                        "authGeneration=${retired.authorityGeneration}"
                )
            }
        },
        // A cold process has no in-memory predecessor to retire.  Remove
        // only rows from older generations and preserve the exact current
        // generation, so a successor login cannot be erased by restoration.
        onBindingBound = { bound ->
            val cleared = journal.clearForPrincipalBeforeAuthRevision(
                environmentId = bound.environmentId,
                principalId = bound.accountId,
                authGeneration = bound.authorityGeneration,
            )
            if (!cleared) {
                diagnosticsLogger(
                    "thread_pending_send_stale_generation_cleanup_failed " +
                        "environmentId=${bound.environmentId} " +
                        "principalId=${bound.accountId} " +
                        "authGeneration=${bound.authorityGeneration}"
                )
            }
        },
        // Same-binding token rotation (401 -> silent refresh): rehome the
        // principal's journaled pending sends into the successor auth
        // revision instead of retiring them, so an offline send admitted
        // before the refresh stays reachable for drain/restore. Binding
        // changes (logout/account switch) keep delete semantics via
        // onBindingRetired above.
        onBindingAuthRevisionRotated = { retired, bound ->
            val rehomed = journal.rehomeAuthRevision(
                environmentId = retired.environmentId,
                principalId = retired.accountId,
                fromAuthGeneration = retired.authorityGeneration,
                toAuthGeneration = bound.authorityGeneration,
            )
            diagnosticsLogger(
                "thread_pending_send_auth_revision_rehomed " +
                    "environmentId=${retired.environmentId} " +
                    "principalId=${retired.accountId} " +
                    "fromAuthGeneration=${retired.authorityGeneration} " +
                    "toAuthGeneration=${bound.authorityGeneration} " +
                    "rehomed=$rehomed"
            )
        },
    )
