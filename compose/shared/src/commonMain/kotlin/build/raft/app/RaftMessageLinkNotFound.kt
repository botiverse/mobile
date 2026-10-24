package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

internal const val RAFT_MESSAGE_LINK_NOT_FOUND_PAGE = "compose_message_link_not_found"

/** The empty state as its own page, pushed above whatever is showing (identity-free pageData). */
@Page(RAFT_MESSAGE_LINK_NOT_FOUND_PAGE, supportInLocal = true)
internal class ComposeRaftMessageLinkNotFoundPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftMessageLinkNotFoundPageContent() }
    }
}

@Composable
private fun RaftMessageLinkNotFoundPageContent() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager()
    RaftMessageLinkNotFoundScreen(
        onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
        topSafeArea = raftTopSafeAreaPadding(),
    )
}

internal fun Pager.openRaftMessageLinkNotFoundPage(storeScope: RaftShellStoreScope): Boolean =
    openRaftPage(
        pageName = RAFT_MESSAGE_LINK_NOT_FOUND_PAGE,
        pageData = JSONObject().put("pageName", RAFT_MESSAGE_LINK_NOT_FOUND_PAGE),
        storeScopeOverride = storeScope,
    )

/**
 * The one answer for a message link this account cannot open (frame d ③, #proj-mobile #25/#26):
 * not a member of the server, a private or deleted conversation, a deleted message, a bad id.
 * The copy never says which, so the screen does not reveal whether the message exists.
 *
 * A "Join to view" action for a public server is not offered: the app has no join-public-server
 * path yet.
 *
 * Design-system: [RaftPanelBackHeader] (raft-ui PanelHeader with Back), [RaftIconTile] (muted),
 * [RaftText] headline/callout roles, [RaftButton] default variant. Colours are theme tokens only.
 */
@Composable
internal fun RaftMessageLinkNotFoundScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topSafeArea: Dp = 0.dp,
) {
    RaftBackHandler(enabled = true, label = "message_link_not_found", blocksBackSwipe = false) { onBack() }
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(colors.layerCanvas)
                    // The surface owns every touch under it (no tap reaches the shell beneath).
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            RaftPanelBackHeader(
                title = stringResource(MR.strings.message_link_title),
                subtitle = null,
                onBack = onBack,
                topSafeArea = topSafeArea,
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 320.dp).padding(bottom = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    RaftIconTile(size = RaftIconTileSize.Lg) {
                        RaftText(
                            text = "?",
                            style = RaftTheme.typography.title.copy(fontWeight = FontWeight.Bold),
                            color = colors.foregroundMuted,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    RaftText(
                        text = stringResource(MR.strings.message_link_not_found_title),
                        style = RaftTheme.typography.headline,
                        color = colors.foreground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    RaftText(
                        text = stringResource(MR.strings.message_link_not_found_body),
                        style = RaftTheme.typography.callout,
                        color = colors.foregroundMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    RaftButton(
                        text = stringResource(MR.strings.common_back),
                        onClick = onBack,
                        size = RaftButtonSize.Lg,
                        modifier = Modifier.widthIn(min = 200.dp),
                    )
                }
            }
        }
    }
}
