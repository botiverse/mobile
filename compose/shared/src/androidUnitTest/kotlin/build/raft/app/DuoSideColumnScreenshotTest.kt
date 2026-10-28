package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.designsystem.components.RaftSideColumnKeepOut
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * iPhone Duo's side column (raft-design #859, page-structure "Foldable phones"), with a fake host
 * posture, light:
 *  - outer screen, Home (466x678): the server switcher is the first header line, on paper, no
 *    yellow row; the bell (with its attention pixel) is the column's one page action, standing
 *    over the native tab bar's 260pt (a grey stand-in: iOS draws it); a camera band (the darker
 *    stand-in) at the column's top.
 *  - outer screen, a channel: Back first in the column, Search and Settings under it; the white
 *    56dp header keeps the title and the subtitle, from the 16dp margin, without Back and icon
 *    buttons; the messages and the composer end at the column's inner edge. No tab bar.
 *  - inner screen held sideways (951x669), the apps' desktop mode without the top bar
 *    (page-structure "The inner screen"): the rail's header tile is the server switcher (with the
 *    other-server attention light), Search is its first destination, the bell (with its attention
 *    pixel) is in its footer above Settings; then the Home list with no server switcher of its own.
 *    Home alone; Home with the bell's popover open next to the rail; a channel in the main area,
 *    where the column holds the history Back and the channel's actions, never destinations.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DuoSideColumnScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(sdk = [34], qualifiers = "w466dp-h678dp-xhdpi")
    fun outerScreenHome() =
        capture("duo.outer.home", outerPosture, tabBarStandIn = OuterTabBarHeight) { HomeList(serverSwitcherInTopBar = false) }

    @Test
    @Config(sdk = [34], qualifiers = "w466dp-h678dp-xhdpi")
    fun outerScreenChannel() = capture("duo.outer.channel", outerPosture.copy(nativeTabBarHeight = 0.dp), tabBarStandIn = 0.dp) { ChannelPage() }

    @Test
    @Config(sdk = [34], qualifiers = "w951dp-h669dp-mdpi")
    fun innerScreenLandscapeHome() = capture("duo.inner-landscape.home", innerPosture, tabBarStandIn = 0.dp) { WideShell() }

    @Test
    @Config(sdk = [34], qualifiers = "w951dp-h669dp-mdpi")
    fun innerScreenLandscapeHomeBellOpen() =
        capture("duo.inner-landscape.home-bell", innerPosture, tabBarStandIn = 0.dp) { WideShell(bellOpen = true) }

    @Test
    @Config(sdk = [34], qualifiers = "w951dp-h669dp-mdpi")
    fun innerScreenLandscapeChannel() =
        capture("duo.inner-landscape.channel", innerPosture, tabBarStandIn = 0.dp) { WideShell(channelOpen = true) }

    private fun capture(
        name: String,
        posture: RaftSideColumnPosture,
        tabBarStandIn: Dp,
        content: @Composable () -> Unit,
    ) = RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                    RaftSideColumnScaffold(posture = posture, content = content)
                    // Stand-ins for what the host draws in the column: the camera, the tab bar.
                    posture.keepOut.forEach { band ->
                        Box(
                            Modifier
                                .align(AbsoluteAlignment.TopRight)
                                .padding(top = band.top)
                                .size(posture.stripWidth, band.bottom - band.top)
                                .background(RaftColor.FillPressed),
                        )
                    }
                    if (tabBarStandIn > 0.dp) {
                        Box(
                            Modifier
                                .align(AbsoluteAlignment.BottomRight)
                                .size(posture.stripWidth, tabBarStandIn)
                                .background(RaftColor.FillMuted),
                        )
                    }
                }
            }
        }
    }
}

private val OuterTabBarHeight = 260.dp
private val StripWidth = 56.dp

private val outerPosture =
    RaftSideColumnPosture(
        sideColumn = true,
        stripWidth = StripWidth,
        nativeTabBarHeight = OuterTabBarHeight,
        keepOut = listOf(RaftSideColumnKeepOut(top = 0.dp, bottom = 48.dp)),
    )

private val innerPosture =
    RaftSideColumnPosture(
        sideColumn = true,
        stripWidth = StripWidth,
        keepOut = listOf(RaftSideColumnKeepOut(top = 0.dp, bottom = 40.dp)),
    )

private val duoServers =
    listOf(
        RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L),
        RaftServerSwitcherItem(id = "s2", name = "Acme Labs", slug = "acme", activityUnreadCount = 3L),
    )

@Composable
private fun HomeList(serverSwitcherInTopBar: Boolean) {
    RaftStructuralHomeShell(
        currentServerName = duoServers.first().name,
        currentServerId = duoServers.first().id,
        servers = duoServers,
        channels =
            listOf(
                RaftHomeConversationItem(id = "c-general", name = "general", kind = "channel"),
                RaftHomeConversationItem(id = "c-proj-mobile", name = "proj-mobile", kind = "channel", unreadCount = 3),
                RaftHomeConversationItem(id = "c-wg-mobile", name = "wg-mobile", kind = "channel"),
                RaftHomeConversationItem(id = "c-triage", name = "triage", kind = "channel"),
            ),
        directMessages =
            listOf(
                RaftHomeConversationItem(id = "dm-ada", name = "Ada", kind = "dm", avatarLabel = "AD"),
                RaftHomeConversationItem(id = "dm-lagoon", name = "Lagoon", kind = "dm", avatarLabel = "LA"),
            ),
        notifications = listOf(RaftHomeNotificationItem(id = "n-offline", title = "Computer offline")),
        serverSwitcherInTopBar = serverSwitcherInTopBar,
        applyTopSafeArea = false,
        topSafeAreaOverride = 0.dp,
        showBottomBar = false,
        searchAndActivityOnShellNav = true,
        bottomContentPadding = 0.dp,
        outerPadding = 0.dp,
        contentRefreshEnabled = false,
    )
}

