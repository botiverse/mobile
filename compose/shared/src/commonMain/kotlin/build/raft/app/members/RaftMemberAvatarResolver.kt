package build.raft.app.members

import build.raft.app.data.ActorResolveRef
import build.raft.app.data.ResolvedActor
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedServerMembersState
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.avatarSlotResolution
import build.raft.app.model.avatarWireValueForRenderedAvatar
import build.raft.app.model.resolveAvatarSlot
import build.raft.app.raftAvatarDiagnosticsHash
import build.raft.app.raftAvatarDiagnosticsSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class RaftMemberAvatarResolver(
    private val currentAvatar: (String?) -> String?,
    private val avatarFlow: (String?) -> Flow<String?>,
    private val currentJointActorRead: (MemberKind, String) -> ResolvedActor? = { _, _ -> null },
    private val jointActorRead: (MemberKind, String) -> Flow<ResolvedActor?> = { _, _ -> flowOf(null) },
    /**
     * The rows this resolver serves are a joint channel's (or a thread in one): message rows may
     * read [jointActor] for senders from the other server.
     */
    val jointSurface: Boolean = false,
    private val currentAgentPending: (String?, Boolean) -> Boolean = { _, _ -> false },
    private val agentPendingFlow: (String?, Boolean) -> Flow<Boolean> = { _, _ -> flowOf(false) },
) {
    fun current(memberId: String?): String? = currentAvatar(memberId)

    operator fun invoke(memberId: String?): Flow<String?> = avatarFlow(memberId)

    /** True while [memberId]'s agent avatar is not yet known and not yet confirmed absent. */
    fun isAgentAvatarPending(memberId: String?, joint: Boolean = jointSurface): Boolean =
        currentAgentPending(memberId, joint)

    fun agentAvatarPendingFlow(memberId: String?, joint: Boolean = jointSurface): Flow<Boolean> =
        agentPendingFlow(memberId, joint)

    /** This resolver for rows whose surface is ([joint]) or is not a joint channel. */
    fun forJointSurface(joint: Boolean): RaftMemberAvatarResolver =
        if (joint == jointSurface) {
            this
        } else {
            RaftMemberAvatarResolver(
                currentAvatar = currentAvatar,
                avatarFlow = avatarFlow,
                currentJointActorRead = currentJointActorRead,
                jointActorRead = jointActorRead,
                jointSurface = joint,
                currentAgentPending = currentAgentPending,
                agentPendingFlow = agentPendingFlow,
            )
        }

    /**
     * A joint channel's sender from the other server, as `POST /api/actors/resolve` answered: null
     * while this server's members / agents know [memberId] (they always win), and asked for when
     * collected and still missing. Only joint surfaces may read this.
     */
    fun jointActor(kind: MemberKind?, memberId: String?): Flow<ResolvedActor?> {
        val ref = jointActorRefOrNull(kind, memberId) ?: return flowOf(null)
        return jointActorRead(ref.kind, ref.id)
    }

    fun currentJointActor(kind: MemberKind?, memberId: String?): ResolvedActor? {
        val ref = jointActorRefOrNull(kind, memberId) ?: return null
        return currentJointActorRead(ref.kind, ref.id)
    }
}

private fun jointActorRefOrNull(kind: MemberKind?, memberId: String?): ActorResolveRef? {
    if (kind != MemberKind.Human && kind != MemberKind.Agent) return null
    val id = memberId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return ActorResolveRef(kind = kind, id = id)
}

/** The resolved actor's own avatar (its uploaded image), else null. */
internal fun ResolvedActor.explicitAvatarWireValue(): String? =
    resolveAvatarSlot(
        kind = kind,
        id = id,
        avatarWireValue = null,
        avatarUrl = avatarUrl,
    ).displayAvatarWireValue

/** What a message row paints for the resolved actor: its own avatar, or an agent's default face. */
internal fun ResolvedActor.renderedAvatarWireValue(): String? =
    avatarWireValueForRenderedAvatar(
        kind = kind,
        id = id,
        avatarWireValue = null,
        avatarUrl = avatarUrl,
    )

internal val EmptyRaftMemberAvatarResolver =
    RaftMemberAvatarResolver(
        currentAvatar = { null },
        avatarFlow = { flowOf(null) }
    )

