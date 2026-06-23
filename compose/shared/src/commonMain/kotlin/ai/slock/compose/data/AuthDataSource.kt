package ai.slock.compose.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val authJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

class SharedAuthDataSource(
    private val api: SlockAuthApi,
    private val sessionStore: SlockAuthSessionStore,
    private val diagnosticsLogger: (String) -> Unit = {}
) {
    constructor(
        transport: SlockAuthTransport,
        sessionStore: SlockAuthSessionStore,
        diagnosticsLogger: (String) -> Unit = {}
    ) : this(api = SlockAuthApiClient(transport), sessionStore = sessionStore, diagnosticsLogger = diagnosticsLogger)

    fun logDiagnostics(message: String) {
        diagnosticsLogger(message)
    }

    suspend fun login(email: String, password: String): SharedAuthResult<StoredAuthSession> {
        if (email.isBlank() || password.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Email and password are required."
            )
        }

        diagnosticsLogger("auth_login_start provider=password")
        val login =
            when (
                val response =
                    runCatchingPreservingCancellation { api.login(email = email.trim(), password = password) }
                        .getOrElse { throwable ->
                            diagnosticsLogger(
                                "auth_login_result provider=password result=failure stage=login_request errorType=${throwable.safeDiagnosticsType()} mapped=network"
                            )
                            return SharedAuthResult.Failure(
                                SharedAuthErrorCode.Network,
                                "Could not reach Raft. Check your connection and try again."
                            )
                        }
            ) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> {
                    val failure = response.toSharedAuthFailure()
                    diagnosticsLogger("auth_login_result provider=password result=failure code=${failure.code}")
                    return failure
                }
            }
        diagnosticsLogger("auth_login_result provider=password result=success parsed=true")
        val servers =
            when (
                val response =
                    runCatchingPreservingCancellation { api.servers("Bearer ${login.accessToken}") }
                        .getOrElse { throwable ->
                            diagnosticsLogger(
                                "auth_login_servers_result result=failure stage=servers_request errorType=${throwable.safeDiagnosticsType()} mapped=network fallback=empty"
                            )
                            AuthApiResult.Failure(code = 0)
                        }
            ) {
                is AuthApiResult.Success -> {
                    diagnosticsLogger("auth_login_servers_result result=success stage=servers_parse serversCount=${response.data.size}")
                    response.data
                }
                is AuthApiResult.Failure -> {
                    val failure = response.toSharedAuthFailure()
                    diagnosticsLogger("auth_login_servers_result result=failure stage=servers_parse code=${failure.code} contract=${response.contractCode ?: "none"} fallback=empty")
                    if (response.contractCode != null) {
                        return loginStageFailure("servers_parse", failure.message)
                    }
                    emptyList()
                }
            }
        diagnosticsLogger("auth_selected_server_persist_start serverIdPresent=false")
        val session =
            StoredAuthSession(
                user = login.user,
                accessToken = login.accessToken,
                refreshToken = login.refreshToken,
                servers = servers
            )
        diagnosticsLogger("auth_session_persist_start userPresent=${login.user.id.isNotBlank()} serversCount=${servers.size} selectedServer=false")
        runCatchingPreservingCancellation { sessionStore.saveSession(session) }
            .getOrElse { throwable ->
                diagnosticsLogger(
                    "auth_session_persist_result result=failure stage=session_persist ${throwable.persistenceDiagnostics()} mapped=post_login_setup"
                )
                return loginStageFailure("session_persist", "Could not save session locally.")
            }
        diagnosticsLogger("auth_session_persist_result result=success stage=session_persist")
        diagnosticsLogger("auth_selected_server_persist_result result=success serverIdPresent=false")
        diagnosticsLogger(
            "auth_login_result provider=password result=success serversCount=${servers.size} selectedServer=false"
        )
        return SharedAuthResult.Success(session)
    }

    suspend fun completeSocialLogin(provider: String, code: String): SharedAuthResult<StoredAuthSession> {
        val normalizedProvider = provider.trim().lowercase()
        if (normalizedProvider != "google") {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "This sign-in provider is not available on this device."
            )
        }
        if (code.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Google sign-in did not return an authorization code."
            )
        }

        val body = authJson.encodeToString(AuthSocialCompleteRequestBody(code = code.trim()))
        return completeSocialLoginWithBody(normalizedProvider, body)
    }

    suspend fun completeGoogleIdTokenLogin(
        idToken: String,
        nonce: String? = null
    ): SharedAuthResult<StoredAuthSession> {
        if (idToken.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Google sign-in did not return an ID token."
            )
        }

        val body =
            authJson.encodeToString(
                AuthSocialCompleteRequestBody(
                    idToken = idToken.trim(),
                    nonce = nonce?.takeIf { it.isNotBlank() }
                )
            )
        return completeSocialLoginWithBody("google", body)
    }

    private suspend fun completeSocialLoginWithBody(
        provider: String,
        body: String
    ): SharedAuthResult<StoredAuthSession> {
        val tokens =
            when (val response = api.completeSocialLogin(provider, body)) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> return response.toSharedAuthFailure()
            }
        val user =
            when (val response = api.currentUser("Bearer ${tokens.accessToken}")) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> return response.toSharedAuthFailure()
            }
        val servers =
            when (val response = api.servers("Bearer ${tokens.accessToken}")) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> emptyList()
            }
        val session =
            StoredAuthSession(
                user = user,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                servers = servers
            )
        sessionStore.saveSession(session)
        return SharedAuthResult.Success(session)
    }

    suspend fun register(
        name: String,
        email: String,
        password: String
    ): SharedAuthResult<StoredAuthSession> {
        if (name.isBlank() || email.isBlank() || password.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Name, email, and password are required."
            )
        }
        if (password.length < 8) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Password must be at least 8 characters."
            )
        }

        val register =
            when (val response = api.register(name = name.trim(), email = email.trim(), password = password)) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> return response.toSharedAuthFailure()
            }
        val servers =
            when (val response = api.servers("Bearer ${register.accessToken}")) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> emptyList()
            }
        val session =
            StoredAuthSession(
                user = register.user,
                accessToken = register.accessToken,
                refreshToken = register.refreshToken,
                servers = servers
            )
        sessionStore.saveSession(session)
        return SharedAuthResult.Success(session)
    }

    suspend fun forgotPassword(email: String): SharedAuthResult<Unit> {
        if (email.isBlank() || !email.contains("@")) {
            return SharedAuthResult.Failure(SharedAuthErrorCode.Validation, "Enter a valid email address.")
        }
        return when (val response = api.forgotPassword(email.trim())) {
            is AuthApiResult.Success -> SharedAuthResult.Success(Unit)
            is AuthApiResult.Failure -> response.toSharedAuthFailure()
        }
    }

    suspend fun currentSession(): SharedAuthResult<StoredAuthSession> =
        sessionStore.readSession()?.let { SharedAuthResult.Success(it) }
            ?: SharedAuthResult.Unauthenticated

    suspend fun restoreSession(): SharedAuthResult<StoredAuthSession> {
        diagnosticsLogger("auth_restore_start")
        val session =
            runCatchingPreservingCancellation { sessionStore.readSession() }
                .getOrElse { throwable ->
                    diagnosticsLogger(
                        "auth_restore_result result=failure stage=session_restore ${throwable.persistenceDiagnostics()} mapped=local_setup"
                    )
                    return SharedAuthResult.Failure(
                        SharedAuthErrorCode.Unknown,
                        "Could not restore local session. Local storage setup failed."
                    )
                }
                ?: run {
                    diagnosticsLogger("auth_restore_result result=unauthenticated reason=no_cached_session")
                    return SharedAuthResult.Unauthenticated
                }
        val current =
            runCatchingPreservingCancellation { currentUserWithSession(session, clearOnUnauthorized = false) }
                .getOrElse { throwable ->
                    diagnosticsLogger(
                        "auth_restore_current_user_exception errorType=${throwable.safeDiagnosticsType()} mapped=network"
                    )
                    SharedAuthResult.Failure(SharedAuthErrorCode.Network, "Network unavailable. Check your connection.")
                }
        return when (current) {
            is SharedAuthResult.Success -> {
                diagnosticsLogger("auth_restore_result result=success source=current_user selectedServer=${!current.data.selectedServerId.isNullOrBlank()}")
                current
            }
            SharedAuthResult.Unauthenticated -> {
                diagnosticsLogger("auth_restore_current_user_result result=unauthenticated action=refresh")
                val refreshed =
                    runCatchingPreservingCancellation { refresh(session.refreshToken) }
                        .getOrElse { throwable ->
                            diagnosticsLogger(
                                "auth_restore_refresh_exception errorType=${throwable.safeDiagnosticsType()} mapped=network"
                            )
                            SharedAuthResult.Failure(SharedAuthErrorCode.Network, "Network unavailable. Check your connection.")
                        }
                when (refreshed) {
                    is SharedAuthResult.Success -> {
                        diagnosticsLogger("auth_restore_refresh_result result=success")
                        val updated = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
                        when (val refreshedCurrent =
                            runCatchingPreservingCancellation { currentUserWithSession(updated, clearOnUnauthorized = true) }
                                .getOrElse {
                                    diagnosticsLogger(
                                        "auth_restore_refreshed_current_user_exception errorType=${it.safeDiagnosticsType()} mapped=network"
                                    )
                                    SharedAuthResult.Failure(
                                        SharedAuthErrorCode.Network,
                                        "Network unavailable. Check your connection."
                                    )
                                }
                        ) {
                            is SharedAuthResult.Success -> {
                                diagnosticsLogger(
                                    "auth_restore_result result=success source=refresh_current_user selectedServer=${!refreshedCurrent.data.selectedServerId.isNullOrBlank()}"
                                )
                                refreshedCurrent
                            }
                            SharedAuthResult.Unauthenticated -> {
                                diagnosticsLogger("auth_restore_result result=unauthenticated source=refresh_current_user")
                                SharedAuthResult.Unauthenticated
                            }
                            is SharedAuthResult.Failure ->
                                if (refreshedCurrent.isRecoverableRestoreFailure()) {
                                    diagnosticsLogger(
                                        "auth_restore_result result=success source=cached_after_refresh_current_user_failure recoverableCode=${refreshedCurrent.code}"
                                    )
                                    SharedAuthResult.Success(updated)
                                } else {
                                    diagnosticsLogger(
                                        "auth_restore_result result=failure source=refresh_current_user code=${refreshedCurrent.code}"
                                    )
                                    refreshedCurrent
                                }
                        }
                    }
                    is SharedAuthResult.Failure ->
                        if (refreshed.isRecoverableRestoreFailure()) {
                            diagnosticsLogger(
                                "auth_restore_result result=success source=cached_after_refresh_failure recoverableCode=${refreshed.code}"
                            )
                            SharedAuthResult.Success(session)
                        } else {
                            diagnosticsLogger("auth_restore_result result=failure source=refresh code=${refreshed.code}")
                            refreshed
                        }
                    SharedAuthResult.Unauthenticated -> {
                        diagnosticsLogger("auth_restore_result result=unauthenticated source=refresh")
                        SharedAuthResult.Unauthenticated
                    }
                }
            }
            is SharedAuthResult.Failure ->
                if (current.isRecoverableRestoreFailure()) {
                    diagnosticsLogger(
                        "auth_restore_result result=success source=cached_after_current_user_failure recoverableCode=${current.code}"
                    )
                    SharedAuthResult.Success(session)
                } else {
                    diagnosticsLogger("auth_restore_result result=failure source=current_user code=${current.code}")
                    current
                }
        }
    }

    suspend fun refresh(refreshToken: String): SharedAuthResult<StoredAuthSession> {
        if (refreshToken.isBlank()) {
            sessionStore.clearSession()
            return SharedAuthResult.Unauthenticated
        }
        val current = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        val tokens =
            when (val response = api.refresh(refreshToken)) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> {
                    if (response.code == 401) {
                        sessionStore.clearSession()
                        return SharedAuthResult.Unauthenticated
                    }
                    return response.toSharedAuthFailure()
                }
            }
        val session = current.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
        sessionStore.saveSession(session)
        return SharedAuthResult.Success(session)
    }

    suspend fun refreshRealtimeAuth(reason: String): SharedAuthResult<StoredAuthSession> {
        val current = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        if (current.refreshToken.isBlank()) {
            diagnosticsLogger("auth_realtime_refresh_result result=unauthenticated reason=$reason missing_refresh_token=true")
            return SharedAuthResult.Unauthenticated
        }
        diagnosticsLogger("auth_realtime_refresh_start reason=$reason")
        val tokens =
            when (val response = api.refresh(current.refreshToken)) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> {
                    val failure = response.toSharedAuthFailure()
                    diagnosticsLogger("auth_realtime_refresh_result result=failure reason=$reason code=${failure.code}")
                    // Realtime reconnect attempts are background recovery. Preserve the cached
                    // session on unauthorized so the visible auth gate decides whether to clear it.
                    return failure
                }
            }
        val session = current.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
        sessionStore.saveSession(session)
        diagnosticsLogger("auth_realtime_refresh_result result=success reason=$reason")
        return SharedAuthResult.Success(session)
    }

    suspend fun loadServers(): SharedAuthResult<StoredAuthSession> {
        val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        val servers =
            when (val response = api.servers(session.bearer())) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> {
                    if (response.code == 401) {
                        sessionStore.clearSession()
                        return SharedAuthResult.Unauthenticated
                    }
                    return response.toSharedAuthFailure()
                }
            }
        val updated = session.withValidatedServerSelection(servers)
        sessionStore.saveSession(updated)
        return SharedAuthResult.Success(updated)
    }

    suspend fun selectServer(serverId: String): SharedAuthResult<StoredAuthSession> {
        val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        val selected = session.servers.firstOrNull { it.id == serverId }
            ?: return SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "Server not found.")
        val updated = session.copy(selectedServerId = selected.id, selectedServerSlug = selected.slug)
        sessionStore.saveSession(updated)
        return SharedAuthResult.Success(updated)
    }

    suspend fun createServer(
        name: String,
        slug: String? = null,
        region: String? = null
    ): SharedAuthResult<StoredAuthSession> {
        val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) {
            return SharedAuthResult.Failure(SharedAuthErrorCode.Validation, "Server name is required.")
        }
        val body =
            authJson.encodeToString(
                AuthCreateServerRequestBody(
                    name = normalizedName,
                    slug = slug?.trim()?.takeIf { it.isNotBlank() },
                    region = region?.trim()?.takeIf { it.isNotBlank() }
                )
            )
        val created =
            when (val response = api.createServer(session.bearer(), body)) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> {
                    if (response.code == 401) {
                        sessionStore.clearSession()
                        return SharedAuthResult.Unauthenticated
                    }
                    return response.toSharedAuthFailure()
                }
            }
        val latestServers =
            when (val response = api.servers(session.bearer())) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> emptyList()
            }
                .upsert(created)
                .ifEmpty { listOf(created) }
        val selected = latestServers.firstOrNull { it.id == created.id } ?: created
        val updated =
            session.copy(
                servers = latestServers,
                selectedServerId = selected.id,
                selectedServerSlug = selected.slug
            )
        sessionStore.saveSession(updated)
        return SharedAuthResult.Success(updated)
    }

    suspend fun logout(): SharedAuthResult<Unit> {
        val session = sessionStore.readSession()
        if (session != null) {
            runCatchingPreservingCancellation {
                api.logout(session.refreshToken)
            }
        }
        sessionStore.clearSession()
        return SharedAuthResult.Success(Unit)
    }

    private suspend fun currentUserWithSession(
        session: StoredAuthSession,
        clearOnUnauthorized: Boolean
    ): SharedAuthResult<StoredAuthSession> {
        val user =
            when (val response = api.currentUser(session.bearer())) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> {
                    if (response.code == 401) {
                        if (clearOnUnauthorized) sessionStore.clearSession()
                        return SharedAuthResult.Unauthenticated
                    }
                    return response.toSharedAuthFailure()
                }
            }
        val servers =
            when (val response = api.servers(session.bearer())) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> session.servers
            }
        val updated = session.copy(user = user).withValidatedServerSelection(servers)
        sessionStore.saveSession(updated)
        return SharedAuthResult.Success(updated)
    }
}

