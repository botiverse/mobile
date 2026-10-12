package build.raft.app.shared

import build.hands.update.models.App
import build.hands.update.models.LatestUpdate
import build.hands.update.models.Patch
import build.hands.update.models.UpdateAsset
import build.hands.update.models.UpdateCheckResponse
import build.raft.app.BuildConfig
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
    fun systemAppLanguageUsesTheResolvedSystemTag() {
        assertEquals("ja-JP", handsUpdateLanguageTag(null, "ja_JP"))
        assertEquals("zh-CN", handsUpdateLanguageTag("system", "zh-CN"))
    }

    @Test
    fun unreadableLanguageStateFallsBackToEnglish() {
        assertEquals("en", handsUpdateLanguageTag(" ", "POSIX"))
        assertEquals("en", handsUpdateLanguageTag(null, null))
    }

    @Test
    fun verifiedLocalApkRemainsInstallableWhenOfferStillMatches() {
        val decision =
            handsUpdateBridgeDecision(
                sdkStateWire = "ready_to_install",
                sdkTarget = updateTarget(1_000_011L),
                sdkInstalledVersionCode = 1_000_010L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(1_000_011L)
            )

        assertEquals(SharedSettingsUpdateTransactionState.ReadyToInstall, decision.state)
        assertEquals(1_000_011L, decision.targetVersionCode)
        assertTrue(decision.updateAvailable)
        assertFalse(decision.targetDrifted)
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
    fun buildIdentityRemainsCaseSensitiveLikeTheSdkAuthority() {
        val decision =
            handsUpdateBridgeDecision(
                sdkStateWire = "ready_to_install",
                sdkTarget = updateTarget(1_000_011L, buildId = "BUILD-A"),
                sdkInstalledVersionCode = 1_000_010L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(1_000_011L, buildId = "build-a")
            )

        assertEquals(SharedSettingsUpdateTransactionState.Stale, decision.state)
        assertTrue(decision.targetDrifted)
    }

    @Test
    fun sameBuildReplacementWithDifferentTargetShaFailsOldLocalApkClosed() {
        val decision =
            handsUpdateBridgeDecision(
                sdkStateWire = "installer_opened",
                sdkTarget =
                    updateTarget(
                        versionCode = 1_000_011L,
                        buildId = "build-a",
                        assetSha256 = "a".repeat(64)
                    ),
                sdkInstalledVersionCode = 1_000_010L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget =
                    updateTarget(
                        versionCode = 1_000_011L,
                        buildId = "build-a",
                        assetSha256 = "b".repeat(64)
                    )
            )

        assertEquals(SharedSettingsUpdateTransactionState.Stale, decision.state)
        assertTrue(decision.targetDrifted)
    }

    @Test
    fun normalizedFullIdentityKeepsVerifiedLocalApkInstallable() {
        val decision =
            handsUpdateBridgeDecision(
                sdkStateWire = "ready_to_install",
                sdkTarget =
                    updateTarget(
                        versionCode = 1_000_011L,
                        buildId = " build-a ",
                        assetSha256 = "A".repeat(64)
                    ),
                sdkInstalledVersionCode = 1_000_010L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget =
                    updateTarget(
                        versionCode = 1_000_011L,
                        buildId = "build-a",
                        assetSha256 = "a".repeat(64)
                    )
            )

        assertEquals(SharedSettingsUpdateTransactionState.ReadyToInstall, decision.state)
        assertFalse(decision.targetDrifted)
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
    fun offlineOfferCheckPreservesSdkTransactionWithoutGuessingDrift() {
        val decision =
            handsUpdateBridgeDecision(
                sdkStateWire = "downloading",
                sdkTarget = updateTarget(1_000_011L),
                sdkInstalledVersionCode = 1_000_010L,
                sdkRetryable = false,
                offerChecked = false,
                offerAvailable = false,
                offerTarget = updateTarget(null)
            )

        assertEquals(SharedSettingsUpdateTransactionState.Downloading, decision.state)
        assertTrue(decision.updateAvailable)
        assertFalse(decision.targetDrifted)
    }

    @Test
    fun checkingTransactionCanUseFreshOfferBeforeSdkPersistsItsTarget() {
        val decision =
            handsUpdateBridgeDecision(
                sdkStateWire = "checking",
                sdkTarget = updateTarget(null),
                sdkInstalledVersionCode = 1_000_010L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(1_000_011L)
            )

        assertEquals(SharedSettingsUpdateTransactionState.Checking, decision.state)
        assertEquals(1_000_011L, decision.targetVersionCode)
        assertTrue(decision.updateAvailable)
        assertFalse(decision.targetDrifted)
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
    fun structuredUpdateEventsAreFeedbackSafeAndLocaleVisible() {
        assertEquals(
            "update_event name=patch_fallback state=downloading target=1000011 " +
                "error=patch_apply_failed requested_lang=zh-CN resolved_lang=zh-CN",
            handsUpdateEventLogLine(
                name = "patch_fallback",
                stateWire = "downloading",
                targetVersionCode = 1_000_011L,
                errorCode = "patch_apply_failed",
                requestedLanguageTag = "zh-CN",
                resolvedLanguageTag = "zh-CN"
            )
        )
        assertEquals(
            "update_event name=bad_line state=failed target=0 error=- requested_lang=- resolved_lang=-",
            handsUpdateEventLogLine(
                name = "bad\nline",
                stateWire = "failed",
                targetVersionCode = null,
                errorCode = null,
                requestedLanguageTag = null,
                resolvedLanguageTag = null
            )
        )
    }

    @Test
    fun onlyPersistedStateTransitionsRequestAnotherUiRefresh() {
        assertTrue(handsUpdateEventShouldRefresh("state_changed"))
        assertFalse(handsUpdateEventShouldRefresh("check_started"))
        assertFalse(handsUpdateEventShouldRefresh("patch_fallback"))
        assertFalse(handsUpdateEventShouldRefresh("cleanup_failed"))
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
    fun displaySizeProjectionRemainsClosedOverSdkFacts() {
        val outputFields =
            HandsUpdateDisplaySizes::class.java.declaredFields
                .filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }
                .mapTo(mutableSetOf()) { it.name }
        val sourceFields =
            mapOf(
                "patchSizeBytes" to (Patch::class.java to "size_bytes"),
                "fullSizeBytes" to (UpdateAsset::class.java to "size_bytes")
            )

        assertEquals(sourceFields.keys, outputFields)
        sourceFields.forEach { (outputField, source) ->
            val (sourceType, sourceField) = source
            assertTrue(
                "$outputField must map to declared SDK fact $sourceField",
                sourceType.declaredFields.any { it.name == sourceField }
            )
        }
    }

    @Test
    fun displaySizesHideInvalidOrInapplicablePatch() {
        val rejected =
            listOf(
                "delta disabled" to updateResponse().displaySizes(deltaEnabled = false),
                "wrong base" to updateResponse(patchFromVersionCode = 1_000_013L).displaySizes(),
                "unsupported algorithm" to updateResponse(patchAlgorithm = "future-patcher-v2").displaySizes(),
                "missing target hash" to updateResponse(patchTargetSha256 = "").displaySizes(),
                "empty patch" to updateResponse(patchSizeBytes = 0L).displaySizes()
            )

        rejected.forEach { (reason, sizes) ->
            assertNull(reason, sizes.patchSizeBytes)
            assertEquals(reason, 94_411_540L, sizes.fullSizeBytes)
        }
        assertNull(updateResponse(fullSizeBytes = 0L).displaySizes().fullSizeBytes)
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

    private fun UpdateCheckResponse.displaySizes(
        deltaEnabled: Boolean = true
    ): HandsUpdateDisplaySizes =
        handsUpdateDisplaySizes(deltaEnabled = deltaEnabled)

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
