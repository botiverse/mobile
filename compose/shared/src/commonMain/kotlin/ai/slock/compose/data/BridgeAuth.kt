package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.model.TaskStatus
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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

    override suspend fun pickAccountAvatarFile(): DataResult<AttachmentUploadFile> {
        val response = bridge.threadAttachmentUpload(channelId = "settings-account-avatar", imageOnly = true)
        val code = response?.optInt("code", 0) ?: 0
        if (code == 204) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        if (code !in 200..299) {
            val errorCode = response?.optString("errorCode").orEmpty().ifBlank { "settings_account_avatar_picker_failed" }
            return DataResult.Error(ScreenError(errorCode, retryable = true))
        }
        val file = parseSharedThreadPickedAttachmentFiles(response).firstOrNull()
            ?: return DataResult.Error(ScreenError("settings_account_avatar_picker_empty", retryable = true))
        return DataResult.Content(file)
    }

    override suspend fun pickConnectedAppLogoFile(clientRecordId: String): DataResult<AttachmentUploadFile> {
        val response = bridge.threadAttachmentUpload(channelId = "settings-connected-app-logo-$clientRecordId", imageOnly = true)
        val code = response?.optInt("code", 0) ?: 0
        if (code == 204) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        if (code !in 200..299) {
            val errorCode = response?.optString("errorCode").orEmpty().ifBlank { "settings_connected_app_logo_picker_failed" }
            return DataResult.Error(ScreenError(errorCode, retryable = true))
        }
        val file = parseSharedThreadPickedAttachmentFiles(response).firstOrNull()
            ?: return DataResult.Error(ScreenError("settings_connected_app_logo_picker_empty", retryable = true))
        return DataResult.Content(file)
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
                platformLabel = response.optString("platformLabel"),
                buildTime = response.optString("buildTime"),
                gitCommit = response.optString("gitCommit")
            )
        )
    }

    override suspend fun loadDeveloper(): DataResult<SharedSettingsDeveloperSnapshot> {
        val response = bridge.settingsCapability(JSONObject().put("action", "loadDeveloper"))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_developer_unavailable" }))
        }
        return DataResult.Content(response.toDeveloperSnapshot())
    }

    override suspend fun setApiEnvironment(environmentKey: String): DataResult<SharedSettingsDeveloperSnapshot> {
        val response =
            bridge.settingsCapability(
                JSONObject()
                    .put("action", "setApiEnvironment")
                    .put("environmentKey", environmentKey)
            )
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_developer_environment_failed" }))
        }
        return DataResult.Content(response.toDeveloperSnapshot())
    }

    override suspend fun loadDiagnosticsHost(): DataResult<SharedSettingsDiagnosticsHostSnapshot> {
        val response = bridge.settingsCapability(JSONObject().put("action", "loadDiagnostics"))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_diagnostics_unavailable" }))
        }
        return DataResult.Content(response.toDiagnosticsHostSnapshot())
    }

    override suspend fun loadNetworkProbe(): DataResult<SharedSettingsNetworkProbeSnapshot> {
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

    override suspend fun setRecompositionProfilerEnabled(enabled: Boolean): DataResult<Unit> {
        val response =
            bridge.settingsCapability(
                JSONObject()
                    .put("action", "setRecompositionProfilerEnabled")
                    .put("enabled", enabled)
            )
        if (response?.optString("status") != "ok") {
            return DataResult.Error(
                ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_profiler_bridge_failed" })
            )
        }
        return DataResult.Content(Unit)
    }

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
                .put("parentChannelId", request.parentChannelId.orEmpty())
                .put("parentMessageId", request.parentMessageId.orEmpty())
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

    private fun JSONObject.toDeveloperSnapshot(): SharedSettingsDeveloperSnapshot {
        val optionsJson = optJSONArray("options")
        val options =
            buildList {
                if (optionsJson != null) {
                    for (index in 0 until optionsJson.length()) {
                        val option = optionsJson.optJSONObject(index) ?: continue
                        val key = option.optString("key")
                        if (key.isBlank()) continue
                        add(
                            SharedSettingsApiEnvironmentOption(
                                key = key,
                                label = option.optString("label").ifBlank { key },
                                apiBaseUrl = option.optString("apiBaseUrl"),
                                socketBaseUrl = option.optString("socketBaseUrl"),
                                webUrl = option.optString("webUrl")
                            )
                        )
                    }
                }
            }
        return SharedSettingsDeveloperSnapshot(
            environmentKey = optString("environmentKey"),
            environmentLabel = optString("environmentLabel"),
            apiBaseUrl = optString("apiBaseUrl").ifBlank { SLOCK_API_BASE_URL },
            socketBaseUrl = optString("socketBaseUrl").ifBlank { SLOCK_SOCKET_BASE_URL },
            webUrl = optString("webUrl"),
            options = options,
            feedback = optString("feedback").takeIf { it.isNotBlank() }
        )
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
    suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): HomeNetworkResponse
    suspend fun attachmentUrl(attachmentId: String, disposition: String? = null): HomeNetworkResponse
    suspend fun attachmentComments(attachmentId: String): HomeNetworkResponse
    suspend fun sendAttachmentComment(attachmentId: String, body: String): HomeNetworkResponse
    suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse
    suspend fun saveMessage(messageId: String): HomeNetworkResponse
    suspend fun unsaveMessage(messageId: String): HomeNetworkResponse
    suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse
    suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse
    suspend fun updateTaskStatus(taskId: String, status: TaskStatus): HomeNetworkResponse
    suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse
}
