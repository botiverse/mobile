package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftBackgroundKillNotice
import build.raft.app.data.RaftBackgroundWhitelistGuide
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Android background-kill notice ("Raft was stopped in the background") at the top of the
 * page as the phone shell draws it, on a 360dp phone directing the user to Notification settings:
 * Don't show again and Dismiss (ghost) before Open settings, wrapping onto a second line where the
 * three do not fit on one. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h400dp-xxhdpi")
class RaftBackgroundKillNoticeBannerScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val previousLocale = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun huaweiEnglish() = capture("shell.background-kill-notice.huawei", AppLocale.English)

    private fun capture(name: String, locale: AppLocale) {
        StringDesc.localeType = locale.stringDescLocaleType
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                    RaftBackgroundKillNoticeBanner(
                        notice =
                            RaftBackgroundKillNotice(
                                exitTimestampMillis = 1_793_322_000_000L,
                                guide = RaftBackgroundWhitelistGuide.HuaweiAppLaunch,
                            ),
                        onOpenSettings = {},
                        onDismiss = {},
                        onDismissForever = {},
                        onShown = {},
                    )
                }
            }
        }
    }
}
