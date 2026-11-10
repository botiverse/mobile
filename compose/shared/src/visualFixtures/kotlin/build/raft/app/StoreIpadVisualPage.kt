package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.base.BasePager
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentCreatorSummary
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentWorkspaceTreeSnapshot
import build.raft.app.data.AppLocale
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.ComputersRackView
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxTaskConversation
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.TaskWireDto
import build.raft.app.data.TasksContent
import build.raft.app.data.TasksPresentationState
import build.raft.app.data.TasksUiState
import build.raft.app.data.TasksViewMode
import build.raft.app.data.ThreadAgentFollower
import build.raft.app.data.ThreadAgentFollowerRoster
import build.raft.app.data.ThreadAgentFollowersState
import build.raft.app.data.applyVisualCaptureLocale
import build.raft.app.data.summaryCounts
import build.raft.app.data.toTaskSummary
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.MessageMention
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftWorkspaceFileKind
import build.raft.app.model.RaftWorkspaceNode
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.initialThreadRepliesReadModel
import build.raft.app.resources.*
import build.raft.designsystem.tokens.RaftThemeId
import kotlinx.coroutines.flow.flowOf

/**
 * App Store iPad 13" screenshots (1.23): the app's wide layout on the iPad Pro 13" simulator
 * (1032x1376pt), with fictional demo content (Northwind Labs; Maya Chen, Leo Park, Priya; agents
 * Ruby, Atlas, Nova, Sage, Echo, Scout, Forge, Pixel) mirroring the approved iPhone 6.9" set. The
 * demo content stays English in every language, as on the iPhone set; the app UI follows `lang`.
 *
 * pageData: `scene` = 01-chat | 02-mention | 03-thread | 04-tasks | 05-agent | 06-activity |
 * 07-computers | 08-home; `lang` = en | zh-Hans | ja. The top 24pt (status bar) and bottom 20pt
 * (home indicator) are left blank: `scripts/store-screenshots/frames.py --set ipad8` crops them and
 * draws the device's own.
 */
@Page("compose_store_ipad_visual", supportInLocal = true)
internal class StoreIpadVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { StoreIpadVisualContent() }
    }
}

private val IpadStatusBar = 24.dp
private val IpadHomeIndicator = 20.dp

// 2026-10-28T09:55:00Z
private val IpadTime = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = 20_754L, nowEpochMillis = 1_793_181_300_000L)

@Composable
private fun StoreIpadVisualContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    val scene = remember { params.optString("scene").trim().ifEmpty { "01-chat" } }
    val lang = remember { params.optString("lang").trim().ifEmpty { "en" } }
    remember(lang) {
        val locale =
            when (lang) {
                "zh-Hans", "zh" -> AppLocale.SimplifiedChinese
                "ja" -> AppLocale.Japanese
                else -> AppLocale.English
            }
        RaftAppThemeState.apply(RaftAppAppearance.Light)
        RaftAppThemeState.setRaftTheme(RaftThemeId.Raft, decor = false)
        locale.applyVisualCaptureLocale()
    }
    val clock = remember { RaftTimeContextProvider(IpadTime) }
    CompositionLocalProvider(
        LocalRaftTimeContextProvider provides clock,
        build.raft.designsystem.components.LocalRaftSpinnerStillStep provides 0,
    ) {
        RaftDesignSystemTheme {
            ThreadReactionAtlasProvider {
                Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                    Box(Modifier.fillMaxWidth().height(IpadStatusBar).background(RaftColor.Canvas))
                    Box(Modifier.weight(1f).fillMaxWidth()) { IpadScene(scene) }
                    Box(Modifier.fillMaxWidth().height(IpadHomeIndicator).background(RaftColor.SurfaceWhite))
                }
            }
        }
    }
}

@Composable
private fun IpadScene(scene: String) {
    val home: @Composable (String?) -> Unit = { selected -> IpadHomeList(Modifier.fillMaxSize(), selected) }
    when (scene) {
        "02-mention" -> IpadShell("home") { IpadSplit(RaftListPaneKind.Sidebar, { home("ch-$IpadChannel") }) { IpadMentionScene() } }
        "03-thread" -> IpadShell("home") { IpadSplit(RaftListPaneKind.Sidebar, { home("ch-$IpadChannel") }) { IpadThreadScene() } }
        "04-tasks" -> IpadShell("tasks") { IpadTasksScene() }
        "05-agent" -> IpadShell("home") { IpadSplit(RaftListPaneKind.Sidebar, { home("dm-agent-forge") }) { IpadAgentScene() } }
        "06-activity" ->
            IpadShell("activity") {
                IpadSplit(RaftListPaneKind.List, { IpadActivityList(selectedKey = "channel:$IpadChannel") }) { IpadChatScene() }
            }
        "07-computers" -> IpadShell("computers") { IpadComputersScene() }
        "08-home" -> IpadShell("home") { IpadSplit(RaftListPaneKind.Sidebar, { home("dm-agent-atlas") }) { IpadDmScene() } }
        else -> IpadShell("home") { IpadSplit(RaftListPaneKind.Sidebar, { home("ch-$IpadChannel") }) { IpadChatScene() } }
    }
}

// ---- Shell: top bar over the rail, list + detail (SettingsHomePage navRailActive, iPad) ------------

private val IpadServers =
    listOf(
        RaftServerSwitcherItem(id = "srv-store", name = "Northwind Labs", slug = "northwind"),
        RaftServerSwitcherItem(id = "srv-side", name = "Side Projects", slug = "side", activityUnreadCount = 2L),
    )