@OptIn(ExperimentalCoroutinesApi::class)
internal fun raftMemberAvatarResolver(
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?,
    diagnosticsLogger: (String) -> Unit = {}
): RaftMemberAvatarResolver {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
        ?: return EmptyRaftMemberAvatarResolver
    val jointActors = serverMembersStore?.jointActors
    return RaftMemberAvatarResolver(
        currentJointActorRead = { kind, memberId ->
            jointActors
                ?.takeIf { !serverMembersStore.currentAvatarMemberProfileResolution(cleanServerId, memberId).authoritative }
                ?.current(cleanServerId, memberId, kind)
        },
        jointActorRead = { kind, memberId ->
            if (jointActors == null) {
                flowOf(null)
            } else {
                // A sender this server authoritatively knows keeps its own face (or its absence);
                // non-authoritative message-author candidates do not block actor resolution.
                serverMembersStore
                    .memberAvatarProfileResolutionFlow(cleanServerId, memberId)
                    .map { resolution -> resolution.authoritative }
                    .distinctUntilChanged()
                    .flatMapLatest { knownLocally ->
                        if (knownLocally) flowOf(null) else jointActors.actorFlow(cleanServerId, ActorResolveRef(kind, memberId))
                    }
                    .distinctUntilChanged()
            }
        },
        currentAvatar = { memberId ->
            serverMembersStore?.let { store ->
                val resolution = store.currentAvatarMemberProfileResolution(cleanServerId, memberId)
                val cachedJoint =
                    if (!resolution.authoritative && !memberId.isNullOrBlank()) {
                        jointActors?.current(cleanServerId, memberId, kind = null)
                    } else {
                        null
                    }
                if (cachedJoint != null) {
                    cachedJoint.explicitAvatarWireValue()
                } else {
                    resolution.resolvedAvatarWireValue()
                        ?: store.durableMemberAvatarWireValue(cleanServerId, memberId)
                }
            }
        },
        avatarFlow = { memberId ->
            if (serverMembersStore == null) {
                diagnosticsLogger(
                    "result=missing_store serverHash=${cleanServerId?.value.raftAvatarDiagnosticsHash()} " +
                        "memberHash=${memberId.raftAvatarDiagnosticsHash()}"
                )
                flowOf(null)
            } else {
                val baseResolutionFlow = serverMembersStore.memberAvatarProfileResolutionFlow(cleanServerId, memberId)
                val resolutionWithJointFlow: Flow<Pair<SharedServerMembersState.ResolvedMemberProfile, ResolvedActor?>> =
                    if (jointActors == null || memberId.isNullOrBlank()) {
                        baseResolutionFlow.map { resolution -> resolution to null }
                    } else {
                        combine(
                            baseResolutionFlow,
                            jointActors.cachedActorFlow(cleanServerId, memberId, kind = null),
                        ) { resolution, cachedJoint ->
                            resolution to cachedJoint.takeUnless { resolution.authoritative }
                        }
                    }
                resolutionWithJointFlow
                    .map { (resolution, cachedJoint) ->
                        val profile = resolution.profile
                        // Cold start: until the directory (or a profile carrying an avatar) lands,
                        // render the last authoritative avatar persisted for this member. A
                        // non-authoritative message-author candidate with no explicit avatar must
                        // stay null so row-carried avatars and POST /api/actors/resolve are not
                        // overshadowed by a synthesized pixel:robot default. Once actors/resolve or
                        // actor:updated resolves an actor not authoritatively known locally, its
                        // avatar also outranks any stale non-authoritative candidate.
                        val liveAvatarWireValue =
                            if (cachedJoint != null) {
                                cachedJoint.explicitAvatarWireValue()
                            } else {
                                resolution.resolvedAvatarWireValue()
                            }
                        val avatarWireValue =
                            if (cachedJoint != null) {
                                liveAvatarWireValue
                            } else {
                                liveAvatarWireValue
                                    ?: serverMembersStore.durableMemberAvatarWireValue(cleanServerId, memberId)
                            }
                        diagnosticsLogger(
                            "result=profile serverHash=${cleanServerId?.value.raftAvatarDiagnosticsHash()} " +
                                "memberHash=${memberId.raftAvatarDiagnosticsHash()} " +
                                "profile=${profile != null} " +
                                "kind=${profile?.kind?.name ?: "none"} " +
                                "avatarSource=${avatarWireValue.raftAvatarDiagnosticsSource()} " +
                                "durable=${cachedJoint == null && liveAvatarWireValue == null && avatarWireValue != null}"
                        )
                        avatarWireValue
                    }
                    .distinctUntilChanged()
            }
        },
        currentAgentPending = { memberId, joint ->
            val cleanId = memberId?.trim()?.takeIf { it.isNotEmpty() }
            if (serverMembersStore == null || cleanId == null) {
                false
            } else {
                val resolution = serverMembersStore.currentAvatarMemberProfileResolution(cleanServerId, cleanId)
                val knownAvatar =
                    resolution.authoritative ||
                        resolution.resolvedAvatarWireValue() != null ||
                        serverMembersStore.durableMemberAvatarWireValue(cleanServerId, cleanId) != null ||
                        jointActors?.current(cleanServerId, cleanId, kind = null) != null
                if (knownAvatar) {
                    false
                } else {
                    val rosterSettled = serverMembersStore.isServerRosterSettled(cleanServerId)
                    val jointSettled = !joint || (jointActors?.isSettled(cleanServerId, MemberKind.Agent, cleanId) ?: true)
                    !(rosterSettled && jointSettled)
                }
            }
        },
        agentPendingFlow = { memberId, joint ->
            val cleanId = memberId?.trim()?.takeIf { it.isNotEmpty() }
            if (serverMembersStore == null || cleanId == null) {
                flowOf(false)
            } else {
                val resolutionFlow = serverMembersStore.memberAvatarProfileResolutionFlow(cleanServerId, cleanId)
                val rosterSettledFlow = serverMembersStore.serverRosterSettledFlow(cleanServerId)
                val cachedJointFlow =
                    jointActors?.cachedActorFlow(cleanServerId, cleanId, kind = null) ?: flowOf(null)
                val jointSettledFlow =
                    if (joint && jointActors != null) {
                        jointActors.settledFlow(cleanServerId, MemberKind.Agent, cleanId)
                    } else {
                        flowOf(true)
                    }
                combine(resolutionFlow, rosterSettledFlow, cachedJointFlow, jointSettledFlow) {
                    resolution,
                    rosterSettled,
                    cachedJoint,
                    jointSettled ->
                    val knownAvatar =
                        resolution.authoritative ||
                            resolution.resolvedAvatarWireValue() != null ||
                            serverMembersStore.durableMemberAvatarWireValue(cleanServerId, cleanId) != null ||
                            cachedJoint != null
                    !knownAvatar && !(rosterSettled && jointSettled)
                }.distinctUntilChanged()
            }
        },
    )
}

