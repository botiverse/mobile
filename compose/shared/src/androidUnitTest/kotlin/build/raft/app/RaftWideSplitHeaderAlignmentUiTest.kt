package build.raft.app

import build.raft.designsystem.theme.RaftControlMetrics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RealtimeSubscription
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.thread.CONVERSATION_SIDE_CARDS_SWITCH_TEST_TAG
import build.raft.app.thread.ConversationSideCard
import build.raft.app.thread.ConversationSideCardsCover
import build.raft.app.thread.ConversationSideCardsState
import build.raft.app.thread.LocalThreadHeaderMenuEventBus
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadHeaderSideCardToggles
import build.raft.app.thread.ThreadPageHeader
import build.raft.app.thread.conversationSideCardsColumn
import build.raft.designsystem.components.RAFT_PANE_GRID_HEAD_TEST_TAG
import build.raft.designsystem.components.RAFT_PANE_GRID_TABS_TEST_TAG
import build.raft.designsystem.components.RaftPaneGridExpand
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * First-principles split-view header bottom-line alignment across EVERY side-by-side column
 * (#proj-workspace:#379 PR2):
 * 1. Every visible side-by-side column's first horizontal hairline aligns with the conversation
 *    header's bottom hairline (`RaftTheme.controls.headHeight`: 48dp touch in a wide window / 44dp fine pointer).
 * 2. Loading and loaded Artifact share one layout (both aligned to `headHeight`, including beneath
 *    the `headHeight` standalone row-tab strip when crowded).
 * 3. Tasks/Files short-column switch (`shortSwitch`) aligns with `headHeight` in wide split view,
 *    and Rule 4 crowded row tabs sit inline in native headers (Conversation, Thread, Tasks/Files,
 *    Profile/Details) and at `headHeight` above Artifact without pushing any column out of line.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w2000dp-h800dp-mdpi")
class RaftWideSplitHeaderAlignmentUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private object QuietEventBus : RaftEventBus {
        override fun observe(eventName: String, crossProcess: Boolean, listener: (JsonObject?) -> Unit) = RealtimeSubscription {}

        override fun post(eventName: String, eventData: JsonObject, crossProcess: Boolean, source: String) = Unit
    }

    private enum class ProfileHeadVariant {
        HumanProfile,
        AgentProfile,
        ChannelDetails,
    }

    // The heads' height from the design system's own metrics (a wide window: the short head by touch).
    private val TouchHead = RaftControlMetrics.MobileShortHead.headHeight.value
    private val FineHead = RaftControlMetrics.Desktop.headHeight.value

    private var pointer by mutableStateOf(RaftPointerInput.Touch)
    private var rowWidth by mutableStateOf(2000f)
    private var rowHeight by mutableStateOf(700f)
    private var threadOpen by mutableStateOf(true)
    private var cardsState by mutableStateOf(
        ConversationSideCardsState().withOpen(ConversationSideCard.Tasks, true).withOpen(ConversationSideCard.Files, true)
    )
    private var artifactOpen by mutableStateOf(true)
    private var artifactLoaded by mutableStateOf(false)
    private var profileOpen by mutableStateOf(true)
    private var profileVariant by mutableStateOf(ProfileHeadVariant.HumanProfile)
    private var mainCarriesTabs by mutableStateOf(true)

    private val conversationPayload =
        RaftThreadScreenPayload(
            instanceId = "c1",
            sourceThreadId = "c1",
            title = "#general",
            status = "content",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            showModeTabs = true,
            showSettingsAction = true,
        )

    private val replyThreadPayload =
        RaftThreadScreenPayload(
            instanceId = "t1",
            sourceThreadId = "t1",
            title = "Thread",
            subtitle = "#general",
            status = "content",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            showModeTabs = false,
            showSettingsAction = false,
        )

    private val humanMember =
        MemberProfile(
            id = MemberId("u1"),
            kind = MemberKind.Human,
            displayName = "Alice",
            handle = "alice",
        )

    private val agentMember =
        MemberProfile(
            id = MemberId("a1"),
            kind = MemberKind.Agent,
            displayName = "Lagoon",
            handle = "lagoon",
        )

    private fun ComposeUiTest.showSplit() {
        val transport = HostBridgeTransport { _, _, _ -> }
        val platform =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) = Unit
                override fun closePage() = Unit
            }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), platform, bridgeTransport = transport)
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            val grid = remember { RaftPaneGridStore() }
            val sideLayout = remember { RaftChannelSideLayoutStore() }
            val expand = remember { RaftPaneGridExpand() }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftHost provides host,
                LocalRaftPaneGridStore provides grid,
                LocalRaftChannelSideLayoutStore provides sideLayout,
                LocalThreadHeaderMenuEventBus provides QuietEventBus,
            ) {
                RaftTheme(appearance = RaftAppearance.Light, pointerInput = pointer) {
                    RaftDesignSystemTheme {
                        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(rowWidth.dp, rowHeight.dp)) {
                            val threadColumn =
                                if (threadOpen) {
                                    RaftPaneGridColumn(
                                        bounds = RaftPaneGridColumns.Thread,
                                        resizeLabel = "Resize thread",
                                        tabLabel = "Thread",
                                        headCarriesTabs = true,
                                        cells =
                                            listOf(
                                                RaftPaneGridCell(id = "thread") {
                                                    val sidePane = remember { RaftSidePanePage(depth = 1) { threadOpen = false } }
                                                    CompositionLocalProvider(LocalRaftSidePanePage provides sidePane) {
                                                        Column(Modifier.fillMaxSize()) {
                                                            Box(Modifier.fillMaxWidth().testTag(THREAD_HEAD)) {
                                                                ThreadPageHeader(
                                                                    payload = replyThreadPayload,
                                                                    showBack = true,
                                                                    onBack = {},
                                                                    onOpenInChannel = {},
                                                                    onOpenMemberProfile = {},
                                                                    onOpenSearch = {},
                                                                    onShowMembers = {},
                                                                    onShowSettings = {},
                                                                    onToggleFocusMode = {},
                                                                    onToggleThreadFollow = {},
                                                                    threadFollowed = true,
                                                                )
                                                            }
                                                            BasicText("thread-body", Modifier.fillMaxSize().testTag("thread-body"))
                                                        }
                                                    }
                                                }
                                            ),
                                    )
                                } else {
                                    null
                                }
                            val artifactColumn =
                                if (artifactOpen) {
                                    raftArtifactColumn {
                                        val sidePane = remember { RaftSidePanePage(depth = 1) { artifactOpen = false } }
                                        CompositionLocalProvider(
                                            LocalRaftSidePanePage provides sidePane,
                                            LocalRaftArtifactViewerInColumn provides true,
                                        ) {
                                            val hostHead = raftArtifactViewerHostHeadHere()
                                            val url =
                                                remember(hostHead) {
                                                    raftArtifactViewerEmbeddedPageUrl(WEB, ArtifactLinkTarget("pt_a1"), hostHead = hostHead)
                                                }
                                            RaftArtifactViewerScreenFrame(
                                                title = "plan.html",
                                                onBack = {},
                                                pageSaysItDrawsBar = artifactLoaded,
                                                viewing = true,
                                                scopeKnown = true,
                                            ) {
                                                Column(Modifier.fillMaxSize().testTag(ARTIFACT_BODY)) {
                                                    Box(
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .height((raftArtifactViewerHostHeadIn(url) ?: 0).dp)
                                                            .testTag(ARTIFACT_PAGE_BAR)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    null
                                }
                            val profileColumn =
                                if (profileOpen) {
                                    raftProfileColumn(listOf(RaftProfileColumnCell.Profile)) {
                                        val sidePane = remember { RaftSidePanePage(depth = 1) { profileOpen = false } }
                                        CompositionLocalProvider(LocalRaftSidePanePage provides sidePane) {
                                            Column(Modifier.fillMaxSize()) {
                                                Box(Modifier.fillMaxWidth().testTag(PROFILE_HEAD)) {
                                                    when (profileVariant) {
                                                        ProfileHeadVariant.HumanProfile ->
                                                            MembersHumanDetailHeader(
                                                                member = humanMember,
                                                                currentUserId = "me",
                                                                currentServerId = "s1",
                                                                onBack = {},
                                                                onOpenDirectMessage = {},
                                                            )
                                                        ProfileHeadVariant.AgentProfile ->
                                                            MembersDetailHeader(
                                                                member = agentMember,
                                                                currentUserId = "me",
                                                                currentServerId = "s1",
                                                                detail = null,
                                                                deleted = false,
                                                                canManage = true,
                                                                agentStatusIndicatorState = null,
                                                                onBack = {},
                                                                onOpenDirectMessage = {},
                                                                onOpenLounge = {},
                                                                onCopyMemberId = {},
                                                                onManagementMenuAnchorResolverReady = {},
                                                                onRequestManagementMenu = {},
                                                            )
                                                        ProfileHeadVariant.ChannelDetails ->
                                                            RaftPanelBackHeader(
                                                                title = "Channel details",
                                                                subtitle = "#general",
                                                                onBack = {},
                                                            )
                                                    }
                                                }
                                                BasicText("profile-body", Modifier.fillMaxSize().testTag("profile-body"))
                                            }
                                        }
                                    }
                                } else {
                                    null
                                }
                            // Outermost split grid (RaftAdaptiveListDetailSplit) -> detail grid -> conversation grid (ThreadRouteScaffold).
                            RaftPaneGrid(columns = emptyList(), modifier = Modifier.fillMaxSize()) {
                                RaftPaneGrid(
                                    columns = listOfNotNull(threadColumn, artifactColumn, profileColumn),
                                    modifier = Modifier.fillMaxSize(),
                                    expand = expand,
                                ) {
                                    val sideCards =
                                        conversationSideCardsColumn(
                                            cards = cardsState,
                                            onPick = { picked -> cardsState = cardsState.focused(picked) },
                                            onClose = { closed -> cardsState = cardsState.withOpen(closed, false) },
                                            tasksContent = { BasicText("tasks-body", Modifier.fillMaxSize().testTag("tasks-body")) },
                                            filesContent = { BasicText("files-body", Modifier.fillMaxSize().testTag("files-body")) },
                                        )
                                    RaftPaneGrid(
                                        columns = listOfNotNull(sideCards),
                                        modifier = Modifier.fillMaxSize(),
                                        mainHeadCarriesTabs = mainCarriesTabs,
                                    ) {
                                        Column(Modifier.fillMaxSize()) {
                                            Box(Modifier.fillMaxWidth().testTag(CONVERSATION_HEAD)) {
                                                ThreadPageHeader(
                                                    payload = conversationPayload,
                                                    showBack = false,
                                                    onBack = {},
                                                    onOpenInChannel = {},
                                                    onOpenMemberProfile = {},
                                                    onOpenSearch = {},
                                                    onShowMembers = {},
                                                    onShowSettings = {},
                                                    sideCardToggles =
                                                        ThreadHeaderSideCardToggles(
                                                            tasksAvailable = true,
                                                            cards = cardsState,
                                                            onToggle = { card -> cardsState = cardsState.toggled(card) },
                                                        ),
                                                )
                                            }
                                            BasicText("conv-body", Modifier.fillMaxSize().testTag("conv-body"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.topDp(tag: String): Float =
        with(density) { onAllNodesWithTag(tag, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.top.toDp().value }

    private fun ComposeUiTest.bottomDp(tag: String): Float =
        with(density) { onAllNodesWithTag(tag, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.bottom.toDp().value }

    private fun near(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 1f, "$what: expected $expected, was $actual")

    @Test
    fun normalWideSplitAlignsEveryColumnFirstBottomHairlineByTouchAndMouse() =
        runRobolectricComposeUiTest {
            rowWidth = 2000f
            rowHeight = 700f
            threadOpen = true
            cardsState = ConversationSideCardsState().withOpen(ConversationSideCard.Tasks, true).withOpen(ConversationSideCard.Files, true)
            artifactOpen = true
            profileOpen = true
            mainCarriesTabs = true
            pointer = RaftPointerInput.Touch
            showSplit()

            listOf(RaftPointerInput.Touch to TouchHead, RaftPointerInput.Fine to FineHead).forEach { (mode, expected) ->
                runOnIdle { pointer = mode }
                waitForIdle()
                val label = mode.name
                val convBottom = bottomDp(CONVERSATION_HEAD)
                near(expected, convBottom, "$label conversation head bottom")
                near(convBottom, bottomDp(THREAD_HEAD), "$label thread head bottom")
                // RaftPaneGridColumnHead tags its Row (`headHeight - 1dp` above the 1dp hairline).
                near(convBottom - 1f, bottomDp(RAFT_PANE_GRID_HEAD_TEST_TAG + "tasks"), "$label tasks card head row")
                // Artifact loading & loaded share the same first bottom hairline.
                runOnIdle { artifactLoaded = false }
                waitForIdle()
                near(convBottom, topDp(ARTIFACT_BODY), "$label artifact loading bar bottom")
                runOnIdle { artifactLoaded = true }
                waitForIdle()
                near(convBottom, bottomDp(ARTIFACT_PAGE_BAR), "$label artifact loaded page bar bottom")
                // Profile / Details variants all align with conversation head bottom.
                ProfileHeadVariant.entries.forEach { variant ->
                    runOnIdle { profileVariant = variant }
                    waitForIdle()
                    near(convBottom, bottomDp(PROFILE_HEAD), "$label profile ($variant) head bottom")
                }
            }
        }

    @Test
    fun shortColumnSideCardsSwitchAlignsWithConversationHeadByTouchAndMouse() =
        runRobolectricComposeUiTest {
            rowWidth = 1280f
            // Below raftPaneGridStackMinHeightDp(2) = 321dp so SideCards enters shortSwitch mode.
            rowHeight = 280f
            threadOpen = false
            artifactOpen = false
            profileOpen = false
            cardsState =
                ConversationSideCardsState()
                    .withOpen(ConversationSideCard.Tasks, true)
                    .withOpen(ConversationSideCard.Files, true)
                    .focused(ConversationSideCard.Tasks)
            pointer = RaftPointerInput.Touch
            showSplit()

            listOf(RaftPointerInput.Touch to TouchHead, RaftPointerInput.Fine to FineHead).forEach { (mode, expected) ->
                runOnIdle { pointer = mode }
                waitForIdle()
                val convBottom = bottomDp(CONVERSATION_HEAD)
                near(expected, convBottom, "${mode.name} conversation head bottom")
                near(0f, topDp(CONVERSATION_SIDE_CARDS_SWITCH_TEST_TAG), "${mode.name} shortSwitch top")
                near(convBottom, bottomDp(CONVERSATION_SIDE_CARDS_SWITCH_TEST_TAG), "${mode.name} shortSwitch bottom")
            }

            // Switching from Tasks to Files in shortSwitch mode keeps the top switch aligned.
            onNode(hasText("Files") and hasAnyAncestor(hasTestTag(CONVERSATION_SIDE_CARDS_SWITCH_TEST_TAG))).performClick()
            waitForIdle()
            onNodeWithTag("files-body", useUnmergedTree = true).assertExists()
            near(bottomDp(CONVERSATION_HEAD), bottomDp(CONVERSATION_SIDE_CARDS_SWITCH_TEST_TAG), "shortSwitch bottom after pick")
        }

    @Test
    fun crowdedSplitRowTabsAlignEveryColumnByTouchAndMouse() =
        runRobolectricComposeUiTest {
            // Case 1: 700dp with 5 units -> [Conv] [Thread] [Tasks·Files] [Artifact | Profile]
            rowWidth = 700f
            rowHeight = 700f
            threadOpen = true
            cardsState = ConversationSideCardsState().withOpen(ConversationSideCard.Tasks, true).withOpen(ConversationSideCard.Files, true)
            artifactOpen = true
            artifactLoaded = false
            profileOpen = true
            profileVariant = ProfileHeadVariant.HumanProfile
            mainCarriesTabs = true
            pointer = RaftPointerInput.Touch
            showSplit()

            listOf(RaftPointerInput.Touch to TouchHead, RaftPointerInput.Fine to FineHead).forEach { (mode, expected) ->
                runOnIdle { pointer = mode }
                waitForIdle()
                val label = "700dp ${mode.name}"
                val convBottom = bottomDp(CONVERSATION_HEAD)
                near(expected, convBottom, "$label conversation head bottom")
                near(convBottom, bottomDp(THREAD_HEAD), "$label thread head bottom")
                // Artifact carries no inline tabs (WebView owns loaded toolbar), so its standalone tab strip is headHeight tall.
                val artifactTabsTag = RAFT_PANE_GRID_TABS_TEST_TAG + RaftPaneGridColumns.Artifact.id
                near(0f, topDp(artifactTabsTag), "$label artifact tab strip top")
                near(convBottom, bottomDp(artifactTabsTag), "$label artifact tab strip bottom")
                // Loading and loaded Artifact sit directly under the same headHeight tab strip.
                runOnIdle { artifactLoaded = false }
                waitForIdle()
                val loadingBarBottom = topDp(ARTIFACT_BODY)
                runOnIdle { artifactLoaded = true }
                waitForIdle()
                val loadedBarBottom = bottomDp(ARTIFACT_PAGE_BAR)
                near(loadingBarBottom, loadedBarBottom, "$label artifact loading vs loaded share one layout under tab strip")

                // Switch to Profile tab: Profile carries row tabs inline in its own header (no extra strip above it).
                onNodeWithText("Profile").performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
                ProfileHeadVariant.entries.forEach { variant ->
                    runOnIdle { profileVariant = variant }
                    waitForIdle()
                    near(0f, topDp(PROFILE_HEAD), "$label profile ($variant) head top (no extra strip)")
                    near(convBottom, bottomDp(PROFILE_HEAD), "$label profile ($variant) head bottom")
                    onNodeWithTag(RAFT_PANE_GRID_TABS_TEST_TAG + RaftPaneGridColumns.Profile.id, useUnmergedTree = true).assertExists()
                }
                // Switch back to Artifact before next pointer mode iteration.
                onNodeWithText("Artifact").performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
            }

            // Case 2: 420dp with [Conv, Thread, SideCards] -> [Conv] [Thread | Tasks·Files]
            runOnIdle {
                artifactOpen = false
                profileOpen = false
                rowWidth = 420f
                pointer = RaftPointerInput.Touch
            }
            waitForIdle()
            listOf(RaftPointerInput.Touch to TouchHead, RaftPointerInput.Fine to FineHead).forEach { (mode, expected) ->
                runOnIdle { pointer = mode }
                waitForIdle()
                val label = "420dp ${mode.name}"
                val convBottom = bottomDp(CONVERSATION_HEAD)
                near(expected, convBottom, "$label conversation head bottom")
                // Thread carries row tabs inline in ThreadPageHeader (top = 0dp, bottom = headHeight).
                near(0f, topDp(THREAD_HEAD), "$label thread head top (no extra strip)")
                near(convBottom, bottomDp(THREAD_HEAD), "$label thread head bottom")
                onNodeWithTag(RAFT_PANE_GRID_TABS_TEST_TAG + RaftPaneGridColumns.Thread.id, useUnmergedTree = true).assertExists()
                // Tabs remain clickable even in narrow touch width to switch to Tasks · Files.
                onNodeWithText("Tasks · Files").performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
                near(convBottom - 1f, bottomDp(RAFT_PANE_GRID_HEAD_TEST_TAG + "tasks"), "$label switched tasks card head row")
                // Switch back to Thread.
                onNodeWithText("Thread").performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
            }

            // Case 3: 280dp with [Conv, SideCards] -> [Conv | Tasks·Files] (main pane carries row tabs inline!)
            runOnIdle {
                threadOpen = false
                rowWidth = 280f
                pointer = RaftPointerInput.Touch
            }
            waitForIdle()
            listOf(RaftPointerInput.Touch to TouchHead, RaftPointerInput.Fine to FineHead).forEach { (mode, expected) ->
                runOnIdle { pointer = mode }
                waitForIdle()
                val label = "280dp ${mode.name}"
                // Main conversation carries row tabs inside ThreadPageHeader (no extra strip pushing it down).
                near(0f, topDp(CONVERSATION_HEAD), "$label conversation head top (no extra strip)")
                near(expected, bottomDp(CONVERSATION_HEAD), "$label conversation head bottom")
                onNodeWithTag(RAFT_PANE_GRID_TABS_TEST_TAG + "conversation", useUnmergedTree = true).assertExists()
                // Clicking Tasks · Files tab switches to SideCards cleanly with matching head bottom.
                onNodeWithText("Tasks · Files").performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
                near(expected - 1f, bottomDp(RAFT_PANE_GRID_HEAD_TEST_TAG + "tasks"), "$label switched tasks card head row")
                // Switch back to Chat.
                onNodeWithText("Chat").performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
            }
        }

    @Test
    fun phoneOverlaySideCardsSwitchMatchesWebHeightByTouchAndMouse() =
        runRobolectricComposeUiTest {
            var inputMode by mutableStateOf(RaftPointerInput.Touch)
            val both = ConversationSideCardsState().withOpen(ConversationSideCard.Tasks, true).withOpen(ConversationSideCard.Files, true)
            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftTheme(appearance = RaftAppearance.Light, pointerInput = inputMode) {
                        RaftDesignSystemTheme {
                            Box(Modifier.requiredSize(393.dp, 600.dp)) {
                                ConversationSideCardsCover(
                                    cards = both,
                                    onPick = {},
                                    onBack = null,
                                    onSwipeClose = {},
                                    tasksContent = { BasicText("tasks") },
                                    filesContent = { BasicText("files") },
                                )
                            }
                        }
                    }
                }
            }
            waitForIdle()
            // Web `channel-side-switch`: h-12 md:pointer-fine:h-panel-header (48dp touch, 44dp fine pointer, seam inside).
            near(48f, bottomDp(CONVERSATION_SIDE_CARDS_SWITCH_TEST_TAG) - topDp(CONVERSATION_SIDE_CARDS_SWITCH_TEST_TAG), "overlay touch switch height")
            runOnIdle { inputMode = RaftPointerInput.Fine }
            waitForIdle()
            near(44f, bottomDp(CONVERSATION_SIDE_CARDS_SWITCH_TEST_TAG) - topDp(CONVERSATION_SIDE_CARDS_SWITCH_TEST_TAG), "overlay fine switch height")
        }

    private companion object {
        const val WEB = "https://app.raft.build"
        const val CONVERSATION_HEAD = "split-conv-head"
        const val THREAD_HEAD = "split-thread-head"
        const val PROFILE_HEAD = "split-profile-head"
        const val ARTIFACT_BODY = "split-artifact-body"
        const val ARTIFACT_PAGE_BAR = "split-artifact-page-bar"
    }
}
