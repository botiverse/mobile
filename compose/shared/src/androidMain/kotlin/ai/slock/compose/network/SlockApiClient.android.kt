package ai.slock.compose.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

class SlockApiClient(
    private val baseUrl: String,
    private val httpClient: HttpClient = createHttpClient(HttpClientConfig(timeout = TimeoutConfig())),
    private val foregroundHttpClient: HttpClient = httpClient,
    private val visibleHttpClient: HttpClient = httpClient,
    private val backgroundHttpClient: HttpClient = httpClient,
    private val authTransport: AuthenticatedKtorTransport,
    private val warningLogger: (String) -> Unit = {},
    private val requestScheduler: SlockRequestScheduler = SlockRequestScheduler()
) {
    suspend fun settingsAccount(priority: SlockRequestPriority): KmpHttpResponse = get(priority, "auth/me")
    suspend fun updateSettingsAccount(priority: SlockRequestPriority, body: String): KmpHttpResponse = patch(priority, "auth/me", body)
    suspend fun settingsMachines(priority: SlockRequestPriority, serverId: String): KmpHttpResponse = get(priority, "servers/$serverId/machines")
    suspend fun registerMachine(priority: SlockRequestPriority, serverId: String, body: String): KmpHttpResponse =
        post(priority, "servers/$serverId/machines", body)
    suspend fun renameMachine(priority: SlockRequestPriority, serverId: String, machineId: String, body: String): KmpHttpResponse =
        patch(priority, "servers/$serverId/machines/$machineId", body)
    suspend fun deleteMachine(priority: SlockRequestPriority, serverId: String, machineId: String): KmpHttpResponse =
        delete(priority, "servers/$serverId/machines/$machineId")
    suspend fun rotateMachineKey(priority: SlockRequestPriority, serverId: String, machineId: String): KmpHttpResponse =
        post(priority, "servers/$serverId/machines/$machineId/rotate-key")
    suspend fun settingsMachineWorkspaces(priority: SlockRequestPriority, serverId: String, machineId: String): KmpHttpResponse =
        get(priority, "servers/$serverId/machines/$machineId/workspaces")
    suspend fun machineRuntimeModels(priority: SlockRequestPriority, serverId: String, machineId: String, runtime: String): KmpHttpResponse =
        get(priority, "servers/$serverId/machines/$machineId/runtime-models/$runtime")
    suspend fun deleteMachineWorkspace(
        priority: SlockRequestPriority,
        serverId: String,
        machineId: String,
        directoryName: String
    ): KmpHttpResponse =
        delete(priority, "servers/$serverId/machines/$machineId/workspaces/$directoryName")
    suspend fun settingsAgents(priority: SlockRequestPriority): KmpHttpResponse = get(priority, "agents")
    suspend fun agentDetail(priority: SlockRequestPriority, agentId: String): KmpHttpResponse = get(priority, "agents/$agentId")
    suspend fun createAgent(priority: SlockRequestPriority, body: String): KmpHttpResponse = post(priority, "agents", body)
    suspend fun updateAgent(priority: SlockRequestPriority, agentId: String, body: String): KmpHttpResponse = patch(priority, "agents/$agentId", body)
    suspend fun startAgent(priority: SlockRequestPriority, agentId: String): KmpHttpResponse = post(priority, "agents/$agentId/start")
    suspend fun stopAgent(priority: SlockRequestPriority, agentId: String): KmpHttpResponse = post(priority, "agents/$agentId/stop")
    suspend fun resetAgent(priority: SlockRequestPriority, agentId: String, body: String): KmpHttpResponse = post(priority, "agents/$agentId/reset", body)
    suspend fun deleteAgent(priority: SlockRequestPriority, agentId: String): KmpHttpResponse = delete(priority, "agents/$agentId")
    suspend fun assignAgentMachine(priority: SlockRequestPriority, agentId: String, body: String): KmpHttpResponse =
        post(priority, "agents/$agentId/assign-machine", body)
    suspend fun agentScopes(priority: SlockRequestPriority, agentId: String): KmpHttpResponse = get(priority, "agents/$agentId/scopes")
    suspend fun updateAgentScopes(priority: SlockRequestPriority, agentId: String, body: String): KmpHttpResponse =
        request(priority, HttpMethod.Put, "agents/$agentId/scopes", body = body)
    suspend fun issueAgentBootstrapToken(priority: SlockRequestPriority, agentId: String, body: String): KmpHttpResponse =
        post(priority, "agents/$agentId/bootstrap-tokens", body)
    suspend fun channels(priority: SlockRequestPriority, archived: String? = null): KmpHttpResponse =
        get(priority, "channels", listOfNotNull(archived?.let { "archived" to it }))
    suspend fun serverMembers(priority: SlockRequestPriority, serverId: String): KmpHttpResponse = get(priority, "servers/$serverId/members")
    suspend fun updateSettingsServerProfile(priority: SlockRequestPriority, serverId: String, body: String): KmpHttpResponse =
        patch(priority, "servers/$serverId", body)
    suspend fun settingsServerUsage(priority: SlockRequestPriority, serverId: String): KmpHttpResponse = get(priority, "servers/$serverId/usage")
    suspend fun settingsBillingSubscription(priority: SlockRequestPriority): KmpHttpResponse = get(priority, "billing/subscription")
    suspend fun createSettingsServerInvite(priority: SlockRequestPriority, serverId: String, body: String): KmpHttpResponse =
        post(priority, "servers/$serverId/invites", body)
    suspend fun settingsServerOnboardingSettings(priority: SlockRequestPriority, serverId: String): KmpHttpResponse =
        get(priority, "servers/$serverId/onboarding-settings")
    suspend fun updateSettingsServerOnboardingSettings(priority: SlockRequestPriority, serverId: String, body: String): KmpHttpResponse =
        patch(priority, "servers/$serverId/onboarding-settings", body)
    suspend fun deleteSettingsServer(priority: SlockRequestPriority, serverId: String): KmpHttpResponse = delete(priority, "servers/$serverId")
    suspend fun memberProfile(priority: SlockRequestPriority, serverId: String, memberId: String): KmpHttpResponse =
        get(priority, "servers/$serverId/members/$memberId/profile")
    suspend fun agentDirectMessages(priority: SlockRequestPriority, agentId: String): KmpHttpResponse = get(priority, "agents/$agentId/agent-dms")
    suspend fun agentWorkspaceFiles(priority: SlockRequestPriority, agentId: String, dirPath: String? = null): KmpHttpResponse =
        get(priority, "agents/$agentId/workspace-files", listOfNotNull(dirPath?.let { "dirPath" to it }))
    suspend fun readAgentWorkspaceFile(priority: SlockRequestPriority, agentId: String, path: String): KmpHttpResponse =
        get(priority, "agents/$agentId/workspace-files/read", listOf("path" to path))
    suspend fun agentSkills(priority: SlockRequestPriority, agentId: String): KmpHttpResponse = get(priority, "agents/$agentId/skills")
    suspend fun activeAnnouncements(priority: SlockRequestPriority): KmpHttpResponse = get(priority, "announcements/active")
    suspend fun dismissAnnouncement(priority: SlockRequestPriority, announcementId: String): KmpHttpResponse =
        post(priority, "announcements/$announcementId/dismiss")
    suspend fun reminders(priority: SlockRequestPriority, ownerAgentId: String, status: String = "scheduled"): KmpHttpResponse =
        get(priority, "reminders", listOf("ownerAgentId" to ownerAgentId, "status" to status))
    suspend fun integrationsOverview(priority: SlockRequestPriority): KmpHttpResponse = get(priority, "integrations/overview")
    suspend fun integrationClients(priority: SlockRequestPriority): KmpHttpResponse = get(priority, "integrations/clients")
    suspend fun createIntegrationClient(priority: SlockRequestPriority, body: String): KmpHttpResponse =
        post(priority, "integrations/clients", body)
    suspend fun approveIntegrationRequest(priority: SlockRequestPriority, requestId: String, body: String): KmpHttpResponse =
        post(priority, "integrations/requests/$requestId/approve", body)
    suspend fun denyIntegrationRequest(priority: SlockRequestPriority, requestId: String): KmpHttpResponse =
        post(priority, "integrations/requests/$requestId/deny")
    suspend fun revokeIntegrationGrant(priority: SlockRequestPriority, grantId: String): KmpHttpResponse =
        post(priority, "integrations/grants/$grantId/revoke")
    suspend fun agentIntegrations(priority: SlockRequestPriority, agentId: String): KmpHttpResponse =
        get(priority, "integrations/agents/$agentId")
    suspend fun executeAction(priority: SlockRequestPriority, messageId: String, body: String): KmpHttpResponse =
        post(priority, "actions/$messageId/execute", body)
    suspend fun emitActionEvent(priority: SlockRequestPriority, messageId: String, body: String): KmpHttpResponse =
        post(priority, "actions/$messageId/event", body)
    suspend fun markActionExecuted(priority: SlockRequestPriority, messageId: String, body: String): KmpHttpResponse =
        post(priority, "actions/$messageId/mark-executed", body)
    suspend fun pushVapidKey(priority: SlockRequestPriority): KmpHttpResponse = get(priority, "push/vapid-key")
    suspend fun recordPushPromptEvent(priority: SlockRequestPriority, body: String): KmpHttpResponse =
        post(priority, "push/prompt-events", body)
    suspend fun subscribePush(priority: SlockRequestPriority, body: String): KmpHttpResponse = post(priority, "push/subscribe", body)
    suspend fun unsubscribePush(priority: SlockRequestPriority, body: String): KmpHttpResponse =
        request(priority, HttpMethod.Delete, "push/subscribe", body = body)
    suspend fun testPush(priority: SlockRequestPriority): KmpHttpResponse = post(priority, "push/test")
    suspend fun agentActivityLog(priority: SlockRequestPriority, agentId: String, limit: Int = 50): KmpHttpResponse =
        get(priority, "agents/$agentId/activity-log", listOf("limit" to limit.toString()))
    suspend fun channelMembers(priority: SlockRequestPriority, channelId: String): KmpHttpResponse = get(priority, "channels/$channelId/members")
    suspend fun createChannel(priority: SlockRequestPriority, body: String): KmpHttpResponse = post(priority, "channels", body)
    suspend fun joinChannel(priority: SlockRequestPriority, channelId: String): KmpHttpResponse = post(priority, "channels/$channelId/join")
    suspend fun leaveChannel(priority: SlockRequestPriority, channelId: String): KmpHttpResponse = post(priority, "channels/$channelId/leave")
    suspend fun archiveChannel(priority: SlockRequestPriority, channelId: String): KmpHttpResponse = post(priority, "channels/$channelId/archive")
    suspend fun deleteChannel(priority: SlockRequestPriority, channelId: String): KmpHttpResponse = delete(priority, "channels/$channelId")
    suspend fun addChannelMember(priority: SlockRequestPriority, channelId: String, body: String): KmpHttpResponse =
        post(priority, "channels/$channelId/members", body)
    suspend fun removeChannelMember(priority: SlockRequestPriority, channelId: String, kind: String, memberId: String): KmpHttpResponse =
        delete(priority, "channels/$channelId/members/$kind/$memberId")
    suspend fun directMessages(priority: SlockRequestPriority): KmpHttpResponse = get(priority, "channels/dm")
    suspend fun openDirectMessage(priority: SlockRequestPriority, body: String): KmpHttpResponse = post(priority, "channels/dm", body)
    suspend fun channelUnreadCounts(priority: SlockRequestPriority): KmpHttpResponse = get(priority, "channels/unread")
    suspend fun markChannelReadToSeq(priority: SlockRequestPriority, channelId: String, body: String): KmpHttpResponse =
        post(priority, "channels/$channelId/read", body)
    suspend fun markChannelReadAll(priority: SlockRequestPriority, channelId: String): KmpHttpResponse = post(priority, "channels/$channelId/read-all")
    suspend fun markChannelUnread(priority: SlockRequestPriority, channelId: String): KmpHttpResponse = post(priority, "channels/$channelId/unread")
    suspend fun inbox(priority: SlockRequestPriority, filter: String, limit: Int, offset: Int): KmpHttpResponse =
        get(priority, "channels/inbox", listOf("filter" to filter, "limit" to limit.toString(), "offset" to offset.toString()))
    suspend fun markInboxReadAll(priority: SlockRequestPriority): KmpHttpResponse = post(priority, "channels/inbox/read-all")
    suspend fun serverTasks(priority: SlockRequestPriority): KmpHttpResponse = get(priority, "tasks/server")
    suspend fun channelTasks(priority: SlockRequestPriority, channelId: String): KmpHttpResponse = get(priority, "tasks/channel/$channelId")
    suspend fun claimTask(priority: SlockRequestPriority, taskId: String): KmpHttpResponse = patch(priority, "tasks/$taskId/claim")
    suspend fun updateTaskStatus(priority: SlockRequestPriority, taskId: String, body: String): KmpHttpResponse = patch(priority, "tasks/$taskId/status", body)
    suspend fun saved(priority: SlockRequestPriority, limit: Int, offset: Int): KmpHttpResponse =
        get(priority, "channels/saved", listOf("limit" to limit.toString(), "offset" to offset.toString()))
    suspend fun saveMessage(priority: SlockRequestPriority, messageId: String): KmpHttpResponse =
        post(priority, "channels/saved", "{\"messageId\":\"${messageId.escapeJsonString()}\"}")
    suspend fun unsaveMessage(priority: SlockRequestPriority, messageId: String): KmpHttpResponse = delete(priority, "channels/saved/$messageId")
    suspend fun searchMessages(
        priority: SlockRequestPriority,
        query: String,
        limit: Int,
        offset: Int,
        sort: String? = null,
        senderId: String? = null,
        channelId: String? = null,
        after: String? = null,
        before: String? = null
    ): KmpHttpResponse =
        get(
            priority,
            "messages/search",
            listOfNotNull(
                "q" to query,
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                sort?.let { "sort" to it },
                senderId?.let { "senderId" to it },
                channelId?.let { "channelId" to it },
                after?.let { "after" to it },
                before?.let { "before" to it }
            )
        )
    suspend fun channelMessages(priority: SlockRequestPriority, channelId: String, limit: Int, beforeSeq: Long? = null): KmpHttpResponse =
        get(
            priority,
            "messages/channel/$channelId",
            listOfNotNull("limit" to limit.toString(), beforeSeq?.let { "before" to it.toString() })
        )
    suspend fun messageContext(priority: SlockRequestPriority, messageId: String, channelId: String? = null): KmpHttpResponse =
        get(priority, "messages/context/$messageId", listOfNotNull(channelId?.let { "channelId" to it }))
    suspend fun messageSync(priority: SlockRequestPriority, sinceSeq: Long, limit: Int, channelId: String? = null): KmpHttpResponse =
        get(
            priority,
            "messages/sync",
            listOfNotNull(
                "since_seq" to sinceSeq.toString(),
                "limit" to limit.toString(),
                channelId?.let { "channel_id" to it }
            )
        )
    suspend fun sendMessage(priority: SlockRequestPriority, body: String): KmpHttpResponse = post(priority, "messages", body)
    suspend fun uploadAttachments(priority: SlockRequestPriority, channelId: String, files: List<MultipartFile>): KmpHttpResponse =
        multipart(priority, "attachments/upload", MultipartFormData(fields = mapOf("channelId" to channelId), files = files))
    suspend fun uploadAccountAvatar(priority: SlockRequestPriority, file: MultipartFile): KmpHttpResponse =
        multipart(priority, "auth/me/avatar", MultipartFormData(files = listOf(file)))
    suspend fun uploadAgentAvatar(priority: SlockRequestPriority, agentId: String, file: MultipartFile): KmpHttpResponse =
        multipart(priority, "agents/$agentId/avatar", MultipartFormData(files = listOf(file)))
    suspend fun attachmentUrl(
        priority: SlockRequestPriority,
        attachmentId: String,
        disposition: String? = null
    ): KmpHttpResponse =
        get(
            priority,
            "attachments/$attachmentId/url",
            listOfNotNull(disposition?.let { "disposition" to it })
        )
    suspend fun attachmentPreview(priority: SlockRequestPriority, attachmentId: String): KmpHttpResponse =
        get(priority, "attachments/$attachmentId/preview")
    suspend fun attachmentHtmlPreviewUrl(priority: SlockRequestPriority, attachmentId: String): KmpHttpResponse =
        get(priority, "attachments/$attachmentId/html-preview-url")
    suspend fun addMessageReaction(priority: SlockRequestPriority, messageId: String, emoji: String): KmpHttpResponse =
        post(priority, "messages/$messageId/reactions", "{\"emoji\":\"${emoji.escapeJsonString()}\"}")
    suspend fun removeMessageReaction(priority: SlockRequestPriority, messageId: String, emoji: String): KmpHttpResponse =
        request(priority, HttpMethod.Delete, "messages/$messageId/reactions", body = "{\"emoji\":\"${emoji.escapeJsonString()}\"}")
    suspend fun channelFiles(priority: SlockRequestPriority, channelId: String, limit: Int = 50, cursor: String? = null): KmpHttpResponse =
        get(
            priority,
            "channels/$channelId/files",
            listOfNotNull(
                "limit" to limit.toString(),
                cursor?.takeIf { it.isNotBlank() }?.let { "cursor" to it }
            )
        )
    suspend fun channelThreads(priority: SlockRequestPriority, channelId: String, limit: Int): KmpHttpResponse =
        get(priority, "channels/$channelId/threads", listOf("limit" to limit.toString()))
    suspend fun createOrOpenThread(priority: SlockRequestPriority, channelId: String, body: String): KmpHttpResponse =
        post(priority, "channels/$channelId/threads", body)
    suspend fun followedThreads(priority: SlockRequestPriority, limit: Int): KmpHttpResponse =
        get(priority, "channels/threads/followed", listOf("limit" to limit.toString()))
    suspend fun followThread(priority: SlockRequestPriority, body: String): KmpHttpResponse = post(priority, "channels/threads/follow", body)
    suspend fun unfollowThread(priority: SlockRequestPriority, body: String): KmpHttpResponse = post(priority, "channels/threads/unfollow", body)
    suspend fun markThreadDone(priority: SlockRequestPriority, body: String): KmpHttpResponse = post(priority, "channels/threads/done", body)
    suspend fun markThreadUndone(priority: SlockRequestPriority, body: String): KmpHttpResponse = post(priority, "channels/threads/undone", body)
    suspend fun serverUnreadSummary(priority: SlockRequestPriority): KmpHttpResponse = get(priority, "servers/unread-summary")

    private suspend fun get(priority: SlockRequestPriority, path: String, query: List<Pair<String, String>> = emptyList()): KmpHttpResponse =
        request(priority, HttpMethod.Get, path, query)

    suspend fun authenticatedRequest(
        priority: SlockRequestPriority,
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        serverIdOverride: String? = null,
        body: String? = null
    ): KmpHttpResponse =
        request(priority, HttpMethod(method), path, query, body, serverIdOverride)

    private suspend fun post(priority: SlockRequestPriority, path: String, body: String? = null): KmpHttpResponse =
        request(priority, HttpMethod.Post, path, body = body)

    private suspend fun patch(priority: SlockRequestPriority, path: String, body: String? = null): KmpHttpResponse =
        request(priority, HttpMethod.Patch, path, body = body)

    private suspend fun delete(priority: SlockRequestPriority, path: String): KmpHttpResponse =
        request(priority, HttpMethod.Delete, path)

    private suspend fun request(
        priority: SlockRequestPriority,
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        serverIdOverride: String? = null
    ): KmpHttpResponse {
        return requestScheduler.run(
            priority = priority,
            key = path,
            reason = method.value
        ) {
            authTransport.executeWithAuthRetry { tokens ->
                executeKtor(priority.httpClient(), method, path, query, body, tokens.withServerOverride(serverIdOverride))
            }
        }.toKmpHttpResponse()
    }

    private suspend fun multipart(priority: SlockRequestPriority, path: String, formData: MultipartFormData): KmpHttpResponse {
        return requestScheduler.run(
            priority = priority,
            key = path,
            reason = "multipart"
        ) {
            authTransport.executeWithAuthRetry { tokens ->
                executeMultipart(priority.httpClient(), path, formData, tokens)
            }
        }.toKmpHttpResponse()
    }

    private fun SlockRequestPriority.httpClient(): HttpClient =
        when (this) {
            SlockRequestPriority.Foreground -> foregroundHttpClient
            SlockRequestPriority.Visible -> visibleHttpClient
            SlockRequestPriority.Background -> backgroundHttpClient
        }

    private fun AuthTokens?.withServerOverride(serverIdOverride: String?): AuthTokens? {
        val serverId = serverIdOverride?.takeIf { it.isNotBlank() } ?: return this
        return this?.copy(selectedServerId = serverId)
    }

    private suspend fun executeKtor(
        client: HttpClient,
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>>,
        body: String?,
        tokens: AuthTokens?
    ): HttpResponse =
        try {
            when (method) {
                HttpMethod.Get -> client.get { buildRequest(path, query, body, tokens) }
                HttpMethod.Post -> client.post { buildRequest(path, query, body, tokens) }
                HttpMethod.Patch -> client.patch { buildRequest(path, query, body, tokens) }
                HttpMethod.Put -> client.put { buildRequest(path, query, body, tokens) }
                HttpMethod.Delete -> client.delete { buildRequest(path, query, body, tokens) }
                else -> error("Unsupported HTTP method ${method.value}")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            warningLogger("slock_api_failed path=$path method=${method.value} reason=${error::class.simpleName}")
            throw error
    }

    private suspend fun executeMultipart(
        client: HttpClient,
        path: String,
        formData: MultipartFormData,
        tokens: AuthTokens?
    ): HttpResponse =
        try {
            client.submitFormWithBinaryData(
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

private fun String.escapeJsonString(): String =
    buildString(length) {
        for (char in this@escapeJsonString) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
