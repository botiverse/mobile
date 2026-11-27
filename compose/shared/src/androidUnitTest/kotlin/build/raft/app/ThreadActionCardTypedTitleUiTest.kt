package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardMetadataItem
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.thread.LocalThreadActionCardNames
import build.raft.app.thread.ThreadActionCardEntityKind
import build.raft.app.thread.ThreadActionCardFlatPermissionsTag
import build.raft.app.thread.ThreadActionCardNameLookup
import build.raft.app.thread.ThreadActionCardView
import build.raft.app.thread.ThreadInstallAppActionDialogState
import build.raft.app.thread.ThreadInstallAppActionIntent
import build.raft.app.thread.ThreadInstallAppActionSheetSurfaceForFixture
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Typed action card presentation and App detail v6 alignment:
 * - `integration:connect_mcp` names the App and the agent from its spec without legacy notice.
 * - `agent:create` names the agent without an `@`: "Create agent Scribe".
 * - `integration:approve_agent_login`, `integration:install_marketplace_app`, and
 *   `integration:register_app` omit redundant Service / Agent, Redirect URI, and Client key /
 *   Client ID rows, render permissions flat in plain language (never collapsed behind
 *   `Permissions (N)`), and use unified Add / Create Raft app wording.
 * - `ThreadInstallAppActionDialog` uses `"Add {App}"` / `"Add app"` title & CTA, renders known
 *   scopes in plain language, and hides empty permission sections instead of `"None"`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1400dp")
class ThreadActionCardTypedTitleUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

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

    @Test
    fun approveLoginCardOmitsServiceAgentRowsAndRendersFlatPlainPermissions() = runRobolectricComposeUiTest {
        var commits = 0
        val card =
            ThreadActionCard(
                type = "integration:approve_agent_login",
                clientName = "Linear",
                agentName = "@lumen",
                scopes = listOf("openid", "profile", "agent:notification:write", "custom:scope"),
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                ThreadActionCardView(card = card, pending = false, onCommit = { commits += 1 })
            }
        }
        waitForIdle()
        onNodeWithText("Approve Linear for lumen", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(ThreadActionCardFlatPermissionsTag, useUnmergedTree = true).assertIsDisplayed()
        // No duplicate Service / Agent rows and no collapsed "Permissions (N)" header.
        onAllNodesWithText("Service", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Agent", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("@lumen", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Permissions (4)", useUnmergedTree = true).assertCountEquals(0)
        onAllNodes(hasText("Permissions (", substring = true), useUnmergedTree = true).assertCountEquals(0)
        // Known scopes render in plain language without raw scope tokens; unknown scopes stay visible.
        onNodeWithText(
            "Signs you in with Raft and issues a stable identity token. Authentication only.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNodeWithText(
            "Reads your basic profile fields, such as display name. No access to messages, files, or agents.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNodeWithText(
            "Sends notification messages to the one agent you authorize.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onAllNodesWithText("openid", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("profile", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("agent:notification:write", useUnmergedTree = true).assertCountEquals(0)
        onNodeWithText("custom:scope", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Raft has no description for this scope.", useUnmergedTree = true).assertIsDisplayed()
        // Agent-inbound scope notices (web ActionCardScopeNotices).
        onNodeWithText("Will send to: lumen", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(
            "Cannot send chat as you, speak as the agent, read messages, or take actions.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNode(hasText("Approve login") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(1, commits)
    }

    @Test
    fun installMarketplaceAppCardUsesAddCopyOmitsServiceAgentRowsAndRendersFlatPermissions() = runRobolectricComposeUiTest {
        var commits = 0
        val namedCard =
            ThreadActionCard(
                type = "integration:install_marketplace_app",
                clientName = "Notion",
                agentName = "scout",
                scopes = listOf("openid", "agent:read"),
                installScopes = listOf("openid", "agent:read"),
                installGroups = listOf("agent"),
            )
        val unnamedCard =
            ThreadActionCard(
                type = "integration:install_marketplace_app",
                clientName = null,
                agentName = "scout",
                scopes = emptyList(),
                installScopes = emptyList(),
                installGroups = emptyList(),
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                Column {
                    ThreadActionCardView(card = namedCard, pending = false, onCommit = { commits += 1 })
                    ThreadActionCardView(card = unnamedCard, pending = false, onCommit = {})
                }
            }
        }
        waitForIdle()
        onNodeWithText("Add Notion for scout", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Add Notion", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Add app", useUnmergedTree = true).assertIsDisplayed()
        onAllNodesWithText("Install app", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Service", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Agent", useUnmergedTree = true).assertCountEquals(0)
        onAllNodes(hasText("Permissions (", substring = true), useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("openid", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("agent:read", useUnmergedTree = true).assertCountEquals(0)
        onNodeWithText(
            "Signs you in with Raft and issues a stable identity token. Authentication only.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNodeWithText(
            "Read the IDs, handles, display names and avatars of agents in the Server you authorize. This permission does not allow sending messages or reading conversations, activity or unread counts.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNode(hasText("Add Notion") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(1, commits)
    }

    @Test
    fun registerAppCardOmitsRedirectUriClientKeyRowsAndRendersFlatPermissions() = runRobolectricComposeUiTest {
        var commits = 0
        val card =
            ThreadActionCard(
                type = "integration:register_app",
                name = "Build Monitor",
                metadataItems =
                    listOf(
                        ThreadActionCardMetadataItem("name", "Build Monitor"),
                        ThreadActionCardMetadataItem("service", "Build Monitor"),
                        ThreadActionCardMetadataItem("agent", "@release"),
                        ThreadActionCardMetadataItem("clientKey", "build-monitor"),
                        ThreadActionCardMetadataItem("clientId", "cid-123"),
                        ThreadActionCardMetadataItem("returnUrl", "https://build.example/oauth/callback"),
                        ThreadActionCardMetadataItem("redirectUri", "https://build.example/cb"),
                        ThreadActionCardMetadataItem("scopes", "identity, profile"),
                    ),
                presentationWarning = "The requesting agent becomes the initial app owner. No client secret is stored in this card.",
                genericApprovalAllowed = true,
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                ThreadActionCardView(card = card, pending = false, onCommit = { commits += 1 })
            }
        }
        waitForIdle()
        onNodeWithText("Create Raft app Build Monitor", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Create Raft app", useUnmergedTree = true).assertIsDisplayed()
        onAllNodesWithText("Register app", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Service", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Agent", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Client key", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Client ID", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("build-monitor", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("cid-123", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Return URL", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Redirect URI", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("https://build.example/oauth/callback", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("https://build.example/cb", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Scopes", useUnmergedTree = true).assertCountEquals(0)
        onAllNodes(hasText("Permissions (", substring = true), useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("identity", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("profile", useUnmergedTree = true).assertCountEquals(0)
        onNodeWithText(
            "Reads who you are — human or agent, your Raft subject, and which server/principal you are acting as. No content access.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNodeWithText(
            "Reads your basic profile fields, such as display name. No access to messages, files, or agents.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNode(hasText("Create Raft app") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(1, commits)
    }

    @Test
    fun installAppDialogShowsAddAppTitleCtaPlainPermissionsAndHidesEmptySections() = runRobolectricComposeUiTest {
        val intents = mutableListOf<ThreadInstallAppActionIntent>()
        val scopesOnlyCard =
            ThreadActionCard(
                type = "integration:install_marketplace_app",
                clientName = "Notion",
                agentName = "@scout",
                installScopes = listOf("openid", "agent:read", "notion:pages"),
                installGroups = emptyList(),
            )
        setContent {
            RaftDesignSystemTheme {
                ThreadInstallAppActionSheetSurfaceForFixture(
                    state = ThreadInstallAppActionDialogState(messageId = "m-install-1", card = scopesOnlyCard),
                    onIntent = { intents += it },
                )
            }
        }
        waitForIdle()
        // Both dialog title and primary CTA say "Add Notion" (never "Install app" / "Install").
        onAllNodesWithText("Add Notion", useUnmergedTree = true).assertCountEquals(2)
        onAllNodesWithText("Install app", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("Install", useUnmergedTree = true).assertCountEquals(0)
        onNodeWithText(
            "scout asked to sign in to Notion, which isn't added to this Server yet. Adding it approves all of the following for this Server.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        // Non-empty Login scopes shows plain descriptions; empty Data access is omitted without "None".
        onAllNodesWithText("Login scopes", useUnmergedTree = true).onFirst().assertIsDisplayed()
        onAllNodesWithText("Data access", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("None", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("openid", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("agent:read", useUnmergedTree = true).assertCountEquals(0)
        onNodeWithText(
            "Signs you in with Raft and issues a stable identity token. Authentication only.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNodeWithText("notion:pages", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Raft has no description for this scope.", useUnmergedTree = true).assertIsDisplayed()
        onNode(hasText("Add Notion") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals<List<ThreadInstallAppActionIntent>>(listOf(ThreadInstallAppActionIntent.Confirm), intents)
    }

    @Test
    fun installAppDialogFallsBackToAddAppAndOmitsEmptyLoginScopes() = runRobolectricComposeUiTest {
        val groupsOnlyCard =
            ThreadActionCard(
                type = "integration:install_marketplace_app",
                clientName = null,
                agentName = "scout",
                installScopes = emptyList(),
                installGroups = listOf("agent"),
            )
        setContent {
            RaftDesignSystemTheme {
                ThreadInstallAppActionSheetSurfaceForFixture(
                    state = ThreadInstallAppActionDialogState(messageId = "m-install-2", card = groupsOnlyCard),
                )
            }
        }
        waitForIdle()
        onAllNodesWithText("Add app", useUnmergedTree = true).assertCountEquals(2)
        onAllNodesWithText("Data access", useUnmergedTree = true).onFirst().assertIsDisplayed()
        onNodeWithText("Agent · read", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Directory, profiles, and status", useUnmergedTree = true).assertIsDisplayed()
        onAllNodesWithText("Login scopes", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText("None", useUnmergedTree = true).assertCountEquals(0)
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
