package build.raft.app.push

import android.app.Application
import android.content.Context
import build.raft.app.shared.RaftAndroidBackgroundConnectionControls
import com.google.android.gms.common.ConnectionResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidColdStartPushRegistrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        RaftPlayServicesAvailability.resetForTests()
        AndroidPushRegistration.resetForTests()
        RaftPlayServicesAvailability.debugForceNoGmsProvider = { false }
    }

    @After
    fun tearDown() {
        RaftPlayServicesAvailability.resetForTests()
        AndroidPushRegistration.resetForTests()
    }

    @Test
    fun playServicesAvailabilityCachesAcrossCallersUntilRefreshed() {
        val probes = AtomicInteger(0)
        val currentCode = AtomicInteger(ConnectionResult.SUCCESS)
        RaftPlayServicesAvailability.availabilityCodeProvider = {
            probes.incrementAndGet()
            currentCode.get()
        }

        assertEquals(RaftFcmAvailability.Available, RaftPlayServicesAvailability.fcmAvailability(context))
        assertFalse(RaftPlayServicesAvailability.isFcmUnavailable(context))
        assertEquals(RaftFcmAvailability.Available, RaftPlayServicesAvailability.fcmAvailability(context))
        assertEquals(1, probes.get())

        currentCode.set(ConnectionResult.SERVICE_MISSING)
        assertTrue(RaftPlayServicesAvailability.refresh(context))
        assertEquals(2, probes.get())
        assertTrue(RaftPlayServicesAvailability.isFcmUnavailable(context))
        assertEquals(
            RaftFcmAvailability.Unavailable("no_play_services code=${ConnectionResult.SERVICE_MISSING}"),
            RaftPlayServicesAvailability.fcmAvailability(context)
        )
        assertEquals(2, probes.get())
    }

    @Test
    fun pushRegistrationStartRunsOffCallingThreadAndDeliversBufferedFidAfterInstall() {
        val callingThread = Thread.currentThread().name
        val probes = AtomicInteger(0)
        val installThread = AtomicReference<String?>(null)
        val installEntered = CountDownLatch(1)
        val releaseInstall = CountDownLatch(1)
        val permissionReported = AtomicBoolean(false)
        val firebaseRegistered = AtomicBoolean(false)
        val deliveredFids = mutableListOf<String>()

        RaftPlayServicesAvailability.availabilityCodeProvider = {
            probes.incrementAndGet()
            ConnectionResult.SUCCESS
        }
        AndroidPushRegistration.reportPermissionForTests = { permissionReported.set(true) }
        AndroidPushRegistration.installBridgeForTests = { _, unavailableReason ->
            assertNull(unavailableReason)
            installThread.set(Thread.currentThread().name)
            installEntered.countDown()
            assertTrue(releaseInstall.await(5L, TimeUnit.SECONDS))
        }
        AndroidPushRegistration.updateRegistrationForTests = { fid, _, _ ->
            synchronized(deliveredFids) { deliveredFids += fid }
        }
        AndroidPushRegistration.registerFirebaseForTests = { firebaseRegistered.set(true) }

        val job = requireNotNull(AndroidPushRegistration.startAsync(context))
        assertTrue("startAsync should reach install on background thread", installEntered.await(5L, TimeUnit.SECONDS))
        // A second concurrent start while startup is in flight is coalesced.
        assertNull(AndroidPushRegistration.startAsync(context))

        // An FID arriving before install finishes is buffered until install completes.
        AndroidPushRegistration.onFirebaseInstallationId(context, "fid-during-start")
        synchronized(deliveredFids) { assertTrue(deliveredFids.isEmpty()) }

        releaseInstall.countDown()
        runBlocking { job.join() }

        assertNotNull(installThread.get())
        assertNotEquals(callingThread, installThread.get())
        assertTrue(permissionReported.get())
        assertTrue(firebaseRegistered.get())
        synchronized(deliveredFids) { assertEquals(listOf("fid-during-start"), deliveredFids) }
        // Keep-alive fallback reuses the cached Play services result without a second probe.
        assertFalse(RaftPlayServicesAvailability.isFcmUnavailable(context))
        assertEquals(1, probes.get())
    }

    @Test
    fun pushRegistrationDropsBufferedFidWhenPlayServicesUnavailable() {
        val installEntered = CountDownLatch(1)
        val releaseInstall = CountDownLatch(1)
        val installedReason = AtomicReference<String?>(null)
        val firebaseRegistered = AtomicBoolean(false)
        val deliveredFids = mutableListOf<String>()

        RaftPlayServicesAvailability.availabilityCodeProvider = { ConnectionResult.SERVICE_MISSING }
        AndroidPushRegistration.reportPermissionForTests = { }
        AndroidPushRegistration.installBridgeForTests = { _, unavailableReason ->
            installedReason.set(unavailableReason)
            installEntered.countDown()
            assertTrue(releaseInstall.await(5L, TimeUnit.SECONDS))
        }
        AndroidPushRegistration.updateRegistrationForTests = { fid, _, _ ->
            synchronized(deliveredFids) { deliveredFids += fid }
        }
        AndroidPushRegistration.registerFirebaseForTests = { firebaseRegistered.set(true) }

        val job = requireNotNull(AndroidPushRegistration.startAsync(context))
        assertTrue(installEntered.await(5L, TimeUnit.SECONDS))
        AndroidPushRegistration.onFirebaseInstallationId(context, "fid-ignored-no-gms")
        releaseInstall.countDown()
        runBlocking { job.join() }

        assertEquals("no_play_services code=${ConnectionResult.SERVICE_MISSING}", installedReason.get())
        assertFalse(firebaseRegistered.get())
        synchronized(deliveredFids) { assertTrue(deliveredFids.isEmpty()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun backgroundConnectionControlsDeferBatteryOptimizationProbeUntilNeeded() {
        val probes = AtomicInteger(0)
        val testScope = TestScope(StandardTestDispatcher())
        val controls =
            RaftAndroidBackgroundConnectionControls(
                context = context,
                refreshScope = testScope,
                batteryOptimizationExemptProvider = {
                    probes.incrementAndGet()
                    true
                }
            )

        // Constructing controls on GMS cold start performs zero PowerManager IPCs.
        assertEquals(0, probes.get())

        // First StateFlow observation triggers one async refresh on refreshScope.
        val flow = controls.batteryOptimizationExemptState
        assertEquals(0, probes.get())
        assertFalse(flow.value)
        testScope.advanceUntilIdle()
        assertEquals(1, probes.get())
        assertTrue(flow.value)

        // Subsequent observations reuse the populated StateFlow without re-probing.
        assertTrue(controls.batteryOptimizationExemptState.value)
        testScope.advanceUntilIdle()
        assertEquals(1, probes.get())

        // Explicit resume refresh re-queries PowerManager once.
        controls.refreshBatteryOptimizationStatus()
        assertEquals(2, probes.get())
    }
}
