package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsBillingSnapshot
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * iOS Plan & Billing, view-only (artin 11-02), on the phone: laid out like the web page
 * (PlanSectionContent) with every purchase entry left out. Free (the plan line, the upload
 * meter, message history and members) and Pro (seats meter, the Billing card), light and dark.
 * The wide pane is SettingsWideSplitScreenshotTest. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-mdpi")
class SettingsBillingViewOnlyScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun freeLight() = capture("settings.billing.ios.free", RaftAppAppearance.Light, settingsBillingViewOnlyFreeFixture)

    @Test
    fun freeDark() = capture("settings.billing.ios.free.dark", RaftAppAppearance.Dark, settingsBillingViewOnlyFreeFixture)

    @Test
    fun proLight() = capture("settings.billing.ios.pro", RaftAppAppearance.Light, settingsBillingViewOnlyProFixture)

    @Test
    fun proDark() = capture("settings.billing.ios.pro.dark", RaftAppAppearance.Dark, settingsBillingViewOnlyProFixture)

    private fun capture(name: String, appearance: RaftAppAppearance, billing: ScreenLoadState<SettingsBillingSnapshot>) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            PhonePage(billing)
        }

    @Composable
    private fun PhonePage(billing: ScreenLoadState<SettingsBillingSnapshot>) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize().background(RaftColor.SurfaceCream)) {
                    RaftTopPageHeader(
                        title = "Plan & Billing",
                        iconName = RaftLucideIconName.CreditCard,
                        backgroundColor = RaftColor.SurfaceWhite,
                        onBack = {},
                    )
                    SettingsBillingViewOnlyContent(
                        billing = billing,
                        sessionPlan = null,
                        onRetry = {},
                        nowEpochMillis = SettingsBillingViewOnlyFixtureNow,
                    )
                }
            }
        }
    }
}
