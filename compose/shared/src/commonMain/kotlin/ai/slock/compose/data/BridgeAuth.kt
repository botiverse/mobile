package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.flow.asSharedFlow
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
) : SlockAuthTransport {
    override suspend fun login(bodyText: String): AuthNetworkResponse =
        request(path = "auth/login", method = "POST", bodyText = bodyText)

    override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse =
        request(path = "auth/${provider.trim().lowercase()}/complete", method = "POST", bodyText = bodyText)

    override suspend fun register(bodyText: String): AuthNetworkResponse =
        request(path = "auth/register", method = "POST", bodyText = bodyText)

    override suspend fun forgotPassword(bodyText: String): AuthNetworkResponse =
        request(path = "auth/forgot-password", method = "POST", bodyText = bodyText)

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

internal class BridgeAuthenticatedRequestExecutor(
    private val sessionStore: SlockAuthSessionStore,
    private val authApi: SlockAuthApi,
    private val request: suspend (BridgeAuthenticatedRequest) -> HomeNetworkResponse,
    private val baseUrl: String = AUTH_BASE_URL
) {
    constructor(
        bridge: BridgeModule,
        sessionStore: SlockAuthSessionStore,
        baseUrl: String = AUTH_BASE_URL
    ) : this(
        sessionStore = sessionStore,
        authApi = SlockAuthApiClient(BridgeAuthApi(bridge, baseUrl)),
        request = { command ->
            bridge.authRequest(command.toBridgeCommand()).toHomeNetworkResponse()
        },
        baseUrl = baseUrl
    )

    suspend fun request(
        path: String,
        query: String? = null,
        serverIdOverride: String? = null,
        method: String = "GET",
        bodyText: String? = null
    ): HomeNetworkResponse {
        val session = sessionStore.readSession()
            ?: return HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = SESSION_EXPIRED_ERROR)
        val serverId = serverIdOverride ?: session.currentServerId.orEmpty()
        if (session.accessToken.isBlank() || serverId.isBlank()) {
            return HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = SESSION_EXPIRED_ERROR)
        }
        val first = request(buildRequest(path, query, serverId, session.accessToken, method, bodyText))
        if (!first.requiresTokenRefresh()) return first

        val refreshed = refreshSession(session)
        if (refreshed == null) {
            sessionStore.clearSession()
            return first.asSessionExpired()
        }
        val retry = request(buildRequest(path, query, serverId, refreshed.accessToken, method, bodyText))
        if (retry.requiresTokenRefresh()) {
            sessionStore.clearSession()
            return retry.asSessionExpired()
        }
        return retry
    }

    private suspend fun refreshSession(session: StoredAuthSession): StoredAuthSession? {
        val refreshToken = session.refreshToken.takeIf { it.isNotBlank() } ?: return null
        return when (val refreshed = authApi.refresh(refreshToken)) {
            is AuthApiResult.Success -> {
                val next =
                    session.copy(
                        accessToken = refreshed.data.accessToken,
                        refreshToken = refreshed.data.refreshToken
                    )
                sessionStore.saveSession(next)
                next
            }
            is AuthApiResult.Failure -> null
        }
    }

    private fun buildRequest(
        path: String,
        query: String?,
        serverId: String,
        accessToken: String,
        method: String,
        bodyText: String?
    ): BridgeAuthenticatedRequest =
        BridgeAuthenticatedRequest(
            url =
                buildString {
                    append(baseUrl.trimEnd('/'))
                    append('/')
                    append(path.trimStart('/'))
                    query?.takeIf { it.isNotBlank() }?.let {
                        append('?')
                        append(it)
                    }
                },
            method = method,
            authorization = "Bearer $accessToken",
            serverId = serverId,
            bodyText = bodyText
        )
}

