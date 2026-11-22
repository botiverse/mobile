package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                // As the app composes Home: the head runs edge to edge.
                outerPadding = 0.dp,
            )
        }

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
                    bannerTopInset = raftMinBuildGateBannerTopInset(),
                    bannerHorizontalInsets = raftMinBuildGateBannerHorizontalInsets(),
                    content = page,
                )
            }
        }
}
