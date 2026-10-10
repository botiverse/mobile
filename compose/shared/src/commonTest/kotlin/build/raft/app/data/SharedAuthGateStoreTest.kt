package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedAuthGateStoreTest {
    @Test
    fun coldRestoreQaBootstrapSessionStopsAtRecoveryGate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val session =
            storedSession(selectedServerId = "server-1").copy(
                qaBootstrap =
                    StoredQaBootstrapMetadata(
                        ticketDigest = "sha256:ticket",
                        runCaseId = "run-case",
                        attempt = 1,
                        ciJobRef = "gha:job",
                        principalId = "user-1",
                        serverId = "server-1",
                        sessionGeneration = "generation-1",
                        targetRoute = "compose_slock_app_shell",
                        applicationId = "build.raft.app.alpha",
                        signingCertSha256 = "sha256:cert",
                        deviceBootGeneration = "sha256:boot"
                    )
            )
        val store = SharedAuthGateStore(restoreSession = { SharedAuthResult.Success(session) }, scope = scope)

        store.accept(SharedAuthGateIntent.Restore)

        assertEquals(SharedAuthGateState.BootstrapRecoveryRequired(session), store.state.value)
        store.accept(SharedAuthGateIntent.ResumeQaBootstrap(session))
        assertTrue(store.state.value is SharedAuthGateState.Ready)
        scope.cancel()
    }

    @Test
    fun restoreReadySessionMovesDirectlyToReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Success(storedSession(selectedServerId = "server-1")) },
                scope = scope
            )

        store.accept(SharedAuthGateIntent.Restore)

        val state = store.state.value
        assertTrue(state is SharedAuthGateState.Ready)
        assertEquals("server-1", state.session.selectedServerId)
        scope.cancel()
    }

    @Test
    fun restoreWithoutSelectedServerMovesToServerRequired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Success(storedSession(selectedServerId = null)) },
                scope = scope
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertTrue(store.state.value is SharedAuthGateState.ServerRequired)
        scope.cancel()
    }

    @Test
    fun restoreUnverifiedEmailMovesToEmailVerificationRequired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val store =
            SharedAuthGateStore(
                restoreSession = {
                    SharedAuthResult.Success(
                        storedSession(selectedServerId = "server-1", emailVerified = false)
                    )
                },
                scope = scope
            )

        store.accept(SharedAuthGateIntent.Restore)

        val state = store.state.value
        assertTrue(state is SharedAuthGateState.EmailVerificationRequired)
        assertEquals("user@slock.ai", state.session.user.email)
        scope.cancel()
    }

    @Test
    fun restoreVerifiedEmailWithoutServerMovesToServerRequired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val store =
            SharedAuthGateStore(
                restoreSession = {
                    SharedAuthResult.Success(
                        storedSession(selectedServerId = null, emailVerified = true)
                    )
                },
                scope = scope
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertTrue(store.state.value is SharedAuthGateState.ServerRequired)
        scope.cancel()
    }

    @Test
    fun restoreVerifiedPendingProfileMovesToProfileSetupRequiredBeforeServerGate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val store =
            SharedAuthGateStore(
                restoreSession = {
                    SharedAuthResult.Success(
                        storedSession(
                            selectedServerId = null,
                            emailVerified = true,
                            profileSetupCompletedAt = null
                        )
                    )
                },
                scope = scope
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertTrue(store.state.value is SharedAuthGateState.ProfileSetupRequired)
        scope.cancel()
    }

    @Test
    fun completedProfileMovesFromProfileSetupRequiredToServerRequired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val pending =
            storedSession(
                selectedServerId = null,
                emailVerified = true,
                profileSetupCompletedAt = null
            )
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Success(pending) },
                scope = scope
            )

        store.accept(SharedAuthGateIntent.SignedIn(pending))
        assertTrue(store.state.value is SharedAuthGateState.ProfileSetupRequired)

        val completed =
            pending.copy(
                user =
                    pending.user.copy(
                        name = "artin",
                        displayName = "Artin Chen",
                        profileSetupCompletedAt = "2026-07-21T00:00:00Z"
                    )
            )
        store.accept(SharedAuthGateIntent.SignedIn(completed))

        assertTrue(store.state.value is SharedAuthGateState.ServerRequired)
        scope.cancel()
    }

    @Test
    fun unauthenticatedRestoreMovesToAuthRequired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Unauthenticated },
                scope = scope
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertEquals(SharedAuthGateState.AuthRequired, store.state.value)
        scope.cancel()
    }

    @Test
    fun coldRestoreWithValidCachedSessionBecomesReadyBeforeNetworkRestoreCompletes() = runBlocking {
        val scope = authGateDispatchTestScope()
        val cachedSession = storedSession(selectedServerId = "server-1")
        val restoredSession = cachedSession.copy(accessToken = "access-token-2")
        val restoreStarted = CompletableDeferred<Unit>()
        val restoreResult = CompletableDeferred<SharedAuthResult<StoredAuthSession>>()
        val logs = mutableListOf<String>()
        val store =
            SharedAuthGateStore(
                restoreSession = {
                    restoreStarted.complete(Unit)
                    restoreResult.await()
                },
                cachedSession = { cachedSession },
                scope = scope,
                diagnosticsLogger = logs::add
            )

        store.dispatch(SharedAuthGateIntent.Restore)
        restoreStarted.await()
        yield()

        assertEquals(
            SharedAuthGateState.Ready(
                cachedSession,
                phase = SharedAuthReadyPhase.CachedRestorePending
            ),
            store.state.value
        )
        assertContains(logs, "auth_gate_state source=restore_cached_ready state=ready selectedServer=true")

        restoreResult.complete(SharedAuthResult.Success(restoredSession))

        assertEquals(
            SharedAuthGateState.Ready(
                restoredSession,
                phase = SharedAuthReadyPhase.CachedRestoreCompleted
            ),
            store.awaitState { state ->
                (state as? SharedAuthGateState.Ready)?.session?.accessToken == "access-token-2"
            }
        )
        assertContains(logs, "auth_gate_state source=restore_background_result state=ready selectedServer=true")
        scope.cancel()
    }

    @Test
    fun signedOutCancelsInFlightCachedReadyBackgroundRestoreResult() = runBlocking {
        val scope = authGateDispatchTestScope()
        val cachedSession = storedSession(selectedServerId = "server-1")
        val restoreStarted = CompletableDeferred<Unit>()
        val restoreResult = CompletableDeferred<SharedAuthResult<StoredAuthSession>>()
        val logs = mutableListOf<String>()
        val store =
            SharedAuthGateStore(
                restoreSession = {
                    restoreStarted.complete(Unit)
                    restoreResult.await()
                },
                cachedSession = { cachedSession },
                scope = scope,
                diagnosticsLogger = logs::add
            )

        store.dispatch(SharedAuthGateIntent.Restore)
        restoreStarted.await()
        yield()
        assertEquals(
            SharedAuthGateState.Ready(
                cachedSession,
                phase = SharedAuthReadyPhase.CachedRestorePending
            ),
            store.state.value
        )

        store.dispatch(SharedAuthGateIntent.SignedOut)
        restoreResult.complete(
            SharedAuthResult.Success(cachedSession.copy(accessToken = "stale-background-token"))
        )
        yield()

        assertEquals(SharedAuthGateState.AuthRequired, store.state.value)
        assertFalse(logs.any { it.contains("source=restore_background_result") })
        scope.cancel()
    }

    @Test
    fun coldRestoreCachedReadyUnauthenticatedBackgroundMovesToAuthRequired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val cachedSession = storedSession(selectedServerId = "server-1")
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Unauthenticated },
                cachedSession = { cachedSession },
                scope = scope
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertEquals(SharedAuthGateState.AuthRequired, store.state.value)
        scope.cancel()
    }

    @Test
    fun coldRestoreCachedReadyFailureKeepsReadySessionAndLogs() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val logs = mutableListOf<String>()
        val cachedSession = storedSession(selectedServerId = "server-1")
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Failure(SharedAuthErrorCode.Network, "offline") },
                cachedSession = { cachedSession },
                scope = scope,
                diagnosticsLogger = logs::add
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertEquals(
            SharedAuthGateState.Ready(
                cachedSession,
                phase = SharedAuthReadyPhase.CachedRestoreCompleted
            ),
            store.state.value
        )
        assertContains(logs, "auth_gate_restore_background_failure code=Network message=offline keeping_ready=true")
        assertContains(logs, "auth_gate_state source=restore_background_keep_ready state=ready selectedServer=true")
        scope.cancel()
    }

    @Test
    fun silentRefreshDuringCachedRestoreJoinsItAndClosesPendingPhase() = runBlocking {
        val scope = authGateDispatchTestScope()
        val cachedSession = storedSession(selectedServerId = "server-1")
        val restoreStarted = CompletableDeferred<Unit>()
        val restoreResult = CompletableDeferred<SharedAuthResult<StoredAuthSession>>()
        val store =
            SharedAuthGateStore(
                restoreSession = {
                    restoreStarted.complete(Unit)
                    restoreResult.await()
                },
                cachedSession = { cachedSession },
                scope = scope
            )

        store.dispatch(SharedAuthGateIntent.Restore)
        restoreStarted.await()
        yield()
        assertEquals(
            SharedAuthReadyPhase.CachedRestorePending,
            (store.state.value as SharedAuthGateState.Ready).phase
        )

        store.dispatch(SharedAuthGateIntent.RefreshSilently)
        restoreResult.complete(SharedAuthResult.Failure(SharedAuthErrorCode.Network, "offline"))
        val finalState =
            store.awaitState { state ->
                (state as? SharedAuthGateState.Ready)?.phase == SharedAuthReadyPhase.CachedRestoreCompleted
            }
        assertEquals(cachedSession, (finalState as SharedAuthGateState.Ready).session)
        scope.cancel()
    }

    @Test
    fun silentRefreshDoesNotRestartARestoreThatIsStillRunning() = runBlocking {
        // Hands 79db9fe6: the restore's own token refresh reported a same-account session change,
        // the gate cancelled the restore and ran it again from the top (a second auth/me + /servers).
        val scope = authGateDispatchTestScope()
        val cachedSession = storedSession(selectedServerId = "server-1")
        var restoreCalls = 0
        val restoreStarted = CompletableDeferred<Unit>()
        val restoreResult = CompletableDeferred<SharedAuthResult<StoredAuthSession>>()
        val store =
            SharedAuthGateStore(
                restoreSession = {
                    restoreCalls += 1
                    restoreStarted.complete(Unit)
                    restoreResult.await()
                },
                cachedSession = { cachedSession },
                scope = scope
            )

        store.dispatch(SharedAuthGateIntent.Restore)
        restoreStarted.await()
        store.dispatch(SharedAuthGateIntent.RefreshSilently)
        store.dispatch(SharedAuthGateIntent.RefreshSilently)
        restoreResult.complete(SharedAuthResult.Success(cachedSession))
        val finalState =
            store.awaitState { state ->
                (state as? SharedAuthGateState.Ready)?.phase == SharedAuthReadyPhase.CachedRestoreCompleted
            }
        assertEquals(cachedSession, (finalState as SharedAuthGateState.Ready).session)
        assertEquals(1, restoreCalls, "the running restore lands the result; it is not cancelled and redone")

        // Once nothing is running, a silent refresh runs again.
        store.dispatch(SharedAuthGateIntent.RefreshSilently)
        store.awaitState { restoreCalls == 2 }
        scope.cancel()
    }

    @Test
    fun coldRestoreCachedReadyServerRequiredBackgroundMovesToServerRequired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val cachedSession = storedSession(selectedServerId = "server-1")
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Success(storedSession(selectedServerId = null)) },
                cachedSession = { cachedSession },
                scope = scope
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertTrue(store.state.value is SharedAuthGateState.ServerRequired)
        scope.cancel()
    }

    @Test
    fun coldRestoreCachedReadyEmailVerificationBackgroundMovesToEmailVerification() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val cachedSession = storedSession(selectedServerId = "server-1")
        val store =
            SharedAuthGateStore(
                restoreSession = {
                    SharedAuthResult.Success(
                        storedSession(selectedServerId = "server-1", emailVerified = false)
                    )
                },
                cachedSession = { cachedSession },
                scope = scope
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertTrue(store.state.value is SharedAuthGateState.EmailVerificationRequired)
        scope.cancel()
    }

    @Test
    fun coldRestoreWithoutSelectedServerDoesNotUseCachedReady() = runBlocking {
        val scope = authGateDispatchTestScope()
        val cachedSession = storedSession(selectedServerId = null)
        val restoreStarted = CompletableDeferred<Unit>()
        val restoreResult = CompletableDeferred<SharedAuthResult<StoredAuthSession>>()
        val logs = mutableListOf<String>()
        val store =
            SharedAuthGateStore(
                restoreSession = {
                    restoreStarted.complete(Unit)
                    restoreResult.await()
                },
                cachedSession = { cachedSession },
                scope = scope,
                diagnosticsLogger = logs::add
            )

        store.dispatch(SharedAuthGateIntent.Restore)
        restoreStarted.await()
        yield()

        assertEquals(SharedAuthGateState.Restoring, store.state.value)
        assertFalse(logs.any { it.contains("source=restore_cached_ready") })

        restoreResult.complete(SharedAuthResult.Success(cachedSession))

        assertTrue(store.awaitState { it is SharedAuthGateState.ServerRequired } is SharedAuthGateState.ServerRequired)
        scope.cancel()
    }

    @Test
    fun coldRestoreWithoutAccessTokenDoesNotUseCachedReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val cachedSession = storedSession(selectedServerId = "server-1", accessToken = "")
        val logs = mutableListOf<String>()
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Unauthenticated },
                cachedSession = { cachedSession },
                scope = scope,
                diagnosticsLogger = logs::add
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertEquals(SharedAuthGateState.AuthRequired, store.state.value)
        assertFalse(logs.any { it.contains("source=restore_cached_ready") })
        scope.cancel()
    }

    @Test
    fun coldRestoreWithSelectedServerOutsideCachedMembershipDoesNotUseCachedReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val cachedSession =
            storedSession(selectedServerId = "server-2").copy(
                servers = listOf(StoredAuthServer(id = "server-1", name = "Slock"))
            )
        val logs = mutableListOf<String>()
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Unauthenticated },
                cachedSession = { cachedSession },
                scope = scope,
                diagnosticsLogger = logs::add
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertEquals(SharedAuthGateState.AuthRequired, store.state.value)
        assertFalse(logs.any { it.contains("source=restore_cached_ready") })
        scope.cancel()
    }

    @Test
    fun coldRestoreWithoutUserDoesNotUseCachedReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val cachedSession = storedSession(selectedServerId = "server-1", userId = "")
        val logs = mutableListOf<String>()
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Unauthenticated },
                cachedSession = { cachedSession },
                scope = scope,
                diagnosticsLogger = logs::add
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertEquals(SharedAuthGateState.AuthRequired, store.state.value)
        assertFalse(logs.any { it.contains("source=restore_cached_ready") })
        scope.cancel()
    }

    @Test
    fun coldRestoreWithoutCachedSessionKeepsExistingNoSessionBehavior() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val logs = mutableListOf<String>()
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Unauthenticated },
                cachedSession = { null },
                scope = scope,
                diagnosticsLogger = logs::add
            )

        store.accept(SharedAuthGateIntent.Restore)

        assertEquals(SharedAuthGateState.AuthRequired, store.state.value)
        assertFalse(logs.any { it.contains("source=restore_cached_ready") })
        scope.cancel()
    }

    @Test
    fun signedOutCancelsInFlightRestoreResult() = runBlocking {
        val scope = authGateDispatchTestScope()
        val restoreStarted = CompletableDeferred<Unit>()
        val restoreResult = CompletableDeferred<SharedAuthResult<StoredAuthSession>>()
        val store =
            SharedAuthGateStore(
                restoreSession = {
                    restoreStarted.complete(Unit)
                    restoreResult.await()
                },
                scope = scope
            )

        store.dispatch(SharedAuthGateIntent.Restore)
        restoreStarted.await()

        store.dispatch(SharedAuthGateIntent.SignedOut)
        restoreResult.complete(SharedAuthResult.Success(storedSession(selectedServerId = "server-1")))
        yield()

        assertEquals(SharedAuthGateState.AuthRequired, store.state.value)
        scope.cancel()
    }

    @Test
    fun retryUsesSameRestoreStateMachine() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val logs = mutableListOf<String>()
        var attempts = 0
        val store =
            SharedAuthGateStore(
                restoreSession = {
                    attempts += 1
                    if (attempts == 1) {
                        SharedAuthResult.Failure(SharedAuthErrorCode.Network, "offline")
                    } else {
                        SharedAuthResult.Success(storedSession(selectedServerId = "server-1"))
                    }
                },
                scope = scope,
                diagnosticsLogger = logs::add
            )

        store.accept(SharedAuthGateIntent.Restore)
        assertTrue(store.state.value is SharedAuthGateState.Error)

        store.accept(SharedAuthGateIntent.Retry)

        assertTrue(store.state.value is SharedAuthGateState.Ready)
        assertContains(logs, "auth_gate_state source=restore_result state=error selectedServer=false")
        assertContains(logs, "auth_gate_state source=restore_result state=ready selectedServer=true")
        scope.cancel()
    }

    @Test
    fun silentRefreshSuccessKeepsReadyPathWithoutRestoring() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val logs = mutableListOf<String>()
        val refreshedSession = storedSession(selectedServerId = "server-1", accessToken = "access-token-2")
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Success(refreshedSession) },
                scope = scope,
                diagnosticsLogger = logs::add
            )
        store.accept(SharedAuthGateIntent.SignedIn(storedSession(selectedServerId = "server-1")))

        store.accept(SharedAuthGateIntent.RefreshSilently)

        val state = store.state.value
        assertTrue(state is SharedAuthGateState.Ready)
        assertEquals("access-token-2", state.session.accessToken)
        assertFalse(logs.any { it.contains("source=restore_start state=restoring") })
        assertContains(logs, "auth_gate_state source=silent_refresh_result state=ready selectedServer=true")
        scope.cancel()
    }

    @Test
    fun silentRefreshUnauthenticatedMovesToAuthRequired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Unauthenticated },
                scope = scope
            )
        store.accept(SharedAuthGateIntent.SignedIn(storedSession(selectedServerId = "server-1")))

        store.accept(SharedAuthGateIntent.RefreshSilently)

        assertEquals(SharedAuthGateState.AuthRequired, store.state.value)
        scope.cancel()
    }

    @Test
    fun silentRefreshFailureKeepsPreviousReadySessionAndLogs() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val logs = mutableListOf<String>()
        val previousSession = storedSession(selectedServerId = "server-1")
        val store =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Failure(SharedAuthErrorCode.Network, "offline") },
                scope = scope,
                diagnosticsLogger = logs::add
            )
        store.accept(SharedAuthGateIntent.SignedIn(previousSession))

        store.accept(SharedAuthGateIntent.RefreshSilently)

        assertEquals(SharedAuthGateState.Ready(previousSession), store.state.value)
        assertContains(logs, "auth_gate_silent_refresh_failure code=Network message=offline keeping_ready=true")
        assertContains(logs, "auth_gate_state source=silent_refresh_keep_ready state=ready selectedServer=true")
        scope.cancel()
    }

    @Test
    fun restoreExceptionUsesRecoverableMessageAndLogsMapping() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val logs = mutableListOf<String>()
        val store =
            SharedAuthGateStore(
                restoreSession = { throw IllegalStateException("boom") },
                scope = scope,
                diagnosticsLogger = logs::add
            )

        store.accept(SharedAuthGateIntent.Restore)

        val state = store.state.value
        assertTrue(state is SharedAuthGateState.Error)
        assertEquals("Could not restore session. Retry or sign in again.", state.message)
        assertContains(logs, "auth_gate_restore_exception errorType=IllegalStateException mapped=restore_error")
        assertContains(logs, "auth_gate_state source=restore_exception state=error selectedServer=false")
        scope.cancel()
    }

    private fun storedSession(
        selectedServerId: String?,
        emailVerified: Boolean? = null,
        profileSetupCompletedAt: String? = "legacy_complete",
        accessToken: String = "access-token",
        userId: String = "user-1"
    ): StoredAuthSession =
        StoredAuthSession(
            user =
                StoredAuthUser(
                    id = userId,
                    email = "user@slock.ai",
                    displayName = "Artin",
                    emailVerified = emailVerified,
                    profileSetupCompletedAt = profileSetupCompletedAt
                ),
            accessToken = accessToken,
            refreshToken = "refresh-token",
            selectedServerId = selectedServerId,
            selectedServerSlug = selectedServerId?.let { "slock" },
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
        )

    private suspend fun SharedAuthGateStore.awaitState(
        predicate: (SharedAuthGateState) -> Boolean
    ): SharedAuthGateState =
        withTimeout(1_000) {
            state.first(predicate)
        }

    private fun CoroutineScope.authGateDispatchTestScope(): CoroutineScope =
        CoroutineScope(coroutineContext + SupervisorJob())
}
