package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

enum class SlockMemberKind {
    Agent,
    Human
}

enum class SlockPresence {
    Online,
    Offline,
    Unknown
}

@Composable
fun SlockMemberRow(
    name: String,
    role: String,
    kind: SlockMemberKind,
    presence: SlockPresence,
    modifier: Modifier = Modifier,
    avatarLabel: String = name.take(1).uppercase(),
    detail: String? = null,
    device: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .padding(SlockSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MemberAvatar(label = avatarLabel, color = kind.avatarColor)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Text(
                    text = name,
                    style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                PresenceBadge(presence = presence)
            }
            Text(
                text = "${kind.displayLabel} · $role",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = SlockType.Caption,
                    color = SlockColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (device != null) {
                Text(
                    text = device,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MemberAvatar(
    label: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(color)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = SlockType.Button,
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun PresenceBadge(presence: SlockPresence) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(presence.color, CircleShape)
                .border(SlockStroke.Thin, SlockColor.BorderBlack, CircleShape)
        )
        Text(
            text = presence.displayLabel,
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
    }
}

private val SlockMemberKind.displayLabel: String
    get() = when (this) {
        SlockMemberKind.Agent -> "Agent"
        SlockMemberKind.Human -> "Human"
    }

private val SlockMemberKind.avatarColor: Color
    get() = when (this) {
        SlockMemberKind.Agent -> SlockColor.AccentLavender
        SlockMemberKind.Human -> SlockColor.AccentCyan
    }

private val SlockPresence.displayLabel: String
    get() = when (this) {
        SlockPresence.Online -> "Online"
        SlockPresence.Offline -> "Offline"
        SlockPresence.Unknown -> "Unknown"
    }

private val SlockPresence.color: Color
    get() = when (this) {
        SlockPresence.Online -> SlockColor.AccentLime
        SlockPresence.Offline -> SlockColor.EmptyStateGray
        SlockPresence.Unknown -> SlockColor.BrandYellow
    }
