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
    fun everyVendorEndsWithAppDetailsFallbackAndLenovoAndHuaweiKeepExpectedTargets() {
        for (vendor in AndroidOemVendor.entries) {
            val targets = RaftAndroidVendorWhitelist.targetsFor(vendor)
            assertTrue("vendor=$vendor must have at least fallback", targets.isNotEmpty())
            val last = targets.last()
            assertEquals("vendor=$vendor fallback id", "app_details", last.id)
            assertEquals("vendor=$vendor fallback action", Settings.ACTION_APPLICATION_DETAILS_SETTINGS, last.action)
            assertTrue("vendor=$vendor fallback packageUri", last.packageUri)
        }

        val genericTargets = RaftAndroidVendorWhitelist.targetsFor(AndroidOemVendor.Generic)
        assertEquals(listOf("app_details"), genericTargets.map { it.id })

        val huaweiTargets = RaftAndroidVendorWhitelist.targetsFor(AndroidOemVendor.Huawei)
        assertEquals(
            listOf(
                "huawei_startup_normal",
                "huawei_app_startup_action",
                "huawei_bootapp_action",
                "huawei_startup_control",
                "huawei_protect",
                "app_details",
            ),
            huaweiTargets.map { it.id },
        )

        val honorTargets = RaftAndroidVendorWhitelist.targetsFor(AndroidOemVendor.Honor)
        assertTrue(honorTargets.first().componentPackage == "com.hihonor.systemmanager")
        assertTrue(honorTargets.any { it.componentPackage == "com.huawei.systemmanager" })
        assertEquals("app_details", honorTargets.last().id)

        val lenovoTargets = RaftAndroidVendorWhitelist.targetsFor(AndroidOemVendor.LenovoZui)
        assertEquals(
            listOf(
                "com.zui.safecenter/com.zui.safecenter.startup.StartupAppListActivity",
                "com.zui.safecenter/com.zui.safecenter.autorun.AutoRunActivity",
                "com.zui.safecenter/com.zui.safecenter.MainTabActivity",
                "com.zui.safecenter/com.zui.safecenter.MainActivity",
                "com.lenovo.security/com.lenovo.security.purebackground.PureBackgroundActivity",
                "com.lenovo.performancecenter/com.lenovo.performancecenter.core.ui.switches.SwitchesActivity",
            ),
            lenovoTargets.dropLast(1).map { "${it.componentPackage}/${it.componentClass}" },
        )
        assertEquals("app_details", lenovoTargets.last().id)
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

    @Test
    fun targetIntentsAlwaysIncludeNewTaskAndPopulateComponentActionOrPackageUri() {
        val pkg = "build.raft.app"

        val zuiIntent = RaftAndroidVendorWhitelist.targetsFor(AndroidOemVendor.LenovoZui).first().toIntent(pkg)
        assertTrue((zuiIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertEquals("com.zui.safecenter", zuiIntent.component?.packageName)
        assertEquals("com.zui.safecenter.startup.StartupAppListActivity", zuiIntent.component?.className)

        val xiaomiPowerkeeper =
            RaftAndroidVendorWhitelist.targetsFor(AndroidOemVendor.Xiaomi)
                .first { it.id == "xiaomi_powerkeeper_app" }
                .toIntent(pkg, appLabel = "Raft")
        assertTrue((xiaomiPowerkeeper.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertEquals(pkg, xiaomiPowerkeeper.getStringExtra("package_name"))
        assertEquals("Raft", xiaomiPowerkeeper.getStringExtra("package_label"))

        val fallbackIntent = RaftAndroidVendorWhitelist.targetsFor(AndroidOemVendor.Generic).single().toIntent(pkg)
        assertTrue((fallbackIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, fallbackIntent.action)
        assertEquals("package:$pkg", fallbackIntent.data?.toString())
    }
}
