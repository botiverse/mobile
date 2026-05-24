package ai.slock.android.core.data.auth

import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthErrorCode
import ai.slock.compose.data.SharedAuthResult
import ai.slock.compose.data.SlockAuthApi
import ai.slock.compose.data.SlockAuthApiClient
import ai.slock.compose.data.SlockAuthTransport
import ai.slock.compose.data.StoredAuthSession
import kotlinx.coroutines.CancellationException

class KtorAuthRepository(
    private val store: AuthSessionStore,
    private val api: SlockAuthApi = createAuthApi(),
    private val logger: (String) -> Unit = { message -> ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(TAG, message) },
    private val warningLogger: (String) -> Unit = { message -> ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(TAG, message) }
) : AuthRepository {
    private val sharedAuthDataSource =
        SharedAuthDataSource(
            api = api,
            sessionStore = ComposeAuthSessionStoreAdapter(store)
        )

    override suspend fun login(email: String, password: String): AuthResult<AuthSessionSummary> =
        sharedAuthDataSource.login(email, password).toAndroidAuthSummary()

    override suspend fun register(
        name: String,
        email: String,
        password: String
    ): AuthResult<AuthSessionSummary> =
        sharedAuthDataSource.register(name = name, email = email, password = password).toAndroidAuthSummary()

    override suspend fun forgotPassword(email: String): AuthResult<Unit> =
        sharedAuthDataSource.forgotPassword(email).toAndroidUnit()

    override suspend fun restoreSession(): AuthResult<AuthSessionSummary> {
        val session = store.readSession()
        if (session == null) {
            warningLogger("restore_session_missing")
        } else {
            logger(
                "restore_session_found serverCount=${session.servers.size} selectedServer=${session.currentServerId != null}"
            )
        }
        return sharedAuthDataSource.restoreSession().toAndroidAuthSummary()
    }

    override suspend fun currentUser(): AuthResult<AuthSessionSummary> =
        sharedAuthDataSource.restoreSession().toAndroidAuthSummary()

    override suspend fun refresh(refreshToken: String): AuthResult<AuthSessionSummary> =
        sharedAuthDataSource.refresh(refreshToken).toAndroidAuthSummary()

    override suspend fun logout(): AuthResult<Unit> =
        try {
            sharedAuthDataSource.logout().toAndroidUnit()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            store.clearSession()
            AuthResult.Success(Unit)
        }

    private fun SharedAuthResult<StoredAuthSession>.toAndroidAuthSummary(): AuthResult<AuthSessionSummary> =
        when (this) {
            is SharedAuthResult.Success -> AuthResult.Success(data.toAndroidAuthSession().summary())
            SharedAuthResult.Unauthenticated -> AuthResult.Unauthenticated
            is SharedAuthResult.Failure -> toAndroidAuthFailure()
        }

    private fun SharedAuthResult<Unit>.toAndroidUnit(): AuthResult<Unit> =
        when (this) {
            is SharedAuthResult.Success -> AuthResult.Success(Unit)
            SharedAuthResult.Unauthenticated -> AuthResult.Unauthenticated
            is SharedAuthResult.Failure -> toAndroidAuthFailure()
        }

    private fun SharedAuthResult.Failure.toAndroidAuthFailure(): AuthResult.Failure =
        AuthResult.Failure(
            code =
                when (code) {
                    SharedAuthErrorCode.Validation -> AuthErrorCode.Validation
                    SharedAuthErrorCode.InvalidCredentials -> AuthErrorCode.InvalidCredentials
                    SharedAuthErrorCode.Network -> AuthErrorCode.Network
                    SharedAuthErrorCode.Server -> AuthErrorCode.Server
                    SharedAuthErrorCode.Unknown -> AuthErrorCode.Unknown
                },
            message = message
        )

    private fun AuthSession.summary(): AuthSessionSummary =
        AuthSessionSummary(
            user = user,
            selectedServerId = selectedServerId,
            selectedServerSlug = selectedServerSlug,
            servers = servers
        )

    companion object {
        const val BASE_URL = "https://api.slock.ai/api/"
        private const val TAG = "SlockAuth"

        fun createAuthTransport(baseUrl: String = BASE_URL): SlockAuthTransport =
            KtorAuthApi(baseUrl)

        fun createAuthApi(baseUrl: String = BASE_URL): SlockAuthApi =
            SlockAuthApiClient(createAuthTransport(baseUrl))
    }
}