private fun StoredAuthSession.withValidatedServerSelection(servers: List<StoredAuthServer>): StoredAuthSession {
    val selected = selectedServerId?.let { selectedId -> servers.firstOrNull { it.id == selectedId } }
    return copy(
        servers = servers,
        selectedServerId = selected?.id,
        selectedServerSlug = selected?.slug
    )
}

private fun SharedAuthResult.Failure.isRecoverableRestoreFailure(): Boolean =
    code == SharedAuthErrorCode.Network || code == SharedAuthErrorCode.Server

private fun loginStageFailure(stage: String, detail: String? = null): SharedAuthResult.Failure =
    SharedAuthResult.Failure(
        SharedAuthErrorCode.Unknown,
        buildString {
            append("Sign in succeeded, but Raft could not finish setup at ")
            append(stage)
            append(". Try again.")
            if (!detail.isNullOrBlank()) {
                append(" ")
                append(detail)
            }
        }
    )

private fun Throwable.persistenceDiagnostics(): String =
    toPersistenceFailureDiagnostics().let { diagnostics ->
        "errorType=${diagnostics.errorType} sqliteCategory=${diagnostics.category} sqliteCode=${diagnostics.code ?: "none"}"
    }

/**
 * Typed auth/server boundary.
 *
 * Keep backend field-shape notes here rather than in separate API docs:
 * login/refresh require canonical `accessToken` and `refreshToken` fields,
 * current user is a user object at the response root, and server list is a
 * root JSON array. Legacy token aliases are intentionally rejected by tests.
 */
