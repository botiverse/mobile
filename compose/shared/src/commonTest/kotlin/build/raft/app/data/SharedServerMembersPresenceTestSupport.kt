package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.model.ChannelId

internal fun SharedServerMembersStore.applyAgentActivitySnapshotForTest(
    serverId: ServerId,
    agentId: MemberId,
    activity: List<AgentActivityEntry>
) {
    applyAgentActivitySnapshot(
        request = beginAgentActivityPresenceProjection(serverId, agentId),
        agentId = agentId,
        activity = activity
    )
}

internal object PresenceNoopMemberRepository : MemberRepository {
    private fun unused(): Nothing = error("unused")

    override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> =
        unused()

    override suspend fun getMemberProfile(
        priority: DataRequestPriority,
        memberId: MemberId,
        serverId: ServerId?
    ) = unused()

    override suspend fun updateAgentProfile(priority: DataRequestPriority, request: AgentProfileUpdateRequest) =
        unused()

    override suspend fun uploadAgentAvatar(priority: DataRequestPriority, request: AgentAvatarUploadRequest) =
        unused()

    override suspend fun getAgentDetail(priority: DataRequestPriority, agentId: MemberId) = unused()
    override suspend fun getAgentDirectMessages(priority: DataRequestPriority, agentId: MemberId) = unused()
    override suspend fun getAgentReminders(priority: DataRequestPriority, agentId: MemberId) = unused()
    override suspend fun getAgentActivity(priority: DataRequestPriority, agentId: MemberId) = unused()
    override suspend fun getAgentWorkspaces(priority: DataRequestPriority, agentId: MemberId) = unused()
    override suspend fun getAgentWorkspaceTree(priority: DataRequestPriority, agentId: MemberId) = unused()

    override suspend fun getAgentWorkspaceChildren(
        priority: DataRequestPriority,
        agentId: MemberId,
        dirPath: String
    ) = unused()

    override suspend fun readAgentWorkspaceFile(
        priority: DataRequestPriority,
        agentId: MemberId,
        path: String
    ) = unused()

    override suspend fun getAgentSkills(priority: DataRequestPriority, agentId: MemberId) = unused()
    override suspend fun startAgent(priority: DataRequestPriority, agentId: MemberId) = unused()
    override suspend fun stopAgent(priority: DataRequestPriority, agentId: MemberId) = unused()
    override suspend fun resetAgent(priority: DataRequestPriority, request: AgentResetRequest) = unused()
    override suspend fun deleteAgent(priority: DataRequestPriority, agentId: MemberId, workspaceChoice: AgentArchiveWorkspaceChoice) = unused()

    override suspend fun assignAgentMachine(
        priority: DataRequestPriority,
        request: AgentMachineAssignmentRequest
    ) = unused()

    override suspend fun getChannelMembers(priority: DataRequestPriority, channelId: ChannelId) = unused()
    override suspend fun addChannelMember(priority: DataRequestPriority, request: AddChannelMemberRequest) = unused()
    override suspend fun removeChannelMember(priority: DataRequestPriority, request: RemoveChannelMemberRequest) =
        unused()
}