/** [railKey]: home | activity | tasks | members | computers. */
@Composable
private fun IpadShell(railKey: String, content: @Composable () -> Unit) {
    val topBar = remember { RaftAppShellTopBarState() }
    val labels = raftNavLabels(RaftNavPresentation.Rail)
    val searchLabel = stringResource(Res.string.home_nav_search)
    val inlinePage = if (railKey == "computers") RaftAppDestination.Computers.id else null
    val selectedTab = if (inlinePage != null) "home" else railKey
    val items =
        appShellRailTabs(
            selectedTab = selectedTab,
            labelFor = { key -> labels[key] ?: key },
            activityUnreadCount = if (railKey == "activity") 0 else 4,
            inlinePageRailId = inlinePage,
        ).orderedLikePhoneBar { it.key }
    val footer =
        appShellRailFooterTabs(
            selectedTab = if (inlinePage != null) "" else selectedTab,
            labelFor = { key -> labels[key] ?: key },
            helpOpen = false,
        )
    CompositionLocalProvider(LocalRaftAppShellTopBar provides topBar) {
        Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            RaftAppShellTopBar(
                currentServer = IpadServers.first(),
                servers = IpadServers,
                serverMenuOpen = false,
                onWorkspaceClick = {},
                onServerPick = {},
                searchLabel = searchLabel,
                onSearchClick = {},
                windowWidth = 1032.dp,
                bell = topBar,
                history = RaftAppShellTopBarHistory(canGoBack = true, canGoForward = false, onBack = {}, onForward = {}),
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                RaftNavigationRailTabBar(
                    items = items,
                    onSelect = {},
                    modifier = Modifier.fillMaxHeight(),
                    bottomItems = footer,
                )
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    }
}

@Composable
private fun IpadSplit(kind: RaftListPaneKind, list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    RaftAdaptiveListDetailSplit(
        kind = kind,
        widths = remember { RaftListPaneWidths(store = null) },
        dualPane = true,
        active = true,
        hasSelection = true,
        detailBottomInset = 0.dp,
        listPane = { list() },
        detailPane = { modifier -> Box(modifier) { detail() } },
    )
}

// ---- Demo cast ----------------------------------------------------------------------------------

private data class IpadAgent(val id: String, val name: String, val status: AgentStatus, val avatarKey: String, val description: String) {
    val avatarWire: String get() = AvatarReference.Pixel(avatarKey).wireValue
}

private val IpadAgents =
    listOf(
        IpadAgent("agent-ruby", "Ruby", AgentStatus.Working, "boxbot", "Fixes bugs, writes tests"),
        IpadAgent("agent-atlas", "Atlas", AgentStatus.Online, "owl", "Reviews pull requests"),
        IpadAgent("agent-nova", "Nova", AgentStatus.Working, "astronaut", "Builds and ships releases"),
        IpadAgent("agent-sage", "Sage", AgentStatus.Online, "tree", "Keeps the docs current"),
        IpadAgent("agent-echo", "Echo", AgentStatus.Online, "tv", "Answers support tickets"),
        IpadAgent("agent-scout", "Scout", AgentStatus.Thinking, "magnifier", "Tests every change end to end"),
        IpadAgent("agent-forge", "Forge", AgentStatus.Online, "bolt", "Fixes bugs across the web app"),
        IpadAgent("agent-pixel", "Pixel", AgentStatus.Online, "catbot", "Designs the screens"),
    )

private val IpadHumans = mapOf("human-maya" to "Maya Chen", "human-leo" to "Leo Park", "human-priya" to "Priya")

private fun ipadAgent(id: String?) = IpadAgents.firstOrNull { it.id == id }

private fun ipadAvatar(id: String?): String? = ipadAgent(id)?.avatarWire

private fun ipadName(id: String): String = ipadAgent(id)?.name ?: IpadHumans[id] ?: id

private fun ipadInitials(name: String): String = name.split(' ').filter { it.isNotBlank() }.joinToString("") { it.take(1) }.take(1)

private val IpadDescriptions: Map<String, String> = IpadAgents.associate { it.id to it.description }

private val IpadStatusResolver: RaftAgentStatusResolver = { id ->
    flowOf(
        ipadAgent(id)?.let {
            raftAgentStatusIndicatorState(
                status = it.status,
                presence = if (it.status == AgentStatus.Offline) Presence.Offline else Presence.Active,
                statusLabel = it.status.name.lowercase(),
                external = false,
            )
        }
    )
}

private val IpadAvatarResolver =
    RaftMemberAvatarResolver(
        currentAvatar = { id -> ipadAvatar(id) },
        avatarFlow = { id -> flowOf(ipadAvatar(id)) },
    )

/** The agents' model after their name, as on the iPhone set's demo server ("Demo"). */
private val IpadModelLabelResolver =
    RaftMemberModelLabelResolver(
        currentLabel = { id -> if (ipadAgent(id) != null) "Demo" else null },
        labelFlow = { id -> flowOf(if (ipadAgent(id) != null) "Demo" else null) },
    )

// ---- Messages ------------------------------------------------------------------------------------

private data class IpadMessage(
    val authorId: String,
    val body: String,
    val time: String,
    val reactions: List<Pair<String, Int>> = emptyList(),
    val mentions: List<String> = emptyList(),
    val replies: List<Pair<String, String>> = emptyList(),
)

private const val IpadChannel = "ship-mobile"
private const val IpadChannelSubtitle = "Release train for the mobile app"

private val IpadChat =
    listOf(
        IpadMessage("human-priya", "Dark mode mocks for onboarding are up in #design.", "09:20"),
        IpadMessage("agent-pixel", "Exported the assets at 1x, 2x and 3x and attached them to the task.", "09:24", reactions = listOf("👍" to 2)),
        IpadMessage("agent-sage", "Settings docs are updated for 2.4.", "09:31"),
        IpadMessage("human-leo", "Morning all. Code freeze for 2.4 is at noon.", "09:38"),
        IpadMessage("human-maya", "@Ruby can you fix the crash on the settings screen before the 2.4 build?", "09:41", mentions = listOf("agent-ruby")),
        IpadMessage(
            "agent-ruby",
            "Found it: a null `locale` when the device language isn't supported. Fix is up in **PR #482** with a test, CI is green.",
            "09:44",
            reactions = listOf("🎉" to 2, "👍" to 1),
        ),
        IpadMessage(
            "agent-atlas",
            "Reviewed #482, approved. Release notes for 2.4 are drafted in the thread.",
            "09:47",
            replies = listOf("agent-atlas" to "Draft is ready, 6 fixes and 2 features.", "human-maya" to "Looks good, ship it."),
        ),
        IpadMessage("agent-nova", "Build 2.4.0 (318) is uploading to internal testing now.", "09:52", reactions = listOf("🚀" to 3)),
    )

private val IpadThreadOrigin = IpadChat[4]

private val IpadThreadReplies =
    listOf(
        IpadMessage("agent-ruby", "Found it: a null `locale` when the device language isn't supported. Fix and a test are up in **PR #482**.", "09:44"),
        IpadMessage("agent-scout", "Ran the settings suite on Android and iOS: 48 of 48 pass, including the unsupported-language case.", "09:45", reactions = listOf("🎉" to 2)),
        IpadMessage("agent-atlas", "Reviewed #482 and approved.", "09:47"),
        IpadMessage("agent-ruby", "Merged. @Nova it's ready for build 318.", "09:48", mentions = listOf("agent-nova")),
        IpadMessage("agent-nova", "Build 2.4.0 (318) is uploading to internal testing.", "09:52", reactions = listOf("🚀" to 3)),
        IpadMessage("human-maya", "Thanks, everyone. Shipping it to beta testers now.", "09:54", reactions = listOf("🎉" to 2)),
    )

private val IpadDmAtlas =
    listOf(
        IpadMessage("human-maya", "Can you draft the 2.4 release notes from the merged PRs?", "09:12"),
        IpadMessage("agent-atlas", "On it. I'll group them into fixes and features and link each PR.", "09:13"),
        IpadMessage("agent-atlas", "Release notes for 2.4 are ready for your review: 6 fixes and 2 new features.", "09:30"),
        IpadMessage("human-maya", "Nice. Can you call out the dark mode onboarding?", "09:34"),
        IpadMessage("agent-atlas", "Done. It leads the Features section now and links **PR #479**.", "09:36", reactions = listOf("🙌" to 1)),
        IpadMessage("human-maya", "Perfect, thanks!", "09:37"),
        IpadMessage("agent-atlas", "I'll post it in #ship-mobile once build 318 is out.", "09:38"),
    )

private fun ipadMessageItems(messages: List<IpadMessage>, idPrefix: String): List<RaftThreadMessageItem> =
    messages.mapIndexed { index, message ->
        val isAgent = message.authorId.startsWith("agent-")
        val name = ipadName(message.authorId)
        RaftThreadMessageItem(
            id = "$idPrefix-$index",
            authorId = message.authorId,
            authorName = name,
            authorDescription = IpadDescriptions[message.authorId],
            authorKind = if (isAgent) "agent" else "human",
            isOwn = message.authorId == "human-maya",
            avatarLabel = ipadInitials(name),
            avatarWireValue = ipadAvatar(message.authorId),
            seq = index + 1L,
            timestampLabel = "2026-10-28T09:${10 + index}:00Z",
            body = message.body,
            mentions = message.mentions.map { id -> MessageMention(type = "agent", id = id, name = ipadName(id)) },
            reactions =
                message.reactions.mapIndexed { r, (emoji, count) ->
                    RaftThreadReactionItem(emoji = emoji, count = count, selected = r == 0 && count > 1)
                },
            replyCount = message.replies.size,
            inlineReplies =
                message.replies.takeIf { it.isNotEmpty() }?.let { replies ->
                    initialThreadRepliesReadModel(
                        replies =
                            replies.mapIndexed { r, (authorId, text) ->
                                ReplyPreview(
                                    messageId = "r-$idPrefix-$index-$r",
                                    seq = 10L + r,
                                    content = text,
                                    senderId = authorId,
                                    senderType = if (authorId.startsWith("agent-")) "agent" else "user",
                                    senderName = ipadName(authorId).lowercase(),
                                    senderAvatarUrl = ipadAvatar(authorId),
                                    createdAt = "2026-10-28T09:4${8 + r}:00Z",
                                    senderDisplayName = ipadName(authorId),
                                )
                            },
                        replyCount = replies.size,
                        limit = 3,
                    )
                },
        )
    }

@Composable
private fun IpadMessageCard(message: RaftThreadMessageItem, time: String, showReplyAction: Boolean = true) {
    ThreadMessageCard(
        message = message,
        messageBodyTextStyle = raftMessageBodyTextStyle("md"),
        agentStatusResolver = IpadStatusResolver,
        memberAvatarResolver = IpadAvatarResolver,
        memberModelLabelResolver = IpadModelLabelResolver,
        showReplyAction = showReplyAction,
        isMarkdownTagValid = { tag -> tag.kind == ThreadMarkdownTagKind.UserMention },
        timestampLabel = time,
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

/**
 * Bottom-anchored like the app's message list: the longest run from the first child that fits
 * whole above [bottomPadding], the last one ending right above it; the room left stays above.
 */
@Composable
private fun IpadFillColumn(modifier: Modifier, bottomPadding: Dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        val available = constraints.maxHeight - bottomPadding.roundToPx()
        // Keep the newest messages: drop from the top until the rest fits.
        var first = 0
        var used = placeables.sumOf { it.height }
        while (first < placeables.size - 1 && used > available) {
            used -= placeables[first].height
            first++
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            var y = (available - used).coerceAtLeast(0)
            for (i in first until placeables.size) {
                placeables[i].place(0, y)
                y += placeables[i].height
            }
        }
    }
}

@Composable
private fun IpadMessageList(messages: List<IpadMessage>, modifier: Modifier, idPrefix: String) {
    val items = remember(messages) { ipadMessageItems(messages, idPrefix) }
    Box(modifier.clipToBounds().background(RaftColor.SurfaceWhite)) {
        IpadFillColumn(Modifier.fillMaxSize(), bottomPadding = 12.dp) {
            items.forEachIndexed { index, message -> IpadMessageCard(message, messages[index].time) }
        }
    }
}

@Composable
private fun IpadChannelHeader() {
    val payload =
        remember {
            RaftThreadScreenPayload(
                instanceId = "ipad-chat",
                sourceThreadId = IpadChannel,
                title = "#$IpadChannel",
                subtitle = IpadChannelSubtitle,
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                selectedTab = "chat",
                showModeTabs = true,
                showSettingsAction = true,
                showMembersAction = true,
                membersCount = 11,
                showOpenInChannelAction = false,
            )
        }
    ThreadPageHeader(
        payload = payload,
        headerDisplay =
            ThreadHeaderDisplayModel(
                title = IpadChannel,
                subtitle = IpadChannelSubtitle,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
            ),
        agentStatusResolver = IpadStatusResolver,
        memberAvatarResolver = IpadAvatarResolver,
        applyTopSafeArea = false,
        showBack = false,
        onBack = {},
        onOpenInChannel = {},
        onOpenMemberProfile = {},
        onOpenSearch = {},
        onShowMembers = {},
        onShowSettings = {},
    )
    RaftTopTabBar(
        items =
            RaftThreadTab.entries.map { tab ->
                RaftTabSpec(
                    key = tab,
                    label =
                        when (tab) {
                            RaftThreadTab.Chat -> stringResource(Res.string.thread_tab_chat)
                            RaftThreadTab.Tasks -> stringResource(Res.string.thread_tab_tasks)
                            RaftThreadTab.Files -> stringResource(Res.string.thread_tab_files)
                        },
                    iconName =
                        when (tab) {
                            RaftThreadTab.Chat -> RaftLucideIconName.MessageSquare
                            RaftThreadTab.Tasks -> RaftLucideIconName.ListTodo
                            RaftThreadTab.Files -> RaftLucideIconName.Paperclip
                        },
                ).toTopTabBarItem()
            },
        selectedKey = RaftThreadTab.Chat.toString(),
        onSelectedKeyChange = {},
        itemKeyPrefix = "thread-tab",
    )
}

@Composable
private fun IpadComposer(placeholder: String, surfaceKind: String = RaftThreadSurfaceKind.Channel) {
    ThreadComposerDock {
        ThreadComposerContent(
            payload =
                RaftThreadComposerPayload(
                    instanceId = "ipad-dock",
                    placeholder = placeholder,
                    surfaceKind = surfaceKind,
                    showAsTaskToggle = surfaceKind == RaftThreadSurfaceKind.Channel,
                ),
            onEvent = { _, _, _, _ -> },
            attachmentPreviewEnabled = false,
        )
    }
}

// ---- 1. Team chat ---------------------------------------------------------------------------------

@Composable
private fun IpadChatScene() {
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        IpadChannelHeader()
        IpadMessageList(IpadChat, Modifier.weight(1f).fillMaxWidth(), "m")
        IpadComposer(stringResource(Res.string.thread_composer_placeholder_channel, IpadChannel))
    }
}

// ---- 2. @mention: the composer's member picker over the list ----------------------------------------

@Composable
private fun IpadMentionScene() {
    val placeholder = stringResource(Res.string.thread_composer_placeholder_channel, IpadChannel)
    val payload =
        remember {
            RaftThreadComposerPayload(
                instanceId = "ipad-mention",
                placeholder = placeholder,
                seedText = "@",
                mentionChannelId = IpadChannel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                showAsTaskToggle = true,
            )
        }
    val members =
        remember {
            listOf("agent-forge", "agent-ruby", "agent-atlas", "agent-nova", "agent-scout", "agent-sage").map { id ->
                val agent = ipadAgent(id)!!
                RaftComposerMemberItem(
                    displayName = agent.name,
                    handle = agent.name,
                    kind = MemberKind.Agent,
                    description = agent.description,
                    avatarWireValue = agent.avatarWire,
                    memberId = agent.id,
                )
            }
        }
    val surface = remember { payload.toThreadComposerSurface(channelId = IpadChannel, mentionChannelLabel = IpadChannel) }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        IpadChannelHeader()
        Box(Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite)) {
            var popupTop by remember { mutableStateOf<Int?>(null) }
            val items = remember { ipadMessageItems(IpadChat, "mc") }
            // The list as in the chat, bottom-anchored above the picker's top edge.
            Box(Modifier.fillMaxSize().clipToBounds()) {
                IpadFillColumn(Modifier.fillMaxSize(), bottomPadding = 0.dp) {
                    items.forEachIndexed { index, message -> IpadMessageCard(message, IpadChat[index].time) }
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = RaftAppSpacing.Sm)
                    .padding(bottom = 8.dp)
                    .onGloballyPositioned { popupTop = it.positionInParent().y.toInt() },
            ) {
                ComposerSuggestionPanelContent(
                    panelState = ThreadComposerSuggestionPanelState.MemberItems,
                    surface = surface,
                    memberSuggestions = members,
                    memberSelectedIndex = 0,
                    memberErrorCode = null,
                    channelSuggestions = emptyList(),
                    channelSelectedIndex = 0,
                    channelErrorCode = null,
                    agentStatusResolver = IpadStatusResolver,
                    onMemberClick = {},
                    onChannelClick = {},
                )
            }
        }
        ThreadComposerDock {
            ThreadComposerContent(payload = payload, onEvent = { _, _, _, _ -> }, attachmentPreviewEnabled = false)
        }
    }
}

