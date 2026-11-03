package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.NotificationPreferenceSnapshot
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftNotificationAccountSettings
import build.raft.app.data.RaftNotificationLevel
import build.raft.app.data.RaftNotificationLevelStore
import build.raft.app.data.RaftNotificationQuietHours
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.ServerPushMode
import build.raft.app.data.SettingsMobileActionSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import build.raft.app.data.toSettingsMobileSelectionSnapshot
import build.raft.app.model.NotificationLevelChanged
import build.raft.app.model.ServerId
import kotlin.test.AfterTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Notifications in the web's order and copy (botiverse/slock
 * packages/web/src/components/settings/notifications): This device, Pause and schedule (quiet
 * hours on, saved for another zone), Content (preview, the phone delay set to Always with its
 * note, sound) and Servers (the current server first, one channel customized). Phone light and
 * dark; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h2000dp-xxhdpi")
class SettingsNotificationsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @AfterTest
    fun clearLevels() = RaftNotificationLevelStore.clear()

    @Test
    fun notificationsPageLight() =
        captureNotifications("settings.notifications", RaftAppAppearance.Light, activities) { NotificationsFixture() }

    @Test
    fun notificationsPageDark() =
        captureNotifications("settings.notifications.dark", RaftAppAppearance.Dark, activities) { NotificationsFixture() }
}

/**
 * The same page in a desktop window's detail pane (720dp beside the settings list), paused: the
 * pause row shows until when and Resume; server names and their level selects share a line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1024dp-h1500dp-mdpi")
class SettingsNotificationsWideScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @AfterTest
    fun clearLevels() = RaftNotificationLevelStore.clear()

    @Test
    fun notificationsPageWidePaused() =
        captureNotifications("settings.notifications.wide", RaftAppAppearance.Light, activities) {
            Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                Box(Modifier.width(720.dp).fillMaxHeight()) {
                    NotificationsFixture(pausedUntil = "2026-11-03T10:30:00Z")
                }
            }
        }
}

private fun captureNotifications(
    name: String,
    appearance: RaftAppAppearance,
    activities: RobolectricComposeActivityRule,
    content: @Composable () -> Unit
) = RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
    val pager =
        remember {
            object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
        }
    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
        RaftDesignSystemTheme { content() }
    }
}

/** 2026-11-03T08:00:00Z. */
private const val FIXTURE_NOW_MILLIS = 1_793_692_800_000L

@Composable
private fun NotificationsFixture(pausedUntil: String? = null) {
    remember {
        RaftNotificationLevelStore.clear()
        RaftNotificationLevelStore.applyServerLevels(mapOf("s1" to RaftNotificationLevel.All, "s2" to RaftNotificationLevel.None))
        RaftNotificationLevelStore.applyRealtime(
            NotificationLevelChanged(serverId = ServerId("s1"), scope = "channel", scopeId = "c1", level = "mentions", prefsVersion = 3)
        )
    }
    SettingsNotificationsContent(
        state = ScreenLoadState.Content(notificationsMobileFixture),
        actionState = null,
        bottomContentPadding = 16.dp,
        notificationPreferenceSnapshot = NotificationPreferenceSnapshot(enabled = true),
        preferenceBusy = false,
        foregroundKeepAliveBusy = false,
        offlineComputerAlertsBusy = false,
        foregroundMessageBannersBusy = false,
        onNotificationPreferenceChange = {},
        onForegroundKeepAlivePreferenceChange = {},
        onOfflineComputerAlertsChange = {},
        onForegroundMessageBannersChange = {},
        onServerNotificationModeChange = {},
        onPushPreviewModeChange = {},
        onPermission = {},
        onSettings = {},
        onTest = {},
        onRetry = {},
        servers =
            listOf(
                SettingsNotificationServer(id = "s1", name = "Botiverse", current = true),
                SettingsNotificationServer(id = "s2", name = "Raft HQ", current = false)
            ),
        nowEpochMillis = FIXTURE_NOW_MILLIS,
        timeZoneId = "Asia/Shanghai",
        accountSettingsOverride =
            RaftNotificationAccountSettings(
                pausedUntil = pausedUntil,
                quietHours = RaftNotificationQuietHours(enabled = true, startMinute = 22 * 60, endMinute = 8 * 60, timeZone = "Europe/Berlin"),
                previewMode = PushPreviewMode.SenderOnly.wireName,
                sound = true,
                mobileDelay = "always",
                version = 3
            )
    )
}

private val notificationsMobileFixture =
    SettingsMobileSnapshot(
        pushNotifications = SettingsMobileToggleSnapshot(enabled = true, available = true, statusLabel = "On", detail = ""),
        muteServer = SettingsMobileToggleSnapshot(enabled = false, available = true, statusLabel = "Unmuted", detail = ""),
        serverNotificationMode = ServerPushMode.All.toSettingsMobileSelectionSnapshot(available = true),
        pushPreviewMode = PushPreviewMode.SenderOnly.toSettingsMobileSelectionSnapshot(available = true),
        sendTestEnabled = true,
        notificationPermission = SettingsMobileActionSnapshot(available = true, statusLabel = "Allowed", detail = ""),
        notificationSettings = SettingsMobileActionSnapshot(available = true, statusLabel = "Open", detail = ""),
        testNotification = SettingsMobileActionSnapshot(available = true, statusLabel = "Ready", detail = "")
    )
