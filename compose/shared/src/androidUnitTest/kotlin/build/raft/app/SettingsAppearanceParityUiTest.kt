package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.APPEARANCE_ACTION_COMPOSER_SEND_KEY
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.RaftComposerSendKey
import build.raft.app.data.RaftComposerSendKeyRuntime
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsAppearanceSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.settings.LocalSettingsAppearancePreviewTimeLabel
import build.raft.app.settings.SettingsAppearanceContent
import build.raft.app.settings.SettingsAppearanceKeyboardSectionTag
import build.raft.app.settings.SettingsAppearanceSectionTag
import build.raft.app.settings.SettingsAppearanceSendKeyErrorTag
import build.raft.app.settings.SettingsAppearanceSendKeyRowTag
import build.raft.app.settings.SettingsKeyboardShortcutsRowTag
import build.raft.app.settings.settingsAppPreferenceVisibility
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings › Appearance web parity (1.29.0):
 * - Single Appearance section with no per-item descriptions and no "Saved on this device." footer.
 * - Keyboard section ("Send messages with": Enter / Ctrl+Enter or ⌘Enter, plus Keyboard shortcuts)
 *   shown on Compose desktop and hardware keyboards, hidden on touch-only mobile.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi")
class SettingsAppearanceParityUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @AfterTest
    fun tearDown() {
        RaftComposerSendKeyRuntime.resetForTest(null)
    }

    @Test
    fun desktopShowsSingleAppearanceGroupWithoutDescriptionsAndKeyboardSendKey() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val selectedKeys = mutableListOf<RaftComposerSendKey>()
            val desktopVisibility =
                settingsAppPreferenceVisibility(
                    finePointer = true,
                    supportsInAppBrowser = false,
                    supportsMultiShellHost = false,
                    supportsIosNativeNav = false,
                    largeScreenPossible = true,
                    hardwareKeyboardConnected = true,
                    isiOSAppOnMac = false,
                )

            setContent {
                CompositionLocalProvider(LocalSettingsAppearancePreviewTimeLabel provides "20:30") {
                    RaftDesignSystemTheme {
                        SettingsAppearanceContent(
                            state = ScreenLoadState.Content(SettingsAppearanceSnapshot(composerSendKey = RaftComposerSendKey.Enter)),
                            actionState = null,
                            fontSizeDraft = "md",
                            bottomContentPadding = 16.dp,
                            onFontSizeDraftChange = {},
                            onSave = {},
                            onRetry = {},
                            showModeCard = false,
                            preferences = sampleMobileSnapshot(),
                            preferenceVisibility = desktopVisibility,
                            themeServerName = "Botiverse",
                            onComposerSendKeyChange = { selectedKeys += it },
                            shortcutsUseCommand = false,
                        )
                    }
                }
            }
            waitForIdle()

            // One Appearance section, Theme on server link without description, no legacy sub-sections or footer.
            onNodeWithText("Theme on Botiverse").assertExists()
            onNodeWithText("Each server can have its own theme.").assertDoesNotExist()
            onNodeWithTag(SettingsAppearanceSectionTag).assertExists()
            onNodeWithText("Appearance").assertExists()
            onNodeWithText("Messages").assertDoesNotExist()
            onNodeWithText("Home").assertDoesNotExist()
            onNodeWithText("Windows").assertDoesNotExist()
            onNodeWithText("Display").assertDoesNotExist()
            onNodeWithText("Saved on this device.").assertDoesNotExist()

            // No per-item descriptions on Appearance rows.
            onNodeWithText("Message font size").assertExists()
            onNodeWithText("Live agent activity").assertExists()
            onNodeWithText("Show agent model").assertExists()
            onNodeWithText("Show member descriptions").assertExists()
            onNodeWithText("Hide empty Home sections").assertExists()
            onNodeWithText(
                "Adjust rendered message text on this device, including headings, code blocks, inline code, and reference chips. Buttons, labels, and sidebar keep their fixed UI sizes.",
            ).assertDoesNotExist()
            onNodeWithText(
                "Show which agents are working, and what they are doing, at the top of the window and on the Home bar.",
            ).assertDoesNotExist()
            onNodeWithText("Show each agent's model next to its name in chat.").assertDoesNotExist()
            // Soft-keyboard Enter to send toggle is replaced by Keyboard › Send messages with.
            onNodeWithText("Enter to send").assertDoesNotExist()

            // Keyboard section with Send messages with (Enter / Ctrl+Enter) + Keyboard shortcuts.
            onNodeWithTag(SettingsAppearanceKeyboardSectionTag).assertExists()
            onNodeWithText("Keyboard").assertExists()
            onNodeWithTag(SettingsAppearanceSendKeyRowTag).assertExists()
            onNodeWithText("Send messages with").assertExists()
            onNodeWithText("Enter").assertExists()
            onNodeWithText("Ctrl+Enter").assertExists()
            onNodeWithTag(SettingsKeyboardShortcutsRowTag).assertExists()

            onNodeWithText("Ctrl+Enter").performClick()
            waitForIdle()
            assertEquals(listOf(RaftComposerSendKey.ModEnter), selectedKeys)
        }

    @Test
    fun appleKeyboardUsesCommandEnterAndShowsSaveFailureLine() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val appleVisibility =
                settingsAppPreferenceVisibility(
                    finePointer = true,
                    supportsInAppBrowser = false,
                    supportsMultiShellHost = false,
                    supportsIosNativeNav = false,
                    largeScreenPossible = true,
                    hardwareKeyboardConnected = true,
                    isiOSAppOnMac = false,
                )

            setContent {
                CompositionLocalProvider(LocalSettingsAppearancePreviewTimeLabel provides "20:30") {
                    RaftDesignSystemTheme {
                        SettingsAppearanceContent(
                            state = ScreenLoadState.Content(SettingsAppearanceSnapshot(composerSendKey = RaftComposerSendKey.ModEnter)),
                            actionState =
                                SharedSettingsActionState(
                                    action = APPEARANCE_ACTION_COMPOSER_SEND_KEY,
                                    errorCode = "save_failed",
                                    screenError = ScreenError("save_failed", retryable = true),
                                ),
                            fontSizeDraft = "md",
                            bottomContentPadding = 16.dp,
                            onFontSizeDraftChange = {},
                            onSave = {},
                            onRetry = {},
                            showModeCard = false,
                            preferences = sampleMobileSnapshot(),
                            preferenceVisibility = appleVisibility,
                            shortcutsUseCommand = true,
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithText("⌘Enter").assertExists()
            onNodeWithTag(SettingsAppearanceSendKeyErrorTag).assertExists()
            onNodeWithText("Couldn’t change how messages send. Try again.").assertExists()
        }

    @Test
    fun touchOnlyMobileHidesKeyboardSectionAndKeepsSoftEnterToSendInAppearance() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val phoneVisibility =
                settingsAppPreferenceVisibility(
                    finePointer = false,
                    supportsInAppBrowser = true,
                    supportsMultiShellHost = false,
                    supportsIosNativeNav = false,
                    largeScreenPossible = false,
                    hardwareKeyboardConnected = false,
                    isiOSAppOnMac = false,
                )

            setContent {
                CompositionLocalProvider(LocalSettingsAppearancePreviewTimeLabel provides "20:30") {
                    RaftDesignSystemTheme {
                        SettingsAppearanceContent(
                            state = ScreenLoadState.Content(SettingsAppearanceSnapshot(composerSendKey = RaftComposerSendKey.Enter)),
                            actionState = null,
                            fontSizeDraft = "md",
                            bottomContentPadding = 16.dp,
                            onFontSizeDraftChange = {},
                            onSave = {},
                            onRetry = {},
                            showModeCard = false,
                            preferences = sampleMobileSnapshot(),
                            preferenceVisibility = phoneVisibility,
                            shortcutsUseCommand = false,
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithTag(SettingsAppearanceSectionTag).assertExists()
            onNodeWithText("Enter to send").assertExists()
            onNodeWithText("Open web links").assertDoesNotExist()
            onNodeWithTag(SettingsAppearanceKeyboardSectionTag).assertDoesNotExist()
            onNodeWithTag(SettingsAppearanceSendKeyRowTag).assertDoesNotExist()
            onNodeWithText("Send messages with").assertDoesNotExist()
        }

    private fun sampleMobileSnapshot(): SettingsMobileSnapshot =
        SettingsMobileSnapshot(
            pushNotifications = SettingsMobileToggleSnapshot(enabled = true, available = true, statusLabel = "On", detail = ""),
            muteServer = SettingsMobileToggleSnapshot(enabled = false, available = true, statusLabel = "Off", detail = ""),
        )
}