// ---- 3. A reply thread: agents hand off, review and ship ---------------------------------------------

@Composable
private fun IpadThreadScene() {
    val origin = remember { ipadMessageItems(listOf(IpadThreadOrigin), "origin").single() }
    val replies = IpadThreadReplies
    val payload =
        remember {
            RaftThreadScreenPayload(
                instanceId = "ipad-thread",
                sourceThreadId = "t-482",
                title = ThreadReplyPageTitle,
                subtitle = "#$IpadChannel",
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                selectedTab = "chat",
                showModeTabs = false,
                showSettingsAction = false,
                showMembersAction = false,
                showOpenInChannelAction = true,
            )
        }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        ThreadPageHeader(
            agentFollowersHeader = {
                ThreadAgentFollowersHeader(
                    state =
                        ThreadAgentFollowersState(
                            roster =
                                ThreadAgentFollowerRoster(
                                    threadChannelId = "t-482",
                                    canManage = false,
                                    agents =
                                        listOf("agent-ruby", "agent-scout", "agent-atlas", "agent-nova").map { id ->
                                            ThreadAgentFollower(
                                                id = id,
                                                name = ipadName(id).lowercase(),
                                                displayName = ipadName(id),
                                                status = "online",
                                                avatarUrl = ipadAvatar(id),
                                                serverId = "srv-store",
                                                isCurrentServer = true,
                                                canRemove = false,
                                            )
                                        },
                                ),
                        ),
                    dispatch = {},
                    agentStatusResolver = IpadStatusResolver,
                )
            },
            payload = payload,
            headerDisplay =
                ThreadHeaderDisplayModel(
                    title = ThreadReplyPageTitle,
                    subtitle = "#$IpadChannel",
                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                    channelKind = "channel",
                ),
            originMessage = origin,
            originReplyCount = replies.size,
            agentStatusResolver = IpadStatusResolver,
            memberAvatarResolver = IpadAvatarResolver,
            applyTopSafeArea = false,
            showBack = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
        val items = remember { ipadMessageItems(replies, "reply") }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(RaftColor.SurfaceWhite)) {
            IpadFillColumn(Modifier.fillMaxSize(), bottomPadding = 12.dp) {
                Column(Modifier.fillMaxWidth()) {
                    IpadMessageCard(origin, IpadThreadOrigin.time, showReplyAction = false)
                    Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderSeam))
                    ThreadHistoryStatusRow(threadOlderMessagesStatusSpec(canLoadMoreBefore = false, secondaryReplyCount = replies.size))
                    ThreadDateDivider(stringResource(Res.string.thread_date_divider_today))
                }
                items.forEachIndexed { index, message -> IpadMessageCard(message, replies[index].time, showReplyAction = false) }
            }
        }
        IpadComposer(stringResource(Res.string.thread_composer_placeholder_reply), RaftThreadSurfaceKind.ReplyThread)
    }
}

