package build.raft.app.network

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.SlockHomeApi

class SlockApiClientHomeApi(
    private val client: SlockApiClient
) : SlockHomeApi {
    override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
        client.channels(priority.toRequestPriority()).toHomeNetworkResponse()

    override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
        client.directMessages(priority.toRequestPriority()).toHomeNetworkResponse()

    override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
        client.channelUnreadCounts(priority.toRequestPriority()).toHomeNetworkResponse()

    override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
        client.serverUnreadSummary(priority.toRequestPriority()).toHomeNetworkResponse()
}

fun DataRequestPriority.toRequestPriority(): SlockRequestPriority =
    when (this) {
        DataRequestPriority.Foreground -> SlockRequestPriority.Foreground
        DataRequestPriority.Visible -> SlockRequestPriority.Visible
        DataRequestPriority.Background -> SlockRequestPriority.Background
    }

fun KmpHttpResponse.toHomeNetworkResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = code,
        bodyText = bodyText,
        errorBodyText = errorBodyText
    )
