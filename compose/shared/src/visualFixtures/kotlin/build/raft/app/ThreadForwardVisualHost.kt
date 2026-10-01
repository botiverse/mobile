package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager

// Stable visual fixtures for the two-step forward flow.
@Page("compose_thread_forward_modal_visual", supportInLocal = true)
internal class ComposeThreadForwardModalVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadForwardModalVisualScreen() }
    }
}

@Composable
internal fun ThreadForwardModalVisualScreen() {
    val params = (LocalActivity.current.getPager() as Pager).pageData.params
    val scrollProbe = params.optString("fixture") == ThreadForwardScrollProbeFixture
    var selectedProbeTargetId by remember(scrollProbe) { mutableStateOf<String?>(null) }
    val fixtureTargets =
        remember(scrollProbe) {
            if (scrollProbe) {
                List(ThreadForwardScrollProbeTargetCount) { index ->
                    SlockForwardTargetPickerItem(
                        stableKey = "forward-scroll-probe-$index",
                        title = "Forward target ${index + 1}",
                        subtitle = "Recent DM",
                        visualKind = SlockForwardTargetVisualKind.DirectMessage,
                        payload = null
                    )
                }
            } else {
                listOf(
                    SlockForwardTargetPickerItem(
                        stableKey = "channel-design",
                        title = "design",
                        subtitle = "Product and UI decisions",
                        visualKind = SlockForwardTargetVisualKind.PublicChannel,
                        payload = null
                    ),
                    SlockForwardTargetPickerItem(
                        stableKey = "dm-artin",
                        title = "artin",
                        subtitle = "Direct message",
                        visualKind = SlockForwardTargetVisualKind.DirectMessage,
                        avatarWireValue = AvatarReference.Random("artin").wireValue,
                        avatarLabel = "A",
                        payload = null
                    ),
                    SlockForwardTargetPickerItem(
                        stableKey = "channel-android",
                        title = "android-artifacts",
                        subtitle = "Public channel",
                        visualKind = SlockForwardTargetVisualKind.PrivateChannel,
                        payload = null
                    ),
                    SlockForwardTargetPickerItem(
                        stableKey = "joint-release",
                        title = "release-joint",
                        subtitle = "Joint channel",
                        visualKind = SlockForwardTargetVisualKind.JointChannel,
                        payload = null
                    ),
                    SlockForwardTargetPickerItem(
                        stableKey = "member-cindy",
                        title = "Cindy",
                        subtitle = "Keeps visual testing release notes aligned",
                        visualKind = SlockForwardTargetVisualKind.DirectMessage,
                        payload = null
                    )
                )
            }
        }
    SlockForwardTargetPicker(
        messageCount = 2,
        skippedCount = 0,
        nestedForwardCount = 0,
        taskLinkedCount = 0,
        state =
            SlockForwardTargetPickerUiState(
                sessionKey = SlockForwardPickerSessionKey("visual", "server-visual", 1),
                items = if (selectedProbeTargetId == null) fixtureTargets else emptyList(),
                loadState = SlockForwardPickerLoadState.Loaded
            ),
        onIntent = { intent ->
            if (scrollProbe && intent is SlockForwardTargetPickerIntent.TargetSelected) {
                selectedProbeTargetId = intent.item.stableKey
            }
        }
    )
}

private const val ThreadForwardScrollProbeFixture = "scroll_probe"
private const val ThreadForwardScrollProbeTargetCount = 24

@Page("compose_thread_forward_note_visual", supportInLocal = true)
internal class ComposeThreadForwardNoteVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadForwardNoteVisualScreen() }
    }
}

@Composable
internal fun ThreadForwardNoteVisualScreen() {
    val target =
        remember {
            ForwardMessageTarget(
                serverId = ServerId("server-visual"),
                kind = ForwardMessageTargetKind.RecentChannel,
                id = "channel-design",
                title = "#design",
                channelId = ChannelId("channel-design")
            )
        }
    var note by
        remember {
            val text = "Please review these together."
            mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
        }
    ThreadForwardNoteDialog(
        messageCount = 2,
        selectedTargetLabel = target.title,
        note = note,
        sending = false,
        onNoteChange = { note = it },
        onDismiss = {},
        onSend = {}
    )
}
