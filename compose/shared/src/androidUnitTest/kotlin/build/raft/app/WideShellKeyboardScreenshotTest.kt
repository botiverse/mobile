package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.RaftHostImeInsets
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #338 (Kirby 11-24): the wide shell with the keyboard up, through the real [RaftAppShellSurface] (top bar, rail, a
 * list pane and a conversation pane, a 24dp status bar). The keyboard is a grey block; the host reports it, as
 * Robolectric dispatches no IME inset.
 * - `tall`: a tablet held sideways (1024 x 768): the top bar steps aside, the head stays.
 * - `medium` (960 x 600): the head doesn't fit with the composer and a few rows, so it steps aside too.
 * - `short`: a phone held sideways (914 x 411): the composer keeps its height on the keyboard (#5630).
 * - `keyboard-down`: everything back. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WideShellKeyboardScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test @Config(sdk = [34], qualifiers = "w914dp-h411dp-mdpi")
    fun shortKeyboardDown() = capture("shell.wide-keyboard.short.keyboard-down", keyboardDp = 0, windowWidth = 914.dp)

    @Test @Config(sdk = [34], qualifiers = "w914dp-h411dp-mdpi")
    fun shortKeyboardUp() = capture("shell.wide-keyboard.short.keyboard-up", keyboardDp = 250, windowWidth = 914.dp)

    @Test @Config(sdk = [34], qualifiers = "w960dp-h600dp-mdpi")
    fun mediumKeyboardUp() = capture("shell.wide-keyboard.medium.keyboard-up", keyboardDp = 300, windowWidth = 960.dp)

    @Test @Config(sdk = [34], qualifiers = "w1024dp-h768dp-mdpi")
    fun tallKeyboardUp() = capture("shell.wide-keyboard.tall.keyboard-up", keyboardDp = 320, windowWidth = 1024.dp)

    private fun capture(name: String, keyboardDp: Int, windowWidth: Dp) =
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/wide-keyboard", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData =
                            PageData(
                                JSONObject().apply {
                                    // Own window id: the inset map is process-wide.
                                    put("slockWindowId", "test:wide-shell-keyboard")
                                    put("statusBarHeight", "24")
                                },
                            )
                    }
                }
            // mdpi: a px is a dp.
            val ime = remember { RaftHostImeInsets().apply { imeBottomPx = keyboardDp } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftAppShellTopBar provides remember { RaftAppShellTopBarState() },
                LocalRaftHostImeInsets provides ime,
            ) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        WideShellWithConversation(windowWidth)
                        if (keyboardDp > 0) {
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(keyboardDp.dp).background(RaftColor.TextMutedGray))
                        }
                    }
                }
            }
        }
}

@Composable
private fun WideShellWithConversation(windowWidth: Dp) {
    val topSafeArea = raftWindowTopSafeAreaPadding()
    val servers = listOf(RaftServerSwitcherItem(id = "s1", name = "Raft", slug = "raft", activityUnreadCount = 0L))
    RaftAppShellSurface(
        shellScope =
            RaftAppShellScope(
                topSafeArea = topSafeArea,
                bottomSafeArea = 0.dp,
                bottomTabBarContentInset = 0.dp,
                bottomTabBarOverlaysContent = false,
            ),
        bottomTabs = emptyList(),
        onBottomTabSelected = {},
        showBottomTabs = false,
        leadingNavigation = {
            RaftNavigationRailTabBar(
                items =
                    listOf(
                        RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.MessageSquare, selected = true),
                        RaftBottomTabItemPayload("activity", "Activity", RaftLucideIconName.Users),
                    ),
                onSelect = {},
                modifier = Modifier.fillMaxHeight(),
            )
        },
        topNavigation = {
            RaftAppShellTopBar(
                currentServer = servers.first(),
                servers = servers,
                serverMenuOpen = false,
                onWorkspaceClick = {},
                onServerPick = {},
                searchLabel = "Search",
                onSearchClick = {},
                windowWidth = windowWidth,
                topSafeArea = topSafeArea,
            )
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            // The list pane (content not under test).
            Box(Modifier.width(240.dp).fillMaxHeight().background(RaftColor.SurfaceWhite))
            Spacer(Modifier.width(1.dp).fillMaxHeight().background(RaftColor.BorderSeam))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // As ThreadRouteScaffold: the head steps aside per raftWideKeyboardHeadHidden.
                if (!raftWideKeyboardHeadHidden()) RaftPanelBackHeader(title = "# all", subtitle = "General chat", onBack = null)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite).padding(16.dp)) {
                            RaftText("qa-test33: 1.29-cda6-smoke-2230", style = RaftTheme.typography.body)
                            RaftText("alex: the top bar steps aside for the keyboard", style = RaftTheme.typography.body)
                            RaftText("kirby: then the head, if it still doesn't fit", style = RaftTheme.typography.body)
                        }
                        ThreadComposerDock(
                            composerModifier =
                                Modifier.raftThreadComposerLiveKeyboardPadding(
                                    bottomSafeArea = 0.dp,
                                    keyboardInsetExcludesBottomSafeArea = true,
                                ),
                        ) {
                            ThreadComposerContent(
                                payload = RaftThreadComposerPayload(instanceId = "wide-keyboard-dock", placeholder = "Message #all"),
                                onEvent = { _, _, _, _ -> },
                                attachmentPreviewEnabled = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
