package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.MemberServerRef
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Member labels (web botiverse/slock #14535 MessageItem `AvatarOriginCorner`): a sender from
 * another server carries that server's tile (its initial; no logo: the viewer is not in it) on the
 * avatar's top-left corner, opposite the agent's presence light; this server's sender has none.
 *  - `thread.message-sender.origin` (light) and `.dark`: a person and an agent from Peer Labs, then
 *    one of this server's people.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h320dp-xxhdpi")
class ThreadMessageSenderOriginScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun light() = capture("thread.message-sender.origin", RaftAppAppearance.Light)

    @Test fun dark() = capture("thread.message-sender.origin.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { OriginRows() }
            }
        }
}

private val peerLabs = MemberServerRef("server-peer", "Peer Labs", "peer-labs")

private val originFacts =
    mapOf(
        "user-bo" to RaftMemberAuthorFacts(originServers = listOf(peerLabs)),
        "agent-mira" to RaftMemberAuthorFacts(originServers = listOf(peerLabs)),
    )

private val originResolver =
    RaftMemberModelLabelResolver(
        currentLabel = { null },
        labelFlow = { flowOf(null) },
        currentAuthorFacts = { id -> originFacts[id] },
        authorFactsFlow = { id -> flowOf(originFacts[id]) },
    )

private val fromPeer =
    RaftThreadMessageItem(
        id = "msg-1",
        authorId = "user-bo",
        authorName = "Bo Peer",
        authorKind = "human",
        avatarLabel = "B",
        timestampLabel = "2026-11-13T10:30:00Z",
        body = "The partner build is ready on our side.",
    )

private val agentFromPeer =
    fromPeer.copy(
        id = "msg-2",
        authorId = "agent-mira",
        authorName = "Mira",
        authorKind = "agent",
        avatarLabel = "M",
        timestampLabel = "2026-11-13T10:31:00Z",
        body = "Smoke tests passed on Peer Labs' runners.",
    )

private val local =
    fromPeer.copy(
        id = "msg-3",
        authorId = "user-artin",
        authorName = "artin",
        avatarLabel = "A",
        timestampLabel = "2026-11-13T10:32:00Z",
        body = "Thanks, merging ours.",
    )

@Composable
private fun OriginRows() {
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        listOf(fromPeer, agentFromPeer, local).forEach { message ->
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                timestampLabel = "10:${message.timestampLabel.substring(14, 16)}",
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = { _, _ -> },
                onOpenMarkdownUrl = {},
                memberModelLabelResolver = originResolver,
            )
        }
    }
}
