package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsAppearanceSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin 2026-10-29: the desktop app (a fine pointer) has no "Mobile" Settings section. Its cards
 * sit at the end of Appearance, where the web keeps its device preferences (packages/web
 * SettingsPanel AppearanceSection), with pointer-neutral copy; "Open web links" (no embedded web
 * view on desktop), the forward swipe and multiple windows are not shown there. Wide list with
 * Appearance picked, as the desktop app shows it. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h1500dp-mdpi")
class SettingsAppPreferencesDesktopScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun appearanceWithAppPreferencesLight() =
        capture("settings.wide.appearance.desktop", RaftAppAppearance.Light) {
            SettingsWideListDetail(
                payload = appShellSettingsHomePayload(session = null, appPreferencesUnderAppearance = true),
                widths = remember { RaftListPaneWidths(store = null) },
                active = true,
                picked = SettingsNativeDetailPage.Appearance.key,
                onPick = {},
                onBackToDefault = {},
                detailBottomInset = 0.dp,
                bottomContentPadding = 0.dp,
                detailPane = { _, modifier ->
                    Box(modifier) {
                        SettingsDetailShell(title = "Appearance", subtitle = "", iconName = RaftLucideIconName.Type, onBack = {}) {
                            SettingsAppearanceContent(
                                state = ScreenLoadState.Content(SettingsAppearanceSnapshot()),
                                actionState = null,
                                fontSizeDraft = "md",
                                activitySenderAvatarsEnabled = true,
                                bottomContentPadding = 0.dp,
                                onFontSizeDraftChange = {},
                                onActivitySenderAvatarsChange = {},
                                onSave = {},
                                onRetry = {},
                                showModeCard = false,
                                appPreferenceItems = {
                                    settingsAppPreferenceItems(
                                        state = ScreenLoadState.Content(desktopAppPreferencesFixture),
                                        actionState = null,
                                        actions = SettingsAppPreferenceActions(),
                                        visibility =
                                            settingsAppPreferenceVisibility(
                                                finePointer = true,
                                                supportsInAppBrowser = false,
                                                supportsMultiShellHost = false,
                                            ),
                                    )
                                },
                            )
                        }
                    }
                },
            )
        }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftAppShellTopBar provides remember { RaftAppShellTopBarState() },
            ) {
                val servers = listOf(RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L))
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                        RaftAppShellTopBar(
                            currentServer = servers.first(),
                            servers = servers,
                            serverMenuOpen = false,
                            onWorkspaceClick = {},
                            onServerPick = {},
                            searchLabel = "Search",
                            onSearchClick = {},
                            windowWidth = 1280.dp,
                        )
                        Row(Modifier.weight(1f)) {
                            RaftNavigationRailTabBar(
                                items =
                                    listOf(
                                        RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.House),
                                        RaftBottomTabItemPayload("activity", "Activity", RaftLucideIconName.Activity),
                                        RaftBottomTabItemPayload("tasks", "Tasks", RaftLucideIconName.SquareCheckBig),
                                        RaftBottomTabItemPayload("members", "Members", RaftLucideIconName.Users),
                                    ),
                                onSelect = {},
                                modifier = Modifier.fillMaxHeight(),
                                bottomItems =
                                    listOf(RaftBottomTabItemPayload("settings", "Settings", RaftLucideIconName.Settings, selected = true)),
                            )
                            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                        }
                    }
                }
            }
        }
}

private val desktopAppPreferencesFixture =
    SettingsMobileSnapshot(
        pushNotifications = SettingsMobileToggleSnapshot(enabled = false, available = false, statusLabel = "", detail = ""),
        muteServer = SettingsMobileToggleSnapshot(enabled = false, available = false, statusLabel = "", detail = ""),
    ).let {
        // Desktop: no right-edge forward recognizer (raftPlatformSupportsConversationForwardGesture).
        it.copy(conversationForwardGesture = it.conversationForwardGesture.copy(available = false))
    }
