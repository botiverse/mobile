package build.raft.app

import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.SharedAuthGateIntent
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
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
