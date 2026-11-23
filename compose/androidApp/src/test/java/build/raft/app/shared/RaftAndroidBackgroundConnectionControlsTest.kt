package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.provider.Settings
import build.raft.app.data.RaftBackgroundWhitelistGuide
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies the unified Android keep-alive launcher and Play manifest compliance (#proj-mobile:#325):
 * - Direct builds declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and use the direct exemption prompt
 *   before falling back to system battery optimization settings and App Info.
 * - Play builds strip `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and NEVER invoke
 *   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` from either entry point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftAndroidBackgroundConnectionControlsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun manifestsKeepBatteryOptimizationPermissionSplitForPlayCompliance() {
        val main = File("src/main/AndroidManifest.xml").readText()
        val play = File("src/play/AndroidManifest.xml").readText()
        assertTrue(main.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertTrue(
            Regex(
                """android:name="android\.permission\.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"\s+tools:node="remove""""
            ).containsMatchIn(play)
        )
    }

    @Test
    fun permissionDeclarationCheckReflectsMergedManifestPermissions() {
        val pm = shadowOf(context.packageManager)
        val infoWithPerm =
            PackageInfo().apply {
                packageName = context.packageName
                requestedPermissions = arrayOf(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            }
        pm.installPackage(infoWithPerm)
        assertTrue(isBatteryOptimizationPermissionDeclared(context))

        val infoWithoutPerm =
            PackageInfo().apply {
                packageName = context.packageName
                requestedPermissions = arrayOf(Manifest.permission.INTERNET)
            }
        pm.installPackage(infoWithoutPerm)
        assertFalse(isBatteryOptimizationPermissionDeclared(context))
    }

    @Test
    fun directBuildRequestBatteryOptimizationExemptionFollowsUnifiedFallbackOrder() {
        val launchedActions = mutableListOf<String?>()
        val controls =
            RaftAndroidBackgroundConnectionControls(
                context = context,
                batteryOptimizationExemptProvider = { false },
                batteryOptimizationPermissionDeclaredProvider = { true },
                noPlayServicesFallbackProvider = { true },
                vendorProvider = { AndroidOemVendor.Huawei },
                intentLauncher = { intent ->
                    launchedActions += intent.action
                    if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                        throw ActivityNotFoundException("unavailable: ${intent.action}")
                    }
                },
            )

        assertTrue(controls.isOffered())
        assertTrue(controls.canRequestBatteryOptimizationExemption())
        assertEquals(RaftBackgroundWhitelistGuide.HuaweiAppLaunch, controls.whitelistGuide())

        controls.requestBatteryOptimizationExemption()

        assertEquals(
            listOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            launchedActions,
        )
    }

    @Test
    fun playBuildNeverInvokesDirectBatteryPromptOnEitherEntryPoint() {
        val directCallIntents = mutableListOf<Intent>()
        val playControls =
            RaftAndroidBackgroundConnectionControls(
                context = context,
                batteryOptimizationExemptProvider = { false },
                batteryOptimizationPermissionDeclaredProvider = { false },
                noPlayServicesFallbackProvider = { false },
                vendorProvider = { AndroidOemVendor.LenovoZui },
                intentLauncher = { intent ->
                    directCallIntents += intent
                    if (intent.action != Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) {
                        throw SecurityException("oem blocked")
                    }
                },
            )

        assertFalse(playControls.isOffered())
        assertFalse(playControls.canRequestBatteryOptimizationExemption())
        assertEquals(RaftBackgroundWhitelistGuide.LenovoZui, playControls.whitelistGuide())

        // Even if requestBatteryOptimizationExemption() is called on a Play build, it skips the direct prompt.
        playControls.requestBatteryOptimizationExemption()
        assertEquals(
            listOf(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            directCallIntents.map { it.action },
        )
        assertTrue(directCallIntents.none { it.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS })

        // openVendorBackgroundSettings() tries OEM intents -> battery optimization settings -> App Info.
        val vendorIntents = mutableListOf<Intent>()
        val playOemControls =
            RaftAndroidBackgroundConnectionControls(
                context = context,
                batteryOptimizationExemptProvider = { false },
                batteryOptimizationPermissionDeclaredProvider = { false },
                noPlayServicesFallbackProvider = { false },
                vendorProvider = { AndroidOemVendor.LenovoZui },
                intentLauncher = { intent ->
                    vendorIntents += intent
                    if (intent.action != Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) {
                        throw ActivityNotFoundException("oem target missing")
                    }
                },
            )

        playOemControls.openVendorBackgroundSettings()

        assertTrue("Expected OEM targets to be attempted before fallback", vendorIntents.size > 1)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, vendorIntents.last().action)
        assertTrue(vendorIntents.none { it.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS })
    }
}
