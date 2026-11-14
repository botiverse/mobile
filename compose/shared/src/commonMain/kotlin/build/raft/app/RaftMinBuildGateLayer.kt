package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.data.RaftMinBuildGate
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftIconTileVariant
import kotlinx.coroutines.launch

internal const val RAFT_MIN_BUILD_GATE_BLOCK_TAG = "raft_min_build_gate_block"
internal const val RAFT_MIN_BUILD_GATE_BANNER_TAG = "raft_min_build_gate_banner"

/**
 * A page's minimum build gate layer ([build.raft.app.base.RaftPageLayer.MinBuildGate]): follows
 * [RaftMinBuildGate.decision]. A block covers the page with "Please update to the latest version"
 * (Update opens the store or Hands download; Send feedback and Sign out stay reachable); a banner
 * is a dismissible notice over the top of the page. The feedback form is exempt from the layer, so
 * Send feedback opens a page that works.
 */
@Composable
internal fun RaftMinBuildGateLayer(content: @Composable () -> Unit) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val decision by RaftMinBuildGate.decision.collectAsState()
    val dismissed by RaftMinBuildGate.dismissedBanner.collectAsState()
    val scope = rememberCoroutineScope()
    var signingOut by remember { mutableStateOf(false) }
    val storeScope =
        remember(pager.pageData.params.toString(), pager.pagerId) {
            pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
        }
    RaftMinBuildGateFrame(
        decision = decision,
        dismissedBannerThreshold = dismissed,
        signingOut = signingOut,
        onUpdate = { url ->
            raftLogInfo("SlockMinBuildGate", "min_build_gate action=update")
            bridge.openUrl(url, inBrowser = true)
        },
        onSendFeedback = {
            raftLogInfo("SlockMinBuildGate", "min_build_gate action=feedback")
            pager.openSettingsHandsFeedbackPage(storeScope)
        },
        onSignOut = {
            if (!signingOut) {
                signingOut = true
                raftLogInfo("SlockMinBuildGate", "min_build_gate action=sign_out")
                scope.launch {
                    try {
                        val authDataSource = RaftSharedStoreRegistry.get(bridge).createAuthDataSourceForCurrentEnvironment(storeScope)
                        bridge.realtimeDisconnect("logout")
                        pager.performStandaloneSettingsLogout(authDataSource)
                    } finally {
                        signingOut = false
                    }
                }
            }
        },
        onDismissBanner = { threshold ->
            raftLogInfo("SlockMinBuildGate", "min_build_gate action=dismiss_banner threshold=$threshold")
            RaftMinBuildGate.dismissBanner(threshold)
        },
        content = content,
    )
}

/** [RaftMinBuildGateLayer] without its pager, so a test can compose it. */
@Composable
internal fun RaftMinBuildGateFrame(
    decision: RaftMinBuildGateDecision,
    dismissedBannerThreshold: Long?,
    signingOut: Boolean,
    onUpdate: (String) -> Unit,
    onSendFeedback: () -> Unit,
    onSignOut: () -> Unit,
    onDismissBanner: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    val blocked = decision is RaftMinBuildGateDecision.Block
    Box(modifier = Modifier.fillMaxSize()) {
        // Under a block the page stays composed (nothing is torn down) but is hidden from
        // accessibility services; the block above takes every touch.
        Box(modifier = Modifier.fillMaxSize().then(if (blocked) Modifier.clearAndSetSemantics {} else Modifier)) {
            content()
        }
        when (decision) {
            is RaftMinBuildGateDecision.Block ->
                RaftMinBuildGateBlockContent(
                    signingOut = signingOut,
                    onUpdate = { onUpdate(decision.updateUrl) },
                    onSendFeedback = onSendFeedback,
                    onSignOut = onSignOut,
                )
            is RaftMinBuildGateDecision.Banner ->
                if (dismissedBannerThreshold != decision.threshold) {
                    RaftMinBuildGateBanner(
                        modifier = Modifier.align(Alignment.TopCenter),
                        onUpdate = decision.updateUrl?.let { url -> { onUpdate(url) } },
                        onDismiss = { onDismissBanner(decision.threshold) },
                    )
                }
            RaftMinBuildGateDecision.None -> Unit
        }
    }
}

/** The full-screen block: "Please update to the latest version", Update, Send feedback, Sign out. */
@Composable
internal fun RaftMinBuildGateBlockContent(
    signingOut: Boolean,
    onUpdate: () -> Unit,
    onSendFeedback: () -> Unit,
    onSignOut: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(RAFT_MIN_BUILD_GATE_BLOCK_TAG)
                // Every touch stops here: nothing reaches the page underneath.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                }
                .background(RaftColor.SurfaceCream)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(RaftAppSpacing.Xl),
        contentAlignment = Alignment.Center,
    ) {
        RaftCard(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
                ) {
                    RaftIconTile(variant = RaftIconTileVariant.Warning, size = RaftIconTileSize.Lg) {
                        RaftLucideIcon(
                            name = RaftLucideIconName.CircleArrowUp,
                            modifier = Modifier.size(RaftIconTileSize.Lg.icon),
                            color = raftArgbHex(RaftColor.TextBlack),
                        )
                    }
                    Text(
                        text = stringResource(Res.string.min_build_gate_title),
                        modifier = Modifier.weight(1f),
                        style = RaftType.PageTitle,
                        color = RaftColor.TextBlack,
                    )
                }
                Text(
                    text = stringResource(Res.string.min_build_gate_body),
                    style = RaftType.Body,
                    color = RaftColor.TextMutedGray,
                )
                RaftButton(
                    text = stringResource(Res.string.min_build_gate_action_update),
                    onClick = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                )
                RaftButton(
                    text = stringResource(Res.string.min_build_gate_action_feedback),
                    onClick = onSendFeedback,
                    modifier = Modifier.fillMaxWidth(),
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg,
                )
                RaftButton(
                    text = stringResource(Res.string.min_build_gate_action_sign_out),
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    enabled = !signingOut,
                    loading = signingOut,
                )
            }
        }
    }
}

/** The dismissible notice when the channel did not confirm a build at or above the threshold. */
@Composable
internal fun RaftMinBuildGateBanner(
    onUpdate: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftBanner(
        description = stringResource(Res.string.min_build_gate_banner),
        status = RaftBannerStatus.Warning,
        modifier =
            modifier
                .testTag(RAFT_MIN_BUILD_GATE_BANNER_TAG)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Sm)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
        action =
            if (onUpdate != null) {
                {
                    RaftButton(
                        text = stringResource(Res.string.min_build_gate_action_update),
                        onClick = onUpdate,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                    )
                }
            } else {
                null
            },
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.common_dismiss),
    )
}
