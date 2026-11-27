package build.raft.app.thread

import build.raft.app.MemberKindWireContract
import build.raft.app.thread.SharedThreadDataSource.MembersEnrichOutcome
import build.raft.app.data.AddChannelMemberRequest
import build.raft.app.data.ChannelMemberStillInChannel
import build.raft.app.data.ChannelProfileUpdateRequest
import build.raft.app.data.ChannelProfileUpdateResult
import build.raft.app.data.ChannelRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MemberRepository
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.strictMemberKindOrNull
import kotlinx.coroutines.CancellationException

internal data class ThreadRemoveChannelMemberResult(
    val payload: RaftThreadScreenPayload,
    val stillInChannel: ChannelMemberStillInChannel? = null,
)

/**
 * The channel-member commands of [SharedThreadDataSource] (join, update profile, load / add /
 * remove members, leave, archive, delete), moved out of it unchanged; the data source forwards to
 * them. Holds only the immutable collaborators it was constructed with and reads the session
 * through [readSession].
 */
internal class ThreadChannelMemberCommands(
    private val memberRepository: MemberRepository?,
    private val serverMembersStore: SharedServerMembersStore?,
    private val channelRepository: ChannelRepository?,
    private val readSession: suspend () -> StoredAuthSession?,
) {

    suspend fun joinChannel(channelId: ChannelId) =
        channelRepository?.joinChannel(channelId)
            ?: DataResult.Error(ScreenError("channel_repository_unavailable", retryable = false))

    suspend fun updateChannelProfile(
        channelId: ChannelId,
        name: String,
        description: String
    ): DataResult<ChannelProfileUpdateResult> =
        channelRepository?.updateChannelProfile(
            ChannelProfileUpdateRequest(
                channelId = channelId,
                name = name,
                description = description
            )
        ) ?: DataResult.Error(ScreenError("channel_repository_unavailable", retryable = false))

    suspend fun enrichThreadMembers(payload: RaftThreadScreenPayload): MembersEnrichOutcome {
        val threadId = payload.sourceThreadId?.takeIf { it.isNotBlank() }
            ?: return MembersEnrichOutcome.Unchanged
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val store = serverMembersStore
        val repository = memberRepository
        val channelMembers =
            try {
                if (store != null && serverId != null) {
                    store.loadChannelMembers(serverId, ChannelId(threadId), DataRequestPriority.Visible)
                } else {
                    repository?.getChannelMembers(DataRequestPriority.Visible, ChannelId(threadId))
                        ?: return MembersEnrichOutcome.Unchanged
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                return MembersEnrichOutcome.Failed("getChannelMembers threw: ${t::class.simpleName}")
            }
        if (channelMembers !is DataResult.Content) {
            // Reached only for Empty / NoPermission / Error. Treat
            // Empty as success-no-data (Loaded), NoPermission / Error
            // as transient failure so the next modal open can retry.
            return when (val result = channelMembers) {
                is DataResult.Empty -> MembersEnrichOutcome.Unchanged
                is DataResult.NoPermission ->
                    MembersEnrichOutcome.Failed("getChannelMembers: NoPermission")
                is DataResult.Error ->
                    MembersEnrichOutcome.Failed("getChannelMembers: Error(${result.error.code})")
                is DataResult.Content -> error("unreachable: filtered by !is DataResult.Content above")
            }
        }
        val serverMembers = serverId?.let { serverMembersStore?.currentServerMembers(it) }
        val members =
            channelMembers.data.members
                .distinctBy { it.memberKey }
                .map { it.toRaftThreadMemberItem(serverId) }
        val candidates =
            if (serverMembers == null) {
                payload.memberCandidates
            } else {
                threadAddMemberCandidatesExcludingJoined(
                    candidates = serverMembers.map { it.toRaftThreadMemberItem(serverId) },
                    members = members,
                    localServerId = serverId?.value
                )
            }
        return MembersEnrichOutcome.Updated(
            payload.copy(
                membersCount = members.size,
                members = members,
                memberCandidates = candidates,
                canAddMembers = channelMembers.data.canAddMembers
            )
        )
    }

    suspend fun addChannelMember(
        payload: RaftThreadScreenPayload,
        memberId: String,
        kind: String?
    ): DataResult<RaftThreadScreenPayload> {
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "thread_member_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val memberKind =
            kind.strictMemberKindOrNull(
                source = "thread_add_member",
                contract = MemberKindWireContract.Projected,
            )
                ?: return DataResult.Error(ScreenError(code = "thread_member_kind_missing", retryable = false))
        val cleanMemberId =
            memberId.takeIf { it.isNotBlank() }?.let(::MemberId)
                ?: return DataResult.Error(ScreenError(code = "thread_member_id_missing", retryable = false))
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val request =
            AddChannelMemberRequest(
                channelId = channelId,
                memberId = cleanMemberId,
                kind = memberKind
            )
        return when (
            val result =
                if (serverMembersStore != null && serverId != null) {
                    serverMembersStore.addChannelMember(
                        serverId = serverId,
                        request = request,
                        priority = DataRequestPriority.Foreground
                    )
                } else {
                    repository.addChannelMember(DataRequestPriority.Foreground, request)
                }
        ) {
            is DataResult.Content ->
                DataResult.Content(
                    refreshMembersAfterMutation(
                        payload.copy(addMemberStatus = "idle", addMemberErrorCode = null)
                    )
                )
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun removeChannelMember(
        payload: RaftThreadScreenPayload,
        memberId: String,
        kind: String?
    ): DataResult<ThreadRemoveChannelMemberResult> {
        val repository =
            memberRepository
                ?: return DataResult.Error(ScreenError(code = "thread_member_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val memberKind =
            kind.strictMemberKindOrNull(
                source = "thread_remove_member",
                contract = MemberKindWireContract.Projected,
            )
                ?: return DataResult.Error(ScreenError(code = "thread_member_kind_missing", retryable = false))
        val cleanMemberId =
            memberId.takeIf { it.isNotBlank() }?.let(::MemberId)
                ?: return DataResult.Error(ScreenError(code = "thread_member_id_missing", retryable = false))
        val serverId = readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)
        val request =
            RemoveChannelMemberRequest(
                channelId = channelId,
                memberId = cleanMemberId,
                kind = memberKind
            )
        return when (
            val result =
                if (serverMembersStore != null && serverId != null) {
                    serverMembersStore.removeChannelMember(
                        serverId = serverId,
                        request = request,
                        priority = DataRequestPriority.Foreground
                    )
                } else {
                    repository.removeChannelMember(DataRequestPriority.Foreground, request)
                }
        ) {
            is DataResult.Content ->
                DataResult.Content(
                    ThreadRemoveChannelMemberResult(
                        payload =
                            refreshMembersAfterMutation(
                                payload.copy(
                                    removeMemberStatus = "idle",
                                    removingMemberId = null,
                                    removeMemberErrorCode = null
                                )
                            ),
                        stillInChannel = result.data.stillInChannel,
                    )
                )
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun leaveChannel(payload: RaftThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.leaveChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun archiveChannel(payload: RaftThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.archiveChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun deleteChannel(payload: RaftThreadScreenPayload): DataResult<Unit> {
        val repository =
            channelRepository
                ?: return DataResult.Error(ScreenError(code = "thread_channel_repository_missing", retryable = false))
        val channelId =
            payload.sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        return when (val result = repository.deleteChannel(channelId)) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    private suspend fun refreshMembersAfterMutation(payload: RaftThreadScreenPayload): RaftThreadScreenPayload {
        return when (val outcome = enrichThreadMembers(payload)) {
            is SharedThreadDataSource.MembersEnrichOutcome.Updated -> outcome.payload
            is SharedThreadDataSource.MembersEnrichOutcome.Unchanged,
            is SharedThreadDataSource.MembersEnrichOutcome.Failed -> payload
        }
    }
}
