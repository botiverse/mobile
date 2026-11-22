package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftListPaneKind
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftMessageImageDimensions
import build.raft.designsystem.components.RaftMessageImageGallery
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin's foldable report: a Huawei foldable unfolded (1016 x 720dp, touch), the Activity page with a thread open and
 * an Artifact opened from it. The real wide layout: the shell's top bar and rail, [RaftAdaptiveListDetailSplit] with
 * the Activity list (its kind's width kept at 640, at most half the row with an item open, as the shell passes it:
 * 479), and in its detail the shell's [RaftWorkspaceDetailGrid] with the thread as its main pane and the Artifact
 * column handed up to the split's row. The row right of the list is 479, short of the thread's 360 and the
 * Artifact's 480: by raft-design responsive.md "The channel's side columns" both clip (rule 4, step 4), their content
 * kept at its minimum against their start, each head fitted to what shows (rule 2: "the head is never clipped"),
 * nothing past the window or over the neighbour. The thread's text fades into the surface at its end (fade-edge, in
 * `layer-canvas`, never to transparent); its image and the Artifact's dark page are full-bleed, cut hard at the edge,
 * never faded (raftPaneGridNoFade): no band of the surface glowing over dark media. The thread's head is
 * [RaftTopPageHeader] with a title long enough to ellipsize; the Artifact's is the App-drawn viewer bar
 * ([RaftArtifactViewerFrame]) over a dark slide. Light and dark. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1016dp-h720dp-mdpi")
class ActivityThreadArtifactFoldableScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Before
    fun installPreferenceStore() {
        val store = InMemoryRaftKeyValueStore()
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(RaftLocalPreferenceStore.KuiklyNamespace) { store }
    }

    @After
    fun clearPreferenceStore() = RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()

    @Test
    fun activityThreadArtifactFoldable() = capture("activity_thread_artifact_foldable", RaftAppAppearance.Light)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            val grid = remember { RaftPaneGridStore() }
            val widths =
                remember {
                    // A width someone dragged wide: the Activity list then renders at its half-row cap, as in the video.
                    val preferences = KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())
                    preferences.saveAdaptiveListPaneWidth(RaftListPaneKind.List, "640")
                    RaftListPaneWidths(preferences)
                }
            val host =
                remember {
                    val transport = HostBridgeTransport { _, _, _ -> }
                    val navigator =
                        object : RaftCmpNavigator {
                            override fun openPage(pageName: String, pageData: JsonObject) = Unit

                            override fun closePage() = Unit
                        }
                    RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
                }
            val artifact =
                remember {
                    RaftArtifactColumnPane().open(RAFT_ARTIFACT_VIEWER_PAGE, JsonObject(mapOf("artifactId" to JsonPrimitive("pt_coscon"))))
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftHost provides host,
                LocalRaftPaneGridStore provides grid,
                LocalRaftChannelSideLayoutStore provides remember { RaftChannelSideLayoutStore() },
            ) {
                val servers = listOf(RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 454L))
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                        RaftAppShellTopBar(
                            currentServer = servers.first(),
                            servers = servers,
                            serverMenuOpen = false,
                            onWorkspaceClick = {},
                            onServerPick = {},
                            searchLabel = "Search",
                            onSearchClick = {},
                            windowWidth = 1016.dp,
                        )
                        Row(Modifier.weight(1f)) {
                            RaftNavigationRailTabBar(
                                items =
                                    listOf(
                                        RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.House),
                                        RaftBottomTabItemPayload("activity", "Activity", RaftLucideIconName.Activity, selected = true),
                                        RaftBottomTabItemPayload("tasks", "Tasks", RaftLucideIconName.SquareCheckBig),
                                        RaftBottomTabItemPayload("members", "Members", RaftLucideIconName.Users),
                                    ),
                                onSelect = {},
                                modifier = Modifier.fillMaxHeight(),
                                bottomItems = listOf(RaftBottomTabItemPayload("settings", "Settings", RaftLucideIconName.Settings)),
                            )
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                RaftAdaptiveListDetailSplit(
                                    kind = RaftListPaneKind.List,
                                    widths = widths,
                                    dualPane = true,
                                    active = true,
                                    hasSelection = true,
                                    detailBottomInset = 0.dp,
                                    listPane = { ActivityListFixture() },
                                    detailPane = { modifier ->
                                        RaftWorkspaceDetailGrid(
                                            modifier = modifier,
                                            wide = true,
                                            panes = { RaftProfileColumnPanes() },
                                            onPanesChange = {},
                                            columnOpen = false,
                                            tabSelected = true,
                                            active = true,
                                            sideThreadColumn = null,
                                            reportHost = true,
                                            onShown = {},
                                            artifact = { artifact },
                                            onArtifactChange = {},
                                            artifactOpen = true,
                                            page = { _, _ -> ArtifactPageFixture() },
                                        ) {
                                            ThreadFixture()
                                        }
                                    },
                                    // The shell's Activity: the list at most half the row while an item is open.
                                    maxShare = 0.5f,
                                )
                            }
                        }
                    }
                }
            }
        }

    @Composable
    private fun ActivityListFixture() {
        Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            RaftTopPageHeader(title = "Activity", subtitle = "447 in progress · 454 unread", applyTopSafeArea = false)
            listOf(
                "wg-testbed-app" to "Artea: testbed's Android cloud phone: the input method only types English",
                "techhorizonlabs-x-Raft" to "Gwynne: Noted, and thank you for correcting it",
                "proj-mobile-release" to "Hands: Raft Android · Google Play · In review 1.26.0",
                "ops-support" to "Cat: Mail · Request · Slavik Permyakov",
                "monitoring-alerts" to "ZZAlertManager: FIRING Telescope",
            ).forEach { (title, line) -> RaftListRow(title = title, subtitle = line, onClick = {}) }
        }
    }

    /** The open thread, the split's detail: its head (a long title, Back) and a few replies over the composer. */
    @Composable
    private fun ThreadFixture() {
        Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            RaftTopPageHeader(
                title = "@xxchan: I think something like the teleprompter would work for the talk",
                subtitle = "@Tenny · 106 replies",
                onBack = {},
                applyTopSafeArea = false,
            )
            Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                RaftText("Tenny", style = RaftTheme.typography.headline)
                RaftText("COSCon talk · teleprompter (phone version), v1, updated the day before yesterday.")
                RaftText(
                    "A correction: the page's \"keep the screen on\" is blocked by the artifact sandbox and has no effect. " +
                        "Before going on stage, turn auto-lock off in the phone's settings. Everything else works at phone size.",
                    modifier = Modifier.padding(top = 12.dp),
                )
                // A dark image attachment, wider than what shows: full-bleed, cut hard at the column's edge.
                RaftMessageImageGallery(
                    images = listOf(RaftMessageImageDimensions(1600, 900)),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1B2430)))
                }
            }
            Box(
                Modifier.fillMaxWidth().padding(12.dp).height(72.dp)
                    .border(1.dp, RaftColor.BorderSeam, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                RaftText("Reply in thread", color = RaftColor.ForegroundHint)
            }
        }
    }

    /** The Artifact viewer in its column with the App's own bar (the page doesn't draw one), over the slide's text. */
    @Composable
    private fun ArtifactPageFixture() {
        RaftArtifactViewerFrame(
            title = "COSCon talk · teleprompter (phone version)",
            onBack = {},
            topSafeAreaOverride = 0.dp,
            inColumn = true,
        ) {
            Column(Modifier.fillMaxSize().background(RaftColor.TextBlack).padding(20.dp)) {
                RaftText("Surfaces · Raft App   11 / 17", color = RaftColor.SurfaceWhite, style = RaftTheme.typography.headline)
                RaftText(
                    "Behind Stamp is a more general mechanism, called Raft App. We wanted Raft to be an open ecosystem " +
                        "early on: outside systems let people and agents sign in with their Raft identity, Login with Raft.",
                    color = RaftColor.SurfaceWhite,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Box(Modifier.weight(1f))
                RaftText("‹ Previous      A-      A+      Next ›", color = RaftColor.SurfaceWhite)
            }
        }
    }
}
