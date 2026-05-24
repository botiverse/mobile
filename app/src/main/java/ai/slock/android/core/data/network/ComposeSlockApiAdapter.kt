package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.KtorAuthRepository
import ai.slock.compose.network.AuthTokenProvider
import ai.slock.compose.network.AuthTokens
import ai.slock.compose.network.AuthenticatedKtorTransport
import ai.slock.compose.network.HttpClientConfig
import ai.slock.compose.network.KmpHttpResponse
import ai.slock.compose.network.MultipartFile
import ai.slock.compose.network.SlockApiClient
import ai.slock.compose.network.SlockRequestPriority
import ai.slock.compose.network.SlockRequestScheduler
import ai.slock.compose.network.TimeoutConfig
import ai.slock.compose.network.applyAuthTokens
import ai.slock.compose.network.createPriorityHttpClient
import ai.slock.compose.network.toKmpHttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.url
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class ComposeSlockApiAdapter(
        store: AuthSessionStore,
        private val baseUrl: String = KtorAuthRepository.BASE_URL,
        tokenRefresher: AuthTokenRefresher =
                AuthTokenRefresher(
                        store = store,
                        api = KtorAuthRepository.createAuthApi(baseUrl)
                ),
        httpClient: HttpClient =
                createPriorityHttpClient(
                        config = HttpClientConfig(timeout = TimeoutConfig()),
                        maxRequests = 6,
                        maxRequestsPerHost = 6
                ),
        private val foregroundHttpClient: HttpClient =
                createPriorityHttpClient(
                        config = HttpClientConfig(timeout = TimeoutConfig()),
                        maxRequests = 12,
                        maxRequestsPerHost = 12
                ),
        private val visibleHttpClient: HttpClient =
                createPriorityHttpClient(
                        config = HttpClientConfig(timeout = TimeoutConfig()),
                        maxRequests = 8,
                        maxRequestsPerHost = 8
                ),
        private val backgroundHttpClient: HttpClient =
                createPriorityHttpClient(
                        config = HttpClientConfig(timeout = TimeoutConfig()),
                        maxRequests = 4,
                        maxRequestsPerHost = 4
                ),
        private val logger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(TAG, message)
        },
        private val warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(TAG, message)
        },
        private val authTransport: AuthenticatedKtorTransport =
                AuthenticatedKtorTransport(
                        tokenProvider =
                                AndroidAuthTokenProvider(
                                        store = store,
                                        tokenRefresher = tokenRefresher,
                                        warningLogger = warningLogger
                                ),
                        logger = { message -> logger("compose_$message") }
                ),
        private val client: SlockApiClient =
                SlockApiClient(
                        baseUrl = baseUrl,
                        httpClient = httpClient,
                        foregroundHttpClient = foregroundHttpClient,
                        visibleHttpClient = visibleHttpClient,
                        backgroundHttpClient = backgroundHttpClient,
                        authTransport = authTransport,
                        requestScheduler =
                                SlockRequestScheduler(
                                        maxForegroundRequests = 12,
                                        maxVisibleRequests = 8,
                                        maxBackgroundRequests = 4,
                                        logger = { message -> logger("request_pool_$message") }
                                ),
                        warningLogger = warningLogger
                )
) : SlockApi {
    override suspend fun settingsAccount(priority: SlockRequestPriority): ApiResponse = request(priority) { client.settingsAccount(priority) }
    override suspend fun updateSettingsAccount(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            request(priority) { client.updateSettingsAccount(priority, body.bodyAsString()) }
    override suspend fun settingsMachines(priority: SlockRequestPriority, serverId: String): ApiResponse =
            request(priority) { client.settingsMachines(priority, serverId) }
    override suspend fun settingsMachineWorkspaces(priority: SlockRequestPriority, serverId: String, machineId: String): ApiResponse =
            request(priority) { client.settingsMachineWorkspaces(priority, serverId, machineId) }
    override suspend fun settingsAgents(priority: SlockRequestPriority): ApiResponse = request(priority) { client.settingsAgents(priority) }
    override suspend fun updateAgent(priority: SlockRequestPriority, agentId: String, body: RequestBody): ApiResponse =
            request(priority) { client.updateAgent(priority, agentId, body.bodyAsString()) }
    override suspend fun channels(priority: SlockRequestPriority, archived: String?): ApiResponse = request(priority) { client.channels(priority, archived) }
    override suspend fun serverMembers(priority: SlockRequestPriority, serverId: String): ApiResponse = request(priority) { client.serverMembers(priority, serverId) }
    override suspend fun updateSettingsServerProfile(priority: SlockRequestPriority, serverId: String, body: RequestBody): ApiResponse =
            request(priority) { client.updateSettingsServerProfile(priority, serverId, body.bodyAsString()) }
    override suspend fun settingsServerUsage(priority: SlockRequestPriority, serverId: String): ApiResponse =
            request(priority) { client.settingsServerUsage(priority, serverId) }
    override suspend fun settingsBillingSubscription(priority: SlockRequestPriority): ApiResponse =
            request(priority) { client.settingsBillingSubscription(priority) }
    override suspend fun createSettingsServerInvite(priority: SlockRequestPriority, serverId: String, body: RequestBody): ApiResponse =
            request(priority) { client.createSettingsServerInvite(priority, serverId, body.bodyAsString()) }
    override suspend fun settingsServerOnboardingSettings(priority: SlockRequestPriority, serverId: String): ApiResponse =
            request(priority) { client.settingsServerOnboardingSettings(priority, serverId) }
    override suspend fun updateSettingsServerOnboardingSettings(priority: SlockRequestPriority, serverId: String, body: RequestBody): ApiResponse =
            request(priority) { client.updateSettingsServerOnboardingSettings(priority, serverId, body.bodyAsString()) }
    override suspend fun deleteSettingsServer(priority: SlockRequestPriority, serverId: String): ApiResponse =
            request(priority) { client.deleteSettingsServer(priority, serverId) }
    override suspend fun memberProfile(priority: SlockRequestPriority, serverId: String, memberId: String): ApiResponse =
            request(priority) { client.memberProfile(priority, serverId, memberId) }
    override suspend fun agentWorkspaceFiles(priority: SlockRequestPriority, agentId: String, dirPath: String?): ApiResponse =
            request(priority) { client.agentWorkspaceFiles(priority, agentId, dirPath) }
    override suspend fun readAgentWorkspaceFile(priority: SlockRequestPriority, agentId: String, path: String): ApiResponse =
            request(priority) { client.readAgentWorkspaceFile(priority, agentId, path) }
    override suspend fun agentSkills(priority: SlockRequestPriority, agentId: String): ApiResponse =
            request(priority) { client.agentSkills(priority, agentId) }
    override suspend fun agentDirectMessages(priority: SlockRequestPriority, agentId: String): ApiResponse =
            request(priority) { client.agentDirectMessages(priority, agentId) }
    override suspend fun reminders(priority: SlockRequestPriority, ownerAgentId: String, status: String): ApiResponse =
            request(priority) { client.reminders(priority, ownerAgentId, status) }
    override suspend fun agentActivityLog(priority: SlockRequestPriority, agentId: String, limit: Int): ApiResponse =
            request(priority) { client.agentActivityLog(priority, agentId, limit) }
    override suspend fun channelMembers(priority: SlockRequestPriority, channelId: String): ApiResponse =
            request(priority) { client.channelMembers(priority, channelId) }
    override suspend fun createChannel(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            request(priority) { client.createChannel(priority, body.bodyAsString()) }
    override suspend fun joinChannel(priority: SlockRequestPriority, channelId: String): ApiResponse = request(priority) { client.joinChannel(priority, channelId) }
    override suspend fun leaveChannel(priority: SlockRequestPriority, channelId: String): ApiResponse =
            request(priority) {
                authTransport
                        .executeWithAuthRetry { tokens ->
                            priority.httpClient().post {
                                url("${baseUrl.trimEnd('/')}/channels/$channelId/leave")
                                applyAuthTokens(tokens)
                            }
                        }
                        .toKmpHttpResponse()
            }
    override suspend fun archiveChannel(priority: SlockRequestPriority, channelId: String): ApiResponse =
            request(priority) { client.archiveChannel(priority, channelId) }
    override suspend fun deleteChannel(priority: SlockRequestPriority, channelId: String): ApiResponse = request(priority) { client.deleteChannel(priority, channelId) }
    override suspend fun addChannelMember(priority: SlockRequestPriority, channelId: String, body: RequestBody): ApiResponse =
            request(priority) { client.addChannelMember(priority, channelId, body.bodyAsString()) }
    override suspend fun removeChannelMember(priority: SlockRequestPriority, channelId: String, kind: String, memberId: String): ApiResponse =
            request(priority) { client.removeChannelMember(priority, channelId, kind, memberId) }
    override suspend fun directMessages(priority: SlockRequestPriority): ApiResponse = request(priority) { client.directMessages(priority) }
    override suspend fun openDirectMessage(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            request(priority) { client.openDirectMessage(priority, body.bodyAsString()) }
    override suspend fun channelUnreadCounts(priority: SlockRequestPriority): ApiResponse = request(priority) { client.channelUnreadCounts(priority) }
    override suspend fun markChannelReadToSeq(priority: SlockRequestPriority, channelId: String, body: RequestBody): ApiResponse =
            request(priority) { client.markChannelReadToSeq(priority, channelId, body.bodyAsString()) }
    override suspend fun markChannelReadAll(priority: SlockRequestPriority, channelId: String): ApiResponse =
            request(priority) { client.markChannelReadAll(priority, channelId) }
    override suspend fun markChannelUnread(priority: SlockRequestPriority, channelId: String): ApiResponse =
            request(priority) { client.markChannelUnread(priority, channelId) }
    override suspend fun inbox(priority: SlockRequestPriority, filter: String, limit: Int, offset: Int): ApiResponse =
            request(priority) { client.inbox(priority, filter, limit, offset) }
    override suspend fun markInboxReadAll(priority: SlockRequestPriority): ApiResponse = request(priority) { client.markInboxReadAll(priority) }
    override suspend fun serverTasks(priority: SlockRequestPriority): ApiResponse = request(priority) { client.serverTasks(priority) }
    override suspend fun channelTasks(priority: SlockRequestPriority, channelId: String): ApiResponse = request(priority) { client.channelTasks(priority, channelId) }
    override suspend fun claimTask(priority: SlockRequestPriority, taskId: String): ApiResponse = request(priority) { client.claimTask(priority, taskId) }
    override suspend fun updateTaskStatus(priority: SlockRequestPriority, taskId: String, body: RequestBody): ApiResponse =
            request(priority) { client.updateTaskStatus(priority, taskId, body.bodyAsString()) }
    override suspend fun saved(priority: SlockRequestPriority, limit: Int, offset: Int): ApiResponse = request(priority) { client.saved(priority, limit, offset) }
    override suspend fun saveMessage(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            request(priority) { client.saveMessage(priority, body.bodyAsString()) }
    override suspend fun unsaveMessage(priority: SlockRequestPriority, messageId: String): ApiResponse = request(priority) { client.unsaveMessage(priority, messageId) }
    override suspend fun searchMessages(
        priority: SlockRequestPriority,
        query: String,
        limit: Int,
        offset: Int,
        sort: String?,
        senderId: String?,
        channelId: String?,
        after: String?,
        before: String?
    ): ApiResponse =
            request(priority) {
                client.searchMessages(
                    priority = priority,
                    query = query,
                    limit = limit,
                    offset = offset,
                    sort = sort,
                    senderId = senderId,
                    channelId = channelId,
                    after = after,
                    before = before
                )
            }
    override suspend fun channelMessages(priority: SlockRequestPriority, channelId: String, limit: Int, beforeSeq: Long?): ApiResponse =
            request(priority) { client.channelMessages(priority, channelId, limit, beforeSeq) }
    override suspend fun messageContext(priority: SlockRequestPriority, messageId: String): ApiResponse = request(priority) { client.messageContext(priority, messageId) }
    override suspend fun messageSync(priority: SlockRequestPriority, sinceSeq: Long, limit: Int, channelId: String?): ApiResponse =
            request(priority) { client.messageSync(priority, sinceSeq, limit, channelId) }
    override suspend fun sendMessage(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            request(priority) { client.sendMessage(priority, body.bodyAsString()) }
    override suspend fun uploadAttachments(priority: SlockRequestPriority, channelId: RequestBody, files: List<MultipartBody.Part>): ApiResponse =
            request(priority) { client.uploadAttachments(priority, channelId.bodyAsString(), files.map { it.toKmpMultipartFile() }) }
    override suspend fun attachmentUrl(priority: SlockRequestPriority, attachmentId: String): ApiResponse =
            request(priority) { client.attachmentUrl(priority, attachmentId) }
    override suspend fun channelFiles(priority: SlockRequestPriority, channelId: String, limit: Int, cursor: String?): ApiResponse =
            request(priority) { client.channelFiles(priority, channelId, limit, cursor) }
    override suspend fun channelThreads(priority: SlockRequestPriority, channelId: String, limit: Int): ApiResponse =
            request(priority) { client.channelThreads(priority, channelId, limit) }
    override suspend fun createOrOpenThread(priority: SlockRequestPriority, channelId: String, body: RequestBody): ApiResponse =
            request(priority) { client.createOrOpenThread(priority, channelId, body.bodyAsString()) }
    override suspend fun followedThreads(priority: SlockRequestPriority, limit: Int): ApiResponse = request(priority) { client.followedThreads(priority, limit) }
    override suspend fun followThread(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            request(priority) { client.followThread(priority, body.bodyAsString()) }
    override suspend fun unfollowThread(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            request(priority) { client.unfollowThread(priority, body.bodyAsString()) }
    override suspend fun markThreadDone(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            request(priority) { client.markThreadDone(priority, body.bodyAsString()) }
    override suspend fun markThreadUndone(priority: SlockRequestPriority, body: RequestBody): ApiResponse =
            request(priority) { client.markThreadUndone(priority, body.bodyAsString()) }
    override suspend fun serverUnreadSummary(priority: SlockRequestPriority): ApiResponse = request(priority) { client.serverUnreadSummary(priority) }

    private suspend fun request(priority: SlockRequestPriority, block: suspend () -> KmpHttpResponse): ApiResponse =
            try {
                ApiResponse.from(block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                warningLogger("compose_api_failed reason=${io::class.java.simpleName}")
                networkUnavailableResponse()
            } catch (error: Exception) {
                warningLogger("compose_api_failed reason=${error::class.java.simpleName}")
                networkUnavailableResponse()
            }

    private fun RequestBody.bodyAsString(): String {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun SlockRequestPriority.httpClient(): HttpClient =
            when (this) {
                SlockRequestPriority.Foreground -> foregroundHttpClient
                SlockRequestPriority.Visible -> visibleHttpClient
                SlockRequestPriority.Background -> backgroundHttpClient
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

    private companion object {
        const val TAG = "SlockComposeApi"
        val JSON = "application/json; charset=utf-8".toMediaType()

        fun networkUnavailableResponse(): ApiResponse =
                ApiResponse.error(
                        code = 0,
                        errorBody =
                                """{"error":"network_unavailable"}"""
                                        .toResponseBody(JSON)
                )
    }
}

internal class AndroidAuthTokenProvider(
        private val store: AuthSessionStore,
        private val tokenRefresher: AuthTokenRefresher,
        private val warningLogger: (String) -> Unit
) : AuthTokenProvider {
    override suspend fun currentTokens(): AuthTokens? =
            store.readSession()?.toAuthTokens()

    override suspend fun refreshTokens(failedAccessToken: String): AuthTokens? =
            tokenRefresher.refreshIfCurrent(failedAccessToken)?.toAuthTokens()

    override suspend fun onUnauthorized(failedAccessToken: String?) {
        val current = store.readSession()
        if (
                !failedAccessToken.isNullOrBlank() &&
                        current != null &&
                        current.accessToken == failedAccessToken
        ) {
            warningLogger("compose_api_retry_unauthorized_clearing_session")
            store.clearSession()
        }
    }
}

internal fun AuthSession.toAuthTokens(): AuthTokens =
        AuthTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                selectedServerId = currentServerId
        )
