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
 * RFC 077 PR 2b message rows, light and dark (web MessageItem `message-retracted-*`): Alice's own
 * message; a message her assistant Friday sent for her and she retracted — the header (sender,
 * "sent by Friday", time) stays and the body is the muted localized retracted line; the row's
 * stale attachment, reaction and the server's English placeholder do not render; then a
 * continuation row Friday sent that was retracted too ("(sent by Friday)" still ends it).
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
class ThreadRetractedMessageScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun retractedThreadLight() = capture("thread.delegated-retracted", RaftAppAppearance.Light)

    @Test
    fun retractedThreadDark() = capture("thread.delegated-retracted.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { RetractedThreadRows() }
            }
        }
}

private val friday = MessageDelegatedBy(id = "agent-friday", name = "Friday", avatarUrl = "pixel:random:friday")
private const val ServerPlaceholder = "This message was retracted by its sender."

private fun aliceRow(id: String, minute: Int, body: String, delegatedBy: MessageDelegatedBy? = null, deletedAt: String? = null) =
    RaftThreadMessageItem(
        id = id,
        authorId = "user-alice",
        authorName = "Alice",
        authorKind = "human",
        avatarLabel = "A",
        timestampLabel = "2026-10-21T16:$minute:00Z",
        body = body,
        delegatedBy = delegatedBy,
        deletedAt = deletedAt,
    )

@Composable
private fun RetractedThreadRows() {
    val rows =
        listOf(
            aliceRow("own", 40, "Morning! I'm out until 2 today. Friday will post the launch checklist for me."),
            aliceRow("retracted-1", 42, ServerPlaceholder, friday, deletedAt = "2026-10-21T16:44:00Z").copy(
                // A row held from before the retraction: none of this may render.
                attachments = listOf(RaftThreadAttachmentItem(id = "att-1", name = "checklist.pdf", mimeType = "application/pdf", sizeLabel = "120 KB")),
                reactions = listOf(RaftThreadReactionItem(emoji = "👍", count = 2)),
            ),
            aliceRow("retracted-2", 43, ServerPlaceholder, friday, deletedAt = "2026-10-21T16:44:00Z"),
        )
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        rows.forEachIndexed { index, message ->
            val suppress = shouldSuppressCompactThreadAuthor(rows.getOrNull(index - 1), message, localOffsetMinutes = 0)
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                compactConsecutiveMessages = true,
                suppressRepeatedAuthor = suppress,
                showReactionAddAction = true,
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
