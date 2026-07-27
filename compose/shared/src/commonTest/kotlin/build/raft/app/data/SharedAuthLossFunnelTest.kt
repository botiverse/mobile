package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
                clearSession = { _, _ -> error("session must not clear") },
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
                clearSession = { _, _ -> clearCount += 1 },
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
                clearSession = { reason, source -> clearCalls += reason to source },
                scope = scope
            )

        val decision =
            funnel.report(
                AuthLossSignal(
                    source = "thread:thread-1",
                    requestId = "request-1",
                    tokenFingerprint = "token-a",
                    pendingOpenRequest = pendingThreadRequest()
                )
            ).await()

        assertIs<AuthLossDecision.AuthRequired>(decision)
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
                clearSession = { _, _ -> Unit },
                scope = scope,
            )

        assertIs<AuthLossDecision.AuthRequired>(
            funnel.report(
                AuthLossSignal(
                    source = "thread",
                    tokenFingerprint = "old-token",
                    pendingOpenRequest = pendingThreadRequest(),
                )
            ).await()
        )

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
                clearSession = { _, _ -> Unit },
                scope = scope,
            )

        assertIs<AuthLossDecision.AuthRequired>(
            funnel.report(
                AuthLossSignal(
                    source = "thread",
                    tokenFingerprint = "old-token",
                    pendingOpenRequest = pendingThreadRequest(),
                )
            ).await()
        )

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
                    clearSession = { _, _ -> error("storage unavailable") },
                    scope = scope
                )
            funnel.report(
                AuthLossSignal(source = "thread", tokenFingerprint = "token-a")
            ).await()
        }

        assertIs<AuthLossDecision.AuthRequired>(resolution.await())
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
