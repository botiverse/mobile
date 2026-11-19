package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.data.PROFILE_DESCRIPTION_MAX_LENGTH
import build.raft.designsystem.components.RaftFieldDescription
import build.raft.designsystem.components.RaftFieldError
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

internal const val RaftProfileDescriptionCounterTestTag = "profile-description-counter"

/**
 * The message row under a profile description textarea (web `StableField` message row, botiverse/slock
 * #15664): the [error] (danger, semibold) or else the [hint] (hint ink) on the left, and the
 * persistent "[count]/300" counter on the right in hint mono. The counter stays in hint ink even
 * over the limit: one saved under the older limit reads e.g. 412/300 with no error until edited.
 */
@Composable
internal fun RaftProfileDescriptionFoot(
    count: Int,
    hint: String?,
    error: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val messageModifier = Modifier.weight(1f)
        when {
            error != null -> RaftFieldError(error, messageModifier, enabled = enabled)
            hint != null -> RaftFieldDescription(hint, messageModifier, enabled = enabled)
            else -> Spacer(messageModifier)
        }
        RaftText(
            text = "$count/$PROFILE_DESCRIPTION_MAX_LENGTH",
            modifier = Modifier.testTag(RaftProfileDescriptionCounterTestTag),
            style = RaftTheme.typography.monoMeta.copy(fontFeatureSettings = "tnum"),
            color = RaftTheme.colors.foregroundHint,
            maxLines = 1,
        )
    }
}
