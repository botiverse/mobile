package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
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
 * line floating over the list's end, just above where the composer starts, on the list's end fade
 * (raft-ui `MessageListEndFade`). One agent, and four (the first two named, the rest counted, three
 * faces). Then a long message scrolled under the edge: with the line (solid panel color under it,
 * the fade above) and without it (the fade alone). Reports land in `build/reports/home-screenshots/`;
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

    @Test fun overLongMessageLight() =
        capture("thread.agent-typing.long-message", RaftAppAppearance.Light, agents.take(1), longMessage = true)

    @Test fun overLongMessageDark() =
        capture("thread.agent-typing.long-message.dark", RaftAppAppearance.Dark, agents.take(1), longMessage = true)

    @Test fun nobodyTypingLight() =
        capture("thread.agent-typing.none", RaftAppAppearance.Light, emptyList(), longMessage = true)

    @Test fun nobodyTypingDark() =
        capture("thread.agent-typing.none.dark", RaftAppAppearance.Dark, emptyList(), longMessage = true)

    private fun capture(
        name: String,
        appearance: RaftAppAppearance,
        typing: List<ThreadTypingAgent>,
        longMessage: Boolean = false,
    ) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                        // Unbounded, so a long message runs on under the bottom edge, as in a scrolled list.
                        Column(
                            Modifier.fillMaxSize().wrapContentHeight(Alignment.Top, unbounded = true)
                                .padding(top = RaftAppSpacing.Sm),
                        ) { Rows(longMessage) }
                        // As ThreadPage stacks them: the end fade, then the line on it.
                        ThreadMessageListEndFade(
                            typingLineShown = typing.isNotEmpty(),
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                        if (typing.isNotEmpty()) {
                            ThreadAgentTypingLine(
                                typing = ThreadAgentTypingUi(serverId = "srv-1", agents = typing),
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
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
private fun Rows(longMessage: Boolean) {
    val messages = if (longMessage) shortMessages.take(1) + longReply else shortMessages
    messages.forEach { message ->
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

/** A reply long enough to run under the list's bottom edge. */
private val longReply =
    RaftThreadMessageItem(
        id = "m-3",
        authorId = "agent-niko",
        authorName = "Niko",
        authorKind = "agent",
        avatarLabel = "N",
        seq = 3,
        timestampLabel = "2026-10-23T09:14:00Z",
        body =
            "Two incidents yesterday, both resolved. The first was a slow deploy of the API at 14:05: " +
                "a migration held a lock on the messages table for six minutes, so sends queued and " +
                "retried. The second was the push relay dropping notifications for about twenty " +
                "minutes after a certificate rotation. I filed follow-ups for both: run long " +
                "migrations in batches, and alert when the relay's delivery rate falls. I'll post " +
                "the timelines and the owners for each follow-up here once they are confirmed.",
    )

private val shortMessages =
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
    )
