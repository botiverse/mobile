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
import build.raft.app.markdown.LocalRaftMarkdownRenderConfig
import build.raft.app.markdown.LocalRaftMarkdownTaskStatuses
import build.raft.app.markdown.LocalRaftMessagePermalinkLabelResolver
import build.raft.app.markdown.RaftMarkdownRenderConfig
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.model.TaskStatus
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin #20 lane D: references and Markdown in a message, light and dark. Row one holds every
 * inline chip (channel, thread, the reader's own @mention, someone else's @mention, inline code, a
 * task with a status and an unknown one); row two a web link and a message permalink chip; row
 * three a bulleted and a numbered list, a quote and a rule. Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadMessageRefsMarkdownScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun refsMarkdownLight() = capture("thread.refs-markdown", RaftAppAppearance.Light)

    @Test
    fun refsMarkdownDark() = capture("thread.refs-markdown.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftMarkdownTaskStatuses provides mapOf(92 to TaskStatus.InReview, 145 to TaskStatus.Done),
                LocalRaftMarkdownRenderConfig provides RaftMarkdownRenderConfig(renderMessagePermalinksAsTag = true),
                LocalRaftMessagePermalinkLabelResolver provides { "proj-raft-ui" },
            ) {
                RaftDesignSystemTheme { RefsMarkdownRows() }
            }
        }
    }
}

private val refsKnownChannels = setOf("proj-raft-ui", "general")
private val refsKnownMentions = setOf("artin", "ada")

private fun isRefValid(tag: ThreadMarkdownTag): Boolean =
    when (tag.kind) {
        ThreadMarkdownTagKind.Channel, ThreadMarkdownTagKind.ChannelThread ->
            tag.channelName.orEmpty().lowercase() in refsKnownChannels
        ThreadMarkdownTagKind.UserMention -> tag.label.removePrefix("@").lowercase() in refsKnownMentions
        ThreadMarkdownTagKind.Task -> true
        else -> false
    }

private val chipsBody =
    "@artin can you check #proj-raft-ui and the thread #proj-raft-ui:692d9e88 with @Ada? " +
        "Run `./gradlew check` first, then #92, #145 and #7 去#general 看一下。"

private val linksBody =
    "Specs at [raft-design](https://raft-design.botiverse.build) and in " +
        "https://app.raft.build/s/mobile/channel/channel-1?msg=message-1 for context."

private val blocksBody =
    """
    Release notes:
    - Faster scrolling
    - Server switch
      - nested item

    3. Fix the crash
    4. Fix the display

    > A quoted line that wraps across more than one line, to show the rail beside it.

    ---
    After the rule.
    """.trimIndent()

@Composable
private fun RefsMarkdownRows() {
    val rows =
        listOf(chipsBody, linksBody, blocksBody).mapIndexed { index, body ->
            RaftThreadMessageItem(
                id = "refs-$index",
                authorId = "user-niko",
                authorName = "Niko",
                authorKind = "human",
                avatarLabel = "N",
                timestampLabel = "2026-10-24T09:4$index:00Z",
                body = body,
            )
        }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        rows.forEachIndexed { index, message ->
            val suppress = shouldSuppressCompactThreadAuthor(rows.getOrNull(index - 1), message, localOffsetMinutes = 0)
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                highlightedMentions = setOf("artin"),
                compactConsecutiveMessages = true,
                suppressRepeatedAuthor = suppress,
                timestampLabel = "9:4$index AM",
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = { _, _ -> },
                onOpenMarkdownUrl = {},
                isMarkdownTagValid = ::isRefValid,
            )
        }
    }
}
