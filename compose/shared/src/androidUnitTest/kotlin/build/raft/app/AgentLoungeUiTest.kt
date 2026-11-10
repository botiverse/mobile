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
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

    private fun ComposeUiTest.showHome(
        directMessages: List<RaftHomeConversationItem>,
        agentLounges: List<RaftHomeConversationItem> = emptyList(),
        pinnedItems: List<build.raft.app.data.SettingsServerSidebarPinnedItem> = emptyList(),
        hiddenDmIds: List<String> = emptyList(),
        onOpen: (String) -> Unit = {},
        onPinToggle: (String) -> Unit = {},
        onCloseDirectMessage: (String) -> Unit = {},
        onMuteToggle: (String, Boolean) -> Unit = { _, _ -> },
    ) =
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
                        agentLounges = agentLounges,
                        pinnedItems = pinnedItems,
                        hiddenDmIds = hiddenDmIds,
                        contentRefreshEnabled = false,
                        selectedTabKey = "home",
                        showBottomBar = true,
                        searchAndActivityOnShellNav = true,
                        outerPadding = 0.dp,
                        onChannelClick = onOpen,
                        onPinToggle = onPinToggle,
                        onCloseDirectMessage = onCloseDirectMessage,
                        onChannelActivityMuteToggle = onMuteToggle,
                    )
                }
            }
        }

    private val bugenDm =
        RaftHomeConversationItem(
            id = "dm-bugen",
            name = "Bugen",
            kind = RaftThreadSurfaceKind.DirectMessage,
            avatarLabel = "BU",
            peerType = "agent",
            presenceMemberId = "agent-bugen",
        )

    /** Home's projection of Bugen's Lounge ([homeSidebarAgentLoungeItems]), joined, its row loaded. */
    private fun bugenLounge(
        summary: build.raft.app.data.AgentLoungeSummary =
            build.raft.app.data.AgentLoungeSummary(ChannelId("lounge-bugen"), joined = true, unreadCount = 3, mentionCount = 1),
        muted: Boolean = false,
    ): RaftHomeConversationItem {
        RaftAgentLounges.rememberRow(
            "server-1",
            build.raft.app.data.HomeChannelRow(
                threadId = build.raft.app.model.ThreadId("lounge-bugen"),
                channelId = ChannelId("lounge-bugen"),
                name = "bugen-lounge",
                joined = summary.joined,
                activityNotificationPreference =
                    build.raft.app.model.ChannelActivityNotificationPreference(activityMuted = muted, activityMuteSupported = true),
                loungeAgentId = "agent-bugen",
            ),
        )
        return homeSidebarAgentLoungeItems(
            enabled = true,
            directMessages =
                listOf(
                    build.raft.app.data.HomeDirectMessageRow(
                        threadId = build.raft.app.model.ThreadId("dm-bugen"),
                        channelId = ChannelId("dm-bugen"),
                        name = "Bugen",
                        directMessagePeerType = "agent",
                        presenceMemberId = build.raft.app.model.MemberId("agent-bugen"),
                        lounge = summary,
                    )
                ),
            known = RaftAgentLounges.lounges.value["server-1"].orEmpty(),
            markedUnreadIds = emptySet(),
            title = { "$it's Lounge" },
        ).single()
    }

    @Test
    fun theLoungeHasItsOwnRowInDirectMessagesWithItsUnreadAndOpensTheLounge() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<String>()
            showHome(listOf(bugenDm), agentLounges = listOf(bugenLounge()), onOpen = { opened += it })
            onNodeWithText("Bugen").assertExists()
            onNodeWithTag(AgentLoungeTestTags.SidebarRow).assertExists()
            onNodeWithText("Bugen's Lounge").assertExists()
            onAllNodesWithText("bugen-lounge", substring = true).assertCountEquals(0)
            onNodeWithTag(AgentLoungeTestTags.SidebarUnread, useUnmergedTree = true).assertExists()
            onNodeWithTag(AgentLoungeTestTags.SidebarMention, useUnmergedTree = true).assertExists()
            onNodeWithTag(AgentLoungeTestTags.SidebarRow).performClick()
            runOnIdle { assertEquals(listOf("lounge-bugen"), opened) }
        }

    @Test
    fun theLoungeRowStaysWhenItsAgentsDmIsClosed() =
        runRobolectricComposeUiTest {
            showHome(listOf(bugenDm), agentLounges = listOf(bugenLounge()), hiddenDmIds = listOf("dm-bugen"))
            onAllNodesWithText("Bugen").assertCountEquals(0)
            onNodeWithTag(AgentLoungeTestTags.SidebarRow).assertExists()
        }

    @Test
    fun aPinnedLoungeShowsOnlyUnderPinned() =
        runRobolectricComposeUiTest {
            showHome(
                listOf(bugenDm),
                agentLounges = listOf(bugenLounge()),
                pinnedItems = listOf(build.raft.app.data.SettingsServerSidebarPinnedItem(kind = "channel", id = "lounge-bugen")),
            )
            onAllNodesWithTag(AgentLoungeTestTags.SidebarRow).assertCountEquals(1)
            onNodeWithText("Bugen's Lounge").assertExists()
        }

    @Test
    fun theLoungeRowsMenuIsADmRowsMenu() =
        runRobolectricComposeUiTest {
            val pinned = mutableListOf<String>()
            val closed = mutableListOf<String>()
            val muted = mutableListOf<Pair<String, Boolean>>()
            showHome(
                listOf(bugenDm),
                agentLounges = listOf(bugenLounge()),
                onPinToggle = { pinned += it },
                onCloseDirectMessage = { closed += it },
                onMuteToggle = { id, mute -> muted += id to mute },
            )
            onNodeWithTag(AgentLoungeTestTags.SidebarRow).performTouchInput { longClick() }
            onNodeWithText("Mark as Read", ignoreCase = true).assertExists()
            onNodeWithText("Mute", ignoreCase = true).assertExists()
            onAllNodesWithText("Move to Section", ignoreCase = true).assertCountEquals(0)
            onNodeWithText("Pin", ignoreCase = true).performClick()
            onNodeWithTag(AgentLoungeTestTags.SidebarRow).performTouchInput { longClick() }
            onNodeWithText("Mute", ignoreCase = true).performClick()
            onNodeWithTag(AgentLoungeTestTags.SidebarRow).performTouchInput { longClick() }
            onNodeWithText("Close Chat", ignoreCase = true).performClick()
            runOnIdle {
                assertEquals(listOf("lounge-bugen"), pinned)
                assertEquals(listOf("lounge-bugen" to true), muted)
                assertEquals(listOf("lounge-bugen"), closed)
            }
        }

    @Test
    fun noLoungeRowWhenNoneIsListed() =
        runRobolectricComposeUiTest {
            // Flag off, a Guest, no Lounge yet, or neither joined nor mentioned: nothing projected.
            showHome(listOf(bugenDm))
            onNodeWithText("Bugen").assertExists()
            onAllNodesWithTag(AgentLoungeTestTags.SidebarRow).assertCountEquals(0)
        }
}
