package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import build.raft.app.data.PeerReadReceiptScope
import build.raft.app.data.PeerReadState
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.model.ReadReceiptPeerKind
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * read_receipts_v0, light and dark, as the web draws them (slock staging dac70954, task #693):
 *  - channel: the viewer's own message @mentions two agents and a human; @Alpha has read it (lime
 *    pixel on the mention's corner), @Beta not yet (white pixel), @carol is a human (no pixel). The
 *    agent's own reply carries nothing, and a channel never shows a "Read" line.
 *  - DM with an agent: the viewer's last message, which the agent's watermark has reached, gets the
 *    right-aligned "Read" line under it; the earlier message does not repeat it.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h360dp-xxhdpi")
class ThreadReadReceiptsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun channelAgentReceiptsLight() = capture("thread.read-receipts.channel", RaftAppAppearance.Light) { ChannelRows() }

    @Test fun channelAgentReceiptsDark() = capture("thread.read-receipts.channel.dark", RaftAppAppearance.Dark) { ChannelRows() }

    @Test fun dmPeerWatermarkLight() = capture("thread.read-receipts.dm", RaftAppAppearance.Light) { DmRows() }

    @Test fun dmPeerWatermarkDark() = capture("thread.read-receipts.dm.dark", RaftAppAppearance.Dark) { DmRows() }

    private fun capture(name: String, appearance: RaftAppAppearance, rows: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(top = RaftAppSpacing.Sm)) { rows() }
                }
            }
        }
}

private val mentionDirectory =
    mapOf(
        "alpha" to build.raft.app.model.MessageMention("agent", "agent-alpha", "alpha"),
        "beta" to build.raft.app.model.MessageMention("agent", "agent-beta", "beta"),
        "carol" to build.raft.app.model.MessageMention("user", "user-carol", "carol"),
    )

private fun row(
    id: String,
    seq: Long,
    body: String,
    own: Boolean,
    author: String = "Me",
    kind: String = "human",
) = RaftThreadMessageItem(
    id = id,
    authorId = if (own) "user-me" else "agent-$author".lowercase(),
    authorName = author,
    authorKind = kind,
    isOwn = own,
    avatarLabel = author.take(1),
    seq = seq,
    timestampLabel = "2026-10-22T09:${10 + seq}:00Z",
    body = body,
)

@Composable
private fun ReceiptRows(receipts: ThreadPeerReadReceiptsUi, rows: List<RaftThreadMessageItem>) {
    val lastId = rows.last().id
    CompositionLocalProvider(LocalThreadPeerReadReceipts provides receipts) {
        rows.forEach { message ->
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                isMarkdownTagValid = { tag -> tag.kind == ThreadMarkdownTagKind.UserMention },
                timestampLabel = "9:${message.timestampLabel.substring(14, 16)} AM",
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = { _, _ -> },
                onOpenMarkdownUrl = {},
            )
            if (receipts.dmSurface && message.id == lastId && receipts.showsDmReadStatus(message)) {
                ThreadDmReadStatusRow(Modifier.padding(start = RaftAppSpacing.Sm, end = RaftAppSpacing.Sm, bottom = RaftAppSpacing.Xs))
            }
        }
    }
}

@Composable
private fun ChannelRows() {
    val receipts =
        ThreadPeerReadReceiptsUi(
            scope =
                PeerReadReceiptScope.Peers(
                    listOf(
                        PeerReadState(ReadReceiptPeerKind.Agent, "agent-alpha", 12),
                        PeerReadState(ReadReceiptPeerKind.Agent, "agent-beta", 9),
                    ),
                ),
            dmSurface = false,
            directoryMentions = mentionDirectory,
        )
    ReceiptRows(
        receipts,
        listOf(
            row("m-9", 9, "Deploy notes are in the doc.", own = false, author = "Alpha", kind = "agent"),
            row("m-10", 10, "@alpha can you check the release notes? @beta please review the copy, and @carol FYI.", own = true),
        ),
    )
}

@Composable
private fun DmRows() {
    val receipts =
        ThreadPeerReadReceiptsUi(
            scope = PeerReadReceiptScope.Peers(listOf(PeerReadState(ReadReceiptPeerKind.Agent, "agent-alpha", 11))),
            dmSurface = true,
            directoryMentions = mentionDirectory,
        )
    ReceiptRows(
        receipts,
        listOf(
            row("m-9", 9, "Can you summarize yesterday's incidents?", own = true),
            row("m-10", 10, "Two incidents, both resolved. Details are in the thread.", own = false, author = "Alpha", kind = "agent"),
            row("m-11", 11, "Thanks! Please file a follow-up task for the second one.", own = true),
        ),
    )
}
