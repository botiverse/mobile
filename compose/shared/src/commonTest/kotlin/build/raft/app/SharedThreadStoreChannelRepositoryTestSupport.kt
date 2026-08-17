package build.raft.app

import build.raft.app.data.ChannelActivityMuteSettings
import build.raft.app.data.ChannelArchiveResult
import build.raft.app.data.ChannelDeleteResult
import build.raft.app.data.ChannelJoinResult
import build.raft.app.data.ChannelLeaveResult
import build.raft.app.data.ChannelProfileUpdateRequest
import build.raft.app.data.ChannelProfileUpdateResult
import build.raft.app.data.ChannelRepository
import build.raft.app.data.ChannelSnapshot
import build.raft.app.data.ChannelUpdateResult
import build.raft.app.data.ChannelVisibility
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.CreateChannelResult
import build.raft.app.data.DataResult
import build.raft.app.data.DirectMessageSnapshot
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.toHomeChannelRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId

internal class FakeChannelRepository(
    private val profileUpdater: suspend (ChannelProfileUpdateRequest) -> DataResult<ChannelProfileUpdateResult> = {
        error("unused")
    },
    private val channelsResult: (suspend (ServerId) -> DataResult<ChannelSnapshot>)? = null,
    private val directMessagesResult: (suspend (ServerId) -> DataResult<DirectMessageSnapshot>)? = null,
    private val homeResult: (suspend (ServerId) -> DataResult<HomeSnapshot>)? = null,
) : ChannelRepository {
    val createRequests: MutableList<CreateChannelRequest> = mutableListOf()
    val profileUpdateRequests: MutableList<ChannelProfileUpdateRequest> = mutableListOf()
    var homeCalls: Int = 0
    var channelsCalls: Int = 0
    var directMessageCalls: Int = 0
    private var directoryHomeResultForBackground: Pair<ServerId, DataResult<HomeSnapshot>>? = null

    override suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot> {
        homeCalls += 1
        directoryHomeResultForBackground
            ?.takeIf { cached -> cached.first == serverId }
            ?.let { cached ->
                directoryHomeResultForBackground = null
                return cached.second
            }
        return homeResult?.invoke(serverId) ?: error("unused")
    }

    override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> {
        channelsCalls += 1
        channelsResult?.let { result -> return result(serverId) }
        val home = homeResult?.invoke(serverId) ?: error("unused")
        directoryHomeResultForBackground = serverId to home
        return when (home) {
            is DataResult.Content ->
                DataResult.Content(
                    ChannelSnapshot(
                        server = home.data.server,
                        channels = home.data.channels,
                    )
                )
            is DataResult.Empty -> home
            is DataResult.Error -> home
            is DataResult.NoPermission -> home
        }
    }

    override suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot> {
        directMessageCalls += 1
        directMessagesResult?.let { result -> return result(serverId) }
        val home = homeResult?.invoke(serverId) ?: error("unused")
        directoryHomeResultForBackground = serverId to home
        return when (home) {
            is DataResult.Content ->
                DataResult.Content(
                    DirectMessageSnapshot(
                        server = home.data.server,
                        directMessages = home.data.directMessages,
                    )
                )
            is DataResult.Empty -> home
            is DataResult.Error -> home
            is DataResult.NoPermission -> home
        }
    }

    override suspend fun openAgentDirectMessage(agentId: MemberId): DataResult<OpenDirectMessageResult> =
        error("unused")

    override suspend fun openUserDirectMessage(userId: MemberId): DataResult<OpenDirectMessageResult> =
        error("unused")

    override suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult> {
        createRequests += request
        return DataResult.Content(
            CreateChannelResult(
                channel =
                    ConversationSummary(
                        threadId = ThreadId("channel-created"),
                        channelId = ChannelId("channel-created"),
                        kind = ConversationKind.Channel,
                        name = request.name,
                    ).toHomeChannelRow(),
                addedAgentMemberIds = request.agentMemberIds,
                addedUserMemberIds = request.userMemberIds,
            )
        )
    }

    override suspend fun joinChannel(channelId: ChannelId): DataResult<ChannelJoinResult> = error("unused")

    override suspend fun leaveChannel(channelId: ChannelId): DataResult<ChannelLeaveResult> = error("unused")

    override suspend fun archiveChannel(channelId: ChannelId): DataResult<ChannelArchiveResult> = error("unused")

    override suspend fun deleteChannel(channelId: ChannelId): DataResult<ChannelDeleteResult> = error("unused")

    override suspend fun updateChannel(
        channelId: ChannelId,
        visibility: ChannelVisibility,
    ): DataResult<ChannelUpdateResult> = error("unused")

    override suspend fun updateChannelProfile(
        request: ChannelProfileUpdateRequest
    ): DataResult<ChannelProfileUpdateResult> {
        profileUpdateRequests += request
        return profileUpdater(request)
    }

    override suspend fun setChannelActivityMuted(
        channelId: ChannelId,
        muted: Boolean,
    ): DataResult<ChannelActivityMuteSettings> = error("unused")
}
