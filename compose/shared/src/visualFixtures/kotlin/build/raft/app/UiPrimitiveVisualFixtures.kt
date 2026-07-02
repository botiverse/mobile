package build.raft.app

import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.SlockAvatarListRow
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalBadge
import build.raft.app.components.SlockBrutalBadgeButton
import build.raft.app.components.SlockBrutalTone
import build.raft.app.components.SlockSectionHeader
import build.raft.app.components.SlockSurfaceListItem
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

@Composable
internal fun UiSegmentedControlVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SegmentedControl(
            selectedValue = "mentions",
            options =
                listOf(
                    SegmentedControlOption(value = "all", label = "All", count = 24),
                    SegmentedControlOption(value = "mentions", label = "Mentions", count = 3),
                    SegmentedControlOption(value = "unread", label = "Unread", count = 9)
                ),
            onValueSelected = {}
        )
    }
}

@Composable
internal fun UiButtonVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(150.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockBrutalActionButton(label = "Save", onClick = {}, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
                SlockBrutalActionButton(label = "Sync", onClick = {}, backgroundColor = SlockColor.BrandYellow, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
                SlockBrutalActionButton(label = "Delete", onClick = {}, backgroundColor = SlockColor.AccentPink, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockBrutalActionButton(label = "Add", onClick = {}, backgroundColor = SlockColor.AccentCyan, minHeight = 24.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp))
                SlockBrutalActionButton(label = "Continue", onClick = {}, backgroundColor = SlockColor.AccentLime, minHeight = 32.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 16.sp))
                SlockBrutalActionButton(label = "Disabled", onClick = {}, backgroundColor = SlockColor.SurfaceCream, enabled = false, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
            }
        }
    }
}

@Composable
internal fun UiBadgeVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(92.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockBrutalBadge("Shared", tone = SlockBrutalTone.White)
                SlockBrutalBadge("Installed", tone = SlockBrutalTone.Lime)
                SlockBrutalBadge("Update", tone = SlockBrutalTone.Orange)
                SlockBrutalBadge("Built In", tone = SlockBrutalTone.Black)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockBrutalBadge("task #273", tone = SlockBrutalTone.Red, uppercase = false)
                SlockBrutalBadgeButton("Install", tone = SlockBrutalTone.Pink, onClick = {})
            }
        }
    }
}

@Composable
internal fun UiSectionHeaderVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(88.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SlockSectionHeader(
                label = "Connected Apps",
                count = 3,
                modifier = Modifier.fillMaxWidth(),
                action = {
                    SlockBrutalActionButton(label = "Add", onClick = {}, minHeight = 24.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp))
                }
            )
            Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        }
    }
}

@Composable
internal fun UiSurfaceListItemVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(150.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockSurfaceListItem(interactive = false) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Private app", style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp), color = SlockColor.TextBlack, maxLines = 1)
                    Text("Available to this server", style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp), color = SlockColor.TextBlack.copy(alpha = 0.5f), maxLines = 1)
                }
            }
            SlockSurfaceListItem(selected = true, interactive = false) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Active integration", style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp), color = SlockColor.TextBlack, maxLines = 1)
                    Text("Selected list item state", style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp), color = SlockColor.TextBlack.copy(alpha = 0.5f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun UiAvatarListRowVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(168.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockAvatarListRow(
                avatarWireValue = "pixel:random:cindy",
                avatarLabel = "Cindy",
                name = "Cindy",
                subtitle = "Claude Code",
                rightContent = {
                    SlockBrutalBadge("Online", tone = SlockBrutalTone.Lime)
                }
            )
            SlockAvatarListRow(
                avatarWireValue = null,
                avatarLabel = "Product UX Designer",
                name = "Product UX Designer",
                subtitle = "product@slock.ai",
                selected = true,
                rightContent = {
                    SlockBrutalActionButton(label = "Open", onClick = {}, minHeight = 24.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp))
                }
            )
        }
    }
}
