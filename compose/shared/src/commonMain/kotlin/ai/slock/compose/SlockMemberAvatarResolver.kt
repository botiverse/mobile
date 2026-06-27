package ai.slock.compose

import ai.slock.compose.data.SharedServerMembersStore
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.avatarWireValueForAvatarSlot
import ai.slock.compose.model.toGravatarAvatarUrl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal typealias SlockMemberAvatarResolver = (String?) -> Flow<String?>

internal val EmptySlockMemberAvatarResolver: SlockMemberAvatarResolver = { flowOf(null) }

internal fun slockMemberAvatarResolver(
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?
): SlockMemberAvatarResolver {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
    return { memberId ->
        if (serverMembersStore == null) {
            flowOf(null)
        } else {
            serverMembersStore
                .memberProfileFlow(cleanServerId, memberId)
                .map { profile -> profile?.avatarWireValueForMemberAvatarResolver() }
        }
    }
}

internal fun MemberProfile.avatarWireValueForMemberAvatarResolver(): String? {
    val explicitAvatarWireValue =
        avatar?.wireValue?.takeIf { it.isNotBlank() }
            ?: avatarUrl?.takeIf { it.isNotBlank() }
    if (explicitAvatarWireValue != null) {
        return explicitAvatarWireValue
    }
    if (kind == MemberKind.Agent) {
        return gravatarHash?.takeIf { it.isNotBlank() }?.toGravatarAvatarUrl()
            ?: avatarWireValueForAvatarSlot()
    }
    return avatarWireValueForAvatarSlot()
}

@Composable
internal fun rememberSlockMemberAvatarWireValue(
    memberAvatarResolver: SlockMemberAvatarResolver,
    memberId: String?
): String? {
    val avatarFlow = remember(memberAvatarResolver, memberId) { memberAvatarResolver(memberId) }
    val avatarWireValue by avatarFlow.collectAsState(null)
    return avatarWireValue
}