// ---- 4. Tasks board -------------------------------------------------------------------------------

private data class IpadTask(val channel: String, val number: Int, val title: String, val status: String, val assigneeId: String?, val createdAt: String)

private val IpadTasks =
    listOf(
        IpadTask(IpadChannel, 2, "Write the 2.4 release notes", "todo", "agent-atlas", "2026-10-28T09:40:00Z"),
        IpadTask(IpadChannel, 1, "Add dark mode to the onboarding screens", "todo", null, "2026-10-28T09:40:00Z"),
        IpadTask("web-app", 1, "Can someone clean up the unused feature flags before the release?", "todo", "agent-forge", "2026-10-26T09:00:00Z"),
        IpadTask(IpadChannel, 4, "Migrate push notifications to the new API", "in_progress", "agent-nova", "2026-10-27T14:00:00Z"),
        IpadTask(IpadChannel, 7, "Find the flaky tests in the checkout suite", "in_progress", "agent-scout", "2026-10-27T23:00:00Z"),
        IpadTask(IpadChannel, 5, "Speed up cold start on older phones", "in_review", "agent-ruby", "2026-10-27T10:00:00Z"),
        IpadTask(IpadChannel, 3, "Fix crash on the settings screen", "done", "agent-ruby", "2026-10-28T09:41:00Z"),
        IpadTask(IpadChannel, 6, "Translate the app into Japanese", "done", "agent-sage", "2026-10-27T08:00:00Z"),
    )

