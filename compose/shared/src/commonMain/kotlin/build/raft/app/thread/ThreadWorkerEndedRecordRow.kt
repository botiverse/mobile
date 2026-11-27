package build.raft.app.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftColor
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftRelativeTimestampText
import build.raft.app.RaftTimestampDisplayGroup
import build.raft.app.RaftType
import build.raft.app.fill
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.theme.RaftTheme

/**
 * Whether `worker_v1` is enabled for the active conversation's server.
 * Defaults to `false` so surfaces and tests that do not enable `worker_v1` remain untouched.
 */
internal val LocalThreadWorkerV1Enabled = compositionLocalOf { false }

/**
 * Worker v1 ended system record row (slock `WorkerEndedRecordRow.tsx`):
 * renders a `systemSubtype == "worker.ended"` message as a subtle one-line disclosure (timestamp +
 * ellipsized server-authored text + chevron) that expands inline to reveal the full verbatim plain
 * text in a monospace note surface (no markdown links, no @mention links, no timeline link).
 */
@Composable
internal fun ThreadWorkerEndedRecordRow(
    message: RaftThreadMessageItem,
    timestampValue: String,
    onExpand: (() -> Unit)? = null,
) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val textColor = RaftColor.TextBlack.copy(alpha = 0.5f)
    val timeColor = RaftColor.TextBlack.copy(alpha = 0.4f)
    val chevronColor = RaftColor.TextBlack.copy(alpha = 0.35f)
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val toggle = {
        expanded = !expanded
        if (expanded) onExpand?.invoke()
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftColor.SurfaceWhite)
                .testTag("worker-ended-record")
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = RaftAppSpacing.Md),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = ThreadMessageTimelineTurn, bottom = ThreadMessageTimelineRowInset),
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier =
                    Modifier
                        .testTag("worker-ended-record-toggle")
                        .raftClickable(
                            onClick = toggle,
                            shape = RoundedCornerShape(6.dp),
                            tier = RaftHoverTier.Subtle,
                        )
                        .padding(horizontal = RaftAppSpacing.Sm, vertical = ThreadMessageTimelineRowInset),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftRelativeTimestampText(
                    value = timestampValue,
                    displayGroup = RaftTimestampDisplayGroup.Conversation,
                    style = RaftType.Caption.copy(color = timeColor, fontFamily = RaftMonoFamily),
                    color = timeColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = message.body,
                    style = RaftType.Caption.copy(color = textColor),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                RaftGlyphIcon(
                    RaftGlyph.ChevronRight,
                    Modifier.rotate(if (expanded) -90f else 90f),
                    size = 12.dp,
                    color = chevronColor,
                )
            }
        }
        if (expanded) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = ThreadMessageTimelineRowInset)
                        .testTag("worker-ended-record-text")
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.fillSubtle)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = message.body,
                    style = typography.monoMeta,
                    color = colors.foregroundMuted,
                )
            }
        }
    }
}
