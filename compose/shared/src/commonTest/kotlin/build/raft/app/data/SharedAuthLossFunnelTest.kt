package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SharedAuthLossFunnelTest {
    @Test
    fun concurrentSignalsForSameTokenRestoreOnlyOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val restoreStarted = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        var restoreCount = 0
        val resolutions = mutableListOf<AuthLossResolution>()
        val funnel =
            SharedAuthLossFunnel(
                restoreSession = {
                    restoreCount += 1
                    restoreStarted.complete(Unit)
                    releaseRestore.await()
                    SharedAuthResult.Success(storedSession())
                },
                clearSession = { _, _ -> error("session must not clear") },
                scope = scope
            )
        val resolutionCollector = launch {
            funnel.resolutions.collect { resolutions += it }
        }
        yield()

        val first =
            funnel.report(
                AuthLossSignal(source = "thread", tokenFingerprint = "token-a")
            )
        val second =
            funnel.report(
                AuthLossSignal(source = "home", tokenFingerprint = "token-a")
            )
        restoreStarted.await()
        yield()

        assertEquals(1, restoreCount)
        releaseRestore.complete(Unit)
        assertIs<AuthLossDecision.SessionPreserved>(first.await())
        assertIs<AuthLossDecision.SessionPreserved>(second.await())
        assertEquals(1, restoreCount)
        yield()
        assertEquals(1, resolutions.size)
        resolutionCollector.cancel()
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
        assertNull(funnel.consumePendingOpenRequest("server-2"))
        val pending = assertIs<AuthPendingOpenRequest.Thread>(funnel.consumePendingOpenRequest("server-1"))
        assertEquals("thread-1", pending.sourceThreadId)
        assertNull(funnel.consumePendingOpenRequest("server-1"))
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
