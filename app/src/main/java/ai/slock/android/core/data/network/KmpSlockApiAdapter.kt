package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.RetrofitAuthRepository
import ai.slock.kmp.network.HttpClientConfig
import ai.slock.kmp.network.MultipartFile
import ai.slock.kmp.network.MultipartFormData
import ai.slock.kmp.network.TimeoutConfig
import ai.slock.kmp.network.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Response

class KmpSlockApiAdapter(
        private val store: AuthSessionStore,
        private val fallback: SlockApi,
        private val baseUrl: String = RetrofitAuthRepository.BASE_URL,
        private val tokenRefresher: AuthTokenRefresher =
                AuthTokenRefresher(
                        store = store,
                        api = RetrofitAuthRepository.createAuthApi(baseUrl)
                ),
        private val httpClient: HttpClient =
                createHttpClient(
                        HttpClientConfig(
                                timeout = TimeoutConfig()
                        )
                ),
        private val logger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(TAG, message)
        },
        private val warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(TAG, message)
        }
) : SlockApi {
    override suspend fun settingsAccount(): Response<ResponseBody> = get("auth/me")

    override suspend fun updateSettingsAccount(body: RequestBody): Response<ResponseBody> =
            patch("auth/me", body)

    override suspend fun settingsSessions(): Response<ResponseBody> = get("auth/sessions")

    override suspend fun revokeSettingsSession(sessionId: String): Response<ResponseBody> =
            delete("auth/sessions/$sessionId")

    override suspend fun settingsMachines(serverId: String): Response<ResponseBody> =
            get("servers/$serverId/machines")

    override suspend fun settingsMachineWorkspaces(
            serverId: String,
            machineId: String
    ): Response<ResponseBody> = get("servers/$serverId/machines/$machineId/workspaces")

    override suspend fun settingsAgents(): Response<ResponseBody> = get("agents")

    override suspend fun updateAgent(agentId: String, body: RequestBody): Response<ResponseBody> =
            patch("agents/$agentId", body)

    override suspend fun channels(archived: String?): Response<ResponseBody> =
            get("channels", listOfNotNull(archived?.let { "archived" to it }))

    override suspend fun serverMembers(serverId: String): Response<ResponseBody> =
            get("servers/$serverId/members")

    override suspend fun updateSettingsServerProfile(
            serverId: String,
            body: RequestBody
    ): Response<ResponseBody> = patch("servers/$serverId", body)

    override suspend fun settingsServerUsage(serverId: String): Response<ResponseBody> =
            get("servers/$serverId/usage")

    override suspend fun settingsBillingSubscription(): Response<ResponseBody> =
            get("billing/subscription")

    override suspend fun createSettingsServerInvite(
            serverId: String,
            body: RequestBody
    ): Response<ResponseBody> = post("servers/$serverId/invites", body)

    override suspend fun settingsServerOnboardingSettings(serverId: String): Response<ResponseBody> =
            get("servers/$serverId/onboarding-settings")

    override suspend fun updateSettingsServerOnboardingSettings(
            serverId: String,
            body: RequestBody
    ): Response<ResponseBody> = patch("servers/$serverId/onboarding-settings", body)

    override suspend fun deleteSettingsServer(serverId: String): Response<ResponseBody> =
            delete("servers/$serverId")

    override suspend fun memberProfile(memberId: String): Response<ResponseBody> =
            get("members/$memberId/profile")

    override suspend fun agentDirectMessages(agentId: String): Response<ResponseBody> =
            get("agents/$agentId/agent-dms")

    override suspend fun reminders(ownerAgentId: String, status: String): Response<ResponseBody> =
            get("reminders", listOf("ownerAgentId" to ownerAgentId, "status" to status))

    override suspend fun agentActivityLog(agentId: String, limit: Int): Response<ResponseBody> =
            get("agents/$agentId/activity-log", listOf("limit" to limit.toString()))

    override suspend fun channelMembers(channelId: String): Response<ResponseBody> =
            get("channels/$channelId/members")

    override suspend fun createChannel(body: RequestBody): Response<ResponseBody> =
            post("channels", body)

    override suspend fun joinChannel(channelId: String): Response<ResponseBody> =
            post("channels/$channelId/join")

    override suspend fun archiveChannel(channelId: String): Response<ResponseBody> =
            post("channels/$channelId/archive")

    override suspend fun deleteChannel(channelId: String): Response<ResponseBody> =
            delete("channels/$channelId")

    override suspend fun addChannelMember(
            channelId: String,
            body: RequestBody
    ): Response<ResponseBody> = post("channels/$channelId/members", body)

    override suspend fun removeChannelMember(
            channelId: String,
            kind: String,
            memberId: String
    ): Response<ResponseBody> = delete("channels/$channelId/members/$kind/$memberId")

    override suspend fun directMessages(): Response<ResponseBody> = get("channels/dm")

    override suspend fun openDirectMessage(body: RequestBody): Response<ResponseBody> =
            post("channels/dm", body)

    override suspend fun channelUnreadCounts(): Response<ResponseBody> = get("channels/unread")

    override suspend fun markChannelReadToSeq(
            channelId: String,
            body: RequestBody
    ): Response<ResponseBody> = post("channels/$channelId/read", body)

    override suspend fun markChannelReadAll(channelId: String): Response<ResponseBody> =
            post("channels/$channelId/read-all")

    override suspend fun markChannelUnread(channelId: String): Response<ResponseBody> =
            post("channels/$channelId/unread")

    override suspend fun inbox(filter: String, limit: Int, offset: Int): Response<ResponseBody> =
            get(
                    "channels/inbox",
                    listOf("filter" to filter, "limit" to limit.toString(), "offset" to offset.toString())
            )

    override suspend fun markInboxReadAll(): Response<ResponseBody> =
            post("channels/inbox/read-all")

    override suspend fun serverTasks(): Response<ResponseBody> = get("tasks/server")

    override suspend fun channelTasks(channelId: String): Response<ResponseBody> =
            get("tasks/channel/$channelId")

    override suspend fun claimTask(taskId: String): Response<ResponseBody> =
            patch("tasks/$taskId/claim")

    override suspend fun updateTaskStatus(
            taskId: String,
            body: RequestBody
    ): Response<ResponseBody> = patch("tasks/$taskId/status", body)

    override suspend fun saved(limit: Int, offset: Int): Response<ResponseBody> =
            get("channels/saved", listOf("limit" to limit.toString(), "offset" to offset.toString()))

    override suspend fun saveMessage(messageId: String): Response<ResponseBody> =
            post("messages/$messageId/save")

    override suspend fun unsaveMessage(messageId: String): Response<ResponseBody> =
            delete("messages/$messageId/save")

    override suspend fun searchMessages(
            query: String,
            limit: Int,
            offset: Int
    ): Response<ResponseBody> =
            get(
                    "messages/search",
                    listOf("q" to query, "limit" to limit.toString(), "offset" to offset.toString())
            )

    override suspend fun channelMessages(
            channelId: String,
            limit: Int,
            beforeSeq: Long?
    ): Response<ResponseBody> =
            get(
                    "messages/channel/$channelId",
                    listOfNotNull(
                            "limit" to limit.toString(),
                            beforeSeq?.let { "before" to it.toString() }
                    )
            )

    override suspend fun messageContext(messageId: String): Response<ResponseBody> =
            get("messages/context/$messageId")

    override suspend fun messageSync(
            sinceSeq: Long,
            limit: Int,
            channelId: String?
    ): Response<ResponseBody> =
            get(
                    "messages/sync",
                    listOfNotNull(
                            "since_seq" to sinceSeq.toString(),
                            "limit" to limit.toString(),
                            channelId?.let { "channel_id" to it }
                    )
            )

    override suspend fun sendMessage(body: RequestBody): Response<ResponseBody> =
            post("messages", body)

    override suspend fun uploadAttachments(
            channelId: RequestBody,
            files: List<MultipartBody.Part>
    ): Response<ResponseBody> =
            multipart(
                    path = "attachments/upload",
                    formData =
                            MultipartFormData(
                                    fields = mapOf("channelId" to channelId.bodyAsString()),
                                    files = files.map { it.toKmpMultipartFile() }
                            )
            )

    override suspend fun attachmentUrl(attachmentId: String): Response<ResponseBody> =
            get("attachments/$attachmentId/url")

    override suspend fun channelThreads(channelId: String, limit: Int): Response<ResponseBody> =
            get("channels/$channelId/threads", listOf("limit" to limit.toString()))

    override suspend fun createOrOpenThread(
            channelId: String,
            body: RequestBody
    ): Response<ResponseBody> = post("channels/$channelId/threads", body)

    override suspend fun followedThreads(limit: Int): Response<ResponseBody> =
            get("channels/threads/followed", listOf("limit" to limit.toString()))

    override suspend fun followThread(body: RequestBody): Response<ResponseBody> =
            post("channels/threads/follow", body)

    override suspend fun unfollowThread(body: RequestBody): Response<ResponseBody> =
            post("channels/threads/unfollow", body)

    override suspend fun markThreadDone(body: RequestBody): Response<ResponseBody> =
            post("channels/threads/done", body)

    override suspend fun markThreadUndone(body: RequestBody): Response<ResponseBody> =
            post("channels/threads/undone", body)

    override suspend fun serverUnreadSummary(): Response<ResponseBody> =
            get("servers/unread-summary")

    private suspend fun get(
            path: String,
            query: List<Pair<String, String>> = emptyList()
    ): Response<ResponseBody> = request(HttpMethod.Get, path, query)

    private suspend fun post(path: String, body: RequestBody? = null): Response<ResponseBody> =
            request(HttpMethod.Post, path, body = body?.bodyAsString())

    private suspend fun patch(path: String, body: RequestBody? = null): Response<ResponseBody> =
            request(HttpMethod.Patch, path, body = body?.bodyAsString())

    private suspend fun delete(path: String): Response<ResponseBody> =
            request(HttpMethod.Delete, path)

    private suspend fun multipart(
            path: String,
            formData: MultipartFormData,
            alreadyRetried: Boolean = false
    ): Response<ResponseBody> {
        val session = store.readSession()
        val failedAccessToken = session?.accessToken
        val response = executeMultipart(path, formData, session)
        if (response.status.value != 401 || alreadyRetried || failedAccessToken.isNullOrBlank()) {
            return response.toRetrofitResponse()
        }
        val refreshed = tokenRefresher.refreshIfCurrent(failedAccessToken)
                ?: return response.toRetrofitResponse()
        logger("kmp_api_401_retry path=$path multipart=true")
        return executeMultipart(path, formData, refreshed).toRetrofitResponse()
    }

    private suspend fun executeMultipart(
            path: String,
            formData: MultipartFormData,
            session: AuthSession?
    ): HttpResponse =
            try {
                httpClient.submitFormWithBinaryData(
                        url = absoluteUrl(path),
                        formData =
                                io.ktor.client.request.forms.formData {
                                    formData.fields.forEach { (key, value) ->
                                        append(key, value)
                                    }
                                    formData.files.forEach { file ->
                                        append(
                                                key = file.name,
                                                value = file.bytes,
                                                headers =
                                                        Headers.build {
                                                            append(HttpHeaders.ContentType, file.mimeType)
                                                            append(
                                                                    HttpHeaders.ContentDisposition,
                                                                    "filename=\"${file.fileName}\""
                                                            )
                                                        }
                                        )
                                    }
                                }
                ) {
                    applyAuthHeaders(session)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                throw io
            } catch (error: Exception) {
                warningLogger("kmp_api_failed path=$path method=multipart reason=${error::class.java.simpleName}")
                throw IOException(error)
            }

    private suspend fun request(
            method: HttpMethod,
            path: String,
            query: List<Pair<String, String>> = emptyList(),
            body: String? = null,
            alreadyRetried: Boolean = false
    ): Response<ResponseBody> {
        val session = store.readSession()
        val failedAccessToken = session?.accessToken
        val response = executeKtor(method, path, query, body, session)
        if (response.status.value != 401 || alreadyRetried || failedAccessToken.isNullOrBlank()) {
            return response.toRetrofitResponse()
        }
        val refreshed = tokenRefresher.refreshIfCurrent(failedAccessToken)
                ?: return response.toRetrofitResponse()
        logger("kmp_api_401_retry path=$path")
        return executeKtor(method, path, query, body, refreshed).toRetrofitResponse()
    }

    private suspend fun executeKtor(
            method: HttpMethod,
            path: String,
            query: List<Pair<String, String>>,
            body: String?,
            session: AuthSession?
    ): HttpResponse =
            try {
                when (method) {
                    HttpMethod.Get -> httpClient.get { buildRequest(path, query, body, session) }
                    HttpMethod.Post -> httpClient.post { buildRequest(path, query, body, session) }
                    HttpMethod.Patch -> httpClient.patch { buildRequest(path, query, body, session) }
                    HttpMethod.Delete -> httpClient.delete { buildRequest(path, query, body, session) }
                    else -> throw IOException("Unsupported HTTP method ${method.value}")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                throw io
            } catch (error: Exception) {
                warningLogger("kmp_api_failed path=$path method=${method.value} reason=${error::class.java.simpleName}")
                throw IOException(error)
            }

    private fun io.ktor.client.request.HttpRequestBuilder.buildRequest(
            path: String,
            query: List<Pair<String, String>>,
            body: String?,
            session: AuthSession?
    ) {
        url(absoluteUrl(path, query))
        applyAuthHeaders(session)
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuthHeaders(
            session: AuthSession?
    ) {
        headers.remove(HttpHeaders.Authorization)
        headers.remove(HEADER_SERVER_ID)
        session?.toAuthHeaders()?.forEach { (key, value) ->
            header(key, value)
        }
    }

    private fun absoluteUrl(
            path: String,
            query: List<Pair<String, String>> = emptyList()
    ): String {
        val root = baseUrl.trimEnd('/')
        val encodedQuery =
                query.joinToString("&") { (name, value) ->
                    "${name.urlEncode()}=${value.urlEncode()}"
                }
        return buildString {
            append(root)
            append('/')
            append(path.trimStart('/'))
            if (encodedQuery.isNotBlank()) {
                append('?')
                append(encodedQuery)
            }
        }
    }

    private suspend fun HttpResponse.toRetrofitResponse(): Response<ResponseBody> {
        val raw = body<String>()
        val responseBody = raw.toResponseBody()
        return if (status.value in 200..299) {
            Response.success(responseBody)
        } else {
            Response.error(status.value, responseBody)
        }
    }

    private fun RequestBody.bodyAsString(): String {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun MultipartBody.Part.toKmpMultipartFile(): MultipartFile {
        val body = body
        val headers = headers
        val disposition = headers?.get("Content-Disposition").orEmpty()
        val name = disposition.dispositionParameter("name") ?: "files"
        val fileName = disposition.dispositionParameter("filename") ?: "attachment"
        val buffer = Buffer()
        body.writeTo(buffer)
        return MultipartFile(
                name = name,
                fileName = fileName,
                mimeType = body.contentType()?.toString() ?: "application/octet-stream",
                bytes = buffer.readByteArray()
        )
    }

    private fun String.dispositionParameter(name: String): String? {
        val pattern = Regex("""(?:^|;\s*)${Regex.escape(name)}="([^"]*)"""")
        return pattern.find(this)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun String.urlEncode(): String =
            java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

    private companion object {
        const val TAG = "SlockKmpApi"
        const val HEADER_SERVER_ID = "X-Server-Id"
    }
}

private fun AuthSession.toAuthHeaders(): Map<String, String> =
        buildMap {
            put("Authorization", "Bearer $accessToken")
            currentServerId?.let { put("X-Server-Id", it) }
        }
