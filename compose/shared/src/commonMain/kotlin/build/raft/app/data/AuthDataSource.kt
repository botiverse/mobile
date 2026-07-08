package build.raft.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

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
    private val serverSelectionMemory = sessionStore as? SlockAuthServerSelectionMemory

    constructor(
        transport: SlockAuthTransport,
        sessionStore: SlockAuthSessionStore,
        diagnosticsLogger: (String) -> Unit = {}
    ) : this(
        api = SlockAuthApiClient(transport, diagnosticsLogger),
        sessionStore = sessionStore,
        diagnosticsLogger = diagnosticsLogger
    )

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

        diagnosticsLogger("auth_login_clear_cached_session_start provider=password")
        runCatchingPreservingCancellation { sessionStore.clearSession() }
            .getOrElse { throwable ->
                diagnosticsLogger(
                    "auth_login_clear_cached_session_result result=failure stage=session_clear ${throwable.persistenceDiagnostics()} mapped=pre_login_setup"
                )
                return loginStageFailure("session_clear", "Could not clear previous session locally.")
            }
        diagnosticsLogger("auth_login_clear_cached_session_result result=success provider=password")
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
        val session =
            StoredAuthSession(
                user = login.user,
                accessToken = login.accessToken,
                refreshToken = login.refreshToken,
                servers = servers
            ).withValidatedServerSelection(servers, serverSelectionMemory)
        val selectedServerPresent = !session.selectedServerId.isNullOrBlank()
        diagnosticsLogger("auth_selected_server_persist_start serverIdPresent=$selectedServerPresent")
        diagnosticsLogger("auth_session_persist_start userPresent=${login.user.id.isNotBlank()} serversCount=${servers.size} selectedServer=$selectedServerPresent")
        runCatchingPreservingCancellation { sessionStore.saveSession(session) }
            .getOrElse { throwable ->
                diagnosticsLogger(
                    "auth_session_persist_result result=failure stage=session_persist ${throwable.persistenceDiagnostics()} mapped=post_login_setup"
                )
                return loginStageFailure("session_persist", "Could not save session locally.")
            }
        diagnosticsLogger("auth_session_persist_result result=success stage=session_persist")
        diagnosticsLogger("auth_selected_server_persist_result result=success serverIdPresent=$selectedServerPresent")
        diagnosticsLogger(
            "auth_login_result provider=password result=success serversCount=${servers.size} selectedServer=$selectedServerPresent"
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

    suspend fun startMobileOAuthLogin(
        provider: String,
        appEnv: String,
        returnUri: String,
        codeChallenge: String
    ): SharedAuthResult<AuthMobileOAuthStartResponse> {
        val normalizedProvider = provider.trim().lowercase()
        if (normalizedProvider !in MobileOAuthProviders) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "This sign-in provider is not available on this device."
            )
        }
        if (appEnv.isBlank() || returnUri.isBlank() || codeChallenge.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Mobile sign-in could not be prepared."
            )
        }
        return when (
            val response =
                api.startMobileOAuth(
                    provider = normalizedProvider,
                    mode = "login",
                    platform = "android",
                    appEnv = appEnv.trim(),
                    returnUri = returnUri.trim(),
                    codeChallenge = codeChallenge.trim()
                )
        ) {
            is AuthApiResult.Success -> SharedAuthResult.Success(response.data)
            is AuthApiResult.Failure -> response.toSharedAuthFailure()
        }
    }

    suspend fun completeMobileOAuthLogin(
        code: String,
        codeVerifier: String,
        platform: String = "android",
        acceptLegalTerms: Boolean = false
    ): SharedAuthResult<StoredAuthSession> {
        if (code.isBlank() || codeVerifier.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Mobile sign-in did not return the required credentials."
            )
        }
        diagnosticsLogger("auth_login_start provider=mobile_oauth")
        val login =
            when (
                val response =
                    api.completeMobileOAuth(
                        code = code.trim(),
                        codeVerifier = codeVerifier.trim(),
                        platform = platform.trim().ifBlank { "android" },
                        acceptLegalTerms = acceptLegalTerms
                    )
            ) {
                is AuthApiResult.Success -> {
                    diagnosticsLogger("auth_login_result provider=mobile_oauth result=success parsed=true")
                    response.data
                }
                is AuthApiResult.Failure -> {
                    val failure = response.toSharedAuthFailure()
                    diagnosticsLogger(
                        "auth_login_result provider=mobile_oauth result=failure code=${failure.code} " +
                            "http=${response.code} ${response.errorBodyText.authErrorDiagnostics()} " +
                            "message=${failure.message.authDiagnosticsToken()}"
                    )
                    return failure
                }
            }
        val servers =
            when (val response = api.servers("Bearer ${login.accessToken}")) {
                is AuthApiResult.Success -> {
                    diagnosticsLogger("auth_login_servers_result provider=mobile_oauth result=success serversCount=${response.data.size}")
                    response.data
                }
                is AuthApiResult.Failure -> {
                    val failure = response.toSharedAuthFailure()
                    diagnosticsLogger("auth_login_servers_result provider=mobile_oauth result=failure code=${failure.code} fallback=empty")
                    emptyList()
                }
            }
        val session =
            StoredAuthSession(
                user = login.user,
                accessToken = login.accessToken,
                refreshToken = login.refreshToken,
                servers = servers
            ).withValidatedServerSelection(servers, serverSelectionMemory)
        val selectedServerPresent = !session.selectedServerId.isNullOrBlank()
        diagnosticsLogger("auth_session_persist_start provider=mobile_oauth userPresent=${login.user.id.isNotBlank()} serversCount=${servers.size} selectedServer=$selectedServerPresent")
        runCatchingPreservingCancellation { sessionStore.saveSession(session) }
            .getOrElse { throwable ->
                diagnosticsLogger(
                    "auth_session_persist_result provider=mobile_oauth result=failure stage=session_persist ${throwable.persistenceDiagnostics()} mapped=post_login_setup"
                )
                return loginStageFailure("session_persist", "Could not save session locally.")
            }
        diagnosticsLogger("auth_session_persist_result provider=mobile_oauth result=success stage=session_persist")
        diagnosticsLogger("auth_login_result provider=mobile_oauth result=success serversCount=${servers.size} selectedServer=$selectedServerPresent")
        return SharedAuthResult.Success(session)
    }

    suspend fun startMobileOAuthLink(
        provider: String,
        returnUri: String,
        codeChallenge: String
    ): SharedAuthResult<AuthMobileOAuthStartResponse> {
        val normalizedProvider = provider.trim().lowercase()
        if (normalizedProvider !in MobileOAuthProviders) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "This account provider is not available on this device."
            )
        }
        if (returnUri.isBlank() || codeChallenge.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Account linking could not be prepared."
            )
        }
        val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        return when (
            val response =
                api.startMobileOAuthLink(
                    provider = normalizedProvider,
                    returnUri = returnUri.trim(),
                    codeChallenge = codeChallenge.trim(),
                    authorization = "Bearer ${session.accessToken}"
                )
        ) {
            is AuthApiResult.Success -> SharedAuthResult.Success(response.data)
            is AuthApiResult.Failure -> response.toSharedAuthFailure()
        }
    }

    suspend fun completeMobileOAuthLink(
        provider: String,
        code: String,
        codeVerifier: String
    ): SharedAuthResult<AuthMobileOAuthLinkCompleteResponse> {
        val normalizedProvider = provider.trim().lowercase()
        if (normalizedProvider !in MobileOAuthProviders) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "This account provider is not available on this device."
            )
        }
        if (code.isBlank() || codeVerifier.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Account linking did not return the required credentials."
            )
        }
        val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        return when (
            val response =
                api.completeMobileOAuthLink(
                    provider = normalizedProvider,
                    code = code.trim(),
                    codeVerifier = codeVerifier.trim(),
                    authorization = "Bearer ${session.accessToken}"
                )
        ) {
            is AuthApiResult.Success -> SharedAuthResult.Success(response.data)
            is AuthApiResult.Failure -> response.toSharedAuthFailure()
        }
    }

    private suspend fun completeSocialLoginWithBody(
        provider: String,
        body: String
    ): SharedAuthResult<StoredAuthSession> {
        diagnosticsLogger("auth_login_start provider=$provider")
        val tokens =
            when (val response = api.completeSocialLogin(provider, body)) {
                is AuthApiResult.Success -> {
                    diagnosticsLogger("auth_login_result provider=$provider result=success tokens=true")
                    response.data
                }
                is AuthApiResult.Failure -> {
                    val failure = response.toSharedAuthFailure()
                    diagnosticsLogger("auth_login_result provider=$provider result=failure code=${failure.code}")
                    return failure
                }
            }
        val user =
            when (val response = api.currentUser("Bearer ${tokens.accessToken}")) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> {
                    val failure = response.toSharedAuthFailure()
                    diagnosticsLogger("auth_login_current_user_result provider=$provider result=failure code=${failure.code}")
                    return failure
                }
            }
        val servers =
            when (val response = api.servers("Bearer ${tokens.accessToken}")) {
                is AuthApiResult.Success -> {
                    diagnosticsLogger("auth_login_servers_result provider=$provider result=success serversCount=${response.data.size}")
                    response.data
                }
                is AuthApiResult.Failure -> {
                    val failure = response.toSharedAuthFailure()
                    diagnosticsLogger("auth_login_servers_result provider=$provider result=failure code=${failure.code} fallback=empty")
                    emptyList()
                }
            }
        val session =
            StoredAuthSession(
                user = user,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                servers = servers
            ).withValidatedServerSelection(servers, serverSelectionMemory)
        val selectedServerPresent = !session.selectedServerId.isNullOrBlank()
        diagnosticsLogger("auth_session_persist_start provider=$provider userPresent=${user.id.isNotBlank()} serversCount=${servers.size} selectedServer=$selectedServerPresent")
        runCatchingPreservingCancellation { sessionStore.saveSession(session) }
            .getOrElse { throwable ->
                diagnosticsLogger(
                    "auth_session_persist_result provider=$provider result=failure stage=session_persist ${throwable.persistenceDiagnostics()} mapped=post_login_setup"
                )
                return loginStageFailure("session_persist", "Could not save session locally.")
            }
        diagnosticsLogger("auth_session_persist_result provider=$provider result=success stage=session_persist")
        diagnosticsLogger("auth_login_result provider=$provider result=success serversCount=${servers.size} selectedServer=$selectedServerPresent")
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
            ).withValidatedServerSelection(servers, serverSelectionMemory)
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

    suspend fun resendVerification(): SharedAuthResult<Unit> {
        val bearer =
            sessionStore.readSession()?.bearer()
                ?: return SharedAuthResult.Unauthenticated
        return when (val response = api.resendVerification(bearer)) {
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
                                        "auth_restore_result result=success source=cached_after_refresh_current_user_failure " +
                                            "recoverableCode=${refreshedCurrent.code} " +
                                            "recoverableMessage=${refreshedCurrent.message.authDiagnosticsToken()}"
                                    )
                                    SharedAuthResult.Success(updated)
                                } else {
                                    diagnosticsLogger(
                                        "auth_restore_result result=failure source=refresh_current_user " +
                                            "code=${refreshedCurrent.code} message=${refreshedCurrent.message.authDiagnosticsToken()}"
                                    )
                                    refreshedCurrent
                                }
                        }
                    }
                    is SharedAuthResult.Failure ->
                        if (refreshed.isRecoverableRestoreFailure()) {
                            diagnosticsLogger(
                                "auth_restore_result result=success source=cached_after_refresh_failure " +
                                    "recoverableCode=${refreshed.code} recoverableMessage=${refreshed.message.authDiagnosticsToken()}"
                            )
                            SharedAuthResult.Success(session)
                        } else {
                            diagnosticsLogger(
                                "auth_restore_result result=failure source=refresh " +
                                    "code=${refreshed.code} message=${refreshed.message.authDiagnosticsToken()}"
                            )
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
                        "auth_restore_result result=success source=cached_after_current_user_failure " +
                            "recoverableCode=${current.code} recoverableMessage=${current.message.authDiagnosticsToken()}"
                    )
                    SharedAuthResult.Success(session)
                } else {
                    diagnosticsLogger(
                        "auth_restore_result result=failure source=current_user " +
                            "code=${current.code} message=${current.message.authDiagnosticsToken()}"
                    )
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
        val session =
            sessionStore.readSession()
                ?: run {
                    diagnosticsLogger("auth_servers_load_result result=unauthenticated reason=no_cached_session")
                    return SharedAuthResult.Unauthenticated
                }
        diagnosticsLogger(
            "auth_servers_load_start selectedServer=${!session.selectedServerId.isNullOrBlank()} cachedServers=${session.servers.size}"
        )
        val servers =
            when (val response = api.servers(session.bearer())) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> {
                    if (response.code == 401) {
                        diagnosticsLogger("auth_servers_load_result result=unauthorized source=access_token action=refresh")
                        return loadServersAfterRefresh(session)
                    }
                    val failure = response.toSharedAuthFailure()
                    diagnosticsLogger("auth_servers_load_result result=failure source=access_token code=${failure.code}")
                    return failure
                }
            }
        return persistLoadedServers(session, servers, source = "access_token")
    }

    private suspend fun loadServersAfterRefresh(session: StoredAuthSession): SharedAuthResult<StoredAuthSession> {
        when (val refreshed = refresh(session.refreshToken)) {
            is SharedAuthResult.Success -> {
                val updated = refreshed.data
                val servers =
                    when (val retryResponse = api.servers(updated.bearer())) {
                        is AuthApiResult.Success -> retryResponse.data
                        is AuthApiResult.Failure -> {
                            if (retryResponse.code == 401) {
                                diagnosticsLogger("auth_servers_load_result result=unauthenticated source=refresh_retry")
                                sessionStore.clearSession()
                                return SharedAuthResult.Unauthenticated
                            }
                            val failure = retryResponse.toSharedAuthFailure()
                            diagnosticsLogger("auth_servers_load_result result=failure source=refresh_retry code=${failure.code}")
                            return failure
                        }
                    }
                return persistLoadedServers(updated, servers, source = "refresh_retry")
            }
            SharedAuthResult.Unauthenticated -> {
                diagnosticsLogger("auth_servers_load_result result=unauthenticated source=refresh")
                return SharedAuthResult.Unauthenticated
            }
            is SharedAuthResult.Failure -> {
                diagnosticsLogger("auth_servers_load_result result=failure source=refresh code=${refreshed.code}")
                return refreshed
            }
        }
    }

    private suspend fun persistLoadedServers(
        session: StoredAuthSession,
        servers: List<StoredAuthServer>,
        source: String
    ): SharedAuthResult<StoredAuthSession> {
        val current = sessionStore.readSession()
        if (current?.authSessionClearFingerprint() != session.authSessionClearFingerprint()) {
            diagnosticsLogger(
                "auth_servers_load_result result=stale source=$source action=drop"
            )
            return SharedAuthResult.Unauthenticated
        }
        val updated = session.withValidatedServerSelection(servers, serverSelectionMemory)
        sessionStore.saveSession(updated)
        diagnosticsLogger(
            "auth_servers_load_result result=success source=$source serversCount=${servers.size} " +
                "selectedServer=${!updated.selectedServerId.isNullOrBlank()} " +
                "avatarCount=${servers.count { !it.avatarUrl.isNullOrBlank() }} " +
                "order=${servers.map { it.id }.authServerOrderDiagnosticsValue()}"
        )
        return SharedAuthResult.Success(updated)
    }

    suspend fun selectServer(serverId: String): SharedAuthResult<StoredAuthSession> {
        val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        val selected = session.servers.firstOrNull { it.id == serverId }
            ?: return SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "Server not found.")
        val updated = session.copy(selectedServerId = selected.id, selectedServerSlug = selected.slug)
        updated.rememberSelectedServer(serverSelectionMemory)
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
        updated.rememberSelectedServer(serverSelectionMemory)
        sessionStore.saveSession(updated)
        return SharedAuthResult.Success(updated)
    }

    suspend fun logout(): SharedAuthResult<Unit> {
        val session = sessionStore.readSession()
        if (session != null) {
            runCatchingPreservingCancellation {
                api.logout(session.refreshToken)
            }
            session.rememberSelectedServer(serverSelectionMemory)
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
        val updated = session.copy(user = user).withValidatedServerSelection(servers, serverSelectionMemory)
        sessionStore.saveSession(updated)
        return SharedAuthResult.Success(updated)
    }
}

