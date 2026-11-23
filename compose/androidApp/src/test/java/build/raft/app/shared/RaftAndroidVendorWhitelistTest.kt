package build.raft.app.shared

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import build.raft.app.data.RaftBackgroundWhitelistGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftAndroidVendorWhitelistTest {
    @Test
    fun manufacturerAndBrandMapToExpectedVendorAndGuide() {
        val cases =
            listOf(
                Triple("HUAWEI", "HUAWEI", AndroidOemVendor.Huawei to RaftBackgroundWhitelistGuide.HuaweiAppLaunch),
                Triple("HONOR", "HONOR", AndroidOemVendor.Honor to RaftBackgroundWhitelistGuide.HuaweiAppLaunch),
                Triple("HUAWEI", "HONOR", AndroidOemVendor.Honor to RaftBackgroundWhitelistGuide.HuaweiAppLaunch),
                Triple("hihonor", "hihonor", AndroidOemVendor.Honor to RaftBackgroundWhitelistGuide.HuaweiAppLaunch),
                Triple("LENOVO", "Lenovo", AndroidOemVendor.LenovoZui to RaftBackgroundWhitelistGuide.LenovoZui),
                Triple("ZUK", "zuk", AndroidOemVendor.LenovoZui to RaftBackgroundWhitelistGuide.LenovoZui),
                Triple("Xiaomi", "Redmi", AndroidOemVendor.Xiaomi to RaftBackgroundWhitelistGuide.VendorSettings),
                Triple("Xiaomi", "POCO", AndroidOemVendor.Xiaomi to RaftBackgroundWhitelistGuide.VendorSettings),
                Triple("OPPO", "realme", AndroidOemVendor.Oppo to RaftBackgroundWhitelistGuide.VendorSettings),
                Triple("OnePlus", "OnePlus", AndroidOemVendor.Oppo to RaftBackgroundWhitelistGuide.VendorSettings),
                Triple("vivo", "iQOO", AndroidOemVendor.Vivo to RaftBackgroundWhitelistGuide.VendorSettings),
                Triple("samsung", "samsung", AndroidOemVendor.Samsung to RaftBackgroundWhitelistGuide.VendorSettings),
                Triple("ZTE", "nubia", AndroidOemVendor.Zte to RaftBackgroundWhitelistGuide.VendorSettings),
                Triple("Google", "google", AndroidOemVendor.Generic to RaftBackgroundWhitelistGuide.AppDetails),
                Triple("", "", AndroidOemVendor.Generic to RaftBackgroundWhitelistGuide.AppDetails),
                Triple(null, null, AndroidOemVendor.Generic to RaftBackgroundWhitelistGuide.AppDetails),
            )
        for ((manufacturer, brand, expected) in cases) {
            val vendor = RaftAndroidVendorWhitelist.resolveVendor(manufacturer, brand)
            assertEquals("$manufacturer/$brand vendor", expected.first, vendor)
            assertEquals("$manufacturer/$brand guide", expected.second, RaftAndroidVendorWhitelist.guideFor(vendor))
        }
    }

    @Test
    fun targetsForEveryVendorFallsBackToBatteryOptimizationSettingsThenAppDetailsAndNeverUsesDirectPrompt() {
        for (vendor in AndroidOemVendor.entries) {
            val targets = RaftAndroidVendorWhitelist.targetsFor(vendor)
            assertTrue("$vendor should have at least two fallback targets", targets.size >= 2)
            assertEquals(
                "$vendor penultimate target should be battery_optimization_settings",
                RaftAndroidVendorWhitelist.TARGET_BATTERY_OPTIMIZATION_SETTINGS,
                targets[targets.size - 2].id,
            )
            assertEquals(
                "$vendor battery_optimization_settings action",
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                targets[targets.size - 2].action,
            )
            assertEquals(
                "$vendor final target should be app_details",
                RaftAndroidVendorWhitelist.TARGET_APP_DETAILS,
                targets.last().id,
            )
            assertEquals(
                "$vendor app_details action",
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                targets.last().action,
            )
            assertTrue(
                "$vendor targetsFor must never invoke ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                targets.none { it.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS },
            )
        }
    }

    @Test
    fun batteryOptimizationTargetsGateDirectPromptOnAllowDirectPromptFlag() {
        val direct = RaftAndroidVendorWhitelist.batteryOptimizationTargets(allowDirectPrompt = true)
        assertEquals(
            listOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            direct.map { it.action },
        )

        val play = RaftAndroidVendorWhitelist.batteryOptimizationTargets(allowDirectPrompt = false)
        assertEquals(
            listOf(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            play.map { it.action },
        )
    }

    @Test
    fun launchFirstAvailableFallsThroughRuntimeExceptionsToBatteryOptimizationSettingsThenAppDetails() {
        val targets = RaftAndroidVendorWhitelist.targetsFor(AndroidOemVendor.LenovoZui)
        val attempted = mutableListOf<String>()

        // 1st throws ActivityNotFoundException, 2nd SecurityException, 3rd IllegalStateException, 4th succeeds.
        val launchedFourth =
            RaftAndroidVendorWhitelist.launchFirstAvailable(targets) { target ->
                attempted += target.id
                when (attempted.size) {
                    1 -> throw ActivityNotFoundException("missing activity")
                    2 -> throw SecurityException("not exported")
                    3 -> throw IllegalStateException("oem activity error")
                    else -> Unit
                }
            }
        assertEquals(targets[3].id, launchedFourth)
        assertEquals(targets.take(4).map { it.id }, attempted)

        // All OEM targets fail -> falls back to battery_optimization_settings BEFORE app_details.
        val batteryFallbackAttempts = mutableListOf<String>()
        val launchedBatterySettings =
            RaftAndroidVendorWhitelist.launchFirstAvailable(targets) { target ->
                batteryFallbackAttempts += target.id
                if (target.id != RaftAndroidVendorWhitelist.TARGET_BATTERY_OPTIMIZATION_SETTINGS) {
                    throw IllegalArgumentException("bad oem intent")
                }
            }
        assertEquals(RaftAndroidVendorWhitelist.TARGET_BATTERY_OPTIMIZATION_SETTINGS, launchedBatterySettings)
        assertEquals(targets.dropLast(1).map { it.id }, batteryFallbackAttempts)

        // OEM targets AND battery_optimization_settings fail -> falls back to app_details.
        val fallbackAttempts = mutableListOf<String>()
        val launchedFallback =
            RaftAndroidVendorWhitelist.launchFirstAvailable(targets) { target ->
                fallbackAttempts += target.id
                if (target.id != RaftAndroidVendorWhitelist.TARGET_APP_DETAILS) {
                    throw IllegalArgumentException("unavailable")
                }
            }
        assertEquals(RaftAndroidVendorWhitelist.TARGET_APP_DETAILS, launchedFallback)
        assertEquals(targets.map { it.id }, fallbackAttempts)

        // Everything fails -> returns null without throwing.
        val noneLaunched =
            RaftAndroidVendorWhitelist.launchFirstAvailable(targets) {
                throw SecurityException("blocked")
            }
        assertNull(noneLaunched)
    }
}
