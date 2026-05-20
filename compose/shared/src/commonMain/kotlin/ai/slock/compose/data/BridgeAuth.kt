package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.model.ServerId
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val AUTH_BASE_URL = "https://api.slock.ai/api/"
private const val SESSION_KEY = "auth_session"

private val bridgeAuthJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal class BridgeAuthApi(
    private val bridge: BridgeModule,
    private val baseUrl: String = AUTH_BASE_URL
) : SlockAuthApi {
    override suspend fun login(bodyText: String): AuthNetworkResponse =
        request(path = "auth/login", method = "POST", bodyText = bodyText)

    override suspend fun currentUser(authorization: String): AuthNetworkResponse =
        request(path = "auth/me", method = "GET", authorization = authorization)

    override suspend fun refresh(bodyText: String): AuthNetworkResponse =
        request(path = "auth/refresh", method = "POST", bodyText = bodyText)

    override suspend fun logout(bodyText: String): AuthNetworkResponse =
        request(path = "auth/logout", method = "POST", bodyText = bodyText)

    override suspend fun servers(authorization: String): AuthNetworkResponse =
        request(path = "servers", method = "GET", authorization = authorization)

    override suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse =
        request(path = "servers", method = "POST", authorization = authorization, bodyText = bodyText)

    private suspend fun request(
        path: String,
        method: String,
        authorization: String? = null,
        serverId: String? = null,
        bodyText: String? = null
    ): AuthNetworkResponse {
        val command =
            JSONObject()
                .put("url", "${baseUrl.trimEnd('/')}/${path.trimStart('/')}")
                .put("method", method)
        authorization?.let { command.put("authorization", it) }
        serverId?.let { command.put("serverId", it) }
        bodyText?.let { command.put("bodyText", it) }

        val response = bridge.authRequest(command)
        return AuthNetworkResponse(
            code = response?.optString("code").orEmpty().toIntOrNull() ?: 0,
            bodyText = response?.optString("bodyText").takeUnless { it.isNullOrBlank() },
            errorBodyText = response?.optString("errorBodyText").takeUnless { it.isNullOrBlank() }
                ?: response?.optString("message").takeUnless { it.isNullOrBlank() }
        )
    }
}

internal class BridgeHomeApi(
    private val bridge: BridgeModule,
    private val sessionStore: SlockAuthSessionStore,
    private val baseUrl: String = AUTH_BASE_URL
) : SlockHomeApi {
    override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
        request("channels")

    override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
        request("channels/dm")

    override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
        request("channels/unread")

    override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
        request("servers/unread-summary")

    override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        request("channels", serverId.value)

    override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        request("channels/dm", serverId.value)

    override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        request("channels/unread", serverId.value)

    override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        request("servers/unread-summary", serverId.value)

    private suspend fun request(path: String, serverIdOverride: String? = null): HomeNetworkResponse {
        val session = sessionStore.readSession()
        val token = session?.accessToken.orEmpty()
        val serverId = serverIdOverride ?: session?.currentServerId.orEmpty()
        if (token.isBlank() || serverId.isBlank()) return HomeNetworkResponse(code = 401, bodyText = null)
        val response =
            bridge.authRequest(
                JSONObject()
                    .put("url", "${baseUrl.trimEnd('/')}/${path.trimStart('/')}")
                    .put("method", "GET")
                    .put("authorization", "Bearer $token")
                    .put("serverId", serverId)
            )
        return HomeNetworkResponse(
            code = response?.optString("code").orEmpty().toIntOrNull() ?: 0,
            bodyText = response?.optString("bodyText").takeUnless { it.isNullOrBlank() },
            errorBodyText = response?.optString("errorBodyText").takeUnless { it.isNullOrBlank() }
                ?: response?.optString("message").takeUnless { it.isNullOrBlank() }
        )
    }
}

internal class BridgeMembersApi(
    private val bridge: BridgeModule,
    private val sessionStore: SlockAuthSessionStore,
    private val baseUrl: String = AUTH_BASE_URL
) {
    suspend fun serverMembers(serverId: String): HomeNetworkResponse =
        request("servers/$serverId/members", serverId)

    private suspend fun request(path: String, serverId: String): HomeNetworkResponse {
        val session = sessionStore.readSession()
        val token = session?.accessToken.orEmpty()
        if (token.isBlank() || serverId.isBlank()) return HomeNetworkResponse(code = 401, bodyText = null)
        val response =
            bridge.authRequest(
                JSONObject()
                    .put("url", "${baseUrl.trimEnd('/')}/${path.trimStart('/')}")
                    .put("method", "GET")
                    .put("authorization", "Bearer $token")
                    .put("serverId", serverId)
            )
        return HomeNetworkResponse(
            code = response?.optString("code").orEmpty().toIntOrNull() ?: 0,
            bodyText = response?.optString("bodyText").takeUnless { it.isNullOrBlank() },
            errorBodyText = response?.optString("errorBodyText").takeUnless { it.isNullOrBlank() }
                ?: response?.optString("message").takeUnless { it.isNullOrBlank() }
        )
    }
}

