package build.raft.app

import build.raft.app.data.RaftDisplayPreferenceRuntime
import build.raft.app.data.memberOrigin
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
    private val labelFlow: (String?) -> Flow<String?>,
    private val currentAuthorFacts: (String?) -> RaftMemberAuthorFacts? = { null },
    private val authorFactsFlow: (String?) -> Flow<RaftMemberAuthorFacts?> = { flowOf(null) },
) {
    fun current(memberId: String?): String? = currentLabel(memberId)

    operator fun invoke(memberId: String?): Flow<String?> = labelFlow(memberId)

    fun currentFacts(memberId: String?): RaftMemberAuthorFacts? = currentAuthorFacts(memberId)

    fun facts(memberId: String?): Flow<RaftMemberAuthorFacts?> = authorFactsFlow(memberId)
}

/**
 * What the message author row reads about its sender from the members directory besides the model
 * (dm:@artin:#120, web MessageItem botiverse/slock #14629): the description (directory first, so a
 * live `message:new`, which omits `senderDescription`, shows it too) and whether the sender is a
 * Guest of the viewed server.
 */
internal data class RaftMemberAuthorFacts(
    val description: String? = null,
    val viewerServerGuest: Boolean = false,
)

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
                    .map { profile -> profile?.authorRowModelLabel(cleanServerId) }
                    .distinctUntilChanged()
            }
        },
        currentAuthorFacts = { memberId ->
            serverMembersStore?.state?.value?.memberProfile(cleanServerId, memberId)?.authorFacts(cleanServerId)
        },
        authorFactsFlow = { memberId ->
            if (serverMembersStore == null) {
                flowOf(null)
            } else {
                serverMembersStore
                    .memberProfileFlow(cleanServerId, memberId)
                    .map { profile -> profile?.authorFacts(cleanServerId) }
                    .distinctUntilChanged()
            }
        },
    )
}

internal fun SharedServerMembersState.currentMemberModelLabel(
    serverId: ServerId?,
    memberId: String?
): String? = memberProfile(serverId, memberId)?.authorRowModelLabel(serverId)

/**
 * The model the message author row names (web MessageItem `agentModelLabelText`): this server's
 * agents only, never a personal assistant's (nor an App Bot's, [modelLabel]).
 */
internal fun build.raft.app.model.MemberProfile.authorRowModelLabel(viewerServerId: ServerId?): String? {
    if (!assistantOwnerUserId.isNullOrBlank()) return null
    if (isFromAnotherServer(viewerServerId)) return null
    return modelLabel()
}

/** The author row's directory facts about [this] sender, as seen from [viewerServerId]. */
internal fun build.raft.app.model.MemberProfile.authorFacts(viewerServerId: ServerId?): RaftMemberAuthorFacts =
    RaftMemberAuthorFacts(
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        // web `isViewerServerGuest`: a Guest label is a fact of the viewed server only.
        viewerServerGuest =
            kind == build.raft.app.model.MemberKind.Human &&
                (serverRole ?: effectiveChannelRole)?.trim()?.lowercase() == "guest" &&
                !isFromAnotherServer(viewerServerId),
    )

/**
 * The one "from another server" rule ([memberOrigin], #4656): a joint roster's `serverId` is only
 * the person's first source, so `inViewerServer` decides when the server sent it.
 */
private fun build.raft.app.model.MemberProfile.isFromAnotherServer(viewerServerId: ServerId?): Boolean =
    memberOrigin(this, viewerServerId?.value).external

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
    // Settings > Appearance "Show agent model" (Web MessageItem `showAgentModelName`).
    return label.takeIf { RaftDisplayPreferenceRuntime.showAgentModelName }
}

/** [RaftMemberAuthorFacts] of [memberId], live from the directory; null while nothing knows them. */
@Composable
internal fun rememberRaftMemberAuthorFacts(
    resolver: RaftMemberModelLabelResolver,
    memberId: String?
): RaftMemberAuthorFacts? {
    val factsFlow = remember(resolver, memberId) { resolver.facts(memberId) }
    val initialFacts = remember(resolver, memberId) { resolver.currentFacts(memberId) }
    val facts by factsFlow.collectAsState(initialFacts)
    return facts
}
