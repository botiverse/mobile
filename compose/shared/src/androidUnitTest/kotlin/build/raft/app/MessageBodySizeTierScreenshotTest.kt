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
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.ThreadMessageCard
import build.raft.app.thread.key
import build.raft.app.thread.raftMessageBodyTextStyle
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reader's message text size (Settings › Appearance) in its three tiers, Small / Medium / Large,
 * one message each. The tiers sit relative to `text-body` as on the web (appearanceStore
 * MESSAGE_BODY_FONT_SIZE_VALUES: body -/+ 2px, line -/+ 4px):
 *  - `thread.message-body-size.wide`: a wide window (body 14/22): 12/18, 14/22, 16/26;
 *  - `thread.message-body-size.phone`: a phone (body 16/24): 14/20, 16/24, 18/28, as before.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageBodySizeTierScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h420dp-xxhdpi")
    fun phone() = capture("thread.message-body-size.phone")

    private fun capture(name: String) =
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { TierRows() }
            }
        }
}

private val tierMessages =
    listOf("sm" to "Small", "md" to "Medium", "lg" to "Large").mapIndexed { index, (key, label) ->
        key to
            RaftThreadMessageItem(
                id = "msg-$key",
                authorId = "user-artin",
                authorName = "artin",
                authorKind = "human",
                avatarLabel = "A",
                timestampLabel = "2026-11-19T10:3$index:00Z",
                body = "$label: the release notes are in the task, and the preview build is up for testing.",
            )
    }

@Composable
private fun TierRows() {
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        tierMessages.forEach { (key, message) ->
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle(key),
                timestampLabel = message.timestampLabel.substring(11, 16),
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
