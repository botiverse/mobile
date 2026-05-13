package ai.slock.android.core.data.auth

interface AuthRepository {
    suspend fun login(email: String, password: String): AuthResult<AuthSessionSummary>
    suspend fun register(name: String, email: String, password: String): AuthResult<AuthSessionSummary>
    suspend fun forgotPassword(email: String): AuthResult<Unit>
    suspend fun restoreSession(): AuthResult<AuthSessionSummary>
    suspend fun currentUser(): AuthResult<AuthSessionSummary>
    suspend fun refresh(refreshToken: String): AuthResult<AuthSessionSummary>
    suspend fun logout(): AuthResult<Unit>
}