interface SlockAuthApi {
    suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse>
    suspend fun completeSocialLogin(provider: String, bodyText: String): AuthApiResult<AuthSocialCompleteResponse>
    suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse>
    suspend fun forgotPassword(email: String): AuthApiResult<Unit>
    suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser>
    suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse>
    suspend fun logout(refreshToken: String): AuthApiResult<Unit>
    suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>>
    suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer>
}

class SlockAuthApiClient(
    private val transport: SlockAuthTransport
) : SlockAuthApi {
    override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
        transport.login(authJson.encodeToString(AuthLoginRequestBody(email = email, password = password)))
            .mapAuthBody("auth_login_contract_mismatch", AuthLoginResponseDto.serializer()) { it.toDomain() }

    override suspend fun completeSocialLogin(
        provider: String,
        bodyText: String
    ): AuthApiResult<AuthSocialCompleteResponse> =
        transport.completeSocialLogin(provider, bodyText)
            .mapAuthBody("auth_social_complete_contract_mismatch", AuthSocialCompleteResponseDto.serializer()) {
                it.toDomain()
            }

    override suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse> =
        transport.register(authJson.encodeToString(AuthRegisterRequestBody(email = email, password = password, name = name)))
            .mapAuthBody("auth_register_contract_mismatch", AuthLoginResponseDto.serializer()) { it.toDomain() }

    override suspend fun forgotPassword(email: String): AuthApiResult<Unit> {
        val response = transport.forgotPassword(authJson.encodeToString(AuthForgotPasswordRequestBody(email = email)))
        if (!response.isSuccessful) return response.toAuthApiFailure()
        return response.mapAuthBody("auth_forgot_password_contract_mismatch", AuthOkResponseDto.serializer()) { Unit }
    }

    override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
        transport.currentUser(authorization)
            .mapAuthBody("auth_me_contract_mismatch", StoredAuthUser.serializer()) { it }

    override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> =
        transport.refresh(authJson.encodeToString(AuthRefreshRequestBody(refreshToken = refreshToken)))
            .mapAuthBody("auth_refresh_contract_mismatch", AuthRefreshResponseDto.serializer()) { it.toDomain() }

    override suspend fun logout(refreshToken: String): AuthApiResult<Unit> {
        val response = transport.logout(authJson.encodeToString(AuthLogoutRequestBody(refreshToken = refreshToken)))
        if (!response.isSuccessful) return response.toAuthApiFailure()
        return AuthApiResult.Success(Unit)
    }

    override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
        transport.servers(authorization)
            .mapAuthBody("servers_list_contract_mismatch", ListSerializer(StoredAuthServer.serializer())) { it }

    override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
        transport.createServer(authorization, bodyText)
            .mapAuthBody("server_create_contract_mismatch", StoredAuthServer.serializer()) { it }
}

