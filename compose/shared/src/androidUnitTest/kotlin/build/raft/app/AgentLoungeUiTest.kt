package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Agent Lounge v0 on a real composition (AGENTS.md "Compose UI tests"), against the web behaviour
 * tests (slock-fb 2026-11-07 features/channel/tests/agentLoungeChatPanel.behavior.test.tsx,
 * features/sidebar/tests/agentLoungeSidebar.behavior.test.tsx): the Lounge header names the agent
 * and offers no Join, the audience notice, the read-only composer, the archived banner's Unarchive,
 * and the Home sub-row under the agent's DM row only when it is projected.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class AgentLoungeUiTest {
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
        RaftAgentLounges.clear()
    }

    private val lounge =
        RaftAgentLounge(ChannelId("lounge-bugen"), "agent-bugen", agentName = "Bugen", channelName = "bugen-lounge")

    private fun pageLounge(
        lounge: RaftAgentLounge? = this.lounge,
        canUnarchive: Boolean = false,
        onUnarchive: () -> Unit = {},
        onOpenAgentProfile: (() -> Unit)? = {},
    ) = ThreadPageLounge(
        lounge = lounge,
        agentAvatarWireValue = null,
        cacheServerId = "server-1",
        canUnarchive = canUnarchive,
        unarchiving = false,
        onUnarchive = onUnarchive,
        onOpenAgentProfile = onOpenAgentProfile,
        onOpenDmAgentLounge = null,
        dmLoungeOpening = false,
    )

    private fun ComposeUiTest.showLoungePage(inputs: ThreadPageLounge, joined: Boolean = false) {
        val payload =
            RaftThreadScreenPayload(
                instanceId = "lounge",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                status = "content",
                title = "bugen-lounge",
                streamChannelId = "lounge-bugen",
            ).copy(joined = joined).withAgentLoungeAccess(inputs.lounge)
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    // The page's head, its notice and its footer, as ThreadPageContentContent composes them.
                    ThreadPageHeader(
                        payload = payload,
                        headerDisplay =
                            ThreadHeaderDisplayModel(
                                title = "bugen-lounge",
                                subtitle = "a description",
                                surfaceKind = RaftThreadSurfaceKind.Channel,
                                channelKind = "channel",
                            ),
                        applyTopSafeArea = false,
                        onBack = {},
                        onOpenInChannel = {},
                        onOpenMemberProfile = {},
                        onOpenSearch = {},
                        onShowMembers = {},
                        onShowSettings = {},
                        lounge = inputs,
                    )
                    AgentLoungeAudienceNotice()
                    val conversationLounge = inputs.lounge
                    val kind = agentLoungeFooterKind(conversationLounge, conversationLounge)
                    if (kind != null) {
                        AgentLoungeFooter(kind, inputs)
                    } else if (threadFooterShowsJoin(payload.joined, conversationLounge)) {
                        ThreadJoinFooter(label = "Join channel", onJoin = {})
                    }
                }
            }
            }
        }
    }

    @Test
    fun theLoungeHeaderNamesTheAgentAndThereIsNoJoin() =
        runRobolectricComposeUiTest {
            var profileOpens = 0
            showLoungePage(pageLounge(onOpenAgentProfile = { profileOpens++ }))
            onNodeWithTag(AgentLoungeTestTags.Header).assertExists()
            onNodeWithText("Bugen's Lounge").assertExists()
            onAllNodesWithText("bugen-lounge", substring = true).assertCountEquals(0)
            onAllNodesWithText("a description").assertCountEquals(0)
            onAllNodesWithText("Join", substring = true).assertCountEquals(0)
            // The face carries the Lounge mark, once.
            onAllNodesWithTag(AgentLoungeTestTags.AvatarBadge, useUnmergedTree = true).assertCountEquals(1)
            onNodeWithText(
                "Members of this server can take part. Other agents can read it.",
            ).assertExists()
            onAllNodesWithTag(AgentLoungeTestTags.ReadOnlyComposer).assertCountEquals(0)
        }

    @Test
    fun anOrdinaryUnjoinedChannelKeepsItsJoin() =
        runRobolectricComposeUiTest {
            showLoungePage(pageLounge(lounge = null))
            onNodeWithText("Join channel").assertExists()
            onAllNodesWithTag(AgentLoungeTestTags.Header).assertCountEquals(0)
        }

    @Test
    fun aLoungeYouMayOnlyReadHasTheReadOnlyComposer() =
        runRobolectricComposeUiTest {
            showLoungePage(pageLounge(lounge = lounge.copy(canPost = false)))
            onNodeWithTag(AgentLoungeTestTags.ReadOnlyComposer).assertExists()
            onNodeWithText("Chat is unavailable", useUnmergedTree = true).assertExists()
            onAllNodesWithText("Join", substring = true).assertCountEquals(0)
            onNodeWithTag(AgentLoungeTestTags.AudienceNotice).assertExists()
        }

    @Test
    fun anArchivedLoungeOffersUnarchiveOnlyToWhoMayArchiveChannels() =
        runRobolectricComposeUiTest {
            showLoungePage(pageLounge(lounge = lounge.copy(archived = true), canUnarchive = false))
            onNodeWithText("This channel is archived.").assertExists()
            onAllNodesWithTag(AgentLoungeTestTags.UnarchiveAction).assertCountEquals(0)
        }

    @Test
    fun anArchivedLoungeUnarchivesInPlaceForAnAdmin() =
        runRobolectricComposeUiTest {
            var unarchives = 0
            showLoungePage(pageLounge(lounge = lounge.copy(archived = true), canUnarchive = true, onUnarchive = { unarchives++ }))
            onNodeWithTag(AgentLoungeTestTags.UnarchiveAction).performClick()
            runOnIdle { assertEquals(1, unarchives) }
        }

    private fun ComposeUiTest.showHome(directMessages: List<RaftHomeConversationItem>, onOpen: (String) -> Unit = {}) =
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                Box(Modifier.fillMaxSize()) {
                    RaftStructuralHomeShell(
                        currentServerName = "Raft",
                        currentServerId = "server-1",
                        channels = listOf(RaftHomeConversationItem(id = "general", name = "general", kind = "channel")),
                        directMessages = directMessages,
                        contentRefreshEnabled = false,
                        selectedTabKey = "home",
                        showBottomBar = true,
                        searchAndActivityOnShellNav = true,
                        outerPadding = 0.dp,
                        onChannelClick = onOpen,
                    )
                }
            }
        }

    private fun bugenDm(lounge: RaftHomeAgentLoungeRow?) =
        RaftHomeConversationItem(
            id = "dm-bugen",
            name = "Bugen",
            kind = RaftThreadSurfaceKind.DirectMessage,
            avatarLabel = "BU",
            peerType = "agent",
            presenceMemberId = "agent-bugen",
            agentLounge = lounge,
        )

    private val loungeNamed = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Bugen's Lounge"))

    @Test
    fun theSidebarSubRowShowsUnderTheAgentDmWithItsUnreadAndOpensTheLounge() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<String>()
            showHome(listOf(bugenDm(RaftHomeAgentLoungeRow("lounge-bugen", unreadCount = 3, mentioned = true))), onOpen = { opened += it })
            onNodeWithTag(AgentLoungeTestTags.SidebarRow).assertExists()
            onNode(loungeNamed, useUnmergedTree = true).assertExists()
            onNodeWithText("Lounge").assertExists()
            onAllNodesWithText("bugen-lounge", substring = true).assertCountEquals(0)
            onNodeWithTag(AgentLoungeTestTags.SidebarUnread, useUnmergedTree = true).assertExists()
            onNodeWithTag(AgentLoungeTestTags.SidebarMention, useUnmergedTree = true).assertExists()
            onNodeWithTag(AgentLoungeTestTags.SidebarRow).performClick()
            runOnIdle { assertEquals(listOf("lounge-bugen"), opened) }
        }

    @Test
    fun noSubRowWhenTheDmCarriesNone() =
        runRobolectricComposeUiTest {
            // Flag off, a Guest, no Lounge yet, or neither joined nor mentioned: nothing projected.
            showHome(
                listOf(
                    bugenDm(
                        bugenDmRowOrNull(
                            build.raft.app.data.AgentLoungeSummary(ChannelId("lounge-bugen"), joined = false, unreadCount = 4),
                            enabled = true,
                        )
                    ),
                    bugenDm(null).copy(id = "dm-other", name = "Other", presenceMemberId = "agent-other"),
                )
            )
            onNodeWithText("Bugen").assertExists()
            onAllNodesWithTag(AgentLoungeTestTags.SidebarRow).assertCountEquals(0)
        }

    @Test
    fun theFlagGateDecidesTheSubRow() =
        runRobolectricComposeUiTest {
            val joined = build.raft.app.data.AgentLoungeSummary(ChannelId("lounge-bugen"), joined = true)
            showHome(listOf(bugenDm(bugenDmRowOrNull(joined, enabled = false))))
            onNodeWithText("Bugen").assertExists()
            onAllNodesWithTag(AgentLoungeTestTags.SidebarRow).assertCountEquals(0)
        }

    /** The projection Home applies to the agent's DM row ([agentLoungeSidebarRow]). */
    private fun bugenDmRowOrNull(summary: build.raft.app.data.AgentLoungeSummary, enabled: Boolean): RaftHomeAgentLoungeRow? =
        build.raft.app.data.HomeDirectMessageRow(
            threadId = build.raft.app.model.ThreadId("dm-bugen"),
            channelId = ChannelId("dm-bugen"),
            name = "Bugen",
            directMessagePeerType = "agent",
            presenceMemberId = build.raft.app.model.MemberId("agent-bugen"),
            lounge = summary,
        ).agentLoungeSidebarRow(enabled, "server-1")
}
