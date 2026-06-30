package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.network.KmpHttpResponse
import build.raft.app.network.MultipartFile
import build.raft.app.network.SlockApiClient
import build.raft.app.network.SlockRequestPriority

class KtorSlockNetworkClient(
    private val client: SlockApiClient
) : SlockNetworkClient {
    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse =
        client.authenticatedRequest(
            priority = priority.toNetworkPriority(),
            method = method,
            path = path,
            query = query,
            serverIdOverride = serverIdOverride?.value,
            body = bodyText
        ).toHomeNetworkResponse()

    override suspend fun requestRawAbsoluteUrl(
        priority: DataRequestPriority,
        url: String,
        method: String,
        headers: Map<String, String>,
        bodyText: String?
    ): HomeNetworkResponse =
        client.rawAbsoluteRequest(
            priority = priority.toNetworkPriority(),
            url = url,
            method = method,
            headers = headers,
            body = bodyText
        ).toHomeNetworkResponse()

    override suspend fun uploadAttachments(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>
    ): HomeNetworkResponse =
        client.uploadAttachments(
            priority = priority.toNetworkPriority(),
            channelId = channelId.value,
            files = files
        ).toHomeNetworkResponse()

    override suspend fun uploadAccountAvatar(
        priority: DataRequestPriority,
        file: MultipartFile
    ): HomeNetworkResponse =
        client.uploadAccountAvatar(
            priority = priority.toNetworkPriority(),
            file = file
        ).toHomeNetworkResponse()

    override suspend fun uploadAgentAvatar(
        priority: DataRequestPriority,
        agentId: MemberId,
        file: MultipartFile
    ): HomeNetworkResponse =
        client.uploadAgentAvatar(
            priority = priority.toNetworkPriority(),
            agentId = agentId.value,
            file = file
        ).toHomeNetworkResponse()

    override suspend fun uploadConnectedAppLogo(
        priority: DataRequestPriority,
        clientRecordId: String,
        file: MultipartFile
    ): HomeNetworkResponse =
        client.uploadConnectedAppLogo(
            priority = priority.toNetworkPriority(),
            clientRecordId = clientRecordId,
            file = file
        ).toHomeNetworkResponse()
}

private fun DataRequestPriority.toNetworkPriority(): SlockRequestPriority =
    when (this) {
        DataRequestPriority.Foreground -> SlockRequestPriority.Foreground
        DataRequestPriority.Visible -> SlockRequestPriority.Visible
        DataRequestPriority.Background -> SlockRequestPriority.Background
    }

private fun KmpHttpResponse.toHomeNetworkResponse(): HomeNetworkResponse =
    HomeNetworkResponse(code = code, bodyText = bodyText, errorBodyText = errorBodyText)
