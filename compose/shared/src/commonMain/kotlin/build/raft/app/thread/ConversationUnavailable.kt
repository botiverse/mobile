package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * A DM, channel or thread that cannot be opened: it does not exist, was deleted, or this account
 * cannot read it. The server answers 404 for all three, so the app says one sentence that tells
 * none of them apart (web slock #13147 `ChannelAccessLost unavailable`), with one button. No
 * "403" or "permission" wording, and no icon or separate back arrow.
 *
 * An unknown server slug or a non-member stays on the neutral message-link / public-preview
 * not-found screen ([RaftMessageLinkNotFoundScreen]); it is not this state.
 */
internal fun isConversationUnavailableErrorCode(errorCode: String?): Boolean =
    threadUserFacingErrorCodeReason(errorCode) == ThreadUserFacingErrorReason.ItemNotFound

/**
 * Where [RaftConversationUnavailableState]'s button goes and how it is named. [wide]: the
 * dual-pane window, whose button returns to the server's channel list; otherwise the phone's
 * conversation list (Home tab). [onBack] leaves the unavailable conversation for that place.
 */
internal data class ConversationUnavailableNav(val wide: Boolean, val onBack: () -> Unit)

/** Provided by a thread page that can leave an unavailable conversation; absent keeps the old retry state. */
internal val LocalConversationUnavailableNav = staticCompositionLocalOf<ConversationUnavailableNav?> { null }

/** One sentence and one button, centred; the empty-state pattern of [RaftMessageLinkNotFoundScreen] without its tile. */
@Composable
internal fun RaftConversationUnavailableState(
    wide: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftDesignSystemTheme {
        Column(
            modifier = modifier.fillMaxSize().padding(horizontal = 24.dp).testTag("conversation-unavailable"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RaftText(
                text = stringResource(Res.string.conversation_unavailable_title),
                style = RaftTheme.typography.headline,
                color = RaftTheme.colors.foreground,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            Spacer(Modifier.height(24.dp))
            RaftButton(
                text =
                    stringResource(
                        if (wide) Res.string.conversation_unavailable_back_channels
                        else Res.string.conversation_unavailable_back_conversations
                    ),
                onClick = onBack,
                size = RaftButtonSize.Lg,
                modifier = Modifier.widthIn(min = 200.dp).testTag("conversation-unavailable-back"),
            )
        }
    }
}

/** The state as a whole page (a permalink that resolved to 404), wide when the window is dual-pane wide. */
@Composable
internal fun RaftConversationUnavailableScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    RaftBackHandler(enabled = true, label = "conversation_unavailable", blocksBackSwipe = false) { onBack() }
    RaftDesignSystemTheme {
        BoxWithConstraints(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(RaftTheme.colors.layerCanvas)
                    // The surface owns every touch under it (no tap reaches the shell beneath).
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            RaftConversationUnavailableState(wide = maxWidth.value >= DualPaneMinimumWidthDp, onBack = onBack)
        }
    }
}
