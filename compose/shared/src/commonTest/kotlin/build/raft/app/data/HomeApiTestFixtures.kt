package build.raft.app.data

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
