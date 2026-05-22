package ai.slock.compose.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedAuthGateStoreTest {
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
    fun retryUsesSameRestoreStateMachine() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
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
                scope = scope
            )

        store.accept(SharedAuthGateIntent.Restore)
        assertTrue(store.state.value is SharedAuthGateState.Error)

        store.accept(SharedAuthGateIntent.Retry)

        assertTrue(store.state.value is SharedAuthGateState.Ready)
        scope.cancel()
    }

    private fun storedSession(selectedServerId: String?): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@slock.ai", displayName = "Artin"),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = selectedServerId,
            selectedServerSlug = selectedServerId?.let { "slock" },
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
        )
}