interface SlockAuthTransport {
    suspend fun login(bodyText: String): AuthNetworkResponse
    suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse
    suspend fun register(bodyText: String): AuthNetworkResponse
    suspend fun forgotPassword(bodyText: String): AuthNetworkResponse
    suspend fun currentUser(authorization: String): AuthNetworkResponse
    suspend fun refresh(bodyText: String): AuthNetworkResponse
    suspend fun logout(bodyText: String): AuthNetworkResponse
    suspend fun servers(authorization: String): AuthNetworkResponse
    suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse
}

sealed interface AuthApiResult<out T> {
    data class Success<T>(val data: T) : AuthApiResult<T>
    data class Failure(
        val code: Int,
        val errorBodyText: String? = null,
        val contractCode: String? = null
    ) : AuthApiResult<Nothing>
}

data class AuthLoginResponse(
    val user: StoredAuthUser,
    val accessToken: String,
    val refreshToken: String
)

data class AuthSocialCompleteResponse(
    val accessToken: String,
    val refreshToken: String
)

data class AuthRefreshResponse(
    val accessToken: String,
    val refreshToken: String
)

data class AuthNetworkResponse(
    val code: Int,
    val bodyText: String?,
    val errorBodyText: String? = null
) {
    val isSuccessful: Boolean
        get() = code in 200..299
}

