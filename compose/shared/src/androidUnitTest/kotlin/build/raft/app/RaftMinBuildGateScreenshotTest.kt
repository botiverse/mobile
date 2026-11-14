package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
 * version", Update, Send feedback, Sign out) and the dismissible banner over a page. Light and
 * dark; verify / record as [RaftRobolectricScreenshot].
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

    @Test
    fun bannerLight() = capture("app.min-build-gate.banner", RaftAppAppearance.Light, banner)

    @Test
    fun bannerDark() = capture("app.min-build-gate.banner.dark", RaftAppAppearance.Dark, banner)

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
}
