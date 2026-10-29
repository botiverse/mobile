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
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin 2026-10-29: the wide Settings as list + detail (SettingsWideListDetail) beside the rail
 * under the wide top bar: the Settings list in the sidebar column with the shown section
 * highlighted, the section's page beside it with no Back. Nothing picked shows Account (the web
 * default); Appearance picked. The detail is a fixture Account / Appearance page (the real ones
 * are child pages). Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class SettingsWideSplitScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun defaultAccountLight() = capture("settings.wide.account", RaftAppAppearance.Light) { WideSettings(picked = null) }

    @Test
    fun defaultAccountDark() = capture("settings.wide.account.dark", RaftAppAppearance.Dark) { WideSettings(picked = null) }

    @Test
    fun appearancePickedLight() =
        capture("settings.wide.appearance", RaftAppAppearance.Light) { WideSettings(picked = SettingsNativeDetailPage.Appearance.key) }

    @Composable
    private fun WideSettings(picked: String?) {
        SettingsWideListDetail(
            payload = appShellSettingsHomePayload(session = null),
            widths = remember { RaftListPaneWidths(store = null) },
            active = true,
            picked = picked,
            onPick = {},
            onBackToDefault = {},
            detailBottomInset = 0.dp,
            bottomContentPadding = 0.dp,
            detailPane = { key, modifier ->
                Box(modifier) {
                    if (key == SettingsDestinationAccount) {
                        SettingsDetailShell(title = "Account", subtitle = "", onBack = {}) {
                            SettingsAccountContent(payload = wideAccountFixture, applyTopSafeArea = false, onEvent = { _, _ -> })
                        }
                    } else {
                        SettingsDetailShell(title = "Appearance", subtitle = "", iconName = RaftLucideIconName.Type, onBack = {}) {
                            SettingsAppearanceContent(
                                state = build.raft.app.data.ScreenLoadState.Content(build.raft.app.data.SettingsAppearanceSnapshot()),
                                actionState = null,
                                fontSizeDraft = "md",
                                activitySenderAvatarsEnabled = true,
                                bottomContentPadding = 0.dp,
                                onFontSizeDraftChange = {},
                                onActivitySenderAvatarsChange = {},
                                onSave = {},
                                onRetry = {},
                                showModeCard = true,
                            )
                        }
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

private val wideAccountFixture =
    RaftSettingsAccountPayload(
        instanceId = "settings-wide-screenshot",
        profile =
            RaftSettingsAccountProfile(
                displayName = "Artin",
                handle = "artin",
                email = "artin@raft.build",
                avatarLabel = "AR",
                verified = true,
            ),
        connectedAccounts =
            listOf(
                RaftSettingsConnectedAccount("password", "Email password", "artin@raft.build", "connected", canConnect = false),
                RaftSettingsConnectedAccount("github", "GitHub", "artin@raft.build", "connected", canConnect = false),
                RaftSettingsConnectedAccount("google", "Google", "Not connected", "notConnected", canConnect = true),
            ),
    )
