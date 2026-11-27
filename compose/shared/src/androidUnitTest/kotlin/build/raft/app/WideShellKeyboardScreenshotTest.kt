package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import build.raft.app.data.RaftListPaneKind
import build.raft.app.thread.ThreadComposerDock
import build.raft.app.thread.raftThreadComposerLiveKeyboardPadding
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
 * - `short`: a phone held sideways (914 x 411): the composer keeps its height on the keyboard (#5630) and, typed in,
 *   runs the window's full width over the rail and the list column (Kirby 640ad7a2, "通栏全宽").
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
                        // The composer is what is typed in (as its focus tells the shell; a caret would blink here).
                        WideShellWithConversation(windowWidth, composerClaimsFocus = true)
                        if (keyboardDp > 0) {
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(keyboardDp.dp).background(RaftColor.TextMutedGray))
                        }
                    }
                }
            }
        }
}

internal const val WIDE_SHELL_KEYBOARD_RAIL = "wide-keyboard-rail"
internal const val WIDE_SHELL_KEYBOARD_LIST = "wide-keyboard-list"
internal const val WIDE_SHELL_KEYBOARD_COMPOSER = "wide-keyboard-composer"

/**
 * The wide shell with the real list split ([RaftAdaptiveListDetailSplit]) and a conversation pane as
 * [ThreadRouteScaffold] lays it out. [composerClaimsFocus]: tells the shell the composer is typed in without focusing
 * it (no caret in a golden); else the composer tells it itself as its focus changes ([raftWideKeyboardComposerFocus]).
 */
@Composable
internal fun WideShellWithConversation(windowWidth: Dp, composerClaimsFocus: Boolean = false) {
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
                modifier = Modifier.fillMaxHeight().testTag(WIDE_SHELL_KEYBOARD_RAIL),
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
        RaftAdaptiveListDetailSplit(
            kind = RaftListPaneKind.Sidebar,
            widths = remember { RaftListPaneWidths(null) },
            dualPane = true,
            active = true,
            hasSelection = true,
            detailBottomInset = 0.dp,
            listPane = {
                Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(16.dp).testTag(WIDE_SHELL_KEYBOARD_LIST)) {
                    RaftText("# all", style = RaftTheme.typography.body)
                    RaftText("# design", style = RaftTheme.typography.body)
                }
            },
            detailPane = { modifier ->
                Column(modifier) {
                    // As ThreadRouteScaffold: the head steps aside per raftWideKeyboardHeadHidden.
                    if (!raftWideKeyboardHeadHidden()) RaftPanelBackHeader(title = "# all", subtitle = "General chat", onBack = null)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize()) {
                            Column(Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite).padding(16.dp)) {
                                RaftText("qa-test33: 1.29-cda6-smoke-2230", style = RaftTheme.typography.body)
                                RaftText("alex: the top bar steps aside for the keyboard", style = RaftTheme.typography.body)
                                RaftText("kirby: then the head, if it still doesn't fit", style = RaftTheme.typography.body)
                            }
                            if (composerClaimsFocus) {
                                val note = LocalRaftWideKeyboardComposerFocus.current
                                val who = remember { Any() }
                                SideEffect { note?.report(who, true) }
                            }
                            ThreadComposerDock(
                                composerModifier =
                                    Modifier
                                        .raftThreadComposerLiveKeyboardPadding(
                                            bottomSafeArea = 0.dp,
                                            keyboardInsetExcludesBottomSafeArea = true,
                                        )
                                        // As ThreadRouteScaffold.
                                        .raftWideKeyboardComposerFocus()
                                        .testTag(WIDE_SHELL_KEYBOARD_COMPOSER),
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
            },
        )
    }
}
