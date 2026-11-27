package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.shell.LocalRaftAppShellTopBar
import build.raft.app.shell.RaftAppShellSurface
import build.raft.app.shell.RaftAppShellTopBar
import build.raft.app.shell.RaftAppShellTopBarState
import build.raft.app.shell.RaftBottomTabItemPayload
import build.raft.app.shell.RaftNavigationRailTabBar
import build.raft.app.shell.rememberRaftAppShellScope
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The minimum build gate layer on a real composition ([RaftMinBuildGateFrame]): a block takes every
 * touch and hides the page from accessibility, keeps Update / Send feedback / Sign out working, and
 * goes away when the decision does (flag off); a banner leaves the page usable and dismisses, and
 * sits above the page (Home's head and tabs, the wide top bar) rather than over it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class RaftMinBuildGateUiTest {
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

    private val url = "https://example.test/raft.apk"

    @Test
    fun blockTakesEveryTouchUntilTheDecisionLifts() = runRobolectricComposeUiTest {
        var decision by mutableStateOf<RaftMinBuildGateDecision>(RaftMinBuildGateDecision.Block(12_000_000L, 12_600_000L, url))
        var pageTaps = 0
        val opened = mutableListOf<String>()
        var feedback = 0
        var signOuts = 0
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftMinBuildGateFrame(
                    decision = decision,
                    dismissedBannerThreshold = null,
                    signingOut = false,
                    onUpdate = { opened += it },
                    onSendFeedback = { feedback++ },
                    onSignOut = { signOuts++ },
                    onDismissBanner = {},
                ) {
                    Box(Modifier.fillMaxSize().testTag(PAGE).clickable { pageTaps++ })
                }
            }
        }
        waitForIdle()

        onNodeWithTag(RAFT_MIN_BUILD_GATE_BLOCK_TAG).assertExists()
        // Hidden from accessibility services while blocked.
        onNodeWithTag(PAGE).assertDoesNotExist()
        onNodeWithTag(RAFT_MIN_BUILD_GATE_BLOCK_TAG).performTouchInput { click(Offset(8f, 8f)) }
        waitForIdle()
        assertEquals(0, pageTaps, "a touch on the block reached the page")

        onNodeWithText("Please update to the latest version").assertExists()
        onNodeWithText("Update").performClick()
        onNodeWithText("Send feedback").performClick()
        onNodeWithText("Sign out").performClick()
        waitForIdle()
        assertEquals(listOf(url), opened)
        assertEquals(1, feedback)
        assertEquals(1, signOuts)

        // Flag off: the block goes and the page is usable again.
        runOnIdle { decision = RaftMinBuildGateDecision.None }
        onNodeWithTag(RAFT_MIN_BUILD_GATE_BLOCK_TAG).assertDoesNotExist()
        onNodeWithTag(PAGE).performClick()
        waitForIdle()
        assertEquals(1, pageTaps)
    }

    @Test
    fun bannerLeavesThePageUsableAndDismisses() = runRobolectricComposeUiTest {
        val threshold = 12_000_000L
        var dismissed by mutableStateOf<Long?>(null)
        var pageTaps = 0
        val opened = mutableListOf<String>()
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftMinBuildGateFrame(
                    decision = RaftMinBuildGateDecision.Banner(threshold, url),
                    dismissedBannerThreshold = dismissed,
                    signingOut = false,
                    onUpdate = { opened += it },
                    onSendFeedback = {},
                    onSignOut = {},
                    onDismissBanner = { dismissed = it },
                ) {
                    Box(Modifier.fillMaxSize().testTag(PAGE).clickable { pageTaps++ })
                }
            }
        }
        waitForIdle()

        onNodeWithTag(RAFT_MIN_BUILD_GATE_BANNER_TAG).assertExists()
        onNodeWithTag(PAGE).performClick()
        onNodeWithText("Update").performClick()
        waitForIdle()
        assertEquals(1, pageTaps)
        assertEquals(listOf(url), opened)

        onNodeWithText("Dismiss").performClick()
        waitForIdle()
        assertEquals(threshold, dismissed)
        onNodeWithTag(RAFT_MIN_BUILD_GATE_BANNER_TAG).assertDoesNotExist()
    }

    /**
     * The wide layout: the band sits above the app shell's top bar (server switcher, search), which
     * starts right under it and keeps taking taps.
     */
    @Test
    @Config(qualifiers = "w1024dp-h600dp-mdpi")
    fun bannerSitsAboveTheWideTopBar() = runRobolectricComposeUiTest {
        var workspaceTaps = 0
        var searchTaps = 0
        setContent {
            MinBuildGateTestPage(windowId = "test:min-build-gate-wide") {
                RaftTheme(appearance = RaftAppearance.Light) {
                    RaftMinBuildGateFrame(
                        decision = RaftMinBuildGateDecision.Banner(12_000_000L, url),
                        dismissedBannerThreshold = null,
                        signingOut = false,
                        onUpdate = {},
                        onSendFeedback = {},
                        onSignOut = {},
                        onDismissBanner = {},
                        bannerTopInset = raftMinBuildGateBannerTopInset(),
                        bannerHorizontalInsets = raftMinBuildGateBannerHorizontalInsets(),
                    ) {
                        MinBuildGateWideShell(onWorkspaceClick = { workspaceTaps++ }, onSearchClick = { searchTaps++ })
                    }
                }
            }
        }
        waitForIdle()

        val band = onNodeWithTag(RAFT_MIN_BUILD_GATE_BAND_TAG).getBoundsInRoot()
        val workspace = highestNodeWithText(SERVER).getBoundsInRoot()
        val search = onNodeWithText("Search", useUnmergedTree = true).getBoundsInRoot()
        assertFalse(band.overlaps(workspace), "the banner covers the server switcher: band=$band workspace=$workspace")
        assertFalse(band.overlaps(search), "the banner covers the search field: band=$band search=$search")
        assertTrue(workspace.top - band.bottom < STATUS_BAR, "the top bar pads the status bar again under the band")

        highestNodeWithText(SERVER).performClick()
        onNodeWithText("Search", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, workspaceTaps)
        assertEquals(1, searchTaps)
    }

    /** The head's server switcher: the bottom bar's server tab carries the server name too. */
    private fun ComposeUiTest.highestNodeWithText(text: String): SemanticsNodeInteraction {
        val nodes = onAllNodesWithText(text)
        val count = nodes.fetchSemanticsNodes().size
        return (0 until count).map { nodes[it] }.minBy { it.getBoundsInRoot().top.value }
    }

    private fun DpRect.overlaps(other: DpRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    private companion object {
        const val PAGE = "page"
        const val SERVER = "Botiverse"
        val STATUS_BAR = 24.dp
    }
}

