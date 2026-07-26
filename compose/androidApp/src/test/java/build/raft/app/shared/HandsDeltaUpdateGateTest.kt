package build.raft.app.shared

import build.raft.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HandsDeltaUpdateGateTest {
    @Test
    fun generatedVariantConfigEnablesDeltaOnlyForExactAlphaApplication() {
        val isExactAlpha = BuildConfig.APPLICATION_ID == HANDS_DELTA_ALPHA_APPLICATION_ID

        assertEquals(isExactAlpha, BuildConfig.HANDS_DELTA_UPDATE_ENABLED)
        if (isExactAlpha) {
            assertEquals(HANDS_DELTA_ALPHA_APP_SLUG, BuildConfig.HANDS_DELTA_APP_SLUG)
            assertEquals(HANDS_DELTA_ALPHA_CHANNEL, BuildConfig.HANDS_DELTA_CHANNEL)
        } else {
            assertEquals("", BuildConfig.HANDS_DELTA_APP_SLUG)
            assertEquals("", BuildConfig.HANDS_DELTA_CHANNEL)
        }
    }

    @Test
    fun runtimeGateRequiresEveryCompileAndPackageCoordinate() {
        assertTrue(
            handsDeltaUpdateAllowed(
                configuredEnabled = true,
                buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                channel = HANDS_DELTA_ALPHA_CHANNEL
            )
        )

        val denied =
            listOf(
                handsDeltaUpdateAllowed(
                    configuredEnabled = false,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_ALPHA_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    buildApplicationId = "build.raft.app",
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_ALPHA_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = "build.raft.app",
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_ALPHA_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = "raft-android",
                    channel = HANDS_DELTA_ALPHA_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = "debug"
                )
            )

        denied.forEach(::assertFalse)
    }

    @Test
    fun alphaRouteUsesDedicatedDeltaCoordinates() {
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_ALPHA_DELTA,
                appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                channel = HANDS_DELTA_ALPHA_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                alphaDeltaEnabled = true,
                defaultAppSlug = "raft-android",
                defaultChannel = "debug",
                deltaAppSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                deltaChannel = HANDS_DELTA_ALPHA_CHANNEL
            )
        )
    }

    @Test
    fun deniedDeltaRouteUsesDefaultFullApkCoordinates() {
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_FULL_APK,
                appSlug = "raft-android",
                channel = "main",
                deltaEnabled = false
            ),
            resolveHandsUpdateRoute(
                alphaDeltaEnabled = false,
                defaultAppSlug = "raft-android",
                defaultChannel = "main",
                deltaAppSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                deltaChannel = HANDS_DELTA_ALPHA_CHANNEL
            )
        )
    }

    @Test
    fun callOwnerAllowsOnlyOneConcurrentSdkCall() = runTest {
        val owner = HandsDeltaUpdateCallOwner()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first =
            async {
                owner.runIfEligible(eligible = true) {
                    entered.complete(Unit)
                    release.await()
                    "first"
                }
            }

        entered.await()
        assertTrue(owner.isRunning())
        var secondInvoked = false
        assertNull(
            owner.runIfEligible(eligible = true) {
                secondInvoked = true
                "second"
            }
        )
        assertFalse(secondInvoked)

        release.complete(Unit)
        assertEquals("first", first.await())
        assertFalse(owner.isRunning())
    }

    @Test
    fun callOwnerReleasesAfterSuccessAndAllowsNextVersion() = runTest {
        val owner = HandsDeltaUpdateCallOwner()

        assertEquals("v1", owner.runIfEligible(eligible = true) { "v1" })
        assertFalse(owner.isRunning())
        assertEquals("v2", owner.runIfEligible(eligible = true) { "v2" })
        assertFalse(owner.isRunning())
    }

    @Test
    fun callOwnerReleasesAfterThrow() = runTest {
        val owner = HandsDeltaUpdateCallOwner()

        try {
            owner.runIfEligible(eligible = true) { error("expected") }
            fail("Expected the SDK call to throw")
        } catch (error: IllegalStateException) {
            assertEquals("expected", error.message)
        }
        assertFalse(owner.isRunning())
        assertEquals("retry", owner.runIfEligible(eligible = true) { "retry" })
    }

    @Test
    fun callOwnerReleasesAfterCancellation() = runTest {
        val owner = HandsDeltaUpdateCallOwner()
        val entered = CompletableDeferred<Unit>()
        val job =
            launch {
                owner.runIfEligible(eligible = true) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }

        entered.await()
        assertTrue(owner.isRunning())
        job.cancelAndJoin()
        assertFalse(owner.isRunning())
        assertEquals("retry", owner.runIfEligible(eligible = true) { "retry" })
    }

    @Test
    fun statusOnlyCallDoesNotTakeOwnership() = runTest {
        val owner = HandsDeltaUpdateCallOwner()
        var invoked = false

        assertNull(
            owner.runIfEligible(eligible = false) {
                invoked = true
                "unexpected"
            }
        )
        assertFalse(invoked)
        assertFalse(owner.isRunning())
    }
}
