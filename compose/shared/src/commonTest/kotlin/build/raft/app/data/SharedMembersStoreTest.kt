package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedMembersStoreTest {
    @Test
    fun channelSuggestionsLoadOncePerServer() = runBlocking {
        val channelRepository = FakeChannelRepository()
        val store = store(channelRepository)

        store.dispatch(SharedMembersIntent.EnsureChannelSuggestions)
        store.dispatch(SharedMembersIntent.EnsureChannelSuggestions)

        assertEquals(1, channelRepository.getChannelsCalls)
        assertEquals(listOf(SharedMembersChannelSuggestion("general", "team")), store.state.value.channelSuggestions)
        assertEquals(SharedMembersLoadStatus.Loaded, store.state.value.channelStatus)
    }

    @Test
    fun channelSuggestionsForceRefresh() = runBlocking {
        val channelRepository = FakeChannelRepository()
        val store = store(channelRepository)

        store.dispatch(SharedMembersIntent.EnsureChannelSuggestions)
        channelRepository.channelsByServer["server-1"] = listOf(channel("channel-2", "random", "chat"))
        store.dispatch(SharedMembersIntent.RefreshChannelSuggestions)

        assertEquals(2, channelRepository.getChannelsCalls)
        assertEquals(listOf(SharedMembersChannelSuggestion("random", "chat")), store.state.value.channelSuggestions)
    }

    @Test
    fun channelSuggestionsIgnoreArchivedAndDirectMessages() = runBlocking {
        val channelRepository =
            FakeChannelRepository(
                channels =
                    listOf(
                        channel("channel-1", "general", "team"),
                        channel("channel-2", "archived", "old", archived = true),
                        channel("dm-1", "cindy", "dm", kind = ConversationKind.DirectMessage)
                    )
            )
        val store = store(channelRepository)

        store.dispatch(SharedMembersIntent.EnsureChannelSuggestions)

        assertEquals(listOf(SharedMembersChannelSuggestion("general", "team")), store.state.value.channelSuggestions)
    }

    private fun store(channelRepository: FakeChannelRepository): SharedMembersStore =
        SharedMembersStore(
            authStore = InMemoryRaftAuthSessionStore(sampleSession()),
            channelRepository = channelRepository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )

    private class FakeChannelRepository(
        channels: List<HomeChannelRow> = listOf(channel("channel-1", "general", "team"))
    ) : ChannelRepository {
        val channelsByServer = mutableMapOf("server-1" to channels)
        var getChannelsCalls = 0

        override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> {
            getChannelsCalls += 1
            return DataResult.Content(ChannelSnapshot(server(serverId.value), channelsByServer[serverId.value].orEmpty()))
        }

        override suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot> = error("unused")
        override suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot> = error("unused")
        override suspend fun openAgentDirectMessage(agentId: MemberId): DataResult<OpenDirectMessageResult> = error("unused")
        override suspend fun openUserDirectMessage(userId: MemberId): DataResult<OpenDirectMessageResult> = error("unused")
        override suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult> = error("unused")
        override suspend fun joinChannel(channelId: ChannelId): DataResult<ChannelJoinResult> = error("unused")
        override suspend fun leaveChannel(channelId: ChannelId): DataResult<ChannelLeaveResult> = error("unused")
        override suspend fun archiveChannel(channelId: ChannelId): DataResult<ChannelArchiveResult> = error("unused")
        override suspend fun deleteChannel(channelId: ChannelId): DataResult<ChannelDeleteResult> = error("unused")
        override suspend fun updateChannel(
            channelId: ChannelId,
            visibility: ChannelVisibility
        ): DataResult<ChannelUpdateResult> = error("unused")
        override suspend fun updateChannelProfile(
            request: ChannelProfileUpdateRequest
        ): DataResult<ChannelProfileUpdateResult> = error("unused")
        override suspend fun setChannelActivityMuted(
            channelId: ChannelId,
            muted: Boolean
        ): DataResult<ChannelActivityMuteSettings> = error("unused")
    }
}

private fun server(id: String): build.raft.app.model.ServerSummary =
    build.raft.app.model.ServerSummary(id = ServerId(id), name = "Workspace", slug = "workspace")

private fun sampleSession(): StoredAuthSession =
    StoredAuthSession(
        user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
        accessToken = "token-a",
        refreshToken = "token-r",
        selectedServerId = "server-1",
        servers = listOf(StoredAuthServer(id = "server-1", name = "Workspace", slug = "workspace"))
    )

private fun channel(
    id: String,
    name: String,
    subtitle: String? = null,
    kind: ConversationKind = ConversationKind.Channel,
    archived: Boolean = false
): HomeChannelRow =
    HomeChannelRow(
        threadId = ThreadId(id),
        channelId = ChannelId(id),
        name = name,
        kind = kind,
        subtitle = subtitle,
        archived = archived
    )