private val MobileOAuthProviders = setOf("google", "github")
private const val AUTH_ME_CONTRACT_MISMATCH = "auth_me_contract_mismatch"

private suspend fun StoredAuthSession.withValidatedServerSelection(
    servers: List<StoredAuthServer>,
    serverSelectionMemory: SlockAuthServerSelectionMemory?
): StoredAuthSession {
    val selected =
        selectedServerId?.let { selectedId -> servers.firstOrNull { it.id == selectedId } }
            ?: serverSelectionMemory?.readRememberedServerSelection(user)?.let { remembered ->
                servers.firstOrNull { it.id == remembered.serverId }
            }
    return copy(
        servers = servers,
        selectedServerId = selected?.id,
        selectedServerSlug = selected?.slug
    )
}

private suspend fun StoredAuthSession.rememberSelectedServer(serverSelectionMemory: SlockAuthServerSelectionMemory?) {
    val selectedId = selectedServerId?.takeIf { it.isNotBlank() } ?: return
    val selectedSlug = servers.firstOrNull { it.id == selectedId }?.slug ?: selectedServerSlug
    serverSelectionMemory?.saveRememberedServerSelection(
        user,
        StoredAuthServerSelection(serverId = selectedId, serverSlug = selectedSlug)
    )
}

private fun SharedAuthResult.Failure.isRecoverableRestoreFailure(): Boolean =
    code == SharedAuthErrorCode.Network ||
        code == SharedAuthErrorCode.Server ||
        (code == SharedAuthErrorCode.Unknown && message == AUTH_ME_CONTRACT_MISMATCH)

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

