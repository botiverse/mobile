package build.raft.app.members

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import build.raft.app.RaftAppAvatar
import build.raft.app.RaftAvatarRowDiagnostics
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.MemberKind
import build.raft.app.model.appAvatarWireValueOrNull
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.raftAvatarKind
import build.raft.app.raftMemberAvatarCacheSubject

/**
 * Who a face belongs to: enough to look the person up in their server's member directory.
 * [serverId] scopes the directory read and the image cache.
 */
@Immutable
internal data class RaftMemberAvatarIdentity(
    val kind: MemberKind?,
    val memberId: String?,
    val serverId: String?,
)

/**
 * The one resolution order for a known person's face:
 * 1. [resolvedAvatarWireValue], what [RaftMemberAvatarResolver] answers for the id: the member
 *    directory's avatar (a known agent with none already wears its default), else the avatar last
 *    saved for the id while the directory has none;
 * 2. [carriedAvatarWireValue], the avatar the row itself carries (a wire payload's), filtered as a
 *    profile's own avatar is (a person shows only an uploaded image; a retired `gravatar:` value is none);
 * 3. [jointActorAvatarWireValue], on a joint channel's surface ([LocalRaftJointActorSurface]): the
 *    other server's person or agent as `POST /api/actors/resolve` answered, for an id no roster here
 *    has (message rows already read it; every other face of that person now does too);
 * 4. the kind's own default: an agent's pixel robot. A person with none stays null ([RaftAppAvatar]
 *    draws the placeholder person).
 */
internal fun raftMemberAvatarWireValue(
    identity: RaftMemberAvatarIdentity,
    resolvedAvatarWireValue: String?,
    carriedAvatarWireValue: String? = null,
    jointActorAvatarWireValue: String? = null,
): String? {
    // A retired Gravatar value (`gravatar:<hash>`, from a cache or snapshot) is no avatar.
    resolvedAvatarWireValue?.appAvatarWireValueOrNull()?.let { return it }
    val carried =
        carriedAvatarWireValue?.appAvatarWireValueOrNull()
            ?: jointActorAvatarWireValue?.appAvatarWireValueOrNull()
    val kind = identity.kind
    if (kind != MemberKind.Human && kind != MemberKind.Agent) return carried
    return avatarWireValueForAvatarSlot(
        kind = kind,
        id = identity.memberId?.trim().orEmpty(),
        avatarWireValue = carried,
    )
}

/**
 * The faces below are on a joint channel's surface (its conversation, a thread or task in it): a
 * person or agent no roster of this server has may be the other server's, and [RaftMemberAvatar]
 * then reads them through `POST /api/actors/resolve`, as message rows do. False elsewhere, where
 * nothing is asked.
 */
internal val LocalRaftJointActorSurface = staticCompositionLocalOf { false }

/**
 * The member directory for [serverId]: the app's shared members store unless [serverMembersStore]
 * is given. Empty (nothing resolves; carried value and kind default still apply) without a store or
 * a server.
 */
@Composable
internal fun rememberRaftMemberAvatarResolver(
    serverId: String?,
    serverMembersStore: SharedServerMembersStore? = null,
): RaftMemberAvatarResolver {
    val store = serverMembersStore ?: remember { RaftSharedStoreRegistry.currentOrNull()?.serverMembersStore }
    return remember(store, serverId) { raftMemberAvatarResolver(store, serverId) }
}

/**
 * The one way to draw a known person's or agent's face: resolves [identity] through the member
 * directory ([raftMemberAvatarWireValue]: directory, then saved avatar, then [carriedAvatarWireValue],
 * then, on a joint surface, the other server's resolved actor, then the kind's default) and draws [RaftAppAvatar] of [identity]'s kind (its fallback, also when an
 * image fails: an agent's robot, a person's placeholder, never initials), keyed by member for the image cache. Pass
 * [resolver] where the page already holds one for [identity]'s server; otherwise the app's shared
 * members store is read. Non-person faces (server icons, app bots' own images) use [RaftAppAvatar].
 */
@Composable
internal fun RaftMemberAvatar(
    identity: RaftMemberAvatarIdentity,
    label: String,
    avatarSize: Dp,
    modifier: Modifier = Modifier,
    carriedAvatarWireValue: String? = null,
    resolver: RaftMemberAvatarResolver = rememberRaftMemberAvatarResolver(identity.serverId),
    borderWidth: Dp = Dp.Unspecified,
    diagnostics: RaftAvatarRowDiagnostics? = null,
) {
    val memberId = identity.memberId?.trim()?.takeIf { it.isNotEmpty() }
    val resolved = rememberRaftMemberAvatarWireValue(resolver, memberId)
    val jointSurface = resolver.jointSurface || LocalRaftJointActorSurface.current
    // Null while this server's roster knows them (they always win) or off a joint surface.
    val jointActor =
        rememberRaftJointActor(
            memberAvatarResolver = resolver,
            kind = identity.kind,
            memberId = memberId,
            enabled = jointSurface,
        )
    val explicitAvatar =
        resolved?.appAvatarWireValueOrNull()
            ?: carriedAvatarWireValue?.appAvatarWireValueOrNull()
            ?: jointActor?.renderedAvatarWireValue()
    val avatarPending =
        explicitAvatar == null &&
            rememberRaftMemberAvatarPending(
                memberAvatarResolver = resolver,
                kind = identity.kind,
                memberId = memberId,
                jointSurface = jointSurface,
            )
    RaftAppAvatar(
        kind = raftAvatarKind(identity.kind),
        avatarWireValue =
            if (avatarPending) {
                null
            } else {
                raftMemberAvatarWireValue(identity, resolved, carriedAvatarWireValue, jointActor?.explicitAvatarWireValue())
            },
        label = label,
        modifier = modifier,
        avatarSize = avatarSize,
        borderWidth = borderWidth,
        diagnostics = diagnostics,
        cacheSubject = raftMemberAvatarCacheSubject(identity.kind, identity.memberId),
        cacheServerId = identity.serverId?.trim()?.takeIf { it.isNotEmpty() },
        avatarPending = avatarPending,
    )
}
