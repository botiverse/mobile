package ai.slock.compose.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private val authJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class SharedAuthDataSource(
    private val api: SlockAuthApi,
    private val sessionStore: SlockAuthSessionStore
) {
    constructor(
        transport: SlockAuthTransport,
        sessionStore: SlockAuthSessionStore
    ) : this(api = SlockAuthApiClient(transport), sessionStore = sessionStore)

    suspend fun login(email: String, password: String): SharedAuthResult<StoredAuthSession> {
        if (email.isBlank() || password.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Email and password are required."
            )
        }

        val login =
            when (val response = api.login(email = email.trim(), password = password)) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> return response.toSharedAuthFailure()
            }
        val servers =
            when (val response = api.servers("Bearer ${login.accessToken}")) {
                is AuthApiResult.Success -> response.data
                is AuthApiResult.Failure -> emptyList()
            }
        val selectedServer = servers.firstOrNull()
        val session =
            StoredAuthSession(
                user = login.user,
                accessToken = login.accessToken,
                refreshToken = login.refreshToken,
                selectedServerId = selectedServer?.id,
                selectedServerSlug = selectedServer?.slug,
                servers = servers
            )
        sessionStore.saveSession(session)
        return SharedAuthResult.Success(session)
    }

    suspend fun currentSession(): SharedAuthResult<StoredAuthSession> =
        sessionStore.readSession()?.let { SharedAuthResult.Success(it) }
            ?: SharedAuthResult.Unauthenticated

    suspend fun restoreSession(): SharedAuthResult<StoredAuthSession> {
        val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        return when (val current = currentUserWithSession(session, clearOnUnauthorized = false)) {
            is SharedAuthResult.Success -> current
            SharedAuthResult.Unauthenticated -> {
                when (val refreshed = refresh(session.refreshToken)) {
                    is SharedAuthResult.Success -> {
                        val updated = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
                        currentUserWithSession(updated, clearOnUnauthorized = true)
                    }
                    is SharedAuthResult.Failure ->
                        if (refreshed.code == SharedAuthErrorCode.Network) {
                            SharedAuthResult.Success(session)
                        } else {
                            refreshed
                        }
                    SharedAuthResult.Unauthenticated -> SharedAuthResult.Unauthenticated
                }
            }
            is SharedAuthResult.Failure ->
                if (current.code == SharedAuthErrorCode.Network) {
                    SharedAuthResult.Success(session)
                } else {
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
        val selected = session.selectedServerId?.let { selectedId -> servers.firstOrNull { it.id == selectedId } }
        val updated =
            session.copy(
                servers = servers,
                selectedServerId = selected?.id ?: session.selectedServerId,
                selectedServerSlug = selected?.slug ?: session.selectedServerSlug
            )
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
            buildJsonObject {
                put("name", normalizedName)
                slug?.trim()?.takeIf { it.isNotBlank() }?.let { put("slug", it) }
                region?.trim()?.takeIf { it.isNotBlank() }?.let { put("region", it) }
            }.toString()
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
            runCatching {
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
        val selected = session.selectedServerId?.let { selectedId -> servers.firstOrNull { it.id == selectedId } }
        val updated =
            session.copy(
                user = user,
                servers = servers,
                selectedServerId = selected?.id ?: session.selectedServerId,
                selectedServerSlug = selected?.slug ?: session.selectedServerSlug
            )
        sessionStore.saveSession(updated)
        return SharedAuthResult.Success(updated)
    }
}

interface SlockAuthApi {
    suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse>
    suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser>
    suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse>
    suspend fun logout(refreshToken: String): AuthApiResult<Unit>
    suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>>
    suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer>
}

class SlockAuthApiClient(
    private val transport: SlockAuthTransport
) : SlockAuthApi {
    override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> {
        val response = transport.login(authJson.encodeToString(LoginRequestPayload(email = email, password = password)))
        return response.mapAuthBody("auth_login_contract_mismatch") { element ->
            val root = element.asObjectOrNull() ?: return@mapAuthBody null
            val user = (root.objectOrNull("user") ?: return@mapAuthBody null).toStoredUser() ?: return@mapAuthBody null
            val accessToken = root.string("accessToken") ?: return@mapAuthBody null
            val refreshToken = root.string("refreshToken") ?: return@mapAuthBody null
            AuthLoginResponse(user = user, accessToken = accessToken, refreshToken = refreshToken)
        }
    }

    override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
        transport.currentUser(authorization).mapAuthBody("auth_me_contract_mismatch") { element ->
            element.asObjectOrNull()?.toStoredUser()
        }

    override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> {
        val response = transport.refresh(authJson.encodeToString(RefreshRequestPayload(refreshToken = refreshToken)))
        return response.mapAuthBody("auth_refresh_contract_mismatch") { element ->
            val root = element.asObjectOrNull() ?: return@mapAuthBody null
            val accessToken = root.string("accessToken") ?: return@mapAuthBody null
            val nextRefreshToken = root.string("refreshToken") ?: return@mapAuthBody null
            AuthRefreshResponse(accessToken = accessToken, refreshToken = nextRefreshToken)
        }
    }

    override suspend fun logout(refreshToken: String): AuthApiResult<Unit> {
        val response = transport.logout(authJson.encodeToString(LogoutRequestPayload(refreshToken = refreshToken)))
        if (!response.isSuccessful) return response.toAuthApiFailure()
        return AuthApiResult.Success(Unit)
    }

    override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
        transport.servers(authorization).mapAuthBody("servers_list_contract_mismatch") { element ->
            (element as? JsonArray)?.mapNotNull { it.asObjectOrNull()?.toStoredServer() }
        }

    override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
        transport.createServer(authorization, bodyText).mapAuthBody("server_create_contract_mismatch") { element ->
            element.asObjectOrNull()?.toStoredServer()
        }
}

interface SlockAuthTransport {
    suspend fun login(bodyText: String): AuthNetworkResponse
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
private data class LoginRequestPayload(
    val email: String,
    val password: String
)

@kotlinx.serialization.Serializable
private data class RefreshRequestPayload(
    val refreshToken: String
)

@kotlinx.serialization.Serializable
private data class LogoutRequestPayload(
    val refreshToken: String
)

private fun AuthApiResult.Failure.toSharedAuthFailure(): SharedAuthResult.Failure {
    val message = errorBodyText?.parseJsonElementOrNull()?.asObjectOrNull()?.string("error", "message")
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
                message ?: "Slock is unavailable. Try again soon."
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

private inline fun <T> AuthNetworkResponse.mapAuthBody(
    contractCode: String,
    mapper: (JsonElement) -> T?
): AuthApiResult<T> {
    if (!isSuccessful) return toAuthApiFailure()
    val element = bodyText?.parseJsonElementOrNull()
        ?: return AuthApiResult.Failure(code = code, contractCode = contractCode)
    val value = mapper(element)
        ?: return AuthApiResult.Failure(code = code, contractCode = contractCode)
    return AuthApiResult.Success(value)
}

private fun JsonObject.toStoredUser(): StoredAuthUser? {
    val id = string("id") ?: return null
    val email = string("email", "mail") ?: ""
    return StoredAuthUser(
        id = id,
        email = email,
        name = string("name"),
        displayName = string("displayName"),
        description = string("description", "bio"),
        avatarUrl = string("avatarUrl"),
        gravatarHash = string("gravatarHash"),
        emailVerified = boolean("emailVerified")
    )
}

private fun JsonObject.toStoredServer(): StoredAuthServer? {
    val id = string("id") ?: return null
    val name = string("name") ?: id
    return StoredAuthServer(
        id = id,
        name = name,
        slug = string("slug"),
        ownerId = string("ownerId", "owner_id"),
        onboardingAgentId = string("onboardingAgentId", "onboarding_agent_id"),
        plan = string("plan"),
        planDowngradedAt = string("planDowngradedAt", "plan_downgraded_at"),
        role = string("role"),
        createdAt = string("createdAt", "created_at")
    )
}

private fun String.parseJsonElementOrNull(): JsonElement? =
    runCatching { authJson.parseToJsonElement(this) }.getOrNull()

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.string(vararg names: String): String? {
    for (name in names) {
        val primitive = this[name] as? JsonPrimitive ?: continue
        primitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

private fun JsonObject.boolean(vararg names: String): Boolean? {
    for (name in names) {
        val primitive = this[name] as? JsonPrimitive ?: continue
        primitive.booleanOrNull?.let { return it }
        primitive.contentOrNull?.lowercase()?.let { value ->
            when (value) {
                "true", "1", "yes" -> return true
                "false", "0", "no" -> return false
            }
        }
    }
    return null
}

private fun StoredAuthSession.bearer(): String = "Bearer $accessToken"

private fun List<StoredAuthServer>.upsert(server: StoredAuthServer): List<StoredAuthServer> =
    filterNot { it.id == server.id } + server
