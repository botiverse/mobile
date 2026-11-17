package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuLabel
import build.raft.designsystem.components.RaftMenuSurface

/** The public Raft documentation (web LeftRail HelpMenu "Raft Documentation"). */
internal const val RaftDocumentationUrl = "https://docs.raft.build"

/**
 * From the window's bottom to the rail's Help item's bottom edge: the rail's [bottomInset], the
 * footer's end inset, then Settings (the last footer item) and one item gap above it, in the
 * rail's size for this window (compact in a short one).
 */
@Composable
internal fun raftAppRailHelpItemBottom(bottomInset: Dp): Dp {
    val metrics = raftAppRailWindowMetrics()
    return bottomInset + metrics.footerBottom + metrics.itemSize + metrics.itemGap
}

/**
 * The rail's Help menu (web LeftRail `HelpMenu`, "Help & resources"): beside the rail, 8dp past
 * its seam, its bottom level with the Help item's (web `side="right" align="end"`). It holds the
 * web menu's rows that apply in the app: Raft Documentation (opens the docs in the browser) and
 * Feedback (the Settings Feedback page). The web's Mobile App row is for the web only, and Join
 * Community is already in the server panel. A tap outside, Back or a pick closes it.
 */
@Composable
internal fun RaftAppRailHelpOverlay(
    open: Boolean,
    onDismiss: () -> Unit,
    bottomInset: Dp,
    onDocumentation: () -> Unit,
    onFeedback: () -> Unit,
) {
    if (!open) return
    RaftBackHandler(enabled = true, label = "rail_help_menu", onBack = onDismiss)
    RaftFloatingOverlayHost(
        onDismiss = onDismiss,
        dismissActivationDelayMillis = RaftFloatingOverlayClickTriggeredDismissDelayMillis,
    ) { _ ->
        RaftDesignSystemTheme {
            Box(Modifier.fillMaxSize()) {
                RaftMenuSurface(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = RaftAppRailOuterWidth + RaftAppRailPopoverGap,
                                bottom = raftAppRailHelpItemBottom(bottomInset),
                            ).raftFloatingContentClickTarget(),
                    header = { RaftMenuLabel(stringResource(Res.string.rail_help_menu_title)) },
                ) {
                    RaftMenuItem(
                        text = stringResource(Res.string.rail_help_documentation),
                        onClick = onDocumentation,
                        leadingIcon = { RaftContentLucideIcon(RaftLucideIconName.BookOpenText, size = 14.dp) },
                    )
                    RaftMenuItem(
                        text = stringResource(Res.string.settings_feedback_title),
                        onClick = onFeedback,
                        leadingIcon = { RaftContentLucideIcon(RaftLucideIconName.MessageSquarePlus, size = 14.dp) },
                    )
                }
            }
        }
    }
}
