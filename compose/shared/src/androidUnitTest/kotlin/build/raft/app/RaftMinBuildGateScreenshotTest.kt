package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.json.JSONObject
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxHeight
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the minimum build gate: the full-screen block ("Please update to the latest
 * version", Update, Send feedback, Sign out) and the dismissible banner in its band above Home
 * (phone) and above the wide top bar. Light and dark; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class RaftMinBuildGateScreenshotTest {
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

    private val block = RaftMinBuildGateDecision.Block(12_000_000L, 12_600_000L, "https://example.test/raft.apk")
    private val banner = RaftMinBuildGateDecision.Banner(12_000_000L, "https://example.test/raft")

    @Test
    fun blockLight() = capture("app.min-build-gate.block", RaftAppAppearance.Light, block)

    @Test
    fun blockDark() = capture("app.min-build-gate.block.dark", RaftAppAppearance.Dark, block)

    // The banner over Home with a 24dp status bar (#103): a band above the page that clears the
    // status bar; Home's head (server switcher, bell) starts under it, nothing of it covered.
    @Test
    fun bannerLight() = captureHome("app.min-build-gate.banner", RaftAppAppearance.Light)

    @Test
    fun bannerDark() = captureHome("app.min-build-gate.banner.dark", RaftAppAppearance.Dark)

    // The wide layout: the band above the app shell's top bar.
    @Test
    @Config(qualifiers = "w1024dp-h600dp-mdpi")
    fun bannerWideLight() = captureWide("app.min-build-gate.banner.wide", RaftAppAppearance.Light)

    @Test
    @Config(qualifiers = "w1024dp-h600dp-mdpi")
    fun bannerWideDark() = captureWide("app.min-build-gate.banner.wide.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance, decision: RaftMinBuildGateDecision) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
        ) {
            RaftDesignSystemTheme {
                RaftMinBuildGateFrame(
                    decision = decision,
                    dismissedBannerThreshold = null,
                    signingOut = false,
                    onUpdate = {},
                    onSendFeedback = {},
                    onSignOut = {},
                    onDismissBanner = {},
                ) {
                    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas))
                }
            }
        }

    private fun captureHome(name: String, appearance: RaftAppAppearance) =
        captureBannerOver(name, appearance) {
            RaftStructuralHomeShell(
                currentServerName = "Botiverse",
                currentServerId = "botiverse",
                servers = listOf(RaftServerSwitcherItem(id = "botiverse", name = "Botiverse", slug = "botiverse")),
                showBottomBar = true,
                outerPadding = 0.dp,
            )
        }

    private fun captureWide(name: String, appearance: RaftAppAppearance) = captureBannerOver(name, appearance) { MinBuildGateWideShell() }

    private fun captureBannerOver(name: String, appearance: RaftAppAppearance, page: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
        ) {
            MinBuildGateTestPage(windowId = "test:min-build-gate-screenshot") {
                RaftMinBuildGateFrame(
                    decision = banner,
                    dismissedBannerThreshold = null,
                    signingOut = false,
                    onUpdate = {},
                    onSendFeedback = {},
                    onSignOut = {},
                    onDismissBanner = {},
                    content = page,
                )
            }
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
