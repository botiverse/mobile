package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.members.RaftProfilePreviewCardContent
import build.raft.app.members.RaftProfilePreviewCardModel
import build.raft.app.members.RaftProfilePreviewComputer
import build.raft.app.members.RaftProfilePreviewReadReceipt
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberKind
import build.raft.app.resources.desc
import build.raft.designsystem.components.RaftPreviewCardSurface
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The @mention hover card's profile content (web `ProfilePreviewCardContent`) on the raft-ui
 * PreviewCard surface, light and dark, without the popup (the design system's
 * `preview_card_open_*` holds the card open over its trigger). No buttons, as on the web: the
 * trigger's own click opens the profile.
 *  - a person: avatar, name, @handle, the description under a hairline (two lines at most);
 *  - an agent: its status light and words after the name, @handle, "Not read yet", Computer,
 *    Runtime, Model and Reasoning under a hairline, the description cut at two lines;
 *  - someone from another server: the `info-soft` ribbon across the top (Peer Labs · Acme Corp's
 *    tiles and names, "External"), then the person as above.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
class ProfilePreviewCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun humanLight() = capture("profile-preview.human", RaftAppAppearance.Light) { Card(humanModel) }

    @Test fun humanDark() = capture("profile-preview.human.dark", RaftAppAppearance.Dark) { Card(humanModel) }

    @Test fun agentLight() = capture("profile-preview.agent", RaftAppAppearance.Light) { Card(agentModel) }

    // Member labels (web botiverse/slock #14535 ExternalOriginRibbon): someone from another server,
    // the ribbon across the card's top with their servers' tiles and names, and "External".
    @Test fun externalLight() = capture("profile-preview.external", RaftAppAppearance.Light) { Card(externalModel) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(24.dp)) { content() }
                }
            }
        }
}

@Composable
private fun Card(model: RaftProfilePreviewCardModel) {
    RaftPreviewCardSurface {
        RaftProfilePreviewCardContent(model = model)
    }
}

private val humanModel =
    RaftProfilePreviewCardModel(
        memberId = "human-ada",
        kind = MemberKind.Human,
        displayName = "Ada Lovelace",
        handle = "ada",
        description = "Designs the release checklists and reviews every store listing before it ships.",
    )

private val agentModel =
    RaftProfilePreviewCardModel(
        memberId = "agent-bugen",
        kind = MemberKind.Agent,
        displayName = "Bugen",
        handle = "bugen",
        avatarWireValue = "pixel:robot",
        description = "Reviews pull requests for the mobile app and keeps the release notes honest.",
        status =
            SharedServerMemberStatus(status = AgentStatus.Working, presence = AgentStatus.Working.presence, runtime = "claude", external = false)
                .toRaftAgentStatusIndicatorState(),
        runtimeLabel = "Claude Code",
        modelLabel = "Claude Opus",
        reasoningLabel = "High".desc(),
        computer = RaftProfilePreviewComputer.Named("studio-mac"),
        readReceipt = RaftProfilePreviewReadReceipt.Unread,
    )

private val externalModel =
    humanModel.copy(
        memberId = "human-bo",
        displayName = "Bo Peer",
        handle = "bo",
        description = "Runs the partner integrations from the Peer Labs side.",
        external = true,
        originServers =
            listOf(
                build.raft.app.model.MemberServerRef("server-peer", "Peer Labs", "peer-labs"),
                build.raft.app.model.MemberServerRef("server-acme", "Acme Corp", "acme"),
            ),
    )
