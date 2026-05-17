package ai.slock.android.core.data.network

import ai.slock.compose.data.DataRequestPriority
import ai.slock.compose.data.HomeNetworkResponse
import ai.slock.compose.data.SlockHomeApi
import ai.slock.compose.network.SlockRequestPriority

class AndroidSlockHomeApi(
    private val api: SlockApi
) : SlockHomeApi {
    override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
        api.channels(priority.toSlockRequestPriority()).toHomeNetworkResponse()

    override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
        api.directMessages(priority.toSlockRequestPriority()).toHomeNetworkResponse()

    override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
        api.channelUnreadCounts(priority.toSlockRequestPriority()).toHomeNetworkResponse()

    override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
        api.serverUnreadSummary(priority.toSlockRequestPriority()).toHomeNetworkResponse()
}

private fun DataRequestPriority.toSlockRequestPriority(): SlockRequestPriority =
    when (this) {
        DataRequestPriority.Foreground -> SlockRequestPriority.Foreground
        DataRequestPriority.Visible -> SlockRequestPriority.Visible
        DataRequestPriority.Background -> SlockRequestPriority.Background
    }

private fun ApiResponse.toHomeNetworkResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = code,
        bodyText = body?.string(),
        errorBodyText = errorBody?.string()
    )
