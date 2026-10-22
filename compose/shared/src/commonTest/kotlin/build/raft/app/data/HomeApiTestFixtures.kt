package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred

/** Home API whose every endpoint answers an empty 200; pair with a `parseHome` fixture. */
internal object RootEmptyHomeApi : RaftHomeApi {
    override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(200, "[]")

    override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(200, "[]")

    override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(200, EMPTY_CHANNEL_UNREAD_SUMMARY_BODY)

    override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
        HomeNetworkResponse(200, "[]")
}

/**
 * [RootEmptyHomeApi] whose `channels` request, from call [gateFromCall] on, waits for [release]:
 * a deterministic stand-in for a Home bootstrap that is still on the network.
 */
internal class GatedChannelsHomeApi(
    private val gateFromCall: Int = 2,
) : RaftHomeApi {
    private val gate = CompletableDeferred<Unit>()
    var channelsCalls = 0
        private set

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse {
        channelsCalls += 1
        if (channelsCalls >= gateFromCall) gate.await()
        return RootEmptyHomeApi.channels(priority)
    }

    override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
        RootEmptyHomeApi.directMessages(priority)

    override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
        RootEmptyHomeApi.channelUnreadCounts(priority)

    override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
        RootEmptyHomeApi.serverUnreadSummary(priority)
}