internal fun SharedServerMembersState.ResolvedMemberProfile.resolvedAvatarWireValue(): String? {
    val current = profile ?: return null
    return if (authoritative) {
        current.avatarWireValueForRenderedAvatar()
    } else {
        current.avatarSlotResolution().displayAvatarWireValue
    }
}

internal fun SharedServerMembersState.currentMemberAvatarWireValue(
    serverId: ServerId?,
    memberId: String?
): String? = resolveMemberProfile(serverId, memberId).resolvedAvatarWireValue()

/**
 * The resolved joint-channel sender for a row, or null. [enabled] is the row's own "joint channel"
 * fact: elsewhere every sender is this server's and nothing is asked.
 */
@Composable
internal fun rememberRaftJointActor(
    memberAvatarResolver: RaftMemberAvatarResolver,
    kind: MemberKind?,
    memberId: String?,
    enabled: Boolean,
): ResolvedActor? {
    // Keyed as a whole: `collectAsState` keeps its state across a new flow, so without the key the
    // first frame after the member changes would still paint the previous member's actor.
    return key(memberAvatarResolver, kind, memberId, enabled) {
        val actorFlow =
            remember {
                if (enabled) memberAvatarResolver.jointActor(kind, memberId) else flowOf(null)
            }
        val initial =
            remember {
                if (enabled) memberAvatarResolver.currentJointActor(kind, memberId) else null
            }
        actorFlow.collectAsState(initial).value
    }
}

@Composable
internal fun rememberRaftMemberAvatarWireValue(
    memberAvatarResolver: RaftMemberAvatarResolver,
    memberId: String?
): String? {
    // Keyed as a whole: `collectAsState` keeps its state across a new flow, so without the key a row
    // whose sender changes (it moved to the top with a new message) painted the previous sender's face
    // for a frame.
    return key(memberAvatarResolver, memberId) {
        val avatarFlow = remember { memberAvatarResolver(memberId) }
        val initialAvatarWireValue = remember { memberAvatarResolver.current(memberId) }
        avatarFlow.collectAsState(initialAvatarWireValue).value
    }
}

/**
 * #proj-mobile:#290: true while an agent's avatar is neither cached/resolved nor confirmed absent
 * by the server roster / joint-actor directory. Always false for humans and empty resolvers.
 */
@Composable
internal fun rememberRaftMemberAvatarPending(
    memberAvatarResolver: RaftMemberAvatarResolver,
    kind: MemberKind?,
    memberId: String?,
    jointSurface: Boolean = memberAvatarResolver.jointSurface,
): Boolean {
    if (kind != MemberKind.Agent) return false
    val cleanId = memberId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return key(memberAvatarResolver, cleanId, jointSurface) {
        val pendingFlow = remember { memberAvatarResolver.agentAvatarPendingFlow(cleanId, jointSurface) }
        val initialPending = remember { memberAvatarResolver.isAgentAvatarPending(cleanId, jointSurface) }
        pendingFlow.collectAsState(initialPending).value
    }
}
