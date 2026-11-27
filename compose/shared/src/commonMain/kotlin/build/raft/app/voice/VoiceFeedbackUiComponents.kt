package build.raft.app.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.VoiceFeedbackSubmitFailure
import build.raft.app.resources.*
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

// Shared UI primitives for voice dictation & agent voice call feedback (`#wg-call:#7`,
// parity with `botiverse/slock#17246`).

/** Compact selectable issue-tag chip used in post-dictation and post-call feedback forms. */
@Composable
internal fun VoiceFeedbackTagChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Chip)
    val fill =
        when {
            !enabled -> colors.fillMuted
            selected -> colors.foreground
            else -> colors.layerCard
        }
    val borderColor =
        when {
            !enabled -> colors.lineHairline
            selected -> colors.foreground
            else -> colors.lineStrong
        }
    val textColor =
        when {
            !enabled -> colors.foregroundDisabled
            selected -> colors.foregroundInverse
            else -> colors.foreground
        }
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(fill, shape)
                .border(RaftStroke.Hairline, borderColor, shape)
                .raftClickable(
                    onClick = onClick,
                    enabled = enabled,
                    role = Role.Button,
                    shape = shape,
                    selected = selected,
                )
                .semantics { toggleableState = ToggleableState(selected) }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(
            text = label,
            style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
            color = textColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Maps a typed [VoiceFeedbackSubmitFailure] from Raft's proxy routes to localized inline copy. */
@Composable
internal fun voiceFeedbackFailureMessage(
    failure: VoiceFeedbackSubmitFailure,
    fallback: String,
): String =
    when (failure) {
        is VoiceFeedbackSubmitFailure.PrivacyModeBlocked,
        is VoiceFeedbackSubmitFailure.Unavailable ->
            stringResource(Res.string.voice_feedback_error_unavailable)

        is VoiceFeedbackSubmitFailure.RateLimited -> {
            val retrySeconds = failure.retryAfterSeconds
            if (retrySeconds != null && retrySeconds > 0) {
                stringResource(Res.string.voice_feedback_error_rate_limited, retrySeconds)
            } else {
                stringResource(Res.string.voice_feedback_error_rate_limited_no_seconds)
            }
        }

        is VoiceFeedbackSubmitFailure.NotFound ->
            stringResource(Res.string.voice_feedback_error_not_found)

        is VoiceFeedbackSubmitFailure.InvalidRequest,
        is VoiceFeedbackSubmitFailure.Failed -> fallback
    }
