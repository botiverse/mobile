package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.MultipartFile
import build.raft.app.network.SlockNetworkModule
import build.raft.app.network.SlockRequestPriority
import build.raft.app.network.SlockRequestScheduler
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeSource

class KuiklySlockNetworkClient(
    private val sessionStore: SlockAuthSessionStore,
    private val networkModule: SlockNetworkModule,
    private val baseUrl: String = SLOCK_API_BASE_URL,
    private val warningLogger: (String) -> Unit = {},
    private val requestScheduler: SlockRequestScheduler = SlockRequestScheduler()
) : SlockNetworkClient {
    private val authMiddleware =
        SlockNetworkAuthMiddleware(
            sessionStore = sessionStore,
            authApi = SlockAuthApiClient(KuiklySlockAuthTransport(networkModule, baseUrl))
        )

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse =
        requestScheduler.run(
            priority = priority.toNetworkPriority(),
            key = path,
            reason = method
        ) {
            val startedAt = TimeSource.Monotonic.markNow()
            val category = path.networkDiagnosticsCategory()
            val normalizedPath = path.networkDiagnosticsPath()
            val response =
                authMiddleware.request(serverIdOverride) { auth ->
                    networkModule.requestRaw(
                        url = absoluteUrl(path, query),
                        method = method.uppercase(),
                        headers = authHeaders(auth, contentType = bodyText?.let { "application/json" }),
                        bodyText = bodyText,
                        bodyBytes = null
                    )
                }
            warningLogger(
                "endpoint category=$category path=$normalizedPath method=${method.uppercase()} " +
                    "queryKeys=${query.networkDiagnosticsQueryKeys()} " +
                    "requestBytes=${bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    "result=${response.networkDiagnosticsResult()}"
            )
            response
        }

    override suspend fun requestRawAbsoluteUrl(
        priority: DataRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?
    ): HomeNetworkResponse =
        requestScheduler.run(
            priority = priority.toNetworkPriority(),
            key = url.substringBefore('?'),
            reason = "raw:${method.uppercase()}"
        ) {
            val startedAt = TimeSource.Monotonic.markNow()
            val response =
                networkModule.requestRaw(
                    url = url,
                    method = method.uppercase(),
                    headers =
                        JSONObject().apply {
                            headers.forEach { (name, value) -> put(name, value) }
                        },
                    bodyText = bodyText,
                    bodyBytes = null
                )
            warningLogger(
                "endpoint category=raw_absolute_url path=${url.substringBefore('?')} method=${method.uppercase()} " +
                    "requestBytes=${bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    "result=${response.networkDiagnosticsResult()}"
            )
            response
        }

    override suspend fun uploadAttachments(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>
    ): HomeNetworkResponse =
        requestScheduler.run(
            priority = priority.toNetworkPriority(),
            key = "attachments/upload",
            reason = "multipart"
        ) {
            val startedAt = TimeSource.Monotonic.markNow()
            val boundary = "slock-${channelId.value}-${files.size}"
            val response =
                authMiddleware.request { auth ->
                    val headers =
                        authHeaders(
                            auth = auth,
                            contentType = "multipart/form-data; boundary=$boundary"
                        )
                    val uploadFiles =
                        networkModule.resolvePlatformFileRefs(files)
                            .getOrElse { return@request it }
                    networkModule.requestRaw(
                        url = absoluteUrl("attachments/upload"),
                        method = "POST",
                        headers = headers,
                        bodyBytes =
                            buildMultipartBody(
                                boundary = boundary,
                                fields = mapOf("channelId" to channelId.value),
                                files = uploadFiles
                        ),
                        bodyText = null
                    )
                }
            warningLogger(
                "endpoint category=attachments_upload path=attachments/upload method=POST " +
                    "fileCount=${files.size} requestBytes=${files.uploadByteEstimate()} " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    "result=${response.networkDiagnosticsResult()}"
            )
            response
        }

    override suspend fun uploadAccountAvatar(
        priority: DataRequestPriority,
        file: MultipartFile
    ): HomeNetworkResponse =
        requestScheduler.run(
            priority = priority.toNetworkPriority(),
            key = "auth/me/avatar",
            reason = "multipart"
        ) {
            val startedAt = TimeSource.Monotonic.markNow()
            val boundary = "slock-account-avatar"
            val response =
                authMiddleware.request { auth ->
                    val headers =
                        authHeaders(
                            auth = auth,
                            contentType = "multipart/form-data; boundary=$boundary"
                        )
                    val uploadFiles =
                        networkModule.resolvePlatformFileRefs(listOf(file))
                            .getOrElse { return@request it }
                    networkModule.requestRaw(
                        url = absoluteUrl("auth/me/avatar"),
                        method = "POST",
                        headers = headers,
                        bodyBytes =
                            buildMultipartBody(
                                boundary = boundary,
                                fields = emptyMap(),
                                files = uploadFiles
                            ),
                        bodyText = null
                    )
                }
            warningLogger(
                "endpoint category=account_avatar_upload path=auth/me/avatar method=POST " +
                    "fileCount=1 requestBytes=${file.bytes.size} " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    "result=${response.networkDiagnosticsResult()}"
            )
            response
        }

    override suspend fun uploadAgentAvatar(
        priority: DataRequestPriority,
        agentId: MemberId,
        file: MultipartFile
    ): HomeNetworkResponse =
        requestScheduler.run(
            priority = priority.toNetworkPriority(),
            key = "agents/${agentId.value}/avatar",
            reason = "multipart"
        ) {
            val startedAt = TimeSource.Monotonic.markNow()
            val boundary = "slock-agent-avatar-${agentId.value}"
            val response =
                authMiddleware.request { auth ->
                    val headers =
                        authHeaders(
                            auth = auth,
                            contentType = "multipart/form-data; boundary=$boundary"
                        )
                    val uploadFiles =
                        networkModule.resolvePlatformFileRefs(listOf(file))
                            .getOrElse { return@request it }
                    networkModule.requestRaw(
                        url = absoluteUrl("agents/${agentId.value}/avatar"),
                        method = "POST",
                        headers = headers,
                        bodyBytes =
                            buildMultipartBody(
                                boundary = boundary,
                                fields = emptyMap(),
                                files = uploadFiles
                            ),
                        bodyText = null
                    )
                }
            warningLogger(
                "endpoint category=agent_avatar_upload path=agents/${agentId.value}/avatar method=POST " +
                    "fileCount=1 requestBytes=${file.bytes.size} " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    "result=${response.networkDiagnosticsResult()}"
            )
            response
        }

    override suspend fun uploadConnectedAppLogo(
        priority: DataRequestPriority,
        clientRecordId: String,
        file: MultipartFile
    ): HomeNetworkResponse =
        requestScheduler.run(
            priority = priority.toNetworkPriority(),
            key = "integrations/clients/$clientRecordId/logo",
            reason = "multipart"
        ) {
            val startedAt = TimeSource.Monotonic.markNow()
            val boundary = "slock-connected-app-logo-$clientRecordId"
            val response =
                authMiddleware.request { auth ->
                    val headers =
                        authHeaders(
                            auth = auth,
                            contentType = "multipart/form-data; boundary=$boundary"
                        )
                    val uploadFiles =
                        networkModule.resolvePlatformFileRefs(listOf(file))
                            .getOrElse { return@request it }
                    networkModule.requestRaw(
                        url = absoluteUrl("integrations/clients/$clientRecordId/logo"),
                        method = "POST",
                        headers = headers,
                        bodyBytes =
                            buildMultipartBody(
                                boundary = boundary,
                                fields = emptyMap(),
                                files = uploadFiles
                            ),
                        bodyText = null
                    )
                }
            warningLogger(
                "endpoint category=connected_app_logo_upload path=integrations/clients/:id/logo method=POST " +
                    "fileCount=1 requestBytes=${file.bytes.size} " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    "result=${response.networkDiagnosticsResult()}"
            )
            response
        }

    private fun absoluteUrl(path: String, query: List<Pair<String, String>> = emptyList()): String {
        val url = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
        if (query.isEmpty()) return url
        return "$url?${query.joinToString("&") { (key, value) -> "${key.percentEncode()}=${value.percentEncode()}" }}"
    }

    private fun authHeaders(
        auth: SlockAuthenticatedRequestContext,
        contentType: String?
    ): JSONObject =
        JSONObject().apply {
            put("Authorization", auth.authorization)
            put(HEADER_SERVER_ID, auth.serverId)
            contentType?.let { put("Content-Type", it) }
        }

    private fun DataRequestPriority.toNetworkPriority(): SlockRequestPriority =
        when (this) {
            DataRequestPriority.Foreground -> SlockRequestPriority.Foreground
            DataRequestPriority.Visible -> SlockRequestPriority.Visible
            DataRequestPriority.Background -> SlockRequestPriority.Background
        }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun SlockNetworkModule.requestRaw(
        url: String,
        method: String,
        headers: JSONObject,
        bodyBytes: ByteArray?,
        bodyText: String? = null
    ): HomeNetworkResponse {
        val response =
            request(
                JSONObject().apply {
                    put("url", url)
                    put("method", method)
                    put("headers", headers)
                    bodyText?.let { put("bodyText", it) }
                    bodyBytes?.let { put("bodyBase64", Base64.Default.encode(it)) }
                    put("timeoutMillis", 30_000)
                }
            )
        val body = response.bodyText
        val code = response.code
        return if (code in 200..299) {
            HomeNetworkResponse(code = code, bodyText = body, errorBodyText = null)
        } else {
            HomeNetworkResponse(
                code = code,
                bodyText = null,
                errorBodyText = response.errorBodyText.ifBlank { response.message }
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun SlockNetworkModule.resolvePlatformFileRefs(files: List<MultipartFile>): ResolvedPlatformFiles {
        val resolved = mutableListOf<MultipartFile>()
        files.forEach { file ->
            if (file.fileRef == null || file.bytes.isNotEmpty()) {
                resolved += file
                return@forEach
            }
            val response = readPlatformFile(file.fileRef)
            if (response.code !in 200..299 || response.bodyBase64.isBlank()) {
                return ResolvedPlatformFiles.Error(
                    HomeNetworkResponse(
                        code = 400,
                        bodyText = null,
                        errorBodyText =
                            platformFileReadErrorBody(
                                errorCode = response.errorCode,
                                message = response.message
                            )
                    )
                )
            }
            val bytes =
                runCatching { Base64.Default.decode(response.bodyBase64) }
                    .getOrNull()
                    ?: return ResolvedPlatformFiles.Error(
                        HomeNetworkResponse(
                            code = 400,
                            bodyText = null,
                            errorBodyText = """{"error":"attachment_read_failed","message":"invalid file bytes"}"""
                        )
                    )
            resolved +=
                file.copy(
                    bytes = bytes,
                    fileRef = null,
                    sizeBytes = file.sizeBytes ?: response.sizeBytes ?: bytes.size.toLong()
                )
        }
        return ResolvedPlatformFiles.Content(resolved)
    }
}

private fun platformFileReadErrorBody(errorCode: String, message: String): String =
    JSONObject().apply {
        put("error", errorCode.ifBlank { "attachment_read_failed" })
        put("message", message)
    }.toString()

internal class KuiklySlockAuthTransport(
    private val networkModule: SlockNetworkModule,
    private val baseUrl: String = SLOCK_API_BASE_URL
) : SlockAuthTransport {
    override suspend fun login(bodyText: String): AuthNetworkResponse =
        request(path = "auth/login", method = "POST", bodyText = bodyText)

    override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse =
        request(path = "auth/${provider.trim().lowercase()}/complete", method = "POST", bodyText = bodyText)

    override suspend fun startMobileOAuth(bodyText: String, authorization: String?): AuthNetworkResponse =
        request(path = "auth/mobile/oauth/start", method = "POST", authorization = authorization, bodyText = bodyText)

    override suspend fun completeMobileOAuth(bodyText: String, authorization: String?): AuthNetworkResponse =
        request(path = "auth/mobile/oauth/complete", method = "POST", authorization = authorization, bodyText = bodyText)

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
        bodyText: String? = null
    ): AuthNetworkResponse {
        val response =
            networkModule.request(
                JSONObject().apply {
                    put("url", "${baseUrl.trimEnd('/')}/${path.trimStart('/')}")
                    put("method", method)
                    put("headers", JSONObject().apply {
                        authorization?.let { put("Authorization", it) }
                        bodyText?.let { put("Content-Type", "application/json") }
                    })
                    bodyText?.let { put("bodyText", it) }
                    put("timeoutMillis", 30_000)
                }
            )
        return AuthNetworkResponse(
            code = response.code,
            bodyText = response.bodyText.takeUnless { it.isBlank() },
            errorBodyText = response.errorBodyText.takeUnless { it.isBlank() }
                ?: response.message.takeUnless { it.isBlank() }
        )
    }
}

private sealed class ResolvedPlatformFiles {
    data class Content(val files: List<MultipartFile>) : ResolvedPlatformFiles()
    data class Error(val response: HomeNetworkResponse) : ResolvedPlatformFiles()
}

private inline fun ResolvedPlatformFiles.getOrElse(onError: (HomeNetworkResponse) -> List<MultipartFile>): List<MultipartFile> =
    when (this) {
        is ResolvedPlatformFiles.Content -> files
        is ResolvedPlatformFiles.Error -> onError(response)
    }

internal fun buildMultipartBody(
    boundary: String,
    fields: Map<String, String>,
    files: List<MultipartFile>
): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    fields.forEach { (name, value) ->
        chunks += "--$boundary\r\n".encodeToByteArray()
        chunks += "Content-Disposition: form-data; name=\"$name\"\r\n\r\n".encodeToByteArray()
        chunks += value.encodeToByteArray()
        chunks += "\r\n".encodeToByteArray()
    }
    files.forEach { file ->
        chunks += "--$boundary\r\n".encodeToByteArray()
        chunks +=
            (
                "Content-Disposition: form-data; name=\"${file.name}\"; " +
                    "filename=\"${file.fileName.sanitizeMultipartFileName()}\"\r\n"
            ).encodeToByteArray()
        chunks += "Content-Type: ${file.mimeType}\r\n\r\n".encodeToByteArray()
        chunks += file.bytes
        chunks += "\r\n".encodeToByteArray()
    }
    chunks += "--$boundary--\r\n".encodeToByteArray()
    return chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
}

private fun List<MultipartFile>.uploadByteEstimate(): Long =
    sumOf { file -> file.bytes.size.toLong().takeIf { it > 0L } ?: file.sizeBytes ?: 0L }

private fun String.sanitizeMultipartFileName(): String =
    replace("\"", "'").replace("\r", "").replace("\n", "")

private fun String.networkDiagnosticsCategory(): String {
    val normalized = trim().trimStart('/')
    return when {
        normalized.startsWith("channels/dm") -> "direct_messages"
        normalized.startsWith("channels/threads/followed") -> "followed_threads"
        normalized.startsWith("channels/threads/follow") -> "thread_follow"
        normalized.startsWith("channels/threads") -> "thread_actions"
        normalized.startsWith("channels/unread") -> "channel_unread_counts"
        normalized.startsWith("channels/inbox/read-all") -> "inbox_read_all"
        normalized.startsWith("channels/") && normalized.endsWith("/read-all") -> "channel_read_all"
        normalized.startsWith("channels/") && normalized.endsWith("/unread") -> "channel_unread"
        normalized.startsWith("channels/") && normalized.endsWith("/threads") -> "channel_threads"
        normalized.startsWith("channels/") && normalized.endsWith("/files") -> "channel_files"
        normalized.startsWith("messages/channel/") -> "messages_channel"
        normalized.startsWith("messages/context/") -> "messages_context"
        normalized.startsWith("messages/sync") -> "messages_sync"
        normalized.startsWith("channels") -> "channels"
        normalized.startsWith("servers/unread-summary") -> "server_unread_summary"
        normalized.startsWith("servers") -> "servers"
        normalized.startsWith("members") -> "members"
        normalized.startsWith("messages/search") -> "message_search"
        normalized.startsWith("messages") -> "messages"
        normalized.startsWith("saved") -> "saved_items"
        normalized.startsWith("attachments/upload") -> "attachments_upload"
        normalized.startsWith("tasks") -> "tasks"
        normalized.startsWith("settings") -> "settings"
        else -> normalized.substringBefore('/').ifBlank { "unknown" }
    }
}

private fun String.networkDiagnosticsPath(): String =
    trim().trimStart('/').ifBlank { "root" }

private fun List<Pair<String, String>>.networkDiagnosticsQueryKeys(): String =
    if (isEmpty()) {
        "none"
    } else {
        joinToString(",") { it.first }
    }

private fun HomeNetworkResponse.networkDiagnosticsResult(): String =
    when {
        code in 200..299 -> "success"
        code == 0 -> "network_error"
        code == 401 || code == 403 -> "auth_or_permission_error"
        else -> "http_error"
    }

private fun String.percentEncode(): String {
    val bytes = encodeToByteArray()
    val builder = StringBuilder(bytes.size)
    bytes.forEach { byte ->
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        if (
            char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '.' ||
                char == '_' ||
                char == '~'
        ) {
            builder.append(char)
        } else {
            builder.append('%')
            builder.append(value.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return builder.toString()
}