internal class BridgeThreadApi(
    private val bridge: BridgeModule,
    private val sessionStore: SlockAuthSessionStore,
    private val baseUrl: String = AUTH_BASE_URL
) {
    suspend fun channelMessages(threadId: String, limit: Int = 50): HomeNetworkResponse =
        request("messages/channel/${threadId.trim()}", "limit=$limit")

    suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean = false
    ): HomeNetworkResponse =
        request(
            path = "messages",
            method = "POST",
            bodyText = buildSharedThreadSendMessageBody(
                channelId = channelId,
                body = body,
                attachmentIds = emptyList(),
                asTask = asTask
            )
        )

    suspend fun attachmentUrl(attachmentId: String): HomeNetworkResponse =
        request("attachments/${attachmentId.trim()}/url")

    private suspend fun request(
        path: String,
        query: String? = null,
        method: String = "GET",
        bodyText: String? = null
    ): HomeNetworkResponse {
        val session = sessionStore.readSession()
        val token = session?.accessToken.orEmpty()
        val serverId = session?.currentServerId.orEmpty()
        if (token.isBlank() || serverId.isBlank()) return HomeNetworkResponse(code = 401, bodyText = null)
        val url =
            buildString {
                append(baseUrl.trimEnd('/'))
                append('/')
                append(path.trimStart('/'))
                query?.takeIf { it.isNotBlank() }?.let {
                    append('?')
                    append(it)
                }
            }
        val response =
            bridge.authRequest(
                JSONObject()
                    .put("url", url)
                    .put("method", method)
                    .put("authorization", "Bearer $token")
                    .put("serverId", serverId)
                    .also { command ->
                        bodyText?.let { command.put("bodyText", it) }
                    }
            )
        return HomeNetworkResponse(
            code = response?.optString("code").orEmpty().toIntOrNull() ?: 0,
            bodyText = response?.optString("bodyText").takeUnless { it.isNullOrBlank() },
            errorBodyText = response?.optString("errorBodyText").takeUnless { it.isNullOrBlank() }
                ?: response?.optString("message").takeUnless { it.isNullOrBlank() }
        )
    }
}

@Serializable
private data class SharedThreadSendMessageBody(
    val channelId: String,
    val content: String,
    val attachmentIds: List<String> = emptyList(),
    val asTask: Boolean = false
)

internal fun buildSharedThreadSendMessageBody(
    channelId: String,
    body: String,
    attachmentIds: List<String> = emptyList(),
    asTask: Boolean = false
): String =
    bridgeAuthJson.encodeToString(
        SharedThreadSendMessageBody(
            channelId = channelId,
            content = body,
            attachmentIds = attachmentIds,
            asTask = asTask
        )
    )

internal class KuiklyAuthSessionStore(
    private val store: SlockRelationalStore
) : SlockAuthSessionStore {
    override suspend fun readSession(): StoredAuthSession? {
        ensureTable()
        val rows = store.query("SELECT value FROM slock_kv WHERE key = ?", listOf(RelationalValue.Text(SESSION_KEY)))
        val encoded = rows.rows.firstOrNull()?.firstOrNull()?.asText() ?: return null
        return runCatching {
            bridgeAuthJson.decodeFromString(StoredAuthSession.serializer(), encoded)
        }.getOrNull()
    }

    override suspend fun saveSession(session: StoredAuthSession) {
        ensureTable()
        val encoded = bridgeAuthJson.encodeToString(StoredAuthSession.serializer(), session)
        store.execute(
            "INSERT OR REPLACE INTO slock_kv(key, value) VALUES(?, ?)",
            listOf(RelationalValue.Text(SESSION_KEY), RelationalValue.Text(encoded))
        )
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val current = readSession() ?: return
        saveSession(current.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
        val current = readSession() ?: return
        saveSession(current.copy(selectedServerId = serverId, selectedServerSlug = serverSlug))
    }

    override suspend fun clearSession() {
        ensureTable()
        store.execute("DELETE FROM slock_kv WHERE key = ?", listOf(RelationalValue.Text(SESSION_KEY)))
    }

    private suspend fun ensureTable() {
        store.execute(
            "CREATE TABLE IF NOT EXISTS slock_kv(" +
                "key TEXT PRIMARY KEY NOT NULL, " +
                "value TEXT NOT NULL" +
                ")"
        )
    }
}

private fun RelationalValue.asText(): String? =
    when (this) {
        is RelationalValue.Text -> value
        is RelationalValue.LongNumber -> value.toString()
        is RelationalValue.DoubleNumber -> value.toString()
        is RelationalValue.BooleanValue -> value.toString()
        RelationalValue.Null -> null
    }
