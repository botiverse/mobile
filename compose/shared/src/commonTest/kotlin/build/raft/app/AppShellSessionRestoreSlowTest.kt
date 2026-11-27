package build.raft.app

import build.raft.app.data.SharedAuthGateIntent
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.SharedAuthGateStore
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.shell.appShellRestoreSignOut
import build.raft.app.thread.dispatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Web #15116: the actions the slow cold-restore splash offers ([AppShellSessionRestoreSplash]).
 * Try again is the gate's Retry (a fresh restore); Sign out is the email-verification gate's
 * sign-out (clear the session, leave realtime, then SignedOut), and the restore still hanging
 * cannot land after it. Virtual time throughout.
 */
class AppShellSessionRestoreSlowTest {
    private val session =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@slock.ai", displayName = "Artin", emailVerified = true, profileSetupCompletedAt = "done"),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = "server-1",
            selectedServerSlug = "slock",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock")),
        )

    @Test
    fun tryAgainStartsAFreshRestoreAndItsAnswerLands() = runTest {
        val attempts = mutableListOf<CompletableDeferred<SharedAuthResult<StoredAuthSession>>>()
        val store =
            SharedAuthGateStore(
                restoreSession = { CompletableDeferred<SharedAuthResult<StoredAuthSession>>().also { attempts += it }.await() },
                scope = backgroundScope,
            )
        store.dispatch(SharedAuthGateIntent.Restore)
        runCurrent()
        assertEquals(1, attempts.size)
        assertEquals(SharedAuthGateState.Restoring, store.state.value)

        store.dispatch(SharedAuthGateIntent.Retry)
        runCurrent()
        assertEquals(2, attempts.size, "Try again restarts the restore")
        assertEquals(SharedAuthGateState.Restoring, store.state.value)

        // The first, abandoned request answering late does not count; the fresh one does.
        attempts[0].complete(SharedAuthResult.Unauthenticated)
        runCurrent()
        assertEquals(SharedAuthGateState.Restoring, store.state.value)
        attempts[1].complete(SharedAuthResult.Success(session))
        runCurrent()
        assertEquals(SharedAuthGateState.Ready(session), store.state.value)
    }

    @Test
    fun signOutClearsTheSessionThenLeavesRealtimeThenSignsOutAndTheHangingRestoreCannotLand() = runTest {
        val hanging = CompletableDeferred<SharedAuthResult<StoredAuthSession>>()
        val store = SharedAuthGateStore(restoreSession = { hanging.await() }, scope = backgroundScope)
        store.dispatch(SharedAuthGateIntent.Restore)
        runCurrent()
        assertEquals(SharedAuthGateState.Restoring, store.state.value)

        val steps = mutableListOf<String>()
        appShellRestoreSignOut(
            logout = { steps += "logout" },
            disconnectRealtime = { steps += "realtime_disconnect" },
            dispatch = { intent ->
                steps += "dispatch:$intent"
                store.dispatch(intent)
            },
        )
        assertEquals(listOf("logout", "realtime_disconnect", "dispatch:${SharedAuthGateIntent.SignedOut}"), steps)
        assertEquals(SharedAuthGateState.AuthRequired, store.state.value)

        // The restore that was hanging answers after the sign-out: it was cancelled, nothing lands.
        hanging.complete(SharedAuthResult.Success(session))
        runCurrent()
        assertEquals(SharedAuthGateState.AuthRequired, store.state.value)
    }
}