sealed interface SharedAuthResult<out T> {
    data class Success<T>(val data: T) : SharedAuthResult<T>
    data object Unauthenticated : SharedAuthResult<Nothing>
    data class Failure(
        val code: SharedAuthErrorCode,
        val message: String
    ) : SharedAuthResult<Nothing>
}

enum class SharedAuthErrorCode {
    Validation,
    InvalidCredentials,
    Network,
    Server,
    Unknown
}

@kotlinx.serialization.Serializable
data class AuthLoginRequestBody(
    val email: String,
    val password: String
)

@kotlinx.serialization.Serializable
data class AuthSocialCompleteRequestBody(
    val code: String? = null,
    val idToken: String? = null,
    val nonce: String? = null
)

@kotlinx.serialization.Serializable
data class AuthRegisterRequestBody(
    val email: String,
    val password: String,
    val name: String,
    val acceptTerms: Boolean = true,
    val termsVersion: String = CURRENT_TERMS_VERSION,
    val privacyVersion: String = CURRENT_PRIVACY_VERSION,
    val legalAcceptanceSource: String = "signup"
)

@kotlinx.serialization.Serializable
data class AuthForgotPasswordRequestBody(
    val email: String
)

@kotlinx.serialization.Serializable
data class AuthCreateServerRequestBody(
    val name: String,
    val slug: String? = null,
    val region: String? = null
)

