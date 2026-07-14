package build.raft.app

import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.SharedAuthGateIntent
import build.raft.app.data.SharedAuthGateStore
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsHomePageAuthGateTest {
    @Test
    fun homeAuthErrorDoesNotOverrideRestore() {
        assertFalse(
            shouldApplyHomeAuthErrorGate(
                errorCode = "unauthenticated",
                authGateState = SharedAuthGateState.Restoring,
                cachedSession = null
            )
        )
    }

    @Test
    fun homeAuthErrorDoesNotOverrideCachedSession() {
        assertFalse(
            shouldApplyHomeAuthErrorGate(
                errorCode = "unauthenticated",
                authGateState = SharedAuthGateState.AuthRequired,
                cachedSession = storedSession()
            )
        )
    }

    @Test
    fun homeAuthErrorAppliesOnlyAfterSessionIsConfirmedMissing() {
        assertTrue(
            shouldApplyHomeAuthErrorGate(
                errorCode = "unauthenticated",
                authGateState = SharedAuthGateState.AuthRequired,
                cachedSession = null
            )
        )
    }

    @Test
    fun nonAuthHomeErrorDoesNotApplyAuthGate() {
        assertFalse(
            shouldApplyHomeAuthErrorGate(
                errorCode = "network",
                authGateState = SharedAuthGateState.AuthRequired,
                cachedSession = null
            )
        )
    }

    @Test
    fun sessionChangeSameIdentityUsesSilentRefreshIntent() {
        val current = storedSession(userId = "user-1", selectedServerId = "server-1", accessToken = "access-token-1")
        val cached = storedSession(userId = "user-1", selectedServerId = "server-1", accessToken = "access-token-2")

        val identityChanged = appShellSessionIdentityChanged(cached, current)

        assertFalse(identityChanged)
        assertEquals(SharedAuthGateIntent.RefreshSilently, appShellSessionChangeRestoreIntent(identityChanged))
    }

    @Test
    fun selectedServerChangeDoesNotMasqueradeAsIdentityChange() {
        val current = storedSession(userId = "user-1", selectedServerId = "server-1")
        val cached = storedSession(userId = "user-1", selectedServerId = "server-2")

        val identityChanged = appShellSessionIdentityChanged(cached, current)

        assertFalse(identityChanged)
        assertEquals(SharedAuthGateIntent.RefreshSilently, appShellSessionChangeRestoreIntent(identityChanged))
    }

    @Test
    fun bindingMetadataChangeSilentlyRefreshesTheLiveAuthGate() {
        assertEquals(
            SharedAuthGateIntent.RefreshSilently,
            appShellAuthSessionEventRestoreIntent(AuthSessionEvent.BindingMetadataChanged(generation = 7))
        )
        assertEquals(
            null,
            appShellAuthSessionEventRestoreIntent(AuthSessionEvent.TokensRotated(generation = 8))
        )
    }

    @Test
    fun restoringBootstrapStillProcessesNonReplayableAuthSignals() {
        assertTrue(appShellDefersReadyGraph(SharedAuthGateState.Restoring))
        assertEquals(
            AppShellBootstrapAuthDecision(
                intent = SharedAuthGateIntent.SignedOut,
                resetSessionState = true
            ),
            appShellBootstrapAuthDecision(AppShellBootstrapAuthSignal.SessionInvalidated)
        )
        assertEquals(
            AppShellBootstrapAuthDecision(
                intent = SharedAuthGateIntent.Restore,
                resetSessionState = true
            ),
            appShellBootstrapAuthDecision(
                AppShellBootstrapAuthSignal.SessionChanged,
                identityChanged = true
            )
        )
        assertEquals(
            AppShellBootstrapAuthDecision(
                intent = SharedAuthGateIntent.RefreshSilently,
                resetSessionState = false
            ),
            appShellBootstrapAuthDecision(AppShellBootstrapAuthSignal.BindingMetadataChanged)
        )
    }

    @Test
    fun appShellInitialRestoreInvalidationCancelsCachedReadyAdmission() = runBlocking {
        val cachedReadStarted = CompletableDeferred<Unit>()
        val releaseCachedRead = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val staleSession = storedSession(accessToken = "stale-access")
        val gateStore =
            SharedAuthGateStore(
                restoreSession = { SharedAuthResult.Success(staleSession) },
                cachedSession = {
                    cachedReadStarted.complete(Unit)
                    releaseCachedRead.await()
                    staleSession
                },
                scope = scope
            )

        requestAppShellInitialAuthRestore(gateStore::dispatch)
        cachedReadStarted.await()
        assertEquals(SharedAuthGateState.Restoring, gateStore.state.value)

        val invalidation =
            appShellBootstrapAuthDecision(AppShellBootstrapAuthSignal.SessionInvalidated)
        gateStore.dispatch(invalidation.intent)
        releaseCachedRead.complete(Unit)
        yield()

        assertTrue(invalidation.resetSessionState)
        assertEquals(SharedAuthGateState.AuthRequired, gateStore.state.value)
        scope.cancel()
    }

    private fun storedSession(
        userId: String = "user-1",
        selectedServerId: String = "server-1",
        accessToken: String = "access-token"
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "user@slock.ai", displayName = "Artin"),
            accessToken = accessToken,
            refreshToken = "refresh-token",
            selectedServerId = selectedServerId,
            selectedServerSlug = "slock",
            servers = listOf(StoredAuthServer(id = selectedServerId, name = "Slock", slug = "slock"))
        )
}
