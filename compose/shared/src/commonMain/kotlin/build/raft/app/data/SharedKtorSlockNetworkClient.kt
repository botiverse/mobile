package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.MultipartFile
import build.raft.app.network.SlockRequestPriority
import build.raft.app.network.SlockRequestScheduler
import build.raft.app.network.createSlockKtorHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

internal class SharedKtorSlockNetworkClient(
    private val sessionStore: SlockAuthSessionStore,
    private val baseUrl: String = SLOCK_API_BASE_URL,
    private val warningLogger: (String) -> Unit = {},
    private val requestScheduler: SlockRequestScheduler = SlockRequestScheduler(),
    private val platformFileResolver: SlockPlatformFileResolver? = null,
    private val foregroundHttpClient: HttpClient = createSlockKtorHttpClient(SlockRequestPriority.Foreground),
    private val visibleHttpClient: HttpClient = createSlockKtorHttpClient(SlockRequestPriority.Visible),
    private val backgroundHttpClient: HttpClient = createSlockKtorHttpClient(SlockRequestPriority.Background)
) : SlockNetworkClient {
    private val authMiddleware =
        SlockNetworkAuthMiddleware(
            sessionStore = sessionStore,
            authApi = SlockAuthApiClient(SharedKtorSlockAuthTransport(baseUrl, foregroundHttpClient, warningLogger))
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
            reason = method.uppercase()
        ) {
            val startedAt = TimeSource.Monotonic.markNow()
            val category = path.networkDiagnosticsCategory()
            val normalizedPath = path.networkDiagnosticsPath()
            val response =
                authMiddleware.request(serverIdOverride) { auth ->
                    executeKtor(
                        client = priority.toNetworkPriority().httpClient(),
                        url = absoluteUrl(path, query),
                        method = method.uppercase(),
                        headers =
                            buildMap {
                                put(HttpHeaders.Authorization, auth.authorization)
                                put(HEADER_SERVER_ID, auth.serverId)
                            },
                        bodyText = bodyText,
                        jsonBody = bodyText != null
                    )
                }
            warningLogger(
                "endpoint category=$category path=$normalizedPath method=${method.uppercase()} " +
                    "queryKeys=${query.networkDiagnosticsQueryKeys()} " +
                    "queryMeta=${query.networkDiagnosticsQueryMeta()} " +
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
                executeKtor(
                    client = priority.toNetworkPriority().httpClient(),
                    url = url,
                    method = method.uppercase(),
                    headers = headers,
                    bodyText = bodyText,
                    jsonBody = false
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
    ): HomeNetworkResponse {
        val resolvedFiles = files.resolvePlatformFileRefs().getOrElse { return it }
        return uploadMultipart(
            priority = priority,
            path = "attachments/upload",
            category = "attachments_upload",
            diagnosticPath = "attachments/upload",
            fields = mapOf("channelId" to channelId.value),
            files = resolvedFiles,
            requestBytes = resolvedFiles.uploadByteEstimate(),
            key = "attachments/upload"
        )
    }

    override suspend fun uploadAccountAvatar(
        priority: DataRequestPriority,
        file: MultipartFile
    ): HomeNetworkResponse {
        val resolvedFile = listOf(file).resolvePlatformFileRefs().getOrElse { return it }.single()
        return uploadMultipart(
            priority = priority,
            path = "auth/me/avatar",
            category = "account_avatar_upload",
            diagnosticPath = "auth/me/avatar",
            fields = emptyMap(),
            files = listOf(resolvedFile),
            requestBytes = resolvedFile.bytes.size.toLong(),
            key = "auth/me/avatar"
        )
    }

    override suspend fun uploadAgentAvatar(
        priority: DataRequestPriority,
        agentId: MemberId,
        file: MultipartFile
    ): HomeNetworkResponse {
        val resolvedFile = listOf(file).resolvePlatformFileRefs().getOrElse { return it }.single()
        val path = "agents/${agentId.value}/avatar"
        return uploadMultipart(
            priority = priority,
            path = path,
            category = "agent_avatar_upload",
            diagnosticPath = path,
            fields = emptyMap(),
            files = listOf(resolvedFile),
            requestBytes = resolvedFile.bytes.size.toLong(),
            key = path
        )
    }

    override suspend fun uploadConnectedAppLogo(
        priority: DataRequestPriority,
        clientRecordId: String,
        file: MultipartFile
    ): HomeNetworkResponse {
        val resolvedFile = listOf(file).resolvePlatformFileRefs().getOrElse { return it }.single()
        val path = "integrations/clients/$clientRecordId/logo"
        return uploadMultipart(
            priority = priority,
            path = path,
            category = "connected_app_logo_upload",
            diagnosticPath = "integrations/clients/:id/logo",
            fields = emptyMap(),
            files = listOf(resolvedFile),
            requestBytes = resolvedFile.bytes.size.toLong(),
            key = path
        )
    }

    private suspend fun List<MultipartFile>.resolvePlatformFileRefs(): KtorResolvedPlatformFiles {
        if (!requiresPlatformFileResolver()) return KtorResolvedPlatformFiles.Content(this)
        val resolver = platformFileResolver
            ?: return KtorResolvedPlatformFiles.Error(fileRefUnsupportedResponse())
        val resolved = mutableListOf<MultipartFile>()
        forEach { file ->
            val fileRef = file.fileRef
            if (fileRef == null || file.bytes.isNotEmpty()) {
                resolved += file
                return@forEach
            }
            val response = resolver.readFile(fileRef)
            if (!response.success) {
                return KtorResolvedPlatformFiles.Error(
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
            resolved +=
                file.copy(
                    bytes = response.bytes,
                    fileRef = null,
                    sizeBytes = file.sizeBytes ?: response.sizeBytes ?: response.bytes.size.toLong()
                )
        }
        return KtorResolvedPlatformFiles.Content(resolved)
    }

    private suspend fun uploadMultipart(
        priority: DataRequestPriority,
        path: String,
        category: String,
        diagnosticPath: String,
        fields: Map<String, String>,
        files: List<MultipartFile>,
        requestBytes: Long,
        key: String
    ): HomeNetworkResponse =
        requestScheduler.run(
            priority = priority.toNetworkPriority(),
            key = key,
            reason = "multipart"
        ) {
            val startedAt = TimeSource.Monotonic.markNow()
            val response =
                authMiddleware.request { auth ->
                    executeMultipartKtor(
                        client = priority.toNetworkPriority().httpClient(),
                        path = path,
                        fields = fields,
                        files = files,
                        headers =
                            mapOf(
                                HttpHeaders.Authorization to auth.authorization,
                                HEADER_SERVER_ID to auth.serverId
                            )
                    )
                }
            warningLogger(
                "endpoint category=$category path=$diagnosticPath method=POST " +
                    "fileCount=${files.size} requestBytes=$requestBytes " +
                    "responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} durationMs=${startedAt.elapsedNow().inWholeMilliseconds} " +
                    "result=${response.networkDiagnosticsResult()}"
            )
            response
        }

    private suspend fun executeKtor(
        client: HttpClient,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?,
        jsonBody: Boolean
    ): HomeNetworkResponse =
        try {
            client.request {
                url(url)
                this.method = HttpMethod(method)
                headers.forEach { (name, value) -> header(name, value) }
                if (bodyText != null) {
                    if (jsonBody) contentType(ContentType.Application.Json)
                    setBody(bodyText)
                }
            }.toHomeNetworkResponse()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger(
                "slock_shared_ktor_failed path=${url.substringBefore('?')} " +
                    "method=$method reason=${error.safeDiagnosticsName()}"
            )
            HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = error.message)
        }

    private suspend fun executeMultipartKtor(
        client: HttpClient,
        path: String,
        fields: Map<String, String>,
        files: List<MultipartFile>,
        headers: Map<String, String>
    ): HomeNetworkResponse =
        try {
            client.submitFormWithBinaryData(
                url = absoluteUrl(path),
                formData =
                    io.ktor.client.request.forms.formData {
                        fields.forEach { (key, value) -> append(key, value) }
                        files.forEach { file ->
                            append(
                                key = file.name,
                                value = file.bytes,
                                headers =
                                    Headers.build {
                                        append(HttpHeaders.ContentType, file.mimeType)
                                        append(
                                            HttpHeaders.ContentDisposition,
                                            "filename=\"${file.fileName.sanitizeMultipartFileName()}\""
                                        )
                                    }
                            )
                        }
                    }
            ) {
                headers.forEach { (name, value) -> header(name, value) }
            }.toHomeNetworkResponse()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger("slock_shared_ktor_failed path=$path method=multipart reason=${error.safeDiagnosticsName()}")
            HomeNetworkResponse(code = 0, bodyText = null, errorBodyText = error.message)
        }

    private fun SlockRequestPriority.httpClient(): HttpClient =
        when (this) {
            SlockRequestPriority.Foreground -> foregroundHttpClient
            SlockRequestPriority.Visible -> visibleHttpClient
            SlockRequestPriority.Background -> backgroundHttpClient
        }

    private fun absoluteUrl(path: String, query: List<Pair<String, String>> = emptyList()): String {
        val url = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
        if (query.isEmpty()) return url
        return "$url?${query.joinToString("&") { (key, value) -> "${key.percentEncode()}=${value.percentEncode()}" }}"
    }
}

class SharedKtorSlockAuthTransport(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val warningLogger: (String) -> Unit = {}
) : SlockAuthTransport {
    constructor(
        baseUrl: String = SLOCK_API_BASE_URL,
        warningLogger: (String) -> Unit = {}
    ) : this(
        baseUrl = baseUrl,
        httpClient = createSlockKtorHttpClient(SlockRequestPriority.Foreground),
        warningLogger = warningLogger
    )

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
    ): AuthNetworkResponse =
        try {
            val response =
                httpClient.request {
                    url("${baseUrl.trimEnd('/')}/${path.trimStart('/')}")
                    this.method = HttpMethod(method)
                    authorization?.let { header(HttpHeaders.Authorization, it) }
                    if (bodyText != null) {
                        contentType(ContentType.Application.Json)
                        setBody(bodyText)
                    }
                }
            response.toAuthNetworkResponse()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger("slock_auth_ktor_failed path=$path method=$method reason=${error.safeDiagnosticsName()}")
            AuthNetworkResponse(code = 0, bodyText = null, errorBodyText = error.message)
        }
}

private suspend fun HttpResponse.toHomeNetworkResponse(): HomeNetworkResponse {
    val body = bodyAsText()
    return if (status.value in 200..299) {
        HomeNetworkResponse(code = status.value, bodyText = body, errorBodyText = null)
    } else {
        HomeNetworkResponse(code = status.value, bodyText = null, errorBodyText = body)
    }
}

private suspend fun HttpResponse.toAuthNetworkResponse(): AuthNetworkResponse {
    val body = bodyAsText()
    return if (status.value in 200..299) {
        AuthNetworkResponse(code = status.value, bodyText = body.takeUnless { it.isBlank() }, errorBodyText = null)
    } else {
        AuthNetworkResponse(code = status.value, bodyText = null, errorBodyText = body.takeUnless { it.isBlank() })
    }
}

private fun DataRequestPriority.toNetworkPriority(): SlockRequestPriority =
    when (this) {
        DataRequestPriority.Foreground -> SlockRequestPriority.Foreground
        DataRequestPriority.Visible -> SlockRequestPriority.Visible
        DataRequestPriority.Background -> SlockRequestPriority.Background
    }

private fun List<MultipartFile>.requiresPlatformFileResolver(): Boolean =
    any { it.requiresPlatformFileResolver() }

private fun MultipartFile.requiresPlatformFileResolver(): Boolean =
    fileRef != null && bytes.isEmpty()

private fun fileRefUnsupportedResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = 501,
        bodyText = null,
        errorBodyText = """{"error":"file_ref_upload_requires_app_scope_file_resolver"}"""
    )

private sealed interface KtorResolvedPlatformFiles {
    data class Content(val files: List<MultipartFile>) : KtorResolvedPlatformFiles
    data class Error(val response: HomeNetworkResponse) : KtorResolvedPlatformFiles
}

private inline fun KtorResolvedPlatformFiles.getOrElse(onError: (HomeNetworkResponse) -> Nothing): List<MultipartFile> =
    when (this) {
        is KtorResolvedPlatformFiles.Content -> files
        is KtorResolvedPlatformFiles.Error -> onError(response)
    }

private fun platformFileReadErrorBody(errorCode: String, message: String): String =
    """{"error":"${errorCode.ifBlank { "attachment_read_failed" }.jsonString()}","message":"${message.jsonString()}"}"""

private fun List<MultipartFile>.uploadByteEstimate(): Long =
    sumOf { file -> file.bytes.size.toLong().takeIf { it > 0L } ?: file.sizeBytes ?: 0L }

private fun String.sanitizeMultipartFileName(): String =
    replace("\"", "'").replace("\r", "").replace("\n", "")

private fun String.jsonString(): String =
    buildString {
        this@jsonString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

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

private fun List<Pair<String, String>>.networkDiagnosticsQueryMeta(): String {
    val query = firstOrNull { it.first == "q" }?.second ?: return "none"
    val trimmed = query.trim()
    val wordCount =
        trimmed
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
    val flags =
        buildList {
            if (query.any { it.isWhitespace() }) add("space")
            if ('+' in query) add("plus")
            if ('#' in query) add("hash")
            if (query.any { it.code > 127 }) add("nonAscii")
        }
            .ifEmpty { listOf("plain") }
            .joinToString("|")
    return "qLen=${trimmed.length};qWords=$wordCount;qFlags=$flags"
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

private fun Throwable.safeDiagnosticsName(): String =
    this::class.simpleName ?: "Exception"
