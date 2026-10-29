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
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberKind
import build.raft.designsystem.components.RaftPreviewCardSurface
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The @mention hover card's profile content (web `ProfilePreviewCardContent`) on the raft-ui
 * PreviewCard surface, light and dark, without the popup (the design system's
 * `preview_card_open_*` holds the card open over its trigger):
 *  - a person: avatar, name, @handle, the description under a hairline, Message and View Profile;
 *  - an agent: its status light and words after the name, @handle, "Not read yet", Runtime and
 *    Model under a hairline, the description.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
class ProfilePreviewCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun humanLight() = capture("profile-preview.human", RaftAppAppearance.Light) { Card(humanModel, actions = true) }

    @Test fun humanDark() = capture("profile-preview.human.dark", RaftAppAppearance.Dark) { Card(humanModel, actions = true) }

    @Test fun agentLight() = capture("profile-preview.agent", RaftAppAppearance.Light) { Card(agentModel, actions = false) }

    @Test fun agentDark() = capture("profile-preview.agent.dark", RaftAppAppearance.Dark) { Card(agentModel, actions = false) }

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
private fun Card(model: RaftProfilePreviewCardModel, actions: Boolean) {
    RaftPreviewCardSurface {
        RaftProfilePreviewCardContent(
            model = model,
            onMessage = if (actions) ({}) else null,
            onViewProfile = if (actions) ({}) else null,
        )
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
        readReceipt = RaftProfilePreviewReadReceipt.Unread,
    )
