package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.ThreadMarkdownTagKind
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Agent typing line (raft-design 0042), light and dark: the conversation's last messages with the
 * line floating over the list's end, just above where the composer starts. One agent, and four (the
 * first two named, the rest counted, three faces). Reports land in `build/reports/home-screenshots/`;
 * verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h260dp-xxhdpi")
class ThreadAgentTypingScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun oneAgentLight() = capture("thread.agent-typing.one", RaftAppAppearance.Light, agents.take(1))

    @Test fun oneAgentDark() = capture("thread.agent-typing.one.dark", RaftAppAppearance.Dark, agents.take(1))

    @Test fun manyAgentsLight() = capture("thread.agent-typing.many", RaftAppAppearance.Light, agents)

    @Test fun manyAgentsDark() = capture("thread.agent-typing.many.dark", RaftAppAppearance.Dark, agents)

    private fun capture(name: String, appearance: RaftAppAppearance, typing: List<ThreadTypingAgent>) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                        Column(Modifier.fillMaxSize().padding(top = RaftAppSpacing.Sm)) { Rows() }
                        ThreadAgentTypingLine(
                            typing = ThreadAgentTypingUi(serverId = "srv-1", agents = typing),
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
}

private val agents =
    listOf(
        ThreadTypingAgent("agent-niko", "Niko", null),
        ThreadTypingAgent("agent-kirby", "Kirby", null),
        ThreadTypingAgent("agent-tenny", "Tenny", null),
        ThreadTypingAgent("agent-mo", "Mo", null),
    )

@Composable
private fun Rows() {
    listOf(
        RaftThreadMessageItem(
            id = "m-1",
            authorId = "user-me",
            authorName = "Me",
            authorKind = "human",
            isOwn = true,
            avatarLabel = "M",
            seq = 1,
            timestampLabel = "2026-10-23T09:11:00Z",
            body = "Can someone summarize yesterday's incidents and file follow-ups?",
        ),
        RaftThreadMessageItem(
            id = "m-2",
            authorId = "agent-niko",
            authorName = "Niko",
            authorKind = "agent",
            avatarLabel = "N",
            seq = 2,
            timestampLabel = "2026-10-23T09:12:00Z",
            body = "On it. Two incidents, both resolved; I'll post the details here.",
        ),
    ).forEach { message ->
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
    }
}
