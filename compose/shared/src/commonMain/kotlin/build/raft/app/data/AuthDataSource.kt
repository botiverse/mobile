package build.raft.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal const val AuthAuthorityChangedMessage = "auth_authority_changed"

private val authJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

class SharedAuthDataSource(
    private val api: SlockAuthApi,
    private val sessionStore: SlockAuthSessionStore,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val criticalDiagnosticsLogger: (String) -> Unit = diagnosticsLogger
) {
    private var avatarCacheRuntime: SlockAvatarCacheRuntime? = null
    private var avatarCacheNativeCache: SlockAvatarNativeCache = UnsupportedSlockAvatarNativeCache
    private var avatarCacheEnvironmentId: () -> String = { "" }

    internal fun withAvatarCacheRuntime(
        runtime: SlockAvatarCacheRuntime?,
        nativeCache: SlockAvatarNativeCache,
        environmentId: () -> String
    ): SharedAuthDataSource = apply {
        avatarCacheRuntime = runtime
        avatarCacheNativeCache = nativeCache
        avatarCacheEnvironmentId = environmentId
    }

    private val serverSelectionMemory = sessionStore as? SlockAuthServerSelectionMemory
    private val authMiddleware =
        SlockNetworkAuthMiddleware(
            sessionStore = sessionStore,
            authApi = api,
            logger = diagnosticsLogger,
            warningLogger = diagnosticsLogger,
            criticalLogger = criticalDiagnosticsLogger
        )

    constructor(
        transport: SlockAuthTransport,
        sessionStore: SlockAuthSessionStore,
        diagnosticsLogger: (String) -> Unit = {},
        criticalDiagnosticsLogger: (String) -> Unit = diagnosticsLogger
    ) : this(
        api = SlockAuthApiClient(transport, diagnosticsLogger),
        sessionStore = sessionStore,
        diagnosticsLogger = diagnosticsLogger,
        criticalDiagnosticsLogger = criticalDiagnosticsLogger
    )

    fun logDiagnostics(message: String) {
        diagnosticsLogger(message)
    }

    /**
     * Backend-advertised, platform-gated social provider ids. Returns null on any
     * failure so callers can stay fail-closed (provider hidden until a probe succeeds).
     */
    suspend fun probeBackendSocialProviders(platform: String): Set<String>? =
        when (val result = api.fetchSocialAuthProviders(platform)) {
            is AuthApiResult.Success -> result.data
            is AuthApiResult.Failure -> {
                diagnosticsLogger(
                    "auth_social_providers_probe platform=$platform result=failure http=${result.code}"
                )
                null
            }
        }

    suspend fun login(email: String, password: String): SharedAuthResult<StoredAuthSession> {
        if (email.isBlank() || password.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Email and password are required."
            )
        }

        diagnosticsLogger("auth_login_clear_cached_session_start provider=password")
        runCatchingPreservingCancellation {
            clearSessionAndRetireAvatarOwners(
                reason = "login_clear_cached_session",
                source = "auth_login"
            )
        }
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
        val orderedServers =
            servers.orderedByServerOrder(
                loadServerOrderOrNull("Bearer ${login.accessToken}", source = "password_login")
            )
        val session =
            StoredAuthSession(
                user = login.user,
                accessToken = login.accessToken,
                refreshToken = login.refreshToken,
                servers = orderedServers
            ).withValidatedServerSelection(orderedServers, serverSelectionMemory)
        val selectedServerPresent = !session.selectedServerId.isNullOrBlank()
        diagnosticsLogger("auth_selected_server_persist_start serverIdPresent=$selectedServerPresent")
        diagnosticsLogger("auth_session_persist_start userPresent=${login.user.id.isNotBlank()} serversCount=${orderedServers.size} selectedServer=$selectedServerPresent")
        runCatchingPreservingCancellation {
            replaceAuthenticatedSessionAndRetireChangedAccount(session, "password_login")
        }
            .getOrElse { throwable ->
                diagnosticsLogger(
                    "auth_session_persist_result result=failure stage=session_persist ${throwable.persistenceDiagnostics()} mapped=post_login_setup"
                )
                return loginStageFailure("session_persist", "Could not save session locally.")
            }
        diagnosticsLogger("auth_session_persist_result result=success stage=session_persist")
        diagnosticsLogger("auth_selected_server_persist_result result=success serverIdPresent=$selectedServerPresent")
        diagnosticsLogger(
            "auth_login_result provider=password result=success serversCount=${orderedServers.size} selectedServer=$selectedServerPresent"
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
        platform: String = "android",
        appEnv: String,
        returnUri: String,
        codeChallenge: String
    ): SharedAuthResult<AuthMobileOAuthStartResponse> {
        val normalizedProvider = provider.trim().lowercase()
        if (normalizedProvider !in BrowserMobileOAuthProviders) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "This sign-in provider is not available on this device."
            )
        }
        if (platform.isBlank() || appEnv.isBlank() || returnUri.isBlank() || codeChallenge.isBlank()) {
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
                    platform = platform.trim(),
                    appEnv = appEnv.trim(),
                    returnUri = returnUri.trim(),
                    codeChallenge = codeChallenge.trim()
                )
        ) {
            is AuthApiResult.Success -> SharedAuthResult.Success(response.data)
            is AuthApiResult.Failure -> response.toSharedAuthFailure()
        }
    }

    suspend fun startNativeAppleMobileOAuthLogin(
        appEnv: String,
        returnUri: String,
        codeChallenge: String,
    ): SharedAuthResult<AuthNativeAppleMobileOAuthStartResponse> =
        startNativeAppleMobileOAuth(
            mode = "login",
            appEnv = appEnv,
            returnUri = returnUri,
            codeChallenge = codeChallenge,
        )

    suspend fun authorizeNativeAppleMobileOAuthLogin(
        requestId: String,
        requestToken: String,
        authorizationCode: String,
        identityToken: String,
    ): SharedAuthResult<AuthNativeAppleMobileOAuthAuthorizationResponse> =
        authorizeNativeAppleMobileOAuth(
            mode = "login",
            requestId = requestId,
            requestToken = requestToken,
            authorizationCode = authorizationCode,
            identityToken = identityToken,
        )

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
        return establishLoginSession(login)
    }

    suspend fun startAppLogin(returnUri: String, codeChallenge: String): SharedAuthResult<AuthAppLoginStartResponse> =
        when (val response = api.startAppLogin(returnUri, codeChallenge)) {
            is AuthApiResult.Success -> SharedAuthResult.Success(response.data)
            is AuthApiResult.Failure -> response.toSharedAuthFailure()
        }

    suspend fun completeAppLogin(code: String, codeVerifier: String, isCurrent: () -> Boolean = { true }): SharedAuthResult<StoredAuthSession> =
        when (val response = api.completeAppLogin(code, codeVerifier)) {
            is AuthApiResult.Success -> if (isCurrent()) establishLoginSession(response.data, isCurrent) else SharedAuthResult.Unauthenticated
            is AuthApiResult.Failure -> response.toSharedAuthFailure()
        }

    private suspend fun establishLoginSession(login: AuthLoginResponse, isCurrent: () -> Boolean = { true }): SharedAuthResult<StoredAuthSession> {
        val servers =
            when (val response = api.servers("Bearer ${login.accessToken}")) {
                is AuthApiResult.Success -> {
                    diagnosticsLogger("auth_login_servers_result provider=browser_login result=success serversCount=${response.data.size}")
                    response.data
                }
                is AuthApiResult.Failure -> {
                    val failure = response.toSharedAuthFailure()
                    diagnosticsLogger("auth_login_servers_result provider=browser_login result=failure code=${failure.code} fallback=empty")
                    emptyList()
                }
            }
        val orderedServers =
            servers.orderedByServerOrder(
                loadServerOrderOrNull("Bearer ${login.accessToken}", source = "browser_login")
            )
        val session =
            StoredAuthSession(
                user = login.user,
                accessToken = login.accessToken,
                refreshToken = login.refreshToken,
                servers = orderedServers
            ).withValidatedServerSelection(orderedServers, serverSelectionMemory)
        val selectedServerPresent = !session.selectedServerId.isNullOrBlank()
        diagnosticsLogger("auth_session_persist_start provider=browser_login userPresent=${login.user.id.isNotBlank()} serversCount=${orderedServers.size} selectedServer=$selectedServerPresent")
        if (!isCurrent()) return SharedAuthResult.Unauthenticated
        val committed = runCatchingPreservingCancellation {
            replaceAuthenticatedSessionAndRetireChangedAccount(session, "browser_login", isCurrent)
        }
            .getOrElse { throwable ->
                diagnosticsLogger(
                    "auth_session_persist_result provider=browser_login result=failure stage=session_persist ${throwable.persistenceDiagnostics()} mapped=post_login_setup"
                )
                return loginStageFailure("session_persist", "Could not save session locally.")
            }
        if (!committed) return SharedAuthResult.Unauthenticated
        diagnosticsLogger("auth_session_persist_result provider=browser_login result=success stage=session_persist")
        diagnosticsLogger("auth_login_result provider=browser_login result=success serversCount=${orderedServers.size} selectedServer=$selectedServerPresent")
        return SharedAuthResult.Success(session)
    }

    suspend fun startMobileOAuthLink(
        provider: String,
        returnUri: String,
        codeChallenge: String
    ): SharedAuthResult<AuthMobileOAuthStartResponse> {
        val normalizedProvider = provider.trim().lowercase()
        if (normalizedProvider !in BrowserMobileOAuthProviders) {
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

    suspend fun startNativeAppleMobileOAuthLink(
        appEnv: String,
        returnUri: String,
        codeChallenge: String,
    ): SharedAuthResult<AuthNativeAppleMobileOAuthStartResponse> =
        startNativeAppleMobileOAuth(
            mode = "link",
            appEnv = appEnv,
            returnUri = returnUri,
            codeChallenge = codeChallenge,
        )

    suspend fun authorizeNativeAppleMobileOAuthLink(
        requestId: String,
        requestToken: String,
        authorizationCode: String,
        identityToken: String,
    ): SharedAuthResult<AuthNativeAppleMobileOAuthAuthorizationResponse> =
        authorizeNativeAppleMobileOAuth(
            mode = "link",
            requestId = requestId,
            requestToken = requestToken,
            authorizationCode = authorizationCode,
            identityToken = identityToken,
        )

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

    private suspend fun startNativeAppleMobileOAuth(
        mode: String,
        appEnv: String,
        returnUri: String,
        codeChallenge: String,
    ): SharedAuthResult<AuthNativeAppleMobileOAuthStartResponse> {
        if (appEnv.isBlank() || returnUri.isBlank() || codeChallenge.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                if (mode == "link") "Account linking could not be prepared." else "Mobile sign-in could not be prepared.",
            )
        }
        val authorization =
            if (mode == "link") {
                val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
                "Bearer ${session.accessToken}"
            } else {
                null
            }
        return when (
            val response =
                api.startNativeAppleMobileOAuth(
                    mode = mode,
                    platform = "ios",
                    appEnv = appEnv.trim(),
                    returnUri = returnUri.trim(),
                    codeChallenge = codeChallenge.trim(),
                    authorization = authorization,
                )
        ) {
            is AuthApiResult.Success -> SharedAuthResult.Success(response.data)
            is AuthApiResult.Failure -> response.toSharedAuthFailure()
        }
    }

    private suspend fun authorizeNativeAppleMobileOAuth(
        mode: String,
        requestId: String,
        requestToken: String,
        authorizationCode: String,
        identityToken: String,
    ): SharedAuthResult<AuthNativeAppleMobileOAuthAuthorizationResponse> {
        if (
            requestId.isBlank() ||
            requestToken.isBlank() ||
            authorizationCode.isBlank() ||
            identityToken.isBlank()
        ) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                if (mode == "link") {
                    "Account linking did not return the required credentials."
                } else {
                    "Mobile sign-in did not return the required credentials."
                },
            )
        }
        val authorization =
            if (mode == "link") {
                val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
                "Bearer ${session.accessToken}"
            } else {
                null
            }
        return when (
            val response =
                api.authorizeNativeAppleMobileOAuth(
                    requestId = requestId.trim(),
                    requestToken = requestToken.trim(),
                    authorizationCode = authorizationCode.trim(),
                    identityToken = identityToken.trim(),
                    authorization = authorization,
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
        val orderedServers =
            servers.orderedByServerOrder(
                loadServerOrderOrNull("Bearer ${tokens.accessToken}", source = "${provider}_login")
            )
        val session =
            StoredAuthSession(
                user = user,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                servers = orderedServers
            ).withValidatedServerSelection(orderedServers, serverSelectionMemory)
        val selectedServerPresent = !session.selectedServerId.isNullOrBlank()
        diagnosticsLogger("auth_session_persist_start provider=$provider userPresent=${user.id.isNotBlank()} serversCount=${orderedServers.size} selectedServer=$selectedServerPresent")
        runCatchingPreservingCancellation {
            replaceAuthenticatedSessionAndRetireChangedAccount(session, "${provider}_login")
        }
            .getOrElse { throwable ->
                diagnosticsLogger(
                    "auth_session_persist_result provider=$provider result=failure stage=session_persist ${throwable.persistenceDiagnostics()} mapped=post_login_setup"
                )
                return loginStageFailure("session_persist", "Could not save session locally.")
            }
        diagnosticsLogger("auth_session_persist_result provider=$provider result=success stage=session_persist")
        diagnosticsLogger("auth_login_result provider=$provider result=success serversCount=${orderedServers.size} selectedServer=$selectedServerPresent")
        return SharedAuthResult.Success(session)
    }

    suspend fun register(
        email: String,
        password: String
    ): SharedAuthResult<StoredAuthSession> {
        if (email.isBlank() || password.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Email and password are required."
            )
        }
        if (password.length < 8) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Password must be at least 8 characters."
            )
        }

        val register =
            when (val response = api.register(email = email.trim(), password = password)) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> return response.toSharedAuthFailure()
            }
        val servers =
            when (val response = api.servers("Bearer ${register.accessToken}")) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> emptyList()
            }
        val orderedServers =
            servers.orderedByServerOrder(
                loadServerOrderOrNull("Bearer ${register.accessToken}", source = "register")
            )
        val session =
            StoredAuthSession(
                user = register.user,
                accessToken = register.accessToken,
                refreshToken = register.refreshToken,
                servers = orderedServers
            ).withValidatedServerSelection(orderedServers, serverSelectionMemory)
        replaceAuthenticatedSessionAndRetireChangedAccount(session, "register")
        return SharedAuthResult.Success(session)
    }

    suspend fun completeProfile(name: String, displayName: String): SharedAuthResult<StoredAuthSession> {
        val normalizedName = name.trim().removePrefix("@")
        val normalizedDisplayName = displayName.trim()
        if (normalizedName.length < 5 || normalizedDisplayName.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Username and display name are required."
            )
        }
        val current = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        val updatedUser =
            when (
                val response = api.completeProfile(
                    authorization = current.bearer(),
                    name = normalizedName,
                    displayName = normalizedDisplayName
                )
            ) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> return response.toSharedAuthFailure()
            }
        sessionStore.updateMetadata(expectedAccountId = current.user.id) { latest ->
            latest.copy(user = updatedUser)
        }
        val persisted = sessionStore.readSession()
        if (persisted == null || persisted.user.id != current.user.id || persisted.user != updatedUser) {
            return SharedAuthResult.Unauthenticated
        }
        return SharedAuthResult.Success(persisted)
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

    /**
     * Consumes the server-issued email verification token, then refreshes the persisted session
     * from the canonical current-user endpoint. Verification itself is intentionally independent
     * of local auth state; local `emailVerified` is never fabricated from possession of the link.
     */
    suspend fun verifyEmail(token: String): SharedEmailVerificationResult {
        if (token.isBlank()) {
            return SharedEmailVerificationResult.Failure(
                SharedAuthErrorCode.Validation,
                "Email verification token is required."
            )
        }
        when (val response = api.verifyEmail(token)) {
            is AuthApiResult.Success -> Unit
            is AuthApiResult.Failure -> {
                val failure = response.toSharedAuthFailure()
                return SharedEmailVerificationResult.Failure(failure.code, failure.message)
            }
        }
        return refreshEmailVerificationStatus()
    }

    /** Retries only canonical account refresh after the one-time token was already consumed. */
    suspend fun refreshEmailVerificationStatus(): SharedEmailVerificationResult =
        when (val refreshed = restoreSession(allowRecoverableCachedSession = false)) {
            is SharedAuthResult.Success ->
                if (refreshed.data.user.emailVerified == true) {
                    SharedEmailVerificationResult.Verified(refreshed.data)
                } else {
                    SharedEmailVerificationResult.RefreshPending(
                        "Email verification is complete, but account status is still updating. Refresh again shortly."
                    )
                }
            SharedAuthResult.Unauthenticated -> SharedEmailVerificationResult.LoginRequired
            is SharedAuthResult.Failure ->
                SharedEmailVerificationResult.RefreshPending(
                    if (refreshed.code == SharedAuthErrorCode.Network) {
                        "Email verified. Refresh your account status when you're back online."
                    } else {
                        "Email verified, but your account status could not be refreshed. Refresh it in the app."
                    }
                )
        }

    suspend fun currentSession(): SharedAuthResult<StoredAuthSession> =
        sessionStore.readSession()?.let { SharedAuthResult.Success(it) }
            ?: SharedAuthResult.Unauthenticated

    suspend fun restoreSession(): SharedAuthResult<StoredAuthSession> =
        restoreSession(allowRecoverableCachedSession = true)

    private suspend fun restoreSession(
        allowRecoverableCachedSession: Boolean
    ): SharedAuthResult<StoredAuthSession> {
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
                    runCatchingPreservingCancellation {
                        refreshAfterUnauthorized(
                            failedSession = session,
                            clearOnUnauthorized = true,
                            reason = "auth_restore_401",
                            caller = "auth_restore"
                        )
                    }
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
                                if (allowRecoverableCachedSession && refreshedCurrent.isRecoverableRestoreFailure()) {
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
                        if (allowRecoverableCachedSession && refreshed.isRecoverableRestoreFailure()) {
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
                if (allowRecoverableCachedSession && current.isRecoverableRestoreFailure()) {
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

    private suspend fun refreshAfterUnauthorized(
        failedSession: StoredAuthSession,
        clearOnUnauthorized: Boolean,
        reason: String,
        caller: String
    ): SharedAuthResult<StoredAuthSession> {
        if (failedSession.refreshToken.isBlank()) {
            if (clearOnUnauthorized) {
                val cleared =
                    sessionStore.clearSessionIfMatches(
                        expectedAccountId = failedSession.user.id,
                        expectedAccessToken = failedSession.accessToken,
                        expectedRefreshToken = failedSession.refreshToken,
                        reason = "missing_refresh_token",
                        source = "auth_data_source_refresh",
                    )
                if (!cleared) {
                    return sessionStore.readSession()
                        ?.let { SharedAuthResult.Success(it) }
                        ?: SharedAuthResult.Unauthenticated
                }
            }
            return SharedAuthResult.Unauthenticated
        }
        return when (
            val result =
                authMiddleware.refreshAfterUnauthorized(
                    failedSession = failedSession,
                    clearOnUnauthorized = clearOnUnauthorized,
                    reason = reason,
                    caller = caller
                )
        ) {
            is SharedAuthRefreshResult.Success -> SharedAuthResult.Success(result.session)
            is SharedAuthRefreshResult.Failure ->
                if (result.response.code == 401 || result.response.code == 403) {
                    SharedAuthResult.Unauthenticated
                } else {
                    result.response.toSharedAuthFailure()
                }
            SharedAuthRefreshResult.Unavailable -> SharedAuthResult.Unauthenticated
        }
    }

    suspend fun refreshRealtimeAuth(reason: String): SharedAuthResult<StoredAuthSession> {
        val current = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        if (current.refreshToken.isBlank()) {
            diagnosticsLogger("auth_realtime_refresh_result result=unauthenticated reason=$reason missing_refresh_token=true")
            return SharedAuthResult.Unauthenticated
        }
        diagnosticsLogger("auth_realtime_refresh_start reason=$reason")
        return when (
            val result =
                authMiddleware.refreshAfterUnauthorized(
                    failedSession = current,
                    clearOnUnauthorized = false,
                    reason = reason,
                    caller = "shared_realtime"
                )
        ) {
            is SharedAuthRefreshResult.Success -> {
                diagnosticsLogger("auth_realtime_refresh_result result=success reason=$reason")
                SharedAuthResult.Success(result.session)
            }
            is SharedAuthRefreshResult.Failure -> {
                val failure = result.response.toSharedAuthFailure()
                criticalDiagnosticsLogger(
                    "auth_realtime_refresh_result result=failure reason=$reason code=${failure.code}"
                )
                // Realtime reconnect attempts are background recovery. Preserve the cached
                // session on unauthorized so the visible auth gate decides whether to clear it.
                failure
            }
            SharedAuthRefreshResult.Unavailable -> SharedAuthResult.Unauthenticated
        }
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
                        criticalDiagnosticsLogger(
                            "auth_servers_load_result result=unauthorized source=access_token action=refresh"
                        )
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
        when (
            val refreshed =
                refreshAfterUnauthorized(
                    failedSession = session,
                    clearOnUnauthorized = true,
                    reason = "servers_http_401",
                    caller = "auth_data_source_load_servers"
                )
        ) {
            is SharedAuthResult.Success -> {
                val updated = refreshed.data
                val servers =
                    when (val retryResponse = api.servers(updated.bearer())) {
                        is AuthApiResult.Success -> retryResponse.data
                        is AuthApiResult.Failure -> {
                            if (retryResponse.code == 401) {
                                diagnosticsLogger("auth_servers_load_result result=unauthenticated source=refresh_retry")
                                return terminalUnauthorizedForCapturedSession(
                                    session = updated,
                                    reason = "servers_refresh_retry_http_401",
                                    source = "auth_data_source_load_servers",
                                )
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

    private suspend fun terminalUnauthorizedForCapturedSession(
        session: StoredAuthSession,
        reason: String,
        source: String,
    ): SharedAuthResult<StoredAuthSession> {
        val cleared =
            sessionStore.clearSessionIfMatches(
                expectedAccountId = session.user.id,
                expectedAccessToken = session.accessToken,
                expectedRefreshToken = session.refreshToken,
                reason = reason,
                source = source,
            )
        if (cleared) return SharedAuthResult.Unauthenticated
        return sessionStore.readSession()
            ?.let { SharedAuthResult.Success(it) }
            ?: SharedAuthResult.Unauthenticated
    }

    private suspend fun terminalMutationUnauthorizedForCapturedSession(
        session: StoredAuthSession,
        reason: String,
        source: String,
    ): SharedAuthResult<StoredAuthSession> {
        val cleared =
            sessionStore.clearSessionIfMatches(
                expectedAccountId = session.user.id,
                expectedAccessToken = session.accessToken,
                expectedRefreshToken = session.refreshToken,
                reason = reason,
                source = source,
            )
        if (cleared || sessionStore.readSession() == null) {
            return SharedAuthResult.Unauthenticated
        }
        return SharedAuthResult.Failure(
            SharedAuthErrorCode.Unknown,
            AuthAuthorityChangedMessage,
        )
    }

    private suspend fun persistLoadedServers(
        session: StoredAuthSession,
        servers: List<StoredAuthServer>,
        source: String
    ): SharedAuthResult<StoredAuthSession> {
        val orderedServers = servers.orderedByServerOrder(loadServerOrderOrNull(session.bearer(), source = source))
        // Validate selection in the suspend body (withValidatedServerSelection is suspend), then apply
        // only the resulting server/selection FIELDS onto the store's LATEST session under the lock
        // (keeping latest tokens) — the #466 fix, mirroring currentUserWithSession. Replaces the old
        // read-fingerprint-then-whole-save that either dropped on any concurrent change or clobbered.
        val validated = session.withValidatedServerSelection(orderedServers, serverSelectionMemory)
        var persisted: StoredAuthSession? = null
        sessionStore.updateMetadata(expectedAccountId = session.user.id) { latest ->
            latest.copy(
                servers = validated.servers,
                selectedServerId = validated.selectedServerId,
                selectedServerSlug = validated.selectedServerSlug
            ).also { persisted = it }
        }
        val updated = persisted ?: run {
            diagnosticsLogger("auth_servers_load_result result=stale source=$source action=drop")
            return SharedAuthResult.Unauthenticated
        }
        diagnosticsLogger(
            "auth_servers_load_result result=success source=$source serversCount=${orderedServers.size} " +
                "selectedServer=${!updated.selectedServerId.isNullOrBlank()} " +
                "avatarCount=${orderedServers.count { !it.avatarUrl.isNullOrBlank() }} " +
                "order=${orderedServers.map { it.id }.authServerOrderDiagnosticsValue()}"
        )
        return SharedAuthResult.Success(updated)
    }

    suspend fun selectServer(
        serverId: String,
        expectedSelectedServerId: String? = null,
    ): SharedAuthResult<StoredAuthSession> {
        val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        return selectStoredAuthServer(
            sessionStore = sessionStore,
            expectedSession = session,
            expectedSelectedServerId = expectedSelectedServerId,
            serverId = serverId,
            serverSelectionMemory = serverSelectionMemory,
        )
    }

    suspend fun createServer(
        name: String,
        slug: String? = null,
        region: String? = null
    ): SharedAuthResult<StoredAuthSession> {
        var session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
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
        var createResponse = api.createServer(session.bearer(), body)
        if (createResponse is AuthApiResult.Failure && createResponse.code == 401) {
            when (
                val refreshed =
                    refreshAfterUnauthorized(
                        failedSession = session,
                        clearOnUnauthorized = true,
                        reason = "create_server_http_401",
                        caller = "auth_data_source_create_server",
                    )
            ) {
                is SharedAuthResult.Success -> {
                    if (refreshed.data.user.id != session.user.id) {
                        return SharedAuthResult.Failure(
                            SharedAuthErrorCode.Unknown,
                            AuthAuthorityChangedMessage,
                        )
                    }
                    session = refreshed.data
                    createResponse = api.createServer(session.bearer(), body)
                }
                is SharedAuthResult.Failure -> return refreshed
                SharedAuthResult.Unauthenticated -> return SharedAuthResult.Unauthenticated
            }
        }
        val created =
            when (val response = createResponse) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> {
                    if (response.code == 401) {
                        return terminalMutationUnauthorizedForCapturedSession(
                            session = session,
                            reason = "create_server_refresh_retry_http_401",
                            source = "auth_data_source_create_server",
                        )
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
                .orderedByServerOrder(loadServerOrderOrNull(session.bearer(), source = "create_server"))
        val selected = latestServers.firstOrNull { it.id == created.id } ?: created
        // #466: update the server list on the LATEST session under the lock (keep latest tokens),
        // then use the explicit selected-server primitive so shell and process selection authorities
        // converge on the newly created server.
        var persisted: StoredAuthSession? = null
        sessionStore.updateMetadata(expectedAccountId = session.user.id) { latest ->
            latest.copy(
                servers = latestServers,
            ).also { persisted = it }
        }
        val updated = persisted ?: return SharedAuthResult.Unauthenticated
        return selectStoredAuthServer(
            sessionStore = sessionStore,
            expectedSession = updated,
            serverId = selected.id,
            serverSelectionMemory = serverSelectionMemory,
        )
    }

    suspend fun consumeQaBootstrap(request: QaBootstrapConsumeRequest): SharedAuthResult<StoredAuthSession> {
        runCatchingPreservingCancellation {
            clearSessionAndRetireAvatarOwners(
                reason = "qa_bootstrap_replace",
                source = "qa_bootstrap"
            )
        }.getOrElse {
            return SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "Could not clear the previous QA session.")
        }
        val consumed =
            when (val response = api.consumeQaBootstrap(request)) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> return response.toSharedAuthFailure()
            }
        val servers =
            when (val response = api.servers("Bearer ${consumed.accessToken}")) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> return response.toSharedAuthFailure()
            }
        val orderedServers =
            servers.orderedByServerOrder(
                loadServerOrderOrNull("Bearer ${consumed.accessToken}", source = "qa_bootstrap")
            )
        val selected = orderedServers.firstOrNull { it.id == consumed.bootstrap.serverId }
            ?: return SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "The QA bootstrap server is unavailable.")
        if (consumed.user.id != consumed.bootstrap.principalId) {
            return SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "The QA bootstrap principal is invalid.")
        }
        val session =
            StoredAuthSession(
                user = consumed.user,
                accessToken = consumed.accessToken,
                refreshToken = consumed.refreshToken,
                selectedServerId = selected.id,
                selectedServerSlug = selected.slug,
                servers = orderedServers,
                qaBootstrap =
                    StoredQaBootstrapMetadata(
                        ticketDigest = consumed.bootstrap.ticketDigest,
                        runCaseId = consumed.bootstrap.runCaseId,
                        attempt = consumed.bootstrap.attempt,
                        ciJobRef = consumed.bootstrap.ciJobRef,
                        principalId = consumed.bootstrap.principalId,
                        serverId = consumed.bootstrap.serverId,
                        sessionGeneration = consumed.bootstrap.sessionGeneration,
                        targetRoute = consumed.bootstrap.targetRoute,
                        applicationId = request.applicationId,
                        signingCertSha256 = request.signingCertSha256,
                        deviceBootGeneration = request.deviceBootGeneration
                    ),
                qaBootstrapLaunchAuthorized = true,
                allowImplicitServerFallback = false
            )
        return runCatchingPreservingCancellation {
            replaceAuthenticatedSessionAndRetireChangedAccount(session, "qa_bootstrap")
            SharedAuthResult.Success(session)
        }.getOrElse {
            SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "Could not save the QA bootstrap session.")
        }
    }

    suspend fun consumeQaLoginBootstrap(
        envelopeJson: String,
        envelopeDigest: String,
        applicationId: String,
        signingCertSha256: String,
        deviceBootGeneration: String,
    ): SharedAuthResult<StoredAuthSession> {
        runCatchingPreservingCancellation {
            clearSessionAndRetireAvatarOwners(
                reason = "qa_login_bootstrap_replace",
                source = "qa_login_bootstrap"
            )
        }.getOrElse {
            return SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "Could not clear the previous QA session.")
        }
        val envelope =
            runCatching { authJson.decodeFromString<QaBootstrapLoginEnvelope>(envelopeJson) }
                .getOrElse {
                    return SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "The QA login envelope is invalid.")
                }
        if (
            envelope.version != QA_BOOTSTRAP_LOGIN_PROTOCOL ||
            envelope.runCaseId.isBlank() ||
            envelope.attempt < 1 ||
            envelope.ciJobRef.isBlank() ||
            envelope.principalId != envelope.user.id ||
            envelope.serverId.isBlank() ||
            envelope.targetRoute != "compose_slock_app_shell" ||
            envelope.applicationId != applicationId ||
            envelope.signingCertSha256 != signingCertSha256 ||
            envelope.bootGeneration != deviceBootGeneration ||
            envelope.accessToken.isBlank() ||
            envelope.refreshToken.isBlank() ||
            !envelopeDigest.matches(Regex("sha256:[a-f0-9]{64}"))
        ) {
            return SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "The QA login envelope binding is invalid.")
        }
        val servers =
            when (val response = api.servers("Bearer ${envelope.accessToken}")) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> return response.toSharedAuthFailure()
            }
        val orderedServers =
            servers.orderedByServerOrder(
                loadServerOrderOrNull("Bearer ${envelope.accessToken}", source = "qa_login_bootstrap")
            )
        val selected = orderedServers.firstOrNull { it.id == envelope.serverId }
            ?: return SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "The QA login server is unavailable.")
        val session =
            StoredAuthSession(
                user = envelope.user,
                accessToken = envelope.accessToken,
                refreshToken = envelope.refreshToken,
                selectedServerId = selected.id,
                selectedServerSlug = selected.slug,
                servers = orderedServers,
                qaBootstrap =
                    StoredQaBootstrapMetadata(
                        ticketDigest = envelopeDigest,
                        runCaseId = envelope.runCaseId,
                        attempt = envelope.attempt,
                        ciJobRef = envelope.ciJobRef,
                        principalId = envelope.principalId,
                        serverId = envelope.serverId,
                        sessionGeneration = envelopeDigest,
                        targetRoute = envelope.targetRoute,
                        applicationId = envelope.applicationId,
                        signingCertSha256 = envelope.signingCertSha256,
                        deviceBootGeneration = envelope.bootGeneration,
                        protocol = QA_BOOTSTRAP_LOGIN_PROTOCOL,
                    ),
                qaBootstrapLaunchAuthorized = true,
                allowImplicitServerFallback = false,
            )
        return runCatchingPreservingCancellation {
            replaceAuthenticatedSessionAndRetireChangedAccount(session, "qa_login_bootstrap")
            SharedAuthResult.Success(session)
        }.getOrElse {
            SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "Could not save the QA login session.")
        }
    }

    suspend fun readyQaBootstrap(): SharedAuthResult<QaBootstrapReadyResponse> {
        val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        val metadata = session.qaBootstrap ?: return SharedAuthResult.Unauthenticated
        if (metadata.protocol == QA_BOOTSTRAP_LOGIN_PROTOCOL) {
            val response = metadata.localQaLoginReadyResponse()
            val persisted = sessionStore.updateQaBootstrapIfBindingMatches(metadata) { latest ->
                latest.copy(
                    qaBootstrap =
                        requireNotNull(latest.qaBootstrap).copy(
                            state = "ready",
                            readyReceipt =
                                StoredQaBootstrapReadyReceipt(
                                    receiptId = response.receiptId,
                                    receiptDigest = response.receiptDigest,
                                    version = response.receipt.version,
                                    readyAtServer = response.receipt.readyAtServer,
                                ),
                        )
                )
            }
            return if (persisted != null) {
                SharedAuthResult.Success(response)
            } else {
                SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "The QA login session changed before READY.")
            }
        }
        return when (
            val response =
                api.readyQaBootstrap(
                    ticketDigest = metadata.ticketDigest,
                    authorization = session.bearer(),
                    sessionGeneration = metadata.sessionGeneration
                )
        ) {
            is AuthApiResult.Success -> {
                if (!response.data.matchesFrozenBinding(metadata)) {
                    return SharedAuthResult.Failure(
                        SharedAuthErrorCode.Unknown,
                        "The QA bootstrap READY receipt does not match the consumed session."
                    )
                }
                val persisted = sessionStore.updateQaBootstrapIfBindingMatches(metadata) { latest ->
                    latest.copy(
                        qaBootstrap =
                            requireNotNull(latest.qaBootstrap).copy(
                                state = "ready",
                                readyReceipt =
                                    StoredQaBootstrapReadyReceipt(
                                        receiptId = response.data.receiptId,
                                        receiptDigest = response.data.receiptDigest,
                                        version = response.data.receipt.version,
                                        readyAtServer = response.data.receipt.readyAtServer
                                    )
                            )
                    )
                }
                if (persisted == null) {
                    SharedAuthResult.Failure(
                        SharedAuthErrorCode.Unknown,
                        "The QA bootstrap session changed before the READY receipt was saved."
                    )
                } else {
                    response.toSharedAuthResult()
                }
            }
            is AuthApiResult.Failure -> response.toSharedAuthFailure()
        }
    }

    suspend fun statusQaBootstrap(): SharedAuthResult<QaBootstrapStatusResponse> {
        val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        val metadata = session.qaBootstrap ?: return SharedAuthResult.Unauthenticated
        if (metadata.protocol == QA_BOOTSTRAP_LOGIN_PROTOCOL) {
            val ready = metadata.localQaLoginReadyResponse()
            return SharedAuthResult.Success(
                QaBootstrapStatusResponse(
                    status = metadata.state,
                    receiptId = ready.receiptId,
                    receiptDigest = ready.receiptDigest,
                    receipt = ready.receipt,
                )
            )
        }
        return when (val response = api.statusQaBootstrap(metadata.ticketDigest, session.bearer())) {
            is AuthApiResult.Success -> {
                if (response.data.status == "ready" && response.data.readyResponseOrNull()?.matchesFrozenBinding(metadata) != true) {
                    SharedAuthResult.Failure(
                        SharedAuthErrorCode.Unknown,
                        "The recovered QA bootstrap READY receipt does not match the consumed session."
                    )
                } else {
                    SharedAuthResult.Success(response.data)
                }
            }
            is AuthApiResult.Failure -> response.toSharedAuthFailure()
        }
    }

    suspend fun readStoredSession(): StoredAuthSession? = sessionStore.readSession()

    suspend fun authorizeQaBootstrapLaunch(session: StoredAuthSession): StoredAuthSession? =
        session.qaBootstrap?.let { expected ->
            sessionStore.updateQaBootstrapIfBindingMatches(expected) { latest ->
                latest.copy(qaBootstrapLaunchAuthorized = true)
            }
        }

    suspend fun applyQaBootstrapStatus(status: QaBootstrapStatusResponse): StoredAuthSession? {
        val session = sessionStore.readSession() ?: return null
        val metadata = session.qaBootstrap ?: return null
        if (status.status != "ready") {
            return session
        }
        val ready = status.readyResponseOrNull()?.takeIf { it.matchesFrozenBinding(metadata) } ?: return null
        return sessionStore.updateQaBootstrapIfBindingMatches(metadata) { latest ->
            latest.copy(
                qaBootstrap =
                    requireNotNull(latest.qaBootstrap).copy(
                        state = "ready",
                        readyReceipt =
                            StoredQaBootstrapReadyReceipt(
                                receiptId = ready.receiptId,
                                receiptDigest = ready.receiptDigest,
                                version = ready.receipt.version,
                                readyAtServer = ready.receipt.readyAtServer
                            )
                        )
            )
        }
    }

    suspend fun saveQaBootstrapRuntimeEnvelope(
        expected: StoredQaBootstrapMetadata,
        envelope: StoredQaBootstrapRuntimeEnvelope,
    ): StoredAuthSession? =
        sessionStore.updateQaBootstrapIfBindingMatches(expected) { latest ->
            latest.copy(
                qaBootstrap = requireNotNull(latest.qaBootstrap).copy(runtimeEnvelope = envelope)
            )
        }

    suspend fun logout(): SharedAuthResult<Unit> =
        withContext(NonCancellable) {
            diagnosticsLogger("auth_logout_enter")
            val session = sessionStore.readSession()
            val avatarRetirement = captureAvatarOwnerRetirement(session)
            var serverRevocation: String = "skipped_no_session"
            if (session != null) {
                runCatchingPreservingCancellation {
                    SlockPushRegistrationLifecycle.unbindBeforeLogout()
                }.onFailure { throwable ->
                    diagnosticsLogger(
                        "push_registration_unbind_result result=failure stage=logout " +
                            "errorType=${throwable.safeDiagnosticsType()}"
                    )
                }
                serverRevocation =
                    when (
                        val apiResult =
                            runCatchingPreservingCancellation {
                                api.logout(session.refreshToken)
                            }.getOrElse { throwable ->
                                diagnosticsLogger(
                                    "auth_logout_api_result result=error " +
                                        "errorType=${throwable.safeDiagnosticsType()}"
                                )
                                null
                            }
                    ) {
                        is AuthApiResult.Success -> {
                            diagnosticsLogger("auth_logout_api_result result=success")
                            "success"
                        }
                        is AuthApiResult.Failure -> {
                            diagnosticsLogger(
                                "auth_logout_api_result result=failure code=${apiResult.code}"
                            )
                            "failure_code_${apiResult.code}"
                        }
                        null -> "failure_exception"
                    }
                session.rememberSelectedServer(serverSelectionMemory)
            }
            sessionStore.clearSession(reason = "logout", source = "auth_data_source_logout")
            retireCapturedAvatarOwners(avatarRetirement, source = "logout")
            diagnosticsLogger("auth_logout_cleared serverRevocation=$serverRevocation")
            SharedAuthResult.Success(Unit)
        }

    private suspend fun clearSessionAndRetireAvatarOwners(reason: String, source: String) {
        val capture = captureAvatarOwnerRetirement(sessionStore.readSession())
        sessionStore.clearSession(reason = reason, source = source)
        retireCapturedAvatarOwners(capture, source)
    }

    private suspend fun replaceAuthenticatedSessionAndRetireChangedAccount(
        session: StoredAuthSession,
        source: String,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        val previous = sessionStore.readSession()
        val capture =
            previous
                ?.takeIf { it.user.id.trim() != session.user.id.trim() }
                ?.let(::captureAvatarOwnerRetirement)
        if (!sessionStore.replaceAuthenticatedSessionIfCurrent(session, isCurrent)) return false
        retireCapturedAvatarOwners(capture, source)
        return true
    }

    private fun captureAvatarOwnerRetirement(session: StoredAuthSession?): SlockAvatarOwnerRetirementCapture? =
        avatarCacheRuntime?.captureSessionRetirement(
            environmentId = avatarCacheEnvironmentId(),
            session = session
        )

    private suspend fun retireCapturedAvatarOwners(
        capture: SlockAvatarOwnerRetirementCapture?,
        source: String
    ) {
        val runtime = avatarCacheRuntime ?: return
        val captured = capture ?: return
        val receipt =
            runCatchingPreservingCancellation {
                runtime.retireOwners(captured, avatarCacheNativeCache)
            }
                .getOrElse {
                    diagnosticsLogger("avatar_cache_retire result=failure source=$source stage=exception")
                    return
                }
        when (receipt) {
            SlockAvatarCacheClearReceipt.Success ->
                diagnosticsLogger("avatar_cache_retire result=success source=$source owners=${captured.owners.size}")
            is SlockAvatarCacheClearReceipt.Failed ->
                diagnosticsLogger(
                    "avatar_cache_retire result=failure source=$source " +
                        "stages=${receipt.stages.joinToString(",") { it.name.lowercase() }}"
                )
        }
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
                        if (clearOnUnauthorized) {
                            val cleared =
                                sessionStore.clearSessionIfMatches(
                                    expectedAccountId = session.user.id,
                                    expectedAccessToken = session.accessToken,
                                    expectedRefreshToken = session.refreshToken,
                                    reason = "current_user_http_401",
                                    source = "auth_data_source_current_user",
                                )
                            if (!cleared) {
                                return sessionStore.readSession()
                                    ?.let { SharedAuthResult.Success(it) }
                                    ?: SharedAuthResult.Unauthenticated
                            }
                        }
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
        val orderedServers = servers.orderedByServerOrder(loadServerOrderOrNull(session.bearer(), source = "current_user"))
        // Compute the fetched metadata (user + servers + validated selection). This is token-free;
        // its tokens are the captured `session`'s and are deliberately NOT persisted below.
        val metadata = session.copy(user = user).withValidatedServerSelection(orderedServers, serverSelectionMemory)
        // #466 fix: apply the metadata onto the store's LATEST session under the store lock, keeping
        // the LATEST tokens. The old `session.copy(user=user); saveSession(updated)` wrote back this
        // captured snapshot's tokens, clobbering a token the socket coordinator may have rotated in
        // between (the #466/#489 lost-update). `updateMetadata` reads-latest-in-lock and is fail-closed
        // against any token change, so it can never overwrite a concurrently-committed rotation.
        var persisted: StoredAuthSession? = null
        // expectedAccountId guards against a login switching accounts between our capture and this
        // write — never apply this account's metadata onto a different account's session (#466).
        sessionStore.updateMetadata(expectedAccountId = session.user.id) { latest ->
            latest.copy(
                user = metadata.user,
                servers = metadata.servers,
                selectedServerId = metadata.selectedServerId,
                selectedServerSlug = metadata.selectedServerSlug,
            ).also { persisted = it }
        }
        // Session cleared concurrently (logout) → updateMetadata no-ops; do NOT revive it.
        return persisted?.let { SharedAuthResult.Success(it) } ?: SharedAuthResult.Unauthenticated
    }

    private suspend fun loadServerOrderOrNull(authorization: String, source: String): List<String>? =
        when (val response = api.serverOrder(authorization)) {
            is AuthApiResult.Success -> {
                diagnosticsLogger("auth_server_order_result result=success source=$source orderCount=${response.data.serverOrder.size}")
                response.data.serverOrder
            }
            is AuthApiResult.Failure -> {
                diagnosticsLogger(
                    "auth_server_order_result result=failure source=$source http=${response.code} contract=${response.contractCode ?: "none"} fallback=servers_order"
                )
                null
            }
        }
}