private val channelPayload =
    RaftThreadScreenPayload(
        instanceId = "duo-channel",
        sourceThreadId = "proj-mobile",
        title = "proj-mobile",
        subtitle = "Mobile app: releases, QA and the iPhone Duo work",
        status = "content",
        surfaceKind = RaftThreadSurfaceKind.Channel,
        selectedTab = "chat",
        showModeTabs = true,
        showSettingsAction = true,
        showMembersAction = true,
        membersCount = 4,
        showOpenInChannelAction = false,
    )

private fun duoMessage(id: String, seq: Long, author: String, body: String) =
    RaftThreadMessageItem(
        id = id,
        authorId = "agent-${author.lowercase()}",
        authorName = author,
        authorKind = "agent",
        isOwn = false,
        avatarLabel = author.take(1),
        seq = seq,
        timestampLabel = "2026-10-28T09:${10 + seq}:00Z",
        body = body,
    )

@Composable
private fun ChannelPage(showBack: Boolean = true) {
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        ThreadPageHeader(
            payload = channelPayload,
            headerDisplay =
                ThreadHeaderDisplayModel(
                    title = "proj-mobile",
                    subtitle = "Mobile app: releases, QA and the iPhone Duo work",
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = "channel",
                ),
            applyTopSafeArea = false,
            showBack = showBack,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
        Column(Modifier.weight(1f).fillMaxWidth().padding(top = RaftAppSpacing.Sm)) {
            listOf(
                duoMessage("m-1", 1, "Maya", "The outer screen build is up: the bell moved into the side column."),
                duoMessage("m-2", 2, "Forge", "Composer ends at the column's inner edge, so Send never sits under it."),
                duoMessage("m-3", 3, "Nova", "Camera band is clear on 27.1; 27.0 reports no reserved regions."),
            ).forEach { message ->
                ThreadMessageCard(
                    message = message,
                    messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                    isMarkdownTagValid = { tag -> tag.kind == ThreadMarkdownTagKind.UserMention },
                    timestampLabel = "9:${message.timestampLabel.substring(14, 16)} AM",
                    onShowActions = {},
                    onOpenMember = {},
                    onOpenThread = {},
                    onOpenTask = {},
                    onOpenTaskStatusMenu = {},
                    onToggleReaction = {},
                    onOpenAttachment = {},
                    onOpenMarkdownTag = { _, _ -> },
                    onOpenMarkdownUrl = {},
                )
            }
        }
        ThreadComposerDock {
            ThreadComposerContent(
                payload = RaftThreadComposerPayload(instanceId = "duo-composer", placeholder = "Message #proj-mobile"),
                onEvent = { _, _, _, _ -> },
                attachmentPreviewEnabled = false,
            )
        }
    }
}

@Composable
private fun WideShell(channelOpen: Boolean = false, bellOpen: Boolean = false) {
    val topBar = remember { RaftAppShellTopBarState() }
    // Home closes the shell's bell when it first shows: open it a frame later, as a tap would.
    if (bellOpen) {
        LaunchedEffect(Unit) {
            withFrameNanos {}
            topBar.bellOpen.value = true
        }
    }
    // The shell hands the column its history Back (the detail pane has none of its own).
    if (channelOpen) raftSideColumnFallbackBack(raftSideColumnBackAction(onBack = {}))
    val railWithoutTopBar = raftAppRailWithoutTopBar(navRailActive = true)
    CompositionLocalProvider(LocalRaftAppShellTopBar provides topBar) {
        Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            Row(Modifier.fillMaxSize()) {
                RaftNavigationRailTabBar(
                    items =
                        appShellRailItemsWithSearch(
                            items =
                                listOf(
                                    RaftBottomTabItemPayload(key = "home", label = "Home", iconName = RaftLucideIconName.House, selected = true),
                                    RaftBottomTabItemPayload(key = "activity", label = "Activity", iconName = RaftLucideIconName.Activity, badgeCount = 3),
                                    RaftBottomTabItemPayload(key = "tasks", label = "Tasks", iconName = RaftLucideIconName.SquareCheckBig),
                                    RaftBottomTabItemPayload(key = "members", label = "Members", iconName = RaftLucideIconName.Users),
                                ),
                            searchLabel = "Search",
                            searchOpen = false,
                        ),
                    onSelect = {},
                    modifier = Modifier.fillMaxHeight(),
                    bottomItems = listOf(RaftBottomTabItemPayload(key = "settings", label = "Settings", iconName = RaftLucideIconName.Settings)),
                    header =
                        if (railWithoutTopBar) {
                            { RaftAppRailServerTile(duoServerMenu, onClick = {}) }
                        } else {
                            null
                        },
                    footerTools = if (railWithoutTopBar) ({ RaftAppRailNotificationBell(topBar, onToggle = {}) }) else null,
                )
                Box(Modifier.width(280.dp).fillMaxHeight()) { HomeList(serverSwitcherInTopBar = true) }
                Box(Modifier.weight(1f).fillMaxHeight()) { if (channelOpen) ChannelPage(showBack = false) }
            }
            RaftAppTopBarNotificationOverlay(topBar, topInset = 0.dp, besideRail = true)
        }
    }
}

private val duoServerMenu =
    RaftServerMenuModel(servers = duoServers, currentServer = duoServers.first(), otherServersUnread = true)
