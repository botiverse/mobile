package build.raft.app

import build.raft.app.data.ActorResolveRef
import build.raft.app.data.ResolvedActor
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedServerMembersState
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForRenderedAvatar
import build.raft.app.model.resolveAvatarSlot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
) {
    fun current(memberId: String?): String? = currentAvatar(memberId)

    operator fun invoke(memberId: String?): Flow<String?> = avatarFlow(memberId)

    /** This resolver for rows whose surface is ([joint]) or is not a joint channel. */
    fun forJointSurface(joint: Boolean): RaftMemberAvatarResolver =
        if (joint == jointSurface) {
            this
        } else {
            RaftMemberAvatarResolver(currentAvatar, avatarFlow, currentJointActorRead, jointActorRead, joint)
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

/** The resolved actor's own avatar (uploaded image, or a same-server person's Gravatar), else null. */
internal fun ResolvedActor.explicitAvatarWireValue(): String? =
    resolveAvatarSlot(
        kind = kind,
        id = id,
        avatarWireValue = null,
        avatarUrl = avatarUrl,
        gravatarHash = gravatarHash,
    ).displayAvatarWireValue

/** What a message row paints for the resolved actor: its own avatar, or an agent's default face. */
internal fun ResolvedActor.renderedAvatarWireValue(): String? =
    avatarWireValueForRenderedAvatar(
        kind = kind,
        id = id,
        avatarWireValue = null,
        avatarUrl = avatarUrl,
        gravatarHash = gravatarHash,
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
                ?.takeIf { serverMembersStore.state.value.memberProfile(cleanServerId, memberId) == null }
                ?.current(cleanServerId, memberId, kind)
        },
        jointActorRead = { kind, memberId ->
            if (jointActors == null) {
                flowOf(null)
            } else {
                // A sender this server knows keeps its own face (or its absence); the actor row is
                // read only while no roster has them.
                serverMembersStore
                    .memberProfileFlow(cleanServerId, memberId)
                    .map { profile -> profile != null }
                    .distinctUntilChanged()
                    .flatMapLatest { knownLocally ->
                        if (knownLocally) flowOf(null) else jointActors.actorFlow(cleanServerId, ActorResolveRef(kind, memberId))
                    }
                    .distinctUntilChanged()
            }
        },
        currentAvatar = { memberId ->
            serverMembersStore?.let { store ->
                store.state.value.currentMemberAvatarWireValue(cleanServerId, memberId)
                    ?: store.durableMemberAvatarWireValue(cleanServerId, memberId)
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
                serverMembersStore
                    .memberProfileFlow(cleanServerId, memberId)
                    .map { profile ->
                        // Cold start: until the directory (or a profile carrying an avatar) lands,
                        // render the last authoritative avatar persisted for this member.
                        val liveAvatarWireValue = profile?.avatarWireValueForRenderedAvatar()
                        val avatarWireValue =
                            liveAvatarWireValue
                                ?: serverMembersStore.durableMemberAvatarWireValue(cleanServerId, memberId)
                        diagnosticsLogger(
                            "result=profile serverHash=${cleanServerId?.value.raftAvatarDiagnosticsHash()} " +
                                "memberHash=${memberId.raftAvatarDiagnosticsHash()} " +
                                "profile=${profile != null} " +
                                "kind=${profile?.kind?.name ?: "none"} " +
                                "avatarSource=${avatarWireValue.raftAvatarDiagnosticsSource()} " +
                                "durable=${liveAvatarWireValue == null && avatarWireValue != null}"
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
    val actorFlow =
        remember(memberAvatarResolver, kind, memberId, enabled) {
            if (enabled) memberAvatarResolver.jointActor(kind, memberId) else flowOf(null)
        }
    val initial =
        remember(memberAvatarResolver, kind, memberId, enabled) {
            if (enabled) memberAvatarResolver.currentJointActor(kind, memberId) else null
        }
    val actor by actorFlow.collectAsState(initial)
    return actor
}

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
