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
    suspend fun login(email: String, password: String): SharedAuthResult<StoredAuthSession> {
        if (email.isBlank() || password.isBlank()) {
            return SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Email and password are required."
            )
        }

        val loginResponse =
            api.login(authJson.encodeToString(LoginRequestPayload(email = email.trim(), password = password)))
        if (!loginResponse.isSuccessful) return loginResponse.toAuthFailure()

        val login = loginResponse.bodyText?.parseJsonElementOrNull()?.toLoginPayload()
            ?: return SharedAuthResult.Failure(
                SharedAuthErrorCode.Unknown,
                "Sign in failed. Try again."
            )
        val servers =
            api.servers("Bearer ${login.accessToken}")
                .takeIf { it.isSuccessful }
                ?.bodyText
                ?.parseJsonElementOrNull()
                ?.toStoredServers()
                .orEmpty()
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
        val response =
            api.refresh(authJson.encodeToString(RefreshRequestPayload(refreshToken = refreshToken)))
        if (response.code == 401) {
            sessionStore.clearSession()
            return SharedAuthResult.Unauthenticated
        }
        if (!response.isSuccessful) return response.toAuthFailure()
        val tokens = response.bodyText?.parseJsonElementOrNull()?.toRefreshPayload()
            ?: return SharedAuthResult.Failure(
                SharedAuthErrorCode.Unknown,
                "Could not refresh your session."
            )
        val session = current.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
        sessionStore.saveSession(session)
        return SharedAuthResult.Success(session)
    }

    suspend fun loadServers(): SharedAuthResult<StoredAuthSession> {
        val session = sessionStore.readSession() ?: return SharedAuthResult.Unauthenticated
        val response = api.servers(session.bearer())
        if (response.code == 401) {
            sessionStore.clearSession()
            return SharedAuthResult.Unauthenticated
        }
        if (!response.isSuccessful) return response.toAuthFailure()
        val servers = response.bodyText?.parseJsonElementOrNull()?.toStoredServers().orEmpty()
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
        val response = api.createServer(session.bearer(), body)
        if (response.code == 401) {
            sessionStore.clearSession()
            return SharedAuthResult.Unauthenticated
        }
        if (!response.isSuccessful) return response.toAuthFailure()
        val created = response.bodyText?.parseJsonElementOrNull()?.toCreatedServer()
            ?: return SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "Could not create server.")
        val latestServers =
            api.servers(session.bearer())
                .takeIf { it.isSuccessful }
                ?.bodyText
                ?.parseJsonElementOrNull()
                ?.toStoredServers()
                .orEmpty()
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
                api.logout(authJson.encodeToString(LogoutRequestPayload(refreshToken = session.refreshToken)))
            }
        }
        sessionStore.clearSession()
        return SharedAuthResult.Success(Unit)
    }

    private suspend fun currentUserWithSession(
        session: StoredAuthSession,
        clearOnUnauthorized: Boolean
    ): SharedAuthResult<StoredAuthSession> {
        val response = api.currentUser(session.bearer())
        if (response.code == 401) {
            if (clearOnUnauthorized) sessionStore.clearSession()
            return SharedAuthResult.Unauthenticated
        }
        if (!response.isSuccessful) return response.toAuthFailure()
        val user = response.bodyText?.parseJsonElementOrNull()?.toStoredUserFromRoot()
            ?: return SharedAuthResult.Failure(SharedAuthErrorCode.Unknown, "Could not restore your session.")
        val servers =
            api.servers(session.bearer())
                .takeIf { it.isSuccessful }
                ?.bodyText
                ?.parseJsonElementOrNull()
                ?.toStoredServers()
                ?: session.servers
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
    suspend fun login(bodyText: String): AuthNetworkResponse
    suspend fun currentUser(authorization: String): AuthNetworkResponse
    suspend fun refresh(bodyText: String): AuthNetworkResponse
    suspend fun logout(bodyText: String): AuthNetworkResponse
    suspend fun servers(authorization: String): AuthNetworkResponse
    suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse
}

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

private data class LoginPayload(
    val user: StoredAuthUser,
    val accessToken: String,
    val refreshToken: String
)

