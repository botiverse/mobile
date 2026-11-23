package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.NotificationPreferenceSnapshot
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftBackgroundConnectionControls
import build.raft.app.data.RaftBackgroundConnectionControlsRegistry
import build.raft.app.data.RaftBackgroundKillNotice
import build.raft.app.data.RaftBackgroundKillNoticeHost
import build.raft.app.data.RaftBackgroundKillNoticeHostRegistry
import build.raft.app.data.RaftBackgroundWhitelistGuide
import build.raft.app.data.RaftNotificationLevelStore
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.ServerPushMode
import build.raft.app.data.SettingsMobileActionSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import build.raft.app.data.toSettingsMobileSelectionSnapshot
import build.raft.app.model.RaftTimeContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1400dp-xxhdpi")
class RaftBackgroundKillNoticeUnifiedFlowTest {
    @AfterTest
    fun tearDown() {
        RaftBackgroundKillNoticeHostRegistry.host = null
        RaftBackgroundConnectionControlsRegistry.controls = null
        RaftNotificationLevelStore.clear()
    }

    @Test
    fun bannerOpenSettingsAcknowledgesNoticeAndRoutesToInAppNotifications() = runRobolectricComposeUiTest {
        val noticeState =
            MutableStateFlow<RaftBackgroundKillNotice?>(
                RaftBackgroundKillNotice(
                    exitTimestampMillis = 1_793_322_000_000L,
                    guide = RaftBackgroundWhitelistGuide.HuaweiAppLaunch,
                )
            )
        var settingsOpenedAckCount = 0
        var shownCount = 0
        var navigatedToNotificationsCount = 0
        RaftBackgroundKillNoticeHostRegistry.host =
            object : RaftBackgroundKillNoticeHost {
                override val notice: StateFlow<RaftBackgroundKillNotice?> = noticeState
                override fun markSettingsOpened() {
                    settingsOpenedAckCount += 1
                    noticeState.value = null
                }
                override fun dismiss() = Unit
                override fun dismissForever() = Unit
                override fun markShown() {
                    shownCount += 1
                }
            }

        setContent {
            RaftDesignSystemTheme {
                val bannerSlot =
                    rememberRaftBackgroundKillNoticeBannerSlot(
                        networkStatus = RaftAppShellNetworkStatus.Online,
                        onOpenNotificationSettings = { navigatedToNotificationsCount += 1 },
                    )
                bannerSlot?.invoke()
            }
        }

        onNodeWithTag(RAFT_BACKGROUND_KILL_NOTICE_BANNER_TAG).assertIsDisplayed()
        onNodeWithTag(RAFT_BACKGROUND_KILL_NOTICE_SETTINGS_TAG).assertIsDisplayed().performClick()
        waitForIdle()

        assertEquals(1, shownCount)
        assertEquals(1, settingsOpenedAckCount)
        assertEquals(1, navigatedToNotificationsCount)
        onNodeWithTag(RAFT_BACKGROUND_KILL_NOTICE_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun notificationsPageExposesUnifiedKeepAliveControlsOnNoGmsDirectBuildEvenWhenKeepAliveSwitchIsOff() =
        runRobolectricComposeUiTest {
            val controls =
                RecordingBackgroundControls(
                    offered = true,
                    canRequestBattery = true,
                    guide = RaftBackgroundWhitelistGuide.HuaweiAppLaunch,
                    exempt = false,
                )
            RaftBackgroundConnectionControlsRegistry.controls = controls

            setContent {
                NotificationsTestHost(foregroundKeepAliveEnabled = false)
            }

            onNodeWithTag(SETTINGS_NOTIFICATIONS_BATTERY_OPTIMIZATION_ROW_TAG).assertIsDisplayed().performClick()
            onNodeWithTag(SETTINGS_NOTIFICATIONS_VENDOR_BACKGROUND_ROW_TAG).assertIsDisplayed().performClick()

            assertEquals(1, controls.batteryRequests)
            assertEquals(1, controls.vendorSettingsRequests)
        }

    @Test
    fun notificationsPageExposesUnifiedKeepAliveControlsOnGmsDirectBuild() =
        runRobolectricComposeUiTest {
            val controls =
                RecordingBackgroundControls(
                    offered = false,
                    canRequestBattery = true,
                    guide = RaftBackgroundWhitelistGuide.VendorSettings,
                    exempt = false,
                )
            RaftBackgroundConnectionControlsRegistry.controls = controls

            setContent {
                NotificationsTestHost(foregroundKeepAliveEnabled = false)
            }

            onNodeWithTag(SETTINGS_NOTIFICATIONS_BATTERY_OPTIMIZATION_ROW_TAG).assertIsDisplayed().performClick()
            onNodeWithTag(SETTINGS_NOTIFICATIONS_VENDOR_BACKGROUND_ROW_TAG).assertIsDisplayed().performClick()

            assertEquals(1, controls.batteryRequests)
            assertEquals(1, controls.vendorSettingsRequests)
        }

    @Test
    fun notificationsPageExposesOnlyPlaySafeBackgroundRowOnPlayBuild() =
        runRobolectricComposeUiTest {
            val controls =
                RecordingBackgroundControls(
                    offered = false,
                    canRequestBattery = false,
                    guide = RaftBackgroundWhitelistGuide.LenovoZui,
                    exempt = false,
                )
            RaftBackgroundConnectionControlsRegistry.controls = controls

            setContent {
                NotificationsTestHost(foregroundKeepAliveEnabled = false)
            }

            onNodeWithTag(SETTINGS_NOTIFICATIONS_BATTERY_OPTIMIZATION_ROW_TAG).assertDoesNotExist()
            onNodeWithTag(SETTINGS_NOTIFICATIONS_VENDOR_BACKGROUND_ROW_TAG).assertIsDisplayed().performClick()

            assertEquals(0, controls.batteryRequests)
            assertEquals(1, controls.vendorSettingsRequests)
        }

    @Test
    fun notificationsPageHidesAndroidBackgroundRowsWhenNoHostControlsInstalled() =
        runRobolectricComposeUiTest {
            RaftBackgroundConnectionControlsRegistry.controls = null

            setContent {
                NotificationsTestHost(foregroundKeepAliveEnabled = false)
            }

            onNodeWithTag(SETTINGS_NOTIFICATIONS_BATTERY_OPTIMIZATION_ROW_TAG).assertDoesNotExist()
            onNodeWithTag(SETTINGS_NOTIFICATIONS_VENDOR_BACKGROUND_ROW_TAG).assertDoesNotExist()
        }

    private class RecordingBackgroundControls(
        private val offered: Boolean,
        private val canRequestBattery: Boolean,
        private val guide: RaftBackgroundWhitelistGuide,
        private val exempt: Boolean,
    ) : RaftBackgroundConnectionControls {
        var batteryRequests: Int = 0
        var vendorSettingsRequests: Int = 0

        override fun isOffered(): Boolean = offered
        override fun canRequestBatteryOptimizationExemption(): Boolean = canRequestBattery
        override fun whitelistGuide(): RaftBackgroundWhitelistGuide = guide
        override fun isBatteryOptimizationExempt(): Boolean = exempt
        override val batteryOptimizationExemptState: StateFlow<Boolean> = MutableStateFlow(exempt)
        override fun requestBatteryOptimizationExemption() {
            batteryRequests += 1
        }
        override fun openVendorBackgroundSettings() {
            vendorSettingsRequests += 1
        }
    }

    @androidx.compose.runtime.Composable
    private fun NotificationsTestHost(foregroundKeepAliveEnabled: Boolean) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        val timeContext =
            remember {
                RaftTimeContextProvider(
                    RaftTimeContext(localOffsetMinutes = 0, timeFormat = "24h").refreshedAt(1_793_692_800_000L)
                )
            }
        CompositionLocalProvider(
            LocalActivity provides RaftPageActivity(pager),
            LocalRaftTimeContextProvider provides timeContext,
        ) {
            RaftDesignSystemTheme {
                SettingsNotificationsContent(
                    state = ScreenLoadState.Content(sampleSnapshot),
                    actionState = null,
                    bottomContentPadding = 16.dp,
                    notificationPreferenceSnapshot =
                        NotificationPreferenceSnapshot(
                            enabled = true,
                            foregroundKeepAliveEnabled = foregroundKeepAliveEnabled,
                        ),
                    preferenceBusy = false,
                    foregroundKeepAliveBusy = false,
                    foregroundMessageBannersBusy = false,
                    onNotificationPreferenceChange = {},
                    onForegroundKeepAlivePreferenceChange = {},
                    onForegroundMessageBannersChange = {},
                    onServerNotificationModeChange = {},
                    onPushPreviewModeChange = {},
                    onPermission = {},
                    onSettings = {},
                    onTest = {},
                    onRetry = {},
                )
            }
        }
    }

    private val sampleSnapshot =
        SettingsMobileSnapshot(
            pushNotifications = SettingsMobileToggleSnapshot(enabled = true, available = true, statusLabel = "On", detail = ""),
            muteServer = SettingsMobileToggleSnapshot(enabled = false, available = true, statusLabel = "Unmuted", detail = ""),
            serverNotificationMode = ServerPushMode.All.toSettingsMobileSelectionSnapshot(available = true),
            pushPreviewMode = PushPreviewMode.SenderOnly.toSettingsMobileSelectionSnapshot(available = true),
            sendTestEnabled = true,
            notificationPermission = SettingsMobileActionSnapshot(available = true, statusLabel = "Allowed", detail = ""),
            notificationSettings = SettingsMobileActionSnapshot(available = true, statusLabel = "Open", detail = ""),
            testNotification = SettingsMobileActionSnapshot(available = true, statusLabel = "Ready", detail = ""),
        )
}
