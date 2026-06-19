package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ServerId
import ai.slock.compose.network.KmpHttpResponse
import ai.slock.compose.network.MultipartFile
import ai.slock.compose.network.SlockApiClient
import ai.slock.compose.network.SlockRequestPriority

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
}

private fun DataRequestPriority.toNetworkPriority(): SlockRequestPriority =
    when (this) {
        DataRequestPriority.Foreground -> SlockRequestPriority.Foreground
        DataRequestPriority.Visible -> SlockRequestPriority.Visible
        DataRequestPriority.Background -> SlockRequestPriority.Background
    }

private fun KmpHttpResponse.toHomeNetworkResponse(): HomeNetworkResponse =
    HomeNetworkResponse(code = code, bodyText = bodyText, errorBodyText = errorBodyText)