private fun ipadTaskSummaries(): List<TaskSummary> =
    IpadTasks.map { task ->
        TaskWireDto(
            id = "task-${task.channel}-${task.number}",
            messageId = "task-${task.channel}-${task.number}",
            channelId = task.channel,
            channelName = task.channel,
            channelType = "channel",
            taskNumber = task.number,
            title = task.title,
            status = task.status,
            createdByType = "user",
            createdById = "human-maya",
            createdByName = "Maya Chen",
            claimedByType = task.assigneeId?.let { "agent" },
            claimedById = task.assigneeId,
            claimedByName = task.assigneeId?.let(::ipadName),
            claimedAt = task.assigneeId?.let { task.createdAt },
            completedAt = if (task.status == "done") "2026-10-28T09:50:00Z" else null,
            createdAt = task.createdAt,
            updatedAt = task.createdAt,
        ).toTaskSummary() ?: error("store task ${task.channel}#${task.number} must map")
    }

@Composable
private fun IpadTasksScene() {
    val tasks = remember { ipadTaskSummaries() }
    Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedTasksPanel(
            state = TasksUiState(loadState = ScreenLoadState.Content(TasksContent(summaryCounts = tasks.summaryCounts(), tasks = tasks))),
            onIntent = {},
            showHeader = true,
            showChannelFilter = true,
            initialPresentationOverride =
                TasksPresentationState.Default.copy(selectedMode = TasksViewMode.Board, collapsedStatuses = setOf(TaskStatus.Closed)),
            timeContext = IpadTime,
        )
    }
}

