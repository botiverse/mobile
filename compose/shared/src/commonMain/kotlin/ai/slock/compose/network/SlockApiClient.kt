package ai.slock.compose.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext

class SlockApiClient(
    private val baseUrl: String,
    private val httpClient: HttpClient = createHttpClient(HttpClientConfig(timeout = TimeoutConfig())),
    private val authTransport: AuthenticatedKtorTransport,
    private val warningLogger: (String) -> Unit = {},
    private val requestScheduler: SlockRequestScheduler = SlockRequestScheduler()
) {
    suspend fun settingsAccount(): KmpHttpResponse = get("auth/me")
    suspend fun updateSettingsAccount(body: String): KmpHttpResponse = patch("auth/me", body)
    suspend fun settingsSessions(): KmpHttpResponse = get("auth/sessions")
    suspend fun revokeSettingsSession(sessionId: String): KmpHttpResponse = delete("auth/sessions/$sessionId")
    suspend fun settingsMachines(serverId: String): KmpHttpResponse = get("servers/$serverId/machines")
    suspend fun settingsMachineWorkspaces(serverId: String, machineId: String): KmpHttpResponse =
        get("servers/$serverId/machines/$machineId/workspaces")
    suspend fun settingsAgents(): KmpHttpResponse = get("agents")
    suspend fun updateAgent(agentId: String, body: String): KmpHttpResponse = patch("agents/$agentId", body)
    suspend fun channels(archived: String? = null): KmpHttpResponse =
        get("channels", listOfNotNull(archived?.let { "archived" to it }))
    suspend fun serverMembers(serverId: String): KmpHttpResponse = get("servers/$serverId/members")
    suspend fun updateSettingsServerProfile(serverId: String, body: String): KmpHttpResponse =
        patch("servers/$serverId", body)
    suspend fun settingsServerUsage(serverId: String): KmpHttpResponse = get("servers/$serverId/usage")
    suspend fun settingsBillingSubscription(): KmpHttpResponse = get("billing/subscription")
    suspend fun createSettingsServerInvite(serverId: String, body: String): KmpHttpResponse =
        post("servers/$serverId/invites", body)
    suspend fun settingsServerOnboardingSettings(serverId: String): KmpHttpResponse =
        get("servers/$serverId/onboarding-settings")
    suspend fun updateSettingsServerOnboardingSettings(serverId: String, body: String): KmpHttpResponse =
        patch("servers/$serverId/onboarding-settings", body)
    suspend fun deleteSettingsServer(serverId: String): KmpHttpResponse = delete("servers/$serverId")
    suspend fun memberProfile(memberId: String): KmpHttpResponse = get("members/$memberId/profile")
    suspend fun agentDirectMessages(agentId: String): KmpHttpResponse = get("agents/$agentId/agent-dms")
    suspend fun agentWorkspaceFiles(agentId: String, dirPath: String? = null): KmpHttpResponse =
        get("agents/$agentId/workspace-files", listOfNotNull(dirPath?.let { "dirPath" to it }))
    suspend fun readAgentWorkspaceFile(agentId: String, path: String): KmpHttpResponse =
        get("agents/$agentId/workspace-files/read", listOf("path" to path))
    suspend fun reminders(ownerAgentId: String, status: String = "scheduled"): KmpHttpResponse =
        get("reminders", listOf("ownerAgentId" to ownerAgentId, "status" to status))
    suspend fun agentActivityLog(agentId: String, limit: Int = 50): KmpHttpResponse =
        get("agents/$agentId/activity-log", listOf("limit" to limit.toString()))
    suspend fun channelMembers(channelId: String): KmpHttpResponse = get("channels/$channelId/members")
    suspend fun createChannel(body: String): KmpHttpResponse = post("channels", body)
    suspend fun joinChannel(channelId: String): KmpHttpResponse = post("channels/$channelId/join")
    suspend fun archiveChannel(channelId: String): KmpHttpResponse = post("channels/$channelId/archive")
    suspend fun deleteChannel(channelId: String): KmpHttpResponse = delete("channels/$channelId")
    suspend fun addChannelMember(channelId: String, body: String): KmpHttpResponse =
        post("channels/$channelId/members", body)
    suspend fun removeChannelMember(channelId: String, kind: String, memberId: String): KmpHttpResponse =
        delete("channels/$channelId/members/$kind/$memberId")
    suspend fun directMessages(): KmpHttpResponse = get("channels/dm")
    suspend fun openDirectMessage(body: String): KmpHttpResponse = post("channels/dm", body)
    suspend fun channelUnreadCounts(): KmpHttpResponse = get("channels/unread")
    suspend fun markChannelReadToSeq(channelId: String, body: String): KmpHttpResponse =
        post("channels/$channelId/read", body)
    suspend fun markChannelReadAll(channelId: String): KmpHttpResponse = post("channels/$channelId/read-all")
    suspend fun markChannelUnread(channelId: String): KmpHttpResponse = post("channels/$channelId/unread")
    suspend fun inbox(filter: String, limit: Int, offset: Int): KmpHttpResponse =
        get("channels/inbox", listOf("filter" to filter, "limit" to limit.toString(), "offset" to offset.toString()))
    suspend fun markInboxReadAll(): KmpHttpResponse = post("channels/inbox/read-all")
    suspend fun serverTasks(): KmpHttpResponse = get("tasks/server")
    suspend fun channelTasks(channelId: String): KmpHttpResponse = get("tasks/channel/$channelId")
    suspend fun claimTask(taskId: String): KmpHttpResponse = patch("tasks/$taskId/claim")
    suspend fun updateTaskStatus(taskId: String, body: String): KmpHttpResponse = patch("tasks/$taskId/status", body)
    suspend fun saved(limit: Int, offset: Int): KmpHttpResponse =
        get("channels/saved", listOf("limit" to limit.toString(), "offset" to offset.toString()))
    suspend fun saveMessage(messageId: String): KmpHttpResponse = post("messages/$messageId/save")
    suspend fun unsaveMessage(messageId: String): KmpHttpResponse = delete("messages/$messageId/save")
    suspend fun searchMessages(query: String, limit: Int, offset: Int): KmpHttpResponse =
        get("messages/search", listOf("q" to query, "limit" to limit.toString(), "offset" to offset.toString()))
    suspend fun channelMessages(channelId: String, limit: Int, beforeSeq: Long? = null): KmpHttpResponse =
        get(
            "messages/channel/$channelId",
            listOfNotNull("limit" to limit.toString(), beforeSeq?.let { "before" to it.toString() })
        )
    suspend fun messageContext(messageId: String): KmpHttpResponse = get("messages/context/$messageId")
    suspend fun messageSync(sinceSeq: Long, limit: Int, channelId: String? = null): KmpHttpResponse =
        get(
            "messages/sync",
            listOfNotNull(
                "since_seq" to sinceSeq.toString(),
                "limit" to limit.toString(),
                channelId?.let { "channel_id" to it }
            )
        )
    suspend fun sendMessage(body: String): KmpHttpResponse = post("messages", body)
    suspend fun uploadAttachments(channelId: String, files: List<MultipartFile>): KmpHttpResponse =
        multipart("attachments/upload", MultipartFormData(fields = mapOf("channelId" to channelId), files = files))
    suspend fun attachmentUrl(attachmentId: String): KmpHttpResponse = get("attachments/$attachmentId/url")
    suspend fun channelThreads(channelId: String, limit: Int): KmpHttpResponse =
        get("channels/$channelId/threads", listOf("limit" to limit.toString()))
    suspend fun createOrOpenThread(channelId: String, body: String): KmpHttpResponse =
        post("channels/$channelId/threads", body)
    suspend fun followedThreads(limit: Int): KmpHttpResponse =
        get("channels/threads/followed", listOf("limit" to limit.toString()))
    suspend fun followThread(body: String): KmpHttpResponse = post("channels/threads/follow", body)
    suspend fun unfollowThread(body: String): KmpHttpResponse = post("channels/threads/unfollow", body)
    suspend fun markThreadDone(body: String): KmpHttpResponse = post("channels/threads/done", body)
    suspend fun markThreadUndone(body: String): KmpHttpResponse = post("channels/threads/undone", body)
    suspend fun serverUnreadSummary(): KmpHttpResponse = get("servers/unread-summary")

    private suspend fun get(path: String, query: List<Pair<String, String>> = emptyList()): KmpHttpResponse =
        request(HttpMethod.Get, path, query)

    private suspend fun post(path: String, body: String? = null): KmpHttpResponse =
        request(HttpMethod.Post, path, body = body)

    private suspend fun patch(path: String, body: String? = null): KmpHttpResponse =
        request(HttpMethod.Patch, path, body = body)

    private suspend fun delete(path: String): KmpHttpResponse =
        request(HttpMethod.Delete, path)

    private suspend fun request(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null
    ): KmpHttpResponse =
        coroutineContext[SlockRequestPriorityElement].let { priority ->
            requestScheduler.run(
                priority = priority?.priority ?: SlockRequestPriority.Visible,
                key = priority?.requestKey,
                reason = priority?.reason
            ) {
                authTransport.executeWithAuthRetry { tokens ->
                    executeKtor(method, path, query, body, tokens)
                }
            }
        }.toKmpHttpResponse()

    private suspend fun multipart(path: String, formData: MultipartFormData): KmpHttpResponse =
        coroutineContext[SlockRequestPriorityElement].let { priority ->
            requestScheduler.run(
                priority = priority?.priority ?: SlockRequestPriority.Visible,
                key = priority?.requestKey,
                reason = priority?.reason
            ) {
                authTransport.executeWithAuthRetry { tokens ->
                    executeMultipart(path, formData, tokens)
                }
            }
        }.toKmpHttpResponse()

    private suspend fun executeKtor(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>>,
        body: String?,
        tokens: AuthTokens?
    ): HttpResponse =
        try {
            when (method) {
                HttpMethod.Get -> httpClient.get { buildRequest(path, query, body, tokens) }
                HttpMethod.Post -> httpClient.post { buildRequest(path, query, body, tokens) }
                HttpMethod.Patch -> httpClient.patch { buildRequest(path, query, body, tokens) }
                HttpMethod.Delete -> httpClient.delete { buildRequest(path, query, body, tokens) }
                else -> error("Unsupported HTTP method ${method.value}")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger("slock_api_failed path=$path method=${method.value} reason=${error::class.simpleName}")
            throw error
        }

    private suspend fun executeMultipart(
        path: String,
        formData: MultipartFormData,
        tokens: AuthTokens?
    ): HttpResponse =
        try {
            httpClient.submitFormWithBinaryData(
                url = absoluteUrl(path),
                formData =
                    io.ktor.client.request.forms.formData {
                        formData.fields.forEach { (key, value) -> append(key, value) }
                        formData.files.forEach { file ->
                            append(
                                key = file.name,
                                value = file.bytes,
                                headers =
                                    Headers.build {
                                        append(HttpHeaders.ContentType, file.mimeType)
                                        append(HttpHeaders.ContentDisposition, "filename=\"${file.fileName}\"")
                                    }
                            )
                        }
                    }
            ) {
                applyAuthTokens(tokens)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger("slock_api_failed path=$path method=multipart reason=${error::class.simpleName}")
            throw error
        }

    private fun io.ktor.client.request.HttpRequestBuilder.buildRequest(
        path: String,
        query: List<Pair<String, String>>,
        body: String?,
        tokens: AuthTokens?
    ) {
        url(absoluteUrl(path, query))
        applyAuthTokens(tokens)
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private fun absoluteUrl(path: String, query: List<Pair<String, String>> = emptyList()): String {
        val root = baseUrl.trimEnd('/')
        val encodedQuery = query.joinToString("&") { (name, value) -> "${name.urlEncode()}=${value.urlEncode()}" }
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

    private fun String.urlEncode(): String =
        encodeToByteArray().joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char.isUrlSafe()) char.toString() else "%${value.toString(16).uppercase().padStart(2, '0')}"
        }

    private fun Char.isUrlSafe(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_' || this == '.' || this == '~'
}
