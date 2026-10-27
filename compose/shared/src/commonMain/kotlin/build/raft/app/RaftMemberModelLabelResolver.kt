package build.raft.app

import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedServerMembersState
import build.raft.app.model.ServerId
import build.raft.app.model.getModelLabel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Resolves the agent model label shown in the message header (task #392,
 * port of Web PR #7545). Mirrors `RaftMemberAvatarResolver`: the model is
 * not on the message wire (same as Web — `messages` has no model field), so
 * it is looked up live from the members directory by `senderId`, exactly as
 * Web reads `store.agents[senderId].model`.
 *
 * Returns `null` for humans, system senders, agents whose roster/profile
 * carries no `model`, and whenever the store is absent — the header renders
 * nothing rather than a raw id.
 */
internal class RaftMemberModelLabelResolver(
    private val currentLabel: (String?) -> String?,
    private val labelFlow: (String?) -> Flow<String?>
) {
    fun current(memberId: String?): String? = currentLabel(memberId)

    operator fun invoke(memberId: String?): Flow<String?> = labelFlow(memberId)
}

internal val EmptyRaftMemberModelLabelResolver =
    RaftMemberModelLabelResolver(
        currentLabel = { null },
        labelFlow = { flowOf(null) }
    )

internal fun raftMemberModelLabelResolver(
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?
): RaftMemberModelLabelResolver {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
        ?: return EmptyRaftMemberModelLabelResolver
    return RaftMemberModelLabelResolver(
        currentLabel = { memberId ->
            serverMembersStore
                ?.state
                ?.value
                ?.currentMemberModelLabel(cleanServerId, memberId)
        },
        labelFlow = { memberId ->
            if (serverMembersStore == null) {
                flowOf(null)
            } else {
                serverMembersStore
                    .memberProfileFlow(cleanServerId, memberId)
                    .map { profile -> profile?.modelLabel() }
                    .distinctUntilChanged()
            }
        }
    )
}

internal fun SharedServerMembersState.currentMemberModelLabel(
    serverId: ServerId?,
    memberId: String?
): String? = memberProfile(serverId, memberId)?.modelLabel()

internal fun build.raft.app.model.MemberProfile.modelLabel(): String? {
    if (kind != build.raft.app.model.MemberKind.Agent) return null
    // An App Bot runs no model: `agents.model` keeps the column default (botiverse/slock #11273
    // MessageItem `isAppBotSender`). Its row carries the Bot badge instead.
    if (!managedByAppInstallId.isNullOrBlank()) return null
    val rawModel = model?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return getModelLabel(runtimeId = roleDescription.orEmpty(), modelId = rawModel)
}

@Composable
internal fun rememberRaftMemberModelLabel(
    modelLabelResolver: RaftMemberModelLabelResolver,
    memberId: String?
): String? {
    val labelFlowValue = remember(modelLabelResolver, memberId) { modelLabelResolver(memberId) }
    val initialLabel =
        remember(modelLabelResolver, memberId) {
            modelLabelResolver.current(memberId)
        }
    val label by labelFlowValue.collectAsState(initialLabel)
    return label
}
