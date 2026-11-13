package build.raft.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.NotificationPreferenceSnapshot
import build.raft.app.data.RaftNotificationAccountSettingsStore
import build.raft.app.data.RaftNotificationSettingsApi
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.ScriptedAgentControlsNetworkClient
import build.raft.app.data.ServerPushMode
import build.raft.app.data.SettingsMobileActionSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import build.raft.app.data.toSettingsMobileSelectionSnapshot
import kotlin.test.AfterTest
import kotlinx.coroutines.runBlocking
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftNotificationAccountSettings
import build.raft.app.data.RaftNotificationAccountSettingsPatch
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Notifications › Content, "Replies in threads I follow" (botiverse/slock #14677,
 * 9b284c6d, `followedThreadReplies`) on a real composition (AGENTS.md "Compose UI tests"): the row
 * after Sound exists only when the server sent the field, reads its value, and a flip sends only
 * that field.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class SettingsNotificationsFollowedThreadRepliesUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val productionAccountApi = RaftNotificationAccountSettingsStore.apiProvider
    private val productionPreviewCache = RaftNotificationAccountSettingsStore.previewCache

    @AfterTest
    fun restoreStore() {
        RaftNotificationAccountSettingsStore.apiProvider = productionAccountApi
        RaftNotificationAccountSettingsStore.previewCache = productionPreviewCache
        RaftNotificationAccountSettingsStore.clear()
    }

    /** The whole page reading the account store, as production does (no fixture override). */
    private fun ComposeUiTest.showPage() =
        setContent {
            val pager =
                remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    SettingsNotificationsContent(
                        state = ScreenLoadState.Content(mobileSnapshot),
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
                        onRetry = {}
                    )
                }
            }
        }

    /**
     * Tenny, #triage:#357: before the account settings load the row is neither on nor clickable
     * (the web drew it on and a click wrote it back on). Not loaded: no row at all and nothing is
     * sent; once the GET lands with `false`, the row reads off.
     */
    @Test
    fun beforeTheSettingsLoadThereIsNoRowAndAfterALoadedFalseItReadsOff() =
        runRobolectricComposeUiTest {
            val client = ScriptedAgentControlsNetworkClient()
            RaftNotificationAccountSettingsStore.apiProvider = { RaftNotificationSettingsApi(client) }
            RaftNotificationAccountSettingsStore.previewCache = {}
            RaftNotificationAccountSettingsStore.clear()
            showPage()
            onNodeWithTag(SettingsNotificationFollowedThreadRepliesTestTag).assertDoesNotExist()
            assertEquals(emptyList<String>(), client.requests.filter { it.method == "PATCH" }.map { it.path })

            client.respond(
                "GET",
                "me/notification-settings",
                200,
                """{"settings":{"previewMode":"full","sound":true,"mobileDelay":"inactive","followedThreadReplies":false,"version":2},"servers":[]}"""
            )
            runBlocking { RaftNotificationAccountSettingsStore.load() }
            waitForIdle()
            onNodeWithTag(SettingsNotificationFollowedThreadRepliesTestTag).assertExists()
            onNode(isToggleable().and(hasAncestorTag(SettingsNotificationFollowedThreadRepliesTestTag)), useUnmergedTree = true)
                .assertIsOff()
        }

    private fun ComposeUiTest.showContent(settings: RaftNotificationAccountSettings, saved: MutableList<RaftNotificationAccountSettingsPatch>) =
        setContent {
            RaftDesignSystemTheme {
                SettingsNotificationContentSection(
                    settings = settings,
                    previewMode = PushPreviewMode.Full,
                    saving = false,
                    saveFailed = false,
                    onPreviewChange = {},
                    onSave = { saved += it }
                )
            }
        }

    @Test
    fun anOlderServerWithoutTheFieldShowsNoRow() =
        runRobolectricComposeUiTest {
            showContent(RaftNotificationAccountSettings(sound = true, followedThreadReplies = null), mutableListOf())
            onNodeWithTag(SettingsNotificationFollowedThreadRepliesTestTag).assertDoesNotExist()
        }

    @Test
    fun theRowShowsTheServerValueAndAFlipSendsOnlyThatField() =
        runRobolectricComposeUiTest {
            val saved = mutableListOf<RaftNotificationAccountSettingsPatch>()
            showContent(RaftNotificationAccountSettings(sound = true, followedThreadReplies = true, version = 4), saved)
            val switch = onNodeWithTag(SettingsNotificationFollowedThreadRepliesTestTag, useUnmergedTree = true)
            switch.assertExists()
            val toggle = onNode(isToggleable().and(hasAncestorTag(SettingsNotificationFollowedThreadRepliesTestTag)), useUnmergedTree = true)
            toggle.assertIsOn()
            toggle.performClick()
            runOnIdle {
                assertEquals(listOf(RaftNotificationAccountSettingsPatch(followedThreadReplies = false)), saved)
            }
        }

    @Test
    fun anOffValueReadsOff() =
        runRobolectricComposeUiTest {
            showContent(RaftNotificationAccountSettings(followedThreadReplies = false), mutableListOf())
            onNode(isToggleable().and(hasAncestorTag(SettingsNotificationFollowedThreadRepliesTestTag)), useUnmergedTree = true)
                .assertIsOff()
        }
}

private fun hasAncestorTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(tag))

private val mobileSnapshot =
    SettingsMobileSnapshot(
        pushNotifications = SettingsMobileToggleSnapshot(enabled = true, available = true, statusLabel = "On", detail = ""),
        muteServer = SettingsMobileToggleSnapshot(enabled = false, available = true, statusLabel = "Unmuted", detail = ""),
        serverNotificationMode = ServerPushMode.All.toSettingsMobileSelectionSnapshot(available = true),
        pushPreviewMode = PushPreviewMode.Full.toSettingsMobileSelectionSnapshot(available = true),
        sendTestEnabled = true,
        notificationPermission = SettingsMobileActionSnapshot(available = true, statusLabel = "Allowed", detail = ""),
        notificationSettings = SettingsMobileActionSnapshot(available = true, statusLabel = "Open", detail = ""),
        testNotification = SettingsMobileActionSnapshot(available = true, statusLabel = "Ready", detail = "")
    )
