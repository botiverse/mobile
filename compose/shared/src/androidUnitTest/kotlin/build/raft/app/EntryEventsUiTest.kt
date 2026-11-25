package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftConversationHeaderAction
import build.raft.app.data.RaftConversationHeaderActionSelectedProperties
import build.raft.app.data.RaftConversationHeaderKind
import build.raft.app.data.RaftConversationHeaderSurface
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RaftProductEvent
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.ThreadAgentFollower
import build.raft.app.data.ThreadAgentFollowerRoster
import build.raft.app.data.ThreadAgentFollowersIntent
import build.raft.app.data.ThreadAgentFollowersState
import build.raft.app.data.ThreadFollowersError
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.app.resources.StringDesc
import build.raft.designsystem.components.LocalRaftCopyHandler
import build.raft.designsystem.components.RaftCopyHandler
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
 * End-to-end Compose UI verification for `#proj-mobile:#260` (`conversation_header_action_selected`,
 * including channel / DM / reply-thread headers, announcement strip menu open, and followers popover,
 * and verifying task conversations emit nothing).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class EntryEventsUiTest {
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
    fun channelHeaderTitleTapToggleAndLongPressCopyEmitExpectedEvents() =
        runRobolectricComposeUiTest {
            val copied = mutableListOf<String>()
            val channelPayload =
                RaftThreadScreenPayload(
                    instanceId = "ch-1",
                    title = "engineering",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    sourceThreadId = "ch-1",
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
                    LocalRaftCopyHandler provides RaftCopyHandler { copied += it },
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
                                onTitleClick = { detailsOpen = !detailsOpen },
                            )
                        }
                    }
                }
            }

            // First tap opens details; second tap while open records close_details on title.
            onNodeWithText("engineering").performClick()
            waitForIdle()
            onNodeWithText("engineering").performClick()
            waitForIdle()
            // Long press copies name and records copy_name on title.
            onNodeWithText("engineering").performTouchInput { longClick() }
            waitForIdle()
            // Phone Back stays unrecorded.
            onNodeWithContentDescription("Back").performClick()
            waitForIdle()

            assertEquals(listOf("engineering"), copied)
            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenDetails,
                        surface = RaftConversationHeaderSurface.Title,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.CloseDetails,
                        surface = RaftConversationHeaderSurface.Title,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.CopyName,
                        surface = RaftConversationHeaderSurface.Title,
                        conversation = RaftConversationHeaderKind.Channel,
                        compact = true,
                    ),
                ),
                headerEvents(),
            )
        }

    @Test
    fun agentDmAvatarMenuSwitchesToLoungeAndShowsPrivateAudienceSubtitle() =
        runRobolectricComposeUiTest {
            var openedLounge = 0
            val dmPayload =
                RaftThreadScreenPayload(
                    instanceId = "dm-1",
                    title = "Lagoon",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    sourceThreadId = "dm-1",
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
                        loungeUnread = AgentLoungeSwitchUnread(count = 3),
                        onSelectLounge = {
                            RaftProductEvents.conversationHeaderActionSelected(
                                action = RaftConversationHeaderAction.OpenLounge,
                                surface = RaftConversationHeaderSurface.Header,
                                conversation = RaftConversationHeaderKind.Dm,
                                compact = true,
                            )
                            openedLounge++
                        },
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
                        onOpenAgentProfile = null,
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
                                        title = "Lagoon",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                                        channelKind = "dm",
                                        dmPeerIdentity = MemberDetailIdentity(MemberId("ag-1"), MemberKind.Agent),
                                    ),
                                applyTopSafeArea = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = {},
                                lounge = lounge,
                            )
                        }
                    }
                }
            }

            onNodeWithText("Only you and Lagoon can see this.").fetchSemanticsNode()
            onNodeWithTag(AgentLoungeTestTags.ConversationMenuTrigger).performClick()
            waitForIdle()
            onNodeWithTag(AgentLoungeTestTags.ConversationMenuLounge).performClick()
            waitForIdle()

            assertEquals(1, openedLounge)
            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenLounge,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Dm,
                        compact = true,
                    )
                ),
                headerEvents(),
            )
        }

    @Test
    fun replyThreadHeaderAndMoreMenuEmitExpectedEventsWhileTaskHeaderEmitsNone() =
        runRobolectricComposeUiTest {
            val threadPayload =
                RaftThreadScreenPayload(
                    instanceId = "reply-1",
                    title = ThreadReplyPageTitle,
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                    parentChannelId = "ch-1",
                    parentMessageId = "msg-1",
                    sourceThreadId = "th-1",
                    streamChannelId = "th-1",
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
                                payload = threadPayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = ThreadReplyPageTitle,
                                        subtitle = "#engineering",
                                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                                        channelKind = "public",
                                    ),
                                applyTopSafeArea = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = {},
                                onTitleClick = {},
                                threadFollowed = true,
                                onToggleThreadFollow = {},
                                onMarkThreadUnread = {},
                                onOpenTimeline = {},
                            )
                        }
                    }
                }
            }

            onNodeWithText("Thread").performClick()
            waitForIdle()
            onNodeWithText("#engineering").performClick()
            waitForIdle()
            onNodeWithContentDescription("Thread panel menu").performClick()
            waitForIdle()
            onNodeWithText("Unfollow thread").performClick()
            waitForIdle()

            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.ScrollToStart,
                        surface = RaftConversationHeaderSurface.Title,
                        conversation = RaftConversationHeaderKind.Thread,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.ViewInChannel,
                        surface = RaftConversationHeaderSurface.Title,
                        conversation = RaftConversationHeaderKind.Thread,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenMenu,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Thread,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.Unfollow,
                        surface = RaftConversationHeaderSurface.MoreMenu,
                        conversation = RaftConversationHeaderKind.Thread,
                        compact = true,
                    ),
                ),
                headerEvents(),
            )
        }

    @Test
    fun taskConversationHeaderEmitsNoHeaderEvents() =
        runRobolectricComposeUiTest {
            val taskPayload =
                RaftThreadScreenPayload(
                    instanceId = "task-24",
                    title = "Task #24",
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = "task",
                    sourceThreadId = "task-ch-24",
                    streamChannelId = "task-ch-24",
                )
            val taskChrome =
                TaskConversationHeaderChrome(
                    ref = "#engineering:#24",
                    taskNumber = 24,
                    status = TaskStatus.InProgress,
                    assigneeName = null,
                    summarized = false,
                    followed = true,
                    onToggleHeadFloat = {},
                    onViewInChannel = {},
                    onToggleFollow = {},
                    onDeleteTask = {},
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
                                payload = taskPayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = "Task #24",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.Channel,
                                        channelKind = "task",
                                    ),
                                applyTopSafeArea = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = {},
                                threadFollowed = true,
                                onToggleThreadFollow = {},
                                taskConversationChrome = taskChrome,
                            )
                        }
                    }
                }
            }

            onNodeWithContentDescription("Thread panel menu").performClick()
            waitForIdle()
            onNodeWithText("Unfollow task").performClick()
            waitForIdle()

            assertEquals(emptyList(), headerEvents())
        }

    @Test
    @Config(sdk = [34], qualifiers = "w1024dp-h768dp")
    fun wideReplyThreadHeaderEmitsExpandRestoreAndSidePaneCloseWithCompactFalse() =
        runRobolectricComposeUiTest {
            var closed = 0
            val threadPayload =
                RaftThreadScreenPayload(
                    instanceId = "reply-wide-1",
                    title = ThreadReplyPageTitle,
                    status = "content",
                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                    parentChannelId = "ch-1",
                    parentMessageId = "msg-1",
                    sourceThreadId = "th-1",
                    streamChannelId = "th-1",
                )
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                var focused by remember { mutableStateOf(false) }
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalThreadHeaderMenuEventBus provides QuietEventBus,
                    LocalRaftSidePanePage provides RaftSidePanePage(depth = 0, close = { closed++ }),
                ) {
                    RaftDesignSystemTheme {
                        Column(Modifier.fillMaxSize()) {
                            ThreadPageHeader(
                                payload = threadPayload,
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = ThreadReplyPageTitle,
                                        subtitle = "#engineering",
                                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                                        channelKind = "public",
                                    ),
                                applyTopSafeArea = false,
                                onBack = {},
                                onOpenInChannel = {},
                                onOpenMemberProfile = {},
                                onOpenSearch = {},
                                onShowMembers = {},
                                onShowSettings = {},
                                onToggleFocusMode = { focused = !focused },
                                focusModeActive = focused,
                            )
                        }
                    }
                }
            }

            onNodeWithTag(FOCUS_MODE_MENU_ITEM_ID).performClick()
            waitForIdle()
            onNodeWithTag(FOCUS_MODE_MENU_ITEM_ID).performClick()
            waitForIdle()
            onNodeWithContentDescription("Close").performClick()
            waitForIdle()

            assertEquals(1, closed)
            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.Expand,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Thread,
                        compact = false,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.Restore,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Thread,
                        compact = false,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.Close,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Thread,
                        compact = false,
                    ),
                ),
                headerEvents(),
            )
        }

    @Test
    fun channelAnnouncementStripMenuOpenCallbackFiresWhenMenuOpens() =
        runRobolectricComposeUiTest {
            var menuOpens = 0
            var copies = 0
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        ChannelAnnouncementStrip(
                            announcement = channelAnnouncementFixture,
                            authorName = "Bugen",
                            onOpen = {},
                            onCopyLink = { copies++ },
                            onOpenSettings = {},
                            onOpenMenu = { menuOpens++ },
                        )
                    }
                }
            }

            onNodeWithTag(ChannelAnnouncementTestTags.StripOptions).performClick()
            waitForIdle()
            onNodeWithTag(ChannelAnnouncementTestTags.StripCopyLink).performClick()
            waitForIdle()

            assertEquals(1, menuOpens)
            assertEquals(1, copies)
        }

    @Test
    fun followersHeaderTracksReplyThreadAndIgnoresTaskAndRetry() =
        runRobolectricComposeUiTest {
            val agent =
                ThreadAgentFollower(
                    id = "ag-1",
                    displayName = "Lagoon",
                    name = "lagoon",
                    avatarUrl = null,
                    status = "active",
                    serverId = "srv-1",
                    serverName = null,
                    serverSlug = null,
                    isCurrentServer = true,
                    canRemove = true,
                )
            val loadedRoster = ThreadAgentFollowerRoster(threadChannelId = "th-1", canManage = true, agents = listOf(agent))
            var trackingEnabled by mutableStateOf(true)
            var state by mutableStateOf(ThreadAgentFollowersState(roster = loadedRoster, open = false))
            setContent {
                RaftDesignSystemTheme {
                    ThreadFollowersOverlayHost {
                        ThreadAgentFollowersHeader(
                            state = state,
                            dispatch = { intent ->
                                state =
                                    when (intent) {
                                        ThreadAgentFollowersIntent.Open -> state.copy(open = true)
                                        ThreadAgentFollowersIntent.Close -> state.copy(open = false)
                                        else -> state
                                    }
                            },
                            onOpenAgentProfile = {},
                            trackHeaderActions = trackingEnabled,
                        )
                    }
                }
            }

            // Open followers popover -> open_followers (header).
            onNodeWithContentDescription("1 watching").performClick()
            waitForIdle()
            // Remove follower -> remove_follower (followers_popover).
            onNodeWithContentDescription("Remove Lagoon from followers").performClick()
            waitForIdle()
            // Tap follower row -> open_profile (followers_popover).
            onNodeWithText("Lagoon").performClick()
            waitForIdle()

            // Retry on load error must not emit an event.
            state = ThreadAgentFollowersState(roster = null, open = true, error = ThreadFollowersError.Load)
            waitForIdle()
            onNodeWithText("Retry").performClick()
            waitForIdle()

            // Task conversation (trackHeaderActions = false) must emit nothing.
            trackingEnabled = false
            state = ThreadAgentFollowersState(roster = loadedRoster, open = false)
            waitForIdle()
            onNodeWithContentDescription("1 watching").performClick()
            waitForIdle()
            onNodeWithText("Lagoon").performClick()
            waitForIdle()

            assertEquals(
                listOf(
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenFollowers,
                        surface = RaftConversationHeaderSurface.Header,
                        conversation = RaftConversationHeaderKind.Thread,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.RemoveFollower,
                        surface = RaftConversationHeaderSurface.FollowersPopover,
                        conversation = RaftConversationHeaderKind.Thread,
                        compact = true,
                    ),
                    RaftConversationHeaderActionSelectedProperties(
                        action = RaftConversationHeaderAction.OpenProfile,
                        surface = RaftConversationHeaderSurface.FollowersPopover,
                        conversation = RaftConversationHeaderKind.Thread,
                        compact = true,
                    ),
                ),
                headerEvents(),
            )
        }
}
