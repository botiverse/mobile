package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
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
        emailVerified: Boolean? = null
    ): StoredAuthSession =
        StoredAuthSession(
            user =
                StoredAuthUser(
                    id = "user-1",
                    email = "user@slock.ai",
                    displayName = "Artin",
                    emailVerified = emailVerified
                ),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = selectedServerId,
            selectedServerSlug = selectedServerId?.let { "slock" },
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
        )
}
