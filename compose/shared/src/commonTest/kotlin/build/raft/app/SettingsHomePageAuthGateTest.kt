package build.raft.app

import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsHomePageAuthGateTest {
    @Test
    fun homeAuthErrorDoesNotOverrideRestore() {
        assertFalse(
            shouldRestoreHomeAuthErrorGate(
                errorCode = "unauthenticated",
                authGateState = SharedAuthGateState.Restoring,
                cachedSession = null
            )
        )
    }

    @Test
    fun homeAuthErrorRestoresCachedSession() {
        assertTrue(
            shouldRestoreHomeAuthErrorGate(
                errorCode = "unauthenticated",
                authGateState = SharedAuthGateState.AuthRequired,
                cachedSession = storedSession()
            )
        )
    }

    @Test
    fun homeAuthErrorDoesNotForceSignedOutWhenSessionIsMissing() {
        assertFalse(
            shouldRestoreHomeAuthErrorGate(
                errorCode = "unauthenticated",
                authGateState = SharedAuthGateState.AuthRequired,
                cachedSession = null
            )
        )
    }

    @Test
    fun activeHomeAuthErrorRequestsRestoreBeforeLogin() {
        assertTrue(
            shouldRestoreHomeAuthErrorGate(
                errorCode = "unauthenticated",
                authGateState = SharedAuthGateState.Ready(storedSession()),
                cachedSession = null
            )
        )
    }

    @Test
    fun nonAuthHomeErrorDoesNotApplyAuthGate() {
        assertFalse(
            shouldRestoreHomeAuthErrorGate(
                errorCode = "network",
                authGateState = SharedAuthGateState.Ready(storedSession()),
                cachedSession = null
            )
        )
    }

    private fun storedSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@slock.ai", displayName = "Artin"),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = "server-1",
            selectedServerSlug = "slock",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
        )
}
