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
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager

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
                    RaftForwardTargetPickerItem(
                        stableKey = "forward-scroll-probe-$index",
                        title = "Forward target ${index + 1}",
                        subtitle = "Recent DM",
                        visualKind = RaftForwardTargetVisualKind.DirectMessage,
                        payload = null
                    )
                }
            } else {
                listOf(
                    RaftForwardTargetPickerItem(
                        stableKey = "channel-design",
                        title = "design",
                        subtitle = "Product and UI decisions",
                        visualKind = RaftForwardTargetVisualKind.PublicChannel,
                        payload = null
                    ),
                    RaftForwardTargetPickerItem(
                        stableKey = "dm-artin",
                        title = "artin",
                        subtitle = "Direct message",
                        visualKind = RaftForwardTargetVisualKind.DirectMessage,
                        avatarWireValue = AvatarReference.Random("artin").wireValue,
                        avatarLabel = "A",
                        payload = null
                    ),
                    RaftForwardTargetPickerItem(
                        stableKey = "channel-android",
                        title = "android-artifacts",
                        subtitle = "Public channel",
                        visualKind = RaftForwardTargetVisualKind.PrivateChannel,
                        payload = null
                    ),
                    RaftForwardTargetPickerItem(
                        stableKey = "joint-release",
                        title = "release-joint",
                        subtitle = "Joint channel",
                        visualKind = RaftForwardTargetVisualKind.JointChannel,
                        payload = null
                    ),
                    RaftForwardTargetPickerItem(
                        stableKey = "member-cindy",
                        title = "Cindy",
                        subtitle = "Keeps visual testing release notes aligned",
                        visualKind = RaftForwardTargetVisualKind.DirectMessage,
                        payload = null
                    )
                )
            }
        }
    RaftForwardTargetPicker(
        messageCount = 2,
        skippedCount = 0,
        nestedForwardCount = 0,
        state =
            RaftForwardTargetPickerUiState(
                sessionKey = RaftForwardPickerSessionKey("visual", "server-visual", 1),
                items = if (selectedProbeTargetId == null) fixtureTargets else emptyList(),
                loadState = RaftForwardPickerLoadState.Loaded
            ),
        onIntent = { intent ->
            if (scrollProbe && intent is RaftForwardTargetPickerIntent.TargetSelected) {
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