internal class BridgeSlockNetworkClient(
    private val bridge: BridgeModule,
    sessionStore: SlockAuthSessionStore,
    baseUrl: String = AUTH_BASE_URL
) : SlockNetworkClient {
    private val executor = BridgeAuthenticatedRequestExecutor(bridge, sessionStore, baseUrl)

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse =
        executor.request(
            path = path,
            query = query.toBridgeQuery(),
            serverIdOverride = serverIdOverride?.value,
            method = method,
            bodyText = bodyText
        )

    private fun List<Pair<String, String>>.toBridgeQuery(): String? =
        joinToString("&") { (name, value) -> "${bridge.urlEncode(name)}=${bridge.urlEncode(value)}" }
            .takeIf { it.isNotBlank() }
}

internal data class BridgeAuthenticatedRequest(
    val url: String,
    val method: String,
    val authorization: String,
    val serverId: String,
    val bodyText: String? = null
) {
    fun toBridgeCommand(): JSONObject =
        JSONObject()
            .put("url", url)
            .put("method", method)
            .put("authorization", authorization)
            .put("serverId", serverId)
            .also { command -> bodyText?.let { command.put("bodyText", it) } }
}

internal class BridgeHomeApi(
    private val bridge: BridgeModule,
    private val sessionStore: SlockAuthSessionStore,
    private val baseUrl: String = AUTH_BASE_URL
) : SlockHomeApi {
    private val executor = BridgeAuthenticatedRequestExecutor(bridge, sessionStore, baseUrl)

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
        return executor.request(path = path, serverIdOverride = serverIdOverride)
    }
}

internal class BridgeAnnouncementsRemindersApi(
    bridge: BridgeModule,
    sessionStore: SlockAuthSessionStore,
    baseUrl: String = AUTH_BASE_URL
) : SlockAnnouncementsRemindersApi {
    private val executor = BridgeAuthenticatedRequestExecutor(bridge, sessionStore, baseUrl)

    override suspend fun activeAnnouncements(): HomeNetworkResponse =
        executor.request(path = "announcements/active")

    override suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse =
        executor.request(
            path = "announcements/${announcementId.trim()}/dismiss",
            method = "POST"
        )

    override suspend fun reminders(ownerAgentId: MemberId, status: String): HomeNetworkResponse =
        executor.request(
            path = "reminders",
            query = "ownerAgentId=${ownerAgentId.value}&status=$status"
        )
}

internal class BridgeMembersApi(
    bridge: BridgeModule,
    sessionStore: SlockAuthSessionStore,
    baseUrl: String = AUTH_BASE_URL
) {
    private val networkClient =
        BridgeSlockNetworkClient(
            bridge = bridge,
            sessionStore = sessionStore,
            baseUrl = baseUrl
        )

    suspend fun serverMembers(serverId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "servers/$serverId/members",
            serverIdOverride = ServerId(serverId)
        )

    suspend fun agents(serverId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents",
            serverIdOverride = ServerId(serverId)
        )
}

