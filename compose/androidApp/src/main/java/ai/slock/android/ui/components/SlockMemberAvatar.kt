package ai.slock.android.ui.components

import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Presence
import ai.slock.android.ui.theme.SlockColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SlockMemberAvatar(
    member: MemberProfile,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    fontSize: TextUnit = 11.sp,
    fallbackColor: Color = member.kind.defaultAvatarColor,
    statusSize: Dp = (size * 0.28f).coerceAtLeast(9.dp),
    showAgentStatus: Boolean = true
) {
    SlockMemberAvatar(
        avatarUrl = member.avatarUrl,
        avatar = member.avatar,
        label = member.avatarLabel,
        kind = member.kind,
        presence = member.presence,
        agentStatus = member.agentStatus,
        modifier = modifier,
        size = size,
        fontSize = fontSize,
        fallbackColor = fallbackColor,
        statusSize = statusSize,
        showAgentStatus = showAgentStatus
    )
}

@Composable
fun SlockMemberAvatar(
    avatarUrl: String?,
    avatar: AvatarReference?,
    label: String,
    kind: MemberKind,
    presence: Presence,
    agentStatus: AgentStatus?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    fontSize: TextUnit = 11.sp,
    fallbackColor: Color = kind.defaultAvatarColor,
    statusSize: Dp = (size * 0.28f).coerceAtLeast(9.dp),
    showAgentStatus: Boolean = true
) {
    Box(modifier = modifier.size(size)) {
        SlockAvatar(
            avatarUrl = avatarUrl,
            avatar = avatar,
            label = label,
            modifier = Modifier.fillMaxSize(),
            size = size,
            fontSize = fontSize,
            fallbackColor = fallbackColor
        )
        if (showAgentStatus && kind == MemberKind.Agent) {
            SlockAgentStatusIndicator(
                status = agentStatus,
                presence = presence,
                modifier = Modifier.align(Alignment.BottomEnd),
                size = statusSize
            )
        }
    }
}

val MemberKind.defaultAvatarColor: Color
    get() = when (this) {
        MemberKind.Human -> SlockColor.AccentLavender
        MemberKind.Agent -> SlockColor.AccentPink
        MemberKind.System -> SlockColor.AccentCyan
    }
