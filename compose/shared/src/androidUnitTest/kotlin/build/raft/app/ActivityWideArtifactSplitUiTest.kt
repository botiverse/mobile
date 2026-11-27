package build.raft.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import build.raft.app.data.ActivityObjectRow
import build.raft.app.data.AppLocale
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.StringDesc
import build.raft.app.shell.LocalRaftPaneGridStore
import build.raft.app.shell.RaftAdaptiveListDetailSplit
import build.raft.app.shell.RaftListPaneWidths
import build.raft.app.shell.RaftPaneEntry
import build.raft.app.shell.RaftPaneGridStore
import build.raft.app.thread.key
import build.raft.designsystem.components.raftClickable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Wide Activity split-pane Artifact viewer UI tests (web `MainLayout.tsx` `InboxContentRoute` +
 * `ArtifactContentSlot` + `ArtifactActivityViewer.tsx`, dm:@artin:#168):
 * - On wide viewports, clicking an Activity Artifact row (comment, version, mention) opens the
 *   Artifact viewer in the right split pane beside the Activity list instead of pushing a
 *   full-screen window page, while keeping the Activity list visible and interactive on the left.
 * - Clicking another Activity Artifact (or an Artifact link inside the viewer) switches the right
 *   pane in place; switching between Artifact and Thread/Conversation items cleanly swaps the
 *   right detail pane without stacking or reviving replaced conversations on close.
 * - Closing via the viewer's Close (`closePage`) or System Back closes the right pane cleanly and
 *   leaves the Activity list visible; the next Back falls through to the parent page.
 * - On phone / narrow viewports (`dualPane = false`), no split pane is mounted and clicking an
 *   Activity Artifact still dispatches full-screen navigation.
 * - Narrowing out of wide split while an Artifact is open promotes it onto the full-screen stack.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class ActivityWideArtifactSplitUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    private val storeScope = RaftShellStoreScope.create(shellInstanceId = "shell-activity-test", initialServerId = "s-1")

    private val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 24),
            nowEpochMillis = 1_795_514_400_000L,
        )

    private val snapshot =
        InboxSnapshot(
            server = ServerSummary(id = ServerId("s-1"), name = "Northwind Labs"),
            items =
                listOf(
                    artifactInboxItem(
                        id = "pt_alpha",
                        title = "Q4 launch plan",
                        eventKind = "comment",
                        reason = "mentioned",
                        unread = 2,
                        mention = true,
                    ),
                    artifactInboxItem(
                        id = "pt_beta",
                        title = "Metrics dashboard",
                        eventKind = "version",
                        reason = "following",
                        unread = 1,
                        version = 3,
                    ),
                    InboxItem(
                        key = "thread:t-design",
                        kind = InboxItemKind.Thread,
                        channelId = ChannelId("t-design"),
                        threadChannelId = ChannelId("t-design"),
                        parentChannelId = ChannelId("c-design"),
                        channelName = "design",
                        unreadCount = 1,
                        lastMessageSenderName = "artin",
                        lastMessageSenderKind = MemberKind.Human,
                        lastMessagePreview = "Should open in the side pane",
                        lastMessageAt = "2026-11-24T11:30:00Z",
                        latestActivityAt = "2026-11-24T11:30:00Z",
                    ),
                ),
            totalCount = 1,
            totalUnreadCount = 1,
            hasMore = false,
        )

    private lateinit var artifactState: ActivityWideArtifactState
    private var dualPane by mutableStateOf(true)
    private var selectedConversationId by mutableStateOf<String?>(null)
    private var serverId by mutableStateOf("s-1")
    private var surfaceSelected by mutableStateOf(true)
    private val platformOpened = mutableListOf<Pair<String, JsonObject>>()
    private val narrowFallbackUrls = mutableListOf<String>()
    private var pageBacks = 0
    private var observedViewerInColumn: Boolean? = null

    @BeforeTest
    fun setUp() {
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        artifactState = ActivityWideArtifactState()
        dualPane = true
        selectedConversationId = null
        serverId = "s-1"
        surfaceSelected = true
        platformOpened.clear()
        narrowFallbackUrls.clear()
        pageBacks = 0
        observedViewerInColumn = null
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    private fun ComposeUiTest.mount(widthDp: Float = 1280f): () -> Unit {
        var dispatcher: OnBackPressedDispatcher? = null
        val transport = HostBridgeTransport { _, _, _ -> }
        val platform =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) {
                    platformOpened += pageName to pageData
                }

                override fun closePage() = Unit
            }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), platform, bridgeTransport = transport)
        setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            RaftBackHandler(enabled = true, label = "test_activity_host_page") { pageBacks++ }
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            val widths = remember { RaftListPaneWidths(KeyValueChatLayoutPreferenceStore(InMemoryRaftKeyValueStore())) }
            val grid = remember { RaftPaneGridStore() }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftHost provides host,
                LocalRaftPaneGridStore provides grid,
                LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext),
                LocalRaftArtifactOpenStoreScope provides storeScope,
                LocalUriHandler provides
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            narrowFallbackUrls += uri
                        }
                    },
            ) {
                RaftDesignSystemTheme {
                    ActivityWideArtifactLifecycleEffect(
                        state = artifactState,
                        wide = dualPane,
                        surfaceSelected = surfaceSelected,
                        active = surfaceSelected,
                        serverId = serverId,
                    )
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(widthDp.dp, 720.dp)) {
                        val hasArtifact = dualPane && artifactState.isOpen
                        val hasSelection = hasArtifact || selectedConversationId != null
                        RaftAdaptiveListDetailSplit(
                            kind = RaftListPaneKind.List,
                            widths = widths,
                            dualPane = dualPane,
                            active = true,
                            hasSelection = hasSelection,
                            detailBottomInset = 0.dp,
                            maxShare = 0.5f.takeIf { hasSelection },
                            listPane = {
                                Box(Modifier.fillMaxSize().testTag(ACTIVITY_LIST_TAG)) {
                                    SharedHomeActivityPanel(
                                        snapshot = snapshot,
                                        selectedFilter = InboxFilter.All,
                                        onOpenItem = { item ->
                                            artifactState.close()
                                            selectedConversationId = item.toConversationSummary().threadId.value
                                        },
                                        onOpenArtifact =
                                            if (dualPane) {
                                                { target, scopeOverride, conv ->
                                                    selectedConversationId = null
                                                    artifactState.open(target, scopeOverride ?: storeScope, conv)
                                                    true
                                                }
                                            } else {
                                                null
                                            },
                                        externalSelectedThreadId =
                                            if (artifactState.isOpen) null else selectedConversationId,
                                        externalSelectedArtifactId =
                                            artifactState.currentArtifactId.takeIf { dualPane },
                                        externalSelectionOwned = dualPane,
                                        timeContext = timeContext,
                                        foregroundEpoch = 0L,
                                    )
                                }
                            },
                            detailPane = { detailModifier ->
                                if (hasArtifact) {
                                    RaftActivityArtifactDetailPane(
                                        state = artifactState,
                                        active = true,
                                        modifier = detailModifier,
                                        page = { entry, _ -> FixtureActivityArtifactViewer(entry) },
                                    )
                                } else if (selectedConversationId != null) {
                                    Box(detailModifier.testTag("conversation-detail:${selectedConversationId}")) {
                                        BasicText("conversation:${selectedConversationId}")
                                    }
                                } else {
                                    Box(detailModifier.testTag(EMPTY_DETAIL_TAG)) {
                                        BasicText("empty-detail")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        waitForIdle()
        return {
            runOnIdle { assertNotNull(dispatcher, "back dispatcher").onBackPressed() }
            waitForIdle()
        }
    }

    @Composable
    private fun FixtureActivityArtifactViewer(entry: RaftPaneEntry) {
        val navigator = LocalRaftHost.current.navigator
        val inColumn = LocalRaftArtifactViewerInColumn.current
        SideEffect { observedViewerInColumn = inColumn }
        val id = entry.pageData.toKuiklyJson().raftArtifactViewerTargetOrNull()?.id.orEmpty()
        Column(Modifier.fillMaxSize().testTag("activity-viewer:$id")) {
            BasicText(
                "close",
                Modifier.fillMaxWidth().testTag("activity-viewer-close").raftClickable(onClick = navigator::closePage),
            )
            BasicText(
                "open-linked-artifact",
                Modifier
                    .fillMaxWidth()
                    .testTag("activity-viewer-open-linked")
                    .raftClickable(
                        onClick = {
                            navigator.openPage(
                                RAFT_ARTIFACT_VIEWER_PAGE,
                                raftArtifactViewerPageData(ArtifactLinkTarget("pt_beta")).toJsonObject(),
                            )
                        },
                    ),
            )
        }
    }

    private fun ComposeUiTest.leftDp(tag: String): Float =
        with(density) { onAllNodesWithTag(tag, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.left.toDp().value }

    private fun ComposeUiTest.rightDp(tag: String): Float =
        with(density) { onAllNodesWithTag(tag, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.right.toDp().value }

    @Test
    fun wideActivityClickingArtifactOpensInRightSplitPaneAndSwitchesCleanlyWithArtifactsAndThreads() =
        runRobolectricComposeUiTest {
            val back = mount()
            onNodeWithTag(ACTIVITY_LIST_TAG, useUnmergedTree = true).assertExists()
            onNodeWithTag(EMPTY_DETAIL_TAG, useUnmergedTree = true).assertExists()
            onNodeWithTag(ActivityWideArtifactTags.Viewer, useUnmergedTree = true).assertDoesNotExist()

            // Click comment/mention Artifact row -> opens in right split pane beside the Activity list, not full-screen.
            onNodeWithText("Q4 launch plan", useUnmergedTree = true).performClick()
            waitForIdle()
            assertTrue(artifactState.isOpen)
            assertEquals("pt_alpha", artifactState.currentArtifactId)
            assertEquals(true, observedViewerInColumn)
            assertTrue(platformOpened.isEmpty(), "wide Activity Artifact click must not push full-screen window")
            assertTrue(narrowFallbackUrls.isEmpty(), "wide Activity Artifact click must not fall back to browser")
            onNodeWithTag(ACTIVITY_LIST_TAG, useUnmergedTree = true).assertExists()
            onNodeWithTag(ActivityWideArtifactTags.Viewer, useUnmergedTree = true).assertExists()
            onNodeWithTag("activity-viewer:pt_alpha", useUnmergedTree = true).assertExists()
            assertTrue(
                leftDp(ActivityWideArtifactTags.Viewer) >= rightDp(ACTIVITY_LIST_TAG) - 1f,
                "Artifact viewer stands to the right of the Activity list",
            )

            // Click a second Artifact row (version notification) -> switches right pane in place.
            onNodeWithText("Metrics dashboard", useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals("pt_beta", artifactState.currentArtifactId)
            onNodeWithTag("activity-viewer:pt_alpha", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("activity-viewer:pt_beta", useUnmergedTree = true).assertExists()
            assertTrue(platformOpened.isEmpty())

            // Click a Thread row -> Artifact pane closes and right pane shows the Thread conversation.
            onNodeWithText("design", substring = true, useUnmergedTree = true).performClick()
            waitForIdle()
            assertFalse(artifactState.isOpen)
            assertEquals("t-design", selectedConversationId)
            onNodeWithTag(ActivityWideArtifactTags.Viewer, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("conversation-detail:t-design", useUnmergedTree = true).assertExists()

            // Click an Artifact row again while Thread is open -> replaces Thread with Artifact in right pane.
            onNodeWithText("Q4 launch plan", useUnmergedTree = true).performClick()
            waitForIdle()
            assertTrue(artifactState.isOpen)
            assertNull(selectedConversationId, "opening an Activity Artifact supersedes the previous conversation")
            onNodeWithTag("conversation-detail:t-design", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("activity-viewer:pt_alpha", useUnmergedTree = true).assertExists()

            // Clicking an Artifact link inside the viewer switches in place too.
            onNodeWithTag("activity-viewer-open-linked", useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals("pt_beta", artifactState.currentArtifactId)
            onNodeWithTag("activity-viewer:pt_beta", useUnmergedTree = true).assertExists()

            // Close via viewer Close -> returns to empty detail beside the Activity list (does not revive t-design).
            onNodeWithTag("activity-viewer-close", useUnmergedTree = true).performClick()
            waitForIdle()
            assertFalse(artifactState.isOpen)
            assertNull(selectedConversationId)
            onNodeWithTag(ActivityWideArtifactTags.Viewer, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(ACTIVITY_LIST_TAG, useUnmergedTree = true).assertExists()
            onNodeWithTag(EMPTY_DETAIL_TAG, useUnmergedTree = true).assertExists()

            // Open again and close via System Back -> first Back closes Artifact, second Back reaches host.
            onNodeWithText("Q4 launch plan", useUnmergedTree = true).performClick()
            waitForIdle()
            assertTrue(artifactState.isOpen)
            back()
            assertFalse(artifactState.isOpen)
            assertEquals(0, pageBacks)
            onNodeWithTag(EMPTY_DETAIL_TAG, useUnmergedTree = true).assertExists()
            back()
            assertEquals(1, pageBacks)
        }

    @Test
    fun narrowViewportDoesNotMountSplitPaneAndUsesFullScreenPath() =
        runRobolectricComposeUiTest {
            dualPane = false
            mount(widthDp = 390f)
            onNodeWithTag(ACTIVITY_LIST_TAG, useUnmergedTree = true).assertExists()
            onAllNodesWithTag(ActivityWideArtifactTags.Viewer, useUnmergedTree = true).assertCountEquals(0)

            onNodeWithText("Q4 launch plan", useUnmergedTree = true).performClick()
            waitForIdle()
            assertFalse(artifactState.isOpen, "phone/narrow viewport must not open wide split Artifact pane")
            onAllNodesWithTag(ActivityWideArtifactTags.Viewer, useUnmergedTree = true).assertCountEquals(0)
            // Without a live native BridgeModule in Robolectric, the non-overridden opener reaches its fallback,
            // proving openPageOverride was NOT installed for narrow viewports.
            assertEquals(1, narrowFallbackUrls.size)
            assertTrue(narrowFallbackUrls[0].contains("pt_alpha"))
        }

    @Test
    fun narrowingWhileWideArtifactIsOpenPromotesItToWindowStack() =
        runRobolectricComposeUiTest {
            mount()
            onNodeWithText("Q4 launch plan", useUnmergedTree = true).performClick()
            waitForIdle()
            assertTrue(artifactState.isOpen)
            assertTrue(platformOpened.isEmpty())

            // Shrinking to compact promotes the open Artifact onto the platform window stack.
            runOnIdle { dualPane = false }
            waitForIdle()
            assertFalse(artifactState.isOpen)
            assertEquals(1, platformOpened.size)
            assertEquals(RAFT_ARTIFACT_VIEWER_PAGE, platformOpened[0].first)
            assertEquals(
                ArtifactLinkTarget("pt_alpha"),
                platformOpened[0].second.toKuiklyJson().raftArtifactViewerTargetOrNull(),
            )
        }

    @Test
    fun switchingServerOrLeavingActivityClosesWideArtifactPaneCleanly() =
        runRobolectricComposeUiTest {
            mount()
            onNodeWithText("Q4 launch plan", useUnmergedTree = true).performClick()
            waitForIdle()
            assertTrue(artifactState.isOpen)

            // Leaving Activity tab closes the split viewer without pushing a full-screen window.
            runOnIdle { surfaceSelected = false }
            waitForIdle()
            assertFalse(artifactState.isOpen)
            assertTrue(platformOpened.isEmpty())

            // Returning and opening again, then switching servers closes the previous server's viewer.
            runOnIdle { surfaceSelected = true }
            waitForIdle()
            onNodeWithText("Metrics dashboard", useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals("pt_beta", artifactState.currentArtifactId)

            runOnIdle { serverId = "s-2" }
            waitForIdle()
            assertFalse(artifactState.isOpen)
            assertTrue(platformOpened.isEmpty())
        }

    private fun artifactInboxItem(
        id: String,
        title: String,
        eventKind: String,
        reason: String,
        unread: Int,
        mention: Boolean = false,
        version: Int? = null,
    ) = InboxItem(
        key = "object:artifact:$id",
        kind = InboxItemKind.Object,
        channelId = ChannelId("object:$id"),
        channelName = title,
        unreadCount = unread,
        hasMention = mention,
        lastMessageAt = "2026-11-24T12:00:00Z",
        latestActivityAt = "2026-11-24T12:00:00Z",
        isFollowing = true,
        activityObject =
            ActivityObjectRow(
                objectKind = "artifact",
                objectId = id,
                title = title,
                artifactKind = "html",
                latestVersion = version,
                homeKind = "channel",
                homeLabel = "proj-workspace",
                eventKind = eventKind,
                reason = reason,
                actorType = "user",
                actorId = "u-artin",
                actorName = "artin",
                eventAt = "2026-11-24T12:00:00Z",
                targetVersion = version,
                doneFrontierSeq = "42",
            ),
    )

    private companion object {
        const val ACTIVITY_LIST_TAG = "activity-split-list"
        const val EMPTY_DETAIL_TAG = "activity-split-empty-detail"
    }
}
