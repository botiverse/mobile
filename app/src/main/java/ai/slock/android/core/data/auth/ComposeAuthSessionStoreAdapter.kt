package ai.slock.android.core.data.auth

import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser

class ComposeAuthSessionStoreAdapter(
    private val delegate: AuthSessionStore
) : SlockAuthSessionStore {
    override suspend fun readSession(): StoredAuthSession? =
        delegate.readSession()?.toStoredAuthSession()

    override suspend fun saveSession(session: StoredAuthSession) {
        delegate.saveSession(session.toAndroidAuthSession())
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        delegate.updateTokens(accessToken, refreshToken)
    }

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        delegate.updateSelectedServer(serverId, serverSlug)
    }

    override suspend fun clearSession() {
        delegate.clearSession()
    }
}

fun AuthSession.toStoredAuthSession(): StoredAuthSession =
    StoredAuthSession(
        user = user.toStoredAuthUser(),
        accessToken = accessToken,
        refreshToken = refreshToken,
        selectedServerId = selectedServerId,
        selectedServerSlug = selectedServerSlug,
        servers = servers.map { it.toStoredAuthServer() }
    )

fun StoredAuthSession.toAndroidAuthSession(): AuthSession =
    AuthSession(
        user = user.toAndroidAuthUser(),
        accessToken = accessToken,
        refreshToken = refreshToken,
        selectedServerId = selectedServerId,
        selectedServerSlug = selectedServerSlug,
        servers = servers.map { it.toAndroidAuthServer() }
    )

private fun AuthUser.toStoredAuthUser(): StoredAuthUser =
    StoredAuthUser(
        id = id,
        email = email,
        name = name,
        displayName = displayName,
        description = description,
        avatarUrl = avatarUrl,
        gravatarHash = gravatarHash,
        emailVerified = emailVerified
    )

private fun StoredAuthUser.toAndroidAuthUser(): AuthUser =
    AuthUser(
        id = id,
        email = email,
        name = name,
        displayName = displayName,
        description = description,
        avatarUrl = avatarUrl,
        gravatarHash = gravatarHash,
        emailVerified = emailVerified
    )

private fun AuthServer.toStoredAuthServer(): StoredAuthServer =
    StoredAuthServer(
        id = id,
        name = name,
        slug = slug,
        ownerId = ownerId,
        onboardingAgentId = onboardingAgentId,
        plan = plan,
        planDowngradedAt = planDowngradedAt,
        role = role,
        createdAt = createdAt
    )

private fun StoredAuthServer.toAndroidAuthServer(): AuthServer =
    AuthServer(
        id = id,
        name = name,
        slug = slug,
        ownerId = ownerId,
        onboardingAgentId = onboardingAgentId,
        plan = plan,
        planDowngradedAt = planDowngradedAt,
        role = role,
        createdAt = createdAt
    )
