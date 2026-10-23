package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.RaftListPaneKind
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin task #20: the wide list + detail split ([RaftAdaptiveListDetailSplit]) beside the rail at
 * each column kind's default width: Activity (380 list, empty detail beside it), Search before and
 * after a result is picked (full width, then the 380 list beside the result) and Home (240
 * sidebar). Fixed fixture rows; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class AdaptiveListDetailSplitScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun activityLight() = capture("shell.split.activity", RaftAppAppearance.Light, Scene.Activity)

    @Test
    fun activityDark() = capture("shell.split.activity.dark", RaftAppAppearance.Dark, Scene.Activity)

    @Test
    fun searchNothingPickedLight() = capture("shell.split.search-empty", RaftAppAppearance.Light, Scene.SearchEmpty)

    @Test
    fun searchPickedLight() = capture("shell.split.search-picked", RaftAppAppearance.Light, Scene.SearchPicked)

    @Test
    fun homeSidebarLight() = capture("shell.split.home", RaftAppAppearance.Light, Scene.Home)

    private enum class Scene(
        val railKey: String,
        val kind: RaftListPaneKind,
        val title: String,
        val picked: Boolean,
        val fillWhenNothingPicked: Boolean,
    ) {
        Activity("activity", RaftListPaneKind.List, "Activity", picked = false, fillWhenNothingPicked = false),
        SearchEmpty("search", RaftListPaneKind.List, "Search", picked = false, fillWhenNothingPicked = true),
        SearchPicked("search", RaftListPaneKind.List, "Search", picked = true, fillWhenNothingPicked = true),
        Home("home", RaftListPaneKind.Sidebar, "Botiverse", picked = true, fillWhenNothingPicked = false),
    }

    private fun capture(name: String, appearance: RaftAppAppearance, scene: Scene) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Row(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                        RaftNavigationRailTabBar(
                            items =
                                listOf(
                                    RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.House, selected = scene.railKey == "home"),
                                    RaftBottomTabItemPayload("activity", "Activity", RaftLucideIconName.Activity, selected = scene.railKey == "activity"),
                                    RaftBottomTabItemPayload("tasks", "Tasks", RaftLucideIconName.SquareCheckBig),
                                    RaftBottomTabItemPayload("search", "Search", RaftLucideIconName.Search, selected = scene.railKey == "search"),
                                    RaftBottomTabItemPayload("members", "Members", RaftLucideIconName.Users),
                                ),
                            onSelect = {},
                            modifier = Modifier.fillMaxHeight(),
                            header = RaftNavigationRailHeader(key = "server:1", label = "Botiverse"),
                            bottomItems =
                                listOf(RaftBottomTabItemPayload("settings", "Settings", RaftLucideIconName.Settings)),
                        )
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            RaftAdaptiveListDetailSplit(
                                kind = scene.kind,
                                widths = remember { RaftListPaneWidths(store = null) },
                                dualPane = true,
                                active = true,
                                hasSelection = scene.picked,
                                detailBottomInset = 0.dp,
                                listPane = { FixtureList(scene) },
                                detailPane = { modifier -> FixtureDetail(modifier, scene.picked) },
                                fillListWhenNothingPicked = scene.fillWhenNothingPicked,
                            )
                        }
                    }
                }
            }
        }

    @Composable
    private fun FixtureList(scene: Scene) {
        Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            RaftText(
                scene.title,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                style = RaftTheme.typography.headline,
            )
            val rows =
                listOf(
                    Triple("triage", "44m", "You: migrating an agent while picking the machine"),
                    Triple("infra-hosts", "1h", "IT-hetzer-host: new critical alert on host 3"),
                    Triple("proj-workflow", "4h", "You: summarise which ones ship now and which wait"),
                    Triple("design", "5h", "Ada: the list column now follows the web widths"),
                    Triple("general", "1d", "Scout: weekly notes are up"),
                )
            rows.forEachIndexed { index, (title, meta, description) ->
                RaftListRow(
                    title = if (scene.kind == RaftListPaneKind.Sidebar) "# $title" else title,
                    meta = meta.takeIf { scene.kind == RaftListPaneKind.List },
                    description = description.takeIf { scene.kind == RaftListPaneKind.List },
                    descriptionMaxLines = 1,
                    selected = scene.picked && index == 3,
                    divider = scene.kind == RaftListPaneKind.List,
                    onClick = {},
                )
            }
        }
    }

    @Composable
    private fun FixtureDetail(modifier: Modifier, picked: Boolean) {
        if (picked) {
            Column(modifier.background(RaftColor.SurfaceWhite)) {
                RaftText(
                    "# design",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = RaftTheme.typography.headline,
                )
                Box(Modifier.weight(1f).padding(16.dp), contentAlignment = Alignment.BottomStart) {
                    RaftText("Ada: the list column now follows the web widths")
                }
                Box(Modifier.height(56.dp))
            }
        } else {
            RaftEmptyState(
                title = "Select a conversation",
                modifier = modifier.fillMaxSize(),
                iconName = RaftLucideIconName.MessageSquare,
            )
        }
    }
}
