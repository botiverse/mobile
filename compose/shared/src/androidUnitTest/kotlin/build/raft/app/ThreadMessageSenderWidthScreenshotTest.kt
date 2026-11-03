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
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sender line takes the web's widths (slock web MessageItem `MessageItemSenderGroup`,
 * `max-w-full`; botiverse/slock staging bb8e8bd9b1):
 *  - `thread.message-sender.wide`: on a wide window a long name is shown whole (it used to stop
 *    at 220dp), followed by its subtitle and the time;
 *  - `thread.message-sender.narrow`: on a phone the same name truncates, the subtitle gives up
 *    its room first, and the time stays on the row.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadMessageSenderWidthScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h200dp-xhdpi")
    fun wide() = capture("thread.message-sender.wide")

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h200dp-xxhdpi")
    fun narrow() = capture("thread.message-sender.narrow")

    private fun capture(name: String) =
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { SenderRows() }
            }
        }
}

private val longName =
    RaftThreadMessageItem(
        id = "msg-1",
        authorId = "agent-release",
        authorName = "Release Coordinator for the Android and iOS Builds",
        authorKind = "agent",
        avatarLabel = "R",
        timestampLabel = "2026-11-03T10:30:00Z",
        authorDescription = "Owns preview, TestFlight and Play release trains",
        body = "Preview 1.19.1 is up; the download link is in the task.",
    )

private val shortName =
    longName.copy(
        id = "msg-2",
        authorId = "user-artin",
        authorName = "artin",
        authorKind = "human",
        avatarLabel = "A",
        timestampLabel = "2026-11-03T10:32:00Z",
        authorDescription = null,
        body = "Thanks, testing now.",
    )

@Composable
private fun SenderRows() {
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        listOf(longName, shortName).forEach { message ->
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
            )
        }
    }
}
