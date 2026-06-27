package ai.slock.compose

import ai.slock.compose.data.SharedServerMembersStore
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.avatarWireValueForRenderedAvatar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal typealias SlockMemberAvatarResolver = (String?) -> Flow<String?>

internal val EmptySlockMemberAvatarResolver: SlockMemberAvatarResolver = { flowOf(null) }

internal fun slockMemberAvatarResolver(
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?,
    diagnosticsLogger: (String) -> Unit = {}
): SlockMemberAvatarResolver {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
    return { memberId ->
        if (serverMembersStore == null) {
            diagnosticsLogger(
                "result=missing_store serverId=${cleanServerId?.value ?: "none"} " +
                    "memberId=${memberId.slockAvatarDiagnosticsId()}"
            )
            flowOf(null)
        } else {
            serverMembersStore
                .memberProfileFlow(cleanServerId, memberId)
                .map { profile ->
                    val avatarWireValue = profile?.avatarWireValueForRenderedAvatar()
                    diagnosticsLogger(
                        "result=profile serverId=${cleanServerId?.value ?: "current"} " +
                            "memberId=${memberId.slockAvatarDiagnosticsId()} " +
                            "profile=${profile != null} " +
                            "kind=${profile?.kind?.name ?: "none"} " +
                            "avatarSource=${avatarWireValue.slockAvatarDiagnosticsSource()}"
                    )
                    avatarWireValue
                }
                .distinctUntilChanged()
        }
    }
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

internal fun String?.slockAvatarDiagnosticsId(): String =
    this
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(64)
        ?: "none"

internal fun String?.slockAvatarDiagnosticsSource(): String {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return "none"
    return when {
        value.startsWith("http://") || value.startsWith("https://") -> "url"
        value.startsWith("pixel:") -> "pixel"
        value.startsWith("random:") -> "random"
        value.length in 32..128 && value.all { it.isLetterOrDigit() } -> "hash"
        else -> "wire"
    }
}