@kotlinx.serialization.Serializable
data class AuthRefreshRequestBody(
    val refreshToken: String
)

private const val CURRENT_TERMS_VERSION = "2026-05-12"
private const val CURRENT_PRIVACY_VERSION = "2026-05-12"

@kotlinx.serialization.Serializable
data class AuthLogoutRequestBody(
    val refreshToken: String
)

private fun AuthApiResult.Failure.toSharedAuthFailure(): SharedAuthResult.Failure {
    val errorDto = errorBodyText?.let { body ->
        runCatching { authJson.decodeFromString(AuthErrorBodyDto.serializer(), body) }.getOrNull()
    }
    val message = errorDto?.error?.takeIf { it.isNotBlank() }
        ?: errorDto?.message?.takeIf { it.isNotBlank() }
    return when {
        contractCode != null ->
            SharedAuthResult.Failure(
                SharedAuthErrorCode.Unknown,
                contractCode
            )
        code == 0 ->
            SharedAuthResult.Failure(
                SharedAuthErrorCode.Network,
                message ?: "Network unavailable. Check your connection."
            )
        code == 400 ->
            SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                message ?: "Check the fields and try again."
            )
        code == 401 ->
            SharedAuthResult.Failure(
                SharedAuthErrorCode.InvalidCredentials,
                message ?: "Email or password is incorrect."
            )
        code in 500..599 ->
            SharedAuthResult.Failure(
                SharedAuthErrorCode.Server,
                message ?: "Raft is unavailable. Try again soon."
            )
        else ->
            SharedAuthResult.Failure(
                SharedAuthErrorCode.Unknown,
                message ?: "Sign in failed. Try again."
            )
    }
}

