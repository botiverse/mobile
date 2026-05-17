package ai.slock.android.core.data.auth

import ai.slock.android.core.data.network.ApiResponse
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedAuthErrorCode
import ai.slock.compose.data.SharedAuthResult
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

class KtorAuthRepository(
    private val store: AuthSessionStore,
    private val api: AuthApi = createAuthApi(),
    private val logger: (String) -> Unit = { message -> ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(TAG, message) },
    private val warningLogger: (String) -> Unit = { message -> ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(TAG, message) }
) : AuthRepository {
    private val gson = Gson()
    private val sharedAuthDataSource =
        SharedAuthDataSource(
            api = AndroidSlockAuthApi(api),
            sessionStore = ComposeAuthSessionStoreAdapter(store)
        )

    override suspend fun login(email: String, password: String): AuthResult<AuthSessionSummary> {
        return try {
            when (val result = sharedAuthDataSource.login(email, password)) {
                is SharedAuthResult.Success -> AuthResult.Success(result.data.toAndroidSummary())
                SharedAuthResult.Unauthenticated -> AuthResult.Unauthenticated
                is SharedAuthResult.Failure -> result.toAndroidAuthFailure()
            }
        } catch (_: IOException) {
            AuthResult.Failure(AuthErrorCode.Network, "Network unavailable. Check your connection.")
        } catch (_: Exception) {
            AuthResult.Failure(AuthErrorCode.Unknown, "Sign in failed. Try again.")
        }
    }

    override suspend fun register(
        name: String,
        email: String,
        password: String
    ): AuthResult<AuthSessionSummary> {
        if (name.isBlank() || email.isBlank() || password.isBlank()) {
            return AuthResult.Failure(AuthErrorCode.Validation, "Name, email, and password are required.")
        }
        if (password.length < 8) {
            return AuthResult.Failure(AuthErrorCode.Validation, "Password must be at least 8 characters.")
        }

        return executeAuthCall {
            val response = api.register(
                jsonBody(
                    RegisterRequest(
                        email = email.trim(),
                        password = password,
                        name = name.trim()
                    )
                )
            )
            val login = response.parseBody<LoginResponse>()
            saveLoginSession(login)
        }
    }

    override suspend fun forgotPassword(email: String): AuthResult<Unit> {
        if (email.isBlank() || !email.contains("@")) {
            return AuthResult.Failure(AuthErrorCode.Validation, "Enter a valid email address.")
        }

        return executeAuthCall {
            api.forgotPassword(jsonBody(ForgotPasswordRequest(email.trim()))).ensureSuccessful()
            Unit
        }
    }

    override suspend fun restoreSession(): AuthResult<AuthSessionSummary> {
        val session = store.readSession()
        if (session == null) {
            warningLogger("restore_session_missing")
            return AuthResult.Unauthenticated
        }
        logger(
            "restore_session_found serverCount=${session.servers.size} selectedServer=${session.currentServerId != null}"
        )
        return when (val result = currentUserWithSession(session, clearOnUnauthorized = false)) {
            is AuthResult.Success -> {
                logger("restore_current_user_success")
                result
            }
            AuthResult.Unauthenticated -> {
                warningLogger("restore_current_user_unauthenticated_try_refresh")
                val refreshed = refresh(session.refreshToken)
                when (refreshed) {
                    is AuthResult.Success -> {
                        logger("restore_refresh_success")
                        val updated = store.readSession()
                        if (updated == null) {
                            warningLogger("restore_refresh_missing_session")
                            AuthResult.Unauthenticated
                        } else {
                            currentUserWithSession(updated, clearOnUnauthorized = true)
                        }
                    }
                    is AuthResult.Failure -> {
                        if (refreshed.code == AuthErrorCode.Network) {
                            warningLogger("restore_refresh_network_using_cached_session")
                            AuthResult.Success(session.summary())
                        } else {
                            warningLogger("restore_refresh_failure code=${refreshed.code}")
                            refreshed
                        }
                    }
                    AuthResult.Unauthenticated -> {
                        warningLogger("restore_refresh_failed_unauthenticated")
                        AuthResult.Unauthenticated
                    }
                }
            }
            is AuthResult.Failure -> {
                if (result.code == AuthErrorCode.Network) {
                    warningLogger("restore_current_user_network_using_cached_session")
                    AuthResult.Success(session.summary())
                } else {
                    warningLogger("restore_current_user_failure code=${result.code}")
                    result
                }
            }
        }
    }

    override suspend fun currentUser(): AuthResult<AuthSessionSummary> {
        val session = store.readSession() ?: return AuthResult.Unauthenticated
        return currentUserWithSession(session)
    }

    override suspend fun refresh(refreshToken: String): AuthResult<AuthSessionSummary> {
        if (refreshToken.isBlank()) {
            store.clearSession()
            return AuthResult.Unauthenticated
        }

        val current = store.readSession() ?: return AuthResult.Unauthenticated
        return executeAuthCall(
            onUnauthorized = {
                warningLogger("restore_current_user_401_clearing_session")
                store.clearSession()
                AuthResult.Unauthenticated
            }
        ) {
            val response = api.refresh(jsonBody(RefreshRequest(refreshToken)))
            val refreshed = response.parseBody<RefreshResponse>()
            val session = current.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken
            )
            store.saveSession(session)
            session.summary()
        }
    }

    override suspend fun logout(): AuthResult<Unit> {
        val session = store.readSession()
        if (session != null) {
            runCatching {
                api.logout(jsonBody(LogoutRequest(session.refreshToken)))
            }
        }
        store.clearSession()
        return AuthResult.Success(Unit)
    }

    private suspend fun currentUserWithSession(
        session: AuthSession,
        clearOnUnauthorized: Boolean = true
    ): AuthResult<AuthSessionSummary> =
        executeAuthCall(
            onUnauthorized = {
                if (clearOnUnauthorized) {
                    store.clearSession()
                }
                AuthResult.Unauthenticated
            }
        ) {
            val response = api.currentUser(session.bearer())
            val user = response.parseAuthUser()
            val servers = fetchServers(session.accessToken).getOrElse { session.servers }
            val selectedServer = servers.firstOrNull { it.id == session.selectedServerId } ?: servers.firstOrNull()
            val updated = session.copy(
                user = user,
                selectedServerId = selectedServer?.id ?: session.selectedServerId,
                selectedServerSlug = selectedServer?.slug ?: session.selectedServerSlug,
                servers = servers
            )
            store.saveSession(updated)
            updated.summary()
        }

    private suspend fun fetchServers(accessToken: String): Result<List<AuthServer>> =
        runCatching {
            api.servers("Bearer $accessToken").parseAuthServers()
        }

    private suspend fun saveLoginSession(login: LoginResponse): AuthSessionSummary {
        val servers = fetchServers(login.accessToken).getOrElse { emptyList() }
        val selectedServer = servers.firstOrNull()
        val session = AuthSession(
            user = login.user,
            accessToken = login.accessToken,
            refreshToken = login.refreshToken,
            selectedServerId = selectedServer?.id,
            selectedServerSlug = selectedServer?.slug,
            servers = servers
        )
        store.saveSession(session)
        return session.summary()
    }

    private suspend fun <T> executeAuthCall(
        onUnauthorized: suspend () -> AuthResult<T> = {
            AuthResult.Failure(AuthErrorCode.InvalidCredentials, "Email or password is incorrect.")
        },
        block: suspend () -> T
    ): AuthResult<T> =
        try {
            AuthResult.Success(block())
        } catch (error: AuthHttpException) {
            when (error.code) {
                400 -> AuthResult.Failure(AuthErrorCode.Validation, error.apiMessage ?: "Check the fields and try again.")
                401 -> onUnauthorized()
                in 500..599 -> AuthResult.Failure(AuthErrorCode.Server, error.apiMessage ?: "Slock is unavailable. Try again soon.")
                else -> AuthResult.Failure(AuthErrorCode.Unknown, error.apiMessage ?: "Sign in failed. Try again.")
            }
        } catch (_: IOException) {
            AuthResult.Failure(AuthErrorCode.Network, "Network unavailable. Check your connection.")
        } catch (_: Exception) {
            AuthResult.Failure(AuthErrorCode.Unknown, "Sign in failed. Try again.")
        }

    private inline fun <reified T> ApiResponse.parseBody(): T {
        if (!isSuccessful) {
            throw AuthHttpException(code(), errorBody()?.apiErrorMessage())
        }
        val rawBody = body()?.string().orEmpty()
        if (rawBody.isBlank()) throw AuthHttpException(code(), null)
        return gson.fromJson(rawBody, T::class.java)
    }

    private fun ApiResponse.parseAuthUser(): AuthUser {
        val root = parseJsonRoot()
        val userJson = if (root.isJsonObject) {
            root.asJsonObject.objectValue("user", "account", "profile") ?: root
        } else {
            root
        }
        return gson.fromJson(userJson, AuthUser::class.java)
    }

    private fun ApiResponse.parseAuthServers(): List<AuthServer> {
        val root = parseJsonRoot()
        val serversJson = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val objectRoot = root.asJsonObject
                objectRoot.arrayValue("servers", "items", "data")
                    ?: objectRoot.objectValue("data")?.arrayValue("servers", "items")
                    ?: return emptyList()
            }
            else -> return emptyList()
        }
        return serversJson.map { gson.fromJson(it, AuthServer::class.java) }
    }

    private fun ApiResponse.parseJsonRoot(): JsonElement {
        if (!isSuccessful) {
            throw AuthHttpException(code(), errorBody()?.apiErrorMessage())
        }
        val rawBody = body()?.string().orEmpty()
        if (rawBody.isBlank()) throw AuthHttpException(code(), null)
        return gson.fromJson(rawBody, JsonElement::class.java)
    }

    private fun ApiResponse.ensureSuccessful() {
        if (!isSuccessful) {
            throw AuthHttpException(code(), errorBody()?.apiErrorMessage())
        }
    }

    private fun ResponseBody.apiErrorMessage(): String? =
        runCatching {
            gson.fromJson(string(), ApiErrorResponse::class.java).error
        }.getOrNull()

    private fun JsonObject.objectValue(vararg names: String): JsonObject? {
        for (name in names) {
            val value = get(name)
            if (value != null && value.isJsonObject) return value.asJsonObject
        }
        return null
    }

    private fun JsonObject.arrayValue(vararg names: String): com.google.gson.JsonArray? {
        for (name in names) {
            val value = get(name)
            if (value != null && value.isJsonArray) return value.asJsonArray
        }
        return null
    }

    private fun jsonBody(value: Any) =
        gson.toJson(value).toRequestBody(JSON)

    private fun AuthSession.bearer(): String = "Bearer $accessToken"

    private fun AuthSession.summary(): AuthSessionSummary =
        AuthSessionSummary(
            user = user,
            selectedServerId = selectedServerId,
            selectedServerSlug = selectedServerSlug,
            servers = servers
        )

    private fun ai.slock.compose.data.StoredAuthSession.toAndroidSummary(): AuthSessionSummary =
        toAndroidAuthSession().summary()

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

    private class AuthHttpException(
        val code: Int,
        val apiMessage: String?
    ) : Exception()

    companion object {
        const val BASE_URL = "https://api.slock.ai/api/"
        private const val TAG = "SlockAuth"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun createAuthApi(baseUrl: String = BASE_URL): AuthApi =
            KtorAuthApi(baseUrl)
    }
}