internal suspend fun selectStoredAuthServer(
    sessionStore: SlockAuthSessionStore,
    expectedSession: StoredAuthSession,
    expectedSelectedServerId: String? = null,
    serverId: String,
    serverSelectionMemory: SlockAuthServerSelectionMemory? = null,
): SharedAuthResult<StoredAuthSession> {
    if (expectedSession.servers.none { it.id == serverId }) {
        return SharedAuthResult.Failure(
            SharedAuthErrorCode.Unknown,
            "Server not found."
        )
    }
    val updated =
        sessionStore.selectServerIfSessionMatches(
            expectedAccountId = expectedSession.user.id,
            expectedAccessToken = expectedSession.accessToken,
            expectedRefreshToken = expectedSession.refreshToken,
            expectedSelectedServerId = expectedSelectedServerId,
            serverId = serverId,
        ) ?: return SharedAuthResult.Unauthenticated
    updated.rememberSelectedServer(serverSelectionMemory)
    return SharedAuthResult.Success(updated)
}

private fun <T> AuthApiResult.Success<T>.toSharedAuthResult(): SharedAuthResult<T> = SharedAuthResult.Success(data)

private val BrowserMobileOAuthProviders = setOf("google", "github")
private val MobileOAuthProviders = BrowserMobileOAuthProviders + "apple"
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
    suspend fun startAppLogin(returnUri: String, codeChallenge: String): AuthApiResult<AuthAppLoginStartResponse> = AuthApiResult.Failure(code = 501)
    suspend fun completeAppLogin(code: String, codeVerifier: String): AuthApiResult<AuthLoginResponse> = AuthApiResult.Failure(code = 501)
    suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse>
    suspend fun completeSocialLogin(provider: String, bodyText: String): AuthApiResult<AuthSocialCompleteResponse>
    suspend fun fetchSocialAuthProviders(platform: String): AuthApiResult<Set<String>>
    suspend fun startMobileOAuth(
        provider: String,
        mode: String,
        platform: String,
        appEnv: String,
        returnUri: String,
        codeChallenge: String,
        authorization: String? = null
    ): AuthApiResult<AuthMobileOAuthStartResponse>
    suspend fun startNativeAppleMobileOAuth(
        mode: String,
        platform: String,
        appEnv: String,
        returnUri: String,
        codeChallenge: String,
        authorization: String? = null,
    ): AuthApiResult<AuthNativeAppleMobileOAuthStartResponse> = AuthApiResult.Failure(code = 501)
    suspend fun authorizeNativeAppleMobileOAuth(
        requestId: String,
        requestToken: String,
        authorizationCode: String,
        identityToken: String,
        authorization: String? = null,
    ): AuthApiResult<AuthNativeAppleMobileOAuthAuthorizationResponse> = AuthApiResult.Failure(code = 501)
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
    suspend fun register(email: String, password: String): AuthApiResult<AuthLoginResponse>
    suspend fun completeProfile(
        authorization: String,
        name: String,
        displayName: String
    ): AuthApiResult<StoredAuthUser> = AuthApiResult.Failure(code = 501)
    suspend fun forgotPassword(email: String): AuthApiResult<Unit>
    suspend fun resendVerification(authorization: String): AuthApiResult<Unit>
    suspend fun verifyEmail(token: String): AuthApiResult<Unit>
    suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser>
    suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse>
    suspend fun refreshWithAttempt(
        refreshToken: String,
        attempt: AuthRefreshAttemptContext,
    ): AuthApiResult<AuthRefreshResponse> = refresh(refreshToken)
    suspend fun logout(refreshToken: String): AuthApiResult<Unit>
    suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>>
    suspend fun serverOrder(authorization: String): AuthApiResult<ServerSwitcherOrderPreference>
    suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer>
    suspend fun consumeQaBootstrap(request: QaBootstrapConsumeRequest): AuthApiResult<QaBootstrapConsumeResponse> =
        AuthApiResult.Failure(501)
    suspend fun readyQaBootstrap(ticketDigest: String, authorization: String, sessionGeneration: String): AuthApiResult<QaBootstrapReadyResponse> =
        AuthApiResult.Failure(501)
    suspend fun statusQaBootstrap(ticketDigest: String, authorization: String): AuthApiResult<QaBootstrapStatusResponse> =
        AuthApiResult.Failure(501)
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

    override suspend fun fetchSocialAuthProviders(platform: String): AuthApiResult<Set<String>> =
        transport.socialAuthProviders(platform)
            .mapAuthBody(
                "auth_social_providers_contract_mismatch",
                AuthSocialProvidersDto.serializer(),
                diagnosticsLogger
            ) { dto ->
                dto.providers
                    .filter { it.enabled }
                    .map { it.id.trim().lowercase() }
                    .filter { it.isNotBlank() }
                    .toSet()
            }

    override suspend fun startAppLogin(returnUri: String, codeChallenge: String): AuthApiResult<AuthAppLoginStartResponse> =
        transport.startAppLogin(authJson.encodeToString(AuthAppLoginStartBody(returnUri, codeChallenge)))
            .mapAuthBody("auth_app_login_start_contract_mismatch", AuthAppLoginStartResponse.serializer(), diagnosticsLogger) {
                it
            }

    override suspend fun completeAppLogin(code: String, codeVerifier: String): AuthApiResult<AuthLoginResponse> =
        transport.completeAppLogin(authJson.encodeToString(AuthAppLoginCompleteBody(code, codeVerifier)))
            .mapAuthBody("auth_app_login_complete_contract_mismatch", AuthLoginResponseDto.serializer(), diagnosticsLogger) { it.toDomain() }

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

    override suspend fun startNativeAppleMobileOAuth(
        mode: String,
        platform: String,
        appEnv: String,
        returnUri: String,
        codeChallenge: String,
        authorization: String?,
    ): AuthApiResult<AuthNativeAppleMobileOAuthStartResponse> =
        transport.startNativeAppleMobileOAuth(
            authJson.encodeToString(
                AuthNativeAppleMobileOAuthStartRequestBody(
                    mode = mode,
                    platform = platform,
                    appEnv = appEnv,
                    returnUri = returnUri,
                    codeChallenge = codeChallenge,
                    codeChallengeMethod = "S256",
                )
            ),
            authorization = authorization,
        ).mapAuthBody(
            "auth_native_apple_mobile_oauth_start_contract_mismatch",
            AuthNativeAppleMobileOAuthStartResponseDto.serializer(),
            diagnosticsLogger,
        ) {
            it.toDomain()
        }

    override suspend fun authorizeNativeAppleMobileOAuth(
        requestId: String,
        requestToken: String,
        authorizationCode: String,
        identityToken: String,
        authorization: String?,
    ): AuthApiResult<AuthNativeAppleMobileOAuthAuthorizationResponse> =
        transport.authorizeNativeAppleMobileOAuth(
            authJson.encodeToString(
                AuthNativeAppleMobileOAuthAuthorizationRequestBody(
                    requestId = requestId,
                    requestToken = requestToken,
                    authorizationCode = authorizationCode,
                    identityToken = identityToken,
                )
            ),
            authorization = authorization,
        ).mapAuthBody(
            "auth_native_apple_mobile_oauth_authorize_contract_mismatch",
            AuthNativeAppleMobileOAuthAuthorizationResponseDto.serializer(),
            diagnosticsLogger,
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

    override suspend fun register(email: String, password: String): AuthApiResult<AuthLoginResponse> =
        transport.register(authJson.encodeToString(AuthRegisterRequestBody(email = email, password = password)))
            .mapAuthBody("auth_register_contract_mismatch", AuthLoginResponseDto.serializer(), diagnosticsLogger) { it.toDomain() }

    override suspend fun completeProfile(
        authorization: String,
        name: String,
        displayName: String
    ): AuthApiResult<StoredAuthUser> =
        transport.completeProfile(
            authorization,
            authJson.encodeToString(AuthCompleteProfileRequestBody(name = name, displayName = displayName))
        ).mapAuthBody(
            "auth_complete_profile_contract_mismatch",
            StoredAuthUser.serializer(),
            diagnosticsLogger
        ) { it }

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

    override suspend fun verifyEmail(token: String): AuthApiResult<Unit> {
        val response = transport.verifyEmail(authJson.encodeToString(AuthVerifyEmailRequestBody(token = token)))
        if (!response.isSuccessful) return response.toAuthApiFailure()
        return response.mapAuthBody(
            "auth_verify_email_contract_mismatch",
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

    override suspend fun refreshWithAttempt(
        refreshToken: String,
        attempt: AuthRefreshAttemptContext,
    ): AuthApiResult<AuthRefreshResponse> =
        transport.refreshWithAttempt(
            bodyText = authJson.encodeToString(AuthRefreshRequestBody(refreshToken = refreshToken)),
            attemptId = attempt.attemptId,
            installationId = attempt.installationId,
        ).mapAuthBody("auth_refresh_contract_mismatch", AuthRefreshResponseDto.serializer(), diagnosticsLogger) {
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

    override suspend fun serverOrder(authorization: String): AuthApiResult<ServerSwitcherOrderPreference> =
        transport.serverOrder(authorization)
            .mapAuthBody("servers_order_contract_mismatch", AuthServerOrderResponseDto.serializer(), diagnosticsLogger) {
                it.toDomain()
            }

    override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
        transport.createServer(authorization, bodyText)
            .mapAuthBody("server_create_contract_mismatch", StoredAuthServer.serializer(), diagnosticsLogger) { it }

    override suspend fun consumeQaBootstrap(request: QaBootstrapConsumeRequest): AuthApiResult<QaBootstrapConsumeResponse> =
        transport.consumeQaBootstrap(authJson.encodeToString(request))
            .mapAuthBody("qa_bootstrap_consume_contract_mismatch", QaBootstrapConsumeResponse.serializer(), diagnosticsLogger) { it }

    override suspend fun readyQaBootstrap(
        ticketDigest: String,
        authorization: String,
        sessionGeneration: String
    ): AuthApiResult<QaBootstrapReadyResponse> =
        transport.readyQaBootstrap(
            ticketDigest,
            authorization,
            authJson.encodeToString(QaBootstrapReadyRequest(sessionGeneration))
        ).mapAuthBody("qa_bootstrap_ready_contract_mismatch", QaBootstrapReadyResponse.serializer(), diagnosticsLogger) { it }

    override suspend fun statusQaBootstrap(
        ticketDigest: String,
        authorization: String
    ): AuthApiResult<QaBootstrapStatusResponse> =
        transport.statusQaBootstrap(ticketDigest, authorization)
            .mapAuthBody("qa_bootstrap_status_contract_mismatch", QaBootstrapStatusResponse.serializer(), diagnosticsLogger) { it }
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

internal const val QA_BOOTSTRAP_LOGIN_PROTOCOL = "qa-bootstrap-login/v1"

@Serializable
private data class AuthSocialProviderEntryDto(
    val id: String,
    val enabled: Boolean = false
)

@Serializable
private data class AuthSocialProvidersDto(
    val providers: List<AuthSocialProviderEntryDto> = emptyList()
)

@Serializable
data class QaBootstrapLoginEnvelope(
    val version: String,
    @kotlinx.serialization.SerialName("run_case_id") val runCaseId: String,
    val attempt: Int,
    @kotlinx.serialization.SerialName("ci_job_ref") val ciJobRef: String,
    @kotlinx.serialization.SerialName("principal_id") val principalId: String,
    @kotlinx.serialization.SerialName("server_id") val serverId: String,
    @kotlinx.serialization.SerialName("target_route") val targetRoute: String,
    @kotlinx.serialization.SerialName("application_id") val applicationId: String,
    @kotlinx.serialization.SerialName("signing_cert_sha256") val signingCertSha256: String,
    @kotlinx.serialization.SerialName("boot_generation") val bootGeneration: String,
    val user: StoredAuthUser,
    @kotlinx.serialization.SerialName("access_token") val accessToken: String,
    @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class QaBootstrapConsumeRequest(
    val ticket: String,
    val applicationId: String,
    val signingCertSha256: String,
    val deviceBootGeneration: String
)

@Serializable
data class QaBootstrapBinding(
    val ticketDigest: String,
    val runCaseId: String,
    val attempt: Int,
    val ciJobRef: String,
    val principalId: String,
    val serverId: String,
    val sessionGeneration: String,
    val targetRoute: String,
    val consumedAt: String
)

@Serializable
data class QaBootstrapConsumeResponse(
    val user: StoredAuthUser,
    val accessToken: String,
    val refreshToken: String,
    val bootstrap: QaBootstrapBinding
)

@Serializable
private data class QaBootstrapReadyRequest(val sessionGeneration: String)

@Serializable
data class QaBootstrapStableReceipt(
    val version: String,
    val ticketDigest: String,
    val runCaseId: String,
    val attempt: Int,
    val ciJobRef: String,
    val principalId: String,
    val serverId: String,
    val sessionGeneration: String,
    val targetRoute: String,
    val applicationId: String,
    val signingCertSha256: String,
    val deviceBootGeneration: String,
    val serverReadyVersion: Int,
    val readyAtServer: String
)

@Serializable
data class QaBootstrapReadyResponse(
    val receiptId: String,
    val receiptDigest: String,
    val receipt: QaBootstrapStableReceipt
)

@Serializable
data class QaBootstrapStatusResponse(
    val status: String,
    val receiptId: String? = null,
    val receiptDigest: String? = null,
    val receipt: QaBootstrapStableReceipt? = null
)

private fun QaBootstrapStatusResponse.readyResponseOrNull(): QaBootstrapReadyResponse? {
    val id = receiptId ?: return null
    val digest = receiptDigest ?: return null
    val stable = receipt ?: return null
    return QaBootstrapReadyResponse(id, digest, stable)
}

private fun QaBootstrapReadyResponse.matchesFrozenBinding(metadata: StoredQaBootstrapMetadata): Boolean =
    receiptId.isNotBlank() &&
        receiptDigest.isNotBlank() &&
        (if (metadata.protocol == QA_BOOTSTRAP_LOGIN_PROTOCOL) {
             receipt.version == "qa-bootstrap-login-ready/v1" && receipt.serverReadyVersion == 0
         } else {
             receipt.version == "qa-bootstrap-ready/v1" && receipt.serverReadyVersion == 1
         }) &&
        receipt.readyAtServer.isNotBlank() &&
        receipt.ticketDigest == metadata.ticketDigest &&
        receipt.runCaseId == metadata.runCaseId &&
        receipt.attempt == metadata.attempt &&
        receipt.ciJobRef == metadata.ciJobRef &&
        receipt.principalId == metadata.principalId &&
        receipt.serverId == metadata.serverId &&
        receipt.sessionGeneration == metadata.sessionGeneration &&
        receipt.targetRoute == metadata.targetRoute &&
        receipt.applicationId == metadata.applicationId &&
        receipt.signingCertSha256 == metadata.signingCertSha256 &&
        receipt.deviceBootGeneration == metadata.deviceBootGeneration

private fun StoredQaBootstrapMetadata.localQaLoginReadyResponse(): QaBootstrapReadyResponse =
    QaBootstrapReadyResponse(
        receiptId = ticketDigest,
        receiptDigest = ticketDigest,
        receipt =
            QaBootstrapStableReceipt(
                version = "qa-bootstrap-login-ready/v1",
                ticketDigest = ticketDigest,
                runCaseId = runCaseId,
                attempt = attempt,
                ciJobRef = ciJobRef,
                principalId = principalId,
                serverId = serverId,
                sessionGeneration = sessionGeneration,
                targetRoute = targetRoute,
                applicationId = applicationId,
                signingCertSha256 = signingCertSha256,
                deviceBootGeneration = deviceBootGeneration,
                serverReadyVersion = 0,
                readyAtServer = "not-applicable",
            ),
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

data class AuthNativeAppleMobileOAuthStartResponse(
    val requestId: String,
    val requestToken: String,
    val returnUri: String,
    val expiresAt: String,
)

data class AuthNativeAppleMobileOAuthAuthorizationResponse(
    val requestId: String,
    val handoffCode: String,
    val provider: String,
    val mode: String,
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

data class AuthRefreshAttemptContext(
    val attemptId: String,
    val installationId: String,
)

data class ServerSwitcherOrderPreference(
    val serverOrder: List<String>
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

sealed interface SharedEmailVerificationResult {
    data class Verified(val session: StoredAuthSession) : SharedEmailVerificationResult
    data object LoginRequired : SharedEmailVerificationResult
    data class RefreshPending(val message: String) : SharedEmailVerificationResult
    data class Failure(
        val code: SharedAuthErrorCode,
        val message: String
    ) : SharedEmailVerificationResult
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
data class AuthNativeAppleMobileOAuthStartRequestBody(
    val mode: String,
    val platform: String,
    val appEnv: String,
    val returnUri: String,
    val codeChallenge: String,
    val codeChallengeMethod: String,
)

@kotlinx.serialization.Serializable
data class AuthNativeAppleMobileOAuthAuthorizationRequestBody(
    val requestId: String,
    val requestToken: String,
    val authorizationCode: String,
    val identityToken: String,
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
    val acceptTerms: Boolean = true,
    val termsVersion: String = CURRENT_TERMS_VERSION,
    val privacyVersion: String = CURRENT_PRIVACY_VERSION,
    val legalAcceptanceSource: String = "signup"
)

@kotlinx.serialization.Serializable
data class AuthCompleteProfileRequestBody(
    val name: String,
    val displayName: String
)

@kotlinx.serialization.Serializable
data class AuthForgotPasswordRequestBody(
    val email: String
)

@kotlinx.serialization.Serializable
private data class AuthVerifyEmailRequestBody(
    val token: String
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
private data class AuthNativeAppleMobileOAuthStartResponseDto(
    val requestId: String,
    val requestToken: String,
    val returnUri: String,
    val expiresAt: String,
)

private fun AuthNativeAppleMobileOAuthStartResponseDto.toDomain(): AuthNativeAppleMobileOAuthStartResponse =
    AuthNativeAppleMobileOAuthStartResponse(
        requestId = requestId,
        requestToken = requestToken,
        returnUri = returnUri,
        expiresAt = expiresAt,
    )

@Serializable
private data class AuthNativeAppleMobileOAuthAuthorizationResponseDto(
    val requestId: String,
    val handoffCode: String,
    val provider: String,
    val mode: String,
)

private fun AuthNativeAppleMobileOAuthAuthorizationResponseDto.toDomain(): AuthNativeAppleMobileOAuthAuthorizationResponse =
    AuthNativeAppleMobileOAuthAuthorizationResponse(
        requestId = requestId,
        handoffCode = handoffCode,
        provider = provider,
        mode = mode,
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
private data class AuthServerOrderResponseDto(
    val serverOrder: List<String> = emptyList()
)

private fun AuthServerOrderResponseDto.toDomain(): ServerSwitcherOrderPreference =
    ServerSwitcherOrderPreference(serverOrder = serverOrder)

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

private fun List<StoredAuthServer>.orderedByServerOrder(serverOrder: List<String>?): List<StoredAuthServer> {
    if (serverOrder.isNullOrEmpty() || size < 2) return this
    val byId = associateBy { it.id }
    val seen = mutableSetOf<String>()
    val ordered =
        serverOrder.mapNotNull { id ->
            byId[id]?.takeIf { seen.add(it.id) }
        }
    if (ordered.isEmpty()) return this
    return ordered + filterNot { it.id in seen }
}

@Serializable
data class AuthAppLoginStartResponse(val requestId: String, val loginUrl: String, val expiresAt: String)
@Serializable
private data class AuthAppLoginStartBody(val returnUri: String, val codeChallenge: String)
@Serializable
private data class AuthAppLoginCompleteBody(val code: String, val codeVerifier: String)
