package ai.slock.compose.data

import ai.slock.compose.model.MemberKind

/**
 * KMP-side data source for the Members page **humans section** flows.
 *
 * Per @artin 2026-06-17 #members专修 msg=f1db7897 ("应该抽象一个统一的"),
 * the create-agent flow was extracted to its own
 * [SharedCreateAgentCoordinator]. This data source now owns only the
 * members-side surface that is **not** the create-agent flow:
 *   - `loadServerMembers`: GET /servers/{id}/members for the humans "+"
 *     picker (candidates minus current channel members).
 *   - `addChannelMember`: POST /channels/{id}/members with the kind-keyed
 *     body (`{agentId}` or `{userId}`) for the humans section "+"
 *     when the chosen UX is "add existing human to channel".
 *
 * If the humans "+" UX turns out to be "invite by email" instead (per the
 * 2026-06-17 #43 spec, artin said "对齐 react" — see
 * `third_party/slock-source/packages/web/src/components/agent/ChannelMembers.tsx`),
 * the invite API methods will be added here too; the create-agent methods
 * will not come back.
 */
internal class SharedMembersDataSource(
    private val memberRepository: MemberRepository
) {
    suspend fun loadServerMembers(
        serverId: String
    ): DataResult<List<ai.slock.compose.model.MemberProfile>> {
        return when (val result = memberRepository.getMembers(DataRequestPriority.Foreground, ai.slock.compose.model.ServerId(serverId))) {
            is DataResult.Content -> DataResult.Content(result.data.members)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }

    suspend fun addChannelMember(
        channelId: String,
        memberId: String,
        kind: MemberKind
    ): DataResult<Unit> {
        return when (val result = memberRepository.addChannelMember(
            DataRequestPriority.Foreground,
            AddChannelMemberRequest(
                channelId = ai.slock.compose.model.ChannelId(channelId),
                memberId = ai.slock.compose.model.MemberId(memberId),
                kind = kind
            )
        )) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }
}
