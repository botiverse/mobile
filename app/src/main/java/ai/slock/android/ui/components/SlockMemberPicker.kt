package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Presence
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType

data class SlockMemberPickerItem(
    val id: String,
    val name: String,
    val handle: String,
    val role: String,
    val avatarLabel: String,
    val avatarUrl: String? = null,
    val avatar: AvatarReference? = AvatarReference.fromWireValue(avatarUrl),
    val kind: MemberKind,
    val presence: Presence,
    val agentStatus: AgentStatus? = null
)

fun MemberProfile.toSlockMemberPickerItem(
    role: String = roleDescription ?: when (kind) {
        MemberKind.Agent -> "Agent"
        MemberKind.Human -> "Member"
        MemberKind.System -> "System"
    }
): SlockMemberPickerItem =
    SlockMemberPickerItem(
        id = id.value,
        name = displayName,
        handle = handle,
        role = role,
        avatarLabel = avatarLabel,
        avatarUrl = avatarUrl,
        avatar = avatar,
        kind = kind,
        presence = presence,
        agentStatus = agentStatus
    )

@Composable
fun SlockMemberPickerSection(
    label: String,
    members: List<SlockMemberPickerItem>,
    selectedIds: Set<String>,
    disabledIds: Set<String> = emptySet(),
    enabled: Boolean,
    modifier: Modifier = Modifier,
    emptyText: String = "None",
    selectedText: String = "Selected",
    disabledText: String = "Joined",
    availableText: String = "Add",
    onMemberSelected: (SlockMemberPickerItem) -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = label,
            style = SlockType.SectionLabel,
            color = SlockColor.TextMutedGray
        )
        if (members.isEmpty()) {
            Text(
                text = emptyText,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                modifier = Modifier.padding(vertical = SlockSpacing.Xs)
            )
        } else {
            members.forEach { member ->
                val disabled = member.id in disabledIds
                val selected = member.id in selectedIds
                SlockMemberPickerRow(
                    member = member,
                    selected = selected,
                    disabled = disabled,
                    enabled = enabled && !disabled,
                    selectedText = selectedText,
                    disabledText = disabledText,
                    availableText = availableText,
                    onClick = { onMemberSelected(member) }
                )
            }
        }
    }
}

@Composable
fun SlockMemberPickerMessage(text: String) {
    Text(
        text = text,
        style = SlockType.Caption,
        color = SlockColor.TextMutedGray,
        modifier = Modifier.padding(SlockSpacing.Sm)
    )
}

@Composable
private fun SlockMemberPickerRow(
    member: SlockMemberPickerItem,
    selected: Boolean,
    disabled: Boolean,
    enabled: Boolean,
    selectedText: String,
    disabledText: String,
    availableText: String,
    onClick: () -> Unit
) {
    val rowColor = when {
        disabled -> SlockColor.SurfaceCream
        selected -> SlockColor.AccentLime
        else -> SlockColor.SurfaceWhite
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(modifier = Modifier.size(34.dp)) {
            SlockAvatar(
                avatarUrl = member.avatarUrl,
                avatar = member.avatar,
                label = member.avatarLabel,
                modifier = Modifier.fillMaxSize(),
                size = 34.dp,
                fontSize = SlockType.Caption.fontSize,
                fallbackColor = member.kind.avatarColor
            )
            if (member.kind != MemberKind.Human) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(9.dp)
                        .background(member.agentStatus.agentStatusColor(member.presence))
                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.name,
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = if (disabled) SlockColor.TextMutedGray else SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (member.kind == MemberKind.Human) member.role else "${member.role} · ${member.agentStatus.agentStatusLabel(member.presence)}",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = when {
                disabled -> disabledText
                selected -> selectedText
                else -> availableText
            },
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack
        )
    }
}

private val MemberKind.avatarColor: Color
    get() = when (this) {
        MemberKind.Human -> SlockColor.AccentLavender
        MemberKind.Agent -> SlockColor.AccentPink
        MemberKind.System -> SlockColor.AccentCyan
    }
