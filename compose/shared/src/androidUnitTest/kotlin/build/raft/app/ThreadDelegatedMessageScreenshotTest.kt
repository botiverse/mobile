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
import build.raft.app.model.MessageDelegatedBy
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RFC 077 (assistant launch, task #6) message rows, light and dark: Alice's own message; two
 * messages her assistant Friday sent for her (Friday's face on her avatar and "sent by Friday",
 * then a continuation row ending "(sent by Friday)" — grouped with each other, never with her
 * own); one from a deleted assistant (grayed default face, "sent by a deleted assistant").
 * Grouping runs through [shouldSuppressCompactThreadAuthor] like the message list.
 * Reports land in `build/reports/thread-delegated-screenshots/`; verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
class ThreadDelegatedMessageScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun delegatedThreadLight() = capture("thread.delegated-sending", RaftAppAppearance.Light)

    @Test
    fun delegatedThreadDark() = capture("thread.delegated-sending.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/thread-delegated-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { DelegatedThreadRows() }
            }
        }
}

private val friday = MessageDelegatedBy(id = "agent-friday", name = "Friday", avatarUrl = "pixel:random:friday")
private val deletedAssistant = MessageDelegatedBy(id = "agent-gone", name = "Echo", deleted = true)

private fun aliceRow(id: String, minute: Int, body: String, delegatedBy: MessageDelegatedBy? = null) =
    RaftThreadMessageItem(
        id = id,
        authorId = "user-alice",
        authorName = "Alice",
        authorKind = "human",
        avatarLabel = "A",
        timestampLabel = "2026-10-21T16:$minute:00Z",
        body = body,
        delegatedBy = delegatedBy,
    )

@Composable
private fun DelegatedThreadRows() {
    val rows =
        listOf(
            aliceRow("own", 40, "Morning! I'm out until 2 today. Friday will post the launch checklist for me."),
            aliceRow("sent-1", 42, "The launch checklist is ready for review: two items left.", friday),
            aliceRow("sent-2", 43, "Both are linked in the launch doc.", friday),
            aliceRow("sent-gone", 45, "Moved the design review to Thursday at 4.", deletedAssistant),
        )
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        rows.forEachIndexed { index, message ->
            val suppress = shouldSuppressCompactThreadAuthor(rows.getOrNull(index - 1), message, localOffsetMinutes = 0)
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                compactConsecutiveMessages = true,
                suppressRepeatedAuthor = suppress,
                timestampLabel = "4:${message.timestampLabel.substring(14, 16)} PM",
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