internal class BridgeSettingsPlatformCapabilities(
    private val bridge: BridgeModule
) : SharedSettingsPlatformCapabilities {
    fun bridgeCopyToClipboard(content: String) {
        bridge.copyToPasteboard(content)
        bridge.toast("Copied")
    }

    fun openUrl(url: String) {
        bridge.openUrl(url)
    }

    override suspend fun loadStorage(): DataResult<SharedSettingsStorageSnapshot> {
        val response = bridge.settingsCapability(JSONObject().put("action", "loadStorage"))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_storage_unavailable" }))
        }
        return DataResult.Content(response.toStorageSnapshot())
    }

    override suspend fun clearStorage(target: String): DataResult<SharedSettingsStorageSnapshot> {
        val response =
            bridge.settingsCapability(
                JSONObject()
                    .put("action", "clearStorage")
                    .put("target", target)
            )
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_storage_clear_failed" }))
        }
        return DataResult.Content(response.toStorageSnapshot())
    }

    override suspend fun loadAbout(): DataResult<SharedSettingsAboutSnapshot> {
        val response = bridge.settingsCapability(JSONObject().put("action", "loadAbout"))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_about_unavailable" }))
        }
        return DataResult.Content(
            SharedSettingsAboutSnapshot(
                appName = response.optString("appName").ifBlank { "Slock" },
                versionLabel = response.optString("versionLabel"),
                buildLabel = response.optString("buildLabel"),
                packageName = response.optString("packageName"),
                signedInUserLabel = response.optString("signedInUserLabel"),
                selectedServerLabel = response.optString("selectedServerLabel"),
                sessionStatusLabel = response.optString("sessionStatusLabel"),
                socketStatusLabel = response.optString("socketStatusLabel"),
                notificationStatusLabel = response.optString("notificationStatusLabel"),
                deviceLabel = response.optString("deviceLabel"),
                platformLabel = response.optString("platformLabel")
            )
        )
    }

    override suspend fun loadDiagnosticsHost(): DataResult<SharedSettingsDiagnosticsHostSnapshot> {
        val response = bridge.settingsCapability(JSONObject().put("action", "loadDiagnostics"))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_diagnostics_unavailable" }))
        }
        return DataResult.Content(response.toDiagnosticsHostSnapshot())
    }

    suspend fun copyDiagnostics(): Boolean =
        bridge.settingsCapability(JSONObject().put("action", "copyDiagnostics"))?.optString("status") == "ok"

    suspend fun shareDiagnostics(): Boolean =
        bridge.settingsCapability(JSONObject().put("action", "shareDiagnostics"))?.optString("status") == "ok"

    suspend fun clearDiagnostics(): Boolean =
        bridge.settingsCapability(JSONObject().put("action", "clearDiagnostics"))?.optString("status") == "ok"

    suspend fun sendFeedback(): Boolean =
        bridge.settingsCapability(JSONObject().put("action", "sendFeedback"))?.optString("status") == "ok"

    suspend fun buglyCrashMonitorAction(action: String): Boolean =
        bridge.settingsCapability(JSONObject().put("action", action))?.optString("status") == "ok"

    suspend fun requestNotificationPermission(): SystemNotificationActionResult =
        systemNotificationAction("requestNotificationPermission")

    suspend fun openNotificationSettings(): SystemNotificationActionResult =
        systemNotificationAction("openNotificationSettings")

    suspend fun sendTestNotification(): SystemNotificationActionResult =
        systemNotificationAction("sendTestNotification")

    private fun JSONObject.toStorageSnapshot(): SharedSettingsStorageSnapshot {
        val rowsJson = optJSONArray("rows")
        val rows =
            buildList {
                if (rowsJson != null) {
                    for (index in 0 until rowsJson.length()) {
                        val row = rowsJson.optJSONObject(index) ?: continue
                        add(
                            SharedSettingsStorageRow(
                                target = row.optString("target"),
                                title = row.optString("title"),
                                detail = row.optString("detail"),
                                sizeLabel = row.optString("sizeLabel").ifBlank { row.optString("size") },
                                clearEnabled = row.optBoolean("clearEnabled", false),
                                clearInProgress = row.optBoolean("clearInProgress", false)
                            )
                        )
                    }
                }
            }
        return SharedSettingsStorageSnapshot(
            totalSizeLabel = optString("totalSizeLabel").ifBlank { optString("totalSize") },
            rows = rows,
            feedback = optString("feedback").takeIf { it.isNotBlank() }
        )
    }

    private fun JSONObject.toDiagnosticsHostSnapshot(): SharedSettingsDiagnosticsHostSnapshot {
        val entriesJson = optJSONArray("entries")
        val entries =
            buildList {
                if (entriesJson != null) {
                    for (index in 0 until entriesJson.length()) {
                        val entry = entriesJson.optJSONObject(index) ?: continue
                        add(
                            SharedSettingsDiagnosticsEntry(
                                timestampLabel = entry.optString("timestampLabel"),
                                tag = entry.optString("tag"),
                                message = entry.optString("message")
                            )
                        )
                    }
                }
            }
        return SharedSettingsDiagnosticsHostSnapshot(entries = entries)
    }

    private suspend fun systemNotificationAction(action: String): SystemNotificationActionResult {
        val response = bridge.settingsCapability(JSONObject().put("action", action))
            ?: return SystemNotificationActionResult(SystemNotificationActionStatus.Unsupported, code = "settings_capability_missing")
        return response.toSystemNotificationActionResult()
    }

    private fun JSONObject.toSystemNotificationActionResult(): SystemNotificationActionResult {
        val status = optString("status")
        val code = optString("code").ifBlank { status.ifBlank { "unknown" } }
        return SystemNotificationActionResult(
            status =
                when {
                    status == "ok" -> SystemNotificationActionStatus.Ok
                    code == "permission_denied" -> SystemNotificationActionStatus.PermissionDenied
                    code == "notifications_disabled" -> SystemNotificationActionStatus.NotificationsDisabled
                    code == "channel_blocked" -> SystemNotificationActionStatus.ChannelBlocked
                    code == "unsupported" || code == "notification_unsupported" -> SystemNotificationActionStatus.Unsupported
                    else -> SystemNotificationActionStatus.Error
                },
            code = code,
            message = optString("message")
        )
    }
}

