package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import build.raft.app.BuildConfig
import build.raft.app.data.NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftBackgroundConnectionControlsRegistry
import build.raft.app.data.RaftFcmProbeUiStatus
import build.raft.app.data.RaftPushProbeResult
import build.raft.app.push.AndroidFcmReachabilityController
import build.raft.app.push.AndroidFcmReachabilityStateMachine
import build.raft.app.push.AndroidNetworkSnapshot
import build.raft.app.push.FcmReachabilityStatus
import build.raft.app.push.RaftPlayServicesAvailability
import build.raft.app.push.raftFcmUnreadNotificationRequest
import build.raft.app.shared.RaftBackgroundConnectionPolicy.Decision
import com.google.android.gms.common.ConnectionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * End-to-end Android dual-engine push + probed-reachability local notification gating tests
 * (`#proj-mobile:#379`, runs on BOTH `testDebugUnitTest` and `testPlayUnitTest`):
 * - Critical Tenny rule (`msg=85eae7f0`): `fcmRegistered == true` + `fcmProbedUnreachable == true`
 *   NEVER suppresses local Socket.IO notifications, and duplicate `messageId` deliveries across
 *   socket + FCM deduplicate with `FLAG_ONLY_ALERT_ONCE`.
 * - Direct APK: 2 consecutive foreground misses auto-start [RaftBackgroundConnectionService],
 *   surface [RaftFcmProbeUiStatus.DirectAutoFallbackActive], and stop cleanly once FCM recovers
 *   past dwell (while respecting the user's manual `#5719` override when ON).
 * - Google Play build: defense-in-depth NEVER starts [RaftBackgroundConnectionService] and NEVER
 *   reports `isAutoFallbackActive == true`; surfaces dismissible [RaftFcmProbeUiStatus.PlayPushUnreachableNotice].
 * - Feature flag OFF or HTTP 404: 100% identical to `#5719` (zero probes, zero transitions, zero prompts).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidFcmProbeDualEngineIntegrationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs = mutableMapOf<String, String>()
    private lateinit var testDispatcher: kotlinx.coroutines.test.TestDispatcher
    private lateinit var testScope: TestScope
    private var nowMillis = 10_000_000L
    private var probeCounter = 1
    private val sentProbeIds = mutableListOf<String>()

    @Before
    fun setUp() {
        prefs.clear()
        sentProbeIds.clear()
        nowMillis = 10_000_000L
        probeCounter = 1
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)

        RaftPlayServicesAvailability.resetForTests()
        RaftPlayServicesAvailability.debugForceNoGmsProvider = { false }
        RaftPlayServicesAvailability.availabilityCodeProvider = { ConnectionResult.SUCCESS }
        RaftBackgroundConnectionService.resetForTests()
        AndroidRealtimeKeepAliveController.resetForTests()
        AndroidFcmReachabilityController.resetForTests()
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

        AndroidFcmReachabilityController.clockMillis = { nowMillis }
        AndroidFcmReachabilityController.probeIdGenerator = { "probe-${probeCounter++}" }
        AndroidFcmReachabilityController.scopeProvider = { testScope }
        AndroidFcmReachabilityController.dispatcherForTests = testDispatcher
        AndroidFcmReachabilityController.signedInProvider = { true }
        AndroidFcmReachabilityController.fcmUnavailableProvider = { false }
        AndroidFcmReachabilityController.hasRegistrationProvider = { true }
        AndroidFcmReachabilityController.serverKeyProvider = { "https://api.example|server-1" }
        AndroidFcmReachabilityController.networkSnapshotProvider = {
            AndroidNetworkSnapshot(networkKey = "net:1:wifi", online = true)
        }
        AndroidFcmReachabilityController.probeExecutor = { probeId ->
            sentProbeIds += probeId
            RaftPushProbeResult.Sent(probeId)
        }
        AndroidFcmReachabilityController.stateMachine =
            AndroidFcmReachabilityStateMachine(
                clockMillis = { nowMillis },
                probeIdGenerator = { "probe-${probeCounter++}" },
            )
    }

    @After
    fun tearDown() {
        AndroidFcmReachabilityController.resetForTests()
        RaftPlayServicesAvailability.resetForTests()
        RaftBackgroundConnectionService.resetForTests()
        AndroidRealtimeKeepAliveController.resetForTests()
        RaftBackgroundConnectionControlsRegistry.controls = null
    }

    @Test
    fun tennyRuleRequiresProbedReachabilityToSkipLocalNotificationAndDeduplicatesSameMessageId() {
        // Flag ON + fcmRegistered/policyRemotePushActive == true + probed Unreachable -> MUST NOT skip local notification!
        assertFalse(
            AndroidAppScopeRealtimeHostContext.shouldTreatRemotePushAsActive(
                fcmUnavailable = false,
                directGmsKeepAliveOverrideActive = false,
                policyRemotePushActive = true,
                fcmProbeEnabled = true,
                fcmReachability = FcmReachabilityStatus.Unreachable,
            )
        )
        // Flag ON + Unknown / Probing -> MUST NOT skip local notification (no missed messages before probe confirms).
        assertFalse(
            AndroidAppScopeRealtimeHostContext.shouldTreatRemotePushAsActive(
                fcmUnavailable = false,
                directGmsKeepAliveOverrideActive = false,
                policyRemotePushActive = true,
                fcmProbeEnabled = true,
                fcmReachability = FcmReachabilityStatus.Unknown,
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeHostContext.shouldTreatRemotePushAsActive(
                fcmUnavailable = false,
                directGmsKeepAliveOverrideActive = false,
                policyRemotePushActive = true,
                fcmProbeEnabled = true,
                fcmReachability = FcmReachabilityStatus.Probing,
            )
        )
        // Flag ON + probed Reachable + policyRemotePushActive == true -> skips local notification (FCM delivers).
        assertTrue(
            AndroidAppScopeRealtimeHostContext.shouldTreatRemotePushAsActive(
                fcmUnavailable = false,
                directGmsKeepAliveOverrideActive = false,
                policyRemotePushActive = true,
                fcmProbeEnabled = true,
                fcmReachability = FcmReachabilityStatus.Reachable,
            )
        )
        // Even when Reachable, manual direct override ON still posts locally.
        assertFalse(
            AndroidAppScopeRealtimeHostContext.shouldTreatRemotePushAsActive(
                fcmUnavailable = false,
                directGmsKeepAliveOverrideActive = true,
                policyRemotePushActive = true,
                fcmProbeEnabled = true,
                fcmReachability = FcmReachabilityStatus.Reachable,
            )
        )
        // Flag OFF -> exact #5719 behavior (skips local notification when policyRemotePushActive == true).
        assertTrue(
            AndroidAppScopeRealtimeHostContext.shouldTreatRemotePushAsActive(
                fcmUnavailable = false,
                directGmsKeepAliveOverrideActive = false,
                policyRemotePushActive = true,
                fcmProbeEnabled = false,
                fcmReachability = FcmReachabilityStatus.Unreachable,
            )
        )

        // Verify duplicate messageId across local Socket.IO post + subsequent FCM delivery sets FLAG_ONLY_ALERT_ONCE.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(app)
        manager.cancelAll()
        AndroidUnreadNotificationPoster.resetGroupingState()
        val request =
            raftFcmUnreadNotificationRequest(
                mapOf(
                    "serverId" to "server-1",
                    "channelId" to "channel-1",
                    "messageId" to "msg-dual-1",
                    "kind" to "channel",
                    "category" to "RAFT_MESSAGE",
                    "title" to "#general",
                    "body" to "Alice: hello",
                    "notificationKey" to "collapse-server-1-channel-1",
                )
            )
        val first =
            AndroidUnreadNotificationPoster.postGroupedUnreadNotification(
                context = app,
                channelId = AndroidUnreadNotificationPoster.UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID,
                request = request,
                previewMode = PushPreviewMode.Full,
                contentIntent = { null },
            )
        assertEquals("posted", first["code"])
        val second =
            AndroidUnreadNotificationPoster.postGroupedUnreadNotification(
                context = app,
                channelId = AndroidUnreadNotificationPoster.UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID,
                request = request,
                previewMode = PushPreviewMode.Full,
                contentIntent = { null },
            )
        assertEquals("posted", second["code"])
        val active =
            manager.activeNotifications.single { it.tag == AndroidUnreadNotificationGrouping.CONVERSATION_TAG }
        assertEquals(1, active.notification.number)
        assertNotEquals(0, active.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun directApkAutoStartsForegroundServiceOnTwoMissesAndRecoversCleanlyAfterDwell() {
        AndroidRealtimeKeepAliveController.directInstallApkProvider = { true }
        AndroidRealtimeKeepAliveController.serviceDeclaredProvider = { true }
        AndroidFcmReachabilityController.featureFlagEnabledProvider = { true }

        // Foreground + 2 consecutive misses -> automatic fallback activates and starts FGS!
        AndroidFcmReachabilityController.onAppForegrounded(app)
        advanceClockAndRun(0L)
        assertEquals(listOf("probe-1"), sentProbeIds)

        advanceClockAndRun(AndroidFcmReachabilityStateMachine.PROBE_TIMEOUT_MS)
        assertEquals(FcmReachabilityStatus.Unknown, AndroidFcmReachabilityController.currentReachabilityStatus())
        assertFalse(AndroidRealtimeKeepAliveController.isFcmAutoFallbackActive(app))
        assertNull(shadowOf(app).nextStartedService)

        advanceClockAndRun(AndroidFcmReachabilityStateMachine.MIN_RETRY_GAP_MS)
        assertEquals(listOf("probe-1", "probe-2"), sentProbeIds)

        advanceClockAndRun(AndroidFcmReachabilityStateMachine.PROBE_TIMEOUT_MS)
        assertEquals(FcmReachabilityStatus.Unreachable, AndroidFcmReachabilityController.currentReachabilityStatus())
        assertTrue(AndroidRealtimeKeepAliveController.isFcmAutoFallbackActive(app))
        assertEquals(Decision.Run, AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(app))
        assertEquals(RaftFcmProbeUiStatus.DirectAutoFallbackActive, AndroidFcmReachabilityController.currentUiStatus(app))
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(RaftBackgroundConnectionService::class.java.name, started?.component?.className)

        // Simulate service running; resume must keep it running while Unreachable.
        RaftBackgroundConnectionService.resetForTests()
        RaftBackgroundConnectionService.setRunningForTests(true)
        AndroidRealtimeKeepAliveController.start(app, source = "activity_on_resume")
        assertNull(shadowOf(app).nextStoppedService)

        // Advance past 5-min dwell, then real FCM arrives -> flips Reachable and stops FGS cleanly!
        advanceClockAndRun(AndroidFcmReachabilityStateMachine.MIN_FALLBACK_DWELL_MS)
        AndroidFcmReachabilityController.onRealFcmDelivered(app, source = "alert")
        assertEquals(FcmReachabilityStatus.Reachable, AndroidFcmReachabilityController.currentReachabilityStatus())
        assertFalse(AndroidRealtimeKeepAliveController.isFcmAutoFallbackActive(app))
        assertEquals(RaftFcmProbeUiStatus.None, AndroidFcmReachabilityController.currentUiStatus(app))
        val stopped = shadowOf(app).nextStoppedService
        assertEquals(RaftBackgroundConnectionService::class.java.name, stopped?.component?.className)
    }

    @Test
    fun directApkManualOverrideKeepsServiceAliveEvenAfterAutoFallbackRecovers() {
        AndroidRealtimeKeepAliveController.directInstallApkProvider = { true }
        AndroidRealtimeKeepAliveController.serviceDeclaredProvider = { true }
        AndroidFcmReachabilityController.featureFlagEnabledProvider = { true }
        prefs[NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY] = "true"

        // Drive to Unreachable, then recover via real FCM past dwell.
        AndroidFcmReachabilityController.onAppForegrounded(app)
        advanceClockAndRun(0L)
        advanceClockAndRun(AndroidFcmReachabilityStateMachine.PROBE_TIMEOUT_MS)
        advanceClockAndRun(AndroidFcmReachabilityStateMachine.MIN_RETRY_GAP_MS)
        advanceClockAndRun(AndroidFcmReachabilityStateMachine.PROBE_TIMEOUT_MS)
        assertTrue(AndroidRealtimeKeepAliveController.isFcmAutoFallbackActive(app))
        shadowOf(app).nextStartedService

        RaftBackgroundConnectionService.resetForTests()
        RaftBackgroundConnectionService.setRunningForTests(true)
        advanceClockAndRun(AndroidFcmReachabilityStateMachine.MIN_FALLBACK_DWELL_MS)
        AndroidFcmReachabilityController.onRealFcmDelivered(app, source = "alert")

        assertFalse(AndroidRealtimeKeepAliveController.isFcmAutoFallbackActive(app))
        assertTrue(AndroidRealtimeKeepAliveController.isDirectGmsKeepAliveOverrideActive(app))
        assertEquals(Decision.Run, AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(app))
        assertNull("Manual #5719 override must keep service running after FCM recovers", shadowOf(app).nextStoppedService)
    }

    @Test
    fun playBuildNeverStartsForegroundServiceAndSurfacesDismissibleNotice() {
        AndroidRealtimeKeepAliveController.directInstallApkProvider = { false }
        AndroidRealtimeKeepAliveController.serviceDeclaredProvider = { false }
        AndroidFcmReachabilityController.featureFlagEnabledProvider = { true }

        AndroidFcmReachabilityController.onAppForegrounded(app)
        advanceClockAndRun(0L)
        advanceClockAndRun(AndroidFcmReachabilityStateMachine.PROBE_TIMEOUT_MS)
        advanceClockAndRun(AndroidFcmReachabilityStateMachine.MIN_RETRY_GAP_MS)
        advanceClockAndRun(AndroidFcmReachabilityStateMachine.PROBE_TIMEOUT_MS)

        assertEquals(FcmReachabilityStatus.Unreachable, AndroidFcmReachabilityController.currentReachabilityStatus())
        assertFalse(AndroidFcmReachabilityController.isAutoFallbackActive(app))
        assertFalse(AndroidRealtimeKeepAliveController.isFcmAutoFallbackActive(app))
        assertEquals(
            Decision.Stop("play_services_available"),
            AndroidRealtimeKeepAliveController.currentBackgroundConnectionDecision(app),
        )
        assertNull("Play build MUST NEVER start RaftBackgroundConnectionService", shadowOf(app).nextStartedService)
        assertFalse(RaftBackgroundConnectionService.active)

        // Settings › Notifications surfaces the gentle dismissible Play notice and honors dismiss.
        assertEquals(RaftFcmProbeUiStatus.PlayPushUnreachableNotice, AndroidFcmReachabilityController.currentUiStatus(app))
        AndroidFcmReachabilityController.dismissPlayUnreachableNotice(app)
        assertEquals(RaftFcmProbeUiStatus.None, AndroidFcmReachabilityController.currentUiStatus(app))

        // Verify real Play build default provider also blocks Play automatically.
        if (BuildConfig.BUILD_TYPE == "play") {
            AndroidRealtimeKeepAliveController.resetForTests()
            assertFalse(AndroidRealtimeKeepAliveController.directGmsKeepAliveOverrideAllowed(app))
            assertFalse(AndroidFcmReachabilityController.isAutoFallbackActive(app))
        }
    }

    @Test
    fun flagOffAndServer404KeepExactPreProbeBehaviorWithZeroProbesAndZeroPrompts() {
        AndroidRealtimeKeepAliveController.directInstallApkProvider = { true }
        AndroidRealtimeKeepAliveController.serviceDeclaredProvider = { true }
        AndroidFcmReachabilityController.featureFlagEnabledProvider = { false }

        AndroidFcmReachabilityController.onAppForegrounded(app)
        advanceClockAndRun(120_000L)
        assertTrue(sentProbeIds.isEmpty())
        assertEquals(FcmReachabilityStatus.Unknown, AndroidFcmReachabilityController.currentReachabilityStatus())
        assertEquals(RaftFcmProbeUiStatus.None, AndroidFcmReachabilityController.currentUiStatus(app))
        assertNull(shadowOf(app).nextStartedService)

        // Flag ON + server returns HTTP 404 -> marks unsupported, zero retries, zero prompts.
        AndroidFcmReachabilityController.featureFlagEnabledProvider = { true }
        AndroidFcmReachabilityController.probeExecutor = { probeId ->
            sentProbeIds += probeId
            RaftPushProbeResult.Unsupported
        }
        AndroidFcmReachabilityController.onFeatureFlagChanged()
        advanceClockAndRun(0L)
        assertEquals(listOf("probe-1"), sentProbeIds)
        assertFalse(AndroidFcmReachabilityController.isFeatureEnabled(app))
        assertEquals(RaftFcmProbeUiStatus.None, AndroidFcmReachabilityController.currentUiStatus(app))

        advanceClockAndRun(300_000L)
        AndroidFcmReachabilityController.onForegroundResume(app)
        advanceClockAndRun(0L)
        assertEquals(listOf("probe-1"), sentProbeIds)
        assertNull(shadowOf(app).nextStartedService)
    }

    private fun advanceClockAndRun(deltaMillis: Long) {
        nowMillis += deltaMillis
        testScope.advanceTimeBy(deltaMillis)
        testScope.runCurrent()
    }
}