private data class RefreshPayload(
    val accessToken: String,
    val refreshToken: String
)

@kotlinx.serialization.Serializable
private data class RefreshRequestPayload(
    val refreshToken: String
)

@kotlinx.serialization.Serializable
private data class LogoutRequestPayload(
    val refreshToken: String
)

private fun AuthNetworkResponse.toAuthFailure(): SharedAuthResult.Failure {
    val message = errorBodyText?.parseJsonElementOrNull()?.asObjectOrNull()?.string("error", "message")
    return when (code) {
        0 ->
            SharedAuthResult.Failure(
                SharedAuthErrorCode.Network,
                message ?: "Network unavailable. Check your connection."
            )
        400 ->
            SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                message ?: "Check the fields and try again."
            )
        401 ->
            SharedAuthResult.Failure(
                SharedAuthErrorCode.InvalidCredentials,
                message ?: "Email or password is incorrect."
            )
        in 500..599 ->
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

private fun JsonElement.toLoginPayload(): LoginPayload? {
    val root = asObjectOrNull() ?: return null
    val userRoot = root.objectOrNull("user") ?: root.objectOrNull("account") ?: root.objectOrNull("profile") ?: root
    val accessToken = root.string("accessToken", "access_token", "token") ?: return null
    val refreshToken = root.string("refreshToken", "refresh_token") ?: return null
    val user = userRoot.toStoredUser() ?: return null
    return LoginPayload(user = user, accessToken = accessToken, refreshToken = refreshToken)
}

private fun JsonElement.toRefreshPayload(): RefreshPayload? {
    val root = asObjectOrNull() ?: return null
    val accessToken = root.string("accessToken", "access_token", "token") ?: return null
    val refreshToken = root.string("refreshToken", "refresh_token") ?: return null
    return RefreshPayload(accessToken = accessToken, refreshToken = refreshToken)
}

private fun JsonElement.toStoredUserFromRoot(): StoredAuthUser? {
    val root = asObjectOrNull() ?: return null
    val userRoot = root.objectOrNull("user")
        ?: root.objectOrNull("account")
        ?: root.objectOrNull("profile")
        ?: root
    return userRoot.toStoredUser()
}

private fun JsonElement.toStoredServers(): List<StoredAuthServer> {
    val array =
        when (this) {
            is JsonArray -> this
            is JsonObject ->
                arrayOrNull("servers", "items", "data")
                    ?: objectOrNull("data")?.arrayOrNull("servers", "items")
                    ?: return emptyList()
            else -> return emptyList()
        }
    return array.mapNotNull { it.asObjectOrNull()?.toStoredServer() }
}

private fun JsonElement.toCreatedServer(): StoredAuthServer? {
    val root = asObjectOrNull() ?: return null
    val serverRoot =
        root.objectOrNull("server")
            ?: root.objectOrNull("item")
            ?: root.objectOrNull("data")
            ?: root.objectOrNull("workspace")
            ?: root
    return serverRoot.toStoredServer()
}

private fun JsonObject.toStoredUser(): StoredAuthUser? {
    val id = string("id", "userId", "_id") ?: return null
    val email = string("email", "mail") ?: ""
    return StoredAuthUser(
        id = id,
        email = email,
        name = string("name"),
        displayName = string("displayName", "display_name", "username"),
        description = string("description", "bio"),
        avatarUrl = string("avatarUrl", "avatar", "avatar_url", "imageUrl", "profileImageUrl", "profileAvatar"),
        gravatarHash = string("gravatarHash", "gravatar", "gravatar_hash", "gravatarMd5", "gravatar_md5"),
        emailVerified = boolean("emailVerified", "email_verified")
    )
}

private fun JsonObject.toStoredServer(): StoredAuthServer? {
    val id = string("id", "serverId", "_id") ?: return null
    val name = string("name", "serverName", "title") ?: id
    return StoredAuthServer(
        id = id,
        name = name,
        slug = string("slug", "serverSlug"),
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

private fun JsonObject.arrayOrNull(vararg names: String): JsonArray? {
    for (name in names) {
        val value = this[name]
        if (value is JsonArray) return value
    }
    return null
}

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