// ---- 5. Agent profile -------------------------------------------------------------------------------

private val IpadMachines =
    listOf(
        ComputerSummary(
            id = "machine-mini",
            name = "Mac mini",
            status = ComputerStatus.Online,
            statusVersion = 0L,
            apiKeyPrefix = null,
            runtimes = listOf("claude", "codex", "pi"),
            daemonVersion = "0.65.0",
            computerVersion = "1.4.2",
            isComputer = true,
            hostname = "mini.local",
            os = "macOS 15.1",
            description = null,
            lastHeartbeat = null,
            createdAt = null,
            agentCount = 4,
        )
    )

@Composable
private fun IpadAgentScene() {
    val forge = ipadAgent("agent-forge")!!
    val member =
        remember {
            MemberProfile(
                id = MemberId(forge.id),
                displayName = forge.name,
                handle = "@Forge",
                kind = MemberKind.Agent,
                description = forge.description,
                avatar = AvatarReference.Pixel(forge.avatarKey),
                presence = Presence.Active,
                agentStatus = AgentStatus.Online,
                serverRole = "member",
            )
        }
    val snapshot =
        remember {
            AgentDetailSnapshot(
                agentId = member.id,
                summary =
                    AgentDetailSummary(
                        id = forge.id,
                        name = "Forge",
                        displayName = forge.name,
                        description = forge.description,
                        serverRole = "member",
                        createdAt = "2026-10-28T02:30:00.000Z",
                        status = "active",
                        runtime = "pi",
                        model = "demo",
                        executionMode = "byoc",
                        machineId = "machine-mini",
                        machineName = "Mac mini",
                        daemonVersion = "0.65.0",
                        creatorId = "human-maya",
                        creatorType = "user",
                        creator = AgentCreatorSummary(type = "human", id = "human-maya", name = "maya", displayName = "Maya Chen"),
                        activity = "online",
                    ),
                directMessages = emptyList(),
                reminders = emptyList(),
                activity = emptyList(),
            )
        }
    val tree =
        remember {
            AgentWorkspaceTreeSnapshot(
                agentId = member.id,
                rootPath = "~/agents/forge",
                root =
                    RaftWorkspaceNode(
                        id = "~/agents/forge",
                        name = "forge",
                        path = "~/agents/forge",
                        kind = RaftWorkspaceFileKind.Folder,
                        children =
                            listOf(
                                RaftWorkspaceNode(id = "MEMORY.md", name = "MEMORY.md", path = "MEMORY.md", kind = RaftWorkspaceFileKind.Markdown, meta = "4.8 KB"),
                                RaftWorkspaceNode(id = "notes", name = "notes", path = "notes", kind = RaftWorkspaceFileKind.Folder, meta = "folder"),
                            ),
                    ),
            )
        }
    MembersAgentDetailStoreContent(
        payload =
            RaftMemberDetailPayload(
                instanceId = "store-ipad-forge",
                initialAgentTab = "profile",
                localOffsetMinutes = IpadTime.localOffsetMinutes,
                todayEpochDay = IpadTime.todayEpochDay,
                nowEpochMillis = IpadTime.nowEpochMillis,
            ),
        member = member,
        snapshot = snapshot,
        machines = IpadMachines,
        workspaceTree = tree,
    )
}

// ---- 6. Activity -----------------------------------------------------------------------------------

private fun ipadTaskThread(number: Int, title: String, status: TaskStatus, unread: Int, senderId: String, preview: String, at: String): InboxItem {
    val key = "t-$number"
    return InboxItem(
        key = "thread:$key",
        kind = InboxItemKind.Thread,
        channelId = ChannelId(key),
        channelName = IpadChannel,
        unreadCount = unread,
        firstUnreadMessageId = if (unread > 0) MessageId("first-$key") else null,
        threadChannelId = ChannelId(key),
        parentChannelId = ChannelId(IpadChannel),
        parentChannelName = IpadChannel,
        parentChannelKind = ConversationKind.Channel,
        parentMessagePreview = "#$IpadChannel:#$number $title",
        latestActivityPreview = preview,
        latestActivitySenderName = ipadName(senderId),
        latestActivitySenderId = senderId,
        latestActivitySenderKind = MemberKind.Agent,
        latestActivitySenderAvatarWireValue = ipadAvatar(senderId),
        latestActivityAt = at,
        isFollowing = true,
        taskNumber = number,
        taskStatus = status,
        taskConversation = InboxTaskConversation(taskId = TaskId("task-$key"), taskTitle = title),
    )
}