private fun String.authDiagnosticsToken(): String =
    trim()
        .ifBlank { "empty" }
        .replace(Regex("""[^A-Za-z0-9_.:-]+"""), "_")
        .take(96)

private fun String?.authErrorDiagnostics(): String {
    if (isNullOrBlank()) return "errorKeys=none error=none serverCode=none"
    val body = this
    val parsed = runCatching { authJson.decodeFromString(JsonObject.serializer(), body) }.getOrNull()
        ?: return "errorKeys=unparseable error=unparseable serverCode=unparseable"
    val keys = parsed.keys.sorted().joinToString("|").ifBlank { "empty" }.authDiagnosticsToken()
    val error = parsed["error"]?.jsonPrimitive?.content?.authDiagnosticsToken() ?: "none"
    val code = parsed["code"]?.jsonPrimitive?.content?.authDiagnosticsToken() ?: "none"
    return "errorKeys=$keys error=$error serverCode=$code"
}

private fun String.authBodyDiagnostics(): String {
    val bytes = encodeToByteArray().size
    val element = runCatching { authJson.decodeFromString(JsonElement.serializer(), this) }.getOrNull()
        ?: return "bodyShape=unparseable bodyBytes=$bytes bodyKeys=none"
    return when (element) {
        is JsonObject -> {
            val keys = element.keys.sorted().joinToString("|").ifBlank { "empty" }.authDiagnosticsToken()
            "bodyShape=object bodyBytes=$bytes bodyKeys=$keys"
        }
        is JsonArray -> "bodyShape=array bodyBytes=$bytes bodyKeys=none"
        is JsonPrimitive -> "bodyShape=primitive bodyBytes=$bytes bodyKeys=none"
    }
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
    suspend fun startMobileOAuth(
        provider: String,
        mode: String,
        platform: String,
        appEnv: String,
        returnUri: String,
        codeChallenge: String,
        authorization: String? = null
    ): AuthApiResult<AuthMobileOAuthStartResponse>
    suspend fun completeMobileOAuth(
        code: String,
        codeVerifier: String,
        platform: String,
        acceptLegalTerms: Boolean = false,
        authorization: String? = null
    ): AuthApiResult<AuthLoginResponse>
    suspend fun startMobileOAuthLink(
        provider: String,
        returnUri: String,
        codeChallenge: String,
        authorization: String
    ): AuthApiResult<AuthMobileOAuthStartResponse>
    suspend fun completeMobileOAuthLink(
        provider: String,
        code: String,
        codeVerifier: String,
        authorization: String
    ): AuthApiResult<AuthMobileOAuthLinkCompleteResponse>
    suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse>
    suspend fun forgotPassword(email: String): AuthApiResult<Unit>
    suspend fun resendVerification(authorization: String): AuthApiResult<Unit>
    suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser>
    suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse>
    suspend fun logout(refreshToken: String): AuthApiResult<Unit>
    suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>>
    suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer>
}