private fun AuthNetworkResponse.toAuthApiFailure(): AuthApiResult.Failure =
    AuthApiResult.Failure(code = code, errorBodyText = errorBodyText)

/**
 * Single-shot, typed decode of the response body.
 *
 * Mirrors the shared realtime mapper: success path decodes the whole body via
 * an explicit [KSerializer]; any deviation from the canonical server shape
 * (missing required field, wrong type, malformed JSON, non-2xx status) becomes
 * a [AuthApiResult.Failure] with the supplied contract code.
 *
 * Field names in the wire DTOs (see below) must match the server emit shape
 * exactly. No snake_case fallbacks, no alias chains.
 */
private inline fun <Wire, T> AuthNetworkResponse.mapAuthBody(
    contractCode: String,
    serializer: KSerializer<Wire>,
    crossinline transform: (Wire) -> T,
): AuthApiResult<T> {
    if (!isSuccessful) return toAuthApiFailure()
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return AuthApiResult.Failure(code = code, contractCode = contractCode)
    val wire = try {
        authJson.decodeFromString(serializer, body)
    } catch (_: SerializationException) {
        return AuthApiResult.Failure(code = code, contractCode = contractCode)
    }
    return AuthApiResult.Success(transform(wire))
}

// ── Wire DTOs ──
// Source of truth: packages/server/src/routes/auth.ts and
// packages/server/src/services/userService.ts. Field names mirror the server's
// JSON emit shape exactly.

@Serializable
private data class AuthLoginResponseDto(
    val user: StoredAuthUser,
    val accessToken: String,
    val refreshToken: String,
)

private fun AuthLoginResponseDto.toDomain(): AuthLoginResponse =
    AuthLoginResponse(user = user, accessToken = accessToken, refreshToken = refreshToken)

@Serializable
private data class AuthSocialCompleteResponseDto(
    val accessToken: String,
    val refreshToken: String,
)

private fun AuthSocialCompleteResponseDto.toDomain(): AuthSocialCompleteResponse =
    AuthSocialCompleteResponse(accessToken = accessToken, refreshToken = refreshToken)

@Serializable
private data class AuthRefreshResponseDto(
    val accessToken: String,
    val refreshToken: String,
)

private fun AuthRefreshResponseDto.toDomain(): AuthRefreshResponse =
    AuthRefreshResponse(accessToken = accessToken, refreshToken = refreshToken)

@Serializable
private data class AuthOkResponseDto(
    val ok: Boolean = true,
    val message: String? = null,
)

@Serializable
private data class AuthErrorBodyDto(
    val error: String? = null,
    val message: String? = null,
)

private fun StoredAuthSession.bearer(): String = "Bearer $accessToken"

private fun List<StoredAuthServer>.upsert(server: StoredAuthServer): List<StoredAuthServer> =
    filterNot { it.id == server.id } + server