/** A page with a 24dp status bar (its own window id: the inset map is process-wide). */
@Composable
internal fun MinBuildGateTestPage(windowId: String, content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    val pager =
        remember {
            object : Pager() {}.apply {
                pageData =
                    PageData(
                        JSONObject().apply {
                            put("slockWindowId", windowId)
                            // Pixels: 24dp at the test's density.
                            put("statusBarHeight", (24 * density).toString())
                        },
                    )
            }
        }
    CompositionLocalProvider(
        LocalActivity provides RaftPageActivity(pager),
        LocalRaftAppShellTopBar provides remember { RaftAppShellTopBarState() },
    ) {
        RaftDesignSystemTheme(content)
    }
}

/** The wide app shell as the app composes it: the top bar over the rail and the content. */
@Composable
internal fun MinBuildGateWideShell(onWorkspaceClick: () -> Unit = {}, onSearchClick: () -> Unit = {}) {
    val shellScope = rememberRaftAppShellScope()
    val server = RaftServerSwitcherItem(id = "botiverse", name = "Botiverse", slug = "botiverse")
    RaftAppShellSurface(
        shellScope = shellScope,
        bottomTabs = emptyList(),
        onBottomTabSelected = {},
        showBottomTabs = false,
        leadingNavigation = {
            RaftNavigationRailTabBar(
                items =
                    listOf(
                        RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.MessageSquare, selected = true),
                        RaftBottomTabItemPayload("members", "Members", RaftLucideIconName.Users),
                    ),
                onSelect = {},
                modifier = Modifier.fillMaxHeight(),
            )
        },
        topNavigation = {
            RaftAppShellTopBar(
                currentServer = server,
                servers = listOf(server),
                serverMenuOpen = false,
                onWorkspaceClick = onWorkspaceClick,
                onServerPick = {},
                searchLabel = "Search",
                onSearchClick = onSearchClick,
                windowWidth = 1024.dp,
                topSafeArea = shellScope.topSafeArea,
            )
        },
    ) {
        Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite))
    }
}