internal class BridgeTasksApi(
    private val bridge: BridgeModule,
    private val sessionStore: SlockAuthSessionStore,
    private val baseUrl: String = AUTH_BASE_URL
) : SlockTasksApi {
    private val executor = BridgeAuthenticatedRequestExecutor(bridge, sessionStore, baseUrl)

    override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        request(path = "tasks/server", serverId = serverId.value)

    override suspend fun channelTasks(channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse =
        request(path = "tasks/channel/${channelId.value}")

    override suspend fun taskByNumber(
        channelId: ChannelId,
        taskNumber: Int,
        priority: DataRequestPriority
    ): HomeNetworkResponse =
        request(path = "tasks/channel/${channelId.value}/number/$taskNumber")

    override suspend fun createTasks(
        channelId: ChannelId,
        drafts: List<TaskCreateDraft>,
        priority: DataRequestPriority
    ): HomeNetworkResponse =
        request(
            path = "tasks/channel/${channelId.value}",
            method = "POST",
            bodyText = buildSharedTasksCreateBody(drafts)
        )

    override suspend fun claimTask(taskId: TaskId): HomeNetworkResponse =
        request(path = "tasks/${taskId.value}/claim", method = "PATCH")

    override suspend fun convertMessage(messageId: MessageId): HomeNetworkResponse =
        request(
            path = "tasks/convert-message",
            method = "POST",
            bodyText = buildSharedTaskConvertBody(messageId)
        )

    override suspend fun unclaimTask(taskId: TaskId): HomeNetworkResponse =
        request(path = "tasks/${taskId.value}/unclaim", method = "PATCH")

    override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
        request(
            path = "tasks/${taskId.value}/status",
            method = "PATCH",
            bodyText = buildSharedTaskStatusBody(status)
        )

    override suspend fun deleteTask(taskId: TaskId): HomeNetworkResponse =
        request(path = "tasks/${taskId.value}", method = "DELETE")

    private suspend fun request(
        path: String,
        serverId: String? = null,
        method: String = "GET",
        bodyText: String? = null
    ): HomeNetworkResponse {
        return executor.request(path = path, serverIdOverride = serverId, method = method, bodyText = bodyText)
    }
}

internal interface SharedThreadNetworkApi {
    suspend fun channelMessages(threadId: String, limit: Int = 50, beforeSeq: Long? = null): HomeNetworkResponse
    suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int = 50): HomeNetworkResponse
    suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean = false,
        attachmentIds: List<String> = emptyList()
    ): HomeNetworkResponse
    suspend fun attachmentUrl(attachmentId: String): HomeNetworkResponse
    suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse
    suspend fun saveMessage(messageId: String): HomeNetworkResponse
    suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse
    suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse
    suspend fun updateTaskStatus(taskId: String, status: TaskStatus): HomeNetworkResponse
    suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse
}

