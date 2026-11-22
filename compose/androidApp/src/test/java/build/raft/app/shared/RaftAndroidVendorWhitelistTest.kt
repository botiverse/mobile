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
    fun launchFirstAvailableFallsThroughRuntimeExceptionsToAppDetails() {
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

        // All OEM targets fail -> falls back to app_details.
        val fallbackAttempts = mutableListOf<String>()
        val launchedFallback =
            RaftAndroidVendorWhitelist.launchFirstAvailable(targets) { target ->
                fallbackAttempts += target.id
                if (target.id != "app_details") throw IllegalArgumentException("bad oem intent")
            }
        assertEquals("app_details", launchedFallback)
        assertEquals(targets.map { it.id }, fallbackAttempts)

        // Everything fails -> returns null without throwing.
        val noneLaunched =
            RaftAndroidVendorWhitelist.launchFirstAvailable(targets) {
                throw SecurityException("blocked")
            }
        assertNull(noneLaunched)
    }
}