private fun ipadChannelRow(channel: String, unread: Int, mention: Boolean, senderId: String, preview: String, at: String) =
    InboxItem(
        key = "channel:$channel",
        kind = InboxItemKind.Channel,
        channelId = ChannelId(channel),
        channelName = channel,
        unreadCount = unread,
        hasMention = mention,
        lastMessageSenderName = ipadName(senderId),
        lastMessageSenderId = senderId,
        lastMessageSenderKind = MemberKind.Agent,
        lastMessageSenderAvatarWireValue = ipadAvatar(senderId),
        lastMessagePreview = preview,
        lastMessageAt = at,
    )

private fun ipadDmRow(agentId: String, unread: Int, preview: String, at: String) =
    InboxItem(
        key = "dm:$agentId",
        kind = InboxItemKind.DirectMessage,
        channelId = ChannelId("dm-$agentId"),
        channelName = ipadName(agentId),
        channelKind = ConversationKind.DirectMessage,
        unreadCount = unread,
        lastMessageSenderName = ipadName(agentId),
        lastMessageSenderId = agentId,
        lastMessageSenderKind = MemberKind.Agent,
        lastMessageSenderAvatarWireValue = ipadAvatar(agentId),
        lastMessagePreview = preview,
        lastMessageAt = at,
    )

private val IpadActivityRows =
    listOf(
        ipadChannelRow(IpadChannel, 3, true, "agent-nova", "Build 2.4.0 (318) is live in internal testing.", "2026-10-28T09:54:00Z"),
        ipadTaskThread(3, "Fix crash on the settings screen", TaskStatus.Done, 1, "agent-ruby", "Fix and test are in PR #482.", "2026-10-28T09:54:00Z"),
        ipadDmRow("agent-atlas", 6, "Release notes are ready for your review.", "2026-10-28T09:54:00Z"),
        ipadTaskThread(4, "Migrate push notifications to the new API", TaskStatus.InProgress, 1, "agent-nova", "Android is switched over, iOS is next.", "2026-10-28T09:54:00Z"),
        ipadChannelRow("support", 3, false, "agent-echo", "Answered all 12 tickets today, 2 handed to Ruby.", "2026-10-28T09:54:00Z"),
        ipadTaskThread(6, "Translate the app into Japanese", TaskStatus.Done, 0, "agent-sage", "Merged, screenshots updated too.", "2026-10-28T09:54:00Z"),
        ipadDmRow("agent-echo", 1, "Answered all 12 support tickets today, 2 handed to Ruby.", "2026-10-27T23:50:00Z"),
        ipadDmRow("agent-scout", 1, "Found 3 flaky tests in the checkout suite and opened a task for each.", "2026-10-27T23:40:00Z"),
        ipadDmRow("agent-forge", 1, "The unused feature flags are gone, PR is up for review.", "2026-10-27T23:30:00Z"),
        ipadChannelRow("design", 0, false, "agent-pixel", "Onboarding mocks are exported for all three sizes.", "2026-10-27T22:00:00Z"),
    )

@Composable
private fun IpadActivityList(selectedKey: String?) {
    val rows = IpadActivityRows
    val context =
        remember {
            ActivityConversationListContext(
                channelSymbolsByChannelId = mapOf(IpadChannel to "#", "support" to "#", "design" to "#"),
                dmPeersByChannelId =
                    listOf("agent-atlas", "agent-echo", "agent-scout", "agent-forge").associate { id ->
                        "dm-$id" to
                            ActivityConversationDmPeer(
                                peerId = id,
                                peerIsAgent = true,
                                avatarWireValue = ipadAvatar(id),
                                displayName = ipadName(id),
                                activityMuted = false,
                            )
                    },
            )
        }
    Column(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        SharedActivityHeader(
            totalCount = 9,
            totalUnreadCount = 16,
            onClose = null,
            topSafeAreaPadding = 0.dp,
            refreshing = false,
            mainTab = true,
        )
        SharedActivityFilterBar(selectedFilter = InboxFilter.All, totalUnreadCount = 16, onFilterSelected = {}, onMarkAllRead = {})
        Column(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            rows.forEachIndexed { index, item ->
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.All),
                    divider = index > 0,
                    selected = item.key == selectedKey,
                    timeContext = IpadTime,
                    agentStatusResolver = IpadStatusResolver,
                    canMarkDone = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth(),
                    onSwipeDone = {},
                    swipeEndAction = item.activitySwipeEndAction(emptyMap()),
                    onSwipeEnd = {},
                    onSwipeToggleRead = {},
                )
            }
        }
    }
}

// ---- 7. Computers -------------------------------------------------------------------------------------

private data class IpadComputer(val id: String, val name: String, val host: String, val platform: String, val agents: List<String>, val online: Boolean = true)

private val IpadComputers =
    listOf(
        IpadComputer("machine-mini", "Mac mini", "mini.local", "macOS 15.1", listOf("agent-ruby", "agent-atlas", "agent-forge", "agent-sage")),
        IpadComputer("machine-studio", "Studio Mac", "studio.local", "macOS 15.1", listOf("agent-nova", "agent-pixel")),
        IpadComputer("machine-build", "build-server", "build-01", "Ubuntu 24.04", listOf("agent-scout", "agent-echo")),
        IpadComputer("machine-laptop", "Travel laptop", "laptop.local", "macOS 15.0", emptyList(), online = false),
    )

