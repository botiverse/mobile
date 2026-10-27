package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * App Bots, light and dark, as the web draws them (botiverse/slock staging 6386cfe22, #11273,
 * #11303, #11321):
 *  - author line: an App Bot named after its App reads "Stamp [Bot]"; one shown under another
 *    name reads "Release helper [Bot · Listing Bot App]"; neither shows a model.
 *  - DM header: the Bot badge replaces the presence line ("Offline").
 *  - removed bot DM: the warning notice above the disabled composer ("Chat is unavailable").
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h360dp-xxhdpi")
class ThreadAppBotScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun authorLineLight() = capture("thread.app-bot.author-line", RaftAppAppearance.Light) { AuthorRows() }

    @Test fun authorLineDark() = capture("thread.app-bot.author-line.dark", RaftAppAppearance.Dark) { AuthorRows() }

    @Test fun dmHeaderLight() = capture("thread.app-bot.dm-header", RaftAppAppearance.Light) { DmHeader() }

    @Test fun dmHeaderDark() = capture("thread.app-bot.dm-header.dark", RaftAppAppearance.Dark) { DmHeader() }

    @Test fun removedDmComposerLight() = capture("thread.app-bot.removed-dm", RaftAppAppearance.Light) { RemovedDmComposer() }

    @Test fun removedDmComposerDark() = capture("thread.app-bot.removed-dm.dark", RaftAppAppearance.Dark) { RemovedDmComposer() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftMemberAppBotResolver provides appBots,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) { content() }
                }
            }
        }
}

private val appBots =
    raftStaticMemberAppBotResolver(
        mapOf(
            "agent-stamp" to RaftAppBotIdentity(handle = "stamp", appName = "Stamp", removed = false),
            "agent-helper" to RaftAppBotIdentity(handle = "listing-bot", appName = "Listing Bot App", removed = true),
        ),
    )

private fun botRow(id: String, seq: Long, authorId: String, author: String, body: String) =
    RaftThreadMessageItem(
        id = id,
        authorId = authorId,
        authorName = author,
        authorKind = "agent",
        isOwn = false,
        avatarLabel = author.take(1),
        seq = seq,
        timestampLabel = "2026-10-26T09:${10 + seq}:00Z",
        body = body,
    )

@Composable
private fun AuthorRows() {
    Column(Modifier.padding(top = RaftAppSpacing.Sm)) {
        listOf(
            botRow("m-1", 1, "agent-stamp", "Stamp", "PR #3753 is approved and ready to merge."),
            botRow("m-2", 2, "agent-helper", "Release helper", "Release notes for 1.21.35 are drafted."),
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
}

@Composable
private fun DmHeader() {
    val payload = RaftThreadScreenPayload(instanceId = "dm-stamp", surfaceKind = RaftThreadSurfaceKind.DirectMessage, status = "content")
    ThreadPageHeader(
        payload = payload,
        headerDisplay =
            ThreadHeaderDisplayModel(
                title = "Stamp",
                subtitle = null,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                dmPeerIdentity = MemberDetailIdentity(MemberId("agent-stamp"), MemberKind.Agent),
            ),
        applyTopSafeArea = false,
        showActions = false,
        onBack = {},
        onOpenInChannel = {},
        onOpenMemberProfile = {},
        onOpenSearch = {},
        onShowMembers = {},
        onShowSettings = {},
    )
}

@Composable
private fun RemovedDmComposer() {
    Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.BottomStart) {
        androidx.compose.material3.Text(
            text = "Release notes for 1.21.35 are drafted.",
            style = RaftType.Body,
            color = RaftColor.TextBlack,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    ThreadComposerDock {
        Column(Modifier.fillMaxWidth()) {
            ThreadRemovedAppBotNotice(handle = "listing-bot")
            ThreadComposerContent(
                payload =
                    RaftThreadComposerPayload(instanceId = "dm", placeholder = "Message @listing-bot")
                        .removedAppBotReadOnly(stringResource(Res.string.thread_composer_chat_unavailable)),
                onEvent = { _, _, _, _ -> },
                attachmentPreviewEnabled = false,
            )
        }
    }
}
