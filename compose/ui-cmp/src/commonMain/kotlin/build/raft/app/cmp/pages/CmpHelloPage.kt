package build.raft.app.cmp.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.LocalRaftPageArgs
import build.raft.app.toRaftJsonString
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftSize
import build.raft.designsystem.tokens.RaftSpacing
import build.raft.designsystem.tokens.RaftStroke

/**
 * Debug-only end-to-end check of a CMP host (`compose_cmp_hello`, see RaftCmpPages.debugOnly):
 * Raft theme and fonts, the route args, a HostCapabilities call (toast) and host navigation
 * (close). Not a product page; its copy is intentionally not localized.
 */
@Composable
internal fun CmpHelloPage() {
    val host = LocalRaftHost.current
    val args = LocalRaftPageArgs.current
    val colors = RaftTheme.colors
    val type = RaftTheme.typography
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RaftSpacing.Md, vertical = RaftSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(RaftSpacing.Md),
    ) {
        RaftText("Compose Multiplatform", style = type.title)
        RaftText(
            "This page renders on the CMP host while every other page stays on Kuikly.",
            style = type.body,
            color = colors.foregroundMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(RaftSpacing.Sm)) {
            Swatch(colors.brand)
            Swatch(colors.ink)
            Swatch(colors.infoSoft)
            Swatch(colors.successSoft)
            Swatch(colors.dangerSoft)
        }
        Column(verticalArrangement = Arrangement.spacedBy(RaftSpacing.Xs)) {
            RaftText("pageName", style = type.footnote.copy(fontWeight = FontWeight.Bold), color = colors.foregroundMuted)
            RaftText(args.pageName, style = type.monoCode)
            RaftText("pageData", style = type.footnote.copy(fontWeight = FontWeight.Bold), color = colors.foregroundMuted)
            RaftText(args.pageData.toRaftJsonString(), style = type.monoCode)
        }
        HelloButton("Show toast", primary = true) {
            host.capabilities.toast("Hello from Compose Multiplatform")
        }
        HelloButton("Close") { host.navigator.closePage() }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        Modifier
            .size(28.dp)
            .background(color, RoundedCornerShape(RaftRadius.Sticker))
            .border(RaftStroke.Hairline, RaftTheme.colors.lineSeam, RoundedCornerShape(RaftRadius.Sticker)),
    )
}

/** Minimal tap target; the real RaftButton arrives with the components PRs. */
@Composable
private fun HelloButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RaftSize.MinTouchTarget)
            .background(if (primary) colors.brand else colors.layerCard, shape)
            .border(RaftStroke.Control, colors.ink, shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(label, style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold), color = colors.ink)
    }
}
