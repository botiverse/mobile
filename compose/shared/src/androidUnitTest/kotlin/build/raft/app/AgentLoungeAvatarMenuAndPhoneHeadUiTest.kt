package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.RaftConversationHeaderAction
import build.raft.app.data.RaftConversationHeaderActionSelectedProperties
import build.raft.app.data.RaftConversationHeaderKind
import build.raft.app.data.RaftConversationHeaderSurface
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RaftProductEvent
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end Robolectric Compose UI verification for:
 * 1. Agent Lounge B1 avatar switcher menu (`botiverse/slock#16450`, `#proj-chat:#39`):
 *    Lounge / Private chat radio items with checkmarks, audience descriptions, opposite-side
 *    unread count/dot, in-place switching, and View profile.
 * 2. Phone conversation header fold (`botiverse/slock#16464`, `#proj-chat:#45`):
 *    Channel, human DM, Agent DM (with Call), and Agent Lounge show only Search + `⋯` on a 390dp
 *    phone, with Call (when present), Tasks, Files, and Details & settings folded into `⋯` in
 *    exact order; wide layout (1024dp) stays unfolded.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class AgentLoungeAvatarMenuAndPhoneHeadUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType
    private var previousObserver: ((RaftProductEvent<*>) -> Unit)? = null
    private val recorded = mutableListOf<RaftProductEvent<*>>()

    @BeforeTest
    fun setup() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        recorded.clear()
        previousObserver = RaftProductEvents.testEntryEventObserver
        RaftProductEvents.testEntryEventObserver = { recorded += it }
    }

    @AfterTest
    fun tearDown() {
        RaftProductEvents.testEntryEventObserver = previousObserver
        StringDesc.localeType = previousLocale
    }

    private object QuietEventBus : RaftEventBus {
        override fun observe(eventName: String, crossProcess: Boolean, listener: (JsonObject?) -> Unit) = RealtimeSubscription {}

        override fun post(eventName: String, eventData: JsonObject, crossProcess: Boolean, source: String) = Unit
    }

    private fun headerEvents(): List<RaftConversationHeaderActionSelectedProperties> =
        recorded.filterIsInstance<RaftProductEvent.ConversationHeaderActionSelected>().map { it.properties }

    @Test
    fun loungeAvatarMenuShowsRadioCheckmarksUnreadAndViewProfileAndSwitchesInPlace() =
        runRobolectricComposeUiTest {
            var switchedToPrivate = 0
            var viewedProfile = 0
            val loungeRecord =
                RaftAgentLounge(
                    channelId = ChannelId("lounge-tenny"),
                    agentId = "agent-tenny",
                    agentName = "Tenny",
                    channelName = "tenny-lounge",
                )
            val loungePayload =
                RaftThreadScreenPayload(
                    instanceId = "lounge-1",
                    title = "tenny-lounge",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    sourceThreadId = "lounge-tenny",
                    streamChannelId = "lounge-tenny",
                    showModeTabs = true,
                    showSettingsAction = true,
                )
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                val switch =
                    AgentLoungeConversationSwitch(
                        activeSide = AgentLoungeConversationSide.Lounge,
                        privateUnread = AgentLoungeSwitchUnread(count = 3),
                        onSelectLounge = {},
                        onSelectPrivate = { switchedToPrivate++ },
                    )
                val lounge =
                    ThreadPageLounge(
                        lounge = loungeRecord,
                        agentAvatarWireValue = null,
                        cacheServerId = null,
                        canUnarchive = false,
                        unarchiving = false,
                        onUnarchive = {},
                        onOpenAgentProfile = { viewedProfile++ },
                        conversationSwitch = switch,
                    )
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalThreadHeaderMenuEventBus provides QuietEventBus,
                ) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize()) {
                            ThreadPageHeader(
                                payload = loungePayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = "tenny-lounge",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.Channel,
                                        channelKind = "public",
                                    ),
                                applyTopSafeArea = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = {},
                                sideCardToggles = ThreadHeaderSideCardToggles(tasksAvailable = true, cards = ConversationSideCardsState(), onToggle = {}),
                                lounge = lounge,
                            )
                        }
                    }
                }
            }

            onNodeWithText("Tenny's Lounge").assertIsDisplayed()
            onNodeWithText("Members and agents of this server can take part.").assertIsDisplayed()
            onNodeWithTag(AgentLoungeTestTags.ConversationAvatarMark, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(AgentLoungeTestTags.ConversationAvatarDot, useUnmergedTree = true).assertIsDisplayed()

            // Open the avatar switcher menu.
            onNodeWithTag(AgentLoungeTestTags.ConversationMenuTrigger).performClick()
            waitForIdle()

            onNodeWithTag(AgentLoungeTestTags.ConversationMenuLounge).assertIsSelected()
            onNodeWithTag(AgentLoungeTestTags.ConversationMenuPrivate).assertIsNotSelected()
            onNodeWithText("Private chat").assertIsDisplayed()
            onNodeWithText("Only you and Tenny can see this.").assertIsDisplayed()
            onNodeWithTag(AgentLoungeTestTags.ConversationMenuUnread, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("View profile").assertIsDisplayed()

            // Switch to Private chat in place.
            onNodeWithTag(AgentLoungeTestTags.ConversationMenuPrivate).performClick()
            waitForIdle()
            assertEquals(1, switchedToPrivate)

            // Reopen and tap View profile.
            onNodeWithTag(AgentLoungeTestTags.ConversationMenuTrigger).performClick()
            waitForIdle()
            onNodeWithTag(AgentLoungeTestTags.ConversationMenuProfile).performClick()
            waitForIdle()
            assertEquals(1, viewedProfile)
            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenProfile,
                        surface = RaftConversationHeaderSurface.Title,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    )
                ),
                headerEvents(),
            )
        }

    @Test
    fun phoneChannelHeadShowsOnlySearchAndMoreWithTasksFilesAndChannelDetailsInOrder() =
        runRobolectricComposeUiTest {
            val toggledCards = mutableListOf<ConversationSideCard>()
            var detailsOpened = 0
            val channelPayload =
                RaftThreadScreenPayload(
                    instanceId = "ch-1",
                    title = "proj-workspace",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    sourceThreadId = "ch-1",
                    streamChannelId = "ch-1",
                    showModeTabs = true,
                    showSettingsAction = true,
                )
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalThreadHeaderMenuEventBus provides QuietEventBus,
                ) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize()) {
                            ThreadPageHeader(
                                payload = channelPayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = "proj-workspace",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.Channel,
                                        channelKind = "public",
                                    ),
                                applyTopSafeArea = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = { detailsOpened++ },
                                sideCardToggles =
                                    ThreadHeaderSideCardToggles(
                                        tasksAvailable = true,
                                        cards = ConversationSideCardsState(),
                                        onToggle = { toggledCards += it },
                                    ),
                            )
                        }
                    }
                }
            }

            // Only Search and ⋯ are visible on the phone head; Tasks/Files/Settings are folded.
            onNodeWithContentDescription("Search this channel").assertIsDisplayed()
            onNodeWithContentDescription("Thread panel menu").assertIsDisplayed()
            onNodeWithContentDescription("Open Tasks").assertDoesNotExist()
            onNodeWithContentDescription("Open files").assertDoesNotExist()
            onNodeWithContentDescription("Channel settings").assertDoesNotExist()

            // Open ⋯ and verify order: Tasks -> Files -> Channel settings.
            onNodeWithContentDescription("Thread panel menu").performClick()
            waitForIdle()
            val tasksTop = onNodeWithText("Open Tasks").fetchSemanticsNode().boundsInRoot.top
            val filesTop = onNodeWithText("Open files").fetchSemanticsNode().boundsInRoot.top
            val detailsTop = onNodeWithText("Channel settings").fetchSemanticsNode().boundsInRoot.top
            assert(tasksTop < filesTop && filesTop < detailsTop) {
                "expected Open Tasks ($tasksTop) < Open files ($filesTop) < Channel settings ($detailsTop)"
            }
            val detailsLayouts = mutableListOf<TextLayoutResult>()
            onNodeWithText("Channel settings", useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action
                ?.invoke(detailsLayouts)
            assertEquals(1, detailsLayouts.single().lineCount, "Channel settings must stay on a single line")
            onNodeWithText("Channel settings").performClick()
            waitForIdle()
            assertEquals(1, detailsOpened)
            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenMenu,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenDetails,
                        surface = RaftConversationHeaderSurface.MoreMenu,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                ),
                headerEvents(),
            )
        }

    @Test
    fun phoneAgentDmWithCallFoldsCallFirstBeforeTasksFilesAndDetailsAndSettings() =
        runRobolectricComposeUiTest {
            var callsStarted = 0
            val dmPayload =
                RaftThreadScreenPayload(
                    instanceId = "dm-tenny",
                    title = "Tenny",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    sourceThreadId = "dm-tenny",
                    streamChannelId = "dm-tenny",
                    showModeTabs = true,
                    showSettingsAction = true,
                )
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                val switch =
                    AgentLoungeConversationSwitch(
                        activeSide = AgentLoungeConversationSide.Private,
                        loungeUnread = AgentLoungeSwitchUnread(count = 2),
                        onSelectLounge = {},
                        onSelectPrivate = {},
                    )
                val lounge =
                    ThreadPageLounge(
                        lounge = null,
                        agentAvatarWireValue = null,
                        cacheServerId = null,
                        canUnarchive = false,
                        unarchiving = false,
                        onUnarchive = {},
                        onOpenAgentProfile = {},
                        conversationSwitch = switch,
                    )
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalThreadHeaderMenuEventBus provides QuietEventBus,
                ) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize()) {
                            ThreadPageHeader(
                                payload = dmPayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = "Tenny",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                                        channelKind = "dm",
                                        dmPeerIdentity = MemberDetailIdentity(MemberId("agent-tenny"), MemberKind.Agent),
                                    ),
                                applyTopSafeArea = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = {},
                                sideCardToggles = ThreadHeaderSideCardToggles(tasksAvailable = true, cards = ConversationSideCardsState(), onToggle = {}),
                                lounge = lounge,
                                onStartCall = { callsStarted++ },
                            )
                        }
                    }
                }
            }

            onNodeWithTag(AgentLoungeTestTags.ConversationMenuTrigger).assertIsDisplayed()
            onNodeWithContentDescription("Search this conversation").assertIsDisplayed()
            onNodeWithContentDescription("Thread panel menu").assertIsDisplayed()
            onNodeWithContentDescription("Call").assertDoesNotExist()
            onNodeWithContentDescription("Open Tasks").assertDoesNotExist()
            onNodeWithContentDescription("Open files").assertDoesNotExist()
            onNodeWithContentDescription("Details and settings").assertDoesNotExist()

            // Open ⋯ and verify exact order: Call -> Tasks -> Files -> Details and settings.
            onNodeWithContentDescription("Thread panel menu").performClick()
            waitForIdle()
            val callTop = onNodeWithText("Call").fetchSemanticsNode().boundsInRoot.top
            val tasksTop = onNodeWithText("Open Tasks").fetchSemanticsNode().boundsInRoot.top
            val filesTop = onNodeWithText("Open files").fetchSemanticsNode().boundsInRoot.top
            val detailsTop = onNodeWithText("Details and settings").fetchSemanticsNode().boundsInRoot.top
            assert(callTop < tasksTop && tasksTop < filesTop && filesTop < detailsTop) {
                "expected Call ($callTop) < Open Tasks ($tasksTop) < Open files ($filesTop) < Details and settings ($detailsTop)"
            }
            onNodeWithText("Call").performClick()
            waitForIdle()
            assertEquals(1, callsStarted)
            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenMenu,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Dm,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.StartCall,
                        surface = RaftConversationHeaderSurface.MoreMenu,
                        conversation = RaftConversationHeaderKind.Dm,
                        compact = true,
                    ),
                ),
                headerEvents(),
            )
        }

    @Test
    @Config(sdk = [34], qualifiers = "w1024dp-h768dp")
    fun wideConversationHeadKeepsAllOwnActionsUnfolded() =
        runRobolectricComposeUiTest {
            val dmPayload =
                RaftThreadScreenPayload(
                    instanceId = "dm-wide",
                    title = "Tenny",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    sourceThreadId = "dm-wide",
                    streamChannelId = "dm-wide",
                    showModeTabs = true,
                    showSettingsAction = true,
                )
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalThreadHeaderMenuEventBus provides QuietEventBus,
                ) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize()) {
                            ThreadPageHeader(
                                payload = dmPayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = "Tenny",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                                        channelKind = "dm",
                                        dmPeerIdentity = MemberDetailIdentity(MemberId("agent-tenny"), MemberKind.Agent),
                                    ),
                                applyTopSafeArea = false,
                                showBack = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = {},
                                sideCardToggles = ThreadHeaderSideCardToggles(tasksAvailable = true, cards = ConversationSideCardsState(), onToggle = {}),
                                onStartCall = {},
                            )
                        }
                    }
                }
            }

            onNodeWithContentDescription("Search this conversation").assertIsDisplayed()
            onNodeWithContentDescription("Call").assertIsDisplayed()
            onNodeWithContentDescription("Open Tasks").assertIsDisplayed()
            onNodeWithContentDescription("Open files").assertIsDisplayed()
            onNodeWithContentDescription("Details and settings").assertIsDisplayed()
            onNodeWithContentDescription("Thread panel menu").assertDoesNotExist()
        }

    @Test
    fun phoneChannelHeadPromotesTasksToggleAndFoldsSearchIntoMoreWithAnalytics() =
        runRobolectricComposeUiTest {
            var searches = 0
            val toggledCards = mutableListOf<ConversationSideCard>()
            val channelPayload =
                RaftThreadScreenPayload(
                    instanceId = "ch-tasks-primary",
                    title = "proj-mobile",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    sourceThreadId = "ch-tasks-primary",
                    streamChannelId = "ch-tasks-primary",
                    showModeTabs = true,
                    showSettingsAction = true,
                )
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                var cards by remember { mutableStateOf(ConversationSideCardsState()) }
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalThreadHeaderMenuEventBus provides QuietEventBus,
                ) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize()) {
                            ThreadPageHeader(
                                payload = channelPayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = "proj-mobile",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.Channel,
                                        channelKind = "public",
                                    ),
                                applyTopSafeArea = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = { searches++ },
                                onShowMembers = {},
                                onShowSettings = {},
                                sideCardToggles =
                                    ThreadHeaderSideCardToggles(
                                        tasksAvailable = true,
                                        cards = cards,
                                        onToggle = { card ->
                                            toggledCards += card
                                            val wasOpen = cards.isOpen(card)
                                            cards =
                                                when (card) {
                                                    ConversationSideCard.Tasks -> cards.copy(tasks = !wasOpen, focus = card.takeIf { !wasOpen })
                                                    ConversationSideCard.Files -> cards.copy(files = !wasOpen, focus = card.takeIf { !wasOpen })
                                                }
                                        },
                                    ),
                                phoneHeaderPrimaryConfig = parsePhoneConversationHeaderPrimaryConfig("tasks,search"),
                            )
                        }
                    }
                }
            }

            // Tasks is promoted to the standalone header toggle; Search is folded into ⋯.
            onNodeWithContentDescription("Open Tasks").assertIsDisplayed().assertIsOff()
            onNodeWithContentDescription("Thread panel menu").assertIsDisplayed()
            onNodeWithContentDescription("Search this channel").assertDoesNotExist()
            onNodeWithContentDescription("Open files").assertDoesNotExist()
            onNodeWithContentDescription("Channel settings").assertDoesNotExist()

            // Tapping promoted Tasks activates its toggle state and does not double-emit conversation_header_action_selected.
            onNodeWithContentDescription("Open Tasks").performClick()
            waitForIdle()
            onNodeWithContentDescription("Close Tasks").assertIsDisplayed().assertIsOn()
            assertEquals(listOf(ConversationSideCard.Tasks), toggledCards)
            assertEquals(emptyList(), headerEvents())

            // Open ⋯: Search this channel -> Open files -> Channel settings in canonical order.
            onNodeWithContentDescription("Thread panel menu").performClick()
            waitForIdle()
            val searchTop = onNodeWithText("Search this channel").fetchSemanticsNode().boundsInRoot.top
            val filesTop = onNodeWithText("Open files").fetchSemanticsNode().boundsInRoot.top
            val detailsTop = onNodeWithText("Channel settings").fetchSemanticsNode().boundsInRoot.top
            assert(searchTop < filesTop && filesTop < detailsTop) {
                "expected Search this channel ($searchTop) < Open files ($filesTop) < Channel settings ($detailsTop)"
            }
            onNodeWithText("Search this channel").performClick()
            waitForIdle()
            assertEquals(1, searches)
            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenMenu,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.Search,
                        surface = RaftConversationHeaderSurface.MoreMenu,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                ),
                headerEvents(),
            )
        }

    @Test
    fun phoneDmWithoutTasksFallsBackToSearchWhenTasksSearchConfigured() =
        runRobolectricComposeUiTest {
            val dmPayload =
                RaftThreadScreenPayload(
                    instanceId = "dm-no-tasks",
                    title = "Alice",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    sourceThreadId = "dm-no-tasks",
                    streamChannelId = "dm-no-tasks",
                    showModeTabs = true,
                    showSettingsAction = true,
                )
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalThreadHeaderMenuEventBus provides QuietEventBus,
                ) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize()) {
                            ThreadPageHeader(
                                payload = dmPayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = "Alice",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                                        channelKind = "dm",
                                    ),
                                applyTopSafeArea = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = {},
                                sideCardToggles = ThreadHeaderSideCardToggles(tasksAvailable = false, cards = ConversationSideCardsState(), onToggle = {}),
                                phoneHeaderPrimaryConfig = parsePhoneConversationHeaderPrimaryConfig("tasks,search"),
                            )
                        }
                    }
                }
            }

            onNodeWithContentDescription("Search this conversation").assertIsDisplayed()
            onNodeWithContentDescription("Thread panel menu").assertIsDisplayed()
            onNodeWithContentDescription("Open Tasks").assertDoesNotExist()
            onNodeWithContentDescription("Open files").assertDoesNotExist()
            onNodeWithContentDescription("Details and settings").assertDoesNotExist()
        }

    @Test
    fun phoneChannelHeadPromotesDetailsToggleWithHeaderSurfaceAnalytics() =
        runRobolectricComposeUiTest {
            val channelPayload =
                RaftThreadScreenPayload(
                    instanceId = "ch-details-primary",
                    title = "engineering",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    sourceThreadId = "ch-details-primary",
                    streamChannelId = "ch-details-primary",
                    showModeTabs = true,
                    showSettingsAction = true,
                )
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                var detailsOpen by remember { mutableStateOf(false) }
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalThreadHeaderMenuEventBus provides QuietEventBus,
                ) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize()) {
                            ThreadPageHeader(
                                payload = channelPayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = "engineering",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.Channel,
                                        channelKind = "public",
                                    ),
                                applyTopSafeArea = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = { detailsOpen = !detailsOpen },
                                settingsOpen = detailsOpen,
                                sideCardToggles = ThreadHeaderSideCardToggles(tasksAvailable = true, cards = ConversationSideCardsState(), onToggle = {}),
                                phoneHeaderPrimaryConfig = parsePhoneConversationHeaderPrimaryConfig("details"),
                            )
                        }
                    }
                }
            }

            onNodeWithContentDescription("Channel settings").assertIsDisplayed().assertIsOff()
            onNodeWithContentDescription("Search this channel").assertDoesNotExist()
            onNodeWithContentDescription("Channel settings").performClick()
            waitForIdle()
            onNodeWithContentDescription("Close channel settings").assertIsDisplayed().assertIsOn()
            onNodeWithContentDescription("Close channel settings").performClick()
            waitForIdle()
            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenDetails,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.CloseDetails,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                ),
                headerEvents(),
            )
        }

    @Test
    fun phoneChannelHeadWithNoneVariantFoldsAllActionsIntoMoreMenu() =
        runRobolectricComposeUiTest {
            val channelPayload =
                RaftThreadScreenPayload(
                    instanceId = "ch-none-primary",
                    title = "announcements",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    sourceThreadId = "ch-none-primary",
                    streamChannelId = "ch-none-primary",
                    showModeTabs = true,
                    showSettingsAction = true,
                )
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalThreadHeaderMenuEventBus provides QuietEventBus,
                ) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize()) {
                            ThreadPageHeader(
                                payload = channelPayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = "announcements",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.Channel,
                                        channelKind = "public",
                                    ),
                                applyTopSafeArea = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = {},
                                sideCardToggles = ThreadHeaderSideCardToggles(tasksAvailable = true, cards = ConversationSideCardsState(), onToggle = {}),
                                phoneHeaderPrimaryConfig = parsePhoneConversationHeaderPrimaryConfig("none"),
                            )
                        }
                    }
                }
            }

            onNodeWithContentDescription("Thread panel menu").assertIsDisplayed()
            onNodeWithContentDescription("Search this channel").assertDoesNotExist()
            onNodeWithContentDescription("Open Tasks").assertDoesNotExist()
            onNodeWithContentDescription("Open files").assertDoesNotExist()
            onNodeWithContentDescription("Channel settings").assertDoesNotExist()

            onNodeWithContentDescription("Thread panel menu").performClick()
            waitForIdle()
            val searchTop = onNodeWithText("Search this channel").fetchSemanticsNode().boundsInRoot.top
            val tasksTop = onNodeWithText("Open Tasks").fetchSemanticsNode().boundsInRoot.top
            val filesTop = onNodeWithText("Open files").fetchSemanticsNode().boundsInRoot.top
            val detailsTop = onNodeWithText("Channel settings").fetchSemanticsNode().boundsInRoot.top
            assert(searchTop < tasksTop && tasksTop < filesTop && filesTop < detailsTop) {
                "expected Search ($searchTop) < Tasks ($tasksTop) < Files ($filesTop) < Details ($detailsTop)"
            }
        }

    @Test
    @Config(sdk = [34], qualifiers = "w1024dp-h768dp")
    fun wideConversationHeadIgnoresPhoneRemoteConfigAndStaysUnfolded() =
        runRobolectricComposeUiTest {
            val channelPayload =
                RaftThreadScreenPayload(
                    instanceId = "ch-wide-rc",
                    title = "engineering",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    sourceThreadId = "ch-wide-rc",
                    streamChannelId = "ch-wide-rc",
                    showModeTabs = true,
                    showSettingsAction = true,
                )
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalThreadHeaderMenuEventBus provides QuietEventBus,
                ) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize()) {
                            ThreadPageHeader(
                                payload = channelPayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = "engineering",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.Channel,
                                        channelKind = "public",
                                    ),
                                applyTopSafeArea = false,
                                showBack = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = {},
                                sideCardToggles = ThreadHeaderSideCardToggles(tasksAvailable = true, cards = ConversationSideCardsState(), onToggle = {}),
                                phoneHeaderPrimaryConfig = parsePhoneConversationHeaderPrimaryConfig("none"),
                            )
                        }
                    }
                }
            }

            onNodeWithContentDescription("Search this channel").assertIsDisplayed()
            onNodeWithContentDescription("Open Tasks").assertIsDisplayed()
            onNodeWithContentDescription("Open files").assertIsDisplayed()
            onNodeWithContentDescription("Channel settings").assertIsDisplayed()
            onNodeWithContentDescription("Thread panel menu").assertDoesNotExist()
        }
}
