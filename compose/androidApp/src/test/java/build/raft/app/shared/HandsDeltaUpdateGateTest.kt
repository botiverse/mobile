package build.raft.app.shared

import build.hands.update.INSTALL_PERMISSION_REQUIRED
import build.hands.update.models.App
import build.hands.update.models.LatestUpdate
import build.hands.update.models.Patch
import build.hands.update.models.UpdateAsset
import build.hands.update.models.UpdateCheckResponse
import build.raft.app.BuildConfig
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftAppUpdatePermissionResumeRepository
import build.raft.app.data.SharedSettingsUpdateTransactionState
import java.lang.reflect.Modifier
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
    fun playBuildDisablesHandsApkUpdateBridge() {
        val response = handsApkUpdateDisabledResponse()

        assertEquals("ok", response["status"])
        assertEquals("hands_apk_update_disabled", response["code"])
        assertEquals(false, response["updateCheckSupported"])
        assertEquals(false, response["updateAvailable"])
    }

    @Test
    fun explicitAppLanguageWinsOverTheSystemLocale() {
        assertEquals("zh-CN", handsUpdateLanguageTag("zh-CN", "en-US"))
        assertEquals("ja", handsUpdateLanguageTag("ja", "zh-CN"))
    }

    @Test
    fun sameVersionReplacementWithDifferentBuildFailsOldLocalApkClosed() {
        val decision =
            handsUpdateBridgeDecision(
                sdkStateWire = "ready_to_install",
                sdkTarget = updateTarget(1_000_011L, buildId = "build-a"),
                sdkInstalledVersionCode = 1_000_010L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(1_000_011L, buildId = "build-b")
            )

        assertEquals(SharedSettingsUpdateTransactionState.Stale, decision.state)
        assertEquals(1_000_011L, decision.targetVersionCode)
        assertTrue(decision.updateAvailable)
        assertTrue(decision.retryable)
        assertTrue(decision.targetDrifted)
    }

    @Test
    fun newerOrCancelledOfferFailsOldLocalApkClosedToStale() {
        val newer =
            handsUpdateBridgeDecision(
                sdkStateWire = "installer_opened",
                sdkTarget = updateTarget(1_000_011L),
                sdkInstalledVersionCode = 1_000_010L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(1_000_012L)
            )
        val cancelled =
            handsUpdateBridgeDecision(
                sdkStateWire = "ready_to_install",
                sdkTarget = updateTarget(1_000_011L),
                sdkInstalledVersionCode = 1_000_010L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = false,
                offerTarget = updateTarget(null)
            )

        assertEquals(SharedSettingsUpdateTransactionState.Stale, newer.state)
        assertEquals(1_000_012L, newer.targetVersionCode)
        assertTrue(newer.updateAvailable)
        assertTrue(newer.retryable)
        assertTrue(newer.targetDrifted)
        assertEquals(SharedSettingsUpdateTransactionState.Stale, cancelled.state)
        assertFalse(cancelled.updateAvailable)
        assertFalse(cancelled.retryable)
        assertTrue(cancelled.targetDrifted)
    }

    @Test
    fun installedVersionWinsOverStaleProcessSnapshot() {
        val decision =
            handsUpdateBridgeDecision(
                sdkStateWire = "installer_opened",
                sdkTarget = updateTarget(1_000_011L),
                sdkInstalledVersionCode = 1_000_011L,
                sdkRetryable = true,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(1_000_011L)
            )

        assertEquals(SharedSettingsUpdateTransactionState.Installed, decision.state)
        assertFalse(decision.updateAvailable)
        assertFalse(decision.retryable)
        assertFalse(decision.targetDrifted)
    }

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
    fun applicableDeltaOfferDisplaysPatchAndFullFallbackSizes() {
        assertEquals(
            HandsUpdateDisplaySizes(
                patchSizeBytes = 95_256L,
                fullSizeBytes = 94_411_540L
            ),
            updateResponse().handsUpdateDisplaySizes(deltaEnabled = true)
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

    /**
     * Android kills Raft when "Install unknown apps" changes, so the marker is stored: the resume
     * after the restart (a new repository over the same store) takes it once, whatever the answer.
     */
    @Test
    fun permissionResumeReopensOnceWhenAllowedAndNeverLeadsBackToSettings() = runTest {
        listOf(
            Triple(false, true, HandsDeltaPermissionResume.NotWaiting),
            Triple(true, true, HandsDeltaPermissionResume.Reopened),
            Triple(true, false, HandsDeltaPermissionResume.StillDenied)
        ).forEach { (marked, allowed, expected) ->
            val stored = InMemoryRaftKeyValueStore()
            if (marked) RaftAppUpdatePermissionResumeRepository { stored }.mark()
            val afterRestart = RaftAppUpdatePermissionResumeRepository { stored }
            var permissionReads = 0
            var reopens = 0
            val resume: suspend () -> HandsDeltaPermissionResume = {
                handsDeltaPermissionResume(
                    marker = afterRestart,
                    canInstall = {
                        permissionReads += 1
                        allowed
                    },
                    reopen = { reopens += 1 }
                )
            }

            val case = "marked=$marked allowed=$allowed"
            assertEquals(case, expected, resume())
            assertEquals(case, HandsDeltaPermissionResume.NotWaiting, resume())
            assertEquals(case, if (expected == HandsDeltaPermissionResume.Reopened) 1 else 0, reopens)
            assertEquals(case, if (marked) 1 else 0, permissionReads)
        }

        // A store that cannot be opened keeps nothing: the resume neither throws nor opens anything.
        val unavailable = RaftAppUpdatePermissionResumeRepository { error("store unavailable") }
        unavailable.mark()
        var touched = false
        assertEquals(
            HandsDeltaPermissionResume.NotWaiting,
            handsDeltaPermissionResume(
                marker = unavailable,
                canInstall = {
                    touched = true
                    true
                },
                reopen = { touched = true }
            )
        )
        assertFalse(touched)
    }

    private fun updateTarget(
        versionCode: Long?,
        buildId: String? = versionCode?.let { "build-$it" },
        assetSha256: String? = versionCode?.toString(16)?.padStart(64, '0')
    ): HandsUpdateTargetIdentity =
        HandsUpdateTargetIdentity(
            versionCode = versionCode,
            buildId = buildId,
            assetSha256 = assetSha256
        )

    private fun updateResponse(
        currentVersionCode: Long = 1_000_014L,
        patchFromVersionCode: Long? = currentVersionCode,
        patchAlgorithm: String? = "archive-patcher-v1+gzip",
        patchTargetSha256: String? = "a".repeat(64),
        patchSizeBytes: Long? = 95_256L,
        fullSizeBytes: Long? = 94_411_540L
    ): UpdateCheckResponse =
        UpdateCheckResponse(
            update_available = true,
            app = App(slug = "raft-android-alpha-delta", platform = "android"),
            channel = "alpha-delta",
            current_version_code = currentVersionCode,
            latest_version_code = 1_000_015L,
            latest =
                LatestUpdate(
                    build_id = "build-1_000_015",
                    version = "1.0.15",
                    version_code = 1_000_015L,
                    released_at = 1L
                ),
            asset =
                UpdateAsset(
                    platform = "android",
                    filetype = "apk",
                    size_bytes = fullSizeBytes ?: 0L,
                    download_url = "https://hands.invalid/full.apk"
                ),
            patch =
                Patch(
                    from_version_code = patchFromVersionCode ?: 0L,
                    algorithm = patchAlgorithm.orEmpty(),
                    download_url = "https://hands.invalid/patch.bin",
                    size_bytes = patchSizeBytes ?: 0L,
                    target_sha256 = patchTargetSha256
                )
        )
}
