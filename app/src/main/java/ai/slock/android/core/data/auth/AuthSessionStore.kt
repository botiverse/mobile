package ai.slock.android.core.data.auth

interface AuthSessionStore {
    suspend fun readSession(): AuthSession?
    suspend fun saveSession(session: AuthSession)
    suspend fun updateTokens(accessToken: String, refreshToken: String)
    suspend fun updateSelectedServer(serverId: String?, serverSlug: String?)
    suspend fun clearSession()
}
