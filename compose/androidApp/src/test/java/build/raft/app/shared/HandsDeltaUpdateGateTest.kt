package build.raft.app.shared

import build.hands.update.HandsUpdateProgress
import build.hands.update.HandsUpdateState
import build.hands.update.HandsUpdateStatus
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
        assertFalse(decision.supersededPriorInstall)
    }

    @Test
    fun newerOfferOverridesCompletedInstalledTransactionAndClearsStaleError() {
        listOf("installed", "installer_opened", "failed").forEach { priorState ->
            val decision =
                handsUpdateBridgeDecision(
                    sdkStateWire = priorState,
                    sdkTarget = updateTarget(13_000_003L),
                    sdkInstalledVersionCode = 13_000_004L,
                    sdkRetryable = true,
                    offerChecked = true,
                    offerAvailable = true,
                    offerTarget = updateTarget(13_000_005L)
                )

            assertEquals(priorState, SharedSettingsUpdateTransactionState.Idle, decision.state)
            assertEquals(priorState, 13_000_005L, decision.targetVersionCode)
            assertEquals(priorState, 13_000_004L, decision.installedVersionCode)
            assertTrue(priorState, decision.updateAvailable)
            assertFalse(priorState, decision.retryable)
            assertFalse(priorState, decision.targetDrifted)
            assertTrue(priorState, decision.supersededPriorInstall)
            assertNull(
                priorState,
                handsUpdateBridgeErrorCode(
                    decision = decision,
                    offerAvailable = true,
                    sdkErrorCode = "download_failed"
                )
            )
        }
    }

    @Test
    fun installedTransactionStaysInstalledUnlessCheckedOfferIsStrictlyNewer() {
        val sameVersion =
            handsUpdateBridgeDecision(
                sdkStateWire = "installed",
                sdkTarget = updateTarget(13_000_004L),
                sdkInstalledVersionCode = 13_000_004L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(13_000_004L)
            )
        val olderVersion =
            handsUpdateBridgeDecision(
                sdkStateWire = "installed",
                sdkTarget = updateTarget(13_000_003L),
                sdkInstalledVersionCode = 13_000_004L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(13_000_003L)
            )
        val unavailableOffer =
            handsUpdateBridgeDecision(
                sdkStateWire = "installed",
                sdkTarget = updateTarget(13_000_003L),
                sdkInstalledVersionCode = 13_000_004L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = false,
                offerTarget = updateTarget(13_000_005L)
            )
        val uncheckedOffer =
            handsUpdateBridgeDecision(
                sdkStateWire = "installed",
                sdkTarget = updateTarget(13_000_003L),
                sdkInstalledVersionCode = 13_000_004L,
                sdkRetryable = false,
                offerChecked = false,
                offerAvailable = true,
                offerTarget = updateTarget(13_000_005L)
            )
        val missingOfferTarget =
            handsUpdateBridgeDecision(
                sdkStateWire = "installed",
                sdkTarget = updateTarget(13_000_003L),
                sdkInstalledVersionCode = 13_000_004L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(null)
            )
        val unknownInstalledSameTarget =
            handsUpdateBridgeDecision(
                sdkStateWire = "installed",
                sdkTarget = updateTarget(13_000_004L),
                sdkInstalledVersionCode = null,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(13_000_004L)
            )
        val unknownInstalledNewerTarget =
            handsUpdateBridgeDecision(
                sdkStateWire = "installed",
                sdkTarget = updateTarget(13_000_004L),
                sdkInstalledVersionCode = null,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(13_000_005L)
            )
        val unknownInstalledNullSdkTarget =
            handsUpdateBridgeDecision(
                sdkStateWire = "installed",
                sdkTarget = updateTarget(null),
                sdkInstalledVersionCode = null,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(13_000_005L)
            )

        listOf(
            "sameVersion" to sameVersion,
            "olderVersion" to olderVersion,
            "unavailableOffer" to unavailableOffer,
            "uncheckedOffer" to uncheckedOffer,
            "missingOfferTarget" to missingOfferTarget,
            "unknownInstalledSameTarget" to unknownInstalledSameTarget
        ).forEach { (label, decision) ->
            assertEquals(label, SharedSettingsUpdateTransactionState.Installed, decision.state)
            assertFalse(label, decision.updateAvailable)
            assertFalse(label, decision.retryable)
            assertFalse(label, decision.targetDrifted)
            assertFalse(label, decision.supersededPriorInstall)
        }

        listOf(
            "unknownInstalledNewerTarget" to unknownInstalledNewerTarget,
            "unknownInstalledNullSdkTarget" to unknownInstalledNullSdkTarget
        ).forEach { (label, decision) ->
            assertEquals(label, SharedSettingsUpdateTransactionState.Idle, decision.state)
            assertEquals(label, 13_000_005L, decision.targetVersionCode)
            assertTrue(label, decision.updateAvailable)
            assertFalse(label, decision.retryable)
            assertFalse(label, decision.targetDrifted)
            assertTrue(label, decision.supersededPriorInstall)
        }
    }

    @Test
    fun bridgeErrorCodePreservesActiveErrorsAndClearsInstalledOrSupersededErrors() {
        val nativeIdle =
            handsUpdateBridgeDecision(
                sdkStateWire = "idle",
                sdkTarget = updateTarget(null),
                sdkInstalledVersionCode = 13_000_004L,
                sdkRetryable = false,
                offerChecked = false,
                offerAvailable = false,
                offerTarget = updateTarget(null)
            )
        val nativeFailed =
            handsUpdateBridgeDecision(
                sdkStateWire = "failed",
                sdkTarget = updateTarget(13_000_005L),
                sdkInstalledVersionCode = 13_000_004L,
                sdkRetryable = true,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(13_000_005L)
            )
        val driftedAvailable =
            handsUpdateBridgeDecision(
                sdkStateWire = "ready_to_install",
                sdkTarget = updateTarget(13_000_005L),
                sdkInstalledVersionCode = 13_000_004L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = true,
                offerTarget = updateTarget(13_000_006L)
            )
        val driftedWithdrawn =
            handsUpdateBridgeDecision(
                sdkStateWire = "ready_to_install",
                sdkTarget = updateTarget(13_000_005L),
                sdkInstalledVersionCode = 13_000_004L,
                sdkRetryable = false,
                offerChecked = true,
                offerAvailable = false,
                offerTarget = updateTarget(null)
            )
        val installed =
            handsUpdateBridgeDecision(
                sdkStateWire = "failed",
                sdkTarget = updateTarget(13_000_004L),
                sdkInstalledVersionCode = 13_000_004L,
                sdkRetryable = true,
                offerChecked = true,
                offerAvailable = false,
                offerTarget = updateTarget(13_000_004L)
            )

        assertEquals("network_error", handsUpdateBridgeErrorCode(nativeIdle, offerAvailable = false, sdkErrorCode = "network_error"))
        assertEquals("sha256_mismatch", handsUpdateBridgeErrorCode(nativeFailed, offerAvailable = true, sdkErrorCode = "sha256_mismatch"))
        assertEquals("target_changed", handsUpdateBridgeErrorCode(driftedAvailable, offerAvailable = true, sdkErrorCode = null))
        assertEquals("offer_withdrawn", handsUpdateBridgeErrorCode(driftedWithdrawn, offerAvailable = false, sdkErrorCode = null))
        assertNull(handsUpdateBridgeErrorCode(installed, offerAvailable = false, sdkErrorCode = "download_failed"))
    }

    @Test
    fun generatedVariantConfigEnablesDeltaOnlyForExactAlphaBetaPreviewAndMainRelease() {
        val isExactAlpha =
            BuildConfig.BUILD_TYPE == "alpha" &&
                BuildConfig.APPLICATION_ID == HANDS_DELTA_ALPHA_APPLICATION_ID
        val isExactAlphaChannelRelease =
            BuildConfig.BUILD_TYPE == "release" &&
                BuildConfig.APPLICATION_ID == HANDS_DELTA_PRODUCTION_APPLICATION_ID &&
                BuildConfig.HANDS_APP_SLUG == HANDS_DELTA_PRODUCTION_APP_SLUG &&
                BuildConfig.HANDS_CHANNEL == HANDS_DELTA_RELEASE_ALPHA_CHANNEL
        val isExactBetaRelease =
            BuildConfig.BUILD_TYPE == "release" &&
                BuildConfig.APPLICATION_ID == HANDS_DELTA_PRODUCTION_APPLICATION_ID &&
                BuildConfig.HANDS_APP_SLUG == HANDS_DELTA_PRODUCTION_APP_SLUG &&
                BuildConfig.HANDS_CHANNEL == HANDS_DELTA_BETA_CHANNEL
        val isExactPreviewRelease =
            BuildConfig.BUILD_TYPE == "release" &&
                BuildConfig.APPLICATION_ID == HANDS_DELTA_PRODUCTION_APPLICATION_ID &&
                BuildConfig.HANDS_APP_SLUG == HANDS_DELTA_PRODUCTION_APP_SLUG &&
                BuildConfig.HANDS_CHANNEL == HANDS_DELTA_PREVIEW_CHANNEL
        val isExactMainRelease =
            BuildConfig.BUILD_TYPE == "release" &&
                BuildConfig.APPLICATION_ID == HANDS_DELTA_PRODUCTION_APPLICATION_ID &&
                BuildConfig.HANDS_APP_SLUG == HANDS_DELTA_PRODUCTION_APP_SLUG &&
                BuildConfig.HANDS_CHANNEL == HANDS_DELTA_MAIN_CHANNEL
        val isPlay = BuildConfig.BUILD_TYPE == "play"

        assertEquals(!isPlay, BuildConfig.HANDS_APK_UPDATE_ENABLED)
        assertEquals(
            isExactAlpha || isExactAlphaChannelRelease || isExactBetaRelease || isExactPreviewRelease || isExactMainRelease,
            BuildConfig.HANDS_DELTA_UPDATE_ENABLED
        )
        when {
            isExactAlpha -> {
                assertEquals(HANDS_DELTA_ALPHA_APP_SLUG, BuildConfig.HANDS_DELTA_APP_SLUG)
                assertEquals(HANDS_DELTA_ALPHA_CHANNEL, BuildConfig.HANDS_DELTA_CHANNEL)
            }
            isExactAlphaChannelRelease -> {
                assertEquals(HANDS_DELTA_PRODUCTION_APP_SLUG, BuildConfig.HANDS_DELTA_APP_SLUG)
                assertEquals(HANDS_DELTA_RELEASE_ALPHA_CHANNEL, BuildConfig.HANDS_DELTA_CHANNEL)
            }
            isExactBetaRelease -> {
                assertEquals(HANDS_DELTA_PRODUCTION_APP_SLUG, BuildConfig.HANDS_DELTA_APP_SLUG)
                assertEquals(HANDS_DELTA_BETA_CHANNEL, BuildConfig.HANDS_DELTA_CHANNEL)
            }
            isExactPreviewRelease -> {
                assertEquals(HANDS_DELTA_PRODUCTION_APP_SLUG, BuildConfig.HANDS_DELTA_APP_SLUG)
                assertEquals(HANDS_DELTA_PREVIEW_CHANNEL, BuildConfig.HANDS_DELTA_CHANNEL)
            }
            isExactMainRelease -> {
                assertEquals(HANDS_DELTA_PRODUCTION_APP_SLUG, BuildConfig.HANDS_DELTA_APP_SLUG)
                assertEquals(HANDS_DELTA_MAIN_CHANNEL, BuildConfig.HANDS_DELTA_CHANNEL)
            }
            else -> {
                assertEquals("", BuildConfig.HANDS_DELTA_APP_SLUG)
                assertEquals("", BuildConfig.HANDS_DELTA_CHANNEL)
            }
        }

        val allowed =
            handsDeltaUpdateAllowed(
                configuredEnabled = BuildConfig.HANDS_DELTA_UPDATE_ENABLED,
                apkUpdateEnabled = BuildConfig.HANDS_APK_UPDATE_ENABLED,
                buildApplicationId = BuildConfig.APPLICATION_ID,
                runtimePackageName = BuildConfig.APPLICATION_ID,
                appSlug = BuildConfig.HANDS_DELTA_APP_SLUG,
                channel = BuildConfig.HANDS_DELTA_CHANNEL
            )
        assertEquals(
            isExactAlpha || isExactAlphaChannelRelease || isExactBetaRelease || isExactPreviewRelease || isExactMainRelease,
            allowed
        )

        val route =
            resolveHandsUpdateRoute(
                deltaEnabled = allowed,
                defaultAppSlug = BuildConfig.HANDS_APP_SLUG,
                defaultChannel = BuildConfig.HANDS_CHANNEL,
                deltaAppSlug = BuildConfig.HANDS_DELTA_APP_SLUG,
                deltaChannel = BuildConfig.HANDS_DELTA_CHANNEL
            )
        when {
            isExactAlpha ->
                assertEquals(
                    HandsUpdateRoute(
                        mode = HANDS_UPDATE_MODE_ALPHA_DELTA,
                        appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                        channel = HANDS_DELTA_ALPHA_CHANNEL,
                        deltaEnabled = true
                    ),
                    route
                )
            isExactAlphaChannelRelease ->
                assertEquals(
                    HandsUpdateRoute(
                        mode = HANDS_UPDATE_MODE_RELEASE_ALPHA_DELTA,
                        appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                        channel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL,
                        deltaEnabled = true
                    ),
                    route
                )
            isExactBetaRelease ->
                assertEquals(
                    HandsUpdateRoute(
                        mode = HANDS_UPDATE_MODE_BETA_DELTA,
                        appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                        channel = HANDS_DELTA_BETA_CHANNEL,
                        deltaEnabled = true
                    ),
                    route
                )
            isExactPreviewRelease ->
                assertEquals(
                    HandsUpdateRoute(
                        mode = HANDS_UPDATE_MODE_MAIN_DELTA,
                        appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                        channel = HANDS_DELTA_MAIN_CHANNEL,
                        deltaEnabled = true
                    ),
                    route
                )
            isExactMainRelease ->
                assertEquals(
                    HandsUpdateRoute(
                        mode = HANDS_UPDATE_MODE_MAIN_DELTA,
                        appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                        channel = HANDS_DELTA_MAIN_CHANNEL,
                        deltaEnabled = true
                    ),
                    route
                )
            else ->
                assertEquals(
                    HandsUpdateRoute(
                        mode = HANDS_UPDATE_MODE_FULL_APK,
                        appSlug = BuildConfig.HANDS_APP_SLUG,
                        channel = BuildConfig.HANDS_CHANNEL,
                        deltaEnabled = false
                    ),
                    route
                )
        }
    }

    @Test
    fun runtimeGateRequiresEveryCompileAndPackageCoordinate() {
        assertTrue(
            handsDeltaUpdateAllowed(
                configuredEnabled = true,
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                channel = HANDS_DELTA_ALPHA_CHANNEL
            )
        )
        assertTrue(
            handsDeltaUpdateAllowed(
                configuredEnabled = true,
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL
            )
        )
        assertTrue(
            handsDeltaUpdateAllowed(
                configuredEnabled = true,
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_BETA_CHANNEL
            )
        )
        assertTrue(
            handsDeltaUpdateAllowed(
                configuredEnabled = true,
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = "canary"
            )
        )
        assertTrue(
            handsDeltaUpdateAllowed(
                configuredEnabled = true,
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = "canary-1"
            )
        )
        assertTrue(
            handsDeltaUpdateAllowed(
                configuredEnabled = true,
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_PREVIEW_CHANNEL
            )
        )
        assertTrue(
            handsDeltaUpdateAllowed(
                configuredEnabled = true,
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_MAIN_CHANNEL
            )
        )

        val denied =
            listOf(
                // Configured disabled for Alpha, Preview, and Main
                handsDeltaUpdateAllowed(
                    configuredEnabled = false,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_ALPHA_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = false,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_PREVIEW_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = false,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_MAIN_CHANNEL
                ),
                // Play (apkUpdateEnabled=false) denied even with exact Main, Beta, Preview, or Alpha tuples
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = false,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_MAIN_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = false,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_BETA_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = false,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_PREVIEW_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = false,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_ALPHA_CHANNEL
                ),
                // Non-release channels on build.raft.app stay full-APK: debug, nightly, empty
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = "debug"
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = "nightly"
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = ""
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = "../bad"
                ),
                // Other application IDs or app slugs
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = "build.raft.app.connectedtest",
                    runtimePackageName = "build.raft.app.connectedtest",
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_PREVIEW_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = "build.raft.app.connectedtest",
                    runtimePackageName = "build.raft.app.connectedtest",
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_MAIN_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = "raft-android-other",
                    channel = HANDS_DELTA_PREVIEW_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = "raft-android-other",
                    channel = HANDS_DELTA_MAIN_CHANNEL
                ),
                // Cross-paired Alpha and Production package / slug / channel coordinates
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_ALPHA_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_ALPHA_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_PREVIEW_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_MAIN_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_ALPHA_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_PREVIEW_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_MAIN_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_MAIN_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_PREVIEW_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_MAIN_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_ALPHA_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = HANDS_DELTA_ALPHA_CHANNEL
                ),
                handsDeltaUpdateAllowed(
                    configuredEnabled = true,
                    apkUpdateEnabled = true,
                    buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                    appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                    channel = "debug"
                )
            )

        denied.forEach(::assertFalse)
    }

    @Test
    fun routeResolutionSelectsAlphaDeltaBetaDeltaPreviewDeltaMainDeltaOrFullApk() {
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_ALPHA_DELTA,
                appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                channel = HANDS_DELTA_ALPHA_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = "raft-android",
                defaultChannel = "debug",
                deltaAppSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                deltaChannel = HANDS_DELTA_ALPHA_CHANNEL
            )
        )
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_RELEASE_ALPHA_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = "raft-android",
                defaultChannel = "alpha",
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL
            )
        )
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_BETA_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_BETA_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = "raft-android",
                defaultChannel = "beta",
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_BETA_CHANNEL
            )
        )
        assertEquals(
            HandsUpdateRoute(
                mode = "canary_channel_delta",
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = "canary",
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = "raft-android",
                defaultChannel = "canary",
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = "canary"
            )
        )
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_MAIN_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_MAIN_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = "raft-android",
                defaultChannel = "preview",
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_PREVIEW_CHANNEL
            )
        )
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_MAIN_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_MAIN_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = "raft-android",
                defaultChannel = "main",
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_MAIN_CHANNEL
            )
        )
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_FULL_APK,
                appSlug = "raft-android",
                channel = "debug",
                deltaEnabled = false
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = false,
                defaultAppSlug = "raft-android",
                defaultChannel = "debug",
                deltaAppSlug = "",
                deltaChannel = ""
            )
        )
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_FULL_APK,
                appSlug = "raft-android",
                channel = "main",
                deltaEnabled = false
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = false,
                defaultAppSlug = "raft-android",
                defaultChannel = "main",
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_MAIN_CHANNEL
            )
        )
        // Unrecognized or cross-paired delta tuple fails closed to full_apk even if deltaEnabled is true.
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_FULL_APK,
                appSlug = "raft-android",
                channel = "debug",
                deltaEnabled = false
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = "raft-android",
                defaultChannel = "debug",
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = "debug"
            )
        )
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_FULL_APK,
                appSlug = "raft-android",
                channel = "main",
                deltaEnabled = false
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = "raft-android",
                defaultChannel = "main",
                deltaAppSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                deltaChannel = HANDS_DELTA_MAIN_CHANNEL
            )
        )
    }

    @Test
    fun previewRetiresToMainAndRuntimeChannelOverrideFollowsServerConfiguredTargetChannel() {
        assertEquals(HANDS_DELTA_MAIN_CHANNEL, normalizeHandsReleaseChannel("preview"))
        assertEquals(HANDS_DELTA_MAIN_CHANNEL, normalizeHandsReleaseChannel(" PREVIEW "))
        assertEquals(HANDS_DELTA_MAIN_CHANNEL, normalizeHandsReleaseChannel("main"))
        assertEquals(HANDS_DELTA_BETA_CHANNEL, normalizeHandsReleaseChannel("beta"))
        assertEquals(HANDS_DELTA_RELEASE_ALPHA_CHANNEL, normalizeHandsReleaseChannel("alpha"))
        assertEquals("canary", normalizeHandsReleaseChannel("canary"))
        assertEquals("debug", normalizeHandsReleaseChannel("debug"))

        // Persisted overrides capture intent ("early_access" vs "main"); legacy 1.31.0 "alpha" and any
        // valid dynamic channel slug normalize to "early_access" so users track the live server variant.
        assertEquals(HANDS_OVERRIDE_EARLY_ACCESS, normalizeHandsRuntimeOverrideChannel("early_access"))
        assertEquals(HANDS_OVERRIDE_EARLY_ACCESS, normalizeHandsRuntimeOverrideChannel(" EARLY_ACCESS "))
        assertEquals(HANDS_OVERRIDE_EARLY_ACCESS, normalizeHandsRuntimeOverrideChannel("beta"))
        assertEquals(HANDS_OVERRIDE_EARLY_ACCESS, normalizeHandsRuntimeOverrideChannel(" BETA "))
        assertEquals(HANDS_OVERRIDE_EARLY_ACCESS, normalizeHandsRuntimeOverrideChannel("ALPHA"))
        assertEquals(HANDS_OVERRIDE_EARLY_ACCESS, normalizeHandsRuntimeOverrideChannel("alpha"))
        assertEquals(HANDS_OVERRIDE_EARLY_ACCESS, normalizeHandsRuntimeOverrideChannel("canary"))
        assertEquals(HANDS_DELTA_MAIN_CHANNEL, normalizeHandsRuntimeOverrideChannel("main"))
        assertNull(normalizeHandsRuntimeOverrideChannel("preview"))
        assertNull(normalizeHandsRuntimeOverrideChannel("debug"))
        assertNull(normalizeHandsRuntimeOverrideChannel("nightly"))
        assertNull(normalizeHandsRuntimeOverrideChannel("alpha-delta"))
        assertNull(normalizeHandsRuntimeOverrideChannel("../bad"))
        assertNull(normalizeHandsRuntimeOverrideChannel(null))

        assertTrue(
            handsRuntimeChannelSwitchSupported(
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_MAIN_CHANNEL
            )
        )
        assertTrue(
            handsRuntimeChannelSwitchSupported(
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_BETA_CHANNEL
            )
        )
        assertTrue(
            handsRuntimeChannelSwitchSupported(
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = "canary"
            )
        )
        assertTrue(
            handsRuntimeChannelSwitchSupported(
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_PREVIEW_CHANNEL
            )
        )
        assertTrue(
            handsRuntimeChannelSwitchSupported(
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL
            )
        )
        assertFalse(
            handsRuntimeChannelSwitchSupported(
                apkUpdateEnabled = false,
                buildApplicationId = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_PRODUCTION_APPLICATION_ID,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_MAIN_CHANNEL
            )
        )
        assertFalse(
            handsRuntimeChannelSwitchSupported(
                apkUpdateEnabled = true,
                buildApplicationId = HANDS_DELTA_ALPHA_APPLICATION_ID,
                runtimePackageName = HANDS_DELTA_ALPHA_APPLICATION_ID,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = "debug"
            )
        )

        // Switching a production main/preview build to Early access enables beta_channel_delta by default.
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_BETA_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_BETA_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_MAIN_CHANNEL,
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_MAIN_CHANNEL,
                overrideChannel = HANDS_OVERRIDE_EARLY_ACCESS,
                channelSwitchSupported = true,
                earlyAccessVisible = true,
                earlyAccessDefaultOn = false,
                earlyAccessChannel = HANDS_DELTA_BETA_CHANNEL
            )
        )
        // Server variant "canary" routes BOTH defaultOn and explicit opt-in (including legacy "alpha"
        // prefs) to canary with delta updates enabled.
        assertEquals(
            HandsUpdateRoute(
                mode = "canary_channel_delta",
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = "canary",
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_MAIN_CHANNEL,
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_MAIN_CHANNEL,
                overrideChannel = null,
                channelSwitchSupported = true,
                earlyAccessVisible = true,
                earlyAccessDefaultOn = true,
                earlyAccessChannel = "canary"
            )
        )
        assertEquals(
            HandsUpdateRoute(
                mode = "canary_channel_delta",
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = "canary",
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_MAIN_CHANNEL,
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_MAIN_CHANNEL,
                overrideChannel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL,
                channelSwitchSupported = true,
                earlyAccessVisible = true,
                earlyAccessDefaultOn = false,
                earlyAccessChannel = "canary"
            )
        )
        // Invalid or production earlyAccessChannel variants safely fall back to "beta".
        for (invalidTarget in listOf("", "main", "preview", "../bad")) {
            assertEquals(
                invalidTarget,
                HandsUpdateRoute(
                    mode = HANDS_UPDATE_MODE_BETA_DELTA,
                    appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    channel = HANDS_DELTA_BETA_CHANNEL,
                    deltaEnabled = true
                ),
                resolveHandsUpdateRoute(
                    deltaEnabled = true,
                    defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    defaultChannel = HANDS_DELTA_MAIN_CHANNEL,
                    deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                    deltaChannel = HANDS_DELTA_MAIN_CHANNEL,
                    overrideChannel = HANDS_OVERRIDE_EARLY_ACCESS,
                    channelSwitchSupported = true,
                    earlyAccessVisible = true,
                    earlyAccessDefaultOn = false,
                    earlyAccessChannel = invalidTarget
                )
            )
        }
        // Legacy 1.31.0 persisted "alpha" override seamlessly migrates to default "beta" when visible.
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_BETA_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_BETA_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_MAIN_CHANNEL,
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_MAIN_CHANNEL,
                overrideChannel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL,
                channelSwitchSupported = true,
                earlyAccessVisible = true,
                earlyAccessDefaultOn = false
            )
        )
        // Server defaultOn=true (with visible=true) routes untouched installs to beta by default.
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_BETA_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_BETA_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_PREVIEW_CHANNEL,
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_PREVIEW_CHANNEL,
                overrideChannel = null,
                channelSwitchSupported = true,
                earlyAccessVisible = true,
                earlyAccessDefaultOn = true
            )
        )
        // Explicit user opt-out (overrideChannel=main) wins over server defaultOn=true even for "canary".
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_MAIN_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_MAIN_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_MAIN_CHANNEL,
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_MAIN_CHANNEL,
                overrideChannel = HANDS_DELTA_MAIN_CHANNEL,
                channelSwitchSupported = true,
                earlyAccessVisible = true,
                earlyAccessDefaultOn = true,
                earlyAccessChannel = "canary"
            )
        )
        // Revoking visibility falls back to main for main/preview builds even when override or defaultOn was set.
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_MAIN_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_MAIN_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_PREVIEW_CHANNEL,
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_PREVIEW_CHANNEL,
                overrideChannel = HANDS_DELTA_BETA_CHANNEL,
                channelSwitchSupported = true,
                earlyAccessVisible = false,
                earlyAccessDefaultOn = true
            )
        )
        // A beta-built release APK enables beta_channel_delta and can opt back out to main.
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_BETA_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_BETA_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_BETA_CHANNEL,
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_BETA_CHANNEL,
                overrideChannel = null,
                channelSwitchSupported = true,
                earlyAccessVisible = false
            )
        )
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_MAIN_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_MAIN_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_BETA_CHANNEL,
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_BETA_CHANNEL,
                overrideChannel = HANDS_DELTA_MAIN_CHANNEL,
                channelSwitchSupported = true,
                earlyAccessVisible = true
            )
        )
        // An engineering alpha-built release APK continues to delta-update on alpha when earlyAccessVisible=false.
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_RELEASE_ALPHA_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL,
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL,
                overrideChannel = null,
                channelSwitchSupported = true,
                earlyAccessVisible = false,
                earlyAccessDefaultOn = false
            )
        )
        // Switching back to main returns to main_delta even if originally built for alpha.
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_MAIN_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_MAIN_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL,
                deltaAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                deltaChannel = HANDS_DELTA_RELEASE_ALPHA_CHANNEL,
                overrideChannel = HANDS_DELTA_MAIN_CHANNEL,
                channelSwitchSupported = true,
                earlyAccessVisible = true
            )
        )
        // Unsupported builds ignore any overrideChannel or server early-access flags.
        assertEquals(
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_ALPHA_DELTA,
                appSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                channel = HANDS_DELTA_ALPHA_CHANNEL,
                deltaEnabled = true
            ),
            resolveHandsUpdateRoute(
                deltaEnabled = true,
                defaultAppSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                defaultChannel = "debug",
                deltaAppSlug = HANDS_DELTA_ALPHA_APP_SLUG,
                deltaChannel = HANDS_DELTA_ALPHA_CHANNEL,
                overrideChannel = HANDS_DELTA_MAIN_CHANNEL,
                channelSwitchSupported = false,
                earlyAccessVisible = true,
                earlyAccessDefaultOn = true
            )
        )
    }

    @Test
    fun deltaDownloadProgressTokenAndPayloadCoverActiveReadyFailedAndMismatchedTargets() {
        val mainRoute =
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_MAIN_DELTA,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_MAIN_CHANNEL,
                deltaEnabled = true
            )
        val fullApkRoute =
            HandsUpdateRoute(
                mode = HANDS_UPDATE_MODE_FULL_APK,
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = "debug",
                deltaEnabled = false
            )

        val token =
            handsDeltaDownloadProgressToken(
                updateRoute = mainRoute,
                state = SharedSettingsUpdateTransactionState.Downloading,
                targetVersionCode = 13_000_002L
            )
        assertEquals("hands_sdk:raft-android:main:13000002", token)
        assertEquals(13_000_002L, handsDeltaDownloadProgressTokenTargetVersionCode(token))
        assertEquals(
            "",
            handsDeltaDownloadProgressToken(
                updateRoute = mainRoute,
                state = SharedSettingsUpdateTransactionState.ReadyToInstall,
                targetVersionCode = 13_000_002L
            )
        )
        assertEquals(
            "",
            handsDeltaDownloadProgressToken(
                updateRoute = mainRoute,
                state = SharedSettingsUpdateTransactionState.Patching,
                targetVersionCode = 13_000_002L
            )
        )
        assertEquals(
            "",
            handsDeltaDownloadProgressToken(
                updateRoute = fullApkRoute,
                state = SharedSettingsUpdateTransactionState.Downloading,
                targetVersionCode = 13_000_002L
            )
        )

        assertEquals(
            mapOf(
                "status" to "ok",
                "token" to token,
                "phase" to "downloading",
                "downloadedBytes" to 12_000_000L,
                "totalBytes" to 45_000_000L
            ),
            handsDeltaProgressPayload(
                token = token,
                status =
                    HandsUpdateStatus(
                        state = HandsUpdateState.DOWNLOADING,
                        targetVersionCode = 13_000_002L,
                        progress = HandsUpdateProgress(downloadedBytes = 12_000_000L, totalBytes = 45_000_000L)
                    )
            )
        )
        assertEquals(
            mapOf(
                "status" to "ok",
                "token" to token,
                "phase" to "paused",
                "downloadedBytes" to 12_000_000L,
                "totalBytes" to -1L
            ),
            handsDeltaProgressPayload(
                token = token,
                status =
                    HandsUpdateStatus(
                        state = HandsUpdateState.DOWNLOADING,
                        targetVersionCode = 13_000_002L,
                        progress = HandsUpdateProgress(downloadedBytes = 12_000_000L, totalBytes = null, paused = true)
                    )
            )
        )
        for (terminalReady in listOf(
            HandsUpdateState.READY_TO_INSTALL,
            HandsUpdateState.INSTALLER_OPENED,
            HandsUpdateState.INSTALLED
        )) {
            assertEquals(
                "ready",
                handsDeltaProgressPayload(
                    token = token,
                    status = HandsUpdateStatus(state = terminalReady, targetVersionCode = 13_000_002L)
                )?.get("phase")
            )
        }
        for (terminalFailed in listOf(HandsUpdateState.FAILED, HandsUpdateState.STALE)) {
            assertEquals(
                mapOf("status" to "ok", "token" to token, "phase" to "failed"),
                handsDeltaProgressPayload(
                    token = token,
                    status = HandsUpdateStatus(state = terminalFailed, targetVersionCode = 13_000_002L)
                )
            )
        }
        assertNull(
            handsDeltaProgressPayload(
                token = token,
                status = HandsUpdateStatus(state = HandsUpdateState.DOWNLOADING, targetVersionCode = 13_000_003L)
            )
        )
        assertNull(
            handsDeltaProgressPayload(
                token = token,
                status = HandsUpdateStatus(state = HandsUpdateState.IDLE, targetVersionCode = 13_000_002L)
            )
        )
        assertNull(
            handsDeltaProgressPayload(
                token = "42",
                status = HandsUpdateStatus(state = HandsUpdateState.DOWNLOADING, targetVersionCode = 13_000_002L)
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
        assertEquals(
            HandsUpdateDisplaySizes(
                patchSizeBytes = 95_256L,
                fullSizeBytes = 94_411_540L
            ),
            updateResponse(
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_BETA_CHANNEL
            ).handsUpdateDisplaySizes(deltaEnabled = true)
        )
        assertEquals(
            HandsUpdateDisplaySizes(
                patchSizeBytes = 95_256L,
                fullSizeBytes = 94_411_540L
            ),
            updateResponse(
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_PREVIEW_CHANNEL
            ).handsUpdateDisplaySizes(deltaEnabled = true)
        )
        assertEquals(
            HandsUpdateDisplaySizes(
                patchSizeBytes = 95_256L,
                fullSizeBytes = 94_411_540L
            ),
            updateResponse(
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_MAIN_CHANNEL
            ).handsUpdateDisplaySizes(deltaEnabled = true)
        )
        assertEquals(
            HandsUpdateDisplaySizes(
                patchSizeBytes = null,
                fullSizeBytes = 94_411_540L
            ),
            updateResponse(
                appSlug = HANDS_DELTA_PRODUCTION_APP_SLUG,
                channel = HANDS_DELTA_MAIN_CHANNEL
            ).handsUpdateDisplaySizes(deltaEnabled = false)
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

    @Test
    fun handsUpdateEventShouldRefreshIncludesPatchStartAndFallback() {
        assertTrue(handsUpdateEventShouldRefresh("state_changed"))
        assertTrue(handsUpdateEventShouldRefresh("patch_started"))
        assertTrue(handsUpdateEventShouldRefresh("patch_fallback"))
        assertFalse(handsUpdateEventShouldRefresh("patch_download_started"))
        assertFalse(handsUpdateEventShouldRefresh("patch_apply_started"))
        assertFalse(handsUpdateEventShouldRefresh("check_started"))
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
        appSlug: String = HANDS_DELTA_ALPHA_APP_SLUG,
        channel: String = HANDS_DELTA_ALPHA_CHANNEL,
        currentVersionCode: Long = 1_000_014L,
        patchFromVersionCode: Long? = currentVersionCode,
        patchAlgorithm: String? = "archive-patcher-v1+gzip",
        patchTargetSha256: String? = "a".repeat(64),
        patchSizeBytes: Long? = 95_256L,
        fullSizeBytes: Long? = 94_411_540L
    ): UpdateCheckResponse =
        UpdateCheckResponse(
            update_available = true,
            app = App(slug = appSlug, platform = "android"),
            channel = channel,
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
