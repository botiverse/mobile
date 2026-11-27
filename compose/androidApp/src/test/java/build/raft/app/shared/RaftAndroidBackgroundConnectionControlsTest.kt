package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.provider.Settings
import build.raft.app.data.RaftBackgroundWhitelistGuide
import build.raft.app.data.RaftFcmProbeUiStatus
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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

    @Test
    fun playManifestStripsBackgroundConnectionServiceAndFgsPermissions() {
        val main = File("src/main/AndroidManifest.xml").readText()
        val play = File("src/play/AndroidManifest.xml").readText()
        assertTrue(main.contains("android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING"))
        assertTrue(main.contains(".shared.RaftBackgroundConnectionService"))
        assertTrue(
            Regex("""android:name="android\.permission\.FOREGROUND_SERVICE"\s+tools:node="remove"""").containsMatchIn(play)
        )
        assertTrue(
            Regex("""android:name="android\.permission\.FOREGROUND_SERVICE_REMOTE_MESSAGING"\s+tools:node="remove"""").containsMatchIn(play)
        )
        assertTrue(
            Regex("""android:name="build\.raft\.app\.shared\.RaftBackgroundConnectionService"\s+tools:node="remove"""").containsMatchIn(play)
        )
    }

    @Test
    fun mainManifestStripsUnusedLiveKitScreenCaptureFgsAndPlayManifestKeepsAllFgsRemovals() {
        val main = File("src/main/AndroidManifest.xml").readText()
        val play = File("src/play/AndroidManifest.xml").readText()
        assertTrue(
            Regex(
                """android:name="android\.permission\.FOREGROUND_SERVICE_MEDIA_PROJECTION"\s+tools:node="remove""""
            ).containsMatchIn(main)
        )
        assertTrue(
            Regex(
                """android:name="io\.livekit\.android\.room\.track\.screencapture\.ScreenCaptureService"\s+tools:node="remove""""
            ).containsMatchIn(main)
        )
        val declaredMainFgsPermissions =
            Regex("""<uses-permission\b[^>]*android:name="(android\.permission\.FOREGROUND_SERVICE[^"]*)"[^>]*/>""")
                .findAll(main)
                .filterNot { it.value.contains("""tools:node="remove"""") }
                .map { it.groupValues[1] }
                .toSet()
        assertEquals(
            setOf(
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            ),
            declaredMainFgsPermissions,
        )
        for (permission in declaredMainFgsPermissions) {
            assertTrue(
                "Play manifest must remove $permission",
                Regex("""android:name="${Regex.escape(permission)}"\s+tools:node="remove"""").containsMatchIn(play),
            )
        }
        assertTrue(
            Regex("""android:name="build\.raft\.app\.shared\.RaftDownloadForegroundService"\s+tools:node="remove"""").containsMatchIn(play)
        )
    }

    @Test
    fun directGmsOverrideExposedAndToggleableOnlyOnDirectInstallWithGms() {
        val persistedWrites = mutableListOf<Boolean>()
        var stored = false
        val directGmsControls =
            RaftAndroidBackgroundConnectionControls(
                context = context,
                batteryOptimizationExemptProvider = { false },
                batteryOptimizationPermissionDeclaredProvider = { true },
                noPlayServicesFallbackProvider = { false },
                directGmsKeepAliveOverrideAllowedProvider = { true },
                directGmsKeepAliveOverrideEnabledProvider = { stored },
                directGmsKeepAliveOverrideSetter = { _, enabled ->
                    stored = enabled
                    persistedWrites += enabled
                },
                vendorProvider = { AndroidOemVendor.Vivo },
                intentLauncher = {},
            )

        assertFalse(directGmsControls.isOffered())
        assertTrue(directGmsControls.canOverrideWithForegroundService())
        assertFalse(directGmsControls.directGmsKeepAliveOverrideState.value)

        directGmsControls.setDirectGmsKeepAliveOverrideEnabled(true)
        assertTrue(directGmsControls.directGmsKeepAliveOverrideState.value)
        assertEquals(listOf(true), persistedWrites)

        directGmsControls.setDirectGmsKeepAliveOverrideEnabled(false)
        assertFalse(directGmsControls.directGmsKeepAliveOverrideState.value)
        assertEquals(listOf(true, false), persistedWrites)

        // Direct no-GMS device: automatic fallback already applies, override is not offered or mutable.
        val noGmsWrites = mutableListOf<Boolean>()
        val noGmsControls =
            RaftAndroidBackgroundConnectionControls(
                context = context,
                batteryOptimizationExemptProvider = { false },
                batteryOptimizationPermissionDeclaredProvider = { true },
                noPlayServicesFallbackProvider = { true },
                directGmsKeepAliveOverrideAllowedProvider = { true },
                directGmsKeepAliveOverrideEnabledProvider = { true },
                directGmsKeepAliveOverrideSetter = { _, enabled -> noGmsWrites += enabled },
                vendorProvider = { AndroidOemVendor.Huawei },
                intentLauncher = {},
            )
        assertTrue(noGmsControls.isOffered())
        assertFalse(noGmsControls.canOverrideWithForegroundService())
        noGmsControls.refreshDirectGmsKeepAliveOverrideStatus()
        assertFalse(noGmsControls.directGmsKeepAliveOverrideState.value)
        noGmsControls.setDirectGmsKeepAliveOverrideEnabled(true)
        assertFalse(noGmsControls.directGmsKeepAliveOverrideState.value)
        assertTrue(noGmsWrites.isEmpty())

        // Play build: NEVER offers the override, ignores persisted true, and ignores setter calls.
        val playWrites = mutableListOf<Boolean>()
        val playControls =
            RaftAndroidBackgroundConnectionControls(
                context = context,
                batteryOptimizationExemptProvider = { false },
                batteryOptimizationPermissionDeclaredProvider = { false },
                noPlayServicesFallbackProvider = { false },
                directGmsKeepAliveOverrideAllowedProvider = { false },
                directGmsKeepAliveOverrideEnabledProvider = { true },
                directGmsKeepAliveOverrideSetter = { _, enabled -> playWrites += enabled },
                vendorProvider = { AndroidOemVendor.Vivo },
                intentLauncher = {},
            )
        assertFalse(playControls.isOffered())
        assertFalse(playControls.canOverrideWithForegroundService())
        playControls.refreshDirectGmsKeepAliveOverrideStatus()
        assertFalse(playControls.directGmsKeepAliveOverrideState.value)
        playControls.setDirectGmsKeepAliveOverrideEnabled(true)
        assertFalse(playControls.directGmsKeepAliveOverrideState.value)
        assertTrue(playWrites.isEmpty())
    }

    @Test
    fun fcmProbeUiStatusRefreshesAndDismissesPlayNoticeWhileStayingNoneOnNoGms() {
        var playStatus = RaftFcmProbeUiStatus.PlayPushUnreachableNotice
        var dismissCalls = 0
        val playControls =
            RaftAndroidBackgroundConnectionControls(
                context = context,
                batteryOptimizationExemptProvider = { false },
                batteryOptimizationPermissionDeclaredProvider = { false },
                noPlayServicesFallbackProvider = { false },
                directGmsKeepAliveOverrideAllowedProvider = { false },
                fcmProbeUiStatusProvider = { playStatus },
                dismissPlayFcmUnreachableNoticeAction = {
                    dismissCalls += 1
                    playStatus = RaftFcmProbeUiStatus.None
                },
                vendorProvider = { AndroidOemVendor.Generic },
                intentLauncher = {},
            )
        playControls.refreshFcmReachabilityStatus()
        assertEquals(RaftFcmProbeUiStatus.PlayPushUnreachableNotice, playControls.fcmProbeUiStatusState.value)
        playControls.dismissPlayFcmUnreachableNotice()
        assertEquals(1, dismissCalls)
        assertEquals(RaftFcmProbeUiStatus.None, playControls.fcmProbeUiStatusState.value)

        // No-GMS device always stays None regardless of probe provider.
        val noGmsControls =
            RaftAndroidBackgroundConnectionControls(
                context = context,
                batteryOptimizationExemptProvider = { false },
                batteryOptimizationPermissionDeclaredProvider = { true },
                noPlayServicesFallbackProvider = { true },
                directGmsKeepAliveOverrideAllowedProvider = { true },
                fcmProbeUiStatusProvider = { RaftFcmProbeUiStatus.DirectAutoFallbackActive },
                vendorProvider = { AndroidOemVendor.Huawei },
                intentLauncher = {},
            )
        noGmsControls.refreshFcmReachabilityStatus()
        assertEquals(RaftFcmProbeUiStatus.None, noGmsControls.fcmProbeUiStatusState.value)
    }

    @Test
    fun staleInitialOverrideRefreshDoesNotClobberSubsequentToggle() {
        val readStarted = CountDownLatch(1)
        val allowReadReturn = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val controls =
                RaftAndroidBackgroundConnectionControls(
                    context = context,
                    refreshScope = scope,
                    batteryOptimizationExemptProvider = { false },
                    batteryOptimizationPermissionDeclaredProvider = { true },
                    noPlayServicesFallbackProvider = { false },
                    directGmsKeepAliveOverrideAllowedProvider = { true },
                    directGmsKeepAliveOverrideEnabledProvider = {
                        readStarted.countDown()
                        assertTrue(allowReadReturn.await(5, TimeUnit.SECONDS))
                        try {
                            false
                        } finally {
                            readFinished.countDown()
                        }
                    },
                    directGmsKeepAliveOverrideSetter = { _, _ -> },
                    vendorProvider = { AndroidOemVendor.Vivo },
                    intentLauncher = {},
                )

            // First observation kicks off the async initial read.
            assertFalse(controls.directGmsKeepAliveOverrideState.value)
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))

            // User enables the toggle before the stale initial read completes.
            controls.setDirectGmsKeepAliveOverrideEnabled(true)
            assertTrue(controls.directGmsKeepAliveOverrideState.value)

            // Allow the stale initial read (returning false) to finish; it must NOT overwrite true.
            allowReadReturn.countDown()
            assertTrue(readFinished.await(5, TimeUnit.SECONDS))
            runBlocking { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertTrue(controls.directGmsKeepAliveOverrideState.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleInitialFcmProbeRefreshDoesNotClobberPlayNoticeDismiss() {
        val readStarted = CountDownLatch(1)
        val allowReadReturn = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        var dismissed = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val controls =
                RaftAndroidBackgroundConnectionControls(
                    context = context,
                    refreshScope = scope,
                    batteryOptimizationExemptProvider = { false },
                    batteryOptimizationPermissionDeclaredProvider = { false },
                    noPlayServicesFallbackProvider = { false },
                    directGmsKeepAliveOverrideAllowedProvider = { false },
                    fcmProbeUiStatusProvider = {
                        if (!dismissed && readStarted.count > 0L) {
                            readStarted.countDown()
                            assertTrue(allowReadReturn.await(5, TimeUnit.SECONDS))
                            try {
                                RaftFcmProbeUiStatus.PlayPushUnreachableNotice
                            } finally {
                                readFinished.countDown()
                            }
                        } else if (dismissed) {
                            RaftFcmProbeUiStatus.None
                        } else {
                            RaftFcmProbeUiStatus.PlayPushUnreachableNotice
                        }
                    },
                    dismissPlayFcmUnreachableNoticeAction = { dismissed = true },
                    vendorProvider = { AndroidOemVendor.Generic },
                    intentLauncher = {},
                )

            // First observation starts the async initial read.
            assertEquals(RaftFcmProbeUiStatus.None, controls.fcmProbeUiStatusState.value)
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))

            // User dismisses while the initial read is still in flight.
            controls.dismissPlayFcmUnreachableNotice()
            assertTrue(dismissed)
            assertEquals(RaftFcmProbeUiStatus.None, controls.fcmProbeUiStatusState.value)

            // Release the stale initial read returning PlayPushUnreachableNotice; it must not resurface.
            allowReadReturn.countDown()
            assertTrue(readFinished.await(5, TimeUnit.SECONDS))
            runBlocking { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            assertEquals(RaftFcmProbeUiStatus.None, controls.fcmProbeUiStatusState.value)
        } finally {
            scope.cancel()
        }
    }
}
