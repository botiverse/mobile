package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import build.raft.app.data.ThreadActionCard
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An agent's action cards in a conversation (web ActionCard on raft-ui MessageActionCard), light
 * and dark: a channel to create, waiting with the agent's reason; an agent created by RC, folded
 * to its done line; an app login with its permissions; members to add that this reader can't
 * run (View only); a frozen card; a card to confirm again; an agent card prepared on another
 * server (View only, folded, saying where it runs). Fixed fixture data. Reports land in `build/reports/home-screenshots/`;
 * verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h2000dp-xxhdpi")
class ThreadActionCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun threadActionCardsLight() = capture("thread.action-cards", RaftAppAppearance.Light)

    @Test
    fun threadActionCardsDark() = capture("thread.action-cards.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { ThreadActionCardsVisualContent() }
        }
}

@Composable
private fun ThreadActionCardsVisualContent() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ThreadActionCardView(
            card =
                ThreadActionCard(
                    type = "channel:create",
                    name = "release-review",
                    visibility = "private",
                    description = "Coordinate the final release review.",
                    initialHumans = listOf("Ada", "Will"),
                    initialAgents = listOf("Atlas"),
                    draftHint = "You asked for a private space for the release review.",
                ),
            pending = false,
            onCommit = {},
        )
        ThreadActionCardView(
            card =
                ThreadActionCard(
                    type = "agent:create",
                    state = "executed",
                    name = "Folio",
                    description = "Documentation DRI: owns how Raft's written docs are shaped and kept current.",
                    draftHint = "For stdrc: a dedicated agent for writing docs and designing doc frameworks.",
                    executedByUserName = "RC",
                    resultKind = "agent",
                    resultName = "Folio",
                ),
            pending = false,
            executedTimeLabel = "10/20 23:09",
            onCommit = {},
        )
        ThreadActionCardView(
            card =
                ThreadActionCard(
                    type = "integration:approve_agent_login",
                    clientName = "Linear",
                    agentName = "lumen",
                    scopes = listOf("openid", "profile", "identity"),
                ),
            pending = false,
            onCommit = {},
        )
        ThreadActionCardView(
            card =
                ThreadActionCard(
                    type = "channel:add_member",
                    channel = "general",
                    humans = listOf("Ada", "Will"),
                    agents = listOf("Lumen"),
                ),
            pending = false,
            commitEnabled = false,
            onCommit = {},
        )
        ThreadActionCardView(
            card = ThreadActionCard(type = "channel:create", name = "launch", visibility = "public", state = "frozen"),
            pending = false,
            onCommit = {},
        )
        ThreadActionCardView(
            card = ThreadActionCard(type = "channel:create", name = "launch", visibility = "public", state = "reconfirm_required"),
            pending = false,
            onCommit = {},
        )
        // Prepared on a joint channel's peer server: read-only here, says where it runs.
        CompositionLocalProvider(LocalThreadViewerServerId provides "server-b") {
            ThreadActionCardView(
                card =
                    ThreadActionCard(
                        type = "agent:create",
                        name = "Scout",
                        sourceServerId = "server-a",
                        targetServerName = "Raft HQ",
                    ),
                pending = false,
                onCommit = {},
            )
        }
    }
}
