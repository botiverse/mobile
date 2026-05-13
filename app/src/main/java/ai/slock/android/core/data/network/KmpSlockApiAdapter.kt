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
import ai.slock.compose.network.TimeoutConfig
import ai.slock.compose.network.createHttpClient
import io.ktor.client.HttpClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer

class KmpSlockApiAdapter(
        store: AuthSessionStore,
        baseUrl: String = KtorAuthRepository.BASE_URL,
        tokenRefresher: AuthTokenRefresher =
                AuthTokenRefresher(
                        store = store,
                        api = KtorAuthRepository.createAuthApi(baseUrl)
                ),
        httpClient: HttpClient =
                createHttpClient(
                        HttpClientConfig(
                                timeout = TimeoutConfig()
                        )
                ),
        logger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(TAG, message)
        },
        private val warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(TAG, message)
        },
        private val client: SlockApiClient =
                SlockApiClient(
                        baseUrl = baseUrl,
                        httpClient = httpClient,
                        authTransport =
                                AuthenticatedKtorTransport(
                                        tokenProvider =
                                                AndroidAuthTokenProvider(
                                                        store = store,
                                                        tokenRefresher = tokenRefresher,
                                                        warningLogger = warningLogger
                                                ),
                                        logger = { message -> logger("kmp_$message") }
                                ),
                        warningLogger = warningLogger
                )
) : SlockApi {
    override suspend fun settingsAccount(): ApiResponse = request { client.settingsAccount() }
    override suspend fun updateSettingsAccount(body: RequestBody): ApiResponse =
            request { client.updateSettingsAccount(body.bodyAsString()) }
    override suspend fun settingsSessions(): ApiResponse = request { client.settingsSessions() }
    override suspend fun revokeSettingsSession(sessionId: String): ApiResponse =
            request { client.revokeSettingsSession(sessionId) }
    override suspend fun settingsMachines(serverId: String): ApiResponse =
            request { client.settingsMachines(serverId) }
    override suspend fun settingsMachineWorkspaces(serverId: String, machineId: String): ApiResponse =
            request { client.settingsMachineWorkspaces(serverId, machineId) }
    override suspend fun settingsAgents(): ApiResponse = request { client.settingsAgents() }
    override suspend fun updateAgent(agentId: String, body: RequestBody): ApiResponse =
            request { client.updateAgent(agentId, body.bodyAsString()) }
    override suspend fun channels(archived: String?): ApiResponse = request { client.channels(archived) }
    override suspend fun serverMembers(serverId: String): ApiResponse = request { client.serverMembers(serverId) }
    override suspend fun updateSettingsServerProfile(serverId: String, body: RequestBody): ApiResponse =
            request { client.updateSettingsServerProfile(serverId, body.bodyAsString()) }
    override suspend fun settingsServerUsage(serverId: String): ApiResponse =
            request { client.settingsServerUsage(serverId) }
    override suspend fun settingsBillingSubscription(): ApiResponse =
            request { client.settingsBillingSubscription() }
    override suspend fun createSettingsServerInvite(serverId: String, body: RequestBody): ApiResponse =
            request { client.createSettingsServerInvite(serverId, body.bodyAsString()) }
    override suspend fun settingsServerOnboardingSettings(serverId: String): ApiResponse =
            request { client.settingsServerOnboardingSettings(serverId) }
    override suspend fun updateSettingsServerOnboardingSettings(serverId: String, body: RequestBody): ApiResponse =
            request { client.updateSettingsServerOnboardingSettings(serverId, body.bodyAsString()) }
    override suspend fun deleteSettingsServer(serverId: String): ApiResponse =
            request { client.deleteSettingsServer(serverId) }
    override suspend fun memberProfile(memberId: String): ApiResponse = request { client.memberProfile(memberId) }
    override suspend fun agentWorkspaceFiles(agentId: String, dirPath: String?): ApiResponse =
            request { client.agentWorkspaceFiles(agentId, dirPath) }
    override suspend fun readAgentWorkspaceFile(agentId: String, path: String): ApiResponse =
            request { client.readAgentWorkspaceFile(agentId, path) }
    override suspend fun agentDirectMessages(agentId: String): ApiResponse =
            request { client.agentDirectMessages(agentId) }
    override suspend fun reminders(ownerAgentId: String, status: String): ApiResponse =
            request { client.reminders(ownerAgentId, status) }
    override suspend fun agentActivityLog(agentId: String, limit: Int): ApiResponse =
            request { client.agentActivityLog(agentId, limit) }
    override suspend fun channelMembers(channelId: String): ApiResponse =
            request { client.channelMembers(channelId) }
    override suspend fun createChannel(body: RequestBody): ApiResponse =
            request { client.createChannel(body.bodyAsString()) }
    override suspend fun joinChannel(channelId: String): ApiResponse = request { client.joinChannel(channelId) }
    override suspend fun archiveChannel(channelId: String): ApiResponse =
            request { client.archiveChannel(channelId) }
    override suspend fun deleteChannel(channelId: String): ApiResponse = request { client.deleteChannel(channelId) }
    override suspend fun addChannelMember(channelId: String, body: RequestBody): ApiResponse =
            request { client.addChannelMember(channelId, body.bodyAsString()) }
    override suspend fun removeChannelMember(channelId: String, kind: String, memberId: String): ApiResponse =
            request { client.removeChannelMember(channelId, kind, memberId) }
    override suspend fun directMessages(): ApiResponse = request { client.directMessages() }
    override suspend fun openDirectMessage(body: RequestBody): ApiResponse =
            request { client.openDirectMessage(body.bodyAsString()) }
    override suspend fun channelUnreadCounts(): ApiResponse = request { client.channelUnreadCounts() }
    override suspend fun markChannelReadToSeq(channelId: String, body: RequestBody): ApiResponse =
            request { client.markChannelReadToSeq(channelId, body.bodyAsString()) }
    override suspend fun markChannelReadAll(channelId: String): ApiResponse =
            request { client.markChannelReadAll(channelId) }
    override suspend fun markChannelUnread(channelId: String): ApiResponse =
            request { client.markChannelUnread(channelId) }
    override suspend fun inbox(filter: String, limit: Int, offset: Int): ApiResponse =
            request { client.inbox(filter, limit, offset) }
    override suspend fun markInboxReadAll(): ApiResponse = request { client.markInboxReadAll() }
    override suspend fun serverTasks(): ApiResponse = request { client.serverTasks() }
    override suspend fun channelTasks(channelId: String): ApiResponse = request { client.channelTasks(channelId) }
    override suspend fun claimTask(taskId: String): ApiResponse = request { client.claimTask(taskId) }
    override suspend fun updateTaskStatus(taskId: String, body: RequestBody): ApiResponse =
            request { client.updateTaskStatus(taskId, body.bodyAsString()) }
    override suspend fun saved(limit: Int, offset: Int): ApiResponse = request { client.saved(limit, offset) }
    override suspend fun saveMessage(messageId: String): ApiResponse = request { client.saveMessage(messageId) }
    override suspend fun unsaveMessage(messageId: String): ApiResponse = request { client.unsaveMessage(messageId) }
    override suspend fun searchMessages(query: String, limit: Int, offset: Int): ApiResponse =
            request { client.searchMessages(query, limit, offset) }
    override suspend fun channelMessages(channelId: String, limit: Int, beforeSeq: Long?): ApiResponse =
            request { client.channelMessages(channelId, limit, beforeSeq) }
    override suspend fun messageContext(messageId: String): ApiResponse = request { client.messageContext(messageId) }
    override suspend fun messageSync(sinceSeq: Long, limit: Int, channelId: String?): ApiResponse =
            request { client.messageSync(sinceSeq, limit, channelId) }
    override suspend fun sendMessage(body: RequestBody): ApiResponse =
            request { client.sendMessage(body.bodyAsString()) }
    override suspend fun uploadAttachments(channelId: RequestBody, files: List<MultipartBody.Part>): ApiResponse =
            request { client.uploadAttachments(channelId.bodyAsString(), files.map { it.toKmpMultipartFile() }) }
    override suspend fun attachmentUrl(attachmentId: String): ApiResponse =
            request { client.attachmentUrl(attachmentId) }
    override suspend fun channelThreads(channelId: String, limit: Int): ApiResponse =
            request { client.channelThreads(channelId, limit) }
    override suspend fun createOrOpenThread(channelId: String, body: RequestBody): ApiResponse =
            request { client.createOrOpenThread(channelId, body.bodyAsString()) }
    override suspend fun followedThreads(limit: Int): ApiResponse = request { client.followedThreads(limit) }
    override suspend fun followThread(body: RequestBody): ApiResponse =
            request { client.followThread(body.bodyAsString()) }
    override suspend fun unfollowThread(body: RequestBody): ApiResponse =
            request { client.unfollowThread(body.bodyAsString()) }
    override suspend fun markThreadDone(body: RequestBody): ApiResponse =
            request { client.markThreadDone(body.bodyAsString()) }
    override suspend fun markThreadUndone(body: RequestBody): ApiResponse =
            request { client.markThreadUndone(body.bodyAsString()) }
    override suspend fun serverUnreadSummary(): ApiResponse = request { client.serverUnreadSummary() }

    private suspend fun request(block: suspend () -> KmpHttpResponse): ApiResponse =
            try {
                ApiResponse.from(block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                throw io
            } catch (error: Exception) {
                warningLogger("kmp_api_failed reason=${error::class.java.simpleName}")
                throw IOException(error)
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

    private companion object {
        const val TAG = "SlockKmpApi"
    }
}

private class AndroidAuthTokenProvider(
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
            warningLogger("kmp_api_retry_unauthorized_clearing_session")
            store.clearSession()
        }
    }
}

private fun AuthSession.toAuthTokens(): AuthTokens =
        AuthTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                selectedServerId = currentServerId
        )
