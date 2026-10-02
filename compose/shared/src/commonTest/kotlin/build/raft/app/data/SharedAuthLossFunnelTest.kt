package build.raft.app.data

import build.raft.app.appShellAuthLossEffectShouldApply
import build.raft.app.appShellRunIfAuthSessionTerminalOrAuthorityRevision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SharedAuthLossFunnelTest {
    @Test
    fun concurrentSignalsForSameTokenRestoreOnlyOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val restoreStarted = CompletableDeferred<Unit>()
        val restoreFinished = CompletableDeferred<Unit>()
        val secondSignalJoined = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        var restoreCount = 0
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = {
                    restoreCount += 1
                    restoreStarted.complete(Unit)
                    releaseRestore.await()
                    restoreFinished.complete(Unit)
                    SharedAuthResult.Success(storedSession())
                },
                clearSessionIfMatches = { _, _, _ -> error("session must not clear") },
                scope = scope,
                diagnosticsLogger = { message ->
                    if (message.startsWith("auth_loss_join ")) {
                        secondSignalJoined.complete(Unit)
                    }
                }
            )
        // Subscribe before the owner can emit because this SharedFlow has no replay.
        val resolution = async(start = CoroutineStart.UNDISPATCHED) {
            funnel.resolutions.first()
        }

        val first =
            funnel.report(
                AuthLossSignal(source = "thread", tokenFingerprint = "token-a")
            )
        val second =
            funnel.report(
                AuthLossSignal(source = "home", tokenFingerprint = "token-a")
            )
        restoreStarted.await()
        // The duplicate report must join the held restore before it is allowed to finish.
        secondSignalJoined.await()

        assertEquals(1, restoreCount)
        releaseRestore.complete(Unit)
        restoreFinished.await()
        assertIs<AuthLossDecision.SessionPreserved>(first.await())
        assertIs<AuthLossDecision.SessionPreserved>(second.await())
        assertEquals(1, restoreCount)
        assertIs<AuthLossDecision.SessionPreserved>(resolution.await().decision)
        scope.cancel()
    }

    @Test
    fun diagnosticFingerprintCollisionDoesNotMergeDifferentCapturedAuthorities() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob())
        val bothRestoresStarted = CompletableDeferred<Unit>()
        val releaseRestores = CompletableDeferred<Unit>()
        val restoreCountMutex = Mutex()
        var restoreCount = 0
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = {
                    // Both restores run concurrently on Dispatchers.Default.
                    restoreCountMutex.withLock {
                        restoreCount += 1
                        if (restoreCount == 2) bothRestoresStarted.complete(Unit)
                    }
                    releaseRestores.await()
                    SharedAuthResult.Success(storedSession())
                },
                clearSessionIfMatches = { _, _, _ -> error("session must not clear") },
                scope = scope,
            )
        val firstSession = storedSession()
        val secondSession =
            firstSession.copy(
                user = StoredAuthUser(id = "user-2", email = "user2@example.com"),
                accessToken = "second-access",
                refreshToken = "second-refresh",
            )

        val first =
            funnel.report(
                AuthLossSignal(
                    source = "thread",
                    tokenFingerprint = "same-diagnostic-fingerprint",
                    capturedSession = firstSession,
                )
            )
        val second =
            funnel.report(
                AuthLossSignal(
                    source = "home",
                    tokenFingerprint = "same-diagnostic-fingerprint",
                    capturedSession = secondSession,
                )
            )

        try {
            withTimeout(10_000) {
                bothRestoresStarted.await()
                assertEquals(2, restoreCountMutex.withLock { restoreCount })
                releaseRestores.complete(Unit)
                assertIs<AuthLossDecision.SessionPreserved>(first.await())
                assertIs<AuthLossDecision.SessionPreserved>(second.await())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recoverableRestoreFailurePreservesSessionAndDropsPendingRoute() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        var clearCount = 0
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = {
                    SharedAuthResult.Failure(
                        SharedAuthErrorCode.Network,
                        "Network unavailable"
                    )
                },
                clearSessionIfMatches = { _, _, _ ->
                    clearCount += 1
                    AuthSessionClearOutcome.Cleared
                },
                scope = scope
            )

        val decision =
            funnel.report(
                AuthLossSignal(
                    source = "thread",
                    tokenFingerprint = "token-a",
                    pendingOpenRequest = pendingThreadRequest()
                )
            ).await()

        assertIs<AuthLossDecision.SessionPreserved>(decision)
        assertEquals(0, clearCount)
        assertNull(funnel.peekPendingOpenRequest())
        scope.cancel()
    }

    @Test
    fun unauthenticatedRestoreClearsSessionAndKeepsPendingRouteUntilMatchingServerConsumesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val clearCalls = mutableListOf<Pair<String, String>>()
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = { SharedAuthResult.Unauthenticated },
                clearSessionIfMatches = { _, reason, source ->
                    clearCalls += reason to source
                    AuthSessionClearOutcome.Cleared
                },
                scope = scope
            )
        val resolution = async(start = CoroutineStart.UNDISPATCHED) {
            funnel.resolutions.first()
        }

        val decision =
            funnel.report(
                AuthLossSignal(
                    source = "thread:thread-1",
                    requestId = "request-1",
                    tokenFingerprint = "token-a",
                    capturedSession = storedSession(),
                    pendingOpenRequest = pendingThreadRequest()
                )
            ).await()

        assertIs<AuthLossDecision.AuthRequired>(decision)
        funnel.activatePendingOpenRequest(resolution.await())
        assertEquals(
            listOf("auth_loss_unavailable" to "auth_loss_funnel:thread:thread-1"),
            clearCalls
        )
        assertNull(
            funnel.consumePendingOpenRequest(
                serverId = "server-2",
                currentAccountPrincipalId = "user-1",
            )
        )
        val pending =
            assertIs<AuthPendingOpenRequest.Thread>(
                funnel.consumePendingOpenRequest(
                    serverId = "server-1",
                    currentAccountPrincipalId = "user-1",
                )
            )
        assertEquals("thread-1", pending.sourceThreadId)
        assertNull(
            funnel.consumePendingOpenRequest(
                serverId = "server-1",
                currentAccountPrincipalId = "user-1",
            )
        )
        scope.cancel()
    }

    @Test
    fun pendingRouteIsAtomicallyDroppedWhenDifferentAccountSelectsSameServer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = { SharedAuthResult.Unauthenticated },
                clearSessionIfMatches = { _, _, _ -> AuthSessionClearOutcome.AlreadyAbsent },
                scope = scope,
            )
        val emitted = async(start = CoroutineStart.UNDISPATCHED) {
            funnel.resolutions.first()
        }

        assertIs<AuthLossDecision.AuthRequired>(
            funnel.report(
                AuthLossSignal(
                    source = "thread",
                    tokenFingerprint = "old-token",
                    pendingOpenRequest = pendingThreadRequest(),
                )
            ).await()
        )
        funnel.activatePendingOpenRequest(emitted.await())

        assertNull(
            funnel.consumePendingOpenRequest(
                serverId = "server-1",
                currentAccountPrincipalId = "user-2",
            )
        )
        assertNull(funnel.peekPendingOpenRequest())
        scope.cancel()
    }

    @Test
    fun pendingRouteCanResumeForSameAccountAfterTokensChange() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = { SharedAuthResult.Unauthenticated },
                clearSessionIfMatches = { _, _, _ -> AuthSessionClearOutcome.AlreadyAbsent },
                scope = scope,
            )
        val emitted = async(start = CoroutineStart.UNDISPATCHED) {
            funnel.resolutions.first()
        }

        assertIs<AuthLossDecision.AuthRequired>(
            funnel.report(
                AuthLossSignal(
                    source = "thread",
                    tokenFingerprint = "old-token",
                    pendingOpenRequest = pendingThreadRequest(),
                )
            ).await()
        )
        funnel.activatePendingOpenRequest(emitted.await())

        val resumed =
            assertIs<AuthPendingOpenRequest.Thread>(
                funnel.consumePendingOpenRequest(
                    serverId = "server-1",
                    currentAccountPrincipalId = "user-1",
                )
            )
        assertEquals("thread-1", resumed.sourceThreadId)
        assertNull(funnel.peekPendingOpenRequest())
        scope.cancel()
    }

    @Test
    fun clearFailureStillPublishesAuthRequired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val resolution = async {
            val funnel =
                SharedAuthLossFunnel(
                    restoreSession = { SharedAuthResult.Unauthenticated },
                    clearSessionIfMatches = { _, _, _ -> error("storage unavailable") },
                    scope = scope
                )
            funnel.report(
                AuthLossSignal(source = "thread", tokenFingerprint = "token-a")
            ).await()
        }

        assertIs<AuthLossDecision.AuthRequired>(resolution.await())
        scope.cancel()
    }

    @Test
    fun clearFailureResolutionAtomicallyClearsCapturedAuthorityBeforeTerminalEffect() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val captured = storedSession()
        val store = KeyValueAuthSessionStore(InMemorySlockKeyValueStore())
        store.replaceAuthenticatedSession(captured)
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = { SharedAuthResult.Unauthenticated },
                clearSessionIfMatches = { _, _, _ -> error("first clear unavailable") },
                scope = scope,
            )
        val emitted = async(start = CoroutineStart.UNDISPATCHED) {
            funnel.resolutions.first()
        }

        val decision =
            funnel.report(
                AuthLossSignal(
                    source = "thread",
                    tokenFingerprint = captured.accessToken.authLossTokenFingerprint(),
                    capturedSession = captured,
                    pendingOpenRequest = pendingThreadRequest(),
                )
            ).await()

        assertEquals(AuthLossDecision.AuthRequired("restore_unauthenticated"), decision)
        assertEquals(captured, store.readSession())
        val resolution = emitted.await()
        val admitted = store.readAuthSessionTerminalSnapshot()
        var terminalEffectCount = 0
        val applied =
            appShellRunIfAuthSessionTerminalOrAuthorityRevision(
                terminalSource = store,
                expectedGeneration = admitted.generation,
                expectedSession = resolution.signal.capturedSession,
            ) {
                terminalEffectCount += 1
            }

        kotlin.test.assertNotNull(applied)
        assertEquals(1, terminalEffectCount)
        assertNull(store.readSession())
        scope.cancel()
    }

    @Test
    fun unauthenticatedRestoreDoesNotClearOrPublishAuthRequiredAfterAuthorityChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val captured = storedSession()
        val successor =
            captured.copy(
                user = StoredAuthUser(id = "user-2", email = "successor@example.com"),
                accessToken = "successor-access",
                refreshToken = "successor-refresh",
            )
        var current: StoredAuthSession? = captured
        var clearCalls = 0
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = {
                    current = successor
                    SharedAuthResult.Unauthenticated
                },
                clearSessionIfMatches = { expected, _, _ ->
                    clearCalls += 1
                    if (current == expected) {
                        current = null
                        AuthSessionClearOutcome.Cleared
                    } else {
                        AuthSessionClearOutcome.AuthorityChanged
                    }
                },
                scope = scope,
            )

        val decision =
            funnel.report(
                AuthLossSignal(
                    source = "thread",
                    tokenFingerprint = captured.accessToken.authLossTokenFingerprint(),
                    capturedSession = captured,
                    pendingOpenRequest = pendingThreadRequest(),
                )
            ).await()

        assertEquals(AuthLossDecision.SessionPreserved("auth_authority_changed"), decision)
        assertEquals(1, clearCalls)
        assertEquals(successor, current)
        assertNull(funnel.peekPendingOpenRequest())
        scope.cancel()
    }

    @Test
    fun restoreThatAlreadyClearedCapturedAuthorityStillPublishesAuthRequired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val captured = storedSession()
        val store = InMemorySlockAuthSessionStore(captured)
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = {
                    assertEquals(
                        AuthSessionClearOutcome.Cleared,
                        store.clearSessionIfMatchesOutcome(
                            expectedAccountId = captured.user.id,
                            expectedAccessToken = captured.accessToken,
                            expectedRefreshToken = captured.refreshToken,
                            reason = "restore_refresh_unauthorized",
                            source = "test_restore",
                        )
                    )
                    SharedAuthResult.Unauthenticated
                },
                clearSessionIfMatches = { expected, reason, source ->
                    store.clearSessionIfMatchesOutcome(
                        expectedAccountId = expected.user.id,
                        expectedAccessToken = expected.accessToken,
                        expectedRefreshToken = expected.refreshToken,
                        reason = reason,
                        source = source,
                    )
                },
                scope = scope,
            )
        val resolution = async(start = CoroutineStart.UNDISPATCHED) {
            funnel.resolutions.first()
        }

        val decision =
            funnel.report(
                AuthLossSignal(
                    source = "thread",
                    tokenFingerprint = captured.accessToken.authLossTokenFingerprint(),
                    capturedSession = captured,
                    pendingOpenRequest = pendingThreadRequest(),
                )
            ).await()

        assertEquals(AuthLossDecision.AuthRequired("restore_unauthenticated"), decision)
        assertNull(store.readSession())
        funnel.activatePendingOpenRequest(resolution.await())
        val pending = assertIs<AuthPendingOpenRequest.Thread>(funnel.peekPendingOpenRequest())
        funnel.discardPendingOpenRequest(resolution.await())
        assertNull(funnel.peekPendingOpenRequest())
        scope.cancel()
    }

    @Test
    fun joinedPendingRouteIsSelectedByResolutionAndCannotReachSuccessorBeforeEffectAcceptance() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val restoreStarted = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        val captured = storedSession()
        val successor =
            captured.copy(
                user = StoredAuthUser(id = "user-2", email = "successor@example.com"),
                accessToken = "successor-access",
                refreshToken = "successor-refresh",
            )
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = {
                    restoreStarted.complete(Unit)
                    releaseRestore.await()
                    SharedAuthResult.Unauthenticated
                },
                clearSessionIfMatches = { _, _, _ -> AuthSessionClearOutcome.AlreadyAbsent },
                scope = scope,
                diagnosticsLogger = { message ->
                    if (message.startsWith("auth_loss_join ")) joined.complete(Unit)
                },
            )
        val emitted = async(start = CoroutineStart.UNDISPATCHED) {
            funnel.resolutions.first()
        }
        val owner =
            funnel.report(
                AuthLossSignal(
                    source = "home",
                    tokenFingerprint = captured.accessToken.authLossTokenFingerprint(),
                    capturedSession = captured,
                )
            )
        restoreStarted.await()
        val joiner =
            funnel.report(
                AuthLossSignal(
                    source = "thread",
                    tokenFingerprint = captured.accessToken.authLossTokenFingerprint(),
                    capturedSession = captured,
                    pendingOpenRequest = pendingThreadRequest(),
                )
            )
        joined.await()
        releaseRestore.complete(Unit)

        assertIs<AuthLossDecision.AuthRequired>(owner.await())
        assertIs<AuthLossDecision.AuthRequired>(joiner.await())
        val resolution = emitted.await()
        assertNull(resolution.signal.pendingOpenRequest)
        assertIs<AuthPendingOpenRequest.Thread>(resolution.pendingOpenRequest)
        assertNull(funnel.peekPendingOpenRequest())
        assertNull(
            funnel.consumePendingOpenRequest(
                serverId = "server-1",
                currentAccountPrincipalId = successor.user.id,
            )
        )
        kotlin.test.assertFalse(
            appShellAuthLossEffectShouldApply(resolution.signal.capturedSession, successor)
        )
        funnel.discardPendingOpenRequest(resolution)
        assertNull(funnel.peekPendingOpenRequest())
        scope.cancel()
    }

    private fun pendingThreadRequest(): AuthPendingOpenRequest.Thread =
        AuthPendingOpenRequest.Thread(
            serverId = "server-1",
            accountPrincipalId = "user-1",
            surfaceKind = "channel",
            channelKind = "channel",
            threadId = "thread-1",
            sourceThreadId = "thread-1",
            streamChannelId = "thread-1",
            parentChannelId = null,
            parentMessageId = null,
            parentSurfaceKind = null,
            parentChannelKind = null,
            parentNavigationHint = null,
            originParentMessageId = null,
            focusedMessageId = "message-1",
            selectedTab = "chat"
        )

    private fun storedSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.com"),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = "server-1"
        )
}
