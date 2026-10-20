package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
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
                // web ThreadForwardModalVisualCaseView seeds two channels and two DMs with no
                // activity, so getForwardTargets orders them by label: @artin, @Cindy,
                // android-artifacts, design. DM peers have no avatar (human placeholder).
                listOf(
                    RaftForwardTargetPickerItem(
                        stableKey = "dm-artin",
                        title = "artin",
                        subtitle = "Direct message",
                        visualKind = RaftForwardTargetVisualKind.DirectMessage,
                        payload = null
                    ),
                    RaftForwardTargetPickerItem(
                        stableKey = "dm-cindy",
                        title = "Cindy",
                        subtitle = "Direct message",
                        visualKind = RaftForwardTargetVisualKind.DirectMessage,
                        payload = null
                    ),
                    RaftForwardTargetPickerItem(
                        stableKey = "channel-android",
                        title = "android-artifacts",
                        subtitle = "Builds and screenshots",
                        visualKind = RaftForwardTargetVisualKind.PublicChannel,
                        payload = null
                    ),
                    RaftForwardTargetPickerItem(
                        stableKey = "channel-design",
                        title = "design",
                        subtitle = "Product and UI decisions",
                        visualKind = RaftForwardTargetVisualKind.PublicChannel,
                        payload = null
                    )
                )
            }
        }
    RaftForwardTargetPicker(
        messageCount = 2,
        skippedCount = 0,
        nestedForwardCount = 0,
        sourceLabel = "#design",
        singleTapContinues = !scrollProbe,
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
