package build.raft.app.shared

import android.app.Application
import build.raft.app.BuildConfig
import build.raft.app.data.NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY
import build.raft.app.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import build.raft.app.data.NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
import build.raft.app.data.RaftBackgroundConnectionControlsRegistry
import build.raft.app.push.RaftPlayServicesAvailability
import build.raft.app.shared.RaftBackgroundConnectionPolicy.Decision
import com.google.android.gms.common.ConnectionResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies the direct-install Android APK GMS keep-alive override (#proj-mobile:#373):
 * - Direct APK defaults OFF with GMS (100% identical to prior behavior).
 * - Direct APK opt-in ON with GMS starts and keeps [RaftBackgroundConnectionService] alive across
 *   `onHostResumed` + `activity_on_resume`, allows local realtime notifications to post, and stops
 *   promptly on toggle-off, sign-out, or disabling message notifications.
 * - Play builds NEVER expose or honor any persisted override flag.
 * - No-GMS devices stay completely unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidRealtimeKeepAliveDirectGmsOverrideTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        prefs.clear()
        RaftPlayServicesAvailability.resetForTests()
        RaftPlayServicesAvailability.debugForceNoGmsProvider = { false }
        RaftBackgroundConnectionService.resetForTests()
        AndroidRealtimeKeepAliveController.resetForTests()
        RaftBackgroundConnectionControlsRegistry.controls = null

        AndroidRealtimeKeepAliveController.localPreferenceReader = { _, key, defaultValue ->
            prefs[key] ?: defaultValue
        }
        AndroidRealtimeKeepAliveController.localPreferenceWriter = { _, key, value ->
            prefs[key] = value
            true
        }
        AndroidRealtimeKeepAliveController.storedSessionProvider = { true }
        AndroidRealtimeKeepAliveController.systemNotificationsAllowedProvider = { true }
    }

    @After
    fun tearDown() {
        RaftPlayServicesAvailability.resetForTests()
        RaftBackgroundConnectionService.resetForTests()
        AndroidRealtimeKeepAliveController.resetForTests()
        RaftBackgroundConnectionControlsRegistry.controls = null
    }

    @Test
    fun directApkWithGmsDefaultsOffAndDoesNotStartService() {
        RaftPlayServicesAvailability.availabilityCodeProvider = { ConnectionResult.SUCCESS }
        AndroidRealtimeKeepAliveController.directInstallApkProvider = { true }
        AndroidRealtimeKeepAliveController.serviceDeclaredProvider = { true }

        assertFalse(AndroidRealtimeKeepAliveController.noPlayServicesFallbackApplies(app))
        assertTrue(AndroidRealtimeKeepAliveController.directGmsKeepAliveOverrideAllowed(app))
        assertFalse(AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideEnabled(app))
        assertFalse(AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideActive(app))
        assertEquals(
            Decision.Stop("play_services_available"),
            AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(app),
        )
        assertTrue(
            AndroidAppScopeRealtimeHostContext.shouldTreatRemotePushAsActive(
                fcmUnavailable = false,
                directGmsKeepAliveOverrideActive = AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideActive(app),
                policyRemotePushActive = true,
            )
        )

        AndroidRealtimeKeepAliveController.start(app, source = "activity_on_resume")
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(RaftBackgroundConnectionService.active)
    }

    @Test
    fun directApkOptInOnWithGmsStartsStaysAliveOnResumeAndStopsOnToggleOffOrSignOut() {
        RaftPlayServicesAvailability.availabilityCodeProvider = { ConnectionResult.SUCCESS }
        AndroidRealtimeKeepAliveController.directInstallApkProvider = { true }
        AndroidRealtimeKeepAliveController.serviceDeclaredProvider = { true }

        // User enables the direct-install GMS override while Background realtime stays OFF.
        assertTrue(AndroidRealtimeKeepAliveController.setDirectGmsKeepAliveOverrideEnabled(app, true))
        assertEquals("true", prefs[NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY])
        assertTrue(AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideActive(app))
        assertEquals(Decision.Run, AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(app))

        val startedIntent = shadowOf(app).nextStartedService
        assertNotNull(startedIntent)
        assertEquals(RaftBackgroundConnectionService::class.java.name, startedIntent?.component?.className)
        assertTrue(RaftBackgroundConnectionService.active)

        // Local realtime notifications must NOT be skipped as remote_push_active while override is active.
        assertFalse(
            AndroidAppScopeRealtimeHostContext.shouldTreatRemotePushAsActive(
                fcmUnavailable = false,
                directGmsKeepAliveOverrideActive = AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideActive(app),
                policyRemotePushActive = true,
            )
        )

        // Simulate service now running, then host resumes and re-checks Play services (still SUCCESS):
        // must keep the service alive cleanly without stopping it as play_services_available.
        RaftBackgroundConnectionService.resetForTests()
        RaftBackgroundConnectionService.setRunningForTests(true)
        AndroidRealtimeKeepAliveController.onHostResumed(app)
        AndroidRealtimeKeepAliveController.start(app, source = "activity_on_resume")
        assertNull("onHostResumed + activity_on_resume must not stop the active override service", shadowOf(app).nextStoppedService)
        assertNull("already-running service must not be redundantly re-started", shadowOf(app).nextStartedService)
        assertTrue(RaftBackgroundConnectionService.running)

        // Turning off the separate Background realtime switch must not kill the active direct GMS override.
        AndroidRealtimeKeepAliveController.stop(app, reason = "preference_disabled")
        assertNull(shadowOf(app).nextStoppedService)
        assertTrue(RaftBackgroundConnectionService.running)

        // Turning the direct GMS override OFF stops the service promptly.
        assertTrue(AndroidRealtimeKeepAliveController.setDirectGmsKeepAliveOverrideEnabled(app, false))
        assertEquals("false", prefs[NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY])
        assertFalse(AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideActive(app))
        assertEquals(
            Decision.Stop("play_services_available"),
            AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(app),
        )
        val stoppedOnToggleOff = shadowOf(app).nextStoppedService
        assertEquals(RaftBackgroundConnectionService::class.java.name, stoppedOnToggleOff?.component?.className)

        // Re-enable and verify sign-out stops the service promptly.
        RaftBackgroundConnectionService.resetForTests()
        assertTrue(AndroidRealtimeKeepAliveController.setDirectGmsKeepAliveOverrideEnabled(app, true))
        shadowOf(app).nextStartedService
        RaftBackgroundConnectionService.resetForTests()
        RaftBackgroundConnectionService.setRunningForTests(true)
        AndroidRealtimeKeepAliveController.stop(app, reason = "logout")
        val stoppedOnLogout = shadowOf(app).nextStoppedService
        assertEquals(RaftBackgroundConnectionService::class.java.name, stoppedOnLogout?.component?.className)

        // Disabling message notifications also stops the service promptly.
        RaftBackgroundConnectionService.resetForTests()
        RaftBackgroundConnectionService.setRunningForTests(true)
        prefs[NOTIFICATION_ENABLED_PREFERENCE_KEY] = "false"
        AndroidRealtimeKeepAliveController.refreshBackgroundConnectionService(app, source = "message_notifications_preference")
        val stoppedOnNotificationsOff = shadowOf(app).nextStoppedService
        assertEquals(RaftBackgroundConnectionService::class.java.name, stoppedOnNotificationsOff?.component?.className)
    }

    @Test
    fun playBuildCannotEnableAndIgnoresPersistedOverrideFlag() {
        RaftPlayServicesAvailability.availabilityCodeProvider = { ConnectionResult.SUCCESS }
        // Simulate a Play build even with a persisted "true" flag left in storage (e.g. backup restore).
        prefs[NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY] = "true"
        AndroidRealtimeKeepAliveController.directInstallApkProvider = { false }
        AndroidRealtimeKeepAliveController.serviceDeclaredProvider = { false }

        assertFalse(AndroidRealtimeKeepAliveController.directGmsKeepAliveOverrideAllowed(app))
        assertFalse(AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideEnabled(app))
        assertFalse(AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideActive(app))
        assertFalse(AndroidRealtimeKeepAliveController.backgroundConnectionServiceApplies(app))
        assertEquals(
            Decision.Stop("play_services_available"),
            AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(app),
        )

        assertFalse(AndroidRealtimeKeepAliveController.setDirectGmsKeepAliveOverrideEnabled(app, true))
        AndroidRealtimeKeepAliveController.start(app, source = "activity_on_resume")
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(RaftBackgroundConnectionService.active)

        // Even if the service declaration seam returned true, HANDS_APK_UPDATE_ENABLED=false alone blocks Play.
        AndroidRealtimeKeepAliveController.serviceDeclaredProvider = { true }
        assertFalse(AndroidRealtimeKeepAliveController.directGmsKeepAliveOverrideAllowed(app))
        assertFalse(AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideActive(app))
        assertEquals(
            Decision.Stop("play_services_available"),
            AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(app),
        )
    }

    @Test
    fun noGmsDeviceBehaviorRemainsUnchanged() {
        RaftPlayServicesAvailability.availabilityCodeProvider = { ConnectionResult.SERVICE_MISSING }
        AndroidRealtimeKeepAliveController.directInstallApkProvider = { true }
        AndroidRealtimeKeepAliveController.serviceDeclaredProvider = { true }
        prefs[NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY] = "true"

        assertTrue(AndroidRealtimeKeepAliveController.noPlayServicesFallbackApplies(app))
        assertFalse(AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideActive(app))
        assertTrue(AndroidRealtimeKeepAliveController.backgroundConnectionServiceApplies(app))
        assertEquals(Decision.Run, AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(app))

        prefs[NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY] = "false"
        prefs[NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY] = "true"
        assertEquals(
            Decision.Stop("preference_disabled"),
            AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(app),
        )
    }

    @Test
    fun defaultDirectInstallProviderMatchesCurrentBuildType() {
        val isPlayBuild = BuildConfig.BUILD_TYPE == "play"
        assertEquals(!isPlayBuild, AndroidRealtimeKeepAliveController.directInstallApkProvider())
        if (isPlayBuild) {
            prefs[NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY] = "true"
            RaftPlayServicesAvailability.availabilityCodeProvider = { ConnectionResult.SUCCESS }
            assertFalse(AndroidRealtimeKeepAliveController.directGmsKeepAliveOverrideAllowed(app))
            assertFalse(AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideEnabled(app))
            assertFalse(AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideActive(app))
            assertEquals(
                Decision.Stop("play_services_available"),
                AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(app),
            )
        }
    }
}