@Composable
private fun IpadComputersScene() {
    val snapshot =
        remember {
            SettingsComputersSnapshot(
                computers =
                    IpadComputers.map { computer ->
                        SettingsComputerSnapshot(
                            id = computer.id,
                            name = computer.name,
                            daemonVersion = "",
                            computerVersion = "1.4.2",
                            isComputer = true,
                            online = computer.online,
                            hostname = computer.host,
                            platform = computer.platform,
                            runtimes = listOf("claude", "codex"),
                            agentCount = computer.agents.size,
                            lastHeartbeat = if (computer.online) null else "2026-10-27T18:00:00Z",
                        )
                    },
                canAddComputer = true,
                latestComputerVersion = "1.4.2",
            )
        }
    val agentsByMachine =
        remember {
            IpadComputers.associate { computer ->
                computer.id to computer.agents.map { id -> RaftComputerCardAgent(id = id, name = ipadName(id), avatarWireValue = ipadAvatar(id)) }
            }
        }
    val model = remember { raftComputersListModel(ScreenLoadState.Content(snapshot)) }
    val title = stringResource(Res.string.settings_computers_title)
    CompositionLocalProvider(LocalRaftSettingsWideDetail provides true) {
        SettingsDetailShell(
            title = title,
            subtitle = "",
            applyTopSafeArea = false,
            onBack = {},
            titleContent =
                if (model is RaftComputersListModel.Content) {
                    { RaftComputersWideTitle(title, raftComputersCountsLine(raftComputersCounts(model.rows, agentsByMachine))) }
                } else {
                    null
                },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RaftComputersRackViewToggle(ComputersRackView.Cards) {}
                    Box(Modifier.padding(start = 8.dp)) { RaftComputersAddButton {} }
                }
            },
        ) {
            RaftComputersTheme {
                RaftSettingsComputersContent(
                    model = model,
                    onRetry = {},
                    onAddComputer = {},
                    onOpenComputer = {},
                    agentsByMachine = agentsByMachine,
                    agentStatusResolver = IpadStatusResolver,
                    rackView = ComputersRackView.Cards,
                    summaryInHeader = true,
                )
            }
        }
    }
}

// ---- 8. Home: the sidebar, with a DM open -------------------------------------------------------------

private val IpadChannels = listOf("all", "general", "design", "support", "growth", "web-app", IpadChannel)

@Composable
private fun IpadHomeList(modifier: Modifier, selectedChannelId: String?) {
    val channels =
        remember {
            IpadChannels.map { name ->
                RaftHomeConversationItem(
                    id = "ch-$name",
                    name = name,
                    kind = "channel",
                    unreadCount = if (name == "support") 3 else 0,
                    activityMuteSupported = true,
                )
            }
        }
    val dms =
        remember {
            listOf("agent-atlas" to 2, "agent-echo" to 1, "agent-scout" to 1, "agent-forge" to 0, "agent-ruby" to 0, "agent-nova" to 0).map { (id, unread) ->
                val agent = ipadAgent(id)!!
                RaftHomeConversationItem(
                    id = "dm-$id",
                    name = agent.name,
                    description = agent.description,
                    kind = RaftThreadSurfaceKind.DirectMessage,
                    unreadCount = unread,
                    avatarLabel = agent.name.take(1),
                    avatarWireValue = agent.avatarWire,
                    peerType = "agent",
                    presenceMemberId = id,
                )
            }
        }
    RaftStructuralHomeShell(
        modifier = modifier,
        currentServerName = "Northwind Labs",
        currentServerId = "srv-store",
        servers = IpadServers,
        channels = channels,
        directMessages = dms,
        labels = RaftHomeShellLabels.localized(),
        savedCount = 4,
        agentStatusResolver = IpadStatusResolver,
        memberAvatarResolver = IpadAvatarResolver,
        contentRefreshEnabled = false,
        selectedTabKey = "home",
        selectedChannelId = selectedChannelId,
        showBottomBar = false,
        serverSwitcherInTopBar = true,
        applyTopSafeArea = false,
        topSafeAreaOverride = 0.dp,
        bottomContentPadding = 0.dp,
        searchAndActivityOnShellNav = true,
        outerPadding = 0.dp,
    )
}

@Composable
private fun IpadDmScene() {
    val atlas = ipadAgent("agent-atlas")!!
    val payload =
        remember {
            RaftThreadScreenPayload(
                instanceId = "ipad-dm",
                sourceThreadId = "dm-agent-atlas",
                title = atlas.name,
                subtitle = atlas.description,
                status = "content",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                selectedTab = "chat",
                showModeTabs = false,
                showSettingsAction = false,
                showMembersAction = false,
                showOpenInChannelAction = false,
            )
        }
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        ThreadPageHeader(
            payload = payload,
            headerDisplay =
                ThreadHeaderDisplayModel(
                    title = atlas.name,
                    subtitle = atlas.description,
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    channelKind = "dm",
                    dmPeerAvatarWireValue = atlas.avatarWire,
                    dmPeerAvatarLabel = "A",
                ),
            agentStatusResolver = IpadStatusResolver,
            memberAvatarResolver = IpadAvatarResolver,
            applyTopSafeArea = false,
            showBack = false,
            onBack = {},
            onOpenInChannel = {},
            onOpenMemberProfile = {},
            onOpenSearch = {},
            onShowMembers = {},
            onShowSettings = {},
        )
        IpadMessageList(IpadDmAtlas, Modifier.weight(1f).fillMaxWidth(), "dm")
        IpadComposer(stringResource(Res.string.thread_composer_placeholder_dm, atlas.name), RaftThreadSurfaceKind.DirectMessage)
    }
}
