package ai.slock.android.core.data.auth

import ai.slock.android.core.data.network.ApiResponse
import ai.slock.android.core.data.repository.ChannelSnapshot
import ai.slock.android.core.data.repository.CreateServerRequest
import ai.slock.compose.data.DataResult
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.InboxSnapshot
import ai.slock.android.core.data.repository.ServerListSnapshot
import ai.slock.android.core.data.repository.ServerRepository
import ai.slock.android.core.data.repository.SettingsSnapshot
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenError
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class AuthServerRepository(
    private val store: AuthSessionStore,
    private val authRepository: AuthRepository,
    private val api: AuthApi = KtorAuthRepository.createAuthApi()
) : ServerRepository {
    private val gson = Gson()

    override suspend fun getServers(): DataResult<ServerListSnapshot> {
        val session = store.readSession() ?: return authRequired()
        val restored = authRepository.currentUser()
        val latest = store.readSession() ?: session

        if (latest.servers.isEmpty()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        if (restored is AuthResult.Failure && restored.code == AuthErrorCode.Network) {
            return DataResult.Error(ScreenError(code = "servers_network", retryable = true))
        }

        return DataResult.Content(latest.toServerListSnapshot())
    }

    override suspend fun selectServer(serverId: ServerId): DataResult<ServerListSnapshot> {
        val session = store.readSession() ?: return authRequired()
        val selected = session.servers.firstOrNull { it.id == serverId.value }
            ?: return DataResult.Error(ScreenError(code = "server_not_found", retryable = false))

        store.updateSelectedServer(selected.id, selected.slug)
        val updated = store.readSession() ?: session.copy(
            selectedServerId = selected.id,
            selectedServerSlug = selected.slug
        )
        return DataResult.Content(updated.toServerListSnapshot())
    }

    override suspend fun createServer(request: CreateServerRequest): DataResult<ServerListSnapshot> {
        val session = store.readSession() ?: return authRequired()
        val name = request.name.trim()
        if (name.isBlank()) {
            return DataResult.Error(ScreenError(code = "server_name_required", retryable = false))
        }

        return try {
            val authorization = session.bearer()
            val createdServer = api.createServer(
                authorization = authorization,
                body = jsonBody(
                    mapOf(
                        "name" to name,
                        "slug" to request.slug?.trim().orEmpty().ifBlank { null },
                        "region" to request.region?.trim().orEmpty().ifBlank { null }
                    ).filterValues { it != null }
                )
            ).parseCreatedServer()
            val latestServers = runCatching { api.servers(authorization).parseAuthServers() }
                .getOrElse { session.servers.upsert(createdServer) }
                .upsert(createdServer)
            val selected = latestServers.firstOrNull { it.id == createdServer.id } ?: createdServer
            val updated = session.copy(
                servers = latestServers,
                selectedServerId = selected.id,
                selectedServerSlug = selected.slug
            )
            store.saveSession(updated)
            DataResult.Content(updated.toServerListSnapshot())
        } catch (error: ServerHttpException) {
            when (error.code) {
                400 -> DataResult.Error(ScreenError(code = "server_validation", retryable = false))
                401 -> authRequired()
                403 -> DataResult.NoPermission(PermissionBlocker(code = "server_create_forbidden"))
                409 -> DataResult.Error(ScreenError(code = "server_duplicate", retryable = false))
                in 500..599 -> DataResult.Error(ScreenError(code = "server_create_unavailable", retryable = true))
                else -> DataResult.Error(ScreenError(code = "server_create_failed", retryable = true))
            }
        } catch (_: IOException) {
            DataResult.Error(ScreenError(code = "server_create_network", retryable = true))
        } catch (_: Exception) {
            DataResult.Error(ScreenError(code = "server_create_failed", retryable = true))
        }
    }

    override suspend fun getHomeSnapshot(serverId: ServerId): DataResult<HomeSnapshot> =
        DataResult.Error(ScreenError(code = "server_home_not_wired", retryable = false))

    override suspend fun getInbox(serverId: ServerId): DataResult<InboxSnapshot> =
        DataResult.Error(ScreenError(code = "server_inbox_not_wired", retryable = false))

    override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> =
        DataResult.Error(ScreenError(code = "server_channels_not_wired", retryable = false))

    override suspend fun getSettings(serverId: ServerId): DataResult<SettingsSnapshot> =
        DataResult.Error(ScreenError(code = "server_settings_not_wired", retryable = false))

    private fun AuthSession.toServerListSnapshot(): ServerListSnapshot {
        val summaries = servers.map { it.toServerSummary() }
        return ServerListSnapshot(
            servers = summaries,
            selectedServer = summaries.firstOrNull { it.id.value == selectedServerId }
        )
    }

    private fun AuthSession.bearer(): String = "Bearer $accessToken"

    private fun List<AuthServer>.upsert(server: AuthServer): List<AuthServer> =
        (this.filterNot { it.id == server.id } + server)

    private fun ApiResponse.parseCreatedServer(): AuthServer {
        val root = parseJsonRoot()
        val serverJson = if (root.isJsonObject) {
            val objectRoot = root.asJsonObject
            objectRoot.objectValue("server", "item", "data", "workspace") ?: objectRoot
        } else {
            root
        }
        return gson.fromJson(serverJson, AuthServer::class.java)
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
            throw ServerHttpException(code())
        }
        val rawBody = body()?.string().orEmpty()
        if (rawBody.isBlank()) throw ServerHttpException(code())
        return gson.fromJson(rawBody, JsonElement::class.java)
    }

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

    private fun <T> authRequired(): DataResult<T> =
        DataResult.NoPermission(
            PermissionBlocker(
                code = "auth_required",
                requiredCapability = "auth_session"
            )
        )

    private class ServerHttpException(val code: Int) : Exception()

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
