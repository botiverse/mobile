package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.model.TaskStatus
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val SESSION_KEY = "auth_session"

private val bridgeAuthJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class SettingsDiagnosticsFeedbackDraft(
    val message: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bytes: ByteArray
)

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
                appName = response.optString("appName").ifBlank { "Raft" },
                versionLabel = response.optString("versionLabel"),
                buildLabel = response.optString("buildLabel"),
                sourceLabel = response.optString("sourceLabel"),
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

    suspend fun loadNetworkProbe(): DataResult<SharedSettingsNetworkProbeSnapshot> {
        val response = bridge.settingsCapability(JSONObject().put("action", "probeNetwork"))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_network_probe_unavailable" }))
        }
        return DataResult.Content(
            SharedSettingsNetworkProbeSnapshot(
                baseUrl = response.optString("baseUrl").ifBlank { SLOCK_API_BASE_URL },
                statusLabel = response.optString("statusLabel").ifBlank { if (response.optBoolean("reachable", false)) "Reachable" else "Unavailable" },
                detail = response.optString("detail"),
                reachable = response.optBoolean("reachable", false),
                code = response.optInt("code", 0),
                durationMillis = response.optLong("durationMillis", 0L)
            )
        )
    }

    suspend fun copyDiagnostics(): Boolean =
        bridge.settingsCapability(JSONObject().put("action", "copyDiagnostics"))?.optString("status") == "ok"

    suspend fun shareDiagnostics(): Boolean =
        bridge.settingsCapability(JSONObject().put("action", "shareDiagnostics"))?.optString("status") == "ok"

    suspend fun clearDiagnostics(): Boolean =
        bridge.settingsCapability(JSONObject().put("action", "clearDiagnostics"))?.optString("status") == "ok"

    suspend fun sendFeedback(): Boolean =
        bridge.settingsCapability(JSONObject().put("action", "sendFeedback"))?.optString("status") == "ok"

    suspend fun prepareDiagnosticsFeedback(): SettingsDiagnosticsFeedbackDraft? =
        bridge.settingsCapability(JSONObject().put("action", "prepareDiagnosticsFeedback"))
            ?.toDiagnosticsFeedbackDraft()

    suspend fun requestNotificationPermission(): SystemNotificationActionResult =
        systemNotificationAction("requestNotificationPermission")

    suspend fun openNotificationSettings(): SystemNotificationActionResult =
        systemNotificationAction("openNotificationSettings")

    suspend fun sendTestNotification(): SystemNotificationActionResult =
        systemNotificationAction("sendTestNotification")

    suspend fun postUnreadNotification(request: SystemUnreadNotificationRequest): SystemNotificationActionResult {
        val command =
            JSONObject()
                .put("action", "postUnreadNotification")
                .put("serverId", request.serverId)
                .put("messageId", request.messageId)
                .put("threadId", request.threadId)
                .put("channelId", request.channelId.orEmpty())
                .put("category", request.category.wireValue)
                .put("title", request.title)
                .put("body", request.body)
                .put("notificationKey", request.notificationKey)
        val response = bridge.settingsCapability(command)
            ?: return SystemNotificationActionResult(SystemNotificationActionStatus.Unsupported, code = "settings_capability_missing")
        return response.toSystemNotificationActionResult()
    }

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

    @OptIn(ExperimentalEncodingApi::class)
    private fun JSONObject.toDiagnosticsFeedbackDraft(): SettingsDiagnosticsFeedbackDraft? {
        if (optString("status") != "ok") return null
        val fileName = optString("fileName").ifBlank { "slock-feedback.txt" }
        val mimeType = optString("mimeType").ifBlank { "text/plain" }
        val bodyBase64 = optString("bodyBase64").takeIf { it.isNotBlank() } ?: return null
        val bytes = runCatching { Base64.decode(bodyBase64) }.getOrNull() ?: return null
        return SettingsDiagnosticsFeedbackDraft(
            message = optString("message").ifBlank { "## Raft feedback\n\n<!-- Describe what happened here. -->" },
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = optLong("sizeBytes", bytes.size.toLong()).takeIf { it > 0L } ?: bytes.size.toLong(),
            bytes = bytes
        )
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
                    code == "permission_requested" -> SystemNotificationActionStatus.Requested
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

internal interface SharedThreadNetworkApi {
    suspend fun channelMessages(threadId: String, limit: Int = 50, beforeSeq: Long? = null): HomeNetworkResponse
    suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int = 50): HomeNetworkResponse
    suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean = false,
        attachmentIds: List<String> = emptyList()
    ): HomeNetworkResponse
    suspend fun attachmentUrl(attachmentId: String, disposition: String? = null): HomeNetworkResponse
    suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse
    suspend fun saveMessage(messageId: String): HomeNetworkResponse
    suspend fun unsaveMessage(messageId: String): HomeNetworkResponse
    suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse
    suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse
    suspend fun updateTaskStatus(taskId: String, status: TaskStatus): HomeNetworkResponse
    suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse
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
