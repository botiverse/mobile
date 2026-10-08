package build.raft.app.data

import build.raft.app.base.BridgeModule
import build.raft.app.flushSlockPlatformDiagnosticsDownstream
import build.raft.app.flushSlockPlatformLogs
import build.raft.app.slockPlatformSupportsConversationForwardGesture
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val bridgeAuthJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class HandsUpdateBridgeErrorBody(
    val status: String? = null,
    val code: String? = null,
    val message: String? = null
)

class SettingsDiagnosticsFeedbackDraft(
    val message: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bytes: ByteArray
)

class SettingsHandsFeedbackSubmission(
    val ticketId: String,
    val shareText: String
)

internal class BridgeSettingsPlatformCapabilities(
    private val bridge: BridgeModule,
    private val copiedMessage: String,
    private val bridgeCallContext: CoroutineContext = EmptyCoroutineContext,
    private val feedbackVisualSubmission: SettingsHandsFeedbackSubmission? = null,
    private val openUrlHandler: ((String) -> Unit)? = null
) : SharedSettingsPlatformCapabilities {
    override val supportsConversationForwardGesture: Boolean
        get() = slockPlatformSupportsConversationForwardGesture()

    fun bridgeCopyToClipboard(content: String) {
        bridge.copyToPasteboard(content)
        bridge.toast(copiedMessage)
    }

    fun bridgeToast(content: String) {
        bridge.toast(content)
    }

    fun openUrl(url: String) {
        openUrlHandler?.invoke(url) ?: bridge.openUrl(url)
    }

    fun openExternalUrl(url: String) {
        bridge.openUrl(url)
    }

    fun log(message: String) {
        bridge.log(message)
    }

    override suspend fun startMobileOAuthAccountLink(providerId: String): DataResult<Unit> {
        val response =
            settingsCapability(
                JSONObject()
                    .put("action", "mobileOAuthLink")
                    .put("providerId", providerId)
            )
        val status = response?.optString("status").orEmpty()
        return when (status) {
            "started", "ok" -> DataResult.Content(Unit)
            "prepared" -> startSharedBrowserMobileOAuthLink(providerId, response)
            "unsupported" -> DataResult.Empty(EmptyReason.NoSettingsAvailable)
            else -> {
                val code = response?.optString("code").orEmpty().ifBlank { "settings_mobile_oauth_link_failed" }
                DataResult.Error(ScreenError(code, retryable = true))
            }
        }
    }

    suspend fun startWebLogin(): DataResult<Unit> {
        val prep = settingsCapability(JSONObject().put("action", "appLoginPrepare"))
        val generation = prep?.optString("generation").orEmpty()
        val url = SlockAppLogin.start(generation, prep?.optString("codeVerifier").orEmpty(),
            prep?.optString("codeChallenge").orEmpty(), prep?.optString("returnUri").orEmpty())
            ?: return DataResult.Error(ScreenError("web_login_prepare_failed", retryable = true))
        val result = settingsCapability(JSONObject().put("action", "appLoginOpen").put("authorizationUrl", url))
        if (result?.optString("status") == "started") return DataResult.Content(Unit)
        SlockAppLogin.cancel(generation)
        return DataResult.Error(ScreenError("web_login_browser_failed", retryable = true))
    }

    private suspend fun startSharedBrowserMobileOAuthLink(
        providerId: String,
        preparation: JSONObject?,
    ): DataResult<Unit> {
        val generation = preparation?.optString("generation").orEmpty()
        val verifier = preparation?.optString("codeVerifier").orEmpty()
        val challenge = preparation?.optString("codeChallenge").orEmpty()
        val returnUri = preparation?.optString("returnUri").orEmpty()
        val pkcePattern = Regex("^[A-Za-z0-9_-]{43,128}$")
        if (
            generation.isBlank() ||
            !pkcePattern.matches(verifier) ||
            !pkcePattern.matches(challenge) ||
            returnUri !in setOf("raft://oauth/callback", "raft-debug://oauth/callback")
        ) {
            return DataResult.Error(ScreenError("settings_mobile_oauth_pkce_failed", retryable = true))
        }
        val start =
            suspendCoroutine<SlockIosMobileOAuthStartResult> { continuation ->
                SlockIosMobileOAuthBridge.startBrowser(
                    provider = providerId,
                    mode = "link",
                    generation = generation,
                    returnUri = returnUri,
                    codeVerifier = verifier,
                    codeChallenge = challenge,
                ) { continuation.resume(it) }
            }
        val authorizationUrl = start.authorizationUrl.orEmpty()
        if (
            start.status != "started" ||
            start.presentation != "browser" ||
            start.callbackScheme != returnUri.substringBefore("://") ||
            !authorizationUrl.startsWith("https://")
        ) {
            return DataResult.Error(
                ScreenError(start.errorCode.orEmpty().ifBlank { "settings_mobile_oauth_link_failed" }, retryable = true)
            )
        }
        val open =
            settingsCapability(
                JSONObject()
                    .put("action", "mobileOAuthOpen")
                    .put("authorizationUrl", authorizationUrl)
            )
        if (open?.optString("status") == "started") return DataResult.Content(Unit)
        suspendCoroutine<Unit> { continuation ->
            SlockIosMobileOAuthBridge.cancel(generation, "browser_failed") { continuation.resume(Unit) }
        }
        val code = open?.optString("code").orEmpty().ifBlank { "settings_mobile_oauth_browser_failed" }
        return DataResult.Error(ScreenError(code, retryable = true))
    }

    override suspend fun pickAccountAvatarFile(): DataResult<PlatformFile> {
        val response = threadAttachmentUpload(channelId = "settings-account-avatar", imageOnly = true)
        val code = response?.optInt("code", 0) ?: 0
        if (code == 204) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        if (code !in 200..299) {
            val errorCode = response?.optString("errorCode").orEmpty().ifBlank { "settings_account_avatar_picker_failed" }
            return DataResult.Error(ScreenError(errorCode, retryable = true))
        }
        val file = parseSharedThreadPickedPlatformFiles(response).firstOrNull()
            ?: return DataResult.Error(ScreenError("settings_account_avatar_picker_empty", retryable = true))
        return DataResult.Content(file)
    }

    override suspend fun pickConnectedAppLogoFile(clientRecordId: String): DataResult<PlatformFile> {
        val response = threadAttachmentUpload(channelId = "settings-connected-app-logo-$clientRecordId", imageOnly = true)
        val code = response?.optInt("code", 0) ?: 0
        if (code == 204) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        if (code !in 200..299) {
            val errorCode = response?.optString("errorCode").orEmpty().ifBlank { "settings_connected_app_logo_picker_failed" }
            return DataResult.Error(ScreenError(errorCode, retryable = true))
        }
        val file = parseSharedThreadPickedPlatformFiles(response).firstOrNull()
            ?: return DataResult.Error(ScreenError("settings_connected_app_logo_picker_empty", retryable = true))
        return DataResult.Content(file)
    }

    override suspend fun loadStorage(): DataResult<SharedSettingsStorageSnapshot> {
        val response = settingsCapability(JSONObject().put("action", "loadStorage"))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_storage_unavailable" }))
        }
        return DataResult.Content(response.toStorageSnapshot())
    }

    override suspend fun clearStorage(target: String): DataResult<SharedSettingsStorageSnapshot> {
        val response =
            settingsCapability(
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
        val response = settingsCapability(JSONObject().put("action", "loadAbout"))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_about_unavailable" }))
        }
        return DataResult.Content(
            SharedSettingsAboutSnapshot(
                appName = response.optString("appName").ifBlank { "Raft" },
                versionLabel = response.optString("versionLabel"),
                versionCodeLabel = response.optString("versionCodeLabel"),
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
                sdkLabel = response.optString("sdkLabel"),
                installTimeLabel = response.optString("installTimeLabel"),
                screenLabel = response.optString("screenLabel"),
                densityLabel = response.optString("densityLabel"),
                fontScaleLabel = response.optString("fontScaleLabel"),
                modelLabel = response.optString("modelLabel"),
                buildTime = response.optString("buildTime"),
                gitCommit = response.optString("gitCommit")
            )
        )
    }

    override suspend fun loadOpenSourceLicenses(): DataResult<SharedSettingsOpenSourceLicensesSnapshot> {
        val response = settingsCapability(JSONObject().put("action", "loadOpenSourceLicenses"))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_open_source_licenses_unavailable" }))
        }
        val payload =
            response.optString("payloadJson")
                .takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                ?: response
        val itemsJson = payload.optJSONArray("items")
        val items =
            buildList {
                if (itemsJson != null) {
                    for (index in 0 until itemsJson.length()) {
                        val item = itemsJson.optJSONObject(index) ?: continue
                        val name = item.optString("name")
                        val displayName = item.optString("displayName")
                        val licenseName = item.optString("licenseName")
                        add(
                            SharedSettingsOpenSourceLicenseItem(
                                id = item.optString("id").ifBlank { "${item.optString("group")}:$name:${item.optString("version")}" },
                                group = item.optString("group"),
                                name = name,
                                version = item.optString("version"),
                                displayName = displayName.ifBlank { name },
                                licenseName = licenseName.ifBlank { "Unknown" },
                                licenseUrl = item.optString("licenseUrl"),
                                sourceUrl = item.optString("sourceUrl")
                            )
                        )
                    }
                }
            }
        return DataResult.Content(
            SharedSettingsOpenSourceLicensesSnapshot(
                schemaVersion = payload.optInt("schemaVersion", response.optInt("schemaVersion", 1)),
                variant = payload.optString("variant").ifBlank { response.optString("variant") },
                sourceConfiguration = payload.optString("sourceConfiguration").ifBlank { response.optString("sourceConfiguration") },
                items = items
            )
        )
    }

    override suspend fun loadDeveloper(): DataResult<SharedSettingsDeveloperSnapshot> {
        val response = settingsCapability(JSONObject().put("action", "loadDeveloper"))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_developer_unavailable" }))
        }
        return DataResult.Content(response.toDeveloperSnapshot())
    }

    override suspend fun checkHandsUpdate(): DataResult<SharedSettingsDeveloperSnapshot> {
        val response = settingsCapability(JSONObject().put("action", "handsUpdateCheck"))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_hands_update_check_failed" }))
        }
        return DataResult.Content(response.toDeveloperSnapshot())
    }

    override suspend fun checkHandsUpdateStatus(): DataResult<SharedSettingsUpdateCheckSnapshot> =
        handsUpdateAction("handsUpdateStatus")

    override suspend fun readHandsDownloadProgress(token: String): SharedUpdateDownloadProgress? {
        val response = settingsCapability(JSONObject().put("action", "handsDownloadProgress").put("token", token))
        return response?.toSharedUpdateDownloadProgress()
    }

    override suspend fun installHandsUpdate(): DataResult<SharedSettingsUpdateCheckSnapshot> =
        handsUpdateAction("handsUpdateInstall")

    override suspend fun installHandsUpdateForToken(token: String): DataResult<SharedSettingsUpdateCheckSnapshot> =
        handsUpdateAction("handsUpdateInstall", token)

    override suspend fun prefetchReleaseNotes(url: String): DataResult<Unit> {
        if (url.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val response =
            settingsCapability(
                JSONObject()
                    .put("action", "prefetchReleaseNotes")
                    .put("url", url)
            )
        return when (response?.optString("status")) {
            "ok", "started" -> DataResult.Content(Unit)
            "unsupported" -> DataResult.Empty(EmptyReason.NoSettingsAvailable)
            else -> DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_release_notes_prefetch_failed" }))
        }
    }

    override suspend fun setApiEnvironment(environmentKey: String): DataResult<SharedSettingsDeveloperSnapshot> {
        val response =
            settingsCapability(
                JSONObject()
                    .put("action", "setApiEnvironment")
                    .put("environmentKey", environmentKey)
            )
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_developer_environment_failed" }))
        }
        return DataResult.Content(response.toDeveloperSnapshot())
    }

    override suspend fun saveApiEnvironment(request: SharedSettingsApiEnvironmentRequest): DataResult<SharedSettingsDeveloperSnapshot> {
        val response =
            settingsCapability(
                JSONObject()
                    .put("action", "setApiEnvironment")
                    .put("environmentKey", request.key)
                    .put("environmentLabel", request.label)
                    .put("apiBaseUrl", request.apiBaseUrl)
                    .put("socketBaseUrl", request.socketBaseUrl)
                    .put("webUrl", request.webUrl)
            )
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_developer_environment_failed" }))
        }
        return DataResult.Content(response.toDeveloperSnapshot())
    }

    override suspend fun onApiEnvironmentChanged(
        before: SharedSettingsApiEnvironmentOption,
        after: SharedSettingsApiEnvironmentOption
    ): DataResult<Unit> {
        val response =
            settingsCapability(
                JSONObject()
                    .put("action", "apiEnvironmentChanged")
                    .put("beforeEnvironmentKey", before.key)
                    .put("afterEnvironmentKey", after.key)
                    .put("apiBaseUrl", after.apiBaseUrl)
                    .put("socketBaseUrl", after.socketBaseUrl)
                    .put("webUrl", after.webUrl)
            )
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_developer_environment_hook_failed" }))
        }
        return DataResult.Content(Unit)
    }

    override suspend fun loadDiagnosticsHost(): DataResult<SharedSettingsDiagnosticsHostSnapshot> {
        val response = diagnosticsSettingsCapability("loadDiagnostics")
        if (response?.optString("status") != "ok") {
            return DataResult.Error(ScreenError(response?.optString("code").orEmpty().ifBlank { "settings_diagnostics_unavailable" }))
        }
        return DataResult.Content(response.toDiagnosticsHostSnapshot())
    }

    suspend fun copyDiagnostics(): Boolean =
        diagnosticsSettingsCapability("copyDiagnostics")?.optString("status") == "ok"

    suspend fun shareDiagnostics(): Boolean =
        diagnosticsSettingsCapability("shareDiagnostics")?.optString("status") == "ok"

    suspend fun clearDiagnostics(): Boolean =
        diagnosticsSettingsCapability("clearDiagnostics")?.optString("status") == "ok"

    override suspend fun setDiagnosticFeatureFlagOverride(key: String, mode: String): DataResult<Unit> {
        val response =
            settingsCapability(
                JSONObject()
                    .put("action", "setDiagnosticFeatureFlagOverride")
                    .put("key", key)
                    .put("mode", mode)
            )
        if (response?.optString("status") != "ok") {
            return DataResult.Error(
                ScreenError(
                    response?.optString("code").orEmpty()
                        .ifBlank { "diagnostic_feature_flag_override_failed" },
                    retryable = false,
                )
            )
        }
        return DataResult.Content(Unit)
    }

    suspend fun sendFeedback(): Boolean =
        diagnosticsSettingsCapability("sendFeedback")?.optString("status") == "ok"

    suspend fun prepareDiagnosticsFeedback(): SettingsDiagnosticsFeedbackDraft? =
        diagnosticsSettingsCapability("prepareDiagnosticsFeedback")
            ?.toDiagnosticsFeedbackDraft()

    suspend fun pickFeedbackAttachment(imageOnly: Boolean): DataResult<List<PlatformFile>> {
        val response = bridge.threadAttachmentUpload(channelId = "settings-hands-feedback", imageOnly = imageOnly)
        val code = response?.optInt("code", 0) ?: 0
        if (code == 204) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        if (code !in 200..299) {
            val errorCode = response?.optString("errorCode").orEmpty().ifBlank { "settings_feedback_picker_failed" }
            return DataResult.Error(ScreenError(errorCode, retryable = true))
        }
        val files = parseSharedThreadPickedPlatformFiles(response)
        if (files.isEmpty()) {
            return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        }
        return DataResult.Content(files)
    }

    suspend fun promoteFeedbackDraftAttachments(
        accountId: String,
        groupId: String,
        files: List<PlatformFile>,
        maxBytes: Long
    ): DataResult<List<PlatformFile>> {
        if (groupId.isBlank() || files.isEmpty() || files.size > 9 || maxBytes <= 0L) {
            return DataResult.Error(
                ScreenError("settings_feedback_attachment_promotion_invalid", retryable = false)
            )
        }
        val command =
            JSONObject()
                .put("action", "promoteFeedbackDraftAttachments")
                .put("accountId", accountId)
                .put("groupId", groupId)
                .put("maxBytes", maxBytes)
                .put("files", files.toFeedbackDraftPromotionFilesJson())
        val response = settingsCapability(command)
        if (response?.optString("status") != "ok") {
            return DataResult.Error(
                ScreenError(
                    response?.optString("code").orEmpty()
                        .ifBlank { "settings_feedback_attachment_promotion_failed" },
                    retryable = true
                )
            )
        }
        val promoted = parseSharedThreadPickedPlatformFiles(response)
        val valid =
            promoted.size == files.size &&
                promoted.zip(files).all { (actual, requested) ->
                    actual.id == requested.id &&
                        actual.fileName.isNotBlank() &&
                        !actual.fileRef.isNullOrBlank() &&
                        actual.source == "settings_feedback_draft" &&
                        actual.sizeBytes?.let { it in 1L..maxBytes } == true
                }
        return if (valid) {
            DataResult.Content(promoted)
        } else {
            try {
                discardFeedbackDraftAttachmentRefs(
                    accountId = accountId,
                    fileRefs = promoted.mapNotNull { it.fileRef?.takeIf(String::isNotBlank) }
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
            }
            DataResult.Error(
                ScreenError("settings_feedback_attachment_promotion_incomplete", retryable = true)
            )
        }
    }

    suspend fun discardFeedbackDraftAttachments(
        accountId: String,
        files: List<PlatformFile>
    ): Boolean {
        val fileRefs =
            files
                .filter { it.source == "settings_feedback_draft" }
                .mapNotNull { it.fileRef?.takeIf(String::isNotBlank) }
                .distinct()
        return discardFeedbackDraftAttachmentRefs(accountId, fileRefs)
    }

    private suspend fun discardFeedbackDraftAttachmentRefs(
        accountId: String,
        fileRefs: List<String>
    ): Boolean {
        val distinctRefs = fileRefs.filter(String::isNotBlank).distinct()
        if (distinctRefs.isEmpty()) return true
        val response =
            settingsCapability(
                JSONObject()
                    .put("action", "discardFeedbackDraftAttachments")
                    .put("accountId", accountId)
                    .put(
                        "fileRefs",
                        JSONArray().also { array -> distinctRefs.forEach(array::put) }
                    )
            )
        return response?.optString("status") == "ok"
    }

    suspend fun submitHandsFeedback(
        message: String,
        attachments: List<PlatformFile>
    ): DataResult<SettingsHandsFeedbackSubmission> {
        bridge.log(
            "settings_hands_feedback_submit_start messageLength=${message.length} " +
                "attachmentCount=${attachments.size}"
        )
        feedbackVisualSubmission?.let { submission ->
            bridge.log(
                "settings_hands_feedback_visual_submit_success attachmentCount=${attachments.size} " +
                    "ticket=${submission.ticketId.take(8)}"
            )
            return DataResult.Content(submission)
        }
        bridge.log("settings_hands_feedback_bridge_request_prepared")
        flushSlockPlatformLogs()
        if (!flushSlockPlatformDiagnosticsDownstream()) {
            return DataResult.Error(ScreenError("settings_diagnostics_flush_failed", retryable = true))
        }
        val response =
            settingsCapability(
                JSONObject()
                    .put("action", "submitFeedback")
                    .put("message", message)
                    .put("files", attachments.toFeedbackFilesJson())
            )
        bridge.log(
            "settings_hands_feedback_submit_response status=${response?.optString("status").orEmpty()} " +
                "code=${response?.optString("code").orEmpty()} " +
                "ticket=${response?.optString("ticketId").orEmpty().take(8)}"
        )
        return when (response?.optString("status")) {
            "ok" ->
                DataResult.Content(
                    SettingsHandsFeedbackSubmission(
                        ticketId = response.optString("ticketId"),
                        shareText = response.optString("shareText")
                    )
                )
            "unsupported" -> DataResult.Empty(EmptyReason.NoSettingsAvailable)
            else -> {
                val code = response?.optString("code").orEmpty()
                    .ifBlank { response?.optString("message").orEmpty() }
                    .ifBlank { "settings_feedback_submit_failed" }
                DataResult.Error(ScreenError(code, retryable = true))
            }
        }
    }

    override suspend fun loadSystemNotificationPermission(): SettingsMobileActionSnapshot? {
        val response = settingsCapability(JSONObject().put("action", "getNotificationPermissionStatus"))
            ?: return null
        return systemNotificationPermissionSnapshot(
            code = response.optString("code"),
            message = response.optString("message")
        )
    }

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
        val response = settingsCapability(command)
            ?: return SystemNotificationActionResult(SystemNotificationActionStatus.Unsupported, code = "settings_capability_missing")
        return response.toSystemNotificationActionResult()
    }

    private suspend fun handsUpdateAction(action: String, token: String = ""): DataResult<SharedSettingsUpdateCheckSnapshot> {
        val response = settingsCapability(JSONObject().put("action", action).put("token", token))
        if (response?.optString("status") != "ok") {
            return DataResult.Error(handsUpdateBridgeError(response?.toString()))
        }
        return DataResult.Content(response.toDeveloperSnapshot().updateCheck)
    }

    private suspend fun settingsCapability(command: JSONObject): JSONObject? =
        onBridgeCallContext {
            bridge.settingsCapability(command)
        }

    private suspend fun diagnosticsSettingsCapability(action: String): JSONObject? {
        val command = JSONObject().put("action", action)
        flushSlockPlatformLogs()
        if (!flushSlockPlatformDiagnosticsDownstream()) return null
        return settingsCapability(command)
    }

    private suspend fun threadAttachmentUpload(channelId: String, imageOnly: Boolean): JSONObject? =
        onBridgeCallContext {
            bridge.threadAttachmentUpload(channelId = channelId, imageOnly = imageOnly)
        }

    private suspend fun <T> onBridgeCallContext(block: suspend () -> T): T =
        if (bridgeCallContext == EmptyCoroutineContext) {
            block()
        } else {
            withContext(bridgeCallContext) {
                block()
            }
        }

    @OptIn(ExperimentalEncodingApi::class)
    private fun List<PlatformFile>.toFeedbackFilesJson(): JSONArray =
        JSONArray().also { array ->
            take(3).forEach { file ->
                array.put(
                    JSONObject()
                        .put("id", file.id)
                        .put("fileName", file.fileName)
                        .put("mimeType", file.mimeType.orEmpty())
                        .put("sizeBytes", file.sizeBytes ?: file.bytes.size.toLong())
                        .put("previewUri", file.previewUri.orEmpty())
                        .put("fileRef", file.fileRef.orEmpty())
                        .put("source", file.source)
                        .also { item ->
                            if (file.bytes.isNotEmpty()) {
                                item.put("bodyBase64", Base64.encode(file.bytes))
                            }
                        }
                )
            }
        }

    private fun List<PlatformFile>.toFeedbackDraftPromotionFilesJson(): JSONArray =
        JSONArray().also { array ->
            forEach { file ->
                array.put(
                    JSONObject()
                        .put("id", file.id)
                        .put("fileName", file.fileName)
                        .put("mimeType", file.mimeType.orEmpty())
                        .put("fileRef", file.fileRef.orEmpty())
                        .put("previewUri", file.previewUri.orEmpty())
                )
            }
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
        val response = settingsCapability(JSONObject().put("action", action))
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

internal fun handsUpdateBridgeError(bodyText: String?): ScreenError {
    val body =
        bodyText
            ?.takeIf { it.isNotBlank() }
            ?.let { payload ->
                runCatching {
                    bridgeAuthJson.decodeFromString(HandsUpdateBridgeErrorBody.serializer(), payload)
                }.getOrNull()
            }
    return ScreenError(
        code = body?.code?.trim()?.takeIf { it.isNotBlank() } ?: "settings_hands_update_failed",
        serverMessage =
            body
                ?.takeIf { it.status == "error" }
                ?.message
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    )
}

internal fun systemNotificationPermissionSnapshot(
    code: String,
    message: String
): SettingsMobileActionSnapshot? {
    val statusLabel =
        when (code) {
            "permission_authorized" -> "Allowed"
            "permission_provisional" -> "Provisional"
            "permission_ephemeral" -> "Allowed"
            "permission_denied" -> "Denied"
            "permission_not_determined" -> "Not requested"
            else -> return null
        }
    return SettingsMobileActionSnapshot(
        available = code != "permission_denied",
        statusLabel = statusLabel,
        detail = message.ifBlank { statusLabel }
    )
}

internal fun JSONObject.toStorageSnapshot(): SharedSettingsStorageSnapshot {
    // Android sends "rowsJson" (string bridge, #476); OHOS/iOS still send
    // structured "rows". Same dual-accept shape as entriesJson (#484).
    val rowsJson =
        optJSONArray("rows")
            ?: optString("rowsJson")
                .takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
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

internal fun JSONObject.toDeveloperSnapshot(): SharedSettingsDeveloperSnapshot {
    // Android sends "optionsJson" (string bridge, #476); OHOS/iOS still
    // send structured "options". Same dual-accept shape as entriesJson.
    val optionsJson =
        optJSONArray("options")
            ?: optString("optionsJson")
                .takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
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
    val updateCheckSupported = optBoolean("updateCheckSupported", false)
    return SharedSettingsDeveloperSnapshot(
        environmentKey = optString("environmentKey"),
        environmentLabel = optString("environmentLabel"),
        apiBaseUrl = optString("apiBaseUrl").ifBlank { SLOCK_API_BASE_URL },
        socketBaseUrl = optString("socketBaseUrl").ifBlank { SLOCK_SOCKET_BASE_URL },
        webUrl = optString("webUrl"),
        updateCheck =
            SharedSettingsUpdateCheckSnapshot(
                supported = updateCheckSupported,
                transactionState =
                    SharedSettingsUpdateTransactionState.fromWire(
                        optString("updateTransactionState")
                    ),
                transactionTargetVersionCode = optLong("updateTransactionTargetVersionCode", 0L),
                transactionRetryable = optBoolean("updateTransactionRetryable", false),
                statusLabel =
                    optString("updateCheckStatusLabel").ifBlank {
                        if (updateCheckSupported) "Check" else "Disabled"
                    },
                detail =
                    optString("updateCheckDetail").ifBlank {
                        if (updateCheckSupported) {
                            "Checks for a newer Android build and opens the installer when available."
                        } else {
                            "This build does not expose in-app update checks."
                        }
                    },
                actionLabel = optString("updateCheckActionLabel"),
                updateAvailable = optBoolean("updateAvailable", false),
                currentVersionLabel = optString("currentVersionLabel"),
                currentVersionCode = optLong("currentVersionCode", 0L),
                latestVersionLabel = optString("latestVersionLabel"),
                latestVersionCode = optLong("latestVersionCode", 0L),
                latestChangelog = optString("latestChangelog"),
                downloadUrl = optString("updateDownloadUrl"),
                downloadProgressToken = optString("updateDownloadProgressToken"),
                downloadSizesResolved = optBoolean("updateDownloadSizesResolved", false),
                fullDownloadSizeBytes = optLong("updateFullDownloadSizeBytes", 0L).coerceAtLeast(0L),
                patchDownloadSizeBytes = optLong("updatePatchDownloadSizeBytes", 0L).coerceAtLeast(0L),
                checkedAtLabel = optString("updateCheckedAtLabel"),
                errorCode = optString("updateCheckErrorCode")
            ),
        options = options.ifEmpty { sharedSettingsDefaultApiEnvironmentOptions() },
        feedback = optString("feedback").takeIf { it.isNotBlank() }
    )
}

internal fun JSONObject.toDiagnosticsHostSnapshot(): SharedSettingsDiagnosticsHostSnapshot {
    val entriesJson =
        optJSONArray("entries")
            ?: optString("entriesJson")
                .takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
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
    val readStateJson =
        optString("readStateV1DiagnosticJson")
            .takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
    val featureFlagOverridesJson =
        optString("diagnosticFeatureFlagOverridesJson")
            .takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
    val featureFlagOverrides =
        buildList {
            if (featureFlagOverridesJson != null) {
                for (index in 0 until featureFlagOverridesJson.length()) {
                    val json = featureFlagOverridesJson.optJSONObject(index) ?: continue
                    val key = json.optString("key").trim()
                    if (key.isBlank()) continue
                    add(
                        SharedSettingsDiagnosticFeatureFlagSnapshot(
                            key = key,
                            supported = json.optBoolean("supported", false),
                            scopeKind = json.optString("scopeKind").ifBlank { "unsupported" },
                            entitled = json.optBoolean("entitled", false),
                            clientOverride = json.optString("clientOverride").ifBlank { "Server" },
                            observedScope = json.optString("observedScope"),
                            boundScope = json.optString("boundScope"),
                            expiresAtMillis = json.optLong("expiresAtMillis", 0L).takeIf { it > 0L },
                            serverEffective = json.optNullableBoolean("serverEffective"),
                            finalEffective = json.optNullableBoolean("finalEffective"),
                            precedence = json.optString("precedence").ifBlank { "not_observed" },
                            exactScopeMatched = json.optBoolean("exactScopeMatched", false),
                            clearReason = json.optString("clearReason"),
                            evidence = json.optString("evidence"),
                        )
                    )
                }
            }
        }
    return SharedSettingsDiagnosticsHostSnapshot(
        entries = entries,
        readStateV1 =
            readStateJson?.let { json ->
                SharedSettingsReadStateV1DiagnosticSnapshot(
                    entitled = json.optBoolean("entitled", false),
                    clientOverride = json.optString("clientOverride").ifBlank { "Server" },
                    observedScope = json.optString("observedScope"),
                    boundScope = json.optString("boundScope"),
                    expiresAtMillis = json.optLong("expiresAtMillis", 0L).takeIf { it > 0L },
                    serverEffective = json.optNullableBoolean("serverEffective"),
                    finalEffective = json.optNullableBoolean("finalEffective"),
                    precedence = json.optString("precedence").ifBlank { "not_observed" },
                    exactScopeMatched = json.optBoolean("exactScopeMatched", false),
                    clearReason = json.optString("clearReason"),
                    selectedScope = json.optString("selectedScope"),
                    witnessGeneration = json.optLong("witnessGeneration", 0L).takeIf { it > 0L },
                    durableReadStateVersion = json.optLong("durableReadStateVersion", 0L).takeIf { it > 0L },
                    durableMaxReadSeq = json.optLong("durableMaxReadSeq", -1L).takeIf { it >= 0L },
                    latestAdjudication = json.optString("latestAdjudication"),
                    destinationPublication = json.optString("destinationPublication"),
                    barrierState = json.optString("barrierState"),
                    stalePublicationRejectedCount = json.optLong("stalePublicationRejectedCount", 0L),
                )
            } ?: SharedSettingsReadStateV1DiagnosticSnapshot(),
        featureFlagOverrides = featureFlagOverrides,
    )
}

private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    when (optString(key).trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

internal fun CoroutineScope.kuiklyBridgeCallContext(): CoroutineContext =
    coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext

internal interface SharedThreadNetworkApi {
    suspend fun channelMessages(
        threadId: String,
        limit: Int = 50,
        beforeSeq: Long? = null,
        afterSeq: Long? = null
    ): HomeNetworkResponse
    suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int = 50): HomeNetworkResponse
    suspend fun messageContext(messageId: String, channelId: String? = null): HomeNetworkResponse
    suspend fun sendMessage(
        channelId: String,
        body: String,
        asTask: Boolean = false,
        attachmentIds: List<String> = emptyList(),
        randomId: String? = null
    ): HomeNetworkResponse
    suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): HomeNetworkResponse
    suspend fun attachmentUrl(attachmentId: String, disposition: String? = null): HomeNetworkResponse
    suspend fun attachmentHtmlPreviewUrl(attachmentId: String): HomeNetworkResponse
    suspend fun attachmentComments(attachmentId: String): HomeNetworkResponse
    suspend fun sendAttachmentComment(attachmentId: String, body: String): HomeNetworkResponse
    suspend fun markChannelReadToSeq(channelId: String, seq: Long): HomeNetworkResponse
    suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse
    suspend fun saveMessage(messageId: String): HomeNetworkResponse
    suspend fun unsaveMessage(messageId: String): HomeNetworkResponse
    suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse
    suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse
    suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse
}
