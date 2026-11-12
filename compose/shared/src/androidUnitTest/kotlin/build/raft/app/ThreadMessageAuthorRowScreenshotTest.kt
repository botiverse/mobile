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
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `thread.message-author-row` (dm:@artin:#120; web MessageItem, botiverse/slock #14629): on a phone,
 *  - Mira, this server's agent, just posted live (`message:new` carries no description): her model,
 *    then her description read from the members directory;
 *  - Quinn, this server's Guest: the Guest label, then the description;
 *  - Friday, a personal assistant: no model, its description;
 *  - artin, an Owner with no description: just the name (no "Owner" on the row);
 *  - a long name with a long description: the description gives way first.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadMessageAuthorRowScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h520dp-xxhdpi")
    fun authorRow() =
        RaftRobolectricScreenshot.capture("thread.message-author-row", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { AuthorRows() }
            }
        }
}

private fun authorRowMessage(id: String, authorId: String, name: String, kind: String, body: String, description: String? = null) =
    RaftThreadMessageItem(
        id = id,
        authorId = authorId,
        authorName = name,
        authorKind = kind,
        avatarLabel = name.take(1),
        timestampLabel = "2026-11-12T10:3${id.last()}:00Z",
        authorDescription = description,
        body = body,
    )

private val authorRowMessages =
    listOf(
        authorRowMessage("m-1", "agent-mira", "Mira", "agent", "Preview 1.25.0 is building."),
        authorRowMessage("m-2", "user-quinn", "Quinn", "human", "I can review the copy.", description = "Contract designer"),
        authorRowMessage("m-3", "agent-friday", "Friday", "agent", "Added it to your list.", description = "artin's assistant"),
        authorRowMessage("m-4", "user-artin", "artin", "human", "Thanks, testing now."),
        authorRowMessage(
            "m-5",
            "user-long",
            "Release Coordinator for Android",
            "human",
            "Play review passed.",
            description = "Owns preview, TestFlight and Play release trains",
        ),
    )

// The members directory of server-1, read the way the store's resolver reads it.
private val authorRowServer = ServerId("server-1")
private val authorRowProfiles =
    listOf(
        MemberProfile(MemberId("agent-mira"), "Mira", "mira", MemberKind.Agent, description = "Builds and ships the mobile apps", roleDescription = "claude", model = "sonnet", serverId = authorRowServer),
        MemberProfile(MemberId("user-quinn"), "Quinn", "quinn", MemberKind.Human, description = "Contract designer", serverRole = "guest", serverId = authorRowServer),
        MemberProfile(MemberId("agent-friday"), "Friday", "friday", MemberKind.Agent, description = "artin's assistant", roleDescription = "claude", model = "sonnet", assistantOwnerUserId = "user-artin", serverId = authorRowServer),
        MemberProfile(MemberId("user-artin"), "artin", "artin", MemberKind.Human, roleDescription = "Owner", serverRole = "owner", serverId = authorRowServer),
    ).associateBy { it.id.value }

private val authorRowDirectory =
    RaftMemberModelLabelResolver(
        currentLabel = { id -> authorRowProfiles[id]?.authorRowModelLabel(authorRowServer) },
        labelFlow = { id -> flowOf(authorRowProfiles[id]?.authorRowModelLabel(authorRowServer)) },
        currentAuthorFacts = { id -> authorRowProfiles[id]?.authorFacts(authorRowServer) },
        authorFactsFlow = { id -> flowOf(authorRowProfiles[id]?.authorFacts(authorRowServer)) },
    )

@Composable
private fun AuthorRows() {
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        authorRowMessages.forEach { message ->
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                timestampLabel = "10:3${message.id.last()}",
                memberModelLabelResolver = authorRowDirectory,
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
