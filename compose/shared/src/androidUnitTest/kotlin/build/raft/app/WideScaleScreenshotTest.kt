package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import build.raft.app.data.RaftListPaneKind
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wide Home (top bar, rail, the real sidebar list) with a channel conversation open (its header,
 * message rows and the composer) in a 1024x768 tablet window. The type follows the window width
 * alone, so both captures take the wide type; the controls follow the input (artin 2026-11-02):
 *  - `scale.wide.touch`: nothing pinned, as a tablet resolves it with a finger: wide type, 44dp controls;
 *  - `scale.wide.fine`: a mouse or trackpad in use (the Mac, or a tablet with one): wide type, 34dp controls, 44dp heads and top bar, the compact rail (sizing v12).
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1024dp-h768dp-mdpi")
class WideScaleScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun touch() = capture("scale.wide.touch", pointerInput = null)

    @Test
    fun finePointer() = capture("scale.wide.fine", RaftPointerInput.Fine)

    private fun capture(name: String, pointerInput: RaftPointerInput?) =
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                if (pointerInput == null) {
                    RaftDesignSystemTheme { WideHomeWithConversation() }
                } else {
                    RaftTheme(appearance = RaftAppearance.Light, pointerInput = pointerInput) {
                        RaftDesignSystemTheme { WideHomeWithConversation() }
                    }
                }
            }
        }
}

@Composable
internal fun WideHomeWithConversation() {
    val fixture = VisualFixtureData.HomeChannelList
    val servers = listOf(RaftServerSwitcherItem(id = VisualFixtureData.Server.ID, name = fixture.SERVER_NAME, slug = VisualFixtureData.Server.SLUG))
    val channels = remember { fixture.CHANNELS.map(::homeChannelListChannel) }
    val directMessages = remember { fixture.DIRECT_MESSAGES.map(::homeChannelListDirectMessage) }
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftAppShellTopBar(
            currentServer = servers.first(),
            servers = servers,
            serverMenuOpen = false,
            onWorkspaceClick = {},
            onServerPick = {},
            searchLabel = "Search",
            onSearchClick = {},
            windowWidth = 1024.dp,
        )
        Row(Modifier.weight(1f)) {
            RaftNavigationRailTabBar(
                items =
                    listOf(
                        RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.House, selected = true),
                        RaftBottomTabItemPayload("activity", "Activity", RaftLucideIconName.Activity),
                        RaftBottomTabItemPayload("tasks", "Tasks", RaftLucideIconName.SquareCheckBig),
                        RaftBottomTabItemPayload("members", "Members", RaftLucideIconName.Users),
                    ),
                onSelect = {},
                modifier = Modifier.fillMaxHeight(),
                bottomItems = listOf(RaftBottomTabItemPayload("settings", "Settings", RaftLucideIconName.Settings)),
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                RaftAdaptiveListDetailSplit(
                    kind = RaftListPaneKind.Sidebar,
                    widths = remember { RaftListPaneWidths(store = null) },
                    dualPane = true,
                    active = true,
                    hasSelection = true,
                    detailBottomInset = 0.dp,
                    listPane = {
                        RaftStructuralHomeShell(
                            currentServerName = fixture.SERVER_NAME,
                            currentServerId = VisualFixtureData.Server.ID,
                            servers = servers,
                            channels = channels,
                            directMessages = directMessages,
                            selectedChannelId = channels.firstOrNull()?.id,
                            pinnedChannelIds = fixture.PINNED_CHANNEL_IDS,
                            savedCount = fixture.SAVED_TOTAL,
                            contentRefreshEnabled = false,
                            selectedTabKey = "home",
                            showBottomBar = false,
                            serverSwitcherInTopBar = true,
                            searchAndActivityOnShellNav = true,
                            outerPadding = 0.dp,
                            applyTopSafeArea = false,
                        )
                    },
                    detailPane = { modifier -> WideConversation(modifier, channels.firstOrNull()?.name ?: "general") },
                )
            }
        }
    }
}

@Composable
private fun WideConversation(modifier: Modifier, channelName: String) {
    val payload =
        remember(channelName) {
            RaftThreadScreenPayload(
                instanceId = "scale-wide-conversation",
                sourceThreadId = "channel-scale",
                title = "#$channelName",
                subtitle = "Release train for the tablet layout",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                selectedTab = "chat",
                showSettingsAction = true,
                showMembersAction = true,
                membersCount = 6,
                showOpenInChannelAction = false,
            )
        }
    val messages =
        listOf(
            scaleRow("m-1", 1, "Ada", "Morning. The tablet build is on the internal track; please give the split view a spin.", own = false),
            scaleRow("m-2", 2, "Scout", "Checked on the Pixel Tablet: the sidebar and the conversation sit side by side, rows read well.", own = false, kind = "agent"),
            scaleRow("m-3", 3, "Me", "Thanks. Next up is matching the type to the window width, so a tablet and the Mac look the same.", own = true),
            scaleRow("m-4", 4, "Ada", "Works for me. Touch keeps the 44dp targets; the mouse gets the tighter controls.", own = false),
        )
    Column(modifier.background(RaftColor.SurfaceWhite)) {
        ThreadPageHeader(
            payload = payload,
            applyTopSafeArea = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
        Column(Modifier.weight(1f).fillMaxWidth().background(RaftColor.Canvas).padding(top = RaftAppSpacing.Sm)) {
            messages.forEach { message ->
                ThreadMessageCard(
                    message = message,
                    messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                    isMarkdownTagValid = { false },
                    timestampLabel = "9:1${message.seq} AM",
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
                payload = RaftThreadComposerPayload(instanceId = "scale-wide", placeholder = "Message #$channelName"),
                onEvent = { _, _, _, _ -> },
                attachmentPreviewEnabled = false,
            )
        }
    }
}

private fun scaleRow(id: String, seq: Long, author: String, body: String, own: Boolean, kind: String = "human") =
    RaftThreadMessageItem(
        id = id,
        authorId = if (own) "user-me" else "member-$author".lowercase(),
        authorName = author,
        authorKind = kind,
        isOwn = own,
        avatarLabel = author.take(1),
        seq = seq,
        timestampLabel = "2026-11-02T09:1$seq:00Z",
        body = body,
    )
