package build.raft.app

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import build.raft.app.data.NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY
import build.raft.app.data.NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftBackgroundConnectionControls
import build.raft.app.data.RaftBackgroundWhitelistGuide
import build.raft.app.data.RaftFcmProbeUiStatus
import build.raft.app.data.RaftNotificationAccountSettings
import build.raft.app.data.RaftNotificationAccountSettingsPatch
import build.raft.app.data.RaftNotificationMobileDelay
import build.raft.app.settings.SettingsNotificationsBackgroundKeepAliveMode
import build.raft.app.settings.settingsMobileDelayPatch
import build.raft.app.settings.settingsNotificationPermissionGranted
import build.raft.app.settings.settingsNotificationsBackgroundControlsPlan
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsNotificationsUiContractTest {
    @Test
    fun permissionGrantedRecognizesPositivePlatformStatuses() {
        assertTrue(settingsNotificationPermissionGranted("Allowed"))
        assertTrue(settingsNotificationPermissionGranted(" granted "))
        assertTrue(settingsNotificationPermissionGranted("Enabled"))
        assertTrue(settingsNotificationPermissionGranted("ON"))
    }

    @Test
    fun previewModeExposesExactlyTheThreeCanonicalWireValues() {
        // The settings control renders one option per enum entry, so the enum *is* the user-facing
        // choice list: it must stay at three and must never resurface the retired "private" value.
        assertEquals(
            listOf("full", "sender_only", "hidden"),
            PushPreviewMode.entries.map { it.wireName }
        )
    }

    @Test
    fun stricterOfIsOrderIndependent() {
        for (a in PushPreviewMode.entries) {
            for (b in PushPreviewMode.entries) {
                val expected = if (a.strictness >= b.strictness) a else b
                assertEquals(expected, PushPreviewMode.stricterOf(a, b), "stricterOf($a, $b)")
                assertEquals(PushPreviewMode.stricterOf(a, b), PushPreviewMode.stricterOf(b, a), "stricterOf($b, $a)")
            }
        }
        assertEquals(PushPreviewMode.Hidden, PushPreviewMode.stricterOf(PushPreviewMode.Full, PushPreviewMode.Hidden))
        assertEquals(PushPreviewMode.SenderOnly, PushPreviewMode.stricterOf(PushPreviewMode.SenderOnly, PushPreviewMode.Full))
    }

    @Test
    fun mobileDelayRowIsHiddenWithoutTheServerFieldAndPicksWriteOnlyChanges() {
        // The row reads mobileDelaySetting: null (no row) when an older server sent no field.
        assertNull(RaftNotificationAccountSettings().mobileDelaySetting)
        assertNull(settingsMobileDelayPatch(RaftNotificationAccountSettings(), RaftNotificationMobileDelay.Always), "never sent to an older server")

        val inactive = RaftNotificationAccountSettings(mobileDelay = "inactive")
        assertEquals(RaftNotificationMobileDelay.Inactive, inactive.mobileDelaySetting)
        assertNull(settingsMobileDelayPatch(inactive, RaftNotificationMobileDelay.Inactive), "the held value is a no-op")
        assertEquals(
            RaftNotificationAccountSettingsPatch(mobileDelay = RaftNotificationMobileDelay.Minutes5),
            settingsMobileDelayPatch(inactive, RaftNotificationMobileDelay.Minutes5)
        )
        // An unknown value shows as Inactive; picking Inactive replaces it.
        val unknown = RaftNotificationAccountSettings(mobileDelay = "30m")
        assertEquals(RaftNotificationMobileDelay.Inactive, unknown.mobileDelaySetting)
        assertEquals(
            RaftNotificationAccountSettingsPatch(mobileDelay = RaftNotificationMobileDelay.Inactive),
            settingsMobileDelayPatch(unknown, RaftNotificationMobileDelay.Inactive)
        )
    }

    @Test
    fun backgroundControlsPlanCoversAllPlatformsAndAndroidBuildVariants() {
        assertNotEquals(
            NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY,
            NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY,
        )

        fun fakeControls(
            offered: Boolean,
            canRequestBattery: Boolean,
            guide: RaftBackgroundWhitelistGuide,
            canOverrideWithForegroundService: Boolean = false,
        ): RaftBackgroundConnectionControls =
            object : RaftBackgroundConnectionControls {
                override fun isOffered(): Boolean = offered
                override fun canOverrideWithForegroundService(): Boolean = canOverrideWithForegroundService
                override fun canRequestBatteryOptimizationExemption(): Boolean = canRequestBattery
                override fun whitelistGuide(): RaftBackgroundWhitelistGuide = guide
                override fun isBatteryOptimizationExempt(): Boolean = false
                override val batteryOptimizationExemptState: StateFlow<Boolean> = MutableStateFlow(false)
                override fun requestBatteryOptimizationExemption() = Unit
                override fun openVendorBackgroundSettings() = Unit
            }

        // Interface defaults keep the direct GMS override disabled when a host does not opt in.
        val minimalControls =
            object : RaftBackgroundConnectionControls {
                override fun isOffered(): Boolean = false
                override fun isBatteryOptimizationExempt(): Boolean = false
                override val batteryOptimizationExemptState: StateFlow<Boolean> = MutableStateFlow(false)
                override fun requestBatteryOptimizationExemption() = Unit
            }
        assertFalse(minimalControls.canOverrideWithForegroundService())
        assertFalse(minimalControls.directGmsKeepAliveOverrideState.value)
        minimalControls.setDirectGmsKeepAliveOverrideEnabled(true)
        assertFalse(minimalControls.directGmsKeepAliveOverrideState.value)
        assertEquals(RaftFcmProbeUiStatus.None, minimalControls.fcmProbeUiStatusState.value)
        minimalControls.dismissPlayFcmUnreachableNotice()
        assertEquals(RaftFcmProbeUiStatus.None, minimalControls.fcmProbeUiStatusState.value)

        // iOS / desktop: no controls and no background realtime support -> hidden.
        val nonAndroid = settingsNotificationsBackgroundControlsPlan(controls = null, platformSupportsBackgroundRealtime = false)
        assertEquals(SettingsNotificationsBackgroundKeepAliveMode.None, nonAndroid.mode)
        assertFalse(nonAndroid.showDirectGmsOverrideRow)
        assertFalse(nonAndroid.showBatteryExemptionRow)
        assertFalse(nonAndroid.showVendorBackgroundRow)

        // Shared fixture without installed Android controls -> switch only.
        val switchOnly = settingsNotificationsBackgroundControlsPlan(controls = null, platformSupportsBackgroundRealtime = true)
        assertEquals(SettingsNotificationsBackgroundKeepAliveMode.RealtimeSwitchOnly, switchOnly.mode)
        assertFalse(switchOnly.showDirectGmsOverrideRow)
        assertFalse(switchOnly.showBatteryExemptionRow)
        assertFalse(switchOnly.showVendorBackgroundRow)

        // No-GMS direct OEM (e.g. Huawei without Play services): fallback switch + battery row + OEM row;
        // never shows the direct GMS override row even if the host claims override support.
        val noGmsOem =
            settingsNotificationsBackgroundControlsPlan(
                controls =
                    fakeControls(
                        offered = true,
                        canRequestBattery = true,
                        guide = RaftBackgroundWhitelistGuide.HuaweiAppLaunch,
                        canOverrideWithForegroundService = true,
                    ),
                platformSupportsBackgroundRealtime = true,
            )
        assertEquals(SettingsNotificationsBackgroundKeepAliveMode.NoPlayServicesFallback, noGmsOem.mode)
        assertFalse(noGmsOem.showDirectGmsOverrideRow)
        assertTrue(noGmsOem.showBatteryExemptionRow)
        assertTrue(noGmsOem.showVendorBackgroundRow)
        assertEquals(RaftBackgroundWhitelistGuide.HuaweiAppLaunch, noGmsOem.whitelistGuide)

        // No-GMS direct Generic AOSP: fallback switch + battery row only (no separate OEM or override row).
        val noGmsGeneric =
            settingsNotificationsBackgroundControlsPlan(
                controls = fakeControls(offered = true, canRequestBattery = true, guide = RaftBackgroundWhitelistGuide.AppDetails),
                platformSupportsBackgroundRealtime = true,
            )
        assertEquals(SettingsNotificationsBackgroundKeepAliveMode.NoPlayServicesFallback, noGmsGeneric.mode)
        assertFalse(noGmsGeneric.showDirectGmsOverrideRow)
        assertTrue(noGmsGeneric.showBatteryExemptionRow)
        assertFalse(noGmsGeneric.showVendorBackgroundRow)

        // GMS direct OEM (sideloaded Hands APK on OEM ROM, e.g. vivo): background realtime switch +
        // direct GMS override switch + battery row + OEM row.
        val gmsDirectOem =
            settingsNotificationsBackgroundControlsPlan(
                controls =
                    fakeControls(
                        offered = false,
                        canRequestBattery = true,
                        guide = RaftBackgroundWhitelistGuide.VendorSettings,
                        canOverrideWithForegroundService = true,
                    ),
                platformSupportsBackgroundRealtime = true,
            )
        assertEquals(SettingsNotificationsBackgroundKeepAliveMode.AndroidWithPlayServices, gmsDirectOem.mode)
        assertTrue(gmsDirectOem.showDirectGmsOverrideRow)
        assertTrue(gmsDirectOem.showBatteryExemptionRow)
        assertTrue(gmsDirectOem.showVendorBackgroundRow)

        // GMS direct Generic/Pixel: background realtime switch + direct GMS override switch + battery row only.
        val gmsDirectGeneric =
            settingsNotificationsBackgroundControlsPlan(
                controls =
                    fakeControls(
                        offered = false,
                        canRequestBattery = true,
                        guide = RaftBackgroundWhitelistGuide.AppDetails,
                        canOverrideWithForegroundService = true,
                    ),
                platformSupportsBackgroundRealtime = true,
            )
        assertEquals(SettingsNotificationsBackgroundKeepAliveMode.AndroidWithPlayServices, gmsDirectGeneric.mode)
        assertTrue(gmsDirectGeneric.showDirectGmsOverrideRow)
        assertTrue(gmsDirectGeneric.showBatteryExemptionRow)
        assertFalse(gmsDirectGeneric.showVendorBackgroundRow)

        // Play build on OEM (e.g. Lenovo ZUI) and Generic: NEVER shows direct GMS override or direct
        // battery prompt row; always exposes the single Play-safe unified vendor/system background row.
        for (guide in listOf(RaftBackgroundWhitelistGuide.LenovoZui, RaftBackgroundWhitelistGuide.AppDetails)) {
            val playPlan =
                settingsNotificationsBackgroundControlsPlan(
                    controls =
                        fakeControls(
                            offered = false,
                            canRequestBattery = false,
                            guide = guide,
                            canOverrideWithForegroundService = false,
                        ),
                    platformSupportsBackgroundRealtime = true,
                )
            assertEquals(SettingsNotificationsBackgroundKeepAliveMode.AndroidWithPlayServices, playPlan.mode)
            assertFalse(playPlan.showDirectGmsOverrideRow)
            assertFalse(playPlan.showBatteryExemptionRow)
            assertTrue(playPlan.showVendorBackgroundRow)
            assertEquals(guide, playPlan.whitelistGuide)
        }
    }
}
