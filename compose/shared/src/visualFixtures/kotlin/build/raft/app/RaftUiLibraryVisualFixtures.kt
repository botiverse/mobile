package build.raft.app

import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockRaftUiAvatar
import build.raft.app.components.SlockRaftUiAvatarGroup
import build.raft.app.components.SlockRaftUiAvatarType
import build.raft.app.components.SlockRaftUiBanner
import build.raft.app.components.SlockRaftUiBannerSize
import build.raft.app.components.SlockRaftUiBannerStatus
import build.raft.app.components.SlockRaftUiEmptyState
import build.raft.app.components.SlockRaftUiField
import build.raft.app.components.SlockRaftUiInlineCode
import build.raft.app.components.SlockRaftUiInputGroup
import build.raft.app.components.SlockRaftUiKbd
import build.raft.app.components.SlockRaftUiTextHeading
import build.raft.app.components.SlockRaftUiTextMono
import build.raft.app.components.SlockRaftUiTextSans
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

@Composable
internal fun RaftUiBannerVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(176.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockRaftUiBanner(
                status = SlockRaftUiBannerStatus.Info,
                title = "Release gate ready",
                description = "Review the latest visual library diff before publishing.",
                iconName = SlockLucideIconName.Info,
                action = {
                    SlockBrutalActionButton(label = "Open", onClick = {}, minHeight = 24.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp))
                }
            )
            SlockRaftUiBanner(
                status = SlockRaftUiBannerStatus.Warning,
                title = "Android capture stale",
                description = "Cloud phone artifact is older than React.",
                size = SlockRaftUiBannerSize.Small,
                iconName = SlockLucideIconName.TriangleAlert
            )
            SlockRaftUiBanner(
                status = SlockRaftUiBannerStatus.Destructive,
                description = "Provider failed to render one component.",
                size = SlockRaftUiBannerSize.Small
            )
        }
    }
}

@Composable
internal fun RaftUiEmptyStateVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(248.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.30f))
        ) {
            SlockRaftUiEmptyState(
                title = "No visual changes",
                description = "Library fixtures are ready, but no reviewer has opened this run yet.",
                action = {
                    SlockBrutalActionButton(label = "Create fixture", onClick = {}, backgroundColor = SlockColor.BrandYellow, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
                }
            )
        }
    }
}

@Composable
internal fun RaftUiTypographyVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(178.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockRaftUiTextHeading("Raft UI primitives")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockRaftUiTextSans("Compare")
                SlockRaftUiInlineCode("library.raft-ui")
                SlockRaftUiTextSans("cases")
            }
            SlockRaftUiTextMono("RGB 95.43% · pixel 38.07%")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockRaftUiKbd("⌘")
                SlockRaftUiKbd("K")
                SlockRaftUiKbd("Enter")
            }
        }
    }
}

@Composable
internal fun RaftUiFieldInputVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(236.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockRaftUiField(
                label = "Workspace",
                required = true,
                description = "Lowercase server slug used in routes."
            ) {
                SlockRaftUiInputGroup(value = "visual-testing")
            }
            SlockRaftUiField(
                label = "Invite URL",
                error = "Invite URL is required."
            ) {
                SlockRaftUiInputGroup(
                    value = "slock.ai/join/",
                    leadingText = "https://",
                    trailingText = "copy",
                    invalid = true
                )
            }
        }
    }
}

@Composable
internal fun RaftUiAvatarVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(116.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SlockRaftUiAvatar(label = "AD", type = SlockRaftUiAvatarType.Agent, size = 64.dp, badge = true)
            SlockRaftUiAvatar(label = "AR", type = SlockRaftUiAvatarType.Human, size = 48.dp)
            SlockRaftUiAvatarGroup {
                SlockRaftUiAvatar(label = "C", type = SlockRaftUiAvatarType.Agent, size = 32.dp)
                SlockRaftUiAvatar(label = "P", type = SlockRaftUiAvatarType.Human, size = 32.dp)
                SlockRaftUiAvatar(label = "K", type = SlockRaftUiAvatarType.Agent, size = 32.dp)
                Box(
                    modifier =
                        Modifier
                            .width(32.dp)
                            .height(32.dp)
                            .background(SlockColor.SurfaceCream)
                            .border(SlockStroke.Default, SlockColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+4", style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp), color = SlockColor.TextBlack)
                }
            }
        }
    }
}
