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
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Channel refs glued to CJK text (`去#proj-raft-ui`) render as chips, like the web
 * (`createRaftChannelRefRegex` has no left boundary). Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadChannelRefScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun channelRefsLight() =
        RaftRobolectricScreenshot.capture(
            "thread.channel-ref-cjk",
            RaftAppAppearance.Light,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
        ) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { ChannelRefRows() }
            }
        }
}

private val knownChannels = setOf("proj-raft-ui", "general")

private fun isChannelRefValid(tag: ThreadMarkdownTag): Boolean =
    when (tag.kind) {
        ThreadMarkdownTagKind.Channel -> tag.channelName.orEmpty().lowercase() in knownChannels
        ThreadMarkdownTagKind.Task -> true
        else -> false
    }

@Composable
private fun ChannelRefRows() {
    val rows =
        listOf(
            RaftThreadMessageItem(
                id = "glued",
                authorId = "user-artin",
                authorName = "artin",
                authorKind = "human",
                avatarLabel = "A",
                timestampLabel = "2026-10-23T16:40:00Z",
                body = "这个组件样式很怪啊，看看我们对话框到底还有哪些样式没适配的，你可以写完之后去#proj-raft-ui 写一下",
            ),
            RaftThreadMessageItem(
                id = "spaced",
                authorId = "user-artin",
                authorName = "artin",
                authorKind = "human",
                avatarLabel = "A",
                timestampLabel = "2026-10-23T16:41:00Z",
                body = "写到 #proj-raft-ui，然后（#general）看一下；a#nope 不是频道",
            ),
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
                isMarkdownTagValid = ::isChannelRefValid,
            )
        }
    }
}
