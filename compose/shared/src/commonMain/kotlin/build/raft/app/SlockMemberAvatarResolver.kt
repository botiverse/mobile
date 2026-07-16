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

internal class SlockMemberAvatarResolver(
    private val currentAvatar: (String?) -> String?,
    private val avatarFlow: (String?) -> Flow<String?>
) {
    fun current(memberId: String?): String? = currentAvatar(memberId)

    operator fun invoke(memberId: String?): Flow<String?> = avatarFlow(memberId)
}

internal val EmptySlockMemberAvatarResolver =
    SlockMemberAvatarResolver(
        currentAvatar = { null },
        avatarFlow = { flowOf(null) }
    )

internal fun slockMemberAvatarResolver(
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?,
    diagnosticsLogger: (String) -> Unit = {}
): SlockMemberAvatarResolver {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
    return SlockMemberAvatarResolver(
        currentAvatar = { memberId ->
            serverMembersStore
                ?.state
                ?.value
                ?.currentMemberAvatarWireValue(cleanServerId, memberId)
        },
        avatarFlow = { memberId ->
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
    )
}

internal fun SharedServerMembersState.currentMemberAvatarWireValue(
    serverId: ServerId?,
    memberId: String?
): String? = memberProfile(serverId, memberId)?.avatarWireValueForRenderedAvatar()

@Composable
internal fun rememberSlockMemberAvatarWireValue(
    memberAvatarResolver: SlockMemberAvatarResolver,
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