internal class BridgeThreadApi(
    private val bridge: BridgeModule,
    private val sessionStore: SlockAuthSessionStore,
    private val baseUrl: String = AUTH_BASE_URL
) : SharedThreadNetworkApi {
    private val executor = BridgeAuthenticatedRequestExecutor(bridge, sessionStore, baseUrl)

    override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?): HomeNetworkResponse =
        request(
            "messages/channel/${threadId.trim()}",
            listOfNotNull("limit=$limit", beforeSeq?.let { "before=$it" }).joinToString("&")
        )

    override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int): HomeNetworkResponse =
        request(
            "messages/sync",
            listOf(
                "since_seq=$sinceSeq",
                "limit=$limit",
                "channel_id=${channelId.trim()}"
            ).joinToString("&")
        )

    override suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean,
        attachmentIds: List<String>
    ): HomeNetworkResponse =
        request(
            path = "messages",
            method = "POST",
            bodyText = buildSharedThreadSendMessageBody(
                channelId = channelId,
                body = body,
                attachmentIds = attachmentIds,
                asTask = asTask
            )
        )

    override suspend fun attachmentUrl(attachmentId: String): HomeNetworkResponse =
        request("attachments/${attachmentId.trim()}/url")

    override suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse =
        request(path = "channels/${channelId.trim()}/read-all", method = "POST")

    override suspend fun saveMessage(messageId: String): HomeNetworkResponse =
        request(
            path = "channels/saved",
            method = "POST",
            bodyText = buildSharedSaveMessageBody(MessageId(messageId.trim()))
        )

    override suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse =
        request(
            path = "messages/${messageId.trim()}/reactions",
            method = "POST",
            bodyText = buildSharedReactionBody(emoji)
        )

    override suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse =
        request(
            path = "messages/${messageId.trim()}/reactions",
            method = "DELETE",
            bodyText = buildSharedReactionBody(emoji)
        )

    override suspend fun updateTaskStatus(taskId: String, status: TaskStatus): HomeNetworkResponse =
        request(
            path = "tasks/${taskId.trim()}/status",
            method = "PATCH",
            bodyText = buildSharedTaskStatusBody(status)
        )

    override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse =
        request(
            path = "channels/${channelId.trim()}/threads",
            method = "POST",
            bodyText = bridgeAuthJson.encodeToString(ParentMessageThreadRequestBody(parentMessageId.trim()))
        )

    private suspend fun request(
        path: String,
        query: String? = null,
        method: String = "GET",
        bodyText: String? = null
    ): HomeNetworkResponse {
        return executor.request(path = path, query = query, method = method, bodyText = bodyText)
    }
}

internal class KuiklyAuthSessionStore(
    private val store: SlockRelationalStore
) : SlockAuthSessionStore, SlockAuthSessionInvalidationSource {
    private val invalidations = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val sessionInvalidations: kotlinx.coroutines.flow.SharedFlow<Unit> =
        invalidations.asSharedFlow()

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
        invalidations.emit(Unit)
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

private const val SESSION_EXPIRED_ERROR = """{"error":"session_expired"}"""

private fun JSONObject?.toHomeNetworkResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = this?.optString("code").orEmpty().toIntOrNull() ?: 0,
        bodyText = this?.optString("bodyText").takeUnless { it.isNullOrBlank() },
        errorBodyText =
            this?.optString("errorBodyText").takeUnless { it.isNullOrBlank() }
                ?: this?.optString("message").takeUnless { it.isNullOrBlank() }
    )

private fun HomeNetworkResponse.requiresTokenRefresh(): Boolean =
    code == 401 || errorBodyText.isExpiredTokenText()

private fun String?.isExpiredTokenText(): Boolean {
    val normalized = this?.lowercase().orEmpty()
    return normalized.contains("expiredtoken") ||
        normalized.contains("expired_token") ||
        normalized.contains("token_expired") ||
        normalized.contains("jwt expired")
}

private fun HomeNetworkResponse.asSessionExpired(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = 401,
        bodyText = null,
        errorBodyText = errorBodyText.takeUnless { it.isNullOrBlank() } ?: SESSION_EXPIRED_ERROR
    )
