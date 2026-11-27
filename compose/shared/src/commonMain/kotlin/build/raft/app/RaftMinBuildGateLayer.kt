package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.data.RaftMinBuildGate
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.resources.*
import build.raft.app.thread.icon
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftIconTileVariant
import build.raft.designsystem.components.RaftStatusBarBackdropClaim
import build.raft.designsystem.components.rememberRaftStableWindowInsets
import kotlinx.coroutines.launch

internal const val RAFT_MIN_BUILD_GATE_BLOCK_TAG = "raft_min_build_gate_block"
internal const val RAFT_MIN_BUILD_GATE_BANNER_TAG = "raft_min_build_gate_banner"
internal const val RAFT_MIN_BUILD_GATE_BAND_TAG = "raft_min_build_gate_band"

/**
 * A page's minimum build gate layer ([build.raft.app.base.RaftPageLayer.MinBuildGate]): follows
 * [RaftMinBuildGate.decision]. A block covers the page with "Please update to the latest version"
 * (Update opens the store or Hands download; Send feedback and Sign out stay reachable); a banner
 * is a dismissible notice in a band above the page, which pushes the page down rather than lying
 * over its header (the server switcher, Back, the top bar stay where a tap reaches them). The
 * feedback form is exempt from the layer, so Send feedback opens a page that works.
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
        bannerTopInset = raftMinBuildGateBannerTopInset(),
        bannerHorizontalInsets = raftMinBuildGateBannerHorizontalInsets(),
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

/**
 * What the banner's band clears at the window's top for the page under it: what the page's head
 * (or the app shell's top bar) would have, the status bar or a host tab bar laid along the top edge.
 */
@Composable
internal fun raftMinBuildGateBannerTopInset(): Dp = maxOf(raftWindowTopSafeAreaPadding(), raftNativeTabBarEdgeInsets().top)

/** The window's side safe areas, which the banner keeps out of (physical edges). */
@Composable
internal fun raftMinBuildGateBannerHorizontalInsets(): WindowInsets {
    val safeArea = raftHorizontalSafeArea()
    return WindowInsets(left = safeArea.left.coerceAtLeast(0.dp), right = safeArea.right.coerceAtLeast(0.dp))
}

/**
 * [RaftMinBuildGateLayer] without its pager, so a test can compose it. [bannerTopInset] and
 * [bannerHorizontalInsets] are the window's top and side safe areas, which the banner's band
 * clears for the page underneath.
 */
@Composable
internal fun RaftMinBuildGateFrame(
    decision: RaftMinBuildGateDecision,
    dismissedBannerThreshold: Long?,
    signingOut: Boolean,
    onUpdate: (String) -> Unit,
    onSendFeedback: () -> Unit,
    onSignOut: () -> Unit,
    onDismissBanner: (Long) -> Unit,
    bannerTopInset: Dp = 0.dp,
    bannerHorizontalInsets: WindowInsets = WindowInsets(0.dp),
    content: @Composable () -> Unit,
) {
    val blocked = decision is RaftMinBuildGateDecision.Block
    val banner = (decision as? RaftMinBuildGateDecision.Banner)?.takeIf { dismissedBannerThreshold != it.threshold }
    val statusBarInsets = rememberRaftStableWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
    Box(modifier = Modifier.fillMaxSize()) {
        // The banner never lies over the page (#103: over Home it covered the server switcher): it
        // is a band above the page, and the page starts under it. The band clears the status bar,
        // so the page's head and the app shell's top bar do not pad it again. The page keeps its
        // place in the tree whether the band shows or not (nothing is remounted).
        Column(modifier = Modifier.fillMaxSize()) {
            if (banner != null) {
                RaftMinBuildGateBannerBand(
                    topInset = bannerTopInset,
                    horizontalInsets = bannerHorizontalInsets,
                    onUpdate = banner.updateUrl?.let { url -> { onUpdate(url) } },
                    onDismiss = { onDismissBanner(banner.threshold) },
                )
            }
            // Under a block the page stays composed (nothing is torn down) but is hidden from
            // accessibility services; the block above takes every touch.
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .then(if (banner != null) Modifier.consumeWindowInsets(statusBarInsets) else Modifier)
                        .then(if (blocked) Modifier.clearAndSetSemantics {} else Modifier)
            ) {
                CompositionLocalProvider(LocalRaftTopSafeAreaClearedAbovePage provides (banner != null), content = content)
            }
        }
        if (decision is RaftMinBuildGateDecision.Block) {
            RaftMinBuildGateBlockContent(
                signingOut = signingOut,
                onUpdate = { onUpdate(decision.updateUrl) },
                onSendFeedback = onSendFeedback,
                onSignOut = onSignOut,
            )
        }
    }
}

/**
 * The banner's band above the page: the canvas from the window's top edge (it claims the status
 * bar's backdrop, as a page head does), the banner centred in it, and the seam a page head keeps
 * under it.
 */
@Composable
internal fun RaftMinBuildGateBannerBand(
    topInset: Dp,
    horizontalInsets: WindowInsets,
    onUpdate: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftStatusBarBackdropClaim(RaftColor.Canvas, enabled = topInset > 0.dp)
    Column(modifier = modifier.fillMaxWidth().testTag(RAFT_MIN_BUILD_GATE_BAND_TAG).background(RaftColor.Canvas)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = topInset)
                    .windowInsetsPadding(rememberRaftStableWindowInsets(horizontalInsets.union(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))))
                    .padding(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Sm),
            contentAlignment = Alignment.TopCenter,
        ) {
            RaftMinBuildGateBanner(onUpdate = onUpdate, onDismiss = onDismiss)
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftAppStroke.Thin)
                    .background(RaftColor.BorderSeam.compositeOver(RaftColor.Canvas)),
        )
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
                .windowInsetsPadding(rememberRaftStableWindowInsets(WindowInsets.safeDrawing))
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

/** The dismissible notice when the channel did not confirm a build at or above the threshold (in [RaftMinBuildGateBannerBand]). */
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
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
        // raft-ui: a banner with a button closes through a ghost Dismiss before it; without one,
        // through the frameless × (RaftBanner shows that only when there is no action).
        action =
            if (onUpdate != null) {
                {
                    RaftButton(
                        text = stringResource(Res.string.common_dismiss),
                        onClick = onDismiss,
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Md,
                    )
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
        onDismiss = if (onUpdate == null) onDismiss else null,
        dismissLabel = stringResource(Res.string.common_dismiss),
    )
}
