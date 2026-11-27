package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardMetadataItem
import build.raft.app.thread.LocalThreadActionCardNames
import build.raft.app.thread.ThreadActionCardEntityKind
import build.raft.app.thread.ThreadActionCardNameLookup
import build.raft.app.thread.ThreadActionCardView
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The typed action card's titles after botiverse/slock #13298 (2f7e5d5437):
 * - `integration:connect_mcp` (typed here: the App has no connection picker, so its button opens
 *   the card on the web) names the App and the agent from its spec, "Authorize Notion for Kai",
 *   the agent by its display name here, else by the name the server had; a card without a spec
 *   keeps the legacy title. No card says the legacy notice ("Choose a connection to grant").
 * - `agent:create` names the agent without an @: "Create agent Scribe".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1400dp")
class ThreadActionCardTypedTitleUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /**
     * botiverse/slock origin/staging f2c1ab9f3, shared `actionCardSpecBuilder.test.ts.snap`
     * "integration:connect_mcp prepared" (its ids), as the wire carries a Msg: the catalog text of
     * `ACTION_CARD_MESSAGES["actionCard.title.connectMcp"]` (en, ja) and the agent reference.
     */
    private fun connectSpec(agentId: String, fallback: String) =
        """
        {"catalog":"action-card.v1","icon":"plug",
         "title":{"key":"actionCard.title.connectMcp","text":{"en":"Authorize {app} for {agent}","ja":"{agent} の {app} 接続を認可"},"params":{"app":"Notion","agent":{"kind":"agent","id":"$agentId","fallback":"$fallback"}}},
         "spec":{"root":"root","elements":{"root":{"type":"Stack","props":{"direction":"column","wrap":false},"children":[]}}}}
        """.trimIndent()

    /** The legacy fields as shared `buildActionCardPresentation` writes them (snapshot "integration:connect_mcp"). */
    private fun connectCard(agentId: String, spec: String?) =
        ThreadActionCard(
            type = "integration:connect_mcp",
            agentId = agentId,
            metadataItems =
                listOf(
                    ThreadActionCardMetadataItem("mcpServerId", MCP_SERVER_ID),
                    ThreadActionCardMetadataItem("agentId", agentId),
                    ThreadActionCardMetadataItem("reason", "login_required"),
                    ThreadActionCardMetadataItem("revision", "3"),
                ),
            presentationTitle = LEGACY_TITLE,
            presentationConfirmLabel = "Authorize",
            presentationWarning = LEGACY_WARNING,
            presentationCardJson = spec,
        )

    private val names = ThreadActionCardNameLookup { kind, id -> if (kind == ThreadActionCardEntityKind.Agent && id == SCOUT_ID) "Kai" else null }

    @Test
    fun aConnectCardNamesTheAppAndTheAgentWithoutTheNoticeAndANewAgentHasNoAt() = runRobolectricComposeUiTest {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalThreadActionCardNames provides names) {
                    Column {
                        ThreadActionCardView(card = connectCard(SCOUT_ID, connectSpec(SCOUT_ID, "scout")), pending = false, onCommit = {})
                        ThreadActionCardView(card = connectCard(OTHER_AGENT_ID, connectSpec(OTHER_AGENT_ID, "Ivy")), pending = false, onCommit = {})
                        ThreadActionCardView(card = connectCard(SCOUT_ID, spec = null), pending = false, onCommit = {})
                        ThreadActionCardView(card = ThreadActionCard(type = "agent:create", name = "@Scribe"), pending = false, onCommit = {})
                    }
                }
            }
        }
        onAllNodesWithText("Authorize Notion for Kai").assertCountEquals(1)
        // An agent this lookup doesn't know: the name the server had when it prepared the card.
        onAllNodesWithText("Authorize Notion for Ivy").assertCountEquals(1)
        // No spec this App reads: the legacy title, still without the notice.
        onAllNodesWithText(LEGACY_TITLE).assertCountEquals(1)
        onAllNodesWithText(LEGACY_WARNING).assertCountEquals(0)
        // The App can't grant here: Open on the web, never a pink Grant access.
        onAllNodesWithText("Open on the web").assertCountEquals(3)
        onAllNodesWithText("Create agent Scribe").assertCountEquals(1)
        onAllNodesWithText("Create Agent @Scribe").assertCountEquals(0)
    }

    private companion object {
        const val SCOUT_ID = "2d3a9e6a-3f4c-4b8d-9e5f-7a9b0c1d2e3f"
        const val OTHER_AGENT_ID = "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b"
        const val MCP_SERVER_ID = "8d9a5e2a-9f0c-4b4d-9e1f-3a5b6c7d8e9f"
        const val LEGACY_TITLE = "Authorize an MCP connection"
        const val LEGACY_WARNING =
            "An agent tried to use this MCP server and has no authorized connection. Choose a connection to grant, or authorize a new one."
    }
}
