package build.raft.app

import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedServerMembersState
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForRenderedAvatar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class RaftMemberAvatarResolver(
    private val currentAvatar: (String?) -> String?,
    private val avatarFlow: (String?) -> Flow<String?>
) {
    fun current(memberId: String?): String? = currentAvatar(memberId)

    operator fun invoke(memberId: String?): Flow<String?> = avatarFlow(memberId)
}

internal val EmptyRaftMemberAvatarResolver =
    RaftMemberAvatarResolver(
        currentAvatar = { null },
        avatarFlow = { flowOf(null) }
    )

internal fun raftMemberAvatarResolver(
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?,
    diagnosticsLogger: (String) -> Unit = {}
): RaftMemberAvatarResolver {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
        ?: return EmptyRaftMemberAvatarResolver
    return RaftMemberAvatarResolver(
        currentAvatar = { memberId ->
            serverMembersStore
                ?.state
                ?.value
                ?.currentMemberAvatarWireValue(cleanServerId, memberId)
        },
        avatarFlow = { memberId ->
            if (serverMembersStore == null) {
                diagnosticsLogger(
                    "result=missing_store serverHash=${cleanServerId?.value.raftAvatarDiagnosticsHash()} " +
                        "memberHash=${memberId.raftAvatarDiagnosticsHash()}"
                )
                flowOf(null)
            } else {
                serverMembersStore
                    .memberProfileFlow(cleanServerId, memberId)
                    .map { profile ->
                        val avatarWireValue = profile?.avatarWireValueForRenderedAvatar()
                        diagnosticsLogger(
                            "result=profile serverHash=${cleanServerId?.value.raftAvatarDiagnosticsHash()} " +
                                "memberHash=${memberId.raftAvatarDiagnosticsHash()} " +
                                "profile=${profile != null} " +
                                "kind=${profile?.kind?.name ?: "none"} " +
                                "avatarSource=${avatarWireValue.raftAvatarDiagnosticsSource()}"
                        )
                        avatarWireValue
                    }
                    .distinctUntilChanged()
            }
        }
    )
}

internal fun SharedServerMembersState.currentMemberAvatarWireValue(
    serverId: ServerId?,
    memberId: String?
): String? = memberProfile(serverId, memberId)?.avatarWireValueForRenderedAvatar()

@Composable
internal fun rememberRaftMemberAvatarWireValue(
    memberAvatarResolver: RaftMemberAvatarResolver,
    memberId: String?
): String? {
    val avatarFlow = remember(memberAvatarResolver, memberId) { memberAvatarResolver(memberId) }
    val initialAvatarWireValue =
        remember(memberAvatarResolver, memberId) {
            memberAvatarResolver.current(memberId)
        }
    val avatarWireValue by avatarFlow.collectAsState(initialAvatarWireValue)
    return avatarWireValue
}
