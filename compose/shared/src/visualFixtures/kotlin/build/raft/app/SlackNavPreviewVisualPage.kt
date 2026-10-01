package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.BasePager
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page

/**
 * task #94 Slack-aligned bottom navigation preview (gated by mobile_slack_nav_v0).
 * Renders the fixed four-tab set — Home (channels only) / DMs / Activity / More — on the
 * floating dock host, plus Slack's floating circular Search action. Local visual probe for
 * product sign-off; the wired implementation lands in the #94 follow-up increments.
 */
@Page("compose_slack_nav_preview_visual", supportInLocal = true)
internal class ComposeSlackNavPreviewVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlackNavPreviewContent() }
    }
}

@Composable
private fun SlackNavPreviewContent() {
    var selectedKey by remember { mutableStateOf("home") }
    val items =
        listOf(
            SlockBottomTabItemPayload("home", "Home", SlockLucideIconName.House, selected = selectedKey == "home"),
            SlockBottomTabItemPayload("dms", "DMs", SlockLucideIconName.MessageCircle, selected = selectedKey == "dms"),
            SlockBottomTabItemPayload("activity", "Activity", SlockLucideIconName.Bell, selected = selectedKey == "activity"),
            SlockBottomTabItemPayload("more", "More", SlockLucideIconName.Ellipsis, selected = selectedKey == "more")
        )
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Slack-aligned nav (task #94)",
                style = SlockType.PageTitle.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Home / DMs / Activity / More + floating Search — gated, order configurable",
                style = SlockType.Input.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "Edge bar",
                style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SlockBottomTabBar(items = items, onSelect = { selectedKey = it }, modifier = Modifier.fillMaxWidth())
        }
        // Slack-style floating circular Search action, above the dock at bottom end.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 96.dp)
                .size(52.dp)
                .background(SlockColor.SurfaceWhite, RoundedCornerShape(26.dp))
                .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(26.dp))
        ) {
            SlockLucideIcon(
                name = SlockLucideIconName.Search,
                modifier = Modifier.align(Alignment.Center).size(24.dp),
                color = SlockColor.TextBlackArgb
            )
        }
        SlockAppBottomTabBarHost(
            items = items,
            onSelect = { selectedKey = it },
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            bottomSafeArea = 0.dp
        )
    }
}