class SlockAuthApiClient(
    private val transport: SlockAuthTransport,
    private val diagnosticsLogger: (String) -> Unit = {}
) : SlockAuthApi {
    override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
        transport.login(authJson.encodeToString(AuthLoginRequestBody(email = email, password = password)))
            .mapAuthBody("auth_login_contract_mismatch", AuthLoginResponseDto.serializer(), diagnosticsLogger) { it.toDomain() }

    override suspend fun completeSocialLogin(
        provider: String,
        bodyText: String
    ): AuthApiResult<AuthSocialCompleteResponse> =
        transport.completeSocialLogin(provider, bodyText)
            .mapAuthBody(
                "auth_social_complete_contract_mismatch",
                AuthSocialCompleteResponseDto.serializer(),
                diagnosticsLogger
            ) {
                it.toDomain()
            }

    override suspend fun startMobileOAuth(
        provider: String,
        mode: String,
        platform: String,
        appEnv: String,
        returnUri: String,
        codeChallenge: String,
        authorization: String?
    ): AuthApiResult<AuthMobileOAuthStartResponse> =
        transport.startMobileOAuth(
            authJson.encodeToString(
                AuthMobileOAuthStartRequestBody(
                    provider = provider,
                    mode = mode,
                    platform = platform,
                    appEnv = appEnv,
                    returnUri = returnUri,
                    codeChallenge = codeChallenge,
                    codeChallengeMethod = "S256"
                )
            ),
            authorization = authorization
        ).mapAuthBody(
            "auth_mobile_oauth_start_contract_mismatch",
            AuthMobileOAuthStartResponseDto.serializer(),
            diagnosticsLogger
        ) {
            it.toDomain()
        }

    override suspend fun completeMobileOAuth(
        code: String,
        codeVerifier: String,
        platform: String,
        acceptLegalTerms: Boolean,
        authorization: String?
    ): AuthApiResult<AuthLoginResponse> =
        transport.completeMobileOAuth(
            authJson.encodeToString(
                AuthMobileOAuthCompleteRequestBody(
                    code = code,
                    codeVerifier = codeVerifier,
                    platform = platform,
                    acceptTerms = acceptLegalTerms.takeIf { it },
                    termsVersion = CURRENT_TERMS_VERSION.takeIf { acceptLegalTerms },
                    privacyVersion = CURRENT_PRIVACY_VERSION.takeIf { acceptLegalTerms },
                    legalAcceptanceSource = "oauth".takeIf { acceptLegalTerms }
                )
            ),
            authorization = authorization
        ).mapAuthBody(
            "auth_mobile_oauth_complete_contract_mismatch",
            AuthLoginResponseDto.serializer(),
            diagnosticsLogger
        ) {
            it.toDomain()
        }

    override suspend fun startMobileOAuthLink(
        provider: String,
        returnUri: String,
        codeChallenge: String,
        authorization: String
    ): AuthApiResult<AuthMobileOAuthStartResponse> =
        transport.startMobileOAuthLink(
            provider = provider,
            bodyText =
                authJson.encodeToString(
                    AuthMobileOAuthLinkStartRequestBody(
                        returnUri = returnUri,
                        codeChallenge = codeChallenge,
                        codeChallengeMethod = "S256"
                    )
                ),
            authorization = authorization
        ).mapAuthBody(
            "auth_mobile_oauth_link_start_contract_mismatch",
            AuthMobileOAuthStartResponseDto.serializer(),
            diagnosticsLogger
        ) {
            it.toDomain()
        }

    override suspend fun completeMobileOAuthLink(
        provider: String,
        code: String,
        codeVerifier: String,
        authorization: String
    ): AuthApiResult<AuthMobileOAuthLinkCompleteResponse> =
        transport.completeMobileOAuthLink(
            provider = provider,
            bodyText =
                authJson.encodeToString(
                    AuthMobileOAuthLinkCompleteRequestBody(
                        code = code,
                        codeVerifier = codeVerifier
                    )
                ),
            authorization = authorization
        ).mapAuthBody(
            "auth_mobile_oauth_link_complete_contract_mismatch",
            AuthMobileOAuthLinkCompleteResponseDto.serializer(),
            diagnosticsLogger
        ) {
            it.toDomain()
        }

    override suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse> =
        transport.register(authJson.encodeToString(AuthRegisterRequestBody(email = email, password = password, name = name)))
            .mapAuthBody("auth_register_contract_mismatch", AuthLoginResponseDto.serializer(), diagnosticsLogger) { it.toDomain() }

    override suspend fun forgotPassword(email: String): AuthApiResult<Unit> {
        val response = transport.forgotPassword(authJson.encodeToString(AuthForgotPasswordRequestBody(email = email)))
        if (!response.isSuccessful) return response.toAuthApiFailure()
        return response.mapAuthBody(
            "auth_forgot_password_contract_mismatch",
            AuthOkResponseDto.serializer(),
            diagnosticsLogger
        ) { Unit }
    }

    override suspend fun resendVerification(authorization: String): AuthApiResult<Unit> {
        val response = transport.resendVerification(authorization)
        if (!response.isSuccessful) return response.toAuthApiFailure()
        return response.mapAuthBody(
            "auth_resend_verification_contract_mismatch",
            AuthOkResponseDto.serializer(),
            diagnosticsLogger
        ) { Unit }
    }

    override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
        transport.currentUser(authorization)
            .mapAuthBody(AUTH_ME_CONTRACT_MISMATCH, StoredAuthUser.serializer(), diagnosticsLogger) { it }

    override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> =
        transport.refresh(authJson.encodeToString(AuthRefreshRequestBody(refreshToken = refreshToken)))
            .mapAuthBody("auth_refresh_contract_mismatch", AuthRefreshResponseDto.serializer(), diagnosticsLogger) {
                it.toDomain()
            }

    override suspend fun logout(refreshToken: String): AuthApiResult<Unit> {
        val response = transport.logout(authJson.encodeToString(AuthLogoutRequestBody(refreshToken = refreshToken)))
        if (!response.isSuccessful) return response.toAuthApiFailure()
        return AuthApiResult.Success(Unit)
    }

    override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
        transport.servers(authorization)
            .mapAuthBody("servers_list_contract_mismatch", ListSerializer(StoredAuthServer.serializer()), diagnosticsLogger) { it }

    override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
        transport.createServer(authorization, bodyText)
            .mapAuthBody("server_create_contract_mismatch", StoredAuthServer.serializer(), diagnosticsLogger) { it }
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

data class AuthMobileOAuthStartResponse(
    val authorizationUrl: String,
    val requestId: String,
    val returnUri: String,
    val expiresAt: String
)

data class AuthMobileOAuthIdentity(
    val provider: String,
    val email: String?
)

data class AuthMobileOAuthLinkCompleteResponse(
    val provider: String,
    val mode: String,
    val returnUri: String,
    val identities: List<AuthMobileOAuthIdentity>
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
    LegalAcceptanceRequired,
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
data class AuthMobileOAuthStartRequestBody(
    val provider: String,
    val mode: String,
    val platform: String,
    val appEnv: String,
    val returnUri: String,
    val codeChallenge: String,
    val codeChallengeMethod: String
)

@kotlinx.serialization.Serializable
data class AuthMobileOAuthCompleteRequestBody(
    val code: String,
    val codeVerifier: String,
    val platform: String,
    val acceptTerms: Boolean? = null,
    val termsVersion: String? = null,
    val privacyVersion: String? = null,
    val legalAcceptanceSource: String? = null
)

@kotlinx.serialization.Serializable
data class AuthMobileOAuthLinkStartRequestBody(
    val returnUri: String,
    val codeChallenge: String,
    val codeChallengeMethod: String
)

@kotlinx.serialization.Serializable
data class AuthMobileOAuthLinkCompleteRequestBody(
    val code: String,
    val codeVerifier: String
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
        code == 422 && errorDto?.error in setOf("LEGAL_ACCEPTANCE_REQUIRED", "TERMS_CHANGED") ->
            SharedAuthResult.Failure(
                SharedAuthErrorCode.LegalAcceptanceRequired,
                "Review and accept Raft's Terms of Service and Privacy Policy to continue."
            )
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

private fun List<String>.authServerOrderDiagnosticsValue(): String =
    joinToString(separator = ",", prefix = "[", postfix = "]")

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
    diagnosticsLogger: (String) -> Unit = {},
    crossinline transform: (Wire) -> T,
): AuthApiResult<T> {
    if (!isSuccessful) return toAuthApiFailure()
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return AuthApiResult.Failure(code = code, contractCode = contractCode).also {
            diagnosticsLogger("auth_contract_mismatch contract=$contractCode http=$code bodyShape=missing bodyBytes=0")
        }
    val wire = try {
        authJson.decodeFromString(serializer, body)
    } catch (error: SerializationException) {
        diagnosticsLogger(
            "auth_contract_mismatch contract=$contractCode http=$code " +
                "${body.authBodyDiagnostics()} errorType=${error.safeDiagnosticsType()}"
        )
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
private data class AuthMobileOAuthStartResponseDto(
    val authorizationUrl: String,
    val requestId: String,
    val returnUri: String,
    val expiresAt: String,
)

private fun AuthMobileOAuthStartResponseDto.toDomain(): AuthMobileOAuthStartResponse =
    AuthMobileOAuthStartResponse(
        authorizationUrl = authorizationUrl,
        requestId = requestId,
        returnUri = returnUri,
        expiresAt = expiresAt
    )

@Serializable
private data class AuthMobileOAuthLinkIdentityDto(
    val provider: String,
    val providerEmail: String? = null,
    val email: String? = null
)

@Serializable
private data class AuthMobileOAuthLinkCompleteResponseDto(
    val provider: String,
    val mode: String,
    val returnUri: String,
    val identities: List<AuthMobileOAuthLinkIdentityDto> = emptyList()
)

private fun AuthMobileOAuthLinkCompleteResponseDto.toDomain(): AuthMobileOAuthLinkCompleteResponse =
    AuthMobileOAuthLinkCompleteResponse(
        provider = provider,
        mode = mode,
        returnUri = returnUri,
        identities =
            identities.map {
                AuthMobileOAuthIdentity(
                    provider = it.provider,
                    email = it.providerEmail?.takeIf { email -> email.isNotBlank() }
                        ?: it.email?.takeIf { email -> email.isNotBlank() }
                )
            }
    )

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
