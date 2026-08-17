package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedServerMembersCanonicalMentionHandleTest {
    @Test
    fun currentThreadHandleBridgesParentScopeLoadRaceWithoutOverridingLoadedParent() {
        val memberId = "agent-1"
        val serverId = ServerId("server-1")
        val stateBeforeParentLoad =
            SharedServerMembersState(
                entriesByServerId =
                    mapOf(
                        serverId.value to
                            SharedServerMembersEntry(
                                serverId = serverId.value,
                                members = listOf(member(memberId, "roster-handle"))
                            )
                    ),
                channelMemberSlices =
                    mapOf(
                        SharedServerChannelMembersScope(serverId.value, "thread-child") to
                            SharedServerChannelMembersSlice(
                                serverId = serverId.value,
                                channelId = "thread-child",
                                members = listOf(member(memberId, "thread-handle"))
                            )
                    )
            )

        assertEquals(
            "thread-handle",
            stateBeforeParentLoad.canonicalMentionHandle(
                serverId = serverId,
                mentionChannelId = "parent-channel",
                fallbackChannelId = "thread-child",
                memberId = memberId
            )
        )
        assertEquals(
            "roster-handle",
            stateBeforeParentLoad.canonicalMentionHandle(
                serverId = serverId,
                mentionChannelId = "parent-channel",
                memberId = memberId
            )
        )

        val stateAfterParentLoad =
            stateBeforeParentLoad.copy(
                channelMemberSlices =
                    stateBeforeParentLoad.channelMemberSlices +
                        (
                            SharedServerChannelMembersScope(serverId.value, "parent-channel") to
                                SharedServerChannelMembersSlice(
                                    serverId = serverId.value,
                                    channelId = "parent-channel",
                                    members = listOf(member(memberId, "parent-handle"))
                                )
                        )
            )
        assertEquals(
            "parent-handle",
            stateAfterParentLoad.canonicalMentionHandle(
                serverId = serverId,
                mentionChannelId = "parent-channel",
                fallbackChannelId = "thread-child",
                memberId = memberId
            )
        )
    }

    private fun member(id: String, handle: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = handle,
            handle = handle,
            kind = MemberKind.Agent
        )
}
