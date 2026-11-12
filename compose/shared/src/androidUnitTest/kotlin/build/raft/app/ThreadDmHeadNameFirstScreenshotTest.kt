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
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An agent DM's head on a 390dp phone with task v2's Tasks · Files · Settings (artin 2026-11-06,
 * #proj-mobile:d7ca4947): the name keeps its width and the activity words give way, down to the
 * dot. "Daniel" + "Message received" (the reported case), a long name, and a short status that
 * fits whole. The production [ThreadPageHeader]; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h240dp-xxhdpi")
class ThreadDmHeadNameFirstScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun crowded() = capture("thread.dm-head.name-first", RaftAppAppearance.Light)

    @Test fun crowdedDark() = capture("thread.dm-head.name-first.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        DmHead("Daniel", "Message received")
                        DmHead("Release coordinator", "Reviewing release readiness")
                        DmHead("Volta", "Idle")
                    }
                }
            }
        }
}

@Composable
private fun DmHead(title: String, status: String) {
    val identity = remember(title) { MemberDetailIdentity(MemberId("agent-$title"), MemberKind.Agent) }
    val state =
        remember(status) {
            RaftAgentStatusIndicatorState(
                status = AgentStatus.Working,
                color = RaftColor.AccentLime,
                label = status,
                external = false,
            )
        }
    val resolver: RaftAgentStatusResolver = remember(state) { { flowOf(state) } }
    ThreadPageHeader(
        payload = remember(title) { RaftThreadScreenPayload(instanceId = "dm-$title", surfaceKind = RaftThreadSurfaceKind.DirectMessage, status = "content") },
        headerDisplay =
            ThreadHeaderDisplayModel(
                title = title,
                subtitle = null,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
                dmPeerAvatarLabel = title.take(2).uppercase(),
                dmPeerIdentity = identity,
            ),
        agentStatusResolver = resolver,
        applyTopSafeArea = false,
        onBack = {},
        onOpenInChannel = {},
        onOpenMemberProfile = {},
        onOpenSearch = {},
        onShowMembers = {},
        onShowSettings = {},
        sideCardToggles = remember { ThreadHeaderSideCardToggles(tasksAvailable = true, cards = ConversationSideCardsState(), onToggle = {}) },
    )
}
